package com.vn.agent.tools.mutation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Hourly TTL reaper for {@link AiMutationIntent} dedup rows. Idempotent
 * (multi-instance safe — bulk delete by {@code expiresAt < now}).
 *
 * <p>Requires {@code @EnableScheduling} on AIConfiguration (added in Plan 11-02).
 * Targets the {@code agentstoreTransactionManager} since the entity lives in
 * the agentstore datasource.
 *
 * <p>Cleanup policy (D-02):
 * <ul>
 *     <li>{@code COMMITTED} and {@code FAILED} rows past {@code expiresAt} are
 *         deleted in bulk.</li>
 *     <li>{@code PENDING} and {@code COMMIT_UNKNOWN} rows past {@code expiresAt}
 *         are <em>logged</em> at WARN for operator investigation, never deleted
 *         automatically — deleting them could allow duplicate host writes after
 *         a finalization failure.</li>
 * </ul>
 */
@Component
public class MutationIntentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MutationIntentCleanupJob.class);

    private final MutationIntentRepository repository;

    public MutationIntentCleanupJob(MutationIntentRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly at minute 0
    @Transactional("agentstoreTransactionManager")
    public void deleteExpiredIntents() {
        OffsetDateTime now = OffsetDateTime.now();
        int removed = repository.deleteExpired(now);
        int staleInFlight = repository.countExpiredInFlight(now);
        if (removed > 0) {
            log.debug("Removed {} expired AiMutationIntent rows", removed);
        }
        if (staleInFlight > 0) {
            log.warn("{} expired AiMutationIntent rows remain PENDING/COMMIT_UNKNOWN; manual investigation required before cleanup", staleInFlight);
        }
    }
}
