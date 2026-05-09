package com.vn.agent.audit;

import com.vn.agent.entity.AiToolCallOutcome;
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

import java.util.UUID;

/**
 * Phase 11 D-09 mutation-callback boundary wrapper. Used in place of
 * {@link ToolCallbackAuditDecorator} for callbacks emitted by
 * {@code BuiltInMutationTools} ({@code create_record}, {@code update_record},
 * {@code add_related_record}, {@code remove_related_record}).
 *
 * <p><b>Why mutation callbacks need a different decorator.</b> The mutation tool
 * methods on {@code BuiltInMutationTools} self-audit through
 * {@code MutationCommitCoordinator.safeWriteAudit} with mutation-specific outcomes
 * ({@link AiToolCallOutcome#SUCCESS}, {@link AiToolCallOutcome#IDEMPOTENT_REPLAY},
 * {@link AiToolCallOutcome#BLOCKED}, {@link AiToolCallOutcome#COMMIT_FAILED},
 * {@link AiToolCallOutcome#ERROR}) and a custom {@code resultSummary} carrying the
 * post-image diff. Wrapping them in {@link ToolCallbackAuditDecorator} would write a
 * SECOND row per call with the generic shape — duplicate audit rows are a HIGH-severity
 * correctness bug (Plan 11-09 review feedback).
 *
 * <p><b>What this decorator does.</b>
 * <ul>
 *   <li>Emits {@link StreamingEvent.ToolCall} before delegate invocation and
 *       {@link StreamingEvent.ToolResult} after delegate return / throw — same shape
 *       as {@link ToolCallbackAuditDecorator} so the streaming UI keeps showing live
 *       tool-call cards (D-09 review point: streaming regression prevention).</li>
 *   <li>On normal delegate return: does NOT call {@link AuditWriter}.
 *       {@code BuiltInMutationTools} already wrote its own audit row exactly once.</li>
 *   <li>On delegate-thrown exception: writes exactly one ERROR audit row before
 *       rethrowing. This path covers binding/invocation failures that happen BEFORE
 *       the tool method body enters its own try/catch (Spring AI argument binding,
 *       JSON schema coercion, reflective invocation setup). Plan 11-07 invariant:
 *       {@code BuiltInMutationTools} catches all expected in-method failures and
 *       its {@code safeWriteAudit} never throws, so reaching this rethrow path
 *       means a delegate-thrown exception is a bug / last-resort path. The
 *       single ERROR row written here is the only audit signal in that case.</li>
 * </ul>
 *
 * <p><b>Not a Spring @Component.</b> Instantiated per-callback by
 * {@code AgentToolCallbacks.forCurrentUser()} for each mutation
 * {@link ToolCallback}, after the generic {@link ToolCallbackAuditDecorator}
 * loop has already wrapped read + link + contributor callbacks.
 */
public class MutationToolCallbackBoundaryDecorator implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(MutationToolCallbackBoundaryDecorator.class);

    /** Cap on captured raw arguments JSON written into the LOB column. Mirrors {@link ToolCallbackAuditDecorator}. */
    static final int ARGUMENTS_JSON_MAX_CHARS = 4096;

    /** Cap on captured result/error summary written into the LOB column. */
    static final int RESULT_SUMMARY_MAX_CHARS = 4096;

    /** Suffix appended when a captured value is truncated, so the truncation is observable. */
    static final String TRUNCATION_SUFFIX = "…[truncated]";

    private static String cap(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + TRUNCATION_SUFFIX;
    }

    private final ToolCallback delegate;
    private final StreamingSinkHolder streamingSinkHolder;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final MutationArgumentSanitizer mutationArgumentSanitizer;

    public MutationToolCallbackBoundaryDecorator(ToolCallback delegate,
                                                 StreamingSinkHolder streamingSinkHolder,
                                                 AuditWriter auditWriter,
                                                 CurrentAuthentication currentAuthentication,
                                                 MutationArgumentSanitizer mutationArgumentSanitizer) {
        this.delegate = delegate;
        this.streamingSinkHolder = streamingSinkHolder;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.mutationArgumentSanitizer = mutationArgumentSanitizer;
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

    private String callInternal(String toolInput, ToolContext toolContext, boolean useContextOverload) {
        UUID runId = resolveRunId(toolContext);
        UUID conversationId = resolveConversationId(toolContext);
        String userUsername = resolveUserUsername();
        String toolName = delegate.getToolDefinition().name();
        String safeInput = cap(mutationArgumentSanitizer.sanitize(toolName, toolInput), ARGUMENTS_JSON_MAX_CHARS);
        RunContextSnapshot previousContext = RunContextSnapshot.capture();
        installRunContext(runId, conversationId);

        // Streaming pair-id correlation (mirrors ToolCallbackAuditDecorator).
        final UUID toolCallId = UUID.randomUUID();
        emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(toolCallId, toolName, safeInput)));

        long startNanos = System.nanoTime();
        String output = null;
        try {
            output = useContextOverload ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
            // Normal return: BuiltInMutationTools already self-audited via safeWriteAudit
            // (single audit owner per Plan 11-07 invariant). Do NOT call AuditWriter here.
            return output;
        } catch (Throwable t) {
            // Delegate threw before BuiltInMutationTools could self-audit. This is the
            // last-resort pre-method audit path: Plan 11-07 contract says tool methods
            // catch all expected mutation/audit/idempotency failures and safeWriteAudit
            // never throws, so reaching this branch means a Spring AI binding /
            // reflective-invocation failure (or a genuine bug). Write exactly one ERROR
            // audit row, then rethrow so Spring AI surfaces the failure to the model.
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            UUID parentId = RunContext.getRootAuditId();
            try {
                auditWriter.writeToolCall(parentId, runId, userUsername, conversationId, toolName,
                        safeInput, /* resultSummary */ null, latencyMs,
                        AiToolCallOutcome.ERROR,
                        /* denialReason */ null, t.getClass().getName());
            } catch (Throwable t2) {
                log.warn("Mutation boundary audit write failed runId={} tool={}", runId, toolName, t2);
            }
            throw t;
        } finally {
            // Emit the ToolResult event regardless of outcome so the streaming UI can close
            // the tool-call card. On the normal-return path the summary mirrors the LLM-visible
            // tool result string (already produced by BuiltInMutationTools' canned envelopes,
            // safe to surface). On the throw path, output is null so the UI sees an empty
            // summary plus the failed outcome ToolResult would otherwise carry; we use
            // SUCCESS-shaped ToolResult only when output != null. When output is null
            // (delegate threw before self-audit), surface ERROR so the streaming UI can mark
            // the tool-call card as failed even before the rethrown exception terminates the
            // outer Flux.
            AiToolCallOutcome outcome = output != null ? AiToolCallOutcome.SUCCESS : AiToolCallOutcome.ERROR;
            String emittedSummary = output != null ? cap(output, RESULT_SUMMARY_MAX_CHARS) : null;
            emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolResult(
                    toolCallId, toolName, emittedSummary, outcome, null)));
            previousContext.restore();
        }
    }

    private void installRunContext(UUID runId, UUID conversationId) {
        if (runId != null) {
            RunContext.set(runId);
            try {
                RunContext.setRootAuditId(auditWriter.findChatRootId(runId));
            } catch (RuntimeException lookupFailure) {
                log.debug("Failed to resolve chat root audit id for mutation tool runId={}", runId, lookupFailure);
                RunContext.setRootAuditId(null);
            }
        }
        if (conversationId != null) {
            RunContext.setConversationId(conversationId);
        }
    }

    private static UUID resolveRunId(ToolContext toolContext) {
        UUID runId = uuidFromToolContext(toolContext, RunContext.TOOL_CONTEXT_RUN_ID_KEY);
        return runId != null ? runId : RunContext.get();
    }

    private static UUID resolveConversationId(ToolContext toolContext) {
        UUID conversationId = uuidFromToolContext(toolContext, RunContext.TOOL_CONTEXT_CONVERSATION_ID_KEY);
        return conversationId != null ? conversationId : RunContext.getConversationId();
    }

    private static UUID uuidFromToolContext(ToolContext toolContext, String key) {
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

    private void emitToolEvent(UUID runId,
                               java.util.function.Consumer<reactor.core.publisher.Sinks.Many<StreamingEvent>> emitter) {
        if (streamingSinkHolder == null) {
            return;
        }
        try {
            streamingSinkHolder.currentOrForRun(runId).ifPresent(emitter);
        } catch (RuntimeException ex) {
            log.debug("Streaming tool-event emission failed in mutation boundary; continuing", ex);
        }
    }

    private String resolveUserUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            return user.getUsername();
        } catch (RuntimeException anon) {
            return "anonymous";
        }
    }

    private record RunContextSnapshot(UUID runId,
                                      UUID rootAuditId,
                                      UUID conversationId,
                                      Integer retrievalTopK,
                                      Double retrievalSimilarityThreshold,
                                      String retrievalFiltersJson) {
        private static RunContextSnapshot capture() {
            return new RunContextSnapshot(
                    RunContext.get(),
                    RunContext.getRootAuditId(),
                    RunContext.getConversationId(),
                    RunContext.getRetrievalTopK(),
                    RunContext.getRetrievalSimilarityThreshold(),
                    RunContext.getRetrievalFiltersJson());
        }

        private void restore() {
            RunContext.clear();
            if (runId != null) {
                RunContext.set(runId);
            }
            if (rootAuditId != null) {
                RunContext.setRootAuditId(rootAuditId);
            }
            if (conversationId != null) {
                RunContext.setConversationId(conversationId);
            }
            if (retrievalTopK != null) {
                RunContext.setRetrievalTopK(retrievalTopK);
            }
            if (retrievalSimilarityThreshold != null) {
                RunContext.setRetrievalSimilarityThreshold(retrievalSimilarityThreshold);
            }
            if (retrievalFiltersJson != null) {
                RunContext.setRetrievalFiltersJson(retrievalFiltersJson);
            }
        }
    }
}
