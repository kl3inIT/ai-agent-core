---
status: resolved
trigger: "UAT Test 3: model selected jmixapp_Customer even though baseline context showed Customer (Khach hang)"
created: 2026-04-27T16:24:10.6831287+07:00
updated: 2026-04-27T16:24:10.6831287+07:00
---

## Current Focus

hypothesis: Prompt/tool metadata gives the model conflicting entity-name guidance.
test: Inspect baseline rendering, prompt rules, tool parameter descriptions, and prompt-contract tests.
expecting: If true, model-facing text will still mention canonical Jmix/internal names or hard-coded prefixes for tool-call arguments.
next_action: Plan a gap-closure change that aligns tool-call entityName guidance with agent.entities/list_entities.
reasoning_checkpoint: null
tdd_checkpoint: null

## Symptoms

expected: The model should use the exact entity name exposed in agent.entities or returned by list_entities when calling tools, while still using human labels in user-facing replies.
actual: User observed a first tool call using {"entityName":"jmixapp_Customer"} even though baseline context exposed Customer (Khach hang).
errors: None reported.
reproduction: UAT Test 3 during Phase 09 verification.
started: Discovered during UAT.

## Eliminated

- hypothesis: describe_entity widened payload or record-list <data> envelope is missing.
  evidence: Existing DescribeEntityPayloadTest and ToolResultFormatterTest cover the widened payload/envelope path; the reported symptom is about tool-call entityName selection before/around record lookup.
  timestamp: 2026-04-27T16:24:10.6831287+07:00

## Evidence

- timestamp: 2026-04-27T16:24:10.6831287+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  found: Prompt rules say tool calls still use canonical entity and tool names and include the concrete example jmixapp_Customer.
  implication: The model is explicitly allowed, and likely primed, to use prefixed internal names in tool arguments.
- timestamp: 2026-04-27T16:24:10.6831287+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  found: @ToolParam descriptions say "Jmix entity name" and describe_entity gives the example jmixapp_Order.
  implication: Tool metadata reinforces prefix-style canonical names instead of "exact name from agent.entities/list_entities".
- timestamp: 2026-04-27T16:24:10.6831287+07:00
  checked: ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java and guard tests
  found: Tests assert user-facing leak prevention and unknown-entity hints, but do not lock tool-call entityName guidance against invented host prefixes.
  implication: The ambiguous guidance can regress without failing TEST-08.

## Resolution

root_cause: Model-facing prompt/tool metadata has a contract gap. Phase 9 forbids internal entity names in user-facing replies, but it does not clearly require entityName tool arguments to be copied exactly from agent.entities/list_entities. Existing prompt text and @ToolParam descriptions still mention canonical/Jmix entity names and hard-coded jmixapp_* examples, which can bias the model to invent or reuse an internal prefix.
fix: Planned only. Add exact-name tool-call guidance, remove hard-coded jmixapp_* examples from prompt/tool metadata, and add prompt-contract tests.
verification: Gap closure plan 09-07 created for execution.
files_changed:
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-UAT.md
  - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-07-PLAN.md
