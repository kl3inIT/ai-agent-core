---
phase: 17-mutation-internals-hardening-phase-11-follow-up
plan: 04
subsystem: mutation-gate-chain
tags: [mutation, gate-chain, sealed-request, fail-closed, thin-adapter, MUT-15, MUT-18-parity]
requires:
  - "Plan 17-01 MUT-15 gate-order/save-after-gates/no-@Transactional reflection invariants (RED until this plan)"
  - "Plan 17-03 MutationAttributeBinder.prefetchReferences + 3-arg coerceAttributes + MutationSaveExecutor.bulkSave setDiscardSaved (MUT-16 O(1) batch contract)"
  - "Phase 11 mutation collaborators (MutationAuthorizationService, MutationCommitCoordinator, MutationIntentRepository, MutationErrorTranslator, RelatedWriteMetadataResolver, DiffSerializer, MutationSaveExecutor)"
provides:
  - "MutationGateChain @Component — one canonical fail-closed gate spine (enforceRole/resolve/authorize/reserve/coerce/guard/save/finalize) with a single execute(MutationRequest) entry point; NO @Transactional"
  - "Sealed MutationRequest hierarchy (Create/Update/AddRelated/RemoveRelated/Bulk)"
  - "BuiltInMutationTools five @Tool methods reduced to thin adapters over mutationGateChain.execute(...)"
affects:
  - "Phase 18 perf pass (the consolidated MutationGateChain + shared batch-FK load is now the single optimization target; the resolve gate is the memo seam, D-05)"
tech-stack:
  added: []
  patterns:
    - "Template-method gate spine: one ordered execute() calling named private gates; source-level order enforced by indexOf invariant test (T-17-01)"
    - "Sealed-interface request dispatch via exhaustive switch (compiler-enforced exhaustiveness; mirrors FilterNode/StreamingEvent)"
    - "Per-execute mutable Context (static nested class) — never singleton instance fields (T-17-08)"
    - "Single try/catch ladder with AccessDeniedException arm BEFORE ToolUserError; bulk-only row_access_denied (T-17-09)"
key-files:
  created:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequest.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java"
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java"
decisions:
  - "Related-write child-id parse + ensureInverseClearable moved into the authorize() gate (BEFORE reserve), preserving the inline gate ORDER — a bad relatedId / non-clearable inverse throws before any idempotency row is reserved, so markFailedIfReserved sees no reservation exactly as v1.1 did."
  - "Related-write parent/child loads + childBelongsToDifferentParent + MutationGuard.check live in the guard() gate (after reserve, before save) so guards still see typed loaded entity refs (D-03), matching the inline ordering."
  - "AiUiSettingsResolver injected into the chain (not listed in the plan's collaborator subset) because the inline TTL (resolveMutationIdempotencyTtlSeconds) and bulk DoS cap (resolveMutationBulkMaxRows) reads are part of the lifted gate behavior — Rule 3 (required to preserve parity)."
  - "Per-execute Context is a private static nested class with all per-call state (commitState/reservation/failedRowIndex/startedAt/userUsername/metaClass + per-variant hand-off fields); concurrency-safe on the singleton."
metrics:
  duration: "~40 min"
  completed: "2026-05-31"
  tasks: 3
  files: 3
---

# Phase 17 Plan 04: Extract MutationGateChain Summary

Lifted the fail-closed mutation gate sequence — previously hand-written inline four times in `BuiltInMutationTools` (create/update, shared related-write, bulk) — into ONE canonical `MutationGateChain` `@Component` with a single `execute(MutationRequest)` entry point and eight ordered named private gates; the five `@Tool` methods are now thin adapters that build a sealed `MutationRequest` variant and return `mutationGateChain.execute(...)`. Behavior-frozen: the three intentionally-RED MUT-15 invariants flip GREEN without weakening, and the full Phase 9/10/11/13 mutation + performance suites pass with zero test-body edits (MUT-18 parity).

## What Was Built

- **Task 1 — `MutationRequest.java`:** `sealed interface MutationRequest permits Create, Update, AddRelated, RemoveRelated, Bulk` with nested records, each carrying only its tool's divergent inputs plus `idempotencyKey` (exposed via the shared `idempotencyKey()` accessor for the reserve gate). Class javadoc cites `FilterNode`'s compiler-enforced-exhaustiveness rationale. Pure model file — no `@Component`, no `@JsonTypeInfo`.
- **Task 2 — `MutationGateChain.java`:** a plain `@Component` (NO `@Transactional`, NO `@ConditionalOnProperty`) replicating `MutationSaveExecutor`'s self-invocation-pitfall javadoc. `execute(MutationRequest)` runs ONE try/catch whose body calls the gates in strictly increasing source order: `enforceRole` &rarr; `resolve` &rarr; `authorize` &rarr; `reserve` (early-return on non-RESERVED via `handleReservationResult`) &rarr; `coerce` &rarr; `guard` &rarr; `save` &rarr; `finalize`. `save` is the ONLY place `mutationSaveExecutor.*` is called and it sits after `guard(`. Per-variant divergence dispatched via exhaustive `switch` on the sealed type. The bulk path preserves the MUT-16 contract verbatim: ONE `prefetchReferences(metaClass, rowAttrsInOrder)` before the per-row loop, 3-arg `coerceAttributes(...prefetchedReferences)` per row, per-row `CrudEntityContext` + two `AccessDeniedException` throws, `setDiscardSaved`-backed `bulkSave`, savedIds read from in-memory `@JmixGeneratedValue` UUIDs. Catch ladder: `ToolVetoedException` &rarr; BLOCKED/`mutation_guard_vetoed`; `AccessDeniedException` &rarr; BLOCKED/`row_access_denied` (Bulk only reaches it); `ToolUserError` &rarr; ERROR; `Throwable` &rarr; `translateThrowableAfterReservation` + `auditOutcome`. All audit writes route through `mutationCommitCoordinator` (no direct `auditWriter.writeToolCall`).
- **Task 3 — `BuiltInMutationTools` adapters:** the five `@Tool` methods now build the matching `MutationRequest` variant and `return mutationGateChain.execute(request)`. The 18-field constructor collapsed to a single `MutationGateChain` injection; `executeRelatedWrite`, `resolveIdempotencyTtl`, and all inline gate logic removed. `@Tool`/`@ToolParam` descriptions and parameter names are byte-identical (frozen model-facing contract); `@ConditionalOnProperty` + the no-`@Transactional` class javadoc remain. No `AuditWriter` field/usage.

## Verification Results

| Test | Result | Notes |
|------|--------|-------|
| `:ai-agent:compileJava` | BUILD SUCCESSFUL | after each of the 3 tasks |
| `MutationToolInvariantsTest` | 8/8 green | the 3 MUT-15 `mutationGateChain_*` seams (gate order, save-after-gates, no-`@Transactional` reflection) now GREEN; 5 original Plan 11-07C / MUT-16 assertions stay green |
| `com.vn.agent.tools.mutation.*` + `com.vn.agent.performance.*` | 106 tests, 0 failures, 0 errors, 2 skipped | MUT-16 `MutationFkBatchLoadQueryCountTest` (slope ≤ 1) + all bulk-save parity / partial-failure / idempotency / guard-veto / related-write tests pass with ZERO test-body edits (MUT-18 parity); 2 skipped are pre-existing `@Disabled` scaffolds |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Injected `AiUiSettingsResolver` into `MutationGateChain`**
- **Found during:** Task 2
- **Issue:** The plan's collaborator list did not name `AiUiSettingsResolver`, but the inline gate behavior reads the idempotency TTL (`resolveMutationIdempotencyTtlSeconds`) and the bulk DoS cap (`resolveMutationBulkMaxRows`) through it. Omitting it would either drop the Phase 16 admin-editable knobs or fail to compile the lifted code.
- **Fix:** Added `AiUiSettingsResolver` to the chain constructor and moved `resolveIdempotencyTtl()` into the chain; bulk DoS-cap read stays in the `resolveBulk` gate. Behavior-identical to the inline reads.
- **Files modified:** `MutationGateChain.java`
- **Commit:** 5c321a5

**2. [Rule 1 - Bug] Class javadoc string `mutationSaveExecutor.*(...)` tripped the save-after-gates token scan**
- **Found during:** Task 2 (first invariant run)
- **Issue:** `mutationGateChain_saveTokenAppearsAfterAllGateTokens` does `src.indexOf("mutationSaveExecutor.")` and requires it AFTER `guard(`. Two class-javadoc lines contained the literal `mutationSaveExecutor.` (in `{@code ...}` prose) which appears BEFORE the gate spine, so the first occurrence was in the javadoc — failing an otherwise-correct implementation.
- **Fix:** Rephrased the javadoc to say "the save delegate" / `{@link MutationSaveExecutor#save}` without the trailing-dot token. The code path is unchanged; the only `mutationSaveExecutor.` call sites are now inside the `save` gate methods, after `guard(`. Scan GREEN.
- **Files modified:** `MutationGateChain.java` (javadoc only)
- **Commit:** 5c321a5 (folded into the task commit)

### Design choices (parity-preserving, plan-sanctioned)

- Related-write child-id parse + `ensureInverseClearable` placed in `authorize()` (BEFORE `reserve`), and parent/child loads + `childBelongsToDifferentParent` + `MutationGuard.check` placed in `guard()` (after `reserve`, before `save`) — this mirrors the inline `executeRelatedWrite` ordering exactly, so reservation timing and `markFailedIfReserved` behavior are unchanged.
- Per-execute mutable state lives in a private static nested `Context` (never instance fields) per T-17-08.

## Threat Model Compliance

- **T-17-01 (mitigate):** Gate tokens appear in strictly-increasing source order and `mutationSaveExecutor.` appears after `guard(` — both proven GREEN by `MutationToolInvariantsTest`. A reorder that lets a save precede an authorization gate fails the build.
- **T-17-07 (mitigate):** `MutationGateChain` carries NO `@Transactional` (class or method) — proven by the reflection invariant. The sole transactional boundary remains `MutationSaveExecutor`.
- **T-17-08 (mitigate):** All per-call state is in the per-`execute` `Context`; no singleton instance fields hold commit/reservation state.
- **T-17-09 (mitigate):** The `AccessDeniedException` arm is BEFORE `ToolUserError`, routes Bulk to BLOCKED/`row_access_denied`, and the generic `Throwable` arm does not swallow it; single-row tools keep their three-arm ladder. Locked GREEN by the unchanged `BulkSavePartialFailureTest`.
- **T-17-SC (accept):** No package installs in this plan.

## Known Stubs

None. This is a behavior-frozen refactor — every gate path is wired to its existing collaborator; no placeholder data or unwired components introduced.

## No new threat surface

This plan moves existing production code between classes (extraction) and adds one pure model file. No new network endpoints, auth paths, file access, or schema changes at trust boundaries. The single transactional save boundary, the constrained-DataManager FK batch path, and all error/audit/idempotency shapes are byte-identical to v1.1.

## Self-Check: PASSED

- `MutationRequest.java`, `MutationGateChain.java` exist on disk; `BuiltInMutationTools.java` modified.
- Commits 58f672d (Task 1), 5c321a5 (Task 2), 86cef20 (Task 3) present in git history.
- `MutationGateChain.java` contains `execute`, `sealed`-dispatch switches, and `mutationSaveExecutor.` only inside the save gate; `MutationRequest.java` contains `sealed interface MutationRequest`; `BuiltInMutationTools.java` contains `mutationGateChain.execute` per tool and no ` AuditWriter ` field.
