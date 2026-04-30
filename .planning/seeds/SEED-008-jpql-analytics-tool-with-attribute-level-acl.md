---
id: SEED-008
status: dormant
planted: 2026-04-28
planted_during: v1.1 / Phase 11 discuss-phase (jmix-crm reference review)
trigger_when: When a concrete analytics chat use case demonstrates that find_records + count_records + structured FilterNode cannot express required aggregation/JOIN/GROUP BY queries, AND attribute-level SELECT-time ACL design is approved.
scope: Large
---

# SEED-008: JPQL/analytics tool with attribute-level ACL

## Why This Matters

The reference `D:/DTH/jmix-crm` ships an `executeQuery(jpql, parameters, selectAliases, offset, limit)` `@Tool` that lets the LLM write JPQL directly. It works well for analytics-style questions ("top 10 clients by total order value last 30 days, group by region") that this add-on's structured `find_records` cannot express without N+1 client-side aggregation.

However, copying the reference verbatim would break three architectural commitments of v1.0/v1.1:

1. **Attribute-level ACL bypass.** `LoadValuesAccessContext` checks entity-level read access only. SELECT-clause attribute references like `SELECT u.passwordHash FROM User u` would pass entity-level Jmix permissions if the user has READ on `User` — bypassing the `FetchPlanIntersector` ACL the entire Phase 9 tool layer hardens around.
2. **Phase 9 PROMPT-04 records-wrapper bypass.** JPQL returns `KeyValueEntity` flat tuples — no `_instance_name`, no entity-label header. The carefully designed `<data entity="<label>" type="<internal>">` wrapper is bypassed.
3. **Phase 10 EXP-05 RAG denylist parity loss.** `RetrievalFilterBuilder` denylist applies to RAG hits but not to JPQL — admin denylist is leaky for direct JPQL queries.

Until those gaps are designed for, JPQL is a foot-gun, not a feature. This seed preserves the future capability without rushing it.

## When to Surface

**Trigger:** All three conditions must hold:

1. A concrete production use case demonstrates `find_records + count_records + FilterNode` cannot express required analytics queries (real audit logs, not speculation).
2. SELECT-time attribute-level ACL design is approved — likely a JPQL query transformer that walks the SELECT/JOIN clauses and rejects (or rewrites) attribute references the user cannot read per `LlmExposurePolicy.canReadAttribute`.
3. RAG/exposure parity decision: how does the Phase 10 entity denylist apply to JPQL? Is `source_entity NOT IN <denylisted>` extended to a full `FROM` clause check?

Surface during `/gsd-new-milestone` when:
- analytics chat features land in milestone scope
- the audit corpus shows recurring multi-step `find_records` chains for aggregation
- enterprise customers ask for "ask the LLM to compute X across our data"

## Scope Estimate

**Large** — 1-2 phases. Touches: new tool class, JPQL parser/transformer for attribute-level ACL, output adapter (KeyValueEntity → records-wrapper), system-prompt extension (~150 lines of JPQL guidance like jmix-crm), new test surface for injection / ACL-bypass attempts.

## Breadcrumbs

Reference implementation:
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/jpql/query/AiJpqlQueryService.java` — `LoadValuesAccessContext` security check pattern, `dataManager.loadValues(jpql).properties(aliases)` execution path, parameter type inference, retry-on-unknown-parameter fallback.
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/tool/JpqlExecutorTool.java` — system-prompt-rich `@Tool` description (~150 lines: aliases, reserved words, Jmix date macros, parameter formats, parser limitations).
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/jpql/query/AiJpqlParameterConverter.java` — Spring `ConversionService`-based parameter type inference (UUID/date/numeric/boolean detection).

Existing add-on touch points (would need extension):
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` — `canReadAttribute(MetaClass, String)` already in place; would need new `validateJpqlSelect(jpql)` returning the set of attribute references for ACL check.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` — projection-not-security comment is the architectural anchor; JPQL transformer would mirror this rule SELECT-side.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java` — `records()` method and PROMPT-04 wrapper would need a tuple-aware variant.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java` — denylist pattern that JPQL `FROM` clause would mirror.

## Notes

Captured during Phase 11 discuss-phase review of jmix-crm.

- Reference's `AS aliases mandatory` rule is good ergonomics — flat tuple needs property names; keep as a contract.
- Reference's parameter type inference (`AiJpqlParameterConverter`) is solid; this add-on already has `FilterLiteralValueConverter` for the structured-filter path — a JPQL implementation could share the same converter.
- Reference's `ensureQueryIsPermitted` only checks entity-level. Designing the SELECT-time attribute walker is the hard part — likely needs an EclipseLink AST walk, not regex.
- Pair with `feedback_rich_tool_descriptions` MEMORY rule — the system-prompt section for this tool will be substantial.
- DO NOT activate this seed inside Phase 11 — Phase 11 is mutation, not read expansion. JPQL extends the read surface, conflicting with Phase 11 scope.
