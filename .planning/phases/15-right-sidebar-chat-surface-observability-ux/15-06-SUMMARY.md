---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 06
subsystem: ai-agent-chat-ui
tags: [observability, chat-ui, css, ux-fix, gap-closure]
requires:
  - ChatPanelFragment (Phase 13.1/14/15-04)
  - AiAuditEvent CHAT/TOOL/RETRIEVAL tree (agentstore)
  - StreamingEvent.Final / .Activity / .ToolCall / .ToolResult
provides:
  - startNewChat force-clear after an errored turn (conversationId==null && messageCount>0)
  - .doOnError best-effort conversationId sync from the run's AiAuditEvent CHAT root
  - Option-A inline per-turn DOM anchoring of turn-detail / action-choice / intent-confirm / NOTICE
  - restyled disclosure (.ai-agent-turn-activity) + action-choice (.ai-agent-action-choice) per the approved mockup
affects:
  - all 3 chat surfaces (FULL_ROUTE / HEADER_BUTTON / SIDEBAR — single shared ChatPanelFragment)
tech-stack:
  added: []
  patterns:
    - "TurnExtra registry keyed by transcript index; server-side ordering in messageListSlot + executeJs splice into the <vaadin-message-list> light DOM keyed off data-ai-turn-index"
    - "Reused the loadTurnSteps UnconstrainedDataManager + mandatory userUsername/runId/parent filter for the errored-run conversation-id read"
key-files:
  created: []
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentConversationIdTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailHistoryTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityLeakTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/NoticeRenderTest.java
decisions:
  - "Server-side the inline extras stay as messageListSlot children right after <vaadin-message-list> (ordered by turnIndex) and a data-ai-turn-index-keyed executeJs pass splices them into the <vaadin-message-list> light DOM after the right <vaadin-message>. Rationale: Vaadin Flow's MessageList exposes no server-side child Elements (items is a JSON property), so an Element.insertChild between messages is not possible server-side; the JS pass achieves the user-visible inline position while keeping server state deterministic + unit-testable."
  - "Reconciled the working-tree staged revert of commit 2c2f326 in favour of the must-haves: Gap 1 (force-clear + .doOnError conversationId sync) is required by the plan's truths, so the revert was dropped and 2c2f326 kept."
metrics:
  duration: ~3h
  completed: 2026-05-12
---

# Phase 15 Plan 06: Right-Sidebar Chat Surface & Observability UX — Gap Closure Summary

Closed both open Phase-15 UAT gaps in `ChatPanelFragment` (+ its CSS): "new conversation" now clears stranded messages after an errored turn, and the per-turn "what the agent did" disclosure / phase-14 action-choice / NOTICE rows render inline directly under their turn's `<vaadin-message>` with the user-approved Option-A styling.

## What shipped

### Task 1 — new-conversation-after-error fix (Gap 1, major) — commit `2c2f326`
- `setConversationIdInternal(UUID)`: the early-return guard no longer short-circuits the reset when `cid == null && messageCount > 0` (an errored turn leaves `conversationId == null` with the user message still rendered), so `newChatButton → confirm Yes → startNewChat()` clears the screen.
- `submitChatTurn(...)`'s `.doOnError`: best-effort syncs `conversationId` from the run's `AiAuditEvent` CHAT root when `activeRunId != null && conversationId == null`, via the new `resolveConversationIdForRun(UUID)` helper that reuses the `loadTurnSteps` security pattern exactly (`UnconstrainedDataManager` + mandatory `userUsername`/`runId`/`parent is null` filter, narrow `conversation.id` projection, `catch (RuntimeException)` → `log.debug` + skip). Never throws on the error path.
- `ChatPanelFragmentConversationIdTest`: +3 cases (force-clear-after-errored-turn, happy-path no-op preserved, `.doOnError` conversationId-sync source scan).

### Tasks 2 & 3 — Option-A inline anchoring + disclosure/action-choice restyle (Gap 2, minor/user-prioritized) — commit `d165a0a`
- New `TurnExtra(int turnIndex, Element)` registry (`turnExtras`, kept sorted by `turnIndex`) + `turnDetailWrapperByRunId: Map<UUID, Div>` replacing the dead `turnActivityBlock` field.
- `anchorExtra(turnIndex, element)` / `reanchorAllExtras()` — server-side the extras stay as children of `messageListSlot` immediately after the `<vaadin-message-list>` ordered by `turnIndex`; a `data-ai-turn-index`-keyed `executeJs` pass then splices each into the `<vaadin-message-list>` light DOM right after the matching `<vaadin-message>` (Option A). Re-anchor runs after every `messageList.setItems(...)` (in `submitChatTurn`) and at the end of history replay; `clearMessageList` clears `turnExtras` + `turnDetailWrapperByRunId`.
- `appendTurnDetails` wraps the `Details` (now classed `.ai-agent-turn-activity`) in a `Div.ai-agent-turn-extra` and anchors it under the just-completed assistant turn; `appendHistoryTurnDetails(runId, cid, turnIndex)` anchors history disclosures under their ASSISTANT turn; `correlateHistoryTurnDetails(UUID, List<Integer>)` now zips the audit roots against per-turn assistant indices. `appendNoticeRow` / `appendActionChoiceRow` / `appendIntentConfirmRow` route through `anchorExtra(items.size()-1, ...)`; `removeActionChoiceRow` drops the matching `TurnExtra`. Lazy-load + memoization, no-guess correlation, `messageCount` bookkeeping, status-line behaviour and `onDetach`/`clearMessageList` teardown unchanged.
- `buildStepRow` adds KIND-keyed modifier classes (`--tool` / `--retrieval` / `--chat`, plus `--errored`) for the CSS icon chip and orders children label / [error] / duration.
- `ai-agent-chat.css`: `.ai-agent-turn-extra` indent wrapper (`calc(var(--lumo-size-m) + var(--lumo-space-s))`); `.ai-agent-turn-activity` border + `--lumo-contrast-5pct` tint + `::part(summary)` (cursor/font/colour + `::before` ⚙) + `::part(content)`; `.ai-agent-turn-activity__steps` left-indented; `.ai-agent-turn-activity__step` flex + per-step `::before` chip (🔍 / 📄 / 💬, ✕ red for `--errored`) + right-aligned `font-variant-numeric: tabular-nums` ms; `.ai-agent-action-choice` primary left border + `--lumo-primary-color-10pct` tint + bubble indent; `.ai-agent-intent-confirm` same indent. `--lumo-*` tokens only; no `theme.json` / `frontend/themes/` / `frontend/generated/` edits; no new `msg://` key.
- Tests: `ChatPanelFragmentTurnDetailTest` / `...TurnDetailHistoryTest` rewritten for the new anchoring contract (wrapper class, `data-ai-turn-index`, server-side ordering after the message list, modifier classes, source scan for `reanchorAllExtras` / `correlateHistoryTurnDetails(cid, assistantTurnIndices)` / pure-DOM splice). `ObservabilityLeakTest` + `NoticeRenderTest` updated for the removed `turnActivityBlock` / the `anchorExtra` NOTICE path.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Pre-existing failing fragment tests fixed**
- **Found during:** Task 2 (running the targeted suites)
- **Issue:** `ChatPanelFragmentTurnDetailTest.loadTurnSteps_*` and the whole `ChatPanelFragmentTurnDetailHistoryTest` were already failing at HEAD (2c2f326) — the Mockito deep-stub chains for the `loadTurnSteps` / `correlateHistoryTurnDetails` reads stopped at `.parameter("cid", ...)` and so didn't match production's trailing `.parameter("toolKind"/"retrievalKind"/"chatKind", ...)` calls, returning empty lists.
- **Fix:** Extended the stub chains with the trailing `.parameter(eq("toolKind"|"retrievalKind"|"chatKind"), any())` before `.list()`. (These two test files were already in this plan's `files_modified`.)
- **Files modified:** `ChatPanelFragmentTurnDetailTest.java`, `ChatPanelFragmentTurnDetailHistoryTest.java`
- **Commit:** `d165a0a`

**2. [Rule 3 — Blocking] ObservabilityLeakTest / NoticeRenderTest referenced removed internals**
- **Found during:** Task 2
- **Issue:** `ObservabilityLeakTest` read the deleted `turnActivityBlock` field; `NoticeRenderTest` asserted `appendNoticeRow` does `messageListSlot.getElement().appendChild(notice)` (changed to `anchorExtra(...)`).
- **Fix:** Updated both source/DOM assertions to the new anchoring path; `ObservabilityLeakTest`'s harness now seeds 2 transcript items so `appendTurnDetails` has an anchor.
- **Files modified:** `ObservabilityLeakTest.java`, `NoticeRenderTest.java`
- **Commit:** `d165a0a`

**3. Reconciled the working-tree staged revert** — see `decisions` above: the staged revert of 2c2f326 was dropped because the plan's must-have truths require the Gap-1 behaviour it implemented.

### Notes
- The unrelated working-tree changes (`docker-compose.yml`, `jmix-app/.../application-local.properties`) and the UAT artifacts (`15-UAT.md`, `15-option-A-mockup.html`, `uat-*.png`) were left untouched / uncommitted as instructed.

## Threat surface
No new network endpoints, auth paths, or schema. T-15-06-01 mitigated by reusing the `loadTurnSteps` unconstrained+mandatory-filter pattern with a narrow `conversation.id` projection. T-15-06-02/03 unchanged — anchoring only moves existing escaped elements (`Element.setText` / `Span(messages.getMessage(...))`); the `executeJs` splice is `insertBefore` on existing nodes, no `innerHTML`, no markdown on these elements. `ObservabilityLeakTest` green.

## Verification
- `./gradlew :ai-agent:ai-agent:test` — BUILD SUCCESSFUL (full add-on module, HSQLDB).
- Targeted: `ChatPanelFragmentTurnDetailTest`, `ChatPanelFragmentTurnDetailHistoryTest`, `ChatPanelFragmentConversationIdTest`, `ChatPanelFragmentStatusLineTest`, `ObservabilityLeakTest`, `NoticeRenderTest`, `ActionChoiceRowTest` — all green.
- `git diff --stat`: only `ChatPanelFragment.java`, `ai-agent-chat.css`, and the 5 test files changed (across 2c2f326 + d165a0a); no changes to `chat-panel-fragment.xml`, `AiAuditEvent.java`, `AiMessage.java`, `AiChatSessionState.java`, `TurnDetailRenderer.java`; no new `**/liquibase/**`, `theme.json`, `frontend/themes/`, `frontend/generated/`, or `msg://` key; no file deletions.
- `:jmix-app:test` (live PostgreSQL `agentstore`) deliberately not run — out of scope per `deferred-items.md`.
- Manual UI verification (app on :8088) deferred to `/gsd-verify-work`.

## Self-Check: PASSED
- ChatPanelFragment.java — FOUND
- ai-agent-chat.css — FOUND
- 15-06-SUMMARY.md — FOUND
- commit 2c2f326 — FOUND
- commit d165a0a — FOUND
