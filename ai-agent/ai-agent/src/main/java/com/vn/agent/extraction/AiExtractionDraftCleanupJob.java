package com.vn.agent.extraction;

import com.vn.agent.entity.AiExtractionDraft;
import io.jmix.core.UnconstrainedDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hourly TTL reaper for expired {@link AiExtractionDraft} rows.
 *
 * <p>Uses {@link UnconstrainedDataManager} because scheduled cleanup is trusted
 * system-internal work that must not depend on the current user's row-level
 * policies. User-facing draft paths use secured DataManager instead.
 */
@Component
public class AiExtractionDraftCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionDraftCleanupJob.class);

    private final UnconstrainedDataManager dataManager;

    public AiExtractionDraftCleanupJob(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Scheduled(fixedDelayString = "${ai-agent.extraction.cleanup-interval-ms:3600000}")
    @Transactional("agentstoreTransactionManager")
    public void deleteExpiredDrafts() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AiExtractionDraft> expiredDrafts = dataManager.load(AiExtractionDraft.class)
                .query("select e from ai_AiExtractionDraft e where e.expiresAt < :now")
                .parameter("now", now)
                .list();
        for (AiExtractionDraft expiredDraft : expiredDrafts) {
            dataManager.remove(expiredDraft);
        }
        if (!expiredDrafts.isEmpty()) {
            log.debug("Removed {} expired AiExtractionDraft rows", expiredDrafts.size());
        }
    }
}
