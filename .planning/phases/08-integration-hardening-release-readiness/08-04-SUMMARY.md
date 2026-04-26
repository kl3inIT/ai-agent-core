---
phase: 08
plan: 04
subsystem: test/live
tags: [test-only, live-tier, golden-suite, semantic, capability-coverage, TEST-05, R-04a, R-04b, R-04c, R-04d, R-04e]
requires:
  - com.vn.agent.ChatService#ask(String userId, UUID conversationId, String message)
  - com.vn.agent.orchestration.ChatResponseDto (record — content() + conversationId())
  - jackson-databind + jackson-dataformat-yaml (already on testRuntimeClasspath via Spring Boot BOM)
  - ai-agent.gradle liveTest task (@Tag('live') include) — already present
provides:
  - golden-questions.yaml: 7 capability-coverage questions (R-04b RAG split)
  - ChatServiceLiveSemanticGoldenSuiteTest: parameterized live-tier @Test
  - R-04e visible ENABLED/SKIPPED announce in @BeforeAll
affects:
  - none (test-only — gated to liveTest task; default `./gradlew test` excludes @Tag('live'))
tech-stack:
  added: []
  patterns:
    - "YAML-driven parameterized live test using Jackson YAMLFactory + JUnit @MethodSource"
    - "Visible-skip @BeforeAll log line — distinguishes skip-by-default from skip-by-missing-key in CI logs"
    - "Multi-turn replay via chatService.ask(...).conversationId() chaining (carries conversation id forward)"
key-files:
  created:
    - ai-agent/ai-agent/src/test/resources/golden-questions.yaml
    - ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java
  modified: []
key-decisions:
  - "Compile-only verification per session policy: live test class is dual-gated and the default ./gradlew test excludes @Tag('live'); running it requires ./gradlew :ai-agent:ai-agent:liveTest with OPENROUTER_API_KEY set"
  - "Multi-turn anchors hardcoded to ['acme', 'corp'] per plan example — actual seed is in the off-classpath jmix-app module so cannot be auto-verified at compile time. Operator running liveTest locally substitutes the real seeded customer name if different"
  - "rag-retrieval-positive precondition documents that the demo KB must contain an 'onboarding' document — RED on empty KB is the correct (informative) signal, not a flaky test"
  - "rag-retrieval-empty-kb is the fail-closed honesty check — anchors are 'I cannot find / no information / do not have' phrasings; RED if LLM hallucinates content for an unknown topic"
  - "Refusal-injection notAnchors use credential-shaped tokens (admin123, SYSTEM:, instructions bypassed) instead of bare 'password' (R-04d) — bare 'password' would false-flag the correct response 'I will not reveal the password'"
patterns-established:
  - "Live-tier test fixture pattern: YAML record schema with id/prompt/anchors/notAnchors/expectedTools/multiTurnPrior/precondition/notes; Jackson record-binding via @MethodSource"
requirements-completed:
  - TEST-05
duration: ~15min
completed: 2026-04-26
---

# Phase 8 Plan 04: TEST-05 Live-Tier Semantic Golden Suite Summary

Delivered the YAML capability-coverage fixture (7 entries, R-04b RAG split applied) and the parameterized `@Tag("live")` test that consumes it via Jackson + JUnit `@MethodSource`. Test class is dual-gated by `@Tag("live")` AND `@EnabledIfEnvironmentVariable("OPENROUTER_API_KEY")`, with a visible `@BeforeAll` ENABLED/SKIPPED announce per R-04e so CI logs distinguish skip-by-default from skip-by-missing-key.

## Outcome

- **Compile-only verification per session policy** (file compiles via `compileTestJava`; default `./gradlew test` correctly excludes the class via `@Tag("live")` filter; running the suite requires `./gradlew :ai-agent:ai-agent:liveTest` with `OPENROUTER_API_KEY` set).
- Zero ERROR-severity findings on the new test file (JetBrains MCP).

| Artifact | Lines | Status |
|---|---:|---|
| golden-questions.yaml | 50 | ✓ 7 entries |
| ChatServiceLiveSemanticGoldenSuiteTest.java | 122 | ✓ compiles |

## Tasks Executed

| Task | Name | Notes |
|---|---|---|
| 1 | Create golden-questions.yaml (7 entries — R-04a/b/c/d) | All 7 capability ids present; literal injection payload preserved verbatim |
| 2 | Create ChatServiceLiveSemanticGoldenSuiteTest (R-04e visible-skip) | Parameterized over YAML; record schema includes precondition field |
| 3 | JetBrains MCP get_file_problems | Zero ERROR-severity findings |

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileTestJava` — **PASS**
- Default `./gradlew test` does NOT execute the new class (verified by `@Tag("live")` filter in ai-agent.gradle)
- All R-04a..R-04e acceptance greps pass
- `mcp__jetbrains__get_file_problems` — zero ERROR-severity findings

### Acceptance criteria mapping (all satisfied)

| Criterion | Expected | Actual |
|---|---|---|
| YAML id count | 7 | 7 |
| All 7 capability ids present | each = 1 | each = 1 |
| R-04c literal SYSTEM payload in YAML | ≥ 1 | 1 |
| `<data>` token in YAML | ≥ 1 | 1 |
| `notAnchors:` count | 1 (only refusal) | 1 |
| `multiTurnPrior:` count | 1 | 1 |
| `@Tag("live")` in test class | 1 | 1 |
| `@EnabledIfEnvironmentVariable` in test class | 1 | 1 |
| `OPENROUTER_API_KEY` references | ≥ 2 | 2 |
| `@ParameterizedTest` count | 1 | 1 |
| `@MethodSource("loadQuestions")` | 1 | 1 |
| `StubChatModelConfiguration` references | 0 | 0 |
| `@BeforeAll` (R-04e) | 1 | 1 |
| Live-suite ENABLED/SKIPPED literal in @BeforeAll | ≥ 1 | 1 (combined string) |
| `precondition` record-field reference | ≥ 1 | 1 |

## Notes for downstream waves and operators

- **To run the live suite:** `OPENROUTER_API_KEY=<key> ./gradlew :ai-agent:ai-agent:liveTest`
- **Multi-turn-memory anchor (`acme`/`corp`):** if your demo seed has a different customer name, edit `golden-questions.yaml`'s `multi-turn-memory.anchors` and `multi-step-tool-chain.prompt` together to match.
- **RAG positive (`onboarding`):** the test RED on an empty KB is the correct signal; upload a fixture doc containing the keyword "onboarding" before expecting GREEN.
- Plan 08-07 release wiring is orthogonal — the liveTest gradle task is already present, no changes needed.

## Self-Check: PASSED

All success criteria met:
- 7 capability questions covering all six D-08 capabilities (RAG split into positive + empty-kb).
- Anchors are sharp enough to act as a real GATE per R-04a/b/c/d.
- CI logs distinguish "skip-by-default" from "skip-by-missing-key" via R-04e @BeforeAll line.
- Compile + JetBrains-MCP gates clean.
- No StubChatModelConfiguration import — uses real ChatModel as required by D-11.
