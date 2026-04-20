---
phase: 04-orchestration-core
plan: 01
subsystem: audit
tags: [liquibase, entity, audit, schema-migration]
dependency-graph:
  requires:
    - Phase 2 changelog 030 (AI_AGENT_TOOL_CALL_AUDIT table)
    - Phase 2 entity AiToolCallAudit
  provides:
    - RUN_ID / KIND / PHASE / PROMPT_HASH / ERROR_CLASS columns on AI_AGENT_TOOL_CALL_AUDIT
    - Matching entity fields + accessors on AiToolCallAudit
    - IDX_AI_AGENT_TOOL_CALL_AUDIT__ON_RUN_ID
  affects:
    - Plan 04-03 (AuditWriter / AuditAdvisor / ToolCallbackAuditDecorator — reads/writes these columns)
    - Plan 04-05 (integration tests that assert pre/post audit rows correlate by runId)
tech-stack:
  added: []
  patterns:
    - Additive Liquibase migration (no edits to Phase 2 changelog 030)
    - Liquibase ${uuid.type} property token (postgres uuid / hsqldb varchar(36) / mssql uniqueidentifier)
    - Entity field kind/phase as plain String (no enum class — AuditWriter writes literal CHAT/TOOL and PRE/POST)
key-files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/080-ai-tool-call-audit-runid.xml
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - String kind/phase over a dedicated enum class — keeps the entity dependency-light; AuditWriter is the single writer and writes literal strings
  - All new columns nullable at the DB level; runtime invariants (kind/phase always populated) enforced by AuditWriter, not DB constraint, because existing rows (none in production — table was empty pre-Phase 4) avoid a backfill step
metrics:
  duration_minutes: ~5
  tasks_completed: 2
  files_created: 1
  files_modified: 4
  commits: 2
  completed_date: 2026-04-20
---

# Phase 04 Plan 01: AiToolCallAudit runId + kind/phase discriminators Summary

Additive Liquibase 080 migration plus entity + i18n updates that add the five D-12 columns (`runId`, `kind`, `phase`, `promptHash`, `errorClass`) required by the Phase 4 audit pipeline; Phase 2 changelog 030 is unchanged.

## Objective recap

Phase 2 shipped `AiToolCallAudit` without the columns that thread a single `AskService.ask()` invocation across pre/post chat rows and per-tool rows. Plan 04-03 (`AuditWriter`, `AuditAdvisor`, `ToolCallbackAuditDecorator`) cannot land without them. Per PATTERNS.md this had to be its own schema plan so the writer plan is never tangled with DDL.

## Work completed

### Task 1 — Liquibase changelog 080 + master include (commit `8181ff4`)

Created `080-ai-tool-call-audit-runid.xml` with two changeSets:

1. `addColumn` on `AI_AGENT_TOOL_CALL_AUDIT`:
   - `RUN_ID` (`${uuid.type}`) — correlation ID across a single ask() invocation
   - `KIND` (`varchar(8)`) — `CHAT` | `TOOL` discriminator
   - `PHASE` (`varchar(8)`) — `PRE` | `POST` discriminator
   - `PROMPT_HASH` (`varchar(64)`) — SHA-256 hex of user message (set on chat-pre row)
   - `ERROR_CLASS` (`varchar(255)`) — simple class name of thrown Throwable
2. `createIndex` `IDX_AI_AGENT_TOOL_CALL_AUDIT__ON_RUN_ID` to keep run-correlation lookups fast.

Registered in master `changelog.xml` immediately after the existing 070 include; no edits to 010–070.

### Task 2 — Entity fields, accessors, i18n (commit `1789b7e`)

- Added five private fields on `AiToolCallAudit` with `@Column` name/length matching changelog 080.
- Hand-written getters/setters (no Lombok per CLAUDE.md entity rule); `getOutcome()`/`setOutcome(AiToolCallOutcome)` enum-coercion accessors and `@InstanceName getDisplayName()` left untouched.
- Five new message keys in both `messages.properties` (EN) and `messages_vi.properties` (VI).
- `./gradlew :ai-agent:ai-agent:compileJava -q` passes cleanly.

## Deviations from Plan

None — plan executed exactly as written. Every acceptance criterion matched on first pass (compileJava green, all grep checks green).

## Verification

- `grep "include file=" changelog.xml` → 8 real includes (plus 1 occurrence inside a comment, unchanged from Phase 2).
- `grep "RUN_ID|PROMPT_HASH|IDX_AI_AGENT_TOOL_CALL_AUDIT__ON_RUN_ID" 080-ai-tool-call-audit-runid.xml` → all present.
- Entity grep for `private UUID runId;`, `public UUID getRunId`, `private String promptHash;` → all green.
- i18n grep for `AiToolCallAudit.runId=Run ID` (EN) and `AiToolCallAudit.runId=Mã lượt chạy` (VI) → both green.
- `./gradlew :ai-agent:ai-agent:compileJava -q` → exit 0.
- Changelog 030 untouched (no edits made to the Phase 2 file).

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/080-ai-tool-call-audit-runid.xml
- FOUND: commit 8181ff4 (liquibase 080 + master changelog include)
- FOUND: commit 1789b7e (entity fields + i18n)
- FOUND: compileJava exit 0
