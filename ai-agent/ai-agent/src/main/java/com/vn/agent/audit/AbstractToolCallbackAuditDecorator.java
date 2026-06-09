package com.vn.agent.audit;

import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.publisher.Sinks;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared base for the two {@link ToolCallback} audit decorators —
 * {@link ToolCallbackAuditDecorator} (generic read/link/contributor callbacks) and
 * {@link MutationToolCallbackBoundaryDecorator} (self-auditing mutation callbacks).
 *
 * <p>Holds the LOB-cap constants and the tool-context / streaming-sink / identity helpers both
 * decorators share. Subclasses implement only their distinct {@link #callInternal} body.
 *
 * <p><b>Not a Spring @Component.</b> Instances are created per-callback by
 * {@code AgentToolCallbacks}. No {@code @Transactional} here — the durability guarantee comes
 * from the injected {@link AuditWriter} REQUIRES_NEW proxy, not from the decorator.
 */
abstract class AbstractToolCallbackAuditDecorator implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AbstractToolCallbackAuditDecorator.class);

    /** Cap on {@code argumentsJson} (model-supplied tool input) written into the LOB column (MD-04). */
    static final int ARGUMENTS_JSON_MAX_CHARS = 4096;

    /** Cap on {@code resultSummary} written into the LOB column — keeps rows reasonable. */
    static final int RESULT_SUMMARY_MAX_CHARS = 4096;

    /** Suffix appended when a captured value is truncated, so the truncation is observable. */
    static final String TRUNCATION_SUFFIX = "…[truncated]";

    protected final ToolCallback delegate;
    protected final AuditWriter auditWriter;
    protected final CurrentAuthentication currentAuthentication;
    protected final StreamingSinkHolder streamingSinkHolder;

    protected AbstractToolCallbackAuditDecorator(ToolCallback delegate,
                                                 AuditWriter auditWriter,
                                                 CurrentAuthentication currentAuthentication,
                                                 StreamingSinkHolder streamingSinkHolder) {
        this.delegate = delegate;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.streamingSinkHolder = streamingSinkHolder;
    }

    @Override
    @NonNull
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput) {
        return callInternal(toolInput, null, false);
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput, ToolContext toolContext) {
        return callInternal(toolInput, toolContext, true);
    }

    /** Subclass audit + streaming body for a single tool invocation. */
    protected abstract String callInternal(String toolInput, ToolContext toolContext, boolean useContextOverload);

    static String cap(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + TRUNCATION_SUFFIX;
    }

    static UUID resolveRunId(ToolContext toolContext) {
        UUID runId = uuidFromToolContext(toolContext, RunContext.TOOL_CONTEXT_RUN_ID_KEY);
        return runId != null ? runId : RunContext.get();
    }

    static UUID resolveConversationId(ToolContext toolContext) {
        UUID conversationId = uuidFromToolContext(toolContext, RunContext.TOOL_CONTEXT_CONVERSATION_ID_KEY);
        return conversationId != null ? conversationId : RunContext.getConversationId();
    }

    static UUID uuidFromToolContext(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object raw = toolContext.getContext().get(key);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Emit a lifecycle event into the request-scoped {@link StreamingSinkHolder} sink if one
     * exists for the current thread's runId. Non-streaming callers (and callers outside
     * {@code ChatService.stream(...)}) see a no-op. Emission failures never propagate into the
     * tool/audit path.
     */
    protected void emitToolEvent(UUID runId, Consumer<Sinks.Many<StreamingEvent>> emitter) {
        if (streamingSinkHolder == null) {
            return;
        }
        try {
            streamingSinkHolder.currentOrForRun(runId).ifPresent(emitter);
        } catch (RuntimeException ex) {
            log.debug("Streaming tool-event emission failed; continuing with audit-only path", ex);
        }
    }

    protected String resolveUserUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            return user != null ? user.getUsername() : "anonymous";
        } catch (RuntimeException anon) {
            return "anonymous";
        }
    }
}
