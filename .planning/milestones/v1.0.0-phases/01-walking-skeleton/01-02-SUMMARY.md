---
phase: 01-walking-skeleton
plan: 02
subsystem: agent-spi
tags: [spring-ai, chat-service, auto-configuration, spi, tdd]

requires:
  - 01-01 (Spring AI BOM 1.0.2 on classpath, spring-ai-client-chat on functional module)
provides:
  - Stable com.vn.agent.ChatService SPI with D-03 signature: ChatResponse ask(String, UUID, String)
  - com.vn.agent.ChatResponse record (content + metadata) with null-guard compact ctor
  - com.vn.agent.DefaultChatServiceImpl @Service (component-scanned by AIConfiguration)
  - AIAutoConfiguration @Bean ChatClient via @ConditionalOnMissingBean (host-overridable)
affects: [01-03, 01-04, all downstream ChatService consumers]

tech-stack:
  added: []
  patterns:
    - "Component-scanned @Service in functional module + @ConditionalOnMissingBean @Bean in starter — keeps single-impl case simple; promote to @Bean when second impl arrives"
    - "Constructor injection of ChatClient.Builder, build() in ctor — aligns with CLAUDE.md (no field injection) and RESEARCH Pitfall 4 (no @PostConstruct)"
    - "Mock ChatClient.Builder in AITestConfiguration — isolates functional module tests from OpenAI starter auto-config"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/ChatResponseTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplTest.java
  modified:
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java

key-decisions:
  - "ChatService lives in functional module (com.vn.agent), not starter — matches existing AIConfiguration @ComponentScan, keeps SPI decoupled from auto-config"
  - "DefaultChatServiceImpl is @Service (not @Bean in AIAutoConfiguration) — component scan already covers com.vn.agent; @Bean + @ConditionalOnMissingBean will be introduced when a second impl arrives"
  - "ChatClient @Bean goes in AIAutoConfiguration with @ConditionalOnMissingBean — gives hosts the override seam without requiring a @ConditionalOnBean(Builder) guard in Phase 1"
  - "Mock ChatClient.Builder in AITestConfiguration instead of pulling spring-ai-starter-model-openai into functional module test scope — keeps functional module lean and avoids live-key tests in default CI"

requirements-completed:
  - PKG-02
  - PKG-03

duration: ~25min
completed: 2026-04-18
---

# Phase 01 / Plan 02: ChatService SPI + DefaultChatServiceImpl + ChatClient Bean — Summary

**The minimum Java surface needed to inject `ChatService` in `jmix-app` end-to-end is live: public SPI + record DTO in the functional module, component-scanned default impl, and a host-overridable `ChatClient` bean in the auto-configuration.**

## Performance

- **Started:** 2026-04-18 ~21:25 ICT
- **Completed:** 2026-04-18 ~21:32 ICT
- **Duration:** ~25 min
- **Tasks:** 3 planned + 1 Rule-3 blocker fix
- **Commits:** 5 (2 RED test commits, 2 GREEN feat commits, 1 combined Task 3 + deviation)

## Accomplishments

1. **Task 1 — `ChatService` + `ChatResponse`.** Created `com.vn.agent.ChatService` interface with the D-03 signature `ChatResponse ask(String message, UUID conversationId, String userKey)` and `com.vn.agent.ChatResponse` record with null-guard compact constructor. RED test `ChatResponseTest` locks the null-guard contract; 4 tests green.
2. **Task 2 — `DefaultChatServiceImpl`.** `@Service` in `com.vn.agent`, picked up by existing `AIConfiguration @ComponentScan`. Constructor-injects `ChatClient.Builder`, calls `.build()` once into a `private final ChatClient`, and implements `ask()` with `chatClient.prompt().user(message).call().content()` — exactly the fluent chain from the plan. Null content from the client is coerced to `""`; conversationId echoed in metadata under key `"conversationId"`. RED test `DefaultChatServiceImplTest` mocks the full Spring AI fluent chain (Builder → ChatClient → ChatClientRequestSpec → CallResponseSpec); 2 tests green.
3. **Task 3 — `AIAutoConfiguration` ChatClient bean.** Added `@Bean @ConditionalOnMissingBean ChatClient chatClient(ChatClient.Builder builder) { return builder.build(); }`. Preserved `@AutoConfiguration` + `@Import({AIConfiguration.class})`. Did NOT add a `@Bean ChatService` (impl is `@Service` elsewhere — avoids bean collision per threat T-01-10). Did NOT add `@ConditionalOnBean(ChatClient.Builder.class)` — Spring resolves @Bean parameters lazily at DI time, and the existing `AITest.contextLoads` now passes without it (see Deviations).

## Deviations

**[Rule 3 — Blocking issue] Mock `ChatClient.Builder` added to `AITestConfiguration`.**

- **Found during:** Task 3 verification (`./gradlew :ai-agent:test` failed after `DefaultChatServiceImpl` became a component-scanned `@Service`).
- **Issue:** The functional module's test context boots `AITestConfiguration` with `@EnableAutoConfiguration`, but only the *starter* module depends on `spring-ai-starter-model-openai` — the auto-configuration that produces the real `ChatClient.Builder`. `DefaultChatServiceImpl`, now scanned into the test context, could not resolve its `ChatClient.Builder` constructor dependency, and `AITest.contextLoads` regressed with `NoSuchBeanDefinitionException: ChatClient$Builder`.
- **Fix:** Added a Mockito-backed `@Bean ChatClient.Builder chatClientBuilder()` to `AITestConfiguration` that returns a stub chain (`build() → ChatClient → prompt().user(...).call().content() → "test"`). This scope is test-only; production wiring is unchanged (host apps depend on `ai-agent-starter`, which pulls the real builder via auto-config).
- **Alternative considered:** Adding `spring-ai-starter-model-openai` to the functional module's test classpath. Rejected — it would couple functional-module tests to the OpenAI starter and risk live-key usage in default CI.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java`
- **Commit:** `36bc61e` (combined with Task 3's production code to keep the fix atomic with the scope change that caused it)

No `@ConditionalOnBean(ChatClient.Builder.class)` was required on the `chatClient` @Bean — the plan's contingency was not triggered.

## Verification

- `./gradlew :ai-agent:compileJava` — **succeeds**
- `./gradlew :ai-agent-starter:compileJava` — **succeeds**
- `./gradlew :ai-agent:test` — **BUILD SUCCESSFUL** (7 tests: `AITest.contextLoads` + 4× `ChatResponseTest` + 2× `DefaultChatServiceImplTest`)
- Constructor injection only (no `@Autowired` fields) — confirmed by inspection
- No Lombok imports introduced — confirmed by inspection
- `AutoConfiguration.imports` unchanged — confirmed

## Commits

| Commit  | Description                                                                |
| ------- | -------------------------------------------------------------------------- |
| c914aa5 | test(01-02): RED — failing ChatResponse null-guard tests                   |
| 9326540 | feat(01-02): GREEN — ChatService SPI + ChatResponse record                 |
| 26da7e0 | test(01-02): RED — failing DefaultChatServiceImpl test (fluent chain mock) |
| d148a79 | feat(01-02): GREEN — DefaultChatServiceImpl as component-scanned @Service  |
| 36bc61e | feat(01-02): ChatClient @Bean in AIAutoConfiguration + test blocker fix    |

## TDD Gate Compliance

Tasks 1 and 2 followed the RED/GREEN cycle (test commit → feat commit). REFACTOR was not needed — implementations were minimal. Task 3 was non-TDD (plan spec `type="auto"` without `tdd="true"`); verified by existing `AITest.contextLoads` plus the `DefaultChatServiceImplTest` from Task 2.

## Downstream Impact

- Plan 01-03 can now inject `@Autowired ChatService` in `jmix-app` and exercise the blocking ask path against OpenRouter (via the real `ChatClient` @Bean).
- Plan 01-04 consumer smoke procedure can reference the three public classes (`ChatService`, `ChatResponse`, `DefaultChatServiceImpl`) as the add-on's Phase 1 SPI footprint.
- Future phases (memory, advisors, tools) swap `DefaultChatServiceImpl` by removing its `@Service`, promoting to a `@Bean` in `AIAutoConfiguration` with `@ConditionalOnMissingBean` — no contract change for host code.

## Self-Check: PASSED

- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — FOUND
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — FOUND (modified)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java` — FOUND (modified)
- Commits c914aa5, 9326540, 26da7e0, d148a79, 36bc61e — all FOUND in `git log`
- `./gradlew :ai-agent:compileJava :ai-agent-starter:compileJava :ai-agent:test` — PASS
