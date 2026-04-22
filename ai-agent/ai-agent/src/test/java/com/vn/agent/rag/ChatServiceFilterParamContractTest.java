package com.vn.agent.rag;

import com.vn.agent.DefaultChatServiceImpl;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G-1 guard (ROADMAP): {@code DefaultChatServiceImpl.ask(...)} MUST set the advisor param
 * {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} on every non-admin request and MUST
 * OMIT it on the admin-bypass path (per the plan 05-02 contract + RESEARCH Pitfall #3: a null
 * per-request param would collide with the retriever's per-request-filter resolution — the
 * service must conditionally skip the call, not pass {@code null}).
 *
 * <p>This is a pure Mockito test — no Spring context, no Docker. It isolates the single
 * behaviour that matters for G-1: the captured advisor consumer invokes
 * {@code .param(FILTER_EXPRESSION, ragFilter)} iff {@code retrievalFilterBuilder.buildFor(auth)}
 * returned non-null.</p>
 */
class ChatServiceFilterParamContractTest {

    ChatClient chatClient;
    ChatClient.ChatClientRequestSpec requestSpec;
    ChatClient.CallResponseSpec callSpec;
    ConversationGateway conversationGateway;
    AgentToolCallbacks toolCallbacks;
    AiParametersResolver parametersResolver;
    BaselineContextProvider baselineContextProvider;
    RetrievalFilterBuilder retrievalFilterBuilder;
    CurrentAuthentication currentAuthentication;
    SystemAuthenticator systemAuthenticator;
    RateLimitGuard rateLimitGuard;
    TokenBudgetGuard tokenBudgetGuard;
    AuditWriter auditWriter;
    Validator validator;
    ChatClient.AdvisorSpec advisorSpec;
    ChatClientResponse chatClientResponse;
    CancellationRegistry cancellationRegistry;
    StreamingSinkHolder streamingSinkHolder;

    DefaultChatServiceImpl service;

    UUID convId;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        conversationGateway = mock(ConversationGateway.class);
        toolCallbacks = mock(AgentToolCallbacks.class);
        parametersResolver = mock(AiParametersResolver.class);
        baselineContextProvider = mock(BaselineContextProvider.class);
        retrievalFilterBuilder = mock(RetrievalFilterBuilder.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        systemAuthenticator = mock(SystemAuthenticator.class);
        rateLimitGuard = mock(RateLimitGuard.class);
        tokenBudgetGuard = mock(TokenBudgetGuard.class);
        auditWriter = mock(AuditWriter.class);
        validator = mock(Validator.class);
        advisorSpec = mock(ChatClient.AdvisorSpec.class);
        chatClientResponse = mock(ChatClientResponse.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        streamingSinkHolder = mock(StreamingSinkHolder.class);

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
        when(toolCallbacks.callbacksFor(anyString(), any())).thenReturn(new org.springframework.ai.tool.ToolCallback[]{});

        // Fluent chain — every builder call returns requestSpec; .call().chatResponse() returns null.
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatClientResponse()).thenReturn(chatClientResponse);
        when(chatClientResponse.chatResponse()).thenReturn(null);
        when(chatClientResponse.context()).thenReturn(new java.util.HashMap<>());

        // advisorSpec is fluent — every .param(...) returns itself.
        when(advisorSpec.param(anyString(), any())).thenReturn(advisorSpec);

        service = new DefaultChatServiceImpl(chatClient, conversationGateway, toolCallbacks,
                parametersResolver, baselineContextProvider, retrievalFilterBuilder,
                currentAuthentication, systemAuthenticator, rateLimitGuard, tokenBudgetGuard, auditWriter, validator,
                reactor.core.scheduler.Schedulers.immediate(),
                cancellationRegistry,
                streamingSinkHolder);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void test_filter_expression_param_always_set_on_every_non_admin_request() {
        // Non-admin path: builder returns a concrete filter expression.
        Filter.Expression ragFilter = new FilterExpressionBuilder()
                .eq("embeddingModel", "openai/text-embedding-3-small").build();
        when(retrievalFilterBuilder.buildFor(any())).thenReturn(ragFilter);

        service.ask("alice", null, "hello");

        // Capture the advisors consumer; invoke it against our mock AdvisorSpec to observe the
        // .param(...) calls the service wires up.
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec, atLeastOnce()).advisors(captor.capture());
        Consumer<ChatClient.AdvisorSpec> consumer = captor.getValue();
        consumer.accept(advisorSpec);

        // G-1 REGRESSION GUARD: FILTER_EXPRESSION MUST be set on every non-admin request.
        verify(advisorSpec).param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, ragFilter);

        // Conversation id + runId are always set.
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, convId.toString());
        verify(advisorSpec).param(org.mockito.ArgumentMatchers.eq("audit.runId"), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void test_filter_expression_param_omitted_on_admin_bypass() {
        // Admin bypass path: buildFor returns null — service MUST NOT set FILTER_EXPRESSION.
        when(retrievalFilterBuilder.buildFor(any())).thenReturn(null);

        service.ask("alice", null, "hello");

        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec, atLeastOnce()).advisors(captor.capture());
        Consumer<ChatClient.AdvisorSpec> consumer = captor.getValue();
        consumer.accept(advisorSpec);

        // Pitfall #3: passing .param(FILTER_EXPRESSION, null) would silently disable the per-request
        // filter resolution. The service MUST skip the call entirely on admin bypass.
        verify(advisorSpec, never()).param(
                org.mockito.ArgumentMatchers.eq(VectorStoreDocumentRetriever.FILTER_EXPRESSION),
                any());

        // Memory + audit params still present.
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, convId.toString());
    }

    @Test
    void stream_wrapsSubscription_inPerUserAuthenticationScope() {
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(reactor.core.publisher.Flux.empty());

        service.stream("alice", null, "hello", null).blockLast();

        var order = inOrder(systemAuthenticator, conversationGateway);
        order.verify(systemAuthenticator).begin("alice");
        order.verify(conversationGateway).loadOrCreate("alice", null, "hello");
        order.verify(systemAuthenticator).end();
    }
}
