# Phase 11: Mutation-Capable Built-In Tools — Context

**Gathered:** 2026-04-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Hosts can opt in to LLM-driven create/update/related-write operations against
host entities. All mutations go through Jmix `DataManager` and are gated
fail-closed by a layered chain: `LlmExposurePolicy.canModify` (Phase 10
boundary) → `AccessManager` `CrudEntityContext` + per-attribute
`EntityAttributeContext.canModify` → optional `MutationGuard` SPI →
`@Transactional` `DataManager.save`. Calls are idempotent via mandatory
`idempotencyKey`, audited end-to-end through the existing `AuditWriter.writeToolCall`
REQUIRES_NEW boundary, and never leak user-supplied PII through error strings.

This phase ALSO ships an always-on `BuiltInLinkTools` (read-only deep-link
generator) so the LLM can render verify-links to created/updated records and
to any entity surfaced by `find_records` / `get_record`.

**In scope:**
- `BuiltInMutationTools` separate `@Component` (NOT methods on
  `BuiltInDataTools` — preserves the v1.0 ASM read-only test). Conditional via
  `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled",
  havingValue="true")` — **default OFF**.
- 4 `@Tool` methods: `create_record`, `update_record`, `add_related_record`,
  `remove_related_record`. NO `delete_record` (deferred to v1.2 — destructive
  ops need separate UX with confirmation/undo).
- `MutationGuard` SPI with minimal `MutationIntent` shape (toolName, metaClass,
  entityId, attributes map). Default no-op bean. Veto via `ToolVetoedException`
  (existing exception, reused).
- `AiAgentMutationProperties` (`@ConfigurationProperties("ai-agent.tools.mutation")`):
  `enabled` (default false), `allowDelete` (default false; reserved for v1.2 —
  no method ships even when true), `confirmationRequired` (default true; UX
  hint, not enforcement), `idempotencyTtl` (default 24h).
- `AiMutationIntent` Jmix entity in `agentstore` for idempotency dedup.
  Fields: `id`, `toolName`, `idempotencyKey`, `userUsername`, `conversationId`,
  `resultEntityId`, `resultEntityName`, `createdAt`, `expiresAt`. Unique
  constraint on `(tool_name, idempotency_key, user_username)`. TTL-based
  cleanup (hourly job) honors `idempotencyTtl`.
- `MutationErrorTranslator` translating JPA / `AccessDeniedException` /
  `OptimisticLockException` / type-conversion failures into 6 stable error
  codes (D-04). Never echoes user-supplied PII or raw exception messages into
  the LLM result string.
- `ToolEntityResolver` shared `@Component` consumed by both `BuiltInDataTools`
  and `BuiltInMutationTools` — extracted from existing
  `resolveReadableEntityOrThrow` / `parseEntityId` helpers, plus new
  `resolveWritableEntityOrThrow` (delegates to `LlmExposurePolicy.canModify`,
  preserves `unknown_entity` opacity).
- Audit reuses `AuditWriter.writeToolCall` with new `eventName` strings
  (`create_record`, `update_record`, `add_related_record`, `remove_related_record`).
  `AiToolCallOutcome` enum extended with `IDEMPOTENT_REPLAY`, `COMMIT_FAILED`.
  Pre/post-image diff written to `resultSummary` per AUD-07; PII fields hashed
  per existing `AiAgentAuditProperties` plumbing (Phase 9 D-18).
- System-prompt rule additions (via `AgentSystemPromptRules` Phase 9 extension
  point): rule about idempotencyKey freshness; rule "do not retry on
  access_denied"; rule "preview when `confirmationRequired=true`".
- Locale message keys for every denial / success / idempotency / error path
  in `messages.properties` AND `messages_vi.properties`.
- New `AiAgentMutationRole` resource role (empty default — host composes with
  their own roles to grant CRUD on entities the LLM may mutate).
- `BuiltInLinkTools` `@Component` (always-on, independent of `mutation.enabled`).
  2 `@Tool`: `generate_entity_list_link`, `generate_entity_detail_link`.
  `LlmExposurePolicy.canReadEntity` opacity gate (returns `unknown_entity` not
  `access_denied` per Phase 10 D-04 R4). Audited via `writeToolCall`.
- TEST-10: per-attribute denial blocks at gating step 2; `DataManager.save`
  never called; structured `access_denied` returned.
- TEST-11: idempotency replay — same key returns same result with
  `outcome=IDEMPOTENT_REPLAY`; only one row created.
- TEST-12: post-flush save throws → audit row written with `outcome=COMMIT_FAILED`
  (REQUIRES_NEW boundary intact).
- TEST-13: default-config boot test — zero mutation tool callbacks present in
  `AgentToolCallbacks.forCurrentUser`.

**Out of scope (explicit):**
- `delete_record` mutation tool — deferred to v1.2 (MUT-13). `allowDelete=true`
  remains a forward-signal property that does nothing in v1.1.
- `MutationGuard` lifecycle hooks (afterCommit, onRollback) — only `check(...)`
  ships in v1.1; richer guard surface defers until concrete consumer asks.
- Pre-image lazy `Supplier` on `MutationIntent` — minimum shape only; guards
  needing pre-image must reload via `DataManager` themselves.
- Bulk mutation tools (`update_records`, `delete_records` plural) — defer.
- Mutation preview/dry-run mode — `confirmationRequired=true` is a UX hint
  metadata only; no separate dry-run code path in v1.1. Phase 12 chat surfaces
  may render the preview UI; Phase 11 does not.
- Auto-title service (separate todo, Phase 12).
- JPQL/analytics tool (SEED-008, future milestone).
- Attribute-level exposure rules — Phase 10 deferred; not a Phase 11 concern.
- New audit kinds — `AuditKind.TOOL` reused for all mutation events.
- Web/REST exposure of mutation tools — chat-only in v1.1.

</domain>

<decisions>
## Implementation Decisions

### Tool argument shapes

- **D-01:** Mutation tools accept field values as `Map<String,Object>
  attributes` and reference relationships by foreign-key UUID strings inside
  the same map. Tool signatures:
  - `create_record(String entityName, Map<String,Object> attributes, String
    idempotencyKey)` → `{outcome, entityId, instanceName}`
  - `update_record(String entityName, String id, Map<String,Object> attributes,
    String idempotencyKey)` → `{outcome, entityId, instanceName, diffSummary}`
  - `add_related_record(String entityName, String id, String relationship,
    String relatedId, String idempotencyKey)` → `{outcome, parentId,
    relationship, relatedId}`
  - `remove_related_record(String entityName, String id, String relationship,
    String relatedId, String idempotencyKey)` → `{outcome, parentId,
    relationship, relatedId}`
  Rationale: matches Spring AI tool-param JSON inference; LLM uses ids it
  already saw via `find_records` / `get_record`; no instance_name lookup
  ambiguity; no per-entity DTO synthesis (preserves the `@Tool`/`@Component`
  static-discovery pattern that `BuiltInDataTools` uses).

### Idempotency contract

- **D-02:** `AiMutationIntent` unique constraint on `(tool_name,
  idempotency_key, user_username)` — same `idempotencyKey` from two users is
  two distinct intents (prevents cross-user information leak; avoids
  cross-user `idempotency_violation` confusion). Row stores `resultEntityId`
  and `resultEntityName` only — NOT the full result JSON. Replay returns
  `{outcome:IDEMPOTENT_REPLAY, entityId, instanceName}` re-resolved live
  under current locale + security (instance_name re-renders fresh).
  TTL default 24h via `idempotencyTtl` property; expired rows reaped by an
  hourly cleanup job.

### Guard SPI shape

- **D-03:** `MutationIntent` minimal shape: `toolName + metaClass + entityId +
  attributes (Map<String,Object>)`. `entityId` is null on `create_record`,
  populated on update / add_related / remove_related. Forward-compatible: extra
  fields can be added behind default methods later. Guards needing
  user/conversation context fetch from `CurrentAuthentication` / `RunContext`;
  guards needing pre-image reload via `DataManager` themselves (Phase 11 does
  not pre-load before `check(...)`).
  `MutationGuard` mirrors `ToolGuard` shape:
  ```java
  public interface MutationGuard {
      void check(MutationIntent intent) throws ToolVetoedException;
  }
  ```
  Default no-op bean registered in `SpiDefaultsAutoConfiguration` per existing
  SPI convention (mirrors `ToolFetchPlanCustomizer` from Phase 9).

### Error-code taxonomy (MUT-07)

- **D-04:** `MutationErrorTranslator` maps thrown exceptions / denial paths
  into 6 stable structured error codes, each carrying `ToolErrorDto.expected`
  hints per Phase 9 D-14:
  - `access_denied` — Jmix `AccessManager` denial OR `LlmExposurePolicy.canModify`
    denial OR per-attribute `EntityAttributeContext.canModify` denial. Hint:
    "do not retry; surface to user". Note: when entity is unknown OR
    `LlmExposurePolicy.canReadEntity` denies entity entirely, the resolution
    path returns `unknown_entity` (Phase 10 R4 uniform-opacity preserved).
    `access_denied` is reached only when the LLM passed a *visible* entity
    that lacks per-attribute or per-CRUD-op permission.
  - `validation_failed` — JPA constraint violation, `dataContext.validate`
    failure, mandatory-attribute missing on create. Hint: "fix the value(s)
    and retry with same idempotencyKey".
  - `idempotency_violation` — `(toolName, idempotencyKey, userUsername)` row
    exists but its semantics conflict (e.g. different `attributes` map for
    same key — currently we treat any collision as violation; replay only
    matches when call shape matches). Hint: "use a fresh idempotencyKey".
  - `concurrent_modification` — `OptimisticLockException` (Jmix `@Version`
    bump). Hint: "call get_record to fetch current state, then retry with a
    fresh idempotencyKey".
  - `parameter_conversion_error` — LLM passed `"abc"` for an Integer
    attribute, `"maybe"` for a Boolean, malformed UUID, etc. Translator
    inspects target attribute type via `MetaProperty` and maps the converter
    failure. Hint: "send <type> as <format>; describe_entity has the
    attributeType field".
  - `not_found` — `update_record` / `add_related_record` /
    `remove_related_record` with id that doesn't resolve. Hint: "call
    get_record to verify the id exists before retrying".

### Deep-link generator

- **D-05:** `BuiltInLinkTools` ships always-on (independent of
  `mutation.enabled`) in this phase. 2 `@Tool` methods:
  - `generate_entity_list_link(entityName)` →
    `"/<contextPath>/<listViewRoute>"` or `unknown_entity` if entity is hidden.
  - `generate_entity_detail_link(entityName, entityId)` →
    `"/<contextPath>/<detailViewRoute>/<entityId>"` or `unknown_entity` if
    entity is hidden. Does NOT verify the id exists (LLM uses get_record for
    that).
  Both gated through `LlmExposurePolicy.canReadEntity` (uniform-opacity per
  Phase 10 D-04 R4). Returns RAW URL string — chat UI / system prompt
  instructs the LLM to render Markdown links. Audited via
  `AuditWriter.writeToolCall` (eventName `generate_entity_list_link` /
  `generate_entity_detail_link`). Mutation tool result schema stays clean
  (`{entityId, instanceName}`); LLM calls the link tool separately when it
  wants to surface a verify-link.

### Tool description style (cross-cutting)

- **D-06:** All 4 mutation tools + 2 link tools use the 5-section description
  pattern per MEMORY `feedback_rich_tool_descriptions`:
  MANDATORY WORKFLOW / INPUT CONTRACT / PARAMETER FORMATS / ERROR HANDLING /
  STRICTNESS + ✓-CORRECT/✗-INCORRECT EXAMPLES. Per-tool budget: ~50-150 lines
  acceptable; mutation tools especially require this density (idempotency
  contract, per-attribute denial recovery, type-conversion rules).
  Existing `BuiltInDataTools` are NOT retrofitted in Phase 11 — opportunistic
  cleanup later if total tool-description tokens exceed ~3k system-prompt
  budget.

### Scope guards

- **D-07:** `delete_record` is NOT shipped, even when `allowDelete=true`.
  `allowDelete` is a forward-signal property documented as "v1.2-reserved".
  TEST-13 boot-test explicitly asserts no `delete_record` callback under any
  flag combination in v1.1.
- **D-08:** Audit row reuse — no new `AuditKind`. All 4 mutation events plus
  2 link events use existing `AuditKind.TOOL`. New strings live in `eventName`
  only. `AiToolCallOutcome` enum gains `IDEMPOTENT_REPLAY` + `COMMIT_FAILED`
  values (no new enum class, no Liquibase schema change — column type stays
  String-backed via `EnumClass<String>`).
- **D-09:** TEST-13 zero-callback assertion is at the
  `AgentToolCallbacks.forCurrentUser()` level — the actual callback chain
  the chat client consumes — not at `MethodToolCallbackProvider.builder()`.
  Default-config boot context must show 6 read-only callbacks
  (`BuiltInDataTools` 6 tools) + 2 link callbacks (`BuiltInLinkTools`),
  ZERO mutation callbacks. With `mutation.enabled=true` it shows 6 + 2 + 4 = 12.

### Claude's Discretion

- Package layout for new classes — `com.vn.agent.tools.mutation` for
  `BuiltInMutationTools` + `MutationErrorTranslator` + `AiAgentMutationProperties`;
  `com.vn.agent.tools.link` for `BuiltInLinkTools`; `com.vn.agent.spi` for
  `MutationGuard` + `MutationIntent` (alongside existing `ToolGuard`);
  `com.vn.agent.tools` for shared `ToolEntityResolver` extracted helper.
  Planner picks final layout; favor cohesion + mirroring `BuiltInDataTools`
  package position.
- `AiMutationIntent` entity package — `com.vn.agent.tools.mutation` (entity
  alongside its only consumer) vs `com.vn.agent.entity` (alongside other AI-*
  entities). Planner picks; the mutation-package option couples cleanly with
  `BuiltInMutationTools`.
- Liquibase changelog placement — `070-ai-mutation-intent.xml` under
  `liquibase/agentstore-changelog/`, included in parent
  `agentstore-changelog.xml` per Phase 7.2 conventions.
- `AiInternalEntityNames` extension — `AiMutationIntent` is an internal AI
  entity; add to the always-excluded set so admins cannot accidentally
  denylist or expose it via `AiExposureRule`. Mirrors Phase 10 D-11.
- Cleanup-job scheduling shape — Spring `@Scheduled` cron under
  `MutationIntentCleanupJob` `@Component` vs JmixApp scheduled-task entity.
  Planner picks; `@Scheduled` is simpler and matches the audit `RetentionPolicy`
  style if any exists.
- `MUT-10` system-prompt mutation rules wording — bullet list inserted into
  `AgentSystemPromptRules` only when `mutation.enabled=true`. Suggested text:
  "When you call a mutation tool, generate a fresh UUID idempotencyKey per
  logical operation. To retry a failed mutation, reuse the SAME
  idempotencyKey. On `access_denied` do NOT retry — surface to the user. On
  `parameter_conversion_error` re-read describe_entity attributeType and
  retry with corrected types. On `concurrent_modification` call get_record,
  then retry with a fresh idempotencyKey. On success, you may call
  generate_entity_detail_link to render a verify-link." Planner refines text;
  must NOT mention `prepare_form_draft` (Phase 14 forward reference).
- `AiAgentMutationRole` shape — empty marker `@ResourceRole` interface OR
  one concrete `@EntityPolicy(entityClass = AiMutationIntent.class, actions =
  ALL)` so the role can read its own dedup table for replay. Planner picks;
  REQUIREMENTS SEC-07 just says "host composes with their own roles".
- `AiAgentAdminRole` extension — add `AiMutationIntent` CRUD + view + menu
  policies (mirror `AiExposureRule` extension Phase 10) so admins can inspect
  the dedup table.
- `MutationGuard` `SpiDefaultsAutoConfiguration` `@ConditionalOnMissingBean`
  registration vs `@Component` no-op — match existing `ToolFetchPlanCustomizer`
  pattern (Phase 9 SPI-09).
- `BuiltInLinkTools` location of route resolution — `ViewRegistry` lookups
  cached vs per-call. Per-call is fine; `ViewRegistry` is in-memory.
- `AuditWriter.writeToolCall` `argumentsJson` payload format — JSON
  serialization of the `attributes` Map verbatim, with PII fields hashed via
  `AuditFieldHasher` (Phase 9 plumbing) when the attribute name appears in
  `AiAgentAuditProperties.sensitiveFields`. `resultSummary` for update_record
  carries `[{"attribute","from","to"}]` JSON array; for create_record carries
  `[{"attribute","to"}]`; for add_/remove_related carries
  `{"relationship","action":"ADD|REMOVE","relatedId"}`. From/to values for
  sensitive attributes are SHA-256 hashed.

### Folded Todos

- `2026-04-28-add-deep-link-generator-tool.md` — folded into Phase 11 as
  D-05. Original problem (after-create user verification UX) is addressed by
  shipping `BuiltInLinkTools` always-on alongside the mutation tools. Will be
  picked up by the planner as a plan slot in PLAN.md.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 11: Mutation-Capable Built-In Tools" — goal,
  success criteria #1..#5, dependencies (Phase 9 + Phase 10 hard chain),
  requirements list.
- `.planning/REQUIREMENTS.md` — `MUT-01..MUT-12`, `ENT-09`, `AUD-06`, `AUD-07`,
  `SEC-07`, `SPI-10`, `TEST-10..TEST-13`. Authoritative for scope.
- `.planning/PROJECT.md` §"Current Milestone v1.1.0" — value prop and explicit
  in/out-of-scope. Note constraint "Mutation tools remain opt-in future work;
  v1 ships read-only tools by default" superseded for v1.1 as opt-in via
  `@ConditionalOnProperty`.
- `.planning/STATE.md` — Phase 10 shipped 2026-04-28; Phase 11 is next.

### Prior phase context (load before planning)
- `.planning/phases/10-ai-specific-llm-exposure-policy/10-CONTEXT.md` —
  D-01/D-02 `LlmExposurePolicy.canModify(MetaClass)` already shipped without
  caller; Phase 11 is the first consumer (gating step 1). D-04 R4 uniform
  `unknown_entity` opacity for read paths — Phase 11 preserves it via
  `ToolEntityResolver.resolveWritableEntityOrThrow` + the access_denied vs
  unknown_entity disambiguation in D-04.
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-CONTEXT.md` —
  D-14 `unknown_entity` retry contract (`ToolErrorDto.expected` hint shape).
  D-15 stateless-component pattern for SPIs. D-18 `AiAgentAuditProperties`
  pre-staged for AUD-07 — Phase 11 is the first consumer.
- `.planning/milestones/v1.0.0-phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` —
  D-08 "access denied = entity does not exist" opacity rule. Phase 11
  preserves it for entity-resolution; `access_denied` only surfaces for
  visible-entity per-attribute / per-CRUD-op denial.
- `.planning/milestones/v1.0.0-phases/07.2-audit-tree/` — `AuditWriter`
  REQUIRES_NEW boundary, Pitfall #1 (cascade re-save of children) — Phase 11
  reuses `writeToolCall` verbatim, MUST NOT load children fetch plan.

### Add-on source touch points
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` —
  read-only sibling. Helpers `resolveReadableEntityOrThrow`, `parseEntityId`,
  `llmReadableAttributes` extract into shared `ToolEntityResolver` (MUT-09).
  ASM read-only test (Plan 04) MUST stay green — `BuiltInMutationTools` lives
  in a separate class so the test scope is unchanged.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` —
  `canModify(MetaClass)` (Phase 10 D-02) is the gating step 1 entry point.
  `getReadableSchema()` not reused for write path; mutation uses entity-level
  + per-attribute checks via `AccessManager` directly.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` —
  `writeToolCall(parentId, runId, userUsername, conversationId, toolName,
  argumentsJson, resultSummary, latencyMs, outcome, denialReason, errorClass)`.
  REQUIRES_NEW transaction propagation: audit row commits independently of
  the mutation transaction (TEST-12 covers this). Pitfall #1 children-free
  fetch plan applies if Phase 11 ever loads parent rows directly.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java` —
  `resolvedHashSensitiveFields()` + `resolvedSensitiveFields()` Phase 9
  plumbing. Phase 11 is the first consumer via `MutationErrorTranslator` /
  pre/post-image diff in `resultSummary`. Sensitive-fields list is global flat
  Set<String> (attribute simple-name match, not entity-qualified).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java` —
  Phase 9 SHA-256 helper for hashing pre/post-image values when the attribute
  is in the sensitive-fields set.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java` —
  enum extension site for `IDEMPOTENT_REPLAY` + `COMMIT_FAILED`. EnumClass<String>
  shape; values are simple String ids; `fromId` lookup unchanged.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` +
  `ToolVetoedException.java` — pattern that `MutationGuard` mirrors (typed
  intent argument instead of `Map<String,Object>`). `ToolVetoedException`
  reused as-is — no new exception type.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` +
  `SpiDefaultsAutoConfiguration` (Phase 9) — registration pattern for the
  no-op default `MutationGuard` bean.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` —
  `forCurrentUser()` callback assembly site. Phase 11 wires
  `BuiltInMutationTools` (conditional) + `BuiltInLinkTools` (always-on).
  TEST-13 asserts callback count under default config.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` —
  extend with `@EntityPolicy(entityClass=AiMutationIntent.class, actions=ALL)`,
  `@MenuPolicy/@ViewPolicy` for the mutation-intent admin list view if planner
  ships one.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java` —
  add `AiMutationIntent` to the always-excluded set (Phase 10 D-11 mirror).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` —
  source of `runId` + `userUsername` + `conversationId` for audit row +
  idempotency intent key. No changes needed; mutation tools read from it.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` +
  `messages_vi.properties` — every new error code, denial reason, success
  notification key MUST land in BOTH files (CLAUDE.md "ALL locale files").
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/` —
  new `070-ai-mutation-intent.xml` for `AiMutationIntent` table. Include in
  parent `agentstore-changelog.xml`. UUID PK, audit columns, unique constraint
  on `(tool_name, idempotency_key, user_username)`, indexes on `expires_at`
  for cleanup job + on `(tool_name, idempotency_key, user_username)` for
  replay lookup.

### New code to create (planner sketches package layout)
- `com.vn.agent.tools.mutation.BuiltInMutationTools` — 4 `@Tool` methods.
- `com.vn.agent.tools.mutation.MutationErrorTranslator` — exception → 6-code
  mapper with `expected` hints.
- `com.vn.agent.tools.mutation.AiAgentMutationProperties` — `@ConfigurationProperties`.
- `com.vn.agent.tools.mutation.AiMutationIntent` — Jmix entity (or under
  `com.vn.agent.entity` per Claude's Discretion).
- `com.vn.agent.tools.mutation.MutationIntentRepository` — replay lookup
  + cleanup queries.
- `com.vn.agent.tools.mutation.MutationIntentCleanupJob` — `@Scheduled` TTL
  cleanup.
- `com.vn.agent.spi.MutationGuard` — SPI interface.
- `com.vn.agent.spi.MutationIntent` — record carrying toolName + metaClass +
  entityId + attributes (passed to `MutationGuard.check`). Distinct from
  `AiMutationIntent` JPA entity (different concept — runtime call descriptor
  vs persistence dedup row).
- `com.vn.agent.tools.ToolEntityResolver` — shared `@Component` (D-09 of MUT-09).
- `com.vn.agent.tools.link.BuiltInLinkTools` — 2 `@Tool` methods (D-05).
- `com.vn.agent.security.AiAgentMutationRole` — empty marker resource role.

### Reference implementation (pattern-learning, NOT a dependency)
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/JpqlExecutorTool.java` —
  exemplar for the 5-section rich `@Tool` description pattern (D-06).
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/RunReportTool.java` —
  same pattern with structured error codes, MANDATORY workflow, STRICTNESS
  bullets. ERROR HANDLING block documents every code with one-line meaning.
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/ViewsDiscoveryTool.java` —
  exemplar for `BuiltInLinkTools` shape (D-05): `generateEntityListLink` /
  `generateEntityDetailLink` over `ViewRegistry` + `ServerProperties`
  context-path.

### Project conventions
- `CLAUDE.md` — ALL locale files for new strings; UUID + Version +
  InstanceName on `AiMutationIntent`; `DataManager` only (NOT
  `EntityManager`); JetBrains MCP `get_file_problems` after Java work.
- MEMORY (`C:\Users\admin\.claude\projects\D--DTH-ai-agent-core\memory\`):
  - `feedback_rich_tool_descriptions.md` — **NEW**, saved 2026-04-28.
    5-section `@Tool` description pattern. Mandatory for D-06.
  - `feedback_jmix_unconstrained_for_system_writes.md` — `MutationIntentRepository`
    uses `UnconstrainedDataManager` for replay lookups + cleanup writes
    (rules apply globally; user roles must NOT bypass).
  - `feedback_jmix_loadvalue_store.md` — explicit `.store("agentstore")` for
    any raw-JPQL `loadValue` against `AiMutationIntent`.
  - `feedback_ai_as_jmix_client.md` — Jmix `AccessManager` is authoritative
    for the per-attribute `EntityAttributeContext.canModify` step. Phase 11
    layers `LlmExposurePolicy.canModify` ABOVE this for admin denylist
    governance, never replaces it.
  - `feedback_reuse_jmix_builtins.md` — for the parameter conversion path
    audit `Datatype` registry / `MetaProperty.getRange()` BEFORE writing
    custom converters. `FilterLiteralValueConverter` already exists for the
    structured-filter path; reuse for parameter-conversion errors in
    `MutationErrorTranslator`.
  - `feedback_jetbrains_mcp_in_workflow.md` — run `get_file_problems` on each
    new Java file after the chunk.

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — `@JmixEntity` + UUID + `@Version` + `@InstanceName` for
  `AiMutationIntent`; `@Composition` is N/A for this entity (flat).
- `jmix-services` — `DataManager` save semantics; transaction boundary via
  `@Transactional`; `UnconstrainedDataManager` for the dedup repository.
- `jmix-security-roles` — `@ResourceRole` shape for `AiAgentMutationRole`;
  `@EntityPolicy` extension on `AiAgentAdminRole` for `AiMutationIntent`.
- `jmix-i18n` — message bundles for all 4 mutation tool names + 6 error
  codes + denial reasons in both locales.
- `jmix-liquibase` — changelog conventions for `070-ai-mutation-intent.xml`.
- `jmix-testing` — `@SpringBootTest` for TEST-10..13; mocking `DataManager.save`
  to throw `OptimisticLockException` for TEST-12.

### Spring AI primitives to verify in research
- Spring AI 1.1.4 `@Tool` + `@ToolParam` Map<String,Object> coercion behavior
  on the configured provider — verify via Context7 `/spring-ai/spring-ai`
  before planner commits to D-01 Map shape. If the Spring AI JSON-schema
  inference for Map<String,Object> is brittle on small models, fall back to
  `String payloadJson` server-side parse (option D from discussion).
- `OptimisticLockException` propagation through `@Transactional` REQUIRED —
  confirm Jmix wraps it consistently so `MutationErrorTranslator` can match
  cleanly.
- `MethodToolCallbackProvider.builder().toolObjects(...)` discovery rules for
  `@Tool` methods on `@ConditionalOnProperty`-gated beans — verify the bean
  registration sequence so TEST-13 `forCurrentUser` callback count matches
  expectations under each property combination.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BuiltInDataTools` (`com.vn.agent.tools`) — read-only sibling. Helpers
  extract into `ToolEntityResolver` per MUT-09. The class itself stays
  unchanged — no `@Tool` methods added; mutation tools live in the new
  `BuiltInMutationTools` class.
- `LlmExposurePolicy.canModify(MetaClass)` (Phase 10 D-02) — first consumer is
  Phase 11 gating step 1. Pure pass-through to `userCanModify AND NOT
  entityExcluded` already implemented; no Phase 11 changes to this class.
- `AuditWriter.writeToolCall` (Phase 7.2) — REQUIRES_NEW boundary survives
  mutation rollback (TEST-12 design). New `eventName` strings + 2 new
  `outcome` enum values; no schema migration.
- `AiAgentAuditProperties` (Phase 9 D-18) — `hashSensitiveFields` (default
  true) + `sensitiveFields` (Set<String>, default empty). Phase 11 first
  consumer via diff hashing in `resultSummary`.
- `AuditFieldHasher` (Phase 9) — SHA-256 helper for hashing diff values when
  attribute is in sensitive-fields set.
- `ToolGuard` + `ToolVetoedException` — pattern that `MutationGuard` mirrors;
  `ToolVetoedException` reused as-is.
- `ToolFetchPlanCustomizer` + `SpiDefaultsAutoConfiguration` (Phase 9 SPI-09) —
  default-bean registration pattern for `MutationGuard`.
- `FilterLiteralValueConverter` (`com.vn.agent.filter`) — type coercion path
  for structured filters; reuse target type → Java type conversion for
  `MutationErrorTranslator` parameter_conversion_error mapping.
- `ToolErrorDto.expected` (Phase 9 D-14) — hint shape for the 6 mutation
  error codes' recovery instructions.
- `RunContext` (`com.vn.agent.orchestration`) — source for `runId`,
  `userUsername`, `conversationId` consumed by audit + idempotency intent
  key.
- `AiAgentAdminRole` — extension site for `AiMutationIntent` policies.
- `AiInternalEntityNames` (Phase 10) — extension site to mark
  `AiMutationIntent` as always-excluded from `LlmExposurePolicy`.
- `ViewRegistry` + `ServerProperties` (Spring/Jmix) — already on the
  classpath for `BuiltInLinkTools`; no new dependencies.
- `AgentToolCallbacks.forCurrentUser` — assembly site for the new
  callbacks. TEST-13 assertion target.

### Established Patterns
- **Namespace:** `com.vn.agent.*`. Phase 11 adds `com.vn.agent.tools.mutation.*`
  and `com.vn.agent.tools.link.*`. Planner picks final layout per Claude's
  Discretion; favor cohesion.
- **agentstore datasource:** `@Store(name="agentstore")` per existing AI-*
  entities. `@JmixEntity`, UUID + `@JmixGeneratedValue` + `@Version` +
  `@InstanceName` per CLAUDE.md.
- **`UnconstrainedDataManager`:** `MutationIntentRepository` reads/writes
  bypass user-level data security per MEMORY
  `feedback_jmix_unconstrained_for_system_writes` (replay row creation MUST
  succeed regardless of user-role grants on `AiMutationIntent`).
- **Audit:** reuse `AuditWriter.writeToolCall` REQUIRES_NEW path. `eventName`
  carries the tool name; `argumentsJson` carries serialized attributes Map
  with PII hashing; `resultSummary` carries diff JSON for update / created
  id+name for create / relationship action for related-write.
- **Locales:** every new UI / error string in BOTH `messages.properties` and
  `messages_vi.properties`.
- **Liquibase:** numeric prefix per existing convention; include in parent
  `agentstore-changelog.xml`.
- **`@Tool` description style:** 5-section per MEMORY
  `feedback_rich_tool_descriptions` (D-06).

### Integration Points
- `BuiltInMutationTools` is on the chat hot path only when
  `mutation.enabled=true`. Each call: `ToolEntityResolver.resolveWritableEntityOrThrow`
  → `LlmExposurePolicy.canModify` → `AccessManager` `CrudEntityContext` +
  per-attribute `EntityAttributeContext.canModify` per attribute the LLM
  writes → `MutationGuard.check` → `MutationIntentRepository.findOrCreate`
  (idempotency check) → `@Transactional DataManager.save` → audit via
  `writeToolCall` → return result.
- `BuiltInLinkTools` is on every chat turn (always-on, ~5ms route lookup
  per call against in-memory `ViewRegistry`). LlmExposurePolicy lookup is
  the same hot-path call BuiltInDataTools already pays.
- `AiMutationIntent` cleanup job runs hourly; deletes rows where
  `expiresAt < now()`. Idempotent.
- `AgentToolCallbacks.forCurrentUser` callback count varies by property:
  - default (mutation off): 6 data + 2 link = 8 callbacks.
  - mutation on: 6 + 2 + 4 = 12 callbacks.
  - never: a `delete_record` callback under any property combination in v1.1.

</code_context>

<specifics>
## Specific Ideas

- **Mutation tools always layer through BOTH `LlmExposurePolicy.canModify`
  AND `AccessManager` per-attribute checks** — never one or the other.
  Admin denylist (LlmExposurePolicy) sits ABOVE Jmix per-attribute checks;
  AccessManager remains authoritative for what Jmix policies allow.
- **`unknown_entity` vs `access_denied` disambiguation:**
  - Entity unknown OR `LlmExposurePolicy.canReadEntity` denies entity →
    `unknown_entity` (Phase 10 R4 uniform-opacity preserved).
  - Entity visible BUT `LlmExposurePolicy.canModify` denies (admin denylist
    on write side) → `access_denied`. Note this is a deliberate small
    information leak: admin can deny WRITE while allowing READ; LLM can
    differentiate. Acceptable trade-off — write-denial is intentional admin
    governance signal, not security-critical opacity.
  - Entity + write visible BUT per-attribute `canModify` denies → `access_denied`
    with `expected` hint pointing to `agent.permissions[entity].modifiable`
    inventory.
- **Idempotency replay returns FRESH `instanceName`** — re-resolved under
  current locale + security on each replay, not the literal first-call
  string. Token cost is one extra `DataManager.load(...)` on replay; matches
  Phase 9 D-15 stateless-component pattern.
- **`parameter_conversion_error` taxonomy edge case** — when LLM passes a
  String for a relationship attribute (expected UUID), DOES the translator
  return `parameter_conversion_error` (LLM should re-format) or `not_found`
  (LLM should call get_record)? Decision: `parameter_conversion_error` if
  the string isn't a valid UUID format (36-char hyphenated); `not_found` if
  it's a valid UUID format but no record exists. Hint distinguishes the
  recovery path.
- **`BuiltInLinkTools` is read-only** — no audit privacy concern; URL
  strings don't carry PII. No need to hash; no need for sensitive-fields
  treatment.
- **Mutation result schema stays clean** — `{outcome, entityId, instanceName,
  diffSummary?}`. NOT `{outcome, entityId, instanceName, deepLink}`. LLM
  composes the verify-link by calling `generate_entity_detail_link` after
  the mutation. Decoupled. If a future phase wants tighter UX (one round
  trip), schema can extend without breaking — `deepLink` becomes optional.
- **Tests live alongside `BuiltInDataTools` test layout** — not a new
  package; mirror existing convention.

</specifics>

<deferred>
## Deferred Ideas

- **`delete_record` mutation tool** (MUT-13) — destructive ops need separate
  UX with confirmation/undo. v1.2.
- **Bulk mutation** (`update_records`, `delete_records`) — defer until
  concrete consumer demand.
- **`MutationGuard` lifecycle hooks** (`beforeCommit`, `afterCommit`,
  `onRollback`) — only `check(...)` ships in v1.1. Defer until concrete guard
  use case requires them.
- **`MutationIntent` pre-image lazy `Supplier`** — guards needing pre-image
  reload via `DataManager` themselves in v1.1. Add the field if a guard
  consumer documents the need.
- **Mutation preview / dry-run mode** — `confirmationRequired=true` is a UX
  hint metadata only. Phase 12 chat surfaces may render preview UI; not a
  Phase 11 concern. Could become a separate code path in v1.2.
- **`AiMutationIntent` admin list view** — operators may eventually want a
  read-only Flow UI to inspect dedup rows. Defer until a concrete operator
  asks; for v1.1 they can use the audit list (every mutation already audits).
- **JPQL/analytics tool** (SEED-008) — preserves the future capability;
  conflicts with Phase 11 read-surface scope.
- **Auto-title service** — Phase 12 (Configurable Chat Surfaces) consumer.
- **Attribute-level `parameter_conversion_error` per-language hint
  catalogue** (e.g. "use 1500.50 not 1500,50 for European locale") — defer
  until i18n parameter handling becomes a real pain point.
- **`AiAgentMutationRole` row-level scoping** — empty default role; row-level
  scoping defers until concrete deployment requires it.
- **Retroactive 5-section description retrofit on `BuiltInDataTools`** — not
  a Phase 11 ask. Opportunistic cleanup if total tool-description tokens
  exceed ~3k.

### Reviewed Todos (not folded)

- `2026-04-28-add-llm-auto-generated-conversation-titles.md` — reviewed,
  NOT folded. Belongs to Phase 12 (Configurable Chat Surfaces) where the
  conversation list UI is the primary consumer of generated titles. Phase 11
  has no conversation-list surface to consume titles.

</deferred>

---

*Phase: 11-mutation-capable-built-in-tools*
*Context gathered: 2026-04-28*
