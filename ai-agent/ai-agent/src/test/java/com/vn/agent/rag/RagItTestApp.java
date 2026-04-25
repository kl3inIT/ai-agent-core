package com.vn.agent.rag;

import com.vn.agent.AIConfiguration;
import io.jmix.core.annotation.JmixModule;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dedicated {@link SpringBootConfiguration} for Phase 5 RAG integration tests.
 *
 * <p>Differs from the Phase 4 {@code AITestConfiguration} in two ways:
 * <ol>
 *   <li><b>No embedded HSQL {@code DataSource} bean.</b> The
 *       {@code @DynamicPropertySource} registered in {@link AbstractRagIntegrationTest} points
 *       Spring Boot's {@code DataSourceAutoConfiguration} at the Testcontainers pgvector
 *       container. Supplying a competing {@code @Primary DataSource} bean would silently win
 *       and our pgvector-specific assertions (role-scoped retrieval, HNSW index, vector(2000))
 *       would collapse onto HSQL which has none of those features.</li>
 *   <li><b>Mock {@link ChatClient.Builder} is still provided</b> because the RAG tests don't
 *       exercise a real LLM — role-scoping / atomicity assertions only touch the vector store
 *       and upload/delete services. {@code ChatServiceFilterParamContractTest} uses a spy on
 *       a real chain and doesn't need this mock either, so it's kept minimal here.</li>
 * </ol>
 *
 * <p>Loads {@code test-app.properties} for the Phase 4 defaults ({@code
 * jmix.ai-agent.defaults.*}) so {@code AiParametersResolver} binds cleanly at context start.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AIConfiguration.class)
@PropertySource("classpath:/com/vn/agent/test-app.properties")
@JmixModule(id = "com.vn.agent.rag.test", dependsOn = AIConfiguration.class)
public class RagItTestApp {

    /**
     * Mock {@code ChatClient.Builder}. Wires the minimal call graph so any Phase 4
     * wiring that runs through {@code ChatClientFactory} can build a usable client without
     * driving a real LLM. RAG tests that want a real advisor chain (e.g., the filter-param
     * contract test) can spy on the injected {@code ChatClient} downstream.
     */
    @Bean
    ChatClient.Builder ragItChatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("test");
        when(callSpec.chatResponse()).thenReturn(null);
        return builder;
    }
}
