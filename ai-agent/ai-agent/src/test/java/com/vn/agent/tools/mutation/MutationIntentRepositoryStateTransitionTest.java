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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 2 — repository state-machine invariants.
 *
 * <p>Every finalization method on {@link MutationIntentRepository}
 * ({@code markCommitted}, {@code markFailed}, {@code markCommitUnknown}) reloads the current
 * row inside its own {@code REQUIRES_NEW} transaction and only mutates {@code PENDING} rows.
 * Invalid attempts (e.g. {@code COMMITTED -> FAILED}, {@code FAILED -> COMMITTED}) are silently
 * ignored — they MUST NOT downgrade or overwrite a terminal state.
 *
 * <p>The {@code MutationIntentFailureProbe} test seam (test-only bean — never in production) is
 * exercised in the dedicated TEST-12 plan (11-11); this file pins the state-machine contract
 * without simulating a markCommitted failure.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class MutationIntentRepositoryStateTransitionTest {

    @Autowired
    private MutationIntentRepository mutationIntentRepository;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final List<UUID> intentIds = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        if (intentIds.isEmpty()) return;
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : intentIds) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
        });
        intentIds.clear();
    }

    @Test
    void markCommitted_isIgnoredOnFailedRow_andDoesNotOverwriteFailedStatus() {
        AiMutationIntent intent = freshlyReserved("hash-marker-1", "u1");
        systemAuthenticator.runWithSystem(() -> mutationIntentRepository.markFailed(intent, "boom"));

        // Attempt invalid PENDING-required transition on a FAILED row.
        UUID resultId = UUID.randomUUID();
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitted(intent, resultId, "test_Entity"));

        AiMutationIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus())
                .as("markCommitted on a FAILED row must be ignored — never overwrite terminal state")
                .isEqualTo(AiMutationIntentStatus.FAILED);
        assertThat(reloaded.getResultEntityId())
                .as("FAILED row must not pick up resultEntityId from an ignored markCommitted call")
                .isNull();
    }

    @Test
    void markFailed_isIgnoredOnCommittedRow_doesNotDowngrade() {
        AiMutationIntent intent = freshlyReserved("hash-marker-2", "u2");
        UUID resultId = UUID.randomUUID();
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitted(intent, resultId, "test_Entity"));

        // Attempt FAILED downgrade on a COMMITTED row.
        systemAuthenticator.runWithSystem(() -> mutationIntentRepository.markFailed(intent, "late-failure"));

        AiMutationIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus())
                .as("markFailed must NOT downgrade a COMMITTED row")
                .isEqualTo(AiMutationIntentStatus.COMMITTED);
        assertThat(reloaded.getResultEntityId())
                .as("COMMITTED row must keep its resultEntityId")
                .isEqualTo(resultId);
    }

    @Test
    void markCommitUnknown_isIgnoredOnCommittedRow_noDowngrade() {
        AiMutationIntent intent = freshlyReserved("hash-marker-3", "u3");
        UUID resultId = UUID.randomUUID();
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitted(intent, resultId, "test_Entity"));

        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitUnknown(intent, "late-finalization-failure"));

        AiMutationIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus())
                .as("markCommitUnknown must never downgrade a COMMITTED row (Plan 11-07C invariant)")
                .isEqualTo(AiMutationIntentStatus.COMMITTED);
    }

    @Test
    void markCommitUnknown_isIgnoredOnFailedRow_noOverwrite() {
        AiMutationIntent intent = freshlyReserved("hash-marker-4", "u4");
        systemAuthenticator.runWithSystem(() -> mutationIntentRepository.markFailed(intent, "boom"));

        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitUnknown(intent, "should-not-apply"));

        AiMutationIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus())
                .as("markCommitUnknown only mutates PENDING; FAILED row must be left alone")
                .isEqualTo(AiMutationIntentStatus.FAILED);
    }

    @Test
    void markFailed_isIgnoredOnCommitUnknownRow_noOverwrite() {
        AiMutationIntent intent = freshlyReserved("hash-marker-5", "u5");
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitUnknown(intent, "post-save-finalize-failed"));

        systemAuthenticator.runWithSystem(() -> mutationIntentRepository.markFailed(intent, "later-error"));

        AiMutationIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus())
                .as("markFailed must NOT overwrite COMMIT_UNKNOWN — host save may have committed")
                .isEqualTo(AiMutationIntentStatus.COMMIT_UNKNOWN);
    }

    @Test
    void validTransitions_pendingToCommitted_pendingToFailed_pendingToCommitUnknown() {
        // PENDING -> COMMITTED
        {
            AiMutationIntent intent = freshlyReserved("hash-valid-1", "u-valid-1");
            UUID resultId = UUID.randomUUID();
            systemAuthenticator.runWithSystem(() ->
                    mutationIntentRepository.markCommitted(intent, resultId, "test_Entity"));
            assertThat(reload(intent.getId()).getStatus()).isEqualTo(AiMutationIntentStatus.COMMITTED);
        }
        // PENDING -> FAILED
        {
            AiMutationIntent intent = freshlyReserved("hash-valid-2", "u-valid-2");
            systemAuthenticator.runWithSystem(() -> mutationIntentRepository.markFailed(intent, "code-X"));
            assertThat(reload(intent.getId()).getStatus()).isEqualTo(AiMutationIntentStatus.FAILED);
        }
        // PENDING -> COMMIT_UNKNOWN
        {
            AiMutationIntent intent = freshlyReserved("hash-valid-3", "u-valid-3");
            systemAuthenticator.runWithSystem(() ->
                    mutationIntentRepository.markCommitUnknown(intent, "post-save-fail"));
            assertThat(reload(intent.getId()).getStatus()).isEqualTo(AiMutationIntentStatus.COMMIT_UNKNOWN);
        }
    }

    // -------- helpers --------

    private AiMutationIntent freshlyReserved(String requestHash, String userUsername) {
        MutationIntentRepository.ReservationResult result = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        "create_record",
                        UUID.randomUUID().toString(),
                        userUsername,
                        null,
                        requestHash,
                        Duration.ofHours(1)));
        assertThat(result.state()).isEqualTo(MutationIntentRepository.ReservationState.RESERVED);
        intentIds.add(result.intent().getId());
        return result.intent();
    }

    private AiMutationIntent reload(UUID id) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class).id(id).one());
    }
}
