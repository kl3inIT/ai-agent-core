package com.vn.agent.test_support;

import com.vn.agent.rag.config.StubEmbeddingModelConfiguration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Test-only {@code @TestConfiguration} that supplies an in-memory {@link SimpleVectorStore} as the
 * {@link VectorStore} bean so Phase-4 orchestration integration tests do not drive the Phase-5
 * PgVectorStore against HSQLDB (which has no pgvector extension and fails the JDBC path).
 *
 * <p>Imported by Phase-4 integration tests that boot {@code AIAutoConfiguration} (which now
 * registers a {@code PgVectorStore} unconditionally under {@code @ConditionalOnMissingBean}).
 * With this stub declared {@code @Primary}, the in-memory store wins bean selection; the
 * {@code RetrievalAugmentationAdvisor} inserted at {@code HIGHEST_PRECEDENCE + 250} in Phase 5
 * runs against an empty-but-functional store and returns zero documents — the stub ChatModel's
 * prompt is then answered without any DB round trip.</p>
 *
 * <p>Also imports {@link StubEmbeddingModelConfiguration} because {@code SimpleVectorStore.builder}
 * requires an {@link EmbeddingModel}. The stub embedding model is deterministic and offline.</p>
 */
@TestConfiguration
@Import(StubEmbeddingModelConfiguration.class)
public class StubVectorStoreConfiguration {

    @Bean
    @Primary
    public VectorStore stubVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
