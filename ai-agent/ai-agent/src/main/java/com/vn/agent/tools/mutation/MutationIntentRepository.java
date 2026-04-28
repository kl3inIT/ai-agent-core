package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency reservation/replay repository for {@link AiMutationIntent}.
 *
 * <p>Uses {@link UnconstrainedDataManager} per MEMORY
 * {@code feedback_jmix_unconstrained_for_system_writes}: dedup rows must be
 * readable/writable regardless of user-role grants on AiMutationIntent.
 *
 * <p>Concurrency contract: {@link #reserveOrReplay} writes a PENDING row before
 * host mutation. The unique index on (toolName, idempotencyKey, userUsername)
 * is the distributed lock; only the reservation owner may call DataManager.save
 * on host data. A concurrent duplicate sees PENDING or COMMIT_UNKNOWN and
 * returns concurrent_modification without saving host data.
 *
 * <p>State machine (enforced internally on every finalization):
 * <ul>
 *     <li>PENDING -&gt; COMMITTED  (markCommitted)</li>
 *     <li>PENDING -&gt; FAILED     (markFailed; only for failures BEFORE host save returned)</li>
 *     <li>PENDING -&gt; COMMIT_UNKNOWN (markCommitUnknown; host save returned but finalization failed)</li>
 *     <li>FAILED  -&gt; PENDING    (reserveOrReplay reclaim, optimistic-lock guarded)</li>
 * </ul>
 * COMMITTED, FAILED, and COMMIT_UNKNOWN are never downgraded by markCommitUnknown / markFailed.
 */
@Component
public class MutationIntentRepository {

    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;
    private final TransactionTemplate agentstoreRequiresNew;
    private final MutationIntentFailureProbe failureProbe;

    public MutationIntentRepository(UnconstrainedDataManager dataManager,
                                    Metadata metadata,
                                    @Qualifier("agentstoreTransactionManager")
                                    PlatformTransactionManager agentstoreTransactionManager,
                                    ObjectProvider<MutationIntentFailureProbe> failureProbe) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.agentstoreRequiresNew = new TransactionTemplate(agentstoreTransactionManager);
        this.agentstoreRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureProbe = failureProbe.getIfAvailable(MutationIntentFailureProbe::noop);
    }

    public ReservationResult reserveOrReplay(String toolName,
                                             String idempotencyKey,
                                             String userUsername,
                                             UUID conversationId,
                                             String requestHash,
                                             Duration ttl) {
        validateIdempotencyKey(idempotencyKey);
        try {
            return agentstoreRequiresNew.execute(status -> reserveOrReplayInTransaction(
                    toolName, idempotencyKey, userUsername, conversationId, requestHash, ttl));
        } catch (DataAccessException | TransactionSystemException | PersistenceException writeRace) {
            // Unique constraint violations (for example DataIntegrityViolationException)
            // can be raised when the transaction commits, after the callback body has
            // returned. Catch around execute(...), re-read, and classify only if the
            // competing row now exists; otherwise rethrow.
            return agentstoreRequiresNew.execute(status -> classifyExisting(
                    findExisting(toolName, idempotencyKey, userUsername).orElseThrow(() -> writeRace),
                    requestHash,
                    ttl));
        }
    }

    private ReservationResult reserveOrReplayInTransaction(String toolName,
                                                           String idempotencyKey,
                                                           String userUsername,
                                                           UUID conversationId,
                                                           String requestHash,
                                                           Duration ttl) {
        Optional<AiMutationIntent> existing = findExisting(toolName, idempotencyKey, userUsername);
        if (existing.isPresent()) {
            return classifyExisting(existing.get(), requestHash, ttl);
        }

        AiMutationIntent intent = metadata.create(AiMutationIntent.class);
        intent.setToolName(toolName);
        intent.setIdempotencyKey(idempotencyKey);
        intent.setUserUsername(userUsername);
        intent.setConversationId(conversationId);
        intent.setRequestHash(requestHash);
        intent.setStatus(AiMutationIntentStatus.PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        intent.setCreatedAt(now);
        intent.setExpiresAt(now.plus(ttl));
        return ReservationResult.reserved(dataManager.save(intent));
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markCommitted(AiMutationIntent intent,
                              UUID resultEntityId,
                              String resultEntityName) {
        findById(intent.getId()).ifPresent(current -> {
            if (current.getStatus() != AiMutationIntentStatus.PENDING) {
                return;
            }
            failureProbe.beforeMarkCommitted(current);
            current.setStatus(AiMutationIntentStatus.COMMITTED);
            current.setResultEntityId(resultEntityId);
            current.setResultEntityName(resultEntityName);
            current.setCommittedAt(OffsetDateTime.now());
            dataManager.save(current);
        });
    }

    /**
     * Test seam for TEST-12 — never registered as a bean in production.
     * The {@link ObjectProvider} fallback uses {@link #noop()}. Tests may
     * register a {@code @Bean MutationIntentFailureProbe} that throws from
     * {@link #beforeMarkCommitted(AiMutationIntent)} to exercise the
     * COMMIT_UNKNOWN path without mocking the whole repository.
     */
    public interface MutationIntentFailureProbe {
        void beforeMarkCommitted(AiMutationIntent intent);

        static MutationIntentFailureProbe noop() {
            return intent -> { };
        }
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(AiMutationIntent intent, String errorCode) {
        // Only call for failures before host save/saveAll has returned. Do not
        // call after MutationCommitState.HOST_SAVE_RETURNED; use markCommitUnknown instead.
        findById(intent.getId()).ifPresent(current -> {
            if (current.getStatus() != AiMutationIntentStatus.PENDING) {
                return;
            }
            current.setStatus(AiMutationIntentStatus.FAILED);
            current.setErrorCode(errorCode);
            dataManager.save(current);
        });
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markCommitUnknown(AiMutationIntent intent, String errorCode) {
        findById(intent.getId()).ifPresent(current -> {
            if (current.getStatus() == AiMutationIntentStatus.COMMITTED) {
                // markCommitted may have committed successfully and thrown during/after
                // transaction completion. Never downgrade COMMITTED to COMMIT_UNKNOWN.
                return;
            }
            if (current.getStatus() != AiMutationIntentStatus.PENDING) {
                return;
            }
            current.setStatus(AiMutationIntentStatus.COMMIT_UNKNOWN);
            current.setErrorCode(errorCode);
            dataManager.save(current);
        });
    }

    private ReservationResult classifyExisting(AiMutationIntent existing,
                                               String requestHash,
                                               Duration ttl) {
        if (!requestHash.equals(existing.getRequestHash())) {
            return ReservationResult.violation(existing);
        }
        AiMutationIntentStatus status = existing.getStatus();
        if (status == AiMutationIntentStatus.COMMITTED) {
            return ReservationResult.replay(existing);
        }
        if (status == AiMutationIntentStatus.FAILED) {
            // Same logical operation failed before commit. Reclaim the row for
            // retry with the SAME key and SAME request hash. A corrected call
            // shape must use a fresh idempotency key and will be a VIOLATION.
            return reclaimFailed(existing, ttl);
        }
        if (status == AiMutationIntentStatus.COMMIT_UNKNOWN) {
            return ReservationResult.pending(existing);
        }
        return ReservationResult.pending(existing);
    }

    private ReservationResult reclaimFailed(AiMutationIntent existing, Duration ttl) {
        existing.setStatus(AiMutationIntentStatus.PENDING);
        existing.setErrorCode(null);
        existing.setExpiresAt(OffsetDateTime.now().plus(ttl));
        // This save is the FAILED -> PENDING compare-and-set. AiMutationIntent has
        // @Version; if another retry reclaimed the row first, the transaction fails
        // and reserveOrReplay's outer catch re-reads and classifies the current row.
        return ReservationResult.reserved(dataManager.save(existing));
    }

    private Optional<AiMutationIntent> findById(UUID id) {
        return dataManager.load(AiMutationIntent.class).id(id).optional();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ToolUserError("parameter_conversion_error",
                    "idempotencyKey must be a UUID",
                    List.of("generate a fresh UUID idempotencyKey for this logical operation"));
        }
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new ToolUserError("parameter_conversion_error",
                    "idempotencyKey must be a UUID",
                    List.of("generate a fresh UUID idempotencyKey for this logical operation"));
        }
    }

    public Optional<AiMutationIntent> findExisting(String toolName,
                                                   String idempotencyKey,
                                                   String userUsername) {
        return dataManager.load(AiMutationIntent.class)
                .query("select e from aiMutation_AiMutationIntent e " +
                        "where e.toolName = :toolName " +
                        "and e.idempotencyKey = :key " +
                        "and e.userUsername = :user")
                .parameter("toolName", toolName)
                .parameter("key", idempotencyKey)
                .parameter("user", userUsername)
                .optional();
    }

    /**
     * Bulk-removes expired terminal dedup rows. Invoked hourly by the cleanup job.
     * PENDING and COMMIT_UNKNOWN are deliberately retained and logged by the
     * cleanup job; deleting them can allow duplicate host writes after a
     * finalization failure.
     * Returns the number of rows removed for logging/metrics.
     */
    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(OffsetDateTime now) {
        List<AiMutationIntent> expired = dataManager.load(AiMutationIntent.class)
                .query("select e from aiMutation_AiMutationIntent e where e.expiresAt < :now " +
                        "and e.status in ('COMMITTED', 'FAILED')")
                .parameter("now", now)
                .list();
        for (AiMutationIntent intent : expired) {
            dataManager.remove(intent);
        }
        return expired.size();
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", readOnly = true)
    public int countExpiredInFlight(OffsetDateTime now) {
        return dataManager.load(AiMutationIntent.class)
                .query("select e from aiMutation_AiMutationIntent e where e.expiresAt < :now " +
                        "and e.status in ('PENDING', 'COMMIT_UNKNOWN')")
                .parameter("now", now)
                .list()
                .size();
    }

    public record ReservationResult(ReservationState state, AiMutationIntent intent) {
        static ReservationResult reserved(AiMutationIntent intent) {
            return new ReservationResult(ReservationState.RESERVED, intent);
        }
        static ReservationResult replay(AiMutationIntent intent) {
            return new ReservationResult(ReservationState.REPLAY, intent);
        }
        static ReservationResult pending(AiMutationIntent intent) {
            return new ReservationResult(ReservationState.PENDING, intent);
        }
        static ReservationResult violation(AiMutationIntent intent) {
            return new ReservationResult(ReservationState.VIOLATION, intent);
        }
    }

    public enum ReservationState {
        RESERVED, REPLAY, PENDING, VIOLATION
    }
}
