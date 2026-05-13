---
status: complete
phase: 15-right-sidebar-chat-surface-observability-ux
source: [15-01-SUMMARY.md, 15-02-SUMMARY.md, 15-03-SUMMARY.md, 15-04-SUMMARY.md, 15-05-SUMMARY.md, 15-06-SUMMARY.md]
started: 2026-05-12T02:45:34Z
updated: 2026-05-12T15:25:00Z
---

## Current Test

[testing complete — base 9/10 + 15-06 gap-closure retest: all 3 gaps (test 3, test 7×2) RESOLVED via Playwright UAT 2026-05-12T15:24Z]

## Tests

### 1. Right Sidebar Toggle Appears
expected: Navbar shows a distinct sidebar toggle button (panel icon, aria-label "Open AI chat sidebar"), separate from the existing header chat button.
result: pass

### 2. Open and Close the Sidebar Panel
expected: Clicking the navbar sidebar toggle slides a chat panel in docked to the right edge (~640–760px wide); main page content shifts left to make room (gets pushed, not overlapped); the toggle shows a pressed/active state. The panel has a header row with a close (X) button. Clicking either the close button in the panel header or the navbar toggle again closes the panel and restores the content width.
result: pass

### 3. Chat Works Inside the Sidebar
expected: With the sidebar open, you can type a message and send it; the assistant streams a reply inside the sidebar panel exactly like the full-route / dialog chat. Same conversation/memory.
result: pass
note: Sent "Liệt kê tất cả khách hàng" — assistant streamed a reply with a customer table inside the sidebar. (Separate new-conversation bug tracked in Gaps, test 3.)

### 4. Conversation Continuity Across Surfaces
expected: Start a conversation in one surface (e.g. the sidebar), then open another surface (the header-button dialog or the full chat route) — the same conversation/messages are shown; continuing in either surface keeps one continuous conversation, not two separate threads.
result: pass
note: Conversation "Email khách hàng Acme Corporation" started in the full route, then opened in the sidebar (same messages + title), then in the header-button dialog (same messages + title). Auto-title also applied.

### 5. Admin Can Disable the Sidebar Surface
expected: In the AI UI Settings admin view, "Right sidebar" appears in the enabled-surfaces checkbox group and the default-surface radio group. Unchecking "Right sidebar" and saving makes the navbar sidebar toggle disappear (after reload) for users; re-enabling brings it back.
result: pass
note: "Thanh bên phải" (SIDEBAR) present in both the enabled-surfaces checkbox group and the default-surface radio group. Unchecked + saved → navbar sidebar toggle gone after reload (only "Mở trò chuyện AI" remained). Re-enabled + saved → toggle returned. State restored.

### 6. Ephemeral Streaming Status Line
expected: When you send a message, a small centered status line appears below the message list while the turn is streaming — text like "working", then "thinking" / "searching data" / "retrieving documents" depending on what the agent is doing — with an animated trailing ellipsis. When the reply finishes (or errors, or you press stop), the status line disappears completely. It is never merged into the reply bubble text.
result: pass
note: Caught "đang truy xuất tài liệu.." (RETRIEVAL status) as a separate centered row below the message list while streaming, with progress bar; disappeared once the reply completed. Not merged into the bubble.

### 7. Per-Turn "What the Agent Did" Disclosure
expected: After an assistant turn that used tools or document retrieval, a collapsed disclosure ("what the agent did — N steps · M ms") appears below that turn. Expanding it lists the steps with friendly labels only (e.g. "Searched data", "Retrieved documents", "Generated reply") and a duration per step (or "—" when unknown); a step that errored/rolled back shows an error indicator. No raw tool names, entity names, or arguments are shown.
result: issue
reported: "sao lại hiện ra agent đã làm được gì thô thế" — functionally works (collapsed 'agent đã làm gì — 1 bước · 60 ms'; expands to a label-only row 'Đã tìm dữ liệu  60 ms', no internal names) but the disclosure summary + step rows look unstyled / visually crude.
severity: cosmetic

### 8. Turn Disclosure on Reloaded History
expected: Reload a conversation that has prior assistant turns with tool/retrieval activity. Each such prior turn shows the collapsed disclosure. Expanding one loads its step rows (lazily, once); collapsing and re-expanding does not re-query. Turns with no tool/retrieval activity show no disclosure.
result: pass
note: Re-opened a history conversation in the sidebar — the prior assistant turn showed a collapsed "agent đã làm gì" (summaryPending) disclosure; expanding it lazy-loaded "Đã tìm dữ liệu 124 ms" / "Đã tìm dữ liệu 6295 ms" and the summary updated to "agent đã làm gì — 2 bước · 6,419 ms". (Same visual-crudeness as test 7.)

### 9. Mobile / Narrow Window Behavior
expected: Narrow the browser window below ~768px. With the sidebar open, the panel becomes a full-width overlay over the content (no side-by-side push). Closing still works.
result: pass
note: At 600px viewport width the sidebar panel rendered full-width, overlaying the page content (no side-by-side push).

### 10. No Internal Name Leakage
expected: Across the streaming status line and the turn-detail disclosure, you never see internal `@Tool` method names (e.g. `find_records`) or host-prefixed entity names (e.g. `jmixapp_Customer`) — only friendly localized labels.
result: pass
note: Across all observed turns the status line showed only "đang truy xuất tài liệu" / "đang tìm dữ liệu" and the disclosures only "Đã tìm dữ liệu". No `find_records`, `jmixapp_*`, args JSON, or entity metaclass names anywhere. (Customer/product business data shown in replies is intended.)

## Summary

total: 10
passed: 9
issues: 1
pending: 0
skipped: 0
blocked: 0
notes: 1 cosmetic issue (test 7 — turn-detail disclosure looks unstyled/"thô"); 1 user-reported bug NOT reproduced (new-conversation — needs user re-confirm)

## Gaps

- truth: "Clicking 'new conversation' starts a fresh blank chat (works in sidebar, header-button dialog, and full route) — including after a turn that errored"
  status: failed
  reason: "User reported (vi): 'ấn vào cuộc trò chuyện mới k được ...'. Confirm dialog appears, click Yes → nothing clears. Happens specifically when the preceding turn ERRORED (e.g. the OpenRouter call failed): the streaming pipeline never delivers a StreamingEvent.Final, so ChatPanelFragment.conversationId stays null (it is only set by initializeConversationFromFinalEvent on Final, ChatPanelFragment.java:812-818), even though the user message is still rendered (messageCount > 0). Then startNewChat() → setConversationIdInternal(null) hits the early-return guard `if (Objects.equals(this.conversationId, cid)) return;` (line 594) — null == null → returns without clearing. (Could not repro on the happy path because there a Final sets conversationId; the user's prior session had the failing-DB / OpenRouter errors that left conversationId null.)"
  severity: major
  test: 3
  root_cause: "ChatPanelFragment.setConversationIdInternal(null) early-returns when conversationId is already null, but an errored turn leaves conversationId==null with messages still on screen. Fix: don't short-circuit the reset when cid==null && messageCount>0 (or make startNewChat() force a clear). Also (orthogonal): .doOnError should keep the conversationId in sync if the server already created it."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "setConversationIdInternal early-return guard (line ~594) + .doOnError not setting conversationId (line ~882)"
  missing:
    - "Guard fix in setConversationIdInternal / startNewChat so a reset always clears stranded messages"
  debug_session: ""

- truth: "Turn-detail disclosure AND the phase-14 action-choice (intent) prompt AND NOTICE rows render inline directly below the assistant message they belong to, inside the scrolling message area — not stacked at the bottom of messageListSlot"
  status: failed
  reason: "User design feedback: 'giống các agent khác cái ui ai đã làm gì nó phải dưới cái câu chat trước và giữ lại ở messageList' + 'và intent cũng thế'. Currently appendActionChoiceRow / appendNoticeRow / turnActivityBlock all do messageListSlot.add(...) → they pile up after <vaadin-message-list>, disconnected from their turn (a known shortcut around Vaadin MessageList not allowing arbitrary children between messages). Approved approach: Option A — see 15-option-A-mockup.html. Re-anchor each extra element into the DOM right after its turn's <vaadin-message> (re-anchor after MessageList.setItems and after history-replay), indent to the bubble, and restyle the disclosure (border + tint + ⚙ + caret + per-step icons + right-aligned tabular ms + red 'lỗi — đã hoàn tác' row) and the action-choice block (primary left border + primary-10 tint). Label-only content, lazy-load, transient status line, and 3-surface sharing all unchanged."
  severity: minor
  test: 7
  root_cause: "Design shortcut: non-message UI (turn-detail disclosure, phase-14 action-choice prompt, NOTICE rows) appended as messageListSlot siblings after <vaadin-message-list> instead of anchored per-turn."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "appendActionChoiceRow / appendNoticeRow / turnActivityBlock / appendTurnDetails / appendHistoryTurnDetails / correlateHistoryTurnDetails — all attach to messageListSlot tail, not per-turn"
    - path: "ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css"
      issue: ".ai-agent-turn-activity / .ai-agent-turn-activity__step / .ai-agent-action-choice need real disclosure-block styling (mockup: 15-option-A-mockup.html)"
  missing:
    - "Per-turn DOM anchoring of turn-detail + action-choice + NOTICE; re-anchor after setItems / history replay"
    - "CSS restyle of the disclosure + action-choice per the approved mockup"
  debug_session: ""

- truth: "The per-turn 'what the agent did' disclosure (summary line + expanded step rows) is visually styled, not raw text"
  status: failed
  reason: "User reported (vi): 'sao lại hiện ra agent đã làm được gì thô thế'. Functionally correct (label-only steps, no leak) but the <vaadin-details> summary 'agent đã làm gì — 1 bước · 60 ms' and the step row 'Đã tìm dữ liệu / 60 ms' render as bare/unstyled text — the .ai-agent-turn-activity / .ai-agent-turn-activity__step CSS does not visually distinguish it as a polished disclosure block."
  severity: cosmetic
  test: 7
  root_cause: ""
  artifacts: []
  missing: []
  debug_session: ""

---

## 15-06 Gap-Closure Retest (2026-05-12T15:24Z, Playwright, sidebar + header-button dialog, admin/admin, local profile @ :8088)

All three gaps above are now RESOLVED. Verified against the running app:

### Gap (test 3) — new conversation always clears — RESOLVED
- Sidebar → sent "Liệt kê tất cả khách hàng" → got the customer table + turn-detail disclosure → clicked "Cuộc trò chuyện mới" → confirm "Yes" → message list fully cleared (DOM: `vaadin-message-list` light children = [], `items.length` = 0, 0 `.ai-agent-turn-extra`). Happy-path clear confirmed; the errored-turn-specific path (`setConversationIdInternal(null)` no longer short-circuits when `messageCount > 0`, plus `.doOnError` conversationId sync) is in `2c2f326`/`d165a0a` — the staged hand-revert of it was reconciled away in 15-06. Could not force a server error in this UAT pass; covered by code + `ChatPanelFragmentConversationIdTest`.

### Gap (test 7) — turn-detail / action-choice / NOTICE anchored inline per-turn — RESOLVED (Option A)
- After the assistant reply, `vaadin-message-list` light DOM = `[vaadin-message, vaadin-message, div.ai-agent-turn-extra[data-ai-turn-index=1]]` — the extras wrapper is spliced into the message-list light DOM immediately after the turn's assistant `<vaadin-message>` (server-side ordered children + `data-ai-turn-index`-keyed `executeJs` splice). Survives the conversation-switch re-render. Confirmed in both the SIDEBAR surface and the HEADER_BUTTON dialog surface.
- Cross-surface continuity: started "Có bao nhiêu sản phẩm?" in the sidebar, opened the header-button dialog → same conversation + messages + per-turn disclosure shown (one MessageList, 2 messages, not doubled — WR-03 fix holds).

### Gap (test 7, cosmetic) — disclosure visually styled per mockup — RESOLVED
- Collapsed summary: "▸ agent đã làm gì — 2 bước · 96 ms" with caret + ⚙-style affordance; expanded: per-step rows "🔍 Đã tìm dữ liệu …… 35 ms" / "🔍 Đã tìm dữ liệu …… 61 ms" — per-step icon + label-only text + right-aligned tabular ms, inside a bordered/tinted `<vaadin-details>` block. Matches `15-option-A-mockup.html`. Step rows remain label-only (no tool/entity names) — leak gate intact.

### Other checks
- Ephemeral streaming-status line: showed "đang truy xuất tài liệu" below the message list while streaming, removed on Final (DOM: `.ai-agent-status` absent after completion). Sidebar pushes AppLayout content; main view stays interactive.
- Console: 0 errors across the session.

screenshots: uat-15-06-sidebar-open.png, uat-15-06-streaming-status.png, uat-15-06-turn-detail.png, uat-15-06-turn-detail-expanded.png, uat-15-06-cross-surface.png

still pending (not coverable here): forced-server-error new-conversation path (code + unit-test covered); `:jmix-app:test` against real PostgreSQL (no PG provisioned — deferred-items.md).
