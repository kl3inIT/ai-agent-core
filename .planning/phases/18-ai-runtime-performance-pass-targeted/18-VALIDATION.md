---
phase: 18
slug: ai-runtime-performance-pass-targeted
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-09
---

# Phase 18 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `18-RESEARCH.md` §Validation Architecture (grounded against live code).
> Reconciled to concrete `{18-PP-TT}` task IDs after PLAN.md files were created (2026-06-09).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) + AssertJ + Mockito via `spring-boot-starter-test` (`junit-vintage` excluded) |
| **Config file** | `ai-agent/ai-agent/ai-agent.gradle` (module build script) |
| **Quick run command** | `cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.<Class>"` |
| **Full suite command** | `cd ai-agent && ./gradlew :ai-agent:test` (excludes `live`, `rag-it`, `eval`) |
| **Perf-proxy harness** | `net.ttddyy:datasource-proxy:1.11.0` via `QueryCountingDataSourceConfiguration` (wraps `agentstoreDataSource` in-place) — boots + passes today in isolation (D-11) |
| **Estimated runtime** | Per-class call-count/Mockito tests <30s each; full default suite is the wave gate |

---

## Sampling Rate

- **After every task commit:** Run the single most-relevant `cd ai-agent && ./gradlew :ai-agent:test --tests "<Class>"` (each <30s under the pure-JUnit/Mockito style)
- **After every plan wave:** Run `cd ai-agent && ./gradlew :ai-agent:test` (full default suite)
- **Before `/gsd-verify-work`:** Full default suite must be green — the ONLY allowed test-body edit is `ToolQueryCountBaselineTest.java:151` (the `METAMODEL_TOOL_POLICY_LOOKUP_CEILING` recalibration)
- **Max feedback latency:** ~30s per task; full suite at wave merge

---

## Per-Task Verification Map

> Task IDs reconciled to `{18-plan-task}` against the created PLAN.md files.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 18-01-01 | 18-01 | 1 | PERF-02 | T-18-01, T-18-02 | denylist memoized app-wide via `ConcurrentHashMap`+`computeIfAbsent`; evicted on `LlmExposureChangedEvent`; no `@Cacheable`; no new component/publisher | source assertion (compile + grep) | `--tests "com.vn.agent.exposure.LlmExposureDenylistMemoTest"` | ❌ W0 (created by 18-01-02) | ⬜ pending |
| 18-01-02 | 18-01 | 1 | PERF-02 | T-18-01, T-18-02, T-18-03 | denylist fetched once, reused until `LlmExposureChangedEvent`, then exactly one refetch; SELECT ceiling lowered (line 151 only); every exposure-derived cache subscribes to the event; security/opacity assertions byte-for-byte | SELECT-count (edit ceiling `:151`) + call-count spy (`times(1)`→`times(2)`) + source-scan invariant | `--tests "com.vn.agent.exposure.LlmExposureDenylistMemoTest" --tests "com.vn.agent.exposure.ExposureCacheEventSubscriptionInvariantTest" --tests "com.vn.agent.performance.ToolQueryCountBaselineTest"` | ✅ ceiling / ❌ W0 call-count+invariant | ⬜ pending |
| 18-02-01 | 18-02 | 2 | PERF-01 | T-18-04, T-18-05 | one `RunContext` per-turn cache slot added; ACTIVE-TURN-GATED accessor (allocates only when `RunContext.get()!=null`; recompute-without-store on no-active-turn/foreign threads) + non-init `perTurnCacheSnapshotForTest`; wiped in `clear()`; no process-wide runId map, no reactor-Context propagation | source assertion (compile + grep) | `cd ai-agent && ./gradlew :ai-agent:compileJava` | ❌ W0 | ⬜ pending |
| 18-02-02 | 18-02 | 2 | PERF-01 + boundary (D-09) | T-18-04, T-18-06, T-18-07 | `canReadEntity` + CRUD verdicts + readable schema resolved once per `RunContext` across N tool calls (canReadEntity included per D-08); raw slot null after `RunContext.clear()` (non-init seam); per-turn cache symbol confined to `LlmExposurePolicy` + `RunContext`, absent from `BuiltInDataTools` | call-count (Mockito) + non-init-snapshot-null assert + source-scan boundary invariant | `--tests "com.vn.agent.exposure.LlmExposurePerTurnMemoTest" --tests "com.vn.agent.exposure.PerTurnCacheBoundaryInvariantTest"` | ❌ W0 (template: `RelatedWriteMetadataMemoTest`) | ⬜ pending |
| 18-03-01 | 18-03 | 2 | PERF-03 | T-18-08, T-18-09, T-18-10 | RAG `Filter.Expression` built once per retrieval; denylist read reuses PERF-02 cache; clauses preserved verbatim; role extraction request-fresh | existing denylist test unchanged | `--tests "com.vn.agent.rag.RetrievalFilterBuilderDenylistTest"` | ✅ denylist | ⬜ pending |
| 18-03-02 | 18-03 | 2 | PERF-03 | T-18-08 | denylist lookup / build invoked exactly once per `buildFor` retrieval; NIN + role clause still emitted | call-count `times(1)` + verbatim-clause smoke | `--tests "com.vn.agent.rag.RetrievalFilterBuilderBuildOncePerRetrievalTest" --tests "com.vn.agent.rag.RetrievalFilterBuilderDenylistTest"` | ❌ W0 call-count | ⬜ pending |
| 18-04-01 | 18-04 | 1 | PERF-04 | T-18-11, T-18-12, T-18-13 | per-file encode (counted via counting `FileStorage` seam at `FileStorageLocator.getByName`→`openStream`, NOT private Tika internals) + `resolveTaskFile*` ≤ once per `(convId, taskFileId)` per turn (regression-lock) or memoized+evicted via SELF-CONTAINED resolver `ConcurrentHashMap` (cache branch — NOT `RunContext.perTurnCache`, Wave-1); row load stays constrained `DataManager` | call-count on FileStorage `openStream` + `resolveTaskFile*`/settings-singleton (branch per D-10) | `--tests "com.vn.agent.taskfile.TaskFileMediaEncodeOncePerTurnTest" --tests "com.vn.agent.taskfile.PerTurnMediaInjectionTest"` | ✅ PerTurn / ❌ W0 encode call-count | ⬜ pending |
| 18-05-01 | 18-05 | 3 | PERF-05 | T-18-15 | no JMH/Gatling/Caffeine token in build scripts; every PERF-01..04 proxy class exists | build-dep scan invariant + proxy-existence scan | `--tests "com.vn.agent.performance.NoNewPerfDependencyInvariantTest"` | ❌ W0 dep-scan | ⬜ pending |
| 18-05-02 | 18-05 | 3 | PERF-05 | T-18-14, T-18-16 | admin edit via `LlmExposureChangedEvent` (+ `AiSettingsChangedEvent` where applicable) visible next turn; full suite green; only existing-test-body edit is line 151 | call-count visibility + full-suite gate | `--tests "com.vn.agent.exposure.SettingsEditVisibleNextTurnTest"` then `cd ai-agent && ./gradlew :ai-agent:test` | ❌ W0 settings-eviction | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

> Threat refs map into each plan's `<threat_model>` STRIDE register (T-18-NN). The dominant class is
> cache-induced authorization bypass / stale-permission reuse; the per-turn `RunContext.clear()` +
> foreign-thread-safe-miss (18-02) and the `@EventListener` eviction (18-01 / 18-05) are the mitigations.

---

## Wave 0 Requirements

> Wave-0 stubs are folded into each plan's first task (the pure-JUnit/Mockito proxy tests reference
> symbols created in the same plan, so they are written alongside — not as a standalone wave). The
> PERF-02 SELECT-ceiling proxy (`ToolQueryCountBaselineTest`) already exists and is recalibrated in 18-01-02.

- [ ] PERF-01 call-count + `assertThat(RunContext.perTurnCache()).isEmpty()`-after-`clear()` test (template: `RelatedWriteMetadataMemoTest`) — **18-02-02** (`LlmExposurePerTurnMemoTest`)
- [ ] PERF-02 "one fetch reused until `LlmExposureChangedEvent`, then exactly one refetch" call-count test (spy `LlmExposureRuleRepository.findEnabledExcludedEntityNames`) — **18-01-02** (`LlmExposureDenylistMemoTest`)
- [ ] PERF-02 reflection/source-scan invariant "every exposure-derived cache subscribes to `LlmExposureChangedEvent`" (template: `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan`) — **18-01-02** (`ExposureCacheEventSubscriptionInvariantTest`)
- [ ] PERF-03 `times(1)` per-retrieval call-count on `RetrievalFilterBuilder.buildFor` — **18-03-02** (`RetrievalFilterBuilderBuildOncePerRetrievalTest`)
- [ ] PERF-04 encode call-count test (FileStorage read + Tika + `resolveTaskFile*`) — OR regression lock if proxy shows `resolveActive` already runs once/turn (D-10 scope discipline) — **18-04-01** (`TaskFileMediaEncodeOncePerTurnTest`)
- [ ] PERF-05 build-dependency invariant (no `jmh`/`gatling`/`caffeine`) — **18-05-01** (`NoNewPerfDependencyInvariantTest`) + `AiSettingsChangedEvent`/`LlmExposureChangedEvent`-visible-next-turn test — **18-05-02** (`SettingsEditVisibleNextTurnTest`)
- [ ] D-09 boundary invariant: per-turn cache symbol confined to `LlmExposurePolicy` + `RunContext` — **18-02-02** (`PerTurnCacheBoundaryInvariantTest`)

*Framework install: none — JUnit 5 / Mockito / AssertJ / datasource-proxy all present.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Admin denylist/settings edit visible within one turn | PERF-02 / PERF-05 | End-to-end admin→turn visibility is exercised by the event-eviction integration test (18-05-02); a live UI smoke is optional confirmation only | After a denylist edit in the admin screen, start a new chat turn and confirm the newly-denied entity is absent from the readable schema |

*All other phase behaviors have automated verification via call-count / SELECT-count / source-scan proxies.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s per task (call-count/Mockito) — full suite at wave merge
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** reconciled to task IDs 2026-06-09; pending execution.
