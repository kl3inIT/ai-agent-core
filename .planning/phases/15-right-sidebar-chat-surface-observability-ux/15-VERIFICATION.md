---
phase: 15-right-sidebar-chat-surface-observability-ux
verified: 2026-05-12T19:00:00Z
status: passed
score: 5/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: null
human_verification:
  - test: "Open the right-sidebar surface (enable SIDEBAR in AiUiSettings), drive a streaming turn, confirm the ephemeral status line position (sibling after the message list) and that the per-turn <vaadin-details> visually anchors right after the turn's <vaadin-message> in the message-list light DOM (Option-A client-side splice)."
    expected: "Status line appears below the bubble while streaming and disappears on Final; the collapsed 'what the agent did' disclosure renders inline under the turn that produced it; sidebar pushes the AppLayout content, main view stays interactive."
    why_human: "Client-side executeJs splice into Vaadin MessageList light DOM has no server-side Element to assert; visual position is UAT territory (noted in the verification request)."
  - test: "Cross-surface continuity: start a conversation in FULL_ROUTE, switch to SIDEBAR / HEADER_BUTTON."
    expected: "The same conversation is visible across surfaces (AiChatSessionState.currentConversationId)."
    why_human: "Requires a running app with all surfaces enabled."
  - test: "Run :jmix-app:test against a real PostgreSQL agentstore datasource."
    expected: "Green — confirms the integration suite (DB-dependent) is unaffected by Phase 15 test-only changes."
    why_human: "No PostgreSQL provisioned in this environment (pre-existing, documented in deferred-items.md); :ai-agent:ai-agent:test (HSQLDB) is green."
---

# Phase 15: Right-Sidebar Chat Surface & Observability UX — Verification Report

**Phase Goal:** An operator can open chat from a right-sidebar `SIDEBAR` surface in addition to the shipped `FULL_ROUTE` and `HEADER_BUTTON` surfaces, and can see what the agent is doing while it works (an ephemeral streaming-status line) and what it did afterward (a collapsed-by-default per-turn tool-detail disclosure) — all without internal tool/entity names ever leaking into the UI, and with no new persisted state.
**Verified:** 2026-05-12T19:00:00Z
**Status:** passed (with human verification items for UAT-level visual / cross-surface / DB-integration checks)
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria + merged PLAN must_haves)

| # | Truth | Status | Evidence |
| --- | --- | --- | --- |
| 1 | A `SIDEBAR` surface enabled independently via `AiUiSettings`, mounts the shared `ChatPanelFragment`, preserves `AiChatSessionState` continuity, no second backend/memory/duplicate fragment | ✓ VERIFIED | `AiChatSurface.SIDEBAR` enum constant (`AiChatSurface.java:11`); `ChatSurfaceMounter.mountSidebar/mountSidebarToggle/mountSidebarPanel/toggleSidebar/shouldShowSidebar`, panel `Div` appended to `UI.getCurrent().getElement()` not the AppLayout slot, gated on `getEnabledSurfaceSet().contains(SIDEBAR)` + `UiShowViewContext("AiAgent_Sidebar")`; `AiAgentSidebarView` is a lean `StandardView` whose XML contains only `<fragment class=...ChatPanelFragment/>` (no new fragment class), created via `views.create(AiAgentSidebarView.class)` mirroring `ChatDialogView`; `onBeforeShow`/`onReady` sync `setConversationId` from `AiChatSessionState`. Role views `AiAgent_Sidebar` added to `AiAgentUserRole` and `AiAgentAdminRole`. Distinct toggle: id `aiAgentSidebarToggleButton`, icon `VaadinIcon.PANEL`, `LUMO_TERTIARY+LUMO_ICON`, `aria-pressed` + `--active` class. `ChatSurfaceMounterTest` (20 tests) green. |
| 2 | Ephemeral status line in a sibling slot, KIND-keyed, clears on finalize, never concatenated into the answer, never leaks tool/entity names | ✓ VERIFIED | `StreamingEvent.Activity(ActivityKind)` additive variant, `permits` updated, `ActivityKind {CHAT, TOOL, RETRIEVAL}` (`StreamingEvent.java`); `AuditingDocumentRetriever.emitRetrievalActivity` and `ToolCallbackAuditDecorator` emit `Activity(RETRIEVAL)`/`Activity(TOOL)` best-effort (try/catch swallowed), `Activity(CHAT)` not emitted (UI derives). `ChatPanelFragment`: `<span class="ai-agent-status">` created in `showStatus`, neutral indicator before any event, flips to CHAT on first `Content`, TOOL/RETRIEVAL on `Activity`; `removeStatusRow()` called in `.doOnComplete`/`.doOnError`/`finishStreamInternal`/`clearMessageList`/stop-path/detach. `@CssImport("./styles/ai-agent-chat.css")` on `ChatPanelFragment`. `ChatPanelFragmentStatusLineTest`, `StreamingActivityEventTest`, `RenderStreamEventTest` green. |
| 3 | Each completed turn shows a collapsed-by-default "what the agent did — N steps · total ms" disclosure with label-only KIND-keyed steps, per-step ms, error/rollback indicator; hidden for zero-tool turns | ✓ VERIFIED | `.ai-agent-turn-activity` `Details` (collapsed by default) built in `appendTurnDetails`/`appendHistoryTurnDetails`, wrapped in `.ai-agent-turn-extra`, anchored after the turn's `<vaadin-message>` via `anchorExtra(turnIndex, element)` + `reanchorAllExtras()` after `setItems`; summary via `TurnDetailRenderer.summaryKey()/summaryArgs(...)`; per-step rows via `buildStepRow`, em-dash `UNKNOWN_DURATION_TEXT` for null latency, error indicator via `errorIndicatorKey()`; zero-tool turns get no `Details` (live path only appends when `steps` non-empty; history path only when `childCount > 0`). `TurnDetailRendererTest`, `ChatPanelFragmentTurnDetailTest`, `ChatPanelFragmentTurnDetailHistoryTest` green. **NOTE:** ROADMAP SC-3's `AiAuditEventListView?runId=...` deep-link clause is explicitly DESCOPED for Phase 15 per `15-SPEC.md` (the disclosure is label-only, no link) — ROADMAP records this as a doc-sync follow-up only. Not a gap. |
| 4 | Panels driven by existing `StreamingEvent` flux + `AiAuditEvent` tree — no new persisted turn entity, no parallel state store, per-turn detail bounded, labels use `msg://` in all locale bundles | ✓ VERIFIED | No new `@Entity`/`@Table`/Liquibase changelog (`git diff HEAD~25..HEAD -- '*liquibase*' entity/` empty; `NoNewPersistedStateTest` asserts no Phase-15 changelog file / `<include>`). `loadTurnSteps(runId, conversationId)` reads `AiAuditEvent` (agentstore) via `UnconstrainedDataManager` with MANDATORY `e.userUsername = :me and e.conversation.id = :cid and e.runId = :rid and e.parent is not null` clause + narrow fetch plan; history correlation `correlateHistoryTurnDetails` does two narrow `loadValues` (`.store("agentstore")`), zips runId list against assistant turns ONLY when counts equal, else renders nothing (debug-log, never throws). `liveTurnSteps` capped (`LIVE_TURN_STEP_CAP`), cleared on every teardown site. `AiChatSessionState` still `{currentConversationId, listeners}` (`AiChatSessionStateTest`). All new keys (`AiChatSurface.SIDEBAR`, `chatView.status.*`, `chatView.turnDetail.*`, `chatSurfaceMounter.sidebar*`) present in `messages_en.properties` AND `messages_vi.properties` (`ObservabilityMessagesCompletenessTest`). |
| 5 | TEST-19 — UI-layer leak test reusing Phase 9 leak-guard pattern packs asserts status line + per-turn disclosure never emit `@Tool` method names or raw entity names | ✓ VERIFIED | `ObservabilityLeakTest` (4 tests, green): uses `ToolNamePatternProvider` + `HostPrefixPatternProvider` (Phase 9, not forked); scans `TurnDetailRenderer`-mapped status keys across all `ActivityKind` values + null + neutral; scans mapped step-row labels incl. errored/rolled-back outcomes and the MessageFormat summary; drives a real `ChatPanelFragment` via reflective `showStatus`/`appendTurnDetails` and scans the ACTUAL rendered `<span class="ai-agent-status">` text and ACTUAL rendered `Details` step-row text inside `.ai-agent-turn-extra`; negative control routes a `find_records` tool name through the rendering path and asserts the pattern trips. |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
| --- | --- | --- | --- |
| `entity/AiChatSurface.java` | SIDEBAR constant | ✓ VERIFIED | `SIDEBAR("SIDEBAR")` line 11 |
| `orchestration/StreamingEvent.java` | `Activity` record + `ActivityKind` + permits | ✓ VERIFIED | `record Activity(ActivityKind kind)`, `enum ActivityKind { CHAT, TOOL, RETRIEVAL }`, permits updated |
| `rag/advisor/AuditingDocumentRetriever.java` | `Activity(RETRIEVAL)` emit | ✓ VERIFIED | `emitRetrievalActivity` via `StreamingSinkHolder`, swallowed RuntimeException; ctor takes `StreamingSinkHolder` |
| `audit/ToolCallbackAuditDecorator.java` | `Activity(TOOL)` emit | ✓ VERIFIED | `emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.Activity(TOOL)))`, WR-06 only for tools with a generic audit row |
| `view/chat/ChatSurfaceMounter.java` | sidebar mount/toggle/gating | ✓ VERIFIED | see Truth 1 |
| `view/chat/AiAgentSidebarView.java` | lean host view | ✓ VERIFIED | `@ViewController("AiAgent_Sidebar")`, only a `ChatPanelFragment` `@ViewComponent`, syncs conversationId |
| `view/chat/ai-agent-sidebar-view.xml` | `<fragment class=...ChatPanelFragment/>` | ✓ VERIFIED | exactly that, no new fragment class |
| `view/chat/fragment/TurnDetailRenderer.java` | pure-fn kind→msg:// mapper | ✓ VERIFIED | `statusKeyFor`, `neutralStatusKey`, `errorIndicatorKey`, `unknownDurationKey`, `summaryKey`/`summaryArgs`, `stepRow`; label-only |
| `view/chat/fragment/ChatPanelFragment.java` | Activity arm, status span, turn-activity block, capped live steps, loadTurnSteps, anchoring, teardown | ✓ VERIFIED | see Truths 2–4; `@CssImport` present; `startNewChat` force-clear + `.doOnError` conversationId sync (15-06) |
| `frontend/styles/ai-agent-chat.css` | `.ai-agent-sidebar*`, `.ai-agent-turn-activity*`, `.ai-agent-action-choice`, `.ai-agent-turn-extra`, `.ai-agent-status`, small-device media query | ✓ VERIFIED | all rules present, `--lumo-*` tokens, `@media (max-width:768px)` overlay; no edits under `frontend/generated` or `frontend/themes` |
| `messages_en.properties` / `messages_vi.properties` | all new keys both bundles | ✓ VERIFIED | confirmed by grep + `ObservabilityMessagesCompletenessTest` |
| `test/.../ObservabilityLeakTest.java` | TEST-19 leak test | ✓ VERIFIED | 4 tests green, reuses Phase 9 providers, real-component scan + negative control |
| `test/.../NoNewPersistedStateTest.java` | structural no-DDL assertion | ✓ VERIFIED | 3 tests green |
| `.planning/todos/done/2026-04-26-add-collapsible-tool-detail-...md` | folded todo | ✓ VERIFIED | present in `done/`, absent from `pending/` |

### Key Link Verification

| From | To | Via | Status |
| --- | --- | --- | --- |
| `AiUiSettingsDetailView` enabled-surface control | `AiChatSurface.SIDEBAR` | enum-backed select | ✓ WIRED (`AiUiSettingsModelTest`, `AiUiSettingsDetailViewTest`) |
| `AuditingDocumentRetriever.retrieve` | `StreamingSinkHolder` | `tryEmitNext(Activity(RETRIEVAL))` try/catch | ✓ WIRED |
| `ToolCallbackAuditDecorator.callInternal` | sink | `emitToolEvent → Activity(TOOL)` | ✓ WIRED |
| `RetrievalAugmentationAdvisorFactory` | `new AuditingDocumentRetriever(..., streamingSinkHolder)` | added ctor arg | ✓ WIRED |
| `ChatSurfaceMounter` sidebar panel `Div` | `UI.getCurrent().getElement()` | `appendChild` (not AppLayout slot) | ✓ WIRED |
| `ChatSurfaceMounter` | `views.create(AiAgentSidebarView.class)` + `panelDiv.appendChild(sidebarView.getElement())` | mirrors `openDialog()` | ✓ WIRED |
| sidebar mount/show gate | `getEnabledSurfaceSet().contains(SIDEBAR)` + `UiShowViewContext(SIDEBAR_VIEW_ID)` | `shouldShowSidebar` | ✓ WIRED |
| navbar toggle + in-panel closer | single `toggleSidebar()` | both click listeners call it; flips push class + aria-pressed + active class | ✓ WIRED |
| `ChatPanelFragment.submitChatTurn(...).doOnNext` | `StreamingEvent.Activity` / `Content` | `instanceof Activity → showStatus(statusKeyFor(kind))`; first `Content → showStatus(CHAT)` | ✓ WIRED |
| history-replay | `AiAuditEvent` (agentstore) | grouped `loadValues` count, zip-when-equal, `appendHistoryTurnDetails` for childCount>0 | ✓ WIRED |
| per-turn `Details` on Final / first expand | `AiAuditEvent` TOOL/RETRIEVAL children for one runId | `loadTurnSteps(runId, cid)` Unconstrained + mandatory username+conversation filter, memoized | ✓ WIRED |
| status line + Details rows | `TurnDetailRenderer` | label-only kind→msg:// | ✓ WIRED |
| `startNewChat`/`setConversationIdInternal` | `clearMessageList` | force-clear bypassing null==null early-return when messageCount>0 (15-06) | ✓ WIRED |
| `appendTurnDetails`/`appendActionChoiceRow`/`appendNoticeRow` | `<vaadin-message>` sibling DOM position | `anchorExtra(turnIndex, element)` + `reanchorAllExtras()` after setItems (15-06 Option-A: server-side ordered children + `data-ai-turn-index`-keyed executeJs splice) | ✓ WIRED (server-side ordering verified; client-side splice position is UAT) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| --- | --- | --- | --- |
| Phase-15 unit/UI test suite (HSQLDB) | `./gradlew :ai-agent:ai-agent:test --tests <phase-15 classes>` | 91 tests, 0 failures, 0 errors | ✓ PASS |
| No new Liquibase DDL in phase commits | `git diff HEAD~25..HEAD -- '*liquibase*' entity/` | empty | ✓ PASS |
| chat-panel-fragment.xml unchanged | `git diff HEAD~12 -- chat-panel-fragment.xml` | empty | ✓ PASS |

### Probe Execution

Not applicable — no project probe scripts declared for this phase; behavioral verification via the JUnit suite above.

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
| --- | --- | --- | --- | --- |
| SURF-11 | 15-01, 15-03, 15-06 | SIDEBAR as a third chat surface, shared fragment, AiUiSettings participation, AiChatSessionState continuity, no second backend/memory/fragment | ✓ SATISFIED | Truth 1 |
| OBS-01 | 15-02, 15-04, 15-05, 15-06 | Ephemeral KIND-keyed streaming-status line in a sibling slot, clears on finalize, no leaks | ✓ SATISFIED | Truth 2 |
| OBS-02 | 15-04, 15-05, 15-06 | Collapsed-by-default per-turn tool-detail disclosure, label-only, per-step timing, error/rollback, hidden for zero-tool turns | ✓ SATISFIED (deep-link clause DESCOPED per 15-SPEC, ROADMAP-acknowledged doc-sync follow-up) | Truth 3 |
| OBS-04 | 15-01, 15-02, 15-04, 15-05 | Driven by existing flux + AiAuditEvent tree, no new persisted state, bounded per-turn detail, msg:// keys in all bundles, folds the 2026-04-26 todo | ✓ SATISFIED | Truth 4; todo in `done/` |
| TEST-19 | 15-05 | UI-layer leak test reusing Phase 9 pattern packs against status line + disclosure | ✓ SATISFIED | Truth 5 |

No orphaned requirements: all 5 phase requirement IDs appear in plan frontmatter and are covered.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| --- | --- | --- | --- | --- |
| (none) | — | No `TBD`/`FIXME`/`XXX` debt markers in phase-modified files; the only `TODO`-shaped text is a humanized resolved-todo reference in test Javadoc | ℹ️ Info | None |

### Human Verification Required

1. **Sidebar surface + inline anchoring (UAT)** — Enable SIDEBAR in `AiUiSettings`, drive a streaming turn. Expected: ephemeral status line appears below the bubble while streaming and disappears on Final; the collapsed "what the agent did" disclosure visually anchors right after the turn's `<vaadin-message>` (Option-A client-side splice); sidebar pushes the AppLayout content; main view stays interactive (no modality curtain). Why human: client-side `executeJs` splice into the MessageList light DOM has no server-side `Element` to assert.
2. **Cross-surface continuity** — Start a conversation in FULL_ROUTE, switch to SIDEBAR / HEADER_BUTTON. Expected: same conversation visible across surfaces. Why human: needs a running app with all surfaces enabled.
3. **`:jmix-app:test` against real PostgreSQL** — Expected: green (Phase 15 changes are test-only and live in `:ai-agent:ai-agent`). Why human: no PostgreSQL provisioned here (pre-existing, documented in `deferred-items.md`); `:ai-agent:ai-agent:test` is green.

### Gaps Summary

No gaps. All five ROADMAP success criteria and all five requirement IDs are satisfied in the codebase. The 91-test phase-15 suite passes with zero failures. The one ROADMAP-noted scope reduction (OBS-02's `AiAuditEventListView?runId=...` deep-link) is explicitly descoped in `15-SPEC.md` and acknowledged in ROADMAP as a doc-sync follow-up, not a Phase-15 deliverable. The Option-A inline-anchoring's client-side visual position and cross-surface continuity, plus the DB-dependent `:jmix-app:test` run, are routed to human/UAT verification (consistent with the verification request's framing).

---

_Verified: 2026-05-12T19:00:00Z_
_Verifier: Claude (gsd-verifier)_
