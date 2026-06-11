---
phase: 18-ai-runtime-performance-pass-targeted
reviewed: 2026-06-09T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/ExposureCacheEventSubscriptionInvariantTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposureDenylistMemoTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePerTurnMemoTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/PerTurnCacheBoundaryInvariantTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/SettingsEditVisibleNextTurnTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/NoNewPerfDependencyInvariantTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderBuildOncePerRetrievalTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TaskFileMediaEncodeOncePerTurnTest.java
findings:
  critical: 0
  warning: 4
  info: 5
  total: 9
status: issues_found
---

# Phase 18: Code Review Report

**Reviewed:** 2026-06-09T00:00:00Z
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Phase 18 is a targeted memoization pass adding (a) an app-wide denylist memo (`ConcurrentHashMap` single-sentinel-key, evicted on `LlmExposureChangedEvent`) and (b) a per-turn `ThreadLocal` verdict/schema cache (`RunContext.PER_TURN_CACHE`, wiped in `clear()`), on top of already-shipped exposure / RAG / task-file code.

The dominant risk class — cache-induced authorization bypass / stale-permission reuse — was examined closely and the core security invariants hold:

- The per-turn cache is **active-turn-gated**: off-turn / foreign-thread access is a true safe MISS (`Collections.emptyMap()`, no allocation, no store) and `perTurnMemoize` recomputes-without-storing when `CURRENT.get() == null`. A pooled streaming/Vaadin thread cannot serve a verdict from a previous turn because `clear()` calls `PER_TURN_CACHE.remove()` and is invoked in `doFinally`/`finally` on every terminal signal across both transports (`AuditAdvisor:121`, `DefaultChatServiceImpl:373,768`, `MutationToolCallbackBoundaryDecorator:250`).
- The denylist memo returns a `Collections.unmodifiableSet` view (and `getReadableSchema()` is deeply immutable via `Set.copyOf`), so a caller cannot poison the shared cache; eviction is wired via `@EventListener(LlmExposureChangedEvent.class)`.
- `RetrievalFilterBuilder` preserves the NIN/role/fail-closed clauses verbatim; role extraction stays request-fresh (derived from the supplied `Authentication` every call, never cached).
- No `UnconstrainedDataManager`/raw-JPQL was introduced on the cached surface beyond the pre-existing, convention-correct governance read in `LlmExposureRuleRepository`.

No BLOCKER-tier defects were found. The findings below are correctness-edge and test-rigor concerns (WARNING) plus minor quality notes (Info).

## Warnings

### WR-01: Denylist eviction has a benign-but-real repopulate-with-stale race

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java:222-238`
**Issue:** `onExposureChanged` calls `denylistCache.clear()` while `hiddenEntityNames()` uses `computeIfAbsent` on the same key. If an `LlmExposureChangedEvent` lands while another thread is mid-`computeIfAbsent` (mapping function still running, having already read the OLD rule rows from `findEnabledExcludedEntityNames()`), the `clear()` removes nothing yet-to-be-inserted, and the in-flight compute then stores the OLD denylist AFTER the clear — re-arming the cache with a stale value. The next turn would still serve the pre-edit denylist until the following event/turn. The window is sub-millisecond and self-heals (the entity save publishes the event synchronously post-commit and every later turn re-reads), so this is not a BLOCKER, but the "admin denylist edit is visible on the next turn (T-18-01)" guarantee is only *probabilistic*, not absolute, under concurrency. None of the proxy tests exercise concurrent eviction-vs-compute, so the guarantee is unproven for the racing case.
**Fix:** Either accept and document the window explicitly (it is narrow and self-healing), or close it with a generation/version stamp so a compute that started before an eviction cannot publish its result:
```java
private final AtomicLong generation = new AtomicLong();
// in onExposureChanged: generation.incrementAndGet(); denylistCache.clear();
// in hiddenEntityNames(): capture gen before fetch; after fetch, only store if
// generation is unchanged (else return the freshly-computed set without caching).
```

### WR-02: Mid-turn `LlmExposureChangedEvent` is invisible until next turn for read/CRUD verdicts (consistency gap vs. denylist)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java:112-185`
**Issue:** `canReadEntity`, `canCreate`, `canUpdate`, and `getReadableSchema` are memoized in the per-turn cache, which is wiped only by `RunContext.clear()`. An `LlmExposureChangedEvent` firing mid-turn evicts `denylistCache` but does NOT evict the per-turn verdict/schema entries. So within the same turn a newly-EXCLUDE-ed entity that was already gated once stays at its old verdict (the `getReadableSchema` snapshot and any cached `canReadEntity(mc)` keep the pre-edit answer). This is documented as accepted (T-18-07, "within a turn verdicts are immutable") and the direction is fail-toward-the-old-value, not over-permissive in the dangerous direction for a newly-*added* EXCLUDE (the entity becomes MORE hidden, but the cached verdict still shows it). For a rule *removal* (re-including an entity), the cached verdict keeps it hidden mid-turn — harmless. Net: not a security bypass, but the class Javadoc's "visible on the next turn" claim is true only for the across-turn boundary, and a reviewer/operator could reasonably expect the mid-turn edit to take hold. Flagging for explicit acknowledgement.
**Fix:** No code change required if T-18-07 is genuinely accepted; tighten the Javadoc on `canReadEntity`/`getReadableSchema` to state that an exposure edit landing mid-turn is reflected only on the NEXT turn (the per-turn cache is intentionally not event-evicted), so the across-turn vs. within-turn distinction is unambiguous.

### WR-03: `SettingsEditVisibleNextTurnTest` mutates the stub mid-cache to "prove" staleness — assertion is partly self-fulfilling

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/SettingsEditVisibleNextTurnTest.java:64-99`
**Issue:** The "before eviction the memo MUST still serve the OLD denylist" assertion is constructed by re-stubbing `findEnabledExcludedEntityNames()` from setA to setB and asserting the policy still returns setA. That genuinely proves a cache exists — but it does so against a Mockito stub whose return value the test fully controls; it cannot distinguish "the memo served the cached setA" from "the second `when(...)` did not take effect for some unrelated reason." The `verify(..., times(1))` after the stillCached read is what actually carries the proof (one fetch ⇒ second call was served from cache). The value-equality assertion (`stillCached contains sample_Customer, doesNotContain sample_Order`) is the load-bearing one and is sound only because it is paired with the `times(1)` verify. Without the verify it would be self-fulfilling. The proof holds, but the design leans on the reader noticing the verify; an accidental future deletion of line 87's `verify(..., times(1))` would silently downgrade this to a tautology.
**Fix:** Add a comment pinning that line 87's `verify(times(1))` is the non-tautological half of the assertion, or assert the fetch count is the primary signal (assert `times(1)` BEFORE the value assertion) so the proof does not depend on stub-swap timing.

### WR-04: `ToolQueryCountBaselineTest` never establishes an active turn, so it does not exercise (or guard) the PERF-01 per-turn memo it documents

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java:153-183`
**Issue:** The class header and the `list_entities`/`describe_entity` comments tie these baselines to `LlmExposurePolicy` routing, and the Phase 18 surface adds per-turn memoization of `canReadEntity`/`getReadableSchema`. But none of these test bodies call `RunContext.set(...)`, so `RunContext.get()` is null throughout and every `perTurnMemoize` call takes the off-turn safe-miss branch (recompute, no store). The SELECT-count ceilings (`METAMODEL_TOOL_POLICY_LOOKUP_CEILING = 4`) are therefore measuring the UN-memoized path. This is not wrong as a regression ceiling, but it means the per-turn memo provides ZERO query-count benefit in this test and the test gives no coverage that the memo actually collapses N tool calls to one resolve. The genuine per-turn coverage lives only in `LlmExposurePerTurnMemoTest` (pure Mockito). A reader could mistake this `@SpringBootTest` suite for the in-context proof of PERF-01's query reduction; it is not.
**Fix:** Either wrap the measured invocations in `RunContext.set(UUID.randomUUID())` / `RunContext.clear()` and assert the second same-turn call issues zero additional denylist SELECTs (an end-to-end PERF-01 proof), or add a comment clarifying that this suite measures the off-turn ceiling and the per-turn collapse is proven only by the Mockito proxy.

## Info

### IN-01: `getReadableSchema()` recomputes the denylist on every active-turn miss within `computeReadableSchema`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java:116-130`
**Issue:** `computeReadableSchema()` calls `hiddenEntityNames()` (one denylist memo read) and `canReadEntity` separately reads `hiddenEntityNames()` again. Both hit the app-wide `denylistCache` so it is a `ConcurrentHashMap` lookup, not a DB round-trip — negligible. Noting only that the schema memo and the per-entity read memo each independently re-consult the denylist; there is no shared per-turn denylist snapshot, but the app-wide memo makes that immaterial.
**Fix:** None required.

### IN-02: `RetrievalFilterBuilderBuildOncePerRetrievalTest` proves call-count against a mock, not the real memo

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderBuildOncePerRetrievalTest.java:68-94`
**Issue:** `denylistPolicy()` mocks `LlmExposurePolicy`, so `buildForIssuesExactlyOneDenylistLookupPerRetrieval` proves only that `buildFor` calls `getDenylistedEntityNames()` once per invocation — it does NOT prove that call is backed by the PERF-02 app-wide memo (that is asserted by comment and covered separately in `LlmExposureDenylistMemoTest`). The class is honest about this ("not a cross-retrieval cache claim"). No defect; recorded so the proxy's scope is not over-read.
**Fix:** None required.

### IN-03: Source-scan invariant tests assert structure, not behavior

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/ExposureCacheEventSubscriptionInvariantTest.java:30-58`, `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/PerTurnCacheBoundaryInvariantTest.java:48-89`, `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/NoNewPerfDependencyInvariantTest.java:80-130`
**Issue:** These are regex/file-presence source scans (e.g., "a `new ConcurrentHashMap<` exists AND an `@EventListener(LlmExposureChangedEvent` exists in the same file"). They guard structure but cannot prove the eviction is *correct* (e.g., that the listener actually clears the right map, or that the regex would catch a cache declared via a typedef/factory rather than `new ConcurrentHashMap<`). They are reasonable guardrails but are not substitutes for the behavioral proxies; the behavioral coverage is carried by `LlmExposureDenylistMemoTest` / `LlmExposurePerTurnMemoTest`, which are present.
**Fix:** None required; the pairing of structural + behavioral tests is adequate.

### IN-04: `extractDocumentText` truncates by `char` count via `substring`, risking a split surrogate pair

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java:352-355`
**Issue:** `text.substring(0, MAX_DOC_TEXT_CHARS)` can slice through a UTF-16 surrogate pair if the 50,000th char is a high surrogate, yielding one dangling unpaired surrogate at the boundary. Cosmetic (the LLM tokenizer tolerates it) and pre-existing (verbatim port, not introduced in Phase 18), but noting since this file is in scope.
**Fix:** Round the cut to a code-point boundary, e.g. `if (Character.isHighSurrogate(text.charAt(MAX_DOC_TEXT_CHARS - 1))) end--;` before `substring`. Low priority.

### IN-05: `buildBudgetArgumentsJson` allocates a fresh `ObjectMapper` per overflow

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java:275`
**Issue:** `new ObjectMapper()` is constructed inside the budget-exceeded audit path. `ObjectMapper` is thread-safe and intended to be reused; per-call construction is wasteful. Out-of-scope for v1 performance but a minor quality smell, and pre-existing (this is the overflow path, not the hot encode path).
**Fix:** Hoist a `private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();` (or inject the Spring-managed bean) and reuse it. Low priority.

---

_Reviewed: 2026-06-09T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
