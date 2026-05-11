# Phase 15: Right-Sidebar Chat Surface & Observability UX - Research

**Researched:** 2026-05-11
**Domain:** Jmix 2.8 / Vaadin Flow 24 UI — chat surface mounting, Spring AI streaming flux observability, in-app activity rendering, leak-test reuse
**Confidence:** HIGH (codebase verified by direct read; Jmix component facts confirmed via Context7)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Skip Jmix `sidePanelLayout`. `ChatSurfaceMounter` appends a `position: fixed` docked `Div`/wrapper hosting the **shared** `ChatPanelFragment` (`right: 0; top: <navbar-h>; bottom: 0; width: ~32% / min ~420px`) onto a stable host (the `UI` element or AppLayout content), and toggles a CSS push class on the work-area / AppLayout content that sets `padding-right`/`margin-right`. A small-device media query (~768px) collapses the panel to a full-screen overlay. In-panel closer = plain close `JmixButton` in the panel header. Non-modal (main view stays clickable); single shared `ChatPanelFragment` (no second `ChatService`, no second chat memory, no duplicate fragment); `SIDEBAR` participates in `AiUiSettings.enabledSurfaceIds`/`defaultSurface` and the admin enabled-surface control; with `SIDEBAR` disabled, no panel and no toggle are mounted (absent, not greyed); `ChatPanelFragment` slot ids unchanged.
- **D-02:** Distinct far-right navbar toggle `JmixButton` built via the same pipeline as `aiAgentHeaderChatButton`: `uiComponents.create(JmixButton.class)` → `setId(...)` (id distinct from `aiAgentHeaderChatButton`) → add a CSS class → `setIcon(VaadinIcon.PANEL.create())` → `addThemeVariants(LUMO_TERTIARY, LUMO_ICON)` → `aria-label` from `msg://` (distinct open/closed labels) → click listener that toggles the panel.
- **D-03:** Open/closed state via `aria-pressed` (`true`/`false`) + a small project "active" CSS class on the toggle. Both the navbar toggle and the in-panel closer route through **one** toggle method. When both `SIDEBAR` and `HEADER_BUTTON` enabled, both navbar buttons present and operate independently.
- **D-04:** Panel starts CLOSED. `defaultSurface=SIDEBAR` means only "the sidebar toggle is mounted" — direct analogue of `HEADER_BUTTON` ⇒ button present, dialog closed. No auto-open on navigation/login. Once-per-session auto-open and `localStorage`-persisted state are deferred.
- **D-05:** New ADDITIVE `StreamingEvent.Activity(ActivityKind kind)` variant; `ActivityKind` closed enum `{CHAT, TOOL, RETRIEVAL}`. `AuditingDocumentRetriever.retrieve(...)` emits `Activity(RETRIEVAL)` at retrieval start via the existing streaming sink holder; the tool-callback audit decorator emits `Activity(TOOL)`; stream assembly emits `Activity(CHAT)` before the model stream. Emit sites are OUTSIDE `ChatService`/the advisor chain/the persisted `AiAuditEvent` shape. Adding the variant is additive: one new `permits` entry + one new arm in every exhaustive `switch`.
- **D-06:** Status line = a `Span` with a CSS three-dot/pulse animation, appended as a sibling `<div>`-style element inside `messageListSlot` AFTER the `<vaadin-message-list>` block (existing NOTICE-row pattern). `role="status"` + `aria-live="polite"`; guard animation with `prefers-reduced-motion`; REMOVE entirely on terminal `Final`/`Error`; KIND-keyed labels (`CHAT`→thinking…, `TOOL`→searching data…, `RETRIEVAL`→retrieving documents…, neutral before any ActivityKind); never concatenated into the assistant `MessageListItem` bubble. All strings from `msg://` keys in en + vi.
- **D-07:** Hybrid data strategy — for the in-flight/just-completed turn, accumulate a BOUNDED per-turn step list from the `StreamingEvent` flux (current/last turn only or small capped collection), cleared on `Final`/`Error` (nothing accumulates unbounded in `AiChatSessionState`); for any turn rendered after a FRESH navigation, lazily `DataManager`-query the `AiAuditEvent` TOOL/RETRIEVAL children for that one `runId` ON EXPAND with a narrow fetch plan (`kind`, `startedAt`/`latencyMs`, `outcome`) and `.store("agentstore")` if raw-JPQL `loadValues` is used. No new persisted "turn" entity, no parallel store.
- **D-08:** One Vaadin `Details` per assistant turn, `setOpened(false)`; summary = "what the agent did — N steps · total ms"; content = `VerticalLayout` of label-only step rows + per-step ms + error/rollback indicator; appended as a sibling after that turn's `MessageListItem`, anchored by `runId`; OMITTED entirely when step count == 0; component-local open state (session-only). One `kind → msg://` mapper for both live and historical paths — KIND-keyed localized labels (en + vi) only, never a tool/entity name. No `?runId=` deep-link (descoped).
- **D-09:** TEST-19 reuses the Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` pattern-pack definitions VERBATIM (do not fork) against the RENDERED TEXT of (a) the streaming-status line and (b) the per-turn disclosure, across the supported `ActivityKind` values + an errored/rolled-back step.

### Claude's Discretion
- Exact panel width / `min-width` / small-device breakpoint for D-01 (starting point ~32% / min ~420px / ~768px).
- Exact button id, CSS class names, and `msg://` key names (follow existing naming: `aiAgentHeaderChatButton`, `chatSurfaceMounter.headerButton.ariaLabel`, etc.).
- Whether `Activity(CHAT)` is emitted explicitly or "no Activity yet ⇒ neutral, first `Content` ⇒ thinking…" — D-05 only requires RETRIEVAL and TOOL be explicitly signalled.
- Exact shape of the bounded live-turn step accumulator (capped list vs current-turn-only) — D-07 only requires "bounded, cleared on terminal event".

### Deferred Ideas (OUT OF SCOPE)
- Chat-state side panel (active model / conversation id+title / LLM-exposure flag / mutation-tools flag / attached-file count / token-budget usage / last-turn summary) — `OBS-FUT-01`. Do not implement.
- `AiAuditEventListView?runId=...` deep-link from a turn — descoped from OBS-02 for Phase 15.
- `SIDEBAR` panel auto-open behaviour (once-per-session / `localStorage`-persisted). Phase 15 ships "starts closed".
- Optional `AiAgentMainView extends StandardMainView` that wraps the work-area in `sidePanelLayout` — not built in Phase 15.
- Doc-sync follow-ups (REQUIREMENTS.md OBS-02 `?runId=` clause; ROADMAP.md criterion 3; `sidePanelLayout` references) — these are doc edits, not code.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **SURF-11** | A third `SIDEBAR` chat surface mounts the shared `ChatPanelFragment` as a non-modal right side panel at the MainView/AppLayout shell level; participates in `AiUiSettings`; distinct far-right navbar toggle + in-panel closer; persists across route navigation. | `ChatSurfaceMounter` already does the `AppLayout` tree-walk, `addToNavbar`, per-UI `MountedChatSurfaceState`, `AfterNavigationEvent` re-sync, `JmixButton` creation+theming, surface-visibility toggling off `getEnabledSurfaceSet()` — the SIDEBAR mount slots in. The fixed-position `Div` attaches to `UI.getCurrent().getElement()` so it survives the `AppLayout` content-slot replacement on navigation. `AiChatSurface` is an `EnumClass<String>` (add `SIDEBAR`). `AiUiSettings` columns are comma-joined strings — no schema change. `AiUiSettingsDetailView` enabled-surface control auto-includes a new enum value (`enabledSurfacesField.setItems(AiChatSurface.class)`). See §Architecture Patterns. |
| **OBS-01** | KIND-keyed ephemeral streaming-status line in a sibling slot of the message list; clears completely on finalize; never internal tool/entity names; all strings from `msg://`. | The existing NOTICE-row pattern (`appendNoticeRow` → plain `<div class="ai-agent-attachment-notice">` appended to `messageListSlot.getElement()`) is the exact template. The `Flux<StreamingEvent>` is already consumed in `submitChatTurn(...)` via `.doOnNext(...)`; add an `Activity` arm. `clearMessageList()` already does `messageListSlot.getElement().removeAllChildren()` on conversation switch — the status row must also be removed in `finishStreamInternal()` / on `Final`/`Error`. New `ActivityKind` enum guarantees no leak structurally. See §Code Examples. |
| **OBS-02** | Per-turn collapsed-by-default "what the agent did — N steps · total ms" disclosure: humanized KIND-keyed label-only steps + per-step timing + error/rollback indicator; hidden on zero-tool turns; session-only expand/collapse; data from the flux (live turn) and/or lazy `AiAuditEvent` re-read (history turn); no deep-link. | Vaadin `Details` is in Jmix's accepted component set (`com.vaadin.flow.component.details.Details`, `<details>` XML, `setOpened`, `setSummaryText`, theme variants). `StreamingEvent.ToolResult` already carries `toolCallId`, `toolName`, `outcome`, `payloadJson` and is emitted by `ToolCallbackAuditDecorator` — the live-turn step list is built from `ToolCall`/`ToolResult`/`Activity(RETRIEVAL)`. For history turns, `AiAuditEvent` carries `runId`, `kind` (`CHAT`/`TOOL`/`RETRIEVAL`), `startedAt`, `latencyMs`, `outcome`, `errorClass`, parent/child tree — query the TOOL/RETRIEVAL children by `runId` with `UnconstrainedDataManager` (or `DataManager` for user-attributable reads) and a narrow fetch plan. `runId` is tracked in the fragment as `activeRunId` (set from `StreamingEvent.Final.runId()`). See §Architecture Patterns + §Code Examples. |
| **OBS-04** | Driven only by the existing flux + audit tree; no new entity/table/changelog; per-turn detail bounded in `AiChatSessionState`. | `AiChatSessionState` (VaadinSessionScope) currently holds only `currentConversationId` + listeners — keep it that way; the bounded step accumulator lives in `ChatPanelFragment` (per-fragment instance, not session), cleared on `Final`/`Error`. No `@Entity`/`@Table`/`*-changelog.xml` is added. `Details` open state is component-local → session-only by construction. |
| **TEST-19** | A UI-layer leak test reuses the Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` pattern packs against the rendered text of the status line + per-turn disclosure (across `ActivityKind` values + an errored step); fails if a tool/entity name is routed through the rendering path. | `HostPrefixPatternProvider.PATTERN_KEY = "HOST_PREFIX_LEAK"` + `ToolNamePatternProvider.PATTERN_KEY = "TOOL_NAME_LEAK"`; both expose `asPattern()` returning a compiled regex (`AiAgentGuardProperties.OutputScanner.Pattern`). Existing tests `ToolNameLeakScannerTest` / `HostPrefixLeakScannerTest` show the construction pattern (`new ToolNamePatternProvider(List.of(), propsDefaults())` + `.buildPattern()` + `Pattern.compile(provider.asPattern().orElseThrow().regex())`). The new test renders the status line / `Details` (label-only mapper output) for every `ActivityKind` + an `ERROR` outcome step and asserts `compiled.matcher(renderedText).find()` is `false`; a negative-control case deliberately routes `find_records` / a host-prefixed entity name through the mapper and asserts the matcher trips. See §Validation Architecture. |
</phase_requirements>

## Summary

This is a UI extension phase on a mature, well-factored chat subsystem. Nearly every needed mechanism already exists: `ChatSurfaceMounter` is a `VaadinServiceInitListener` that walks the UI tree to find the `AppLayout`, does `addToNavbar`, holds per-UI mounted state, re-syncs on `AfterNavigationEvent`, and toggles surface visibility off `AiUiSettings.getEnabledSurfaceSet()` — the third SIDEBAR surface and its navbar toggle slot directly into that lifecycle. `ChatPanelFragment` already consumes the `Flux<StreamingEvent>` in `submitChatTurn(...).doOnNext(...)`, already appends plain `<div>` sibling rows to `messageListSlot` (the NOTICE-row pattern), and already tracks `activeRunId` from `StreamingEvent.Final`. The `AiAuditEvent` tree (CHAT root per `runId` + TOOL/RETRIEVAL children, with `kind`/`startedAt`/`latencyMs`/`outcome`/`errorClass`/parent-child) carries everything the disclosure needs. Phase 9's leak-guard providers (`HostPrefixPatternProvider`, `ToolNamePatternProvider`) expose compiled regexes via `asPattern()` and are trivially reusable in a new `@UiTest`.

The two non-trivial pieces: (1) the `position:fixed` shell wrapper for SURF-11 — there's no Jmix component for shell-level non-modal push docking around the router outlet, so D-01 sanctions a hand-rolled CSS approach; the wrapper must attach to `UI.getCurrent().getElement()` (not the `AppLayout` content slot, which `setContent()`-replaces on every navigation) and toggle a CSS push class on the `AppLayout`/work-area; the project already ships an `@CssImport`-loaded stylesheet (`META-INF/resources/frontend/styles/ai-agent-chat.css` via `@CssImport("./styles/ai-agent-chat.css")` on `MessageBubbleComponent`) — extend that file, no new theme machinery. (2) The additive `StreamingEvent.Activity(ActivityKind kind)` variant — the compiler will force a new arm in every exhaustive `switch` over the sealed interface (currently just `StreamEventRenderer.renderStreamEventDetails`), and the emit sites are `AuditingDocumentRetriever.retrieve(...)` (for `RETRIEVAL`, via `StreamingSinkHolder.currentOrForRun(runId)`), `ToolCallbackAuditDecorator.callInternal(...)` (for `TOOL`, alongside the existing `ToolCall`/`ToolResult` emits), and stream assembly in `DefaultChatServiceImpl.stream(...)` (for `CHAT`, optional per D-05).

**Primary recommendation:** Mount the SIDEBAR as a `position:fixed` `Div` on `UI.getCurrent().getElement()` managed by `ChatSurfaceMounter` (mirroring its existing `aiAgentHeaderChatButton` + `MountedChatSurfaceState` machinery) with a CSS push class on the `AppLayout`; render the status line as a NOTICE-style sibling `<div>` in `messageListSlot` and the per-turn detail as a Vaadin `<details>` sibling after the assistant `MessageListItem`, both driven by the existing flux (extended with `Activity`) and a bounded per-fragment step list, with `AiAuditEvent`-by-`runId` as the on-expand fallback for history turns; reuse Phase 9's pattern-provider regexes verbatim in the TEST-19 `@UiTest`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| SIDEBAR surface mount + navbar toggle lifecycle | Frontend Server (Vaadin Flow UI) — `ChatSurfaceMounter` `VaadinServiceInitListener` | Browser/Client (CSS push class, fixed-position layout, `prefers-reduced-motion`) | Surface mounting is server-side Flow component-tree manipulation per existing pattern; the *visual* shell docking/push is pure CSS (no Jmix component does it). |
| `SIDEBAR` enum + `AiUiSettings` persistence + admin control | Frontend Server (entity + detail view) + Database/Storage (`agentstore` string columns — no schema change) | — | Enum value threads through the comma-joined `enabledSurfaceIds`/`defaultSurface` strings; the `JmixCheckboxGroup`/`JmixRadioButtonGroup` auto-list a new enum constant. |
| `Activity(ActivityKind)` event emission | API/Backend (orchestration edge: `AuditingDocumentRetriever`, `ToolCallbackAuditDecorator`, `DefaultChatServiceImpl.stream`) | — | KIND originates server-side at the retrieval/tool/model-stream boundary; it rides the existing `StreamingSinkHolder` sink keyed by `runId`. Must NOT touch `ChatService`-proper, the advisor chain, or the persisted `AiAuditEvent` shape. |
| Streaming-status line rendering | Frontend Server (`ChatPanelFragment` — sibling `<div>` in `messageListSlot`) | Browser/Client (CSS pulse animation) | Server composes the row from the flux; CSS animates it. |
| Per-turn tool-detail disclosure | Frontend Server (`ChatPanelFragment` — Vaadin `Details` sibling after the assistant `MessageListItem`) | Database/Storage (`AiAuditEvent` lazy re-query by `runId` on expand for history turns) | Live turn from the flux (bounded in-memory list); history turn lazily from the durable audit tree — no parallel store. |
| KIND → label mapping (no-leak guarantee) | Frontend Server (one `ActivityKind`/`kind` → `msg://` mapper, label-only) | — | Closed enum + `msg://` keys = structural no-leak; the mapper never touches `toolName`/entity names. |
| TEST-19 leak assertion | (test only — `@UiTest`, server-side Flow render) | — | Reuses `HostPrefixPatternProvider`/`ToolNamePatternProvider` compiled regexes against rendered text. |

## Standard Stack

This phase introduces **no new libraries** (PROJECT.md "no new core dependencies"; D-01 explicitly accepts a small hand-rolled CSS addition to the existing project stylesheet, not a new theme/library).

### Core (all already on the classpath)
| Library / API | Version | Purpose | Why Standard |
|---------------|---------|---------|--------------|
| Jmix Flow UI | 2.8 (Vaadin Flow 24.8) | `ChatSurfaceMounter` (`VaadinServiceInitListener`), `JmixButton`, `UiComponents`, `AppLayout.addToNavbar`, `Fragment`, `Messages` | The shipped UI framework; all chat surfaces already built on it. `[VERIFIED: codebase — ChatSurfaceMounter.java imports io.jmix.flowui.*]` |
| Vaadin `Details` component | bundled with Jmix 2.8 flow-ui | Per-turn collapsible disclosure (D-08) | In Jmix's accepted Vaadin set — `<details>` XML element, theme variants `filled`/`reverse`/`small`, `setOpened`/`setSummaryText`. `[CITED: Context7 jmix-framework/jmix-context7 — "Apply Theme Variants to Details Component"]` |
| Vaadin `MessageList` / `MessageListItem` | bundled | Existing chat turn substrate (Phase 7.1/13.1); the `Details` and status `<div>` are siblings of `<vaadin-message-list>` inside `messageListSlot` | Already in `ChatPanelFragment`. `[VERIFIED: codebase]` |
| Spring AI `Sinks.Many<StreamingEvent>` (reactor) | per Spring AI 1.1.x BOM | Carries the new `Activity` event; `StreamingSinkHolder.currentOrForRun(runId)` is the existing emit hook | Existing streaming transport (Phase 7). `[VERIFIED: codebase — StreamingSinkHolder.java]` |
| `@CssImport("./styles/ai-agent-chat.css")` on a packaged component | Vaadin 24 frontend scanner | The project's existing stylesheet at `ai-agent/.../src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css` — extend it for the side-panel shell, push class, status-line pulse animation, toggle-active class | The established project convention ("Rule: edit this file — NOT the Java components — when tweaking visuals"). `[VERIFIED: codebase — MessageBubbleComponent.java @CssImport + ai-agent-chat.css header comment]` |

### Supporting (existing, reused)
| Component | Purpose | When to Use |
|-----------|---------|-------------|
| `HostPrefixPatternProvider` / `ToolNamePatternProvider` | Phase 9 leak-guard regex providers; `asPattern()` → `AiAgentGuardProperties.OutputScanner.Pattern(key, regex)` | TEST-19 — construct with `new ToolNamePatternProvider(List.of(), new AiAgentGuardProperties(null,null,null,null))`, call `.buildPattern()`, `Pattern.compile(provider.asPattern().orElseThrow().regex())`. |
| `AuditWriter` (`UnconstrainedDataManager`, `.store("agentstore")` for raw-JPQL `loadValue`) | Existing `AiAuditEvent` write path; shows the `agentstore`-store query convention for raw JPQL | Model the on-expand history-turn read after `AuditWriter`'s `dataManager.load(AiAuditEvent.class)...` (typed loads infer the store from `@Store(name="agentstore")`; only raw-JPQL `loadValue/loadValues` need `.store("agentstore")` — project memory `feedback_jmix_loadvalue_store.md`). |
| `MountedChatSurfaceState` (private record inside `ChatSurfaceMounter`, `ComponentUtil.getData(ui, ...)`) | Per-UI bookkeeping of the mounted chat button | Add `sidebarToggleButton` + `sidebarPanelDiv` + `sidebarOpen` fields; same `ComponentUtil`-backed per-UI lifetime. |
| `AiChatUIState` (`@UIScope`) | Currently holds the modeless `DialogWindow`; analogue for SIDEBAR | Could hold a reference to the mounted side-panel `Div` if a UI-scoped handle is cleaner than `MountedChatSurfaceState`; planner picks. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `position:fixed` `Div` on `UI` element (D-01) | Jmix `sidePanelLayout` wrapping the router outlet | Jmix docs/samples only ever use `sidePanelLayout` *inside a single view over non-routed content*; `AppLayout.showRouterLayoutContent()` `setContent()`-replaces the content slot on every navigation, so a `sidePanelLayout` there would be re-wrapped/raced every `AfterNavigationEvent`. D-01 already settled this — do not relitigate; research confirms the risk is real. |
| New `StreamingEvent.Activity` variant (D-05) | Derive KIND in the UI from `Content`/`ToolCall`/`ToolResult`/`Citation` | RETRIEVAL is **not observable** from existing variants — RAG runs in `AuditingDocumentRetriever.retrieve(...)` *before* the model call, and `Citation` events arrive with/after the final answer; a `Citation→RETRIEVAL` mapping fires at the wrong end of the turn. D-05 settled this; the SPEC pre-authorizes the additive variant. |
| Vaadin `Details` (D-08) | `Accordion` / custom `<div>` | `Accordion`'s single-open semantics fight per-turn independence; a custom `<div>` re-implements the a11y `Details` already provides. D-08 settled this. |

**Installation:** none — no `build.gradle` change.

**Version verification:** Not applicable — no new packages. Jmix 2.8 / Vaadin Flow 24.8 / Spring AI 1.1.x are pinned by existing BOMs (PROJECT.md). The `Details`, `MessageList`, `AppLayout.addToNavbar` APIs are present in the bundled Vaadin 24.8 (confirmed: `ChatPanelFragment` already imports `com.vaadin.flow.component.messages.MessageList`; `ChatSurfaceMounter` already calls `appLayout.get().addToNavbar(...)`; Context7 confirms the `<details>` element + theme variants).

## Architecture Patterns

### System Architecture Diagram

```
                            ┌──────────────────────────────────────────────┐
  Vaadin UI element ────────┤  ChatSurfaceMounter (VaadinServiceInitListener)│
  (survives navigation)     │  - serviceInit → addUIInitListener             │
        │                   │  - initializeUi(ui): mountHeaderButton +       │
        │                   │      mountSidebarToggle + mountSidebarPanel    │  ◄── AiUiSettings.getEnabledSurfaceSet()
        │                   │  - addAfterNavigationListener → refresh all 3  │       (FULL_ROUTE / HEADER_BUTTON / SIDEBAR)
        │                   │  - one toggleSidebar() method (navbar + closer)│
        │                   └──────────────────────────────────────────────┘
        │                              │ mounts
        ▼                              ▼
  ┌───────────────┐          ┌────────────────────────────────────────────┐
  │  AppLayout    │          │  <div class="ai-agent-sidebar"> (fixed,     │
  │  + navbar:    │◄─push────│   right:0; top:var(--navbar-h); bottom:0;   │
  │   [magic btn] │  CSS     │   width:32%/min 420px) hosting the SHARED   │
  │   [PANEL btn]─┼─toggle──►│   ChatPanelFragment instance + close btn    │
  │  + content    │          └────────────────────────────────────────────┘
  │   (router     │                          │
  │    outlet —   │                          ▼  consumes
  │    replaced   │          ┌────────────────────────────────────────────┐
  │    each nav)  │          │  ChatPanelFragment.submitChatTurn(...)      │
  └───────────────┘          │  chatService.stream(...).doOnNext(evt → {   │
                             │    switch(evt):                             │
  ┌─────────────────┐        │     Content    → botMsg.appendText(md)      │
  │ DefaultChatSvc  │ stream │     ToolCall    → live step list (+ status) │
  │  .stream(runId) ├───────►│     ToolResult  → live step list (outcome)  │
  │  Activity(CHAT) │  Flux  │     Activity(RETRIEVAL) → status "retrieving"│
  └─────────────────┘ <Stream│     Activity(TOOL)      → status "searching" │
        ▲   ▲          Event>│     Activity(CHAT)      → status "thinking…" │
        │   │                │     Final/Error → REMOVE status row;         │
        │   │                │                   build <details> per turn  │
        │   │                │  })                                         │
        │   │                │  - bounded liveTurnSteps (cleared on Final)  │
        │   │                │  - <span class="ai-agent-status"> sibling    │
        │   │                │      in messageListSlot AFTER <message-list> │
        │   │                │  - <details opened="false"> sibling AFTER    │
        │   │                │      the assistant MessageListItem (anchor   │
        │   │                │      = runId); omitted when stepCount==0     │
        │   │                │  - on Details.expand for a HISTORY turn:     │
        │   │                │      DataManager.load(AiAuditEvent)          │
        │   │                │        .query("...where e.runId=:rid and     │
        │   │                │         e.parent is not null")  ◄── agentstore
        │   │                │        narrow fetch plan: kind, startedAt,   │
        │   │                │        latencyMs, outcome, errorClass        │
        │   │                └────────────────────────────────────────────┘
        │   │
   ┌────┴───┴──────────────┐   ┌────────────────────────────────┐
   │ ToolCallbackAuditDecor.│   │ AuditingDocumentRetriever      │
   │  callInternal(...):    │   │  retrieve(query):              │
   │   emit ToolCall        │   │   sink.currentOrForRun(runId)  │
   │   + Activity(TOOL)     │   │     .tryEmitNext(              │
   │   ... delegate.call    │   │       Activity(RETRIEVAL))     │
   │   emit ToolResult      │   │   ... delegate.retrieve        │
   └────────────────────────┘   │   (writeRetrieval audit row)   │
            │                   └────────────────────────────────┘
            ▼ (existing, unchanged)
   AiAuditEvent tree (agentstore): CHAT root per runId
     + TOOL / RETRIEVAL children (kind, startedAt, finishedAt, latencyMs,
       outcome, errorClass, parent, runId)
```

### Recommended Project Structure (changes only — no new packages)
```
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── entity/AiChatSurface.java                 # + SIDEBAR("SIDEBAR")
├── orchestration/StreamingEvent.java         # + Activity(ActivityKind) variant; + ActivityKind enum (nest or sibling)
├── orchestration/StreamingSinkHolder.java    # unchanged (emit hook already exists)
├── rag/advisor/AuditingDocumentRetriever.java # + emit Activity(RETRIEVAL) at retrieval start
├── audit/ToolCallbackAuditDecorator.java     # + emit Activity(TOOL) alongside ToolCall
├── DefaultChatServiceImpl.java               # + emit Activity(CHAT) before model stream (optional per D-05)
├── view/chat/ChatSurfaceMounter.java         # + mountSidebarToggle / mountSidebarPanel / toggleSidebar / SIDEBAR refresh
├── view/chat/fragment/ChatPanelFragment.java # + Activity arm in .doOnNext; status-line <div>; per-turn <details>; bounded step list; on-expand AiAuditEvent re-query
├── view/chat/fragment/StreamEventRenderer.java # + Activity arm in renderStreamEventDetails (compiler-forced); or a new sibling pure-fn for the live step list
└── (new, optional) view/chat/fragment/TurnDetailRenderer.java  # pure-fn: List<AuditStep> + kind→msg:// mapper → label-only rows (TEST-19 target — keep Vaadin-free for plain JUnit + UiTest reuse)

ai-agent/ai-agent/src/main/resources/
├── META-INF/resources/frontend/styles/ai-agent-chat.css   # + .ai-agent-sidebar, .ai-agent-sidebar--open, .ai-agent-content--pushed, .ai-agent-status (+ @keyframes + prefers-reduced-motion), .ai-agent-sidebar-toggle--active, ai-agent-turn-detail tweaks
├── com/vn/agent/messages_en.properties        # + sidebar surface label, toggle aria-labels, status labels (thinking/searching/retrieving/neutral), turn-detail summary + step labels + error indicator
├── com/vn/agent/messages_vi.properties         # same keys, vi
└── (no liquibase changelog — OBS-04)

ai-agent/ai-agent/src/test/java/com/vn/agent/
├── view/chat/ChatSurfaceMounterTest.java      # + SIDEBAR mount / toggle / persistence-across-navigation cases
├── view/chat/ChatDialogViewTest.java          # update if needed (independence of dialog vs sidebar)
├── view/chat/ChatPanelFragmentSurfaceSwitchTest.java  # + SIDEBAR continuity
├── view/uisettings/AiUiSettingsDetailViewTest.java    # + SIDEBAR selectable in enabled-surface + defaultSurface
├── view/chat/AiChatSessionStateTest.java      # + assert no per-turn detail accumulates (bounded)
├── view/chat/<new> ObservabilityLeakTest.java # TEST-19 — @UiTest reusing Phase 9 pattern packs against rendered status line + <details>
└── view/chat/fragment/<new> TurnDetailRendererTest.java  # plain JUnit — kind→msg:// mapper label-only contract
```

### Pattern 1: Side-panel mount on the UI element (survives navigation)
**What:** Append a `position:fixed` `Div` (hosting the shared `ChatPanelFragment`) to `UI.getCurrent().getElement()` — NOT to the `AppLayout` content slot. Toggle a CSS class on the `AppLayout` (or its content) that adds `padding-right`/`margin-right` ≈ panel width. The Div lives outside the router outlet so navigation can't tear it down.
**When to use:** SURF-11 mount in `ChatSurfaceMounter` (mirrors how `attachDialogWindowToUi` already does `ui.getElement().appendChild(dialogWindow.getElement())` to keep the modeless dialog alive across navigation).
**Example:** see §Code Examples — "ChatSurfaceMounter SIDEBAR mount sketch".

### Pattern 2: NOTICE-row-style sibling element in `messageListSlot`
**What:** A plain `<div>`/`<span>` appended via `messageListSlot.getElement().appendChild(element)` AFTER the `<vaadin-message-list>` block (the `messageList` Vaadin component is `messageListSlot.add(messageList)`-ed first, so anything appended later is a sibling rendered below). Removed via `element.removeFromParent()` or `messageListSlot.getElement().removeChild(...)`.
**When to use:** OBS-01 status line (`<span class="ai-agent-status" role="status" aria-live="polite">`) and OBS-02 per-turn `<details>`.
**Existing precedent:** `appendNoticeRow(...)`, `appendIntentConfirmRow(...)`, `appendActionChoiceRow(...)` in `ChatPanelFragment`. Note `clearMessageList()` already does `messageListSlot.removeAll(); messageListSlot.getElement().removeAllChildren();` then re-adds `messageList` — the status row is naturally cleared on conversation switch, but must ALSO be explicitly removed on terminal `Final`/`Error` within the same turn (D-06).

### Pattern 3: Additive sealed-interface variant (compiler-enforced exhaustiveness)
**What:** Add `Activity(ActivityKind kind)` to `StreamingEvent`'s `permits` clause + a `record Activity(ActivityKind kind) implements StreamingEvent {}`. Every exhaustive `switch (event)` over `StreamingEvent` must add a `case StreamingEvent.Activity a -> ...` arm or fail to compile.
**Current exhaustive switches over `StreamingEvent`:** only `StreamEventRenderer.renderStreamEventDetails(...)` (the `ChatPanelFragment.doOnNext` uses `instanceof` pattern checks, not a `switch`, so it's an additive change there too — add an `else if (evt instanceof StreamingEvent.Activity a)` branch). `ToolCallbackAuditDecorator` and `AuditingDocumentRetriever` *emit* events, they don't switch on them. **Grep for `switch` over `StreamingEvent` and for `StreamingEvent.` `instanceof` before/after the change** to be sure (planner: add a checkable proxy).
**When to use:** D-05.

### Pattern 4: One `kind → msg://` mapper, label-only (no-leak by construction)
**What:** A single pure function `String labelFor(ActivityKind kind)` for the status line and `String labelFor(String auditKind)` / `List<StepRow> rowsFor(...)` for the disclosure — it accepts ONLY the closed enum / the `kind` String (`CHAT`/`TOOL`/`RETRIEVAL`) + timing + outcome, and resolves `msg://` keys; it never receives `toolName`, `argsJson`, entity names, or `resultSummary`. This makes TEST-19 a structural assertion: the input alphabet can't contain a tool/entity name. Keep this mapper in a Vaadin-free class (like `StreamEventRenderer`) so a plain JUnit test can lock the contract and the `@UiTest` can assert the rendered component text.
**When to use:** OBS-01 + OBS-02 + TEST-19.

### Anti-Patterns to Avoid
- **Wrapping the router outlet in a layout component for the sidebar** — `AppLayout` `setContent()`-replaces it on every navigation; you'd race the framework. (D-01 already chose `position:fixed` Div.)
- **Concatenating status text into the assistant `MessageListItem`** — explicitly forbidden (OBS-01); the current code already does this for *intermediate model prose* via `botMsg.appendText(md)` — the status line is a SEPARATE sibling element, not appended to `botMsg`. Do not "fix" the existing prose-in-bubble behaviour in this phase (out of scope; it's not the status line).
- **Routing `toolName` / `resultSummary` / entity names into the status line or `<details>` row text** — the closed `ActivityKind` enum + the `kind`-keyed mapper exist precisely to prevent this; never pass the raw `StreamingEvent.ToolResult.toolName()` or `AiAuditEvent.eventName`/`argumentsJson`/`resultSummary` into a rendered label. (TEST-19 will catch a regression.)
- **Putting the per-turn step accumulator in `AiChatSessionState`** — OBS-04 requires it bounded; keep it per-`ChatPanelFragment` instance, cleared on `Final`/`Error`. `AiChatSessionState` stays at `currentConversationId` + listeners.
- **Re-querying `AiAuditEvent` for a turn on every render** — only on `Details` expand (user-initiated), and only for turns NOT in the bounded live list (history turns after a fresh navigation). Cache the result on the `Details` component so re-collapse/re-expand doesn't re-query.
- **Adding a new `@Entity`/`@Table`/Liquibase changelog** — OBS-04 forbids it; the `SIDEBAR` enum value is a string change to existing `enabledSurfaceIds`/`defaultSurface` columns (no DDL).
- **Hardcoded UI text** — every label via `msg://` in BOTH `messages_en.properties` and `messages_vi.properties` (CLAUDE.md + project memory). Inject `io.jmix.core.Messages` in views; keep keys in the root `com.vn.agent` bundle and resolve via the class-less `messages.getMessage(key)` form (project memory `feedback_jmix_messages_over_spring.md`).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Collapsible per-turn disclosure with a11y | Custom `<div>` + JS toggle + ARIA wiring | Vaadin `Details` (`<details>` / `setOpened` / `setSummaryText` / theme variants) | Ships keyboard + ARIA + Lumo styling; in Jmix's accepted set (D-08; Context7 confirmed). |
| Per-UI mounted-surface bookkeeping | A new session/UI bean | The existing `MountedChatSurfaceState` (`ComponentUtil.getData(ui, ...)`) inside `ChatSurfaceMounter` | Already scoped per-UI, already re-synced on `AfterNavigationEvent`; add fields. |
| Surface enable/disable + default + admin control | A new settings entity/screen | `AiUiSettings.getEnabledSurfaceSet()` + `AiUiSettingsDetailView` (`JmixCheckboxGroup<AiChatSurface>` auto-lists new enum values) | Already the surface gate for FULL_ROUTE/HEADER_BUTTON; `SIDEBAR` just needs the enum value + labels. |
| Streaming → UI transport for a new "what's happening" signal | A second flux / a polling endpoint | The existing `Flux<StreamingEvent>` + `StreamingSinkHolder.currentOrForRun(runId)` emit hook | Already wired end-to-end (Phase 7); `Activity` is an additive variant. |
| Durable per-turn step record (for history turns) | A new "AiTurn" entity / parallel store | The existing `AiAuditEvent` tree (CHAT root per `runId` + TOOL/RETRIEVAL children with `kind`/`startedAt`/`latencyMs`/`outcome`/`errorClass`) | OBS-04 forbids new persisted state; the audit tree already has everything. |
| Leak-pattern definitions for the UI test | New `HOST_PREFIX_LEAK`/`TOOL_NAME_LEAK` regexes | `HostPrefixPatternProvider.asPattern()` / `ToolNamePatternProvider.asPattern()` (Phase 9) | D-09 mandates verbatim reuse — do not fork. |
| Project CSS file plumbing | A new Vaadin theme / `theme.json` / `frontend/themes/<name>/` | Append rules to the existing `META-INF/resources/frontend/styles/ai-agent-chat.css` (loaded via `@CssImport("./styles/ai-agent-chat.css")` on `MessageBubbleComponent`) | Established project convention; "no new core dependencies"; the `@CssImport` scanner already picks this file up. |

**Key insight:** Phase 15 is ~90% wiring already-built mechanisms into one new surface + two new in-fragment sibling elements + one additive sealed-interface variant + one test that reuses existing regexes. The genuinely new code is a small CSS block (shell docking + push class + a `@keyframes` pulse) and a label-only `kind → msg://` mapper. Resist the temptation to build infrastructure.

## Common Pitfalls

### Pitfall 1: Sidebar Div torn down on navigation
**What goes wrong:** The panel attached to `AppLayout`'s content slot vanishes (or re-mounts with lost state) on every route change.
**Why it happens:** `AppLayout.showRouterLayoutContent()` calls `setContent()` which replaces the content slot child. Anything inside it is detached.
**How to avoid:** Attach the wrapper `Div` to `UI.getCurrent().getElement()` (like `attachDialogWindowToUi`), not the content slot. On `AfterNavigationEvent`, re-assert (a) the Div is still attached (re-append if not), (b) the push class is still on the (possibly new) `AppLayout`/work-area instance, (c) the navbar toggle is still mounted (`mountHeaderButton` already does this self-heal for the magic button — mirror it).
**Warning signs:** Panel disappears after clicking a menu item; CSS push gap collapses after navigation; conversation state resets.

### Pitfall 2: `position:fixed` overlaps the navbar
**What goes wrong:** The panel covers the top app bar.
**Why it happens:** `top: 0` instead of `top: <navbar height>`.
**How to avoid:** Use the Jmix Lumo navbar-height CSS variable if available; if not reliably present across host shells, use a fixed `top` (e.g. `var(--lumo-size-xl)` ≈ navbar height, or a documented constant). D-01 says "use the navbar-height CSS variable if available; otherwise a fixed `top`." Verify against the host's `AppLayout` in UI verification. The push class should also set `padding-top` only if needed (usually the navbar is `position:relative` in `AppLayout`, so just `padding-right` on the content area suffices).
**Warning signs:** Navbar buttons unclickable; panel header hidden behind the app bar.

### Pitfall 3: `clearMessageList()` removes the status row but the *terminal-event* path doesn't
**What goes wrong:** Status line lingers after a turn completes (until the next conversation switch).
**Why it happens:** Relying on `clearMessageList()` (which only runs on conversation switch) instead of removing the row in `finishStreamInternal()` / on `Final`/`Error`.
**How to avoid:** Track the status `<span>` as a field; remove it (`removeFromParent()`) in the terminal-event handler and in `finishStreamInternal()`. D-06: "REMOVE entirely on terminal `Final`/`Error`" — not blanked.
**Warning signs:** "searching data…" still visible under a completed answer.

### Pitfall 4: The `Details` for a turn re-queries `AiAuditEvent` every expand
**What goes wrong:** Each expand/collapse cycle hits the DB.
**Why it happens:** No memoization of the on-expand read.
**How to avoid:** On first expand of a history turn's `Details`, query once, populate the content `VerticalLayout`, set a "loaded" flag on the component (e.g. `ComponentUtil.setData(details, ...)` or a `Map<UUID,Boolean>` keyed by `runId`). For live turns, populate from the bounded step list when the turn finalizes (no DB at all).
**Warning signs:** N DB round-trips for N expand clicks.

### Pitfall 5: Adding the `Activity` variant breaks a `switch` somewhere unexpected
**What goes wrong:** Compilation fails in a module you didn't expect, or (worse) a non-exhaustive `switch` with a `default:` silently swallows `Activity`.
**Why it happens:** Sealed-interface `switch`es are exhaustive only without a `default`; some code may use `default`.
**How to avoid:** After adding the variant, `grep -rn "StreamingEvent" --include=*.java` and check every `switch` and `instanceof` chain. Currently only `StreamEventRenderer.renderStreamEventDetails` is an exhaustive `switch`; `ChatPanelFragment.doOnNext` uses `instanceof`. Add the new arm/branch to each. Tests: `RenderStreamEventTest`, `RenderStreamEventIntentPayloadTest`, `ChatViewStreamTest` will exercise the renderer — run the full chat test suite.
**Warning signs:** `error: the switch statement does not cover all possible input values`; or `Activity` events visibly ignored at runtime.

### Pitfall 6: `Activity(RETRIEVAL)` emitted with the wrong `runId` (tool-context vs RunContext)
**What goes wrong:** The `RETRIEVAL` status never shows because the sink lookup misses.
**Why it happens:** `AuditingDocumentRetriever.retrieve(...)` resolves `runId` from `RunContext.get()` first, then from `query.context().get("audit.runId")`. The streaming sink in `StreamingSinkHolder` is keyed by the run id registered by `DefaultChatServiceImpl.stream(...)`. If the RAG retriever runs on a thread where `RunContext` is restored differently, use `streamingSinkHolder.currentOrForRun(runId)` exactly as `ToolCallbackAuditDecorator` does — that method prefers `forRun(runId)` then falls back to `current()`.
**How to avoid:** Mirror `ToolCallbackAuditDecorator.emitToolEvent(runId, ...)` exactly: resolve `runId` the same way (`RunContext.get()` then context key), call `streamingSinkHolder.currentOrForRun(runId).ifPresent(sink -> sink.tryEmitNext(new StreamingEvent.Activity(ActivityKind.RETRIEVAL)))`, swallow exceptions. `AuditingDocumentRetriever` is instantiated inline by `RetrievalAugmentationAdvisorFactory` (not a `@Component`) — the factory must pass it the `StreamingSinkHolder` bean (constructor change).
**Warning signs:** "retrieving documents…" never appears even when KB docs are returned.

### Pitfall 7: Toggle button id collides with `aiAgentHeaderChatButton`
**What goes wrong:** `ChatSurfaceMounter.findComponentById(ui, CHAT_BUTTON_ID, ...)` finds the wrong button; visibility logic crosses wires.
**Why it happens:** Reusing or fuzzy-matching the existing id.
**How to avoid:** Use a clearly distinct id (e.g. `aiAgentSidebarToggleButton`) and a distinct CSS class (`ai-agent-sidebar-toggle-button`); the find-by-id helpers match exact ids, so distinctness is sufficient. The leak/no-greying contract: when `SIDEBAR` disabled, do not mount the toggle at all (don't `setVisible(false)` it — `mountHeaderButton` mounts unconditionally and toggles visibility, but the SPEC for SIDEBAR says "absent, not greyed" — match the magic-button *mounting* pattern but gate the *mount* itself on `enabledSurfaceSet.contains(SIDEBAR)`, OR mount-then-`setVisible(false)`; planner picks — note the existing magic button is mounted-always-then-hidden, but its acceptance is "absent" too via `setVisible(false)` — be consistent with whatever the existing pattern actually does and document it).

### Pitfall 8: `AiAuditEvent` query crosses store / N+1 on children
**What goes wrong:** `loadValues`/`loadValue` raw JPQL silently picks the wrong store; or loading the CHAT root then walking `getChildren()` triggers a lazy-load per child.
**Why it happens:** `AiAuditEvent` is `@Store(name = "agentstore")`; raw-JPQL `loadValue/loadValues` does NOT infer the store (project memory `feedback_jmix_loadvalue_store.md`) — must `.store("agentstore")`. Typed `DataManager.load(AiAuditEvent.class)...` DOES infer it.
**How to avoid:** Prefer a typed load with an explicit `query("select e from ai_AiAuditEvent e where e.runId = :rid and e.parent is not null order by e.startedAt asc")` and a narrow fetch plan (`kind`, `startedAt`, `finishedAt`, `latencyMs`, `outcome`, `errorClass` — NOT `parent`/`children`/`conversation`/the LOB columns). If raw `loadValues` is used for a projection, append `.store("agentstore")`. Use `UnconstrainedDataManager` if this is treated as a system-internal read, or `DataManager` if user-attributable — the audit list view (`AiAuditEventListView`) is the existing precedent; check what it uses. (The fragment already injects `DataManager`; `AuditWriter` uses `UnconstrainedDataManager` — pick per intent. The user is viewing their own conversation's audit, so `DataManager` with the user's row-level security is the safer default; confirm `AiAuditEvent` has a row-level role that lets a chat user read their own rows, or fall back to `UnconstrainedDataManager` filtered by `userUsername`.)
**Warning signs:** Empty step list for history turns; or a burst of SELECTs proportional to step count.

## Code Examples

### Example: `StreamingEvent.Activity` additive variant
```java
// orchestration/StreamingEvent.java — Source: codebase (extend the existing sealed interface)
public sealed interface StreamingEvent
        permits StreamingEvent.Content,
                StreamingEvent.ToolCall,
                StreamingEvent.ToolResult,
                StreamingEvent.Citation,
                StreamingEvent.Activity,   // <-- new
                StreamingEvent.Final,
                StreamingEvent.Error {

    /** Closed activity-kind enum surfaced to the chat UI for the streaming-status line (Phase 15 D-05).
     *  Mirrors the AiAuditEvent KIND values that are user-renderable; NEVER carries a @Tool method name
     *  or an entity name. */
    enum ActivityKind { CHAT, TOOL, RETRIEVAL }

    record Activity(ActivityKind kind) implements StreamingEvent {}
    // ... existing records unchanged ...
}
```

### Example: emit `Activity(RETRIEVAL)` from `AuditingDocumentRetriever` (mirrors `ToolCallbackAuditDecorator.emitToolEvent`)
```java
// rag/advisor/AuditingDocumentRetriever.java — Source: codebase pattern (ToolCallbackAuditDecorator.emitToolEvent)
// constructor gains: private final StreamingSinkHolder streamingSinkHolder;  (passed by RetrievalAugmentationAdvisorFactory)
@Override
public @NonNull List<Document> retrieve(@NonNull Query query) {
    UUID runId = RunContext.get();
    if (runId == null && query.context().get("audit.runId") instanceof UUID u) runId = u;
    if (streamingSinkHolder != null) {
        try {
            UUID rid = runId;
            streamingSinkHolder.currentOrForRun(rid)
                .ifPresent(sink -> sink.tryEmitNext(
                    new StreamingEvent.Activity(StreamingEvent.ActivityKind.RETRIEVAL)));
        } catch (RuntimeException ignored) { /* observability emit must never break retrieval */ }
    }
    // ... existing retrieve + audit body unchanged ...
}
```

### Example: emit `Activity(TOOL)` in `ToolCallbackAuditDecorator.callInternal` (one line, alongside the existing `ToolCall` emit)
```java
// audit/ToolCallbackAuditDecorator.java — Source: codebase (existing emitToolEvent helper)
final UUID toolCallId = UUID.randomUUID();
emitToolEvent(runId, sink -> sink.tryEmitNext(
        new StreamingEvent.Activity(StreamingEvent.ActivityKind.TOOL)));   // <-- new, before/with the ToolCall emit
emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(toolCallId, toolName, cappedInput)));
```

### Example: status-line + per-turn `<details>` in `ChatPanelFragment.submitChatTurn(...).doOnNext(...)`
```java
// view/chat/fragment/ChatPanelFragment.java — Source: codebase pattern (appendNoticeRow / doOnNext branches)
// fields:
//   private com.vaadin.flow.dom.Element statusRow;          // null when no turn streaming
//   private final java.util.List<TurnStep> liveTurnSteps = new java.util.ArrayList<>();   // bounded; cleared on Final/Error
//   private final java.util.Map<UUID, com.vaadin.flow.dom.Element> turnDetailsByRunId = new java.util.HashMap<>();  // optional anchor

.doOnNext(evt -> {
    if (evt instanceof StreamingEvent.Final f) { /* ...existing... activeRunId = f.runId(); */ }
    if (evt instanceof StreamingEvent.Activity a) {
        accessUi(() -> showStatus(messages.getMessage(statusKeyFor(a.kind()))));   // statusKeyFor: CHAT->...thinking, TOOL->...searching, RETRIEVAL->...retrieving
        return;
    }
    if (evt instanceof StreamingEvent.ToolCall tc) { liveTurnSteps.add(TurnStep.started(tc.toolCallId())); }
    if (evt instanceof StreamingEvent.ToolResult tr) { liveTurnSteps.add(TurnStep.finished(tr.toolCallId(), tr.outcome())); /* dedupe by id */ }
    StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(evt, labels, citationState);
    // ... existing markdown / draft / action-proposal branches unchanged ...
})
.doOnError(err -> accessUi(() -> { removeStatusRow(); /* ...existing... */ }))
.doOnComplete(() -> accessUi(() -> {
    removeStatusRow();
    int stepCount = liveTurnSteps.size();
    if (stepCount > 0 && activeRunId != null) {
        appendTurnDetails(activeRunId, liveTurnSteps);   // builds a <details opened=false> sibling after botMsg's MessageListItem
    }
    liveTurnSteps.clear();   // OBS-04 — nothing accumulates
    finishStreamInternal();
}))
.subscribe();

// showStatus: create-or-update a <span class="ai-agent-status" role="status" aria-live="polite"> appended to messageListSlot.getElement()
// removeStatusRow: if (statusRow != null) { statusRow.removeFromParent(); statusRow = null; }
// appendTurnDetails: Details d = new Details(); d.setOpened(false); d.setSummaryText(MessageFormat.format(messages.getMessage("...summary"), stepCount, totalMs));
//   d.add(buildStepRows(...));  messageListSlot.add(d);  // sibling after the MessageList; on first expand of a HISTORY-replayed turn, lazy-query AiAuditEvent by runId
```

### Example: TEST-19 leak assertion reusing Phase 9 pattern providers
```java
// view/chat/ObservabilityLeakTest.java (new) — Source: codebase pattern (ToolNameLeakScannerTest)
private static java.util.regex.Pattern toolNameRegex() {
    var props = new com.vn.agent.guard.AiAgentGuardProperties(null, null, null, null);
    var p = new com.vn.agent.guard.ToolNamePatternProvider(java.util.List.of(), props);
    p.buildPattern();
    return java.util.regex.Pattern.compile(p.asPattern().orElseThrow().regex());
}
// (HostPrefixPatternProvider needs a Metadata mock with the host metaclasses — see ToolNameLeakScannerTest.noOpHostProvider();
//  in a @SpringBootTest the real Metadata bean is available, so use the autowired HostPrefixPatternProvider.asPattern().)

@Test
void statusLineAndTurnDetailNeverEmitInternalNames() {
    Pattern tool = toolNameRegex();
    Pattern host = hostPrefixRegex();   // from autowired HostPrefixPatternProvider in @SpringBootTest
    for (StreamingEvent.ActivityKind k : StreamingEvent.ActivityKind.values()) {
        String label = renderStatusLine(k);                       // the actual rendered <span> text
        assertThat(tool.matcher(label).find()).isFalse();
        assertThat(host.matcher(label).find()).isFalse();
    }
    for (String stepText : renderTurnDetailRows(/*kinds=*/List.of("TOOL","RETRIEVAL"), /*outcome=*/AiToolCallOutcome.ERROR)) {
        assertThat(tool.matcher(stepText).find()).isFalse();
        assertThat(host.matcher(stepText).find()).isFalse();
    }
    // negative control: deliberately route a tool name through the rendering path -> must trip
    assertThat(tool.matcher(renderStepRowWithRawLabel("find_records")).find()).isTrue();
}
```

### Example: `<details>` in XML (if any part of the disclosure is declarative — most will be built in Java)
```xml
<!-- Source: Context7 jmix-framework/jmix-context7 — Details component -->
<details id="turnDetail" summaryText="msg:///chatView.turnDetail.summary" width="100%" opened="false">
    <vbox id="turnDetailSteps" spacing="false" padding="false"/>
</details>
```
(In practice the per-turn `Details` is created in Java because it's appended dynamically as a sibling of a `MessageListItem` — XML is fine for a static container but not for the per-turn instances.)

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Streaming shows only a `ProgressBar`; intermediate model prose appended into the assistant bubble | This phase adds an ephemeral KIND-keyed sibling status line (separate from the bubble) | Phase 15 | The prose-in-bubble behaviour (`botMsg.appendText(md)`) is NOT changed here — only an additional status line is added. |
| To see what the agent did, the operator opens `AiAuditEventListView` manually | Per-turn collapsed `Details` in chat (label-only) | Phase 15 | The audit list view is unchanged; no `?runId=` deep-link (descoped). |
| Two chat surfaces (`FULL_ROUTE`, `HEADER_BUTTON`) | Third `SIDEBAR` surface (push-mode right panel) | Phase 15 | `AiChatSurface` gains `SIDEBAR`; `AiUiSettings` columns are strings → no DDL. |
| Jmix `sidePanelLayout` was the SPEC's "preferred realization" | `position:fixed` docked Div on the UI element (D-01) | 2026-05-11 discuss-phase | The SPEC/REQUIREMENTS/ROADMAP still mention `sidePanelLayout` — doc-sync follow-up only; the code uses D-01. |

**Deprecated/outdated:**
- The SPEC's "Preferred SIDEBAR realization" (`sidePanelLayout` at the shell level) — superseded by D-01. Research confirms the risk the SPEC flagged is real (`AppLayout` content-slot replacement on navigation).
- Vaadin `Upload.getReceiver/setReceiver` — `forRemoval` in 24.8 (project memory `feedback_jmix_upload_receiver_deprecated.md`); irrelevant to this phase (no upload changes) but noted because `ChatPanelFragment` already uses the modern `UploadHandler.toFile` path — don't regress it.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The Jmix Lumo theme exposes a usable navbar-height CSS variable (D-01 mentions "if available"). | Pitfall 2 / Architecture | Low — fallback is a fixed `top` (e.g. `var(--lumo-size-xl)`); planner verifies in UI verification. |
| A2 | `AppLayout` (the host `StandardMainView`'s app bar) is `position:relative`, so a `padding-right` on the content area is enough for "push" mode without re-laying-out the navbar. | Pattern 1 / Pitfall 2 | Low-medium — if the host uses a custom main view with a `position:fixed` navbar, the push class may need `padding-top` too; the fixed-position Div approach is shell-agnostic by design (D-01's stated rationale) so this is a CSS tweak, not a structural risk. |
| A3 | The chat user has a row-level role allowing them to `DataManager`-read their own `AiAuditEvent` rows (for the on-expand history-turn query); otherwise the fragment must use `UnconstrainedDataManager` filtered by `userUsername`. | Pitfall 8 / OBS-02 | Medium — affects which DataManager the on-expand query uses. Planner: check `AiAuditEventListView`'s query path and the `agentstore` security roles; `AuditWriter` uses `UnconstrainedDataManager`, the audit *list* view is the read precedent. |
| A4 | `StreamEventRenderer.renderStreamEventDetails(...)` is the ONLY exhaustive `switch` over `StreamingEvent`; `ChatPanelFragment` uses `instanceof` chains, not a `switch`. | Pattern 3 / Pitfall 5 | Low — verified by reading both files; planner should still `grep` after the change as a checkable proxy. |
| A5 | `RetrievalAugmentationAdvisorFactory` can be given the `StreamingSinkHolder` bean to pass into the inline-constructed `AuditingDocumentRetriever` (it's `@Component`-injectable). | Code Examples / Pitfall 6 | Low — `StreamingSinkHolder` is a `@Component`; the factory is the standard injection point. |
| A6 | Appending an element to `messageListSlot.getElement()` after `messageListSlot.add(messageList)` renders it as a visible sibling *below* the `<vaadin-message-list>`. | Pattern 2 | Very low — this is exactly what `appendNoticeRow` / `appendIntentConfirmRow` / `appendActionChoiceRow` already do successfully. |
| A7 | Adding `SIDEBAR` to `AiChatSurface` requires no `agentstore` changelog because `ENABLED_SURFACE_IDS`/`DEFAULT_SURFACE` are `varchar` columns holding comma-joined ids. | OBS-04 / SURF-11 | Very low — confirmed by reading `AiUiSettings.java` (`@Column(name = "ENABLED_SURFACE_IDS")` String, `@Column(name = "DEFAULT_SURFACE", length = 64)` String) and SPEC ("no schema change expected"). |
| A8 | The default-seed `AiUiSettings` (`AiUiSettingsService.createDefaultSettings()` uses `EnumSet.allOf(AiChatSurface.class)`) will automatically include `SIDEBAR` once the enum value is added — so a fresh install ships with all three surfaces enabled and `FULL_ROUTE` default. | SURF-11 | Low — confirmed by reading `AiUiSettingsService`; planner should decide whether shipping `SIDEBAR` enabled-by-default is desired or whether the seed should be tightened (a product call; current code = all enabled). |

## Open Questions

1. **Which `DataManager` for the on-expand `AiAuditEvent` history-turn read?**
   - What we know: `AuditWriter` uses `UnconstrainedDataManager`; `ChatPanelFragment` injects `DataManager`; `AiAuditEvent` is `@Store("agentstore")`; the audit list view exists.
   - What's unclear: whether the chat user's Jmix roles permit a constrained read of their own audit rows.
   - Recommendation: planner reads `AiAuditEventListView` + the `agentstore` security roles; default to `DataManager` (constrained, user-attributable) if the role allows it, else `UnconstrainedDataManager` with an explicit `where e.userUsername = :me` clause. Either way, narrow fetch plan, `where e.runId = :rid and e.parent is not null`, and `.store("agentstore")` only if raw-JPQL projection is used.

2. **Should `SIDEBAR` ship enabled-by-default in the seed?**
   - What we know: `createDefaultSettings()` enables all `AiChatSurface` values; adding `SIDEBAR` to the enum auto-enables it on fresh installs.
   - What's unclear: product preference (a ~32%-viewport surface enabled by default is a visible default).
   - Recommendation: the panel "starts closed" (D-04) so enabled-by-default only means "the toggle button is in the navbar" — consistent with HEADER_BUTTON shipping a navbar button by default. Recommend keeping the all-enabled seed for parity; flag for the design-conscious owner during UI verification.

3. **Is the existing `aiAgentHeaderChatButton` mounted-always-then-hidden, or mounted-only-when-enabled?**
   - What we know: `mountHeaderButton` mounts unconditionally on UI init / navigation; `refreshMountedSurfaces` then `setVisible(...)` based on `shouldShowHeaderButton`. So it's *mounted-always, visibility-toggled*. But the SURF-11 acceptance says the sidebar toggle is "absent, not greyed" when disabled.
   - What's unclear: whether "absent" is satisfied by `setVisible(false)` (the magic button's approach — it's `visible=false`, which removes it from the DOM in Vaadin) or requires not creating the component at all.
   - Recommendation: mirror the existing magic-button pattern (mount always, `setVisible(false)` when disabled) — Vaadin `setVisible(false)` removes the element from the rendered DOM, which satisfies "absent"; document this in the plan so the verifier checks DOM-absence, not just `isVisible()`.

## Environment Availability

Not applicable in the external-tool sense — this is a code/config change inside the existing Jmix add-on. The only "dependency" is the running host shell having an `AppLayout`-based main view (`StandardMainView`), which `ChatSurfaceMounter` already detects and degrades gracefully when absent (logs "AI Agent chat button not mounted: host main view does not extend AppLayout" — the SIDEBAR mount must do the same: skip if no `AppLayout`, no throw). Build/test: `./gradlew test` (existing harness; `@UiTest` + `@SpringBootTest` with `FlowuiTestAssistConfiguration`, `StubChatModelConfiguration`, `StubVectorStoreConfiguration` for chat-flow tests). App runs on `http://localhost:8088` (project memory `project_local_dev_port.md`) — do NOT auto-start `bootRun`.

## Validation Architecture

Nyquist validation is enabled (`workflow.nyquist_validation: true`). Existing test infrastructure (`@UiTest` + `@SpringBootTest`, plain JUnit 5 + AssertJ + Mockito) fully covers this phase — no new framework.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ + Mockito; Jmix `@UiTest` (`io.jmix.flowui.testassist`) for Flow-UI component tests; `@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})` + `@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})` |
| Config file | `ai-agent/ai-agent/build.gradle` (test deps); no separate test config file |
| Quick run command | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*"` (or a single class, e.g. `--tests "com.vn.agent.view.chat.ChatSurfaceMounterTest"`) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SURF-11 | `AiChatSurface.SIDEBAR` exists and is an `EnumClass<String>` value | unit | `./gradlew test --tests "com.vn.agent.entity.AiUiSettingsModelTest"` (extend) | ✅ extend `AiUiSettingsModelTest` / `AiChatSurface` covered transitively |
| SURF-11 | `SIDEBAR` selectable in the admin enabled-surface control + `defaultSurface` selector | `@UiTest` | `./gradlew test --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest"` | ✅ extend |
| SURF-11 | With `SIDEBAR` enabled, `ChatSurfaceMounter` mounts a fixed-position panel `Div` on the UI element + a distinct navbar toggle; with `SIDEBAR` disabled, neither is in the rendered tree | `@UiTest` | `./gradlew test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest"` | ✅ extend (mirror `defaultSettingsMountExactlyOneVisibleHeaderButton...` / `disabling...HidesMountedButton...`) |
| SURF-11 | Panel survives route navigation (still attached, push class still on AppLayout) and the main view stays interactive (non-modal — no modality curtain element) | `@UiTest` | same class — model on `dialogWindowSurvivesRouteNavigationWithConversationState` | ✅ extend |
| SURF-11 | No second `ChatService` / `ChatMemory` / `ChatPanelFragment` introduced | source-scan unit | a small test asserting only one `ChatPanelFragment`-typed `@ViewComponent` per chat view and `ChatPanelFragment` is the only `Fragment` subclass used by the sidebar (or a structural assertion the sidebar `Div` hosts the same fragment instance the mounter created) | ❌ Wave 0 — `SidebarSharesSingleFragmentTest` (or fold into `ChatSurfaceMounterTest`) |
| SURF-11 | Cross-surface `AiChatSessionState` continuity (conversation started in any surface visible after switching) | `@UiTest` | `./gradlew test --tests "com.vn.agent.view.chat.ChatPanelFragmentSurfaceSwitchTest"` | ✅ extend |
| OBS-01 | A status `<span role="status" aria-live="polite">` appears in `messageListSlot` (sibling of `<vaadin-message-list>`, NOT inside any `MessageListItem`) keyed by `ActivityKind` while streaming, and is REMOVED on `Final`/`Error` | `@UiTest` (drive a stub stream emitting `Activity` + `Final`) | `./gradlew test --tests "com.vn.agent.view.chat.fragment.*"` (model on `ChatPanelFragmentLoadingIndicatorTest`) | ❌ Wave 0 — `ChatPanelFragmentStatusLineTest` |
| OBS-01 | Status text is never concatenated into the assistant bubble; final bubble has only reply markdown | `@UiTest` | same class — assert `botMsg`-equivalent `MessageListItem` text == reply markdown, status `<span>` is a separate element | ❌ Wave 0 (same file) |
| OBS-01 | `ActivityKind`-keyed labels resolve in `messages_en.properties` AND `messages_vi.properties` | unit | a `Messages`-resolution test (model on existing locale-completeness tests if any; else a plain test loading both bundles and asserting keys present) | ❌ Wave 0 — `ObservabilityMessagesCompletenessTest` (or extend an existing locale test) |
| OBS-02 | A completed turn with ≥1 step shows a `<details opened="false">` with summary "…N steps · total ms" and N label-only step rows with per-step ms; an `ERROR`/rollback step shows the indicator | `@UiTest` | `./gradlew test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailTest"` | ❌ Wave 0 — `ChatPanelFragmentTurnDetailTest` |
| OBS-02 | A zero-step turn shows NO `<details>` at all | `@UiTest` | same class | ❌ Wave 0 (same file) |
| OBS-02 | For a turn replayed after a fresh navigation, expanding the `Details` lazily reads `AiAuditEvent` TOOL/RETRIEVAL children by `runId` and renders the same label-only rows; re-expand does not re-query | `@UiTest` + audit-row fixture | `./gradlew test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailHistoryTest"` (seed `AiAuditEvent` rows, navigate away+back, expand) | ❌ Wave 0 — `ChatPanelFragmentTurnDetailHistoryTest` |
| OBS-02 | No `?runId=` deep-link, no change to `AiAuditEventListView` | source-scan unit | grep-style assertion: the new code contains no `AiAuditEventListView` navigation; `AiAuditEventListView` source unchanged (or no new route param) | ❌ Wave 0 (small) — fold into the turn-detail test |
| OBS-04 | No new `@Entity`/`@Table`/Liquibase changelog added for observability | source/structural unit | assert `agentstore-changelog.xml` (and `changelog.xml`) gained no Phase-15 include; `git diff --stat` proxy in the plan; or a test enumerating `@Entity` classes and asserting count unchanged | ❌ Wave 0 — `NoNewPersistedStateTest` (or a documented `git diff` proxy) |
| OBS-04 | `AiChatSessionState` does not accumulate per-turn detail without bound (a long multi-turn conversation leaves it at `currentConversationId` + listeners) | unit | `./gradlew test --tests "com.vn.agent.view.chat.AiChatSessionStateTest"` (extend — assert no new fields / no growing collection) | ✅ extend |
| OBS-04 | The in-fragment live-turn step list is cleared on `Final`/`Error` | `@UiTest` | the OBS-02 turn-detail test — after a turn, assert the next turn starts with an empty step list | ❌ Wave 0 (covered by `ChatPanelFragmentTurnDetailTest`) |
| TEST-19 | The Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` regexes find no match in the rendered status line (all `ActivityKind` values) or the per-turn disclosure rows (incl. an `ERROR` step); a deliberately-routed tool/entity name trips the assertion | `@UiTest` (or `@SpringBootTest` for the `HostPrefixPatternProvider` bean) | `./gradlew test --tests "com.vn.agent.view.chat.ObservabilityLeakTest"` | ❌ Wave 0 — `ObservabilityLeakTest` |
| TEST-19 (support) | The `kind → msg://` mapper is label-only (pure-function contract) | plain JUnit | `./gradlew test --tests "com.vn.agent.view.chat.fragment.TurnDetailRendererTest"` | ❌ Wave 0 — `TurnDetailRendererTest` (Vaadin-free pure-fn test, mirrors `RenderStreamEventTest`) |

### Sampling Rate
- **Per task commit:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.view.uisettings.*" --tests "com.vn.agent.orchestration.*" --tests "com.vn.agent.guard.*"` (the touched packages — chat UI, surfaces, streaming, leak guards).
- **Per wave merge:** `./gradlew :ai-agent:ai-agent:test` (full add-on test module — catches the sealed-`switch` exhaustiveness fallout and any chat-flow regression).
- **Phase gate:** `./gradlew test` (whole composite) green before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `ObservabilityLeakTest.java` — TEST-19; reuses `ToolNamePatternProvider` / `HostPrefixPatternProvider` `asPattern()` regexes against rendered status-line + `<details>` text.
- [ ] `TurnDetailRendererTest.java` — plain JUnit; locks the `kind → msg://` label-only mapper contract (Vaadin-free, like `RenderStreamEventTest`).
- [ ] `ChatPanelFragmentStatusLineTest.java` (under `view/chat/fragment/`) — `@UiTest`; drives a stub `Flux<StreamingEvent>` emitting `Activity` + `Final`, asserts the status `<span>` appears keyed by kind and is removed on terminal event, and is never inside a `MessageListItem`.
- [ ] `ChatPanelFragmentTurnDetailTest.java` — `@UiTest`; ≥1-step turn → `<details opened=false>` with N-step summary + label rows + per-step ms + `ERROR` indicator; 0-step turn → no `<details>`; step list cleared after the turn.
- [ ] `ChatPanelFragmentTurnDetailHistoryTest.java` — `@UiTest` + seeded `AiAuditEvent` rows; navigate away+back, expand a history turn's `Details`, assert lazy `AiAuditEvent`-by-`runId` read renders the same label-only rows and a second expand does not re-query (SELECT-count or call-count proxy).
- [ ] `NoNewPersistedStateTest.java` — structural; asserts no Phase-15 `@Entity` added and `agentstore-changelog.xml` / `changelog.xml` unchanged (or a documented `git diff --stat` proxy in the plan).
- [ ] `ObservabilityMessagesCompletenessTest.java` (or extend an existing locale test) — every new `msg://` key resolves in `messages_en.properties` AND `messages_vi.properties`.
- [ ] (optional) `SidebarSharesSingleFragmentTest.java` — asserts the sidebar `Div` hosts the same `ChatPanelFragment` instance the mounter created (no second fragment) — or fold this assertion into `ChatSurfaceMounterTest`.
- [ ] Existing-test extensions (not gaps, but tracked): `ChatSurfaceMounterTest` (+ SIDEBAR mount/toggle/persistence cases), `AiUiSettingsDetailViewTest` (+ SIDEBAR selectable), `ChatPanelFragmentSurfaceSwitchTest` (+ SIDEBAR continuity), `AiChatSessionStateTest` (+ bounded-state assertion), `ChatDialogViewTest` (+ sidebar/dialog independence if needed).

## Security Domain

`security_enforcement` is enabled (`security_block_on: high`, ASVS L1). This phase is a UI/observability surface — most ASVS categories are not directly engaged, but two are:

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Surface mounting/visibility uses the existing `AccessManager`/`UiShowViewContext` gate (`isDialogViewPermitted`); SIDEBAR should gate the same way (only mount/show for chat-permitted users). |
| V3 Session Management | partial | The sidebar `Div`, the bounded step list, and the `Details` open state are per-UI/per-fragment (correct scope); `AiChatSessionState` stays VaadinSessionScope with no new mutable state (OBS-04). No new cookies/tokens. |
| V4 Access Control | yes | The on-expand `AiAuditEvent` read must respect Jmix row-level security (use `DataManager` if the chat user's role allows reading their own audit rows; else `UnconstrainedDataManager` with an explicit `userUsername` filter — never an unconstrained "read any user's audit" path). Open Question 1. |
| V5 Input Validation | partial | The only "input" is the closed `ActivityKind` enum from the streaming flux (server-originated) and the `runId` of a turn (server-originated UUID) — no user free-text reaches the new rendering. Status/disclosure text is from `msg://` keys + server-side timing/outcome only. The `Details` summary uses `MessageFormat` with integer args (step count, ms) — safe; do not interpolate any tool/entity name. |
| V6 Cryptography | no | None. |

### Known Threat Patterns for {Jmix Flow UI + Spring AI streaming}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Internal `@Tool` method name / raw host-entity name leaking into the streaming-status line or per-turn disclosure (the headline threat for this phase) | Information disclosure | Closed `ActivityKind` enum + `kind`-keyed `msg://` mapper (structural — the rendering path's input alphabet can't contain a tool/entity name); TEST-19 enforces with the Phase 9 leak regexes; never pass `ToolResult.toolName`/`argsJson`/`resultSummary` or `AiAuditEvent.eventName`/`argumentsJson`/`resultSummary`/`queryText` into a rendered label. |
| HTML injection via a rendered label (e.g. a filename or model output reaching a status/disclosure element) | Tampering / XSS | Use Vaadin `Element.setText(...)` / `Span(text)` (HTML-escaped) — never `setProperty("innerHTML", ...)` or `MarkdownRenderer` on these elements; the NOTICE-row precedent (`appendNoticeRow` uses `notice.setText(text)`) is correct. Status/disclosure text is `msg://` + integers only anyway. |
| Cross-user audit-row exposure via the on-expand query | Elevation of privilege / Information disclosure | Constrained `DataManager` (row-level security) OR `UnconstrainedDataManager` with a mandatory `where e.userUsername = :currentUser` clause; never load `AiAuditEvent` by `runId` alone with `UnconstrainedDataManager` and no owner filter. |
| Observability emit (`Activity(...)`) failing and breaking the chat turn or RAG retrieval | Denial of service | Wrap every `sink.tryEmitNext(new StreamingEvent.Activity(...))` in `try { } catch (RuntimeException ignored) { }` — exactly as `ToolCallbackAuditDecorator.emitToolEvent` and `AuditingDocumentRetriever`'s audit writes already do; observability is best-effort. |
| Side-panel `Div` mounted for a user without chat permission | Broken access control | Gate the SIDEBAR mount (and the navbar toggle) on the same `AccessManager`/`UiShowViewContext` check the HEADER_BUTTON dialog uses (`isDialogViewPermitted()`), AND on `AiUiSettings.getEnabledSurfaceSet().contains(SIDEBAR)`. |

## Sources

### Primary (HIGH confidence)
- Codebase (read directly this session): `ChatSurfaceMounter.java`, `AiChatSurface.java`, `AiChatUIState.java`, `AiChatSessionState.java`, `ChatPanelFragment.java`, `chat-panel-fragment.xml`, `StreamEventRenderer.java`, `StreamingEvent.java`, `StreamingSinkHolder.java`, `AuditingDocumentRetriever.java`, `ToolCallbackAuditDecorator.java`, `AuditWriter.java` (partial), `AiAuditEvent.java`, `AiUiSettings.java`, `AiToolCallOutcome.java`, `AuditKind.java`, `ToolNamePatternProvider.java`, `HostPrefixPatternProvider.java`, `AiUiSettingsDetailView.java`, `AiUiSettingsService.java`, `ChatDialogView.java`, `chat-dialog-view.xml`, `chat-view.xml`, `ai-agent-chat.css`, `MessageBubbleComponent.java` (@CssImport), `ChatSurfaceMounterTest.java`, `ToolNameLeakScannerTest.java`, `ChatPanelFragmentSurfaceSwitchTest.java` (partial), `AiUiSettingsDetailViewTest.java` (partial).
- Planning docs: `15-CONTEXT.md`, `15-SPEC.md`, `ROADMAP.md` (Phase 15 section), `PROJECT.md`, `.planning/config.json`.
- Project memory (applied, not re-derived): `feedback_jmix_loadvalue_store.md`, `feedback_jmix_messages_over_spring.md`, `feedback_jmix_first_ui.md`, `feedback_jmix_upload_receiver_deprecated.md`, `feedback_jmix_view_listeners.md`, `project_local_dev_port.md`.
- Context7 `/jmix-framework/jmix-context7` — "Apply Theme Variants to Details Component" (confirms `Details` is in Jmix's accepted Vaadin set: `<details>` XML element, `setOpened`, `setSummaryText`, theme variants `filled`/`reverse`/`small`); "Application theme directory structure" / "Main stylesheet with import directives" (confirms how Jmix themes load CSS — not needed here since the project uses `@CssImport` on a packaged stylesheet, but documents the alternative).

### Secondary (MEDIUM confidence)
- (none — all claims verified against codebase or Context7.)

### Tertiary (LOW confidence)
- A1 (Lumo navbar-height CSS variable availability) and A2 (`AppLayout` navbar `position:relative`) — assumed from general Vaadin Lumo knowledge; flagged in the Assumptions Log; planner verifies during UI verification. D-01 already anticipates the fallback ("a fixed `top`").

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new libraries; every reused API confirmed present in the codebase; `Details` confirmed via Context7.
- Architecture: HIGH — the mount lifecycle, NOTICE-row pattern, sealed-variant additivity, and `AiAuditEvent` shape are all read directly; the only MEDIUM-ish item is the exact CSS docking behaviour against an unknown host shell (A2), which D-01 already designed around.
- Pitfalls: HIGH — derived from reading the actual code paths (`AppLayout` content-slot replacement, `RunContext`/sink keying, `@Store("agentstore")` + `loadValue` store inference, sealed-`switch` exhaustiveness).
- Validation: HIGH — the test harness, the leak-provider construction pattern, and the existing chat `@UiTest`s are all read directly; the Wave 0 gaps are concrete file names mirroring existing tests.

**Research date:** 2026-05-11
**Valid until:** ~2026-06-10 (30 days — stable internal codebase; Jmix 2.8 / Vaadin 24.8 / Spring AI 1.1.x pinned. Re-check if the chat subsystem or `StreamingEvent` shape changes before planning.)
