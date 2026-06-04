# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) - Research

**Researched:** 2026-05-30
**Domain:** Internal refactor of an existing Spring/Jmix LLM-mutation tool surface — gate-chain extraction, FK batch-load, metadata memoization, with byte-for-byte behavior parity
**Confidence:** HIGH (the work is local to a well-bounded, fully-read package; every recommendation is grounded in the current source, not assumptions)

## Summary

This is a pure internal refactor. There is no new runtime dependency, no new user-facing surface, and no behavior change. The Phase 9/10/11 mutation suites lock the externally observable behavior and must pass with zero test-body edits. The locked decisions in `17-CONTEXT.md` (D-01..D-17) are not relitigated here — this research documents **HOW to execute them safely** and surfaces the concrete parity landmines.

The three refactors are independent and can land as separate waves:
1. **MUT-15 gate-chain extraction** — the highest-risk piece because it must preserve a cross-cutting exception-classification ladder (`ToolVetoedException`→BLOCKED, `ToolUserError`→ERROR, `Throwable`→COMMIT_FAILED/ERROR via `commitState`) byte-for-byte across five entry points that diverge in real ways (argumentsJson serializer shape, success-result shape, the bulk per-row loop, and a *fourth* catch arm `AccessDeniedException` that ONLY bulk has).
2. **MUT-16 FK batch-load** — surgical two-pass change inside `MutationAttributeBinder`; the parity-critical detail is reproducing the exact `access_denied`-vs-`not_found` decision and `failedRowIndex` re-attribution.
3. **MUT-17 memoization** — the lowest-risk piece; a `ConcurrentHashMap` over `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship`, caching both outcomes via a `Result` holder, never caching a live throwable.

**Primary recommendation:** Implement as three sequential, independently-verifiable waves (MUT-17 → MUT-16 → MUT-15, easiest-to-hardest), each green against the full Phase 9/10/11 suite before the next starts. Treat the **Option E fallback** (tools keep their own try/catch) as a live possibility for MUT-15 and design the chain so falling back costs only the gate-extraction, not the FK/memoization work. Surface the agentstore-FK-target tension (below, Open Question 1) to the planner up front — the existing query-count harness wraps the **agentstore** datasource, but the only mutation FK fixture (`MutationLinkedChildFixture.linkedParent`) is in the **main** store.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| LLM tool entry points (`@Tool` methods) | API / Backend (Spring AI tool callbacks) | — | These are tool-callback methods invoked by the agent harness; thin adapters only after MUT-15 |
| Fail-closed gate orchestration (`MutationGateChain`) | API / Backend (Spring `@Component`) | — | Pure orchestration bean; no transaction, no proxy crossing of its own |
| Transaction boundary (`MutationSaveExecutor`) | Database / Persistence | — | Sole `@Transactional` bean; the ONLY proxy crossing (D-04) |
| FK reference resolution + coercion (`MutationAttributeBinder`) | Database / Persistence (constrained `DataManager`) | API (error classification) | Loads target entities under row-level security; owns `access_denied`/`not_found` classification |
| Relationship-metadata resolution (`RelatedWriteMetadataResolver`) | API / Backend (in-memory metamodel) | — | Reads immutable Jmix metamodel; memoizable app-wide with no eviction |
| Idempotency reservation (`MutationIntentRepository`) | Database / Persistence (agentstore, `REQUIRES_NEW`) | — | Unchanged; orchestrated by the chain |
| Audit + commit-state (`MutationCommitCoordinator`) | Database / Persistence (agentstore) | API (error translation) | Unchanged; orchestrated by the chain catch arms |

## Standard Stack

This is an internals refactor with **no new dependencies** (SPEC constraint). The relevant existing stack:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jmix `core` (DataManager, MetaClass, AccessManager) | 2.8 | Constrained data access, metamodel, security | Project framework `[CITED: CLAUDE.md]` |
| Spring AI `tool` (`@Tool`/`@ToolParam`) | (existing) | Tool-callback annotations on the five entry points | Already in use; descriptions frozen this phase |
| `net.ttddyy:datasource-proxy` | 1.11.0 | Test-scoped SELECT-count proxy for MUT-16 | Already `testImplementation` `[VERIFIED: ToolQueryCountBaselineTest source]` |
| JUnit 5 + AssertJ | (existing) | Pure-JUnit source/reflection invariant tests | House convention `[VERIFIED: MutationToolInvariantsTest source]` |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.util.concurrent.ConcurrentHashMap` | JDK 21 | MUT-17 memoization (house pattern) | `computeIfAbsent` keyed on a `record Key` (D-10/D-11) |
| `java.util.concurrent.atomic.AtomicInteger` | JDK 21 | MUT-17 call-count seam | Already used by `BuiltInMutationToolsBulkSavePartialFailureTest` `[VERIFIED]` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `ConcurrentHashMap` memo | Spring `@Cacheable` | Rejected by D-13 — host owns `CacheManager`; self-invocation bypass; can't cache exceptions |
| `ConcurrentHashMap` memo | Caffeine/Guava | Rejected — new dependency, SPEC-forbidden |
| Template-method chain | chain-of-responsibility `List<Gate>` | Rejected by D-01 — demotes the source-level ORDER invariant to a weak runtime wiring check |
| Source/reflection invariant test | Mockito `InOrder` | Rejected by D-17 — breaks the no-mock house convention |

**Installation:** None — no new dependencies.

## Package Legitimacy Audit

> Not applicable — this phase installs **zero** external packages (SPEC constraint "No new runtime dependencies"). All libraries listed in Standard Stack are pre-existing in the module's build. No slopcheck run required.

## Architecture Patterns

### System Architecture Diagram (target state after MUT-15/16/17)

```
LLM agent harness (Spring AI tool callbacks)
        │  invokes one of five @Tool methods
        ▼
┌─────────────────────────────────────────────────────────────┐
│ BuiltInMutationTools  (thin adapters — D-03)                  │
│  createRecord / updateRecord / addRelatedRecord /             │
│  removeRelatedRecord / bulkSaveRecords                        │
│   1. build a MutationRequest variant                          │
│   2. call mutationGateChain.execute(request)                  │
│   3. format the result (success-result shape per variant)     │
└───────────────┬───────────────────────────────────────────────┘
                │  MutationRequest (sealed: Create/Update/AddRelated/RemoveRelated/Bulk)
                ▼
┌─────────────────────────────────────────────────────────────┐
│ MutationGateChain  (@Component, NO @Transactional — D-04)     │
│  execute(request) {                                           │
│    try {                                                      │
│      enforceRole   ─┐                                         │
│      resolve        │  ordered named private gates            │
│      authorize      │  (the source-level ORDER invariant      │
│      reserve        │   asserts strictly-increasing indexOf)  │
│      coerce         │   ── MUT-16 prefetch happens here       │
│      guard          │                                         │
│      save  ─────────┼──► mutationSaveExecutor.save/saveAll/   │
│      finalize       │      bulkSave  (ONLY proxy crossing)    │
│    } catch (ToolVetoedException)  → BLOCKED   ─┐              │
│      catch (AccessDeniedException)→ BLOCKED*   │ catch ladder │
│      catch (ToolUserError)        → ERROR      │ (byte parity)│
│      catch (Throwable)            → COMMIT_FAILED/ERROR ┘     │
│  }   *bulk-only arm — see Pitfall 2                            │
└───────────────┬───────────────────────────────────────────────┘
                │  orchestrates EXISTING collaborators (D-03, no rewrites)
                ▼
  MutationAuthorizationService · MutationAttributeBinder (+FK prefetch, MUT-16)
  MutationRequestHasher · MutationIntentRepository · MutationCommitCoordinator
  MutationErrorTranslator · RelatedWriteMetadataResolver (+memo, MUT-17) · DiffSerializer
                │
                ▼  constrained DataManager.ids(...) — row-level security preserved
            host database (main store) + agentstore (AiMutationIntent, AiAuditEvent)
```

### Recommended Structure (no new package needed)
```
com/vn/agent/tools/mutation/
├── BuiltInMutationTools.java          # becomes thin adapters (MUT-15)
├── MutationGateChain.java             # NEW @Component (MUT-15)
├── MutationRequest.java               # NEW sealed interface + variants (MUT-15)
├── MutationAttributeBinder.java       # + prefetchReferences/coerce overload (MUT-16)
├── RelatedWriteMetadataResolver.java  # + ConcurrentHashMap memo + compute seam (MUT-17)
└── (all other collaborators unchanged)
```
Package placement of `MutationGateChain`/`MutationRequest` is Claude's discretion (D, "Claude's Discretion"); keeping them in the existing `tools.mutation` package matches the source-test root resolution in `MutationToolInvariantsTest` (`src/main/java/com/vn/agent/tools/mutation`) so the invariant test finds them with no path change.

### Pattern 1: Template-method chain over a sealed request (D-01/D-02)
**What:** One `execute(MutationRequest)` with an ordered list of named private gate calls; the sealed variants localize divergence.
**When to use:** This phase, for MUT-15.
**Where divergence lives (the two real fork points, D-02):**
- **coerce/guard/save stage:** single-row (Create/Update/AddRelated/RemoveRelated) vs the bulk per-row loop (`for i: coerce → per-row CrudEntityContext → guard → saveContext.saving`); bulk builds a `SaveContext` and calls `mutationSaveExecutor.bulkSave`, the others call `save`/`saveAll`.
- **adapter shape:** each tool's `argumentsJson` serializer (`serializeEntityArgumentsJson` vs `serializeRelatedArgumentsJson` vs `serializeBulkArgumentsJson`) and success-result map shape (`entityId`/`instanceName` vs `+diffSummary` vs `parentId/relationship/relatedId` vs `count/savedIds`).

### Anti-Patterns to Avoid
- **Adding `@Transactional` to `MutationGateChain`** — Spring self-invocation makes it a silent no-op AND breaks the fail-closed contract. Only `MutationSaveExecutor` is transactional (D-04). The MUT-15 invariant test must assert its absence by reflection.
- **Splitting absence→error ownership across two beans** for MUT-16 — D-08 rejected a dedicated `FkReferenceBatchLoader` precisely because it splits the `not_found` decision away from the binder, raising parity-regression risk. Keep one coercion path, one error-classification owner.
- **Caching a live `ToolUserError`** in the MUT-17 memo — re-throwing a cached exception instance leaks a stale stack/suppressed state and risks message drift. Cache a `Result` and rethrow a freshly built `unsupportedRelationship()` (D-12).
- **Using `UnconstrainedDataManager` or raw JPQL** on the FK path — would bypass row-level security and silently change `not_found`/`access_denied` semantics (D-09).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Load N entities by id | A loop of `.id().optional()` | `dataManager.load(class).ids(idCollection).list()` | One SELECT, preserves row-level security `[CITED: docs.jmix.io/jmix/data-access/data-manager.html]` |
| Gate-order enforcement | A runtime wiring assertion | Source-level strictly-increasing `indexOf` of distinct tokens | D-14; runtime checks can't prove "throws before save" |
| Metadata memo | Custom cache w/ eviction | `ConcurrentHashMap.computeIfAbsent` (no eviction) | Metamodel immutable at runtime (D-10) |
| Exception classification | Re-deriving error codes in the chain | Existing `MutationErrorTranslator` + `MutationCommitCoordinator` | Byte-parity owner already exists (D-03) |

**Key insight:** Every collaborator the chain needs already exists and is correct. The risk is entirely in *re-wiring* them in the same order with the same catch ladder — not in building anything new.

## Runtime State Inventory

> This is a code-and-test refactor with NO data migration, NO renamed strings, NO stored-key changes.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — `AiMutationIntent` rows keyed on `(toolName, idempotencyKey, userUsername)` are unaffected; `toolName` literals (`create_record`, etc.) are unchanged | None — verified: SPEC out-of-scope explicitly forbids idempotency-semantics changes |
| Live service config | None — no external service config references these classes | None |
| OS-registered state | None | None |
| Secrets/env vars | None — `ai-agent.tools.mutation.enabled` flag and TTL/bulk-max knobs are read, not renamed | None |
| Build artifacts | None — no module/artifact rename | None |

**Nothing found in any category** — verified by reading the full mutation package; the refactor changes class internals only, not persisted keys, tool names, audit shapes, or config property names.

## Common Pitfalls

### Pitfall 1: The catch ladder is NOT uniform across the five entry points
**What goes wrong:** Centralizing the catch arms in `MutationGateChain.execute` assumes all five tools have the same three-arm ladder. They do not.
**Why it happens:** `bulkSaveRecords` has a **fourth** arm — `catch (AccessDeniedException ade)` (lines 1058–1067) — that classifies per-row `CrudEntityContext` denial to `AiToolCallOutcome.BLOCKED` with `denialReason="row_access_denied"`. The single-row tools have only `ToolVetoedException`/`ToolUserError`/`Throwable`. If the chain's generic `Throwable` arm swallows `AccessDeniedException` for bulk, the audit `denialReason` and outcome change (BLOCKED→COMMIT_FAILED/ERROR) — a byte-parity break locked by `BuiltInMutationToolsBulkSavePartialFailureTest.oneRowAccessDeniedByCrudEntityContextRollsBackBatch`.
**How to avoid:** The sealed `Bulk` variant must carry (or the chain must conditionally apply) the extra `AccessDeniedException→BLOCKED/row_access_denied` arm. Confirm by running that test class unchanged. **This is the single most likely trigger of the Option E fallback.**
**Warning signs:** `BuiltInMutationToolsBulkSavePartialFailureTest` scenario 2 flips outcome from BLOCKED.

### Pitfall 2: `commitState` is per-invocation mutable and drives the `Throwable` arm
**What goes wrong:** The `Throwable` arm calls `translateThrowableAfterReservation(t, commitState, ...)` and `auditOutcome(commitState)` — both branch on `commitState` (NO_HOST_WRITE / HOST_SAVE_RETURNED / INTENT_COMMITTED). If the chain holds `commitState` as a field instead of a per-`execute` local, concurrent tool calls corrupt each other's commit classification.
**Why it happens:** `MutationGateChain` is a singleton `@Component`; tool calls are concurrent. The current code keeps `commitState` as a method local in each `@Tool` method.
**How to avoid:** Carry `commitState` (and `reservation`, `failedRowIndex`, `startedAt`, `userUsername`, `metaClass`) in a per-`execute` mutable context object or method locals — **never** instance fields. Same applies to `reservation`.
**Warning signs:** `BuiltInMutationToolsCommitUnknownTest`, `BuiltInMutationToolsPostCommitAuditFailureTest` go flaky under parallel test execution.

### Pitfall 3: Gate ORDER is load-bearing for security, not just for the test
**What goes wrong:** Reordering `reserve` before `coerce`/`guard`, or moving `enforceReadPermission` relative to the FK load, changes *when* `access_denied` vs `not_found` vs `idempotency_violation` is emitted — observable in audit rows and error codes.
**Why it happens:** The current order is exact: role → resolve → (id parse) → CRUD+attribute authorize → hash+reserve → coerce → guard → save → finalize. `reserveOrReplay` runs **before** `coerce` so a duplicate key replays without re-coercing; `guard` runs **after** `coerce` so guards see typed values (locked by `BuiltInMutationToolsGuardReceivesCoercedAttributesTest`).
**How to avoid:** Mirror the order verbatim. The D-14 invariant test (strictly-increasing `indexOf` of distinct gate tokens + `save token index > all gate tokens`) is the structural guard; the behavioral suites are the semantic guard.
**Warning signs:** `BuiltInMutationToolsIdempotencyReplayTest`, `...GuardReceivesCoercedAttributesTest`, gating-order tests.

### Pitfall 4 (MUT-16): `failedRowIndex` re-attribution must iterate rows in submission order
**What goes wrong:** A two-pass prefetch collects FK ids across all rows; if a row's FK id is absent from the prefetched map, the error must carry the SAME `failedRowIndex` the per-reference path produced — which is the row index in **submission order** (`safeRecords.get(i)` loop, `failedRowIndex = i`).
**Why it happens:** Today the bulk loop sets `failedRowIndex = i` and coerces row-by-row; the FK `not_found` throws from inside `coerceAttributeValue` during row `i`. Moving the load to a pre-pass decouples the load from the row index, so the absent-id→`failedRowIndex` mapping must be re-derived by iterating rows in order (D-08).
**How to avoid:** Keep the per-row binding pass (coerce-from-map) iterating `safeRecords` in order; on an absent FK id throw `mutationErrorTranslator.notFound(targetMetaClass, rawValue)` at that row's index. The prefetch pass is purely additive (collect + one `.ids()` load + `enforceReadPermission` once per target class).
**Warning signs:** Any bulk partial-failure test asserting a specific `failedRowIndex`.

### Pitfall 5 (MUT-16): `access_denied` comes ONLY from `enforceReadPermission(targetMetaClass)`, never from an empty load
**What goes wrong:** Conflating "row filtered by row-level security" with "denied". Today (binder lines 224–231): `enforceLlmRelationshipTargetExposure` → `enforceReadPermission(targetMetaClass)` (entity-level, throws `access_denied`) → `.id().optional().orElseThrow(notFound)`. A constrained `.optional()` that returns empty — whether the row is genuinely missing OR row-level-security-filtered — collapses to `not_found`, **not** `access_denied`.
**Why it happens:** Entity-level read denial and row-level filtering are different layers; only the former is `access_denied`.
**How to avoid (D-08 contract):** Prefetch pass calls `enforceLlmRelationshipTargetExposure` + `enforceReadPermission` **once per collected target class** (identical entity-level timing/semantics), then `.ids(...).list()`; any row whose FK id is absent from the resulting map → `notFound` at that row's index + full-batch rollback. Never throw `access_denied` from an empty-result row.
**Warning signs:** `BuiltInMutationToolsRelationshipExposureTest`, `MutationErrorTranslatorTest`.

### Pitfall 6 (MUT-16): The bound value must stay the loaded entity instance, not the id
**What goes wrong:** Binding the FK id instead of the loaded reference breaks the `MutationGuard` SPI contract (D-03 of Phase 11: guards receive typed refs).
**How to avoid:** The prefetched map is `targetId → loadedEntity`; `coerceAttributes` binds `map.get(targetId)`. `applyAttributes` then `EntityValues.setValue(entity, attr, loadedEntity)` exactly as today.

### Pitfall 7 (MUT-16): add/remove_related_record does NOT go through `coerceAttributeValue`
**What goes wrong:** Assuming all FK loads are in the binder. They are not — `executeRelatedWrite` loads parent+child directly via `dataManager.load(...).id(...)` (lines 673, 682), bypassing the binder. MUT-16 batching applies ONLY to create/update/bulk (the `coerceAttributes` path).
**How to avoid:** Scope MUT-16 to `coerceAttributes`. Related-write parent/child loads are single-id by nature (one parent, one child per call) — no N+1 there, leave them. SPEC confirms: cross-row batching is for `bulk_save_records`; create/update get single-call dedup.

### Pitfall 8 (MUT-17): Never cache a live exception; key on entity name not MetaClass
**What goes wrong:** Caching the thrown `ToolUserError` (D-12) or keying on raw `MetaClass` (framework identity).
**How to avoid:** `record Key(String parentEntityName, String relationshipName)` using `parentMetaClass.getName()` (D-11). Store a `Result` holder carrying EITHER a `SupportedRelatedRelationship` OR a "rejected" marker; on a cached rejection rethrow `unsupportedRelationship()` freshly (D-12). Note: `unsupportedRelationship()` is already a static no-arg factory (resolver lines 307–314) — no LLM data inside — so rebuilding it is byte-identical.

## Code Examples

### Current per-reference FK load (the N+1) — `MutationAttributeBinder.coerceAttributeValue`
```java
// Source: MutationAttributeBinder.java lines 223–231 [VERIFIED: source]
MetaClass targetMetaClass = property.getRange().asClass();
mutationAuthorizationService.enforceLlmRelationshipTargetExposure(targetMetaClass, false);
UUID targetId = requireUuidId(
        toolEntityResolver.parseEntityId(rawValue.toString(), targetMetaClass));
mutationAuthorizationService.enforceReadPermission(targetMetaClass);   // <-- ONLY access_denied source
return dataManager.load(targetMetaClass.getJavaClass())
        .id(targetId)
        .optional()
        .orElseThrow(() -> mutationErrorTranslator.notFound(targetMetaClass, rawValue.toString())); // empty => not_found
```

### Target batch load (MUT-16, two-pass)
```java
// Pass 1 — prefetch: collect to-one ids per target class across ALL rows, ONE load per class.
// Source pattern: docs.jmix.io ids() [CITED] + D-06/D-08/D-09
// enforceLlmRelationshipTargetExposure + enforceReadPermission called ONCE per target class here.
List<Object> entities = dataManager.load(targetMetaClass.getJavaClass())
        .ids(idCollectionForThisClass)   // ids(Collection) overload [ASSUMED — verify, see A1]
        .list();
Map<Object, Object> byId = entities.stream()
        .collect(toMap(EntityValues::getId, e -> e));
// Pass 2 — bind: iterate rows in submission order; absent id => notFound at that row index.
```

### Current ORDER inside each @Tool (the sequence to mirror in the chain)
```java
// Source: BuiltInMutationTools.createRecord lines 216–247 [VERIFIED: source]
enforceMutationRole(AiAgentMutationRole.CODE);          // role
toolEntityResolver.resolveCreatableEntityOrThrow(...);  // resolve
enforceCreatePermission(metaClass);                     // authorize (entity)
enforceAttributeWriteAccess(metaClass, keys);           // authorize (attribute)
reserveOrReplay(...);  if (!RESERVED) return replay;    // reserve
coerceAttributes(metaClass, safeAttributes);            // coerce  (<-- MUT-16 prefetch here)
mutationGuard.check(new MutationIntent(...));            // guard
mutationSaveExecutor.save(entity);                       // save (ONLY proxy crossing)
markCommitted(...); safeWriteAudit(...);                // finalize
```

### MUT-17 memoization shape (compute seam for the call-count proxy, D-13/D-16)
```java
// In RelatedWriteMetadataResolver
private final Map<Key, Result> cache = new ConcurrentHashMap<>();
record Key(String parentEntityName, String relationshipName) {}

public SupportedRelatedRelationship resolveSupportedRelatedWriteRelationship(
        MetaClass parentMetaClass, String relationshipName) {
    Key key = new Key(parentMetaClass.getName(), relationshipName);
    Result r = cache.computeIfAbsent(key, k -> computeSupported(parentMetaClass, relationshipName));
    if (r.rejected()) throw unsupportedRelationship();   // fresh, never the cached throwable
    return r.relationship();
}

// package-private seam the pure-JUnit AtomicInteger proxy wraps/counts (D-16)
SupportedRelatedRelationship walk... -> wrapped in computeSupported(...)
```

## State of the Art

| Old Approach (Phase 11) | Current/Target Approach (Phase 17) | When Changed | Impact |
|--------------------------|------------------------------------|--------------|--------|
| Gate sequence inline in 5 `@Tool` methods | One `MutationGateChain` `@Component`; tools are thin adapters | Phase 17 (this) | One place for Phase 18's perf pass to touch |
| Per-reference `.id().optional()` FK load | One constrained `.ids(...)` per target class | Phase 17 (this) | Kills the bulk N+1 |
| Un-memoized metamodel walk per related-write | `ConcurrentHashMap` memo, no eviction | Phase 17 (this) | Walk once per `(entity,relationship)` key |

**Deprecated/outdated:** ArchUnit — dropped in Phase 2; do NOT reintroduce (CONTEXT D / STATE.md). Full bare `@SpringBootTest` autoconfig — blocked by the known atmosphere-runtime / `agentstoreEntityManagerFactory` boot regression; use the narrowed boot recipe from `ToolQueryCountBaselineTest`.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Jmix `FluentLoader.ByIds.ids(...)` accepts a `Collection` argument in addition to varargs | MUT-16 code example | LOW — if Collection overload absent, pass varargs via `ids(collection.toArray())`; Jmix 2.x `LoadContext.Query` and `FluentLoader.ByIds` historically support both. Verify against the project's Jmix 2.8 `io.jmix.core.FluentLoader` API or Context7 `jmix-framework/jmix-context7` before writing the prefetch signature. Official docs `[CITED]` show varargs `.ids(id1,id2)`; missing-id rows are simply absent from the result list (no exception). |
| A2 | `.ids(...).list()` preserves row-level security identically to `.id().optional()` | MUT-16 | LOW — Jmix docs confirm constrained `DataManager` applies entity + row-level policies on all load paths `[CITED: docs.jmix.io]`. The parity test (Pitfall 5) is the safety net. |
| A3 | The narrowed boot recipe (`@SpringBootTest(classes=AITestConfiguration.class)` + `@ImportAutoConfiguration({AIAutoConfiguration, SpiDefaultsAutoConfiguration, AiToolsAutoConfiguration})` + stub chat/vectorstore + `QueryCountingDataSourceConfiguration`) boots green for a mutation-FK SELECT-count test | MUT-18 | MEDIUM — `ToolQueryCountBaselineTest` uses exactly this recipe and is green, but it targets read tools; a mutation variant adds `ai-agent.tools.mutation.enabled=true` + `MutationToolTestUsersConfiguration` (as the gating tests do). See Open Question 1. |

**No `[ASSUMED]` package claims** — zero external packages installed.

## Open Questions

1. **MUT-16 SELECT-count target store — agentstore vs main store (HIGH priority for planner).**
   - What we know: `QueryCountingDataSourceConfiguration` wraps the **`agentstoreDataSource`** bean only (it explicitly notes the main `dataSource` is untouched, and that `ai_*` entities are agentstore-backed). The only existing mutation FK fixture, `MutationLinkedChildFixture.linkedParent → MutationLinkedParentFixture`, lives in the **main** store (`MUTATION_LINKED_*_FIXTURE` tables, no `@Store`). The `MutationTestFixture` (the entity the bulk tests use) has **no to-one FK at all**.
   - What's unclear: D-15 says "target an `ai_*` agentstore-backed FK entity," but the agentstore FK entities (`AiAuditEvent.parent` self-FK, `AiAuditEvent.conversation → AiConversation`) are not mutation-tool targets (`AiAuditEvent` parent is a `@Composition @OneToMany` inverse → rejected by mutation tools; and mutation tools target host entities, not agentstore audit rows). So there is a genuine gap between "the FK fixture I can mutate" (main store, not counted) and "the datasource the harness counts" (agentstore).
   - Recommendation: The plan must do ONE of: (a) add a `@ManyToOne` FK to a **new agentstore test fixture** (with `@Store("agentstore")`) plus a parent target, register it via the test-only Jmix module pattern (`MutationFixturePersistenceTestConfiguration`), so the existing agentstore-wrapping harness counts the FK `.ids()` SELECT; OR (b) extend `QueryCountingDataSourceConfiguration` to ALSO wrap the **main** `dataSource` bean (second `BeanPostProcessor` arm + a second logical datasource name) and target the existing `MutationLinkedChildFixture.linkedParent`. Option (a) honors D-15's "agentstore-backed" wording literally; option (b) reuses the existing FK fixture but widens the harness. Surface both to the planner; flag (a) as the lower-divergence choice since it doesn't touch the shared counting config. This is the only non-trivial design decision left open after CONTEXT.

2. **Bulk `AccessDeniedException` arm placement in the sealed model.**
   - What we know: only `Bulk` needs the fourth catch arm (Pitfall 1).
   - What's unclear: whether the chain holds a single try/catch with a variant-conditional arm, or whether `Bulk` overrides the catch handling.
   - Recommendation: Single `execute` try/catch; add `catch (AccessDeniedException)` BEFORE `catch (ToolUserError)` (AccessDeniedException is not a ToolUserError, so order is safe) and route it to `BLOCKED/row_access_denied` — but only emit `denialReason="row_access_denied"` for the `Bulk` variant to preserve byte-parity (single-row tools never produced that arm because their entity-level CRUD denial throws `ToolUserError(access_denied)` earlier, not `AccessDeniedException`). Verify single-row tools never reach this arm.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| `net.ttddyy:datasource-proxy` | MUT-16 SELECT-count proxy | ✓ | 1.11.0 (testImplementation) | — |
| Jmix `DataManager.ids()` | MUT-16 batch load | ✓ | Jmix 2.8 | varargs `ids(arr...)` if Collection overload absent (A1) |
| JUnit 5 + AssertJ | All invariant tests | ✓ | existing | — |
| `ConcurrentHashMap` / `AtomicInteger` | MUT-17 | ✓ | JDK 21 | — |
| Full `@SpringBootTest` autoconfig | (NOT used) | ✗ (boot regression) | — | Narrowed boot recipe (A3) — REQUIRED, not optional |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** Full-autoconfig boot is unavailable (known regression) — all integration coverage uses the narrowed recipe.

## Validation Architecture

> `workflow.nyquist_validation` not disabled in config — section included.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ + (selective) Mockito `@MockitoBean`; pure-JUnit source/reflection for invariants |
| Config file | Gradle module `ai-agent/ai-agent` (included build) |
| Quick run command | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.*"` |
| Full suite command | `./gradlew :ai-agent:ai-agent:test` |
| Note | Run via the included-build path `:ai-agent:ai-agent:*` (root has no `:ai-agent:test` task — STATE.md Phase 12 decision) |

### Phase Requirements → Test Map (each success criterion → observable proxy + required seam)
| Req ID | Behavior | Test Type | Automated Command | Seam that must exist | File Exists? |
|--------|----------|-----------|-------------------|----------------------|-------------|
| MUT-15 | Gate ORDER + "throws before save" + no `@Transactional` on chain | source/reflection (pure JUnit) | `--tests "*MutationToolInvariantsTest"` (extended) | `MutationGateChain` with named ordered private gate calls in source; declared methods + class reflected for `@Transactional` absence | ⚠️ extend existing `MutationToolInvariantsTest` |
| MUT-15 | Five tools are thin adapters; chain orchestrates collaborators | source assertion | same | adapters call `mutationGateChain.execute(...)` | ⚠️ Wave 0 (new assertions) |
| MUT-16 | One FK load per target class for K-row batch (slope ≈ 0, K 10→100) | SELECT-count (datasource-proxy, narrowed boot) | `--tests "*MutationFkBatchLoadQueryCountTest"` | agentstore FK fixture OR widened counting config (Open Q1); `QueryCountHolder.clear()`/`safeSelectCount()` pattern | ❌ Wave 0 — new test + possibly new fixture |
| MUT-16 | FK path uses no `UnconstrainedDataManager`, no raw JPQL | source-scan (pure JUnit) | `--tests "*MutationToolInvariantsTest"` (extended) | grep `MutationAttributeBinder.java` for forbidden tokens | ⚠️ extend invariant test |
| MUT-16 | FK not-found/not-readable → identical code + `failedRowIndex` + full rollback | behavioral (existing suites unchanged) | `--tests "*BulkSavePartialFailureTest" "*RelationshipExposureTest" "*MutationErrorTranslatorTest"` | none new — must pass unchanged | ✅ exists |
| MUT-17 | Walk runs once per distinct `(entity,relationship)` key | call-count (pure JUnit + AtomicInteger) | `--tests "*RelatedWriteMetadataMemoTest"` | package-private compute/`walk` seam in `RelatedWriteMetadataResolver` (D-13) | ❌ Wave 0 — new test |
| MUT-18 | Full Phase 9/10/11 mutation suites + default-config boot pass with zero test edits | regression (all existing) | `./gradlew :ai-agent:ai-agent:test` | none — parity gate | ✅ exists (`AgentToolCallbacksDefaultConfigTest`, gating/audit/error/host-guard/security suites) |

### Sampling Rate
- **Per task commit:** `--tests "com.vn.agent.tools.mutation.*"` (the mutation package suite).
- **Per wave merge:** full `:ai-agent:ai-agent:test` (catches cross-cutting parity regressions, esp. audit/idempotency).
- **Phase gate:** full suite green before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] Extend `MutationToolInvariantsTest` — add gate-ORDER (strictly-increasing `indexOf`), "save token after all gate tokens", `@Transactional`-absence reflection, and the MUT-16 forbidden-token source-scan on `MutationAttributeBinder.java`.
- [ ] New `MutationFkBatchLoadQueryCountTest` — mirror `ToolQueryCountBaselineTest` narrowed boot recipe + datasource-proxy; resolve Open Question 1 (agentstore FK fixture vs widened counting config) first.
- [ ] New `RelatedWriteMetadataMemoTest` — pure-JUnit `AtomicInteger` counting seam over the package-private compute method; assert walk advances exactly once across two `resolve` calls for the same key, and once each for supported AND unsupported keys.
- [ ] Possible new agentstore FK test fixture (`@Store("agentstore")` entity with a `@ManyToOne` + its parent) registered via the existing test-only Jmix module pattern — only if Open Question 1 resolves to option (a).
- [ ] No framework install needed.

## Security Domain

> `security_enforcement` not disabled — section included. This phase is security-preserving by construction (byte-for-byte parity), but the gate sequence IS the security boundary.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | Fail-closed gate chain; transaction boundary isolated to one bean |
| V4 Access Control | yes | `AccessManager` CRUD + per-attribute + per-row `CrudEntityContext`; `LlmExposurePolicy`; marker-role exact-authority equality (SEC-07) — all preserved unchanged |
| V5 Input Validation | yes | Mass-assignment denylist + `FilterLiteralValueConverter` coercion in `MutationAttributeBinder` — unchanged |
| V7 Error Handling/Logging | yes | `MutationErrorTranslator` 6-code closed taxonomy; P-22 (no raw exception/LLM text in prose); sanitized audit — must stay byte-identical |
| V6 Cryptography | partial | `AuditFieldHasher` SHA-256 for sensitive fields via `DiffSerializer` — unchanged |

### Known Threat Patterns for this stack (all already mitigated; refactor must not regress)
| Pattern | STRIDE | Standard Mitigation (preserved) |
|---------|--------|---------------------------------|
| Mass assignment via LLM attributes | Tampering / Elevation | `validateWritableProperty` denylist (pk/version/audit/readonly/non-JPA/collection) |
| Row-level security bypass on FK load | Information Disclosure / Elevation | Constrained `DataManager` only — `.ids()` keeps row policies (D-09); NEVER `UnconstrainedDataManager`/raw JPQL |
| Idempotency replay / duplicate writes | Tampering | `AiMutationIntent` reserve-before-save + `commitState` ladder; order unchanged |
| Self-invocation defeating `@Transactional` | Tampering (lost rollback) | Sole `@Transactional` on `MutationSaveExecutor`; chain has none (D-04) — asserted by reflection |
| PII/secret leak in error/audit prose | Information Disclosure | P-22: `MutationErrorTranslator` canned templates + `DiffSerializer` hashing — unchanged |
| Guard veto bypass | Elevation | `MutationGuard.check` after coerce, before save; per-row in bulk — unchanged |

## Sources

### Primary (HIGH confidence)
- Source files read in full: `BuiltInMutationTools.java` (1092 lines), `MutationAttributeBinder.java`, `RelatedWriteMetadataResolver.java`, `MutationCommitCoordinator.java`, `MutationSaveExecutor.java`, `MutationAuthorizationService.java`, `AiAuditEvent.java`, fixtures (`MutationTestFixture`, `MutationLinkedChildFixture`, `MutationLinkedParentFixture`), tests (`MutationToolInvariantsTest`, `ToolQueryCountBaselineTest`, `QueryCountingDataSourceConfiguration`, `RelatedWriteMetadataResolverTest`, `BuiltInMutationToolsBulkSavePartialFailureTest`, `BuiltInMutationToolsAccessGatingTest`).
- `17-SPEC.md`, `17-CONTEXT.md` (D-01..D-17 locked decisions), `REQUIREMENTS.md` (MUT-15..18), `ROADMAP.md` (Phase 17/18), `STATE.md`.

### Secondary (MEDIUM confidence)
- `docs.jmix.io/jmix/data-access/data-manager.html` — `DataManager.load(class).ids(...)` varargs + row-level security on load paths (WebFetch, verified against official Jmix docs).

### Tertiary (LOW confidence)
- `.ids(Collection)` overload existence (A1) — training knowledge of Jmix 2.x `FluentLoader.ByIds`; verify against project's Jmix 2.8 API or Context7 `jmix-framework/jmix-context7` before locking the prefetch signature.

## Metadata

**Confidence breakdown:**
- Gate-chain extraction (MUT-15): HIGH — full source read; the divergence points (bulk loop, fourth catch arm, per-invocation `commitState`) are concretely identified with the locking tests named.
- FK batch-load (MUT-16): HIGH on the refactor shape and error-parity contract; MEDIUM on the SELECT-count test target (Open Question 1 must be resolved by the planner).
- Memoization (MUT-17): HIGH — smallest surface, exact current walk + factory read.
- Verification: HIGH on the invariant/memo seams; MEDIUM on the query-count harness target store.

**Research date:** 2026-05-30
**Valid until:** 2026-06-29 (stable — internal refactor against frozen behavior; only the Jmix `.ids()` API note (A1) is version-sensitive)
