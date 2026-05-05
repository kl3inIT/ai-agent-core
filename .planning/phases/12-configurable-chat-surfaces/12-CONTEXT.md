# Phase 12: Configurable Chat Surfaces — Context

**Gathered:** 2026-04-30
**Status:** Ready for planning

<domain>
## Phase Boundary

Wrap the existing v1.0 baseline (`ChatPanelFragment` + `ChatService` +
`AiConversation` + `ConversationGateway` + `CancellationRegistry`) into **two**
admin-toggleable presentation surfaces — `FULL_ROUTE` (the existing `ChatView`)
and `HEADER_BUTTON` (a new in-host-navbar button that opens a new
`ChatDialogView` via Jmix `DialogWindow` non-modal anchored top-right) — with
continuous conversation state across surface switches via a
`@VaadinSessionScope` `AiChatSessionState`. Ship `AiUiSettings` (single-row
admin entity) for runtime toggling of which surface is enabled and which is
the default. Fold the auto-generated conversation title todo so titles arrive
automatically after the first assistant reply, plus a pencil-edit affordance
beside the title for manual override.

**Scope change vs ROADMAP/REQUIREMENTS as written:** the original 3-surface
shape (FULL_ROUTE + SIDEBAR + FLOATING) is reduced to 2 surfaces
(FULL_ROUTE + HEADER_BUTTON). This drops `SidebarChatComponent`, drops the
raw-Vaadin `Dialog.setModality(MODELESS).setDraggable(true)` bottom-right
launcher, and drops the P-21 admin-dialog stacking mitigation entirely (Jmix
`DialogWindow` already participates correctly in Vaadin's overlay manager).
The reference is `D:/DTH/jmix-crm` `MainView.chatButton` + `dialogWindows.detail(...)`.
ROADMAP.md §"Phase 12" and REQUIREMENTS.md SURF-01..SURF-10 will be updated to
match this 2-surface shape after CONTEXT.md is approved.

**In scope:**
- `AiUiSettings` Jmix entity in `agentstore` (single-row by convention).
  Fields: `enabledSurfaces` (Set<AiChatSurface>: `FULL_ROUTE`, `HEADER_BUTTON`),
  `defaultSurface` (AiChatSurface), audit fields. NOT bundled into
  `AiParameters`.
- `AiChatSurface` enum (`EnumClass<String>`): `FULL_ROUTE`, `HEADER_BUTTON`.
- `AiChatSessionState` `@VaadinSessionScope` bean — fields:
  `currentConversationId` (UUID, null = new chat) + listener registry
  (`Consumer<UUID>` for cross-tab/cross-fragment notification on conversation
  switch). NO `activeRunId` (delegated to existing `CancellationRegistry`),
  NO fragment instance store (fragments mount stateless).
- `AiChatUIState` `@UIScope` bean — fields: `dialogInstance`
  (`DialogWindow<ChatDialogView>` or null = closed). Dialog handle is
  per-tab; conversation continuity is per-session.
- `ChatSurfaceMounter` `@Component` listening to Vaadin `UIInitEvent` (fallback
  to first `AfterNavigationEvent` if `AppLayout` not yet attached at UIInit
  fire — verify timing via Context7 / spring-ai-flowui docs):
  - Locates `StandardMainView` / `AppLayout` instance via UI tree walk.
  - If absent: `log.warn(...)` with a "wrap your shell in StandardMainView"
    hint and skips mounting; FULL_ROUTE still works via the menu.
  - If present + `HEADER_BUTTON` enabled in `AiUiSettings`: creates a
    `chatButton` (icon `MAGIC`, themes `icon tertiary`, classNames `me-l`),
    `accessManager.applyRegisteredConstraints(new UiShowViewContext("AiAgent_ChatDialog"))`
    → `setVisible(context.isPermitted())`, then `appLayout.addToNavbar(button)`.
  - Listens to `AfterNavigationEvent`: when current route is `AiAgent_Chat`
    (the FULL_ROUTE) → `chatButton.setVisible(false)` to eliminate the
    dual-mount scenario; otherwise visible.
  - Listens to `AfterNavigationEvent`: re-reads `AiUiSettings`
    (no-cache, eventual consistency on next nav) and adjusts visibility/menu.
  - Programmatically hides `MenuItem` id `aiAgent.chat` when `FULL_ROUTE`
    is disabled.
- `ChatDialogView` new `@ViewController` (`StandardView`, no entity) with
  `chat-dialog-view.xml` descriptor that composes `<fragment
  class="ChatPanelFragment"/>` + a close button + a new-chat button. Opened
  via `dialogWindows.view(parentView, ChatDialogView.class).build()` with:
  `setModal(false)`, `setLeft("65%")`, `setTop("5%")`, `setWidth("35%")`,
  `setHeight("75%")`, `setResizable(true)`, `setDraggable(true)` (verify
  Jmix `DialogWindow` exposes draggable via Context7; if not, accept
  resizable-only). Dialog is attached at UI level (not parentView) so it
  persists across route navigation.
- Header button click handler — toggle:
  - If `AiChatUIState.dialogInstance == null` → open new `ChatDialogView`
    dialog, store handle, `chatPanelFragment.setConversationId(state.currentConversationId)`.
  - If dialog open → close it (does NOT dispose conversation; only hides
    UI; `AiChatSessionState.currentConversationId` survives).
- `ChatView.onBeforeEnter` (existing) extension: read `AiUiSettings`; if
  `FULL_ROUTE` disabled, `event.forwardTo(home)` + `notifications.create("Chat is available via the header button only")`.
- `AiUiSettingsService` `@Component`:
  - `loadCurrent()`: reads via `UnconstrainedDataManager` (READ-bypass
    because regular users have no policy on this admin-only entity);
    creates the singleton row via `dataManager.create(AiUiSettings.class)`
    + `unconstrainedDataManager.save(...)` on first read miss
    (`ApplicationReadyEvent` initializer or lazy-on-first-call).
  - Single-row strategy: convention id `AiUiSettings.SINGLETON_ID` (a
    fixed UUID string constant) — load by id; if missing, ensure-default.
- `AiUiSettingsView` admin Flow UI: `StandardDetailView<AiUiSettings>`,
  loads by singleton id, fields for `enabledSurfaces` (multi-select) +
  `defaultSurface` (single-select). Mirrors the existing
  `AiAgent_Configuration` view shape. Menu entry `aiAgent.uiSettings`
  in `menu.xml`.
- `ChatPanelFragment` layout extension — split 68/32 horizontal with
  right-side `attachmentsPanel` slot (mirrors jmix-crm
  `ai-conversation-detail-view.xml`). Phase 12 ships the empty slot
  (`<vbox id="attachmentsPanel" visible="false"/>` — hidden until
  Phase 13 wires `AiTaskFile` + `<upload>` into it). Pencil-edit button
  beside the conversation title `<h3>` opens
  `dialogs.createInputDialog(...)` for inline title rename
  (mirrors jmix-crm `AiConversationDetailView.java:219-260`).
- `AiConversationTitleService` `@Component`:
  - Trigger: `@Async @EventListener ConversationTitleEligibleEvent`
    fired from `DefaultChatServiceImpl` after assistant message persists
    + stream completes, when `assistant_message_count == 1` AND
    `conversation.title == default`.
  - Model: same provider/key/baseUrl as main `ChatClient`; per-call
    override via cloned `ChatClient.Builder` with
    `OpenAiChatOptions.builder().model(properties.modelId).temperature(0.0).maxTokens(32).build()`,
    no tools, no advisors, separate system prompt template
    `prompts/ai-conversation-title-system-prompt.st`.
  - Locale: detect via `conversation.locale` (if set) → fallback to
    `CurrentAuthentication` user pref → fallback to `Locale.getDefault()`.
    Prompt template carries Vietnamese + English placeholders.
  - Sanitize: strip leading/trailing quotes, trailing period, length-cap 80
    chars, reject `NEW_CONVERSATION` sentinel and empty/blank.
  - Save: re-load `AiConversation` via `UnconstrainedDataManager` before
    save → if title is no longer default (user pencil-edited), skip save
    (do not clobber). Save via `unconstrainedDataManager.save`.
  - Audit: `AuditWriter.writeToolCall` reuse with
    `eventName="conversation_title"`, `kind=AuditKind.CHAT`, `parentId=null`,
    model + latency + token counts + outcome. Cost-tracking visibility.
  - Failure mode: catch `Exception` → `log.warn("auto-title generation failed", e)` + audit `outcome=ERROR` → DO NOT re-throw (chat reply path stays clean).
- `AiAgentTitleProperties` (`@ConfigurationProperties("ai-agent.conversation-title")`):
  `enabled` (default true), `model-id` (optional override; default = main model),
  `max-context-messages` (default 6), `min-assistant-messages-trigger` (default 1).
  `@ConditionalOnProperty` gate so hosts can disable.
- TEST-14 cross-surface continuity test — `@SpringBootTest @UiTest`
  serial-mount shape:
  1. Boot UI, navigate to `AiAgent_Chat` (fragment A mounts).
  2. Send 1 message via `ChatService` test stub returning canned reply.
  3. Capture `AiChatSessionState.currentConversationId`.
  4. Navigate away (fragment A detaches via existing `onDetach` +
     `CancellationRegistry`).
  5. Open `ChatDialogView` via `dialogWindows.view(...)` (fragment B mounts).
  6. Assert fragment B reads same `conversationId` from state on
     `setConversationId` call.
  7. Send another message; assert
     `dataManager.load(AiConversation).count() == 1` AND
     `AiMessage.count()` matches (2 user + 2 assistant).
  Reuse `ChatPanelFragmentConversationIdTest` scaffolding.
- `AiInternalEntityNames` extension — add `AiUiSettings` to
  always-excluded set (Phase 10 D-11 mirror).
- `AiAgentAdminRole` extension — `@EntityPolicy(AiUiSettings.class, ALL)` +
  `@MenuPolicy("aiAgent.uiSettings")` + `@ViewPolicy("AiAgent_AiUiSettings.detail")`.
- Liquibase changelog `080-ai-ui-settings.xml` under
  `agentstore-changelog/`, included in parent `agentstore-changelog.xml`.
  UUID PK, audit columns, columns for `enabled_surfaces` (text/JSON or
  separate join table — planner picks per Jmix EnumClass<String> Set
  persistence convention) + `default_surface` (text).
- Locale messages in BOTH `messages.properties` and `messages_vi.properties`:
  every menu label, button label, dialog title, notification text, error
  message, surface enum display name, settings field label, prompt-template
  heading.

**Out of scope (explicit):**
- `SidebarChatComponent` mounted to `AppLayout slot="drawer-end"` — dropped
  by 2-surface scope decision. SURF-01.2 obsolete.
- Raw-Vaadin `Dialog.setModality(MODELESS).setDraggable(true)` bottom-right
  floating launcher — replaced by Jmix `DialogWindow` non-modal anchored
  top-right via header button. SURF-01.3 reshaped.
- P-21 admin-dialog stacking mitigation (DOM observer / `Dialogs` wrapper
  pattern) — moot because Jmix `DialogWindow` participates in Vaadin's
  shared overlay manager.
- `defaultPosition` / `defaultSize` configurability for the dialog —
  hard-coded to mirror jmix-crm 35%×75% top-right for v1.1; defer to v1.2
  if hosts request it.
- `setCompactMode(boolean)` on `ChatPanelFragment` (SURF-10) — defer; full
  layout used in both surfaces.
- Conversation list inline rendering inside `ChatPanelFragment` — defer;
  users reach the list via the existing `aiAgent.conversations`
  (`AiAgent_Conversation.list`) menu item.
- `<upload>` component + `AiTaskFile` entity + file storage wiring — Phase 13
  scope (TASK-01..TASK-05). Phase 12 ships only the empty `attachmentsPanel`
  slot (hidden by default) so Phase 13 mounts into existing layout without
  rebuilding.
- Real-time push of `AiUiSettings` changes to open UIs via `UI.access` —
  defer; eventual consistency on next `AfterNavigationEvent` is enough.
- Collapsible "AI did" tool-detail panel + ephemeral streaming-status
  indicator — already deferred per PROJECT.md / STATE.md "Out of scope for
  v1.1".
- Multi-conversation tabs / split-screen across surfaces — defer.

</domain>

<decisions>
## Implementation Decisions

### Surface-mounting (header button injection)

- **D-01:** `ChatSurfaceMounter` injects the header button via Vaadin
  `AppLayout.addToNavbar(...)` on `UIInitEvent`. Discovered via UI tree walk
  for the active `StandardMainView` / `AppLayout` instance. No host
  XML/Java changes required. Planner MUST verify timing via Context7
  `/jmix-framework/jmix` and `/vaadin/flow` (docs for `UIInitEvent` vs
  `AfterNavigationEvent` ordering relative to `AppLayout` attach). If
  AppLayout not yet attached at UIInit, fall back to first
  `AfterNavigationEvent` listener with one-shot semantics.
- **D-02:** Custom-shell graceful degradation — when `StandardMainView` /
  `AppLayout` is absent from the UI tree at the chosen mount tick, log a
  WARN ("AI Agent chat button not mounted: host main view does not extend
  AppLayout. Use the FULL_ROUTE surface or wrap your shell in
  StandardMainView.") and skip mounting silently. FULL_ROUTE remains
  reachable via menu and direct URL. NOT a hard requirement; NOT a
  fixed-position UI fallback (avoid floating over login / full-screen
  routes).
- **D-03:** When admin disables `FULL_ROUTE` in `AiUiSettings` (only
  HEADER_BUTTON enabled), the `aiAgent.chat` menu item is hidden
  programmatically on `UIInitEvent` and on every `AfterNavigationEvent`
  (`MenuItem.setVisible(false)`); the route itself is blocked at
  `ChatView.onBeforeEnter` — `event.forwardTo(home)` plus a
  `notifications.create("Chat is available via the header button only")`
  notice. Non-admin users do not encounter 404.
- **D-04:** `AiUiSettings` is read by per-UI no-cache `loadCurrent()` via
  `UnconstrainedDataManager` — called on `UIInitEvent` AND on every
  `AfterNavigationEvent`. Eventual consistency: admin save → user sees
  effect on next navigation. Acceptable cost: ~1 query per nav. NO Spring
  cache, NO real-time push, NO capture-once-per-UI semantics.

### Dialog shell

- **D-05:** New `ChatDialogView` (`StandardView`, no entity, own
  `chat-dialog-view.xml` descriptor) composes
  `<fragment class="ChatPanelFragment"/>` + close button + new-chat button.
  Both `ChatView` (route shell) and `ChatDialogView` (dialog shell)
  compose the same `ChatPanelFragment` — fragment is the shared chat
  substrate; shells handle surface-specific concerns (route binding +
  query-param parsing in `ChatView`, dialog close + `AiChatUIState`
  coordination in `ChatDialogView`). Click handler:
  `dialogWindows.view(parentView, ChatDialogView.class).build()` followed
  by Jmix `DialogWindow` styling calls.
- **D-06:** Dialog default size/position hard-coded to mirror jmix-crm:
  `setModal(false)`, `setLeft("65%")`, `setTop("5%")`, `setWidth("35%")`,
  `setHeight("75%")`, `setResizable(true)`. `setDraggable(true)` if Jmix
  `DialogWindow` exposes a draggable API in 2.8 (verify via Context7
  before commit). NO new fields in `AiUiSettings`, NO new
  `application.yml` properties for size/position in v1.1.
- **D-07:** Header button click is a toggle: open if dialog handle null,
  close if dialog handle present. Closing the dialog only hides the UI —
  it does NOT dispose the conversation, does NOT clear
  `AiChatSessionState.currentConversationId`. Re-opening reuses the same
  conversation by passing `state.currentConversationId` to
  `chatPanelFragment.setConversationId`. Toggle state lives in
  `AiChatUIState.dialogInstance` (`@UIScope`).
- **D-08:** Dialog is attached at UI level (NOT `parentView`-attached) so
  it persists across route navigation. The "chat from anywhere" property
  is required by the toggle/persist contract: user opens dialog on
  `/orders`, navigates to `/clients`, dialog stays + in-flight stream
  continues. Auto-cleanup happens on UI destroy / VaadinSession
  invalidate via Vaadin's standard lifecycle.

### Cross-surface continuity contract

- **D-09:** Two-bean split for state.
  - `AiChatSessionState` (`@VaadinSessionScope`, per SURF-04):
    `currentConversationId` (UUID, null = new chat) +
    listener registry (`Consumer<UUID>` for cross-fragment / cross-tab
    push via `UI.access`). Fragment subscribes in `onAttach`,
    unsubscribes in `onDetach`.
  - `AiChatUIState` (`@UIScope`): `dialogInstance` handle. Per-tab —
    each browser tab opens its own dialog.
  - When tab A's user clicks "New chat", `state.setCurrentConversationId(null)`
    → listener loop → tab B's mounted fragments receive notification via
    `UI.access` → fragment refreshes message list. Multi-tab semantics
    preserved by `@VaadinSessionScope`.
- **D-10:** `AiChatSessionState` deliberately does NOT store `activeRunId`
  (existing `CancellationRegistry` is the source of truth, keyed by
  `runId`) and does NOT store fragment instances (fragments are mounted
  stateless and re-derive their state from `currentConversationId` +
  JDBC memory rows on `setConversationId`).
- **D-11:** Dual-mount scenario (FULL_ROUTE + dialog active simultaneously)
  is eliminated by hiding the header button when current route is
  `AiAgent_Chat`. `ChatSurfaceMounter` listens to `AfterNavigationEvent`
  and toggles `chatButton.setVisible(currentRoute != AiAgent_Chat)`. When
  user navigates away from chat route → button reappears. This means
  there is at most ONE `ChatPanelFragment` instance active per tab
  consuming the chat stream.
- **D-12:** TEST-14 implementation: `@SpringBootTest @UiTest` serial-mount
  shape. Boot UI → navigate to `AiAgent_Chat` (fragment A mount) → send
  message via `ChatService` stub → capture `state.currentConversationId`
  → navigate away (fragment A detach + `CancellationRegistry.cancel`) →
  open `ChatDialogView` via `dialogWindows.view(...)` (fragment B mount)
  → assert fragment B reads same conversation id → send another message
  → assert `dataManager.load(AiConversation).count() == 1` and
  `AiMessage.count()` per session matches expected. Reuse
  `ChatPanelFragmentConversationIdTest` scaffolding.

### Auto-title service

- **D-13:** `AiConversationTitleService` ships in Phase 12, paired with a
  pencil-edit button on the conversation title (jmix-crm
  `AiConversationDetailView.java:219-260` pattern via
  `dialogs.createInputDialog`). Auto-title fills the default; pencil-edit
  is the manual override path. Re-load the `AiConversation` row before
  the auto-title save and skip if title is no longer the default sentinel
  (don't clobber user edits).
- **D-14:** Model strategy — same provider config as the main `ChatClient`,
  per-request override on a cloned `ChatClient.Builder`:
  `OpenAiChatOptions.builder().model(properties.modelId).temperature(0.0).maxTokens(32).build()`,
  tools=none, advisors=none, separate system prompt template
  `prompts/ai-conversation-title-system-prompt.st`. NO separate
  `ChatClient` bean. NO reuse of the main client without override.
- **D-15:** Async + event-driven trigger.
  `DefaultChatServiceImpl` publishes a
  `ConversationTitleEligibleEvent` after the assistant message persists
  and the stream completes. `AiConversationTitleService` consumes via
  `@Async @EventListener`. Trigger gate: `assistant_message_count == 1`
  AND `conversation.title == default` (configurable via message key).
  Add `@EnableAsync` and a sized `TaskExecutor` bean in autoconfig.
- **D-16:** Audit ON, locale-aware, fail-silent.
  - Audit: `AuditWriter.writeToolCall` reuse with
    `eventName="conversation_title"`, `kind=AuditKind.CHAT`,
    `parentId=null`, `argumentsJson` summary (model + max-context-messages),
    `resultSummary` final title, latency, token counts (if available),
    `outcome=SUCCESS|ERROR`. Cost tracking visible in audit list.
  - Locale: prompt template loads vi or en variant; chosen via
    `conversation.locale` → `CurrentAuthentication` user pref →
    `Locale.getDefault()`.
  - Failure: catch `Exception`, `log.warn`, audit `outcome=ERROR`,
    DO NOT re-throw. Chat reply path stays clean.
  - Sanitize: strip leading/trailing quotes, trailing period, cap 80
    chars, reject `NEW_CONVERSATION` sentinel + blank.
  - Properties: `AiAgentTitleProperties`
    (`@ConfigurationProperties("ai-agent.conversation-title")`):
    `enabled` (default true), `model-id` (optional override), `max-context-messages`
    (default 6), `min-assistant-messages-trigger` (default 1).
    `@ConditionalOnProperty` gate.

### Claude's Discretion

- `AiUiSettings` row management — single-row strategy via
  `AiUiSettings.SINGLETON_ID` constant UUID; ensure-default on
  `AiUiSettingsService.loadCurrent` first-call miss using
  `UnconstrainedDataManager.save` (or eager `ApplicationReadyEvent`
  initializer — planner picks). Mirror existing
  `AiAgent_Configuration` / `AiParameters` single-row admin view shape.
- `AiUiSettingsView` admin Flow UI — `StandardDetailView<AiUiSettings>`
  loads-by-singleton-id (override `loadEntity()` or use a custom loader);
  fields: `enabledSurfaces` checkbox-group on `AiChatSurface` enum +
  `defaultSurface` radio-group on `AiChatSurface` enum. Admin-only via
  `AiAgentAdminRole`. Menu entry `aiAgent.uiSettings` in `menu.xml`.
- `AiInternalEntityNames` extension — add `AiUiSettings` (Phase 10 D-11
  mirror).
- `AiAgentAdminRole` extension — `@EntityPolicy(AiUiSettings.class, ALL)`
  + `@MenuPolicy("aiAgent.uiSettings")` +
  `@ViewPolicy("AiAgent_AiUiSettings.detail")`.
- Liquibase changelog `080-ai-ui-settings.xml` under
  `agentstore-changelog/`, included in parent `agentstore-changelog.xml`.
  Persistence shape for `enabledSurfaces` Set<EnumClass<String>>:
  planner picks between (a) comma-separated text column with custom
  converter, (b) join table — verify Jmix 2.8 idiom via `jmix-entities`
  skill + Context7.
- `AiChatSurface` enum location — `com.vn.agent.entity` alongside other
  AI-* enum classes (`AiToolCallOutcome`, `AiMessageRole`, `AiAuditKind`).
- `ChatDialogView` package — `com.vn.agent.view.chat` alongside `ChatView`.
- `ChatSurfaceMounter` package — `com.vn.agent.view.chat` (UI mounting
  belongs to the chat-view package, not orchestration).
- `AiChatSessionState` + `AiChatUIState` package —
  `com.vn.agent.view.chat` (UI session/UI state; not orchestration
  context).
- Pencil-edit title button placement — beside `<h3>` title in
  `chat-panel-fragment.xml`; click handler in `ChatPanelFragment` opens
  `dialogs.createInputDialog` (mirrors jmix-crm
  `AiConversationDetailView.java:219-260`).
- `attachmentsPanel` layout slot — `<vbox id="attachmentsPanel"
  visible="false"/>` inside a `<split>` 68/32 in
  `chat-panel-fragment.xml`. Hidden by default in v1.1; Phase 13 sets
  visible + mounts `<upload>` + `gridLayout`. Empty-state component
  not shipped in Phase 12.
- `AiConversationTitleService` package — `com.vn.agent.conversation`
  (matches todo's recommended path).
- Prompt template location —
  `src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st`.
- New menu entry `aiAgent.uiSettings` ordering in `menu.xml` —
  alongside `aiAgent.configuration` (admin grouping).
- `AiUiSettings.enabledSurfaces` default — both `FULL_ROUTE` and
  `HEADER_BUTTON` enabled; `defaultSurface = FULL_ROUTE`.
- Test layout — `ChatPanelFragmentSurfaceSwitchTest` for TEST-14;
  `AiConversationTitleServiceTest` for sanitize / locale / idempotency
  (re-load before save) / fail-silent paths;
  `AiUiSettingsServiceSingletonTest` for ensure-default behavior; reuse
  existing `@UiTest` + `@SpringBootTest` infra.

### Folded Todos

- `2026-04-28-add-llm-auto-generated-conversation-titles.md` — folded
  into Phase 12 as D-13..D-16. Original problem (sidebar conversation
  list reads "New conversation" forever) is partially mitigated even
  without a sidebar in 2-surface scope: existing
  `AiAgent_Conversation.list` menu and the conversation header inside
  `ChatPanelFragment` both consume titles. Pencil-edit override pairs
  naturally so users always have an out when auto-title misses.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 12: Configurable Chat Surfaces" — goal,
  success criteria #1..#4, dependencies (Phase 9 baseline), requirements
  list. **Note:** ROADMAP describes 3 surfaces; CONTEXT.md scope-changes
  to 2 surfaces. Planner MUST update ROADMAP §"Phase 12" + REQUIREMENTS
  §"Configurable Chat Surfaces" SURF-01..SURF-10 to match the 2-surface
  shape as part of Plan 12-01.
- `.planning/REQUIREMENTS.md` — `SURF-01..SURF-10`, `ENT-06`, `TEST-14`.
  Authoritative for scope intent; surface enumeration to be amended.
- `.planning/PROJECT.md` §"Current Milestone v1.1.0" — value prop, in/out
  of scope. Note "Out of scope for v1.1: collapsible tool-detail panel
  + ephemeral streaming-status indicator (deferred)" preserved.
- `.planning/STATE.md` — Phase 11 shipped 2026-04-29 (PR #19); Phase 12
  is next.

### Prior phase context (load before planning)
- `.planning/phases/11-mutation-capable-built-in-tools/11-CONTEXT.md` —
  D-08 audit-row reuse pattern (`eventName` strings, no new
  `AuditKind`). Phase 12 auto-title reuses
  `AuditWriter.writeToolCall` with `eventName="conversation_title"`,
  `kind=AuditKind.CHAT` per the same convention.
- `.planning/phases/10-ai-specific-llm-exposure-policy/10-CONTEXT.md` —
  D-11 `AiInternalEntityNames` always-excluded set pattern. Phase 12
  adds `AiUiSettings` to that set.
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-CONTEXT.md` —
  D-15 stateless-component pattern (fragments and surfaces re-derive
  state from `setConversationId`).
- `.planning/milestones/v1.0.0-phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` —
  D-08 access-denied-as-not-found opacity rule (Phase 12 unaffected;
  noted for completeness).

### Add-on source touch points
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java` —
  existing FULL_ROUTE shell. `onQueryParametersChange` (lines 37-61)
  parses `?conversationId=...`; `onNewChatButtonClick` (lines 63-79)
  shows confirm dialog. Both behaviors are route-only and stay in
  `ChatView`; `ChatDialogView` does NOT inherit them.
  `onBeforeEnter` MUST be added to gate FULL_ROUTE access against
  `AiUiSettings.enabledSurfaces`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` —
  existing chat substrate. `setConversationId(UUID)` is the public API
  that both shells call. `onAttach` / `onDetach` lifecycle (lines
  102-122) handles `CancellationRegistry.cancel` on detach (Pitfall #8
  dispose-on-detach). Phase 12 ADDs subscriber registration to
  `AiChatSessionState` listener registry on attach + unregister on
  detach. NO change to existing dispose-on-detach behavior.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml` —
  Phase 12 wraps existing layout in `<split>` 68/32 horizontal: left =
  current `messageListSlot` + `messageInputSlot`, right =
  `<vbox id="attachmentsPanel" visible="false"/>` placeholder.
  Pencil-edit `<button id="editConversationTitleBtn"/>` beside `<h3>`
  title.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` /
  `DefaultChatServiceImpl` — Phase 12 publishes
  `ConversationTitleEligibleEvent` after assistant-message persistence
  + stream complete. NO changes to `ask` / `stream` signature.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java` —
  conversation creation / lookup. Auto-title service uses this (or
  `UnconstrainedDataManager`) to load the conversation row for the
  re-load-before-save check.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java` —
  source of truth for active runs. `AiChatSessionState` does NOT
  duplicate `activeRunId`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` —
  `writeToolCall` REQUIRES_NEW boundary. Auto-title audits via this
  with `eventName="conversation_title"`, `kind=AuditKind.CHAT`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` —
  extension site for `@EntityPolicy(AiUiSettings.class, ALL)` +
  `@MenuPolicy/@ViewPolicy` for the new admin view.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java` —
  add `AiUiSettings` to the always-excluded set (Phase 10 D-11 mirror).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` — new
  entry `aiAgent.uiSettings` referencing
  `AiAgent_AiUiSettings.detail`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`
  + `messages_vi.properties` — every new label, button text, dialog
  title, notification text, surface enum display name, settings field
  label, prompt-template heading. CLAUDE.md "ALL locale files".
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/` —
  new `080-ai-ui-settings.xml`. Include in parent
  `agentstore-changelog.xml`.

### New code to create (planner sketches package layout)
- `com.vn.agent.entity.AiChatSurface` — `EnumClass<String>` with
  `FULL_ROUTE`, `HEADER_BUTTON`.
- `com.vn.agent.entity.AiUiSettings` — Jmix entity, single-row,
  agentstore.
- `com.vn.agent.view.chat.AiUiSettingsService` — load/ensure-default,
  `UnconstrainedDataManager`-backed.
- `com.vn.agent.view.chat.AiChatSessionState` — `@VaadinSessionScope`.
- `com.vn.agent.view.chat.AiChatUIState` — `@UIScope`.
- `com.vn.agent.view.chat.ChatSurfaceMounter` — `@Component` listening
  `UIInitEvent` + `AfterNavigationEvent`.
- `com.vn.agent.view.chat.ChatDialogView` — `StandardView`.
- `com.vn.agent.view.uisettings.AiUiSettingsListView` — optional, can
  defer if singleton-only is enough; planner picks.
- `com.vn.agent.view.uisettings.AiUiSettingsDetailView` —
  `StandardDetailView<AiUiSettings>` with singleton-load override.
- `com.vn.agent.conversation.AiConversationTitleService` —
  `@Component` `@Async @EventListener`.
- `com.vn.agent.conversation.AiAgentTitleProperties` —
  `@ConfigurationProperties("ai-agent.conversation-title")`.
- `com.vn.agent.conversation.ConversationTitleEligibleEvent` —
  Spring application event.

### Reference implementation (pattern-learning, NOT a dependency)
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/view/main/MainView.java:97-114,198-215` —
  `chatButton` permission check pattern (`UiShowViewContext` +
  `accessManager.applyRegisteredConstraints` + `setVisible(context.isPermitted())`)
  AND `dialogWindows.detail(...).setModal(false).setLeft("65%").setTop("5%").setWidth("35%").setHeight("75%").setResizable(true)`
  open pattern. Adapt for our `dialogWindows.view(parentView, ChatDialogView.class)`
  (no entity binding; ChatDialogView is a no-entity StandardView).
- `D:/DTH/jmix-crm/src/main/resources/com/company/crm/view/main/main-view.xml:30-72` —
  navbar layout target shape; our `ChatSurfaceMounter` mounts the
  button programmatically into the same conceptual slot.
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/view/aiconversation/AiConversationDetailView.java:219-260` —
  pencil-edit title pattern via `dialogs.createInputDialog` with
  `InputParameter.stringParameter("title").withDefaultValue(currentTitle)`,
  validate non-blank, save via `getViewData().getDataContext().save()`.
- `D:/DTH/jmix-crm/src/main/resources/com/company/crm/ai/view/aiconversation/ai-conversation-detail-view.xml` —
  `<split>` 68/32 layout, attachmentsPanel slot, pencil-edit button
  beside `<h3>` title.
- jmix-crm reference for auto-title pattern (per-request
  `OpenAiChatOptions.model(modelId)` override on cloned
  `ChatClient.Builder`) — exact file path to be discovered by
  researcher; folded-todo references this pattern.

### Project conventions
- `CLAUDE.md` — UUID + `@Version` + `@InstanceName` on `AiUiSettings`;
  `DataManager` only (NOT `EntityManager`); `JetBrains` MCP
  `get_file_problems` after Java work; ALL locale files; menu entry
  for new view.
- MEMORY (`C:\Users\admin\.claude\projects\D--DTH-ai-agent-core\memory\`):
  - `feedback_jmix_first_ui.md` — Jmix XML view descriptors + Jmix
    components default; raw Vaadin only with explicit justification.
    `chatButton`, `ChatDialogView`, `AiUiSettingsView` ALL follow Jmix
    XML pattern.
  - `feedback_jmix_view_listeners.md` — verify uncertain `@Subscribe` /
    `@Install` syntax via Context7 → Jmix docs → GitHub before guessing.
    Applies to `ChatSurfaceMounter` (Vaadin `UIInitEvent` /
    `AfterNavigationEvent` listener wiring) and `ChatDialogView`
    descriptor.
  - `feedback_jmix_unconstrained_for_system_writes.md` —
    `AiUiSettingsService.loadCurrent` + `ensure-default` save AND
    `AiConversationTitleService` save MUST use
    `UnconstrainedDataManager` (jmix-security-data is on classpath;
    system-internal writes need policy bypass).
  - `feedback_jmix_messages_over_spring.md` — inject `io.jmix.core.Messages`
    in `ChatDialogView`, `AiUiSettingsView`, fragment additions.
    Per-view bundles trip IntelliJ plugin index — keep keys in root
    `messages.properties` + `messages_vi.properties`.
  - `feedback_jmix_dialogs_pattern.md` (if exists) — `dialogs.createInputDialog`
    for the pencil-edit button.
  - `feedback_jetbrains_mcp_in_workflow.md` — run `get_file_problems`
    on each new Java file after the chunk.
  - `feedback_no_abbreviations.md` — full identifiers
    (`AiChatSessionState`, not `ChatState`; `AiUiSettings`, not
    `UiSet`).
  - `feedback_jmix_loadvalue_store.md` — explicit `.store("agentstore")`
    for any raw-JPQL `loadValue` against `AiUiSettings` / `AiConversation`
    in title service.
  - `feedback_rich_tool_descriptions.md` — N/A (no `@Tool` methods in
    Phase 12).

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — `@JmixEntity` + UUID + `@Version` + `@InstanceName`
  for `AiUiSettings`; persistence of `Set<AiChatSurface>` (planner
  picks comma-separated text + converter vs join table).
- `jmix-enums` — `EnumClass<String>` shape for `AiChatSurface`;
  `fromId` / `getId` pattern; Liquibase column type (text).
- `jmix-views` — `StandardView` (no entity) for `ChatDialogView`;
  `StandardDetailView<AiUiSettings>` for `AiUiSettingsView`;
  `@Subscribe` / `@Install` / `@ViewComponent` patterns.
- `jmix-fragments` — `<fragment class="...">` composition in
  `chat-dialog-view.xml` and `chat-view.xml`.
- `jmix-services` — `DataManager` save semantics;
  `UnconstrainedDataManager` for system-internal writes;
  `@Transactional` for title save path.
- `jmix-security-roles` — `@EntityPolicy` extension on
  `AiAgentAdminRole`; `@MenuPolicy` / `@ViewPolicy` for
  `aiAgent.uiSettings` + `AiAgent_AiUiSettings.detail`.
- `jmix-i18n` — message bundles for surface enum names + every label
  in BOTH locales.
- `jmix-liquibase` — changelog conventions for
  `080-ai-ui-settings.xml`.
- `jmix-testing` — `@SpringBootTest` + `@UiTest` for TEST-14 serial
  mount.

### Spring AI primitives to verify in research
- Spring AI 1.1.4 cloned `ChatClient.Builder` + per-request
  `OpenAiChatOptions.builder().model(modelId).temperature(0.0).maxTokens(32)`
  override behavior — verify via Context7 `/spring-ai/spring-ai`
  before planner commits to D-14. Confirm tools=none / advisors=none
  isolation via builder semantics.
- Vaadin `UIInitEvent` vs first `AfterNavigationEvent` ordering
  relative to `AppLayout` attach — verify via Context7 `/vaadin/flow`
  + `/jmix-framework/jmix` before planner commits to D-01 mount tick.
- Jmix `DialogWindow` API surface in 2.8 —
  `setLeft/setTop/setWidth/setHeight/setResizable/setDraggable/setModal`
  availability + persistence-across-route-nav semantics. Verify via
  Context7 `/jmix-framework/jmix-context7`.
- Spring `@Async @EventListener` ordering relative to publishing
  transaction commit — verify the auto-title fires AFTER assistant
  message persistence (post-commit, or `@TransactionalEventListener`).
- Vaadin `MessageList` auto-scroll-to-bottom behavior on `addItem` /
  `setItems` — confirm no manual scroll handling needed (current
  `ChatPanelFragment` relies on this).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ChatView` (`com.vn.agent.view.chat`) — existing FULL_ROUTE shell.
  Continues unchanged except `onBeforeEnter` gate added.
- `ChatPanelFragment` (`com.vn.agent.view.chat.fragment`) — chat
  substrate with `setConversationId(UUID)` public API.
  `onAttach`/`onDetach` lifecycle handles `CancellationRegistry`
  dispose-on-detach (Pitfall #8). Phase 12 adds listener
  subscribe/unsubscribe to `AiChatSessionState` and a pencil-edit
  button beside the `<h3>` title.
- `ChatService` / `DefaultChatServiceImpl` — publishes
  `ConversationTitleEligibleEvent` post-commit. No signature changes.
- `ConversationGateway` — used by auto-title service (or
  `UnconstrainedDataManager` directly) for the re-load-before-save
  check.
- `CancellationRegistry` — source of truth for active runs;
  `AiChatSessionState` does NOT duplicate runId tracking.
- `AuditWriter.writeToolCall` (Phase 7.2) — REQUIRES_NEW boundary;
  reused for auto-title audit row with `eventName="conversation_title"`,
  `kind=AuditKind.CHAT`.
- `AccessManager` + `UiShowViewContext` — header button visibility
  gate (mirrors jmix-crm `MainView.checkChatButtonPermission()`).
- `Dialogs` Jmix service — `createInputDialog` for pencil-edit;
  `dialogWindows.view(parentView, ChatDialogView.class)` for
  HEADER_BUTTON dialog open.
- `UnconstrainedDataManager` — `AiUiSettings` ensure-default + auto-title
  save (jmix-security-data on classpath, system-internal writes need
  policy bypass per MEMORY `feedback_jmix_unconstrained_for_system_writes`).
- `AiInternalEntityNames` — extension site for `AiUiSettings`
  always-excluded marker (Phase 10 D-11 mirror).
- `AiAgentAdminRole` — extension site for
  `@EntityPolicy(AiUiSettings.class, ALL)` +
  `@MenuPolicy("aiAgent.uiSettings")` +
  `@ViewPolicy("AiAgent_AiUiSettings.detail")`.
- Existing `@UiTest` infra (`ChatViewStreamTest`, `ChatViewStopTest`,
  `ChatPanelFragmentConversationIdTest`,
  `ChatPanelFragmentLoadingIndicatorTest`) — TEST-14 reuses scaffolding.

### Established Patterns
- **Namespace:** `com.vn.agent.*`. Phase 12 adds
  `com.vn.agent.view.chat.*` (mounter + dialog view + state beans),
  `com.vn.agent.view.uisettings.*` (admin view), and
  `com.vn.agent.conversation.*` (auto-title).
- **agentstore datasource:** `@Store(name="agentstore")` on
  `AiUiSettings`. `@JmixEntity`, UUID + `@JmixGeneratedValue` +
  `@Version` + `@InstanceName` per CLAUDE.md.
- **`UnconstrainedDataManager`:** all system-internal writes
  (`AiUiSettings` ensure-default, `AiConversationTitleService` save).
- **Audit:** `AuditWriter.writeToolCall` with `eventName="conversation_title"`,
  `kind=AuditKind.CHAT`, `parentId=null` per existing tree-lite shape.
  No new `AuditKind`.
- **Locales:** every new UI / error / surface display string in BOTH
  `messages.properties` and `messages_vi.properties`.
- **Liquibase:** numeric prefix per existing `agentstore-changelog`
  convention; include in parent.
- **Jmix-first UI:** XML view descriptors + Jmix components for
  `ChatDialogView`, `AiUiSettingsView`. No raw Vaadin `Dialog`.

### Integration Points
- `ChatSurfaceMounter` is on the cold path (UI init + each navigation
  event). Per-nav cost: 1 `AiUiSettings` query + visibility checks +
  optional menu-item lookup. Acceptable.
- Auto-title is on the warm path (each first-assistant-reply per
  conversation). Async on a separate `TaskExecutor`; never blocks
  reply latency.
- `ChatView.onBeforeEnter` adds 1 `AiUiSettings` query per route hit.
- `ChatDialogView` open path: `dialogWindows.view(...)` + dialog
  styling + fragment mount + `setConversationId` (loads conversation
  + messages from DB). Same cost as opening `ChatView` route.
- `AiAgentAdminRole.adminViews` ViewPolicy gains
  `AiAgent_AiUiSettings.detail`. Non-admin users have no path to that
  view (no menu, no policy).

</code_context>

<specifics>
## Specific Ideas

- **2-surface scope is a deliberate simplification of the 3-surface
  ROADMAP shape.** Reference is `D:/DTH/jmix-crm` `MainView.chatButton` +
  `dialogWindows.detail(...).setModal(false).setLeft("65%").setTop("5%").setWidth("35%").setHeight("75%").setResizable(true)`.
  Drops `SidebarChatComponent`, drops raw-Vaadin `Dialog` modeless+draggable,
  drops P-21. Updates to ROADMAP.md / REQUIREMENTS.md happen in
  Plan 12-01.
- **`ChatPanelFragment` stays the shared substrate** — both shells
  (`ChatView` route, `ChatDialogView` dialog) compose it via Jmix
  `<fragment>`. Surface-specific behavior (route query-param parsing,
  new-chat confirm dialog) stays in `ChatView`; dialog-specific
  behavior (close button, dialog handle coordination) stays in
  `ChatDialogView`. Fragment never knows which surface mounted it —
  only `setConversationId` and `AiChatSessionState` listener registry.
- **Dialog persists across route navigation** by attaching at UI level
  (not parent-view-attached). Mid-stream survives nav; auto-cleanup
  on UI destroy. This is the "chat from anywhere" property; without
  it, dialog mode is pointless.
- **Dual-mount eliminated by hiding header button on `AiAgent_Chat`
  route.** Simpler than dual-mount input lock or Flux multicast. Means
  at most ONE `ChatPanelFragment` per UI tab is active for the chat
  stream — preserves existing `CancellationRegistry` semantics.
- **`AiUiSettings` persistence shape** is open. Set<EnumClass<String>>
  has two idioms in Jmix: comma-separated text column + custom
  converter, OR join table. Planner verifies via `jmix-entities` skill
  + Context7. Either is acceptable.
- **Auto-title model strategy** mirrors jmix-crm pattern: same provider,
  per-request override on cloned `ChatClient.Builder`. NO separate
  bean for v1.1. Future flexibility (separate provider for cost
  reasons) is a v1.2+ concern.
- **Pencil-edit + auto-title pairing:** auto-title fills the default
  title once, asynchronously, after first assistant reply.
  Pencil-edit is the manual override. Auto-title MUST re-load the
  conversation row before save and skip if title is no longer default
  — never clobber user edits. This applies even within the async
  window (user might pencil-edit between event publish and async
  consumer fire).
- **Header button visibility = (HEADER_BUTTON enabled in AiUiSettings)
  AND (user has AccessManager permission for ChatDialogView) AND
  (current route ≠ AiAgent_Chat).** Three-condition AND.
- **Fail-silent for auto-title** is essential. Title generation errors
  must NEVER surface to the user as a notification or interfere with
  the chat reply path. Audit `outcome=ERROR` + `log.warn` is enough.
- **TEST-14 explicitly covers the surface switch in BOTH directions**
  is a stretch goal — minimum is FULL_ROUTE → DIALOG. Reverse
  direction reuses the same fragment lifecycle and is implicit.
  Planner can extend the test if low cost.

</specifics>

<deferred>
## Deferred Ideas

- **`SidebarChatComponent` mounted to `slot="drawer-end"`** — original
  SURF-01.2 dropped by 2-surface scope decision. Could revive in v1.2
  if a host explicitly wants always-visible chat.
- **Raw-Vaadin `Dialog` modeless+draggable bottom-right launcher** —
  original SURF-01.3 reshaped. Defer if a host wants exactly this UX
  (vs Jmix `DialogWindow`).
- **P-21 admin-dialog stacking mitigation** — moot under Jmix
  `DialogWindow`. Re-evaluate only if v1.2 reintroduces raw Vaadin
  `Dialog`.
- **Dialog default size/position configurability** in `AiUiSettings`
  fields or `application.yml` properties — defer per SURF-06 v1.2.
- **`setCompactMode(boolean)` on ChatPanelFragment** (SURF-10) —
  defer; full layout used in both surfaces. Revisit if the dialog
  feels cramped at 35%×75%.
- **Real-time push of `AiUiSettings` changes to open UIs via UI.access**
  — defer; eventual consistency on next nav is acceptable.
- **Conversation list dropdown inside `ChatDialogView` / inline list
  in fragment** — defer; existing `aiAgent.conversations` menu
  link is enough for v1.1.
- **Right-side `attachmentsPanel` content (upload + AiTaskFile +
  TTL job)** — Phase 13 scope (TASK-01..TASK-05). Phase 12 ships only
  the empty hidden slot.
- **Collapsible "AI did" tool-detail panel + ephemeral
  streaming-status indicator** — already explicitly out of scope per
  PROJECT.md / STATE.md.
- **Multi-conversation tabs / split-screen across surfaces** — defer.
- **Separate small-model `ChatClient` bean for auto-title** —
  v1.1 uses per-request override; defer separate-bean shape until a
  host has a real provider-split need.
- **Cost-cap properties** for auto-title (e.g. `maxRetries`,
  `disableAfterFailures`) — defer; fail-silent + audit is enough
  for v1.1.
- **`AiUiSettings` audit log** (who toggled what when) — covered
  implicitly by Jmix audit on the entity if enabled at host level;
  no Phase 12 work.
- **Programmatic surface registration SPI** for hosts to add their
  own custom surface beyond FULL_ROUTE / HEADER_BUTTON — defer.

</deferred>

---

*Phase: 12-configurable-chat-surfaces*
*Context gathered: 2026-04-30*
