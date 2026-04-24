package com.vn.agent.rag.config;

import com.vn.agent.AITestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 05-01 (RAG-02) — bean-collision guard.
 *
 * <p>Proves that exactly one {@link EmbeddingModel} bean resolves in the add-on's
 * Spring context by default, mechanically satisfying ROADMAP's "single shared
 * EmbeddingModel bean (enforced by bean-collision test)" contract. Multiple
 * beans would indicate a starter collision (RESEARCH Pitfall #4): two embedding
 * starters both registering {@link EmbeddingModel} would trigger
 * {@code NoUniqueBeanDefinitionException} anywhere the bean is injected by type,
 * including inside this add-on's own {@code aiAgentVectorStore} bean.</p>
 *
 * <p><b>Context composition</b> mirrors {@link com.vn.agent.FoundationsBootSmokeTest}:
 * boot against {@link AITestConfiguration} (existing HSQLDB + Mockito
 * {@code ChatClient.Builder} harness) and explicitly {@code @ImportAutoConfiguration}
 * the add-on's starter auto-configs so their beans are registered even when the
 * starter JAR is absent from the test classpath.</p>
 *
 * <p><b>Why the OpenAI embedding auto-config is excluded</b>: the test classpath
 * transitively brings {@code spring-ai-starter-model-openai}, which would register
 * a second {@link EmbeddingModel} bean alongside the {@link StubEmbeddingModelConfiguration}
 * stub — yielding {@code getBeansOfType().size() == 2} and defeating the test's
 * purpose of catching unintended duplicates. The exclusion lets the stub be the
 * sole producer; the add-on's own {@code aiAgentEmbeddingModel} passthrough then
 * correctly skips (via {@code @ConditionalOnMissingBean}) and we observe exactly
 * one bean. A real host app that wires a real embedding provider through a
 * single starter hits the same one-bean outcome through the non-test path.</p>
 *
 * <p><b>Why the pgvector auto-config is excluded</b>: its {@code VectorStore} bean
 * would try to construct a real {@link org.springframework.ai.vectorstore.pgvector.PgVectorStore}
 * against HSQLDB. The add-on's own {@code aiAgentVectorStore} bean (in
 * {@code AIAutoConfiguration}) still fires under {@code @ConditionalOnMissingBean};
 * it builds cleanly against HSQLDB because {@code initializeSchema(false)} plus
 * Spring AI's default {@code vectorTableValidationsEnabled=false} short-circuit
 * {@code PgVectorStore#afterPropertiesSet()} before any DB call.</p>
 */
@SpringBootTest(
        classes = AITestConfiguration.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,"
                        + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
        }
)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(StubEmbeddingModelConfiguration.class)
class EmbeddingModelBeanCollisionTest {

    @Autowired ApplicationContext ctx;

    @Test
    void exactly_one_embedding_model_bean_resolves() {
        // RAG-02 literal contract. The @ConditionalOnMissingBean on
        // AIAutoConfiguration.aiAgentEmbeddingModel should skip because the
        // StubEmbeddingModelConfiguration bean is present; the OpenAI auto-config
        // is explicitly excluded to keep the count honest.
        Map<String, EmbeddingModel> beans = ctx.getBeansOfType(EmbeddingModel.class);
        assertThat(beans)
                .as("Exactly one EmbeddingModel bean must resolve — multiple indicates a "
                        + "starter collision (RESEARCH Pitfall #4). Current beans: %s", beans.keySet())
                .hasSize(1);
        assertThat(beans.values().iterator().next())
                .as("The resolved EmbeddingModel bean must be non-null")
                .isNotNull();
    }
}
