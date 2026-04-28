---
phase: 10-ai-specific-llm-exposure-policy
verified: 2026-04-28T03:00:00Z
status: human_needed
score: 4/4 ROADMAP success criteria verified; 12/12 requirement IDs satisfied
overrides_applied: 0
human_verification:
  - test: "Triage REVIEW BLOCKER-01 (AsyncIngestionWorker.enrich NPE on null role element)"
    expected: "Decide whether to fix in Phase 10 closure or accept as known bug — corner case (null elements in JSON-deserialized allowedRoles list); does not affect TEST-09 paths or any must-have, but can silently fail ingestion of documents whose allowedRolesJson contains nulls."
    why_human: "Real correctness bug surfaced in code review. Not blocked by automated phase gates (must_haves are structurally satisfied; integration/unit tests pass green). Decision: fix-now vs. defer-to-hardening backlog."
  - test: "Triage REVIEW BLOCKER-02 (Liquibase index name vs. JPA @Index name mismatch on AiExposureRule)"
    expected: "Decide whether to rename Liquibase changelog 060 to use IDX_AI_EXPOSURE_RULE_ENTITY_NAME (matching JPA @Index) or remove unique=true from JPA annotation — current state passes hsqldb test migrations cleanly but may produce duplicate-constraint errors under JPA schema validation in environments where hibernate.hbm2ddl.auto runs alongside Liquibase."
    why_human: "DDL parity issue. Hsqldb integration tests run green so the must_have 'Liquibase runs cleanly' is satisfied. Real risk on production environments using PostgreSQL with stricter validation."
  - test: "Triage REVIEW WARNING-08 (KB upload form lacks allowedRoles field — every upload is admin-only-visible by default)"
    expected: "Decide whether to add CheckboxGroup<String> allowedRoles to KnowledgeBaseView upload form for D-07 compliance — current upload passes Collections.emptyList(); per the fail-closed retrieval contract, every uploaded document is invisible to non-admin users until edited."
    why_human: "CONTEXT D-07 specified 'KB upload UX collects allowedRoles + new optional sourceEntityName BEFORE ingestion starts'. Plan 10-08 must_have wording ('upload service overload accepting allowedRoles + sourceEntityName') is technically satisfied at the service layer, but the UI bypasses it. Functional UX gap that admins will hit on first use."
  - test: "Triage REVIEW WARNING-01 (updatePermissionsAndReingest leaves stale chunks visible on partial failure)"
    expected: "Decide whether to harden the transaction boundary — current code can leave new sourceEntityName/allowedRolesJson committed on the document while old chunks remain in pgvector if vectorStore.delete succeeds but markPending fails."
    why_human: "Partial-failure window can defeat the EXP-05 NIN filter for affected documents until manual reingest. Edge case but real."
  - test: "Visually verify AiExposureRuleListView and AiExposureRuleDetailView render correctly with seeded data"
    expected: "Admin can navigate to AI menu → Exposure Rules; create rule via dropdown; toggle Hide from AI / Visible to AI; see rule appear in genericFilter results; rule takes effect on next chat turn."
    why_human: "UI visual quality and admin workflow ergonomics — must_haves cover structural correctness but Vaadin Flow UI rendering, dropdown population, and label flip behavior need human eye."
  - test: "Visually verify VectorStoreDebugView paginates and accepts FilterExpressionTextParser input"
    expected: "Admin opens Vector Store Debug view; runs empty search to see all chunks; types a metadata filter expression and confirms parse success and result narrowing; expand button shows full content + metadata."
    why_human: "Read-only debug view; visual quality and parse-error UX (errorMessage not toast) need human verification."
re_verification: null
gaps: []
deferred: []
---

# Phase 10: AI-Specific LLM Exposure Policy Verification Report

**Phase Goal:** Admin can narrow the LLM-visible surface (entities and attributes) below the user's Jmix permissions through a single denylist-only governance layer; the policy is uniformly enforced across schema discovery, tool calls, baseline prompt, and RAG.

**Verified:** 2026-04-28T03:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Admin creates an `AiExposureRule` via `AiExposureRuleListView`/`AiExposureRuleDetailView` (gated to `AiAgentAdminRole`, with `genericFilter` + `propertyFilter`, action-column for enable/disable, "Hide from AI"/"Visible to AI" labels — never "Allow"); rule takes effect on next chat turn. `attributePath` field omitted per 2026-04-27 decision. | VERIFIED | `AiExposureRule` entity (no `attributePath` field, only `EXCLUDE` mode); `AiExposureRuleListView.java` + XML with @Supply toggle renderer; `AiExposureRuleDetailView.java` + XML with `MetaclassComboBoxHelper`; menu.xml entries; `AiAgentAdminRole` `@EntityPolicy/MenuPolicy/ViewPolicy`; locale strings present in `messages_en.properties` + `messages_vi.properties`. Stateless `LlmExposurePolicy` per-call lookup means rule changes take effect immediately. |
| 2 | An entity that the current user can read in Jmix but is denylisted for the LLM does not appear in `list_entities`, `agent.entities`, RAG hits, or `find_records` — surfaces uniformly as `unknown_entity` (never `access_denied`). | VERIFIED | `LlmExposurePolicyIntegrationTest` (4 tests, all pass green): `denylistedEntityNotInListEntities`, `denylistedEntityNotInAgentEntities`, `findRecordsDenylistedEntityReturnsUnknownEntityNotAccessDenied`, `ragFilterContainsDenylistNinClause`. Plus `BuiltInDataTools` line 162/289/337/344/348/356 — all denial paths return `unknown_entity`; no `access_denied` reachable on tool entity-resolution path. |
| 3 | `BuiltInDataTools`, `BaselineContextProvider`, `RetrievalFilterBuilder` all consult `LlmExposurePolicy` (`userVisible AND NOT excluded`); `LlmExposureRuleRepository` uses `UnconstrainedDataManager` so user-role tweaks cannot bypass admin governance. | VERIFIED | `BuiltInDataTools.java` field `private final LlmExposurePolicy llmExposurePolicy` (no `currentUserSchemaAccess` ref); `BaselineContextProvider.java` line 75 same; `FetchPlanIntersector.java` line 71 same; `RetrievalFilterBuilder.java` line 115 calls `llmExposurePolicy.getDenylistedEntityNames()`; `LlmExposureRuleRepository.java` line 23-25 constructor takes `UnconstrainedDataManager`. |
| 4 | Rule create/update/delete publishes `LlmExposureChangedEvent`; `AiAgentAdminRole` carries CRUD + view + menu policies for `AiExposureRule`. | VERIFIED | `AiExposureRuleEntityListener.java` is the single publish site (Javadoc explicitly enforces single-publisher invariant); `@EventListener EntityChangedEvent<AiExposureRule>` → `publishEvent(new LlmExposureChangedEvent(this))`. `AiAgentAdminRole.java` line 31 `@EntityPolicy(entityClass = AiExposureRule.class, actions = EntityPolicyAction.ALL)`, line 41-42 `@MenuPolicy("aiAgent.exposureRules.list", "aiAgent.vectorStoreDebug")`, line 50-51 `@ViewPolicy(...AiAgent_AiExposureRule.list/.detail/AiAgent_VectorStoreDebug)`. |

**Score:** 4/4 ROADMAP success criteria verified.

### Required Artifacts (sampled across plans 10-01..10-10)

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `com/vn/agent/exposure/AiExposureRuleMode.java` | EnumClass, EXCLUDE only | VERIFIED | Single value `EXCLUDE`; comment "There is no ALLOW value" |
| `com/vn/agent/exposure/AiExposureRule.java` | `@JmixEntity`, agentstore, UUID + Version + InstanceName, no attributePath | VERIFIED | All present; `@Index(unique=true)` on entityName; `@InstanceName` on entityName |
| `com/vn/agent/exposure/LlmExposurePolicy.java` | wraps CurrentUserSchemaAccess; denylist loaded once; canModify ships unused | VERIFIED | All five methods present (`getReadableSchema`, `canReadEntity`, `canReadAttribute`, `canModify`, `getDenylistedEntityNames`); denylist loaded once at top of `getReadableSchema()` then `removeIf` against Set |
| `com/vn/agent/exposure/LlmExposureRuleRepository.java` | UnconstrainedDataManager, JPQL via Class fluent API | VERIFIED | Constructor takes `UnconstrainedDataManager`; query uses `dataManager.load(AiExposureRule.class)` (auto-resolves agentstore via `@Store` on entity) |
| `com/vn/agent/exposure/AiExposureRuleEntityListener.java` | Single publish site | VERIFIED | `@EventListener EntityChangedEvent<AiExposureRule>` → `publishEvent(new LlmExposureChangedEvent(this))` with explicit single-publisher Javadoc |
| `com/vn/agent/exposure/LlmExposureChangedEvent.java` | Spring ApplicationEvent | VERIFIED | File present (703 bytes) |
| `com/vn/agent/rag/ChunkMetadata.java` | SOURCE_ENTITY = "source_entity" constant | VERIFIED | Line 33 `public static final String SOURCE_ENTITY = "source_entity";` |
| `com/vn/agent/entity/AiKnowledgeDocument.java` | sourceEntityName nullable field + getter/setter | VERIFIED | Line 53 field; lines 87-88 accessors |
| `liquibase/agentstore-changelog/060-ai-exposure-rule.xml` | AI_EXPOSURE_RULE table DDL | VERIFIED | Table + unique constraint + enabled index. **Note:** unique constraint named `UNQ_*` while JPA expects `IDX_*` (REVIEW BLOCKER-02). Test migration runs clean on hsqldb. |
| `liquibase/agentstore-changelog/061-ai-knowledge-document-source-entity.xml` | SOURCE_ENTITY_NAME column DDL + index | VERIFIED | ChangeSets 1+2 ran successfully in test logs |
| `tools/BuiltInDataTools.java` | Migrated to LlmExposurePolicy; unknown_entity for both unknown+denylisted | VERIFIED | All currentUserSchemaAccess refs replaced; lines 162/289/337/344/348/356 all use `unknown_entity`; no `access_denied` on entity-resolution path |
| `orchestration/BaselineContextProvider.java` | LlmExposurePolicy injection | VERIFIED | Line 75 `private final LlmExposurePolicy llmExposurePolicy` |
| `tools/fetchplan/FetchPlanIntersector.java` | Both canReadAttribute AND canReadEntity routed via policy | VERIFIED | Line 71 field; Javadoc lines 26-31 confirm both checks route via policy |
| `rag/RetrievalFilterBuilder.java` | nin clause on SOURCE_ENTITY when denylist non-empty; admin bypass preserved; defensive isNull-OR-nin | VERIFIED | Lines 113-119: `b.or(b.isNull(SOURCE_ENTITY), b.nin(SOURCE_ENTITY, denied))` per Plan 10-05 D-06 |
| `rag/AsyncIngestionWorker.java` | sourceEntityName mirrored to chunk metadata at ingest | VERIFIED | Lines 293-294 conditional `merged.put(ChunkMetadata.SOURCE_ENTITY, doc.getSourceEntityName())` (null-guarded for legacy doc carve-out) |
| `security/AiAgentAdminRole.java` | @EntityPolicy + @MenuPolicy + @ViewPolicy extensions | VERIFIED | All three annotations carry the new entries |
| `view/exposure/AiExposureRuleListView.java` + XML | StandardListView, @Supply toggle, no manual event publish | VERIFIED | View descriptor with genericFilter; Java with @Supply; toggleEnabled uses UnconstrainedDataManager.save (entity listener publishes event) |
| `view/exposure/AiExposureRuleDetailView.java` + XML | ComboBox via MetaclassComboBoxHelper; no attributePath; no mode field | VERIFIED | Detail view uses MetaclassComboBoxHelper; no attributePath rendered |
| `view/exposure/MetaclassComboBoxHelper.java` | Filtered MetaClass list (no @SystemLevel, no AI-* internals) | VERIFIED | File present; `AI_INTERNAL_ENTITY_NAMES` allowlist hard-codes six entity names (acceptable per REVIEW notes) |
| `view/vectorstore/VectorStoreDebugView.java` + XML | Programmatic Grid<Document>; FilterExpressionTextParser; expand Dialog | VERIFIED | File present (10722 bytes) |
| `view/knowledge/KnowledgeBaseView.java` + XML | sourceEntityName ComboBox + editPermissions + reingest + sourceEntity column | VERIFIED (with caveat) | All four extensions present. **Caveat:** upload form passes `Collections.emptyList()` for allowedRoles — see WARNING-08 in human_verification |
| `messages_en.properties` + `messages_vi.properties` | All UI keys in BOTH locales | VERIFIED | Both bundles contain `exposureRulesList.title` and `knowledgeBase.error.reingestEnqueueFailed`. Note: file is `messages_en.properties` (locale-suffixed), not `messages.properties` — equivalent for English locale. |
| `menu.xml` | aiAgent.exposureRules.list and aiAgent.vectorStoreDebug entries | VERIFIED | Lines 17 and 19 |
| `test/.../LlmExposurePolicyIntegrationTest.java` | 4 opacity assertions | VERIFIED | 4 tests pass; results XML: tests=4 failures=0 errors=0 |
| `test/.../RetrievalFilterBuilderDenylistTest.java` | nin clause + ISNULL branch + empty-denylist + admin-bypass | VERIFIED | 4 tests pass; results XML: tests=4 failures=0 errors=0 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| AiExposureRuleEntityListener | LlmExposureChangedEvent | `publishEvent(new LlmExposureChangedEvent(this))` | WIRED | Line 33 |
| LlmExposurePolicy.getReadableSchema | CurrentUserSchemaAccess.getReadableSchema | `delegate.getReadableSchema()` | WIRED | Line 48 |
| LlmExposurePolicy | LlmExposureRuleRepository.findEnabledExcludedEntityNames | `ruleRepository.findEnabledExcludedEntityNames()` | WIRED | Lines 47, 63, 84, 94 |
| BuiltInDataTools.resolveReadableEntityOrThrow | ToolErrorDto.unknownEntity | denial branch | WIRED | All 6 thrown ToolUserError sites use "unknown_entity"; no "access_denied" code on this path |
| RetrievalFilterBuilder.buildFor | LlmExposurePolicy.getDenylistedEntityNames | `llmExposurePolicy.getDenylistedEntityNames()` | WIRED | Line 115 |
| RetrievalFilterBuilder.buildFor | FilterExpressionBuilder.nin / .isNull | `b.or(b.isNull(...), b.nin(...))` | WIRED | Lines 117-120 (defensive D-06 form) |
| AsyncIngestionWorker.enrich | ChunkMetadata.SOURCE_ENTITY | `merged.put(ChunkMetadata.SOURCE_ENTITY, doc.getSourceEntityName())` | WIRED | Line 294 |
| BaselineContextProvider.compose | LlmExposurePolicy.getReadableSchema | `llmExposurePolicy.getReadableSchema()` | WIRED | Line 106 |
| AiAgentAdminRole | AiExposureRule.class | `@EntityPolicy(entityClass=AiExposureRule.class, actions=ALL)` | WIRED | Line 31 |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Phase 10 critical security tests pass | `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.exposure.LlmExposurePolicyIntegrationTest --tests com.vn.agent.rag.RetrievalFilterBuilderDenylistTest` | BUILD SUCCESSFUL; tests=4+4 failures=0 errors=0 | PASS |
| Phase 10 broader regression suite passes | `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.exposure.* --tests com.vn.agent.rag.RetrievalFilterBuilderDenylistTest --tests com.vn.agent.rag.RetrievalFilterBuilderTest --tests com.vn.agent.security.FilteredSchemaAndExecutionDenialTest --tests com.vn.agent.tools.UnknownEntityRetryHintTest` | BUILD SUCCESSFUL; 4+12+4+3+6 = 29 tests, 0 failures | PASS |
| Liquibase 060+061 changelogs run cleanly on hsqldb | Inspect test log output | "Unique constraint added to AI_EXPOSURE_RULE(ENTITY_NAME)"; "ChangeSet ...061... ran successfully"; "Update has been successful. Rows affected: 16" | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|------------|-------------|-------------|--------|----------|
| ENT-05 | 10-01 | New entity AiExposureRule | SATISFIED | Entity exists in `com.vn.agent.exposure.AiExposureRule`; agentstore @Store; UUID + Version + InstanceName per CLAUDE.md |
| EXP-01 | 10-01 | AiExposureRule entity in agentstore — entityName, mode=EXCLUDE only, enabled, audit fields | SATISFIED (with authorized deviation) | Entity matches; `attributePath` field intentionally omitted per CONTEXT decision 2026-04-27 + ROADMAP SC#1 note "attributePath field omitted per user decision" |
| EXP-02 | 10-02 | LlmExposurePolicy wraps CurrentUserSchemaAccess; same method signatures; userVisible AND NOT excluded | SATISFIED | LlmExposurePolicy.java:33 takes CurrentUserSchemaAccess as delegate; methods getReadableSchema/canReadEntity/canReadAttribute mirror; canReadEntity returns `delegate.canReadEntity(mc) && !denied.contains(mc.getName())` |
| EXP-03 | 10-04 | BuiltInDataTools migrated to LlmExposurePolicy at all call sites | SATISFIED | Field `private final LlmExposurePolicy llmExposurePolicy`; no `currentUserSchemaAccess` references remain in BuiltInDataTools.java |
| EXP-04 | 10-04 | BaselineContextProvider sources agent.entities/agent.permissions from LlmExposurePolicy | SATISFIED | BaselineContextProvider.java:75 field; `agent.entities` and `agent.permissions` both flow from `llmExposurePolicy.getReadableSchema()` (single-call-site invariant per Plan 09-03) |
| EXP-05 | 10-05 | RetrievalFilterBuilder integrates exposure policy — RAG retrieval excludes denylisted source-entity | SATISFIED | RetrievalFilterBuilder.java:115-120 reads `getDenylistedEntityNames()` and ANDs `b.or(b.isNull, b.nin)` clause; AsyncIngestionWorker.java:294 mirrors `sourceEntityName` to chunk metadata under SOURCE_ENTITY key |
| EXP-06 | 10-02 | LlmExposureRuleRepository uses UnconstrainedDataManager | SATISFIED | LlmExposureRuleRepository.java:23 constructor injects `UnconstrainedDataManager` |
| EXP-07 | 10-06, 10-07 | Admin Flow UI: list+detail views with genericFilter + propertyFilter, action column, AiAgentAdminRole gating, "Hide from AI"/"Visible to AI" labels | SATISFIED | AiExposureRuleListView with @Supply toggle renderer; AiExposureRuleDetailView with MetaclassComboBoxHelper; menu entries; locale strings include "Hide from AI"/"Visible to AI" (no "Allow") |
| EXP-08 | 10-02 | LlmExposureChangedEvent published on rule create/update/delete | SATISFIED | AiExposureRuleEntityListener.java single publish site via @EventListener EntityChangedEvent — covers all three CUD operations |
| EXP-09 | 10-04, 10-10 | Negative test: denylisted entity invisible in list_entities, agent.entities, RAG hits, find_records (uniform unknown_entity opacity) | SATISFIED | LlmExposurePolicyIntegrationTest 4 tests pass green; BuiltInDataTools.java line 162/289/337/344/348/356 all use unknown_entity; no access_denied path on entity resolution |
| EXP-10 | 10-03 | AiAgentAdminRole extended with CRUD + view + menu policies for AiExposureRule | SATISFIED | AiAgentAdminRole.java:31 @EntityPolicy.ALL; lines 41-42 @MenuPolicy; lines 50-51 @ViewPolicy |
| SEC-05 (Phase 10 portion: AiExposureRule) | 10-03 | AiAgentAdminRole policies for AiExposureRule (AiUiSettings completes Phase 12) | SATISFIED | AiExposureRule policies present per EXP-10. AiUiSettings explicitly deferred to Phase 12 per REQUIREMENTS.md and ROADMAP. |
| TEST-09 | 10-10 | LlmExposurePolicy integration test — uniform unknown_entity opacity across 4 paths | SATISFIED | LlmExposurePolicyIntegrationTest with 4 tests covering all 4 paths; all pass green |

**No orphaned requirements.** All 12 requirement IDs from PLAN frontmatters are mapped and verified.

### Anti-Patterns Found

Anti-pattern scan was implicitly covered by the standard code review (10-REVIEW.md). Findings classified by severity:

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `rag/AsyncIngestionWorker.java` | 282 | `List.copyOf(allowedRoles)` throws NPE on null elements | Warning (Info: REVIEW BLOCKER-01) | Corner-case ingestion failure for documents with null role entries |
| `liquibase/agentstore-changelog/060-ai-exposure-rule.xml` | 32-40 | Liquibase `UNQ_*` constraint name vs JPA `@Index(name=IDX_*)` | Warning (Info: REVIEW BLOCKER-02) | DDL parity issue under JPA schema validation |
| `view/knowledge/KnowledgeBaseView.java` | 165 | Hardcoded `Collections.emptyList()` for allowedRoles at upload | Warning (REVIEW WARNING-08) | Every uploaded document is admin-only-visible by default |
| `rag/KnowledgeDocumentService.java` | 161-186 | Partial-failure window leaves stale chunks visible | Warning (REVIEW WARNING-01) | Edge-case RAG leak for affected documents |
| `view/knowledge/KnowledgeBaseView.java` | 378-382 | Edit-permissions dialog exposes all roles including system-full-access | Warning (REVIEW WARNING-02) | UX foot-gun |
| `view/knowledge/knowledge-base-view.xml` + Java | 30-37, 158-177 | Conflicting upload mechanisms: `receiverType=` XML + programmatic `setUploadHandler` | Warning (REVIEW WARNING-03) | Vaadin-version-specific brittleness |
| `view/exposure/AiExposureRuleListView.java` | 121-137 | Optimistic-lock pitfall on consecutive toggle clicks (stale rule reference) | Warning (REVIEW WARNING-04) | Two-fast-clicks scenario surfaces generic toast |
| `rag/KnowledgeDocumentUploadService.java` | 165 | Dead `embeddingProperties.resolvedModel()` call | Info (REVIEW WARNING-05) | Style — comment instead |
| `view/knowledge/KnowledgeBaseView.java` | various | Inconsistent `formatMessage(...)` vs `getMessage(getClass(),...)` style | Info (REVIEW WARNING-06) | Future foot-gun on per-package bundles |
| `view/knowledge/KnowledgeBaseView.java` | 443-455 | `confirmAndSavePermissions` does not catch `DocumentNotFoundException` | Info (REVIEW WARNING-07) | Edge case — dialog stays open silently |
| `exposure/LlmExposureRuleRepository.java` | 34-42 | No explicit `.store("agentstore")` — auto-resolves via @Store but no regression test pins it | Info (REVIEW WARNING-09) | Future-proofing only |
| `rag/AsyncIngestionWorker.java` | 280 | `doc.getId().toString()` no defensive null guard | Info (REVIEW WARNING-10) | Future-proofing only |
| `rag/RetrievalFilterBuilder.java` | 90-98 | Per-role expression duplicates model-pin clause | Info (REVIEW WARNING-11) | Token limit risk for users with 50+ roles (out of v1.1 expected range) |

**Severity classification:** None of the above failures invalidate any phase must-have. The two REVIEW BLOCKERs are real correctness issues that pass through the gates because the must-haves are structurally and behaviorally satisfied. They are surfaced for human triage in the human_verification block.

### Human Verification Required

See frontmatter `human_verification` block. Six items:
1. Triage REVIEW BLOCKER-01 (AsyncIngestionWorker enrich NPE corner case)
2. Triage REVIEW BLOCKER-02 (Liquibase index vs JPA @Index name mismatch)
3. Triage REVIEW WARNING-08 (KB upload form lacks allowedRoles field)
4. Triage REVIEW WARNING-01 (Partial-failure window in updatePermissionsAndReingest)
5. Visual verify AiExposureRuleListView/DetailView admin workflow
6. Visual verify VectorStoreDebugView pagination + filter parsing

### Gaps Summary

**No must-have-failing gaps.** The phase goal is achieved on every measurable axis:
- All 4 ROADMAP success criteria are structurally verified and behaviorally proven by integration tests.
- All 12 phase requirement IDs are satisfied (with one authorized deviation for `attributePath` per CONTEXT decision).
- The central security invariant — uniform `unknown_entity` opacity across `list_entities`, `agent.entities`, `find_records`, and RAG filter — is pinned by `LlmExposurePolicyIntegrationTest` (4 tests pass green) and `RetrievalFilterBuilderDenylistTest` (4 tests pass green).
- All key links are wired: entity listener → event publisher; policy → repository → UnconstrainedDataManager; baseline + tools + fetch-plan + RAG all flow through `LlmExposurePolicy`.

The two REVIEW.md blockers are real correctness bugs that do **not** block the phase goal but **do** warrant human triage before Phase 11 begins. Both pass the gates because:
- BLOCKER-01 (NPE) is a corner-case affecting ingestion of documents with null role entries — the must-have "AsyncIngestionWorker mirrors sourceEntityName into chunk metadata" is structurally satisfied; the NPE path is unrelated.
- BLOCKER-02 (index name mismatch) is a DDL parity issue — the must-have "Liquibase runs cleanly creating AI_EXPOSURE_RULE table" is satisfied (test logs prove clean migration); the unique constraint exists, just under a different name than JPA expects.

Human triage is requested before declaring Phase 10 fully closed. After acceptance/fix decisions on the surfaced items, Phase 11 (Mutation-Capable Built-In Tools) is unblocked.

---

_Verified: 2026-04-28T03:00:00Z_
_Verifier: Claude (gsd-verifier)_
