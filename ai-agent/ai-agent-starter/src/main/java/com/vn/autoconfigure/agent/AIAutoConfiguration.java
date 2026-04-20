package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the AI Agent add-on (Phase 4).
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Imports {@link AIConfiguration} (functional module's Jmix-aware @Configuration).</p>
 *
 * <p>Phase 4 ownership changes:</p>
 * <ul>
 *   <li>The default {@code ChatClient} @Bean is now provided by {@code ChatClientFactory} inside
 *       the ai-agent module (D-01) — this autoconfig no longer owns it.</li>
 *   <li>This autoconfig supplies {@link ChatMemory} (a {@link MessageWindowChatMemory} of size 20)
 *       and a raw {@link JdbcChatMemoryRepository}, both {@code @ConditionalOnMissingBean}. The
 *       {@code @Primary ProjectingChatMemoryRepository} in the ai-agent module decorates the raw
 *       JDBC repository so the {@code ChatMemory} builder sees the dual-layer decorator (D-08).</li>
 * </ul>
 */
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JdbcChatMemoryRepository.class)
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();
    }
}
