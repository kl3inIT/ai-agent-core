---
phase: 06-parameters-structured-output-guardrails
fixed_at: 2026-04-21T06:05:00Z
review_path: .planning/phases/06-parameters-structured-output-guardrails/06-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 06: Code Review Fix Report

**Fixed at:** 2026-04-21T06:05:00Z
**Source review:** .planning/phases/06-parameters-structured-output-guardrails/06-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (1 critical + 3 warning; info findings are 0)
- Fixed: 4
- Skipped: 0

## Fixed Issues

### CR-01: Host `CacheManager` overrides can silently disable both cache-backed guards

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java`
**Commit:** 7dcdb44
**Applied fix:** Replaced the fail-open `getCache(name) == null -> return` branch in both guards with a fail-fast `IllegalStateException` carrying actionable remediation text (pre-register the cache on the host `CacheManager`, or rely on the starter's default `ConcurrentMapCacheManager`). `TokenBudgetGuard` gained a private `requireCache()` helper reused by `check`, `accumulate`, and `currentTotal`. The autoconfig bean itself was left `@ConditionalOnMissingBean` (hosts supplying their own `CacheManager` still win), but misconfiguration now surfaces at the first guarded request rather than as silent enforcement drop. `reset()` intentionally kept its lenient semantics (operator tooling path) so a stale test hook cannot escalate to a crash.

### WR-01: `ask` creates conversations before guard checks, and `askTyped` retries do not reuse the created id

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
**Commit:** 7774b3c
**Applied fix:** Split the guard preamble so `rateLimitGuard.check(userId)` runs BEFORE `conversationGateway.loadOrCreate(...)`; on rate-limit denial the audit/denial DTO now carries the original (possibly null) `conversationId` instead of persisting a fresh `AiConversation`. `tokenBudgetGuard.check(convId)` remains after `loadOrCreate` because it is strictly per-conversation. In `askTyped`, introduced `UUID currentConversationId = conversationId;` and reassign `currentConversationId = resp.conversationId()` after each `ask(...)` call so parse-failure retries reuse the conversation id minted on attempt 1 — preserving memory continuity and the per-conversation token budget instead of spawning a second conversation on retry.

### WR-02: `ParametersService.create(..., true)` returns a stale inactive entity

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java`
**Commit:** 32a1919
**Applied fix:** After `setActive(saved.getId())` flips the DB row, return `loadOrThrow(saved.getId())` so admin/API callers observe the post-activation state (`active=true`) instead of the stale `saved` instance which still reported `active=false`. The `active=false` branch keeps the existing `return saved` path (no reload cost when nothing changed).

### WR-03: The guarded tool-calling wrapper only works when the upstream bean is named `toolCallingManager`

**Files modified:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java`
**Commit:** 1e33628
**Applied fix:** Replaced the `@ConditionalOnBean(name = "toolCallingManager")` gate plus `BeanFactory.getBean("toolCallingManager", ...)` lookup with `@ConditionalOnBean(ToolCallingManager.class)` plus an `ObjectProvider<ToolCallingManager>` that is filtered to exclude any `GuardedToolCallingManager` instance (avoiding the `@Primary`-on-self cycle). `resolveDelegate` throws `IllegalStateException` with actionable guidance when zero or multiple non-self candidates exist, so hosts now either get the guarded decoration regardless of upstream bean name, or fail loudly at startup instead of silently dropping iteration-cap and `ToolGuard` veto enforcement. Removed the now-unused `BeanFactory` import and updated the class-level + method-level javadoc to describe the type-based resolution.

---

_Fixed: 2026-04-21T06:05:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
