package com.vn.agent;

import io.jmix.core.annotation.JmixModule;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AIConfiguration.class)
@PropertySource("classpath:/com/vn/agent/test-app.properties")
@JmixModule(id = "com.vn.agent.test", dependsOn = AIConfiguration.class)
public class AITestConfiguration {

    @Bean
    @Primary
    DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.HSQL)
                .build();
    }

    /**
     * Test-only {@link ChatClient.Builder}. The functional module's test context does not load
     * the starter (which pulls {@code spring-ai-starter-model-openai} and auto-configures the real
     * {@code ChatClient.Builder}). Supplying a Mockito-backed stub here keeps {@code AITest.contextLoads}
     * green without requiring a live OpenRouter key. Production wiring is unaffected — host apps
     * depend on {@code ai-agent-starter}, which brings the real auto-configured builder.
     */
    @Bean
    ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("test");
        return builder;
    }
}
