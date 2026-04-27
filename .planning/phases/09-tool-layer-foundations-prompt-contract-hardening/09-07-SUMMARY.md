---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 07
subsystem: prompt-contract
tags: [prompt-contract, tools, entity-name, uat-gap]

# Dependency graph
requires:
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: agent.entities baseline inventory, BuiltInDataTools read-only tool surface, Phase 9 prompt rules, TEST-08 prompt-contract suite
provides:
  - Exact entityName tool-call guidance in AgentSystemPromptRules
  - entityName ToolParam descriptions aligned with agent.entities/list_entities
  - Regression coverage for hard-coded host-prefix examples in prompt/tool metadata
affects:
  - phase-10-llm-exposure-policy
  - phase-11-mutation-tools

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Prompt rules distinguish user-facing labels from tool-call entityName arguments."
    - "Tool metadata tells the model to copy entity names from agent.entities/list_entities instead of inferring prefixes."

key-files:
  created:
    - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-07-SUMMARY.md
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java
    - .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-UAT.md
    - .planning/debug/prompt-entity-name-contract.md

key-decisions:
  - "Keep user-facing replies label-first, but make tool-call entityName arguments exact-name-from-inventory."
  - "Remove concrete jmixapp_* examples from model-facing prompt/tool metadata to avoid priming prefix invention."
  - "Preserve UnknownEntityHints byte-for-byte while adding the new entityName guidance."

patterns-established:
  - "When prompt rules expose labels, add an explicit companion rule for tool argument values that must remain canonical."

requirements-completed: []

# Metrics
duration: 12min
completed: 2026-04-27
---

# Phase 09 Plan 07: Entity-Name Tool-Call Contract Gap Closure Summary

**Prompt/tool metadata now tells the model to copy entityName values exactly from agent.entities or list_entities instead of inventing host prefixes.**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-04-27T16:24:10.6831287+07:00
- **Completed:** 2026-04-27T16:36:27.8511537+07:00
- **Tasks:** 4
- **Files modified:** 6

## Accomplishments

- Added regression tests that failed on the old prompt contract: missing exact entityName guidance, hard-coded `jmixapp_Customer`, and hard-coded `jmixapp_Order`.
- Updated `AgentSystemPromptRules.PROMPT_RULES` to say tool arguments named `entityName` must use exactly one entity name shown in `agent.entities` or returned by `list_entities`.
- Updated every `BuiltInDataTools` `entityName` `@ToolParam` description to the same exact-name inventory wording.
- Marked the UAT gap resolved and updated the debug session with the executed fix and verification.

## Task Commits

Each task was committed atomically:

1. **Task 7.1-7.4: entityName prompt contract gap closure** - `5657e39` (fix)

**Plan metadata:** included in the docs completion commit for this plan.

_Note: TDD RED was verified before production edits; the failing run reported 4 expected failures in `AgentSystemPromptRulesTest` and `PromptContractMockTest`._

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java` - Removes concrete host-prefix example and adds exact entityName tool-call guidance.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` - Aligns all `entityName` parameter descriptions with `agent.entities` / `list_entities`.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java` - Locks exact entityName guidance and absence of hard-coded host-prefix examples.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java` - Verifies the composed system prompt carries the new rule.
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-UAT.md` - Marks the UAT gap resolved.
- `.planning/debug/prompt-entity-name-contract.md` - Records the executed fix and verification.

## Decisions Made

- Tool calls still use exact tool schema names, but `entityName` values are not guessed from examples. They are copied from runtime inventory.
- Kept the existing user-facing vocabulary rules intact, so replies still use labels and avoid raw tool names.
- Did not change runtime entity resolution, data envelope formatting, Jmix security, or fetch-plan behavior; this gap was prompt/tool metadata only.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- `gsd-sdk query commit` initially committed only `.planning/STATE.md` even though file paths were supplied. I amended the commit with the intended Java/test files immediately afterward.

## Verification

- RED gate: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.guard.AgentSystemPromptRulesTest" --tests "com.vn.agent.PromptContractMockTest"` failed with the expected new assertions before production edits.
- GREEN gate: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.guard.AgentSystemPromptRulesTest" --tests "com.vn.agent.PromptContractMockTest" --tests "com.vn.agent.tools.UnknownEntityRetryHintTest"` passed.
- JetBrains `build_project` passed for touched Java files.
- JetBrains file inspections are clean for `AgentSystemPromptRules.java`, `AgentSystemPromptRulesTest.java`, and `PromptContractMockTest.java`. `BuiltInDataTools.java` still reports expected `@Tool` reflection/contract-guard warnings unrelated to this fix.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Phase 9's UAT gap is closed. Future Phase 10/11 tool and exposure work should keep the same rule: labels are for user-facing text; tool argument identifiers must be copied from runtime inventory.

---
*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Completed: 2026-04-27*
