---
status: resolved
phase: 13-chat-task-input-stt-task-scoped-file
source: [13-VERIFICATION.md]
started: 2026-05-06T00:00:00Z
updated: 2026-05-06T12:00:00Z
resolved: 2026-05-06T12:00:00Z
---

## Current Test

[ALL UAT ITEMS RESOLVED — 3 passed, 1 passed-with-caveat (deferred regression), 1 originally-failed (BLK-01) closed by gap plan 13-06 + CR-01 follow-up fix at commit `38d9476` (re-reviewed at `f3a6cc1`).]

## Tests

### 1. BLK-02 — Hardcoded DB credentials decision
expected: Either explicit acknowledgement that committed `username=postgres` / `password=admin123` against `10.123.123.174:5555` in `jmix-app/src/main/resources/application.properties` is intentional for the dev branch and not a release blocker, OR the credentials are externalised via `.env` and the leaked password is rotated.
result: passed (2026-05-06 — user approved as dev-only artefact for Phase 13; revisit before any release branch cut)

### 2. BLK-01 — Streaming fallback double-write smoke test
expected: With a chat model that throws `UnsupportedOperationException` on `.stream()`, sending a chat message with an attached file produces exactly 1 user `AiMessage` row per submission and `markInjected` stamps each `AiTaskFile` exactly once.
result: resolved (2026-05-06 — closed by gap plan 13-06 + CR-01 follow-up fix). Originally failed (2026-05-06 — confirmed by static trace: streaming `Flux.defer` persisted user message once at line 454, then catch on `UnsupportedOperationException` recursed into `ask(...)` which persisted AGAIN at line 285, producing 2 user AiMessage rows per turn). Closed via Path A (pre-resolved-state pass-through helper):
- Plan 13-06 Task 1 (commit `e34b7f1`) extracted private `executeBlockingTurn(...)` helper that takes already-resolved `Resolved` + already-persisted `userMessageId` as parameters; both `ask(...)` and the streaming catch delegate to the same helper.
- Plan 13-06 Task 2 (commit `5c014ca`) added pure JUnit + Mockito `DefaultChatServiceImplStreamFallbackTest` with 5 tests asserting `Mockito.verify(times(1))` on `persistUserMessage`, `markInjected`, and `resolvePending` for the fallback path; 5/5 tests green under `./gradlew :ai-agent:ai-agent:test --tests "*StreamFallbackTest*"` (NOT @SpringBootTest — sidesteps the deferred AiAuditEvent metamodel boot regression).
- Code review of the fix surfaced CR-01 (the recursive-ask removal had silently bypassed the streaming-path guard preamble — rate-limit / token-budget / IterationCounter never primed on streaming branch). CR-01 closed by commit `38d9476`: `IterationCounter.start()` at line 445, `rateLimitGuard.check(userId)` at line 452 (BEFORE `loadOrCreate`), `tokenBudgetGuard.check(convId)` at line 472 (AFTER `loadOrCreate` since gate is per-conversation), `IterationCounter.reset()` at line 639 in `.doFinally(...)`. Re-review at commit `f3a6cc1` confirms 0 BLOCKERs; 3 non-blocking WARNINGs surfaced (WR-01 ThreadLocal/Reactor scheduler hop, WR-02 asymmetric Final emit, WR-03 null `convId` on first-turn denial — all streaming-architecture concerns, NOT regressions of D-01 / D-03 / D-04).

Verification gates (all green):
- `grep -c "ChatResponseDto blocking = ask\(" DefaultChatServiceImpl.java` = 0 (recursive call gone)
- `grep -nE "executeBlockingTurn" DefaultChatServiceImpl.java` = 1 declaration (line 338) + 2 call sites (line 292 in `ask`, line 604 in streaming catch)
- `grep -nE "userMessagePersister\.persistUserMessage" DefaultChatServiceImpl.java` = 2 sites (line 285 ask, line 529 streaming defer)
- `grep -c "@SpringBootTest" DefaultChatServiceImplStreamFallbackTest.java` = 0 annotations (2 JavaDoc references at lines 52, 54 only — explanatory text, not annotations)
- `:ai-agent:ai-agent:test --tests "*StreamFallbackTest*"` = 5 tests / 0 failures / 0 errors
- `:ai-agent:ai-agent:compileJava` and `:compileTestJava` = BUILD SUCCESSFUL

### 3. BLK-03 — Stream cancel/retry behaviour
expected: Per D-03 contract, a cancelled SSE stream leaves the task file pending so a retry re-injects.
result: passed (2026-05-06 — confirmed by static trace). `DefaultChatServiceImpl.stream()` lines 583-595: `markInjected` lives only in `doOnComplete`. Reactor contract: `doOnComplete` fires on `onComplete` only — NOT on `onCancel` or `onError`. So cancelled stream leaves `injectedAt IS NULL` → next-turn resolver picks the same rows up again (matches D-03 "cancelled = retry works"). The intentional comment at line 579-582 explicitly documents this. Adding `doOnError`/`doOnCancel` would BREAK D-03; the asymmetry is by design.

### 4. bulk_save_records test execution
expected: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*"` either passes, or fails only with the pre-existing `MetaClass not found for AiAuditEvent` regression documented in `deferred-items.md`.
result: passed-with-caveat (2026-05-06 — 10 tests / 10 failed, ALL with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145` whose root cause is `Caused by: java.lang.IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent` — exact match for the deferred regression. Cross-confirmed by running pre-existing Phase 11 test `BuiltInMutationToolsAuditArgumentsTest` which fails identically. Test artefacts are structurally correct (compileTestJava passed during plan execution); Spring-context boot regression predates Phase 13 and is documented in `deferred-items.md` as out-of-scope per Plan 13-05 SCOPE BOUNDARY. Tests will go green once Phase 11 Plan 11-10 fixes the underlying regression.)

## Summary

total: 4
passed: 3
passed-with-caveat: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

### BLK-01 — Streaming-fallback double-write of user AiMessage row
status: resolved
file: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
trigger: chat models that throw `UnsupportedOperationException` from `chatClient.prompt()...stream()` AND user has attached files
symptom_original: 2 user AiMessage rows persisted per turn (one at line 454 in the streaming `Flux.defer`, a second at line 285 inside the inner `ask(...)` call that the line 523-531 catch fell through to)
fix_landed:
  - Plan 13-06 Task 1 (commit e34b7f1) — extracted `executeBlockingTurn(...)` private helper; both `ask(...)` and the streaming catch delegate to it; recursive `ask(...)` from streaming catch removed
  - Plan 13-06 Task 2 (commit 5c014ca) — added DefaultChatServiceImplStreamFallbackTest (5 tests / 5 pass) asserting Mockito.verify(times(1)) on the fallback path
  - CR-01 follow-up (commit 38d9476) — wired streaming-path guard preamble (rateLimitGuard.check + tokenBudgetGuard.check + IterationCounter.start/reset) to mirror ask()'s preamble, closing the regression introduced when the recursive-ask was removed
  - Re-review (commit f3a6cc1) confirms 0 BLOCKERs remain; 3 non-blocking WARNINGs noted in 13-VERIFICATION.md `known_issues` frontmatter

### deferred — Spring-context boot regression (pre-existing, blocks bulk-save integration tests)
status: deferred
source: .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md
symptom: `IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent` raised inside `AnnotatedRoleBuilderImpl` during `@SpringBootTest` context boot. Affects all Plan 11-10..11-13 mutation tests AND the new Plan 13-05 bulk-save / taskfile integration tests.
verified: 2026-05-06 — running `BuiltInMutationToolsAuditArgumentsTest` (Phase 11 pre-existing) reproduces the same fingerprint
recommendation: dedicated debug session (`/gsd-debug`) on the metamodel-session ordering. Out-of-scope for Phase 13 per SCOPE BOUNDARY rule.

### follow-ups — non-blocking WARNINGs from CR-01 re-review
status: routed
source: 13-REVIEW.md (re-review at commit f3a6cc1)
items:
  - WR-01 ThreadLocal IterationCounter may not survive Reactor thread-hops on streaming path (lines 445, 639)
  - WR-02 Asymmetric Final emit — `onErrorResume`-mapped errors do not emit terminating `Final` event (lines 615-634)
  - WR-03 `Final.conversationId` may be null on first-turn rate-limit denial (line 459) — symmetric with `ask()` blocking path
recommendation: route via `/gsd-code-review 13 --fix` follow-up cycle; NOT regressions of D-01 / D-03 / D-04 contracts and NOT goal failures for Phase 13.
