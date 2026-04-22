---
slug: vector-store-relation-missing
status: resolved
trigger: |
  <!-- DATA_START -->
  Caused by: org.postgresql.util.PSQLException: ERROR: relation "public.vector_store" does not exist
  Position: 45
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T15:50:00Z
---

# Debug Session: vector-store-relation-missing

## Symptoms

- **Expected:** VectorStore queries hit `AI_AGENT_KB_VECTOR_STORE` (Liquibase-owned in `070-ai-kb-vector-store.xml`).
- **Actual:** Queries hit `public.vector_store` (Spring AI default), which Liquibase never created.
- **Error:** `ERROR: relation "public.vector_store" does not exist`.
- **Reproduction:** Any VectorStore call (RAG retrieval / ingestion) against the real PostgreSQL datasource.

## Current Focus

reasoning_checkpoint:
  hypothesis: "Spring AI's `PgVectorStoreAutoConfiguration.vectorStore(...)` registers its `@ConditionalOnMissingBean` PgVectorStore BEFORE `com.vn.autoconfigure.agent.AIAutoConfiguration.aiAgentVectorStore(...)` — so ours is skipped, and the auto-configured one uses the default table `public.vector_store` which Liquibase never created (we created `AI_AGENT_KB_VECTOR_STORE`)."
  confirming_evidence:
    - "Liquibase `070-ai-kb-vector-store.xml` creates `AI_AGENT_KB_VECTOR_STORE` (NOT `vector_store`)."
    - "`AIAutoConfiguration.aiAgentVectorStore` sets `.vectorTableName(\"AI_AGENT_KB_VECTOR_STORE\")` but is gated by `@ConditionalOnMissingBean` and has no ordering vs `PgVectorStoreAutoConfiguration`."
    - "Decompiled `PgVectorStoreAutoConfiguration` in spring-ai 1.1.4 has `@Bean @ConditionalOnMissingBean` on its own `vectorStore(...)` method — first-registered wins; ours loses."
    - "`PgVectorStoreProperties` default `tableName` / `schemaName` yield `public.vector_store`, matching the error string exactly."
    - "Host `application.properties` does NOT set `spring.ai.vectorstore.pgvector.table-name` — so Spring AI's bean uses the default."
  falsification_test: "If Spring AI's autoconfig were NOT winning, the error relation name would be `ai_agent_kb_vector_store` (or quoted uppercase) — but it's the pgvector default `vector_store`. That alone confirms which bean got registered."
  fix_rationale: "Force our autoconfig to register its VectorStore first by adding `@AutoConfigureBefore(PgVectorStoreAutoConfiguration.class)`. Then Spring AI's `@ConditionalOnMissingBean` skips its own, and the correct table `AI_AGENT_KB_VECTOR_STORE` is used. This preserves the add-on's single-owner ownership of pgvector DDL described in the existing Javadoc and keeps host override via `@ConditionalOnMissingBean` intact."
  blind_spots: "Have not runtime-verified ordering empirically with this exact classpath; relying on Spring Boot contract that `@AutoConfigureBefore` forces ours to run first. A companion change (adding `PgVectorStoreAutoConfiguration` explicit reference) requires its class to be on the classpath — confirmed via api-scope pgvector starter in ai-agent.gradle."

## Evidence

- 2026-04-22 — Liquibase creates `AI_AGENT_KB_VECTOR_STORE`, not `vector_store` (070-ai-kb-vector-store.xml, changeset id=2).
- 2026-04-22 — `AIAutoConfiguration.aiAgentVectorStore` uses correct table name + `initializeSchema(false)` + `@ConditionalOnMissingBean`, class-level `@AutoConfiguration(after = OpenAiEmbeddingAutoConfiguration.class)` — no ordering vs PgVectorStoreAutoConfiguration.
- 2026-04-22 — Decompiled `spring-ai-autoconfigure-vector-store-pgvector-1.1.4`: class has `@AutoConfiguration(after=JdbcTemplateAutoConfiguration.class)` and its `vectorStore(...)` @Bean carries `@ConditionalOnMissingBean`. Properties class defaults to `tableName=vector_store`, `schemaName=public`.
- 2026-04-22 — Error `public.vector_store` matches pgvector defaults exactly → Spring AI's bean is the active VectorStore.
- 2026-04-22 — Host `application.properties` sets `initialize-schema=false` but leaves `table-name`/`schema-name` at default → confirms the active bean is Spring AI's default.
- 2026-04-22 — User revalidated after full clean/rebuild/publish (`ai-agent` + `ai-agent-starter`) and clean `jmix-app`; boot log shows successful startup and PgVectorStore targeting `AI_AGENT_KB_VECTOR_STORE` with no metadata/ClassNotFound warnings.

## Eliminated

- hypothesis: "Liquibase changelog missing / not included" — eliminated: `070-ai-kb-vector-store.xml` is present and `<include>`d in `changelog.xml`, and the error uses a different table name anyway.
- hypothesis: "Wrong schema / search_path" — eliminated: error references `public.vector_store` which is the pgvector default, not a search_path mismatch against `ai_agent_kb_vector_store`.

## Resolution

**Root cause:** Runtime used mixed/stale artifacts across composite modules, so the effective boot classpath did not consistently reflect the intended add-on autoconfiguration/enhancement outputs; this left Spring AI default pgvector wiring active (`public.vector_store`) and produced intermittent metadata-loader warnings in the affected runs.

**Fix:** Rebuild and republish both add-on modules, then clean and restart host app (`:ai-agent:ai-agent`, `:ai-agent:ai-agent-starter`, `publishToMavenLocal`, `:jmix-app:clean`, `:jmix-app:bootRun`) so runtime picks a consistent artifact set. Verification confirms PgVectorStore points to `AI_AGENT_KB_VECTOR_STORE` and startup warnings/errors are gone.

**Verification plan:**
1. Completed — full clean/rebuild/publish cycle executed for both add-on modules.
2. Completed — fresh host boot reached `Started JmixAppApplication`.
3. Completed — no `ClassNotFoundException`, `MetaClass not found`, or `not loaded into metadata`; PgVectorStore log shows `AI_AGENT_KB_VECTOR_STORE`.
