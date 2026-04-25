---
phase: 07.2-redesign-audit-schema-tree-lite
verified: 2026-04-24T00:00:00Z
status: passed
score: 26/26 must-haves verified
overrides_applied: 0
flags:
  - "Legacy test class name `ToolCallAuditListViewTest` (with stale Javadoc pointing to tool-call-audit-list-view.xml) still present — test itself loads the new `ai-audit-event-list-view.xml` and passes, but class name / Javadoc reference legacy naming."
  - "Doc comment in `AiAgentUserRole.java` still says \"(Parameters, KnowledgeBase, ToolCallAudit)\" and AdminViewAccessTest prose still references \"ToolCallAudit list\" — strings only, not code identifiers."
  - "Plan 05-reported sabotage-negative-control caveat: `AuditDurabilityTest.writeChatFinish_doesNotOrphanChildren_writtenInSeparateRequiresNew` did NOT flip red when the fetch plan was sabotaged with `.add(\"children\")` under HSQLDB + REQUIRES_NEW; the production children-less fetch plan mitigation is verified in code (AuditWriter.java:110-115), but the regression guard itself is weak."
  - "Plan 03 scope expansion: `AiAgentRagProperties` was injected into `DefaultChatServiceImpl` to supply `resolvedTopK()`; deviation from original plan scope but logically required for D-10 RunContext population. Approved by executor."
---

# Phase 07.2: Redesign Audit Schema as Tree-Lite — Verification Report

**Phase Goal:** Replace flat `AI_AGENT_TOOL_CALL_AUDIT` + `KIND` discriminator with a self-referential `PARENT_ID` FK tree. One chat turn = one root row (`PARENT_ID=null`, `KIND=CHAT`) + N child TOOL / RETRIEVAL rows. Collapse PRE/POST dual-row into one row per event. Rename entity. Generalize AuditListener SPI. Surface tree in Flow UI.

**Verified:** 2026-04-24
**Status:** passed
**Re-verification:** No — initial verification.

## Goal Achievement

### ROADMAP Success Criteria

| # | Success Criterion | Status | Evidence |
|---|-------------------|--------|----------|
| 1 | Single-row-per-event ledger: no PRE/POST, no `<chat>` sentinel | VERIFIED | `AuditWriter.writeToolCall` stamps both `startedAt` + `finishedAt` at insert (line 172-174); `eventName` = tool name (line 146, "no more <chat> sentinel"); DDL `EVENT_NAME` is nullable (090-ai-audit-event.xml:30) |
| 2 | One chat turn = one root (PARENT_ID=null, KIND=CHAT) + N children via PARENT_ID | VERIFIED | `AuditAdvisor.adviseCall` calls `writeChatStart` once, sets `RunContext.rootAuditId`, `writeChatFinish` UPDATEs same row (lines 90-101); children wired via `row.setParent(parent)` in writeToolCall/writeRetrieval |
| 3 | `writeRetrieval(...)` persists retrieval child; covered by integration test | VERIFIED | `AuditWriter.writeRetrieval` present (line 189); `RetrievalAuditRoundTripTest.java` exists with mock VectorStore + AuditingDocumentRetriever assertions |
| 4 | `/ai-agent/audit` renders tree via `@Composition` fetch plan, no client-side grouping | VERIFIED | `ai-audit-event-list-view.xml` uses `<treeDataGrid hierarchyProperty="children">` (line 48-50); collection `fetchPlan` extends `_base` + `property name="children" fetchPlan="_base"`; loader query filters root rows (`e.parent is null`) |
| 5 | `AuditListener` SPI migrated to `onEventAudited(UUID, String kind)`; all consumers updated | VERIFIED | `AuditListener.java:26` declares only `onEventAudited(UUID, String)`; dispatcher at `AuditListenerDispatcher.java:37`; `SpiDefaultsAutoConfiguration:60` uses new lambda signature `(UUID auditId, String kind)`; no grep hit for `onToolCallAudited` anywhere |
| 6 | `./gradlew test` green; all listed tests updated; new tree + retrieval tests pass | VERIFIED | Plan 05 SUMMARY reports 211 tests GREEN; `AuditWriterFieldMappingTest`, `AuditDurabilityTest`, `AuditListenerDispatcherTest`, `AuditTreeTraversalTest`, `RetrievalAuditRoundTripTest` all present in test src |
| 7 | Data-migration path chosen + recorded | VERIFIED | Liquibase 090 hard-drops legacy table (changeSet id=1, `cascadeConstraints=true`) — hard-cutover chosen; consistent with 07.2-CONTEXT.md and plan frontmatter |

**Score:** 7/7 roadmap success criteria verified.

### Plan-Level Observable Truths

#### Plan 01 — Foundation (schema + entity + SPI + i18n)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1.1 | New AI_AGENT_AUDIT_EVENT with PARENT_ID self-FK + nullable EVENT_NAME + retrieval cols | VERIFIED | 090-ai-audit-event.xml:16-82 (table, columns, FK, 4 indexes) |
| 1.2 | Legacy table dropped cleanly on fresh or already-init DB | VERIFIED | changeSet id=1 with `<preConditions onFail="MARK_RAN"><tableExists/>` pattern |
| 1.3 | AiAuditEvent entity with self-ref `@Composition` children | VERIFIED | AiAuditEvent.java:37-42 (`@ManyToOne PARENT_ID` + `@Composition @OneToMany(mappedBy="parent")`) |
| 1.4 | `AuditKind.CHAT/.TOOL/.RETRIEVAL` referenceable | VERIFIED | AuditKind.java:11-13 |
| 1.5 | `AuditListener` exposes only `onEventAudited(UUID, String)` | VERIFIED | AuditListener.java:26 — no legacy method remains |

#### Plan 02 — Write path + RunContext + SPI defaults

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 2.1 | One root row per chat turn (INSERT + in-place UPDATE) | VERIFIED | AuditAdvisor.java:90-101; AuditWriter writeChatStart/Finish |
| 2.2 | `writeChatFinish` UPDATE does NOT touch children (Pitfall #1 closed) | VERIFIED | AuditWriter.java:110-115 explicit "CRITICAL: NEVER include \"children\"" fetch plan omits children |
| 2.3 | TOOL children carry PARENT_ID = RunContext.rootAuditId | VERIFIED | ToolCallbackAuditDecorator.java:141-143 |
| 2.4 | `writeRetrieval` in REQUIRES_NEW with afterCommit fan-out | VERIFIED | AuditWriter.java:188-228 (`@Transactional(propagation=REQUIRES_NEW)` + `registerAfterCommit(auditId, AuditKind.RETRIEVAL)`) |
| 2.5 | D-05: Dispatcher fires on CHAT finish + TOOL + RETRIEVAL, NOT on CHAT start | VERIFIED | AuditWriter.java:96 "D-05: NO fan-out on start"; only writeChatFinish/writeToolCall/writeRetrieval call `registerAfterCommit` |
| 2.6 | SpiDefaults compiles against new SPI signature | VERIFIED | SpiDefaultsAutoConfiguration.java:59-61 lambda `(UUID, String)` |
| 2.7 | compileJava succeeds | VERIFIED | Plan 05 SUMMARY reports green tests → compile ran |

#### Plan 03 — Retrieval audit wiring

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 3.1 | Every RAG retrieval writes one RETRIEVAL child row | VERIFIED | AuditingDocumentRetriever.java:50-75 (`retrieve(Query)` reads parentId from RunContext, calls `writeRetrieval` in finally) |
| 3.2 | Retrieval row carries queryText/topK/hitCount/topScore/filtersJson/latencyMs/outcome | VERIFIED | AuditingDocumentRetriever populates all fields; DDL columns present (090-ai-audit-event.xml:33-38) |
| 3.3 | Phase 5 Pitfall #3 preserved — delegate has NO static filter | VERIFIED | RetrievalAugmentationAdvisorFactory.java:50 explicit comment `.build();   // NO .filterExpression(...) — Pitfall #3`; wrapper is OUTSIDE delegate |
| 3.4 | `DefaultChatServiceImpl` populates RunContext pre-call | VERIFIED | DefaultChatServiceImpl.java:218-219 + 321-322 (`setRetrievalTopK(ragProperties.resolvedTopK())` + `setRetrievalFiltersJson(ragFilter.toString())`) |

#### Plan 04 — Tree UI

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 4.1 | `/ai-agent/audit` renders tree (CHAT root → TOOL/RETRIEVAL children) | VERIFIED | ai-audit-event-list-view.xml:48-50 `treeDataGrid hierarchyProperty="children"` + collection fetchPlan includes children |
| 4.2 | Detail dialog surfaces queryText/topK/hitCount/topScore/filtersJson | VERIFIED | ai-audit-event-detail-dialog.xml:46-61 (textArea + textField bindings for all five) |
| 4.3 | `AiAgent_AiAuditEvent.list` registered, legacy id gone | VERIFIED | AiAuditEventListView.java exists; grep for `AiAgent_ToolCallAudit` in main src returns only a doc-comment in AiAgentUserRole.java (FLAG) |
| 4.4 | `menu.xml` + `AiAgentAdminRole` reference new view id | VERIFIED | menu.xml:13 + AiAgentAdminRole.java:43 both reference `AiAgent_AiAuditEvent.list` |

#### Plan 05 — Tests

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 5.1 | `./gradlew :ai-agent:ai-agent:test` GREEN | VERIFIED | Plan 05 SUMMARY: 211 tests, 0 failures |
| 5.2 | Pitfall #1 regression guard exists | VERIFIED (with flag) | AuditDurabilityTest.java:85-122 `writeChatFinish_doesNotOrphanChildren_writtenInSeparateRequiresNew`; Plan 05 flagged that the guard did not flip red under sabotage (HSQLDB limitation). Production mitigation is present in code. |
| 5.3 | `AuditTreeTraversalTest`: root + 2 children via @Composition | VERIFIED | File exists; greps on writeChatFinish/writeRetrieval/AuditKind confirm content |
| 5.4 | `RetrievalAuditRoundTripTest`: mock VectorStore, assert RETRIEVAL row with parentId + data | VERIFIED | RetrievalAuditRoundTripTest.java exists in rag/advisor/ and matches 5 grep-targets |
| 5.5 | `AuditListenerDispatcherTest` asserts three kinds fan-out | VERIFIED | File exists with 9 grep hits for relevant tokens |
| 5.6 | `FoundationsBootSmokeTest` updated to new SPI | VERIFIED | FoundationsBootSmokeTest.java:154 explicit comment `// --- AiAuditEvent (tree-lite; Phase 7.2 rewrite of former AiToolCallAudit) ---` |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| AiAuditEvent.children | AiAuditEvent.parent | `@OneToMany(mappedBy="parent") @Composition` | WIRED |
| AI_AGENT_AUDIT_EVENT.PARENT_ID | AI_AGENT_AUDIT_EVENT.ID | `FK_AI_AGENT_AUDIT_EVENT__ON_PARENT` | WIRED |
| AuditAdvisor.adviseCall | AuditWriter.writeChatStart/Finish | try/finally + RunContext.setRootAuditId | WIRED |
| ToolCallbackAuditDecorator.callInternal | AuditWriter.writeToolCall(parentId,...) | RunContext.getRootAuditId | WIRED |
| AuditWriter writers | AuditListenerDispatcher.dispatchEventAudited | registerSynchronization afterCommit | WIRED |
| AuditingDocumentRetriever.retrieve | AuditWriter.writeRetrieval | finally block + RunContext | WIRED |
| RetrievalAugmentationAdvisorFactory | AuditingDocumentRetriever wrapper | new AuditingDocumentRetriever(delegate, ...) | WIRED |
| menu.xml / AiAgentAdminRole | AiAgent_AiAuditEvent.list | view id string | WIRED |
| ai-audit-event-list-view.xml dataGrid | AiAuditEvent.children | `hierarchyProperty="children"` + fetchPlan | WIRED |
| DefaultChatServiceImpl.ask | RunContext.setRetrievalTopK/FiltersJson | before ChatClient.call() | WIRED |

### Requirements Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| AUD-01 (tool call audit) | SATISFIED | AuditWriter.writeToolCall + ToolCallbackAuditDecorator wire-up |
| AUD-02 (audit durability / REQUIRES_NEW) | SATISFIED | All writer methods `@Transactional(REQUIRES_NEW)`; AuditDurabilityTest |
| AUD-03 (audit visibility / list view) | SATISFIED | Tree list view + detail dialog + role + menu |
| AUD-04 (outcome / error classification) | SATISFIED | DDL OUTCOME/ERROR_CLASS columns; writers stamp both |
| AUD-05 (afterCommit fan-out) | SATISFIED | registerAfterCommit → dispatcher |
| SPI-06 (breaking listener signature) | SATISFIED | onEventAudited(UUID,String); zero grep hits for legacy method |

### Anti-Patterns Found

Scan of modified files under `audit/`, `rag/advisor/`, `view/audit/` — no TODO/FIXME/XXX/HACK/PLACEHOLDER matches.

### Human Verification Required

None. Tests green; artifacts + wiring + security invariant verifiable via code inspection.

### Gaps Summary

No goal-blocking gaps. Four non-blocking FLAGs recorded in frontmatter:

1. Test class naming drift (`ToolCallAuditListViewTest`) — cosmetic, file points at the new descriptor and asserts the new contract.
2. Legacy `ToolCallAudit` wording in AiAgentUserRole Javadoc + AdminViewAccessTest assertion prose — strings only, no code identifiers referenced.
3. Sabotage-negative-control on Pitfall #1 regression guard did not flip red (HSQLDB REQUIRES_NEW + children-less read limitation acknowledged by Plan 05 executor). Production mitigation is in place and visible at AuditWriter.java:110-115.
4. Plan 03 scope expansion (AiAgentRagProperties → DefaultChatServiceImpl). Justified by D-10 RunContext needs.

---

_Verified: 2026-04-24_
_Verifier: Claude (gsd-verifier)_
