# Requirements — Jmix AI Copilot (ai-agent-core)

**Version:** v1 (MVP)
**Last updated:** 2026-04-19 (post-forensics sync)

## v1 Requirements

### Packaging & Distribution

- [ ] **PKG-01**: Add-on ships today as two Gradle modules under `ai-agent/`: `ai-agent` (functional) + `ai-agent-starter`. The `ai-agent-flowui` / `ai-agent-flowui-starter` split is deferred until a named REST-only consumer use case justifies it.
- [ ] **PKG-02**: Active starter modules register auto-configuration via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] **PKG-03**: Add-on declares `@JmixModule(dependsOn = {CoreConfiguration.class, DataConfiguration.class, SecurityConfiguration.class, FlowuiConfiguration.class})` where appropriate for the current packaging shape
- [ ] **PKG-04**: Zero-Vaadin functional-module posture is deferred with the 4-module split; current 2-module packaging keeps UI dependencies in `ai-agent-starter`
- [ ] **PKG-05**: Clean-consumer smoke test: `publishToMavenLocal` + fresh Jmix project consumes `ai-agent-starter` and boots with default config

### Entities, Data & Security

- [ ] **ENT-01**: Five Jmix JPA entities: `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument` — UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok. (`AiExposureRule` dropped per D-10 in Phase 02 CONTEXT; see MEMORY note "AI is just another Jmix client".)
- [ ] **ENT-02**: All DDL owned by add-on Liquibase changelogs with `AI_AGENT_*` table-name prefix (no collisions with host)
- [ ] **ENT-03**: Spring AI JDBC chat-memory DDL ported into add-on Liquibase (do not rely on `initialize-schema: true`)
- [ ] **ENT-04**: pgvector extension created via Liquibase (`CREATE EXTENSION IF NOT EXISTS vector`) and distinct vector table name (e.g. `AI_AGENT_KB_VECTOR_STORE`)
- [ ] **SEC-01**: Ship `AiAgentUserRole` (Chat view access) and `AiAgentAdminRole` (Parameters / KB / Audit views). (Exposure view dropped per D-10.)
- [ ] **SEC-02**: Any authenticated Jmix user has Chat view by default; admin views gated to `AiAgentAdminRole`
- [ ] **SEC-03**: All entity persistence via `DataManager` only — `EntityManager` forbidden in add-on code, enforced by code review + CLAUDE.md convention (ArchUnit deferred per D-10)
- [ ] **SEC-04**: `AiConversation.createdBy` scoped to user; conversation replay and list filter by ownership at DataManager level

### Metadata-First Runtime & Tools

- [ ] **TOOL-01**: `MetamodelScanner` reads Jmix `Metadata`/`MetaClass`/`MetaProperty` and produces raw inventory (cached once)
- [ ] **TOOL-02**: Effective per-user schema computed per request via `AccessManager` directly — never cached per-app. (`EntityExposurePolicy` chain dropped per D-10; Jmix native security is authoritative.)
- [ ] **TOOL-03**: Six generic read-only tools auto-generated: `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`
- [ ] **TOOL-04**: All tool bodies call `DataManager` (inheriting Jmix entity/attribute/row security); no native SQL, no JPQL authored by the LLM
- [x] **TOOL-05**: `find_records` accepts a structured filter DSL (attribute + operator + literal) mapped to `Condition.createAnd(...)` — not free-text JPQL
- [ ] **TOOL-06**: Hard row-count cap (default 20, max 100) on every collection-returning tool; LLM cannot override
- [ ] **TOOL-07**: Tool result formatter wraps user-editable string fields in `<data>…</data>` delimiters with escaping to defuse prompt injection
- [x] **TOOL-08**: Read-only posture enforced by code review + unit tests asserting each tool class's public methods call only `DataManager` read-path operations (`DataManager.load` / `DataManager.getCount` / `DataManager.loadValues`). (ArchUnit deferred per D-10 and MEMORY note "Avoid ArchUnit until drift".) [Plan 03-04 `BuiltInDataToolsReadOnlyTest` — ASM bytecode scan; sabotage-and-revert validated.]

### Orchestration (ChatClient + Advisors + Memory)

- [x] **ORCH-01**: `ChatClientFactory` builds a `ChatClient` per request with the caller's effective tool set and parameter profile (never `.defaultTools(...)` for auto-generated tools)
- [x] **ORCH-02**: Advisor chain ordered: `MessageChatMemoryAdvisor` (`HIGHEST_PRECEDENCE+200`) → RAG advisor (`+250`) → `ToolCallAdvisor` with `.disableInternalConversationHistory()` (`+300`) → `AuditAdvisor` (around-chain)
- [x] **ORCH-03**: JDBC-backed `ChatMemoryRepository` is authoritative for the model; `ConversationProjector` decorator synchronously mirrors each turn into `AiConversation`/`AiMessage` entities via `DataManager`
- [x] **ORCH-04**: `conversationId` scoped to user; `ChatService` rejects replay/continuation of a conversation not owned by the current user
- [x] **ORCH-05**: `ChatService` public API supports `ask(conversationId, question)` (blocking) and `stream(conversationId, question)` (if streaming works with tool calls in M4; otherwise graceful fallback to blocking)
- [ ] **ORCH-06**: Default LLM provider: OpenAI-compatible via OpenRouter, configured through `spring.ai.openai.*` with `base-url` override; provider swappable by host replacing `ChatModel` bean

### Audit

- [ ] **AUD-01**: `AuditAdvisor` writes `AiToolCallAudit` pre (tool invoked) and post (completed | failed | denied) entries
- [ ] **AUD-02**: Audit persistence in `@Transactional(propagation = REQUIRES_NEW)` so tool rollback does not lose audit
- [ ] **AUD-03**: Audit records include: conversationId, userId, tool name, input JSON, output summary, latency, outcome, denial reason
- [ ] **AUD-04**: `AuditListener` SPI fires after each audit write (for Slack/SIEM/metrics side-channels); listener exceptions must not fail the main flow
- [ ] **AUD-05**: Audit cannot be silently disabled — enforced by unit tests on `AuditAdvisor` + code review (ArchUnit deferred per D-10)

### RAG (Knowledge Base)

- [x] **RAG-01**: Admin can upload PDF / MD / TXT / HTML via Flow UI; files read via Apache Tika (`TikaDocumentReader`)
- [x] **RAG-02**: Single shared `EmbeddingModel` bean used for both ingestion and retrieval (mismatched models forbidden)
- [x] **RAG-03**: Ingestion is asynchronous with status tracked on `AiKnowledgeDocument` (`PENDING` / `PROCESSING` / `READY` / `FAILED`)
- [x] **RAG-04**: Chunks stored in pgvector with metadata: `source`, `documentId`, `embeddingModel`, `allowedRoles` (list of Jmix role codes)
- [x] **RAG-05**: Retrieval advisor applies per-request `FILTER_EXPRESSION` derived from the caller's roles via `CurrentAuthentication`
- [x] **RAG-06**: Untagged documents refused for non-admin users (fail closed)
- [x] **RAG-07**: `CustomIngester` SPI + one example (e.g. URL-less markdown file) so hosts can plug in domain-specific sources
- [x] **RAG-08**: Admin can delete a document → corresponding vector chunks removed atomically

### Parameters & Configuration

- [x] **PARAM-01**: `AiParameters` entity stores multiple profiles (YAML blob) with exactly one marked active
- [ ] **PARAM-02**: Profile fields: model id, temperature, max tokens, system prompt, enabled tool names, RAG top-k, RAG similarity threshold
- [x] **PARAM-03**: Per-conversation parameter override supported by `ChatService` API
- [x] **PARAM-04**: `default-params.yaml` bundled with starter; seeded on first startup if table empty
- [x] **PARAM-05**: Host can contribute additional system-prompt fragments via `PromptContextContributor` SPI

### Guardrails

- [x] **GUARD-01**: `ToolGuard` SPI invoked before each tool execution; veto raises a denial captured in audit
- [x] **GUARD-02**: `ToolCallingManager` max-iteration cap (default 6, configurable)
- [x] **GUARD-03**: Per-session token circuit breaker (configurable ceiling); breach returns a user-friendly error and audits the truncation
- [x] **GUARD-04**: Per-user rate limit on chat submissions (configurable; default 10 req/min)
- [x] **GUARD-05**: Output-side advisor scans model response for likely injection patterns echoed back; redacts or flags
- [x] **GUARD-06**: Structured output via `.entity(Class)` + `BeanOutputConverter` + bounded retry (max 2). Do not assume native structured-output support

### SPI Extension Points (functional module)

- [ ] **SPI-01**: `ToolContributor` — hosts register additional `@Tool`-annotated beans
- [ ] **SPI-02**: `ContextContributor` — inject per-request context (user, tenant, env) into prompt
- [ ] **SPI-03**: `PromptContextContributor` — augment system prompt with host-specific instructions
- [x] **SPI-05**: `ToolGuard` — veto tool calls
- [ ] **SPI-06**: `AuditListener` — observe audit writes for side-channels
- [x] **SPI-07**: `CustomIngester` — plug in additional KB sources

> Note: SPI-04 (`EntityExposurePolicy`) dropped per D-10. SPI-08 (per-SPI integration test with custom host impl) dropped per D-10; each SPI has a default no-op bean and is smoke-tested via the Phase 02 foundations boot test asserting defaults auto-wire.

### Built-in Flow UI

- [x] **UI-01**: `ChatView` — end-user chat; shows tool calls transparently (collapsible cards with name + args + summary); streams responses when supported; citations link to KB documents
- [x] **UI-02**: `ChatView` includes `New chat` and (when streaming) `Stop` controls
- [ ] **UI-03**: `ConversationListView` + `ConversationDetailView` — user sees their own conversations; admin sees all; replay renders original messages + tool calls
- [ ] **UI-04**: `ParametersListView` + `ParametersDetailView` — admin CRUD over profiles; YAML editor with validation; `Set active` action
- [x] **UI-05**: `KnowledgeBaseView` — upload, list, delete, status indicator, reingest action
- [x] **UI-06**: `ToolCallAuditListView` — searchable/filterable table (user, tool, outcome, date); Excel + JSON export via Jmix `gridexport` add-on (`grdexp_excelExport`, `grdexp_jsonExport`)

> Note: UI-07 (`ExposureRuleListView`) dropped per D-10 (`AiExposureRule` entity removed). UI-08..UI-10 numbering preserved for cross-doc reference stability.

- [ ] **UI-08**: Menu entries namespaced `aiAgent.*` in add-on `menu.xml`; labels in both `messages_en.properties` and `messages_vi.properties`
- [ ] **UI-09**: All user-facing strings use `msg://` keys — zero hardcoded UI text
- [ ] **UI-10**: Admin views visibility gated to `AiAgentAdminRole`

### Testing

- [ ] **TEST-01**: Three-tier structure: `src/test` (unit), `src/integrationTest` (`@SpringBootTest` with mock `ChatModel`), `@Tag("live")` tests excluded from default `./gradlew test`
- [x] **TEST-02**: Unit tests cover: metamodel scanner, schema filtering, tool generator, filter DSL → Condition mapping, audit entity construction, chunk metadata filter expression builder *(Phase 03-04 unit tests + Phase 04-05 orchestration/audit tests)*
- [x] **TEST-03**: Integration tests in `jmix-app` harness cover: auto-config boots; `ChatService.ask` round-trips with mock ChatModel; advisor ordering preserved; tool call audited *(Phase 03-05 jmix-app ChatServiceToolIntegrationTest + Phase 04-05 OrchestrationIntegrationTest + AdvisorOrderStructuralTest + AuditDurabilityTest + DualLayerParityTest + OwnershipOpacityTest + AuditListenerFanOutTest + AuditWriterFieldMappingTest)*
- [ ] **TEST-04**: Security negative-case suite: user without read access to an entity receives filtered schema AND execution is denied; RAG retrieval filters out forbidden roles; cross-user conversation access denied
- [x] **TEST-05**: `@Tag("live")` opt-in tier uses semantic-similarity assertions (`spring-ai-test`) — no brittle exact-text asserts *(Phase 04-05 ChatServiceLiveSemanticTest: @Tag("live") primary gate + @EnabledIfEnvironmentVariable OPENROUTER_API_KEY safety net + soft `containsAnyOf("pong","yes","ok","sure")` semantic assertion)*
- [ ] **TEST-06**: (removed per D-10 — ArchUnit rules deferred per MEMORY note "Avoid ArchUnit until drift"). Code review + the existing forbidden-import convention in CLAUDE.md remain authoritative until rule drift justifies ArchUnit.
- [ ] **TEST-07**: Clean-consumer smoke: `publishToMavenLocal` → fresh minimal Jmix app consumes `ai-agent-starter` → boots + menu registers (runs in CI on release)

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
- Jmix internal/impl-package APIs — forbidden by convention (code review + CLAUDE.md); only public APIs (ArchUnit enforcement deferred per D-10)
- Universal/generic agent framework positioning — this is specifically a Jmix add-on
- Jailbreak-proof or "SOC2-compliant" guarantees — honest security posture only
- Personas, voice, image generation, fine-tuning UI, plugin marketplace, public conversation sharing, end-user model sliders — not aligned with enterprise copilot positioning
- Bundled API keys or telemetry phone-home

## Traceability

*(Filled by roadmap — maps REQ-IDs to phases)*

| REQ | Phase |
|-----|-------|

## Scope Changes Log

| Date | Decision | Change |
|------|----------|--------|
| 2026-04-18 | D-10 (Phase 02 CONTEXT) | ENT-01 6→5 entities (`AiExposureRule` dropped); SPI-04 (`EntityExposurePolicy`) + SPI-08 (per-SPI integration-test obligation) removed; UI-07 (`ExposureRuleListView`) removed; TEST-06 converted to convention (ArchUnit deferred); TOOL-08 de-ArchUnit'd; SEC-01 Exposure view dropped; SEC-03/AUD-05/TOOL-02 wording aligned with D-10; Spring AI version pinned at 1.1.4 |

---
*Last updated: 2026-04-18 after Phase 02 planning — D-10 applied*
