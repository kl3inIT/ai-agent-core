# Phase 6: Parameters, Structured Output & Guardrails — Pattern Map

**Mapped:** 2026-04-21
**Files analyzed:** 23 new / 5 modified
**Analogs found:** 27 / 28

---

## File Classification

### New files

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `com/vn/agent/parameters/ParametersService.java` | service | CRUD + transactional invariant | `com/vn/agent/rag/KnowledgeDocumentService.java` | exact (CRUD + @Transactional + DataManager) |
| `com/vn/agent/parameters/AiParametersBody.java` | DTO (record) | transform (Jackson YAML bind) | `com/vn/agent/orchestration/AiAgentDefaultsProperties.java` | role-match (record shape only; `AiAgentDefaultsProperties` is `@ConfigurationProperties`, this is Jackson-bound) |
| `com/vn/agent/parameters/AiParametersBodyYamlMapper.java` | utility | transform (YAML <-> DTO) | `com/vn/agent/rag/AsyncIngestionWorker.java` (static `ObjectMapper JSON` field) | partial (ObjectMapper holder idiom) |
| `com/vn/agent/parameters/ParametersValidationException.java` | exception | - | `com/vn/agent/spi/ToolVetoedException.java` | exact (unchecked runtime exception shape) |
| `com/vn/agent/parameters/DefaultParamsSeeder.java` | component | event-driven boot seed | `com/vn/agent/rag/ClasspathMarkdownIngester.java` (classpath resource seed pattern) + `AIConfiguration.aiAgentIngestExecutor` (bean sizing) | partial (classpath resource load + `metadata.create` + `dataManager.save`) |
| `com/vn/agent/parameters/Overrides.java` (record) | value object | - | `com/vn/agent/orchestration/ChatResponseDto.java` (record with javadoc) | exact (record + javadoc style) |
| `com/vn/agent/guard/AiAgentGuardProperties.java` | config | - | `com/vn/agent/rag/config/AiAgentRagProperties.java` | exact (nested record properties) |
| `com/vn/agent/guard/RateLimitGuard.java` | service (guard) | request-response (precheck) | `com/vn/agent/rag/RetrievalFilterBuilder.java` | role-match (stateless `check`/`buildFor` shape; constructor injection) |
| `com/vn/agent/guard/TokenBudgetGuard.java` | service (guard) | request-response + post-response accumulate | `com/vn/agent/rag/IngestionStatusWriter.java` (single-responsibility writer with constructor injection) | partial (state transitions pattern; this uses cache not DB) |
| `com/vn/agent/guard/GuardedToolCallingManager.java` | delegating adapter | request-response wrap | `com/vn/agent/audit/ToolCallbackAuditDecorator.java` | exact (wrap-delegate + try/finally + `AuditWriter` calls + `RunContext` read) |
| `com/vn/agent/guard/OutputScannerAdvisor.java` | advisor | request-response wrap | `com/vn/agent/audit/AuditAdvisor.java` | exact (same `CallAdvisor` interface, `getOrder`, `adviseCall(req, chain)`, context map usage) |
| `com/vn/agent/guard/OutputScannerPattern.java` | value object | - | `com/vn/agent/rag/ChunkMetadata.java` (static constants holder) | partial |
| `com/vn/agent/guard/RateLimitExceededException.java` | exception | - | `com/vn/agent/spi/ToolVetoedException.java` | exact |
| `com/vn/agent/guard/TokenBudgetExhaustedException.java` | exception | - | `com/vn/agent/spi/ToolVetoedException.java` | exact |
| `com/vn/agent/guard/IterationCapExceededException.java` | exception | - | `com/vn/agent/spi/ToolVetoedException.java` | exact |
| `com/vn/agent/guard/StructuredOutputException.java` | exception | carries raw text + class | `com/vn/agent/orchestration/ConversationNotFoundException.java` | role-match (typed exception with extra state) |
| `com/vn/agent/guard/IterationCounter.java` (helper) | utility | per-turn ThreadLocal / context slot | `com/vn/agent/orchestration/RunContext.java` | exact (ThreadLocal holder idiom) |
| `ai-agent-starter/…/default-params.yaml` | config resource | - | (no prior YAML resource) | no analog — see "No Analog Found" |
| `ai-agent-starter/…/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` | autoconfig | - | `com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` + `AIAutoConfiguration.aiAgentVectorStore` | exact (@AutoConfiguration + @ConditionalOnMissingBean per extensible bean) |
| `liquibase/changelog/090-ai-tool-call-outcome-flagged.xml` (only if `FLAGGED` added to enum) | migration | - | `liquibase/changelog/080-ai-tool-call-audit-runid.xml` | exact (add-column / extend pattern) |
| Tests: `ParametersServiceTest`, `RateLimitGuardTest`, `TokenBudgetGuardTest`, `GuardedToolCallingManagerTest`, `OutputScannerAdvisorTest`, `AskTypedRetryTest`, `DefaultParamsSeederTest`, `I18nParityTest` | test | - | existing `AuditDurabilityTest` / `ChatServiceLiveSemanticTest` (inferred from research refs) | role-match (`@SpringBootTest` + `@MockBean ChatModel`) |

### Modified files

| Modified File | Role | Phase 6 Change | Patterns to Reuse |
|---------------|------|----------------|-------------------|
| `com/vn/agent/DefaultChatServiceImpl.java` | controller/service | add guard preamble, `ask(…, Overrides)` overload, `askTyped(…)` | own existing body; insert guards before `chatClient.prompt()`; add `askTyped` method |
| `com/vn/agent/ChatService.java` | interface | add `ask(…, Overrides)` overload + `askTyped(…)` + `askTyped(…, Overrides)` | follow existing interface doc style |
| `com/vn/agent/orchestration/AiParametersResolver.java` | service | add `effectiveModel(AiParameters, Overrides)` overload + `effectiveSystemPrompt(AiParameters, RunContext)` overload invoking `PromptContextContributor` list | existing effectiveX getters are the template |
| `com/vn/agent/orchestration/ChatClientFactory.java` | config | insert `OutputScannerAdvisor` in `defaultAdvisors` at `HIGHEST_PRECEDENCE + 400`; inject `GuardedToolCallingManager` as the `ToolCallingManager` bean; optionally wire `StructuredOutputValidationAdvisor` via `@ConditionalOnClass` | existing advisor ordering contract is the template |
| `com/vn/agent/orchestration/ChatResponseDto.java` | DTO (record) | add `flagged`, `flaggedPatternKey`, `GuardDenialInfo guardDenial` | existing record shape |
| `com/vn/agent/entity/AiToolCallOutcome.java` | enum | add `FLAGGED("FLAGGED")` | existing SUCCESS/BLOCKED/ERROR trio |
| `resources/com/vn/agent/messages.properties` + `messages_vi.properties` | i18n | add `ai-agent.guard.*`, `ai-agent.parameters.*` keys | existing enum/entity key block style |

---

## Pattern Assignments

### `ParametersService.java` (service, CRUD + transactional invariant)

**Analog:** `com/vn/agent/rag/KnowledgeDocumentService.java`

**Imports pattern** (KnowledgeDocumentService.java lines 1–17):
```java
package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
```

**Constructor injection + `@Service @Transactional` shape** (KnowledgeDocumentService.java lines 48–70):
```java
@Service
@Transactional
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private final DataManager dataManager;
    // ... other collaborators

    public KnowledgeDocumentService(DataManager dataManager, /* ... */) {
        this.dataManager = dataManager;
        // ...
    }
```

**`setActive` transactional invariant** — use this exact shape for D-06 (see AI-SPEC Example 3):
```java
@Transactional(propagation = Propagation.REQUIRED)
public void setActive(UUID targetId) {
    List<AiParameters> active = dataManager.load(AiParameters.class)
            .query("select e from ai_AiParameters e where e.active = true").list();
    for (AiParameters p : active) p.setActive(Boolean.FALSE);
    dataManager.save(active.toArray());
    AiParameters target = dataManager.load(AiParameters.class).id(targetId).one();
    target.setActive(Boolean.TRUE);
    dataManager.save(target);
}
```

**`loadOrThrow` helper pattern** (KnowledgeDocumentService.java lines 137–142) — reuse shape for `ParametersService.loadOrThrow`:
```java
private AiKnowledgeDocument loadOrThrow(UUID documentId) {
    return dataManager.load(AiKnowledgeDocument.class).id(documentId).optional()
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
}
```

---

### `AiParametersBody.java` (record DTO with Jakarta validation)

**Analog:** `com/vn/agent/orchestration/AiAgentDefaultsProperties.java` (record shape) + AI-SPEC 4b.1 for validator annotations

**Record shape with javadoc** (AiAgentDefaultsProperties.java lines 15–31):
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

For Phase 6 the Jackson-YAML body record should **not** carry `@ConfigurationProperties`; it is deserialised via a private ObjectMapper. Use the AI-SPEC Section 4b.1 shape:
```java
@JsonPropertyOrder({"model","temperature","maxTokens","systemPrompt"})
public record AiParametersBody(
    @NotBlank String model,
    @NotNull @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
    @NotNull @DecimalMin("1") @DecimalMax("16000") Integer maxTokens,
    @NotBlank String systemPrompt) {}
```

Plus the new Phase 6 fields from PARAM-02: `topP`, `enabledTools` (`List<String>`), `ragTopK`, `ragSimilarityThreshold`.

---

### `DefaultParamsSeeder.java` (event-driven seed)

**Analog:** `com/vn/agent/rag/IngestionStatusWriter.java` (constructor-inject + `metadata.create` + `dataManager.save`) + research example 4

**Bean instantiation via Metadata** — CLAUDE.md rule, used in `AuditWriter.writeChatPre` (AuditWriter.java line 60):
```java
AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
row.setRunId(runId);
// … setters …
dataManager.save(row);
```

**Apply for seeder:**
```java
@EventListener(ApplicationReadyEvent.class)
public void seedIfEmpty() {
    long count = dataManager.load(AiParameters.class)
            .query("select count(e) from ai_AiParameters e").one();
    if (count > 0) return;
    try (InputStream in = resourceLoader.getResource(defaultsResourcePath).getInputStream()) {
        AiParametersBody body = yamlMapper.readValue(in);
        AiParameters row = metadata.create(AiParameters.class);
        row.setProfileName("default");
        row.setActive(Boolean.TRUE);
        row.setBodyYaml(yamlMapper.writeAsYaml(body));
        dataManager.save(row);
    } catch (IOException e) {
        throw new IllegalStateException("Failed to seed " + defaultsResourcePath, e);
    }
}
```

---

### `Overrides.java` (record)

**Analog:** `com/vn/agent/orchestration/ChatResponseDto.java`

**Shape** (ChatResponseDto.java lines 1–28):
```java
package com.vn.agent.orchestration;
import java.util.UUID;
/** Javadoc describing each field and its contract. */
public record ChatResponseDto(UUID conversationId, UUID runId, String content, String model, long latencyMs) { }
```

Apply for `Overrides`:
```java
public record Overrides(String model) {
    public static final Overrides NONE = new Overrides(null);
}
```

---

### `AiAgentGuardProperties.java` (configuration record)

**Analog:** `com/vn/agent/rag/config/AiAgentRagProperties.java`

**Nested record + `resolved*` accessors** (AiAgentRagProperties.java lines 43–105):
```java
@ConfigurationProperties("jmix.ai-agent.rag")
public record AiAgentRagProperties(
        Boolean adminBypass, Integer topK, Double similarityThreshold,
        Splitter splitter, EmbedRetry embedRetry, /* … */) {
    public record Splitter(Integer chunkSize, Integer chunkOverlap, Integer minChunkSizeChars) {}
    public record EmbedRetry(Integer maxAttempts, Duration initialInterval, Double multiplier) {}
    public int resolvedTopK() { return topK == null ? 5 : topK; }
    public double resolvedSimilarityThreshold() { return similarityThreshold == null ? 0.50 : similarityThreshold; }
}
```

Apply for `AiAgentGuardProperties` under prefix `jmix.ai-agent.guard` with nested `RateLimit`, `TokenBreaker`, `IterationCap`, `OutputScanner(List<Pattern>)` records (exact shape in RESEARCH Pattern 1).

---

### `GuardedToolCallingManager.java` (delegating wrapper)

**Analog:** `com/vn/agent/audit/ToolCallbackAuditDecorator.java`

**Wrap-delegate with try/finally + AuditWriter + RunContext pattern** (ToolCallbackAuditDecorator.java lines 76–121):
```java
private String callInternal(String toolInput, ToolContext toolContext, boolean useContextOverload) {
    UUID runId = RunContext.get();
    String userUsername = resolveUserUsername();
    UUID conversationId = null;
    String toolName = delegate.getToolDefinition().name();
    long startNanos = System.nanoTime();
    String cappedInput = cap(toolInput, ARGUMENTS_JSON_MAX_CHARS);
    try {
        auditWriter.writeToolCall(runId, userUsername, conversationId, toolName,
                cappedInput, null, 0L, AiToolCallOutcome.SUCCESS, null, null, "PRE");
    } catch (Throwable t) {
        log.warn("Tool PRE audit failed runId={} tool={}", runId, toolName, t);
    }
    boolean success = false; String output = null;
    try {
        output = useContextOverload ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
        success = true; return output;
    } catch (Throwable t) {
        errorClass = t.getClass().getName(); throw t;
    } finally {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditWriter.writeToolCall(runId, userUsername, conversationId, toolName,
                cappedInput, resultSummary, latencyMs, outcome, null, errorClass, "POST");
    }
}
```

Apply: the guard iteration counter + `ToolGuard.check` loop plugs into the same `try`/`finally` discipline. `AuditWriter` calls for iteration-cap / tool-veto denials use the same signature with `toolName="__chat__"` for request-level and the real tool name for tool-level (per D-11).

Note the "constructor injection only, no @Transactional on this class" convention (ToolCallbackAuditDecorator.java lines 24–26) — apply to `GuardedToolCallingManager`.

---

### `OutputScannerAdvisor.java` (CallAdvisor)

**Analog:** `com/vn/agent/audit/AuditAdvisor.java`

**CallAdvisor skeleton** (AuditAdvisor.java lines 44–103):
```java
@Component
public class AuditAdvisor implements CallAdvisor {
    public static final String RUN_ID_CONTEXT_KEY = "audit.runId";
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditAdvisor(AuditWriter auditWriter, CurrentAuthentication currentAuthentication) {
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override public @NonNull String getName() { return "AuditAdvisor"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest request,
                                                   @NonNull CallAdvisorChain chain) {
        Map<String, Object> context = request.context();
        // read keys from context
        try {
            return chain.nextCall(request);
        } catch (Throwable t) { /* … */ throw t; }
        finally { /* cleanup */ }
    }
}
```

**Apply for OutputScannerAdvisor:** same skeleton, with `getOrder() = Ordered.HIGHEST_PRECEDENCE + 400` (higher than AuditAdvisor so scanner runs inner to audit — Pitfall #5). Response manipulation (per AI-SPEC 4.3 / research Example 2):
```java
ChatClientResponse response = chain.nextCall(req);
String assistantText = response.chatResponse().getResult().getOutput().getText();
for (CompiledPattern p : patterns) {
    if (p.regex().matcher(assistantText).find()) {
        response.context().put("outputScanner.flaggedPatternKey", p.key());   // KEY only — D-17
        break;
    }
}
return response;
```

---

### `RateLimitGuard.java` / `TokenBudgetGuard.java` (request-response guards)

**Analog:** `com/vn/agent/rag/RetrievalFilterBuilder.java` (stateless `@Component` with constructor injection)

**Constructor-injected stateless component** — follow the `RetrievalFilterBuilder` + `IngestionStatusWriter` shape:
```java
@Component
public class RateLimitGuard {
    private final CacheManager cacheManager;
    private final AiAgentGuardProperties props;

    public RateLimitGuard(CacheManager cacheManager, AiAgentGuardProperties props) {
        this.cacheManager = cacheManager;
        this.props = props;
    }

    public void check(String username) { /* … */ }
}
```
See RESEARCH Pattern 3 (lines 400–428) for the concrete token-bucket body.

---

### `IterationCounter.java` (ThreadLocal holder)

**Analog:** `com/vn/agent/orchestration/RunContext.java`

**Full shape** (RunContext.java lines 14–28):
```java
public final class RunContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private RunContext() { }
    public static void set(UUID runId) { CURRENT.set(runId); }
    public static UUID get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
```

Apply with `ThreadLocal<Integer>` + `incrementAndGet` / `reset` (or use advisor-context map keyed by runId — RESEARCH open question #3 gives planner discretion).

---

### `DefaultChatServiceImpl` modifications

**Analog:** `DefaultChatServiceImpl.java` (self) — existing `ask(userId, convId, message)` body

**Preserve advisor-context plumbing** (DefaultChatServiceImpl.java lines 105–124):
```java
ChatResponse springResponse = chatClient.prompt()
        .system(composedSystemPrompt)
        .user(message)
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
        .advisors(advisorSpec -> {
            advisorSpec.param(ChatMemory.CONVERSATION_ID, convId.toString())
                       .param("audit.runId", runId);
            if (ragFilter != null) {
                advisorSpec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, ragFilter);
            }
        })
        .options(ChatOptions.builder()
                .model(model)
                .temperature(parametersResolver.effectiveTemperature(active))
                .topP(parametersResolver.effectiveTopP(active))
                .maxTokens(parametersResolver.effectiveMaxTokens(active))
                .build())
        .call()
        .chatResponse();
```

**Modifications:**
- Prepend guard preamble: `rateLimitGuard.check(userId); tokenBudgetGuard.check(convId);`
- Replace `parametersResolver.effectiveModel(active)` with `parametersResolver.effectiveModel(active, overrides)`
- Replace `parametersResolver.effectiveSystemPrompt(active)` with `parametersResolver.effectiveSystemPrompt(active, runContext(userId, convId))`
- Post-response: `tokenBudgetGuard.accumulate(convId, response.getMetadata().getUsage().getTotalTokens());`
- Read `chatClient.prompt()…call().chatClientResponse()` (not `.chatResponse()`) so `ChatClientResponse.context()` is reachable; map scanner flag from context into DTO
- Wrap preamble + body in typed-exception→DTO mapper (D-10)
- Add `askTyped(…)` method per AI-SPEC 3.3 inline retry loop; narrow catch `BeanOutputParseException | ConstraintViolationException`

Preserve existing `safeGetAuthentication` helper (lines 144–150) and D-09 opacity via `ConversationGateway.loadOrCreate` (line 78).

---

### `ChatClientFactory` modifications

**Analog:** `ChatClientFactory.java` (self)

**Advisor list** (ChatClientFactory.java lines 62–66):
```java
return ChatClient.builder(chatModel)
        .defaultSystem(systemPrompt != null ? systemPrompt : AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT)
        .defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor)
        .build();
```

**Modify to inject OutputScannerAdvisor between ToolCallAdvisor and the terminal**:
```java
.defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor, outputScannerAdvisor)
```

Inject `GuardedToolCallingManager` instead of the raw `ToolCallingManager` bean (or qualify by name per RESEARCH Pattern 2 caveat about `@Primary`).

Optionally wire `StructuredOutputValidationAdvisor` via `@ConditionalOnClass` — AI-SPEC 4.5 has the full snippet.

---

### `AiParametersResolver` modifications

**Analog:** `AiParametersResolver.java` (self)

**Existing `effectiveModel` signature** (AiParametersResolver.java lines 73–86) is the model for new `effectiveModel(AiParameters, Overrides)`:
```java
public String effectiveModel(AiParameters params) {
    String body = params.getBodyYaml();
    String model;
    if (body != null && !body.isBlank()) {
        Object v = parseBody(params).get("model");
        model = v != null ? String.valueOf(v) : defaults.model();
    } else {
        model = defaults.model();
    }
    if (model == null || !model.contains("/")) {
        throw new IllegalStateException("Model id must follow OpenRouter slug format provider/model: " + model);
    }
    return model;
}
```

**Add overload:**
```java
public String effectiveModel(AiParameters params, Overrides overrides) {
    if (overrides != null && overrides.model() != null && !overrides.model().isBlank()) {
        // reuse slug validation
        if (!overrides.model().contains("/")) {
            throw new IllegalStateException("Model id must follow OpenRouter slug format provider/model: " + overrides.model());
        }
        return overrides.model();
    }
    return effectiveModel(params);
}
```

**Modify `effectiveSystemPrompt`** (lines 115–122) to add a `RunContext`-arg overload that concatenates `PromptContextContributor.fragment()` in `@Order` sequence (D-08).

---

### `ChatResponseDto` modification

**Analog:** `ChatResponseDto.java` (self) — extend the record.

**Existing shape:**
```java
public record ChatResponseDto(UUID conversationId, UUID runId, String content,
                              String model, long latencyMs) { }
```

**Extend (research Example 5):**
```java
public record ChatResponseDto(
        UUID conversationId, UUID runId, String content, String model, long latencyMs,
        boolean flagged, String flaggedPatternKey, GuardDenialInfo guardDenial) {
    public record GuardDenialInfo(String messageKey, Map<String, Object> params) {}
    public static ChatResponseDto denied(UUID convId, UUID runId, String msgKey, Map<String, Object> params) {
        return new ChatResponseDto(convId, runId, "", null, 0L, false, null,
                new GuardDenialInfo(msgKey, params));
    }
}
```

Callers that build the happy-path DTO at DefaultChatServiceImpl.java line 135 must be updated to the new arity.

---

### Exception classes (Rate/Token/Iteration/StructuredOutput)

**Analog:** `com/vn/agent/spi/ToolVetoedException.java`

**Full shape** (ToolVetoedException.java lines 11–14):
```java
public class ToolVetoedException extends RuntimeException {
    public ToolVetoedException(String message) { super(message); }
    public ToolVetoedException(String message, Throwable cause) { super(message, cause); }
}
```

Apply per guard. `StructuredOutputException` adds `private final String rawText; private final Class<?> targetType;` getters (D-19 contract).

---

### `AiAgentGuardAutoConfiguration.java` (starter autoconfig)

**Analog:** `com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` + `AIAutoConfiguration`

**@AutoConfiguration + @ConditionalOnMissingBean per extensible bean** (SpiDefaultsAutoConfiguration.java lines 29–71):
```java
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class SpiDefaultsAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public ToolGuard defaultToolGuard() { return (toolName, arguments) -> { /* allow all */ }; }
    // …
}
```

**Apply for guard autoconfig:**
- Default `CacheManager` (`ConcurrentMapCacheManager` with the two named caches from D-12)
- Default `AiAgentGuardProperties` (will be picked up via `@ConfigurationPropertiesScan` already on `AIConfiguration`)
- Optional `StructuredOutputValidationAdvisor` (`@ConditionalOnClass`)

Register in starter `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` next to existing entries.

---

### Liquibase changelog (only if enum gains `FLAGGED`)

**Analog:** `liquibase/changelog/080-ai-tool-call-audit-runid.xml`

**Naming convention:** next sequence number `090-…`; **include in `changelog.xml`** (line 32 precedent):
```xml
<include file="/com/vn/agent/liquibase/changelog/080-ai-tool-call-audit-runid.xml"/>
```

D-17 leaves this as planner discretion (enum value vs side column). If side-column is chosen, use the `080-…` shape; if enum only, no changelog is needed since `OUTCOME` is already `VARCHAR(16)` — but planner must add `FLAGGED` to the Java enum and i18n locale files.

---

## Shared Patterns

### Audit writing for guard denials
**Source:** `com/vn/agent/audit/AuditWriter.java`
**Apply to:** `GuardedToolCallingManager` (tool-veto, iteration-cap), `DefaultChatServiceImpl` guard preamble (rate-limit, token-breaker), `OutputScannerAdvisor` (FLAGGED)

Reuse `AuditWriter.writeToolCall(runId, userUsername, conversationId, toolName, argumentsJson, resultSummary, latencyMs, outcome, denialReason, errorClass, phase)` **directly** (AuditWriter.java lines 123–168). For request-level denials pass `toolName = "__chat__"` (D-11 synthetic reserved name); for tool-level veto pass the real `@Tool` method name. `denialReason` MUST be a stable key (`"rate-limit-exceeded"`, `"token-budget-exhausted"`, `"iteration-cap-exceeded"`, `"tool-vetoed:<stableKey>"`, `"flagged:<patternKey>"`), **not** any ceiling value, bucket id, or matched-text payload (Pitfall #4 / D-17).

Existing sentinel for chat-level rows is `CHAT_TOOL_NAME_SENTINEL = "<chat>"` (AuditWriter.java line 42) — the new `__chat__` (underscore-prefix) is **orthogonal**, deliberately distinct so guard rows are filterable from chat-level rows.

### Constructor injection (forbidden alternatives)
**Source:** CLAUDE.md + every existing `@Service`/`@Component` in the codebase (e.g. `AuditAdvisor` lines 58–61, `KnowledgeDocumentService` lines 60–70, `AiParametersResolver` lines 34–40)
**Apply to:** All new `@Service` / `@Component` classes in Phase 6
- No field injection, no setter injection
- No Lombok on entities
- No `EntityManager` — always `DataManager`
- No entity constructor — always `metadata.create(Class)` (see `AuditWriter.writeChatPre` line 60)

### i18n keys in BOTH locales
**Source:** `resources/com/vn/agent/messages.properties` + `messages_vi.properties`
**Apply to:** Every `msg://ai-agent.guard.*` and `msg://ai-agent.parameters.*` key

**Existing key block style** (messages.properties lines 17–19, messages_vi.properties lines 17–19):
```properties
com.vn.agent.entity/AiToolCallOutcome.SUCCESS=Success
com.vn.agent.entity/AiToolCallOutcome.BLOCKED=Blocked
com.vn.agent.entity/AiToolCallOutcome.ERROR=Error
```

Phase 6 must add (example):
```properties
ai-agent.guard.rate-limit-exceeded=Too many requests — please wait before trying again.
ai-agent.guard.token-budget-exhausted=This conversation has reached its token limit. Start a new chat to continue.
ai-agent.guard.iteration-cap-exceeded=The assistant could not complete the task within the allowed number of steps.
ai-agent.guard.tool-vetoed=This action was blocked by policy.
ai-agent.guard.output-flagged=This response was flagged — review carefully.
ai-agent.parameters.model.required=Model is required.
ai-agent.parameters.maxTokens.min=maxTokens must be at least {0}.
ai-agent.parameters.maxTokens.max=maxTokens must not exceed {0}.
```
…with exact Vietnamese equivalents in `messages_vi.properties`. E-11 parity test enforces (AI-SPEC Pitfall #8).

If `AiToolCallOutcome` gains `FLAGGED`, add:
```properties
com.vn.agent.entity/AiToolCallOutcome.FLAGGED=Flagged    # messages.properties
com.vn.agent.entity/AiToolCallOutcome.FLAGGED=Đã gắn cờ  # messages_vi.properties
```

### Plug-and-play defaults via `@ConditionalOnMissingBean`
**Source:** `SpiDefaultsAutoConfiguration` + `AIAutoConfiguration.aiAgentVectorStore`
**Apply to:** Every new extensible bean (CacheManager fallback, output-scanner pattern list, optional StructuredOutputValidationAdvisor)

Exact shape from `SpiDefaultsAutoConfiguration.java` lines 52–55:
```java
@Bean
@ConditionalOnMissingBean
public ToolGuard defaultToolGuard() {
    return (toolName, arguments) -> { /* allow all */ };
}
```

### Advisor context for per-turn state
**Source:** `DefaultChatServiceImpl.java` lines 109–116 + `AuditAdvisor.RUN_ID_CONTEXT_KEY`
**Apply to:** `OutputScannerAdvisor` flag key, `GuardedToolCallingManager` iteration counter

Existing keys:
- `audit.runId` (AuditAdvisor line 50)
- `chat_memory_conversation_id` (AuditAdvisor line 53)
- `VectorStoreDocumentRetriever.FILTER_EXPRESSION`

Add (Phase 6):
- `outputScanner.flaggedPatternKey`
- `guard.iterationCounter` (or plan uses ThreadLocal helper — open question #3)

### ThreadLocal lifecycle (set → try → finally clear)
**Source:** `AuditAdvisor.adviseCall` lines 81–102 — `RunContext.set(runId); try {…} finally { RunContext.clear(); }`
**Apply to:** `IterationCounter` ThreadLocal, any Phase 6 ThreadLocal-backed state.

### REQUIRES_NEW transactional writer (durability across rollback)
**Source:** `AuditWriter` (entire class, lines 37–168) + `IngestionStatusWriter` (entire class, lines 41–128)
**Apply to:** No new writer needed in Phase 6 — guard denials reuse `AuditWriter.writeToolCall` directly (D-11).

If retry-attempt metadata adds a new writer (D-20 open), copy `IngestionStatusWriter` shape exactly — class javadoc calls this out explicitly at lines 22–29.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `ai-agent-starter/src/main/resources/default-params.yaml` | config resource | - | No prior bundled YAML resource in the starter. Shape is trivial — a YAML serialisation of `AiParametersBody` with default values (`model: openai/gpt-4o-mini`, `temperature: 0.2`, `maxTokens: 2048`, `topP: 1.0`, `systemPrompt: "You are a helpful assistant."`). No analog needed — planner composes from the DTO schema. |

---

## Metadata

**Analog search scope:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/{orchestration,audit,rag,entity,spi,tools}/**`
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/**`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/{liquibase,messages*.properties}`

**Files scanned:** 26 Java sources + 2 locale files + 1 changelog index + starter autoconfig trio

**Pattern extraction date:** 2026-04-21

**Key insight for planner:** Phase 6 is composition over creation. Every Phase 6 surface has a strong existing analog except `default-params.yaml` and the DTO-with-Jakarta-Validation body (which has a template in AI-SPEC 4b.1 but no codebase analog). The three "unavoidable hand-rolled primitives" flagged by RESEARCH (`GuardedToolCallingManager`, `OutputScannerAdvisor`, inline `askTyped` retry) each have an exact wrap-delegate / advisor / try-catch-narrow analog in `ToolCallbackAuditDecorator` and `AuditAdvisor` respectively.
