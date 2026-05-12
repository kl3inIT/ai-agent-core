# Phase 15: Right-Sidebar Chat Surface & Observability UX — Specification

**Created:** 2026-05-11
**Ambiguity score:** 0.148 (gate: ≤ 0.20)
**Requirements:** 6 locked

## Goal

Add a third `SIDEBAR` chat surface — a non-modal, push-mode right side panel hosted at the MainView/AppLayout shell level that stays open across route navigation while the routed main view remains usable — and surface, inside the shared `ChatPanelFragment`, an ephemeral KIND-keyed streaming-status line plus a collapsed-by-default per-turn "what the agent did" disclosure, with no internal `@Tool`/entity names ever reaching the UI and no new persisted state.

## Background

Today the add-on ships two chat surfaces (`AiChatSurface.FULL_ROUTE`, `HEADER_BUTTON`): `FULL_ROUTE` is the `/ai-agent/chat` route, `HEADER_BUTTON` is a navbar magic-icon button that toggles a non-modal draggable `DialogWindow` (`ChatDialogView`) — both mount the single shared `ChatPanelFragment` via `ChatSurfaceMounter` (a `VaadinServiceInitListener` that finds the host `AppLayout`/`StandardMainView`). `AiUiSettings` persists `enabledSurfaceIds` (comma-joined ids) + `defaultSurface`, and the admin UI-settings view toggles which surfaces are enabled. There is no persistent right-side chat surface.

While a turn streams, `ChatPanelFragment` only flips a `ProgressBar` and disables the input; any intermediate model prose gets appended into the assistant `MessageListItem` bubble, so operational text ("Để tôi tìm kiếm…") can persist permanently in the final reply. To see what the agent actually did (tool count, latency, the parent/child `AiAuditEvent` tree) an operator must leave chat and open `AiAuditEventListView` manually. The `AiAuditEvent` tree (one CHAT root per `runId` + TOOL/RETRIEVAL children, `KIND` ∈ {`CHAT`,`TOOL`,`RETRIEVAL`}) and the `StreamingEvent` flux (`Content`/`ToolCall`/`ToolResult`/`Citation`/`Final`/`Error`) already carry everything needed — the chat layout just lacks a place to render it. Phase 9 ships output-scanner leak-guard pattern packs (`HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK`) used at the LLM-output layer; there is no UI-layer leak test for chat observability (those surfaces don't exist yet).

This phase was rescoped on 2026-05-11: the originally planned chat-state side panel (OBS-03) is deferred to `OBS-FUT-01`, and a new right-sidebar chat surface (SURF-11) was added. It resolves the pending `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo.

## Requirements

1. **SIDEBAR chat surface (SURF-11)**: A third `SIDEBAR` chat surface mounts the shared `ChatPanelFragment` as a non-modal right side panel at the MainView/AppLayout shell level.
   - Current: `AiChatSurface` has only `FULL_ROUTE` and `HEADER_BUTTON`; `ChatSurfaceMounter` mounts the header magic-icon button + toggles the `FULL_ROUTE` menu item; no shell-level side panel exists.
   - Target: A `SIDEBAR` value on `AiChatSurface`; `ChatSurfaceMounter` mounts a right side panel (preferred realization: Jmix 2.8 `sidePanelLayout`, `modal=false`, `sidePanelPosition=RIGHT`, `sidePanelOverlay=false` push-mode on desktop, `displayAsOverlayOnSmallDevices=true`, explicit `sidePanelHorizontalSize`, `sidePanelLayoutCloser`) hosting the **single shared** `ChatPanelFragment` — no second `ChatService`, no second chat memory, no duplicate fragment implementation; the panel stays open across route navigation while the routed main view stays fully interactive; `SIDEBAR` participates in `AiUiSettings.enabledSurfaceIds`/`defaultSurface` and the admin UI-settings view's enabled-surface control.
   - Acceptance: with `SIDEBAR` enabled, opening the panel then navigating between two non-chat routes keeps the panel open and the main view clickable (no modality curtain); with `SIDEBAR` not enabled, no panel and no sidebar toggle are mounted; a conversation started in any enabled surface is visible after switching to another (`AiChatSessionState` continuity); a structural/source check confirms no second `ChatService`/`ChatMemory`/`ChatPanelFragment` was introduced.

2. **SIDEBAR launch affordance**: A distinct far-right navbar toggle opens/closes the side panel and reflects its open/closed state; the panel also has an in-panel closer.
   - Current: only the HEADER_BUTTON magic-icon button (`aiAgentHeaderChatButton`) exists; there is no sidebar toggle.
   - Target: when `SIDEBAR` is enabled, `ChatSurfaceMounter` mounts a separate far-right navbar toggle button (right-panel/sidebar icon) whose active/pressed visual state reflects whether the panel is open; clicking toggles the side panel; the panel additionally exposes an in-panel close/collapse control (`sidePanelLayoutCloser` or equivalent) for local dismissal; this toggle is independent of the HEADER_BUTTON button — when both surfaces are enabled, both navbar buttons are present and operate independently.
   - Acceptance: with `SIDEBAR` + `HEADER_BUTTON` both enabled, two distinct navbar buttons are present; the sidebar toggle's active state flips when the panel opens/closes; the in-panel closer collapses the panel without affecting the HEADER_BUTTON dialog; with `SIDEBAR` disabled the sidebar toggle is absent (not greyed).

3. **Ephemeral streaming-status line (OBS-01)**: A KIND-keyed status line renders in a sibling slot of the message list while a turn streams and clears completely on finalization.
   - Current: streaming shows only a `ProgressBar`; intermediate model prose is appended into the assistant `MessageListItem` and stays in the final bubble.
   - Target: a status line in a sibling slot of the message list (never inside a `MessageListItem`/`MessageBubbleComponent`) showing a humanized, label-only status keyed by the current activity `KIND` — `CHAT`→"thinking…", `TOOL`→"searching data…", `RETRIEVAL`→"retrieving documents…", with a neutral typing indicator when no KIND is yet identifiable — derived from the existing `StreamingEvent` flux; on the terminal `Final`/`Error` event the status line clears completely; status text is never concatenated into the assistant bubble and never contains an internal `@Tool` method name or a raw entity name; all status strings come from `msg://` keys present in every locale bundle.
   - Acceptance: during a turn with a tool call the status line shows the KIND-keyed label and the assistant bubble holds only reply markdown; after finalization the status line is empty/removed; the TEST-19 leak scan finds no `@Tool`/entity name in the status text; the keys resolve in `messages.properties` and every `messages_*.properties`.

4. **Per-turn tool-detail disclosure (OBS-02, deep-link descoped)**: Each completed turn with ≥1 tool call shows a collapsed-by-default, label-only "what the agent did" disclosure; turns with zero tool calls show nothing.
   - Current: nothing per-turn in chat; the operator must open `AiAuditEventListView` manually.
   - Target: each completed assistant turn that involved ≥1 tool call shows a collapsed-by-default disclosure ("what the agent did — N steps · total ms") that, expanded, lists humanized, label-only steps (KIND-keyed wording, never an internal tool/entity name) with per-step timing and an error/rollback indicator where applicable; the disclosure is hidden entirely (not "0 steps") for zero-tool-call turns; its data comes from the existing `StreamingEvent` flux for the in-flight turn and/or a lazy read of the `AiAuditEvent` subtree (by root `runId`) for the completed turn — no new persisted entity, no parallel store; expand/collapse state is session-only (not a persisted per-user preference); all disclosure strings come from `msg://` keys in every locale bundle. The `AiAuditEventListView?runId=...` deep-link clause of OBS-02 is **descoped for Phase 15** (decision 2026-05-11) — the disclosure is label-only and does not link to the audit views.
   - Acceptance: a 3-tool-call turn shows a collapsed "…3 steps…" header expanding to 3 label-only rows with per-step ms; a zero-tool-call turn shows no disclosure at all; an errored/rolled-back step shows the indicator; the TEST-19 leak scan finds no internal tool/entity names in the disclosure; keys resolve in all bundles; no `?runId=` deep-link is added.

5. **No new persisted state; bounded session state (OBS-04)**: Observability is driven only by the existing flux + audit tree; no new entity/table; per-turn detail does not accumulate unbounded in `AiChatSessionState`.
   - Current: `AiChatSessionState` (VaadinSessionScope) holds only `currentConversationId` + listeners; the `AiAuditEvent` tree is the only persisted turn record; `ChatPanelFragment` holds active stream/run authority.
   - Target: the streaming-status line and per-turn disclosure are driven solely by the existing `StreamingEvent` flux and `AiAuditEvent` tree; no new `@Entity` (no "turn" entity), no new persisted table, no new Liquibase changelog, no parallel state store; any in-memory per-turn detail held by the fragment/session is bounded (current/last turn only, or a capped collection, or lazy re-query from `AiAuditEvent`) and does not accumulate unbounded in `AiChatSessionState`; new labels use `msg://` keys in every locale bundle.
   - Acceptance: no new `@Entity`/`@Table`/changelog is added for observability; a long multi-turn conversation does not grow `AiChatSessionState` per-turn detail without bound; the disclosure still works after a fresh navigation to the chat surface (re-read from `AiAuditEvent`).

6. **TEST-19 — UI-layer leak test**: An automated test reuses the Phase 9 leak-guard pattern packs at the UI layer to prove the status line and disclosure never emit internal tool/entity names.
   - Current: Phase 9 pattern packs (`HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK`) exist at the LLM-output layer; no UI-layer leak test.
   - Target: a test reuses those pattern packs against the rendered text of the streaming-status line and the per-turn tool-detail disclosure — across the supported `KIND` values and an errored/rolled-back step — asserting no `@Tool` method name and no raw entity name appears.
   - Acceptance: the test exists, references the Phase 9 pattern packs, and passes; deliberately routing a tool/entity name through the status/disclosure rendering path fails the assertion.

## Boundaries

**In scope:**
- `AiChatSurface.SIDEBAR` enum value; `SIDEBAR` selectable in the admin UI-settings enabled-surface control and the `defaultSurface` selector; any default-seed `AiUiSettings` data adjustment (the `enabledSurfaceIds`/`defaultSurface` columns are strings — no schema change expected)
- `ChatSurfaceMounter` extension: mount a `sidePanelLayout`-based right side panel at the MainView/AppLayout shell level hosting the shared `ChatPanelFragment`, plus a distinct far-right navbar toggle button with active/closed state; in-panel closer
- The shared `ChatPanelFragment` rendered inside the side panel — single instance, same `ChatService`/chat memory, no duplicate fragment
- Ephemeral streaming-status line in a sibling slot of the message list, KIND-keyed, clears on finalize
- Collapsed-by-default per-turn tool-detail disclosure: label-only steps, per-step timing, error/rollback indicator, hidden on zero-tool turns, session-only expand/collapse state
- New `msg://` keys for all status/disclosure/surface/toggle labels in `messages.properties` and every `messages_*.properties`
- TEST-19 UI-layer leak test reusing the Phase 9 pattern packs; updates/additions to `ChatSurfaceMounterTest` / `ChatDialogViewTest` and new tests for the SIDEBAR mount, toggle, and persistence-across-navigation
- Moving the `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo to `done/` when the phase ships

**Out of scope:**
- The chat-state side panel (active model code / conversation id+title / LLM-exposure flag / mutation-tools flag / attached-file count / attachment token-budget usage / last-turn summary) — explicitly deferred to `OBS-FUT-01` per the 2026-05-11 decision; do not implement
- The `AiAuditEventListView?runId=...` deep-link from a turn — descoped from OBS-02 for Phase 15; labels only, no link, no changes to the audit list/detail views
- Any new persisted "turn" entity / table / Liquibase changelog / parallel state store
- Streaming tool *output* / payloads / interim tool results into the status line — it shows only KIND-keyed labels
- Persisted per-user expand/collapse preference for the disclosure — session-only state only
- Mobile-specific redesign of the `ChatPanelFragment` internals — the existing fragment layout is reused as-is inside the side panel; only the side panel's small-screen overlay behavior is configured
- Model-picker / curated catalog (Phase 16), config-knob migration (Phase 17), mutation-internals hardening (Phase 18), AI-runtime perf pass (Phase 19), voice input / STT (Phase 20)
- Changing the existing `FULL_ROUTE` / `HEADER_BUTTON` flows, the `ChatSurfaceMounter` slot contract, or the `ChatPanelFragment` slot ids (`messageListSlot`, `messageInputSlot`, `attachmentsPanel`) — must stay behavior-compatible

## Constraints

- **Preferred SIDEBAR realization:** Jmix 2.8 `sidePanelLayout` with `modal=false` (so no modality curtain blocks the main view — confirmed in Jmix docs), `sidePanelPosition=RIGHT`, `sidePanelOverlay=false` (push mode) on desktop, `displayAsOverlayOnSmallDevices=true` (overlay only on narrow screens), an explicit `sidePanelHorizontalSize`, and `sidePanelLayoutCloser` for in-panel dismissal; it must be hosted so the routed main view remains the main content area and stays interactive while the panel is open.
- **Open implementation risk (resolve in research/plan-phase):** Jmix docs/samples show `sidePanelLayout` used *inside* a single view with non-routed main content; whether it can be hosted at the `StandardMainView`/`AppLayout` shell level wrapping the router outlet must be verified. If it cannot wrap the router outlet cleanly, the requirement (a persistent, non-modal, push-mode right sidebar that survives route navigation with the main view interactive) still stands and an alternative non-modal realization is acceptable.
- Zero behavior change to the Phase 12 surface contract for the existing two surfaces: `ChatSurfaceMounter` (FULL_ROUTE menu + HEADER_BUTTON dialog flows), `AiUiSettings`/`AiUiSettingsService`, `ChatView`, `ChatDialogView` keep working as today; `ChatPanelFragment` slot ids unchanged so Phase 20's mic recorder and the existing attachments pane still mount.
- All new user-visible text uses `msg://` keys present in `messages.properties` AND every `messages_*.properties` (currently `en` + `vi`).
- No new runtime dependency — `sidePanelLayout` ships with Jmix 2.8 flow-ui.
- The leak test reuses the Phase 9 `HOST_PREFIX_LEAK` / `TOOL_NAME_LEAK` pattern packs — do not fork new pattern definitions.
- Observability rendering must not change `ChatService` / the advisor chain / the persisted shape of `AiAuditEvent`; a new additive `StreamingEvent` variant to surface the current `KIND` is acceptable if needed, but is not required if `KIND` can be derived from existing `Content`/`ToolCall`/`ToolResult`/`Citation` events (a plan-phase decision).
- Doc-sync note (not a code change): REQUIREMENTS.md OBS-02 still carries the `AiAuditEventListView?runId=...` deep-link clause — this SPEC supersedes it for Phase 15; REQUIREMENTS.md/ROADMAP.md should be updated to match.

## Acceptance Criteria

- [ ] `AiChatSurface` has a `SIDEBAR` value, selectable in the admin UI-settings enabled-surface control and the `defaultSurface` selector
- [ ] With `SIDEBAR` enabled, the side panel hosting the chat fragment opens from a distinct far-right navbar toggle and stays open across navigation between two non-chat routes, with the main view remaining clickable (no modality curtain)
- [ ] The navbar sidebar toggle shows an active/pressed state while the panel is open and an inactive state while closed; the in-panel closer collapses the panel
- [ ] With `SIDEBAR` disabled, no side panel and no sidebar toggle are mounted (absent, not greyed)
- [ ] A conversation started in any enabled surface is visible after switching to another enabled surface (session continuity)
- [ ] No second `ChatService`, second chat memory store, or duplicate `ChatPanelFragment` implementation is introduced
- [ ] During a streaming turn, a status line renders in a sibling slot (not inside a message bubble), shows a KIND-keyed humanized label, and is empty/removed after the turn finalizes
- [ ] The final assistant bubble contains only reply markdown — no leftover status prose
- [ ] A completed turn with ≥1 tool call shows a collapsed-by-default "N steps · total ms" disclosure that expands to label-only per-step rows with per-step ms and an error/rollback indicator where applicable
- [ ] A completed turn with zero tool calls shows no disclosure at all
- [ ] No internal `@Tool` method name or raw entity name appears in the streaming-status line or the disclosure — verified by the TEST-19 leak test reusing the Phase 9 pattern packs
- [ ] No `AiAuditEventListView?runId=...` deep-link is added; existing audit list/detail behavior is unchanged
- [ ] No new `@Entity`/`@Table`/Liquibase changelog is added for the observability features; per-turn detail in `AiChatSessionState` is bounded (capped or lazily re-queried), not unbounded
- [ ] All new labels resolve in `messages.properties` and every `messages_*.properties` locale file
- [ ] The `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo is moved to `done/`

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                 |
|--------------------|-------|------|--------|-----------------------------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | SIDEBAR surface + ephemeral status line + per-turn disclosure — all measurable |
| Boundary Clarity   | 0.88  | 0.70 | ✓      | Chat-state panel (→OBS-FUT-01) and audit deep-link explicitly descoped; SURF-11 bounds non-introductions |
| Constraint Clarity | 0.82  | 0.65 | ✓      | `sidePanelLayout` preferred (modal=false / push / overlay-on-small); shell-level placement flagged as a plan/research-phase verification item with a fallback |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | 15 pass/fail checks incl. the TEST-19 leak test                       |
| **Ambiguity**      | 0.148 | ≤0.20| ✓      |                                                                       |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective        | Question summary                                  | Decision locked                                                                 |
|-------|--------------------|---------------------------------------------------|---------------------------------------------------------------------------------|
| 0     | (user doc edit)    | Phase 15 rescoped before spec began               | Drop OBS-03 chat-state panel → `OBS-FUT-01`; add SURF-11 SIDEBAR surface; Phase 15 = SURF-11 + OBS-01/02/04 + TEST-19 |
| 1     | Researcher/Boundary| What is the `SIDEBAR` surface physically?         | Jmix 2.8 `sidePanelLayout` at the MainView/AppLayout shell level; `modal=false`; desktop push (non-overlay); overlay only on narrow screens; persists across route navigation; main view stays usable |
| 1     | Boundary Keeper    | SIDEBAR launch affordance vs HEADER_BUTTON?       | Distinct far-right navbar toggle (right-panel icon + active state) opens/closes the non-modal right sidebar; in-panel closer for local dismissal; independent of HEADER_BUTTON |
| 1     | Boundary Keeper    | Scope of the OBS-02 audit deep-link?              | Deferred entirely — Phase 15 shows only human-readable activity labels in the status line + disclosure; no audit-list link, no internal tool names |
| 1     | (research)         | Verify `sidePanelLayout` shell-level placement + `modal=false` interaction (Context7 → Jmix docs) | `modal=false` → no modality curtain → main content interactive; `sidePanelOverlay=false` → push; `displayAsOverlayOnSmallDevices=true` → overlay on small screens. Docs/samples only show it *inside a view* with non-routed content → shell-level wrapping of the router outlet must be verified in plan/research-phase; non-modal fallback acceptable |

---

*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Spec created: 2026-05-11*
*Next step: /gsd-discuss-phase 15 — implementation decisions (how to host `sidePanelLayout` at the shell level, how to derive `KIND` for the status line, disclosure layout, etc.)*
