package com.vn.agent.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Defaults consulted by {@link AiParametersResolver} when no active {@code AiParameters} row exists
 * (D-04). Bound to the {@code jmix.ai-agent.defaults} property prefix; picked up automatically by
 * the {@code @ConfigurationPropertiesScan} on {@code AIConfiguration}.
 *
 * <p>Phase 6 will add CRUD + the {@code default-params.yaml} bootstrap row; this resolver does NOT
 * change at that point — Phase 6 only inserts a row that wins over these defaults.</p>
 *
 * <p>Model id MUST follow the OpenRouter slug format {@code provider/model} (e.g.
 * {@code openai/gpt-4o-mini}); validation lives in {@link AiParametersResolver}.</p>
 */
@ConfigurationProperties("jmix.ai-agent.defaults")
public record AiAgentDefaultsProperties(
        String model,
        Double temperature,
        Double topP,
        Integer maxTokens,
        String systemPrompt) {
}
