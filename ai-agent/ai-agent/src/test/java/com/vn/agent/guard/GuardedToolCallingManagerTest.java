package com.vn.agent.guard;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.ToolGuard;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.test_support.EvalFixtures;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E-03 + E-04 rubrics for {@link GuardedToolCallingManager}:
 * <ul>
 *   <li>E-03: iteration cap breach at round 7 throws {@link IterationCapExceededException} and
 *       writes a request-level audit row keyed by {@code __chat__}.</li>
 *   <li>E-04: a {@link ToolGuard} veto writes an audit row with the REAL tool name,
 *       {@code BLOCKED} outcome, and {@code denialReason="tool-vetoed:<msg>"}.</li>
 * </ul>
 * Corpus-driven iteration cap cases are loaded from {@code iteration-cap-fixtures.yaml}.
 */
@Tag("eval")
class GuardedToolCallingManagerTest {

    private ToolCallingManager delegate;
    private ToolGuard toolGuard;
    private AuditWriter auditWriter;
    private CurrentAuthentication auth;
    private GuardedToolCallingManager manager;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallingManager.class);
        toolGuard = mock(ToolGuard.class);
        auditWriter = mock(AuditWriter.class);
        auth = mock(CurrentAuthentication.class);
        when(auth.getUser()).thenReturn(User.withUsername("alice").password("x").authorities("ROLE_USER").build());
        AiAgentGuardProperties props = new AiAgentGuardProperties(
                null, null, new AiAgentGuardProperties.IterationCap(6), null);
        manager = new GuardedToolCallingManager(delegate, props, toolGuard, auditWriter, auth);
        IterationCounter.start();
    }

    @AfterEach
    void tearDown() {
        IterationCounter.reset();
        RunContext.clear();
    }

    // ---------- E-03: iteration-cap-fixtures.yaml driven --------------------------------------

    static Stream<org.junit.jupiter.params.provider.Arguments> iterationCapCases() {
        return EvalFixtures.loadCases("iteration-cap-fixtures.yaml").stream()
                .map(c -> org.junit.jupiter.params.provider.Arguments.of(
                        ((Number) c.get("rounds")).intValue(),
                        c.get("expectedException")));
    }

    @ParameterizedTest(name = "rounds={0} expected={1}")
    @MethodSource("iterationCapCases")
    void iterationCapFixture(int rounds, String expectedException) {
        // ChatResponse with NO tool calls — so executeToolCalls loops over the iteration increment
        // without triggering the ToolGuard fan-out. Delegate returns null ToolExecutionResult —
        // fine because this test never inspects the result (we drive the decorator directly).
        ChatResponse emptyResp = chatResponseWithToolCalls(List.of());
        Prompt prompt = mock(Prompt.class);

        // Run rounds one-by-one. A breach must surface on the (cap+1)th round.
        for (int i = 1; i <= rounds; i++) {
            int round = i;
            if (expectedException != null && round > 6) {
                assertThatThrownBy(() -> manager.executeToolCalls(prompt, emptyResp))
                        .isInstanceOf(IterationCapExceededException.class);
                // Audit row written with the CHAT sentinel tool name.
                verify(auditWriter, atLeastOnce()).writeToolCall(
                        ArgumentMatchers.<UUID>any(),
                        ArgumentMatchers.<UUID>any(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<UUID>any(),
                        eq(GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME),
                        ArgumentMatchers.<String>any(),
                        ArgumentMatchers.<String>any(),
                        anyLong(),
                        eq(AiToolCallOutcome.BLOCKED),
                        eq("iteration-cap-exceeded"),
                        ArgumentMatchers.<String>any());
                return;
            }
            // Within cap: must not throw.
            assertThatCode(() -> manager.executeToolCalls(prompt, emptyResp))
                    .as("round %d should be within cap", round)
                    .doesNotThrowAnyException();
        }
        // If we completed all rounds without expecting an exception, assert the delegate was
        // invoked exactly `rounds` times.
        if (expectedException == null) {
            verify(delegate, org.mockito.Mockito.times(rounds))
                    .executeToolCalls(any(Prompt.class), any(ChatResponse.class));
        }
    }

    // ---------- E-04: tool veto audit uses the REAL tool name ---------------------------------

    @Test
    void toolVetoIsAuditedWithRealToolName() {
        String realToolName = "dangerousTool";
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "id-1", "function", realToolName, "{\"x\":1}");
        ChatResponse resp = chatResponseWithToolCalls(List.of(call));
        Prompt prompt = mock(Prompt.class);

        doThrow(new ToolVetoedException("policy-X"))
                .when(toolGuard).check(eq(realToolName), ArgumentMatchers.<Map<String, Object>>any());

        assertThatThrownBy(() -> manager.executeToolCalls(prompt, resp))
                .isInstanceOf(ToolVetoedException.class);

        verify(auditWriter).writeToolCall(
                ArgumentMatchers.<UUID>any(),                 // parentId
                ArgumentMatchers.<UUID>any(),                 // runId
                eq("alice"),
                isNull(),                           // conversation id is null at decorator layer
                eq(realToolName),                   // REAL tool name (not __chat__)
                ArgumentMatchers.<String>any(),
                isNull(),
                anyLong(),
                eq(AiToolCallOutcome.BLOCKED),
                argThat(reason -> reason != null && reason.startsWith("tool-vetoed:")),
                ArgumentMatchers.<String>any());
    }

    @Test
    void iterationCapBreachAuditsWithChatSentinel() {
        ChatResponse emptyResp = chatResponseWithToolCalls(List.of());
        Prompt prompt = mock(Prompt.class);
        // Drive 6 passing rounds, 7th must throw.
        for (int i = 1; i <= 6; i++) {
            manager.executeToolCalls(prompt, emptyResp);
        }
        assertThatThrownBy(() -> manager.executeToolCalls(prompt, emptyResp))
                .isInstanceOf(IterationCapExceededException.class);

        verify(auditWriter, atLeastOnce()).writeToolCall(
                ArgumentMatchers.<UUID>any(),
                ArgumentMatchers.<UUID>any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.<UUID>any(),
                eq(GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME),
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<String>any(),
                anyLong(),
                eq(AiToolCallOutcome.BLOCKED),
                eq("iteration-cap-exceeded"),
                ArgumentMatchers.<String>any());
    }

    @Test
    void delegateIsCalledWhenCapNotBreachedAndNoVeto() {
        ChatResponse emptyResp = chatResponseWithToolCalls(List.of());
        Prompt prompt = mock(Prompt.class);
        manager.executeToolCalls(prompt, emptyResp);
        verify(delegate).executeToolCalls(prompt, emptyResp);
        assertThat(IterationCounter.current()).isEqualTo(1);
    }

    // ---------- helpers ------------------------------------------------------------------------

    /**
     * Build a {@link ChatResponse} whose assistant message carries the supplied tool calls.
     * The 4-arg {@link AssistantMessage} constructor is {@code protected}, so mocking the chain
     * is the simplest Spring-AI-version-agnostic path (GuardedToolCallingManager only reads
     * {@code getResult().getOutput().getToolCalls()}).
     */
    private static ChatResponse chatResponseWithToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        ChatResponse resp = mock(ChatResponse.class);
        Generation gen = mock(Generation.class);
        AssistantMessage msg = mock(AssistantMessage.class);
        when(resp.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(msg);
        when(msg.getToolCalls()).thenReturn(toolCalls);
        return resp;
    }
}
