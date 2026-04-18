# Project Research Summary

**Project:** Jmix AI Copilot (ai-agent-core)
**Domain:** Reusable Jmix 2.8 add-on embedding Spring AI 1.0.2 (ChatClient + advisors + RAG + tools + chat memory) with a Flow UI for chat / KB / parameters / audit
**Researched:** 2026-04-18
**Confidence:** MEDIUM-HIGH

## Executive Summary

This add-on is a **governed, metadata-first Q&A layer** over a Jmix app's live entities and uploaded documents — *not* a consumer chatbot and *not* a generic agent framework. Competitors in this category (Glean, M365 Copilot, Danswer, Dify, and the `jmix-ai-backend` reference) converge on admin-curated parameters/profiles, KB ingestion with citations, a structured tool-call audit, role-gated admin views, and end-to-end authorization inherited from the host. Our unique positioning is the intersection of *metamodel-driven tool generation* + *DataManager-enforced security* + *SPI-driven host extension*; no reference competitor occupies this spot.

The recommended approach is to **use Spring AI 2.x primitives as-is** (ChatClient, advisors, VectorStore, ChatMemory, `@Tool` beans) and only add Jmix-owned wiring: six generic parametric read-tools generated from `Metadata`, a stacked 5-layer security chain (Jmix auth → entity/attr/row policies → EntityExposurePolicy → ToolGuard → AuditAdvisor), a dual-layer chat persistence where a `ChatMemoryRepository` decorator projects Spring AI's memory rows into Jmix `AiConversation`/`AiMessage` entities, and a strict module split (`ai-agent` functional / `ai-agent-starter` / `ai-agent-flowui` / `ai-agent-flowui-starter`) so headless hosts can consume the copilot without Vaadin.

Dominant risks: (1) Spring AI 1.0.2 milestone drift — verify every call via Context7, isolate behind thin factories; (2) security bypass via cached per-app tool schemas — build schema per-user, not per-app; (3) prompt injection through user-editable record fields returned by tools; (4) RAG that ignores user authorization — needs per-request `FILTER_EXPRESSION` derived from caller's roles; (5) add-on packaging mistakes (missing `@JmixModule`, missing `AutoConfiguration.imports`, menu/message-key collisions). A walking-skeleton phase must land before feature work to defuse these.

## Key Findings

### Recommended Stack (headline)

Spring AI 1.0.2 via `spring-ai-bom` (never per-artifact versions), 2.x starter naming. Milestone repo `https://repo.spring.io/milestone` mandatory. OpenRouter via OpenAI-compatible starter with `base-url` override. pgvector default, swappable `VectorStore` bean.

- Java 17 + Jmix 2.8.0 + Spring Boot 3.x (HIGH)
- `org.springframework.ai:spring-ai-bom:1.0.2` (HIGH)
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
- *Foundations:* module split (functional + starter + flowui + flowui-starter), Ai* JPA entities + Liquibase (`AI_AGENT_*` prefix), `AiAgentUserRole` + `AiAgentAdminRole`, `messages*.properties` in all locales, namespaced `menu.xml` (`aiAgent.*`), `AutoConfiguration.imports`, `@JmixModule(dependsOn=…)`.
- *Orchestration:* per-user metadata-first schema (never per-app cached), 6 generic read tools, DataManager-bound execution, EntityExposurePolicy SPI, ToolGuard SPI, ChatClientFactory + verified advisor chain (memory → RAG → tools → audit) with `ToolCallAdvisor.disableInternalConversationHistory()`, JDBC ChatMemory with user-scoped `conversationId`, ConversationProjector decorator, audit in `REQUIRES_NEW`.
- *RAG:* Admin upload (PDF/MD/TXT/HTML via Tika), async ingestion status, single shared `EmbeddingModel` bean, chunk metadata (`source`, `documentId`, `embeddingModel`, `allowedRoles`), per-request `FILTER_EXPRESSION`.
- *UI:* Chat (streaming + tool transparency + citations + stop/new), Conversations list+replay, Parameters (multi-profile, one-active, YAML i/o), KnowledgeBase admin, Tool-call audit list.
- *Extensibility:* 6 SPIs in functional module — Tool / Context / Prompt / Exposure / Guard / AuditListener.
- *Testing:* three tiers (Unit / Integration w/ mock ChatModel / `@Tag("live")` opt-in), `jmix-app` as harness with security negative-case tests, ArchUnit (no EntityManager, no `.impl.`/`.internal.`, no `DataManager.save/remove` in tool bodies).

**Anti-features:** personas/voice/image-gen; autonomous loops; plugin marketplace; public sharing; end-user model sliders; auto-ingest entities; URL crawling v1; auto-OCR; custom VectorStore wrapping; real-time spy view; parallel AI-permissions model; jailbreak-proof guarantees; GUI prompt-chain builder; fine-tuning UI; long-term per-user memory; runtime SPI hot-reload; bundled API keys; telemetry phone-home; mutation tools enabled by default.

**Defer v2+:** mutation tools with dry-run; multi-tenancy; document versioning; SOTA PII redaction; native non-OpenAI-compatible starters; per-doc-type chunking; usage dashboard; Vault integration; audit retention pruning.

Full detail: `.planning/research/FEATURES.md`.

### Architecture Approach — Keystones

1. **Metadata-first, not codegen** — `MetamodelScanner` produces raw inventory; schema filtered per-request via `AccessManager` + `EntityExposurePolicy`.
2. **6 generic tools pattern** — `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records` (avoids N-tools-per-entity explosion).
3. **Advisor chain as composition unit** — `MessageChatMemoryAdvisor` (`HIGHEST_PRECEDENCE+200`) → RAG (`+250`) → `ToolCallAdvisor` with `.disableInternalConversationHistory()` (`+300`) → `AuditAdvisor` (around-chain).
4. **5-layer stacked security** (can only narrow): L1 Jmix auth → L2 Jmix entity/attr/row policies via DataManager → L3 EntityExposurePolicy (at schema-gen AND execution) → L4 ToolGuard → L5 AuditAdvisor.
5. **Dual-layer conversation persistence** — Spring AI's JDBC `ChatMemoryRepository` authoritative for the model; `ConversationProjector` decorator synchronously mirrors each turn into Jmix-owned `AiConversation`/`AiMessage`.
6. **Module split: functional / flowui / starters** — `ai-agent` (headless SPIs + services), `ai-agent-starter`, `ai-agent-flowui`, `ai-agent-flowui-starter`. REST-only hosts consume just the functional starter pair.

Full detail: `.planning/research/ARCHITECTURE.md`.

### Critical Pitfalls — Top 5

1. **Per-app schema cache leaks forbidden entities** → per-user effective schema via `AccessManager`; `.tools(...)` per call, not `.defaultTools(...)`. *Phase: Foundations + Metadata&Tools.*
2. **Advisor ordering duplicates history / bypasses security** → `+200/+250/+300`, `.disableInternalConversationHistory()`, integration test. *Phase: Orchestration Core.*
3. **Prompt injection via user-editable record fields** → `<data>` delimiter escaping, hardened system prompt, output-side scanner advisor. *Phase: Orchestration + Guardrails.*
4. **RAG ignoring user authorization** → chunk `metadata.allowedRoles`/`tenantId` at ingest + per-request `FILTER_EXPRESSION` at retrieval; refuse untagged for non-admin. *Phase: RAG.*
5. **Add-on packaging breaks plug-and-play** → `@JmixModule(dependsOn=…)`, `AutoConfiguration.imports`, namespaced menu + message keys, ArchUnit against `.impl.`/`.internal.`, own DDL in Liquibase with `AI_AGENT_*` prefix, test both `includeBuild` AND `publishToMavenLocal`. *Phase: Foundations + Release Readiness.*

Honourable mentions: M4 API drift (P0 adapter + canary); EntityManager/native-SQL in tools (ArchUnit); infinite tool loops (iteration cap 5–8, `limit` cap 100, token circuit breaker); conversation cross-user leakage (user-scoped `conversationId`, row ownership); audit `REQUIRES_NEW`; read-only erosion (ArchUnit + `@ReadOnlyToolPolicy`). Full 15 in `.planning/research/PITFALLS.md`.

## Implications for Roadmap — 8 Coarse Phases

Researchers suggested 5, 7, and 8 phases independently. Synthesized into **8 coarse-grain phases** aligned with PROJECT.md Active groupings and ARCHITECTURE dependency graph.

### Phase 0 — Walking Skeleton & Packaging De-risk
Pin `spring-ai-bom:1.0.2` + milestone repo; `AutoConfiguration.imports` in both starters; `@JmixModule(dependsOn=…)`; end-to-end `ChatClient.prompt().call()` smoke via OpenRouter; mock `ChatModel` scaffold; three-tier JUnit layout; `publishToMavenLocal` fresh-consumer smoke.
*Defuses:* #5 packaging, #12 collisions, #14 live-LLM-in-CI. **Research flag: YES** (M4 surface, Boot baseline).

### Phase 1 — Foundations (entities, security, SPI contracts)
6 Ai* JPA entities (UUID + `@Version` + `@InstanceName`), Liquibase (`AI_AGENT_*`), `AiAgentUserRole` + `AiAgentAdminRole`, 6 SPI interfaces in functional module, ArchUnit rules, messages skeleton (en + vi).
*Defuses:* read-only posture, SPI shape for per-user schema. **Research flag: No.**

### Phase 2 — Metadata-First Runtime & Six Tools
`MetamodelScanner` (raw), default + rule-backed + chained `EntityExposurePolicy`, `ToolGenerator` via `MethodToolCallback`, `ToolRegistry`, `DataManagerToolExecutor` with per-user schema filtering, hard `limit` cap (20 default, 100 max), structured-filter DSL (never LLM-authored JPQL), `<data>` delimiter formatter.
*Defuses:* #1 (PRIMARY), #3 (formatter), #6, #15. **Research flag: Partial** (M4 `MethodToolCallback.Builder`).

### Phase 3 — Orchestration Core (ChatClient + Advisors + Memory + Audit)
`ChatModel` via OpenRouter, `ChatClientFactory` (per-request builder), verified-ordered advisor chain with `disableInternalConversationHistory()`, JDBC ChatMemory (`initialize-schema: never`, Liquibase DDL), `ConversationProjector`, user-scoped `conversationId` with ownership check, `ChatService` public API, `AuditAdvisor` with `REQUIRES_NEW` + pre/post, `AuditListener` SPI, `@Tag("live")` semantic-similarity smoke.
*Defuses:* #2 ordering, #10 cross-user leak, #13 audit transaction. **Research flag: YES** (advisor ordering re-verify; Vaadin Flow streaming).

### Phase 4 — RAG (KB Ingestion & Retrieval)
pgvector `VectorStore` (Liquibase `CREATE EXTENSION vector`, distinctive table name), **single shared `EmbeddingModel` bean**, `IngesterManager` with async status, Tika + `TokenTextSplitter`, `CustomIngester` SPI, chunk-metadata contract, `QuestionAnswerAdvisor` with per-request `FILTER_EXPRESSION`, `KnowledgeBaseExposurePolicy` SPI, architectural memo against entity-row ingestion.
*Defuses:* #7 embed mismatch, #8 RAG authz (PRIMARY — ingest + retrieval ship together), #9 vector-as-truth. **Research flag: YES** (`QuestionAnswerAdvisor` vs `RetrievalAugmentationAdvisor`, `FILTER_EXPRESSION` API).

### Phase 5 — Parameters, Structured Output & Guardrails
`ParametersService` (multi-profile single-active + per-conversation override), YAML i/o, `default-params.yaml` bootstrap, `PromptContextContributor` chain, `ToolGuard` wired + denial-audit, `ToolCallingManager` max-iteration cap (5–8), per-session token circuit breaker, rate limit, structured output via `BeanOutputConverter` + `.entity(Class)` + bounded retry, output-side injection-pattern advisor.
*Defuses:* #3 (output-side), #6 (iteration + budget). **Research flag: YES** (`StructuredOutputValidationAdvisor` existence; per-model capability detection).

### Phase 6 — Flow UI
`ai-agent-flowui` + `ai-agent-flowui-starter` with auto-config, ChatView (streaming + tool panel + citations), ConversationList/Detail, ParametersList/Detail, KnowledgeBaseView, ToolCallAuditListView, ExposureRuleListView, namespaced `menu.xml`, `messages_en` + `messages_vi` full coverage, admin-role gating, CSV export on audit.
*Defuses:* flowui-side packaging. **Research flag: Partial** (reuses P3 streaming validation).

### Phase 7 — Integration Hardening & Release Readiness
E2E integration tests in `jmix-app` with Jmix-security negative cases (restricted user filtered schema, RAG denies untagged, cross-user conversation denied), poisoned-field semantic assertion, rollback-audit test, live-tier semantic-similarity suite, `publishToMavenLocal` + clean consumer smoke, performance smoke (cap enforcement + N+1 via Hibernate stats), cost/token observability, operator docs.
**Research flag: No.**

### Ordering Rationale
- ARCHITECTURE dependency graph: entities → scanner/policy/tools → provider/advisors/memory → RAG → params/structured → UI → integration.
- P0 precedes everything purely for M4 + packaging de-risk.
- Security interlocked: P1 contracts enforced by P2/P3/P4; injection defense two-layered (P2 formatter + P5 output advisor); P4 ingest + retrieval MUST ship together.
- Read-only posture mechanical (ArchUnit rules from P1 protect every tool).
- UI strictly last — ChatView depends on P3 streaming, P4 citations, P5 parameters, P3 audit.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Context7 + two working references. LOW on `StructuredOutputValidationAdvisor`; MEDIUM on Boot baseline. |
| Features | MEDIUM-HIGH | Classification HIGH; Jmix-fit MEDIUM. |
| Architecture | HIGH | Context7-verified Spring AI + Jmix patterns. MEDIUM on M4 observability surface. |
| Pitfalls | HIGH | Verified both Jmix and Spring AI sides. MEDIUM on exact M4 signatures. |

**Overall:** MEDIUM-HIGH. Milestone drift addressed by P0 + adapter + weekly canary.

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
- Spring AI 1.0.2 milestone surface — expect drift
- Jmix 2.8 Boot baseline — numeric pin unverified

### Tertiary (LOW)
- `StructuredOutputValidationAdvisor` — not surfaced via Context7
- Vaadin Flow streaming with `ToolCallAdvisor` in M4
- `ToolCallingManager` max-iteration config surface in M4

---
*Research completed: 2026-04-18*
*Ready for roadmap: yes*
