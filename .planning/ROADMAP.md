# Roadmap — Jmix AI Copilot (ai-agent-core)

**Version:** v1 (MVP)
**Granularity:** Coarse
**Phases:** 8
**Last updated:** 2026-04-19 (post-forensics sync)

## Phase Summary

| # | Phase | Goal | Requirements | Success Criteria |
|---|-------|------|--------------|------------------|
| 1 | Walking Skeleton | Prove M4 + packaging + Jmix add-on shape end-to-end before any feature work | PKG-01..05, ENT-02 (partial), TEST-01 (scaffold) | 4 |
| 2 | Foundations | Entities, Liquibase, roles, SPI interfaces (no ArchUnit per D-10) | ENT-01..04, SEC-01..04, SPI-01, SPI-02, SPI-03, SPI-05, SPI-06, SPI-07 (interfaces only) | 4 |
| 3 | Metadata Runtime & Six Tools | Metamodel scanner + per-user schema + 6 DataManager-backed read tools | TOOL-01..08, SPI-01 (impl) | 5 |
| 4 | Orchestration Core | ChatClient + advisor chain + JDBC memory + audit | ORCH-01..06, AUD-01..05, SPI-02/03 (impl), SPI-06 (impl) | 5 |
| 5 | RAG Layer | KB ingestion + pgvector + role-scoped retrieval | RAG-01..08, SPI-07 (impl) | 4 |
| 6 | Parameters & Guardrails | Parameter profiles + structured output + iteration/token caps + injection scanner | PARAM-01..05, GUARD-01..06, SPI-05 (impl) | 4 |
| 7 | Flow UI | Plug-and-play admin UI: Chat, Conversations, Parameters, KB, Audit | UI-01..06, UI-08, UI-09, UI-10 (UI-07 dropped per D-10) | 5 |
| 8 | Integration & Release | Security negative tests, clean-consumer smoke, operator docs, release polish | TEST-02..05, TEST-07 (TEST-06 dropped per D-10) | 4 |

**Total v1 requirements mapped:** 69 of 69 ✓ (was 73 pre-D-10; `AiExposureRule`/SPI-04/SPI-08/UI-07/TEST-06 dropped)

---

## Phase Details

### Phase 1 — Walking Skeleton & Packaging De-risk

**Goal:** Prove the add-on skeleton (current 2-module shape, auto-config, `@JmixModule`, clean-consumer consumption) works end-to-end with Spring AI 1.1.4 pinned via BOM (upgraded from 1.0.2 between Phase 1 wave start and Phase 2 start, per D-10), and de-risk milestone-release API drift before committing to architecture.

**Requirements:** PKG-01, PKG-02, PKG-03, PKG-04, PKG-05, TEST-01 (scaffold only)

**Deliverables:**
- Keep existing 2-module shape (`ai-agent` + `ai-agent-starter`) per [D-01 in 01-CONTEXT.md](phases/01-walking-skeleton/01-CONTEXT.md). The `ai-agent-flowui` / `ai-agent-flowui-starter` split is **deferred** until a named REST-only consumer use case justifies it; `ai-agent-starter` continues to ship UI deps (PKG-04 deferred accordingly).
- `spring-ai-bom:1.1.4` imported (upgraded from 1.0.2 per D-10); `https://repo.spring.io/milestone` added to repositories
- The `ai-agent-starter` registers via `AutoConfiguration.imports`
- `@JmixModule(dependsOn = …)` on each configuration class
- Smoke test: `ChatClient.prompt().call().content()` end-to-end through OpenRouter (as a `@Tag("live")` test) + mock `ChatModel` variant for CI
- Three-tier JUnit layout scaffolded (`src/test`, `src/integrationTest`, `@Tag("live")` excluded by default)
- Verify Jmix 2.8 → Spring Boot baseline ≥ 3.4; document version matrix

**Success criteria:**
1. `./gradlew :jmix-app:bootRun` boots the host app with the 2-module add-on (`ai-agent` + `ai-agent-starter`) loaded and no bean-wiring errors
2. `ChatService` bean (stub impl) is injectable in `jmix-app` with default config
3. `publishToMavenLocal` + `jmix-app` in Maven-coord mode (composite `includeBuild 'ai-agent'` toggled off) boots with `ai-agent-starter` resolved from `~/.m2/repository`, per [docs/consumer-smoke.md](../docs/consumer-smoke.md) (D-02)
4. `@Tag("live")` smoke test calls OpenRouter successfully with `spring-ai-starter-model-openai` and pinned `base-url`

**Needs research phase:** YES — verify M4 starter IDs + BOM availability + Boot baseline via Context7.

**Plans:** 4 plans

Plans:
- [x] 01-01-PLAN.md — Gradle/BOM wiring + excludeTags + jmix-app application.yaml
- [x] 01-02-PLAN.md — ChatService API + DefaultChatServiceImpl + AIAutoConfiguration ChatClient bean
- [x] 01-03-PLAN.md — ChatServiceMockTest + ChatServiceLiveTest (@Tag("live"))
- [x] 01-04-PLAN.md — Version matrix + consumer-smoke doc + jmix-app CommandLineRunner injection proof + ROADMAP/PROJECT updates

---

### Phase 2 — Foundations

**Goal:** Land all persistent entities, Liquibase changelogs, security roles, and SPI interface contracts that downstream phases depend on.

**Requirements:** ENT-01, ENT-02, ENT-03, ENT-04, SEC-01, SEC-02, SEC-03, SEC-04, SPI-01, SPI-02, SPI-03, SPI-05, SPI-06, SPI-07 (interfaces only, no impl) — SPI-04, SPI-08, TEST-06 dropped per D-10

**Deliverables:**
- Five JPA entities with UUID + `@Version` + `@InstanceName`, Liquibase changelogs (`AI_AGENT_*` prefix) — per D-10 (`AiExposureRule` dropped)
- Spring AI JDBC chat-memory DDL replicated in add-on Liquibase (`spring.ai.chat.memory.repository.jdbc.initialize-schema: never`)
- `CREATE EXTENSION IF NOT EXISTS vector` + `AI_AGENT_KB_VECTOR_STORE` table via Liquibase
- Host `jmix-app` master `changelog.xml` explicitly `<include>`s the add-on master changelog (D-02: Jmix does NOT auto-discover add-on Liquibase changelogs)
- `AiAgentUserRole` + `AiAgentAdminRole` with policies
- Six SPI interfaces in functional module (method signatures + Javadoc; no impl yet). SPI-04 `EntityExposurePolicy` dropped per D-10.
- Default no-op beans for all six SPIs (`SpiDefaultsAutoConfiguration` with `@ConditionalOnMissingBean`)
- `messages_en.properties` + `messages_vi.properties` for every entity + enum localization

**Success criteria:**
1. `./gradlew test` passes — foundations boot smoke test (5 assertions) green
2. `./gradlew bootRun` in `jmix-app` creates all AI-agent tables via Liquibase on fresh DB
3. `AiAgentAdminRole` assignable from Jmix role admin UI
4. All six SPIs compile; each has one no-op default bean registered

**Needs research phase:** No — standard Jmix entity/security patterns.

**Plans:** 11 plans

Plans:
- [x] 02-01-PLAN.md — Entity enums (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome) + enum i18n
- [x] 02-02-PLAN.md — Six SPI interfaces + ToolVetoedException in com.vn.agent.spi
- [x] 02-03-PLAN.md — Five JPA entities (AiConversation, AiMessage, AiToolCallAudit, AiParameters, AiKnowledgeDocument) + entity/attribute i18n
- [x] 02-04-PLAN.md — Add-on master changelog + five step changelogs (010-050) + host <include> edit (D-02 correction)
- [x] 02-05-PLAN.md — SPRING_AI_CHAT_MEMORY changeset (Postgres + HSQLDB variants) + application.properties initialize-schema:never
- [x] 02-06-PLAN.md — pgvector extension + AI_AGENT_KB_VECTOR_STORE table + HNSW index (Postgres-only with preCondition gating)
- [x] 02-07-PLAN.md — SpiDefaultsAutoConfiguration with six @ConditionalOnMissingBean no-op beans
- [x] 02-08-PLAN.md — AiAgentUserRole, AiAgentAdminRole, AiAgentUserRowLevelRole + role i18n
- [x] 02-09-PLAN.md — AIConfiguration @JmixModule dependsOn widened to include DataConfiguration + SecurityConfiguration
- [x] 02-10-PLAN.md — FoundationsBootSmokeTest (5 @Test methods: Liquibase, entities, row-level, SPI defaults, roles)
- [x] 02-11-PLAN.md — D-10 doc updates (REQUIREMENTS, ROADMAP, PROJECT, STACK) + D-02 Liquibase-include correction

---

### Phase 3 — Metadata-First Runtime & Six Tools

**Goal:** Build the metadata scanner, per-user effective schema, and six DataManager-backed read-only tools — the load-bearing architectural decision. All structured-data security flows through here.

**Requirements:** TOOL-01, TOOL-02, TOOL-03, TOOL-04, TOOL-05, TOOL-06, TOOL-07, TOOL-08, SPI-01 (impl), TEST-02 (partial) — SPI-04 dropped per D-10. TOOL-08 enforced via code review + unit tests, not ArchUnit (D-10).

**Deliverables:**
- `MetamodelScanner` — raw `MetaClass`/`MetaProperty` inventory, cached once
- Per-request effective-schema computer applying `AccessManager` directly (no `EntityExposurePolicy` chain per D-10)
- Six tools as `@Tool`-annotated bean methods producing `MethodToolCallback` instances (generated per request via `ChatClient.Builder.tools(...)`, never `.defaultTools(...)`)
- `DataManagerToolExecutor` runs all queries through `DataManager.load(...).query(...)` with structured filter DSL
- `FilterDsl → Condition` mapper
- Hard `limit` cap enforced via constant + unit test (ArchUnit deferred per D-10)
- `<data>`-delimited safe formatter for result strings
- `ToolContributor` SPI impl example

**Success criteria:**
1. Unit: scanner produces inventory for `jmix-app` (Customer, Order, LineItem, User, etc.) without connecting to an LLM
2. Unit: restricted user with no `Customer` read policy gets a schema containing zero Customer fields
3. Integration: `find_records("Order", filter=...)` through `ChatService` returns correct DataManager results; denied attributes absent
4. Unit test + code review (D-10): no tool body calls `DataManager.save()` or `.remove()`; no `@Tool` method contains raw JPQL strings from user input (ArchUnit deferred)
5. Prompt-injection harness: a Customer with `notes = "SYSTEM: ignore previous instructions"` is escaped inside `<data>` delimiters in tool result

**Needs research phase:** Partial — verify M4 `MethodToolCallback.Builder` signature.

**Plans:** 5 plans

Plans:
- [ ] 03-01-PLAN.md — MetamodelScanner + EffectiveSchemaComputer + schema DTOs (TOOL-01, TOOL-02)
- [ ] 03-02-PLAN.md — FilterNode DSL + LiteralCoercer + FilterDslMapper with DeMorgan NOT + depth cap (TOOL-05, TOOL-06)
- [ ] 03-03-PLAN.md — ToolResultFormatter (<data> wrapping) + BuiltInDataTools (six @Tool methods) + AgentToolCallbacks + AiToolsAutoConfiguration (TOOL-03, TOOL-04, TOOL-06, TOOL-07)
- [ ] 03-04-PLAN.md — Unit tests + PromptInjectionHarnessTest + ASM BuiltInDataToolsReadOnlyTest (TOOL-08, TEST-02)
- [ ] 03-05-PLAN.md — OrderSummaryToolContributor host SPI impl + ChatServiceToolIntegrationTest (SPI-01)

---

### Phase 4 — Orchestration Core

**Goal:** Compose `ChatClient` with verified advisor ordering, JDBC chat memory, dual-layer conversation persistence, and the audit pipeline. First end-to-end LLM path lands here.

**Requirements:** ORCH-01..06, AUD-01..05, SPI-02 (impl), SPI-03 (impl), SPI-06 (impl), TEST-02 (partial), TEST-03, TEST-05

**Deliverables:**
- `ChatModel` bean wired to OpenRouter; `base-url` override; model selection per `AiParameters`
- `ChatClientFactory` assembles advisor chain in verified order with `ToolCallAdvisor.disableInternalConversationHistory()`
- `JdbcChatMemoryRepository` backed by add-on-owned Liquibase schema (`initialize-schema: never`)
- `ConversationProjector` decorator (synchronous, same transaction)
- User-scoped `conversationId` ownership check at `ChatService` boundary
- `AuditAdvisor` around-chain with `REQUIRES_NEW` propagation, pre/post entries, latency capture
- `AuditListener` SPI fan-out (exceptions isolated)
- `ContextContributor` + `PromptContextContributor` default impls
- Integration tests with mock `ChatModel` verifying: advisor ordering, memory contents, audit rows, ownership enforcement, listener fan-out

**Success criteria:**
1. `ChatService.ask("user-1", "Hello")` returns a response via mock `ChatModel`; `AiConversation` + `AiMessage` + `AiToolCallAudit` (if tool called) rows present
2. Integration test asserts advisor execution order via instrumented mock advisors
3. User-A cannot read User-B's conversation (DataManager denies; `ChatService` rejects replay)
4. Rollback of a tool transaction does not erase its audit (`REQUIRES_NEW` verified)
5. `@Tag("live")` semantic-similarity test against OpenRouter passes when `OPENROUTER_API_KEY` present

**Needs research phase:** YES — re-verify advisor ordering in M4; probe Vaadin Flow streaming with `ToolCallAdvisor`.

---

### Phase 5 — RAG Layer

**Goal:** Knowledge base upload + pgvector storage + role-scoped retrieval. RAG authorization is a parallel channel to Jmix security and must be enforced at both ingest and retrieval.

**Requirements:** RAG-01..08, SPI-07 (impl)

**Deliverables:**
- File-upload ingestion path: Flow UI upload → `AiKnowledgeDocument` with status → async `IngesterManager` → Tika `TikaDocumentReader` → `TokenTextSplitter` → embed → pgvector insert
- Single shared `EmbeddingModel` bean (enforced by bean-collision test)
- Chunk metadata contract: `source`, `documentId`, `embeddingModel`, `allowedRoles` (JSON array of role codes)
- `QuestionAnswerAdvisor` with per-request `FILTER_EXPRESSION` built from `CurrentAuthentication.getAuthorities()`
- Fail-closed default: chunks with empty/missing `allowedRoles` filtered out for non-admin callers
- `CustomIngester` SPI + one sample impl (e.g. classpath markdown folder)
- Delete-document atomic removal of vector chunks (same transaction)
- Ingestion status view backing queries

**Success criteria:**
1. Admin uploads a PDF → document reaches `READY`; chunks visible in pgvector table with correct metadata
2. Unit: filter-expression builder produces correct expression from a set of roles
3. Integration: admin-uploaded doc tagged with `[AiAgentAdminRole]` is NOT retrieved when called by user with only `AiAgentUserRole`
4. Delete-document removes both `AiKnowledgeDocument` row and associated vector chunks in one transaction

**Needs research phase:** YES — verify `QuestionAnswerAdvisor` vs `RetrievalAugmentationAdvisor` in M4; confirm `FILTER_EXPRESSION` API shape.

---

### Phase 6 — Parameters, Structured Output & Guardrails

**Goal:** Admin-editable parameter profiles, structured-output affordance, and the complete guardrail stack (iteration caps, token circuit breaker, rate limit, output injection scanner).

**Requirements:** PARAM-01..05, GUARD-01..06, SPI-05 (impl)

**Deliverables:**
- `ParametersService` with CRUD over profiles + active-profile lookup
- YAML i/o (ser/de via `jackson-dataformat-yaml`); `default-params.yaml` bootstrap on empty table
- Per-conversation parameter override via `ChatService.ask(convId, question, Overrides)`
- `PromptContextContributor` chain wired into system prompt
- `ToolGuard` default impl + wiring before each tool execution; denial → audit with reason
- `ToolCallingManager` max-iteration cap (default 6)
- Per-session token circuit breaker (configurable ceiling)
- Per-user chat rate limiter (default 10 req/min)
- Output-side injection-pattern advisor (regex-based; pluggable)
- Structured output: `BeanOutputConverter` + `.entity(Class)` path + bounded retry (max 2); fallback if `StructuredOutputValidationAdvisor` absent

**Success criteria:**
1. Admin creates a profile, marks active; `ChatService` uses it on next request; UI refreshes active indicator
2. Tool guard vetoes `get_record` for a denied entity; audit row present with `denialReason`
3. Iteration-cap test: malicious prompt forcing tool-call loop terminates at max iterations with a bounded error
4. Structured-output test: `ChatService.askTyped(Question, Answer.class)` returns a parsed object; on malformed LLM output, one retry then typed error

**Needs research phase:** YES — `StructuredOutputValidationAdvisor` existence probe; per-model capability detection.

---

### Phase 7 — Flow UI

**Goal:** Ship the full plug-and-play admin + end-user UI: Chat, Conversations, Parameters, Knowledge Base, Audit. Menu + locales + role gating. (Exposure view dropped per D-10.)

**Requirements:** UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-08, UI-09, UI-10 (UI-07 dropped per D-10)

**Deliverables:**
- `ChatView` (streaming if supported; tool-call cards; citations; `New chat`, `Stop`)
- `ConversationListView` + `ConversationDetailView` (ownership filter; replay)
- `ParametersListView` + `ParametersDetailView` (YAML editor + validation; `Set active` action)
- `KnowledgeBaseView` (upload, list, status, delete, reingest)
- `ToolCallAuditListView` (filter by user/tool/outcome/date; CSV export)
- `menu.xml` with `aiAgent.*` namespaced ids; role-gated visibility
- Full `messages_en.properties` + `messages_vi.properties` coverage (zero hardcoded strings)
- Navigation wiring via `ViewNavigators`; `@ViewController` + `@ViewDescriptor` conventions

**Success criteria:**
1. Fresh `jmix-app` boot → menu shows `AI Agent` section; `Chat` visible to any user; admin views visible only to `AiAgentAdminRole`
2. End-to-end Playwright/manual: user sends a chat message → response streams (or renders blocking) → tool calls expand to show args → citation link opens source document
3. Admin uploads a doc → appears in KB view with status transitioning PENDING → READY
4. Audit view CSV export produces valid UTF-8 CSV
5. Bilingual smoke: switching locale `en ↔ vi` flips every user-visible label

**Needs research phase:** Partial — reuses P4 streaming validation; Vaadin upload patterns standard.

---

### Phase 8 — Integration Hardening & Release Readiness

**Goal:** Cross-phase security negative-case suite, clean-consumer smoke, live-tier semantic suite, operator docs, release polish.

**Requirements:** TEST-02 (completion), TEST-03 (completion), TEST-04, TEST-05 (completion), TEST-07 (TEST-06 dropped per D-10)

**Deliverables:**
- Security negative-case integration suite: restricted user receives filtered schema AND execution denial; RAG refuses untagged docs for non-admin; cross-user conversation replay blocked
- Poisoned-field prompt-injection semantic assertion test
- Rollback-preserves-audit transactional test
- `@Tag("live")` semantic-similarity suite over 6 golden questions
- Performance smoke: `limit` cap enforced; N+1 detection via Hibernate/EclipseLink statistics
- Clean-consumer smoke in CI: `publishToMavenLocal` → fresh minimal Jmix project boots + menu registers
- Operator docs in `ai-agent/README.md`: installation, required env vars, configuration matrix, entity/table ownership, upgrade checklist, air-gap notes, how to implement each SPI
- Release polish: version bump, CHANGELOG, module publishing metadata (`maven-publish` target)

**Success criteria:**
1. All integration tests green on CI (non-live tier)
2. Clean-consumer smoke passes on fresh JDK 17 + Postgres
3. Operator README walkthrough produces a working demo in under 10 minutes for someone new to the repo
4. Live-tier suite (opt-in) passes against OpenRouter with documented model + params

**Needs research phase:** No.

---

## Dependency Graph

```
P1 (Skeleton)
  └→ P2 (Foundations)
       ├→ P3 (Metadata & Tools) ──→ P4 (Orchestration) ──┐
       │                                                  ├→ P7 (Flow UI) → P8 (Release)
       └→ P5 (RAG) ─────────────────────────────────┐    │
                                                     ├─→ P6 (Params & Guardrails) ─┘
                                                     │
                                        (P4 provides advisor chain; P5/P6 add to it)
```

P7 depends on P3, P4, P5, P6. P8 depends on all.

---

## Research Flags

| Phase | Research needed? | Focus |
|-------|------------------|-------|
| 1 | YES | M4 BOM + starter IDs; Boot baseline |
| 2 | No | standard Jmix |
| 3 | Partial | M4 `MethodToolCallback.Builder` |
| 4 | YES | advisor ordering re-verify; Vaadin streaming |
| 5 | YES | `QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor`; `FILTER_EXPRESSION` |
| 6 | YES | `StructuredOutputValidationAdvisor` existence |
| 7 | Partial | reuses P4 |
| 8 | No | standard release |

---

## Requirements Traceability

| REQ | Phase | REQ | Phase |
|-----|-------|-----|-------|
| PKG-01..05 | 1 | RAG-01..08 | 5 |
| ENT-01..04 | 2 | PARAM-01..05 | 6 |
| SEC-01..04 | 2 | GUARD-01..06 | 6 |
| TOOL-01..08 | 3 | SPI-01..03, SPI-05..07 | 2 (contracts) + 3–6 (impls) |
| ORCH-01..06 | 4 | UI-01..06, UI-08..10 | 7 |
| AUD-01..05 | 4 | TEST-01 | 1 |
|  |  | TEST-02..05, TEST-07 | 8 |

> Footnote (D-10): SPI-04 (`EntityExposurePolicy`), SPI-08 (per-SPI integration-test obligation), UI-07 (`ExposureRuleListView`), and TEST-06 (ArchUnit rules) were removed from v1. See `.planning/phases/02-foundations/02-CONTEXT.md` §D-10 and `REQUIREMENTS.md` Scope Changes Log.

---

*Last updated: 2026-04-18 after Phase 02 planning — D-10 applied*
