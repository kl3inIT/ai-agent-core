---
created: 2026-04-26T02:50:00+07:00
title: Add collapsible tool-detail and ephemeral status to chat UI
area: ui
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
---

## Problem

Even after the prompt-side fix (todo
`hide-internal-tool-and-entity-names-from-user-facing-chat.md`) ensures the model no longer echoes
internal entity names or tool names in the reply text, two UI-level problems remain:

1. **Power users / developers still want to see what the AI did** — number of tool calls, latency,
   parent/child tree of events. Today they have to leave the chat and open the audit list view
   manually, scroll, find the matching `runId`. High friction.
2. **Streaming status text gets concatenated into the final reply.** While the model is working,
   the chat surface tends to render intermediate text like *"Để tôi tìm kiếm..."* / *"Đang truy
   xuất tài liệu..."* as part of the message bubble. Once the assistant finishes, that prefix
   stays in the bubble forever — turning operational status into permanent reply content.

This is purely a UI architecture issue. The audit data already exists (and gets a clean tree-lite
shape from the in-flight redesign). The chat layout just needs a place to put it.

## Solution

Two coordinated UI surfaces inside `ChatPanelFragment`:

### 1. Collapsible "AI did" panel per assistant turn

Per assistant message bubble, add a secondary slot below (or attached to) the bubble:

```
┌─────────────────────────────────────────┐
│ Hiện có 5 khách hàng ở Hà Nội: An, …   │  ← message bubble (reply only)
│                                          │
│ ▶ AI đã làm gì (3 bước, 1.2s)          │  ← collapsed by default
└─────────────────────────────────────────┘
```

Expanded:

```
│ ▼ AI đã làm gì (3 bước, 1.2s)              │
│   1. Tra cứu danh sách entity (3ms)         │
│   2. Tìm khách hàng ở Hà Nội (37ms)         │
│   3. Truy xuất tài liệu liên quan (980ms)   │
│   [Xem chi tiết trong nhật ký →]            │
```

- **Header**: count + total latency, derived from the tree-lite `AiAuditEvent` root row matched by
  `runId`.
- **Body**: child events of that root, label-only (NEVER name the tool — say *"Tra cứu danh sách
  entity"*, not `list_entities`). i18n keys for each `KIND` (CHAT/TOOL/RETRIEVAL/...) live in the
  message bundle so the wording stays consistent.
- **Deep-link**: anchor to the audit list view filtered by `runId`, e.g. navigate to
  `AiAuditEventListView` with `?runId=...` query parameter via Jmix `ViewNavigators`.
- **Default state**: collapsed. Persist expand/collapse state per session only — not per-user
  preference (over-engineering for v1).

### 2. Ephemeral streaming status

While the assistant is producing tokens, surface status separately from the message bubble:

```
┌─────────────────────────────────────┐
│ 🔄 Đang tìm kiếm dữ liệu...        │  ← lives in a sibling slot
└─────────────────────────────────────┘
```

- Status text comes from i18n keys keyed by current tool kind being executed (TOOL/RETRIEVAL/...).
  Wording must NOT name the tool.
- When the assistant message finalizes, the status component clears completely. The final message
  bubble contains only the reply text.
- Implementation options to evaluate during planning:
  - Vaadin `MessageList` secondary slot, if Vaadin 24.8 exposes one for streaming.
  - A sibling `Span` / typing-indicator component above the input bar, bound to a streaming
    `Phase` enum on the chat controller.
- Consider a fallback typing indicator (just dots) for cases where no kind is identifiable yet.

### Data source

Reuse the tree-lite `AiAuditEvent` rows from the in-flight redesign. Each chat turn → one root row
(`PARENT_ID = null`, `KIND = CHAT`) + N children. The "AI did" panel just reads that subtree
scoped to current conversation + current user. No new persistence work.

### Decisions to make during planning

- **Per-turn vs per-conversation drill-down**: secondary panel attached to each message bubble
  vs a single side panel showing the whole conversation's audit tree. Per-turn matches user
  intuition better but costs more layout work.
- **Status wording**: "Đang tìm kiếm dữ liệu...", "Đang tra cứu tài liệu...", "Đang xử lý..."
  — must not name tools. Tied to i18n message bundle. Confirm Vietnamese + English copy.
- **Streaming hookup**: how does the controller know which `KIND` is currently executing? Likely
  via `AuditAdvisor` event publishing or a session-scoped `Phase` holder.
- **Failure mode**: if the audit subtree is empty (e.g. pure-LLM turn with no tool calls), hide
  the "AI did" panel entirely rather than showing "0 bước".

## Relationship to other todos

- **Depends on** `redesign-audit-schema-as-tree-lite-...` (must merge first — this todo reads the
  tree).
- **Depends on** `hide-internal-tool-and-entity-names-from-user-facing-chat.md` (M1) — that closes
  the prompt-side leak. This todo provides the secondary UI surface. Order: prompt-side first,
  UI-side after.
- **Coordinate with** `add-dedicated-chat-speech-and-file-task-input.md` (M2) and
  `add-intent-driven-extraction-to-prefilled-jmix-forms.md` (M2). All three touch
  `ChatView.java` + `ChatPanelFragment.java` + the matching XML descriptors. Plan them as one
  Chat-UI-v2 phase rather than three separate phases that each rebuild the layout.

## Why M2

- Touches `ChatView`, `ChatPanelFragment`, XML descriptors → real layout work.
- Needs UI design call (per-turn vs per-conversation, expand interaction, deep-link nav target).
- Two M2 chat-input todos share the same files — bundling avoids three layout rebuilds.
- Not blocking M1 release: prompt-side fix already removes the user-visible leak; this todo
  upgrades the surface for power users in the next release.
