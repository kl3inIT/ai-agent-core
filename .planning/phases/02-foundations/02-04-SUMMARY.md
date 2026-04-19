---
phase: 02-foundations
plan: 04
subsystem: persistence/liquibase
tags: [liquibase, ddl, schema, entity]
requires: [02-03]
provides: [ai-agent-schema]
affects:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/**
  - jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml
tech-stack:
  added: []
  patterns: [liquibase-xml-changelog, addon-master-include]
key-files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/010-ai-conversation.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/020-ai-message.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/030-ai-tool-call-audit.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/040-ai-parameters.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/050-ai-knowledge-document.xml
  modified:
    - jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml
decisions:
  - All add-on changesets carry author="ai-agent" (distinct from host's "jmix-app") to keep DATABASECHANGELOG provenance clean.
  - Master add-on changelog uses <includeAll> so future 06x+ changelogs (chat-memory, pgvector) auto-join without editing master.
  - Host explicitly <include>s add-on master (add-on changelogs are NOT auto-discovered — Pitfall #2 from RESEARCH).
metrics:
  completed: 2026-04-19
---

# Phase 02 Plan 04: Entity DDL Liquibase Changelogs Summary

Created the 5 AI_AGENT_* entity DDL changelogs and wired the host master changelog to include the add-on master so tables are created on boot.

## Scope

- `changelog.xml` (add-on master): `<includeAll path="/com/vn/agent/liquibase/changelog"/>`.
- `010-ai-conversation.xml`: `AI_AGENT_CONVERSATION` (ID, VERSION, TITLE, CREATED_BY not-null, CREATED_DATE, LAST_MODIFIED_BY, LAST_MODIFIED_DATE) + idx on `CREATED_BY`.
- `020-ai-message.xml`: `AI_AGENT_MESSAGE` with non-null FK `FK_AI_AGENT_MESSAGE__ON_CONVERSATION` → `AI_AGENT_CONVERSATION(ID)`; `CONTENT` as `clob`; `ROLE_` reserved-word-safe; idx on `CONVERSATION_ID`.
- `030-ai-tool-call-audit.xml`: `AI_AGENT_TOOL_CALL_AUDIT` with nullable FK to conversation, `OUTCOME`/`TOOL_NAME`/`STARTED_AT` non-null, `LATENCY_MS bigint`, clob `ARGUMENTS_JSON`/`RESULT_SUMMARY`; indexes on `CONVERSATION_ID` and `STARTED_AT`.
- `040-ai-parameters.xml`: `AI_AGENT_PARAMETERS` with `ACTIVE_` boolean default false; unique index on `PROFILE_NAME`.
- `050-ai-knowledge-document.xml`: `AI_AGENT_KNOWLEDGE_DOCUMENT` with `STATUS varchar(16) defaultValue="PENDING"` not-null, `FILE_NAME` not-null; indexes on `STATUS` and `CREATED_BY`.
- Host edit: inserted `<include file="/com/vn/agent/liquibase/changelog.xml"/>` between the three `io.jmix.*` includes and host `<includeAll>`; preserved DO-NOT-REMOVE comment.

## Verification

All column names cross-checked against entities in `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/` (AiConversation, AiMessage, AiToolCallAudit, AiParameters, AiKnowledgeDocument) — exact match incl. `ROLE_`, `ACTIVE_`, `CONVERSATION_ID`, `USER_USERNAME`. Liquibase XML header + schema location mirrors `jmix-app/.../020-customer.xml` and `040-order.xml`. FK naming follows `FK_<TABLE>__ON_<TARGET>` convention. Host insertion order verified: after `securitydata`, before host `<includeAll>`.

Gradle smoke-boot skipped per executor context (node/gradle not invoked); plan 10 will assert all 5 tables exist on fresh HSQLDB boot.

## Deviations from Plan

None — plan executed exactly as written.

## Commits

- 7cb7f26  feat(02-04): add entity DDL Liquibase changelogs + host include

## Self-Check: PASSED

All 6 created XML files present on disk; host changelog.xml contains the add-on include line; commit 7cb7f26 in `git log`.
