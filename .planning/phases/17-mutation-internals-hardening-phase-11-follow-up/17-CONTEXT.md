# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) - Context

**Gathered:** 2026-05-30
**Status:** Ready for planning

<domain>
## Phase Boundary

Internal refactor of the Phase 11 LLM-mediated mutation surface: consolidate the duplicated fail-closed gate sequence into one canonical `MutationGateChain`, batch-load to-one FK references, and memoize related-write metadata resolution. **Behavior is frozen byte-for-byte** — Phase 9/10/11 mutation test suites pass unchanged. No new user-facing surface, no behavior changes.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**4 requirements are locked.** See `17-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `17-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- A `MutationGateChain` `@Component` encapsulating the canonical fail-closed sequence.
- `create_record` / `update_record` / `add_related_record` / `remove_related_record` / `bulk_save_records` reduced to thin adapters over the chain.
- A source-level invariant test for gate order + "all gates throw before transactional save" + "no `@Transactional` on the chain".
- Batch FK loading: cross-row batching for `bulk_save_records` (one constrained `.ids(...)` load per target class), single-call dedup for `create`/`update`.
- A SELECT-count proxy ("1 query not N") and the static "constrained-DataManager-only / no-raw-JPQL" assertion for the FK path.
- Memoization of `(parentMetaClass, relationshipName)` → descriptor in `RelatedWriteMetadataResolver`, with a call-count proxy.
- Low-risk adjacent internal improvements **only when** all three hold: no new public surface, byte-for-byte behavior preserved, existing suites pass unchanged.

**Out of scope (from SPEC.md):**
- Per-turn / app-wide memoization of schema / `AccessManager` / `LlmExposurePolicy` / denylist / RAG / media / prompt — that is Phase 18 (PERF-01/02).
- Any new admin UI, config knob, `@Tool`, or `@Tool`/`@ToolParam` description change.
- `delete_record` (D-07).
- Any change to gating outcomes, error codes, audit row shape, or idempotency semantics.
- Editing the bodies of the Phase 9/10/11 mutation test suites.
- Benchmark harness or admin-screen performance work.

</spec_lock>

<decisions>
## Implementation Decisions

### Gate chain shape & adapter API (MUT-15)
- **D-01:** Model `MutationGateChain` as a **template-method `@Component`** with a single `execute(MutationRequest)` entry point that calls explicit, **ordered, named private gate methods** (enforceRole → resolve → authorize → reserve → coerce → guard → save → finalize). The ordered call list is the enabler for the mandated source-level ORDER invariant.
- **D-02:** Introduce a **sealed `MutationRequest` hierarchy** — `Create` / `Update` / `AddRelated` / `RemoveRelated` / `Bulk` — dispatched via a `switch`. The sealed variants localize the two real points of divergence (bulk's per-row coerce/`CrudEntityContext`/guard loop + `SaveContext`/`bulkSave`; per-tool `argumentsJson` serializer + success-result shape) without forking the shared spine.
- **D-03:** The five `@Tool` methods become thin adapters: build the `MutationRequest`, call `execute`, format the result. The chain orchestrates the **existing** collaborators (`MutationAuthorizationService`, `MutationAttributeBinder`, `MutationRequestHasher`, `MutationIntentRepository`, `MutationSaveExecutor`, `MutationCommitCoordinator`, `MutationErrorTranslator`, `RelatedWriteMetadataResolver`, `DiffSerializer`) — it does not replace them.
- **D-04:** **No `@Transactional` on `MutationGateChain`** (Spring self-invocation pitfall). Only `MutationSaveExecutor.save/saveAll/bulkSave` crosses the proxy boundary; every gate throws before that call.
- **D-05:** The single `resolve`/load gate is the documented seam for Phase 18's per-turn memoization. Keep it a clean single point so Phase 18 refactors the consolidated chain, not duplicated code.
- *Rejected:* chain-of-responsibility (`List<Gate>`) and functional pipeline — both demote the source-level ORDER invariant to a weak runtime wiring check and don't fit the cross-cutting exception-classification ladder. Hybrid "tools keep their own try/catch" (Option E) is the **documented fallback** only if centralizing the catch arms threatens byte-for-byte parity.

### FK batch-load placement (MUT-16)
- **D-06:** Add **two-pass methods on the existing `MutationAttributeBinder`**: a `prefetchReferences` pass collects to-one FK ids per target class and issues **one constrained `DataManager.load(targetClass).ids(...)` per class**, returning a prefetched reference map; a `coerceAttributes` overload binds from that map. Single coercion path, single owner of error classification.
- **D-07:** `create_record` / `update_record` reuse the identical path by passing a **one-element row list** (single-call dedup) — no separate branch.
- **D-08:** **Error-parity contract (must reproduce today's behavior exactly):** today's per-reference path emits `access_denied` *only* from `enforceReadPermission(targetMetaClass)` (per target class, before load); a constrained `.id().optional()` that returns empty — whether the row is genuinely missing OR row-level-security-filtered — collapses to `not_found`. The batch path therefore: calls `enforceReadPermission` once per collected target class (identical entity-level `access_denied` semantics/timing), then for any row whose FK id is **absent from the prefetched map**, throws the same `MutationErrorTranslator.notFound` with the correct `failedRowIndex` (re-attributed by iterating rows in submission order) and full-batch rollback.
- **D-09:** Constrained `DataManager.ids(...)` only — never `UnconstrainedDataManager`, never raw JPQL; row-level security still filters results. The bound value stays the **loaded entity instance** (guards receive typed refs, not ids — D-03 of Phase 11).
- *Rejected:* dedicated `FkReferenceBatchLoader` collaborator (splits absence→error ownership across two beans → highest parity-regression risk); per-call context cache (still needs an eager pre-pass to hit "1 query", adds signature churn for no MUT-16 benefit).

### Metadata memoization (MUT-17)
- **D-10:** Memoize `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship` with a plain **`ConcurrentHashMap`** (house pattern — cf. `StreamingSinkHolder`, `CancellationRegistry`; zero new deps; immutable metamodel → no eviction).
- **D-11:** Key on a small immutable **`record Key(String parentEntityName, String relationshipName)`** (using `parentMetaClass.getName()`), not the raw `MetaClass` — costs nothing, self-documenting, avoids relying on framework-type identity/equals.
- **D-12:** Cache **both outcomes** via a tiny sealed/`Optional`-style `Result` holder so "walk once per distinct key" holds for **supported AND unsupported** keys. **Never cache a live exception** — on a cached rejection, rethrow a **freshly built canned `unsupportedRelationship()`** (a constant factory) so the error is byte-for-byte identical in code, message, and retry hints.
- **D-13:** Extract the metamodel traversal into a package-private compute/`walk` method so the pure-JUnit call-count proxy can wrap/override it (see D-16).
- *Rejected:* `@Cacheable` (host owns `CacheManager`; self-invocation bypass; doesn't cache exceptions); Caffeine/Guava (new dependency — SPEC-forbidden).

### Invariant & proxy test strategy (verifies MUT-15/16/17)
- **D-14 (MUT-15):** Pure-JUnit source/reflection invariant test in the `MutationToolInvariantsTest` house style (no Spring, no mocks). Assert gate ORDER via **strictly-increasing `indexOf` of distinct gate-call tokens** (not body-regex), assert "throws before save" by `indexOf(save token) > all gate tokens`, and assert `@Transactional` absence via **reflection** on `MutationGateChain.class` + its declared methods.
- **D-15 (MUT-16):** **Reuse the existing** `net.ttddyy:datasource-proxy:1.11.0` test harness (`QueryCountingDataSourceConfiguration` + `QueryCountHolder`) — already `testImplementation`. Mirror the **narrowed, already-green boot recipe** of `ToolQueryCountBaselineTest` (NOT a bare full-autoconfig `@SpringBootTest` — avoids the module's known boot regression). Target an `ai_*` **agentstore-backed** FK entity; prove slope ≈ 0 as K grows (10→100), i.e. one FK SELECT per target class, not K.
- **D-16 (MUT-17):** Pure-JUnit **counting seam** — a test subclass/wrapper around the memoized compute method + `AtomicInteger`; call `resolve` twice for the same key and assert the walk advanced exactly once. No Spring context, no Mockito.
- **D-17:** **No Mockito InOrder supplement.** Fail-closed at runtime is already covered by the existing Phase 11 host-guard-veto / gating-order behavioral tests; keep to the house no-mock convention.

### Claude's Discretion
- Exact method/field/record names, package placement of `MutationGateChain` and `MutationRequest` variants, and the internal shape of the `Result` holder — left to planning/implementation, provided the decisions above hold.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements
- `.planning/phases/17-mutation-internals-hardening-phase-11-follow-up/17-SPEC.md` — Locked requirements (MUT-15..18), boundaries, acceptance criteria. **MUST read before planning.**
- `.planning/REQUIREMENTS.md` §"Mutation Internals Hardening (Phase 11 follow-up)" — MUT-15, MUT-16, MUT-17, MUT-18; §"Carried debt" rows on `@Transactional`-on-chain and the boot regression.
- `.planning/ROADMAP.md` §"Phase 17" — goal, success criteria, hard ordering constraint (must precede Phase 18).

### Source to refactor (MUT-15)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` — the five tool methods with the duplicated inline gate sequence + per-tool try/catch ladder (`ToolVetoedException`→BLOCKED, `ToolUserError`→ERROR, `Throwable`→COMMIT_FAILED/ERROR via `commitState`).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java` — sole `@Transactional` boundary; javadoc documents why it is a separate `@Component`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java` — reservation-result handling, replay, `safeWriteAudit`, post-reservation error translation, `commitState` transitions.

### Source to refactor (MUT-16)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java` — `coerceAttributeValue` per-reference to-one load (the N+1); `enforceReadPermission` ordering; `applyAttributes`/`capturePreImage`.

### Source to refactor (MUT-17)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java` — `resolveSupportedRelatedWriteRelationship` (un-memoized walk) + `SupportedRelatedRelationship` record; `unsupportedRelationship()` canned-error factory.

### Test patterns to mirror (verification)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolInvariantsTest.java` — pure-JUnit source/reflection house convention (`Files.readString` + `indexOf`/`countOccurrences`, no Spring, no mocks).
- `ToolQueryCountBaselineTest` + `QueryCountingDataSourceConfiguration` + `QueryCountHolder` (test sources) — the green narrowed-boot SELECT-count harness using `net.ttddyy:datasource-proxy`.
- Phase 9/10/11 suites that must pass unchanged: `MutationToolInvariantsTest`, gating-order, audit-row, error-translator (`MutationErrorTranslator`), host-guard-veto, row-level mutation-security tests; `AgentToolCallbacksDefaultConfigTest` (zero-mutation-callback boot).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **All Phase 11 mutation collaborators** are reused as-is and orchestrated by the new chain — no rewrites: `MutationAuthorizationService`, `MutationAttributeBinder`, `MutationRequestHasher`, `MutationIntentRepository`, `MutationSaveExecutor`, `MutationCommitCoordinator`, `MutationErrorTranslator`, `RelatedWriteMetadataResolver`, `DiffSerializer`.
- **`ConcurrentHashMap` memoization precedent**: `StreamingSinkHolder`, `CancellationRegistry` — house pattern for MUT-17.
- **`datasource-proxy` query-count harness** already a `testImplementation` dependency (`net.ttddyy:datasource-proxy:1.11.0`) with `QueryCountingDataSourceConfiguration` + `QueryCountHolder` — reuse for the MUT-16 SELECT-count proxy.

### Established Patterns
- Sole `@Transactional` boundary lives on `MutationSaveExecutor` (separate `@Component`); orchestrators must never be `@Transactional` (self-invocation pitfall).
- Invariant tests are pure-JUnit source/reflection; **ArchUnit was dropped in Phase 2 and is not a dependency** — do not reintroduce.
- Tools are registered only when `ai-agent.tools.mutation.enabled=true` (`@ConditionalOnProperty`, default OFF).
- Module has a known `@SpringBootTest` boot regression (atmosphere-runtime / `agentstoreEntityManagerFactory`); integration tests use narrowed boot recipes, not full autoconfig.

### Integration Points
- The `MutationGateChain` slots **beneath** the five `@Tool` adapters and **atop** the existing collaborators.
- FK batch-load integrates inside `MutationAttributeBinder`'s coercion path; the prefetched-reference map is the Phase 18 memoization seam.
- Memoization is internal to `RelatedWriteMetadataResolver`; no caller changes.

</code_context>

<specifics>
## Specific Ideas

- The source-level gate-ORDER test must read like the existing `MutationToolInvariantsTest`: load the `.java` source, assert distinct gate-call tokens appear in strictly increasing index order, and that the `MutationSaveExecutor` save-token index is greater than all of them.
- The memoization "walk once" proof and the FK "1 query not N" proof are explicit acceptance criteria — implementation must expose the seams (package-private compute method; agentstore-backed FK target) up front, not retrofit them.

</specifics>

<deferred>
## Deferred Ideas

- Broader per-turn / app-wide memoization (schema, `AccessManager`, exposure denylist, RAG filter, media cache) — **Phase 18** (PERF-01/02), with eviction on `LlmExposureChangedEvent` / the Phase 16 `AiParameters` change event.
- Roadmap hygiene: the "Promotes Backlog 999.1" note in the Phase 17 ROADMAP entry is stale text — current Backlog 999.1 is "Admin-rotated provider credentials", unrelated to mutation hardening. Not a Phase 17 work item; clean up separately if desired.

</deferred>

---

*Phase: 17-mutation-internals-hardening-phase-11-follow-up*
*Context gathered: 2026-05-30*
