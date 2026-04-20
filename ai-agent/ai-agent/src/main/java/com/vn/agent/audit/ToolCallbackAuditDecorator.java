package com.vn.agent.audit;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

/**
 * Wraps a delegate {@link ToolCallback} so every tool invocation persists a PRE row eagerly
 * (before the delegate runs) and a POST row in {@code finally} with the real outcome + latency
 * (AUD-04). Both writes go through the {@link AuditWriter} REQUIRES_NEW bean, so the rows
 * survive even when the delegate throws AND its own transaction rolls back (AUD-02).
 *
 * <p><b>Not a Spring @Component.</b> Instantiated per-callback by {@code AgentToolCallbacks}
 * (Plan 04-04). No {@code @Transactional} annotation on this class — the durability guarantee
 * comes from the injected {@link AuditWriter} proxy, not from the decorator.
 */
public class ToolCallbackAuditDecorator implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackAuditDecorator.class);

    /** Cap on {@code resultSummary} written into the LOB column — keeps rows reasonable. */
    static final int RESULT_SUMMARY_MAX_CHARS = 4096;

    /** Cap on {@code argumentsJson} (model-supplied tool input) written into the LOB column (MD-04). */
    static final int ARGUMENTS_JSON_MAX_CHARS = 4096;

    /** Suffix appended when a captured value is truncated, so the truncation is observable. */
    static final String TRUNCATION_SUFFIX = "…[truncated]";

    private static String cap(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + TRUNCATION_SUFFIX;
    }

    private final ToolCallback delegate;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public ToolCallbackAuditDecorator(ToolCallback delegate,
                                      AuditWriter auditWriter,
                                      CurrentAuthentication currentAuthentication) {
        this.delegate = delegate;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return callInternal(toolInput, null, false);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return callInternal(toolInput, toolContext, true);
    }

    private String callInternal(String toolInput, ToolContext toolContext, boolean useContextOverload) {
        UUID runId = RunContext.get();          // may be null if invoked outside a chat call (defensive)
        String userUsername = resolveUserUsername();
        UUID conversationId = null;             // chat-level row carries it; tool row correlates by runId
        String toolName = delegate.getToolDefinition().name();
        long startNanos = System.nanoTime();

        String cappedInput = cap(toolInput, ARGUMENTS_JSON_MAX_CHARS);

        // PRE row — write eagerly so it survives even if delegate throws AND its tx rolls back.
        try {
            auditWriter.writeToolCall(runId, userUsername, conversationId, toolName,
                    cappedInput, /*resultSummary*/ null, 0L,
                    AiToolCallOutcome.SUCCESS,  // sentinel; POST records real outcome
                    /*denialReason*/ null, /*errorClass*/ null, "PRE");
        } catch (Throwable t) {
            log.warn("Tool PRE audit failed runId={} tool={}", runId, toolName, t);
        }

        boolean success = false;
        String output = null;
        String errorMessage = null;
        String errorClass = null;
        try {
            output = useContextOverload ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
            success = true;
            return output;
        } catch (Throwable t) {
            errorMessage = t.getMessage();
            errorClass = t.getClass().getName();
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            try {
                AiToolCallOutcome outcome = success ? AiToolCallOutcome.SUCCESS : AiToolCallOutcome.ERROR;
                String resultSummary = output == null
                        ? cap(errorMessage, RESULT_SUMMARY_MAX_CHARS)
                        : cap(output, RESULT_SUMMARY_MAX_CHARS);
                auditWriter.writeToolCall(runId, userUsername, conversationId, toolName,
                        cappedInput, resultSummary, latencyMs, outcome,
                        /*denialReason*/ null, errorClass, "POST");
            } catch (Throwable t2) {
                log.warn("Tool POST audit failed runId={} tool={}", runId, toolName, t2);
            }
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
}
