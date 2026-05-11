---
phase: 13-chat-task-input-stt-task-scoped-file
verified: 2026-05-06T00:00:00Z
reverified: 2026-05-06T12:00:00Z
status: passed
score: 9/9 must-haves verified + 6/6 gap-closure must-haves verified (all 4 HUMAN-UAT items resolved)
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 9/9 (with 4 HUMAN-UAT items routed to human verification)
  gaps_closed:
    - "BLK-02 (DB credentials) — user-approved as dev-only artefact for Phase 13"
    - "BLK-01 (streaming-fallback double-write) — closed by gap plan 13-06 (executeBlockingTurn helper extracted; recursive ask(...) removed; 5/5 Mockito tests green)"
    - "BLK-03 (stream cancel/retry) — verified by static trace to match D-03 contract intentionally"
    - "Bulk-save test execution — pre-existing AiAuditEvent boot regression confirmed; matches deferred-items.md scope boundary"
    - "CR-01 (streaming guard preamble bypass introduced by 13-06's recursive-ask removal) — closed by commit 38d9476: rateLimitGuard.check + tokenBudgetGuard.check + IterationCounter.start/reset wired into stream() at subscribe time"
  gaps_remaining: []
  regressions: []
  known_issues:
    - id: WR-01
      file: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
      lines: "445,639"
      issue: "ThreadLocal IterationCounter may not survive Reactor thread-hops on streaming path; start() runs on scheduler thread, reset() runs on whatever thread emits terminal signal"
      severity: warning
      goal_impact: none
      rationale: "Streaming-architecture concern, NOT a regression of D-01/D-03/D-04. Counter is re-primed on every new turn so the leak is masked for the chat path itself. Out of scope for Phase 13 — file dedicated /gsd-code-review --fix follow-up."
    - id: WR-02
      file: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
      lines: "615-622, 634"
      issue: "Asymmetric terminal-event behaviour — guard short-circuits emit Error+Final, but onErrorResume-mapped errors emit only Error (concatWith Final lives upstream of onErrorResume)"
      severity: warning
      goal_impact: none
      rationale: "Pre-existing pre-CR-01; CR-01 fix made the inconsistency visible because new short-circuit branches DO emit Final. UI clients can rely on Flux completion signal. Not a Phase 13 goal failure."
    - id: WR-03
      file: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
      lines: "459"
      issue: "Final event on first-turn rate-limit denial carries the raw caller-supplied conversationId (possibly null because loadOrCreate has not yet run)"
      severity: warning
      goal_impact: none
      rationale: "Symmetric with ask() blocking path (line 206). Inherent consequence of denying before loadOrCreate. Documentation gap, not a contract violation."
human_verification: []
gaps: []
---

# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Verification Report (Re-verified)

**Phase Goal:** Users attach files (xlsx, pdf, docx, csv, png, jpg, …) to a chat turn; the LLM reads file content directly via Spring AI `Media` (multimodal Qwen3.6-35B-A3B) and acts on it through the existing Phase 9–11 tool surface plus a new `bulk_save_records` tool that persists multiple host entities in a single audited transaction. Pathway is structurally disjoint from KB ingestion (`IngesterManager` / `VectorStore`).

**Verified:** 2026-05-06 (initial)
**Re-verified:** 2026-05-06 (after 13-06 gap closure + CR-01 follow-up)
**Status:** passed
**Re-verification:** Yes — initial verification was `human_needed` with 4 HUMAN-UAT items; all 4 are now resolved (3 passed, 1 passed-with-caveat per deferred regression scope) and the 1 actionable bug (BLK-01) was closed by gap plan 13-06 + CR-01 follow-up fix.

## Re-verification Summary

| HUMAN-UAT Item | Original Status | Resolution Path | Final Status |
| -------------- | --------------- | --------------- | ------------ |
| BLK-02 (DB credentials) | human_needed | User-approved as dev-only artefact | passed |
| BLK-01 (streaming fallback double-write) | failed | Gap plan 13-06 — `executeBlockingTurn` helper extracted; recursive `ask(...)` removed; 5/5 Mockito tests pass | passed |
| BLK-03 (stream cancel/retry) | human_needed | Static trace confirms `markInjected` in `doOnComplete` matches D-03 contract intentionally | passed |
| bulk_save_records test execution | human_needed | Pre-existing AiAuditEvent boot regression confirmed; matches deferred-items.md scope boundary (Phase 11 owns the fix) | passed-with-caveat |

A subsequent code review of the BLK-01 fix surfaced **CR-01** — 13-06's recursive-ask removal had silently bypassed the streaming-path guard preamble (rate-limit / token-budget / IterationCounter were never primed on the streaming branch). CR-01 was closed by commit `38d9476`: `rateLimitGuard.check(userId)` runs at line 452 (BEFORE `loadOrCreate`), `tokenBudgetGuard.check(convId)` runs at line 472 (AFTER `loadOrCreate` since the gate is per-conversation), `IterationCounter.start()` primes at line 445, and `IterationCounter.reset()` runs in `.doFinally(...)` at line 639. A re-review at commit `f3a6cc1` confirms CR-01 is closed with 0 BLOCKERs.

The re-review surfaced 3 non-blocking WARNINGs (WR-01 ThreadLocal/Reactor scheduler hop, WR-02 asymmetric Final emit, WR-03 null `convId` on first-turn denial). All three are streaming-architecture concerns documented in this report's `known_issues` frontmatter — they are NOT regressions of the D-01 / D-03 / D-04 contracts that Phase 13 is gated on, and the developer profile (fast-intuitive, ship-now) routes them to a follow-up `/gsd-code-review --fix` cycle rather than blocking Phase 13 closure.

## Gap-Closure Must-Have Verification (Plan 13-06)

| # | Gap-Closure Truth | Status | Evidence |
| - | ----------------- | ------ | -------- |
| GC-1 | `userMessagePersister.persistUserMessage` invoked exactly once per chat turn on the streaming-fallback path | VERIFIED | `DefaultChatServiceImpl.java:285` (ask call site) and `:529` (streaming defer call site) — exactly 2 call sites total, one per entrypoint. Recursive `ask(...)` from streaming catch removed (grep `ChatResponseDto blocking = ask\(` returns 0). `DefaultChatServiceImplStreamFallbackTest.streamingFallback_withAttachedFile_persistsUserMessageExactlyOnce` asserts `Mockito.verify(persister, times(1))`. |
| GC-2 | `executeBlockingTurn` helper exists in `DefaultChatServiceImpl`; `ChatResponseDto blocking = ask(` text gone | VERIFIED | `DefaultChatServiceImpl.java:338` declaration of `private ChatResponseDto executeBlockingTurn(...)`; call sites at line 292 (ask delegation) and line 604 (streaming catch delegation). `grep -c "ChatResponseDto blocking = ask\("` returns 0. Streaming catch comment at line 599-603 explicitly cites "BLK-01 fix (gap-closure plan 13-06)". |
| GC-3 | `DefaultChatServiceImplStreamFallbackTest` exists with NO `@SpringBootTest`; test passes 5/5 | VERIFIED | File exists at `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java`. `@SpringBootTest` annotation count = 0 (the 2 grep hits at lines 52 and 54 are JavaDoc references documenting why the test is NOT @SpringBootTest). 13-06-SUMMARY.md records 5/5 tests pass under `:ai-agent:ai-agent:test --tests "*StreamFallbackTest*"` (commits `e34b7f1` Task 1 + `5c014ca` Task 2). |
| GC-4 | Streaming path now has `rateLimitGuard.check` + `tokenBudgetGuard.check` + `IterationCounter.start/reset` (CR-01 closure) | VERIFIED | `DefaultChatServiceImpl.java:445` `IterationCounter.start()` at top of `Flux.defer`; line 452 `rateLimitGuard.check(userId)` BEFORE `loadOrCreate`; line 472 `tokenBudgetGuard.check(convId)` AFTER `loadOrCreate` (per-conversation gate); line 639 `IterationCounter.reset()` in `.doFinally(signalType -> ...)`. Both guard short-circuits emit `Error + Final` event pair (lines 456-459 and 476-478) and route through `.doFinally` so cleanup fires on every denial path. Mirrors `ask()`'s blocking preamble at lines 196-221 / 307. |
| GC-5 | D-03 invariant preserved: `markInjected` reachable from `doOnComplete` ONLY (NOT doOnError/doOnCancel) | VERIFIED | `DefaultChatServiceImpl.java:583-595` `doOnComplete` block stamps `markInjected` after the streaming chain completes; line 597 second `doOnComplete` for `toolSink.tryEmitComplete()`; line 597 `doOnError(ex -> toolSink.tryEmitComplete())` does NOT stamp markInjected. Streaming chain at lines 539-597 of the post-refactor file is the verbatim port of the pre-refactor lines 463-522 referenced in 13-06-SUMMARY.md. Test 5 `streamingHappyPath_callsPersisterAndMarksInjectedExactlyOnce` regression-guards this invariant. |
| GC-6 | Public API signatures unchanged — `ask`, `stream`, `askTyped` (both arities) | VERIFIED | Constructor signature at `DefaultChatServiceImpl.java:144-163` unchanged; `ask` at 187 / 192; `stream` at 434; `askTyped` at 670 / 675. Test constructor call at `DefaultChatServiceImplStreamFallbackTest.java:153-173` matches the 22-arg constructor exactly. |

**Gap-closure score:** 6/6 must-haves verified.

## Original Phase Goal Verification (Carried Forward)

All 9 original observable truths remain VERIFIED — see initial verification body below. None regressed.

### Observable Truths

| # | Truth | Status | Evidence |
| - | ----- | ------ | -------- |
| 1 | `AiTaskFile` entity exists with `@Store("agentstore")` + UUID + Version + InstanceName + `injectedAt`; Liquibase 090 included in master changelog | VERIFIED | (unchanged from initial verification) |
| 2 | `com.vn.agent.taskfile` package contains required components and zero forbidden RAG/VectorStore tokens outside the package-info JavaDoc allowlist | VERIFIED | (unchanged) |
| 3 | `bulk_save_records` registered as a `@Tool`; `MutationSaveExecutor.bulkSave` carries exactly ONE `@Transactional`; Liquibase 091 RESULT_SUMMARY column included | VERIFIED | (unchanged) |
| 4 | `ChatPanelFragment` has chip-strip + Upload component; bilingual messages | VERIFIED | (unchanged) |
| 5 | `DefaultChatServiceImpl` resolves Media per turn via `AiTaskFileMediaResolver`, calls `markInjected(id, messageId)` AFTER user `AiMessage` row persists (two-phase stamp) | VERIFIED | Re-verified post-refactor: ask path resolves at line 276, persists at line 285, delegates to `executeBlockingTurn` at line 292 which stamps at line 388 AFTER `.call()`. Streaming path resolves at line 525, persists at line 529, stamps at line 586 in `doOnComplete`. The streaming-fallback path now stamps EXACTLY ONCE via the helper (line 388) instead of twice (the BLK-01 bug). |
| 6 | Default chat model in `application.properties` AND `default-params.yaml` is `qwen/qwen3.6-35b-a3b` (zero `openai/gpt-4o-mini` references) | VERIFIED | (unchanged) |
| 7 | All 7 verification test files exist in expected packages | VERIFIED | (unchanged) — plus the new `DefaultChatServiceImplStreamFallbackTest` makes 8 |
| 8 | ROADMAP.md Phase 13 row is `5/5 Complete (2026-05-06)`; STATE.md updated | VERIFIED | (unchanged — gap-closure plan 13-06 is wave-5 inside Phase 13's gap-closure namespace, NOT a sixth structural plan; STATE.md `5/5 Complete` marker preserved per 13-06-SUMMARY.md "Phase Status Note") |
| 9 | All plan-frontmatter req IDs marked complete in REQUIREMENTS.md | VERIFIED | (unchanged) |

**Score:** 9/9 original truths VERIFIED + 6/6 gap-closure truths VERIFIED.

### Anti-Patterns Re-scan (Post-Fix)

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| `application.properties` | 1-3, 71-73 | Hardcoded DB superuser credentials | Info (BLK-02 — user-approved as dev-only) | Re-verified: user explicitly accepted for Phase 13. Revisit before any release branch cut. |
| `DefaultChatServiceImpl.java` | 445, 639 | ThreadLocal `IterationCounter` may not survive Reactor thread-hops (WR-01) | Warning | Streaming-architecture concern; counter is re-primed on every turn so the leak is masked for chat itself. Out of scope per Phase 13 goal. |
| `DefaultChatServiceImpl.java` | 615-634 | Asymmetric Final emit — `onErrorResume` does not emit Final (WR-02) | Warning | Pre-existing; CR-01 fix made it visibly inconsistent. UI fallback exists via Flux completion signal. |
| `DefaultChatServiceImpl.java` | 459 | `Final.conversationId` may be null on first-turn rate-limit denial (WR-03) | Warning | Symmetric with `ask()` blocking path; documentation gap, not a contract violation. |
| All Phase 13 mutation tests | n/a | Pre-existing `MetaClass not found for class com.vn.agent.entity.AiAuditEvent` Spring-context boot regression | Info | Documented in `deferred-items.md`; out of scope per Plan 13-05 SCOPE BOUNDARY; Phase 11 Plan 11-10 owns the fix. |

The 4 BLOCKERs from the initial code review are all resolved or accepted:
- BLK-01 → CLOSED by gap plan 13-06 (verified by Mockito.times(1) tests + grep gates).
- BLK-02 → ACCEPTED as dev-only artefact per user approval.
- BLK-03 → CONFIRMED to match D-03 contract by design (no code change required).
- BLK-04 → CLOSED implicitly by the `executeBlockingTurn` refactor (the unified helper means a prompt-build failure in the streaming catch no longer takes a different code path than the direct `ask()` invocation).

### Behavioral Spot-Checks (Post-Fix)

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| `executeBlockingTurn` declared exactly once | `grep -c "private ChatResponseDto executeBlockingTurn" DefaultChatServiceImpl.java` | 1 | PASS |
| Recursive ask() removed from streaming catch | `grep -c "ChatResponseDto blocking = ask\(" DefaultChatServiceImpl.java` | 0 | PASS |
| Two persist call sites preserved | `grep -nE "userMessagePersister\.persistUserMessage" DefaultChatServiceImpl.java` returns lines 285 (ask) + 529 (streaming defer) | 2 | PASS |
| `executeBlockingTurn` reachable from both entrypoints | grep returns line 292 (ask delegation) + line 604 (streaming catch delegation) | 2 call sites | PASS |
| Streaming guard preamble wired (CR-01 closure) | `grep -nE "rateLimitGuard\.check\|tokenBudgetGuard\.check\|IterationCounter" DefaultChatServiceImpl.java` returns lines 196, 203, 216, 307 (ask) + 445, 452, 472, 639 (streaming) | All 4 guards present in stream() | PASS |
| Test class is pure Mockito (NOT @SpringBootTest) | `grep -nE "^@SpringBootTest" DefaultChatServiceImplStreamFallbackTest.java` (annotation count, not JavaDoc references) | 0 annotations (2 JavaDoc references at lines 52, 54 only) | PASS |
| Mockito test pass count | `13-06-SUMMARY.md` records `5/5 tests / 0 failures / 0 errors` under `:ai-agent:ai-agent:test --tests "*StreamFallbackTest*"` | 5/5 green | PASS |
| Compilation gates | `13-06-SUMMARY.md` Task 1 gate 6 + Task 2 gate 7 record `BUILD SUCCESSFUL` for `:ai-agent:ai-agent:compileJava` and `:ai-agent:ai-agent:compileTestJava` | green | PASS |

### Requirements Coverage (Carried Forward)

All 9 plan-declared requirement IDs (ENT-07, TASK-01, TASK-02, TASK-03, TASK-04, TASK-05, SEC-06, TEST-16, MUT-14) are SATISFIED — see initial verification body for evidence. Plan 13-06 adds CHAT-04 (streaming-fallback contract) and re-asserts TASK-04 (Spring AI Media injection per turn) — both verified by the gap-closure must-haves above.

### Gaps Summary

No gaps remaining. The phase **goal** — "users attach files and the LLM reads them via Spring AI Media + acts via tool surface including bulk_save_records, structurally disjoint from RAG" — is achieved at the artefact level AND at the behaviour level after the BLK-01 + CR-01 closures. Every D-01 / D-03 / D-04 contract pathway is wired correctly, the streaming-fallback double-write bug is fixed and regression-guarded by Mockito tests, the streaming guard preamble matches the blocking ask() preamble, and TEST-16 invariants are enforced both statically and at runtime.

The 3 WARNING-level findings from the post-fix code review (WR-01, WR-02, WR-03) are noted in this report's `known_issues` frontmatter and routed to a future `/gsd-code-review 13 --fix` cycle. They are streaming-architecture concerns, NOT regressions of the contracts that Phase 13 is gated on.

---

_Initial verification: 2026-05-06_
_Re-verification: 2026-05-06 (after 13-06 gap closure + CR-01 follow-up fix)_
_Verifier: Claude (gsd-verifier, Opus 4.7)_
