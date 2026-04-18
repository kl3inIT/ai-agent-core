---
phase: 01-walking-skeleton
plan: 03
subsystem: testing
tags: [testing, mockito, spring-ai, live-smoke, openrouter, junit5]

requires:
  - 01-01 (Spring AI BOM 1.0.2, liveTest Gradle task, OpenRouter yaml)
  - 01-02 (ChatService SPI, DefaultChatServiceImpl, ChatClient @Bean, AITestConfiguration stub builder)
provides:
  - ChatServiceMockTest — Mockito-on-ChatModel unit test (complements Builder-layer test from 01-02)
  - ChatServiceLiveTest — @Tag("live") + @EnabledIfEnvironmentVariable OpenRouter smoke
  - spring.ai.openai.* props wired into test-app.properties for live-test context
  - Empirical proof that belt-and-suspenders skip works (Gradle excludeTags AND annotation guard)
affects: [01-04, all future test-layer plans]

tech-stack:
  added: []
  patterns:
    - "Mockito.mock(ChatModel.class) + ChatClient.builder(model) — exercises real ChatClient fluent chain against fake transport; deeper than Builder-layer mocks"
    - "Belt-and-suspenders live-test skip: Gradle excludeTags 'live' (CI) + @EnabledIfEnvironmentVariable (manual runs) — either alone suffices; both make leakage a three-mistake chain"
    - "Env-var-backed test-app.properties: spring.ai.openai.api-key=${OPENROUTER_API_KEY:none} — defaults keep tests inert when key absent"

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java
  modified:
    - ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties

key-decisions:
  - "Kept existing DefaultChatServiceImplTest (from 01-02) — it mocks at the ChatClient.Builder layer and was already green. ChatServiceMockTest adds a ChatModel-layer mock per plan 01-03's explicit acceptance criterion (grep Mockito.mock(ChatModel.class) >= 1). The two tests are complementary, not duplicate — one asserts wrapping behavior against a mocked Builder, the other asserts end-to-end flow through the real ChatClient against a mocked transport."
  - "Used primary-path Mockito.mock(ChatModel.class) — Assumption A1 spike for spring-ai-test MockChatModel was unnecessary: the Spring AI 1.0.2 mock path compiled and passed on first run. No library surface check needed."
  - "test-app.properties appended (not rewritten) — preserved existing main.liquibase.change-log line. Spring AI OpenAI properties live alongside Jmix liquibase config in the same file."

patterns-established:
  - "Three-tier test discipline per D-04: unit (Mockito, no context) → integration (@SpringBootTest, no @Tag) → live (@SpringBootTest + @Tag('live') + env guard). Phase 1 scaffolds tiers 1 and 3; tier 2 inherits AITest.contextLoads."

requirements-completed:
  - TEST-01

duration: ~20min
completed: 2026-04-18
---

# Phase 01 / Plan 03: ChatService Smoke Tests (Mockito + Live) — Summary

**Two test classes now guard ChatService correctness at two levels: a Mockito unit test that stubs the real ChatModel transport and exercises the full ChatClient fluent chain, and a @Tag("live") SpringBootTest that calls OpenRouter end-to-end — firewalled from CI by both Gradle tag exclusion and an env-var annotation guard.**

## Performance

- **Started:** 2026-04-18 ~21:30 ICT
- **Completed:** 2026-04-18 ~21:35 ICT
- **Duration:** ~20 min
- **Tasks:** 2 (both completed atomically)
- **Commits:** 2

## Accomplishments

1. **Task 1 — `ChatServiceMockTest`.** Created `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` with two tests: (a) happy path — `Mockito.mock(ChatModel.class)` returns a canned `ChatResponse(Generation(AssistantMessage("hello from mock")))`, and `DefaultChatServiceImpl.ask(...)` wraps it into our `ChatResponse` DTO with `conversationId` in metadata; (b) null-userKey path per D-03. `ChatClient.builder(mockModel)` constructs a real `ChatClient.Builder` bound to the mock, so the full Spring AI fluent chain (`prompt().user().call().content()`) runs against fake transport. No `@SpringBootTest`; runs in ~100ms.

2. **Task 2 — `ChatServiceLiveTest` + test-app.properties.** Created `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java` — `@SpringBootTest @Tag("live") @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")` — auto-wires the real `ChatService` bean and calls OpenRouter with `"Reply with exactly the word OK."`, asserting only non-blank content. Appended `spring.ai.openai.api-key`, `base-url` (`https://openrouter.ai/api/v1`), and `chat.options.model` props to `test-app.properties` with env-var defaults so the test is inert when `OPENROUTER_API_KEY` is unset.

## Plan Assumption Outcomes

- **A1 (spring-ai-test MockChatModel spike):** Not exercised. The primary Mockito path (`Mockito.mock(ChatModel.class)` stubbing `call(Prompt)` to return a `ChatResponse(List.of(new Generation(new AssistantMessage(...))))`) compiled and passed on first run. No need to inspect `spring-ai-test` for a library-provided mock. Spring AI 1.0.2 types used:
  - `org.springframework.ai.chat.client.ChatClient` and `ChatClient.Builder`
  - `org.springframework.ai.chat.model.ChatModel`
  - `org.springframework.ai.chat.model.ChatResponse` (Spring AI's — name-collides with our DTO, referenced via fully-qualified name in the test)
  - `org.springframework.ai.chat.model.Generation`
  - `org.springframework.ai.chat.messages.AssistantMessage`
  - `org.springframework.ai.chat.prompt.Prompt`

- **A2 (OpenRouter base-url empirical check):** **Not empirically validated in this plan — no `OPENROUTER_API_KEY` available in the executor environment.** The default `https://openrouter.ai/api/v1` from RESEARCH is in place. Plan 04 (consumer smoke) or first manual `./gradlew :ai-agent:liveTest` run with a real key will validate; if 404, flip to `/api` and document in Plan 04's version matrix. Belt-and-suspenders guard was verified instead (see Verification).

## Scope Reduction vs Plan (Informational, Not a Deviation)

The plan's behavior text for Task 1 mentions that "if the Mockito-stubbed `ChatModel.call` path proves fragile … the test uses `spring-ai-test`'s `MockChatModel` IF that class exists." The primary path was not fragile — no fallback needed. Additionally, plan 01-02 already shipped `DefaultChatServiceImplTest`, which mocks at the `ChatClient.Builder` layer. Rather than remove that test, this plan adds `ChatServiceMockTest` at the `ChatModel` layer — the two files are complementary per the plan's explicit acceptance criterion that requires `grep Mockito.mock(ChatModel.class) >= 1` in the new test.

## Deviations from Plan

None. Plan executed exactly as written; both tasks atomic, no auto-fixes triggered, no Rule-4 architectural stops.

## Verification

- `./gradlew :ai-agent:test --tests "com.vn.agent.ChatServiceMockTest"` → **BUILD SUCCESSFUL in 20s**, 2 tests green
- `./gradlew :ai-agent:test` (full default suite) → **BUILD SUCCESSFUL in 31s** — test-results dir contains `AITest`, `ChatResponseTest`, `ChatServiceMockTest`, `DefaultChatServiceImplTest`. Notably absent: `ChatServiceLiveTest` (Gradle `excludeTags 'live'` filter working as designed — Plan 01 Task 2 gate).
- `./gradlew :ai-agent:liveTest` (no `OPENROUTER_API_KEY` set) → **BUILD SUCCESSFUL in 3s**. `@EnabledIfEnvironmentVariable` disables the class; zero failures, no OpenRouter network call. **Belt-and-suspenders (Pitfall 3) confirmed working:** default `test` excludes via tag, `liveTest` task exists and is runnable, annotation guard skips without a key.
- Acceptance criteria (from plan 01-03):
  - `Mockito.mock(ChatModel.class)` in `ChatServiceMockTest` — PASS (grep count = 2 — once per test method)
  - No `@SpringBootTest` or `@Tag("live")` in `ChatServiceMockTest` — PASS
  - `@Tag("live")` + `@EnabledIfEnvironmentVariable(…"OPENROUTER_API_KEY"…matches = ".+")` + `@SpringBootTest` in `ChatServiceLiveTest` — PASS (all 3 annotations, count = 1 each)
  - Default test report excludes `ChatServiceLiveTest` — PASS (not present in `build/test-results/test/`)
  - `liveTest` task exists and cleanly skips without key — PASS
  - `test-app.properties` contains `spring.ai.openai.api-key` binding — PASS

## Final State of test-app.properties

```properties
main.liquibase.change-log=com/vn/agent/liquibase/changelog.xml

# OpenRouter live-test wiring (consumed by ChatServiceLiveTest).
# Default ./gradlew :ai-agent:test excludes @Tag("live") so these are inert for CI.
# For manual ./gradlew :ai-agent:liveTest runs, set OPENROUTER_API_KEY in env;
# if unset, @EnabledIfEnvironmentVariable skips the test before these props are used.
spring.ai.openai.api-key=${OPENROUTER_API_KEY:none}
spring.ai.openai.base-url=${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}
spring.ai.openai.chat.options.model=${OPENROUTER_MODEL:openai/gpt-4o-mini}
```

## Commits

| Commit  | Description                                                                  |
| ------- | ---------------------------------------------------------------------------- |
| a8034dc | test(01-03): add ChatServiceMockTest mocking ChatModel layer                 |
| 9556a06 | test(01-03): add ChatServiceLiveTest with belt-and-suspenders skip           |

## TDD Gate Compliance

Plan 01-03 frontmatter has `type: execute` (not `type: tdd`), and each task is `tdd="true"` at the task level. However, the production code under test (`DefaultChatServiceImpl`) was delivered by plan 01-02 and is already green; RED/GREEN cycle is degenerate here — the tests assert correctness of pre-existing code and both passed on first run. Commit types (`test(...)`) reflect the test-only nature of the changes. No `feat(...)` commits are needed because no production code was added or modified.

## Downstream Impact

- Plan 01-04 (consumer smoke) can reference both tests: `ChatServiceMockTest` proves the SPI wrapper contract holds against a fake ChatModel; `ChatServiceLiveTest` is the opt-in end-to-end check host apps can invoke before cutting releases.
- `test-app.properties` OpenAI props are reusable for any future live smoke tests in the functional module — no duplication needed.
- First real `liveTest` run with `OPENROUTER_API_KEY` set will empirically validate Assumption A2 (base-url `/api/v1` vs `/api`) and feed Plan 04's version matrix.

## Self-Check: PASSED

- `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java` — FOUND
- `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties` — FOUND (modified, spring.ai.openai.* appended)
- Commit a8034dc — FOUND in `git log`
- Commit 9556a06 — FOUND in `git log`
- `./gradlew :ai-agent:test` — PASS (excludes live test correctly)
- `./gradlew :ai-agent:liveTest` (no API key) — PASS (clean skip)
