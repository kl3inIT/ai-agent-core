# Phase 4: Orchestration Core - Context

**Gathered:** 2026-04-20
**Status:** Ready for planning

<domain>
## Phase Boundary

Compose the first end-to-end LLM path: `ChatClient` with verified advisor ordering (Audit → Memory → Tool), JDBC-backed chat memory, a dual-layer conversation persistence scheme (Spring AI memory table + Jmix-owned `AiConversation`/`AiMessage` entities), user-scoped `conversationId` ownership enforcement at the `ChatService` boundary, and the full audit pipeline (chat-level AuditAdvisor + per-tool-call callback wrapper with `REQUIRES_NEW`, plus `AuditListener` SPI fan-out with exception isolation).

In scope: ChatModel/ChatClient wiring to OpenRouter, advisor assembly, chat memory + projection, ownership check, audit write path, listener fan-out, default `ContextContributor`/`PromptContextContributor` impls, mock-based integration tests.

Out of scope (future phases): streaming response path (Phase 7), RAG retrieval advisor (Phase 5), ParametersService CRUD + YAML bootstrap (Phase 6), guardrails / iteration caps / structured output (Phase 6), exposure-policy SPI (dropped per D-10).

</domain>

<decisions>
## Implementation Decisions

### ChatClientFactory & Advisor Chain

- **D-01: Cached `ChatClient` at app start; per-request `.prompt()` builder.** One `ChatClient` is assembled at startup with `defaultAdvisors(...)` in verified order. Each `ChatService.ask()` call invokes `.prompt().tools(perRequestToolSet).options(perRequestChatOptions).user(...).call()`. Advisors are stable across requests; tools (per D-09/D-10 Phase 3) and ChatOptions (per D-03 below) vary. Avoids builder GC churn on every request while preserving per-request flexibility.

- **D-02: Advisor order (outermost → innermost): `AuditAdvisor` → `MessageChatMemoryAdvisor` → `ToolCallAdvisor`.** `AuditAdvisor` has `HIGHEST_PRECEDENCE` so it wraps the entire chain (sees final request and final response; measures true latency; captures pre-chain and post-chain errors). `MessageChatMemoryAdvisor` next so it injects history before tool calls see the prompt. `ToolCallAdvisor` innermost. Phase 5's `QuestionAnswerAdvisor` slots between Memory and Tool; Phase 4 MUST leave the ordering contract explicit so the Phase 5 insertion point is deterministic. Planner verifies the `getOrder()` values during research against M4 defaults.

- **D-03: Per-request `ChatOptions` built from the active `AiParameters` profile.** `ChatService.ask()` resolves the active profile via `DataManager`, constructs `OpenAiChatOptions` (model, temperature, top-p, any provider-specific knobs), and passes it via `.options(...)`. System prompt from the profile goes through `.system(...)` on the same request. Per-conversation overrides (Phase 6 deliverable) extend the same mechanism without reshaping `ChatService`.

- **D-04: Phase 4 reads `AiParameters` via `DataManager`; falls back to `@ConfigurationProperties` constants if no active row exists.** Phase 6 adds CRUD + `default-params.yaml` bootstrap; Phase 4's resolver code does NOT change when Phase 6 lands — Phase 6 only inserts the bootstrap row. No duplicate bootstrap paths. Fallback values (default model id, temperature, system prompt) live in `application.yml` as `jmix.ai-agent.defaults.*`.

### Dual-Layer Conversation Persistence

- **D-05: Project to `AiConversation` / `AiMessage` via a decorator around `JdbcChatMemoryRepository`.** The add-on registers a `ProjectingChatMemoryRepository` that wraps Spring AI's `JdbcChatMemoryRepository`; all `add()`/`get()`/`clear()` calls go through the decorator. On `add()`, it writes the corresponding `AiMessage` row via `DataManager` in the caller's transaction. Single write path, cannot drift from `SPRING_AI_CHAT_MEMORY`, survives advisor-chain changes.

- **D-06: `AiMessage.content` stores the full message text (duplicated with `SPRING_AI_CHAT_MEMORY`).** Spring AI's memory table is its internal context-window machinery and may reshape across upgrades; `AiMessage` is the Jmix-queryable, host-visible record used by the Conversations replay view and audit queries. Storage cost is acceptable vs. coupling to Spring AI's schema.

- **D-07: Projection runs in the same transaction as the chat-memory write (`Propagation.REQUIRED`).** If projection fails, Spring AI's memory insert rolls back too. Guarantees invariant: every row in `SPRING_AI_CHAT_MEMORY` for this add-on has a matching `AiMessage`, and vice versa. Literal implementation of the ROADMAP deliverable "synchronous, same transaction".

- **D-08: `ChatService.ask()` creates `AiConversation` on first message for a new `conversationId`.** If `DataManager.load(AiConversation.class).id(convId).optional()` returns empty AND the caller is providing a new id, `ChatService` creates the conversation (`createdBy = currentUser`, `title = firstUserMessage` truncated to ~80 chars, `createdAt = now`) via `Metadata.create()` + `DataManager.save()`, then proceeds. First `AiMessage` persists in the same transaction via the decorator. Callers never have to pre-create conversations.

### Ownership Enforcement

- **D-09: Explicit pre-check in `ChatService` + `DataManager` row-level predicate as defence in depth.** `ChatService.ask(userId, convId, ...)` first calls `DataManager.load(AiConversation.class).id(convId).optional()`. If empty (row-level predicate D-08 Phase 2 hid it, OR the row does not exist), throw `ConversationNotFoundException` — same exception for both cases so the error does not leak existence of a conversation owned by another user. DataManager row-level remains in place as authoritative; the pre-check is for crisp service-boundary semantics and deterministic error mapping.

### Audit Pipeline

- **D-10: Two audit layers — `AuditAdvisor` (chat-level) + per-tool-call callback wrapper.** `AuditAdvisor` wraps the entire chain as the outermost advisor (D-02): writes one pre-row and one post-row per `ask()` call linked by a generated `runId` UUID. A separate `MethodToolCallback` decorator wraps every `@Tool` bean invocation and writes a `kind=TOOL` `AiToolCallAudit` row per tool call with args, outcome, latency, and `denialReason` (when Phase 6 guards reject). Clean separation: chain-level timing vs per-tool-call details.

- **D-11: `REQUIRES_NEW` lives on a dedicated `AuditWriter` bean's methods only, not on the advisor/wrapper methods themselves.** `AuditWriter.writeChatPre(...)`, `writeChatPost(...)`, `writeToolCall(...)` are each `@Transactional(propagation = REQUIRES_NEW)`. The caller (AuditAdvisor, tool-callback wrapper) does NOT run in a new tx — only the audit write does. This is what makes success criterion #4 (tool-tx rollback does not erase tool audit) work: even when the outer tool tx rolls back, the `AuditWriter.writeToolCall` tx has already committed.

- **D-12: Chat-level audit content: pre-row captures (runId, user, conversationId, promptHash, timestamp); post-row captures (runId, outcome, latencyMs, errorClass nullable).** Full prompt/response text is NOT stored in `AiToolCallAudit` — it is already in `AiMessage` (D-06). `promptHash` is SHA-256 of the user message text for after-the-fact correlation without storing the payload twice. `outcome` is an enum (`SUCCESS`, `FAILED`, `TIMEOUT`, `CANCELLED`). `errorClass` is the simple class name of any thrown `Throwable`, never the stack trace.

### AuditListener SPI Fan-Out

- **D-13: Synchronous, same-thread, per-listener try/catch.** After `AuditWriter`'s `REQUIRES_NEW` tx commits (see D-14), the add-on iterates over all injected `AuditListener` beans and invokes `listener.onEntry(auditRow)` inside a per-listener `try/catch(Throwable)`. Exceptions are logged at WARN and swallowed — no listener can break another listener or break the chat request. No `@Async`, no `ApplicationEventPublisher`, no executor pool. Listener contract is documented: "listeners must be fast; delegate to host-owned async if slow".

- **D-14: Fan-out fires via `TransactionSynchronizationManager.registerSynchronization` → `afterCommit`.** Registered inside `AuditWriter.writeXxx` before the REQUIRES_NEW tx commits. Ensures listeners only see rows that are actually persisted; never fire on rolled-back rows. Matches the invariant that audit is the durable source of truth.

### Default Contributor Impls (per D-11 Phase 2)

- **D-15: Built-in contributors are not `ContextContributor` SPI impls — they are first-class Phase 4 components.** Per D-11 Phase 2, baseline runtime context (current user identity, roles, locale, conversation id) is populated by the add-on itself BEFORE SPI contributors run. Phase 4 ships a `BaselineContextProvider` (plain `@Component`, not `ContextContributor`) that feeds these into the prompt/system-message assembly. The SPI default bean for `ContextContributor` remains a no-op (registered in `SpiDefaultsAutoConfiguration` per D-06 Phase 2) — SPI is only for truly custom host context. The `PromptContextContributor` SPI default is similarly no-op; Phase 6 wires the real prompt chain (`PARAM-01..05` territory).

### Streaming

- **D-16: Phase 4 is blocking only.** `ChatService.ask()` returns a synchronous `ChatResponse`. Streaming (`.stream()` / Flux) + Vaadin Flow server push integration is deferred to Phase 7 when the UI consumer is built and the `ToolCallAdvisor`-with-streaming behaviour can be validated against a real UI. ROADMAP flagged this as research — Phase 4 keeps its test surface small and lets Phase 7 handle it end-to-end.

### Claude's Discretion

- Exact bean names, package placement within `com.vn.agent.orchestration` (or equivalent), `ChatService` method signature details beyond the locked shape, internal method decomposition of `ChatClientFactory`, enum value set for `AiToolCallOutcome` (already defined in Phase 2), promptHash algorithm if SHA-256 is inappropriate for any reason, exact title-derivation rule for new conversations beyond "first user message truncated to ~80 chars".
- Planner decides the concrete beans that implement `BaselineContextProvider` and how it integrates with Spring AI's `PromptTemplate` / system message.
- Planner decides the exact wire-up mechanism for the `MethodToolCallback` decorator (BeanPostProcessor? `ToolCallbackProvider` decorator? A fluent builder used at `.tools(...)` assembly time?) — all three are viable; match whatever keeps D-09 Phase 3 (`per-request tool assembly`) intact.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/PROJECT.md` — Vision, constraints, deferred decisions (esp. D-01 module split, D-10 exposure-layer drop)
- `.planning/REQUIREMENTS.md` — ORCH-01..06, AUD-01..05, SPI-02/03/06 contracts, TEST-02/03/05 scope
- `.planning/ROADMAP.md` §Phase 4 — deliverables and success criteria (authoritative for scope)
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` — D-03 (ChatService future-shaped signature), D-04 (mock-first + `@Tag("live")`)
- `.planning/phases/02-foundations/02-CONTEXT.md` — D-07/D-08 (role + row-level predicate on `AiConversation.createdBy`), D-11 (baseline context built-in, not via SPI), D-10 (drop exposure layer), D-06 (SpiDefaultsAutoConfiguration shape)
- `.planning/phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` — D-09 (one `@Component` `BuiltInDataTools`), D-10 (per-request `.tools(...)` assembly — NEVER `.defaultTools(...)`), D-11 (LLM entity naming via Jmix entity name)

### Project conventions
- `CLAUDE.md` — DataManager-only, no EntityManager, entity constants, msg:// i18n rule, constructor injection for services
- `docs/consumer-smoke.md` — publishToMavenLocal toggle pattern (D-02 Phase 1)

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-services` — DataManager + `@Transactional` patterns (REQUIRED, REQUIRES_NEW nuances)
- `jmix-entities` — Metadata.create, @Version, @InstanceName usage
- `jmix-security-roles` — AiAgentUserRole / AiAgentAdminRole row-level predicate (D-08 Phase 2)
- `jmix-testing` — @SpringBootTest patterns for integration tests

### External reference implementations (pattern source, NOT a dependency)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI 1.1.x ChatClient + advisor assembly reference (generalize, do not copy domain-specifics)
- `D:/ai/traffic-law-chatbot` — OpenAI-compatible via OpenRouter with per-request `ChatOptions.model` (D-03 above)

### Spring AI docs (use Context7 before writing code, M4 API shifts)
- Advisor chain & `Ordered` contract — `mcp__context7__` `/spring-projects/spring-ai/v1.1.2` → advisor ordering / getOrder semantics
- `MessageChatMemoryAdvisor`, `JdbcChatMemoryRepository` — M4 APIs, `initialize-schema: never` config (D-05/D-06 above)
- `ToolCallAdvisor` — M4 API; confirm `disableInternalConversationHistory()` behaviour (ROADMAP Phase 4 deliverable)
- `.entity(...)` / `BeanOutputConverter` — DO NOT use in Phase 4 (Phase 6 scope)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets (from prior phases)
- `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters` entities — Phase 2 (`com.vn.agent.entity`)
- `AiMessageRole`, `AiToolCallOutcome` enums — Phase 2 (`com.vn.agent.entity` or `.enumeration`)
- `AiAgentUserRole` + row-level predicate on `AiConversation.createdBy` — Phase 2 (`com.vn.agent.security`)
- Six SPI interfaces incl. `ContextContributor`, `PromptContextContributor`, `AuditListener` — Phase 2 (`com.vn.agent.spi`)
- `SpiDefaultsAutoConfiguration` no-op beans — Phase 2 (override via `@ConditionalOnMissingBean`)
- `SPRING_AI_CHAT_MEMORY` Liquibase changeset with `initialize-schema: never` — Phase 2 (D-05 Phase 2 changelog)
- `BuiltInDataTools` `@Component` with 6 tool methods — Phase 3
- `BuiltInDataTools` exposed via a `ToolCallbackProvider` for per-request assembly — Phase 3 (D-10)
- `ChatService` stub with future-shaped signature — Phase 1 (D-03 Phase 1)
- `AIAutoConfiguration` with stub `ChatClient` bean — Phase 1

### Established Patterns
- Spring AI 1.1.4 pinned via BOM; OpenAI-compatible starter with OpenRouter `base-url` override
- Constructor injection for all services (per `CLAUDE.md`)
- i18n: every label/title via `msg://` in `messages.properties` + `messages_vi.properties`
- Mock-first tests with `@Tag("live")` gating the single OpenRouter smoke
- Jmix add-on auto-discovers Liquibase changelogs via the module master `changelog.xml` include (D-02 Phase 2)

### Integration Points
- `AIAutoConfiguration` — register `ChatModel`, `ChatMemoryRepository` (decorator), `ChatClient`, `AuditAdvisor`, `AuditWriter`
- `SpiDefaultsAutoConfiguration` — confirm default no-op `AuditListener`, `ContextContributor`, `PromptContextContributor` beans remain (no change, just verify they are still `@ConditionalOnMissingBean`)
- `jmix-app` host — inject `ChatService` in a `CommandLineRunner` or a smoke test to prove end-to-end flow
- OpenRouter via `spring-ai-starter-model-openai` with `base-url: https://openrouter.ai/api/v1`, `api-key: ${OPENROUTER_API_KEY:}`

</code_context>

<specifics>
## Specific Ideas

- `runId` (UUID) threads pre-row and post-row of a single chat-level audit together, and threads all tool-call rows emitted during that chat to the same parent run (`runId` as a column or a self-referencing `parentRunId` — planner picks).
- Every tool-call audit row (`kind=TOOL`) MUST be written via `REQUIRES_NEW` before the tool method body returns; the wrapper sequence is: pre-write (REQUIRES_NEW) → invoke tool → post-write (REQUIRES_NEW) with outcome/latency/errorClass. This way a tool that throws and rolls back its own work still leaves two audit rows behind.
- When Phase 6 adds `ToolGuard`, guard-denial audit rows use the same `AiToolCallAudit` shape with `outcome=DENIED` and `denialReason` populated. Phase 4 reserves the enum values but does not wire guard calls.
- `ConversationNotFoundException` uses the same message for "does not exist" and "exists but not yours" — success criterion #3 explicitly requires User-A to get the same rejection for User-B's conversation as for a nonexistent one.

</specifics>

<deferred>
## Deferred Ideas

- **Streaming response path (`.stream()` / Flux + Vaadin push)** — deferred to Phase 7 when UI consumer lands; research flagged by ROADMAP.
- **Active-profile change notification / live rebuild of `ChatOptions`** — Phase 6 territory; Phase 4 reads per-request so no rebuild needed.
- **Tool-result caching across chat turns** — not in v1; every tool call re-executes.
- **Listener-order guarantees across multiple `AuditListener` beans** — Spring injection order is sufficient for v1; deterministic ordering deferred until a host surfaces a concrete need.
- **Async / queue-backed audit writing** — REQUIRES_NEW + same-thread fan-out is fine at MVP scale; revisit only on load evidence.
- **Redaction hooks on audit content** — `promptHash` sidesteps content redaction in v1; revisit when a host requires PII scrubbing.

</deferred>

---

*Phase: 04-orchestration-core*
*Context gathered: 2026-04-20*
