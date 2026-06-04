---
phase: 17-mutation-internals-hardening-phase-11-follow-up
reviewed: 2026-05-31T00:00:00Z
depth: standard
files_reviewed: 6
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequest.java
findings:
  critical: 0
  warning: 4
  info: 5
  total: 9
status: issues_found
---

# Phase 17: Code Review Report

**Reviewed:** 2026-05-31
**Depth:** standard (Java / Jmix / Spring-aware, with git parity diffing against the pre-refactor `BuiltInMutationTools`)
**Files Reviewed:** 6
**Status:** issues_found

## Summary

Phase 17 splits the five `@Tool` methods of `BuiltInMutationTools` into thin adapters over a new
`MutationGateChain` (MUT-15, sealed `MutationRequest` dispatch), memoizes the relationship
support-matrix walk (MUT-17), batches FK prefetch through the constrained `DataManager`, and drops
the post-save reload via `SaveContext.setDiscardSaved(true)` (MUT-16).

I diffed the refactor against `86cef20~1` (the pre-refactor monolith). The gate ordering, the
authorization sequence, the related-write parent/child load + `childBelongsToParent` /
`childBelongsToDifferentParent` ordering, the bulk save loop, `savedIds` derivation, and the
catch-arm taxonomy are all transferred verbatim — **no behavioral regression was introduced by the
extraction itself.** The focus-area concerns hold up:

- **MUT-17 memoization** is correctly thread-safe and security-independent (no `Throwable` cached,
  fresh canned error rethrown, key built from entity *name* not the `MetaClass`). No defect.
- **MUT-16 FK batch** uses the constrained `dataManager.load(...).ids(...)` only — never
  `UnconstrainedDataManager` or raw JPQL — and a row-level-filtered id correctly collapses to
  `not_found` via `bindPrefetchedReference`, never `access_denied`. No defect.
- **`setDiscardSaved(true)`** is sound: `savedIds` are read from in-memory `@JmixGeneratedValue`
  UUIDs (populated at `create`/already-present on `update` loads), and the rollback-all transaction
  invariant does cover the silent-row-drop case (a listener/policy that drops a row from the
  `SaveContext` fails the whole transaction rather than committing a partial set). See WR-01 for the
  one residual caveat worth recording.
- **No `@Transactional` on `MutationGateChain`** (confirmed) and per-execute state lives entirely in
  the nested `Context`, not instance fields — concurrency-safe.

The findings below are quality/robustness issues; several (WR-02, WR-03, IN-02) are pre-existing
defects that the refactor *carried forward unchanged* and that should be cleaned up while this code
is open, since the tool descriptions actively misrepresent the runtime contract to the LLM.

## Warnings

### WR-01: Silent-row-drop detection now relies entirely on rollback-all; the removed defensive guard had no replacement assertion

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:677-685`
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java:64-75`

**Issue:** With `setDiscardSaved(true)` the returned `EntitySet` is empty, so `savedIds` is derived
purely from `ctx.entitiesInOrder` (the in-memory entities the chain *submitted*). The previous design
could cross-check the returned `EntitySet` against the submitted set to detect a host
policy/listener that silently drops a row. That cross-check is gone. The code comment asserts the
"rollback-all transaction invariant" covers it.

That invariant holds for the case where a listener *throws* (whole batch rolls back). It does **not**
hold for the narrower case where a `BeforeInsert`/`BeforeUpdate` entity listener returns normally but
arranges for a row not to be persisted (e.g. a listener that calls `setSoftDeletion`/marks an entity
removed, or a data store that filters the save set without raising). In Jmix this is uncommon and
arguably out of contract, but the removed guard was the only thing that would have surfaced it;
`savedIds[i]` would now report a freshly-generated UUID for a row that never hit the database, and the
tool returns `SUCCESS`.

This is a deliberate MUT-16 trade-off and the residual risk is low, but it is silent — there is no
log, no assertion, no test gate proving the invariant.

**Fix:** Either (a) keep `discardSaved(true)` but add a cheap post-commit sanity assertion that the
`SaveContext.getEntitiesToSave()` count equals the submitted count and log a marker
(`AI_AGENT_MUTATION_BULK_SAVE_SET_SHRANK`) if not; or (b) document the exact Jmix listener shapes the
invariant does and does not cover in the class JavaDoc and add a focused test that a throwing listener
rolls back and a non-throwing drop is explicitly declared out-of-scope. Do not leave the only evidence
of coverage in a code comment.

### WR-02: `bulk_save_records` tool description promises a `failedRowIndex` / `errorCode` result shape the runtime never returns

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java:309-348`
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:234-255`

**Issue:** The tool description tells the LLM that a per-row failure returns
`{outcome: ERROR | BLOCKED | COMMIT_FAILED, failedRowIndex: N, errorCode: <code>}` (lines 311-313)
and gives a worked example `{"outcome":"ERROR","failedRowIndex":1,"errorCode":"validation_failed"}`
(line 346). But every error path in `MutationGateChain.execute` returns
`toolResultFormatter.error(translated)`, which serializes only the `ToolErrorDto` (`error` + `reason`
+ `expected`). `ctx.failedRowIndex` is set during the save loop (line 623) but is **never read** by
any catch arm, and no error response ever carries `failedRowIndex` or `errorCode`. The LLM is told it
will get a row index it cannot get. (Note: this mismatch pre-exists the Phase 17 refactor — the
pre-refactor monolith had the same gap — but it remains a live correctness/contract defect.)

**Fix:** Either thread `ctx.failedRowIndex` into the bulk error result/audit (see WR-03, the serializer
already exists), or correct the tool description to describe the actual error shape
(`{error, reason, expected}`) so the LLM is not instructed to parse a field that is absent.

### WR-03: `DiffSerializer.serializeBulkFailureSummary(...)` is dead code — the bulk failure-attribution path was never wired

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java:208-216`
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:143,623,669`

**Issue:** `serializeBulkFailureSummary(int failedRowIndex, String stableErrorCode, String operation)`
has no callers anywhere in `src/main` (verified by grep). It exists precisely to emit the
`failedRowIndex`/`errorCode` shape WR-02 promises, but nothing invokes it. Correspondingly,
`Context.failedRowIndex` (line 143) is written in two places (lines 318, 623) and reset (line 669)
but never read — it is effectively a dead field. The intent (attribute the correct failed row to the
LLM) was designed and then left unconnected. This is the root cause of WR-02.

**Fix:** Wire the bulk catch arms to build the failure audit/result via
`serializeBulkFailureSummary(ctx.failedRowIndex, translated.toDto().error(), operationKind)` when
`ctx.failedRowIndex != null`, then update the success/error result accordingly. If the
`failedRowIndex` contract is genuinely abandoned, delete the dead serializer and the `Context` field
and fix the tool description (WR-02) so the codebase stops carrying a half-built feature.

### WR-04: Bulk row-level errors raised inside `prefetchReferences` lose their row index (`failedRowIndex` stays null)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:466-487`
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java:131-152`

**Issue:** `coerceBulk` calls `prefetchReferences(ctx.metaClass, rowAttrsInOrder)` in gate 5, BEFORE
the per-row save loop that sets `ctx.failedRowIndex`. `prefetchReferences` iterates every row and
calls `validateWritableProperty` (throws `validation_failed` on an unknown/read-only/PK attribute)
and `requireUuidId(parseEntityId(...))` (throws `parameter_conversion_error` on a malformed FK UUID).
If row 4 of a 5-row batch carries a bad attribute name or an unparseable FK, the error is thrown with
`ctx.failedRowIndex == null`, so even if WR-02/WR-03 are fixed the offending row cannot be attributed.
The per-row index is only knowable inside the prefetch loop, which currently does not track it.
(Also pre-existing — the monolith had the identical structure — but it directly undermines the
`failedRowIndex` contract this phase touches.)

**Fix:** Make `prefetchReferences` row-index-aware (e.g. accept a callback or surface the failing row
ordinal) so the chain can set `ctx.failedRowIndex` before the throw propagates, OR move
attribute-name/FK-UUID validation into the per-row save loop where `ctx.failedRowIndex` is already
maintained. At minimum, document that prefetch-phase failures are reported without a row index.

## Info

### IN-01: `currentAuthentication.getUser()` dereferenced outside the try/catch in `execute`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:195`

**Issue:** `ctx.userUsername = currentAuthentication.getUser().getUsername();` runs before the `try`.
If `getUser()` ever returns null or throws (no authentication bound), the exception escapes `execute`
entirely — bypassing the catch ladder, the audit write, and the structured error contract. In the
tool-call path an authentication is always bound, so this is low-risk, and it is byte-identical to the
pre-refactor behavior, but the new single-entry-point structure makes it trivial to move inside the
try and gain uniform fail-closed handling.

**Fix:** Move the username read to the first line inside the `try` so an unexpected auth failure is
translated and audited like any other `Throwable`.

### IN-02: `relatedAction` / several `Context` fields are write-once hand-offs that read fragile across gates

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:136-168,604,607,749-769`

**Issue:** `Context` carries ~20 mutable stage-hand-off fields. `relatedAction` is set only in
`saveRelated` and read in `finalizeRelated`; `parent`/`child` are set in `guardRelated` and read in
`saveRelated`. The ordering happens to be correct, but the compiler cannot prove a field is populated
before it is read — a future gate reorder could read a null silently. This is the expected cost of the
mutable-context pattern, recorded as Info, not a defect.

**Fix (optional):** Consider per-variant typed sub-contexts (a `RelatedContext`, `BulkContext`) or
returning stage results between gates so hand-off dependencies are compiler-checked rather than
convention-checked.

### IN-03: `coerceBulk` sets `ctx.coercedAttributes = null` as a sentinel

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java:482`

**Issue:** `ctx.coercedAttributes = null; // not used for bulk` relies on a comment to communicate that
the field is intentionally unused for the Bulk variant. Because `Context` is shared across all five
variants this is benign, but an explicit null assignment-as-documentation is a code smell.

**Fix:** Drop the assignment (the field defaults to null) or move bulk-only state into a dedicated
sub-structure (see IN-02).

### IN-04: `RelatedWriteMetadataResolver.computeSupported` re-checks the blank `relationshipName` guard already enforced by the public method

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java:155-157,191-193`

**Issue:** `resolveSupportedRelatedWriteRelationship` early-throws on null/blank
`relationshipName` (line 155) before key construction, and `computeSupported` repeats the same guard
(line 191). The duplicate is defensible as defense-in-depth for the test seam that calls
`computeSupported` directly, and is documented as such, so this is informational only.

**Fix:** None required; keep if the test seam depends on it, otherwise collapse.

### IN-05: Memo map growth is bounded by the immutable metamodel but unbounded in adversarial entity-name inputs only at the resolve gate, not here

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java:136-167`

**Issue:** The `ConcurrentHashMap` memo keys on `(parentEntityName, relationshipName)`. Both come from
already-resolved/validated facts at the call site (`authorizeRelated` resolves the parent `MetaClass`
via `resolveUpdatableEntityOrThrow` before calling the resolver, and a blank relationship is
early-thrown before key construction). So the key space is bounded by the finite metamodel and cannot
be inflated by arbitrary LLM strings reaching this map. The no-eviction design is therefore safe. This
is a confirmation, not a defect — recorded because unbounded caches keyed on external input are a
common footgun and a future caller that bypasses `resolveUpdatable...` first could change the
guarantee.

**Fix:** None required. If a future code path ever calls the resolver with an unvalidated
`parentMetaClass`/`relationshipName`, revisit (e.g. size cap or `Caffeine` with max size).

---

_Reviewed: 2026-05-31_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
