---
phase: 13-chat-task-input-stt-task-scoped-file
plan: 06
plan_id: "13-06"
subsystem: chat-service
tags:
  - gap-closure
  - streaming-fallback
  - chat-memory
  - duplicate-write
  - blk-01
  - blk-04
gap_closure: true
gap_source: HUMAN-UAT.md
requires:
  - 13-04   # Plan 04 introduced Resolved record + UserMessagePersister + markInjected
  - 13-05   # verification surface (HUMAN-UAT) flagged BLK-01
provides:
  - DefaultChatServiceImpl.executeBlockingTurn   # private helper, single owner of blocking-turn logic
  - DefaultChatServiceImplStreamFallbackTest     # Mockito unit test asserting single persist+markInjected on fallback
affects:
  - DefaultChatServiceImpl
tech-stack:
  added: []
  patterns:
    - "pre-resolved-state pass-through helper (Path A)"
    - "Mockito RETURNS_DEEP_STUBS for ChatClient fluent builder traversal"
key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
decisions:
  - "Path A (pre-resolved-state pass-through) chosen over Path B (state-flag in ask)."
  - "Helper takes Resolved + userMessageId as parameters; ask() owns the resolve+persist preamble; streaming catch reuses values already in scope inside Flux.defer."
  - "Test uses Flux.blockLast() (no reactor-test on classpath) — Schedulers.immediate() drives synchronous execution."
  - "BLK-04 (prompt-build orphan) closed implicitly by the refactor; no separate task per gap_closure_scope guidance."
metrics:
  duration: "~22 min"
  tasks_completed: 2
  files_changed: 2
  date: "2026-05-06"
requirements:
  - TASK-04
  - CHAT-04
---

# Phase 13 Plan 06: BLK-01 Gap Closure — Streaming-Fallback Double-Write Fix Summary

Eliminates the duplicate user `AiMessage` row that occurred on the D-04 graceful streaming-fallback path when a chat model throws `UnsupportedOperationException` from `chatClient.prompt()...stream()` AND the user has attached task files, by extracting a shared `executeBlockingTurn` helper that both `ask()` and the streaming catch delegate to without re-resolving Media or re-persisting the user message.

## Objective

Close BLK-01 from `13-HUMAN-UAT.md` (streaming-fallback path persisting the user message twice per turn, polluting `ChatMemory` replay and inflating prompt size). Preserve all D-01, D-03, D-04 invariants from `13-CONTEXT.md`.

## What Shipped

### Task 1 — Refactor `DefaultChatServiceImpl` (commit `e34b7f1`)

Added private helper `executeBlockingTurn(...)` that accepts already-resolved state (`Resolved resolvedMedia`, `UUID userMessageIdAlreadyPersisted`, plus the composed prompt parameters) and runs the blocking `chatClient.prompt()...call()` + `markInjected` + DTO assembly block.

- `ask(...)` (lines 192-296 post-refactor) keeps its resolve+persist preamble (lines 271-289) and now delegates to `executeBlockingTurn(...)` once at the end.
- `stream(...)` catch on `UnsupportedOperationException` (was lines 523-531) now calls `executeBlockingTurn(userId, convId, message, effectiveOverrides, resolvedMedia, userMessageIdRef.get(), composedSystemPrompt, model, active, ragFilter, retrievalTopK, retrievalSimilarityThreshold, runId, startNanos)` directly — reusing the Media + user-message id already produced by the streaming `Flux.defer` block (lines 449-460).
- The streaming-success branch (`chatClient.prompt()...stream()...doOnComplete(markInjected)`) at lines 463-522 of the pre-refactor file is preserved verbatim — D-03 cancelled-stream-leaves-pending contract intact.

### Task 2 — `DefaultChatServiceImplStreamFallbackTest` (commit `5c014ca`)

Pure JUnit 5 + Mockito unit test (NOT `@SpringBootTest`) covering:

| # | Test | Assertion |
| - | ---- | --------- |
| 1 | `streamingFallback_withAttachedFile_persistsUserMessageExactlyOnce` | `verify(userMessagePersister, times(1)).persistUserMessage(...)` |
| 2 | `streamingFallback_withAttachedFile_marksInjectedExactlyOnce` | `verify(taskFileRepository, times(1)).markInjected(...)` |
| 3 | `streamingFallback_withAttachedFile_resolvesMediaExactlyOnce` | `verify(taskFileMediaResolver, times(1)).resolvePending(convId)` |
| 4 | `streamingFallback_withNoAttachedFile_doesNotCallPersisterOrMarkInjected` | both `never()` — degenerate-case guard |
| 5 | `streamingHappyPath_callsPersisterAndMarksInjectedExactlyOnce` | streaming-success path: both `times(1)` — D-03 doOnComplete invariant guard |

Result: 5 tests / 0 failures / 0 errors under `:ai-agent:ai-agent:test --tests "*StreamFallbackTest*"`.

## Verify Gate Results

### Task 1 gates (all green)

| # | Gate | Expected | Actual |
| - | ---- | -------- | ------ |
| 1 | `private ChatResponseDto executeBlockingTurn` declarations | 1 | 1 |
| 2 | `executeBlockingTurn(` lines (1 decl + 2 call sites + 1 comment) | 4 lines | 4 (decl @ 338, call @ 292 in `ask`, call @ 570 in streaming catch, comment @ 567) |
| 3 | `ChatResponseDto blocking = ask(` count | 0 | 0 |
| 4 | `userMessagePersister.persistUserMessage` non-comment sites | 2 | 2 (line 285 `ask`, line 495 streaming defer) |
| 5 | `taskFileMediaResolver.resolvePending` non-comment sites | 2 | 2 (line 276 `ask`, line 491 streaming defer) |
| 6 | `:ai-agent:ai-agent:compileJava` | BUILD SUCCESSFUL | green |

### Task 2 gates (all green)

| # | Gate | Expected | Actual |
| - | ---- | -------- | ------ |
| 1 | test class declaration | 1 | 1 |
| 2 | `verify(userMessagePersister, times(1))` count | >= 1 | 2 |
| 3 | `verify(taskFileRepository, times(1)).markInjected` count | >= 1 | 2 |
| 4 | `verify(taskFileMediaResolver, times(1)).resolvePending` count | >= 1 | 1 |
| 5 | `@SpringBootTest` count | 0 | 0 |
| 6 | `UnsupportedOperationException` references | >= 1 | 3 |
| 7 | test pass | 5/5 green | 5/5 (0 failures, 0 errors) |

## Invariants Preserved (Verified Manually)

- **D-01 single-turn-inject:** Media injects exactly once per turn — proven by Test 2 + Test 5.
- **D-03 cancelled-stream-leaves-pending:** `markInjected` stays in `doOnComplete` only on the streaming-success path; the streaming chain at lines 463-522 (pre-refactor numbering) was untouched. Test 5 regression-guards the doOnComplete-fired markInjected.
- **D-04 graceful streaming fallback:** Provider throwing `UnsupportedOperationException` on `.stream()` STILL produces a working blocking response wrapped as a single `Content` chunk + `Final`. Tests 1-3 exercise this path; the response is delivered without recursing through `ask()`.
- **Two-phase stamp ordering in `ask()`:** resolve → persist user message → call → markInjected after persist. Lines 271-289 of `ask()` left intact; the helper trusts the caller has done resolve+persist.
- **Public API unchanged:** `ChatService.ask(...)` and `ChatService.stream(...)` signatures identical. Helper is private.

## Deviations from Plan

None — plan executed exactly as written, with three benign adaptations driven by the project environment (CLAUDE.md "verify build/test-sensitive changes"):

1. **`reactor-test` not on classpath** — plan example used `StepVerifier.create(...).thenConsumeWhile(...).verifyComplete()`. Replaced with `sut.stream(...).blockLast()` driven by `Schedulers.immediate()`. Same semantics (drive Flux to terminal completion synchronously) without adding a new dependency. Documented inline.
2. **`ChatClient.user(...)` overload is ambiguous under `any()`** — Mockito's `any()` cannot disambiguate between `user(Resource)` and `user(Consumer<PromptUserSpec>)`. Used `any(java.util.function.Consumer.class)` to bind the matcher to the lambda overload (the one `DefaultChatServiceImpl` actually invokes).
3. **Helper insertion location** — placed `executeBlockingTurn` AFTER the `ask(...)` method body and BEFORE `stream(...)`, exactly as the plan suggested.

## Closed Bugs

| ID | Source | Status |
| -- | ------ | ------ |
| BLK-01 | `13-HUMAN-UAT.md` ## Gaps | Closed by Task 1+2 — duplicate user `AiMessage` write eliminated; Mockito.verify(times(1)) gates fail-fast in CI. |
| BLK-04 | `13-REVIEW.md` (prompt-build orphan) | Closed implicitly — the unified `executeBlockingTurn` owner means a prompt-build failure in the streaming catch no longer takes a different code path than the direct `ask()` invocation. No separate task per gap_closure_scope guidance. |

## Out-of-Scope (Per Plan)

| ID | Status | Rationale |
| -- | ------ | --------- |
| BLK-02 | Not addressed | DB credentials — dev-only, user-approved. |
| BLK-03 | Not addressed | `markInjected` only in `doOnComplete` matches D-03 by design — verified, intentional. |
| AiAuditEvent metamodel boot regression | Not addressed | Deferred per HUMAN-UAT item 4. New test is pure JUnit + Mockito specifically to sidestep this. |
| 11 review WARNINGs | Not addressed | Routed via `/gsd-code-review 13 --fix`, not here. |

## Threat Mitigations Applied

Per `<threat_model>` register in plan:

- **T-13-22 Tampering** — future regression guard: combined gates (`grep` for `ChatResponseDto blocking = ask(` count == 0 + Mockito test 1 fail-fast on persistUserMessage > 1) make reintroduction of the recursive call CI-fail-fast.
- **T-13-23 Information disclosure** — duplicate user message in `ChatMemory` replay eliminated at the source.
- **T-13-24 Repudiation** — markInjected idempotent re-stamp dropped from 2x to 1x; ambiguity in `injectedAt` resolved by single-call invariant.
- **T-13-25 Denial of Service** — per-turn agentstore writes halved on the fallback path.

## Phase Status Note

Phase 13 status row stays at 5/5 Complete. This gap closure is wave-5 inside Phase 13's gap-closure namespace, not a sixth structural plan. STATE.md should NOT regress the 5/5 marker.

## Self-Check: PASSED

- File `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` modified — confirmed by `git log` and Task 1 verify gates 1-6.
- File `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java` created — confirmed by `git log` and Task 2 verify gates 1-7.
- Commit `e34b7f1` (Task 1) — present in `git log --oneline`.
- Commit `5c014ca` (Task 2) — present in `git log --oneline`.
- `:ai-agent:ai-agent:compileJava` — BUILD SUCCESSFUL.
- `:ai-agent:ai-agent:compileTestJava` — BUILD SUCCESSFUL.
- `:ai-agent:ai-agent:test --tests "*StreamFallbackTest*"` — 5 tests, 0 failures, 0 errors.
