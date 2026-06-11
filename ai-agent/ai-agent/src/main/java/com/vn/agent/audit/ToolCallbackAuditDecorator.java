package com.vn.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.UUID;

/**
 * Wraps a delegate {@link ToolCallback} so every tool invocation persists a POST row in
 * {@code finally} with the real outcome + latency (AUD-04). The write goes through the
 * {@link AuditWriter} REQUIRES_NEW bean, so the row survives even when the delegate throws AND
 * its own transaction rolls back (AUD-02).
 *
 * <p>Shared tool-context / streaming / identity plumbing lives in
 * {@link AbstractToolCallbackAuditDecorator}; this subclass owns the generic per-call audit
 * write, the {@code shouldWriteGenericAudit} carve-out (so self-auditing tools like
 * {@code prepare_form_draft} are not double-audited), and the structured-payload streaming
 * extraction.
 *
 * <p><b>Plan 07-02 streaming extension (RESEARCH Open Q#2 RESOLVED).</b> When a streaming run
 * is active, each tool invocation emits {@link StreamingEvent.ToolCall} on entry and
 * {@link StreamingEvent.ToolResult} on exit so the UI can render live tool-call cards (D-08).
 * Blocking {@code ask(...)} path is unchanged: the sink lookup returns empty and the emit is a
 * no-op, preserving Phase-4 semantics.</p>
 */
public class ToolCallbackAuditDecorator extends AbstractToolCallbackAuditDecorator {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackAuditDecorator.class);

    private static final String PREPARE_FORM_DRAFT_TOOL = "prepare_form_draft";
    private static final String OPEN_FORM_WITH_DRAFT_ACTION = "open_form_with_draft";
    private static final String PROPOSE_ACTION_CHOICES_TOOL = "propose_action_choices";
    private static final String SHOW_ACTION_CHOICES_ACTION = "show_action_choices";
    private static final ObjectMapper STRUCTURED_PAYLOAD_OBJECT_MAPPER = new ObjectMapper();

    /**
     * Phase-4 constructor preserved for non-streaming callers; delegates to the Plan-07-02
     * overload with a {@code null} sink holder (blocking path only).
     */
    public ToolCallbackAuditDecorator(ToolCallback delegate,
                                      AuditWriter auditWriter,
                                      CurrentAuthentication currentAuthentication) {
        this(delegate, auditWriter, currentAuthentication, null);
    }

    /**
     * Plan 07-02 streaming-aware constructor. When {@code streamingSinkHolder} is non-null and
     * a run is registered for the current thread's runId, tool-call lifecycle events are
     * emitted into the active Flux.
     */
    public ToolCallbackAuditDecorator(ToolCallback delegate,
                                      AuditWriter auditWriter,
                                      CurrentAuthentication currentAuthentication,
                                      StreamingSinkHolder streamingSinkHolder) {
        super(delegate, auditWriter, currentAuthentication, streamingSinkHolder);
    }

    @Override
    protected String callInternal(String toolInput, ToolContext toolContext, boolean useContextOverload) {
        UUID runId = resolveRunId(toolContext);          // may be null if invoked outside a chat call (defensive)
        String userUsername = resolveUserUsername();
        UUID conversationId = resolveConversationId(toolContext);
        String toolName = delegate.getToolDefinition().name();
        long startNanos = System.nanoTime();

        String cappedInput = cap(toolInput, ARGUMENTS_JSON_MAX_CHARS);

        // Plan 07.2 (D-01): children are append-only — no PRE row. One row per tool invocation,
        // written in the finally block below with parentId = RunContext.getRootAuditId().

        // Plan 07-02: correlate ToolCall / ToolResult pair via a single id. The pair is
        // consumed by StreamEventRenderer (Plan 07.1-02) which matches the ToolResult back
        // to the originating ToolCall by this id. Emission is a no-op when no streaming
        // run is active (blocking ask() path or non-chat invocation).
        final UUID toolCallId = UUID.randomUUID();
        // Phase 15 D-05: ephemeral streaming-status marker — carries only the closed ActivityKind
        // constant (never the @Tool method name / args), best-effort via the same emitToolEvent
        // helper (null-guards the sink holder, swallows RuntimeException).
        // WR-06: only emit the Activity(TOOL) status marker for tools that get a generic audit
        // child row, so the live status line and the post-Final per-turn Details (which reads only
        // persisted children) agree on the step set. The ToolCall/ToolResult pair is still emitted
        // for every tool — StreamEventRenderer needs ToolResult's structured payload (e.g.
        // prepare_form_draft → open_form_with_draft) regardless of auditing.
        if (shouldWriteGenericAudit(toolName)) {
            emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.TOOL)));
        }
        emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(toolCallId, toolName, cappedInput)));

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
            AiToolCallOutcome outcome = success ? AiToolCallOutcome.SUCCESS : AiToolCallOutcome.ERROR;
            String resultSummary = output == null
                    ? cap(errorMessage, RESULT_SUMMARY_MAX_CHARS)
                    : cap(output, RESULT_SUMMARY_MAX_CHARS);
            UUID parentId = RunContext.getRootAuditId();   // may be null defensively (out-of-chain calls — D-10)
            if (shouldWriteGenericAudit(toolName)) {
                try {
                    auditWriter.writeToolCall(parentId, runId, userUsername, conversationId, toolName,
                            cappedInput, resultSummary, latencyMs, outcome,
                            /*denialReason*/ null, errorClass);
                } catch (Throwable t2) {
                    log.warn("Tool audit write failed runId={} tool={}", runId, toolName, t2);
                }
            }
            // Plan 07-02: emit post-exit ToolResult to the streaming sink (both success + error paths).
            final String emittedSummary = resultSummary;
            final String emittedPayloadJson = structuredPayloadJson(toolName, output);
            emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolResult(
                    toolCallId, toolName, emittedSummary, outcome, emittedPayloadJson)));
        }
    }

    private static boolean shouldWriteGenericAudit(String toolName) {
        // ExtractionService owns prepare_form_draft audit rows so denial/failure paths produce
        // exactly one plan-defined row and no duplicate generic decorator row.
        return !PREPARE_FORM_DRAFT_TOOL.equals(toolName);
    }

    private static String structuredPayloadJson(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonNode root = STRUCTURED_PAYLOAD_OBJECT_MAPPER.readTree(output);
            if (root != null && PREPARE_FORM_DRAFT_TOOL.equals(toolName)
                    && OPEN_FORM_WITH_DRAFT_ACTION.equals(root.path("action").asText())) {
                return output;
            }
            if (root != null && PROPOSE_ACTION_CHOICES_TOOL.equals(toolName)
                    && SHOW_ACTION_CHOICES_ACTION.equals(root.path("action").asText())
                    && "READY".equals(root.path("status").asText())) {
                return output;
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
        return null;
    }
}
