# Phase 12: Configurable Chat Surfaces - Research

**Researched:** 2026-05-02 [VERIFIED: environment current_date]
**Domain:** Jmix 2.8 Flow UI, Vaadin Flow shell mounting, Spring AI conversation title generation [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md]
**Confidence:** HIGH for Jmix/Vaadin UI primitives and project stack; MEDIUM for exact `UIInitEvent` attach timing because the API is verified but attach ordering still needs an implementation test. [VERIFIED: Context7 `/jmix-framework/jmix-context7`; VERIFIED: `javap` local Gradle classpath]

<user_constraints>

## User Constraints (from CONTEXT.md)

The following locked decisions, discretion areas, and deferred ideas are copied from `.planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md`. Treat this section as the authoritative scope for planning; it supersedes the older three-surface roadmap wording. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md]

### Locked Decisions

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

### the agent's Discretion

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

### Deferred Ideas (OUT OF SCOPE)

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

</user_constraints>

## Project Constraints (from AGENTS.md)

- Use Context7 for current library/framework/API documentation, and use `/jmix-framework/jmix-context7` for Jmix reference information. [VERIFIED: AGENTS.md]
- Use relevant installed Jmix skills for entities, enums, views, fragments, services, security roles, i18n, Liquibase, and testing. [VERIFIED: AGENTS.md; VERIFIED: `C:/Users/admin/.codex/skills/jmix-*.md`]
- Prefer Jmix Flow UI XML descriptors and Jmix components; Java controllers should handle orchestration and event handling, not declarative layout trees. [VERIFIED: AGENTS.md]
- Verify Jmix UI event types and XML/component syntax before implementation; use official `@Subscribe`, `@Install`, and `@Supply` patterns. [VERIFIED: AGENTS.md]
- Use `DataManager` for normal data access and `UnconstrainedDataManager` for trusted system-internal writes under `jmix-security-data`; do not use `EntityManager` for regular CRUD. [VERIFIED: AGENTS.md]
- Raw JPQL `loadValue` / `loadValues` against `agentstore` entities must call `.store("agentstore")`; fluent `dataManager.load(EntityClass)` resolves the store from metadata. [VERIFIED: AGENTS.md; VERIFIED: .planning/STATE.md]
- Entity work requires `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`, Liquibase, and messages in all locale files; Lombok and direct constructors are forbidden on entities. [VERIFIED: AGENTS.md; VERIFIED: jmix-entities skill]
- UI work requires XML + Java view pairs, menu updates, message keys for all user-visible strings, and role policies for protected views/menus. [VERIFIED: AGENTS.md; VERIFIED: jmix-views skill; VERIFIED: jmix-i18n skill]
- Services use constructor injection; business logic belongs in services, not views. [VERIFIED: AGENTS.md; VERIFIED: jmix-services skill]
- New UI Java code should inject `io.jmix.core.Messages`, not Spring `MessageSource`, for locale-aware captions, notifications, and dialogs. [VERIFIED: AGENTS.md]
- Do not add an AI-specific exposure layer for this phase; AI remains another Jmix client using normal Jmix security, and Phase 12 only extends admin settings/security for the UI surfaces. [VERIFIED: AGENTS.md; VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md]
- Keep module count minimal and do not split headless/UI modules without a concrete consumer requirement. [VERIFIED: AGENTS.md]
- After meaningful Java changes, run JetBrains MCP `get_file_problems(..., onlyErrors=false)` on touched Java/XML files and then run module-scoped tests. [VERIFIED: AGENTS.md]

## Summary

Phase 12 should implement the CONTEXT-locked two-surface design, not the stale three-surface roadmap wording. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; VERIFIED: `.planning/ROADMAP.md` via `gsd-sdk query roadmap.get-phase "12"`] The correct architecture is one reusable `ChatPanelFragment`, one existing `ChatService`, one active `AiConversation` per user-session conversation, and two shells: the existing route `ChatView` plus a new `ChatDialogView` opened from a mounter-injected header button. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; VERIFIED: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`; VERIFIED: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`]

The biggest planning correction is persistence: do not store `Set<AiChatSurface>` as a JPA element collection, because Jmix documentation states that collections of enums are not supported as element collection attributes. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/entities.html] Use a single text column for deterministic comma-separated enum ids, with typed helper getters/setters, unless the planner intentionally chooses a separate entity/join table for future extensibility. [VERIFIED: jmix-enums skill; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/enumerations.html]

Auto-title belongs in the backend, not the UI shell. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md] Spring Framework supports transaction-bound event listeners whose default phase is after commit, and async event listeners run on a separate thread with exceptions not propagated to the publisher; use that combination deliberately or publish only after the assistant message is already durable. [CITED: https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/event.html; CITED: https://docs.spring.io/spring-framework/reference/6.2/core/beans/context-introduction.html]

**Primary recommendation:** Use the existing Jmix/Vaadin/Spring AI stack with no new core dependency; implement `AiUiSettings`, `AiChatSessionState`, `AiChatUIState`, `ChatSurfaceMounter`, `ChatDialogView`, and `AiConversationTitleService` around the existing `ChatPanelFragment` and `DefaultChatServiceImpl`. [VERIFIED: ai-agent/build.gradle; VERIFIED: ai-agent/ai-agent/ai-agent.gradle; VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md]

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| Admin surface toggles | Database / Storage | Jmix service | `AiUiSettings` is a single-row `agentstore` entity loaded by `AiUiSettingsService`; UI reads settings but persistence owns truth. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md] |
| Admin settings editor | Frontend Server (Jmix Flow UI) | Database / Storage | `AiUiSettingsView` is a `StandardDetailView<AiUiSettings>` using Jmix XML/data binding and role policies. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/flow-ui/views.html] |
| Header button mounting | Frontend Server (Vaadin/Jmix UI) | Security | `ChatSurfaceMounter` attaches a button to `AppLayout.addToNavbar(...)`, then checks view permission with `UiShowViewContext`. [VERIFIED: `javap` local Gradle classpath for `AppLayout.addToNavbar`; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/security/authorization.html] |
| Route/dialog presentation | Frontend Server (Jmix Flow UI) | Browser / Client | `ChatView` and `ChatDialogView` compose the same fragment; the browser only renders Vaadin components produced by the server-side UI tree. [VERIFIED: `ChatView.java`; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/flow-ui/fragments/using-fragments.html] |
| Conversation continuity | Frontend Server session state | Database / Storage | `AiChatSessionState` carries the active id while `AiConversation` and `AiMessage` rows back actual continuity. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; VERIFIED: `ChatPanelFragment.java`; VERIFIED: `ConversationGateway.java`] |
| Chat turns and streaming | API / Backend service | Frontend Server | `DefaultChatServiceImpl` owns model calls, tools, memory, and streaming events; the fragment only submits and renders. [VERIFIED: `DefaultChatServiceImpl.java`; VERIFIED: `ChatPanelFragment.java`] |
| Auto conversation title | API / Backend service | Spring AI provider | Title generation is asynchronous backend work using Spring AI and `UnconstrainedDataManager`, then the UI reloads persisted title state. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; VERIFIED: `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/AiConversationTitleService.java`] |
| Authorization for surfaces | Security / Backend policy | Frontend Server | Role annotations and `AccessManager` decide whether views/menus/buttons are visible or navigable. [VERIFIED: `AiAgentAdminRole.java`; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/security/authorization.html] |

<phase_requirements>

## Phase Requirements

The old `SURF-01..SURF-10` descriptions still name `SIDEBAR` and `FLOATING`, but Phase 12 CONTEXT explicitly says Plan 12-01 must amend roadmap and requirements to the two-surface `FULL_ROUTE` + `HEADER_BUTTON` shape. [VERIFIED: .planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md; VERIFIED: .planning/REQUIREMENTS.md]

| ID | Description | Research Support |
|----|-------------|------------------|
| SURF-01 | Presentation surfaces over same backend and fragment; original text says three surfaces. [VERIFIED: .planning/REQUIREMENTS.md] | Implement two surfaces only: `FULL_ROUTE` and `HEADER_BUTTON`; sidebar/floating are out of scope. [VERIFIED: 12-CONTEXT.md] |
| SURF-02 / ENT-06 | `AiUiSettings` entity in `agentstore`, single-row settings. [VERIFIED: .planning/REQUIREMENTS.md] | Use Jmix entity conventions, UUID/version/instance name, text-backed enum ids for enabled surfaces, and Liquibase `080-ai-ui-settings.xml`. [VERIFIED: jmix-entities skill; CITED: Jmix enumeration docs] |
| SURF-03 | `ChatSurfaceMounter` injects enabled surfaces into host shell. [VERIFIED: .planning/REQUIREMENTS.md] | Implement `VaadinServiceInitListener`, call `event.getSource().addUIInitListener(...)`, and add an `AfterNavigationListener` fallback. [VERIFIED: `javap` local Gradle classpath for `VaadinService.addUIInitListener`; VERIFIED: `javap` local Gradle classpath for `UI.addAfterNavigationListener`] |
| SURF-04 / SURF-05 | Session continuity and one active conversation id shared by surfaces. [VERIFIED: .planning/REQUIREMENTS.md] | Use `@VaadinSessionScope AiChatSessionState` for `currentConversationId`; do not store run ids or fragments. [VERIFIED: 12-CONTEXT.md; CITED: https://vaadin.com/docs/latest/flow/integrations/spring/scopes] |
| SURF-06 / SURF-07 / SURF-10 | Floating-launcher size/z-index/compact-mode items from stale requirements. [VERIFIED: .planning/REQUIREMENTS.md] | Do not implement; CONTEXT defers floating launcher, z-index mitigation, and compact mode. [VERIFIED: 12-CONTEXT.md] |
| SURF-08 | Admin Flow UI for runtime toggles. [VERIFIED: .planning/REQUIREMENTS.md] | Use `StandardDetailView<AiUiSettings>`, XML descriptor, enum checkbox/radio fields, menu item, messages, and admin role policies. [VERIFIED: jmix-views skill; VERIFIED: 12-CONTEXT.md] |
| SURF-09 / TEST-14 | Cross-surface continuity test. [VERIFIED: .planning/REQUIREMENTS.md] | Use `@SpringBootTest @UiTest` where executable, or a pragmatic add-on test harness if Flow UI test assist cannot boot in the add-on module. [CITED: Jmix UI integration test docs; VERIFIED: `ChatViewStreamTest.java` notes no add-on `@UiTest` precedent] |
| SEC-05 | Extend admin role for `AiUiSettings`. [VERIFIED: .planning/REQUIREMENTS.md] | Add entity/view/menu policies to `AiAgentAdminRole` and extend `AdminViewAccessTest`. [VERIFIED: `AiAgentAdminRole.java`; VERIFIED: `AdminViewAccessTest.java`] |
</phase_requirements>

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java toolchain | 21 target, Gradle launcher JVM Temurin 25.0.2 | Compile/runtime baseline for this repo | `ai-agent/build.gradle` and `jmix-app/build.gradle` set `JavaLanguageVersion.of(21)` and `options.release = 21`; AGENTS.md says Java 17 but repository README says Java 21 is required. [VERIFIED: ai-agent/build.gradle; VERIFIED: jmix-app/build.gradle; VERIFIED: `./gradlew.bat --version`; VERIFIED: ai-agent/README.md] |
| Gradle | 8.14.4 | Build/test runner | Wrapper reports Gradle 8.14.4. [VERIFIED: `./gradlew.bat --version`] |
| Jmix | 2.8.1 | Entity metadata, DataManager, Flow UI, security roles, Liquibase integration | Jmix Gradle plugin and BOM are pinned to 2.8.1; existing module uses Jmix Flow UI starters. [VERIFIED: ai-agent/build.gradle; VERIFIED: `./gradlew :ai-agent:dependencies --configuration runtimeClasspath`] |
| Spring Boot | 3.5.13 | Application/autoconfiguration/test runtime | Runtime dependency graph resolves Boot starters to 3.5.13. [VERIFIED: Gradle dependency report] |
| Spring Framework | 6.2.17 | Events, transactions, async infrastructure | Test runtime resolves `spring-core:6.2.17`; docs queried are Spring Framework 6.2. [VERIFIED: Gradle dependency report; CITED: Spring Framework 6.2 docs] |
| Vaadin Flow server | 24.9.13 | Server-side UI tree, UI init, navigation, session/UI scopes | Runtime/test classpath resolves `flow-server:24.9.13`; `VaadinService`, `UIInitEvent`, and `UI.addAfterNavigationListener` APIs were verified locally. [VERIFIED: Gradle dependency report; VERIFIED: `javap` local Gradle classpath] |
| Vaadin components | 24.9.12 | AppLayout, Dialog, MessageList/MessageInput | Runtime graph resolves `vaadin-app-layout-flow:24.9.12` and `vaadin-dialog-flow:24.9.12`; local bytecode exposes needed methods. [VERIFIED: Gradle dependency report; VERIFIED: `javap` local Gradle classpath] |
| Spring AI | 1.1.4 | Existing chat client, JDBC memory, RAG, title model call | Build pins `spring-ai-client-chat`, JDBC chat memory, RAG, pgvector, Tika, and test modules to 1.1.4. [VERIFIED: ai-agent/ai-agent/ai-agent.gradle; VERIFIED: Gradle dependency report] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jmix Flow UI test assist | Jmix 2.8.x-aligned | UI integration test harness | Use for TEST-14 if the add-on or host-app test module can boot `@UiTest`. [CITED: Jmix UI integration test docs; VERIFIED: jmix-app/build.gradle] |
| JUnit Jupiter | 5.12.2 | Unit/integration tests | Use for service, mounter, state, and title tests. [VERIFIED: Gradle testRuntimeClasspath report] |
| AssertJ | 3.27.7 | Assertions | Existing test classpath includes AssertJ via Spring Boot test. [VERIFIED: Gradle testRuntimeClasspath report] |
| Mockito / `@MockitoBean` | Mockito 5.17.0 | Test doubles for services/LLM boundaries | Use `@MockitoBean`, not deprecated `@MockBean`, per project convention and test classpath. [VERIFIED: Gradle testRuntimeClasspath report; VERIFIED: jmix-testing skill; VERIFIED: `.planning/STATE.md`] |
| HSQLDB | 2.7.3 | Default test database | Existing default tests run against HSQLDB and Liquibase. [VERIFIED: Gradle testRuntimeClasspath report] |
| Testcontainers Postgres | Declared 1.19.8, resolved core 1.21.4 for transitive pieces | Optional pgvector/RAG integration tests | Not required for Phase 12 default continuity tests unless planner chooses a Postgres-specific coverage path. [VERIFIED: ai-agent/ai-agent/ai-agent.gradle; VERIFIED: Gradle testRuntimeClasspath report] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Jmix `DialogWindow<ChatDialogView>` | Raw Vaadin `Dialog` bottom-right floating launcher | Raw Vaadin launcher matches the stale roadmap but contradicts CONTEXT; Jmix `DialogWindow` integrates with Flow UI view lifecycle and exposes modal/draggable/resizable/position APIs. [VERIFIED: 12-CONTEXT.md; VERIFIED: `javap` local Gradle classpath for `AbstractDialogWindow`] |
| Text-backed `enabledSurfaces` column | JPA element collection of enum values | Jmix docs explicitly do not support collections of enums as element collection attributes; a separate join/entity is heavier than needed for two values. [CITED: Jmix entities docs; VERIFIED: jmix-enums skill] |
| `@VaadinSessionScope` + `@UIScope` split | Static singleton or fragment-instance registry | Session/UI scopes match Vaadin's state model; fragment-instance registries would leak UI components and conflict with CONTEXT D-10. [CITED: Vaadin Spring scopes docs; VERIFIED: 12-CONTEXT.md] |
| `@TransactionalEventListener` / Spring async | Manual thread creation for title generation | Spring docs provide transaction phase binding and async listener semantics; manual threads would bypass Spring context, error handling, and executors. [CITED: Spring Framework event docs] |

**Installation:**

No new dependency should be added for Phase 12. [VERIFIED: 12-CONTEXT.md; VERIFIED: ai-agent/ai-agent/ai-agent.gradle]

```bash
# Use existing Gradle dependencies; do not install new packages.
.\gradlew.bat :ai-agent:test
```

**Version verification:** Versions above were verified from `ai-agent/build.gradle`, `jmix-app/build.gradle`, Gradle runtime/test dependency reports, and local Gradle cache bytecode inspection. [VERIFIED: Gradle dependency report; VERIFIED: `javap` local Gradle classpath]

## Architecture Patterns

### System Architecture Diagram

The primary flow should be planned as a reusable fragment plus thin shells, not as separate chat implementations per surface. [VERIFIED: 12-CONTEXT.md]

```text
VaadinServiceInitListener
        |
        v
UIInitEvent -> ChatSurfaceMounter -> load AiUiSettings (agentstore)
        |                              |
        |                              v
        |                       enabled FULL_ROUTE? -> menu aiAgent.chat visible / route gate
        |                              |
        v                              v
first AfterNavigationEvent fallback -> StandardMainView/AppLayout found?
        |                              |
        | yes                          | no
        v                              v
AppLayout.addToNavbar(chatButton)      log WARN, route still works
        |
        v
Header button click -> AiChatUIState.dialogInstance?
        | open null                         | close non-null
        v                                   v
DialogWindows.view(ChatDialogView)          close dialog only
        |
        v
ChatDialogView -> ChatPanelFragment.setConversationId(AiChatSessionState.currentConversationId)
        |
        v
ChatPanelFragment -> ChatService.stream(...) -> AiConversation/AiMessage/JDBC memory
        |
        v
ConversationTitleEligibleEvent -> async title service -> UnconstrainedDataManager save + audit
```

### Recommended Project Structure

```text
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── entity/
│   ├── AiChatSurface.java          # EnumClass<String> ids FULL_ROUTE, HEADER_BUTTON
│   └── AiUiSettings.java           # singleton agentstore entity
├── view/chat/
│   ├── AiChatSessionState.java     # @VaadinSessionScope active conversation id
│   ├── AiChatUIState.java          # @UIScope dialog handle
│   ├── AiUiSettingsService.java    # load/ensure singleton settings
│   ├── ChatSurfaceMounter.java     # Vaadin service/UI/navigation listener
│   ├── ChatDialogView.java         # non-entity StandardView dialog shell
│   └── ChatView.java               # existing full route + route gate
├── view/uisettings/
│   └── AiUiSettingsDetailView.java # singleton settings editor
├── conversation/
│   ├── ConversationTitleEligibleEvent.java
│   ├── AiAgentTitleProperties.java
│   └── AiConversationTitleService.java
├── security/
│   └── AiAgentAdminRole.java       # add entity/menu/view policy methods
└── exposure/
    └── AiInternalEntityNames.java  # add ai_AiUiSettings
```

This structure follows the CONTEXT package placement and the repo's `entity/`, `view/`, `security/`, and service layering conventions. [VERIFIED: 12-CONTEXT.md; VERIFIED: AGENTS.md; VERIFIED: repository tree]

### Pattern 1: Shared Fragment, Thin Shells

**What:** Keep `ChatPanelFragment` as the only chat UI substrate and let `ChatView` and `ChatDialogView` compose it. [VERIFIED: 12-CONTEXT.md; VERIFIED: `ChatPanelFragment.java`]

**When to use:** Use this for every Phase 12 surface so message rendering, stream cancellation, history replay, and input behavior do not fork. [VERIFIED: 12-CONTEXT.md; VERIFIED: `ChatPanelFragment.java`]

**Example:**

```xml
<!-- Source: Jmix fragments docs; Phase 12 adapts to ChatDialogView. -->
<view xmlns="http://jmix.io/schema/flowui/view">
    <layout>
        <vbox width="100%" height="100%">
            <fragment id="chatPanelFragment"
                      class="com.vn.agent.view.chat.fragment.ChatPanelFragment"/>
        </vbox>
    </layout>
</view>
```

Jmix documents reusable fragments with a controller extending `Fragment<...>` and a view XML `<fragment class="..."/>` include. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/flow-ui/fragments/using-fragments.html]

### Pattern 2: Vaadin Service Init Plus Navigation Fallback

**What:** Register a Vaadin UI init listener through a `VaadinServiceInitListener`, then use `UI.addAfterNavigationListener(...)` for route-aware visibility and first-navigation fallback. [VERIFIED: `javap` local Gradle classpath for `VaadinService`, `UI`, `UIInitEvent`; VERIFIED: 12-CONTEXT.md]

**When to use:** Use this for no-host-code header injection. [VERIFIED: 12-CONTEXT.md]

**Example:**

```java
// Source: local Vaadin 24.9.13 bytecode verification.
@Component
public class ChatSurfaceMounter implements VaadinServiceInitListener {
    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiEvent -> mountOnUi(uiEvent.getUI()));
    }

    private void mountOnUi(UI ui) {
        ui.addAfterNavigationListener(afterNavigation -> refreshSurfaceVisibility(ui));
        tryMountHeaderButton(ui);
    }
}
```

`ServiceInitEvent` itself does not expose `addUIInitListener`, but `event.getSource()` returns `VaadinService`, and `VaadinService` exposes `addUIInitListener(UIInitListener)`. [VERIFIED: `javap` local Gradle classpath]

### Pattern 3: Singleton Settings Entity With Text-Backed Enum Set

**What:** Store `defaultSurface` as the enum id string and `enabledSurfaces` as deterministic text, then expose typed helpers. [VERIFIED: Jmix enum docs; VERIFIED: Jmix entities docs]

**When to use:** Use this for two known surfaces where queryability is not needed. [VERIFIED: 12-CONTEXT.md]

**Example:**

```java
// Source: Jmix EnumClass docs; adapted for a multi-value text field.
@Column(name = "DEFAULT_SURFACE", nullable = false, length = 32)
private String defaultSurface;

@Column(name = "ENABLED_SURFACES", nullable = false, length = 128)
private String enabledSurfaces;

public AiChatSurface getDefaultSurface() {
    return AiChatSurface.fromId(defaultSurface);
}

public void setDefaultSurface(AiChatSurface surface) {
    this.defaultSurface = surface == null ? null : surface.getId();
}
```

Jmix enum docs show storing the identifier type in the entity field and converting in getters/setters; Jmix entity docs state enum collections are not supported as element collections. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/enumerations.html; CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/entities.html]

### Pattern 4: DialogWindow for the Header Button Surface

**What:** Open `ChatDialogView` with `DialogWindows.view(...).build()`, then set modal/position/size/draggable/resizable on the returned `DialogWindow`. [VERIFIED: 12-CONTEXT.md; VERIFIED: `javap` local Gradle classpath for `AbstractDialogWindow`; CITED: Jmix dialogs docs]

**When to use:** Use for `HEADER_BUTTON` only. [VERIFIED: 12-CONTEXT.md]

**Example:**

```java
// Source: Jmix DialogWindows docs + local DialogWindow bytecode verification.
DialogWindow<ChatDialogView> dialogWindow = dialogWindows
        .view(currentView, ChatDialogView.class)
        .build();
dialogWindow.setModal(false);
dialogWindow.setLeft("65%");
dialogWindow.setTop("5%");
dialogWindow.setWidth("35%");
dialogWindow.setHeight("75%");
dialogWindow.setResizable(true);
dialogWindow.setDraggable(true);
dialogWindow.open();
```

The local Jmix 2.8.1 `AbstractDialogWindow` class exposes `setModal`, `setLeft`, `setTop`, `setWidth`, `setHeight`, `setResizable`, and `setDraggable`. [VERIFIED: `javap` local Gradle classpath]

### Pattern 5: Backend Auto-Title After Durable Assistant Message

**What:** Publish a title-eligible event after the assistant message is persisted and the stream completes; consume it asynchronously with a post-commit-safe listener or a test-proven after-persist publisher. [VERIFIED: 12-CONTEXT.md; CITED: Spring Framework event docs]

**When to use:** Use once per conversation when the first assistant reply exists and the title is still default. [VERIFIED: 12-CONTEXT.md]

**Example:**

```java
// Source: Spring Framework transaction event docs; adapted for Phase 12.
@Async("aiAgentTitleExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onConversationTitleEligible(ConversationTitleEligibleEvent event) {
    titleService.generateTitleIfNeeded(event.conversationId());
}
```

Spring's `@TransactionalEventListener` defaults to the commit phase and supports explicit `AFTER_COMMIT`; Spring's `@Async @EventListener` runs asynchronously but exceptions are not propagated to the publisher. [CITED: https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/event.html; CITED: https://docs.spring.io/spring-framework/reference/6.2/core/beans/context-introduction.html]

### Anti-Patterns to Avoid

- **Planning the stale three-surface roadmap:** CONTEXT reduces Phase 12 to `FULL_ROUTE` + `HEADER_BUTTON`; do not create `SidebarChatComponent`, `FloatingChatLauncher`, or P-21 z-index mitigation tasks. [VERIFIED: 12-CONTEXT.md]
- **Mapping `Set<AiChatSurface>` directly as an element collection:** Jmix docs say collections of enums are not supported as element collection attributes. [CITED: Jmix entities docs]
- **Storing UI components in session state:** CONTEXT explicitly forbids storing fragment instances in `AiChatSessionState`; keep fragment lifecycle local and rederive from id + database rows. [VERIFIED: 12-CONTEXT.md]
- **Business logic in `ChatDialogView` or `AiUiSettingsView`:** AGENTS.md and the Jmix skills require service-layer business logic and view controllers for orchestration only. [VERIFIED: AGENTS.md; VERIFIED: jmix-views skill; VERIFIED: jmix-services skill]
- **Hardcoded UI text:** All labels, notifications, buttons, enum captions, and prompt headings need message keys in every locale file. [VERIFIED: AGENTS.md; VERIFIED: jmix-i18n skill]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Dialog overlay and dragging/resizing | Custom raw Vaadin floating overlay or z-index watcher | Jmix `DialogWindow` / Vaadin `Dialog` through `DialogWindows` | `DialogWindow` exposes modal, position, draggable, and resizable APIs and participates in Jmix view lifecycle. [VERIFIED: `javap` local Gradle classpath; CITED: Jmix dialogs docs] |
| View/menu authorization | Manual role strings or UI-only checks | Jmix `@EntityPolicy`, `@ViewPolicy`, `@MenuPolicy`, `AccessManager`, `UiShowViewContext` | This matches existing `AiAgentAdminRole` and `AdminViewAccessTest`; Jmix applies registered constraints consistently. [VERIFIED: `AiAgentAdminRole.java`; VERIFIED: `AdminViewAccessTest.java`; CITED: Jmix security docs] |
| System-internal settings/title writes | `runWithSystem`, direct JDBC, or `EntityManager` | `UnconstrainedDataManager` | Project policy says system user is still policy-gated under `jmix-security-data`; `UnconstrainedDataManager` bypasses policies for trusted internal code. [VERIFIED: AGENTS.md; CITED: Jmix DataManager docs] |
| Conversation memory continuity | Browser local storage, Vaadin session message cache, or per-surface memory | Existing `AiConversation` / `AiMessage` rows and Spring AI JDBC memory | `ChatPanelFragment.setConversationId` already reloads rows by conversation id, and `ChatService` already uses JDBC chat memory. [VERIFIED: `ChatPanelFragment.java`; VERIFIED: `DefaultChatServiceImpl.java`; VERIFIED: ai-agent/ai-agent/ai-agent.gradle] |
| Enum localization and UI options | Plain Java enum names or hardcoded labels | Jmix `EnumClass<String>` plus message bundle keys | Jmix enum integration expects `getId`/`fromId` and message bundles for display names. [CITED: Jmix enumeration docs; VERIFIED: jmix-enums skill] |
| Async title execution | New threads or blocking UI submit path | Spring events, `@Async`, bounded `ThreadPoolTaskExecutor` | Spring provides async event semantics; project already has `@EnableAsync` in `AIConfiguration`. [CITED: Spring Framework event docs; VERIFIED: `AIConfiguration.java`] |
| UI integration testing harness | Browser-only manual test as the only gate | Jmix `@UiTest` where executable, plus focused service/state tests | Jmix docs support opening views and simulating component interactions in UI tests. [CITED: Jmix UI integration test docs] |

**Key insight:** This phase is integration glue around mature Jmix/Vaadin/Spring primitives; custom surface frameworks, caches, or security layers would create drift from the existing add-on architecture. [VERIFIED: AGENTS.md; VERIFIED: 12-CONTEXT.md]

## Common Pitfalls

### Pitfall 1: Reintroducing Sidebar/Floating Work From The Roadmap

**What goes wrong:** The planner schedules `SidebarChatComponent`, raw bottom-right launcher, or z-index mitigation tasks. [VERIFIED: .planning/REQUIREMENTS.md; VERIFIED: 12-CONTEXT.md]
**Why it happens:** `ROADMAP.md` and `REQUIREMENTS.md` still contain the older three-surface success criteria. [VERIFIED: `gsd-sdk query roadmap.get-phase "12"`; VERIFIED: .planning/REQUIREMENTS.md]
**How to avoid:** Make Plan 12-01 update roadmap/requirements to the two-surface shape before implementation tasks. [VERIFIED: 12-CONTEXT.md]
**Warning signs:** Any task mentions `SIDEBAR`, `FLOATING`, `slot="drawer-end"`, bottom-right launcher, or P-21. [VERIFIED: 12-CONTEXT.md]

### Pitfall 2: Unsupported Enum Collection Mapping

**What goes wrong:** `Set<AiChatSurface>` is modeled as a JPA `@ElementCollection`, then Jmix metadata/UI binding fails or behaves inconsistently. [CITED: Jmix entities docs]
**Why it happens:** Generic JPA supports element collections, but Jmix docs state collections of enums are not supported as element collection attributes. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/entities.html]
**How to avoid:** Use a text column with deterministic enum ids or a separate entity/table. [VERIFIED: jmix-enums skill; CITED: Jmix enumeration docs]
**Warning signs:** Changelog has a join table for a raw enum collection, or entity field is `Set<AiChatSurface>` with JPA collection annotations. [VERIFIED: jmix-entities skill]

### Pitfall 3: UIInit Before AppLayout Is Discoverable

**What goes wrong:** `ChatSurfaceMounter` runs on `UIInitEvent`, cannot find `StandardMainView` / `AppLayout`, and never mounts the header button. [VERIFIED: 12-CONTEXT.md]
**Why it happens:** API availability is verified, but attach/navigation ordering relative to the host main view is not proven by docs. [VERIFIED: `javap` local Gradle classpath; MEDIUM confidence]
**How to avoid:** Implement one-shot first `AfterNavigationEvent` fallback and cover both immediate and fallback paths in tests. [VERIFIED: 12-CONTEXT.md; VERIFIED: `javap` local Gradle classpath for `UI.addAfterNavigationListener`]
**Warning signs:** The mounter logs a WARN on every navigation or mounts duplicate buttons after route changes. [VERIFIED: 12-CONTEXT.md]

### Pitfall 4: Clearing Conversation State On Dialog Close

**What goes wrong:** Closing the dialog clears `AiChatSessionState.currentConversationId`, so reopening starts a new row and TEST-14 fails. [VERIFIED: 12-CONTEXT.md]
**Why it happens:** Developers conflate dialog lifecycle with conversation lifecycle. [VERIFIED: 12-CONTEXT.md]
**How to avoid:** Closing only clears or nulls `AiChatUIState.dialogInstance`; session conversation id survives until explicit New Chat. [VERIFIED: 12-CONTEXT.md]
**Warning signs:** Tests find more than one `AiConversation` after route-to-dialog switching. [VERIFIED: 12-CONTEXT.md]

### Pitfall 5: Dual Mounted Chat Fragments In One Tab

**What goes wrong:** Route and dialog both stream/respond at the same time, confusing cancellation and state updates. [VERIFIED: 12-CONTEXT.md]
**Why it happens:** Header button remains visible on the full chat route. [VERIFIED: 12-CONTEXT.md]
**How to avoid:** Hide the header button when current route is `AiAgent_Chat`; re-show it after navigation away. [VERIFIED: 12-CONTEXT.md]
**Warning signs:** Two `ChatPanelFragment` instances attach in the same UI tab while streaming. [VERIFIED: 12-CONTEXT.md]

### Pitfall 6: Title Generation Before Commit Or With Full Chat Advisors

**What goes wrong:** The title service reads no assistant message, clobbers a user-edited title, or calls tools/RAG/memory during a title-only model call. [VERIFIED: 12-CONTEXT.md]
**Why it happens:** The event is published before durable persistence, or the service reuses the main chat prompt configuration unfiltered. [VERIFIED: 12-CONTEXT.md; CITED: Spring Framework transaction event docs]
**How to avoid:** Trigger after assistant persistence/stream completion, use post-commit semantics where applicable, clone/configure a title-only `ChatClient.Builder`, reload before save, and skip if title changed. [VERIFIED: 12-CONTEXT.md; VERIFIED: `D:/DTH/jmix-crm/.../AiConversationTitleService.java`; CITED: Spring AI docs]
**Warning signs:** Title tests need sleeps to observe committed rows, or audit shows RAG/tool events during title generation. [VERIFIED: 12-CONTEXT.md]

### Pitfall 7: New UI Code Uses Spring `MessageSource`

**What goes wrong:** New notifications/dialog captions produce nullability warnings or miss Jmix locale behavior. [VERIFIED: AGENTS.md]
**Why it happens:** Existing `ChatView` and `ChatPanelFragment` currently inject Spring `MessageSource`, so copy/paste spreads the older pattern. [VERIFIED: `ChatView.java`; VERIFIED: `ChatPanelFragment.java`]
**How to avoid:** Use `io.jmix.core.Messages` or `MessageBundle` in new/modified Jmix UI code, and keep keys in root bundles. [VERIFIED: AGENTS.md; CITED: Jmix message bundle docs]
**Warning signs:** New code imports `org.springframework.context.MessageSource`. [VERIFIED: repository grep]

### Pitfall 8: Raw JPQL Count Against `agentstore` Without `.store("agentstore")`

**What goes wrong:** Title eligibility counts return empty/wrong results because raw JPQL routes to the main store. [VERIFIED: AGENTS.md]
**Why it happens:** Fluent `dataManager.load(EntityClass)` resolves the store, but `loadValue/loadValues` raw JPQL needs explicit `.store(...)`. [VERIFIED: AGENTS.md; VERIFIED: .planning/STATE.md]
**How to avoid:** Prefer `load(AiMessage.class)` where possible; otherwise call `.store("agentstore")` on raw aggregate queries. [VERIFIED: AGENTS.md]
**Warning signs:** `loadValue("select count... ai_AiMessage", Long.class)` without `.store("agentstore")`. [VERIFIED: AGENTS.md]

## Code Examples

Verified patterns from official sources and local code:

### Jmix EnumClass Shape

```java
// Source: Jmix enumeration docs.
public enum AiChatSurface implements EnumClass<String> {
    FULL_ROUTE("full_route"),
    HEADER_BUTTON("header_button");

    private final String id;

    AiChatSurface(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static AiChatSurface fromId(String id) {
        for (AiChatSurface surface : values()) {
            if (surface.getId().equals(id)) {
                return surface;
            }
        }
        return null;
    }
}
```

Jmix docs show `EnumClass<String>` with `getId()` and nullable `fromId(...)`; message bundles should define localized enum captions. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/enumerations.html; VERIFIED: jmix-enums skill]

### Permission-Gated Header Button Visibility

```java
// Source: Jmix security docs + existing AdminViewAccessTest pattern.
UiShowViewContext context = new UiShowViewContext("AiAgent_ChatDialog");
accessManager.applyRegisteredConstraints(context);
chatButton.setVisible(context.isPermitted());
```

`UiShowViewContext` is the documented Jmix access context for checking whether a UI view can be shown. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/security/authorization.html; VERIFIED: `AdminViewAccessTest.java`]

### Non-Modal Jmix DialogWindow

```java
// Source: Jmix dialogs docs + local Jmix 2.8.1 bytecode.
DialogWindow<ChatDialogView> window = dialogWindows
        .view(parentView, ChatDialogView.class)
        .build();
window.setModal(false);
window.setLeft("65%");
window.setTop("5%");
window.setWidth("35%");
window.setHeight("75%");
window.setResizable(true);
window.setDraggable(true);
window.open();
```

`DialogWindow` in local Jmix 2.8.1 exposes these methods through `AbstractDialogWindow`; jmix-crm uses the same size/position pattern for its chat button. [VERIFIED: `javap` local Gradle classpath; VERIFIED: `D:/DTH/jmix-crm/src/main/java/com/company/crm/view/main/MainView.java`]

### Title-Only Spring AI Call

```java
// Source: Spring AI runtime options docs + jmix-crm reference.
ChatClient titleClient = chatClientBuilder.clone()
        .defaultSystem(systemPrompt)
        .defaultOptions(OpenAiChatOptions.builder()
                .model(properties.modelId())
                .temperature(0.0)
                .maxTokens(32)
                .build())
        .build();

String title = titleClient.prompt()
        .user(conversationSnippet)
        .call()
        .content();
```

Spring AI docs show runtime/per-request chat options for model, temperature, and token limits; the jmix-crm reference uses a cloned builder and separate title prompt. [CITED: https://docs.spring.io/spring-ai/reference/api/chat/prompt-engineering-patterns.html; VERIFIED: `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/AiConversationTitleService.java`]

### UI Integration Test Shape

```java
// Source: Jmix UI integration test docs.
@UiTest
@SpringBootTest(classes = {TestApplication.class, FlowuiTestAssistConfiguration.class})
class ChatPanelFragmentSurfaceSwitchTest {
    @Autowired ViewNavigators viewNavigators;
    @Autowired DataManager dataManager;

    @Test
    void routeThenDialogKeepsOneConversation() {
        // navigate to AiAgent_Chat, send via stub, open ChatDialogView, assert same id and rows
    }
}
```

Jmix docs specify `@UiTest` plus `@SpringBootTest(..., FlowuiTestAssistConfiguration.class)` for UI integration tests. [CITED: https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/testing/ui-integration-tests.html]

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Three surfaces: full route, sidebar, floating launcher. [VERIFIED: .planning/REQUIREMENTS.md] | Two surfaces: full route plus header-button dialog. [VERIFIED: 12-CONTEXT.md] | Phase 12 discuss context gathered 2026-04-30. [VERIFIED: 12-CONTEXT.md] | Planner must amend roadmap/requirements before implementation and avoid obsolete sidebar/floating tasks. [VERIFIED: 12-CONTEXT.md] |
| Raw Vaadin bottom-right `Dialog` with z-index mitigation. [VERIFIED: .planning/REQUIREMENTS.md] | Jmix `DialogWindow` non-modal top-right opened from navbar. [VERIFIED: 12-CONTEXT.md] | Phase 12 context. [VERIFIED: 12-CONTEXT.md] | Avoids custom overlay stacking work and keeps Jmix lifecycle integration. [VERIFIED: 12-CONTEXT.md; VERIFIED: `javap` local Gradle classpath] |
| Java 17 stated in pasted AGENTS stack. [VERIFIED: AGENTS.md] | Java 21 toolchain/release in executable Gradle files. [VERIFIED: ai-agent/build.gradle; VERIFIED: jmix-app/build.gradle] | Project docs note the Java 17-to-21 correction. [VERIFIED: ai-agent/README.md; VERIFIED: CHANGELOG.md] | Planner should target Java 21-compatible source and run Gradle wrapper, not system `java` from PATH. [VERIFIED: environment audit] |
| `@MockBean` in older Spring Boot tests. [ASSUMED] | `@MockitoBean` in project tests and jmix-testing skill. [VERIFIED: repository grep; VERIFIED: jmix-testing skill] | Project state records Phase 11 test preference. [VERIFIED: .planning/STATE.md] | New tests should use `@MockitoBean`. [VERIFIED: .planning/STATE.md] |
| Copying `MessageSource` from existing chat UI. [VERIFIED: `ChatView.java`; VERIFIED: `ChatPanelFragment.java`] | New UI code uses Jmix `Messages` / `MessageBundle`. [VERIFIED: AGENTS.md; CITED: Jmix message bundle docs] | Project convention from AGENTS.md. [VERIFIED: AGENTS.md] | Do not spread Spring `MessageSource` in new Phase 12 UI code. [VERIFIED: AGENTS.md] |

**Deprecated/outdated:**

- The roadmap's `SIDEBAR` and `FLOATING` surface IDs are outdated for Phase 12 planning because CONTEXT locks `FULL_ROUTE` and `HEADER_BUTTON`. [VERIFIED: 12-CONTEXT.md; VERIFIED: .planning/REQUIREMENTS.md]
- Direct enum element collections are not a viable Jmix entity pattern because Jmix docs state enum collections are not supported as element collection attributes. [CITED: Jmix entities docs]
- Raw Vaadin floating dialog and custom overlay stacking are out of scope because CONTEXT selected Jmix `DialogWindow`. [VERIFIED: 12-CONTEXT.md]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `@MockBean` is characterized as the "older Spring Boot tests" approach. [ASSUMED] | State of the Art | Low; project-specific rule to use `@MockitoBean` is verified, so implementation guidance is unaffected. |
| A2 | Research validity is estimated through 2026-06-01. [ASSUMED] | Metadata | Low; planner should re-run version/docs checks if dependency versions change before execution. |

All other implementation-critical claims in this research were verified from project files, installed skill files, Context7/official docs, Gradle dependency output, local bytecode inspection, or the jmix-crm reference. [VERIFIED: sources listed below]

## Open Questions

1. **Exact attach timing for UIInit vs AppLayout availability.** [MEDIUM confidence]
   - What we know: `VaadinService.addUIInitListener`, `UIInitEvent.getUI`, `UI.addAfterNavigationListener`, and `AppLayout.addToNavbar` exist in the resolved Vaadin classes. [VERIFIED: `javap` local Gradle classpath]
   - What's unclear: Official docs did not prove the host `StandardMainView`/`AppLayout` is attached when `UIInitEvent` fires in this Jmix app. [VERIFIED: Context7 `/vaadin/flow` query did not provide attach-order evidence]
   - Recommendation: Plan a test/fallback task for one-shot first `AfterNavigationEvent` mounting. [VERIFIED: 12-CONTEXT.md]

2. **Where TEST-14 should live.** [MEDIUM confidence]
   - What we know: Jmix supports `@UiTest` with `FlowuiTestAssistConfiguration`, and `jmix-app` has an existing UI test dependency. [CITED: Jmix UI integration test docs; VERIFIED: jmix-app/build.gradle; VERIFIED: `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java`]
   - What's unclear: The add-on module has historical comments saying there was no add-on `@UiTest` harness for previous chat tests. [VERIFIED: `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java`]
   - Recommendation: Planner should first attempt an add-on `@UiTest`; if bootstrapping is brittle, put TEST-14 in the host `jmix-app` harness or split into service/state tests plus a narrower UI smoke. [VERIFIED: jmix-testing skill]

3. **Title-client advisor/tool isolation needs an executable assertion.** [MEDIUM confidence]
   - What we know: Spring AI supports runtime chat options, and `ChatClient.Builder.clone()`, `defaultOptions`, `defaultAdvisors`, and `defaultToolCallbacks` are present in the local Spring AI 1.1.4 classes. [CITED: Spring AI docs; VERIFIED: `javap` local Gradle classpath for Spring AI]
   - What's unclear: Whether the final title-service builder construction accidentally inherits default advisors/tools from the main builder depends on how the project wires the builder. [VERIFIED: `DefaultChatServiceImpl.java`; VERIFIED: `ChatClientFactory` reference in code comments]
   - Recommendation: Add a title-service test that verifies no tool callbacks/advisors/memory are invoked during title generation. [VERIFIED: 12-CONTEXT.md]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Gradle wrapper | Build and tests | Yes | 8.14.4 | None needed. [VERIFIED: `./gradlew.bat --version`] |
| Java via `JAVA_HOME` | Gradle launcher / toolchain host | Yes | Temurin 25.0.2; build targets release 21 | Use `JAVA_HOME\bin\java.exe` or Gradle wrapper; PATH `java` is unreliable. [VERIFIED: environment audit; VERIFIED: ai-agent/build.gradle] |
| Java via PATH | Direct shell Java commands | Partially | First PATH hit is `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe` and exits without version text | Do not rely on bare `java`; use Gradle wrapper or `JAVA_HOME`. [VERIFIED: `where.exe java`; VERIFIED: `java -version` exit 1] |
| Docker | Optional Testcontainers/RAG integration tests | Yes | 28.0.4 | Default Phase 12 tests can use HSQLDB; Docker only if planner chooses tagged integration coverage. [VERIFIED: `docker --version`; VERIFIED: ai-agent/ai-agent/ai-agent.gradle] |
| psql CLI | Manual PostgreSQL inspection | No | Not installed | Not blocking; Phase 12 does not require manual psql and default tests use HSQLDB. [VERIFIED: `psql --version` failed; VERIFIED: ai-agent/ai-agent/ai-agent.gradle] |
| gsd-sdk | GSD init/commit helpers | Yes | On PATH at `C:\nvm4w\nodejs` and `C:\Users\admin\bin` | None needed. [VERIFIED: `where.exe gsd-sdk`] |

**Missing dependencies with no fallback:** None for research/planning. [VERIFIED: environment audit]

**Missing dependencies with fallback:**
- `psql` is missing; use Gradle/HSQLDB tests or DataManager-based tests instead. [VERIFIED: environment audit; VERIFIED: ai-agent/ai-agent/ai-agent.gradle]
- Bare PATH `java` is unreliable; use `JAVA_HOME` or Gradle wrapper. [VERIFIED: environment audit]

## Security Domain

`security_enforcement` is absent from `.planning/config.json`, so this section is required. [VERIFIED: .planning/config.json]

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | No new authentication in this phase | Reuse Jmix current user/session; do not create auth flows. [VERIFIED: 12-CONTEXT.md; VERIFIED: AGENTS.md] |
| V3 Session Management | Yes | Use Vaadin `@VaadinSessionScope` for active conversation id and `@UIScope` for per-tab dialog handle. [VERIFIED: 12-CONTEXT.md; CITED: Vaadin Spring scopes docs] |
| V4 Access Control | Yes | Jmix `@EntityPolicy`, `@ViewPolicy`, `@MenuPolicy`, `AccessManager`, and `UiShowViewContext`. [VERIFIED: `AiAgentAdminRole.java`; CITED: Jmix security docs] |
| V5 Input Validation | Yes | Sanitize generated titles, reject blank/default sentinel, validate settings defaults, and use enum parsing through `fromId`. [VERIFIED: 12-CONTEXT.md; VERIFIED: jmix-enums skill] |
| V6 Cryptography | No new cryptography | Do not add custom crypto; no Phase 12 requirement needs it. [VERIFIED: 12-CONTEXT.md] |
| V8 Data Protection | Yes | Do not expose `AiUiSettings` to LLM tool schema; add it to `AiInternalEntityNames`. [VERIFIED: 12-CONTEXT.md; VERIFIED: `AiInternalEntityNames.java`] |
| V10 Malicious Code / LLM Prompt Injection Boundary | Yes | Title prompt must not reuse tools/advisors/RAG/memory and must fail silently with audit. [VERIFIED: 12-CONTEXT.md] |

### Known Threat Patterns for Jmix/Vaadin/Spring AI Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Non-admin toggles chat surfaces by hitting admin view directly | Elevation of privilege | Add `AiUiSettings` entity policy and `AiAgent_AiUiSettings.detail` / `aiAgent.uiSettings` policies; test with `UiShowViewContext`. [VERIFIED: `AiAgentAdminRole.java`; VERIFIED: `AdminViewAccessTest.java`; CITED: Jmix security docs] |
| Hidden menu but route still reachable | Elevation of privilege / Tampering | Gate `ChatView.onBeforeEnter` when `FULL_ROUTE` disabled and check `ChatDialogView` permission before showing header button. [VERIFIED: 12-CONTEXT.md] |
| Cross-user conversation probing via `conversationId` | Information disclosure | Preserve `ConversationGateway` ownership opacity query and do not bypass it from fragments. [VERIFIED: `ConversationGateway.java`; VERIFIED: `ChatPanelFragment.java`] |
| Title generation clobbers user-edited title | Tampering | Reload conversation before auto-title save and skip if title is no longer default. [VERIFIED: 12-CONTEXT.md; VERIFIED: jmix-crm title service reference] |
| Title model leaks tools/RAG or writes audit as normal chat tool call | Information disclosure / Repudiation | Use separate title prompt/options, no tools/advisors, and audit `eventName="conversation_title"` with sanitized arguments/result. [VERIFIED: 12-CONTEXT.md] |
| UI listener leak after fragment detach | Denial of service / Tampering | Subscribe in `onAttach`, unregister in `onDetach`, and guard `UI.access` with owner UI presence. [VERIFIED: 12-CONTEXT.md; VERIFIED: `ChatPanelFragment.java`] |
| Settings entity appears in LLM entity inventory | Information disclosure | Add `AiUiSettings` to `AiInternalEntityNames` always-excluded set. [VERIFIED: 12-CONTEXT.md; VERIFIED: `AiInternalEntityNames.java`] |

## Sources

### Primary (HIGH confidence)

- `/jmix-framework/jmix-context7` - fragments, dialogs, DataManager/UnconstrainedDataManager, enums, entity limitations, security, i18n, UI tests. [VERIFIED: Context7]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/flow-ui/fragments/using-fragments.html` - fragment declaration and XML embedding. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/flow-ui/dialogs.html` - Jmix dialog configuration and `DialogWindows`. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/enumerations.html` - `EnumClass`, id storage, `fromId`. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-model/entities.html` - unsupported enum element collections. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/data-access/data-manager.html` - `UnconstrainedDataManager` bypasses security policies. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/security/authorization.html` - role policies and `UiShowViewContext`. [CITED]
- `https://github.com/jmix-framework/jmix-context7/blob/main/content/docs/testing/ui-integration-tests.html` - `@UiTest` / `FlowuiTestAssistConfiguration`. [CITED]
- `/vaadin/flow` - AppLayout and navigation examples; sparse for service init. [VERIFIED: Context7]
- `https://vaadin.com/docs/latest/flow/advanced/service-init-listener` - service init listener source checked for UI listener registration context. [CITED]
- `https://vaadin.com/docs/latest/flow/integrations/spring/scopes` - Vaadin Spring UI/session scope semantics. [CITED]
- `/websites/spring_io_spring-ai_reference` - runtime chat options. [VERIFIED: Context7]
- `https://docs.spring.io/spring-ai/reference/api/chat/prompt-engineering-patterns.html` - ChatClient prompt/options example. [CITED]
- `/websites/spring_io_spring-framework_reference_6_2` - async events and transaction-bound events. [VERIFIED: Context7]
- `https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/event.html` - `@TransactionalEventListener` phases. [CITED]
- `https://docs.spring.io/spring-framework/reference/6.2/core/beans/context-introduction.html` - `@Async @EventListener` semantics. [CITED]
- Local Gradle dependency reports for runtime/test versions. [VERIFIED: `./gradlew.bat :ai-agent:dependencies`]
- Local bytecode inspection for Vaadin/Jmix/Spring AI APIs. [VERIFIED: `javap` on Vaadin/Jmix/Spring AI jars in Gradle cache]
- `.planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md` - locked scope and decisions. [VERIFIED]
- `.planning/REQUIREMENTS.md` and `.planning/STATE.md` - stale requirement mapping and prior project decisions. [VERIFIED]
- `AGENTS.md` and installed Jmix skill files - project constraints and Jmix implementation patterns. [VERIFIED]

### Secondary (MEDIUM confidence)

- `D:/DTH/jmix-crm` reference implementation - header chat button, `DialogWindow` sizing, pencil-edit title pattern, title generation service/listener. [VERIFIED: local reference files]
- Existing add-on tests and code comments for pragmatic add-on UI-test limitations. [VERIFIED: repository grep and file reads]

### Tertiary (LOW confidence)

- A1 in Assumptions Log: "older Spring Boot tests" wording for `@MockBean`. [ASSUMED]
- A2 in Assumptions Log: research validity estimate. [ASSUMED]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - versions are pinned in Gradle files and verified by dependency reports. [VERIFIED: ai-agent/build.gradle; VERIFIED: Gradle dependency report]
- Architecture: HIGH - phase scope is locked in CONTEXT and existing code already exposes the fragment/service/conversation substrate. [VERIFIED: 12-CONTEXT.md; VERIFIED: `ChatPanelFragment.java`; VERIFIED: `DefaultChatServiceImpl.java`]
- Jmix patterns: HIGH - Context7 docs and installed Jmix skills align. [VERIFIED: Context7 `/jmix-framework/jmix-context7`; VERIFIED: jmix skills]
- Vaadin UI init timing: MEDIUM - APIs are verified, but exact `UIInitEvent` vs `AppLayout` attach timing must be proven by test/fallback. [VERIFIED: `javap` local Gradle classpath]
- Auto-title Spring AI pattern: MEDIUM-HIGH - Spring option semantics and jmix-crm pattern are verified; final advisor/tool isolation must be covered by tests. [CITED: Spring AI docs; VERIFIED: jmix-crm title service]
- Pitfalls: HIGH - most are direct contradictions between CONTEXT and stale requirements or documented Jmix constraints. [VERIFIED: 12-CONTEXT.md; VERIFIED: .planning/REQUIREMENTS.md; CITED: Jmix docs]

**Research date:** 2026-05-02 [VERIFIED: environment current_date]
**Valid until:** 2026-06-01 for repo-pinned stack assumptions; re-check Context7/Gradle if Jmix, Vaadin, Spring AI, or Spring Boot versions change. [ASSUMED]
