# Phase 18: AI-Runtime Performance Pass (targeted) — Specification

**Created:** 2026-06-08
**Ambiguity score:** 0.157 (gate: ≤ 0.20)
**Requirements:** 5 locked

## Goal

Eliminate per-turn recomputation in the already-shipped chat-turn / tool-call / RAG-retrieval / task-file paths through targeted memoization — per-turn (one `RunContext`) for anything user/role/exposure-sensitive, app-wide (event-evicted) for pure-metadata and exposure-derived caches — so that within a single turn each of `getReadableSchema()`, the exposure denylist, `LlmExposurePolicy` resolution, the RAG `Filter.Expression`, and task-file `Media` is computed once instead of once-per-call, with every optimization backed by a checkable proxy and all existing security/exposure/audit/tool/RAG suites passing unchanged.

## Background

Grounded in the current `com.vn.agent` add-on (gradle module `:ai-agent:ai-agent`):

- **`RunContext`** (`orchestration/RunContext.java`) is a `final` class of `ThreadLocal` slots, set by `AuditAdvisor` at chat-call start and cleared in a `finally`. It is the natural per-turn cache anchor (one turn = one user), but carries no computation cache today.
- **`getReadableSchema()`** (`orchestration/BaselineContextProvider.java` → `exposure/LlmExposurePolicy.java`) rebuilds the readable schema on every call: a per-user metamodel walk via `CurrentUserSchemaAccess` **plus** a fresh `hiddenEntityNames()` agentstore query.
- **`getDenylistedEntityNames()` / `hiddenEntityNames()`** (`exposure/LlmExposurePolicy.java`) runs `LlmExposureRuleRepository.findEnabledExcludedEntityNames()` — **one agentstore SELECT per call** — and is called from `getReadableSchema`, `canReadEntity`, `canCreate`, `canUpdate`, and `RetrievalFilterBuilder.buildFor`. Caching was explicitly deferred in v1.0/v1.1 (D-14). Plan 10-04 raised `ToolQueryCountBaselineTest`'s SELECT ceiling 0→5 to absorb this per-call lookup.
- **`LlmExposureChangedEvent`** (`exposure/LlmExposureChangedEvent.java`) is published from the single site `AiExposureRuleEntityListener` on any `AiExposureRule` change; it currently has **no consumer** — it was wired ahead for exactly this caching pass.
- **`RetrievalFilterBuilder.buildFor(Authentication)`** (`rag/RetrievalFilterBuilder.java`) fetches the denylist and rebuilds the `(source_entity IS NULL) OR (NOT IN <denied>)` + per-role `Filter.Expression` on each retrieval.
- **`AiTaskFileMediaResolver.resolveActive(convId)`** (`taskfile/AiTaskFileMediaResolver.java`) loads rows, reads `FileStorage` bytes for images and runs `TikaDocumentReader` for documents, and calls `AiUiSettingsResolver.resolveTaskFile*` (each loads the singleton fresh). Note: per Phase 13.1 it is already invoked **once per turn** — so PERF-04 is partly a re-encode guard, not a new cache.
- **Phase 17** already landed `MutationGateChain` + `MutationAttributeBinder.prefetchReferences` (one constrained `.ids()` per target class — MUT-16) and `RelatedWriteMetadataResolver` (a `ConcurrentHashMap` memo over the immutable metamodel — MUT-17).
- **Phase 16** already landed `AiSettingsChangedEvent` (`Kind {PARAMETERS, UI_SETTINGS}`) with two single publish sites (`AiParametersEntityListener`, `AiUiSettingsEntityListener`) — the settings-cache eviction hook for this phase.
- **Proxy harness** exists: `performance/QueryCountingDataSourceConfiguration.java` (datasource-proxy on the agentstore DS), `ToolQueryCountBaselineTest`, and `MutationFkBatchLoadQueryCountTest` (the `countLarge − countSmall ≤ 1` slope detector). Spring Cache starter is present (used by guards); no `@Cacheable` exists in functional code; no Caffeine.

The gap: nothing memoizes the per-turn schema/exposure resolution, the app-wide denylist, the RAG filter, or task-file encoding — these recompute per tool-call / per retrieval / per injection. This phase closes that gap **without** changing any security/exposure/opacity semantics.

## Requirements

1. **Per-turn schema/metadata/access memoization (PERF-01)**: Within one `RunContext`, schema and exposure resolution is computed once and shared across every tool call in the turn.
   - Current: `getReadableSchema()` rebuilds per call; `LlmExposurePolicy.canReadEntity/canCreate/canUpdate` each construct a fresh `CrudEntityContext` + run `accessManager.applyRegisteredConstraints` and a denylist query per call — recomputed for every tool call in a multi-call turn.
   - Target: `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions / `LlmExposurePolicy` resolution are memoized for the duration of the turn, anchored to `RunContext` and cleared in the turn-end `finally`; nothing user/role/exposure-sensitive is reused across turns or users.
   - Acceptance: a call-count or SELECT-count proxy shows N tool calls in one turn trigger schema/exposure resolution once (call-count == 1 per turn, or SELECT slope ≤ baseline); a test asserts the per-turn cache is empty after `RunContext.clear()` (no cross-turn / cross-user bleed).

2. **App-wide denylist + pure-metadata memoization with event eviction (PERF-02)**: The exposure denylist and entity-name→`MetaClass` derivations are memoized longer-lived, evicted by the right event.
   - Current: `getDenylistedEntityNames()` issues one agentstore SELECT per call from five call sites; no cache (D-14). Entity-name→`MetaClass` already resolves off the immutable Jmix metamodel.
   - Target: `getDenylistedEntityNames()` is memoized app-wide and evicted on `LlmExposureChangedEvent`; pure-metadata derivations are memoized with no eviction (immutable metamodel); `AccessManager` remains authoritative for actual data access.
   - Acceptance: a call-count/SELECT-count proxy shows `findEnabledExcludedEntityNames()` runs once and is reused until an `LlmExposureChangedEvent` fires (then refetches once); a source-level/test invariant confirms every exposure-derived cache subscribes to `LlmExposureChangedEvent`; `ToolQueryCountBaselineTest`'s SELECT ceiling is **lowered** to reflect the eliminated per-call denylist lookup, with its security/opacity assertions unchanged byte-for-byte.

3. **RAG `Filter.Expression` built once per retrieval (PERF-03)**: The role/exposure scoping filter is constructed once per retrieval, with clauses preserved verbatim.
   - Current: `RetrievalFilterBuilder.buildFor(Authentication)` fetches the denylist and rebuilds the full `Filter.Expression` on each call.
   - Target: the `Filter.Expression` is built once per retrieval (not rebuilt repeatedly); the `(source_entity IS NULL) OR (NOT IN <denied>)` / role clauses are preserved verbatim — no "redundant clause" removal; the denylist portion reuses the PERF-02 cache; role extraction stays request-fresh.
   - Acceptance: the existing `RetrievalFilterBuilderDenylistTest` passes unchanged; a proxy confirms the filter build / denylist lookup is invoked once per retrieval (call-count == 1); the Phase 10 TEST-09 RAG leg passes unchanged.

4. **Task-file `Media` memoized per `(conversationId, taskFileId)` per turn; no in-turn re-serialization (PERF-04)**: Repeated injections within a turn do not re-encode the same file.
   - Current: `AiTaskFileMediaResolver.resolveActive(convId)` re-reads `FileStorage` (images) and re-runs Tika (documents) on each invocation, and `AiUiSettingsResolver.resolveTaskFile*` each reload the singleton; `resolveActive` is already called once per turn today.
   - Target: encoding/resolution of `Media` is memoized per `(conversationId, taskFileId)` for the turn (evicted on attachment add/delete/TTL) so repeated injections don't re-encode; prompt/context is not re-serialized within a turn; FK batch-loading (shared with MUT-16) remains in effect. Per the locked scope discipline, where a proxy shows a step already runs once per turn, lock that with a regression assertion instead of adding a redundant cache.
   - Acceptance: a proxy shows per-file encode (`FileStorage` read / Tika parse) and the settings-resolver singleton load happen at most once per `(convId, taskFileId)` per turn; where already once-per-turn, a call-count regression test locks the good behavior; existing task-file tests (`PerTurnMediaInjectionTest`, budget/TTL tests) pass unchanged.

5. **Proxies, no benchmark/admin-perf scope, suites unchanged, settings edit visible within one turn (PERF-05)**: Every optimization is provable and nothing outside AI-runtime perf is introduced.
   - Current: only the existing query-count harness exists; `AiUiSettingsResolver` / `AiParametersResolver` read fresh; no Caffeine/JMH/Gatling.
   - Target: no benchmark harness, no admin-screen perf work, no Caffeine or new cache dependency (`ConcurrentMapCacheManager` / `ConcurrentHashMap` only); each optimization ships a checkable proxy (datasource-proxy SELECT-count, `countLarge − countSmall ≤ 1` slope, or call-count); all existing security/exposure/audit/tool/RAG suites pass unchanged; any settings cache evicts on the Phase 16 `AiSettingsChangedEvent` so an admin edit is visible on the next turn.
   - Acceptance: the build adds no JMH/Gatling/Caffeine dependency; each of PERF-01..04 ships ≥1 proxy test; the full existing suite passes with zero test-body edits **except** the deliberately-recalibrated `ToolQueryCountBaselineTest` ceiling; a call-count/integration test shows a change published via `AiSettingsChangedEvent` is reflected on the next turn.

## Boundaries

**In scope:**
- Per-turn memoization (anchored to `RunContext`, cleared in the turn-end `finally`) of `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions / `LlmExposurePolicy` resolution.
- App-wide `getDenylistedEntityNames()` cache evicted on `LlmExposureChangedEvent`, plus entity-name→`MetaClass` memoization (no eviction, immutable metamodel).
- Building the RAG `Filter.Expression` once per retrieval, reusing the cached denylist; clauses preserved verbatim.
- Memoizing task-file `Media` encode/resolve and the `AiUiSettingsResolver` singleton read per turn, evicted on attachment add/delete/TTL and on `AiSettingsChangedEvent`.
- One checkable proxy test per optimization; lowering the `ToolQueryCountBaselineTest` SELECT ceiling as the PERF-02 proxy.
- **Proxy-driven scope discipline** (locked decision): if a proxy shows a listed hotspot is already optimal (e.g. `resolveActive` already runs once per turn; `RelatedWriteMetadataResolver` already memoized), drop the redundant cache and lock the good state with a regression assertion — each PERF-ID is still covered, by the proxy that fits.

**Out of scope:**
- Benchmark harness (JMH / Gatling) — hotspots are bounded and JVM-lifetime-stable; the existing `ConcurrentMapCacheManager` + datasource-proxy assertions suffice.
- Caffeine or any new cache dependency — `ConcurrentMapCacheManager` / `ConcurrentHashMap` only.
- Admin-screen performance work (`CFG-FUT-01`) — this pass is AI-runtime only.
- Any change to security / exposure / opacity semantics — `AccessManager` stays authoritative; RAG clauses preserved verbatim; no `unknown_entity` opacity change.
- New components or new public API — touches only already-shipped classes.
- Editing existing test bodies — **except** the single deliberate `ToolQueryCountBaselineTest` ceiling recalibration.
- Cross-turn or cross-user caching of anything user/role/exposure-sensitive — strictly forbidden.

## Constraints

- Per-turn caches are scoped to one `RunContext` (one user, one turn) and cleared in the turn-end `finally`; user/role/exposure-sensitive data is never reused across turns or users.
- Every cache wires to an invalidation hook: `LlmExposureChangedEvent` for exposure-derived caches; the Phase 16 `AiSettingsChangedEvent` (`PARAMETERS` / `UI_SETTINGS`) for settings caches; immutable-metamodel caches need none.
- Any (re)introduced FK batch-load stays a **constrained** `DataManager.load(...).ids(...)` — never `UnconstrainedDataManager`, never raw JPQL — so row-level security still applies.
- No `@Transactional` changes; no change to gating order, audit rows, or opacity behavior.
- Proxies use the test-scoped `net.ttddyy:datasource-proxy` harness (`QueryCountingDataSourceConfiguration`) on the agentstore DataSource, or a call-count assertion.
- Depends on Phase 17 (`MutationGateChain` / `MutationAttributeBinder` FK batch — complete) and Phase 16 (`AiSettingsChangedEvent` — complete). Both are shipped.
- Caution (planner note, treat as assumption): `RunContext` is `ThreadLocal`; the streaming (reactor) transport may execute on threads where the `ThreadLocal` is not propagated. Per-turn memoization must be correct (a cache miss / recompute) — never incorrect (stale cross-turn reuse) — if the `RunContext` is absent on a worker thread.

## Acceptance Criteria

- [ ] Within one `RunContext`, `getReadableSchema()` / `LlmExposurePolicy` resolution is computed once across all tool calls in the turn (call-count or SELECT-count proxy).
- [ ] A test asserts per-turn caches are empty after `RunContext.clear()` — no cross-turn / cross-user bleed.
- [ ] `getDenylistedEntityNames()` is memoized app-wide; a proxy shows one fetch reused until `LlmExposureChangedEvent`, then exactly one refetch.
- [ ] A source-level/test invariant asserts every exposure-derived cache subscribes to `LlmExposureChangedEvent`.
- [ ] `ToolQueryCountBaselineTest`'s SELECT ceiling is lowered to reflect the eliminated per-call denylist lookup; its security/opacity assertions are unchanged byte-for-byte.
- [ ] `RetrievalFilterBuilder` builds the `Filter.Expression` once per retrieval; clauses preserved verbatim; `RetrievalFilterBuilderDenylistTest` and the TEST-09 RAG leg pass unchanged.
- [ ] Task-file `Media` is encoded/resolved at most once per `(conversationId, taskFileId)` per turn (proxy); where already once-per-turn, a regression assertion locks it.
- [ ] A test shows a settings change published via `AiSettingsChangedEvent` is reflected on the next turn (settings cache eviction within one turn).
- [ ] No JMH / Gatling / Caffeine or other new perf/cache dependency is added (build check).
- [ ] Each of PERF-01..04 ships ≥1 checkable proxy (SELECT-count, `countLarge − countSmall ≤ 1` slope, or call-count).
- [ ] The full existing security / exposure / audit / tool / RAG suites pass with zero test-body edits except the recalibrated `ToolQueryCountBaselineTest` ceiling.

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                        |
|--------------------|-------|------|--------|-------------------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | 5 named hotspots; per-turn vs app-wide split explicit        |
| Boundary Clarity   | 0.86  | 0.70 | ✓      | Proxy-driven scope discipline locked; explicit out-of-scope  |
| Constraint Clarity | 0.76  | 0.65 | ✓      | Eviction events, constrained-load rule, baseline recalibration |
| Acceptance Criteria| 0.84  | 0.70 | ✓      | Per-item checkable proxies; suites-unchanged gate            |
| **Ambiguity**      | 0.157 | ≤0.20| ✓      |                                                             |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective       | Question summary                                              | Decision locked                                                                 |
|-------|-------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------|
| 1     | Boundary Keeper   | What to do when a proxy shows a listed hotspot is already optimal? | Proxy-driven: drop the redundant cache, lock the good state with a regression test ("best practice là được" — user delegated). |
| 1     | Failure Analyst   | Make lowering the `ToolQueryCountBaselineTest` SELECT ceiling an acceptance criterion? | Yes — lower the ceiling as the primary PERF-02 proxy; security/opacity assertions unchanged byte-for-byte. |

---

*Phase: 18-ai-runtime-performance-pass-targeted*
*Spec created: 2026-06-08*
*Next step: /gsd-discuss-phase 18 — implementation decisions (per-turn cache mechanism, ThreadLocal-vs-RunContext anchoring under streaming, proxy test placement)*
