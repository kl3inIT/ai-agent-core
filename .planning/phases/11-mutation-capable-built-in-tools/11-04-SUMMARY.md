---
phase: 11-mutation-capable-built-in-tools
plan: 04
subsystem: tools-mutation
tags: [refactor, shared-component, mut-09, llm-exposure, opacity]

# Dependency graph
requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: MutationGuard SPI + MutationIntent record + default no-op bean (Plan 11-03)
provides:
  - "Public ToolEntityResolver @Component (com.vn.agent.tools.ToolEntityResolver) — shared MUT-09 surface for BuiltInDataTools (READ) and BuiltInMutationTools (WRITE)"
  - "Operation-specific exposure gates LlmExposurePolicy.canCreate(MetaClass) / canUpdate(MetaClass); canModify retained as backward-compatible alias"
  - "resolveCreatableEntityOrThrow / resolveUpdatableEntityOrThrow with visible-but-denied → access_denied separation"
affects: [11-05, 11-06, 11-07, 11-07A, 11-07B, 11-07C, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Shared resolver @Component injected by both READ and WRITE tool classes — single MUT-09 surface so the Phase 10 R4 uniform-opacity contract is enforced uniformly"
    - "Operation-specific create/update CRUD-gate methods on LlmExposurePolicy (avoids blocking create-only users with an update-oriented check)"
    - "Backward-compatible alias canModify → canUpdate so Phase 10 callers do not break while Phase 11 mutation tools migrate to the operation-specific methods"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolEntityResolver.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java

key-decisions:
  - "ToolEntityResolver.resolveReadableEntityOrThrow body is byte-equivalent (modulo formatting) to the previous BuiltInDataTools private helper — UNKNOWN_ENTITY_HINTS strings preserved verbatim including the em dash U+2014 on hint #3 (TEST-08 invariant)"
  - "Visible-but-denied surfaces as access_denied (not unknown_entity) on the create/update paths — the LLM already saw the entity through list_entities, so distinguishing the two is intentional and not an opacity leak"
  - "canModify retained as a one-line alias delegating to canUpdate; no Phase 10 call sites had to change in this plan"
  - "Removed the Metadata / MetadataTools / FilterLiteralValueConverter constructor parameters from BuiltInDataTools because all three were used only inside the migrated helpers; kept the type imports because surviving class-level + describeEntity Javadoc still {@link} them"

patterns-established:
  - "Tool-class entity resolution flows through a single @Component; mutation tools (Wave 5+) inject ToolEntityResolver and call resolveCreatableEntityOrThrow / resolveUpdatableEntityOrThrow rather than rebuilding the unknown_entity opacity contract"
  - "Pre-existing Mockito unit tests that hand-instantiated BuiltInDataTools migrate by constructing a real ToolEntityResolver wired to the same mocks (Metadata, MetadataTools, LlmExposurePolicy, FilterLiteralValueConverter) instead of mocking the helper itself — preserves behavioral coverage without test rewrites"

requirements-completed:
  - MUT-09

# Metrics
duration: 11min
completed: 2026-04-28
---

# Phase 11 Plan 04: ToolEntityResolver shared @Component + BuiltInDataTools refactor

**Wave 4 lands the MUT-09 shared resolver. `LlmExposurePolicy` gains operation-specific `canCreate` / `canUpdate` gates (HIGH review feedback addressed) plus a backward-compatible `canModify` alias. `ToolEntityResolver` ships as a public `@Component` with `resolveReadableEntityOrThrow`, `resolveCreatableEntityOrThrow`, `resolveUpdatableEntityOrThrow`, `parseEntityId`, and `llmReadableAttributes`. `BuiltInDataTools` delegates to the resolver while keeping its public `@Tool` surface byte-identical — the ASM `BuiltInDataToolsReadOnlyTest` stays green.**

## Performance

- **Duration:** ~11 min (two-task plan; warm Gradle daemon)
- **Started:** 2026-04-28T20:25:24Z
- **Completed:** 2026-04-28T20:36Z
- **Tasks:** 2 / 2
- **Files modified:** 4 (1 created, 3 modified — including test fix-up)

## Accomplishments

- `LlmExposurePolicy.canCreate(MetaClass)` and `canUpdate(MetaClass)` ship as operation-specific CRUD gates: each constructs a `CrudEntityContext`, applies `AccessManager.applyRegisteredConstraints`, and AND-combines with `!hiddenEntityNames().contains(mc.getName())`. `canModify` is now a one-line alias that delegates to `canUpdate` so Phase 10 call sites compile unchanged.
- `ToolEntityResolver` (`com.vn.agent.tools.ToolEntityResolver`) ships as a public `@Component` injecting `Metadata`, `MetadataTools`, `LlmExposurePolicy`, and `FilterLiteralValueConverter`. Five public methods:
  - `resolveReadableEntityOrThrow(String)` — Phase 10 R4 uniform-opacity contract: null/blank/unknown/hidden all surface as `unknown_entity` with `UnknownEntityHints.AS_LIST` (em dash U+2014 preserved).
  - `resolveCreatableEntityOrThrow(String)` — read-resolved + create-side gate; `access_denied` for visible-but-denied.
  - `resolveUpdatableEntityOrThrow(String)` — read-resolved + update-side gate; `access_denied` for visible-but-denied.
  - `parseEntityId(String, MetaClass)` — delegates to `FilterLiteralValueConverter.convertValue` for typed PK conversion.
  - `llmReadableAttributes(MetaClass, Set<String>)` — preserves the relationship-target read-filter contract from `BuiltInDataTools` (caller-provided iteration order, drop only relationship attributes whose target entity is denied).
- `BuiltInDataTools` now constructor-injects `ToolEntityResolver` and delegates all five `resolveReadableEntityOrThrow` call sites, both `parseEntityId` call sites, and the single `llmReadableAttributes` call site. The three private helpers are deleted. The constructor lost `Metadata`, `MetadataTools`, and `FilterLiteralValueConverter` parameters because they were used only inside the migrated helpers; class-level + `describeEntity` Javadoc references stay so the type imports remain.
- The class-level Javadoc paragraph on Phase 10 Fix R4 was updated to point at `ToolEntityResolver#resolveReadableEntityOrThrow` via `{@link}` rather than the deleted private helper.
- `UnknownEntityHints` import + the public `@Tool` method bodies (other than the resolver-call substitutions) are byte-identical; the ASM `BuiltInDataToolsReadOnlyTest` confirms the read-only invariants (no `DataManager.save / saveContext / remove`, no `EntityManager.*`, no LLM-parameter-derived JPQL).
- `UnknownEntityRetryHintTest` (the pre-existing pure-Mockito unit test) was updated to construct a real `ToolEntityResolver` wired to the existing mocks (`metadata`, `metadataTools`, `schemaAccess`, `literalConverter`) and pass it to the new `BuiltInDataTools` constructor. All six tests still pass — R4 unknown-entity uniformity, blank-name shape, denied-entity-as-unknown, and the three FetchPlanResolver wiring tests. No assertion logic changed.

## Task Commits

1. **Task 1: Add canCreate/canUpdate gates and extract ToolEntityResolver** — `43e68ac` (feat)
2. **Task 2: Refactor BuiltInDataTools to delegate to ToolEntityResolver** — `781564b` (refactor)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolEntityResolver.java` — public `@Component`; the MUT-09 shared resolver. 152 lines including Javadoc.

### Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` — split `canModify` into `canCreate` + `canUpdate`; `canModify` retained as alias. Net +20 lines (Javadoc + two new methods).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` — constructor + field changes, helper deletions, and call-site delegations. Net -73 lines.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java` — wire a real `ToolEntityResolver` into the existing mock graph and call the new `BuiltInDataTools` constructor. Net +6 lines.

## Decisions Made

- **`canModify` kept as alias, not deleted** — Phase 10 callers (no in-tree consumer right now per Plan 11-03 RESEARCH, but the SPI and exposure-policy tests reference it) keep compiling. Phase 11 mutation tools (Wave 5+) will call `canCreate` / `canUpdate` directly.
- **`access_denied` for visible-but-denied on the write side** — the LLM has already seen this entity name through `list_entities` (because `canReadEntity` returned `true`). Returning `unknown_entity` here would lie to the LLM and waste a roundtrip; returning `access_denied` with `expected: ["do not retry; surface to user"]` lets the LLM stop retrying and surface the denial to the user.
- **Drop `Metadata` / `MetadataTools` / `FilterLiteralValueConverter` constructor parameters from `BuiltInDataTools`** — they are now used only inside the migrated helpers (now living in `ToolEntityResolver`). Type imports stay because class-level Javadoc and `describeEntity` Javadoc still `{@link}` them. This keeps the diff honest: callers no longer have to inject dependencies they do not consume.
- **Test fix-up via real `ToolEntityResolver`, not a mocked one** — the test's existing mock graph (`metadata`, `metadataTools`, `schemaAccess`, `literalConverter`) is exactly what `ToolEntityResolver` needs. Constructing a real resolver wired to the same mocks preserves the existing behavioral coverage (R4 opacity assertions, FetchPlanResolver wiring) without rewriting the assertions to mock-resolver expectations. This is the pattern future Mockito unit tests should follow when adapting to delegated helpers.
- **`canCreate` / `canUpdate` apply the denylist same as `canReadEntity`** — `&& !hiddenEntityNames().contains(mc.getName())`. A denylisted entity is never creatable / updatable even if the user has CRUD permission. Keeps the denylist as the absolute upper bound on LLM exposure (consistent with `canReadEntity`).

## Deviations from Plan

**1. [Rule 3 - Blocking] Updated `UnknownEntityRetryHintTest` after constructor signature change**
- **Found during:** Task 2 verification (`./gradlew :ai-agent:test --tests "...BuiltInDataToolsReadOnlyTest"` — the test compile failed first)
- **Issue:** `UnknownEntityRetryHintTest` hand-instantiates `BuiltInDataTools` with the previous 11-arg constructor. The plan's Task 2 changed the constructor (drop `Metadata`/`MetadataTools`/`FilterLiteralValueConverter`, add `ToolEntityResolver`), so test compile failed with "constructor BuiltInDataTools cannot be applied to given types".
- **Fix:** Construct a real `ToolEntityResolver` wired to the same mocks (`metadata`, `metadataTools`, `schemaAccess`, `literalConverter`) and pass it to the new constructor. No assertion logic changed; the pre-existing R4 opacity tests still cover the same code paths because `ToolEntityResolver` invokes the same `metadata.getClass(...)` / `schemaAccess.canReadEntity(...)` / `literalConverter.convertValue(...)` mocks the test already sets up.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java`
- **Commit:** included in `781564b` (Task 2)

The plan's read_first list pointed at `BuiltInDataToolsReadOnlyTest` only; the `UnknownEntityRetryHintTest` constructor coupling was a foreseeable blocker not called out explicitly. Fix is mechanical (construct a real resolver wired to existing mocks); no behavioral change.

## Issues Encountered

- **Initial broader-scope test run reported 5 ApplicationContext failures in `PromptInjectionHarnessTest`** — confirmed flaky by re-running the same test class in isolation (build successful). The earlier failures came from a stale build cache after the test compile error left a partial classpath; once `:ai-agent:compileTestJava` succeeded, the cached failure threshold cleared on the next run. Not a real regression. The unrelated `AnnotatedResourceRoleProvider` constructor failure in the inner stack trace is the same Jmix-2.8.1 + role-annotation startup quirk visible elsewhere in this codebase and predates this plan.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** the JetBrains MCP server is not registered in this execution environment. The following Java files should be opened in IntelliJ for `get_file_problems("path", onlyErrors=false)` triage during the next session that has the MCP available:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolEntityResolver.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java`

  Gradle compile (`:ai-agent:compileJava`) and the targeted ASM + unit-test runs (`BuiltInDataToolsReadOnlyTest`, `UnknownEntityRetryHintTest`, `com.vn.agent.tools.*`) all exit 0 with zero ERROR-level Javac diagnostics, so no functional issue is expected; this is a precautionary review only.

## User Setup Required

None — internal refactor + new shared `@Component`. Hosts get the new `ToolEntityResolver` bean automatically via `@ComponentScan`; no configuration changes required.

## Next Phase Readiness

- Wave 5 (`BuiltInMutationTools` + supporting tools-mutation classes) can now inject `ToolEntityResolver` and call:
  - `toolEntityResolver.resolveCreatableEntityOrThrow(entityName)` for `create_record`
  - `toolEntityResolver.resolveUpdatableEntityOrThrow(entityName)` for `update_record` / `add_related_record` / `remove_related_record`
  - `toolEntityResolver.parseEntityId(id, metaClass)` for any ID-keyed mutation
- The Phase 10 R4 uniform-opacity contract is now enforced by a single shared method; mutation tools cannot accidentally drift from the read tools' unknown_entity behavior.
- No blockers.

## Self-Check: PASSED

All claimed files exist on disk and both task commit hashes are present in `git log`.

- 4 / 4 files verified
- 2 / 2 commit hashes verified (`43e68ac`, `781564b`)

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-28*
