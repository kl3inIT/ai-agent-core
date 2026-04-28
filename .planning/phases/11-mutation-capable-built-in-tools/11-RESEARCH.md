# Phase 11: Mutation-Capable Built-In Tools — Research

**Researched:** 2026-04-28
**Domain:** Spring AI 1.1.4 `@Tool` mutation surface over Jmix 2.8 `DataManager`
**Confidence:** HIGH (Spring AI + Jmix specifics verified via Context7 `/spring-projects/spring-ai` and `/jmix-framework/jmix-context7`; in-repo helpers verified by reading source)

## Summary

Phase 11 converts the Phase 10 read-only LLM surface into a **conditionally-enabled** mutation surface. Four tools (`create_record`, `update_record`, `add_related_record`, `remove_related_record`) ship behind a single `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled")` flag, plus an always-on `BuiltInLinkTools` (2 tools) for verify-link generation. The fail-closed gating chain has four distinct steps that MUST run in order on every call: `LlmExposurePolicy.canModify` → `AccessManager` `CrudEntityContext` + per-attribute `EntityAttributeContext.canModify` → `MutationGuard.check` → `@Transactional("agentstoreTransactionManager")`-bracketed save? — **No**: mutations operate on **HOST entities in the main store**, not agentstore. The `@Transactional` boundary is therefore the default Jmix transaction manager. Audit, idempotency, and dedup writes target `agentstore` and use a SECOND transactional surface (`AuditWriter` already has REQUIRES_NEW; the new `MutationIntentRepository` mirrors that pattern).

All decisions in CONTEXT.md (D-01..D-09, plus Claude's Discretion items) are honored. No CONTEXT.md decision needs revisiting based on this research.

**Primary recommendation:** Plan slots in this order — (1) `AiToolCallOutcome` enum extension + `AiMutationIntent` entity + Liquibase 070, (2) `ToolEntityResolver` shared helper + `MutationGuard` SPI + default no-op bean, (3) `MutationErrorTranslator` + `BuiltInLinkTools`, (4) `BuiltInMutationTools` + `AiAgentMutationProperties` + `AgentToolCallbacks` wiring, (5) cleanup job + admin role + `AgentSystemPromptRules` extension + locales, (6) tests TEST-10..13. Each plan is < 200 LOC of new code; the entity + Liquibase plan must land first because every later plan imports `AiMutationIntent` or the new enum values.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Mutation tools take `Map<String,Object> attributes` and reference relationships by FK UUID strings inside the same map. Tool signatures verbatim:
  - `create_record(String entityName, Map<String,Object> attributes, String idempotencyKey)` → `{outcome, entityId, instanceName}`
  - `update_record(String entityName, String id, Map<String,Object> attributes, String idempotencyKey)` → `{outcome, entityId, instanceName, diffSummary}`
  - `add_related_record(String entityName, String id, String relationship, String relatedId, String idempotencyKey)` → `{outcome, parentId, relationship, relatedId}`
  - `remove_related_record(String entityName, String id, String relationship, String relatedId, String idempotencyKey)` → `{outcome, parentId, relationship, relatedId}`
- **D-02:** `AiMutationIntent` unique constraint `(tool_name, idempotency_key, user_username)`; row stores `resultEntityId`+`resultEntityName` only (NOT full result JSON); replay re-resolves `instanceName` live; TTL default 24h.
- **D-03:** `MutationGuard.check(MutationIntent intent)` SPI with minimal record shape (toolName + metaClass + entityId + attributes Map). `entityId` null on create. `MutationGuard` mirrors `ToolGuard`. Default no-op bean.
- **D-04:** 6 stable error codes: `access_denied`, `validation_failed`, `idempotency_violation`, `concurrent_modification`, `parameter_conversion_error`, `not_found`. Each carries `ToolErrorDto.expected` hint. `unknown_entity` is reserved for the entity-resolution path (Phase 10 R4 opacity), distinct from `access_denied`.
- **D-05:** `BuiltInLinkTools` always-on (independent of `mutation.enabled`). 2 `@Tool`: `generate_entity_list_link`, `generate_entity_detail_link`. Both gated by `LlmExposurePolicy.canReadEntity` with uniform `unknown_entity` opacity. Returns a JSON object with a single `url` property; detail links validate and URL-encode UUID path segments.
- **D-06:** All 6 new tools (4 mutation + 2 link) use the 5-section description pattern from MEMORY `feedback_rich_tool_descriptions`. Mutation tools especially need ~50–150 line descriptions covering idempotency contract, per-attribute denial recovery, and type-conversion rules. `BuiltInDataTools` is NOT retrofitted in Phase 11.
- **D-07:** `delete_record` NOT shipped. `allowDelete=true` is forward-signal only. TEST-13 explicitly asserts no `delete_record` callback under any flag combination.
- **D-08:** No new `AuditKind`. All 6 events reuse `AuditKind.TOOL`. `AiToolCallOutcome` gains `IDEMPOTENT_REPLAY` + `COMMIT_FAILED` (no schema migration; column is already a String-backed `EnumClass<String>`).
- **D-09:** TEST-13 assertion is at `AgentToolCallbacks.forCurrentUser()`. Default-config callback count = 6 data + 2 link = 8; `mutation.enabled=true` count = 12. ZERO `delete_record` under any combo in v1.1.

### Claude's Discretion

- Package layout: `com.vn.agent.tools.mutation` for `BuiltInMutationTools` + `MutationErrorTranslator` + `AiAgentMutationProperties`; `com.vn.agent.tools.link` for `BuiltInLinkTools`; `com.vn.agent.spi` for `MutationGuard` + `MutationIntent`; `com.vn.agent.tools` for `ToolEntityResolver`.
- `AiMutationIntent` package: planner picks between `com.vn.agent.tools.mutation` (cohesion) vs `com.vn.agent.entity` (alongside other AI-* entities).
- Liquibase: `070-ai-mutation-intent.xml` under `liquibase/agentstore-changelog/`, included in parent `agentstore-changelog.xml`.
- `AiInternalEntityNames` extension: add `aiMutation_AiMutationIntent` (or whatever metaClass name lands).
- Cleanup job: `@Scheduled` `MutationIntentCleanupJob` `@Component` (simpler than JmixApp scheduled-task entity).
- MUT-10 system-prompt wording: bullet list inserted into `AgentSystemPromptRules` only when `mutation.enabled=true`; must NOT mention `prepare_form_draft` (Phase 14).
- `AiAgentMutationRole` shape: empty marker `@ResourceRole` interface; do not grant dedup-table read by default.
- `AiAgentAdminRole` extension: add `AiMutationIntent` CRUD only; no view/menu policies in v1.1.
- `MutationGuard` `SpiDefaultsAutoConfiguration` registration: match `ToolFetchPlanCustomizer` pattern (Phase 9 SPI-09).
- `BuiltInLinkTools` route resolution: per-call (cached `ViewRegistry` is in-memory).
- `AuditWriter.writeToolCall` payload: JSON serialization of `attributes` Map verbatim, with PII fields hashed via `AuditFieldHasher` when attribute name appears in `AiAgentAuditProperties.sensitiveFields`. `resultSummary` shapes per CONTEXT.md.

### Deferred Ideas (OUT OF SCOPE)

- `delete_record` mutation tool — v1.2 (MUT-13).
- `MutationGuard` lifecycle hooks (`beforeCommit`, `afterCommit`, `onRollback`) — only `check(...)` ships.
- `MutationIntent` pre-image lazy `Supplier` — guards reload via `DataManager` themselves in v1.1.
- Bulk mutation (`update_records`, `delete_records`).
- Mutation preview / dry-run mode — `confirmationRequired=true` is UX hint only.
- `AiMutationIntent` admin list view — defer; admins use the audit list.
- Attribute-level per-language `parameter_conversion_error` hint catalogue.
- `AiAgentMutationRole` row-level scoping.
- Retroactive 5-section description retrofit on `BuiltInDataTools`.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| MUT-01 | `BuiltInMutationTools` separate `@Component`, `@ConditionalOnProperty(... enabled, havingValue="true")`, default OFF | `@ConditionalOnProperty` is standard Spring; gated bean's `@Tool` methods are NOT discovered by `MethodToolCallbackProvider` if the bean isn't registered (Spring AI tool callback lookup is bean-driven, not classpath-driven) — see Q5 |
| MUT-02 | 4 tools: `create_record`, `update_record`, `add_related_record`, `remove_related_record`. NO `delete_record`. | Tool methods declared in the new class only. Tests assert callback names match exactly. |
| MUT-03 | Layered fail-closed gating: `LlmExposurePolicy.canModify` → `AccessManager` `CrudEntityContext`+per-attribute `EntityAttributeContext.canModify` → `MutationGuard.check` → `@Transactional` `DataManager.save` (regular DM) | Each step shipped or pre-existing: `canModify` (Phase 10), `CrudEntityContext`/`EntityAttributeContext` (Jmix core, see Q2), `ToolGuard` pattern (Phase 9), `DataManager.save` (Jmix). |
| MUT-04 | Mandatory `idempotencyKey` UUID string; `AiMutationIntent` table; replay returns same `entityId`+`instanceName` with `outcome=IDEMPOTENT_REPLAY`; TTL 24h. | Unique constraint `(tool_name, idempotency_key, user_username)`. Replay re-resolves `instanceName` live (not stored). |
| MUT-05 | `MutationGuard` SPI with default no-op bean | Mirror `ToolFetchPlanCustomizer`/`SpiDefaultsAutoConfiguration` registration pattern (Phase 9). Reuse `ToolVetoedException`. |
| MUT-06 | `AiAgentMutationProperties`: `enabled` (false), `allowDelete` (false; reserved), `confirmationRequired` (true), `idempotencyTtl` (24h) | `@ConfigurationProperties("ai-agent.tools.mutation")` per `AiAgentAuditProperties` pattern. |
| MUT-07 | `MutationErrorTranslator` 6 codes, never echoes user PII or raw exception text | Catch JPA `OptimisticLockException` (Q3), `AccessDeniedException`, `ConstraintViolationException`, `ToolUserError` from resolver. Reuse `FilterLiteralValueConverter` for `parameter_conversion_error`. |
| MUT-08 | Audit reuses `AuditWriter.writeToolCall`; new `eventName` strings; new outcome values; pre/post-image diff in `resultSummary`; PII hashing via `AuditFieldHasher` | Existing REQUIRES_NEW boundary survives mutation rollback (TEST-12). Uses `AiAgentAuditProperties.resolvedSensitiveFields()` for hash list. |
| MUT-09 | `ToolEntityResolver` shared `@Component` consumed by `BuiltInDataTools` (READ path) and `BuiltInMutationTools` (WRITE path). | Extract `resolveReadableEntityOrThrow`, `parseEntityId`, `llmReadableAttributes` from `BuiltInDataTools`. Add new `resolveWritableEntityOrThrow`. |
| MUT-10 | System-prompt rules added when `mutation.enabled=true` | Extend `AgentSystemPromptRules` (Phase 9 extension point). Must NOT reference `prepare_form_draft` (Phase 14). |
| MUT-11 | Locale messages for every denial / success / idempotency / error path | `messages_en.properties` + `messages_vi.properties`. NO untagged `messages.properties` exists in this project — both files must change. |
| MUT-12 | Boot test asserts zero mutation callbacks under default config | TEST-13. `AgentToolCallbacks.forCurrentUser` is the assertion target (D-09). |
| ENT-09 | `AiMutationIntent` Jmix entity in `agentstore` | UUID + `@Version` + `@InstanceName` per CLAUDE.md. Liquibase 070. |
| AUD-06 | Outcome enum `IDEMPOTENT_REPLAY` + `COMMIT_FAILED`; new `eventName` strings | `AiToolCallOutcome` extension only — string-backed `EnumClass<String>`, no migration. |
| AUD-07 | Pre/post-image diff with optional PII hashing | `AiAgentAuditProperties` (Phase 9) + `AuditFieldHasher` (Phase 9) — Phase 11 is first consumer. |
| SEC-07 | `AiAgentMutationRole` resource role; default empty | Host composes with own roles. |
| SPI-10 | `MutationGuard` SPI | See MUT-05. |
| TEST-10 | Per-attribute denial → `access_denied`, `DataManager.save` never called | `@SpringBootTest`. Mock `AccessManager` to deny one attribute; assert tool returns structured `access_denied`. |
| TEST-11 | Same `idempotencyKey` → same result, `outcome=IDEMPOTENT_REPLAY`, only one row | `@SpringBootTest`. Run tool twice; assert dedup row count = 1, second call audit `outcome=IDEMPOTENT_REPLAY`. |
| TEST-12 | Post-flush save throws → audit row written with `outcome=COMMIT_FAILED` | `@SpringBootTest`. Mock `DataManager.save` to throw `OptimisticLockException`; assert audit row exists with that outcome (REQUIRES_NEW separates the boundary). |
| TEST-13 | Default-config boot: zero mutation callbacks in `forCurrentUser()` | `@SpringBootTest`. Default property `mutation.enabled=false`. Assert callback array length = 8 (6 data + 2 link), no callback name matches `create_record|update_record|add_related_record|remove_related_record|delete_record`. |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| Mutation tool callable surface | API/Backend (Spring AI ChatClient on Jmix backend) | — | LLM tool calling is server-side; no browser/client tier for mutation |
| Authorization gating chain | API/Backend (`LlmExposurePolicy` → `AccessManager` → `MutationGuard`) | — | All authoritative checks in JVM; never trust LLM/client |
| Persistence | Database/Storage (host main store via `DataManager`) | — | Host entities live in main store; agentstore is for AI-internal tables only |
| Idempotency dedup | Database/Storage (`agentstore`, `AiMutationIntent`) | — | Same store as audit; same multi-store transaction boundary needed |
| Audit row | Database/Storage (`agentstore`, `AiAuditEvent`) | — | Pre-existing — Phase 11 reuses `AuditWriter` REQUIRES_NEW boundary |
| Deep-link generation | API/Backend (`ViewRegistry` lookup) | Browser/Client (renders Markdown link) | URL is generated server-side; client renders. Server's `ServerProperties.getServlet().getContextPath()` is the canonical context source. |
| Cleanup job | API/Backend (`@Scheduled` Spring component) | — | Hourly TTL reaper; idempotent; runs on app instance |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring AI | 1.1.4 | `@Tool`/`@ToolParam`, `MethodToolCallbackProvider`, `ToolExecutionException`, `ToolExecutionExceptionProcessor` | [VERIFIED: Context7 /spring-projects/spring-ai] Already pinned by Phase 0..10. No version bump needed. |
| Jmix Core | 2.8 | `DataManager`, `Metadata`, `MetadataTools`, `AccessManager`, `CrudEntityContext`, `EntityAttributeContext` | [VERIFIED: Context7 /jmix-framework/jmix-context7] Mutation gating uses these primitives directly. |
| Jmix Security | 2.8 | `@ResourceRole`, `@EntityPolicy`, `@MenuPolicy`, `@ViewPolicy` | [VERIFIED: in-repo `AiAgentAdminRole.java`] Standard role authoring. |
| Jmix Eclipselink | 2.8 | `@Version` optimistic locking; persistence | [VERIFIED: in-repo `AiExposureRule.java`] Versioned trait standard. |
| Spring Framework | 6.x (Boot 3.x) | `@Transactional`, `@ConditionalOnProperty`, `@ConfigurationProperties`, `@Scheduled`, `ApplicationEventPublisher` | [VERIFIED: in-repo] Existing project patterns. |
| Jackson | 2.x (transitive) | JSON serialization for `argumentsJson` and `resultSummary` | [VERIFIED: in-repo `BaselineContextProvider.java` uses `ObjectMapper`] Reuse the same `ObjectMapper` bean. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jmix FlowUI ViewRegistry | 2.8 | `ViewRegistry.findViewInfo`, `getListViewId`, `getDetailViewId` | `BuiltInLinkTools` route resolution. Pattern verified in `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/ViewsDiscoveryTool.java`. |
| Spring Boot Web | 3.x | `ServerProperties.getServlet().getContextPath()` | Link tool prefix construction. |
| `org.apache.commons.lang3.StringUtils` | already on classpath | `substringBeforeLast`, `isEmpty` for the `:id` route trim trick (`Route("departments/:id")` → `departments`) | Pattern proven in `ViewsDiscoveryTool.generateEntityDetailLink`. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Map<String,Object>` tool param shape (D-01) | `String payloadJson` server-side parse | `Map` is the proven pattern (jmix-crm `RunReportTool` ships with `Map<String,Object> parameters`); raw JSON-string fallback is documented as Option D in CONTEXT.md but unnecessary based on Q1 finding. |
| `@Scheduled` cleanup job | JmixApp scheduled-task entity | `@Scheduled` is simpler; matches existing project style (no scheduled-task surface in this codebase). Per CONTEXT.md Claude's Discretion. |
| Cache `ViewRegistry` lookups | Per-call lookup | Per-call is fine; `ViewRegistry` is in-memory and cheap. Per CONTEXT.md. |
| New `AuditKind` for mutations | Reuse `AuditKind.TOOL` (D-08) | Reuse: simpler queries, no schema change, distinguishes via `eventName`. |
| Hand-rolled UUID validation | `FilterLiteralValueConverter.convertValue` (already exists) | Reuse existing converter; it's the canonical Jmix-typed coercion path used by structured filters. |

**Installation:** No new dependencies. All required libraries are already on the classpath via Phase 0..10.

**Version verification:** Run `gradle dependencies` to confirm Spring AI 1.1.4 + Jmix 2.8 are still pinned; no `npm view`-equivalent for Maven Central is needed because we're not adding artifacts.

## Architecture Patterns

### System Architecture Diagram

```
LLM (Spring AI ChatClient)
   │
   │ tool call: {toolName, arguments JSON}
   ▼
┌─────────────────────────────────────────────────────────┐
│ MethodToolCallbackProvider.builder().toolObjects(beans) │  ← AgentToolCallbacks.forCurrentUser
│   discovers @Tool methods on each registered bean       │     (re-runs per request)
└─────────────────────────────────────────────────────────┘
   │
   │ ToolCallbackAuditDecorator (PRE/POST audit per call)
   ▼
┌─────────────────────────────────────────────────────────┐
│ BuiltInMutationTools.{create_record|update_record|...}  │  ← @ConditionalOnProperty gated
│  ① ToolEntityResolver.resolveWritableEntityOrThrow      │     (Phase 10 D-04 R4 opacity)
│  ② AccessManager: CrudEntityContext.isCreatePermitted   │     (or .isUpdatePermitted)
│     PLUS for each LLM-supplied attribute key:           │
│       new EntityAttributeContext(metaClass, attrPath)   │
│       accessManager.applyRegisteredConstraints(ctx)     │
│       ctx.canModify()  → false ⇒ access_denied          │
│  ③ MutationIntentRepository.findExisting(toolName,      │  ── if hit: replay, no save
│       idempotencyKey, userUsername)                     │     return outcome=IDEMPOTENT_REPLAY
│  ④ MutationGuard.check(MutationIntent)                  │  ── ToolVetoedException ⇒ blocked
│  ⑤ FilterLiteralValueConverter.convertValue per attr    │  ── failure ⇒ parameter_conversion_error
│  ⑥ DataManager.create / .load + setValue + .save        │
│       within @Transactional (default txn manager)       │
│       OptimisticLockException ⇒ concurrent_modification │
│       ConstraintViolationException ⇒ validation_failed  │
│  ⑦ MutationIntentRepository.create(record dedup row)    │  REQUIRES_NEW boundary (agentstore)
│  ⑧ AuditWriter.writeToolCall(...)                       │  REQUIRES_NEW (agentstore) — survives ⑥ rollback
└─────────────────────────────────────────────────────────┘
   │
   │ JSON result string {outcome, entityId, instanceName, diffSummary?}
   ▼
LLM (sees structured result; may call generate_entity_detail_link next)
```

### Recommended Project Structure

```
com.vn.agent/
├── tools/
│   ├── BuiltInDataTools.java               # unchanged; calls extracted ToolEntityResolver
│   ├── ToolEntityResolver.java             # NEW @Component (MUT-09)
│   ├── ToolResultFormatter.java            # unchanged
│   ├── AgentToolCallbacks.java             # MODIFIED — wires BuiltInMutationTools (cond) + BuiltInLinkTools
│   ├── mutation/
│   │   ├── BuiltInMutationTools.java       # NEW @Component @ConditionalOnProperty
│   │   ├── AiAgentMutationProperties.java  # NEW @ConfigurationProperties
│   │   ├── MutationErrorTranslator.java    # NEW @Component
│   │   ├── MutationIntentRepository.java   # NEW @Component (UnconstrainedDataManager)
│   │   ├── MutationIntentCleanupJob.java   # NEW @Component @Scheduled
│   │   ├── AiMutationIntent.java           # NEW Jmix entity (or under com.vn.agent.entity)
│   │   └── DiffSerializer.java             # NEW helper for AUD-07 diff JSON shaping
│   └── link/
│       └── BuiltInLinkTools.java           # NEW @Component (always-on)
├── spi/
│   ├── MutationGuard.java                  # NEW SPI (mirrors ToolGuard)
│   └── MutationIntent.java                 # NEW record (toolName, metaClass, entityId, attributes)
├── entity/
│   └── AiToolCallOutcome.java              # MODIFIED — add IDEMPOTENT_REPLAY + COMMIT_FAILED
├── exposure/
│   └── AiInternalEntityNames.java          # MODIFIED — add AiMutationIntent metaClass name
├── security/
│   ├── AiAgentMutationRole.java            # NEW @ResourceRole
│   └── AiAgentAdminRole.java               # MODIFIED — +@EntityPolicy(AiMutationIntent), no view/menu policies in v1.1
└── orchestration/
    └── AgentSystemPromptRules.java         # MODIFIED — bullet list when mutation.enabled
```

Resources:
```
resources/com/vn/agent/
├── liquibase/agentstore-changelog/
│   ├── 070-ai-mutation-intent.xml          # NEW
│   └── (existing 010..061)
├── messages_en.properties                  # MODIFIED — new keys
└── messages_vi.properties                  # MODIFIED — same new keys (CLAUDE.md mandate)
```

### Pattern 1: Per-attribute access check (gating step 2)

**What:** For each key in the LLM-supplied `attributes` Map, instantiate a fresh `EntityAttributeContext` and run `applyRegisteredConstraints` before any value is set on the entity instance.

**When to use:** Step 2 of every mutation call, after entity-level `CrudEntityContext` passes.

**Example:**
```java
// Source: Phase 10 BaselineContextProvider.modifiableAttributesOf (in-repo) + Context7 /jmix-framework/jmix-context7 authorization.html
private void enforceAttributeWriteAccess(MetaClass metaClass, Set<String> attributeNames) {
    for (String attributeName : attributeNames) {
        EntityAttributeContext attr = new EntityAttributeContext(metaClass, attributeName);
        accessManager.applyRegisteredConstraints(attr);
        if (!attr.canModify()) {
            // Phase 10 R4 opacity does NOT apply here — entity was already resolved
            // via canModify (write-side); per-attribute denial is a deliberate small
            // information leak per CONTEXT.md "specifics" section.
            throw new ToolUserError("access_denied",
                "attribute '" + attributeName + "' not modifiable on " + metaClass.getName(),
                List.of("agent.permissions[" + metaClass.getName() + "].modifiable"));
        }
    }
}
```

### Pattern 2: Idempotent replay path

**What:** Before any host save, reserve `(toolName, idempotencyKey, userUsername)` in `AiMutationIntent` with a canonical request hash. On COMMITTED hit with the same hash, re-load the original entity by stored `resultEntityId` + `resultEntityName`, return its FRESH `instanceName` (locale + security re-evaluated), tag outcome as `IDEMPOTENT_REPLAY`. On same-key/different-hash, return `idempotency_violation`. On PENDING/COMMIT_UNKNOWN, return `concurrent_modification` and never run a duplicate host write.

**When to use:** Step 3 of every mutation call.

**Pitfall:** The dedup row write uses the `agentstore` transaction manager; the host entity write uses the default. Two distinct transactions. The Phase 11 plan uses a pre-host-save reservation because it is the only way to stop concurrent duplicate host writes across nodes. If host save fails before commit, mark FAILED and allow same-hash retry. If host save returned but dedup finalization fails, mark COMMIT_UNKNOWN if possible or leave PENDING; never mark FAILED/reclaimable because that can duplicate a committed host write.

**Example:**
```java
// Replay branch
Optional<AiMutationIntent> existing = mutationIntentRepository.findExisting(
        toolName, idempotencyKey, currentAuthentication.getUser().getUsername());
if (existing.isPresent()) {
    AiMutationIntent intent = existing.get();
    MetaClass mc = metadata.getClass(intent.getResultEntityName());
    Object entity = dataManager.load(mc.getJavaClass())
            .id(intent.getResultEntityId())
            .optional().orElse(null);  // null is acceptable — entity may have been deleted
    String freshName = entity != null
            ? metadataTools.getInstanceName(entity)
            : null;
    return formatResult(AiToolCallOutcome.IDEMPOTENT_REPLAY,
            intent.getResultEntityId(), freshName);
}
```

### Pattern 3: Diff serialization with PII hashing (AUD-07)

**What:** For `update_record`, capture pre-image (load via `DataManager` BEFORE mutation; child-free fetch plan) and post-image (after `setValue` calls, before save). Compute attribute-level diff. Serialize as JSON array `[{"attribute","from","to"}]`. For attributes whose simple name is in `AiAgentAuditProperties.resolvedSensitiveFields()`, replace `from`/`to` with `AuditFieldHasher.sha256Hex(stringValue)`.

**When to use:** Just before `auditWriter.writeToolCall(... resultSummary=diffJson ...)`.

**Example:**
```java
// Source: in-repo AuditFieldHasher.java + AiAgentAuditProperties.java (Phase 9 plumbing)
public record AttributeDiff(String attribute, String from, String to) {}

private String serializeDiff(MetaClass metaClass,
                             Map<String, Object> preImage,
                             Map<String, Object> postImage) {
    Set<String> sensitive = auditProperties.resolvedSensitiveFields();
    boolean hash = auditProperties.resolvedHashSensitiveFields();
    List<AttributeDiff> diffs = new ArrayList<>();
    for (String attr : postImage.keySet()) {
        Object from = preImage.get(attr);
        Object to = postImage.get(attr);
        if (Objects.equals(from, to)) continue;
        String fromStr = from == null ? null : from.toString();
        String toStr = to == null ? null : to.toString();
        if (hash && sensitive.contains(attr)) {
            fromStr = AuditFieldHasher.sha256Hex(fromStr);
            toStr = AuditFieldHasher.sha256Hex(toStr);
        }
        diffs.add(new AttributeDiff(attr, fromStr, toStr));
    }
    return objectMapper.writeValueAsString(diffs);
}
```

### Anti-Patterns to Avoid

- **DO NOT use `UnconstrainedDataManager` for the host save.** Phase 11 mutations MUST go through regular `DataManager.save` so user-level row policies, lifecycle events, and entity listeners apply (per MUT-03). `UnconstrainedDataManager` is only for `MutationIntentRepository` (system-level dedup), `AuditWriter` (system-level audit), and the cleanup job — none of which write host entity state.
- **DO NOT call `MutationGuard.check` or attribute access checks AFTER `DataManager.save`.** The contract is fail-closed: every gate runs before any database write. A guard ordering bug here is a security regression.
- **DO NOT cache `idempotencyKey` lookups in memory (e.g. `ConcurrentHashMap`).** The dedup table IS the cache; in-memory caching breaks across instances and after restart. The hot-path latency is one indexed `(tool_name, idempotency_key, user_username)` lookup — cheap.
- **DO NOT reload the parent entity's `@Composition` children fetch plan when handling `add_related_record` / `remove_related_record`.** The children may be re-saved by Jmix with bumped `@Version` — same Pitfall #1 from `AuditWriter`. Load parent with a projection that omits the relationship collection, manipulate via a fresh `DataContext`, save the parent.
- **DO NOT swallow `ToolVetoedException` to surface as `access_denied`.** Per CONTEXT.md, `ToolVetoedException` is reused as-is and produces a BLOCKED audit row with `denialReason=exception.getMessage()`. The error code surfaced to the LLM is `access_denied` (with the `expected` hint "do not retry"); the audit row preserves the original exception message for operator diagnostics.
- **DO NOT use afterCommit-only idempotency insertion.** It cannot stop two concurrent callers from both writing host data before the dedup row exists. Reserve first in `agentstore`, then finalize to COMMITTED/FAILED/COMMIT_UNKNOWN according to the host save outcome.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Type coercion of LLM-supplied `String`/`Number` to attribute Java type | Custom `instanceof` ladder per attribute type | `FilterLiteralValueConverter.convertValue(raw, metaProperty)` — already exists in `com.vn.agent.filter` | Handles UUID, enum (with valid-name expected hint), every Jmix `Datatype` (BigDecimal, OffsetDateTime, etc.), and number narrowing. Failure throws `ToolUserError` with structured `expected` hint — exactly the shape `MutationErrorTranslator` needs to produce `parameter_conversion_error`. |
| SHA-256 hashing for sensitive-field diff | `MessageDigest.getInstance` per call | `AuditFieldHasher.sha256Hex(value)` — Phase 9 plumbing | Already shipped, exception-handled, lowercase-hex normalized. |
| Determining sensitive-field set | Hardcoded constant in `MutationErrorTranslator` | `AiAgentAuditProperties.resolvedSensitiveFields()` — Phase 9 plumbing | `@ConfigurationProperties("jmix.ai-agent.audit")` host-tunable. Phase 11 is first consumer. |
| Audit row write surviving rollback | Manual `TransactionSynchronization` registration | `AuditWriter.writeToolCall` (REQUIRES_NEW) | Already correct — REQUIRES_NEW boundary survives caller rollback. TEST-12 directly exercises this. |
| Entity resolution + read-access check | New `getMetaClass` + `accessManager.canRead` per tool | `ToolEntityResolver.resolveReadableEntityOrThrow` (extracting from `BuiltInDataTools`) | Phase 10 R4 uniform-opacity contract baked in. Adding `resolveWritableEntityOrThrow` reuses the same opacity path for unknown entity, then layers `canModify` for `access_denied`. |
| ID parsing (string → UUID/Long/etc.) | `UUID.fromString` per tool | `ToolEntityResolver.parseEntityId(id, metaClass)` followed by a mutation-tool `requireUuidId(...)` check | Phase 11 tool contract accepts UUID id strings; keeping resolver typed avoids breaking read tools while mutation tools fail fast with `parameter_conversion_error` for non-UUID ids. |
| Tool guard SPI shape | New `interface` + new exception type | `MutationGuard` mirrors `ToolGuard`; reuse `ToolVetoedException` (per CONTEXT.md) | Pattern already proven; one less exception class for hosts to import. |
| Liquibase audit columns + UUID PK | Hand-typed SQL | `${uuid.type}` token + `defaultValueNumeric` for `VERSION` per `060-ai-exposure-rule.xml` template | Convention already established; copy the existing changeset shape. |
| Spring AI tool callback discovery | Manual `MethodToolCallback.builder()` per tool | `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` per `AgentToolCallbacks.fromBean` (in-repo) | Already proven; `@ConditionalOnProperty` gated bean is simply absent from the constructor list when disabled — see Q5. |
| Diff JSON serialization | Custom string concatenation | Jackson `ObjectMapper` (existing in `BaselineContextProvider`) | Reuse same `ObjectMapper` bean for consistent escape semantics. |

**Key insight:** This phase is almost entirely composition. Every primitive — type coercion, hashing, audit, transaction boundary, exposure check, tool callback discovery, Liquibase shape — already exists in the codebase. New code is gluing them in the right order with the right error mapping.

## Runtime State Inventory

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | New entity `AiMutationIntent` rows in agentstore (created Phase 11 onward); new outcome enum values `IDEMPOTENT_REPLAY` + `COMMIT_FAILED` written into existing `AI_AUDIT_EVENT.OUTCOME` String column | New rows: created by Phase 11 itself (greenfield). Outcome strings: backward-compatible — `AiToolCallOutcome.fromId` returns null for unknown ids; existing rows unaffected because they only contain pre-Phase-11 strings. |
| Live service config | None — phase ships pure code/config; no external service registrations. The only "live config" is the new `ai-agent.tools.mutation.*` property block, but that's host-controlled application.yml, NOT a database/UI-stored value. | None. |
| OS-registered state | None — no Windows Task Scheduler, launchd, systemd, or pm2 registrations involved. The new `@Scheduled` cleanup job is Spring-managed, not OS-managed. | None. |
| Secrets/env vars | None — no new secrets. Phase consumes existing OpenAI/OpenRouter API key already in use; no new env-var contract. | None. |
| Build artifacts | New entity `AiMutationIntent` will trigger Eclipselink JPA metamodel regeneration; the `metamodel/` Eclipselink-generated `.java` files will gain `AiMutationIntent_` static metamodel. Standard incremental rebuild handles this. | None — `gradle clean build` after first add is sufficient. |

## Common Pitfalls

### Pitfall 1: `EntityChangedEvent` does not fire from `DataManager` cross-store saves

**What goes wrong:** The dedup row write to `agentstore` and the host entity write to the main store are TWO transactions. `EntityChangedEvent` fires per save. If the planner wires a listener on `AiMutationIntent` that does anything stateful, it sees events even when the corresponding host save rolled back.

**Why it happens:** Jmix multi-store has separate transaction managers per store; cross-store saves are not 2-phase committed.

**How to avoid:** Don't put listeners on `AiMutationIntent`. The dedup row is plumbing, not domain — no business logic should react to it.

**Warning signs:** Any `@EventListener` for `EntityChangedEvent<AiMutationIntent>` showing up in the plan.

### Pitfall 2: `@Tool` description multi-line string + Markdown special chars in OpenAI request

**What goes wrong:** Spring AI passes the description verbatim as the `description` field of the tool's JSON schema. If the description contains characters that need JSON-escape inside the schema string, Jackson handles it, but very long descriptions (~150 lines, ~3000 chars) approach OpenAI's per-tool description limit (~1024 chars guidance, hard limit higher but unspecified).

**Why it happens:** Tool descriptions are sent on every chat turn that exposes the tool — token cost accumulates.

**How to avoid:** Keep mutation descriptions to ~50–100 lines (~1500–2500 chars per tool). The 5-section template per MEMORY `feedback_rich_tool_descriptions` naturally fits this budget. If total description tokens exceed the prompt budget (CONTEXT.md flags ~3k system-prompt budget), trim by tightening MANDATORY WORKFLOW + EXAMPLES sections, not error codes.

**Warning signs:** Single tool description over 150 lines; total of all 6 new tool descriptions over ~10k chars.

### Pitfall 3: Re-entry into `BuiltInMutationTools` from a `@PostPersist` host listener

**What goes wrong:** Host application has an `@EntityChangedEvent` listener that, on `Customer` create, fires another LLM call (via direct `ChatService.ask`). That call could itself emit a tool call, including back into `create_record`. Recursive mutation chain not bounded by iteration cap because the cap is per chat turn.

**Why it happens:** Chat-level iteration cap (Phase 9 `GuardedToolCallingManager`) protects within a turn; cross-turn recursion is unbounded.

**How to avoid:** Document in `MutationGuard` Javadoc that hosts MUST NOT call `ChatService.ask` from any entity listener for entities the LLM can mutate. This is a deployment-discipline rule, not a code-enforced one.

**Warning signs:** Tests that simulate a host `@EventListener<EntityChangedEvent<HostEntity>>` calling `chatService.ask`.

### Pitfall 4: `MetadataTools.getInstanceName(entity)` requires INSTANCE_NAME fetch plan

**What goes wrong:** Replay path loads the original entity by id and calls `getInstanceName`. If load uses `_base` plan and `@InstanceName` references unloaded attributes, instance name is null or partially rendered.

**Why it happens:** Jmix lazy loading off; instance name tries to format an unfetched String/relationship.

**How to avoid:** Always load with `FetchPlan.INSTANCE_NAME` (or build a plan that adds `INSTANCE_NAME`) for replay. Pattern in `BuiltInDataTools.getRecord`: `fetchPlanResolver.resolve("get_record", metaClass)` already includes instance-name attributes.

**Warning signs:** Replay returns `instanceName: null` in the result JSON; tests assert non-null.

### Pitfall 5: `OptimisticLockException` may surface as `javax.persistence.OptimisticLockException` OR `org.springframework.orm.ObjectOptimisticLockingFailureException` depending on translation

**What goes wrong:** Jmix uses Eclipselink under the hood. Eclipselink throws `javax.persistence.OptimisticLockException`. Spring's `JpaTransactionManager` translates to `org.springframework.orm.ObjectOptimisticLockingFailureException` IF `@Transactional` boundary is on the bean and translation is enabled (default). If the planner's translator catches only `javax.persistence.OptimisticLockException`, Spring-translated cases miss; if only Spring's, raw Eclipselink-thrown cases miss.

**Why it happens:** Spring wraps JPA exceptions when persistence-exception-translation is active.

**How to avoid:** Catch BOTH (or the common ancestor `org.springframework.dao.OptimisticLockingFailureException`, which is the parent of `ObjectOptimisticLockingFailureException` and is also wrapped around `javax.persistence.OptimisticLockException`). Confirmed via Context7 — Jmix's `Versioned` trait standard JPA `@Version`, behavior is the standard Spring/JPA contract; no Jmix-specific wrapper exists.

**Warning signs:** TEST-12 mock that throws `javax.persistence.OptimisticLockException` doesn't trigger `concurrent_modification` mapping in unit test but does in integration test (or vice-versa).

### Pitfall 6: `@Composition` re-save cascades on `add_related_record`

**What goes wrong:** `add_related_record` loads the parent entity, adds a child to its `@Composition`-annotated `@OneToMany` collection, calls `dataManager.save(parent)`. If the parent's collection was loaded with the existing children attached, Jmix re-saves ALL of them (bumping `@Version`). Concurrent edits by another user are clobbered (lost-update) because the children are re-saved with stale `@Version`.

**Why it happens:** `@Composition` semantics in Jmix: parent owns lifecycle of children; `cascade=ALL` + `orphanRemoval=true` is canonical (see in-repo `AiAuditEvent` pitfall #1 documentation).

**How to avoid:** Use a fresh `DataContext` per call (`dataContext = applicationContext.getBean(DataContext.class)` is wrong; use the standalone `DataContext` API or just save the parent + new child together via `dataManager.save(parent, newChild)` `EntitySet`-style without re-loading the existing children). Verified via Context7 jmix-context7 `data-manager.html` — `EntitySet save(parent, child)` returns both saved.

**Warning signs:** Tests fail with `OptimisticLockException` on existing children even when only one child was added.

## Code Examples

### Example 1: `AiMutationIntent` entity (mirror `AiExposureRule` shape)

```java
// Source: in-repo com.vn.agent.exposure.AiExposureRule (verified pattern) + CLAUDE.md mandate
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

    @NotNull @Column(name = "IDEMPOTENCY_KEY", nullable = false, length = 64)
    private String idempotencyKey;

    @NotNull @Column(name = "USER_USERNAME", nullable = false, length = 255)
    private String userUsername;

    @Column(name = "CONVERSATION_ID")
    private UUID conversationId;

    @Column(name = "RESULT_ENTITY_ID")
    private UUID resultEntityId;

    @Column(name = "RESULT_ENTITY_NAME", length = 255)
    private String resultEntityName;

    @NotNull @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    // getters/setters omitted
}
```

### Example 2: Liquibase 070-ai-mutation-intent.xml

```xml
<!-- Source: in-repo 060-ai-exposure-rule.xml shape -->
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
      <column name="TOOL_NAME" type="varchar(64)">
        <constraints nullable="false"/>
      </column>
      <column name="IDEMPOTENCY_KEY" type="varchar(64)">
        <constraints nullable="false"/>
      </column>
      <column name="USER_USERNAME" type="varchar(255)">
        <constraints nullable="false"/>
      </column>
      <column name="CONVERSATION_ID" type="${uuid.type}"/>
      <column name="RESULT_ENTITY_ID" type="${uuid.type}"/>
      <column name="RESULT_ENTITY_NAME" type="varchar(255)"/>
      <column name="CREATED_AT" type="datetime">
        <constraints nullable="false"/>
      </column>
      <column name="EXPIRES_AT" type="datetime">
        <constraints nullable="false"/>
      </column>
    </createTable>
  </changeSet>

  <changeSet id="2" author="ai-agent">
    <createIndex indexName="IDX_AI_MUT_INTENT_DEDUP"
                 tableName="AI_MUTATION_INTENT"
                 unique="true">
      <column name="TOOL_NAME"/>
      <column name="IDEMPOTENCY_KEY"/>
      <column name="USER_USERNAME"/>
    </createIndex>
    <createIndex indexName="IDX_AI_MUT_INTENT_EXPIRES_AT"
                 tableName="AI_MUTATION_INTENT">
      <column name="EXPIRES_AT"/>
    </createIndex>
  </changeSet>
</databaseChangeLog>
```

### Example 3: BuiltInLinkTools (extends jmix-crm `ViewsDiscoveryTool` pattern)

```java
// Source: D:/DTH/jmix-crm/.../ViewsDiscoveryTool.java + CONTEXT.md D-05
@Component
public class BuiltInLinkTools {

    private final ServerProperties serverProperties;
    private final ViewRegistry viewRegistry;
    private final Metadata metadata;
    private final LlmExposurePolicy llmExposurePolicy;
    private final ToolResultFormatter toolResultFormatter;

    // ctor omitted

    @Tool(name = "generate_entity_list_link",
          description = """
                  MANDATORY WORKFLOW:
                  1. Call list_entities first to confirm the entity name is visible.
                  2. Pass the EXACT entity name (e.g., 'sample_Customer'), never a label.

                  INPUT CONTRACT:
                  - entityName: exact internal name from agent.entities or list_entities.

                  PARAMETER FORMATS:
                  - entityName: String, Jmix metaClass name (host-prefixed).

                  ERROR HANDLING:
                  - unknown_entity: entity is hidden or has no list view registered.
                    Recovery: tell the user the entity isn't browsable, do NOT retry.

                  STRICTNESS + EXAMPLES:
                  ✓ CORRECT: generate_entity_list_link("sample_Customer")
                  ✗ INCORRECT: generate_entity_list_link("Customer")  (display label, not internal)
                  ✗ INCORRECT: generate_entity_list_link("customers")  (route, not entity)
                  """)
    public String generateEntityListLink(
            @ToolParam(description = "Exact entity name from list_entities; never a label or route")
            String entityName) {
        try {
            MetaClass mc = resolveReadableEntityOrThrow(entityName);
            String route = viewRegistry.findViewInfo(viewRegistry.getListViewId(mc))
                    .flatMap(this::extractRoute)
                    .orElse(null);
            if (route == null) {
                throw new ToolUserError("unknown_entity",
                        "no list view registered for " + entityName,
                        UnknownEntityHints.AS_LIST);
            }
            return toolResultFormatter.toJson(
                    Map.of("url", contextPath() + "/" + route));
        } catch (ToolUserError err) {
            return toolResultFormatter.error(err);
        }
    }

    // generate_entity_detail_link — symmetric, with substringBeforeLast trim and entityId append
}
```

### Example 4: MutationErrorTranslator skeleton

```java
@Component
public class MutationErrorTranslator {

    public ToolUserError translate(Throwable thrown, String toolName, MetaClass metaClass) {
        // OptimisticLockException — both flavors
        if (thrown instanceof org.springframework.dao.OptimisticLockingFailureException
                || thrown instanceof javax.persistence.OptimisticLockException) {
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
        if (thrown instanceof javax.validation.ConstraintViolationException
                || thrown instanceof org.springframework.dao.DataIntegrityViolationException) {
            return new ToolUserError("validation_failed",
                    "value validation failed for " + metaClass.getName(),
                    List.of("call describe_entity to inspect mandatory and constraint fields; if you change values, retry with a fresh idempotencyKey"));
        }
        if (thrown instanceof ToolUserError tue) {
            // already typed, but mutation boundary still rebuilds safe prose
            // from stable code + entity name; do not pass through raw messages.
            return sanitizeStableToolUserError(tue.toDto().error(), metaClass);
        }
        // Default: do NOT echo exception message into LLM result string (P-22)
        return new ToolUserError("validation_failed",
                "operation failed",
                List.of("call describe_entity to inspect required fields; if you change values, retry with a fresh idempotencyKey"));
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `Function<I,O>` Spring AI tool registration | `@Tool` + `@ToolParam` with `MethodToolCallbackProvider` | Spring AI 1.0 GA | Already adopted in this codebase. No change needed. |
| Manual `@PostPersist` on Jmix entities | `@EventListener EntityChangedEvent` (Jmix 2.x idiom) | Jmix 2.0 | Already adopted (see `AiExposureRuleEntityListener`). |
| Single-store `@Transactional` | `@Transactional("<store>TransactionManager")` for non-default stores | Jmix multi-store rollout | Phase 11 mutation tools target the default store (host entities); audit/idempotency target `agentstore`. Two transaction managers in play, but always REQUIRES_NEW boundaries — no cross-store transaction surface needed. |
| `runWithSystem` for system writes | `UnconstrainedDataManager` per MEMORY `feedback_jmix_unconstrained_for_system_writes` | jmix-security-data adoption | Already adopted (`AuditWriter` uses `UnconstrainedDataManager`). `MutationIntentRepository` mirrors. |

**Deprecated/outdated:**
- `runWithSystem` for system-internal writes — still works but jmix-security-data policy-gates the system user. `UnconstrainedDataManager` is the cleaner pattern.
- `JsonSchema` typed McpSchema — Spring AI migrated to `Map<String,Object>` for `inputSchema` to support arbitrary JSON Schema dialects [CITED: Spring AI upgrade-notes via Context7]. Affects MCP integration only; Phase 11 doesn't touch MCP.

## Research Question Answers

### Q1: Spring AI 1.1.4 `@Tool` `Map<String,Object>` coercion

**Answer:** Verified working in production. The jmix-crm `RunReportTool.runReport(... Map<String,Object> parameters ...)` ships with Spring AI 1.1.x against OpenAI/Azure-class models and processes LLM-generated JSON object inputs reliably [VERIFIED: in-repo `D:/DTH/jmix-crm/.../RunReportTool.java` line 90].

Spring AI's `JsonSchemaGenerator.generateForMethodInput` produces a JSON Schema object with `type: "object"` and `additionalProperties: true` when it encounters `Map<String,Object>` (no nested property type info). The LLM sees this as "free-form object" and emits arbitrary key/value pairs based on the description text — which is why D-06's 5-section description is critical (the schema doesn't constrain; the description does). [CITED: Spring AI tools.adoc via Context7 /spring-projects/spring-ai]

**Mitigation if a model misbehaves:** Per CONTEXT.md "Spring AI primitives to verify in research", Option D fallback is `String payloadJson` server-side parse. NOT needed based on jmix-crm production evidence — recommend planner stays with Map<String,Object> per D-01.

**Confidence:** HIGH (production evidence in jmix-crm + Context7 spec).

### Q2: Jmix `EntityAttributeContext.canModify`

**Answer:** [VERIFIED: in-repo `BaselineContextProvider.modifiableAttributesOf` lines 274–287 + `CurrentUserSchemaAccess` line 71 + Context7 /jmix-framework/jmix-context7 authorization.html]

API surface:
```java
EntityAttributeContext attr = new EntityAttributeContext(metaClass, attributePath);
accessManager.applyRegisteredConstraints(attr);
boolean canRead = attr.canView();
boolean canWrite = attr.canModify();
```

- **Per attribute, fresh instance:** A new `EntityAttributeContext` is required per check; it's stateful (constraints applied via `applyRegisteredConstraints` mutate it).
- **Path-aware:** Constructor accepts dot-separated property paths (e.g. `"customer.region.code"`).
- **Same answer for create vs update?** Per Jmix docs ([CITED: resource-roles.html via Context7]), `EntityAttributePolicy` is action-typed (`VIEW` vs `MODIFY`); `canModify` returns true if the user has MODIFY on the attribute REGARDLESS of whether the operation is CREATE or UPDATE. The CRUD operation is checked separately via `CrudEntityContext.isCreatePermitted` vs `isUpdatePermitted`. So the attribute check is the same; only the entity-level check differs by op. **Plan implication:** one helper `enforceAttributeWriteAccess(metaClass, attributeNames)` for both `create_record` and `update_record`.
- **Modify implies View:** Jmix docs explicitly state "the modify action automatically includes view permissions" [CITED: resource-roles.html] — so no need to check both for write paths.

**Confidence:** HIGH.

### Q3: Jmix `OptimisticLockException` propagation

**Answer:** Jmix uses Eclipselink under standard JPA `@Version` semantics — see "Versioned Trait" docs [CITED: data-model/entities.html via Context7]: "It is crucial never to change the value of the @Version attribute in the application code, as this will prevent the instance from being updated in the database."

The exception path through `@Transactional` has TWO surface forms:
1. Raw `javax.persistence.OptimisticLockException` (Jakarta `jakarta.persistence.OptimisticLockException` in Jmix 2.8 / Spring Boot 3) — what Eclipselink throws.
2. `org.springframework.orm.ObjectOptimisticLockingFailureException` — when Spring's `JpaTransactionManager` translates JPA exceptions (default behavior under `@Transactional`).

Both extend `org.springframework.dao.OptimisticLockingFailureException` (Spring's translation parent) when translation is active. **Recommendation:** `MutationErrorTranslator` catches `OptimisticLockingFailureException` (Spring's parent) AND `jakarta.persistence.OptimisticLockException` (raw) for safety. Jmix does NOT wrap into a Jmix-specific exception type; it relies on the standard JPA contract.

**Confidence:** HIGH (Context7 confirms standard JPA contract; no Jmix-specific wrapper).

### Q4: Jmix `@Composition` cascade vs `add_related_record`

**Answer:** [VERIFIED: Context7 /jmix-framework/jmix-context7 — see "Order Entity with Composition and Relationships" snippet in ui-samples/generic-filter-jpql-condition.md]

For a parent with `@Composition @OneToMany(mappedBy="parent")` collection, the canonical pattern to add a child is:

```java
Order parent = dataManager.load(Order.class).id(parentId).one();   // do NOT fetch items
OrderItem child = dataManager.create(OrderItem.class);
child.setOrder(parent);
// set other child attrs
EntitySet saved = dataManager.save(parent, child);
```

Or, when doing it through a `DataContext`:
```java
DataContext dc = ...;
Order parent = dc.merge(loadedParent);
OrderItem child = dc.create(OrderItem.class);
child.setOrder(parent);
parent.getItems().add(child);
dc.save();
```

For `add_related_record` we should **NOT** load `parent` with the children fetch plan — that re-saves all existing children with bumped `@Version` (Pitfall #6). The cleanest path: load parent with `_base` (no children), create child via `dataManager.create`, set the parent reference on the child via `EntityValues.setValue(child, mappedByAttr, parent)`, save both via `dataManager.save(parent, child)`.

For `remove_related_record`: load the CHILD by id, load its parent reference, verify child's parent matches the parent id from the LLM call, then `dataManager.remove(child)`. The `@Composition` `orphanRemoval=true` semantics mean removing the child via `DataManager.remove` works exactly as removing from the parent collection; no need to re-save parent.

**Edge case:** Bidirectional `@OneToMany` without `@Composition` — pure association. `add_related_record` is then ambiguous: does it mean "create a brand new related entity" or "set an existing related entity's FK to the parent"? CONTEXT.md D-01 signature is `add_related_record(parent, relationship, relatedId)` — passing `relatedId` of an EXISTING child, so semantics are unambiguous: load both, set the FK side, save the FK-owning side. For composition, the same shape works: `add_related_record` requires the related entity to exist already; the LLM uses `create_record` for the child first, then `add_related_record` to attach (only meaningful for non-composition associations — composition children are attached at `setOrder(parent)` time).

**Confidence:** HIGH for cardinality + canonical save pattern; MEDIUM for the precise semantics of `add_related_record` on `@Composition` (planner should write a clear contract in the @Tool description: "use create_record with parent id in attributes Map for composition children; use add_related_record for non-composition associations only").

### Q5: `MethodToolCallbackProvider.builder().toolObjects(...)` discovery for `@ConditionalOnProperty`-gated beans

**Answer:** [VERIFIED: in-repo `AgentToolCallbacks.fromBean` line 86–91 + Context7 /spring-projects/spring-ai]

`MethodToolCallbackProvider` is a builder over a list of bean instances, NOT a classpath scanner. It reflects on `@Tool` methods of each bean instance passed to `.toolObjects(bean)`. So the discovery boundary is "is the bean in the Spring context".

`@ConditionalOnProperty(... havingValue="true", matchIfMissing=false)` (the canonical default-OFF shape) means: if the property is missing or "false", the bean is NOT registered. `AgentToolCallbacks` should accept `Optional<BuiltInMutationTools>` (or `ObjectProvider<BuiltInMutationTools>`) in its constructor — Spring injects empty when the conditional bean is absent. Then `forCurrentUser()`:

```java
all.addAll(Arrays.asList(fromBean(builtIns)));            // 6 read tools (always)
all.addAll(Arrays.asList(fromBean(builtInLinkTools)));    // 2 link tools (always)
mutationTools.ifPresent(b -> all.addAll(Arrays.asList(fromBean(b))));  // 4 mutation tools (cond)
```

**TEST-13 mechanic:** With `mutation.enabled=false` (default), `mutationTools.isPresent()` is false → 8 callbacks. With `mutation.enabled=true` → 12 callbacks. Bean count drives callback count; no other discovery layer involved.

**Pitfall:** Do NOT use `@Autowired(required=false) BuiltInMutationTools` field injection — proxy/eager-init quirks make this brittle. Use `ObjectProvider<BuiltInMutationTools>` constructor injection per Spring 5+ idiom.

**Confidence:** HIGH (in-repo pattern + Context7 confirms builder mechanics).

### Q6: `@Scheduled` + Jmix multi-store

**Answer:** [VERIFIED: Context7 /jmix-framework/jmix-context7 transactions.html]

`@Scheduled` cleanup job for `AiMutationIntent` (in `agentstore`) needs explicit transaction manager:

```java
@Component
public class MutationIntentCleanupJob {
    private final MutationIntentRepository repository;

    @Scheduled(cron = "0 0 * * * *")  // hourly
    @Transactional("agentstoreTransactionManager")
    public void deleteExpiredIntents() {
        OffsetDateTime now = OffsetDateTime.now();
        int removed = repository.deleteExpired(now);       // COMMITTED/FAILED only
        int stale = repository.countExpiredInFlight(now);  // PENDING/COMMIT_UNKNOWN only
        // log removed/stale counts; never auto-delete in-flight rows
    }
}
```

`MutationIntentRepository` uses `UnconstrainedDataManager`; the scheduled job keeps an explicit `@Transactional("agentstoreTransactionManager")` boundary for the agentstore table. **MEMORY `feedback_jmix_unconstrained_for_system_writes`** mandates `UnconstrainedDataManager` for system-internal writes regardless. Per CLAUDE.md, `@EnableScheduling` must be active — verify via `grep -rn "@EnableScheduling" ai-agent/`.

**Verified above:** `@EnableAsync` is on `AIConfiguration` but `@EnableScheduling` is NOT — planner adds `@EnableScheduling` to `AIConfiguration` in Plan 11-02 to keep configuration changes centralized with the existing async/configuration-properties annotations.

**Confidence:** HIGH.

### Q7: `AiMutationIntent` Liquibase column types

**Answer:** [VERIFIED: in-repo `060-ai-exposure-rule.xml`]

Canonical project conventions:
- UUID PK: `<column name="ID" type="${uuid.type}"><constraints primaryKey="true" nullable="false"/></column>`
- Version: `<column name="VERSION" type="int" defaultValueNumeric="1"><constraints nullable="false"/></column>`
- String: `varchar(N)` with explicit length
- Timestamps: `datetime` (NOT `timestamp` — Jmix project convention seen across all 7 existing changesets)
- Boolean: `boolean` with `defaultValueBoolean="true|false"`
- UUID FK: `<column name="..._ID" type="${uuid.type}"/>`
- Indexes: separate `<changeSet>` after the table; `unique="true"` for unique constraints (NOT `<addUniqueConstraint>`); name format `IDX_<TABLE>_<COLS>` matching `@Index(name=...)` on the entity

Concrete shape for Phase 11 already drafted in Code Examples Example 2 above.

**Confidence:** HIGH (direct file evidence).

### Q8: Pre/post-image diff serialization

**Answer:** [VERIFIED: in-repo Jackson usage in `BaselineContextProvider.serializePermissions` line 262 + `AuditFieldHasher.sha256Hex`]

- **Serializer:** Jackson `ObjectMapper` — already used elsewhere in `com.vn.agent.orchestration` for system-prompt JSON. Inject the same bean (Spring auto-configures `ObjectMapper`).
- **Diff JSON shape:** `[{"attribute": "name", "from": "Old", "to": "New"}, ...]` — list of `AttributeDiff` records (Code Examples Example 3 above).
- **Sensitive-field set:** `AiAgentAuditProperties.resolvedSensitiveFields()` — flat `Set<String>` of attribute simple names (per CONTEXT.md "global flat Set, attribute simple-name match, not entity-qualified"). Match by `metaProperty.getName()` (simple name).
- **Hash mechanic:** Replace BOTH `from` and `to` with `AuditFieldHasher.sha256Hex(stringRepresentation)` — preserves the fact that the value changed (different hashes) without revealing values. The marker `[REDACTED]` is NOT used — distinguishability of from/to changes is auditor-valuable.
- **null handling:** `null.toString()` is null; hash null → null. Write `"from": null`, `"to": "<hash>"` for new-value-set-on-create.
- **Create result shape:** `[{"attribute": "name", "to": "Alice"}, ...]` — `from` omitted (entity didn't exist). Per CONTEXT.md.

**Confidence:** HIGH.

### Q9: `generate_entity_detail_link` route resolution

**Answer:** [VERIFIED: jmix-crm `ViewsDiscoveryTool.java` lines 79–129 + Context7 /jmix-framework/jmix-context7]

```java
ViewRegistry viewRegistry;
ServerProperties serverProperties;

// list view route
String listViewId = viewRegistry.getListViewId(metaClass);  // returns null if no list view
viewRegistry.findViewInfo(listViewId)
        .map(viewInfo -> AnnotationUtils.findAnnotation(viewInfo.getControllerClass(), Route.class))
        .map(Route::value);  // e.g. "customers"

// detail view route
String detailViewId = viewRegistry.getDetailViewId(metaClass);
String detailRoute = viewRegistry.findViewInfo(detailViewId)
        .map(this::extractRoute)
        .orElse(null);  // e.g. "customers/:id"
String trimmed = StringUtils.substringBeforeLast(detailRoute, "/");  // "customers"
String url = contextPath + "/" + trimmed + "/" + entityId;
```

- **`getListViewId` / `getDetailViewId`:** Returns the `Route` value of the entity's primary list/detail view. **Returns null if no view registered** for that entity — handle by returning `unknown_entity` ToolUserError (per D-05 contract).
- **Context path source:** `serverProperties.getServlet().getContextPath()` — confirmed via jmix-crm reference + Spring Boot autoconfig contract. Returns `""` (empty) when app is at root context (most cases).
- **Detail-view route trim:** Detail routes typically end in `/:id`; the `substringBeforeLast(.., "/")` strips it. The pattern in jmix-crm strips unconditionally — works for both `customers/:id` and `customers/:id/items` (rare).
- **No id existence check:** Per D-05, link tool does NOT verify `entityId` exists — that's `get_record`'s job.

**Confidence:** HIGH.

### Q10: 5-section `@Tool` description rendering

**Answer:** [VERIFIED: Context7 /spring-projects/spring-ai tools.adoc + jmix-crm `JpqlExecutorTool.java` 230 lines]

- Spring AI accepts the description string verbatim. It ends up as the `description` field in the tool's JSON schema, sent to the LLM on every chat turn.
- Per Context7, "Spring AI will automatically generate the JSON schema for the input parameters of the method." The `@Tool(description=...)` text is NOT processed/truncated by Spring AI — it's passed through.
- jmix-crm production evidence: `JpqlExecutorTool` ships with a 230-line description; `RunReportTool` ships with a 90-line description. Both work in production against OpenAI gpt-4o-class models.
- **Token budget:** Spring AI does NOT truncate, but the LLM provider may. OpenAI's tool description has no documented hard limit but billed against input-token quota. ~150 lines is roughly 1500 tokens; 6 tools × 1500 tokens = 9000 tokens of always-on tool descriptions — significant but acceptable for gpt-4-class context windows (128k+).
- **Recommendation:** Stay within ~80–120 lines per mutation tool description (4 tools × 100 lines ≈ 4000 tokens budget). Link tools can be tighter (~30–40 lines each).

**Confidence:** HIGH.

## Open Questions (RESOLVED)

1. **`AiInternalEntityNames` exact metaClass name.**
   - What we know: existing names use prefix patterns like `ai_`, `aiExposure_`. Likely `aiMutation_AiMutationIntent`.
   - What's unclear: whether the planner will adopt that prefix or use `ai_AiMutationIntent` to keep new entities under one prefix.
   - RESOLVED: Use `aiMutation_AiMutationIntent` to match the package layout (`com.vn.agent.tools.mutation`) and mirror the `aiExposure_` precedent. Add to `AiInternalEntityNames.NAMES` set.

2. **Role for `AiMutationIntent` admin visibility.**
   - What we know: `AiAgentAdminRole` extension is suggested. CONTEXT.md says "if planner ships an admin list view".
   - What's unclear: Is a list view in scope? CONTEXT.md "Deferred Ideas" says NO list view in v1.1.
   - RESOLVED: Add `@EntityPolicy(entityClass=AiMutationIntent.class, actions=ALL)` to `AiAgentAdminRole` so admins CAN query the table programmatically (or via Jmix Studio at runtime). Skip `@MenuPolicy/@ViewPolicy` entries since no list view ships.

3. **`AiAgentMutationRole` initial population.**
   - What we know: CONTEXT.md says "empty default role". SEC-07 says "host composes with their own roles".
   - What's unclear: Should the role have ANY policies? An empty `@ResourceRole` interface compiles but does nothing.
   - RESOLVED: Empty marker interface with just `@ResourceRole(name="AI Agent Mutation", code="ai-agent-mutation")` and a Javadoc explaining the host adds `@EntityPolicy`s. Do not grant `AiMutationIntent` READ by default; replay uses `UnconstrainedDataManager`, and a blanket READ grant exposes idempotency keys/usernames/conversation IDs/result IDs.

4. **Dedup row write before vs after host save.**
   - What we know: Cross-store transactions are not atomic; CONTEXT.md says "REQUIRES_NEW boundary keeps audit durable".
   - What's unclear: Should `MutationIntentRepository.create` be called inside the host `@Transactional` (rolls back together) or via `TransactionSynchronization.afterCommit`?
   - RESOLVED: Reserve a PENDING row before host save in `agentstore` via `UnconstrainedDataManager`; the unique index is the distributed idempotency lock. After host save returns, mark COMMITTED. If host save returned but finalization fails, mark COMMIT_UNKNOWN if possible or leave PENDING; never mark FAILED/reclaimable because retrying could duplicate a committed host write.

## Project Constraints (from CLAUDE.md)

| Directive | Phase 11 Application |
|-----------|----------------------|
| Use Skill tool for jmix-* skills before implementing | Planner invokes `jmix-entities`, `jmix-services`, `jmix-security-roles`, `jmix-i18n`, `jmix-liquibase`, `jmix-testing` |
| Java 21 / Jmix 2.8 / Spring Boot 3 / Vaadin Flow / Gradle | All present; no version bumps |
| Entity rules: UUID + `@Version` + `@InstanceName`, no Lombok | `AiMutationIntent` follows |
| Messages in BOTH `messages_en.properties` AND `messages_vi.properties` | NO untagged `messages.properties` exists; planner edits both locale files |
| Use `DataManager` (NOT `EntityManager`) | Mutation path uses `DataManager`; idempotency repo uses `UnconstrainedDataManager` |
| `@Transactional` when needed | Host save method is `@Transactional` (default mgr); cleanup job is `@Transactional("agentstoreTransactionManager")` |
| Liquibase changelog included in main `changelog.xml` | New `070-ai-mutation-intent.xml` listed in `agentstore-changelog.xml` |
| `Metadata.create()` or `DataManager.create()` for entity instantiation, NOT constructor | Mutation tool body uses `dataManager.create(metaClass.getJavaClass())` (or `metadata.create`) |
| Constructor injection (services); `@Autowired` for Jmix beans in views | All new components use constructor injection |
| Forbidden: Lombok on entities, EntityManager, hardcoded UI text, edits in `frontend/generated/` | None violated by Phase 11 |
| Validation: file problems via `jetbrains` MCP, write tests, `./gradlew test`, UI verification via `playwright` MCP if applicable | Standard workflow; Phase 11 has no UI surface beyond admin-list-view (deferred) |

## Sources

### Primary (HIGH confidence)
- **Context7 `/spring-projects/spring-ai`** — topics: `@Tool` annotation, `@ToolParam`, `MethodToolCallbackProvider`, JSON schema generation, `ToolExecutionException`, `ToolExecutionExceptionProcessor`, `ConditionalOnProperty` + tool discovery, `Map<String,Object>` parameter shape.
- **Context7 `/jmix-framework/jmix-context7`** — topics: `EntityAttributeContext.canModify`, `CrudEntityContext.isCreatePermitted/isUpdatePermitted`, `AccessManager.applyRegisteredConstraints`, `@Version` Versioned trait, `@Composition` cascade, `DataManager.save/saveAll`, multi-store `@Transactional("...TransactionManager")`, `ViewRegistry.getListViewId/getDetailViewId/findViewInfo`, `UnconstrainedDataManager`.
- **In-repo source** (verified by reading):
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` (helpers to extract)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` (REQUIRES_NEW pattern)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java` (Phase 9 plumbing)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java` (Phase 9 plumbing)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` (`canModify`)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java` (extension site)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java` (entity shape pattern)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java`, `ToolVetoedException.java`, `ToolFetchPlanCustomizer.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java` (extension site)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` (runId/conversationId carrier)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` (forCurrentUser pattern)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` (per-attribute canModify pattern)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterLiteralValueConverter.java` (type coercion)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` (configuration site)
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml` (Liquibase template)
- **jmix-crm reference exemplars** (pattern-only, not a dependency):
  - `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/JpqlExecutorTool.java` — 5-section description exemplar
  - `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/RunReportTool.java` — `Map<String,Object> parameters` proven in production + 5-section structure
  - `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/ViewsDiscoveryTool.java` — `BuiltInLinkTools` pattern source

### Secondary (MEDIUM confidence)
- Project memory (auto-loaded): `feedback_jmix_unconstrained_for_system_writes`, `feedback_jmix_loadvalue_store`, `feedback_rich_tool_descriptions`, `feedback_reuse_jmix_builtins`.

### Tertiary (LOW confidence)
- None — every claim in this research has either Context7 or in-repo evidence.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | OpenAI / Azure OpenAI gpt-4-class models reliably emit valid JSON object payloads against a free-form `Map<String,Object>` schema | Q1 | If wrong: planner falls back to D-01 Option D (`String payloadJson` server-side parse). Cost: one extra parsing layer + slightly worse error messages. Mitigation: jmix-crm production evidence makes this LOW risk. |
| A2 | Jmix 2.8 + Spring Boot 3.x consistently translates `jakarta.persistence.OptimisticLockException` into `org.springframework.orm.ObjectOptimisticLockingFailureException` under `@Transactional` | Q3 | If wrong: TEST-12 may pass against one form but not the other. Mitigation: catch BOTH in `MutationErrorTranslator` (recommended in answer). |
| A3 | `@EnableScheduling` is NOT currently in this codebase | Q6 | Verified via Bash grep (no `@EnableScheduling` found). If wrong: planner skips re-adding it. Cost: zero. |
| A4 | Spring AI 1.1.4 does NOT truncate `@Tool` description strings, even at ~150 lines | Q10 | If wrong: descriptions get cut at some boundary, breaking 5-section template. Mitigation: jmix-crm `JpqlExecutorTool` ships 230-line description in production — strong production evidence. |
| A5 | The `aiMutation_AiMutationIntent` metaClass name aligns with project conventions | Open Question 1 | If wrong: planner picks `ai_AiMutationIntent` instead. Cost: rename in 3 places (entity annotation, Liquibase changeset comment, `AiInternalEntityNames`). Trivial. |

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already on classpath; versions verified.
- Architecture (gating chain, transaction boundaries): HIGH — every step has in-repo or Context7 evidence.
- Pitfalls: HIGH — Pitfalls 1, 4, 6 have documented in-repo precedent (Phase 7.2 children-fetch-plan, `BuiltInDataTools` instance-name fetch); Pitfalls 2, 3, 5 derived from Spring AI / Spring framework docs.
- Idempotency design: HIGH — Open Question 4 was superseded by review feedback; the safe resolution is pre-host-save reservation plus non-reclaimable COMMIT_UNKNOWN on post-host-save finalization failure.
- Spring AI `Map<String,Object>` reliability: HIGH — production evidence in jmix-crm.

**Research date:** 2026-04-28
**Valid until:** ~2026-05-28 (30 days; Spring AI 1.1.x and Jmix 2.8 are stable). Re-verify if Spring AI 1.2 or Jmix 2.9 lands before phase ships.

## RESEARCH COMPLETE

**TL;DR for the planner:** Phase 11 is composition-heavy: every primitive is already in the codebase (Phase 9 + 10 plumbing). New code is ~1200 LOC across ~10 files plus one Liquibase changeset. Hard chain of plans: (1) entity + enum + Liquibase first, (2) `ToolEntityResolver` extraction + `MutationGuard` SPI + default no-op, (3) `MutationErrorTranslator` + `BuiltInLinkTools`, (4) `BuiltInMutationTools` + properties + `AgentToolCallbacks` wiring with `ObjectProvider<BuiltInMutationTools>` for the conditional bean, (5) cleanup job (`@Scheduled` + `@Transactional("agentstoreTransactionManager")` + add `@EnableScheduling`) + admin role + system-prompt rules + locales (both `messages_en.properties` AND `messages_vi.properties`), (6) tests TEST-10..13. Catch BOTH `jakarta.persistence.OptimisticLockException` and `org.springframework.orm.ObjectOptimisticLockingFailureException` in the translator. Dedup row writes reserve before host mutation, then finalize to COMMITTED/FAILED/COMMIT_UNKNOWN. Use a fresh `EntityAttributeContext` per LLM-supplied attribute key. `BuiltInLinkTools` returns a single-property `{url}` object and URL-encodes UUID path segments. Map<String,Object> tool param shape is production-proven via jmix-crm; no fallback needed.
