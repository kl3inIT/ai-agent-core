package com.vn.agent.taskfile;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for task-scoped chat file uploads, bound to
 * {@code ai-agent.task-file.*}. Picked up by {@code @ConfigurationPropertiesScan}
 * on {@code AIConfiguration}.
 *
 * <p>Phase 13.1 contract:
 * <ul>
 *   <li>{@code ttlSeconds = 86400} (24h) — TTL applied to
 *       {@link com.vn.agent.entity.AiTaskFile} rows at upload time. The hourly
 *       {@link AiTaskFileCleanupJob} reaps rows where {@code expiresAt < now()}.
 *       Sentinel {@code -1} disables TTL entirely (the cleanup job logs once at
 *       INFO and returns without scanning the table). FK cascade on
 *       conversation-delete still bounds growth in sentinel mode.</li>
 *   <li>{@code maxFileSizeBytes = 100 MB} — per-file upload size cap.
 *       Deliberately equal to {@code jmix.ai-agent.rag.upload.max-file-size-bytes}.</li>
 *   <li>{@code perTurnMaxFiles = 10} — per-turn cap on file count for
 *       {@code AiTaskFileMediaResolver}. Sentinel {@code -1} disables.</li>
 *   <li>{@code perTurnMaxTotalBytes = 50 MB} — per-turn cap on total Media
 *       bytes injected into the LLM prompt. Sentinel {@code -1} disables.</li>
 * </ul>
 *
 * <p>Phase 13.1 flipped the prior duration-based ttl knob to a {@code long}
 * seconds counter (RESEARCH Pitfall 1 - Option B locked) so the operator-facing
 * sentinel value {@code -1} can be expressed natively in {@code application.properties}.
 *
 * <p><b>Security note:</b> no secrets are carried; {@code toString} is safe to log.
 */
@ConfigurationProperties(prefix = "ai-agent.task-file")
public class AiTaskFileProperties {

    /** TTL in seconds. Default 86400 (24h). Sentinel -1 = no TTL (skip cleanup-job purge). */
    @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
            displayMessageKey = "bootConfig.knob.taskFile.ttlSeconds")
    private long ttlSeconds = 86_400L;

    @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
            displayMessageKey = "bootConfig.knob.taskFile.maxFileSizeBytes")
    private long maxFileSizeBytes = 104_857_600L;

    /** Per-turn cap on file count for AiTaskFileMediaResolver. Default 10. Sentinel -1 disables. */
    @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
            displayMessageKey = "bootConfig.knob.taskFile.perTurnMaxFiles")
    private int perTurnMaxFiles = 10;

    /** Per-turn cap on total Media bytes. Default 52428800 (50 MB). Sentinel -1 disables. */
    @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
            displayMessageKey = "bootConfig.knob.taskFile.perTurnMaxTotalBytes")
    private long perTurnMaxTotalBytes = 52_428_800L;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getPerTurnMaxFiles() {
        return perTurnMaxFiles;
    }

    public void setPerTurnMaxFiles(int perTurnMaxFiles) {
        this.perTurnMaxFiles = perTurnMaxFiles;
    }

    public long getPerTurnMaxTotalBytes() {
        return perTurnMaxTotalBytes;
    }

    public void setPerTurnMaxTotalBytes(long perTurnMaxTotalBytes) {
        this.perTurnMaxTotalBytes = perTurnMaxTotalBytes;
    }
}
