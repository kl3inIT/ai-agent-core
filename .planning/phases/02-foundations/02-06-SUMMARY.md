---
phase: 02-foundations
plan: 06
subsystem: persistence
tags: [liquibase, pgvector, postgresql, ddl, spring-ai]
requires: [02-04]
provides: [AI_AGENT_KB_VECTOR_STORE table, pgvector extension install]
affects: [ENT-04]
tech_stack:
  added: [pgvector, hstore, uuid-ossp]
  patterns: [postgres-gated-changeset, belt-and-suspenders-preCondition]
key_files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/070-ai-kb-vector-store.xml
  modified: []
decisions:
  - "Use dbms attribute + preConditions onFail=MARK_RAN on BOTH changesets (per Pitfall #3)"
  - "Keep OpenAI default 1536 vector dimension; hosts override for other models"
  - "Install uuid-ossp (not pgcrypto) to match Spring AI reference schema using uuid_generate_v4()"
  - "HNSW index with vector_cosine_ops for fast approximate NN search"
metrics:
  duration: "~3m"
  completed: "2026-04-19"
  tasks: 1
  files: 1
---

# Phase 02 Plan 06: pgvector DDL Changelog Summary

One-liner: Shipped `070-ai-kb-vector-store.xml` with pgvector extension install + `AI_AGENT_KB_VECTOR_STORE(id uuid, content text, metadata json, embedding vector(1536))` + HNSW cosine index, fully gated to PostgreSQL so HSQLDB test boots skip it cleanly.

## What Shipped

- **File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/070-ai-kb-vector-store.xml`
- **changeSet id=1 (author=ai-agent, dbms=postgresql):** `CREATE EXTENSION IF NOT EXISTS` for `vector`, `hstore`, `uuid-ossp`
- **changeSet id=2 (author=ai-agent, dbms=postgresql):** `AI_AGENT_KB_VECTOR_STORE` table matching Spring AI PgVectorStore reference schema exactly + `IDX_AI_AGENT_KB_VECTOR_STORE__EMBEDDING` HNSW index using `vector_cosine_ops`
- **Gating:** Each changeset has `dbms="postgresql"` attribute AND a `<preConditions onFail="MARK_RAN">` wrapping `<dbms type="postgresql"/>`. HSQLDB boots mark both RAN without executing any SQL.
- **Master changelog:** Root uses `<includeAll>`, so `070-...` auto-joins the sequence in numeric order after `060-ai-chat-memory.xml`. No edit to `changelog.xml` required.

## Why

ENT-04 mandates the pgvector schema ship in Phase 2 so Phase 5 `PgVectorStore.builder().vectorTableName("ai_agent_kb_vector_store").initializeSchema(false)` finds the table at runtime. The table shape (id uuid / content text / metadata json / embedding vector(1536)) matches Spring AI's `pgvector.adoc` reference exactly to guarantee binding compatibility.

## Deviations from Plan

None — plan executed exactly as written.

## Threat Flags

None — new surface already enumerated in plan's `<threat_model>` (T-02-VEC-01..04).

## Verification

- File exists with both changesets marked `dbms="postgresql"`
- Both changesets wrap a `<preConditions onFail="MARK_RAN">` with `<dbms type="postgresql"/>` (belt-and-suspenders per Pitfall #3)
- All three required extensions present (`vector`, `hstore`, `uuid-ossp`)
- Table `AI_AGENT_KB_VECTOR_STORE` with `embedding vector(1536)` column and HNSW `vector_cosine_ops` index
- Plan 10 smoke test (HSQLDB boot) will confirm changesets are MARK_RAN and table absent
- Plan 09 (postgres manual verification) will confirm table + extension present on real Postgres

## Self-Check: PASSED

- File present: `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/070-ai-kb-vector-store.xml`
- Commit: `82c20a3` (`feat(02-06): add pgvector DDL changelog (postgres-gated)`)
