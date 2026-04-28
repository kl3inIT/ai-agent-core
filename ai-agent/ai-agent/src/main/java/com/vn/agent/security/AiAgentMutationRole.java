package com.vn.agent.security;

import io.jmix.security.role.annotation.ResourceRole;

/**
 * Marker resource role granting eligibility to invoke LLM-mediated mutation tools.
 *
 * <p><b>Default scope:</b> no entity access whatsoever. {@code BuiltInMutationTools}
 * checks for this marker (exact authority equality via
 * {@code RoleGrantedAuthorityUtils.createResourceRoleGrantedAuthority(CODE)})
 * before running any mutation call. Jmix entity policies still decide
 * {@code CREATE} / {@code UPDATE} on the host entities the LLM may mutate
 * (SEC-07 — host composes this marker with its own host-entity write policies).
 *
 * <p><b>Why no {@code @EntityPolicy} on {@link com.vn.agent.tools.mutation.AiMutationIntent}:</b>
 * idempotency replay and cleanup use {@code UnconstrainedDataManager}, so the
 * mutation flow never relies on user-level READ on the dedup table. Granting
 * blanket READ here would expose idempotency keys, usernames, conversation IDs,
 * request hashes, and result entity IDs to every mutation-role user — which is
 * a Phase 11 review-fix violation. Operators who need to inspect the dedup
 * table use {@link AiAgentAdminRole} (Plan 11-01 grants ALL there).
 *
 * <p><b>Example host composition:</b>
 * <pre>
 * &#64;ResourceRole(name = "Sales Mutation", code = "sales-mutation")
 * public interface SalesMutationRole extends AiAgentMutationRole {
 *
 *     &#64;EntityPolicy(entityClass = Customer.class,
 *             actions = {EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
 *     void customerWrite();
 * }
 * </pre>
 *
 * <p>Plan 11-07A enforces this marker as the per-user opt-in gate before any
 * idempotency reservation or host save runs.
 */
@ResourceRole(name = "AI Agent Mutation", code = AiAgentMutationRole.CODE)
public interface AiAgentMutationRole {

    String CODE = "ai-agent-mutation";
}
