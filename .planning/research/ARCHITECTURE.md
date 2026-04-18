# Architecture Research — Jmix AI Copilot (ai-agent add-on)

**Domain:** Metadata-first enterprise AI copilot add-on for Jmix 2.8 (Spring Boot 3 + Vaadin Flow)
**Researched:** 2026-04-18
**Confidence:** HIGH for Spring AI primitives (verified via Context7 against `/spring-projects/spring-ai`) and Jmix module patterns (verified against codebase + CLAUDE.md). MEDIUM on exact observability surface of Spring AI 2.0.0-M4 (milestone-moving target; final APIs may rename).

---

## 1. Principles That Shape Everything

1. **Metadata-first, not code-generation-first.** The agent learns the host's schema from `io.jmix.core.Metadata` at runtime; no codegen step, no host-side tool classes required for the base case.
2. **DataManager is the only path to host data.** Security, attribute policies, row-level policies all live there (`CLAUDE.md` forbids `EntityManager`). Tools wrap `DataManager`, never ORM directly.
3. **Spring AI primitives, not abstractions over them.** `ChatClient`, advisors, `VectorStore`, `ChatMemory`, `ToolCallback` are used as-is. A thin add-on-owned layer only does Jmix wiring (policy, audit, entity persistence, views).
4. **Two layers of security, stacked.** Jmix roles/policies are the authoritative enforcement. `EntityExposurePolicy` is an additional whitelist filter on top — it can only narrow visibility, never widen it.
5. **Functional core vs UI shell separation.** All SPIs and services live in `ai-agent` (functional, no Flow UI). Flow UI views live in `ai-agent-flowui`. Headless hosts (pure REST) can consume the copilot without Vaadin.
6. **Read-only v1.** Six generic read tools ship enabled. Mutation tool framework is scaffolded but disabled.

---

## 2. System Overview (Layered)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Host Jmix App (jmix-app/)                          │
│   Customer, Order entities · menu · roles · liquibase · LoginView         │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                            implementation
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                ai-agent-flowui-starter (auto-config)                      │
│                ai-agent-flowui  (Flow UI views module)                    │
│  ChatView · ConversationsListView · ParametersView · KnowledgeBaseView ·  │
│  ToolCallAuditListView · ExposurePolicyView                               │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                           @JmixModule dependsOn
                                    ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    ai-agent-starter (auto-config)                         │
│                    ai-agent  (functional module — HEADLESS)               │
│                                                                            │
│   ┌─────────── Orchestration Layer ────────────┐                          │
│   │  ChatService  · ConversationService        │                          │
│   │  ChatClientFactory (builds per-request)    │                          │
│   └─────────────────────────────────────────────┘                          │
│                       │                                                    │
│   ┌─────── Spring AI Advisor Chain (per request) ────────────────┐        │
│   │  MessageChatMemoryAdvisor (ORDER+200)                          │       │
│   │  RetrievalAugmentationAdvisor / QuestionAnswerAdvisor (+250)   │       │
│   │  ToolCallAdvisor (+300, internal history disabled)             │       │
│   │  AuditAdvisor  (around-chain; captures tool calls)             │       │
│   │  StructuredOutputValidationAdvisor (optional, for .entity()  ) │       │
│   └────────────────────────────────────────────────────────────────┘      │
│                       │                                                    │
│   ┌─── Metadata-First Tool Stack ──────┐  ┌──── RAG Stack ────────────┐   │
│   │ MetamodelScanner                    │  │ DocumentReaders (Tika/   │   │
│   │   → AiSchema                        │  │   PDF/MD/TXT/HTML)       │   │
│   │ EntityExposurePolicy  (SPI)         │  │ TokenTextSplitter        │   │
│   │ ToolGenerator                       │  │ EmbeddingModel           │   │
│   │   → 6 generic ToolCallbacks         │  │ PgVectorStore            │   │
│   │ ToolRegistry (generated + host @Tool)│ │ VectorStoreDocumentRetriever│ │
│   │ ToolGuard  (SPI — veto pre-exec)    │  │ CustomIngester (SPI)     │   │
│   │ DataManagerToolExecutor             │  │ IngesterManager          │   │
│   └──────────────────────────────────────┘  └──────────────────────────┘   │
│                       │                                                    │
│   ┌──────── Persistence Layer (Jmix entities via DataManager) ────────┐   │
│   │  AiConversation · AiMessage · AiAgentParameters (profile) ·        │   │
│   │  AiKnowledgeDocument · AiToolCallAudit · AiExposureRule            │   │
│   └────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
                                    │
                      ┌─────────────┴──────────────┐
                      ▼                            ▼
            ┌──────────────────┐        ┌──────────────────────┐
            │ Host RDB         │        │  Vector Store (pgvector)│
            │ (Postgres/HSQL)  │        │  Spring AI table schema │
            │ · Jmix data      │        │                         │
            │ · Ai* entities   │        │                         │
            │ · JDBC ChatMemory│        │                         │
            └──────────────────┘        └──────────────────────┘
                                    │
                                    ▼
                            LLM Provider (OpenRouter / OpenAI-compatible)
```

---

## 3. Component Responsibilities

| # | Component | Module | Responsibility | Key Dependencies |
|---|-----------|--------|----------------|------------------|
| C1 | **MetamodelScanner** | `ai-agent` | On `ApplicationReadyEvent`, walk `Metadata.getSession().getClasses()`, extract `MetaClass`/`MetaProperty`/relations into in-memory `AiSchema` (name, caption, attributes, types, ranges, associations). Rebuild on explicit admin action only (metamodel is immutable at runtime). | `io.jmix.core.Metadata` |
| C2 | **EntityExposurePolicy (SPI)** | `ai-agent` | Decide which metaclasses and which attributes the agent is allowed to see/query. Composed of (a) default policy (all non-`@SystemLevel` entities), (b) property-backed allow/deny lists (`AiExposureRule` entity), (c) host-supplied `EntityExposurePolicy` beans (chained). | C1, `AiExposureRule` |
| C3 | **ToolGenerator** | `ai-agent` | Convert `AiSchema` (already filtered by C2) → six generic `ToolCallback` instances built via `MethodToolCallback.Builder` with hand-authored `ToolDefinition` (name + description + JSON schema). | C1, C2, Spring AI `MethodToolCallback` |
| C4 | **ToolRegistry** | `ai-agent` | Merge (generated tools from C3) + (host-contributed `@Tool` beans via `ToolCallbacks.from(bean)`) + (programmatic `ToolCallback` beans). Deduplicate by name. Exposes `ToolCallback[]` per active `AiAgentParameters` profile (which can disable specific tools). | C3, Spring bean context |
| C5 | **ToolGuard (SPI)** | `ai-agent` | `boolean allow(ToolCall call, Authentication auth)` — vetoed calls throw → captured by audit advisor with status=BLOCKED. Default impl = allow-all. | none |
| C6 | **DataManagerToolExecutor** | `ai-agent` | The actual body of the six generic tools. Translates tool args (`entityName`, `conditions`, `fetchPlan`, `page`) into `DataManager.load(MetaClass).query(...)`. Runs inside the caller's `SecurityContext` so Jmix security applies. | `DataManager`, `FetchPlans` |
| C7 | **ChatClientFactory** | `ai-agent` | Per-request: look up active `AiAgentParameters` (or per-conversation override), build `ChatClient.builder(chatModel).defaultSystem(...).defaultAdvisors(C8, C10, C12, C13).build()`. Factory is a singleton; the *builder* produces per-request clients because `defaultOptions` (temperature, model) may differ per profile. | C8–C13 beans, `ChatModel` |
| C8 | **MessageChatMemoryAdvisor** | Spring AI built-in, wired in `ai-agent` | Pulls history from `JdbcChatMemoryRepository` keyed by `conversationId`. `advisorOrder = HIGHEST_PRECEDENCE + 200` (runs before tool advisor). | `JdbcChatMemoryRepository` |
| C9 | **ConversationProjector** | `ai-agent` | Mirrors every `UserMessage`/`AssistantMessage`/`ToolResponseMessage` written to `JdbcChatMemoryRepository` into Jmix-owned `AiConversation` + `AiMessage` entities via `DataManager`. Implemented as a `ChatMemory` *decorator* wrapping the JDBC repo — not a duplicate write path. Jmix entities are the queryable, secured, UI-bound projection; Spring AI repo is the raw memory the LLM sees. | C8 |
| C10 | **RetrievalAugmentationAdvisor** | Spring AI built-in, wired in `ai-agent` | `VectorStoreDocumentRetriever` (threshold configurable per profile). `advisorOrder = HIGHEST_PRECEDENCE + 250`. Optional reranker hooks in as a `DocumentPostProcessor` (Spring AI 2.x) — not shipped in v1, but SPI exposed. | `PgVectorStore` |
| C11 | **IngesterManager + CustomIngester (SPI)** | `ai-agent` | Orchestrates `DocumentReader → TokenTextSplitter → VectorStore.add(...)` for: (a) admin file uploads (default `TikaDocumentReader` covers PDF/MD/TXT/HTML via Apache Tika), (b) host-provided `CustomIngester` beans for bespoke sources. Tracks status in `AiKnowledgeDocument`. | `TikaDocumentReader`, `TokenTextSplitter`, `VectorStore` |
| C12 | **ToolCallAdvisor** | Spring AI built-in, wired in `ai-agent` | Configured with `.toolCallingManager(tcm).disableInternalConversationHistory().advisorOrder(HIGHEST_PRECEDENCE+300)`. Internal history is disabled so C8 owns history. | `ToolCallingManager` |
| C13 | **AuditAdvisor** | `ai-agent` | Around-advice on the advisor chain. Captures: conversation id, user, prompt, resolved tool calls (name + args + result + latency + status), final response, token usage. Persists as `AiToolCallAudit` entity via `DataManager`. Fires `AuditListener` SPI after persist (Slack/SIEM/metrics). | `DataManager`, `AuditListener` SPI |
| C14 | **StructuredOutputValidationAdvisor (optional)** | `ai-agent` | Only added to the chain when a `ChatService.ask(prompt, Class<T>)` overload is called. Uses `BeanOutputConverter<T>.getJsonSchema()` → validates LLM output → retries on failure (bounded). Degrades gracefully when model lacks native structured output. | `BeanOutputConverter` |
| C15 | **ChatService** | `ai-agent` | Primary public API. `ChatResponse send(conversationId, userMessage)` and `<T> T send(conversationId, userMessage, Class<T>)`. Owns transaction boundaries around conversation projection + audit. | C7, C8, C13 |
| C16 | **ConversationService** | `ai-agent` | CRUD over `AiConversation`/`AiMessage` via `DataManager`; used by UI replay view. Read-only toward Spring AI ChatMemory (no writes from here — projector owns that direction). | `DataManager` |
| C17 | **ParametersService** | `ai-agent` | Manages `AiAgentParameters` profiles (model, temperature, system prompt, enabled-tools list, RAG threshold). One marked active; per-conversation override supported via `ChatService` arg. Defaults loaded from `default-params.yaml` classpath resource. | `DataManager` |
| C18 | **Flow UI views** | `ai-agent-flowui` | Standard Jmix views (XML + controller) for each admin surface. Pure presentation — delegates to services C15–C17 + `AiToolCallAudit` CRUD. | Jmix FlowUI, `com.vn.agent.*` services |
| C19 | **Auto-configuration** | `ai-agent-starter`, `ai-agent-flowui-starter` | `@AutoConfiguration` classes registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; conditional on `ChatModel`, `EmbeddingModel`, `VectorStore` presence. | Spring Boot 3 AutoConfig |

---

## 4. SPI Interface Sketches

All SPIs live in `ai-agent` (functional module), package `com.vn.agent.spi`, so headless hosts can implement without pulling Flow UI.

```java
// C2 — controls what the agent SEES in the metamodel.
public interface EntityExposurePolicy {
    /** Return false to hide the metaclass entirely. */
    boolean isVisible(MetaClass metaClass);
    /** Return false to hide a single attribute (column/association). */
    boolean isVisible(MetaProperty metaProperty);
    /** Higher = applied later, can override earlier policies. */
    default int getOrder() { return 0; }
}

// C5 — vetoes tool calls before execution.
public interface ToolGuard {
    /** Throw ToolVetoedException to block; return silently to allow. */
    void check(ToolInvocation invocation, Authentication auth) throws ToolVetoedException;
    default int getOrder() { return 0; }
}

// Host-contributed extra tools are just Spring beans with @Tool methods;
// picked up via ToolCallbacks.from(bean) inside C4. No custom SPI needed.

// Host-contributed context injection into system prompt.
public interface PromptContextContributor {
    /** Append text/variables to the system prompt for this request. */
    void contribute(PromptContext ctx);  // ctx has user, conversationId, profile, Map<String,Object> bag
}

// C11 — bespoke ingestion source (e.g., Confluence, S3, a CRM).
public interface CustomIngester {
    String getId();               // stable id, shown in admin UI
    String getDisplayName();
    List<Document> read(IngestionContext ctx);  // returns ready-to-split Documents
}

// C13 — side-channel observability for audit events.
public interface AuditListener {
    void onToolCall(AiToolCallAudit audit);          // after persistence
    void onConversationCompleted(AiConversation c);  // after final assistant message
}
```

**Module placement rule:** every interface above is in `ai-agent`, never in `ai-agent-flowui`. Admin UI screens are just thin presentation over these beans + the Jmix entities they populate.

---

## 5. Data Flow — Primary Query Path

```
[1]  User types in ChatView (ai-agent-flowui)
         │
[2]  ChatView.sendMessage(text) → ChatService.send(conversationId, text)  (ai-agent)
         │
[3]  ChatService loads active AiAgentParameters (or per-conversation override)
         → ChatClientFactory.build(params) returns a per-request ChatClient
         │
[4]  ChatClient.prompt().user(text).advisors(...).call()
         │   (advisor chain executes in order, around-style)
         │
         ├─► [4a] MessageChatMemoryAdvisor  (ORDER+200)
         │       loads prior turns from JdbcChatMemoryRepository by conversationId
         │       ConversationProjector decorator ALSO persists AiMessage rows
         │
         ├─► [4b] RetrievalAugmentationAdvisor  (ORDER+250)
         │       embeds query → VectorStoreDocumentRetriever.retrieve()
         │       → PgVectorStore similarity search → top-k Documents
         │       → injected as grounded context into the prompt
         │
         ├─► [4c] ToolCallAdvisor  (ORDER+300)  ─ model decides to call a tool
         │       ToolCallingManager resolves tool by name via ToolRegistry
         │       → ToolGuard.check(invocation, auth)       (veto pre-exec)
         │       → DataManagerToolExecutor.exec(args)
         │            · resolves MetaClass via Metadata
         │            · checks EntityExposurePolicy (defense in depth)
         │            · builds DataManager.load(meta).query(cond).fetchPlan(fp).list()
         │            · Jmix security runs IN the DataManager call (row/column filter)
         │            · serializes result rows to JSON → back to model
         │
         ├─► [4d] Model generates final answer; may also return structured output
         │       (StructuredOutputValidationAdvisor validates vs BeanOutputConverter schema)
         │
         └─► [4e] AuditAdvisor (around-chain) writes AiToolCallAudit rows via DataManager
                 and fires AuditListener beans (Slack/SIEM/metrics)
         │
[5]  Assistant message returned to ChatService
         ConversationProjector writes AssistantMessage → AiMessage row
         │
[6]  ChatView renders answer + collapsible "Tool calls (3)" panel
         sourced from AiToolCallAudit rows of this turn
```

**Key design note on memory duplication (C9):** Spring AI owns the *raw* `ChatMemory` that feeds the model (`SPRING_AI_CHAT_MEMORY` table from the JDBC starter). Jmix owns the *projected* view (`AiConversation`/`AiMessage`) used by the admin UI, audit queries, security scoping, and i18n-friendly rendering. The projector wraps `JdbcChatMemoryRepository` as a decorator so there is exactly one write per message.

---

## 6. Module Boundaries & Build Artifacts

```
ai-agent/
├── ai-agent/                    (functional, HEADLESS — no Flow UI deps)
│   com.vn.agent
│   ├── AIConfiguration           @JmixModule(dependsOn = {EclipselinkConfiguration.class})
│   ├── entity/                   AiConversation, AiMessage, AiAgentParameters,
│   │                             AiKnowledgeDocument, AiToolCallAudit, AiExposureRule
│   ├── security/                 AiAgentUserRole, AiAgentAdminRole (ResourceRoles)
│   ├── spi/                      (the six SPIs from §4)
│   ├── metamodel/                MetamodelScanner, AiSchema, DefaultEntityExposurePolicy
│   ├── tool/                     ToolGenerator, ToolRegistry, DataManagerToolExecutor,
│   │                             six generic Tool classes, ToolGuardChain
│   ├── chat/                     ChatService, ChatClientFactory, AuditAdvisor,
│   │                             ConversationProjector, PromptContextContributorChain
│   ├── rag/                      IngesterManager, UploadIngester, CustomIngester contract
│   ├── params/                   ParametersService, DefaultParamsLoader (YAML)
│   └── audit/                    AuditService, AuditListenerChain
│   resources/com/vn/agent/
│   ├── module.properties, messages.properties
│   ├── liquibase/changelog.xml + changelog/ (Ai* tables)
│   └── init/default-params.yaml
│
├── ai-agent-starter/            (auto-config for functional module)
│   com.vn.autoconfigure.agent.AIAutoConfiguration
│   @ConditionalOnBean(ChatModel.class) @Import(AIConfiguration.class)
│
├── ai-agent-flowui/             (Flow UI views, depends on ai-agent)
│   com.vn.agent.flowui
│   ├── AIFlowUIConfiguration    @JmixModule(dependsOn = {AIConfiguration.class,
│   │                                                      FlowuiConfiguration.class})
│   └── view/
│       ├── chat/          ChatView (+ XML)
│       ├── conversations/ ConversationListView, ConversationDetailView
│       ├── parameters/    ParametersListView, ParametersDetailView
│       ├── knowledgebase/ KnowledgeBaseView (upload/list/delete)
│       ├── audit/         ToolCallAuditListView
│       └── exposure/      ExposureRuleListView
│   resources/com/vn/agent/flowui/
│   ├── module.properties (menu-config path)
│   ├── menu.xml           (AI top-level + child items)
│   ├── messages_en.properties, messages_vi.properties
│   └── view/...           (XML descriptors mirror Java layout)
│
└── ai-agent-flowui-starter/     (auto-config for UI module)
    com.vn.autoconfigure.agent.flowui.AIFlowUIAutoConfiguration
    @ConditionalOnClass(FlowuiConfiguration.class) @Import(AIFlowUIConfiguration.class)
```

**Why split functional vs flowui:**
1. A REST-only Jmix host can depend on `ai-agent-starter` and expose its own endpoints over `ChatService` — no Vaadin cost.
2. Swapping the UI (e.g., React on the frontend, calling a REST adapter) doesn't fork the core.
3. Keeps the SPI surface free of UI concerns; host implementers don't need Flow knowledge to add tools/policies.

---

## 7. Architectural Patterns

### Pattern 1 — Metadata-First Tool Generation (instead of per-entity tools)

**What:** Six generic tools parameterized by entity name, not one tool per entity.
**Why:** Host schemas vary wildly; N entities × 4 ops = explosion of tool schemas that blow the model's context window. Six fixed tool shapes keep the prompt small and discoverable (`list_entities` returns the inventory the model then uses).
**Trade-off:** Generic JSON-shaped arguments require the model to plan two-step calls (`list_entities` → `find_records`). Worth it for scalability.

```java
// Sketch of find_records tool (C6)
@Component
class FindRecordsTool {
    private final DataManager dataManager;
    private final Metadata metadata;
    private final EntityExposurePolicy exposure;

    @Tool(name = "find_records",
          description = "Find records of the given entity matching conditions. "
                       + "Respects entity, attribute, and row-level Jmix security.")
    public List<Map<String,Object>> find(
        @ToolParam(description="Entity name, e.g., 'sample_Customer'") String entityName,
        @ToolParam(description="JSON-ish conditions, e.g. {\"status\":\"ACTIVE\"}") Map<String,Object> conditions,
        @ToolParam(description="Max rows (<=50)") Integer limit) {

        MetaClass mc = metadata.getSession().getClass(entityName);
        if (!exposure.isVisible(mc)) throw new AccessDeniedException(entityName);

        return dataManager.load(mc.getJavaClass())
            .query(buildJpql(mc, conditions))
            .parameters(conditions)
            .fetchPlan(fpBuilder -> exposure.visibleAttributes(mc).forEach(fpBuilder::add))
            .maxResults(Math.min(limit == null ? 20 : limit, 50))
            .list().stream()
            .map(this::toMap)
            .toList();
    }
}
```

### Pattern 2 — Advisor Chain as the Composition Unit

**What:** Every cross-cutting concern (memory, RAG, tools, audit, validation) is a Spring AI `Advisor`, not a hand-rolled interceptor.
**Why:** Spring AI 2.x expressly designs tool+memory interaction through `advisorOrder` + `ToolCallAdvisor.disableInternalConversationHistory()`. Fighting that means subtle bugs (duplicate history, tool calls not visible to memory advisor).
**Order (verified from Spring AI docs):**
- `MessageChatMemoryAdvisor` at `HIGHEST_PRECEDENCE + 200`
- RAG advisor at `HIGHEST_PRECEDENCE + 250`
- `ToolCallAdvisor` at `HIGHEST_PRECEDENCE + 300` with `disableInternalConversationHistory()`
- `AuditAdvisor` at lowest precedence so it observes all others

### Pattern 3 — ChatMemory Decorator for Dual-Write without Dual-Source-of-Truth

**What:** Wrap `JdbcChatMemoryRepository` with `ConversationProjector` implementing `ChatMemoryRepository`; delegate to the JDBC repo, and on every `save(...)` fan out a `DataManager` write into `AiMessage`.
**Why:** Keeps Spring AI's view of memory authoritative (it drives the LLM), while giving Jmix a first-class entity for UI/security/audit. No background sync job, no lag.
**Trade-off:** Two rows written per message (one in Spring AI table, one in `AiMessage`). Acceptable — chat volume is low relative to business data.

### Pattern 4 — Exposure Policy as a Chain of `EntityExposurePolicy` Beans

**What:** Jmix auto-collects all `EntityExposurePolicy` beans, orders by `getOrder()`, evaluates AND-style (any `false` wins). The default bean hides `@SystemLevel`, the admin-configured rule bean consults `AiExposureRule` entities, host-supplied beans can add business rules.
**Why:** Mirrors how Jmix composes role policies; feels native to Jmix developers. Admin-editable rules + code-level rules coexist.

### Pattern 5 — Parameters-as-Entity with Per-Conversation Override

**What:** `AiAgentParameters` is a Jmix entity (active flag + JSON/YAML body). `ChatService.send()` accepts an optional `parametersOverrideId` param for A/B testing. `ChatClientFactory` merges override on top of active.
**Why:** Matches reference `jmix-ai-backend` UX; admins iterate on prompts/temp/tool subsets without redeploy.

---

## 8. Build Order / Dependency Graph

Components must be built in dependency order. Arrow = "blocks".

```
                     [Ai* JPA entities + Liquibase]   ← gate; nothing persists until ready
                                 │
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
 [MetamodelScanner +      [ParametersService +    [AuditService +
  ExposurePolicy]          default-params.yaml]    AuditListener SPI]
          │                      │                      │
          ▼                      │                      │
   [ToolGenerator +               │                      │
    6 generic tools +              │                      │
    ToolGuard SPI +                │                      │
    DataManagerToolExecutor]       │                      │
          │                        │                      │
          └───────────┬────────────┘                      │
                      ▼                                    │
            [ToolRegistry]                                 │
                      │                                    │
         ┌────────────┼─────────────┐                      │
         │            │             │                      │
         ▼            ▼             ▼                      │
 [JdbcChatMemory][PgVectorStore][ChatModel bean]           │
 [Repository +  ][+ EmbeddingModel]    (provider cfg)      │
  ConversationProjector]                                    │
         │            │             │                      │
         │            ▼             │                      │
         │   [IngesterManager +     │                      │
         │    TikaDocumentReader +  │                      │
         │    TokenTextSplitter +   │                      │
         │    AiKnowledgeDocument]  │                      │
         │            │             │                      │
         └────────────┴─────────────┴──────────────────────┘
                      │
                      ▼
            [ChatClientFactory assembles advisor chain]
                      │
                      ▼
            [ChatService + ConversationService]  ← functional module feature-complete
                      │
                      ▼
            [ai-agent-flowui views:
             Chat, Conversations, Parameters, KB, Audit, Exposure]
                      │
                      ▼
            [Auto-configuration starters + Ai*Role resource roles
             + menu.xml wiring + messages_*.properties]
                      │
                      ▼
            [Integration tests in jmix-app (Customer/Order harness)]
```

**Phased interpretation for the roadmap:**

| Phase | Components | Why this order |
|-------|------------|----------------|
| **P1 Foundations** | Ai entities, Liquibase, roles, `AIConfiguration` skeleton, starters | Nothing persists or secures without these; cheap to get right first. |
| **P2 Metadata & Tools** | MetamodelScanner, ExposurePolicy SPI + default, ToolGenerator, 6 tools, ToolGuard SPI, DataManagerToolExecutor, ToolRegistry | Heart of "metadata-first". Unit-testable headlessly against the Customer/Order harness without any LLM. |
| **P3 Spring AI Wiring** | ChatModel provider (OpenRouter), EmbeddingModel, ChatClientFactory, MessageChatMemoryAdvisor + JDBC repo, ConversationProjector, ToolCallAdvisor, AuditAdvisor, ChatService | First end-to-end LLM path. Live-model tests opt-in via `@Tag("live")`. |
| **P4 RAG** | PgVectorStore config, IngesterManager, TikaDocumentReader path, TokenTextSplitter, CustomIngester SPI, RetrievalAugmentationAdvisor, AiKnowledgeDocument entity | Depends on P3 to have the advisor chain in place; can be mocked with an in-memory vector store in tests. |
| **P5 Parameters & Structured Output** | ParametersService, per-conversation override, StructuredOutputValidationAdvisor, PromptContextContributor SPI | Refinement layer; depends on P3. |
| **P6 Flow UI** | ai-agent-flowui module + starter, all six views, menu.xml, messages_*.properties, AiAgentAdminRole menu/view policies | Last — every screen depends on services from P1–P5. |
| **P7 Integration hardening** | jmix-app integration tests over Customer/Order, semantic-similarity assertions, audit queries, performance smoke | Requires full stack. |

---

## 9. Security Layering (Stacked Defense)

| Layer | What it enforces | Where it runs | Can it be bypassed by the agent? |
|-------|------------------|---------------|----------------------------------|
| **L1 — Jmix authentication** | Who the user is; `Authentication` on thread | Spring Security filter chain | No. Chat endpoint is behind the standard Jmix security config. |
| **L2 — Jmix entity/attribute/row policies** | What rows/columns the user can read | Inside `DataManager.load(...).list()` via EclipseLink session events | No. Tool executor calls DataManager, not EntityManager. |
| **L3 — EntityExposurePolicy** | What metaclasses/attributes the agent can even *plan* to query | At tool schema generation (C3) AND defensively at tool execution (C6) | No — filtered out of the tool's JSON schema so the model cannot name hidden entities, and re-checked on execution. |
| **L4 — ToolGuard** | Business-rule veto on resolved calls (rate limits, tenant bounds, forbidden value patterns) | Before `DataManagerToolExecutor` runs | No — guard runs inside the request thread with the user's auth. |
| **L5 — AuditAdvisor** | Observability / forensic | After every tool call and final response | N/A — monitoring, not enforcement. Cannot be silently disabled (per PROJECT.md). |

**Interaction rule:** L3 can only *narrow* L2. A user with `FullAccessRole` whose tenant policy hides `CreditLimit` from the agent sees all rows of `Customer` via the business UI but the agent sees `Customer` without the `creditLimit` column. Removing the exposure rule does not grant L2 access the user didn't already have.

---

## 10. Integration Points

### External Services

| Service | Integration | Notes |
|---------|-------------|-------|
| OpenRouter / OpenAI-compatible LLM | `spring-ai-starter-model-openai` with `spring.ai.openai.base-url=https://openrouter.ai/api/v1` | Reuses pattern from `traffic-law-chatbot` reference. Per-request model selection via `ChatOptions` populated from `AiAgentParameters`. |
| pgvector | `spring-ai-starter-vector-store-pgvector` | Dev can use embedded postgres via Testcontainers; v1 default is a Postgres the host already runs, or HSQLDB for simple dev with `SimpleVectorStore` fallback. |
| JDBC ChatMemory | `spring-ai-starter-model-chat-memory-repository-jdbc` | `spring.ai.chat.memory.repository.jdbc.initialize-schema=never` — we manage schema via Liquibase to match Jmix conventions. |
| Apache Tika (via Spring AI) | `TikaDocumentReader` | Covers PDF/MD/TXT/HTML/DOCX with zero per-format code. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `ai-agent-flowui` → `ai-agent` | Direct Spring bean calls (`ChatService`, `ConversationService`, etc.) | Flow UI views must never call `ChatClient` directly — always through `ChatService` so audit + projection stay centralized. |
| `ai-agent` → Spring AI | Composition via advisor chain + `ToolCallback` | Never extend or wrap `ChatClient`; only configure it. |
| Host app → `ai-agent` SPIs | `@Component` beans of `EntityExposurePolicy`, `ToolGuard`, `PromptContextContributor`, `CustomIngester`, `AuditListener`, plus `@Tool` beans | Picked up by default `@ComponentScan("com.example.host")` — no registration boilerplate. |

---

## 11. Anti-Patterns (Specific to This Add-on)

### AP1 — Wrapping `VectorStore`, `ChatClient`, or `ChatModel` in our own interface
**Why wrong:** Spring AI 2.x still moves; wrappers turn every point release into a porting task. PROJECT.md explicitly forbids custom vector store abstractions.
**Do instead:** Expose and inject the Spring AI primitive directly; only add Jmix-owned wiring (audit, projection, exposure).

### AP2 — Per-entity tool generation (one tool per metaclass)
**Why wrong:** Hosts with 200 entities blow the model's tool list out of context; names collide across modules; every metamodel change invalidates tool schemas.
**Do instead:** Six generic parametric tools (Pattern 1).

### AP3 — Bypassing `DataManager` in the tool executor for "performance"
**Why wrong:** Strips Jmix entity/attribute/row security. Turns the agent into a privilege-escalation vector.
**Do instead:** Always `DataManager`; use fetch plans to shape payloads.

### AP4 — Letting Flow UI views drive LLM calls directly
**Why wrong:** Audit, projection, and transaction boundaries fragment across UI controllers; headless hosts get nothing.
**Do instead:** UI always goes through `ChatService`. Controllers are thin.

### AP5 — Writing `AiMessage` asynchronously after Spring AI writes memory
**Why wrong:** Creates a window where the UI shows stale history; doubles failure modes.
**Do instead:** `ConversationProjector` decorator synchronously writes both in the same transaction.

### AP6 — Running ingestion in the request thread when a user uploads a doc
**Why wrong:** Large PDFs with Tika + embedding can take minutes; blocks the Vaadin UI session.
**Do instead:** `IngesterManager.submit(...)` queues work on a bounded `TaskExecutor`; `AiKnowledgeDocument.status` (`QUEUED`/`INGESTING`/`READY`/`FAILED`) drives UI polling.

### AP7 — Enabling mutation tools in v1 "just in case"
**Why wrong:** A read-only surface is already powerful; mutation requires dry-run + confirm + per-entity policy, all of which are v2 work.
**Do instead:** Scaffold the SPI (`MutationTool` marker + disabled-by-default flag). Ship nothing enabled.

---

## 12. Scaling Considerations

| Scale | What to do |
|-------|-----------|
| Single-tenant, <50 users | Default: one Postgres for both Jmix data and pgvector. One JVM. `TaskExecutor` with 2 threads for ingestion. |
| 50–500 users | Split pgvector to its own Postgres; keep chat memory with business DB (for audit joinability). Tune HNSW index. |
| 500+ users or multi-tenant | Separate `ChatModel` rate-limit pool per tenant (tenant passed via `ToolContext`). Consider async streaming responses (Spring AI supports streaming via `ChatClient.stream()`). Keep ingestion on a dedicated worker service — shares DB, not JVM. |

### First Bottleneck to Watch
LLM provider rate limits, not Jmix/DataManager. A metadata-first read tool is cheap relative to a 4k-token model call. Surface token-usage metrics from `AuditAdvisor` early.

---

## 13. Key Design Decisions Driven by Spring AI 2.x Primitives

| Decision | Spring AI 2.x reason |
|----------|----------------------|
| Advisor ordering with explicit `advisorOrder` | Verified: docs show `HIGHEST_PRECEDENCE + 200/250/300` pattern for memory → RAG → tools. |
| `ToolCallAdvisor.disableInternalConversationHistory()` | Verified: when using external `ChatMemory`, internal history must be disabled to avoid duplicate turns. |
| `JdbcChatMemoryRepository` with `initialize-schema=never` | Verified: starter auto-configures, but schema should be managed by Liquibase to match Jmix ops conventions. |
| `RetrievalAugmentationAdvisor` over `QuestionAnswerAdvisor` | Verified: 2.x prefers the modular-RAG flavor; pluggable `DocumentRetriever`/`DocumentPostProcessor` gives us a rerank hook without forking. |
| `ToolCallbacks.from(bean)` for host `@Tool` beans | Verified: standard way to convert a bean with `@Tool` methods into `ToolCallback[]` for the registry. |
| `ToolContext` to pass `userId`, `tenantId`, `conversationId` into tools | Verified: standard pattern; avoids static holders and works with advisor chain. |
| `BeanOutputConverter.getJsonSchema()` + `.entity(Class)` | Verified: 2.x fluent path for structured output; `StructuredOutputValidationAdvisor` retries on validation failure. |
| Per-request `ChatClient` built from builder, not a shared singleton | `AiAgentParameters` changes `defaultOptions` and system prompt per conversation — singleton can't express that. Builder is cheap. |

---

## 14. Sources

- **Context7 `/spring-projects/spring-ai`** (HIGH confidence):
  - `MessageChatMemoryAdvisor`, `ToolCallAdvisor`, advisor ordering pattern (chatclient.adoc, advisors-recursive.adoc, tools.adoc)
  - `JdbcChatMemoryRepository` starter + schema initialization properties (chat-memory.adoc)
  - `RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever`, `QuestionAnswerAdvisor` (retrieval-augmented-generation.adoc, advisors.adoc)
  - `PgVectorStore` builder, `EmbeddingModel` (vectordbs/pgvector.adoc, embeddings.adoc)
  - `ToolCallback`, `MethodToolCallback.Builder`, `ToolCallbacks.from`, `ToolCallbackResolver`, `ToolContext` (tools.adoc)
  - `TikaDocumentReader`, `TokenTextSplitter`, `ParagraphPdfDocumentReader` (etl-pipeline.adoc)
  - `BeanOutputConverter.getJsonSchema()` + `.entity(Class)` (structured-output-converter.adoc)
- **`D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/README.md`** (HIGH confidence on admin-UI shape, MEDIUM on Spring AI 1.1 vs 2.0 differences — we verified 2.0 specifics above): `ChatImpl`, ingesters pattern, `Parameters` entity, answer checks, admin views.
- **Local codebase files** (HIGH confidence):
  - `.planning/PROJECT.md` — constraints, scope, safety posture
  - `.planning/codebase/ARCHITECTURE.md` — current Jmix module composition (AIConfiguration + starter auto-import)
  - `.planning/codebase/STRUCTURE.md` — exact module layout under `ai-agent/` and `jmix-app/`
  - `CLAUDE.md` — DataManager-only rule, entity patterns, view conventions, msg:// i18n requirement
- **Reference pattern** (training + local): `D:/ai/traffic-law-chatbot` — OpenRouter via OpenAI starter with `base-url` override.

### Gaps / Open Questions (flag for later phases)

- **Streaming responses in Vaadin Flow + advisor chain with tools:** `ChatClient.stream()` behavior during tool calls needs verification against 2.0.0-M4 release notes; may force non-streaming for turns containing tool calls in v1.
- **`ToolCallingManager` observability hook:** Spring AI 2.x has a listener/metrics surface; exact interface name is still moving between M-releases. AuditAdvisor as an around-advisor is the safe default; refine once the observability contract stabilizes.
- **pgvector index strategy at 10M+ chunks:** HNSW vs IVFFlat choice — out of scope for v1 but worth revisiting before multi-tenant rollout.
- **Per-tenant `VectorStore` isolation:** Metadata filter `tenant == 'X'` via `SearchRequest.filterExpression` is the lean path; dedicated stores-per-tenant is a v2 discussion.

---
*Architecture research for: Jmix AI Copilot (metadata-first, Spring AI 2.0.0-M4)*
*Researched: 2026-04-18*
