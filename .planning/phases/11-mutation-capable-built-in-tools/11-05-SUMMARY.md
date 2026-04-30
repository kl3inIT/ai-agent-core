---
phase: 11-mutation-capable-built-in-tools
plan: 05
subsystem: tools-mutation
tags: [idempotency, dedup, scheduled-cleanup, mut-04, mut-11, agentstore]

# Dependency graph
requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: AiMutationIntent entity + AiMutationIntentStatus enum + Liquibase changelog (Plan 11-01)
  - phase: 11-mutation-capable-built-in-tools
    provides: AiAgentMutationProperties + @EnableScheduling on AIConfiguration (Plan 11-02)
provides:
  - "MutationIntentRepository @Component (com.vn.agent.tools.mutation) — reserveOrReplay / markCommitted / markFailed / markCommitUnknown / findExisting / deleteExpired / countExpiredInFlight"
  - "MutationIntentRepository.ReservationResult record + ReservationState enum (RESERVED, REPLAY, PENDING, VIOLATION) — public API surface for BuiltInMutationTools"
  - "MutationIntentRepository.MutationIntentFailureProbe — package-public test seam wired through ObjectProvider for TEST-12 COMMIT_UNKNOWN coverage"
  - "MutationIntentCleanupJob @Component — hourly @Scheduled(cron='0 0 * * * *') TTL reaper bound to agentstoreTransactionManager"
affects: [11-06, 11-07, 11-07A, 11-07B, 11-07C, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Reservation-then-finalize idempotency: PENDING row written via TransactionTemplate REQUIRES_NEW BEFORE host save, so the AI_MUTATION_INTENT composite unique index is the distributed lock that serializes concurrent duplicate calls at the DB boundary"
    - "Outer try/catch around TransactionTemplate.execute(...) catches commit-time DataIntegrityViolationException / TransactionSystemException / PersistenceException and re-classifies the existing row instead of letting unique-index races escape as generic mutation errors"
    - "Internal state-machine enforcement: every finalization method reloads the row by id and only applies PENDING → terminal transitions; markCommitUnknown will not downgrade a COMMITTED row even if a post-commit exception fires"
    - "FAILED → PENDING reclaim is a @Version-guarded compare-and-set; the loser of a reclaim race re-reads and classifies as PENDING/REPLAY/VIOLATION rather than also returning RESERVED"
    - "MutationIntentFailureProbe ObjectProvider hook — production has no bean and uses noop(); tests register a throwing @Bean to exercise the COMMIT_UNKNOWN path without mocking the repository"
    - "Hourly cleanup deletes only COMMITTED/FAILED rows; PENDING and COMMIT_UNKNOWN are logged at WARN for operator triage and never auto-deleted (deleting them could allow duplicate host writes after a finalization failure)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentCleanupJob.java
  modified: []

key-decisions:
  - "Repository reservation uses programmatic TransactionTemplate (REQUIRES_NEW) rather than method-level @Transactional so the outer method body wraps execute(...) and catches commit-time DataAccessException / TransactionSystemException / PersistenceException — required because @Transactional methods cannot intercept the proxy's own commit-time throw"
  - "FAILED rows are reclaimable on same-key + same-hash; corrected request shape after validation_failed always returns VIOLATION because requestHash mismatch is checked before status-based branching"
  - "markCommitUnknown explicitly no-ops on COMMITTED to prevent post-commit exceptions (e.g. audit write or finalization log) from downgrading a successfully committed dedup row"
  - "Cleanup job logs but never deletes PENDING/COMMIT_UNKNOWN: deletion would let the next call insert a fresh PENDING reservation while host data may still be in the DB from a hung commit, which is the exact concurrency hole this design was built to close"
  - "No locale changes for cleanup job — log lines only, no user-facing UI surface in v1.1; tool-error locales land in Plan 11-06"
  - "Bulk delete inside deleteExpired() iterates dataManager.remove(intent) over a JPQL-loaded list rather than a JPQL DELETE statement, because Jmix data-API listeners and audit triggers run only on per-entity remove(); for an idempotency dedup row this is a small list (<<10k expected) and the listener correctness is worth more than the tiny JPQL bulk-delete win"

patterns-established:
  - "Repository @Transactional on finalization methods: transactionManager='agentstoreTransactionManager' + Propagation.REQUIRES_NEW so finalization commits independently of any caller transaction; reservation uses TransactionTemplate so the outer method can catch commit failures"
  - "ObjectProvider test seam: package-public interface + ObjectProvider.getIfAvailable(Default::noop) — production has no bean, tests register one without mocking the host repository"

requirements-completed:
  - MUT-04
  - MUT-11

# Metrics
duration: 9min
completed: 2026-04-28
---

# Phase 11 Plan 05: MutationIntentRepository + MutationIntentCleanupJob Summary

**Wave 5 ships the idempotency reservation/replay backbone for the Phase 11 mutation tools. `MutationIntentRepository` reserves a `PENDING` row through an `agentstoreTransactionManager` `TransactionTemplate` (REQUIRES_NEW) before any host mutation, so the `(toolName, idempotencyKey, userUsername)` composite unique index serializes concurrent duplicate calls at the database boundary. Finalization methods (`markCommitted`, `markFailed`, `markCommitUnknown`) each reload the row by id and enforce a strict state machine — `markCommitUnknown` never downgrades a `COMMITTED` row, and `FAILED → PENDING` reclaim is `@Version`-guarded so only one retry can win. The `MutationIntentFailureProbe` `ObjectProvider` hook gives TEST-12 a clean COMMIT_UNKNOWN test seam without mocking the repository. `MutationIntentCleanupJob` runs `@Scheduled(cron="0 0 * * * *")` hourly under `@Transactional("agentstoreTransactionManager")`, deleting only `COMMITTED` / `FAILED` rows past `expiresAt`; stale `PENDING` and `COMMIT_UNKNOWN` rows are logged at WARN for operator triage and never auto-deleted (deleting them could allow duplicate host writes after a finalization failure).**

## Performance

- **Duration:** ~9 min (two-task plan; warm Gradle daemon — both tasks compiled in 2-5s)
- **Started:** 2026-04-28T20:34:23Z
- **Completed:** 2026-04-28T20:43:16Z
- **Tasks:** 2 / 2
- **Files modified:** 2 (both created — no existing files touched)
- **Per-task verification:** `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL after each commit

## Accomplishments

- **`MutationIntentRepository`** ships as a public `@Component` in `com.vn.agent.tools.mutation`, constructor-injecting `UnconstrainedDataManager`, `Metadata`, the qualified `agentstoreTransactionManager`, and an `ObjectProvider<MutationIntentFailureProbe>`:
  - `reserveOrReplay(toolName, idempotencyKey, userUsername, conversationId, requestHash, ttl)` — programmatic `TransactionTemplate` (REQUIRES_NEW) wrapping the reservation/replay branch; commit-time `DataAccessException` / `TransactionSystemException` / `PersistenceException` are caught around `execute(...)` and re-classified via a second `execute(...)` that re-reads the row.
  - `markCommitted(intent, resultEntityId, resultEntityName)` — `@Transactional(transactionManager="agentstoreTransactionManager", propagation=REQUIRES_NEW)`; reloads the intent by id, guards `current.getStatus() != PENDING`, fires `MutationIntentFailureProbe.beforeMarkCommitted(current)` (noop in production), then transitions `PENDING → COMMITTED` and saves.
  - `markFailed(intent, errorCode)` — same propagation/qualifier; reloads + `PENDING` guard; transitions `PENDING → FAILED`. Caller contract: only invoke for failures BEFORE host save returned.
  - `markCommitUnknown(intent, errorCode)` — same propagation/qualifier; reloads + early-return on `COMMITTED` (never downgrade) + `PENDING` guard; transitions `PENDING → COMMIT_UNKNOWN`.
  - `findExisting(toolName, idempotencyKey, userUsername)` — fluent `dataManager.load(AiMutationIntent.class).query(...)` lookup; auto-resolves `agentstore` via `@Store` on the entity class (per MEMORY `feedback_jmix_loadvalue_store`, the explicit `.store("agentstore")` rule applies only to raw-JPQL `loadValue/loadValues`, not the typed-class fluent API).
  - `deleteExpired(now)` — `@Transactional(REQUIRES_NEW)`; per-entity `dataManager.remove(...)` over `(status in COMMITTED, FAILED) and expiresAt < :now`. Returns count for logging.
  - `countExpiredInFlight(now)` — `@Transactional(readOnly=true)`; counts `PENDING / COMMIT_UNKNOWN` past `expiresAt` for the cleanup job's WARN log.
  - `validateIdempotencyKey(key)` — null/blank/non-UUID inputs throw `ToolUserError("parameter_conversion_error", "idempotencyKey must be a UUID", ["generate a fresh UUID idempotencyKey for this logical operation"])` BEFORE any database write.
  - `ReservationResult(state, intent)` record + `ReservationState` enum (`RESERVED`, `REPLAY`, `PENDING`, `VIOLATION`) — public API for `BuiltInMutationTools` (Wave 6).
  - `MutationIntentFailureProbe` interface — package-public test seam exposed via `ObjectProvider`. Production: no bean, defaults to `noop()`. TEST-12 will register a `@Bean` that throws inside `beforeMarkCommitted(...)` to drive the `COMMIT_UNKNOWN` path with real reservation/replay rows.
- **`MutationIntentCleanupJob`** ships as a public `@Component` in the same package:
  - `@Scheduled(cron = "0 0 * * * *")` — hourly at minute 0; relies on `@EnableScheduling` already in place on `AIConfiguration` (Plan 11-02).
  - `@Transactional("agentstoreTransactionManager")` — multi-store binding; ensures the bulk delete commits to the agentstore datasource where `AiMutationIntent` lives.
  - Body: invokes `repository.deleteExpired(now)` first, then `repository.countExpiredInFlight(now)`. Logs `removed > 0` at DEBUG and `staleInFlight > 0` at WARN with explicit "manual investigation required" wording.

## Task Commits

1. **Task 1: MutationIntentRepository** — `f860f5b` (feat)
2. **Task 2: MutationIntentCleanupJob** — `edce0ce` (feat)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java` — 288 lines including Javadoc and the public `ReservationResult` / `ReservationState` / `MutationIntentFailureProbe` API surface.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentCleanupJob.java` — 53 lines including Javadoc on the cleanup policy.

### Modified
- None.

## Decisions Made

- **`reserveOrReplay` uses `TransactionTemplate.execute(...)` instead of `@Transactional`** — the outer method body must catch commit-time `DataIntegrityViolationException` raised by the unique index. With a `@Transactional` proxy, that throw fires at proxy commit (after the method body returns), so the method body cannot catch it. Programmatic demarcation is the standard Spring pattern for this exact case.
- **Two separate `execute(...)` calls in the catch path** — the recovery branch needs its own transaction to re-read the existing row through `findExisting(...)`. Sharing the original transaction would also fail because the original has rolled back due to the unique-index violation.
- **`markCommitUnknown` no-ops on `COMMITTED`** — the "post-commit exception downgrade" hazard is real: `markCommitted` may successfully commit and then the post-`@Transactional` boundary throws (e.g. while the orchestrator's own audit write fails). Without this guard the catch path would call `markCommitUnknown` and demote a committed row.
- **FAILED-row reclaim is `@Version`-guarded, not pessimistic-locked** — `AiMutationIntent` has `@Version`. Two concurrent retries of a FAILED row will race on the `setStatus(PENDING)` save; the loser's transaction throws `OptimisticLockException`, falls into the outer catch, re-reads, and sees the now-PENDING row → returns `ReservationState.PENDING`. Pessimistic locking would serialize all reads through that row even when no contention exists.
- **`requestHash` check is BEFORE the status-based branch** — same key + different hash ALWAYS returns `VIOLATION`, including when the existing row is `FAILED`. This enforces the D-02 contract that corrected values after `validation_failed` or `parameter_conversion_error` must use a fresh idempotency key.
- **`countExpiredInFlight` returns `int` via `.list().size()` not a JPQL `count(...)`** — Jmix `dataManager.loadValue(...)` for raw count requires `.store("agentstore")` (MEMORY `feedback_jmix_loadvalue_store`); the fluent typed-list path auto-resolves the store and is type-safe at the cost of materializing the list. Expected stale count is small (otherwise the WARN log fires and operators investigate before the list ever grows large), so the trade-off favors readability and store auto-resolution.
- **`deleteExpired` iterates `dataManager.remove(intent)` instead of bulk JPQL DELETE** — entity listeners and any future Phase-9-style audit triggers run only on per-entity remove. The dedup table is small (24h TTL × peak request rate); the per-entity path is the safer default.
- **No locale captions added** — the cleanup job emits log lines only; there is no user-facing UI surface in v1.1. Tool-error locales (used by `BuiltInMutationTools`) land in Plan 11-06. CLAUDE.md "ALL locale files" applies to user-visible strings; English-only operator log messages are an established convention in this codebase (see `AuditWriter`, `LlmExposurePolicy`).
- **Package-public `MutationIntentFailureProbe`** — keeps the test seam internal to the package while still discoverable via `ObjectProvider`. Tests in `com.vn.agent.tools.mutation` can implement and register it; production code outside the package cannot, which prevents accidental misuse.

## Deviations from Plan

None — plan executed exactly as written. The plan's verbatim code skeleton compiled on first attempt; only minor stylistic adjustments were made (consolidate the two `OffsetDateTime.now()` calls in the cleanup job into a single `now` local so deleteExpired and countExpiredInFlight share the same timestamp; promote the `MutationIntentFailureProbe` interface from `interface` to `public interface` since it must be implementable by test classes outside the file). Both adjustments preserve every acceptance-criteria literal.

## Issues Encountered

None. Both tasks compiled on first attempt. JetBrains MCP `get_file_problems` is not available in this execution environment (per the execution_context note), so static-analysis triage is deferred to the next IntelliJ session.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** when next available, run on:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentCleanupJob.java`
  Gradle `:ai-agent:compileJava` exits 0 with zero ERROR-level Javac diagnostics, so this is a precautionary review only.

## User Setup Required

None — both classes ship as `@Component` and auto-discover via `@ComponentScan`. The cleanup job activates automatically because `@EnableScheduling` is already on `AIConfiguration` (Plan 11-02). No new properties; the 24h TTL comes from `AiAgentMutationProperties.resolvedIdempotencyTtl()` (Plan 11-02) and is passed by callers (Wave 6).

## Next Phase Readiness

- **Wave 6 (Plan 11-06: locales + MutationErrorTranslator)** can land structured error messages for each `ReservationState` (`RESERVED`, `REPLAY`, `PENDING`, `VIOLATION`) and the six D-04 error codes.
- **Wave 7+ (Plan 11-07*: BuiltInMutationTools)** can inject `MutationIntentRepository` and call:
  - `reserveOrReplay(...)` — pre-host-save, branch on `ReservationResult.state()`
  - `markCommitted(intent, resultEntityId, resultEntityName)` — after host `DataManager.save` returns
  - `markFailed(intent, errorCode)` — for failures BEFORE host save returned (validation, guard veto, type coercion)
  - `markCommitUnknown(intent, errorCode)` — for failures AFTER host save returned (orchestrator audit, finalization)
- **TEST-12 (concurrent-safe COMMIT_UNKNOWN test)** can register a `@Bean MutationIntentFailureProbe` that throws from `beforeMarkCommitted` to exercise the post-commit-exception hazard with real reservation/replay rows.
- No blockers for Plan 11-06.

## Self-Check: PASSED

All claimed files exist on disk; both task commits exist in `git log`.

- 2 / 2 files verified (`MutationIntentRepository.java`, `MutationIntentCleanupJob.java`)
- 2 / 2 commit hashes verified (`f860f5b`, `edce0ce`)
- `./gradlew :ai-agent:compileJava` exits 0

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-28*
