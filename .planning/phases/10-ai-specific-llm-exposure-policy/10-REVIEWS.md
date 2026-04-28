---
phase: 10
reviewers: [codex, opencode]
reviewed_at: 2026-04-27T12:19:55Z
plans_reviewed: [10-01-PLAN.md, 10-02-PLAN.md, 10-03-PLAN.md, 10-04-PLAN.md, 10-05-PLAN.md, 10-06-PLAN.md, 10-07-PLAN.md, 10-08-PLAN.md, 10-09-PLAN.md, 10-10-PLAN.md]
---

# Cross-AI Plan Review — Phase 10

Two external AI CLIs (Codex and OpenCode) independently reviewed all 10 PLAN.md files for Phase 10 against PROJECT.md, ROADMAP.md, REQUIREMENTS.md, CONTEXT.md, and RESEARCH.md.

Reviewers configured / available:
- gemini: missing
- claude: skipped (executing CLI is Claude Code)
- codex: invoked
- coderabbit: missing
- opencode: invoked
- qwen: missing
- cursor: missing
- ollama / lm_studio / llama_cpp: not configured

---

## Codex Review

**Summary**

The phase is well decomposed and mostly aligned with the roadmap, but I would not execute these plans unchanged. The backend boundary design is sound, yet several plans miss codebase realities: `BuiltInDataTools` will still return `access_denied` for denylisted entities, `FetchPlanIntersector` has a nested `canReadEntity()` call the plan underspecifies, and `KnowledgeBaseView` cannot persist upload-time `sourceEntityName` without changing `KnowledgeDocumentUploadService`. Overall risk is **HIGH** until those are corrected.

**Strengths**

- Clear wave ordering: schema foundations, service boundary, security, call-site swaps, UI, then tests.
- Good core architecture: `LlmExposurePolicy` wraps `CurrentUserSchemaAccess` and narrows with `userVisible AND NOT excluded`.
- Correct use of `UnconstrainedDataManager` for rule reads.
- Good decision to avoid `attributePath` and `ALLOW` in v1.1.
- RAG governance is addressed at both metadata-write time and retrieval-filter time.
- Plans consistently require all UI strings in both locale bundles.
- Spring AI assumptions are mostly current: `SearchRequest` has `query`, `topK`, `similarityThreshold`, and `filterExpression`; `FilterExpressionBuilder` supports `NIN`.

**Concerns**

- **HIGH, 10-04:** Mechanical swap does not satisfy uniform opacity. `BuiltInDataTools.resolveReadableEntityOrThrow()` currently throws `access_denied` when `canReadEntity()` is false. After swapping to `LlmExposurePolicy`, a denylisted but Jmix-readable entity will still return `access_denied`, failing EXP-09.
- **HIGH, 10-04:** `FetchPlanIntersector` also calls `schemaAccess.canReadEntity(nestedMetaClass)`, not only `canReadAttribute()`. The plan text focuses on `canReadAttribute`, so denylisted relationship targets may still leak through host fetch-plan overrides if this call is missed.
- **HIGH, 10-08:** Upload-time `sourceEntityName` cannot be persisted with only `KnowledgeBaseView` changes. Current `KnowledgeDocumentUploadService.upload(sourceUri, sourceKind, allowedRoles)` creates and saves the document internally, then schedules ingestion after commit. The plan must modify this service signature or pass a metadata object before ingestion is scheduled.
- **HIGH, 10-08:** “Edit permissions” is underdesigned. The plan adds an action but no concrete editor for `allowedRolesJson` and `sourceEntityName`; saving the selected grid row does not actually let the admin change either value.
- **MEDIUM, 10-05:** The `NIN` clause should explicitly preserve legacy chunks with missing `source_entity`. Depending on pgvector converter/null semantics, `source_entity NIN [...]` alone may exclude missing/null metadata. Safer expression: `(source_entity IS NULL OR source_entity NIN denied)`.
- **MEDIUM, 10-02 / 10-05:** `getDenylistedEntityNames()` is package-private in 10-02 but made public in 10-05. Decide once in 10-02; the later visibility change is unnecessary churn.
- **MEDIUM, 10-06:** Toggle save manually publishes `LlmExposureChangedEvent`, but the entity listener should already publish on save. This can double-fire invalidation events.
- **MEDIUM, 10-09:** `VectorStoreDebugView` XML uses a Jmix `dataGrid` without a data container, then adds columns programmatically. This is compile/runtime-risky in Flow UI. Prefer a programmatic Vaadin `Grid<Document>` hosted in a simple container, or a real DTO-backed container.
- **MEDIUM, 10-10:** Tests do not account for the existing `FilteredSchemaAndExecutionDenialTest`, which currently asserts `access_denied`. If Phase 10 changes denial semantics, update or split the old test explicitly.
- **LOW, 10-01:** Audit fields should use Jmix audit annotations (`@CreatedBy`, `@CreatedDate`, `@LastModifiedBy`, `@LastModifiedDate`) if they are expected to populate automatically.
- **LOW, 10-09:** The plan says the debug view benefits from `RetrievalFilterBuilder` admin bypass, but the debug view calls `VectorStore` directly. Admin visibility comes from not applying the chat retrieval filter.

**Suggestions**

- In 10-04, explicitly update `resolveReadableEntityOrThrow()` to return `unknown_entity` for denylisted entities. If preserving Jmix `access_denied` for ordinary role denial matters, add a policy helper like `isExcluded(MetaClass)` so the tool can distinguish the two cases.
- In 10-04, require swapping both `canReadAttribute()` and `canReadEntity()` in `FetchPlanIntersector`.
- In 10-08, add `KnowledgeDocumentUploadService.upload(..., Collection<String> allowedRoles, String sourceEntityName)` or a request DTO, and set `sourceEntityName` before `dataManager.save(document)`.
- In 10-08, define the edit dialog concretely: role multi-select, source entity combo, save, then confirm reingest.
- In 10-05, build the RAG clause as null-preserving unless verified pgvector semantics guarantee missing metadata remains visible.
- In 10-02, make `getDenylistedEntityNames()` public from the start with narrow Javadoc.
- In 10-06, rely on the `EntityChangedEvent` listener for event publication unless testing proves `UnconstrainedDataManager.save()` does not emit it.
- In 10-10, add regression coverage for existing denial semantics and an assertion that `DefaultChatServiceImpl` actually passes the denylist filter into retrieval.

**Risk Assessment**

Overall risk: **HIGH**. The architecture is directionally correct, but the current plan set has at least three likely phase-blocking failures: denylisted tools returning `access_denied`, incomplete fetch-plan migration, and KB source-entity persistence being impossible without a service-layer change.

Docs checked: Jmix Flow UI list actions and `EntityChangedEvent`; Spring AI vector store `SearchRequest` and filter expression docs.

---

## OpenCode Review

# Phase 10 Plan Review

## 10-01-PLAN.md

**Summary**  
Strong foundation plan with clear entity/schema scope and good alignment to the entity-level-only decision. It correctly avoids `attributePath` and sets up required primitives (`AiExposureRule`, enum, metadata constant, `sourceEntityName`).

**Strengths**
- Explicitly honors the 2026-04-27 decision: entity-level denylist only.
- Includes Liquibase artifacts and ties them to code-level fields.
- Good acceptance checks for forbidden shapes (`ALLOW`, `attributePath`).

**Concerns**
- **MEDIUM:** Audit fields are added as plain columns, but no explicit auditing annotations/traits are listed; auto-population may be inconsistent.
- **LOW:** Verification commands rely on `grep/tail` style checks; brittle for long-term CI confidence.

**Suggestions**
- Add explicit Jmix audit field strategy (same pattern as existing AI entities).
- Add a small integration test that persists `AiExposureRule` and reads it back from `agentstore`.

**Risk Assessment**  
**LOW-MEDIUM** — core scope is solid; biggest risk is audit-field behavior mismatch.

---

## 10-02-PLAN.md

**Summary**  
Architecturally strong and central to the phase. The delegate-and-narrow boundary is correct, and the event bridge via `EntityChangedEvent` is the right Jmix idiom.

**Strengths**
- Correctly uses `UnconstrainedDataManager` in repository.
- Explicit no-cache stance aligns with phase decisions.
- `getReadableSchema()` optimization avoids N-per-entity DB lookups.

**Concerns**
- **MEDIUM:** `canReadEntity()` and `canModify()` each re-query denylist every call; could cause repeated DB hits inside tool-heavy paths.
- **LOW:** Event is publish-only with no consumer; may confuse maintainers unless documented clearly (partially addressed).

**Suggestions**
- Consider one-call local denylist reuse in methods that do multiple checks in a single execution path.
- Add Javadoc note: no consumer by design until Phase 12+.

**Risk Assessment**  
**MEDIUM** — functionally correct, minor performance churn possible under heavy tool call volume.

---

## 10-03-PLAN.md

**Summary**  
Simple and necessary role-policy extension. Good separation and minimal change surface.

**Strengths**
- Directly addresses SEC-05/EXP-10.
- Preserves existing policies while extending menu/view/entity access.

**Concerns**
- **LOW:** No negative test to confirm non-admin denial for new views/entities.

**Suggestions**
- Add one security test validating non-admin cannot access `AiExposureRule` view/menu.

**Risk Assessment**  
**LOW** — straightforward, low complexity.

---

## 10-04-PLAN.md

**Summary**  
Good mechanical swap plan; keeps behavior stable while routing all relevant call sites through the new policy boundary.

**Strengths**
- Scope is tight and migration-focused.
- Explicitly preserves `unknown_entity` messaging contract.
- Correctly leaves `CurrentUserSchemaAccess` unchanged.

**Concerns**
- **MEDIUM:** “No `CurrentUserSchemaAccess` in orchestration/tools” verification may overreach and fail due unrelated files.
- **LOW:** No explicit regression assertion for byte-for-byte error payload in tests.

**Suggestions**
- Narrow grep/verification to touched files only.
- Add one focused test for unchanged `unknown_entity` payload text.

**Risk Assessment**  
**LOW-MEDIUM** — likely safe; verification fragility is the main issue.

---

## 10-05-PLAN.md

**Summary**  
Critical plan that closes RAG leakage path. `nin(source_entity, denylist)` and ingestion mirroring are the right primitives.

**Strengths**
- Preserves admin bypass branch order (important).
- Uses `FilterExpressionBuilder.nin()` instead of hand-rolled logic.
- Correct null-key behavior for legacy docs aligns with documented contract.

**Concerns**
- **MEDIUM:** Plan mutates `LlmExposurePolicy` visibility but does not list file in header `files_modified`.
- **MEDIUM:** Cross-plan inconsistency: 10-02 says package-private helper; 10-05 flips to public.
- **LOW:** Legacy-doc behavior may be misunderstood operationally (not code risk, but rollout risk).

**Suggestions**
- Update plan metadata to include `LlmExposurePolicy.java`.
- Resolve visibility decision once (public method or dedicated public facade) and document in PLAN/SUMMARY.
- Add an explicit operator note in docs for legacy chunks without `source_entity`.

**Risk Assessment**  
**MEDIUM** — technically sound, but plan-consistency and visibility-churn risks exist.

---

## 10-06-PLAN.md

**Summary**  
Good admin list UX plan with reusable metaclass helper and clear toggle semantics.

**Strengths**
- Reuses Jmix idioms (`genericFilter`, `@Supply`, list actions).
- Correctly enforces “Hide from AI / Visible to AI” wording.
- Smart helper extraction improves reuse for later plans.

**Concerns**
- **MEDIUM:** Toggle handler publishes `LlmExposureChangedEvent` manually while entity listener already publishes on CUD — duplicate events.
- **LOW:** Unconstrained save in UI is intentional but should be tightly justified in code comments.

**Suggestions**
- Remove manual event publish from view; rely on entity listener only.
- Keep UnconstrainedDataManager usage but document why in method Javadoc.

**Risk Assessment**  
**MEDIUM** — duplicate event emission is the main correctness/maintainability issue.

---

## 10-07-PLAN.md

**Summary**  
Solid detail-view and i18n completeness plan; strong on localization discipline and menu integration.

**Strengths**
- Correctly avoids free-text entity entry and omitted fields (`mode`, `attributePath`).
- Strong bilingual message discipline.
- Good menu wiring aligned with role policy IDs.

**Concerns**
- **LOW:** Requirement tag includes `EXP-09` though this plan is mostly UI/i18n/menu (traceability noise).
- **MEDIUM:** Very large “all keys now” step can create merge pain and accidental key drift.

**Suggestions**
- Split message-key commit by feature groups (exposure, KB, debug) to ease review.
- Keep a generated key checklist file to reduce omission/duplication risk.

**Risk Assessment**  
**LOW-MEDIUM** — implementation likely fine, but large i18n batch is error-prone.

---

## 10-08-PLAN.md

**Summary**  
Important operational plan linking KB metadata edits to reingest contract. Aligns with D-07/D-08 goals.

**Strengths**
- Correctly treats reingest as propagation mechanism.
- Adds source-entity capture at upload time.
- Includes explicit reingest action and sourceEntity rendering.

**Concerns**
- **HIGH:** Dependency list omits `10-06` even though this plan requires `MetaclassComboBoxHelper`.
- **MEDIUM:** “Choose simplest dialog pattern” leaves implementation ambiguous; high variance risk.
- **MEDIUM:** Edit+reingest flow lacks a clear transactional guard for partial failures (save succeeds, reingest enqueue fails).

**Suggestions**
- Add `depends_on: [10-06]`.
- Specify one concrete UI flow for edit-permissions (exact dialog + save path).
- Add explicit error handling path: save rollback vs saved-with-warning status and retry action.

**Risk Assessment**  
**MEDIUM-HIGH** — dependency gap and workflow ambiguity can cause integration breakage.

---

## 10-09-PLAN.md

**Summary**  
Valuable admin debug surface, but technically the riskiest plan due uncertainty around Jmix DataGrid vs plain Vaadin Grid binding for `Document` objects.

**Strengths**
- Good read-only boundary and explicit no-mutation scope.
- Proper parser-based filter handling with inline errors.
- Accessibility requirement (ARIA labels) is a strong inclusion.

**Concerns**
- **HIGH:** Component model ambiguity (`DataGrid<Document>` with no data container) may fail at runtime/compile; plan itself flags uncertainty.
- **MEDIUM:** XML column definitions plus programmatic column creation can conflict/duplicate.
- **MEDIUM:** Empty-query embedding cost may be non-trivial in production debug usage.

**Suggestions**
- Lock one approach now: use plain Vaadin `Grid<Document>` if Jmix `DataGrid` proves incompatible.
- Avoid dual column definitions; pick XML or programmatic, not both.
- Add throttling/debounce or explicit “Search only on click” guidance (already mostly present).

**Risk Assessment**  
**HIGH** — highest implementation risk in the set due framework-integration uncertainty.

---

## 10-10-PLAN.md

**Summary**  
Good test intent and strong focus on opacity contract, but test setup details are somewhat brittle and may produce false confidence if not tightened.

**Strengths**
- Covers core denial surfaces (`list_entities`, `agent.entities`, `find_records`).
- Adds dedicated unit test for denylist clause logic.
- Keeps RAG expression-shape assertion separate from heavy integration tests.

**Concerns**
- **MEDIUM:** Authority-mapping assumptions in admin bypass unit test may be wrong and flaky.
- **MEDIUM:** Dynamic target-entity selection in integration tests can cause nondeterminism.
- **LOW-MEDIUM:** RAG path is validated structurally, not end-to-end behaviorally (acceptable but limited).

**Suggestions**
- Use a deterministic known test entity instead of “first non-system entity”.
- Build authentication principals exactly as existing tests do (reuse helper).
- Add one lightweight integration assertion that retrieval filter passed to vector store includes source_entity denylist when rule exists.

**Risk Assessment**  
**MEDIUM** — good direction, but test reliability needs hardening.

---

## Overall Assessment

**Summary**  
The phase plan set is mostly well-structured and maps strongly to the intended Phase 10 goals (single denylist governance boundary, call-site substitution, RAG narrowing, admin UI, and opacity tests). The major gaps are in cross-plan consistency and a few high-risk implementation ambiguities, especially around `VectorStoreDebugView` and one missing dependency in KB view extensions.

**Strengths**
- Strong alignment to locked decisions and security posture.
- Good separation of concerns across waves.
- Clear intent to preserve opacity (`unknown_entity`) and avoid widening permissions.

**Concerns**
- **HIGH:** 10-09 technical uncertainty could stall/derail late wave execution.
- **HIGH:** 10-08 missing dependency on 10-06 can break build ordering.
- **MEDIUM:** Event publication duplication (10-06 + entity listener).
- **MEDIUM:** Visibility/API churn (`getDenylistedEntityNames`) between 10-02 and 10-05.

**Suggestions**
- Fix dependency graph (`10-08 -> 10-06`) before execution.
- Resolve `getDenylistedEntityNames` visibility decision once and update both plans.
- Remove duplicate event publish from list view.
- De-risk 10-09 early with a spike/prototype before wave 4 lock.

**Risk Assessment**  
**MEDIUM** overall — the architecture is sound and phase goals are achievable, but execution risk is concentrated in a few integration points that should be resolved before implementation begins.


---

## Consensus Summary

Both reviewers agree the architecture is directionally correct — single denylist boundary, clean call-site substitution, sound RAG narrowing strategy, and good security posture. Both also flag overlapping execution risks that should be resolved BEFORE running `/gsd-execute-phase`. Codex assigns overall HIGH risk; OpenCode assigns MEDIUM overall with specific plans at HIGH.

### Agreed Strengths

- Strong alignment with locked CONTEXT.md decisions (entity-level only, EXCLUDE-only, "Hide from AI" / "Visible to AI" wording, reingest as propagation contract).
- Clean wave structure with explicit dependencies; UI separation honors Jmix conventions.
- Tests dedicated to the four opacity paths (TEST-09); RAG filter shape covered by unit test.
- Reingest treated as the propagation mechanism; no direct pgvector mutation.
- ALL UI strings in BOTH locale bundles; AiAgentAdminRole policies extended consistently.

### Agreed Concerns (raised by 2+ reviewers — highest priority)

- **HIGH — Plan 10-08 is incomplete.** Codex: `KnowledgeBaseView` cannot persist `sourceEntityName` at upload time without modifying `KnowledgeDocumentUploadService` (or a request DTO carrying allowedRoles + sourceEntityName before `dataManager.save(document)`). OpenCode: 10-08 also needs `depends_on: [10-06]` because it consumes `MetaclassComboBoxHelper`. Both findings converge on "this plan needs more concrete service-layer work and the dependency graph needs fixing."

- **MEDIUM — `LlmExposureChangedEvent` double-publish risk.** Both reviewers flag that 10-06 publishes the event manually after the toggle save, AND 10-02 wires a Jmix `EntityChangedEvent` listener that also publishes. Net effect: the event fires twice for every toggle. Resolve by relying on the listener exclusively (remove the manual publish from 10-06's save handler) — verified that `UnconstrainedDataManager.save()` does fire `EntityChangedEvent`.

- **MEDIUM — `getDenylistedEntityNames()` visibility churn.** 10-02 introduces this as package-private; 10-05 then re-opens it to public for `RetrievalFilterBuilder` (cross-package consumer). Decide once: declare the method `public` in 10-02 with narrow Javadoc and stable contract — eliminates the visibility flip in 10-05.

### Codex-only HIGH concerns (single reviewer but high signal)

- **HIGH — Opacity break in 10-04.** Codex: `BuiltInDataTools.resolveReadableEntityOrThrow()` currently returns `access_denied` for unknown entities. The Phase 10 contract requires denylisted entities to surface as `unknown_entity` (not `access_denied`) per success criterion #2 + Phase 3 D-08 opacity. Plan 10-04 must explicitly migrate this conversion. Add a policy helper like `isExcluded(MetaClass)` if Jmix `access_denied` for ordinary role denial still needs to be distinguishable.
- **HIGH — Incomplete `FetchPlanIntersector` migration in 10-04.** Codex: `FetchPlanIntersector` calls BOTH `canReadAttribute` AND `canReadEntity` (relationship hop check). 10-04's task description swaps only the attribute call; the entity call must also route through `LlmExposurePolicy` or denylisted entities will leak through nested fetch plans.
- **MEDIUM — RAG `nin` clause null-handling.** Codex: confirm pgvector `nin` semantics with a missing key — chunks without `source_entity` should be retained (D-06 contract). Build the clause defensively if pgvector behavior is uncertain.

### OpenCode-only HIGH concerns

- **HIGH — Plan 10-09 framework integration uncertainty.** OpenCode: `DataGrid<Document>` binding for vector-store chunks is not standard Jmix shape (Jmix DataGrid expects a `CollectionContainer<Entity>`; `Document` is a Spring AI POJO, not a Jmix entity). Risk of compile-time or runtime failure. Recommend: lock to plain Vaadin `Grid<Document>` if Jmix `DataGrid` proves incompatible, and choose XML-only OR programmatic column definitions (not both).

### Other concerns flagged by one reviewer

- 10-08: dialog pattern ambiguity ("choose simplest dialog") + save+reingest partial-failure handling.
- 10-10: nondeterministic test target selection ("first non-system entity") and authority-mapping assumptions in admin-bypass test — make principal construction match existing test helpers.
- 10-07: large all-keys-at-once message commit creates merge / drift risk — split by feature group.
- 10-10: RAG path is asserted structurally (filter expression shape) rather than end-to-end behavior; acceptable but limited.

### Divergent Views

- **10-09 risk level:** OpenCode HIGH (framework integration uncertainty); Codex did not flag it as strongly. The Jmix `DataGrid` vs Vaadin `Grid` choice should be locked before execution rather than deferred to the executor.
- **Overall risk:** Codex HIGH ("would not execute unchanged"); OpenCode MEDIUM ("achievable but resolve a few integration points first"). The disagreement traces to how each reviewer weighs the opacity break in 10-04 (Codex treats it as phase-blocking; OpenCode acknowledges but treats as fixable).

### Recommended Next Step

Run `/gsd-plan-phase 10 --reviews` to replan with this feedback. Priority fixes:
1. **10-08:** add `depends_on: [10-06]`; specify `KnowledgeDocumentUploadService` (or DTO) extension for upload-time `sourceEntityName` persistence; concrete edit dialog flow; partial-failure handling.
2. **10-04:** ensure `BuiltInDataTools` denied-entity error path returns `unknown_entity` (preserve Phase 9 D-14 hint strings byte-for-byte); migrate BOTH `canReadAttribute` AND `canReadEntity` in `FetchPlanIntersector`.
3. **10-02 + 10-06:** decide once that `getDenylistedEntityNames()` is public; remove manual event publish from the toggle save handler in 10-06.
4. **10-09:** lock the grid component choice (Vaadin `Grid<Document>` vs Jmix `DataGrid`) and column definition style (XML-only vs programmatic) before execution.
5. **10-10:** use a deterministic seeded test entity; reuse existing authentication helpers; add one end-to-end assertion that retrieval filter passed to vector store includes `source_entity` denylist.
