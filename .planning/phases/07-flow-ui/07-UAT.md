---
status: complete
phase: 07-flow-ui
source:
  - 07-01-SUMMARY.md (foundation: @Push, MarkdownRenderer, role, menu, i18n)
  - 07-02-SUMMARY.md (streaming backend: ChatService.stream, StreamingSinkHolder)
  - 07-03-SUMMARY.md (ChatView + fragments)
  - 07-04-SUMMARY.md (ConversationList + Detail)
  - 07-05-SUMMARY.md (Parameters list + detail)
  - 07-06-SUMMARY.md (KnowledgeBase + ToolCallAudit)
  - 07-07a/b-SUMMARY.md (test suite)
started: 2026-04-21T00:00:00Z
updated: 2026-04-26T00:00:00+07:00
closure_reason: "superseded_by_phase_7_1_and_later_refactors"
---

## Current Test

[testing complete]

Closure note: closed as obsolete on 2026-04-26. Phase 7.1 superseded the chat-specific
checks and later refactors made this Phase 7 UAT checklist unreliable as a source of Phase 8
scope. Keep this file as historical context only.

## Tests

- [x] 1. Cold Start Smoke Test — closed obsolete/superseded
- [x] 2. ~~Chat view loads at /ai-agent/chat~~ ~~(superseded by UAT-7.1-CHAT-01..07 — Phase 7.1 MessageList adoption)~~
- [x] 3. Conversations list view loads — closed obsolete/superseded
- [x] 4. Parameters list view loads — closed obsolete/superseded
- [x] 5. Knowledge base view loads — closed obsolete/superseded
- [x] 6. Tool-call audit view loads — closed obsolete/superseded by Phase 7.2 audit rewrite
- [x] 7. ~~i18n: switch locale to VI renders Vietnamese labels~~ ~~(superseded by UAT-7.1-CHAT-07 for chat scope; other views unchanged)~~
- [x] 8. Admin-only views gated for non-admin — closed obsolete/superseded

### Amendment (Phase 7.1 — MessageList/MessageInput adoption)

Steps below supersede the original ChatView UAT items referring to bubble/tool-card/citation-dialog components. Added 2026-04-21 by Plan 07.1-07.

- [x] UAT-7.1-CHAT-01 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-02 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-03 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-04 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-05 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-06 — closed obsolete in `07.1-UAT.md`
- [x] UAT-7.1-CHAT-07 — closed obsolete in `07.1-UAT.md`

#### UAT-7.1-CHAT-01 — Streaming renders into MessageList

- Run `./gradlew :jmix-app:bootRun`; log in as `admin`/`admin`.
- Navigate to `/ai-agent/chat`.
- Type "list my customers" into the MessageInput at the bottom; press Enter.
- **Expect:** a user message appears (color index 0, name "You" / "Bạn" depending on locale); a blank bot message appears (color index 2, "AI Assistant" / "Trợ lý AI"); bot message accumulates markdown text as tokens stream in.

#### UAT-7.1-CHAT-02 — Tool calls render as inline markdown

- Within the bot message body during / after a tool-invoking turn, a bold tool-name header (e.g. `**find_records**`) appears, followed by an italicized `_done — <summary>_` block and a `---` separator.
- **Expect:** No expand/collapse widget is present (by design — D-02). Full tool args/results remain visible in `AiToolCallAudit`.

#### UAT-7.1-CHAT-03 — Citations render as clickable Sources list with deep-link

- If the response included RAG citations: expect a trailing `Sources` (or `Nguồn tham khảo` in VI) markdown list; each bullet is a link.
- Click one. Browser navigates to `/ai-agent/knowledge?documentId=<uuid>` and the KB view pre-selects that document. (A-03: parameter name is `documentId`, not `docId`.)

#### UAT-7.1-CHAT-04 — Stop cancels stream and writes CANCELLED audit

- Start a streaming answer. While tokens are arriving, click Stop (button top-left of panel).
- **Expect:** MessageInput re-enables; Stop button hides; streaming stops mid-sentence.
- Admin-navigate to `/ai-agent/audit`. Expect a row with `outcome = CANCELLED` for the just-cancelled run (D-04 contract).

#### UAT-7.1-CHAT-05 — New chat with unsaved-confirm dialog

- After sending at least one message, click `New chat`. Expect a confirm dialog.
- Click Yes. Message list clears; URL strips `conversationId` query param.
- Repeat on a fresh page with no messages: `New chat` should NOT prompt (D-06 only gates when `hasMessages() || isStreaming()`).

#### UAT-7.1-CHAT-06 — conversationId query param replays transcript

- After sending messages, copy the URL including `?conversationId=<uuid>`. Open in a new tab.
- **Expect:** MessageList populated via `setConversationId → setItems` with user + assistant turns in chronological order (D-07).

#### UAT-7.1-CHAT-07 — Bilingual locale smoke

- Switch locale to VI via Vaadin DevTools / user profile.
- Reload chat.
- **Expect:** user-name column reads `Bạn`; assistant-name reads `Trợ lý AI`; Stop button text is localized; citation Sources header is `Nguồn tham khảo`.
- No strings on the page that are hardcoded English.

## Test Log

2026-04-26: Closed historical Phase 7 UAT checklist as obsolete/superseded by Phase 7.1,
Phase 7.2, and later refactors. Phase 8 should use a new UAT plan derived from the current
codebase.
