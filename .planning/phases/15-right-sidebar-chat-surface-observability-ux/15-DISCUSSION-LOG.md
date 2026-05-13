# Phase 15: Right-Sidebar Chat Surface & Observability UX - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-11
**Phase:** 15-right-sidebar-chat-surface-observability-ux
**Areas discussed:** Shell-level side-panel hosting, KIND source for the streaming-status line, Per-turn tool-detail disclosure (data + component), Status line + sidebar-toggle UI
**Mode:** advisor (USER-PROFILE.md present; vendor philosophy = thorough-evaluator → full_maturity calibration; 4 parallel `gsd-advisor-researcher` agents spawned, one per selected area)

---

## Shell-level side-panel hosting

| Option | Description | Selected |
|--------|-------------|----------|
| A — opt-in `AiAgentMainView extends StandardMainView` (XML) wrapping the work-area in `sidePanelLayout` | Pure Jmix-idiomatic, the SPEC's preferred realization; but forces every host to ship a custom main view (can't be zero-config) and needs a `showRouterLayoutContent()` override | |
| B — `ChatSurfaceMounter` wraps AppLayout content in `sidePanelLayout` at runtime, re-wraps on every `AfterNavigationEvent` | Works through the existing mounter pattern (zero host config); still uses the real `sidePanelLayout`; but fights the Vaadin router (`setContent` replaces the wrapper on every nav) with no Jmix precedent | |
| C — `position:fixed` docked `Div` hosting the shared fragment + CSS `padding-right` "push" class + small-device media-query overlay | Bulletproof on nav-survival & no-curtain; shell-agnostic; no dependency on `sidePanelLayout` shell-hosting working; not the SPEC's preferred realization (explicitly an allowed fallback); needs the "raw CSS w/ justification" note | ✓ |
| D — modeless right-pinned `DialogWindow` (reuse the existing HEADER_BUTTON realization shape) | Smallest delta (repo already runs a modeless nav-surviving `DialogWindow`); but an overlay floats rather than pushes — weakest on the "docked push side panel" intent | |

**User's choice:** C only — skip `sidePanelLayout`, ship the CSS-push `Div`.
**Notes:** This overrides the SPEC's "Preferred SIDEBAR realization" constraint, but lands in its explicit "Open implementation risk" clause: Jmix docs only ever use `sidePanelLayout` inside a single view over non-routed content; there's no documented support for it wrapping the router outlet, and `AppLayout.showRouterLayoutContent()` `setContent()`-replaces the content slot on every navigation. The non-modal fallback is explicitly sanctioned. In-panel closer becomes a plain close `JmixButton` in the panel header ("`sidePanelLayoutCloser` or equivalent"). Width/breakpoint left as a planner detail (starting point ≈32% desktop / min ≈420px / full-screen overlay below ~768px).

---

## KIND source for the ephemeral streaming-status line

| Option | Description | Selected |
|--------|-------------|----------|
| B — one additive `StreamingEvent.Activity(ActivityKind kind)` variant | Emitted from `AuditingDocumentRetriever` (RETRIEVAL) / the tool-callback decorator (TOOL) / stream assembly (CHAT); only option where all 3 KINDs are real-time & accurate; emit sites outside `ChatService`/advisor chain and don't touch persisted `AiAuditEvent`; SPEC explicitly sanctions it; closed enum → no-leak is structural; ~4-6 files | ✓ |
| A — derive KIND in the UI only (`Content→CHAT`, `ToolCall/ToolResult→TOOL`, `Citation→RETRIEVAL`) | Zero backend change; but RETRIEVAL is not observable from existing variants (RAG runs before the model call; `Citation` arrives after) → the "retrieving documents…" state can't be shown truthfully; degrades to neutral / thinking… / searching data… | |
| C — UI lazily reads the in-flight `AiAuditEvent` subtree KIND | Reuses the audit tree; but couples the UI render loop to the persisted store mid-turn, inherits A's latency inversion (rows written in `finally`, after the activity), adds DB round-trips + polling, puts the leak boundary next to `retrievalHitsJson`; not recommended | |

**User's choice:** B — additive `StreamingEvent.Activity(ActivityKind)` variant.
**Notes:** Decisive fact from research — RETRIEVAL is genuinely not observable from the existing `StreamingEvent` set, so option A's `Citation→RETRIEVAL` mapping fires at the wrong end of the turn. The SPEC pre-authorizes "a new additive `StreamingEvent` variant to surface the current `KIND`." Closed `ActivityKind` enum keeps the no-leak guarantee structural; additive change = one new `permits` entry + one compiler-enforced `switch` arm everywhere. Option A retained in CONTEXT.md as the explicit fallback if scope must shrink.

---

## Per-turn tool-detail disclosure — data strategy (UI component = Vaadin `Details` either way)

| Option | Description | Selected |
|--------|-------------|----------|
| A — hybrid: flux accumulates the live turn's bounded step list (cleared on `Final`/`Error`); history turns lazily re-query `AiAuditEvent` children by `runId` on expand (narrow fetch plan, `.store("agentstore")`) | Satisfies both invariants ("no unbounded `AiChatSessionState` growth" + "works after fresh navigation"); no new entity (OBS-04); one shared `kind→msg` mapper for no-leak; per-turn user-initiated query so no N+1 | ✓ |
| B — always lazy: re-query `AiAuditEvent` on every expand, even for the just-completed turn | One code path, zero state, trivially bounded; but the just-completed turn may not have all audit children flushed at expand time (write-vs-UI race) → empty/partial for the most common interaction; every expand is a DB hit | |
| C — always from flux: capped `Map<runId,List<StepRow>>` in fragment/session, no audit read | Single source, instant expand, no DB cost; but fails the fresh-navigation requirement — a reload rebuilds the fragment with an empty map, so older turns show nothing; breaks a stated invariant | |

**User's choice:** A — hybrid.
**Notes:** UI component decided by research as clear-cut and folded into the recommendation: Vaadin `Details` (`setOpened(false)`, summary "what the agent did — N steps · total ms", content = `VerticalLayout` of label-only step rows + per-step ms + error/rollback icon), one per assistant turn appended after its `MessageListItem` (anchored by `runId`), omitted entirely at 0 steps, open-state component-local (naturally session-only). `Accordion`/custom `<div>` rejected.

---

## Streaming-status-line component & placement (sets the in-fragment status-row pattern Phase 20's STT row reuses)

| Option | Description | Selected |
|--------|-------------|----------|
| `Span` + CSS three-dot pulse, sibling div in `messageListSlot` after `<vaadin-message-list>` (the existing NOTICE-row pattern); `role="status"` + `aria-live="polite"`; `prefers-reduced-motion` guard; removed entirely on `Final`/`Error`; never inside a `MessageListItem` | Reuses an established codebase pattern; Phase 20's STT error/retry row inherits the mechanism for free; lowest surprise | ✓ |
| Vaadin indeterminate `ProgressBar` + label in a new dedicated `<vbox>` slot between `messageListSlot` and `intentCardRow` | Maximally idiomatic Vaadin with built-in ARIA; but reads as a "page is loading" bar, is visually heavier, and adds a fragment slot all three surfaces must honor | |
| Custom `<div>` with bespoke typing-indicator markup in a dedicated slot | Full control; but the most custom CSS to own, no ARIA for free, least idiomatic — strictly worse than the `Span`-in-`messageListSlot` option | |

**User's choice:** `Span` + CSS three-dot pulse, sibling div in `messageListSlot` after `<vaadin-message-list>`.
**Notes:** Two related sub-decisions were presented as recorded defaults (not separately asked; user did not override): (1) sidebar navbar toggle = `VaadinIcon.PANEL` + `aria-pressed` + an "active" CSS class, built via the same `uiComponents.create(JmixButton.class)` pipeline as `aiAgentHeaderChatButton`, with the in-panel closer routing through the same toggle method; (2) the panel starts closed — `defaultSurface=SIDEBAR` means "the sidebar toggle is mounted" (the `HEADER_BUTTON` analogue), no auto-open on login/navigation. Once-per-session auto-open and `localStorage`-persisted state deferred.

---

## Claude's Discretion

- Exact panel width / `min-width` / small-device breakpoint for the `position:fixed` realization (starting point recorded — planner refines; design-conscious owner may revisit during UI verification).
- Exact button id / CSS class names / `msg://` key names (follow existing naming conventions).
- Whether `Activity(CHAT)` is emitted explicitly vs "no Activity yet ⇒ neutral, first `Content` ⇒ thinking…" — planner decides on emit-ordering grounds; only RETRIEVAL and TOOL must be explicitly signalled.
- Exact shape of the bounded live-turn step accumulator (capped list vs current-turn-only).

## Deferred Ideas

- Chat-state side panel (model/conversation/governance/attachment-budget facts) → `OBS-FUT-01` (already scoped out in SPEC/ROADMAP).
- `AiAuditEventListView?runId=...` deep-link from a turn — descoped from OBS-02 for Phase 15.
- `SIDEBAR` panel auto-open behaviour (once-per-session flag or `localStorage`-persisted state) — Phase 15 ships "starts closed".
- Optional `AiAgentMainView extends StandardMainView` wrapping the work-area in `sidePanelLayout` for hosts wanting the pure-XML realization — not built in Phase 15.
- Doc-sync follow-ups (not code): REQUIREMENTS.md OBS-02 + ROADMAP.md Phase 15 success-criterion 3 still carry the descoped `?runId=` deep-link; REQUIREMENTS.md/ROADMAP.md/SPEC.md describe `sidePanelLayout` as the realization — decision D-01 supersedes that. Update these docs when the phase ships.
