package com.vn.agent.orchestration;

import com.vn.agent.audit.AuditAdvisor;
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
 * <p><b>No bean-time {@code DataManager} access.</b> Profile resolution
 * ({@link AiParametersResolver#resolveActive()}) is intentionally deferred to
 * {@code DefaultChatServiceImpl.ask(...)} so this {@code @Bean} factory method never triggers
 * Jmix's {@code eclipselink_JpaDataStore} / {@code eclipselink_QueryCache} bootstrap mid-
 * context-refresh. Doing a Jmix query here caused
 * {@code IllegalStateException: Unable to find cache: jmix-eclipselink-query-cache} at
 * {@code StandardQueryCache.java:52} because the Spring {@code CacheManager} has not yet
 * registered the {@code jmix-eclipselink-query-cache} bucket when the {@code ChatClient}
 * bean is instantiated. Per-request resolution in {@code DefaultChatServiceImpl} also
 * supersedes any baked-in {@code .defaultSystem(...)} via {@code chatClient.prompt().system(...)},
 * so the earlier bean-time call was functionally dead in addition to being unsafe.</p>
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
 *   <li>{@link ToolCallAdvisor} at {@code HIGHEST_PRECEDENCE + 300} with
 *       {@code conversationHistoryEnabled(true)} so recursive tool rounds keep the assistant
 *       tool-call message paired with the tool response. OpenRouter/OpenAI-compatible APIs reject
 *       orphan {@code role=tool} messages ({@code 400 messages.[1].role}) when that pairing is
 *       missing. Order setter on this builder is named {@code .advisorOrder(int)} (different from
 *       MessageChatMemoryAdvisor).</li>
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
                                        ToolCallingManager toolCallingManager,
                                        RetrievalAugmentationAdvisor ragAdvisor,
                                        @Qualifier("outputScannerAdvisor") CallAdvisor outputScannerAdvisor) {
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE + 200)
                .build();

        ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(true)
                .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 300)
                .build();

        // .defaultSystem(...) is intentionally the static fallback — DefaultChatServiceImpl
        // calls chatClient.prompt().system(composedSystemPrompt) per request, which supersedes
        // whatever is set here. We must NOT resolve the active AiParameters row at bean-factory
        // time: doing so forces Jmix's JpaDataStore/QueryCache chain to initialize before
        // Spring's CacheManager has registered jmix-eclipselink-query-cache, crashing context
        // startup with IllegalStateException at StandardQueryCache.java:52.
        return ChatClient.builder(chatModel)
                .defaultSystem(AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT)
                .defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor, outputScannerAdvisor)
                .build();
    }
}
