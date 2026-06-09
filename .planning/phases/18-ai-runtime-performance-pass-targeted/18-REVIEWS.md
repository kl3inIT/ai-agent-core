---
phase: 18
reviewers: [codex]
reviewed_at: 2026-06-09T03:26:20Z
plans_reviewed: [18-01-PLAN.md, 18-02-PLAN.md, 18-03-PLAN.md, 18-04-PLAN.md, 18-05-PLAN.md]
review_model: cx/gpt-5.5 (xhigh reasoning)
source_grounding_pass: complete
---

# Cross-AI Plan Review — Phase 18

Phase 18 is "AI-Runtime Performance Pass (targeted)" — a targeted memoization pass over
already-shipped Jmix AI-agent code (5 plans, PERF-01..05). The internal gsd-plan-checker passed
with 0 blockers; this is an external Codex (`cx/gpt-5.5`, xhigh reasoning) second perspective plus a
source-grounding pass over every existing-code symbol the plans cite.

## Codex Review

### Cross-Plan Findings

The main plan-quality issue is not the memoization direction; it is verification and cache-boundary
precision.

- **[HIGH]** All plan verification commands use `cd ai-agent && ./gradlew :ai-agent:ai-agent:test` /
  `compileJava`, but the Gradle root at `ai-agent/` only has `:ai-agent` and `:ai-agent-starter`.
  Use `./gradlew :ai-agent:test` and `./gradlew :ai-agent:compileJava`.
- **[HIGH]** Cached public values must become immutable. `LlmExposurePolicy.getDenylistedEntityNames()`
  currently returns a fresh mutable `Set`; caching that same mutable set app-wide would let any caller
  accidentally poison the denylist cache.
- **[HIGH]** `RunContext.perTurnCache()` should not create a stored ThreadLocal map when there is no
  active run id. A foreign thread with `RunContext.get() == null` should bypass caching or use an
  unstored local map, otherwise it can leave a pooled-thread ThreadLocal behind outside a turn.

### 18-01 (PERF-02 denylist memo)

**Summary:** Strong foundation plan. Puts the denylist memo in the correct class, avoids Spring proxy
caching, wires the intended eviction event, and adds both count and invariant proxies.

**Strengths**
- Correctly avoids `@Cacheable`; local source confirms `hiddenEntityNames()` is private and
  self-invoked (`LlmExposurePolicy.java:121`).
- Uses the already-shipped `LlmExposureChangedEvent` as a consumer-only eviction hook.
- Call-count test plus event-subscription invariant is the right proxy mix.

**Concerns**
- **[HIGH]** The cached denylist set must be immutable or defensively copied before returning.
  Otherwise a public caller of `getDenylistedEntityNames()` can mutate the app-wide cached set.
- **[MEDIUM]** `ConcurrentHashMap.computeIfAbsent()` plus `clear()` has a race: an in-flight old
  computation can repopulate after an event clear. If "admin edit visible next turn" is strict, use
  synchronized load/clear or a versioned/atomic snapshot.
- **[MEDIUM]** The plan weakens D-06 by making entity-name→`MetaClass` memoization conditional. If no
  such derivation exists in scope, prove that with a source invariant; otherwise implement the
  `ConcurrentHashMap` memo explicitly.
- **[MEDIUM]** Lowering `ToolQueryCountBaselineTest` from `5L` to `4L` is probably achievable, but it
  is a weak SELECT proxy. The call-count test is doing the real proof.

**Suggestions**
- Cache `Collections.unmodifiableSet(hidden)` or return `Set.copyOf(...)` consistently.
- Add an eviction-race note or make cache load and clear mutually exclusive.
- Replace all verification commands with `./gradlew :ai-agent:test ...`.

**Risk Assessment:** MEDIUM. The design is right, but mutable cached state and eviction races are
security-relevant enough to fix before execution.

### 18-02 (PERF-01 per-turn RunContext cache)

**Summary:** Correctly scopes user/role-sensitive memoization to `RunContext`, but misses
`canReadEntity()` and needs a safer "no active turn" behavior for the ThreadLocal accessor.

**Strengths**
- Correctly rejects process-wide `runId` maps and Reactor context propagation.
- Adds a source-boundary invariant keeping the cache out of `BuiltInDataTools`.
- `clear()` wipe is placed at the correct lifecycle boundary (`RunContext.java:142`).

**Concerns**
- **[HIGH]** The plan does not cache `canReadEntity()`, even though PERF-01/D-08 include
  readable-entity verdicts. Local grep shows many hot callers use `canReadEntity()` directly.
- **[HIGH]** `perTurnCache()` lazy-init after `RunContext.clear()` makes the proposed "assert empty
  after clear" test pass by creating a new stored empty map. That hides, rather than proves, cleanup.
- **[HIGH]** Safe-miss should mean "recompute without storing" when no run is active, not "create a
  new ThreadLocal map on an arbitrary worker thread."
- **[MEDIUM]** Cached `getReadableSchema()` should also be immutable or defensively copied to avoid
  persistent caller mutation.
- **[MEDIUM]** The call-count test wording expects exactly one
  `AccessManager.applyRegisteredConstraints` across create and update; with separate operation keys,
  expected count is one per operation.

**Suggestions**
- Add `canReadEntity()` read-through with a `CrudVerdictKey` or `ReadVerdictKey`.
- Add `RunContext.hasPerTurnCacheForTest()` / `perTurnCacheSnapshotForTest()` that does not
  initialize the ThreadLocal.
- Make `perTurnCache()` return `null`/optional when `RunContext.get() == null`, and have
  `LlmExposurePolicy` compute directly on that path.

**Risk Assessment:** HIGH. The plan's target is security-sensitive, and the current accessor/test
shape could create exactly the kind of pooled-thread leakage the phase is trying to prevent.

### 18-03 (PERF-03 RAG filter once-per-retrieval)

**Summary:** Mostly a regression-lock plan. The current `RetrievalFilterBuilder.buildFor()` already
reads the denylist once and builds the expression in one pass, so the plan's no-op production stance
is reasonable.

**Strengths**
- Preserves role extraction per `Authentication`, avoiding cross-user role staleness.
- Keeps the denylist cache centralized in `LlmExposurePolicy`.
- Protects the existing denylist clause test from churn.

**Concerns**
- **[MEDIUM]** Verifying `getDenylistedEntityNames()` `times(1)` proves one denylist lookup, not
  necessarily "filter expression built once." Acceptable only because the source is simple.
- **[LOW]** `Filter.Expression.toString()` smoke assertions can be brittle; keep the existing
  `RetrievalFilterBuilderDenylistTest` as the real clause-shape authority.
- **[LOW]** The roadmap wording mentions `(source_entity IS NULL) OR NOT IN`; the current code
  intentionally uses only `NIN` due to Spring AI converter limitations. The summary should explicitly
  say the existing tested shape is preserved.

**Suggestions**
- Add a source invariant that `buildFor()` contains exactly one `getDenylistedEntityNames()` call.
- Keep new tests focused on call count; avoid over-asserting Spring AI `toString()` formatting.

**Risk Assessment:** LOW. The production path is already structurally correct; the plan mainly needs
precise wording.

### 18-04 (PERF-04 task-file Media encode)

**Summary:** The proxy-first discipline is good, but the proposed pure-unit test is under-specified
for the current private `FileStorage`/Tika path, and the conditional cache branch is dangerous if it
runs before Plan 02.

**Strengths**
- Correctly avoids adding a cache when the path is already once per turn.
- Keeps constrained `DataManager` as a hard acceptance criterion.
- Preserves existing task-file budget/TTL tests.

**Concerns**
- **[HIGH]** The test plan assumes Mockito can count private `readFileBytes()` /
  `extractDocumentText()` / `new TikaDocumentReader(...)` calls. The current class has no seam for
  that.
- **[HIGH]** If the cache branch is taken in Wave 1, `RunContext.perTurnCache()` is not available yet.
  A resolver-level `ConcurrentHashMap<(conversationId, taskFileId), Media>` would risk stale/deleted
  attachment reuse unless concrete add/delete/TTL invalidation hooks are implemented.
- **[MEDIUM]** The plan may confuse "one turn invokes `resolveActive()` once" with "multiple
  `resolveActive()` calls in a test." Repeated calls should not force a new production cache unless the
  real service path repeats them.
- **[MEDIUM]** Current `AiUiSettingsResolver` loads the singleton separately for each task-file
  setting. Counting `resolveTaskFile*()` calls does not prove singleton DB reads are once per turn.

**Suggestions**
- Use a counting fake `FileStorage` and real small test files, or add a small package-private document
  extraction seam before trying to count Tika.
- If a real cache is needed, make 18-04's cache branch depend on 18-02 or split it into a Wave-2
  follow-up.
- Count `UnconstrainedDataManager.load(AiUiSettings.class)` if the settings-read claim is part of
  PERF-04.

**Risk Assessment:** HIGH. Regression-lock branch is safe; cache branch and test feasibility need
tightening before autonomous execution.

### 18-05 (PERF-05 closing gate)

**Summary:** Good closing-gate concept: dependency invariant, proxy existence, event visibility, and
full-suite validation. Main weakness is that some checks are structural or doc-coupled rather than
behavior-coupled.

**Strengths**
- Explicitly forbids JMH/Gatling/Caffeine.
- Adds a value-change eviction test, not only a call-count test.
- Correctly makes this plan depend on all implementation plans.

**Concerns**
- **[MEDIUM]** Reading `18-04-SUMMARY.md` from a Java test couples source tests to planning artifacts.
  Detect whether a settings memo exists from source instead.
- **[MEDIUM]** Calling `policy.onExposureChanged(...)` directly proves the method works, not that
  Spring event delivery is wired. Plan 01's source scan helps, but the wording should not overclaim
  "published via event."
- **[MEDIUM]** Proxy-existence checks prove files exist, not that they contain meaningful assertions.
  Full suite helps, but the invariant itself is shallow.
- **[LOW]** The dependency scan should walk all add-on Gradle scripts, not just two hardcoded files.

**Suggestions**
- Source-scan for `@EventListener(AiSettingsChangedEvent` instead of reading the plan summary.
- Keep the direct-method eviction test, but pair it with the event-listener source invariant.
- Make the "only existing test body edit" gate an explicit scripted diff check in the summary, since
  it is not a JUnit invariant.

**Risk Assessment:** MEDIUM. The gate is useful, but several checks can pass while missing the intended
behavioral guarantee.

**Codex Overall Risk:** MEDIUM-HIGH. The phase direction is sound and most dependencies are ordered
correctly, but Codex "would not execute these plans unchanged." Fix the Gradle task paths, immutable
cached values, `RunContext` no-active-turn behavior, missing `canReadEntity()` memoization, and the
18-04 test/cache branch before starting implementation.

---

## Source-Grounding Pass (reviewer-performed)

For every existing-code symbol the plans cite (classes, methods, fields, file paths, test classes), I
confirmed a real declaration in source via grep/Read. Symbols the plans declare under "Artifacts this
phase produces" are EXCLUDED (created by this phase, not references).

**Result: 100% VERIFIED. Zero MISSING / AMBIGUOUS / UNCHECKABLE symbols.** Every cited existing
symbol resolves to a real declaration, and the line anchors are accurate (exact in the load-bearing
cases).

### Files (27/27 VERIFIED)
All 27 cited source + test files exist at the cited paths under
`ai-agent/ai-agent/src/{main,test}/java/com/vn/agent/...`.

### Load-bearing symbols / line anchors (all VERIFIED)
- `LlmExposurePolicy.getReadableSchema():47`, `canReadEntity():62`, `canReadAttribute():72`,
  `canCreate():82`, `canUpdate():95`, `canModify()→canUpdate():107-108`,
  `getDenylistedEntityNames():117`, private `hiddenEntityNames():121` — all exact.
- `LlmExposureRuleRepository.findEnabledExcludedEntityNames():34` — exact.
- `AiInternalEntityNames.all():30` — exact.
- `RelatedWriteMetadataResolver` `ConcurrentHashMap` field `:136`, `computeIfAbsent` `:160` — exact.
- `AiExposureRuleEntityListener` `@EventListener` `:31` + `publishEvent(new LlmExposureChangedEvent(this))`
  `:33` (SINGLE publish site) — exact.
- `LlmExposureChangedEvent(Object source)` — single-arg ctor; confirms the plans' `new
  LlmExposureChangedEvent(this)` calls compile.
- `RunContext` — exactly 12 `ThreadLocal` slots `:31-42`, `PREPARE_FORM_DRAFT_INVOKED:42`,
  `clear():142-155` with all 12 `.remove()` calls including `PREPARE_FORM_DRAFT_INVOKED.remove():154` —
  exact (matches plan 18-02's "existing 12 ThreadLocal slots" and ":154" anchor).
- `AuditAdvisor.openEnvelope():94`, `closeEnvelope():108` → `RunContext.clear():121` — exact.
- `GuardedToolCallingManager.executeToolCalls():96`, `RunContext.getRootAuditId():100`,
  `RunContext.get():149`, `RunContext.getConversationId():154` — exact (D-03 streaming-visibility
  evidence sites).
- `IterationCounter` `ThreadLocal<Integer> COUNT:17`, `start():23`, `reset():41` — exact.
- `RetrievalFilterBuilder.buildFor():77`, `getDenylistedEntityNames():99`,
  `b.nin(ChunkMetadata.SOURCE_ENTITY,...):102` (NIN clause) — exact.
- `AiTaskFileMediaResolver.resolveActive():155`, `extractDocumentText():320`, `readFileBytes():357`,
  `resolveTaskFile*` calls `:162,179,180` — exact (the `resolveTaskFile*` glob resolves to
  `resolveTaskFileTtlSeconds` / `resolveTaskFilePerTurnMaxFiles` / `resolveTaskFilePerTurnMaxTotalBytes`).
- `AiUiSettingsResolver.loadSingleton():78`, `resolveTaskFile*` accessors `:97,106,115,128` — exact.
- `AiSettingsChangedEvent` `enum Kind {PARAMETERS, UI_SETTINGS}:28-32`, ctor `(Object source, Kind kind)`,
  `getKind():42` — exact.
- `BuiltInDataTools.getReadableSchema():100`, `canReadEntity():371` — exact (D-09 boundary target).
- `ToolQueryCountBaselineTest.java:151` reads EXACTLY `private static final long
  METAMODEL_TOOL_POLICY_LOOKUP_CEILING = 5L;` — the single allowed edit point is verified byte-exact.
  Assertion sites at `:163-164` (listEntities) and `:179-180` (describeEntity); the four
  `*_STEADY_STATE_CEILING = 1000L` constants at `:192-195` — all confirmed.
- `AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan():229`,
  `findMainJavaRoot():327` — present (plan anchors :228-260 / :322-343 are approximate but resolve).
- `RelatedWriteMetadataMemoTest:45`, `AiUiSettingsResolverReadThroughTest` `@Tag("unit"):53`,
  `RetrievalFilterBuilderDenylistTest`, `PerTurnMediaInjectionTest`,
  `MutationFkBatchLoadQueryCountTest`, `QueryCountingDataSourceConfiguration` — all present.

### Build / dependency facts (VERIFIED)
- `ai-agent/ai-agent/ai-agent.gradle:111` declares `net.ttddyy:datasource-proxy:1.11.0` (the ALLOWED
  proxy harness) with the comment at `:107`.
- NO `caffeine`, `jmh`, or `gatling` token in `ai-agent.gradle` or `ai-agent/build.gradle` — confirms
  the PERF-05 dep-scan premise is satisfiable today.
- `settings.gradle` (rootProject `ai-agent-addon`) includes only `:ai-agent` and `:ai-agent-starter`;
  the `rootProject.children.each` block only sets `buildFileName`, it does NOT nest sub-projects.

### needs-acknowledgement (from source-grounding)
- **No MISSING symbols.** The plans are exceptionally well grounded — every cited existing symbol is
  real and almost every line anchor is exact.
- **CONFIRMED-DRIFT (not from source-grounding, but verified during it):** the Gradle task path
  `:ai-agent:ai-agent:test` used in all 5 plans' `<verify>`/`<verification>` blocks does NOT exist.
  The valid path is `:ai-agent:test`. This is the source-grounded confirmation of Codex's #1 HIGH and
  is the single most important fix before execution. (Listed as a Current HIGH below.)

---

## Consensus Summary

Only one external reviewer (Codex) was available (`gemini` not installed; `claude` self-skipped as the
executing CLI). The "consensus" here is Codex's findings cross-checked against the reviewer's
source-grounding pass.

### Agreed Strengths
- Memoization DIRECTION is correct: app-wide denylist memo inside `LlmExposurePolicy` (avoiding the
  `@Cacheable` self-invocation trap, D-07), per-turn `RunContext` ThreadLocal for user/role-sensitive
  verdicts, denylist reuse in the RAG path, proxy-first discipline for task-file encode.
- Eviction wiring reuses the already-shipped single-publish-site events
  (`LlmExposureChangedEvent` / `AiSettingsChangedEvent`) as consumers only.
- The plans are extremely well source-grounded (verified: 100% of cited existing symbols real, anchors
  exact).
- Wave ordering (01 foundation → 02/03 reuse the denylist memo → 05 closing gate; 04 parallel in
  Wave 1) is mostly correct.

### Agreed Concerns (highest priority)
1. **[HIGH] Wrong Gradle task path in every plan** (`:ai-agent:ai-agent:test` → `:ai-agent:test`).
   Source-confirmed via `settings.gradle`. Would fail every `<verify>` step on first run.
2. **[HIGH] Mutable cached denylist set** (18-01). `hiddenEntityNames()` returns a fresh mutable
   `LinkedHashSet` today; once cached app-wide, the same instance is handed to 15+ external callers of
   `getDenylistedEntityNames()` who could mutate the shared cache. Plan 18-01 does not mandate
   `unmodifiableSet`/`Set.copyOf`.
3. **[HIGH] `RunContext.perTurnCache()` no-active-turn / post-clear semantics** (18-02). Lazy-init that
   always stores a new map (a) makes the "empty after clear()" test self-fulfilling and (b) risks
   leaving a map on a pooled worker thread outside any turn — the exact cross-turn leakage PERF-01
   tries to prevent.
4. **[HIGH] `canReadEntity()` not routed through the per-turn cache** (18-02), despite D-08 naming
   readable-entity verdicts and 15 external `canReadEntity` call sites.
5. **[HIGH] 18-04 test feasibility + premature cache branch.** No Mockito seam exists for the private
   `readFileBytes()` / `extractDocumentText()` / `new TikaDocumentReader(...)` path; and if the cache
   branch fires in Wave 1, `RunContext.perTurnCache()` (Plan 02, Wave 2) is not yet available, so a
   resolver-level `ConcurrentHashMap` would need its own attach/delete/TTL invalidation.

### Divergent Views
- No second external reviewer to diverge. Where the reviewer's source-grounding pass partially
  TEMPERS a Codex HIGH: the 18-04 cache-branch dependency risk is largely mitigated by the plan's
  own proxy-first stance (RESEARCH Open Q2 + Pitfall 5 strongly predict the regression-lock branch,
  which adds no cache). It remains a HIGH only for the residual case where the proxy forces the cache
  branch — the plan should make that branch explicitly Wave-2 / 18-02-dependent rather than Wave 1.
