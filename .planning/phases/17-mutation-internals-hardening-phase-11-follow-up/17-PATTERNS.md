# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) - Pattern Map

**Mapped:** 2026-05-31
**Files analyzed:** 11 (3 new source, 4 modified source, 4 new/extended test)
**Analogs found:** 11 / 11

This is a **behavior-frozen internal refactor**. All "new" files are internal collaborators or extracted seams; every analog already lives in the same module. No new public surface, no new dependency. The implementer's job is to *re-wire existing collaborators in the same order with the same catch ladder* — analogs below are house idioms to mirror exactly, not novel designs.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| **NEW** `MutationGateChain.java` | orchestrator `@Component` | request-response (template-method) | `BuiltInMutationTools.java` (current inline sequence) + `MutationSaveExecutor.java` (`@Component` style) | role-match (orchestrator + house @Component) |
| **NEW** `MutationRequest.java` (sealed hierarchy) | model / DTO (sealed interface + record variants) | transform / dispatch | `orchestration/StreamingEvent.java` | exact (sealed interface + nested records + exhaustive switch) |
| **NEW** `Result` holder + `record Key(...)` (in `RelatedWriteMetadataResolver`) | model / memo holder | transform | `RelatedWriteMetadataResolver.SupportedRelatedRelationship` (nested record) + `StreamingEvent` (sealed) | exact (nested record), role-match (sealed holder) |
| **MODIFY** `BuiltInMutationTools.java` (5 `@Tool` → thin adapters) | controller / tool-callback | request-response | itself (current structure is the baseline) + `MutationToolInvariantsTest` "thin bean" assertions | exact (in-place) |
| **MODIFY** `MutationAttributeBinder.java` (two-pass FK prefetch) | service / persistence | batch + transform | itself (`coerceAttributeValue` lines 207-242 is the per-ref baseline) | exact (in-place) |
| **MODIFY** `RelatedWriteMetadataResolver.java` (memoize + compute seam) | service / metamodel | transform + memo | `CancellationRegistry.java` / `StreamingSinkHolder.java` (`ConcurrentHashMap` house pattern) | exact (memo precedent) |
| **MODIFY** `MutationSaveExecutor.java` | persistence (`@Transactional` boundary) | request-response | itself (reference-only; no change beyond chain call site) | exact (in-place) |
| **EXTEND** `MutationToolInvariantsTest.java` | test (source/reflection invariant) | file-I/O + reflection | itself (`Files.readString` + `indexOf` + `countOccurrences` idiom) | exact (extend in place) |
| **NEW** `MutationFkBatchLoadQueryCountTest.java` | test (SELECT-count proxy) | event-driven (JDBC count) | `performance/ToolQueryCountBaselineTest.java` + `QueryCountingDataSourceConfiguration.java` | exact (narrowed-boot + datasource-proxy recipe) |
| **NEW** `RelatedWriteMetadataMemoTest.java` | test (call-count seam) | event-driven (AtomicInteger) | `BuiltInMutationToolsBulkSavePartialFailureTest` (AtomicInteger counting idiom) + `RelatedWriteMetadataResolverTest` (concrete-metamodel) | role-match |
| **POSSIBLE NEW** agentstore FK fixture + module | test fixture / config | persistence | `fixture/MutationLinkedChildFixture.java` (`@ManyToOne` shape) + `MutationFixturePersistenceTestConfiguration.java` (`@JmixModule`) | exact (only `@Store("agentstore")` differs) |

---

## Pattern Assignments

### NEW `MutationGateChain.java` (orchestrator `@Component`, request-response / template-method) — MUT-15

**Primary analog (house `@Component` + constructor-injection style):** `MutationSaveExecutor.java` lines 26-33
```java
@Component
public class MutationSaveExecutor {

    private final DataManager dataManager;

    public MutationSaveExecutor(DataManager dataManager) {
        this.dataManager = dataManager;
    }
```
Mirror this exactly for the chain: a plain `@Component` (NO `@ConditionalOnProperty` — the conditional stays on `BuiltInMutationTools`; the chain is an unconditional collaborator bean it injects), constructor injection of the existing collaborators (`MutationAuthorizationService`, `MutationAttributeBinder`, `MutationRequestHasher`, `MutationIntentRepository`, `MutationSaveExecutor`, `MutationCommitCoordinator`, `MutationErrorTranslator`, `RelatedWriteMetadataResolver`, `DiffSerializer`, `DataManager`, `MetadataTools`, `CurrentAuthentication`, `AiAgentMutationProperties`, `AccessManager`).

**Constructor-injection breadth analog:** `BuiltInMutationTools.java` lines 88-120 — the 18-field constructor-injection block is the current owner of these collaborators; the chain takes over the orchestration subset. **DO NOT field-inject; the whole module is constructor-injection.**

**The ordered gate sequence to mirror verbatim (the source-level ORDER invariant enabler, D-01):** `BuiltInMutationTools.createRecord` (per RESEARCH lines 232-243):
```java
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
Each step becomes a named private method (`enforceRole` / `resolve` / `authorize` / `reserve` / `coerce` / `guard` / `save` / `finalize`) called in order from one `execute(MutationRequest)` so the invariant test can assert strictly-increasing `indexOf` of the method-call tokens, and that the `mutationSaveExecutor.save/saveAll/bulkSave` token index exceeds all gate tokens (D-14).

**Critical anti-pattern (assert by reflection in the invariant test):** `MutationSaveExecutor` carries `@Transactional` (lines 36, 45, 64) — the chain MUST NOT. See its class javadoc lines 9-25 for the self-invocation rationale; replicate that javadoc warning on `MutationGateChain`. Per-`execute` mutable state (`commitState`, `reservation`, `failedRowIndex`, `startedAt`, `userUsername`, `metaClass`) MUST be method locals or a per-call context object — NEVER instance fields (singleton concurrency, RESEARCH Pitfall 2).

---

### NEW `MutationRequest.java` (sealed interface + record variants, transform/dispatch) — MUT-15 (D-02)

**Analog (exact):** `orchestration/StreamingEvent.java` lines 25-89 — a `sealed interface` whose permitted subtypes are nested `record` variants in the same file, consumed via exhaustive `switch`.
```java
public sealed interface StreamingEvent
        permits StreamingEvent.Content,
                StreamingEvent.ToolCall,
                StreamingEvent.ToolResult,
                StreamingEvent.Citation,
                StreamingEvent.Activity,
                StreamingEvent.Final,
                StreamingEvent.Error {

    record Content(String markdownChunk) implements StreamingEvent {}
    record ToolCall(UUID toolCallId, String toolName, String argsJson) implements StreamingEvent {}
    // ... variants carry only the data that differs between cases
    record Error(String messageKey, Map<String, Object> params) implements StreamingEvent {}
}
```
Mirror this shape for `MutationRequest permits Create, Update, AddRelated, RemoveRelated, Bulk`. Each record carries only the divergent payload (per RESEARCH Pattern 1): the `Bulk` variant carries the per-row list + the bulk-only `AccessDeniedException → BLOCKED/row_access_denied` concern; each variant carries its `argumentsJson` serializer selection and success-result shape. Note `StreamingEvent` also shows the house **back-compat secondary-constructor** idiom (lines 44-46, 84-86) if a variant needs a convenience constructor.

**Compiler-enforced exhaustiveness analog:** `filter/FilterNode.java` lines 18-29 — documents "exactly four permitted subtypes ... exhaustive `switch` ... the Java compiler enforces exhaustiveness." This is the load-bearing reason D-01 chose sealed dispatch over chain-of-responsibility; cite it in the `MutationRequest` javadoc. (Jackson `@JsonTypeInfo` from `FilterNode` is NOT needed — `MutationRequest` is built in-process by the adapters, not deserialized.)

---

### NEW `Result` holder + `record Key(...)` (inside `RelatedWriteMetadataResolver.java`, transform/memo) — MUT-17 (D-10/D-11/D-12)

**Nested-record analog (exact, same file):** `RelatedWriteMetadataResolver.SupportedRelatedRelationship` lines 98-101
```java
public record SupportedRelatedRelationship(
        MetaProperty parentProperty,
        MetaClass childMetaClass,
        MetaProperty childInverseProperty) { }
```
Add `record Key(String parentEntityName, String relationshipName) {}` in the same nested style (D-11: key on `parentMetaClass.getName()`, never the raw `MetaClass`). For the `Result` holder use a tiny sealed/`Optional`-style type (see `StreamingEvent` for the sealed-variant idiom) that carries EITHER a `SupportedRelatedRelationship` OR a "rejected" marker — never a live throwable (D-12).

**Canned-error rebuild contract (do NOT cache the exception):** `RelatedWriteMetadataResolver.unsupportedRelationship()` lines 307-314 is already a `private static` no-arg factory with no LLM data inside — on a cached rejection, rethrow a *fresh* `unsupportedRelationship()` so the error is byte-identical (D-12, RESEARCH Pitfall 8):
```java
private static ToolUserError unsupportedRelationship() {
    return new ToolUserError("validation_failed",
            "relationship is not supported by add/remove_related_record",
            List.of(...));
}
```

**ConcurrentHashMap memo + compute-seam shape:** target the public entry point `resolveSupportedRelatedWriteRelationship` lines 110-172. Mirror RESEARCH lines 247-260:
```java
private final Map<Key, Result> cache = new ConcurrentHashMap<>();
// public method: cache.computeIfAbsent(key, k -> computeSupported(parentMetaClass, relationshipName));
// then: if (r.rejected()) throw unsupportedRelationship();  else return r.relationship();
```
Extract the entire current walk body (lines 113-171) into a **package-private** `computeSupported(...)` / `walk(...)` so the pure-JUnit `AtomicInteger` proxy (D-13/D-16) can subclass/override and count it.

---

### MODIFY `RelatedWriteMetadataResolver.java` memoization — house `ConcurrentHashMap` precedent

**Analog (exact house pattern):** `rag/CancellationRegistry.java` lines 33-37, 63-66
```java
@Component
public class CancellationRegistry {
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    ...
    public long currentGeneration(UUID id) {
        if (id == null) return 0L;
        return generations.computeIfAbsent(id, k -> new AtomicLong()).get();
    }
```
Also `orchestration/StreamingSinkHolder.java` lines 28-39 (`private final ConcurrentMap<...> = new ConcurrentHashMap<>();` + `put`/`remove`). Both confirm: plain `ConcurrentHashMap` field, `computeIfAbsent`, no eviction, no new dependency — exactly D-10. The immutable Jmix metamodel means no eviction logic (cite the `CancellationRegistry` "single-JVM acceptable" javadoc tone for the memo's no-eviction note).

---

### MODIFY `MutationAttributeBinder.java` two-pass FK prefetch (service, batch + transform) — MUT-16

**Per-reference baseline to preserve byte-for-byte (the N+1 being fixed):** `coerceAttributeValue` lines 223-231
```java
MetaClass targetMetaClass = property.getRange().asClass();
mutationAuthorizationService.enforceLlmRelationshipTargetExposure(targetMetaClass, false);
UUID targetId = requireUuidId(toolEntityResolver.parseEntityId(rawValue.toString(), targetMetaClass));
mutationAuthorizationService.enforceReadPermission(targetMetaClass);   // <-- ONLY access_denied source
return dataManager.load(targetMetaClass.getJavaClass())
        .id(targetId)
        .optional()
        .orElseThrow(() -> mutationErrorTranslator.notFound(targetMetaClass, rawValue.toString())); // empty => not_found
```
**Constructor-injection block to extend (no signature break):** lines 76-88 already hold `dataManager`, `toolEntityResolver`, `mutationAuthorizationService`, `mutationErrorTranslator` — everything the prefetch pass needs; add the two-pass methods (`prefetchReferences` + a `coerceAttributes` overload binding from the prefetched map) WITHOUT a new collaborator (D-06 rejected a separate `FkReferenceBatchLoader`).

**Error-parity contract (D-08, RESEARCH Pitfalls 4/5/6):**
- Call `enforceLlmRelationshipTargetExposure` + `enforceReadPermission` **once per collected target class** in the prefetch pass (identical entity-level `access_denied` timing).
- Use constrained `DataManager.load(class).ids(...)` only — never `UnconstrainedDataManager`, never raw JPQL (D-09; verify `.ids(Collection)` vs varargs against Jmix 2.8, RESEARCH A1).
- Bind the **loaded entity instance**, not the id (guards receive typed refs).
- Bind pass iterates rows in **submission order**; absent FK id → `mutationErrorTranslator.notFound(targetMetaClass, rawValue)` at that row's index (`failedRowIndex` re-attribution).
- `add_related_record`/`remove_related_record` do NOT go through `coerceAttributeValue` (RESEARCH Pitfall 7) — scope MUT-16 to the `coerceAttributes` path only.

---

### MODIFY `BuiltInMutationTools.java` (5 `@Tool` → thin adapters) — MUT-15 (D-03)

Adapters: build the `MutationRequest` variant → `mutationGateChain.execute(request)` → format the per-variant success result. The "thin bean" contract is already asserted by `MutationToolInvariantsTest` (`builtInMutationTools_hasNoDirectAuditWriterUsage`, lines 121-131) — preserve those passing. The `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")` (lines 83-85) and the no-`@Transactional` contract (class javadoc lines 67-71) stay on this class.

---

## Shared Patterns

### Pattern: House `@Component` + constructor injection
**Source:** `MutationSaveExecutor.java` lines 26-33; `MutationAttributeBinder.java` lines 52-88; `BuiltInMutationTools.java` lines 83-120
**Apply to:** `MutationGateChain` (new). Constructor injection only — no `@Autowired` fields, no field injection (project CLAUDE.md "Services: Constructor injection only").

### Pattern: Sole `@Transactional` boundary; orchestrators carry none
**Source:** `MutationSaveExecutor.java` lines 9-25 (javadoc) + lines 36/45/64 (the only `@Transactional` annotations in the package); `BuiltInMutationTools.java` javadoc lines 67-71
**Apply to:** `MutationGateChain` — assert `@Transactional` absence by reflection on the class + declared methods (D-04, D-14). Replicate the self-invocation-pitfall javadoc.

### Pattern: Sealed interface + nested record variants + exhaustive switch
**Source:** `orchestration/StreamingEvent.java` lines 25-89; `filter/FilterNode.java` lines 18-29
**Apply to:** `MutationRequest` (new) and the `Result` holder. Compiler-enforced exhaustiveness is the design rationale (D-01/D-02).

### Pattern: `ConcurrentHashMap` memo, no eviction, no new dependency
**Source:** `rag/CancellationRegistry.java` lines 37/63-66; `orchestration/StreamingSinkHolder.java` lines 28-39
**Apply to:** `RelatedWriteMetadataResolver` memo (MUT-17). `computeIfAbsent` keyed on a `record Key`.

### Pattern: Pure-JUnit source/reflection invariant (no Spring, no mocks)
**Source:** `MutationToolInvariantsTest.java`
**Apply to:** extended `MutationToolInvariantsTest`. The exact idiom to copy:
```java
String src = Files.readString(SOME_FILE, StandardCharsets.UTF_8);
int aIdx = src.indexOf("tokenA");
int bIdx = src.indexOf("tokenB");
assertThat(bIdx).isGreaterThan(aIdx);          // ORDER invariant
long n = countOccurrences(src, "needle");      // helper at lines 161-169
```
Plus `resolveModuleRoot()` (lines 176-195) for working-dir-independent path resolution, and `Files.walk(MUTATION_PACKAGE)` (lines 68-72) to scan the package. New assertions to add: gate-ORDER strictly-increasing `indexOf` on `MutationGateChain.java`; save-token index > all gate tokens; `@Transactional`-absence **by reflection** on `MutationGateChain.class` + declared methods (NOT regex — D-14); forbidden-token source-scan (`UnconstrainedDataManager`, raw-JPQL markers) on `MutationAttributeBinder.java` (MUT-16).

### Pattern: AtomicInteger call-counting seam (pure JUnit, no Mockito InOrder)
**Source:** `BuiltInMutationToolsBulkSavePartialFailureTest.java` line 193 (`AtomicInteger crudCallCount = new AtomicInteger();`); concrete-metamodel resolver tests in `RelatedWriteMetadataResolverTest.java` lines 40-58
**Apply to:** new `RelatedWriteMetadataMemoTest`. Wrap/override the package-private `computeSupported` seam, increment an `AtomicInteger`, call `resolve` twice for one `Key`, assert the walk advanced exactly once — for BOTH a supported and an unsupported key (D-16/D-17, no Mockito InOrder).

### Pattern: Narrowed-boot SELECT-count harness (datasource-proxy)
**Source:** `performance/ToolQueryCountBaselineTest.java` lines 50-58 (boot recipe), 69-118 (count helpers, warmup, slope probe); `performance/QueryCountingDataSourceConfiguration.java`
**Apply to:** new `MutationFkBatchLoadQueryCountTest`. Copy the recipe exactly:
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class,
        com.vn.autoconfigure.agent.AiToolsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        QueryCountingDataSourceConfiguration.class})
```
Plus the count helpers — `QueryCountHolder.clear()` in `@BeforeEach`, `safeSelectCount()` (lines 101-104, null-defensive), `measureSteadyStateSelects(...)` warmup (lines 113-118), and the **slope probe** (lines 266-295: small vs large K, assert `countLarge - countSmall` ≈ 0). Add `ai-agent.tools.mutation.enabled=true` + `MutationToolTestUsersConfiguration` (as the gating tests do, RESEARCH A3). **Do NOT use bare full-autoconfig `@SpringBootTest`** — module boot regression (RESEARCH "Deprecated/outdated").
**Open Question 1 (planner must resolve first):** the harness wraps only `agentstoreDataSource` (`QueryCountingDataSourceConfiguration` lines 22-28, 40); the existing FK fixture `MutationLinkedChildFixture.linkedParent` is **main-store**. Either (a) add an `@Store("agentstore")` FK fixture, or (b) widen the counting config to also wrap the main `dataSource`. (a) is the lower-divergence choice.

### Pattern: Test-only Jmix module for fixture persistence
**Source:** `MutationFixturePersistenceTestConfiguration.java` (`@Configuration` + `@JmixModule(id=..., dependsOn={...})`); `fixture/MutationLinkedChildFixture.java` (`@ManyToOne(fetch=LAZY)` + `@JoinColumn` optional FK shape)
**Apply to:** possible new agentstore FK fixture (Open Q1 option a) — copy `MutationLinkedChildFixture` shape but add `@Store("agentstore")`, register via the `@JmixModule` pattern.

---

## No Analog Found

None. Every file in scope has a same-module analog (the work is in-place refactor + house-idiom replication).

## Metadata

**Analog search scope:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/`, `.../orchestration/`, `.../rag/`, `.../filter/`; test sources under `.../tools/mutation/` and `.../performance/`.
**Files scanned:** 15 main-source mutation files, 28 test-source mutation files, `StreamingSinkHolder`, `CancellationRegistry`, `StreamingEvent`, `FilterNode`, `ToolQueryCountBaselineTest`, `QueryCountingDataSourceConfiguration`, `MutationFixturePersistenceTestConfiguration`, `MutationLinkedChildFixture`.
**Pattern extraction date:** 2026-05-31
