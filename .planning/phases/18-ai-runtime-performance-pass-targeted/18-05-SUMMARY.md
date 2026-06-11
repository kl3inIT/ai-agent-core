---
phase: 18-ai-runtime-performance-pass-targeted
plan: 05
subsystem: performance
tags: [perf, PERF-05, dep-scan, invariant, eviction-visibility, phase-gate]
requires:
  - "LlmExposurePolicy.getDenylistedEntityNames + @EventListener(LlmExposureChangedEvent) eviction (Plan 18-01/02)"
  - "PERF-01..04 proxy test classes on the test source tree (Plans 18-01..04)"
  - "AiSettingsChangedEventListenerInvariantTest (Phase 16) — the file-walk + findMainJavaRoot analog"
provides:
  - "NoNewPerfDependencyInvariantTest — dep-scan invariant (no caffeine/jmh/gatling) + PERF-01..04 proxy-existence guard"
  - "SettingsEditVisibleNextTurnTest — admin denylist edit visible next turn via LlmExposureChangedEvent eviction"
affects:
  - "None — two new test-only files; no main source touched in this plan"
tech-stack:
  added: []
  patterns:
    - "Pure JUnit 5 dep-scan over Gradle build scripts via java.nio.file.Path walk (Windows/POSIX portable)"
    - "Proxy-existence guard: package-agnostic file-name match across the test source root"
    - "Observable-value eviction test (old-before / new-after) layered on the Plan-01 call-count proxy"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/performance/NoNewPerfDependencyInvariantTest.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/SettingsEditVisibleNextTurnTest.java"
  modified: []
decisions:
  - "AiSettingsChangedEvent visibility leg OMITTED — Plan 18-04 took the REGRESSION-LOCK branch (no settings memo), so the task-file settings are read fresh each turn and are visible by construction; absence documented in the test Javadoc per the plan's conditional."
  - "Dep-scan is case-insensitive over the LOWERCASED build-script text; datasource-proxy (the allowed SELECT-count harness) and spring-ai-model-chat-memory-repository-jdbc (contains 'memory', not a forbidden token) both pass."
  - "Proxy-existence guard matches by file simple-name across the whole test source root (package-agnostic) so a future package move cannot silently defeat it; PerTurnCacheBoundaryInvariantTest lives in package exposure, not orchestration."
metrics:
  duration: "~25m"
  completed: "2026-06-09"
  tasks: 2
  files: 2
---

# Phase 18 Plan 05: PERF-05 Cross-Cutting Close (dep-scan + admin-edit-visibility + full-suite gate) Summary

Closes PERF-05 — the phase's "proxies exist, nothing out-of-scope introduced, suites unchanged,
admin edit visible within one turn" gate — with two new pure-JUnit tests and a clean full-suite run.
Both guarantees are now machine-checkable rather than argued.

## What shipped

- **`NoNewPerfDependencyInvariantTest`** (`com.vn.agent.performance`, `@Tag("unit")`, no
  `@SpringBootTest`) — 2 tests, green:
  - `buildScriptsDeclareNoBenchmarkOrCacheDependency` — reads `ai-agent/ai-agent/ai-agent.gradle`
    (module) and `ai-agent/build.gradle` (root add-on), asserts (case-insensitive) NEITHER declares
    a `caffeine`, `jmh`, or `gatling` token (T-18-15 / T-18-SC).
  - `everyPerfOptimizationShipsAtLeastOneProxyTest` — walks the test source root and asserts all six
    PERF-01..04 proxy test files exist: `LlmExposureDenylistMemoTest` +
    `ExposureCacheEventSubscriptionInvariantTest` (PERF-02, Plan 01), `LlmExposurePerTurnMemoTest` +
    `PerTurnCacheBoundaryInvariantTest` (PERF-01, Plan 02),
    `RetrievalFilterBuilderBuildOncePerRetrievalTest` (PERF-03, Plan 03),
    `TaskFileMediaEncodeOncePerTurnTest` (PERF-04, Plan 04).
  - All path navigation uses `java.nio.file.Path` (no hardcoded `/` or `\`), portable across the
    Windows dev box and POSIX CI.
- **`SettingsEditVisibleNextTurnTest`** (`com.vn.agent.exposure`, `@Tag("unit")`, JUnit 5 + Mockito,
  no `@SpringBootTest`) — 2 tests, green:
  - `adminDenylistEditIsVisibleOnTheNextTurnAfterEvictionEvent` — builds a real `LlmExposurePolicy`
    over a mock `LlmExposureRuleRepository`; asserts the policy returns the OLD denylist (set A) both
    before and immediately after an admin edit re-stubs the repo to set B (proving a real cross-turn
    cache exists), then asserts that after `onExposureChanged(new LlmExposureChangedEvent(this))` the
    next resolve reflects set B — the admin edit is visible on the next turn (T-18-14).
  - `evictionRefetchesExactlyOncePerEventNotPerCall` — one pre-event fetch + exactly one post-event
    refetch across many resolves (the "exactly one refetch after the event" half of the contract).

## Branch decision (recorded per critical constraint)

| Leg | Plan condition | Taken? |
|-----|----------------|--------|
| Denylist eviction visibility (`LlmExposureChangedEvent`) | always | YES — both visibility + exactly-once-refetch asserted end-to-end against Plan 01's `@EventListener` wiring |
| Settings-memo visibility (`AiSettingsChangedEvent(UI_SETTINGS)`) | only if Plan 04 added a settings memo | NO — Plan 18-04 SUMMARY records the REGRESSION-LOCK branch (no settings cache). Task-file settings are read fresh from the `AiUiSettings` singleton each turn, so an admin edit is visible by construction with no memo to evict. Documented in the test Javadoc. |

## Deviations from Plan

None — plan executed as written. The `AiSettingsChangedEvent` leg's omission is the plan's own
conditional (present only if Plan 04 took the settings-memo branch; it did not). No Rule 1-4 deviations.

## Constraints honored

- **No JMH/Gatling/Caffeine** — asserted green by the new dep-scan; no dependency added by this plan.
- **PERF-01..04 proxies all present** — asserted green (six files).
- **Only one existing-test-body edit across Phase 18** — verified via
  `git diff ad167e3^..HEAD -- src/test/**`: the sole `M` test file in the Phase-18 commit range is
  `ToolQueryCountBaselineTest.java`, and its only line change is the line-151 ceiling
  `5L → 4L`. Every other Phase-18 test change is a NEW file. (The broader `main...HEAD` range also
  contains Phase 17 / test-consolidation `M`/`D` entries — those are out of Phase 18's scope and not
  counted against this invariant.)
- **ConcurrentHashMap/ThreadLocal only** — no cache added in this plan at all (test-only).

## Verification

- `./gradlew :ai-agent:test --tests "com.vn.agent.performance.NoNewPerfDependencyInvariantTest"`
  → BUILD SUCCESSFUL, `tests="2" failures="0" errors="0"`.
- `./gradlew :ai-agent:test --tests "com.vn.agent.exposure.SettingsEditVisibleNextTurnTest"`
  → BUILD SUCCESSFUL, `tests="2" failures="0" errors="0"`.
- **Full default suite** `./gradlew :ai-agent:test` → **BUILD SUCCESSFUL in 15m 7s**.
  Aggregate over 184 test classes: **865 tests, 0 failures, 0 errors, 3 skipped** (single-fork
  default `maxParallelForks=1`, `forkEvery=20`).

### Full-suite honesty note (per critical constraint)

This single-fork full-suite run completed **green with zero failures** — the known full-suite
`@SpringBootTest` boot regression (`MetaClass not found for AiAuditEvent` /
`agentstoreEntityManagerFactory`) did **not** trip in this run. That regression is a full-suite
forked-context-pressure phenomenon (documented in
`.planning/codebase/CONCERNS.md` and `13-.../deferred-items.md`) that manifests intermittently under
memory pressure, not a recipe-level failure of any individual test. **This phase introduced zero new
failures**: both Phase-18 new tests pass in isolation AND inside the green full suite, and the
modified `ToolQueryCountBaselineTest` (line-151 ceiling 4L) passes with the rest of the suite.

## Known Stubs

None. Both new files are real assertions over real artifacts (the live build scripts, the live
`LlmExposurePolicy` + its `@EventListener` eviction, and the on-disk proxy test files).

## Threat Flags

None. No new network endpoint, auth path, file-access pattern, or schema change at a trust boundary.
T-18-14 (non-evicting cache → stale admin edit) and T-18-15 / T-18-SC (out-of-scope dependency creep)
are both actively asserted GREEN by this plan; T-18-16 (silent existing-test-body edit) is held by the
single-line-151 invariant confirmed above.

## Self-Check: PASSED

- `NoNewPerfDependencyInvariantTest.java` — FOUND
- `SettingsEditVisibleNextTurnTest.java` — FOUND
- `18-05-SUMMARY.md` — FOUND
- Task 1 commit `d08a824` — FOUND in git log
- Task 2 commit `de94796` — FOUND in git log
