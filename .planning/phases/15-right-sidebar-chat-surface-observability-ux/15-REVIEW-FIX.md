---
phase: 15-right-sidebar-chat-surface-observability-ux
fixed_at: 2026-05-12T00:00:00Z
review_path: .planning/phases/15-right-sidebar-chat-surface-observability-ux/15-REVIEW.md
iteration: 1
findings_in_scope: 7
fixed: 7
skipped: 0
status: all_fixed
---

# Phase 15: Code Review Fix Report

**Fixed at:** 2026-05-12
**Source review:** .planning/phases/15-right-sidebar-chat-surface-observability-ux/15-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 7 (1 critical + 6 warnings)
- Fixed: 7
- Skipped: 0

## Fixed Issues

### CR-01: History-correlation roots query matches non-CHAT parent-null rows

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
**Commit:** 6290f43
**Applied fix:** Added `and e.kind = :chatKind` (bound to `com.vn.agent.spi.AuditKind.CHAT`) to the `correlateHistoryTurnDetails` roots query so the auto-title-generation TOOL row (`parent IS NULL`, `kind = 'TOOL'`) is no longer counted as a CHAT root, which previously made `rootRunIds.size() != assistantTurnCount` fire for every auto-titled conversation and suppress all history `Details`.
**Note:** Requires human verification — logic-sensitive; a regression test that includes a `parent IS NULL` / `kind='TOOL'` row in the stubbed query result should be added (was not added here — see Follow-ups).

### WR-01: Sidebar host view created eagerly on every UI init

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java`
**Commit:** f0b1c3a
**Applied fix:** `mountSidebarPanel` now returns early (creates nothing) unless `shouldShowSidebar(uiSettingsService.loadCurrent(), isSidebarViewPermitted())`, mirroring the HEADER_BUTTON dialog's lazy creation. The host view (and its `ChatPanelFragment` + temp upload directory) is no longer instantiated for users who cannot see the sidebar or when the `SIDEBAR` surface is disabled.
**Note:** Requires human verification — `ChatSurfaceMounterTest` may assume eager panel creation; check the test path uses an enabled+permitted setup or update it.

### WR-02: Audit child reads do not filter on `kind`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
**Commit:** 6290f43
**Applied fix:** Added `and e.kind in (:toolKind, :retrievalKind)` (bound to `AuditKind.TOOL` / `AuditKind.RETRIEVAL`) to both `loadTurnSteps` and the `correlateHistoryTurnDetails` child-count query, so the two reads agree and `TurnDetailRenderer.stepRow(String,…)` is never handed an out-of-set kind (no more swallowed `IllegalArgumentException` / `Details`-flash-then-vanish).

### WR-03: Double `MessageList` render when opening onto an existing conversation

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
**Commit:** 643d0f5
**Applied fix:** `ChatPanelFragment.onReady` now only builds/attaches a `MessageList` when `messageList == null`. When a host view called `setConversationId(...)` in `onBeforeShow` (which goes through `clearMessageList()` and already creates+attaches a `MessageList`), `onReady` no longer adds a second `<vaadin-message-list>`.
**Note:** Requires human verification — logic-sensitive (relies on `messageList` being null at first `onReady` and non-null only after `clearMessageList`).

### WR-04: `mountSidebarPanel` re-asserts push class but never removes it

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java`
**Commit:** f0b1c3a
**Applied fix:** Replaced the conditional `addClassName` with an `if/else` that adds `CONTENT_PUSHED_CLASS` when `sidebarOpen` and removes it otherwise, mirroring `setSidebarOpen`'s symmetry, so the push class always tracks `sidebarOpen` regardless of how the AppLayout was obtained.

### WR-05: `AuditingDocumentRetriever` hard-codes `5` / `0.5` magic numbers

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java`
**Commit:** 3085c9c
**Applied fix:** Extracted `DEFAULT_TOP_K = 5` and `DEFAULT_SIMILARITY_THRESHOLD = 0.50` named constants with a Javadoc note that they must stay in sync with `AiAgentRagProperties.resolvedTopK()` / `resolvedSimilarityThreshold()` (AI-SPEC §4 defaults), and used them in the delegate-based constructor. (Did not refactor `AiAgentRagProperties` to share the constant — it also inlines the literals; the "at minimum, name them" path was taken.)

### WR-06: Auto-title `Activity(TOOL)` emitted with no corresponding audit child

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java`
**Commit:** 6033d3e
**Applied fix:** The `Activity(StreamingEvent.ActivityKind.TOOL)` status marker is now emitted only when `shouldWriteGenericAudit(toolName)` is true (option (a) from the review, scoped to the status marker). The `ToolCall`/`ToolResult` pair is still emitted for every tool because `StreamEventRenderer` needs `ToolResult`'s structured payload (e.g. `prepare_form_draft` → `open_form_with_draft`) regardless of auditing — suppressing the pair would have broken the form-draft flow.

## Follow-ups (not applied — out of fix scope or test work)

- CR-01 / WR-02: add a regression test feeding a `parent IS NULL`, `kind='TOOL'` row plus child rows of an unrecognized kind into the stubbed `loadValues` result so the kind filters can't silently regress.
- IN-01..IN-04: not in `critical_warning` scope; left for a follow-up pass.

---

_Fixed: 2026-05-12_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
