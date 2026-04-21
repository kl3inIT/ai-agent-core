---
phase: 07-flow-ui
reviewed: 2026-04-21T00:00:00Z
depth: deep
files_reviewed: 27
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditDetailDialog.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/MessageBubbleComponent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ToolCallCardComponent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/tool-call-audit-list-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-detail-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-list-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-list-view.xml
findings:
  critical: 2
  warning: 13
  info: 9
  total: 24
status: issues_found
---

# Phase 7: Code Review Report

**Reviewed:** 2026-04-21
**Depth:** deep
**Files Reviewed:** 27 (15 Java + 10 XML + 2 properties)
**Status:** issues_found

## Summary

Phase 7 delivers the full Jmix Vaadin Flow UI — chat (live streaming + citations + tool cards), conversations (list + read-only replay), parameters (list + detail with YAML preview), knowledge base (upload + push-driven status), tool-call audit (filters + export + detail dialog), admin role, menu, i18n, and the `@Push` AppShell. The implementation is substantially correct and generally follows Jmix conventions: XML view descriptors, Jmix containers/loaders, `Dialogs`/`Notifications`/`ViewNavigators` APIs, `MarkdownRenderer` with OWASP sanitization, and per-UI `UI.access()` in streaming/push callbacks.

Key concerns:

1. **Critical:** Two SQL injection-shaped patterns in XSS/user-input surfaces — `ConversationListView.rebuildQuery` concatenates a user-controlled filter into JPQL directly (still bound via `:param`, but the `where` fragment itself is built unconditionally from authenticated state, so the risk is low-confidence). More serious: `ToolCallAuditListView.rebuildQuery` and `ConversationListView.rebuildQuery` use the Jmix `CollectionLoader.setQuery(String)` pattern with stale parameters from previous invocations — `removeParameter` is not called on the audit view's filter branches, so leftover `:user`/`:tool`/etc. parameters will raise `IllegalStateException: Parameter 'user' is not used` when the user clears a filter field. Treated as a bug, not a vuln.
2. **Critical:** Citation dialog's "Open in KB" navigates to `KnowledgeBaseView` but the view's `beforeEnter` overrides the loader query with `where e.id = :docId` — any subsequent reload (push refresh, action) continues to show only that one row forever, because the query is never reset. Single-row lock-in across the admin session.
3. **Warning (Jmix-first):** Significant programmatic Vaadin layout is used instead of XML descriptors — `ToolCallAuditDetailDialog`, `CitationDialog`, `ToolCallCardComponent`, `MessageBubbleComponent` are fully built in Java. Per CLAUDE.md "Jmix-first UI over raw Vaadin" these should be XML fragments/dialog views unless there is a compelling dynamic reason. The chat bubble and tool card have a genuine streaming/dynamic justification; the two dialogs do not.
4. **Warning (event wiring):** All interactive buttons use raw `addClickListener(...)` / `addValueChangeListener(...)` inside `onInit`. Jmix idiom is `@Subscribe("buttonId")` for a `ClickEvent<Button>` or XML `<action>` + `@Subscribe("componentId.actionId")`. This is a systemic pattern across every view.
5. **Warning (admin role coverage):** `AiAgentAdminRole.adminViews()` is missing `AiAgent_Chat` and `AiAgent_Conversation.*` view IDs. Admins inherit from `AiAgentUserRole` only if explicitly added via `@ResourceRole.grants` — the current code does not, so admins without the user role cannot open chat/conversations.
6. **Warning (XSS surface):** `MarkdownRenderer` composes `Sanitizers.FORMATTING.and(BLOCKS).and(LINKS).and(TABLES)` — the `LINKS` policy allows `href` on anchors but does not reject `data:` or `vbscript:` URIs by itself. OWASP's bundled sanitizer does block `javascript:` via its URL policy, but the class-level Javadoc claims `data:` URIs are stripped — verify with a test fixture. Also `Autolink` extension converts raw URLs — combined with the allowlist this is OK, but flagged for a regression test.
7. **Info:** Numerous i18n keys referenced in Java are not present in either properties file (`chatView.toolCard.collapsed` placeholder use is fine; `knowledgeBase.upload.rejected` used as a generic error catch-all).

## Critical Issues

### CR-01: `ToolCallAuditListView.rebuildQuery` leaves stale loader parameters

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java:141-188`
**Issue:** When a filter field is cleared after a previous query used it, the new JPQL no longer references `:user` / `:tool` / `:outcome` / `:fromDate` / `:toDate`, but `auditsDl` still carries the parameter from the prior `setParameter` call. Jmix `CollectionLoader.load()` raises `IllegalStateException` for parameters set but not referenced. `ConversationListView.rebuildQuery` avoids this by calling `removeParameter` on the negative branch (see `ConversationListView:143,148`), but the audit view never does. Clearing any filter after populating it will break the grid.
**Fix:**
```java
// in each conditional, add the else branch:
if (user != null && !user.isBlank()) {
    auditsDl.setParameter("user", "%" + user.toLowerCase(Locale.ROOT) + "%");
} else {
    auditsDl.removeParameter("user");
}
// repeat for tool, outcome, fromDate, toDate
```

### CR-02: `KnowledgeBaseView.beforeEnter` permanently scopes the loader to one document

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:177-191`
**Issue:** When the admin arrives from the citation dialog with `?documentId=…`, the loader query is mutated to `where e.id = :docId` and the `docId` parameter is set — but the view never restores the default query. Subsequent push refreshes (`onDocumentStatusChanged` falls through to `documentsDl.load()`), `reingest` / `delete` reloads, and admin navigation all stay pinned to the single-row scope for the life of the view. The user gets a one-row grid with no way back to full list other than a browser reload.
**Fix:** Either (a) don't mutate the loader at all — instead use the incoming `documentId` to pre-select / scroll to the row inside the full list once the load completes; or (b) capture the filter state, and reset it on a "Clear filter" button / after any mutation. Option (a) is idiomatic:
```java
@Override
public void beforeEnter(BeforeEnterEvent event) {
    QueryParameters qp = event.getLocation().getQueryParameters();
    List<String> docIds = qp.getParameters().getOrDefault("documentId", List.of());
    if (docIds.isEmpty()) return;
    try {
        UUID docId = UUID.fromString(docIds.get(0));
        // Defer highlight until after list loads.
        documentsDl.addPostLoadListener(e -> documentsDc.getItems().stream()
                .filter(d -> docId.equals(d.getId()))
                .findFirst()
                .ifPresent(documentsDataGrid::select));
    } catch (IllegalArgumentException ignored) { }
}
```

## Warnings

### WR-01: Admin role missing Chat + Conversation view policies

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java:36-39`
**Issue:** `@ViewPolicy(viewIds = {...})` lists only Parameters, Knowledge, and Audit. `AiAgent_Chat`, `AiAgent_Conversation.list`, and `AiAgent_Conversation.detail` are granted via `AiAgentUserRole`, but `AiAgentAdminRole` is not declared with `@ResourceRole(..., scope = ..., ...)` grants that inherit the user role. An admin without the user role assigned will be locked out of chat/conversations even though `AiAgent_Chat` appears in `menu.xml`.
**Fix:** Either add those view IDs to the admin role:
```java
@MenuPolicy(menuIds = {"aiAgent.chat", "aiAgent.conversations", ...})
@ViewPolicy(viewIds = {"AiAgent_Chat", "AiAgent_Conversation.list", "AiAgent_Conversation.detail", ...})
```
or document that admins must be granted both roles and add a deploy-time test that asserts the role composition covers every menu entry.

### WR-02: Raw `addClickListener` / `addValueChangeListener` instead of `@Subscribe`

**Files:** Pervasive — `ChatView.java:61`, `ChatPanelFragment.java:106-107`, `ConversationDetailView.java:69`, `ConversationListView.java:87-98`, `KnowledgeBaseView.java:116-129`, `ParametersDetailView.java:127-137`, `ParametersListView.java:73-76`, `ToolCallAuditListView.java:98-114`.
**Issue:** Jmix Flow UI convention is `@Subscribe("buttonId") public void onSend(ClickEvent<Button> e)` (or XML `<action>` + `@Subscribe("grid.action")`) instead of programmatic listener registration. Current code compiles and runs, but loses declarative wiring, visibility in view descriptors, and interferes with future action-based hardening (disabling, permissions, titles).
**Fix:** Prefer:
```java
@Subscribe("sendButton")
public void onSendClick(ClickEvent<Button> event) { ... }

@Subscribe("userFilter")
public void onUserFilterChange(ComponentValueChangeEvent<TextField, String> event) { ... }
```
Retain raw listeners only where the wiring is genuinely dynamic (e.g., grid selection → button enabled state; per-row renderer callbacks).

### WR-03: Programmatic dialogs should be XML fragment / dialog views

**Files:** `ToolCallAuditDetailDialog.java` (entire class), `CitationDialog.java` (entire class).
**Issue:** Per CLAUDE.md and MEMORY `feedback_jmix_first_ui`, dialogs should be Jmix view descriptors opened via `DialogWindows.detail(...)` / `DialogWindows.view(...)`. Both of these dialogs are fully built in Java with raw Vaadin `FormLayout`/`VerticalLayout`/`Pre`/`Button`/hardcoded inline styles. There is no streaming or dynamic composition that would justify opting out of XML.
**Fix:** Convert each to an XML `<view>` opened via `DialogWindows`, or use `Dialogs.createOptionDialog()` where structure permits. At minimum, hoist the styling (`setMaxHeight`, `set("white-space","pre-wrap")`, etc.) into Lumo utility classes / a CSS file so the Java controller is layout-free.

### WR-04: `MessageBubbleComponent` / `ToolCallCardComponent` fully programmatic

**Files:** `MessageBubbleComponent.java`, `ToolCallCardComponent.java`.
**Issue:** These have a reasonable dynamic justification (streaming content accumulation / per-tool-call instantiation), but the hard-coded Lumo CSS strings (`root.getStyle().setPadding("var(--lumo-space-m)")`, etc.) and per-role styling branches would be better expressed as theme classes with a single `getElement().getThemeList().add(role.name().toLowerCase())`.
**Fix:** Move style to CSS file; keep Java-side layer only for buffer management and `htmlContent.setHtmlContent(...)`.

### WR-05: `XML action="auditsDataGrid.excelExport"` duplicated with sibling XML `<action>` — verify

**File:** `tool-call-audit-list-view.xml:37-54`
**Issue:** `<button action="auditsDataGrid.excelExport"/>` binds to a grid action with `id="excelExport" type="grdexp_excelExport"`. Verify that gridexport 2.8 requires the `type` token `grdexp_excelExport` (vs. `excelExport` in older docs). If Jmix 2.8 renamed the action type, the XML fails at view load. Use Context7 for `jmix-framework/jmix-context7` to confirm the exact type name before shipping.
**Fix:** Verify via Jmix docs; if the type is wrong, the button click will produce a runtime "action not found" warning.

### WR-06: Business logic ("admin probe") in `ConversationListView.currentUserIsAdmin`

**File:** `ConversationListView.java:107-116`
**Issue:** Role checking by string-matching `GrantedAuthority.getAuthority()` against `AiAgentAdminRole.CODE` is brittle (Jmix prefixes authorities; the raw role code rarely equals `authority.getAuthority()`). Per CLAUDE.md "AI is just another Jmix client" this check should go through Jmix `AccessManager` / `SecureOperations` APIs, not ad-hoc authority inspection.
**Fix:** Inject `SecureOperations` or `AccessManager` and check a view policy, or inject a small service that wraps the check. Also: the fallback `catch(Exception ex) { return false; }` silently downgrades admins to non-admins on any transient issue — log at WARN, not DEBUG.

### WR-07: `ParametersDetailView.scanApplicationContextForToolMethods` is over-engineered

**File:** `ParametersDetailView.java:248-273`
**Issue:** A view controller should not do reflective `ApplicationContext` scans to discover tool beans. The primary path (`AgentToolCallbacks.forCurrentUser()`) is already correct; the reflective fallback introduces a second, divergent definition of "what is a tool" that can drift. Also note the bean-name loop instantiates every bean in the context (`applicationContext.getBean(beanName)`) which can be very expensive and has side effects on lazy beans.
**Fix:** Drop the reflective fallback. If `forCurrentUser()` throws, surface the exception; don't paper it over.

### WR-08: Per-row `DataManager.loadValue` count in `ConversationListView` (N+1)

**File:** `ConversationListView.java:82, 154-169`
**Issue:** The `messageCount` column renderer issues one count query per rendered row. The class-level Javadoc acknowledges this as a "path (b) fallback" — acceptable for v1 for non-admins (small result sets) but admins can easily surface hundreds of rows. Document thresholds or guard with a row cap, else it will become a UX complaint in production.
**Fix:** Use a `KeyValueCollectionLoader` with `select c, count(m) from ai_AiConversation c left join ai_AiMessage m on m.conversation = c group by c` or add a computed `@JmixProperty` on `AiConversation` with `@DependsOnProperties("messages")`.

### WR-09: `ConversationDetailView.onReady` double-loads messages

**File:** `ConversationDetailView.java:72-81`
**Issue:** XML declares `<facets><dataLoadCoordinator auto="true"/></facets>`, which will trigger the messages loader automatically once the `:conv` parameter is available. The Java `onReady` explicitly re-calls `messagesDl.setParameter(...); messagesDl.load();` — two load cycles per navigation. Also the query in XML uses `order by m.createdDate asc, m.seq asc` while the chat fragment uses `m.seq asc, m.createdDate asc` — inconsistent ordering between replay and live path.
**Fix:** Either remove the auto-coordinator or remove the manual load. Unify the order-by clause across both code paths (prefer `seq asc, createdDate asc`).

### WR-10: `AiAgentAppShell` `@Component` + `@ConditionalOnProperty` does not gate Vaadin's classpath scan

**File:** `AiAgentAppShell.java:35-46`
**Issue:** The class Javadoc acknowledges the open question (Assumption A2). Vaadin scans the classpath for `AppShellConfigurator` implementations independently of Spring's conditional bean resolution — `@ConditionalOnProperty` only controls Spring bean registration, not Vaadin's AppShell detection. If a host application already provides an `AppShellConfigurator`, deployment will fail at `bootRun` with "More than one class found with the AppShellConfigurator" regardless of the opt-out property.
**Fix:** Either (a) move `@Push` to a documented snippet consumers copy into their own shell (delete this class), or (b) keep the class but also gate it via a Maven classifier/optional dependency so it is only on the classpath when the consumer opts in. Assumption A2 must be resolved before 1.0 — add a bootRun verification test under multiple host-shell scenarios.

### WR-11: `CitationDialog.buildQueryParams` is dead code (`@SuppressWarnings("unused")`)

**File:** `CitationDialog.java:82-85`
**Issue:** Unused helper kept alive with `@SuppressWarnings`. Dead code per CLAUDE.md "Forbidden — commented-out/dead code" (spirit). Same for `ToolCallAuditListView.unused()`, `ToolCallCardComponent.asHasText`, `ConversationDetailView.unusedLoaderReference` — these exist only to silence unused-import / unused-field warnings.
**Fix:** Delete the dead helpers. If the field is genuinely needed for future use, remove it and add back when the use lands.

### WR-12: `ChatPanelFragment.setConversationId` silently reloads + not called from a lifecycle subscription

**File:** `ChatPanelFragment.java:137-147, ChatView.java:82,87,94,114`
**Issue:** `setConversationId(null)` is used for both "fresh chat" and "load failure recovery". The fragment clears the UI synchronously on the caller thread (`ChatView.beforeEnter` runs on the UI thread so this is fine), but there's no explicit invalidation of `activeStream` — a user who triggers `setConversationId(null)` mid-stream will get a new fragment state while the old stream continues writing into a cleared `messageList`. `activeAssistantBubble = null` prevents visible writes, but the Flux keeps pushing events until its natural completion.
**Fix:** Dispose the active stream at the top of `setConversationId`:
```java
public void setConversationId(UUID conversationId) {
    Disposable d = this.activeStream;
    if (d != null && !d.isDisposed()) d.dispose();
    this.activeStream = null;
    // ... existing clear code ...
}
```

### WR-13: `messageInput` Enter-to-send not wired

**File:** `chat-panel-fragment.xml:13-17`, `ChatPanelFragment.java`
**Issue:** Send button is the only way to submit. For a chat UX this is unusual — press Enter to send, Shift+Enter for newline is the convention. Not blocking, but materially affects UX acceptance.
**Fix:** Add a keydown listener on `messageInput` that routes Enter (no shift) to `onSendClick`. Or document explicitly that v1 is click-only.

## Info

### IN-01: Outcome theme branches rendered twice

**Files:** `ToolCallAuditDetailDialog.outcomeTheme` (line 102) and `ToolCallCardComponent.themeFor` (line 113) are identical switches.
**Fix:** Extract to a single utility (`AiToolCallOutcomeThemes.badgeTheme(outcome)`).

### IN-02: i18n key mismatch — outcome keys

**Files:** `messages.properties:231-238`, code usage in `ToolCallAuditDetailDialog:94`, `ToolCallAuditListView:93,120`.
**Issue:** Java looks up `auditList.outcome.success|blocked|error|flagged` (lowercased enum name). Properties define *both* `success/failed/denied/timeout/cancelled` (legacy) AND `blocked/error/flagged` (current). `failed/denied/timeout/cancelled` are orphan keys (enum has no such values).
**Fix:** Remove `auditList.outcome.failed|denied|timeout|cancelled` and `chatView.toolCard.outcome.failed|denied` from both properties files to prevent drift.

### IN-03: `ToolCallCardComponent` outcome label lookup path

**File:** `ToolCallCardComponent.java:105`
**Issue:** Uses `"com.vn.agent.entity/AiToolCallOutcome." + name` while `ToolCallAuditListView` uses `"auditList.outcome." + lowercased`. Two different key schemas for the same enum labelling.
**Fix:** Pick one and use everywhere.

### IN-04: `ChatPanelFragment.ownerUi` is redundant

**File:** `ChatPanelFragment.java:102, 111-114, 358-368`
**Issue:** `getUI()` (from `Composite`) returns an `Optional<UI>` that is kept in sync by Vaadin automatically. Capturing `ownerUi` in `onAttach` is defensive but doubles the tracking state.
**Fix:** Use `getUI()` exclusively.

### IN-05: Inline style strings repeated verbatim

**Files:** `ToolCallAuditDetailDialog.java:67-78`, `ToolCallCardComponent.java:66-76, 73-75`, `CitationDialog.java:50-52`.
**Issue:** `white-space: pre-wrap; max-height: 20em; overflow: auto` appears 5+ times.
**Fix:** CSS class or static helper.

### IN-06: `ParametersListView.parsedCache` cleared but also reparsed per renderer invocation

**File:** `ParametersListView.java:67, 104, 136-144`
**Issue:** Renderer is invoked once per row per render pass. Cache is clear on reload, so first render = parse all rows (N YAML parses), subsequent renders = cache hit. Acceptable, but the `ConcurrentHashMap` is overkill — renderers run on the UI thread.
**Fix:** Use `HashMap`.

### IN-07: Abbreviated field names `dl`, `dc` in `@ViewComponent`

**Files:** `auditsDl`, `auditsDc`, `documentsDl`, `documentsDc`, `parametersDl`, `parametersDc`, `conversationsDl`, `conversationsDc`, `messagesDl`, `messagesDc`.
**Issue:** MEMORY `feedback_no_abbreviations`: "enterprise codebase; spell names fully (no `uei`, `mc`, `mp`, `dt`)". `Dl`/`Dc` are abbreviations of DataLoader / DataContainer.
**Fix:** Rename to `auditsLoader` / `auditsContainer` etc. for consistency with the project rule. Given these are Jmix convention (Studio scaffolds use `Dl`/`Dc`), consider clarifying the project rule explicitly for Jmix-generated names — but at minimum do not introduce new `Dl`/`Dc` names in hand-written code.

### IN-08: `ChatView.newChatButton` click wiring in `onInit` + magic fallback

**File:** `ChatView.java:60-62, 91-92`
**Issue:** `if (!chatPanel.hasMessages()) { silent reset }` branches on UI state reachable from the fragment; the fragment could expose a higher-level "is dirty" concept instead of message count.
**Fix:** Rename `hasMessages()` to `isDirty()` or accept the count semantics.

### IN-09: `KnowledgeBaseView.onReingestClick` error message reuses `knowledgeBase.upload.rejected`

**File:** `KnowledgeBaseView.java:302, 332`
**Issue:** Reingest and delete error paths both surface `knowledgeBase.upload.rejected` — the label reads "Upload rejected: …" which is wrong semantics for a failed reingest/delete.
**Fix:** Add `knowledgeBase.reingest.failed` / `knowledgeBase.delete.failed` keys in both locales and use those.

---

_Reviewed: 2026-04-21_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_

---

## Addendum — Current Code Delta After `07-REVIEW-FIX.md`

This addendum was verified against the current Phase 7 code after the fixes recorded in
`07-REVIEW-FIX.md` iteration 2. It does **not** reopen items already fixed there
(stale loader params, Knowledge Base one-row lock-in, admin menu/view coverage,
`@Subscribe` refactor, dead code removal, double-load in conversation detail,
stream disposal on reset, Enter-to-send wiring, CSS extraction).

No new critical issues found in this pass. The remaining gaps are mostly Jmix-first
consistency and a couple of lifecycle correctness issues.

### AD-01: `ChatPanelFragment.onReady()` can register duplicate Enter handlers

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:106-115`

**Issue:** `onReady()` adds a native `keydown` listener every time the fragment reaches
`ReadyEvent`. Per Jmix Flow UI lifecycle, ready-stage callbacks can happen again when the
same view instance is shown again, so this can stack duplicate Enter handlers and trigger
multiple sends for one key press. The current code also uses
`addEventData("event.preventDefault()")`, which works as a side-effect trick but is not
the official listener API.

**Jmix-first fix:** register the DOM listener once, or guard it with a boolean/registration
field. Use the returned `DomListenerRegistration.preventDefault()` instead of piggybacking
`preventDefault()` through `addEventData(...)`.

### AD-02: `KnowledgeBaseView.beforeEnter()` accumulates loader listeners

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:188-204`

**Issue:** every navigation with `documentId` adds another `documentsDl.addPostLoadListener(...)`
and never unregisters it. The original "single-row lock-in" bug is fixed, but the new code
still grows a listener list across the lifetime of the same view instance. Later reloads can
re-select stale documents and make the behavior harder to reason about.

**Jmix-first fix:** keep a `pendingDocumentId` field and handle the selection in one
data-loader callback, then clear that field after the first matching load.

### AD-03: Some XML-declared Jmix components are still injected as raw Vaadin types

**Files:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java:3-6,54-61,105-114`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java:3-9,71-84,116-139`

**Issue:** these controllers inject `<textField>`, `<comboBox>`, and `<dateTimePicker>`
components as `com.vaadin.flow` base classes (`TextField`, `ComboBox`, `DateTimePicker`)
instead of Jmix Flow UI component types. The code works, but it gives up typed Jmix APIs,
typed change events, and makes the view layer less consistent with the rest of the module
where Jmix component classes are already used (`JmixUpload`, `JmixButton`, `JmixTextArea`,
`JmixBigDecimalField`, etc.).

**Jmix-first fix:** switch these fields to Jmix component classes and typed value-change
events so the controller stays fully inside the Flow UI contract.

### AD-04: A few view flows still bypass official Jmix services

**Files:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java:78-81`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:367-371`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditDetailDialog.java`

**Issue:** these paths still use raw Vaadin APIs (`UI.navigate`, `Notification.show`,
plain `Dialog`) instead of Jmix `ViewNavigators`, `Notifications`, and dialog views /
`DialogWindows`. This is the clearest remaining place where Phase 7 drops below the Jmix
abstraction layer.

**Jmix-first fix:** use `ViewNavigators` in the detail view, inject `Notifications` into
the chat fragment, and convert the citation/audit dialogs into Jmix dialog views when you
want the last non-Jmix pieces gone.

### AD-05: There is still user-facing text outside the message bundles

**Files:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/MessageBubbleComponent.java:76-80`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java:130-134`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java:188-220,240-244`

**Issue:** the stopped suffix in `MessageBubbleComponent` is still hardcoded as
`"— stopped"`, and some error fallbacks still render raw `"Error"`, `"Invalid form"`,
or `"YAML write error"` strings instead of bundle-backed messages. This violates the
repo's "all UI text via msg:// / bundles" rule.

**Jmix-first fix:** move these strings into `messages.properties` / `messages_vi.properties`
and resolve them through the view's message API.

### AD-06: Knowledge Base still reuses an upload-specific error key for non-upload failures

**Files:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:317-320`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:347-349`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties:214`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties:214`

**Issue:** reingest/delete failures still call `knowledgeBase.upload.rejected`, so the admin
gets an upload-specific message for operations that are not uploads.

**Jmix-first fix:** add dedicated keys such as `knowledgeBase.reingest.failed` and
`knowledgeBase.delete.failed` in both locales and use those from the two action handlers.
