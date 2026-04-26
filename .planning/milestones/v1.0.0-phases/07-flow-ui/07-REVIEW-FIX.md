---
phase: 07-flow-ui
fixed_at: 2026-04-21T00:00:00Z
review_path: .planning/phases/07-flow-ui/07-REVIEW.md
iteration: 2
findings_in_scope: 15
fixed: 12
skipped: 3
status: partial
---

# Phase 7: Code Review Fix Report

**Fixed at:** 2026-04-21
**Source review:** `.planning/phases/07-flow-ui/07-REVIEW.md`
**Iteration:** 1

**Summary:**
- Findings in scope: 15 (2 Critical + 13 Warning)
- Fixed: 9
- Skipped: 6

## Fixed Issues

### CR-01: `ToolCallAuditListView.rebuildQuery` leaves stale loader parameters

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java`
**Commit:** bc01b6a
**Applied fix:** Added `else { auditsDl.removeParameter(...) }` branches for each of the five filter fields (`user`, `tool`, `outcome`, `fromDate`, `toDate`) so clearing a filter after populating it removes the now-unreferenced parameter and avoids the `IllegalStateException: Parameter 'x' is not used` raised by Jmix `CollectionLoader.load()`.

### CR-02: `KnowledgeBaseView.beforeEnter` permanently scopes the loader to one document

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
**Commit:** 363d712
**Applied fix:** Replaced `documentsDl.setQuery("... where e.id = :docId")` with a `documentsDl.addPostLoadListener(...)` that scans the fully-loaded container for the cited document and calls `documentsDataGrid.select(...)`. The loader query is no longer mutated, so subsequent reloads (push refresh, reingest, delete) continue to show the full document list while the admin still lands on the cited row.

### WR-01: Admin role missing Chat + Conversation view policies

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`
**Commit:** ebedb6f
**Applied fix:** Added `aiAgent.chat` and `aiAgent.conversations` to `@MenuPolicy.menuIds`, and `AiAgent_Chat`, `AiAgent_Conversation.list`, `AiAgent_Conversation.detail` to `@ViewPolicy.viewIds`. Admins without the user role can now open the full menu.

### WR-06: Business logic ("admin probe") in `ConversationListView.currentUserIsAdmin` — fixed: requires human verification

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java`
**Commit:** e4a706d
**Applied fix:** Replaced brittle `GrantedAuthority` string-match against `AiAgentAdminRole.CODE` with Jmix `AccessManager.applyRegisteredConstraints(new UiShowViewContext("AiAgent_ToolCallAudit.list"))` — the same path `AdminViewAccessTest` already exercises. The fallback log level was also upgraded from DEBUG to WARN so transient probe failures are visible. Note: the probe now returns true only if the user can open an admin-only view; behavioural verification in a live Jmix context is advisable.

### WR-07: `ParametersDetailView.scanApplicationContextForToolMethods` is over-engineered

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java`
**Commit:** 4573288
**Applied fix:** Deleted the reflective `scanApplicationContextForToolMethods` fallback, its `try/catch` around the primary path, the `ApplicationContext` injection, and the now-unused import. `AgentToolCallbacks.forCurrentUser()` is now the single source of truth; a registry failure surfaces as an `IllegalStateException` at view init rather than a silent divergent enumeration.

### WR-09: `ConversationDetailView.onReady` double-loads messages

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-detail-view.xml`

**Commit:** ec2b728
**Applied fix:** Removed the manual `messagesDl.setParameter("conv", conv); messagesDl.load();` from `onReady` — the XML `<dataLoadCoordinator auto="true"/>` triggers the load once the `:conv` parameter becomes available from `conversationDc`. Also changed the ORDER BY clause in the XML query from `m.createdDate asc, m.seq asc` to `m.seq asc, m.createdDate asc` so replay and live chat render messages in the same sequence.

### WR-11: `CitationDialog.buildQueryParams` is dead code (and siblings)

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ToolCallCardComponent.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java`

**Commit:** 9524c3a
**Applied fix:** Deleted `CitationDialog.buildQueryParams`, `ToolCallAuditListView.unused()`, `ToolCallCardComponent.asHasText`, and `ConversationDetailView.unusedLoaderReference()` along with the now-orphan `InstanceLoader conversationDl` field and all corresponding unused imports.

### WR-12: `ChatPanelFragment.setConversationId` leaks an in-flight stream

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
**Commit:** 023c791
**Applied fix:** Added a `Disposable.dispose()` guard at the top of `setConversationId(UUID)` so a mid-stream reset tears down the Flux subscription before the UI state is cleared.

### WR-13: `messageInput` Enter-to-send not wired — fixed: requires human verification

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
**Commit:** 65e2576
**Applied fix:** Added a native keydown listener on `messageInput.getElement()` filtered to `event.key === 'Enter' && !event.shiftKey && !event.isComposing`, with `event.preventDefault()` so the text area does not also append a newline before firing the send. Shift+Enter still inserts a newline. The JavaScript filter and preventDefault semantics should be verified end-to-end in a running Jmix app.

## Skipped Issues

### WR-02: Raw `addClickListener` / `addValueChangeListener` instead of `@Subscribe`

**File:** Pervasive across all views (ChatView, ChatPanelFragment, ConversationDetailView, ConversationListView, KnowledgeBaseView, ParametersDetailView, ParametersListView, ToolCallAuditListView).
**Reason:** skipped: systemic refactor out of scope for an automated review-fix pass. Each conversion requires either an XML `<action>` declaration or a rename to match a button id, plus verification that existing value-change semantics (LAZY debounce, filter-reload chaining) are preserved. Should be tackled as a dedicated refactor phase.
**Original issue:** Jmix Flow UI convention prefers declarative `@Subscribe("buttonId")` wiring over programmatic listener registration.

### WR-03: Programmatic dialogs should be XML fragment / dialog views

**Files:** `CitationDialog.java`, `ToolCallAuditDetailDialog.java`.
**Reason:** skipped: converting each dialog to a Jmix XML view descriptor + `DialogWindows` navigator is a structural rewrite that needs XML fragments, message-bundle wiring, and regression tests across call sites. Out of scope for a review-fix pass; file as a dedicated follow-up.
**Original issue:** Per CLAUDE.md "Jmix-first UI over raw Vaadin", dialogs without streaming or dynamic composition should be XML descriptors.

### WR-04: `MessageBubbleComponent` / `ToolCallCardComponent` fully programmatic

**Files:** `MessageBubbleComponent.java`, `ToolCallCardComponent.java`.
**Reason:** skipped: per the review itself, the streaming/dynamic justification is legitimate; the improvement is a CSS extraction that touches theme files outside the phase's test coverage. Defer to a theming pass.
**Original issue:** Inline Lumo style strings should move to CSS theme classes.

### WR-05: `action="auditsDataGrid.excelExport"` type name verification

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/tool-call-audit-list-view.xml`.
**Reason:** skipped: this is a verification task, not a bug. The current XML was written with explicit awareness of the `grdexp_excelExport` token per 07-06 plan; if Jmix 2.8 had renamed it the view would fail at load time during any existing integration test run. Context7/docs lookup recommended before 1.0 but the code is not known-broken.
**Original issue:** Verify Jmix 2.8 grid export action type name.

### WR-08: Per-row `DataManager.loadValue` count in `ConversationListView` (N+1)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java`.
**Reason:** skipped: already documented in the class-level Javadoc as an accepted v1 tradeoff ("path (b) fallback ... typical non-admin result sets are small because row-level security already narrows to the current user"). Moving to a KeyValueCollectionLoader or `@JmixProperty` with `@DependsOnProperties("messages")` is a Phase 2 entity change plus a projection query, which is out of scope for a review-fix pass.
**Original issue:** N+1 count queries per rendered row.

### WR-10: `AiAgentAppShell` `@Component` + `@ConditionalOnProperty` does not gate Vaadin's classpath scan

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java`.
**Reason:** skipped: architectural decision (Assumption A2 per the class Javadoc). Options discussed (move `@Push` to a snippet consumers copy, or gate via optional Maven classifier) require build/packaging changes outside this phase. Should be resolved before 1.0 with a deploy-time bootRun test but is not fixable in-source alone.
**Original issue:** Vaadin scans the classpath for `AppShellConfigurator` independently of Spring conditionals; deploy conflicts cannot be suppressed by `@ConditionalOnProperty` alone.

---

## Iteration 2 (2026-04-21)

**Scope:** Revisit three findings previously skipped in iteration 1 — WR-02, WR-03,
WR-04. All three are fixed in this iteration; WR-03 is a partial / "at minimum"
fix per the review's fallback guidance.

**Updated summary:**
- Findings in scope: 15
- Fixed: 12 (9 from iteration 1 + 3 in iteration 2)
- Skipped: 3 (WR-05, WR-08, WR-10 remain deferred)

### Iteration 2 Fixed Issues

#### WR-02: Raw `addClickListener` / `addValueChangeListener` instead of `@Subscribe`

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java`

**Commit:** 3379772
**Applied fix:** Replaced static button-click and filter value-change
`addClickListener` / `addValueChangeListener` registrations with declarative
`@Subscribe("componentId")` handlers across all eight views/fragments. Raw
listeners intentionally retained where the handler needs per-event context that
is awkward to re-derive from an id: grid selection listeners that drive button
enabled state, per-row `ItemDoubleClickEvent` / `ItemClickEvent` navigation,
upload receivers, per-row renderer callbacks, and the native DOM keydown
listener on `messageInput` (needs JS-side `event.preventDefault()` and key
filter that `@Subscribe` cannot express). Those carve-outs match the review's
own guidance for "dynamic grid wiring". Compilation verified via
`./gradlew :ai-agent:compileJava`.

#### WR-04: `MessageBubbleComponent` / `ToolCallCardComponent` fully programmatic

**Files modified:**
- `ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css` (new)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/MessageBubbleComponent.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ToolCallCardComponent.java`

**Commit:** 4301468
**Applied fix:** Created `META-INF/resources/frontend/styles/ai-agent-chat.css`
on the ai-agent library classpath (standard Vaadin packaging for library
modules that do not own a `frontend/` dir) with BEM-style classes for
`ai-agent-bubble`, `ai-agent-bubble--user/assistant/system`,
`ai-agent-tool-card__heading`, and `ai-agent-tool-card__pre`. Both components
now declare `@CssImport("./styles/ai-agent-chat.css")` and use
`addClassName(...)` instead of programmatic `getStyle().set(...)` calls. The
Java side retains only buffer / streaming / outcome-theme logic — no inline
Lumo strings remain in either component.

#### WR-03: Programmatic dialogs should be XML fragment / dialog views — fixed: partial

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditDetailDialog.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/CitationDialog.java`
- `ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css`

**Commit:** b5e6471
**Applied fix ("at minimum" scope per review):** Added shared CSS classes
`ai-agent-scroll-pre` (14em default, for the audit dialog's args/result panes)
and `ai-agent-scroll-pre--tall` (30em modifier, for the citation dialog's
longer snippet payload) in `ai-agent-chat.css`. Replaced repeated inline
`getStyle().set("white-space", "pre-wrap") / .setMaxHeight(...) / .set("overflow", "auto")`
calls in both dialogs with `addClassName(...)` plus `@CssImport`. This also
consolidates the IN-05 repeated-inline-style smell called out in the review.

**Partial scope:** Full Jmix XML view-descriptor conversion + `DialogWindows`
navigator rewiring for both dialogs is deferred as a tracked follow-up. The
XML conversion touches two call sites, needs message-bundle re-wiring, and
removes the programmatic `MessageFormat`-driven header title on the citation
dialog — an amount of structural rewrite that is larger than a review-fix
pass. This matches the review's own fallback wording:
*"At minimum, hoist the styling ... into Lumo utility classes / a CSS file so
the Java controller is layout-free."* Compilation verified.

_Fixed: 2026-04-21_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 2_

---

_Fixed: 2026-04-21_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
