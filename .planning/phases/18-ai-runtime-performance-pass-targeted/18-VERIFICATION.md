---
phase: 18-ai-runtime-performance-pass-targeted
verified: 2026-06-09T13:00:00Z
status: passed
score: 5/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: none
---

# Phase 18: AI-Runtime Performance Pass (targeted) Verification Report

**Phase Goal:** Known/suspected per-turn hotspots in chat turn execution, tool calls, mutation binding/save, media/attachment injection, RAG retrieval/filter building, and prompt/context construction are eliminated through targeted memoization — per-turn for anything user/role/exposure-sensitive, app-wide (evicted on `LlmExposureChangedEvent` / the Phase 16 `AiParameters` change event) for pure-metadata and exposure-derived caches — with no benchmark harness, no admin-screen perf work, every optimization shipping a checkable proxy, and all existing security/exposure/audit/tool/RAG test suites passing unchanged.
**Verified:** 2026-06-09T13:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (per requirement)

| # | Truth (PERF-ID) | Status | Evidence |
|---|-----------------|--------|----------|
| 1 | **PERF-01** — per-turn memo: `RunContext` has ONE new ThreadLocal cache slot wiped in `clear()`; `LlmExposurePolicy` routes `canReadEntity`/`canCreate`/`canUpdate`/`getReadableSchema` through it; off-turn access is a safe MISS (recompute-without-store); cached schema deeply immutable | ✓ VERIFIED | `RunContext.java:70` declares the single `ThreadLocal<Map<Object,Object>> PER_TURN_CACHE`; `:177-188` `perTurnCache()` is active-turn-gated (returns `Collections.emptyMap()` with no store when `CURRENT.get()==null`); `:200-206` `perTurnMemoize` recomputes-without-store off-turn; `:239` `PER_TURN_CACHE.remove()` is the last line of `clear()`; `:214-216` non-init `perTurnCacheSnapshotForTest()`. `LlmExposurePolicy.java:113,139-141,160-167,177-184` route all four through `RunContext.perTurnMemoize`; `:125-129` deep-immutable schema (`Set.copyOf` per inner set + `unmodifiableMap`). Proxies ran green: `LlmExposurePerTurnMemoTest` (2 tests, 0 fail), `PerTurnCacheBoundaryInvariantTest` (2 tests, 0 fail). |
| 2 | **PERF-02** — app-wide denylist memo: `hiddenEntityNames()` memoizes via `ConcurrentHashMap`+`computeIfAbsent` returning immutable view; evicted via `@EventListener(LlmExposureChangedEvent)`; NO `@Cacheable`. `ToolQueryCountBaselineTest` ceiling lowered to 4L (line 151 only); security/opacity assertions byte-for-byte | ✓ VERIFIED | `LlmExposurePolicy.java:91` `denylistCache = new ConcurrentHashMap<>()`; `:222-226` `computeIfAbsent(DENYLIST_KEY, ...)` returning `Collections.unmodifiableSet`; `:236-239` `@EventListener(LlmExposureChangedEvent.class) onExposureChanged` → `denylistCache.clear()`. No `@Cacheable`/`@CacheEvict`/`publishEvent` annotation (only a Javadoc note stating they are forbidden). `ToolQueryCountBaselineTest.java:151` = `4L`; `git diff main` = exactly 1 line changed (5L→4L); assertion bodies `:160-164`/`:176-180` unchanged. Test passes in isolation: 7/7 BUILD SUCCESSFUL. Proxies green: `LlmExposureDenylistMemoTest` (2), `ExposureCacheEventSubscriptionInvariantTest` (1). |
| 3 | **PERF-03** — RAG filter once: `RetrievalFilterBuilder.buildFor` builds once per retrieval reusing the denylist cache; NIN/role/fail-closed clauses verbatim; `RetrievalFilterBuilderDenylistTest` unchanged | ✓ VERIFIED | `RetrievalFilterBuilder.java:116` single `getDenylistedEntityNames()` call (hits PERF-02 cache); `:119` NIN clause verbatim; `:125-129` admin-bypass branch; `:132-135` fail-closed empty-role branch; `:140-148` per-role `(model&&roleA)||(model&&roleB)` composition; `:95-101` role extraction request-fresh (per-`Authentication`, not cached). No new cache field added. `RetrievalFilterBuilderDenylistTest.java` `git diff` empty across phase. Proxy green: `RetrievalFilterBuilderBuildOncePerRetrievalTest` (3 tests, 0 fail). |
| 4 | **PERF-04** — task-file Media: D-10 scope discipline — proxy ran, encode already once-per-turn → REGRESSION-LOCK (no cache added); `PerTurnMediaInjectionTest` unchanged | ✓ VERIFIED | `AiTaskFileMediaResolver.java:155-165` regression-lock Javadoc `[NOTE]` documenting once-per-turn encode; NO `ConcurrentHashMap`/`computeIfAbsent`/`RunContext.perTurnCache` added (added-line grep = 0); `:175` row load stays constrained `dataManager.load(AiTaskFile.class)` (no `UnconstrainedDataManager` introduced; the one reference is a pre-existing negative Javadoc at `:147`). `PerTurnMediaInjectionTest`, `BudgetCapTest`, `TtlConfigTest` all unchanged (`git diff` empty). Proxy green: `TaskFileMediaEncodeOncePerTurnTest` (4 tests, 0 fail). SUMMARY records REGRESSION-LOCK branch. |
| 5 | **PERF-05** — cross-cutting close: `NoNewPerfDependencyInvariantTest` (no jmh/gatling/caffeine + all proxies exist) + `SettingsEditVisibleNextTurnTest` (admin edit visible next turn) pass; no new component/public-API/migration | ✓ VERIFIED | `NoNewPerfDependencyInvariantTest` (2 tests, 0 fail) + grep of all `*.gradle` under `ai-agent/` = no caffeine/jmh/gatling token. `SettingsEditVisibleNextTurnTest` (2 tests, 0 fail) proves denylist returns OLD value pre-eviction, NEW value after `onExposureChanged`. Phase-18 source diff = 4 already-shipped prod classes modified + 8 new test files + 1 existing-test 1-line edit; NO new production component, NO new public-API class, NO Liquibase changelog. |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `exposure/LlmExposurePolicy.java` | denylist memo + @EventListener + per-turn read-through + immutable views | ✓ VERIFIED | All present; compiles; routed; no `@Cacheable` |
| `orchestration/RunContext.java` | one ThreadLocal slot + active-turn-gated accessor + memoize + snapshot seam + wipe in clear() | ✓ VERIFIED | All present; no process-wide map, no reactor Context |
| `rag/RetrievalFilterBuilder.java` | build once per retrieval, clauses verbatim, no new cache | ✓ VERIFIED | Single denylist read; clauses verbatim; no cache field |
| `taskfile/AiTaskFileMediaResolver.java` | regression-lock Javadoc note, constrained load preserved | ✓ VERIFIED | `[NOTE]` present; no cache; constrained `DataManager` |
| 8 new proxy test classes (PERF-01..05) | exist + pass | ✓ VERIFIED | All on disk; 25 tests total executed, 0 failures/errors/skips |
| `ToolQueryCountBaselineTest.java` | ceiling lowered to 4L, line 151 only | ✓ VERIFIED | `git diff main` = 1 line; passes 7/7 in isolation |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `LlmExposurePolicy.hiddenEntityNames()` | `LlmExposureRuleRepository.findEnabledExcludedEntityNames()` | `computeIfAbsent(DENYLIST_KEY)` | ✓ WIRED | `:222-224` |
| `LlmExposurePolicy.onExposureChanged()` | `denylistCache.clear()` | `@EventListener(LlmExposureChangedEvent)` | ✓ WIRED | `:236-239` |
| `LlmExposurePolicy` verdicts/schema | `RunContext.perTurnMemoize()` | active-turn-gated memoize | ✓ WIRED | `:113,139,160,177` |
| `RunContext.clear()` | `PER_TURN_CACHE.remove()` | last-line wipe | ✓ WIRED | `:239` |
| `RetrievalFilterBuilder.buildFor` | `LlmExposurePolicy.getDenylistedEntityNames()` | single per-retrieval call → PERF-02 cache | ✓ WIRED | `:116` |

### Behavioral Spot-Checks (executed in this verification, own process)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| 8 pure-unit PERF proxies (PERF-01..05) | `gradlew :ai-agent:test --tests <8 classes>` | BUILD SUCCESSFUL; 21 tests, 0 fail/err/skip | ✓ PASS |
| PERF-02 SELECT-count proxy (lowered 4L ceiling, @SpringBootTest) | `gradlew :ai-agent:test --tests ToolQueryCountBaselineTest` | BUILD SUCCESSFUL; 7/7, 0 fail | ✓ PASS |

(Aggregate: 9 proxy test classes / 25 tests executed independently of the executor's run, all green.)

### Out-of-Scope Guard

| Guard | Status | Evidence |
|-------|--------|----------|
| No benchmark harness (jmh/gatling) | ✓ HELD | grep all `*.gradle` = 0; `NoNewPerfDependencyInvariantTest` green |
| No Caffeine / new cache dependency | ✓ HELD | grep = 0; only `ConcurrentHashMap`/ThreadLocal used |
| No admin-screen perf work | ✓ HELD | Phase-18 diff touches only exposure/orchestration/rag/taskfile runtime classes |
| No security/exposure/opacity semantic change | ✓ HELD | RAG clauses verbatim; immutable cached views; `AccessManager`/constrained `DataManager` authoritative; review confirms 0 blockers |
| Only existing-test-body edit = ToolQueryCountBaselineTest:151 | ✓ HELD | Phase-18 src diff: 1 existing test (1 line) + 8 new test files |
| No new component / public API / migration | ✓ HELD | 4 already-shipped prod classes modified; no new `@Component` class; no changelog |

### Requirements Coverage

| Requirement | Source Plan | Status | Evidence |
|-------------|-------------|--------|----------|
| PERF-01 | 18-02-PLAN | ✓ SATISFIED | RunContext slot + per-turn read-through; proxies green |
| PERF-02 | 18-01-PLAN | ✓ SATISFIED | Denylist memo + event eviction + lowered ceiling; proxies green |
| PERF-03 | 18-03-PLAN | ✓ SATISFIED | Build-once-per-retrieval; denylist test unchanged; proxy green |
| PERF-04 | 18-04-PLAN | ✓ SATISFIED | Regression-lock (no cache); constrained load; proxy green |
| PERF-05 | 18-05-PLAN | ✓ SATISFIED | Dep-scan + proxy-existence + admin-edit-visible; full suite green |

All 5 declared requirement IDs (PERF-01..05) cross-referenced against `REQUIREMENTS.md:52-56` and the Phase-18 traceability rows `:131-135` (all marked Complete). No orphaned requirements.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none introduced by Phase 18) | — | — | — | No TBD/FIXME/XXX/TODO/HACK in added prod lines; the only `@Cacheable` occurrence is a Javadoc note stating it is forbidden (D-07) |

### Code Review (advisory, 18-REVIEW.md)

0 blockers, 4 warnings, 5 info. Factored into confidence; none are gaps:
- **WR-01** (benign sub-ms repopulate-with-stale race on concurrent eviction): self-healing, narrow; the "visible next turn" guarantee is probabilistic only under a concurrent eviction-vs-compute window — not a correctness defect. Synchronous post-commit event publish + per-turn re-read self-heal.
- **WR-02** (mid-turn exposure edit invisible until next turn for per-turn verdicts): explicitly accepted (T-18-07); direction is fail-toward-old, never over-permissive. Documentation tightening suggested, not required.
- **WR-03 / WR-04** (test-rigor): `SettingsEditVisibleNextTurnTest` value assertion is load-bearing only when paired with its `times(1)` verify (which is present); `ToolQueryCountBaselineTest` never calls `RunContext.set` so it measures the off-turn ceiling — PERF-01 per-turn collapse is instead proven by `LlmExposurePerTurnMemoTest` (verified present and green). Coverage is adequate via the dedicated Mockito proxy.

### Human Verification Required

None. This is a memoization/test-hardening phase whose claims are fully machine-checkable via proxies. The full default suite ran green per the orchestrator (`:ai-agent:test` → BUILD SUCCESSFUL, 865 tests, 0 failures, 3 skipped), and this verification independently re-ran 9 proxy test classes (25 tests, all green) plus the `@SpringBootTest` SELECT-count proxy in isolation (7/7). The documented `@SpringBootTest` boot regression (MetaClass not found for AiAuditEvent) is a pre-existing full-suite forked-context-pressure phenomenon, not a Phase-18 gap, and does not trip the single-fork suite run.

### Gaps Summary

No gaps. All five requirements (PERF-01..05) are achieved in the actual codebase, every optimization ships ≥1 executed-and-green proxy, every out-of-scope boundary holds, the only existing-test-body edit is the deliberate ToolQueryCountBaselineTest:151 ceiling recalibration, and no new component/public-API/migration was introduced. The four advisory warnings are correctness-edge/test-rigor notes with documented acceptances, not blockers.

---

_Verified: 2026-06-09T13:00:00Z_
_Verifier: Claude (gsd-verifier)_
