package com.vn.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.action.ActionIntentId;
import com.vn.agent.conversation.ConversationTitleEligibilityPublisher;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.extraction.ExtractionInput;
import com.vn.agent.extraction.ExtractionResult;
import com.vn.agent.extraction.ExtractionSchemaException;
import com.vn.agent.extraction.ExtractionService;
import com.vn.agent.extraction.ExtractionSourceText;
import com.vn.agent.extraction.ExtractionToolBridge;
import com.vn.agent.extraction.IntentOption;
import com.vn.agent.extraction.IntentRegistry;
import com.vn.agent.guard.AgentSystemPromptRulesComposer;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import com.vn.agent.taskfile.AiTaskFileRepository;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultChatServiceIntentRoutingTest {

    private static final String USER_ID = "alice";
    private static final String MESSAGE = "prepare a customer draft";
    private static final String INTENT_ID = "customer-from-source";
    private static final IntentOption CUSTOMER_INTENT = new IntentOption(
            INTENT_ID, "Customer draft", "Prepare a Customer", "jmixapp_Customer", false);

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
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
    private CancellationRegistry cancellationRegistry;
    private StreamingSinkHolder streamingSinkHolder;
    private AgentSystemPromptRulesComposer rulesComposer;
    private IntentRegistry intentRegistry;
    private ConversationTitleEligibilityPublisher titleEligibilityPublisher;
    private AiTaskFileMediaResolver taskFileMediaResolver;
    private AiTaskFileRepository taskFileRepository;
    private DefaultChatServiceImpl service;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
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
        validator = mock(Validator.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        streamingSinkHolder = mock(StreamingSinkHolder.class);
        rulesComposer = mock(AgentSystemPromptRulesComposer.class);
        intentRegistry = mock(IntentRegistry.class);
        titleEligibilityPublisher = mock(ConversationTitleEligibilityPublisher.class);
        taskFileMediaResolver = mock(AiTaskFileMediaResolver.class);
        taskFileRepository = mock(AiTaskFileRepository.class);

        conversationId = UUID.randomUUID();
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(conversationGateway.loadOrCreate(eq(USER_ID), any(), eq(MESSAGE)))
                .thenReturn(conversation);

        AiParameters active = mock(AiParameters.class);
        when(parametersResolver.resolveActive()).thenReturn(active);
        when(parametersResolver.effectiveModel(any(), any())).thenReturn("test-model");
        when(parametersResolver.effectiveSystemPrompt(any(), anyString(), any(), any()))
                .thenReturn("profile-system");
        when(parametersResolver.effectiveTemperature(any())).thenReturn(0.0);
        when(parametersResolver.effectiveTopP(any())).thenReturn(1.0);
        when(parametersResolver.effectiveMaxTokens(any())).thenReturn(1024);
        when(parametersResolver.effectiveRagTopK(any(), anyInt())).thenReturn(4);
        when(parametersResolver.effectiveRagSimilarityThreshold(any(), anyDouble()))
                .thenReturn(0.5);

        when(baselineContextProvider.renderAsText(any())).thenReturn("agent.context");
        when(retrievalFilterBuilder.buildFor(any())).thenReturn(null);
        when(rulesComposer.effectiveRules()).thenReturn("base-rules");
        when(rulesComposer.effectiveRules(INTENT_ID, "Customer draft"))
                .thenReturn("base-rules\nnamed-intent-rules");
        when(rulesComposer.effectiveActionRules(ActionIntentId.CREATE_NOW))
                .thenReturn("base-rules\naction-rules");
        when(intentRegistry.eligibleForCurrentUser()).thenReturn(List.of(CUSTOMER_INTENT));
        when(taskFileMediaResolver.resolveActive(conversationId))
                .thenReturn(AiTaskFileMediaResolver.Resolved.empty());
        doNothing().when(rateLimitGuard).check(anyString());
        doNothing().when(tokenBudgetGuard).check(any());

        service = new DefaultChatServiceImpl(
                chatClient,
                conversationGateway,
                toolCallbacks,
                parametersResolver,
                baselineContextProvider,
                retrievalFilterBuilder,
                ragProperties(),
                currentAuthentication,
                rateLimitGuard,
                tokenBudgetGuard,
                auditWriter,
                validator,
                Schedulers.immediate(),
                cancellationRegistry,
                streamingSinkHolder,
                rulesComposer,
                intentRegistry,
                titleEligibilityPublisher,
                taskFileMediaResolver,
                taskFileRepository);
    }

    @Test
    void unknownIntentFailsClosedBeforePromptCreation() {
        when(intentRegistry.eligibleForCurrentUser()).thenReturn(List.of());

        ChatResponseDto response = service.ask(USER_ID, conversationId, MESSAGE,
                Overrides.NONE, "missing-intent");

        assertThat(response.guardDenial()).isNotNull();
        assertThat(response.guardDenial().messageKey())
                .isEqualTo("chatView.intent.unknownIntent");
        verify(chatClient, never()).prompt();
        verify(toolCallbacks, never()).callbacksFor(anyString(), any(), anyString());
    }

    @Test
    void callbackMisconfigurationFailsClosedBeforeBlockingModelCall() {
        stubBlockingChatClient();
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, INTENT_ID))
                .thenThrow(new AgentToolCallbacks.ToolConfigurationException(
                        AgentToolCallbacks.PREPARE_FORM_DRAFT_TOOL_NAME, 0));

        ChatResponseDto response = service.ask(USER_ID, conversationId, MESSAGE,
                Overrides.NONE, INTENT_ID);

        assertThat(response.guardDenial()).isNotNull();
        assertThat(response.guardDenial().messageKey())
                .isEqualTo("chatView.intent.configurationError");
        verify(callSpec, never()).chatClientResponse();
    }

    @Test
    void streamingUnknownIntentFailsClosedBeforePromptCreation() {
        when(intentRegistry.eligibleForCurrentUser()).thenReturn(List.of());

        List<StreamingEvent> events = service.stream(USER_ID, conversationId, MESSAGE,
                Overrides.NONE, "missing-intent").collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(0)).isInstanceOf(StreamingEvent.Error.class);
        StreamingEvent.Error error = (StreamingEvent.Error) events.get(0);
        assertThat(error.messageKey()).isEqualTo("chatView.intent.unknownIntent");
        verify(chatClient, never()).prompt();
    }

    @Test
    void streamingFirstTurnEmitsServiceCreatedConversationId() {
        stubStreamingChatClient("draft ready");
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, INTENT_ID))
                .thenReturn(new ToolCallback[0]);

        List<StreamingEvent> events = service.stream(USER_ID, null, MESSAGE,
                Overrides.NONE, INTENT_ID).collectList().block();

        assertThat(events).isNotNull();
        StreamingEvent.Final finalEvent = events.stream()
                .filter(StreamingEvent.Final.class::isInstance)
                .map(StreamingEvent.Final.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(finalEvent.conversationId()).isEqualTo(conversationId);
        verify(conversationGateway).loadOrCreate(USER_ID, null, MESSAGE);
    }

    @Test
    void streamingRateLimitDenialDoesNotCreateFirstTurnConversation() {
        doThrow(new com.vn.agent.guard.RateLimitExceededException(60))
                .when(rateLimitGuard).check(USER_ID);

        List<StreamingEvent> events = service.stream(USER_ID, null, MESSAGE,
                Overrides.NONE, INTENT_ID).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(0)).isInstanceOf(StreamingEvent.Error.class);
        StreamingEvent.Error error = (StreamingEvent.Error) events.get(0);
        assertThat(error.messageKey()).isEqualTo("ai-agent.guard.rate-limit-exceeded");
        verify(conversationGateway, never()).loadOrCreate(anyString(), any(), anyString());
    }

    @Test
    void streamingFallbackPreservesNamedIntentForBothCallbackAssemblies() {
        stubStreamingFallbackChatClient();
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, INTENT_ID))
                .thenReturn(new ToolCallback[0]);

        service.stream(USER_ID, conversationId, MESSAGE, Overrides.NONE, INTENT_ID)
                .blockLast();

        verify(toolCallbacks, times(2)).callbacksFor(USER_ID, conversationId, INTENT_ID);
        verify(taskFileMediaResolver, times(1)).resolveActive(conversationId);
    }

    @Test
    void streamingActionTurnAddsProposalContextToSystemPrompt() {
        stubStreamingChatClient("created");
        String actionIntent = ActionIntentId.selectionParameter(ActionIntentId.CREATE_NOW);
        String actionMessage = "Create now";
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(conversationGateway.loadOrCreate(USER_ID, conversationId, actionMessage))
                .thenReturn(conversation);
        String privateContext = "Proposal id: secret\nCollected values JSON: {\"name\":\"Acme\"}";
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, actionIntent))
                .thenReturn(new ToolCallback[0]);

        service.stream(USER_ID, conversationId, actionMessage, Overrides.NONE, actionIntent, privateContext)
                .collectList()
                .block();

        org.mockito.ArgumentCaptor<String> systemPromptCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemPromptCaptor.capture());
        assertThat(systemPromptCaptor.getValue())
                .contains("Private per-turn action context:")
                .contains(privateContext)
                .contains("This private context is not user-authored text");
    }

    @Test
    void blockingIntentTurnPublishesDocumentSourceTextsToRunContext() {
        stubBlockingChatClient();
        UUID taskFileId = UUID.randomUUID();
        AiTaskFileMediaResolver.DocumentText documentText =
                new AiTaskFileMediaResolver.DocumentText("customer.txt", "Customer Acme", false);
        when(taskFileMediaResolver.resolveActive(conversationId))
                .thenReturn(new AiTaskFileMediaResolver.Resolved(
                        List.of(), List.of(documentText), false, List.of(taskFileId)));
        List<List<ExtractionSourceText>> capturedSourceTexts = new ArrayList<>();
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, INTENT_ID))
                .thenAnswer(invocation -> {
                    capturedSourceTexts.add(RunContext.getSourceTexts());
                    return new ToolCallback[0];
                });

        service.ask(USER_ID, conversationId, MESSAGE, Overrides.NONE, INTENT_ID);

        assertThat(capturedSourceTexts).hasSize(1);
        assertThat(capturedSourceTexts.get(0))
                .containsExactly(new ExtractionSourceText("customer.txt", "Customer Acme", false));
    }

    @Test
    void streamingIntentTurnPublishesDocumentSourceTextsToRunContext() {
        stubStreamingChatClient("draft ready");
        UUID taskFileId = UUID.randomUUID();
        AiTaskFileMediaResolver.DocumentText documentText =
                new AiTaskFileMediaResolver.DocumentText("customer.txt", "Customer Acme", true);
        when(taskFileMediaResolver.resolveActive(conversationId))
                .thenReturn(new AiTaskFileMediaResolver.Resolved(
                        List.of(), List.of(documentText), false, List.of(taskFileId)));
        List<List<ExtractionSourceText>> capturedSourceTexts = new ArrayList<>();
        when(toolCallbacks.callbacksFor(USER_ID, conversationId, INTENT_ID))
                .thenAnswer(invocation -> {
                    capturedSourceTexts.add(RunContext.getSourceTexts());
                    return new ToolCallback[0];
                });

        service.stream(USER_ID, conversationId, MESSAGE, Overrides.NONE, INTENT_ID)
                .collectList()
                .block();

        assertThat(capturedSourceTexts).hasSize(1);
        assertThat(capturedSourceTexts.get(0))
                .containsExactly(new ExtractionSourceText("customer.txt", "Customer Acme", true));
    }

    @Test
    void namedExtractionTurnRejectsSecondPrepareFormDraftInvocation() {
        ExtractionService extractionService = mock(ExtractionService.class);
        UUID draftId = UUID.randomUUID();
        when(extractionService.prepare(eq(INTENT_ID), any(ExtractionInput.class)))
                .thenReturn(new ExtractionResult(draftId, "jmixapp_Customer", "Customer draft"));
        ExtractionToolBridge bridge = new ExtractionToolBridge(extractionService);
        RunContext.setExtractionTurn(INTENT_ID, conversationId, MESSAGE, List.of(), List.of());
        try {
            Map<String, Object> first = bridge.prepareFormDraft(INTENT_ID, Map.of());

            assertThat(first).containsEntry("draftId", draftId);
            assertThatThrownBy(() -> bridge.prepareFormDraft(INTENT_ID, Map.of()))
                    .isInstanceOf(ExtractionSchemaException.class)
                    .extracting("code")
                    .isEqualTo(ExtractionSchemaException.CODE_VALIDATION_FAILURE);
            verify(extractionService, times(1)).prepare(eq(INTENT_ID), any(ExtractionInput.class));
        } finally {
            RunContext.clear();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubBlockingChatClient() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatClientResponse()).thenReturn(chatClientResponse);
        when(chatClientResponse.chatResponse()).thenReturn(chatResponse(""));
        when(chatClientResponse.context()).thenReturn(new HashMap<>());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubStreamingFallbackChatClient() {
        stubBlockingChatClient();
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse())
                .thenThrow(new UnsupportedOperationException("streaming unsupported"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubStreamingChatClient(String content) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse(content)));
    }

    private static AiAgentRagProperties ragProperties() {
        AiAgentRagProperties ragProperties = mock(AiAgentRagProperties.class);
        when(ragProperties.resolvedTopK()).thenReturn(4);
        when(ragProperties.resolvedSimilarityThreshold()).thenReturn(0.5);
        return ragProperties;
    }

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
