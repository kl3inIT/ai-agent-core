---
phase: 06-parameters-structured-output-guardrails
plan: 04
subsystem: [orchestration, guard-wiring, structured-output, ask-typed]
tags: [chat-service, askTyped, overrides, bean-output-converter, scanner-promotion, typed-exceptions]
requires:
  - com.vn.agent.parameters.Overrides
  - com.vn.agent.orchestration.AiParametersResolver (effectiveModel/effectiveSystemPrompt overloads)
  - com.vn.agent.guard.RateLimitGuard
  - com.vn.agent.guard.TokenBudgetGuard
  - com.vn.agent.guard.OutputScannerAdvisor
  - com.vn.agent.guard.GuardedToolCallingManager
  - com.vn.agent.audit.AuditWriter
  - com.vn.agent.orchestration.ChatResponseDto (+ GuardDenialInfo)
  - com.vn.agent.guard.* exception types
provides:
  - ChatService.ask(String, UUID, String, Overrides)
  - ChatService.askTyped(String, UUID, String, Class<T>)
  - ChatService.askTyped(String, UUID, String, Overrides, Class<T>)
  - DefaultChatServiceImpl wired runtime path (guard preamble + scanner promotion + structured-output retry + typed-exception mapper)
  - ChatClientFactory advisor chain with OutputScannerAdvisor at HP+400
affects:
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java (constructor/stub update)
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java (@ConditionalOnBean gate)
tech-stack:
  added: []
  patterns:
    - "BeanOutputConverter<T> + format hint appended to user message; narrow catch on RuntimeException whose cause is JsonProcessingException"
    - "Typed-exception mapper — switch on i18n messageKey from GuardDenialInfo, re-throws guard exceptions so askTyped callers can react in code"
    - "RunContext + IterationCounter paired lifecycle (start on entry, clear in finally)"
    - "Scanner flag read from ChatClientResponse.context() via CONTEXT_KEY_FLAGGED_PATTERN — pattern KEY only, never matched text (D-17/D-18)"
    - "@Qualifier(\"outputScannerAdvisor\") CallAdvisor injected as LAST entry in defaultAdvisors — inner to ToolCallAdvisor so it sees the final assistant text"
key-files:
  created:
    - .planning/phases/06-parameters-structured-output-guardrails/06-04-SUMMARY.md
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
decisions:
  - "BeanOutputConverter exception shape (D-21 confirmed in Spring AI 1.1.4): parse failures throw a plain java.lang.RuntimeException whose cause is com.fasterxml.jackson.core.JsonProcessingException — there is NO BeanOutputParseException class. askTyped's catch block narrows on `ex.getCause() instanceof JsonProcessingException` and rethrows otherwise, so guard exceptions raised from inside ask() (RateLimitExceeded, TokenBudgetExhausted, IterationCapExceeded, ToolVetoed) propagate cleanly without being swallowed by the retry loop."
  - "ChatClient.Builder tool-calling-manager wiring: Spring AI 1.1.4's ChatClient.Builder has NO explicit tool-calling-manager setter — the ChatModel resolves the ToolCallingManager via the ApplicationContext's primary bean at prompt time. Since the starter's AiAgentGuardAutoConfiguration registers GuardedToolCallingManager as @Primary, it is auto-picked with zero changes to ChatClientFactory. The decorator wiring is therefore invisible at the factory level — documented here so future contributors do not chase a missing setter."
  - "ChatResponseDto.ok(...) bridge factory RETAINED. The factory predates Plan 01's full 8-component record and is still used by test harnesses and the service's own happy-path construction (short call site). Removing it would be a gratuitous churn commit; the record's explicit constructor is available for callers that want every field."
  - "Final advisor chain order (innermost last): AuditAdvisor @ HIGHEST_PRECEDENCE (0) → MessageChatMemoryAdvisor @ HP+200 → RetrievalAugmentationAdvisor @ HP+250 → ToolCallAdvisor @ HP+300 → OutputScannerAdvisor @ HP+400. Scanner runs AFTER tool loops converge so it observes the final assistant content, matching D-17's placement contract."
  - "AiAgentGuardAutoConfiguration.guardedToolCallingManager gained @ConditionalOnBean(name=\"toolCallingManager\") — without it, test contexts that do not load Spring AI's default tool-calling autoconfig fail at resolveDelegate() with NoSuchBeanDefinitionException. Rule 3 fix: keeps the decorator a pure wrapper that declines to register when there is nothing to wrap."
  - "Structured-output retry budget = 2 total attempts (initial + 1 retry) per D-19. On parse failure, the user message is enriched with an explicit remediation clause (\"Your previous reply could not be parsed. Strictly follow this format:\") prepended to the original BeanOutputConverter format hint. On two consecutive failures, StructuredOutputException(lastRaw, targetType) surfaces to the caller — lastRaw is the model's final attempt for debugging, not logged to audit."
metrics:
  duration: "~90 minutes (including mid-plan context compaction)"
  completed: "2026-04-21"
  tasks: 3
---

# Phase 6 Plan 04: Runtime Chat Path Wiring Summary

Wire Phase 6's foundation (Plan 01 types), parameters (Plan 02 resolver + Overrides), and guards (Plan 03 RateLimitGuard, TokenBudgetGuard, OutputScannerAdvisor, GuardedToolCallingManager) into the runtime ChatService + ChatClient advisor chain with `askTyped` structured-output retries and stable typed-exception mapping.

## Tasks Executed

1. **Task 1 — extend ChatService** (commit `2cb5035`): Added three new signatures on the service interface — `ask(userId, convId, msg, Overrides)`, `<T> askTyped(userId, convId, msg, Class<T>)`, `<T> askTyped(userId, convId, msg, Overrides, Class<T>)`. Original `ask(userId, convId, msg)` preserved unchanged.

2. **Task 2a — wire guard preamble in ask(Overrides)** (commit `498103b`): DefaultChatServiceImpl constructor grew from 7 to 11 deps (+RateLimitGuard, +TokenBudgetGuard, +AuditWriter, +jakarta.validation.Validator). New ask(Overrides) body: `RunContext.set(runId) → IterationCounter.start() → try { conversationGateway.loadOrCreate → rateLimitGuard.check(userId) → tokenBudgetGuard.check(convId) → AiParametersResolver overloads → chatClient.prompt()...call().chatClientResponse() → tokenBudgetGuard.accumulate(usage.totalTokens) → read OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN } catch IterationCapExceeded/ToolVetoed → ChatResponseDto.denied with i18n keys } finally { IterationCounter.reset(); RunContext.clear() }`. RAG filter param + audit.runId param + conversationId param preserved.

3. **Task 2b — askTyped retry loop + typed-exception mapper** (commit `0c4a9b0`): BeanOutputConverter-driven retry (2 attempts, narrow catch on `ex.getCause() instanceof JsonProcessingException`), jakarta.validation validation check on parsed bean, `mapDenialToTypedException(GuardDenialInfo, Class<?>)` switches on the i18n messageKey to re-throw the matching typed guard exception. Also carried the ChatServiceFilterParamContractTest constructor extension and the `@ConditionalOnBean(name="toolCallingManager")` gate on AiAgentGuardAutoConfiguration (Rule 3 fix — see Deviations).

4. **Task 3 — wire OutputScannerAdvisor into ChatClientFactory** (commit `6716b55`): Injected via `@Qualifier("outputScannerAdvisor") CallAdvisor`, appended as the LAST entry in `.defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor, outputScannerAdvisor)`. Updated the Javadoc advisor-ordering list to include HP+400.

## Preserved Invariants

- **RunContext lifecycle** — `RunContext.set(runId)` on entry, `RunContext.clear()` in finally (paired with IterationCounter.reset()).
- **ConversationGateway opacity** — service never touches AiConversation internals; loadOrCreate returns an entity used only for its id.
- **audit.runId advisor param** — still passed via `.advisors(a -> a.param("audit.runId", runId))` on every request.
- **RAG filter param** — `VectorStoreDocumentRetriever.FILTER_EXPRESSION` set iff `retrievalFilterBuilder.buildFor(auth)` returned non-null (admin-bypass path correctly OMITS the call — ChatServiceFilterParamContractTest pins this).

## Plan-Output-Spec Answers

- **BeanOutputConverter exception class**: plain `java.lang.RuntimeException` whose cause is `com.fasterxml.jackson.core.JsonProcessingException`. No `BeanOutputParseException` class exists in Spring AI 1.1.4.
- **ChatClient.Builder tool-calling-manager API**: no explicit setter. Auto-pickup via the application context's @Primary ToolCallingManager bean. GuardedToolCallingManager is registered @Primary by AiAgentGuardAutoConfiguration → wiring is transparent at the factory.
- **ChatResponseDto.ok(...) bridge**: RETAINED (see Decisions above).
- **Final advisor chain order**: AuditAdvisor (0) → MessageChatMemoryAdvisor (+200) → RetrievalAugmentationAdvisor (+250) → ToolCallAdvisor (+300) → OutputScannerAdvisor (+400).
- **Regressions in Phase 2-5 tests**: None introduced by this plan. 28 pre-existing `@SpringBootTest` context-bootstrap failures rooted in eclipselink `StandardQueryCache` init predate Plan 06-04 — see Deferred Issues.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Guard autoconfig rejects contexts without Spring AI default ToolCallingManager**
- **Found during:** Task 2b (compile-green, test-run)
- **Issue:** AiAgentGuardAutoConfiguration.resolveDelegate() threw IllegalStateException on test contexts that did not load Spring AI's default tool-calling autoconfig — the decorator had no delegate to wrap.
- **Fix:** Added `@ConditionalOnBean(name = "toolCallingManager")` to the `guardedToolCallingManager` bean method so the decorator simply skips when the upstream bean is absent. Prod contexts continue to register the @Primary decorator; test contexts get the raw upstream manager (or none, if the test doesn't need it).
- **Files modified:** ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
- **Commit:** `0c4a9b0` (folded into Task 2b commit)

**2. [Rule 3 - Blocking] Test harness constructor mismatch**
- **Found during:** Task 2a
- **Issue:** DefaultChatServiceImpl constructor grew from 7 to 11 deps — ChatServiceFilterParamContractTest stopped compiling.
- **Fix:** Extended test setUp() with mocks for RateLimitGuard, TokenBudgetGuard, AuditWriter, Validator; switched the ChatClient fluent-chain stub to `.call().chatClientResponse()` with a mock ChatClientResponse whose `context()` returns an empty HashMap; aligned parametersResolver stubs to the new AiParametersResolver overloads (`effectiveModel(any, any)`, `effectiveSystemPrompt(any, anyString, any, any)`).
- **Files modified:** ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
- **Commit:** `0c4a9b0` (folded into Task 2b commit)

### Deferred Issues (Out of Scope)

**Pre-existing @SpringBootTest infrastructure failures — 28 tests.** Every failing suite surfaces a `java.lang.IllegalStateException at StandardQueryCache.java:52` (eclipselink QueryCache init) via the Spring test context bootstrap. These failures exist on the wave-2 baseline (Plan 06-03 ship) — re-confirmed by reverting local changes pre-commit. The focused unit test this plan directly targets — `com.vn.agent.rag.ChatServiceFilterParamContractTest` — PASSES both filter-set and admin-bypass cases (see `build/reports/tests/test/classes/com.vn.agent.rag.ChatServiceFilterParamContractTest.html`, 2/2 green, 1.23s). Scope boundary per executor rules: not triggered by any file 06-04 modifies. Recommend Phase 6 Plan 05 or a follow-up infrastructure plan address the eclipselink test-cache init root cause separately.

## Threat Surface Notes

Threat register mitigations applied:
- **T-06-22** (InfoDisclosure on scanner promotion): Service reads ONLY `OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN` (a stable key string) — no reads of any "matched text" context key. The resulting ChatResponseDto.flaggedPatternKey is the advisor-promoted KEY, never the matched span.
- **D-10 opacity**: Typed guard exceptions catch bodies populate ChatResponseDto.denied with i18n keys (`ai-agent.guard.rate-limit-exceeded`, `ai-agent.guard.token-budget-exhausted`, `ai-agent.guard.iteration-cap-exceeded`, `ai-agent.guard.tool-vetoed`) — no raw ceilings or internal state.
- **D-19 retry budget**: askTyped loop is hard-capped at 2 total attempts; no unbounded recursion or attempt counter under attacker influence.

## Self-Check: PASSED

- `.planning/phases/06-parameters-structured-output-guardrails/06-04-SUMMARY.md` — FOUND
- Commit `2cb5035` (Task 1 — ChatService signatures) — FOUND
- Commit `498103b` (Task 2a — ask(Overrides) body) — FOUND
- Commit `0c4a9b0` (Task 2b — askTyped retry + mapper + autoconfig gate + test update) — FOUND
- Commit `6716b55` (Task 3 — OutputScannerAdvisor in ChatClientFactory) — FOUND
- `./gradlew :ai-agent:compileJava` — exit 0
- `ChatServiceFilterParamContractTest` — 2/2 passed (both non-admin filter-set path AND admin-bypass omission path)
