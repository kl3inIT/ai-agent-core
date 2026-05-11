# Phase 15: Right-Sidebar Chat Surface & Observability UX - Pattern Map

**Mapped:** 2026-05-11
**Files analyzed:** 16 modified + ~7 new (tests + optional renderer)
**Analogs found:** 16 / 16 modified have an in-file or sibling analog; new files all model on a concrete existing file

This phase is ~90% wiring existing mechanisms. Almost every change has a *same-file* analog (add a sibling enum constant / a sibling record arm / a sibling navbar button block) rather than a different-file one. The "analog" column below points to the exact lines to copy from.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `entity/AiChatSurface.java` | model (enum) | config | same file — `FULL_ROUTE` / `HEADER_BUTTON` constants | exact (in-file) |
| `entity/AiUiSettings.java` | model | config (varchar columns) | same file — `enabledSurfaceIds` / `defaultSurface` String columns + `getEnabledSurfaceSet()` | exact (in-file, no change likely needed) |
| `view/chat/AiUiSettingsService.java` | service | config-read/seed | same file — `createDefaultSettings()` (`EnumSet.allOf` already picks up a new enum value automatically) | exact (likely no change) |
| `view/uisettings/AiUiSettingsDetailView.java` | controller (detail view) | request-response | same file — `enabledSurfacesField.setItems(AiChatSurface.class)` / `defaultSurfaceField` (auto-lists a new enum value; only new `msg://` labels needed) | exact (likely no Java change) |
| `view/chat/ChatSurfaceMounter.java` | provider (`VaadinServiceInitListener`) | event-driven (UI lifecycle) | same file — `createChatButton()` / `mountHeaderButton()` / `attachDialogWindowToUi()` / `afterNavigation()` / `MountedChatSurfaceState` / `refreshMountedSurfaces()` / `shouldShowHeaderButton()` | exact (in-file) |
| `orchestration/StreamingEvent.java` | model (sealed DTO) | streaming | same file — existing `record` variants + `permits` clause; nested `AiToolCallOutcome` enum import precedent | exact (in-file) |
| `orchestration/StreamingSinkHolder.java` | service | streaming | same file — `currentOrForRun(runId)` already the emit hook (no change expected) | exact (likely no change) |
| `view/chat/fragment/StreamEventRenderer.java` | utility (pure-fn) | transform | same file — `renderStreamEventDetails(...)` exhaustive `switch` over `StreamingEvent` (compiler forces a new `case Activity` arm) | exact (in-file) |
| `view/chat/fragment/ChatPanelFragment.java` | fragment (UI) | streaming + CRUD-read | same file — `appendNoticeRow(...)` / `appendIntentConfirmRow(...)` / `appendActionChoiceRow(...)` (NOTICE-row sibling pattern), `submitChatTurn(...).doOnNext(...).doOnError(...).doOnComplete(...)`, `clearMessageList()`, `accessUi(...)`, `activeRunId`, `finishStreamInternal()` | exact (in-file) |
| `rag/advisor/AuditingDocumentRetriever.java` | service (decorator) | streaming-emit | `audit/ToolCallbackAuditDecorator.emitToolEvent(...)` (mirror exactly — best-effort sink emit via `streamingSinkHolder.currentOrForRun(runId)`) | role-match (cross-file) |
| `rag/advisor/RetrievalAugmentationAdvisorFactory.java` | config (`@Configuration`) | DI wiring | same file — `retrievalAugmentationAdvisor(...)` `@Bean` (add `StreamingSinkHolder` constructor arg to the inline `new AuditingDocumentRetriever(...)`) | exact (in-file) |
| `audit/ToolCallbackAuditDecorator.java` | service (decorator) | streaming-emit | same file — `emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(...)))` (add an `Activity(TOOL)` emit alongside) | exact (in-file) |
| `DefaultChatServiceImpl.java` | service (stream assembly) | streaming | same file — the `Flux<StreamingEvent>` assembly around line 575-648 (optionally emit `Activity(CHAT)` into `toolSink` before the model stream) | exact (in-file, optional per D-05) |
| `entity/AiAuditEvent.java` | model | CRUD-read | same file — `runId` / `kind` / `startedAt` / `latencyMs` / `outcome` / `errorClass` / `parent` / `children` already present; the on-expand read is a typed `DataManager.load(AiAuditEvent.class)...` in `ChatPanelFragment` (no entity change) | exact (no change) |
| `META-INF/resources/frontend/styles/ai-agent-chat.css` | config (stylesheet) | n/a | same file — `.ai-agent-attachment-notice`, `.ai-agent-intent-confirm`, `.ai-agent-action-choice` rules; `@CssImport("./styles/ai-agent-chat.css")` on `MessageBubbleComponent` | exact (in-file) |
| `com/vn/agent/messages_en.properties` + `messages_vi.properties` | config (i18n) | n/a | same files — `chatSurfaceMounter.headerButton.ariaLabel`, `com.vn.agent.entity/AiChatSurface.FULL_ROUTE`, `chatView.attachments.notice`, `aiUiSettingsDetail.*` keys | exact (in-file). NOTE: there is **no** `messages.properties` (no-locale default) — only `messages_en.properties` and `messages_vi.properties`; add keys to BOTH. |
| **NEW** `view/chat/fragment/TurnDetailRenderer.java` (optional) | utility (pure-fn) | transform | `view/chat/fragment/StreamEventRenderer.java` (Vaadin-free, Spring-free; `labels` map pull-through; pure-static) | role-match |
| **NEW** `view/chat/ObservabilityLeakTest.java` (TEST-19) | test | n/a | `guard/ToolNameLeakScannerTest.java` (`new ToolNamePatternProvider(List.of(), propsDefaults())` → `.buildPattern()` → `Pattern.compile(provider.asPattern().orElseThrow().regex())`); `guard/HostPrefixLeakScannerTest.java` for `HostPrefixPatternProvider` via `Metadata` mock or autowired in `@SpringBootTest` | exact (cross-file) |
| **NEW** `view/chat/fragment/ChatPanelFragmentStatusLineTest.java` | test (`@UiTest`) | n/a | `view/chat/fragment/ChatPanelFragmentLoadingIndicatorTest.java` | role-match |
| **NEW** `view/chat/fragment/ChatPanelFragmentTurnDetailTest.java` / `...TurnDetailHistoryTest.java` | test (`@UiTest`) | n/a | `view/chat/ChatPanelFragmentSurfaceSwitchTest.java` + `view/chat/ChatSurfaceMounterTest.java` (`@UiTest` + `@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})` + `@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})`) | role-match |
| **NEW** `view/chat/fragment/TurnDetailRendererTest.java` | test (plain JUnit) | n/a | `view/chat/RenderStreamEventTest.java` | role-match |
| existing test extensions: `ChatSurfaceMounterTest`, `ChatDialogViewTest`, `ChatPanelFragmentSurfaceSwitchTest`, `AiUiSettingsDetailViewTest`, `AiChatSessionStateTest`, `AiUiSettingsModelTest` | test | n/a | the files themselves | exact |

## Pattern Assignments

### `entity/AiChatSurface.java` (model enum, config)

**Analog:** same file.

**Pattern to copy** (whole file is the template — add one constant):
```java
public enum AiChatSurface implements EnumClass<String> {

    FULL_ROUTE("FULL_ROUTE"),
    HEADER_BUTTON("HEADER_BUTTON"),
    SIDEBAR("SIDEBAR");   // <-- new

    // ...everything else unchanged: getId(), fromId()...
}
```
No Liquibase changelog — `AI_UI_SETTINGS.ENABLED_SURFACE_IDS` / `DEFAULT_SURFACE` are varchar columns holding comma-joined ids (`AiUiSettings.java` lines 47-53).

---

### `view/chat/ChatSurfaceMounter.java` (provider, event-driven UI lifecycle)

**Analog:** same file. This is the heaviest change; mirror these existing blocks.

**Navbar-button creation pattern** (lines 128-138 — `createChatButton()`):
```java
private JmixButton createChatButton() {
    JmixButton chatButton = uiComponents.create(JmixButton.class);
    chatButton.setId(CHAT_BUTTON_ID);
    chatButton.addClassName(CHAT_BUTTON_CLASS_NAME);
    chatButton.setIcon(VaadinIcon.MAGIC.create());
    chatButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
    chatButton.getElement()
            .setAttribute("aria-label", messages.getMessage("chatSurfaceMounter.headerButton.ariaLabel"));
    chatButton.addClickListener(clickEvent -> toggleDialog());
    return chatButton;
}
```
→ New `createSidebarToggleButton()`: distinct id (`aiAgentSidebarToggleButton`), distinct class (`ai-agent-sidebar-toggle-button`), `VaadinIcon.PANEL.create()`, same `LUMO_TERTIARY`+`LUMO_ICON`, `aria-label` from a new `msg://` key, click listener → `toggleSidebar()`. Per D-03 also set `aria-pressed` (`"false"` initially) and toggle an `ai-agent-sidebar-toggle--active` class on open.

**Mount-on-navbar pattern** (lines 105-126 — `mountHeaderButton()`): self-healing (re-find by id if parent gone, else `appLayout.get().addToNavbar(...)`); `warnMissingAppLayoutOnce(...)` when no `AppLayout`. Mirror for `mountSidebarToggle(...)` and `mountSidebarPanel(...)`. NOTE pattern is *mount-always-then-`setVisible(false)`*; for SIDEBAR's "absent, not greyed" acceptance Vaadin `setVisible(false)` removes the element from the rendered DOM, which satisfies it (RESEARCH Open Q3).

**Attach-to-UI-element pattern (survives navigation)** (lines 192-197 — `attachDialogWindowToUi()`):
```java
private static void attachDialogWindowToUi(DialogWindow<?> dialogWindow) {
    UI ui = UI.getCurrent();
    if (ui != null) {
        ui.getElement().appendChild(dialogWindow.getElement());
    }
}
```
→ The sidebar wrapper `Div` (hosting the shared `ChatPanelFragment`) attaches to `UI.getCurrent().getElement()` the same way — NOT to the `AppLayout` content slot (which `setContent()`-replaces on every navigation). Stash the panel `Div` + the toggle button + `sidebarOpen` boolean on `MountedChatSurfaceState` (private record at lines 365-368) via the same `ComponentUtil.getData(ui, ...)` mechanism (lines 320-327), OR on the `@UIScope` `AiChatUIState` bean alongside `dialogInstance` (`AiChatUIState.java`) — planner picks.

**After-navigation re-sync pattern** (lines 94-103 — `afterNavigation()` + lines 255-267 — `refreshMountedSurfaces()`): on every `AfterNavigationEvent` re-assert the toggle is mounted (`mountSidebarToggle`), the panel `Div` is still attached (re-append if not), the CSS push class is still on the (possibly new) `AppLayout`, and visibility = `settings.getEnabledSurfaceSet().contains(AiChatSurface.SIDEBAR)` (mirror `shouldShowHeaderButton(...)` at lines 286-290).

**One-toggle-method pattern** (lines 140-157 — `toggleDialog()` / lines 199-208 — `closeExistingDialog`): both the navbar toggle and the in-panel closer call **one** `toggleSidebar()` (or `openSidebar()`/`closeSidebar()`) that flips the push class + `aria-pressed` + the toggle's "active" class together so state never drifts (D-03).

**Push-class JS toggle precedent** (lines 269-284 — `updateFullRouteMenuVisibility()` uses `ui.getElement().executeJs(...)` for a DOM toggle): the CSS push class can be added/removed on the `AppLayout` server-side via `appLayout.get().addClassName(...)`/`removeClassName(...)` (cleaner than executeJs here since the AppLayout component is reachable).

**Permission gate** (lines 296-300 — `isDialogViewPermitted()` via `UiShowViewContext` + `accessManager.applyRegisteredConstraints(...)`): gate the SIDEBAR mount/show on the same `isDialogViewPermitted()` check (Security Domain — V4/broken-access-control mitigation) AND on `enabledSurfaceSet.contains(SIDEBAR)`.

---

### `orchestration/StreamingEvent.java` (sealed DTO, streaming)

**Analog:** same file.

**Additive-variant pattern** (lines 23-29 `permits` + lines 31-63 records):
```java
public sealed interface StreamingEvent
        permits StreamingEvent.Content,
                StreamingEvent.ToolCall,
                StreamingEvent.ToolResult,
                StreamingEvent.Citation,
                StreamingEvent.Activity,   // <-- new
                StreamingEvent.Final,
                StreamingEvent.Error {

    enum ActivityKind { CHAT, TOOL, RETRIEVAL }   // closed enum — structural no-leak

    record Activity(ActivityKind kind) implements StreamingEvent {}
    // ...existing records unchanged...
}
```
Compiler then forces a `case StreamingEvent.Activity ...` arm in every exhaustive `switch` — currently only `StreamEventRenderer.renderStreamEventDetails` (line 120). `ChatPanelFragment.doOnNext` uses `instanceof` chains (line 734-776), so it's an additive `else if (evt instanceof StreamingEvent.Activity a)` branch there. Run the full chat test suite after (`RenderStreamEventTest`, `RenderStreamEventIntentPayloadTest`, `ChatViewStreamTest`).

---

### `view/chat/fragment/StreamEventRenderer.java` (pure-fn utility, transform)

**Analog:** same file — `renderStreamEventDetails(...)` switch (lines 117-148):
```java
return switch (event) {
    case StreamingEvent.Content c -> RenderedStreamEvent.markdown(c.markdownChunk());
    case StreamingEvent.ToolCall ignoredToolCall -> RenderedStreamEvent.markdown("");
    case StreamingEvent.ToolResult toolResult -> renderToolResult(toolResult);
    case StreamingEvent.Citation c -> { /* ... */ }
    case StreamingEvent.Error err -> { /* ... */ }
    case StreamingEvent.Final ignoredFinal -> RenderedStreamEvent.markdown("");
    // case StreamingEvent.Activity ignoredActivity -> RenderedStreamEvent.markdown("");   // <-- new arm (no markdown contribution; status handled in the fragment)
};
```
Add a no-op `Activity` arm here (the status line is rendered by the fragment from the raw event, not via the markdown path). The `labels`-map pull-through (lines 111-113, 139-142) and the Vaadin-free / Spring-free design are the model for the new optional `TurnDetailRenderer` class.

---

### `view/chat/fragment/ChatPanelFragment.java` (fragment, streaming + read)

**Analog:** same file.

**NOTICE-row sibling pattern** (lines 958-967 — `appendNoticeRow()` — the canonical template per D-06/D-08):
```java
private void appendNoticeRow(String text) {
    if (text == null) text = "";
    com.vaadin.flow.dom.Element notice = new com.vaadin.flow.dom.Element("div");
    notice.getClassList().add("ai-agent-attachment-notice");
    notice.setText(text);                       // <-- HTML-escaped by the Element API (XSS-safe; do NOT use innerHTML)
    messageListSlot.getElement().appendChild(notice);
    messageCount++;
}
```
→ Status line: a `<span class="ai-agent-status" role="status" aria-live="polite">` (set role/aria-live as in `appendIntentConfirmRow` lines 972-973: `row.getElement().setAttribute("role", "status"); row.getElement().setAttribute("aria-live", "polite");`), `setText(...)` only, appended to `messageListSlot.getElement()` AFTER `messageListSlot.add(messageList)` (so it renders as a sibling below the `<vaadin-message-list>`). Keep a field reference; on terminal `Final`/`Error` and in `finishStreamInternal()` call `statusRow.removeFromParent()` and null it.

**Per-turn `Details` sibling** — model on `appendIntentConfirmRow(...)`/`appendActionChoiceRow(...)` (lines 969-1051) which build a `Div` + `Span` + `Button`(s) and `messageListSlot.add(row)`. The per-turn disclosure is a `com.vaadin.flow.component.details.Details` instead: `Details d = new Details(); d.setOpened(false); d.setSummaryText(MessageFormat.format(messages.getMessage("...turnDetail.summary"), stepCount, totalMs)); d.add(buildStepRows(...)); messageListSlot.add(d);`. Anchor by `runId` in a `Map<UUID, Details>` (mirror `actionChoiceRowsByProposalId` at line 1044). Omit entirely when `stepCount == 0`.

**Streaming-flux consumption** (lines 728-783 — `submitChatTurn(...)`):
```java
activeStream = source
    .doOnSubscribe(sub -> { /* ...cancellation register... */ })
    .doOnNext(evt -> {
        if (evt instanceof StreamingEvent.Final f) { if (activeRunId == null) activeRunId = f.runId(); /* ... */ }
        // NEW: if (evt instanceof StreamingEvent.Activity a) { accessUi(() -> showStatus(messages.getMessage(statusKeyFor(a.kind())))); return; }
        // NEW: if (evt instanceof StreamingEvent.ToolCall tc) { liveTurnSteps.add(...); }
        // NEW: if (evt instanceof StreamingEvent.ToolResult tr) { liveTurnSteps.add(...); }   // dedupe by toolCallId
        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(evt, labels, citationState);
        // ...existing draft / action-proposal / markdown branches unchanged...
    })
    .doOnError(err -> { log.warn(...); accessUi(() -> { removeStatusRow(); showGenericErrorNotification(); finishStreamInternal(); }); })
    .doOnComplete(() -> accessUi(() -> {
        removeStatusRow();
        if (!liveTurnSteps.isEmpty() && activeRunId != null) appendTurnDetails(activeRunId, liveTurnSteps);
        liveTurnSteps.clear();   // OBS-04 — nothing accumulates unbounded
        finishStreamInternal();
    }))
    .subscribe();
```
The bounded `liveTurnSteps` list is a **per-fragment-instance** field (NOT in `AiChatSessionState`, which stays at `currentConversationId` + listeners — `AiChatSessionState.java`), cleared on terminal event.

**`accessUi(...)` wrapper** (lines 1278-1282) — every UI mutation from the reactive callback goes through `accessUi(...)` / `accessUiAuthenticated(...)`; do the same for `showStatus`, `removeStatusRow`, `appendTurnDetails`.

**Messages resolution** (line 838-843 + project memory `feedback_jmix_messages_over_spring`): inject `io.jmix.core.Messages`, use the **class-less** `messages.getMessage(key)` form, keep keys in the root `com.vn.agent` bundle (`messages_en.properties` / `messages_vi.properties`).

**On-expand history-turn read** (NEW; no existing analog in this fragment — closest is the `taskFilesDl` loader pattern, but use a programmatic typed `DataManager` load): on first expand of a `Details` not in `liveTurnSteps`, run a typed `dataManager.load(AiAuditEvent.class).query("select e from ai_AiAuditEvent e where e.runId = :rid and e.parent is not null order by e.startedAt asc").parameter("rid", runId).fetchPlan(fp -> fp.add("kind").add("startedAt").add("finishedAt").add("latencyMs").add("outcome").add("errorClass")).list()`. Typed loads infer the `agentstore` store from `@Store(name="agentstore")` on `AiAuditEvent` — only raw-JPQL `loadValue/loadValues` need `.store("agentstore")` (project memory `feedback_jmix_loadvalue_store`). Memoize the result on the `Details` (e.g. `ComponentUtil.setData(details, ...)` flag) so re-expand doesn't re-query (RESEARCH Pitfall 4). Open Q1: `DataManager` (constrained, if the chat user's role permits reading their own audit rows) vs `UnconstrainedDataManager` with `where e.userUsername = :currentUser` — never load by `runId` alone unconstrained (Security Domain V4).

---

### `audit/ToolCallbackAuditDecorator.java` (decorator, streaming-emit)

**Analog:** same file — `callInternal(...)` (lines 112-165) + `emitToolEvent(...)` (lines 228-238):
```java
final UUID toolCallId = UUID.randomUUID();
emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(toolCallId, toolName, cappedInput)));
// ...
private void emitToolEvent(UUID runId, Consumer<Sinks.Many<StreamingEvent>> emitter) {
    if (streamingSinkHolder == null) return;
    try { streamingSinkHolder.currentOrForRun(runId).ifPresent(emitter); }
    catch (RuntimeException ex) { log.debug("Streaming tool-event emission failed; ...", ex); }   // <-- best-effort; never break the turn
}
```
→ Add one line before/with the `ToolCall` emit:
```java
emitToolEvent(runId, sink -> sink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.TOOL)));
```

---

### `rag/advisor/AuditingDocumentRetriever.java` (decorator, streaming-emit)

**Analog:** `ToolCallbackAuditDecorator.emitToolEvent(...)` (cross-file — copy the best-effort pattern exactly).

**Pattern:** add a `private final StreamingSinkHolder streamingSinkHolder;` constructor field (passed by `RetrievalAugmentationAdvisorFactory`); at the *start* of `retrieve(Query)` (lines 84-92, where `runId` is already resolved from `RunContext.get()` then `query.context().get("audit.runId")`), emit:
```java
if (streamingSinkHolder != null) {
    final UUID rid = runId;
    try {
        streamingSinkHolder.currentOrForRun(rid)
            .ifPresent(sink -> sink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.RETRIEVAL)));
    } catch (RuntimeException ignored) { /* observability emit must never break retrieval */ }
}
```
Resolve `runId` exactly as the existing code does (lines 86-92) so the sink lookup matches the run id registered by `DefaultChatServiceImpl.stream(...)` (RESEARCH Pitfall 6). The existing audit-write-failure-never-rethrows convention (lines 127-129, 137-141) is the model for swallowing emit failures.

---

### `rag/advisor/RetrievalAugmentationAdvisorFactory.java` (config @Bean)

**Analog:** same file — `retrievalAugmentationAdvisor(...)` `@Bean` (lines 39-57):
```java
@Bean
@ConditionalOnMissingBean
public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                AiAgentRagProperties props,
                                                                AuditWriter auditWriter,
                                                                CurrentAuthentication currentAuthentication,
                                                                StreamingSinkHolder streamingSinkHolder) {   // <-- new param ($StreamingSinkHolder is a @Component)
    DocumentRetriever retriever = new AuditingDocumentRetriever(vectorStore,
            props.resolvedTopK(), props.resolvedSimilarityThreshold(), auditWriter, currentAuthentication, streamingSinkHolder);   // <-- pass through
    // ...rest unchanged...
}
```

---

### `DefaultChatServiceImpl.java` (stream assembly) — OPTIONAL per D-05

**Analog:** same file — the streaming assembly around lines 575-648; `toolSink` is created at line 494 and registered via `streamingSinkHolder.register(runId, toolSink)` (line 495), then merged with the content flux at line 635 (`toolSink.asFlux().mergeWith(content)`).

**Pattern (optional):** before the model `.stream()` (line 605) emit `toolSink.tryEmitNext(new StreamingEvent.Activity(StreamingEvent.ActivityKind.CHAT))`. D-05 only *requires* TOOL and RETRIEVAL be explicitly signalled; CHAT can be derived in the UI ("no Activity yet ⇒ neutral, first `Content` ⇒ thinking…"). Planner picks based on emit-ordering cleanliness.

---

### `META-INF/resources/frontend/styles/ai-agent-chat.css` (stylesheet)

**Analog:** same file — `.ai-agent-attachment-notice` (lines 49-70), `.ai-agent-intent-confirm` (lines 211-229), `.ai-agent-action-choice` (lines 233-252) use `--lumo-*` tokens, flex centering, `border-radius`, `box-shadow`. Loaded via `@CssImport("./styles/ai-agent-chat.css")` on `MessageBubbleComponent.java` (line 25) — no new theme machinery.

**New rules to add (follow the same Lumo-token style):**
- `.ai-agent-sidebar` — `position: fixed; right: 0; top: var(--lumo-size-xl, 3.5rem); bottom: 0; width: clamp(420px, 32vw, 32vw);` (use a navbar-height var if reliably present, else a fixed `top` — RESEARCH Pitfall 2 / Assumption A1) + a high `z-index` below modal layers.
- `.ai-agent-sidebar--open` — visible state (the default `.ai-agent-sidebar` could be `display: none` / off-canvas; `--open` brings it in).
- A push class on the AppLayout content area, e.g. `.ai-agent-content--pushed { padding-right: 32vw; }` (`AppLayout` navbar is `position: relative`, so `padding-right` on the content area is enough — Assumption A2; tweak `padding-top` only if the host uses a fixed navbar).
- `@media (max-width: 768px)` — collapse `.ai-agent-sidebar` to a full-screen overlay (`width: 100vw; left: 0;`) and drop the push padding.
- `.ai-agent-status` — the ephemeral status line: small, centered, secondary text color; a three-dot/pulse `@keyframes` animation; guarded by `@media (prefers-reduced-motion: reduce) { ... animation: none; }`.
- `.ai-agent-sidebar-toggle--active` — the "pressed" toggle state (Lumo has no built-in pressed variant): e.g. `background: var(--lumo-primary-color-10pct); color: var(--lumo-primary-text-color);`.
- Optional `.ai-agent-turn-detail` tweaks for the `Details` spacing.

---

### `com/vn/agent/messages_en.properties` + `messages_vi.properties` (i18n)

**Analog:** same files. Existing precedent keys (en file):
- `com.vn.agent.entity/AiChatSurface.FULL_ROUTE=Full route` (line 13) → add `com.vn.agent.entity/AiChatSurface.SIDEBAR=...` (and vi: line 15-16).
- `chatSurfaceMounter.headerButton.ariaLabel=Open AI chat` (line 227) → add `chatSurfaceMounter.sidebarToggle.ariaLabel.open` / `...ariaLabel.close` (distinct open/closed labels per D-02).
- `chatView.attachments.notice=...` (en line 548) → status keys, e.g. `chatView.status.chat=thinking…`, `chatView.status.tool=searching data…`, `chatView.status.retrieval=retrieving documents…`, `chatView.status.neutral=...` (neutral typing indicator).
- Turn-detail keys: `chatView.turnDetail.summary={0} steps · {1} ms` (MessageFormat with integer args), `chatView.turnDetail.step.tool=...`, `chatView.turnDetail.step.retrieval=...`, `chatView.turnDetail.errorIndicator=...`.
- `aiUiSettingsDetail.field.enabledSurfaces*` (en lines ~228) already auto-list the new enum via `setItemLabelGenerator(messages::getMessage)` — only the `AiChatSurface.SIDEBAR` label key is new.

**Add every new key to BOTH files** (there is no `messages.properties` no-locale default — confirmed; the project ships only `_en` + `_vi`).

---

### NEW `view/chat/ObservabilityLeakTest.java` (TEST-19)

**Analog:** `guard/ToolNameLeakScannerTest.java` + `guard/HostPrefixLeakScannerTest.java`.

**Tool-name regex construction** (from `ToolNameLeakScannerTest`):
```java
private static java.util.regex.Pattern toolNameRegex() {
    var props = new com.vn.agent.guard.AiAgentGuardProperties(null, null, null, null);   // defaults => enabled
    var p = new com.vn.agent.guard.ToolNamePatternProvider(java.util.List.of(), props);
    p.buildPattern();
    return java.util.regex.Pattern.compile(p.asPattern().orElseThrow().regex());
}
```
**Host-prefix regex:** `HostPrefixPatternProvider` needs a `Metadata` with host metaclasses — in a `@SpringBootTest` use the autowired `HostPrefixPatternProvider.asPattern()`; outside one, mirror `ToolNameLeakScannerTest.noOpHostProvider()` (mocked `Metadata`) or `HostPrefixLeakScannerTest.providerFor(Set<String> metaclassNames, boolean enabled)` which builds a `Metadata` mock returning the given metaclass names.

**Assertion shape:** for every `StreamingEvent.ActivityKind k`, render the actual `<span>` text and the `Details` step-row texts (incl. an `AiToolCallOutcome.ERROR` step) and `assertThat(toolNameRegex.matcher(renderedText).find()).isFalse()` + same for host-prefix. Negative control: deliberately route `find_records` (or a host-prefixed entity name) through the rendering path and assert the matcher trips. Use the `@UiTest` + `@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})` + `@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})` harness from `ChatSurfaceMounterTest`.

---

### NEW `view/chat/fragment/TurnDetailRenderer.java` + `TurnDetailRendererTest.java` (optional)

**Analog:** `StreamEventRenderer.java` (Vaadin-free, Spring-free, pure-static, `labels`-map pull-through) and `RenderStreamEventTest.java` (plain JUnit 5 + AssertJ). The renderer's contract: it accepts ONLY the closed `ActivityKind` / the audit `kind` String (`CHAT`/`TOOL`/`RETRIEVAL`) + timing + `outcome` — never `toolName`, `argsJson`, `resultSummary`, entity names — and resolves `msg://` keys; this makes TEST-19 a structural assertion (the input alphabet can't contain a tool/entity name).

## Shared Patterns

### Authentication / permission gating
**Source:** `ChatSurfaceMounter.isDialogViewPermitted()` (lines 296-300):
```java
UiShowViewContext accessContext = new UiShowViewContext(CHAT_DIALOG_VIEW_ID);
accessManager.applyRegisteredConstraints(accessContext);
return accessContext.isPermitted();
```
**Apply to:** the SIDEBAR mount + navbar toggle (mount/show only when permitted AND `enabledSurfaceSet.contains(SIDEBAR)`).

### Best-effort streaming emit (never break the turn)
**Source:** `ToolCallbackAuditDecorator.emitToolEvent(...)` (lines 228-238) — `streamingSinkHolder == null` guard + `currentOrForRun(runId).ifPresent(emitter)` + swallow `RuntimeException`.
**Apply to:** every `Activity(...)` emit site — `ToolCallbackAuditDecorator` (TOOL), `AuditingDocumentRetriever` (RETRIEVAL), `DefaultChatServiceImpl` (CHAT, optional).

### Reactive-callback UI mutation
**Source:** `ChatPanelFragment.accessUi(...)` / `accessUiAuthenticated(...)` (lines 1278-1282); the `.doOnNext/.doOnError/.doOnComplete` chain (lines 734-783).
**Apply to:** `showStatus`, `removeStatusRow`, `appendTurnDetails`, the on-expand `Details` content population.

### NOTICE-row sibling element in `messageListSlot`
**Source:** `ChatPanelFragment.appendNoticeRow(...)` (lines 958-967) — plain `Element("div")` + `getClassList().add(...)` + `setText(...)` (HTML-escaped) appended to `messageListSlot.getElement()` after `messageListSlot.add(messageList)`; `appendIntentConfirmRow` (lines 969-996) shows the `role="status"` / `aria-live="polite"` attribute pattern.
**Apply to:** the OBS-01 status `<span>` and the OBS-02 per-turn `Details`.

### i18n / Messages
**Source:** `feedback_jmix_messages_over_spring` + `ChatPanelFragment` line 838-843 comment — inject `io.jmix.core.Messages`, use the class-less `messages.getMessage(key)` form, keep keys in the root bundle; `setItemLabelGenerator(messages::getMessage)` for enum fields (`AiUiSettingsDetailView` lines 61-64).
**Apply to:** all new labels — added to BOTH `messages_en.properties` and `messages_vi.properties`.

### Agentstore typed reads
**Source:** project memory `feedback_jmix_loadvalue_store` + `AiAuditEvent` `@Store(name="agentstore")` (line 17); `AiUiSettingsService.loadCurrent()` uses `unconstrainedDataManager.load(AiUiSettings.class)...` for the singleton.
**Apply to:** the on-expand `AiAuditEvent`-by-`runId` read in `ChatPanelFragment` — typed `DataManager.load(AiAuditEvent.class)` infers the store; only raw-JPQL projections need `.store("agentstore")`. Filter by owner (`userUsername`) if using `UnconstrainedDataManager`.

### Additive sealed-interface variant
**Source:** `StreamingEvent.java` sealed interface (lines 23-63) + `StreamEventRenderer.renderStreamEventDetails` exhaustive `switch` (lines 117-148).
**Apply to:** `StreamingEvent.Activity` — one `permits` entry + one new arm in every exhaustive `switch` (compiler-enforced) + one new `instanceof` branch in `ChatPanelFragment.doOnNext`.

## No Analog Found

| File / Concern | Role | Data Flow | Reason / Guidance |
|------|------|-----------|-------------------|
| The `position: fixed` shell-docking CSS (`.ai-agent-sidebar`, `.ai-agent-content--pushed`, small-device overlay media query) | config (stylesheet) | n/a | No Jmix component does shell-level non-modal push docking around the router outlet (D-01's stated justification). Closest precedent is the *style* of existing `.ai-agent-*` rules in the same file (Lumo tokens, flex, `border-radius`) — copy that style, but the docking behaviour itself is new. RESEARCH §Code Examples + Pitfalls 1-2 + Assumptions A1-A2 cover the exact pitfalls. Use RESEARCH/SPEC patterns, verify against the host `AppLayout` during UI verification. |
| The status-line pulse `@keyframes` + `prefers-reduced-motion` guard | config (stylesheet) | n/a | New animation; no in-codebase precedent. Standard CSS — keep it minimal, guarded. |
| The on-expand `AiAuditEvent`-by-`runId` lazy read inside a fragment | service-ish (programmatic `DataManager` in a fragment) | CRUD-read | `AiAuditEventListView` uses a declarative `<collection>`/`<loader>` (XML) + `genericFilter`, not a programmatic load — not directly reusable. Closest is `AiUiSettingsService.loadCurrent()` (programmatic `load(...).id(...).optional()`). Build a typed `dataManager.load(AiAuditEvent.class).query("... where e.runId = :rid and e.parent is not null ...").fetchPlan(...).list()` with a narrow inline fetch plan; memoize on the `Details` component. |

## Metadata

**Analog search scope:** `ai-agent/ai-agent/src/main/java/com/vn/agent/{view/chat,view/chat/fragment,view/uisettings,view/audit,entity,orchestration,audit,rag/advisor,guard}/**`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/**`, `ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/**`, `ai-agent/ai-agent/src/test/java/com/vn/agent/{view/chat,view/chat/fragment,view/uisettings,entity,guard}/**`.
**Files scanned:** ~22 source files read directly (ChatSurfaceMounter, AiChatSurface, AiUiSettings, AiUiSettingsService, AiUiSettingsDetailView, StreamingEvent, StreamingSinkHolder, StreamEventRenderer, ChatPanelFragment (partial — targeted reads of the NOTICE-row + doOnNext + finishStream + appendNoticeRow regions), AiAuditEvent, AiAuditEventListView, ToolNamePatternProvider, HostPrefixPatternProvider, ToolCallbackAuditDecorator, AuditingDocumentRetriever, RetrievalAugmentationAdvisorFactory, DefaultChatServiceImpl (partial — stream assembly region), AiChatSessionState, AiChatUIState, ai-agent-chat.css, MessageBubbleComponent (@CssImport line), messages_en/vi.properties (grep), plus test-file headers for ChatSurfaceMounterTest / ToolNameLeakScannerTest / HostPrefixLeakScannerTest).
**Pattern extraction date:** 2026-05-11
