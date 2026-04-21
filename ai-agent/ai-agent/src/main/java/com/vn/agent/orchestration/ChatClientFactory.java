package com.vn.agent.orchestration;

import com.vn.agent.audit.AuditAdvisor;
import com.vn.agent.entity.AiParameters;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Builds the single cached {@link ChatClient} bean (D-01, ORCH-01). One {@code ChatClient} per
 * application instance — thread-safe per Spring AI docs; {@code DefaultChatServiceImpl} calls
 * {@code chatClient.prompt()} per request with per-request {@code .system()}, {@code .user()},
 * {@code .toolCallbacks()}, {@code .advisors()}, and {@code .options()}.
 *
 * <p>Advisor ordering (D-02, verified via {@code javap} probes against
 * {@code spring-ai-client-chat-1.1.4.jar} — see {@code ToolCallAdvisorBuilderConstants}):</p>
 *
 * <ol>
 *   <li>{@link AuditAdvisor} at {@code Ordered.HIGHEST_PRECEDENCE} — outermost; records chat-level
 *       PRE/POST audit rows correlated by runId.</li>
 *   <li>{@link MessageChatMemoryAdvisor} at {@code HIGHEST_PRECEDENCE + 200} — uses
 *       {@code .order(int)} setter on its builder.</li>
 *   <li>{@link RetrievalAugmentationAdvisor} at {@code HIGHEST_PRECEDENCE + 250} — built by
 *       {@code RetrievalAugmentationAdvisorFactory}; per-request role-scoped retrieval filter is
 *       supplied by {@code DefaultChatServiceImpl} via the
 *       {@code VectorStoreDocumentRetriever.FILTER_EXPRESSION} advisor param.</li>
 *   <li>{@link ToolCallAdvisor} at {@code HIGHEST_PRECEDENCE + 300} with internal memory disabled
 *       via {@code .disableMemory()}; order setter on this builder is named
 *       {@code .advisorOrder(int)} (different from MessageChatMemoryAdvisor).</li>
 *   <li>{@code OutputScannerAdvisor} at {@code HIGHEST_PRECEDENCE + 400} (Plan 06-04) —
 *       innermost CallAdvisor; inspects assistant content for configured patterns and
 *       promotes a stable pattern-key flag into the advisor context (never the matched
 *       text, per D-17/D-18). Injected by qualified bean name so hosts can override the
 *       scanner without shadowing the entire advisor chain. The {@link ToolCallingManager}
 *       is auto-picked via {@code @Primary} when the starter's guard autoconfig registers
 *       {@code GuardedToolCallingManager} — no explicit setter needed here.</li>
 * </ol>
 */
@Configuration
public class ChatClientFactory {

    @Bean
    public ChatClient defaultChatClient(ChatModel chatModel,
                                        ChatMemory chatMemory,
                                        AuditAdvisor auditAdvisor,
                                        AiParametersResolver parametersResolver,
                                        ToolCallingManager toolCallingManager,
                                        RetrievalAugmentationAdvisor ragAdvisor,
                                        @Qualifier("outputScannerAdvisor") CallAdvisor outputScannerAdvisor) {
        AiParameters active = parametersResolver.resolveActive();
        String systemPrompt = parametersResolver.effectiveSystemPrompt(active);

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE + 200)
                .build();

        ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .disableMemory()
                .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 300)
                .build();

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt != null ? systemPrompt : AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT)
                .defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor, outputScannerAdvisor)
                .build();
    }
}
