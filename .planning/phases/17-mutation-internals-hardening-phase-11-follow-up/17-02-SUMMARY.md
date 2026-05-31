---
phase: 17-mutation-internals-hardening-phase-11-follow-up
plan: 02
subsystem: mutation-tools-related-write
tags: [mutation, memoization, concurrenthashmap, metamodel-walk, mut-17, parity]
requires:
  - "Phase 11 mutation tool surface (RelatedWriteMetadataResolver + add/remove_related_record chain)"
  - "Plan 17-01 package-private computeSupported(MetaClass, String) seam + RelatedWriteMetadataMemoTest CountingResolver"
provides:
  - "MUT-17 walk-once memoization: (parentEntityName, relationshipName) -> Result cache over the immutable Jmix metamodel (no eviction)"
  - "Result holder (rejection marker, never a Throwable) + fresh-rethrow of unsupportedRelationship() (D-12)"
  - "RelatedWriteMetadataMemoTest walkCount==1 assertions GREEN"
affects:
  - "Plan 04 (MutationGateChain extraction — still owns the 3 MUT-15 RED assertions; unaffected by this memo)"
tech-stack:
  added: []
  patterns:
    - "ConcurrentHashMap memo over an immutable metamodel keyed on a record Key(String, String) — no eviction (CancellationRegistry/StreamingSinkHolder house precedent)"
    - "Result holder caches a rejection MARKER (never a Throwable); caller rethrows a fresh canned error (D-12)"
    - "computeIfAbsent mapping function catches ToolUserError -> Result.reject() so the rejected outcome caches without storing the exception"
key-files:
  created: []
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java"
decisions:
  - "Kept computeSupported's signature (returns SupportedRelatedRelationship, throws ToolUserError on rejection) instead of changing it to return Result as the plan body literally instructed — the Plan 01 CountingResolver test seam overrides `SupportedRelatedRelationship computeSupported(MetaClass, String)` and throws on rejection; changing the signature would break the binding test contract. Memoization lives entirely in the public wrapper, which catches ToolUserError inside computeIfAbsent and caches a Result.reject() marker."
  - "Named the rejected-Result factory Result.reject() (not rejected()) to avoid the Java record-component/accessor name collision with the boolean `rejected` component."
metrics:
  duration: "~8 min"
  completed: "2026-05-31"
  tasks: 1
  files: 1
---

# Phase 17 Plan 02: Memoize RelatedWriteMetadataResolver Summary

Turned the per-call related-write metamodel walk into a walk-once-per-key `ConcurrentHashMap` memo (MUT-17), caching both the supported descriptor and the rejection outcome over the immutable Jmix metamodel — flipping the Plan 01 `walkCount==1` seam GREEN while keeping every externally observable verdict byte-for-byte identical to v1.1.

## What Was Built

- **Task 1 (MUT-17):** Memoized `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship`:
  - Added `import java.util.Map;` + `import java.util.concurrent.ConcurrentHashMap;`.
  - Added a nested `record Key(String parentEntityName, String relationshipName)` (D-11; built from `parentMetaClass.getName()`, never the raw `MetaClass`) and a nested `record Result(boolean rejected, SupportedRelatedRelationship relationship)` with `Result.of(descriptor)` / `Result.reject()` factories. The `Result` holder NEVER stores a `Throwable` (D-12).
  - Added a `private final Map<Key, Result> cache = new ConcurrentHashMap<>();` over the immutable metamodel — no eviction (D-10).
  - The public method keeps the `Objects.requireNonNull(parentMetaClass)` + blank/null `relationshipName` early-throw BEFORE key construction (so a degenerate key never enters the cache), builds the `Key`, then `cache.computeIfAbsent(key, k -> { try { return Result.of(computeSupported(...)); } catch (ToolUserError e) { return Result.reject(); } })`. On a cached rejection it rebuilds and throws a FRESH `unsupportedRelationship()` (D-12) — byte-identical code/message/hints, never the cached throwable.
  - `computeSupported(MetaClass, String)` keeps its Plan 01 signature and body unchanged (still `private`-package-visible, still throws `ToolUserError` at each rejection point) so the Plan 01 `CountingResolver` seam overrides it exactly. Javadoc updated to state memoization has landed.
  - `unsupportedRelationship`, `wireInverseReference`, `clearInverseReference`, `ensureInverseClearable`, `childBelongsToParent`, `childBelongsToDifferentParent`, `isCompositionOrDeleteCapable`, and the `SupportedRelatedRelationship` record were NOT touched.

## Verification Results

| Test | Result | Notes |
|------|--------|-------|
| `RelatedWriteMetadataMemoTest` | all green (3/3) | `walkRunsOnceForRepeatedSupportedKey` + `walkRunsOnceForRepeatedUnsupportedKey` (walkCount==1) now GREEN; `distinctKeysEachWalkOnce` (==2) + `isNotSameAs` fresh-rethrow stay green |
| `RelatedWriteMetadataResolverTest` | all green | ZERO edits to the test body — the resolver's externally observable verdicts + canned error are byte-identical (MUT-18 parity) |
| `com.vn.agent.tools.mutation.*` (full suite) | 93 green / 3 RED | the ONLY remaining failures are the 3 MUT-15 `MutationToolInvariantsTest` assertions (`mutationGateChain_gatesAppearInCanonicalOrder`, `mutationGateChain_saveTokenAppearsAfterAllGateTokens`, `mutationGateChain_carriesNoTransactionalAnnotation`) — owned by Plan 04 (MutationGateChain extraction), unaffected by this memo. The 2 MUT-17 RED seams flipped GREEN. No parity regression. |

Pre-memo the mutation suite was 91 green / 5 RED (Plan 01). This plan flips the 2 MUT-17 RED → GREEN, leaving 93 green / 3 RED (the 3 MUT-15 seams for Plan 04).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Kept `computeSupported` returning `SupportedRelatedRelationship` (throws on rejection) instead of returning a `Result`**
- **Found during:** Task 1
- **Issue:** The plan body literally instructed extracting `computeSupported` to RETURN `Result` (converting each internal `throw` to `return Result.reject()`). But the Plan 01 `RelatedWriteMetadataMemoTest.CountingResolver` test seam — the binding contract — overrides `SupportedRelatedRelationship computeSupported(MetaClass, String)` and THROWS a fresh `ToolUserError` on the unsupported branch. Changing `computeSupported`'s return type would make `CountingResolver` no longer override the seam (compile error / lost @Override) and break the test. The plan's `key_links`/`success_criteria` require the memo test to pass against this exact seam name and signature, so the test wins.
- **Fix:** Moved the `Result` boundary into the public wrapper instead: `computeSupported` is unchanged (signature + throwing body), and `computeIfAbsent`'s mapping function catches `ToolUserError` to produce `Result.reject()`. Net behavior is identical to the plan's intent (walk-once, both outcomes cached, fresh rethrow) and satisfies every acceptance criterion. No source stores a `Throwable`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java`
- **Commit:** 873cb42

**2. [Rule 3 - Blocking] Renamed the rejected-`Result` factory from `rejected()` to `reject()`**
- **Found during:** Task 1
- **Issue:** A Java `record Result(boolean rejected, ...)` auto-generates a public `rejected()` accessor; a `static Result rejected()` factory collides with it (`invalid accessor method in record Result — accessor method must be public`), failing `compileJava`.
- **Fix:** Renamed the static factory to `Result.reject()`. The boolean accessor stays `rejected()`; the public wrapper reads `result.rejected()`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java`
- **Commit:** 873cb42

## Threat Model Compliance

- **T-17-03 (mitigate):** The memo caches ONLY metamodel-relationship-support facts (immutable, security-INDEPENDENT). No `AccessManager`/`LlmExposurePolicy`/row-level decision is read or cached here; those gates run in the chain around this resolver, unchanged. The cache key `(parentEntityName, relationshipName)` is derived from already-visible inputs, so caching across security contexts cannot leak a permission-dependent outcome. Confirmed: `computeSupported` reads no security context.
- **T-17-04 (mitigate):** The cache stores a rejection MARKER (`Result.reject()`), NEVER the thrown `ToolUserError`. On a cached rejection a FRESH `unsupportedRelationship()` is rebuilt — byte-identical code/message/hints. The memo test's `isNotSameAs` assertion locks "fresh rebuild" so no stale stack/suppressed state leaks.
- **T-17-SC (accept):** No package installs — `ConcurrentHashMap` is JDK 21. No legitimacy checkpoint required.

## Known Stubs

None.

## No new threat surface

One production file modified (memoization wrapper over an immutable, security-independent metamodel fact). No new network endpoints, auth paths, file access, or schema changes at trust boundaries. The new `Result` holder cannot carry a `Throwable` by construction.

## Self-Check: PASSED

- `RelatedWriteMetadataResolver.java` exists and contains `ConcurrentHashMap`, nested `record Key`, nested `record Result`, and `cache.computeIfAbsent(...)` with a `throw unsupportedRelationship();` AFTER the lookup.
- Commit `873cb42` present in git history (`perf(17-02): memoize RelatedWriteMetadataResolver metamodel walk (MUT-17)`).
