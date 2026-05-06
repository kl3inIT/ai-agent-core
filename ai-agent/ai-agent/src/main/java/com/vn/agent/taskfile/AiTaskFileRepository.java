package com.vn.agent.taskfile;

import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.UnconstrainedDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agentstore CRUD seam for {@link AiTaskFile}. Owns the TTL cleanup orchestration
 * consumed by {@link AiTaskFileCleanupJob} (Phase 13.1: per-turn-all resolver loads
 * rows directly via {@link DataManager} — no repo intermediary).
 *
 * <p>Concurrency contract:
 * <ul>
 *     <li>{@link #deleteRow(AiTaskFile)} removes the blob BEFORE the row so a
 *         partial failure leaves the row in place for the next hourly retry,
 *         preventing blob-orphaning (PATTERNS Pitfall 3).</li>
 *     <li>{@link #loadExpired(OffsetDateTime)} runs from the {@code @Scheduled}
 *         cleanup job which has no user principal — uses
 *         {@link UnconstrainedDataManager} per project memory
 *         {@code feedback_jmix_unconstrained_for_system_writes}.</li>
 * </ul>
 *
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileRepository {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileRepository.class);

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FileStorageLocator fileStorageLocator;
    private final TransactionTemplate agentstoreRequiresNew;

    public AiTaskFileRepository(DataManager dataManager,
                                UnconstrainedDataManager unconstrainedDataManager,
                                FileStorageLocator fileStorageLocator,
                                @Qualifier("agentstoreTransactionManager")
                                PlatformTransactionManager agentstoreTransactionManager) {
        // dataManager kept on the constructor signature for now — caller wiring in
        // AIConfiguration / Spring autowiring expects it. Future Plan may drop it
        // when no live read path remains in this class.
        java.util.Objects.requireNonNull(dataManager, "dataManager");
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.fileStorageLocator = fileStorageLocator;
        this.agentstoreRequiresNew = new TransactionTemplate(agentstoreTransactionManager);
        this.agentstoreRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Loads expired rows ({@code expiresAt < :now}) for the cleanup job. Uses
     * {@link UnconstrainedDataManager} because the @Scheduled job runs without
     * a user principal (memory {@code feedback_jmix_unconstrained_for_system_writes}).
     */
    public List<AiTaskFile> loadExpired(OffsetDateTime now) {
        return unconstrainedDataManager.load(AiTaskFile.class)
                .query("select e from ai_AiTaskFile e where e.expiresAt < :now")
                .parameter("now", now)
                .list();
    }

    /**
     * Removes the row's blob from {@link io.jmix.core.FileStorage} BEFORE
     * removing the row itself (PATTERNS Pitfall 3). On blob-remove failure the
     * row is left in place so the next hourly run can retry; this avoids
     * orphaning a blob whose pointer row has already been deleted.
     *
     * @return {@code true} if both blob and row were removed (or the row had no
     *         blob to remove); {@code false} if the blob delete failed and the
     *         row was therefore preserved for the next retry.
     */
    public boolean deleteRow(AiTaskFile row) {
        FileRef ref = row.getStorageRef();
        if (ref != null) {
            try {
                fileStorageLocator.getByName(ref.getStorageName()).removeFile(ref);
            } catch (Exception ex) {
                log.warn("Failed to remove blob for AiTaskFile {} (skipping row delete; will retry next hour)",
                        row.getId(), ex);
                return false;
            }
        }
        unconstrainedDataManager.remove(row);
        return true;
    }

    /**
     * Deletes every expired row, blob-first per row (see {@link #deleteRow}).
     * The whole iteration runs in a single REQUIRES_NEW agentstore transaction
     * so a single corrupt row does not roll back the entire batch — failed
     * rows are logged + skipped via {@link #deleteRow}'s false return.
     *
     * @return count of rows whose blob+row delete BOTH succeeded.
     */
    public int deleteAllExpired(OffsetDateTime now) {
        Integer removed = agentstoreRequiresNew.execute(status -> {
            List<AiTaskFile> expired = loadExpired(now);
            int count = 0;
            for (AiTaskFile row : expired) {
                if (deleteRow(row)) {
                    count++;
                }
            }
            return count;
        });
        return removed == null ? 0 : removed;
    }
}
