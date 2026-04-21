---
phase: 07-flow-ui
plan: 03
subsystem: flow-ui
tags: [flowui, chat, streaming, push, fragment, markdown, vaadin]
requires:
  - ChatService.stream (Phase 04 DefaultChatServiceImpl)
  - ConversationGateway.loadOrCreate (Phase 04, ownership-opacity enforced)
  - CancellationRegistry (07-02, for Stop dispatch alt-path; v1 uses direct Disposable.dispose)
  - MarkdownRenderer (07-02, Flexmark + OWASP sanitizer)
  - KnowledgeBaseView (07-06, navigation target for CitationDialog)
  - AiToolCallOutcome enum (SUCCESS/BLOCKED/ERROR/FLAGGED)
  - StreamingEvent sealed variants (Content/ToolCall/ToolResult/Citation/Final/Error)
provides:
  - "@Route(ai-agent/chat) ChatView + chat-view.xml composing ChatPanelFragment"
  - "Jmix Fragment<VerticalLayout> ChatPanelFragment — reusable D-29 substrate for v2 floating launcher"
  - "MessageBubbleComponent, ToolCallCardComponent, CitationDialog — shared UI primitives (ConversationDetailView 07-04 will reuse)"
  - "50ms Flux.bufferTimeout content coalescing (≤20 Hz DOM mutations under fast providers)"
affects:
  - "messages.properties / messages_vi.properties — added chatView.citation.close key"
tech-stack:
  added: []
  patterns:
    - "Jmix Fragment (XML descriptor + @FragmentDescriptor + @ViewComponent field injection)"
    - "Dispose-on-detach (Pitfall #8): onDetach disposes activeStream Disposable"
    - "UI.access per 50ms batch (Pitfall #7): Flux.bufferTimeout(64, 50ms) caps DOM updates"
    - "Vaadin 24.9 Style API: raw .set(\"white-space\", \"pre-wrap\") over typed WhiteSpace enum"
    - "BeforeEnterObserver.beforeEnter for ?conversationId query param routing"
    - "Dialogs.createOptionDialog for New-chat confirm (matches KnowledgeBaseView pattern)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/MessageBubbleComponent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ToolCallCardComponent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "Use Flux.bufferTimeout(64, 50ms) over the full event stream (simpler compile shape than publish+groupBy). Structural events are bunched by 50ms too — acceptable because tool cards / citations are not real-time critical (plan Rule 1 tolerance)."
  - "Ship ChatPanelFragment as Jmix Fragment with XML descriptor (D-29 substrate) — ChatView's chat-view.xml embeds it via <fragment class=...> declaration."
  - "Outcome badge themes mapped against REAL AiToolCallOutcome enum values (SUCCESS/BLOCKED/ERROR/FLAGGED), not the plan-assumed FAILED/DENIED/TIMEOUT/CANCELLED set (Plan 07-06's Rule-1 precedent re-applied)."
  - "Vaadin Style API typed enums WhiteSpace/Overflow require String constants via .set(key,value) — typed setters would not compile with plan's String literals."
  - "T-07-09 mitigation: ConversationNotFoundException caught in ChatView.beforeEnter + fallthrough with generic error notification; raw exception never reaches UI."
  - "T-07-10 mitigation: Fragment.onDetach disposes activeStream unconditionally."
metrics:
  duration: 1 session
  completed: 2026-04-21
---

# Phase 07 Plan 03: ChatView + streaming UI Summary

Three Wave-3 commits deliver the live-streaming chat experience on route `/ai-agent/chat` — a reusable Jmix Fragment (D-29) wired to `ChatService.stream` through a 50 ms `Flux.bufferTimeout` that caps UI.access at ≤20 Hz (Pitfall #7), plus collapsed tool cards, citation dialog with real KnowledgeBaseView navigation, and confirm-on-reset.

## What shipped

1. **Shared UI primitives (commit `0452132`)** — `MessageBubbleComponent` (role-styled, OWASP-sanitized markdown via `MarkdownRenderer`), `ToolCallCardComponent` (Vaadin `Details` with outcome badge), `CitationDialog` (typed `UI.navigate(KnowledgeBaseView.class, QueryParameters.of("documentId", uuid))`). Added `chatView.citation.close` to EN + VI bundles.

2. **ChatPanelFragment + XML descriptor (commit `107bf28`)** — `@FragmentDescriptor("chat-panel-fragment.xml")` Fragment hosting empty-state panel, message scroller, text area input, Send/Stop buttons. `onSendClick` subscribes to `ChatService.stream(userId, conversationId, text, null)` via `.bufferTimeout(64, Duration.ofMillis(50)).filter(not-empty).subscribe(handleBatch, handleError, finishStream)`. `handleBatch` concatenates Content events into a single `appendMarkdown` call and dispatches structural events under one `UI.access` per window. `onDetach` disposes `activeStream` (Pitfall #8).

3. **ChatView route host (commit `4ce141a`)** — `@Route("ai-agent/chat")` `StandardView` with `@ViewController("AiAgent_Chat")` (matches menu.xml from 07-01). `chat-view.xml` embeds the fragment via `<fragment class="com.vn.agent.view.chat.fragment.ChatPanelFragment" .../>`. `BeforeEnterObserver.beforeEnter` reads `conversationId` query param; malformed UUIDs and `ConversationNotFoundException` fall through to generic error notification + fresh chat (T-07-09). New chat button uses `Dialogs.createOptionDialog` confirm when the panel has messages; empty panel resets silently.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava :ai-agent:ai-agent:processResources` → `BUILD SUCCESSFUL` after each task.
- Done-criteria greps: `bufferTimeout` (3+), `Duration.ofMillis(50)` (1), `dispose()` (2), `UI.access` references (3+), `msg://` in XML (5), `AiAgent_Chat` (1), `ai-agent/chat` (1), `ChatPanelFragment` in ChatView (2+), `conversationId` (4+) — all satisfied.
- No `lastRenderNanos` (plan's alternate-shape tripwire) present.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Plan-API bug] Vaadin Style typed enums**
- **Found during:** Task 1
- **Issue:** Plan sketched `style.setWhiteSpace("pre-wrap")` / `style.setOverflow("auto")`. Vaadin 24.9 requires typed enums (`WhiteSpace`, `Overflow`), which do not accept String.
- **Fix:** Switched to raw `style.set("white-space", "pre-wrap")` / `style.set("overflow", "auto")` in `ToolCallCardComponent` and `CitationDialog`.
- **Files modified:** ToolCallCardComponent.java, CitationDialog.java
- **Commit:** 0452132

**2. [Rule 1 — Plan-assumption bug] Outcome enum mismatch**
- **Found during:** Task 1
- **Issue:** Plan assumed `AiToolCallOutcome { SUCCESS, FAILED, DENIED, TIMEOUT, CANCELLED }`. The real enum is `{ SUCCESS, BLOCKED, ERROR, FLAGGED }` (confirmed by grep + 07-06 precedent).
- **Fix:** Outcome→theme map: SUCCESS→success, ERROR→error, BLOCKED→warning, FLAGGED→contrast.
- **Commit:** 0452132

**3. [Rule 2 — missing i18n key] chatView.citation.close**
- **Found during:** Task 1
- **Issue:** Plan referenced `actions.cancel` for the dialog close button; no such key exists in the bundle.
- **Fix:** Added `chatView.citation.close` to both EN ("Close") and VI ("Đóng") message bundles per CLAUDE.md "ALL locale files" rule.
- **Commit:** 0452132

No architectural (Rule 4) deviations.

## Authentication Gates

None — plan executed autonomously.

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/MessageBubbleComponent.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ToolCallCardComponent.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java
- FOUND commit: 0452132
- FOUND commit: 107bf28
- FOUND commit: 4ce141a
