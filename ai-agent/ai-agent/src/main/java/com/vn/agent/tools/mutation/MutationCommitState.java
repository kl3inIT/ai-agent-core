package com.vn.agent.tools.mutation;

/**
 * Tracks where a mutation tool call stands relative to the host save and idempotency
 * finalization. Drives {@link MutationCommitCoordinator#markFailedIfReserved} and
 * {@link MutationCommitCoordinator#auditOutcome} so the catch ladder cannot accidentally
 * downgrade an already-COMMITTED dedup row or mark a host-saved-but-finalization-failed
 * row as FAILED (which would let a same-hash retry duplicate the host write).
 *
 * <p>Plan 11-07 reference contract:
 * <ul>
 *     <li>{@link #NO_HOST_WRITE} — reservation succeeded but
 *         {@link MutationSaveExecutor#save(Object)} / {@link MutationSaveExecutor#saveAll}
 *         has not yet returned. Failures here may mark the dedup row FAILED.</li>
 *     <li>{@link #HOST_SAVE_RETURNED} — host save returned but
 *         {@link MutationIntentRepository#markCommitted} has not yet succeeded. Failures
 *         here may mark COMMIT_UNKNOWN; never FAILED.</li>
 *     <li>{@link #INTENT_COMMITTED} — both host save and markCommitted succeeded. Later
 *         audit/result failures must NOT downgrade the dedup row.</li>
 * </ul>
 */
public enum MutationCommitState {
    NO_HOST_WRITE,
    HOST_SAVE_RETURNED,
    INTENT_COMMITTED
}
