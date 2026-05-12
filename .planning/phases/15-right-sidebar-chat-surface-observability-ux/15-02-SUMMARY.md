---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 02
subsystem: api
tags: [streaming-events, sealed-interface, observability, rag, tool-callback, spring-ai]

# Dependency graph
requires:
  - phase: 07-streaming-chat (v1.0.0)
    provides: StreamingEvent sealed interface, StreamingSinkHolder per-run sink registry, ToolCallbackAuditDecorator best-effort emit pattern
  - phase: 07.2-audit-schema-tree-lite (v1.0.0)
    provides: AuditingDocumentRetriever (audit-write-never-rethrows convention), RetrievalAugmentationAdvisorFactory inline retriever wiring
provides:
  - "StreamingEvent.Activity(ActivityKind) additive variant + closed enum ActivityKind {CHAT, TOOL, RETRIEVAL}"
  - "Activity(RETRIEVAL) emitted best-effort at AuditingDocumentRetriever.retrieve(...) start"
  - "Activity(TOOL) emitted best-effort alongside ToolCallbackAuditDecorator's ToolCall emit"
  - "StreamingSinkHolder wired into AuditingDocumentRetriever via RetrievalAugmentationAdvisorFactory"
  - "Compiler-forced no-op Activity arm in StreamEventRenderer.renderStreamEventDetails"
affects: [15-04 (ChatPanelFragment ephemeral status line consumes Activity), 15-03 (SIDEBAR surface), 15-05 (TEST-19 leak-regex test over rendered status text)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Additive sealed-interface variant: add to permits clause + compiler-forced no-op arm in every exhaustive switch (here only StreamEventRenderer; ChatPanelFragment/DefaultChatServiceImpl use instanceof chains)"
    - "Best-effort observability emit: tryEmitNext wrapped in catch(RuntimeException), null-guarded sink holder — mirrors ToolCallbackAuditDecorator.emitToolEvent and the retriever's audit-write-never-rethrows convention"
    - "Closed-enum no-leak guarantee: Activity carries only an ActivityKind constant — structurally cannot carry a @Tool method name / argsJson / entity name"

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/StreamingActivityEventTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactoryTest.java

key-decisions:
  - "Activity(CHAT) is NOT emitted from the orchestration edge (review point #11) — DefaultChatServiceImpl unchanged; the UI (Plan 04) derives CHAT from the first Content event of the turn"
  - "Added back-compat constructor overloads to AuditingDocumentRetriever (3-arg and 5-arg delegate to the new 4-arg/6-arg streamingSinkHolder forms with null) to avoid touching the 3 existing test callers"

patterns-established:
  - "Additive StreamingEvent variant pattern: permits-clause entry + no-op arm in the single exhaustive switch (StreamEventRenderer)"
  - "Best-effort streaming-status emit at the orchestration edge (retriever + tool decorator) — never breaks the security-critical path"

requirements-completed: [OBS-01, OBS-04]

# Metrics
duration: ~35min
completed: 2026-05-11
---

# Phase 15 Plan 02: StreamingEvent.Activity Variant + Edge Emit Summary

**Additive `StreamingEvent.Activity(ActivityKind)` variant (closed enum `{CHAT, TOOL, RETRIEVAL}`) with best-effort `Activity(RETRIEVAL)`/`Activity(TOOL)` emits from `AuditingDocumentRetriever` and `ToolCallbackAuditDecorator`; `Activity(CHAT)` deliberately not emitted (UI derives it); `DefaultChatServiceImpl` untouched.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-05-11T22:18:00Z (approx)
- **Completed:** 2026-05-11T22:55:00Z (approx)
- **Tasks:** 2
- **Files modified:** 7 (1 created, 6 modified)

## Accomplishments
- Added `StreamingEvent.Activity(ActivityKind kind)` record + nested closed enum `ActivityKind { CHAT, TOOL, RETRIEVAL }`, with Javadoc documenting the no-leak guarantee and the "CHAT is legal but never emitted from the edge" rule (review point #11).
- Added the compiler-forced no-op `case StreamingEvent.Activity ignoredActivity -> RenderedStreamEvent.markdown("")` arm to `StreamEventRenderer.renderStreamEventDetails` (the only exhaustive switch over `StreamingEvent` in main).
- `ToolCallbackAuditDecorator.callInternal` now emits `Activity(TOOL)` via the existing best-effort `emitToolEvent` helper, immediately before the existing `ToolCall` emit.
- `AuditingDocumentRetriever` gained a `private final StreamingSinkHolder streamingSinkHolder` field (last constructor param, with back-compat overloads delegating with `null`) and emits `Activity(RETRIEVAL)` at the start of `retrieve(...)` via `currentOrForRun(runId).ifPresent(...)` wrapped in `catch (RuntimeException)`.
- `RetrievalAugmentationAdvisorFactory.retrievalAugmentationAdvisor(...)` now injects `StreamingSinkHolder` and passes it to the inline `new AuditingDocumentRetriever(...)`.
- New `StreamingActivityEventTest` (pure JUnit5 + Mockito, no Spring): tool call emits exactly one `Activity(TOOL)` and never `Activity(CHAT)`; retrieval emits exactly one `Activity(RETRIEVAL)`; null sink holder and a throwing sink never break the tool call / retrieval.
- `DefaultChatServiceImpl` is unchanged (`git status` confirms no edit) — `Activity(CHAT)` is not emitted from the edge.

## Grep proxy result

`grep -E "switch\s*\(\s*\w*[Ee]vent\w*\s*\)" ai-agent/ai-agent/src/main/java` (and a follow-up scan of `ChatPanelFragment.java` + `DefaultChatServiceImpl.java`) confirms:

- `StreamEventRenderer.java:120` — `return switch (event) {` — the **only** exhaustive `switch` over `StreamingEvent` in main.
- `ChatPanelFragment.java` — no `switch` over a `StreamingEvent` (the one `switch`-word hit at ~line 557 is inside a doc comment, not a real `switch`); it uses an `instanceof` chain.
- `DefaultChatServiceImpl.java:808` — `return switch (info.messageKey())` — a `String` switch, not over `StreamingEvent`.

So adding the variant required exactly one new arm; the add-on test module (`./gradlew :ai-agent:ai-agent:compileTestJava`) compiles cleanly — no other exhaustive switch was missed.

## Exact emit-site lines added

`ToolCallbackAuditDecorator.callInternal(...)` (before the existing `ToolCall` emit):
```java
emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.TOOL)));
```

`AuditingDocumentRetriever.retrieve(Query)` (right after `runId` is resolved):
```java
emitRetrievalActivity(runId);
```
where:
```java
private void emitRetrievalActivity(UUID runId) {
    if (streamingSinkHolder == null) {
        return;
    }
    try {
        streamingSinkHolder.currentOrForRun(runId).ifPresent(sink ->
                sink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.RETRIEVAL)));
    } catch (RuntimeException ignored) {
        log.debug("Streaming retrieval-activity emission failed; continuing retrieval", ignored);
    }
}
```

`StreamEventRenderer.renderStreamEventDetails(...)`:
```java
case StreamingEvent.Activity ignoredActivity ->
        RenderedStreamEvent.markdown("");
```

## Task Commits

1. **Task 1: Add the StreamingEvent.Activity variant and the compiler-forced renderer arm** — `95d3cdf` (feat) — TDD: test extension + implementation committed together (additive variant, trivial GREEN).
2. **Task 2: Emit Activity(RETRIEVAL) / Activity(TOOL) from the orchestration edge, best-effort (NOT Activity(CHAT))** — `c32d0be` (feat) — TDD: new test + implementation committed together.

**Plan metadata:** _(this commit)_ `docs(15-02): complete plan`

## Files Created/Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java` — `Activity` record + `ActivityKind` enum + permits-clause entry
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java` — no-op `case StreamingEvent.Activity` arm
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java` — `StreamingSinkHolder` ctor field + back-compat overloads + `emitRetrievalActivity(...)` at retrieve start
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java` — inject `StreamingSinkHolder`, pass to inline `new AuditingDocumentRetriever(...)`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java` — `Activity(TOOL)` emit alongside `ToolCall`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventTest.java` — `Activity(...)` empty-markdown + closed-enum assertions
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/StreamingActivityEventTest.java` — new: edge emit-site tests (best-effort, never breaks the call)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactoryTest.java` — updated for the new `StreamingSinkHolder` bean-method arg

## Decisions Made
- **Activity(CHAT) not emitted from the edge** — confirmed per review point #11; `DefaultChatServiceImpl` not modified. `ActivityKind` still includes `CHAT` (legal audit `kind`, UI uses `statusKeyFor(CHAT)`); the UI (Plan 04) derives CHAT from the first `Content` event.
- **Back-compat constructor overloads** on `AuditingDocumentRetriever` — added 4-arg `(DocumentRetriever, AuditWriter, CurrentAuthentication, StreamingSinkHolder)` and 6-arg `(VectorStore, int, double, AuditWriter, CurrentAuthentication, StreamingSinkHolder)` forms; the pre-existing 3-arg / 5-arg constructors now delegate with `null` sink holder so the 3 existing test callers stay untouched.

## Deviations from Plan

None — plan executed exactly as written. The plan explicitly anticipated touching `RetrievalAugmentationAdvisorFactoryTest` for the new ctor arg; the 3-arg `AuditingDocumentRetriever` back-compat overload was the cleanest way to honor "add `streamingSinkHolder` as the last param" without rewriting unrelated tests (within the plan's intent — additive, no behavior change).

## Issues Encountered
None — both TDD tasks went RED→GREEN cleanly; full add-on module test suite green after both tasks.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- `StreamingEvent.Activity` + `ActivityKind` are available for Plan 04 (`ChatPanelFragment` ephemeral status line) and Plan 03 (`SIDEBAR` surface).
- `Activity(TOOL)` / `Activity(RETRIEVAL)` flow into the existing `StreamingEvent` Flux best-effort; Plan 04 must add the `chatView.status.*` `msg://` keys and the `instanceof StreamingEvent.Activity` arm in the fragment's render chain.
- No change to the persisted `AiAuditEvent` shape, no new audit row, no new Liquibase changelog, no advisor-chain behavior change beyond the `StreamingSinkHolder` injection — confirmed via `git status` / full add-on test green.

## Threat Flags

None — no new security surface introduced. `Activity` carries only a closed enum constant; all emits are best-effort and OUTSIDE `ChatService`-proper, the advisor chain, and the audit-write path (matches the plan's threat register T-15-B1..B4).

## Self-Check: PASSED

All 9 created/modified files exist on disk; both task commits (`95d3cdf`, `c32d0be`) present in git log.

---
*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Completed: 2026-05-11*
