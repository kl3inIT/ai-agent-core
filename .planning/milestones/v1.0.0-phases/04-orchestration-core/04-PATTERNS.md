# Phase 4: Orchestration Core - Pattern Map

**Mapped:** 2026-04-20
**Files analyzed:** 18 (15 new, 3 modified)
**Analogs found:** 17 / 18

> All file paths below are absolute. New files land under `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/` (functional module) except auto-config additions, which land in `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/`. The functional module owns `@Component`/`@Service`/`@Configuration` beans discovered by `AIConfiguration`'s `@ComponentScan`; the starter owns `@AutoConfiguration` beans listed in `AutoConfiguration.imports` (verified — all current `@Component`s in Phase 3 live in `ai-agent` and are picked up via `AIConfiguration`).

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `orchestration/ChatService.java` (modify) | service interface | request-response | `ai-agent/.../ChatService.java` | exact (extend in place) |
| `orchestration/DefaultChatServiceImpl.java` (modify/replace) | service | request-response | `ai-agent/.../DefaultChatServiceImpl.java` | exact (full rewrite) |
| `orchestration/ChatResponseDto.java` | DTO record | data transfer | `ai-agent/.../ChatResponse.java` | exact |
| `orchestration/ConversationGateway.java` | service | CRUD (load-or-create) | `metadata/CurrentUserSchemaAccess.java` (per-request stateless `@Component` against Jmix) | role-match |
| `orchestration/ConversationNotFoundException.java` | exception | error-channel | `tools/ToolUserError.java` (existing fail-closed exception) | role-match |
| `orchestration/ProjectingChatMemoryRepository.java` | decorator / repository | event-driven (write fan-in) | `tools/AgentToolCallbacks.java` (composition over framework callback) | role-match |
| `orchestration/BaselineContextProvider.java` | service / context-builder | request-response | `metadata/CurrentUserSchemaAccess.java` (per-request stateless component reading `CurrentAuthentication`) | role-match |
| `orchestration/AiParametersResolver.java` | service | CRUD (read) | `metadata/CurrentUserSchemaAccess.java` (per-request `DataManager` read, no caching) | role-match |
| `orchestration/AiAgentDefaultsProperties.java` | config-properties | config | (none — first `@ConfigurationProperties` in module) | none |
| `orchestration/ChatClientFactory.java` | configuration / bean factory | startup wiring | `starter/.../AIAutoConfiguration.java` (current `@Bean ChatClient`) | exact |
| `orchestration/advisor/AuditAdvisor.java` | advisor (around-chain) | request-response | (none — first `CallAdvisor` impl) | none |
| `orchestration/advisor/ToolCallbackAuditDecorator.java` | tool-callback decorator | request-response | `tools/AgentToolCallbacks.java` (assembles per-request `ToolCallback[]`) | role-match |
| `audit/AuditWriter.java` | service | CRUD (write, REQUIRES_NEW) | `tools/BuiltInDataTools.java` (constructor-injected `DataManager`/`Metadata`) | role-match |
| `audit/AuditListenerFanOut.java` | event dispatcher | event-driven (afterCommit) | `spi/AuditListener.java` (consumer contract) | partial |
| `audit/RunContext.java` | utility | thread-local | (none) | none |
| `starter/.../AIAutoConfiguration.java` (modify) | autoconfiguration | startup wiring | `starter/.../AIAutoConfiguration.java` (extend) | exact (in place) |
| `starter/.../AIAutoConfiguration.java` add `ChatMemory` + `ProjectingChatMemoryRepository` `@Bean`s | autoconfiguration | startup wiring | `starter/.../SpiDefaultsAutoConfiguration.java` (`@ConditionalOnMissingBean` shape) | role-match |
| `ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java` | integration test | request-response | `ai-agent/src/test/.../FoundationsBootSmokeTest.java` | exact |
| `ai-agent/src/test/.../ProjectingChatMemoryRepositoryTest.java` | integration test | CRUD | `FoundationsBootSmokeTest.java#all_five_entities_round_trip` | role-match |
| `ai-agent/src/test/.../AuditWriterRequiresNewTest.java` | integration test | CRUD (tx semantics) | `FoundationsBootSmokeTest.java` | role-match |
| `ai-agent/.../resources/com/vn/agent/messages*.properties` (modify) | i18n | config | `ai-agent/.../messages.properties` | exact |

## Pattern Assignments

### `orchestration/DefaultChatServiceImpl.java` (service, request-response)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (Phase 1 stub — full rewrite for Phase 4 per D-01)

**Constructor-injection pattern** (analog lines 14-22):
```java
@Service
public class DefaultChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    // CLAUDE.md: constructor injection only.
    public DefaultChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }
```
**Phase 4 deviation:** inject the *cached* `ChatClient` bean (from `ChatClientFactory`), not the `Builder`; add `AgentToolCallbacks`, `AiParametersResolver`, `ConversationGateway`, `BaselineContextProvider`. Keep `@Service` annotation, keep constructor injection, keep package `com.vn.agent` (recommended substructure `.orchestration` per RESEARCH.md).

**Per-request `.prompt()` builder pattern** (analog lines 25-30 — extend per AI-SPEC §3):
```java
String content = chatClient.prompt()
        .user(message)
        .call()
        .content();
```
Phase 4 extends to: `.prompt().system(params.getSystemPrompt()).user(message).tools(agentToolCallbacks.forCurrentUser()).options(openAiChatOptions).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.toString())).call().chatClientResponse()`.

**Tool assembly invariant — copy verbatim from analog:** `AgentToolCallbacks.forCurrentUser()` returns a `ToolCallback[]` per call (D-10 Phase 3); pass via `.tools(...)`, **never** `.defaultTools(...)`. See `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` lines 39-53.

---

### `orchestration/ChatResponseDto.java` (DTO record, data transfer)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java`

**Full pattern** (analog lines 11-20):
```java
public record ChatResponse(String content, Map<String, Object> metadata) {
    public ChatResponse {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null (use Map.of() for empty)");
        }
    }
}
```
**Phase 4 deviation:** add `runId` (UUID) and `usage` (token counts if available from `ChatClientResponse.chatResponse().getMetadata().getUsage()`). Keep record + compact-constructor null-check pattern. Naming choice (`ChatResponseDto` vs reuse `ChatResponse`) is planner's discretion — RESEARCH.md uses `ChatResponseDto` to disambiguate from `org.springframework.ai.chat.model.ChatResponse`.

---

### `orchestration/ConversationGateway.java` (service, CRUD)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` (per-request stateless `@Component` reading `AccessManager`/`Metadata`; never cached)

**Imports + constructor pattern** (analog lines 1-37):
```java
import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserSchemaAccess {

    private final AccessManager accessManager;
    private final Metadata metadata;

    public CurrentUserSchemaAccess(AccessManager accessManager, Metadata metadata) {
        this.accessManager = accessManager;
        this.metadata = metadata;
    }
```
**Phase 4 deviation:** inject `DataManager`, `Metadata`, `CurrentAuthentication` instead. Stateless `@Component`. Method `loadOrCreate(UUID userId, UUID conversationId, String firstMessage)` that:
1. Calls `dataManager.load(AiConversation.class).id(conversationId).optional()`.
2. If empty → throws `ConversationNotFoundException` (defence in depth — Jmix row-level predicate from `AiAgentUserRowLevelRole` already hides not-yours rows; same exception fires for both per D-09).
3. If `optional()` returns empty AND caller signalled "new conversation", `metadata.create(AiConversation.class)` + set `createdBy` from `CurrentAuthentication.getUser().getUsername()` + set `title` = first message truncated to ~80 chars + `dataManager.save(conv)`.

**Entity-creation pattern** (copy from `FoundationsBootSmokeTest.all_five_entities_round_trip` analog `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` lines 125-141):
```java
AiConversation conv = metadata.create(AiConversation.class);
conv.setTitle("round-trip conv");
conv.setCreatedBy("system");
// ...
AiConversation savedConv = dataManager.save(conv, msg).get(conv);
```
**CLAUDE.md rules enforced:** never instantiate entities by constructor; `Metadata.create()` always.

---

### `orchestration/ConversationNotFoundException.java` (exception, error-channel)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java` (existing fail-closed exception used in `BuiltInDataTools`)

**Pattern:** unchecked `RuntimeException`. Single message string MUST be identical for "row does not exist" and "row exists but not yours" (D-09 — error-channel opacity). Use a `msg://` key for the message text per CLAUDE.md i18n rule. Reserved key:
```
com.vn.agent.orchestration/ConversationNotFound=Conversation not found
```
Both `messages.properties` and `messages_vi.properties` must receive the key — see analog `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` for namespacing convention (`com.vn.agent.<subpackage>/<Key>=Text`).

---

### `orchestration/ProjectingChatMemoryRepository.java` (decorator, event-driven write)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` (composition-over-framework decorator)

**Constructor-injection pattern** (analog lines 26-34):
```java
@Component
public class AgentToolCallbacks {

    private final BuiltInDataTools builtIns;
    private final List<ToolContributor> contributors;

    public AgentToolCallbacks(BuiltInDataTools builtIns, List<ToolContributor> contributors) {
        this.builtIns = builtIns;
        this.contributors = contributors;
    }
```
**Phase 4 deviation:** implement `org.springframework.ai.chat.memory.ChatMemoryRepository`. Inject `JdbcChatMemoryRepository delegate`, `DataManager`, `Metadata`. NOT a `@Component` discovered by scan — registered as a `@Bean` in `AIAutoConfiguration` so the `@Primary` / `@ConditionalOnMissingBean` semantics work and the `JdbcChatMemoryRepository` raw bean is decorated.

**`AiMessage` write pattern** (copy from `FoundationsBootSmokeTest` lines 129-141 + AI-SPEC §3 Pattern 3):
```java
AiMessage entity = metadata.create(AiMessage.class);
entity.setConversation(conversationRef);             // load AiConversation by id (D-08 invariant)
entity.setRole(mapRole(m.getMessageType()));         // USER/ASSISTANT/SYSTEM/TOOL
entity.setContent(m.getText());
dataManager.save(entity);
```

**Transaction pattern (D-07 — REQUIRED, same tx as caller):**
```java
@Override
@Transactional(propagation = Propagation.REQUIRED)
public void saveAll(String conversationId, List<Message> messages) {
    delegate.saveAll(conversationId, messages);
    for (Message m : messages) { /* ...save AiMessage... */ }
}
```
Note: `AiMessage.conversation` is `@NotNull` (see `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java` lines 31-35) — projector MUST resolve a real `AiConversation` reference, not a synthetic one. Combine with `ConversationGateway.loadOrCreate(...)` having run first in `DefaultChatServiceImpl` so the parent row exists.

---

### `orchestration/BaselineContextProvider.java` (service, request-response)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`

**Stateless `@Component` + `CurrentAuthentication` pattern:** copy the constructor-injection shape (analog lines 28-37). Inject `CurrentAuthentication` from `io.jmix.core.security`. Build a baseline map per `ContextContributor` SPI Javadoc (`D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ContextContributor.java` lines 12-18):
```
agent.userId, agent.username, agent.roles (Set<String>), agent.locale, agent.conversationId
```
**Critical contract from SPI Javadoc lines 22-23:** "Implementations MUST NOT overwrite keys under the reserved `agent.*` namespace." `BaselineContextProvider` is the *producer* of those keys; SPI contributors run AFTER it (D-15) and append host-specific entries.

**Composition with system prompt:** prepend baseline to `AiParameters.systemPrompt` and to the iterated `PromptContextContributor.fragment()` calls (analog `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/spi/PromptContextContributor.java` lines 25-29). Resulting string passed to `.system(...)` on the `ChatClient` builder.

---

### `orchestration/AiParametersResolver.java` (service, CRUD read)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` (per-request, no caching)

**Constructor-injection pattern:** copy lines 28-37.

**`DataManager` read pattern** (copy from `BuiltInDataTools` analog `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` lines 184-190):
```java
Object entity = dataManager.load(metaClass.getJavaClass())
        .id(parsedId)
        .fetchPlan(FetchPlan.INSTANCE_NAME)
        .optional()
        .orElse(null);
```
**Phase 4 deviation:** load by predicate, not id:
```java
return dataManager.load(AiParameters.class)
        .query("select e from ai_AiParameters e where e.active = true")
        .optional()
        .orElseGet(this::buildFallbackFromProperties);
```
Fallback builds an in-memory `AiParameters` (via `metadata.create(AiParameters.class)` — never `new`) from `AiAgentDefaultsProperties`. See `AiParameters` shape at `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java` lines 17-71 — note the YAML body lives in `bodyYaml`; resolver MUST parse it (snakeyaml on classpath via Spring Boot) to surface `model`/`temperature`/`topP`/`systemPrompt`. Discretion for parsing strategy is open.

---

### `orchestration/AiAgentDefaultsProperties.java` (config-properties, config)

**No analog** — first `@ConfigurationProperties` in the module. Use Spring Boot canonical pattern. `@ConfigurationPropertiesScan` is already present on `AIConfiguration` (line 22 of `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`) so a `@ConfigurationProperties("jmix.ai-agent.defaults")` record will be picked up automatically.

```java
@ConfigurationProperties("jmix.ai-agent.defaults")
public record AiAgentDefaultsProperties(
        String model,
        Double temperature,
        Double topP,
        Integer maxTokens,
        String systemPrompt) { }
```

---

### `orchestration/ChatClientFactory.java` (configuration, startup wiring)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` (current Phase 1 `@Bean ChatClient`)

**Bean-method pattern** (analog lines 18-27):
```java
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```
**Phase 4 deviation:** the existing `@Bean ChatClient` in `AIAutoConfiguration` is replaced by an injection of `ChatClientFactory`, OR `ChatClientFactory` itself becomes the `@Configuration` class housing the `ChatClient` `@Bean` (planner's discretion — both viable). The bean assembles `MessageChatMemoryAdvisor` + `ToolCallAdvisor` + `AuditAdvisor` and registers them via `defaultAdvisors(...)` per AI-SPEC §3. **Preserve `@ConditionalOnMissingBean`** so host apps can still override.

**Advisor order constants — copy verbatim from AI-SPEC:**
- `AuditAdvisor.getOrder() == Ordered.HIGHEST_PRECEDENCE`
- `MessageChatMemoryAdvisor.builder().advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 200)`
- `ToolCallAdvisor.builder().advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 300).disableMemory()`

**Open question per RESEARCH.md OQ-1:** verify `.disableMemory()` exists on `ToolCallAdvisor.Builder` in 1.1.4 jar at task start; substitute `.disableInternalConversationHistory()` / `.conversationHistoryEnabled(false)` if signature shifted. Record verified signature in `04-SUMMARY.md`.

---

### `orchestration/advisor/AuditAdvisor.java` (advisor, around-chain)

**No direct in-codebase analog** — first `CallAdvisor` impl in the module. Source: AI-SPEC §3 Pattern 2 (verified against Spring AI 1.1.2 docs).

**Skeleton (copy verbatim from RESEARCH.md Pattern 2 lines 308-343):**
```java
public class AuditAdvisor implements CallAdvisor {

    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditAdvisor(AuditWriter auditWriter, CurrentAuthentication ca) { /* ... */ }

    @Override public String getName() { return "AuditAdvisor"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        UUID runId = UUID.randomUUID();
        RunContext.set(runId);
        // ... auditWriter.writeChatPre(...); try { return chain.nextCall(req); } catch ...
    }
}
```
**Constructor injection** matches existing module convention (`BuiltInDataTools` lines 58-76).

**Critical (D-11):** `AuditAdvisor` itself is NOT `@Transactional`. It delegates to `AuditWriter` whose methods carry `@Transactional(REQUIRES_NEW)`. Self-invocation (`this.writeXxx`) bypasses Spring's CGLib proxy and silently breaks the requirement — the advisor MUST hold an injected `AuditWriter` reference.

---

### `orchestration/advisor/ToolCallbackAuditDecorator.java` (decorator, request-response)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` (per-request callback assembly)

**Wire-up pattern (planner's choice per D-15 in CONTEXT):** the simplest seam given `AgentToolCallbacks.forCurrentUser()` returns `ToolCallback[]` is to wrap each callback at assembly time. Extend `AgentToolCallbacks` analog lines 40-53:
```java
public ToolCallback[] forCurrentUser() {
    List<ToolCallback> all = new ArrayList<>();
    Collections.addAll(all, fromBean(builtIns));
    for (ToolContributor tc : contributors) { /* ... */ }
    return all.toArray(ToolCallback[]::new);
}
```
**Phase 4 deviation:** wrap each `ToolCallback` in the returned array with `ToolCallbackAuditDecorator(delegate, auditWriter)`. The decorator implements `ToolCallback` (delegates `getToolDefinition()`, `getToolMetadata()`); on `call(String toolInput, ToolContext ctx)` it:
1. `runId = RunContext.get()`
2. `auditWriter.writeToolCallPre(runId, name, argsJson)` — REQUIRES_NEW
3. invoke `delegate.call(...)` with try/catch
4. `auditWriter.writeToolCallPost(runId, outcome, latencyMs, errorClass, resultSummary)` — REQUIRES_NEW

This is the wire-up mechanism RESEARCH.md "Architectural Responsibility Map" rows "Per-tool-call audit" calls for and matches D-09 Phase 3's per-request `.tools(...)` invariant. Inject `AgentToolCallbacks` constructor with `AuditWriter` (constructor-injection per CLAUDE.md).

---

### `audit/AuditWriter.java` (service, CRUD write with REQUIRES_NEW)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` (constructor-injected `DataManager`/`Metadata` write surface)

**Constructor-injection pattern** (analog lines 58-76):
```java
public BuiltInDataTools(DataManager dataManager,
                        Metadata metadata,
                        /* ... */ ) {
    this.dataManager = dataManager;
    this.metadata = metadata;
    // ...
}
```

**Entity creation + save** (copy from `FoundationsBootSmokeTest` lines 154-158):
```java
AiToolCallAudit audit = metadata.create(AiToolCallAudit.class);
audit.setToolName("sampleTool");
audit.setOutcome(AiToolCallOutcome.SUCCESS);
audit.setStartedAt(OffsetDateTime.now());
AiToolCallAudit savedAudit = dataManager.save(audit);
```
Entity shape and field names: see `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java` (toolName, argumentsJson, resultSummary, outcome, denialReason, latencyMs, startedAt, finishedAt — all already shipped). `AiToolCallOutcome` enum: SUCCESS, BLOCKED, ERROR (see messages.properties lines 16-18).

**Transaction pattern (D-11 — REQUIRES_NEW on each method):**
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void writeChatPre(UUID runId, UUID userId, UUID convId, String promptHash) { /* ... */ }

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void writeChatPost(UUID runId, AiToolCallOutcome outcome, long latencyMs, String errorClass) { /* ... */ }

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void writeToolCall(UUID runId, String toolName, String argsJson, ...) { /* ... */ }
```
Each method MUST register the listener fan-out via `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { auditListenerFanOut.fire(savedAuditId); } })` BEFORE the method returns (D-14).

**Modelling note:** Phase 2's `AiToolCallAudit` does NOT have a `runId` column (verify via `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/030-ai-tool-call-audit.xml`) — planner must decide whether to (a) add a Phase 4 changelog adding `RUN_ID` UUID column + entity field, or (b) overload existing fields. Per CONTEXT D-12 "runId threads pre-row and post-row" — option (a) is required. New changelog `080-ai-tool-call-audit-runid.xml` included in master `changelog.xml` per CLAUDE.md.

---

### `audit/AuditListenerFanOut.java` (event dispatcher, event-driven)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditListener.java` (consumer contract)

**Listener contract — copy verbatim from analog lines 12-15:**
```java
public interface AuditListener {
    /** @param auditId the just-persisted {@code AiToolCallAudit.id} */
    void onToolCallAudited(java.util.UUID auditId);
}
```
**Per-listener try/catch pattern (D-13):** SPI Javadoc lines 6-10 already document the contract: "exceptions thrown here MUST NOT fail the primary request. The add-on wraps every invocation in a try/catch so a broken listener cannot corrupt the audit write or the user-visible tool-call result." Implementation:
```java
@Component
public class AuditListenerFanOut {
    private static final Logger log = LoggerFactory.getLogger(AuditListenerFanOut.class);
    private final List<AuditListener> listeners;

    public AuditListenerFanOut(List<AuditListener> listeners) {
        this.listeners = listeners;
    }

    public void fire(UUID auditId) {
        for (AuditListener l : listeners) {
            try { l.onToolCallAudited(auditId); }
            catch (Throwable t) { log.warn("AuditListener {} threw — swallowed", l.getClass().getName(), t); }
        }
    }
}
```
SPI default no-op already wired in `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` lines 58-61 — no change needed there.

---

### `audit/RunContext.java` (utility, thread-local)

**No analog.** Trivial `ThreadLocal<UUID>` holder with `set`/`get`/`clear` static methods. Used by `AuditAdvisor` (sets/clears per chat call) and `ToolCallbackAuditDecorator` (reads to attach `runId` to per-tool rows).

---

### `starter/.../AIAutoConfiguration.java` (modify in place)

**Analog:** itself + sibling `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java`

**Existing structure** (lines 18-27):
```java
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

**Phase 4 additions (each `@ConditionalOnMissingBean` per `SpiDefaultsAutoConfiguration` analog):**
```java
@Bean
@ConditionalOnMissingBean
public ChatMemory chatMemory(ChatMemoryRepository repository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
}

@Bean
@ConditionalOnMissingBean(ChatMemoryRepository.class)
@Primary
public ChatMemoryRepository projectingChatMemoryRepository(JdbcChatMemoryRepository delegate,
                                                           DataManager dataManager,
                                                           Metadata metadata) {
    return new ProjectingChatMemoryRepository(delegate, dataManager, metadata);
}
```
The existing `chatClient(...)` bean is replaced/superseded by `ChatClientFactory`'s assembly logic per AI-SPEC §3 entry-point pattern.

**Auto-configure ordering:** add `@AutoConfigureAfter` on the JDBC chat-memory starter's auto-config class so `JdbcChatMemoryRepository` is present when our decorator wires. Analog: `AiToolsAutoConfiguration` lines 24-27 demonstrate `@AutoConfigureAfter({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})`.

---

### Test files — Integration test pattern

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java`

**Test bootstrap pattern** (analog lines 79-83):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(FoundationsBootSmokeTest.TestUsers.class)
class FoundationsBootSmokeTest {
```
Phase 4 tests reuse this bootstrap. Add `AiToolsAutoConfiguration.class` to the `@ImportAutoConfiguration` list. For `OrchestrationIntegrationTest`, override the `ChatModel` bean with a Mockito stub matching the existing `ChatServiceMockTest` shape:

**ChatModel mocking pattern** (analog `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` lines 32-53):
```java
ChatModel mockModel = Mockito.mock(ChatModel.class);
org.springframework.ai.chat.model.ChatResponse cannedResponse =
        new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage("hello from mock"))));
Mockito.when(mockModel.call(ArgumentMatchers.any(Prompt.class)))
        .thenReturn(cannedResponse);
ChatClient.Builder builder = ChatClient.builder(mockModel);
```

**Identity-switched test sections** (analog `FoundationsBootSmokeTest` line 123 + line 357):
```java
systemAuthenticator.runWithSystem(() -> { /* setup */ });
systemAuthenticator.runWithUser("alice", () -> { /* assertions under alice's row-level predicate */ });
```
Use this for ownership tests: insert conv-of-bob under system, then `runWithUser("alice", ...)` and assert `ConversationGateway.loadOrCreate(...)` throws `ConversationNotFoundException` with the SAME message that the "no such id" case produces (success criterion #3).

**Audit-survives-rollback test (AUD-02 / D-11):** invoke a `@Tool` method that throws after writing → verify via `JdbcTemplate` query (analog lines 107, 285-291) that `AI_AGENT_TOOL_CALL_AUDIT` has the row even though the tool's outer transaction rolled back.

---

### `messages.properties` + `messages_vi.properties` (i18n, modify)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`

**Namespacing pattern** (analog lines 6-9, 71-73):
```
com.vn.agent.entity/AiMessageRole.USER=User
com.vn.agent.security/AiAgentUserRole=AI Agent User
```

**Phase 4 additions (apply to BOTH locale files per CLAUDE.md):**
```
com.vn.agent.orchestration/ConversationNotFound=Conversation not found
```
Vietnamese translation for `messages_vi.properties` discretion (suggested: `Không tìm thấy cuộc hội thoại`).

## Shared Patterns

### Constructor injection (every new service/component)
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` lines 58-76
**Apply to:** All Phase 4 `@Component`/`@Service`/`@Configuration` classes.
**Rule (CLAUDE.md):** No `@Autowired` fields, no `@RequiredArgsConstructor`/Lombok. Every dependency is a `private final` field assigned in a single public constructor.

### Entity instantiation via `Metadata.create()` (CLAUDE.md hard rule)
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` lines 125-141
**Apply to:** `ConversationGateway` (creates `AiConversation`), `ProjectingChatMemoryRepository` (creates `AiMessage`), `AuditWriter` (creates `AiToolCallAudit`), `AiParametersResolver` (creates fallback `AiParameters` from defaults).
```java
AiConversation conv = metadata.create(AiConversation.class);
// set fields...
AiConversation saved = dataManager.save(conv);
```
**Forbidden:** `new AiConversation()`, `new AiMessage()`, `new AiToolCallAudit()`.

### Per-request stateless `@Component` (no caching)
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` lines 17-44 (Javadoc explicitly: "Stateless `@Component` — no class-level cache")
**Apply to:** `ConversationGateway`, `BaselineContextProvider`, `AiParametersResolver`, `AuditListenerFanOut`. Reasoning: `CurrentAuthentication`/`AccessManager`/`DataManager` resolve per call; caching would freeze identity. Phase 6's profile-swap requirement (D-04) depends on `AiParametersResolver` reading per request.

### `DataManager` fluent loader (CLAUDE.md — `DataManager` only, never `EntityManager`)
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` lines 184-190 (`get_record` tool)
```java
Object entity = dataManager.load(metaClass.getJavaClass())
        .id(parsedId)
        .fetchPlan(FetchPlan.INSTANCE_NAME)
        .optional()
        .orElse(null);
```
**Apply to:** `ConversationGateway.loadOrCreate(...)` (`.id(convId).optional()`), `AiParametersResolver.resolveActive()` (`.query(...).optional()`).

### `@ConditionalOnMissingBean` for SPI-style overrides
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` lines 33-71 (every default bean wears the annotation)
**Apply to:** Every new `@Bean` in `AIAutoConfiguration` (`ChatClient`, `ChatMemory`, `ChatMemoryRepository`). Rationale: hosts can replace any of these by declaring their own bean of the same type.

### i18n `msg://` keys in BOTH locale files (CLAUDE.md — "ALL labels...MUST use `msg://` keys" + "Single-locale messages — ALWAYS add to ALL locale files")
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` (73 lines) and `messages_vi.properties` (73 lines, parallel structure)
**Apply to:** `ConversationNotFoundException` message key (`com.vn.agent.orchestration/ConversationNotFound`).

### Spring AI Mockito-on-`ChatModel` test pattern
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` lines 32-67
**Apply to:** All Phase 4 unit tests of `DefaultChatServiceImpl`, `AuditAdvisor` flow, advisor-ordering tests. Avoids the OpenRouter live dependency; matches the Phase 1 D-04 mock-first convention.

### `@SpringBootTest(classes = AITestConfiguration.class)` integration bootstrap
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` lines 79-83 + `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java`
**Apply to:** All Phase 4 integration tests (advisor wiring, ownership enforcement, dual-layer projection, audit-survives-rollback).

### `SystemAuthenticator.runWithUser(...)` for identity-switched tests
**Source:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` lines 122-187 (uses `runWithSystem`); analog uses `runWithUser` per Javadoc lines 73-76
**Apply to:** Ownership tests — set up conv-of-bob under system, then assert `runWithUser("alice", ...)` triggers `ConversationNotFoundException` identical to non-existent-id case.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `orchestration/AiAgentDefaultsProperties.java` | config-properties | config | First `@ConfigurationProperties` in module. Use Spring Boot canonical record-with-`@ConfigurationProperties` shape. RESEARCH.md §"Installation" specifies the property prefix `jmix.ai-agent.defaults`. |
| `orchestration/advisor/AuditAdvisor.java` | advisor | request-response | First `org.springframework.ai.chat.client.advisor.api.CallAdvisor` impl. Skeleton lifted verbatim from RESEARCH.md Pattern 2 (lines 308-343), which is itself verified against Spring AI 1.1.2 Context7 docs. |
| `audit/RunContext.java` | utility | thread-local | Trivial holder; no equivalent thread-local utility in module today. |

## Metadata

**Analog search scope:**
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/**`
- `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/**`
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/**`
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/**`

**Files scanned:** 22 production Java files, 11 test Java files, 7 Liquibase changelogs, 2 messages bundles, 3 auto-config classes.

**Pattern extraction date:** 2026-04-20
