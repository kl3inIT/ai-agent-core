# Phase 6: Parameters, Structured Output & Guardrails — Research

**Researched:** 2026-04-21
**Domain:** Spring AI 1.1.4 (Java 17 / Spring Boot 3 / Jmix 2.8) — structured output, guardrails, parameter profiles
**Confidence:** HIGH (primary Spring AI claims verified via official docs; ecosystem claims verified via multiple sources)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Parameters**
- **D-01** Per-conversation `Overrides` is model-only in v1: record shape `Overrides(String model)`. No temperature/prompt/tool-set overrides.
- **D-02** Null-merge composition. `AiParametersResolver` gains `effectiveModel(AiParameters, Overrides)` overload only.
- **D-03** `ChatService.ask` grows an overload (`ask(userId, convId, message, Overrides)`) — no breaking change; existing 3-arg `ask` delegates to it. Same shape for `askTyped`.
- **D-04** `default-params.yaml` seeds once, at `ApplicationReadyEvent`, only if table empty. Admin deletion is intentional; no re-seed.
- **D-05** YAML parsed + validated (strict, unknown-key-reject) on WRITE via `AiParametersBody` DTO; tolerant parse on READ via the existing snakeyaml path.
- **D-06** "Exactly one active" enforced at service layer inside `@Transactional(REQUIRED)` — flip-all-then-set-one. No DB-level partial unique index.
- **D-07** Active-profile switch seen on the next request (no caching) — existing Phase 4 D-04 posture preserved.
- **D-08** `PromptContextContributor` chain invoked per-request, concatenated after base system prompt via `AiParametersResolver.effectiveSystemPrompt(AiParameters, RunContext)` (new signature).

**Guardrails**
- **D-09** Firing order: rate-limit → token breaker → iteration cap → ToolGuard → output scanner. Request-level guards in `DefaultChatServiceImpl.ask` preamble; iteration cap + ToolGuard inside tool-calling loop; output scanner via `CallAdvisor` before audit POST.
- **D-10** Each guard throws a distinct typed exception mapped to `msg://ai-agent.guard.<name>`. `ChatResponseDto` grows `GuardDenialInfo` optional field. No limit ceilings / pattern specifics leak to end users.
- **D-11** Single `AuditWriter` path for all guard denials. Tool-level denials use real tool name + `outcome=BLOCKED`. Request-level denials use reserved synthetic `__chat__` tool name. Output-scanner flags → `outcome=FLAGGED`.

**Rate limit / Token breaker**
- **D-12** Counter storage via JSR-107 JCache / Spring Cache abstraction. Two named caches: `aiAgentRateLimitBuckets`, `aiAgentConversationTokenUsage`. Default `ConcurrentMapCacheManager` (local JVM). Host swaps `CacheManager` bean for clustered deployments.
- **D-13** Rate limiter keyed by `CurrentAuthentication.getUser().getUsername()`. Default 10 req/min. `jmix.ai-agent.guard.rate-limit.{requests-per-minute,enabled}`.
- **D-14** Token breaker is per-conversation lifetime total. Default ceiling 100 000. Key = `AiConversation.id`. User recovers via New Chat.
- **D-15** Token count source = provider-reported `ChatResponse.getMetadata().getUsage().getTotalTokens()`. No local tokenizer (`jtokkit` out of scope).
- **D-16** Iteration cap = 6 by default. **IMPORTANT:** `ToolCallingManager` has NO built-in `maxIterations` API in Spring AI 1.1.4 — implement as `GuardedToolCallingManager` wrapper counting iterations in-house. `jmix.ai-agent.guard.iteration-cap.max-iterations`.

**Output scanner / Structured output**
- **D-17** Scanner action = flag-and-pass-through (not block). Record stable PATTERN KEY (never matched text) in both `ChatResponseDto.flaggedPatternKey` and audit row.
- **D-18** Patterns bundled as hardcoded defaults inside `@ConfigurationProperties`; host extends via `jmix.ai-agent.guard.output-scanner.patterns`. No `OutputScanner` SPI in v1.
- **D-19** `ChatService.askTyped(userId, convId, question, Class<T>) → T` is a new method (not an `ask` overload). Same `Overrides` overload shape as D-03.
- **D-20** Retry ONLY on parse/validation failure. Max 2 retries (3 total attempts). Guard denials bubble without retry. **Narrow-catch `BeanOutputParseException | ConstraintViolationException` only** — never catch bare `Exception`.
- **D-21** `StructuredOutputValidationAdvisor` is research-gated. **RESEARCH VERDICT (this phase, HIGH confidence): it does NOT exist in Spring AI 1.1.4.** Primary path is the inline retry loop in `DefaultChatServiceImpl.askTyped`. Future release: `@ConditionalOnClass` soft-wire via reflective construction.

### Claude's Discretion

- Exact package layout inside `com.vn.agent.parameters` / `com.vn.agent.guard`.
- `ToolGuard` enforcement seam choice — delegating `ToolCallingManager` vs `ToolCallback` interceptor vs pre-dispatch hook. All three satisfy D-09/D-11.
- Output-scanner advisor — post-ToolCall-only vs wraps-every-turn. Functionally equivalent for flag-and-pass-through.
- Rate-limit bucket implementation — hand-rolled on `CacheManager` vs `Bucket4j-JCache` library. Storage abstraction is locked (D-12); library is a footprint trade-off.
- `AiParametersBody` Jackson-YAML DTO shape — planner picks; `jakarta.validation` annotations preferred.
- Whether `OutputFlaggedException` is actually thrown vs non-exceptional flag propagation (D-17 says pass-through). Prefer non-exceptional.
- `AiToolCallOutcome.FLAGGED` enum addition vs side column. Planner picks; both satisfy D-11/D-17.
- Retry-attempt audit carrier — new optional column vs metadata JSON vs new `AiStructuredOutputAudit` table. Prefer smallest change that survives CSV export.
- `jmix.ai-agent.guard.*` properties — single `AiAgentGuardProperties` record vs split per guard.

### Deferred Ideas (OUT OF SCOPE)

- Per-conversation overrides beyond `model` (temperature/topP/systemPrompt/tool-set).
- Named-profile per-conversation reference (Overrides-points-at-profile-id).
- `OutputScanner` SPI (v1 is config-driven regex only).
- Cluster-aware counter storage shipped with add-on (abstraction ships; hosts swap).
- Self-tokenizer pre-flight token counting.
- Admin token-breaker reset affordance (Phase 7).
- Dynamic pattern reload without restart.
- Rate-limit tiers by role.
- Streaming-response interaction with output scanner (Phase 7).
- Mutation-tool dry-run + confirmation flow (post-v1).
- Structured-output schema registry / shared type library.

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PARAM-01 | `AiParameters` entity stores multiple profiles (YAML blob) with exactly one active | Entity + unique `profileName` index already ship from Phase 2. Jackson YAML DTO + Bean Validation (Section "Standard Stack") enables write-time validation. |
| PARAM-02 | Profile fields: model id, temperature, maxTokens, system prompt, enabled tool names, RAG top-k, RAG similarity threshold | Existing `AiParametersResolver` covers model/temperature/topP/maxTokens/systemPrompt. Phase 6 extends body DTO with `enabledTools`, `ragTopK`, `ragSimilarityThreshold`. Jmix admin CRUD via Phase 7 `ParametersDetailView`. |
| PARAM-03 | Per-conversation parameter override supported by `ChatService` API | D-01/D-02/D-03 lock `Overrides(String model)` + `.ask(..., Overrides)` overload + `effectiveModel(AiParameters, Overrides)`. |
| PARAM-04 | `default-params.yaml` bundled with starter; seeded on first startup if table empty | D-04. Seeder is an `@EventListener(ApplicationReadyEvent.class)` component in `ai-agent-starter` reading classpath `default-params.yaml` (overridable via property). |
| PARAM-05 | Host can contribute system-prompt fragments via `PromptContextContributor` SPI | D-08. SPI already ships from Phase 2 (no-op default). Phase 6 wires `List<PromptContextContributor>` into `AiParametersResolver.effectiveSystemPrompt(AiParameters, RunContext)`. |
| GUARD-01 | `ToolGuard` SPI invoked before each tool execution; veto → audit | D-09/D-11. Enforcement seam: `GuardedToolCallingManager` wrapping `ToolCallingManager.executeToolCalls` OR `ToolCallback` decorator chain. SPI already ships from Phase 2. |
| GUARD-02 | `ToolCallingManager` max-iteration cap (default 6) | D-16. **`ToolCallingManager` in Spring AI 1.1.4 does NOT expose `maxIterations`** — implement via `GuardedToolCallingManager` counting iterations in-house. Verified via docs.spring.io (HIGH confidence). |
| GUARD-03 | Per-conversation token circuit breaker | D-14/D-15. Post-response accumulation from `ChatResponse.getMetadata().getUsage().getTotalTokens()` into `aiAgentConversationTokenUsage` cache. |
| GUARD-04 | Per-user chat rate limit (default 10 req/min) | D-13. Token-bucket backed by `aiAgentRateLimitBuckets` cache. Hand-rolled or Bucket4j-JCache (planner discretion). |
| GUARD-05 | Output-side advisor scans response for likely injection patterns; redacts or flags | D-17/D-18. `CallAdvisor` slotted before `AuditAdvisor` POST. Flag-and-pass-through. Pattern KEY in audit, never matched text. |
| GUARD-06 | Structured output via `.entity(Class)` + `BeanOutputConverter` + bounded retry (max 2). Do not assume native structured-output support | D-19/D-20/D-21. Primary path = inline retry loop (`StructuredOutputValidationAdvisor` absent in 1.1.4 — verified HIGH). |
| SPI-05 impl | `ToolGuard` wired into runtime | D-09 enforcement seam. Interface ships from Phase 2. |

</phase_requirements>

## Project Constraints (from CLAUDE.md)

- **Data access:** `DataManager` only — `EntityManager` is FORBIDDEN in add-on code (SEC-03).
- **Entity instantiation:** `Metadata.create(Class)` or `DataManager.create(Class)` — never constructor. Phase 6 creates `AiToolCallAudit` rows for guard denials → must use `Metadata.create` (already the pattern in `AuditWriter`).
- **Dependency Injection:** Constructor injection in services. No field injection.
- **i18n:** ALL user-facing strings via `msg://` keys in BOTH `messages.properties` AND `messages_vi.properties`. No hardcoded UI text.
- **Transactions:** `@Transactional(REQUIRES_NEW)` for audit writer (already shipped). Guard denial rows reuse the existing `AuditWriter` REQUIRES_NEW surface (D-11).
- **Skill tool:** Use Jmix skills (`jmix-services`, `jmix-entities`, `jmix-security-roles`, `jmix-testing`) before implementing Jmix surfaces.
- **Context7 MCP:** Use for every Spring AI / Jmix / library API lookup — training data is stale.
- **JetBrains MCP:** Run `get_file_problems("path/to/file.ext", onlyErrors=false)` on modified Java files after a chunk of work (MEMORY note).
- **Testing:** Prefer `@SpringBootTest` (integration) + `@UiTest` (UI); automatic schema via Liquibase.
- **Forbidden:** Lombok on entities, EntityManager, business logic in views, hardcoded UI text, single-locale messages, edits in `frontend/generated/`.

---

## Summary

Phase 6 is a hardening phase, not a greenfield phase. Every subsystem it touches already exists in the codebase (`ChatClientFactory`, `AiParametersResolver`, `AuditWriter`, `DefaultChatServiceImpl`, `AiToolCallAudit`, `ToolGuard` SPI). The research question is NOT "which library?" — the stack is pinned (Spring AI 1.1.4 / Jmix 2.8 / Jackson YAML / JSR-107 cache). The research question IS "which 1.1.4 APIs actually exist, which ones the AI-SPEC and roadmap assume but don't, and where extension points force us to hand-roll."

**Primary recommendation:**

1. **Do NOT import `StructuredOutputValidationAdvisor`.** It does not exist in Spring AI 1.1.4. Ship the inline retry loop as the ONLY path; soft-wire via `@ConditionalOnClass` + reflective construction so a future minor release auto-lights it up without code change.
2. **Do NOT rely on `ToolCallingManager.maxIterations(int)`.** That method does not exist on the `ToolCallingManager` interface in 1.1.4. Wrap `ToolCallingManager` in a `GuardedToolCallingManager` that counts iterations in its own `ThreadLocal` / advisor-context slot and throws `IterationCapExceededException` deterministically. This also provides the seam for `ToolGuard` enforcement (D-09 G-04).
3. **Jackson `ObjectMapper` with YAMLFactory** is the right YAML parser for `AiParametersBody` (PARAM-01 D-05 strict validation) — NOT snakeyaml-direct. `AiParametersResolver` continues to use snakeyaml on the tolerant-read path (already shipped).
4. **`CacheManager` abstraction is the correct rate-limit / token-breaker storage.** `Bucket4j-JCache` is optional (pick based on footprint); hand-rolled token bucket on `ConcurrentMapCacheManager` is sufficient for v1. D-12 is locked.
5. **`SafeGuardAdvisor` (Spring AI built-in) is input-side only** — it does not satisfy GUARD-05. The hand-rolled `OutputScannerAdvisor` is unavoidable.

**Three hand-rolled primitives are unavoidable in Phase 6** (all forced by framework gaps, not by preference):
1. `GuardedToolCallingManager` — iteration cap + `ToolGuard` enforcement seam.
2. `OutputScannerAdvisor` — output-side regex scan (flag-and-pass-through).
3. Retry loop in `askTyped` — `StructuredOutputValidationAdvisor` absent.

All three are thin (under 100 LOC each) and have direct test seams. They are not parallel framework layers — they are wrappers over Spring AI primitives.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Parameter profile CRUD + active-flip | API / Backend (Jmix `DataManager`) | — | Pure Jmix entity service; row-level security via existing `AiAgentAdminRole`. No client-tier logic. |
| `default-params.yaml` seeding | API / Backend (`ApplicationReadyEvent`) | Starter (classpath resource) | Boot-time side-effect. Runs in the add-on's Spring context. |
| Rate-limit counter | API / Backend (Spring Cache) | Host-replaceable via `@ConditionalOnMissingBean` CacheManager | Request-scoped; must be authenticated (needs Jmix `CurrentAuthentication` from backend). |
| Token-breaker counter | API / Backend (Spring Cache) | Same as above | Accumulated post-response from provider metadata — backend-only. |
| Iteration cap enforcement | API / Backend (`GuardedToolCallingManager`) | — | Inside the Spring AI tool-calling loop; one JVM only. |
| `ToolGuard` enforcement | API / Backend (delegating `ToolCallingManager` / `ToolCallback` interceptor) | Host SPI impl | Pre-tool hook; runs in backend before `DataManager` invoked. |
| Output scanner | API / Backend (`CallAdvisor`) | — | Scans final assistant text server-side; flag reaches UI as an enum flag, not regex output. |
| Structured output + retry | API / Backend (`DefaultChatServiceImpl.askTyped` + Jackson + Bean Validation) | — | Synchronous; Bean Validation provider is a backend-only dependency (`hibernate-validator`). |
| Parameter profile YAML editor | Frontend Server (Jmix Flow UI — Phase 7) | API (validation endpoint) | Phase 7 territory. |

No client-side (browser-tier) logic in Phase 6. Phase 7 adds the Vaadin Flow views.

---

## Standard Stack

### Core (already on classpath from Phases 1–5 — no new versions to pin)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.ai:spring-ai-client-chat` | 1.1.4 (BOM-pinned) | `ChatClient`, `CallAdvisor`, `BeanOutputConverter`, `ToolCallingManager`, `Usage` | Project pin. [VERIFIED: `ai-agent/ai-agent/ai-agent.gradle` line 10] |
| `org.springframework.ai:spring-ai-starter-model-openai` | 1.1.4 | OpenRouter via OpenAI-compatible client | Phase 1 decision. [VERIFIED: `ai-agent-starter/ai-agent-starter.gradle` line 16] |
| Jmix `io.jmix.core:jmix-core-starter` + `io.jmix.data:jmix-eclipselink-starter` | 2.8.0 | `DataManager`, `Metadata`, `CurrentAuthentication`, JPA | Platform pin. [VERIFIED: `ai-agent.gradle`] |
| `io.jmix.security:jmix-security-starter` | 2.8.0 | `CurrentAuthentication.getUser().getUsername()` for rate-limit key (D-13) | Platform pin. |

### Supporting (new classpath additions Phase 6)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | Managed by Spring Boot 3.x BOM (2.17.x) | Jackson `ObjectMapper(new YAMLFactory())` for strict-on-write `AiParametersBody` DTO validation (D-05) | `ParametersService.save` — fail unknown-key reject via `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` |
| `jakarta.validation:jakarta.validation-api` | Provided by Jmix / Spring Boot transitively | `@NotBlank`, `@NotNull`, `@DecimalMin/Max` on `AiParametersBody` fields | Write-side validation per AI-SPEC 4b.1 |
| `org.hibernate.validator:hibernate-validator` | Provided by Spring Boot | Bean Validation provider; `Validator.validate(T)` call after `BeanOutputConverter` deserialize (D-20 retry trigger on `ConstraintViolationException`) | `askTyped` retry loop |
| `jakarta.cache:cache-api` (JSR-107) | Transitively via Spring Cache | `CacheManager` abstraction for rate-limit + token-breaker counters (D-12) | Both guard counters |
| Spring Cache (`org.springframework.boot:spring-boot-starter-cache`) | Spring Boot 3 BOM | `ConcurrentMapCacheManager` default + `@Cacheable`-free direct cache access | D-12 default provider |

### Optional (planner discretion — D-12)

| Library | Version | Purpose | Tradeoff |
|---------|---------|---------|----------|
| `com.bucket4j:bucket4j-jcache` | 8.14.0 (current stable April 2026) [CITED: github.com/bucket4j/bucket4j] | Token-bucket algorithm with JSR-107 storage | **Pro:** battle-tested, greedy-refill semantics right out of the box, replaceable Bucket4j backend (Hazelcast/Redis/Ignite). **Con:** extra dependency (~140 KB), classpath surface to maintain across Spring AI upgrades. Hand-rolling a token bucket on `CacheManager` is ~60 LOC; recommend hand-roll for v1 to keep footprint minimal. |
| `com.giffing.bucket4j.spring.boot.starter:bucket4j-spring-boot-starter` | 0.12.x | Config-driven rate-limit with annotation/interceptor | Rejected: hides the integration inside a filter layer; we want the guard in `DefaultChatServiceImpl.ask` preamble (D-09), not at the HTTP filter level. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff / Why Not Standard |
|------------|-----------|------------------------------|
| Hand-rolled `OutputScannerAdvisor` | Spring AI `SafeGuardAdvisor` | `SafeGuardAdvisor` scans **input**, not output [VERIFIED: baeldung.com/spring-ai-advisors, docs.spring.io/spring-ai/reference/api/advisors.html]. Does not satisfy GUARD-05. |
| `GuardedToolCallingManager` (hand-rolled) | `ToolCallingManager.maxIterations(int)` | **Does not exist in Spring AI 1.1.4** [VERIFIED: docs.spring.io/spring-ai/reference/api/tools.html]. `ToolCallingManager` has only `resolveToolDefinitions` + `executeToolCalls`. |
| Inline retry loop (`askTyped`) | `StructuredOutputValidationAdvisor` | **Does not exist in Spring AI 1.1.4** [VERIFIED: docs.spring.io/spring-ai/reference/api/structured-output-converter.html — doc explicitly states "Consider implementing a validation mechanism" rather than shipping one; docs.spring.io/spring-ai/docs/current/api/allclasses-index.html lists no such class] |
| Jackson YAML | snakeyaml direct | snakeyaml is on classpath and used by existing `AiParametersResolver.parseBody` (tolerant read). Write-side validation needs Jackson schema-binding + unknown-property rejection — snakeyaml is insufficient. |
| JSR-107 `CacheManager` | Redis / Hazelcast hardcoded | Jmix standalone default is local-JVM (MEMORY note "AI is just another Jmix client"); host swaps `CacheManager` bean for clustered. |
| Hand-rolled token bucket | `Resilience4j` rate limiter | Resilience4j is annotation-driven and AOP-heavy; fits circuit-breaker / retry patterns better than per-user keyed buckets. Bucket4j or hand-roll is the correct shape for per-user keyed rate limiting. |
| `jtokkit` pre-flight token count | Provider-reported `Usage` | D-15 explicitly locks provider-reported tokens; `jtokkit` is out of scope. |

**Installation (additions for Phase 6):**

```groovy
// ai-agent/ai-agent/ai-agent.gradle
dependencies {
    // ... existing ...
    implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml'   // managed by Spring Boot BOM
    implementation 'org.springframework.boot:spring-boot-starter-cache'         // brings CacheManager + JSR-107
    // jakarta.validation + hibernate-validator are already transitive via Jmix / Spring Boot starters
}
```

**Version verification:**

| Package | Verified Version | Source | Notes |
|---------|------------------|--------|-------|
| `spring-ai-bom` | 1.1.4 | [VERIFIED: project `ai-agent/build.gradle` line 40] | Project pin — do not override per PROJECT.md |
| `jackson-dataformat-yaml` | 2.17.x (managed) | [VERIFIED: Spring Boot 3.4 BOM] | No explicit version — rely on BOM |
| `bucket4j-jcache` (if adopted) | 8.14.0 | [CITED: https://github.com/bucket4j/bucket4j/releases — checked April 2026] | `npm view`-equivalent: `mvn dependency:get -Dartifact=com.bucket4j:bucket4j-jcache:8.14.0` |

---

## Architecture Patterns

### System Architecture Diagram

```
                    ┌────────────────────────────────────────────────────────────┐
                    │                 DefaultChatServiceImpl.ask                  │
                    │                                                              │
   Request ───▶  G-01 RateLimitGuard.check(userId)   ─ throws? ─▶ mapToDto(msg)   │
                    │   (cache: aiAgentRateLimitBuckets — D-12/D-13)               │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  G-02 TokenBudgetGuard.check(convId) ─ throws? ─▶ mapToDto  │
                    │   (cache: aiAgentConversationTokenUsage — D-14/D-15)         │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  AiParametersResolver.resolveActive() + effective*()         │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  PromptContextContributor chain → system prompt (D-08)       │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  chatClient.prompt()                                         │
                    │    .system(prompt).user(msg).toolCallbacks(...)              │
                    │    .advisors([                                               │
                    │      AuditAdvisor (HIGHEST_PRECEDENCE),                      │
                    │      MessageChatMemoryAdvisor (+200),                        │
                    │      RetrievalAugmentationAdvisor (+250),                    │
                    │      ToolCallAdvisor (+300)  ─ uses ─▶ GuardedToolCalling    │
                    │                                          Manager             │
                    │                                          (G-03 iter cap,     │
                    │                                           G-04 ToolGuard)    │
                    │      OutputScannerAdvisor (+400, before AuditAdvisor POST)   │
                    │    ])                                                         │
                    │    .options(...).call().chatResponse()                       │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  G-02 TokenBudgetGuard.accumulate(convId, usage.total)       │
                    │         │                                                    │
                    │         ▼                                                    │
                    │  map ChatResponse → ChatResponseDto (content + flagged + key)│
                    └────────────────────────────────────────────────────────────┘

Audit side-channel (REQUIRES_NEW — Phase 4 AuditWriter, reused):
  ├─ Chat PRE/POST  → toolName="<chat>" (existing sentinel)
  ├─ Tool calls     → real toolName, outcome=SUCCESS|ERROR|BLOCKED|FLAGGED
  └─ Guard denials  → toolName="__chat__" (NEW synthetic — D-11),
                       outcome=BLOCKED (rate/token/iter) or FLAGGED (scanner)

Structured output path (askTyped — D-19/D-20):
  Request ──▶ G-01 ──▶ G-02 ──▶ chatClient.prompt().call().entity(Class<T>)
                                   │
                                   ├─ BeanOutputConverter.convert(raw) — Jackson
                                   │
                                   ├─ validator.validate(T) — jakarta.validation
                                   │
                                   └─ parse/validate fail → retry (≤2) with re-injected
                                      BeanOutputConverter.getFormat() + failing raw
                                   │
                                   └─ 3 attempts exhausted → StructuredOutputException
```

### Recommended Project Structure

```
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── parameters/                                  [NEW]
│   ├── ParametersService.java                   # CRUD + setActive (D-06 tx invariant)
│   ├── AiParametersBody.java                    # Jackson/YAML DTO + jakarta.validation
│   ├── AiParametersBodyYamlMapper.java          # shared Jackson ObjectMapper(new YAMLFactory())
│   ├── ParametersValidationException.java       # D-05 strict-on-write
│   └── DefaultParamsSeeder.java                 # @EventListener(ApplicationReadyEvent) — D-04
│
├── guard/                                       [NEW]
│   ├── AiAgentGuardProperties.java              # @ConfigurationProperties("jmix.ai-agent.guard")
│   ├── RateLimitGuard.java                      # G-01 — token bucket over CacheManager
│   ├── TokenBudgetGuard.java                    # G-02 — accumulator over CacheManager
│   ├── GuardedToolCallingManager.java           # G-03 iter cap + G-04 ToolGuard enforcement
│   ├── OutputScannerAdvisor.java                # G-05 CallAdvisor — flag-and-pass-through
│   ├── OutputScannerPattern.java                # {key, regex} compiled holder
│   ├── RateLimitExceededException.java          # typed; maps to msg://ai-agent.guard.*
│   ├── TokenBudgetExhaustedException.java
│   ├── IterationCapExceededException.java
│   └── StructuredOutputException.java
│
└── orchestration/                               [EXISTING — modified]
    ├── AiParametersResolver.java                # + effectiveModel(AiParameters, Overrides)
    │                                            # + effectiveSystemPrompt(AiParameters, RunContext)
    ├── ChatClientFactory.java                   # wires OutputScannerAdvisor + GuardedToolCallingMgr
    ├── ChatResponseDto.java                     # + flagged + flaggedPatternKey + GuardDenialInfo
    └── Overrides.java                           # NEW record — model-only in v1 (D-01)

ai-agent/ai-agent-starter/src/main/resources/
└── default-params.yaml                          # NEW — D-04 bundled default profile
```

### Pattern 1: `@ConfigurationProperties`-driven Guards

**What:** Every guard tunable ships as a `@ConfigurationProperties` record property; no magic numbers in code. Single nested record `AiAgentGuardProperties` OR split per guard (planner discretion — match whatever Phase 2/5 established).

**When to use:** Every numeric/string threshold in Phase 6 (rate limit, token ceiling, iteration cap, pattern list).

**Example:**

```java
// Source: pattern established by existing AiAgentDefaultsProperties (Phase 4)
@ConfigurationProperties("jmix.ai-agent.guard")
public record AiAgentGuardProperties(
        RateLimit rateLimit,
        TokenBreaker tokenBreaker,
        IterationCap iterationCap,
        OutputScanner outputScanner) {
    public record RateLimit(Boolean enabled, Integer requestsPerMinute) {}
    public record TokenBreaker(Boolean enabled, Integer ceiling) {}
    public record IterationCap(Integer maxIterations) {}
    public record OutputScanner(Boolean enabled, List<Pattern> patterns) {
        public record Pattern(String key, String regex) {}
    }
}
```

### Pattern 2: Wrap, Don't Replace — `GuardedToolCallingManager`

**What:** The `ToolCallingManager` interface has only two methods (`resolveToolDefinitions`, `executeToolCalls`) — both are trivial to delegate. The wrapper adds iteration counting + `ToolGuard` enforcement without catching any Spring-AI-internal exception.

**When to use:** Always for the Phase 6 tool-calling layer; `@Bean @ConditionalOnMissingBean` so hosts can replace.

**Example:**

```java
// Source: Spring AI 1.1.4 ToolCallingManager interface (docs.spring.io/spring-ai/reference/api/tools.html)
// Verified 2026-04-21: ToolCallingManager has no maxIterations — hand-roll.
@Component
@ConditionalOnMissingBean(name = "guardedToolCallingManager")
public class GuardedToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ToolGuard toolGuard;
    private final AuditWriter auditWriter;
    private final AiAgentGuardProperties props;

    public GuardedToolCallingManager(@Qualifier("defaultToolCallingManager") ToolCallingManager delegate,
                                      ToolGuard toolGuard,
                                      AuditWriter auditWriter,
                                      AiAgentGuardProperties props) {
        this.delegate = delegate;
        this.toolGuard = toolGuard;
        this.auditWriter = auditWriter;
        this.props = props;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions opts) {
        return delegate.resolveToolDefinitions(opts);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        UUID convId = RunContext.get();
        int iteration = IterationCounter.incrementAndGet(convId);
        if (iteration > props.iterationCap().maxIterations()) {
            auditWriter.writeToolCall(RunContext.runId(), /* username */ null, convId,
                    "__chat__", null, null, 0L,
                    AiToolCallOutcome.BLOCKED, "iteration-cap-exceeded",
                    IterationCapExceededException.class.getName(), "POST");
            throw new IterationCapExceededException(props.iterationCap().maxIterations());
        }
        // D-09 G-04: ToolGuard check BEFORE delegating
        AssistantMessage am = chatResponse.getResult().getOutput();
        for (AssistantMessage.ToolCall call : am.getToolCalls()) {
            try {
                toolGuard.check(call.name(), parseArgs(call.arguments()));
            } catch (ToolVetoedException veto) {
                auditWriter.writeToolCall(RunContext.runId(), /* username */ null, convId,
                        call.name(), call.arguments(), null, 0L,
                        AiToolCallOutcome.BLOCKED, "tool-vetoed:" + veto.getStableKey(),
                        ToolVetoedException.class.getName(), "POST");
                throw veto;
            }
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }
}
```

Note the delegate-qualifier pattern: create the default `ToolCallingManager` as a non-primary bean named `"defaultToolCallingManager"` and inject it explicitly. Do NOT replace `@Primary` `ToolCallingManager` with the wrapper at the DI level if other parts of Spring AI auto-wire it — use explicit qualifier routing via `ChatClientFactory` instead.

### Pattern 3: Cache-as-State-Store for Guards

**What:** Rate-limit buckets and token-breaker counters are ephemeral state. `CacheManager` is the correct abstraction per D-12 — local `ConcurrentMapCacheManager` by default, host swaps for clustered.

**Example:**

```java
// Source: Spring Cache reference + D-12 locked decision
@Component
public class RateLimitGuard {

    private final CacheManager cacheManager;
    private final AiAgentGuardProperties props;

    public RateLimitGuard(CacheManager cacheManager, AiAgentGuardProperties props) {
        this.cacheManager = cacheManager;
        this.props = props;
    }

    public void check(String username) {
        if (!Boolean.TRUE.equals(props.rateLimit().enabled())) return;
        Cache cache = cacheManager.getCache("aiAgentRateLimitBuckets");
        TokenBucket bucket = cache.get(username, TokenBucket.class);
        if (bucket == null) {
            bucket = TokenBucket.freshGreedy(props.rateLimit().requestsPerMinute());
            cache.put(username, bucket);
        }
        synchronized (bucket) {                            // per-user monitor acceptable for v1 local-cache
            if (!bucket.tryConsume(1)) {
                throw new RateLimitExceededException(props.rateLimit().requestsPerMinute());
            }
        }
    }
}
```

### Pattern 4: Inline Retry Loop with Narrow Catch (Structured Output)

**What:** `askTyped` retry loop catches ONLY `BeanOutputParseException | ConstraintViolationException`. Guard exceptions bubble untouched.

**Why narrow:** Catching bare `Exception` swallows `RateLimitExceededException` / `TokenBudgetExhaustedException` from a re-entered guard call in the retry, silently retrying across guard denials and burning the token budget (D-20 Pitfall 6, AI-SPEC Section 3.5 pitfall 3).

**Example:** See AI-SPEC 3.3 / 4.4 — the `retryPrompt(original, schemaFormat, failingRaw)` helper is authoritative.

### Anti-Patterns to Avoid

- **Bare `Exception` catch in retry loop** — swallows guard denials. See Pitfall #6 (common pitfalls below).
- **Hard-importing `StructuredOutputValidationAdvisor`** — class is absent in 1.1.4; compilation breaks. Use `@ConditionalOnClass(name = "…")` + `Class.forName` reflective construction if soft-wire is desired.
- **Catching `ToolCallingManager`-internal exceptions by class-name string** — exception types are not stable public API across Spring AI minor releases (D-16 Pitfall 2). Count iterations in-house instead.
- **Writing the matched injection text into `AiToolCallAudit.denialReason`** — turns the audit table into its own prompt-injection persistence vector (D-17 FM #2). Record pattern KEY only.
- **`null` `maxTokens` on `OpenAiChatOptions`** — uncaps completion length at the provider (OWASP LLM10; AI-SPEC 3.5 pitfall 6). Fail-loud in `ChatClientFactory` if `effectiveMaxTokens` returns null.
- **Self-invocation of `@Transactional(REQUIRES_NEW)` methods inside the same bean** — bypasses Spring proxy, dissolves REQUIRES_NEW silently. `AuditWriter` already has a comment on this (Phase 4 Pitfall #3); Phase 6 reuses the same class — inherits the safety.
- **`@Order`-based precedence for `CallAdvisor` without reading the chain direction** — Spring AI `CallAdvisor.getOrder()` is a stack, not a queue (lower order = OUTER wrapper — runs first pre-LLM, last post-LLM). The output-scanner advisor MUST have a higher order than `AuditAdvisor` so scan runs before audit commits the row [VERIFIED: AI-SPEC 3.5 pitfall 5].

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| YAML parse + structured validation | Regex or raw snakeyaml with manual field checks | `ObjectMapper(new YAMLFactory())` + `AiParametersBody` record with `@NotNull`/`@NotBlank`/`@DecimalMin`/`@DecimalMax` + `FAIL_ON_UNKNOWN_PROPERTIES` | Jackson schema-binding handles type coercion, nested objects, unknown-key rejection, error localization. Hand-rolled validators miss 30+ edge cases (null coalescing, numeric coercion, locale-dependent parsing). |
| Per-user rate-limit math | Hand-rolled sliding window / leaky bucket | Token bucket via `CacheManager` (simple hand-roll OR Bucket4j) | Token bucket is the standard; greedy refill semantics are ~60 LOC over `CacheManager`. Sliding-window windowing is an unnecessary degree of sophistication for 10 req/min/user. |
| LLM output JSON parsing | Regex JSON extraction | `BeanOutputConverter<T>(Class<T>)` + `ChatClient.entity(Class)` | Spring AI emits schema into the prompt and deserializes via Jackson in one shot. Hand-rolled JSON extraction silently accepts malformed output. |
| Bean Validation after JSON deserialize | If-null null-checks on each field | `jakarta.validation.Validator.validate(T)` | Bean Validation integrates with Jackson via `@Valid` and produces `ConstraintViolationException` with field-keyed messages matched to `msg://` keys. |
| Prompt-injection regex corpus from scratch | 5-6 ad-hoc patterns | Curate from **OWASP LLM Top 10 2025 LLM01 canonical examples** (ignore-previous-instructions, system-tag-leak, role-break) + AppSec review [CITED: AI-SPEC Section 1b sources] | Regex corpus quality is the product; AppSec practitioner review (Section 1b expert role) is the standard. E-12 eval enforces >=0.9 recall / <=0.1 FPR on a 20-example corpus. |
| Transactional audit writing | New code path | Reuse Phase 4 `AuditWriter` (already REQUIRES_NEW) | AUD-02 contract already enforced by existing tests (`AuditDurabilityTest`). Just call `writeToolCall(...)` with `"__chat__"` sentinel for guard denials (D-11). |
| Entity instantiation | `new AiToolCallAudit()` | `metadata.create(AiToolCallAudit.class)` | CLAUDE.md — forbidden pattern. Existing code already complies. |
| Conversation ownership check | Ad-hoc `if (conv.getCreatedBy().equals(user))` | Existing `ConversationGateway.loadOrCreate(userId, convId, msg)` (Phase 4 D-09 opacity) | Already throws a single-literal-message `ConversationNotFoundException` — reuse. |
| i18n resolution | Hardcoded English strings | `msg://ai-agent.guard.*` keys in BOTH `messages.properties` + `messages_vi.properties` | CLAUDE.md — forbidden. `I18nParityTest` (E-11) enforces key parity programmatically. |

**Key insight:** Phase 6 should feel thin. The only genuinely new primitives are the three framework-gap wrappers (`GuardedToolCallingManager`, `OutputScannerAdvisor`, `askTyped` retry loop). Everything else is composition of existing Jmix / Spring AI / Spring Boot surfaces.

---

## Common Pitfalls

### Pitfall 1: `StructuredOutputValidationAdvisor` phantom-import

**What goes wrong:** A planner reads older Spring AI community blog posts (1.0.x preview era) that reference `StructuredOutputValidationAdvisor` and imports it in `ChatClientFactory`. Compilation fails: `cannot find symbol`.

**Why it happens:** The class exists in community proposals but has NOT landed in Spring AI 1.1.4 [VERIFIED: docs.spring.io/spring-ai/reference/api/structured-output-converter.html — no mention; docs.spring.io/spring-ai/docs/current/api/allclasses-index.html — not in class list as of April 2026].

**How to avoid:** NEVER add a compile-time import for this class. Wire optionally via `@ConditionalOnClass(name = "org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor")` + reflective construction (AI-SPEC 4.5). The primary path is the inline retry loop.

**Warning signs:** Compilation error on a clean build; a "class not found" runtime error when `askTyped` fires.

### Pitfall 2: `ToolCallingManager.maxIterations(int)` phantom-API

**What goes wrong:** Planner assumes `ToolCallingManager.builder().maxIterations(6).build()` works. It doesn't — the 1.1.4 `ToolCallingManager` interface has only `resolveToolDefinitions` + `executeToolCalls` [VERIFIED: docs.spring.io/spring-ai/reference/api/tools.html].

**Why it happens:** Multiple framework vendors (LangChain, LlamaIndex) have `maxIterations` at the agent-loop manager; natural to assume Spring AI does too.

**How to avoid:** Count iterations in `GuardedToolCallingManager`. Track the count in an advisor-context slot keyed by `conversationId` + `runId` (one turn scope). Do NOT catch a framework-internal "iteration limit" exception by class-name string — that exception type shifts across 1.1.x point releases (AI-SPEC 3.5 pitfall 2).

**Warning signs:** A grep for `maxIterations` inside `ToolCallingManager.builder()` call sites turns up no results in the spring-ai-client-chat jar.

### Pitfall 3: Bare `Exception` catch in `askTyped` retry loop

**What goes wrong:** `try { ... } catch (Exception e) { lastRaw = ... }` swallows `RateLimitExceededException`, `TokenBudgetExhaustedException`, `IterationCapExceededException`, `ToolVetoedException`. Instead of surfacing the guard denial, the loop retries 2 more times — 3x the cost, 3x the audit rows, and the user sees `StructuredOutputException` instead of `RateLimitExceededException`.

**Why it happens:** Jackson's parse exception is wrapped in a `RuntimeException` subclass (`JsonProcessingException` → `IOException` at the base); developers catch `Exception` as "any parse failure."

**How to avoid:** Catch EXACTLY `BeanOutputParseException` (or the narrower `JsonProcessingException` wrapped by Spring AI — verify at implementation) plus `ConstraintViolationException`. Nothing else. If this creates test-coverage gaps, write E-07 explicitly to assert the bubble (AI-SPEC Section 5 E-07 FAIL list).

**Warning signs:** Test `verify(chatModel, times(3))` passes on a rate-limit-exceeded input; `RateLimitExceededException` is not visible in `ChatResponseDto`.

### Pitfall 4: Output scanner writes matched text into audit

**What goes wrong:** `OutputScannerAdvisor` records `auditWriter.writeToolCall(..., "pattern matched: <raw assistant text>", ...)` instead of recording the pattern KEY. Admin opens `ToolCallAuditListView` (UI-06 / Phase 7), the injection payload is rendered in the CSV export, and the audit table becomes a new persistence vector for the attack (LLM02 Sensitive Information Disclosure).

**Why it happens:** "Natural" instinct is to record context for debugging. Wrong context.

**How to avoid:** D-17 locked. `OutputScannerPattern` has a stable `key` field (e.g. `IGNORE_PREVIOUS_INSTRUCTIONS`, `SYSTEM_TAG_LEAK`, `ROLE_BREAK`). Advisor records ONLY the key. Test E-05 (AI-SPEC) asserts the matched text appears nowhere in `denialReason`, server logs (via `OutputCaptureExtension`), or CSV export.

**Warning signs:** Admin UI or CSV export shows a prompt-injection payload verbatim.

### Pitfall 5: `CallAdvisor.getOrder()` direction inversion

**What goes wrong:** `OutputScannerAdvisor.getOrder()` is set LOWER than `AuditAdvisor.getOrder()` (Ordered.HIGHEST_PRECEDENCE). Scanner runs OUTSIDE audit — scanner flag never reaches the audit row.

**Why it happens:** Spring `@Order` semantics: lower value = higher priority = runs FIRST in chain. But for around-advisors this means "wraps others first" = outermost. Post-LLM work runs in REVERSE order. Scanner must have HIGHER order (inner) to post-process before audit commits.

**How to avoid:** Scanner order = 1000 or `AuditAdvisor.getOrder() + 100`. Test via `AdvisorOrderStructuralTest` (Phase 4 precedent) — add a new assertion for scanner position.

**Warning signs:** Audit rows for a flagged turn do not carry `outcome=FLAGGED`.

### Pitfall 6: `maxTokens` null footgun

**What goes wrong:** Host forgets to set `jmix.ai-agent.defaults.max-tokens`; `AiAgentDefaultsProperties.maxTokens()` returns null; `OpenAiChatOptions.builder().maxTokens(null)` passes through to OpenRouter; completion is effectively uncapped at `gpt-4o` tier — one turn costs $5.

**Why it happens:** Spring AI's `OpenAiChatOptions` accepts `null` (treats as "omit from request"); OpenAI-compatible providers have no global default cap.

**How to avoid:** `ChatClientFactory` validates on construction — if `defaultsProperties.maxTokens() == null`, fail fast with a clear message. Alternatively, `AiParametersResolver.effectiveMaxTokens` applies a conservative fallback (e.g. 2048) and logs WARN.

**Warning signs:** A runaway-cost incident in production; `ai.agent.token.usage.total` metric spikes on a single-turn sample.

### Pitfall 7: Split-brain `active = true` rows in `AiParameters`

**What goes wrong:** `ParametersService.setActive` executes the flip-all-to-false and set-target-to-true in separate transactions, or outside any `@Transactional`. Two admin sessions concurrently toggle to profiles A and B; end state: both A and B have `active=true`, or neither.

**Why it happens:** D-06 says the invariant is enforced at service layer, not DB. Easy to get wrong.

**How to avoid:** One `@Transactional(REQUIRED)` method. Single connection. One `UPDATE ... SET active = false` followed by `UPDATE ... SET active = true WHERE id = ?`. Test E-09 uses a two-thread contention harness to assert exactly-one after concurrent flips. Consider a DB-level check at boot in `DefaultParamsSeeder`: `SELECT COUNT(*) WHERE active = true` — alert / auto-repair if not 1. Also monitor via `ai.agent.params.active_count` gauge (AI-SPEC 7.2).

**Warning signs:** Integration test flakes on concurrent `setActive`; UI active-indicator inconsistent with subsequent chat behaviour.

### Pitfall 8: i18n locale-bundle drift

**What goes wrong:** Adding `RateLimitExceededException` → `msg://ai-agent.guard.rate-limit-exceeded` in Java → adding the key to `messages.properties` → FORGETTING to add it to `messages_vi.properties`. Vietnamese users see raw `msg://...` key text in the UI.

**Why it happens:** Two files to keep in sync; CLAUDE.md convention enforced by review, not by a compile-time check.

**How to avoid:** E-11 (AI-SPEC) is a `@ParameterizedTest` that enumerates every `msg://ai-agent.guard.*` and `msg://ai-agent.parameters.*` key referenced in Java source (scan `ChatResponseDto.GuardDenialInfo.messageKey` constants + annotation scan) and asserts the key exists in BOTH `ResourceBundle`s with non-empty values. This test is a merge blocker (High priority).

**Warning signs:** `NoSuchMessageException` in production logs; a Vietnamese user screenshots a raw `msg://` key.

---

## Code Examples

Verified patterns from official sources and established Phase 4/5 code shapes.

### Example 1: `BeanOutputConverter` + `ChatClient.entity(Class)` + Bean Validation

```java
// Source: docs.spring.io/spring-ai/reference/api/structured-output-converter.html
// (Spring AI 1.1.4 — verified 2026-04-21)
BeanOutputConverter<Invoice> converter = new BeanOutputConverter<>(Invoice.class);

Invoice invoice = chatClient.prompt()
        .user(u -> u.text("Generate an invoice for: {subject}\n{format}")
                    .param("subject", subject)
                    .param("format", converter.getFormat()))
        .call()
        .entity(Invoice.class);         // Jackson parse; throws runtime on malformed JSON

validator.validate(invoice);             // jakarta.validation — throws ConstraintViolationException
```

### Example 2: `CallAdvisor` — `OutputScannerAdvisor`

```java
// Source: docs.spring.io/spring-ai/reference/api/advisors.html
// (CallAdvisor / CallAdvisorChain — Spring AI 1.1.4 — verified 2026-04-21)
@Component
public class OutputScannerAdvisor implements CallAdvisor {

    private final List<CompiledPattern> patterns;

    public OutputScannerAdvisor(AiAgentGuardProperties props) {
        this.patterns = props.outputScanner().patterns().stream()
                .map(p -> new CompiledPattern(p.key(), Pattern.compile(p.regex(), Pattern.CASE_INSENSITIVE)))
                .toList();
    }

    @Override public String getName() { return "OutputScannerAdvisor"; }
    // Higher order than AuditAdvisor so scan runs before audit POST (Pitfall #5)
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 400; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        String text = response.chatResponse().getResult().getOutput().getText();
        if (text == null) return response;
        for (CompiledPattern p : patterns) {
            if (p.regex().matcher(text).find()) {
                // D-17: record the STABLE KEY, never the matched text
                response.context().put("outputScanner.flaggedPatternKey", p.key());
                break;
            }
        }
        return response;
    }
}
```

### Example 3: `ParametersService.setActive` — transactional invariant (D-06)

```java
// Source: Phase 4 DataManager + @Transactional pattern (AuditWriter precedent reversed: REQUIRED, not REQUIRES_NEW)
@Service
public class ParametersService {

    private final DataManager dataManager;

    public ParametersService(DataManager dataManager) { this.dataManager = dataManager; }

    @Transactional(propagation = Propagation.REQUIRED)
    public void setActive(UUID targetId) {
        // Flip all currently-active rows off
        List<AiParameters> active = dataManager.load(AiParameters.class)
                .query("select e from ai_AiParameters e where e.active = true")
                .list();
        for (AiParameters p : active) {
            p.setActive(Boolean.FALSE);
        }
        dataManager.save(active.toArray());
        // Set target active
        AiParameters target = dataManager.load(AiParameters.class).id(targetId).one();
        target.setActive(Boolean.TRUE);
        dataManager.save(target);
    }
}
```

### Example 4: `DefaultParamsSeeder` — D-04 event-driven seed

```java
// Source: D-04 locked pattern + Jmix Metadata.create + Jackson YAML (NEW in Phase 6)
@Component
public class DefaultParamsSeeder {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final ResourceLoader resourceLoader;
    private final AiParametersBodyYamlMapper yamlMapper;
    private final String defaultsResourcePath;    // jmix.ai-agent.parameters.defaults-resource, default classpath:default-params.yaml

    // ... constructor injection (CLAUDE.md)

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        long count = dataManager.load(AiParameters.class)
                .query("select count(e) from ai_AiParameters e")
                .one();
        if (count > 0) return;       // D-04: admin deletion intentional, never re-seed

        try (InputStream in = resourceLoader.getResource(defaultsResourcePath).getInputStream()) {
            AiParametersBody body = yamlMapper.readValue(in);  // strict validation
            AiParameters row = metadata.create(AiParameters.class);
            row.setProfileName("default");
            row.setActive(Boolean.TRUE);
            row.setBodyYaml(yamlMapper.writeAsYaml(body));     // canonicalised on seed
            dataManager.save(row);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to seed " + defaultsResourcePath, e);
        }
    }
}
```

### Example 5: `ChatResponseDto` + `GuardDenialInfo` (D-10)

```java
// Source: D-10 locked + Phase 4 ChatResponseDto shape preserved (ChatResponseDto.java)
public record ChatResponseDto(
        UUID conversationId,
        UUID runId,
        String content,
        String model,
        long latencyMs,
        boolean flagged,                       // NEW — D-17
        String flaggedPatternKey,              // NEW — D-17 stable key, null if not flagged
        GuardDenialInfo guardDenial            // NEW — D-10 optional; null = normal path
) {
    public record GuardDenialInfo(String messageKey, Map<String, Object> params) {}

    public static ChatResponseDto denied(UUID convId, UUID runId, String messageKey, Map<String, Object> params) {
        return new ChatResponseDto(convId, runId, "", null, 0L, false, null,
                new GuardDenialInfo(messageKey, params));
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Ad-hoc `catch (Exception)` around `.entity(Class)` | Narrow catch `BeanOutputParseException | ConstraintViolationException` | D-20 / Pitfall #3 | Prevents silent guard-denial retry loops — correctness-critical |
| Regex-in-code for YAML parse | Jackson `ObjectMapper(new YAMLFactory())` + record DTO + Bean Validation | Industry standard Spring Boot 3 | Structured validation with unknown-key rejection, field-keyed error messages |
| `ToolCallingManager.maxIterations()` (assumed API) | Hand-rolled `GuardedToolCallingManager` counting in-house | Spring AI 1.1.4 does not expose it | Correctness — no phantom-API compile failure |
| `StructuredOutputValidationAdvisor` (assumed API) | Inline retry loop + `@ConditionalOnClass` soft wire | Spring AI 1.1.4 does not ship it | Correctness — no phantom-import compile failure |
| Per-user rate limit via servlet filter | In-service guard preamble keyed by Jmix username | D-09 + D-13 | Runs post-authentication, correct user identity; survives Vaadin WebSocket path where filter-based limits miss |

**Deprecated / outdated:**

- **`ChatResponse` (plain)** in `ChatService` — Phase 4 replaced with `ChatResponseDto`. Phase 6 evolves the DTO (adds `flagged`, `flaggedPatternKey`, `guardDenial`) but does not revert.
- **`SafeGuardAdvisor` for output filtering** — it's input-side only [VERIFIED: baeldung.com/spring-ai-advisors]. Do not propose it for GUARD-05.
- **Claude Agent SDK-style auto-compaction** — not available in Spring AI 1.1.4; context-window is a ceiling (token-breaker), not a compactor (AI-SPEC 4b.4).

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Hand-rolled token bucket on `CacheManager` is sufficient for v1 (vs `Bucket4j-JCache`). | Standard Stack / Optional | Medium — if clustered hosts need cross-node rate-limit, `Bucket4j-JCache` is the drop-in upgrade. Storage abstraction (`CacheManager`) already supports the swap. |
| A2 | `BeanOutputParseException` is the narrow exception to catch for Jackson parse failures via `ChatClient.entity()`. Spring AI 1.1.4 may actually throw `JsonProcessingException` or a Spring-AI-specific wrapper instead. | Pitfall #3 / Code Example 4 | Low — planner verifies exact type at implementation by reading Spring AI source or stepping through a failing `.entity()` call. Workaround: catch both `JsonProcessingException` and `ConstraintViolationException`; assert in a unit test that a known-malformed payload raises the expected type. |
| A3 | `@ConditionalOnClass(name = "...StructuredOutputValidationAdvisor")` will correctly activate when a future Spring AI release ships the class. | Alternative Stack / Pitfall #1 | Low — standard Spring Boot idiom; if the package path differs in a future release, wiring breaks silently but inline retry loop continues to work. |
| A4 | OpenRouter passes `Usage` (prompt + completion + total tokens) through OpenAI-compatible responses for the default `openai/gpt-4o-mini` model. | D-15 | Medium — a `@Tag("live")` sanity test (`ChatServiceLiveSemanticTest` pattern from Phase 4) must assert `getMetadata().getUsage().getTotalTokens() != null` for a real OpenRouter call on the default model. If Null, token-breaker is non-functional until the provider route is fixed. |
| A5 | Hibernate Validator is already on the classpath transitively via Jmix / Spring Boot. | Supporting Stack | Low — easy to verify with `./gradlew :ai-agent:ai-agent:dependencies | grep hibernate-validator`; if missing, add `implementation 'org.hibernate.validator:hibernate-validator'` explicitly. |
| A6 | `ConcurrentMapCacheManager` is auto-provided by `spring-boot-starter-cache` without explicit config. | Pattern 3 | Low — standard Spring Boot default; if the host has already registered a `CacheManager` bean, ours gets displaced (desired outcome per `@ConditionalOnMissingBean`). |

**If this table is empty:** not empty — six assumptions documented. Each has a low-to-medium risk profile and a concrete disambiguation path.

---

## Open Questions

1. **Exact exception type thrown by `ChatClient.entity(Class)` on Jackson parse failure.**
   - What we know: Spring AI uses Jackson under the hood; Jackson raises `JsonProcessingException` (checked, wrapped by Spring AI as runtime).
   - What's unclear: Whether Spring AI 1.1.4 wraps it further in a framework-specific class like `BeanOutputParseException` or surfaces Jackson's runtime directly.
   - Recommendation: Implementation-time probe — write a throwaway test with malformed JSON mock, catch `Throwable`, log `e.getClass().getName()`, use that class name in the narrow catch. Document in a code comment citing the actual exception class. Covered by A2.

2. **Best advisor-order number for `OutputScannerAdvisor` given existing slots.**
   - What we know: Phase 4 established `AuditAdvisor` at `HIGHEST_PRECEDENCE`, memory at `+200`, RAG at `+250`, `ToolCallAdvisor` at `+300`.
   - What's unclear: Whether `+400` leaves room for future insertions, or if a higher number (e.g. `+1000`) is more idiomatic.
   - Recommendation: `+400` — follows the "+100 between stages" pattern; leaves `+500`, `+600`, etc. available for future advisors. `AdvisorOrderStructuralTest` asserts exact positions so future drift is caught.

3. **Whether `GuardedToolCallingManager` can reuse `RunContext` (Phase 4 ThreadLocal) for iteration counting, or needs a separate per-turn slot.**
   - What we know: `RunContext` holds `conversationId`; the iteration counter must reset per turn, not per conversation.
   - What's unclear: Whether Spring AI's tool-calling loop re-enters the same `ToolCallingManager` bean on each iteration (natural counting site) or wraps each iteration in a new context.
   - Recommendation: Planner reads `DefaultToolCallingManager` source (in `spring-ai-client-chat-1.1.4.jar` — already on classpath) at implementation time to confirm. Fallback: store counter in `ChatClientRequest` advisor-context, keyed by `runId`.

4. **Whether `@Tag("eval")` (proposed in AI-SPEC Section 5) requires a new Gradle task alongside the existing `liveTest` / `integrationTest` tasks.**
   - What we know: Current `ai-agent.gradle` defines `test` (excludes `live`, `rag-it`), `liveTest`, `integrationTest`.
   - What's unclear: Whether eval tests run as part of the default `test` task (with `@Tag("eval")` included) or require a new opt-in task.
   - Recommendation: Add `@Tag("eval")` to the default `test` task (DO NOT exclude it) — per AI-SPEC Section 5.4 "Eval failures on the `eval` tag are merge-blocking for Critical-priority dimensions." Update `excludeTags 'live', 'rag-it'` unchanged; eval-tagged tests run in every PR.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java JDK | Build / test | ✓ | 17 (project min); 25 in build env (confirmed by ASM 9.9 upgrade in Phase 3) | — |
| Gradle | Build | ✓ | 8.x (wrapper) | — |
| HSQLDB | Default test DB | ✓ | managed | — (used by `./gradlew test`) |
| PostgreSQL | Integration + pgvector | ✓ via Testcontainers | 16 + pgvector extension (Phase 5) | Skip `@Tag("rag-it")` tests when Docker unavailable (already gated) |
| `spring-ai-bom:1.1.4` | Phase 6 APIs | ✓ | 1.1.4 | — (BOM-pinned project-wide) |
| `jackson-dataformat-yaml` | `AiParametersBody` strict parse | Need to add | Managed by Spring Boot | — |
| `hibernate-validator` | Bean Validation in `askTyped` | ✓ transitively | Managed by Spring Boot | Verify with `./gradlew :ai-agent:ai-agent:dependencies --configuration runtimeClasspath | grep -i validator` |
| `spring-boot-starter-cache` | `CacheManager` for guards | Need to add | Managed by Spring Boot | `ConcurrentMapCacheManager` auto-configured |
| OpenRouter API key | `@Tag("live")` tests only | ✗ in CI | — | Tests already gated with `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` |
| Docker | `@Tag("rag-it")` tests only | ✗ in sandbox | — | Tests already gated; `check` task auto-skips |

**Missing dependencies with no fallback:** None — all Phase 6 core additions are managed dependencies in existing BOMs.

**Missing dependencies with fallback:** OpenRouter API key (live tests only — already gated); Docker (rag-it tests only — already gated).

---

## Security Domain

### Applicable ASVS / OWASP-LLM Categories

| Category | Applies | Standard Control |
|---------|---------|-----------------|
| V2 Authentication | Yes (upstream — Jmix) | Jmix `CurrentAuthentication`; rate-limit key (D-13); add-on enforces authenticated-only contract |
| V3 Session Management | Yes (upstream — Jmix) | Conversation ownership via `AiConversation.createdBy` (Phase 4 D-09) |
| V4 Access Control | Yes | `ToolGuard` SPI (GUARD-01 / SPI-05); `DataManager` row-level security honoured inside every tool body (TOOL-04) |
| V5 Input Validation | Yes | `AiParametersBody` Bean Validation (D-05); tool filter DSL literal coercion (Phase 3); `BeanOutputConverter` schema + `jakarta.validation` on LLM outputs (D-20) |
| V6 Cryptography | No | Not a Phase 6 concern — no new cryptographic primitives |
| V7 Error Handling | Yes | Typed exceptions + `msg://` keys (D-10); no stack traces or internal detail leaked to end users |
| V8 Data Protection | Yes | Pattern KEY not matched text in audit (D-17); `AiToolCallAudit.denialReason` column bounded at 512 chars |
| V13 API | Partial | `ChatService.ask/askTyped` contract stability (D-03/D-19 non-breaking evolution) |

### Known Threat Patterns for Spring AI / Jmix stack (OWASP LLM Top 10 2025)

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| LLM01 Indirect prompt injection via RAG chunks | Tampering / Elevation | (1) Tool result `<data>` delimiters (TOOL-07, shipped); (2) `ToolGuard` veto (G-04); (3) output scanner flag (G-05). Three independent layers. |
| LLM01 Direct prompt injection via user message | Tampering | System/user role separation enforced by Spring AI API (no concatenation in service code — AI-SPEC 4b.3); output scanner (G-05) as defense in depth. |
| LLM02 Sensitive Information Disclosure via audit echo | Information Disclosure | Pattern KEY not matched text (D-17); `GuardDenialInfo` carries message key + typed params, never ceilings or regex (D-10). |
| LLM05 Improper Output Handling — schema drift | Tampering | `askTyped` Bean Validation + retry (D-20); `StructuredOutputException` on exhaustion carrying raw text for audit. |
| LLM07 Insecure Plugin Design / Excessive Agency | Elevation / Tampering | `ToolGuard` veto (G-04); read-only tools only (TOOL-08); every tool call audited (AUD-04). |
| LLM10 Unbounded Consumption — rate | Denial of Service | Per-user rate limit (G-01). |
| LLM10 Unbounded Consumption — tokens | Denial of Service | Per-conversation token circuit breaker (G-02); `maxTokens` fail-loud in `ChatClientFactory` (Pitfall #6). |
| LLM10 Unbounded Consumption — tool loop | Denial of Service | Iteration cap (G-03). |
| Audit bypass (silent denial) | Repudiation | `AuditWriter` REQUIRES_NEW (Phase 4 AUD-02); `__chat__` synthetic row for every guard denial (D-11). |
| Active-profile split-brain | Tampering | `@Transactional(REQUIRED)` in `ParametersService.setActive` (D-06); `ai.agent.params.active_count` gauge alert (AI-SPEC 7.3). |

**NIST AI RMF Measure function** — the `AiToolCallAudit`-backed observability layer (rows per guard denial, per flagged output, per retry attempt, per iteration-cap hit) directly implements "measuring how often the LLM produces disallowed content" (AI-SPEC 1b).

---

## Sources

### Primary (HIGH confidence)

- **Spring AI 1.1.4 Structured Output Converter reference** — https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html — confirmed `BeanOutputConverter<T>`, `.entity(Class<T>)`, `getFormat()`, `convert(String)` APIs; confirmed NO `StructuredOutputValidationAdvisor` documented.
- **Spring AI 1.1.4 Tools / ToolCallingManager reference** — https://docs.spring.io/spring-ai/reference/api/tools.html — confirmed `ToolCallingManager` interface has only `resolveToolDefinitions(ToolCallingChatOptions)` + `executeToolCalls(Prompt, ChatResponse)`; confirmed NO `maxIterations` API; confirmed `DefaultToolCallingManager` + `ToolExecutionExceptionProcessor` are the current implementation/extension points.
- **Spring AI 1.1.4 Advisors API reference** — https://docs.spring.io/spring-ai/reference/api/advisors.html — confirmed `CallAdvisor.adviseCall(ChatClientRequest, CallAdvisorChain)` + `CallAdvisorChain.nextCall(ChatClientRequest)`; confirmed advisor list does NOT include `StructuredOutputValidationAdvisor`; confirmed `SafeGuardAdvisor` is present.
- **Spring AI 1.1.4 ChatClient API reference** — https://docs.spring.io/spring-ai/reference/api/chatclient.html
- **Spring AI 1.1.4 All Classes index** — https://docs.spring.io/spring-ai/docs/current/api/allclasses-index.html — `StructuredOutputValidationAdvisor` absent from class list (April 2026).
- **Project build file** — `D:/DTH/ai-agent-core/ai-agent/build.gradle` — confirms `spring-ai-bom:1.1.4`, `bomVersion: 2.8.0` (Jmix).
- **Project module build files** — `ai-agent/ai-agent.gradle` + `ai-agent-starter.gradle` — confirms `spring-ai-client-chat:1.1.4`, `spring-ai-starter-model-openai:1.1.4`, `spring-ai-starter-vector-store-pgvector:1.1.4`, `spring-ai-tika-document-reader:1.1.4`, `spring-ai-rag` (2.0.0-M2 per BOM), `spring-ai-model-chat-memory-repository-jdbc:1.1.4`.
- **AI-SPEC Section 1b Domain Context** — OWASP LLM Top 10 2025 + NIST AI RMF 1.0 sourcing; Section 1b failure modes grounded in domain expert literature.
- **Existing code in `ai-agent/ai-agent/src/main/java/com/vn/agent/`** — `AiParametersResolver`, `ChatClientFactory`, `DefaultChatServiceImpl`, `AuditWriter`, `AiToolCallAudit`, `ToolGuard`, `PromptContextContributor`, `AiAgentDefaultsProperties` — verified class shapes and extension points match CONTEXT.md assumptions.

### Secondary (MEDIUM confidence)

- **Baeldung — A Guide to Spring AI Advisors** — https://www.baeldung.com/spring-ai-advisors — confirmed `SafeGuardAdvisor(List<String> forbiddenWords)` is input-side only; does not scan model responses.
- **Spring blog — Supercharging Your AI Applications with Spring AI Advisors** — https://spring.io/blog/2024/10/02/supercharging-your-ai-applications-with-spring-ai-advisors/ — advisor chain semantics.
- **Bucket4j GitHub** — https://github.com/bucket4j/bucket4j — confirms 8.14.0 is current April 2026; JCache module exists; token bucket with greedy refill is the standard algorithm.
- **Jackson YAML docs** — https://github.com/FasterXML/jackson-dataformats-text/tree/2.17/yaml — `YAMLFactory` pattern for strict parse.
- **Bucket4j Spring Boot Starter** — https://github.com/MarcGiffing/bucket4j-spring-boot-starter — rejected for v1 (too filter-centric, hides integration).
- **Phase 4 CONTEXT + Summaries** — `.planning/phases/04-orchestration-core/` — D-04 per-request re-resolution; D-08 dual-layer; D-09 opacity; D-11 `AuditWriter` REQUIRES_NEW pattern (reused in Phase 6).
- **AI-SPEC Section 3** — Spring AI 1.1.4 entry-point pattern snippets independently reconcile with docs.spring.io verified content above.

### Tertiary (LOW confidence — flagged for validation)

- **Exact exception class thrown by `ChatClient.entity(Class)` on parse failure** (OQ-1). Assumed `BeanOutputParseException` or Jackson's `JsonProcessingException` wrapped as runtime. To verify at implementation time.
- **`OpenRouter passes `Usage` metadata through for `openai/gpt-4o-mini`** (A4). Needs live-test confirmation.

---

## Metadata

**Confidence breakdown:**

- **Standard stack:** HIGH — every library is already on classpath from Phases 1–5 or is a standard Spring Boot managed dependency; BOM pin verified in build files.
- **Architecture patterns:** HIGH — Spring AI 1.1.4 advisor / tool-calling / structured-output APIs verified via multiple doc pages; existing Phase 4/5 code confirms the composition shape.
- **Pitfalls:** HIGH — the three phantom-API pitfalls (`StructuredOutputValidationAdvisor` absent, `ToolCallingManager.maxIterations` absent, `SafeGuardAdvisor` input-only) are verified against official Spring AI 1.1.4 docs.
- **Security / threat map:** HIGH — grounded in OWASP LLM Top 10 2025 and NIST AI RMF 1.0 (cited via AI-SPEC 1b).
- **Rate-limit library choice:** MEDIUM — hand-roll vs Bucket4j-JCache is a footprint trade-off with no correctness difference at v1 load profile; planner discretion locked by D-12 storage abstraction.

**Research date:** 2026-04-21
**Valid until:** 2026-05-21 (30 days for stable BOM-pinned stack; revisit if Spring AI 1.1.5+ releases or if `StructuredOutputValidationAdvisor` is proposed on spring-projects/spring-ai).
