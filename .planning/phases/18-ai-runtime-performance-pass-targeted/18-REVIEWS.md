---
phase: 18
cycle: 2
reviewers: [codex]
reviewed_at: 2026-06-09T03:48:10Z
plans_reviewed: [18-01-PLAN.md, 18-02-PLAN.md, 18-03-PLAN.md, 18-04-PLAN.md, 18-05-PLAN.md]
review_model: cx/gpt-5.5 (xhigh reasoning)
source_grounding_pass: complete
prior_cycle_high: 5
current_high: 0
---

# Cross-AI Plan Review — Phase 18 (Cycle 2)

Phase 18 is "AI-Runtime Performance Pass (targeted)" — targeted memoization over already-shipped Jmix
AI-agent code (5 plans, PERF-01..05). This is a **re-review (cycle 2)** after a `--reviews` replan that
addressed the 5 HIGH concerns raised in cycle 1. The review mandate was to VERIFY each prior HIGH was
actually fixed in the current PLAN.md files, assess whether any fix introduced a NEW HIGH, and re-run
the source-grounding pass over every cited existing-code symbol.

Only one external reviewer (Codex `cx/gpt-5.5`, xhigh reasoning) was available — `gemini` not installed;
`claude` self-skipped as the executing CLI. Findings are cross-checked against the reviewer's own
source-grounding pass against the live code.

## Cycle-2 Verdict (headline)

- **All 5 prior HIGH concerns: FULLY RESOLVED** (verified in current plan text + against live source).
- **New HIGHs introduced by the replan: 0.**
- **Remaining unresolved HIGHs: 0.**
- Overall risk: **MEDIUM** (down from cycle-1 MEDIUM-HIGH). Residual items are MEDIUM/LOW polish, not
  execution blockers.

---

## Prior-HIGH Verification

| # | Cycle-1 HIGH | Cycle-2 verdict | Evidence in current plans |
|---|--------------|-----------------|---------------------------|
| 1 | Gradle task path `:ai-agent:ai-agent:test`/`compileJava` (nonexistent) | **FULLY RESOLVED** | Zero `:ai-agent:ai-agent:` occurrences across all 5 plans + `18-VALIDATION.md`; all verify/verification blocks use `:ai-agent:test` / `:ai-agent:compileJava`. Source-confirmed: `ai-agent/settings.gradle` (rootProject `ai-agent-addon`) includes only `:ai-agent` and `:ai-agent-starter`, so `:ai-agent:test` is the correct path. |
| 2 | Mutable cached denylist (18-01) — caching the fresh `LinkedHashSet` app-wide lets callers poison the shared cache | **FULLY RESOLVED** | 18-01 Task 1 mandates the cache lambda wrap the result in `Set.copyOf(hidden)` / `Collections.unmodifiableSet(...)` BEFORE storing; acceptance criterion requires the immutable token present. Task 2(b) adds `assertThrows(UnsupportedOperationException.class, () -> returned.add(...))` plus an assertion the cache is not re-fetched/poisoned afterward (`times(1)` still holds). T-18-04 added to the threat register. |
| 3 | `RunContext.perTurnCache()` no-active-turn / post-clear semantics (18-02) — always-store lazy-init makes "empty after clear" self-fulfilling and can leave a map on a pooled thread | **FULLY RESOLVED** | 18-02 Task 1 makes the accessor active-turn-gated: `CURRENT.get() == null` ⇒ return `Collections.emptyMap()` with NO `PER_TURN_CACHE.set(...)`; lazy-init a stored `HashMap` only within an active turn. Adds `perTurnMemoize(...)` (recompute-without-store off-turn) and a non-initializing `perTurnCacheSnapshotForTest()` returning the raw slot (may be null). Test asserts `perTurnCacheSnapshotForTest()` is null after `clear()` (meaningful, not self-fulfilling) and that a no-active-turn call recomputes-without-storing. |
| 4 | `canReadEntity` not memoized (18-02) — D-08 names readable-entity verdicts, ~15 call sites | **FULLY RESOLVED** | 18-02 Task 2(a) routes `canReadEntity`, `canCreate`, `canUpdate`, AND `getReadableSchema()` through `RunContext.perTurnMemoize(...)` with a `CrudVerdictKey(metaClassName, operation)` using distinct `"read"`/`"create"`/`"update"` operations. Test asserts `delegate.canReadEntity(mc)` fires `times(1)` across N same-turn calls. |
| 5 | 18-04 test feasibility + premature cache branch — no Mockito seam for private Tika path; Wave-1 cache cannot use Wave-2 `RunContext.perTurnCache()` | **FULLY RESOLVED** | 18-04 counts at the injectable `FileStorage` seam via `FileStorageLocator` → `FileStorage.openStream(FileRef)` (which gates both the image-bytes and Tika paths), explicitly forbidding direct counting of the private `readFileBytes()`/`extractDocumentText()`/inline `TikaDocumentReader`. The cache branch is a SELF-CONTAINED resolver-level `ConcurrentHashMap` with its OWN attach/delete/TTL + `@EventListener(AiSettingsChangedEvent)` (UI_SETTINGS) eviction and explicitly forbids `RunContext.perTurnCache`/`perTurnMemoize` (acceptance criterion: grep == 0). RESEARCH Open Q2 + Pitfall 5 predict the regression-lock branch (no cache, no Wave-2 dependency). |

**Conclusion: 5/5 prior HIGHs FULLY RESOLVED; 0 introduced as new HIGH.**

---

## Codex Review

### Prior-HIGH Verification (Codex)

1. **Gradle task path — FULLY RESOLVED.** Current verify commands consistently use `:ai-agent:compileJava`
   and `:ai-agent:test`; zero remaining `:ai-agent:ai-agent:` occurrences.
2. **Mutable cached denylist (18-01) — FULLY RESOLVED.** Cache lambda returns `Set.copyOf`/
   `unmodifiableSet` before storing; test asserts `UnsupportedOperationException` on mutation and no
   re-fetch/poisoning. Closes the shared mutable-cache poisoning risk.
3. **`perTurnCache()` no-active-turn / post-clear (18-02) — FULLY RESOLVED.** Returns `emptyMap()` with no
   store when `CURRENT.get()==null`; `perTurnMemoize` recomputes-without-store off-turn;
   `perTurnCacheSnapshotForTest()` proves the raw slot is null after `clear()`. Fixes both the
   self-fulfilling test and the pooled-thread residue. (Stale wording in objective/threat-model mentions
   `perTurnCache().isEmpty()`, but the task/action/acceptance text correctly uses the raw-snapshot seam.)
4. **`canReadEntity` not memoized — FULLY RESOLVED.** `canReadEntity`/`canCreate`/`canUpdate`/
   `getReadableSchema()` routed through `perTurnMemoize` with distinct `"read"`/`"create"`/`"update"` keys;
   test verifies `delegate.canReadEntity(mc)` fires `times(1)` across same-turn calls.
5. **18-04 test feasibility + premature cache branch — FULLY RESOLVED for the original HIGH.** Counts at
   the injectable `FileStorage` seam via `FileStorageLocator`/`openStream`, forbids private-Tika counting;
   cache branch is self-contained with attach/delete/TTL + `AiSettingsChangedEvent(UI_SETTINGS)` eviction
   and forbids `RunContext.perTurnCache`. Residual: inconsistent `getDefault()` wording (see below).

### New Issues Introduced by the Replan (Codex)

- **[MEDIUM] 18-04 cache branch is labelled "per-turn" but the resolver `ConcurrentHashMap<TaskFileMediaKey,
  Media>` is keyed only by `(conversationId, taskFileId)` with no turn boundary.** If the cache branch is
  taken it is not truly per-turn unless gated by a fresh constrained row load + reliable lifecycle removal.
  Either add a real turn discriminator / turn-end clear, or stop calling it "per-turn" and document it as a
  bounded app-level attachment cache evicted on attach/delete/TTL + settings change. (Fires only in the
  residual cache branch; RESEARCH predicts the regression-lock branch.)
- **[MEDIUM] 18-04 malformed plan structure:** acceptance-criteria bullets appear after `</verify>` without
  an opening `<acceptance_criteria>` tag, then a closing `</acceptance_criteria>`. Constraints are still
  readable, but a tag-driven executor/template may mishandle the section.
- **[MEDIUM] 18-02 only requires top-level immutability for cached `getReadableSchema()`.** The shape is
  `Map<MetaClass, Set<String>>` with mutable `LinkedHashSet` values; `Map.copyOf`/`unmodifiableMap` blocks
  top-level mutation but not mutation of the cached attribute-name sets. Require copying each value to
  `Set.copyOf(...)` before caching/returning.

### Other Concerns — per plan (Codex)

- **[LOW] 18-01** — artifact metadata claims an entity-name→`MetaClass` memo is provided, but the objective
  makes that memo conditional on the proxy proving a metadata SELECT. Task text is correct; clean up the
  metadata to match.
- **[LOW] 18-02** — stale wording still says assert `RunContext.perTurnCache().isEmpty()` after clear; the
  actionable task/acceptance correctly use `perTurnCacheSnapshotForTest()`. Remove the stale phrase to avoid
  executor drift.
- **[LOW/MEDIUM] 18-04** — `FileStorageLocator.getDefault()` remains in `must_haves`/acceptance text even
  though the live path (`AiTaskFileMediaResolver:358`) calls `getByName(...)`. Action text correctly says to
  stub `getByName(...)`; fix every remaining `getDefault()` reference since acceptance criteria are often
  treated as authoritative.
- **[LOW] 18-04** — one branch still says `verify(tika/...; times(1))` despite the plan correctly stating Tika
  internals are not Mockito-countable. Remove or qualify (only if a deliberate package-private extraction
  seam is added).
- **[LOW] 18-05** — the admin-edit visibility test exercises raw denylist eviction, not the full memoized
  LLM-facing schema/verdict path across `RunContext.clear()` / next `RunContext.set(...)`. Plan 02 covers
  turn clearing separately, so not a HIGH; the PERF-05 test would be stronger asserting `canReadEntity` /
  `getReadableSchema` reflects the edit on the next turn.

### Overall (Codex)

**Risk: MEDIUM. Execute unchanged? No** — substantively all 5 prior HIGHs resolved, **0 remaining
unresolved prior HIGHs, 0 new HIGHs**; the 18-04 cache-branch turn-boundary ambiguity and shallow schema
immutability are meaningful MEDIUMs to correct first.

---

## Source-Grounding Pass (reviewer-performed, cycle 2)

Re-confirmed every existing-code symbol the plans cite resolves to a real declaration via grep/Read.
Symbols under each plan's "Artifacts this phase produces" are EXCLUDED (created by this phase).

**Result: 100% VERIFIED. Zero MISSING symbols.** All cited source + test files exist; load-bearing line
anchors are accurate.

### Files (all VERIFIED)
- Source: `exposure/LlmExposurePolicy.java`, `LlmExposureRuleRepository.java`, `AiExposureRuleEntityListener.java`,
  `LlmExposureChangedEvent.java`, `AiInternalEntityNames.java`; `orchestration/RunContext.java`,
  `BaselineContextProvider.java`, `AiUiSettingsResolver.java`; `tools/mutation/RelatedWriteMetadataResolver.java`,
  `guard/IterationCounter.java`, `audit/AuditAdvisor.java`, `rag/RetrievalFilterBuilder.java`,
  `taskfile/AiTaskFileMediaResolver.java`, `tools/BuiltInDataTools.java`, `admin/config/AiSettingsChangedEvent.java`.
- Test analogs: `performance/ToolQueryCountBaselineTest.java`, `admin/config/AiUiSettingsResolverReadThroughTest.java`,
  `admin/config/AiSettingsChangedEventListenerInvariantTest.java`, `tools/mutation/RelatedWriteMetadataMemoTest.java`,
  `rag/RetrievalFilterBuilderDenylistTest.java`, `taskfile/PerTurnMediaInjectionTest.java`,
  `performance/MutationFkBatchLoadQueryCountTest.java`, `performance/QueryCountingDataSourceConfiguration.java`.

### Load-bearing symbols / anchors (VERIFIED)
- `LlmExposurePolicy`: `getReadableSchema():47`, `canReadEntity():62`, `canReadAttribute():72`, `canCreate():82`,
  `canUpdate():95`, `canModify()→canUpdate():107-108`, `getDenylistedEntityNames():117-119`, private
  `hiddenEntityNames():121-125` — all exact; `hiddenEntityNames` confirmed private + self-invoked from 5 callers (D-07 premise holds).
- `LlmExposureRuleRepository.findEnabledExcludedEntityNames():34` — exact.
- `RunContext`: 12 ThreadLocal slots `:31-42` (`PREPARE_FORM_DRAFT_INVOKED:42`), `clear():142-155` with all 12
  `.remove()` incl. `PREPARE_FORM_DRAFT_INVOKED.remove():154`, `CURRENT`/`get():50` — exact.
- `RetrievalFilterBuilder.buildFor():77`, single `getDenylistedEntityNames():99`, `b.nin(ChunkMetadata.SOURCE_ENTITY,...):102` — exact.
- `AiTaskFileMediaResolver`: `FileStorageLocator` ctor edge `:117/:124`, `resolveActive():155`, constrained
  `dataManager.load(AiTaskFile.class):163`, `extractDocumentText():320`, `readFileBytes():357`,
  `fileStorageLocator.getByName(...):358`, `fileStorage.openStream(...):359`, `new TikaDocumentReader(...):335` — exact.
- `AiUiSettingsResolver`: `UnconstrainedDataManager.load(AiUiSettings.class):80`, `resolveTaskFile*` accessors `:97,106,115,128` — exact.
- `AiSettingsChangedEvent` `enum Kind {PARAMETERS, UI_SETTINGS}:28-32`, `getKind():42`; `LlmExposureChangedEvent(Object source):16` — exact.
- `ToolQueryCountBaselineTest.java:151` reads EXACTLY `private static final long METAMODEL_TOOL_POLICY_LOOKUP_CEILING = 5L;`;
  four `*_STEADY_STATE_CEILING = 1000L` at `:192-195`; assertion sites `:160-164`/`:176-180` — exact.
- `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan():229`, `findMainJavaRoot():327` — present.

### Build / dependency facts (VERIFIED)
- `ai-agent/ai-agent/ai-agent.gradle:111` declares `net.ttddyy:datasource-proxy:1.11.0` (ALLOWED harness); comment `:107`.
- NO `caffeine`/`jmh`/`gatling` token in `ai-agent.gradle` or `ai-agent/build.gradle` — PERF-05 dep-scan is satisfiable today.
- `settings.gradle` (rootProject `ai-agent-addon`) includes only `:ai-agent` and `:ai-agent-starter`.

### needs-acknowledgement (from source-grounding)
- **No MISSING symbols.**
- **MINOR INCONSISTENCY (not a missing symbol):** 18-04 `must_haves`/acceptance text references the counting
  seam as `FileStorageLocator.getDefault()`, but the live encode path calls `fileStorageLocator.getByName(
  fileRef.getStorageName())` at `:358` — there is no `getDefault()` in the path. The plan's `read_first`
  correctly identifies `getByName(...):358` and hedges "add `getDefault()` only if the code path uses it,"
  so this is a wording cleanup (LOW/MEDIUM), not a grounding failure or a HIGH.

---

## Consensus Summary

Single external reviewer (Codex) cross-checked against the reviewer's source-grounding pass.

### Agreed Strengths
- All 5 cycle-1 HIGH concerns are FULLY RESOLVED in the current plan text and consistent with the live source.
- Memoization direction remains correct: app-wide denylist memo inside `LlmExposurePolicy` (avoiding the
  `@Cacheable` self-invocation trap, D-07), per-turn `RunContext` ThreadLocal for user/role-sensitive verdicts,
  denylist reuse in the RAG path, proxy-first discipline for task-file encode.
- Eviction wiring reuses already-shipped single-publish-site events (`LlmExposureChangedEvent` /
  `AiSettingsChangedEvent`) as consumers only.
- Plans are exceptionally well source-grounded (100% of cited existing symbols real; anchors exact).

### Agreed Concerns (highest priority — none HIGH)
1. **[MEDIUM] 18-02 cached `getReadableSchema()` deep immutability** — top-level `Map.copyOf`/`unmodifiableMap`
   does not protect the mutable `Set<String>` values; require per-value `Set.copyOf(...)`.
2. **[MEDIUM] 18-04 cache-branch "per-turn" labeling** — the resolver `ConcurrentHashMap` keyed by
   `(conversationId, taskFileId)` has no turn boundary; clarify it as an attach/delete/TTL + settings-evicted
   app-level cache, or add a turn discriminator. Fires only in the residual cache branch.
3. **[MEDIUM] 18-04 malformed `<acceptance_criteria>` tag block** — opening tag missing; risks tag-driven
   executor mishandling.
4. **[LOW/MEDIUM] 18-04 `getDefault()` wording** — fix remaining references to `getByName(...)` to match the
   live path.
5. **[LOW] 18-02 stale `perTurnCache().isEmpty()` phrase** and **[LOW] 18-01 metadata/objective mismatch on the
   conditional `MetaClass` memo** — doc cleanups to avoid executor drift.

### Divergent Views
- No second external reviewer to diverge. The reviewer's source-grounding pass independently confirms Codex's
  FULLY-RESOLVED verdicts on all 5 prior HIGHs and the MINOR `getDefault()` inconsistency.

---

## Current HIGH Concerns

None.

The replan fully resolved all 5 cycle-1 HIGH concerns and introduced no new HIGH. Remaining items are
MEDIUM (deep schema immutability; 18-04 cache-branch turn-boundary labeling; malformed acceptance-tag block)
and LOW (doc/wording cleanups). These are recommended tightening before execution but are not blockers.
