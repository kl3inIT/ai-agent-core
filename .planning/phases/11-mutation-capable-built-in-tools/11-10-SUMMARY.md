---
phase: 11-mutation-capable-built-in-tools
plan: 10
subsystem: testing
tags: [jmix, mutation-tools, idempotency, audit, security, regression-tests]

requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: Plan 11-07B fixture entities and Plan 11-09 callback wiring
provides:
  - TEST-10 access gating, mass-assignment, relationship exposure, guard, and audit coverage
  - TEST-11 idempotency replay, violation, reservation, and request-hash regression coverage
  - TEST-13 callback shape and mutation audit ownership regression coverage
affects: [phase-11, mutation-tools, callback-audit, test-fixtures]

tech-stack:
  added: []
  patterns:
    - MutationToolTestContext wraps SystemAuthenticator plus RunContext
    - Test-only Jmix module owns mutation fixture persistence descriptor ordering
    - @MockitoBean is used for test doubles

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsAccessGatingTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsAuditArgumentsTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsGuardReceivesCoercedAttributesTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyReplayTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyViolationTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsMassAssignmentTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsRelationshipExposureTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationFixturePersistenceTestConfiguration.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationRequestHashCanonicalizationTest.java
    - ai-agent/ai-agent/src/test/resources/com/vn/agent/tools/mutation/persistence.xml
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolTestContext.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolTestUsersConfiguration.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixtureTestRole.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksDefaultConfigTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksMutationEnabledTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksMutationAuditOwnershipTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationIntentRepositoryReservationTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationIntentRepositoryStateTransitionTest.java

key-decisions:
  - "Use a test-only Jmix module that depends on grid export and security flow UI so fixture persistence wins Jmix's reverse persistence.xml scan."
  - "Use @MockitoBean for the guard capture test to avoid leaking a vetoing @TestConfiguration into unrelated Spring contexts."
  - "Keep scalar null as a valid mutation clear value before delegating non-null scalars to FilterLiteralValueConverter."

patterns-established:
  - "Mutation tests mock collaborator boundaries only when asserting a pre-save gate."
  - "Direct tool success tests use real DataManager saves against the main-store fixture tables and real agentstore dedup rows."

requirements-completed: [TEST-10, TEST-11, TEST-13]

duration: 25min continuation
completed: 2026-04-29
---

# Phase 11 Plan 10: Core Mutation Regression Tests Summary

**Mutation tool regression coverage for access gates, idempotency replay, callback exposure, and single audit ownership**

## Performance

- **Duration:** ~25 min continuation in this executor
- **Started:** 2026-04-29T05:14:08Z
- **Completed:** 2026-04-29T05:39:23Z
- **Tasks:** 4
- **Files modified:** 19

## Accomplishments

- Added TEST-10 coverage for per-attribute denial, marker-role denial, fake-marker denial, mass-assignment rejection, hidden relationship targets, guard coercion, null clears, and full audit argument envelopes.
- Added TEST-11 coverage for idempotent replay, same-key different-shape violation, repository reservation races, valid/invalid state transitions, and canonical raw request hashing.
- Added TEST-13 callback-shape and audit-ownership tests proving mutation callbacks are default-off, opt-in only, never expose delete, and are wrapped by `MutationToolCallbackBoundaryDecorator` rather than `ToolCallbackAuditDecorator`.

## Task Commits

1. **Task 0: Verify shared fixtures and wire mutation test users/run context** - `0d6069c` (test)
2. **Task 1: TEST-13 callback shape plus mutation audit ownership** - `8ce752b` (test)
3. **Task 2: Repository-only idempotency state-transition tests** - `525169c` (test)
4. **Task 3: TEST-10 access gating and tool-level TEST-11 replay/violation tests** - `a4717c3` (test)

## Files Created/Modified

- `MutationToolTestContext.java` - Wraps direct tool calls in both authenticated user context and deterministic `RunContext`.
- `MutationToolTestUsersConfiguration.java` - Seeds mutation personas with exact Jmix-created role authorities.
- `MutationTestFixtureTestRole.java` - Grants fixture CRUD plus wildcard MODIFY attribute policies for mutation tests.
- `BuiltInMutationTools*Test.java` - Covers access gates, mass assignment, relationship exposure, guard coercion, audit arguments, replay, and violation paths.
- `MutationIntentRepository*Test.java` - Covers reservation and state-transition semantics without invoking tool orchestration.
- `MutationRequestHashCanonicalizationTest.java` - Pins canonical raw-call-shape hashing semantics.
- `MutationFixturePersistenceTestConfiguration.java` and test `persistence.xml` - Make fixture entities part of the main JPA persistence unit in Spring tests.
- `MutationAttributeBinder.java` - Preserves scalar null clears before structured-filter literal conversion.

## Decisions Made

- Test fixture persistence uses a test-only Jmix module instead of editing Plan 11-07B-owned `AITestConfiguration`.
- The guard capture regression uses `@MockitoBean MutationGuard`; nested `@TestConfiguration` leaked through the broad test component scan.
- Jmix attribute `MODIFY` policies are used without duplicate `VIEW` policies because Jmix docs state MODIFY includes VIEW.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Preserved scalar null clears**
- **Found during:** Task 3
- **Issue:** Scalar null attributes were delegated to `FilterLiteralValueConverter`, whose structured-filter semantics reject null; mutation semantics require null to clear optional fields.
- **Fix:** Return null before scalar conversion in `MutationAttributeBinder`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java`
- **Verification:** `BuiltInMutationToolsGuardReceivesCoercedAttributesTest` and full Task 3 suite pass.
- **Committed in:** `a4717c3`

**2. [Rule 3 - Blocking] Corrected included-build Gradle invocation**
- **Found during:** Task verification
- **Issue:** Plan commands used root `./gradlew :ai-agent:...`, but `ai-agent` is an included build in this workspace.
- **Fix:** Used `.\gradlew -p ai-agent :ai-agent:...` for compile and targeted tests.
- **Verification:** All listed Gradle commands below exit 0.
- **Committed in:** documentation only in this summary

**3. [Rule 3 - Blocking] Wired mutation fixture persistence for main-store tests**
- **Found during:** Task 3 replay/audit tests
- **Issue:** Jmix metadata saw the fixture entities, but runtime selected add-on `main` persistence descriptors that did not include them.
- **Fix:** Added a test-only Jmix module and `persistence.xml`; module dependencies force this descriptor to win Jmix's reverse scan.
- **Files modified:** `MutationFixturePersistenceTestConfiguration.java`, `src/test/resources/com/vn/agent/tools/mutation/persistence.xml`
- **Verification:** Replay, violation, related-write, and audit success-path tests all persist fixture rows.
- **Committed in:** `a4717c3`

**4. [Rule 2 - Missing Critical] Completed fixture security grants for success-path mutation tests**
- **Found during:** Task 3
- **Issue:** Successful mutation tests require ordinary fixture CRUD and attribute MODIFY grants in addition to the mutation marker role.
- **Fix:** Added fixture CRUD to `mutation-admin` and wildcard MODIFY attribute policies to `MutationTestFixtureTestRole`.
- **Files modified:** `MutationToolTestUsersConfiguration.java`, `MutationTestFixtureTestRole.java`
- **Verification:** Access-gating tests prove missing/fake marker users are denied before reservation, while success-path tests pass under `mutation-user`.
- **Committed in:** `a4717c3`

**5. [Rule 1 - Bug] Removed leaked vetoing guard from unrelated test contexts**
- **Found during:** Task 3 replay debugging
- **Issue:** A nested guard `@TestConfiguration` was picked up by broad test component scanning and vetoed unrelated mutation tests.
- **Fix:** Replaced it with `@MockitoBean MutationGuard` scoped to the guard-capture test.
- **Files modified:** `BuiltInMutationToolsGuardReceivesCoercedAttributesTest.java`
- **Verification:** Replay test and full Task 3 suite pass.
- **Committed in:** `a4717c3`

**Total deviations:** 5 auto-fixed (2 bugs, 2 blockers, 1 missing critical)
**Impact on plan:** All fixes were needed to make the planned regression tests executable and faithful to Jmix security/persistence behavior.

## Verification

- `.\gradlew -p ai-agent :ai-agent:compileTestJava` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.AgentToolCallbacksDefaultConfigTest" --tests "com.vn.agent.tools.mutation.AgentToolCallbacksMutationEnabledTest" --tests "com.vn.agent.tools.mutation.AgentToolCallbacksMutationAuditOwnershipTest"` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryReservationTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryStateTransitionTest"` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsAccessGatingTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsMassAssignmentTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsRelationshipExposureTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsGuardReceivesCoercedAttributesTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsAuditArgumentsTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyReplayTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyViolationTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryReservationTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryStateTransitionTest" --tests "com.vn.agent.tools.mutation.MutationRequestHashCanonicalizationTest"` - PASS
- `rg -n "@Commit|@MockBean|@SpyBean|@MockitoSpyBean|getCode\(" ...` - PASS, no matches
- `rg -n "System\.out|printStackTrace|\[diag\]|\[trace" ...` - PASS, no matches
- JetBrains `get_file_problems(..., errorsOnly=false)` - PASS, no ERROR-level problems. Warnings were fixed where actionable; remaining warnings are intentional defensive guards or Java-version-sensitive suggestions.

## Known Stubs

None. Stub scan found no TODO/FIXME/placeholder text. Null-value matches are intentional cleanup guards and the scalar-null mutation behavior under test.

## Auth Gates

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 11-10 closes TEST-10, TEST-11, and TEST-13. Plan 11-11 can build on the same fixture users/context and focus on TEST-12/supporting regressions without reworking callback exposure or idempotency basics.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/11-mutation-capable-built-in-tools/11-10-SUMMARY.md`.
- Task commits found: `0d6069c`, `8ce752b`, `525169c`, `a4717c3`.
- No tracked file deletions were introduced by the Task 3 commit.

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-29*
