---
phase: 15-right-sidebar-chat-surface-observability-ux
reviewed: 2026-05-11T18:01:15Z
depth: standard
files_reviewed: 34
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiAgentSidebarView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/TurnDetailRenderer.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/ai-agent-sidebar-view.xml
  - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
  - ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/StreamingActivityEventTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactoryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiChatSessionStateTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatPanelFragmentSurfaceSwitchTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentStatusLineTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailHistoryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/TurnDetailRendererTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/NoNewPersistedStateTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityLeakTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityMessagesCompletenessTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java
findings:
  critical: 1
  warning: 6
  info: 4
  total: 11
status: issues_found
---

# Phase 15: Code Review Report

**Reviewed:** 2026-05-11T18:01:15Z
**Depth:** standard
**Files Reviewed:** 34
**Status:** issues_found

## Summary

Phase 15 adds the `SIDEBAR` chat surface (`AiChatSurface.SIDEBAR` + a lean `AiAgentSidebarView` host
view mounted onto the UI element by `ChatSurfaceMounter`), a `StreamingEvent.Activity(ActivityKind)`
event emitted best-effort from the retrieval / tool edge, and an in-fragment observability layer
(ephemeral streaming-status line + collapsed per-turn tool-detail `Details`). The structural no-leak
guarantees (`TurnDetailRenderer` accepts only closed enums / validated audit-kind strings / `msg://`
keys) are sound and well tested; the `StreamingEvent` sealed-interface exhaustive switch in
`StreamEventRenderer` correctly handles the new `Activity` variant; the best-effort emit sites
swallow `RuntimeException` and never break the tool/retrieval path; and the `loadTurnSteps`
unconstrained audit read carries the mandatory `userUsername = :me AND conversation.id = :cid`
clause. No new persisted state / no Liquibase changelog — confirmed.

One **BLOCKER**: the history-correlation roots query in `ChatPanelFragment.correlateHistoryTurnDetails`
selects `e.parent is null` *without* `e.kind = 'CHAT'`, so it also picks up the auto-title-generation
TOOL row (`AiConversationTitleService.audit` writes `writeToolCall(parentId=null, …)`). For any
conversation that has been auto-titled (i.e. essentially every conversation past the first turn) the
root count will exceed the replayed-assistant-turn count, the deliberate "count mismatch ⇒ render
none" branch fires, and the per-turn `Details` history feature renders nothing — the common-case
behavior is wrong, not an edge case.

Plus six WARNINGs (eager host-view creation on every UI init regardless of permission/enablement;
missing `kind` filter on the child reads risks an `IllegalArgumentException`/Details-flash;
double-`MessageList` render when the sidebar/dialog opens onto an existing conversation; a couple of
robustness / consistency nits) and four INFO items.

## Critical Issues

### CR-01: History-correlation roots query matches non-CHAT parent-null rows → per-turn Details history silently never renders

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:1229-1242`
**Issue:** `correlateHistoryTurnDetails` builds `rootRunIds` from

```java
"select e.runId from ai_AiAuditEvent e " +
"where e.userUsername = :me and e.conversation.id = :cid and e.parent is null " +
"order by e.startedAt asc"
```

The intent is "CHAT roots", but the predicate is just `e.parent is null` — there is no `and e.kind = 'CHAT'`.
`AiConversationTitleService.audit(...)` writes the auto-title-generation row via
`auditWriter.writeToolCall(/*parentId*/ null, runId, event.userUsername(), event.conversationId(), …)`,
i.e. a row with `parent IS NULL`, `kind = 'TOOL'`, the same `userUsername`, and the same `conversation.id`.
Auto-titling runs for the conversation after the first user turn, so for any auto-titled conversation
`rootRunIds.size()` is `assistantTurnCount + 1` (or more). The very next check is
`if (rootRunIds.size() != assistantTurnCount) { … return; }` — the "no guess on mismatch" branch — so
**no history `Details` are ever appended for the typical conversation**. The feature looks implemented
and tested (the tests stub the query and never include a title-gen row) but is non-functional in
production for the common case.

Note the child-count query (`c.parent is not null group by c.runId`) and `loadTurnSteps`
(`e.parent is not null and e.runId = :rid`) are *not* polluted by the title-gen row (it has
`parent IS NULL`), so the fix is localized to the roots query.

**Fix:**
```java
for (io.jmix.core.entity.KeyValueEntity row : unconstrainedDataManager.loadValues(
                "select e.runId from ai_AiAuditEvent e " +
                "where e.userUsername = :me and e.conversation.id = :cid " +
                "and e.parent is null and e.kind = :chatKind " +
                "order by e.startedAt asc")
        .store("agentstore")
        .properties("runId")
        .parameter("me", me)
        .parameter("cid", conversationId)
        .parameter("chatKind", com.vn.agent.spi.AuditKind.CHAT)
        .list()) {
    ...
}
```
(And add a regression test that includes a `parent IS NULL` `kind='TOOL'` row in the stubbed result
so this can't silently regress again.)

## Warnings

### WR-01: Sidebar host view (and its `ChatPanelFragment`) is created eagerly on every UI init even when the SIDEBAR surface is disabled or the user lacks the view policy

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java:103-111, 217-271`
**Issue:** `initializeUi` calls `mountSidebar(ui, mountedState)` → `mountSidebarPanel` → `createSidebarPanel`
unconditionally, *before* `refreshMountedSurfaces` evaluates `shouldShowSidebar(...)` /
`isSidebarViewPermitted()`. `createSidebarPanel` does `views.create(AiAgentSidebarView.class)`, which
fully instantiates the view and its embedded `ChatPanelFragment` and fires `BeforeShowEvent` +
`ReadyEvent` — and `ChatPanelFragment.onReady` runs `initAttachmentsAndUpload()` which calls
`Files.createTempDirectory("ai-agent-task-file-upload-")`. So **every browser UI of every user creates
a temp directory and a full chat fragment on page load**, even users who can never see the sidebar
(view policy denied) and even when `AiChatSurface.SIDEBAR` is not in the enabled-surface set. The
HEADER_BUTTON dialog, by contrast, is created lazily on click (`openDialog()`). This is a regression in
resource discipline relative to the dialog path and a cross-user instantiation of a view the user
isn't permitted to see.
**Fix:** Gate `mountSidebarPanel`/`createSidebarPanel` on `shouldShowSidebar(uiSettingsService.loadCurrent(), isSidebarViewPermitted())`
(create lazily on first toggle, mirroring `openDialog()`), or at minimum skip `createSidebarPanel`
when `!isSidebarViewPermitted()`. Also consider creating `uploadTempDir` lazily on first upload rather
than in `onReady`.

### WR-02: Audit child reads do not filter on `kind` — an unrecognized child kind throws `IllegalArgumentException` (swallowed) and makes a history `Details` flash then vanish

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:1180-1206, 1244-1258`
**Issue:** `loadTurnSteps` selects `… and e.parent is not null …` and then calls
`TurnDetailRenderer.stepRow(child.getKind(), …)`, which **throws `IllegalArgumentException` for any
`kind` not in `{CHAT,TOOL,RETRIEVAL}`** (`TurnDetailRenderer.java:135-143`). `AuditKind` is documented
as open-ended ("hosts may introduce additional kinds (e.g. \"GUARDRAIL\")", `AuditKind.java:6`), and a
host could write child rows with such a kind. The `catch (RuntimeException)` in `loadTurnSteps` swallows
it and returns `List.of()` — so a history `Details` whose child-count query said "≥1 child" is then
removed on first expand (`appendHistoryTurnDetails` line 1097-1101). The visible result is a `Details`
disclosure that appears and then disappears the moment the user clicks it. The child-count query
(`correlateHistoryTurnDetails`) likewise counts children of any kind, so the inconsistency is built in.
**Fix:** Add `and e.kind in (:toolKind, :retrievalKind)` to the `loadTurnSteps` query and
`and c.kind in (…)` to the child-count query (using `AuditKind.TOOL` / `AuditKind.RETRIEVAL`), so the
two reads agree and `TurnDetailRenderer.stepRow(String,…)` is never handed an out-of-set kind.

### WR-03: `AiAgentSidebarView`/`ChatDialogView` call `setConversationId` in `onBeforeShow` before the fragment's `onReady` has created `messageList` → two `<vaadin-message-list>` rendered when opening onto an existing conversation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiAgentSidebarView.java:31-43`; `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:280-300, 1608-1634`
**Issue:** When the session already has a current conversation, `AiAgentSidebarView.onBeforeShow` →
`chatPanelFragment.setConversationId(cid)` → `setConversationIdInternal(cid)` → (cid differs from the
fragment's null) → `clearMessageList()`, which creates a fresh `MessageList` and adds it to
`messageListSlot`. The fragment's `onReady` (fired by the subsequent `ReadyEvent`) then *also* does
`messageList = new MessageList(); messageListSlot.add(messageList);` without removing the first one —
so the slot now holds two `<vaadin-message-list>` elements, both with the replayed history items. The
`onAttach` listener-registration re-`clearMessageList`s only if it sees a *non-null* state conversation
id that differs from the current one — but after `onBeforeShow` ran `setConversationId(cid)` the
fragment's `conversationId` already equals `cid`, so `setConversationIdInternal(cid)` early-returns and
the second list is not cleaned up. (`ChatDialogView` shares this pattern; the new `AiAgentSidebarView`
reproduces it.)
**Fix:** Make `ChatPanelFragment.onReady` idempotent w.r.t. `messageList` — e.g. only create/add a
`MessageList` if `messageListSlot` doesn't already contain one (or call `clearMessageList()` at the top
of `onReady`). Alternatively defer the host-view `setConversationId` sync to `onReady` only (drop the
`onBeforeShow` call), since `onReady` already syncs.

### WR-04: `ChatSurfaceMounter.mountSidebarPanel` re-asserts the push class but never removes it; relies on AppLayout being a fresh instance after navigation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java:230-236`
**Issue:** After navigation `mountSidebarPanel` adds `CONTENT_PUSHED_CLASS` to the AppLayout iff
`mountedState.sidebarOpen`, but it does not remove the class in the `!sidebarOpen` branch. This is only
correct because Jmix replaces the AppLayout content slot (and typically the AppLayout) on navigation,
so a stale push class doesn't carry over. If a host shell ever keeps the same AppLayout across
navigations while the sidebar is closed, the main content would stay shifted. Low likelihood, but the
asymmetry is fragile.
**Fix:** In `mountSidebarPanel`, add an explicit `else { appLayout.removeClassName(CONTENT_PUSHED_CLASS); }`
(mirroring `setSidebarOpen`), so the push class always tracks `sidebarOpen` regardless of how the
AppLayout was obtained.

### WR-05: `AuditingDocumentRetriever` delegate-based constructors hard-code `defaultTopK = 5` / `defaultSimilarityThreshold = 0.5` magic numbers

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java:74-80`
**Issue:** The two delegate-based constructors (one of which is the new Plan-15 `StreamingSinkHolder`
overload) set `this.defaultTopK = 5; this.defaultSimilarityThreshold = 0.5;` inline. These literals
duplicate the RAG defaults that `AiAgentRagProperties` owns; if the property defaults change, these
silently drift. (The vector-store constructors correctly take them from `props.resolvedTopK()` /
`props.resolvedSimilarityThreshold()`.)
**Fix:** Extract the fallbacks to named constants shared with `AiAgentRagProperties`, or have the
delegate constructors accept the resolved values too. At minimum, name them
(`DEFAULT_TOP_K`, `DEFAULT_SIMILARITY_THRESHOLD`) with a comment pointing at the property source.

### WR-06: Auto-title-generation `Activity(TOOL)` is emitted with no corresponding audit child, so the live status line flips to "searching data…" for a step that never appears in the per-turn Details

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java:132-133`
**Issue:** `emitToolEvent(... new StreamingEvent.Activity(ActivityKind.TOOL))` and the paired `ToolCall`
fire for *every* wrapped tool, including `prepare_form_draft` (for which `shouldWriteGenericAudit`
returns false). For tools whose audit row is owned elsewhere this is fine, but the broader consequence
is that the ephemeral status line can show "searching data…" / a live `STEP_TOOL` row that the
post-`Final` `loadTurnSteps` read (which only sees persisted children) does not reproduce — when the
turn has at least one *other* audited tool, `loadTurnSteps` returns non-empty so the live fallback is
discarded entirely and the unaudited tool's step silently vanishes from the disclosure. The live vs.
history rendering of the same turn can therefore disagree on step count. This is an inconsistency, not
a leak (no tool name reaches the UI), but it undermines the "what the agent did" summary's accuracy.
**Fix:** Either (a) only emit `Activity(TOOL)`/`ToolCall` when a generic audit row will be written
(`shouldWriteGenericAudit(toolName)`), so live and history stay in sync; or (b) document explicitly
that the per-turn `Details` is the authoritative count and the live status line is best-effort, and
have the fallback path merge (not replace) when `loadTurnSteps` is partial.

## Info

### IN-01: `correlateHistoryTurnDetails` doc-comment claims `loadValues` "does NOT infer the store" but the JPQL queries against `ai_AiAuditEvent` would also benefit from a `kind` index note

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:1208-1272`
**Issue:** The (otherwise excellent) Javadoc explains the two-query design and the `.store("agentstore")`
requirement, but does not mention that both reads scan by `userUsername`/`conversation.id` with no
`runId`/`startedAt` index hint; on a long-lived conversation with many turns this is a full child
scan. Out of v1 perf scope, but worth a one-line note for future readers.
**Fix:** Add a comment noting the read cost grows with conversation length and pointing at the
`AiAuditEvent` indexes.

### IN-02: `messages.properties` base bundle absent — only `_en` / `_vi` exist

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`, `messages_vi.properties`
**Issue:** `CLAUDE.md` says to add new keys to "ALL locale files (`messages.properties`, `messages_*.properties`)";
this module has no `messages.properties` base bundle, so a missing default-locale fallback could surface
as a raw key under an unconfigured locale. Established project pattern (not introduced by this phase),
but flagging for the checklist.
**Fix:** None required for this phase; consider adding a `messages.properties` mirror project-wide.

### IN-03: `AuditingDocumentRetriever.emitRetrievalActivity` swallows the `RuntimeException` into a local named `ignored` but then logs it — slightly misleading name

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/AuditingDocumentRetriever.java:162-173`
**Issue:** `catch (RuntimeException ignored) { … log.debug("…", ignored); }` — the variable is logged,
so `ignored` is a misnomer. Cosmetic.
**Fix:** Rename to `ex` (matches `ToolCallbackAuditDecorator.emitToolEvent`'s `catch (RuntimeException ex)`).

### IN-04: `TurnDetailRenderer.stepLabelKeyFor(null)` returns `STEP_TOOL_KEY` while `stepRow(String,…)` rejects unknown kinds — two different "unknown kind" policies in the same class

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/TurnDetailRenderer.java:123-145, 155-164`
**Issue:** `stepRow(ActivityKind, …)` maps a `null` kind to `STEP_TOOL_KEY` (silent default) but
`stepRow(String auditKind, …)` throws `IllegalArgumentException` on an unrecognized/`null` string. The
asymmetry is defensible (the enum overload can't be handed a value outside the closed set except
`null`) but is a small surprise for callers.
**Fix:** Decide on one policy — either both default to `STEP_TOOL_KEY`, or both throw — and document it.

---

_Reviewed: 2026-05-11T18:01:15Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
