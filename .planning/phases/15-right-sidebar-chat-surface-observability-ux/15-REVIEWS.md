---
phase: 15
reviewers: [codex, opencode]
reviewed_at: 2026-05-11T14:22:03Z
plans_reviewed: [15-01-PLAN.md, 15-02-PLAN.md, 15-03-PLAN.md, 15-04-PLAN.md, 15-05-PLAN.md]
---

# Cross-AI Plan Review — Phase 15

> Reviewers: **codex** (OpenAI Codex CLI, default model) · **opencode** (OpenCode, model `nemotron-3-super-free`).
> Prompt included: PROJECT.md (excerpt), the Phase 15 ROADMAP section, the 5 requirements (SURF-11, OBS-01, OBS-02, OBS-04, TEST-19), `15-CONTEXT.md` (decisions D-01..D-09), and all 5 PLAN.md files in full.

## Codex Review

**Summary**

The plan set is well decomposed and security-conscious, but it currently overpromises on two core UI mechanics: mounting a "same shared" `ChatPanelFragment` directly in a shell-level `Div`, and placing per-turn `Details` after individual `MessageListItem`s. In the current code shape, those are not reliably achievable as written. The phase is directionally sound, but Plans 03 and 04 need correction before execution.

**Strengths**

- Clear wave ordering: enum/settings first, streaming event substrate next, surface mount, fragment observability, then cross-cutting tests.
- Strong no-leak design: closed `ActivityKind` plus label-only `TurnDetailRenderer` is the right structural boundary.
- Good best-effort posture for observability emits; failures should not break RAG/tool execution.
- Correct instinct to use `UnconstrainedDataManager` for audit reads only with mandatory `userUsername + conversation.id` filters.
- Locale completeness and no-DDL tests are good phase gates.
- The plans explicitly preserve existing `FULL_ROUTE` / `HEADER_BUTTON` behavior and slot IDs.

**Concerns**

- **HIGH**: Plan 03's "same shared `ChatPanelFragment` instance" conflicts with Vaadin/Jmix component ownership. Existing `ChatView` and `ChatDialogView` each own their own fragment instance via XML. A fragment mounted directly under a raw `UI` `Div` may lack a valid `FragmentOwner`; `ChatPanelFragment.hostView()` can throw for dialogs/actions. The goal should be one shared implementation/backend state, not one physical component instance.

- **HIGH**: Plan 04's per-turn `Details` placement is not achievable with the current `MessageList` substrate. `MessageListItem`s are data items inside one `<vaadin-message-list>` component; appending a `Details` to `messageListSlot` places it after the whole list, not after a specific assistant item. Historical "one disclosure after each prior turn" will not render as described without changing the transcript substrate.

- **HIGH**: The history correlation pass is brittle. Zipping ordered assistant messages to ordered `AiAuditEvent` CHAT roots can misassociate rows when a turn is denied, cancelled, errors before content, has missing audit rows, or produces unusual assistant-message counts. It also conflicts with "hidden entirely for zero-tool turns": the described placeholder approach shows a disclosure until first expand unless the root query also knows child counts.

- **MEDIUM**: Sidebar width is likely too narrow for the existing fragment. The fragment has a 68/32 split; with a 420px sidebar, the attachment pane is about 134px, below the project's own prior note that ~200px is needed for attachment cards.

- **MEDIUM**: CSS loading is assumed but not guaranteed by the plan. The stylesheet is currently imported via `@CssImport` on `MessageBubbleComponent`, while `ChatPanelFragment` now uses `MessageList`; add the import to a component/view guaranteed to be present for chat/sidebar.

- **MEDIUM**: Status sequencing can be wrong. If `Activity(CHAT)` fires before the model stream and retrieval later emits `Activity(RETRIEVAL)`, the status may remain "retrieving documents…" while the model is already streaming content. First content should switch status back to CHAT/thinking.

- **MEDIUM**: Conversation switch / stop mid-stream cleanup needs stronger handling. `liveTurnSteps` and status rows should be cleared in `finishStreamInternal()`, `clearMessageList()`, and dispose/cancel paths, not only `doOnComplete` / `doOnError`.

- **MEDIUM**: Live per-step timing is approximate. `ToolResult` has no latency field, and `Activity(RETRIEVAL)` has no duration, so "per-step timing" is not fully satisfied for live disclosures unless Plan 04 lazy-loads audit rows after finalization or enriches events.

- **LOW**: Plan 05's leak test may become a mapper test rather than a UI-layer test if it only scans `TurnDetailRenderer` output. TEST-19 asks for rendered status/disclosure text, so at least one test should inspect the actual component text.

- **LOW**: Hardcoded entity-count tests for "no new persisted state" can be brittle if adjacent phases run concurrently. Prefer asserting no Phase-15 entity/changelog and that touched state holders did not gain per-turn collections.

**Suggestions**

- Change Plan 03 wording from "same fragment instance" to "same `ChatPanelFragment` implementation and same `AiChatSessionState`/chat memory continuity." Mount a proper Jmix-owned sidebar host view/fragment owner, or explicitly prove programmatic fragment creation has a valid owner and lifecycle.

- Decide the transcript substrate before Plan 04. If per-turn `Details` must appear after each assistant turn, replace `MessageList` with a controlled `VerticalLayout`/message-bubble component model. If retaining `MessageList`, revise the acceptance criteria to a grouped activity area after the transcript.

- For historical details, either add a stable existing correlation mechanism or downgrade to best-effort. If using audit roots, query child counts first and hide zero-child turns immediately. On mismatch between assistant count and root count, prefer hiding details over showing possibly wrong details.

- On `Final`, consider lazy-loading audit children even for the just-completed turn so timings come from `AiAuditEvent`, not UI event arrival time. Use typed `load(AiAuditEvent.class)` or remember `.store("agentstore")` if switching to `loadValues`.

- Add cleanup requirements: status row and live steps cleared in `finishStreamInternal()`, `clearMessageList()`, stop/cancel, detach, and conversation switch.

- Add a Playwright/manual layout gate for the sidebar: open, navigate, check main view interactivity, check no overlap, and verify the attachment pane is usable at desktop and mobile widths.

- Make Plan 05 scan actual rendered component text for at least one status and one disclosure path, with `TurnDetailRenderer` tests as supplementary unit coverage.

**Risk Assessment**

**HIGH** as written. The security model and event substrate are solid, but two core UI delivery claims are not compatible with the current `ChatPanelFragment`/`MessageList` architecture. Once Plan 03 defines a valid Jmix-owned sidebar host and Plan 04 either changes the transcript substrate or revises the per-turn placement/history contract, the phase risk should drop to **MEDIUM**.

References checked via Context7: Jmix Flow UI side dialog/side panel samples and Jmix UI integration testing docs from `/jmix-framework/jmix-context7`.

---

## OpenCode Review

## Summary

The Phase 15 plan set is well-structured and comprehensive, covering all required aspects of implementing a right-sidebar chat surface with observability features. The plans are logically ordered across 4 waves, with clear dependencies and incremental delivery. The implementation follows Jmix best practices, leverages existing patterns, and includes thorough testing strategies. The observability features (streaming status line and per-turn tool detail disclosure) are designed with strong security guarantees to prevent internal tool/entity names from leaking to the UI. Overall, this is a high-quality plan set that should successfully deliver the phase goals.

## Strengths

- **Clear wave-based dependency structure**: Plans are properly ordered with 15-01 and 15-02 in Wave 1 (foundation), 15-03 in Wave 2 (depends on 15-01), 15-04 in Wave 3 (depends on 15-02 and 15-03), and 15-05 in Wave 4 (depends on 15-04)
- **Strong security focus**: Multiple layers of protection against leaking internal names:
  - Closed `ActivityKind` enum (CHAT, TOOL, RETRIEVAL) as the sole source for observability
  - `TurnDetailRenderer` as a Vaadin-free, Spring-free pure function mapper that only handles label-only keys
  - Structural guarantees that no tool/entity names ever reach the UI layer
  - TEST-19 leak test reusing Phase 9 pattern packs to verify no internal names appear
- **Proper Jmix adherence**:
  - Uses existing patterns like `ChatSurfaceMounter` extension
  - Leverages `Details` component for collapsible panels
  - Maintains single shared `ChatPanelFragment` instance
  - Preserves cross-surface continuity via `AiChatSessionState`
  - Uses `msg://` keys for all UI text in both locale bundles
- **Performance considerations**:
  - Bounded per-turn step list (cleared on terminal events)
  - One query per conversation switch for history correlation
  - Memoized on-expand audit re-query with `LOADED_KEY` flag
  - Best-effort observability emits that never block the main flow
- **Thorough testing strategy**:
  - Unit tests for pure functions (`TurnDetailRendererTest`)
  - UI tests for fragment behavior (`ChatPanelFragmentStatusLineTest`, etc.)
  - History correlation tests with seeded data
  - Leak test (`ObservabilityLeakTest`)
  - Locale completeness test
  - No new persisted state verification
  - Session state bounds verification

## Concerns

**MEDIUM**:
- The history-correlation pass in Plan 04 (lines 563-595) assumes a 1:1 correspondence between replayed ASSISTANT turns and CHAT audit roots. While the plan mentions zipping up to the shorter length and skipping extras, there's potential for confusion if counts mismatch significantly (e.g., due to errors or filtered turns). The defensive approach is good but could be clarified.

**LOW**:
- Plan 03's CSS implementation uses `clamp(420px, 32vw, 32vw)` which seems redundant (32vw, 32vw). Likely intended to be `clamp(420px, 32vw, 480px)` or similar for proper responsive behavior.
- The neutral status key behavior (`chatView.status.neutral`) is left to Claude's Discretion in Plan 02, but Plan 04 expects it to be implemented. Should be explicitly decided.
- In Plan 04, the `liveTurnSteps` list is described as "bounded" but no specific cap is mentioned. While clearing on terminal events prevents unbounded growth, very long turns could still accumulate many steps. A reasonable cap (e.g., 50 steps) would provide additional safety.

## Suggestions

- **Clarify history-correlation logic**: Add more explicit handling for when the count of ASSISTANT turns doesn't match CHAT audit roots (e.g., log a warning for debugging but continue with min length).
- **Fix CSS clamp function**: Correct the redundant `clamp(420px, 32vw, 32vw)` to proper min/max values like `clamp(420px, 32vw, 480px)`.
- **Explicitly decide neutral status**: Make a clear decision on whether to emit `Activity(CHAT)` explicitly or derive CHAT status from neutral→first Content.
- **Add step list cap**: Implement a reasonable cap on `liveTurnSteps` (e.g., 50) to prevent memory issues in extremely long turns, while noting that clearing on terminal events already prevents unbounded accumulation between turns.
- **Enhance test documentation**: In the test sections, be more specific about how query-count spies on `UnconstrainedDataManager` will be implemented for the memoization verification.

## Risk Assessment

**LOW**

Justification: The plan set demonstrates strong adherence to Jmix patterns, proper security considerations (especially the no-leak guarantee), clear dependency management, and comprehensive testing. The concerns identified are mostly minor implementation details rather than fundamental flaws. The observability features are designed with multiple layers of protection against leaking internal names, and the structural approach (using closed enums and pure functions) provides strong guarantees. The performance considerations are appropriate, and the testing strategy covers the critical aspects including leak detection, locale completeness, and state bounds verification.

---

## Consensus Summary

Both reviewers agree the plan set is **well-decomposed, security-conscious, and correctly wave-ordered**, with a structurally sound no-leak design (closed `ActivityKind` enum + label-only `TurnDetailRenderer`) and good best-effort emit posture. They diverge sharply on **risk level**: Codex rates the phase **HIGH** (two core UI-delivery claims may be incompatible with the current `ChatPanelFragment`/`MessageList` architecture); OpenCode rates it **LOW** (sees only minor implementation-detail concerns). Codex's review is the more architecturally specific of the two and its HIGH-severity findings should be treated as the priority follow-ups before execution.

### Agreed Strengths
- Clear wave-based dependency structure (enum/settings → streaming-event substrate → surface mount → fragment observability → cross-cutting tests); `depends_on` declarations match.
- Strong, structural no-leak design: closed `ActivityKind` + Vaadin-free/Spring-free `TurnDetailRenderer` label-only mapper; TEST-19 reuses the Phase 9 pattern packs verbatim.
- Best-effort observability emits that never break RAG/tool execution.
- Correct instinct for `AiAuditEvent` reads: `UnconstrainedDataManager` + mandatory `userUsername` + `conversation.id` filter (never `runId`-only unconstrained).
- Performance posture: bounded per-turn step list cleared on terminal events, one history-correlation query per conversation switch, memoized on-expand re-query.
- Existing `FULL_ROUTE` / `HEADER_BUTTON` flows and `ChatPanelFragment` slot ids explicitly preserved; locale-completeness + no-DDL structural tests as phase gates.

### Agreed Concerns
- **History-correlation zip is brittle (Codex: HIGH · OpenCode: MEDIUM)** — zipping ordered ASSISTANT `MessageListItem`s against ordered `AiAuditEvent` CHAT roots can misassociate when a turn is denied/cancelled/errors-before-content, has missing audit rows, or yields an unusual assistant-message count. Both want the count-mismatch handling clarified. Codex additionally notes it conflicts with "hidden entirely for zero-tool turns" unless the root query also knows child counts (otherwise a placeholder shows until first expand).

### Divergent Views
- **Overall risk: HIGH (Codex) vs LOW (OpenCode).** The gap is almost entirely Codex's two architecture findings, which OpenCode did not raise:
  - **Codex HIGH — "same shared `ChatPanelFragment` instance" vs Vaadin/Jmix component ownership.** `ChatView` and `ChatDialogView` each own their own fragment instance via XML; a fragment mounted directly under a raw `UI` `Div` may lack a valid `FragmentOwner` (`hostView()` can throw). Codex argues the goal should be "one shared *implementation* + one `AiChatSessionState`/chat-memory continuity", not literally one physical component instance. Worth verifying against the actual `ChatSurfaceMounter`/`AiChatUIState` code before executing Plan 03 — the plan body does say "obtain it the way the rest of `ChatSurfaceMounter`/`AiChatUIState` does", so it may already be handled, but the must-have *truth* as written says "the SAME `ChatPanelFragment` instance".
  - **Codex HIGH — per-turn `Details` placement vs the `MessageList` substrate.** `MessageListItem`s are data items inside one `<vaadin-message-list>`; appending a `Details` to `messageListSlot` lands it *after the whole list*, not after a specific assistant item — so "one disclosure after each prior turn" (live and history) may not render as described without changing the transcript substrate. This contradicts CONTEXT D-08's "appended as a sibling after that turn's `MessageListItem`, anchored by `runId`" — needs a feasibility check; if confirmed, either revise the placement to a single grouped activity area after the transcript, or change the transcript substrate.
- Codex also flags several MEDIUM items OpenCode mostly didn't: sidebar width likely too narrow for the fragment's 68/32 split (≈420px ⇒ ≈134px attachment pane, below the project's own ~200px note — OpenCode separately flagged the redundant `clamp(420px, 32vw, 32vw)`); `@CssImport` currently lives on `MessageBubbleComponent` (which the `MessageList`-based fragment may not instantiate) so the new CSS may not load; status-sequencing bug (`Activity(CHAT)` then `Activity(RETRIEVAL)` can leave "retrieving documents…" showing while content streams — first `Content` should flip back to CHAT); mid-stream conversation-switch/stop cleanup should also clear `liveTurnSteps`/status row in `finishStreamInternal()`/`clearMessageList()`/dispose-cancel, not only `doOnComplete`/`doOnError`; live per-step timing is approximate (`ToolResult` has no latency field, `Activity(RETRIEVAL)` no duration) so "per-step timing" for live disclosures needs the post-finalization audit read or event enrichment.
- Both note (Codex LOW) TEST-19 risks becoming a `TurnDetailRenderer` unit test rather than a UI-layer test unless at least one assertion scans actual rendered component text; Codex (LOW) also wants the "no new persisted state" test to assert "no Phase-15 entity/changelog + touched state holders gained no per-turn collections" rather than a brittle hardcoded entity count.
- OpenCode-only minor items: explicitly decide the neutral-status / `Activity(CHAT)` question (D-05 leaves it to discretion but Plan 04 depends on it); add an explicit cap to `liveTurnSteps` (e.g. 50); document how the `UnconstrainedDataManager` query-count spy is wired in the `@UiTest` harness.

### Recommended actions before execution
1. **Verify Plan 03's fragment-hosting claim against the real code** — confirm whether a single physical `ChatPanelFragment` can legally be re-parented into a shell-level `Div`, or restate the must-have as "one shared implementation + `AiChatSessionState` continuity" and host a proper Jmix-owned fragment owner.
2. **Verify Plan 04's per-turn `Details` placement against the `MessageList` substrate** — if `Details` can't sit after an individual `MessageListItem`, revise D-08/Plan 04 (grouped activity area, or change the transcript substrate) before execution.
3. **Tighten the history-correlation contract** — query child counts up front so zero-tool turns are hidden immediately (no placeholder), and on count mismatch prefer hiding details over risking a wrong association.
4. **Fix the CSS `clamp`** (`clamp(420px, 32vw, 32vw)` → a real min/preferred/max), reconsider the panel min-width vs the fragment's 68/32 attachment-pane needs, and **ensure the new CSS is `@CssImport`ed from a component guaranteed present in the chat/sidebar render path.**
5. **Add the status-sequencing fix** (first `Content` ⇒ flip status back to CHAT), **broaden cleanup** (status row + `liveTurnSteps` cleared in `finishStreamInternal()` / `clearMessageList()` / stop / detach), and **decide whether live disclosures lazy-load `AiAuditEvent` on `Final`** so per-step timings are real.
6. **Make TEST-19 inspect at least one actual rendered component's text** (the status `<span>` + one `Details` row), keeping the `TurnDetailRenderer` scans as supplementary.
7. **Resolve the open discretion points explicitly in the plans** — the neutral-status/`Activity(CHAT)` decision; the `liveTurnSteps` cap; the query-count-spy mechanism.

---

*To incorporate this feedback into planning: `/gsd-plan-phase 15 --reviews`*
