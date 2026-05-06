package com.vn.agent.taskfile;

import com.vn.agent.entity.AiMessage;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Agentstore CRUD seam for {@link AiTaskFile}. Owns the per-conversation pending
 * lookup, the post-send {@code markInjected} stamping, and the TTL cleanup
 * orchestration consumed by {@link AiTaskFileCleanupJob}.
 *
 * <p>Concurrency contract:
 * <ul>
 *     <li>{@link #loadPending(UUID)} runs in the user request thread; user
 *         row-level policy ({@code userUsername = :current_user_username} from
 *         Plan 13-01) filters cross-user reads structurally, so the regular
 *         {@link DataManager} is used.</li>
 *     <li>{@link #markInjected(java.util.List, UUID, OffsetDateTime)} stamps
 *         the authoritative {@code injectedAt} marker in a REQUIRES_NEW
 *         agentstore transaction so a chat-memory rollback in the surrounding
 *         advisor cannot un-stamp the row (REVIEWS HIGH-1; mirrors the Phase 11
 *         {@code AuditWriter.writeToolCall} REQUIRES_NEW invariant).</li>
 *     <li>{@link #deleteRow(AiTaskFile)} removes the blob BEFORE the row so a
 *         partial failure leaves the row in place for the next hourly retry,
 *         preventing blob-orphaning (PATTERNS Pitfall 3).</li>
 * </ul>
 *
 * <p>System-internal writes go through {@link UnconstrainedDataManager} per
 * project memory {@code feedback_jmix_unconstrained_for_system_writes} —
 * {@code markInjected} runs on behalf of the chat path with a known correct
 * principal stamp, and {@code deleteRow} is invoked by the @Scheduled cleanup
 * job which has no user principal at all.
 *
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileRepository {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileRepository.class);

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FileStorageLocator fileStorageLocator;
    private final TransactionTemplate agentstoreRequiresNew;

    public AiTaskFileRepository(DataManager dataManager,
                                UnconstrainedDataManager unconstrainedDataManager,
                                FileStorageLocator fileStorageLocator,
                                @Qualifier("agentstoreTransactionManager")
                                PlatformTransactionManager agentstoreTransactionManager) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.fileStorageLocator = fileStorageLocator;
        this.agentstoreRequiresNew = new TransactionTemplate(agentstoreTransactionManager);
        this.agentstoreRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Loads the pending task-file rows for a conversation in submission order.
     * Pending = {@code injectedAt IS NULL AND expiresAt > :now} (REVIEWS HIGH-1 —
     * {@code injectedAt} is the stable pending marker; the {@code message} FK
     * is unstable because the projecting chat-memory repo deletes/reinserts
     * AiMessage rows on every {@code saveAll}).
     *
     * <p>Uses the regular {@link DataManager} so the user row-level policy
     * filters by {@code userUsername} (Plan 13-01); cross-user reads are
     * structurally impossible.
     */
    public List<AiTaskFile> loadPending(UUID conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return dataManager.load(AiTaskFile.class)
                .query("select e from ai_AiTaskFile e " +
                        "where e.conversation.id = :cid and e.injectedAt is null " +
                        "and e.expiresAt > :now " +
                        "order by e.createdDate asc")
                .parameter("cid", conversationId)
                .parameter("now", OffsetDateTime.now())
                .list();
    }

    /**
     * Stamps the authoritative {@code injectedAt} marker on each row in
     * {@code taskFileIds} and best-effort sets the optional display-link
     * {@code message} FK (REVIEWS HIGH-1; renamed from {@code markSent} per
     * REVIEWS HIGH-14).
     *
     * <p>Runs in REQUIRES_NEW so it commits even if the surrounding chat-memory
     * advisor's transaction rolls back. The {@code userMessageId} parameter
     * MUST come from the caller's pre-persisted user {@link AiMessage}{@code .id}
     * (Plan 04 invariant — never a SELECT-back "latest USER message" lookup).
     *
     * <p>The display-link lookup is tolerant: the projecting chat-memory repo
     * may have already deleted/reinserted the AiMessage row by the time this
     * method runs, in which case the {@code message} FK is left null and the
     * resolver predicate continues to work off {@code injectedAt} alone.
     *
     * <p>No-op when {@code taskFileIds} is empty.
     */
    public void markInjected(List<UUID> taskFileIds, UUID userMessageId, OffsetDateTime injectedAt) {
        if (taskFileIds == null || taskFileIds.isEmpty()) {
            return;
        }
        agentstoreRequiresNew.execute(status -> {
            AiMessage messageRef = null;
            if (userMessageId != null) {
                Optional<AiMessage> loaded = unconstrainedDataManager.load(AiMessage.class)
                        .id(userMessageId)
                        .optional();
                if (loaded.isEmpty()) {
                    log.debug("markInjected: AiMessage {} not found (chat-memory may have rewritten it); " +
                            "leaving message FK null and relying on injectedAt", userMessageId);
                }
                messageRef = loaded.orElse(null);
            }
            for (UUID id : taskFileIds) {
                Optional<AiTaskFile> rowOpt = unconstrainedDataManager.load(AiTaskFile.class)
                        .id(id)
                        .optional();
                if (rowOpt.isEmpty()) {
                    log.warn("markInjected: AiTaskFile {} not found (cleanup may have removed it); skipping", id);
                    continue;
                }
                AiTaskFile row = rowOpt.get();
                // Phase 13.1 SCHEMA-01 (Plan 13.1-01): AiTaskFile.message + injectedAt
                // fields were dropped from the entity (and Liquibase 100 drops the
                // matching columns at next boot). The per-turn-all resolver
                // (Plan 13.1-02) loads every non-expired row each turn, so there is
                // no pending-state marker to stamp here. This block is left as a
                // structural no-op pending Plan 13.1-02 rewrite which removes the
                // method entirely; parameters stay on the signature so existing
                // callers in Plan 13.1-01 still compile. Best-effort save() retained
                // to keep the row's last-modified-* lifecycle stamps fresh.
                unconstrainedDataManager.save(row);
                // Suppress unused-parameter warnings until Plan 13.1-02 deletes the method.
                java.util.Objects.requireNonNull(injectedAt, "injectedAt");
                if (messageRef != null) {
                    // intentionally unused; will be removed in Plan 13.1-02.
                    log.trace("markInjected: message FK lookup succeeded but field has been removed");
                }
            }
            return null;
        });
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
