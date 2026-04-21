---
slug: live-semantic-test-spring-fail
status: resolved
trigger: "ChatServiceLiveSemanticTest fails during Spring test context startup with java.lang.IllegalStateException at StandardQueryCache.java:52, preventing Phase 6 UAT Test 1 (live OpenRouter smoke) from running."
created: 2026-04-21T13:02:31+07:00
updated: 2026-04-21T13:16:25+07:00
phase: 06-parameters-structured-output-guardrails
related_uat: .planning/phases/06-parameters-structured-output-guardrails/06-UAT.md
---

## Symptoms

- expected: `ChatServiceLiveSemanticTest` boots the Spring test context, calls OpenRouter via `ChatService.ask(...)`, and verifies end-to-end flow with the seeded default `AiParameters` profile.
- actual: Spring test context fails to initialize. Test never reaches OpenRouter.
- error: `java.lang.IllegalStateException at StandardQueryCache.java:52` during test context startup.
- timeline: Surfaced during Phase 6 UAT (2026-04-21). Phase 6 introduced parameters, structured output, guardrails, and seeding. Other unit/integration tests (`:ai-agent:ai-agent:evalTest`, `:ai-agent:ai-agent-starter:evalTest`) pass — only the live smoke context fails.
- reproduction: Run `ChatServiceLiveSemanticTest` (live smoke test) in the `ai-agent-starter` module.
- scope: Blocking Phase 6 UAT Test 1 only. 6/7 tests passed; Test 1 marked `blocked / prior-phase` pending resolution.

## Current Focus

hypothesis: (resolved) A Phase 6 `@Bean` in `AiAgentGuardAutoConfiguration` constructed a `ConcurrentMapCacheManager` with a fixed name list, disabling dynamic cache creation and preventing Jmix's `StandardQueryCache` / `ResourceRoleRepository` from obtaining their expected caches during context refresh.
next_action: (none — fix applied and all test suites green)

## Evidence

- 2026-04-21T13:02:31+07:00: UAT Test 2–7 pass; only live smoke blocked. Non-live evalTest suites pass, isolating failure to the live-test Spring context configuration.
- 2026-04-21T13:05:00+07:00: Reproduced `./gradlew :ai-agent:liveTest --tests "*ChatServiceLiveSemanticTest*"` with `OPENROUTER_API_KEY=dummy-for-reproduction`. Full stack trace confirms `io.jmix.eclipselink.impl.entitycache.StandardQueryCache.init` line 52 throws `Unable to find cache: jmix-eclipselink-query-cache`. Triggering caller chain: `ChatClientFactory.defaultChatClient(…)` → `AiParametersResolver.resolveActive()` → `UnconstrainedDataManagerImpl.load(...)` → `DataStoreFactory.get(...)` instantiates `eclipselink_JpaDataStore` mid-context-refresh, pulling `eclipselink_QueryCache` before the Spring `CacheManager` has the Jmix cache bucket.
- 2026-04-21T13:08:00+07:00: Deferred the bean-time `resolveActive()` call in `ChatClientFactory` (dead code — `DefaultChatServiceImpl.ask` overrides `.defaultSystem(...)` via `chatClient.prompt().system(...)` per request). Live test now exposes a second, deeper cache miss: `IllegalStateException: Unable to find cache: resource-roles-cache` from `sec_ResourceRoleRepository`.
- 2026-04-21T13:09:00+07:00: Inspected `AiAgentGuardAutoConfiguration.aiAgentGuardCacheManager()`: `new ConcurrentMapCacheManager("ai-agent.rateLimit", "ai-agent.tokenBreaker")`. Confirmed Spring Boot's `ConcurrentMapCacheManager(String... cacheNames)` constructor flips the manager into fixed-names mode (`dynamic=false`); any subsequent `getCache(name)` for a name NOT in the constructor list returns `null`. That is exactly what Jmix's `StandardQueryCache` and `ResourceRoleRepository` do at `@PostConstruct` time.
- 2026-04-21T13:10:00+07:00: Applied fix — replace the fixed-names constructor with the no-args `ConcurrentMapCacheManager()` (dynamic mode ON) and pre-touch `ai-agent.rateLimit` / `ai-agent.tokenBreaker` so the guard beans' fail-fast lookups still succeed. Re-ran live test: Spring context boots, Jmix eclipselink + security initialize, `ChatService.ask()` runs. Test assertion fails only because the dummy key path falls back to a stub ChatModel — expected with `OPENROUTER_API_KEY=dummy-for-reproduction`; real-key live run will exercise the full OpenRouter wire.
- 2026-04-21T13:12:00+07:00: Unmasked a pre-existing stale assertion in `AdvisorOrderStructuralTest`: the test still expects 4 advisors, but Phase 6 Plan 06-04 added `OutputScannerAdvisor` at `HIGHEST_PRECEDENCE + 400`. Test was silently broken because the context-load failure above hid it. Updated the assertion to require 5 advisors with the Phase 6 ordering documented.
- 2026-04-21T13:14:00+07:00: `./gradlew :ai-agent:test :ai-agent-starter:test` — BUILD SUCCESSFUL (all 162+ tests pass). `./gradlew :ai-agent:evalTest :ai-agent-starter:evalTest` — BUILD SUCCESSFUL.

## Eliminated

- `StandardQueryCache.java:52` owned by Jmix's EclipseLink L2 cache was the correct file, not an EclipseLink internal.
- Not an AiParameters seeding (`default-params.yaml`) timing issue — seeding runs after context refresh completes.
- Not an `@EnableJpaRepositories` or `persistence.xml` wiring issue — Liquibase changelogs apply cleanly and `JmixEntityManagerFactoryBean` initializes normally once the CacheManager wiring is fixed.
- The `ChatClientFactory.defaultChatClient` bean-time `parametersResolver.resolveActive()` call is **also** a latent issue (it forces the Jmix data-store chain to bootstrap during `@Bean` factory evaluation and is functionally dead code), but it is NOT the sole trigger — fixing only that still surfaced `resource-roles-cache` next. Both fixes are included.

## Resolution

root_cause: `AiAgentGuardAutoConfiguration.aiAgentGuardCacheManager()` registered a default `CacheManager` bean using the fixed-names `ConcurrentMapCacheManager(String... cacheNames)` constructor with only `"ai-agent.rateLimit"` and `"ai-agent.tokenBreaker"`. That constructor disables dynamic cache creation. Because this bean is `@ConditionalOnMissingBean` and is picked up as the primary `CacheManager`, Jmix's runtime cache consumers — `StandardQueryCache` (cache name `jmix-eclipselink-query-cache`), `ResourceRoleRepositoryImpl` (cache name `resource-roles-cache`), and similar — could not obtain their caches at `@PostConstruct`/context-refresh time, crashing startup with `IllegalStateException: Unable to find cache: <name>`. A contributing latent issue was `ChatClientFactory.defaultChatClient` calling `AiParametersResolver.resolveActive()` at bean-factory time, which forced Jmix's `eclipselink_JpaDataStore`/`eclipselink_QueryCache` chain to initialize inside `@Bean` evaluation — an unnecessary coupling since `DefaultChatServiceImpl.ask(...)` already resolves per-request and overrides `.defaultSystem(...)`.

fix:
  1. `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` — replace `new ConcurrentMapCacheManager("ai-agent.rateLimit", "ai-agent.tokenBreaker")` with `new ConcurrentMapCacheManager()` + pre-touch the two D-12 caches via `mgr.getCache("ai-agent.rateLimit")` / `mgr.getCache("ai-agent.tokenBreaker")`. Dynamic mode stays ON; Jmix's caches auto-create on first `getCache(name)`. Javadoc expanded with an explicit "do NOT pass cache names to the constructor" warning so a future edit cannot silently reintroduce the regression.
  2. `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java` — remove the bean-time `parametersResolver.resolveActive()` call and the unused `AiParametersResolver parametersResolver` constructor parameter. `.defaultSystem(...)` now uses the static `AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT` constant; per-request resolution in `DefaultChatServiceImpl.ask(...)` supersedes it via `chatClient.prompt().system(composedSystemPrompt)`. Javadoc documents the "no bean-time DataManager access" rule.
  3. `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java` — updated the stale 4-advisor assertion (Phase 4/5 era) to the 5-advisor Phase 6 shape, asserting `OutputScannerAdvisor` at `HIGHEST_PRECEDENCE + 400`. Previously hidden by the context-load failure.

verification:
  - `./gradlew :ai-agent:test :ai-agent-starter:test` → BUILD SUCCESSFUL (all tests pass, including `AdvisorOrderStructuralTest`).
  - `./gradlew :ai-agent:evalTest :ai-agent-starter:evalTest` → BUILD SUCCESSFUL.
  - `./gradlew :ai-agent:liveTest --tests "*ChatServiceLiveSemanticTest*"` with a dummy key → Spring context loads cleanly (no cache errors). Jmix eclipselink + security repositories initialize normally. Test then runs end-to-end through `ChatService.ask(...)`. The only remaining assertion failure is the content check, which happens because the dummy-key path falls back to the stub ChatModel (test returns `"stub:..."`). This is an artefact of using a dummy key for reproduction; a real `OPENROUTER_API_KEY` run will exercise the full OpenRouter wire. Phase 6 UAT Test 1 is no longer blocked by the context-startup failure.
