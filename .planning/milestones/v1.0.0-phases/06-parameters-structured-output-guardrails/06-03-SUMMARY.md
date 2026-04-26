---
phase: 06-parameters-structured-output-guardrails
plan: 03
subsystem: [guardrails, rate-limit, token-budget, iteration-cap, output-scanner, autoconfig]
tags: [guard, rate-limit, token-breaker, iteration-cap, scanner, @Primary, reflective-class]
requires:
  - com.vn.agent.guard.AiAgentGuardProperties
  - com.vn.agent.guard.RateLimitExceededException
  - com.vn.agent.guard.TokenBudgetExhaustedException
  - com.vn.agent.guard.IterationCapExceededException
  - com.vn.agent.audit.AuditWriter
  - com.vn.agent.spi.ToolGuard
  - com.vn.agent.spi.ToolVetoedException
provides:
  - com.vn.agent.guard.RateLimitGuard
  - com.vn.agent.guard.TokenBudgetGuard
  - com.vn.agent.guard.IterationCounter
  - com.vn.agent.guard.CompiledOutputScannerPattern
  - com.vn.agent.guard.GuardedToolCallingManager
  - com.vn.agent.guard.OutputScannerAdvisor
  - com.vn.autoconfigure.agent.AiAgentGuardAutoConfiguration
affects:
  - ai-agent/ai-agent/ai-agent.gradle (added spring-boot-starter-cache)
  - ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (AiAgentGuardAutoConfiguration appended)
tech-stack:
  added:
    - org.springframework.boot:spring-boot-starter-cache (BOM-versioned)
  patterns:
    - "Sliding-window deque per user in a Spring Cache; synchronized read-modify-write"
    - "ThreadLocal<Integer> iteration counter owned by decorator, lifecycle by orchestrator"
    - "ToolCallingManager decorator with pre-delegate ToolGuard fan-out + iteration cap"
    - "CallAdvisor flag-and-pass-through with pattern KEY only (no matched text) into response.context()"
    - "@Primary bean + BeanFactory.getBean(name) lookup to avoid @Primary-on-self injection cycle"
    - "@ConditionalOnClass + reflective construction for optional forward-compat Spring AI class"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCounter.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/CompiledOutputScannerPattern.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
decisions:
  - "ToolGuard.check takes Map<String, Object>, not a JSON string — the plan pseudocode passed argumentsJson verbatim but the actual Phase 2 SPI signature is (String toolName, Map<String,Object> arguments). Jackson ObjectMapper is used in GuardedToolCallingManager to parse AssistantMessage.ToolCall.arguments() (which IS a JSON string per Spring AI 1.1.4) into a Map before calling the SPI. Malformed JSON falls back to an empty Map + debug log so ToolGuard can still inspect the tool name."
  - "GuardedToolCallingManager resolves its delegate via BeanFactory.getBean('toolCallingManager', ToolCallingManager.class) at bean construction time rather than taking a ToolCallingManager parameter. A direct parameter would cause Spring to inject the @Primary bean (which is this bean itself once created) — a classic self-reference cycle. Name lookup is deterministic because Spring AI's default autoconfig registers under the canonical method name 'toolCallingManager'."
  - "AssistantMessage.ToolCall in Spring AI 1.1.4 is a record with accessor methods name() / arguments() (confirmed by javap against the 1.1.4 jar in local Gradle cache). Not getName()/getArguments() — plan guidance to confirm at implementation time paid off."
  - "IterationCap comparison is strict next > cap (D-16 'maximum rounds ALLOWED'): after max allowed rounds have run, the (max+1)th call throws. Plan 05 test E-05 will pin this."
  - "OutputScannerAdvisor hard-caps input text to 8 KiB (MAX_SCAN_CHARS=8192) before any regex matching — T-06-12 ReDoS defence matching the contract documented in AiAgentGuardProperties.resolvedPatterns() Javadoc. Invalid operator-supplied regexes are caught at constructor time and skipped with a WARN log, so one bad pattern cannot kill the advisor."
metrics:
  duration: "~25 minutes"
  completed: "2026-04-21"
  tasks: 3
---

# Phase 6 Plan 03: Runtime Guardrails Summary

Ship the five runtime guardrail components (RateLimitGuard, TokenBudgetGuard, IterationCounter + GuardedToolCallingManager, OutputScannerAdvisor + CompiledOutputScannerPattern) plus the starter autoconfiguration that wires zero-config defaults for cache, scanner advisor, guarded tool-calling manager, and the forward-compat reflective StructuredOutputValidationAdvisor. All work is additive on top of Plan 06-01's exception catalogue + properties record and Plan 06-02's parameters layer — no shared files modified with 06-02's dependency block.

## Confirmed Spring AI 1.1.4 Interfaces

Confirmed via `javap` against the Spring AI JARs resolved by Gradle to the local cache (`~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/spring-ai-model/1.1.4/...`):

```java
public interface org.springframework.ai.model.tool.ToolCallingManager {
    List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions);
    ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse);
    static DefaultToolCallingManager.Builder builder();
}

public final class org.springframework.ai.chat.messages.AssistantMessage$ToolCall extends Record {
    public String id();
    public String type();
    public String name();          // record accessor style
    public String arguments();     // record accessor style
}

public class org.springframework.ai.chat.messages.AssistantMessage {
    public List<AssistantMessage.ToolCall> getToolCalls();   // getter style
    public boolean hasToolCalls();
}
```

GuardedToolCallingManager implements both abstract methods on `ToolCallingManager` (`resolveToolDefinitions` delegates verbatim; `executeToolCalls` carries the iteration cap + ToolGuard fan-out logic). The static `builder()` is inherited by default.

## What Shipped

### RateLimitGuard (`com.vn.agent.guard.RateLimitGuard`)

- `@Component` with 3-arg constructor injection: `CacheManager`, `AiAgentGuardProperties`, `CurrentAuthentication`.
- `CACHE_NAME = "ai-agent.rateLimit"`.
- Two overloads: `check()` resolves username from `CurrentAuthentication` (anonymous → skip); `check(String username)` is public for tests and for future request-tagged call sites.
- `synchronized` on the instance — acceptable for default `ConcurrentMapCacheManager`; Redis hosts should replace the guard (noted in class Javadoc).
- Algorithm: sliding-window `Deque<Long>` of epoch-ms timestamps per user. Evict entries older than 60 000 ms. If `hits.size() >= ceiling`, throw `new RateLimitExceededException(ceiling)` (stable key message + audit-only side field). Denied attempts are NOT recorded — prevents starvation after cooldown.
- Opt-out via `jmix.ai-agent.guard.rate-limit.enabled=false` → both overloads no-op.

### TokenBudgetGuard (`com.vn.agent.guard.TokenBudgetGuard`)

- `@Component` with 2-arg constructor injection: `CacheManager`, `AiAgentGuardProperties`.
- `CACHE_NAME = "ai-agent.tokenBreaker"`.
- `check(UUID conversationId)` — `synchronized`; throws `TokenBudgetExhaustedException(ceiling)` when stored total ≥ ceiling. Null conversation id skips (first-turn budget is still under allocation).
- `accumulate(UUID conversationId, long tokensUsed)` — `synchronized`; non-positive `tokensUsed` + null convId are no-ops.
- `reset(UUID conversationId)` — `synchronized` cache eviction; test-hook + operator-tooling override.
- Opt-out via `jmix.ai-agent.guard.token-breaker.enabled=false` → all three methods no-op.

### IterationCounter (`com.vn.agent.guard.IterationCounter`)

- Static-only utility (not a Spring bean): `ThreadLocal<Integer> COUNT`.
- API: `start()` / `current()` / `increment()` → returns new value / `reset()`.
- Lifecycle discipline mirrors `RunContext` — orchestrator calls `start()` at top of turn, `reset()` in finally.

### CompiledOutputScannerPattern (`com.vn.agent.guard.CompiledOutputScannerPattern`)

- Java `record(String key, Pattern pattern)`.
- Static factory `from(AiAgentGuardProperties.OutputScanner.Pattern)` compiles once; throws `PatternSyntaxException` on malformed regex so bad entries surface at advisor construction (not mid-chat).

### GuardedToolCallingManager (`com.vn.agent.guard.GuardedToolCallingManager`)

- `public class ... implements ToolCallingManager` (not `@Component` — wired by autoconfig as `@Primary`).
- `CHAT_SENTINEL_TOOL_NAME = "__chat__"` public constant (D-11).
- `resolveToolDefinitions(...)` → pure delegation to wrapped manager.
- `executeToolCalls(Prompt, ChatResponse)`:
  1. `IterationCounter.increment()` → if `next > cap` (strict), audit with sentinel tool `"__chat__"` + `denialReason="iteration-cap-exceeded"` + `phase="REQUEST"` + outcome `BLOCKED`, then `throw new IterationCapExceededException(cap)`.
  2. Extract `chatResponse.getResult().getOutput().getToolCalls()` (empty-list safe at every level).
  3. For each `AssistantMessage.ToolCall`: parse `call.arguments()` JSON → `Map<String,Object>` via Jackson; call `toolGuard.check(name, argumentsMap)`; on `ToolVetoedException` audit with REAL tool name + `denialReason="tool-vetoed:" + msg` + phase `"PRE"` + outcome `BLOCKED`, then re-throw.
  4. `delegate.executeToolCalls(prompt, chatResponse)`.
- All `AuditWriter` calls wrapped in `safeAudit(Runnable)` that swallows `Throwable` with a WARN log so an audit glitch cannot mask the underlying guard decision.
- Start/reset of `IterationCounter` is intentionally NOT in this class (orchestrator owns the lifecycle — Plan 04).

### OutputScannerAdvisor (`com.vn.agent.guard.OutputScannerAdvisor`)

- `public class ... implements CallAdvisor` (not `@Component` — wired by autoconfig).
- `getOrder() = Ordered.HIGHEST_PRECEDENCE + 400` (GUARD-05, D-17).
- `CONTEXT_KEY_FLAGGED_PATTERN = "outputScanner.flaggedPatternKey"`.
- `MAX_SCAN_CHARS = 8192` (T-06-12 ReDoS cap — documented contract matches `AiAgentGuardProperties.resolvedPatterns()` Javadoc).
- Constructor compiles every resolved pattern through `CompiledOutputScannerPattern.from(...)`; malformed entries logged at WARN and filtered out so a single bad operator regex cannot disable the advisor.
- `adviseCall(...)`: run chain first → if scanner disabled or response body missing, return unmodified → slice text to 8 KiB → run each compiled pattern → on first hit write the KEY (never the matched text, D-17) into `response.context()` under `CONTEXT_KEY_FLAGGED_PATTERN` and break. Response body unchanged (flag-and-pass-through).
- Context write wrapped in try/catch so an immutable-map response implementation cannot mask the successful scan.

### AiAgentGuardAutoConfiguration (`com.vn.autoconfigure.agent.AiAgentGuardAutoConfiguration`)

- `@AutoConfiguration @AutoConfigureAfter(AIAutoConfiguration.class)`.
- Four bean methods, all `@ConditionalOnMissingBean`:
  1. `CacheManager aiAgentGuardCacheManager()` → default `ConcurrentMapCacheManager("ai-agent.rateLimit", "ai-agent.tokenBreaker")`. Logs one `INFO` line on fallback so missing host CacheManagers are visible in boot logs (T-06-18).
  2. `CallAdvisor outputScannerAdvisor(AiAgentGuardProperties)` — matched by bean name.
  3. `@Primary ToolCallingManager guardedToolCallingManager(BeanFactory, props, toolGuard, auditWriter, currentAuthentication)` — named `"guardedToolCallingManager"`. Resolves delegate via `BeanFactory.getBean("toolCallingManager", ToolCallingManager.class)` to avoid `@Primary`-on-self injection cycle. Missing delegate surfaces an `IllegalStateException` with a human-readable override hint.
  4. `CallAdvisor structuredOutputValidationAdvisor()` — `@ConditionalOnClass(name="org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor")`. Class absent in Spring AI 1.1.4, so the method is skipped today (as expected, D-21 / forward-compat).

### Build + Registration

- `ai-agent/ai-agent/ai-agent.gradle` — added Plan 06-03 labeled block declaring `implementation 'org.springframework.boot:spring-boot-starter-cache'`. Placed AFTER the Plan 06-02 YAML/Validation block so both plans' deps coexist cleanly with no edit conflict.
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — appended `com.vn.autoconfigure.agent.AiAgentGuardAutoConfiguration` as the fourth line; existing three entries (AIAutoConfiguration, SpiDefaultsAutoConfiguration, AiToolsAutoConfiguration) preserved verbatim.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` — **BUILD SUCCESSFUL** after Task 1 AND after Task 2 (first runs, no retries).
- `./gradlew :ai-agent:ai-agent-starter:compileJava` — **BUILD SUCCESSFUL** after Task 3 (first run, no retries).
- Acceptance-criteria greps:
  - `"ai-agent.rateLimit"` in `RateLimitGuard.java` → 2 matches (constant + Javadoc reference).
  - `"ai-agent.tokenBreaker"` in `TokenBudgetGuard.java` → present on `CACHE_NAME` constant.
  - `props.rateLimitEnabled` / `props.tokenBreakerEnabled` → both present in respective guards.
  - `throw new RateLimitExceededException` in `RateLimitGuard.java` → 1.
  - `throw new TokenBudgetExhaustedException` in `TokenBudgetGuard.java` → 1.
  - `ThreadLocal<Integer>` in `IterationCounter.java` → 1.
  - `spring-boot-starter-cache` in `ai-agent.gradle` → 1.
  - `implements ToolCallingManager` in `GuardedToolCallingManager.java` → 1.
  - `implements CallAdvisor` in `OutputScannerAdvisor.java` → 1.
  - `Ordered.HIGHEST_PRECEDENCE + 400` in `OutputScannerAdvisor.java` → 1.
  - `outputScanner.flaggedPatternKey` in `OutputScannerAdvisor.java` → 1 (the context key literal).
  - `CHAT_SENTINEL_TOOL_NAME = "__chat__"` in `GuardedToolCallingManager.java` → 1.
  - `toolGuard.check` in `GuardedToolCallingManager.java` → 1.
  - `IterationCounter.increment` / `IterationCapExceededException` in `GuardedToolCallingManager.java` → 3 total occurrences.
  - `AiToolCallOutcome.BLOCKED` in `GuardedToolCallingManager.java` → 2 (iteration cap + veto).
  - No `// PSEUDOCODE` comments remain anywhere.
  - `@ConditionalOnMissingBean` in `AiAgentGuardAutoConfiguration.java` → 5 matches (one per bean method + import).
  - `ConcurrentMapCacheManager("ai-agent.rateLimit", "ai-agent.tokenBreaker")` → 1.
  - `StructuredOutputValidationAdvisor` in `AiAgentGuardAutoConfiguration.java` → 3 (Javadoc + `@ConditionalOnClass` arg + `Class.forName`).
  - `AiAgentGuardAutoConfiguration` in imports file → 1; existing 3 entries preserved.

## Deviations from Plan

### Rule 1 Auto-fixes

**1. [Rule 1 — Type mismatch] `ToolGuard.check` takes `Map<String, Object>`, not `String argumentsJson`**

- **Found during:** Task 2 pre-writing review of existing SPI signatures.
- **Issue:** Plan pseudocode called `toolGuard.check(call.name(), call.arguments())` passing the raw JSON string. The actual Phase 2 SPI signature (shipped in Plan 02-02) is `void check(String toolName, Map<String, Object> arguments) throws ToolVetoedException`. Compiling the pseudocode verbatim would have failed.
- **Fix:** GuardedToolCallingManager parses `AssistantMessage.ToolCall.arguments()` (a JSON string per Spring AI 1.1.4) into a `Map<String, Object>` via a shared static `ObjectMapper`. Malformed JSON falls back to `Collections.emptyMap()` with a debug log — ToolGuard implementations that only need the tool name still function; implementations requiring structured arguments will see an empty map (documented in-code).
- **Files modified:** `GuardedToolCallingManager.java`.
- **Commit:** `906e289` (Task 2).

### Rule 3 Auto-fixes

**1. [Rule 3 — Blocking issue] `@Primary`-on-self injection cycle risk**

- **Found during:** Task 3 pre-writing.
- **Issue:** Plan pseudocode for the autoconfig took `ToolCallingManager delegate` as a method parameter and marked the produced bean `@Primary`. Once the guarded manager is registered as primary, Spring would attempt to satisfy the `delegate` parameter by injecting the primary — which is the very bean being constructed. Classic self-reference cycle.
- **Fix:** Swapped the parameter for `BeanFactory` and resolve the delegate inside the method via `beanFactory.getBean("toolCallingManager", ToolCallingManager.class)`. Spring AI's default autoconfig registers the bean under this canonical name; a host that has replaced it with a differently-named bean can declare its own `"guardedToolCallingManager"` bean to opt out. An `IllegalStateException` with a human-readable override hint is raised if the delegate cannot be found.
- **Files modified:** `AiAgentGuardAutoConfiguration.java`.
- **Commit:** `a1e73e6` (Task 3).

### Scope Boundary Notes

- The plan mentioned that `AssistantMessage.ToolCall` might use getter-style accessors (`getName()` / `getArguments()`) as a fallback. Confirmed via `javap` against the Spring AI 1.1.4 JAR: the class is a `public final class ... extends Record` with `name()` / `arguments()` accessors. No fallback shim needed.
- `@ConfigurationPropertiesScan` on `AIConfiguration` already covers `com.vn.agent.*` (confirmed by grep against the existing `@ConfigurationPropertiesScan` annotation at `AIConfiguration.java:28`). No `@EnableConfigurationProperties` supplementation required.
- Scanner pattern trio at runtime is exactly the D-18 bundled set: `IGNORE_PREVIOUS_INSTRUCTIONS` (case-insensitive "ignore (all )?previous instructions"), `SYSTEM_TAG_LEAK` (case-insensitive `</?system>`), `ROLE_BREAK` (case-insensitive bounded `.{0,2048}?` between `assistant:` / `user:` tokens). Custom operator patterns replace this whole list via `jmix.ai-agent.guard.output-scanner.patterns[N].{key,regex}`.

## Known Stubs / Deferred Work

- **`ObjectProvider<ToolCallingManager>` fallback NOT needed today.** The BeanFactory name-lookup approach is cleaner and more deterministic for the current Spring AI 1.1.4 wiring. If Spring AI changes the default bean name in a future release, swap to `ObjectProvider<ToolCallingManager>` + iterate-and-filter-by-`!= this`.
- **Guard instance-level `synchronized`** is a known hot-path point of contention under extreme concurrency. The default `ConcurrentMapCacheManager` does not support atomic compute-and-put; hosts that switch to Redis/Caffeine should also swap in a guard variant with striped locks or atomic cache ops. Tracked in class Javadoc.
- **No behavioural tests yet.** Plan 06-05 owns the test suite (E-01 rate-limit denial, E-02 token-breaker race, E-03 iteration cap exact-6+1, E-04 tool veto audit, E-05 scanner flag, E-08 autoconfig graceful degradation, E-10 plug-and-play boot). This plan's verification gate is `compileJava` only (matches the plan's `<verification>` directive).
- **Plan 04 owes the wiring.** `IterationCounter.start()/reset()` calls, `RateLimitGuard.check()` + `TokenBudgetGuard.check()` invocations at the top of `DefaultChatServiceImpl.ask`, `TokenBudgetGuard.accumulate()` after the response, `OutputScannerAdvisor` added to `ChatClientFactory`'s default advisor list, and the GuardDenialInfo mapper are all Plan 04's scope.
- **`StructuredOutputValidationAdvisor` bean is absent at boot** because Spring AI 1.1.4 does not ship the class — expected per D-21. The `@ConditionalOnClass` stanza activates automatically when a future Spring AI adds the class; no code change required.

## Threat Model Adherence

| Threat ID | Mitigation Plan Says | Implemented |
|-----------|----------------------|-------------|
| T-06-12 | RateLimitGuard enforces per-minute ceiling via sliding-window deque | Yes — `check(username)` evicts entries older than 60 000 ms then compares against `props.resolvedRequestsPerMinute()`. |
| T-06-13 | TokenBudgetGuard enforces per-conversation ceiling | Yes — `check(conversationId)` reads cached total and compares against `props.resolvedTokenCeiling()`; `accumulate` tracks post-response usage. |
| T-06-14 | Iteration cap with strict > comparison | Yes — `if (next > cap)` inside `GuardedToolCallingManager.executeToolCalls` matches D-16's "rounds allowed" semantics. |
| T-06-15 | Pre-tool-call `ToolGuard.check` veto hook with BLOCKED audit carrying real tool name | Yes — veto audit uses `call.name()`, `argumentsJson`, `denialReason = "tool-vetoed:" + message`, outcome `BLOCKED`, phase `"PRE"`; request-level cap breach uses sentinel `"__chat__"`, phase `"REQUEST"`. |
| T-06-16 | OutputScannerAdvisor flag-and-pass-through with pattern KEY only | Yes — first-match-wins; `response.context().put(CONTEXT_KEY_FLAGGED_PATTERN, key)` — matched text never crosses the boundary. |
| T-06-17 | Scanner pattern text / matched fragment never leaks to UI | Yes — advisor writes KEY only; input is first sliced to 8 KiB so the matched substring cannot exceed that even via future code paths that might log it. |
| T-06-18 | @ConditionalOnMissingBean is intentionally overridable; logs one INFO per fallback | Yes — CacheManager fallback logs one INFO line; bean-name conditionals let hosts opt out per-seam. |
| T-06-19 | Guard denial always produces an audit row; write failures wrapped with WARN logs | Yes — `safeAudit(Runnable)` wraps every AuditWriter call in try/catch Throwable; durable chat-level PRE row from AuditAdvisor (REQUIRES_NEW, Phase 4) is independent. |
| T-06-20 | Reflective StructuredOutputValidationAdvisor accepted as trade-off | Yes — class absent in 1.1.4 so the reflective path never fires today; forward-compat only. |

No new threat-register surface discovered beyond the types enumerated in the plan's threat register.

## JetBrains File-Problems Check

JetBrains MCP tooling is not available in this executor agent's tool surface (MCP tools are stripped from sub-agents with a `tools:` frontmatter restriction per the upstream bug). The plan's explicit verification gates (`./gradlew :ai-agent:ai-agent:compileJava` + `./gradlew :ai-agent:ai-agent-starter:compileJava`) are both green on first run after each task. Operators with IntelliJ open can run `get_file_problems` on the 7 new files + 2 modified files to confirm.

## Commits

- `9a1c62f` — **Task 1**: RateLimitGuard + TokenBudgetGuard + IterationCounter + CompiledOutputScannerPattern + spring-boot-starter-cache dep in labeled Plan 06-03 block.
- `906e289` — **Task 2**: GuardedToolCallingManager (Spring AI 1.1.4 ToolCallingManager implementation, iteration cap, ToolGuard fan-out with Jackson JSON → Map adaptation, BLOCKED audit rows) + OutputScannerAdvisor (HIGHEST_PRECEDENCE+400, flag-and-pass-through, 8 KiB input cap).
- `a1e73e6` — **Task 3**: AiAgentGuardAutoConfiguration (CacheManager + scanner advisor + @Primary guarded manager via BeanFactory name lookup + reflective StructuredOutputValidationAdvisor) + AutoConfiguration.imports registration.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCounter.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/CompiledOutputScannerPattern.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java`
- FOUND: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java`
- FOUND (modified): `ai-agent/ai-agent/ai-agent.gradle` — new Plan 06-03 labeled block with `spring-boot-starter-cache`.
- FOUND (modified): `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — appended `AiAgentGuardAutoConfiguration`; 3 prior entries retained.
- FOUND: commit `9a1c62f` (Task 1)
- FOUND: commit `906e289` (Task 2)
- FOUND: commit `a1e73e6` (Task 3)
- BUILD SUCCESSFUL: `./gradlew :ai-agent:ai-agent:compileJava`
- BUILD SUCCESSFUL: `./gradlew :ai-agent:ai-agent-starter:compileJava`
