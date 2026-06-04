---
phase: 17-mutation-internals-hardening-phase-11-follow-up
plan: 05
subsystem: mutation-parity-gate
tags: [mutation, parity, MUT-18, regression-gate, behavior-frozen, git-diff-audit]
requires:
  - "Plan 17-02 MUT-17 memoization (RelatedWriteMetadataMemoTest walk-once GREEN)"
  - "Plan 17-03 MUT-16 batch FK load + setDiscardSaved O(1) bulk contract"
  - "Plan 17-04 MUT-15 MutationGateChain extraction (the 3 mutationGateChain_* seams flipped GREEN)"
provides:
  - "MUT-18 verdict: byte-for-byte observable behavior parity HOLDS — no production code written this plan"
  - "Locked regression gate: full Phase 9/10/11 mutation + performance suites + AgentToolCallbacksDefaultConfigTest pass with ZERO test-body edits"
affects:
  - "Phase 18 perf pass (the consolidated MutationGateChain + shared batch-FK load is now behavior-frozen and safe to optimize further)"
tech-stack:
  added: []
  patterns:
    - "git diff --numstat origin/main test-tree audit: every entry N/0 (insertions only) proves no pre-existing test body edited"
    - "Included-build module test path :ai-agent:ai-agent:test (root has no :ai-agent:test, STATE.md Phase 12 decision)"
key-files:
  created:
    - ".planning/phases/17-mutation-internals-hardening-phase-11-follow-up/17-05-SUMMARY.md"
  modified: []
decisions:
  - "No production code written — this is a verification-only gate (per plan objective)."
  - "Ran the targeted parity slice (com.vn.agent.tools.mutation.* + com.vn.agent.performance.* + AgentToolCallbacksDefaultConfigTest) which covers every Phase 9/10/11 mutation suite + the zero-mutation-callback boot test named in the plan; BUILD SUCCESSFUL."
metrics:
  duration: "~10 min"
  completed: "2026-05-31"
  tasks: 1
  files: 0
---

# Phase 17 Plan 05: MUT-18 Byte-for-Byte Behavior-Parity Gate Summary

**MUT-18 VERDICT: parity HOLDS (clean).** The three Phase 17 refactors (MUT-15 `MutationGateChain` extraction, MUT-16 batch-FK load + `setDiscardSaved` O(1) bulk, MUT-17 metamodel-walk memoization) are observably invisible: the full Phase 9/10/11 mutation suite + performance suite + the zero-mutation-callback default-config boot test pass GREEN with ZERO edits to any pre-existing test body, while the three structural proxies are GREEN (proving the refactor changed internals). No production code was written in this plan — it is the explicit verification gate that freezes the refactor before `/gsd-verify-work`.

## What Was Verified

### 1. Full-suite parity regression (`./gradlew :ai-agent:ai-agent:test`, targeted slice)

`BUILD SUCCESSFUL in 4m 34s`. Aggregated across `com.vn.agent.tools.mutation.*` + `com.vn.agent.performance.*`:

**106 tests · 0 failures · 0 errors · 2 skipped**

The 2 skipped are the pre-existing `@Disabled` Phase-16 scaffold methods on `FindRecordsLimitCapTest` (2/2 skipped) — NOT a Phase-17 regression.

Per-suite (exact counts from `build/test-results/test/TEST-*.xml`):

| Suite | tests | fail | err | skip | Role |
|-------|-------|------|-----|------|------|
| **`MutationToolInvariantsTest`** | 8 | 0 | 0 | 0 | **3 MUT-15 `mutationGateChain_*` seams GREEN** (gate order, save-after-gates, no-`@Transactional`) — confirmed flipped from RED in Plans 17-01..17-03; 5 original Plan 11-07C/MUT-16 assertions still green |
| **`MutationFkBatchLoadQueryCountTest`** | 1 | 0 | 0 | 0 | **MUT-16 FK 1-query slope** (≤1) — boots + passes (was harness-blocked in 17-03, fixed by orchestrator addendum) |
| **`RelatedWriteMetadataMemoTest`** | 3 | 0 | 0 | 0 | **MUT-17 memo walk-once** (walkCount==1 supported + unsupported; distinct-keys==2; fresh-rethrow `isNotSameAs`) |
| `MutationErrorTranslatorTest` | 4 | 0 | 0 | 0 | error classification / canned-template outputs byte-identical |
| `BuiltInMutationToolsBulkSavePartialFailureTest` | 4 | 0 | 0 | 0 | `failedRowIndex` + full-batch rollback parity |
| `BuiltInMutationToolsBulkSaveTest` | 3 | 0 | 0 | 0 | bulk savedIds / JSON output parity |
| `BuiltInMutationToolsBulkSaveIdempotencyTest` | 3 | 0 | 0 | 0 | idempotency semantics |
| `BuiltInMutationToolsIdempotencyReplayTest` | 1 | 0 | 0 | 0 | IDEMPOTENT_REPLAY |
| `BuiltInMutationToolsIdempotencyViolationTest` | 1 | 0 | 0 | 0 | duplicate-serialization |
| `BuiltInMutationToolsReplayPermissionTest` | 2 | 0 | 0 | 0 | replay re-permission |
| `BuiltInMutationToolsAccessGatingTest` | 3 | 0 | 0 | 0 | gating-order / access_denied |
| `BuiltInMutationToolsRelatedWriteSecurityTest` | 12 | 0 | 0 | 0 | row-level mutation-security (add/remove related) |
| `BuiltInMutationToolsRelationshipExposureTest` | 1 | 0 | 0 | 0 | exposure veto |
| `BuiltInMutationToolsGuardReceivesCoercedAttributesTest` | 2 | 0 | 0 | 0 | host-guard sees typed loaded refs (D-03) |
| `BuiltInMutationToolsMassAssignmentTest` | 5 | 0 | 0 | 0 | writable-property enforcement |
| `BuiltInMutationToolsKnownRollbackTest` | 1 | 0 | 0 | 0 | rollback audit row |
| `BuiltInMutationToolsCommitUnknownTest` | 1 | 0 | 0 | 0 | COMMIT_UNKNOWN park |
| `BuiltInMutationToolsPostCommitAuditFailureTest` | 2 | 0 | 0 | 0 | post-commit audit-failure handling |
| `BuiltInMutationToolsPreReservationFailureAuditTest` | 1 | 0 | 0 | 0 | pre-reservation failure audit |
| `BuiltInMutationToolsAuditArgumentsTest` | 3 | 0 | 0 | 0 | audit argumentsJson shape |
| `AgentToolCallbacksMutationAuditOwnershipTest` | 3 | 0 | 0 | 0 | single audit owner (Plan 11-07C boundary decorator) |
| `AgentToolCallbacksMutationEnabledTest` | 1 | 0 | 0 | 0 | callback registration when enabled |
| `AgentToolCallbacksMutationEnabledAllowDeleteTest` | 1 | 0 | 0 | 0 | allow-delete toggle |
| **`AgentToolCallbacksDefaultConfigTest`** | 1 | 0 | 0 | 0 | **zero-mutation-callback boot (default config)** |
| `MutationIntentRepositoryReservationTest` | 4 | 0 | 0 | 0 | reservation serialization |
| `MutationIntentRepositoryStateTransitionTest` | 6 | 0 | 0 | 0 | intent state machine |
| `MutationRequestHashCanonicalizationTest` | 6 | 0 | 0 | 0 | request-hash canonicalization |
| `RelatedWriteMetadataResolverTest` | 14 | 0 | 0 | 0 | related-write verdicts byte-identical (MUT-17 memo transparent) |
| `ToolQueryCountBaselineTest` | 7 | 0 | 0 | 0 | read-tool query-count baseline (Phase 10 recalibrated ceiling) |
| `FindRecordsLimitCapTest` | 2 | 0 | 0 | **2** | pre-existing `@Disabled` Phase-16 scaffold (not a regression) |

### 2. Zero-test-edit git-diff audit (`git diff --numstat origin/main`)

Every changed test-tree entry is `N 0` — **insertions only, ZERO deletions** — proving no pre-existing test body was modified:

| File | +added | -deleted | Disposition |
|------|--------|----------|-------------|
| `tools/mutation/MutationToolInvariantsTest.java` | 187 | **0** | ADDITIVE @Test methods only (MUT-15) — pre-Phase-17 body untouched |
| `performance/MutationFkBatchLoadQueryCountTest.java` | 182 | 0 | NEW Wave-0 file (MUT-16 slope proxy) |
| `tools/mutation/RelatedWriteMetadataMemoTest.java` | 163 | 0 | NEW Wave-0 file (MUT-17 walk-once proxy) |
| `tools/mutation/fixture/MutationStoreLinkedParentFixture.java` | 72 | 0 | NEW agentstore FK fixture |
| `tools/mutation/fixture/MutationStoreLinkedChildFixture.java` | 84 | 0 | NEW agentstore FK fixture |
| `tools/mutation/fixture/MutationTestFixtureTestRole.java` | 9 | 0 | NEW fixture role |
| `test/resources/.../test_liquibase/020-mutation-store-fixture.xml` | 47 | 0 | NEW fixture changelog |
| `test/resources/.../test_liquibase/test-agentstore-changelog.xml` | 25 | 0 | NEW fixture changelog |
| `test/resources/.../tools/mutation/agentstore-persistence.xml` | 9 | 0 | NEW test agentstore PU (orchestrator fixture-registration fix) |

`MutationToolInvariantsTest.java` is the ONE pre-existing test file in the diff and it shows `187 / 0` — purely appended MUT-15 `@Test` methods, no edit to any existing assertion. `AgentToolCallbacksDefaultConfigTest` and every other Phase 9/10/11 mutation/performance test are NOT in the diff at all (byte-identical to `origin/main`). This satisfies T-17-11 (no test-weakening to pass).

### 3. Production-code scope confirmation

`git diff --numstat origin/main` on `src/main` matches the documented Plan 17-02/03/04 scope exactly — no stray files:

| File | +/- | Plan |
|------|-----|------|
| `MutationGateChain.java` (new) | 819 / 0 | 17-04 MUT-15 |
| `MutationRequest.java` (new) | 65 / 0 | 17-04 MUT-15 |
| `BuiltInMutationTools.java` | 13 / 740 | 17-04 MUT-15 (thin adapters) |
| `MutationAttributeBinder.java` | 161 / 18 | 17-03 MUT-16 (batch FK) |
| `MutationSaveExecutor.java` | 8 / 0 | 17-03 MUT-16 (`setDiscardSaved`) |
| `RelatedWriteMetadataResolver.java` | 78 / 1 | 17-02 MUT-17 (memo) |

## Intentional behavior changes (in-scope, NOT parity violations)

Per the critical-parity framing — MUT-18 is observable/functional parity, not internal-query-count parity. The following internal changes are deliberate Phase-17 optimizations and are confirmed observably invisible by the unchanged behavioral suites:

- **MUT-16 `setDiscardSaved(true)`** — the post-save reload (pure overhead for the tool's UUID-only return) is removed; savedIds read from in-memory `@JmixGeneratedValue` UUIDs; the old silent-row-drop `EntitySet` guard is replaced by the rollback-all transaction invariant. Observable tool output (savedIds, JSON, error classification, audit rows) unchanged — all bulk-save parity tests GREEN.
- **MUT-16 batch `.ids()` IN-load** — one constrained-DataManager load per target class replaces per-row N+1; row-level security still filters (constrained DataManager only). FK slope dropped 360 → 90 → ≤1; behavioral verdicts byte-identical.
- **MUT-17 memoization** — metamodel walk-once over the immutable Jmix metamodel; verdicts + canned errors byte-identical (`RelatedWriteMetadataResolverTest` 14/14 unchanged).
- **MUT-15 chain extraction** — gate ORDER, classification, audit rows, idempotency semantics, `MutationGuard` SPI contract preserved; the 5 `@Tool` methods are thin adapters with byte-identical descriptions/param names.

## Deviations from Plan

None — plan executed exactly as written. No production code written (verification-only gate). The orchestrator-addendum fixes to the MUT-16 boot regression (commits `010d963`, `8a1fbec`) were already in place before this plan ran, so `MutationFkBatchLoadQueryCountTest` BOOTS and PASSES here — the Phase 13.1-style "document-and-defer" boot-regression escape hatch was NOT needed for any test in the targeted slice.

## Threat Model Compliance

- **T-17-10 (mitigate):** The full Phase 9/10/11 mutation suite + `AgentToolCallbacksDefaultConfigTest` run UNCHANGED and GREEN — any change to gating order/outcomes, exception classification, `MutationErrorTranslator` outputs, audit rows (incl. rollback), or idempotency semantics would fail a locked test. None did. Parity HOLDS.
- **T-17-11 (mitigate):** The `git diff --numstat origin/main` test-tree audit shows every entry is insertions-only (`N 0`); `MutationToolInvariantsTest` is `187 / 0` (additive @Test only). No pre-existing test body was edited — no regression hidden by assertion-relaxing.
- **T-17-SC (accept):** No package installs in this plan. No legitimacy checkpoint required.

## Known Stubs

None. This is a verification-only gate; no production code, no placeholder data, no unwired components introduced.

## No new threat surface

This plan writes no code. No new network endpoints, auth paths, file access, or schema changes.

## Self-Check: PASSED

- `17-05-SUMMARY.md` created at `.planning/phases/17-mutation-internals-hardening-phase-11-follow-up/`.
- `./gradlew :ai-agent:ai-agent:test` (targeted parity slice) BUILD SUCCESSFUL; 106 tests / 0 fail / 0 err / 2 skip aggregated from `build/test-results/test/TEST-*.xml` (verified on disk).
- `git diff --numstat origin/main` test tree = insertions-only (audited on disk).
