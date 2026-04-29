package com.vn.agent.tools.mutation.fixture;

import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * Plan 11-10 Task 0 — ordinary CRUD resource role for the fixture entities owned by Plan 11-07B.
 *
 * <p>Test users {@code mutation-user} and {@code mutation-no-marker} get this role so they can
 * READ / CREATE / UPDATE the fixture host entities through Jmix policy, irrespective of whether
 * they additionally hold the {@link com.vn.agent.security.AiAgentMutationRole} marker.
 *
 * <p>The marker role is INTENTIONALLY NOT included here. Plan 11-10's must-haves contract
 * splits "ordinary fixture CRUD" from "AI-agent mutation marker": the marker is a distinct
 * Jmix-created authority that the {@code MutationAuthorizationService} compares with EXACT
 * equality. Mixing the two roles would prevent the {@code mutation-no-marker} test user from
 * exercising the missing-marker denial branch.
 */
@ResourceRole(name = "Mutation Test Fixture", code = MutationTestFixtureTestRole.CODE)
public interface MutationTestFixtureTestRole {

    String CODE = "mutation-test-fixture";

    @EntityPolicy(entityClass = MutationTestFixture.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = MutationParentFixture.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = MutationChildFixture.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = MutationLinkedParentFixture.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = MutationLinkedChildFixture.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    void fixtureAccess();
}
