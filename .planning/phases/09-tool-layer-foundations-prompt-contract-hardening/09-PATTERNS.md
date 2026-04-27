# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening — Pattern Map

**Mapped:** 2026-04-27
**Files analyzed:** 16 (10 modified, 6 new)
**Analogs found:** 16 / 16

---

## File Classification

### Modified files (extend in place)

| File | Role | Data Flow | Closest Analog | Match Quality |
|------|------|-----------|----------------|---------------|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` | prompt assembly | request-scoped composition | self (existing `compose` / `renderAsText`) | exact (extension site) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` | Spring AI `@Tool` host | request-response | self (existing `describeEntity` / `resolveReadableEntityOrThrow`) | exact (extension site) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java` | DTO renderer | request-response | self (existing `describe` / `records`) | exact (extension site) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java` | `@ConfigurationProperties` | config | self (existing `OutputScanner` nested record + `resolvedPatterns()`) | exact (extension site) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java` | Spring AI `CallAdvisor` | response post-processing | self (existing `adviseCall` regex loop) | exact (no logic change; pattern source widens) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` | chat orchestrator | request-response | self (existing `ask` system-prompt composition lines 199–207) | exact (extension site) |
| `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` | autoconfig | startup | self (existing `defaultToolContributor` / `defaultToolGuard`) | exact |
| `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` | autoconfig | startup | self (existing `outputScannerAdvisor` bean) | exact |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` + `messages_vi.properties` | i18n | static | existing message-bundle keys (Jmix `Messages`) | exact |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java` | unit test | n/a | self (existing `composeRendersAsTextWithSortedAgentKeys`) | exact (extension) |

### New files

| File | Role | Data Flow | Closest Analog | Match Quality |
|------|------|-----------|----------------|---------------|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` | host SPI interface | per-request override | `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` | exact (sibling SPI) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` | SPI param record | per-request | `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` (carrier shape) | role-match |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` | internal helper | request-scoped transform | `BuiltInDataTools.getRelatedRecords` (lines 229–251 — existing `FetchPlans.builder + addFetchPlan`) and `CurrentUserSchemaAccess.canReadAttribute` (line 64) | exact composite |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java` | startup component | startup-scoped derivation | `OutputScannerAdvisor` ctor (lines 56–69) + `ToolContributor` chain pattern | role-match (new shape) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java` | static utility | pure function | none in repo — fresh utility (closest shape: `ToolResultFormatter.escapeDataDelimiters` lines 239–246, static helper) | role-match |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java` | `@ConfigurationProperties` | config | `AiAgentGuardProperties` (record + `resolved*` accessors) | exact |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java` | `@SpringBootTest` integration test | n/a | `OrchestrationIntegrationTest` (lines 36–41) + `OutputScannerAdvisorTest` corpus loop | exact composite |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/live/PromptContractLiveTest.java` | `@Tag("live")` opt-in | n/a | `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java` | exact (sibling live test) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/DescribeEntityPayloadTest.java` | unit test | n/a | `ToolResultFormatterTest` (lines 26–35 — Mockito-based formatter) | exact |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java` | unit test | n/a | `ToolResultFormatterTest` + `BuiltInDataToolsReadOnlyTest` patterns | role-match |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java` | unit test | n/a | `ToolResultFormatterTest.errorFromToolUserErrorSerializesExpectedList` (lines 67–76) | exact |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditFieldHasherTest.java` | unit test | n/a | `ToolResultFormatterTest` (pure-function unit pattern) | role-match |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/HostPrefixLeakScannerTest.java` | unit test | n/a | `OutputScannerAdvisorTest` (lines 33–67 — corpus + `@ParameterizedTest`) | exact |

---

## Pattern Assignments

### `BaselineContextProvider.java` (prompt assembly, request-scoped composition) — EXTEND

**Analog:** self — `BaselineContextProvider.java` lines 37–96 (existing `compose` + `renderAsText`).

**Class shape and DI** (lines 37–44):
```java
@Component
public class BaselineContextProvider {

    private final CurrentAuthentication currentAuthentication;

    public BaselineContextProvider(CurrentAuthentication currentAuthentication) {
        this.currentAuthentication = currentAuthentication;
    }
```
Pattern to copy: stateless `@Component`, constructor injection only (per CLAUDE.md "Constructor injection only" for services). Phase 9 adds three more constructor-injected collaborators: `CurrentUserSchemaAccess`, `MessageTools`, `AccessManager`, plus the new `AiAgentPromptProperties` (or whichever properties record the planner picks under `jmix.ai-agent.prompt.*`).

**Compose-method extension site** (lines 46–56):
```java
public Map<String, Object> compose(UUID conversationId) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    UserDetails user = safeGetUser();
    ctx.put("agent.userId", user != null ? extractUserKey(user) : null);
    ctx.put("agent.username", user != null ? user.getUsername() : "");
    ctx.put("agent.roles", user != null ? rolesOf(user) : Set.of());
    Locale locale = safeGetLocale();
    ctx.put("agent.locale", locale != null ? locale.toString() : Locale.ROOT.toString());
    ctx.put("agent.conversationId", conversationId != null ? conversationId.toString() : null);
    return ctx;
}
```
PROMPT-01/PROMPT-02 add two more `ctx.put(...)` calls on the same `LinkedHashMap` — `agent.entities` (D-01) and `agent.permissions` (D-02). Empty schema means key omitted entirely (Locked D-01 wording).

**Permission-map source** (locale-free key construction): copy from `CurrentUserSchemaAccess.java` lines 64–69:
```java
public boolean canReadAttribute(MetaClass metaClass, String attributePath) {
    EntityAttributeContext attributeAccessContext =
            new EntityAttributeContext(metaClass, attributePath);
    accessManager.applyRegisteredConstraints(attributeAccessContext);
    return attributeAccessContext.canView();
}
```
Same `AccessManager.applyRegisteredConstraints` pattern is reused for `CrudEntityContext` (CRUD bits) and `EntityAttributeContext.canModify()` (the `modifiable` set per D-02). MetaClass name (`mc.getName()`) is locale-stable; `MessageTools.getEntityCaption(mc)` is the locale-resolved label per render — locale labels MUST NOT enter cache keys (Pitfall 1 in RESEARCH).

**Determinism contract** — copy `TreeMap` use from existing `renderAsText` (line 65):
```java
Map<String, Object> sorted = new TreeMap<>(compose(conversationId));
```
Phase 9 emits `agent.permissions` as a `TreeMap<String, ...>` with `TreeSet<String>` modifiable inside, mirroring the same alpha-ordering invariant the prompt tests already assume.

**No-cache rule** — copy the Javadoc constraint from `CurrentUserSchemaAccess.java` lines 17–20:
```java
 * Stateless {@link Component} — {@code AccessManager} resolves the caller's authentication per
 * call, so there is no class-level cache (per threat model T-03-01 + Open-Question #6
 * recommendation).
```
Phase 9 baseline extension MUST stay no-cache (PROMPT-02 + RESEARCH Pitfall 1).

---

### `BuiltInDataTools.java` (Spring AI `@Tool` host, request-response) — EXTEND

**Analog:** self — `BuiltInDataTools.java` (existing six `@Tool` methods).

**`@Tool` annotation pattern** (lines 80–82, 100–104):
```java
@Tool(name = "describe_entity",
        description = "Describe an entity's attributes, types, constraints, relationships, and enum values.")
public String describeEntity(
        @ToolParam(description = "Jmix entity name from list_entities, e.g. 'jmixapp_Order'")
        String entityName) {
```
Pattern to copy verbatim: `@Tool` + `@ToolParam` shape. Body returns `String` (JSON via `ToolResultFormatter`); errors are caught at the boundary and converted via `toolResultFormatter.error(toolUserError)` (lines 112–114).

**Tool-error envelope pattern** (lines 105–115):
```java
try {
    MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
    Set<String> readableAttributeNames = currentUserSchemaAccess.getReadableSchema().get(metaClass);
    if (readableAttributeNames == null) {
        throw new ToolUserError("access_denied", "no read access to " + entityName);
    }
    return toolResultFormatter.describe(metaClass, readableAttributeNames);
} catch (ToolUserError toolUserError) {
    return toolResultFormatter.error(toolUserError);
}
```
This is the canonical try/catch shape the new `unknown_entity` retry hints (D-14) plug into.

**`unknown_entity` extension site** (lines 264–283 — `resolveReadableEntityOrThrow`):
```java
private MetaClass resolveReadableEntityOrThrow(String entityName) {
    if (entityName == null || entityName.isBlank()) {
        throw new ToolUserError("unknown_entity", "entity name must not be blank");
    }

    MetaClass metaClass;
    try {
        metaClass = metadata.getClass(entityName);
    } catch (RuntimeException runtimeException) {
        throw new ToolUserError("unknown_entity", "no entity named " + entityName);
    }

    if (metaClass == null) {
        throw new ToolUserError("unknown_entity", "no entity named " + entityName);
    }
    if (!currentUserSchemaAccess.canReadEntity(metaClass)) {
        throw new ToolUserError("access_denied", "no read access to " + entityName);
    }
    return metaClass;
}
```
Phase 9 modification: switch the three `unknown_entity` `ToolUserError(...)` constructors to the three-arg form that takes `expected: List<String>` — the constant `UNKNOWN_ENTITY_HINTS` defined per D-14:
```java
private static final List<String> UNKNOWN_ENTITY_HINTS = List.of(
        "call list_entities exactly once",
        "if a name in list_entities matches your intent, retry the original tool with that exact name",
        "if no entity in list_entities matches, tell the user no such entity exists — do not guess"
);
```
**Strings are NOT translated** (RESEARCH Pitfall 7). They are tool-protocol English, hardcoded in the class. Do NOT route through `Messages.getMessage(...)`.

**`access_denied` opacity preserved** — keep the existing `access_denied` error code; Phase 3 D-08's "denied entity = unknown entity" opacity rule remains in force (RESEARCH §"`access_denied` opacity preserved").

**Fetch-plan resolution insertion site** (lines 133–146 in `findRecords`, lines 184–188 in `getRecord`, lines 229–235 in `getRelatedRecords`):
```java
rows = dataManager.load(metaClass.getJavaClass())
        .all()
        .fetchPlan(FetchPlan.BASE)
        .maxResults(clampedLimit + 1)
        .list();
```
Phase 9 replaces the literal `FetchPlan.BASE` in these three call sites with the result of a new resolver call:
```java
FetchPlan plan = fetchPlanResolver.resolve("find_records", metaClass, fetchPlanContextFor(metaClass));
```
The resolver chain is: (1) iterate `List<ToolFetchPlanCustomizer>`, first non-empty `Optional` wins; (2) fall back to `FetchPlan.BASE`; (3) ALWAYS pipe through `FetchPlanIntersector.intersectWithAcl(plan, mc, toolName)` BEFORE `DataManager.load(...)`. See SPI design below.

**`describe_entity` payload-widening** (TOOL-09 / D-04) is implemented in `ToolResultFormatter.describe(...)`, NOT in `BuiltInDataTools` directly — keep that boundary.

**Excluded-fields Javadoc (D-05)** — add Javadoc to `describeEntity(...)` only, listing: DDL column names, JPA fetch type, cascade rules, raw annotations, internal store name, framework-managed audit columns. Do NOT echo into the JSON.

---

### `ToolResultFormatter.java` (DTO renderer, request-response) — EXTEND

**Analog:** self — `ToolResultFormatter.java` (existing `describe`, `records`, helpers).

**Existing `describe` extension site** (lines 76–89):
```java
public String describe(MetaClass metaClass, Set<String> readableAttributeNames) {
    List<AttributeDescription> attributeDescriptions = new ArrayList<>();
    for (MetaProperty metaProperty : metaClass.getProperties()) {
        if (!readableAttributeNames.contains(metaProperty.getName())) {
            continue;
        }
        attributeDescriptions.add(buildAttributeDescription(metaProperty));
    }
    return writeJson(new DescribeEntityResult(
            metaClass.getName(),
            messageTools.getEntityCaption(metaClass),
            attributeDescriptions
    ));
}
```
Phase 9 widens the `DescribeEntityResult` and `AttributeDescription` records to carry the D-04 fields. The walk shape (`for (MetaProperty ...)` + `readableAttributeNames` filter) stays identical.

**Existing `buildAttributeDescription` extension site** (lines 148–164):
```java
private AttributeDescription buildAttributeDescription(MetaProperty metaProperty) {
    Range range = metaProperty.getRange();
    List<String> enumValueNames = range.isEnum() ? enumValueNames(range) : null;
    String relationshipTarget = range.isClass() ? range.asClass().getName() : null;
    Integer maxLength = range.isDatatype() && range.asDatatype().getJavaClass() == String.class
            ? columnLength(metaProperty)
            : null;
    return new AttributeDescription(
            metaProperty.getName(),
            typeLabel(metaProperty),
            !metaProperty.isMandatory(),
            messageTools.getPropertyCaption(metaProperty),
            enumValueNames,
            relationshipTarget,
            maxLength
    );
}
```
D-04 changes:
- `enumValues` becomes `[{name, label}]` not `[name]`. Use `messages.getMessage((Enum<?>) value)` per RESEARCH Pitfall 10.
- `relationshipTarget` becomes `{name, label}` not `String`. Mirror `agent.entities` shape: `new EntityRef(targetMetaClass.getName(), messageTools.getEntityCaption(targetMetaClass))`.
- Add `cardinality = range.getCardinality().name()` (raw enum string per D-04).
- Add `mandatory = metaProperty.isMandatory()` (note: existing `nullable = !mandatory` becomes `mandatory` directly).
- Add `readOnly = metaProperty.isReadOnly()`, `persistent = metadataTools.isJpa(metaProperty)`, `transientProperty = !persistent`, `primaryKey = metadataTools.isPrimaryKey(metaProperty)` (or compare to `metadataTools.getPrimaryKeyProperty(metaClass)`).
- Add `comment = metadataTools.getMetaAnnotationValue(metaProperty, Comment.class)` per D-04 (NOT reflection — RESEARCH "Don't Hand-Roll" + Pitfall 3). Same accessor for entity-level comment via `metadataTools.getMetaAnnotationValue(metaClass, Comment.class)`.

**`columnLength` precedent** (lines 99–109) — the ONLY allowed reflection in `tools/`:
```java
private Integer columnLength(MetaProperty metaProperty) {
    java.lang.reflect.AnnotatedElement annotatedElement = metaProperty.getAnnotatedElement();
    if (annotatedElement == null) return null;
    jakarta.persistence.Column column = annotatedElement.getAnnotation(jakarta.persistence.Column.class);
    if (column == null) return null;
    int length = column.length();
    return length == 255 ? null : length;
}
```
This is whitelisted because `@Column.length` has no `MetadataTools` accessor (RESEARCH "Don't Hand-Roll"). Reuse without modification.

**`records` PROMPT-04 extension site** (lines 117–128):
```java
public String records(List<?> rows, MetaClass metaClass, int limit, boolean truncated) {
    String hint = truncated
            ? "result was truncated to the limit; call count_records for the exact total or narrow the filter"
            : null;
    return writeJson(new RecordsResult(
            metaClass.getName(),
            serializeRows(rows, metaClass),
            limit,
            truncated,
            hint
    ));
}
```
PROMPT-04 reshapes the wrapper element only (label first, internal name second). The `RecordsResult` record shape changes from `(entityName, rows, limit, truncated, hint)` to `(entity, type, rows, limit, truncated, hint)` — `entity = messageTools.getEntityCaption(metaClass)` (locale-resolved label), `type = metaClass.getName()` (internal name). XML-shape `<data entity="<label>" type="<internalName>">` per AI-SPEC §4 "Tool Result Formatting".

**Untrusted-data wrap precedent** (lines 230–246) — reuse without change:
```java
private String wrapUntrustedText(String value) {
    return "<data>" + escapeDataDelimiters(value) + "</data>";
}

static String escapeDataDelimiters(String value) {
    if (value == null) {
        return null;
    }
    return value
            .replace("<data>", "&lt;data&gt;")
            .replace("</data>", "&lt;/data&gt;");
}
```
The existing `<data>` wrapper for entity-string values is the prompt-injection isolation layer; PROMPT-04 only relabels the OUTER wrapper element, not the inner per-attribute wrap.

---

### `OutputScannerAdvisor.java` (Spring AI `CallAdvisor`, response post-processing) — UNCHANGED logic, pattern source widens

**Analog:** self — `OutputScannerAdvisor.java` lines 56–106.

**Constructor compile-once pattern** (lines 56–69):
```java
public OutputScannerAdvisor(AiAgentGuardProperties props) {
    this.props = props;
    this.patterns = props.resolvedPatterns().stream()
            .map(raw -> {
                try {
                    return CompiledOutputScannerPattern.from(raw);
                } catch (PatternSyntaxException bad) {
                    log.warn("Skipping invalid scanner regex key={}: {}", raw.key(), bad.getMessage());
                    return null;
                }
            })
            .filter(p -> p != null)
            .toList();
}
```
**Phase 9 keeps this unchanged.** New host-prefix and tool-name patterns are appended to `props.resolvedPatterns()` via `AiAgentGuardProperties` extension (D-08) — the consumer side has zero-line diff. The new patterns flow through the same `CompiledOutputScannerPattern` compile-once cache.

**Adviser hook signature** (lines 81–107):
```java
@Override
public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest request,
                                               @NonNull CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    if (!props.outputScannerEnabled() || response == null) {
        return response;
    }
    // ...
    String scanned = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
    for (CompiledOutputScannerPattern p : patterns) {
        if (p.pattern().matcher(scanned).find()) {
            writeFlag(response, p.key());
            break; // first-match wins — one FLAGGED audit row per turn
        }
    }
    return response;
}
```
Spring AI 1.1.4 advisor surface to copy: `@Override adviseCall(ChatClientRequest, CallAdvisorChain)` returning `ChatClientResponse`. `getOrder() = HIGHEST_PRECEDENCE + 400`. **Flag-and-pass-through; never block.** New patterns inherit this posture (D-08 / PROMPT-06 explicit wording).

**Audit-key contract — only KEY, never matched text** (lines 109–120 — `writeFlag`):
```java
private static void writeFlag(ChatClientResponse response, String patternKey) {
    try {
        Map<String, Object> context = response.context();
        if (context != null) {
            context.put(CONTEXT_KEY_FLAGGED_PATTERN, patternKey);
        }
    } catch (Exception ex) {
        log.debug("Failed to write scanner flag to response context (pattern key={})", patternKey, ex);
    }
}
```
**Critical contract:** stable pattern key (`HOST_PREFIX_LEAK`, `TOOL_NAME_LEAK`) flows into context map. Raw matched text NEVER persisted (D-17/D-18; RESEARCH "Anti-Patterns: Echoing the matched leak text into the audit row").

---

### `AiAgentGuardProperties.java` (`@ConfigurationProperties`, config) — EXTEND

**Analog:** self — `AiAgentGuardProperties.java`.

**Record + nested-record + `resolved*` accessor pattern** (lines 29–104):
```java
@ConfigurationProperties("jmix.ai-agent.guard")
public record AiAgentGuardProperties(
        RateLimit rateLimit,
        TokenBreaker tokenBreaker,
        IterationCap iterationCap,
        OutputScanner outputScanner) {

    /** Output-side injection-pattern scanner config (D-17/D-18, GUARD-05). */
    public record OutputScanner(Boolean enabled, List<Pattern> patterns) {

        /** One scanner pattern: a stable {@code key} for audit rows + the regex {@code source}. */
        public record Pattern(String key, String regex) {
        }
    }

    public boolean outputScannerEnabled() {
        return outputScanner == null || !Boolean.FALSE.equals(outputScanner.enabled());
    }

    public List<OutputScanner.Pattern> resolvedPatterns() {
        if (outputScanner == null || outputScanner.patterns() == null || outputScanner.patterns().isEmpty()) {
            return List.of(
                    new OutputScanner.Pattern("IGNORE_PREVIOUS_INSTRUCTIONS", "(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
                    new OutputScanner.Pattern("SYSTEM_TAG_LEAK", "(?i)</?system>"),
                    new OutputScanner.Pattern("ROLE_BREAK", "(?i)(^|\\s)(assistant:|user:).{0,2048}?(assistant:|user:)")
            );
        }
        return outputScanner.patterns();
    }
}
```
Pattern to copy:
- Top-level `@ConfigurationProperties("jmix.ai-agent.<area>")` on a Java record. Picked up by `@ConfigurationPropertiesScan` already in `AIConfiguration` — no extra registration.
- `Boolean enabled` defaults to `true` when omitted via the `!Boolean.FALSE.equals(...)` idiom.
- `resolved*()` accessor returns the bundled defaults when absent.

**Phase 9 extension** — D-08 adds two `Boolean enabled` toggles under nested records `OutputScanner.HostPrefixLeak` and `OutputScanner.ToolNameLeak` (or sibling property keys — planner picks per "Claude's Discretion"). The dynamic patterns themselves are NOT static defaults inside `resolvedPatterns()` (because they depend on `Metadata.getSession()` and `AgentToolCallbacks` — runtime values). Two new `@Component` providers (`HostPrefixPatternProvider`, `ToolNamePatternProvider`) build the patterns at `ApplicationReadyEvent` time and feed them to `OutputScannerAdvisor` via a small helper or by widening `resolvedPatterns()` to merge a runtime list. **Planner picks the wiring shape** (this is in CONTEXT.md "Claude's Discretion"); the nested-record + `resolved*` skeleton is the analog to copy.

**New `AiAgentAuditProperties` record** — same pattern, prefix `jmix.ai-agent.audit`:
```java
@ConfigurationProperties("jmix.ai-agent.audit")
public record AiAgentAuditProperties(
        Boolean hashSensitiveFields,
        Set<String> sensitiveFields) {

    public boolean resolvedHashSensitiveFields() {
        return hashSensitiveFields == null || !Boolean.FALSE.equals(hashSensitiveFields);
    }

    public Set<String> resolvedSensitiveFields() {
        return sensitiveFields == null ? Set.of() : Set.copyOf(sensitiveFields);
    }
}
```
Mirrors `AiAgentGuardProperties` byte-for-byte. Default `hashSensitiveFields=true`, default `sensitiveFields=Set.of()` per D-18.

---

### `DefaultChatServiceImpl.java` (chat orchestrator, request-response) — EXTEND

**Analog:** self — `DefaultChatServiceImpl.java`.

**System-prompt composition extension site** (lines 197–207):
```java
AiParameters active = parametersResolver.resolveActive();
String model = parametersResolver.effectiveModel(active, effectiveOverrides);
String profileSystemPrompt = parametersResolver.effectiveSystemPrompt(
        active, userId, convId, runId);

// B5 + B-NEW-1: baseline as deterministic TEXT (D-15) prepended to profile prompt.
String baselineText = baselineContextProvider.renderAsText(convId);
String composedSystemPrompt = baselineText
        + (profileSystemPrompt != null && !profileSystemPrompt.isBlank()
                ? "\n\n" + profileSystemPrompt
                : "");
```
PROMPT-03 + PROMPT-05/D-15 append the system-prompt rules here:
- Forbid host-prefix entity names (`jmixapp_Customer` etc.) and tool names (`find_records` etc.) in user-facing replies.
- Surface the same `unknown_entity` retry contract as a global rule (so the LLM knows the contract before any tool fires).

**Locale-free system-prompt rules** — these strings are NOT user-facing UI; they are model-directed instructions. Per the precedent `AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant."` (`AiAgentDefaultsProperties.java` line 30):
> Kept non-i18n intentionally (LO-01): this is a model-directed instruction, not a user-facing UI string, so `msg://` keys do not apply.

So PROMPT-03 / PROMPT-05 system-prompt rules are hardcoded English constants on `DefaultChatServiceImpl` (or a new package-private constant class). NOT routed through `Messages`.

**ChatClient prompt invocation pattern** (lines 227–250) — unchanged in Phase 9:
```java
ChatClientResponse clientResp = chatClient.prompt()
        .system(composedSystemPrompt)
        .user(message)
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
        .toolContext(auditToolContext(runId, convId))
        .advisors(advisorSpec -> { ... })
        .options(ChatOptions.builder()
                .model(model)
                .temperature(parametersResolver.effectiveTemperature(active))
                .topP(parametersResolver.effectiveTopP(active))
                .maxTokens(parametersResolver.effectiveMaxTokens(active))
                .build())
        .call()
        .chatClientResponse();
```
Reference for AI-SPEC §3 "Entry Point Pattern". Per-request `toolCallbacks(...)`, NOT `defaultToolCallbacks(...)` (RESEARCH Common Pitfall #1).

---

### `ToolFetchPlanCustomizer.java` (host SPI interface) — NEW

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` lines 1–31.

**Sibling SPI shape**:
```java
package com.vn.agent.spi;

import java.util.Map;

/**
 * Host extension point that can veto a tool invocation before it runs.
 * <p>Multiple guards compose by short-circuit AND: any guard throwing {@link ToolVetoedException}
 * blocks the call and produces an {@code AiAuditEvent} row with
 * {@code outcome = BLOCKED} and the thrown message captured as {@code denialReason}.</p>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * @Component
 * class BusinessHoursGuard implements ToolGuard {
 *     @Override
 *     public void check(String toolName, Map<String, Object> arguments) {
 *         if ("issueRefund".equals(toolName) && LocalTime.now().isAfter(LocalTime.of(18, 0))) {
 *             throw new ToolVetoedException("Refunds disabled outside business hours");
 *         }
 *     }
 * }
 * }</pre>
 */
public interface ToolGuard {
    void check(String toolName, Map<String, Object> arguments) throws ToolVetoedException;
}
```
Patterns to copy:
- Package `com.vn.agent.spi`.
- Single-method interface with extensive Javadoc including a `@Component`-using example.
- Discovered by Spring as `List<ToolGuard>` injection; default no-op bean lives in `SpiDefaultsAutoConfiguration`.

**Phase 9 SPI signature** (locked per D-09):
```java
public interface ToolFetchPlanCustomizer {
    /**
     * Optionally override the fetch plan for a built-in or host-contributed tool invocation.
     *
     * <p><b>fetch plan is projection, not security.</b> The returned plan is intersected with the
     * current user's readable-attribute set BEFORE {@code DataManager.load(...)} sees it. Denied
     * properties are silently dropped (audit row {@code outcome=PLAN_NARROWED}).</p>
     *
     * @return {@code Optional.empty()} to fall through to the next customizer, or to the
     *         add-on default {@code FetchPlan.BASE}.
     */
    Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context);
}
```

**`ContextContributor` precedent for "do NOT touch agent.* keys" Javadoc** — `BaselineContextProvider.java` lines 16–19 documents this kind of contract; the Phase 9 SPI Javadoc must include the equivalent "fetch plan does NOT replace `_instance_name`" carve-out per D-13.

---

### `FetchPlanContext.java` (SPI param record) — NEW

**Analog:** `RunContext.java` (carrier shape concept) and `AiAgentGuardProperties` (record idiom).

**Pattern to copy** (D-10 locked):
```java
public record FetchPlanContext(RunContext run, UserDetails user) {
}
```
Where `RunContext` is the existing carrier from `com.vn.agent.orchestration.RunContext` (already carries conversationId + retrieval params), and `UserDetails` is `org.springframework.security.core.userdetails.UserDetails` (the same shape `BaselineContextProvider` reads via `currentAuthentication.getUser()`).

---

### `FetchPlanIntersector.java` (internal helper, request-scoped transform) — NEW

**Analog (composite):** `BuiltInDataTools.getRelatedRecords` lines 229–235 (existing `FetchPlans.builder` usage) + `CurrentUserSchemaAccess.canReadAttribute` line 64 (per-attribute permission check) + `AuditWriter.writeToolCall` (existing audit emission API).

**FetchPlan-build precedent** (`BuiltInDataTools.java` lines 229–232):
```java
FetchPlan fetchPlan = fetchPlans.builder(rootMetaClass.getJavaClass())
        .addFetchPlan(FetchPlan.BASE)
        .add(relationship, fetchPlanBuilder -> fetchPlanBuilder.addFetchPlan(FetchPlan.INSTANCE_NAME))
        .build();
```
The `FetchPlans.builder(...).add(...).build()` API is what the intersector uses to walk + reconstruct narrowed plans. Note the existing per-property add-with-nested-builder pattern (line 231) — the intersector's recursive case (`metaProperty.getRange().isClass()`) reuses this exact shape.

**Permission-check reuse** (`CurrentUserSchemaAccess.java` lines 64–69) — already shown above. The intersector calls `schemaAccess.canReadAttribute(rootMc, propertyName)` once per `FetchPlanProperty` it walks.

**Audit emission for `PLAN_NARROWED`** (`AuditWriter.java` lines 148–189):
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public UUID writeToolCall(UUID parentId, UUID runId, String userUsername, UUID conversationId,
                          String toolName, String argumentsJson, String resultSummary, long latencyMs,
                          AiToolCallOutcome outcome, String denialReason, String errorClass) {
    AiAuditEvent row = metadata.create(AiAuditEvent.class);
    row.setRunId(runId);
    row.setKind(AuditKind.TOOL);
    row.setEventName(toolName);
    // ...
    row.setOutcome(outcome != null ? outcome : AiToolCallOutcome.SUCCESS);
    row.setDenialReason(denialReason);
    // ...
    dataManager.save(row);
}
```
The intersector reuses `AuditWriter.writeToolCall(...)` (D-12 "no new AuditKind") with a textual outcome string. Per `AuditKind.java` lines 11–14: `AuditKind` is open-ended `String` (no enum widening needed). The `outcome` parameter on `writeToolCall` is `AiToolCallOutcome` enum — planner verifies whether `PLAN_NARROWED` already exists on that enum or uses `outcomeRaw` (note `AuditWriter.writeChatFinish` uses `setOutcomeRaw(outcome)` at line 133, so a raw-string path exists). **Planner verifies in Phase 9 implementation**; this affects whether D-12 needs an `AiToolCallOutcome` enum value or stays raw-string only.

**`UnconstrainedDataManager` usage for system writes** — copy from `AuditWriter` lines 60–77:
```java
private final UnconstrainedDataManager dataManager;
private final Metadata metadata;

public AuditWriter(UnconstrainedDataManager dataManager, Metadata metadata, AuditListenerDispatcher dispatcher) {
```
Per project memory `feedback_jmix_unconstrained_for_system_writes`: audit-event writes are system infrastructure; use `UnconstrainedDataManager` not `runWithSystem` and not `DataManager`. The `FetchPlanIntersector` itself does NOT write audit rows directly — it delegates to `AuditWriter.writeToolCall(...)`, which already handles this concern. The intersector only needs `FetchPlans`, `CurrentUserSchemaAccess`, and `AuditWriter` injected.

**Mandatory code comment** (TOOL-11 verbatim):
```java
/**
 * fetch plan is projection, not security.   // ◄── REQUIRED CODE COMMENT
 *
 * Walks the host-supplied (or default) FetchPlan and removes every property for which
 * the current user lacks read access on the corresponding MetaClass attribute path.
 * Returns a freshly-built FetchPlan; never mutates the input.
 */
```

**Recursion shape** — copy from RESEARCH Pattern 3:
```java
public FetchPlan intersectWithAcl(FetchPlan original, MetaClass rootMc, String toolName) {
    List<String> dropped = new ArrayList<>();
    FetchPlanBuilder builder = fetchPlans.builder(rootMc.getJavaClass());

    for (FetchPlanProperty prop : original.getProperties()) {
        String name = prop.getName();
        if (!schemaAccess.canReadAttribute(rootMc, name)) {
            dropped.add(name);
            continue;
        }
        FetchPlan nested = prop.getFetchPlan();
        if (nested != null) {
            MetaClass nestedMc = rootMc.getProperty(name).getRange().asClass();
            FetchPlan narrowedNested = intersectWithAcl(nested, nestedMc, toolName);
            builder.add(name, fpb -> fpb.addFetchPlan(narrowedNested));
        } else {
            builder.add(name);
        }
    }

    if (!dropped.isEmpty()) {
        auditPlanNarrowed(toolName, rootMc.getName(), dropped);
    }
    return builder.build();
}
```

---

### `HostPrefixPatternProvider.java` (startup component) — NEW

**Analog (composite):** `OutputScannerAdvisor` constructor (lines 56–69) for compile-once + `Pattern` shape; existing `@Component` + `@EventListener(ApplicationReadyEvent.class)` idiom (Spring standard).

**Pattern to copy** (RESEARCH Pattern 2):
```java
@Component
public class HostPrefixPatternProvider {

    private final Metadata metadata;
    private volatile String compiledRegex;

    @EventListener(ApplicationReadyEvent.class)
    public void buildPattern() {
        Set<String> prefixes = new TreeSet<>();
        for (MetaClass mc : metadata.getSession().getClasses()) {
            String name = mc.getName();
            int underscore = name.indexOf('_');
            if (underscore > 0) {
                prefixes.add(Pattern.quote(name.substring(0, underscore)));
            }
        }
        if (prefixes.isEmpty()) {
            this.compiledRegex = null;
            return;
        }
        this.compiledRegex = "\\b(" + String.join("|", prefixes) + ")_\\w+\\b";
    }

    public Optional<AiAgentGuardProperties.OutputScanner.Pattern> asPattern() {
        return compiledRegex == null
                ? Optional.empty()
                : Optional.of(new AiAgentGuardProperties.OutputScanner.Pattern(
                        "HOST_PREFIX_LEAK", compiledRegex));
    }
}
```
Patterns to copy:
- `@Component` + constructor injection (single dep: `Metadata`).
- `@EventListener(ApplicationReadyEvent.class)` for one-shot startup work (RESEARCH §"Claude's Discretion: lazily on first scan call vs. ApplicationReadyEvent").
- `volatile` field for thread-safety; `Optional<...>` accessor.
- Stable pattern key `HOST_PREFIX_LEAK` (matches D-06 in RESEARCH).
- ReDoS guards: `Pattern.quote` per token + 200-prefix sanity check (RESEARCH Pitfall 5).

A sibling `ToolNamePatternProvider` follows the same shape but reads `agentToolCallbacks.forCurrentUser()` (or a startup snapshot — D-07) and seeds `RETRIEVAL` plus six built-in tool names (RESEARCH Pitfall 8: literal-underscore form only, NEVER `\s+`).

---

### `AuditFieldHasher.java` (static utility) — NEW

**Analog:** `ToolResultFormatter.escapeDataDelimiters` (`ToolResultFormatter.java` lines 234–246) — the only existing static-helper precedent in the codebase.

**Static-helper pattern to copy:**
```java
/**
 * Escape the literal delimiter substrings {@code <data>} and {@code </data>} inside a
 * text value so an attacker-supplied value cannot terminate the wrapper and smuggle
 * instructions (Pitfall 4 — delimiter escape-sequence bypass).
 */
static String escapeDataDelimiters(String value) {
    if (value == null) {
        return null;
    }
    return value
            .replace("<data>", "&lt;data&gt;")
            .replace("</data>", "&lt;/data&gt;");
}
```
Patterns to copy: `static` method, null-safe (returns `null` for `null` input), no Spring DI, package-private or `public final` class with private constructor. Per D-18 and AI-SPEC §4 "AUD-07 Plumbing":
```java
public final class AuditFieldHasher {

    private AuditFieldHasher() {
    }

    public static String sha256Hex(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
```
Stateless. NOT an SPI (per CONTEXT D-18; project memory `feedback_spi_baseline_builtin`).

**No caller wired in Phase 9.** Phase 11's `MutationErrorTranslator` is the planned consumer.

---

### `SpiDefaultsAutoConfiguration.java` (autoconfig, startup) — EXTEND

**Analog:** self — `SpiDefaultsAutoConfiguration.java` lines 31–72.

**`@ConditionalOnMissingBean` no-op default pattern** (lines 33–55):
```java
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class SpiDefaultsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolContributor defaultToolContributor() {
        return Collections::emptyList;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolGuard defaultToolGuard() {
        return (toolName, arguments) -> { /* allow all */ };
    }
}
```
Phase 9 adds:
```java
@Bean
@ConditionalOnMissingBean
public ToolFetchPlanCustomizer defaultToolFetchPlanCustomizer() {
    return (toolName, metaClass, ctx) -> Optional.empty();   // D-09 no-op
}
```
Per D-09 + RESEARCH Pitfall 9: inject as `List<ToolFetchPlanCustomizer>` at the consumer (mirrors `ToolContributor` precedent at `AgentToolCallbacks.java:33`); first non-empty `Optional` wins; no-op default returns `Optional.empty()`.

---

### `AiAgentGuardAutoConfiguration.java` (autoconfig, startup) — EXTEND

**Analog:** self — `AiAgentGuardAutoConfiguration.java` lines 110–114.

**`@ConditionalOnMissingBean(name = "...")` advisor-bean pattern**:
```java
@Bean
@ConditionalOnMissingBean(name = "outputScannerAdvisor")
public CallAdvisor outputScannerAdvisor(AiAgentGuardProperties props) {
    return new OutputScannerAdvisor(props);
}
```
Phase 9 registers `HostPrefixPatternProvider` and `ToolNamePatternProvider` here (or accepts they are `@Component`s discovered by component-scan in `com.vn.agent.guard` — planner picks). The Spring AI 1.1.4 `CallAdvisor` bean lookup precedent stays unchanged.

---

### `messages.properties` + `messages_vi.properties` (i18n) — EXTEND

**Analog:** existing message-bundle keys throughout the add-on (`Messages` injection per project memory `feedback_jmix_messages_over_spring`).

**Critical constraints**:
- All user-facing strings (none anticipated in Phase 9 except potentially a couple of error labels) MUST land in BOTH `messages.properties` AND `messages_vi.properties` per CLAUDE.md "Single-locale messages — ALWAYS add to ALL locale files".
- Tool-protocol English strings (the `unknown_entity` retry hints D-14, system-prompt rules PROMPT-03/05) are NOT translated and are NOT in `messages.properties` (RESEARCH Pitfall 7).
- LocaleParityTest (`ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java`) gates merge — any new key must be in both files.

---

### `BaselineContextProviderTest.java` (unit test) — EXTEND

**Analog:** self — lines 17–75.

**Mockito + AssertJ pattern** (lines 17–46):
```java
class BaselineContextProviderTest {

    @Test
    void compose_populates_all_baseline_keys() {
        CurrentAuthentication ca = Mockito.mock(CurrentAuthentication.class);
        Mockito.when(ca.getUser()).thenReturn(new User("alice", "x", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Mockito.when(ca.getLocale()).thenReturn(Locale.US);

        UUID convId = UUID.randomUUID();
        Map<String, Object> ctx = new BaselineContextProvider(ca).compose(convId);

        assertThat(ctx).containsKeys("agent.userId", "agent.username", "agent.roles", "agent.locale", "agent.conversationId");
        assertThat(ctx.get("agent.username")).isEqualTo("alice");
    }
```
Phase 9 adds Mockito mocks for `CurrentUserSchemaAccess`, `MessageTools`, `AccessManager` (+ the new properties record). New tests assert:
- `agent.entities` rendered byte-stable as `name (label)\n`-joined string, alpha order.
- `agent.permissions` rendered byte-stable JSON with `r,u,c,d,modifiable` keys in fixed `LinkedHashMap` order.
- Empty schema → keys absent.
- 100-entity threshold + truncation hint line.
- Locale toggle (VI vs EN) does NOT change `agent.permissions` key text — only label rendering.

**`renderAsText` byte-stability assertion pattern** (lines 49–73) — copy verbatim shape:
```java
String[] lines = text.split("\n");
assertThat(lines).hasSize(5);
assertThat(lines[0]).startsWith("agent.conversationId=");
```

---

### `PromptContractMockTest.java` (`@SpringBootTest` integration test) — NEW

**Analog (composite):** `OrchestrationIntegrationTest` (lines 36–46) for boot wiring + `OutputScannerAdvisorTest` (lines 33–67) for corpus loop.

**`@SpringBootTest` boot-pattern** (`OrchestrationIntegrationTest.java` lines 36–46):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class OrchestrationIntegrationTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;
```
Patterns to copy:
- `@SpringBootTest(classes = AITestConfiguration.class)` — loads the test wiring root.
- `@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})` — pulls in starter autoconfigs.
- `@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})` — replaces real LLM + vector store with deterministic stubs.
- `systemAuthenticator.runWithSystem(...)` for tests that need authenticated context (lines 50, 68).

**StubChatModel scripting pattern** (`StubChatModelConfiguration.java`):
```java
@TestConfiguration
public class StubChatModelConfiguration {

    @Bean
    @Primary
    public ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String lastUserText = extractLastUserText(prompt);
                AssistantMessage reply = new AssistantMessage("STUB:" + lastUserText);
                return new ChatResponse(List.of(new Generation(reply)));
            }
            // ...
        };
    }
}
```
Phase 9's `PromptContractMockTest` declares its own `@TestConfiguration` (similar shape) returning two scripted leaky replies (`"jmixapp_Customer is..."` and `"I called find_records..."`) per D-16. Asserts `OutputScannerAdvisor` flags `HOST_PREFIX_LEAK` and `TOOL_NAME_LEAK` via `ChatClientResponse.context()`.

**JUnit5 `@ParameterizedTest` + locale param** (D-17 + `OutputScannerAdvisorTest.java` lines 36–66):
```java
static Stream<Arguments> corpus() {
    return EvalFixtures.loadCases("output-scanner-corpus.yaml").stream()
            .map(c -> Arguments.of(c.get("text"), c.get("expectedPatternKey")));
}

@ParameterizedTest(name = "{1}")
@MethodSource("corpus")
void scannerFlagsAsExpected(String text, String expectedPatternKey) {
```
Phase 9 parameterizes over `Locale.of("vi","VN")` and `Locale.ENGLISH` (D-17). Source: `Stream.of(Arguments.of(Locale.of("vi","VN")), Arguments.of(Locale.ENGLISH))`. Test sets `CurrentAuthentication.locale` via test harness before each iteration.

**Eval fixture loader precedent** — `EvalFixtures.loadCases("output-scanner-corpus.yaml")` (existing test_support utility). Phase 9's new corpora (`baseline-context-golden.yaml`, `prompt-contract-chat.yaml`, etc. — see AI-SPEC §5 reference dataset) follow the same loader path.

**`@Tag("eval")` precedent** — Phase 9 mock test uses `@Tag("eval")` (or none) for default-CI inclusion; live test uses `@Tag("live")` for opt-out.

---

### `PromptContractLiveTest.java` (`@Tag("live")` opt-in) — NEW

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java` (sibling live test — same package convention).

**`@Tag("live")` exclusion** is wired in `ai-agent.gradle` per CONTEXT.md "Established Patterns: `@Tag("live")` excluded by default per `ai-agent.gradle`". The new test only needs `@Tag("live")` annotation; gradle filter handles the rest.

---

### `DescribeEntityPayloadTest.java`, `FetchPlanIntersectorTest.java`, `UnknownEntityRetryHintTest.java`, `AuditFieldHasherTest.java` (unit tests) — NEW

**Analog:** `ToolResultFormatterTest.java` lines 26–35 (Mockito-based pure unit test).

**Pattern to copy:**
```java
class ToolResultFormatterTest {

    private static ToolResultFormatter newFormatter() {
        return new ToolResultFormatter(
                new ObjectMapper(),
                mock(EntityStates.class),
                mock(MetadataTools.class),
                mock(MessageTools.class));
    }

    @Test
    void errorFromToolUserErrorSerializesExpectedList() {
        ToolUserError e = new ToolUserError("unknown_operation", "bad",
                List.of("EQUAL", "NOT_EQUAL"));
        String json = newFormatter().error(e);
        assertThat(json)
                .contains("\"error\":\"unknown_operation\"")
                .contains("\"reason\":\"bad\"")
                .contains("\"expected\":[\"EQUAL\",\"NOT_EQUAL\"]");
    }
}
```
Patterns:
- Pure unit (no `@SpringBootTest`); manual `new` of the class under test with `mock(...)` collaborators.
- AssertJ `.contains("...")` for JSON-substring shape assertions (avoids JSON-equality brittleness for ordering).
- `ToolUserError(...)` three-arg constructor for `expected: List<String>` cases — direct precedent for `UnknownEntityRetryHintTest`.

**`UnknownEntityRetryHintTest`** specifically asserts the three D-14 strings appear in `expected[]` byte-for-byte via `.contains("\"call list_entities exactly once\"")` etc. (NOT translated; RESEARCH Pitfall 7).

**`AuditFieldHasherTest`** — pure-function unit. Asserts:
- `null` → `null`.
- Empty string → 64-char hex.
- ASCII fixture → known SHA-256 output.
- Vietnamese Unicode fixture (e.g. `"Hoạt động"`) — UTF-8-byte stable hex (per AI-SPEC §5 fixture spec).

---

### `HostPrefixLeakScannerTest.java` (unit test) — NEW

**Analog:** `OutputScannerAdvisorTest.java` lines 33–67 (corpus-driven advisor test).

**Pattern to copy** — same `@ParameterizedTest` + `EvalFixtures` corpus loader + `stubResponse(text, contextMap)` + assert `contextMap.get(CONTEXT_KEY_FLAGGED_PATTERN)`. Phase 9 fixture is `output-scanner-corpus.yaml` (extended) per AI-SPEC §5: 8 cases including host-prefix positives (`jmixapp_Customer`), tool-name positives (`find_records`), benign false-positive controls (`"I will look for records"`).

---

## Shared Patterns

### Constructor Injection (CLAUDE.md rule)

**Source:** `BaselineContextProvider.java` lines 42–44, `BuiltInDataTools.java` lines 58–76, `AuditWriter.java` lines 73–77.

**Apply to:** Every new `@Component` (`HostPrefixPatternProvider`, `ToolNamePatternProvider`, `FetchPlanIntersector`).

```java
@Component
public class BaselineContextProvider {

    private final CurrentAuthentication currentAuthentication;

    public BaselineContextProvider(CurrentAuthentication currentAuthentication) {
        this.currentAuthentication = currentAuthentication;
    }
```

### `@ConditionalOnMissingBean` no-op SPI default

**Source:** `SpiDefaultsAutoConfiguration.java` lines 33–61.

**Apply to:** `ToolFetchPlanCustomizer` default bean.

```java
@Bean
@ConditionalOnMissingBean
public ToolGuard defaultToolGuard() {
    return (toolName, arguments) -> { /* allow all */ };
}
```

### `@ConfigurationProperties` record + `resolved*` accessors

**Source:** `AiAgentGuardProperties.java` lines 29–104.

**Apply to:** New `AiAgentAuditProperties` record (D-18) and any new `AiAgentPromptProperties` (D-03 entity-inventory threshold).

```java
@ConfigurationProperties("jmix.ai-agent.guard")
public record AiAgentGuardProperties(...) {

    public boolean outputScannerEnabled() {
        return outputScanner == null || !Boolean.FALSE.equals(outputScanner.enabled());
    }

    public List<OutputScanner.Pattern> resolvedPatterns() {
        if (outputScanner == null || outputScanner.patterns() == null || outputScanner.patterns().isEmpty()) {
            return List.of(/* D-18 defaults */);
        }
        return outputScanner.patterns();
    }
}
```

### Tool-error JSON envelope

**Source:** `BuiltInDataTools.java` lines 105–115 + `ToolUserError.java` + `ToolErrorDto.java` + `ToolResultFormatter.error(ToolUserError)`.

**Apply to:** Every `@Tool` method body.

```java
try {
    // ... business logic ...
    return toolResultFormatter.<shape>(...);
} catch (ToolUserError toolUserError) {
    return toolResultFormatter.error(toolUserError);
}
```

### `<data>...</data>` untrusted-text wrap

**Source:** `ToolResultFormatter.java` lines 230–246.

**Apply to:** Every entity-string value rendered into a tool payload (PROMPT-04 PROMPT-style outer wrapper relabels but per-attribute wrap unchanged).

```java
private String wrapUntrustedText(String value) {
    return "<data>" + escapeDataDelimiters(value) + "</data>";
}
```

### `agentstore` data-store routing for raw JPQL `loadValue` / `loadValues`

**Source:** `AuditWriter.java` lines 244–257 (`findChatRootId`).

**Apply to:** Any future Phase 9 raw-JPQL audit query touching `AiAuditEvent` / `AiConversation` / `AiMessage` / `AiKnowledgeDocument` / `AiParameters`.

```java
return dataManager.loadValue(
                "select e.id from ai_AiAuditEvent e "
                        + "where e.runId = :runId and e.kind = :kind and e.parent is null",
                UUID.class)
        .store("agentstore")          // ← REQUIRED per project memory feedback_jmix_loadvalue_store
        .parameter("runId", runId)
        .parameter("kind", AuditKind.CHAT)
        .optional()
        .orElse(null);
```
Phase 9 adds NO new entities and NO new audit-table queries by itself, but if the planner finds the `PLAN_NARROWED` audit emission needs to read existing rows (it does not — `AuditWriter.writeToolCall` handles INSERT-only), the `.store("agentstore")` rule applies.

### `UnconstrainedDataManager` for system-internal writes

**Source:** `AuditWriter.java` lines 60–77 + project memory `feedback_jmix_unconstrained_for_system_writes`.

**Apply to:** `FetchPlanIntersector` does NOT write directly; it delegates to `AuditWriter`. The `AuditWriter` already uses `UnconstrainedDataManager`, so the convention holds transitively. **No Phase 9 component should call `runWithSystem` or use plain `DataManager` for audit/system writes.**

### MetadataTools-only metadata access (no reflection)

**Source:** Existing `BuiltInDataTools.java` line 312 (`metadataTools.getPrimaryKeyProperty(metaClass)`); RESEARCH §"Don't Hand-Roll".

**Apply to:** New `describe_entity` payload (D-04). Use `metadataTools.getMetaAnnotationValue(...)`, `metadataTools.isJpa(...)`, `metadataTools.isPrimaryKey(...)`. The single allowed reflection in `tools/` is `ToolResultFormatter.columnLength` (lines 99–109) for `@Column.length` only.

### Locale-free cache key invariant

**Source:** `CurrentUserSchemaAccess.java` lines 17–22 (Javadoc): "no class-level cache (per threat model T-03-01)".

**Apply to:** All Phase 9 prompt-rendering code — never cache; if cache is added later, key must be `(userId, roleSet, metaclass-name-set)` only. Locale labels via `MessageTools.getEntityCaption(...)` resolved at render time, NEVER stored in cache key (D-02 PROMPT-02 explicit, RESEARCH Pitfall 1).

### Stable scanner audit-key (never raw match)

**Source:** `OutputScannerAdvisor.java` lines 109–120 (`writeFlag`).

**Apply to:** New `HOST_PREFIX_LEAK` and `TOOL_NAME_LEAK` patterns. Audit row carries the KEY only (`response.context().put(CONTEXT_KEY_FLAGGED_PATTERN, key)`). Matched substring NEVER persisted (D-17/D-18, RESEARCH Anti-Patterns).

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| (none) | — | — | Every Phase 9 file has at least a partial analog in the existing codebase. The closest thing to greenfield is `AuditFieldHasher` (pure utility), but `ToolResultFormatter.escapeDataDelimiters` is a clean static-helper precedent. |

---

## Metadata

**Analog search scope:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/**` (production)
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/**` (autoconfig)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/**` (test patterns)

**Files scanned:** ~95 production Java files, ~70 test Java files (Glob-listed; targeted Reads on the 12 highest-relevance analogs).

**Key patterns identified:**
- All baseline/permission rendering MUST flow through `CurrentUserSchemaAccess.getReadableSchema()` — single source of truth for both prompt baseline AND tool surface (parity guarantee D-02 / RESEARCH "Architecture Patterns").
- Spring AI 1.1.4 advisor extension is purely additive: new patterns flow through `AiAgentGuardProperties.resolvedPatterns()` → `OutputScannerAdvisor` constructor compile loop → existing `adviseCall` regex matcher with zero consumer-side change.
- Fetch-plan resolution MUST be: (1) `List<ToolFetchPlanCustomizer>` first-non-empty-wins → (2) fall back to `FetchPlan.BASE` → (3) `FetchPlanIntersector.intersectWithAcl(plan, mc, toolName)` → (4) `DataManager.load(...).fetchPlan(narrowed)`. Code comment "fetch plan is projection, not security." MANDATORY on the intersector.
- Tool-protocol English strings (`unknown_entity` retry hints, system-prompt rules) are NOT translated and live in Java constants, NOT `messages.properties` (RESEARCH Pitfall 7; precedent: `AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT`).
- AUD-07 plumbing (`AuditFieldHasher` + `AiAgentAuditProperties`) ships uncalled; Phase 11 wires the consumer.

**Pattern extraction date:** 2026-04-27
