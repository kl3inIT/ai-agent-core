package com.vn.agent.view.chat.fragment;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan 15-04 Task 1 — locks the {@code kind -> msg://} mapping contract for
 * {@link TurnDetailRenderer} (the structural no-leak mapper, T-15-D1).
 *
 * <p>Pure JUnit 5 + AssertJ. No Vaadin, no Spring. Mirrors {@code RenderStreamEventTest}.
 */
class TurnDetailRendererTest {

    @Test
    void statusKeyFor_mapsEveryActivityKind() {
        assertThat(TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.CHAT))
                .isEqualTo("chatView.status.chat");
        assertThat(TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.TOOL))
                .isEqualTo("chatView.status.tool");
        assertThat(TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.RETRIEVAL))
                .isEqualTo("chatView.status.retrieval");
    }

    @Test
    void statusKeyFor_nullKind_fallsBackToNeutral() {
        assertThat(TurnDetailRenderer.statusKeyFor(null)).isEqualTo("chatView.status.neutral");
    }

    @Test
    void neutralStatusKey_isStatusNeutral() {
        assertThat(TurnDetailRenderer.neutralStatusKey()).isEqualTo("chatView.status.neutral");
    }

    @Test
    void errorIndicatorAndUnknownDurationAndSummaryKeys_areStable() {
        assertThat(TurnDetailRenderer.errorIndicatorKey()).isEqualTo("chatView.turnDetail.errorIndicator");
        assertThat(TurnDetailRenderer.unknownDurationKey()).isEqualTo("chatView.turnDetail.unknownDuration");
        assertThat(TurnDetailRenderer.summaryKey()).isEqualTo("chatView.turnDetail.summary");
        assertThat(TurnDetailRenderer.UNKNOWN_DURATION_TEXT).isEqualTo("—");
    }

    @Test
    void stepRow_fromActivityKind_toolAndRetrieval_haveRightLabelKeys() {
        TurnDetailRenderer.StepRow tool =
                TurnDetailRenderer.stepRow(StreamingEvent.ActivityKind.TOOL, 42L, AiToolCallOutcome.SUCCESS);
        assertThat(tool.labelKey()).isEqualTo("chatView.turnDetail.step.tool");
        assertThat(tool.latencyMs()).isEqualTo(42L);
        assertThat(tool.errored()).isFalse();

        TurnDetailRenderer.StepRow retrieval =
                TurnDetailRenderer.stepRow(StreamingEvent.ActivityKind.RETRIEVAL, null, null);
        assertThat(retrieval.labelKey()).isEqualTo("chatView.turnDetail.step.retrieval");
        assertThat(retrieval.latencyMs()).isNull();
        assertThat(retrieval.errored()).isFalse();
    }

    @Test
    void stepRow_fromAuditKindString_mapsToSameLabelKeys() {
        assertThat(TurnDetailRenderer.stepRow("TOOL", 7L, AiToolCallOutcome.SUCCESS).labelKey())
                .isEqualTo("chatView.turnDetail.step.tool");
        assertThat(TurnDetailRenderer.stepRow("RETRIEVAL", null, null).labelKey())
                .isEqualTo("chatView.turnDetail.step.retrieval");
        assertThat(TurnDetailRenderer.stepRow("CHAT", 1L, null).labelKey())
                .isEqualTo("chatView.turnDetail.step.chat");
    }

    @Test
    void stepRow_errorOutcome_setsErroredFlag() {
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, AiToolCallOutcome.ERROR).errored()).isTrue();
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, AiToolCallOutcome.FAILED).errored()).isTrue();
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, AiToolCallOutcome.COMMIT_FAILED).errored()).isTrue();
        // Non-error outcomes (incl. BLOCKED/DENIED which are policy outcomes, not failures of the run)
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, AiToolCallOutcome.SUCCESS).errored()).isFalse();
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, AiToolCallOutcome.IDEMPOTENT_REPLAY).errored()).isFalse();
        assertThat(TurnDetailRenderer.stepRow("TOOL", 5L, (AiToolCallOutcome) null).errored()).isFalse();
    }

    @Test
    void stepRow_badAuditKind_throwsIllegalArgument() {
        assertThatThrownBy(() -> TurnDetailRenderer.stepRow("BOGUS", 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TurnDetailRenderer.stepRow((String) null, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepRow_nullLatency_isUnknownDuration_notZeroMs() {
        TurnDetailRenderer.StepRow row = TurnDetailRenderer.stepRow("RETRIEVAL", null, null);
        // The contract: a null latency means "render the em-dash" — never "0 ms" / "null ms".
        assertThat(row.latencyMs()).isNull();
        assertThat(TurnDetailRenderer.unknownDurationKey()).isEqualTo("chatView.turnDetail.unknownDuration");
        assertThat(TurnDetailRenderer.UNKNOWN_DURATION_TEXT)
                .isEqualTo("—")
                .isNotEqualTo("0 ms")
                .isNotEqualTo("null ms");
    }

    @Test
    void summaryArgs_producesStepCountAndTotalMsTuple() {
        Object[] args = TurnDetailRenderer.summaryArgs(3, 1234L);
        assertThat(args).containsExactly(3, 1234L);
    }

    @Test
    void noPublicMethodAcceptsAToolOrEntityNameOrFreeText() {
        // Structural T-15-D1 assertion: every public method's parameter types are restricted
        // to the closed input alphabet — ActivityKind / String (the validated audit-kind) /
        // Long / AiToolCallOutcome / primitives. There is NO `toolName`/`entityName`/free-text
        // parameter anywhere on the public surface.
        Set<Class<?>> allowed = Set.of(
                StreamingEvent.ActivityKind.class,
                String.class,                 // only the validated audit-kind
                Long.class,
                AiToolCallOutcome.class,
                int.class,
                long.class);
        for (Method method : TurnDetailRenderer.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(allowed)
                        .as("public method %s has a disallowed parameter type %s",
                                method.getName(), paramType.getName())
                        .contains(paramType);
            }
            // String params: there is at most ONE, and it is the audit-kind (named accordingly).
            long stringParams = java.util.Arrays.stream(method.getParameterTypes())
                    .filter(t -> t == String.class).count();
            assertThat(stringParams)
                    .as("public method %s must take at most one String (the validated audit-kind)",
                            method.getName())
                    .isLessThanOrEqualTo(1);
        }
    }
}
