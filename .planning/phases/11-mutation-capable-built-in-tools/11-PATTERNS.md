# Phase 11: Mutation-Capable Built-In Tools — Pattern Map

**Mapped:** 2026-04-28
**Files analyzed:** 21 (16 NEW + 5 MODIFIED)
**Analogs found:** 21 / 21

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `com/vn/agent/tools/mutation/BuiltInMutationTools.java` (NEW) | tool component | request-response (CRUD) | `com/vn/agent/tools/BuiltInDataTools.java` | exact (sibling — read vs write) |
| `com/vn/agent/tools/link/BuiltInLinkTools.java` (NEW) | tool component | request-response | `D:/DTH/jmix-crm/.../ViewsDiscoveryTool.java` | exact (link generator) |
| `com/vn/agent/tools/mutation/MutationErrorTranslator.java` (NEW) | error translator / utility | transform | `com/vn/agent/tools/BuiltInDataTools.java` (per-error throws) + `com/vn/agent/filter/FilterLiteralValueConverter.java` | partial — no exact analog |
| `com/vn/agent/tools/mutation/AiAgentMutationProperties.java` (NEW) | config properties | config | `com/vn/agent/audit/AiAgentAuditProperties.java` | exact |
| `com/vn/agent/tools/mutation/AiMutationIntent.java` (NEW) | entity (agentstore) | persistence | `com/vn/agent/exposure/AiExposureRule.java` | exact |
| `com/vn/agent/tools/mutation/MutationIntentRepository.java` (NEW) | repository | CRUD (system) | `com/vn/agent/exposure/LlmExposureRuleRepository.java` | exact (UnconstrainedDataManager pattern) |
| `com/vn/agent/tools/mutation/MutationIntentCleanupJob.java` (NEW) | scheduled job | batch | `com/vn/agent/exposure/LlmExposureRuleRepository.java` (UnconstrainedDataManager) + Research Q6 cron template | role-match (no `@Scheduled` job exists yet in codebase) |
| `com/vn/agent/spi/MutationGuard.java` (NEW) | SPI interface | event-driven (gate) | `com/vn/agent/spi/ToolGuard.java` | exact (mirror) |
| `com/vn/agent/spi/MutationIntent.java` (NEW) | SPI record | DTO | `com/vn/agent/tools/ToolErrorDto.java` (record-style DTO) | role-match (record carrier) |
| `com/vn/agent/tools/ToolEntityResolver.java` (NEW) | utility @Component | transform | `com/vn/agent/tools/BuiltInDataTools.java` lines 333-407 (helpers being extracted) | exact (extraction) |
| `com/vn/agent/security/AiAgentMutationRole.java` (NEW) | security role | config | `com/vn/agent/security/AiAgentUserRole.java` (narrow role) + `AiAgentAdminRole` | exact |
| `com/vn/agent/AIConfiguration.java` (MOD — add `@EnableScheduling` + `@ConditionalOnMissingBean` no-op `MutationGuard`) | config wiring | config | `com/vn/agent/AIConfiguration.java` lines 78-94 | exact (self) |
| `com/vn/agent/entity/AiToolCallOutcome.java` (MOD — add `IDEMPOTENT_REPLAY`, `COMMIT_FAILED`) | enum extension | constant | `com/vn/agent/entity/AiToolCallOutcome.java` (self) | exact (self) |
| `com/vn/agent/exposure/AiInternalEntityNames.java` (MOD — add `aiMutation_AiMutationIntent`) | constant set | constant | `com/vn/agent/exposure/AiInternalEntityNames.java` (self) | exact (self) |
| `com/vn/agent/security/AiAgentAdminRole.java` (MOD — add `AiMutationIntent` policy) | security role | config | `com/vn/agent/security/AiAgentAdminRole.java` (self, line 31 `AiExposureRule` precedent) | exact (self) |
| `com/vn/agent/tools/AgentToolCallbacks.java` (MOD — wire mutation + link tools via `ObjectProvider`) | callback assembly | request-response | `com/vn/agent/tools/AgentToolCallbacks.java` (self) | exact (self) |
| `com/vn/agent/guard/AgentSystemPromptRules.java` (MOD — gated mutation rules) | prompt constants | constant | `com/vn/agent/guard/AgentSystemPromptRules.java` (self) | exact (self) |
| `resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml` (NEW) | liquibase changelog | migration | `060-ai-exposure-rule.xml` | exact |
| `resources/com/vn/agent/messages_en.properties` (MOD) | locale bundle | config | self (lines 320-328 exposure-rule precedent) | exact (self) |
| `resources/com/vn/agent/messages_vi.properties` (MOD) | locale bundle | config | self (lines 322-330) | exact (self) |
| `src/test/java/com/vn/agent/tools/mutation/*Test.java` (NEW — TEST-10..13) | test | request-response | `src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java` (layout) + `audit/AuditDurabilityTest.java` (REQUIRES_NEW) | exact (mutation siblings) |

## Pattern Assignments

### `com/vn/agent/tools/mutation/BuiltInMutationTools.java` (NEW @Component, conditional)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`

**Class header pattern** (lines 32-92) — copy class shape verbatim, ADD `@ConditionalOnProperty`:
```java
@Component
@ConditionalOnProperty(prefix = "ai-agent.tools.mutation", name = "enabled", havingValue = "true")
public class BuiltInMutationTools {

    private final DataManager dataManager;                         // regular DM (NOT Unconstrained) for host writes
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final LlmExposurePolicy llmExposurePolicy;
    private final AccessManager accessManager;                     // NEW vs analog — per-attribute gating
    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final ToolResultFormatter toolResultFormatter;
    private final ToolEntityResolver toolEntityResolver;           // NEW shared helper (MUT-09)
    private final MutationGuard mutationGuard;                     // NEW SPI
    private final MutationErrorTranslator mutationErrorTranslator; // NEW
    private final MutationIntentRepository mutationIntentRepository;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final AiAgentAuditProperties auditProperties;
    private final AiAgentMutationProperties mutationProperties;
    private final ObjectMapper objectMapper;

    // ctor — constructor injection (CLAUDE.md mandate)
}
```

**@Tool method body pattern** (lines 175-214 `findRecords` shape) — adapt for mutation gating chain:
```java
@Tool(name = "create_record", description = "...5-section per D-06...")
public String createRecord(
        @ToolParam(description = "...") String entityName,
        @ToolParam(description = "...") Map<String, Object> attributes,
        @ToolParam(description = "...") String idempotencyKey) {
    try {
        // ① resolve (Phase 10 R4 opacity preserved)
        MetaClass metaClass = toolEntityResolver.resolveWritableEntityOrThrow(entityName);
        // ② AccessManager + per-attribute canModify (Research Pattern 1)
        // ③ pre-host-save idempotency reservation + request hash
        // ④ validateWritableProperty + FilterLiteralValueConverter coercion per attribute
        //    (relationship targets also pass LlmExposurePolicy)
        // ⑤ MutationGuard.check with post-coercion typed attributes
        // ⑥ DataManager.save inside @Transactional helper
        // ⑦ MutationIntentRepository.markCommitted / markFailed / markCommitUnknown
        // ⑧ AuditWriter.writeToolCall (REQUIRES_NEW — survives ⑥ rollback)
        return toolResultFormatter.toJson(Map.of(
                "outcome", AiToolCallOutcome.SUCCESS.getId(),
                "entityId", entityId,
                "instanceName", instanceName));
    } catch (ToolUserError err) {
        return toolResultFormatter.error(err);
    } catch (Throwable t) {
        return toolResultFormatter.error(mutationErrorTranslator.translate(t, "create_record", metaClass));
    }
}
```

**Differs from analog:**
- `BuiltInDataTools` uses `dataManager.load(...)` only; `BuiltInMutationTools` uses `dataManager.create(...) + setValue + save` plus a fail-closed gating chain BEFORE `save`. Guard input is post-coercion, and writable-property validation rejects PK/version/read-only/transient/calculated/system fields before any `EntityValues.setValue`.
- ASM read-only test (`BuiltInDataToolsReadOnlyTest`) MUST stay green — mutation tools live in a separate class so the test scope is unchanged (CONTEXT.md "preserves the v1.0 ASM read-only test").
- 5-section description per D-06 (~50-150 lines) vs analog's one-liner descriptions.

---

### `com/vn/agent/tools/link/BuiltInLinkTools.java` (NEW @Component, always-on)

**Analog:** `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/ViewsDiscoveryTool.java`

**Imports + ctor pattern** (lines 1-37 of analog):
```java
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

@Component
public class BuiltInLinkTools {
    private final ServerProperties serverProperties;
    private final ViewRegistry viewRegistry;
    private final Metadata metadata;
    private final LlmExposurePolicy llmExposurePolicy;       // NEW vs analog (Phase 10 opacity)
    private final ToolEntityResolver toolEntityResolver;     // NEW vs analog (uniform unknown_entity)
    private final ToolResultFormatter toolResultFormatter;   // NEW vs analog (JSON-shape result)
    // ctor with constructor injection
}
```

**Detail-link pattern** (analog lines 109-129 — copy `substringBeforeLast` trim verbatim):
```java
public String generateEntityDetailLink(String entityName, String entityId) {
    String viewRoute = getEntityDetailViewRoute(entityName);
    viewRoute = StringUtils.substringBeforeLast(viewRoute, "/");      // strip "/:id"
    if (StringUtils.isEmpty(viewRoute)) return null;
    return "%s/%s/%s".formatted(getServletContextPath(), viewRoute, entityId);
}

@Nullable
private String getRoute(ViewInfo viewInfo) {
    Route routeAnnotation = AnnotationUtils.findAnnotation(viewInfo.getControllerClass(), Route.class);
    return routeAnnotation != null ? routeAnnotation.value() : null;
}
```

**Differs from analog:**
- Replace `metadataTools.getAllJpaEntityMetaClasses().stream().filter(...)` (analog lines 63-69) with `toolEntityResolver.resolveReadableEntityOrThrow(entityName)` so denial collapses into `unknown_entity` per Phase 10 R4.
- Wrap result as JSON via `toolResultFormatter.toJson(Map.of("url", contextPath + "/" + route))` instead of returning bare String.
- 5-section description per D-06 (~30-40 lines per CONTEXT.md guidance) vs analog's tight 4-line text.
- Audit via `AuditWriter.writeToolCall` is provided by `ToolCallbackAuditDecorator` wrapping (no manual call needed inside tool body — see `AgentToolCallbacks.forCurrentUser` line 70).

---

### `com/vn/agent/tools/mutation/MutationErrorTranslator.java` (NEW)

**Analog:** No exact analog. Composite of:
1. `BuiltInDataTools.resolveReadableEntityOrThrow` (line 349-373) — `unknown_entity` opacity
2. `BuiltInDataTools.parseEntityId` (lines 398-407) — `parameter_conversion_error` via `FilterLiteralValueConverter`
3. `BuiltInDataTools.getRecord` line 252 — `not_found` shape

**Skeleton from RESEARCH Example 4** (lines 567-602 of 11-RESEARCH.md):
```java
@Component
public class MutationErrorTranslator {
    public ToolUserError translate(Throwable thrown, String toolName, MetaClass metaClass) {
        if (thrown instanceof org.springframework.dao.OptimisticLockingFailureException
                || thrown instanceof jakarta.persistence.OptimisticLockException) {
            return new ToolUserError("concurrent_modification",
                    metaClass.getName() + " was modified concurrently",
                    List.of("call get_record to fetch current state, then retry with a fresh idempotencyKey"));
        }
        if (thrown instanceof org.springframework.security.access.AccessDeniedException
                || thrown instanceof io.jmix.security.AccessDeniedException) {
            return new ToolUserError("access_denied",
                    "operation not permitted on " + metaClass.getName(),
                    List.of("do not retry; surface to user"));
        }
        if (thrown instanceof jakarta.validation.ConstraintViolationException
                || thrown instanceof org.springframework.dao.DataIntegrityViolationException) {
            return new ToolUserError("validation_failed", ...);
        }
        if (thrown instanceof ToolUserError tue) {
            return sanitizeStableToolUserError(tue.toDto().error(), metaClass);
        }
        // Default: never echo exception message into LLM result string (P-22)
        return new ToolUserError("validation_failed", "operation failed", ...);
    }
}
```

**Type-coercion error pattern** — reuse `FilterLiteralValueConverter.convertValue(raw, metaProperty)` (it already throws `ToolUserError("parameter_conversion_error", ...)` per Research "Don't Hand-Roll" table).

**Differs from analog:** No analog file — this is a new translator. Catches BOTH `jakarta.persistence.OptimisticLockException` AND `org.springframework.dao.OptimisticLockingFailureException` per RESEARCH Pitfall #5.

---

### `com/vn/agent/tools/mutation/AiAgentMutationProperties.java` (NEW)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java`

**Full class to mirror** (lines 1-42):
```java
package com.vn.agent.tools.mutation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("ai-agent.tools.mutation")
public record AiAgentMutationProperties(
        Boolean enabled,                  // default false
        Boolean allowDelete,              // default false (forward signal — no method ships)
        Boolean confirmationRequired,     // default true (UX hint only)
        Duration idempotencyTtl) {        // default 24h

    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean resolvedAllowDelete() {
        return Boolean.TRUE.equals(allowDelete);
    }

    public boolean resolvedConfirmationRequired() {
        return !Boolean.FALSE.equals(confirmationRequired);
    }

    public Duration resolvedIdempotencyTtl() {
        return idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
    }
}
```

**Differs from analog:**
- Different prefix (`ai-agent.tools.mutation` vs `jmix.ai-agent.audit`).
- Adds `Duration` field (analog has only `Set<String>` + `Boolean`).
- Auto-discovered by existing `@ConfigurationPropertiesScan` on `AIConfiguration` (line 33) — no extra wiring.

---

### `com/vn/agent/tools/mutation/AiMutationIntent.java` (NEW @JmixEntity, agentstore)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java`

**Class header + annotations** (analog lines 35-45):
```java
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "aiMutation_AiMutationIntent")
@Table(name = "AI_MUTATION_INTENT", indexes = {
        @Index(name = "IDX_AI_MUT_INTENT_DEDUP",
               columnList = "TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME",
               unique = true),
        @Index(name = "IDX_AI_MUT_INTENT_EXPIRES_AT", columnList = "EXPIRES_AT")
})
public class AiMutationIntent {

    @Id @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull @Column(name = "TOOL_NAME", nullable = false, length = 64)
    private String toolName;
    // ... fields per RESEARCH Example 1
}
```

**Field set** — verbatim from RESEARCH Example 1 (lines 414-449): `id, version, toolName(@InstanceName), idempotencyKey, userUsername, conversationId, resultEntityId, resultEntityName, createdAt, expiresAt`. NOTE: per BLOCKER-02 comment in `AiExposureRule.java` lines 55-58, the column-level `unique=true` flag is REMOVED — uniqueness comes from `@Index(unique=true)` only.

**Differs from analog:**
- No mode/enabled flags (no admin-governance dimension; this is plumbing).
- No created_by/last_modified_by audit columns (system-internal entity; never user-edited).
- 3 different unique-index columns (composite); analog has single-column unique index.

---

### `com/vn/agent/tools/mutation/MutationIntentRepository.java` (NEW)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java`

**Full pattern to mirror** (analog lines 20-43):
```java
@Component
public class MutationIntentRepository {

    private final UnconstrainedDataManager dataManager;   // MEMORY feedback_jmix_unconstrained_for_system_writes
    private final TransactionTemplate agentstoreRequiresNew;

    public MutationIntentRepository(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Optional<AiMutationIntent> findExisting(String toolName, String idempotencyKey, String userUsername) {
        return dataManager.load(AiMutationIntent.class)
                .query("select e from aiMutation_AiMutationIntent e " +
                       "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                .parameter("toolName", toolName)
                .parameter("key", idempotencyKey)
                .parameter("user", userUsername)
                .optional();
    }

    public ReservationResult reserveOrReplay(...) {
        // wrap transactionTemplate.execute(...) in try/catch so duplicate-key
        // failures raised at commit time are caught by this outer method
    }

    public int deleteExpired(OffsetDateTime now) { /* loop + dataManager.remove or bulk JPQL */ }
}
```

**Differs from analog:**
- Adds `reserveOrReplay(...)`, `markCommitted(...)`, `markFailed(...)`, `markCommitUnknown(...)`, and cleanup/diagnostic methods (analog is read-only).
- Per Phase 11 review resolution: reserve PENDING before host save so the unique index prevents duplicate concurrent host writes. `reserveOrReplay` uses an agentstore `TransactionTemplate` and catches around `execute(...)` because unique-index races can surface at transaction commit, after the callback body returns. After host save returns, finalize to COMMITTED; if finalization fails after host save returned, use COMMIT_UNKNOWN or leave PENDING, never FAILED/reclaimable.
- TEST-12 uses a package-private `MutationIntentFailureProbe` test seam, not a mocked repository, so reservation/replay rows remain real while `markCommitted(...)` can be made to fail before COMMITTED.
- Store routing auto-resolved from `@Store(name="agentstore")` — fluent `dataManager.load(Class)` does NOT need explicit `.store(...)` per analog Javadoc lines 14-18; only raw-JPQL `loadValue/loadValues` needs it (MEMORY `feedback_jmix_loadvalue_store`).

---

### `com/vn/agent/tools/mutation/MutationIntentCleanupJob.java` (NEW @Scheduled)

**Analog:** No `@Scheduled` job exists in the codebase. Closest pattern source:
- `LlmExposureRuleRepository.java` (UnconstrainedDataManager + JPQL)
- RESEARCH Example Q6 (lines 720-737)

**Pattern to copy** (RESEARCH Q6):
```java
@Component
public class MutationIntentCleanupJob {

    private final MutationIntentRepository repository;

    public MutationIntentCleanupJob(MutationIntentRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly
    @Transactional("agentstoreTransactionManager")
    public void deleteExpiredIntents() {
        OffsetDateTime now = OffsetDateTime.now();
        int removed = repository.deleteExpired(now);
        int staleInFlight = repository.countExpiredInFlight(now);
        // log removed rows at debug and stale PENDING/COMMIT_UNKNOWN at warn
    }
}
```

**Differs from analog:**
- Requires `@EnableScheduling` activation. Per RESEARCH Q6 finding: `@EnableScheduling` is NOT yet present (only `@EnableAsync` on `AIConfiguration` line 34). Plan 11-02 adds it directly to `AIConfiguration`.
- Multi-store transaction manager: `@Transactional("agentstoreTransactionManager")` — `AiMutationIntent` lives in `agentstore`, NOT default store.
- `repository.deleteExpired(...)` deletes expired `COMMITTED`/`FAILED` rows only. `repository.countExpiredInFlight(...)` reports stale `PENDING`/`COMMIT_UNKNOWN` rows without deleting them.

---

### `com/vn/agent/spi/MutationGuard.java` + `com/vn/agent/spi/MutationIntent.java` (NEW SPI pair)

**Analog for `MutationGuard`:** `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java`

**Full ToolGuard verbatim** (analog lines 1-31):
```java
package com.vn.agent.spi;

public interface ToolGuard {
    /**
     * @param toolName  the {@code @Tool} name being invoked
     * @param arguments the resolved tool arguments as a map
     * @throws ToolVetoedException when the invocation must be blocked
     */
    void check(String toolName, Map<String, Object> arguments) throws ToolVetoedException;
}
```

**MutationGuard mirror** (per CONTEXT.md D-03):
```java
package com.vn.agent.spi;

public interface MutationGuard {
    /**
     * @param intent typed mutation call descriptor (toolName, metaClass, entityId, attributes)
     * @throws ToolVetoedException when the mutation must be blocked (audit row outcome=BLOCKED)
     */
    void check(MutationIntent intent) throws ToolVetoedException;
}

public record MutationIntent(
        String toolName,
        MetaClass metaClass,
        @Nullable UUID entityId,         // null on create_record
        Map<String, Object> attributes) {}
```

**Differs from analog:**
- Replaces `String toolName + Map<String,Object> arguments` with single typed `MutationIntent` record (CONTEXT.md D-03: forward-compatible — extra fields can be added behind default methods later).
- Reuses `ToolVetoedException` verbatim — NO new exception class (CONTEXT.md "ToolVetoedException reused as-is").

**Default no-op bean registration** — analog `ToolFetchPlanCustomizer` registration in `SpiDefaultsAutoConfiguration` (per CONTEXT.md), but project search shows no `SpiDefaultsAutoConfiguration` class yet — `ToolFetchPlanCustomizer` Javadoc references it (lines 14-17). The `@ConditionalOnMissingBean` pattern in `AIConfiguration` line 79 is the existing project precedent:
```java
@Bean
@ConditionalOnMissingBean(MutationGuard.class)
public MutationGuard noopMutationGuard() {
    return intent -> { /* no-op default; hosts override by declaring own @Component */ };
}
```

---

### `com/vn/agent/tools/ToolEntityResolver.java` (NEW shared @Component)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` lines 333-407 (helpers being extracted)

**Source helpers to extract** (verbatim — copy then refactor `BuiltInDataTools` to call the new bean):
```java
// FROM BuiltInDataTools.java line 349-373
public MetaClass resolveReadableEntityOrThrow(String entityName) {
    if (entityName == null || entityName.isBlank()) {
        throw new ToolUserError("unknown_entity", "entity name must not be blank", UnknownEntityHints.AS_LIST);
    }
    MetaClass metaClass;
    try {
        metaClass = metadata.getClass(entityName);
    } catch (RuntimeException re) {
        throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
    }
    if (metaClass == null) {
        throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
    }
    if (!llmExposurePolicy.canReadEntity(metaClass)) {
        // Phase 10 Fix R4 — uniform opacity
        throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
    }
    return metaClass;
}

// FROM BuiltInDataTools.java line 398-407
public Object parseEntityId(String id, MetaClass metaClass) {
    if (id == null || id.isBlank()) {
        throw new ToolUserError("invalid_id", "id must not be blank");
    }
    MetaProperty pk = metadataTools.getPrimaryKeyProperty(metaClass);
    if (pk == null) {
        throw new ToolUserError("invalid_id", "entity " + metaClass.getName() + " has no primary key");
    }
    return filterLiteralValueConverter.convertValue(id, pk);
}

// FROM BuiltInDataTools.java line 333-342
public Set<String> llmReadableAttributes(MetaClass metaClass, Set<String> readableAttributeNames) { ... }
```

**NEW method (write-side)** — adds opacity layer for `canModify`:
```java
public MetaClass resolveWritableEntityOrThrow(String entityName) {
    MetaClass metaClass = resolveReadableEntityOrThrow(entityName);  // R4 opacity for unknown/hidden
    if (!llmExposurePolicy.canModify(metaClass)) {
        // CONTEXT.md "specifics" — entity visible but write denied: deliberate small info leak
        throw new ToolUserError("access_denied",
                "modification not permitted on " + metaClass.getName(),
                List.of("do not retry; surface to user"));
    }
    return metaClass;
}
```

**Differs from analog:** Helpers move from private methods inside `BuiltInDataTools` to a public `@Component`; `BuiltInDataTools` constructor gains a `ToolEntityResolver` dependency and its private helper methods delegate. ASM read-only test scope unchanged.

---

### `com/vn/agent/security/AiAgentMutationRole.java` (NEW @ResourceRole)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java` (narrow role) + `AiAgentAdminRole.java` (full-CRUD precedent)

**Pattern** (analog `AiAgentUserRole` lines 21-31, plus empty marker form per CONTEXT.md Claude's Discretion):
```java
@ResourceRole(name = "AI Agent Mutation", code = AiAgentMutationRole.CODE)
public interface AiAgentMutationRole {

    String CODE = "ai-agent-mutation";
}
```

**Differs from analog:**
- No `@MenuPolicy/@ViewPolicy` (CONTEXT.md "Deferred Ideas" — no admin list view in v1.1).
- No host-entity policies — host composes (SEC-07 mandate).
- No `AiMutationIntent` READ policy — replay uses `UnconstrainedDataManager`, and exposing dedup rows would leak keys/usernames/conversation IDs/result IDs.

---

### `com/vn/agent/AIConfiguration.java` (MODIFIED)

**Analog:** self lines 31-94

**Add `@EnableScheduling` import + annotation** (precedent: `@EnableAsync` line 34):
```java
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;   // NEW

@Configuration
@ComponentScan
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling                                                    // NEW (Q6 — required by MutationIntentCleanupJob)
@JmixModule(...)
@PropertySource(...)
public class AIConfiguration { ... }
```

**Add no-op `MutationGuard` bean** (precedent: `aiAgentIngestExecutor` `@ConditionalOnMissingBean` lines 78-79):
```java
@Bean
@ConditionalOnMissingBean(MutationGuard.class)
public MutationGuard noopMutationGuard() {
    return intent -> { /* default no-op; hosts override via own @Component */ };
}
```

**Differs from analog (self):** Two annotation/bean additions only. Existing executor/configuration unchanged.

---

### `com/vn/agent/entity/AiToolCallOutcome.java` (MODIFIED — enum extension)

**Analog:** self (lines 1-35)

**Existing values to extend**:
```java
public enum AiToolCallOutcome implements EnumClass<String> {

    SUCCESS("SUCCESS"),
    BLOCKED("BLOCKED"),
    ERROR("ERROR"),
    FLAGGED("FLAGGED"),
    IDEMPOTENT_REPLAY("IDEMPOTENT_REPLAY"),    // NEW (Phase 11 D-08)
    COMMIT_FAILED("COMMIT_FAILED");            // NEW (Phase 11 D-08)
    // existing fromId/getId — no changes
}
```

**Differs from analog (self):** Add 2 enum values. No schema migration required — column is `varchar` String-backed via `EnumClass<String>` (D-08).

---

### `com/vn/agent/exposure/AiInternalEntityNames.java` (MODIFIED)

**Analog:** self (lines 1-30)

**Set extension** (line 17 precedent — add 1 entry):
```java
private static final Set<String> NAMES = Set.of(
        "ai_AiAuditEvent",
        "ai_AiConversation",
        "ai_AiMessage",
        "ai_AiKnowledgeDocument",
        "ai_AiParameters",
        "aiExposure_AiExposureRule",
        "aiMutation_AiMutationIntent"          // NEW (Phase 11)
);
```

---

### `com/vn/agent/security/AiAgentAdminRole.java` (MODIFIED)

**Analog:** self (line 31 — `AiExposureRule` precedent from Phase 10)

**Extension pattern**:
```java
import com.vn.agent.tools.mutation.AiMutationIntent;     // NEW import

@EntityPolicy(entityClass = AiMutationIntent.class, actions = EntityPolicyAction.ALL)   // NEW (after line 31)
void adminAccess();
```

**No `@MenuPolicy/@ViewPolicy` additions** per RESEARCH Open Question 2 (no admin list view ships in v1.1).

---

### `com/vn/agent/tools/AgentToolCallbacks.java` (MODIFIED)

**Analog:** self (lines 30-92)

**Constructor injection extension via `ObjectProvider`** (RESEARCH Q5 mechanic) — modify lines 32-48:
```java
public AgentToolCallbacks(BuiltInDataTools builtIns,
                          BuiltInLinkTools builtInLinkTools,                       // NEW always-on
                          ObjectProvider<BuiltInMutationTools> mutationToolsProvider, // NEW conditional
                          List<ToolContributor> contributors,
                          AuditWriter auditWriter,
                          CurrentAuthentication currentAuthentication,
                          StreamingSinkHolder streamingSinkHolder) {
    this.builtIns = builtIns;
    this.builtInLinkTools = builtInLinkTools;
    this.mutationToolsProvider = mutationToolsProvider;
    // ... existing assignments
}
```

**`forCurrentUser` extension** (modify lines 57-74 — RESEARCH Q5 lines 702-705):
```java
public ToolCallback[] forCurrentUser() {
    List<ToolCallback> all = new ArrayList<>();
    Collections.addAll(all, fromBean(builtIns));                      // 6 read tools
    Collections.addAll(all, fromBean(builtInLinkTools));              // 2 link tools (always)
    BuiltInMutationTools mutationTools = mutationToolsProvider.getIfAvailable();
    if (mutationTools != null) {
        Collections.addAll(all, fromBean(mutationTools));             // 4 mutation tools (cond)
    }
    for (ToolContributor tc : contributors) { /* existing */ }
    // existing decorator wrapping unchanged
}
```

**Differs from analog (self):** 2 new constructor params (`BuiltInLinkTools` always-on, `ObjectProvider<BuiltInMutationTools>` conditional). RESEARCH Q5 explicitly warns AGAINST `@Autowired(required=false)` field injection — must use `ObjectProvider` constructor injection.

---

### `com/vn/agent/guard/AgentSystemPromptRules.java` (MODIFIED — gated mutation rules)

**Analog:** self (lines 35-71)

**Existing constant pattern to extend** (line 45):
```java
public static final String PROMPT_RULES = String.join("\n",
        "",
        "Vocabulary rules:",
        // ... existing rules unchanged
);

// NEW Phase 11: gated mutation rules — only inserted when mutation.enabled=true
public static final String MUTATION_PROMPT_RULES = String.join("\n",
        "",
        "Mutation tool rules (only when mutation tools are enabled):",
        "- Reuse an idempotencyKey ONLY for an exact retry with identical arguments.",
        "- If you change any values after validation_failed or parameter_conversion_error, use a fresh idempotencyKey.",
        "- On 'access_denied' do NOT retry — surface to the user.",
        "- On 'parameter_conversion_error' re-read describe_entity attributeType and retry with corrected types.",
        "- On 'concurrent_modification' call get_record or find_records to verify state. If the tool result says the commit outcome is unknown, do not retry automatically; ask the user before any further mutation.",
        "- On success, you may call generate_entity_detail_link to render a verify-link.",
        ""
);
```

**Wiring** — `SystemPromptComposer.compose` (line 14-20, currently appends `PROMPT_RULES` unconditionally) extends with conditional `MUTATION_PROMPT_RULES` injection driven by `AiAgentMutationProperties.resolvedEnabled()`. Planner picks injection site (composer signature change vs. property-aware constant assembly).

**Differs from analog (self):** Adds 1 new constant; constant is conditional on `mutation.enabled=true`. Must NOT mention `prepare_form_draft` (Phase 14 forward reference per CONTEXT.md).

---

### `resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml` (NEW)

**Analog:** `resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml`

**Full changelog from RESEARCH Example 2** (lines 454-503) — copy template:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <changeSet id="1" author="ai-agent">
    <createTable tableName="AI_MUTATION_INTENT">
      <column name="ID" type="${uuid.type}">
        <constraints primaryKey="true" nullable="false"/>
      </column>
      <column name="VERSION" type="int" defaultValueNumeric="1">
        <constraints nullable="false"/>
      </column>
      <column name="TOOL_NAME" type="varchar(64)"><constraints nullable="false"/></column>
      <column name="IDEMPOTENCY_KEY" type="varchar(64)"><constraints nullable="false"/></column>
      <column name="USER_USERNAME" type="varchar(255)"><constraints nullable="false"/></column>
      <column name="CONVERSATION_ID" type="${uuid.type}"/>
      <column name="RESULT_ENTITY_ID" type="${uuid.type}"/>
      <column name="RESULT_ENTITY_NAME" type="varchar(255)"/>
      <column name="CREATED_AT" type="datetime"><constraints nullable="false"/></column>
      <column name="EXPIRES_AT" type="datetime"><constraints nullable="false"/></column>
    </createTable>
  </changeSet>
  <changeSet id="2" author="ai-agent">
    <createIndex indexName="IDX_AI_MUT_INTENT_DEDUP"
                 tableName="AI_MUTATION_INTENT" unique="true">
      <column name="TOOL_NAME"/>
      <column name="IDEMPOTENCY_KEY"/>
      <column name="USER_USERNAME"/>
    </createIndex>
    <createIndex indexName="IDX_AI_MUT_INTENT_EXPIRES_AT" tableName="AI_MUTATION_INTENT">
      <column name="EXPIRES_AT"/>
    </createIndex>
  </changeSet>
</databaseChangeLog>
```

**Inclusion** — auto-discovered by parent `agentstore-changelog.xml` line 14: `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog" .../>` — NO manual include needed (precedent: 060 already auto-included).

**Differs from analog (060):** Different table/columns; composite unique index instead of single-column. Same `${uuid.type}` token, same `datetime` type convention, same separate `<changeSet>` per index per RESEARCH Q7.

---

### `resources/com/vn/agent/messages_en.properties` + `messages_vi.properties` (BOTH MODIFIED)

**Analog (en):** self lines 320-328 (`AiExposureRule` block)
**Analog (vi):** self lines 322-330 (matching VI translations)

**Required new keys** (per CONTEXT.md ENT-09 + MUT-11 — examples):
```properties
# Entity captions (BOTH locales)
com.vn.agent.tools.mutation/AiMutationIntent=AI Mutation Intent              # en
com.vn.agent.tools.mutation/AiMutationIntent.toolName=Tool name              # en
com.vn.agent.tools.mutation/AiMutationIntent.idempotencyKey=Idempotency key  # en
# ... per entity field

# Tool error reasons (NEW for MUT-07)
ai-agent.tool.error.access_denied=Operation not permitted
ai-agent.tool.error.validation_failed=Value validation failed
ai-agent.tool.error.idempotency_violation=Idempotency conflict
ai-agent.tool.error.concurrent_modification=Record was modified concurrently
ai-agent.tool.error.parameter_conversion_error=Invalid parameter format
ai-agent.tool.error.not_found=Record not found

# AiToolCallOutcome enum captions (extension)
com.vn.agent.entity/AiToolCallOutcome.IDEMPOTENT_REPLAY=Idempotent replay
com.vn.agent.entity/AiToolCallOutcome.COMMIT_FAILED=Commit failed
```

**Differs from analog (self):** New entity prefix `com.vn.agent.tools.mutation/` (mirror `com.vn.agent.exposure/AiExposureRule` precedent). VI translations mandatory per CLAUDE.md "ALL locale files".

**Pitfall (RESEARCH Pitfall 7):** tool-protocol English strings (e.g. `PROMPT_RULES`, `MUTATION_PROMPT_RULES`) live in Java constants, NOT message bundles. Only user-facing UI captions go to bundles.

---

### Tests for TEST-10..13 (NEW)

**Analog (test layout):** `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java`

**Mirror layout** (analog package + filename precedent — sibling tests in same package):
```
src/test/java/com/vn/agent/tools/mutation/
├── BuiltInMutationToolsAccessGatingTest.java          # TEST-10 — per-attribute denial
├── BuiltInMutationToolsIdempotencyReplayTest.java     # TEST-11 — same key → IDEMPOTENT_REPLAY
├── BuiltInMutationToolsCommitFailedAuditTest.java     # TEST-12 — REQUIRES_NEW boundary
└── AgentToolCallbacksDefaultConfigTest.java           # TEST-13 — zero mutation callbacks under default config
```

**TEST-12 REQUIRES_NEW pattern source:** existing `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java` already exercises the `AuditWriter.writeToolCall` REQUIRES_NEW boundary — same `@SpringBootTest` + mock-throw pattern applies.

**TEST-13 callback-count assertion target:** `AgentToolCallbacks.forCurrentUser()` (D-09). Default config: assert array length = 8 (6 data + 2 link), assert no callback name matches `create_record|update_record|add_related_record|remove_related_record|delete_record`. With `mutation.enabled=true` property set: array length = 12.

**Differs from analog:**
- `BuiltInDataToolsReadOnlyTest` is bytecode/ASM — pure static check.
- TEST-10..12 are `@SpringBootTest` integration tests with focused test seams: AccessManager for gating, `MutationIntentFailureProbe` for COMMIT_FAILED finalization failure, and a separate save-rollback case for stable ERROR behavior.
- TEST-13 is `@SpringBootTest` with property-driven bean assembly.

---

## Shared Patterns

### Audit row write (AuditWriter.writeToolCall)

**Source:** `com/vn/agent/audit/AuditWriter.java` lines 149-189
**Apply to:** All 4 mutation tool methods AND both 2 link tool methods (latter via `ToolCallbackAuditDecorator` automatic wrap)
```java
auditWriter.writeToolCall(
        runContext.rootAuditId(),
        runContext.runId(),
        currentAuthentication.getUser().getUsername(),
        runContext.conversationId(),
        toolName,                              // "create_record" / "update_record" / etc.
        argumentsJson,                         // full tool args JSON; sensitive attribute values hashed
        resultSummary,                         // diff JSON for update / created id+name for create / rel action for related
        latencyMs,
        AiToolCallOutcome.SUCCESS,             // or IDEMPOTENT_REPLAY / BLOCKED / ERROR / COMMIT_FAILED
        denialReason,                          // null on success; ToolVetoedException.getMessage() on BLOCKED
        errorClass);                           // null on success; thrown class FQN on ERROR/COMMIT_FAILED
```
REQUIRES_NEW boundary at AuditWriter line 87 survives caller rollback (TEST-12 design).

### Tool error envelope (ToolUserError + ToolResultFormatter.error)

**Source:** `com/vn/agent/tools/ToolUserError.java` (lines 16-31) + `BuiltInDataTools.java` line 110-111 catch pattern
**Apply to:** Every `@Tool` method body in `BuiltInMutationTools` and `BuiltInLinkTools`
```java
} catch (ToolUserError toolUserError) {
    return toolResultFormatter.error(toolUserError);
}
```

### UnconstrainedDataManager for system-internal writes

**Source:** `com/vn/agent/exposure/LlmExposureRuleRepository.java` lines 23-27 + MEMORY `feedback_jmix_unconstrained_for_system_writes`
**Apply to:** `MutationIntentRepository`, `MutationIntentCleanupJob`. NEVER on the host save (CONTEXT.md "Anti-Patterns" — host save uses regular `DataManager` so user policies and listeners apply).

### Constructor injection (CLAUDE.md mandate)

**Apply to:** ALL new `@Component` classes (`BuiltInMutationTools`, `BuiltInLinkTools`, `MutationErrorTranslator`, `MutationIntentRepository`, `MutationIntentCleanupJob`, `ToolEntityResolver`).

### Sensitive-field hashing (AUD-07)

**Source:** `com/vn/agent/audit/AuditFieldHasher.sha256Hex(value)` + `AiAgentAuditProperties.resolvedSensitiveFields()` lines 38-41 + RESEARCH Pattern 3 + Q8
**Apply to:** `BuiltInMutationTools` diff serialization in `update_record`, plus full `argumentsJson` formatting in all 4 mutation tools. `argumentsJson` must include the complete LLM tool arguments (`entityName`, `id` where applicable, `relationship`, `relatedId`, `idempotencyKey`, and nested `attributes`) while hashing sensitive attribute values by key.

### `@Tool` 5-section description (D-06)

**Source:** MEMORY `feedback_rich_tool_descriptions` + `D:/DTH/jmix-crm/.../JpqlExecutorTool.java` (230 lines), `RunReportTool.java` (90 lines)
**Apply to:** All 4 mutation tools (~50-150 lines each) and 2 link tools (~30-40 lines each per RESEARCH Q10). Sections: MANDATORY WORKFLOW / INPUT CONTRACT / PARAMETER FORMATS / ERROR HANDLING / STRICTNESS + ✓-CORRECT/✗-INCORRECT EXAMPLES.

### Liquibase changelog auto-include

**Source:** `resources/com/vn/agent/liquibase/agentstore-changelog.xml` line 14: `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog" .../>`
**Apply to:** New `070-ai-mutation-intent.xml` — drop file in directory; no parent include edit needed.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `MutationIntentCleanupJob.java` | scheduled job | batch | No `@Scheduled` job exists in this codebase yet. Pattern composed from `LlmExposureRuleRepository` (UnconstrainedDataManager) + RESEARCH Q6 (`@Scheduled` cron + `@Transactional("agentstoreTransactionManager")`) + Spring `@Scheduled` standard idiom. Planner must add `@EnableScheduling` to `AIConfiguration` (NOT currently present per RESEARCH A3). |

## Metadata

**Analog search scope:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/{tools,spi,exposure,security,audit,entity,guard,orchestration}/` — primary
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/{liquibase/agentstore-changelog/,messages*.properties}` — resources
- `ai-agent/ai-agent/src/test/java/com/vn/agent/{tools,audit}/` — test layout
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/` — pattern-learning exemplars (NOT a runtime dependency)

**Files scanned:** ~25 source files, 7 changesets, 2 locale bundles, 4 test files (sample read).

**Pattern extraction date:** 2026-04-28

## PATTERN MAPPING COMPLETE

**Phase:** 11 - mutation-capable-built-in-tools
**Files classified:** 21 (16 NEW + 5 MODIFIED)
**Analogs found:** 21 / 21

### Coverage
- Files with exact analog: 19
- Files with role-match (composite or partial) analog: 2 (`MutationErrorTranslator` composite of 2 sources; `MutationIntent` SPI record uses ToolErrorDto record style)
- Files with no analog: 1 (`MutationIntentCleanupJob` — pattern composed from `@Scheduled` standard + `LlmExposureRuleRepository` + RESEARCH Q6)

### Key Patterns Identified
- **All AI agentstore entities follow `AiExposureRule` shape:** `@Store(name="agentstore") + @JmixEntity + @Entity(name="<prefix>_<Name>") + @Table + @JmixGeneratedValue UUID + @Version + @InstanceName + @Index unique=true (NOT column-level unique)`. `AiMutationIntent` adopts this precedent with composite unique index.
- **All system-internal repositories use `UnconstrainedDataManager`** (per `LlmExposureRuleRepository` + MEMORY `feedback_jmix_unconstrained_for_system_writes`); host-data tools use regular `DataManager`. The mutation save path uses regular `DataManager` so user-level row policies and entity listeners fire.
- **All SPIs follow `ToolGuard`/`ToolFetchPlanCustomizer` shape:** single-method interface + reused `ToolVetoedException` + default no-op bean via `@ConditionalOnMissingBean` (precedent: `AIConfiguration.aiAgentIngestExecutor` line 79). `MutationGuard` mirrors this verbatim with typed `MutationIntent` record argument.
- **All security roles follow `AiAgentUserRole`/`AiAgentAdminRole` shape:** `@ResourceRole(name, code) interface` + `@EntityPolicy` per entity + optional `@MenuPolicy/@ViewPolicy`. `AiAgentMutationRole` is empty marker (host composes); `AiAgentAdminRole` extension mirrors line 31 `AiExposureRule` precedent.
- **All `@Tool` callback wiring goes through `AgentToolCallbacks.forCurrentUser`** with `MethodToolCallbackProvider.builder().toolObjects(bean)` reflection (line 87-90). Conditional beans flow via `ObjectProvider<...>` constructor injection per RESEARCH Q5 (NOT `@Autowired(required=false)` — proxy/eager-init brittle).
- **All audit rows reuse `AuditWriter.writeToolCall` REQUIRES_NEW boundary** — no new `AuditKind` (D-08); only new `eventName` strings + 2 new `AiToolCallOutcome` enum values.
- **All `@ConfigurationProperties` records follow `AiAgentAuditProperties` shape** — `record(...) { resolved*() }` with null-tolerant accessors and conservative defaults; auto-discovered by `@ConfigurationPropertiesScan` on `AIConfiguration` line 33.

### File Created
`D:\DTH\ai-agent-core\.planning\phases\11-mutation-capable-built-in-tools\11-PATTERNS.md`

### Ready for Planning
Pattern mapping complete. Planner can now reference each analog file path + line numbers when authoring per-plan action sections in PLAN.md files.
