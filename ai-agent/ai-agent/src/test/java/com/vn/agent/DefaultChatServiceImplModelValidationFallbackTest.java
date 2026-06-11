package com.vn.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.extraction.IntentRegistry;
import com.vn.agent.guard.AgentSystemPromptRulesComposer;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatModelFallbackAppliedEvent;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import com.vn.agent.taskfile.AiTaskFileMediaResolver.Resolved;
import com.vn.agent.taskfile.AiTaskFileRepository;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 16 D-05 / MODEL-02 — bad-model catch + one-shot reissue contract test.
 *
 * <p>Pure JUnit 5 + Mockito (mirrors {@link DefaultChatServiceImplStreamFallbackTest}) —
 * intentionally NOT {@code @SpringBootTest} because the pre-existing Phase 11/13 Spring
 * context boot regression (atmosphere-runtime / agentstoreEntityManagerFactory) blocks
 * {@code @SpringBootTest} in this module. Documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}.
 *
 * <p>Asserts the D-05 catch + reissue contract inside
 * {@code DefaultChatServiceImpl.executeBlockingTurn(...)} per the
 * {@code DefaultChatServiceImplModelValidationFallbackTest} Wave-0 scaffold spec:
 * <ol>
 *   <li>Bad-model classification triggers exactly one reissue against
 *       {@code parametersResolver.fallbackModel()}.</li>
 *   <li>Both audit rows share the same {@code runId} via the {@code MODEL_VALIDATION_FAILURE}
 *       audit row written by {@link AuditWriter#writeAuditEvent(String, UUID, UUID, String,
 *       UUID, String, String, String, long, AiToolCallOutcome, String, String)}.</li>
 *   <li>The saved {@code AiParameters.bodyYaml.model} is NEVER mutated — the resolver's
 *       {@code effectiveModel(...)} return is the local-only swap target.</li>
 *   <li>Non-bad-model exceptions propagate unchanged.</li>
 *   <li>A 5xx response does NOT trigger reissue (Pitfall 6 false-positive guard).</li>
 *   <li>A direct {@code RestClientResponseException} (not wrapped in
 *       {@code NonTransientAiException}) triggers reissue (codex HIGH Concern #8).</li>
 *   <li>The user-visible fallback notification fires (codex HIGH Concern #9) — observable as
 *       a {@link ChatModelFallbackAppliedEvent} on the injected
 *       {@link ApplicationEventPublisher}.</li>
 * </ol>
 */
class DefaultChatServiceImplModelValidationFallbackTest {

    private ChatClient chatClient;
    private ConversationGateway conversationGateway;
    private AgentToolCallbacks toolCallbacks;
    private AiParametersResolver parametersResolver;
    private BaselineContextProvider baselineContextProvider;
    private RetrievalFilterBuilder retrievalFilterBuilder;
    private AiAgentRagProperties ragProperties;
    private CurrentAuthentication currentAuthentication;
    private RateLimitGuard rateLimitGuard;
    private TokenBudgetGuard tokenBudgetGuard;
    private AuditWriter auditWriter;
    private Validator validator;
    private CancellationRegistry cancellationRegistry;
    private StreamingSinkHolder streamingSinkHolder;
    private AgentSystemPromptRulesComposer agentSystemPromptRulesComposer;
    private IntentRegistry intentRegistry;
    private AiTaskFileMediaResolver taskFileMediaResolver;
    private AiTaskFileRepository taskFileRepository;
    private ApplicationEventPublisher eventPublisher;

    private AiParameters activeParams;
    private DefaultChatServiceImpl sut;

    private final UUID convId = UUID.randomUUID();
    private static final String USER_ID = "user-modval-test";
    private static final String MESSAGE = "describe this";
    private static final String OFFENDING_MODEL = "qwen/invalid-foo";
    private static final String FALLBACK_MODEL = "qwen/qwen3-35b-a3b";
    private static final String OFFENDING_BODY_YAML = "model: " + OFFENDING_MODEL + "\n";

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        conversationGateway = mock(ConversationGateway.class);
        toolCallbacks = mock(AgentToolCallbacks.class);
        parametersResolver = mock(AiParametersResolver.class);
        baselineContextProvider = mock(BaselineContextProvider.class);
        retrievalFilterBuilder = mock(RetrievalFilterBuilder.class);
        ragProperties = mock(AiAgentRagProperties.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        rateLimitGuard = mock(RateLimitGuard.class);
        tokenBudgetGuard = mock(TokenBudgetGuard.class);
        auditWriter = mock(AuditWriter.class);
        validator = mock(Validator.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        streamingSinkHolder = mock(StreamingSinkHolder.class);
        agentSystemPromptRulesComposer = mock(AgentSystemPromptRulesComposer.class);
        intentRegistry = mock(IntentRegistry.class);
        taskFileMediaResolver = mock(AiTaskFileMediaResolver.class);
        taskFileRepository = mock(AiTaskFileRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(convId);
        when(conversationGateway.loadOrCreate(eq(USER_ID), any(), eq(MESSAGE)))
                .thenReturn(conversation);

        // Active AiParameters carries the offending model in bodyYaml — savedAiParameters...
        // assertion later proves this is NEVER mutated by the reissue.
        activeParams = mock(AiParameters.class);
        when(activeParams.getBodyYaml()).thenReturn(OFFENDING_BODY_YAML);
        when(parametersResolver.resolveActive()).thenReturn(activeParams);
        when(parametersResolver.effectiveModel(eq(activeParams), any())).thenReturn(OFFENDING_MODEL);
        when(parametersResolver.fallbackModel()).thenReturn(FALLBACK_MODEL);
        when(parametersResolver.effectiveSystemPrompt(any(), anyString(), any(), any()))
                .thenReturn("system-prompt");
        when(parametersResolver.effectiveTemperature(any())).thenReturn(0.0);
        when(parametersResolver.effectiveTopP(any())).thenReturn(1.0);
        when(parametersResolver.effectiveMaxTokens(any())).thenReturn(1024);
        when(parametersResolver.effectiveRagTopK(any(), anyInt())).thenReturn(4);
        when(parametersResolver.effectiveRagSimilarityThreshold(any(), anyDouble()))
                .thenReturn(0.5);

        when(baselineContextProvider.renderAsText(any())).thenReturn("");
        when(retrievalFilterBuilder.buildFor(any())).thenReturn(null);
        when(ragProperties.resolvedTopK()).thenReturn(4);
        when(ragProperties.resolvedSimilarityThreshold()).thenReturn(0.5);
        when(agentSystemPromptRulesComposer.effectiveRules()).thenReturn("");

        doNothing().when(rateLimitGuard).check(anyString());
        doNothing().when(tokenBudgetGuard).check(any());

        when(taskFileMediaResolver.resolveActive(convId))
                .thenReturn(Resolved.empty());

        sut = new DefaultChatServiceImpl(
                chatClient,
                conversationGateway,
                toolCallbacks,
                parametersResolver,
                baselineContextProvider,
                retrievalFilterBuilder,
                ragProperties,
                currentAuthentication,
                rateLimitGuard,
                tokenBudgetGuard,
                auditWriter,
                validator,
                Schedulers.immediate(),
                cancellationRegistry,
                streamingSinkHolder,
                agentSystemPromptRulesComposer,
                intentRegistry,
                taskFileMediaResolver,
                taskFileRepository,
                eventPublisher);
    }

    // ---------- Classifier static unit tests (Suggestion #4) ----------

    @Test
    void classifierAcceptsDirectRestClientResponseExceptionWithBadModelShape() {
        RestClientResponseException rcre = badModelException(404,
                "{\"error\":{\"message\":\"Model invalid/foo is not available\",\"code\":404}}");

        assertThat(DefaultChatServiceImpl.isBadModelException(rcre)).isTrue();
    }

    @Test
    void classifierAcceptsNonTransientAiExceptionWrappingBadModelRcre() {
        RestClientResponseException rcre = badModelException(400,
                "{\"error\":\"unknown model id\"}");
        NonTransientAiException wrapped = new NonTransientAiException("provider rejected", rcre);

        assertThat(DefaultChatServiceImpl.isBadModelException(wrapped)).isTrue();
    }

    @Test
    void classifierRejects5xxEvenWhenBodyMentionsModel() {
        RestClientResponseException rcre = badModelException(502,
                "{\"error\":\"model upstream timeout\"}");

        assertThat(DefaultChatServiceImpl.isBadModelException(rcre)).isFalse();
    }

    @Test
    void classifierRejects4xxWithoutModelSubstring() {
        RestClientResponseException rcre = badModelException(400,
                "{\"error\":\"prompt too long\"}");

        assertThat(DefaultChatServiceImpl.isBadModelException(rcre)).isFalse();
    }

    // ---------- Plan 16-09 classifier extensions for WebClientResponseException ----------
    // The Spring AI 1.x WebClient-backed provider stack (OpenRouter via spring-ai-openai)
    // throws WebClientResponseException — a SIBLING of RestClientResponseException (different
    // package root, both extend the RestClientException abstract superclass), NOT a subclass.
    // The classifier triad must recognise both. Without these tests the WCRE shape would
    // silently bypass the bad-model fallback on production.

    @Test
    void classifierAcceptsDirectWebClientResponseExceptionWithBadModelShape() {
        WebClientResponseException wcre = WebClientResponseException.create(
                400, "Bad Request",
                new HttpHeaders(),
                "{\"error\":{\"message\":\"Model openai/foo is not available\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(DefaultChatServiceImpl.isBadModelException(wcre)).isTrue();
    }

    @Test
    void classifierAcceptsNonTransientAiExceptionWrappingWebClientResponseException() {
        WebClientResponseException wcre = WebClientResponseException.create(
                404, "Not Found",
                new HttpHeaders(),
                "{\"error\":\"unknown model id\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        NonTransientAiException wrapped = new NonTransientAiException("provider rejected", wcre);

        assertThat(DefaultChatServiceImpl.isBadModelException(wrapped)).isTrue();
    }

    @Test
    void classifierRejects5xxWebClientResponseExceptionEvenWhenBodyMentionsModel() {
        // Pitfall 6: status-code structural filter wins over substring even for WCRE.
        WebClientResponseException wcre = WebClientResponseException.create(
                503, "Service Unavailable",
                new HttpHeaders(),
                "{\"error\":\"model upstream timeout\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(DefaultChatServiceImpl.isBadModelException(wcre)).isFalse();
    }

    // ---------- End-to-end contract tests ----------

    @Test
    void badModelExceptionTriggersOneShotReissue() {
        stubChatClientFirstCallThrowsThenSucceeds(badModelNonTransientAi(404));

        ChatResponseDto resp = sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        // The recovered turn returns successfully; the .ask call did NOT propagate the original.
        assertThat(resp).isNotNull();
        assertThat(resp.guardDenial()).isNull();
        // The DTO reports the fallback model — the one the successful response came from.
        assertThat(resp.model()).isEqualTo(FALLBACK_MODEL);
    }

    @Test
    void bothAuditRowsShareRunId() {
        stubChatClientFirstCallThrowsThenSucceeds(badModelNonTransientAi(404));

        ChatResponseDto resp = sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        // The MODEL_VALIDATION_FAILURE audit row was written under the SAME runId as the
        // recovered turn (which carries the same runId as the resp).
        ArgumentCaptor<UUID> runIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(auditWriter, times(1)).writeAuditEvent(
                eq(AuditKind.MODEL_VALIDATION_FAILURE),
                any(),
                runIdCaptor.capture(),
                eq(USER_ID),
                eq(convId),
                eq("model_validation"),
                anyString(),
                anyString(),
                anyLong(),
                eq(AiToolCallOutcome.FAILED),
                any(),
                anyString());
        assertThat(runIdCaptor.getValue()).isEqualTo(resp.runId());
    }

    @Test
    void savedAiParametersBodyYamlModelIsNotMutated() {
        stubChatClientFirstCallThrowsThenSucceeds(badModelNonTransientAi(422));

        sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        // Before AND after the reissue the saved bodyYaml is unchanged — the swap is purely
        // local to ChatOptions inside executeBlockingTurn (the saved profile stays as-is so
        // the admin's next ComboBox save corrects it).
        assertThat(activeParams.getBodyYaml()).isEqualTo(OFFENDING_BODY_YAML);
        // No DataManager.save against the AiParameters row was triggered by the fallback —
        // the resolver does NOT expose a save path on its mock, and the service never calls
        // it; the absence of a setBodyYaml interaction on the mock proves the contract.
        verify(activeParams, never()).setBodyYaml(anyString());
    }

    @Test
    void nonBadModelExceptionPropagates() {
        // 503 (not in BAD_MODEL_STATUS_CODES) + body mentioning "upstream timeout" (no "model"
        // substring) — must propagate unchanged with NO MODEL_VALIDATION_FAILURE audit row.
        RestClientResponseException rcre = badModelException(503, "upstream timeout");
        stubChatClientCallThrows(rcre);

        assertThatThrownBy(() -> sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE))
                .isInstanceOf(RuntimeException.class);

        verify(auditWriter, never()).writeAuditEvent(
                eq(AuditKind.MODEL_VALIDATION_FAILURE),
                any(), any(), anyString(), any(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any(ChatModelFallbackAppliedEvent.class));
    }

    @Test
    void _5xxResponseDoesNotTriggerReissue() {
        // 502 with the WORD "model" in the body — Pitfall 6 false-positive guard:
        // status-code structural filter wins over substring; classifier returns false.
        RestClientResponseException rcre = badModelException(502, "{\"err\":\"model upstream timeout\"}");
        stubChatClientCallThrows(rcre);

        assertThatThrownBy(() -> sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE))
                .isInstanceOf(RuntimeException.class);

        // No reissue, no audit row, no event.
        verify(auditWriter, never()).writeAuditEvent(
                eq(AuditKind.MODEL_VALIDATION_FAILURE),
                any(), any(), anyString(), any(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), anyString());
        verify(eventPublisher, never()).publishEvent(any(ChatModelFallbackAppliedEvent.class));
    }

    @Test
    void directRestClientResponseExceptionTriggersReissue() {
        // codex HIGH Concern #8 — the broad RuntimeException catch + cause-chain walker must
        // accept a DIRECT RestClientResponseException (no NonTransientAiException wrapper).
        RestClientResponseException directRcre = badModelException(400,
                "{\"error\":\"unknown model deepseek/missing\"}");
        stubChatClientFirstCallThrowsThenSucceeds(directRcre);

        ChatResponseDto resp = sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        assertThat(resp).isNotNull();
        assertThat(resp.guardDenial()).isNull();
        assertThat(resp.model()).isEqualTo(FALLBACK_MODEL);
        verify(auditWriter, times(1)).writeAuditEvent(
                eq(AuditKind.MODEL_VALIDATION_FAILURE),
                any(), any(), eq(USER_ID), eq(convId), anyString(), anyString(), anyString(),
                anyLong(), eq(AiToolCallOutcome.FAILED), any(), anyString());
    }

    @Test
    void userVisibleFallbackNotificationFires() {
        // codex HIGH Concern #9 — MODEL-02 fallback notification surface MUST be wired in
        // this phase. ChatPanelFragment listens via @EventListener; this test asserts the
        // ApplicationEventPublisher is invoked with a ChatModelFallbackAppliedEvent carrying
        // the correct runId, conversationId, offending model and fallback model.
        stubChatClientFirstCallThrowsThenSucceeds(badModelNonTransientAi(404));

        ChatResponseDto resp = sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        ArgumentCaptor<ChatModelFallbackAppliedEvent> eventCaptor =
                ArgumentCaptor.forClass(ChatModelFallbackAppliedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        ChatModelFallbackAppliedEvent event = eventCaptor.getValue();
        assertThat(event.getRunId()).isEqualTo(resp.runId());
        assertThat(event.getConversationId()).isEqualTo(convId);
        assertThat(event.getOffendingModel()).isEqualTo(OFFENDING_MODEL);
        assertThat(event.getFallbackModel()).isEqualTo(FALLBACK_MODEL);
    }

    // ---------- Stubbing helpers ----------

    private static RestClientResponseException badModelException(int status, String body) {
        return new RestClientResponseException(
                "provider " + status,
                status,
                String.valueOf(status),
                new HttpHeaders(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static NonTransientAiException badModelNonTransientAi(int status) {
        return new NonTransientAiException("provider rejected model",
                badModelException(status, "{\"error\":{\"message\":\"Model " + OFFENDING_MODEL
                        + " is not available\",\"code\":" + status + "}}"));
    }

    /**
     * Stub the deep-stubbed ChatClient so the first .call().chatClientResponse() throws
     * {@code firstException}; subsequent calls return a sane empty-content response.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubChatClientFirstCallThrowsThenSucceeds(RuntimeException firstException) {
        ChatResponse okResp = mock(ChatResponse.class);
        when(okResp.getResult()).thenReturn(null);
        ChatClientResponse ok = mock(ChatClientResponse.class);
        when(ok.chatResponse()).thenReturn(okResp);
        when(ok.context()).thenReturn(null);

        // First call throws, subsequent calls return ok. Mockito's thenThrow/thenReturn
        // chains take effect in order across successive invocations.
        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .call()
                .chatClientResponse())
                .thenThrow(firstException)
                .thenReturn(ok);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubChatClientCallThrows(RuntimeException ex) {
        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .call()
                .chatClientResponse())
                .thenThrow(ex);
    }
}
