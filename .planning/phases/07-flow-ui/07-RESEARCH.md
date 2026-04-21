# Phase 7: Flow UI — Research

**Researched:** 2026-04-21
**Domain:** Jmix Flow UI (Vaadin Flow under Jmix 2.8) + Spring AI 1.1.4 streaming + reactive chat wiring
**Confidence:** HIGH on locked decisions; MEDIUM on Spring AI streaming+tool-call interaction (known upstream issues); HIGH on audit export (Open Q#1 resolved 2026-04-21 — Excel + JSON via `gridexport` add-on, CSV dropped)

## Summary

Phase 7 delivers the add-on's entire plug-and-play Flow UI atop prior phases' services. The technical core is (a) wiring `ChatService.stream(...)` (a new Flux-returning method) into a Vaadin chat view with `@Push` + `UI.access()`, (b) surfacing ingestion status via event-driven push (no polling), (c) making the chat panel a reusable Jmix Fragment so v2 can embed it, and (d) 100% en+vi locale parity with a build-failing scan.

Three concrete upstream constraints shape every design:

1. **Spring AI 1.1.4 has a documented impedance mismatch between `.stream()` and tool calling.** Tool execution is imperative/blocking. When a prompt triggers tool calls, the aggregated assistant message over the Flux loses `toolCalls` metadata and Micrometer spans don't stitch cleanly (GH issues #5167, #3366, #4315). The reference impl (`ChatImpl.java`) works around this by bridging tool-lifecycle events into the reactive stream via `Sinks.Many` — which is exactly the pattern Phase 7 must adopt. Do NOT assume `.stream().chatResponse()` carries tool metadata.
2. **Jmix `gridexport` add-on ships Excel + JSON only** (`grdexp_excelExport`, `grdexp_jsonExport`). It does **not** ship a CSV action. **RESOLVED 2026-04-21 (Option A):** UI-06, ROADMAP, and CONTEXT D-20 updated to use Excel + JSON natively; CSV is dropped. Planner MUST declare both actions on the audit `dataGrid` and bind two buttons; no hand-rolled `StreamResource` branch is required.
3. **Jmix Fragment API is the right reuse substrate.** `@FragmentDescriptor` + `Fragment<T>` extends a root layout; `content` element holds layout; public methods on the Fragment class are invokable by host view. D-29's reusable chat panel is straightforward.

**Primary recommendation:** Mirror `jmix-ai-backend/ChatImpl.java`'s `Sinks.Many` + `Flux.defer(...).subscribeOn(streamingScheduler)` pattern for streaming. Use Flexmark + OWASP Java HTML Sanitizer server-side; wrap sanitized output in Vaadin `Html`. Make `KnowledgeBaseView` subscribe to an `ApplicationEventPublisher`-emitted `DocumentStatusChangedEvent` and fan out via `UI.access()` per attached UI. Ship the chat panel as a Jmix Fragment (`ChatPanelFragment`). Escalate the gridexport-CSV gap to the user / accept Excel.

## User Constraints (from CONTEXT.md)

### Locked Decisions

All 31 D-01..D-31 decisions from `07-CONTEXT.md` are locked inputs. Research verifies *how* to implement them; it does not revisit *whether*. Highlights (non-exhaustive — the planner MUST read the full CONTEXT):

- **D-01 / D-04 / D-05:** `ChatService.stream(...)` returns `Flux<ChatResponse>` (or a Flux of a streaming-event wrapper — see "Open Questions"); UI subscribes + appends via `UI.access()`; degrades gracefully on non-streaming providers. Research reference-impl `ChatImpl.java` before finalizing.
- **D-02:** Vaadin `@Push` enabled globally by the add-on (plug-and-play), transport `WEBSOCKET_XHR`. Researcher picks cleanest AppShell-friendly mechanism.
- **D-03:** `Stop` reuses existing `CancellationRegistry` (Phase 5); registration keyed on `runId`; audit outcome `CANCELLED`.
- **D-07:** Markdown via Flexmark → sanitized HTML → Vaadin `Html`. Streaming re-parses buffered markdown each `UI.access` tick.
- **D-08 / D-25:** Tool-call cards = collapsed badge → expand on click; reusable between ChatView and ConversationDetailView.
- **D-09:** Citations = inline `[n]` superscript → dialog on click; metadata already on `ChatResponseDto` from Phase 5.
- **D-10 / D-11 / D-12 / D-13:** Parameters detail = Form (source of truth) + read-only YAML Preview regenerated live via `AiParametersBodyYamlMapper`; enumerate every `AiParametersBody` field; per-field inline validation blocks Save.
- **D-14:** `Set active` = row-action + detail button; immediate commit; audit records switch.
- **D-15:** Upload = Jmix Flow UI `<upload>` component (not raw Vaadin `Upload`).
- **D-16:** KB status refresh = Vaadin Push driven by ingestion status-change events (researcher picks ApplicationEventPublisher vs. Broadcaster). **No polling.**
- **D-17 / D-22:** Badges via Vaadin Lumo theme variants (`success`, `error`, `contrast`, `tertiary`). All labels via `msg://`.
- **D-19:** Audit filter = typed bar (user/tool/outcome/date) + Jmix `GenericFilter`.
- **D-20:** Audit export via Jmix gridexport add-on — `grdexp_excelExport` + `grdexp_jsonExport` actions on the audit `dataGrid` (dependency `io.jmix.gridexport:jmix-gridexport-flowui-starter`). CSV explicitly dropped (not shipped by the add-on). Open Q#1 resolved 2026-04-21.
- **D-21:** Row-click opens modal Dialog with full audit detail (no separate detail view).
- **D-23 / D-24 / D-26:** ConversationDetailView = single scrollable transcript reusing ChatView components (read-only); role-aware filter branch; "Continue in chat" button opens ChatView with selected `conversationId`.
- **D-27:** Views under `com.vn.agent.view.{chat, conversation, parameters, knowledge, audit}`.
- **D-28:** Admin gating = ResourceRole `@ViewPolicy` + `@MenuPolicy` + view-class-level direct-URL protection.
- **D-29:** Chat panel = reusable Jmix Fragment; v1 routes full `ChatView`; floating launcher deferred to v2 but Fragment substrate lands now.
- **D-30:** 100% en+vi parity, zero hardcoded strings; build-failing coverage scan.
- **D-31:** `jmix-app` host unchanged.

### Claude's Discretion

- Exact Jmix Fragment boundaries (chat panel shape): planner decides based on v2 embedding requirements.
- Concrete Flexmark config (extensions, sanitizer) and markdown→HTML security posture — apply standard safe defaults.
- Exact CSS / Lumo variant choices for bubble styling.
- Concrete Badge colour mapping terminology if Lumo variants differ from "success/error/contrast/tertiary".
- Whether `AppShellConfigurator` contribution (D-02) is a ship-with-the-add-on class or a documented snippet — pick whichever avoids breaking host `AppShellConfigurator` overrides.
- Package name for the reusable chat-panel Fragment (likely `com.vn.agent.view.chat.fragment`).
- Exact wiring of ingestion status-change events (ApplicationEventPublisher vs. Broadcaster) — pick whichever integrates cleanest with existing `IngesterManager`/`AsyncIngestionWorker`.

### Deferred Ideas (OUT OF SCOPE)

- Floating/embedded chat launcher (v2, D-29 plans Fragment substrate now).
- Admin-configurable chat-surface toggle (v2).
- "Continue in chat" fallback: read-only replay + button removed, if routing/memory complexity surfaces.
- Mutation tools UI (post-v1).
- Scheduled/async export for large audit volumes (current path is synchronous Excel + JSON via gridexport).
- Cross-conversation search.
- VirtualList for long conversation histories.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UI-01 | ChatView end-user chat with tool-call cards, streaming, citations | Spring AI `.stream().chatResponse()` + `Sinks.Many` bridge pattern (ref impl ChatImpl.java); Flexmark+OWASP sanitizer; reusable ToolCallCard component |
| UI-02 | ChatView `New chat` + `Stop` controls | `Stop` wires `activeStreamDisposable.dispose()` + `CancellationRegistry.cancel(runId)` + audit outcome CANCELLED |
| UI-03 | ConversationListView + ConversationDetailView (ownership filter, replay) | `ConversationGateway.loadOrCreate` + role-branch loader; replay reuses bubble/tool-card/citation components read-only |
| UI-04 | ParametersListView + ParametersDetailView (Form + YAML preview + Set active) | Jmix Form data-binding to `AiParameters`; serialize via `AiParametersBodyYamlMapper.writeAsYaml` on every valueChange; read-only `CodeEditor` or `TextArea` for preview |
| UI-05 | KnowledgeBaseView (upload, status, delete, reingest) | Jmix `<upload>` (multi-file + drag-drop + progress OOB); `ApplicationEventPublisher`-emitted status events + `UI.access()` push |
| UI-06 | ToolCallAuditListView (filter, Excel + JSON export) | Typed filter bar + `<genericFilter>`; `grdexp_excelExport` + `grdexp_jsonExport` actions on dataGrid via `io.jmix.gridexport:jmix-gridexport-flowui-starter` (Open Q#1 resolved — Option A) |
| UI-08 | `aiAgent.*` menu namespace, en+vi locale parity | extend existing `menu.xml`; both message bundles |
| UI-09 | Zero hardcoded UI text | Build-failing scan: Gradle task parses view XML + Java for string literals outside `msg://` pattern |
| UI-10 | Admin views gated to `AiAgentAdminRole` | `@ViewPolicy` in `AiAgentAdminRole` interface + view-class-level direct-URL protection |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Streaming assistant message delivery | Server (Spring AI + Reactor) | Browser (Push transport) | Spring AI produces Flux; Vaadin Push ships chunks to browser — browser just renders |
| Tool-call cards / citations rendering | Server-rendered HTML (Vaadin `Html` + Flexmark) | Browser DOM | Markdown parse + HTML sanitize runs server-side so the browser never sees untrusted model text |
| Stop / cancel | Server (CancellationRegistry) | Browser click | Button click → UI event → server disposes Flux + flips CancellationRegistry |
| Upload file receipt | Server (Jmix `<upload>` + `MultiFileTemporaryStorageBuffer`) | Browser drag-drop | Files stream to server temp storage; server schedules ingestion |
| Ingestion status refresh | Server (ApplicationEventPublisher → UI.access()) | Browser (Push channel) | Event emitted in `IngestionStatusWriter` commit; every attached UI listening pushes a row update |
| Parameters CRUD | Server (DataManager + ParametersService) | Browser Form binding | Jmix standard — Form binds to entity, validators run server-side |
| Audit export | Server (gridexport or StreamResource) | Browser download | Export action generates bytes server-side, Vaadin serves as download |
| Role-gated view visibility | Server (AccessManager + MenuPolicy) | Browser (menu render) | Policy-driven; menu XML items hidden when policy denies; view class-level annotation blocks direct URL |

## Standard Stack

### Core (already on classpath — do not re-add)

| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| Spring AI Client Chat | 1.1.4 | `ChatClient.prompt().stream().chatResponse()` → `Flux<ChatResponse>` | [VERIFIED: ai-agent.gradle line `spring-ai-client-chat:1.1.4`] |
| Jmix FlowUI Starter | 2.8 | Views, fragments, Form binding, upload, genericFilter | Already present |
| Reactor Core | transitive via Spring AI | `Flux`, `Sinks.Many`, `Disposable`, `Scheduler` | No explicit dependency needed |
| Vaadin Flow | transitive via Jmix 2.8 | `@Push`, `AppShellConfigurator`, `UI.access()`, `Html`, `Scroller`, `Badge` | No explicit dependency needed |

### New Dependencies for Phase 7

| Library | Coordinate | Purpose | Why Standard |
|---------|------------|---------|--------------|
| Flexmark | `com.vladsch.flexmark:flexmark:0.64.8` (+ `flexmark-ext-gfm-tables`, `flexmark-ext-autolink`) | Server-side markdown → HTML | [CITED: github.com/vsch/flexmark-java] Fast CommonMark 0.28 parser; explicitly **does not sanitize** — caller must sanitize |
| OWASP Java HTML Sanitizer | `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1` | Sanitize Flexmark HTML output | [CITED: OWASP.org] "4x faster than AntiSamy in DOM mode"; battle-tested allowlist-based |
| Jmix gridexport | `io.jmix.gridexport:jmix-gridexport-flowui-starter` (BOM-managed) | Grid export actions | [VERIFIED: docs.jmix.io/jmix/grid-export] Ships Excel + JSON only — see Open Q#1 |

Version verification recommended at plan time via Maven Central:
```bash
# Flexmark — verify latest stable
curl -s "https://search.maven.org/solrsearch/select?q=g:com.vladsch.flexmark+AND+a:flexmark&wt=json&rows=1"
# OWASP sanitizer — verify latest stable (YYYYMMDD.N versioning)
curl -s "https://search.maven.org/solrsearch/select?q=g:com.googlecode.owasp-java-html-sanitizer+AND+a:owasp-java-html-sanitizer&wt=json&rows=1"
```

### Alternatives Considered

| Instead of | Could Use | Why Rejected |
|------------|-----------|--------------|
| Flexmark | CommonMark-Java | CommonMark-Java is ~35% faster but Flexmark has richer extensions (tables, autolinks, footnotes) for chat rendering. Either works; Flexmark picked per D-07 |
| OWASP Sanitizer | jsoup `Whitelist` | jsoup is viable but OWASP's policy DSL is explicitly designed for this use case and much faster |
| Hand-rolled CSV via `StreamResource` | Jmix gridexport `grdexp_excelExport` + `grdexp_jsonExport` | Use the add-on's native formats — no hand-rolled export code, consistent UX, honors filter/sort automatically (Open Q#1 resolved Option A) |
| Vaadin `MessageList` (ref impl uses this) | Custom bubble components | `MessageList` has markdown support but tool-call card embedding requires custom components — mixed model gets awkward. Plain VerticalLayout + bubble components (D-06) is cleaner for D-08/D-09 |

**Installation (Gradle):**
```gradle
// Plan 07 additions to ai-agent/ai-agent.gradle
// Markdown rendering + XSS defence (D-07)
implementation 'com.vladsch.flexmark:flexmark:0.64.8'
implementation 'com.vladsch.flexmark:flexmark-ext-gfm-tables:0.64.8'
implementation 'com.vladsch.flexmark:flexmark-ext-autolink:0.64.8'
implementation 'com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20220608.1'

// Audit grid export (D-20)
implementation 'io.jmix.gridexport:jmix-gridexport-flowui-starter'
```

## Architecture Patterns

### System Data Flow Diagram

```
                     Browser (Vaadin client)
    ┌──────────────────────────────────────────────┐
    │  ChatView   ConvListView   ParamsView  KBView │
    │      ↕ Push (WEBSOCKET_XHR) ↕                  │
    └───────────────┬──────────────────────────────┘
                    │
         UI.access(..) lock
                    │
    ┌───────────────┴──────────────────────────────┐
    │  View controllers (StandardView / ListView)   │
    │    - subscribe to events                      │
    │    - dispose Flux on detach                   │
    └──┬─────────────────────┬───────────────────┬─┘
       │                     │                   │
       │ stream()            │ ingestion events  │ CRUD + actions
       ▼                     ▼                   ▼
  ┌─────────┐          ┌────────────┐      ┌──────────────┐
  │ChatSvc  │          │Application │      │Parameters/   │
  │.stream()│──Flux──→ │EventPub-   │      │Doc/Audit     │
  │         │          │lisher      │      │services      │
  └────┬────┘          └─────┬──────┘      └──────┬───────┘
       │ ChatClient           │ StatusEvt          │ DataManager
       │ .prompt()            │                    │
       │ .stream()            ▼                    ▼
       │           ┌──────────────────┐     ┌──────────────┐
       │           │IngestionStatus-  │     │  Database    │
       │           │Writer REQUIRES_NEW     │ (Postgres +  │
       │           │  → emit event on │     │  pgvector)   │
       │           │  afterCommit     │     └──────────────┘
       └─────┐     └──────────────────┘
             ▼
       ┌─────────────────────────┐
       │ Sinks.Many bridge       │  (D-05 ref pattern)
       │ - tool events push      │
       │ - content tokens merge  │
       │ - final metadata emit   │
       └─────────────────────────┘
                │
                ▼ Flux<StreamingEvent>
    back up through UI.access() → browser
```

### Recommended Package Structure

```
com.vn.agent.view/
├── chat/
│   ├── ChatView.java + chat-view.xml               # full route (UI-01, UI-02)
│   ├── fragment/
│   │   ├── ChatPanelFragment.java + chat-panel-fragment.xml   # D-29 reusable
│   │   ├── MessageBubbleComponent.java             # D-06
│   │   ├── ToolCallCardComponent.java              # D-08, reused in replay
│   │   └── CitationDialog.java                     # D-09
│   └── MarkdownRenderer.java                       # D-07: Flexmark + OWASP
├── conversation/
│   ├── ConversationListView.java + xml             # UI-03, role-aware filter D-24
│   └── ConversationDetailView.java + xml           # D-23 replay + D-26 continue
├── parameters/
│   ├── ParametersListView.java + xml               # UI-04
│   └── ParametersDetailView.java + xml             # D-10/D-11/D-12: Form + YAML preview
├── knowledge/
│   ├── KnowledgeBaseView.java + xml                # UI-05
│   └── UploadCompleteEventHandler.java             # D-15 succeeded listener
└── audit/
    ├── ToolCallAuditListView.java + xml            # UI-06
    └── ToolCallAuditDetailDialog.java + xml        # D-21 row-click modal

com.vn.agent.push/
├── AiAgentAppShell.java                            # D-02 @Push contribution
├── DocumentStatusChangedEvent.java                 # D-16 app event
└── DocumentStatusEventPublisher.java               # emitted by IngestionStatusWriter
```

### Pattern 1: Streaming ChatView with Push (D-01, D-02, D-05)

**What:** `ChatService.stream(userId, convId, message)` returns `Flux<StreamingEvent>` (wrapper over `ChatResponse` so tool-call events can be carried alongside token chunks — mirrors ref impl `EventStreamValueHolder`). The view subscribes and forwards to UI via `UI.access()`.

**When to use:** ChatView only. ConversationDetailView replay uses blocking DataManager queries, not streams.

**Key constraint (from Spring AI 1.1.4 issues #5167, #3366, #4315):** `.stream().chatResponse()` does NOT cleanly surface tool calls. The ref impl bridges tool events via `Sinks.Many<EventStreamValueHolder>` populated by a `ToolEventListener` fired during the blocking tool phase, then `mergeWith`'d with the content Flux. Phase 7 MUST adopt this pattern or fall back to blocking `ask()` on prompts that trigger tools.

**Example (generalized from `ChatImpl.java` — source: ref impl, lines 216-304):**
```java
// ChatServiceImpl (Phase 7 addition to DefaultChatServiceImpl)
public Flux<StreamingEvent> stream(String userId, UUID conversationId, String message, Overrides overrides) {
    return Flux.defer(() -> {
        // Guards run synchronously before LLM call (reuse Phase 6 preamble)
        UUID runId = UUID.randomUUID();
        RunContext.set(runId);

        Sinks.Many<StreamingEvent> toolSink = Sinks.many().unicast().onBackpressureBuffer();
        ToolEventListener listener = createToolListener(toolSink, runId);

        ChatClient.ChatClientRequestSpec spec = prepareSpec(userId, conversationId, message, overrides, listener);

        // Content Flux extracted from Spring AI stream
        Flux<StreamingEvent> content = spec.stream().chatResponse()
            .<StreamingEvent>concatMap(chunk -> {
                String text = extractContent(chunk);
                return (text != null && !text.isEmpty())
                    ? Flux.just(new StreamingEvent.Content(text))
                    : Flux.empty();
            })
            .doOnComplete(toolSink::tryEmitComplete);

        // Register cancellation BEFORE subscribing so Stop wiring works
        return toolSink.asFlux().mergeWith(content);
    })
    .subscribeOn(streamingScheduler)  // off Tomcat servlet threads
    .doFinally(signal -> RunContext.clear());
}

// ChatView (Phase 7)
private void onSubmit(MessageInput.SubmitEvent event) {
    UI ui = event.getSource().getUI().orElseThrow();
    disposeActiveStream();

    MessageBubble botBubble = addBotBubble();
    activeStreamDisposable = chatService.stream(userId, conversationId, event.getValue(), overrides)
        .subscribe(
            evt -> ui.access(() -> {
                switch (evt) {
                    case StreamingEvent.Content c -> botBubble.appendMarkdown(c.text());
                    case StreamingEvent.ToolCall tc -> botBubble.addToolCallCard(tc);
                    case StreamingEvent.Citation cit -> botBubble.addCitationMarker(cit);
                    case StreamingEvent.Final f -> botBubble.finalize(f);
                }
                scrollToBottomIfAnchored();
            }),
            err -> ui.access(() -> notifications.show(messageBundle.getMessage("chat.streamError"))),
            () -> ui.access(() -> enableInput())
        );
}

@Subscribe
public void onDetach(DetachEvent event) {
    disposeActiveStream();  // CRITICAL: prevents Flux leaks across navigation
}
```

### Pattern 2: Vaadin Push via AppShellConfigurator (D-02)

**What:** One `AppShellConfigurator` class annotated with `@Push(transport = Transport.WEBSOCKET_XHR)`, contributed by the add-on.

**Constraint [CITED: vaadin.com/docs/latest/flow/advanced/server-push]:** Vaadin allows only one `AppShellConfigurator`. If the host app defines its own (common), the add-on's class would collide.

**Recommended resolution:** Ship the `AppShellConfigurator` class **in the starter module** gated by `@ConditionalOnMissingBean(AppShellConfigurator.class)` semantics at the bean level — Vaadin discovery is classpath-driven, not Spring-bean driven, so true conditional loading is not possible. Instead:
- Ship it in `ai-agent-starter` with a documented opt-out property `jmix.ai-agent.flowui.push-autoconfigure=false`
- Document the snippet hosts should copy if they already have their own `AppShellConfigurator`
- Provide a `@MetaAnnotation`-style interface `EnableAiAgentPush` hosts can add to their existing `AppShellConfigurator`

**Example:**
```java
// ai-agent-starter: com.vn.agent.push.AiAgentAppShell
@Push(transport = Transport.WEBSOCKET_XHR)
@ConditionalOnProperty(
    name = "jmix.ai-agent.flowui.push-autoconfigure",
    havingValue = "true",
    matchIfMissing = true)
public class AiAgentAppShell implements AppShellConfigurator {
    // marker only — @Push carries the config
}
```
Planner must verify whether Vaadin honors Spring-conditional `AppShellConfigurator` (likely no — Vaadin scans classpath for the type, not the bean). If not, ship as a documented snippet only and drop the class from auto-discovery.

### Pattern 3: Ingestion Status Push via ApplicationEventPublisher (D-16)

**What:** `IngestionStatusWriter` emits a `DocumentStatusChangedEvent` after commit. A Spring `@EventListener` fans out to every attached Vaadin `UI` that has registered interest (the `KnowledgeBaseView` on attach).

**When to use:** KB view only. Chat streaming uses its own Flux directly, not app events.

**Why ApplicationEventPublisher over Vaadin Broadcaster:** [VERIFIED by reading `IngestionStatusWriter.java`] every status-change method is `@Transactional(REQUIRES_NEW)`; Spring's `ApplicationEventPublisher` composes naturally with `TransactionSynchronization.afterCommit` (pattern already used by `AuditWriter.writeToolCall` in Phase 4) so listeners only fire on committed rows. Vaadin `Broadcaster` has no transaction awareness and would fire mid-commit.

**Example:**
```java
// IngestionStatusWriter — extended in Plan 07-XX
private final ApplicationEventPublisher publisher;

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markReady(UUID id, int chunkCount) {
    // ... existing body ...
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
            publisher.publishEvent(new DocumentStatusChangedEvent(id, AiKnowledgeDocumentStatus.READY));
        }
    });
}

// KnowledgeBaseView
@Autowired ApplicationEventPublisher publisher;  // not needed — use listener
private Registration uiAttachReg;
private final Set<UI> attachedUis = ...;  // or use a CopyOnWriteArraySet on the Spring bean

@EventListener  // on a @Component broker, not on the view itself
public void onStatusChanged(DocumentStatusChangedEvent event) {
    for (UI ui : attachedUis) {
        ui.access(() -> {
            // Refresh just the affected row via dataGrid.getDataProvider().refreshItem(...)
        });
    }
}

@Subscribe public void onAttach(AttachEvent e) { broker.register(e.getUI()); }
@Subscribe public void onDetach(DetachEvent e) { broker.unregister(e.getUI()); }
```

### Pattern 4: Jmix `<upload>` Component (D-15)

**What:** Jmix's `<upload>` wraps Vaadin `Upload` with Jmix-native XML + events + multi-file semantics.

**XML [CITED: docs.jmix.io/jmix/flow-ui/vc/components/upload]:**
```xml
<upload id="kbUpload"
        maxFiles="10"
        autoUpload="true"
        receiverType="MULTI_FILE_TEMPORARY_STORAGE_BUFFER"
        acceptedFileTypes=".pdf,.md,.txt,.html"/>
```

**Controller:**
```java
@Subscribe("kbUpload")
public void onUploadSucceeded(SucceededEvent event) {
    String fileName = event.getFileName();
    // event provides InputStream via the buffer on AllFinishedEvent
    // Call KnowledgeDocumentUploadService.upload(...) — Phase 5 service
}

@Subscribe("kbUpload")
public void onUploadAllFinished(AllFinishedEvent event) {
    // Refresh grid to show PENDING rows
    documentsGrid.getDataProvider().refreshAll();
}
```

**Critical: the receiver choice** — `MULTI_FILE_TEMPORARY_STORAGE_BUFFER` spools to disk (good for large files, avoids OOM); `MULTI_FILE_MEMORY_BUFFER` is memory-only (fast but dangerous at scale). Phase 7 ships the temp-storage variant.

### Pattern 5: Jmix Fragment for reusable chat panel (D-29)

**What:** `@FragmentDescriptor`-annotated class extending `Fragment<VerticalLayout>`. Fragment XML has the shape `<fragment xmlns=...><content>...</content></fragment>`. Public methods on the fragment class are directly invokable by the including view.

**Example [CITED: docs.jmix.io/jmix/flow-ui/fragments/fragments.html]:**
```java
// chat-panel-fragment.xml
<fragment xmlns="http://jmix.io/schema/flowui/fragment">
    <content>
        <vbox id="messagesContainer" width="100%" expand="messagesScroller">
            <scroller id="messagesScroller" width="100%" scrollDirection="VERTICAL"/>
        </vbox>
    </content>
</fragment>

// Java
@FragmentDescriptor("chat-panel-fragment.xml")
public class ChatPanelFragment extends Fragment<VerticalLayout> {
    @ViewComponent private Scroller messagesScroller;
    @ViewComponent private VerticalLayout messagesContainer;

    // Public API invokable by ChatView
    public void appendMessage(MessageRole role, String markdown) { ... }
    public void addToolCallCard(ToolCallInfo info) { ... }
    public void clear() { ... }
    public void setInputEnabled(boolean enabled) { ... }
}

// In ChatView's XML
<fragment id="chatPanel" class="com.vn.agent.view.chat.fragment.ChatPanelFragment"/>

// In ChatView's Java
@ViewComponent("chatPanel") private ChatPanelFragment chatPanel;
chatPanel.appendMessage(MessageRole.ASSISTANT, "...");
```

### Pattern 6: Scroll-to-bottom with user-anchor detection (ref impl lines 174-179)

The reference impl's JS snippet is the canonical pattern — copy verbatim:
```java
private void scrollToBottomIfAnchored() {
    messagesScroller.getElement().executeJs(
        "setTimeout(() => { " +
        "if (this.scrollHeight - this.scrollTop - this.clientHeight < 50) " +
        "this.scrollTop = this.scrollHeight; }, 50)");
}
```
**Why the `setTimeout`:** markdown re-render is async; without the deferral, `scrollHeight` lags the new content and the threshold check misfires (see ref impl comments).

### Pattern 7: Markdown render pipeline (Flexmark + OWASP) — D-07

```java
@Component
public class MarkdownRenderer {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public MarkdownRenderer() {
        MutableDataSet opts = new MutableDataSet();
        opts.set(Parser.EXTENSIONS, List.of(
            TablesExtension.create(), AutolinkExtension.create()));
        parser = Parser.builder(opts).build();
        renderer = HtmlRenderer.builder(opts).build();
        sanitizer = Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS)
            .and(Sanitizers.TABLES);
    }

    public String toSafeHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = parser.parse(markdown);
        String dirtyHtml = renderer.render(doc);
        return sanitizer.sanitize(dirtyHtml);
    }
}

// Usage in bubble component
Html html = new Html("<div>" + renderer.toSafeHtml(bufferedMarkdown) + "</div>");
```

**Thread-safety [CITED: flexmark-java wiki]:** `Parser` and `HtmlRenderer` instances are **thread-safe after construction** and designed to be reused (they are essentially immutable configurations). `OWASP PolicyFactory` is explicitly thread-safe. Bean singletons are correct. Streaming re-parse on each `UI.access()` tick is cheap (Flexmark is ~10× faster than IntelliJ markdown and ~30× faster than pegdown).

### Anti-Patterns to Avoid

- **Never call `UI.access()` from inside a reactive operator on a bg thread without checking `UI` is still attached.** Detached UI throws `UIDetachedException` — wrap in try/catch or check `ui.isAttached()` first, or use the single-subscribe Disposable pattern so the Flux terminates on view detach.
- **Never use Vaadin `Broadcaster` for transaction-scoped events** (D-16) — it fires before commit. Use Spring `ApplicationEventPublisher` + `TransactionSynchronization.afterCommit`.
- **Never hand-roll markdown→HTML without sanitization.** Assistant output is untrusted — raw Flexmark output embedded in Vaadin `Html` is an XSS vector.
- **Never poll the database for KB status** (D-16 explicitly forbids) — use the event-driven path.
- **Never wire `@Push` per-view** (Vaadin doesn't allow it); it's app-level only.
- **Never mix `MessageList` (ref impl) with bubble components** — D-06 picks plain VerticalLayout + custom bubble; `MessageList`'s built-in markdown conflicts with our tool-card embedding.
- **Never assume `.stream().chatResponse()` tool-calls surface cleanly** (GH #5167, #3366) — use the `Sinks.Many` bridge pattern or fall back to blocking on tool-calling prompts.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Markdown → HTML | Custom regex or String.replace pipeline | Flexmark + OWASP sanitizer | XSS safety, edge-case coverage (tables, code fences, links), proven at scale |
| File upload multi-file + drag-drop + progress | Raw Vaadin `Upload` wired manually | Jmix `<upload>` component | Ships the exact semantics needed (D-15 user-directed) |
| Audit grid export | Hand-roll OPC / CSV writer / `StreamResource` | Jmix gridexport `grdexp_excelExport` + `grdexp_jsonExport` | Native actions honor filter + sort, i18n-aware, selection-aware via `selectionMode="MULTI"` — no hand-rolled export code. CSV is not shipped (intentional — Open Q#1 Option A) |
| Push config | Per-view @Push or programmatic | `AppShellConfigurator` @Push | Vaadin only honors the class-level annotation |
| Event-driven UI refresh | setInterval / polling | `ApplicationEventPublisher` + `UI.access()` | D-16 user-directed; polling wastes cycles + loses responsiveness |
| Reusable component across views | Copy-paste or base class | Jmix Fragment | D-29 user-directed; built for this |
| Conversation ownership filter | Raw JPQL | `ConversationGateway.loadOrCreate` + Jmix row-level | Phase 4 already enforces ownership; views must not duplicate |
| YAML preview regeneration | Manual YAML string building | `AiParametersBodyYamlMapper.writeAsYaml` | Phase 6 ships the authoritative serializer |
| Role-based view access | Manual `if (user.hasRole(...))` in view | `@ViewPolicy` / `@MenuPolicy` on `AiAgentAdminRole` | Jmix native; centralizes policy; extends to direct-URL protection |
| Tool-call card layout | Custom JS | Vaadin `Details` component (`<details>`) | Server-side collapse/expand; i18n-friendly; a11y handled |

**Key insight:** Phase 7's surface is large but its *novel* code should be small — streaming bridge, markdown pipeline, event broker, reusable chat Fragment. Everything else is assembled from Jmix/Vaadin/Spring AI primitives.

## Runtime State Inventory

Not applicable — Phase 7 is additive (new views, new components, new push config). No rename/refactor/migration.

## Common Pitfalls

### Pitfall 1: Flux subscription leaks across view navigation
**What goes wrong:** User submits a chat, navigates away before stream completes. The Flux continues to process tokens, the UI is detached, `UI.access()` throws `UIDetachedException`, logs fill up; worse, the underlying LLM call keeps running and consuming tokens.
**Root cause:** `subscribe()` returns a `Disposable` that isn't disposed on detach.
**Avoid:** Store the `Disposable` as a field (`activeStreamDisposable`), call `dispose()` in `@Subscribe DetachEvent` AND in `New chat` / `Stop` / new-submit flows. Mirror ref impl lines 186-194.
**Warning signs:** Tomcat logs showing `UIDetachedException` from reactor threads.

### Pitfall 2: Push not working through corporate reverse proxies
**What goes wrong:** User's company has an nginx/F5 with WebSocket timeout = 30s; connection drops mid-stream; push silently stops working.
**Root cause:** Default WS timeout too short; `WEBSOCKET_XHR` still falls back to WS for server→client.
**Avoid:** Document that hosts should configure proxy timeout ≥ 5min for `/VAADIN/push` endpoint. Test with `LONG_POLLING` transport fallback for ops-constrained environments. Add an operator doc (Phase 8 territory but cross-referenced here).
**Warning signs:** Stream stops mid-response; F12 network tab shows WebSocket closed with code 1006.

### Pitfall 3: Multi-file upload memory pressure
**What goes wrong:** Admin drags in 50 PDFs; `MULTI_FILE_MEMORY_BUFFER` holds all bytes; JVM OOMs.
**Root cause:** Wrong receiver type.
**Avoid:** Use `MULTI_FILE_TEMPORARY_STORAGE_BUFFER` by default. Expose per-upload size cap via `maxFileSize` attribute. Default to reasonable limits (e.g., 50MB/file).

### Pitfall 4: Form → YAML regeneration feedback loop
**What goes wrong:** User edits Form field → YAML preview regenerates → if the YAML preview is bindable, it might re-trigger Form valueChange → infinite loop.
**Root cause:** D-10 says "Form source of truth" but if wiring is sloppy, the YAML field emits a valueChange event that the Form listens to.
**Avoid:** YAML preview MUST be `readOnly="true"` and MUST NOT be bound to the entity — it's pure display. Regenerate via `AiParametersBodyYamlMapper.writeAsYaml(formState)` on every Form component's `ValueChangeEvent` and write to a plain `CodeEditor.setValue(...)` / `TextArea.setValue(...)`, not via data-binding.
**Warning signs:** Stack overflow or repeated field updates in dev log when editing parameters.

### Pitfall 5: XSS via assistant markdown
**What goes wrong:** Malicious prompt induces LLM to output `<script>...</script>` or `<img onerror=...>`; Flexmark faithfully renders it; Vaadin `Html` embeds raw HTML; browser executes.
**Root cause:** Flexmark explicitly does not sanitize (per its docs).
**Avoid:** Pipe every Flexmark output through OWASP sanitizer BEFORE wrapping in `Html`. Test with a prompt injection payload: `Write: <script>alert(1)</script>` — the script tag must not survive sanitization.

### Pitfall 6: Flexmark parser reuse thread-safety misunderstanding
**What goes wrong:** Developer creates `Parser.builder(opts).build()` per request; performance tanks under load; or worse, builds it with a `MutableDataSet` that gets mutated later and breaks other threads.
**Root cause:** `Parser` and `HtmlRenderer` are thread-safe after construction IF the `DataSet` was not subsequently mutated.
**Avoid:** Build once in a `@Bean` / `@Component` constructor; never mutate the options after build.

### Pitfall 7: `UI.access()` called from listener registered in `onInit` for now-detached UI
**What goes wrong:** User navigates away during ingestion; status event fires; listener tries `ui.access()` on a detached UI; throws.
**Root cause:** Listener holds a stale UI reference.
**Avoid:** Use a Spring `@Component`-scoped broker with a `Set<UI>` (weak-reference or explicit attach/detach registration). Check `ui.isAttached()` before `ui.access()`, or wrap in try/catch(UIDetachedException).

### Pitfall 8: Spring AI streaming loses tool-call metadata
**What goes wrong:** Chat contains tool calls; `.stream().chatResponse()` returns a `Flux<ChatResponse>` whose aggregated `AssistantMessage` has no `toolCalls`; UI can't render tool-call cards from the final response.
**Root cause:** Known Spring AI bugs #5167, #3366, #4315 — tool-call information is lost during streaming aggregation.
**Avoid:** Use the `Sinks.Many` bridge pattern (ref impl `ChatImpl.java`). Emit explicit `StreamingEvent.ToolCall` events from a `ToolEventListener` fired during the synchronous tool-call phase. Don't rely on streaming to surface tool metadata. OR, detect "prompt likely to trigger tool call" and route to blocking `ask(...)`.
**Warning signs:** Tool-call cards missing from streaming responses even though the LLM called tools.

### Pitfall 9: `AppShellConfigurator` collision with host app
**What goes wrong:** Host defines its own `@Push`-annotated `AppShellConfigurator`; Vaadin refuses to start — "more than one class implements AppShellConfigurator".
**Root cause:** Vaadin allows exactly one.
**Avoid:** Ship as **optional/documented** class (see Pattern 2). If host has their own, document copy-paste snippet.

### Pitfall 10: Multiple UI tabs per user — status events
**What goes wrong:** Admin has two browser tabs open on KB view; status event fires; only one tab refreshes.
**Root cause:** Broker tracks one UI per user.
**Avoid:** Track UIs as a `Set<UI>`, not `Map<Username, UI>`. Iterate all on event.

## Code Examples

### Example 1: Streaming event wrapper (recommended shape)

```java
// com.vn.agent.orchestration.StreamingEvent
public sealed interface StreamingEvent {
    record Content(String markdownChunk) implements StreamingEvent {}
    record ToolCall(UUID toolCallId, String toolName, String argsJson) implements StreamingEvent {}
    record ToolResult(UUID toolCallId, String summary, AiToolCallOutcome outcome) implements StreamingEvent {}
    record Citation(int index, UUID documentId, String snippet) implements StreamingEvent {}
    record Final(UUID runId, long latencyMs, int promptTokens, int completionTokens) implements StreamingEvent {}
    record Error(String messageKey, Map<String, Object> params) implements StreamingEvent {}
}
```

### Example 2: Audit export via Jmix gridexport add-on (D-20, Open Q#1 resolved)

```xml
<!-- In ToolCallAuditListView.xml — declare actions on the audit dataGrid -->
<hbox id="auditButtonsPanel" classNames="buttons-panel">
    <button id="excelExportBtn" action="auditDataGrid.excelExport"
            text="msg://auditList.action.exportExcel" themeNames="primary"/>
    <button id="jsonExportBtn"  action="auditDataGrid.jsonExport"
            text="msg://auditList.action.exportJson"/>
</hbox>

<dataGrid id="auditDataGrid"
          dataContainer="auditsDc"
          selectionMode="MULTI">
    <actions>
        <action id="excelExport" type="grdexp_excelExport"/>
        <action id="jsonExport"  type="grdexp_jsonExport"/>
    </actions>
    <!-- columns ... -->
</dataGrid>
```

Both actions honor the container's current filter and sort; `selectionMode="MULTI"` lets admins optionally export a selected subset. Add `implementation 'io.jmix.gridexport:jmix-gridexport-flowui-starter'` to the `ai-agent` module `build.gradle`.

### Example 3: View access gating (D-28)

```java
// In AiAgentAdminRole interface (Phase 2, extended Phase 7)
@ResourceRole(name = "AI Agent Admin", code = "ai-agent-admin")
public interface AiAgentAdminRole {
    @MenuPolicy(menuIds = {"aiAgent.parameters.list", "aiAgent.knowledge.list", "aiAgent.audit.list"})
    @ViewPolicy(viewIds = {"AiAgent_Parameters.list", "AiAgent_Parameters.detail",
                           "AiAgent_KnowledgeBase.list", "AiAgent_ToolCallAudit.list"})
    void adminViews();
}

// On the view class itself — belt-and-suspenders direct-URL protection
@Route(value = "ai-agent/parameters", layout = MainView.class)
@ViewController("AiAgent_Parameters.list")
@ViewDescriptor("parameters-list-view.xml")
@Secured("ROLE_ai-agent-admin")  // Spring Security — Jmix exposes role codes as ROLE_<code>
public class ParametersListView extends StandardListView<AiParameters> { ... }
```

### Example 4: Build-failing locale coverage scan (D-30)

```java
// src/test/java/com/vn/agent/i18n/LocaleParityTest.java
@Test
void enAndViHaveIdenticalKeys() throws IOException {
    Properties en = loadBundle("messages.properties");
    Properties vi = loadBundle("messages_vi.properties");
    Set<Object> enKeys = new TreeSet<>(en.keySet());
    Set<Object> viKeys = new TreeSet<>(vi.keySet());
    assertThat(enKeys).containsExactlyElementsOf(viKeys);
}

@Test
void noHardcodedStringsInViewXml() throws IOException {
    Path root = Path.of("src/main/resources/com/vn/agent/view");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
        files.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
            String xml = Files.readString(p);
            // Look for attribute values like text="Something" that don't start with msg://
            Matcher m = Pattern.compile("(title|text|label|placeholder)=\"(?!msg://)([^\"]+)\"").matcher(xml);
            while (m.find()) violations.add(p + ": " + m.group(0));
        });
    }
    assertThat(violations).isEmpty();
}
```

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| ChatModel.stream() directly | `ChatClient.prompt().stream().chatResponse()` | Higher-level; supports advisors; but tool-calls lose metadata (GH #5167) |
| Raw Vaadin Upload | Jmix `<upload>` | Multi-file + drag-drop OOB |
| Polling status endpoints | `ApplicationEventPublisher` + `@Push` + `UI.access()` | Zero-latency updates, zero server load when idle |
| Copy-paste component reuse | Jmix Fragment | First-class reuse, XML-driven |
| Hand-rolled markdown | Flexmark + OWASP sanitizer | Safe, fast, feature-complete |

**Deprecated/outdated:**
- Vaadin `MessageList` with `setMarkdown(true)` and tool-call cards: mixing the two fights each other. Phase 7 uses plain `VerticalLayout` + bubble components per D-06.
- Polling for ingestion status: D-16 explicitly forbids.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Parser` and `HtmlRenderer` Flexmark instances are thread-safe once built with immutable `DataSet` | Pattern 7 | Concurrent modification issues under chat load; mitigation = wrap in `ThreadLocal` if proven unsafe |
| A2 | Vaadin `@Push` via single `AppShellConfigurator` is the only activation mechanism in Vaadin 24/Jmix 2.8 (no programmatic alternative) | Pattern 2 | Host-collision resolution may need a different approach; documented snippet fallback always works |
| A3 | Spring AI 1.1.4 `ChatClient.prompt().stream().chatResponse()` returns `Flux<ChatResponse>` and behaves like ref impl | Pattern 1 | If signature differs, planner must re-verify via actual ai-agent classpath javap before coding |
| A4 | OWASP Sanitizer's `Sanitizers.FORMATTING.and(BLOCKS).and(LINKS).and(TABLES)` policy is sufficient for assistant markdown + tables without blocking legitimate LLM output | Pattern 7 | False positives in benign markdown; tune policy |
| A5 | `ApplicationEventPublisher` events fired inside `afterCommit` reach `@EventListener` methods synchronously on the committing thread | Pattern 3 | If async (requires `@Async`), ordering may not hold — ref impl AuditWriter Phase 4 D-14 already uses this pattern and works, so risk is low |
| A6 | Jmix Fragment's public instance methods are invokable by including view after fragment `ReadyEvent` fires | Pattern 5 | Timing issue — confirm with Jmix skill `jmix-fragments` during plan |

## Open Questions

1. **Jmix gridexport CSV gap — RESOLVED 2026-04-21 (Option A).**
   - **Decision:** Align UI-06 / D-20 with the add-on's native formats. Ship Excel + JSON via the gridexport add-on; do **not** ship CSV. User-confirmed via /gsd-plan-phase 7: "follow addons export docs".
   - **Contract updates applied in this commit:**
     - `REQUIREMENTS.md` UI-06 wording → Excel + JSON export.
     - `ROADMAP.md` Phase 7 deliverable + success criterion #4 → Excel/JSON.
     - `CONTEXT.md` D-20, deliverable row, external deps, deferred note → Excel + JSON.
     - `UI-SPEC.md` primary action, message keys (`auditList.action.exportExcel`, `auditList.action.exportJson`), filename patterns (`.xlsx` + `.json`), external deps table.
   - **Binding planner guidance:** Declare both actions on the audit `dataGrid` (`<action id="excelExport" type="grdexp_excelExport"/>`, `<action id="jsonExport" type="grdexp_jsonExport"/>`), bind two buttons via `action="{grid}.excelExport"` / `action="{grid}.jsonExport"`, set `selectionMode="MULTI"` for optional subset export, and include `io.jmix.gridexport:jmix-gridexport-flowui-starter` in the `ai-agent` module `build.gradle`.

2. **Streaming + tool-call interaction — the `Sinks.Many` bridge pattern.**
   - **What we know:** ref impl (`ChatImpl.java`) uses `Sinks.Many<EventStreamValueHolder>` populated by a `ToolEventListener` that hooks Spring AI's blocking tool execution. Spring AI GH issues confirm tool-calls do not surface cleanly through `.stream().chatResponse()` alone.
   - **What's unclear:** Whether Spring AI 1.1.4 exposes a `ToolEventListener` equivalent or whether we need to implement it via a `ToolCallback` wrapper that also emits events. (Phase 3 already has `ToolCallbackAuditDecorator` which wraps every tool — that's the natural place to also emit streaming events.)
   - **Recommendation:** Planner extends `ToolCallbackAuditDecorator` (Phase 4) to optionally emit to a `Sinks.Many` sink when streaming is active, threaded via the `RunContext`. Verify via Context7 `/spring-projects/spring-ai/v1.1.4` that the decorator hook surface doesn't change in 1.1.4.

3. **Vaadin `AppShellConfigurator` + Spring `@ConditionalOnXxx`.**
   - **What we know:** Vaadin scans classpath for `AppShellConfigurator` implementers at startup, not Spring beans.
   - **What's unclear:** Whether a Spring-conditional `AppShellConfigurator` bean is actually excluded from Vaadin's scan when the condition fails, or whether the class loading itself triggers Vaadin registration.
   - **Recommendation:** Test at plan time with a two-configuration experiment. If conditionals don't work, fall back to documented snippet + no shipped class.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring AI client-chat | ChatService.stream() | ✓ | 1.1.4 | — |
| Jmix FlowUI | all views | ✓ | 2.8 | — |
| Reactor | Flux/Sinks | ✓ | transitive via Spring AI | — |
| PostgreSQL | status / audit queries | ✓ | via host | — |
| Flexmark | markdown render | ✗ | — | Add dependency |
| OWASP Java HTML Sanitizer | XSS defence | ✗ | — | Add dependency |
| Jmix gridexport (`io.jmix.gridexport:jmix-gridexport-flowui-starter`) | Excel + JSON export (audit view) | ✗ | — | Add dependency; declare `grdexp_excelExport` + `grdexp_jsonExport` actions on `ToolCallAuditListView` dataGrid |

No blocking missing deps — all three new libs are simple Maven additions.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5.x (Spring Boot test starter) + Mockito + Jmix `@UiTest` |
| Config file | `ai-agent/ai-agent.gradle` test block |
| Quick run command | `./gradlew :ai-agent:ai-agent:test` |
| Full suite command | `./gradlew :ai-agent:ai-agent:check` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| UI-01 | Streaming chat Flux subscribe + UI.access dispatch | unit (Mockito stub ChatService returning Flux.just) + `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "*ChatViewStreamTest"` | ❌ Wave 0 |
| UI-02 | Stop disposes active stream + calls CancellationRegistry | unit | `./gradlew ... --tests "*ChatViewStopTest"` | ❌ Wave 0 |
| UI-03 | ConversationListView role-aware filter | integration `@SpringBootTest` | `... --tests "*ConversationListRoleFilterTest"` | ❌ Wave 0 |
| UI-04 | Form → YAML preview live sync | `@UiTest` | `... --tests "*ParametersDetailYamlPreviewTest"` | ❌ Wave 0 |
| UI-05 | Upload succeeded triggers upload service + grid refresh | `@UiTest` + event-broker mock | `... --tests "*KnowledgeBaseUploadTest"` | ❌ Wave 0 |
| UI-05 status push | Status event via publisher → UI.access on attached UIs | integration | `... --tests "*DocumentStatusPushTest"` | ❌ Wave 0 |
| UI-06 | Audit filter + Excel + JSON export | `@UiTest` | `... --tests "*ToolCallAuditListViewTest"` | ❌ Wave 0 |
| UI-09 | No hardcoded strings in view XML | unit classpath scan | `... --tests "*LocaleParityTest"` | ❌ Wave 0 |
| UI-09 | en ↔ vi parity | unit | `... --tests "LocaleParityTest#enAndViHaveIdenticalKeys"` | ❌ Wave 0 |
| UI-10 | Admin views reject non-admin user | integration `@SpringBootTest` with test-user fixture | `... --tests "*AdminViewAccessTest"` | ❌ Wave 0 |
| D-07 XSS | Flexmark + sanitizer strips `<script>` | unit | `... --tests "MarkdownRendererXssTest"` | ❌ Wave 0 |
| D-02 Push | AppShell @Push class present or documented snippet | unit classpath probe | `... --tests "*PushAutoConfigTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :ai-agent:ai-agent:test` (excludes `live`, `rag-it`, `eval`)
- **Per wave merge:** `./gradlew :ai-agent:ai-agent:check`
- **Phase gate:** Full suite green + manual `./gradlew :jmix-app:bootRun` click-through of Chat/Conv/Params/KB/Audit for success criteria #1–#5.

### Wave 0 Gaps
- [ ] `src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java`
- [ ] `src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java`
- [ ] `src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java`
- [ ] `src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java`
- [ ] `src/test/java/com/vn/agent/view/knowledge/DocumentStatusPushTest.java`
- [ ] `src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java`
- [ ] `src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java`
- [ ] `src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java`
- [ ] `src/test/java/com/vn/agent/security/AdminViewAccessTest.java`
- [ ] `src/test/java/com/vn/agent/i18n/LocaleParityTest.java` (locale parity + hardcoded-string scan)
- [ ] `src/test/java/com/vn/agent/push/PushAutoConfigTest.java`
- [ ] Framework install: none (JUnit + Spring Boot test + `@UiTest` already on classpath)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Jmix `CurrentAuthentication` (authenticated-only contract) |
| V3 Session Management | yes | Vaadin session + CSRF tokens for upload (OOB) |
| V4 Access Control | yes | `@ViewPolicy`/`@MenuPolicy` + view-class `@Secured` (belt-and-suspenders, D-28); `ConversationGateway` ownership (Phase 4 D-09) |
| V5 Input Validation | yes | Jmix Form validators on Parameters; YAML strict-on-write already in Phase 6; OWASP sanitizer on assistant markdown |
| V6 Cryptography | no | Phase 7 introduces no new crypto paths |
| V14 Configuration | yes | `@Push` transport config; upload receiver type; size caps |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| XSS via assistant markdown | Tampering / Elevation | Flexmark → OWASP sanitizer → Vaadin `Html` (Pitfall #5) |
| Direct-URL access to admin views by non-admin | Elevation | `@Secured("ROLE_ai-agent-admin")` on view class (D-28) |
| Cross-user conversation replay | Information Disclosure | `ConversationGateway` ownership check (Phase 4 D-09) — views must not bypass |
| Upload of huge / malicious files | DoS | `maxFileSize` attribute on `<upload>`, `MULTI_FILE_TEMPORARY_STORAGE_BUFFER` (Pitfall #3), Tika-level content validation in worker |
| Prompt-injection echo in audit log | Information Disclosure | Phase 6 already flags with pattern KEY not matched text — Phase 7 UI renders key+i18n-msg, never raw text |
| WebSocket hijacking / CSRF on /push | Tampering | Vaadin ships CSRF token on push channel; do not disable |

## Sources

### Primary (HIGH confidence)
- Phase 4/5/6 CONTEXT files — locked in-repo decisions, read verbatim
- `ai-agent/ai-agent/ai-agent.gradle` — [VERIFIED] current classpath: `spring-ai-client-chat:1.1.4`, `spring-ai-rag`, `spring-ai-tika-document-reader:1.1.4`
- `com/vn/agent/rag/AsyncIngestionWorker.java`, `IngestionStatusWriter.java`, `CancellationRegistry.java` — [VERIFIED] ingestion event wiring source of truth
- `com/vn/agent/ChatService.java`, `com/vn/agent/orchestration/ChatResponseDto.java` — [VERIFIED] current interfaces to extend
- `com/vn/agent/parameters/AiParametersBody.java` — [VERIFIED] enumerated fields for D-11 Form
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/chat/ChatImpl.java` — [VERIFIED] canonical Flux+Sinks.Many pattern (lines 216-304), ToolEventListener shape, persistence pattern
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/main/java/io/jmix/ai/backend/view/chat/ChatView.java` — [VERIFIED] subscribe + UI.access + dispose-on-detach pattern (lines 79-110, 186-194), scrollToBottom JS (lines 174-179)
- [docs.jmix.io/jmix/flow-ui/vc/components/upload.html](https://docs.jmix.io/jmix/flow-ui/vc/components/upload.html) — Jmix `<upload>` component API
- [docs.jmix.io/jmix/flow-ui/fragments/fragments.html](https://docs.jmix.io/jmix/flow-ui/fragments/fragments.html) — Fragment API
- [docs.jmix.io/jmix/grid-export/actions.html](https://docs.jmix.io/jmix/grid-export/actions.html) — Excel + JSON actions (no CSV)
- [vaadin.com/docs/latest/flow/advanced/server-push](https://vaadin.com/docs/latest/flow/advanced/server-push) — @Push, Transport.WEBSOCKET_XHR, UI.access()
- [docs.spring.io/spring-ai/reference/api/chatclient.html](http://docs.spring.io/spring-ai/reference/api/chatclient.html) — ChatClient.stream() return types

### Secondary (MEDIUM confidence)
- Spring AI GH issues [#5167](https://github.com/spring-projects/spring-ai/issues/5167), [#3366](https://github.com/spring-projects/spring-ai/issues/3366), [#4315](https://github.com/spring-projects/spring-ai/issues/4315) — tool-calls lose metadata during streaming
- [github.com/vsch/flexmark-java](https://github.com/vsch/flexmark-java) — parser thread safety, perf ~10× CommonMark speed
- [OWASP Java HTML Sanitizer](https://owasp.org/www-project-java-html-sanitizer/) — policy DSL, thread-safety

### Tertiary (LOW confidence)
- Gradle coordinate `io.jmix.gridexport:jmix-gridexport-flowui-starter` — cited across multiple WebSearch hits but exact version string is BOM-managed; planner should verify against the Jmix BOM active in this project at plan time

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — dependencies verified via gradle + docs
- Architecture: HIGH on overall shape (ref impl is authoritative for streaming); MEDIUM on Push AppShellConfigurator collision resolution
- Pitfalls: HIGH — each pitfall is either witnessed in ref impl comments, Spring AI GH issues, or OWASP/Flexmark official docs
- Spring AI streaming+tools: MEDIUM — documented bug, documented workaround, but exact 1.1.4 API shape of any `ToolEventListener` equivalent not verified via Context7 in this session (CLI unavailable)
- gridexport export format: HIGH — Open Q#1 resolved 2026-04-21 (Option A: Excel + JSON via add-on, CSV dropped)

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (30 days — Spring AI 1.1.x is stable; Jmix 2.8 is stable; Vaadin 24 is stable)
