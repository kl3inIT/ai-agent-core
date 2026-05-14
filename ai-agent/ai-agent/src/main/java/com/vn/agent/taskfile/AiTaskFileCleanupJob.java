package com.vn.agent.taskfile;

import com.vn.agent.orchestration.AiUiSettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hourly TTL reaper for {@link com.vn.agent.entity.AiTaskFile} rows. Mirrors
 * {@code MutationIntentCleanupJob} (Phase 11) — single-instance safe via the
 * agentstore REQUIRES_NEW transaction template inside
 * {@link AiTaskFileRepository#deleteAllExpired}.
 *
 * <p>Requires {@code @EnableScheduling} on {@link com.vn.agent.AIConfiguration}
 * (already present from Phase 11; see line 36 of AIConfiguration.java).
 *
 * <p>Cleanup is intentionally not annotated with {@code @Transactional} here —
 * {@link AiTaskFileRepository#deleteAllExpired} manages its own
 * {@code agentstoreRequiresNew} transaction template internally, which lets
 * a single corrupt row be skipped without rolling back the entire batch
 * (PATTERNS Pitfall 3 — blob-first ordering plus per-row tolerance).
 *
 * <p>Phase 13.1 LIFE-01: when {@link AiTaskFileProperties#getTtlSeconds()}
 * returns the sentinel value {@code -1}, the job logs once at INFO and
 * short-circuits without scanning the table. FK cascade on conversation-delete
 * (see {@code 090-ai-task-file.xml}) still bounds growth structurally.
 *
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileCleanupJob.class);

    private final AiTaskFileRepository repository;
    private final AiTaskFileProperties taskFileProperties;
    private final AiUiSettingsResolver aiUiSettingsResolver;
    private final AtomicBoolean sentinelLoggedOnce = new AtomicBoolean(false);

    public AiTaskFileCleanupJob(AiTaskFileRepository repository,
                                AiTaskFileProperties taskFileProperties,
                                AiUiSettingsResolver aiUiSettingsResolver) {
        this.repository = repository;
        this.taskFileProperties = taskFileProperties;
        this.aiUiSettingsResolver = aiUiSettingsResolver;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly at minute 0 — same cadence as MutationIntentCleanupJob
    public void deleteExpiredTaskFiles() {
        // Phase 16 CFG-01 — read TTL via AiUiSettingsResolver so admin edits on the
        // AiUiSettings singleton column take effect without a restart. Null column
        // falls through to taskFileProperties.getTtlSeconds(). Sentinel -1 still
        // disables cleanup (Phase 13.1 invariant preserved).
        long ttl = aiUiSettingsResolver.resolveTaskFileTtlSeconds();
        if (ttl == -1L) {
            if (sentinelLoggedOnce.compareAndSet(false, true)) {
                log.info("AiTaskFile TTL disabled (ttl-seconds=-1); cleanup job skipping TTL purge");
            }
            return;
        }
        int removed = repository.deleteAllExpired(OffsetDateTime.now());
        if (removed > 0) {
            log.debug("Removed {} expired AiTaskFile rows", removed);
        }
    }
}
