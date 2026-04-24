package com.vn.agent.audit;

import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outermost {@link CallAdvisor} (ORCH-02): sits at {@link Ordered#HIGHEST_PRECEDENCE} so it
 * wraps every other advisor (memory, tool-calling) and records a chat-level PRE row before the
 * chain runs and a POST row in {@code finally} with latency + errorClass (AUD-01).
 *
 * <p><b>No {@code @Transactional} annotation.</b> REQUIRES_NEW durability comes from the
 * INJECTED {@link AuditWriter} — the advisor never self-invokes a transactional method on
 * itself (Pitfall #3 proxy-self-invocation is closed because this class has no transactional
 * methods at all).
 *
 * <p>Seeds {@link RunContext} with the runId so the {@code ToolCallbackAuditDecorator} can
 * correlate per-tool rows against the chat-level rows. Always cleared in {@code finally}.
 *
 * <p>B8: runId is preferentially read from the advisor context key {@code audit.runId}
 * (pre-allocated by {@code DefaultChatServiceImpl} in Plan 04-04). Falls back to a fresh UUID
 * only when the key is absent or malformed — this keeps tests (which invoke the advisor
 * directly, without service-layer pre-allocation) working.
 */
@Component
public class AuditAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AuditAdvisor.class);

    /** Context key the service layer writes the pre-allocated runId under (B8). */
    public static final String RUN_ID_CONTEXT_KEY = "audit.runId";

    /** Spring AI ChatMemory conversation-id context key. */
    public static final String CONVERSATION_ID_CONTEXT_KEY = "chat_memory_conversation_id";

    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditAdvisor(AuditWriter auditWriter, CurrentAuthentication currentAuthentication) {
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override
    @NonNull
    public String getName() {
        return "AuditAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    @NonNull
    public ChatClientResponse adviseCall(@NonNull ChatClientRequest request, @NonNull CallAdvisorChain chain) {
        Map<String, Object> context = request.context();
        Object preAllocated = context.get(RUN_ID_CONTEXT_KEY);
        UUID runId = (preAllocated instanceof UUID uuid) ? uuid : UUID.randomUUID();

        RunContext.set(runId);
        String userUsername = resolveUserUsername();
        UUID conversationId = extractConversationId(context);
        String promptHash = hashPrompt(request);
        long startNanos = System.nanoTime();
        String errorClass = null;
        UUID rootAuditId = null;

        try {
            rootAuditId = auditWriter.writeChatStart(runId, userUsername, conversationId, promptHash);
            RunContext.setRootAuditId(rootAuditId);   // D-10: advertise to downstream hooks (tool decorator + retrieval)
            return chain.nextCall(request);
        } catch (Throwable t) {
            errorClass = t.getClass().getSimpleName();   // D-02: simpleName, not fully-qualified
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String outcome = errorClass == null ? "SUCCESS" : "ERROR";
            try {
                if (rootAuditId != null) {
                    auditWriter.writeChatFinish(rootAuditId, latencyMs, outcome, errorClass);
                }
            } catch (Throwable t2) {
                log.warn("writeChatFinish failed for rootAuditId={}", rootAuditId, t2);
            }
            RunContext.clear();   // Pitfall #2: clear AFTER finish — tool/retrieval hooks run inside chain.nextCall
        }
    }

    private String resolveUserUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            return user != null ? user.getUsername() : "anonymous";
        } catch (RuntimeException anon) {
            return "anonymous";
        }
    }

    private static UUID extractConversationId(Map<String, Object> context) {
        Object raw = context.get(CONVERSATION_ID_CONTEXT_KEY);
        if (raw == null) return null;
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hashPrompt(ChatClientRequest request) {
        String text = null;
        try {
            List<Message> instructions = request.prompt().getInstructions();
            // Prefer last USER message for hashing (the actual input being sent this turn).
            for (int i = instructions.size() - 1; i >= 0; i--) {
                Message m = instructions.get(i);
                if (m.getMessageType() == MessageType.USER) {
                    text = m.getText();
                    break;
                }
            }
            if (text == null && !instructions.isEmpty()) {
                text = instructions.getLast().getText();
            }
            // LO-03: never fall back to Prompt#toString() — it embeds framework metadata
            // (options, tool-call results) and would break the "same user text => same hash"
            // invariant. Zero instructions => hash over the empty string.
            if (text == null) {
                text = "";
            }
        } catch (RuntimeException e) {
            text = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return hex.length() > 64 ? hex.substring(0, 64) : hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of every JRE — defensive only.
            return null;
        }
    }
}
