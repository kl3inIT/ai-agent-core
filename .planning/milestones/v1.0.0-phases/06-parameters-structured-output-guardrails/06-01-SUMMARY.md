---
phase: 06-parameters-structured-output-guardrails
plan: 01
subsystem: [parameters, guardrails, dtos, i18n]
tags: [foundation, dtos, exceptions, configuration-properties]
requires: []
provides:
  - com.vn.agent.guard.RateLimitExceededException
  - com.vn.agent.guard.TokenBudgetExhaustedException
  - com.vn.agent.guard.IterationCapExceededException
  - com.vn.agent.guard.StructuredOutputException
  - com.vn.agent.guard.AiAgentGuardProperties
  - com.vn.agent.parameters.Overrides
  - com.vn.agent.parameters.ParametersValidationException
  - com.vn.agent.parameters.AiParametersBody
  - com.vn.agent.entity.AiToolCallOutcome.FLAGGED
  - com.vn.agent.orchestration.ChatResponseDto (extended 8-component shape + GuardDenialInfo + denied() + ok())
  - i18n keys ai-agent.guard.* / ai-agent.parameters.* (EN + VI)
affects:
  - com.vn.agent.DefaultChatServiceImpl (one-site bridge edit to ChatResponseDto.ok())
tech-stack:
  added: []
  patterns:
    - "@ConfigurationProperties record with nested records + resolved* accessor defaults"
    - "typed guard exception with stable-key super() message + audit-only numeric field"
    - "Jakarta Bean Validation on record DTO for write-path YAML (D-05)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitExceededException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetExhaustedException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCapExceededException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/StructuredOutputException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/Overrides.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersValidationException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBody.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "ChatResponseDto kept as a single 8-component canonical record; 5-arg callers migrate via static factory ChatResponseDto.ok(...); sole existing call site (DefaultChatServiceImpl line 135) rewired in this plan as the one-site bridge edit."
  - "Guard exceptions use stable-key super() messages (\"rate-limit-exceeded\" etc.) never the numeric ceiling; ceilings stored on private field with getter for audit-only use (T-06-01, D-10)."
  - "ROLE_BREAK default regex uses bounded non-greedy quantifier .{0,2048}? and the 8 KiB consumer-input cap contract is documented in AiAgentGuardProperties.resolvedPatterns() Javadoc so Plan 04 cannot skip it (T-06-12)."
  - "@ConfigurationPropertiesScan on AIConfiguration already covers com.vn.agent; AiAgentGuardProperties binds automatically with no registration change needed."
metrics:
  duration: "~15 minutes"
  completed: "2026-04-21"
  tasks: 2
---

# Phase 6 Plan 01: Parameters + Guardrails Foundation Summary

Ship the exception catalogue, validated YAML write DTO, Overrides record, guard configuration properties, extended ChatResponseDto with denial shape, and the complete Phase 6 i18n key block in both locale files. Pure additions (plus the one-site DefaultChatServiceImpl bridge edit) so downstream plans 02/03/04/05 can compile against stable DTO / exception names.

## What Shipped

### Guard exception catalogue (com.vn.agent.guard)
- `RateLimitExceededException` (GUARD-04, D-13) — `super("rate-limit-exceeded")`; audit-only `int requestsPerMinuteCeiling` field.
- `TokenBudgetExhaustedException` (GUARD-03, D-14) — `super("token-budget-exhausted")`; audit-only `long ceiling` field.
- `IterationCapExceededException` (GUARD-02, D-16) — `super("iteration-cap-exceeded")`; audit-only `int maxIterations` field.
- `StructuredOutputException` (GUARD-06, D-19/D-20) — `super("structured-output-failed:<SimpleName>")`; carries `String lastRaw` + `Class<?> targetType`.

All four follow the `ToolVetoedException` shape (two standard `(String)` + `(String, Throwable)` constructors) plus a third audit-only numeric-ceiling constructor where applicable.

### Parameters types (com.vn.agent.parameters)
- `Overrides(String model)` record with `NONE` sentinel — D-01 model-only v1 sparse-merge.
- `ParametersValidationException` — write-path validation failures (D-05).
- `AiParametersBody` — Jackson/YAML record with Jakarta Bean Validation on the PARAM-02 field set (model, temperature, topP, maxTokens, systemPrompt, enabledTools, ragTopK, ragSimilarityThreshold); `@JsonPropertyOrder` for deterministic YAML round-trip.

### Guard configuration (com.vn.agent.guard.AiAgentGuardProperties)
- `@ConfigurationProperties("jmix.ai-agent.guard")` record binding with four nested records — `RateLimit`, `TokenBreaker`, `IterationCap`, `OutputScanner(Pattern)`.
- `resolvedRequestsPerMinute()=10`, `resolvedTokenCeiling()=100_000`, `resolvedMaxIterations()=6`, `resolvedPatterns()` → D-18 bundled three-pattern defaults (IGNORE_PREVIOUS_INSTRUCTIONS, SYSTEM_TAG_LEAK, ROLE_BREAK).
- ROLE_BREAK uses bounded non-greedy quantifier `.{0,2048}?` (no `.*` between role tokens). The Javadoc on `resolvedPatterns()` documents the 8 KiB input-cap consumer contract (T-06-12 ReDoS mitigation — Plan 04's OutputScannerAdvisor responsibility).

### ChatResponseDto extension
- Extended from 5 to 8 record components: adds `boolean flagged`, `String flaggedPatternKey`, `GuardDenialInfo guardDenial`.
- Nested `record GuardDenialInfo(String messageKey, Map<String, Object> params)`.
- Static factory `ChatResponseDto.denied(convId, runId, messageKey, params)` for typed guard denials (D-10/D-17 flag-and-pass-through where applicable; empty content on denial).
- Static factory `ChatResponseDto.ok(convId, runId, content, model, latencyMs)` as the 5-arg back-compat bridge — the sole existing caller (`DefaultChatServiceImpl` line 135) is rewired to `ok(...)` in this plan.

### Enum + i18n
- `AiToolCallOutcome.FLAGGED("FLAGGED")` added after `ERROR`. No Liquibase change required (column is VARCHAR).
- `messages.properties` + `messages_vi.properties` gain 12 new keys each (1 FLAGGED + 11 guard/parameters) with full key parity.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` → **BUILD SUCCESSFUL** after both Task 1 and Task 2.
- `grep -c "^ai-agent\.(guard|parameters)\." messages.properties` → **11**.
- `grep -c "^ai-agent\.(guard|parameters)\." messages_vi.properties` → **11**.
- `AiToolCallOutcome.FLAGGED=` appears in both locale files (1 match each).
- Eight new source files exist; three existing files modified; no stale references to the old 5-arg constructor.

## Deviations from Plan

None. Plan executed exactly as written.

- No Rule 1/2/3 auto-fixes were required — compile succeeded on the first `compileJava` run after each task.
- Jakarta Bean Validation package (`jakarta.validation.constraints.*`) resolved cleanly via Jmix's transitive deps; no extra `spring-boot-starter-validation` coordinate needed.
- `@ConfigurationPropertiesScan` on `AIConfiguration` already covers `com.vn.agent`, so `AiAgentGuardProperties` binds automatically — no autoconfig edit required (plan explicitly permitted falling back to Plan 03 if missing; the scan annotation is present, so no fallback was needed).

## Known Stubs / Deferred Work

- Guard exceptions and ChatResponseDto carry the **shape** only; actual throwing sites for `RateLimitExceededException` / `TokenBudgetExhaustedException` / `IterationCapExceededException` / `StructuredOutputException`, and the mapper that populates `GuardDenialInfo` are Plan 02 / 03 / 04 / 05's scope. This plan's goal is pure additions so downstream plans compile against stable names.
- `AiParametersBody` is written but not yet consumed — `ParametersService` (Plan 02) wires it into the YAML write path.
- `OutputScannerAdvisor` 8 KiB input cap is documented in `resolvedPatterns()` Javadoc but not yet enforced — Plan 04's responsibility.
- `AgentToolCallbacks` / `DefaultChatServiceImpl` still surface the 5-arg `ok(...)` bridge factory for the single success path; full orchestration rewrite (denial short-circuit, flag propagation) lands in Plan 04.

## Commits

- `28d58eb` — **Task 1**: exceptions + Overrides + FLAGGED enum + ChatResponseDto extension + one-site DefaultChatServiceImpl bridge to `ok()`.
- `6767cf6` — **Task 2**: AiParametersBody + AiAgentGuardProperties + 12 i18n keys (both locales).

## Threat Model Adherence

| Threat ID | Mitigation Plan Says | Implemented |
|-----------|----------------------|-------------|
| T-06-01 | Exception messages = stable keys; ceilings on private fields with getters for audit only | Yes — all four guard exceptions carry stable-key super() strings; numeric ceilings on private final fields |
| T-06-02 | GuardDenialInfo.params carries only i18n-safe values | Structural contract documented in Javadoc; consumer mapper (Plan 04) enforces |
| T-06-03 | Locale drift prevention | Both locale files gained the same 12 keys; grep parity count = 11/11 + 1/1 FLAGGED |
| T-06-04 | YAML strict-on-write | AiParametersBody Jakarta-validated; ParametersService (Plan 02) will enable `FAIL_ON_UNKNOWN_PROPERTIES` |
| T-06-12 | ROLE_BREAK bounded quantifier + 8 KiB input cap | Default regex uses `.{0,2048}?`; 8 KiB cap contract documented in Javadoc of `resolvedPatterns()` |

No new threat flags discovered; no surface was introduced beyond the types enumerated in the plan's threat register.

## JetBrains File-Problems Check

JetBrains MCP tooling was not available in this executor agent's tool surface (MCP tools are stripped from sub-agents with a `tools:` frontmatter restriction per upstream bug). The compile-check gate passed clean on first run for both tasks; no Java/XML/properties files produced warnings beyond the pre-existing Gradle deprecation notice. Operators with IntelliJ open can run `get_file_problems` on the 13 touched files to confirm; the plan's explicit verification gate (`./gradlew :ai-agent:ai-agent:compileJava`) is green.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitExceededException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetExhaustedException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/IterationCapExceededException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/StructuredOutputException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/Overrides.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersValidationException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBody.java`
- FOUND (modified): `AiToolCallOutcome.java` with `FLAGGED("FLAGGED")`
- FOUND (modified): `ChatResponseDto.java` — 8 components + GuardDenialInfo + denied() + ok()
- FOUND (modified): `DefaultChatServiceImpl.java` line 135 uses `ChatResponseDto.ok(...)`
- FOUND (modified): `messages.properties` + `messages_vi.properties` — 11 ai-agent.{guard,parameters}.* keys each + FLAGGED
- FOUND: commit `28d58eb` (Task 1)
- FOUND: commit `6767cf6` (Task 2)
- BUILD SUCCESSFUL: `./gradlew :ai-agent:ai-agent:compileJava`
