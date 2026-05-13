# Jmix AI Agent

Drop-in Jmix add-on (2.8+) that lets end-users safely converse with their data and documents. Built on Spring AI 1.1.4. No agent framework code required from the host team.

The add-on ships:
- A chat view (`com.vn.agent.view.chat.ChatView`) that streams responses live.
- Six built-in `@Tool` methods over your Jmix data model (read-only): `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`.
- Per-user / per-conversation guards: rate limit, token budget, output scanning.
- A pgvector-backed RAG layer with role-scoped retrieval.
- Six SPIs for host-side extension (see SPI Cookbook below).
- A Jmix-native audit trail at `AI_AGENT_AUDIT_EVENT` capturing every chat / tool / retrieval event.

## Quick Start (3 commands)

**Prerequisites:** JDK 21 (the Gradle toolchain in `ai-agent/build.gradle` declares `JavaLanguageVersion.of(21)`). Java 17 will not compile.

```bash
git clone <your-fork-url> ai-agent-core
export OPENROUTER_API_KEY=sk-or-v1-...
./gradlew :jmix-app:bootRun
```

Open <http://localhost:8080>, log in as `admin` / `admin`, click **AI Agent → Chat**.

The default `jmix-app` profile expects a PostgreSQL with the `pgvector` extension reachable at `jdbc:postgresql://10.123.123.174:5555/ai-agent` (per `jmix-app/src/main/resources/application.properties`); adjust `main.datasource.url` for your environment, or run a local pgvector via Docker:

```bash
docker run -d -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres \
  pgvector/pgvector:pg16
```

### Optional — clean-consumer smoke (R-06d / R-05f prerequisite)

The `consumer-smoke/` Gradle subproject (introduced in plan 08-05 and currently **deferred** — see `08-05-SUMMARY.md`) is intended to verify that adding `implementation 'com.vn:ai-agent-starter:<version>'` to a fresh Jmix host wires `ChatService` correctly. When that subproject is fully landed, the local pipeline will be:

```bash
# Prerequisite: publish ai-agent + ai-agent-starter to your local Maven repo first.
./gradlew :ai-agent:ai-agent:publishToMavenLocal :ai-agent:ai-agent-starter:publishToMavenLocal

# Then boot the consumer smoke against the just-published artifact:
./gradlew :consumer-smoke:bootRunSmoke
```

Without the publishToMavenLocal step, consumer-smoke cannot resolve `com.vn:ai-agent-starter` and the smoke task fails on dependency resolution. The 6-layer prerequisite chain a real consumer must satisfy is documented in `.planning/phases/08-integration-hardening-release-readiness/08-05-SUMMARY.md`.

## Required Environment Variables

| Variable | Purpose | Default |
|---|---|---|
| `OPENROUTER_API_KEY` | LLM provider key. Without this set, every chat returns 401 from the model provider. | (none — required) |
| `OPENROUTER_BASE_URL` | API endpoint. Override for self-hosted gateways or alternate providers. | `https://openrouter.ai/api/v1` |
| `OPENROUTER_MODEL` | Default LLM slug (provider/model). | `openai/gpt-4o-mini` |
| `OPENROUTER_EMBEDDING_MODEL` | Embedding model slug used by the RAG layer. | `qwen/qwen3-embedding-4b` |
| `OPENROUTER_EMBEDDING_DIMENSIONS` | Vector dimensionality matching the embedding model. | `2000` |

**Runtime:** Java 21 (JDK 21). The Gradle toolchain enforces this; running on JDK 17 will fail to compile. The historical `CLAUDE.md` mention of Java 17 has been corrected per R-06c in this same plan.

## Configuration Matrix

All add-on properties are bound under the `jmix.ai-agent.*` prefix and discovered via `@ConfigurationPropertiesScan` on `AIConfiguration`. Defaults below are taken from the bound POJOs and the test profile properties file (`com/vn/agent/test-app.properties`).

| Property | Default | Description |
|---|---|---|
| `jmix.ai-agent.defaults.model` | `stub/model` (test) / `openai/gpt-4o-mini` (prod) | Model slug used by `AiParametersResolver` when no `AiParameters` profile row is active. |
| `jmix.ai-agent.defaults.temperature` | `0.2` | Default sampling temperature. |
| `jmix.ai-agent.defaults.top-p` | `1.0` | Default top-p sampling. |
| `jmix.ai-agent.defaults.max-tokens` | `1500` | Default max tokens per response. |
| `jmix.ai-agent.defaults.system-prompt` | `You are a helpful assistant.` (`FALLBACK_SYSTEM_PROMPT`) | Last-resort system prompt — the active `AiParameters` profile wins when present. |
| `jmix.ai-agent.guard.rate-limit.enabled` | `true` | Enable per-user request rate limiting. |
| `jmix.ai-agent.guard.rate-limit.requests-per-minute` | `30` | Per-user request ceiling. |
| `jmix.ai-agent.guard.token-breaker.enabled` | `true` | Enable per-conversation token budget. |
| `jmix.ai-agent.guard.token-breaker.ceiling` | `200000` | Max cumulative tokens per conversation before short-circuit. |
| `jmix.ai-agent.guard.iteration-cap.max-iterations` | `8` | Max tool-call iterations per chat turn. |
| `jmix.ai-agent.guard.output-scanner.enabled` | `true` | Enable post-response output scanning. |
| `jmix.ai-agent.guard.output-scanner.patterns[].key` / `.regex` | (configured) | Named regex set; matches flag the response for the UI banner (D-17 flag-and-pass-through). |
| `jmix.ai-agent.embedding.model` | `qwen/qwen3-embedding-4b` | Embedding model slug. |
| `jmix.ai-agent.embedding.dimensions` | `2000` | Vector dimensionality (must match the embedding model). |
| `jmix.ai-agent.embedding.provider-base-url` | (inherits OpenRouter) | Override embedding endpoint. |
| `jmix.ai-agent.rag.admin-bypass` | `true` | Admins receive unfiltered retrieval; non-admins get role-scoped filter. |
| `jmix.ai-agent.rag.top-k` | `5` | Default retrieval `topK`. |
| `jmix.ai-agent.rag.similarity-threshold` | `0.50` | Cosine similarity floor. |
| `jmix.ai-agent.rag.splitter.chunk-size` | `1000` | Tokenized chunk target. |
| `jmix.ai-agent.rag.splitter.chunk-overlap` | `120` | Adjacent chunk overlap. |
| `jmix.ai-agent.rag.splitter.min-chunk-size-chars` | `200` | Drop chunks below this size. |
| `jmix.ai-agent.rag.embed-retry.max-attempts` | `5` | Max attempts on embedding endpoint failure. |
| `jmix.ai-agent.rag.embed-retry.initial-interval` | `PT2S` | Backoff start. |
| `jmix.ai-agent.rag.embed-retry.multiplier` | `2.0` | Backoff multiplier. |
| `jmix.ai-agent.rag.sample-ingester.enabled` | `false` | Toggle the bundled sample ingester. |
| `jmix.ai-agent.rag.sample-ingester.path-pattern` | `classpath:ai-kb/**` | Sample ingester source pattern. |
| `jmix.ai-agent.rag.ingest-executor.core-pool-size` | `2` | Ingestion worker pool. |
| `jmix.ai-agent.rag.ingest-executor.max-pool-size` | `4` | Ingestion worker pool ceiling. |
| `jmix.ai-agent.rag.ingest-executor.queue-capacity` | `50` | Queue size before rejection. |
| `jmix.ai-agent.rag.ingest-executor.keep-alive-seconds` | `60` | Worker idle TTL. |
| `jmix.ai-agent.rag.ingest.max-document-chars` | `1000000` | Reject documents larger than this on upload. |
| `jmix.ai-agent.rag.upload.classpath-allowed-prefixes` | `[classpath:ai-kb/]` | Whitelisted upload source prefixes. |
| `jmix.ai-agent.rag.upload.max-file-size-bytes` | `104857600` | Flow UI per-file upload cap in bytes; align servlet multipart limits above it. |
| `jmix.ai-agent.rag.upload.file-staging-root` | (configured) | On-disk staging directory for uploads. |
| `jmix.ai-agent.parameters.seed-default` | `true` (prod) / `false` (test) | Fire `DefaultParamsSeeder` on `ApplicationReadyEvent`. |
| `agentstore.datasource.*` | (host-supplied) | Required additional store — the add-on persists everything to the `agentstore`. |
| `agentstore.liquibase.change-log` | `com/vn/agent/liquibase/agentstore-changelog.xml` | Pinned changelog path the add-on ships. Hosts MUST set this if not using Spring Boot's default convention. |

For the source of truth, read each `@ConfigurationProperties`-annotated record under `ai-agent/ai-agent/src/main/java/com/vn/agent/`.

## Entity / Table Ownership

Every persisted entity owned by the add-on lives on the `agentstore` Jmix store and is created by Liquibase changesets under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/`.

| Entity | Table | Liquibase Changeset | Created In |
|---|---|---|---|
| `AiConversation` | `AI_AGENT_CONVERSATION` | `010-ai-conversation.xml` | Phase 2 |
| `AiMessage` | `AI_AGENT_MESSAGE` | `020-ai-message.xml` | Phase 2 |
| `AiAuditEvent` | `AI_AGENT_AUDIT_EVENT` | `030-ai-audit-event.xml` | Phase 4 (renamed + reshaped in Phase 7.2) |
| `AiParameters` | `AI_AGENT_PARAMETERS` | `040-ai-parameters.xml` | Phase 6 |
| `AiKnowledgeDocument` | `AI_AGENT_KNOWLEDGE_DOCUMENT` | `050-ai-knowledge-document.xml` | Phase 5 |
| (pgvector store) | `AI_AGENT_KB_VECTOR_STORE` | created by `PgVectorStore` bean at startup | Phase 5 (RAG-02) |

The aggregator changelog `agentstore-changelog.xml` includes all of the above in order.

## SPI Cookbook

Six SPIs ship with the add-on. Each example is a host-side `@Component` implementing the interface — no add-on configuration changes are needed beyond declaring the bean.

### `ToolContributor` — register additional `@Tool` methods

```java
@Component
class CrmTools implements ToolContributor {
    @Override public List<Object> contribute() { return List.of(this); }

    @Tool(description = "Look up a CRM contact by email")
    public Contact lookup(@ToolParam String email) {
        // your query…
    }
}
```

Spring AI's tool-callback resolution discovers every `ToolContributor` bean in the application context; returned beans' `@Tool` methods are exposed via `ToolCallbacks.from(bean)` automatically.

### `ContextContributor` — inject per-request context

```java
@Component
class TenantContextContributor implements ContextContributor {
    @Override
    public void contribute(Map<String, Object> bag) {
        // Reserved keys (agent.userId / agent.username / agent.roles / agent.locale /
        // agent.conversationId) are already populated by the add-on. Add ONLY app-specific
        // entries under a host-owned namespace.
        bag.put("crm.accountId", currentAccountId());
        bag.put("billing.tier", currentTier());
    }
}
```

Implementations MUST NOT overwrite any key under the reserved `agent.*` namespace.

### `PromptContextContributor` — augment system prompt

```java
@Component
class HouseRulesPrompt implements PromptContextContributor {
    @Override public String fragment() {
        return "Refer to customers as 'members'. Never suggest discounts above 15%% without "
             + "citing an explicit promo code.";
    }
    @Override public int getOrder() { return 10; }
}
```

Do NOT re-derive baseline plumbing (current user / roles / locale) — the base prompt already conveys those. Use this SPI ONLY for genuinely domain-specific text (vocabulary, tone, business rules).

### `ToolGuard` — veto tool calls

```java
@Component
class BusinessHoursGuard implements ToolGuard {
    @Override
    public void check(String toolName, Map<String, Object> arguments) {
        if ("issueRefund".equals(toolName) && LocalTime.now().isAfter(LocalTime.of(18, 0))) {
            throw new ToolVetoedException("Refunds disabled outside business hours");
        }
    }
}
```

Guards compose by short-circuit AND: any guard throwing `ToolVetoedException` blocks the call and produces an `AiAuditEvent` row with `outcome = BLOCKED` and the thrown message captured as `denialReason`.

### `AuditListener` — observe audit writes

```java
@Component
class SlackAuditNotifier implements AuditListener {
    @Override
    public void onEventAudited(UUID auditId, String kind) {
        // Fire-and-forget. Listeners MUST swallow their own exceptions —
        // a broken listener cannot corrupt the audit write or the user-visible result.
        try {
            slack.postAsync("#audit", "audit " + kind + " id=" + auditId);
        } catch (RuntimeException quiet) {
            log.warn("Slack audit notify failed for id={}", auditId, quiet);
        }
    }
}
```

Note: the post-Phase-7.2 SPI signature is `onEventAudited(UUID auditId, String kind)`. The pre-7.2 single-method-per-tool-call shape was removed in Phase 7.2.

### `CustomIngester` — plug in additional KB sources

```java
@Component
class ConfluenceIngester implements CustomIngester {
    @Override public String getId() { return "confluence-prod"; }
    @Override public String getDisplayName() { return "Confluence (prod space)"; }
    @Override public List<Document> read() {
        // Pull pages, return Spring AI Documents; the add-on splits + embeds + writes
        // to the vector store on the ingestion worker thread.
        return confluenceClient.fetchAllPagesInSpace("PROD").stream()
                .map(p -> new Document(p.body(), Map.of("title", p.title(), "url", p.url())))
                .toList();
    }
}
```

`getId()` MUST be stable across restarts — it is the source key for re-ingest / delete.

## Upgrade Checklist

- **From 0.x SNAPSHOT to 1.0.0 (Phase 8 release):**
  - Audit table renamed `AI_AGENT_TOOL_CALL_AUDIT` → `AI_AGENT_AUDIT_EVENT` and now stores CHAT / TOOL / RETRIEVAL events under one `kind` column. The Phase 7.2 Liquibase changesets handle the migration; back up agentstore before running.
  - `AuditListener` interface now exposes only `onEventAudited(UUID auditId, String kind)` — the pre-7.2 method that dispatched per tool-call audit is gone. Update host implementations.
  - `BuiltInDataTools.getRelatedRecords` is 3-arg (`entityName`, `id`, `relationship`) — older 4-arg call sites won't compile.
  - `RecordsResult` JSON shape uses the field name `rows` (not `records`).
  - Java toolchain is now 21 (was 17).

## Air-Gap Notes

- No telemetry. No phone-home.
- All LLM and embedding traffic goes to whatever `spring.ai.openai.base-url` resolves to. Air-gapped deployments point this at an internal model gateway (e.g. an enterprise OpenAI-compatible endpoint).
- No bundled API keys. All credentials are host-supplied via env vars.
- pgvector runs in your own database — no external vector-store SaaS.
- File uploads stage to `jmix.ai-agent.rag.upload.file-staging-root` on local disk; nothing is uploaded externally. Large uploads are capped by `jmix.ai-agent.rag.upload.max-file-size-bytes` and by Spring Boot's `spring.servlet.multipart.max-file-size` / `spring.servlet.multipart.max-request-size`.

## Troubleshooting

Common startup and runtime mistakes:

| Symptom | Likely Cause | Fix |
|---|---|---|
| `OpenAiApi: 401 Unauthorized` on first chat | `OPENROUTER_API_KEY` not exported / wrong key | `export OPENROUTER_API_KEY=sk-or-v1-...` and re-run; verify with `echo $OPENROUTER_API_KEY` |
| `UnsupportedClassVersionError` at startup | Running on JDK 17 (or older) | Install JDK 21 (`sdk install java 21-tem` or `brew install openjdk@21`); re-run `./gradlew --stop && ./gradlew :jmix-app:bootRun` |
| `Connection refused: localhost:5432` (or pgvector errors) | Postgres not running for the `jmix-app` default profile | Start postgres (e.g. `docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16`) |
| `LiquibaseException: relation "AI_AGENT_*" does not exist` | Agentstore changelog did not run | Confirm `agentstore.datasource.*` properties are set; confirm `agentstore.liquibase.change-log=com/vn/agent/liquibase/agentstore-changelog.xml` is set; check application logs for the Liquibase header line `Running Changeset: ...AI_AGENT_*` |
| `BeanCreationException: agentstoreDataSource` | Host did not provide an explicit `agentstoreDataSource` bean / properties (R-05b) | Add `jmix.core.additional-stores=agentstore` and `agentstore.datasource.*` to your `application.properties` |
| `bad SQL grammar [CREATE EXTENSION IF NOT EXISTS vector]` | Tried to boot the add-on against a non-pgvector database | Switch the agentstore datasource to PostgreSQL with the `pgvector` extension (HSQLDB and other in-memory engines cannot satisfy this dependency) |
| `View 'login' is not defined` | Host did not provide a Jmix login view | Provide a host-side `LoginView` or set `jmix.ui.login-view-id` to a view that exists in your project |
| Consumer-smoke: `cannot resolve com.vn:ai-agent-starter` | Skipped the publishToMavenLocal prerequisite (R-06d) | Run the prerequisite command in Quick Start before `:consumer-smoke:bootRunSmoke` |
| `ClassNotFoundException: com.vn.agent.autoconfigure.AIAutoConfiguration` | Wrong FQN; actual is `com.vn.autoconfigure.agent.AIAutoConfiguration` (R-05c) | Update host-side classpath probes / config to the correct FQN |
| Chat hangs or returns garbled responses | `OPENROUTER_MODEL` slug points to a model the provider does not actually serve | Pick a known-good slug (e.g. `openai/gpt-4o-mini`); verify with the provider's model index |
| `RetryableException` storms during ingest | Embedding endpoint flaking or wrong base URL | Tune `jmix.ai-agent.rag.embed-retry.*`; verify `OPENROUTER_BASE_URL` reachable from the app host |

## See Also

- [`docs/consumer-smoke.md`](../docs/consumer-smoke.md) — manual clean-consumer smoke walkthrough (companion to the deferred `consumer-smoke/` subproject)
- [`docs/versions.md`](../docs/versions.md) — Jmix / Spring Boot / Spring AI version matrix
- `CHANGELOG.md` (created in plan 08-07 — repo root)

---

> **Verification footer (R-06a):** This README was last verified against commit `520f7098ac67226b6830c61b45a673ad9ee74f05` on `2026-04-26`. Re-run the verification (read each `@ConfigurationProperties` source + enumerate Liquibase changesets + confirm SPI signatures) when bumping versions or after any phase that changes shared types.
