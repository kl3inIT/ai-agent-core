package com.vn.agent.rag.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Test-only {@code @TestConfiguration} providing a deterministic {@link EmbeddingModel}
 * so Phase 5 RAG tests never hit OpenRouter. Mirrors {@code StubChatModelConfiguration}
 * (Phase 4 test_support). Marked {@code @Primary} to win if any transitively-configured
 * embedding starter accidentally registers a second bean.
 *
 * <p>The stub emits a 2000-dim vector whose values are a deterministic function of the
 * input text's {@link String#hashCode()} — keeps tests reproducible and avoids the
 * cost/latency/key-dependency of a real embedding call.</p>
 *
 * <p>Tests tagged {@code @Tag("live")} must NOT import this config — they need the
 * real bean. This class assumes Spring AI 1.1.4's {@link EmbeddingModel} interface,
 * which requires two overrides: {@code call(EmbeddingRequest)} and
 * {@code embed(Document)}.</p>
 */
@TestConfiguration
public class StubEmbeddingModelConfiguration {

    private static final int DIMENSIONS = 2000;

    @Bean
    @Primary
    public EmbeddingModel stubEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new java.util.ArrayList<>();
                List<String> texts = request.getInstructions();
                for (int i = 0; i < texts.size(); i++) {
                    embeddings.add(new Embedding(deterministicVector(texts.get(i)), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return deterministicVector(document.getText());
            }

            @Override
            public int dimensions() {
                return DIMENSIONS;
            }

            private float[] deterministicVector(String text) {
                float[] v = new float[DIMENSIONS];
                int hash = text == null ? 0 : text.hashCode();
                // Math.floorMod keeps values in [0, 999] regardless of hash sign — otherwise
                // Java's signed % can produce negative components and cosine similarity drops
                // below the default 0.0 threshold, making some chunks vanish from topK results.
                for (int i = 0; i < v.length; i++) {
                    v[i] = Math.floorMod(hash + i, 1000) / 1000.0f;
                }
                return v;
            }
        };
    }
}
