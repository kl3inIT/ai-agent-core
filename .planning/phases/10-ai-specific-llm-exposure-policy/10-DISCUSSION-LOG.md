# Phase 10: AI-Specific LLM Exposure Policy - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-27
**Phase:** 10-ai-specific-llm-exposure-policy
**Areas discussed:** Rule scope (entity-only vs attribute-level), Boundary surface + call-site migration, RAG source-entity binding (EXP-05) + KB governance, Admin UI shape, Cache + event invalidation
**Mode:** Advisor (USER-PROFILE.md present, vendor_philosophy=thorough-evaluator → full_maturity tier). Advisor agent unregistered; comparison tables synthesized inline against codebase + Phase 9 conventions + MEMORY.

---

## Rule Scope (entity-level vs attribute-level)

| Option | Description | Selected |
|--------|-------------|----------|
| Entity + attribute-level rules | EXP-01 verbatim — `attributePath` nullable, dotted paths, prefix or wildcard matching options | |
| **Entity-level only for v1.1** | Drop `attributePath` from `AiExposureRule` entirely; rule excludes whole entities; `LlmExposurePolicy.canReadAttribute` is a pure pass-through to `CurrentUserSchemaAccess` | ✓ |

**User's choice:** Entity-level only.
**Notes:** User opened with this clarification before the gray-area selection: "For this milestone, keep LlmExposurePolicy entity-level only. We do not need attribute-level rules, dotted attributePath semantics, relationship prefix matching, or wildcards yet." Locks REQ EXP-01 deviation; planner notes in PLAN.md. Attribute-level rules deferred to a later milestone.

---

## Boundary surface + call-site migration

| Option | Description | Selected |
|--------|-------------|----------|
| **Wrap (delegate-and-narrow) (Recommended)** | `LlmExposurePolicy @Component` wraps `CurrentUserSchemaAccess`, exposes same 3 methods + `canModify(MetaClass)` for Phase 11. Mechanical injection swap at all call sites; Phase 9 tests untouched. | ✓ |
| Replace (rename) | Rename/fold `CurrentUserSchemaAccess` into `LlmExposurePolicy`. Churns Phase 9 tests, comments, CONTEXT D-11 wording. | |
| New SPI seam | `SchemaExposureSource` interface + two impls picked by property. Premature per MEMORY 'SPIs only for app-specific behavior'. | |

**User's choice:** Wrap (delegate-and-narrow).
**Notes:** Matches the Phase 9 substitution-seam framing exactly (CONTEXT 09 D-15). `canModify(MetaClass)` ships in Phase 10 for Phase 11 to wire cleanly; no Phase 10 caller consumes it.

---

## RAG source-entity binding (EXP-05) + KB governance

| Option | Description | Selected |
|--------|-------------|----------|
| **Doc-level field at upload time + reingest-on-edit (extended)** | Add nullable `sourceEntityName` to `AiKnowledgeDocument`; mirror to chunk metadata `source_entity` at ingest; `RetrievalFilterBuilder` adds `NOT IN` clause. Post-ingest edits to `allowedRoles` or `sourceEntityName` REINGEST the document (rewrite chunk metadata) — never mutate pgvector metadata directly. KB upload UX collects `allowedRoles` + `sourceEntityName` BEFORE ingest. KB list view gains row actions for edit-permissions and explicit Reingest. New admin-only Vector Store debug view (read-only paginated grid + metadata filter). | ✓ |
| Query-time post-filter | Fetch top-K, drop denylisted chunks via JDBC lookup of source doc, fetch more if needed. Latency cost; coupling between retrieval and `AiKnowledgeDocument`. | |
| Auto-derive from filename | Heuristic classification. Brittle; opposite of denylist intent. | |
| Don't bind — leave EXP-05 unmet | Drop EXP-05 from Phase 10. REQ explicitly mandates it; admins lose RAG governance. | |

**User's choice:** Doc-level field at upload time + reingest-on-edit (extended).
**Notes:** User materially extended the recommended option mid-discussion: "Knowledge document permissions are editable after ingestion, but `AiKnowledgeDocument.allowedRolesJson` is not the retrieval authority by itself. Retrieval filters read role flags from vector-store chunk metadata, so any post-ingest change to document roles or sourceEntityName must trigger reingestion of that document to rewrite chunk metadata. Phase 10 should implement this as the default path, not direct pgvector metadata mutation." Plus: "When uploading a Knowledge Base document, the UI must also let the admin set permissions immediately: allowedRoles and optional sourceEntityName should be chosen before ingestion starts, then persisted on AiKnowledgeDocument and mirrored into chunk metadata during ingestion." Plus: "add an admin-only Vector Store debug view similar to jmix-ai-backend: a read-only/paginated grid over vector-store chunks showing id, content, and metadata, with metadata filter support. This view is for inspection/debugging only, not the primary permission-management UI. The primary governance UI remains the Knowledge Base document table with row actions for permissions/source entity and reingest." Saved as MEMORY rule `feedback_kb_reingest_default_path.md` since this is a project-pattern decision (chunk metadata is the retrieval authority; reingest is the propagation mechanism).

---

## Admin UI shape

| Option | Description | Selected |
|--------|-------------|----------|
| **Metaclass dropdown across the board (Recommended)** | `AiExposureRule.entityName` + `AiKnowledgeDocument.sourceEntityName` both pick from the same `Metadata.getSession().getClasses()` dropdown (filtered: drop `@SystemLevel` + AI-* internals). Vector Store debug view = Vaadin Grid bound to a custom `DataProvider` over `VectorStore.similaritySearch` with metadata-filter input. | ✓ |
| Metaclass dropdown for rules only; free-text for KB sourceEntityName | Asymmetric — admins type one place, pick another. | |
| Validated free-text everywhere | TextField + JPA pre-persist validator hitting `Metadata.getClass(name)`. Same code as dropdown but worse UX. | |

**User's choice:** Metaclass dropdown across the board.
**Notes:** Single shared dropdown helper extracted; reused in `AiExposureRuleDetailView` and `KnowledgeBaseView` upload + edit forms. Action column uses `@Supply` renderer per MEMORY `feedback_jmix_action_column_renderer`; one button per row whose label/icon flips based on `enabled`. Menu placement under the existing AI admin section.

---

## Cache + event invalidation

| Option | Description | Selected |
|--------|-------------|----------|
| **No cache anywhere (Recommended)** | `AiExposureRule` lookups hit `agentstore` per chat turn via `UnconstrainedDataManager` (rules <50 rows, query <5ms). Vector Store debug view paginates live via `VectorStore`. `LlmExposureChangedEvent` published per EXP-08 with no current consumer; documented for Phase 12+ caching. | ✓ |
| App-wide @EventListener cache for AiExposureRule only | Cache denylisted entity name set, refreshed on `LlmExposureChangedEvent` + `ApplicationReadyEvent`. Multi-instance HA caveat (event is in-process). | |
| Per-RunContext memoization | Compute rule set once per chat turn, memoize. `RunContext` is final + private constructor; requires extension. | |

**User's choice:** No cache anywhere.
**Notes:** Matches Phase 9 stateless `@Component` pattern. Event ships per EXP-08 with no v1.1 consumer; documented in event Javadoc + CONTEXT to prevent reviewer confusion.

---

## Claude's Discretion

Areas explicitly delegated to the planner (per CONTEXT.md decisions section):

- Final package layout (`com.vn.agent.exposure` vs distributed across existing packages).
- Concrete `LlmExposureRuleRepository` signature shape (set-of-names vs full-row list).
- Whether `LlmExposurePolicy.getDenylistedEntityNames()` is public or package-private.
- Liquibase changelog placement / FK / index choices on `entity_name` / `enabled`.
- Reingest scheduling shape (direct call to `AsyncIngestionWorker.enqueue` vs Spring event).
- Vector Store debug view filter input — `FilterExpressionTextParser` if 1.1.4 ships it (verify via Context7), else property+value form builder.
- Whether `AiExposureRule.entityName` carries `@Column(unique=true)` (recommended).
- TEST-09 harness shape (single parameterized vs four narrow tests).

---

## Deferred Ideas

- **Attribute-level exposure rules** — `attributePath` field, dotted paths, prefix/wildcard matching. Deferred to a later milestone per explicit user decision.
- **Document-id allowlist UI** for legacy KB docs ingested without `sourceEntityName` — v1.2.
- **Compiled-rule cache + multi-instance HA invalidation** — defer until telemetry signal.
- **Time-bounded exposure rules (auto-expire)** — EXP-11 per REQUIREMENTS Future Requirements.
- **`MutationGuard` SPI + mutation tools** — Phase 11.
- **`AiUiSettings` admin policies** — Phase 12.
- **Edit / delete actions on Vector Store debug view** — defer pending operator demand.
- **Per-rule audit rows beyond standard Jmix entity audit** — defer.
- **Document-id-level RAG denylist** — v1.2.
- **Attribute-aware `agent.permissions` truncation** for partially excluded entities — deferred with attribute-level rules.

---

## Memory updates

- **NEW:** `feedback_kb_reingest_default_path.md` — saved 2026-04-27. Post-ingest changes to `AiKnowledgeDocument` permission/source-entity fields trigger reingestion; never mutate pgvector chunk metadata directly. Indexed in `MEMORY.md`.
