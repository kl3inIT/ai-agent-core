---
status: resolved
phase: 09-tool-layer-foundations-prompt-contract-hardening
source:
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-01-SUMMARY.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-02-SUMMARY.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-03-SUMMARY.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-04-SUMMARY.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-05-SUMMARY.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-06-SUMMARY.md
started: 2026-04-27T06:38:51.2343084+07:00
updated: 2026-04-27T16:36:27.8511537+07:00
---

## Current Test

[testing complete]

## Tests

### 1. Baseline Entity and Permission Inventory
expected: A chat turn's baseline prompt includes agent.entities as alphabetically sorted readable entity names with localized labels, and agent.permissions as compact locale-free JSON with CRUD bits plus modifiable attributes. Empty or denied schemas omit both keys, and entities beyond the inventory cap do not leak through the permissions JSON.
result: pass

### 2. Baseline Determinism Across Equivalent Users
expected: Rendering the same baseline for equivalent users produces byte-identical output even if their roles or authorities are supplied in different iteration orders; agent.roles is stable and sorted.
result: pass

### 3. Rich Entity Description and Data Envelope
expected: describe_entity returns the widened metadata payload with comments, attribute type, cardinality, mandatory/readOnly/persistent/transient/primaryKey flags, enum value name+label pairs, relationship target name+label pairs, and maxLength where applicable. Record-list tool output is wrapped as <data entity="label" type="internalName">JSON</data> without duplicating entityName inside the JSON payload.
result: issue
reported: |-
  describe_entity returns the widened metadata payload with comments, attribute type, cardinality, mandatory/readOnly/persistent/transient/primaryKey flags, enum value name+label pairs, relationship target name+label pairs, and maxLength where applicable. Record-list tool output is wrapped as <data entity="label" type="internalName">JSON</data> without duplicating entityName inside the JSON payload. how can i check that and 1 see 1 problem is even in base line context has agent.entities=Customer (Khách hàng) but when agent try to calling tool first time it still select {"entityName": "jmixapp_Customer", "filter": {"property": "name", "operation": "equals", "value": "Công ty An Phát"}, "limit": 100}
severity: major

### 4. Unknown Entity Retry Contract
expected: An unknown entity error includes exactly the three procedural recovery hints, and the chat behavior follows them: call list_entities once, retry with an exact listed entity when one matches, or tell the user no such entity exists when none matches.
result: pass

### 5. Fetch Plan Customizer SPI and Projection Guard
expected: A host ToolFetchPlanCustomizer bean can override tool fetch plans for find_records and get_record, the default no-op bean falls back to FetchPlan.BASE, and every returned plan is narrowed through the ACL intersector before DataManager.load so denied attributes are dropped and PLAN_NARROWED is audited by key/reason.
result: pass

### 6. get_related_records Final Fetch Plan Intersection
expected: get_related_records also intersects the final composed fetch plan before loading data. If a relationship target's @InstanceName uses an attribute denied by Jmix security, that attribute is not fetched or exposed to the LLM-facing payload, and any narrowing is audited.
result: pass

### 7. Output Scanner and Prompt Vocabulary Guard
expected: User-facing chat replies do not expose internal entity names such as host_prefix_Name or raw tool/advisor names such as list_entities, find_records, get_record, get_related_records, describe_entity, count_records, or RETRIEVAL. If a blocking or streaming reply leaks one, ChatResponseDto is flagged with HOST_PREFIX_LEAK or TOOL_NAME_LEAK and audit context stores the pattern key, not the matched text.
result: pass

### 8. Cross-Locale Prompt Contract
expected: The prompt-contract regression runs in English and Vietnamese, the captured system prompt carries the correct agent.locale token for each iteration, benign English/Vietnamese replies are not flagged, and the live model variant remains opt-in only under the live test tag.
result: pass

### 9. Audit Hashing Configuration Plumbing
expected: AuditFieldHasher produces deterministic lowercase SHA-256 hex over UTF-8 for null, empty, ASCII, and Vietnamese text; jmix.ai-agent.audit.hash-sensitive-fields defaults to true; jmix.ai-agent.audit.sensitive-fields defaults to an empty set; and the phase still has no production caller until mutation audit wiring lands later.
result: pass

## Summary

total: 9
passed: 8
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: |-
    describe_entity returns the widened metadata payload with comments, attribute type, cardinality, mandatory/readOnly/persistent/transient/primaryKey flags, enum value name+label pairs, relationship target name+label pairs, and maxLength where applicable. Record-list tool output is wrapped as <data entity="label" type="internalName">JSON</data> without duplicating entityName inside the JSON payload.
  status: resolved
  reason: |-
    User reported: describe_entity returns the widened metadata payload with comments, attribute type, cardinality, mandatory/readOnly/persistent/transient/primaryKey flags, enum value name+label pairs, relationship target name+label pairs, and maxLength where applicable. Record-list tool output is wrapped as <data entity="label" type="internalName">JSON</data> without duplicating entityName inside the JSON payload. how can i check that and 1 see 1 problem is even in base line context has agent.entities=Customer (Khách hàng) but when agent try to calling tool first time it still select {"entityName": "jmixapp_Customer", "filter": {"property": "name", "operation": "equals", "value": "Công ty An Phát"}, "limit": 100}
  severity: major
  test: 3
  root_cause: |-
    Model-facing prompt/tool metadata has a contract gap. Phase 9 forbids internal entity names in user-facing replies, but it does not clearly require entityName tool arguments to be copied exactly from agent.entities/list_entities. Existing prompt text and @ToolParam descriptions still mention canonical/Jmix entity names and hard-coded jmixapp_* examples, which can bias the model to invent or reuse an internal prefix.
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java"
      issue: "PROMPT_RULES says tool calls still use canonical entity names and includes a concrete jmixapp_Customer example, but does not say entityName must be copied exactly from agent.entities/list_entities."
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java"
      issue: "@ToolParam descriptions still say Jmix entity name and describe_entity gives the concrete example jmixapp_Order."
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java"
      issue: "Prompt-contract tests assert user-facing vocabulary and hints, but do not lock the tool-call entityName selection rule."
  missing:
    - "Add a prompt rule that entityName tool arguments must use the exact name string from agent.entities or list_entities; the model must not infer or add host prefixes."
    - "Remove hard-coded jmixapp_* examples from model-facing prompt/tool metadata, or replace them with exact-name-from-inventory wording."
    - "Add regression tests proving the composed system prompt carries the exact-name tool-call rule and no concrete host-prefix example."
  fix: "Implemented by commit 5657e39 via Plan 09-07."
  verification: "Focused Gradle suite passed and JetBrains build passed for touched files."
  debug_session: ".planning/debug/prompt-entity-name-contract.md"
