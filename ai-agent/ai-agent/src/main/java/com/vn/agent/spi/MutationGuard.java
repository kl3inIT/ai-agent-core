package com.vn.agent.spi;

/**
 * SPI for host-supplied mutation policy. Invoked by {@code BuiltInMutationTools}
 * AFTER entity-level + per-attribute access checks pass and BEFORE {@code DataManager.save}.
 *
 * <p>Default implementation is a no-op (registered in {@code AIConfiguration} via
 * {@code @ConditionalOnMissingBean}). Hosts override by declaring their own
 * {@code @Component MutationGuard} bean.
 *
 * <p>A veto raises {@link ToolVetoedException}; the audit row records
 * {@code outcome=BLOCKED} with {@code denialReason=exception.getMessage()}.
 * The error code surfaced to the LLM is {@code access_denied} with the
 * {@code expected} hint "do not retry; surface to user".
 *
 * <p>Mirrors {@link ToolGuard} (Phase 9) with a typed {@link MutationIntent}
 * argument instead of {@code Map<String,Object>}.
 */
public interface MutationGuard {

    /**
     * @param intent typed mutation call descriptor
     * @throws ToolVetoedException when the mutation must be blocked
     */
    void check(MutationIntent intent) throws ToolVetoedException;
}
