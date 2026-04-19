# Phase 3: Metadata-First Runtime & Six Tools - Pattern Map

**Mapped:** 2026-04-19
**Files analyzed:** 14 new + 2 modified
**Analogs found:** 14 / 16 (2 files have no close analog in this codebase — see "No Analog Found")

> **Post-execute refactor note (2026-04-20):** Class-name references in this document reflect the original pre-refactor design intent. Per user feedback "Reuse Jmix built-ins over parallel layers" (`memory/feedback_reuse_jmix_builtins.md`), the six metadata classes (`AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */`, `AiEntityInfo /* collapsed post-execute into CurrentUserSchemaAccess */`, `AiAttributeInfo /* collapsed post-execute into CurrentUserSchemaAccess */`, `UserEditableStringIndex /* collapsed post-execute into CurrentUserSchemaAccess */`, `CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */`, `CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */`) were collapsed post-execute into a single adapter `com.vn.agent.metadata.CurrentUserSchemaAccess`, and the filter classes were renamed (`FilterLiteralValueConverter /* previously LiteralCoercer - renamed post-execute */` → `FilterLiteralValueConverter`, `StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */` → `StructuredFilterConditionMapper`). The patterns below are preserved as-executed for pattern-map traceability. A new pattern entry "Thin adapter over Jmix built-ins" (see §Shared Patterns) captures the lesson learned from that refactor.

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `com.vn.agent.metadata.CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */` | service (startup singleton) | event-driven (ApplicationReadyEvent → cache) | `ai-agent/.../spi/ToolContributor.java` + `DefaultChatServiceImpl.java` (constructor-injection pattern) | role-match (no existing startup-event scanner in repo) |
| `com.vn.agent.metadata.CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */` | service (stateless, request-scoped behavior) | request-response (transform) | `DefaultChatServiceImpl.java` | role-match |
| `com.vn.agent.metadata.AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */` / `AiEntityInfo /* collapsed post-execute into CurrentUserSchemaAccess */` / `AiAttributeInfo /* collapsed post-execute into CurrentUserSchemaAccess */` / `UserEditableStringIndex /* collapsed post-execute into CurrentUserSchemaAccess */` | DTO (record) | transform | `ChatResponse.java` (record DTO) | exact |
| `com.vn.agent.tools.BuiltInDataTools` (6 × `@Tool`) | controller (LLM-facing) | request-response + CRUD (read-only) | `DefaultChatServiceImpl.java` (constructor DI + @Service on single bean); `OrderService.java` (DataManager + fetch plans) | role-match |
| `com.vn.agent.tools.DataManagerToolExecutor` | service | CRUD (read-only) | `jmix-app/.../service/OrderService.java` | exact |
| `com.vn.agent.filter.FilterNode` (sealed) + `FilterDsl` records | DTO | transform | `ChatResponse.java` (record) | role-match (no sealed hierarchy exists yet) |
| `com.vn.agent.filter.StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */` | service (pure transform) | transform | `DefaultChatServiceImpl.java` (stateless service) | role-match |
| `com.vn.agent.filter.FilterLiteralValueConverter /* previously LiteralCoercer - renamed post-execute */` | utility | transform | — (none) | no analog |
| `com.vn.agent.tools.ToolResultFormatter` | utility (formatter) | transform | — (none — new cross-cutting concern) | no analog |
| `com.vn.agent.tools.ToolLimits` | config (constants) | — | `AiAgentUserRowLevelRole.CODE` constant style | partial |
| `com.vn.agent.tools.ToolErrorDto` | DTO (record) | transform | `ChatResponse.java` | exact |
| `com.vn.agent.tools.AgentToolCallbacks` | service (provider) | request-response (assembly) | `SpiDefaultsAutoConfiguration.defaultToolContributor` (SPI consumer shape) | role-match |
| `AIConfiguration.java` (MODIFIED) | config | — | self (current file) | exact |
| `ai-agent-starter/.../AiToolsAutoConfiguration.java` (NEW) | config (auto-config) | — | `SpiDefaultsAutoConfiguration.java` | exact |
| `AutoConfiguration.imports` (MODIFIED) | config (resource) | — | self | exact |
| `jmix-app/.../ai/OrderSummaryToolContributor.java` (D-15 sample) | component (host-side SPI impl) | CRUD (read) | `OrderService.java` + `ChatServiceSmokeRunner.java` (`@Component` in jmix-app/ai package) | exact |
| `jmix-app/.../ai/ChatServiceToolIntegrationTest.java` | test (integration) | — | `jmix-app/.../user/UserTest.java` + `FoundationsBootSmokeTest.java` | exact |
| `ai-agent/.../tools/PromptInjectionHarnessTest.java` (#5) | test (integration) | — | `FoundationsBootSmokeTest.java` | exact |
| `ai-agent/.../tools/BuiltInDataToolsReadOnlyTest.java` (D-16) | test (unit, reflection) | — | `ChatServiceMockTest.java` (pure unit, no `@SpringBootTest`) | partial |

---

## Pattern Assignments

### `com.vn.agent.tools.BuiltInDataTools` (controller, request-response)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (constructor-injection + `@Service`/`@Component` single-bean shape) and `jmix-app/src/main/java/com/vn/jmixapp/service/OrderService.java` (DataManager + fetch-plan shape).

**Imports pattern** (mirror of `OrderService.java` lines 1-10):
```java
package com.vn.agent.tools;

import com.vn.agent.filter.StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */;
import com.vn.agent.filter.FilterNode;
import com.vn.agent.metadata.CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.Metadata;
import io.jmix.core.MessageTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.querycondition.Condition;
import io.jmix.core.security.AccessManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
```

**Constructor injection pattern** (from `DefaultChatServiceImpl.java` lines 14-22 — CLAUDE.md "constructor injection only"):
```java
@Component
public class BuiltInDataTools {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AccessManager accessManager;
    private final MessageTools messageTools;
    private final CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */ schemaComputer;
    private final FetchPlans fetchPlans;
    private final StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */ filterMapper;
    private final ToolResultFormatter formatter;

    // CLAUDE.md: constructor injection only.
    public BuiltInDataTools(DataManager dataManager, Metadata metadata, /* ... */) {
        this.dataManager = dataManager;
        // ...
    }
}
```

**DataManager + fetch-plan core pattern** (copy from `OrderService.java` lines 22-32):
```java
@Transactional(readOnly = true)
public BigDecimal calculateTotal(UUID orderId) {
    Order order = dataManager.load(Order.class)
            .id(orderId)
            .fetchPlan(fp -> fp.addFetchPlan("_base")
                .add("lines", lp -> lp.addFetchPlan("_base").add("product", "_base")))
            .one();
    // ...
}
```
Adapt: use `FetchPlan.INSTANCE_NAME` instead of `"_base"` per D-12; use `mc.getJavaClass()` instead of hard `Order.class`; use `.optional().orElse(null)` for not-found path.

**Error handling pattern:** No existing structured-error analog in codebase. Use `ToolErrorDto` record serialized via formatter. Fail-closed per D-07: throw/return structured error JSON, never stack trace.

---

### `com.vn.agent.metadata.CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */` (service, event-driven)

**Analog:** None exact. Structural shape mirrors `DefaultChatServiceImpl.java` (single `@Component`, constructor DI, `final` fields).

**Pattern to use** (synthesized from RESEARCH.md §Code Examples + `DefaultChatServiceImpl` shape):
```java
@Component
public class CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */ {
    private final Metadata metadata;
    private volatile AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */ rawSchema;

    public CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */(Metadata metadata) {
        this.metadata = metadata;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        // iterate metadata.getSession().getClasses(), build immutable AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */
    }
}
```

Do NOT use `@PostConstruct` (Pitfall 1 — `AccessManager` not valid at that phase; we want only the raw inventory here anyway).

---

### `com.vn.agent.metadata.CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */` (service, request-response)

**Analog:** `DefaultChatServiceImpl.java` for shape; `AccessManager` usage is novel for this repo (no existing caller).

**Pattern** (per D-04, D-05, RESEARCH.md §Code Examples):
```java
@Component
public class CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */ {

    private final AccessManager accessManager;
    private final CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */ scanner;
    private final MessageTools messageTools;

    public CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */(AccessManager accessManager,
                                   CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */ scanner,
                                   MessageTools messageTools) {
        this.accessManager = accessManager;
        this.scanner = scanner;
        this.messageTools = messageTools;
    }

    public AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */ forCurrentUser() {
        AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */ raw = scanner.getRawSchema();
        // for each entity:
        //   CrudEntityContext ec = new CrudEntityContext(metaClass);
        //   accessManager.applyRegisteredConstraints(ec);
        //   if (!ec.isReadPermitted()) continue;
        //   for each attr:
        //     EntityAttributeContext ac = new EntityAttributeContext(mc, attrName);
        //     accessManager.applyRegisteredConstraints(ac);
        //     if (ac.canView()) include
    }
}
```

Keep stateless (Open Question #6 answer). Do NOT cache across requests (TOOL-02).

---

### `com.vn.agent.filter.StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */` (service, transform)

**Analog:** Shape follows `DefaultChatServiceImpl.java` (`@Component`, constructor DI, pure Java). Jmix `PropertyCondition`/`LogicalCondition` usage is novel in this repo.

**DSL record shape** (mirror of `ChatResponse.java` record-style — see that file for the `public record Foo(...)` idiom):
```java
public sealed interface FilterNode permits AndNode, OrNode, NotNode, LeafNode {}
public record AndNode(List<FilterNode> children) implements FilterNode {}
public record OrNode(List<FilterNode> children)  implements FilterNode {}
public record NotNode(FilterNode child)          implements FilterNode {}
public record LeafNode(String property, String operation, Object value) implements FilterNode {}
```

**Mapper core pattern:** see RESEARCH.md §"Filter DSL → Condition" code example (lines 449-499 of 03-RESEARCH.md) — switch on the sealed type, map operation string to `PropertyCondition.Operation.*` constant, call `FilterLiteralValueConverter /* previously LiteralCoercer - renamed post-execute */.coerce(...)` before `PropertyCondition.createWithValue(...)`. Implement DeMorgan expansion for `NotNode` (Pitfall 6 recommendation).

**Validation pattern (D-08 path + depth cap):** At each hop, call `AccessManager.applyRegisteredConstraints(new EntityAttributeContext(mc, prop))` and reject if `!ac.canView()`. Fail-closed: throw `ToolUserError` → caught at tool boundary → returned as structured error DTO.

---

### `com.vn.agent.tools.AgentToolCallbacks` (service, request-response)

**Analog:** `SpiDefaultsAutoConfiguration.java` lines 35-37 (reads `ToolContributor` beans). Consumer shape:
```java
@Bean
@ConditionalOnMissingBean
public ToolContributor defaultToolContributor() {
    return Collections::emptyList;
}
```

**Pattern for per-request assembly** (per D-10, RESEARCH.md §Code Examples):
```java
@Component
public class AgentToolCallbacks {

    private final BuiltInDataTools builtIns;
    private final List<ToolContributor> contributors;

    public AgentToolCallbacks(BuiltInDataTools builtIns, List<ToolContributor> contributors) {
        this.builtIns = builtIns;
        this.contributors = contributors;
    }

    public ToolCallback[] forCurrentUser() {
        List<ToolCallback> all = new ArrayList<>();
        Collections.addAll(all, ToolCallbacks.from(builtIns));
        for (ToolContributor tc : contributors) {
            for (Object bean : tc.contribute()) {
                Collections.addAll(all, ToolCallbacks.from(bean));
            }
        }
        return all.toArray(ToolCallback[]::new);
    }
}
```

**Naming note (D-10 discretion):** RESEARCH.md recommends `AgentToolCallbacks` as the bean/class name — reads well at Phase 4's `ChatClientFactory` call site: `chatClient.prompt().toolCallbacks(agentToolCallbacks.forCurrentUser())`.

---

### `ai-agent-starter/.../AiToolsAutoConfiguration.java` (NEW auto-config)

**Analog:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` — EXACT match.

**Imports + class header pattern** (copy lines 1-31 of `SpiDefaultsAutoConfiguration.java`):
```java
package com.vn.autoconfigure.agent;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Phase 3 metadata-first runtime + six built-in tools.
 * Registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
 * Runs AFTER AIAutoConfiguration so ChatClient.Builder is present.
 */
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class AiToolsAutoConfiguration {
    // scanner/executor/builtIns/provider beans are component-scanned from AIConfiguration;
    // this class exists for explicit @AutoConfigureAfter ordering and any @Bean overrides host apps may swap.
}
```

Key decision: the six Phase 3 beans (`CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */`, `CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */`, `BuiltInDataTools`, `DataManagerToolExecutor`, `StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */`, `AgentToolCallbacks`) are discovered via the existing `@ComponentScan` on `AIConfiguration` (see `AIConfiguration.java` line 21). They live under `com.vn.agent.*` so no base-package widening is needed.

---

### `AutoConfiguration.imports` (MODIFIED)

**Analog:** self. Current content (read at lines 1-2):
```
com.vn.autoconfigure.agent.AIAutoConfiguration
com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration
```
**Change:** append one line:
```
com.vn.autoconfigure.agent.AiToolsAutoConfiguration
```

---

### `AIConfiguration.java` (MODIFIED)

**Analog:** self. Current state at lines 23-28 declares `@JmixModule(dependsOn = {...})`. Phase 3 likely does NOT widen `dependsOn` (scanner/executor use `io.jmix.core.*` already transitively included via `DataConfiguration` / `EclipselinkConfiguration`). Verify during planning and only add if scanner needs a class from a not-yet-imported module.

---

### `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java` (D-15 sample)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/ai/ChatServiceSmokeRunner.java` (same `com.vn.jmixapp.ai` package, `@Component`, constructor-injected Jmix add-on interface) + `jmix-app/src/main/java/com/vn/jmixapp/service/OrderService.java` (DataManager + fetch-plan domain logic).

**Imports + header pattern** (from `ChatServiceSmokeRunner.java` lines 1-20):
```java
package com.vn.jmixapp.ai;

import com.vn.agent.spi.ToolContributor;
import com.vn.jmixapp.entity.Order;
import io.jmix.core.DataManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class OrderSummaryToolContributor implements ToolContributor {

    private final DataManager dataManager;

    public OrderSummaryToolContributor(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override public List<Object> contribute() { return List.of(this); }

    @Tool(description = "Summarize recent orders for a customer (total + count).")
    public String summarizeCustomerOrders(
            @ToolParam(description = "Customer UUID") String customerId) {
        // use dataManager.load(Order.class).query("...").list() — exercise join w/ Customer
    }
}
```
Uses existing `Order` entity (`jmix-app/.../entity/Order.java`) and exercises the `Customer` join via a `ManyToOne` relationship already present in that entity (line 48).

---

### `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java` (success criterion #3)

**Analog:** `jmix-app/src/test/java/com/vn/jmixapp/user/UserTest.java` — EXACT match on `@SpringBootTest` + `@ExtendWith(AuthenticatedAsAdmin.class)` + `@ActiveProfiles("test")` host-side integration shape.

**Imports + header pattern** (copy from `UserTest.java` lines 1-24):
```java
package com.vn.jmixapp.ai;

import com.vn.agent.ChatService;
import com.vn.jmixapp.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class ChatServiceToolIntegrationTest {

    @Autowired ChatService chatService;
    @MockBean org.springframework.ai.chat.model.ChatModel chatModel;
    @Autowired DataManager dataManager;

    @Test
    void findRecordsOrderRoundTrip() {
        // 1. seed an Order via dataManager (follow FoundationsBootSmokeTest pattern lines 122-187)
        // 2. script chatModel to emit tool-call for find_records("jmixapp_Order", …)
        // 3. assert DataManager result flowed back into assistant content
    }
}
```

**Seeding pattern** (copy shape from `FoundationsBootSmokeTest.java` lines 122-150 — `metadata.create(...)` + `dataManager.save(...)` under `systemAuthenticator.runWithSystem(...)`).

---

### `ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java` (D-16)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` — pure unit, no `@SpringBootTest`, plain JUnit 5.

**Imports + header pattern** (from `ChatServiceMockTest.java` lines 1-30):
```java
package com.vn.agent.tools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltInDataToolsReadOnlyTest {

    @Test
    void noMutationPathsInToolBodies() throws Exception {
        // ASM ClassReader on BuiltInDataTools.class.getResourceAsStream(...)
        // Walk @Tool-annotated methods; visit INVOKEVIRTUAL / INVOKEINTERFACE;
        // fail on io/jmix/core/DataManager.save | saveContext | remove
        //  or on jakarta/persistence/EntityManager.*
        //  or on String concat feeding DataManager.load(String, ...)
    }
}
```

Per Open Question #2 recommendation: use ASM 9.7 (add `testImplementation 'org.ow2.asm:asm:9.7'` to `ai-agent/ai-agent.gradle`).

---

### `ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java` (success criterion #5)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` lines 79-84 (full `@SpringBootTest` + `AITestConfiguration` + `ImportAutoConfiguration` shape).

**Pattern** — reuse `FoundationsBootSmokeTest`'s boot harness header:
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class,
        com.vn.autoconfigure.agent.AiToolsAutoConfiguration.class   // Phase 3 add
})
class PromptInjectionHarnessTest {

    @Autowired ToolResultFormatter formatter;
    @Autowired Metadata metadata;

    @Test
    void userEditableStringIsWrapped() {
        // see RESEARCH.md §"Prompt-Injection Harness" code example (lines 659-681)
    }
}
```

---

### Other unit tests (`CurrentUserSchemaAccessTest /* previously CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */Test - collapsed post-execute */`, `CurrentUserSchemaAccessTest /* previously CurrentUserSchemaAccess /* previously EffectiveSchemaComputer - collapsed post-execute */Test - collapsed post-execute */`, `StructuredFilterConditionMapperTest /* previously StructuredFilterConditionMapper /* previously FilterDslMapper - renamed post-execute */Test - renamed post-execute */`, `ToolLimitsTest`, `ToolResultFormatterTest`)

**Analog for pure unit tests (no Spring):** `ChatServiceMockTest.java` — JUnit 5 + AssertJ, no `@SpringBootTest`.

**Analog for Spring-backed unit tests (`CurrentUserSchemaAccessTest /* previously CurrentUserSchemaAccess /* previously MetamodelScanner - collapsed post-execute */Test - collapsed post-execute */` needs `Metadata`):** `FoundationsBootSmokeTest.java` header pattern (lines 79-84). Also `DefaultChatServiceImplTest.java` for lighter-weight Mockito-only variant.

---

## Shared Patterns

### Constructor Injection (CLAUDE.md mandate)
**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` lines 17-22
**Apply to:** All Phase 3 `@Component` / `@Service` beans.
```java
private final ChatClient chatClient;

// CLAUDE.md: constructor injection only.
public DefaultChatServiceImpl(ChatClient.Builder builder) {
    this.chatClient = builder.build();
}
```

### DataManager Usage (SEC-03 / TOOL-04)
**Source:** `jmix-app/src/main/java/com/vn/jmixapp/service/OrderService.java` lines 22-32
**Apply to:** `BuiltInDataTools`, `DataManagerToolExecutor`, `OrderSummaryToolContributor`.
```java
@Transactional(readOnly = true)  // appropriate for read-only tool bodies
// ...
Order order = dataManager.load(Order.class)
        .id(orderId)
        .fetchPlan(fp -> fp.addFetchPlan("_base").add(...))
        .one();
```
Phase 3 swap: `"_base"` → `FetchPlan.INSTANCE_NAME`; `.one()` → `.optional().orElse(null)` for not-found.

### Component-Scan Bean Registration (no explicit @Bean)
**Source:** `AIConfiguration.java` line 21 (`@ComponentScan`).
**Apply to:** All `com.vn.agent.tools.*`, `com.vn.agent.metadata.*`, `com.vn.agent.filter.*` — because they live under `com.vn.agent` they are auto-discovered without `@Bean` declarations in any `@AutoConfiguration` class.

### SPI Default Bean Pattern (for future override hooks)
**Source:** `SpiDefaultsAutoConfiguration.java` lines 33-37
**Apply to:** Any Phase 3 bean the host might want to override (e.g. `ToolResultFormatter` if a host wants alternate JSON).
```java
@Bean
@ConditionalOnMissingBean
public ToolResultFormatter defaultToolResultFormatter(...) { ... }
```
Not required for Phase 3 core beans unless explicitly desired.

### Record DTO Pattern
**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java` (record `ChatResponse(String content, Map<String,String> metadata)`).
**Apply to:** `AiSchema /* collapsed post-execute into CurrentUserSchemaAccess */`, `AiEntityInfo /* collapsed post-execute into CurrentUserSchemaAccess */`, `AiAttributeInfo /* collapsed post-execute into CurrentUserSchemaAccess */`, `FilterNode` and implementers, `ToolErrorDto`.
Use `public record X(...)` — no Lombok, no builders.

### Host-side Integration Test Harness
**Source:** `jmix-app/src/test/java/com/vn/jmixapp/user/UserTest.java` lines 21-23 + `AuthenticatedAsAdmin.java`
**Apply to:** `ChatServiceToolIntegrationTest` and any future host-side integration test.
```java
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
```

### Add-on-side Integration Test Harness
**Source:** `FoundationsBootSmokeTest.java` lines 79-84
**Apply to:** `PromptInjectionHarnessTest` and any add-on-side `@SpringBootTest`.
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({ AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class,
                           AiToolsAutoConfiguration.class })
```

### Thin Adapter over Jmix Built-ins (added 2026-04-20, post-execute)
**Source:** Refactor of Plan 03-01's six metadata classes (`AiSchema`, `AiEntityInfo`, `AiAttributeInfo`, `UserEditableStringIndex`, `MetamodelScanner`, `EffectiveSchemaComputer`) into a single adapter `com.vn.agent.metadata.CurrentUserSchemaAccess`.
**User feedback:** `memory/feedback_reuse_jmix_builtins.md` — "Reuse Jmix built-ins over parallel layers." Audit against `Metadata` / `AccessManager` / `DataManager` / `FetchPlan` / `MetadataTools` first; own only the thin LLM adapter (schema shape, literal coercion, path depth, result limits, injection-safe formatting).
**Apply to:** Any future metadata-to-LLM surface. Do NOT build parallel DTO trees (`AiSchema` etc.) that mirror Jmix's `MetaClass` / `MetaProperty`. Instead expose a 3-method adapter (`getReadableSchema`, `canReadAttribute`, `canReadEntity`) that delegates to `Metadata` + `AccessManager` per-call and returns Jmix types directly where possible.
**Lesson learned:** Front-loading six DTO records + two service beans added surface area without paying for itself — every call site already had `Metadata` and `AccessManager` available.

### Authenticated Code Section in Test
**Source:** `FoundationsBootSmokeTest.java` lines 122-123
```java
systemAuthenticator.runWithSystem(() -> {
    // metadata.create(...); dataManager.save(...);
});
```
Apply to any test that needs to seed `jmixapp_Order` / `jmixapp_Customer` rows before invoking tools.

---

## No Analog Found

| File | Role | Data Flow | Reason / Fallback |
|------|------|-----------|-------------------|
| `com.vn.agent.tools.ToolResultFormatter` | utility (JSON + `<data>` wrapper) | transform | No formatter in repo. Fallback: Jackson `ObjectMapper` (transitively on classpath) + custom `<data>` wrapping logic per RESEARCH.md §Pattern 4 and D-13. Use `ObjectMapper` as `@Bean` or singleton; walk attribute set provided by `UserEditableStringIndex /* collapsed post-execute into CurrentUserSchemaAccess */`. |
| `com.vn.agent.filter.FilterLiteralValueConverter /* previously LiteralCoercer - renamed post-execute */` | utility | transform | No type-coercion utility in repo. Direct implementation per D-07: `UUID.fromString`, `Enum.valueOf`, `BigDecimal`/`Long`/`Integer` parse, ISO-8601 `LocalDate.parse` / `OffsetDateTime.parse`. Dispatch on `MetaProperty.getRange()`. |

---

## Metadata

**Analog search scope:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/**` (all Phase 1 + 2 code)
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/**` (both existing auto-configs)
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/**`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/**` (all tests)
- `jmix-app/src/main/java/com/vn/jmixapp/**` (service + entity + ai package)
- `jmix-app/src/test/java/com/vn/jmixapp/**`

**Files scanned:** ~40 (full repo inventory listed via Glob).
**Files read in full:** 10 (AIConfiguration, DefaultChatServiceImpl, ChatService, AIAutoConfiguration, SpiDefaultsAutoConfiguration, ToolContributor, ContextContributor, OrderService, ChatServiceSmokeRunner, FoundationsBootSmokeTest, ChatServiceMockTest, AITestConfiguration, UserTest, AuthenticatedAsAdmin, Order, AiConversation, ChatServiceLiveTest, AutoConfiguration.imports).
**Pattern extraction date:** 2026-04-19

*Phase: 03-metadata-first-runtime-six-tools*
