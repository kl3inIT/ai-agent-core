---
phase: 11-mutation-capable-built-in-tools
plan: 11
subsystem: testing
tags: [jmix, mutation-tools, audit, idempotency, link-tools, prompt-rules, output-scanner]

requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: Plan 11-10 mutation fixture users, run context helper, and persistence fixtures
provides:
  - TEST-12 durability coverage for commit-unknown, known rollback, post-commit audit failure, and replay permission windows
  - Related-write success and fail-closed security coverage for supported and rejected relationship shapes
  - Link opacity, mutation error translation, conditional prompt-rule, and tool-name scanner regressions
affects: [phase-11, mutation-tools, audit, output-scanner, prompt-contract]

tech-stack:
  added: []
  patterns:
    - Focused mutation durability integration tests use MutationToolTestContext with authenticated mutation users
    - Spring test doubles use @MockitoBean and avoid repository spies
    - Tool-name scanner tests assert the complete read/link/mutation built-in tool-name set

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsCommitUnknownTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsKnownRollbackTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsPostCommitAuditFailureTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsReplayPermissionTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsRelatedWriteSecurityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/link/BuiltInLinkToolsOpacityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationErrorTranslatorTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNamePatternProviderMutationToolsTest.java
  modified:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java

key-decisions:
  - "Use @MockitoBean for the commit-unknown failure probe so the failure does not leak into unrelated Spring test contexts."
  - "Keep Java 17-compatible List.get(0) assertions even when JetBrains suggests List.getFirst()."
  - "Align the existing tool-name scanner baseline test with Phase 11's read, link, and mutation built-in names."

patterns-established:
  - "TEST-12 failure windows are split by finalization window, not grouped into one large test class."
  - "Related-write success tests use non-composition fixtures; rejection/security tests use composition and mocked authorization gates."

requirements-completed: [TEST-12, MUT-07, MUT-10, MUT-12]

duration: 20min
completed: 2026-04-29
---

# Phase 11 Plan 11: Mutation Support Regression Tests Summary

**Durability, related-write, link opacity, error taxonomy, prompt-rule, and scanner regression coverage for mutation-capable built-in tools**

## Performance

- **Duration:** ~20 min in this executor
- **Started:** 2026-04-29T05:55:20Z
- **Completed:** 2026-04-29T06:15:24Z
- **Tasks:** 2
- **Files modified:** 10

## Accomplishments

- Added focused TEST-12 integration tests for commit-unknown durability, known rollback handling, post-COMMITTED audit failure absorption, and exact replay under changed read permission.
- Added related-write success and fail-closed coverage across non-composition success, composition/orphanRemoval rejection, authorization denial gates, LLM exposure denial, and unsupported relationship shape rejection.
- Added supporting regressions for link opacity, mutation error-code translation, conditional mutation prompt rules, and scanner coverage of all read/link/mutation built-in names.
- Updated the older scanner baseline test so the full module suite matches the Phase 11 tool-name set.

## Task Commits

1. **Task 1: TEST-12 durability and related-write security tests** - `759e2e4` (test)
2. **Task 2: Link opacity, translator, prompt-rule, and tool-name scanner tests** - `9e526f5` (test)
3. **Deviation fix: Existing scanner baseline alignment** - `79b06c1` (test)

## Files Created/Modified

- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsCommitUnknownTest.java` - Covers post-host-save finalization failure, COMMIT_FAILED audit durability, non-reclaimable intent status, and no duplicate host write on retry.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsKnownRollbackTest.java` - Covers save-time rollback mapping to validation_failed, ERROR audit, FAILED intent, and retry reclamation.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsPostCommitAuditFailureTest.java` - Covers post-COMMITTED audit write failure absorption, replay safety, and no downgrade from COMMITTED to COMMIT_UNKNOWN.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsReplayPermissionTest.java` - Covers exact replay after current read denial without leaking instanceName or fields.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsRelatedWriteSecurityTest.java` - Covers related-write success paths, authorization gates, exposure denial, composition rejection, unsupported shape rejection, and full audit arguments.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/link/BuiltInLinkToolsOpacityTest.java` - Asserts hidden entities return unknown_entity, never access_denied.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationErrorTranslatorTest.java` - Pins the six stable mutation error codes and legacy converter-code remaps.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerTest.java` - Asserts mutation prompt rules are conditional and never mention prepare_form_draft.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNamePatternProviderMutationToolsTest.java` - Asserts scanner coverage for 12 built-in read/link/mutation tool names and excludes delete_record.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java` - Updates the older exact-regex baseline to include Phase 11 link and mutation tool names.

## Decisions Made

- Used `.\gradlew -p ai-agent :ai-agent:...` for verification because `ai-agent` is an included build in this workspace.
- Kept `List.get(0)` in Java tests despite JetBrains suggestions because the project targets Java 17 and Plan 11-11 explicitly forbids `List.getFirst()` in the commit-unknown assertion.
- Treated the stale scanner baseline as a Plan 11-11 verification bug because it contradicted the source-level Phase 11 scanner contract and blocked the full `:ai-agent:test` run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Scoped commit-unknown failure probe to one test context**
- **Found during:** Task 1 (TEST-12 durability tests)
- **Issue:** A nested test configuration for `MutationIntentFailureProbe` was picked up by broad Spring test scanning and caused unrelated mutation tests to fail finalization.
- **Fix:** Used class-local `@MockitoBean MutationIntentRepository.MutationIntentFailureProbe` in `BuiltInMutationToolsCommitUnknownTest`.
- **Files modified:** `BuiltInMutationToolsCommitUnknownTest.java`
- **Verification:** Task 1 targeted suite passed.
- **Committed in:** `759e2e4`

**2. [Rule 1 - Bug] Fixed related-write saveAll varargs stubbing**
- **Found during:** Task 1 (related-write success tests)
- **Issue:** The mocked `MutationSaveExecutor.saveAll(parent, child)` call did not hit a one-argument varargs matcher, so success-path inverse changes were not persisted.
- **Fix:** Stubbed and verified the two-argument varargs call with `saveAll(any(), any())`.
- **Files modified:** `BuiltInMutationToolsRelatedWriteSecurityTest.java`
- **Verification:** Task 1 targeted suite passed.
- **Committed in:** `759e2e4`

**3. [Rule 1 - Bug] Aligned stale scanner baseline test**
- **Found during:** Plan-level full test verification
- **Issue:** `ToolNameLeakScannerTest` still asserted the old Phase 9 exact regex with only six read tools, while `ToolNamePatternProvider` now intentionally scans read, link, and mutation built-ins.
- **Fix:** Updated the exact regex expectation to include the Phase 11 link and mutation tool names.
- **Files modified:** `ToolNameLeakScannerTest.java`
- **Verification:** `ToolNameLeakScannerTest` and full `:ai-agent:test` passed.
- **Committed in:** `79b06c1`

**4. [Rule 3 - Blocking] Corrected included-build Gradle invocation**
- **Found during:** Task verification
- **Issue:** Plan commands used root `./gradlew :ai-agent:...`, but `ai-agent` is an included build in this workspace.
- **Fix:** Used `.\gradlew -p ai-agent :ai-agent:...` for compile, targeted tests, and full module tests.
- **Files modified:** Documentation only in this summary.
- **Verification:** All listed Gradle commands below exit 0.
- **Committed in:** Documentation only.

**Total deviations:** 4 auto-fixed (3 bugs, 1 blocker)
**Impact on plan:** Fixes were limited to test harness and scanner regression alignment required for the planned verification. No production code changed.

## Verification

- `.\gradlew -p ai-agent :ai-agent:compileTestJava` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsCommitUnknownTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsKnownRollbackTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsPostCommitAuditFailureTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsReplayPermissionTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsRelatedWriteSecurityTest"` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.link.BuiltInLinkToolsOpacityTest" --tests "com.vn.agent.tools.mutation.MutationErrorTranslatorTest" --tests "com.vn.agent.guard.AgentSystemPromptRulesComposerTest" --tests "com.vn.agent.guard.ToolNamePatternProviderMutationToolsTest"` - PASS
- `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.guard.ToolNameLeakScannerTest"` - PASS
- `.\gradlew -p ai-agent :ai-agent:test` - PASS (462 tests completed, 2 skipped)
- `rg -n "@MockBean|@SpyBean|@MockitoSpyBean|getCode\(" ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation ai-agent/ai-agent/src/test/java/com/vn/agent/tools/link ai-agent/ai-agent/src/test/java/com/vn/agent/guard` - PASS, no matches
- JetBrains `get_file_problems(..., errorsOnly=false)` - PASS, zero ERROR-level problems on modified Java files. Remaining warnings were Java 17-incompatible `List.getFirst()` suggestions or intentional reflection/helper warnings.

## Known Stubs

None. Stub scan found only intentional test cleanup null assignments; no TODO/FIXME/placeholder text or unwired UI data stubs.

## Threat Flags

None. This plan added tests only and did not introduce new runtime endpoints, auth paths, file access patterns, or schema changes.

## Auth Gates

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 11-11 closes the remaining Phase 11 TEST-12/supporting regression surface. Phase 11 now has green targeted and full module test coverage for mutation durability, related writes, link opacity, prompt rules, and scanner leakage protection.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/11-mutation-capable-built-in-tools/11-11-SUMMARY.md`.
- Task/deviation commits found: `759e2e4`, `9e526f5`, `79b06c1`.
- No tracked file deletions were introduced by the task or deviation commits.

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-29*
