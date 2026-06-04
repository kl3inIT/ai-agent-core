# External Integrations

**Analysis Date:** 2026-06-04

## APIs & External Services

**LLM Provider (chat + embeddings):**
- OpenRouter - OpenAI-compatible gateway for both offline-class (Qwen) and online (Claude) models.
  - SDK/Client: `org.springframework.ai:spring-ai-openai:1.1.4` (`OpenAiChatModel` / `OpenAiEmbeddingModel`).
  - Base URL: `spring.ai.openai.base-url=https://openrouter.ai/api` (`jmix-app/.../application.properties`).
  - Auth: `spring.ai.openai.api-key=${OPENROUTER_API_KEY:}` (env var, loaded from `.env`).
  - Default chat model: `qwen/qwen3.6-35b-a3b`; embedding model: `qwen/qwen3-embedding-4b` (2000 dims).
  - Curated multi-vendor catalog (`module.properties`, `jmix.ai-agent.models.catalog[n]`): `qwen/qwen3.6-35b-a3b` (default), `qwen/qwen3-32b`, `anthropic/claude-sonnet-4-6`, `anthropic/claude-opus-4-7`. Host deployment determines runtime reachability (offline Qwen self-host vs online Claude).
  - Retry: built-in `spring.ai.retry.*` (5 attempts, 2s initial backoff, ×2, 30s cap; no `@Retryable`).

**Tool-mediated data access:**
- Spring AI `@Tool` callbacks (`com.vn.agent.tools.*`) expose Jmix entities to the LLM: `BuiltInDataTools` (read-only, ASM-enforced), mutation tools under `tools/mutation/`, entity link/fetch-plan helpers. All access is funneled through Jmix `AccessManager`/`DataManager` — the AI is "just another Jmix client" with no privileged bypass.

## Data Storage

**Databases:**
- PostgreSQL (primary store `ai-agent`) - `main.datasource.url=jdbc:postgresql://...:5555/ai-agent`. Driver `org.postgresql:postgresql`, ORM EclipseLink (`jmix-eclipselink-starter`). Liquibase `com/vn/jmixapp/liquibase/changelog.xml`.
- PostgreSQL + pgvector (`agentstore` additional store) - `agentstore.datasource.url=...:5555/agentstore`, registered via `jmix.core.additional-stores=agentstore`. Holds AI entities (`AiConversation`, `AiMessage`, `AiAuditEvent`, `AiKnowledgeDocument`, `AiParameters`, `AiTaskFile`, etc.), Spring AI chat-memory tables, and the pgvector vector store. Liquibase `agentstore-changelog.xml`.
  - Vector store: pgvector table `AI_AGENT_KB_VECTOR_STORE`, 2000 dims, HNSW index. Managed by Spring AI `PgVectorStore` (`spring.ai.vectorstore.pgvector.initialize-schema=true`).
  - Chat memory: Spring AI `JdbcChatMemoryRepository` (schema `initialize-schema=never`; addon owns the script). Decorated by `ProjectingChatMemoryRepository` using `UnconstrainedDataManager`.
  - Studio changelog exclusions: `agentstore.datasource.studio.liquibase.exclude-prefixes=spring_ai_,ai_agent_kb_vector_store` (externally-managed tables).
- Local dev: `pgvector/pgvector:pg16` via `docker-compose.yml` (host port 5433). HSQLDB available as test/fallback datastore.

**File Storage:**
- Jmix Local FS (`jmix-localfs-starter`, host only) for uploaded knowledge-base documents.
- RAG upload staging at `${jmix.core.temp-dir}` (`jmix.ai-agent.rag.upload.file-staging-root`), max 100MB/file. Task files (`AiTaskFile`) with TTL 86400s and per-turn caps.
- Document ingestion via `spring-ai-tika-document-reader` → `TokenTextSplitter` (chunk-size 800, overlap 120) → pgvector.

**Caching:**
- Spring Cache (`spring-boot-starter-cache`) - `ai-agent.rateLimit` (per-user `RateLimitGuard`) and `ai-agent.tokenBreaker` (per-conversation `TokenBudgetGuard`). `AiAgentGuardAutoConfiguration` registers a default `ConcurrentMapCacheManager` if the host declares none.

## Authentication & Identity

**Auth Provider:**
- Jmix security (`jmix-security-starter`, `jmix-security-flowui-starter`, `jmix-security-data-starter`).
  - Implementation: role-based access via `@ResourceRole` interfaces in `com.vn.agent.security` (e.g. `AiAgentAdminRole`). Row-level constraints via `jmix-security-data` (`CrudEntityConstraint`, `ReadEntityQueryConstraint`).
  - System-internal writes (audit, chat memory, ingestion) use `UnconstrainedDataManager` to avoid requiring CRUD grants on system entities.
  - UI login admin/admin (`ui.login.defaultUsername/Password`, dev only).

## Monitoring & Observability

**Error Tracking:**
- None (no external APM/error service detected).

**Logs:**
- SLF4J/Logback via Spring Boot. Verbose addon logging in dev: `logging.level.com.vn.agent=DEBUG`, `com.vn.agent.tools.mutation=DEBUG`, `com.vn.agent.audit=DEBUG`, `org.springframework.ai=DEBUG`.
- Domain audit trail: `AiAuditEvent` entity + `com.vn.agent.audit` package (`AuditWriter`), surfaced in `ToolCallAuditListView` with Excel/JSON export (`jmix-gridexport`).

## CI/CD & Deployment

**Hosting:**
- Addon: Maven artifacts published to Nexus (`nexus.x2h.com.vn`, `jmix-internal-snapshots` / `jmix-internal-releases`). Host app deploys as a standard Spring Boot / Jmix application.

**CI Pipeline:**
- GitHub Actions (`.github/workflows/`). Test gating via Gradle tags: default `test` excludes `live`/`rag-it`/`eval`; `liveTest` (needs `OPENROUTER_API_KEY`), `integrationTest` (needs Docker for Testcontainers pgvector, wired into `check` when Docker present), `evalTest`.

## Environment Configuration

**Required env vars:**
- `OPENROUTER_API_KEY` - LLM access (chat + embeddings; required for `live` tests).
- Datasource credentials for `main` + `agentstore` (set in `application.properties` for dev; externalize for prod).
- Nexus publish: `nexusUsername` / `nexusPassword` (+ optional `nexusReleaseUrl` / `nexusSnapshotUrl`) via CI secrets or user-global `~/.gradle/gradle.properties`.

**Secrets location:**
- `.env` / `../.env` (untracked, imported by Spring config). Nexus creds never committed (historical `admin/admin123` burnt in git history — rotate).

## Webhooks & Callbacks

**Incoming:**
- None.

**Outgoing:**
- None. Server push to the chat UI is internal (Vaadin `@Push` via `AiAgentAppShell`; `DocumentStatusChangedEvent` for live KB ingest status), not an external webhook.

---

*Integration audit: 2026-06-04*
