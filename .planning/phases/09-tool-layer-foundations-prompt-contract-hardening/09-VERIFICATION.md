---
phase: 09-tool-layer-foundations-prompt-contract-hardening
verified: 2026-04-27T16:38:55+07:00
status: passed
score: 30/30 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: closed_after_gap_execution
  previous_score: 28/30 must-haves verified
  gaps_closed:
    - TOOL-11 get_related_records final fetch-plan intersection
    - Baseline agent.roles deterministic ordering
    - UAT entityName prompt/tool metadata contract
  gaps_remaining: []
  regressions: []
gaps: []
human_verification: []
---

# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening Verification Report

**Phase Goal:** Tool-layer foundations + prompt contract hardening — deterministic baseline prompt contract, fetch-plan resolver/intersector enforcement, output-scanner pattern packs, system-prompt rules, audit hashing utility plumbing, and TEST-08 prompt-contract regression coverage.

**Verified:** 2026-04-27T16:38:55+07:00
**Status:** passed
**Score:** 30/30 must-haves verified

## Re-Verification Summary

The prior verification report found two blocker gaps:

1. `get_related_records` composed a final relationship fetch plan after resolver/intersector processing.
2. `agent.roles` ordering could vary with `UserDetails.getAuthorities()` iteration order.

Those gaps are now closed in the current codebase:

- `BuiltInDataTools.getRelatedRecords` resolves the data plan, composes the relationship `INSTANCE_NAME` projection, then calls `fetchPlanIntersector.intersectWithAcl(...)` on the final composed plan before `DataManager.load`.
- `BaselineContextProvider.rolesOf` collects authorities into a sorted `TreeSet`.
- Regression coverage for both behaviors passes.

The UAT gap found after manual testing is also closed:

- `AgentSystemPromptRules.PROMPT_RULES` now instructs the model that tool arguments named `entityName` must use exactly one entity name shown in `agent.entities` or returned by `list_entities`.
- The prompt no longer includes the concrete `jmixapp_Customer` example.
- `BuiltInDataTools` `entityName` `@ToolParam` descriptions now use exact inventory/list wording and no longer include the concrete `jmixapp_Order` example.
- `09-UAT.md` is marked `resolved`.

## Automated Verification

Command:

```powershell
.\gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.BaselineContextProviderTest" --tests "com.vn.agent.tools.FetchPlanIntersectorTest" --tests "com.vn.agent.tools.UnknownEntityRetryHintTest" --tests "com.vn.agent.guard.AgentSystemPromptRulesTest" --tests "com.vn.agent.PromptContractMockTest" --tests "com.vn.agent.audit.AuditFieldHasherTest" --tests "com.vn.agent.audit.AiAgentAuditPropertiesTest"
```

Result: `BUILD SUCCESSFUL`

Additional checks:

- Focused GREEN suite for Plan 09-07 passed:
  `AgentSystemPromptRulesTest`, `PromptContractMockTest`, `UnknownEntityRetryHintTest`.
- JetBrains `build_project` passed for touched Java files.
- JetBrains file inspections are clean for:
  - `AgentSystemPromptRules.java`
  - `AgentSystemPromptRulesTest.java`
  - `PromptContractMockTest.java`
- Remaining `BuiltInDataTools.java` warnings are expected:
  - `@Tool` methods reported as unused because Spring AI invokes them reflectively.
  - `metaClass == null` is a defensive contract guard after `metadata.getClass(...)`.

## Must-Have Coverage

| Area | Status | Evidence |
|------|--------|----------|
| AUD-07 hashing utility + audit properties | VERIFIED | `AuditFieldHasherTest`, `AiAgentAuditPropertiesTest` passed. |
| SPI-09 / TOOL-10 fetch-plan customization SPI | VERIFIED | Existing summaries and tests cover `ToolFetchPlanCustomizer`, `FetchPlanContext`, and default no-op bean. |
| PROMPT-01 / PROMPT-02 baseline inventory and permissions | VERIFIED | `BaselineContextProviderTest` passed, including deterministic role ordering and locale behavior. |
| TOOL-09 describe_entity widening and PROMPT-04 data envelope | VERIFIED | Existing `DescribeEntityPayloadTest` / formatter tests remain covered by prior Phase 9 execution; no changes in this gap closure. |
| TOOL-11 fetch-plan ACL intersection | VERIFIED | `FetchPlanIntersectorTest` and `UnknownEntityRetryHintTest.getRelatedRecords_consultsFetchPlanResolverForDataPlan` passed. |
| PROMPT-03 / PROMPT-06 output scanner and vocabulary rules | VERIFIED | `AgentSystemPromptRulesTest` and `PromptContractMockTest` passed. |
| PROMPT-05 unknown_entity retry contract | VERIFIED | `UnknownEntityRetryHintTest` and prompt contract tests passed. |
| TEST-08 cross-locale prompt contract | VERIFIED | `PromptContractMockTest` passed. |
| UAT entityName tool-call contract | VERIFIED | Plan 09-07 tests passed; `09-UAT.md` gap marked resolved. |

## Gaps

None.

## Human Verification

None required. The remaining behavior is locked by prompt-contract tests and backend unit/integration tests. A future live-model spot check can still be useful, but the live suite remains opt-in under `@Tag("live")`.

---
*Verified: 2026-04-27*
