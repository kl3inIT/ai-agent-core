# Architecture Research — v1.2 Operator Experience, Voice Input & Runtime Performance

**Domain:** Subsequent-milestone integration into a shipped Jmix 2.8 + Spring Boot 3 + Spring AI 1.1.4 + Vaadin Flow + pgvector AI agent add-on (`ai-agent` functional module + `ai-agent-starter` auto-config module).
**Researched:** 2026-05-11
**Confidence:** HIGH for existing-architecture seams (read directly from source); MEDIUM-HIGH for Spring AI 1.1.4 transcription API shape (verified via Context7 / spring.io reference docs); HIGH for build-order dependency reasoning.

> **Codebase reality check (correction to the milestone brief):** The Flow UI is **already in the functional `ai-agent` module** (`com.vn.agent.view.*`), not in `ai-agent-starter`. `ai-agent.gradle` already depends on `jmix-flowui-starter`, `jmix-flowui-themes`, `jmix-security-flowui-starter`, `jmix-gridexport-flowui-starter`, flexmark, OWASP sanitizer. The "4-module split deferred" decision (D-01) is still in force; v1.2 work adds packages under the existing two modules. `ChatPanelFragment` lives at `com.vn.agent.view.chat.fragment.ChatPanelFragment` (≈1340 lines) with descriptor `view/chat/fragment/chat-panel-fragment.xml`. There is also already a `view/configuration/AiConfigurationView.java` (a combined admin screen referencing `AiParametersResolver`, `BaselineContextProvider`, `SystemPromptComposer`, `ParametersService`, `MetaclassComboBoxHelper`) and `view/parameters/ParametersDetailView.java` / `ParametersListView.java`. Spring AI is on the classpath as `spring-ai-openai:1.1.4` (the model module) plus `spring-ai-client-chat`, `spring-ai-rag`, `spring-ai-starter-vector-store-pgvector`; the full `spring-ai-starter-model-openai` is only in `ai-agent-starter.gradle` (it auto-configures `ChatModel`/`EmbeddingModel` from `spring.ai.openai.*`). No `RestClient` usage, no `OpenAiAudioApi`, no transcription code exists yet.

---

## Standard Architecture (current, with v1.2 additions overlaid)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Flow UI  (com.vn.agent.view.* — inside ai-agent module)                       │
│  ┌───────────────┐ ┌──────────────┐ ┌─────────────────┐ ┌──────────────────┐  │
│  │ ChatView /    │ │ Conversations│ │ ParametersList/ │ │ Exposure / KB /  │  │
│  │ ChatDialogView│ │ ListView     │ │ Detail + AiConf │ │ VectorStoreDebug │  │
│  └──────┬────────┘ └──────────────┘ └────────┬────────┘ │ AiUiSettings     │  │
│         │ embeds                              │          │ AuditListView    │  │
│  ┌──────▼─────────────────────────────────┐   │          └──────────────────┘  │
│  │ ChatPanelFragment (single shared comp.)│   │                                │
│  │  • messageListSlot (MessageList)       │   │   v1.2 #2: + chat-state side   │
│  │  • intentCardRow                       │   │       panel  (NEW section in   │
│  │  • messageInputSlot ◄── v1.2 #1 mic    │   │       the attachmentsPanel     │
│  │      button + MediaRecorder JS bridge  │   │       column, contract-safe)   │
│  │  • attachmentsPanel (CRM right pane)   │   │   v1.2 #2: + per-turn tool-    │
│  │  • StreamEventRenderer (pure fn)       │   │       detail collapsible +     │
│  │  • MessageBubbleComponent              │   │       streaming-status badge   │
│  └──────┬─────────────────────────────────┘   │   v1.2 #3: model dropdown +    │
│         │ ChatService.ask / .stream           │       free-entry in #3 screen  │
└─────────┼──────────────────────────────────────┼─────────────────────────────┘
          │                                      │
┌─────────▼──────────────────────────────────────▼─────────────────────────────┐
│  Orchestration  (com.vn.agent.orchestration.*)                                 │
│  DefaultChatServiceImpl ──► ChatClient (ChatClientFactory @Bean)               │
│   • RunContext (per-thread; runId, convId, rootAuditId, retrieval params)      │
│   • AiParametersResolver (resolveActive / effectiveModel / effectiveTemp ...)  │ ◄─ v1.2 #3/#4
│   • BaselineContextProvider.renderAsText  → agent.entities + agent.permissions  │ ◄─ v1.2 #6 perf
│   • SystemPromptComposer + AgentSystemPromptRulesComposer                      │
│   • RetrievalFilterBuilder.buildFor(auth)  → RAG Filter.Expression             │ ◄─ v1.2 #6 perf
│   • AiTaskFileMediaResolver.resolveActive(convId) → Media[] + budgetExceeded   │ ◄─ v1.2 #6 perf
│  Advisor chain: AuditAdvisor → MessageChatMemoryAdvisor → ToolCallAdvisor      │
│                 (+ OutputScannerAdvisor, RetrievalAugmentationAdvisor)         │
│  ──────────────────────────────────────────────────────────────────────────   │
│  v1.2 #1 NEW:  TranscriptionService (strategy iface, com.vn.agent.stt.*)       │
│     ├─ SonioxTranscriptionService  (default; custom RestClient; no Java SDK)   │
│     └─ SpringAiTranscriptionService (OpenAI-direct; OWN OpenAiAudioApi bean)   │
│     DOES NOT touch ChatService / ChatClient — fully disjoint path              │
└────────────────────────────────────────────────────────────────────────────────┘
          │                                      │
┌─────────▼──────────────────────────────────────▼─────────────────────────────┐
│  Tools  (com.vn.agent.tools.*)                                                 │
│  AgentToolCallbacks.forCurrentUser()  → ToolCallback[]                          │ ◄─ v1.2 #6 perf
│   ToolCallbackAuditDecorator (read+link+extraction)  • Mutation* boundary deco  │
│   BuiltInDataTools (describe_entity/list/find/get) + ToolEntityResolver         │ ◄─ v1.2 #6 perf
│   FetchPlanResolver + FetchPlanIntersector (projection, not security)           │ ◄─ v1.2 #6 perf
│   BuiltInLinkTools (always-on deep links)                                       │
│   ── mutation (conditional ai-agent.tools.mutation.enabled=true) ──             │
│   BuiltInMutationTools  ─── (v1.2 #5 hardening target) ───────────────────      │
│     gate chain: AuthorizationService → MutationIntentRepository (reserve) →     │
│     MutationAttributeBinder.coerce → MutationGuard SPI → MutationSaveExecutor    │ ◄─ v1.2 #5 batch FK load
│     (@Transactional) → markCommitted; MutationCommitCoordinator owns audit       │ ◄─ v1.2 #5 dedup gate seq
│     RelatedWriteMetadataResolver (verified Jmix-metadata authority)              │ ◄─ v1.2 #5 cache metadata
│     bulk_save_records extends the same chain                                    │
└────────────────────────────────────────────────────────────────────────────────┘
          │                                      │
┌─────────▼──────────────────────────────────────▼─────────────────────────────┐
│  Security / Exposure  (com.vn.agent.exposure.* + Jmix AccessManager)           │
│  LlmExposurePolicy (delegate CurrentUserSchemaAccess; getReadableSchema/        │ ◄─ v1.2 #6 perf (careful)
│   canReadEntity/canReadAttribute/canCreate/canUpdate/getDenylistedEntityNames)  │
│  LlmExposureRuleRepository (UnconstrainedDataManager) ; AiExposureRuleEntity-    │
│   Listener (SINGLE publish site of LlmExposureChangedEvent)                      │
│  AiAgentMutationRole marker • AiAgentAdminRole • AiAgentUserRole                 │
└────────────────────────────────────────────────────────────────────────────────┘
          │
┌─────────▼─────────────────────────────────────────────────────────────────────┐
│  Persistence                                                                    │
│  main DB: host entities (DataManager)  ·  agentstore: AiConversation, AiMessage, │
│   AiAuditEvent (tree-lite PARENT_ID), AiParameters, AiExposureRule,              │
│   AiMutationIntent, AiTaskFile, AiUiSettings, AiExtractionDraft, SPRING_AI_*     │
│  pgvector: AI_AGENT_KB_VECTOR_STORE (RAG only — disjoint from chat task files)   │
│  Config: module.properties (Spring defaults — NOT in strict seed YAML) +         │ ◄─ v1.2 #4 knob migration
│   default-params.yaml (strict AiParameters seed) + host application.properties   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Component Responsibilities — what changes per v1.2 feature

| v1.2 Feature | New components | Modified components | Untouched contract held at the seam |
|---|---|---|---|
| **#1 STT voice input** | `TranscriptionService` (iface) + `SonioxTranscriptionService` + `SpringAiTranscriptionService` + `AiAgentSttProperties` (`@ConfigurationProperties("ai-agent.stt")`) + `SttAutoConfiguration`/`TranscriptionSelectorConfig` (the `@Bean` that picks the active impl by `ai-agent.stt.provider`) + a small JS connector (`@JsModule`) + a `SttAudioReceiver` (Vaadin `UploadHandler`/`StreamReceiver` bound to the mic widget) — all in `com.vn.agent.stt.*` (functional module), except the autoconfig which may live in `ai-agent-starter` | `ChatPanelFragment` (mic button into `messageInputSlot`, transcript → `MessageInput` value, non-blocking error+retry row); `module.properties` (new `ai-agent.stt.*` defaults). `AuditWriter` is **not** modified — it already accepts arbitrary `eventName` | `STT_TRANSCRIPTION` audit via `AuditWriter.writeToolCall(..., eventName="stt_transcription", ...)` — **no new `AuditKind`** (string already reserved AUD-06); `TranscriptionService` **never calls `ChatService.ask`**; transcript lands in `MessageInput` for review-before-send |
| **#2 Chat UX & observability** | a small `TurnDetail`/`ToolCallDetail` in-memory record(s) inside `view.chat.fragment` (POJO, no entity); a `ChatStatePanel` Composite + a collapsible `ToolDetailDisclosure` Composite | `ChatPanelFragment` (adds the side panel + per-turn detail disclosure + ephemeral status badge — `streamProgressBar` already half-exists); `chat-panel-fragment.xml` (new section inside `attachmentsPanel` or a sub-region of `messageListSlot`); `StreamEventRenderer` already emits `ToolCall(toolCallId,toolName,argsJson)` + `ToolResult(toolCallId,toolName,summary,outcome,payloadJson)` — the UI consumes these live, **no service-layer change** | `ChatSurfaceMounter` slot contract (`attachmentsPanel` id) intact; Phase 12 contract files (`ChatSurfaceMounter`, `AiUiSettings`, `AiUiSettingsService`, `ChatView`, `ChatDialogView`) zero-diff |
| **#3 Admin model dropdown** | a small curated-model catalog (`jmix.ai-agent.model-catalog` comma-list in `module.properties`, or a `@ConfigurationProperties` `List<String>`) + a `ComboBox<String>` with `allowCustomValue=true` (or combo + "custom…" → `TypedTextField<String>`) | `ParametersDetailView` and/or `AiConfigurationView` (model field becomes a combo with catalog + custom entry). `AiParametersBodyYamlMapper`/`ParametersService` unchanged — the custom value just writes the existing free-text `model` field in `bodyYaml` | `AiParametersBody.model` stays `@NotBlank String`; `AiParametersResolver.effectiveModel` validation (must contain `/` — OpenRouter slug) stays; admin-only, no per-conversation switching (`Overrides.model` path unchanged); curated defaults stay self-hostable open-weights |
| **#4 Config-knob migration** | (no new entity preferred) — extend `AiParametersBody` with new optional fields **or** grow the existing `AiUiSettings` singleton; small `*ConfigResolver` read-throughs (prefer `AiParameters`/`AiUiSettings` value, fall back to property) | `AiParametersResolver` (already does this read-through for `model/temperature/topP/maxTokens/ragTopK/ragSimilarityThreshold`); relevant `@ConfigurationProperties` records (`AiAgentRagProperties`, `AiTaskFileProperties`, `AiAgentTitleProperties`, `AiAgentMutationProperties` — `enabled` flag stays a boot property); admin screens surfacing the new editable knobs; `module.properties` comments noting which moved | strict `default-params.yaml` stays strict — `ParametersService`/`AiParametersBodyYamlMapper` validation unchanged; secrets (`spring.ai.openai.api-key`, `ai-agent.stt.soniox.api-key`, `ai-agent.stt.openai.api-key`) and `@ConditionalOnProperty` boot toggles (`ai-agent.tools.mutation.enabled`, `ai-agent.stt.enabled`) stay properties |
| **#5 Mutation-internals hardening** | `MutationGateChain` (a `@Component`) — runs the ordered fail-closed steps once for create/update/add-related/remove-related/bulk; a `RelatedWriteMetadataCache` (bounded map, no eviction needed) | `BuiltInMutationTools` (the 4 `@Tool` methods + bulk path delegate to `MutationGateChain` instead of inlining the sequence); `MutationAttributeBinder.coerceAttributes` (batch-load to-one FK targets in one `DataManager.load(...).ids(...)` instead of per-attribute `load().id()`); `RelatedWriteMetadataResolver` (memoize `(parentMetaClass.name, relationshipName) → SupportedRelatedRelationship`); `MutationCommitCoordinator` unchanged (still the sole audit owner) | **Behavior must stay byte-for-byte identical** — same fail-closed ordering (role → entity resolve → CRUD/attr checks → reserve/replay → coerce → `MutationGuard.check` → `mutationSaveExecutor.save` → `markCommitted` → audit), same exception classification, same audit row contents, same idempotency semantics. `MutationGuard` SPI contract (`MutationIntent(toolName, metaClass, idOrNull, coercedAttributes)`, after coerce, before save; `ToolVetoedException` reused) unchanged. No `@Transactional` on the chain (only `MutationSaveExecutor` is, crossed via a separate bean). Lock by extending `MutationToolInvariantsTest` |
| **#6 AI-runtime perf pass** | (mostly no new components) — possibly a tiny `RequestScopedCache` helper keyed on the `RunContext` runId | `BaselineContextProvider`, `LlmExposurePolicy` (memoize `getReadableSchema` *within one chat turn*; memoize `getDenylistedEntityNames` app-wide with an evictor), `FetchPlanIntersector`/`FetchPlanResolver`, `ToolEntityResolver`, `AiTaskFileMediaResolver`, `RetrievalFilterBuilder`, `SystemPromptComposer`/`AgentSystemPromptRulesComposer`, `MutationAttributeBinder` (overlaps #5) | **No caching across users or across exposure-rule changes.** Cache scope = the current `RunContext` (one chat turn) at most for user/role/exposure-sensitive data. `LlmExposureChangedEvent` already exists and remains the only mutation signal; any longer-lived memo must register an `@EventListener(LlmExposureChangedEvent.class)` evictor AND be keyed `(userId, roleSet, metaclass-name-set [, locale])` per the comment already in `BaselineContextProvider`. No benchmark harness, no admin-screen perf |

---

## Detailed integration points (answering the six questions)

### (1) STT path — where it attaches, where the service lives, provider wiring, OpenAI fallback

**Mic button + audio capture.** Mount the mic button **inside `ChatPanelFragment` via `messageInputSlot`** (the documented stable extension point — currently `messageInputSlot.add(streamProgressBar, messageInput)` in `onReady`). Browser-side `MediaRecorder` cannot be done by a pure Jmix component; use a small Vaadin client-side connector (`@JsModule` + `Element.executeJs` on a hidden `Div`, or a tiny `LitElement`) that records up to 60 s of `audio/webm;codecs=opus` (or `audio/mp4`) and ships the `Blob` back. For **transport of the blob to the server**, prefer a **Vaadin server-side receiver over a bespoke `@RestController`**: register an `UploadHandler.toFile(...)` (the exact Jmix-2.8 idiom already used for `taskFileUpload`) on a hidden/inline `Upload`-style element bound to the mic button, or a `StreamReceiver`/`AbstractStreamResource` round-trip. This keeps the audio inside the Vaadin session/security context (no separate endpoint to secure, no CSRF concern, no MIME-sniff issue) and reuses the existing upload pattern. The handler hands the staged temp file to `TranscriptionService.transcribe(...)`, gets back text, and the fragment sets it into `MessageInput` so the user reviews/edits before pressing send. On failure the fragment appends a **non-blocking error + retry row** (same pattern as the existing budget-exceeded toast / NOTICE rows) — the chat flow stays usable.

**Where `TranscriptionService` lives.** New package `com.vn.agent.stt` in the **functional `ai-agent` module** (alongside `taskfile`, `rag`, `tools`). It is not UI code and not starter code. The Jmix view package only calls it. `RestClient` (`org.springframework.web.client.RestClient`) is reachable transitively via Jmix/Spring Boot's `spring-web`; add it explicitly at `implementation` scope if a compile-classpath check fails.

**Provider selection wiring — use a selector `@Bean`, not `@ConditionalOnProperty` per impl**, because `ai-agent.stt.provider` can also name a *host-supplied bean* ("select it by bean name"), which `@ConditionalOnProperty` can't express:

```java
@AutoConfiguration  // (in ai-agent-starter, OR a @Configuration in ai-agent gated on ai-agent.stt.enabled)
@ConditionalOnProperty(prefix = "ai-agent.stt", name = "enabled", havingValue = "true")
class SttAutoConfiguration {
    @Bean @ConditionalOnMissingBean(name = "sonioxTranscriptionService")
    SonioxTranscriptionService sonioxTranscriptionService(AiAgentSttProperties p, RestClient.Builder b) {...}

    @Bean @ConditionalOnMissingBean(name = "springAiTranscriptionService")
    @ConditionalOnProperty(prefix = "ai-agent.stt", name = "provider", havingValue = "openai")
    SpringAiTranscriptionService springAiTranscriptionService(AiAgentSttProperties p) {...}

    @Bean @Primary
    TranscriptionService activeTranscriptionService(ApplicationContext ctx, AiAgentSttProperties p) {
        return switch (p.getProvider()) {                 // soniox | openai | <custom-bean-name>
            case "soniox" -> ctx.getBean("sonioxTranscriptionService", TranscriptionService.class);
            case "openai" -> ctx.getBean("springAiTranscriptionService", TranscriptionService.class);
            default       -> ctx.getBean(p.getProvider(), TranscriptionService.class);  // host bean
        };
    }
}
```
The top-level `ai-agent.stt.enabled` *is* a `@ConditionalOnProperty` boot toggle (it gates whether the whole STT subsystem + mic button exist) → it stays a property, not an `AiParameters` row.

**OpenAI fallback needs its OWN `OpenAiAudioApi` — it cannot reuse the chat `OpenAiApi` bean.** Spring AI 1.1.4 exposes `OpenAiAudioTranscriptionModel(OpenAiAudioApi)` and `OpenAiAudioApi.builder().apiKey(...).baseUrl(...)` (or `new OpenAiAudioApi(apiKey)`); options via `OpenAiAudioTranscriptionOptions.builder().responseFormat(TranscriptResponseFormat.TEXT).language("vi").build()`. The chat path's `OpenAiApi`/`ChatModel` is configured against `spring.ai.openai.base-url=https://openrouter.ai/api`, and **OpenRouter does not proxy `/audio/transcriptions`** (confirmed cross-cutting constraint). So `SpringAiTranscriptionService` must construct a *fresh* `OpenAiAudioApi` with `baseUrl=https://api.openai.com` and the **independent** `ai-agent.stt.openai.api-key` (require it explicitly; don't silently fall back to `spring.ai.openai.api-key` which is the OpenRouter key). The full `spring-ai-starter-model-openai` (present only in `ai-agent-starter`) would auto-configure an `OpenAiAudioTranscriptionModel` bean from `spring.ai.openai.audio.transcription.*`, but that bean inherits the OpenRouter base-url — **do not rely on it**; build the audio API explicitly. Soniox is a completely separate `RestClient` against `https://api.soniox.com` with `Authorization: Bearer ${ai-agent.stt.soniox.api-key}` (`POST /v1/files` → `POST /v1/transcriptions` with `model=stt-async-v4`, `language_hints:["vi","en"]`, poll, retrieve, then `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` cleanup).

**Audit.** The service (or a thin wrapper around the selector) calls `AuditWriter.writeToolCall(parentId=null, runId=<fresh>, userUsername, conversationId=<current convId or null>, eventName="stt_transcription", argumentsJson=<duration,language,model>, resultSummary=<SHA-256 hash of transcript via AuditFieldHasher by default; raw transcript only if ai-agent.stt.audit.store-transcript=true>, latencyMs, outcome, ...)`. `AuditFieldHasher` already exists (Phase 9 plumbing — SHA-256 over UTF-8, lowercase hex). No new `AuditKind`, no schema change.

### (2) Chat-state side panel / tool-detail panel / streaming-status — hook point + whether the audit tree already carries what the UI needs

**Hook point: all three live inside `ChatPanelFragment`.** The ephemeral streaming-status badge is driven by the `StreamingEvent` flux the fragment already consumes (`submitChatTurn` → `chatService.stream(...)` → `StreamEventRenderer.renderStreamEventDetails(evt, ...)`); `streamProgressBar` in `messageInputSlot` is its embryonic form. The collapsible per-turn tool-detail panel attaches to each assistant bubble (`MessageBubbleComponent`) or the new state panel. The chat-state side panel is a new section — cleanest as a **nested vertical section inside the existing `attachmentsPanel` column** (keeps the `ChatSurfaceMounter` slot id and Phase 12 contract files zero-diff), not a second `splitterPosition` region (riskier — `ChatSurfaceMounter` mounts on `attachmentsPanel`).

**Does the audit tree carry tool args/results/timing?** Yes, for the *persisted* record: `AiAuditEvent` (tree-lite, `PARENT_ID`) carries `eventName`, `argumentsJson`, `resultSummary` (capped LOB), `outcome`, `latencyMs`, `startedAt`/`finishedAt`, plus retrieval-specific `queryText/topK/hitCount/topScore/filtersJson/retrievalHitsJson` — written by `ToolCallbackAuditDecorator` (PRE row, then POST row in `finally` with real outcome + latency) and `AuditWriter.writeRetrieval`, parented to `RunContext.getRootAuditId()`. **But the chat UI should NOT round-trip to the audit table for the live per-turn detail** — (a) audit rows are written `afterCommit` (async fan-out — `AuditAfterCommitSynchronization`), so they aren't available during streaming; (b) the audit table is the governance surface (`AuditListView`), a separate concern. The live panel should consume the **`StreamingEvent` flux the fragment already receives**: `StreamingEvent.ToolCall(toolCallId, toolName, argsJson)`, `StreamingEvent.ToolResult(toolCallId, toolName, summary, outcome, payloadJson)`, `StreamingEvent.Citation`, `StreamingEvent.Final(runId, convId, latencyMs, promptTokens, completionTokens, ...)`. **Recommendation:** add a small **in-memory per-turn model** in the fragment (`record TurnDetail(UUID runId, List<ToolCallDetail> toolCalls, int promptTokens, int completionTokens, long latencyMs, ...)`) populated from the flux; no service change, no new entity, no new audit kind. (Internal tool names are leak-guarded by `OutputScannerAdvisor` only for *user-facing assistant text*; the tool-detail panel is an explicit "show internals" affordance — fine to show `toolName` there; keep it collapsed by default, and gate it to admin/role if the project wants it operator-only.)

### (3) Model dropdown — catalog source + custom-entry path

Curated list = a small static catalog: either a comma-separated `jmix.ai-agent.model-catalog` in `module.properties` parsed into a `List<String>` (matches how `module.properties` already holds non-strict defaults), or a tiny `@ConfigurationProperties("jmix.ai-agent.model-catalog")` record. Render in `ParametersDetailView`/`AiConfigurationView` as a Vaadin `ComboBox<String>` with `setAllowCustomValue(true)` (or `JmixComboBox` + a "custom…" sentinel that reveals a `TypedTextField<String>`). **The custom-entry path writes nothing new** — it sets the existing `model` string in `AiParametersBody` → serialized into `bodyYaml` by `AiParametersBodyYamlMapper`/`ParametersService` exactly as today. `AiParametersResolver.effectiveModel` already validates "must contain `/`" (OpenRouter slug) — keep that; the catalog entries must satisfy it; the *curated* defaults stay self-hostable open-weights (e.g. `qwen/qwen3.6-35b-a3b`) while the custom field allows anything the host routes to. Admin-only — no `Overrides.model` per-conversation UI (deferred). The model field today is a free-text parameter inside `bodyYaml`, not a Spring `module.properties` knob, so this is purely a UI affordance change.

### (4) Config-knob migration — cleanest pattern given the strict-YAML / module.properties split

The codebase **already establishes the pattern**: `AiParametersResolver` does a **read-through that prefers the active `AiParameters` value, falling back to the property default** for `model`, `temperature`, `topP`, `maxTokens`, `ragTopK`, `ragSimilarityThreshold` (`effectiveRagTopK(params, defaultValue)`, `effectiveRagSimilarityThreshold(params, defaultValue)`). **Recommendation: extend that mechanism — do not invent a new settings entity.**

1. Add operator-relevant knobs as **new optional fields on `AiParametersBody`** (e.g. `taskFileTtlSeconds`, `taskFilePerTurnMaxFiles`) — keep `default-params.yaml` strict by giving each a documented default in the seed YAML *or* leaving it absent (resolver falls back to the property).
2. For knobs that are *runtime-readable singletons but don't fit a chat profile* (e.g. STT audit mode, conversation-title trigger), reuse the existing **`AiUiSettings` singleton pattern** (Phase 12 — singleton id, `AiUiSettingsService.loadCurrent()`, admin detail view, no arbitrary rows): grow `AiUiSettings` with more columns, or introduce one `AiOperatorSettings` agentstore singleton if you prefer to keep `AiUiSettings` UI-scoped. Either way: a thin `*ConfigResolver` that does `loadCurrent().getX() ?? property.x`.
3. **Keep as properties (never migrate):** secrets (`spring.ai.openai.api-key`, `ai-agent.stt.soniox.api-key`, `ai-agent.stt.openai.api-key`); boot-time `@ConditionalOnProperty` toggles (`ai-agent.tools.mutation.enabled`, `ai-agent.stt.enabled`, RAG ingest-executor sizing, `jmix.ai-agent.rag.sample-ingester.enabled`); `spring.ai.retry.*`. The migration deliverable: (i) an audit table classifying every `jmix.ai-agent.*` / `ai-agent.*` knob as "migrate to `AiParameters`/`AiUiSettings`" vs "stays property"; (ii) the resolver/`*ConfigResolver` read-through extensions; (iii) admin-UI fields. Strict seed YAML stays strict.

### (5) Mutation-hardening refactor — shared abstraction shape + where batch FK load and metadata caching slot in

**Shared abstraction = `MutationGateChain` (a `@Component`).** Today `BuiltInMutationTools.createRecord` / `updateRecord` / `addRelatedRecord` / `removeRelatedRecord` / the `bulk_save_records` path each inline the *same* ordered sequence:
`enforceMutationRole(AiAgentMutationRole.CODE)` → `toolEntityResolver.resolve*EntityOrThrow` → `enforceCreate/UpdatePermission` + `enforceAttributeWriteAccess` (+ `enforceInverseAttributeWriteAccess` / `enforceLlmRelationshipTargetExposure` for related-writes) → `mutationRequestHasher.hash(...)` → `mutationIntentRepository.reserveOrReplay(...)` → (if not RESERVED → `mutationCommitCoordinator.handleReservationResult`) → `mutationAttributeBinder.coerceAttributes` → `mutationGuard.check(new MutationIntent(...))` → `mutationSaveExecutor.save(...)` (sole `@Transactional`, proxy-crossed) → `markCommitted` → `mutationCommitCoordinator.safeWriteAudit(...)`; with three `catch` arms (`ToolVetoedException`, `ToolUserError`, `Throwable`) routing through `MutationCommitCoordinator`.

Extract into `MutationGateChain.execute(MutationGateRequest)` returning a `MutationGateOutcome`, parameterized by an operation enum + a functional "perform host write" callback (create/update build the entity differently; bulk batches). `BuiltInMutationTools`'s `@Tool` methods become thin adapters: build request → call chain → format result.

**Constraints at the seam (restate verbatim in the plan):**
- The chain (and `BuiltInMutationTools`) must carry **no `@Transactional`** — the only `@Transactional` is `MutationSaveExecutor.save/saveAll`, crossed via a separate bean (a `@Transactional` method self-invoked on the chain would be silently bypassed).
- `MutationGuard` SPI contract byte-identical: `mutationGuard.check(MutationIntent(toolName, metaClass, idOrNull, coercedAttributes))` called *after* `coerceAttributes`, *before* `mutationSaveExecutor.save`; `ToolVetoedException` reused verbatim.
- `MutationCommitCoordinator` stays the sole `AuditWriter.writeToolCall` caller; audit row contents (argumentsJson via `DiffSerializer.serializeEntityArgumentsJson`, diffJson, outcome enum, latency) unchanged.
- Same fail-closed ordering, same exception classification, same idempotency semantics (`reserveOrReplay`/`markCommitted`/`markFailedIfReserved`/`COMMIT_UNKNOWN` parking) — **byte-for-byte identical behavior.** Lock with a source-level/Mockito invariant test (extend the existing `MutationToolInvariantsTest`) asserting the call order + that the new chain has no `@Transactional` annotation.

**Batch to-one FK load** slots into `MutationAttributeBinder.coerceAttributes`: today `coerceAttributeValue` for each to-one attribute does `dataManager.load(targetMetaClass.getJavaClass()).id(uuid).optional()` per attribute → N round trips for N FK attributes. Refactor `coerceAttributes` to (1) collect all `(targetMetaClass → set of UUIDs)`, (2) issue one `dataManager.load(class).ids(uuids).list()` per target class, (3) build a `Map<UUID,Entity>` and resolve each attribute from it (keeping the per-target LLM-read-exposure check via `mutationAuthorizationService.enforceLlmRelationshipTargetExposure`). Behavior identical (same entities, same not-found → `ToolUserError`, same null-clears preserved via `LinkedHashMap`), just fewer SELECTs.

**Related-write metadata caching** slots into `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, relationshipName)` — memoize keyed `(parentMetaClass.getName(), relationshipName) → SupportedRelatedRelationship`. The Jmix metamodel is immutable at runtime so this cache **never needs eviction** and is safe across users (pure metadata, no security state). This is the one place a long-lived cache is unambiguously safe.

### (6) Perf pass — integration points + security-context-safe caching strategy

| Integration point | What's hot | Safe optimization | Cache scope / eviction |
|---|---|---|---|
| `BaselineContextProvider.renderAsText(convId)` (every turn) → `llmExposurePolicy.getReadableSchema()` | Recomputes the readable-schema map + locale labels + builds `agent.entities`+`agent.permissions` on every turn | Compute `getReadableSchema()` **once per turn** and pass to both block builders (the code comment already says they share the entity list) | Per-`RunContext` (one turn). Any cross-turn memo: key `(userId, roleSet, metaclass-name-set, locale)` + `@EventListener(LlmExposureChangedEvent.class)` evictor. **Never key on rendered text; never cache across users.** |
| `LlmExposurePolicy.getReadableSchema` / `canReadEntity` / `getDenylistedEntityNames` | Each call hits `CurrentUserSchemaAccess` (AccessManager constraints) **and** `LlmExposureRuleRepository` (an agentstore query) — called from baseline, every `BuiltInDataTools` call, `FetchPlanIntersector`, `RetrievalFilterBuilder`, `ToolEntityResolver`, mutation auth. Comment says "no cache, per D-14" | Memoize `getDenylistedEntityNames()` (the agentstore rule query — *not* user-specific; only an entity-name set) app-wide with an `@EventListener(LlmExposureChangedEvent.class)` evictor; memoize the per-user readable-schema **within a single chat turn** (multiple tool calls in one turn re-derive it today) keyed by the `RunContext` runId | Denylist set: app-wide, evicted on `LlmExposureChangedEvent`. Per-user schema: per-turn only. **Do not** cache `canReadAttribute`/`canReadEntity` decisions across turns — AccessManager row-level policies depend on the data. |
| `FetchPlanResolver` / `FetchPlanIntersector` (`tools/fetchplan`) | Builds + ACL-intersects a fetch plan per tool call; invokes `ToolFetchPlanCustomizer` SPI each time | Memoize the *Jmix-metadata-derived* intersection skeleton per `(entityName, requestedProjection, roleSet)` within a turn; leave SPI invocation as-is (its `FetchPlanContext` carries a request snapshot — may be request-sensitive) | Per-turn |
| `ToolEntityResolver` (`tools/ToolEntityResolver.java`) | `resolveCreatable/Readable EntityOrThrow(name)` re-walks metadata + exposure per call | Memoize `name → MetaClass` (pure metadata — safe long-lived); memoize the exposure verdict only per-turn | name→MetaClass: app-wide (no eviction). Exposure verdict: per-turn. |
| `AiTaskFileMediaResolver.resolveActive(convId)` | Loads all active task-file rows + reads each blob + LRU-budget-caps every turn; multi-file conversations re-read the same bytes | Cache resolved `Media` bytes per `(convId, taskFileId)` with a TTL aligned to the 24h conversation TTL; invalidate on `AiTaskFileDeletedUiEvent` / new upload (events already exist) | Conversation-scoped, evicted on add/delete events; still subject to the per-turn LRU budget cap |
| `RetrievalFilterBuilder.buildFor(auth)` | Builds the RAG `Filter.Expression` per turn from roles + denylist; comment notes "N roles → N copies of the model-pin clause" | Memoize per `(roleSet, denylistSet)`; denylist evicted on `LlmExposureChangedEvent`; roles come from `Authentication` so per-session keying is acceptable if evicted on exposure change | Per-session keyed by roleSet; evict on `LlmExposureChangedEvent` |
| `SystemPromptComposer` + `AgentSystemPromptRulesComposer` | `PROMPT_RULES` is a static constant but the composed prompt (profile + baseline + rules + private appendix + doc blocks) is rebuilt every turn | Rules string already constant; concatenation is cheap — low priority. If profiled hot, cache the "profile prompt + rules" prefix per active-`AiParameters`-version | App-wide keyed by `AiParameters` version; evict on `AiParameters` save |
| `MutationAttributeBinder` to-one loads | (same as #5 batch FK) | covered in #5 — overlaps the hardening phase | n/a |

**Cardinal rule for the perf pass:** every memo must respect the per-request security context — **no entry may be reused across users, across roles, or across an `LlmExposureChangedEvent`.** The only unconditionally-safe long-lived caches are pure-Jmix-metadata derivations (`RelatedWriteMetadataResolver` map; `ToolEntityResolver`'s name→MetaClass *before* the exposure check). Everything user/role/exposure-sensitive is either scoped to a single `RunContext` (one chat turn) or carries `(userId, roleSet, metaclass-name-set [, locale])` in the key **plus** an `@EventListener(LlmExposureChangedEvent.class)` evictor. No new global mutable state without an evictor. Spring Cache `ConcurrentMapCacheManager` is already on the classpath (used today for the rate-limit / token-breaker guards), so `@Cacheable` + a custom key generator + `@CacheEvict` on the exposure event is the natural mechanism.

---

## Suggested phase build order (v1.2)

Dependencies among the six features:

```
  Phase 15  Admin model dropdown + config-knob migration       (#3 + #4)
            depends on: nothing — touches ParametersDetailView /
            AiConfigurationView / AiParametersResolver only.
            → land FIRST: smallest, lowest-risk; #4 audit informs nothing else

  Phase 16  Mutation-internals hardening — MutationGateChain      (#5)
            extraction + batch FK load + RelatedWriteMetadataCache.
            depends on: nothing functional; MUST land BEFORE Phase 17
            so the perf pass refactors against the consolidated chain,
            not the duplicated one. Byte-for-byte-identical constraint
            locked by extended MutationToolInvariantsTest.

  Phase 17  AI-runtime performance pass                          (#6)
            depends on: Phase 16 (mutation-binding/FK-batch work lands
            in the shared chain). Otherwise touches BaselineContextProvider /
            LlmExposurePolicy / FetchPlanIntersector / ToolEntityResolver /
            AiTaskFileMediaResolver / RetrievalFilterBuilder — all shipped.

  Phase 18  Chat UX & observability                              (#2)
            depends on: ChatPanelFragment only (Phase 12 contract).
            Independent of STT / model picker / mutation / perf. Can run in
            parallel with Phase 16/17. Sequence before/with Phase 19 so the
            streaming-status badge and the STT error/retry row share the
            same in-fragment status-row infrastructure.

  Phase 19  Chat voice input — Soniox STT (+ OpenAI fallback)    (#1)
            depends on: ChatPanelFragment.messageInputSlot (stable since
            v1.0 + Phase 12); functionally independent of 10/11/13/14.
            New com.vn.agent.stt.* package + selector bean + JS connector +
            UploadHandler-based audio receiver + STT_TRANSCRIPTION audit
            (no new AuditKind). Land LAST — biggest surface; only the STT-
            specific UI bits depend on Phase 18's status-row work.
```

**Recommended order:** **15 (model + config) → 16 (mutation hardening) → 17 (perf pass) → 18 (chat UX/observability) → 19 (STT).**

Rationale: 15 is the cheapest win and unblocks nothing else (run first or in parallel). **16 must precede 17** (the perf pass refactors the consolidated `MutationGateChain` + the shared batch-FK load, not the duplicated sequence — doing perf first means redoing it after the extraction) — this is the one hard ordering constraint. 17 then proceeds against the hardened mutation internals and the already-shipped baseline/exposure/fetch-plan/media/RAG components. 18 depends only on `ChatPanelFragment` and can overlap 16/17. 19 (STT) depends on `ChatPanelFragment.messageInputSlot` (stable) and reuses 18's in-fragment status-row pattern for its non-blocking error/retry UI, so it lands last. 15 + 18 + 19 are independent of 16/17 if a different ordering is preferred.

(Phase numbering continues from v1.1.0's Phase 14 — these would be Phases 15–19; Backlog 999.1 → mutation hardening, Backlog 999.2 → STT.)

---

## Anti-Patterns to avoid in v1.2

### Caching exposure/security decisions across users or sessions
**What people do:** wrap `LlmExposurePolicy.getReadableSchema()` / `canReadEntity` in a plain `@Cacheable` to "make the perf pass easy."
**Why it's wrong:** exposure decisions are per-user *and* per-exposure-rule-version; a leaked entry shows User B's schema to User A or shows stale entities after a denylist edit.
**Do this instead:** scope to the current `RunContext` (one chat turn), or key on `(userId, roleSet, metaclass-name-set)` + register an `@EventListener(LlmExposureChangedEvent.class)` evictor. The comment in `BaselineContextProvider` already mandates this — honor it.

### Letting the STT path touch `ChatService` / `ChatClient`
**What people do:** wire the transcribed text straight into `ChatService.ask`.
**Why it's wrong:** STT is a disjoint pathway by spec — capture → transcribe → text into `MessageInput` for review-before-send. Auto-sending breaks the review contract and the privacy-safe audit boundary.
**Do this instead:** `TranscriptionService` returns text; `ChatPanelFragment` puts it in `MessageInput`. Zero advisor chain, zero tool calls, zero chat-memory write.

### Reusing the chat `OpenAiApi` bean for `/audio/transcriptions`
**What people do:** inject the existing `OpenAiApi`/`OpenAiAudioTranscriptionModel` auto-config bean into `SpringAiTranscriptionService`.
**Why it's wrong:** that bean points at `https://openrouter.ai/api`, which doesn't proxy `/audio/transcriptions`.
**Do this instead:** build a fresh `OpenAiAudioApi` against `https://api.openai.com` with the independent `ai-agent.stt.openai.api-key`.

### Adding a new `AuditKind` for STT
**What people do:** add `AuditKind.STT` and a new `AiAuditEvent` discriminator.
**Why it's wrong:** `STT_TRANSCRIPTION` is already a reserved `eventName` string (AUD-06); `AuditWriter.writeToolCall` already accepts arbitrary `eventName`.
**Do this instead:** `AuditWriter.writeToolCall(..., eventName="stt_transcription", ...)` — no new kind, no schema change.

### Changing mutation behavior during the hardening refactor
**What people do:** "improve" error messages or reorder steps while extracting `MutationGateChain`.
**Why it's wrong:** the refactor must be byte-for-byte behavior-identical (same fail-closed ordering, exception classification, audit row contents, idempotency semantics); the `MutationGuard` SPI contract must not move.
**Do this instead:** pure mechanical extraction; no `@Transactional` on the chain; lock with an extended `MutationToolInvariantsTest` asserting call order + the absence of `@Transactional` on the new chain.

### Migrating secrets or boot toggles into `AiParameters`
**What people do:** sweep "all the knobs" into the admin UI.
**Why it's wrong:** API keys belong in env/properties; `@ConditionalOnProperty` toggles (`ai-agent.tools.mutation.enabled`, `ai-agent.stt.enabled`) are evaluated at boot and can't be runtime-editable.
**Do this instead:** migrate only operator-relevant *runtime* knobs, via the existing `AiParametersResolver` read-through (prefer `AiParameters`/`AiUiSettings` value, fall back to property default); secrets and boot toggles stay properties.

### Breaking the `ChatSurfaceMounter` slot contract
**What people do:** restructure `chat-panel-fragment.xml` (rename `attachmentsPanel`, add a second split region) to fit the new observability panels.
**Why it's wrong:** `ChatSurfaceMounter` mounts on the `attachmentsPanel` slot id; Phase 12 contract files must stay zero-diff.
**Do this instead:** add the chat-state/tool-detail panels as sections internal to `ChatPanelFragment` (inside the `attachmentsPanel` column or a sub-region of `messageListSlot`).

### Round-tripping the audit table to render live per-turn detail
**What people do:** query `AiAuditEvent` children to populate the tool-detail panel.
**Why it's wrong:** audit rows are written `afterCommit` (async) — not available during streaming; the audit table is the governance surface, not a UI feed.
**Do this instead:** build a small in-memory `TurnDetail` model in the fragment from the `StreamingEvent` flux it already receives. No new entity, no new audit kind, no service change.

---

## Integration Points (summary)

### External services

| Service | Integration pattern | Notes |
|---|---|---|
| Soniox STT (`api.soniox.com`) | Custom `RestClient` (no Java SDK): `POST /v1/files` → `POST /v1/transcriptions` (`model=stt-async-v4`, `language_hints:["vi","en"]`) → poll → retrieve → `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}`. `Authorization: Bearer ${ai-agent.stt.soniox.api-key}` | Independent API key from the chat key. 60 s max recording. No transcoding — webm/opus or mp4 sent directly. |
| OpenAI transcription (`api.openai.com/v1/audio/transcriptions`) | Spring AI 1.1.4 `OpenAiAudioTranscriptionModel(OpenAiAudioApi)`; `OpenAiAudioApi.builder().apiKey(...).baseUrl("https://api.openai.com")`; `OpenAiAudioTranscriptionOptions.builder().responseFormat(TranscriptResponseFormat.TEXT).language("vi").build()` | **Must build a fresh `OpenAiAudioApi`** — cannot reuse the OpenRouter-pointed chat `OpenAiApi`. Independent `ai-agent.stt.openai.api-key`. |
| OpenRouter (existing, chat) | `spring-ai-starter-model-openai` auto-config + `spring.ai.openai.base-url=https://openrouter.ai/api` + per-request `ChatOptions.model` | Unchanged in v1.2. Does **not** proxy `/audio/transcriptions`. |

### Internal boundaries

| Boundary | Communication | Notes |
|---|---|---|
| `view.chat.fragment.ChatPanelFragment` ↔ `stt.TranscriptionService` | direct method call (`transcribe(file/bytes, hints) → TranscriptionResult`) | STT path never reaches `ChatService`. Mic button + JS connector + `UploadHandler`-based receiver live in the fragment; `TranscriptionService` in the functional module. |
| `view.chat.fragment.ChatPanelFragment` ↔ `orchestration.ChatService` | `.ask(...)` / `.stream(...)` returning `Flux<StreamingEvent>` | Unchanged. The new observability panels consume the existing `StreamingEvent` flux. |
| `tools.mutation.BuiltInMutationTools` ↔ new `MutationGateChain` | `execute(MutationGateRequest, hostWriteCallback) → MutationGateOutcome` | The chain owns the fail-closed sequence; tools become adapters. `MutationGuard` SPI + `MutationCommitCoordinator` audit ownership unchanged. No `@Transactional` on the chain. |
| perf-pass memos ↔ `exposure.LlmExposureChangedEvent` | `@EventListener(LlmExposureChangedEvent.class)` evictors | `AiExposureRuleEntityListener` remains the single publish site. Any cross-turn memo of exposure-derived data MUST listen to this event. |
| `orchestration.AiParametersResolver` ↔ `module.properties` defaults + active `AiParameters` row (and `AiUiSettings` singleton) | read-through (prefer row value, fall back to property) | The established pattern for #4 config-knob migration — extend it; don't add a parallel settings layer. |

## Sources

- Existing codebase (read directly): `ai-agent/ai-agent/src/main/java/com/vn/agent/**` — `view/chat/fragment/ChatPanelFragment.java`, `view/chat/fragment/StreamEventRenderer.java`, `view/chat/fragment/chat-panel-fragment.xml`, `view/configuration/AiConfigurationView.java`, `orchestration/DefaultChatServiceImpl.java`, `orchestration/ChatService.java`, `orchestration/ChatClientFactory.java`, `orchestration/AiParametersResolver.java`, `orchestration/BaselineContextProvider.java`, `orchestration/StreamingEvent.java`, `orchestration/AiAgentDefaultsProperties.java`, `audit/AuditWriter.java`, `audit/AuditAdvisor.java`, `audit/ToolCallbackAuditDecorator.java`, `audit/AuditFieldHasher.java` (referenced), `tools/AgentToolCallbacks.java`, `tools/mutation/BuiltInMutationTools.java`, `tools/mutation/MutationAuthorizationService.java`, `tools/mutation/MutationAttributeBinder.java`, `tools/mutation/RelatedWriteMetadataResolver.java`, `tools/mutation/MutationCommitCoordinator.java`, `tools/mutation/AiAgentMutationProperties.java`, `exposure/LlmExposurePolicy.java`, `exposure/LlmExposureRuleRepository.java`, `exposure/AiExposureRuleEntityListener.java`, `rag/RetrievalFilterBuilder.java`, `entity/AiAuditEvent.java`, `entity/AiParameters.java`, `parameters/AiParametersBody.java`, `src/main/resources/com/vn/agent/module.properties`, `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent-starter/ai-agent-starter.gradle`, `ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`, `jmix-app/src/main/resources/application.properties`. — HIGH confidence.
- Spring AI 1.1.4 audio transcription API (`OpenAiAudioApi`, `OpenAiAudioTranscriptionModel`, `OpenAiAudioTranscriptionOptions`, `spring.ai.openai.audio.transcription.*` properties): Context7 `/websites/spring_io_spring-ai_reference` → `docs.spring.io/spring-ai/reference/api/audio/transcriptions/openai-transcriptions.html`. — MEDIUM-HIGH confidence (milestone release; verify exact builder method names at implementation time via Context7).
- Project planning docs: `.planning/PROJECT.md`, `.planning/ROADMAP.md` (Backlog 999.1 / 999.2 + cross-cutting STT constraints), `.planning/MILESTONES.md`, `.planning/STATE.md` (Hard Build-Order, Accumulated Context → Decisions). — HIGH confidence.

---
*Architecture research for: v1.2 of the Jmix AI agent add-on (subsequent-milestone integration)*
*Researched: 2026-05-11*
