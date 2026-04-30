package com.vn.agent.tools.mutation;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Lifecycle status of an {@link AiMutationIntent} reservation row.
 *
 * <p>Phase 11 review fix — concurrency-safe idempotency:
 * <ul>
 *     <li>{@link #PENDING} — reservation row inserted before host save. The
 *         composite unique index on {@code (TOOL_NAME, IDEMPOTENCY_KEY,
 *         USER_USERNAME)} blocks concurrent duplicates at the database level.</li>
 *     <li>{@link #COMMITTED} — host {@code DataManager.save} returned and
 *         finalization wrote {@code resultEntityId} / {@code resultEntityName}.
 *         Replay of this row returns {@code IDEMPOTENT_REPLAY}.</li>
 *     <li>{@link #FAILED} — host save threw or pre-save guard rejected. The
 *         row is reclaimable: a fresh idempotency key with corrected request
 *         shape may proceed.</li>
 *     <li>{@link #COMMIT_UNKNOWN} — host save returned successfully but
 *         post-save finalization threw before the row could be marked
 *         {@code COMMITTED}. The row is parked permanently (NOT reclaimable);
 *         operators investigate via the audit trail.</li>
 * </ul>
 */
public enum AiMutationIntentStatus implements EnumClass<String> {

    PENDING("PENDING"),
    COMMITTED("COMMITTED"),
    FAILED("FAILED"),
    COMMIT_UNKNOWN("COMMIT_UNKNOWN");

    private final String id;

    AiMutationIntentStatus(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiMutationIntentStatus fromId(String id) {
        for (AiMutationIntentStatus status : values()) {
            if (status.getId().equals(id)) {
                return status;
            }
        }
        return null;
    }
}
