package com.vn.agent;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.tools.AgentToolCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase-4 default {@link ChatService} implementation (ORCH-01, ORCH-05).
 *
 * <p>Per D-01: the {@link ChatClient} is a singleton cached bean built by {@code ChatClientFactory};
 * each {@code ask} call reuses it via per-request {@code .prompt()}. Per-turn flow:</p>
 *
 * <ol>
 *   <li>{@link ConversationGateway#loadOrCreate(String, UUID, String)} enforces D-09 opacity via
 *       {@code createdBy} (B3) and applies the D-08 title rule ({@code firstMessage} truncated to
 *       80 chars) on auto-create (B4).</li>
 *   <li>{@link BaselineContextProvider#renderAsText(UUID)} produces a deterministic
 *       {@code agent.*} text block (D-15, B-NEW-1 — text mode, NOT the Map variant) prepended to
 *       the profile system prompt for this request.</li>
 *   <li>{@code runId} is pre-allocated BEFORE {@code chatClient.prompt()} (B8) and handed to
 *       {@code AuditAdvisor} via advisor context key {@code audit.runId}, eliminating the race
 *       where the service reading {@code RunContext.get()} after the call always saw {@code null}
 *       because {@code AuditAdvisor}'s {@code finally}-block had already cleared it.</li>
 *   <li>{@code OpenAiChatOptions} applies the resolved model / temperature / top-p / max-tokens
 *       per request on top of the cached client.</li>
 * </ol>
 */
@Service
public class DefaultChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatServiceImpl.class);

    private final ChatClient chatClient;
    private final ConversationGateway conversationGateway;
    private final AgentToolCallbacks toolCallbacks;
    private final AiParametersResolver parametersResolver;
    private final BaselineContextProvider baselineContextProvider;

    public DefaultChatServiceImpl(ChatClient chatClient,
                                  ConversationGateway conversationGateway,
                                  AgentToolCallbacks toolCallbacks,
                                  AiParametersResolver parametersResolver,
                                  BaselineContextProvider baselineContextProvider) {
        this.chatClient = chatClient;
        this.conversationGateway = conversationGateway;
        this.toolCallbacks = toolCallbacks;
        this.parametersResolver = parametersResolver;
        this.baselineContextProvider = baselineContextProvider;
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message) {
        // B4: pass `message` as firstMessage so auto-created conversation gets a title.
        AiConversation conv = conversationGateway.loadOrCreate(userId, conversationId, message);
        UUID convId = conv.getId();

        AiParameters active = parametersResolver.resolveActive();
        String model = parametersResolver.effectiveModel(active);
        String profileSystemPrompt = parametersResolver.effectiveSystemPrompt(active);

        // B5 + B-NEW-1: baseline as deterministic TEXT (D-15) prepended to profile prompt.
        String baselineText = baselineContextProvider.renderAsText(convId);
        String composedSystemPrompt = baselineText
                + (profileSystemPrompt != null && !profileSystemPrompt.isBlank()
                        ? "\n\n" + profileSystemPrompt
                        : "");

        // B8: pre-allocate runId so AuditAdvisor.before() reads it from advisor context instead
        // of generating a fresh one. Eliminates the RunContext lifecycle race.
        UUID runId = UUID.randomUUID();

        long start = System.nanoTime();
        ChatResponse springResponse = chatClient.prompt()
                .system(composedSystemPrompt)
                .user(message)
                .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
                .advisors(advisorSpec -> advisorSpec
                        .param(ChatMemory.CONVERSATION_ID, convId.toString())
                        .param("audit.runId", runId))
                .options(ChatOptions.builder()
                        .model(model)
                        .temperature(parametersResolver.effectiveTemperature(active))
                        .topP(parametersResolver.effectiveTopP(active))
                        .maxTokens(parametersResolver.effectiveMaxTokens(active))
                        .build())
                .call()
                .chatResponse();
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        String content = springResponse != null && springResponse.getResult() != null
                && springResponse.getResult().getOutput() != null
                        ? springResponse.getResult().getOutput().getText()
                        : "";
        if (content == null) {
            content = "";
        }
        log.debug("ChatService.ask convId={} runId={} model={} latencyMs={}", convId, runId, model, latencyMs);
        return new ChatResponseDto(convId, runId, content, model, latencyMs);
    }
}
