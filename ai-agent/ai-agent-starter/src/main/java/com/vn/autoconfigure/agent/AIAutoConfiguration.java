package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for the AI Agent add-on.
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Imports {@link AIConfiguration} (functional module's Jmix-aware @Configuration) and supplies a default
 * {@link ChatClient} bean built from Spring AI's auto-configured {@link ChatClient.Builder}. Host apps may
 * override by providing their own {@code ChatClient} @Bean.</p>
 */
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
