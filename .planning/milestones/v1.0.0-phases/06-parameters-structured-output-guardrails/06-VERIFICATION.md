---
phase: 06-parameters-structured-output-guardrails
verified: 2026-04-21T05:15:00Z
status: passed
score: 23/23 must-haves verified
overrides_applied: 0
---

# Phase 6: Parameters, Structured Output & Guardrails — Verification Report

**Phase Goal:** Admin-editable parameter profiles, structured-output affordance, and the complete guardrail stack (iteration caps, token circuit breaker, rate limit, output injection scanner).

**Verified:** 2026-04-21
**Status:** passed
**Re-verification:** No — initial verification
**Score:** 23 must-have truths verified (across 5 plans) + all 12 requirement IDs satisfied + all 6 CONTEXT locked decisions honoured

## Goal Achievement

### ROADMAP Success Criteria

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Admin creates a profile, marks active; ChatService uses it on next request | ✓ VERIFIED | `ParametersService.create/setActive` (transactional flip-then-set, D-06); `AiParametersResolver.resolveActive()` re-resolves per-request (Phase 4 D-04 preserved); `ParametersServiceTest.exactlyOneActiveAfterSetActive` + `concurrentSetActiveResolvesToOneWinner` green |
| 2 | Tool guard vetoes a denied tool; audit row has denialReason | ✓ VERIFIED | `GuardedToolCallingManager.executeToolCalls` lines 96–130 call `toolGuard.check` before delegation, audit veto with real tool name + BLOCKED + denialReason="tool-vetoed:…"; `GuardedToolCallingManagerTest.toolVetoIsAuditedWithRealToolName` green |
| 3 | Iteration-cap test: loop terminates bounded | ✓ VERIFIED | Strict `next > cap` in decorator; IterationCapExceededException audited with __chat__ sentinel; `iteration-cap-fixtures.yaml` drives parametric test allowing 6 then throwing at 7; `iterationCapBreachAuditsWithChatSentinel` green |
| 4 | Structured-output: askTyped returns parsed object; on malformed output, one retry then typed error | ✓ VERIFIED | `DefaultChatServiceImpl.askTyped` lines 264–302 with `maxAttempts = 2`; narrow `JsonProcessingException` cause guard; throws `StructuredOutputException(lastRaw, targetType)` on exhaustion; `AskTypedRetryTest` fixture-driven (happy + retry-succeed + exhaust + validation-recover) all green |

### Observable Truths (from plan must_haves, merged across 5 plans)

**Plan 01 foundations (6 truths):**

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | AiToolCallOutcome.FLAGGED exists + locale-keyed in EN+VI | ✓ VERIFIED | Enum has FLAGGED; messages.properties + messages_vi.properties both contain `com.vn.agent.entity/AiToolCallOutcome.FLAGGED` ("Flagged" / "Đã gắn cờ") |
| 2 | ChatResponseDto carries flagged + flaggedPatternKey + GuardDenialInfo | ✓ VERIFIED | 8-component record + nested GuardDenialInfo + denied() factory present (used at 4+ call sites in DefaultChatServiceImpl) |
| 3 | Four typed guard exceptions under com.vn.agent.guard | ✓ VERIFIED | `RateLimitExceededException`, `TokenBudgetExhaustedException`, `IterationCapExceededException`, `StructuredOutputException` — all `extends RuntimeException` |
| 4 | AiParametersBody validates YAML write path via Jakarta Bean Validation | ✓ VERIFIED | `@NotBlank`/`@DecimalMin`/`@DecimalMax` on fields; mapper calls `validator.validate(body)` on every readValue/writeAsYaml |
| 5 | AiAgentGuardProperties binds jmix.ai-agent.guard.* with 4 nested records | ✓ VERIFIED | `@ConfigurationProperties("jmix.ai-agent.guard")` with RateLimit/TokenBreaker/IterationCap/OutputScanner + resolvedX() accessors |
| 6 | All ai-agent.guard.* + ai-agent.parameters.* keys in both locale files | ✓ VERIFIED | 11 keys in each file (exact parity); I18nParityTest green |

**Plan 02 parameters (6 truths):**

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 7 | Single default AiParameters row seeded on first boot | ✓ VERIFIED | `DefaultParamsSeeder` @EventListener(ApplicationReadyEvent) + idempotent `select count(e) from ai_AiParameters e` guard; `DefaultParamsSeederTest.seedsOnceWhenEmpty` + `secondInvocationIsNoOp` green |
| 8 | ParametersService.setActive enforces exactly-one-active atomically | ✓ VERIFIED | `@Transactional(propagation=REQUIRED)` + flip-all-then-set-target ordering (line 94–107) + @Version on AiParameters for optimistic locking; `concurrentSetActiveResolvesToOneWinner` green |
| 9 | Write with unknown YAML key fails with ParametersValidationException | ✓ VERIFIED | Explicit UnrecognizedPropertyException catch emits "ai-agent.parameters.yaml.unknown-key"; `ParametersServiceTest.saveRejectsUnknownKey` green |
| 10 | AiParametersResolver.effectiveModel(AiParameters, Overrides) overload | ✓ VERIFIED | Method at line 123 with slug validation + fallback to no-override path |
| 11 | effectiveSystemPrompt(AiParameters, userId, convId, runId) runs contributor chain | ✓ VERIFIED | Method at line 183; Comparator.comparingInt getOrder; try/catch per-contributor; log-and-skip |
| 12 | AiParametersBodyYamlMapper round-trips with FAIL_ON_UNKNOWN_PROPERTIES | ✓ VERIFIED | `FAIL_ON_UNKNOWN_PROPERTIES` enabled; two-catch form (UnrecognizedPropertyException, IOException); validator invoked |

**Plan 03 guards (8 truths):**

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 13 | RateLimitGuard.check throws RateLimitExceededException at ceiling | ✓ VERIFIED | Cache "ai-agent.rateLimit", sliding-window Deque, throws on size >= ceiling; RateLimitGuardTest 5 methods green |
| 14 | TokenBudgetGuard.check throws + accumulate tracks usage | ✓ VERIFIED | Cache "ai-agent.tokenBreaker"; synchronized methods; TokenBudgetGuardTest 6 methods including concurrent-accumulate green |
| 15 | GuardedToolCallingManager enforces iteration cap | ✓ VERIFIED | Strict `next > cap` at line 99; IterationCapExceededException audited with __chat__ |
| 16 | Pre-tool-call ToolGuard.check + audit with REAL tool name | ✓ VERIFIED | Line 116 calls `toolGuard.check(toolName, argumentsMap)`; BLOCKED outcome + real tool name |
| 17 | OutputScannerAdvisor writes flagged-pattern KEY into context | ✓ VERIFIED | `CONTEXT_KEY_FLAGGED_PATTERN = "outputScanner.flaggedPatternKey"`; pattern KEY not matched text; 8 KiB substring cap (line 99); OutputScannerAdvisorTest 8 methods green |
| 18 | AiAgentGuardAutoConfiguration supplies defaults (CacheManager + optional SOVA + scanner) | ✓ VERIFIED | 4 @Bean methods with @ConditionalOnMissingBean; registered in AutoConfiguration.imports |
| 19 | spring-boot-starter-cache + jackson-dataformat-yaml deps declared | ✓ VERIFIED | build.gradle contains both (BOM-versioned); compile passes |
| 20 | Ordered.HIGHEST_PRECEDENCE + 400 for OutputScannerAdvisor | ✓ VERIFIED | `getOrder()` returns `Ordered.HIGHEST_PRECEDENCE + 400` |

**Plan 04 orchestration (3 truths):**

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 21 | ChatService has 4 methods (ask, ask+Overrides, askTyped, askTyped+Overrides) | ✓ VERIFIED | Interface lines 38/50/71/78; imports Overrides |
| 22 | DefaultChatServiceImpl guard preamble + scanner promotion + typed-exception→denied mapper | ✓ VERIFIED | rateLimitGuard.check/tokenBudgetGuard.check/accumulate, IterationCounter.start/reset, ChatResponseDto.denied at 4+ sites, chatClientResponse(), AiToolCallOutcome.FLAGGED |
| 23 | ChatClientFactory wires OutputScannerAdvisor as LAST advisor + ToolCallingManager injected | ✓ VERIFIED | `.defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor, outputScannerAdvisor)` — scanner last; @Primary `guardedToolCallingManager` auto-picked |

**Plan 05 tests (implicit — all E-01..E-12 mapped):**

| Rubric | Test | Status |
|--------|------|--------|
| E-01 | RateLimitGuardTest (5 methods) | ✓ PASS |
| E-02 | TokenBudgetGuardTest (6 methods incl. concurrent) | ✓ PASS |
| E-03 | GuardedToolCallingManagerTest iteration-cap fixture | ✓ PASS |
| E-04 | GuardedToolCallingManagerTest toolVetoIsAuditedWithRealToolName | ✓ PASS |
| E-05 | OutputScannerAdvisorTest corpus-driven (8 methods) | ✓ PASS |
| E-06/E-07 | AskTypedRetryTest (8 methods, fixture-driven) | ✓ PASS |
| E-08 | Consolidated into AskTypedRetryTest (documented in 06-05-SUMMARY, Rule 3) | ✓ PASS |
| E-09 | ParametersServiceTest.concurrentSetActiveResolvesToOneWinner | ✓ PASS |
| E-10 | AiAgentGuardAutoConfigurationBootTest (2 methods) | ✓ PASS |
| E-11 | I18nParityTest (2 methods) | ✓ PASS |
| E-12 | DefaultParamsSeederTest (4 methods) | ✓ PASS |

**Score:** 23/23 truths verified + all 12 eval rubrics covered by passing tests (50 tests across 9 classes).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `com/vn/agent/parameters/AiParametersBody.java` | Validated YAML DTO | ✓ VERIFIED | 8 fields, JsonPropertyOrder, Bean Validation annotations |
| `com/vn/agent/parameters/Overrides.java` | Record(String model) + NONE | ✓ VERIFIED | Exact shape; NONE constant |
| `com/vn/agent/parameters/ParametersValidationException.java` | RuntimeException | ✓ VERIFIED | Two constructors, matching ToolVetoedException shape |
| `com/vn/agent/parameters/AiParametersBodyYamlMapper.java` | Strict-on-write YAML mapper | ✓ VERIFIED | FAIL_ON_UNKNOWN_PROPERTIES + two-catch + validator |
| `com/vn/agent/parameters/ParametersService.java` | CRUD + setActive invariant | ✓ VERIFIED | @Transactional REQUIRED setActive with flip-then-set |
| `com/vn/agent/parameters/DefaultParamsSeeder.java` | Idempotent seeder | ✓ VERIFIED | ApplicationReadyEvent + runWithSystem + count probe |
| `com/vn/agent/guard/AiAgentGuardProperties.java` | @ConfigurationProperties with 4 nested records | ✓ VERIFIED | D-12/13/14/16/18 defaults; bounded ROLE_BREAK; 8 KiB contract Javadoc |
| `com/vn/agent/guard/RateLimitGuard.java` | Per-minute deque per user | ✓ VERIFIED | "ai-agent.rateLimit" cache, throws at ceiling |
| `com/vn/agent/guard/TokenBudgetGuard.java` | Per-conversation accumulator | ✓ VERIFIED | "ai-agent.tokenBreaker" cache, synchronized check/accumulate/reset |
| `com/vn/agent/guard/IterationCounter.java` | ThreadLocal<Integer> with start/increment/reset | ✓ VERIFIED | Static utility, matches RunContext discipline |
| `com/vn/agent/guard/CompiledOutputScannerPattern.java` | (key, Pattern) tuple | ✓ VERIFIED | Record + from() factory |
| `com/vn/agent/guard/GuardedToolCallingManager.java` | ToolCallingManager decorator | ✓ VERIFIED | Implements all 3 methods of Spring AI 1.1.4 interface; CHAT_SENTINEL_TOOL_NAME; strict > cap |
| `com/vn/agent/guard/OutputScannerAdvisor.java` | CallAdvisor at HP+400 | ✓ VERIFIED | Compiled patterns; 8 KiB cap (line 99); first-match-wins |
| Four guard exceptions | RuntimeException each | ✓ VERIFIED | All exist with constructor variants |
| `orchestration/AiParametersResolver.java` | 2 new overloads (Overrides + RunContext) | ✓ VERIFIED | Both overloads present; constructor takes List<PromptContextContributor> |
| `ChatService.java` | 4 methods total | ✓ VERIFIED | Interface has ask, ask+Overrides, askTyped, askTyped+Overrides |
| `DefaultChatServiceImpl.java` | Guards + scanner + askTyped retry + mapper | ✓ VERIFIED | All wiring present; maxAttempts=2; narrow JsonProcessingException catch |
| `orchestration/ChatClientFactory.java` | OutputScannerAdvisor last + ToolCallingManager wired | ✓ VERIFIED | .defaultAdvisors line includes scanner last |
| `ai-agent-starter/default-params.yaml` | Bundled defaults | ✓ VERIFIED | 5 core + 3 null fields |
| `AiAgentGuardAutoConfiguration.java` | @ConditionalOnMissingBean fallbacks | ✓ VERIFIED | BeanFactory lookup for ToolCallingManager delegate (auto-fix documented) |
| AutoConfiguration.imports | AiAgentGuardAutoConfiguration registered | ✓ VERIFIED | Last line of imports file |
| 4 eval YAML fixtures | Corpus-driven | ✓ VERIFIED | All 4 present under src/test/resources/eval/ |
| 9 test files (spread across modules) | @Tag("eval") suite | ✓ VERIFIED | 50 tests, 0 failures, 0 errors (48 + 2 starter) |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| ChatResponseDto | GuardDenialInfo | nested record + denied() factory | ✓ WIRED |
| messages_vi.properties | messages.properties | key parity (11 + FLAGGED) | ✓ WIRED |
| DefaultParamsSeeder | AiParametersBodyYamlMapper | yamlMapper.readValue(InputStream) | ✓ WIRED |
| ParametersService.save | AiParametersBodyYamlMapper | readValue + writeAsYaml | ✓ WIRED |
| AiParametersResolver.effectiveSystemPrompt | List<PromptContextContributor> | ordered fragment() concat with try/catch | ✓ WIRED |
| GuardedToolCallingManager | ToolGuard.check | pre-tool-call veto hook (line 116) | ✓ WIRED |
| GuardedToolCallingManager | AuditWriter.writeToolCall | BLOCKED + tool-name convention | ✓ WIRED |
| OutputScannerAdvisor | ChatClientResponse.context() | put("outputScanner.flaggedPatternKey", KEY) | ✓ WIRED |
| DefaultChatServiceImpl.ask | RateLimitGuard + TokenBudgetGuard | preamble check + post-response accumulate | ✓ WIRED |
| DefaultChatServiceImpl.ask | ChatClientResponse.context() | reads "outputScanner.flaggedPatternKey" into DTO | ✓ WIRED |
| DefaultChatServiceImpl.askTyped | BeanOutputConverter | getFormat() re-injected on retry | ✓ WIRED |
| ChatClientFactory | OutputScannerAdvisor + GuardedToolCallingManager | defaultAdvisors last + @Primary pickup via BeanFactory | ✓ WIRED |
| AiAgentGuardAutoConfiguration | StructuredOutputValidationAdvisor | @ConditionalOnClass + reflective ctor (D-21 inverted: class IS present in 1.1.4) | ✓ WIRED |

### Data-Flow Trace (Level 4)

Not applicable in the classic UI sense — Phase 6 is pure backend orchestration. Dataflow verified via behavioural tests: DefaultParamsSeeder → AiParameters table (integration test asserts row count 1); RateLimitGuard deque → ceiling comparison (unit test); OutputScannerAdvisor → response.context() → DTO.flagged (corpus-driven test); askTyped loop → model replies → parsed bean (fixture-driven test with 4 scenarios).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| evalTest passes | `./gradlew :ai-agent:ai-agent:evalTest` | BUILD SUCCESSFUL, 48 tests / 0 failures | ✓ PASS |
| starter boot test passes | (included in evalTest build) | 2 tests / 0 failures | ✓ PASS |
| AutoConfig.imports contains entry | grep AutoConfiguration.imports | 4 entries incl. AiAgentGuardAutoConfiguration | ✓ PASS |
| i18n parity counts match | grep -c ^ai-agent.\\(guard\\|parameters\\). in both locale files | 11 + 11 (matching) | ✓ PASS |
| evalTest excluded from default test | build.gradle | `excludeTags 'eval'` on main test task | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| PARAM-01 | 01, 02, 04 | Profile entity + one-active | ✓ SATISFIED | ParametersService.setActive + AiParameters @Version |
| PARAM-02 | 01, 02 | Profile fields: model/temp/maxTokens/systemPrompt/enabledTools/ragTopK/ragSimilarity | ✓ SATISFIED | AiParametersBody 8 fields |
| PARAM-03 | 02 | Per-conversation override | ✓ SATISFIED | ChatService.ask(+Overrides); AiParametersResolver.effectiveModel(AiParameters, Overrides) |
| PARAM-04 | 02 | default-params.yaml seed on first boot | ✓ SATISFIED | DefaultParamsSeeder + default-params.yaml; DefaultParamsSeederTest |
| PARAM-05 | 02, 04 | PromptContextContributor chain | ✓ SATISFIED | effectiveSystemPrompt(RunContext) overload invokes ordered contributors |
| GUARD-01 | 01, 03, 04 | ToolGuard veto + audit | ✓ SATISFIED | GuardedToolCallingManager pre-check + audit real tool name (note: GUARD-01 is rate-limit in REQUIREMENTS.md; the plans map ToolGuard to GUARD-01 while REQUIREMENTS maps it differently — both aspects covered) |
| GUARD-02 | 01, 03, 04 | Iteration cap (default 6) | ✓ SATISFIED | GuardedToolCallingManager strict > cap |
| GUARD-03 | 01, 03, 04 | Token circuit breaker | ✓ SATISFIED | TokenBudgetGuard check + accumulate |
| GUARD-04 | 01, 03, 04 | Per-user rate limit (10/min) | ✓ SATISFIED | RateLimitGuard sliding-window deque |
| GUARD-05 | 01, 03, 04 | Output injection scanner | ✓ SATISFIED | OutputScannerAdvisor at HP+400 + FLAGGED audit |
| GUARD-06 | 01, 03, 04 | Structured output + retry | ✓ SATISFIED | askTyped maxAttempts=2 + StructuredOutputException |
| SPI-05 (impl) | 02, 03, 04 | ToolGuard wiring + PromptContextContributor chain | ✓ SATISFIED | Both surfaces wired |

All declared plan requirements accounted for. No orphaned requirements in REQUIREMENTS.md.

### Anti-Patterns Found

None. Scan on all modified files returned only:
- Intentional `return null` in reflective `structuredOutputValidationAdvisor()` (documented: private-ctor fallback so @ConditionalOnClass-guarded bean degrades silently — D-21 safety)
- Deliberate `Map.of()` empty params in `ChatResponseDto.denied(...)` call sites (D-10: no leaking ceilings to users)
- Test-only hardcoded empty data in mock setups

No TODOs, placeholders, or stub implementations in production code.

### Locked CONTEXT Decisions Honoured

| Decision | Expected | Status |
|----------|----------|--------|
| D-06 | setActive single-tx flip-all-then-set-one + @Version | ✓ HONOURED |
| D-10 | Typed exceptions → denied DTO with fixed msgKey (no leaked ceilings) | ✓ HONOURED |
| D-11 | __chat__ sentinel for request-level denials, real tool name for tool-level | ✓ HONOURED |
| D-12 | ROLE_BREAK bounded `.{0,2048}?` + 8 KiB scanner cap | ✓ HONOURED |
| D-13/14/16/18 | Default 10 req/min / 100k tokens / 6 iterations / 3 bundled regex | ✓ HONOURED |
| D-17 | Scanner flag-and-pass-through with pattern KEY (never matched text) | ✓ HONOURED |
| D-19 | askTyped maxAttempts = 2 | ✓ HONOURED |
| D-21 | StructuredOutputValidationAdvisor reflective wiring — NOTE: class IS present in 1.1.4 (inverted from Plan 06-03 assumption); inspected via jar + test now asserts presence; reflective path returns null if private ctor blocks instantiation | ✓ HONOURED (documented in 06-03/06-05 SUMMARYs) |

### Known Auto-Fixes Verified as Real (not hand-waves)

1. **GuardedToolCallingManager BeanFactory lookup** (from 06-03 SUMMARY): AiAgentGuardAutoConfiguration line 103–120 resolves the non-self `toolCallingManager` bean by name via `BeanFactory.getBean("toolCallingManager", ToolCallingManager.class)` to avoid @Primary self-reference cycle. ✓ REAL.
2. **OutputScannerAdvisor 8 KiB cap** (from 06-03 SUMMARY): Line 99 `text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text` before `matcher().find()`. ✓ REAL.
3. **askTyped no-BeanOutputParseException wrapping** (from 06-04 SUMMARY): Line 292 `if (!(ex.getCause() instanceof JsonProcessingException)) throw ex;` narrows the catch so RateLimitExceededException thrown from inner ask() propagates. ✓ REAL.
4. **E-08 consolidation into AskTypedRetryTest** (from 06-05 SUMMARY): Rule 3 deviation; file `StructuredOutputValidationAdvisorDegradationTest.java` never created; AskTypedRetryTest lines 206+ carry the E-08 assertions. ✓ REAL (documented deviation, test present).

### Human Verification Required

None. All Phase 6 deliverables are backend-only (no UI, no real-time behaviour, no visual appearance). Eval rubrics E-01..E-12 exercise every observable behaviour via automated tests. Phase 7 will add UI-side human-verifiable flows.

### Gaps Summary

**None.** All 23 plan must_haves verified, all 12 requirement IDs satisfied, all 4 ROADMAP success criteria met with passing tests, all 6 locked CONTEXT decisions honoured, all 3 documented auto-fixes verified as real code (not hand-waves), and all 4 eval YAML fixtures present with corresponding corpus-driven tests. Baseline noise (28 pre-existing @SpringBootTest failures from eclipselink StandardQueryCache) excluded per phase guidance — Phase 6 intentionally uses ApplicationContextRunner and fine-grained @SpringBootTest scoping to bypass that noise and all 50 Phase 6 NEW tests pass green.

---

*Verified: 2026-04-21T05:15:00Z*
*Verifier: Claude (gsd-verifier)*
