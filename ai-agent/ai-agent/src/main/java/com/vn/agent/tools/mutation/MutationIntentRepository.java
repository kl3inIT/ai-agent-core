package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.Metadata;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency reservation/replay repository for {@link AiMutationIntent}.
 *
 * <p>Uses the agentstore {@link JdbcTemplate} for its hot path: dedup rows must be
 * readable/writable regardless of user-role grants on AiMutationIntent, and this internal
 * reservation table must not depend on host Jmix entity query generation.
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

    private final Metadata metadata;
    private final JdbcTemplate agentstoreJdbcTemplate;
    private final TransactionTemplate agentstoreRequiresNew;
    private final MutationIntentFailureProbe failureProbe;

    public MutationIntentRepository(Metadata metadata,
                                    @Qualifier("pgvectorJdbcTemplate")
                                    JdbcTemplate agentstoreJdbcTemplate,
                                    @Qualifier("agentstoreTransactionManager")
                                    PlatformTransactionManager agentstoreTransactionManager,
                                    ObjectProvider<MutationIntentFailureProbe> failureProbe) {
        this.metadata = metadata;
        this.agentstoreJdbcTemplate = agentstoreJdbcTemplate;
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

        OffsetDateTime now = OffsetDateTime.now();
        AiMutationIntent intent = newReservedIntent(
                toolName, idempotencyKey, userUsername, conversationId, requestHash, now, now.plus(ttl));
        insertReservedIntent(intent);
        return ReservationResult.reserved(intent);
    }

    /**
     * Single-record tool overload (create_record / update_record / add_related_record /
     * remove_related_record). Leaves {@code RESULT_SUMMARY} null. Behaviour is byte-identical
     * to the pre-Phase-13 contract.
     */
    public void markCommitted(AiMutationIntent intent,
                              UUID resultEntityId,
                              String resultEntityName) {
        markCommitted(intent, resultEntityId, resultEntityName, null);
    }

    /**
     * Phase 13 REVIEWS HIGH-11 — full overload that also persists a tool-specific
     * {@code resultSummary} JSON for {@code IDEMPOTENT_REPLAY}. Bulk tools
     * (bulk_save_records) populate this with the full savedIds JSON so a retry returns the
     * same shape as the original call. Single-record callers pass {@code null}.
     */
    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markCommitted(AiMutationIntent intent,
                              UUID resultEntityId,
                              String resultEntityName,
                              String resultSummary) {
        findById(intent.getId())
                .filter(current -> current.getStatus() == AiMutationIntentStatus.PENDING)
                .ifPresent(current -> {
                    failureProbe.beforeMarkCommitted(current);
                    OffsetDateTime committedAt = OffsetDateTime.now();
                    agentstoreJdbcTemplate.update(
                            "update AI_MUTATION_INTENT set VERSION = VERSION + 1, STATUS_ = ?, " +
                                    "RESULT_ENTITY_ID = ?, RESULT_ENTITY_NAME = ?, RESULT_SUMMARY = ?, " +
                                    "COMMITTED_AT = ?, ERROR_CODE = null " +
                                    "where ID = ? and STATUS_ = ?",
                            AiMutationIntentStatus.COMMITTED.getId(),
                            resultEntityId,
                            resultEntityName,
                            resultSummary,
                            timestamp(committedAt),
                            current.getId(),
                            AiMutationIntentStatus.PENDING.getId());
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
        agentstoreJdbcTemplate.update(
                "update AI_MUTATION_INTENT set VERSION = VERSION + 1, STATUS_ = ?, ERROR_CODE = ? " +
                        "where ID = ? and STATUS_ = ?",
                AiMutationIntentStatus.FAILED.getId(),
                errorCode,
                intent.getId(),
                AiMutationIntentStatus.PENDING.getId());
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markCommitUnknown(AiMutationIntent intent, String errorCode) {
        // Only PENDING rows can move to COMMIT_UNKNOWN. If markCommitted already committed,
        // this conditional update touches zero rows and never downgrades COMMITTED.
        agentstoreJdbcTemplate.update(
                "update AI_MUTATION_INTENT set VERSION = VERSION + 1, STATUS_ = ?, ERROR_CODE = ? " +
                        "where ID = ? and STATUS_ = ?",
                AiMutationIntentStatus.COMMIT_UNKNOWN.getId(),
                errorCode,
                intent.getId(),
                AiMutationIntentStatus.PENDING.getId());
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
            return reclaimFailed(existing, requestHash, ttl);
        }
        if (status == AiMutationIntentStatus.COMMIT_UNKNOWN) {
            return ReservationResult.pending(existing);
        }
        return ReservationResult.pending(existing);
    }

    private ReservationResult reclaimFailed(AiMutationIntent existing, String requestHash, Duration ttl) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(ttl);
        int updated = agentstoreJdbcTemplate.update(
                "update AI_MUTATION_INTENT set VERSION = VERSION + 1, STATUS_ = ?, ERROR_CODE = null, EXPIRES_AT = ? " +
                        "where ID = ? and STATUS_ = ? and VERSION = ?",
                AiMutationIntentStatus.PENDING.getId(),
                timestamp(expiresAt),
                existing.getId(),
                AiMutationIntentStatus.FAILED.getId(),
                existing.getVersion());
        if (updated == 1) {
            return findById(existing.getId())
                    .map(ReservationResult::reserved)
                    .orElseGet(() -> ReservationResult.pending(existing));
        }
        return findById(existing.getId())
                .map(current -> classifyExisting(current, requestHash, ttl))
                .orElseGet(() -> ReservationResult.pending(existing));
    }

    private Optional<AiMutationIntent> findById(UUID id) {
        List<AiMutationIntent> rows = agentstoreJdbcTemplate.query(
                "select ID, VERSION, TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME, CONVERSATION_ID, " +
                        "REQUEST_HASH, STATUS_, RESULT_ENTITY_ID, RESULT_ENTITY_NAME, RESULT_SUMMARY, " +
                        "ERROR_CODE, COMMITTED_AT, CREATED_AT, EXPIRES_AT " +
                        "from AI_MUTATION_INTENT where ID = ?",
                (resultSet, rowNumber) -> readIntent(resultSet),
                id);
        return rows.stream().findFirst();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ToolUserError("parameter_conversion_error",
                    "idempotencyKey must be a UUID v4",
                    List.of("generate a fresh random UUID v4 idempotencyKey: third group starts with 4, fourth group starts with 8, 9, a, or b"));
        }
        try {
            UUID uuid = UUID.fromString(idempotencyKey);
            if (uuid.version() != 4) {
                throw new IllegalArgumentException("idempotencyKey is not UUID v4");
            }
        } catch (IllegalArgumentException e) {
            throw new ToolUserError("parameter_conversion_error",
                    "idempotencyKey must be a UUID v4",
                    List.of("generate a fresh random UUID v4 idempotencyKey: third group starts with 4, fourth group starts with 8, 9, a, or b; do not copy UUIDs from examples or prior tool calls"));
        }
    }

    public Optional<AiMutationIntent> findExisting(String toolName,
                                                   String idempotencyKey,
                                                   String userUsername) {
        return findExistingId(toolName, idempotencyKey, userUsername)
                .flatMap(this::findById);
    }

    private Optional<UUID> findExistingId(String toolName,
                                          String idempotencyKey,
                                          String userUsername) {
        List<UUID> ids = agentstoreJdbcTemplate.query(
                "select ID from AI_MUTATION_INTENT " +
                        "where TOOL_NAME = ? and IDEMPOTENCY_KEY = ? and USER_USERNAME = ?",
                (resultSet, rowNumber) -> readUuid(resultSet, "ID"),
                toolName,
                idempotencyKey,
                userUsername);
        return ids.stream().findFirst();
    }

    private AiMutationIntent newReservedIntent(String toolName,
                                               String idempotencyKey,
                                               String userUsername,
                                               UUID conversationId,
                                               String requestHash,
                                               OffsetDateTime createdAt,
                                               OffsetDateTime expiresAt) {
        AiMutationIntent intent = metadata.create(AiMutationIntent.class);
        intent.setId(UUID.randomUUID());
        intent.setVersion(1);
        intent.setToolName(toolName);
        intent.setIdempotencyKey(idempotencyKey);
        intent.setUserUsername(userUsername);
        intent.setConversationId(conversationId);
        intent.setRequestHash(requestHash);
        intent.setStatus(AiMutationIntentStatus.PENDING);
        intent.setCreatedAt(createdAt);
        intent.setExpiresAt(expiresAt);
        return intent;
    }

    private void insertReservedIntent(AiMutationIntent intent) {
        agentstoreJdbcTemplate.update(
                "insert into AI_MUTATION_INTENT (" +
                        "ID, VERSION, TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME, CONVERSATION_ID, " +
                        "REQUEST_HASH, STATUS_, CREATED_AT, EXPIRES_AT" +
                        ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                intent.getId(),
                intent.getVersion(),
                intent.getToolName(),
                intent.getIdempotencyKey(),
                intent.getUserUsername(),
                intent.getConversationId(),
                intent.getRequestHash(),
                intent.getStatus().getId(),
                timestamp(intent.getCreatedAt()),
                timestamp(intent.getExpiresAt()));
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
        return agentstoreJdbcTemplate.update(
                "delete from AI_MUTATION_INTENT where EXPIRES_AT < ? and STATUS_ in (?, ?)",
                timestamp(now),
                AiMutationIntentStatus.COMMITTED.getId(),
                AiMutationIntentStatus.FAILED.getId());
    }

    @Transactional(transactionManager = "agentstoreTransactionManager", readOnly = true)
    public int countExpiredInFlight(OffsetDateTime now) {
        Integer count = agentstoreJdbcTemplate.queryForObject(
                "select count(*) from AI_MUTATION_INTENT where EXPIRES_AT < ? and STATUS_ in (?, ?)",
                Integer.class,
                timestamp(now),
                AiMutationIntentStatus.PENDING.getId(),
                AiMutationIntentStatus.COMMIT_UNKNOWN.getId());
        return count == null ? 0 : count;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private AiMutationIntent readIntent(ResultSet resultSet) throws SQLException {
        AiMutationIntent intent = metadata.create(AiMutationIntent.class);
        intent.setId(readUuid(resultSet, "ID"));
        intent.setVersion(resultSet.getInt("VERSION"));
        intent.setToolName(resultSet.getString("TOOL_NAME"));
        intent.setIdempotencyKey(resultSet.getString("IDEMPOTENCY_KEY"));
        intent.setUserUsername(resultSet.getString("USER_USERNAME"));
        intent.setConversationId(readUuid(resultSet, "CONVERSATION_ID"));
        intent.setRequestHash(resultSet.getString("REQUEST_HASH"));
        intent.setStatus(AiMutationIntentStatus.fromId(resultSet.getString("STATUS_")));
        intent.setResultEntityId(readUuid(resultSet, "RESULT_ENTITY_ID"));
        intent.setResultEntityName(resultSet.getString("RESULT_ENTITY_NAME"));
        intent.setResultSummary(resultSet.getString("RESULT_SUMMARY"));
        intent.setErrorCode(resultSet.getString("ERROR_CODE"));
        intent.setCommittedAt(readOffsetDateTime(resultSet, "COMMITTED_AT"));
        intent.setCreatedAt(readOffsetDateTime(resultSet, "CREATED_AT"));
        intent.setExpiresAt(readOffsetDateTime(resultSet, "EXPIRES_AT"));
        return intent;
    }

    private static OffsetDateTime readOffsetDateTime(ResultSet resultSet, String column) throws SQLException {
        Object raw = resultSet.getObject(column);
        if (raw == null) {
            return null;
        }
        if (raw instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (raw instanceof Timestamp timestamp) {
            return OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
        }
        if (raw instanceof LocalDateTime localDateTime) {
            return localDateTime.atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(raw.toString());
    }

    private static UUID readUuid(ResultSet resultSet, String column) throws SQLException {
        Object raw = resultSet.getObject(column);
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(raw.toString());
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
