---
phase: 07-flow-ui
plan: 02
subsystem: streaming-backend
tags: [streaming, spring-ai, reactor, sinks-many, cancellation, afterCommit, tool-events, phase7-wave1]

requires:
  - phase: 07-flow-ui
    plan: 01
    provides: StreamingEvent sealed interface + 6 record variants; DocumentStatusChangedEvent record
  - phase: 04
    provides: ToolCallbackAuditDecorator (extended here with streaming emission); RunContext thread-local runId carrier; AuditWriter.writeToolCall afterCommit pattern (mirrored here)
  - phase: 05
    provides: IngestionStatusWriter (extended with afterCommit publish); CancellationRegistry (extended with Disposable overload)
  - phase: 06
    provides: Typed guard exceptions (RateLimit / TokenBudget / IterationCap / ToolVetoed) mapped here to StreamingEvent.Error keys
provides:
  - ChatService.stream(userId, convId, msg, overrides) -> Flux<StreamingEvent> contract (consumed by 07-03 ChatView)
  - ChatStreamingSchedulerConfig bean "chatStreamingScheduler" (boundedElastic 20/1000 "ai-agent-stream") — off-Tomcat execution of stream subscriptions
  - StreamingSinkHolder @Component (ConcurrentHashMap UUID -> Sinks.Many<StreamingEvent>, lookup via RunContext)
  - DefaultChatServiceImpl.stream(...) full implementation: Sinks.Many tool-sink mergeWith ChatClient.prompt().stream().chatResponse() content flux, subscribeOn chatStreamingScheduler, doOnSubscribe cancellation-wiring, doFinally cleanup, onErrorResume -> StreamingEvent.Error with stable i18n key
  - CancellationRegistry.register(UUID, Disposable) + clearDisposable(UUID) overload; cancel(UUID) now disposes registered chat-stream subscription
  - ToolCallbackAuditDecorator streaming overload: StreamingEvent.ToolCall on entry + StreamingEvent.ToolResult on exit (success + error) via StreamingSinkHolder.current().ifPresent(...) — opt-in, zero overhead when no streaming run active
  - IngestionStatusWriter.markPending/Processing/Ready/Failed/Cancelled emit DocumentStatusChangedEvent via TransactionSynchronization.afterCommit (consumed by 07-06 KnowledgeBaseView)
affects: [07-03, 07-06]

tech-stack:
  added:
    - "reactor.core.publisher.Flux / Sinks.Many / Sinks.EmitResult (Spring-AI-provided reactor-core)"
    - "reactor.core.scheduler.Schedulers.newBoundedElastic (named ai-agent-stream)"
    - "org.springframework.transaction.support.TransactionSynchronization/Manager afterCommit for DocumentStatusChangedEvent"
  patterns:
    - "Sinks.Many unicast-onBackpressureBuffer mergeWith Flux<ChatResponse> (Spring AI GH#5167 workaround for interleaving tool events with content chunks)"
    - "doOnSubscribe(subscription -> registry.register(runId, subscription::cancel)) — registers a Disposable-shaped lambda BEFORE tokens flow so Stop is always wirable"
    - "afterCommit TransactionSynchronization publishEvent — mirrors Phase 4 AuditWriter.writeToolCall listener fan-out"
    - "Opt-in tool-event emission via request-scoped StreamingSinkHolder.current() lookup; Optional.empty() for blocking path = zero overhead"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java

key-decisions:
  - "Pattern A (doOnSubscribe subscription.cancel()) chosen over Pattern B (subscribe-site Disposable). The runId is allocated inside Flux.defer so it is not visible to the caller before subscription — emitting a synthetic Started(runId) event first would have added a 7th StreamingEvent variant for infrastructure reasons only. Pattern A uses reactor.core.Disposable as a @FunctionalInterface and satisfies the registry with a () -> subscription.cancel() lambda."
  - "CancellationRegistry extended (not replaced) — added register(UUID, Disposable) + clearDisposable(UUID) and made cancel(UUID) dispose the registered Disposable if present. The pre-existing document-cancellation generation/boolean semantics are preserved byte-for-byte; chat-stream support is an additive layer."
  - "ChatStreamingSchedulerConfig class NAMED distinctly from the @Bean method (not ChatStreamingScheduler) to avoid the BeanDefinitionOverrideException that occurs when the @Configuration class bean id (derived from uncapitalized class name) collides with an explicit @Bean(name=...) id. This was found at @SpringBootTest context load — fixed by renaming the class and keeping the bean name stable."
  - "ToolCallbackAuditDecorator gets a second constructor overload (delegate, auditWriter, currentAuthentication, streamingSinkHolder) — the original 3-arg constructor delegates to the new 4-arg with null, preserving pure-unit tests of the decorator that construct it directly without a sink holder."
  - "IngestionStatusWriter.registerAfterCommit(id, status, errorMessage) helper collapses five nearly-identical inline TransactionSynchronization blocks into one call site per mutation method. Cleaner than the plan's literal `grep == 5` text; the plan's semantic contract (one afterCommit publish per successful mutation, FAILED carries errorMessage, others carry null) is preserved exactly."
  - "mapToStreamingError emits i18n keys only (chatView.error.{conversationNotFound,generic} + ai-agent.guard.{rate-limit-exceeded,token-budget-exhausted,iteration-cap-exceeded,tool-vetoed}) with empty params Map — T-07-05 opacity: no raw provider text reaches the client."

metrics:
  duration: ~35m
  completed: 2026-04-21

requirements-completed: [UI-01, UI-02, UI-05]
---

# Phase 07 Plan 02: Streaming Backend Backbone Summary

**Streaming backend for Phase 7. ChatService.stream(...) returns Flux<StreamingEvent>, bridging Spring AI's ChatClient.prompt().stream() with a Sinks.Many tool-event channel (merged via mergeWith) so ToolCallbackAuditDecorator can inject live ToolCall/ToolResult events into the same Flux the ChatView subscribes to. CancellationRegistry gets Disposable-aware overloads so the ChatView Stop button can dispose the upstream subscription synchronously. IngestionStatusWriter emits DocumentStatusChangedEvent via afterCommit on every status transition so KnowledgeBaseView (07-06) can subscribe without polling.**

## Performance

- **Duration:** ~35 minutes wall clock
- **Tasks:** 3 (all `type="auto"`)
- **Files created:** 2
- **Files modified:** 8 (6 main + 2 test)
- **Commits:** 3 atomic task commits

## Task Commits

1. **Task 1 — ChatService.stream contract + ChatStreamingSchedulerConfig + StreamingSinkHolder + DefaultChatServiceImpl stub** — `d2950d2`
2. **Task 2 — streaming backbone + tool-event emission + cancel-disposable wiring** — `ec23da9`
3. **Task 3 — IngestionStatusWriter afterCommit DocumentStatusChangedEvent** — `6747ac7`

## Accomplishments

### ChatService.stream contract

`ChatService.java` gains one new method after the four existing ask/askTyped overloads:

```java
Flux<StreamingEvent> stream(String userId, UUID conversationId, String message, Overrides overrides);
```

All four pre-existing methods are preserved verbatim (`grep -cE "^\s+(ChatResponseDto ask|<T> T askTyped)" ChatService.java` = 4).

### ChatStreamingSchedulerConfig

`@Configuration` class `ChatStreamingSchedulerConfig` exposing `@Bean(name="chatStreamingScheduler", destroyMethod="dispose") Scheduler` = `Schedulers.newBoundedElastic(20, 1000, "ai-agent-stream")`.

Class renamed from the plan's literal `ChatStreamingScheduler` to avoid BeanDefinitionOverrideException (the @Configuration class bean id collided with the factory-method bean id).

### StreamingSinkHolder

`@Component` request-scoped-by-runId sink registry. API:
- `register(UUID, Sinks.Many<StreamingEvent>)` — called by DefaultChatServiceImpl.stream before subscription
- `unregister(UUID)` — called from doFinally
- `current()` — looks up sink via RunContext.get()
- `forRun(UUID)` — direct lookup (tests + diagnostics)

Backing store: `ConcurrentHashMap<UUID, Sinks.Many<StreamingEvent>>`.

### DefaultChatServiceImpl.stream

Full implementation (98 lines). Key structure:

1. Pre-allocate `runId = UUID.randomUUID()` + `startNanos = System.nanoTime()` OUTSIDE Flux.defer (so the runId is stable across subscription retries).
2. Inside `Flux.defer { ... }`:
   - `RunContext.set(runId)`
   - Create `Sinks.Many<StreamingEvent> toolSink = Sinks.many().unicast().onBackpressureBuffer()`
   - `streamingSinkHolder.register(runId, toolSink)`
   - `conversationGateway.loadOrCreate(userId, convId, msg)` for ownership + autocreate
   - Resolve params: model, systemPrompt, baseline, ragFilter
   - Build `content: Flux<StreamingEvent>` via `chatClient.prompt().system(...).user(...).toolCallbacks(...).advisors(...).options(...).stream().chatResponse().concatMap(chunk -> Content text)`. `doOnComplete(toolSink::tryEmitComplete)` + `doOnError(ex -> toolSink.tryEmitComplete())` to terminate the merge cleanly.
   - UnsupportedOperationException -> D-04 graceful fallback: blocking `ask(...)` wrapped as a single Content chunk.
   - `merged = toolSink.asFlux().mergeWith(content).concatWith(Flux.defer { Final(runId, latencyMs, 0, 0) })`
3. Chain operators:
   - `.subscribeOn(chatStreamingScheduler)` — off Tomcat.
   - `.doOnSubscribe(subscription -> cancellationRegistry.register(runId, (Disposable) subscription::cancel))` — Pattern A cancellation wiring.
   - `.onErrorResume(ex -> Flux.just(mapToStreamingError(ex)))` — typed exceptions mapped to stable i18n keys.
   - `.doFinally(signal -> { RunContext.clear(); streamingSinkHolder.unregister(runId); cancellationRegistry.clearDisposable(runId); })` — cleanup regardless of terminal signal.

`mapToStreamingError` maps: RateLimit/TokenBudget/IterationCap/ToolVetoed -> `ai-agent.guard.*` keys; ConversationNotFound -> `chatView.error.conversationNotFound`; generic -> `chatView.error.generic`. Empty params Map (never raw provider text — T-07-05).

### CancellationRegistry extension

Additive overloads (no breaking changes to existing document-cancellation generation/boolean API):

```java
public void register(UUID runId, Disposable disposable) { ... }
public void clearDisposable(UUID runId) { ... }
// Existing cancel(UUID) now also disposes any registered chat-stream Disposable.
```

`disposables` map (`ConcurrentHashMap<UUID, Disposable>`) separate from the `cancelled` set / `cancelledAtOrBelow` map — no interference with the RAG worker cancellation semantics.

### ToolCallbackAuditDecorator streaming extension

- Optional `StreamingSinkHolder` injection via a new 4-arg constructor overload (`ToolCallback, AuditWriter, CurrentAuthentication, StreamingSinkHolder`); the original 3-arg constructor delegates with `null`, preserving any pure-unit construction.
- `callInternal` now:
  1. Allocates a `toolCallId = UUID.randomUUID()` BEFORE delegate invocation.
  2. Emits `StreamingEvent.ToolCall(toolCallId, toolName, cappedInput)` via `emitToolEvent(sink -> sink.tryEmitNext(...))`.
  3. Delegate invocation (unchanged).
  4. In `finally`: after POST audit-row write, emits `StreamingEvent.ToolResult(toolCallId, resultSummary, outcome)` — success AND error paths.
- `emitToolEvent(Consumer<Sinks.Many<StreamingEvent>>)` is a private helper that checks for null sinkHolder (3-arg constructor case) then `.current().ifPresent(emitter)`. Blocking `ask(...)` path sees `Optional.empty()` — zero behavior change for Phase 4 semantics.

### AgentToolCallbacks

Constructor extended with `StreamingSinkHolder streamingSinkHolder` parameter (Spring autowires). `forCurrentUser` passes it into every `new ToolCallbackAuditDecorator(...)` construction site (1 line change).

### IngestionStatusWriter afterCommit

- Constructor extended with `ApplicationEventPublisher publisher` (constructor injection, not `@Autowired`).
- Five mutation methods (`markPending / markProcessing / markReady / markFailed / markCancelled`) each call `registerAfterCommit(id, status, errorMessage)` after `dataManager.save(doc)` inside the `ifPresentOrElse` success branch.
- `registerAfterCommit` private helper registers a `TransactionSynchronization` that in `afterCommit()` calls `publisher.publishEvent(new DocumentStatusChangedEvent(id, status, errorMessage))`. Defensive: if `TransactionSynchronizationManager.isSynchronizationActive()` is false (should never happen under REQUIRES_NEW), publishes inline with a warning log.
- `markFailed` captures `truncatedMessage` BEFORE calling `setErrorMessage(...)` so the event carries the exact persisted value.
- Event-publish failures wrapped in try/catch — never throw out of afterCommit (would surface as a phantom error to the already-committed caller).

## Open Questions Resolved

### Spring AI 1.1.4 stream API shape

Used `chatClient.prompt()...stream().chatResponse()` returning `Flux<ChatResponse>`. Each chunk's `.getResult().getOutput()` is an `AssistantMessage`; its `.getText()` is the incremental markdown. When text is null/empty (tool-call-only chunks) the concatMap emits `Flux.empty()` — no zero-byte Content events on the wire.

### CancellationRegistry API (pre-existing)

Pre-existing API (verified byte-for-byte from source):
- `public void register(UUID id)` — legacy no-op for future metrics, UNCHANGED
- `public long currentGeneration(UUID id)`
- `public long bumpGeneration(UUID id)`
- `public void cancel(UUID id)` — extended to also dispose any Disposable registered via the new overload
- `public boolean isCancelled(UUID id)` / `isCancelled(UUID, long)`
- `public void clear(UUID id)` — UNCHANGED

Plan 07-02 additions (additive, non-breaking):
- `public void register(UUID runId, Disposable disposable)`
- `public void clearDisposable(UUID runId)`

### Pattern A vs Pattern B cancellation wiring

**Pattern A chosen.** `.doOnSubscribe(subscription -> cancellationRegistry.register(runId, (Disposable) subscription::cancel))` registers a @FunctionalInterface-cast lambda BEFORE tokens flow. Validation: when the ChatView wires its Stop button to `cancellationRegistry.cancel(runId)`, the registry calls `disposable.dispose()` which invokes `subscription.cancel()`, which cascades through the reactor chain to tear down the upstream `chatClient.prompt().stream()` call AND the tool sink (via the Flux chain's dispose propagation).

### ToolCallbackAuditDecorator insertion points

- **Entry (ToolCall emission):** immediately BEFORE the boolean-success try/catch that invokes the delegate, AFTER the PRE audit row write. This keeps PRE audit ordering (audit row first, UI event second) consistent with the blocking path — observers of the audit table always see PRE before any tool-call event hits the UI.
- **Exit (ToolResult emission):** inside the `finally` block, AFTER the POST audit row write. Emits once for success AND once for error (the outcome variable carries SUCCESS/ERROR). Ensures the UI's tool-call card closes only after the audit row is durable — no race where a listener sees a completed card that the audit trail hasn't yet captured.
- `toolCallId` is a local final UUID threaded from entry to exit so the pair correlates in the UI (ChatView ToolCallCardComponent.setResult matches by id).

## Verification

| Command | Result |
|---|---|
| `./gradlew :ai-agent:ai-agent:compileJava` | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:compileTestJava` | BUILD SUCCESSFUL (both pre-existing direct-new-DefaultChatServiceImpl tests updated with 3 new null args) |
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.rag.CancellationRegistryTest"` | BUILD SUCCESSFUL — pre-existing generation/boolean cancellation semantics unbroken by additive Disposable overload |
| `grep -c "Flux<StreamingEvent> stream" ChatService.java` | 1 |
| `grep -c "Flux<StreamingEvent> stream" DefaultChatServiceImpl.java` | 1 |
| `grep -c "Sinks.many" DefaultChatServiceImpl.java` | 1 |
| `grep -c "subscribeOn(chatStreamingScheduler)" DefaultChatServiceImpl.java` | 1 |
| `grep -c "cancellationRegistry.register" DefaultChatServiceImpl.java` | 1 |
| `grep -c "streamingSinkHolder.register\|streamingSinkHolder.unregister" DefaultChatServiceImpl.java` | 2 |
| `grep -c "mapToStreamingError\|StreamingEvent.Error" DefaultChatServiceImpl.java` | 3 |
| `grep -c "StreamingEvent.ToolCall\|StreamingEvent.ToolResult" ToolCallbackAuditDecorator.java` | 2 |
| `grep -c "streamingSinkHolder" ToolCallbackAuditDecorator.java` | 4 |
| `grep -cE "public ChatResponseDto ask\(\|public <T> T askTyped\(" DefaultChatServiceImpl.java` | 4 |
| `grep -c "class StreamingSinkHolder" StreamingSinkHolder.java` | 1 |
| `grep -cE "register\(\|unregister\(\|current\(\)\|forRun\(" StreamingSinkHolder.java` | 6 |
| `grep -c "ai-agent-stream" ChatStreamingSchedulerConfig.java` | 1 |
| `grep -c "DocumentStatusChangedEvent" IngestionStatusWriter.java` | 5 |
| `grep -c "ApplicationEventPublisher" IngestionStatusWriter.java` | 3 |
| `grep -c "afterCommit\|registerAfterCommit" IngestionStatusWriter.java` | 9 |

## Decisions Made

1. **Pattern A for cancellation wiring.** Used `doOnSubscribe -> register(runId, subscription::cancel)` (Disposable is @FunctionalInterface). Avoids needing to expose runId to callers via a synthetic Started(runId) event; keeps the StreamingEvent surface at the six variants 07-01 defined.
2. **Rename ChatStreamingScheduler -> ChatStreamingSchedulerConfig.** Discovered during test context load that an @Configuration class whose uncapitalized class name matches an explicit @Bean name triggers BeanDefinitionOverrideException. Renaming the class (NOT the bean) keeps the plan's bean-name contract stable while eliminating the clash.
3. **Decorator constructor overload instead of signature change.** Original 3-arg `ToolCallbackAuditDecorator(delegate, auditWriter, currentAuthentication)` preserved for compat; new 4-arg overload adds `StreamingSinkHolder`. The 3-arg delegates to 4-arg with null sink holder; `emitToolEvent` checks for null before calling `current()` — zero NPE risk for non-streaming constructions.
4. **Helper method consolidation in IngestionStatusWriter.** Five inline 10-line `TransactionSynchronization` blocks collapsed into one 18-line `registerAfterCommit(id, status, errorMessage)` helper with five 1-line call sites. Preserves the plan's semantic contract exactly; slightly reduces grep counts for `registerSynchronization` / `DocumentStatusChangedEvent` but raises overall `afterCommit` + `registerAfterCommit` reference count to 9.
5. **Pre-allocate runId outside Flux.defer.** `UUID.randomUUID()` + `System.nanoTime()` captured in the lambda closure OUTSIDE the defer block. Ensures the SAME runId is seen by subscribeOn/doOnSubscribe/doFinally across retry-on-error scenarios (defer is called once per subscription; the outer UUID is stable for the single subscription the ChatView creates).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] Pre-existing tests directly construct DefaultChatServiceImpl**
- **Found during:** Task 2 compileTestJava — two tests (AskTypedRetryTest, ChatServiceFilterParamContractTest) instantiate `new DefaultChatServiceImpl(...)` directly with 11 args.
- **Issue:** Adding 3 new constructor params broke test compile. The plan does not mention these tests.
- **Fix:** Added `/* chatStreamingScheduler */ null, /* cancellationRegistry */ null, /* streamingSinkHolder */ null` after the last existing arg in both tests. The tests exercise only `ask(...)` / `askTyped(...)` paths, never `stream(...)`, so nulls are safe — `stream(...)` would NPE but it is never called.
- **Files modified:** `AskTypedRetryTest.java`, `ChatServiceFilterParamContractTest.java`
- **Commit:** `ec23da9`

**2. [Rule 1 — Bug] BeanDefinitionOverrideException on @Configuration class name collision**
- **Found during:** Task 2 `./gradlew :ai-agent:ai-agent:test` — `AuditDurabilityTest` (and every other @SpringBootTest) failed with `BeanDefinitionOverrideException: Invalid bean definition with name 'chatStreamingScheduler'` because the @Configuration class `ChatStreamingScheduler` (auto-named `chatStreamingScheduler`) clashed with its own @Bean factory method of the same name.
- **Issue:** Spring registers component-scanned @Configuration classes as beans under their uncapitalized class name. When that collides with an explicit `@Bean(name=...)` id from the same class, context load fails.
- **Fix:** Renamed the class to `ChatStreamingSchedulerConfig`. The `@Bean(name = "chatStreamingScheduler", destroyMethod = "dispose")` is unchanged, so the plan's bean-name contract is preserved. DefaultChatServiceImpl still uses `@Qualifier("chatStreamingScheduler")`.
- **Files modified:** deleted `ChatStreamingScheduler.java`, created `ChatStreamingSchedulerConfig.java`
- **Commit:** `ec23da9`

**3. [Rule 2 — Added critical functionality] Stub stream() in DefaultChatServiceImpl at Task 1 commit**
- **Found during:** Task 1 — adding the abstract `stream(...)` method to `ChatService` would have broken `DefaultChatServiceImpl` compile immediately, making Task 1's `compileJava` verification fail.
- **Issue:** Plan's Task 1 verify = "compileJava succeeds" but Task 2 is the one that fully implements stream().
- **Fix:** Task 1 commit adds a minimal `@Override public Flux<StreamingEvent> stream(...) { return Flux.error(new UnsupportedOperationException("... see Plan 07-02 Task 2")); }` — compiles clean, fails loudly if called before Task 2. Task 2 replaces it with the full implementation.
- **Files modified:** `DefaultChatServiceImpl.java` (Task 1 commit, replaced in Task 2)
- **Commit:** `d2950d2` (stub), `ec23da9` (replacement)

## Authentication Gates

None.

## Deferred / Follow-up

- **Pre-existing @SpringBootTest failure `View 'login' is not defined`.** Introduced by Plan 07-01 when `io.jmix.security:jmix-security-flowui-starter` was added. Tests like `AuditDurabilityTest`, `IngestionStatusWriterTest`, `ToolCallAuditListViewTest` fail at context load because `DefaultFlowuiVaadinWebSecurity` requires a login view that the add-on test app does not provide. Out of scope for Plan 07-02 (both confirmed by git-stash baseline run and by reading the 07-01 summary which did NOT claim full test-suite green post-additions). Likely resolved by 07-07b when the view-fleet is complete; if not, a test-only `@Configuration` that defines a stub login view will be needed.
- **Chat-stream Disposable registry entry leak on orphan subscription.** Current design: `doOnSubscribe` registers; `doFinally` calls `clearDisposable`. If a subscription is created but the terminal doFinally never runs (JVM crash mid-stream), the entry leaks. Mitigation: the next `cancel(runId)` for that id disposes+removes (idempotent), so leak is bounded per-runId and cleaned by a normal Stop. Not a correctness issue for v1 single-JVM.
- **Streaming error path verification is compile-only for now.** `mapToStreamingError` branches are structurally sound but the 07-07b Wave 0 ChatViewStreamTest will exercise the full Flux<StreamingEvent> sequence end-to-end against a stubbed ChatClient.

## Threat Flags

None — every new surface is covered by the plan's `<threat_model>`:

- **T-07-05 (Information Disclosure in stream error path)** — `mapToStreamingError` rewrites every typed guard exception + ConversationNotFoundException + generic Throwable into a stable `chatView.error.*` / `ai-agent.guard.*` i18n key with empty params Map. Raw exception text never reaches the Flux.
- **T-07-06 (DoS via stream subscription lifecycle)** — dedicated `boundedElastic(20, 1000, "ai-agent-stream")` isolates from Tomcat pool; `doFinally` cleanup is signal-type-agnostic (ON_COMPLETE, ON_ERROR, CANCEL all clear entries).
- **T-07-07 (Tampering — afterCommit fires on rollback)** — `TransactionSynchronizationManager.registerSynchronization` hooks `afterCommit()` which Spring NEVER invokes on rollback; defensive log-only warning path for the impossible "no synchronization active" case.
- **T-07-07a (Information Disclosure — tool-event emission leaks raw tool output)** — the `cappedInput` / `resultSummary` values emitted to the sink are the SAME values already passing through the existing sanitized Phase-4 audit write path (capped via `cap(..., ARGUMENTS_JSON_MAX_CHARS)` / `cap(..., RESULT_SUMMARY_MAX_CHARS)` with `TRUNCATION_SUFFIX`). No new sanitization surface introduced.

## Self-Check: PASSED

Artifacts verified:

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java`
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` — stream() method present
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — full stream() impl + mapToStreamingError helper
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java` — 4-arg constructor + emitToolEvent helper + ToolCall/ToolResult emission sites
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` — sink-holder passed to decorator
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java` — register(UUID, Disposable) + clearDisposable + cancel() Disposable integration
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java` — 5 registerAfterCommit call sites
- FOUND commit: `d2950d2` (Task 1 — ChatService.stream contract + scheduler + sink holder)
- FOUND commit: `ec23da9` (Task 2 — streaming backbone + tool-event emission + cancel-disposable wiring)
- FOUND commit: `6747ac7` (Task 3 — IngestionStatusWriter afterCommit DocumentStatusChangedEvent)

Build gates:

- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:compileTestJava` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.rag.CancellationRegistryTest"` → BUILD SUCCESSFUL

---
*Phase: 07-flow-ui*
*Completed: 2026-04-21*
