---
phase: 06-parameters-structured-output-guardrails
reviewed: 2026-04-21T05:40:15.7724543Z
depth: standard
files_reviewed: 46
files_reviewed_list:
  - ai-agent/ai-agent-starter/ai-agent-starter.gradle
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
  - ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
  - ai-agent/ai-agent-starter/src/test/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfigurationBootTest.java
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/CompiledOutputScannerPattern.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCapExceededException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCounter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitExceededException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/StructuredOutputException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetExhaustedException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBody.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBodyYamlMapper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/DefaultParamsSeeder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/Overrides.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersValidationException.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/GuardedToolCallingManagerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/RateLimitGuardTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/TokenBudgetGuardTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/I18nParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/parameters/DefaultParamsSeederTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/parameters/ParametersServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/EvalFixtures.java
  - ai-agent/ai-agent/src/test/resources/eval/iteration-cap-fixtures.yaml
  - ai-agent/ai-agent/src/test/resources/eval/output-scanner-corpus.yaml
  - ai-agent/ai-agent/src/test/resources/eval/param-profile-fixtures.yaml
  - ai-agent/ai-agent/src/test/resources/eval/structured-output-fixtures.yaml
findings:
  critical: 1
  warning: 3
  info: 0
  total: 4
status: issues_found
---

# Phase 06: Code Review Report

**Reviewed:** 2026-04-21T05:40:15.7724543Z
**Depth:** standard
**Files Reviewed:** 46
**Status:** issues_found

## Summary

This was a static standard-depth review of the Phase 06 source scope plus its evaluation tests. The main problems are guardrails silently dropping out under supported host overrides, incorrect conversation lifecycle handling in `ask`/`askTyped`, and a stale return value from `ParametersService.create(..., true)`.

I did not run Gradle tests for this review.

## Critical Issues

### CR-01: Host `CacheManager` overrides can silently disable both cache-backed guards

**File:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java:63-68`
**Issue:** The starter skips its default `ConcurrentMapCacheManager` whenever the host provides any `CacheManager`, but `RateLimitGuard` and `TokenBudgetGuard` both fail open when `getCache(...)` returns `null` (`ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java:80-85`, `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java:65-67,85-87`). The shipped boot test’s override fixture uses `new ConcurrentMapCacheManager("host-cache")` (`ai-agent/ai-agent-starter/src/test/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfigurationBootTest.java:65-99`), which is exactly the static-cache setup that leaves `ai-agent.rateLimit` and `ai-agent.tokenBreaker` absent. In that supported override path, rate limiting and token-budget enforcement become silent no-ops instead of failing fast.
**Fix:**
```java
@Bean
@ConditionalOnMissingBean
public CacheManager aiAgentGuardCacheManager() {
    return new ConcurrentMapCacheManager(RateLimitGuard.CACHE_NAME, TokenBudgetGuard.CACHE_NAME);
}

private static Cache requireCache(CacheManager cacheManager, String name) {
    Cache cache = cacheManager.getCache(name);
    if (cache == null) {
        throw new IllegalStateException("Required AI guard cache missing: " + name);
    }
    return cache;
}
```

## Warnings

### WR-01: `ask` creates conversations before guard checks, and `askTyped` retries do not reuse the created id

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:149-156,269-270`
**Issue:** `ask()` calls `conversationGateway.loadOrCreate(...)` before running the rate-limit/token-budget preamble, so a denied first turn still persists a new `AiConversation`. `ConversationGateway` immediately saves on `conversationId == null` (`ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java:61-73`). On top of that, `askTyped()` reuses the original `conversationId` argument on every retry instead of threading forward `resp.conversationId()`, so a parse failure on a first turn (`conversationId == null`) creates a second conversation on retry, loses memory continuity, and resets conversation-scoped token budgeting.
**Fix:**
```java
public ChatResponseDto ask(String userId, UUID conversationId, String message, Overrides overrides) {
    rateLimitGuard.check(userId);
    AiConversation conversation = conversationGateway.loadOrCreate(userId, conversationId, message);
    ...
}

public <T> T askTyped(String userId, UUID conversationId, String message, Overrides overrides, Class<T> targetType) {
    UUID currentConversationId = conversationId;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        ChatResponseDto resp = ask(userId, currentConversationId, enrichedUserMessage, overrides);
        currentConversationId = resp.conversationId();
        ...
    }
}
```

### WR-02: `ParametersService.create(..., true)` returns a stale inactive entity

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java:55-63`
**Issue:** `create()` always persists the new row with `active=false`, then calls `setActive(saved.getId())` when `active` is requested, but still returns the original `saved` instance. Callers therefore receive an entity that still reports `active=false` even though the database row was activated, which is an immediate state mismatch for admin UI or API consumers.
**Fix:**
```java
AiParameters saved = dataManager.save(row);
if (active) {
    setActive(saved.getId());
    return loadOrThrow(saved.getId());
}
return saved;
```

### WR-03: The guarded tool-calling wrapper only works when the upstream bean is named `toolCallingManager`

**File:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java:98-120`
**Issue:** The wrapper bean is gated by `@ConditionalOnBean(name = "toolCallingManager")` and resolves its delegate through the same hard-coded bean name. If a host replaces Spring AI’s `ToolCallingManager` under a different bean name and the default autoconfiguration backs off, `guardedToolCallingManager` is never created. `ChatClientFactory` then injects the raw `ToolCallingManager` by type (`ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java:52-70`), which drops the iteration-cap and `ToolGuard` veto enforcement entirely.
**Fix:**
```java
@Bean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
@Primary
@ConditionalOnMissingBean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
public ToolCallingManager guardedToolCallingManager(
        ObjectProvider<ToolCallingManager> managers,
        AiAgentGuardProperties props,
        ToolGuard toolGuard,
        AuditWriter auditWriter,
        CurrentAuthentication currentAuthentication) {

    ToolCallingManager delegate = managers.orderedStream()
            .filter(manager -> !(manager instanceof GuardedToolCallingManager))
            .reduce((first, second) -> {
                throw new IllegalStateException("Expected exactly one upstream ToolCallingManager");
            })
            .orElseThrow(() -> new IllegalStateException("No upstream ToolCallingManager found"));

    return new GuardedToolCallingManager(delegate, props, toolGuard, auditWriter, currentAuthentication);
}
```

---

_Reviewed: 2026-04-21T05:40:15.7724543Z_
_Reviewer: Codex (gsd-code-reviewer)_
_Depth: standard_
