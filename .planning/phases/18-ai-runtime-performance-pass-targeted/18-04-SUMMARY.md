---
phase: 18-ai-runtime-performance-pass-targeted
plan: 04
subsystem: taskfile
tags: [perf, taskfile, media, regression-lock, PERF-04]
requires:
  - "AiTaskFileMediaResolver.resolveActive (Phase 13.1 Plan 02/03 — already invoked once per turn)"
  - "FileStorageLocator.getByName (Jmix core — the injectable counting seam)"
  - "AiUiSettingsResolver.resolveTaskFile* (Phase 16 — the settings read-through under analysis)"
provides:
  - "TaskFileMediaEncodeOncePerTurnTest — pure-JUnit/Mockito call-count proxy regression-locking the once-per-(conversationId, taskFileId)-per-turn encode"
  - "Javadoc [NOTE] on resolveActive recording the PERF-04 regression lock (no Media cache)"
affects:
  - "None — this plan owns AiTaskFileMediaResolver/AiUiSettingsResolver with no symbol overlap with Plans 01/02/03"
tech-stack:
  added: []
  patterns:
    - "Pure-JUnit/Mockito call-count proxy at injectable constructor seams (RelatedWriteMetadataMemoTest twin)"
    - "Hand-written counting FileStorage over real bytes (FileStorage.openStream as the per-file encode proxy)"
    - "FluentLoader.ByQuery RETURNS_DEEP_STUBS for the DataManager.load(...).query(...).parameter(...).list() chain"
    - "Regression-lock (assert already-good behavior) instead of redundant cache — D-10 scope discipline"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TaskFileMediaEncodeOncePerTurnTest.java"
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java"
decisions:
  - "BRANCH TAKEN: REGRESSION-LOCK (no Media cache). The proxy proves FileStorage.openStream (the per-file encode proxy) fires EXACTLY ONCE per (conversationId, taskFileId) per turn — there is no in-turn re-encode to dedupe. Per D-10 + RESEARCH Open Q2 / Pitfall 5, a Media cache would be redundant, so none is added (the predicted branch)."
  - "Settings-singleton read counted honestly: the 3 task-file knob accessors (TTL + perTurnMaxFiles + perTurnMaxTotalBytes) each loadSingleton(), so the AiUiSettings singleton is read 3x/turn. This is a redundant SETTINGS read, NOT a redundant Media ENCODE; the locked PERF-04 scope discipline only authorizes a cache for a proven second in-turn ENCODE, so no settings memo was added in this plan. Recorded as a deferred observation."
metrics:
  duration: "~25m"
  completed: "2026-06-09"
  tasks: 1
  files: 2
---

# Phase 18 Plan 04: Task-File Media Encode Once-Per-Turn (PERF-04) Summary

Proxy-first PERF-04 per the locked D-10 scope discipline: built a call-count proxy over the
task-file encode path and branched on the observed counts. The proxy proves that within ONE turn
(= one `AiTaskFileMediaResolver.resolveActive(conversationId)` — the real production call shape,
Phase 13.1 Plan 03 / `DefaultChatServiceImpl:350,617`), each kept row's blob is read through
`FileStorage.openStream` **exactly once** per `(conversationId, taskFileId)`. There is no in-turn
re-serialization of the same file. Branch taken: **REGRESSION-LOCK** — ship the call-count
assertion that locks the already-optimal behavior; add **NO** `Media` cache (a cache whose proxy
never serves a second in-turn hit is redundant — RESEARCH Open Q2 / Pitfall 5, the predicted
branch). The only production change is a Javadoc `[NOTE]` on `resolveActive` recording the lock.

## Branch Decision (recorded per critical constraint)

| Path | Observed per-turn count | Branch |
|------|------------------------|--------|
| `FileStorage.openStream` (per-file Media encode proxy) | **1** per `(conversationId, taskFileId)`; max 1 read for any single blob across a 3-file turn | **REGRESSION-LOCK — no cache** |
| `resolveTaskFile*` settings accessors | 1 call each → 3 distinct `AiUiSettings` singleton loads per turn | Recorded only — redundant SETTINGS read, not a redundant ENCODE → no cache added (out of locked PERF-04 cache scope) |

**Why no cache:** the objective's hard constraint authorizes a cache ONLY if the proxy proves a
redundant second **encode** within a turn. The encode proxy shows a single read per file — the
guard (regression lock) is the correct, cheapest outcome. The 3x settings-singleton read is a
genuine but distinct redundancy (settings read, not Media encode); per D-10 scope discipline it
is recorded honestly here rather than fixed by an unscoped settings memo in this Wave-1 plan.

## What shipped

- **`TaskFileMediaEncodeOncePerTurnTest`** (`@Tag("unit")`, pure JUnit 5 + Mockito, NO
  `@SpringBootTest`) — 4 tests, all green:
  - `singleDocumentEncodesExactlyOncePerTurn` — one document task file → `openStream` count == 1.
  - `mixedImageAndDocumentRowsEachEncodeExactlyOncePerTurn` — image (`buildMedia`) + 2 docs
    (`extractDocumentText`) → 3 reads total, `maxReadsForAnySingleRef() == 1` (no in-turn
    re-encode of any file).
  - `settingsSingletonAccessorsCalledExactlyOncePerKnobPerTurn` — `verify(times(1))` on each of
    the 3 task-file knob accessors (the settings-read proxy).
  - `rowLoadGoesThroughConstrainedDataManager` — `verify(times(1)).load(AiTaskFile.class)` on the
    constrained `DataManager` (T-18-12 invariant; never `UnconstrainedDataManager`).
- **Test seam (review HIGH #5):** counts at the injectable constructor edges of a *real*
  `AiTaskFileMediaResolver` — a hand-written counting `FileStorage` returned by a mocked
  `FileStorageLocator.getByName(...)` (the actual accessor at `:358`), whose
  `openStream(FileRef)` gates `readFileBytes` (`:357-359`), which gates BOTH the image-bytes
  `.data(...)` (`:305`) and the Tika `extractDocumentText` (`:328`) paths. The private
  `readFileBytes()` / `extractDocumentText()` / inline `new TikaDocumentReader(...)` symbols are
  NOT counted directly (no Mockito seam). The `DataManager.load(...).query(...).parameter(...).list()`
  chain is mocked via `FluentLoader.ByQuery` deep stubs (the `AiParametersResolverTest` idiom).
- **`AiTaskFileMediaResolver.resolveActive` Javadoc `[NOTE]`** — records that the method runs once
  per turn and the per-file encode runs once per `(conversationId, taskFileId)` per turn, locked by
  the new proxy; instructs future maintainers not to add a `Media` memo unless a proxy first proves
  a second in-turn encode.

## Constraints honored

- **No `RunContext.perTurnCache` / `perTurnMemoize` reference introduced** (Wave-1 plan must not
  depend on the Plan-02 Wave-2 slot — review HIGH #5). Grep == 0 in the new test.
- **Constrained `DataManager` row load preserved** — no `UnconstrainedDataManager`/raw JPQL token
  introduced on the row-load path (the one `UnconstrainedDataManager` token in the resolver is a
  pre-existing *negative* Javadoc reference at `:147` reinforcing the invariant). Asserted by
  `rowLoadGoesThroughConstrainedDataManager`.
- **`PerTurnMediaInjectionTest` unchanged** — `git diff` empty.
- **Budget/TTL tests unchanged** — `BudgetCapTest` / `TtlConfigTest` `git diff` empty; per-turn-all
  + LRU budget-cap + `task_file_budget_exceeded` audit semantics untouched.
- **ConcurrentHashMap only / no Caffeine** — no cache added at all, so trivially satisfied.

## Deviations from Plan

None — plan executed as written. The plan's PRIMARY (and predicted) branch was the
regression-lock; that is the branch taken. No Rule 1-4 deviations.

## Verification

- `cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.taskfile.TaskFileMediaEncodeOncePerTurnTest"`
  → **BUILD SUCCESSFUL**, JUnit XML: `tests="4" skipped="0" failures="0" errors="0"`.
- `--tests "com.vn.agent.taskfile.PerTurnMediaInjectionTest"` (the plan's second verification leg)
  → **FAILS TO BOOT** with the pre-existing Phase 11/13 `@SpringBootTest` regression
  (`agentstoreEntityManagerFactory` / `SessionImpl.java:63` `UnsatisfiedDependencyException`),
  documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md` and
  in 18-01-SUMMARY.md. **HONEST REPORTING:** this is NOT introduced by Plan 04 — the file's
  `git diff` is empty and the failure reproduces on the untouched test. It is exactly why the
  plan mandated a pure-JUnit/Mockito proxy for the call-count leg (which boots and passes cleanly).

## Known Stubs

None. No stub/placeholder code introduced; the new test exercises a real `AiTaskFileMediaResolver`
over real bytes.

## Deferred Issues

- **Settings-singleton triple-read per turn (observation, not a regression):** `resolveActive`
  reads the `AiUiSettings` singleton 3x per turn (TTL + perTurnMaxFiles + perTurnMaxTotalBytes,
  each its own `loadSingleton()` in `AiUiSettingsResolver`). This is a per-turn-redundant settings
  read, not a redundant Media encode, so it is OUT of the locked PERF-04 cache scope for this plan
  (which authorizes a cache only for a proven second in-turn *encode*). If a future PERF pass wants
  to collapse this, the correct shape is a self-contained per-turn settings memo on
  `AiUiSettingsResolver` evicted via `@EventListener(AiSettingsChangedEvent)` guarded on
  `Kind == UI_SETTINGS` (per RESEARCH `:109`) — a separate, scoped change.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern, or schema change at a trust
boundary. T-18-11 (stale media memo) is N/A — no cache added. T-18-12 (constrained-load
downgrade) is actively asserted green.

## Self-Check: PASSED

- `TaskFileMediaEncodeOncePerTurnTest.java` — FOUND
- `AiTaskFileMediaResolver.java` (Javadoc [NOTE]) — FOUND
- `18-04-SUMMARY.md` — FOUND
- Task commit `2fdef5f` — FOUND in git log
