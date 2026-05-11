# Phase 12: Configurable Chat Surfaces — Specification

**Created:** 2026-04-30
**Ambiguity score:** 0.10 (gate: ≤ 0.20)
**Requirements:** 9 locked

> **Note:** This SPEC.md was derived retroactively from `12-CONTEXT.md`
> (committed `a541374`) plus ROADMAP.md §"Phase 12" and REQUIREMENTS.md
> SURF-01..SURF-10 + ENT-06 + TEST-14. The Socratic interview was skipped
> because requirements were already locked through `/gsd-discuss-phase 12`.
> The interview log records the discuss-phase areas as the de facto rounds.
> Scope was simplified during discuss-phase from 3 surfaces (FULL_ROUTE +
> SIDEBAR + FLOATING) to 2 surfaces (FULL_ROUTE + HEADER_BUTTON);
> ROADMAP/REQUIREMENTS amendments are tracked as Plan 12-01 follow-ups.

## Goal

The same `ChatPanelFragment` + `ChatService` + `AiConversation` runtime is
exposed through two admin-toggleable presentation surfaces — `FULL_ROUTE`
(`/ai-agent/chat`) and `HEADER_BUTTON` (a button injected into the host's
`StandardMainView`/`AppLayout` navbar that opens a non-modal Jmix
`DialogWindow` anchored top-right) — with the same `conversationId` and the
same JDBC memory rows surviving surface switches in a single VaadinSession.

## Background

The v1.0 baseline already ships `ChatPanelFragment`
(`ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`,
349 lines), `ChatService` / `DefaultChatServiceImpl`, `AiConversation` /
`AiMessage` JPA entities in `agentstore`, `ConversationGateway` for ID
continuity, and `CancellationRegistry` for stream lifecycle. There is exactly
one presentation surface today: the full-route `ChatView` mounted at
`/ai-agent/chat` with menu entry `aiAgent.chat`. There is no:

- `AiUiSettings` entity, no admin toggle for which surfaces are enabled.
- `AiChatSessionState` (no `@VaadinSessionScope` conversation-id holder).
- `ChatSurfaceMounter` (no automatic injection of UI into the host shell).
- Header-button affordance to open chat from any route.
- Auto-generated conversation titles — `AiConversation.title` defaults
  forever; no pencil-edit affordance for manual override.
- Cross-surface continuity test.

The reference pattern is `D:/DTH/jmix-crm`
`MainView.chatButton` + `dialogWindows.detail(...).setModal(false).setLeft("65%").setTop("5%").setWidth("35%").setHeight("75%").setResizable(true)`
which our 2-surface implementation mirrors but adapts (no entity binding;
shared fragment between route and dialog shells).

## Requirements

1. **Two presentation surfaces over the same backend** (SURF-01 reshaped for
   2-surface scope): `FULL_ROUTE` and `HEADER_BUTTON` are admin-toggleable
   via `AiUiSettings.enabledSurfaces`.
   - Current: One route-only surface (`AiAgent_Chat`); no admin toggle.
   - Target: `AiChatSurface` enum with values `FULL_ROUTE`, `HEADER_BUTTON`.
     Both surfaces compose the existing `ChatPanelFragment` via Jmix
     `<fragment>`. Route surface shell is the existing `ChatView`;
     header-button surface shell is a new `ChatDialogView` (`StandardView`,
     no entity binding) opened via `dialogWindows.view(...)`.
   - Acceptance: Boot test asserts an `AiChatSurface` enum exists with
     exactly the two values. Integration test asserts `ChatDialogView`
     exists and successfully mounts `ChatPanelFragment` with a known
     `conversationId`.

2. **`AiUiSettings` Jmix entity** (SURF-02 + ENT-06): single-row admin
   entity persisting which surfaces are enabled and which is the default.
   - Current: No `AiUiSettings` entity exists; surface state is implicit.
   - Target: `AiUiSettings` `@JmixEntity` in `agentstore` with UUID PK,
     `@Version`, `@InstanceName`, fields `enabledSurfaces`
     (`Set<AiChatSurface>`) and `defaultSurface` (`AiChatSurface`), audit
     fields. Single-row by convention via `AiUiSettings.SINGLETON_ID`
     constant. Liquibase changelog `080-ai-ui-settings.xml` registered in
     parent `agentstore-changelog.xml`. Added to
     `AiInternalEntityNames` always-excluded set (Phase 10 D-11 mirror).
   - Acceptance: Liquibase migration creates the table on startup;
     `dataManager.load(AiUiSettings.class).id(SINGLETON_ID).optional()`
     returns the row after first-call ensure-default. `AiInternalEntityNames`
     unit test asserts `AiUiSettings` is in the excluded set.

3. **`ChatSurfaceMounter` mounts the header button into the host shell**
   (SURF-03): listens to Vaadin `UIInitEvent` and `AfterNavigationEvent`,
   discovers the active `StandardMainView` / `AppLayout`, and calls
   `appLayout.addToNavbar(chatButton)`. Hosts need no code edits beyond
   depending on the starter.
   - Current: No mounter exists. Adding chat to a host today requires
     editing the host's `main-view.xml` (per jmix-crm reference).
   - Target: `ChatSurfaceMounter` `@Component`. On `UIInitEvent`:
     reads `AiUiSettings`; if `HEADER_BUTTON` enabled AND user has
     `UiShowViewContext("AiAgent_ChatDialog")` permission AND `AppLayout`
     is found in the UI tree, builds a `chatButton` (icon `MAGIC`, tertiary
     theme) and calls `addToNavbar`. On `AfterNavigationEvent`: re-reads
     `AiUiSettings` (no-cache) and toggles `chatButton.setVisible` based on
     three-condition AND: `(HEADER_BUTTON enabled) AND (user has dialog
     view permission) AND (current route ≠ AiAgent_Chat)`.
   - Acceptance: `@UiTest` asserts that booting a UI with the default
     `StandardMainView` and `AiUiSettings.enabledSurfaces` containing
     `HEADER_BUTTON` results in exactly one button in the navbar with id
     matching the chat button. Disabling `HEADER_BUTTON` and re-navigating
     removes the button from the navbar. Booting with a host that does NOT
     extend `AppLayout` produces a WARN log line and no button (no startup
     failure).

4. **`AiChatSessionState` carries the active conversationId across surfaces**
   (SURF-04 + SURF-05): `@VaadinSessionScope` bean; switching surface
   mid-session preserves the same `conversationId`, the same JDBC memory
   rows, and the same `AiConversation` row.
   - Current: Each `ChatPanelFragment` instance derives its `conversationId`
     from query parameter (route) or null (no other surface exists).
     There is no shared session state.
   - Target: `AiChatSessionState` `@VaadinSessionScope` bean exposing
     `getCurrentConversationId() / setCurrentConversationId(UUID)` and a
     listener registry (`Consumer<UUID>`) for cross-fragment notification
     via `UI.access`. Companion `AiChatUIState` `@UIScope` bean holds the
     `dialogInstance` handle (per-tab, since dialog handles are tab-bound).
     `ChatPanelFragment.onAttach` subscribes; `onDetach` unsubscribes.
     "New chat" from any surface calls
     `state.setCurrentConversationId(null)` and broadcasts to listeners.
   - Acceptance: `AiChatSessionStateTest` asserts that setting the
     conversationId from one fragment instance triggers a listener
     callback registered by another fragment instance in the same
     `VaadinSession`. State bean is annotated `@VaadinSessionScope`.

5. **HEADER_BUTTON opens a Jmix `DialogWindow` non-modal anchored top-right**
   (replaces SURF-06 + SURF-07 + SURF-01.3): button click toggles the
   dialog open/closed; dialog persists across route navigation; no raw
   Vaadin `Dialog`; no P-21 admin-dialog stacking mitigation needed.
   - Current: No header-button surface exists; raw Vaadin `Dialog` not used.
   - Target: Button click handler invokes
     `dialogWindows.view(parentView, ChatDialogView.class).build()` then
     applies `setModal(false)`, `setLeft("65%")`, `setTop("5%")`,
     `setWidth("35%")`, `setHeight("75%")`, `setResizable(true)` (and
     `setDraggable(true)` if Jmix `DialogWindow` exposes it in 2.8 — verify
     via Context7). Dialog attaches at UI level so it survives route
     navigation. Second click closes the dialog (does NOT dispose the
     conversation; only hides the UI). Dialog instance handle is held in
     `AiChatUIState.dialogInstance`.
   - Acceptance: `@UiTest` asserts (a) clicking the button when handle is
     null opens a `DialogWindow` with `isModal()==false`, registered handle
     in `AiChatUIState`; (b) clicking again closes the dialog and clears
     the handle without changing `AiChatSessionState.currentConversationId`;
     (c) opening the dialog, navigating to a different route, asserts the
     dialog is still attached to the UI.

6. **`AiUiSettingsView` admin Flow UI** (SURF-08): admin-only view for
   runtime toggle of which surfaces are enabled and which is the default.
   - Current: No admin view for surface toggles exists.
   - Target: `StandardDetailView<AiUiSettings>` with custom singleton-load
     binding (loads by `AiUiSettings.SINGLETON_ID`), checkbox-group field
     for `enabledSurfaces`, radio-group field for `defaultSurface`. Mirrors
     existing `AiAgent_Configuration` view shape. Menu entry
     `aiAgent.uiSettings` in `menu.xml` references view id
     `AiAgent_AiUiSettings.detail`. Gated by `AiAgentAdminRole` via new
     `@EntityPolicy(AiUiSettings.class, ALL)`,
     `@MenuPolicy("aiAgent.uiSettings")`,
     `@ViewPolicy("AiAgent_AiUiSettings.detail")`.
   - Acceptance: `AdminViewAccessTest` asserts non-admin users cannot
     navigate to `AiAgent_AiUiSettings.detail` (HTTP 403 / forwarded). Admin
     user can navigate, save changes, and the modified `AiUiSettings` row
     persists.

7. **Cross-surface conversation continuity test** (SURF-09 + TEST-14):
   `@SpringBootTest @UiTest` proves the same conversationId and JDBC memory
   rows survive a surface switch.
   - Current: No cross-surface continuity test exists (only one surface
     today).
   - Target: `ChatPanelFragmentSurfaceSwitchTest`: boot UI → navigate to
     `AiAgent_Chat` (fragment A mount) → send 1 message via stubbed
     `ChatService` → capture `state.currentConversationId` → navigate away
     (fragment A detach) → open `ChatDialogView` via `dialogWindows.view`
     (fragment B mount) → assert fragment B's `setConversationId(state.currentConversationId)`
     succeeds → send another message → assert
     `dataManager.load(AiConversation).count() == 1` AND
     `dataManager.load(AiMessage).list().size() == 4` (2 user + 2
     assistant) for that conversation.
   - Acceptance: Test passes deterministically in CI without flake
     across 5 consecutive runs.

8. **`AiConversationTitleService` auto-generates titles + pencil-edit
   override** (folded todo): a separate small-model ChatClient call (same
   provider, per-request override) generates the title asynchronously after
   the first assistant reply; users can manually rename via a pencil button.
   - Current: `AiConversation.title` is set to a static default on first
     message and never refreshed; no pencil-edit affordance.
   - Target: `AiConversationTitleService` `@Component` consumes
     `ConversationTitleEligibleEvent` published by `DefaultChatServiceImpl`
     post-commit. Trigger gate: `assistant_message_count == 1` AND
     `conversation.title == default`. Generates via cloned
     `ChatClient.Builder` with
     `OpenAiChatOptions.builder().model(properties.modelId).temperature(0.0).maxTokens(32).build()`,
     no tools, no advisors, locale-aware bilingual prompt template at
     `prompts/ai-conversation-title-system-prompt.st`. Re-loads the
     conversation row before save and skips if title is no longer the
     default sentinel (do not clobber pencil-edit). Saves via
     `UnconstrainedDataManager`. Audits via `AuditWriter.writeToolCall`
     with `eventName="conversation_title"`, `kind=AuditKind.CHAT`,
     `parentId=null`. Failures are caught, audited as `outcome=ERROR`,
     `log.warn`-ed, and never re-thrown. Pencil button beside the
     conversation `<h3>` opens `dialogs.createInputDialog(...)` mirroring
     jmix-crm `AiConversationDetailView.java:219-260`.
     `AiAgentTitleProperties`
     (`@ConfigurationProperties("ai-agent.conversation-title")`) gates the
     feature: `enabled` (default true), `model-id` (optional), `max-context-messages`
     (default 6), `min-assistant-messages-trigger` (default 1).
   - Acceptance: `AiConversationTitleServiceTest` asserts (a) title is
     generated and persisted within 5 seconds of the assistant reply when
     enabled; (b) title is locale-aware (vi vs en input produces the
     correct language output via stubbed model); (c) sanitization strips
     leading/trailing quotes, trailing period, length-cap 80, rejects
     `NEW_CONVERSATION` sentinel; (d) re-load-before-save skips when title
     was changed by pencil-edit between event publish and async fire;
     (e) thrown exception in the generator never propagates to the chat
     reply path; (f) audit row exists with `eventName="conversation_title"`
     and `kind="CHAT"` for both success and failure.

9. **FULL_ROUTE admin disable: hide menu + block route** (admin toggle
   completeness): when `FULL_ROUTE` is removed from
   `AiUiSettings.enabledSurfaces`, the `aiAgent.chat` menu item is hidden
   and direct navigation to `/ai-agent/chat` is blocked.
   - Current: Route and menu item are always visible (no admin toggle
     exists).
   - Target: `ChatSurfaceMounter` programmatically calls
     `MenuItem.setVisible(false)` for menu id `aiAgent.chat` on
     `UIInitEvent` and `AfterNavigationEvent` when `FULL_ROUTE` is disabled.
     `ChatView.onBeforeEnter` (new) reads `AiUiSettings` via
     `AiUiSettingsService.loadCurrent()`; if `FULL_ROUTE` not in
     `enabledSurfaces`, calls `event.forwardTo(homeViewId)` and shows a
     non-blocking notification "Chat is available via the header button
     only" via `notifications.create(...)`.
   - Acceptance: `@UiTest` asserts (a) with `FULL_ROUTE` enabled, the
     `aiAgent.chat` menu item is visible and `/ai-agent/chat` navigates to
     `ChatView`; (b) with `FULL_ROUTE` disabled, the menu item is hidden
     AND direct navigation to `/ai-agent/chat` results in `event.forwardTo`
     being called (assert via test harness) and a notification text
     matching the message bundle key.

## Boundaries

**In scope:**

- `AiChatSurface` enum (`EnumClass<String>`) with `FULL_ROUTE`,
  `HEADER_BUTTON`.
- `AiUiSettings` Jmix entity, single-row, agentstore.
- `AiUiSettingsService` ensure-default + `UnconstrainedDataManager`-backed
  load.
- `AiUiSettingsView` admin Flow UI (`StandardDetailView<AiUiSettings>`).
- `ChatSurfaceMounter` `@Component` listening to `UIInitEvent` +
  `AfterNavigationEvent`, mounting the header button into
  `AppLayout.addToNavbar`, hiding `aiAgent.chat` menu item when
  `FULL_ROUTE` disabled.
- `ChatDialogView` (`StandardView`, no entity) wrapping
  `<fragment ChatPanelFragment/>` plus close + new-chat buttons.
- `AiChatSessionState` (`@VaadinSessionScope`) holding `currentConversationId`
  + listener registry.
- `AiChatUIState` (`@UIScope`) holding `dialogInstance` handle.
- `ChatView.onBeforeEnter` gate against `AiUiSettings.enabledSurfaces`.
- `ChatPanelFragment` layout extension: `<split>` 68/32 with right-side
  `attachmentsPanel` slot (hidden in v1.1) + pencil-edit button beside
  conversation `<h3>` title.
- `AiConversationTitleService` `@Component` `@Async @EventListener` +
  `AiAgentTitleProperties` `@ConfigurationProperties`.
- `ConversationTitleEligibleEvent` Spring application event published by
  `DefaultChatServiceImpl` post-commit.
- Bilingual prompt template
  `src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st`.
- `AiAgentAdminRole` extension:
  `@EntityPolicy(AiUiSettings.class, ALL)`,
  `@MenuPolicy("aiAgent.uiSettings")`,
  `@ViewPolicy("AiAgent_AiUiSettings.detail")`.
- `AiInternalEntityNames` extension: `AiUiSettings` always-excluded.
- Liquibase changelog `080-ai-ui-settings.xml` (and parent include).
- Locale messages in BOTH `messages.properties` and `messages_vi.properties`
  for every new label, button, dialog title, notification text, surface
  enum display name, settings field label.
- `ChatPanelFragmentSurfaceSwitchTest` (TEST-14),
  `AiConversationTitleServiceTest`, `AiUiSettingsServiceSingletonTest`,
  `ChatSurfaceMounterTest`.
- ROADMAP.md / REQUIREMENTS.md amendments to reflect 2-surface scope
  (Plan 12-01 housekeeping task).

**Out of scope:**

- `SidebarChatComponent` mounted to `AppLayout slot="drawer-end"` — dropped
  by 2-surface scope decision; SURF-01.2 obsolete.
- Raw-Vaadin `Dialog.setModality(MODELESS).setDraggable(true)` bottom-right
  floating launcher — replaced by Jmix `DialogWindow`; SURF-01.3 reshaped.
- P-21 admin-dialog stacking mitigation (DOM observer / `Dialogs` wrapper)
  — moot under Jmix `DialogWindow`.
- Dialog default size / position configurability via `AiUiSettings` fields
  or `application.yml` — defer to v1.2 per SURF-06.
- `setCompactMode(boolean)` on `ChatPanelFragment` (SURF-10) — defer; full
  layout used in both surfaces.
- Conversation list inline rendering inside `ChatPanelFragment` — defer;
  users reach the list via the existing `aiAgent.conversations` menu.
- `<upload>` component + `AiTaskFile` entity + `FileStorage` wiring —
  Phase 13 scope (TASK-01..TASK-05). Phase 12 ships only the empty
  `attachmentsPanel` slot (hidden by default).
- Real-time push of `AiUiSettings` changes to open UIs via `UI.access` —
  defer; eventual consistency on next `AfterNavigationEvent` is enough.
- Collapsible "AI did" tool-detail panel + ephemeral streaming-status
  indicator — already deferred per PROJECT.md / STATE.md.
- Multi-conversation tabs / split-screen across surfaces — defer.
- Separate small-model `ChatClient` bean (vs per-request override) for
  auto-title — defer to v1.2 if a host needs provider split.
- Cost-cap properties for auto-title (max retries, disable-after-failures)
  — defer; fail-silent + audit is enough.
- Programmatic surface-registration SPI for hosts to add custom surfaces
  beyond `FULL_ROUTE` / `HEADER_BUTTON` — defer.

## Constraints

- Java 21 + Jmix 2.8 + Spring Boot 3 + Spring AI 1.1.4 + Vaadin Flow + relational
  DB (`agentstore`) per CLAUDE.md.
- All new UI uses Jmix XML view descriptors + Jmix components per MEMORY
  `feedback_jmix_first_ui` (no raw Vaadin shells).
- All system-internal writes (`AiUiSettings` ensure-default, auto-title save)
  use `UnconstrainedDataManager` per MEMORY
  `feedback_jmix_unconstrained_for_system_writes` (jmix-security-data on
  classpath).
- All new audit events reuse `AuditWriter.writeToolCall` REQUIRES_NEW
  boundary; no new `AuditKind` values.
- Locale messages MUST land in BOTH `messages.properties` and
  `messages_vi.properties` per CLAUDE.md.
- `ChatPanelFragment` public API (`setConversationId`, `hasMessages`,
  `isStreaming`, `startNewChat`) MUST remain unchanged — both shells
  (`ChatView`, `ChatDialogView`) compose it without modification beyond
  attach/detach lifecycle hooks for `AiChatSessionState` listener
  subscribe/unsubscribe.
- `AccessManager` is the authoritative authorization gate for the header
  button visibility and `AiUiSettingsView` access; per-attribute / view
  permission checks use Jmix policies, not custom logic, per MEMORY
  `feedback_ai_as_jmix_client`.
- Auto-title generator MUST NOT block the chat reply path — async via
  Spring `@EventListener @Async` with a sized `TaskExecutor` bean.
- Per-request `OpenAiChatOptions` override on a cloned `ChatClient.Builder`
  for auto-title (no separate `ChatClient` bean), with `tools=none` and
  `advisors=none`.
- Dialog must be UI-attached (not parentView-attached) so it persists
  across route navigation.
- TEST-14 must pass deterministically without flake; serial mount/detach
  shape with stubbed `ChatService`.

## Acceptance Criteria

- [ ] `AiChatSurface` enum exists with exactly `FULL_ROUTE` and
  `HEADER_BUTTON` values; `EnumClass<String>` shape; visible in the Jmix
  metadata model.
- [ ] `AiUiSettings` JPA entity exists in `agentstore` with single-row
  convention; Liquibase migration creates the table on startup; entity is
  in `AiInternalEntityNames` always-excluded set.
- [ ] `AiUiSettingsView` is reachable via menu `aiAgent.uiSettings` for
  `AiAgentAdminRole` and forbidden for non-admin users
  (`AdminViewAccessTest` extended).
- [ ] `ChatSurfaceMounter` adds the chat button to the host's `AppLayout`
  navbar on UIInit when `HEADER_BUTTON` is enabled; hides the button when
  current route is `AiAgent_Chat`; hides the `aiAgent.chat` menu item when
  `FULL_ROUTE` is disabled; logs WARN and skips silently when host has no
  `AppLayout`.
- [ ] `ChatDialogView` exists, has no `@EditedEntityContainer`, composes
  `<fragment class="...ChatPanelFragment"/>`, and opens via
  `dialogWindows.view(...)` with `isModal()==false`,
  `getLeft()=="65%"`, `getTop()=="5%"`, `getWidth()=="35%"`,
  `getHeight()=="75%"`, `isResizable()==true`.
- [ ] Toggle behavior: clicking the header button while `AiChatUIState.dialogInstance`
  is null opens the dialog; clicking again closes it without changing
  `AiChatSessionState.currentConversationId`.
- [ ] Dialog persists across route navigation (UI-attached): opening on
  `/orders` and navigating to `/clients` keeps the dialog attached.
- [ ] `AiChatSessionState` is annotated `@VaadinSessionScope`; setting
  `currentConversationId` triggers all registered listeners; `AiChatUIState`
  is annotated `@UIScope`.
- [ ] `ChatView.onBeforeEnter` forwards to home + shows notification when
  `FULL_ROUTE` is disabled in `AiUiSettings`.
- [ ] `ChatPanelFragmentSurfaceSwitchTest` passes deterministically:
  `AiConversation.count() == 1`, `AiMessage.count()` matches expected
  (2 user + 2 assistant), same `conversationId` across fragment A and
  fragment B.
- [ ] `AiConversationTitleService` updates `AiConversation.title` within 5
  seconds of the first assistant reply in stub-tests; respects locale;
  sanitizes output; skips when title is no longer default at save time;
  audits success and failure via `AuditWriter.writeToolCall` with
  `eventName="conversation_title"`, `kind="CHAT"`; thrown exceptions never
  surface to the chat reply path.
- [ ] Pencil-edit button on the conversation title opens
  `dialogs.createInputDialog`, validates non-blank, saves the new title;
  the auto-title async path skips clobber if the user has edited.
- [ ] Locale messages exist in BOTH `messages.properties` and
  `messages_vi.properties` for every new label.
- [ ] `AiAgentAdminRole` policy set includes `AiUiSettings` ALL +
  `aiAgent.uiSettings` menu + `AiAgent_AiUiSettings.detail` view.
- [ ] ROADMAP.md §"Phase 12" and REQUIREMENTS.md §"Configurable Chat
  Surfaces" SURF-01..SURF-10 are amended to reflect 2-surface scope as
  part of Plan 12-01.
- [ ] `JetBrains` MCP `get_file_problems` reports no errors on every newly
  created Java file (per MEMORY `feedback_jetbrains_mcp_in_workflow`).

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                    |
|--------------------|-------|------|--------|----------------------------------------------------------|
| Goal Clarity       | 0.92  | 0.75 | ✓      | 2 surfaces named explicitly; continuity contract locked. |
| Boundary Clarity   | 0.95  | 0.70 | ✓      | Explicit in/out lists; scope simplification documented.  |
| Constraint Clarity | 0.85  | 0.65 | ✓      | Java 21, Jmix 2.8, Spring AI 1.1.4, Jmix-first UI.       |
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 15 falsifiable checkboxes mapping to test classes.       |
| **Ambiguity**      | 0.10  | ≤0.20| ✓      |                                                          |

## Interview Log

> SPEC.md derived from the discuss-phase transcript instead of a fresh
> Socratic loop (the discussion + decisions were already locked in
> `12-CONTEXT.md`). Each row maps a discuss-phase area to its de facto
> SPEC dimension contribution.

| Round | Perspective     | Question summary                                  | Decision locked                                                                                          |
|-------|-----------------|---------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| 0     | Researcher      | What pattern does jmix-crm use? 2 vs 3 surfaces?  | 2 surfaces (FULL_ROUTE + HEADER_BUTTON) mirroring jmix-crm `chatButton` + `dialogWindows.detail` pattern |
| 1     | Researcher      | Header-button injection mechanism?                | `AppLayout.addToNavbar` on UIInitEvent (Claude's discretion, "You decide")                               |
| 1     | Failure Analyst | Custom shell (no AppLayout) fallback?             | Silent skip + WARN log                                                                                    |
| 1     | Boundary Keeper | FULL_ROUTE disabled — hide menu? block route?     | Both: hide menu + ChatView.onBeforeEnter forwardTo home                                                   |
| 1     | Researcher      | AiUiSettings cache + change propagation?          | Per-UI no-cache + reload on AfterNavigationEvent                                                          |
| 2     | Simplifier      | Dialog shell: new view vs reuse ChatView?         | New ChatDialogView (StandardView, no entity); shared ChatPanelFragment                                   |
| 2     | Boundary Keeper | Dialog default size: hard-code or configurable?   | Hard-code 35%×75% top-right mirroring jmix-crm                                                            |
| 2     | Researcher      | Click handler: toggle vs singleton vs re-open?    | Toggle: open/close; second click hides without disposing conversation                                     |
| 2     | Failure Analyst | Dialog persistence across route navigation?       | UI-attached (persists); auto-close on session end                                                         |
| 3     | Researcher      | AiChatSessionState API surface?                   | Minimal: currentConversationId + listener registry; dialog handle separate                                |
| 3     | Researcher      | State scope: @VaadinSessionScope vs @UIScope?     | Split: AiChatSessionState (@VaadinSessionScope) + AiChatUIState (@UIScope)                                |
| 3     | Failure Analyst | Dual-mount (route + dialog same conversation)?    | Hide header button when current route is AiAgent_Chat; eliminates dual-mount                              |
| 3     | Failure Analyst | TEST-14 implementation shape?                     | @SpringBootTest @UiTest serial mount; reuse ChatPanelFragmentConversationIdTest scaffolding               |
| 4     | Simplifier      | Auto-title: fold into Phase 12 or defer?          | Fold + ship pencil-edit override                                                                          |
| 4     | Researcher      | Auto-title model strategy?                        | Same provider + per-request `OpenAiChatOptions` override on cloned `ChatClient.Builder`                   |
| 4     | Simplifier      | Auto-title async strategy?                        | `@Async @EventListener` on `ConversationTitleEligibleEvent` post-commit                                   |
| 4     | Failure Analyst | Auto-title audit + locale + failure handling?     | Audit on (`eventName=conversation_title`, kind=CHAT), locale-aware, fail-silent                           |

---

*Phase: 12-configurable-chat-surfaces*
*Spec created: 2026-04-30*
*Next step: `/gsd-plan-phase 12` — discuss-phase already complete; planner reads SPEC.md (locked requirements) + 12-CONTEXT.md (locked implementation decisions)*
