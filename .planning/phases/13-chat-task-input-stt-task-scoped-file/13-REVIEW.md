---
phase: 13-chat-task-input-stt-task-scoped-file
reviewed: 2026-05-06T00:00:00Z
depth: standard
files_reviewed: 2
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java
findings:
  critical: 1
  warning: 4
  info: 2
  total: 7
status: issues_found
---

# Phase 13: Code Review Report (Gap-Closure Narrow Scope — BLK-01 Fix)

**Reviewed:** 2026-05-06
**Depth:** standard
**Files Reviewed:** 2
**Status:** issues_found

## Summary

Narrow gap-closure review of plan 13-06 BLK-01 fix. The recursive `ask(...)` call has been
correctly removed from the streaming catch (D-04 graceful-fallback path) and replaced with a
direct call to the new private `executeBlockingTurn(...)` helper that operates on already-
resolved state — so user-message persist and `markInjected` now fire EXACTLY ONCE per
streaming-fallback turn. The five-test Mockito harness exercises the right invariants
(persist x1, markInjected x1, resolvePending x1, no-attached-file degenerate, streaming-success
regression guard) and correctly stays off `@SpringBootTest` to avoid the deferred
AiAuditEvent metamodel boot regression.

However, the refactor introduces a **BLOCKER**: by replacing the recursive `ask(...)` call with
a direct `executeBlockingTurn(...)` call, the streaming-fallback path loses the
`IterationCounter.start()` initialization and the `IterationCapExceededException` /
`ToolVetoedException` catches that the old recursive `ask(...)` path provided. The original
recursive call also "happened" to invoke the `rateLimitGuard.check(userId)` /
`tokenBudgetGuard.check(convId)` preamble — that gating is now bypassed on the fallback path.
This regresses request-level guard coverage that existed before plan 13-06 landed. Also flagged
are several smaller robustness and ordering concerns around the catch block.

D-01 (`.user(u -> u.media(...))` lambda form), D-03 (`doOnComplete`-only `markInjected` in the
streaming-success path), and D-04 (UnsupportedOperationException catch produces a working
Content+Final pair) invariants are individually intact in the post-refactor code. The 11 prior
WARNINGs from the original full-phase review live in files outside this two-file scope and are
out of scope per the run brief.

## Critical Issues

### CR-01: Streaming-fallback path skips IterationCounter.start() AND iteration-cap / tool-vetoed mapping that the old recursive `ask(...)` provided

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:564-577`

**Issue:** The old streaming-fallback recursed through `ask(userId, conversationId, message, overrides)`
which (a) primed `IterationCounter.start()` (line 196), (b) caught `IterationCapExceededException`
and `ToolVetoedException` and mapped them to `ChatResponseDto.denied(...)` (lines 295-305), and
(c) ran the `rateLimitGuard.check(...)` / `tokenBudgetGuard.check(...)` guard preamble (lines 203,
216).

The new direct call to `executeBlockingTurn(...)` from the streaming catch skips ALL THREE:

1. **`IterationCounter` is never primed for the streaming path.** `stream()` (line 439) does not
   call `IterationCounter.start()`, only `RunContext.set(runId)`. When the fallback runs
   `chatClient.prompt()...call()...chatClientResponse()` inside `executeBlockingTurn`, the
   `GuardedToolCallingManager` reads `IterationCounter` ThreadLocal — and because the streaming
   path never primed it, the counter is in whatever state the previous request left it in (the
   blocking `ask()` path ends with `IterationCounter.reset()` in `finally` so usually it's reset,
   but a failed pre-`finally` exit on a pooled Vaadin request thread can leak state into this
   fallback turn).

2. **`IterationCapExceededException` thrown from `executeBlockingTurn` is no longer mapped
   to a typed denial.** Inside the `Flux.defer(...)` body it propagates out of the lambda and
   hits `.onErrorResume(ex -> mapToStreamingError(ex))` which DOES map
   `IterationCapExceededException` to `ai-agent.guard.iteration-cap-exceeded` (lines 618-619), so
   user surface is OK — but `toolSink.tryEmitComplete()` (line 574) is unreachable because the
   throw from `executeBlockingTurn` (line 570-572) happens BEFORE that line, leaving the
   tool-event sink open until `.doFinally(...)` triggers `streamingSinkHolder.unregister(runId)`
   on cancellation/completion of the outer Flux. This is a sink-completion ordering regression.

3. **Rate-limit and token-budget guards are not run on the streaming-fallback path.** The original
   recursive `ask(...)` call would have re-checked both guards before the blocking LLM call. The
   new helper (`executeBlockingTurn`) has no guard preamble of its own — it goes straight to
   `chatClient.prompt()...call()`. So a caller whose chat model lacks streaming support now
   bypasses both guards entirely. This affects fairness/cost-safety on a transport-mode boundary
   that should be invisible to gating.

**Why this is a BLOCKER, not a WARNING:** the fix is intended to be behaviour-preserving for
everything except the duplicate-write defect. Removing iteration-counter priming and silently
dropping the rate-limit/token-budget gate on the fallback transport changes the operational
contract for any deployed model that returns `UnsupportedOperationException` on `.stream()`. This
is exactly the kind of "fix one bug, regress an adjacent invariant" the gap-closure review brief
asks to surface.

**Fix:**
```java
} catch (UnsupportedOperationException nonStreaming) {
    // BLK-01 fix (gap-closure plan 13-06): D-04 graceful fallback. Reuse already-
    // resolved Media + persisted user-message id via executeBlockingTurn(...) — but
    // run the same guard preamble + ThreadLocal priming the blocking ask() path runs,
    // so transport-mode does not become an implicit policy bypass.
    try {
        rateLimitGuard.check(userId);
    } catch (RateLimitExceededException rate) {
        toolSink.tryEmitComplete();
        throw rate; // -> mapToStreamingError -> ai-agent.guard.rate-limit-exceeded
    }
    try {
        tokenBudgetGuard.check(convId);
    } catch (TokenBudgetExhaustedException budget) {
        toolSink.tryEmitComplete();
        throw budget;
    }
    IterationCounter.start();
    try {
        ChatResponseDto blocking;
        try {
            blocking = executeBlockingTurn(userId, convId, message, effectiveOverrides,
                    resolvedMedia, userMessageIdRef.get(), composedSystemPrompt, model, active,
                    ragFilter, retrievalTopK, retrievalSimilarityThreshold, runId, startNanos);
        } catch (IterationCapExceededException | ToolVetoedException denied) {
            toolSink.tryEmitComplete();
            throw denied; // mapToStreamingError handles both keys
        }
        titlePublicationHandled.set(true);
        toolSink.tryEmitComplete();
        content = Flux.just(
                new StreamingEvent.Content(blocking.content() == null ? "" : blocking.content()));
    } finally {
        IterationCounter.reset();
    }
}
```

Then add a regression test that injects an `IterationCapExceededException`-throwing mock chat
model and asserts the `StreamingEvent.Error` carries `ai-agent.guard.iteration-cap-exceeded`,
plus a rate-limit-denied-on-stream test.

## Warnings

### WR-01: `toolSink.tryEmitComplete()` is reachable only on the success branch of the fallback — sink leaks if `executeBlockingTurn` throws

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:570-577`

**Issue:** The catch block currently does:
```java
ChatResponseDto blocking = executeBlockingTurn(...);   // line 570 — may throw
titlePublicationHandled.set(true);                     // line 573
toolSink.tryEmitComplete();                            // line 574
content = Flux.just(...);                              // line 575
```
If `executeBlockingTurn` throws (LLM call failure, audit-write failure, scanner read failure,
etc.), `toolSink.tryEmitComplete()` is never invoked — the tool-event sink stays open until
`.doFinally` clears it via `streamingSinkHolder.unregister(runId)`. Downstream consumers
(`toolSink.asFlux().mergeWith(content)`) will see a half-completed merge. The outer
`onErrorResume` does map the throw to a `StreamingEvent.Error`, but the tool-sink half of the
merge is never told to stop emitting.

**Fix:** wrap the `executeBlockingTurn` call in try/finally so `toolSink.tryEmitComplete()`
always runs, OR move `toolSink.tryEmitComplete()` BEFORE the fallback call (the helper does not
emit tool events itself).

```java
} catch (UnsupportedOperationException nonStreaming) {
    titlePublicationHandled.set(true);
    toolSink.tryEmitComplete(); // close BEFORE the blocking call so a throw cannot leak the sink
    ChatResponseDto blocking = executeBlockingTurn(...);
    content = Flux.just(
            new StreamingEvent.Content(blocking.content() == null ? "" : blocking.content()));
}
```

### WR-02: `executeBlockingTurn` re-runs `markInjected` on the streaming-fallback path even though `taskFileMediaResolver.resolvePending` was already called in `Flux.defer`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:386-394`

**Issue:** The fallback delegates through `executeBlockingTurn(...)` passing the
already-resolved `resolvedMedia` from `Flux.defer`. Inside `executeBlockingTurn` lines 386-394
unconditionally call `taskFileRepository.markInjected(...)` if `!resolvedMedia.isEmpty()`. That
is correct for BLK-01 (it does happen exactly once on the fallback path) — BUT the streaming-
success `doOnComplete` (lines 549-561) has the SAME `markInjected` call against the SAME
`resolvedMedia` and the SAME `userMessageIdRef.get()`. If a future refactor accidentally lets
both branches run (e.g. if `executeBlockingTurn` is called THEN the streaming success path also
fires), `markInjected` would be invoked twice. There's currently no guard preventing this; the
mutual-exclusion is purely flow-control (catch vs no-catch).

**Fix:** add a defensive `AtomicBoolean alreadyMarked` flag set inside `executeBlockingTurn`'s
caller closure, OR (simpler) document the mutual-exclusion contract in `executeBlockingTurn`'s
javadoc — currently the javadoc states "exactly once per turn" but the helper itself has no way
to enforce that against the streaming-success `doOnComplete`. The test suite covers the
single-call invariant per branch but does not cover a hypothetical "both branches fire" path.

### WR-03: `userMessageIdRef.get()` may be `null` when `markInjected` is called on the fallback path

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:493-501, 570-572`

**Issue:** `stream()` only calls `userMessagePersister.persistUserMessage` inside a try/catch
that swallows `RuntimeException` (line 497-500), leaving `userMessageIdRef` empty. The fallback
then passes `userMessageIdRef.get()` (= `null`) into `executeBlockingTurn`, which passes it to
`taskFileRepository.markInjected(ids, null, now())`. Whether that null is acceptable depends on
the AiTaskFile schema — if `injected_message_id` is non-null in the DB, `markInjected` will
throw a constraint violation that the inner try/catch (line 390-393) swallows as a `warn` log,
but the user message and the LLM response have already happened, so we now have a confirmed
`AiMessage` row with NO link from the AiTaskFile chunks. The blocking `ask(...)` path has the
identical defect (line 286-288), so this is not a refactor regression — but the BLK-01 fix has
the opportunity to address it cleanly because both paths now share `executeBlockingTurn`.

**Fix:** when `userMessageIdAlreadyPersisted == null && !resolvedMedia.isEmpty()`, skip the
`markInjected` call entirely (no FK to stamp) and emit a `log.warn` audit-trail breadcrumb so
operators can see an injection attempt that lost its link. Or fail-fast on `null` userMessageId
so the user gets a typed denial instead of a silent partial-state turn.

### WR-04: Streaming path lacks the rate-limit / token-budget guard preamble that the blocking path has

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:439-501`

**Issue:** The blocking `ask(...)` runs `rateLimitGuard.check(userId)` (line 203) BEFORE
`conversationGateway.loadOrCreate` and `tokenBudgetGuard.check(convId)` (line 216) AFTER. The
streaming `stream(...)` runs neither — it goes straight from `RunContext.set(runId)` to
`conversationGateway.loadOrCreate`. This pre-existed the gap-fix, but the gap-fix amplifies its
impact because the new fallback turns a streaming-only path into one that also runs the blocking
LLM call (without guards). Even without the fallback, this means a streaming caller can spend
LLM budget that a blocking caller of the same conversation would be denied.

**Fix:** the streaming path should run the same guard preamble (`rateLimitGuard` then
`tokenBudgetGuard` after `loadOrCreate`) and short-circuit with a terminal
`StreamingEvent.Error` on denial. This needs to land alongside CR-01.

## Info

### IN-01: `setUp()` stubs guard `doNothing().when(rateLimitGuard).check(anyString())` — does not exercise denied-on-stream branch

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java:141-142`

**Issue:** The five tests prove BLK-01 invariants but skip the negative paths
(`RateLimitExceededException` thrown during fallback, `IterationCapExceededException` thrown
from the blocking call, `UserMessagePersister` throwing). The `streamingFallback_*` test names
already imply scope: BLK-01 — but the gap-fix's risk surface (CR-01 above) extends into those
denied-paths and a follow-up test would catch it.

**Fix:** add three negative-path tests after CR-01 / WR-04 land:
- `streamingFallback_whenRateLimitDenied_emitsErrorAndDoesNotPersist`
- `streamingFallback_whenIterationCapExceededInsideBlockingCall_emitsIterationCapErrorKey`
- `streamingFallback_whenUserMessagePersisterThrows_doesNotCallMarkInjected`

### IN-02: Test uses `RETURNS_DEEP_STUBS` with argument matchers inside `when(...)` builder chain

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java:189-208`

**Issue:** Calls like
```java
when(chatClient.prompt().system(anyString()).user(any(...))...options(any()).call().chatClientResponse())
        .thenReturn(clientResp);
```
use Mockito argument matchers inside a deep-stub builder chain. This pattern works in practice
for the current Spring AI 1.1.x ChatClient builder shape but is brittle: if Spring AI changes
the builder return type or makes any builder method final, the deep-stub chain silently returns
a fresh mock and the stubbing applies to the wrong link. Not a bug today, but a maintenance
hazard.

**Fix:** consider extracting an explicit stub helper that captures each builder mock by hand
(`when(chatClient.prompt()).thenReturn(promptSpec); when(promptSpec.system(any())).thenReturn(...)`,
etc.) — much more verbose but immune to silent breakage on the Spring AI upgrade path. Or wait
until the deep-stub chain breaks once and refactor reactively.

---

_Reviewed: 2026-05-06_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
_Scope: BLK-01 gap-closure (plan 13-06) — DefaultChatServiceImpl.java + DefaultChatServiceImplStreamFallbackTest.java only_
