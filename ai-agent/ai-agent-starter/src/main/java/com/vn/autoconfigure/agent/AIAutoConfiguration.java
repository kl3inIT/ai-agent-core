package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
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

    /**
     * Passthrough bean (D-02). The OpenAI starter auto-configures {@link EmbeddingModel}
     * out of the box; this {@code @ConditionalOnMissingBean} declaration is a no-op by
     * default but gives hosts a documented override seam — declare a host
     * {@code @Bean EmbeddingModel} and this method is skipped. Bean-collision across
     * starters is caught by {@code EmbeddingModelBeanCollisionTest} (RAG-02).
     *
     * <p>Intentionally accepts {@link EmbeddingModel} by parameter (not produced
     * fresh) — this is a passthrough, not a construction site. A host swapping the
     * provider replaces the upstream bean directly via a {@code @Primary @Bean}.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingModel aiAgentEmbeddingModel(EmbeddingModel autoConfiguredEmbeddingModel) {
        return autoConfiguredEmbeddingModel;
    }

    /**
     * PgVectorStore bound to the Phase 2 Liquibase-owned table.
     * {@code initializeSchema(false)} is non-negotiable — Liquibase
     * ({@code 070-ai-kb-vector-store.xml}) owns the DDL and the HNSW index, and
     * {@code initializeSchema(true)} would violate the single-owner contract
     * (RESEARCH Pitfall #2). Dimension is pinned from
     * {@link AiAgentEmbeddingProperties#resolvedDimensions()} — default 1536 to
     * match the Phase 2 {@code vector(1536)} column (D-01). A dimension override
     * without a matching Liquibase changeset is a host-side misconfiguration
     * caught at startup by pgvector's own dimension check.
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStore aiAgentVectorStore(JdbcTemplate jdbcTemplate,
                                          EmbeddingModel embeddingModel,
                                          AiAgentEmbeddingProperties embeddingProps) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("AI_AGENT_KB_VECTOR_STORE")
                .initializeSchema(false)
                .dimensions(embeddingProps.resolvedDimensions())
                .build();
    }
}
