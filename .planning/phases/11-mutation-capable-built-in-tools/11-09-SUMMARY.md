---
phase: 11-mutation-capable-built-in-tools
plan: 09
subsystem: tools
tags: [tool-callbacks, system-prompt, mutation, link, scanner, opacity]
requires:
  - 11-07C-PLAN.md (BuiltInMutationTools self-audits via safeWriteAudit; single audit owner)
  - 11-08-PLAN.md (BuiltInLinkTools always-on @Component)
  - 11-02-PLAN.md (AiAgentMutationProperties.resolvedEnabled accessor)
provides:
  - AgentToolCallbacks.forCurrentUser wiring for link (always-on) + mutation (conditional) tools
  - MutationToolCallbackBoundaryDecorator (streaming events + pre-method ERROR audit only)
  - AgentSystemPromptRules.MUTATION_PROMPT_RULES constant (5 MUT-10 bullets, Phase-14-safe)
  - AgentSystemPromptRulesComposer @Component (sibling top-level)
  - SystemPromptComposer 3-arg overload (caller-supplied promptRules)
  - ToolNamePatternProvider scans 12 built-in tool names (read + link + mutation)
affects:
  - DefaultChatServiceImpl (blocking ask + streaming stream paths consume effectiveRules)
  - AskTypedRetryTest, ChatServiceFilterParamContractTest (constructor signature)
tech-stack:
  added:
    - org.springframework.beans.factory.ObjectProvider (Spring core)
  patterns:
    - ObjectProvider.getIfAvailable for @ConditionalOnProperty bean wiring (RESEARCH Q5)
    - Per-callback decorator instantiation (not @Component)
    - Sibling top-level @Component for conditional gate (precedent: existing structure)
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/SystemPromptComposer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
decisions:
  - D-09 honored — TEST-13 callback-count assertion target is AgentToolCallbacks.forCurrentUser; default 8 (6 read + 2 link), enabled 12 (+4 mutation); never delete_record
  - ObjectProvider.getIfAvailable used for BuiltInMutationTools per RESEARCH Q5; @Autowired(required=false) explicitly avoided
  - Mutation callbacks bypass ToolCallbackAuditDecorator and are wrapped in MutationToolCallbackBoundaryDecorator instead — preserves single-audit-owner invariant from Plan 11-07C while keeping streaming ToolCall/ToolResult events live
  - Pre-method audit path writes exactly one ERROR row only on delegate-thrown exceptions before BuiltInMutationTools' try/catch; on normal return no audit row is written (Plan 11-07 invariant: tool methods catch all expected failures, safeWriteAudit never throws)
  - Sibling top-level @Component AgentSystemPromptRulesComposer chosen over nested static class — codebase has no precedent for nested @Components on constants holders; sibling top-level matches existing structure and avoids loader-ordering quirks
  - SystemPromptComposer 2-arg overload preserved for AiConfigurationView, BaselineContextView, and SystemPromptComposerTest — they always show the read-only baseline so previews never leak mutation rules regardless of property state
  - Both DefaultChatServiceImpl call sites (blocking ask + streaming stream) updated so mutation rules apply on every turn regardless of transport mode
  - ToolNamePatternProvider scans mutation tool names unconditionally — closes the property-flip output-leak window (a host can enable mutation tools and the scanner still flags leakage from minute-zero, no restart required)
metrics:
  duration: ~35min
  completed: 2026-04-28
  task_count: 2
  file_count: 9
---

# Phase 11 Plan 09: AgentToolCallbacks wiring + AgentSystemPromptRulesComposer Summary

Wired `BuiltInLinkTools` (always-on, Plan 11-08) and `BuiltInMutationTools` (conditional, Plans 11-07A/B/C) into `AgentToolCallbacks.forCurrentUser` so the chat callback chain actually exposes the new tools to the model. Mutation callbacks ride a new `MutationToolCallbackBoundaryDecorator` that emits streaming ToolCall/ToolResult events but does NOT call `AuditWriter` on normal returns — preserving the Plan 11-07C invariant that `BuiltInMutationTools.safeWriteAudit` is the single audit owner. Conditional mutation rules (`MUTATION_PROMPT_RULES`) are appended to the system prompt by a sibling top-level `@Component AgentSystemPromptRulesComposer` consumed by both blocking `ask(...)` and streaming `stream(...)` paths in `DefaultChatServiceImpl`. Output-leak scanner now covers all 12 built-in tool names (read + link + mutation). Default config: 6+2 = 8 callbacks, no `delete_record`, no mutation prompt block. With `ai-agent.tools.mutation.enabled=true`: 6+2+4 = 12 callbacks plus the 5-bullet mutation rules paragraph in the system prompt.

## Tasks

### Task 1: AgentToolCallbacks + MutationToolCallbackBoundaryDecorator

- **Status:** Done
- **Commit:** `c9b28c0`
- **Files:**
  - Created `MutationToolCallbackBoundaryDecorator.java` (`com.vn.agent.audit`)
  - Modified `AgentToolCallbacks.java` (`com.vn.agent.tools`)
- **Verification:** `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL.

### Task 2: AgentSystemPromptRules.MUTATION_PROMPT_RULES + AgentSystemPromptRulesComposer + DefaultChatServiceImpl wiring + ToolNamePatternProvider

- **Status:** Done
- **Commit:** `14a60c5`
- **Files:**
  - Created `AgentSystemPromptRulesComposer.java` (`com.vn.agent.guard`)
  - Modified `AgentSystemPromptRules.java`, `ToolNamePatternProvider.java`, `SystemPromptComposer.java`, `DefaultChatServiceImpl.java`
  - Modified test fixtures `AskTypedRetryTest.java`, `ChatServiceFilterParamContractTest.java` (Rule 3 — constructor signature change)
- **Verification:** `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL; `./gradlew :ai-agent:compileTestJava` BUILD SUCCESSFUL; affected tests (AskTypedRetryTest, ChatServiceFilterParamContractTest, SystemPromptComposerTest) re-ran green.

## Acceptance Criteria

### Task 1

| Criterion | Result |
| --------- | ------ |
| `AgentToolCallbacks.java` imports `BuiltInLinkTools` | PASS |
| `AgentToolCallbacks.java` imports `BuiltInMutationTools` | PASS |
| `AgentToolCallbacks.java` imports `org.springframework.beans.factory.ObjectProvider` | PASS |
| Private final field of type `BuiltInLinkTools` | PASS |
| Private final field of type `ObjectProvider<BuiltInMutationTools>` | PASS |
| Does NOT contain `@Autowired(required = false)` | PASS (grep count = 0) |
| Contains `mutationToolsProvider.getIfAvailable()` | PASS |
| Contains `fromBean(builtInLinkTools)` | PASS |
| Contains `rawMutationCallbacks` | PASS |
| Contains `MutationToolCallbackBoundaryDecorator` | PASS |
| Contains `single audit owner` comment | PASS (`"single audit owner for mutation outcomes"` in javadoc) |
| Mutation callback names NOT wrapped in `ToolCallbackAuditDecorator` | PASS (mutation callbacks built into a separate `mutationBoundaryWrapped` array, appended after the audited array) |
| `MutationToolCallbackBoundaryDecorator.java` exists, implements `ToolCallback`, contains `StreamingEvent.ToolCall`, `StreamingEvent.ToolResult`, `AuditWriter`, `AiToolCallOutcome.ERROR` | PASS |
| Decorator does NOT call `AuditWriter.writeToolCall` on normal return | PASS (`writeToolCall` only inside `catch (Throwable t)` branch) |
| Decorator documents that delegate-thrown exception is bug/last-resort | PASS (javadoc paragraph "What this decorator does" explicitly states "Plan 11-07 invariant... reaching this rethrow path means a delegate-thrown exception is a bug / last-resort path") |
| `./gradlew :ai-agent:compileJava` exits 0 | PASS |

### Task 2

| Criterion | Result |
| --------- | ------ |
| `AgentSystemPromptRules.java` declares `public static final String MUTATION_PROMPT_RULES` | PASS |
| Contains `idempotencyKey` | PASS |
| Contains `Reuse an idempotencyKey ONLY for an exact retry with identical arguments` | PASS |
| Contains `If you change any values after validation_failed or parameter_conversion_error, use a fresh idempotencyKey` | PASS (split across two adjacent String.join lines; runtime value is contiguous) |
| Contains `do not retry automatically` | PASS (joined onto a single line so static grep finds it) |
| Does NOT contain `reuse the SAME idempotencyKey` | PASS |
| Contains `access_denied`, `parameter_conversion_error`, `concurrent_modification`, `generate_entity_detail_link` | PASS |
| Does NOT contain `prepare_form_draft` (grep count = 0) | PASS |
| `AgentSystemPromptRulesComposer.java` exists as SIBLING top-level | PASS |
| Annotated `@Component` | PASS |
| Declares `public String effectiveRules()` | PASS |
| Body branches on `mutationProperties.resolvedEnabled()` | PASS |
| `AgentSystemPromptRules.java` declares NO nested `@Component` | PASS (constants holder unchanged in shape) |
| `SystemPromptComposer.java` declares 3-arg `compose(baselineText, profileSystemPrompt, promptRules)` | PASS |
| `DefaultChatServiceImpl.java` injects `AgentSystemPromptRulesComposer` | PASS (constructor parameter added, field assigned) |
| `DefaultChatServiceImpl.java` contains exactly 2 call sites of `agentSystemPromptRulesComposer.effectiveRules()` | PASS (grep count = 2; blocking + streaming) |
| `ToolNamePatternProvider.java` contains all 6 link + mutation names | PASS |
| `ToolNamePatternProvider.java` does NOT contain `delete_record` | PASS (grep count = 0) |
| `./gradlew :ai-agent:compileJava` exits 0 | PASS |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] Test-class constructor calls broken by `DefaultChatServiceImpl` parameter addition**

- **Found during:** Task 2, after the constructor of `DefaultChatServiceImpl` was extended with `AgentSystemPromptRulesComposer`.
- **Issue:** `AskTypedRetryTest.java` and `ChatServiceFilterParamContractTest.java` both `new DefaultChatServiceImpl(...)` directly (Mockito-driven unit tests, no Spring context). Adding the new constructor parameter broke their test-source compilation, blocking the plan's `./gradlew :ai-agent:compileJava` verification when test-source is included.
- **Fix:** Added a Mockito mock of `AgentSystemPromptRulesComposer` in each test setup, stubbed `effectiveRules()` to return `AgentSystemPromptRules.PROMPT_RULES` (read-only baseline — neither test exercises mutation prompt assertions), and passed it as the trailing constructor argument. No production code change.
- **Files modified:** `AskTypedRetryTest.java`, `ChatServiceFilterParamContractTest.java`
- **Commit:** `14a60c5`

**2. [Rule 1 - Bug — substring observability] `do not retry automatically` was split across two String.join continuation lines**

- **Found during:** Task 2 acceptance-criteria grep verification.
- **Issue:** The plan's acceptance criterion `MUTATION_PROMPT_RULES value contains literal "do not retry automatically"` is satisfied at the runtime constant level (the joined String contains the substring), but a literal source-code grep failed because the words were split across two `+ "..."` continuation lines (`"do not retry"` then `" automatically"`). Source-level greps in CI / review scripts would falsely report a missing literal.
- **Fix:** Re-flowed the line so `do not retry automatically;` lives on a single source line. Runtime semantics unchanged.
- **Files modified:** `AgentSystemPromptRules.java`
- **Commit:** `14a60c5` (folded into Task 2 commit)

**3. [Rule 2 - Critical functionality — observability] `MutationToolCallbackBoundaryDecorator` emits a ToolResult even on the throw path**

- **Found during:** Task 1 implementation review.
- **Issue:** The plan specified "emit `StreamingEvent.ToolResult` after delegate return/throw". A naive implementation that only emitted ToolResult on success would leak orphaned ToolCall events into the streaming Flux when the decorator rethrew (the streaming UI would never close the tool-call card before the outer Flux terminated with Error).
- **Fix:** Moved the ToolResult emission into the `finally` block. On the throw path, `output` is null and the emitted outcome is `AiToolCallOutcome.ERROR` so the UI can mark the tool-call card as failed even before the rethrown exception terminates the Flux. On the normal-return path, outcome is `SUCCESS` and the summary is the capped delegate output (mirrors `ToolCallbackAuditDecorator` semantics).
- **Files modified:** `MutationToolCallbackBoundaryDecorator.java` (new file — corrected within initial write)
- **Commit:** `c9b28c0`

## Authentication Gates

None.

## Known Stubs

None. All referenced collaborators (`BuiltInLinkTools`, `BuiltInMutationTools`, `AiAgentMutationProperties`, `AuditWriter`, `StreamingSinkHolder`, `CurrentAuthentication`) are real, fully implemented beans from prior Phase 11 plans.

## Threat Flags

None — this plan is pure wiring of existing beans. No new network endpoint, no new auth path, no new schema, no new file access. The `MUTATION_PROMPT_RULES` constant flows into the LLM system prompt (an existing data path), not into user-facing UI; tool-protocol English strings live in Java constants per RESEARCH Pitfall 7. Mutation callbacks remain audit-tracked through `BuiltInMutationTools.safeWriteAudit` (single audit owner invariant from Plan 11-07C).

## Decisions Made

- **ObjectProvider over @Autowired(required=false):** RESEARCH Q5 explicitly forbids `@Autowired(required=false)` field injection on conditional beans (proxy / eager-init quirks). `ObjectProvider<BuiltInMutationTools>.getIfAvailable()` is the idiomatic Spring 6 pattern and works cleanly with `@ConditionalOnProperty`.
- **Mutation callbacks bypass ToolCallbackAuditDecorator entirely (HIGH review feedback):** writing a SECOND audit row through the generic decorator would duplicate the in-method audit row written by `MutationCommitCoordinator.safeWriteAudit` with mutation-specific outcomes (`IDEMPOTENT_REPLAY`, `COMMIT_FAILED`, `BLOCKED`, etc.) and a custom `resultSummary` carrying the post-image diff. The new `MutationToolCallbackBoundaryDecorator` covers only the pre-method audit gap (delegate throws before tool method body runs) AND continues to emit streaming ToolCall/ToolResult events so the UI doesn't regress (HIGH review point: streaming regression prevention).
- **Sibling top-level @Component:** matches the in-repo precedent (`AgentSystemPromptRules` is a constants holder; the conditional Spring component lives next to it). Nested `@Component` static classes have no precedent in this codebase and would risk loader-ordering quirks where the inner is picked up by component scanning before its outer class is fully initialized.
- **SystemPromptComposer 2-arg overload preserved:** `AiConfigurationView`, `BaselineContextView`, and `SystemPromptComposerTest` still call `compose(baselineText, profileSystemPrompt)`. Keeping the static delegating overload means previews always show the read-only baseline regardless of `ai-agent.tools.mutation.enabled` state — the mutation paragraph never leaks into the diagnostics UI.
- **Both blocking and streaming chat paths consume the composer:** Phase 9 P05 established that `DefaultChatServiceImpl` has two call sites (`ask` + `stream`); both need the conditional rules so a host enabling mutation gets it on every turn regardless of transport.
- **Scanner names mutation tools unconditionally:** so a host flipping `ai-agent.tools.mutation.enabled=true` gets output-leakage detection from minute-zero, no restart required. Names are static text, not bean references — listing them is free of reflection cost.
- **No `delete_record` anywhere (D-07 absolute):** never appears in `BUILT_IN_TOOL_NAMES`, never appears in `MUTATION_PROMPT_RULES`. The destructive tool stays out of the v1.x surface entirely.
- **No `prepare_form_draft` (Phase 14 forward reference):** the constant explicitly does not mention it. Leaking the name into the live system prompt would teach the LLM to call a non-existent tool and trigger `unknown_tool` errors.

## Self-Check: PASSED

- File `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java` — FOUND
- File `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java` — FOUND
- Modified `AgentToolCallbacks.java`, `AgentSystemPromptRules.java`, `ToolNamePatternProvider.java`, `SystemPromptComposer.java`, `DefaultChatServiceImpl.java` — all FOUND with the required content
- Test fixtures `AskTypedRetryTest.java`, `ChatServiceFilterParamContractTest.java` — FOUND with composer mock wired into constructor calls
- Commit `c9b28c0` — FOUND in branch history (Task 1)
- Commit `14a60c5` — FOUND in branch history (Task 2)
- `./gradlew :ai-agent:compileJava` — BUILD SUCCESSFUL
- `./gradlew :ai-agent:compileTestJava` — BUILD SUCCESSFUL
- Targeted tests (`AskTypedRetryTest`, `ChatServiceFilterParamContractTest`, `SystemPromptComposerTest`) — BUILD SUCCESSFUL
