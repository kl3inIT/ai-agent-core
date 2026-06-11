package com.vn.agent.conversation;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration surface for auto conversation-title generation, bound to
 * {@code jmix.ai-agent.conversation-title.*}. Every default lives in the
 * {@code resolved*()} accessors / {@code is*()} guards below so the property source
 * never needs to restate a default.
 *
 * <p>The async pool that runs the title job is intentionally NOT a knob here: it is a
 * low-volume, fail-silent side job sized with fixed constants in
 * {@code AIConfiguration.aiAgentTitleExecutor()}. Operators tune <em>what</em> the title
 * sees ({@link #maxContextMessages}, {@link #minAssistantMessagesTrigger}) — not the
 * thread pool.</p>
 */
@ConfigurationProperties("jmix.ai-agent.conversation-title")
public record AiAgentTitleProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.title.enabled")
        Boolean enabled,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.title.modelId")
        String modelId,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.title.maxContextMessages")
        Integer maxContextMessages,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.title.minAssistantMessagesTrigger")
        Integer minAssistantMessagesTrigger) {

    private static final int DEFAULT_MAX_CONTEXT_MESSAGES = 6;
    private static final int DEFAULT_MIN_ASSISTANT_MESSAGES_TRIGGER = 1;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String resolvedModelId() {
        return modelId == null || modelId.isBlank() ? null : modelId.trim();
    }

    public int resolvedMaxContextMessages() {
        return positiveOrDefault(maxContextMessages, DEFAULT_MAX_CONTEXT_MESSAGES);
    }

    public int resolvedMinAssistantMessagesTrigger() {
        return positiveOrDefault(minAssistantMessagesTrigger, DEFAULT_MIN_ASSISTANT_MESSAGES_TRIGGER);
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
