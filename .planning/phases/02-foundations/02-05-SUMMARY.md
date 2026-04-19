---
phase: 02-foundations
plan: 05
subsystem: chat-memory-schema
tags: [liquibase, spring-ai, chat-memory, ddl, ENT-03]
requires: [02-04]
provides: [SPRING_AI_CHAT_MEMORY-table, chat-memory-ddl-ownership]
affects: [phase-04-chat-service, phase-10-boot-verification]
tech-stack:
  added: []
  patterns:
    - "Liquibase changesets gated by dbms attribute for Postgres/HSQLDB variants"
    - "Add-on Liquibase owns Spring-AI-framework tables (initialize-schema=never)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/060-ai-chat-memory.xml
  modified:
    - jmix-app/src/main/resources/application.properties
decisions:
  - "SPRING_AI_CHAT_MEMORY literal table name kept (hard-coded in JdbcChatMemoryRepository; documented ENT-02 exception)"
  - "Set pgvector initialize-schema=false now together with chat-memory to avoid second edit in plan 06"
metrics:
  duration_minutes: 5
  tasks_completed: 2
  files_changed: 2
  completed_date: 2026-04-19
---

# Phase 2 Plan 05: Spring AI Chat-Memory DDL Summary

One-liner: Shipped Spring AI 1.1.4 `SPRING_AI_CHAT_MEMORY` DDL as an add-on Liquibase changelog with separate Postgres/HSQLDB changesets, and disabled Spring AI's `initialize-schema` so Liquibase is the sole DDL owner.

## What Was Built

1. `060-ai-chat-memory.xml` — two changesets (author=`ai-agent`):
   - `dbms="postgresql"`: `CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY` with `TEXT` content, quoted `"timestamp"`, inline CHECK on `type` (USER/ASSISTANT/SYSTEM/TOOL), plus `IF NOT EXISTS` index on `(conversation_id, "timestamp")`.
   - `dbms="hsqldb"`: `CREATE TABLE` with `LONGVARCHAR` content, unquoted `timestamp DEFAULT CURRENT_TIMESTAMP`, separate ALTER adding `TYPE_CHECK` constraint, plus index `(conversation_id, timestamp DESC)`.
   - Picked up automatically by `changelog.xml` `<includeAll path="/com/vn/agent/liquibase/changelog"/>` from plan 02-04.
2. `application.properties` — appended Phase 2 Spring AI block:
   - `spring.ai.chat.memory.repository.jdbc.initialize-schema=never`
   - `spring.ai.vectorstore.pgvector.initialize-schema=false` (defensive; pgvector DDL lands in plan 02-06)

## Key Links Validated

- CHECK literals `USER/ASSISTANT/SYSTEM/TOOL` match `AiMessageRole` enum ids in `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessageRole.java` (all four values, exact case).

## Deviations from Plan

None — plan executed exactly as written. XML and properties are verbatim per plan action blocks (which are themselves verbatim from 02-RESEARCH §060-ai-chat-memory.xml).

## Verification

- File exists at expected path; contains `SPRING_AI_CHAT_MEMORY`, both `dbms="postgresql"` and `dbms="hsqldb"` changesets, `LONGVARCHAR NOT NULL`, `type IN ('USER','ASSISTANT','SYSTEM','TOOL')`, and `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX`.
- `application.properties` retains `jmix.core.available-locales=vi,en` and all prior keys; new properties appended after `logging.level.org.atmosphere=warn`.
- Gradle verification deliberately skipped per orchestrator instructions (Plan 10 will boot-verify the table on HSQLDB).

## Commits

- `0a991ac` — feat(02-05): add Spring AI chat-memory DDL changelog + disable auto-init

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/060-ai-chat-memory.xml
- FOUND: jmix-app/src/main/resources/application.properties (modified)
- FOUND commit: 0a991ac
