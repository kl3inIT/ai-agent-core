# Phase 15: Right-Sidebar Chat Surface & Observability UX - Context

**Gathered:** 2026-05-11
**Status:** Ready for planning

<domain>
## Phase Boundary

A third `SIDEBAR` chat surface — a non-modal, **push-mode** right side panel hosted at the `MainView`/`AppLayout` shell level that stays open across route navigation while the routed main view stays fully interactive — over the **single shared** `ChatPanelFragment`; plus, inside that fragment, an ephemeral `KIND`-keyed streaming-status line and a collapsed-by-default per-turn "what the agent did" disclosure; plus a UI-layer leak test reusing the Phase 9 pattern packs. No internal `@Tool`/entity names ever reach the UI. No new persisted state, no new entity/table/changelog, no second `ChatService`/chat memory/`ChatPanelFragment`. Zero behaviour change to the existing `FULL_ROUTE`/`HEADER_BUTTON` surfaces and the `ChatPanelFragment` slot-id contract.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**6 requirements are locked.** See `15-SPEC.md` for full requirements, boundaries, and acceptance criteria — SURF-11 (SIDEBAR surface), SURF-11/launch affordance, OBS-01 (ephemeral streaming-status line), OBS-02 (per-turn tool-detail disclosure, deep-link descoped), OBS-04 (no new persisted state; bounded session state), TEST-19 (UI-layer leak test).

Downstream agents MUST read `15-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- `AiChatSurface.SIDEBAR` enum value; `SIDEBAR` selectable in the admin UI-settings enabled-surface control and the `defaultSurface` selector; any default-seed `AiUiSettings` data adjustment (string columns — no schema change expected)
- `ChatSurfaceMounter` extension: mount a non-modal right side panel at the shell level hosting the shared `ChatPanelFragment`, plus a distinct far-right navbar toggle button with active/closed state; in-panel closer
- The shared `ChatPanelFragment` rendered inside the side panel — single instance, same `ChatService`/chat memory, no duplicate fragment
- Ephemeral streaming-status line in a sibling slot of the message list, KIND-keyed, clears on finalize
- Collapsed-by-default per-turn tool-detail disclosure: label-only steps, per-step timing, error/rollback indicator, hidden on zero-tool turns, session-only expand/collapse state
- New `msg://` keys for all status/disclosure/surface/toggle labels in `messages.properties` and every `messages_*.properties` (currently `en` + `vi`)
- TEST-19 UI-layer leak test reusing the Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` pattern packs; updates/additions to `ChatSurfaceMounterTest` / `ChatDialogViewTest` and new tests for the SIDEBAR mount, toggle, and persistence-across-navigation
- Moving the `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo to `done/` when the phase ships

**Out of scope (from SPEC.md):**
- The chat-state side panel (active model / conversation id+title / LLM-exposure flag / mutation-tools flag / attached-file count / token-budget usage / last-turn summary) — deferred to `OBS-FUT-01`
- The `AiAuditEventListView?runId=...` deep-link from a turn — descoped from OBS-02 for Phase 15; labels only, no link, no audit-view changes
- Any new persisted "turn" entity / table / Liquibase changelog / parallel state store
- Streaming tool *output* / payloads / interim results into the status line — KIND-keyed labels only
- Persisted per-user expand/collapse preference for the disclosure — session-only state only
- Mobile-specific redesign of `ChatPanelFragment` internals — existing fragment layout reused as-is; only the side panel's small-screen overlay behaviour is configured
- Model-picker / curated catalog (Phase 16), config-knob migration (Phase 17), mutation-internals hardening (Phase 18), AI-runtime perf pass (Phase 19), voice input / STT (Phase 20)
- Changing the existing `FULL_ROUTE` / `HEADER_BUTTON` flows, the `ChatSurfaceMounter` slot contract, or the `ChatPanelFragment` slot ids (`messageListSlot`, `messageInputSlot`, `attachmentsPanel`)

</spec_lock>

<decisions>
## Implementation Decisions

### SIDEBAR surface realization (SURF-11)
- **D-01: Skip Jmix `sidePanelLayout`. Ship a `position:fixed` docked element instead.** `ChatSurfaceMounter` appends a `Div`/wrapper hosting the **shared** `ChatPanelFragment` (`position: fixed; right: 0; top: <navbar-h>; bottom: 0; width: <panel-width>`) onto a stable host (the `UI` element or the AppLayout content), and toggles a CSS class on the work-area / `AppLayout` content that sets `padding-right`/`margin-right` so the routed content reflows ("push" mode). A small-device media query collapses the panel to a full-screen overlay (`displayAsOverlayOnSmallDevices` behaviour, re-implemented in CSS). The in-panel closer is a plain close `JmixButton` in the panel header (the `sidePanelLayoutCloser` "or equivalent" path).
  - **Why this overrides the SPEC's "Preferred SIDEBAR realization":** the SPEC's "Open implementation risk" clause explicitly anticipated this — Jmix docs/samples only ever use `sidePanelLayout` *inside a single view over non-routed content*; there is no documented support for it as a child of `appLayout` or wrapping the Vaadin router outlet, and `AppLayout.showRouterLayoutContent()` `setContent()`-replaces whatever sits in the content slot on every navigation (you'd be re-wrapping it on every `AfterNavigationEvent`, racing the framework, with no Jmix precedent). The SPEC sanctions this: *"an alternative non-modal realization is acceptable."* A fixed-position docked `Div` is the most predictable realization across unknown host shells (plain `StandardMainView`/`AppLayout`, tabbed mode, custom main views) and survives route navigation by construction (it lives on the UI element, not the content slot).
  - **Justification note (Jmix-first UI convention):** this introduces hand-rolled CSS positioning + a small project CSS file rather than a Jmix layout component. That is a deliberate, justified exception — there is no Jmix component that does shell-level non-modal push docking around the router outlet. The fragment *body* is still the existing Jmix-built `ChatPanelFragment`; only the shell wrapper is raw.
  - **Planner detail (starting point, not locked):** desktop panel width ≈ 32% of viewport with a `min-width` ≈ 420px (the right-pane attachment cards inside `ChatPanelFragment` need ~200px); full-screen overlay below the small-device breakpoint (~768px). Use the navbar-height CSS variable if available; otherwise a fixed `top`.
  - **Constraints carried:** non-modal (no modality curtain — main view stays clickable); single shared `ChatPanelFragment` (no second `ChatService`, no second chat memory, no duplicate fragment); `SIDEBAR` participates in `AiUiSettings.enabledSurfaceIds`/`defaultSurface` and the admin enabled-surface control; with `SIDEBAR` disabled, no panel and no toggle are mounted (absent, not greyed); `ChatPanelFragment` slot ids unchanged.

### SIDEBAR launch affordance (SURF-11)
- **D-02: Distinct far-right navbar toggle button, independent of the `HEADER_BUTTON` magic-icon button.** Built via the same pipeline `ChatSurfaceMounter` already uses for `aiAgentHeaderChatButton`: `uiComponents.create(JmixButton.class)` → `setId("aiAgentSidebarToggleButton")` (id TBD by planner, must be distinct) → add a CSS class → `setIcon(VaadinIcon.PANEL.create())` (clearly reads as "right side panel"; unmistakably ≠ `VaadinIcon.MAGIC`) → `addThemeVariants(LUMO_TERTIARY, LUMO_ICON)` (matches the header button) → `aria-label` from a `msg://` key (distinct open/closed labels) → click listener that toggles the panel.
- **D-03: Open/closed state shown via `aria-pressed` (`true`/`false`) + a small project "active" CSS class** on the toggle button (Lumo has no built-in "pressed" button variant). Both the navbar toggle and the in-panel closer route through **one** toggle method so state never drifts. When both `SIDEBAR` and `HEADER_BUTTON` are enabled, both navbar buttons are present and operate independently.
- **D-04: The panel starts CLOSED.** `defaultSurface=SIDEBAR` means only "the sidebar toggle is mounted" — the direct analogue of `HEADER_BUTTON` ⇒ button present, dialog closed. No auto-open on navigation/login (avoids a ~30%-viewport shift on every login; keeps the three `defaultSurface` meanings parallel: "menu item present" / "navbar button present" / "navbar toggle present"). Once-per-session auto-open and `localStorage`-persisted open state are deferred (see Deferred Ideas).

### Streaming-status line (OBS-01)
- **D-05: KIND source = a new additive `StreamingEvent.Activity(ActivityKind kind)` variant.** Emit it from the orchestration edge — `AuditingDocumentRetriever.retrieve(...)` emits `Activity(RETRIEVAL)` at the start of retrieval (via the existing streaming sink holder); the tool-callback audit decorator emits `Activity(TOOL)`; stream assembly emits `Activity(CHAT)` before the model stream. `ActivityKind` is a **closed enum** ({`CHAT`,`TOOL`,`RETRIEVAL`}) → the no-leak guarantee is structural (only an enum constant ever crosses to the UI; the fragment maps it to a KIND-keyed `msg://` string and never touches `toolName`/`argsJson`).
  - **Why not "derive in the UI from existing variants":** RETRIEVAL is **not observable** from the existing `StreamingEvent` set — RAG runs in `AuditingDocumentRetriever.retrieve(...)` *before* the model call, and `Citation` events arrive with/after the final answer, so a `Citation→RETRIEVAL` mapping fires at the wrong end of the turn. The SPEC explicitly pre-authorizes "a new additive `StreamingEvent` variant to surface the current `KIND`" for exactly this case. (Fallback if scope must shrink: derive `Content→CHAT` / `ToolCall|ToolResult→TOOL` in the UI and drop the "retrieving documents…" state — but that is a scope cut, not the plan.)
  - **Constraint:** the emit sites are *outside* `ChatService`, the advisor chain proper, and the persisted shape of `AiAuditEvent` — observability rendering must not change those. Adding the variant is additive: one new entry in the `StreamingEvent` sealed `permits` clause + one new arm in every exhaustive `switch` (compiler-enforced).
- **D-06: Component & placement = a `Span` with a CSS three-dot/pulse animation, appended as a sibling `<div>`-style element inside `messageListSlot` AFTER the `<vaadin-message-list>` block** — the existing NOTICE-row pattern in `ChatPanelFragment` (plain inline sibling elements after the message list, not inside any `MessageListItem`/`MessageBubbleComponent`). Set `role="status"` + `aria-live="polite"`. Guard the animation with a `prefers-reduced-motion` media query. On the terminal `Final`/`Error` event the status line is **removed entirely** (not just blanked). Labels: humanized, label-only, KIND-keyed — `CHAT`→"thinking…", `TOOL`→"searching data…", `RETRIEVAL`→"retrieving documents…", neutral typing indicator when no `ActivityKind` has arrived yet. All strings from `msg://` keys in `messages.properties` AND `messages_vi.properties`. Status text is never concatenated into the assistant `MessageListItem` bubble.
  - **Reuse note:** this is the in-fragment status-row mechanism Phase 20's STT error/retry row will reuse — keep it generic enough to host that later.

### Per-turn tool-detail disclosure (OBS-02, OBS-04)
- **D-07: Data strategy = hybrid.** For the in-flight / just-completed turn, accumulate a per-turn step list from the `StreamingEvent` flux the fragment already consumes; keep it **bounded** — current/last turn only (or a small capped collection), cleared on `Final`/`Error` — so nothing accumulates unbounded in `AiChatSessionState`. For any turn rendered after a **fresh navigation** to the chat surface, lazily `DataManager`-query the `AiAuditEvent` TOOL/RETRIEVAL children for that one `runId` **on expand** — a user-initiated, per-turn read with a narrow fetch plan (`kind`, `startedAt`/`durationMs`, `outcome` only) and `.store("agentstore")` (raw-JPQL `loadValues` does not infer the store for agentstore entities — project memory). No new persisted "turn" entity, no parallel store (OBS-04).
- **D-08: UI component = Vaadin `Details`.** One `Details` per assistant turn, `setOpened(false)` (collapsed by default), `summary` = humanized "what the agent did — N steps · total ms", content = a `VerticalLayout` of label-only step rows with per-step ms + an error/rollback indicator where applicable. Appended as a sibling after that turn's `MessageListItem`, anchored by `runId` (same insertion order re-established on history re-render). The whole `Details` is **omitted entirely** when step count == 0 (not a "0 steps" row). Open/collapse state is component-local → naturally session-only; nothing persisted. Both the live and historical paths funnel through **one** `kind → msg://` mapper that emits only KIND-keyed localized labels (en + vi) — never a tool or entity name. No `?runId=` deep-link (descoped). `Accordion`/custom `<div>` rejected (accordion's single-open semantics fight per-turn independence; a custom div re-implements a11y `Details` already provides; `Details` ships within Jmix's accepted Vaadin set).

### TEST-19 — UI-layer leak test
- **D-09:** Reuse the Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` pattern packs (do not fork new pattern definitions) against the *rendered text* of (a) the streaming-status line and (b) the per-turn tool-detail disclosure, across the supported `ActivityKind` values and an errored/rolled-back step; assert no `@Tool` method name and no raw entity name appears. The test must fail if a tool/entity name is deliberately routed through the status/disclosure rendering path.

### Claude's Discretion
- Exact panel width / `min-width` / small-device breakpoint for D-01 (starting point given above — planner refines, design-conscious owner may revisit during UI verification).
- Exact button id, CSS class names, and `msg://` key names (follow existing naming: `aiAgentHeaderChatButton`, `chatSurfaceMounter.headerButton.ariaLabel`, etc.).
- Whether `Activity(CHAT)` is emitted explicitly or "no Activity yet ⇒ neutral, first `Content` ⇒ thinking…" — planner decides based on emit-ordering cleanliness; D-05 only requires RETRIEVAL and TOOL be explicitly signalled.
- Exact shape of the bounded live-turn step accumulator (capped list vs current-turn-only) — D-07 only requires "bounded, cleared on terminal event".

### Folded Todos
- **Add collapsible tool-detail and ephemeral status to chat UI** (`.planning/todos/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md`) — original ask: per-turn collapsible tool-detail panel + ephemeral streaming-status indicator in the chat UI. Already folded into the SPEC scope (OBS-01 + OBS-02) and covered by decisions D-05..D-08. Move the todo file to `.planning/todos/done/` when Phase 15 ships (also called out in SPEC.md "In scope").

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase 15 spec & roadmap
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/15-SPEC.md` — **locked requirements** (SURF-11, OBS-01/02/04, TEST-19), boundaries, constraints, acceptance criteria. MUST read before planning. NOTE: its "Preferred SIDEBAR realization" constraint (`sidePanelLayout`) is **superseded by decision D-01** (use a `position:fixed` docked Div instead) — the SPEC's "Open implementation risk" clause explicitly allows this.
- `.planning/ROADMAP.md` → "Phase 15: Right-Sidebar Chat Surface & Observability UX" — goal, depends-on (`ChatSurfaceMounter`, `AiUiSettings`, `AiChatSessionState`, `ChatPanelFragment`, the `StreamingEvent` flux, the `AiAuditEvent` tree), success criteria. NOTE: ROADMAP success-criterion 3 still mentions the `AiAuditEventListView?runId=...` deep-link — that clause is **descoped for Phase 15** per the SPEC (doc-sync item, see Deferred Ideas).
- `.planning/REQUIREMENTS.md` → OBS-02 — still carries the `AiAuditEventListView?runId=...` deep-link clause; **superseded for Phase 15** by the SPEC (doc-sync item).
- `.planning/PROJECT.md` — milestone v1.2 context; "Right-sidebar chat surface & observability UX" target feature; "no new core dependencies" constraint.

### Existing components this phase extends (source files)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java` — the `VaadinServiceInitListener` to extend; already does the AppLayout tree-walk, `addToNavbar(...)`, the `JmixButton` creation pattern, modeless `DialogWindow` reattach-on-navigation, `AfterNavigationEvent` handling, per-UI `MountedChatSurfaceState`, and surface-visibility refresh keyed off `AiUiSettings.getEnabledSurfaceSet()`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java` — `EnumClass<String>`; add the `SIDEBAR` value here.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java` + `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java` + `ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java` — `enabledSurfaceIds`/`defaultSurface` persistence + admin enabled-surface control; `SIDEBAR` must be selectable.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` + `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml` — the single shared panel body; `messageListSlot`/`messageInputSlot`/`attachmentsPanel` slot ids must stay stable; the streaming-status line and per-turn `Details` mount here; existing NOTICE-row pattern (plain `<div class="ai-agent-attachment-notice">` siblings appended after `<vaadin-message-list>` inside `messageListSlot`) is the model for D-06.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java` — sealed interface; add the additive `Activity(ActivityKind kind)` variant (one new `permits` entry + one new `switch` arm everywhere `StreamingEvent` is matched).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java` (and the RAG retriever + tool-callback audit decorator on the streaming path) — emit sites for `Activity(...)`; these are outside `ChatService`/the advisor chain.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java` — the durable audit tree (CHAT root per `runId` + TOOL/RETRIEVAL children, `KIND` ∈ {CHAT,TOOL,RETRIEVAL}, per-event timing/outcome) — the lazy re-query source for D-07; query with `.store("agentstore")`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java` + `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java` + `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiChatSessionState.java` — existing surfaces / cross-surface continuity; must keep working unchanged.

### Existing tests to update / model new ones on
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java`, `.../ChatDialogViewTest.java`, `.../ChatPanelFragmentSurfaceSwitchTest.java`, `.../AiUiSettingsDetailViewTest.java`, `.../AiChatSessionStateTest.java` — extend for the SIDEBAR mount, the navbar toggle, persistence-across-navigation, and the new enum value.
- Phase 9 leak-guard pattern-pack definitions (`HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK`) — reused verbatim by TEST-19; do not fork.

### Project memory (apply, don't re-derive)
- `feedback_jmix_first_ui.md` — raw Vaadin/CSS only with explicit justification; D-01's `position:fixed` shell wrapper carries that justification (no Jmix component does shell-level non-modal push docking around the router outlet); the fragment body stays Jmix-built.
- `feedback_jmix_upload_receiver_deprecated.md`, `feedback_jmix_messages_over_spring.md`, `feedback_jmix_loadvalue_store.md` (`.store("agentstore")` for `AiAuditEvent` raw-JPQL), `feedback_jmix_first_ui.md`, `feedback_jmix_view_listeners.md`, `feedback_rich_tool_descriptions.md` (N/A here — no new tools), `project_local_dev_port.md` (app runs on :8088 — don't auto-start).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ChatSurfaceMounter` — full machinery for: walking the UI tree to find `AppLayout`/`StandardMainView`, `addToNavbar`, the `JmixButton` creation+theming+aria-label pattern, per-UI mounted-state bookkeeping, `AfterNavigationEvent` re-sync, modeless `DialogWindow` create + reattach-on-navigation, surface-visibility toggling keyed off `AiUiSettings.getEnabledSurfaceSet()`, and a "warn once if host main view isn't an `AppLayout`" guard. The SIDEBAR mount + navbar toggle slot into the same lifecycle.
- The existing NOTICE-row pattern in `ChatPanelFragment` (`<div class="ai-agent-attachment-notice">` siblings appended after `<vaadin-message-list>` inside `messageListSlot`) — the exact template for the streaming-status `Span` (D-06) and the per-turn `Details` placement (D-08).
- `StreamingEvent` sealed interface + the `Flux<StreamingEvent>` already consumed by `ChatPanelFragment` — the live data source for the streaming-status line and the live-turn step accumulator; extend it additively for `Activity`.
- `AiAuditEvent` tree (CHAT root per `runId` + TOOL/RETRIEVAL children with per-event timing/outcome) — already carries everything the disclosure needs; lazy-read by `runId` for history turns.
- `AiUiSettings`/`AiUiSettingsService`/`AiUiSettingsDetailView` — `enabledSurfaceIds`/`defaultSurface` (string columns, no schema change) + the admin enabled-surface control; add `SIDEBAR` to both.

### Established Patterns
- Surfaces are enum values on `AiChatSurface`, gated by `AiUiSettings.getEnabledSurfaceSet()`, mounted/refreshed by `ChatSurfaceMounter` on UI init + every `AfterNavigationEvent`; disabled surface ⇒ nothing mounted (not greyed).
- One shared `ChatPanelFragment` per UI session backing all surfaces — never instantiate a second one, never a second `ChatService`/chat memory.
- All user-visible text via `msg://` keys present in `messages.properties` AND `messages_vi.properties`; inject `io.jmix.core.Messages` in views (per project memory).
- Vaadin components shipped with Jmix (`MessageList`/`MessageListItem`/`ProgressBar`/`Details`) are accepted; genuinely custom UI (the `position:fixed` shell wrapper, CSS push class) needs the justification note — which D-01 carries.
- `@Subscribe`/`@Install` event wiring for view/fragment listeners; raw `addXListener` in `onInit` is discouraged (project memory).

### Integration Points
- `ChatSurfaceMounter` ↔ AppLayout/`StandardMainView` shell — the new `position:fixed` panel wrapper attaches to a stable host (the `UI` element or the AppLayout content) and toggles a CSS push class on the work-area; survives navigation because it's not in the router-outlet content slot.
- `ChatPanelFragment` ↔ `StreamingEvent` flux — new `Activity` variant flows through here for the status line; the live-turn step list is accumulated here and cleared on `Final`/`Error`.
- `ChatPanelFragment` ↔ `AiAuditEvent` (agentstore) — lazy `DataManager` query by `runId` on disclosure expand for history turns.
- New `Activity` emit sites ↔ `StreamingSinkHolder` / RAG retriever / tool-callback audit decorator — all outside `ChatService` and the advisor chain (constraint-safe).
- `AiChatSurface` / `AiUiSettings` / `AiUiSettingsDetailView` — the `SIDEBAR` enum value threads through the persisted `enabledSurfaceIds`/`defaultSurface` strings and the admin control.

</code_context>

<specifics>
## Specific Ideas

- Streaming-status labels (humanized, label-only): `CHAT`→"thinking…", `TOOL`→"searching data…", `RETRIEVAL`→"retrieving documents…", neutral typing indicator before any `ActivityKind` arrives. (`msg://` keys in en + vi.)
- Per-turn disclosure summary phrasing: "what the agent did — N steps · total ms"; expanded rows are KIND-keyed step labels + per-step ms + an error/rollback indicator.
- Sidebar navbar toggle: `VaadinIcon.PANEL` (clearly "right side panel"; unmistakably ≠ the existing `VaadinIcon.MAGIC` header button), `LUMO_TERTIARY` + `LUMO_ICON` theme variants (matches `aiAgentHeaderChatButton`), `aria-pressed` + an "active" CSS class for open state.
- Panel: non-modal, push-mode, starts closed; ≈32% desktop width / `min-width` ≈420px / full-screen overlay below ~768px (starting point); in-panel close button in the panel header.

</specifics>

<deferred>
## Deferred Ideas

- **Chat-state side panel** (active model code / conversation id+title / LLM-exposure flag / mutation-tools flag / attached-file count / token-budget usage / last-turn summary) — already scoped out to `OBS-FUT-01` in the SPEC and ROADMAP. Do not implement in Phase 15.
- **`AiAuditEventListView?runId=...` deep-link from a turn** — descoped from OBS-02 for Phase 15 (the disclosure is label-only, no link). Could return in a later phase if operators ask for the jump-to-audit affordance.
- **`SIDEBAR` panel auto-open behaviour** — once-per-browser-session auto-open (session-scoped flag) or `localStorage`-persisted last open/closed state. Deferred — Phase 15 ships "starts closed" (the `HEADER_BUTTON` analogue); add persistence/auto-open later only if users ask.
- **Optional `AiAgentMainView extends StandardMainView`** that wraps the work-area in `sidePanelLayout` for hosts who want the pure-XML realization — not built in Phase 15 (D-01 ships the shell-agnostic fixed-position approach instead); could be offered as a documented opt-in later if a host wants it.
- **Doc-sync follow-ups (not code):** (1) REQUIREMENTS.md OBS-02 still carries the `?runId=` deep-link clause — the SPEC supersedes it for Phase 15. (2) ROADMAP.md Phase 15 success-criterion 3 also mentions the deep-link — same. (3) Both REQUIREMENTS.md/ROADMAP.md/SPEC.md describe `sidePanelLayout` as the realization — decision D-01 supersedes that with the `position:fixed` docked-Div approach. Update these docs to match (a `/gsd-docs-update`-style pass or a small manual edit when the phase ships).

</deferred>

---

*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Context gathered: 2026-05-11*
