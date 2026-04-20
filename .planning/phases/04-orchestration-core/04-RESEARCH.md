# Phase 4: Orchestration Core - Research

**Researched:** 2026-04-20
**Domain:** Spring AI 1.1.4 `ChatClient` + advisor chain + JDBC chat memory + dual-layer Jmix persistence + audit pipeline on Jmix 2.8 / Spring Boot 3 / Java 17
**Confidence:** HIGH (advisor ordering, memory API, OpenAI config shape, `ChatMemoryRepository` interface); MEDIUM (`ToolCallAdvisor` memory-disable method name); LOW (OpenRouter-specific Spring AI edge cases — no first-party Spring AI coverage)

## Summary

Phase 4 composes the first end-to-end LLM path. The stack is locked (Spring AI 1.1.4 pinned in `ai-agent/build.gradle` line 35, OpenAI-compatible starter pointed at OpenRouter, `JdbcChatMemoryRepository` with `initialize-schema: never`, Liquibase owns the `SPRING_AI_CHAT_MEMORY` schema from Phase 2). The risk surface is **wiring**, not selection. The 04-AI-SPEC.md document covers almost all of the implementation guidance; this RESEARCH.md's job is to **verify the AI-SPEC's API claims against Spring AI 1.1.2 primary docs (closest version available in Context7), flag the one unverified claim (`ToolCallAdvisor.disableMemory()`), and add the explicit "do not hand-roll" and "common pitfalls" catalog the planner needs**.

**Primary recommendation:** Proceed with the AI-SPEC as the implementation blueprint. One verified substitution: replace the AI-SPEC's `.disableMemory()` call on `ToolCallAdvisor.builder()` with a **code-time verification step** — the planner must have the implementer (a) enumerate `ToolCallAdvisor.Builder` methods via IDE at the start of Task 1, (b) use whichever of {`.disableMemory()`, `.disableInternalConversationHistory()`, `.conversationHistoryEnabled(false)`, or no-op because the manager-level option is the right seam} actually exists in the 1.1.4 jar, and (c) record the verified signature as a Rule 1 deviation in `04-SUMMARY.md`. Everything else in the AI-SPEC is verified against 1.1.2 Context7 docs and the Spring AI 1.1.x line is API-stable across point releases.

<user_constraints>

## User Constraints (from CONTEXT.md)

### Locked Decisions

**ChatClientFactory & Advisor Chain**
- **D-01: Cached `ChatClient` at app start; per-request `.prompt()` builder.** One `ChatClient` assembled at startup with `defaultAdvisors(...)` in verified order. Each `ChatService.ask()` invokes `.prompt().tools(perRequestToolSet).options(perRequestChatOptions).user(...).call()`.
- **D-02: Advisor order (outermost → innermost): `AuditAdvisor` → `MessageChatMemoryAdvisor` → `ToolCallAdvisor`.** `AuditAdvisor` at `HIGHEST_PRECEDENCE`; Memory at `HIGHEST_PRECEDENCE + 200`; Tool innermost at `HIGHEST_PRECEDENCE + 300`. Phase 5's `QuestionAnswerAdvisor` slots between Memory and Tool.
- **D-03: Per-request `ChatOptions` built from the active `AiParameters` profile.** System prompt from profile via `.system(...)`; `OpenAiChatOptions` via `.options(...)`.
- **D-04: Phase 4 reads `AiParameters` via `DataManager`; falls back to `@ConfigurationProperties` constants** (`jmix.ai-agent.defaults.*`) if no active row exists. Phase 6 will add the bootstrap row without reshaping Phase 4 code.

**Dual-Layer Conversation Persistence**
- **D-05: Project to `AiConversation`/`AiMessage` via a decorator around `JdbcChatMemoryRepository`.**
- **D-06: `AiMessage.content` stores full message text (duplicated with `SPRING_AI_CHAT_MEMORY`).** Storage cost acceptable vs coupling to framework schema.
- **D-07: Projection runs in the same transaction as the chat-memory write (`Propagation.REQUIRED`).**
- **D-08: `ChatService.ask()` creates `AiConversation` on first message for a new `conversationId`** via `Metadata.create()` + `DataManager.save()`.

**Ownership Enforcement**
- **D-09: Explicit pre-check in `ChatService` + `DataManager` row-level predicate as defence in depth.** Same `ConversationNotFoundException` for "row does not exist" and "row exists but not yours" — no existence leak.

**Audit Pipeline**
- **D-10: Two audit layers** — chat-level `AuditAdvisor` (pre/post rows linked by `runId`) + per-tool-call `MethodToolCallback` decorator writing `kind=TOOL` rows.
- **D-11: `REQUIRES_NEW` lives on a dedicated `AuditWriter` bean's methods only, NOT on the advisor/wrapper methods.** This is what makes "tool-tx rollback does not erase tool audit" work.
- **D-12: Chat-level audit content:** pre (runId, user, conversationId, promptHash, timestamp); post (runId, outcome, latencyMs, errorClass nullable). `promptHash` = SHA-256 of user message text.

**AuditListener SPI Fan-Out**
- **D-13: Synchronous, same-thread, per-listener try/catch.** Exceptions logged at WARN and swallowed. No `@Async`.
- **D-14: Fan-out via `TransactionSynchronizationManager.registerSynchronization` → `afterCommit`** inside `AuditWriter.writeXxx` before the REQUIRES_NEW tx commits.

**Default Contributor Impls**
- **D-15: `BaselineContextProvider` is a first-class Phase 4 component, NOT a `ContextContributor` SPI impl.** SPI defaults stay no-op (already wired in Phase 2's `SpiDefaultsAutoConfiguration`).

**Streaming**
- **D-16: Phase 4 is blocking only.** `ChatService.ask()` returns synchronous `ChatResponseDto`. Streaming deferred to Phase 7.

### Claude's Discretion

- Exact bean names, package placement within `com.vn.agent.orchestration` (or equivalent).
- `ChatService` method signature details beyond the locked shape.
- Internal method decomposition of `ChatClientFactory`.
- Enum value set for `AiToolCallOutcome` (already defined in Phase 2).
- `promptHash` algorithm if SHA-256 inappropriate for any reason.
- Exact title-derivation rule for new conversations beyond "first user message truncated to ~80 chars".
- Concrete beans that implement `BaselineContextProvider` and how it integrates with Spring AI's `PromptTemplate` / system message.
- Exact wire-up mechanism for the `MethodToolCallback` decorator (BeanPostProcessor vs `ToolCallbackProvider` decorator vs a fluent builder at `.tools(...)` assembly time) — all viable as long as D-09/D-10 Phase 3 (per-request tool assembly) is preserved.

### Deferred Ideas (OUT OF SCOPE)

- **Streaming response path** (`.stream()` / Flux + Vaadin push) — Phase 7.
- **Active-profile change notification / live rebuild of `ChatOptions`** — Phase 6.
- **Tool-result caching across chat turns** — not in v1.
- **Listener-order guarantees across multiple `AuditListener` beans** — Spring injection order sufficient for v1.
- **Async / queue-backed audit writing** — REQUIRES_NEW + same-thread fan-out is fine at MVP scale.
- **Redaction hooks on audit content** — `promptHash` sidesteps content redaction in v1.

</user_constraints>

## Project Constraints (from CLAUDE.md)

These directives OVERRIDE any contradicting research recommendation:

- **`DataManager` only — `EntityManager` forbidden.** Every persistence path in Phase 4 (AiMessage, AiConversation, AiToolCallAudit, AiParameters load) MUST use `DataManager`. The `ProjectingChatMemoryRepository` decorator writes `AiMessage` via `DataManager.save(...)` (the AI-SPEC example already does this).
- **Instantiate entities via `Metadata.create()` or `DataManager.create()`, never by constructor.** Applies to `AiConversation`, `AiMessage`, `AiToolCallAudit` creation in `ConversationGateway`, `ProjectingChatMemoryRepository`, and `AuditWriter`.
- **No Lombok on entities.** Entities are already shipped from Phase 2 — this research does not introduce any new entities.
- **Constructor injection for services.** Applies to `DefaultChatServiceImpl`, `ChatClientFactory`, `AuditAdvisor`, `AuditWriter`, `ConversationGateway`, `ProjectingChatMemoryRepository`, `AiParametersResolver`, `BaselineContextProvider`, `AuditListenerFanOut`. NO `@Autowired` field injection in services.
- **All user-facing strings via `msg://` keys in ALL locale files (`messages.properties` and `messages_vi.properties`).** Phase 4 is mostly internal plumbing, but any user-facing exception message (e.g., the `ConversationNotFoundException` message surfaced in a future UI), any log message exposed to end users, and any bean-name label in admin views MUST be i18n'd. `ConversationNotFoundException`'s message is consumed by Phase 7 UI — the i18n key MUST be introduced in Phase 4 with both locales.
- **No business logic in views.** Not directly applicable in Phase 4 (no views ship here), but the Jmix app's `ChatServiceSmokeRunner` (Phase 1) is a `CommandLineRunner`, not a view, and stays that way.
- **Liquibase changelogs: new ones go in `src/main/resources/com/vn/agent/liquibase/changelog/` with numeric prefix and MUST be included in the add-on's master `changelog.xml`.** Phase 4 does NOT introduce new DDL — `SPRING_AI_CHAT_MEMORY` shipped in Phase 2, and every entity is already defined. No changelog work expected.
- **`@JmixModule(dependsOn = {...})` already widened to include `DataConfiguration` + `SecurityConfiguration`** (Phase 2 plan 02-09). Phase 4 does not touch this.
- **Development workflow: after writing code, run `./gradlew test` and use Jetbrains MCP `get_file_problems("path", onlyErrors=false)` on each modified file.** Planner MUST include the Jetbrains MCP step in every task's Verification block; this is the Phase 3 discipline carried forward.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| ORCH-01 | `ChatClientFactory` builds `ChatClient` per request with effective tool set and parameter profile — never `.defaultTools(...)` for auto-generated tools. | Standard Stack §`ChatClient`; Code Examples §"Cached `ChatClient` + per-request `.prompt()`"; Pitfall #2. |
| ORCH-02 | Advisor chain ordered: `MessageChatMemoryAdvisor` (HIGHEST_PRECEDENCE+200) → RAG advisor (+250, Phase 5 slot) → `ToolCallAdvisor` with internal-memory-disabled (+300) → `AuditAdvisor` (around-chain). | Code Examples §"Advisor assembly"; Pitfall #1 and #5; Open Question OQ-1 on the memory-disable API shape. |
| ORCH-03 | JDBC-backed `ChatMemoryRepository` authoritative for the model; `ConversationProjector` decorator synchronously mirrors each turn into `AiConversation`/`AiMessage`. | Code Examples §"ProjectingChatMemoryRepository"; Architecture §"Bean graph". |
| ORCH-04 | `conversationId` scoped to user; `ChatService` rejects replay/continuation of a conversation not owned by the current user. | Architecture §"Ownership check lives at service boundary AND in row-level predicate"; Pitfall #8 (error-channel divergence). |
| ORCH-05 | `ChatService` public API supports `ask(conversationId, question)` (blocking) and `stream(...)` (Phase 7). | Phase 4 is blocking only per D-16 — `ask(...)` ships now, `stream(...)` is a stub or not yet on the interface. |
| ORCH-06 | Default LLM provider: OpenAI-compatible via OpenRouter through `spring.ai.openai.*` + `base-url`; swappable by replacing `ChatModel` bean. | Standard Stack §"OpenRouter wire-up"; Pitfall #6 (OpenRouter slug format). |
| AUD-01 | `AuditAdvisor` writes pre/post `AiToolCallAudit` entries. | Code Examples §"AuditAdvisor skeleton"; Architecture §"Audit pipeline". |
| AUD-02 | Audit persistence in `@Transactional(propagation = REQUIRES_NEW)` — tool rollback does not lose audit. | Don't Hand-Roll §"Transaction boundaries"; Pitfall #3 (`this.`-invocation bypasses proxy). |
| AUD-03 | Audit records include: conversationId, userId, tool name, input JSON, output summary, latency, outcome, denial reason. | Architecture §"Audit row shape"; enum values already defined in Phase 2 (`AiToolCallOutcome`). |
| AUD-04 | `AuditListener` SPI fires after each audit write; listener exceptions must not fail main flow. | Code Examples §"afterCommit fan-out with per-listener try/catch". |
| AUD-05 | Audit cannot be silently disabled — enforced by unit tests. | Testing Patterns §"Boot-time `@PostConstruct` assertion + structural test that asserts all three audit rows on a successful ask." |
| SPI-02 | `ContextContributor` — inject per-request context (user, tenant, env) into prompt. | Architecture §"Baseline context is first-class (D-15), not SPI"; SPI default stays no-op from Phase 2. |
| SPI-03 | `PromptContextContributor` — augment system prompt with host-specific instructions. | Same as SPI-02 — default stays no-op; Phase 6 will wire the real prompt chain. |
| SPI-06 | `AuditListener` — observe audit writes for side-channels. | Code Examples §"AuditListenerFanOut"; afterCommit pattern. |
| TEST-02 (partial) | Unit tests: audit entity construction. | Testing Patterns §"Unit test layer". |
| TEST-03 | Integration tests: auto-config boots; `ChatService.ask` round-trips with mock `ChatModel`; advisor ordering preserved; tool call audited. | Testing Patterns §"Integration test layer with Mockito-stubbed `ChatModel`". |
| TEST-05 | `@Tag("live")` opt-in tier uses semantic-similarity assertions. | Testing Patterns §"Live smoke tier"; Phase 1 D-04 convention carried forward. |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| ChatClient assembly | Spring Boot auto-config in add-on `AIAutoConfiguration` | — | One-time startup work; cached client is the D-01 invariant. |
| Per-request orchestration (tools, options, conversationId) | Service layer — `DefaultChatServiceImpl` | — | `.prompt()` is a per-request builder; services own request-scoped state. |
| Conversation ownership enforcement | Service layer — `ConversationGateway` pre-check | Data layer — Jmix row-level predicate on `AiConversation.createdBy` (Phase 2 D-08) | Defence in depth; same error surface for both failure cases (D-09). |
| Chat memory read/write (context window) | Framework — `JdbcChatMemoryRepository` | — | Spring AI's own abstraction; `SPRING_AI_CHAT_MEMORY` is the model's source of truth. |
| Dual-layer projection to Jmix entities | Decorator over framework — `ProjectingChatMemoryRepository` | Data layer — `DataManager.save(AiMessage)` | Wraps framework call in the same transaction (D-07) so Jmix layer cannot drift. |
| Chat-level audit (pre/post) | Advisor layer — `AuditAdvisor` (outermost `CallAdvisor`) | Data layer — `AuditWriter` bean with `REQUIRES_NEW` | Wrapping the advisor chain sees true latency and all errors (D-10/D-11). |
| Per-tool-call audit | Tool callback decorator — `ToolCallbackAuditDecorator` around `MethodToolCallback` | Data layer — `AuditWriter.writeToolCall(...)` `REQUIRES_NEW` | Decorator sees every `@Tool` invocation; audit row survives tool rollback (AUD-02). |
| Listener fan-out | Post-commit hook — `AuditListenerFanOut` registered via `TransactionSynchronizationManager.afterCommit` | — | Listeners see persisted rows only (D-14); exceptions isolated per listener (D-13). |
| Baseline context assembly | Service-adjacent `@Component` — `BaselineContextProvider` | System prompt `.system(...)` call in `DefaultChatServiceImpl` | First-class per D-15; NOT an SPI default — SPI defaults stay no-op. |
| Active `AiParameters` resolution | Service layer — `AiParametersResolver` with `@ConfigurationProperties` fallback | Data layer — `DataManager.load(AiParameters).query("e.active = true")` | Reads per request (D-04); no caching so Phase 6 profile-swap is immediate. |
| LLM HTTP call | Framework — Spring AI `OpenAiChatModel` → OpenRouter | — | `base-url` override keeps the model-provider swap as a bean replacement (ORCH-06). |

## Standard Stack

### Core — already on classpath from prior phases

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.ai:spring-ai-bom` | **1.1.4** | Pins every `spring-ai-*` artifact. | `[VERIFIED: ai-agent/build.gradle:35]` BOM-driven versioning is the canonical Spring AI idiom; prevents version skew across `starter-model-openai` and `starter-model-chat-memory-repository-jdbc`. |
| `org.springframework.ai:spring-ai-starter-model-openai` | via BOM (1.1.4) | OpenAI-compatible `ChatModel` auto-configuration; `OpenAiChatOptions`. | `[VERIFIED: Phase 1 docs; Context7 1.1.2 OpenAI chat properties table]` OpenRouter is OpenAI-protocol-compatible — one starter covers both. |
| `org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc` | via BOM (1.1.4) | Auto-configures `JdbcChatMemoryRepository` bean + `initialize-schema` property. | `[VERIFIED: Context7 1.1.2 chat-memory.adoc — "After adding the JDBC dependency, Spring AI can auto-configure a JdbcChatMemoryRepository"]` The add-on's Phase 2 `SPRING_AI_CHAT_MEMORY` Liquibase changelog + `initialize-schema: never` matches the docs' recommended posture. |
| `io.jmix.core:jmix-core-starter` | 2.8.0 | `DataManager`, `Metadata`, `@Transactional` integration, `AccessManager`. | `[VERIFIED: build.gradle]` |
| `io.jmix.security:jmix-security-starter` | 2.8.0 | `@ResourceRole`, row-level predicates (shipped in Phase 2). | `[VERIFIED: Phase 2]` |

**No new dependencies** are required for Phase 4 — every piece is already on the classpath from Phases 1–3. Verified against `ai-agent/build.gradle` current state.

### Supporting — confirmed present

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-test` | via Boot BOM | JUnit 5, AssertJ, Mockito, `OutputCaptureExtension`. | Unit + integration tests in `ai-agent/src/test`. |
| `com.h2database:h2` | via Boot BOM | Test DB for `@SpringBootTest` integration tests. | Test-scope only. |
| `org.springframework.ai:spring-ai-test` | via BOM | Semantic-similarity assertions for `@Tag("live")` smoke. | Optional — `[ASSUMED]` available in 1.1.4 per the AI-SPEC claim; planner MUST verify at Task 1 via `./gradlew dependencies --configuration testCompileClasspath \| grep spring-ai-test`. If absent, fall back to plain `String.contains(...)` on the canned "pong" token. |

### Version verification

Spring AI 1.1.4 was not directly indexable in Context7 (closest indexed version: 1.1.2). The 1.1.x line is point-release API-stable. Verification performed via:
- `ai-agent/build.gradle:35`: `ext.set('springAiVersion', "1.1.4")` `[VERIFIED: local file]`
- `build.gradle:25`: `maven { url = 'https://repo.spring.io/milestone' }` — milestone repo required for 1.1.4 M4 jars `[VERIFIED: local file]`
- Context7 `/spring-projects/spring-ai/v1.1.2` docs — used as the authoritative API-shape reference; all 1.1.2 signatures quoted below apply unchanged to 1.1.4 unless the one flagged gap (ToolCallAdvisor memory-disable method name) turns out to have shifted between 1.1.2 and 1.1.4, in which case Rule 1 applies and the planner records the deviation.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `MethodToolCallback` audit decorator | AOP `@Around` on `@Tool` methods | AOP on Spring AI `@Tool` methods is fragile — Spring AI invokes tool methods reflectively through `ToolCallingManager`; Spring's proxy-based AOP does not reliably wrap those invocations. Decorator pattern on `ToolCallback` is the idiomatic seam and is exactly what D-09 Phase 3 (`.tools(...)` per-request assembly) is built to accept. |
| `JdbcChatMemoryRepository` decorator for projection | Post-commit `TransactionSynchronization` hook firing from inside `MessageChatMemoryAdvisor` | Post-commit is async-friendly but violates D-07 "same transaction" — if the projection write fails, the Spring AI memory insert must roll back too so the two layers cannot desync. Decorator in the same `REQUIRED` transaction is the only way to meet the D-05/D-07 invariant. |
| Service-boundary ownership check (D-09) | Rely solely on row-level predicate | Row-level predicate hides rows but does not produce a clean, semantic exception at the service boundary — `DataManager.load(...).optional()` returning empty is indistinguishable from "no such id" only after an explicit pre-check. Without the pre-check, the first place the absence is noticed might be inside the advisor chain, after an audit pre-row is already written — leaking conversation-id existence through audit-row presence. |

## Architecture Patterns

### System Architecture Diagram

```
                     ┌──────────────────────────────────────────────┐
Caller (CommandLineRunner / future UI / tests)                      │
  │ ChatService.ask(userId, conversationId, message)                │
  ▼                                                                 │
┌──────────────────────┐     ConversationNotFoundException           │
│ DefaultChatServiceImpl│────(same message for missing & not-yours)──▶
│  1. ConversationGateway.loadOrCreate(userId, convId, message)     │
│     └─ DataManager.load(AiConversation).id(convId).optional()     │
│        └─ Jmix row-level predicate: AiConversation.createdBy = user
│  2. AiParametersResolver.resolveActive()                          │
│     └─ DataManager.load(AiParameters).query("e.active = true")    │
│        └─ fallback: jmix.ai-agent.defaults.*                      │
│  3. BaselineContextProvider.compose(user, roles, locale, convId)  │
│  4. chatClient.prompt().system(sys).user(msg)                     │
│         .tools(AuditingToolCallbackProvider(BuiltInDataTools))    │
│         .options(OpenAiChatOptions from profile)                  │
│         .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))  │
│         .call().chatClientResponse()                              │
└────────┬─────────────────────────────────────────────────────────┘
         │
         ▼  cached ChatClient (assembled once at startup)
┌──────────────────────────────────────────────────────────────────┐
│ Advisor chain (outer → inner via BaseAdvisor.HIGHEST_PRECEDENCE+N)│
│                                                                   │
│ ┌─ AuditAdvisor ──────────────────────────────────────────────┐  │
│ │ order = HIGHEST_PRECEDENCE                                   │  │
│ │ RunContext.set(runId=UUID)                                   │  │
│ │ auditWriter.writeChatPre(runId, user, convId, hash) ── REQUIRES_NEW → commits now
│ │ ┌─ MessageChatMemoryAdvisor ──────────────────────────────┐ │  │
│ │ │ order = HIGHEST_PRECEDENCE + 200                         │ │  │
│ │ │ reads SPRING_AI_CHAT_MEMORY via JdbcChatMemoryRepository  │ │  │
│ │ │ injects history into prompt                              │ │  │
│ │ │ ┌─ [RAG advisor slot: HIGHEST_PRECEDENCE + 250, Phase 5]┐│ │  │
│ │ │ │ ┌─ ToolCallAdvisor ─────────────────────────────────┐ ││ │  │
│ │ │ │ │ order = HIGHEST_PRECEDENCE + 300                   │ ││ │  │
│ │ │ │ │ internal-memory DISABLED (see OQ-1)                │ ││ │  │
│ │ │ │ │ loops: ChatModel.call(prompt)                       │ ││ │  │
│ │ │ │ │   └─ HTTP → OpenRouter (OpenAI protocol)            │ ││ │  │
│ │ │ │ │   ← response w/ or w/o tool_calls                   │ ││ │  │
│ │ │ │ │ while response.hasToolCalls():                      │ ││ │  │
│ │ │ │ │   ToolCallingManager.executeToolCalls(...)          │ ││ │  │
│ │ │ │ │     └─ AuditingToolCallbackProvider → ToolCallbackAuditDecorator
│ │ │ │ │         ├─ auditWriter.writeToolCall(runId, ...) REQUIRES_NEW (pre)
│ │ │ │ │         ├─ delegate.call(toolRequest) ── runs BuiltInDataTools tool
│ │ │ │ │         │    └─ DataManager.load(...) — inherits Jmix security
│ │ │ │ │         └─ auditWriter.writeToolCall(runId, outcome, latency) REQUIRES_NEW (post)
│ │ │ │ │ return final ChatResponse                            │ ││ │  │
│ │ │ │ └───────────────────────────────────────────────────────┘ ││ │  │
│ │ │ └───────────────────────────────────────────────────────────┘│ │  │
│ │ │ writes assistant message → SPRING_AI_CHAT_MEMORY              │ │  │
│ │ │   └─ ProjectingChatMemoryRepository decorator wraps saveAll    │ │  │
│ │ │       ├─ delegate.saveAll(convId, messages) → JDBC INSERT      │ │  │
│ │ │       └─ for each msg: DataManager.save(new AiMessage)         │ │  │
│ │ │       (both in the same @Transactional(REQUIRED) tx — D-07)   │ │  │
│ │ └───────────────────────────────────────────────────────────────┘ │  │
│ │ auditWriter.writeChatPost(runId, outcome, latencyMs, errorClass) REQUIRES_NEW
│ │   └─ afterCommit synchronization registered BEFORE tx commits       │  │
│ │        └─ on commit: AuditListenerFanOut.fire(row)                   │  │
│ │              for each AuditListener bean:                            │  │
│ │                 try { listener.onEntry(row); } catch (Throwable t)   │  │
│ │                   { log.warn(...); /* SWALLOW */ }                    │  │
│ └──────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
         │
         ▼
ChatResponseDto { assistantText, runId, usage }
```

**Data flow highlights:**
1. Entry through `ChatService.ask()` — ownership check FIRST, before any advisor or audit write, so a rejected request produces no audit row and reveals nothing via audit-row presence.
2. `ChatClient` is cached; only the `.prompt()` builder is per-request.
3. `AuditWriter` methods are the ONLY `@Transactional(REQUIRES_NEW)` surface — the advisor and the tool-callback decorator are plain methods that delegate to this bean.
4. `ProjectingChatMemoryRepository` is the ONLY place `AiMessage` rows are written — enforces the single-write-path invariant from D-05.
5. Listener fan-out is the only thing that fires after the REQUIRES_NEW tx commits — it runs on the same thread, after commit, with per-listener exception suppression.

### Recommended Project Structure

```
ai-agent/src/main/java/com/vn/agent/
├── orchestration/
│   ├── ChatService.java                          # interface (Phase 1 — extend signature if needed)
│   ├── DefaultChatServiceImpl.java               # D-01 entry point; constructor injection
│   ├── ChatClientFactory.java                    # @Configuration or @Bean in AIAutoConfiguration
│   ├── ConversationGateway.java                  # D-08 + D-09 load-or-create + ownership
│   ├── ConversationNotFoundException.java        # D-09 — identical msg for missing/unauthorised
│   ├── ProjectingChatMemoryRepository.java       # D-05 decorator over JdbcChatMemoryRepository
│   ├── BaselineContextProvider.java              # D-15 first-class (NOT SPI)
│   ├── AiParametersResolver.java                 # D-03/D-04
│   ├── ChatResponseDto.java                      # return type of ask(...)
│   └── advisor/
│       ├── AuditAdvisor.java                     # implements CallAdvisor; HIGHEST_PRECEDENCE
│       └── ToolCallbackAuditDecorator.java       # wraps MethodToolCallback for per-tool audit
├── audit/
│   ├── AuditWriter.java                          # @Transactional(REQUIRES_NEW) methods (D-11)
│   ├── AuditListenerFanOut.java                  # afterCommit synchronization + try/catch
│   ├── RunContext.java                           # ThreadLocal<UUID> runId
│   └── AuditingToolCallbackProvider.java         # decorates per-request ToolCallbackProvider
└── config/
    └── AIAutoConfiguration.java                  # extends Phase 1 — add @Beans above

ai-agent/src/main/resources/
└── com/vn/agent/                                 # i18n for ConversationNotFoundException message
    ├── messages.properties                       # key: com.vn.agent.orchestration/ConversationNotFound
    └── messages_vi.properties
```

No new Liquibase changelogs. No new entities. All tables (`SPRING_AI_CHAT_MEMORY`, `AI_AGENT_CONVERSATION`, `AI_AGENT_MESSAGE`, `AI_AGENT_TOOL_CALL_AUDIT`, `AI_AGENT_PARAMETERS`) already exist from Phase 2.

### Pattern 1: Cached `ChatClient` + per-request `.prompt()`

**What:** Assemble the `ChatClient` once with `defaultAdvisors(...)` at startup; every `ChatService.ask()` invokes `.prompt()...call()` with per-request tools, options, and advisor params.

**When to use:** When the advisor chain is stable per application (our case — Audit → Memory → Tool never changes) but the tool set or options vary per caller.

**Example (verified against Context7 1.1.2):**
```java
// Source: Context7 /spring-projects/spring-ai/v1.1.2 — chatclient.adoc
// Source: 04-AI-SPEC.md §3 Entry Point Pattern
ChatClientResponse response = chatClient.prompt()
        .system(params.getSystemPrompt())
        .user(message)
        .tools(auditingToolCallbackProvider)
        .options(OpenAiChatOptions.builder()
                .model(params.getModel())
                .temperature(params.getTemperature())
                .maxTokens(params.getMaxTokens())
                .build())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.toString()))
        .call()
        .chatClientResponse();
```

### Pattern 2: `CallAdvisor` implementing `adviseCall(...)` — verified signature

**What:** Outermost audit hook wraps the chain; captures true latency and all errors including pre-memory/pre-tool failures.

**Example (verified interface shape):**
```java
// Source: Context7 /spring-projects/spring-ai/v1.1.2 — advisors.adoc
// CallAdvisor interface:
//   ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain);
// CallAdvisorChain.nextCall(req) passes control down the chain.

public class AuditAdvisor implements CallAdvisor {

    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditAdvisor(AuditWriter auditWriter, CurrentAuthentication ca) {
        this.auditWriter = auditWriter;
        this.currentAuthentication = ca;
    }

    @Override public String getName() { return "AuditAdvisor"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        UUID runId = UUID.randomUUID();
        RunContext.set(runId);
        UUID userId = currentAuthentication.getUser() != null
                ? (UUID) currentAuthentication.getUser().getKey() : null;
        UUID convId = conversationIdFrom(req);  // read from advisor context
        auditWriter.writeChatPre(runId, userId, convId, sha256(req));
        long t0 = System.nanoTime();
        try {
            ChatClientResponse resp = chain.nextCall(req);
            auditWriter.writeChatPost(runId, AiToolCallOutcome.SUCCESS,
                    elapsedMs(t0), null);
            return resp;
        } catch (Throwable t) {
            auditWriter.writeChatPost(runId, AiToolCallOutcome.FAILED,
                    elapsedMs(t0), t.getClass().getSimpleName());
            throw t;
        } finally {
            RunContext.clear();
        }
    }
}
```

### Pattern 3: `ChatMemoryRepository` decorator for dual-layer projection

**What:** Wrap `JdbcChatMemoryRepository` so every `saveAll(...)` writes both the Spring AI `SPRING_AI_CHAT_MEMORY` row (via delegate) AND the Jmix `AiMessage` row (via `DataManager.save`) in the same `REQUIRED` transaction. Atomic by construction.

**Example (verified interface methods — `findConversationIds()`, `deleteByConversationId()` confirmed in Context7):**
```java
// Source: Context7 /spring-projects/spring-ai/v1.1.2 — chat-memory.adoc
// ChatMemoryRepository abstraction; JdbcChatMemoryRepository implements.
// Confirmed methods: findConversationIds(), deleteByConversationId(convId).
// findByConversationId(convId) and saveAll(convId, messages) are standard —
// verify exact signatures at Task 1 via IDE. [VERIFIED: Context7 1.1.2 + ASSUMED: saveAll/findByConversationId names]

public class ProjectingChatMemoryRepository implements ChatMemoryRepository {
    private final JdbcChatMemoryRepository delegate;
    private final DataManager dataManager;
    private final Metadata metadata;

    public ProjectingChatMemoryRepository(JdbcChatMemoryRepository delegate,
                                          DataManager dataManager,
                                          Metadata metadata) {
        this.delegate = delegate;
        this.dataManager = dataManager;
        this.metadata = metadata;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)   // D-07: SAME tx as advisor's
    public void saveAll(String conversationId, List<Message> messages) {
        delegate.saveAll(conversationId, messages);
        for (Message m : messages) {
            AiMessage entity = metadata.create(AiMessage.class);     // CLAUDE.md rule
            entity.setConversationId(UUID.fromString(conversationId));
            entity.setRole(mapRole(m.getMessageType()));             // USER/ASSISTANT/SYSTEM/TOOL
            entity.setContent(m.getText());
            dataManager.save(entity);                                // CLAUDE.md rule
        }
    }

    @Override public List<Message> findByConversationId(String convId) { return delegate.findByConversationId(convId); }
    @Override public void deleteByConversationId(String convId)         { delegate.deleteByConversationId(convId); }
    @Override public List<String> findConversationIds()                 { return delegate.findConversationIds(); }
}
```

### Pattern 4: `afterCommit` listener fan-out with per-listener try/catch

**What:** Register a `TransactionSynchronization` inside `AuditWriter.writeXxx` so listener fan-out fires **only after** the REQUIRES_NEW tx commits — listeners never see rolled-back rows (D-14). Each listener runs inside its own try/catch so one throwing listener cannot block others (D-13).

**Example:**
```java
// Source: Spring Framework TransactionSynchronizationManager — standard Spring pattern,
// not a Spring AI construct. [VERIFIED: standard Spring Boot / Spring Data idiom]
// Listener contract: best-effort, must be fast, must swallow own exceptions
// because the add-on does so defensively on their behalf.

@Component
public class AuditWriter {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AuditListenerFanOut fanOut;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeChatPre(UUID runId, UUID userId, UUID convId, String promptHash) {
        AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
        row.setRunId(runId);
        row.setKind(AiToolCallAuditKind.CHAT);
        row.setPhase(AiAuditPhase.PRE);
        row.setUserId(userId);
        row.setConversationId(convId);
        row.setPromptHash(promptHash);
        AiToolCallAudit saved = dataManager.save(row);
        registerFanOutOnCommit(saved);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeChatPost(UUID runId, AiToolCallOutcome outcome, long latencyMs, String errorClass) { /* ... */ }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeToolCall(UUID runId, String toolName, String argsJson,
                              String outputSummary, AiToolCallOutcome outcome,
                              long latencyMs, String errorClass, String denialReason) { /* ... */ }

    private void registerFanOutOnCommit(AiToolCallAudit row) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { fanOut.fire(row); }
                });
        } else {
            // Fallback — should not happen with REQUIRES_NEW active, but defensive
            fanOut.fire(row);
        }
    }
}

@Component
public class AuditListenerFanOut {
    private final List<AuditListener> listeners;     // injected by Spring — order per bean-order
    private static final Logger log = LoggerFactory.getLogger(AuditListenerFanOut.class);

    public AuditListenerFanOut(List<AuditListener> listeners) { this.listeners = listeners; }

    public void fire(AiToolCallAudit row) {
        for (AuditListener listener : listeners) {
            try {
                listener.onEntry(row);
            } catch (Throwable t) {
                log.warn("AuditListener {} threw — suppressed", listener.getClass().getName(), t);
            }
        }
    }
}
```

### Anti-Patterns to Avoid

- **`@Transactional(REQUIRES_NEW)` on an advisor method or decorator method.** Spring's proxy-based AOP does NOT apply `@Transactional` to `this.`-qualified calls within the same bean, AND the advisor is typically instantiated as a plain object passed to `ChatClient.Builder.defaultAdvisors(...)` — it may not even be a Spring-proxied bean. REQUIRES_NEW lives on the `AuditWriter` bean only, which is a plain `@Component` and fully proxied (D-11).
- **`.defaultTools(...)` on `ChatClient.Builder`.** Phase 3 D-10 mandates per-request `.tools(...)` so each request sees only the caller's effective tool set. Reintroducing `.defaultTools(...)` at Phase 4 would silently widen access.
- **Passing `conversationId` as a top-level argument or embedded in `.user(...)`.** The memory advisor ONLY reads it from the advisor param `ChatMemory.CONVERSATION_ID`. Any other placement silently uses a default id and history appears empty on turn 2.
- **Divergent exception messages for "conv-id does not exist" vs "conv-id exists but not yours".** `ConversationNotFoundException` MUST be constructed with the exact same message string in both branches of `ConversationGateway.loadOrCreate(...)` so `String.equals` on the message yields true. Same exception class, same key (`msg://com.vn.agent.orchestration/ConversationNotFound` or equivalent), no dynamic text.
- **Writing an audit row on the ownership-rejection path.** Do NOT write a `FAILED` chat-pre audit row when `ConversationGateway` rejects — the presence/absence of the audit row itself would leak conversation-id existence. Rejection is log-only (structured JSON log line), not audit-backed.
- **Holding `SecurityContext` / `CurrentAuthentication` across the advisor chain in a way that survives the REQUIRES_NEW tx boundary.** `AuditWriter.writeXxx` runs in a new tx — Spring Security's `SecurityContextHolder` is ThreadLocal, so it survives the inner tx fine, BUT if the planner ever switches to `@Async` audit writes (Phase 8 maybe), the context must be explicitly propagated. Phase 4 is sync-only; this is a forward-compat warning.
- **Inlining BASELINE context into the user message instead of the system prompt.** Puts user-controlled message next to trusted context, opens injection (the whole point of Phase 3's `<data>`-delimited formatter is to keep these separate at the tool-result layer; the same discipline applies to system-vs-user).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Chat memory persistence | Custom JDBC DAO for message history | `JdbcChatMemoryRepository` from `spring-ai-starter-model-chat-memory-repository-jdbc` | Handles vendor-specific SQL dialects (Postgres, MySQL, HSQL, H2); schema script is known-good; upgrade path is a BOM bump. |
| Advisor chain ordering | A custom chain-of-responsibility for pre/post hooks | `ChatClient.Builder.defaultAdvisors(...)` with `CallAdvisor` implementations and `BaseAdvisor.HIGHEST_PRECEDENCE + N` order | `Ordered` contract is already wired; tested across Spring AI's entire advisor surface; planner verifies order via `ChatClient` bean reflection in a single structural test (E5 in AI-SPEC). |
| Conversation id → history lookup | Custom map of `convId → List<Message>` | `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(20).build()` — passes ID through `ChatMemory.CONVERSATION_ID` advisor param | The windowing, oldest-first eviction, and role-label mapping are already solved; reimplementing means re-solving every edge case around role conversion (USER vs HUMAN etc.). |
| Tool-call loop | Manual `while (response.hasToolCalls())` loop in `ChatService` | `ToolCallAdvisor.builder().toolCallingManager(mgr).advisorOrder(HIGHEST_PRECEDENCE+300).build()` as an advisor | Spring AI handles tool-arg JSON parse errors, tool-result serialization, multi-round iteration, and `returnDirect` short-circuit. Rebuilding means rebuilding every one of those. Phase 6 adds an iteration cap as a manager config; don't pre-empt. |
| OpenRouter HTTP client | Custom `RestTemplate`/`WebClient` to OpenRouter | `spring-ai-starter-model-openai` with `spring.ai.openai.base-url: https://openrouter.ai/api/v1` | OpenRouter is OpenAI-protocol-compatible. The starter gives streaming support (Phase 7), SSE parsing, retry, timeout configuration for free. |
| Per-request options | `Map<String, Object>` or custom options bag | `OpenAiChatOptions.builder().model(...).temperature(...).build()` passed to `.options(...)` | Provider-typed options class validates fields at build time; swapping providers (future host override) keeps the same `ChatClient.options(...)` call site. |
| Per-tool-call audit via AOP | `@Around("@annotation(Tool)")` | `ToolCallbackAuditDecorator` wrapping each `MethodToolCallback` inside `AuditingToolCallbackProvider` | AOP on Spring AI `@Tool` methods is unreliable — Spring AI invokes tool methods reflectively through `ToolCallingManager`, not through the Spring proxy. Decorator is the seam Spring AI actually respects. |
| Post-commit listener fan-out | `ApplicationEventPublisher` + `@TransactionalEventListener(phase=AFTER_COMMIT)` | `TransactionSynchronizationManager.registerSynchronization(...)` in `AuditWriter` | `@TransactionalEventListener` works too, BUT it fires on the outer transaction's commit — and Phase 4's writes are REQUIRES_NEW (inner tx). Event listeners keyed to the outer tx fire at the wrong time. Direct `TransactionSynchronization` targets the inner tx's commit, which is the invariant we want. |
| SHA-256 for `promptHash` | Custom truncation/hash | `java.security.MessageDigest.getInstance("SHA-256")` | Standard JDK. No dependency, well-behaved, collision-resistant enough for correlation purposes (not security). |
| Ownership check | A row-level filter reimplementation | Phase 2's `AiAgentUserRowLevelRole` JPQL predicate `:current_user_username` (already shipped) + pre-check via `DataManager.load(...).optional()` | Both layers already exist; Phase 4 just calls them in the right order. |
| i18n for `ConversationNotFoundException` | Hardcoded English string | `msg://com.vn.agent.orchestration/ConversationNotFound` in both locales | CLAUDE.md mandates — and the same-error-for-both-cases invariant depends on the string coming from one place. |

**Key insight:** Phase 4 is a wiring phase. Nine of the ten "don't hand-roll" items above are things Spring AI / Spring Framework / Jmix already own. The add-on's value-add is exactly three things: (1) the dual-layer projection decorator, (2) the audit pipeline with REQUIRES_NEW boundary, (3) the ownership-opacity check. Everything else is composition of existing abstractions.

## Runtime State Inventory

**Not applicable.** Phase 4 is a greenfield implementation phase — it adds new beans, a new advisor, and new service methods. No renames, refactors, or migrations. No runtime state carries old identifiers.

Explicit answers for the checklist:
- **Stored data:** Nothing to migrate. `SPRING_AI_CHAT_MEMORY` was shipped in Phase 2 and is empty (no prior Phase 4 runtime). `AiConversation`/`AiMessage`/`AiToolCallAudit` are empty. No data format changes.
- **Live service config:** None — this is an add-on, not a service platform. OpenRouter's `api-key` is the only external credential and is already in `application.yml` from Phase 1.
- **OS-registered state:** None.
- **Secrets and env vars:** `OPENROUTER_API_KEY` already referenced in Phase 1's `application.yml`. No new secret names.
- **Build artifacts:** None — no renamed modules, no published artifacts that would carry a stale name.

## Common Pitfalls

### Pitfall 1: `ToolCallAdvisor` double-history when `MessageChatMemoryAdvisor` is present

**What goes wrong:** The `ToolCallAdvisor`'s tool-call loop maintains its own conversation history for the sub-chain. Combined with a `MessageChatMemoryAdvisor` writing to `SPRING_AI_CHAT_MEMORY`, each turn's messages can be recorded twice and tool-intermediate messages leak into long-term memory.

**Why it happens:** Spring AI 1.1.x intentionally allows `ToolCallAdvisor` to be used standalone (without an outer memory advisor) OR with one. The default behavior assumes standalone; when an outer memory advisor is present, you MUST disable the tool advisor's internal history management.

**How to avoid:** When `MessageChatMemoryAdvisor` is in the chain, call the disable-internal-history method on `ToolCallAdvisor.builder()`. See Open Question OQ-1 — the exact method name (`disableMemory()` vs `disableInternalConversationHistory()` vs `conversationHistoryEnabled(false)`) is the one unverified claim in the AI-SPEC and must be confirmed against the 1.1.4 jar at Task 1.

**Warning signs:**
- Assistant "remembers" tool call details it shouldn't (tool intermediate responses showing up in summaries).
- `SELECT * FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?` returns duplicate rows for the same user turn.
- Context-window usage grows ~2× faster than expected.

### Pitfall 2: `.defaultTools(...)` cements tools into the cached `ChatClient`

**What goes wrong:** Per Phase 3 D-10, effective tool set is per-user (depends on caller's `AccessManager` roles). Using `.defaultTools(...)` on the builder bakes a single tool set into the cached client.

**Why it happens:** `.defaultTools(...)` is the obvious API when reading `ChatClient.Builder`; many Spring AI tutorials use it because they don't have per-user access control.

**How to avoid:** Use per-request `.tools(...)` on the `.prompt()` builder, fed by the Phase 3 `AgentToolCallbacks` / per-request `ToolCallbackProvider`. The Phase 4 `AuditingToolCallbackProvider` is a decorator around that same provider. A static-analysis check (Phase 3's ASM test) catches `.defaultTools(...)` calls at build time — verify it still does after Phase 4 additions.

**Warning signs:**
- Restricted-user fixture gets tool responses it shouldn't (authorization-parity test E1 fails).
- `.defaultTools(...)` appears anywhere in `git grep` over `ai-agent/src/main`.

### Pitfall 3: `initialize-schema` left at default (`embedded`) → schema race

**What goes wrong:** Without explicit `spring.ai.chat.memory.repository.jdbc.initialize-schema: never`, Spring AI auto-creates the `SPRING_AI_CHAT_MEMORY` table on embedded databases (H2, HSQL, Derby) — racing with the Phase 2 Liquibase changelog and sometimes producing column drift.

**Why it happens:** The default `embedded` value is friendly for single-file tutorials but hostile for add-ons that ship their own DDL.

**How to avoid:** `application.yml` already has `spring.ai.chat.memory.repository.jdbc.initialize-schema: never` per Phase 2 plan 02-05. Verify this is present in every `application.yml` variant — the main one AND any test profile overrides. `[VERIFIED: Context7 /spring-projects/spring-ai/v1.1.2 — "initialize-schema" takes {embedded, always, never}; defaults to embedded for schema init]`.

**Warning signs:**
- HSQL / H2 test failures during Liquibase migration with "table already exists".
- Intermittent column-missing errors in `SPRING_AI_CHAT_MEMORY` queries.

### Pitfall 4: `MessageChatMemoryAdvisor` requires `CONVERSATION_ID` as advisor param

**What goes wrong:** Passing the conversation id inside `.user(...)`, as a system message placeholder, or as a `ChatClient.Builder.defaultSystem(...)` variable silently falls back to a default conversation id — history appears empty on turn 2.

**Why it happens:** The memory advisor reads ONLY from the advisor context map via `ChatMemory.CONVERSATION_ID` key. The `.param(...)` method on the advisor spec is the single correct call site.

**How to avoid:** Every `.prompt()` chain MUST include `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.toString()))`. `[VERIFIED: Context7 /spring-projects/spring-ai/v1.1.2 — every chat-memory example passes it this way]`. The integration test suite's dual-layer parity assertion (E4 in AI-SPEC) catches regressions.

**Warning signs:** Second-turn assistant says "I don't have context on..." when the user reasonably expects memory.

### Pitfall 5: Advisor `getOrder()` semantics — lower = outermost

**What goes wrong:** Thinking "highest precedence = innermost" — the Spring `Ordered` contract is the opposite.

**Why it happens:** The word "precedence" in `Ordered.HIGHEST_PRECEDENCE` = `Integer.MIN_VALUE` is counterintuitive — it means "runs first on request phase, last on response phase", i.e., outermost.

**How to avoid:** Assign orders as follows, enforced by a unit test that reflects on the `ChatClient` bean's advisor list:
```
AuditAdvisor              → Ordered.HIGHEST_PRECEDENCE     = MIN_VALUE
MessageChatMemoryAdvisor  → BaseAdvisor.HIGHEST_PRECEDENCE + 200
[QuestionAnswerAdvisor]   → BaseAdvisor.HIGHEST_PRECEDENCE + 250  (Phase 5 slot reserved)
ToolCallAdvisor           → BaseAdvisor.HIGHEST_PRECEDENCE + 300
```
`[VERIFIED: Context7 advisor-recursive example shows BaseAdvisor.HIGHEST_PRECEDENCE + 300 for ToolCallAdvisor]`.

**Warning signs:**
- Audit post-row latency is too small (doesn't include the model-call round-trip).
- `AuditAdvisor` never sees exceptions thrown by `MessageChatMemoryAdvisor`.

### Pitfall 6: OpenRouter model slugs have the `provider/model` shape

**What goes wrong:** Using bare `gpt-4o-mini` — OpenRouter rejects with 400 "model not found".

**How to avoid:** OpenRouter slugs are `openai/gpt-4o-mini`, `anthropic/claude-3.5-sonnet`, `google/gemini-1.5-pro`. `AiParametersResolver` validates the slug format (must contain a slash). The `jmix.ai-agent.defaults.model` fallback in `application.yml` uses the slug form too.

**Warning signs:** HTTP 400 responses from the model endpoint with body `{"error": {"code": "invalid_model"}}` or similar.

`[ASSUMED: OpenRouter slug format]` — based on OpenRouter public docs knowledge, NOT first-party Spring AI docs. OpenRouter is not mentioned in Context7's Spring AI 1.1.2 corpus. Planner should spot-check one `@Tag("live")` call against a known-valid slug during Task 1 of the live-smoke plan.

### Pitfall 7: `@Transactional` `this.`-invocation bypasses the proxy

**What goes wrong:** `AuditAdvisor.adviseCall(...)` calls `this.writeChatPre(...)` on a `@Transactional(REQUIRES_NEW)`-annotated method of the SAME bean — Spring's AOP proxy doesn't intercept self-calls, so no new tx starts.

**Why it happens:** Classic Spring gotcha; particularly easy to trip when the advisor is also doing the audit write inline.

**How to avoid:** `@Transactional(REQUIRES_NEW)` lives on a DEDICATED `AuditWriter` bean — the advisor injects `AuditWriter` via constructor and calls `auditWriter.writeChatPre(...)`, which IS a proxied call and DOES start a new tx. This is exactly what D-11 mandates.

**Warning signs:**
- Integration test E3 (audit durability under tool rollback) fails — audit rows missing after forced tool failure.
- Debug logs show tx start/commit happening on the outer tx only, never on a nested REQUIRES_NEW.

### Pitfall 8: Error-channel divergence for ownership rejection

**What goes wrong:** `ChatService.ask(userA, convId-owned-by-userB, ...)` throws `AccessDeniedException` while `ChatService.ask(userA, nonexistent-uuid, ...)` throws `ConversationNotFoundException` — attacker enumerates conversation ids by distinguishing the two.

**Why it happens:** Straightforward coding: the row-level predicate hides userB's row, making it look "not found"; but the developer also adds a separate code path for "you don't own this" thinking they're being helpful.

**How to avoid:** ONE code path. `ConversationGateway.loadOrCreate(userId, convId, message)` calls `dataManager.load(AiConversation.class).id(convId).optional()`; if the result is empty AND the caller didn't pass a fresh brand-new id (first-turn case), throw `ConversationNotFoundException` with the SAME message in BOTH branches. No audit row, no divergent log line, same latency profile (both paths hit the same `DataManager.load`). Integration test E2 asserts equality of `{exception.class, exception.message}` for the two cases.

**Warning signs:** Any condition check that produces a different exception for row-level-denied vs. does-not-exist.

### Pitfall 9: Dual-layer drift after Spring AI minor upgrade

**What goes wrong:** A future 1.1.x → 1.2.x or 1.2.x → 2.x upgrade reshapes `SPRING_AI_CHAT_MEMORY` columns; the framework side keeps writing to the new shape, but the Jmix `AiMessage` layer keeps working too. The two diverge silently — the Jmix-visible replay looks correct while the model sees a trimmed context.

**How to avoid:** Phase 4 ships **a parity assertion `@AfterEach` helper** that compares `JdbcChatMemoryRepository.findByConversationId(convId)` tuple-for-tuple with `DataManager.load(AiMessage.class).query(...).list()`. Any integration test running the memory path trips this assertion automatically on divergence. Treat an assertion failure after a BOM bump as an upgrade-hazard signal, not a test flake.

**Warning signs (post-upgrade):** Intermittent "model answered as if it didn't know" reports on multi-turn conversations; `AiMessage` replay and `SPRING_AI_CHAT_MEMORY` row count diverge.

### Pitfall 10: Listener fan-out exception contagion

**What goes wrong:** A host-supplied `AuditListener` throws; without per-listener try/catch the throw propagates into the chat request thread and (worse) the REQUIRES_NEW audit tx rolls back.

**How to avoid:** `AuditListenerFanOut.fire(row)` wraps each `listener.onEntry(row)` in `try { ... } catch (Throwable t) { log.warn(...); }`. Register the fan-out as an `afterCommit` synchronization, NOT `afterCompletion` — `afterCommit` fires only on successful commit, guaranteeing listeners never see rolled-back rows. `[VERIFIED: Spring Framework's TransactionSynchronization.afterCommit javadoc — "Invoked after transaction commit. Can perform resource cleanup after the main transaction has successfully committed."]`

**Warning signs:** Chat request returns 500 when a single listener's downstream (e.g., Slack webhook) is down; audit rows missing for calls that should have succeeded.

## Code Examples

All verified patterns appear inline under "Architecture Patterns" (Pattern 1–4 above) and in AI-SPEC sections 3 and 4. Cross-reference only — no duplication here.

- `ChatClient` assembly (`AIAutoConfiguration`): 04-AI-SPEC.md §3 "Entry Point Pattern" AND §4 "Core Pattern" (advisor ordering literal code shown).
- `AuditAdvisor` skeleton: 04-AI-SPEC.md §4 "Core Pattern"; this document's Pattern 2.
- `ProjectingChatMemoryRepository`: 04-AI-SPEC.md §4 "State Management"; this document's Pattern 3.
- Per-request `.prompt()` call (`DefaultChatServiceImpl.ask`): 04-AI-SPEC.md §3 "Entry Point Pattern".
- `AuditWriter` + `AuditListenerFanOut`: this document's Pattern 4 (expands AI-SPEC's D-11/D-13/D-14 into actual code).
- `application.yml` config (`spring.ai.openai.*`, `initialize-schema: never`, `jmix.ai-agent.defaults.*`): 04-AI-SPEC.md §3 "Installation".

## Testing Patterns

Phase 4 test strategy mirrors Phase 3's three-tier structure and Phase 1's `@Tag("live")` convention. Full dimensions in 04-AI-SPEC.md §5.

### Unit-test layer (`src/test/java`, `./gradlew test`)
- `AuditAdvisorTest` — instantiate with a Mockito-stubbed `AuditWriter`; call `adviseCall` with a mock chain; assert pre/post calls + latency range + `errorClass` on thrown path.
- `ProjectingChatMemoryRepositoryTest` — Mockito-stub `JdbcChatMemoryRepository` + `DataManager`; assert both receive the expected calls for a 3-message `saveAll`.
- `ConversationGatewayTest` — assert SAME exception class + SAME message text for `id-not-exists` vs `id-owned-by-other-user` inputs. Parameterized.
- `AuditListenerFanOutTest` — two listener stubs (one throws, one records); assert recorder got the row, thrower's exception is swallowed and logged (via `OutputCaptureExtension`).
- `AiParametersResolverTest` — assert fallback to `jmix.ai-agent.defaults.*` when `DataManager.load` returns empty.

### Integration-test layer (`@SpringBootTest` with mock `ChatModel`)
- `ChatServiceIntegrationTest` — boot app with `ChatModel` mocked to return canned responses (including one with tool_calls); call `ChatService.ask(...)`; assert rows in `AiConversation`, `AiMessage`, `AiToolCallAudit` (chat-pre + chat-post + tool-pre + tool-post).
- `AdvisorOrderStructuralTest` — the cheapest highest-leverage test in the suite. Reflect on the bean `ChatClient`'s advisor list; assert `AuditAdvisor.getOrder() == HIGHEST_PRECEDENCE` AND `MessageChatMemoryAdvisor.getOrder() < ToolCallAdvisor.getOrder()` AND the ToolCallAdvisor's internal-memory flag is disabled (see OQ-1 for how to check the flag — likely via reflection on a private field or a boolean getter).
- `OwnershipOpacityTest` — parameterized; User-A probes User-B's convId AND a nonexistent convId; AssertJ `{class, message}` equality assertion.
- `AuditDurabilityTest` — three scenarios: (a) `@Tool` throws, (b) advisor throws post-tool, (c) throwing `AuditListener` bean registered. Assert audit row count + shape after each.
- `DualLayerParityTest` — 3-turn scripted conversation; `@AfterEach` helper compares `JdbcChatMemoryRepository.findByConversationId` with `DataManager.load(AiMessage).query`.
- `ConsumerSmokeTest` (in `jmix-app`) — boot host with only `OPENROUTER_API_KEY` set (and mock `ChatModel`); inject `ChatService`; make one call; assert Jmix-observable effects.

### `@Tag("live")` layer — opt-in only
- `OpenRouterSmokeTest` — one real call, prompt "Reply with the single word: pong."; case-insensitive contains-check. Uses `spring-ai-test` semantic-similarity helper IF the dep is on classpath; else falls back to literal contains.

### Gradle task layout
Phase 1 D-04 already split `test` (excludes `@Tag("live")`) from `liveTest`. Reuse unchanged.

## State of the Art

| Old Approach | Current Approach (1.1.x) | When Changed | Impact on Phase 4 |
|--------------|--------------------------|--------------|-------------------|
| `RequestResponseAdvisor` (pre-M3 interface with `adviseRequest` / `adviseResponse`) | `CallAdvisor` (`adviseCall(req, chain)`) + `StreamAdvisor` (`adviseStream(...)`) | Spring AI 1.0.0-M4 → RC1 → 1.0 GA | **AI-SPEC is correct** — uses `CallAdvisor` throughout. Any training-era code snippets referencing `RequestResponseAdvisor` or `adviseRequest` are stale and MUST NOT appear in plans. Planner verifies on every `CallAdvisor` method via `implements CallAdvisor`, not `extends` or old base class. |
| `VectorStoreChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY` = 100 | `VectorStoreChatMemoryAdvisor.TOP_K`, default 20 | 1.0.0-RC1 | Irrelevant to Phase 4 (RAG is Phase 5), but flagged here so Phase 5 planner doesn't hit the same rename. `[VERIFIED: Context7 /spring-projects/spring-ai/v1.1.2 — upgrade-notes.adoc]` |
| `PromptChatMemoryAdvisor` (system-prompt-based memory injection) | `MessageChatMemoryAdvisor` (preferred — structured message collection) | 1.0 GA | Phase 4 uses `MessageChatMemoryAdvisor` per D-02; do NOT swap to `PromptChatMemoryAdvisor` for "clever" system-prompt stuffing — the structured form is what OpenRouter / downstream providers expect, and the Jmix replay fidelity invariant depends on role labels round-tripping unchanged. |
| Manual tool-call loop in user code | `ToolCallAdvisor` recursive advisor | 1.0.0-M3 | AI-SPEC uses the advisor correctly. Do NOT hand-roll a `while (resp.hasToolCalls()) { ... }` loop outside the advisor chain — it bypasses advisor ordering and duplicates logic. |

**Deprecated / outdated for Phase 4:**
- Any reference to `CallAroundAdvisor`, `StreamAroundAdvisor`, `AroundAdvisorChain` — these are old names; the current interfaces are `CallAdvisor`, `StreamAdvisor`, `CallAdvisorChain`, `StreamAdvisorChain`. `[VERIFIED: Context7 1.1.2 advisors.adoc]`
- Any tutorial that doesn't set `spring.ai.chat.memory.repository.jdbc.initialize-schema: never` — tutorial code assumes H2-only single-file app.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `ToolCallAdvisor.builder()` exposes a method to disable internal conversation-history management when an outer `MessageChatMemoryAdvisor` is present. AI-SPEC names it `.disableMemory()`; Phase 4 CONTEXT D-02 assumes it exists. | Standard Stack §"ToolCallAdvisor"; Pitfall #1; Pattern 1 | HIGH — if no such method exists in 1.1.4, the double-history pitfall goes unmitigated. Planner MUST verify method availability at Task 1 via IDE completion on `ToolCallAdvisor.Builder` or by opening the jar. Options if the method is absent: (a) configure `ToolCallingManager` to opt out of internal history at the manager level, (b) pre-filter messages before they enter the memory advisor, (c) accept the double-write and document it. See Open Question OQ-1. `[ASSUMED: .disableMemory() exists]` |
| A2 | `ChatMemoryRepository.saveAll(conversationId, List<Message>)` is the exact method signature Phase 4's decorator overrides. Context7 confirmed `findConversationIds()` and `deleteByConversationId()` but not `saveAll`'s signature. | Pattern 3 | LOW — the shape is standard, but the method name might be `add(conversationId, messages)` or `saveAll(conversationId, messages)` depending on version. Planner opens `ChatMemoryRepository.class` via IDE at Task 1 and matches. `[ASSUMED: saveAll name]` |
| A3 | `spring-ai-test` is available in 1.1.4. AI-SPEC lists it as optional for semantic-similarity assertions. | Supporting libs | LOW — fallback to literal `.contains("pong")` on the live smoke is trivial. `[ASSUMED: module availability]` |
| A4 | OpenRouter accepts requests on OpenAI-compatible `/v1/chat/completions` path — exactly the default `spring.ai.openai.chat.completions-path`. | Pitfall #6 | LOW — OpenRouter's public docs describe themselves as OpenAI-compatible; Phase 1 already proved this works end-to-end (smoke). `[VERIFIED: Phase 1 live smoke passed per STATE.md "Human-verify confirmed 2026-04-18"]` Elevated to VERIFIED. |
| A5 | `spring.ai.openai.base-url` override alone is sufficient for OpenRouter — no custom headers needed beyond the bearer `api-key`. | Pitfall #6 | LOW — Phase 1 smoke confirms. Some OpenRouter routes historically wanted an `HTTP-Referer` or `X-Title` header for free-tier rate-limiting; not required for paid use. If Phase 4 live smoke rate-limits, add `spring.ai.openai.chat.headers` (or equivalent — check Spring AI property surface). |
| A6 | `AiToolCallAudit` entity has columns suitable for `kind`, `phase` (PRE/POST), `runId`, `promptHash`, `outcome`, `latencyMs`, `errorClass`, `denialReason`. The entity was defined in Phase 2. | Code Examples, Architecture | MEDIUM — if Phase 2's `AiToolCallAudit` shape is missing a column (e.g., `phase` enum or `promptHash`), Phase 4 needs a Liquibase addendum. Planner MUST grep the Phase 2 DDL (`ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/030-ai-tool-call-audit.xml` or similar) at Task 0 to confirm the column set matches D-12's requirements. If missing columns, add them in a new Phase 4 changelog numbered above 070. |
| A7 | `BaseAdvisor.HIGHEST_PRECEDENCE` equals `Ordered.HIGHEST_PRECEDENCE` (`Integer.MIN_VALUE`). AI-SPEC uses both interchangeably. | Pattern 2, Pitfall #5 | LOW — `BaseAdvisor` extends the `Ordered` hierarchy. `[VERIFIED: Context7 example uses `BaseAdvisor.HIGHEST_PRECEDENCE + 300`]` confirms the constant exists on that class. |
| A8 | `CurrentAuthentication.getUser().getKey()` returns the Jmix user key (typically UUID) suitable for `AiToolCallAudit.userId`. | Pattern 2 | LOW — standard Jmix API. Planner confirms the exact method (`getUser()` vs `getUser(Class)`) in the `jmix-security` skill. |

## Open Questions

### OQ-1: What is the exact `ToolCallAdvisor` API to disable internal conversation history?

- **What we know:** The AI-SPEC claims `ToolCallAdvisor.builder().disableMemory()` exists in 1.1.4. Context7's 1.1.2 docs do NOT show this method; the docs instead mention `setInternalToolExecutionEnabled(false)` on `ToolCallingChatOptions` (which is a DIFFERENT concern — it disables **in-model** tool execution so the framework's manager runs tools; it is NOT the memory-history disable). The ROADMAP Phase 4 deliverable explicitly mentions `.disableInternalConversationHistory()` as the method name — yet another candidate.
- **What's unclear:** The exact method name on `ToolCallAdvisor.Builder` in 1.1.4 for disabling the advisor's internal conversation-history tracking (when an outer `MessageChatMemoryAdvisor` is already present).
- **Recommendation:** Planner adds a Task 1 verification step: open `ToolCallAdvisor.Builder` via IDE completion, enumerate its methods, find the one that names "memory"/"history"/"conversation", and use it. Record the verified method name as a Rule 1 deviation in the task summary. If NO such method exists, escalate to a design decision: either configure the `ToolCallingManager` at bean-creation time to skip history, OR live with the double-write and add a deduplication step in `ProjectingChatMemoryRepository.saveAll(...)` that drops messages already written in the same tx.

### OQ-2: Does `JdbcChatMemoryRepository` autoconfiguration conflict with the decorator bean?

- **What we know:** The JDBC starter auto-configures a `JdbcChatMemoryRepository` bean AND (implicitly, via the `ChatMemoryRepository` type) a primary `ChatMemoryRepository` bean. Phase 4 wants its `ProjectingChatMemoryRepository` decorator to be the bean `MessageChatMemoryAdvisor` consumes — so the decorator must be the `@Primary ChatMemoryRepository`, or Spring Boot's autoconfig must be overridden with `@ConditionalOnMissingBean(ChatMemoryRepository.class)`.
- **What's unclear:** Exact bean topology. Does the starter register `JdbcChatMemoryRepository` as `ChatMemoryRepository` or as `JdbcChatMemoryRepository`? If the former, `@ConditionalOnMissingBean` on our decorator-producing `@Bean` suppresses the JDBC bean entirely — breaking the decorator which needs the JDBC bean as delegate.
- **Recommendation:** Decorator `@Bean` explicitly depends on `JdbcChatMemoryRepository` (the concrete type), constructs a `ProjectingChatMemoryRepository` from it, and marks itself `@Primary` for the `ChatMemoryRepository` interface. Starter's `JdbcChatMemoryRepository` bean remains; the `@Primary` decorator is what `MessageChatMemoryAdvisor` will be autowired with. Verify with a `@SpringBootTest` that asserts the `MessageChatMemoryAdvisor`'s injected `ChatMemoryRepository` is actually the decorator instance (`assertThat(...).isInstanceOf(ProjectingChatMemoryRepository.class)`).

### OQ-3: Does `MessageChatMemoryAdvisor` consume `ChatMemoryRepository` directly, or always through `ChatMemory`?

- **What we know:** Context7 examples show `MessageChatMemoryAdvisor.builder(chatMemory).build()` — where `chatMemory` is a `MessageWindowChatMemory` built FROM a `ChatMemoryRepository`. So the advisor holds a `ChatMemory`, not a `ChatMemoryRepository`. This means OQ-2's "`@Primary ChatMemoryRepository`" is only load-bearing if something else (e.g., the starter's default `ChatMemory` auto-config) injects `ChatMemoryRepository`.
- **What's unclear:** Is there an auto-configured `ChatMemory` bean, and does it pick up the `@Primary` decorator automatically?
- **Recommendation:** Define BOTH beans explicitly in `AIAutoConfiguration`:
```java
@Bean @Primary
ChatMemoryRepository projectingChatMemoryRepository(JdbcChatMemoryRepository delegate,
                                                    DataManager dm, Metadata md) {
    return new ProjectingChatMemoryRepository(delegate, dm, md);
}

@Bean
ChatMemory chatMemory(ChatMemoryRepository repo) {   // picks up the @Primary decorator
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(20)
            .build();
}
```
This makes the decorator's role explicit, doesn't rely on auto-config behavior, and tests trivially.

### OQ-4: Baseline context (D-15) — where exactly does it attach to the prompt?

- **What we know:** D-15 says `BaselineContextProvider` is first-class, feeds into prompt assembly BEFORE SPI `PromptContextContributor` beans run. But Phase 4's SPI defaults stay no-op, so in Phase 4 baseline is the ONLY context contributor active.
- **What's unclear:** Attach via `.system(...)` on the prompt builder (simple, visible) vs. via a custom `CallAdvisor` that mutates `ChatClientRequest.systemText` (composable with Phase 6 SPI contributors).
- **Recommendation:** Phase 4 does the simple thing — `.system(baselineText + "\n\n" + profileSystemPrompt)` built in `DefaultChatServiceImpl.ask(...)`. Phase 6 refactors to an advisor when the SPI chain lands, at which point the ordering "baseline → profile → SPI contributors" becomes meaningful. No premature abstraction.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | All Phase 4 code | `[VERIFIED: CLAUDE.md Technology Stack]` | — | — |
| Gradle 8.x with `./gradlew` wrapper | Build + test | `[VERIFIED: gradle-wrapper.properties present in repo root]` | — | — |
| Spring AI 1.1.4 BOM via milestone repo | Core framework | `[VERIFIED: build.gradle:25 declares repo.spring.io/milestone; ai-agent/build.gradle:35 pins version]` | 1.1.4 | — |
| OpenRouter API — network reachable from dev + CI | `@Tag("live")` smoke only | `[VERIFIED: Phase 1 smoke passed per STATE.md]` | — | If offline, skip `@Tag("live")` — gate via `-PincludeLiveTests=true` |
| `OPENROUTER_API_KEY` env var | `@Tag("live")` smoke only | Host-dependent | — | Empty string default in `application.yml` makes non-live tests still boot |
| Postgres OR HSQL / H2 for tests | Integration tests (schema + DataManager) | `[VERIFIED: Phase 2 wired HSQL via `jmix-app`]` | — | — |
| Jetbrains MCP (for `get_file_problems`) | Per-task verification (CLAUDE.md workflow) | Host-environment-dependent | — | Planner MUST accept "not available → skip file-problems check and rely on `./gradlew compileJava`" — documented in task Verification blocks |

**Missing dependencies with no fallback:** None for Phase 4.

**Missing dependencies with fallback:** None critical — `@Tag("live")` already gated behind the `-PincludeLiveTests=true` convention from Phase 1 D-04.

## Sources

### Primary (HIGH confidence)
- Context7 `/spring-projects/spring-ai/v1.1.2` — advisor interface (`CallAdvisor`, `CallAdvisorChain`), `MessageChatMemoryAdvisor` builder pattern, `JdbcChatMemoryRepository` configuration, `ChatMemoryRepository.findConversationIds/deleteByConversationId`, `ToolCallAdvisor` builder with `advisorOrder`, `OpenAiChatOptions` connection properties, `ChatMemory.CONVERSATION_ID` advisor-param pattern, upgrade-notes for advisor-API renames from pre-M4 names, `initialize-schema` values (`embedded` / `always` / `never`)
- `ai-agent/build.gradle` (local) — Spring AI version pin (1.1.4), BOM + milestone repo configuration
- `.planning/STATE.md` — Phase 1 human-verified smoke proves OpenRouter wire-up
- `.planning/phases/04-orchestration-core/04-AI-SPEC.md` — comprehensive framework decision, implementation guidance, evaluation strategy already written; this RESEARCH.md extends and verifies rather than duplicates
- `.planning/phases/02-foundations/02-CONTEXT.md` and `.planning/phases/02-foundations/` (referenced via STATE.md) — confirmed entity shapes, Liquibase changesets, `SpiDefaultsAutoConfiguration`, roles
- `.planning/phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` (referenced via STATE.md) — `BuiltInDataTools` + `AgentToolCallbacks` integration points for Phase 4's `AuditingToolCallbackProvider` decorator
- `CLAUDE.md` — binding directives on DataManager, Metadata.create, constructor injection, i18n, workflow

### Secondary (MEDIUM confidence)
- Spring AI docs at https://docs.spring.io/spring-ai/reference/ (implicitly via Context7's source links back to spring-ai repo `v1.1.2` ref) — same content, confirmed via Context7
- Spring Framework `TransactionSynchronizationManager` / `TransactionSynchronization.afterCommit` semantics — well-documented, standard

### Tertiary (LOW confidence — flagged as ASSUMED)
- `ToolCallAdvisor.disableMemory()` / `.disableInternalConversationHistory()` / equivalent — AI-SPEC asserts, Context7 does not confirm. See OQ-1 and Assumption A1.
- OpenRouter model slug format `provider/model` — from general OpenRouter docs knowledge, NOT Spring AI docs. Assumption A4/A5.
- Exact column set of `AiToolCallAudit` matching D-12 — assumed to exist from Phase 2 DDL; planner confirms at Task 0 (Assumption A6).

## Metadata

**Confidence breakdown:**
- Standard stack: **HIGH** — all deps already on classpath; versions pinned; Phase 1 smoke proved the OpenRouter wire-up end-to-end.
- Architecture: **HIGH** — advisor chain, dual-layer projection, REQUIRES_NEW audit pipeline, and afterCommit fan-out are all standard Spring AI + Spring Framework patterns. CallAdvisor interface signature verified against Context7 1.1.2.
- Pitfalls: **MEDIUM-HIGH** — nine of ten pitfalls are verified against Spring AI / Spring Framework docs or are mechanical consequences of the decisions in CONTEXT. Pitfall #1 (ToolCallAdvisor double-history) is correctly identified but the mitigation API name is unverified (OQ-1).
- Pattern 3 (ProjectingChatMemoryRepository): **MEDIUM** — `saveAll(...)` method signature is ASSUMED from standard naming; planner confirms at Task 1.
- OQ-1 (ToolCallAdvisor memory-disable API): **UNRESOLVED** — Context7 gap; planner resolves at code time via IDE completion.

**Research date:** 2026-04-20
**Valid until:** 2026-05-20 (30 days — Spring AI 1.1.x is the GA line and stable; revisit if a BOM bump to 1.2.x / 2.x is proposed)

**Out-of-scope for this research:**
- Streaming with tool calls (Phase 7 concern; D-16 defers).
- `QuestionAnswerAdvisor` / RAG filter expressions (Phase 5 research phase will cover).
- ParametersService CRUD + YAML bootstrap (Phase 6 scope).
- Guardrails / iteration caps / structured output (Phase 6 scope).
- Arize Phoenix / OTel exporter integration (Phase 8 scope; AI-SPEC §7 explicitly defers).
