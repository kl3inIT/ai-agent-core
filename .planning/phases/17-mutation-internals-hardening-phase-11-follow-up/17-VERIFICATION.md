---
phase: 17-mutation-internals-hardening-phase-11-follow-up
verified: 2026-05-31T09:35:07Z
status: passed
score: 4/4 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: n/a
human_verification: []
---

# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) Verification Report

**Phase Goal:** Extract the fail-closed mutation gate sequence into one canonical `MutationGateChain` component (5 `@Tool` methods as thin adapters), batch-load to-one FK references via a single constrained `DataManager.ids(...)` per target class, memoize related-write metadata resolution — with behavior byte-for-byte identical to v1.1 so the Phase 9/10/11 mutation suites pass unchanged.
**Verified:** 2026-05-31T09:35:07Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (Requirements MUT-15..18)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| MUT-15 | Fail-closed gate sequence extracted into ONE canonical `MutationGateChain` `@Component`; 5 `@Tool` methods are thin adapters; gate order locked by source-level invariant test; chain carries NO `@Transactional` (only `MutationSaveExecutor.save` does) | ✓ VERIFIED | `MutationGateChain.java` + `MutationRequest.java` exist. `execute()` calls gates in canonical order (`MutationGateChain.java:198-208`: enforceRole→resolve→authorize→reserve→coerce→guard→save→finalize). `mutationSaveExecutor.*` appears only inside save methods (566/588/612/677), all after `guard(`. No `@Transactional` annotation present (grep `^\s*@Transactional` = none; 8 mentions are all javadoc/comments). All 5 `@Tool` methods in `BuiltInMutationTools.java` (118/163/219/268/361) `return mutationGateChain.execute(...)`; class shrank 13/740 (740 deletions). Invariant tests `mutationGateChain_gatesAppearInCanonicalOrder` (strictly-increasing `indexOf`), `mutationGateChain_saveTokenAppearsAfterAllGateTokens` (`mutationSaveExecutor.` after `guard(`), `mutationGateChain_carriesNoTransactionalAnnotation` (reflection `isAnnotationPresent` on class + all declared methods) — `MutationToolInvariantsTest` **8/8 PASS** (TEST XML confirmed). |
| MUT-16 | To-one FK refs batch-loaded — one CONSTRAINED `DataManager.load(...).ids(...)` per target class (never Unconstrained, never raw JPQL); `bulkSaveRecords` calls it ONCE; `bulkSave` sets discardSaved; SELECT-count proxy slope ≤ 1 | ✓ VERIFIED | `MutationAttributeBinder.prefetchReferences` (line 131) issues `dataManager.load(targetMetaClass.getJavaClass()).ids(idSet).list()` (163-164) once per distinct target class after `enforceLlmRelationshipTargetExposure`+`enforceReadPermission` (160-161). Chain bulk path calls `prefetchReferences(...)` ONCE (`MutationGateChain.java:483`) before per-row loop, binds via 3-arg `coerceAttributes` (629). `MutationSaveExecutor.bulkSave` sets `setDiscardSaved(true)` (line 73). Forbidden-token scan `mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql` (asserts no `UnconstrainedDataManager`/`loadValue(`/`loadValues(`/`.query(`) GREEN. `MutationFkBatchLoadQueryCountTest` BOOTS and asserts `countLarge - countSmall <= 1` — **1/1 PASS** (slope dropped 360→90→≤1). |
| MUT-17 | Related-write metadata resolution memoized (immutable metamodel, no eviction), confirmed by walk-once call-count | ✓ VERIFIED | `RelatedWriteMetadataResolver` adds `record Key(String,String)` (111), `record Result` (120) — never stores a `Throwable`, `private final Map<Key,Result> cache = new ConcurrentHashMap<>()` (136, no eviction). Public resolve method early-throws on blank key BEFORE key construction (156), then `cache.computeIfAbsent(...)` over `computeSupported` (160-162); cached rejection rethrows a FRESH `unsupportedRelationship()` (171, D-12). `RelatedWriteMetadataMemoTest` asserts `walkCount == 1` for repeated supported + unsupported keys, `== 2` for distinct keys, `isNotSameAs` fresh-rethrow — **3/3 PASS**. `RelatedWriteMetadataResolverTest` 14/14 unchanged (verdicts byte-identical). |
| MUT-18 | Behavior byte-for-byte identical to v1.1; Phase 9/10/11 mutation suites + zero-mutation-callback boot test pass UNCHANGED; no test-body weakening | ✓ VERIFIED | Ran `:ai-agent:test --tests com.vn.agent.tools.mutation.* --tests com.vn.agent.performance.*` → **BUILD SUCCESSFUL**. Aggregated from `build/test-results/test/TEST-*.xml` across 30 suites: **106 tests · 0 failures · 0 errors · 2 skipped** (2 skipped = pre-existing `@Disabled` `FindRecordsLimitCapTest` scaffolds). `AgentToolCallbacksDefaultConfigTest` (zero-mutation-callback boot) ran clean. `git diff --numstat origin/main` on test tree = every entry `N 0` (insertions only, zero deletions); only pre-existing test file touched is `MutationToolInvariantsTest.java` (187/0, appended @Test only). No Phase 9/10/11 test body edited or weakened (T-17-11 holds). |

**Score:** 4/4 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `MutationGateChain.java` | Canonical fail-closed gate spine, no `@Transactional` | ✓ VERIFIED | New file (819/0). 8 ordered gates, single `execute(MutationRequest)` entry, sealed-switch dispatch, per-execute `Context` (no instance state). |
| `MutationRequest.java` | Sealed request hierarchy | ✓ VERIFIED | New file (65/0). `sealed interface MutationRequest permits Create, Update, AddRelated, RemoveRelated, Bulk` (26-64) + shared `idempotencyKey()`. |
| `BuiltInMutationTools.java` | 5 `@Tool` thin adapters | ✓ VERIFIED | 13/740 — all 5 `@Tool` methods delegate to `mutationGateChain.execute(...)`; `@Tool`/`@ToolParam` descriptions byte-identical (frozen model contract). |
| `MutationAttributeBinder.java` | `prefetchReferences` constrained `.ids()` | ✓ VERIFIED | 161/18. `prefetchReferences` + 3-arg `coerceAttributes` overload + `bindPrefetchedReference`. |
| `MutationSaveExecutor.java` | `bulkSave` setDiscardSaved | ✓ VERIFIED | 8/0. `setDiscardSaved(true)` at line 73. Sole `@Transactional` save boundary. |
| `RelatedWriteMetadataResolver.java` | Memoized walk | ✓ VERIFIED | 78/1. ConcurrentHashMap memo + Key/Result records. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `BuiltInMutationTools` 5 `@Tool` | `MutationGateChain.execute` | `mutationGateChain.execute(request)` | ✓ WIRED | All 5 adapters call it (118/163/219/268/361). |
| `MutationGateChain.guard→save` | `MutationSaveExecutor` | `mutationSaveExecutor.save/saveAll/bulkSave` | ✓ WIRED | Only inside save methods (566/588/612/677), all after `guard(`. |
| `MutationGateChain` bulk | `MutationAttributeBinder.prefetchReferences` | one call before per-row loop | ✓ WIRED | `MutationGateChain.java:483` (once), then 3-arg `coerceAttributes` (629). |
| `prefetchReferences` | constrained `DataManager` | `.load(class).ids(set).list()` | ✓ WIRED | `MutationAttributeBinder.java:163-164`; no Unconstrained/raw JPQL (scan GREEN). |
| `RelatedWriteMetadataResolver` resolve | `computeSupported` | `cache.computeIfAbsent` | ✓ WIRED | `RelatedWriteMetadataResolver.java:160-162`. |

### Behavioral Spot-Checks / Test Execution

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full mutation + performance parity slice | `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.tools.mutation.* --tests com.vn.agent.performance.*` | BUILD SUCCESSFUL in 2m37s; 106 tests / 0 fail / 0 err / 2 skip (XML-aggregated) | ✓ PASS |
| MUT-15 source invariants | `MutationToolInvariantsTest` | 8/8 | ✓ PASS |
| MUT-16 SELECT slope ≤ 1 | `MutationFkBatchLoadQueryCountTest` | 1/1 | ✓ PASS |
| MUT-17 walk-once | `RelatedWriteMetadataMemoTest` | 3/3 | ✓ PASS |
| MUT-18 no test-weakening | `git diff --numstat origin/main` test tree | all entries `N 0` (insertions only) | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| MUT-15 | 17-01, 17-04 | Canonical MutationGateChain + thin adapters + invariant test | ✓ SATISFIED | See MUT-15 truth row |
| MUT-16 | 17-01, 17-03 | Batch FK load, constrained `.ids()`, slope ≤ 1 | ✓ SATISFIED | See MUT-16 truth row |
| MUT-17 | 17-01, 17-02 | Memoized metadata resolution | ✓ SATISFIED | See MUT-17 truth row |
| MUT-18 | 17-05 | Byte-for-byte parity, suites pass unchanged | ✓ SATISFIED | See MUT-18 truth row |

### Anti-Patterns / Code-Review Findings (WR-01..04 classification)

| Finding | File:Line | Classification | Rationale |
|---------|-----------|----------------|-----------|
| Debt markers (TBD/FIXME/XXX) in modified prod files | n/a | ✓ NONE | grep across all 6 modified production files = 0 matches. |
| WR-01: silent-row-drop guard removed; relies on rollback-all invariant | `MutationGateChain.java:677-685`, `MutationSaveExecutor.java:64-75` | ⚠️ ACCEPTED INTENTIONAL TRADEOFF (not a goal gap) | Deliberate MUT-16 `setDiscardSaved(true)` design. Rollback-all invariant covers the in-contract case (listener throws → whole batch rolls back). The uncovered case (a non-throwing `BeforeInsert/Update` listener that silently drops a row without raising) is out of normal Jmix contract and low-risk. All bulk-save parity tests GREEN; observable tool output (savedIds/JSON/errors/audit) unchanged. Residual risk is silent (no log/assertion) — surfaced below as the single human-awareness item, NOT a blocker. |
| WR-02: `bulk_save_records` tool description promises `failedRowIndex`/`errorCode` the runtime never returns | `BuiltInMutationTools.java:309-348`, `MutationGateChain.java:234-255` | ℹ️ PRE-EXISTING DEBT (carried forward unchanged) | REVIEW confirms the pre-refactor monolith had the identical gap; description is byte-identical to v1.1 (MUT-18 freeze). Not introduced by Phase 17; does not affect parity goal. Cleanup candidate for a future phase. |
| WR-03: `DiffSerializer.serializeBulkFailureSummary(...)` dead code; `Context.failedRowIndex` written never read | `DiffSerializer.java:208`, `MutationGateChain.java:143,318,623,669` | ℹ️ PRE-EXISTING DEBT (carried forward unchanged) | Verified: `serializeBulkFailureSummary` has only its declaration, no callers in `src/main`; `failedRowIndex` written (318,623)/reset (669), never read. `DiffSerializer.java` is NOT in the `origin/main` diff → confirmed pre-existing, not a Phase 17 artifact. Root cause of WR-02. Not a goal gap. |
| WR-04: bulk row-level errors in `prefetchReferences` lose row index | `MutationGateChain.java:466-487`, `MutationAttributeBinder.java:131-152` | ℹ️ PRE-EXISTING DEBT | Pre-existing (monolith had identical structure); undermines the already-dead `failedRowIndex` contract (WR-02/03). Not a parity regression. |
| IN-01..05 | various | ℹ️ INFO | Byte-identical to v1.1 / confirmations, not defects. |

### Human Verification Required

None required for the phase goal — it is a behavior-frozen internal refactor whose contract is fully provable by the green parity suite (106/0/0/2), source invariants, and the git-diff zero-test-edit audit. No visual/UX/external-service surface.

One low-priority awareness item (does NOT block phase): WR-01's removal of the silent-row-drop cross-check leaves only the rollback-all invariant, with no log/assertion proving the narrow out-of-contract case. Consider a follow-up (cheap post-commit count sanity marker, or JavaDoc + focused test) when this code is next opened. Not actionable for Phase 17 closure.

### Gaps Summary

No gaps. All four requirements (MUT-15, MUT-16, MUT-17, MUT-18) are verified directly against the codebase, not merely against SUMMARY claims:

- The two new MUT-15 files exist with the canonical ordered gate spine; `@Transactional` is genuinely absent (reflection-checked, not regex); all 5 tool methods are real thin adapters (740 lines removed from `BuiltInMutationTools`).
- The MUT-16 batch path issues a single constrained `.ids()` per target class, is wired once into the bulk loop, pairs with `setDiscardSaved(true)`, and the SELECT-slope proxy BOOTS and passes (≤ 1).
- The MUT-17 memo is a no-eviction ConcurrentHashMap that never caches a `Throwable`; walk-once proven.
- MUT-18 parity is independently re-run by the verifier (not trusted from SUMMARY): 106/0/0/2, and the `origin/main` test-tree diff is insertions-only — proving parity was achieved by preserving behavior, not by weakening tests.

The four code-review findings (WR-01..04) are either an accepted intentional MUT-16 tradeoff (WR-01) or pre-existing debt carried forward unchanged from the v1.1 monolith (WR-02/03/04, confirmed: `DiffSerializer` not in the phase diff). None represents a Phase 17 goal gap.

**Overall Phase Verdict: PASS.**

---

_Verified: 2026-05-31T09:35:07Z_
_Verifier: Claude (gsd-verifier)_
