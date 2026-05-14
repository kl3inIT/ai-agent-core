package com.vn.agent.orchestration;

import com.vn.agent.admin.config.KnobMetadata;
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
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.defaults.model")
        String model,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.defaults.temperature")
        Double temperature,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.defaults.topP")
        Double topP,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.defaults.maxTokens")
        Integer maxTokens,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.defaults.systemPrompt")
        String systemPrompt) {

    /**
     * Last-resort system prompt used by {@link ChatClientFactory} when neither the active
     * {@code AiParameters} profile nor {@code jmix.ai-agent.defaults.system-prompt} produce a
     * value. Kept non-i18n intentionally (LO-01): this is a model-directed instruction, not a
     * user-facing UI string, so {@code msg://} keys do not apply.
     */
    public static final String FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant.";
}
