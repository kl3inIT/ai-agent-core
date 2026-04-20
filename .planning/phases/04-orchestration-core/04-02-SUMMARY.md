---
phase: 04-orchestration-core
plan: 02
subsystem: orchestration
tags: [configuration-properties, spring-ai, baseline-context, resolver, threadlocal, i18n]
dependency-graph:
  requires:
    - Phase 2 AiParameters entity + ai_AiParameters JPA entity name
    - Jmix 2.8 DataManager fluent loader API
    - Jmix 2.8 CurrentAuthentication
    - Spring Boot @ConfigurationPropertiesScan on AIConfiguration
    - org.yaml:snakeyaml 2.4 (transitive via Spring Boot)
  provides:
    - AiAgentDefaultsProperties (bound to jmix.ai-agent.defaults.*)
    - AiParametersResolver.resolveActive() + effective* accessors
    - BaselineContextProvider.compose(UUID) + renderAsText(UUID)
    - RunContext ThreadLocal<UUID>
    - ConversationNotFoundException (single-message, i18n-keyed)
    - i18n key com.vn.agent.orchestration/ConversationNotFound (EN + VI)
    - Host application.properties defaults (5 jmix.ai-agent.defaults.* + 3 spring.ai.openai.*)
  affects:
    - Plan 04-03 (AuditAdvisor/ToolCallbackAuditDecorator read RunContext; ConversationNotFoundException propagated to client)
    - Plan 04-04 (DefaultChatServiceImpl will call AiParametersResolver.resolveActive() each turn and BaselineContextProvider.renderAsText() to prepend baseline text to system prompt)
    - Plan 04-05 (integration test coverage for the orchestration loop)
tech-stack:
  added:
    - org.yaml.snakeyaml.Yaml (already transitively on runtime classpath; verified via W12 probe)
  patterns:
    - Per-request DataManager read + Metadata.create() fallback (CLAUDE.md: no entity constructor)
    - @ConfigurationProperties record bound via @ConfigurationPropertiesScan
    - Deterministic baseline prompt rendering (TreeMap alphabetical sort) — byte-stable for cache/audit prompt-hash
    - ThreadLocal-carried correlation id with clear() in finally (sync-only per D-16)
    - Opacity-preserving exception message (single literal for missing AND not-yours branches — D-09, Pitfall #8)
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentDefaultsProperties.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationNotFoundException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - jmix-app/src/main/resources/application.properties
decisions:
  - Snakeyaml 2.4 already on runtime classpath (via Spring Boot starter) — no explicit build.gradle dep needed.
  - Resolver YAML fallback synthesises full yaml including maxTokens (plan snippet omitted it but record has 5 components; storing all defaults keeps effectiveMaxTokens symmetric with the other accessors).
  - BaselineContextProvider wraps currentAuthentication.getUser()/getLocale() in a try/catch so anonymous-user call sites (no SecurityContext) degrade gracefully to the plan-specified null-user shape instead of throwing.
metrics:
  duration_minutes: ~20
  tasks_completed: 2
  files_created: 7
  files_modified: 3
  commits: 2
  completed_date: 2026-04-20
---

# Phase 04 Plan 02: Orchestration foundations Summary

Five dependency-free building blocks (`AiAgentDefaultsProperties`, `RunContext`, `ConversationNotFoundException`, `AiParametersResolver`, `BaselineContextProvider`) landed in `com.vn.agent.orchestration`, plus matching unit tests, EN/VI i18n, and eight host `application.properties` keys — unblocking the audit pipeline (04-03) and orchestration wiring (04-04).

## Objective recap

Plans 04-03 (audit pipeline) and 04-04 (`DefaultChatServiceImpl`) need a stable substrate: a per-request resolver for the active `AiParameters` row with a config-properties fallback (D-03/D-04), a `ThreadLocal<UUID>` to correlate pre/post chat rows with per-tool rows, a single-message exception to keep conversation-id existence opaque (D-09), and a baseline `agent.*` context producer that doubles as a deterministic text renderer for prompt composition (D-15). Phase 4 is sync-only (D-16), so `ThreadLocal` is sufficient.

## Work completed

### Task 1 — foundations, i18n, defaults (commit `586ef7a`)

- `AiAgentDefaultsProperties` record bound to `jmix.ai-agent.defaults.*` via the existing `@ConfigurationPropertiesScan` on `AIConfiguration`; five components (`model`, `temperature`, `topP`, `maxTokens`, `systemPrompt`).
- `RunContext` — `final` holder with private ctor, `set/get/clear` over `ThreadLocal<UUID>`.
- `ConversationNotFoundException` — extends `RuntimeException`; public `MESSAGE_KEY` constant for i18n, `DEFAULT_MESSAGE = "Conversation not found"`; constructor echoes literal message only (D-09 opacity).
- EN/VI i18n key `com.vn.agent.orchestration/ConversationNotFound` with proper Vietnamese diacritics `Không tìm thấy cuộc hội thoại`.
- Host `jmix-app/application.properties` gets 5 `jmix.ai-agent.defaults.*` keys (model=`openai/gpt-4o-mini`, temperature=0.2, top-p=1.0, max-tokens=1500, system-prompt) and 3 `spring.ai.openai.*` keys (base-url=OpenRouter v1, api-key from env, chat.options.model).
- `./gradlew :ai-agent:ai-agent:compileJava -q` exits 0.

### Task 2 — resolver + baseline provider + unit tests (commit `5a0e4be`)

- `AiParametersResolver` (`@Component`, constructor injection of `DataManager`/`Metadata`/`AiAgentDefaultsProperties`) — `resolveActive()` calls `dataManager.load(AiParameters.class).query("select e from ai_AiParameters e where e.active = true").optional()` with `orElseGet(::buildFallback)`; fallback uses `Metadata.create()` (CLAUDE.md compliance) populated with a YAML-encoded defaults blob and `profileName="__defaults__"`, `active=true`.
- `parseBody(AiParameters)` via snakeyaml `Yaml#load`; `effectiveModel/Temperature/TopP/MaxTokens/SystemPrompt` each fall through to defaults when YAML key missing. `effectiveModel` throws `IllegalStateException` when the slug lacks `/` (OpenRouter format; Pitfall #6).
- `BaselineContextProvider` (`@Component`, constructor injection of `CurrentAuthentication`) — `compose(UUID)` returns a `LinkedHashMap<String,Object>` with the five `agent.*` keys; `renderAsText(UUID)` sorts them via `TreeMap` and joins with `\n` as `key=value`. Null values render as the literal `null`. Reflective `getKey()` extraction on the Jmix `User` class (with `getUsername()` fallback) avoids a compile-time dependency on `jmix-security`'s User shape.
- Unit tests: `AiParametersResolverTest` (3 tests — fallback shape, slug validation, per-key YAML override); `BaselineContextProviderTest` (3 tests incl. `composeRendersAsTextWithSortedAgentKeys` asserting alphabetical determinism with 5 expected lines).
- `./gradlew :ai-agent:ai-agent:test --tests AiParametersResolverTest --tests BaselineContextProviderTest` → BUILD SUCCESSFUL.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `FluentLoader.ByCondition` vs `FluentLoader.ByQuery` in test mock**
- **Found during:** Task 2 compileTestJava
- **Issue:** Plan snippet typed the mock returned by `.query(String)` as `FluentLoader.ByCondition`, but Jmix 2.8's fluent API returns `FluentLoader.ByQuery<T>` from `query(String)` — the test failed to compile.
- **Fix:** imported `FluentLoader.ByQuery` and retyped the mock.
- **Files modified:** `AiParametersResolverTest.java`
- **Commit:** `5a0e4be`

**2. [Rule 2 - Missing robustness] Anonymous SecurityContext safety in `BaselineContextProvider`**
- **Found during:** Task 2 implementation review
- **Issue:** Plan-specified mock for "anonymous" test case stubs `ca.getUser()` returning `null`, but production `CurrentAuthentication.getUser()` on a real anonymous context may throw (Jmix throws when no authentication is bound). For the provider to actually degrade to the documented null-user shape at runtime (not just in tests), the calls are wrapped in `try/catch RuntimeException`.
- **Fix:** private `safeGetUser()` / `safeGetLocale()` helpers return null on exception.
- **Files modified:** `BaselineContextProvider.java`
- **Commit:** `5a0e4be`

**3. [Plan gap — filled] `maxTokens` included in fallback YAML**
- **Found during:** Task 2 implementation
- **Issue:** Plan's `buildFallback` snippet encoded only `model/temperature/topP/systemPrompt` into the synthetic YAML but the record has a fifth component `maxTokens`. Omitting it would have made `effectiveMaxTokens(fallback)` always return the default via the map-miss path, which is functionally equivalent but asymmetric with the other accessors and confusing for readers.
- **Fix:** added the `maxTokens` line to the synthesised YAML.
- **Files modified:** `AiParametersResolver.java`
- **Commit:** `5a0e4be`

## Verification

- Files: all 7 Java files + 3 modified property files present at expected paths.
- `grep -q "@ConfigurationProperties(\"jmix.ai-agent.defaults\")"` → match.
- `grep -q "ThreadLocal<UUID>"` → match.
- `grep -q "DEFAULT_MESSAGE = \"Conversation not found\""` → match.
- `grep -q "ConversationNotFound=Conversation not found"` (EN) → match.
- `grep -q "ConversationNotFound=Không tìm thấy cuộc hội thoại"` (VI) → match with proper diacritics.
- `grep -q "jmix.ai-agent.defaults.model=openai/gpt-4o-mini"` → match.
- `grep -q "spring.ai.openai.base-url=https://openrouter.ai/api/v1"` → match.
- W12 snakeyaml probe: `./gradlew dependencies … | grep snakeyaml` → `org.yaml:snakeyaml:2.4` present (no build.gradle change required).
- `./gradlew :ai-agent:ai-agent:compileJava` → exit 0.
- `./gradlew :ai-agent:ai-agent:test --tests AiParametersResolverTest --tests BaselineContextProviderTest` → BUILD SUCCESSFUL (6 tests).

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentDefaultsProperties.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationNotFoundException.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
- FOUND: commit 586ef7a (foundations + i18n + application.properties)
- FOUND: commit 5a0e4be (resolver + baseline + tests)
- FOUND: compileJava exit 0, test tasks BUILD SUCCESSFUL
