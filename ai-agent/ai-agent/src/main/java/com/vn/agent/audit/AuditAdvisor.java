package com.vn.agent.audit;

import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outermost chat advisor: records one CHAT root row around both blocking and streaming turns.
 * Child tool/retrieval audit hooks read {@link RunContext} while the downstream advisor chain runs.
 */
@Component
public class AuditAdvisor implements CallAdvisor, StreamAdvisor {

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
        AuditEnvelope envelope = openEnvelope(request);
        try {
            return chain.nextCall(request);
        } catch (Throwable t) {
            envelope.errorClass = t.getClass().getSimpleName();
            throw t;
        } finally {
            closeEnvelope(envelope);
        }
    }

    @Override
    @NonNull
    public Flux<ChatClientResponse> adviseStream(
            @NonNull ChatClientRequest request,
            @NonNull StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AuditEnvelope envelope = openEnvelope(request);
            Flux<ChatClientResponse> stream = chain.nextStream(request)
                    .doOnError(t -> envelope.errorClass = t.getClass().getSimpleName());
            return new ChatClientMessageAggregator()
                    .aggregateChatClientResponse(stream, ignored -> closeEnvelope(envelope))
                    .doFinally(ignored -> closeEnvelope(envelope));
        });
    }

    private AuditEnvelope openEnvelope(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        Object preAllocated = context.get(RUN_ID_CONTEXT_KEY);
        UUID runId = (preAllocated instanceof UUID uuid) ? uuid : UUID.randomUUID();
        String userUsername = resolveUserUsername();
        UUID conversationId = extractConversationId(context);

        RunContext.set(runId);
        RunContext.setConversationId(conversationId);
        UUID rootAuditId = auditWriter.writeChatStart(runId, userUsername, conversationId, hashPrompt(request));
        RunContext.setRootAuditId(rootAuditId);
        return new AuditEnvelope(rootAuditId, System.nanoTime());
    }

    private void closeEnvelope(AuditEnvelope envelope) {
        if (!envelope.closed.compareAndSet(false, true)) {
            return;
        }
        long latencyMs = (System.nanoTime() - envelope.startNanos) / 1_000_000L;
        String outcome = envelope.errorClass == null ? "SUCCESS" : "ERROR";
        try {
            if (envelope.rootAuditId != null) {
                auditWriter.writeChatFinish(envelope.rootAuditId, latencyMs, outcome, envelope.errorClass);
            }
        } catch (Throwable t2) {
            log.warn("writeChatFinish failed for rootAuditId={}", envelope.rootAuditId, t2);
        }
        RunContext.clear();
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
            for (int i = instructions.size() - 1; i >= 0; i--) {
                Message message = instructions.get(i);
                if (message.getMessageType() == MessageType.USER) {
                    text = message.getText();
                    break;
                }
            }
            if (text == null && !instructions.isEmpty()) {
                text = instructions.getLast().getText();
            }
            if (text == null) {
                text = "";
            }
        } catch (RuntimeException e) {
            text = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
            return hex.length() > 64 ? hex.substring(0, 64) : hex;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static final class AuditEnvelope {
        private final UUID rootAuditId;
        private final long startNanos;
        private String errorClass;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AuditEnvelope(UUID rootAuditId, long startNanos) {
            this.rootAuditId = rootAuditId;
            this.startNanos = startNanos;
        }
    }
}
