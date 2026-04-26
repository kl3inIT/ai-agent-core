# Phase 5: rag-layer - Pattern Map

**Mapped:** 2026-04-20
**Files analyzed:** 22 new + 3 modified
**Analogs found:** 25 / 25

All new Phase 5 files have strong in-repo analogs in the `ai-agent` module. The two dominant analog clusters are (a) Phase 4 writer/advisor/factory patterns (`AuditWriter`, `AuditAdvisor`, `ChatClientFactory`, `DefaultChatServiceImpl`) for transactional-boundary control, advisor composition, per-request advisor params, and bean registration; and (b) Phase 2 entity/SPI/changelog scaffolding (`AiKnowledgeDocument`, `CustomIngester`, `SpiDefaultsAutoConfiguration`, `changelog.xml`, `messages*.properties`) for i18n, DDL inclusion, and `@ConditionalOnMissingBean` SPI defaults.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `com/vn/autoconfigure/agent/AIAutoConfiguration.java` (MODIFIED) | autoconfig | boot | self (Phase 4) | exact |
| `com/vn/agent/orchestration/ChatClientFactory.java` (MODIFIED) | factory | boot | self (Phase 4) | exact |
| `com/vn/agent/DefaultChatServiceImpl.java` (MODIFIED) | service | request-response | self (Phase 4) | exact |
| `com/vn/agent/rag/RetrievalFilterBuilder.java` | service (pure-function bean) | request-response | `BaselineContextProvider` | role-match |
| `com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java` | factory | boot | `ChatClientFactory` | exact |
| `com/vn/agent/rag/ingest/AsyncIngestionWorker.java` | service (async worker) | event-driven / pipeline | `AuditWriter` + `ToolCallbackAuditDecorator` | role-match |
| `com/vn/agent/rag/ingest/IngestionStatusWriter.java` | writer (REQUIRES_NEW) | CRUD | `AuditWriter` | exact |
| `com/vn/agent/rag/ingest/CancellationRegistry.java` | utility (concurrent map) | in-memory state | `RunContext` (ThreadLocal) | partial |
| `com/vn/agent/rag/ingest/ChunkMetadata.java` | utility (constants) | — | `AuditAdvisor` constants / `AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT` | role-match |
| `com/vn/agent/rag/service/KnowledgeDocumentUploadService.java` | service | request-response | `ConversationGateway` | role-match |
| `com/vn/agent/rag/service/KnowledgeDocumentService.java` (delete + reingest) | service | CRUD | `ConversationGateway` + `AuditWriter` | role-match |
| `com/vn/agent/rag/service/IngesterManager.java` | service (SPI fan-out) | batch | `AuditListenerDispatcher` | role-match |
| `com/vn/agent/rag/service/UnknownRoleCodeException.java` | exception | — | `ConversationNotFoundException` | exact |
| `com/vn/agent/rag/service/DocumentNotFoundException.java` | exception | — | `ConversationNotFoundException` | exact |
| `com/vn/agent/rag/sample/ClasspathMarkdownIngester.java` | SPI impl | batch | `SpiDefaultsAutoConfiguration.defaultCustomIngester` + `CustomIngester` | exact |
| `com/vn/agent/rag/config/AiAgentRagProperties.java` | properties | config | `AiAgentDefaultsProperties` | exact |
| `com/vn/agent/rag/config/AiAgentEmbeddingProperties.java` | properties | config | `AiAgentDefaultsProperties` | exact |
| `com/vn/agent/rag/config/IngestExecutorConfig.java` | config (TaskExecutor bean) | boot | `AIAutoConfiguration` (bean decl w/ `@ConditionalOnMissingBean`) | role-match |
| `messages.properties` (MODIFIED) | i18n | — | self | exact |
| `messages_vi.properties` (MODIFIED) | i18n | — | self | exact |
| `AbstractRagIntegrationTest.java` | test base | — | (no analog — new Testcontainers base) | no-analog |
| `RetrievalFilterBuilderTest.java` | test (unit) | — | (n/a — pure-fn unit; use Mockito-only like existing unit tests) | role-match |
| `UploadToReadyIntegrationTest.java` | test (integration) | — | Phase 4 integration tests | role-match |
| `AtomicDeleteIntegrationTest.java` | test (integration, `@Tag("live")`) | — | Phase 4 `@Tag("live")` tests | role-match |
| `EmbeddingModelBeanCollisionTest.java` | test (integration) | — | (new; assert via `context.getBeansOfType`) | no-analog |

## Pattern Assignments

### `AIAutoConfiguration.java` (MODIFIED — add EmbeddingModel/VectorStore/Advisor beans)

**Analog:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`

**Imports + class shape** (lines 1-32):
```java
package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.ai.chat.memory.ChatMemory;
// ...
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {
```

**`@ConditionalOnMissingBean` bean pattern** (lines 34-49):
```java
@Bean
@ConditionalOnMissingBean
public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(20)
            .build();
}

@Bean
@ConditionalOnMissingBean(JdbcChatMemoryRepository.class)
public JdbcChatMemoryRepository jdbcChatMemoryRepository(JdbcTemplate jdbcTemplate) {
    return JdbcChatMemoryRepository.builder()
            .jdbcTemplate(jdbcTemplate)
            .build();
}
```

**Apply verbatim for:** `EmbeddingModel` passthrough, `VectorStore` (PgVectorStore.builder), `RetrievalAugmentationAdvisor`. Each declared as `@Bean @ConditionalOnMissingBean`.

---

### `ChatClientFactory.java` (MODIFIED — insert RetrievalAugmentationAdvisor at +250)

**Analog:** self, `com/vn/agent/orchestration/ChatClientFactory.java`

**Advisor wiring pattern** (lines 37-60):
```java
@Bean
public ChatClient defaultChatClient(ChatModel chatModel,
                                    ChatMemory chatMemory,
                                    AuditAdvisor auditAdvisor,
                                    AiParametersResolver parametersResolver,
                                    ToolCallingManager toolCallingManager) {
    // ...
    MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
            .order(Ordered.HIGHEST_PRECEDENCE + 200)
            .build();

    ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .disableMemory()
            .advisorOrder(Ordered.HIGHEST_PRECEDENCE + 300)
            .build();

    return ChatClient.builder(chatModel)
            .defaultSystem(systemPrompt != null ? systemPrompt : AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT)
            .defaultAdvisors(auditAdvisor, memoryAdvisor, toolCallAdvisor)
            .build();
}
```

**Change:** Inject `RetrievalAugmentationAdvisor ragAdvisor` (built at `HIGHEST_PRECEDENCE + 250` via the factory bean below); append to `.defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor)` — inserts between memory (+200) and tool (+300) per Phase 4 D-02.

---

### `RetrievalAugmentationAdvisorFactory.java` (NEW factory — advisor, factory, boot)

**Analog:** `ChatClientFactory` — same `@Configuration` + `@Bean` builder idiom.

**Pattern:** Factory `@Bean` method inside `@Configuration` class (not `@Component`), builder-style composition with order constant. Mirror `ChatClientFactory`'s structure:

```java
@Configuration
public class RetrievalAugmentationAdvisorFactory {

    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

    @Bean
    @ConditionalOnMissingBean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore, AiAgentRagProperties props) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(props.similarityThreshold())
                .topK(props.topK())
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .order(ADVISOR_ORDER)
                .build();
    }
}
```

**Critical:** Retriever MUST have NO static `.filterExpression(...)` — pitfall #3 (Section 3 of AI-SPEC). The per-request `FILTER_EXPRESSION` param REPLACES, not AND-s with, a static filter.

---

### `DefaultChatServiceImpl.java` (MODIFIED — add per-request FILTER_EXPRESSION param)

**Analog:** self, `com/vn/agent/DefaultChatServiceImpl.java` lines 86-100

**Per-request advisor param pattern** (lines 86-100):
```java
ChatResponse springResponse = chatClient.prompt()
        .system(composedSystemPrompt)
        .user(message)
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
        .advisors(advisorSpec -> advisorSpec
                .param(ChatMemory.CONVERSATION_ID, convId.toString())
                .param("audit.runId", runId))
        .options(ChatOptions.builder()
                .model(model)
                // ...
                .build())
        .call()
        .chatResponse();
```

**Change:** Inside the `.advisors(advisorSpec -> ...)` lambda, conditionally add:
```java
Filter.Expression filter = retrievalFilterBuilder.buildFor(currentAuthentication);
if (filter != null) {
    advisorSpec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filter);
}
// null == admin bypass (D-06) — do NOT set the param; retriever runs unfiltered.
```

Constructor-inject `RetrievalFilterBuilder` + `CurrentAuthentication`.

---

### `RetrievalFilterBuilder.java` (NEW — pure-function bean, request-response)

**Analog:** `com/vn/agent/orchestration/BaselineContextProvider.java` lines 37-56

**Shape pattern:**
```java
@Component
public class BaselineContextProvider {
    private final CurrentAuthentication currentAuthentication;

    public BaselineContextProvider(CurrentAuthentication currentAuthentication) {
        this.currentAuthentication = currentAuthentication;
    }

    public Map<String, Object> compose(UUID conversationId) {
        // pure function of auth + input
    }
}
```

**Role-extraction pattern** (BaselineContextProvider lines 47-55 + Locale usage):
```java
Set<String> rolesOf(UserDetails user) {
    return user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toCollection(TreeSet::new));
}
```

**Apply to `RetrievalFilterBuilder.buildFor(Authentication auth)`:**
- Constructor-inject `AiAgentRagProperties` and `AiAgentEmbeddingProperties` (for current model name).
- Extract roles via `auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)`.
- Admin-bypass check against `AiAgentAdminRole.CODE` (`"ai-agent-admin"`) — use the constant from `AiAgentAdminRole` (see "Shared Patterns — Role Codes" below).
- Return `null` for admin-bypass; `FilterExpressionBuilder` conjunction otherwise (per AI-SPEC §4 code sample).
- Fail-closed on empty roles: yield a filter that matches nothing (e.g., `b.eq("documentId", "__none__")`).

---

### `IngestionStatusWriter.java` (NEW writer — REQUIRES_NEW, CRUD)

**Analog:** `com/vn/agent/audit/AuditWriter.java` — THE exemplar pattern for Phase 5 (D-14 cites this directly).

**Class header + REQUIRES_NEW methods** (AuditWriter lines 36-80):
```java
@Component
public class AuditWriter {

    private final DataManager dataManager;
    private final Metadata metadata;

    public AuditWriter(DataManager dataManager, Metadata metadata, AuditListenerDispatcher dispatcher) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        // ...
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeChatPre(UUID runId, String userUsername, UUID conversationId, String promptHash) {
        AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
        row.setRunId(runId);
        // ...
        dataManager.save(row);
        return row.getId();
    }
}
```

**Apply to `IngestionStatusWriter`:** Methods `markProcessing(UUID)`, `markReady(UUID)`, `markFailed(UUID, String errorMessage)` — each `@Transactional(propagation = Propagation.REQUIRES_NEW)`; each loads the doc via `dataManager.load(AiKnowledgeDocument.class).id(documentId).one()`, mutates status + timestamps + errorMessage, calls `dataManager.save(...)`. Pitfall: never self-invoke public methods from within this class (Pitfall #3 in `AuditWriter` javadoc line 27-29).

---

### `AsyncIngestionWorker.java` (NEW — @Async, event-driven pipeline)

**Analog hybrid:**
- **Pipeline + writer-delegation:** `com/vn/agent/audit/ToolCallbackAuditDecorator.java` (lines 25-50) — non-transactional orchestrator that delegates REQUIRES_NEW calls to `AuditWriter`.
- **Error handling in finally:** `AuditAdvisor.adviseCall(...)` lines 85-99:

```java
try {
    auditWriter.writeChatPre(runId, userUsername, conversationId, promptHash);
    return chain.nextCall(request);
} catch (Throwable t) {
    errorClass = t.getClass().getName();
    throw t;
} finally {
    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
    try {
        auditWriter.writeChatPost(runId, userUsername, conversationId, latencyMs, errorClass);
    } catch (Throwable t2) {
        log.warn("writeChatPost failed for runId={}", runId, t2);
    }
    RunContext.clear();
}
```

**Apply to `AsyncIngestionWorker.ingest(UUID docId, Resource source, List<String> allowedRoles, String sourceLabel)`:**
- Method annotated `@Async("aiAgentIngestExecutor")`; NO `@Transactional` (Pitfall #7 in AI-SPEC §3 — `@Async` + `@Transactional` on same method is a trap).
- Sequence: `statusWriter.markProcessing(id)` → `TikaDocumentReader(source).read()` → `splitter.apply(docs)` → metadata enrichment → `vectorStore.add(chunks)` → `statusWriter.markReady(id)`. On any exception: log.error full stack, `statusWriter.markFailed(id, ex.getMessage())`, rethrow.
- Poll `cancellationRegistry.isCancelled(id)` between chunks (D-20).
- Do NOT catch `Throwable` silently (AI-SPEC anti-pattern).

**Retry (D-16 RESEARCH OVERRIDE):** Do NOT add `@Retryable` — use built-in `spring.ai.retry.*` (AI-SPEC "Don't Hand-Roll" table row 1). Expose `jmix.ai-agent.rag.embed-retry.*` as pass-through keys in properties class.

---

### `KnowledgeDocumentService.java` — `delete(UUID)` + `reingest(UUID)` (NEW)

**Analog:** `ConversationGateway.loadOrCreate(...)` (lines 56-84) for shape; `AuditWriter.writeToolCall` for the `@Transactional` boundary decision.

**Atomic delete pattern** (from AI-SPEC §4 code sample, binds both stores to one PTM):
```java
@Transactional   // REQUIRED — shared JDBC PTM
public void delete(UUID documentId) {
    cancellationRegistry.markCancelled(documentId);   // D-20 handshake
    Filter.Expression byDocId = new FilterExpressionBuilder()
            .eq("documentId", documentId.toString()).build();
    vectorStore.delete(byDocId);
    AiKnowledgeDocument doc = dataManager.load(AiKnowledgeDocument.class)
            .id(documentId).optional()
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    dataManager.remove(doc);
}
```

**DataManager load pattern** (mirrors `AuditWriter.writeChatPre` lines 67-74):
```java
AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
```

**Query pattern for list views** (mirrors `ConversationGateway` lines 78-82):
```java
dataManager.load(AiConversation.class)
        .query("select c from ai_AiConversation c where c.id = :id and c.createdBy = :owner")
        .parameter("id", conversationId)
        .parameter("owner", userId)
        .optional();
```

**Reingest:** same `@Transactional(REQUIRED)` — `vectorStore.delete(eq("documentId", X))`, reset `doc.setStatus(PENDING); doc.setErrorMessage(null); doc.setIngestedAt(null);` `dataManager.save(doc)`, then invoke the `@Async` worker.

---

### `KnowledgeDocumentUploadService.java` (NEW)

**Analog:** `ConversationGateway.loadOrCreate(...)` lines 56-74.

**Validation + Metadata.create() + DataManager.save() pattern:**
```java
if (userId == null || userId.isBlank()) {
    throw new IllegalArgumentException("userId must not be null or blank");
}
AiConversation fresh = metadata.create(AiConversation.class);
fresh.setCreatedBy(userId);
fresh.setCreatedDate(OffsetDateTime.now());
// ...
return dataManager.save(fresh);
```

**Apply to upload:**
1. Validate each submitted role code via `roleRepository.getRoleByCode(...)`; reject unknown with `UnknownRoleCodeException` (D-07).
2. `AiKnowledgeDocument doc = metadata.create(AiKnowledgeDocument.class);` — set fileName, mimeType, sizeBytes, status=PENDING, allowedRolesJson (Jackson-serialized list), createdBy, createdDate.
3. `dataManager.save(doc)` — commits PENDING row.
4. Dispatch async: `asyncIngestionWorker.ingest(doc.getId(), resource, allowedRoles, fileName)` — **MUST** be after the sync save completes (Anti-pattern in AI-SPEC §4: async race against upload tx commit).

---

### `IngesterManager.java` (NEW — SPI fan-out, batch)

**Analog:** `com/vn/agent/audit/AuditListenerDispatcher.java` (entire file, 22-46).

**Collection injection + per-item try/catch pattern:**
```java
@Component
public class AuditListenerDispatcher {

    private final List<AuditListener> listeners;

    public AuditListenerDispatcher(List<AuditListener> listeners) {
        this.listeners = listeners;
    }

    public void dispatchToolCallAudited(UUID auditId) {
        for (AuditListener listener : listeners) {
            try {
                listener.onToolCallAudited(auditId);
            } catch (Throwable t) {
                log.warn("AuditListener {} threw on auditId={}; suppressed",
                        listener.getClass().getName(), auditId, t);
            }
        }
    }
}
```

**Apply to `IngesterManager.runAll() / runById(String ingesterId)`:**
- Constructor-inject `List<CustomIngester> ingesters`.
- For each ingester: read docs, synthesize `AiKnowledgeDocument` row with stable UUIDv5 id (D-19 — `UUID.nameUUIDFromBytes((ingesterId + "|" + source).getBytes(UTF_8))`), dispatch into the same async pipeline.
- Wrap each SPI `read()` in a per-ingester try/catch (same defensive posture as dispatcher).

---

### `ClasspathMarkdownIngester.java` (NEW — SPI impl, gated)

**Analog:** `SpiDefaultsAutoConfiguration.defaultCustomIngester()` lines 64-71 + `CustomIngester` interface contract (lines 17-26).

**Inline anonymous impl pattern (default no-op):**
```java
@Bean
@ConditionalOnMissingBean
public CustomIngester defaultCustomIngester() {
    return new CustomIngester() {
        @Override public String getId() { return "noop"; }
        @Override public String getDisplayName() { return "No-op"; }
        @Override public List<Document> read() { return Collections.emptyList(); }
    };
}
```

**Apply to `ClasspathMarkdownIngester` (D-17):**
- `@Component` + `@ConditionalOnProperty(prefix = "jmix.ai-agent.rag.sample-ingester", name = "enabled", havingValue = "true")`.
- `getId()` returns stable `"classpath-markdown"`; `getDisplayName()` returns i18n-friendly label.
- `read()` scans via `PathMatchingResourcePatternResolver` against configured `path-pattern`; each `.md` → `new Document(content, Map.of("source", filename, "allowedRoles", List.of(AiAgentUserRole.CODE)))`.

---

### `AiAgentRagProperties.java` + `AiAgentEmbeddingProperties.java` (NEW @ConfigurationProperties)

**Analog:** `com/vn/agent/orchestration/AiAgentDefaultsProperties.java` (entire file).

**Record-based `@ConfigurationProperties` pattern:**
```java
@ConfigurationProperties("jmix.ai-agent.defaults")
public record AiAgentDefaultsProperties(
        String model,
        Double temperature,
        Double topP,
        Integer maxTokens,
        String systemPrompt) {

    public static final String FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant.";
}
```

**Registration via `@ConfigurationPropertiesScan` on `AIConfiguration`** (AIConfiguration line 22) — already enabled, no change needed for new properties records living under `com.vn.agent.*`.

**Apply:**
- `AiAgentRagProperties` prefix `"jmix.ai-agent.rag"` with nested records for `splitter`, `embedRetry`, `sampleIngester`, `ingestExecutor`.
- `AiAgentEmbeddingProperties` prefix `"jmix.ai-agent.embedding"` — model, dimensions, providerBaseUrl.

---

### `CancellationRegistry.java` (NEW — in-memory state utility)

**Analog:** `com/vn/agent/orchestration/RunContext.java` (not fully read but referenced from `AuditAdvisor` lines 78, 98) — thread-safe in-memory coordination primitive.

**Shape:** `@Component` with `ConcurrentHashMap<UUID, Boolean>`; `markCancelled(UUID)`, `isCancelled(UUID)`, `clear(UUID)`. No Spring proxy, no transaction. Worker polls `isCancelled` at chunk boundaries.

---

### Exception classes (`UnknownRoleCodeException`, `DocumentNotFoundException`)

**Analog:** `com/vn/agent/orchestration/ConversationNotFoundException.java` (entire file).

**Pattern:**
```java
public class ConversationNotFoundException extends RuntimeException {
    public static final String MESSAGE_KEY = "com.vn.agent.orchestration/ConversationNotFound";
    public static final String DEFAULT_MESSAGE = "Conversation not found";
    private final UUID conversationId;

    public ConversationNotFoundException(UUID conversationId) {
        super(DEFAULT_MESSAGE);
        this.conversationId = conversationId;
    }

    public UUID getConversationId() { return conversationId; }
}
```

**Apply:** Each exception exposes `MESSAGE_KEY` + `DEFAULT_MESSAGE` constants; carries the identifying value (`roleCode` string, `documentId` UUID) as a field; `super(DEFAULT_MESSAGE)` in constructor.

---

### `IngestExecutorConfig.java` (NEW — @Bean TaskExecutor)

**Analog:** `AIAutoConfiguration.jdbcChatMemoryRepository(...)` (lines 43-49) — `@Bean @ConditionalOnMissingBean` returning a builder-built infrastructure bean.

**Apply:** `@Configuration` class with:
```java
@Bean("aiAgentIngestExecutor")
@ConditionalOnMissingBean(name = "aiAgentIngestExecutor")
public TaskExecutor aiAgentIngestExecutor(AiAgentRagProperties props) {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(props.ingestExecutor().corePoolSize());
    exec.setMaxPoolSize(props.ingestExecutor().maxPoolSize());
    exec.setQueueCapacity(props.ingestExecutor().queueCapacity());
    exec.setThreadNamePrefix("ai-agent-ingest-");
    exec.setDaemon(true);
    exec.setTaskDecorator(new MdcPropagatingTaskDecorator());   // AI-SPEC §4b
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());  // G-6 back-pressure
    exec.initialize();
    return exec;
}
```

---

### `070-ai-kb-vector-store.xml` (NOT modified)

**Do not modify.** Phase 2 DDL is complete; CONTEXT.md canonical_refs confirms Phase 5 introduces no new changelog. Use existing table name `AI_AGENT_KB_VECTOR_STORE` in `PgVectorStore.builder().vectorTableName(...)`.

---

### `messages.properties` / `messages_vi.properties` (MODIFIED)

**Analog:** self, existing entries at lines 75-85 (existing English file).

**Add entries for new user-facing strings** (i18n MUST be in BOTH locale files — CLAUDE.md Forbidden #4):

EN (`messages.properties`):
```properties
# ===== RAG =====
com.vn.agent.rag/UnknownRoleCode=Unknown role code
com.vn.agent.rag/DocumentNotFound=Knowledge document not found
com.vn.agent.rag/UploadFailed=Failed to upload knowledge document
com.vn.agent.rag/IngestFailed=Failed to ingest knowledge document
com.vn.agent.rag/DocumentTooLarge=Document exceeds size limit
com.vn.agent.rag.sample/classpath-markdown=Classpath markdown ingester
```

VI (`messages_vi.properties`): mirror keys with Vietnamese values (follow existing style — see lines 81-85).

Also add any new `AiKnowledgeDocumentStatus.CANCELLED` localization if the planner adds the enum value (discretion per CONTEXT.md D-20).

---

## Shared Patterns

### Dependency Injection (constructor-only)

**Source:** Every existing service — `AuditWriter` (48-52), `ChatClientFactory` (38-44), `ConversationGateway` (39-42), `BaselineContextProvider` (42-44).

**Apply to:** ALL Phase 5 service beans, writers, factories, managers.

```java
private final DataManager dataManager;
private final Metadata metadata;

public MyService(DataManager dataManager, Metadata metadata) {
    this.dataManager = dataManager;
    this.metadata = metadata;
}
```

No `@Autowired` field injection. No Lombok (CLAUDE.md).

---

### Entity instantiation

**Source:** `AuditWriter.writeChatPre` line 60 + `ConversationGateway.loadOrCreate` line 62 + `AiParametersResolver.buildFallback` line 50.

```java
AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
// ... or ...
AiConversation fresh = metadata.create(AiConversation.class);
```

**Apply to:** Every `new AiKnowledgeDocument(...)` site. Never use the constructor (CLAUDE.md Forbidden #2).

---

### DataManager persistence (never EntityManager)

**Source:** `AuditWriter` line 78 (`dataManager.save(row);`), `ConversationGateway` line 73 (`dataManager.save(fresh);`), load pattern line 67 (`dataManager.load(X.class).id(y).optional()`).

**Apply to:** `KnowledgeDocumentUploadService` (save new doc), `IngestionStatusWriter` (save status updates), `KnowledgeDocumentService.delete` (load + remove), `IngesterManager` (save/upsert synthetic docs).

---

### Role codes (admin-bypass detection)

**Source:** `com/vn/agent/security/AiAgentAdminRole.java` line 21:
```java
public interface AiAgentAdminRole {
    String CODE = "ai-agent-admin";
    // ...
}
```

**Apply in `RetrievalFilterBuilder`:**
```java
import com.vn.agent.security.AiAgentAdminRole;
// ...
if (props.adminBypass() && roles.contains(AiAgentAdminRole.CODE)) {
    return null;   // D-06
}
```

Do NOT hardcode the string `"ai-agent-admin"` or `"AiAgentAdminRole"` — reference the constant. The AI-SPEC §4 code sample uses `"AiAgentAdminRole"` which is WRONG — use the Jmix role CODE (`"ai-agent-admin"`) that `GrantedAuthority.getAuthority()` actually returns. Similarly for `AiAgentUserRole.CODE`.

---

### Logging

**Source:** `AuditWriter` line 39, `ConversationGateway` (no logger — pure fn), `AuditAdvisor` line 46.

```java
private static final Logger log = LoggerFactory.getLogger(MyClass.class);
```

SLF4J only. Full stack on errors (AI-SPEC anti-pattern: "Catching Throwable in the async worker and setting FAILED without logging").

---

### i18n keys (both locale files)

**Source:** `messages.properties` + `messages_vi.properties` — pair of files always edited together.

**Rule:** Every user-facing exception defined must declare `MESSAGE_KEY` constant (see `ConversationNotFoundException.MESSAGE_KEY` line 21) AND add matching entries to BOTH `messages.properties` and `messages_vi.properties` (CLAUDE.md Forbidden #5). Structure — namespace/key format: `com.vn.agent.rag/KeyName=Human-readable text`.

---

### Liquibase — no new changelog needed

**Source:** `changelog.xml` + `070-ai-kb-vector-store.xml` (Phase 2 complete).

**Rule:** Phase 5 does NOT add new `<include>` entries unless the planner chooses status-polled cancellation AND needs a new `CANCELLED` enum ID stored — even then, the DB column is already `VARCHAR(16)` so no DDL change is strictly required. If a changelog IS added, it goes as `090-<name>.xml` and is `<include>`-d in `changelog.xml` line 32.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `AbstractRagIntegrationTest.java` | test base | — | First Testcontainers+pgvector base class in the repo — new pattern per AI-SPEC §5. |
| `EmbeddingModelBeanCollisionTest.java` | test | — | First `context.getBeansOfType(...)` assertion-style test; novel shape but trivial. |

Planner should follow the AI-SPEC §5 templates (`@Testcontainers` + `PostgreSQLContainer<>("pgvector/pgvector:pg16")` + `@DynamicPropertySource`) verbatim — those code snippets ARE the pattern.

## Metadata

**Analog search scope:** `ai-agent/ai-agent/src/main/java/com/vn/agent/**` + `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/**` + `ai-agent/ai-agent/src/main/resources/com/vn/agent/**`.
**Files scanned:** ~50 (all existing Java + 3 resource files).
**Pattern extraction date:** 2026-04-20.
