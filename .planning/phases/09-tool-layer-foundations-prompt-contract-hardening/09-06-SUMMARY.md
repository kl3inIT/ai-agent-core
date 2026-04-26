---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 06
subsystem: testing
tags: [test, integration, locale, en, vi, prompt-contract, test-08, regression-suite]

# Dependency graph
requires:
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-03 BaselineContextProvider agent.* keys (agent.locale token asserted in Test 7)
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-04 BuiltInDataTools.UNKNOWN_ENTITY_HINTS (verbatim D-14 strings asserted in Test 2)
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-05 AgentSystemPromptRules.PROMPT_RULES + DefaultChatServiceImpl wiring (Tests 1 + 6 assert the constant + Test 6 asserts it flows to the LLM Prompt)
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-05 OutputScannerAdvisor + HostPrefixPatternProvider + ToolNamePatternProvider (Tests 3 + 4 assert HOST_PREFIX_LEAK + TOOL_NAME_LEAK flag promotion through ChatResponseDto.flagged + flaggedPatternKey)
provides:
  - "PromptContractMockTest — default-CI prompt-contract regression locking the four Phase 9 contracts (10 tests, 6 parameterised + 4 standalone)"
  - "PromptContractLiveTest — @Tag(\"live\") opt-in real-model variant excluded from default ./gradlew :ai-agent:test by the existing excludeTags 'live' filter"
  - "prompt-contract-fixtures.yaml — documentation-only ground-truth inventory mirroring the inline test assertions"
  - "Test isolation pattern — @ActiveProfiles + @Profile-gated nested @TestConfiguration to prevent the AIConfiguration plain @ComponentScan from picking up a per-test BeanFactoryPostProcessor in OTHER tests' contexts"
affects:
  - "Phase 9 closing posture — TEST-08 is the final Phase 9 success criterion (ROADMAP §Phase 9 Success Criteria #5); all five criteria are now covered by tests across plans 09-01..09-06"
  - "Phase 10 (LlmExposurePolicy substitution) — when the substitution lands, this regression suite is the canonical contract test ensuring the prompt-rule + scanner contract survives"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Per-test @Profile-gated @TestConfiguration: gates a nested @TestConfiguration with @Profile(\"...\") and the outer test class with matching @ActiveProfiles, so AIConfiguration's plain Spring @ComponentScan picks the config up only when the test profile is active. Prevents a per-test BeanFactoryPostProcessor from mutating other tests' shared contexts."
    - "BeanFactoryPostProcessor for test bean replacement: deregisters a scan-discovered @Bean (via BeanDefinitionRegistry.removeBeanDefinition) before autowire so the test's @Primary substitute is the sole candidate. Cleaner than spring.main.allow-bean-definition-overriding=true (which depends on registration order and breaks deterministic resolution when the test config is itself component-scanned)."
    - "Mockito CurrentAuthentication that delegates to SecurityContextHolder: getAuthentication / getUser are stubbed to read the live SecurityContextHolder so Jmix policy stores (AuthenticationPolicyStore.getScope) see the real system-user pushed by SystemAuthenticator.withSystem(...). Locale stays under test control via the Recorder."
    - "@ParameterizedTest @MethodSource for cross-locale assertion (D-17): EN + VI iterations execute the same chat turn under different effective locales (Recorder.setLocale per iteration mutates the @Primary CurrentAuthentication mock's getLocale)."
    - "D-17 cross-locale lock test method: separate non-parameterised @Test that captures Prompt for both EN and VI iterations and asserts the agent.locale system-prompt token differs between them — proves the locale parameterisation is non-cosmetic."

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/live/PromptContractLiveTest.java
    - ai-agent/ai-agent/src/test/resources/prompt-contract-fixtures.yaml
  modified: []

key-decisions:
  - "Rule 3 deviation: leaky entity-prefix string uses 'ai_Customer' rather than the planner's literal 'jmixapp_Customer' example. The test JmixModule (AITestConfiguration) only registers entities under the 'ai' prefix (ai_AiAuditEvent, ai_AiMessage, ...), so HostPrefixPatternProvider compiles its dynamic regex as \\b(ai)_\\w+\\b at startup. The planner's example 'jmixapp_Customer' would NOT trigger that regex in this test environment. Using 'ai_Customer' preserves the planner intent (assert against the dynamically-built regex, not a synthetic operator-configured pattern) while making the assertion actually fire. Documented at PromptContractMockTest class-level Javadoc."
  - "Rule 1 fix during full-suite verification: the static nested PromptContractStubChatModelConfiguration was being picked up by AIConfiguration's plain @ComponentScan in other test classes' contexts (Spring's plain @ComponentScan does not apply Spring Boot's TestComponent-aware TypeExcludeFilter). The BeanFactoryPostProcessor was deregistering stubChatModel from EVERY test context, breaking OrchestrationIntegrationTest. Fixed by gating the @TestConfiguration on @Profile(\"prompt-contract-mock\") + @ActiveProfiles(\"prompt-contract-mock\") on the test class. Other tests' contexts skip the configuration entirely."
  - "Use a unique bean name + BeanFactoryPostProcessor for ChatModel substitution rather than spring.main.allow-bean-definition-overriding=true. The override approach depends on registration order and the @ComponentScan-discovered StubChatModelConfiguration was registering AFTER the @Import-discovered test config in some passes, so the scanned bean would silently overwrite mine. The post-processor approach is order-insensitive."
  - "Mockito CurrentAuthentication.getAuthentication() and getUser() delegate to SecurityContextHolder rather than returning a fixed stub. SystemAuthenticator.withSystem(...) pushes a real system-user Authentication onto SecurityContextHolder for the duration of the chat turn; Jmix's AuthenticationPolicyStore.getScope reads from that Authentication via CurrentAuthentication. A pure stubbed mock would return a null Authentication and the policy store throws NPE. Delegation lets the live system-user Authentication flow through."
  - "Both runWithSystem (void) and withSystem (returning) variants of SystemAuthenticator are used: withSystem when the chat returns a value (most tests), runWithSystem when only the side-effect of the call matters (Test 6 + Test 7)."

patterns-established:
  - "Profile-gated nested @TestConfiguration to scope a test's BeanFactoryPostProcessor to its own context only — reusable for any future test that needs to mutate scan-discovered beans without affecting the broader test suite."
  - "Mockito CurrentAuthentication that delegates to SecurityContextHolder for the auth/user accessors but stubs locale to a Recorder — reusable for any future cross-locale @ParameterizedTest that needs per-iteration locale control inside a real Spring context."
  - "Cross-locale lock test method: a dedicated @Test that captures the system Prompt for each iteration and asserts the agent.locale token differs — guards against the parameterisation accidentally becoming cosmetic if a future change disables locale propagation."

requirements:
  - TEST-08
requirements-completed:
  - TEST-08

# Metrics
duration: ~30min
completed: 2026-04-27
---

# Phase 9 Plan 06: PromptContractMockTest + PromptContractLiveTest (TEST-08) Summary

**Two new test files plus one documentation fixture YAML lock the four Phase 9 prompt contracts: PROMPT_RULES presence in the composed system prompt, the verbatim D-14 unknown_entity hints in BOTH ToolErrorDto.expected[] and the system prompt, the OutputScannerAdvisor HOST_PREFIX_LEAK + TOOL_NAME_LEAK flag promotion, and the D-17 cross-locale guarantee that EN and VI iterations actually execute under distinct effective locales (the system prompt agent.locale token differs per iteration). PromptContractMockTest passes 10/10 in the default CI; PromptContractLiveTest is excluded by the existing @Tag("live") filter in ai-agent.gradle.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-04-26T22:04:02Z
- **Completed:** 2026-04-26T22:34:37Z
- **Tasks:** 2 (6.1 PromptContractMockTest + 6.2 PromptContractLiveTest)
- **Files:** 3 created + 1 modified (post-commit fix) = 4 file touches across 3 commits
- **Tests added:** 10 (PromptContractMockTest: 6 parameterised over 3 methods × 2 locales + 4 standalone) + 2 live-only parameterised iterations (excluded from default CI)

## Accomplishments

### Task 6.1 — PromptContractMockTest + prompt-contract-fixtures.yaml (commit `78c8e46`)

- `PromptContractMockTest` (default CI) — `@SpringBootTest(classes = AITestConfiguration.class)` with a per-test nested `@TestConfiguration` (`PromptContractStubChatModelConfiguration`) that contributes:
  - **`Recorder`** bean — per-test mutable state holding the scripted reply, last captured `Prompt`, and the per-iteration `Locale`.
  - **`@Primary CurrentAuthentication` mock** whose `getLocale()` delegates to the `Recorder` and whose `getAuthentication()` / `getUser()` delegate to `SecurityContextHolder` so Jmix's `AuthenticationPolicyStore` sees the real system-user pushed by `SystemAuthenticator.withSystem(...)`.
  - **`@Primary` `promptContractScriptedChatModel`** — scripted `ChatModel` that records every incoming `Prompt` and replies with `Recorder.scriptedReply()`. Lets each test method script a leaky vs benign reply per iteration.
  - **`BeanFactoryPostProcessor`** that deregisters the `stubChatModel` bean (auto-discovered by AIConfiguration's `@ComponentScan` over `com.vn.agent.test_support`) so the scripted model is the sole `ChatModel` candidate.
- 10 test methods covering all four Phase 9 contracts:
  1. `composedSystemPrompt_carriesPhase9PromptRules` — `AgentSystemPromptRules.PROMPT_RULES` substring contract (PROMPT-03 + D-15).
  2. `unknownEntityToolError_carriesThreeProceduralHintsVerbatim` — calls `BuiltInDataTools.describeEntity("nope_does_not_exist")` directly via the bean, asserts the JSON contains all three D-14 hints verbatim (em dash preserved on the give-up clause).
  3. `entityPrefixLeak_triggersHostPrefixLeakFlag` (×2 locales) — scripts `"The ai_Customer table has 12 rows."`, asserts `ChatResponseDto.flaggedPatternKey() == "HOST_PREFIX_LEAK"`.
  4. `toolNameLeak_triggersToolNameLeakFlag` (×2 locales) — scripts `"I will call find_records to look them up."`, asserts `TOOL_NAME_LEAK`.
  5. `benignReply_doesNotTriggerScanner` (×2 locales) — scripts `"You have 12 customers."` (EN) / `"Bạn có 12 khách hàng."` (VI), asserts `flagged() == false`.
  6. `systemPromptRulesAreCarriedThroughToLLM` — captures the `Prompt` the LLM receives and asserts all four `PROMPT_RULES` substrings appear in the system text (defense-in-depth: confirms the rules actually flow through, not just live in a Java constant).
  7. `systemPromptCarriesDifferentAgentLocaleTokenPerIteration` — D-17 cross-locale lock: runs the chat turn twice (once with `Locale.ENGLISH`, once with `Locale.of("vi","VN")`) and asserts the captured system text contains `agent.locale=en` for EN and `agent.locale=vi_VN` for VI, AND each iteration's text does NOT contain the other locale's token. This guards against the locale parameterisation accidentally becoming cosmetic (if a future change disables locale propagation, both iterations would emit the same `agent.locale` token and Tests 3-5 would still appear to pass while actually testing the same locale twice).
- `prompt-contract-fixtures.yaml` — documentation-only inventory of the assertion ground truth (PROMPT_RULES substrings, three D-14 hints, leaky/benign reply text, EN/VI user messages, agent.locale tokens). Reviewers cross-reference this YAML against the inline assertions when making vocabulary changes.

### Task 6.1 follow-up — Test isolation fix (commit `5a7c33d`)

- Discovered during the full `:ai-agent:test` verification: the static nested `PromptContractStubChatModelConfiguration` was being picked up by AIConfiguration's plain `@ComponentScan` in OTHER test classes' contexts (Spring's plain `@ComponentScan` does not apply Spring Boot's `TestComponent`-aware `TypeExcludeFilter`). The `BeanFactoryPostProcessor` was therefore deregistering `stubChatModel` from EVERY test context, breaking `OrchestrationIntegrationTest` (1 failure: `expected "STUB:..." but was "ok"`).
- Fixed by adding `@Profile("prompt-contract-mock")` to the nested `@TestConfiguration` and `@ActiveProfiles("prompt-contract-mock")` to the outer test class. Other tests' contexts skip the configuration entirely.

### Task 6.2 — PromptContractLiveTest (commit `445226f`)

- `PromptContractLiveTest` at `com.vn.agent.live.PromptContractLiveTest`, mirroring the `ChatServiceLiveSemanticTest` convention:
  - `@Tag("live")` — excluded from default `./gradlew :ai-agent:test` by `excludeTags 'live'` filter at `ai-agent.gradle:135`.
  - `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")` — second gate so the test skips cleanly when the env var is unset, even if the task filter is bypassed.
  - No `StubChatModelConfiguration` import — uses the autoconfigured OpenAI `ChatModel` from the starter (real wire to OpenRouter).
  - `@ParameterizedTest @MethodSource("userMessagesByLocale")` over `Locale.ENGLISH` + `Locale.of("vi","VN")` — same cross-locale parameterisation as the mock test.
- Asserts the live model's user-visible text:
  - Does NOT match the host-prefix regex `\b(jmixapp|sample|acme|jmix|ai)_[A-Z][A-Za-z0-9]*\b`.
  - Does NOT contain any of the six built-in tool names (`list_entities`, `describe_entity`, `find_records`, `count_records`, `get_record`, `get_related_records`).
  - Does NOT contain `RETRIEVAL`.
- Run explicitly via:
  ```
  ./gradlew :ai-agent:liveTest --tests "*PromptContractLiveTest"
  ```

## Task Commits

Three atomic commits:

1. **Task 6.1** — `78c8e46` — `test(09-06): add PromptContractMockTest cross-locale regression suite (TEST-08)`
2. **Task 6.1 fix** — `5a7c33d` — `fix(09-06): gate PromptContractMockTest @TestConfiguration to a profile`
3. **Task 6.2** — `445226f` — `test(09-06): add PromptContractLiveTest opt-in real-model variant (TEST-08)`

## Files Created/Modified

### Created (3)

- `ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java` — default-CI prompt-contract regression (10 tests).
- `ai-agent/ai-agent/src/test/java/com/vn/agent/live/PromptContractLiveTest.java` — `@Tag("live")` opt-in live-model variant.
- `ai-agent/ai-agent/src/test/resources/prompt-contract-fixtures.yaml` — documentation-only ground-truth inventory.

### Modified (0 — production)

No production source changes. All four runtime contracts being tested were landed in plans 09-03 / 09-04 / 09-05.

## Verification

### Acceptance-criteria grep checks (PromptContractMockTest)

```
$ grep -F '@ParameterizedTest' .../PromptContractMockTest.java
3 matches  (>=3 required)

$ grep -F 'Locale.of("vi", "VN")' .../PromptContractMockTest.java
2 matches  (>=1 required)

$ grep -F 'Locale.ENGLISH' .../PromptContractMockTest.java
5 matches  (>=1 required)

$ grep -F 'HOST_PREFIX_LEAK' .../PromptContractMockTest.java
5 matches  (>=1 required)

$ grep -F 'TOOL_NAME_LEAK' .../PromptContractMockTest.java
4 matches  (>=1 required)

$ grep -F 'do not guess' .../PromptContractMockTest.java
3 matches  (>=1 required)

$ grep -F 'systemPromptCarriesDifferentAgentLocaleTokenPerIteration' .../PromptContractMockTest.java
2 matches  (==1 required nominal — declaration + Javadoc reference)

$ grep -F 'agent.locale=en' .../PromptContractMockTest.java
4 matches  (>=1 required)

$ grep -F 'agent.locale=vi_VN' .../PromptContractMockTest.java
4 matches  (>=1 required — D-17 lock)
```

### Acceptance-criteria grep checks (PromptContractLiveTest)

```
$ grep -F '@Tag("live")' .../live/PromptContractLiveTest.java
2 matches  (>=1 required — annotation + Javadoc reference)

$ grep -F '@EnabledIfEnvironmentVariable' .../live/PromptContractLiveTest.java
2 matches  (>=1 required)

$ grep -F 'Locale.of("vi", "VN")' .../live/PromptContractLiveTest.java
1 match  (>=1 required)

$ grep -F 'Locale.ENGLISH' .../live/PromptContractLiveTest.java
1 match  (>=1 required)
```

### excludeTags 'live' filter present in ai-agent.gradle (already in v1.0)

```
$ grep -n "excludeTags 'live'" ai-agent/ai-agent/ai-agent.gradle
135:        excludeTags 'live', 'rag-it', 'eval'
```

The `excludeTags 'live'` filter was already in place (line 135) per the v1.0 convention; PromptContractLiveTest does not need a gradle file change.

### Test execution

```
$ ./gradlew :ai-agent:test --tests "com.vn.agent.PromptContractMockTest"
PromptContractMockTest > 10 tests, 0 failures, 0 errors
BUILD SUCCESSFUL in 42s

$ ./gradlew :ai-agent:test
... 310 tests completed (after the test isolation fix), 0 failed
BUILD SUCCESSFUL in 1m 28s

$ ./gradlew :ai-agent-starter:test
BUILD SUCCESSFUL in 4s

$ ls /d/DTH/ai-agent-core/ai-agent/ai-agent/build/test-results/test/TEST-com.vn.agent.live*.xml
(no such file — PromptContractLiveTest excluded by excludeTags 'live')
```

PromptContractMockTest 10/10 PASS in default CI. PromptContractLiveTest produces no test-results XML in the default suite — confirmed excluded. Full module suite green; starter boot-time bean wiring still passes.

### Cross-assertion bar (D-15)

The three D-14 hint substrings in `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` (Plan 09-04) and `AgentSystemPromptRules.PROMPT_RULES` (Plan 09-05) match byte-for-byte (em dash U+2014 preserved on the give-up clause). PromptContractMockTest's two assertion sites use the same literal strings against both the system prompt (Test 6, via captured Prompt) and the tool error envelope (Test 2, via direct `describeEntity("nope_does_not_exist")` call). Drift between the two ground-truth constants would cause one of the two tests to fail.

## Phase 9 Closing Note

Plan 09-06 is the **last plan in Phase 9**. With this commit, all five Phase 9 success criteria from `ROADMAP.md` are now covered by tests across plans 09-01 through 09-06:

1. **`agent.entities` + `agent.permissions` baseline injection** (PROMPT-01, PROMPT-02, TOOL-12) — Plans 09-02 + 09-03; tested by `BaselineContextProviderTest` (12 tests including the locale-invariance regression and the parity-with-entities check).
2. **Widened `describe_entity` payload + PROMPT-04 envelope** (TOOL-09, PROMPT-04) — Plan 09-04; tested by `DescribeEntityPayloadTest` (7 tests) + updated `ToolResultFormatterTest`.
3. **`unknown_entity` retry contract** (PROMPT-05, D-14, D-15) — Plans 09-04 + 09-05; tested by `UnknownEntityRetryHintTest` (6 tests), `AgentSystemPromptRulesTest` (7 tests), AND **PromptContractMockTest** (Tests 1, 2, 6 — the cross-assertion bar).
4. **OutputScannerAdvisor PROMPT_RULES + pattern packs** (PROMPT-03, PROMPT-06) — Plan 09-05; tested by `HostPrefixLeakScannerTest` (8 tests), `ToolNameLeakScannerTest` (8 tests), AND **PromptContractMockTest** (Tests 3, 4, 5 — flag-promotion through ChatResponseDto).
5. **TEST-08 cross-locale prompt-contract regression** — **Plan 09-06 (this plan)** — `PromptContractMockTest` (10 tests including the D-17 cross-locale lock) + `PromptContractLiveTest` (opt-in).

`ToolFetchPlanCustomizer` SPI (SPI-09) and AUD-07 plumbing landed earlier in Plans 09-01 and 09-02 (also tested). Phase 9 is now feature-complete and ready for verification.

## Decisions Made

See `key-decisions` in the frontmatter — the executive summary:

1. **Rule 3 deviation:** leaky entity-prefix string is `ai_Customer` (the test metamodel's actual prefix) rather than the planner's `jmixapp_Customer` example, so the dynamically-built `\b(ai)_\w+\b` regex actually triggers HOST_PREFIX_LEAK in this environment. Documented at PromptContractMockTest class-level Javadoc.
2. **Rule 1 fix:** profile-gated `@TestConfiguration` to prevent `@ComponentScan` from picking up the per-test BeanFactoryPostProcessor in other tests' contexts.
3. **Bean substitution via BeanFactoryPostProcessor + unique bean name** rather than `spring.main.allow-bean-definition-overriding=true` — order-insensitive and fails fast if the source bean is renamed.
4. **CurrentAuthentication.getAuthentication() / getUser() delegate to SecurityContextHolder** so Jmix's policy stores see the real system-user pushed by SystemAuthenticator.
5. **Both `runWithSystem` (void) and `withSystem` (returning) variants used** depending on whether the chat call's return value is asserted on.

## Deviations from Plan

Two judgement calls during execution that align with the planning intent:

1. **[Rule 3 — Test-environment-vs-planner-example mismatch]** The plan example uses `"jmixapp_Customer"` as the leaky entity-prefix string. The test JmixModule (`AITestConfiguration`) only registers entities under the `ai` prefix (`ai_AiAuditEvent`, `ai_AiMessage`, ...). The `HostPrefixPatternProvider` therefore compiles its dynamic regex as `\b(ai)_\w+\b` at startup, which does NOT match `jmixapp_Customer`. Used `"The ai_Customer table has 12 rows."` instead — preserves the planner intent (assert against the dynamically-built regex from `Metadata.getSession().getClasses()`, not a synthetic operator-configured pattern) while making the assertion actually fire. **No user permission needed; this is a Rule 3 fix to make the planner intent achievable in the actual test environment.**

2. **[Rule 1 — @TestConfiguration scan leakage breaking other tests]** Discovered during full `:ai-agent:test` verification that the static nested `PromptContractStubChatModelConfiguration` was being picked up by AIConfiguration's plain `@ComponentScan` in OTHER test classes' contexts. The `BeanFactoryPostProcessor` was therefore deregistering `stubChatModel` from EVERY test context, breaking `OrchestrationIntegrationTest` (`expected "STUB:..." but was "ok"`). Fixed by gating the @TestConfiguration on `@Profile("prompt-contract-mock")` + `@ActiveProfiles("prompt-contract-mock")` on the test class. **No user permission needed; this is a Rule 1 bug fix to make the test isolation actually work.**

## Issues Encountered

- **`SystemAuthenticator.runWithSystem(...)` accepts only `Runnable`, not a value-returning lambda.** Initial implementation used the planner's `runWithSystem(() -> ... return ...)` shape and hit `void cannot be converted to T` compile errors at six sites. Resolved by switching value-returning calls to `withSystem(...)` (Jmix's typed return overload — `<T> T withSystem(AuthenticatedOperation<T>)`). Documented in source.
- **Mockito CurrentAuthentication NPE inside Jmix policy stores.** Pure stub `getAuthentication()` returns null; `AuthenticationPolicyStore.getScope` calls `authentication.getDetails()` and NPEs. Resolved by stubbing `getAuthentication()` and `getUser()` to delegate to `SecurityContextHolder` so the live system-user Authentication pushed by `SystemAuthenticator.withSystem(...)` flows through. Documented in source.
- **`spring.main.allow-bean-definition-overriding=true` did not work** as the first remediation for the `stubChatModel` bean conflict — the @ComponentScan-discovered bean was being registered AFTER the @Import-discovered one and silently winning. Switched to a unique bean name + BeanFactoryPostProcessor-based deregistration of the conflicting bean. Order-insensitive and explicit.
- **Multiple `@Primary ChatModel` beans (NoUniqueBeanDefinitionException)** because `openAiChatModel` from the starter classpath remained `@Primary` alongside both stub variants. Removing the `stubChatModel` bean via the BeanFactoryPostProcessor leaves only `openAiChatModel` and `promptContractScriptedChatModel` — and since both are `@Primary`, Spring picks our test bean per the @Primary tie-break rule (or fails — but the test passes, so the resolution is deterministic in this Spring version). Documented as a known fragility in the test config Javadoc.

## Threat Surface

No new network endpoints, auth paths, file access patterns, or schema changes were introduced. The threat surface matches `09-06-PLAN.md` `<threat_model>`:

- **T-09-25 (live test logs containing real LLM reply with host data):** **accept** — opt-in `@Tag("live")` posture; the host operator running the live test accepts the risk of capturing sample replies in CI logs. The mock variant (default CI) carries no real data.
- **T-09-26 (flaky live test eroding trust):** **accept** — `@Tag("live")` keeps the live variant out of default CI; flakiness affects only opt-in invocations.
- **T-09-27 (Recorder cross-test contamination via parallel runs):** **mitigated** — `Recorder` is a per-Spring-context `@Bean` registered via the local `@TestConfiguration`; JUnit5 default behaviour is one Spring context per test class. Recorder fields use `volatile` to keep last-prompt and locale reads consistent if a future test runner enables parallel methods within a class. ALSO mitigated cross-context: the `@Profile("prompt-contract-mock")` gate ensures the @TestConfiguration is only active in this test's context.

No new threat flags to surface.

## TDD Gate Compliance

This plan is `type: execute` with `tdd="true"` discipline on Task 6.1. RED gates were satisfied via the test-first authoring pattern:

- Task 6.1 — `PromptContractMockTest` written alongside the production code (which already existed from Plans 09-03..09-05); first run failed at compile (`runWithSystem` signature) then on multiple runtime issues (BeanDefinitionOverrideException, NoUniqueBeanDefinitionException, NPE in policy store, `STUB:` echo from wrong ChatModel). Each issue was diagnosed and fixed before the commit landed; the final commit `78c8e46` is `test(...)` because both the test code and its passing state are in the same atomic change.

The plan-level type is `execute`, not `tdd`, so the plan-level RED/GREEN gate sequence does not apply — only per-task TDD discipline did. Task 6.2 is plain `auto` with no `tdd` flag.

## Self-Check: PASSED

- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java` (FOUND)
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/live/PromptContractLiveTest.java` (FOUND)
- File exists: `ai-agent/ai-agent/src/test/resources/prompt-contract-fixtures.yaml` (FOUND)
- Commit `78c8e46` (Task 6.1) exists in git log (FOUND)
- Commit `5a7c33d` (Task 6.1 fix) exists in git log (FOUND)
- Commit `445226f` (Task 6.2) exists in git log (FOUND)
- All acceptance-criteria grep counts meet thresholds (verified above)
- `:ai-agent:test` BUILD SUCCESSFUL (full module suite, 310 tests, 0 failures)
- `:ai-agent-starter:test` BUILD SUCCESSFUL (no regression)
- PromptContractLiveTest produces no test-results XML in the default suite (confirmed excluded by `excludeTags 'live'`)

---
*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Plan: 06 (FINAL Phase 9 plan — phase moves to verification next)*
*Completed: 2026-04-27*
