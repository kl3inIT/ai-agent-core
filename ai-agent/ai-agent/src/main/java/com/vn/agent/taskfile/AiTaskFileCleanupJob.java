package com.vn.agent.taskfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

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
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileCleanupJob.class);

    private final AiTaskFileRepository repository;

    public AiTaskFileCleanupJob(AiTaskFileRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly at minute 0 — same cadence as MutationIntentCleanupJob
    public void deleteExpiredTaskFiles() {
        int removed = repository.deleteAllExpired(OffsetDateTime.now());
        if (removed > 0) {
            log.debug("Removed {} expired AiTaskFile rows", removed);
        }
    }
}
