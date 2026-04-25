package com.vn.agent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration surface for the embedding provider, bound to
 * {@code jmix.ai-agent.embedding.*} (D-22). Picked up by the
 * {@code @ConfigurationPropertiesScan} on {@code AIConfiguration}.
 *
 * <p>Default model is Qwen3 Embedding 4B at 2000 dimensions — pinned to the
 * PgVectorStore schema Spring AI creates ({@code vector(2000)}). A dimension
 * change requires recreating the vector store table and a full reingest (D-01);
 * this record does not defend against mismatch by itself but captures the
 * pinning as constants so downstream beans can assert.</p>
 *
 * <p>{@code providerBaseUrl} mirrors the Phase 4 D-03 pattern — OpenRouter's
 * OpenAI-compatible endpoint is the default in host deployments, but any
 * OpenAI-compatible base URL works.</p>
 *
 * <p><b>Security note</b>: {@code providerBaseUrl} is not a secret (API keys
 * live in {@code spring.ai.openai.api-key}), so this record's {@code toString}
 * is log-safe. Do not add secret-bearing fields without a redaction review.</p>
 */
@ConfigurationProperties("jmix.ai-agent.embedding")
public record AiAgentEmbeddingProperties(String model, Integer dimensions, String providerBaseUrl) {

    /** D-01 default model slug (OpenRouter-compatible). */
    public static final String DEFAULT_MODEL = "qwen/qwen3-embedding-4b";

    /** D-01 pinned dimension — must match the pgvector {@code vector(2000)} schema. */
    public static final int DEFAULT_DIMENSIONS = 2000;

    public String resolvedModel() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    public int resolvedDimensions() {
        return dimensions == null ? DEFAULT_DIMENSIONS : dimensions;
    }
}
