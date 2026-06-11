# Phase 18: AI-Runtime Performance Pass (targeted) - Context

**Gathered:** 2026-06-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Targeted memoization of already-shipped per-turn hotspots — schema/exposure resolution, the exposure denylist, the RAG `Filter.Expression`, and task-file `Media` encoding — split into per-turn caches (one `RunContext`, user/role/exposure-sensitive) and app-wide caches (pure-metadata + exposure-derived, event-evicted), each backed by a checkable proxy. No benchmark harness, no admin-screen perf, no new runtime dependency. Touches only existing components in `com.vn.agent`.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**5 requirements are locked.** See `18-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `18-SPEC.md` before planning or implementing. Requirements (PERF-01..05) are not duplicated here.

**In scope (from SPEC.md):**
- Per-turn memoization (anchored to `RunContext`, cleared in the turn-end `finally`) of `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions / `LlmExposurePolicy` resolution.
- App-wide `getDenylistedEntityNames()` cache evicted on `LlmExposureChangedEvent`, plus entity-name→`MetaClass` memoization (no eviction, immutable metamodel).
- Building the RAG `Filter.Expression` once per retrieval, reusing the cached denylist; clauses preserved verbatim.
- Memoizing task-file `Media` encode/resolve and the `AiUiSettingsResolver` singleton read per turn, evicted on attachment add/delete/TTL and on `AiSettingsChangedEvent`.
- One checkable proxy test per optimization; lowering the `ToolQueryCountBaselineTest` SELECT ceiling as the PERF-02 proxy.
- Proxy-driven scope discipline: if a proxy shows a listed hotspot is already optimal, drop the redundant cache and lock the good state with a regression assertion.

**Out of scope (from SPEC.md):**
- Benchmark harness (JMH / Gatling); Caffeine or any new cache dependency (`ConcurrentMapCacheManager` / `ConcurrentHashMap` only); admin-screen perf work; any change to security/exposure/opacity semantics; new components or new public API; editing existing test bodies except the deliberate `ToolQueryCountBaselineTest` ceiling recalibration; cross-turn or cross-user caching of anything user/role/exposure-sensitive.

</spec_lock>

<decisions>
## Implementation Decisions

All four decisions were taken in advisor mode (full_maturity tier) with parallel research grounded in the live code. Recommended option selected in each case.

### Per-turn cache anchor (PERF-01)
- **D-01:** Anchor the per-turn cache by **extending `RunContext` with a `ThreadLocal<Map<CacheKey,Object>>` slot, wiped inside the existing `RunContext.clear()`** (called in `AuditAdvisor`'s `finally`). Read-through from `BaselineContextProvider` / `LlmExposurePolicy`.
- **D-02:** **Correctness rule (non-negotiable):** under the streaming/reactor transport the cache must degrade to **miss → recompute**, never serve stale cross-turn/cross-user data. A `ThreadLocal.get()` returning `null` on a foreign worker thread IS the safe miss. Do NOT switch to a process-wide `ConcurrentHashMap<runId,…>` (a skipped `finally` would risk the forbidden stale reuse) and do NOT add reactor-Context/Micrometer propagation of the sensitive map (optimizes for hits at the cost of the required correctness).
- **D-03:** Evidence this is safe (verified, not assumed): `guard/GuardedToolCallingManager.executeToolCalls` already reads `RunContext` ThreadLocals (`getRootAuditId()`, conversation id, `IterationCounter`) at tool-execution time, and the audit/guard suites pass under streaming — so the memo IS visible at the per-tool-call read sites in the common case. Spring AI 1.1.x runs the default blocking-tool execution synchronously on the subscription thread chain; the streaming pipeline uses `subscribeOn` with no mid-flight `publishOn`.
- **D-04:** Cache key for the per-turn schema slot follows `BaselineContextProvider`'s existing P-8 cache-key invariant (locale handling preserved per the Plan 09-03 decision: `agent.permissions` is locale-invariant and cacheable as-is; `agent.entities` carries locale labels and must include locale in its key).

### App-wide cache mechanism (PERF-02)
- **D-05:** Denylist cache = **`ConcurrentHashMap` (single sentinel key) + `computeIfAbsent`, cleared via `@EventListener(LlmExposureChangedEvent)` `.clear()` inside `LlmExposurePolicy`** — the exact eviction twin of the shipped Phase 17 `RelatedWriteMetadataResolver` memo. No proxy boundary, so every internal call site hits the cache; greppable; source-scan/call-count testable. (`AtomicReference` snapshot is functionally equivalent and acceptable if a single-value form is preferred, but `ConcurrentHashMap` wins on precedent symmetry.)
- **D-06:** Entity-name→`MetaClass` derivation (immutable, security-independent) = **`ConcurrentHashMap` + `computeIfAbsent`, no eviction**, mirroring `RelatedWriteMetadataResolver`. Never `@Cacheable`.
- **D-07:** **Pitfall — do NOT use Spring `@Cacheable`/`@CacheEvict` in-place.** `hiddenEntityNames()` is a **private method self-invoked from 5 sibling methods** (`getReadableSchema`, `canReadEntity`, `canCreate`, `canUpdate`, line ~118) and the public gates self-invoke (`canModify`→`canUpdate`). Spring 6 proxy caching intercepts only external calls, so `@Cacheable` would silently cache nothing on 4 of 5 hot paths — the lowered `ToolQueryCountBaselineTest` ceiling would be unachievable while the cache *looks* wired. Making `@Cacheable` work would require extracting a new proxied bean, which violates the SPEC "no new components" boundary.

### AccessManager / exposure memo safety boundary (PERF-01)
- **D-08:** Cache scope = **readable-schema `Map<MetaClass,Set<String>>` AND per-`MetaClass` CRUD verdicts (`canReadEntity`/`canCreate`/`canUpdate`)** within the one `RunContext`. Within a turn the authenticated user, role set, and registered constraints are immutable, so a memoized verdict can never go over-permissive mid-turn; the only verdict-changing event (an admin denylist edit) is an *across-turn* `LlmExposureChangedEvent` by design.
- **D-09:** **Security boundary is test-enforced, not just argued.** Ship a pure-JUnit invariant test (house style, e.g. `AiSettingsChangedEventListenerInvariantTest`) asserting: (a) the per-turn cache symbol appears ONLY in `LlmExposurePolicy` + `RunContext`, never in the row-data path classes (`BuiltInDataTools` → constrained `DataManager`); (b) the per-turn slot is empty after `RunContext.clear()` (no cross-turn/cross-user bleed); (c) N tool calls in one `RunContext` drive schema/verdict resolution exactly once. `AccessManager`/`DataManager` stay authoritative for actual row-level data access — the cache memoizes only the LLM-facing schema/verdict surface.

### Proxy-test strategy (PERF-05)
- **D-10:** Use a **mixed, per-requirement proxy mapping** (not one uniform style):
  - **PERF-01** → **call-count** (Mockito): drive N tool calls in one `RunContext`, `verify` the memoized resolver/`hiddenEntityNames` delegate fires once, plus the mandatory `assertThat(cache).isEmpty()` after `RunContext.clear()`. (SELECT-count can't see ThreadLocal reuse and is polluted by the ~600 Jmix per-attribute permission fan-out.)
  - **PERF-02** → **SELECT-count primary** (locked): lower `ToolQueryCountBaselineTest`'s `METAMODEL_TOOL_POLICY_LOOKUP_CEILING` (~line 151, currently `5L`) to reflect the eliminated per-call agentstore SELECT, security/opacity assertions byte-for-byte unchanged; **plus** a call-count "one fetch reused until `LlmExposureChangedEvent`, then exactly one refetch" (spy `LlmExposureRuleRepository.findEnabledExcludedEntityNames`); **plus** a reflection invariant "every exposure-derived cache subscribes to `LlmExposureChangedEvent`".
  - **PERF-03** → **call-count** on `RetrievalFilterBuilder.buildFor` (`times(1)` per retrieval) + assert existing `RetrievalFilterBuilderDenylistTest` unchanged (verbatim-clause guard).
  - **PERF-04** → **call-count** on `FileStorage` read + Tika parse + `AiUiSettingsResolver.resolveTaskFile*` (≤ once per `(convId,taskFileId)` per turn); where `resolveActive` already runs once/turn, lock with a regression assertion rather than adding a redundant cache; keep `PerTurnMediaInjectionTest` + budget/TTL tests unchanged.
- **D-11:** **Crux finding (verified empirically):** the `datasource-proxy` SELECT-count harness (`QueryCountingDataSourceConfiguration` wrapping `agentstoreDataSource` in-place) **boots and passes today** — `MutationFkBatchLoadQueryCountTest` ran a full Jmix context with the mutation-role config and went green; even a "boot-regression-affected" test passed in isolation. The documented boot regression is a **full-suite forked-context-pressure / ordering** phenomenon (`forkEvery=20`, `cache.maxSize=8`, OOM-recycle), NOT a recipe-level failure of `@SpringBootTest(AITestConfiguration)`. SELECT-count is therefore a legitimate proxy where DB-query elimination is the exact claim (PERF-02); call-count covers ThreadLocal/CPU-only reuse the SELECT count can't see.

### Claude's Discretion
- The user delegated the framework-pattern judgment ("best practice là được" in the spec phase) and confirmed all four recommended options. Researcher and planner may refine the exact `CacheKey` shape, the sentinel-key naming, and per-test file placement so long as D-01..D-11 hold.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (read first)
- `.planning/phases/18-ai-runtime-performance-pass-targeted/18-SPEC.md` — Locked requirements (PERF-01..05), boundaries, acceptance criteria — MUST read before planning.

### Phase scope & ordering
- `.planning/ROADMAP.md` §"Phase 18" (lines ~167-181) — goal, success criteria as observable proxies, Phase 17→18 hard-ordering note.
- `.planning/REQUIREMENTS.md` §"AI-Runtime Performance Pass" (PERF-01..05) + Out-of-Scope rows (no Caffeine/benchmark harness; perf is AI-runtime only).
- `.planning/STATE.md` §"Hard Build-Order" + §"Decisions" — eviction-hook conventions and the v1.2 perf-cache invariants.

### Eviction hooks (already shipped — wire caches to these)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureChangedEvent.java` + `exposure/AiExposureRuleEntityListener.java` — single publish site, currently no consumer; this phase is its first consumer (exposure-derived caches).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiSettingsChangedEvent.java` (`Kind {PARAMETERS, UI_SETTINGS}`) + the `AiParametersEntityListener` / `AiUiSettingsEntityListener` twin publish sites — settings-cache eviction hook for the task-file/settings caches.

### Precedent patterns to mirror
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java` — the `ConcurrentHashMap` + `computeIfAbsent` memo idiom (Phase 17 MUT-17) that D-05/D-06 follow.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` — the ThreadLocal-slots + `clear()`-in-finally pattern that D-01 extends; `guard/IterationCounter.java` is the ThreadLocal precedent.

### Test conventions & proxy harness
- `.planning/codebase/TESTING.md` — query-count harness, pure-JUnit source-scan/reflection invariant convention, boot-regression workaround playbook.
- `.planning/codebase/CONCERNS.md` §"Boot-test regression" + §"Performance Bottlenecks" — the boot-regression nature (full-suite pressure) and the Phase 18 hotspot inventory.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/QueryCountingDataSourceConfiguration.java`, `performance/ToolQueryCountBaselineTest.java` (ceiling at ~line 151), `performance/MutationFkBatchLoadQueryCountTest.java` (slope-test template, proven green), `admin/config/AiUiSettingsResolverReadThroughTest.java` (canonical call-count workaround template).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `RunContext` (ThreadLocal slots + `clear()` in `AuditAdvisor`'s finally) — the per-turn cache anchor (D-01); add one slot, wipe it in `clear()`.
- `RelatedWriteMetadataResolver` — copy its `ConcurrentHashMap` + `computeIfAbsent` memo shape for the denylist (with `.clear()` eviction) and metadata derivation (no eviction).
- `QueryCountingDataSourceConfiguration` + `ToolQueryCountBaselineTest` — reuse as-is for the PERF-02 SELECT-count/ceiling proxy (boots today).
- `AiUiSettingsResolverReadThroughTest` — template for the real-unit-plus-mocked-edges call-count tests (PERF-01/03/04).
- `LlmExposureChangedEvent` / `AiSettingsChangedEvent` — pre-existing eviction events; this phase supplies their first cache consumers.

### Established Patterns
- Pure-JUnit source-scan + reflection invariants replace ArchUnit (`MutationToolInvariantsTest`) — use for D-09 boundary test and the "subscribes to event" invariant.
- Single-publish-site entity-listener twin pattern for change events — caches consume, never re-publish.
- Constrained `DataManager` only on data paths (never `UnconstrainedDataManager`/raw JPQL) — caching must not touch this rule.

### Integration Points
- Read-through points: `BaselineContextProvider.getReadableSchema()/compose()`, `LlmExposurePolicy.canReadEntity/canCreate/canUpdate/getDenylistedEntityNames`, `RetrievalFilterBuilder.buildFor`, `AiTaskFileMediaResolver.resolveActive` + `AiUiSettingsResolver.resolveTaskFile*`.
- Eviction wiring: new `@EventListener(LlmExposureChangedEvent)` inside `LlmExposurePolicy` (denylist `.clear()`); task-file/settings cache eviction on attachment add/delete/TTL + `AiSettingsChangedEvent`.

</code_context>

<specifics>
## Specific Ideas

- The streaming-correctness argument is the spine of PERF-01: prefer "miss → recompute" over any mechanism that optimizes cross-thread hits at the cost of stale-reuse risk. The planner should foreground D-02/D-03 in the PERF-01 plan.
- Keep the Plan 10-04 `ToolQueryCountBaselineTest` security/opacity assertions byte-for-byte; only the SELECT ceiling constant moves (D-10).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Fixing the underlying `@SpringBootTest` boot regression remains an unscheduled hardening item per CONCERNS.md; it is NOT in Phase 18 — the proxy strategy D-10/D-11 works around it rather than fixing it.)

</deferred>

---

*Phase: 18-ai-runtime-performance-pass-targeted*
*Context gathered: 2026-06-08*
