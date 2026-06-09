# Phase 18: AI-Runtime Performance Pass (targeted) - Pattern Map

**Mapped:** 2026-06-09
**Files analyzed:** 12 (5 production-modified, 1 test-body-edit, ~6 new test classes)
**Analogs found:** 12 / 12 (every modified/new file has a verified in-repo analog)

> This is a TARGETED memoization phase. It creates **no new production classes** — it adds cache fields/slots to already-shipped classes and adds new test classes. Every "analog" is therefore either (a) the exact insertion-point pattern already present in the file being modified, or (b) a sibling class shipped in Phase 16/17 that the new code copies verbatim.

All paths below are absolute under `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\`.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `main/.../orchestration/RunContext.java` *(modify)* | orchestration / per-turn carrier | event-driven (turn lifecycle) | itself (existing 12 ThreadLocal slots + `clear()`) + `guard/IterationCounter.java` | exact (self-pattern) |
| `main/.../exposure/LlmExposurePolicy.java` *(modify)* | service / exposure gate | request-response + CRUD-verdict | `tools/mutation/RelatedWriteMetadataResolver.java` (ConcurrentHashMap memo) + `exposure/AiExposureRuleEntityListener.java` (`@EventListener`) | exact |
| `main/.../orchestration/BaselineContextProvider.java` *(modify)* | service / prompt-context | transform / read-through | itself (`compose()` `:112` read site) → reads `LlmExposurePolicy` cache | role-match (read-through only) |
| `main/.../rag/RetrievalFilterBuilder.java` *(modify)* | service / RAG filter | transform / request-response | itself (`buildFor` `:77`) → reuses PERF-02 denylist cache | role-match (build-once) |
| `main/.../taskfile/AiTaskFileMediaResolver.java` *(modify)* | service / file-I/O | file-I/O + transform (encode) | itself (`resolveActive` `:155`, already once/turn) | role-match (regression-lock per D-10) |
| `main/.../orchestration/AiUiSettingsResolver.java` *(modify)* | service / settings read-through | CRUD (singleton read) | itself (`resolveTaskFile*`) + `AiSettingsChangedEvent` evictor | role-match |
| `test/.../performance/ToolQueryCountBaselineTest.java` *(SINGLE body edit)* | test / SELECT-count proxy | — | itself (line 151 ceiling constant) | exact (one constant) |
| **NEW** `test/.../*PerTurn*Memo*Test.java` (PERF-01) | test / call-count + ThreadLocal-empty | — | `tools/mutation/RelatedWriteMetadataMemoTest.java` | exact template |
| **NEW** PERF-02 "one fetch reused until event" call-count test | test / Mockito spy call-count | — | `admin/config/AiUiSettingsResolverReadThroughTest.java` (real-unit + mocked edges) | exact template |
| **NEW** PERF-02 "subscribes to event" reflection/source-scan invariant | test / pure-JUnit invariant | — | `admin/config/AiSettingsChangedEventListenerInvariantTest.java` (`singlePublishSiteSourceScan`) | exact template |
| **NEW** PERF-03 `RetrievalFilterBuilder.buildFor` `times(1)` test | test / Mockito spy call-count | — | `RelatedWriteMetadataMemoTest.java` (counting seam) + existing `RetrievalFilterBuilderDenylistTest` (must-pass) | exact template |
| **NEW** PERF-04 encode call-count test (FileStorage/Tika/resolveTaskFile*) | test / call-count or SELECT-slope | — | `performance/MutationFkBatchLoadQueryCountTest.java` (slope) or `RelatedWriteMetadataMemoTest` (call-count) | role-match |
| **NEW** PERF-05 build-dep invariant + settings-visible-next-turn test | test / source/dep scan + integration | — | `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan` (file-walk scan) | exact template |

## Pattern Assignments

### `orchestration/RunContext.java` (modify — D-01, PERF-01)

**Analog:** itself — the existing 12 ThreadLocal slots (`:31-42`) + `clear()` (`:142-155`). Secondary: `guard/IterationCounter.java` (lazy-init + `reset()` ThreadLocal precedent).

**Existing ThreadLocal-slot declaration pattern** (`RunContext.java:31-42`):
```java
private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
private static final ThreadLocal<UUID> ROOT_AUDIT_ID = new ThreadLocal<>();
private static final ThreadLocal<List<Media>> TASK_FILE_MEDIA = new ThreadLocal<>();
```
Add ONE new slot alongside these, e.g.:
```java
private static final ThreadLocal<Map<Object, Object>> PER_TURN_CACHE = new ThreadLocal<>();
```

**Lazy-init safe-miss accessor pattern** — copy the `IterationCounter.current()` null-guard shape (`IterationCounter.java:28-31`), which returns a benign default when the ThreadLocal is unset on a foreign thread (this IS the D-02 safe miss):
```java
public static int current() {
    Integer v = COUNT.get();
    return v == null ? 0 : v;        // ◄── foreign-thread null = safe default, never stale
}
```
New accessor (D-01 target shape from RESEARCH `:220-224`):
```java
public static Map<Object, Object> perTurnCache() {
    Map<Object, Object> m = PER_TURN_CACHE.get();
    if (m == null) { m = new HashMap<>(); PER_TURN_CACHE.set(m); }
    return m;
}
```

**The non-negotiable wipe** — add ONE line to the existing `clear()` body (`RunContext.java:142-155`), which already `.remove()`s every slot in a `finally` called by `AuditAdvisor`:
```java
public static void clear() {
    CURRENT.remove();
    ROOT_AUDIT_ID.remove();
    // ... existing 10 removes ...
    PREPARE_FORM_DRAFT_INVOKED.remove();
    PER_TURN_CACHE.remove();          // ◄── NEW (D-01): wipe the per-turn cache
}
```

---

### `exposure/LlmExposurePolicy.java` (modify — D-05/D-06/D-07, PERF-01/PERF-02)

**Analog (app-wide memo):** `tools/mutation/RelatedWriteMetadataResolver.java` — `ConcurrentHashMap` + `computeIfAbsent` field.
**Analog (event eviction):** `exposure/AiExposureRuleEntityListener.java:31-34` — `@EventListener` shape.

**ConcurrentHashMap memo field + computeIfAbsent idiom** (copy from `RelatedWriteMetadataResolver.java:136,160-167`):
```java
// field (RelatedWriteMetadataResolver.java:136)
private final Map<Key, Result> cache = new ConcurrentHashMap<>();

// usage (RelatedWriteMetadataResolver.java:160-167)
Result result = cache.computeIfAbsent(key, k -> {
    try {
        return Result.of(computeSupported(parentMetaClass, relationshipName));
    } catch (ToolUserError rejection) {
        return Result.reject();
    }
});
```

**Current per-call hot path being memoized** (`LlmExposurePolicy.java:121-125`) — the private self-invoked method from D-07. It is called from `getReadableSchema:48`, `canReadEntity:64`, `canCreate:86`, `canUpdate:99`, `getDenylistedEntityNames:118`:
```java
private Set<String> hiddenEntityNames() {
    Set<String> hidden = new LinkedHashSet<>(AiInternalEntityNames.all());
    hidden.addAll(ruleRepository.findEnabledExcludedEntityNames());   // ◄── one agentstore SELECT/call
    return hidden;
}
```
**Target shape** (D-05, RESEARCH `:199-211`): wrap in a single-sentinel `ConcurrentHashMap`:
```java
private static final String DENYLIST_KEY = "denylist";
private final Map<String, Set<String>> denylistCache = new ConcurrentHashMap<>();

private Set<String> hiddenEntityNames() {
    return denylistCache.computeIfAbsent(DENYLIST_KEY, k -> {
        Set<String> hidden = new LinkedHashSet<>(AiInternalEntityNames.all());
        hidden.addAll(ruleRepository.findEnabledExcludedEntityNames());
        return hidden;
    });
}
```

**Event-eviction listener** — copy the `@EventListener` form from `AiExposureRuleEntityListener.java:31-34`:
```java
@EventListener
public void onExposureRuleChanged(EntityChangedEvent<AiExposureRule> event) {
    eventPublisher.publishEvent(new LlmExposureChangedEvent(this));
}
```
New consumer inside `LlmExposurePolicy` (first-ever consumer of the dormant event per `LlmExposureChangedEvent.java:6-8`):
```java
@EventListener(LlmExposureChangedEvent.class)
void onExposureChanged(LlmExposureChangedEvent e) { denylistCache.clear(); }
```

**Per-turn read-through (PERF-01)** for `canCreate`/`canUpdate` CRUD verdicts (`LlmExposurePolicy.java:82-100`) — these build a fresh `CrudEntityContext` + `accessManager.applyRegisteredConstraints` per call; route them through `RunContext.perTurnCache()` keyed by `(metaClassName, op)` (D-08). The denylist portion reuses the PERF-02 `denylistCache`.

> **FORBIDDEN (D-07):** do NOT annotate `hiddenEntityNames()` / `getDenylistedEntityNames()` with `@Cacheable` — private self-invocation means the Spring proxy caches nothing on 4/5 hot paths.

---

### `orchestration/BaselineContextProvider.java` (modify — PERF-01 read-through)

**Analog:** itself — the `compose()` read site (`:112`). No new mechanism; the cache lives in `LlmExposurePolicy`, so this file only benefits transparently.

**Read site (`BaselineContextProvider.java:112-119`)** — `getReadableSchema()` is called once per `compose()`; the D-04 cache-key invariant lives here (`agent.permissions` locale-free `:111`; `agent.entities` locale-bearing `:115`):
```java
Map<MetaClass, Set<String>> readableSchema = llmExposurePolicy.getReadableSchema();
List<MetaClass> visibleEntities = visibleEntities(readableSchema);
if (!visibleEntities.isEmpty()) {
    ctx.put("agent.entities", renderEntitiesBlock(visibleEntities, readableSchema.size()));
    String permissionsJson = renderPermissionsJson(visibleEntities, readableSchema);
    ...
}
```
Per D-04, keep locale OUT of the `agent.permissions` / CRUD-verdict key; keep locale IN the `agent.entities` key. The `// NEVER cached — labels are locale-sensitive` comment at `:110-111` is the invariant the cache-key shape must honor.

---

### `rag/RetrievalFilterBuilder.java` (modify — PERF-03)

**Analog:** itself — `buildFor` (`:77`). Reuses the PERF-02 denylist cache via the existing `getDenylistedEntityNames()` call (`:99`).

**Denylist reuse + verbatim NIN clause (`RetrievalFilterBuilder.java:99-103`)** — must be preserved byte-for-byte (PERF-03 verbatim-clause guard):
```java
Set<String> denied = llmExposurePolicy.getDenylistedEntityNames();   // ◄── now hits PERF-02 cache
FilterExpressionBuilder.Op exposureClause = null;
if (!denied.isEmpty()) {
    exposureClause = b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied));
}
```
Role extraction (`:78-84`) stays request-fresh. Build the `Filter.Expression` once per retrieval; do NOT remove "redundant" clauses (the WARNING-11 model-pin duplication at `:120-131` is intentional).

---

### `taskfile/AiTaskFileMediaResolver.java` (modify — PERF-04, regression-lock-first per D-10)

**Analog:** itself — `resolveActive` (`:155`), already invoked once per turn (`DefaultChatServiceImpl:350,617`).

**Settings-resolver reads inside `resolveActive` (`AiTaskFileMediaResolver.java:162,179,180`)** — each `resolveTaskFile*` reloads the `AiUiSettings` singleton:
```java
boolean ttlDisabled = aiUiSettingsResolver.resolveTaskFileTtlSeconds() == -1L;        // :162
int maxFiles = aiUiSettingsResolver.resolveTaskFilePerTurnMaxFiles();                  // :179
long maxBytes = aiUiSettingsResolver.resolveTaskFilePerTurnMaxTotalBytes();            // :180
```
Per D-10 scope discipline: run the proxy first. Where `resolveActive` / per-file encode (`FileStorage` read `:357`, Tika `:320-351`) already runs once per `(convId,taskFileId)` per turn, ship a **regression assertion**, not a new cache. Only add the per-`(convId,taskFileId)` memo if the proxy shows a second in-turn encode.

---

### `orchestration/AiUiSettingsResolver.java` (modify — PERF-04 settings memo, optional)

**Analog:** itself — the `resolveTaskFile*` singleton-read methods; eviction via the Phase-16 `AiSettingsChangedEvent(UI_SETTINGS)`. If a per-turn memo of the singleton read is added, evict it on `AiSettingsChangedEvent` exactly as the denylist evicts on `LlmExposureChangedEvent`.

---

### `performance/ToolQueryCountBaselineTest.java` (the ONLY allowed test-body edit — D-10, PERF-02)

**Analog:** itself — line 151 constant.

**Change EXACTLY this one line (`ToolQueryCountBaselineTest.java:151`)** and nothing else:
```java
private static final long METAMODEL_TOOL_POLICY_LOOKUP_CEILING = 5L;   // ◄── lower 5L → new value
```
**Must stay byte-for-byte unchanged** — the two assertion sites that read it (`:160-164` `listEntities_metamodelPlusPolicyLookupOnly`, `:176-180` `describeEntity_metamodelPlusPolicyLookupOnly`):
```java
assertThat(selectsOnSecondCall)
        .as("list_entities steady-state SELECT count (observed=%d, ceiling=%d) — "
                + "metamodel read + LlmExposurePolicy denylist lookup",
                selectsOnSecondCall, METAMODEL_TOOL_POLICY_LOOKUP_CEILING)
        .isLessThanOrEqualTo(METAMODEL_TOOL_POLICY_LOOKUP_CEILING);
```
Do NOT touch the four `*_STEADY_STATE_CEILING = 1000L` constants (`:192-195`) or the slope test.

---

### NEW PERF-01 call-count + ThreadLocal-empty test

**Analog:** `tools/mutation/RelatedWriteMetadataMemoTest.java` — pure-JUnit counting-subclass-over-package-private-seam template (no Spring, no Mockito where avoidable).

**Counting-seam pattern (`RelatedWriteMetadataMemoTest.java:56-76`)** — subclass overrides the package-private seam, bumps an `AtomicInteger`, returns a canned outcome:
```java
private static final class CountingResolver extends RelatedWriteMetadataResolver {
    private final AtomicInteger walkCount = new AtomicInteger();
    @Override
    SupportedRelatedRelationship computeSupported(MetaClass parentMetaClass, String relationshipName) {
        walkCount.incrementAndGet();
        ...
    }
    int walkCount() { return walkCount.get(); }
}
```
**Walk-once assertion (`:110-113`)** → adapt to "N tool calls drive `hiddenEntityNames`/schema resolution exactly once":
```java
assertThat(resolver.walkCount())
        .as("repeated key must walk EXACTLY ONCE — memoized")
        .isEqualTo(1);
```
**Mandatory add (D-09):** after driving N calls, assert the per-turn cache is empty after `RunContext.clear()`:
```java
RunContext.clear();
assertThat(RunContext.perTurnCache()).isEmpty();   // no cross-turn / cross-user bleed
```

---

### NEW PERF-02 "one fetch reused until event, then exactly one refetch" call-count test

**Analog:** `admin/config/AiUiSettingsResolverReadThroughTest.java` — real-unit-under-test + Mockito-mocked edges; `@Tag("unit")`; no Spring boot.

**Setup pattern (`AiUiSettingsResolverReadThroughTest.java:66-106`)** — construct the real bean, mock only the repository/data edges:
```java
@BeforeEach
void setUp() {
    unconstrainedDataManager = mock(UnconstrainedDataManager.class);
    ...
    when(unconstrainedDataManager.load(AiUiSettings.class)).thenReturn(loader);
    when(loader.id(any())).thenReturn(byId);
    when(byId.optional()).thenAnswer(invocation -> Optional.of(columnsBackingRow));
    resolver = new AiUiSettingsResolver(unconstrainedDataManager, ...);
}
```
For PERF-02: construct a real `LlmExposurePolicy` with a Mockito `spy`/`mock` `LlmExposureRuleRepository`; call the gates N times; `verify(repository, times(1)).findEnabledExcludedEntityNames()`; fire `onExposureChanged(new LlmExposureChangedEvent(this))`; call again; `verify(..., times(2))`.

---

### NEW PERF-02 "every exposure-derived cache subscribes to the event" invariant

**Analog:** `admin/config/AiSettingsChangedEventListenerInvariantTest.java` — `singlePublishSiteSourceScan()` (`:228-260`), a `Files.walkFileTree` + regex source scan; `@Tag("unit")`.

**File-walk + regex scan pattern (`:229-259`)** — invert it to assert the consumer side (`@EventListener(LlmExposureChangedEvent` appears in `LlmExposurePolicy.java`):
```java
Path mainJavaRoot = findMainJavaRoot();
Pattern publishPattern = Pattern.compile(
        "publishEvent\\s*\\(\\s*new\\s+AiSettingsChangedEvent", Pattern.DOTALL);
Set<String> offenders = new TreeSet<>();
Files.walkFileTree(mainJavaRoot, new SimpleFileVisitor<>() {
    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
        if (publishPattern.matcher(Files.readString(file)).find()) offenders.add(file.getFileName().toString());
        return FileVisitResult.CONTINUE;
    }
});
assertThat(offenders).isEqualTo(expected);
```
Reuse `findMainJavaRoot()` (`:322-343`) verbatim. The D-09 boundary leg uses the SAME scan to assert the per-turn cache symbol appears ONLY in `LlmExposurePolicy.java` + `RunContext.java`, never in `BuiltInDataTools.java`.

---

### NEW PERF-03 `buildFor` `times(1)`-per-retrieval test

**Analog:** `RelatedWriteMetadataMemoTest.java` counting seam (call-count) + the existing must-pass `rag/RetrievalFilterBuilderDenylistTest.java` (verbatim-clause guard — assert unchanged).
Spy/`verify(times(1))` on `RetrievalFilterBuilder.buildFor` per retrieval; confirm the NIN clause (`RetrievalFilterBuilder.java:99-103`) is preserved.

---

### NEW PERF-04 encode call-count test

**Analog (call-count):** `RelatedWriteMetadataMemoTest.java` counting seam, OR
**Analog (SELECT-slope):** `performance/MutationFkBatchLoadQueryCountTest.java` — `countLarge - countSmall <= 1` slope detector.

**Slope-test body pattern (`MutationFkBatchLoadQueryCountTest.java:162-181`)** — warm up, measure small, measure large, assert slope ≤ 1:
```java
measureBatchSelects(10, parentId);                 // warm-up primes caches
long countSmall = measureBatchSelects(10, parentId);
long countLarge = measureBatchSelects(100, parentId);
assertThat(countLarge - countSmall)
        .as("SELECT count must NOT scale ...")
        .isLessThanOrEqualTo(1L);
```
**QueryCount harness usage (`:97-106,140-141`):**
```java
@BeforeEach void resetCountsBeforeEachTest() { QueryCountHolder.clear(); }
private static long safeSelectCount() {
    QueryCount qc = QueryCountHolder.get(COUNTING_DS);
    return qc == null ? 0L : qc.getSelect();
}
```
Per D-10/Pitfall 5: if the proxy shows encode already runs once per `(convId,taskFileId)` per turn, ship the regression assertion instead of a cache; keep `PerTurnMediaInjectionTest` unchanged.

---

### NEW PERF-05 build-dependency invariant + settings-visible-next-turn test

**Analog:** `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan` (file-walk scan) for the dep-scan leg; the publish-contract `verify(publisher, ...)` methods (`:95-222`) for the settings-eviction integration leg.
Dep-scan: walk `ai-agent.gradle` / `build.gradle` and assert no `caffeine` / `jmh` / `gatling` token. Settings leg: publish `AiSettingsChangedEvent(UI_SETTINGS)`, assert the next resolve reflects the change.

## Shared Patterns

### ConcurrentHashMap + computeIfAbsent memo (app-wide caches)
**Source:** `tools/mutation/RelatedWriteMetadataResolver.java:136,160-167`
**Apply to:** `LlmExposurePolicy` denylist cache (with `.clear()` eviction) and entity-name→`MetaClass` memo (no eviction).
```java
private final Map<Key, Result> cache = new ConcurrentHashMap<>();
Result result = cache.computeIfAbsent(key, k -> { /* compute */ });
```

### ThreadLocal-slot + clear()-in-finally (per-turn cache)
**Source:** `orchestration/RunContext.java:31-42,142-155`; `guard/IterationCounter.java:17,28-31,41-43`
**Apply to:** the new `RunContext` per-turn cache slot. Lazy-init returns a safe miss (`null`→recompute) on foreign streaming threads (D-02); `clear()` wipes it (D-01).

### @EventListener cache eviction
**Source:** `exposure/AiExposureRuleEntityListener.java:31-34`; event `exposure/LlmExposureChangedEvent.java` (dormant, this phase is its first consumer)
**Apply to:** `LlmExposurePolicy.onExposureChanged → denylistCache.clear()`; settings caches evict on `AiSettingsChangedEvent` (Phase 16).

### Pure-JUnit source-scan / call-count invariant (replaces ArchUnit)
**Source:** `admin/config/AiSettingsChangedEventListenerInvariantTest.java:228-260,322-343` (file-walk + regex); `tools/mutation/RelatedWriteMetadataMemoTest.java:56-76` (counting seam); `admin/config/AiUiSettingsResolverReadThroughTest.java:66-106` (real-unit + mocked edges)
**Apply to:** all new D-09 boundary / "subscribes to event" / call-count proxy tests. `@Tag("unit")`, no `@SpringBootTest` (sidesteps the boot regression per D-11).

### datasource-proxy SELECT-count / slope harness
**Source:** `performance/MutationFkBatchLoadQueryCountTest.java:97-106,162-181`; `performance/ToolQueryCountBaselineTest.java:151` (ceiling)
**Apply to:** PERF-02 ceiling lowering and any PERF-04 SELECT-slope proxy. `QueryCountHolder.clear()` in `@BeforeEach`; `safeSelectCount()` null-guard.

## No Analog Found

None. Every file has a verified in-repo analog (the phase is wiring shipped Phase 16/17 patterns into shipped classes, per RESEARCH "this phase is wiring, not invention").

## Metadata

**Analog search scope:** `ai-agent/ai-agent/src/main/java/com/vn/agent/{orchestration,exposure,rag,taskfile,tools/mutation,guard}` and `src/test/java/com/vn/agent/{performance,admin/config,tools/mutation}`
**Files scanned/read in full or in targeted ranges:** 11 source + 4 planning docs
**Pattern extraction date:** 2026-06-09
