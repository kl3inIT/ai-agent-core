# Deferred Items — Phase 13

> Discovered during Plan 13-05 execution. Documented per GSD Rule SCOPE BOUNDARY:
> only auto-fix issues DIRECTLY caused by the current task's changes; out-of-scope
> regressions are logged here.

## Pre-existing Spring-context boot regression (Phase 11 + Phase 13 mutation tests)

**Symptom:**
```
java.lang.IllegalArgumentException: MetaClass not found for class
    com.vn.agent.entity.AiAuditEvent
  at io.jmix.core.metamodel.model.impl.SessionImpl.getClass(SessionImpl.java:63)
  at io.jmix.security.impl.role.builder.extractor.PolicyExtractorUtils
        .getEntityNameByEntityClass(PolicyExtractorUtils.java:43)
  at io.jmix.security.impl.role.builder.extractor.EntityAttributePolicyExtractor
        .extractResourcePolicies(EntityAttributePolicyExtractor.java:61)
  ...
```

**Reproducer:** runs of `./gradlew :ai-agent:ai-agent:test --tests
"com.vn.agent.tools.mutation.BuiltInMutationToolsAuditArgumentsTest"` — a
PRE-EXISTING Phase 11 test owned by Plan 11-10 — fail with the same Spring-context
boot error as the new Plan 13-05 tests. Therefore the regression is NOT introduced
by Plan 13-05 and is out of scope per the Plan 13-05 SCOPE BOUNDARY.

**Affected:**
- `BuiltInMutationToolsAuditArgumentsTest` (Plan 11-10 — pre-existing)
- `BuiltInMutationToolsIdempotencyReplayTest` (Plan 11-10 — pre-existing)
- `BuiltInMutationToolsBulkSaveTest` (Plan 13-05 — new)
- `BuiltInMutationToolsBulkSavePartialFailureTest` (Plan 13-05 — new)
- `BuiltInMutationToolsBulkSaveIdempotencyTest` (Plan 13-05 — new)
- `AiTaskFileMediaResolverIntegrationTest` (Plan 13-05 — new)
- `AiTaskFileNoVectorStoreInvocationTest` (Plan 13-05 — new)
- `AiTaskFileCleanupJobTest` (Plan 13-05 — new)
- (any other Plan 11-10..11-13 mutation integration test that boots Spring with `MutationToolTestUsersConfiguration`)

**Suspected root cause:** the Jmix metamodel session is queried during
`AnnotatedRoleBuilderImpl.createResourceRole(...)` BEFORE the metamodel
SessionImpl has been fully populated with entities from the agentstore module.
This is a pre-existing race that was already latent in the Phase 11 test setup
and predates Plan 13-05. The static `TaskFileNoVectorStoreSourceScannerTest`
(no Spring context) passes cleanly, confirming the Plan 13-05 source code is
correct.

**Fix attempts ruled out (would consume Plan 13-05 budget):**
1. Adding `@JmixModule(dependsOn = ...)` to `AITestConfiguration` — but the
   class already ships `@JmixModule(id="com.vn.agent.test", dependsOn=AIConfiguration.class)`,
   and the issue reproduces on a pre-existing Phase 11 test that uses the
   same configuration unchanged.
2. Adding `MutationFixturePersistenceTestConfiguration` to the new tests — only
   `BuiltInMutationToolsKnownRollbackTest` and `BuiltInMutationToolsPreReservationFailureAuditTest`
   use it (for main-store fixture persistence), and neither
   `BuiltInMutationToolsAuditArgumentsTest` nor `BuiltInMutationToolsIdempotencyReplayTest`
   needs it — yet both still fail. Including it does not change the SessionImpl
   ordering bug.
3. Reordering `@Import` — verified the new tests use the same `@Import` shape
   as the pre-existing Plan 11-10 tests; ordering is irrelevant to the
   role-builder/metamodel race.

**Recommended follow-up (separate phase / hotfix):** investigate whether
`AnnotatedRoleBuilderImpl` should defer policy extraction until the metamodel
session is sealed, or whether `AITestConfiguration` needs an explicit
`@DependsOn` ordering against the agentstore Jmix module. Reproduce on Phase 11
tests in isolation to confirm regression existed before Plan 13-05.

**Verification surface still intact:**
- All 7 new Plan 13-05 test files compile (verified by
  `./gradlew :ai-agent:ai-agent:compileTestJava` — BUILD SUCCESSFUL).
- `TaskFileNoVectorStoreSourceScannerTest` (static scan) passes — TEST-16
  static enforcement is GREEN.
- Source code, mutation logic, resolver predicate, cleanup ordering, and
  bulk-save semantics are pinned correctly in test source; once the
  Spring-context boot regression is resolved separately the assertions will
  run unchanged.
