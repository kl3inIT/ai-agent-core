# Phase 7: Flow UI - Context

**Gathered:** 2026-04-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Ship the full plug-and-play Jmix Flow UI layer for the add-on, all inside the existing `ai-agent` module:

- `ChatView` — end-user streaming chat with transparent tool-call cards, RAG citations, `New chat` + `Stop` controls (UI-01, UI-02)
- `ConversationListView` + `ConversationDetailView` — ownership-filtered list and read-only transcript replay; admin-aware (UI-03)
- `ParametersListView` + `ParametersDetailView` — admin CRUD over profiles with a structured Form (source of truth) and a read-only YAML Preview tab; `Set active` action (UI-04)
- `KnowledgeBaseView` — multi-file upload, status-aware grid, delete, reingest (UI-05)
- `ToolCallAuditListView` — typed filter bar + generic filter, Excel + JSON export (Jmix `gridexport` add-on), row-click detail dialog (UI-06)
- Namespaced menu (`aiAgent.*`), full en + vi locale parity, admin role gating on Parameters/KB/Audit (UI-08, UI-09, UI-10)

**In scope:** streaming response path (`.stream()` + Vaadin Push) — deferred here from Phase 4 per D-16.

**Out of scope (future phases / deferred):**
- Mutation tools UI (Phase 8+)
- Floating/embedded chat launcher and admin-configurable chat-surface toggle (v2)
- Cross-conversation search, message-level editing
- `AiExposureRule` UI (dropped entirely per D-10)
- `jmix-app` new views — host stays minimal; smoke test only

</domain>

<decisions>
## Implementation Decisions

### Streaming + Cancel (Chat path)

- **D-01:** `ChatService` gains a streaming method returning `Flux<ChatResponse>` (Spring AI `.stream()`). `ChatView` subscribes and appends tokens via `UI.access()`. Planner/researcher verify the exact Spring AI 1.1.4 API shape against `jmix-ai-backend`'s `ChatImpl` + `ChatView`.
- **D-02:** Vaadin `@Push` is enabled globally by the add-on (plug-and-play). Shipped as an `AppShellConfigurator` contributed by the add-on or documented snippet auto-applied via starter; transport = WebSocket with XHR fallback (`WEBSOCKET_XHR`). Researcher confirms cleanest Jmix-friendly mechanism (AppShell vs. config).
- **D-03:** `Stop` reuses the existing `CancellationRegistry` (Phase 5). Stream subscription is registered under the current `runId`; `Stop` click → registry cancels → `Flux` disposes → audit post-row outcome = `CANCELLED`.
- **D-04:** Non-streaming providers/models degrade gracefully. `ChatService.stream(...)` either returns a single-chunk `Flux` (when provider doesn't stream) or the call-site falls back to blocking `ask(...)`. `Stop` is hidden/disabled when no active stream. No new `AiParameters` knob.
- **D-05:** Review `jmix-ai-backend/src/main/java/io/jmix/ai/backend/view/chat/ChatView.java` + `ChatProgressView.java` + `ChatImpl.java` and current Vaadin Push / `UI.access` docs **before finalizing** the stream wiring. Generalize — do not copy domain specifics.

### ChatView Rendering

- **D-06:** Plain `VerticalLayout` inside a `Scroller` for the message list. Each message is a bubble component (role-styled). VirtualList deferred — MVP conversation sizes don't justify the UX tradeoff.
- **D-07:** Markdown rendering = server-side (Flexmark → sanitized HTML) wrapped in Vaadin `Html`. Works with streaming: each `UI.access` tick re-parses current buffered markdown and replaces content. Add `com.vladsch.flexmark:flexmark` dependency to the `ai-agent` module.
- **D-08:** Tool-call cards render as **collapsed badge → expand on click**. Closed state: `🔧 {toolName} ({shortSummary})`; expanded state: args JSON (pretty) + raw/structured result + outcome badge. Reusable component, shared between ChatView and ConversationDetailView (D-17).
- **D-09:** Citations render as **inline `[n]` superscript markers → dialog on click**. Dialog shows chunk text + source `AiKnowledgeDocument` name (link to KB view filtered to that doc). Citation metadata already carried by `ChatResponseDto` from Phase 5.

### Parameters View (Form-primary, read-only YAML preview)

- **D-10:** Parameters detail view is **hybrid Form + YAML Preview**, with the **Form as the single source of truth** for v1. YAML tab is read-only and regenerated from form state. Parallel editing of both representations is NOT supported in v1.
- **D-11:** Form covers **all** `AiParametersBody` fields (not just common ones): model, temperature, topP, systemPrompt (multi-line TextArea), enabledTools (MultiSelectComboBox populated from registered `@Tool` beans), rate/token/iteration caps, output-scanner pattern overrides. Planner reads `AiParametersBody` + `AiParametersBodyYamlMapper` and enumerates the concrete field list.
- **D-12:** YAML Preview regenerates **live on every form change** via `AiParametersBodyYamlMapper`. Small CPU cost acceptable for parameter editing.
- **D-13:** Validation is **per-field inline** (range checks for temperature/topP, non-blank required fields, known tool-bean names) + **blocks Save** until clear. Uses Jmix data-binding validators / `Validator` API.
- **D-14:** `Set active` is surfaced both as a **list-view row action** (with selected-row enable) **and** a **detail-view button**. Active profile shown with a badge/icon in the list. **Immediate commit, no confirm dialog** — audit row records the switch; undoing is a matter of re-selecting.

### KnowledgeBase View

- **D-15:** Upload uses the **Jmix Flow UI `<upload>` component** (not raw Vaadin `Upload`). Multi-file, drag-and-drop, progress — all out of the box from Jmix. Each uploaded file triggers one async `IngesterManager` call and creates a `PENDING` row immediately.
- **D-16:** Status refresh uses **Vaadin Push driven by document-status-layer events**. Ingestion status-change events (emitted by `IngesterManager`/`AsyncIngestionWorker`) refresh the grid rows via `UI.access()`. No polling. Researcher confirms the event-emission contract (Spring `ApplicationEventPublisher` vs. `Broadcaster` pattern — pick whichever slots cleanly into existing ingestion code).
- **D-17:** Status column renders as **Vaadin `Badge`** with variants: `PENDING=contrast`, `READY=success`, `FAILED=error` (tooltip shows failure reason). All labels via `msg://` keys.
- **D-18:** `Reingest` is a **row action with confirm dialog** ("Reingest {filename}? Existing chunks will be replaced."). No bulk-only mode; row-level covers both single-fix and batch workflows (select rows → invoke action).

### Audit View

- **D-19:** Filter UX = **typed filter bar + Jmix GenericFilter**. Typed bar: `user` ComboBox, `tool` ComboBox, `outcome` enum select, `date range` picker — covers the 80% case in one click. GenericFilter available below for ad-hoc queries (latencyMs thresholds, denialReason text match).
- **D-20:** Audit export uses the **Jmix gridexport add-on** (`io.jmix.gridexport:jmix-gridexport-flowui-starter`) with the two natively supported actions: `grdexp_excelExport` and `grdexp_jsonExport`. Adds the dependency to the `ai-agent` module; both actions are declared on the audit `dataGrid` and honor the current filter/sort; gives consistent i18n-aware UX. CSV is explicitly **not** shipped (the add-on does not provide a CSV action; aligning with the add-on's native formats avoids a hand-rolled `StreamResource` branch). Resolves RESEARCH Open Q#1 via Option A.
- **D-21:** Row click opens a **modal Dialog** with full audit details (runId, pre/post rows, tool args JSON, tool result, errorClass, latencyMs, denialReason, flagged status). No separate detail view; keeps admins in list context.
- **D-22:** Outcome column renders as **Vaadin `Badge`** variants: `SUCCESS=success`, `FAILED=error`, `TIMEOUT=contrast`, `CANCELLED=tertiary`. Consistent with KB status rendering (D-17).

### Conversation Replay

- **D-23:** `ConversationDetailView` = **single scrollable transcript**. Reuses ChatView bubble + tool-card + citation components (read-only mode). Minimal net new UI code.
- **D-24:** Admin scope = **same `ConversationListView`, role-aware filter**. Non-admin: filtered to `createdBy = currentUser`. Admin (has `AiAgentAdminRole`): ownership filter dropped, extra `User` filter column added. One view class, branch on role at load time.
- **D-25:** Tool-call details in replay use the **same collapsed-badge → expand** component as ChatView (D-08). Consistency across live and replay.
- **D-26:** Replay supports a **"Continue in chat" button** that opens `ChatView` with the selected `conversationId` active. Chat memory continues from the existing conversation. Requires `ChatView` to accept `conversationId` as a route parameter in addition to its default "new chat" path — planner specifies the routing contract.

### Module, Packaging, Security, i18n

- **D-27:** Views live under `com.vn.agent.view.{chat, conversation, parameters, knowledge, audit}`. One subpackage per feature area. Matches existing module organization (`orchestration/`, `rag/`, `guard/`, `parameters/`).
- **D-28:** Admin gating = **ResourceRole policies + menu visibility**. `AiAgentAdminRole` adds `@ViewPolicy` + `@MenuPolicy` for Parameters/KB/Audit. Belt-and-suspenders: `@ViewAccessChecker` (or equivalent Jmix view-level access annotation) on the view class for direct-URL protection.
- **D-29:** Chat-surface architecture: **one shared chat backend + one reusable chat panel component**. v1 ships a full `ChatView` route only. **Deferred to v2:** a lightweight user-facing chat surface (floating launcher / embeddable panel) and an admin-configurable toggle that selects which chat surface is exposed. Plan the chat bubble/transcript as a reusable Jmix Fragment so v2 can embed it without refactoring.
- **D-30:** i18n bar = **100% en + vi parity, zero hardcoded strings**. Every visible string has a `msg://` key in both `messages.properties` and `messages_vi.properties`. Planner includes a coverage test (e.g., scan view XML + Java for literal strings; fail build on miss).
- **D-31:** `jmix-app` host stays unchanged — no new demo views or seeded narratives. Success-criterion verification = manual bootRun click-through + Playwright smoke if available. Resist scope creep into the host app.

### Claude's Discretion

- Exact Jmix Fragment boundaries (reusable chat panel shape): planner decides based on v2 embedding requirements.
- Concrete Flexmark config (extensions, sanitizer) and markdown→HTML security posture — apply standard safe defaults.
- Exact CSS / Lumo variant choices for bubble styling.
- Concrete Badge colour mapping terminology if Lumo variants differ from "success/error/contrast/tertiary".
- Whether `AppShellConfigurator` contribution (D-02) is a ship-with-the-add-on class or a documented snippet — pick whichever avoids breaking host `AppShellConfigurator` overrides.
- Package name for the reusable chat-panel Fragment (likely `com.vn.agent.view.chat.fragment`).
- Exact wiring of ingestion status-change events (ApplicationEventPublisher vs. Broadcaster) — pick whichever integrates cleanest with existing `IngesterManager`/`AsyncIngestionWorker`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope anchors
- `.planning/ROADMAP.md` §Phase 7 — Goal, deliverables, success criteria (5 criteria)
- `.planning/REQUIREMENTS.md` — UI-01..UI-06, UI-08..UI-10 (UI-07 dropped per D-10); SEC-01..SEC-04
- `.planning/PROJECT.md` — Built-in Flow UI v1 scope bullets, Access & security posture

### Prior phase decisions that constrain Phase 7
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` — D-01 (2-module shape; `ai-agent-flowui` split deferred — views ship inside `ai-agent`)
- `.planning/phases/02-foundations/02-CONTEXT.md` — Entity shapes: `AiConversation`, `AiMessage`, `AiParameters`, `AiKnowledgeDocument`, `AiToolCallAudit`; role interfaces `AiAgentUserRole` + `AiAgentAdminRole`; D-10 scope alignment
- `.planning/phases/04-orchestration-core/04-CONTEXT.md` — D-02 advisor chain; D-05/D-06 `AiMessage` is the replay source; D-12 audit row shape (runId, outcome, latencyMs, errorClass); D-16 streaming deferred to Phase 7 (now owned here)
- `.planning/phases/05-rag-layer/05-CONTEXT.md` — `CancellationRegistry` shape and lifecycle (Stop reuses this); citation metadata attached to `ChatResponseDto`; `IngesterManager` / `AsyncIngestionWorker` status lifecycle
- `.planning/phases/06-parameters-structured-output-guardrails/06-CONTEXT.md` — `AiParametersBody` field shape, `AiParametersBodyYamlMapper`, `Overrides` record, active-profile semantics, `ChatResponseDto.flagged` / matched-pattern field

### Reference implementations (pattern-learning only, not dependencies)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/view/chat/ChatView.java` — streaming ChatView shape; review before finalizing D-01/D-05
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/view/chat/ChatProgressView.java` — progress/streaming indicator pattern
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/chat/ChatImpl.java` — Flux-based chat driver reference
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/view/chatlog/ChatLogListView.java` + `ChatLogDetailView.java` — conversation list / replay shape reference

### Framework docs (researcher fetches via Context7)
- Vaadin Flow — `@Push`, `UI.access()`, `AppShellConfigurator`, `Broadcaster` pattern, Scroller + VerticalLayout composition
- Jmix Flow UI — `<upload>` component, `@ViewController`/`@ViewDescriptor`, `StandardListView`/`StandardDetailView`, Fragments, GenericFilter, `Badge` styling (Lumo), role-based view policies
- Jmix gridexport add-on — `grdexp_excelExport` and `grdexp_jsonExport` action types; dependency `io.jmix.gridexport:jmix-gridexport-flowui-starter`; XML declaration under `<dataGrid>/<actions>` with companion button bound via `action="{grid}.excelExport"` / `action="{grid}.jsonExport"`
- Spring AI 1.1.4 — `ChatClient.stream()` / `Flux<ChatResponse>` shape (verify against M4)
- Flexmark — core parser config + HTML renderer + sanitizer guidance

### Skills (invoke during planning/execution)
- `jmix-views` — view controller + descriptor patterns
- `jmix-fragments` — reusable chat-panel Fragment for D-29
- `jmix-i18n` — message bundle conventions (D-30)
- `jmix-security-roles` — ResourceRole + ViewPolicy/MenuPolicy for D-28
- `jmix-services` — DataManager usage inside views

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets (already built in earlier phases)
- `ChatService` (`com.vn.agent.ChatService`) — `ask(...)` blocking + `askTyped(...)`; this phase adds a streaming method.
- `ChatResponseDto` (`com.vn.agent.orchestration.ChatResponseDto`) — carries content, tool-call metadata, citations, `flagged` flag, matched-pattern key.
- `ConversationGateway` (`com.vn.agent.orchestration.ConversationGateway`) — ownership-enforced conversation loader; ConversationListView/DetailView consume this.
- `ParametersService` (`com.vn.agent.parameters.ParametersService`) — CRUD over `AiParameters`, YAML validation, `setActive(...)` action hook.
- `AiParametersBody` + `AiParametersBodyYamlMapper` (`com.vn.agent.parameters.*`) — the authoritative schema the Form binds to; YAML preview is derived via the mapper (D-12).
- `IngesterManager` + `AsyncIngestionWorker` + `CancellationRegistry` (`com.vn.agent.rag.*`) — upload/status/reingest; event source for D-16; stream cancel source for D-03.
- `AiToolCallAudit` + `AuditWriter` (`com.vn.agent.audit.*`, `com.vn.agent.entity.*`) — audit list grid data; runId threading for detail dialog (D-21).
- Existing `menu.xml` (`src/main/resources/com/vn/agent/menu.xml`) — only has `AI` root menu entry so far; Phase 7 populates `aiAgent.*` children.
- Existing `messages.properties` + `messages_vi.properties` — 108 lines each currently; Phase 7 adds full view/menu/label coverage.

### Established Patterns
- All entity persistence via `DataManager` (never `EntityManager`) — view controllers follow this.
- `DataManager.create()` / `Metadata.create()` for new entity instances (not constructors).
- Constructor injection in services; `@ViewComponent` + `@Autowired` split in views (per CLAUDE.md).
- Liquibase changelogs under `src/main/resources/com/vn/agent/liquibase/changelog/` — numbered (010, 020, ...). Phase 7 should need no new DDL; if it does, use the next number.
- `messages.properties` for base/en, `messages_vi.properties` for vi — both must stay in parity.

### Integration Points
- `ai-agent` module `ai-agent.gradle` already depends on `io.jmix.flowui:jmix-flowui-starter` and `jmix-flowui-themes` — no new Jmix UI bootstrap work needed.
- `AIAutoConfiguration` (`com.vn.agent.AIConfiguration`) — may need new bean wiring for the Flexmark renderer bean, a ChatPushConfigurer, or a reusable chat-panel Fragment factory.
- `menu.xml` is already loaded by Jmix — Phase 7 extends it under the existing `AI` root.
- New dependencies likely added to `ai-agent/ai-agent.gradle`: `flexmark` (markdown), Jmix gridexport add-on; verify none pull Hilla/Copilot (existing excludes preserved).

</code_context>

<specifics>
## Specific Ideas

- Review `jmix-ai-backend` ChatView + ChatImpl + ChatProgressView **before** finalizing the Flux + Push wiring (user-directed).
- Use **Jmix Flow UI `<upload>` component** rather than raw Vaadin `Upload` — user-directed specifically because Jmix already supports multi-file + drag-and-drop + progress out of the box.
- Streaming UI updates driven by the **document-status-layer events + Push** (not polling) — user-directed for the KB view.
- Architect the chat panel as a **reusable component (Jmix Fragment)** even though v1 only routes to a full ChatView — this is the substrate for the v2 lightweight user chat surface.

</specifics>

<deferred>
## Deferred Ideas

- **Floating / embeddable user chat launcher** — lightweight chat surface alongside the full ChatView. Deferred to v2. D-29 plans the reusable Fragment now so v2 can embed without refactoring.
- **Admin-configurable chat-surface toggle** — admin setting to pick which chat surface is exposed to end users (full route vs. floating launcher vs. embedded panel). Deferred to v2.
- **"Continue in chat" was accepted for Phase 7 (D-26)**, but if routing/memory complexity surfaces during planning, the fallback is read-only replay with the button removed.
- **Mutation tools UI** — confirmation flows, dry-run preview. Post-v1 (mutation tools themselves are v1-deferred).
- **Scheduled / async export for large audit volumes** — D-20 ships the synchronous gridexport path (Excel + JSON); if volumes grow, revisit with chunked / async export.
- **Cross-conversation search** — not in v1 scope; consider post-v1.
- **VirtualList for very long conversation histories** — revisit if users hit scroll perf issues (D-06).

</deferred>

---

*Phase: 07-flow-ui*
*Context gathered: 2026-04-21*
