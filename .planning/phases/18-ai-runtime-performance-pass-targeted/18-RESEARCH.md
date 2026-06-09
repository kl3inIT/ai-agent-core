# Phase 18: AI-Runtime Performance Pass (targeted) - Research

**Researched:** 2026-06-09
**Domain:** Targeted memoization of per-turn AI-runtime hotspots in a Jmix 2.8 / Spring AI 1.1.4 add-on (`com.vn.agent`)
**Confidence:** HIGH (every locked decision verified against live source with file:line anchors; no external-library claims required)

## Summary

This is a verification-and-grounding pass, not a design pass. The discuss-phase locked 11 decisions (D-01..D-11) and 5 requirements (PERF-01..05). All of them hold against the live code: I confirmed the `RunContext` ThreadLocal-slot + `clear()` shape, the `LlmExposurePolicy.hiddenEntityNames()` private-self-invocation pattern that breaks Spring `@Cacheable` (D-07), the `RelatedWriteMetadataResolver` `ConcurrentHashMap`+`computeIfAbsent` memo idiom that D-05/D-06 mirror, both eviction events (`LlmExposureChangedEvent`, `AiSettingsChangedEvent`) with their single publish sites, the `ToolQueryCountBaselineTest` ceiling constant (`METAMODEL_TOOL_POLICY_LOOKUP_CEILING = 5L`, **line 151** exactly), and the streaming-thread reality that makes the per-turn ThreadLocal cache correct.

**One material drift from CONTEXT.md** (does NOT invalidate any decision, but widens the planning surface): the per-turn memoization read-through surface is **larger than the "5 siblings" in CONTEXT.md**. `canReadEntity` / `canCreate` / `canUpdate` / `getReadableSchema` / `getDenylistedEntityNames` are read from **at least 10 caller classes** outside `LlmExposurePolicy` (BuiltInDataTools, ToolEntityResolver, FetchPlanIntersector, StructuredFilterConditionMapper, ExtractionService, IntentRegistry, MetaClassDtoSynthesizer, DraftLoader, MutationAuthorizationService, MutationCommitCoordinator). The good news: because D-05 puts the cache **inside `LlmExposurePolicy`** (no proxy boundary), every one of those external callers transparently hits the cache. The planner must size the PERF-01/PERF-02 plan to this full caller set, and the D-09 boundary test must assert the cache symbol does NOT leak into the row-data path (`BuiltInDataTools` → constrained `DataManager`).

**Primary recommendation:** Plan exactly to D-01..D-11. Put the per-turn cache as a new `ThreadLocal<Map<CacheKey,Object>>` slot in `RunContext` wiped in `clear()`; put the app-wide denylist + entity-name→MetaClass memos as `ConcurrentHashMap`+`computeIfAbsent` fields **inside `LlmExposurePolicy`** with a `@EventListener(LlmExposureChangedEvent)` `.clear()`; build the RAG filter once per retrieval reusing the cached denylist; memoize task-file `Media` per `(convId, taskFileId)` per turn. Ship the mixed proxy set per D-10. Never use `@Cacheable`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Per-turn schema/exposure memo | Backend / orchestration (`RunContext` ThreadLocal) | — | One turn = one user; ThreadLocal anchored to the chat-turn lifecycle owned by `AuditAdvisor`. Sensitive (user/role) ⇒ must NOT outlive the turn. |
| App-wide denylist cache | Backend / exposure (`LlmExposurePolicy` field) | DB (agentstore `AiExposureRule`) | Exposure-derived, not user-specific; evicted by an admin-write event. |
| Entity-name→MetaClass memo | Backend / exposure (immutable metamodel) | — | Pure Jmix metamodel fact; security-independent; no eviction. |
| RAG `Filter.Expression` build | Backend / RAG (`RetrievalFilterBuilder`) | reuses denylist cache | Role extraction stays request-fresh; denylist portion reuses PERF-02 cache. |
| Task-file `Media` encode | Backend / taskfile (`AiTaskFileMediaResolver`) | FileStorage + Tika; reuses MUT-16 FK batch | Per-`(convId,taskFileId)` per-turn; evicted on attach/delete/TTL + `AiSettingsChangedEvent`. |
| Settings read-through | Backend / orchestration (`AiUiSettingsResolver`) | DB (agentstore `AiUiSettings` singleton) | Memo (if any) evicts on `AiSettingsChangedEvent(UI_SETTINGS)`. |

## Standard Stack

No new dependencies. The phase is constrained to existing primitives, all verified present.

### Core (verified present)
| Library / Primitive | Version | Purpose | Verification |
|---------------------|---------|---------|--------------|
| `java.util.concurrent.ConcurrentHashMap` + `computeIfAbsent` | JDK 21 | App-wide denylist + metadata memos (D-05/D-06) | Used in `RelatedWriteMetadataResolver.java:136,160` `[VERIFIED: codebase grep]` |
| `java.lang.ThreadLocal` | JDK 21 | Per-turn cache slot (D-01) | `RunContext.java:31-42`, `IterationCounter.java:17` `[VERIFIED: codebase]` |
| `spring-boot-starter-cache` (`ConcurrentMapCacheManager`) | Spring Boot 3 | Allowed app-wide cache manager (not used for the sensitive per-turn cache) | `ai-agent.gradle:70`; manager at `AiAgentGuardAutoConfiguration.java:93` (DYNAMIC mode) `[VERIFIED: codebase]` |
| `@EventListener` (Spring) | Spring 6 | Denylist `.clear()` on `LlmExposureChangedEvent` (D-05) | Precedent `AiExposureRuleEntityListener.java:31` `[VERIFIED: codebase]` |
| `net.ttddyy:datasource-proxy` | 1.11.0 | SELECT-count proxy harness (D-10/D-11) | `ai-agent.gradle:111` `[VERIFIED: codebase]` |
| Spring AI | 1.1.4 | Chat/streaming transport (threading model relevant to D-02/D-03) | `ai-agent/build.gradle:37` `springAiVersion=1.1.4` `[VERIFIED: codebase]` |

### Alternatives Considered (all OUT of scope by SPEC boundary — do NOT use)
| Instead of | Could Use | Why FORBIDDEN here |
|------------|-----------|---------------------|
| `ConcurrentHashMap` memo | Spring `@Cacheable`/`@CacheEvict` | D-07: `hiddenEntityNames()` is private + self-invoked; Spring 6 proxy caching intercepts only external calls ⇒ caches nothing on the hot paths. Extracting a proxied bean violates the "no new components" boundary. |
| `ConcurrentMapCacheManager` | Caffeine / Redis | SPEC Out-of-scope: "no new cache dependency". `[VERIFIED: codebase grep — only doc-comment mentions of Caffeine; no dependency]` |
| datasource-proxy SELECT-count + call-count | JMH / Gatling | SPEC Out-of-scope: "no benchmark harness". `[VERIFIED: codebase grep — no jmh/gatling dependency]` |
| Per-turn ThreadLocal (safe-miss) | Process-wide `ConcurrentHashMap<runId,…>` or reactor-Context propagation of the sensitive map | D-02: a skipped `finally` risks forbidden stale cross-user reuse; correctness > hit-rate. |

**Installation:** None. `./gradlew :ai-agent:test` already resolves everything.

**Version verification:** `springAiVersion=1.1.4` and `datasource-proxy:1.11.0` read directly from the build scripts `[VERIFIED: codebase]`. No registry lookup required — this phase installs nothing.

## Package Legitimacy Audit

> Not applicable — this phase installs **zero** external packages. SPEC Out-of-scope forbids any new dependency. All primitives are JDK / Spring / Spring AI already on the classpath. slopcheck/registry verification is moot.

| Package | Disposition |
|---------|-------------|
| (none added) | N/A — phase is memoization over existing classes only |

## Architecture Patterns

### System Architecture Diagram (per-turn data flow, memoization surface)

```
                    Chat turn begins
                          │
         AuditAdvisor.openEnvelope()  [AuditAdvisor.java:94]
            RunContext.set(runId) / setConversationId / setRootAuditId
            ── (NEW D-01) initialize per-turn cache slot ──
                          │
            ┌─────────────┴───────────────────────────────┐
            ▼                                               ▼
   blocking ask()                                  streaming stream()
   [DefaultChatServiceImpl ~294-350]               [Flux.defer .subscribeOn(scheduler) .contextCapture()]
            │                                       [DefaultChatServiceImpl:521,752,755]
            ▼                                               ▼
   BaselineContextProvider.compose()  ──► llmExposurePolicy.getReadableSchema()   [BCP.java:112]
                                          │   READ-THROUGH cache (PERF-01 per-turn)
   RetrievalFilterBuilder.buildFor()  ──► llmExposurePolicy.getDenylistedEntityNames() [RFB.java:99]
                                          │   reuses PERF-02 app-wide denylist cache (PERF-03)
   AiTaskFileMediaResolver.resolveActive() ──► FileStorage read / Tika parse / AiUiSettingsResolver.resolveTaskFile* [ATFMR.java:155]
                                          │   memo per (convId, taskFileId) per turn (PERF-04)
            │
            ▼ (LLM emits tool calls — multiple per turn)
   GuardedToolCallingManager.executeToolCalls()  [GTCM.java:96]
       reads RunContext.getRootAuditId()/get()/getConversationId() [GTCM.java:100,149,154]  ◄── D-03 EVIDENCE: ThreadLocal visible here
            │
            ▼ each tool: ToolEntityResolver / BuiltInDataTools / Mutation* 
              ──► canReadEntity / canCreate / canUpdate / getReadableSchema   ◄── PERF-01 cache hit (computed ONCE this turn)
            │
            ▼
   AuditAdvisor.closeEnvelope()  [AuditAdvisor.java:121]  /  stream .doFinally [DCSI.java:768]
            RunContext.clear()  ── (NEW D-01) wipes per-turn cache slot ──   ◄── no cross-turn/cross-user bleed
```

App-wide caches (denylist, entity-name→MetaClass) live as `LlmExposurePolicy` fields, evicted only by `LlmExposureChangedEvent`; they survive across turns by design (D-08: only an across-turn admin denylist edit changes the verdict).

### Component Responsibilities (read-through / eviction sites — line-accurate)

| Component (file) | Method (line) | Phase action |
|------------------|---------------|--------------|
| `orchestration/RunContext.java` | slots `:31-42`; `clear()` `:142-155` | D-01: add ONE `ThreadLocal<Map<CacheKey,Object>>` slot; wipe it in `clear()` alongside the existing 12 removes. |
| `audit/AuditAdvisor.java` | `openEnvelope` `:94-106`; `closeEnvelope` `:121` (`RunContext.clear()`) | Cache lifecycle anchor — set at start, clear in finally. NO edit needed if the slot self-initializes lazily; clear() already wipes everything if the new slot is added to `clear()`. |
| `exposure/LlmExposurePolicy.java` | `getReadableSchema` `:47`; `canReadEntity` `:62`; `canCreate` `:82`; `canUpdate` `:95`; `canModify→canUpdate` `:107`; `getDenylistedEntityNames` `:117`; **private** `hiddenEntityNames()` `:121` | PERF-01 read-through (per-turn) + PERF-02 app-wide denylist memo + `@EventListener(LlmExposureChangedEvent)` `.clear()`. This is the cache home (D-05/D-07). |
| `exposure/LlmExposureRuleRepository.java` | `findEnabledExcludedEntityNames()` `:34` | The single agentstore SELECT that PERF-02 memoizes; spied for the "one fetch reused" call-count test. |
| `orchestration/BaselineContextProvider.java` | `compose` → `getReadableSchema()` `:112` | PERF-01 read site; P-8/D-04 cache-key invariant lives here (`agent.permissions` locale-free `:111,242`; `agent.entities` locale-bearing `:225`). |
| `rag/RetrievalFilterBuilder.java` | `buildFor` `:77`; `getDenylistedEntityNames()` `:99` | PERF-03: build once per retrieval; reuse PERF-02 denylist; preserve NIN clause verbatim `:99-103`. |
| `taskfile/AiTaskFileMediaResolver.java` | `resolveActive` `:155`; `readFileBytes` `:357`; `extractDocumentText`/Tika `:320-351`; `resolveTaskFile*` calls `:162,179,180` | PERF-04: memo encode per `(convId, taskFileId)` per turn; already once-per-turn at call sites (regression-lock per D-10). |
| `orchestration/AiUiSettingsResolver.java` | `loadSingleton` `:78`; `resolveTaskFile*` `:97,106,115,128` | PERF-04: per-turn memo of the singleton read; evict on `AiSettingsChangedEvent(UI_SETTINGS)`. |
| `DefaultChatServiceImpl.java` | blocking `:294,319,350`; streaming `:521,574,589,617,752,755,768` | The call sites that drive `resolveActive`/`buildFor`/`getReadableSchema`; the streaming `Flux.defer.subscribeOn.contextCapture` + `doFinally RunContext.clear()` is the D-02/D-03 correctness backbone. |

### Pattern 1: App-wide memo mirroring `RelatedWriteMetadataResolver` (D-05/D-06)
**What:** A `private final Map<…> cache = new ConcurrentHashMap<>();` field + `computeIfAbsent`, with `.clear()` on the eviction event (denylist) or no eviction (metadata).
**When to use:** PERF-02 denylist (with `@EventListener` clear) and entity-name→MetaClass (no eviction).
**Example (verified precedent):**
```java
// Source: RelatedWriteMetadataResolver.java:136,160-167
private final Map<Key, Result> cache = new ConcurrentHashMap<>();
Result result = cache.computeIfAbsent(key, k -> { ... compute ... });
```
For PERF-02 the key is a single sentinel (one denylist set app-wide); D-05 allows an `AtomicReference` snapshot as an equivalent single-value form, but `ConcurrentHashMap` wins on precedent symmetry.

### Pattern 2: Single-publish-site eviction event (already wired)
**What:** A Jmix `EntityChangedEvent` listener republishes one Spring `ApplicationEvent`; caches consume via `@EventListener`, never re-publish.
**Verified:** `LlmExposureChangedEvent` published only at `AiExposureRuleEntityListener.java:33` (Javadoc: "No current consumer in v1.1 — wired for Phase 12+ caching consumers" `LlmExposureChangedEvent.java:7`). `AiSettingsChangedEvent` published only at `AiParametersEntityListener.java:65` and `AiUiSettingsEntityListener.java:44`; a source-scan invariant (`AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan()` `:229`) fails the build on drift.

### Pattern 3: Per-turn ThreadLocal anchored to turn lifecycle (D-01)
**What:** A static `ThreadLocal` slot in `RunContext`, set implicitly at turn start, read-through from policy/provider, wiped in `clear()`.
**Verified precedent:** `IterationCounter.java` (ThreadLocal counter, `start()`/`reset()` lifecycle mirroring `RunContext`) and the existing 12 `RunContext` slots.

### Anti-Patterns to Avoid
- **Spring `@Cacheable` on `hiddenEntityNames()` or the public gates** — D-07; self-invocation means the proxy never intercepts. The lowered ceiling would be unachievable while the cache *looks* wired.
- **`UnconstrainedDataManager` / raw JPQL on any data path** — STATE.md invariant; FK batch loads stay constrained `DataManager.load(...).ids(...)`. (Note: `LlmExposureRuleRepository` and `AiUiSettingsResolver` *legitimately* use `UnconstrainedDataManager` for system-internal governance reads — that is pre-existing and correct, not a data path the LLM can steer.)
- **Cross-turn/cross-user caching of schema/verdicts** — strictly forbidden; the ThreadLocal safe-miss is the guard.
- **Process-wide `runId`-keyed map for the per-turn cache** — D-02 forbids (skipped finally ⇒ stale reuse risk).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| App-wide memo with eviction | A bespoke synchronized cache | `ConcurrentHashMap`+`computeIfAbsent` + `@EventListener.clear()` (RelatedWriteMetadataResolver shape) | Proven, greppable, source-scan testable; thread-safe without locks. |
| Per-turn anchor | A new request-scoped Spring bean or reactor Context plumbing | New `RunContext` ThreadLocal slot | D-01/D-02; matches existing lifecycle; safe-miss correctness for free. |
| SELECT counting | Hibernate `Statistics` | `datasource-proxy` `QueryCountHolder` | EclipseLink has no Statistics API (`QueryCountingDataSourceConfiguration` Javadoc). |
| Eviction event | A new publisher | Existing `LlmExposureChangedEvent` / `AiSettingsChangedEvent` | Single-publish-site invariant already enforced; this phase is their first consumer. |

**Key insight:** Phase 17 already shipped the exact memo idiom and Phase 16 shipped the exact eviction events — this phase is wiring, not invention. The single biggest risk is using `@Cacheable` (looks right, silently does nothing on 4/5 hot paths).

## Common Pitfalls

### Pitfall 1: `@Cacheable` silently caches nothing (D-07)
**What goes wrong:** Annotate `hiddenEntityNames()` or `getDenylistedEntityNames()` with `@Cacheable`; the cache appears wired but the per-call SELECT persists.
**Why:** `hiddenEntityNames()` is `private` (`LlmExposurePolicy.java:121`) and self-invoked from `getReadableSchema`(`:48`), `canReadEntity`(`:64`), `canCreate`(`:86`), `canUpdate`(`:99`), `getDenylistedEntityNames`(`:118`); `canModify`→`canUpdate`(`:108`) adds a 6th self-call. Spring 6 proxy caching intercepts only **external** calls.
**How to avoid:** Use the `ConcurrentHashMap`+`computeIfAbsent` field inside the bean (D-05). Never `@Cacheable`.
**Warning sign:** The lowered `ToolQueryCountBaselineTest` ceiling fails while the cache "looks" present.

### Pitfall 2: Under-scoping the per-turn read-through surface
**What goes wrong:** Plan PERF-01 against only the 5 self-invocations and miss the 10+ external callers, leaving most tool calls un-memoized.
**Why:** `canReadEntity/canCreate/canUpdate/getReadableSchema` are called from BuiltInDataTools(`:100,160,371`), ToolEntityResolver(`:83,101,117,154`), FetchPlanIntersector(`:119`), StructuredFilterConditionMapper(`:245`), ExtractionService(`:194`), IntentRegistry(`:70-71`), MetaClassDtoSynthesizer(`:92,156`), DraftLoader(`:161`), MutationAuthorizationService(`:159-160`), MutationCommitCoordinator(`:166`).
**How to avoid:** Because the cache lives **inside `LlmExposurePolicy`** (D-05, no proxy boundary), all external callers transparently benefit — but the PERF-01 call-count proxy must drive a multi-tool turn and verify the underlying `hiddenEntityNames`/schema delegate fires **once**, not once-per-caller.
**Warning sign:** Call-count > 1 in a multi-tool turn.

### Pitfall 3: Streaming-thread ThreadLocal absence treated as a correctness bug
**What goes wrong:** Assuming the per-turn cache must always hit, then "fixing" a miss with cross-thread propagation of the sensitive map.
**Why:** The whole turn runs inside `Flux.defer{...}.subscribeOn(chatStreamingScheduler).contextCapture()` (`DefaultChatServiceImpl.java:521,752,755`) with NO mid-pipeline `publishOn`; tool execution reads `RunContext` on the same subscription thread (`GuardedToolCallingManager.java:100,149,154`), and `RunContext.clear()` fires in `.doFinally` (`:768`). So the common case hits. A foreign worker thread returning `null` from `ThreadLocal.get()` is the **safe miss** (recompute), never stale reuse — D-02.
**How to avoid:** Foreground D-02/D-03 in the PERF-01 plan; treat miss→recompute as correct-by-design. Do NOT add reactor-Context/Micrometer propagation of the sensitive map.
**Warning sign:** A plan task proposing to propagate the cache across scheduler hops.

### Pitfall 4: Editing `ToolQueryCountBaselineTest` security assertions
**What goes wrong:** While lowering the ceiling, the security/opacity assertions get touched.
**Why:** D-10 + Acceptance: only the constant `METAMODEL_TOOL_POLICY_LOOKUP_CEILING` (**line 151**, currently `5L`) moves; everything else byte-for-byte unchanged. Note the **two** assertion sites that compare against it: `listEntities_metamodelPlusPolicyLookupOnly` (`:164`) and `describeEntity_metamodelPlusPolicyLookupOnly` (`:180`).
**How to avoid:** Change only `5L → <new>` at line 151. The slope test (`:267`) and the four `…_STEADY_STATE_CEILING = 1000L` constants (`:192-195`) are unrelated and must not move.
**Warning sign:** Any diff in `ToolQueryCountBaselineTest` outside line 151.

### Pitfall 5: Adding a redundant cache where the hotspot is already once-per-turn (PERF-04)
**What goes wrong:** Adding a `Media` cache when `resolveActive` is already called once per turn.
**Why:** SPEC §Background + STATE.md [Phase 13.1 Plan 03]: `resolveActive(convId)` is already invoked **once per turn** on both transports (`DefaultChatServiceImpl.java:350,617`). PERF-04 is partly a re-encode guard, not a new cache.
**How to avoid:** Per D-10/locked scope discipline — run the proxy first; where already-once, lock with a regression assertion instead of adding a cache; only add the per-`(convId,taskFileId)` memo if the proxy shows repeated encodes within a turn.
**Warning sign:** A new cache whose proxy shows it never serves a second hit in a turn.

## Runtime State Inventory

> This is a memoization pass touching only in-JVM caches — there is no rename, no stored-data migration, no OS-registered state. Inventory included for completeness.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — caches are in-JVM only; no schema, no new column, no datastore key. The agentstore `AiExposureRule`/`AiUiSettings`/`AiParameters` rows are *read*, never re-keyed. | None — verified: no Liquibase changelog in scope. |
| Live service config | None — no external service config carries cache state. | None. |
| OS-registered state | None. | None. |
| Secrets/env vars | None — no new property. (Existing `jmix.ai-agent.tools.max-filter-depth` etc. untouched.) | None. |
| Build artifacts | None — no package rename; `./gradlew :ai-agent:test` unaffected. | None. |

**Nothing found in any category** — verified by grep: no Liquibase changelog, no new entity, no new property in scope.

## Code Examples

### App-wide denylist memo with event eviction (D-05) — target shape
```java
// Home: LlmExposurePolicy.java  (mirror RelatedWriteMetadataResolver.java:136,160)
private static final String DENYLIST_KEY = "denylist";          // single sentinel
private final Map<String, Set<String>> denylistCache = new ConcurrentHashMap<>();

private Set<String> hiddenEntityNames() {
    return denylistCache.computeIfAbsent(DENYLIST_KEY, k -> {
        Set<String> hidden = new LinkedHashSet<>(AiInternalEntityNames.all());
        hidden.addAll(ruleRepository.findEnabledExcludedEntityNames());   // the memoized SELECT
        return hidden;
    });
}

@EventListener(LlmExposureChangedEvent.class)                   // mirror AiExposureRuleEntityListener pattern
void onExposureChanged(LlmExposureChangedEvent e) { denylistCache.clear(); }
```
Note: `AiInternalEntityNames.all()` (`AiInternalEntityNames.java:30`) is itself static-immutable, so it can fold into the cached value safely.

### Per-turn cache slot (D-01) — target shape
```java
// RunContext.java — add alongside the existing 12 ThreadLocals (lines 31-42)
private static final ThreadLocal<Map<Object, Object>> PER_TURN_CACHE = new ThreadLocal<>();

public static Map<Object, Object> perTurnCache() {           // lazy-init; safe-miss when null on foreign thread
    Map<Object, Object> m = PER_TURN_CACHE.get();
    if (m == null) { m = new HashMap<>(); PER_TURN_CACHE.set(m); }
    return m;
}
// in clear() (line 142): PER_TURN_CACHE.remove();   ◄── the non-negotiable wipe
```
Cache key per D-04: `agent.permissions`/CRUD verdicts are locale-invariant (key = `(userId, roleSet, metaclass-name)`); `agent.entities` must include locale. The planner may refine `CacheKey` shape (D-Discretion) so long as D-01..D-11 hold.

### PERF-02 SELECT-count proxy — the ONLY edit (D-10)
```java
// ToolQueryCountBaselineTest.java:151  — change 5L to the new (lower) ceiling; nothing else moves
private static final long METAMODEL_TOOL_POLICY_LOOKUP_CEILING = <new lower value>;
// asserted at :164 (listEntities) and :180 (describeEntity); security/opacity assertions unchanged
```

### PERF-01 / PERF-03 / PERF-04 call-count test template (D-10)
The canonical pure-JUnit + Mockito call-count style (sidesteps the boot regression) is `RelatedWriteMetadataMemoTest.java` (counting subclass over a package-private seam) and `AiUiSettingsResolverReadThroughTest.java` (real unit + mocked `UnconstrainedDataManager`/property beans). For PERF-03, spy/`verify(times(1))` on `RetrievalFilterBuilder.buildFor` per retrieval and assert `RetrievalFilterBuilderDenylistTest` (4 tests) still passes (verbatim NIN clause `:99-103`).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Per-call denylist SELECT (no cache, D-14) | App-wide `ConcurrentHashMap` memo evicted on `LlmExposureChangedEvent` | This phase (PERF-02) | Lowers `ToolQueryCountBaselineTest` ceiling from 5; first consumer of the dormant event. |
| `getReadableSchema()` rebuilt per tool-call | Per-turn `RunContext` memo | This phase (PERF-01) | One resolution per turn across all tool calls. |
| RAG filter rebuilt per retrieval | Built once per retrieval reusing cached denylist | This phase (PERF-03) | Clauses preserved verbatim. |

**Deprecated/outdated:** `@Cacheable` was never adopted in functional code (`SPEC §Background`: "no `@Cacheable` exists in functional code") — D-07 confirms it would be wrong here.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring AI 1.1.4 default blocking-tool execution runs synchronously on the subscription thread with no mid-flight `publishOn` (D-03). Grounded by the **codebase** wiring (`Flux.defer.subscribeOn.contextCapture`, no `publishOn`, tool read-sites + `clear()` on the same terminal) and by the audit/guard suites passing under streaming. Not separately re-confirmed against Spring AI source/docs this session. | Pitfall 3 / D-03 | If a future Spring AI version inserts a `publishOn` inside `ToolCallingManager`, the per-turn cache degrades to miss→recompute (still **correct** by D-02) — never stale. So the risk is a missed optimization, never a correctness bug. LOW. |

**All other claims are `[VERIFIED: codebase]` against the cited file:line.** The Spring AI threading assumption is the only `[ASSUMED]` item, and D-02's safe-miss design makes it non-load-bearing for correctness.

## Open Questions (RESOLVED)

1. **Exact `CacheKey` type for the per-turn slot.**
   - What we know: D-04 fixes the key *semantics* (`agent.permissions`/verdicts locale-invariant; `agent.entities` locale-bearing). The user delegated the concrete shape to researcher/planner.
   - What's unclear: whether to use a sealed-interface key, a record per cached kind, or a string-prefixed `Object` key.
   - Recommendation: a small `record` per cached kind (e.g. `ReadableSchemaKey(userId, Set<role>)`, `CrudVerdictKey(metaClassName, op)`) keyed in one `Map<Object,Object>`; keep locale out of the verdict keys, in the entities key. Planner's discretion (D-Discretion).
   - **RESOLVED (Plan 18-02):** `record CrudVerdictKey(String metaClassName, String operation)` keyed without locale, plus a `ReadableSchemaKey` marker, in one per-turn `Map<Object,Object>` — matches the recommendation.

2. **Does PERF-04 need a cache at all, or only a regression lock?**
   - What we know: `resolveActive` is already once-per-turn (`:350,617`); but *within* `resolveActive` each kept row reads `FileStorage`/Tika exactly once already (`:233-240`).
   - What's unclear: whether any in-turn path re-invokes encode for the same `(convId,taskFileId)`.
   - Recommendation: run the call-count proxy first (D-10 scope discipline). If no second encode occurs in a turn, ship the regression assertion and skip the cache; keep `PerTurnMediaInjectionTest` unchanged.
   - **RESOLVED (Plan 18-04):** built as the proxy-first branch — run the encode call-count proxy, then ship a regression-lock if already once-per-turn, else a memo evicted on `AiSettingsChangedEvent(UI_SETTINGS)` + attachment add/delete/TTL.

## Environment Availability

> The phase has no external runtime dependencies (no DB tool, no service, no CLI). It is code + test changes against an existing build. Audit skipped per the documented skip condition: **SKIPPED (no external dependencies identified)**. Build/test entry point `./gradlew :ai-agent:test` is the only tool, already in use.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + AssertJ + Mockito (via `spring-boot-starter-test`); `junit-vintage` excluded `[VERIFIED: TESTING.md]` |
| Config file | `ai-agent/ai-agent/ai-agent.gradle` (module build script) |
| Quick run command | `./gradlew :ai-agent:test --tests "com.vn.agent.<Class>"` |
| Full suite command | `./gradlew :ai-agent:test` (excludes `live`,`rag-it`,`eval`) |
| Perf-proxy harness | `net.ttddyy:datasource-proxy:1.11.0` via `QueryCountingDataSourceConfiguration` (wraps `agentstoreDataSource` in-place) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command (template) | File Exists? |
|--------|----------|-----------|------------------------------|-------------|
| PERF-01 | schema/exposure resolved once per turn; cache empty after `clear()` | call-count (Mockito) + ThreadLocal-empty assert | `--tests "*PerTurn*Memo*"` (new) | ❌ Wave 0 (template: `RelatedWriteMetadataMemoTest`) |
| PERF-02 | denylist fetched once, reused until event, refetch once; lowered SELECT ceiling; every exposure cache subscribes to event | SELECT-count (edit ceiling :151) + call-count spy + reflection invariant | `--tests "ToolQueryCountBaselineTest"` + new | ✅ (ceiling) / ❌ (call-count + invariant) Wave 0 |
| PERF-03 | filter built once per retrieval; clauses verbatim | call-count `times(1)` + existing denylist test | `--tests "RetrievalFilterBuilderDenylistTest"` + new | ✅ (denylist) / ❌ (call-count) Wave 0 |
| PERF-04 | `Media` encode ≤ once per `(convId,taskFileId)` per turn | call-count on FileStorage/Tika/resolveTaskFile* | `--tests "*TaskFileMedia*"` + existing `PerTurnMediaInjectionTest` | ✅ (PerTurn) / ❌ (encode call-count) Wave 0 |
| PERF-05 | no JMH/Gatling/Caffeine; settings edit visible next turn; suites unchanged | build-dep scan + call-count integration + full suite | `./gradlew :ai-agent:test` | ❌ Wave 0 (dep-scan invariant; settings-eviction test) |

### Sampling Rate
- **Per task commit:** the single most-relevant `--tests "<Class>"` (each runs <30s under the pure-JUnit/Mockito style).
- **Per wave merge:** `./gradlew :ai-agent:test` (full default suite).
- **Phase gate:** full default suite green, with the ONLY allowed test-body edit being `ToolQueryCountBaselineTest.java:151`.

### Wave 0 Gaps
- [ ] PERF-01 call-count + `assertThat(cache).isEmpty()`-after-`clear()` test (template: `RelatedWriteMetadataMemoTest`).
- [ ] PERF-02 "one fetch reused until `LlmExposureChangedEvent`, then exactly one refetch" call-count test (spy `LlmExposureRuleRepository.findEnabledExcludedEntityNames`).
- [ ] PERF-02 reflection/source-scan invariant "every exposure-derived cache subscribes to `LlmExposureChangedEvent`" (template: `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan`).
- [ ] PERF-03 `times(1)` per-retrieval call-count on `RetrievalFilterBuilder.buildFor`.
- [ ] PERF-04 encode call-count test (FileStorage read + Tika + `resolveTaskFile*`) — or regression lock if proxy shows already-once.
- [ ] PERF-05 build-dependency invariant (no jmh/gatling/caffeine) + `AiSettingsChangedEvent`-visible-next-turn test.
- [ ] D-09 boundary invariant: per-turn cache symbol appears ONLY in `LlmExposurePolicy` + `RunContext`, never in `BuiltInDataTools` (constrained-DataManager row path).
- Framework install: none — JUnit5/Mockito/AssertJ/datasource-proxy all present.

**Boot-regression note (D-11, verified):** the `@SpringBootTest` SELECT-count harness (`ToolQueryCountBaselineTest`, `MutationFkBatchLoadQueryCountTest`) **boots and passes today** in isolation; the documented boot regression is a full-suite forked-context-pressure/ordering phenomenon (`forkEvery=20`, `cache.maxSize=8`), not a recipe-level failure. SELECT-count is therefore legitimate for PERF-02; call-count (pure-JUnit/Mockito) covers the ThreadLocal/CPU-only reuse the SELECT count can't see and sidesteps the regression for PERF-01/03/04.

## Security Domain

> `security_enforcement` posture: this phase changes NO security/exposure/opacity semantics (SPEC Out-of-scope). The relevant ASVS surface is "don't regress the existing controls."

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control (must stay intact) |
|---------------|---------|-------------------------------------|
| V4 Access Control | yes | `AccessManager`/`DataManager` remain authoritative for row-level data; the cache memoizes only the LLM-facing schema/verdict surface (D-08/D-09). |
| V5 Input Validation | no (unchanged) | No new input surface; tool-arg binding untouched. |
| V8 Data Protection | yes | Per-turn cache holds user/role-sensitive verdicts ⇒ ThreadLocal scoped to one turn, wiped in `clear()`; never cross-user (D-02/D-09). |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation (this phase) |
|---------|--------|----------------------------------|
| Stale cross-user verdict reuse | Elevation of Privilege / Information Disclosure | ThreadLocal safe-miss + `clear()` in finally; D-09 boundary test asserts no cross-turn bleed (`assertThat(cache).isEmpty()` after `clear()`). |
| Cache leaks into row-data path | Information Disclosure | D-09 source-scan: per-turn cache symbol only in `LlmExposurePolicy` + `RunContext`, never in `BuiltInDataTools`/constrained-DataManager path. |
| Over-permissive memoized verdict mid-turn | Elevation of Privilege | D-08: within a turn user/role/constraints are immutable; only across-turn `LlmExposureChangedEvent` changes verdicts (app-wide cache evicts on it). |
| Denylist staleness after admin edit | Tampering | `@EventListener(LlmExposureChangedEvent)` `.clear()` (D-05); settings caches evict on `AiSettingsChangedEvent` (PERF-05 acceptance: visible next turn). |

## Project Constraints (from CLAUDE.md + STATE.md + memory)

- **DataManager not EntityManager**; constrained `DataManager` on LLM-steerable data paths; FK batch loads stay `DataManager.load(...).ids(...)` — never `UnconstrainedDataManager`/raw JPQL. (Pre-existing `UnconstrainedDataManager` use in `LlmExposureRuleRepository`/`AiUiSettingsResolver` is system-internal governance read — correct, not in scope to change.)
- **No new cache dependency** beyond `ConcurrentHashMap`/`ConcurrentMapCacheManager` (no Caffeine). No JMH/Gatling.
- **No `@Transactional` changes; no change to gating order, audit rows, or opacity behavior.**
- **Constructor injection in services** (no field injection).
- **Pure-JUnit source-scan/reflection invariants replace ArchUnit** (memory `feedback_no_archunit`); use for D-09 boundary + "subscribes to event" invariant.
- **Reuse Jmix built-ins** (memory `feedback_reuse_jmix_builtins`): `AccessManager`/`Metadata` stay authoritative; cache only the thin LLM-facing schema/verdict surface.
- **No abbreviations** in new identifiers (memory `feedback_no_abbreviations`).
- **Touch only already-shipped classes in `com.vn.agent`** — no new components, no new public API (SPEC boundary).
- **Edits in `frontend/generated/` forbidden** (not relevant here; no UI work).

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PERF-01 | Per-turn schema/metadata/access memoization, shared across tool calls; empty after `clear()` | `RunContext` slot (D-01) verified `:31-42,142`; read-through at `LlmExposurePolicy` (`:47-117`) + `BaselineContextProvider:112`; full caller surface enumerated (Pitfall 2); streaming visibility proven (`DefaultChatServiceImpl:752-768`, `GuardedToolCallingManager:100,149`). |
| PERF-02 | App-wide denylist + entity-name→MetaClass memo, event-evicted; lower SELECT ceiling | `RelatedWriteMetadataResolver` idiom (`:136,160`); `LlmExposureRuleRepository.findEnabledExcludedEntityNames:34`; `LlmExposureChangedEvent` single publish site (`AiExposureRuleEntityListener:33`); ceiling constant `ToolQueryCountBaselineTest:151` confirmed `5L`. |
| PERF-03 | RAG `Filter.Expression` once per retrieval; clauses verbatim | `RetrievalFilterBuilder.buildFor:77`, denylist reuse `:99`, NIN clause `:101-103`; must-pass `RetrievalFilterBuilderDenylistTest` (4 tests) read and confirmed. |
| PERF-04 | Task-file `Media` memo per `(convId,taskFileId)` per turn; no in-turn re-serialize | `AiTaskFileMediaResolver.resolveActive:155` (already once/turn per `DefaultChatServiceImpl:350,617`), encode sites `:298-351`; `AiUiSettingsResolver.resolveTaskFile*:97-134`; `AiSettingsChangedEvent(UI_SETTINGS)` evictor confirmed. |
| PERF-05 | Proxies; no benchmark/admin-perf; suites unchanged; settings edit visible next turn | No jmh/gatling/caffeine dep (grep verified); `AiSettingsChangedEvent` two publish sites confirmed; harness boots today (D-11); only allowed test edit is line 151. |

## Sources

### Primary (HIGH confidence — live code, file:line cited inline)
- `orchestration/RunContext.java`, `audit/AuditAdvisor.java`, `exposure/LlmExposurePolicy.java`, `exposure/LlmExposureRuleRepository.java`, `exposure/LlmExposureChangedEvent.java`, `exposure/AiExposureRuleEntityListener.java`, `exposure/AiInternalEntityNames.java`
- `orchestration/BaselineContextProvider.java`, `orchestration/AiUiSettingsResolver.java`, `rag/RetrievalFilterBuilder.java`, `taskfile/AiTaskFileMediaResolver.java`, `guard/GuardedToolCallingManager.java`, `guard/IterationCounter.java`, `DefaultChatServiceImpl.java`
- `admin/config/AiSettingsChangedEvent.java`, `admin/config/AiParametersEntityListener.java`, `admin/config/AiUiSettingsEntityListener.java`
- `tools/mutation/RelatedWriteMetadataResolver.java`
- Tests: `performance/ToolQueryCountBaselineTest.java`, `performance/QueryCountingDataSourceConfiguration.java`, `performance/MutationFkBatchLoadQueryCountTest.java`, `admin/config/AiUiSettingsResolverReadThroughTest.java`, `admin/config/AiSettingsChangedEventListenerInvariantTest.java`, `rag/RetrievalFilterBuilderDenylistTest.java`, `tools/mutation/RelatedWriteMetadataMemoTest.java`
- Build: `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`
- Planning: `18-SPEC.md`, `18-CONTEXT.md`, `STATE.md`, `TESTING.md`, `CONCERNS.md`

### Secondary (MEDIUM)
- Grep audits: caller surface for canCreate/canUpdate/canReadEntity/getReadableSchema/getDenylistedEntityNames; absence of caffeine/jmh/gatling deps.

### Tertiary (LOW)
- None. (The one assumption — Spring AI 1.1.4 sync-tool-execution threading — is documented in the Assumptions Log; D-02's safe-miss makes it non-load-bearing, so no Context7 lookup was required.)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every primitive/dependency read from build scripts and source.
- Architecture / decisions: HIGH — all 11 decisions confirmed with file:line; one drift flagged (wider PERF-01 caller surface) that strengthens rather than contradicts the locked design.
- Pitfalls: HIGH — each grounded in cited code (D-07 self-invocation, streaming wiring, ceiling line 151).
- Streaming threading (D-03): MEDIUM-HIGH — proven via codebase wiring; the only un-re-confirmed library-internal detail is logged as A1 and is correctness-irrelevant by D-02.

**Research date:** 2026-06-09
**Valid until:** ~2026-07-09 (stable; the only volatility is a hypothetical Spring AI upgrade altering tool-execution threading — re-check A1 on any Spring AI bump).
