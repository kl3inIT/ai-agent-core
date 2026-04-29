package com.vn.agent.tools.mutation;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
// (Concurrency primitives are used by the FAILED-reclaim race test below.)

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 2 — repository-level reservation/race tests.
 *
 * <p>Drives {@link MutationIntentRepository#reserveOrReplay} directly (no
 * {@code BuiltInMutationTools} involved) so the duplicate-reservation race, FAILED-reclaim race,
 * and PENDING/COMMIT_UNKNOWN duplicate behaviour are pinned at the repository contract level.
 *
 * <p>Concurrency tests use two {@link Thread}s plus a {@link CountDownLatch} so both attempt
 * the same {@code (toolName, idempotencyKey, userUsername, requestHash)} reservation as close
 * to simultaneously as practical against the HSQLDB unique index. Outcomes are inspected via
 * {@link MutationIntentRepository.ReservationState}; we never read an in-flight row's status
 * directly because the repository contract is "RESERVED for the winner, PENDING/REPLAY for the
 * loser".
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class MutationIntentRepositoryReservationTest {

    @Autowired
    private MutationIntentRepository mutationIntentRepository;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final List<UUID> reservedIntentIds = new ArrayList<>();

    @AfterEach
    void cleanReservedRows() {
        if (reservedIntentIds.isEmpty()) {
            return;
        }
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : reservedIntentIds) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
        });
        reservedIntentIds.clear();
    }

    @Test
    void duplicateReservation_returnsOneReservedAndOnePending_singleRow() {
        // Sequential duplicate reservation — proves the contract that a same-tuple repeat sees
        // PENDING when the winner's row is still PENDING in the database. Concurrent reservation
        // is exercised by the FAILED-reclaim race below, where each reservation goes through
        // the classify-existing branch (no INSERT race).
        String toolName = "create_record";
        String idempotencyKey = UUID.randomUUID().toString();
        String userUsername = "racer-1";
        String requestHash = "hash-A";
        Duration ttl = Duration.ofHours(1);

        MutationIntentRepository.ReservationResult winner = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, requestHash, ttl));
        assertThat(winner.state()).isEqualTo(MutationIntentRepository.ReservationState.RESERVED);
        reservedIntentIds.add(winner.intent().getId());

        // Same tuple, same hash — should return PENDING because the existing row is PENDING and
        // hashes match (not RESERVED, not VIOLATION).
        MutationIntentRepository.ReservationResult duplicate = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, requestHash, ttl));
        assertThat(duplicate.state())
                .as("duplicate same-key/same-hash reservation must surface PENDING — never a second RESERVED")
                .isEqualTo(MutationIntentRepository.ReservationState.PENDING);
        assertThat(duplicate.intent().getId())
                .as("PENDING result intent must be the original winner's row")
                .isEqualTo(winner.intent().getId());

        // Exactly one row in the table.
        long rowCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :tn and e.idempotencyKey = :k and e.userUsername = :u")
                        .parameter("tn", toolName)
                        .parameter("k", idempotencyKey)
                        .parameter("u", userUsername)
                        .list()
                        .size());
        assertThat(rowCount)
                .as("the unique index must allow only one persisted row for the dedup tuple")
                .isEqualTo(1L);
    }

    @Test
    void failedRow_isReclaimableExactlyOnce_loserSeesPendingOrReplay() throws Exception {
        String toolName = "update_record";
        String idempotencyKey = UUID.randomUUID().toString();
        String userUsername = "racer-failed";
        String requestHash = "hash-B";
        Duration ttl = Duration.ofHours(1);

        // Seed a RESERVED row and immediately mark it FAILED so two concurrent retries can
        // race to reclaim.
        MutationIntentRepository.ReservationResult initial = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, requestHash, ttl));
        assertThat(initial.state()).isEqualTo(MutationIntentRepository.ReservationState.RESERVED);
        UUID intentId = initial.intent().getId();
        reservedIntentIds.add(intentId);

        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markFailed(initial.intent(), "test-failure"));

        // Two concurrent retries with the same key + same hash race to reclaim.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<MutationIntentRepository.ReservationResult> retry = () -> {
                start.await();
                return systemAuthenticator.withSystem(() ->
                        mutationIntentRepository.reserveOrReplay(
                                toolName, idempotencyKey, userUsername, null, requestHash, ttl));
            };
            Future<MutationIntentRepository.ReservationResult> a = pool.submit(retry);
            Future<MutationIntentRepository.ReservationResult> b = pool.submit(retry);
            start.countDown();

            MutationIntentRepository.ReservationResult resultA = a.get(20, TimeUnit.SECONDS);
            MutationIntentRepository.ReservationResult resultB = b.get(20, TimeUnit.SECONDS);

            int reservedCount = 0;
            for (MutationIntentRepository.ReservationResult r : List.of(resultA, resultB)) {
                if (r.state() == MutationIntentRepository.ReservationState.RESERVED) reservedCount++;
            }
            assertThat(reservedCount)
                    .as("two callers cannot both reclaim a FAILED row as RESERVED")
                    .isEqualTo(1);

            // The non-RESERVED caller sees PENDING (winner is in-flight) or REPLAY (winner
            // already finalized in the time we took to read). Never RESERVED again.
            for (MutationIntentRepository.ReservationResult r : List.of(resultA, resultB)) {
                if (r.state() != MutationIntentRepository.ReservationState.RESERVED) {
                    assertThat(r.state())
                            .as("FAILED reclaim loser sees PENDING (winner in-flight) — not VIOLATION/RESERVED")
                            .isEqualTo(MutationIntentRepository.ReservationState.PENDING);
                }
            }

            // Still exactly one row in the table after the race.
            long rowCount = systemAuthenticator.withSystem(() -> (long)
                    unconstrainedDataManager.load(AiMutationIntent.class)
                            .query("select e from aiMutation_AiMutationIntent e " +
                                    "where e.toolName = :tn and e.idempotencyKey = :k and e.userUsername = :u")
                            .parameter("tn", toolName)
                            .parameter("k", idempotencyKey)
                            .parameter("u", userUsername)
                            .list()
                            .size());
            assertThat(rowCount)
                    .as("FAILED reclaim must not create a second row")
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void commitUnknownRow_duplicateReservation_returnsPendingNotReserved() {
        String toolName = "create_record";
        String idempotencyKey = UUID.randomUUID().toString();
        String userUsername = "user-commit-unknown";
        String requestHash = "hash-C";
        Duration ttl = Duration.ofHours(1);

        // Reserve, then transition the row to COMMIT_UNKNOWN.
        MutationIntentRepository.ReservationResult initial = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, requestHash, ttl));
        UUID intentId = initial.intent().getId();
        reservedIntentIds.add(intentId);
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitUnknown(initial.intent(), "test-commit-unknown"));

        // Duplicate reservation under the same key + same hash must NOT return RESERVED.
        MutationIntentRepository.ReservationResult duplicate = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, requestHash, ttl));
        assertThat(duplicate.state())
                .as("duplicate reservation against COMMIT_UNKNOWN row must surface PENDING / "
                        + "concurrent_modification — never let a same-hash retry duplicate the host write")
                .isEqualTo(MutationIntentRepository.ReservationState.PENDING);

        // Different hash on a COMMIT_UNKNOWN row must surface VIOLATION (call shape changed).
        MutationIntentRepository.ReservationResult differentHash = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        toolName, idempotencyKey, userUsername, null, "hash-different", ttl));
        assertThat(differentHash.state())
                .as("same key + different hash on COMMIT_UNKNOWN row must be a VIOLATION")
                .isEqualTo(MutationIntentRepository.ReservationState.VIOLATION);
    }
}
