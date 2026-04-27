# Phase 10: AI-Specific LLM Exposure Policy — Context

**Gathered:** 2026-04-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Admin-governed denylist that narrows the LLM-visible surface (entities + RAG hits)
**below** the user's Jmix permissions, through a single `LlmExposurePolicy` boundary
substituted into all schema-discovery, tool-call, baseline-prompt, and RAG paths.
Phase 9 already left a single-call-site substitution seam at
`currentUserSchemaAccess.getReadableSchema()` — Phase 10 swaps the source.

Composition is **`userVisible AND NOT excluded`**: rules can never widen visibility,
only narrow. Rules are **entity-level only** in v1.1 — no `attributePath`, no dotted
paths, no relationship-prefix matching, no wildcards. Attribute-level exposure rules
deferred to a later milestone.

**In scope:**
- `AiExposureRule` Jmix entity in `agentstore` (`entityName`, `enabled`, `mode=EXCLUDE`-only,
  audit fields). NO `attributePath` field — entity-level only.
- `LlmExposurePolicy` `@Component` wrapping `CurrentUserSchemaAccess`
  (delegate-and-narrow). Exposes `getReadableSchema()`, `canReadEntity(MetaClass)`,
  `canReadAttribute(MetaClass, String)`, plus `canModify(MetaClass)` reserved for Phase 11.
- `LlmExposureRuleRepository` using `UnconstrainedDataManager`.
- Mechanical injection swap at every call site: `BuiltInDataTools`,
  `BaselineContextProvider`, `FetchPlanIntersector` (Phase 9), and any other consumer
  of `CurrentUserSchemaAccess`.
- `LlmExposureChangedEvent` published on rule create/update/delete; **no current
  consumer** (no caches in v1.1) — wired for Phase 12+ caching.
- `AiKnowledgeDocument.sourceEntityName` (nullable) — mirrored to chunk metadata
  `source_entity` at ingest time. `RetrievalFilterBuilder` adds a
  `NOT IN <denylisted-entity-names>` clause to the existing model+role filter.
- KB upload UX collects `allowedRoles` + optional `sourceEntityName` BEFORE ingestion.
- KB list view (existing `KnowledgeBaseView`) gains row actions for: edit
  permissions/source entity (triggers reingest), explicit "Reingest" action.
- **Reingestion is the default path** for any post-ingest change to fields mirrored
  into chunk metadata (`allowedRoles`, `sourceEntityName`). Never mutate pgvector
  chunk metadata in place.
- Admin Flow UI: `AiExposureRuleListView` + `AiExposureRuleDetailView`
  (`genericFilter` + `propertyFilter`, action column for enable/disable, gated to
  `AiAgentAdminRole`). Menu under existing admin section.
- New admin-only **Vector Store debug view** — read-only paginated grid over
  vector-store chunks (id, content, metadata) with metadata filter input. Modeled
  on the `jmix-ai-backend` reference UI. Inspection / debugging only.
- `AiAgentAdminRole` extended with CRUD + view + menu policies for `AiExposureRule`
  and the new debug view.
- TEST-09: entity readable by user but denylisted for LLM does not appear in
  `list_entities`, `agent.entities`, RAG hits, or `find_records`; surfaces as
  `unknown_entity` (never `access_denied`) — uniform-opacity assertion.

**Out of scope (explicit):**
- Attribute-level exposure rules (`attributePath`, dotted paths, prefix or wildcard
  matching) — deferred to a later milestone (decision 2026-04-27).
- Mutation gating chain (Phase 11). `LlmExposurePolicy.canModify(MetaClass)` ships
  with the Phase 10 boundary so Phase 11 can wire the call site, but no mutation
  surface consumes it in Phase 10.
- Time-bounded rules (auto-expire) — `EXP-11`, deferred (REQUIREMENTS Future
  Requirements).
- Document-id allowlist UI for legacy docs ingested without `sourceEntityName` —
  deferred to v1.2 (legacy docs are simply not entity-denylistable in v1.1).
- Caching of compiled rules — no cache anywhere in v1.1; ship the event for future
  consumers.
- Direct pgvector chunk metadata mutation as an alternative to reingest — explicitly
  rejected (see decisions D-04, D-08).
- New Jmix entities other than `AiExposureRule` (`AiUiSettings` Phase 12,
  `AiTaskFile` Phase 13, `AiExtractionDraft` Phase 14, `AiMutationIntent` Phase 11).
- Mutation tools, surfaces, STT, intent extraction — Phases 11 / 12 / 13 / 14.

</domain>

<decisions>
## Implementation Decisions

### Boundary surface + call-site migration

- **D-01:** `LlmExposurePolicy` is a stateless `@Component` that **wraps**
  `CurrentUserSchemaAccess` (delegate-and-narrow). `CurrentUserSchemaAccess` stays as the
  Jmix-permission source of truth (its tests, comments, MEMORY checklist references
  remain valid). All call sites that currently inject `CurrentUserSchemaAccess` switch
  injection to `LlmExposurePolicy` mechanically; the new policy method shape mirrors
  the wrapped class.

- **D-02:** Method surface for Phase 10:
  - `Map<MetaClass, Set<String>> getReadableSchema()` — returns the Jmix-readable
    schema with denylisted entities removed entirely (entity-level subtraction; the
    user's per-attribute readable set is preserved verbatim for entities that survive
    the entity-level filter).
  - `boolean canReadEntity(MetaClass)` — `userCanRead AND NOT entityExcluded`.
  - `boolean canReadAttribute(MetaClass, String)` — pure pass-through to the wrapped
    `CurrentUserSchemaAccess.canReadAttribute(...)` for v1.1; no attribute-level
    exposure narrowing.
  - `boolean canModify(MetaClass)` — `userCanModify AND NOT entityExcluded`.
    **Ships in Phase 10** so Phase 11 mutation gating step 1 wires cleanly. No Phase
    10 caller consumes it.

- **D-03:** Three primary call-site swaps (no behavioral change beyond rule
  application):
  - `BuiltInDataTools` — every reference (`getReadableSchema`, `canReadEntity`,
    `canReadAttribute`) repointed at `LlmExposurePolicy`. The `unknown_entity`
    error path stays verbatim per Phase 9 D-14 hints; the only change is that
    the resolution helper now delegates through the policy.
  - `BaselineContextProvider` — single field swap (`currentUserSchemaAccess` →
    `llmExposurePolicy`); call shape unchanged. `agent.entities` /
    `agent.permissions` automatically narrow because both source the same
    `getReadableSchema()` call (Plan 09-03 design intent).
  - `FetchPlanIntersector` (Phase 9) — uses the policy's `canReadAttribute`
    pass-through; no semantics change for v1.1 since attribute-level rules are
    out of scope, but the indirection is in place for the later milestone.

### RAG governance + reingestion contract

- **D-04:** **Vector-store chunk metadata is the retrieval authority.**
  `AiKnowledgeDocument.allowedRolesJson` (existing) and the new
  `AiKnowledgeDocument.sourceEntityName` (nullable) are admin metadata persisted on
  the document row. They DO NOT directly gate retrieval — `RetrievalFilterBuilder`
  reads `role_<code>` flags and `source_entity` from chunk metadata, not from
  `AiKnowledgeDocument`. Therefore any post-ingest change to either field MUST
  trigger document reingestion, which deletes existing chunks and re-runs the
  ingester so chunk metadata is rewritten.

- **D-05:** `RetrievalFilterBuilder.buildFor(Authentication)` adds a
  `source_entity NOT IN <denylisted>` clause to the existing model-pin + role
  filter. Specifically:
  - Look up the current denylist via `LlmExposurePolicy.getDenylistedEntityNames()`
    (new helper returning the set of `MetaClass.getName()` strings whose rule has
    `enabled=true` and `mode=EXCLUDE`).
  - For non-empty denylist, AND a `NOT IN` Spring AI `Filter` clause onto the
    existing expression.
  - Empty denylist → no extra clause (zero overhead).
  - Admin bypass branch (`AiAgentAdminRole` + `admin-bypass=true`) returns `null`
    as today — admins see all chunks (denylist is for the LLM-visible surface,
    not the admin debug view).

- **D-06:** Chunks ingested without a `source_entity` metadata key are NOT
  affected by entity denylist rules — `NOT IN` matches only chunks whose key is
  set AND in the denied set. This is the deliberate v1.1 contract (legacy docs
  remain visible until reingested with the new field). Document this explicitly
  in operator docs; defer document-id allowlist to v1.2.

- **D-07:** KB upload UX collects `allowedRoles` (existing) + new optional
  `sourceEntityName` (metaclass dropdown — same pattern as the `AiExposureRule`
  detail) BEFORE ingestion starts. Both persist on `AiKnowledgeDocument` and
  mirror into chunk metadata during the same ingest run via
  `AsyncIngestionWorker` / `IngesterManager`.

- **D-08:** **Reingest, never mutate pgvector metadata directly.** When admin
  edits `allowedRoles` or `sourceEntityName` via the KB list-view row action,
  the save handler:
  1. Updates the `AiKnowledgeDocument` row (committed via `DataManager.save`
     under existing security).
  2. Schedules a reingest via the existing async path: delete chunks for the
     document id, re-run ingester. Idempotent.

  Add an explicit "Reingest" row action that runs the same path on demand
  (recovery from partial ingests, model upgrades, role-set drift). MEMORY rule
  saved: post-ingest changes to permission/source-entity fields reingest.

- **D-09:** **New Vector Store debug view** (`VectorStoreDebugView`) —
  admin-only, modeled on `jmix-ai-backend`. Vaadin Grid bound to a custom
  `DataProvider` over `VectorStore.similaritySearch(SearchRequest)` /
  `VectorStore.delete(...)` reads. Columns: chunk id, content (truncated cell
  with "show full" expand), metadata (compact JSON cell). Filter input field
  collects a metadata-filter expression (Spring AI `Filter.Expression` parsed
  via `FilterExpressionTextParser` if available in 1.1.4; otherwise a
  property+value form builder). **Read-only / paginated**; no edit / delete
  action in v1.1. Inspection / debugging only — primary governance UI remains
  `KnowledgeBaseView` rows + `AiExposureRuleListView`.

### Admin UI shape

- **D-10:** `AiExposureRuleListView` — single list view with `genericFilter` +
  `propertyFilter` over `entityName` / `enabled` / audit fields. Action column
  (`@Supply(to="grid.col", subject="renderer")` per MEMORY
  `feedback_jmix_action_column_renderer`) renders ONE button per row whose
  label/icon flips between "Hide from AI" / "Visible to AI" based on `enabled`.
  Save flips the bit via `UnconstrainedDataManager` and publishes
  `LlmExposureChangedEvent`. Built-in list actions (create / edit / remove)
  declared via `list_itemTracking` per MEMORY `feedback_jmix_list_item_tracking`.

- **D-11:** `AiExposureRuleDetailView` — `entityName` is a Vaadin `ComboBox`
  populated from `Metadata.getSession().getClasses()` filtered to:
  - exclude `@SystemLevel` entities (matches `CurrentUserSchemaAccess`
    `isSystemLevelEntity` rule);
  - exclude AI-* internal entities (`AiAuditEvent`, `AiConversation`, `AiMessage`,
    `AiKnowledgeDocument`, `AiParameters`, `AiExposureRule` itself) — denylisting
    the denylist UI would be surreal and bricks admin governance.
  Same dropdown component (extracted helper) populates
  `AiKnowledgeDocument.sourceEntityName` in the KB upload + edit forms.

- **D-12:** Menu placement — new menu id `aiAgent.exposureRules.list` under the
  existing AI admin menu section, alongside `aiAgent.parameters.list` and
  `aiAgent.audit.list`. New menu id `aiAgent.vectorStoreDebug` for the debug
  view, placed last in the admin section. Both gated to `AiAgentAdminRole` via
  `@MenuPolicy` + `@ViewPolicy`.

- **D-13:** All UI strings (entity captions, attribute labels, action labels
  "Hide from AI" / "Visible to AI" / "Reingest", dropdown empty-state, debug
  view filter help, menu titles) added to BOTH locale bundles
  (`messages.properties` + `messages_vi.properties`) per CLAUDE.md "ALL locale
  files" rule. `io.jmix.core.Messages` injection per MEMORY
  `feedback_jmix_messages_over_spring`.

### Cache + event invalidation

- **D-14:** **No cache anywhere in v1.1.** `LlmExposurePolicy` looks up rules
  per call via `LlmExposureRuleRepository` (`UnconstrainedDataManager`).
  Expected rule count <50; query latency <5ms; LLM round-trip dwarfs it. Matches
  Phase 9 stateless `@Component` pattern (D-15) and `CurrentUserSchemaAccess`
  no-cache rule. Vector Store debug view paginates live via
  `VectorStore.similaritySearch` — also no cache.

- **D-15:** `LlmExposureChangedEvent` is published on every rule
  create / update / delete (`@PostPersist` / `@PostUpdate` / `@PostRemove`
  Jmix entity listener under `agentstore`). **No current consumer in v1.1.**
  Documented in CONTEXT and in the event class Javadoc to prevent confusion;
  Phase 12+ caching wires the listener cleanly. Multi-instance HA caveat
  (in-process event only) is noted in deferred ideas, not blocking v1.1.

### Claude's Discretion

- Exact package layout: `com.vn.agent.exposure` (entity, policy,
  repository, event) vs. distributing across existing packages
  (`com.vn.agent.metadata` for the policy alongside `CurrentUserSchemaAccess`,
  `com.vn.agent.entity` for the entity, etc.). Planner picks; favor a single
  cohesive `exposure` package since the boundary is conceptually unified.
- Concrete repository signature for `LlmExposureRuleRepository` —
  `Set<String> findEnabledExcludedEntityNames()` vs. `List<AiExposureRule>
  findEnabled()` returning fuller rows for audit purposes. Planner picks; the
  set-of-names shape is sufficient for the entity-only v1.1 contract.
- Whether the `LlmExposurePolicy.getDenylistedEntityNames()` helper is a public
  method or an internal package-private helper used only by
  `RetrievalFilterBuilder`. Planner picks; `RetrievalFilterBuilder` is the only
  external consumer in v1.1 so package-private is fine.
- Liquibase changelog placement (under `agentstore-changelog/` numbered
  `060-ai-exposure-rule.xml`) and FK / index choices on `entity_name` /
  `enabled`. Planner picks per Phase 7.2 conventions.
- Reingest scheduling shape — direct call to `AsyncIngestionWorker.enqueue(...)`
  vs. publishing a Spring event consumed by the worker. Planner picks; direct
  call mirrors existing KB upload path.
- Vector Store debug view filter input — text-based Spring AI
  `FilterExpressionTextParser` (if 1.1.4 ships it) vs. property+value form
  builder. Planner verifies via Context7; falls back to form builder if the
  text parser is absent / brittle in 1.1.4.
- Whether `AiExposureRule.entityName` carries a JPA `@Column(unique=true)`
  constraint or duplicates are tolerated (UI prevents them via dropdown +
  validator). Planner picks; uniqueness on `(entityName)` enforces the
  one-rule-per-entity contract cleanly.
- TEST-09 harness shape — single `@SpringBootTest` with two seeded rules
  (one entity excluded, one not) parameterized over the four assertion paths
  (`list_entities`, `agent.entities`, RAG, `find_records`) vs. four narrow
  tests. Planner picks per existing test-suite conventions.

### Folded Todos

No pending todos in `.planning/todos/pending/` map to Phase 10 (all six v1.1
todos folded into Phase 9 per Phase 9 CONTEXT). Phase 10 scope is fully
REQ-driven plus the user-elevated KB governance / debug-view additions
captured above.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 10: AI-Specific LLM Exposure Policy" — goal,
  success criteria #1..#4, dependencies, requirements list.
- `.planning/REQUIREMENTS.md` — `EXP-01..EXP-10`, `ENT-05`, `SEC-05` (extension
  for `AiExposureRule`), `TEST-09`. Authoritative for scope. Note: this CONTEXT
  drops `attributePath` from EXP-01 per user decision 2026-04-27 (entity-level
  only) — REQUIREMENTS.md may need an editorial pass post-phase, or the planner
  notes the deviation explicitly in PLAN.md.
- `.planning/PROJECT.md` §"Current Milestone v1.1.0" — value prop and explicit
  in/out-of-scope. Note that PROJECT.md "Constraints" still records the v1.0
  no-AI-exposure-layer stance per D-10; v1.1 supersedes that with this phase.
- `.planning/STATE.md` — Phase 9 complete; ready to plan Phase 10.

### Prior phase context (load before planning)
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-CONTEXT.md` —
  D-15 substitution-seam framing, the single-call-site rule for
  `currentUserSchemaAccess.getReadableSchema()`, and the `BaselineContextProvider`
  shape that Phase 10 mechanically migrates. Plan 09-03 design intent: same
  capped-and-sorted entity list drives both `agent.entities` and
  `agent.permissions` so denial cannot leak via permissions.
- `.planning/milestones/v1.0.0-phases/02-foundations/02-CONTEXT.md` §D-10 — the
  original "AI is just another Jmix client" decision that v1.1 supersedes. Read
  to understand the historical context that led to deferring exposure rules to
  v1.1.
- `.planning/milestones/v1.0.0-phases/05-rag-layer/` (any CONTEXT.md / PLAN
  artifacts) — `RetrievalFilterBuilder` / chunk metadata contract that EXP-05
  extends.
- `.planning/milestones/v1.0.0-phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` —
  D-08 "access denied = entity does not exist" opacity rule that TEST-09
  enforces uniformly across all four assertion paths.

### Add-on source touch points
- `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` —
  the wrapped class (D-01). Stays as the Jmix-permission source of truth.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` —
  injection swap site (D-03). Single field rename; call shape unchanged.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` —
  every `currentUserSchemaAccess` reference repointed (D-03). Keep
  `unknown_entity` hint strings byte-for-byte per Phase 9 D-14.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` —
  Phase 9 consumer; uses the policy's pass-through `canReadAttribute` for v1.1.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java` —
  EXP-05 extension site (D-05). Add `source_entity NOT IN <denylisted>` clause.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java` +
  `IngesterManager.java` + `KnowledgeDocumentUploadService.java` — reingest
  scheduling on `AiKnowledgeDocument` edits (D-08); chunk metadata mirroring
  for `source_entity` (D-07).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java` — add a
  `SOURCE_ENTITY` constant alongside existing `ROLE_*` keys (D-05). MEMORY
  rule: ingestion writer + filter builder must mirror this constant.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java` —
  add `sourceEntityName` (nullable String, indexed). Liquibase changelog
  alongside `050-ai-knowledge-document.xml` adds the column.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml` +
  controller — upload form additions (D-07), row actions for permissions/
  source-entity edit and explicit Reingest (D-08).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` —
  extend `@EntityPolicy(entityClass = AiExposureRule.class, ...)`,
  `@MenuPolicy(menuIds = {... "aiAgent.exposureRules.list",
  "aiAgent.vectorStoreDebug"})`, `@ViewPolicy(viewIds = {...
  "AiAgent_AiExposureRule.list", "AiAgent_AiExposureRule.detail",
  "AiAgent_VectorStoreDebug"})`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/` —
  new `060-ai-exposure-rule.xml` (and `061-ai-knowledge-document-source-entity.xml`
  for the doc column add). Include both in the parent `agentstore-changelog.xml`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` +
  `messages_vi.properties` — all new UI strings (D-13). NEVER hardcode.

### New code to create (planner sketches package layout)
- `com.vn.agent.exposure.AiExposureRule` — JPA entity (D-01).
- `com.vn.agent.exposure.LlmExposurePolicy` — `@Component` boundary (D-01, D-02).
- `com.vn.agent.exposure.LlmExposureRuleRepository` — `UnconstrainedDataManager`
  reads (D-14, D-15).
- `com.vn.agent.exposure.LlmExposureChangedEvent` — Spring application event
  (D-15).
- New views: `AiAgent_AiExposureRule.list` / `.detail`, `AiAgent_VectorStoreDebug`.

### Reference implementation (pattern-learning, NOT a dependency)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Vector Store
  debug view shape (read-only paginated grid + metadata filter). D-09 models on it;
  reuse the column / pagination pattern, do NOT copy domain-specific bits.

### Project conventions
- `CLAUDE.md` — ALL locale files for new strings, UUID + Version + InstanceName
  on `AiExposureRule`, `DataManager` only, no `EntityManager`, JetBrains MCP
  `get_file_problems` after Java work.
- MEMORY (`C:\Users\admin\.claude\projects\D--DTH-ai-agent-core\memory\`):
  - `feedback_kb_reingest_default_path.md` — **NEW**, saved 2026-04-27. Reingest is
    the default path for KB chunk metadata changes.
  - `feedback_jmix_action_column_renderer.md` — `@Supply` pattern for the
    enable/disable toggle in `AiExposureRuleListView` (D-10).
  - `feedback_jmix_list_item_tracking.md` — built-in list actions binding for
    create/edit/remove buttons.
  - `feedback_jmix_generic_filter.md` — `genericFilter` + `propertyFilter`
    over hand-built bars (EXP-07).
  - `feedback_jmix_unconstrained_for_system_writes.md` — `UnconstrainedDataManager`
    rationale for the rule repository (EXP-06, D-14).
  - `feedback_jmix_loadvalue_store.md` — explicit `.store("agentstore")` for any
    raw JPQL `loadValue` against AI entities.
  - `feedback_jmix_messages_over_spring.md` — `io.jmix.core.Messages` over
    Spring `MessageSource` in views.
  - `feedback_ai_as_jmix_client.md` — the v1.0 stance now superseded by Phase 10.
    Read so Phase 10 commentary correctly frames this as "concrete consumer use
    case surfaced; AI exposure layer activated".

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — `@JmixEntity` + UUID + `@Version` + `@InstanceName` for
  `AiExposureRule`; metaclass enumeration for the dropdown (D-11).
- `jmix-services` — `DataManager` / `UnconstrainedDataManager` rule
  repository (EXP-06).
- `jmix-views` — list / detail view conventions; `@Subscribe` event wiring;
  `@Install` `Renderer` for action column (D-10).
- `jmix-i18n` — message bundles for all new UI strings (D-13).
- `jmix-liquibase` — changelog conventions for the new entity (`060-...xml`)
  and the `AiKnowledgeDocument` column add.
- `jmix-security-roles` — `@EntityPolicy` / `@MenuPolicy` / `@ViewPolicy`
  extension on `AiAgentAdminRole` (SEC-05).
- `jmix-testing` — `@SpringBootTest` + `agentstore` test-data setup for TEST-09.

### Spring AI primitives to verify in research
- Spring AI 1.1.4 `Filter.Expression` `NOT IN` clause shape via
  `FilterExpressionBuilder` — confirm via Context7
  (`/spring-ai/spring-ai`) before planner finalizes the
  `RetrievalFilterBuilder` extension. The current builder uses `b.eq` + `b.and`
  + `b.or` only (Plan 05-02); `NOT IN` may need a different idiom.
- `VectorStore.similaritySearch(SearchRequest)` shape and how to enumerate
  chunks for the debug view without retraining the embedding (need a
  `SearchRequest` with a permissive query embedding or a separate enumeration
  path). Verify via Context7 or jmix-ai-backend reference.
- Whether 1.1.4 ships `FilterExpressionTextParser` for the debug-view filter
  input (D-09); fall back to property+value form builder if missing.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CurrentUserSchemaAccess` (`com.vn.agent.metadata`) — wrapped by
  `LlmExposurePolicy` per D-01. Three methods (`getReadableSchema`,
  `canReadEntity`, `canReadAttribute`) become the policy's delegate targets.
  Stays untouched in Phase 10 — its tests, comments, and Phase 9 references
  stay valid.
- `BaselineContextProvider` (`com.vn.agent.orchestration`) — Plan 09-03 design
  put the `getReadableSchema()` call on a single line; D-03 swap is one field
  rename. `agent.entities` + `agent.permissions` automatically narrow because
  both source the same call (Phase 9 explicit guarantee).
- `RetrievalFilterBuilder` (`com.vn.agent.rag`) — already builds composite
  filter expressions via `FilterExpressionBuilder` with per-role flag clauses
  + model-pin AND. D-05 adds one `NOT IN` clause; admin-bypass branch
  preserved.
- `ChunkMetadata` (`com.vn.agent.rag`) — central constants for chunk metadata
  keys; add `SOURCE_ENTITY` constant alongside existing `ROLE_*` and
  `EMBEDDING_MODEL` keys.
- `AsyncIngestionWorker` + `IngesterManager` + `KnowledgeDocumentUploadService` —
  existing async ingest path. D-07 mirrors `sourceEntityName` into chunk
  metadata at write time; D-08 reuses the same path for reingest on edit.
- `AiAgentAdminRole` (`com.vn.agent.security`) — extension site for SEC-05;
  pattern is explicit `entityClass=` per existing convention.
- `FetchPlanIntersector` (`com.vn.agent.tools.fetchplan`, Plan 09-04) —
  consumes the policy's `canReadAttribute` pass-through. No semantics change
  in v1.1 since attribute-level rules are out of scope.
- `KnowledgeBaseView` (existing) — UI extension site for D-07 (upload form),
  D-08 (row actions). Existing `genericFilter` shape per MEMORY rule.
- `AuditWriter` (existing) — already writes `outcome=PLAN_NARROWED` from Phase
  9. Phase 10 may emit additional audit rows on rule changes via the new
  `LlmExposureChangedEvent` listener (planner decides; not strictly required
  by EXP-08).

### Established Patterns
- **Namespace:** `com.vn.agent.*`. Phase 10 may add `com.vn.agent.exposure.*`
  (entity, policy, repository, event). Planner picks final layout.
- **agentstore datasource:** `@Store(name = "agentstore")` per existing
  AI-* entities. `@JmixEntity`, UUID + `@JmixGeneratedValue` + `@Version` +
  `@InstanceName` per CLAUDE.md.
- **UnconstrainedDataManager:** rule repository reads bypass user-level
  data security per EXP-06; matches MEMORY
  `feedback_jmix_unconstrained_for_system_writes`.
- **List view filter:** `genericFilter` + `propertyFilter` (MEMORY).
- **Action column:** `@Supply(to="grid.col", subject="renderer")` per MEMORY
  `feedback_jmix_action_column_renderer`. Renderer factory builds an
  enable/disable toggle button.
- **Listeners:** `@Subscribe` / `@Install` per MEMORY
  `feedback_jmix_view_listeners`; verify uncertain syntax via Context7 →
  Jmix docs → GitHub.
- **Locales:** every new UI string in BOTH `messages.properties` and
  `messages_vi.properties` (CLAUDE.md).
- **Liquibase:** changelogs hierarchical or numeric under
  `liquibase/agentstore-changelog/`; include in parent `agentstore-changelog.xml`.

### Integration Points
- `LlmExposurePolicy` is on the hot path for every chat turn — D-14 pays one
  extra `UnconstrainedDataManager` lookup per request, marginal compared to
  the LLM round trip and the existing `getReadableSchema()` walk.
- `RetrievalFilterBuilder.buildFor(...)` is called from `DefaultChatServiceImpl`
  per RAG-augmented turn. D-05 adds one extra clause when the denylist is
  non-empty.
- `AsyncIngestionWorker` runs out-of-band; KB row-action edits enqueue and
  return immediately. Reingest is idempotent (delete chunks for doc id +
  re-run ingester).
- `LlmExposureChangedEvent` has no current consumer in v1.1 — published-only.
  Phase 12+ caching wires the listener.
- `agentstore` separate datasource — Phase 10 ADDS one new table
  (`AI_EXPOSURE_RULE`) and one new column on `AI_KNOWLEDGE_DOCUMENT`. No
  schema churn elsewhere.

</code_context>

<specifics>
## Specific Ideas

- **Wrap, don't replace.** `LlmExposurePolicy` is a thin
  delegate-and-narrow over `CurrentUserSchemaAccess`. Phase 9 tests, MEMORY
  references, and the "fetch plan is projection, not security" comment all
  stay valid because the wrapped class doesn't move.
- **Entity-level only for v1.1.** `AiExposureRule` has NO `attributePath`
  field. Attribute-level rules deferred. Planner must NOT add `attributePath`
  even if REQUIREMENTS EXP-01 still mentions it (this CONTEXT supersedes;
  planner notes the deviation in PLAN.md).
- **Chunk metadata is the retrieval authority, not the document row.** Any
  field mirrored into chunk metadata (`allowedRoles`, `sourceEntityName`)
  reingests on edit. Direct pgvector metadata mutation is explicitly rejected.
- **Reuse the metaclass dropdown across both UIs.** Same filter rules
  (no `@SystemLevel`, no AI-* internals). Extract once, reuse in
  `AiExposureRuleDetailView` and the `KnowledgeBaseView` upload + edit forms.
- **`canModify(MetaClass)` ships in Phase 10** so Phase 11 mutation gating
  step 1 is a one-line wire-in. No Phase 10 caller consumes it.
- **`LlmExposureChangedEvent` ships in Phase 10 with NO consumer.** Documented
  in event class Javadoc + this CONTEXT to prevent reviewer confusion. Phase
  12+ caching activates the listener.
- **Vector Store debug view is read-only.** No edit / delete actions; modeled
  on `jmix-ai-backend` reference UI. Filter-input shape verified against
  Spring AI 1.1.4 via Context7 before planner picks text-parser vs.
  form-builder.
- **TEST-09 covers ALL FOUR opacity paths.** `list_entities`,
  `agent.entities`, RAG hits (chunk presence after a denylist match), AND
  `find_records(entity=denylisted)` returning `unknown_entity` — never
  `access_denied`. Single seeded denylist + parameterized assertions.
- **All new UI strings in BOTH locales.** "Hide from AI" / "Visible to AI" /
  "Reingest" / debug-view filter help / metaclass dropdown empty state — every
  one in `messages.properties` AND `messages_vi.properties`.

</specifics>

<deferred>
## Deferred Ideas

- **Attribute-level exposure rules** (`attributePath` field, dotted paths,
  prefix/wildcard matching) — deferred to a later milestone (user decision
  2026-04-27). When activated, `LlmExposurePolicy.canReadAttribute` stops
  being a pass-through and `AiExposureRule` gains `attributePath`. The boundary
  shape is forward-compatible.
- **Document-id allowlist UI** for legacy KB documents ingested without
  `sourceEntityName` — deferred to v1.2. v1.1 contract: legacy chunks with no
  `source_entity` metadata are simply not entity-denylistable until reingested.
- **Compiled-rule cache + multi-instance HA invalidation** — `LlmExposureChangedEvent`
  is in-process; clustered deployments would see stale caches. Defer until
  v1.1+ telemetry shows per-request DB lookup is a measurable hot spot.
- **Time-bounded exposure rules (auto-expire after date)** — `EXP-11` per
  REQUIREMENTS Future Requirements; deferred until governance demand surfaces.
- **`MutationGuard` SPI + mutation tools** — Phase 11.
- **`AiUiSettings` admin policies** — Phase 12 (paired with configurable chat
  surfaces); SEC-05 partially lands here, fully completes in Phase 12.
- **Edit / delete actions on the Vector Store debug view** — v1.1 ships
  read-only only. Mutation actions (re-embed a chunk, force-delete) defer
  pending concrete operator demand.
- **Audit rows on rule changes** beyond the standard Jmix entity audit — not
  required by EXP-08 (Spring event suffices). Defer unless a host requests
  per-change audit detail.
- **Document-id-level RAG denylist** (separate from entity-level) — defer to
  v1.2; covers the legacy-document case.
- **Attribute-aware `agent.permissions` truncation** when an entity is
  partially excluded — deferred with attribute-level rules.

### Reviewed Todos (not folded)

None — no pending todos in `.planning/todos/pending/` map to Phase 10 (all
v1.1 todos folded into Phase 9).

</deferred>

---

*Phase: 10-ai-specific-llm-exposure-policy*
*Context gathered: 2026-04-27*
