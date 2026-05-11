package com.vn.agent.view.chat.fragment;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 15 Plan 04 (D-06..D-08; review points #2/#3/#6/#7/#8) — the single
 * {@code kind -> msg://} mapper for the two in-fragment observability surfaces:
 * the ephemeral streaming-status line (OBS-01) and the per-turn tool-detail
 * {@code Details} disclosure (OBS-02).
 *
 * <p><strong>Structural no-leak guarantee (T-15-D1).</strong> No public method
 * accepts a {@code @Tool} method name, a host-entity name, {@code argsJson},
 * {@code resultSummary}, {@code payloadJson}, or {@code queryText} — the input
 * alphabet is the closed {@link StreamingEvent.ActivityKind} enum, the validated
 * audit {@code kind} String ({@code CHAT}/{@code TOOL}/{@code RETRIEVAL}), a
 * nullable {@link Long} latency, and the {@link AiToolCallOutcome} enum. Every
 * label this class emits is a {@code msg://} key, never free text. TEST-19
 * (Plan 05) is therefore a structural assertion: the rendering path cannot
 * carry a tool/entity name because no method signature accepts one.
 *
 * <p>Like {@link StreamEventRenderer}, this class is intentionally Vaadin-free
 * and Spring-free so the mapping contract is locked under a plain JUnit test.
 * Callers resolve the returned keys against {@code io.jmix.core.Messages}.
 *
 * <p><strong>Unknown-duration contract.</strong> A {@link StepRow} with a
 * {@code null} {@link StepRow#latencyMs()} is the signal to render the em-dash
 * placeholder ({@value #UNKNOWN_DURATION_TEXT}) — never {@code "0 ms"} and never
 * {@code "null ms"}. {@link #unknownDurationKey()} exposes the {@code msg://}
 * key for callers that prefer a localized placeholder; {@link #UNKNOWN_DURATION_TEXT}
 * is the literal fallback.
 */
public final class TurnDetailRenderer {

    /** {@code msg://} key for the neutral typing indicator shown before any Activity/Content. */
    public static final String STATUS_NEUTRAL_KEY = "chatView.status.neutral";
    /** {@code msg://} key for the CHAT status ("thinking…"). */
    public static final String STATUS_CHAT_KEY = "chatView.status.chat";
    /** {@code msg://} key for the TOOL status ("searching data…"). */
    public static final String STATUS_TOOL_KEY = "chatView.status.tool";
    /** {@code msg://} key for the RETRIEVAL status ("retrieving documents…"). */
    public static final String STATUS_RETRIEVAL_KEY = "chatView.status.retrieval";

    /** {@code msg://} key for a TOOL step row label in the per-turn disclosure. */
    public static final String STEP_TOOL_KEY = "chatView.turnDetail.step.tool";
    /** {@code msg://} key for a RETRIEVAL step row label in the per-turn disclosure. */
    public static final String STEP_RETRIEVAL_KEY = "chatView.turnDetail.step.retrieval";
    /** {@code msg://} key for a CHAT step row label (defensive — CHAT is rarely a child). */
    public static final String STEP_CHAT_KEY = "chatView.turnDetail.step.chat";
    /** {@code msg://} key for the inline error/rollback indicator on a step row. */
    public static final String ERROR_INDICATOR_KEY = "chatView.turnDetail.errorIndicator";
    /** {@code msg://} key for the unknown-duration placeholder. */
    public static final String UNKNOWN_DURATION_KEY = "chatView.turnDetail.unknownDuration";
    /** {@code msg://} key (MessageFormat: {@code {0}} step count, {@code {1}} total ms) for the Details summary. */
    public static final String SUMMARY_KEY = "chatView.turnDetail.summary";

    /** Literal em-dash placeholder for a step with no known duration. Never "0 ms". */
    public static final String UNKNOWN_DURATION_TEXT = "—";

    private static final Set<String> AUDIT_KINDS = Set.of("CHAT", "TOOL", "RETRIEVAL");

    /** Outcome values that mean the step errored / was rolled back / failed to commit. */
    private static final Set<AiToolCallOutcome> ERRORED_OUTCOMES = EnumSet.of(
            AiToolCallOutcome.FAILED,
            AiToolCallOutcome.ERROR,
            AiToolCallOutcome.COMMIT_FAILED);

    private TurnDetailRenderer() {
        // pure-static utility
    }

    /**
     * A single label-only step row for the per-turn disclosure. Carries ONLY a
     * {@code msg://} label key, a nullable {@link Long} latency (null = "—"),
     * and an "errored" boolean — never a tool/entity name or any free text.
     */
    public record StepRow(String labelKey, Long latencyMs, boolean errored) {
    }

    /**
     * Maps the closed {@link StreamingEvent.ActivityKind} to the {@code msg://}
     * key for the ephemeral streaming-status line.
     */
    public static String statusKeyFor(StreamingEvent.ActivityKind kind) {
        if (kind == null) {
            return STATUS_NEUTRAL_KEY;
        }
        return switch (kind) {
            case CHAT -> STATUS_CHAT_KEY;
            case TOOL -> STATUS_TOOL_KEY;
            case RETRIEVAL -> STATUS_RETRIEVAL_KEY;
        };
    }

    /** {@code msg://} key for the neutral typing indicator shown before any Activity/Content. */
    public static String neutralStatusKey() {
        return STATUS_NEUTRAL_KEY;
    }

    /** {@code msg://} key for the inline error/rollback indicator on a step row. */
    public static String errorIndicatorKey() {
        return ERROR_INDICATOR_KEY;
    }

    /** {@code msg://} key for the unknown-duration placeholder ("—"). */
    public static String unknownDurationKey() {
        return UNKNOWN_DURATION_KEY;
    }

    /** {@code msg://} key for the Details summary text (MessageFormat: {@code {0}} steps, {@code {1}} ms). */
    public static String summaryKey() {
        return SUMMARY_KEY;
    }

    /**
     * Builds a label-only {@link StepRow} from the closed {@link StreamingEvent.ActivityKind}
     * enum + a nullable latency + a nullable {@link AiToolCallOutcome}. No tool/entity name
     * is accepted.
     */
    public static StepRow stepRow(StreamingEvent.ActivityKind kind, Long latencyMs, AiToolCallOutcome outcome) {
        return new StepRow(stepLabelKeyFor(kind), latencyMs, isErrored(outcome));
    }

    /**
     * Overload taking the persisted audit {@code kind} String — validated against
     * {@code {"CHAT","TOOL","RETRIEVAL"}} (the audit query already filters; this is
     * defensive). Maps to the same label keys as {@link #stepRow(StreamingEvent.ActivityKind, Long, AiToolCallOutcome)}.
     *
     * @throws IllegalArgumentException if {@code auditKind} is not one of the closed kinds
     */
    public static StepRow stepRow(String auditKind, Long latencyMs, AiToolCallOutcome outcome) {
        if (auditKind == null || !AUDIT_KINDS.contains(auditKind)) {
            throw new IllegalArgumentException("Unknown audit kind: " + auditKind);
        }
        String labelKey = switch (auditKind) {
            case "TOOL" -> STEP_TOOL_KEY;
            case "RETRIEVAL" -> STEP_RETRIEVAL_KEY;
            case "CHAT" -> STEP_CHAT_KEY;
            default -> throw new IllegalArgumentException("Unknown audit kind: " + auditKind);
        };
        return new StepRow(labelKey, latencyMs, isErrored(outcome));
    }

    /**
     * The MessageFormat argument tuple for {@link #SUMMARY_KEY}: {@code {0}} = step count,
     * {@code {1}} = total ms.
     */
    public static Object[] summaryArgs(int stepCount, long totalMs) {
        return new Object[]{stepCount, totalMs};
    }

    private static String stepLabelKeyFor(StreamingEvent.ActivityKind kind) {
        if (kind == null) {
            return STEP_TOOL_KEY;
        }
        return switch (kind) {
            case TOOL -> STEP_TOOL_KEY;
            case RETRIEVAL -> STEP_RETRIEVAL_KEY;
            case CHAT -> STEP_CHAT_KEY;
        };
    }

    private static boolean isErrored(AiToolCallOutcome outcome) {
        return outcome != null && ERRORED_OUTCOMES.contains(outcome);
    }
}
