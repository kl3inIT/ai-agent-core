# Codebase Concerns

**Analysis Date:** 2026-06-04

> **Scope note:** Full repo including `.planning/` artifacts. This is the Jmix 2.8 /
> Spring Boot 3 / Java 21 "Jmix AI Agent" addon. The audit was commissioned to answer
> a felt sense that the codebase is "hard to control" on four axes: (1) opaque
> architecture, (2) AI-generated drift, (3) genuine bugs / maintainability, (4) build/test
> instability. The verdict at the bottom is **disciplined-but-complex with specific,
> already-scoped hot-spots** — not "out of control." Evidence below.

---

## Severity Legend

- **HIGH** — Actively blocks work, masks regressions, or is a real correctness/security risk.
- **MEDIUM** — Real friction or latent risk; bounded and scoped, not urgent.
- **LOW** — Hygiene / cosmetic; cheap to fix, no functional impact.
- **NOT-A-CONCERN** — Investigated and found to be either resolved or inherent domain complexity. Documented so the felt concern can be retired.

---

## Tech Debt

### Boot-test regression forces a parallel "pure-JUnit" testing style — HIGH

**Issue:** Every `@SpringBootTest` (and therefore every `@UiTest`, which extends it) in the
`:ai-agent:ai-agent` functional module **cannot boot its Spring context**. The symptom has two
documented faces:
- `IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent`
  during `AnnotatedRoleBuilderImpl.createResourceRole(...)` — a metamodel-session-not-yet-sealed
  race in the role-builder policy extractor.
- Downstream `agentstoreEntityManagerFactory` / `atmosphere-runtime`
  (`AtmosphereRequestIntrospector`) `IndexOutOfBoundsException` on `@UiTest` boots.

**Files / evidence:**
- Authoritative writeup: `.planning/milestones/v1.1.0-phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`
- Cross-referenced repeatedly: `.planning/milestones/v1.1.0-phases/13.1-.../13.1-06-SUMMARY.md`,
  `13.1-07-SUMMARY.md`, `13.1-VERIFICATION.md`, and Phase 16 plan summaries
  (`.planning/phases/16-admin-settings-model-picker-config-knob-migration/16-03-SUMMARY.md`,
  `16-04-SUMMARY.md`).
- Affected pre-existing tests named in the deferred doc: `BuiltInMutationToolsAuditArgumentsTest`,
  `BuiltInMutationToolsIdempotencyReplayTest`, the Phase 13 bulk-save suite, and "any Plan
  11-10..11-13 mutation integration test that boots Spring with `MutationToolTestUsersConfiguration`."

**Blast radius:** `93 of 213` test files in `ai-agent/ai-agent/src/test` reference
`@SpringBootTest`/`@UiTest`. Many of those that should be true integration/UI tests have been
**rewritten as pure-JUnit reconstructions** — they parse `module.properties` / `default-params.yaml`
by hand, construct the `*Properties` records manually, and invoke `@PostConstruct`/`validate()`
directly instead of letting Spring wire them (see Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01,
16-02, 16-03, 16-04 which all hit the same wall and worked around it identically). The
separate `:jmix-app` integration tests additionally require a live PostgreSQL `agentstore`
datasource and fail in environments without one (`.planning/phases/15-.../deferred-items.md`).

**Impact:**
- **This is the single largest "feels out of control" driver.** Contributors see `@SpringBootTest`
  annotations everywhere but learn the real coverage runs through hand-rolled JUnit shims — the
  test suite's *shape* misrepresents its *runtime*.
- True context-wired behavior (role-builder ordering, entity-listener twin-publish, UI mount) is
  asserted by **source-string / XML-parse proxies**, not live boot. Regressions in wiring order can
  slip through.
- A real Jmix metamodel-sealing bug is masked as "environment flakiness."

**Fix approach:** Dedicated hardening plan (currently *unscheduled* — not in any v1.2 phase). Either
(a) make `AnnotatedRoleBuilderImpl` defer policy extraction until the metamodel session is sealed,
or (b) add explicit `@DependsOn` / `@JmixModule(dependsOn=...)` ordering on the test config against
the agentstore module, then port the pure-JUnit shims back to `@SpringBootTest`/`@UiTest` by
swapping the hand-built `boot()` helper for `@Autowired`. The summaries repeatedly note the test
bodies are written to "port unchanged" once the boot regression clears — so the fix is bounded, but
it has been deferred across at least 6 plans without an owner.

### Carried bookkeeping debt (DEBT-01..03, EXP-FUT-01) — MEDIUM

**Issue:** `.planning/REQUIREMENTS.md` ("Carried debt (later hardening pass)") and `ROADMAP.md`
explicitly defer:
- **DEBT-01:** Phase 10 `10-VERIFICATION.md` still stuck at `human_needed` (stale status; the
  substantive items were fixed in code per the milestone audit).
- **DEBT-02:** Missing Nyquist `*-VALIDATION.md` backfill for phases 9/10/11/12/13/13.1.
- **DEBT-03:** Clean-consumer smoke (PKG-05 / TEST-07) — Postgres/pgvector Testcontainers smoke or
  a starter-stub `VectorStore` boot mode, a v1.0.0 carryover.
- **EXP-FUT-01:** Attribute-path-level exposure rules (`attributePath` on `AiExposureRule`).

**Files:** `.planning/REQUIREMENTS.md` lines 79-84; `ROADMAP.md` lines 222-223;
`.planning/milestones/v1.1.0-MILESTONE-AUDIT.md`.

**Impact:** Mostly *governance/traceability* debt, not code debt. The milestone audit itself rated
this `tech_debt` "for bookkeeping" while integration (8/8) and E2E (5/5) passed. Low correctness
risk; real cost is that the verification trail lies about its own completeness (Phase 10 reads as
unverified when it is effectively verified).

**Fix approach:** A single "hardening pass" sprint: re-run `/gsd-verify-work 10`, backfill the six
`*-VALIDATION.md` docs, and decide whether DEBT-03's clean-consumer smoke is worth a Testcontainers
dependency. None is blocking.

---

## Known Bugs

### `bulk-create-allowlist-collision` — RESOLVED (regression-guarded) — LOW

**Symptoms:** A request to create ≥2 records grouped correctly but the model reported
`propose_bulk_action_choices` "is not in its current toolset" and fell back to per-row creation,
*leaking the internal tool name to the user*.

**Files:** `.planning/debug/bulk-create-allowlist-collision.md`;
fix in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` (orchestration-tool
allowlist exemption) + `guard/AgentSystemPromptRules.java` (no-leak reinforcement) +
regression test in `extraction/AgentToolCallbacksIntentGatingTest.java`.

**Root cause:** Two workstreams collided — Workstream A added `propose_bulk_action_choices`;
Workstream B then made `applyAllowlist` intersect the per-turn toolset with a seeded `enabledTools`
allowlist that **predated** the new tool, intersecting it out. Classic ordering/drift between two AI
work passes.

**Status:** Fixed; the buggy test that *codified* the wrong behavior
(`enabledToolsAllowlistRestrictsPlanningTurnToIntersection`) was corrected and a falsification test
added. **This session lives in `.planning/debug/` (not `resolved/`) with `status: fixing`** — the
write-up text shows the fix was applied and verified, but the file was not moved to
`resolved/`. Hygiene-only.

### `bulk-create-confirm-throws` — RESOLVED (awaiting human verify) — LOW

**Symptoms:** Model passed an *array* of rows into the single-record tool's `values:
Map<String,Object>` param; Spring AI 1.1.4 `MethodToolCallback.buildTypedArgument` threw
`MismatchedInputException` (`START_ARRAY` → `Map`) *during argument binding, before the method body*,
surfacing the generic `chatView.error.generic` ("Đã xảy ra lỗi"); nothing was created.

**Files:** `.planning/debug/bulk-create-confirm-throws.md`; fixes (verified, full
`:ai-agent:test` = 846 tests / 0 failures):
- `ai-agent/ai-agent/src/main/java/com/vn/agent/action/SingleRecordValues.java` (new; array-tolerant
  `@JsonDeserialize` that does NOT alter the advertised JSON Schema)
- `action/ActionProposalTool.java`, `action/ActionProposalResult.java`,
  `guard/AgentSystemPromptRulesComposer.java`

**Root cause:** Legacy single-record prompt guidance competed with the new bulk path; the model
defaulted to the familiar tool and arrayed the wrong param. Spring AI's
`ToolExecutionExceptionProcessor` *cannot* rescue arg-binding failures (confirmed against
spring-ai #3924/#4987), so the only robust boundary was making deserialization succeed and detecting
the bad shape inside the method.

**Status:** `awaiting_human_verify`. **Open theoretical sibling vulnerability (documented, NOT
fixed):** `create_record` / `update_record` `attributes` (`Map<String,Object>`) in
`BuiltInMutationTools` have the *same* array-misroute shape. They are currently protected only by
prompt steering (single-tool-named on the CREATE_NOW turn). If a future UAT shows the model arraying
`attributes`, apply the same `SingleRecordValues` pattern there
(`bulk-create-confirm-throws.md` "Follow-up note").

### Generic error catch-all masks tool-binding failures — MEDIUM

**Issue:** `DefaultChatServiceImpl` (error mapping ~lines 838-843 per the debug log) maps *any*
uncaught `RuntimeException` from the chat client to a single `chatView.error.generic` notification.
Both bugs above surfaced to the user as the same opaque "Đã xảy ra lỗi."

**Impact:** Real tool-execution / arg-binding failures are indistinguishable from generic chat
errors in the UI, and (until audited) hard to triage. This is the reason a deserialization bug
looked like a chat crash.

**Fix approach:** Keep the user-facing message generic (good UX), but ensure the audit row carries
the discriminating class/cause (it does for the bulk cases — `event_name` + `error_class` are
recorded). Consider a structured corrective-result path for the remaining `Map`-typed mutation
params before they bite (see sibling vulnerability above).

---

## Security Considerations

### Mutation gating & LLM exposure — STRONG (low concern) — NOT-A-CONCERN

**Evidence of strength:**
- The fail-closed mutation sequence is now consolidated in one canonical
  `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationGateChain.java` (831 lines,
  ordered named gates, **no `@Transactional` on the chain** so every gate throws *before* the save
  crosses the transaction boundary — fail-closed by construction). `BuiltInMutationTools.java` is now
  thin adapters (`mutationGateChain.execute(new MutationRequest.Create(...))`).
- Row-level security is preserved through the perf work: FK prefetch in
  `mutation/MutationAttributeBinder.java` uses constrained `dataManager.load(...).ids(...)` per
  target class, **never `UnconstrainedDataManager`, never raw JPQL** (lines 163-185) — exactly the
  project rule.
- Default config ships mutation **OFF** (`@ConditionalOnProperty ai-agent.tools.mutation.enabled`);
  the admin `enabledTools` allowlist is the only per-tool lever; Phase 16 SEC-08 source-scan tests
  (`SecretRedactionInvariantsTest.noSecretBoundEditable` / `noConditionalOnPropertyToggleBoundEditable`)
  **fail the build** if any `*.api-key` / `*.password` / `*.secret` / `*.token` is bound editable in a
  view, or a boot toggle is presented as runtime-editable.
- Secrets stay env/`application.properties`-backed; the admin UI shows "configured: yes/no" only
  (`REQUIREMENTS.md` Out-of-Scope row).

**Fragile edges (the only real notes here):**
- **Tool-name leakage** is a recurring failure mode, not a one-off: the `bulk-create-allowlist-collision`
  bug leaked `propose_bulk_action_choices` while *explaining its absence*, a path the existing
  no-leak rules (which target tool *narration*) didn't cover. Mitigated by an added rule, but this is
  a class of prompt-contract drift worth a dedicated leak-scan that also covers
  "unavailable/absent tool" phrasings.
- **Allowlist drift:** any future new orchestration tool risks the same predates-the-seeded-allowlist
  collision unless added to the exemption set in `AgentToolCallbacks.applyAllowlist`.

**Recommendation:** No structural change. Keep the SEC-08 source-scans as the security backstop;
treat tool-name leakage as a first-class prompt-contract invariant with its own scanner.

---

## Performance Bottlenecks

### Phase 18 perf targets — KNOWN & SCOPED (not yet implemented) — MEDIUM

**Problem:** Several per-turn hotspots are documented and slated for Phase 18 (PERF-01..05,
**Pending**):
- `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions /
  `LlmExposurePolicy` resolution recomputed per tool-call instead of once per `RunContext` (turn).
- Pure-metadata derivations (entity name → `MetaClass`) and the exposure denylist
  (`getDenylistedEntityNames()`) not memoized app-wide (eviction hook on `LlmExposureChangedEvent` /
  the Phase 16 `AiParameters` change event already exists).
- RAG `Filter.Expression` rebuilt repeatedly per retrieval rather than once.
- Task-file `Media` re-encoded per injection instead of cached per `(conversationId, taskFileId)`.

**Files:** `ROADMAP.md` Phase 18 (lines 167-181); `REQUIREMENTS.md` traceability (PERF-01..05 →
Phase 18, Pending).

**Impact:** Bounded, JVM-lifetime-stable per-turn overhead — *not* a correctness risk and explicitly
judged not to need a benchmark harness (Out-of-Scope row: "perf hotspots are bounded… the existing
`ConcurrentMapCacheManager` + datasource-proxy SELECT-count assertions suffice"). Each fix ships with
a checkable proxy. Hard ordering constraint already satisfied: **Phase 17 (done) precedes Phase 18.**

**Improvement path:** Execute Phase 18 as planned. The hardest groundwork (consolidated
`MutationGateChain` + shared FK batch-load) is already landed in Phase 17, so the perf pass refactors
a single chain, not five duplicated sequences.

### MUT-16 / MUT-17 N+1 hot-spots — RESOLVED in Phase 17 — NOT-A-CONCERN

The prompt flagged "per-reference FK loads (N+1) in `MutationAttributeBinder`" and an "un-memoized
metadata walk in `RelatedWriteMetadataResolver`." **Both are fixed** (Phase 17 completed 2026-05-31):
- `MutationAttributeBinder.prefetchReferences` (lines 140-185) batch-loads to-one FKs with one
  constrained `.ids()` per distinct target class (two-pass bind), guarded by
  `MutationFkBatchLoadQueryCountTest` ("1 query not N").
- `RelatedWriteMetadataResolver` (lines 136-189) memoizes `(parentMetaClass, relationshipName)` via
  `ConcurrentHashMap.computeIfAbsent` over a package-private `computeSupported` seam (no eviction —
  immutable metamodel), guarded by `RelatedWriteMetadataMemoTest` (walk-once).

These appeared in the spawn prompt as "Phase 18 scope," but the roadmap and source show them
delivered in Phase 17. **The felt concern here is stale.**

---

## Fragile Areas

### Spring AI 1.1.4 tool argument binding — MEDIUM

**Files:** `ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java`,
`SingleRecordValues.java`, and the `Map<String,Object>` params in
`tools/mutation/BuiltInMutationTools.java`.

**Why fragile:** Spring AI builds typed arguments via Jackson *before* the tool method runs, and
arg-binding exceptions escape `ToolExecutionExceptionProcessor` entirely (verified against spring-ai
#3924/#4987). Any `Map`-typed `@ToolParam` is one model misfire away from an uncatchable binding
crash. The codebase has the **correct pattern** (`SingleRecordValues` array-tolerant deserializer
that preserves the JSON Schema) but has only applied it to `propose_action_choices.values` so far.

**Safe modification:** When adding any object/map tool param, default to a tolerant deserializer +
in-method shape detection returning a structured corrective result; never raw `Object @ToolParam`
(project rule `feedback_no_object_toolparam.md`). A schema-stability test must assert the advertised
schema is unchanged.

**Test coverage:** Good for the fixed path (`ActionProposalToolTest` array→multiRecord cases); the
sibling mutation `attributes` params are *uncovered* for this specific misroute.

### Prompt-contract surface — MEDIUM

**Files:** `guard/AgentSystemPromptRules.java`, `guard/AgentSystemPromptRulesComposer.java`,
`tools/AgentToolCallbacks.java`.

**Why fragile:** Behavior correctness depends heavily on prompt wording (tool-selection steering,
no-leak rules, count-the-records-first ordering). Two of the recent bugs were *prompt-contract drift*,
not logic errors. Changes here are not type-checked; only source-substring tests
(`ToolNavigationLeakScannerTest`, `AgentSystemPromptRulesComposerIntentTest`) guard them.

**Safe modification:** Treat every prompt-rule edit as a behavior change: add/extend a substring or
leak-scan test in the same commit. The existing tests are the only guardrail.

---

## Scaling Limits

**Not a current concern.** This is an addon, not a service. The documented limits are bounded:
per-turn memoization opportunities (Phase 18), task-file `Media` LRU budget cap + 24h conversation
TTL (already shipped, Phase 13.1), and mutation idempotency stored in `AiMutationIntent` with a
cleanup job (`MutationIntentCleanupJob.java`). No unbounded growth paths were found.

---

## Dependencies at Risk

### Spring AI 1.1.4 arg-binding behavior — MEDIUM (version-coupled)

The tool-binding fragility above is **specific to Spring AI 1.x** behavior. A Spring AI upgrade
could change `MethodToolCallback.buildTypedArgument` / `ToolExecutionExceptionProcessor` semantics
and either fix or alter the `SingleRecordValues` workaround. Pin the version deliberately and
re-verify the array-tolerant deserialize tests on any Spring AI bump.

### Vaadin / atmosphere-runtime — see Boot regression (HIGH)

The `atmosphere-runtime-3.0.5` `IndexOutOfBoundsException` is part of the boot-test regression's
downstream face. Not a runtime production risk (the app boots fine on `:8088`), purely a test-context
boot issue.

---

## Workspace Hygiene — LOW

**Issue:** The repo working tree carries substantial **untracked churn that is not git-ignored**:
- Root-level scratch YAML: `bulk-new1.yml`, `bulk-new2.yml`, `bulk-snapshot.yml`, `bulk2-snapshot.yml`,
  `bulk3-snapshot.yml`, `bulkv2-1.yml`, `bulkv2-2.yml`, `err-snapshot.yml`, `input-area.yml`
  (debug/snapshot scratch from the bulk-create sessions).
- Eight boot logs: `jmix-app/.bootrun.{out,err}.log` through `.bootrun4.*` (up to ~114 KB each).
- `docker/nexus/`, `ai-agent/.planning/` (a nested stray planning dir), and various modified files
  (`AgentSystemPromptRules.java`, `AgentToolCallbacks.java`, the gating test, `gradle.properties`).

**Files:** `.gitignore` (top section) does **not** match `bulk*.yml`, `*-snapshot.yml`, or
`.bootrun*.log`. `git status` shows 27 uncommitted entries.

**Impact:** Cosmetic, but it is part of the "hard to control" *feeling* — a noisy working tree makes
it hard to see real changes. The `gradle.properties` version bump (`1.0.5` → `1.1.1-SNAPSHOT`) is
real and intentional but uncommitted alongside the scratch.

**Fix approach:** Add `*.bootrun*.log`, `*-snapshot.yml`, and a scratch-dir convention to
`.gitignore`; delete or relocate the root scratch YAML and the nested `ai-agent/.planning/`; commit
the intentional source + version changes separately from scratch.

---

## Test Coverage Gaps

### Mutation integration coverage runs as source-scans, not live boot — HIGH

**What's not tested (at runtime):** The mutation gate chain, role-builder ordering, entity-listener
twin-publish, and UI mount behavior are asserted via XML-parse / source-string proxies because the
boot regression blocks `@SpringBootTest`/`@UiTest`. The *logic* is well-pinned in test source, but it
**does not execute against a live Spring context** in CI for this module.

**Files:** the `~93` `@SpringBootTest`-referencing test files in `ai-agent/ai-agent/src/test`, plus
the pure-JUnit shims described under "Boot-test regression."

**Risk:** Wiring-order regressions (the exact class of bug behind the boot regression itself, and
behind the allowlist collision) can pass the proxy tests and only surface in live UAT on `:8088`.
Indeed, **both recent bugs were caught by live UAT, not by the suite.**

**Priority:** High — fixing the boot regression (above) directly closes this gap; the test bodies are
written to port back to live boot unchanged.

### `:jmix-app` integration tests need a live PostgreSQL — MEDIUM

`./gradlew :jmix-app:test` fails without a provisioned PostgreSQL `agentstore` datasource
(`.planning/phases/15-.../deferred-items.md`). Not a code defect, but it means full integration
verification is environment-gated and easy to skip.

---

## Balanced Verdict

**The codebase is disciplined-but-complex, with a small number of specific, already-identified
fixable hot-spots. It is NOT fundamentally out of control.**

Evidence for **disciplined**:
- **Zero `TODO` / `FIXME` / `HACK` / `XXX` markers** across the entire Java source tree — extremely
  rare and a strong discipline signal.
- A rigorous, traceable planning trail: every requirement maps to a phase
  (`REQUIREMENTS.md`: 29/29 mapped, 0 orphans), milestone audits exist, and out-of-scope decisions are
  recorded with rationale.
- Security is *structural*, not aspirational: fail-closed `MutationGateChain` (no `@Transactional`),
  constrained-`DataManager`-only FK loads, build-failing SEC-08 secret-redaction source-scans,
  mutation OFF by default.
- The two recent bugs were diagnosed to *root cause* with falsification tests and regression
  guards — the opposite of flailing.
- The flagged "N+1 / un-memoized metadata" and "duplicated gate sequence across 5 tools" concerns are
  **already resolved** (Phase 17, 2026-05-31). The prompt's framing of them as open is stale.

The genuine pain — the source of the "hard to control" feeling — is concentrated and explainable:

1. **The boot-test regression (HIGH)** is the root of most of the discomfort. It forces a parallel
   pure-JUnit testing style, makes the suite's *shape* misrepresent its *runtime coverage*, masks a
   real Jmix metamodel-sealing bug, and pushes real verification into live UAT. **This is the one
   thing worth scheduling immediately** — it is bounded (the shims are written to port back unchanged)
   but has been deferred across 6+ plans with no owner.

2. **Prompt-contract & Spring-AI arg-binding fragility (MEDIUM)** is *inherent LLM-integration
   complexity*, not bad code. The codebase already has the correct mitigation pattern
   (`SingleRecordValues`, leak-scans); the residual risk is that the pattern hasn't been applied to
   every `Map`-typed tool param yet, and prompt edits aren't type-checked.

3. **Bookkeeping debt + workspace hygiene (LOW/MEDIUM)** is real but trivial to clear and contributes
   disproportionately to the *feeling* of disorder (stale `human_needed` status, noisy untracked
   working tree).

**Recommended order of attack:** (1) own and fix the boot regression, then port the pure-JUnit shims
back; (2) clean the working tree + `.gitignore` and clear the stale verification statuses; (3)
apply the `SingleRecordValues` pattern to the remaining mutation `Map` params before they bite; (4)
execute Phase 18 as planned. None of these is architectural surgery.

---

*Concerns audit: 2026-06-04*
