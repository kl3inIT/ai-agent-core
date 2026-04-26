# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog v1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> **Discipline note (R-XP-1):** every PR that ships user-visible behavior change adds an
> entry under the appropriate `Added / Changed / Deprecated / Removed / Fixed / Security`
> subheading here, BEFORE merge. Do NOT bump the version on the unreleased entry — that
> happens at release time, when the section is renamed `[X.Y.Z] - YYYY-MM-DD` and a fresh
> empty `[Unreleased]` block is added at the top. Hosts watching this file for changes
> can rely on the `[Unreleased]` block always being present.

### Added
- (nothing yet)

### Changed
- (nothing yet)

### Fixed
- (nothing yet)

## [1.0.0] - 2026-04-26

First release. Backfilled per-phase per R-07g (chronological order — Phase 7 main
landed before its 7.1 / 7.2 polish phases).

### Added — Phase 1 (Walking Skeleton)
- Initial Jmix 2.8 + Spring Boot 3 + Vaadin Flow project skeleton with the agentstore
  additional store, Liquibase changelog discipline, and bootRun + test entry points.
- Demo `jmix-app/` host with Customer / Order entities for end-to-end exercise.

### Added — Phase 2 (Foundations)
- `AiConversation` + `AiMessage` JPA entities on the agentstore (changesets `010` + `020`).
- Six SPI interfaces in `com.vn.agent.spi`: `ToolContributor`, `ContextContributor`,
  `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`.
- `AIConfiguration` + `AgentstoreStoreConfiguration` Spring boot wiring.
- `FoundationsBootSmokeTest` proving the add-on auto-config fires.

### Added — Phase 3 (Metadata-first runtime + six tools)
- Six built-in `@Tool` methods over the Jmix metamodel (`list_entities`,
  `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`)
  in `BuiltInDataTools`.
- `CurrentUserSchemaAccess` per-request schema filter respecting Jmix entity / attribute
  / row-level security.
- `ToolResultFormatter` with `<data>...</data>` wrapping for untrusted text and HTML
  delimiter escaping (Pitfall 4 prompt-injection harness).
- `ToolLimits` constants (`DEFAULT_LIMIT=20`, `MAX_LIMIT=100`,
  `DEFAULT_MAX_FILTER_DEPTH=3`).
- ASM-based read-only enforcement test.

### Added — Phase 4 (Orchestration core)
- `ChatService` + `ChatClientFactory` wiring Spring AI 1.1.4 with the active
  `AiParameters` profile and tool callbacks.
- `AuditWriter` with REQUIRES_NEW transactional discipline so audit rows survive outer
  rollback (AUD-02 contract).
- `ToolCallbackAuditDecorator` capturing tool input / output / latency / outcome / errorClass
  for every tool invocation.
- `AiAuditEvent` initial schema + `AuditAdvisor` chat-call wiring.
- `AuditDurabilityTest` proving REQUIRES_NEW commit independence (and Pitfall #1
  orphan-removal regression guard).

### Added — Phase 5 (RAG layer)
- pgvector-backed `VectorStore` via `spring-ai-starter-vector-store-pgvector` (table
  `AI_AGENT_KB_VECTOR_STORE`).
- `AiKnowledgeDocument` entity (changeset `050`) + Tika-based ingester pipeline.
- `RetrievalAugmentationAdvisorFactory` with role-scoped retrieval filter.
- Knowledge Base view (Jmix Flow UI) for upload / list / re-ingest / delete.
- `IngesterManager` + `CustomIngester` SPI integration.
- Configurable splitter / embed-retry / executor pool / upload prefixes via
  `jmix.ai-agent.rag.*`.

### Added — Phase 6 (Parameters, structured output, guardrails)
- `AiParameters` entity (changeset `040`) + CRUD view; `AiParametersResolver` with
  defaults fallback chain.
- `RateLimitGuard` (per-user requests/minute), `TokenBudgetGuard` (per-conversation
  ceiling), `IterationCapGuard` (max tool-call iterations).
- `OutputScannerGuard` with named-regex flag-and-pass-through contract (D-17).
- `GuardedToolCallingManager` composing all guards with audit-logged denials.
- 12 evaluation rubrics (E-01..E-12) under `:ai-agent:ai-agent:evalTest`.

### Added — Phase 7 (Flow UI)
- `ChatView` with live token streaming via Vaadin Flow + Spring AI streaming flux.
- `ConversationListView` + `ConversationDetailView` for browsing past chats.
- `AiAuditEventListView` admin view with grid filters + Excel/JSON export.
- `MarkdownRenderer` (flexmark) + OWASP HTML sanitizer for assistant output.
- `AiAgentAdminRole` + `AiAgentUserRole` + row-level role security (jmix-security-flowui).

### Added — Phase 7.1 (Adopt Vaadin MessageList / MessageInput for chat view)
- ChatView pivoted from custom Div-rendered bubbles to native Vaadin
  `MessageList` + `MessageInput` components.
- `ChatPanelFragment` reusable fragment encapsulating the chat surface.
- `MessageBubbleComponent` + `StreamEventRenderer` for streamed tool-call cards
  interleaved with assistant message chunks.

### Added — Phase 7.2 (Redesign audit schema, tree-lite)
- `AiAuditEvent` schema redesigned: single table covers CHAT / TOOL / RETRIEVAL events
  via discriminating `kind` column; self-FK `parent` + `children` collection enables
  tree traversal.
- `RunContext` ThreadLocal carrier for runId / rootAuditId / conversationId / retrieval
  topK / similarityThreshold / filtersJson.
- `AuditListener` SPI signature changed to `onEventAudited(UUID auditId, String kind)` —
  hosts updating from 0.x must port their listener implementations.
- `AuditTreeTraversalTest` + `AuditWriterFieldMappingTest` regression guards.

### Added — Phase 8 (Integration hardening + release readiness)
- TEST-02 / TEST-03 acceptance gaps closed: `PromptInjectionHarnessTest` +2 poisoned
  payloads; `AuditDurabilityTest` +1 ERROR-path decorator-routed rollback test
  (parent + runId field assertions).
- TEST-04 acceptance gap closed: per-tool query-count baselines via
  `datasource-proxy:1.11.0` (BeanPostProcessor wraps the agentstore DataSource — no
  `@Primary` collision); R-03h slope-based N+1 detector across 10 vs 100 children;
  `find_records` hard-limit cap test with sufficient seed precondition.
- TEST-05 acceptance gap closed: `golden-questions.yaml` (7 capability-coverage
  entries — RAG split into positive + empty-kb) + `ChatServiceLiveSemanticGoldenSuiteTest`
  parameterized live-tier test (dual-gated `@Tag("live")` + `@EnabledIfEnvironmentVariable("OPENROUTER_API_KEY")`,
  visible @BeforeAll ENABLED/SKIPPED line).
- Operator `ai-agent/README.md` (270 lines, 9 sections incl. Configuration Matrix
  derived from real `@ConfigurationProperties` source, full SPI cookbook, Troubleshooting
  table, R-06a verification footer pinned to commit + date).
- `CHANGELOG.md` (this file) at repo root.
- Three GitHub Actions workflows under `.github/workflows/`: `ai-agent-ci.yml`
  (PR-blocking — test + integrationTest), `ai-agent-live.yml` (manual dispatch live
  suite), `ai-agent-publish.yml` (manual dispatch + tag-trigger publish to Nexus with
  preflight secrets check).
- Snapshot-vs-release URL conditional in `ai-agent/build.gradle` publishing block.
- `ai-agent/build.gradle` version source moved to `gradle.properties` (CI overrides
  via `-Pversion=...`).

### Changed — Phase 8
- `CLAUDE.md`: `Java 17` → `Java 21` to match the Gradle toolchain (R-06c).

### Removed — Phase 8 (consumer-smoke deferred)
- The `consumer-smoke/` Gradle subproject planned in 08-05 was attempted but deferred
  after surfacing a 6-layer starter-consumability gap chain (Liquibase changelog path,
  missing primary `dataSource` bean, missing `UserRepository` bean, `WebEnvironment`
  setup, `LoginView` hard requirement, pgvector `CREATE EXTENSION` infrastructure
  dependency). See `.planning/phases/08-integration-hardening-release-readiness/08-05-SUMMARY.md`
  for the full chain + recommended follow-up paths. README Quick Start documents this
  honestly rather than promising a working pipeline.

### Security — Phase 8
- `ai-agent/gradle.properties` no longer contains plaintext Nexus credentials.
  **HOWEVER:** the historical credential `nexusUsername=admin` / `nexusPassword=admin123`
  is FOREVER BURNT in git history (commits prior to 08-07). Rotation in the Nexus
  admin UI is REQUIRED — removing the lines from the current head does not
  re-secure the credential. R-07a documented this in the threat model.

[Unreleased]: https://github.com/<org>/ai-agent-core/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/<org>/ai-agent-core/releases/tag/v1.0.0
