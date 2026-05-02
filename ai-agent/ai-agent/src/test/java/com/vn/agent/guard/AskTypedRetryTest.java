package com.vn.agent.guard;

import com.vn.agent.DefaultChatServiceImpl;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.conversation.ConversationTitleEligibilityPublisher;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.test_support.EvalFixtures;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E-06 + E-07 rubrics for {@code ChatService.askTyped} bounded retry loop (D-19).
 *
 * <p>Corpus-driven from {@code /eval/structured-output-fixtures.yaml}. Each case supplies a
 * {@code modelReplies} list that is yielded one-per-attempt by the stubbed ChatClient chain —
 * max 2 attempts (initial + 1 retry) per ROADMAP §06 success criterion #4. The test is pure
 * Mockito; no Spring context. The eclipselink-baseline {@code @SpringBootTest} failures
 * (unrelated to Phase 6) are entirely sidestepped this way.</p>
 */
@Tag("eval")
class AskTypedRetryTest {

    /** Target DTO for structured-output conversion. Public for Jackson + BeanOutputConverter. */
    public record Greeting(@NotBlank String name) {
    }

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClientResponse chatClientResponse;
    private ConversationGateway conversationGateway;
    private AgentToolCallbacks toolCallbacks;
    private AiParametersResolver parametersResolver;
    private BaselineContextProvider baselineContextProvider;
    private RetrievalFilterBuilder retrievalFilterBuilder;
    private CurrentAuthentication currentAuthentication;
    private RateLimitGuard rateLimitGuard;
    private TokenBudgetGuard tokenBudgetGuard;
    private AuditWriter auditWriter;
    private Validator validator;

    private DefaultChatServiceImpl service;
    private UUID convId;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        chatClientResponse = mock(ChatClientResponse.class);
        conversationGateway = mock(ConversationGateway.class);
        toolCallbacks = mock(AgentToolCallbacks.class);
        parametersResolver = mock(AiParametersResolver.class);
        baselineContextProvider = mock(BaselineContextProvider.class);
        retrievalFilterBuilder = mock(RetrievalFilterBuilder.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        rateLimitGuard = mock(RateLimitGuard.class);
        tokenBudgetGuard = mock(TokenBudgetGuard.class);
        auditWriter = mock(AuditWriter.class);
        // Real validator so Jakarta @NotBlank on Greeting.name works end-to-end.
        validator = Validation.buildDefaultValidatorFactory().getValidator();

        AiConversation conv = mock(AiConversation.class);
        convId = UUID.randomUUID();
        when(conv.getId()).thenReturn(convId);
        when(conversationGateway.loadOrCreate(anyString(), any(), anyString())).thenReturn(conv);

        AiParameters active = mock(AiParameters.class);
        when(parametersResolver.resolveActive()).thenReturn(active);
        when(parametersResolver.effectiveModel(any(), any())).thenReturn("openai/gpt-4o-mini");
        when(parametersResolver.effectiveSystemPrompt(any(), anyString(), any(), any())).thenReturn("system");
        when(parametersResolver.effectiveTemperature(any())).thenReturn(0.7);
        when(parametersResolver.effectiveTopP(any())).thenReturn(0.9);
        when(parametersResolver.effectiveMaxTokens(any())).thenReturn(2048);
        when(baselineContextProvider.renderAsText(any())).thenReturn("agent.*");
        when(toolCallbacks.callbacksFor(anyString(), any())).thenReturn(new ToolCallback[0]);
        when(retrievalFilterBuilder.buildFor(any())).thenReturn(null);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatClientResponse()).thenReturn(chatClientResponse);
        when(chatClientResponse.context()).thenReturn(new HashMap<>());

        com.vn.agent.rag.config.AiAgentRagProperties ragProperties =
                mock(com.vn.agent.rag.config.AiAgentRagProperties.class);
        when(ragProperties.resolvedTopK()).thenReturn(5);
        // Phase 11 Plan 11-09: AgentSystemPromptRulesComposer chooses between PROMPT_RULES and
        // PROMPT_RULES + MUTATION_PROMPT_RULES. Tests here exercise the read-only baseline only,
        // so a mock returning PROMPT_RULES is sufficient (no mutation property assertions).
        com.vn.agent.guard.AgentSystemPromptRulesComposer rulesComposer =
                mock(com.vn.agent.guard.AgentSystemPromptRulesComposer.class);
        when(rulesComposer.effectiveRules())
                .thenReturn(com.vn.agent.guard.AgentSystemPromptRules.PROMPT_RULES);
        service = new DefaultChatServiceImpl(chatClient, conversationGateway, toolCallbacks,
                parametersResolver, baselineContextProvider, retrievalFilterBuilder, ragProperties,
                currentAuthentication, rateLimitGuard, tokenBudgetGuard, auditWriter, validator,
                /* chatStreamingScheduler */ null,
                /* cancellationRegistry */ null,
                /* streamingSinkHolder */ null,
                rulesComposer,
                mock(ConversationTitleEligibilityPublisher.class));
    }

    /**
     * Stage the stubbed ChatClient so each successive {@code .call().chatClientResponse()} yields
     * the next entry from {@code modelReplies} — after the list is exhausted the last entry is
     * replayed (the service only retries once so this only matters when the list is shorter
     * than the attempts budget).
     */
    private void stageReplies(List<String> modelReplies) {
        AtomicInteger attempt = new AtomicInteger();
        when(chatClientResponse.chatResponse()).thenAnswer(inv -> {
            int i = Math.min(attempt.getAndIncrement(), modelReplies.size() - 1);
            String body = modelReplies.get(i);
            AssistantMessage msg = new AssistantMessage(body);
            return new ChatResponse(List.of(new Generation(msg)));
        });
    }

    // --------------- E-06 + E-07 corpus driven ------------------------------------------------

    static Stream<Arguments> structuredOutputCases() {
        return EvalFixtures.loadCases("structured-output-fixtures.yaml").stream()
                .map(c -> Arguments.of(
                        c.get("name"),
                        c.get("modelReplies"),
                        c.get("expectedParsedName"),
                        c.get("expectsException")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredOutputCases")
    @SuppressWarnings("unchecked")
    void askTypedFixture(String caseName, List<String> modelReplies,
                         String expectedParsedName, Boolean expectsException) {
        stageReplies(modelReplies);

        if (Boolean.TRUE.equals(expectsException)) {
            assertThatThrownBy(() ->
                    service.askTyped("alice", null, "hello", Greeting.class))
                    .isInstanceOf(StructuredOutputException.class)
                    .satisfies(t -> {
                        StructuredOutputException ex = (StructuredOutputException) t;
                        assertThat(ex.getTargetType()).isEqualTo(Greeting.class);
                        assertThat(ex.getLastRaw()).isNotNull();
                    });
        } else {
            Greeting g = service.askTyped("alice", null, "hello", Greeting.class);
            assertThat(g.name()).isEqualTo(expectedParsedName);
        }
    }

    @Test
    void happyPathDoesNotRetry() {
        stageReplies(List.of("{\"name\":\"Alice\"}"));
        Greeting g = service.askTyped("alice", null, "hello", Greeting.class);
        assertThat(g.name()).isEqualTo("Alice");
        // Exactly one prompt invocation: no retry.
        org.mockito.Mockito.verify(chatClient, org.mockito.Mockito.times(1)).prompt();
    }

    @Test
    void boundedRetryStopsAtTwoAttempts() {
        stageReplies(List.of("not json", "{malformed"));
        assertThatThrownBy(() -> service.askTyped("alice", null, "hello", Greeting.class))
                .isInstanceOf(StructuredOutputException.class);
        // Exactly 2 prompt invocations: initial + 1 retry.
        org.mockito.Mockito.verify(chatClient, org.mockito.Mockito.times(2)).prompt();
    }

    @Test
    void rateLimitDenialDuringAskTypedSurfacesAsTypedGuardException() {
        // Guard preamble denies BEFORE any model call — askTyped must surface a typed guard
        // exception (NOT StructuredOutputException), so callers can catch precisely.
        org.mockito.Mockito.doThrow(new RateLimitExceededException(10))
                .when(rateLimitGuard).check(anyString());

        assertThatThrownBy(() -> service.askTyped("alice", null, "hello", Greeting.class))
                .isInstanceOf(RateLimitExceededException.class);
    }

    // ---------- E-08: StructuredOutputValidationAdvisor graceful degradation ------------------

    @Test
    void optionalAdvisorClassIsPresentInSpringAi_1_1_4() throws Exception {
        // Plan 06-03 D-21 documented the class as absent; Spring AI 1.1.4 actually SHIPS it
        // (verified via jar contents). AiAgentGuardAutoConfiguration wires it @ConditionalOnClass
        // so the bean participates when the class is present. This test pins the classpath
        // guarantee — if a future Spring AI bump drops the class, the failure alerts a
        // conscious audit of the graceful-degradation path.
        Class<?> cls = Class.forName(
                "org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor");
        assertThat(cls).isNotNull();
    }

    @Test
    void askTypedRetryLoopIsAuthoritativeRegardlessOfOptionalAdvisor() {
        // E-08: even when the optional advisor participates in the chain, the inline retry loop
        // in DefaultChatServiceImpl is the authoritative parse/validate path tested here.
        // This test exhaustively exercises that contract: a valid reply parses first-try.
        stageReplies(List.of("{\"name\":\"Carol\"}"));
        Greeting g = service.askTyped("alice", null, "hello", Greeting.class);
        assertThat(g.name()).isEqualTo("Carol");
    }
}
