# Codebase Concerns

**Analysis Date:** 2026-04-24

## Tech Debt

**Composite build and nested wrapper sprawl:**
- Issue: The repository root composes `jmix-app` and `ai-agent`, while both child projects keep their own Gradle wrappers and independent settings. Use the root composite for cross-module work and child wrappers only when explicitly validating a published/standalone consumer path.
- Files: `settings.gradle`, `ai-agent/settings.gradle`, `jmix-app/settings.gradle`, `ai-agent/gradlew`, `jmix-app/gradlew`
- Impact: Developers can run different entry points and accidentally validate a different dependency graph than the one used by the root workspace.
- Fix approach: Document the intended command surface, keep wrapper versions aligned, and avoid adding new build logic to only one entry point.

**Publishing configuration still carries release-time assumptions:**
- Issue: Maven publishing is configured directly in Gradle with Nexus coordinates and credential properties. Do not rely on checked-in defaults or local credentials during release readiness work.
- Files: `ai-agent/build.gradle`
- Impact: Publishing tasks can fail late or publish to the wrong repository if `nexusUsername` / `nexusPassword` and target repository are not validated in CI.
- Fix approach: Move release configuration into documented CI properties, fail fast when required publish properties are absent, and remove insecure protocol allowance before release.

**Chat service is a high-complexity orchestration hotspot:**
- Issue: `DefaultChatServiceImpl` owns chat creation, guard handling, parameter resolution, memory, RAG advisor parameters, tool callback wiring, streaming fallback, audit rows, typed-output retries, token accounting, and cancellation cleanup in one class.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
- Impact: Small changes to streaming, guardrails, or audit behavior can regress unrelated paths because lifecycle state is spread across one large method chain.
- Fix approach: Keep changes surgical; add focused regression tests for every lifecycle branch touched; consider extracting stream assembly, audit outcome mapping, and typed-output retry policy after Phase 8 hardening.

**Cancellation registry has mixed responsibilities:**
- Issue: `CancellationRegistry` handles document ingestion generation counters and chat stream `Disposable` cancellation. The same `cancel(UUID)` API means either “cancel document work” or “cancel chat run” depending on the UUID passed.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
- Impact: Future code can confuse document ids and run ids, especially because the class package is `rag` but the UI chat path also depends on it.
- Fix approach: Split chat stream cancellation into a dedicated `ChatStreamCancellationRegistry` or introduce typed wrapper methods that make document id vs run id explicit.

**Flow UI chat uses raw Vaadin listeners inside a Jmix fragment:**
- Issue: `ChatPanelFragment` creates `MessageInput` programmatically and wires `messageInput.addSubmitListener(...)`. This is justified by the Vaadin `MessageInput` API, but it bypasses the preferred XML + `@Subscribe` pattern used for Jmix components.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml`
- Impact: Lifecycle and test assumptions differ from normal Jmix XML-bound components; future UI changes should not blindly copy this pattern to standard Jmix controls.
- Fix approach: Keep raw listeners limited to Vaadin message primitives; continue using `@Subscribe` for Jmix buttons, loaders, containers, and standard components.

**Agent store recovery depends on Liquibase history correctness:**
- Issue: Runtime disables Spring AI schema auto-initialization and expects add-on Liquibase changelogs to create chat memory and pgvector structures. If a table is dropped while Liquibase history remains applied, the app fails at runtime rather than self-healing.
- Files: `jmix-app/src/main/resources/application.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-chat-memory.xml`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-kb-vector-store.xml`
- Impact: UAT can fail with generic chat errors even though the code and changelog files are present.
- Fix approach: Add idempotent recovery changesets for critical agentstore tables/indexes or provide an operational repair script with clear verification steps.

## Known Bugs

**Chat cancellation does not persist a cancellation audit outcome:**
- Symptoms: Stopping an in-progress stream disposes the Reactor subscription, but the audit model and write path do not persist a chat-level `CANCELLED` tool-call outcome.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java`, `.planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md`
- Trigger: User clicks Stop before the stream reaches the normal final event path.
- Workaround: None in the UI; inspect partial chat/audit rows by `runId` when debugging.

**Tool-call streaming events can be dropped across scheduler boundaries:**
- Symptoms: Tool calls may audit in the backend but fail to render inline markdown in the chat stream when `RunContext` / stream sink state is not visible on the tool execution thread.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java`, `.planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md`
- Trigger: Tool-using response where Spring AI invokes tool callbacks outside the original chat stream thread context.
- Workaround: Audit records can still be used to inspect tool execution; UI streaming needs explicit context propagation or explicit sink/run id passing.

**Chat URL query parameter synchronization remains fragile:**
- Symptoms: The route can read a `conversationId` query parameter, but current conversation state is owned inside the fragment and must be pushed back to the URL after first send.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`, `.planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md`
- Trigger: Start a new chat from `/ai-agent/chat`, send the first message, then refresh/share the URL before query-param sync is implemented or verified.
- Workaround: Use the conversation list to reopen saved conversations.

**Upload staging depends on host configuration:**
- Symptoms: Knowledge-base upload submits staged `file:` URIs, and validation rejects them unless `jmix.ai-agent.rag.upload.file-staging-root` matches the staging directory.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `jmix-app/src/main/resources/application.properties`, `.planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md`
- Trigger: Upload a source in a host app without a compatible `jmix.ai-agent.rag.upload.file-staging-root` setting.
- Workaround: Configure the property to the same local staging directory used by the upload UI before testing uploads.

## Security Considerations

**Application properties contain environment-specific credentials/configuration:**
- Risk: The sample app stores datasource username/password properties and AI endpoint configuration in checked-in `application.properties`. Do not copy these values into documentation, logs, or future code.
- Files: `jmix-app/src/main/resources/application.properties`, `.env`
- Current mitigation: `.env` exists for externalized secrets and is not read by the mapper; Spring imports optional `.env` configuration.
- Recommendations: Move sample secrets to local-only files or environment variables, keep committed files to placeholders, and add CI checks that prevent real credentials from being committed.

**LLM tool surface exposes readable data by design:**
- Risk: Built-in tools let the LLM list entities, describe metadata, count, find, read, and traverse related records. Any Jmix security misconfiguration becomes an AI data exposure issue.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/CurrentUserSchemaAccessTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java`
- Current mitigation: Tools use `DataManager`, `CurrentUserSchemaAccess`, whitelisted `MetaClass` resolution, read-only operations, result limits, and tests that block direct `EntityManager` usage.
- Recommendations: Add multi-role integration coverage for every host role added by consumers; treat role policy changes as AI-exposure changes.

**Prompt-injection-safe output formatting is central but easy to bypass:**
- Risk: Tool outputs and RAG snippets are model inputs. Any new formatter that emits raw user-controlled content without bounding, escaping, or source labeling can increase prompt-injection exposure.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java`
- Current mitigation: Existing formatter and tests cover prompt-injection and markdown/XSS paths for current renderers.
- Recommendations: Route all new tool/RAG output through existing formatter patterns and extend the harness before adding new content types.

**Audit schema currently encodes chat-level events as tool-like rows:**
- Risk: Chat-level denials and flagged outputs use sentinel tool names/outcomes, which can obscure whether a row represents a real tool call or a request-level event.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`, `.planning/ROADMAP.md`
- Current mitigation: Sentinel naming and outcome fields distinguish request-level guard events in current queries.
- Recommendations: Complete the planned tree-lite audit schema redesign before adding retrieval audit or additional request-level event kinds.

## Performance Bottlenecks

**RAG ingestion is single-node and local-state coordinated:**
- Problem: Async ingestion cancellation uses in-memory maps and a local executor; there is no cluster-wide cancellation, queue, or backpressure story.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`, `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiToolsAutoConfiguration.java`
- Cause: v1 intentionally targets a single JVM; generation counters and `Disposable` state are process-local.
- Improvement path: Move cancellation state to database/Redis and add queue metrics before supporting horizontally scaled deployments.

**Tool result size limits protect context but hide pagination needs:**
- Problem: `find_records` clamps result size and returns truncation hints rather than cursor-based pagination.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolLimits.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolLimitsTest.java`
- Cause: LLM context budget is prioritized over exhaustive data transfer.
- Improvement path: Keep default limits conservative; add explicit cursor/page tools only when there is a concrete user story requiring multi-page analysis.

**Vector store dimensionality is fixed to current embedding defaults:**
- Problem: pgvector DDL defines a fixed vector size for the configured embedding model family.
- Files: `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-kb-vector-store.xml`, `jmix-app/src/main/resources/application.properties`
- Cause: pgvector columns require a fixed dimension; model/provider changes can require schema migration or re-embedding.
- Improvement path: Treat embedding-model changes as data migrations with reindexing, not simple configuration flips.

## Fragile Areas

**Streaming chat lifecycle:**
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java`
- Why fragile: State crosses Reactor streams, Vaadin `UI.access`, Spring Security context, `RunContext`, audit writers, and cancellation registry cleanup.
- Safe modification: Add tests around stream success, provider error, guard denial, tool event emission, Stop, detach, and URL replay for every lifecycle change.
- Test coverage: Unit and UI-fragment tests exist, but UAT still documents gaps around tool markdown, cancellation audit, URL sync, and generic runtime errors.

**Knowledge ingestion lifecycle:**
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
- Why fragile: Upload staging, source URI validation, async worker retries, cancellation state, status events, vector-store writes, and document rows must remain consistent.
- Safe modification: Preserve fail-closed source validation; add integration tests for every new status transition and upload source type.
- Test coverage: RAG integration tests cover core upload/retry/delete paths, but UI upload configuration errors still need clearer user-facing coverage.

**i18n and locale parity:**
- Files: `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`, `ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/I18nParityTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java`
- Why fragile: UI UAT already surfaced raw message keys when runtime resources or migrations are out of sync.
- Safe modification: Add every new key to all locale files and run parity tests before UI verification.
- Test coverage: Parity tests exist; deployed runtime still needs smoke verification after resource changes.

## Scaling Limits

**Single JVM runtime assumptions:**
- Current capacity: One running application instance owns chat stream cancellation state, ingestion cancellation state, and Vaadin push sessions.
- Limit: Multiple app nodes can disagree about active streams, cancelled ingestions, and in-flight upload state.
- Scaling path: Externalize cancellation/run state, use a shared task queue for ingestion, and document sticky-session requirements for Vaadin push before clustering.

**Read-only tool model:**
- Current capacity: Six read-only data tools plus host-provided contributors.
- Limit: No first-class mutation workflow, approval flow, or transaction preview exists for create/update/delete actions.
- Scaling path: Keep mutation tools deferred until explicit product requirements exist; design them around Jmix transactions, policy checks, audit, confirmation, and rollback semantics.

## Dependencies at Risk

**Spring AI 1.1.4 milestone/snapshot API surface:**
- Risk: The code pins Spring AI 1.1.4 and uses APIs around `ChatClient`, memory advisors, pgvector, RAG advisors, tool callbacks, and structured output that may shift across Spring AI releases.
- Impact: Upgrade can break compile-time APIs and runtime behavior for streaming/tool interleaving.
- Migration plan: Keep version upgrades as dedicated phases with Context7/official-doc verification, focused compile fixes, and regression tests around chat, tools, RAG, and structured output.

**Vaadin MessageList / MessageInput integration:**
- Risk: Phase 07.1 intentionally replaced custom chat UI with Vaadin message primitives; current behavior depends on Vaadin component APIs and Jmix fragment lifecycle working together.
- Impact: Vaadin upgrades can change message rendering, markdown handling, or input listener behavior.
- Migration plan: Keep chat UI tests tied to observable behavior, not implementation internals; re-verify markdown, sources, Stop, and input state after Vaadin/Jmix upgrades.

**PostgreSQL pgvector availability:**
- Risk: RAG requires PostgreSQL with pgvector support for production-like vector retrieval.
- Impact: Hosts without pgvector cannot use document retrieval even if base chat/data tools work.
- Migration plan: Fail clearly when vector store beans or schema are unavailable; keep HSQLDB test paths limited to non-vector scenarios.

## Missing Critical Features

**Phase 8 release readiness is not complete:**
- Problem: Planning state marks all implementation plans complete but the project is awaiting human UAT for Phase 07.1 and Phase 8 hardening/release readiness is not started.
- Blocks: Shipping a stable v1 add-on release with documented install, configuration, migration, and support posture.

**Operational repair and diagnostics are thin:**
- Problem: Runtime failures such as missing chat-memory tables or upload staging mismatch surface as generic UI errors unless logs are inspected.
- Blocks: Non-developer operators cannot self-diagnose common environment/configuration drift.

**Cluster support is intentionally absent:**
- Problem: Cancellation, ingestion, and streaming state are local-process concerns.
- Blocks: Horizontal scaling and stateless deployment topologies.

## Test Coverage Gaps

**Human UAT failures need automated regression coverage:**
- What's not tested: Tool markdown emission across streaming threads, Stop-to-cancelled-audit persistence, post-send URL sync, and upload staging misconfiguration messaging.
- Files: `.planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md`, `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java`
- Risk: Fixes can regress before final release because the failures were found manually.
- Priority: High

**Release/consumer smoke tests are partly manual:**
- What's not tested: Published artifact consumption, clean host installation, required environment variables, and production-like PostgreSQL/pgvector setup in CI.
- Files: `docs/consumer-smoke.md`, `jmix-app/build.gradle`, `ai-agent/build.gradle`, `.planning/STATE.md`
- Risk: The add-on can pass module tests but fail for a fresh consumer app.
- Priority: High

**Multi-role AI data exposure needs continuous integration coverage:**
- What's not tested: Every future host role and row-level policy combination against LLM-facing tools and RAG retrieval filters.
- Files: `ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/CurrentUserSchemaAccessTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java`, `jmix-app/src/main/java/com/vn/jmixapp/security/`
- Risk: A security role change can silently expose metadata, records, or documents to the LLM.
- Priority: High

**No automated cluster/concurrency stress suite:**
- What's not tested: Multi-node cancellation, concurrent uploads at production volume, large RAG corpora, long-running streams, and browser detach/reconnect under load.
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
- Risk: Race conditions and resource leaks appear only under production deployment shapes.
- Priority: Medium

---

*Concerns audit: 2026-04-24*
