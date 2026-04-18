# Requirements — Jmix AI Copilot (ai-agent-core)

**Version:** v1 (MVP)
**Last updated:** 2026-04-18

## v1 Requirements

### Packaging & Distribution

- [ ] **PKG-01**: Add-on ships as four Gradle modules under `ai-agent/`: `ai-agent` (functional), `ai-agent-starter`, `ai-agent-flowui`, `ai-agent-flowui-starter`
- [ ] **PKG-02**: Each starter registers auto-configuration via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] **PKG-03**: Add-on declares `@JmixModule(dependsOn = {CoreConfiguration.class, DataConfiguration.class, SecurityConfiguration.class, FlowuiConfiguration.class})` where appropriate
- [ ] **PKG-04**: Functional module (`ai-agent`) contains zero Vaadin/Flow UI dependencies — consumable by REST-only hosts
- [ ] **PKG-05**: Clean-consumer smoke test: `publishToMavenLocal` + fresh Jmix project consumes `ai-agent-flowui-starter` and boots with default config

### Entities, Data & Security

- [ ] **ENT-01**: Six Jmix JPA entities: `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument`, `AiExposureRule` — UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok
- [ ] **ENT-02**: All DDL owned by add-on Liquibase changelogs with `AI_AGENT_*` table-name prefix (no collisions with host)
- [ ] **ENT-03**: Spring AI JDBC chat-memory DDL ported into add-on Liquibase (do not rely on `initialize-schema: true`)
- [ ] **ENT-04**: pgvector extension created via Liquibase (`CREATE EXTENSION IF NOT EXISTS vector`) and distinct vector table name (e.g. `AI_AGENT_KB_VECTOR_STORE`)
- [ ] **SEC-01**: Ship `AiAgentUserRole` (Chat view access) and `AiAgentAdminRole` (Parameters / KB / Audit / Exposure views)
- [ ] **SEC-02**: Any authenticated Jmix user has Chat view by default; admin views gated to `AiAgentAdminRole`
- [ ] **SEC-03**: All entity persistence via `DataManager` only — ArchUnit rule forbids `EntityManager` in add-on code
- [ ] **SEC-04**: `AiConversation.createdBy` scoped to user; conversation replay and list filter by ownership at DataManager level

### Metadata-First Runtime & Tools

- [ ] **TOOL-01**: `MetamodelScanner` reads Jmix `Metadata`/`MetaClass`/`MetaProperty` and produces raw inventory (cached once)
- [ ] **TOOL-02**: Effective per-user schema computed per request via `AccessManager` + `EntityExposurePolicy` chain — never cached per-app
- [ ] **TOOL-03**: Six generic read-only tools auto-generated: `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`
- [ ] **TOOL-04**: All tool bodies call `DataManager` (inheriting Jmix entity/attribute/row security); no native SQL, no JPQL authored by the LLM
- [ ] **TOOL-05**: `find_records` accepts a structured filter DSL (attribute + operator + literal) mapped to `Condition.createAnd(...)` — not free-text JPQL
- [ ] **TOOL-06**: Hard row-count cap (default 20, max 100) on every collection-returning tool; LLM cannot override
- [ ] **TOOL-07**: Tool result formatter wraps user-editable string fields in `<data>…</data>` delimiters with escaping to defuse prompt injection
- [ ] **TOOL-08**: Read-only posture enforced by ArchUnit: no `DataManager.save()` / `.remove()` in tool bodies

### Orchestration (ChatClient + Advisors + Memory)

- [ ] **ORCH-01**: `ChatClientFactory` builds a `ChatClient` per request with the caller's effective tool set and parameter profile (never `.defaultTools(...)` for auto-generated tools)
- [ ] **ORCH-02**: Advisor chain ordered: `MessageChatMemoryAdvisor` (`HIGHEST_PRECEDENCE+200`) → RAG advisor (`+250`) → `ToolCallAdvisor` with `.disableInternalConversationHistory()` (`+300`) → `AuditAdvisor` (around-chain)
- [ ] **ORCH-03**: JDBC-backed `ChatMemoryRepository` is authoritative for the model; `ConversationProjector` decorator synchronously mirrors each turn into `AiConversation`/`AiMessage` entities via `DataManager`
- [ ] **ORCH-04**: `conversationId` scoped to user; `ChatService` rejects replay/continuation of a conversation not owned by the current user
- [ ] **ORCH-05**: `ChatService` public API supports `ask(conversationId, question)` (blocking) and `stream(conversationId, question)` (if streaming works with tool calls in M4; otherwise graceful fallback to blocking)
- [ ] **ORCH-06**: Default LLM provider: OpenAI-compatible via OpenRouter, configured through `spring.ai.openai.*` with `base-url` override; provider swappable by host replacing `ChatModel` bean

### Audit

- [ ] **AUD-01**: `AuditAdvisor` writes `AiToolCallAudit` pre (tool invoked) and post (completed | failed | denied) entries
- [ ] **AUD-02**: Audit persistence in `@Transactional(propagation = REQUIRES_NEW)` so tool rollback does not lose audit
- [ ] **AUD-03**: Audit records include: conversationId, userId, tool name, input JSON, output summary, latency, outcome, denial reason
- [ ] **AUD-04**: `AuditListener` SPI fires after each audit write (for Slack/SIEM/metrics side-channels); listener exceptions must not fail the main flow
- [ ] **AUD-05**: Audit cannot be silently disabled (ArchUnit forbids conditional short-circuit of `AuditAdvisor`)

### RAG (Knowledge Base)

- [ ] **RAG-01**: Admin can upload PDF / MD / TXT / HTML via Flow UI; files read via Apache Tika (`TikaDocumentReader`)
- [ ] **RAG-02**: Single shared `EmbeddingModel` bean used for both ingestion and retrieval (mismatched models forbidden)
- [ ] **RAG-03**: Ingestion is asynchronous with status tracked on `AiKnowledgeDocument` (`PENDING` / `PROCESSING` / `READY` / `FAILED`)
- [ ] **RAG-04**: Chunks stored in pgvector with metadata: `source`, `documentId`, `embeddingModel`, `allowedRoles` (list of Jmix role codes)
- [ ] **RAG-05**: Retrieval advisor applies per-request `FILTER_EXPRESSION` derived from the caller's roles via `CurrentAuthentication`
- [ ] **RAG-06**: Untagged documents refused for non-admin users (fail closed)
- [ ] **RAG-07**: `CustomIngester` SPI + one example (e.g. URL-less markdown file) so hosts can plug in domain-specific sources
- [ ] **RAG-08**: Admin can delete a document → corresponding vector chunks removed atomically

### Parameters & Configuration

- [ ] **PARAM-01**: `AiParameters` entity stores multiple profiles (YAML blob) with exactly one marked active
- [ ] **PARAM-02**: Profile fields: model id, temperature, max tokens, system prompt, enabled tool names, RAG top-k, RAG similarity threshold
- [ ] **PARAM-03**: Per-conversation parameter override supported by `ChatService` API
- [ ] **PARAM-04**: `default-params.yaml` bundled with starter; seeded on first startup if table empty
- [ ] **PARAM-05**: Host can contribute additional system-prompt fragments via `PromptContextContributor` SPI

### Guardrails

- [ ] **GUARD-01**: `ToolGuard` SPI invoked before each tool execution; veto raises a denial captured in audit
- [ ] **GUARD-02**: `ToolCallingManager` max-iteration cap (default 6, configurable)
- [ ] **GUARD-03**: Per-session token circuit breaker (configurable ceiling); breach returns a user-friendly error and audits the truncation
- [ ] **GUARD-04**: Per-user rate limit on chat submissions (configurable; default 10 req/min)
- [ ] **GUARD-05**: Output-side advisor scans model response for likely injection patterns echoed back; redacts or flags
- [ ] **GUARD-06**: Structured output via `.entity(Class)` + `BeanOutputConverter` + bounded retry (max 2). Do not assume native structured-output support

### SPI Extension Points (functional module)

- [ ] **SPI-01**: `ToolContributor` — hosts register additional `@Tool`-annotated beans
- [ ] **SPI-02**: `ContextContributor` — inject per-request context (user, tenant, env) into prompt
- [ ] **SPI-03**: `PromptContextContributor` — augment system prompt with host-specific instructions
- [ ] **SPI-04**: `EntityExposurePolicy` — narrow which `MetaClass`/`MetaProperty` the agent sees
- [ ] **SPI-05**: `ToolGuard` — veto tool calls
- [ ] **SPI-06**: `AuditListener` — observe audit writes for side-channels
- [ ] **SPI-07**: `CustomIngester` — plug in additional KB sources
- [ ] **SPI-08**: Each SPI has a default implementation + at least one integration test exercising a custom host impl

### Built-in Flow UI

- [ ] **UI-01**: `ChatView` — end-user chat; shows tool calls transparently (collapsible cards with name + args + summary); streams responses when supported; citations link to KB documents
- [ ] **UI-02**: `ChatView` includes `New chat` and (when streaming) `Stop` controls
- [ ] **UI-03**: `ConversationListView` + `ConversationDetailView` — user sees their own conversations; admin sees all; replay renders original messages + tool calls
- [ ] **UI-04**: `ParametersListView` + `ParametersDetailView` — admin CRUD over profiles; YAML editor with validation; `Set active` action
- [ ] **UI-05**: `KnowledgeBaseView` — upload, list, delete, status indicator, reingest action
- [ ] **UI-06**: `ToolCallAuditListView` — searchable/filterable table (user, tool, outcome, date); CSV export
- [ ] **UI-07**: `ExposureRuleListView` — admin CRUD over `AiExposureRule` (entity/attribute allow/deny)
- [ ] **UI-08**: Menu entries namespaced `aiAgent.*` in add-on `menu.xml`; labels in both `messages_en.properties` and `messages_vi.properties`
- [ ] **UI-09**: All user-facing strings use `msg://` keys — zero hardcoded UI text
- [ ] **UI-10**: Admin views visibility gated to `AiAgentAdminRole`

### Testing

- [ ] **TEST-01**: Three-tier structure: `src/test` (unit), `src/integrationTest` (`@SpringBootTest` with mock `ChatModel`), `@Tag("live")` tests excluded from default `./gradlew test`
- [ ] **TEST-02**: Unit tests cover: metamodel scanner, schema filtering, tool generator, filter DSL → Condition mapping, audit entity construction, chunk metadata filter expression builder
- [ ] **TEST-03**: Integration tests in `jmix-app` harness cover: auto-config boots; `ChatService.ask` round-trips with mock ChatModel; advisor ordering preserved; tool call audited
- [ ] **TEST-04**: Security negative-case suite: user without read access to an entity receives filtered schema AND execution is denied; RAG retrieval filters out forbidden roles; cross-user conversation access denied
- [ ] **TEST-05**: `@Tag("live")` opt-in tier uses semantic-similarity assertions (`spring-ai-test`) — no brittle exact-text asserts
- [ ] **TEST-06**: ArchUnit rules enforced in CI: no `EntityManager`, no `io.jmix...impl.` / `...internal.` imports in add-on, no `DataManager.save/remove` in `@Tool` bodies
- [ ] **TEST-07**: Clean-consumer smoke: `publishToMavenLocal` → fresh minimal Jmix app consumes `ai-agent-flowui-starter` → boots + menu registers (runs in CI on release)

## v2 Requirements (deferred)

- Mutation tools (create/update/delete) with dry-run + explicit confirmation; `MutationTool` SPI enabled by opt-in
- Multi-tenancy awareness (per-tenant vector partition, `TenantProvider` integration)
- Document versioning + incremental reingest
- Native Anthropic / Google Gen AI / Ollama starters (not just via OpenRouter)
- URL ingestion and web crawling
- PII redaction pipeline (advanced)
- Usage & cost dashboard view
- Audit retention / archival policies

## Out of Scope

- Autonomous multi-step agent loops — v1 is single-turn tool calling + RAG; loops add safety/cost complexity without clear enterprise value
- Mutation tools enabled by default — too dangerous; host must opt in after v2 ships
- Auto-ingesting host entity records into the vector store — freshness and authorization complexity; `DataManager` is the source of truth for structured data
- Custom `VectorStore` or `ChatModel` abstractions over Spring AI primitives — adds upgrade tax; use 2.x APIs directly
- Jmix internal/impl-package APIs — forbidden by ArchUnit; only public APIs
- Universal/generic agent framework positioning — this is specifically a Jmix add-on
- Jailbreak-proof or "SOC2-compliant" guarantees — honest security posture only
- Personas, voice, image generation, fine-tuning UI, plugin marketplace, public conversation sharing, end-user model sliders — not aligned with enterprise copilot positioning
- Bundled API keys or telemetry phone-home

## Traceability

*(Filled by roadmap — maps REQ-IDs to phases)*

| REQ | Phase |
|-----|-------|

---
*Last updated: 2026-04-18 after initialization*
