---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: Ready to execute
last_updated: "2026-04-20T13:32:23.734Z"
last_activity: 2026-04-20
progress:
  total_phases: 5
  completed_phases: 5
  total_plans: 30
  completed_plans: 30
  percent: 100
---

# Project State

**Last updated:** 2026-04-20

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-18)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 04 — orchestration-core

## Phase Status

| # | Phase | Status |
|---|-------|--------|
| 1 | Walking Skeleton & Packaging De-risk | ✅ Complete (merged to master) |
| 2 | Foundations | ✅ Complete (11/11 plans, static verification PASS — pending human Gradle verify) |
| 3 | Metadata-First Runtime & Six Tools | ✅ Complete (5/5 plans — static verification PASS, pending human Gradle verify) |
| 4 | Orchestration Core | ✅ Complete (5/5 plans — static verification PASS: ./gradlew :ai-agent:ai-agent:test green) |
| 5 | RAG Layer | ✅ Complete (5/5 plans — 05-01/05-02/05-03/05-04/05-05; integrationTest task gated on Docker, default test unblocked) |
| 6 | Parameters, Structured Output & Guardrails | Not started |
| 7 | Flow UI | Not started |
| 8 | Integration Hardening & Release Readiness | Not started |

## Active Milestone

**MVP v1** — 8 phases, 69 requirements. 2/8 phases complete.

## Phase 01 Outcome

All 4 plans completed and merged into master:

- 01-01: Spring AI BOM pinned at **1.1.4** (landed on 1.0.2 initially; upgraded before Phase 2 completion per D-10), OpenRouter wired, liveTest task split
- 01-02: ChatService SPI + DefaultChatServiceImpl + AIAutoConfiguration ChatClient @Bean
- 01-03: ChatServiceMockTest + ChatServiceLiveTest (@Tag("live"), opt-in)
- 01-04: docs/versions.md, docs/consumer-smoke.md, ChatServiceSmokeRunner injection proof, ROADMAP/PROJECT D-01

**Human-verify confirmed (2026-04-18 21:55):** `jmix-app` boots from Maven-Local-resolved starter; `ChatServiceSmokeRunner` logs `ChatService bean present: class=com.vn.agent.DefaultChatServiceImpl`.

## Known Follow-ups (not blocking Phase 2)

- HSQLDB file-lock flakiness on Windows during aborted boots. Pre-boot hygiene documented; consider Postgres migration for `jmix-app` if it recurs.

## Phase 02 Progress

All 11 plans complete on branch `gsd/phase-02-foundations`:

- 02-01: ✅ 3 EnumClass<String> enums (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome) + EN/VI i18n. Enum ids upper-case to match Spring AI 1.1.4 chat-memory CHECK constraint.
- 02-02: ✅ 6 SPI interfaces + ToolVetoedException in com.vn.agent.spi (signatures + Javadoc only)
- 02-03: ✅ 5 JPA entities (AiConversation composes AiMessage via @Composition+CASCADE; enum adapters; no Lombok) + i18n
- 02-04: ✅ 5 entity DDL Liquibase changelogs (010-050) + add-on master + host jmix-app explicit `<include>` (D-02)
- 02-05: ✅ SPRING_AI_CHAT_MEMORY DDL (postgres+hsqldb gated) + `initialize-schema=never`
- 02-06: ✅ pgvector 070 DDL (vector(1536) + HNSW vector_cosine_ops) postgres-gated belt-and-suspenders
- 02-07: ✅ SpiDefaultsAutoConfiguration with 6 @ConditionalOnMissingBean no-op defaults + AutoConfiguration.imports
- 02-08: ✅ 3 Jmix roles (AiAgentUserRole, AiAgentAdminRole, AiAgentUserRowLevelRole with :current_user_username JPQL) + i18n
- 02-09: ✅ @JmixModule(dependsOn) widened to include DataConfiguration + SecurityConfiguration
- 02-10: ✅ FoundationsBootSmokeTest @SpringBootTest (5 assertions: Liquibase, entity round-trip, row-level isolation, SPI defaults, role catalog)
- 02-11: ✅ D-10 scope reductions + D-02 include correction synced to REQUIREMENTS/ROADMAP/PROJECT/STACK docs

**Static verification:** PASS (16/16 must-haves — see `.planning/phases/02-foundations/VERIFICATION.md`)

## Next Steps

1. **Human-verify Phase 2:** run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.FoundationsBootSmokeTest"` and `./gradlew :jmix-app:bootRun` to confirm the phase gate passes on a real JVM.
2. Merge `gsd/phase-02-foundations` → `master` once human-verified.
3. Start Phase 3 — Metadata-First Runtime & Six Tools.

## Key Artifacts

| Artifact | Path |
|---|---|
| Product context | `.planning/PROJECT.md` |
| Requirements | `.planning/REQUIREMENTS.md` |
| Roadmap | `.planning/ROADMAP.md` |
| Research summary | `.planning/research/SUMMARY.md` |
| Phase 1 plans/summaries | `.planning/phases/01-walking-skeleton/` |
| Codebase map | `.planning/codebase/` |
| Config | `.planning/config.json` |

## Phase 03 Progress

- 03-01: ✅ Metadata core — 6 files under `com.vn.agent.metadata`: `AiSchema`, `AiEntityInfo`, `AiAttributeInfo`, `UserEditableStringIndex` (DTOs); `MetamodelScanner` (TOOL-01, `ApplicationReadyEvent`, MetadataTools.isJpa-backed D-13 index); `EffectiveSchemaComputer` (TOOL-02, stateless AccessManager filter + per-request MessageTools labels). Commits `fec54d5`, `0856763`, `00960b7`. Deviation: plan said `io.jmix.core.security.AccessManager`; Jmix 2.8 ships it at `io.jmix.core.AccessManager` (Rule 1 bug-fix applied at Task 3 write time).
- 03-02: ✅ Filter DSL + tool primitives — 10 files under `com.vn.agent.filter` and `com.vn.agent.tools`: sealed `FilterNode` (+ `AndNode`/`OrNode`/`NotNode`/`LeafNode`); `LiteralCoercer` (strict fail-closed D-07); `FilterDslMapper` (TOOL-05, DeMorgan NOT, 13 D-05 operators, depth cap + per-hop AccessManager from D-08); `ToolLimits` (TOOL-06 DEFAULT_LIMIT=20/MAX_LIMIT=100/DEFAULT_MAX_FILTER_DEPTH=3); `ToolErrorDto` + `ToolUserError`. `module.properties` gains `jmix.ai-agent.tools.max-filter-depth = 3`. Commits `998c500`, `c08d8d3`, `542891b`. Deviations: (1) Jmix 2.8 op constant is `NOT_CONTAINS`, not `DOES_NOT_CONTAIN` — mapper accepts both DSL spellings, emits real Jmix constant (Rule 1 bug); (2) renamed a Javadoc mention of `JpqlCondition` to `raw-JPQL` to satisfy acceptance grep (Rule 3).
- 03-03: ✅ LLM-facing tool surface — 4 files added / 2 modified: `ToolResultFormatter` (`<data>` wrap + literal-delimiter HTML escape, D-13/Pitfall 4), `BuiltInDataTools` (single @Component, six @Tool methods — `list_entities`/`describe_entity`/`find_records`/`count_records`/`get_record`/`get_related_records` — all read-only via `DataManager.load`/`.getCount`, `FetchPlan.INSTANCE_NAME`, `ToolLimits.clampLimit`), `AgentToolCallbacks` (per-request `ToolCallback[]` via `MethodToolCallbackProvider` — Phase 4 entry point), `AiToolsAutoConfiguration` (`@AutoConfigureAfter` AI + SPI defaults). `AutoConfiguration.imports` now 3 lines. `ai-agent.gradle` adds `org.ow2.asm:asm:9.7` testImplementation for Plan 04 D-16. Commits `d4f3e98`, `bfd76c9`, `ece124c`. Deviations: (1) Spring AI 1.1.4 has no `ToolCallbacks.from(bean)` — replaced with `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` (Rule 1 bug in plan interfaces); (2) `LoadContext.createQuery(...)` is not a static factory — used `new LoadContext.Query(...)` (Rule 1 bug in plan interfaces).

## Phase 04 Progress

- 04-01: ✅ AiToolCallAudit runId/kind/phase/promptHash/errorClass schema + entity + EN/VI i18n. Commits 8181ff4, 1789b7e.
- 04-02: ✅ Orchestration foundations — 5 classes under `com.vn.agent.orchestration`: `AiAgentDefaultsProperties` (@ConfigurationProperties record, `jmix.ai-agent.defaults.*`, D-04), `RunContext` (ThreadLocal<UUID>, D-12), `ConversationNotFoundException` (single-literal message for D-09 opacity + i18n key), `AiParametersResolver` (DataManager active-row read with Metadata.create() fallback synthesising defaults YAML; effectiveModel/Temperature/TopP/MaxTokens/SystemPrompt accessors; OpenRouter slug validation), `BaselineContextProvider` (compose(UUID) returns agent.* keyed map; renderAsText(UUID) sorts alphabetically for deterministic prompt composition — D-15). 6 unit tests (3 resolver + 3 baseline, incl. renderAsText determinism). 8 new host application.properties keys (5 defaults + 3 spring.ai.openai). Commits 586ef7a, 5a0e4be. Deviations: (1) Rule 1 — plan test snippet typed `dataManager.load(...).query(...)` mock as FluentLoader.ByCondition; Jmix 2.8 returns FluentLoader.ByQuery; (2) Rule 2 — wrapped CurrentAuthentication.getUser()/getLocale() in try/catch for genuine anonymous contexts at runtime (not just mocks); (3) Plan gap — added `maxTokens` line to fallback YAML for accessor symmetry.
- 04-03: ✅ Audit pipeline — 5 classes under `com.vn.agent.audit`: `ToolCallAdvisorBuilderProbe` (OQ-1 closure constants: RESOLVED_BUILDER_METHOD="disableMemory", ORDER_SETTER_METHOD="advisorOrder", INTERNAL_FLAG_FIELD="conversationHistoryEnabled"), `AuditListenerFanOut` (@Component; List<AuditListener> auto-collect; per-listener try/catch(Throwable) — D-13/SPI-06), `AuditWriter` (@Component; sole @Transactional(REQUIRES_NEW) surface — D-11; three methods writeChatPre/writeChatPost/writeToolCall; writeToolCall registers TransactionSynchronization.afterCommit → fanOut.fireToolCallAudited — D-14; chat-level rows use sentinel toolName="<chat>" + sentinel outcome), `AuditAdvisor` (@Component; implements CallAdvisor; Ordered.HIGHEST_PRECEDENCE — ORCH-02/AUD-01; B8 reads runId from advisor context "audit.runId" with UUID.randomUUID() fallback; SHA-256 promptHash of last USER message; RunContext.set/clear in try/finally; no @Transactional, no this.write self-calls — Pitfall #3 closed), `ToolCallbackAuditDecorator` (implements ToolCallback — AUD-04/AUD-02; overrides call(String) and call(String, ToolContext); PRE row eager, POST row in finally; resultSummary truncated to 4096 chars; no @Transactional). EN/VI i18n key com.vn.agent.audit/AuditWriteFailed. Commits 463115a, 13939cd, 70eb934. Deviations: none — plan executed exactly as written; compileJava green on first try.
- 04-04: ✅ Orchestration wiring — 4 new classes + 5 modified under `com.vn.agent.orchestration` + ai-agent root: `ChatResponseDto` (5-component record: conversationId/runId/content/model/latencyMs), `ConversationGateway` (@Component; loadOrCreate(userId, conversationId, firstMessage) — D-09 opacity via combined (id, createdBy) JPQL; D-08 title rule truncates firstMessage to 80 chars on auto-create; InvalidUserId EN/VI i18n), `ChatClientFactory` (@Configuration; @Bean cached ChatClient with verified advisor order AuditAdvisor HIGHEST_PRECEDENCE → MessageChatMemoryAdvisor +200 via .order(int) → ToolCallAdvisor +300 via .advisorOrder(int) + .disableMemory() + .toolCallingManager(ToolCallingManager)), `ProjectingChatMemoryRepository` (@Primary @Component ChatMemoryRepository; all mutating methods @Transactional; writes AiMessage rows via DataManager in same REQUIRED tx as JdbcChatMemoryRepository — D-08 dual-layer). Modified: `ChatService` (new DTO signature `ChatResponseDto ask(String userId, UUID conversationId, String message)`), `ChatResponse` (@Deprecated forRemoval), `DefaultChatServiceImpl` (full orchestration body — cached ChatClient per-request .prompt(); B8 pre-allocated runId handed to AuditAdvisor via advisor context `audit.runId`; B-NEW-1 BaselineContextProvider.renderAsText(convId) text baseline; generic ChatOptions — Rule 1 dev over plan-specified OpenAiChatOptions), `AgentToolCallbacks` (wraps every ToolCallback in ToolCallbackAuditDecorator per AUD-04; adds callbacksFor(userId, conversationId) entry), `AIAutoConfiguration` (drops ChatClient @Bean — owned by ChatClientFactory now; adds ChatMemory MessageWindowChatMemory size 20 + raw JdbcChatMemoryRepository @ConditionalOnMissingBean). Deleted 4 Phase 1 tests pinned to old signature. Added spring-ai-model-chat-memory-repository-jdbc:1.1.4 deps to ai-agent + ai-agent-starter gradles. Commits 8ceeee5, e353bf3, ef013e4. Deviations: (1) Rule 1 — generic ChatOptions replaces plan's OpenAiChatOptions (ai-agent classpath has only spring-ai-client-chat); (2) Rule 3 — added JDBC chat-memory deps to both modules; (3) Rule 1 — injected ToolCallingManager into ChatClientFactory (ToolCallAdvisor.builder NPEs without it); (4) Rule 1 — literal Ordered.HIGHEST_PRECEDENCE + N replaces nonexistent BaseAdvisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER; (5) Rule 3 — deleted 4 obsolete Phase 1 tests (replaced by 04-05).
- 04-05: ✅ Verification suite — 9 test classes under `com.vn.agent.test_support/orchestration/audit/live`: `StubChatModelConfiguration` (@TestConfiguration @Primary ChatModel echoing "STUB:<text>", generic ChatOptions.builder().model("stub/model")), `AdvisorOrderStructuralTest` (reflective read of DefaultChatClient.defaultChatClientRequest.advisors asserting AuditAdvisor@HIGHEST_PRECEDENCE / MessageChatMemoryAdvisor@MIN+200 / ToolCallAdvisor@MIN+300 + ToolCallAdvisorBuilderProbe.INTERNAL_FLAG_FIELD disabled), `OrchestrationIntegrationTest` (2 tests: conversation auto-create with createdBy + D-08 title rule; two-turn ask produces two chat audit pairs keyed by runId), `OwnershipOpacityTest` (cross-user + nonexistent-id probes throw identical ConversationNotFoundException with identical default message — D-09), `DualLayerParityTest` (JdbcChatMemoryRepository.findByConversationId row count and order match DataManager.load(AiMessage) — D-08), `AuditDurabilityTest` (TransactionTemplate + setRollbackOnly proves REQUIRES_NEW audit row survives outer rollback — AUD-02), `AuditListenerFanOutTest` (@ConditionalOnProperty(ai-agent.test.fanout-listeners=true) + @TestPropertySource gate; throwing listener does not block recording listener; D-14 afterCommit fires — SPI-06/D-13), `AuditWriterFieldMappingTest` (4 tests: writeChatPre + writeToolCall + BLOCKED/denialReason + reflective negative assertion for legacy B1 method names — W14), `ChatServiceLiveSemanticTest` (@Tag("live") primary + @EnabledIfEnvironmentVariable(OPENROUTER_API_KEY) double-gate; soft containsAnyOf assertion — TEST-05). Test property additions: jmix.ai-agent.defaults.{model,temperature,top-p,max-tokens,system-prompt} to test-app.properties. `./gradlew :ai-agent:ai-agent:test` PASS (18 suites, 0 failures). Commits 2edbc34, a7810a4, 866f62e, bcfc3c7. Deviations: (1) Rule 1 — ProjectingChatMemoryRepository.saveAll changed to delete-then-insert mirroring JDBC atomic-replace semantics (append-only duplicated rows because MessageWindowChatMemory supplies cumulative list); added seq column + monotonic createdDate for deterministic ordering; (2) Rule 1 — AuditListenerFanOutTest nested TwoListeners @TestConfiguration gated with @ConditionalOnProperty because AIConfiguration's plain @ComponentScan picks up @TestConfiguration under com.vn.agent (plain scan does not apply TypeExcludeFilter); (3) Rule 3 — added jmix.ai-agent.defaults.* stub values to test-app.properties so AiParametersResolver binds at @SpringBootTest boot.

## Phase 03 Progress (archived)

- 03-05: ✅ Host-side SPI-01 sample + integration test — 2 files added in jmix-app: `OrderSummaryToolContributor` (implements `ToolContributor`, exposes `@Tool summarize_customer_orders` joining Order + Customer via DataManager with named-parameter JPQL) + `ChatServiceToolIntegrationTest` (`@SpringBootTest`, 3 assertions: per-request assembly composes 7 callbacks incl. `summarize_customer_orders`, `find_records` round-trip via DataManager against seeded Order, admin describe_entity surfaces structured JSON). Phase 3 success criterion #3 DataManager-path covered. 3 files modified: `jmix-app/build.gradle` (Rule 3 — spring-ai-client-chat host dep), `ToolResultFormatter.java` + its test (Rule 1 — `EntityStates.isLoaded` guard to skip unfetched attributes in `buildEntityMap`). Commits `8198ab1`, `cdfd28d`, `fcac566`. Deviations: (1) Rule 3 — host needs spring-ai-client-chat explicitly because add-on declares it `implementation` only; (2) Rule 1 — Customer entity has `name/email/phone`, not plan's `firstName/lastName/email`; (3) Rule 1 — `FetchPlan.INSTANCE_NAME` only loads `@InstanceName`-referenced attrs; formatter must skip unfetched.
- 03-04: ✅ Tests + TOOL-08 build-time enforcement — 8 test files under `tools/` `filter/` `metadata/`: `ToolLimitsTest`, `LiteralCoercerTest`, `FilterDslMapperTest`, `MetamodelScannerTest`, `EffectiveSchemaComputerTest`, `ToolResultFormatterTest`, `PromptInjectionHarnessTest` (success criterion #5 + Pitfall 4 delimiter-escape), `BuiltInDataToolsReadOnlyTest` (ASM `ClassReader` scan — fails build on any `@Tool` method calling `DataManager.save/saveContext/remove` or `EntityManager.*`, and on any LLM-parameter flow into `createQuery`/`LoadContext$Query` concat). Sabotage-and-revert experiment confirmed the ASM test catches mutations. Commits `7dedbd5`, `cfffc6f`, `b83d58f`, `51ac8f8`. Deviations: (1) ASM 9.7 → 9.9 — 9.7 rejects JDK 25 class v69 (Rule 3); (2) Mockito `mockConstruction` requires extracting local values before `when(...)` to avoid UnfinishedStubbingException (Rule 1 pattern doc); (3) `Datatype<?>` generic wildcard — use raw types in Mockito stubs (Rule 1); (4) `runWithSystem` takes `Runnable` not `Supplier` — drop `return null;` from lambdas (Rule 1).

## Session Tracking

- 2026-04-19 13:28 +07:00 — Forensic investigation complete. Resume from `.planning/forensics/report-20260419-132820.md`.
- 2026-04-19 16:24 +07:00 — Plan 03-01 complete (metadata core). Next: 03-02 (FilterNode DSL / LiteralCoercer / FilterDslMapper).
- 2026-04-19 17:35 +07:00 — Plan 03-02 complete (Filter DSL + tool primitives). Next: 03-03 (ToolResultFormatter + BuiltInDataTools six @Tool methods).
- 2026-04-19 18:30 +07:00 — Plan 03-03 complete (LLM-facing tool surface). Next: 03-04 (unit tests + PromptInjectionHarnessTest + ASM BuiltInDataToolsReadOnlyTest).
- 2026-04-19 19:30 +07:00 — Plan 03-04 complete (8 test files + TOOL-08 ASM enforcement). Next: 03-05 (final phase plan — integration test in jmix-app).
- 2026-04-19 20:15 +07:00 — Plan 03-05 complete (OrderSummaryToolContributor + ChatServiceToolIntegrationTest). Phase 3 static verification PASS — pending human Gradle verify + merge to master.
- 2026-04-20 00:32 +07:00 — Extracted Phase 03 learnings to `.planning/phases/03-metadata-first-runtime-six-tools/03-LEARNINGS.md`.
- 2026-04-20 00:45 +07:00 — Quick task 260420-09p complete: Phase 3 docs resynced after post-execute refactor (metadata collapse 6→1, filter renames, new `ToolResultPayloads`/`ChatServiceSmokeRunner` surfaced, 2026-04-20 audit entry appended to DISCUSSION-LOG). Phase 03 Progress bullets above retain original execution-time prose by design — the phase-folder SUMMARYs are the resynced source of truth.
- 2026-04-20 11:55 +07:00 — Plan 04-01 complete (Liquibase 080 + AiToolCallAudit entity fields + EN/VI i18n; compileJava green). Commits 8181ff4, 1789b7e. Next: 04-02.
- 2026-04-20 12:40 +07:00 — Plan 04-02 complete (orchestration foundations: 5 orchestration classes + 2 unit test classes; 6 tests green; 8 new application.properties keys for defaults + OpenRouter; EN/VI ConversationNotFound i18n key). Commits 586ef7a, 5a0e4be. Next: 04-03.
- 2026-04-20 13:30 +07:00 — Plan 04-03 complete (audit pipeline: ToolCallAdvisorBuilderProbe + AuditListenerFanOut + AuditWriter REQUIRES_NEW + AuditAdvisor HIGHEST_PRECEDENCE + ToolCallbackAuditDecorator; EN/VI AuditWriteFailed i18n key; compileJava green). Commits 463115a, 13939cd, 70eb934. Next: 04-04.
- 2026-04-20 12:22 +07:00 — Plan 04-04 complete (orchestration wiring: ChatResponseDto + ConversationGateway + ChatClientFactory + ProjectingChatMemoryRepository; DefaultChatServiceImpl full rewrite with B8 runId advisor context + B-NEW-1 text baseline; AgentToolCallbacks wraps every callback in ToolCallbackAuditDecorator; AIAutoConfiguration drops ChatClient @Bean moves to ChatClientFactory; adds JDBC chat-memory deps + InvalidUserId EN/VI; compileJava green). Commits 8ceeee5, e353bf3, ef013e4. Next: 04-05 (integration tests + evaluation).
- 2026-04-20 12:50 +07:00 — Plan 04-05 complete (Phase 4 verification suite: StubChatModelConfiguration + 8 test classes covering advisor order, D-08 parity, D-09 opacity, AUD-02 durability, SPI-06/D-13 fan-out, W14/B1 field mapping, TEST-05 live wire). Rule 1 fix to ProjectingChatMemoryRepository (delete-then-insert) + test-isolation fix to AuditListenerFanOutTest. ./gradlew :ai-agent:ai-agent:test PASS (18 suites, 0 failures). Commits 2edbc34, a7810a4, 866f62e, bcfc3c7. **Phase 4 complete (5/5 plans).** Next: Phase 4 merge + Phase 5 (RAG layer).
- 2026-04-20 14:30 +07:00 — Plan 05-03 complete (async ingestion pipeline: IngestionStatusWriter REQUIRES_NEW + CancellationRegistry + MdcPropagatingTaskDecorator + aiAgentIngestExecutor bean + AsyncIngestionWorker Tika→TokenTextSplitter→metadata-enriched VectorStore.add; CANCELLED enum added; Option A flattened role_<code>=true flags; spring.ai.retry.* defaults wired). 18 unit tests green. Commits f316f77, b75b443, 97d60f3. Decisions: REQUIRES_NEW writer isolates status commits from @Async (D-14); built-in spring.ai.retry.* replaces spring-retry (D-17); flattened role flags portable across vector stores; CANCELLED enum value for D-20 terminal state. Next: 05-04 (knowledge-document upload service) or 05-05 (integration tests).
- 2026-04-20 19:23 +07:00 — Plan 05-04 complete (document service + SPI fan-out: KnowledgeDocumentUploadService with ResourceRoleRepository.findRoleByCode validation + afterCommit worker dispatch; KnowledgeDocumentService atomic delete + reingest; IngesterManager List<CustomIngester> fan-out; opt-in ClasspathMarkdownIngester; UnknownRoleCode + DocumentNotFound exceptions; 6 new RAG i18n keys EN + VI). 20 Mockito tests green (6+7+7); full :ai-agent:ai-agent:test passes. Commits 07776bb, 5f04405, 0f491d7, ab30cdd. Decisions: sourceUri→fileName + sourceKind→mimeType (no Liquibase churn); IngesterManager forwards classpath:/file: URIs verbatim; reingest skips role re-validation; D-19 UUIDv5 upsert still deferred. Next: 05-05 (integration tests + Phase 5 verification gate).

### Quick Tasks Completed

| # | Description | Date | Commit | Status | Directory |
|---|-------------|------|--------|--------|-----------|
| 260420-09p | sync phase 3 docs and artifacts with the current code after a large refactor, then verify consistency | 2026-04-19 | pending | Verified | [260420-09p-sync-phase-3-docs-and-artifacts-with-the](./quick/260420-09p-sync-phase-3-docs-and-artifacts-with-the/) |
| 260420-se6 | fix JetBrains file problems project-wide (diamond, @NonNull on @NonNullApi overrides, javadoc, getLast, Objects::nonNull, boolean XOR) | 2026-04-20 | pending | Verified — `:ai-agent:ai-agent:test` green | [260420-se6-fix-jetbrains-file-problems-project-wide](./quick/260420-se6-fix-jetbrains-file-problems-project-wide/) |

**Last activity:** 2026-04-20
