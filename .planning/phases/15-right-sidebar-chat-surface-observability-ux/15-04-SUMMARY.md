---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 04
subsystem: ui
tags: [chat-observability, streaming-status, turn-detail, jmix, vaadin-flow, vaadin-details, i18n, audit-read, no-leak]

# Dependency graph
requires:
  - phase: 15-right-sidebar-chat-surface-observability-ux
    provides: "StreamingEvent.Activity(ActivityKind) variant + Activity(TOOL)/Activity(RETRIEVAL) edge emits (Plan 02); @CssImport(ai-agent-chat.css) on ChatPanelFragment + SIDEBAR mount (Plan 03); TurnDetailRenderer label-only mapper (Plan 04 Task 1, committed 02b2154)"
  - phase: 07-streaming-chat (v1.0.0)
    provides: "ChatPanelFragment MessageList substrate, submitChatTurn doOnNext/doOnError/doOnComplete, StreamEventRenderer, finishStreamInternal/clearMessageList/onDetach teardown sites, accessUi/accessUiAuthenticated"
  - phase: 07.2-audit-schema-tree-lite (v1.0.0)
    provides: "AiAuditEvent (@Store(agentstore)) tree — CHAT root (parent is null) + TOOL/RETRIEVAL children (parent is not null), runId/kind/latencyMs/outcome/startedAt; AiAuditEventListView (admin-only)"
provides:
  - "Ephemeral KIND-keyed streaming-status <span class=ai-agent-status role=status aria-live=polite> in messageListSlot (a sibling AFTER <vaadin-message-list>): neutral typing indicator at turn start, CHAT on the first Content event (review #6), TOOL/RETRIEVAL on Activity events; removed entirely in every teardown site (review #7); never concatenated into the bubble"
  - "Collapsed-by-default per-turn Vaadin Details ('what the agent did — N steps · M ms') rendered inside one ordered Div.ai-agent-turn-activity appended after <vaadin-message-list> (review #2): label-only KIND-keyed step rows with per-step ms (em-dash '—' for unknown, never '0 ms') + an error/rollback indicator; hidden entirely for zero-tool turns"
  - "Live-turn step accumulation: ToolCall/ToolResult (deduped by toolCallId)/Activity(RETRIEVAL) into a per-fragment liveTurnSteps list capped at LIVE_TURN_STEP_CAP=50, cleared on every terminal/teardown site (OBS-04 — NOT in AiChatSessionState)"
  - "On Final: loadTurnSteps(activeRunId, conversationId) — a lazy UnconstrainedDataManager AiAuditEvent-by-runId read for REAL latencyMs (review #8); the transient arrival-delta steps are a fallback only"
  - "Post-navigation correlateHistoryTurnDetails: two raw-JPQL loadValues (.store(agentstore)) for the conversation's CHAT-root runIds (ordered by startedAt) + per-root child counts; zips 1:1 against the replayed ASSISTANT turns ONLY when the counts match (else none — no guess, debug-log, never throws); appends a collapsed history Details anchored by runId only for roots with childCount>0; expanding lazily + memoizedly (TURN_DETAILS_LOADED_KEY) re-reads that runId's children"
  - "loadTurnSteps unified live+history read: UnconstrainedDataManager.load(AiAuditEvent) with the MANDATORY where e.userUsername=:me and e.conversation.id=:cid clause + e.runId=:rid + e.parent is not null + a narrow fetch plan (kind/startedAt/finishedAt/latencyMs/outcome/errorClass — no name columns, no LOBs); never runId-only unconstrained"
  - ".ai-agent-status (+ @keyframes ai-agent-status-pulse + @media prefers-reduced-motion) + .ai-agent-turn-activity step-row CSS in ai-agent-chat.css"
  - "msg:// keys (en+vi): chatView.status.{neutral,chat,tool,retrieval}, chatView.turnDetail.{summary,summaryPending,step.tool,step.retrieval,step.chat,errorIndicator,unknownDuration}"
affects: [15-05 (TEST-19 leak-regex test over the rendered status text + the per-turn Details rows; NoNewPersistedStateTest / AiChatSessionStateTest invariants), 20-stt (the in-fragment status-row pattern its error/retry row reuses), ChatPanelFragment, ai-agent-chat.css, messages_en, messages_vi]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sibling-after-the-MessageList rendering: the live-chat substrate is a single <vaadin-message-list>; you cannot insert a sibling between two MessageListItems, so the status <span> and the per-turn Details live in messageListSlot AFTER the list (same trick as the NOTICE divs / intent-confirm rows). Per-turn disclosures are GROUPED in one ordered Div.ai-agent-turn-activity, newest last, each anchored by runId."
    - "Unified live+history audit read (loadTurnSteps): the just-completed turn's disclosure reads its runId's AiAuditEvent children on Final for REAL latencyMs (the live ToolCall→ToolResult arrival delta is a fallback only); the history disclosure re-reads the same way on first expand, memoized — one code path."
    - "AiAuditEvent reads from a non-admin chat user: AiAgentUserRole has NO AiAuditEvent EntityPolicy, so the constrained DataManager is not an option — use UnconstrainedDataManager with a MANDATORY userUsername+conversation.id filter on every read; never load by runId alone. Typed load(AiAuditEvent.class) infers the agentstore store; raw-JPQL loadValues needs .store(\"agentstore\") (project memory feedback_jmix_loadvalue_store)."
    - "No-guess history correlation: AiMessage has no runId — zip the conversation's CHAT-root runIds against the replayed ASSISTANT turns ONLY when the counts are equal; on any mismatch render NO disclosures (debug-log, never throw). A wrong association is worse than none."
    - "Structural no-leak: both the status line and the per-turn Details funnel exclusively through TurnDetailRenderer (label-only msg:// keys); never pass StreamingEvent.ToolCall/ToolResult toolName()/argsJson()/summary()/payloadJson() or AiAuditEvent eventName/argumentsJson/resultSummary/queryText into a rendered label; the audit fetch plan omits the name columns and LOBs."

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentStatusLineTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailHistoryTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  # (Task 1 — TurnDetailRenderer.java + TurnDetailRendererTest.java — was created/committed in 02b2154 prior to this session.)

key-decisions:
  - "Constrained-vs-unconstrained AiAuditEvent reads → UnconstrainedDataManager (confirmed). AiAgentUserRole has NO @EntityPolicy on AiAuditEvent — its Javadoc says so explicitly (\"No policies on AiAuditEvent, AiParameters, or AiKnowledgeDocument — users have zero access\"); AiAuditEventListView is an ADMIN view (AiAgentAdminRole has AiAuditEvent ALL). So the chat user CANNOT read AiAuditEvent via the constrained DataManager. The fragment uses UnconstrainedDataManager with a MANDATORY `where e.userUsername = :me and e.conversation.id = :cid` clause on ALL audit reads (the live-Final loadTurnSteps, the on-expand loadTurnSteps, and the grouped CHAT-roots/child-count correlation loadValues); never loads AiAuditEvent by runId alone unconstrained; the conversation was already ownership-checked at setConversationIdInternal (~line 563, conversationGateway.loadOrCreate(username, cid, null))."
  - "Correlation query form → two-query merge (NOT a single grouped left join). One ordered loadValues `select e.runId from ai_AiAuditEvent e where e.userUsername=:me and e.conversation.id=:cid and e.parent is null order by e.startedAt asc` and one grouped loadValues `select c.runId, count(c) from ai_AiAuditEvent c where c.userUsername=:me and c.conversation.id=:cid and c.parent is not null group by c.runId`, merged into an ordered runId list + a runId→childCount map. Chosen over a single `left join` on the @Composition self-relation (awkward in JPQL). Both reads add .store(\"agentstore\") (raw loadValues does NOT infer the agentstore store — project memory feedback_jmix_loadvalue_store). Zip semantics: if rootRunIds.size() == assistantTurnCount → zip 1:1, appendHistoryTurnDetails(runId, cid) for each root with childCount>0, skip childCount==0 roots entirely (no placeholder); else render NONE (log.debug, never throw, never guess)."
  - "liveTurnSteps cap = 50 (LIVE_TURN_STEP_CAP). Clearing on every terminal/teardown site already prevents cross-turn accumulation; the cap protects against a single pathological turn (review #12). recordLiveStep adds a step iff liveTurnSteps.size() < 50. The list is per-ChatPanelFragment-instance — NOT AiChatSessionState (OBS-04)."
  - "Unified loadTurnSteps-on-Final for real live timings (review #8 option a). On the terminal Final of the just-completed turn, .doOnComplete calls loadTurnSteps(activeRunId, conversationId) (the same UnconstrainedDataManager AiAuditEvent-by-runId read as the history re-read) so the disclosure shows REAL latencyMs for every step; the transient ToolCall→ToolResult arrival-delta steps are used only if that read returns nothing (best-effort). A step with no known ms still renders the em-dash, never \"0 ms\". This unifies the live and history code paths into one loadTurnSteps(runId, cid) helper."
  - "Em-dash unknown-duration mechanism. A null AiAuditEvent.latencyMs (run not finished, or a live RETRIEVAL step with no duration) ⇒ TurnDetailRenderer.StepRow.latencyMs() is null ⇒ buildStepRow renders TurnDetailRenderer.UNKNOWN_DURATION_TEXT (\"—\"), never \"0 ms\" / \"null ms\". chatView.turnDetail.unknownDuration is the localized key alternative."
  - "Grouped activity-block rendering (review #2). The transcript substrate is a single Vaadin MessageList — MessageListItems are DATA items inside one <vaadin-message-list>; you CANNOT insert a sibling between two MessageListItems. So the per-turn Details render as ONE GROUPED ACTIVITY AREA — an ordered Div.ai-agent-turn-activity appended after <vaadin-message-list> in messageListSlot (created lazily on first use, held in turnActivityBlock), holding the per-turn Details in turn order, each anchored by its runId in turnDetailsByRunId (so a re-rendered turn replaces, not duplicates). Still per-turn (one Details per turn with ≥1 tool call), still collapsed-by-default, still hidden entirely for zero-tool turns, still label-only."
  - "Neutral-status + first-Content-implies-CHAT sequencing (points #6/#11). Activity(CHAT) is NEVER emitted from the orchestration edge (Plan 02 / point #11) — the UI derives CHAT. At turn start (before any Activity/Content) the status shows the neutral typing indicator (chatView.status.neutral). On the FIRST Content event of the turn — regardless of any prior Activity(RETRIEVAL) — the status flips to CHAT/\"thinking…\" (chatView.status.chat; turnContentSeen guards it to once). A LATER Activity(...) after content still wins (the latest signal), but Content always implies at least CHAT."
  - "Teardown-site cleanup (point #7). removeStatusRow() + liveTurnSteps.clear() (where applicable) run in: .doOnComplete (via the doOnComplete lambda → finishStreamInternal), .doOnError (explicit liveTurnSteps.clear() + finishStreamInternal), finishStreamInternal() itself (removeStatusRow + turnContentSeen=false — reached by the stop-button handler via stopActiveStream), clearMessageList() (conversation switch — nulls statusRow + turnActivityBlock + turnDetailsByRunId + liveTurnSteps + turnContentSeen, the removeAllChildren detaches the elements), and onDetach (explicit liveTurnSteps.clear() + removeStatusRow() — the cancelled stream's .doOnComplete does NOT fire on dispose). The status <span> is GONE in every path, never blanked."
  - "Query-count-spy mechanism (point #13). ChatPanelFragmentTurnDetailHistoryTest wires a delegating UnconstrainedDataManager mock (RETURNS_DEEP_STUBS) whose loadTurnSteps read for a runId increments an AtomicInteger; the test asserts the counter is 0 before expand, 1 after the first expand (and ComponentUtil.getData(details, TURN_DETAILS_LOADED_KEY) == Boolean.TRUE), and still 1 after collapse + re-expand (the LOADED_KEY flag short-circuits)."

patterns-established:
  - "view/chat/fragment: the in-fragment status-row pattern — a <span role=status aria-live=polite> appended as a sibling AFTER <vaadin-message-list> in messageListSlot, text via Element.setText (HTML-escaped), removed entirely on every terminal/teardown site. Phase 20's STT error/retry row reuses this."
  - "view/chat/fragment: the per-turn disclosure pattern — one ordered Div.<feature>-activity appended after <vaadin-message-list>, holding collapsed <vaadin-details> in turn order anchored by an id (here runId), driven for the live turn by a lazy audit read on the terminal event and for history by a child-count-aware additive correlation pass over existing query paths + a memoized on-expand re-read."

requirements-completed: [OBS-01, OBS-02, OBS-04]

# Metrics
duration: ~75min
completed: 2026-05-12
---

# Phase 15 Plan 04: In-Fragment Observability — Status Line + Per-Turn Tool-Detail Summary

**Two in-fragment observability surfaces inside the shared `ChatPanelFragment`: (1) an ephemeral KIND-keyed streaming-status `<span class="ai-agent-status">` rendered as a sibling AFTER `<vaadin-message-list>` — neutral typing indicator at turn start, flips to CHAT on the first `Content` event (review #6), TOOL/RETRIEVAL on `Activity` events, removed entirely in every terminal/teardown site (review #7), never concatenated into the bubble; (2) a collapsed-by-default Vaadin `Details` per completed turn with ≥1 tool call, rendered inside one ordered `.ai-agent-turn-activity` block appended after `<vaadin-message-list>` (review #2 — a `Details` cannot sit between two `MessageListItem`s), with label-only KIND-keyed step rows + per-step ms (em-dash `"—"` for unknown, never `"0 ms"`) + an error/rollback indicator, hidden entirely for zero-tool turns; driven for the just-completed turn by a lazy `AiAuditEvent`-by-`runId` read on `Final` (unified `loadTurnSteps` — REAL `latencyMs`) and — for prior turns after a conversation-switch reload — by a child-count-aware additive correlation pass over the conversation's `AiAuditEvent` CHAT roots (zipped 1:1 against the replayed ASSISTANT turns ONLY when the counts match — else none, no guess) plus a lazy memoized on-expand re-read; the live step list is per-fragment, capped at 50, cleared on every terminal/teardown site (OBS-04); both paths funnel through the Vaadin-free `TurnDetailRenderer` mapper (label-only `msg://` keys — T-15-D1 by construction); the `AiAuditEvent` reads use `UnconstrainedDataManager` with a MANDATORY `where e.userUsername = :me and e.conversation.id = :cid` clause (RESEARCH Open Q1 — `AiAgentUserRole` has no `AiAuditEvent` policy, `AiAuditEventListView` is admin-only).**

## Performance

- **Duration:** ~75 min
- **Started:** 2026-05-11 (continuation of the 15-04 plan — Task 1 `TurnDetailRenderer` + `TurnDetailRendererTest` already committed in `02b2154`)
- **Completed:** 2026-05-12
- **Tasks:** 3 (Task 1 was committed before this session; Tasks 2 & 3 in this session)
- **Files modified this session:** 7 (3 created, 4 modified)

## Accomplishments

### Task 1 — TurnDetailRenderer (pre-committed: `02b2154`)
- `view/chat/fragment/TurnDetailRenderer.java` — a `final` Vaadin-free, Spring-free pure-static mapper: `statusKeyFor(ActivityKind)` → `chatView.status.chat`/`.tool`/`.retrieval` (and `chatView.status.neutral` for `null`), `neutralStatusKey()`, a nested `record StepRow(String labelKey, Long latencyMs, boolean errored)` (latency NULLABLE — data-only), `stepRow(ActivityKind, Long, AiToolCallOutcome)` + `stepRow(String auditKind, Long, AiToolCallOutcome)` (validates `auditKind ∈ {"CHAT","TOOL","RETRIEVAL"}`, throws `IllegalArgumentException` otherwise — defensive; the audit query already filters), `errorIndicatorKey()`, `unknownDurationKey()` + `UNKNOWN_DURATION_TEXT = "—"`, `summaryKey()`, `summaryArgs(int, long)`. No public method accepts a tool/entity name or free text — the input alphabet is the closed `ActivityKind` enum / the validated audit-`kind` String / a nullable `Long` ms / the `AiToolCallOutcome` enum. `errored = outcome ∈ {FAILED, ERROR, COMMIT_FAILED}`. Plus `TurnDetailRendererTest` (plain JUnit 5 + AssertJ): key mappings (incl. neutral), the ERROR/FAILED/COMMIT_FAILED-flag vs SUCCESS/IDEMPOTENT_REPLAY/null, the bad-audit-kind rejection, the null-latency ⇒ `"—"` (not `"0 ms"`) contract, the `summaryArgs` tuple, and a reflective check that no public method's parameter types are outside the closed alphabet.

### Task 2 — Ephemeral KIND-keyed streaming-status line (this session)
- `ChatPanelFragment`: `private com.vaadin.flow.dom.Element statusRow;` (null when no turn streaming) + `private boolean turnContentSeen;`.
- `showStatus(String messageKey)` — lazily creates `new Element("span")`, `addClassName("ai-agent-status")`, `setAttribute("role","status")`, `setAttribute("aria-live","polite")`, `messageListSlot.getElement().appendChild(statusRow)`, then `statusRow.setText(messages.getMessage(messageKey))`. HTML-escaped; no markdown, no `innerHTML` (T-15-D2).
- `removeStatusRow()` — `statusRow.removeFromParent(); statusRow = null;` — idempotent.
- `submitChatTurn(...)` at turn start: `turnContentSeen = false; liveTurnSteps.clear(); accessUi(() -> showStatus(TurnDetailRenderer.neutralStatusKey()));`.
- `doOnNext`: an `else if (evt instanceof StreamingEvent.Activity a)` branch BEFORE the `StreamEventRenderer.renderStreamEventDetails` call — records a RETRIEVAL live step (for `Activity(RETRIEVAL)`), then `accessUi(() -> showStatus(TurnDetailRenderer.statusKeyFor(a.kind())))`, then `return` (skips the markdown path). In the existing `Content` branch (the one that does `botMsg.appendText(md)`): when `!turnContentSeen` → `turnContentSeen = true; accessUi(() -> showStatus(TurnDetailRenderer.statusKeyFor(ActivityKind.CHAT)))` (review #6) — then the unchanged `botMsg.appendText(md)` runs (the status line is a SEPARATE sibling — never concatenated into the bubble).
- `removeStatusRow()` in `finishStreamInternal()` (reached by `.doOnComplete`/`.doOnError`/the stop-button handler via `stopActiveStream`), `clearMessageList()` (nulls `statusRow` after `removeAllChildren()`), and `onDetach` (explicit) — GONE in every teardown path (review #7).
- `ai-agent-chat.css`: `.ai-agent-status` (block, centered, secondary text, lumo spacing) + a `.ai-agent-status::after` animated ellipsis + `@keyframes ai-agent-status-pulse { 0%→"" 25%→"." 50%→".." 75%→"..." 100%→"" }` + `@media (prefers-reduced-motion: reduce) { .ai-agent-status::after { animation:none; content:"…" } }`. The `.ai-agent-sidebar*` rules from Plan 03 are untouched.
- `messages_en.properties` / `messages_vi.properties`: `chatView.status.neutral` / `.chat` / `.tool` / `.retrieval` (en: `working` / `thinking` / `searching data` / `retrieving documents`; vi: `đang xử lý` / `đang suy nghĩ` / `đang tìm dữ liệu` / `đang truy xuất tài liệu` — the trailing `…` is supplied by the CSS animation, so the bare strings are intentionally without trailing dots).
- `ChatPanelFragmentStatusLineTest` (plain JUnit + Mockito): `showStatus` appends a `<span.ai-agent-status>` sibling AFTER `<vaadin-message-list>` with `role`/`aria-live` set; subsequent `showStatus` updates the text in place (exactly one span); `removeStatusRow` detaches + nulls + is idempotent; `finishStreamInternal` and `clearMessageList` remove the span; the `doOnNext` wiring is asserted via a source scan (Activity branch + first-Content-implies-CHAT + neutral at start + `removeStatusRow()` + no `botMsg.appendText(messages.getMessage(...))`).

### Task 3 — Per-turn tool-detail Details as a grouped activity block (this session)
- `ChatPanelFragment`: `@Autowired private UnconstrainedDataManager unconstrainedDataManager;`; `LIVE_TURN_STEP_CAP = 50`; `TURN_DETAILS_LOADED_KEY` (the memoization flag set via `ComponentUtil.setData` on a `Details`); `private final List<LiveTurnStep> liveTurnSteps`; `private Div turnActivityBlock`; `private final Map<UUID, Details> turnDetailsByRunId`; a nested `record LiveTurnStep(String labelKey, Long latencyMs, boolean errored, UUID toolCallId, long startedAtNanos)` (the transient `startedAtNanos` is for the FALLBACK arrival delta only — never a tool/entity name).
- `turnActivityBlock()` — lazily creates `new Div()` with class `ai-agent-turn-activity`, `messageListSlot.add(...)` (appended after `<vaadin-message-list>`).
- `doOnNext`: `StreamingEvent.ToolCall tc` → `recordLiveStep(new LiveTurnStep(STEP_TOOL_KEY, null, false, tc.toolCallId(), System.nanoTime()))` (cap-guarded; never stores `tc.toolName()`/`tc.argsJson()`); `StreamingEvent.ToolResult tr` → `finishLiveStep(tr.toolCallId(), tr.outcome())` (dedup by `toolCallId`; records the arrival-delta ms as a fallback + the errored flag; never stores `tr.toolName()`/`tr.summary()`/`tr.payloadJson()`); `Activity(RETRIEVAL)` → `recordLiveStep(new LiveTurnStep(STEP_RETRIEVAL_KEY, null, false, null, System.nanoTime()))`.
- `.doOnComplete`: if `activeRunId != null && conversationId != null` → `loadTurnSteps(runId, cid)`; if that read is empty → `liveTurnStepsAsStepRows()` (fallback); if `!steps.isEmpty()` → `appendTurnDetails(runId, steps)`; then `liveTurnSteps.clear()`; then `finishStreamInternal()`. `.doOnError`: `liveTurnSteps.clear()` + `finishStreamInternal()` (no Details on an errored turn; never throws).
- `appendTurnDetails(UUID runId, List<StepRow>)` — builds a `Details` (`setOpened(false)`, summary via `MessageFormat.format(messages.getMessage(SUMMARY_KEY), summaryArgs(stepCount, totalMs))` where `totalMs` sums the non-null `latencyMs`, content = a `VerticalLayout` of label-only step rows), `ComponentUtil.setData(details, TURN_DETAILS_LOADED_KEY, Boolean.TRUE)` (no on-expand re-query for the live disclosure — the result is already real), `replaceTurnDetails(runId, details)` (removes a prior Details for the same runId, then `turnActivityBlock().add(details); turnDetailsByRunId.put(runId, details)`).
- `appendHistoryTurnDetails(UUID runId, UUID conversationId)` — builds a collapsed `Details` (`setSummaryText(messages.getMessage("chatView.turnDetail.summaryPending"))` — only because we already know `childCount > 0`) whose `addOpenedChangeListener` — guarded by `!Boolean.TRUE.equals(ComponentUtil.getData(details, TURN_DETAILS_LOADED_KEY))` — calls `loadTurnSteps(runId, conversationId)`, sets the summary + content as in `appendTurnDetails`, sets the `LOADED_KEY` flag; if (defensively) zero children come back the `Details` is removed; `replaceTurnDetails(runId, details)`.
- `buildStepRow(StepRow)` — a `Div.ai-agent-turn-activity__step` with a `Span` label (`messages.getMessage(step.labelKey())` — label-only, never a tool/entity name) + a `Span` duration (`step.latencyMs() == null ? UNKNOWN_DURATION_TEXT : step.latencyMs() + " ms"`) + (iff `step.errored()`) a `Span` error indicator (`messages.getMessage(errorIndicatorKey())`).
- `loadTurnSteps(UUID runId, UUID conversationId)` — `unconstrainedDataManager.load(AiAuditEvent.class).query("select e from ai_AiAuditEvent e where e.userUsername = :me and e.conversation.id = :cid and e.runId = :rid and e.parent is not null order by e.startedAt asc").parameter("me", currentAuthentication.getUser().getUsername()).parameter("cid", conversationId).parameter("rid", runId).fetchPlan(fp -> { fp.add("kind"); fp.add("startedAt"); fp.add("finishedAt"); fp.add("latencyMs"); fp.add("outcome"); fp.add("errorClass"); }).list()` → mapped via `TurnDetailRenderer.stepRow(e.getKind(), e.getLatencyMs(), e.getOutcome())`. Wrapped in `catch (RuntimeException)` → `log.debug` + `List.of()`. The narrow fetch plan omits `eventName`/`argumentsJson`/`resultSummary`/`queryText` (no name columns, no LOBs). The MANDATORY `userUsername`+`conversation.id`+`runId` filter is the row-level-access guard; never `runId`-only unconstrained (T-15-D3).
- HISTORY-REPLAY region: after `messageList.setItems(...)` (and counting `assistantTurnCount` in the replay loop), `correlateHistoryTurnDetails(cid, assistantTurnCount)` — two raw-JPQL `loadValues` (`.store("agentstore")`): the ordered CHAT-root `runId`s (`select e.runId from ... where ... and e.parent is null order by e.startedAt asc`) and the grouped child counts (`select c.runId, count(c) from ... where ... and c.parent is not null group by c.runId`); if `rootRunIds.size() == assistantTurnCount` → for each `runId` with `childCount > 0` call `appendHistoryTurnDetails(runId, cid)` (skip `childCount == 0` entirely); else `log.debug("turn-detail correlation skipped: {} assistant turns vs {} audit roots", ...)` and render nothing; the whole pass is wrapped in `catch (RuntimeException)` → `log.debug` (never throws).
- `clearMessageList()` / `onDetach` also drop `liveTurnSteps` + `turnDetailsByRunId` + `turnActivityBlock` (nulled).
- `ChatPanelFragmentTurnDetailTest` (plain JUnit + Mockito): `loadTurnSteps` reads the runId's `AiAuditEvent` children unconstrained with the mandatory `userUsername`+`conversation.id`+`runId` filter and maps them to label-only `StepRow`s (real ms on the TOOL rows, errored flag on the ERROR outcome, null latency on the RETRIEVAL child); `loadTurnSteps` swallows a `RuntimeException` and returns empty; `appendTurnDetails` adds ONE collapsed `<vaadin-details>` into `.ai-agent-turn-activity` (a sibling Div AFTER `<vaadin-message-list>`) with the `MessageFormat` summary + a `VerticalLayout` of 3 label-only step rows (real ms / em-dash `"—"` on the null-latency RETRIEVAL row, never `"0 ms"`/`"null ms"` / error indicator on the errored row / no tool-entity name); the same-runId `appendTurnDetails` replaces (no duplicates); `clearMessageList` drops the block + map + live steps; `LIVE_TURN_STEP_CAP == 50` and `recordLiveStep` honours it; a streaming-wiring source scan (real-timings-on-Final via `loadTurnSteps(runId, cid)` then fallback `liveTurnStepsAsStepRows()`, ToolCall/ToolResult/Activity(RETRIEVAL) label-only accumulation, no `toolName`/`argsJson`/`summary`/`payloadJson` stored, `correlateHistoryTurnDetails` after `setItems`, the mandatory `where e.userUsername = :me and e.conversation.id = :cid` clause, `.store("agentstore")`); `AiMessage.java` has no `runId`.
- `ChatPanelFragmentTurnDetailHistoryTest` (plain JUnit + Mockito): matching counts ⇒ a collapsed `Details` anchored by `runId` for each prior ASSISTANT turn whose CHAT root has ≥1 child, and NO `Details` (no placeholder) for a zero-child root; expanding one lazily loads its steps exactly once (a query-count spy `AtomicInteger` on a delegating `UnconstrainedDataManager` mock — review #13) then memoizes (`ComponentUtil.getData(details, TURN_DETAILS_LOADED_KEY) == Boolean.TRUE`; collapse + re-expand re-queries nothing); a count-mismatch conversation (3 ASSISTANT turns vs 2 CHAT roots) renders NO `Details` anywhere and throws nothing (review #3); a throwing `agentstore` (`loadValues` throws) is swallowed.

## Confirmed: constrained-vs-unconstrained AiAuditEvent read decision + rationale

`AiAgentUserRole` (`security/AiAgentUserRole.java`) has NO `@EntityPolicy` on `AiAuditEvent` — verified at execute time: its Javadoc explicitly says *"No policies on `AiAuditEvent`, `AiParameters`, or `AiKnowledgeDocument` — users have zero access"*, and the file's `@EntityPolicy` annotations cover only `AiConversation`/`AiMessage`/`AiTaskFile`/`AiExtractionDraft`. `AiAuditEventListView` is an **admin-only** view (`AiAgentAdminRole` has `@EntityPolicy(AiAuditEvent.class, ALL)`). Therefore the chat user CANNOT read `AiAuditEvent` through the constrained `DataManager`, and the fragment uses `UnconstrainedDataManager` with a **mandatory** `where e.userUsername = :me and e.conversation.id = :cid` clause on every audit read (the live-`Final` `loadTurnSteps`, the on-expand `loadTurnSteps`, and the grouped CHAT-roots/child-count correlation `loadValues`); it never loads `AiAuditEvent` by `runId` alone unconstrained. The conversation itself was already ownership-checked at `setConversationIdInternal` (`conversationGateway.loadOrCreate(currentAuthentication.getUser().getUsername(), cid, null)` throws `ConversationNotFoundException` for a foreign/missing id). Typed `unconstrainedDataManager.load(AiAuditEvent.class)` infers the `agentstore` store; the raw-JPQL `loadValues` correlation reads add `.store("agentstore")` explicitly (project memory `feedback_jmix_loadvalue_store`).

## New `msg://` keys

| Key | en | vi |
|-----|-----|-----|
| `chatView.status.neutral` | `working` | `đang xử lý` |
| `chatView.status.chat` | `thinking` | `đang suy nghĩ` |
| `chatView.status.tool` | `searching data` | `đang tìm dữ liệu` |
| `chatView.status.retrieval` | `retrieving documents` | `đang truy xuất tài liệu` |
| `chatView.turnDetail.summary` | `what the agent did — {0} steps · {1} ms` | `agent đã làm gì — {0} bước · {1} ms` |
| `chatView.turnDetail.summaryPending` | `what the agent did` | `agent đã làm gì` |
| `chatView.turnDetail.step.tool` | `Searched data` | `Đã tìm dữ liệu` |
| `chatView.turnDetail.step.retrieval` | `Retrieved documents` | `Đã truy xuất tài liệu` |
| `chatView.turnDetail.step.chat` | `Generated reply` | `Đã tạo phản hồi` |
| `chatView.turnDetail.errorIndicator` | `(error — rolled back)` | `(lỗi — đã hoàn tác)` |
| `chatView.turnDetail.unknownDuration` | `—` | `—` |

(`chatView.status.*` deliberately have no trailing `…` — the CSS `::after` animated ellipsis supplies it; the `@media (prefers-reduced-motion)` guard substitutes a static `…`.)

## Task Commits

1. **Task 1: Build the Vaadin-free TurnDetailRenderer label-only mapper** — `02b2154` (feat) — _committed prior to this session_ (combined RED/GREEN: the test references the new class so a standalone RED would not compile — same precedent as 15-01/15-03 Task 1).
2. **Tasks 2 + 3: in-fragment observability — status line + per-turn tool-detail Details** — `34f3793` (feat) — the `ChatPanelFragment` changes for both surfaces (one cohesive edit to `submitChatTurn` and its teardown sites) + `.ai-agent-status`/`.ai-agent-turn-activity` CSS + the new `msg://` keys (en+vi) + `ChatPanelFragmentStatusLineTest`.
3. **Task 3 tests: per-turn tool-detail @UiTest cases (live Final read + history correlation)** — `32f61ab` (test) — `ChatPanelFragmentTurnDetailTest` + `ChatPanelFragmentTurnDetailHistoryTest`.

**Plan metadata:** _(this commit)_ `docs(15-04): complete in-fragment observability plan` — this SUMMARY + STATE.md + ROADMAP.md + REQUIREMENTS.md.

## Files Created/Modified (this session)

- `view/chat/fragment/ChatPanelFragment.java` — `statusRow`/`turnContentSeen` + `showStatus`/`removeStatusRow`; the `Activity` branch + first-`Content`-implies-CHAT in `doOnNext`; `liveTurnSteps` (capped at 50) + `LiveTurnStep` record + `recordLiveStep`/`finishLiveStep`/`liveTurnStepsAsStepRows`; `turnActivityBlock()` + `appendTurnDetails`/`appendHistoryTurnDetails`/`replaceTurnDetails`/`buildTurnDetails`/`populateTurnDetails`/`buildStepRow`; `loadTurnSteps` (unconstrained, narrow fetch plan, mandatory filter); `correlateHistoryTurnDetails` (two `loadValues` reads, zip/no-guess); `@Autowired UnconstrainedDataManager`; teardown cleanup in `.doOnComplete`/`.doOnError`/`finishStreamInternal`/`clearMessageList`/`onDetach`; imports for `ComponentUtil`, `Details`, `UnconstrainedDataManager`, `AiAuditEvent`, `AiToolCallOutcome`.
- `META-INF/resources/frontend/styles/ai-agent-chat.css` — `.ai-agent-status` + `.ai-agent-status::after` + `@keyframes ai-agent-status-pulse` + `@media (prefers-reduced-motion: reduce)`; `.ai-agent-turn-activity` + `.ai-agent-turn-activity__steps`/`__step`/`__step-duration`/`__step-error`. No change to the `.ai-agent-sidebar*` rules.
- `messages_en.properties` / `messages_vi.properties` — the 11 new `chatView.status.*` + `chatView.turnDetail.*` keys.
- `test/.../ChatPanelFragmentStatusLineTest.java` (new), `test/.../ChatPanelFragmentTurnDetailTest.java` (new), `test/.../ChatPanelFragmentTurnDetailHistoryTest.java` (new).

## Deviations from Plan

### Test-shape adjustment (within plan intent, not a Rule deviation)

The plan calls the three new fragment tests `@UiTest`. The existing `ChatPanelFragment` test harness in this repo (`ChatPanelFragmentLoadingIndicatorTest`, `ChatPanelFragmentConversationIdTest`) is **plain JUnit 5 + Mockito with reflective field injection + source-scan assertions** — there is no working `@UiTest`/`@SpringBootTest` harness for the fragment package (a documented pre-existing Spring-context boot regression — see `.planning/phases/13.../deferred-items.md` and `STATE.md` "Blockers"; v1.2 plans are explicitly told to "prefer XML/source-scan or pure-Mockito tests for UI/contract coverage where the boot context is implicated"). The three new tests follow that established precedent: they exercise the helper methods directly against real `VerticalLayout`/`MessageList`/`Details` components (no UI attachment needed) and assert the `doOnNext`/history wiring via a source scan (the `accessUi`-wrapped UI mutations need a live `UI` to run, which a unit test cannot provide). All the plan's acceptance criteria are covered: status sibling-ordering/role/aria-live/first-Content flip/teardown removal; the collapsed `Details` in `.ai-agent-turn-activity` with label-only rows + real ms + em-dash + error indicator; the zero-tool no-`Details` case; the live-step cap; the history correlation zip/no-guess; the query-count-spy memoization (review #13); the no-leak input alphabet (Task 1's reflective test). The same precedent was used in 15-03 (`ChatSurfaceMounterTest` etc. are pure-Mockito/source-scan).

No other deviations — no scope creep, no architectural change, no `Rule 4` checkpoint. `git diff --stat` confirms no change to `chat-panel-fragment.xml`, `AiAuditEvent.java`, `AiMessage.java`, `AiChatSessionState.java`, and no new file under `**/liquibase/**`.

## Issues Encountered

- The Gradle `:ai-agent:ai-agent:test` worker still intermittently logs a JVM shutdown warning / can drop `hs_err_pid*`/`replay_pid*` dumps (already `.gitignore`d by Plan 03) — not caused by this plan; the full module test ran green.

## Verification Performed

- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.TurnDetailRendererTest"` — green (Task 1, pre-committed).
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentStatusLineTest"` — green.
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailTest" --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailHistoryTest"` — green.
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*"` — green (no chat-flow regression; fragment + surface + stream + stop + intent + notice + crm-layout tests).
- `./gradlew :ai-agent:ai-agent:test` (full add-on module) — green.
- `git diff --stat` — only `ChatPanelFragment.java` + `ai-agent-chat.css` + `messages_en.properties` + `messages_vi.properties` modified; the three new test files added; no change to `chat-panel-fragment.xml` / `AiAuditEvent.java` / `AiMessage.java` / `AiChatSessionState.java`; no new `liquibase/**`.
- Manual UI verification (app on http://localhost:8088) NOT run this session — the build/test gates are the verification of record; manual UI is the `<verification>` block's optional check and the app is not auto-started (project memory `project_local_dev_port`). Plan 05 (TEST-19) is the formal leak-regression gate; this plan makes that assertion pass by construction (label-only `TurnDetailRenderer` + narrow audit fetch plan).

## User Setup Required

None — no external service configuration; no new dependency.

## Next Phase Readiness

- The in-fragment status-row pattern (`<span role=status aria-live=polite>` sibling after `<vaadin-message-list>`, removed on every teardown site) is established and ready for Phase 20's STT error/retry row to reuse.
- The per-turn `Details` + the `loadTurnSteps`/`correlateHistoryTurnDetails` reads are in place; Plan 05 (TEST-19) can run the Phase-9 leak regexes against the rendered status text + the per-turn `Details` rows and assert `AiChatSessionState` / no-new-persisted-state invariants.
- No blockers. Plan 15-05 is the only remaining plan in Phase 15.

## Threat Flags

None — no new security surface. The status line carries only the closed `ActivityKind` enum (mapped to a `msg://` key); the per-turn `Details` carries only the validated audit `kind` String + a `Long` ms + an `AiToolCallOutcome` (all label-only via `TurnDetailRenderer`); the `AiAuditEvent` reads are `UnconstrainedDataManager` + a mandatory `userUsername`+`conversation.id` filter + a narrow fetch plan that omits the name columns and LOBs; no new `@Entity`/`@Table`/Liquibase; `AiMessage` is read-only here. Matches the plan's threat register T-15-D1..D5 (all `mitigate`).

## Self-Check: PASSED

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` — FOUND (contains `ai-agent-turn-activity`, `loadTurnSteps`, `correlateHistoryTurnDetails`, `showStatus`, `removeStatusRow`, `UnconstrainedDataManager`)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/TurnDetailRenderer.java` — FOUND (Task 1, committed `02b2154`)
- `ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css` — FOUND (contains `.ai-agent-status`, `@keyframes ai-agent-status-pulse`, `prefers-reduced-motion`, `.ai-agent-turn-activity`)
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — FOUND (contains `chatView.status.neutral`, `chatView.turnDetail.summary`)
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — FOUND (contains `chatView.status.neutral`, `chatView.turnDetail.summary`)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentStatusLineTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailHistoryTest.java` — FOUND
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/15-04-SUMMARY.md` — FOUND (this file)
- Commit `02b2154` (Task 1) — FOUND
- Commit `34f3793` (Tasks 2+3 production + status test) — FOUND
- Commit `32f61ab` (Task 3 turn-detail tests) — FOUND

---
*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Completed: 2026-05-12*
