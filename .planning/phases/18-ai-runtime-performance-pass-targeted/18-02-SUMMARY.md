---
phase: 18-ai-runtime-performance-pass-targeted
plan: 02
subsystem: orchestration + exposure
tags: [perf, per-turn-cache, threadlocal, llm-exposure, security]
requires:
  - "18-01: app-wide denylistCache on LlmExposurePolicy (reused by the CRUD-verdict read-through)"
provides:
  - "RunContext.PER_TURN_CACHE — ONE active-turn-gated ThreadLocal<Map<Object,Object>> per-turn cache slot, wiped in clear()"
  - "RunContext.perTurnCache() / perTurnMemoize(key, supplier) — active-turn-gated safe-miss accessors (recompute-without-store off-turn)"
  - "RunContext.perTurnCacheSnapshotForTest() — non-initializing raw-slot inspection seam"
  - "LlmExposurePolicy: canReadEntity/canCreate/canUpdate/getReadableSchema resolve once per turn via perTurnMemoize; schema returned deeply-immutable"
affects:
  - "LlmExposurePolicy hot read/verdict paths (~15 canReadEntity call sites + CRUD gates) now resolve once per RunContext"
tech-stack:
  added: []
  patterns:
    - "Active-turn-gated ThreadLocal memo (allocate-only-within-a-turn; recompute-without-store off-turn) as the D-02 security safe-miss"
    - "Locale-invariant verdict key record CrudVerdictKey(metaClassName, operation)"
    - "Deep-immutable defensive copy (Map.copyOf semantics + Set.copyOf on every inner attribute set)"
    - "Source-scan boundary invariant (D-09) confining a symbol to an allow-list of files"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePerTurnMemoTest.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/PerTurnCacheBoundaryInvariantTest.java"
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java"
decisions:
  - "Used the existing CURRENT runId ThreadLocal as the active-turn signal (get()==null ⇒ no active turn / post-clear) — no new lifecycle plumbing."
  - "perTurnMemoize recomputes-without-storing off-turn rather than returning the shared empty map to a computeIfAbsent caller — keeps the safe-miss boundary inside RunContext."
  - "Schema cached value is deeply immutable: outer unmodifiableMap PLUS Set.copyOf on every inner attribute set (review cycle-2 MEDIUM) so cached attribute sets cannot be mutated."
  - "CRUD/read verdict keys are locale-invariant (D-04); locale-bearing agent.entities label rendering stays in BaselineContextProvider and is NOT cached here."
metrics:
  duration: "~30m"
  tasks: 2
  files_created: 2
  files_modified: 2
  completed: "2026-06-09"
---

# Phase 18 Plan 02: Per-Turn Memoization Anchor (PERF-01) Summary

Per-turn memoization of `LlmExposurePolicy`'s `canReadEntity` / `canCreate` / `canUpdate` verdicts and `getReadableSchema()` via ONE active-turn-gated `ThreadLocal<Map<Object,Object>>` slot on `RunContext`, wiped in `clear()` — N tool calls in one turn resolve each verdict/schema exactly once, with a security safe-miss (recompute-without-store) on any off-turn / foreign streaming worker thread and a deeply-immutable cached schema view.

## What Was Built

### Task 1 — `RunContext` per-turn cache slot (commit `9c55e19`)
- Added ONE `private static final ThreadLocal<Map<Object,Object>> PER_TURN_CACHE` slot alongside the existing 12.
- `perTurnCache()` is **active-turn-gated**: when `CURRENT.get() == null` (no active turn — foreign streaming worker thread or post-`clear()`) it returns `Collections.emptyMap()` WITHOUT allocating or storing; within an active turn it lazy-inits and stores a `HashMap`. Allocation happens only within a turn (D-02 — nothing left on a pooled worker thread).
- `perTurnMemoize(key, supplier)` recomputes-without-storing when off-turn (the D-02 safe miss — never stale reuse, never a map left behind), else `computeIfAbsent`.
- `perTurnCacheSnapshotForTest()` returns the RAW slot (may be `null`) without lazy-init — makes the "empty after clear()" proof meaningful (review HIGH #3).
- `PER_TURN_CACHE.remove()` is the last line of `clear()` — the non-negotiable wipe.
- No `ConcurrentHashMap<runId,…>`, no reactor-`Context`/Micrometer propagation (D-02). Class Javadoc updated to document the active-turn-gated contract and the cross-turn/cross-user wipe requirement.

### Task 2 — `LlmExposurePolicy` read-through + tests (commit `1d32787`)
- `canReadEntity`/`canCreate`/`canUpdate` route through `RunContext.perTurnMemoize(new CrudVerdictKey(mc.getName(), op), …)` with distinct `"read"`/`"create"`/`"update"` operations — locale-invariant keys (D-04). `canReadEntity` included per review HIGH #4 (~15 call sites, the hottest read gate).
- `getReadableSchema()` memoized under a `READABLE_SCHEMA_KEY` sentinel; the value is a **deeply-immutable** view — `Collections.unmodifiableMap` over a copy PLUS `Set.copyOf` on every inner attribute set (review cycle-2 MEDIUM) so no caller can mutate cached attribute sets.
- `canModify` still delegates to `canUpdate`; `canReadAttribute` stays a pass-through (not cached).
- `LlmExposurePerTurnMemoTest` (`@Tag("unit")`, pure JUnit + Mockito, no `@SpringBootTest`): proves resolve-once-per-turn (`canReadEntity`/`getReadableSchema` `times(1)`; `applyRegisteredConstraints` `times(2)` = once per operation across create+update), the meaningful empty-after-`clear()` via the non-init seam (`isNull()`), second-turn recompute (`times(2)`), and the off-turn safe miss (slot stays `null`).
- `PerTurnCacheBoundaryInvariantTest` (`@Tag("unit")`): source-scans `src/main/java` and asserts the cache symbol (`PER_TURN_CACHE`/`perTurnCache`/`perTurnMemoize`/`perTurnCacheSnapshotForTest`) appears ONLY in `RunContext.java` + `LlmExposurePolicy.java`, never in `BuiltInDataTools.java` (D-09 / T-18-06).

## Verification (actual results)

- `cd ai-agent && ./gradlew :ai-agent:compileJava` — **BUILD SUCCESSFUL** (after each task).
- `cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.exposure.LlmExposurePerTurnMemoTest" --tests "com.vn.agent.exposure.PerTurnCacheBoundaryInvariantTest"` — **BUILD SUCCESSFUL**; JUnit XML: `LlmExposurePerTurnMemoTest` tests=2 failures=0, `PerTurnCacheBoundaryInvariantTest` tests=2 failures=0 (4 tests, 0 failures).
- Regression: `./gradlew :ai-agent:test --tests "com.vn.agent.exposure.LlmExposurePolicyTest"` — **BUILD SUCCESSFUL** (the deeply-immutable `getReadableSchema()` change is backward-compatible; existing assertions unchanged).

## Deviations from Plan

None — plan executed exactly as written. The plan offered an "equivalent acceptable shape" (raw `perTurnCache()` + policy-side null/empty guard); the preferred `perTurnMemoize` helper shape was implemented, keeping the safe-miss boundary inside `RunContext`.

## Threat Surface Notes

No new network endpoints, auth paths, or schema changes. The per-turn cache holds user/role/exposure-sensitive verdicts; T-18-04 (cross-turn/cross-user reuse) is mitigated by the `clear()` wipe + the active-turn-gated allocation, T-18-05 (foreign-thread / left-behind map) by the recompute-without-store safe miss, and T-18-06 (row-data path leak) by the `PerTurnCacheBoundaryInvariantTest` source scan. All proven green.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` (modified)
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` (modified)
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePerTurnMemoTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/PerTurnCacheBoundaryInvariantTest.java`
- FOUND commit: `9c55e19` (Task 1)
- FOUND commit: `1d32787` (Task 2)
