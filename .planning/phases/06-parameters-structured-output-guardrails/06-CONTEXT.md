# Phase 6: Parameters, Structured Output & Guardrails - Context

**Gathered:** 2026-04-20
**Status:** Ready for planning

<domain>
## Phase Boundary

Ship the admin-editable parameter-profile layer (CRUD + YAML body + active-profile switch + `default-params.yaml` bootstrap + per-conversation override), the complete guardrail stack (per-user rate limit, per-conversation token circuit breaker, tool-calling iteration cap, `ToolGuard` enforcement wiring, output-side injection scanner), and the typed structured-output path (`ChatService.askTyped` with bounded parse-retry and graceful degradation when `StructuredOutputValidationAdvisor` is absent in Spring AI 1.1.4).

**In scope:**
- `ParametersService` with CRUD, active-profile flip, and YAML body ser/de via `jackson-dataformat-yaml`.
- `default-params.yaml` bundled with `ai-agent-starter`; one-shot seed at `ApplicationReadyEvent` iff `AI_AGENT_PARAMETERS` is empty.
- Per-conversation `Overrides` record (model-only) plumbed into `ChatService.ask(userId, convId, question, Overrides)` + current 3-arg `ask` retained as thin delegate.
- `PromptContextContributor` chain wired into the system prompt assembled by `ChatClientFactory` / `AiParametersResolver.effectiveSystemPrompt`.
- `ToolGuard` default impl (no-op already ships in `SpiDefaultsAutoConfiguration`) + enforcement wiring: every tool invocation passes through the guard chain; veto produces `AiToolCallAudit` `BLOCKED` row with `denialReason`.
- `ToolCallingManager.maxIterations(6)` (configurable) set on the cached bean; exhaust → typed `IterationCapExceededException`.
- Per-conversation token circuit breaker (configurable ceiling, default ~100k); accumulates `ChatResponse.metadata.getUsage()` per turn; breach → typed `TokenBudgetExhaustedException`.
- Per-user chat rate limiter (configurable, default 10 req/min); keyed by Jmix user id from `CurrentAuthentication`; storage behind JSR-107 `javax.cache` / Spring Cache abstraction, local-by-default.
- Output-side injection-pattern advisor: regex-based, bundled default pattern list, `@ConfigurationProperties`-extensible; match → `ChatResponseDto.flagged=true` + matched pattern key recorded in audit.
- Structured output: `ChatService.askTyped(userId, convId, question, Class<T>) → T` via `ChatClient.prompt()….call().entity(Class)`; parse-fail-only retry with max 2 attempts re-injecting `BeanOutputConverter` format instructions; `StructuredOutputValidationAdvisor` used if present, else in-service retry loop.
- Typed exceptions per guard surface; `DefaultChatServiceImpl` maps each to a `ChatResponseDto` carrying an i18n message-key + params; Phase 7 UI renders localised text.
- Unit + integration tests for the four ROADMAP success criteria (profile activation; tool-guard veto audit; iteration-cap termination; structured-output happy path + one-retry).

**Out of scope (explicit):**
- `ChatView` / `ParametersListView` / `ParametersDetailView` UI — Phase 7 (UI-01, UI-04).
- Streaming response path and streaming-response interaction with the output scanner — Phase 7.
- Mutation tools and mutation-tool safety patterns (dry-run, confirmation) — post-v1.
- Additional `OutputScanner` SPI beyond config-driven regex list — deferred.
- Cluster-safe counter storage (Hazelcast/Redis/DB-backed) — out of scope; abstraction lets hosts swap provider via config without add-on code change.
- Self-tokenizer pre-flight token counting (`jtokkit`) — out of scope; provider-reported `Usage` is authoritative in v1.
- Per-conversation sampling/temperature overrides beyond `model` — deferred.
- Named-profile per-conversation reference (convo points at a non-active profile) — deferred.
- Admin reset UI for exhausted token breakers — Phase 7.

</domain>

<decisions>
## Implementation Decisions

### Parameters: Overrides API, Bootstrap, Activation

- **D-01: Per-conversation `Overrides` is model-only in v1.** Record shape: `Overrides(String model)`. System prompt, tool-set, temperature/maxTokens, RAG top-k, RAG similarity threshold all remain strictly admin-controlled via the active `AiParameters` profile. Rationale: the one user-facing use case is "try a different model on this conversation" (cheaper/larger/reasoning); letting end-users tweak prompts or disable guards fights the product's safety posture. Expansion is a non-breaking record-field addition when a host surfaces a concrete case.

- **D-02: Null-merge composition.** `Overrides` is a sparse record: non-null field replaces the active profile's value, null inherits. Today that means `Overrides(null)` is a no-op and `Overrides("openai/gpt-4o")` overrides model only. The merge boundary lives in `AiParametersResolver` — add an `effectiveModel(AiParameters, Overrides)` overload and keep all other `effectiveX()` getters unchanged. Keeps the pattern uniform if later fields are added per D-01.

- **D-03: `ChatService.ask` grows an overload, no breaking change.** Two methods: the existing `ChatResponseDto ask(userId, convId, message)` (Phase 4) delegates to `ask(userId, convId, message, null)` or `ask(userId, convId, message, Overrides.NONE)`. The overload is the authoritative signature. `askTyped` (D-12) has the same shape with a trailing `Class<T>`. Same decision applies to the typed path.

- **D-04: `default-params.yaml` seeds once, at `ApplicationReadyEvent`, only if the parameters table is empty.** `@EventListener(ApplicationReadyEvent.class)` checks `dataManager.load(AiParameters.class).query(…).all()`; if zero rows, reads the bundled YAML (classpath `default-params.yaml`, overridable via `jmix.ai-agent.parameters.defaults-resource` property), parses via the same Jackson-YAML codepath as admin CRUD, persists one profile with `profileName="default"` and `active=true`. Admin deletion is intentional; no re-seed. Matches PARAM-04 literally.

- **D-05: YAML is parsed, validated, and stored as the typed `bodyYaml` Lob; no canonicalisation.** On write (`ParametersService.save`) the service parses the YAML body once to validate structure (`model` is a string, `temperature` is a number in [0, 2], etc. — enforced via the Jackson-YAML → `AiParametersBody` DTO) and rejects with a `ParametersValidationException` on unknown top-level keys to prevent silent drift. On read, the persisted text flows straight into `AiParametersResolver` which parses it again (re-using the existing snakeyaml path). Strict validation at write, tolerant read.

- **D-06: "Exactly one active" enforced at service layer, not DB.** `ParametersService.setActive(id)` runs within `@Transactional(REQUIRED)`: one UPDATE flips all currently-active rows to `false`, a second UPDATE sets the target id to `true`. No partial unique index — Postgres-only syntax conflicts with the HSQLDB-gated demo host + the invariant is already guaranteed by the sole code path that flips it. Direct SQL by operators bypassing the service is acknowledged acceptable risk for an admin entity.

- **D-07: Active-profile switch is seen on the next request, not cached.** Phase 4 D-04 already ships per-request `AiParametersResolver.resolveActive()` with no caching. Phase 6 does not change that; the value `ChatClientFactory` uses for its `defaultSystem(…)` is a snapshot at construction but `DefaultChatServiceImpl` re-resolves per-request and overrides via `.system(…)` / `.options(…)` on each `prompt()` call. No eviction logic needed.

- **D-08: `PromptContextContributor` chain invoked per-request, concatenated after base system prompt.** `AiParametersResolver.effectiveSystemPrompt(AiParameters, RunContext)` gains a second parameter; calls `List<PromptContextContributor>` in Spring bean-injection order (`@Order` annotation honoured), appends each contributor's output to the effective system prompt with a consistent separator (double newline). Empty/null contributor output is skipped. Matches PARAM-05 + SPI-03. RunContext carries Jmix user, locale, conversationId for contributors that vary by identity.

### Guardrails: Composition, Plumbing, Denial UX, Audit

- **D-09: Guard firing order is rate-limit → token breaker → iteration cap → ToolGuard → output scanner.** Cheapest/highest-blast-radius first: reject at the door before burning provider tokens. Request-level guards run pre-LLM in `DefaultChatServiceImpl.ask()` preamble before `ChatClient.prompt()`. Iteration cap lives inside `ToolCallingManager` (per-turn, mid-loop). `ToolGuard` runs per tool call via a delegating `ToolCallingManager` or a `ToolCallback` interceptor (planner picks the cleanest seam). Output scanner runs once on the final assistant message via a `CallAroundAdvisor` slotted immediately before `AuditAdvisor` completes its POST row so the flag lands in the same audit row.

- **D-10: Each guard throws a distinct typed exception; `DefaultChatServiceImpl` maps to a `ChatResponseDto` carrying an i18n message-key.** Exception catalogue: `RateLimitExceededException`, `TokenBudgetExhaustedException`, `IterationCapExceededException`, `ToolVetoedException` (already ships in Phase 2), `OutputFlaggedException` (non-terminal — see D-13). Each maps to a `msg://ai-agent.guard.<name>` key with typed params (e.g. retry-after seconds, current usage). Phase 7 UI renders localised text in both `messages_en.properties` and `messages_vi.properties`. Raw exception detail stays server-side (logged + audited). `ChatResponseDto` grows a `GuardDenialInfo` optional field carrying key + params for UI rendering; the add-on never leaks limit ceilings or pattern specifics to end users.

- **D-11: Single `AuditWriter` path for all guard denials.** Tool-level denials (ToolGuard veto) produce an `AiToolCallAudit` row with the real tool name, `outcome=BLOCKED`, and `denialReason` populated (Phase 4 D-11 REQUIRES_NEW writer). Request-level denials (rate-limit, token breaker, iteration cap, output scanner flag) produce rows on the same table with a reserved synthetic tool name `__chat__` (underscore-prefix keeps it orthogonal to any legitimate `@Tool` name which must be a Java identifier). Reuses the existing CSV export path (UI-06) and avoids a parallel audit entity. Output-scanner flags produce a `FLAGGED` outcome (new enum value — see D-13); rate-limit/token/iteration produce `BLOCKED`.

### Rate Limit + Token Breaker: Storage, Scope, Identity

- **D-12: Counter storage via JSR-107 JCache / Spring Cache abstraction, local-by-default.** Register two named caches (`aiAgentRateLimitBuckets`, `aiAgentConversationTokenUsage`) via a `CacheManager` autowired from Spring Boot; default provider is `ConcurrentMapCacheManager` on the add-on classpath (zero infra, matches Jmix standalone posture and MEMORY note "AI is just another Jmix client" — no parallel infra layer). Hosts that cluster replace the `CacheManager` bean via `@ConditionalOnMissingBean` (Hazelcast, Ehcache, Redis JCache provider — any JSR-107 compliant impl). The rate-limit token-bucket algorithm is thin enough to hand-roll on top of the cache; `Bucket4j` with its JCache module is an optional library, planner picks based on footprint.

- **D-13: Rate limiter keyed by Jmix user id from `CurrentAuthentication.getUser().getUsername()`.** No session / IP fallback; the add-on contract is authenticated-only (PROJECT.md). Default ceiling: 10 requests/min (GUARD-04). Exposed via `jmix.ai-agent.guard.rate-limit.{requests-per-minute,enabled}`. Bucket refill strategy: greedy/continuous refill at 1 token per 6s (10 tokens/min); per-bucket capacity = requests-per-minute ceiling.

- **D-14: Token breaker is per-conversation rolling total with a configurable ceiling.** Boundary is `AiConversation.id`; the ceiling is lifetime-of-conversation (no time window). Accumulate per-turn `ChatResponse.getMetadata().getUsage().getTotalTokens()` into the `aiAgentConversationTokenUsage` cache keyed by conversationId. Default ceiling `jmix.ai-agent.guard.token-breaker.ceiling = 100000`. Breach semantics: the *incoming* request that would exceed the ceiling is denied (post-prior-response check). User recourse: New Chat (fresh conversationId = fresh bucket). Admin recourse: delete the conversation or reset its counter via a future admin affordance (not in Phase 6 scope).

- **D-15: Token count source is provider-reported `ChatResponse.metadata.getUsage()`.** Spring AI exposes prompt + completion tokens after each LLM call; the breaker reads this post-response and accumulates. No local tokenizer (no `jtokkit` dependency). Consequence: the *first* response that sends a conversation over the ceiling completes successfully; the *next* request on the same conversation is denied. This is bounded by D-16 iteration cap + `AiParameters.maxTokens` per call — a single runaway response cannot 10× the budget in one turn. Docs must explain this clearly to operators.

- **D-16: Iteration cap applied at `ToolCallingManager.maxIterations(6)` (configurable).** Cap lives on the cached `ToolCallingManager` bean exposed to `ChatClientFactory`. Exposed via `jmix.ai-agent.guard.iteration-cap.max-iterations` (default 6, matching ROADMAP). Exhaust → `IterationCapExceededException`; the partial assistant message (if any) is logged but not returned; user sees the typed error per D-10. Success-criterion #3 (malicious prompt-driven tool-call loop terminates bounded) maps directly to this cap.

### Output Scanner + Structured Output

- **D-17: Output scanner action is flag-and-pass-through, not block.** On regex match, set `ChatResponseDto.flagged=true` + carry the matched pattern *key* (not the matched text; redacts PII/prompt leak echoes) into both the response DTO and the audit row (outcome `FLAGGED`). User sees the assistant content plus a banner (Phase 7 UI: "This response was flagged — review carefully"). Least destructive: regex injection heuristics false-positive on benign text; blocking outright would break chat UX. Matches GUARD-05 "redacts or flags" in the softer direction.

- **D-18: Scanner patterns are bundled regex defaults + `@ConfigurationProperties` override.** Default list ships in `ai-agent-starter` as a hardcoded list inside the `@ConfigurationProperties` class (e.g. prompt-injection canaries like case-insensitive `ignore\s+(all\s+)?previous\s+instructions`, system-tag leakage `</?system>`, role-break attempts `assistant:.*user:`). Host extends or replaces via `jmix.ai-agent.guard.output-scanner.patterns` (list of `{key, regex}` maps) in `application.yml`. No `OutputScanner` SPI surface in v1 — config covers the stated need and keeps the SPI catalogue aligned with MEMORY "SPIs only for app-specific behavior". Each pattern carries a stable `key` so audit rows remain stable even if the regex is tuned.

- **D-19: `ChatService.askTyped(userId, convId, question, Class<T>) → T` is a new method, not an `ask` overload.** Distinct method keeps the untyped `ChatResponseDto` return path uncluttered and avoids genericising Phase 4's `ChatResponseDto`. Internally uses `chatClient.prompt()…..call().entity(Class<T>)`. On exhausted retries throws `StructuredOutputException` carrying the last raw text + class; callers handle explicitly. Same `Overrides` overload shape as D-03: `askTyped(userId, convId, question, Overrides, Class<T>)`.

- **D-20: Retry fires only on parse failure; max 2 retries.** `BeanOutputConverter` parse exception → retry with re-injected format instructions ("Your previous response could not be parsed as <schema>; respond again as valid JSON matching: <schema>"). Max 2 retry attempts per ROADMAP success criterion #4. Network errors, rate-limit, token-breaker, iteration-cap denials *bubble without retry* — retrying a guard denial just multiplies the cost/audit-row count. Retry attempt count is captured in audit metadata (new optional column or metadata JSON — planner picks based on existing `AiToolCallAudit` shape).

- **D-21: `StructuredOutputValidationAdvisor` is a research-gated optional advisor.** Phase 6 research verifies Spring AI 1.1.4 ships this advisor (PROJECT.md note: "existence in 1.1.4 to be verified in Phase 6 per research flag"). If present, wire it into the advisor chain when `askTyped` is called (per-request `.advisors(...)` addition) and let the advisor own the retry loop. If absent, retry logic lives inline in `DefaultChatServiceImpl.askTyped` — same semantic contract, different implementation site. Behaviour observable from tests is identical.

### Claude's Discretion

- Exact Java package layout inside `com.vn.agent.parameters` / `com.vn.agent.guard` — planner picks; match existing `com.vn.agent.rag` / `com.vn.agent.orchestration` shape.
- Whether `ToolGuard` enforcement wiring is a delegating `ToolCallingManager` vs a `ToolCallback` interceptor vs a pre-dispatch hook — all three satisfy D-09/D-11; planner picks based on Spring AI 1.1.4 extension points and test-seam ergonomics.
- Whether the output scanner advisor runs strictly post-ToolCall (after all tool turns) or wraps every turn — functionally equivalent for the flag-and-pass-through posture; planner picks whichever composes cleaner with `AuditAdvisor` ordering.
- Whether rate-limit buckets are implemented hand-rolled or via `Bucket4j-JCache` — D-12 fixes the storage abstraction; library choice is a footprint/complexity tradeoff the planner evaluates.
- Exact Jackson-YAML DTO shape for `AiParametersBody` validation (D-05) — planner picks, constraint-annotation style preferred.
- Whether `OutputFlaggedException` is actually thrown vs a non-exceptional flag path (D-17 specifies pass-through content) — prefer non-exceptional flag propagation; the exception name is retained for uniform guard catalogue only if it simplifies the mapper.
- Whether `AiToolCallOutcome` gains a `FLAGGED` value or flags ride as a side column — planner picks; both satisfy D-11/D-17.
- Exact audit metadata carrier for retry-attempt count (D-20) — new optional column vs metadata JSON vs a separate `AiStructuredOutputAudit` table. Prefer smallest change that survives CSV export.
- Whether `jmix.ai-agent.guard.*` property keys sit under one `AiAgentGuardProperties` record or split per guard — pattern match whatever phases 2/5 established.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/PROJECT.md` — Spring AI 1.1.4 pinned via BOM, OpenRouter default, read-only-tools-only posture, plug-and-play contract, D-10 (no AI-specific exposure layer), D-01 (module split deferred), research flag "StructuredOutputValidationAdvisor existence in 1.1.4 to be verified in Phase 6"
- `.planning/REQUIREMENTS.md` — PARAM-01..05 (profile entity/fields/override/seed/contributor), GUARD-01..06 (full guardrail stack), SPI-05 (`ToolGuard` interface already ships; Phase 6 wires enforcement)
- `.planning/ROADMAP.md` §Phase 6 — deliverables, the four success criteria (authoritative for scope), research flag
- `.planning/phases/02-foundations/02-CONTEXT.md` — D-06 (`@ConditionalOnMissingBean` starter pattern), `AiParameters` entity + `AI_AGENT_PARAMETERS` DDL, `ToolGuard` / `PromptContextContributor` SPI shipping
- `.planning/phases/04-orchestration-core/04-CONTEXT.md` — D-02 (advisor order with reserved slots), D-04 (per-request parameter re-resolution, no caching), D-11 (`AuditWriter` REQUIRES_NEW pattern — reuse for guard denial rows), `ChatClientFactory` shape, `AiParametersResolver` pattern
- `.planning/phases/05-rag-layer/05-CONTEXT.md` — deferred "`StructuredOutputValidationAdvisor` interaction with retrieval advisor — Phase 6 territory"; `IngestionStatusWriter` shape (parallel pattern for any new writer in Phase 6)
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` — D-03 plug-and-play boot contract (default guardrail config must not require host intervention)

### Existing code
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java` — entity with `profileName` unique, `active` flag, `bodyYaml` Lob; Phase 6 consumes, does not alter
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java` — existing `resolveActive()` + `effectiveX()` getters; Phase 6 adds `Overrides` overload (D-02) and `PromptContextContributor` chain call (D-08)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentDefaultsProperties.java` — `jmix.ai-agent.defaults.*` record; Phase 6 adds `AiAgentGuardProperties` (or split) for guard tunables
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java` — advisor slots reserved; Phase 6 inserts output scanner advisor + (optional, research-gated) `StructuredOutputValidationAdvisor`; sets `ToolCallingManager.maxIterations`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` / `DefaultChatServiceImpl.java` — Phase 6 adds `ask(…,Overrides)` overload + `askTyped(…,Class<T>)` + per-request pre-LLM guard preamble
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` — interface shipping; Phase 6 wires enforcement
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolVetoedException.java` — thrown by guard; Phase 6 maps to typed UX + audit
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/PromptContextContributor.java` — SPI shipping; Phase 6 invokes the chain (D-08)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java` / `AiToolCallOutcome.java` — guard denial rows land here; enum may gain `FLAGGED` per D-11/D-17
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java` (Phase 4) — output scanner advisor coordinates with this at final-message boundary
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/` — add only if `AiToolCallOutcome` gains `FLAGGED` or audit metadata column required; prefer zero-schema approach if metadata JSON suffices

### Starter resources
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/` — starter auto-configuration pattern; Phase 6 adds `default-params.yaml` here (D-04)
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages*.properties` — new `msg://ai-agent.guard.*` keys for every typed denial exception (D-10); both `messages.properties` + `messages_vi.properties` MUST contain every key

### Project conventions
- `CLAUDE.md` — DataManager-only, no EntityManager; `Metadata.create()` for any synthetic entities; constructor injection; `msg://` i18n in BOTH locale files; `jetbrains` MCP `get_file_problems` on modified files after Java work (MEMORY)

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-services` — `DataManager` patterns, `@Transactional` REQUIRED vs REQUIRES_NEW for D-06 (active-flip) and audit writer
- `jmix-entities` — `Metadata.create()` for parameter/audit entities if Phase 6 instantiates any
- `jmix-liquibase` — only if `AiToolCallOutcome` enum addition forces a changelog (prefer avoiding)
- `jmix-security-roles` — `CurrentAuthentication.getUser().getUsername()` for D-13 rate-limit key; `AiAgentAdminRole` for CRUD policy on Parameters
- `jmix-testing` — `@SpringBootTest` + `@Tag("live")` gating for any live-LLM structured-output test

### External reference implementations (pattern source, NOT a dependency)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI parameter-profile admin view reference; generalise pattern, do not copy domain specifics

### Spring AI docs (use Context7 before writing code — M4 API shifts)
- `/spring-projects/spring-ai/v1.1.4` — `ChatClient.prompt()….entity(Class)` path; `BeanOutputConverter` failure modes (D-19/D-20)
- `/spring-projects/spring-ai/v1.1.4` — `StructuredOutputValidationAdvisor` existence probe + wiring (D-21 research flag)
- `/spring-projects/spring-ai/v1.1.4` — `ToolCallingManager` builder, `maxIterations` setter + exhaust exception type (D-16)
- `/spring-projects/spring-ai/v1.1.4` — `ChatResponse.getMetadata().getUsage()` shape across providers (D-15); confirm it survives OpenRouter passthrough
- `/spring-projects/spring-ai/v1.1.4` — `CallAroundAdvisor` (or the 1.1.4-current name) for the output-scanner advisor (D-09/D-17)
- `/spring-projects/spring-ai/v1.1.4` — `ToolCallback` / delegating `ToolCallingManager` extension points for `ToolGuard` wiring (D-09)

### Cache/rate-limit library references
- `/bucket4j/bucket4j` — JCache integration module (D-12 optional); algorithm semantics for token-bucket with greedy refill (D-13)
- JSR-107 `javax.cache` / Spring `CacheManager` — abstraction boundary for counter storage (D-12)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets (from prior phases)
- `AiParameters` entity + unique profileName index — Phase 2; Phase 6 wraps with `ParametersService` CRUD + active-flip
- `AiParametersResolver.resolveActive()` + `effectiveX()` getters — Phase 4 D-04; Phase 6 extends with `Overrides` overload and `PromptContextContributor` chain
- `AiAgentDefaultsProperties` — Phase 4; pattern template for `AiAgentGuardProperties`
- `ChatClientFactory` with reserved advisor slots + `ToolCallingManager` already injected — Phase 4/5; Phase 6 adds output-scanner advisor + structured-output advisor (research-gated) + `maxIterations`
- `ToolGuard` + `ToolVetoedException` + `PromptContextContributor` SPIs — Phase 2; Phase 6 wires enforcement
- `AuditAdvisor` + `AuditWriter` REQUIRES_NEW pattern — Phase 4 D-11; directly reused for guard denial rows (D-11)
- `AiToolCallAudit` + `AiToolCallOutcome` — Phase 4; Phase 6 may add `FLAGGED` enum value (else use metadata/side column)
- `ai-agent-starter` auto-configuration shape — Phase 1/2; `default-params.yaml` and new `@ConfigurationProperties` classes register here

### Established Patterns
- `@ConditionalOnMissingBean` for every extensible bean (cacheManager, ToolGuard, OutputScanner patterns, StructuredOutputValidationAdvisor)
- Dedicated writer beans for transactional-boundary control (`AuditWriter` pattern) — guard denial rows reuse
- Per-request re-resolution over boot-time caching (Phase 4 D-04) — Phase 6 preserves
- Typed exceptions mapped to typed DTOs at the service boundary, not leaked to UI
- `msg://` keys in both locale files — every new user-facing error string requires `messages.properties` + `messages_vi.properties`
- JSR-107 JCache abstraction with local-by-default provider — matches Jmix standalone posture (user note cites Jmix `CacheOperations` local-first design)

### Integration Points
- `DefaultChatServiceImpl.ask()` — prepend pre-LLM guard preamble (rate-limit → token breaker); wrap existing body unchanged otherwise
- `ChatClientFactory` — add output-scanner advisor (after ToolCallAdvisor, before AuditAdvisor POST); set `ToolCallingManager.maxIterations`; optional `StructuredOutputValidationAdvisor` wiring gated on research
- `AIAutoConfiguration` / `SpiDefaultsAutoConfiguration` — register `@ConditionalOnMissingBean` defaults for `ToolGuard` (no-op already exists per Phase 2 D-06 pattern), output-scanner pattern list, cache manager fallback, `StructuredOutputValidationAdvisor` (if present)
- `AiParametersResolver.effectiveSystemPrompt` — gain `RunContext` arg + call `PromptContextContributor` chain
- `jmix-app` demo host — Phase 7 will exercise ChatView end-to-end; Phase 6 integration tests use `@SpringBootTest` + sync `TaskExecutor` + `@Tag("live")` gating for any live-LLM assertions

</code_context>

<specifics>
## Specific Ideas

- The output scanner flags with a *pattern key*, not the matched text (D-17/D-18). This matters for audit stability and for not echoing prompt-injection payloads back into audit rows (which become their own leak vector). Tests assert the key-based stability.
- `ChatService.askTyped` is the single surface for all structured output — internal code paths (e.g. any future Phase 7/8 "summarise this conversation" feature) route through it, keeping retry semantics uniform.
- The synthetic `__chat__` tool name for request-level audit rows is a reserved identifier — no legitimate `@Tool` name can collide (underscores disallowed in Java identifier start position where `@Tool` names typically derive from method names). Tests assert this invariant.
- Default YAML seeded by D-04 must produce a profile that boots a fully-functional chat on the demo host with zero host intervention — same plug-and-play contract as Phase 1 D-03. Integration test: delete all `AiParameters` rows → restart → send chat → succeeds.
- Per-conversation model override (D-01) validates the same OpenRouter slug format (`provider/model`) as `AiParametersResolver` validation; reuse the existing validator rather than a parallel check.
- Success criterion #3 (iteration-cap termination) is the cleanest negative-test target: construct a prompt that loops a test tool; assert the cap terminates bounded with a `ToolCallsBounded` audit pattern (6 tool-call audit rows + 1 `__chat__` iteration-cap row).

</specifics>

<deferred>
## Deferred Ideas

- **Per-conversation overrides beyond `model`** — D-01 limits Overrides to model in v1; temperature/topP/systemPrompt/tool-set expansion deferred until a named host use case surfaces.
- **Named-profile per-conversation reference** (Overrides points at a non-active profile by id/name) — deferred; plain sparse-merge is simpler and the product shape doesn't demand it yet.
- **`OutputScanner` SPI** — D-18 is config-driven in v1; SPI deferred until a host surfaces a non-regex need (semantic similarity, model-based classifier).
- **Cluster-aware counter storage shipped with the add-on** — D-12 provides the abstraction; shipping Hazelcast/Redis config deferred until a named multi-node deployment surfaces.
- **Self-tokenizer pre-flight token counting** — deferred; provider-reported `Usage` is authoritative in v1 per D-15.
- **Admin token-breaker reset affordance** — Phase 7 could add a "Reset token counter" action on the Conversation detail view; deferred from Phase 6 scope.
- **Dynamic pattern reload** — D-18 patterns bind at boot via `@ConfigurationProperties`; live reload deferred until a host needs incident-response tuning without restart.
- **Rate-limit tiers by role** — all users share the same per-user rate in v1; role-differentiated quotas deferred.
- **Streaming-response interaction with output scanner** — Phase 7 streaming territory; D-17 flag-on-final-message model covers v1 blocking-response semantics; streaming strategy deferred.
- **Mutation-tool dry-run + confirmation flow** — PROJECT.md deferred; `ToolGuard` infrastructure here supports future implementation.
- **Structured-output schema registry / shared type library** — callers supply `Class<T>` directly in v1; schema registry deferred.

</deferred>

---

*Phase: 06-parameters-structured-output-guardrails*
*Context gathered: 2026-04-20*
