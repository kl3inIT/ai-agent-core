---
phase: 18
slug: ai-runtime-performance-pass-targeted
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-09
---

# Phase 18 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `18-RESEARCH.md` §Validation Architecture (grounded against live code).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) + AssertJ + Mockito via `spring-boot-starter-test` (`junit-vintage` excluded) |
| **Config file** | `ai-agent/ai-agent/ai-agent.gradle` (module build script) |
| **Quick run command** | `./gradlew :ai-agent:test --tests "com.vn.agent.<Class>"` |
| **Full suite command** | `./gradlew :ai-agent:test` (excludes `live`, `rag-it`, `eval`) |
| **Perf-proxy harness** | `net.ttddyy:datasource-proxy:1.11.0` via `QueryCountingDataSourceConfiguration` (wraps `agentstoreDataSource` in-place) — boots + passes today in isolation (D-11) |
| **Estimated runtime** | Per-class call-count/Mockito tests <30s each; full default suite is the wave gate |

---

## Sampling Rate

- **After every task commit:** Run the single most-relevant `./gradlew :ai-agent:test --tests "<Class>"` (each <30s under the pure-JUnit/Mockito style)
- **After every plan wave:** Run `./gradlew :ai-agent:test` (full default suite)
- **Before `/gsd-verify-work`:** Full default suite must be green — the ONLY allowed test-body edit is `ToolQueryCountBaselineTest.java:151` (the `METAMODEL_TOOL_POLICY_LOOKUP_CEILING` recalibration)
- **Max feedback latency:** ~30s per task; full suite at wave merge

---

## Per-Task Verification Map

> Task IDs are assigned by the planner. Rows below are seeded from the requirement→test map in
> `18-RESEARCH.md` and MUST be reconciled to concrete `{18-PP-TT}` task IDs once PLAN.md files exist.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 18-??-?? | TBD | TBD | PERF-01 | — | per-turn schema/exposure resolved once; cache empty after `RunContext.clear()` (no cross-turn/cross-user bleed) | call-count (Mockito) + ThreadLocal-empty assert | `--tests "*PerTurn*Memo*"` | ❌ W0 (template: `RelatedWriteMetadataMemoTest`) | ⬜ pending |
| 18-??-?? | TBD | TBD | PERF-02 | — | denylist fetched once, reused until `LlmExposureChangedEvent`, then exactly one refetch; lowered SELECT ceiling; every exposure-derived cache subscribes to the event | SELECT-count (edit ceiling `:151`) + call-count spy + reflection invariant | `--tests "ToolQueryCountBaselineTest"` + new | ✅ ceiling / ❌ W0 call-count+invariant | ⬜ pending |
| 18-??-?? | TBD | TBD | PERF-03 | — | RAG `Filter.Expression` built once per retrieval; clauses preserved verbatim | call-count `times(1)` + existing denylist test | `--tests "RetrievalFilterBuilderDenylistTest"` + new | ✅ denylist / ❌ W0 call-count | ⬜ pending |
| 18-??-?? | TBD | TBD | PERF-04 | — | task-file `Media` encode/resolve ≤ once per `(convId, taskFileId)` per turn (or regression-lock if already once) | call-count on FileStorage read + Tika + `resolveTaskFile*` | `--tests "*TaskFileMedia*"` + existing `PerTurnMediaInjectionTest` | ✅ PerTurn / ❌ W0 encode call-count | ⬜ pending |
| 18-??-?? | TBD | TBD | PERF-05 | — | no JMH/Gatling/Caffeine added; admin settings edit visible next turn; all security/exposure/audit/tool/RAG suites unchanged | build-dep scan invariant + call-count integration + full suite | `./gradlew :ai-agent:test` | ❌ W0 dep-scan + settings-eviction | ⬜ pending |
| 18-??-?? | TBD | TBD | PERF-01 (boundary) | — | per-turn cache symbol appears ONLY in `LlmExposurePolicy` + `RunContext`, never in `BuiltInDataTools` (constrained-DataManager row path) | source-scan/reflection invariant (D-09) | `--tests "*InvariantTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] PERF-01 call-count + `assertThat(cache).isEmpty()`-after-`clear()` test (template: `RelatedWriteMetadataMemoTest`)
- [ ] PERF-02 "one fetch reused until `LlmExposureChangedEvent`, then exactly one refetch" call-count test (spy `LlmExposureRuleRepository.findEnabledExcludedEntityNames`)
- [ ] PERF-02 reflection/source-scan invariant "every exposure-derived cache subscribes to `LlmExposureChangedEvent`" (template: `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan`)
- [ ] PERF-03 `times(1)` per-retrieval call-count on `RetrievalFilterBuilder.buildFor`
- [ ] PERF-04 encode call-count test (FileStorage read + Tika + `resolveTaskFile*`) — OR regression lock if proxy shows `resolveActive` already runs once/turn (D-10 scope discipline)
- [ ] PERF-05 build-dependency invariant (no `jmh`/`gatling`/`caffeine`) + `AiSettingsChangedEvent`-visible-next-turn test
- [ ] D-09 boundary invariant: per-turn cache symbol confined to `LlmExposurePolicy` + `RunContext`

*Framework install: none — JUnit 5 / Mockito / AssertJ / datasource-proxy all present.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Admin denylist/settings edit visible within one turn | PERF-02 / PERF-05 | End-to-end admin→turn visibility is exercised by the event-eviction integration test; a live UI smoke is optional confirmation only | After a denylist edit in the admin screen, start a new chat turn and confirm the newly-denied entity is absent from the readable schema |

*All other phase behaviors have automated verification via call-count / SELECT-count / source-scan proxies.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s per task
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
