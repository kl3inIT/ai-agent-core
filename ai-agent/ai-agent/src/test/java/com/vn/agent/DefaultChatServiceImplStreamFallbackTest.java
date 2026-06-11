package com.vn.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.extraction.IntentRegistry;
import com.vn.agent.guard.AgentSystemPromptRulesComposer;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import com.vn.agent.taskfile.AiTaskFileMediaResolver.Resolved;
import com.vn.agent.taskfile.AiTaskFileRepository;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13.1 Plan 03 stream-fallback contract test (rewritten from the BLK-01 gap-closure
 * suite to track the post-Phase-13.1 contract). Pure JUnit 5 + Mockito — intentionally NOT
 * {@code @SpringBootTest} because the deferred AiAuditEvent metamodel boot regression
 * (HUMAN-UAT item 4 / deferred-items.md) blocks Spring context boot for any agent-layer
 * {@code @SpringBootTest}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>The streaming HAPPY path calls
 *       {@link AiTaskFileMediaResolver#resolveActive(UUID)} EXACTLY ONCE per turn.</li>
 *   <li>The streaming-fallback (D-04 graceful fallback when
 *       {@code chatClient.prompt()...stream()} throws
 *       {@link UnsupportedOperationException}) ALSO performs EXACTLY ONE
 *       {@code resolveActive} call across the whole turn — proving
 *       {@code executeBlockingTurn(...)} reuses the already-resolved
 *       {@link Resolved} record rather than re-resolving (BLK-01 invariant
 *       preserved end-to-end).</li>
 *   <li>The streaming HAPPY path propagates {@code resolvedMedia.budgetExceeded()} onto
 *       the terminal {@link StreamingEvent.Final} so the UI subscriber can render the
 *       D-D1 toast on either transport mode.</li>
 *   <li>The blocking {@code .ask(...)} path propagates the same flag onto the returned
 *       {@link ChatResponseDto#budgetExceeded()}.</li>
 * </ol>
 */
class DefaultChatServiceImplStreamFallbackTest {

    // ---- Mocked collaborators (matches the post Phase-13.1-Plan-03
    //      DefaultChatServiceImpl constructor order). ----
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

    private DefaultChatServiceImpl sut;

    private final UUID convId = UUID.randomUUID();
    private static final String USER_ID = "user-fallback-test";
    private static final String MESSAGE = "describe this file";

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

        // ConversationGateway: return a conversation with the fixed convId for both
        // the streaming entrypoint and the blocking ask() path.
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(convId);
        when(conversationGateway.loadOrCreate(eq(USER_ID), any(), eq(MESSAGE)))
                .thenReturn(conversation);

        // AiParametersResolver: harmless defaults so resolver code paths are inert.
        AiParameters params = mock(AiParameters.class);
        when(parametersResolver.resolveActive()).thenReturn(params);
        when(parametersResolver.effectiveModel(any(), any())).thenReturn("test-model");
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

        // Guards: never deny in these tests.
        doNothing().when(rateLimitGuard).check(anyString());
        doNothing().when(tokenBudgetGuard).check(any());

        // Default resolveActive: one media row, budgetExceeded=false. Tests C and D
        // override this to exercise the budget-exceeded propagation contract.
        Media media = mock(Media.class);
        when(taskFileMediaResolver.resolveActive(convId))
                .thenReturn(new Resolved(List.of(media), List.of(), false, List.of(UUID.randomUUID())));

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

    /**
     * Stubs the deep-stubbed ChatClient request-spec leaves so that
     * {@code .stream().chatResponse()} throws {@link UnsupportedOperationException}
     * (the BLK-01 streaming-fallback trigger condition) AND
     * {@code .call().chatClientResponse()} returns a sane empty-content response so
     * the executeBlockingTurn fallback succeeds.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubChatClientForFallback() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getResult()).thenReturn(null);
        ChatClientResponse clientResp = mock(ChatClientResponse.class);
        when(clientResp.chatResponse()).thenReturn(chatResponse);
        when(clientResp.context()).thenReturn(null);

        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .call()
                .chatClientResponse()).thenReturn(clientResp);

        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .stream()
                .chatResponse())
                .thenThrow(new UnsupportedOperationException("stub: streaming unsupported"));
    }

    /**
     * Stubs the deep-stubbed ChatClient so {@code .stream().chatResponse()} returns
     * an empty Flux of ChatResponse — the streaming-success path; doOnComplete fires
     * once and the terminal Final event flows through.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubChatClientForStreamingSuccess() {
        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .stream()
                .chatResponse())
                .thenReturn(Flux.empty());
    }

    /**
     * Stubs the deep-stubbed ChatClient so {@code .call().chatClientResponse()} returns
     * a sane empty-content response for the blocking {@code .ask(...)} path.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubChatClientForBlocking() {
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getResult()).thenReturn(null);
        ChatClientResponse clientResp = mock(ChatClientResponse.class);
        when(clientResp.chatResponse()).thenReturn(chatResponse);
        when(clientResp.context()).thenReturn(null);

        when(chatClient.prompt()
                .system(anyString())
                .user(any(java.util.function.Consumer.class))
                .toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))
                .toolContext(any(java.util.Map.class))
                .advisors(any(java.util.function.Consumer.class))
                .options(any())
                .call()
                .chatClientResponse()).thenReturn(clientResp);
    }

    // -----------------------------------------------------------------------
    // Test A — streaming HAPPY path resolves Media exactly once per turn.
    // -----------------------------------------------------------------------
    @Test
    void streamingHappyPathCallsResolveActiveExactlyOnce() {
        stubChatClientForStreamingSuccess();

        sut.stream(USER_ID, convId, MESSAGE, Overrides.NONE).blockLast();

        // The Phase-13.1 contract: resolveActive runs ONCE per turn (inside Flux.defer
        // so blob reads happen at subscribe time, not at flux-build time).
        verify(taskFileMediaResolver, times(1)).resolveActive(convId);
    }

    // -----------------------------------------------------------------------
    // Test B — streaming-fallback path resolves Media exactly once across the
    //          fallback delegation. Proves BLK-01 invariant preserved: the
    //          executeBlockingTurn(...) catch-block path REUSES the already-
    //          resolved Resolved record rather than re-resolving.
    // -----------------------------------------------------------------------
    @Test
    void streamingFallbackDelegatesToExecuteBlockingTurnExactlyOnce() {
        stubChatClientForFallback();

        sut.stream(USER_ID, convId, MESSAGE, Overrides.NONE).blockLast();

        // The whole turn (failed stream + executeBlockingTurn fallback) makes EXACTLY
        // ONE resolveActive call. If the fallback recursed through ask(...) instead of
        // executeBlockingTurn(...) the count would be 2 — that is the BLK-01 regression
        // this assertion guards against.
        verify(taskFileMediaResolver, times(1)).resolveActive(convId);
    }

    // -----------------------------------------------------------------------
    // Test C — streaming HAPPY path propagates budgetExceeded onto Final.
    // -----------------------------------------------------------------------
    @Test
    void streamingHappyPathPropagatesBudgetExceeded() {
        // Override the default Resolved stub: budgetExceeded=true.
        Media media = mock(Media.class);
        when(taskFileMediaResolver.resolveActive(convId))
                .thenReturn(new Resolved(List.of(media), List.of(), true, List.of(UUID.randomUUID())));
        stubChatClientForStreamingSuccess();

        StreamingEvent terminal = sut.stream(USER_ID, convId, MESSAGE, Overrides.NONE).blockLast();

        // The terminal event of every streaming turn is a StreamingEvent.Final carrying
        // the D-D1 budgetExceeded flag.
        assertThat(terminal).isInstanceOf(StreamingEvent.Final.class);
        StreamingEvent.Final finalEvt = (StreamingEvent.Final) terminal;
        assertThat(finalEvt.budgetExceeded()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test D — blocking .ask path propagates budgetExceeded onto ChatResponseDto.
    // -----------------------------------------------------------------------
    @Test
    void blockingPathPropagatesBudgetExceeded() {
        Media media = mock(Media.class);
        when(taskFileMediaResolver.resolveActive(convId))
                .thenReturn(new Resolved(List.of(media), List.of(), true, List.of(UUID.randomUUID())));
        stubChatClientForBlocking();

        ChatResponseDto resp = sut.ask(USER_ID, convId, MESSAGE, Overrides.NONE);

        // D-D1: budget-exceeded flag flows from Resolved.budgetExceeded() into the
        // returned DTO so the blocking-path subscriber renders the same toast.
        assertThat(resp.budgetExceeded()).isTrue();
    }
}
