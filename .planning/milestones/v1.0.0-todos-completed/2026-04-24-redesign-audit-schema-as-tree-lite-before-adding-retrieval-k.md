---
created: 2026-04-24T09:48:54.530Z
title: Redesign audit schema as tree-lite (PARENT_ID) before adding retrieval kind
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditListenerDispatcher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditListener.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/030-ai-tool-call-audit.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/080-ai-tool-call-audit-runid.xml
---

## Problem

Current audit schema `AI_AGENT_TOOL_CALL_AUDIT` is a **flat table + `KIND` discriminator** (`CHAT`, `TOOL`), with events from the same chat turn grouped only by `RUN_ID`. The design has accumulated smells that will compound once we add retrieval audit:

- `CHAT_TOOL_NAME_SENTINEL = "<chat>"` hack for the NOT NULL `TOOL_NAME` column (see `AuditWriter.java:42`).
- `PHASE = PRE/POST` dual-row pattern — one chat turn = two `KIND=CHAT` rows, with turn-level fields (latency, errorClass) split across them. `writeChatPre` + `writeChatPost` must be reconstructed to reason about the turn.
- Entity name `AiToolCallAudit` misleads: it holds `CHAT` rows too, and will soon hold `RETRIEVAL`/`GUARDRAIL` rows.
- Parent-child hierarchy is implicit (shared `runId`) — UI rendering of "AI làm gì ở từng bước" needs manual grouping; Jmix fetch plan / `@Composition` cannot render the tree.
- Adding `KIND=RETRIEVAL` on top would multiply the debt: another `<retrieval>` sentinel, another PHASE dance, and another consumer of the legacy name.

Decided during /gsd-explore (2026-04-24): redesign the schema to **tree-lite** — single table with a self-referential `PARENT_ID` FK. Chat turn = one root row (`PARENT_ID = null`, `KIND = CHAT`), sub-events (`TOOL`, `RETRIEVAL`, future `GUARDRAIL`, `RATE_LIMIT`, ...) are children linked via `PARENT_ID`. Collapse PRE/POST into one row per event with `startedAt` + `finishedAt`.

Blocks todo `2026-04-24-audit-vector-store-retrieval-and-full-flow-like-jmix-ai-back.md` — retrieval audit will be built on the new schema, not by bolting `KIND=RETRIEVAL` onto the legacy flat table.

## Solution

Tree-lite (flavor A) chosen over:
- tree-by-grouping (runId only, no physical FK) — cheaper migration but keeps implicit relationship; Jmix fetch plan / list-view tree rendering is harder.
- Two-table split (`AiChatRun` + `AiAuditEvent`) — cleaner semantic but ~20 file blast radius; tree-lite achieves the same invariants by adding one column.

### Scope

1. **Liquibase changelog** (new file): add `PARENT_ID` column (UUID, nullable) + FK to same table + index `IDX_..._ON_PARENT_ID`. Decide table rename (`AI_AGENT_TOOL_CALL_AUDIT` → `AI_AGENT_AUDIT_EVENT`) within this changelog or follow-up.
2. **Entity rename** — `AiToolCallAudit` → `AiAgentAudit` (or `AiAuditEvent`). Relax or rename `TOOL_NAME` (NOT NULL) → `EVENT_NAME` (nullable). Add `@ManyToOne parent` + `@OneToMany(mappedBy="parent") children` with `@Composition` so Jmix list view renders the tree.
3. **AuditWriter rewrite**:
   - `writeChatStart(runId, user, conversationId, promptHash) → UUID parentId` — single root row, `kind=CHAT`, `startedAt=now`, `finishedAt=null`.
   - `writeChatFinish(parentId, latencyMs, errorClass)` — UPDATE same row to set `finishedAt`, `latencyMs`, `outcome`, `errorClass`. Append-only ledger vs mutable root — OPEN QUESTION; leaning toward mutable root for clean 1-row-per-turn semantics, but must be decided in planning.
   - `writeToolCall(parentId, ...)` — insert child with `PARENT_ID=parentId`.
   - `writeRetrieval(parentId, query, topK, hitCount, topScore, filters, latencyMs, outcome)` — NEW method. `RESULT_SUMMARY` = compact one-line text (format decided during planning, aligned with jmix-ai-backend reference).
4. **`AuditListener` SPI** — generalize from `dispatchToolCallAudited(UUID auditId)` to `dispatchEventAudited(UUID auditId, String kind)` or add per-kind methods. This is a **breaking change** — document in ADR/memory. Check first whether any external consumer exists.
5. **List view `/ai-agent/audit`** — render tree (parent row with expandable children showing `stepOrder` via `startedAt`). Use Jmix `@Composition` fetch plan; avoid flat list + manual grouping.
6. **Data migration plan** — verify with stakeholder whether existing audit data must be preserved:
   - If ephemeral (dev / pre-prod): hard cutover — drop + recreate.
   - If must preserve: backfill — group by `runId`, synthesize single root from existing `CHAT PRE+POST` pair (merge `startedAt` from PRE + `finishedAt`/`latency`/`errorClass` from POST), link all same-`runId` `TOOL` rows via `PARENT_ID`.
7. **Tests** — update `AuditWriterFieldMappingTest`, `AuditDurabilityTest`, `AuditListenerDispatcherTest`. Add: tree-traversal test (parent + N children readable via `@Composition`); retrieval audit round-trip.

### Open questions for planning phase

- Append-only ledger vs mutable CHAT root row (UPDATE on finish)? Event-log purism says append-only; operational queries say mutable is simpler. Leaning mutable.
- jmix-ai-backend reference shape for audit tree — align structure or diverge deliberately? Confirm via RESEARCH.md during plan phase.
- `AuditListener` SPI breaking change — audit external consumers (if any). If solo codebase, breaking is cheap; if SPI is advertised, need deprecation path.
- Rename timing: do table/entity rename in the same phase as tree introduction, or split into two phases (add PARENT_ID first, rename later)? Bundling reduces review cycles; splitting reduces risk.

### Dependencies / Ordering

- **BLOCKS** `2026-04-24-audit-vector-store-retrieval-and-full-flow-like-jmix-ai-back.md` — retrieval audit implementation depends on the new schema. Update that todo once this one lands (or link them explicitly when planning phase order).
- Does NOT block the broader RAG security audit (`SystemAuthenticator` coverage, role filter fail-closed, URI allowlist, embedding-model drift) — those can proceed independently on the existing schema.
