package com.vn.agent.extraction;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for intent-extraction draft persistence, bound to
 * {@code ai-agent.extraction.*}. Picked up by {@code @ConfigurationPropertiesScan}
 * and registered explicitly from {@code AIConfiguration}.
 */
@ConfigurationProperties(prefix = "ai-agent.extraction")
public class AiExtractionProperties {

    /** Draft TTL in seconds. Default 3600 (1h). */
    @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
            displayMessageKey = "bootConfig.knob.extraction.ttlSeconds")
    private long ttlSeconds = 3_600L;

    /** Scheduled cleanup fixed delay in milliseconds. Default 3600000 (1h). */
    @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
            displayMessageKey = "bootConfig.knob.extraction.cleanupIntervalMs")
    private long cleanupIntervalMs = 3_600_000L;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
