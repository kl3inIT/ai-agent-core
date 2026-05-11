---
created: 2026-04-28T00:00:00+07:00
title: Add deep-link generator tool
area: tools
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInLinkTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
---

## Problem

After a successful `create_record` / `update_record` call (Phase 11) or any `find_records` / `get_record` retrieval, the LLM cannot point the user at the Jmix detail/list view of the entity. Users have to navigate manually to verify the result. jmix-crm reference solves this with `generateEntityListLink(entityName)` and `generateEntityDetailLink(entityName, entityId)` returning relative URLs the chat UI renders as `<a href>`.

## Solution

Add a `BuiltInLinkTools` `@Component` (separate from `BuiltInDataTools` so the read-only ASM test stays scoped to data tools) exposing two `@Tool` methods:

- `generate_entity_list_link(entityName)` — returns relative URL string `/<contextPath>/<listViewRoute>` or `null` if entity has no list view route or LLM cannot read entity per `LlmExposurePolicy.canReadEntity`.
- `generate_entity_detail_link(entityName, entityId)` — returns `/<contextPath>/<detailViewRoute>/<entityId>` or `null`.

### Scope

1. New `BuiltInLinkTools` `@Component`. Inject `ServerProperties`, `ViewRegistry`, `MetadataTools`, `LlmExposurePolicy`.
2. Both tools fail-closed via `LlmExposurePolicy.canReadEntity(metaClass)` BEFORE returning a route — uniform-opacity (return `unknown_entity` ToolUserError, never `access_denied`) per Phase 10 D-04 R4.
3. Tool description follows `feedback_rich_tool_descriptions` 5-section pattern: MANDATORY (call describe_entity first to confirm entity exists), INPUT (entityName from agent.entities verbatim), STRICTNESS (do not invent route prefixes; do not fabricate ids).
4. Wire into `AgentToolCallbacks.forCurrentUser()` alongside `BuiltInDataTools` and (when `mutation.enabled=true`) `BuiltInMutationTools`.
5. Audit: each link generation writes a `writeToolCall` row (eventName `generate_entity_list_link` / `generate_entity_detail_link`, low-value but consistent with audit-everything posture).
6. Tests: link returned for permitted entity, null for non-existent, `unknown_entity` for denylisted entity, contextPath prefix correct.

### Why this is NOT a Phase 14 conflict

- Phase 14 `prepare_form_draft` returns a structured payload `{action: open_form_with_draft, draftId, ...}` that the chat UI controller consumes to call `ViewNavigators.detailView().newEntity().withInitializer(...)`. The LLM never receives `ViewNavigators` (P-17 mitigation).
- This tool returns a plain URL string — no UI primitive, no navigation API. The chat UI renders it as `<a href>` markup; clicking it routes through the host's normal Vaadin/Jmix navigation pipeline, which performs `AccessManager.isPermitted(ViewContext)` exactly as for any human-clicked link.
- Concretely: `prepare_form_draft` = "open NEW form with prefilled draft"; this tool = "view EXISTING record".

### Decisions to make during planning

- **Phase placement?** Phase 11 has the strongest ROI (mutation tools return entity id → LLM immediately offers verify-link). Could also wait for Phase 14. Recommend Phase 11.
- **Render contract?** Return raw URL string vs Markdown `[label](url)`. Lean toward raw URL — chat UI / system prompt instructs LLM to render Markdown links. Keeps tool composable.
- **Whitelist allowlist of viewable entities?** Already covered by `LlmExposurePolicy.canReadEntity` — no extra config needed.
- **Internal entity guard?** Reuse `AiInternalEntityNames` (already excluded by `LlmExposurePolicy` Phase 10).

### Pairs with

- Phase 11 mutation tools — link verifies the just-created/-updated record.
- MEMORY `feedback_rich_tool_descriptions` — tool description style.
- Phase 9 D-14 `unknown_entity` hint contract — link generator follows the same opacity rule.
