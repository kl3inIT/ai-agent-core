---
phase: 13-chat-task-input-stt-task-scoped-file
reviewed: 2026-05-06T00:00:00Z
depth: standard
files_reviewed: 2
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java
findings:
  critical: 0
  warning: 3
  info: 2
  total: 5
status: issues_found
---

# Phase 13: Code Review Report (Re-review of CR-01 fix at commit `38d9476`)

**Reviewed:** 2026-05-06
**Depth:** standard
**Files Reviewed:** 2
**Status:** issues_found
**Predecessor:** Narrow review at commit `bbc9168`; CR-01 fix landed at `38d9476`. Previous review preserved in git history.

## Summary

**CR-01 is closed.** The streaming path's guard preamble now mirrors `ask()`'s blocking preamble at the right ordering:

- `IterationCounter.start()` is primed at the top of `Flux.defer` (line 445) and `IterationCounter.reset()` is invoked on every terminal signal in `.doFinally(...)` (line 639) — pairs structurally with `ask()`'s line 196 / 307.
- `rateLimitGuard.check(userId)` runs BEFORE `conversationGateway.loadOrCreate` (line 452); on `RateLimitExceededException` the code emits `Error("ai-agent.guard.rate-limit-exceeded", retryAfterSec=60)` + `Final(...)` and exits — mirrors `ask()` lines 202-208 (WR-04 from the prior review is now closed).
- `tokenBudgetGuard.check(convId)` runs AFTER `loadOrCreate` (line 472) since the gate is per-conversation; on denial emits `Error("ai-agent.guard.token-budget-exhausted")` + `Final(...)` — mirrors `ask()` lines 215-221.
- `mapToStreamingError(...)` (line 650) was already mapping `IterationCapExceededException` / `ToolVetoedException` correctly; CR-01 fix did not touch it. Confirmed still correct.
- Early-return Flux denial paths properly trigger `.doFinally(...)` so `IterationCounter.reset()` and `RunContext.clear()` fire on every denial path (`Flux.just(Error, Final)` completes through the outer chain to `doFinally`).
- Streaming-success path primes `IterationCounter` exactly once — no double `start()` because `executeBlockingTurn` does NOT call `start()` itself (the start at line 445 covers both transports).

**D-01 / D-03 / D-04 invariants** still hold post-fix:
- D-01 (single-turn Media injection via `.user(u -> u.media(...))` lambda): preserved at lines 541-547.
- D-03 (`markInjected` only on `doOnComplete`, never on cancel/error in streaming success): preserved at lines 583-595.
- D-04 (graceful streaming fallback to `executeBlockingTurn` instead of recursive `ask()`): preserved at lines 598-611; user-message persist + `markInjected` still fire EXACTLY ONCE per turn per BLK-01.

**Public API signatures** unchanged: `ask`, `stream`, `askTyped` (both arities), constructor argument list and types — verified against the test's constructor call at lines 153-173.

**Test suite:** 5/5 PASSED post-fix per the brief. The tests exercise the BLK-01 single-write invariants under both fallback and streaming-success paths.

Three WARNINGs surface from CR-01-adjacent surface area: a Reactor thread-locality concern with `IterationCounter` (now lit up because `start()` runs on a scheduler thread), a terminal-event asymmetry between guard short-circuits and `onErrorResume`-mapped errors, and a `Final` event carrying a possibly-null `conversationId` on first-turn rate-limit denial. None block ship of CR-01 itself.

## Warnings

### WR-01: ThreadLocal `IterationCounter` may not survive Reactor thread-hops on streaming path

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:445,639`

**Issue:** `IterationCounter` is a ThreadLocal consumed by `GuardedToolCallingManager` on whatever thread invokes the chat client. On the streaming path:

- `IterationCounter.start()` (line 445) runs inside `Flux.defer`, which after `.subscribeOn(chatStreamingScheduler)` (line 624) executes on a scheduler thread.
- `IterationCounter.reset()` (line 639) runs in `.doFinally(...)`, which fires on whatever thread emits the terminal signal. Reactor pipelines hop threads — Spring AI's `ChatClient.stream().chatResponse()` may use the HTTP client's own scheduler — so `reset()` is not guaranteed to run on the same thread as `start()`.
- If the threads differ, `start()` leaks ThreadLocal state on the original scheduler thread. Subsequent `stream()` calls re-prime via `start()` (which sets the count to 0), so the leak is masked for the chat path itself, but anything else dispatched on that scheduler thread between `start()` and the next `start()` could observe stale counter state.

The blocking `ask()` path is unaffected (single-threaded). This concern existed pre-CR-01 only as "the counter was never primed for streaming"; CR-01 makes the leak surface real because `start()` now runs.

**Fix (any one):**
- Migrate `IterationCounter` to a Reactor `ContextView` value rather than a ThreadLocal, OR
- Register a Micrometer `ThreadLocalAccessor` SPI for it and rely on the `.contextCapture()` already at line 627, OR
- Document explicitly that the counter is best-effort on streaming and add an integration test verifying that tool execution for the supported chat models stays on the subscribe thread (smallest change).

### WR-02: Streaming-path guard short-circuits emit `Final`, but `onErrorResume`-mapped errors do NOT

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:456-459,476-478,615-622,634`

**Issue:** Asymmetric terminal-event behaviour after the CR-01 fix:

- The new rate-limit and token-budget short-circuits (lines 456-459 and 476-478) emit `Error(...)` followed by `Final(runId, ...)` — explicit terminal event pair.
- The `.onErrorResume(ex -> Flux.just(mapToStreamingError(ex)))` at line 634 emits ONLY an `Error` event. The `concatWith(Flux.defer(... Final ...))` at line 615 lives upstream of `onErrorResume`, so when `onErrorResume` fires it replaces the entire upstream including the `Final` emitter.
- Consequence: `IterationCapExceededException`, `ToolVetoedException`, `ConversationNotFoundException`, and the generic-error catchall produce a stream that ends with `Error` and no `Final`. UI consumers waiting for `Final` to release the spinner / unlock the input box will hang or have to rely on the Flux completion signal alone.

This was pre-existing; the CR-01 fix made it visibly inconsistent because the new short-circuit branches DO emit `Final`.

**Fix:** make `onErrorResume` emit a terminating `Final` too. Capture `convId` into an `AtomicReference<UUID>` set inside `Flux.defer` after `loadOrCreate` so it is visible to `onErrorResume`:

```java
final AtomicReference<UUID> convIdRef = new AtomicReference<>(conversationId);
// inside Flux.defer, after loadOrCreate:
convIdRef.set(convId);
// ...
.onErrorResume(ex -> Flux.just(
        mapToStreamingError(ex),
        new StreamingEvent.Final(runId, convIdRef.get(),
                (System.nanoTime() - startNanos) / 1_000_000L, 0, 0)))
```

### WR-03: `Final` event on first-turn rate-limit denial carries the raw caller-supplied `conversationId` (possibly `null`)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:459`

**Issue:** On the rate-limit short-circuit, `loadOrCreate` has not yet run, so the server-side `convId` is unknown. Line 459 emits `new StreamingEvent.Final(runId, conversationId, ...)` using the caller's raw `conversationId` — which is `null` for first-turn requests.

This is symmetric with `ask()` line 206 (`ChatResponseDto.denied(conversationId, runId, ...)` also emits the raw caller param), so it is consistent across transports. But it means UI clients receiving a streaming denial for a first-turn request get `Final.conversationId == null` and cannot subsequently navigate to a conversation that does not exist. The blocking path has the same limitation; it is an inherent consequence of denying before `loadOrCreate`.

**Fix:** either (a) document on `StreamingEvent.Final`'s record that `conversationId` may be `null` on pre-`loadOrCreate` denials, or (b) leave behaviour and add a short comment at line 459 stating "convId is null until loadOrCreate runs; rate-limit denials predate that step". No functional change required if intentional — flag for visibility.

## Info

### IN-01: `streamingSinkHolder.unregister(runId)` and `cancellationRegistry.clearDisposable(runId)` run on rate-limit denial path despite never having registered

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:451-460,641-642`

**Issue:** When the rate-limit short-circuit at lines 451-460 fires, `streamingSinkHolder.register(runId, toolSink)` (line 464) is never reached because the early return precedes the registration. The `.doFinally` cleanup at lines 641-642 still calls `unregister(runId)` and `clearDisposable(runId)`. Assumes both operations are silent no-ops on missing-id; if either logs a WARN on missing-id, log volume could spike under sustained rate-limit denial. The token-budget denial branch at lines 471-479 is symmetric — it DOES register first (line 464 runs before the check at 472).

**Fix:** confirm `StreamingSinkHolder.unregister` and `CancellationRegistry.clearDisposable` are silent on missing ids; if not, gate the cleanup with `if (registered) { ... }`. One-line comment confirming the no-op contract is sufficient if the registries already handle it.

### IN-02: Test setUp duplicates the deep-stub chain when only the leaf differs

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java:189-208`

**Issue:** `stubChatClientForFallback()` repeats the full `chatClient.prompt().system(...).user(...).toolCallbacks(...).toolContext(...).advisors(...).options(...)` chain twice — once stubbing `.call().chatClientResponse()` and once stubbing `.stream().chatResponse()`. With `RETURNS_DEEP_STUBS` and matching argument matchers, both paths return the same mid-chain mock, so this works today. But the duplication is brittle if the Spring AI builder chain reorders or if any builder method becomes `final`.

**Fix:** extract the common chain prefix into a helper that returns the request-spec mock at the leaf-1 level, then call `.call().chatClientResponse()` / `.stream().chatResponse()` on the result. Pure test-quality improvement; not blocking.

---

_Reviewed: 2026-05-06_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard (re-review of CR-01 fix at commit `38d9476`)_
_Scope: DefaultChatServiceImpl.java + DefaultChatServiceImplStreamFallbackTest.java only_
