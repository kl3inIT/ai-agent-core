package com.vn.agent.conversation;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-agent.conversation-title")
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
        Integer minAssistantMessagesTrigger,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.title.executor")
        Executor executor) {

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

    public Executor resolvedExecutor() {
        return executor == null ? new Executor(null, null, null, null) : executor;
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    public record Executor(
            Integer corePoolSize,
            Integer maxPoolSize,
            Integer queueCapacity,
            Integer keepAliveSeconds) {

        private static final int DEFAULT_CORE_POOL_SIZE = 1;
        private static final int DEFAULT_MAX_POOL_SIZE = 2;
        private static final int DEFAULT_QUEUE_CAPACITY = 32;
        private static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;

        public int resolvedCorePoolSize() {
            return positiveOrDefault(corePoolSize, DEFAULT_CORE_POOL_SIZE);
        }

        public int resolvedMaxPoolSize() {
            return Math.max(resolvedCorePoolSize(),
                    positiveOrDefault(maxPoolSize, DEFAULT_MAX_POOL_SIZE));
        }

        public int resolvedQueueCapacity() {
            return positiveOrDefault(queueCapacity, DEFAULT_QUEUE_CAPACITY);
        }

        public int resolvedKeepAliveSeconds() {
            return positiveOrDefault(keepAliveSeconds, DEFAULT_KEEP_ALIVE_SECONDS);
        }
    }
}
