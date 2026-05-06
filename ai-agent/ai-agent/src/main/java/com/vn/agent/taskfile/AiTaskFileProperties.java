package com.vn.agent.taskfile;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for task-scoped chat file uploads, bound to
 * {@code ai-agent.task-file.*}. Picked up by {@code @ConfigurationPropertiesScan}
 * on {@code AIConfiguration}.
 *
 * <p>Default values:
 * <ul>
 *   <li>{@code ttl = 1h} — D-03 TTL applied to {@link com.vn.agent.entity.AiTaskFile}
 *       rows at upload time. The hourly cleanup job (Plan 02) reaps rows where
 *       {@code expiresAt < now()}.</li>
 *   <li>{@code maxFileSizeBytes = 100 MB} — per-file upload size cap. Deliberately
 *       equal to {@code jmix.ai-agent.rag.upload.max-file-size-bytes} (CONTEXT
 *       Claude's Discretion in D-04).</li>
 * </ul>
 *
 * <p>Phase 13 plumbing: first consumers are {@code AiTaskFileMediaResolver}
 * (pending-row TTL filter) and the Plan 04 chat-panel upload handler
 * (size-cap enforcement). Mirrors the {@code AiAgentMutationProperties}
 * (Phase 11) shape.
 *
 * <p><b>Security note:</b> no secrets are carried; {@code toString} is safe to log.
 */
@ConfigurationProperties(prefix = "ai-agent.task-file")
public class AiTaskFileProperties {

    private Duration ttl = Duration.ofHours(1);

    private long maxFileSizeBytes = 104_857_600L;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }
}
