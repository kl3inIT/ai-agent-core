---
phase: 04-orchestration-core
plan: 05
subsystem: ai-orchestration-tests
tags: [spring-ai, testing, junit5, integration-tests, audit, chat-memory, advisor-chain, live-test, jmix]

# Dependency graph
requires:
  - phase: 04-orchestration-core
    provides: 04-03 audit pipeline (AuditAdvisor, AuditWriter REQUIRES_NEW, ToolCallbackAuditDecorator, AuditListenerFanOut)
  - phase: 04-orchestration-core
    provides: 04-04 orchestration wiring (ChatClientFactory, ConversationGateway D-09, ProjectingChatMemoryRepository D-08, DefaultChatServiceImpl B8)
provides:
  - Structural regression guard for the advisor chain (AuditAdvisor@HIGHEST_PRECEDENCE, MessageChatMemoryAdvisor@MIN+200, ToolCallAdvisor@MIN+300) via reflection on DefaultChatClient.defaultChatClientRequest.advisors
  - End-to-end orchestration happy-path proof (ChatService.ask → conversation auto-create with D-08 title rule, two round-trips produce two chat audit pairs keyed by runId)
  - D-09 opacity proof (cross-user probe and nonexistent-id probe throw identical ConversationNotFoundException with identical message)
  - AUD-02 durability proof (tool throws, outer tx rolls back, audit row still commits via REQUIRES_NEW)
  - D-08 dual-layer parity proof (JdbcChatMemoryRepository row count == AiMessage row count with matching role + content + order)
  - SPI-06 / D-13 fan-out fault-isolation proof (throwing listener does not block recording listener; D-14 afterCommit fires after durable audit write)
  - W14 field-mapping regression guard (AuditWriter round-trip asserts every real getter + reflective negative assertion for legacy B1 method names)
  - TEST-05 gated live semantic check (@Tag("live") + @EnabledIfEnvironmentVariable OPENROUTER_API_KEY double-gate; soft assertion via containsAnyOf)
  - Shared StubChatModelConfiguration test helper that any future Phase-4 test can @Import to bypass real LLM wire
affects: [future evaluation/regression tests, Phase 5 RAG observability tests, Phase 8 release-hardening suite]

# Tech tracking
tech-stack:
  added:
    - "JUnit 5 @Tag('live') filter convention (already configured in ai-agent.gradle to exclude 'live' from default test task)"
    - "@EnabledIfEnvironmentVariable double-gating for live tests"
  patterns:
    - "Shared @TestConfiguration StubChatModelConfiguration providing @Primary ChatModel that echoes last USER message as 'STUB:<text>' (deterministic, no network)"
    - "Reflective advisor-order probe on DefaultChatClient.defaultChatClientRequest.advisors (1.1.4 field path documented in test) — avoids depending on an unstable public accessor"
    - "TransactionTemplate + status.setRollbackOnly() to prove REQUIRES_NEW durability (audit row survives outer rollback)"
    - "@ConditionalOnProperty-gated nested @TestConfiguration — prevents AIConfiguration's plain @ComponentScan from leaking test-only listener beans into sibling tests"
    - "Reflective negative assertion for legacy getter/setter names (B1 regression guard) — asserts forbidden method names NEVER reappear on AiToolCallAudit"
    - "Monotonic createdDate (base.plusNanos(seq)) + seq column for deterministic projection ordering aligned with JDBC insertion order"

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubChatModelConfiguration.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditListenerFanOutTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditWriterFieldMappingTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java (Rule 1 fix — delete-then-insert to mirror JDBC semantics; seq + monotonic createdDate)
    - ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties (added jmix.ai-agent.defaults.* keys consumed by AiParametersResolver at boot)

key-decisions:
  - "Assert advisor order STRUCTURALLY via reflection on DefaultChatClient.defaultChatClientRequest.advisors, not behaviorally. The 1.1.4 ChatClient interface exposes no public accessor; a behavioural test could not distinguish 'ordered correctly' from 'reordered by luck'. Reflection is the only contract available — documented field path in the test."
  - "Dual-layer projection must mirror JDBC delete-then-insert, not append (Rule 1 bug caught by DualLayerParityTest). MessageWindowChatMemory supplies the CUMULATIVE message list each saveAll invocation; appending duplicates rows. Production ProjectingChatMemoryRepository.saveAll was changed to delete the conversation's AiMessage rows before inserting the new cumulative list, matching JdbcChatMemoryRepository.saveAll semantics exactly."
  - "Gate AuditListenerFanOutTest.TwoListeners nested @TestConfiguration with @ConditionalOnProperty(ai-agent.test.fanout-listeners=true) and activate via @TestPropertySource on the test. AIConfiguration's plain @ComponentScan scans com.vn.agent and does NOT filter @TestConfiguration (only @SpringBootApplication's default scan does via TypeExcludeFilter), so without the conditional the two listener beans leaked into FoundationsBootSmokeTest and broke single-bean injection."
  - "Live test double-gated: @Tag('live') (primary, matches the ai-agent.gradle test task exclude) plus @EnabledIfEnvironmentVariable(OPENROUTER_API_KEY) as belt-and-braces in case the tag filter is bypassed. Soft semantic assertion via containsAnyOf('pong','yes','ok','sure') tolerates model drift — the test's primary value is proving the wire works, not specific output text."
  - "B1 regression guard is REFLECTIVE, not textual. AuditWriterFieldMappingTest asserts the AiToolCallAudit class's Method[] does NOT contain any legacy name (setInputJson/setOutputJson/setSuccess/setErrorMessage/setUserId/setCreatedBy/setCreatedAt/setConversationId). Any future attempt to reintroduce them forces code review of AuditWriter (which only writes real field names)."

# Metrics
metrics:
  duration: "~2h (implementation + Rule 1 fixes + full-suite verification)"
  tasks_completed: 3
  files_created: 9
  files_modified: 2
  commits: 4
  completed_at: "2026-04-20T05:50:00Z"
---

# Phase 4 Plan 5: Orchestration Verification Suite Summary

**One-liner:** Nine test classes + shared StubChatModelConfiguration proving advisor ordering, D-08 dual-layer parity, D-09 opacity, AUD-02 durability, SPI-06 fan-out fault-isolation, W14 field mapping, and TEST-05 gated live wire — all driven against the 04-04 orchestration core with a deterministic stub ChatModel (no OpenRouter calls for the default build).

## Objective (from plan)

Implement the Phase 4 verification suite proving the orchestration core works as designed: advisor ordering is structurally correct, ownership opacity holds across users, audit rows survive tool rollback, dual-layer projection stays in lockstep with primary memory, AuditListener fan-out is fault-isolated, and a tagged live test exercises the real OpenRouter path.

## What Was Built

### Task 1 — StubChatModelConfiguration + AdvisorOrderStructuralTest + OrchestrationIntegrationTest
Commit `2edbc34`.

- **StubChatModelConfiguration** — @TestConfiguration providing @Primary `ChatModel` that echoes the last USER message as `"STUB:<text>"`. Uses generic `ChatOptions.builder().model("stub/model")` (not OpenAiChatOptions — ai-agent module does not depend on spring-ai-openai). Reused by every non-live test in this plan via `@Import`.
- **AdvisorOrderStructuralTest** — Reflectively reads `DefaultChatClient.defaultChatClientRequest.advisors`, filters `CallAdvisor` instances, sorts by `getOrder()`, and asserts the exact three expected advisors with their locked order values (`Ordered.HIGHEST_PRECEDENCE`, `Integer.MIN_VALUE + 200`, `Integer.MIN_VALUE + 300`). Also reflects `ToolCallAdvisor.conversationHistoryEnabled` per `ToolCallAdvisorBuilderProbe.INTERNAL_FLAG_FIELD` to prove memory disabled (closure of OQ-1 from 04-03).
- **OrchestrationIntegrationTest** — Two tests: (1) `askWithoutConversationIdCreatesConversation` asserts conversation auto-create with `createdBy=user-A` and D-08 title rule (first-user-message truncated to 80 chars); (2) `askTwiceSameConversationProducesTwoChatAuditPairs` asserts two PRE+POST chat audit pairs keyed by their respective runIds, both referencing the same conversation FK.

### Task 2 — OwnershipOpacity + AuditDurability + DualLayerParity + AuditListenerFanOut + AuditWriterFieldMapping
Commit `a7810a4`.

- **OwnershipOpacityTest** — Probes cross-user conversation access (user-A's conversation queried by user-B) and nonexistent-id access. Asserts the thrown exception's class and message are IDENTICAL and match the default localized message — proves D-09 opacity (no information leak about whether a row exists "for someone else" vs "at all").
- **AuditDurabilityTest** — Uses `TransactionTemplate` + `status.setRollbackOnly()` to prove a `writeToolCall` REQUIRES_NEW audit row survives the outer transaction's rollback (AUD-02).
- **DualLayerParityTest** — After a `ChatService.ask` round-trip, asserts `JdbcChatMemoryRepository.findByConversationId` and `DataManager.load(AiMessage).query(order by createdDate).list()` return the same number of rows in the same order with matching role id (`MessageType.name()`) and content.
- **AuditListenerFanOutTest** — Two nested `@Bean AuditListener` implementations (throwing + recording). Asserts the recording listener still fires despite the throwing sibling, and the afterCommit fan-out (D-14) runs only after the audit write commits. Nested config gated with `@ConditionalOnProperty` to prevent leakage (see decisions).
- **AuditWriterFieldMappingTest** — Four tests: (1) `writeChatPre` round-trip through DataManager asserts runId/kind/phase/userUsername/toolName/outcome/startedAt/promptHash/conversation-FK; (2) `writeToolCall` round-trip asserts argumentsJson/resultSummary (real field names, NOT legacy inputJson/outputJson); (3) BLOCKED outcome persists `denialReason`; (4) reflective negative assertion that AiToolCallAudit has NO getter/setter with any legacy B1 name.

### Task 3 — ChatServiceLiveSemanticTest
Commit `bcfc3c7`.

- **ChatServiceLiveSemanticTest** — @Tag("live") primary gate (matches ai-agent.gradle exclude on the default `test` task) + `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` safety net. Calls the real `ChatService.ask` with prompt "Reply with the single word: pong" and asserts non-blank `content` + runId + conversationId, plus a soft semantic check `containsAnyOf("pong","yes","ok","sure")`. Skipped entirely by the default build.

## Verification

`./gradlew :ai-agent:ai-agent:test` (default task, excludes `@Tag("live")`): **PASS**

All 18 test suites green, 102 tests total (sum across AITest, AuditDurabilityTest, AuditListenerFanOutTest, AuditWriterFieldMappingTest, FilterLiteralValueConverterTest, StructuredFilterConditionMapperTest, FoundationsBootSmokeTest, CurrentUserSchemaAccessTest, AdvisorOrderStructuralTest, AiParametersResolverTest, BaselineContextProviderTest, DualLayerParityTest, OrchestrationIntegrationTest, OwnershipOpacityTest, BuiltInDataToolsReadOnlyTest, PromptInjectionHarnessTest, ToolLimitsTest, ToolResultFormatterTest). 0 failures, 0 errors. Verified via `build/test-results/test/TEST-*.xml` failures=/errors= attributes.

Live test (`ChatServiceLiveSemanticTest`) not run in this verification — requires `OPENROUTER_API_KEY` and is invoked via the separate `liveTest` task per plan 01-01.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ProjectingChatMemoryRepository duplicated projection rows**

- **Found during:** Task 2 (DualLayerParityTest first run)
- **Issue:** After a `ChatService.ask` round-trip with turn-1 user + turn-1 assistant, the Spring AI JDBC store had 2 rows but the Jmix projection had 3. Root cause: `JdbcChatMemoryRepository.saveAll` does atomic delete-then-insert (replaces the full conversation's rows), while `MessageWindowChatMemory` supplies the CUMULATIVE list each turn. The projection's append-only `saveAll` therefore added the user message twice (once from the pre-model save and once from the cumulative post-model save).
- **Fix:** Changed production `ProjectingChatMemoryRepository.saveAll` to delete all `AiMessage` rows for the conversation before inserting the new cumulative list. Added `seq` column setting and monotonic `createdDate = base.plusNanos(seq)` so `ORDER BY createdDate` lines up with JDBC insertion order.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java`
- **Commit:** `866f62e`

**2. [Rule 1 - Test isolation] AuditListenerFanOutTest nested config leaked into sibling tests**

- **Found during:** Task 2 (FoundationsBootSmokeTest ran next and failed with "expected single matching bean but found 2: throwingListener,recordingListener").
- **Issue:** `AIConfiguration` has a plain `@ComponentScan("com.vn.agent")` which picks up any `@Configuration` (including `@TestConfiguration`) under that package root. Plain @ComponentScan does not apply `TypeExcludeFilter` (only `@SpringBootApplication`'s default scan does), so the nested `TwoListeners` config's two AuditListener beans registered in every sibling `@SpringBootTest` context.
- **Fix:** Added `@ConditionalOnProperty(name = "ai-agent.test.fanout-listeners", havingValue = "true")` on the nested `TwoListeners` class and `@TestPropertySource(properties = "ai-agent.test.fanout-listeners=true")` on `AuditListenerFanOutTest`. The listeners now activate ONLY in this one test's context.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditListenerFanOutTest.java`
- **Commit:** `866f62e` (folded with the production fix since both were blocking the same test run).

**3. [Rule 3 - Blocking issue] AiParametersResolver defaults missing from test properties**

- **Found during:** Task 1 (initial @SpringBootTest boot failed resolving `jmix.ai-agent.defaults.model`).
- **Issue:** `AiParametersResolver` binds `@ConfigurationProperties(prefix="jmix.ai-agent.defaults")` at application startup; the test property file had no defaults block.
- **Fix:** Added `jmix.ai-agent.defaults.{model,temperature,top-p,max-tokens,system-prompt}` to `src/test/resources/com/vn/agent/test-app.properties` with stub values (`stub/model`, 0.2, 1.0, 1500, "You are a test assistant.").
- **Files modified:** `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`
- **Commit:** `2edbc34` (folded with Task 1 since it blocked Task 1's @SpringBootTest from booting).

## Authentication Gates

None. All tests use `SystemAuthenticator.runWithSystem(...)` where a user identity is required; no live auth gates hit during this plan.

## Known Stubs

- `StubChatModelConfiguration.stubChatModel` is intentionally a stub — it is a test-only @TestConfiguration bean. Production ChatModel comes from `spring-ai-starter-model-openai` autoconfiguration wired against OpenRouter via 01-01 properties. Not a stub in the shipping code path.

## Self-Check: PASSED

Verified files exist:
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubChatModelConfiguration.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditListenerFanOutTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditWriterFieldMappingTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java` (modified)

Verified commits:
- FOUND: `2edbc34` test(04-05): add StubChatModelConfiguration + AdvisorOrderStructuralTest + OrchestrationIntegrationTest
- FOUND: `a7810a4` test(04-05): add OwnershipOpacity + AuditDurability + DualLayerParity + AuditListenerFanOut + AuditWriterFieldMapping tests
- FOUND: `866f62e` fix(04-05): mirror JDBC delete-then-insert semantics in projection + gate fan-out listeners
- FOUND: `bcfc3c7` test(04-05): add ChatServiceLiveSemanticTest (live wire smoke)
