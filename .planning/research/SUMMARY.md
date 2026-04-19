# Project Research Summary

**Project:** Jmix AI Copilot (ai-agent-core)
**Domain:** Reusable Jmix 2.8 add-on embedding Spring AI 1.1.4 (ChatClient + advisors + RAG + tools + chat memory) with built-in Flow UI screens in the current 2-module packaging; dedicated flowui modules are deferred
**Researched:** 2026-04-18
**Confidence:** MEDIUM-HIGH

## Executive Summary

> Historical research synthesis. The authoritative current contract lives in `.planning/PROJECT.md`, `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, and `.planning/STATE.md`. This summary has been normalized to the current post-D-10 decisions.

This add-on is a **governed, metadata-first Q&A layer** over a Jmix app's live entities and uploaded documents — *not* a consumer chatbot and *not* a generic agent framework. Competitors in this category (Glean, M365 Copilot, Danswer, Dify, and the `jmix-ai-backend` reference) converge on admin-curated parameters/profiles, KB ingestion with citations, a structured tool-call audit, role-gated admin views, and end-to-end authorization inherited from the host. Our unique positioning is the intersection of *metamodel-driven tool generation* + *DataManager-enforced security* + *SPI-driven host extension*; no reference competitor occupies this spot.

The recommended approach is to **use Spring AI 1.1.x primitives as-is** (ChatClient, advisors, VectorStore, ChatMemory, `@Tool` beans) and only add Jmix-owned wiring: six generic parametric read-tools generated from `Metadata`, Jmix `AccessManager` / `DataManager` as the single authorization layer, a dual-layer chat persistence where a `ChatMemoryRepository` decorator projects Spring AI's memory rows into Jmix `AiConversation`/`AiMessage` entities, and the current 2-module add-on shape (`ai-agent` + `ai-agent-starter`) with a later optional flowui split only if a named REST-only consumer justifies it.

Dominant risks: (1) Spring AI 1.1.4 API drift or misuse — verify every call via Context7 and isolate behind thin factories; (2) security bypass via cached per-app tool schemas — build schema per-user, not per-app; (3) prompt injection through user-editable record fields returned by tools; (4) RAG that ignores user authorization — needs per-request `FILTER_EXPRESSION` derived from caller's roles; (5) add-on packaging mistakes (missing `@JmixModule`, missing `AutoConfiguration.imports`, menu/message-key collisions). A walking-skeleton phase must land before feature work to defuse these.

## Key Findings

### Recommended Stack (headline)

Spring AI 1.1.4 via `spring-ai-bom` (never per-artifact versions). Milestone repo `https://repo.spring.io/milestone` remains mandatory. OpenRouter via OpenAI-compatible starter with `base-url` override. pgvector default, swappable `VectorStore` bean.

- Java 17 + Jmix 2.8.0 + Spring Boot 3.x (HIGH)
- `org.springframework.ai:spring-ai-bom:1.1.4` (HIGH)
- `spring-ai-starter-model-openai` (HIGH)
- `spring-ai-starter-vector-store-pgvector` + Liquibase-owned `CREATE EXTENSION vector` (HIGH)
- `spring-ai-starter-model-chat-memory-repository-jdbc` (HIGH)
- `spring-ai-advisors-vector-store` + `spring-ai-rag` (HIGH)
- `spring-ai-pdf-document-reader` + `spring-ai-jsoup-document-reader` + Apache Tika 3.x (HIGH)
- `spring-ai-test` (MEDIUM)

**LOW-confidence:** `StructuredOutputValidationAdvisor` — not surfaced in 2.x docs. Treat as optional; fall back to `.entity(Class)` + `BeanOutputConverter` + bounded retry.
**MEDIUM:** Jmix 2.8 Boot baseline ≥ 3.4 — verify in Phase 0.

Full detail: `.planning/research/STACK.md`.

### Expected Features (grouped for roadmap)

**Table-stakes MVP v1:**
- *Foundations:* current 2-module packaging (`ai-agent` + `ai-agent-starter`), five Ai* JPA entities + Liquibase (`AI_AGENT_*` prefix), `AiAgentUserRole` + `AiAgentAdminRole`, `messages*.properties` in all locales, namespaced `menu.xml` (`aiAgent.*`), `AutoConfiguration.imports`, `@JmixModule(dependsOn=…)`.
- *Orchestration:* per-user metadata-first schema (never per-app cached), 6 generic read tools, DataManager-bound execution, Jmix `AccessManager` / `DataManager` as the only authorization layer, ToolGuard SPI, ChatClientFactory + verified advisor chain (memory → RAG → tools → audit) with `ToolCallAdvisor.disableInternalConversationHistory()`, JDBC ChatMemory with user-scoped `conversationId`, ConversationProjector decorator, audit in `REQUIRES_NEW`.
- *RAG:* Admin upload (PDF/MD/TXT/HTML via Tika), async ingestion status, single shared `EmbeddingModel` bean, chunk metadata (`source`, `documentId`, `embeddingModel`, `allowedRoles`), per-request `FILTER_EXPRESSION`.
- *UI:* Chat (streaming + tool transparency + citations + stop/new), Conversations list+replay, Parameters (multi-profile, one-active, YAML i/o), KnowledgeBase admin, Tool-call audit list.
- *Extensibility:* 6 SPIs in functional module — Tool / Context / Prompt / Guard / AuditListener / CustomIngester.
- *Testing:* three tiers (Unit / Integration w/ mock ChatModel / `@Tag("live")` opt-in), `jmix-app` as harness with security negative-case tests, code review + targeted unit tests for read-only posture (ArchUnit deferred).

**Anti-features:** personas/voice/image-gen; autonomous loops; plugin marketplace; public sharing; end-user model sliders; auto-ingest entities; URL crawling v1; auto-OCR; custom VectorStore wrapping; real-time spy view; parallel AI-permissions model; jailbreak-proof guarantees; GUI prompt-chain builder; fine-tuning UI; long-term per-user memory; runtime SPI hot-reload; bundled API keys; telemetry phone-home; mutation tools enabled by default.

**Defer v2+:** mutation tools with dry-run; multi-tenancy; document versioning; SOTA PII redaction; native non-OpenAI-compatible starters; per-doc-type chunking; usage dashboard; Vault integration; audit retention pruning.

Full detail: `.planning/research/FEATURES.md`.

### Architecture Approach — Keystones

1. **Metadata-first, not codegen** — `MetamodelScanner` produces raw inventory; schema filtered per-request via `AccessManager`.
2. **6 generic tools pattern** — `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records` (avoids N-tools-per-entity explosion).
3. **Advisor chain as composition unit** — `MessageChatMemoryAdvisor` (`HIGHEST_PRECEDENCE+200`) → RAG (`+250`) → `ToolCallAdvisor` with `.disableInternalConversationHistory()` (`+300`) → `AuditAdvisor` (around-chain).
4. **4-layer stacked security** (can only narrow): L1 Jmix auth → L2 Jmix entity/attr/row policies via DataManager → L3 ToolGuard → L4 AuditAdvisor.
5. **Dual-layer conversation persistence** — Spring AI's JDBC `ChatMemoryRepository` authoritative for the model; `ConversationProjector` decorator synchronously mirrors each turn into Jmix-owned `AiConversation`/`AiMessage`.
6. **Packaging stays pragmatic until proven otherwise** — current delivery shape is `ai-agent` + `ai-agent-starter`; dedicated flowui modules are deferred until a real REST-only consumer requires them.

Full detail: `.planning/research/ARCHITECTURE.md`.

### Critical Pitfalls — Top 5

1. **Per-app schema cache leaks forbidden entities** → per-user effective schema via `AccessManager`; `.tools(...)` per call, not `.defaultTools(...)`. *Phase: Foundations + Metadata&Tools.*
2. **Advisor ordering duplicates history / bypasses security** → `+200/+250/+300`, `.disableInternalConversationHistory()`, integration test. *Phase: Orchestration Core.*
3. **Prompt injection via user-editable record fields** → `<data>` delimiter escaping, hardened system prompt, output-side scanner advisor. *Phase: Orchestration + Guardrails.*
4. **RAG ignoring user authorization** → chunk `metadata.allowedRoles`/`tenantId` at ingest + per-request `FILTER_EXPRESSION` at retrieval; refuse untagged for non-admin. *Phase: RAG.*
5. **Add-on packaging breaks plug-and-play** → `@JmixModule(dependsOn=…)`, `AutoConfiguration.imports`, namespaced menu + message keys, own DDL in Liquibase with `AI_AGENT_*` prefix, test both `includeBuild` AND `publishToMavenLocal`. *Phase: Foundations + Release Readiness.*

Honourable mentions: Spring AI API drift (adapter + canary); EntityManager/native-SQL in tools (code review + targeted tests); infinite tool loops (iteration cap 5–8, `limit` cap 100, token circuit breaker); conversation cross-user leakage (user-scoped `conversationId`, row ownership); audit `REQUIRES_NEW`; read-only erosion (targeted tests on built-in tools). Full 15 in `.planning/research/PITFALLS.md`.

## Implications for Roadmap — 8 Coarse Phases

Researchers suggested 5, 7, and 8 phases independently. The authoritative phase numbering and scope now live in `.planning/ROADMAP.md`; the summary below mirrors that final shape at a high level.

### Phase 1 — Walking Skeleton & Packaging De-risk
Pin `spring-ai-bom:1.1.4` + milestone repo; `AutoConfiguration.imports` on the current starter; `@JmixModule(dependsOn=…)`; end-to-end `ChatClient.prompt().call()` smoke via OpenRouter; mock `ChatModel` scaffold; three-tier JUnit layout; `publishToMavenLocal` fresh-consumer smoke through `ai-agent-starter`.
*Defuses:* packaging and version-baseline risk. **Research flag: YES** (API surface + Boot baseline).

### Phase 2 — Foundations
Five Ai* JPA entities, Liquibase (`AI_AGENT_*`), `AiAgentUserRole` + `AiAgentAdminRole`, 6 SPI interfaces in the functional module, no-op SPI defaults, messages skeleton (en + vi), and no ArchUnit in v1.
*Defuses:* persistence, security, and SPI-shape risk. **Research flag: No.**

### Phase 3 — Metadata-First Runtime & Six Tools
`MetamodelScanner` (raw), effective per-user schema filtered via `AccessManager`, six generic read-only tools, `DataManagerToolExecutor`, strict filter DSL mapping, hard row caps, and `<data>` delimiter formatting for user-editable strings.
*Defuses:* schema leakage, prompt injection through record content, and oversized tool payloads. **Research flag: Partial** (`MethodToolCallback.Builder` / tool wiring verification).

### Phase 4 — Orchestration Core
`ChatModel` via OpenRouter, `ChatClientFactory` (per-request builder), verified advisor ordering, JDBC chat memory, `ConversationProjector`, user-scoped `conversationId`, `AuditAdvisor`, and `AuditListener` SPI.
*Defuses:* advisor-ordering risk, cross-user chat leakage, and audit transaction gaps. **Research flag: YES** (advisor ordering re-verify; streaming behavior).

### Phase 5 — RAG Layer
pgvector `VectorStore`, single shared `EmbeddingModel`, `IngesterManager`, Tika + `TokenTextSplitter`, `CustomIngester` SPI, chunk metadata contract, and role-scoped retrieval filters.
*Defuses:* embedding mismatch, RAG authz failures, and vector-store-as-source-of-truth mistakes. **Research flag: YES** (retrieval-advisor API verification).

### Phase 6 — Parameters, Structured Output & Guardrails
`ParametersService`, YAML bootstrap/import-export, `PromptContextContributor`, `ToolGuard`, token/iteration/rate caps, structured output helpers, and output-side injection scanning.
*Defuses:* runaway tool loops, unsafe output handling, and configuration sprawl. **Research flag: YES** (structured-output capability detection).

### Phase 7 — Flow UI
Built-in chat, conversation replay, parameters, knowledge-base, and audit screens. Current roadmap keeps UI in the main starter/package shape; dedicated `flowui` modules remain deferred until a concrete consumer requires them.
*Defuses:* adoption friction and operator UX gaps. **Research flag: Partial** (streaming validation reuse).

### Phase 8 — Integration Hardening & Release Readiness
Security-negative suites, poisoned-field assertions, rollback-audit tests, live semantic-smoke tier, `publishToMavenLocal` clean-consumer smoke, performance checks, and operator docs.
**Research flag: No.**

### Ordering Rationale
- ARCHITECTURE dependency graph: entities → scanner/tools → provider/advisors/memory → RAG → params/guardrails → UI → integration.
- Security is interlocked: structured-data authorization lands before orchestration and before any RAG retrieval path.
- Injection defense is layered: tool-result delimitering early, output-side scanning later.
- Read-only posture is enforced by code review plus targeted tests on the built-in tool surface.
- UI remains late because it depends on stable tooling, citations, parameters, and audit flows.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Context7 + two working references. LOW on `StructuredOutputValidationAdvisor`; MEDIUM on Boot baseline. |
| Features | MEDIUM-HIGH | Classification HIGH; Jmix-fit MEDIUM. |
| Architecture | HIGH | Context7-verified Spring AI + Jmix patterns. MEDIUM on M4 observability surface. |
| Pitfalls | HIGH | Verified both Jmix and Spring AI sides. MEDIUM on exact M4 signatures. |

**Overall:** MEDIUM-HIGH. Spring AI 1.1.4 drift is addressed by a thin adapter layer plus targeted re-verification before each orchestration-heavy phase.

### Open Questions
- `StructuredOutputValidationAdvisor` existence (P5 spike).
- Jmix 2.8 Boot baseline ≥ 3.4 (P0 verify).
- M4 advisor/RAG builder signature stability (P0 pin + canary).
- JDBC chat-memory DDL capture (P3).
- `spring-ai-test` API surface (P0 spike).
- Vaadin Flow streaming with tool-call advisor in M4 (P3 smoke; degrade if broken).
- pgvector index at 10M+ chunks; per-tenant isolation (v2).

## Sources

### Primary (HIGH)
- Context7 `/spring-projects/spring-ai` (advisor ordering, tools, RAG `FILTER_EXPRESSION`, `BeanOutputConverter`, Tika/TokenTextSplitter/PgVectorStore/JdbcChatMemoryRepository)
- Context7 `/websites/spring_io_spring-ai_reference` (2.x starter renames, `ChatClientMessageAggregator`)
- Context7 `/jmix-framework/jmix-context7` (add-on modules, `@JmixModule`, `AccessManager`, resource-roles)
- Working references: `D:/ai/traffic-law-chatbot` (M4 + OpenRouter + pgvector), `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` (admin UI shape)
- `.planning/PROJECT.md`, `CLAUDE.md`

### Secondary (MEDIUM)
- Cross-product feature synthesis (Glean, M365 Copilot, Perplexity, Danswer, OpenWebUI, LibreChat, Dify)
- Spring AI 1.1.4 tool/advisor surface — still verify before major integration steps
- Jmix 2.8 Boot baseline — numeric pin unverified

### Tertiary (LOW)
- `StructuredOutputValidationAdvisor` — not surfaced via Context7
- Vaadin Flow streaming with `ToolCallAdvisor` in M4
- `ToolCallingManager` max-iteration config surface in M4

---
*Research completed: 2026-04-18*
*Ready for roadmap: yes*
