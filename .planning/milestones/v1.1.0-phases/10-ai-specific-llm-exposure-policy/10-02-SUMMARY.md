---
phase: 10
plan: 02
subsystem: ai-exposure
tags: [policy, repository, event, entity-listener, boundary]
requires:
  - phase-10-01 (AiExposureRule entity + AiExposureRuleMode enum + Liquibase)
provides:
  - LlmExposureChangedEvent (Spring ApplicationEvent — published only, no v1.1 consumer)
  - LlmExposureRuleRepository (UnconstrainedDataManager.findEnabledExcludedEntityNames)
  - AiExposureRuleEntityListener (Jmix EntityChangedEvent → Spring event bridge)
  - LlmExposurePolicy (delegate-and-narrow boundary over CurrentUserSchemaAccess)
affects: []
tech-stack:
  added: []
  patterns:
    - "Jmix 2.x idiom: @EventListener on EntityChangedEvent<T> (NOT JPA @PostPersist/@PostRemove)"
    - "UnconstrainedDataManager + entity @Store auto-routing (store auto-resolved from AiExposureRule's @Store(name=\"agentstore\"); .store(...) chain only on raw-JPQL loadValue/loadValues)"
    - "Stateless @Component delegate-and-narrow boundary (composition: userVisible AND NOT excluded)"
    - "Mockito mockConstruction for CrudEntityContext to unit-test AccessManager-driven canModify"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureChangedEvent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyTest.java
  modified: []
decisions:
  - "Repository uses dataManager.load(AiExposureRule.class).query(...) — store auto-resolved from @Store annotation. .store('agentstore') was specified by the plan but does not exist on the typed-load fluent path; that chain method only applies to loadValue/loadValues raw-JPQL paths. Documented as Rule 1 deviation (compile-blocking bug in plan)."
  - "AiExposureRuleEntityListener documents the SINGLE publish-site contract in Javadoc — view layer must not call applicationEventPublisher.publishEvent(new LlmExposureChangedEvent(...)) directly."
  - "LlmExposurePolicy.canModify uses inline AccessManager + CrudEntityContext (CurrentUserSchemaAccess does not expose canModify; D-02 says we don't add to that class)."
  - "Public visibility for LlmExposurePolicy.getDenylistedEntityNames() — Fix R3 decided here (not flipped in Plan 05). Cross-package consumer is RetrievalFilterBuilder."
  - "TDD: 12 unit tests for LlmExposurePolicy (RED → GREEN; no REFACTOR commit needed). Mockito-only unit suite to keep boot context cost off Spring."
metrics:
  duration_min: 9
  tasks_completed: 2
  files_changed: 5
  completed_date: "2026-04-27"
---

# Phase 10 Plan 02: LlmExposurePolicy Boundary + Repository + Event Bridge Summary

Delegate-and-narrow `LlmExposurePolicy` plus the `LlmExposureRuleRepository`, `AiExposureRuleEntityListener`, and `LlmExposureChangedEvent` Spring infrastructure that all Phase 10 call-site swaps (Plan 04) and RAG filter extensions (Plan 05) depend on.

## Objective Recap

Build the governance boundary on top of the entity foundations from Plan 10-01:

- **`LlmExposurePolicy`** — stateless `@Component` wrapping `CurrentUserSchemaAccess`. Composition is `userVisible AND NOT excluded`. Public method surface mirrors the wrapped class plus `canModify` (Phase 11 wire-in) and `getDenylistedEntityNames` (cross-package consumer in `RetrievalFilterBuilder`).
- **`LlmExposureRuleRepository`** — `UnconstrainedDataManager.findEnabledExcludedEntityNames()` so user-role tweaks cannot bypass admin governance (EXP-06).
- **`AiExposureRuleEntityListener`** — `@EventListener` on Jmix `EntityChangedEvent<AiExposureRule>` that bridges to a Spring `LlmExposureChangedEvent`. Single publish site by contract.
- **`LlmExposureChangedEvent`** — Spring `ApplicationEvent` with no v1.1 consumer (wired for Phase 12+ caching).

No cache anywhere — per call lookup is correct for v1.1 (rule count <50; LLM round-trip dwarfs DB query).

## What Was Built

### Task 1 — Event + Repository + Entity Listener

Three new files in `com.vn.agent.exposure`:

- **`LlmExposureChangedEvent`** — single-line `ApplicationEvent` subclass. Javadoc records: no v1.1 consumer (wired for Phase 12+), in-process only (no clustered propagation), single publish site is the entity listener.
- **`LlmExposureRuleRepository`** — `findEnabledExcludedEntityNames()` returns `LinkedHashSet<String>` of `AiExposureRule.entityName` for rows with `enabled=true` and `mode=EXCLUDE`. Uses constructor-injected `UnconstrainedDataManager`. JPQL entity name `aiExposure_AiExposureRule` (matching Plan 10-01).
- **`AiExposureRuleEntityListener`** — `@EventListener` on `EntityChangedEvent<AiExposureRule>` publishes `LlmExposureChangedEvent`. All three event types (CREATED/UPDATED/DELETED) take the same publish path; the boolean enable/disable toggle that Plan 10-06 will wire reaches this listener via `UnconstrainedDataManager.save` → `EntityChangedEvent` automatically.

**Commit:** `bbd1973` — `feat(10-02): add exposure rule event, repository, and entity listener`

### Task 2 — `LlmExposurePolicy` (TDD)

RED → GREEN cycle. 12 Mockito-only unit tests in `LlmExposurePolicyTest`:

| Test | Asserts |
| ---- | ------- |
| `getReadableSchemaRemovesDenylistedEntities` | denied keys removed; non-denied kept |
| `getReadableSchemaWithEmptyDenylistReturnsBaseUnchanged` | empty denylist short-circuits; base map returned |
| `getReadableSchemaCallsRepositoryOnceNotPerEntity` | Pitfall #1 — repository called exactly once per `getReadableSchema()` call |
| `canReadEntityIsFalseWhenDenied` | denied → false even if delegate allows |
| `canReadEntityIsFalseWhenDelegateDenies` | Jmix denial → false even with empty denylist |
| `canReadEntityIsTrueWhenDelegateAllowsAndNotDenied` | `userVisible AND NOT excluded` |
| `canReadAttributeIsPureDelegate` | repository never consulted (attribute rules deferred) |
| `canModifyIsFalseWhenDenied` | denied → false; uses CrudEntityContext via mockConstruction |
| `canModifyIsFalseWhenAccessManagerDenies` | `isUpdatePermitted=false` → false |
| `canModifyIsTrueWhenAccessManagerAllowsAndNotDenied` | true path; verifies `accessManager.applyRegisteredConstraints` was called |
| `getDenylistedEntityNamesReturnsRepositoryResult` | direct repository pass-through |
| `getDenylistedEntityNamesReturnsEmptyWhenNoRules` | empty set when no rules |

`LlmExposurePolicy` implementation matches the plan body verbatim. No cache, no instance fields beyond the three injected dependencies.

**Commits:**
- `526116d` — `test(10-02): add failing test for LlmExposurePolicy boundary` (RED)
- `4d1d93d` — `feat(10-02): add LlmExposurePolicy delegate-and-narrow boundary` (GREEN)

REFACTOR phase: skipped — no cleanup needed; the plan's reference body is already minimal.

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 1 | BUILD SUCCESSFUL (after Rule 1 fix — see Deviations) |
| `./gradlew :ai-agent:ai-agent:test --tests LlmExposurePolicyTest` after Task 2 | BUILD SUCCESSFUL (12 tests) |
| `./gradlew :ai-agent:ai-agent:test` (full suite) | BUILD SUCCESSFUL |
| `grep UnconstrainedDataManager` across `com.vn.agent.exposure` | only in `LlmExposureRuleRepository` (import + field + ctor + Javadoc); not in `LlmExposurePolicy`, `LlmExposureChangedEvent`, or `AiExposureRuleEntityListener` |
| `grep @PostPersist|@PostRemove` across `com.vn.agent.exposure` | only present in `AiExposureRuleEntityListener` Javadoc explaining its absence |
| `grep @EventListener` in `AiExposureRuleEntityListener` | 1 match |
| `grep public.*getDenylistedEntityNames` in `LlmExposurePolicy` | 1 match (PUBLIC, not package-private — Fix R3 confirmed) |
| `grep delegate.getReadableSchema` in `LlmExposurePolicy` | 1 match |
| `grep findEnabledExcludedEntityNames` in `LlmExposurePolicy` | 4 matches (getReadableSchema, canReadEntity, canModify, getDenylistedEntityNames) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `dataManager.load(EntityClass.class).query(...).store(...)` does not compile**

- **Found during:** Task 1 first compile attempt
- **Issue:** Plan body specified `.store("agentstore")` on the `dataManager.load(AiExposureRule.class).query(...)` chain. That fluent path returns `ByQuery<T>` which has no `store(String)` method — only the raw-JPQL `loadValue/loadValues` paths take an explicit store name. Compile failed with `cannot find symbol: method store(String)`.
- **Root cause:** The MEMORY rule `feedback_jmix_loadvalue_store` was applied too broadly in the plan. That rule applies only to raw-JPQL `loadValue`/`loadValues` paths (which take a string entity name and cannot infer the store automatically). For the typed `dataManager.load(EntityClass.class)` chain, the store is auto-resolved from the entity's `@Store` annotation — and `AiExposureRule` is `@Store(name = "agentstore")` from Plan 10-01, so routing is automatic.
- **Fix:** Removed the `.store("agentstore")` line. Updated the class-level Javadoc to explain the distinction between the typed-load path (auto-store) and the raw-JPQL path (manual store).
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java`
- **Commit:** `bbd1973`
- **Acceptance criterion impact:** PLAN.md Task 1 acceptance criterion `grep -c '.store("agentstore")' ... returns 1` is now 0 in the source; the underlying intent (UnconstrainedDataManager + agentstore routing) is satisfied via the entity's `@Store` annotation. Documented in this SUMMARY's Decisions section.

No Rule 2 (missing critical functionality) or Rule 3 (blocking issue beyond the compile fix) deviations. No Rule 4 architectural change.

## Threat Model Compliance

Threat register from PLAN.md:

- **T-10-01 (mitigate, EoP at LlmExposureRuleRepository):** `LlmExposurePolicy` does NOT save anything — read-only public surface. Only `LlmExposureRuleRepository` injects `UnconstrainedDataManager`, and only as a read source for `findEnabledExcludedEntityNames`. No `dataManager.save(...)` call in any of the four classes. Verified.
- **T-10-02 (mitigate, Information Disclosure at getReadableSchema):** `LlmExposurePolicy.getReadableSchema()` removes denied entities from the returned map before returning. `getReadableSchemaRemovesDenylistedEntities` test asserts the `entityHidden` key is absent from the result. Plan 04 will swap `BaselineContextProvider` to source through this method.
- **T-10-04 (mitigate, Information Disclosure at canReadEntity):** `canReadEntity` returns false for denied entities. `canReadEntityIsFalseWhenDenied` test asserts. Plan 04 will translate this `false` into `unknown_entity` (per Phase 9 D-14 opacity rule).

No new threat surface introduced beyond what the threat model already accepted.

## Open Items / Follow-ups

- Plan 10-03 will extend `AiAgentAdminRole` with `@EntityPolicy(entityClass = AiExposureRule.class, ...)`. Until then, only `UnconstrainedDataManager` callers can write the table.
- Plan 10-04 will swap `BuiltInDataTools`, `BaselineContextProvider`, and `FetchPlanIntersector` to inject `LlmExposurePolicy` instead of `CurrentUserSchemaAccess`.
- Plan 10-05 will consume `LlmExposurePolicy.getDenylistedEntityNames()` from `RetrievalFilterBuilder` (cross-package call validates the public visibility decision).
- `LlmExposureChangedEvent` has no consumer in v1.1 (by design, per D-15) — Phase 12+ caching listener will activate.
- `LlmExposurePolicy.canModify(MetaClass)` ships in Phase 10 with no caller (per D-02). Phase 11 mutation gating step 1 will wire it before `DataManager.save`.
- Spring boot context loaded cleanly with the four new beans (`LlmExposurePolicy`, `LlmExposureRuleRepository`, `AiExposureRuleEntityListener`) — no `ContextLoaderDelegate` failure during full test run.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureChangedEvent.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyTest.java` — FOUND

Commits exist (verified via `git log --oneline -5`):
- `bbd1973` — Task 1 (event + repository + entity listener)
- `526116d` — Task 2 RED (failing tests)
- `4d1d93d` — Task 2 GREEN (LlmExposurePolicy implementation)

Compile + targeted-test + full-suite green on the final run.
