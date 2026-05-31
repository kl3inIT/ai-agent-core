---
phase: 17-mutation-internals-hardening-phase-11-follow-up
plan: 03
subsystem: mutation-attribute-binding
tags: [mutation, fk-batch-load, n+1, constrained-datamanager, two-pass, MUT-16]
requires:
  - "Plan 17-01 MUT-16 SELECT-count slope proxy + forbidden-token scan + agentstore FK fixture pair"
  - "MutationAuthorizationService (enforceLlmRelationshipTargetExposure + enforceReadPermission per target class)"
  - "MutationErrorTranslator.notFound (absent/row-filtered id)"
  - "ToolEntityResolver.parseEntityId + constrained DataManager .ids(Collection) overload (jmix-core 2.8.1)"
provides:
  - "MutationAttributeBinder.prefetchReferences(MetaClass, List<Map>) -> Map<MetaClass, Map<UUID, Object>> (one constrained .ids() load per target class)"
  - "MutationAttributeBinder.coerceAttributes(MetaClass, Map, Map<MetaClass, Map<UUID, Object>>) prefetched-bind overload"
  - "Single-call to-one FK dedup for create/update (single-arg coerceAttributes delegates through the same path)"
affects:
  - "Plan 17-04 (wire bulk caller to ONE cross-row prefetch + per-row prefetched-coerce — chain extraction)"
  - "Phase 18 perf pass (the shared batch-FK load is now the optimization target, not the per-row N+1)"
tech-stack:
  added: []
  patterns:
    - "Two-pass FK prefetch on the existing binder bean (no new collaborator — D-06)"
    - "Constrained DataManager .ids(Collection).list() batch load — one SELECT per distinct target class"
    - "LinkedHashSet per-target id collection (dedup + submission-order)"
    - "Prefetched-bind pass throws notFound at the bind site for failedRowIndex re-attribution (Pitfall 4)"
key-files:
  created: []
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java"
decisions:
  - "Single-arg coerceAttributes delegates through prefetchReferences(metaClass, List.of(attributes)) + the 3-arg overload so create/update get single-call dedup with NO separate to-one branch (D-07)."
  - "coerceAttributeValue (no external caller) keeps its public signature but routes its to-one branch through a one-element prefetchReferences + bindPrefetchedReference, and its scalar path through a shared coerceScalarValue helper — single coercion owner."
  - "Javadoc must NOT spell the literal token UnconstrainedDataManager: the MUT-16 forbidden-token scan greps the whole source file (comments included), so the security note says 'the unconstrained variant' instead."
metrics:
  duration: "~25 min"
  completed: "2026-05-31"
  tasks: 1
  files: 1
---

# Phase 17 Plan 03: Batch-Load To-One FK References (MUT-16) Summary

Killed the bulk to-one FK N+1 in `MutationAttributeBinder` with a two-pass design on the existing bean: a `prefetchReferences` pass batch-loads all distinct to-one FK ids per target class with ONE constrained `dataManager.load(class).ids(idSet).list()` per class, and a `coerceAttributes(prefetched)` overload binds the loaded entity instances — preserving byte-identical error classification, `failedRowIndex`, and full-batch rollback.

## What Was Built

- **`prefetchReferences(MetaClass ownerMetaClass, List<Map<String,Object>> rows)`** (pass 1): iterates ALL rows, collects every writable to-one FK id per target `MetaClass` into a per-class `LinkedHashSet<UUID>` (dedup + submission-order; null FK = clear, skipped). Then for EACH distinct target class exactly once — same order as the per-reference baseline — calls `enforceLlmRelationshipTargetExposure(targetMetaClass, false)` then `enforceReadPermission(targetMetaClass)` (the ONLY `access_denied` source), then issues ONE constrained `dataManager.load(targetMetaClass.getJavaClass()).ids(idSet).list()` and keys the result by `EntityValues.getId`. Returns `Map<MetaClass, Map<UUID, Object>>`. Constrained `DataManager` only — no unconstrained variant, no raw JPQL (D-09, T-17-02).
- **`coerceAttributes(MetaClass, Map, Map<MetaClass, Map<UUID,Object>>)`** (pass 2): identical to the single-arg overload except the to-one branch binds from `prefetched` via `bindPrefetchedReference` — re-parse the id, look it up; absent id (genuinely missing OR row-level-security-filtered) throws `mutationErrorTranslator.notFound(targetMetaClass, rawValue)` at the bind site so the bulk caller's row-order loop attributes the correct `failedRowIndex` (Pitfall 4); present id binds the LOADED ENTITY INSTANCE, not the id (D-09, Pitfall 6). No read-permission/load re-check here.
- **Single-arg `coerceAttributes(metaClass, attributes)`** now delegates through `prefetchReferences(metaClass, List.of(attributes))` + the 3-arg overload — create/update get the same single-call dedup with NO separate to-one branch (D-07).
- **`coerceAttributeValue`** (no external caller, but public signature preserved): its to-one branch routes through a one-element `prefetchReferences` + `bindPrefetchedReference`; its scalar path is factored into a shared `coerceScalarValue` helper. Plus `isToOneRelationship` to centralize the class-range-not-many detection.
- `add_related_record`/`remove_related_record` untouched (they bypass `coerceAttributeValue` — Pitfall 7). `applyAttributes`, `capturePreImage`, `validateWritableProperty`, `requireUuidId`, `AUDIT_SYSTEM_FIELD_NAMES` unchanged.

## Verification Results

| Test | Result | Notes |
|------|--------|-------|
| `MutationToolInvariantsTest` | 5 green / 3 RED | **`mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql` GREEN** (MUT-16 forbidden-token scan satisfied by the implementation). The 3 RED are the `mutationGateChain_*` (MUT-15) assertions naming Plan 04 — out of this plan's scope, intended RED. |
| `BuiltInMutationToolsBulkSavePartialFailureTest` | 4 green / 0 fail | MUT-18 parity: `failedRowIndex` + full-batch rollback byte-identical, ZERO test-body edits. |
| `MutationFkBatchLoadQueryCountTest` | blocked at `seedParent()` | Pre-existing agentstore-fixture `@SpringBootTest` boot regression (documented below). Binder source verifiably issues one `.ids()` per class. |
| `compileTestJava` (module-wide) | BUILD SUCCESSFUL | No compile regression from the binder rewrite. |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Javadoc spelled the forbidden token `UnconstrainedDataManager`, tripping the MUT-16 source scan**
- **Found during:** Task 1 (first test run)
- **Issue:** The `prefetchReferences` security Javadoc originally used `{@link io.jmix.core.UnconstrainedDataManager}`. The MUT-16 forbidden-token scan (`mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql`) greps the WHOLE source file — comments included — with `doesNotContain("UnconstrainedDataManager")`, so the Javadoc link failed an otherwise-correct implementation.
- **Fix:** Rephrased the security note to "the unconstrained variant" — the code path already uses only the constrained `dataManager`; the rename is documentation-only and behavior-identical. Scan now GREEN.
- **Files modified:** `MutationAttributeBinder.java` (Javadoc only)
- **Commit:** 1103634 (folded into the task commit)

### Known boot regression (acceptance-sanctioned)

`MutationFkBatchLoadQueryCountTest` does NOT exit 0. It fails in its `@BeforeEach`/setup `seedParent()` helper — BEFORE the binder is ever invoked — with:

```
java.lang.IllegalArgumentException: Object: ...MutationStoreLinkedParentFixture-<uuid> [new] is not a known Entity type.
  at org.eclipse.persistence...registerNewObjectForPersist
  at ...UnconstrainedDataManagerImpl.save
  at MutationFkBatchLoadQueryCountTest.lambda$seedParent$0(MutationFkBatchLoadQueryCountTest.java:114)
```

This is the agentstore `@Store` FK fixture (`MutationStoreLinkedParentFixture`) not being registered in the EclipseLink agentstore persistence unit under this `@SpringBootTest` boot — the same family of pre-existing module-level `@SpringBootTest` / agentstore-EntityManagerFactory boot regressions documented since Phase 11/13 (Phase 13.1 Plan 06 `deferred-items.md`). It reproduces in isolation (running ONLY this test) and originates entirely in the test's own seed helper using `UnconstrainedDataManager.save(fixture)` — the production `MutationAttributeBinder` change cannot reach this code path (the failure precedes any `bulk_save_records` invocation).

Per the plan's acceptance criterion — *"`*MutationFkBatchLoadQueryCountTest` exits 0 OR documents the known module @SpringBootTest boot regression exactly as Phase 13.1 Plan 06 did, with the binder source verifiably issuing one `.ids()` per class"* — the binder source is verifiably correct: `prefetchReferences` issues exactly one `dataManager.load(targetMetaClass.getJavaClass()).ids(idSet).list()` per distinct target class after the once-per-class gate pair (`MutationAttributeBinder.java` lines ~161-166), and the MUT-16 forbidden-token scan (a real test, GREEN) proves the path uses the constrained DataManager only. The slope assertion stays blocked by the test-harness fixture-registration regression, not by the implementation.

## Threat Model Compliance

- **T-17-02 (mitigate):** The batch path uses `dataManager.load(targetClass).ids(...)` on the CONSTRAINED DataManager only — never the unconstrained variant, never raw JPQL — so row-level security still filters the result. The `mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql` source-scan is GREEN and fails the build on any regression. A row-level-filtered id is absent from the result and collapses to `not_found`, never `access_denied`.
- **T-17-05 (mitigate):** `access_denied` is emitted ONLY from `enforceReadPermission(targetMetaClass)` once per target class; an absent/empty load yields `not_found` at the correct `failedRowIndex` (rows iterated in submission order) with full-batch rollback. Locked GREEN by the unchanged `BulkSavePartialFailureTest` (4/4).
- **T-17-06 (mitigate):** `bindPrefetchedReference` binds the LOADED ENTITY INSTANCE, not the id — `MutationGuard` SPI receives typed refs (Phase 11 D-03 contract).
- **T-17-SC (accept):** No package installs; `.ids(Collection)` is existing Jmix 2.8.1 API. No legitimacy checkpoint required.

## Known Stubs

None. The only non-GREEN tests are: (a) the 3 MUT-15 `mutationGateChain_*` assertions that intentionally name Plan 04, and (b) the `MutationFkBatchLoadQueryCountTest` slope assertion blocked by the pre-existing agentstore-fixture boot regression (test-harness, not implementation).

## No new threat surface

This plan modifies one existing production file on the FK-binding path. No new network endpoints, auth paths, file access, or schema changes at trust boundaries. The new surface (a batch `.ids()` IN-load) stays on the constrained DataManager already in the threat register (T-17-02).

## Self-Check: PASSED

- `MutationAttributeBinder.java` exists and contains `prefetchReferences`, the 3-arg `coerceAttributes` overload, `.ids(` (line ~164), and `mutationErrorTranslator.notFound` (line ~367).
- Task commit 1103634 present in git history.
