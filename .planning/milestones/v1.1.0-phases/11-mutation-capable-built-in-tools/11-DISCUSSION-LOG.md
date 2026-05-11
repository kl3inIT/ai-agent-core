# Phase 11: Mutation-Capable Built-In Tools — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-28
**Phase:** 11-mutation-capable-built-in-tools
**Areas discussed:** Tool args, Idempotency, Guard SPI, Error codes, Deep-link generator (folded mid-discussion via jmix-crm reference review)

---

## Reference repo review (mid-discussion)

User asked to review `D:/DTH/jmix-crm` for cross-pollination ideas:

| Reference idea | Verdict | Disposition |
|---|---|---|
| JPQL executor tool with `LoadValuesAccessContext` | Reject for Phase 11 (scope creep, attribute-level ACL bypass, breaks PROMPT-04 records wrapper) | SEED-008 planted (dormant; trigger = analytics use case + attribute-level SELECT ACL design) |
| YAML schema export multi-entity | Skip — JSON structured output already in place | n/a |
| Auto-generated conversation titles via small-model call | Useful for Phase 12 (Chat Surfaces) | Todo `2026-04-28-add-llm-auto-generated-conversation-titles.md` created |
| Reports-as-tool (Jmix Reports add-on) | User chose to skip | n/a |
| Deep-link generator (`generateEntityDetailLink`) | High ROI for Phase 11 mutation result UX | Todo `2026-04-28-add-deep-link-generator-tool.md` created, then folded into Phase 11 (D-05) |
| Rich `@Tool` descriptions (5-section) | Apply to all Phase 11 tools | MEMORY rule `feedback_rich_tool_descriptions.md` saved |

---

## Tool args

| Option | Description | Selected |
|--------|-------------|----------|
| Map<String,Object> + id-only refs | create_record(entityName, attributes, idempotencyKey); relationships pass as UUID inside attributes map | ✓ |
| Map + id-or-instance_name refs | Same Map shape but server resolves instance_name via unique lookup | |
| Typed payload via Spring AI structured tool | Per-entity DTO synthesized from MetaClass; chatClient.prompt().tools(...) | |
| JSON string + server-side parse | @ToolParam String payloadJson; server parses to Map | |

**User's choice:** Map<String,Object> + id-only refs (Recommended)
**Notes:** Matches Spring AI tool-param JSON inference; LLM uses ids it already saw via find_records / get_record; no instance_name ambiguity; preserves `@Tool`/`@Component` static-discovery pattern.

---

## Idempotency

| Option | Description | Selected |
|--------|-------------|----------|
| (toolName, idempotencyKey, userUsername) + entity id only | Per-user scoping; row stores resultEntityId; replay re-renders instanceName live | ✓ |
| (toolName, idempotencyKey, userUsername) + full result JSON | Same uniqueness; row stores original tool result string verbatim | |
| (idempotencyKey) globally unique | Single-column index; cross-user collision possible | |
| (toolName, idempotencyKey, userUsername, conversationId) | Tightest scoping; conversation-bound | |

**User's choice:** (toolName + idempotencyKey + userUsername), store entity id only (Recommended)
**Notes:** Cheap row, no stale-snapshot risk; instance_name re-resolves under current locale and security on replay.

---

## Guard SPI

| Option | Description | Selected |
|--------|-------------|----------|
| toolName + metaClass + entityId + attributes map | Minimal; mirrors ToolGuard but typed; entityId null on create | ✓ |
| Above + pre-image entity reference (lazy Supplier) | Guards can compare from→to without re-loading | |
| Above + userUsername + conversationId | Adds caller identity context | |
| Full record (preImage + all context fields) | Maximally informative | |

**User's choice:** toolName + metaClass + entityId + attributes map (Recommended)
**Notes:** Forward-compatible (extra fields can be added behind default methods later). Guards needing user/conversation context fetch from CurrentAuthentication / RunContext; pre-image consumers reload via DataManager themselves.

---

## Error codes

| Option | Description | Selected |
|--------|-------------|----------|
| 6 codes: REQ baseline + parameter_conversion_error + not_found | access_denied / validation_failed / idempotency_violation / concurrent_modification / parameter_conversion_error / not_found | ✓ |
| 4 codes: REQ baseline only | parameter_conversion + not_found collapse into validation_failed | |
| 8 codes: above 6 + not_a_relationship + relationship_cardinality_violation | Explicit relationship-tool failures | |
| Extensible registry (enum + STRINGS) | Built-in enum + String escape hatch for MutationGuard custom codes | |

**User's choice:** 6 codes baseline + parameter_conversion_error + not_found (Recommended)
**Notes:** Each code carries ToolErrorDto.expected hint per Phase 9 D-14; LLM can self-correct on parameter_conversion_error and not_found instead of giving up.

---

## Deep-link generator (added mid-discussion)

| Option | Description | Selected |
|--------|-------------|----------|
| Fold into Phase 11 (always-on, independent of mutation flag) | BuiltInLinkTools ships alongside mutation tools; 2 read tools; ~80 LOC + 1 test class | ✓ |
| Fold + extend mutation result with deepLink field | Tighter UX; mutation result schema carries deepLink directly | |
| Keep as separate todo, defer to Phase 12 | Phase 11 stays narrow; verify-link UX waits for Chat Surfaces | |
| Fold but only generate_entity_detail_link | Smallest fold; list-link defers to Phase 12 | |

**User's choice:** Fold vào Phase 11 (Recommended)
**Notes:** Mutation tool result schema stays clean (`{entityId, instanceName}`); LLM calls generate_entity_detail_link separately when it wants to render a verify-link. Decoupled — future phase can extend mutation result with deepLink without breaking.

---

## Claude's Discretion

The following decisions were left for the planner per the user's "fast-intuitive, decisive" preference (deferred without re-asking):

- Package layout for new mutation + link tool classes.
- `AiMutationIntent` entity package placement (`tools.mutation` vs `entity`).
- Liquibase changelog naming (`070-ai-mutation-intent.xml`).
- `AiInternalEntityNames` extension to add `AiMutationIntent`.
- Cleanup-job scheduling (Spring `@Scheduled` cron).
- MUT-10 system-prompt mutation rules wording (Phase 9 `AgentSystemPromptRules`
  extension consumer).
- `AiAgentMutationRole` shape (empty marker vs minimal `AiMutationIntent` self-read grant).
- `AiAgentAdminRole` extension for `AiMutationIntent` CRUD policies.
- `MutationGuard` no-op default bean registration pattern.
- `BuiltInLinkTools` route resolution caching (per-call vs cached).
- `AuditWriter.writeToolCall` argumentsJson + resultSummary serialization shape
  (PII hashing via existing `AuditFieldHasher` Phase 9 plumbing).

---

## Deferred Ideas

Captured during discussion for future phases:

- `delete_record` mutation tool (MUT-13) — v1.2.
- Bulk mutation (`update_records`, `delete_records`) — until concrete demand.
- MutationGuard lifecycle hooks (`beforeCommit`, `afterCommit`, `onRollback`).
- MutationIntent pre-image lazy Supplier.
- Mutation preview / dry-run mode (Phase 12 UI consumer).
- `AiMutationIntent` admin list view.
- JPQL/analytics tool (SEED-008).
- Auto-title service (Phase 12 — todo created).
- Attribute-level `parameter_conversion_error` per-language hint catalogue.
- `AiAgentMutationRole` row-level scoping.
- Retroactive 5-section description retrofit on `BuiltInDataTools`.

### Reviewed Todos (not folded)

- `2026-04-28-add-llm-auto-generated-conversation-titles.md` — Phase 12 consumer.
