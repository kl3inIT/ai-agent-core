---
phase: 18-ai-runtime-performance-pass-targeted
plan: 01
subsystem: exposure
tags: [perf, exposure, caching, PERF-02]
requires:
  - "LlmExposureChangedEvent (Phase 10 — dormant event, this is its first consumer)"
  - "AiExposureRuleEntityListener (Phase 10 — sole publisher of LlmExposureChangedEvent)"
provides:
  - "LlmExposurePolicy app-wide denylist memo (ConcurrentHashMap + computeIfAbsent, evicted on LlmExposureChangedEvent)"
  - "Immutable denylist view (Collections.unmodifiableSet) — shared cache cannot be poisoned"
  - "PERF-02 proxy set: lowered SELECT-count ceiling + call-count memo test + event-subscription source-scan invariant"
affects:
  - "Plan 18-02 (PERF-01 per-turn read-through) reuses this memo"
  - "Plan 18-03 (PERF-03 RAG filter) reuses getDenylistedEntityNames()"
tech-stack:
  added: []
  patterns:
    - "In-bean ConcurrentHashMap single-sentinel-key memo (RelatedWriteMetadataResolver twin)"
    - "@EventListener eviction consumer (AiExposureRuleEntityListener shape)"
    - "Cached value wrapped immutable (Collections.unmodifiableSet) before storing"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposureDenylistMemoTest.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/ExposureCacheEventSubscriptionInvariantTest.java"
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java"
decisions:
  - "D-06 branch taken: NO entity-name→MetaClass memo added — the derivation already resolves off the immutable Jmix metamodel (no agentstore SELECT); D-10 scope discipline + the lowered SELECT ceiling are the regression lock."
metrics:
  duration: "~14m"
  completed: "2026-06-09"
  tasks: 2
  files: 4
---

# Phase 18 Plan 01: Exposure Denylist App-Wide Memoization Summary

App-wide memoization of `LlmExposurePolicy`'s exposure denylist (PERF-02): the per-call agentstore
SELECT in `hiddenEntityNames()` is replaced with a single-sentinel-key `ConcurrentHashMap` +
`computeIfAbsent` (caching an immutable `unmodifiableSet`), evicted by a new
`@EventListener(LlmExposureChangedEvent)` `.clear()` — the eviction twin of Phase 17's
`RelatedWriteMetadataResolver`. The `ToolQueryCountBaselineTest` SELECT ceiling drops 5L→4L
(line 151 only); two pure-JUnit proxies ship (call-count + event-subscription invariant).

## What Was Built

### Task 1 — Denylist memo + event eviction (commit `6ab82e5`)

`LlmExposurePolicy.java`:
- Added `private static final String DENYLIST_KEY = "denylist";` single sentinel key.
- Added `private final Map<String, Set<String>> denylistCache = new ConcurrentHashMap<>();`.
- Rewrote private `hiddenEntityNames()` to `denylistCache.computeIfAbsent(DENYLIST_KEY, key -> ...)`;
  the lambda runs the verbatim prior computation (`new LinkedHashSet<>(AiInternalEntityNames.all())`
  then `addAll(ruleRepository.findEnabledExcludedEntityNames())`) and returns
  `Collections.unmodifiableSet(hidden)` — preserving built-ins-first iteration order while making
  the shared cached value immutable (T-18-04: no caller can poison the app-wide cache).
- Added package-private `@EventListener(LlmExposureChangedEvent.class) void onExposureChanged(...)`
  calling `denylistCache.clear()` (D-05; T-18-01 admin edit visible next turn).
- Kept `hiddenEntityNames()` private and all five internal call sites (`getReadableSchema`,
  `canReadEntity`, `canCreate`, `canUpdate`, `getDenylistedEntityNames`) unchanged in arity — the
  memo lives inside the bean so every self-invocation and external caller transparently hits it.
- Updated class/method Javadoc from the old "no cache, per D-14" / "Do NOT cache" wording to the
  PERF-02 memoization + event-eviction + immutable-view contract.
- NO `@Cacheable`/`@CacheEvict` (D-07), NO `publishEvent` (consumer only), NO new component/publisher.

### Task 2 — Lowered ceiling + PERF-02 proxy tests (commit `ad167e3`)

- `ToolQueryCountBaselineTest.java:151`: `METAMODEL_TOOL_POLICY_LOOKUP_CEILING` 5L → 4L. `git diff`
  shows ONLY line 151 changed; assertion bodies at :160-164 and :176-180 byte-for-byte unchanged;
  the four `*_STEADY_STATE_CEILING = 1000L` constants and the slope test untouched.
- `LlmExposureDenylistMemoTest.java` (`@Tag("unit")`, pure JUnit 5 + Mockito): real
  `LlmExposurePolicy` over mocked `CurrentUserSchemaAccess` / `LlmExposureRuleRepository` /
  `AccessManager`. Asserts `verify(times(1)).findEnabledExcludedEntityNames()` after N≥3 gate calls,
  then `times(2)` after `policy.onExposureChanged(new LlmExposureChangedEvent(this))` — exactly one
  refetch. Second test asserts the returned set throws `UnsupportedOperationException` on `add(...)`
  and the cache is neither poisoned nor re-fetched (still `times(1)`) — review HIGH #2 / T-18-04.
- `ExposureCacheEventSubscriptionInvariantTest.java` (`@Tag("unit")`, source-scan): reuses the
  `findMainJavaRoot()` playbook from `AiSettingsChangedEventListenerInvariantTest`; asserts that
  `LlmExposurePolicy.java` contains a `new ConcurrentHashMap<` cache field AND a matching
  `@EventListener(LlmExposureChangedEvent` consumer in the same file (D-07/T-18-02 — no
  never-evicting cache).

## D-06 Branch Taken

**No dedicated entity-name→`MetaClass` `ConcurrentHashMap` was added.** Per the plan objective and
D-10 scope discipline, the entity-name→`MetaClass` derivation already resolves off the immutable
Jmix metamodel (no agentstore SELECT, security-independent), so a redundant memo would violate the
"no new cache without a proven per-call SELECT" rule. The Task-2 ceiling lower (5L→4L) is itself the
regression lock for the metadata path. The branch decision is recorded; only the denylist memo was
added.

## Verification Results (honest)

| Check | Result |
|-------|--------|
| `:ai-agent:compileJava` | **PASS** (BUILD SUCCESSFUL) |
| `LlmExposureDenylistMemoTest` | **PASS** (both methods green) |
| `ExposureCacheEventSubscriptionInvariantTest` | **PASS** (green) |
| `git diff` ToolQueryCountBaselineTest | **PASS** — only line 151 changed (1 insertion, 1 deletion) |
| `ToolQueryCountBaselineTest` (`@SpringBootTest`) | **CONTEXT-LOAD FAILURE — pre-existing regression, NOT a ceiling/assertion failure** (see below) |

### ToolQueryCountBaselineTest — context-load failure (pre-existing, environmental)

All 7 methods in `ToolQueryCountBaselineTest` failed with `IllegalStateException` /
`Failed to load ApplicationContext`. The root cause extracted from the JUnit report is:

```
Caused by: java.lang.IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent
  ... while instantiating io.jmix.security.impl.role.provider.AnnotatedResourceRoleProvider
  ... → sec_ResourceRoleRepository → knowledgeDocumentUploadService → ingesterManager
```

This is the **pre-existing Phase 11/13 `AiAuditEvent` metaclass Spring-context boot regression**
documented in `.planning/milestones/v1.1.0-phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`
and referenced by every v1.2 plan summary (13.1-06, 13.1-07, 14-01, 14-02, 16-01..06, 15-05). It is
**NOT introduced by this plan**: the failure occurs during `AnnotatedResourceRoleProvider`/
`AiAuditEvent` metaclass resolution, long before `LlmExposurePolicy` is even instantiated, and the
`LlmExposurePolicy` change does not touch the security/role/audit boot graph. The ceiling-lower edit
itself is source-correct and the diff is line-151-only.

Per CONTEXT D-11 the datasource-proxy harness "boots and passes in isolation"; in this full
module-test run (with the test-source `enhanceJmixTest` graph active) it hits the known regression.
Honest disposition: the SELECT-count assertion at the lowered `4L` ceiling **could not be executed
here** because the context will not boot; the change is committed source-correct and should be
re-validated in the isolated-boot environment described by D-11 (or once the deferred Phase 11/13
boot regression is fixed). This is reported rather than claimed as a pass, per the plan's
`<critical_constraints>` directive.

## Deviations from Plan

### Auto-fixed Issues

None — Rules 1–3 not triggered. The denylist memo and ceiling-lower applied exactly as written.

### Deferred Issues

**1. [Environmental] `ToolQueryCountBaselineTest` `@SpringBootTest` cannot boot in the full
module-test run.**
- **Found during:** Task 2 verification.
- **Issue:** Pre-existing Phase 11/13 `AiAuditEvent` metaclass boot regression
  (`AnnotatedResourceRoleProvider` instantiation fails) blocks the Spring context — affects all 7
  baseline methods identically, independent of this plan's change.
- **Disposition:** NOT fixed (out of scope — pre-existing, affects the security/audit boot graph,
  not `LlmExposurePolicy`). Lowered-ceiling assertion to be re-validated under D-11 isolated boot.
- **Commit:** n/a (no code change — environmental).

## Threat Flags

None — no new network endpoint, auth path, file-access pattern, or schema change introduced. This
plan is memoization over existing classes only (zero packages installed; T-18-SC satisfied).

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposureDenylistMemoTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/ExposureCacheEventSubscriptionInvariantTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java`
- FOUND commit: `6ab82e5` (Task 1)
- FOUND commit: `ad167e3` (Task 2)
