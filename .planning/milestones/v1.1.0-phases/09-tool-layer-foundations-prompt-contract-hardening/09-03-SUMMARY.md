---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 03
subsystem: orchestration
tags: [baseline, prompt, schema-inventory, permissions, jmix-access-manager, locale-invariant]

# Dependency graph
requires:
  - phase: 03-metadata-first-runtime-six-tools
    provides: CurrentUserSchemaAccess.getReadableSchema as the single AccessManager-filtered schema source reused by both BuiltInDataTools.listEntities and now BaselineContextProvider.
  - phase: 04-walking-skeleton-baseline-context
    provides: BaselineContextProvider.compose / renderAsText extension site (5 baseline keys already emitted; Plan 09-03 appends 2).
provides:
  - "agent.entities prompt key — alpha-by-MetaClass-name 'name (label)' lines, truncated at configured limit with verbatim D-03 hint"
  - "agent.permissions prompt key — compact deterministic JSON, alpha by entity, fixed key order r,u,c,d,modifiable, alpha-sorted modifiable[]"
  - "AiAgentPromptProperties @ConfigurationProperties record bound to jmix.ai-agent.prompt.* with resolvedEntityInventoryLimit() default 100"
  - "Locale-invariance contract on agent.permissions (P-8 cache-key safety)"
  - "Phase 10 substitution seam: single getReadableSchema() call site is the only thing LlmExposurePolicy needs to swap"
affects:
  - 10-ai-specific-llm-exposure-policy
  - 11-mutation-capable-built-in-tools

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@ConfigurationProperties record + nested EntityInventory record + resolved* default-fallback accessor (mirrors AiAgentGuardProperties pattern from Plan 06)"
    - "AccessManager.applyRegisteredConstraints with side-effecting CrudEntityContext / EntityAttributeContext probe (same pattern as CurrentUserSchemaAccess.canRead*)"
    - "Locale-free JSON cache key: TreeMap<String, ?> (alpha by entity) + LinkedHashMap (fixed key order r,u,c,d,modifiable) + TreeSet attribute iteration (alpha modifiable[])"
    - "Same sorted/capped entity list drives both agent.entities and agent.permissions — entities beyond cap cannot leak via permissions (review fix surfaced during planning)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiAgentPromptPropertiesTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties

key-decisions:
  - "agent.permissions value carries zero locale-resolved labels — only metaclass names and attribute names — so it is byte-equal across locales (P-8 cache-key safety; PROMPT-02 explicit wording). messageTools.getEntityCaption is the only locale-sensitive substring in the entire baseline extension and is confined to the parenthesized suffix of agent.entities."
  - "BaselineContextProvider gains 5 new constructor-injected collaborators (CurrentUserSchemaAccess, AccessManager, MessageTools, ObjectMapper, AiAgentPromptProperties). Spring DI wiring is automatic; no autoconfig change required because the class is already a @Component and all collaborators are already Spring beans."
  - "Same sorted/capped entity list drives both agent.entities and agent.permissions — entities beyond the inventory cap cannot leak via permissions JSON (review fix from planning, validated by compose_permissionsDoesNotMentionEntitiesBeyondInventoryLimit test)."
  - "module.properties carries the new jmix.ai-agent.prompt.entity-inventory.limit=100 default; default-params.yaml left untouched per Plan 09-01 carve-out (it is strict AiParameters seed YAML, not Spring config)."

patterns-established:
  - "Pattern 1: Per-request locale-free cache key envelope. JSON value built from TreeMap (alpha keys) + LinkedHashMap (fixed key order) + TreeSet iteration (alpha modifiable[]) is byte-equal across locales by construction; locale-sensitive labels live only in a sibling key carrying parenthesized text."
  - "Pattern 2: Same sorted/capped collection drives multiple sibling prompt blocks so a tail-truncation in one block can never leak via the other. Tested via the parity-with-entities check."
  - "Pattern 3: Phase-N+1 substitution seam — when a future phase will swap the source class, route every call through that source class only at one site so the substitution is a one-line change. Documented in BaselineContextProvider Javadoc."

requirements-completed:
  - PROMPT-01
  - PROMPT-02
  - TOOL-12

# Metrics
duration: 25min
completed: 2026-04-27
---

# Phase 9 Plan 03: Inject agent.entities + agent.permissions into BaselineContextProvider Summary

**BaselineContextProvider now emits two new deterministic prompt keys per chat turn — alpha-sorted `agent.entities` (`name (label)` per line, truncated at 100) and locale-invariant `agent.permissions` JSON (CRUD + modifiable[]) — sourced from `CurrentUserSchemaAccess` and Jmix `AccessManager`, with locale labels resolved at render time and never embedded in any cache key (P-8).**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-04-26T20:14:00Z
- **Completed:** 2026-04-26T20:39:22Z
- **Tasks:** 2 (both TDD: RED + GREEN)
- **Files created:** 2
- **Files modified:** 3

## Accomplishments

- `AiAgentPromptProperties` `@ConfigurationProperties` record (`jmix.ai-agent.prompt.*`) with nested `EntityInventory(Integer limit)` and `resolvedEntityInventoryLimit()` defaulting to 100 (D-03). 3-test unit suite covering null nested block, null limit field, and operator override — all PASS.
- `BaselineContextProvider` extended with 5 new constructor-injected collaborators (`CurrentUserSchemaAccess`, `AccessManager`, `MessageTools`, `ObjectMapper`, `AiAgentPromptProperties`). `compose(UUID)` now appends `agent.entities` and `agent.permissions` to the existing five baseline keys; both keys are OMITTED entirely on empty schema (D-01).
- `agent.entities` rendering: alpha-sorted by `MetaClass.getName()`, `name (label)` per line, joined with `\n`, truncated at the configured limit (default 100) with verbatim D-03 hint line `... (truncated, call list_entities for full list)` appended.
- `agent.permissions` rendering: compact JSON, alpha-keyed by entity (TreeMap), fixed key order `r,u,c,d,modifiable` (LinkedHashMap), alpha-sorted `modifiable[]` (TreeSet iteration), entries with all CRUD bits zero omitted (D-02). Permissions built from the SAME sorted/capped entity list as `agent.entities` — entities beyond the inventory cap cannot leak via permissions JSON.
- Locale-invariance contract validated: `compose_permissionsJson_isLocaleInvariant_betweenEnglishAndVietnamese` asserts byte-equal `agent.permissions` across `Locale.ENGLISH` and `Locale.of("vi","VN")`. The only locale-sensitive substring in the entire baseline extension is the parenthesized label inside `agent.entities`, which never enters the permissions value.
- 9 new test methods added to `BaselineContextProviderTest` (12 total in the class) — all PASS, including all 6 must-have behaviors from the plan + the review-fix parity-with-entities check.
- Full `:ai-agent:ai-agent:test` suite: BUILD SUCCESSFUL — no regressions in any existing Spring-boot integration test that wires `BaselineContextProvider` via DI (the new constructor's dependencies are all pre-existing Spring beans).

## Task Commits

Each task was committed atomically (TDD on both):

1. **Task 3.1 RED:** `cadc1f4` — `test(09-03): add failing tests for AiAgentPromptProperties D-03 defaults`
2. **Task 3.1 GREEN:** `06c2543` — `feat(09-03): add AiAgentPromptProperties record + module.properties D-03 default`
3. **Task 3.2 RED:** `ba33dd1` — `test(09-03): extend BaselineContextProviderTest for agent.entities + agent.permissions`
4. **Task 3.2 GREEN:** `fb631af` — `feat(09-03): emit agent.entities + agent.permissions from BaselineContextProvider`

**Plan metadata commit:** to be created after this SUMMARY.md / STATE.md / ROADMAP.md / REQUIREMENTS.md are written.

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java` — Created. Java record bound to `jmix.ai-agent.prompt` with nested `EntityInventory(Integer limit)` and `resolvedEntityInventoryLimit()` accessor.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` — Modified. 5 new constructor params, 4 new private helpers (`visibleEntities`, `renderEntitiesBlock`, `renderPermissionsJson`, `modifiableAttributesOf`); `compose(UUID)` extended with two conditional `ctx.put(...)` calls.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiAgentPromptPropertiesTest.java` — Created. 3 unit tests covering D-03 default-resolution paths.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java` — Modified. 9 new test methods + `BuilderConfig` helper + `AccessManager.applyRegisteredConstraints` doAnswer stub that mutates passed-in `CrudEntityContext` / `EntityAttributeContext` (mirrors real Jmix API contract).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — Modified. Appended `jmix.ai-agent.prompt.entity-inventory.limit=100` Spring default (D-03).

## Decisions Made

- Followed plan as specified verbatim. Locked decisions D-01, D-02, D-03 from `09-CONTEXT.md` and the planner's "review incorporation" notes (no edits to `default-params.yaml`; same capped list drives both blocks) were honored exactly.
- The only judgement call was inside the test helper: real Jmix `EntityAttributeContext` exposes `getPropertyPath()` (not `getEntityClass()` / `getName()`), so the test's `doAnswer` stub uses `attr.getPropertyPath().getMetaClass()` and `attr.getPropertyPath().toString()`. Mocked `MetaClass.getPropertyPath(String)` returns a real `MetaPropertyPath` built from a single mocked `MetaProperty` so the constructor side-effect chain works under test. This was discovered by reading the Jmix-core 2.8 sources directly.

## Verification Performed

| Verification | Result |
|---|---|
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.AiAgentPromptPropertiesTest"` | PASS (3 tests) |
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.BaselineContextProviderTest"` | PASS (12 tests = 3 pre-existing + 9 new) |
| `./gradlew :ai-agent:ai-agent:test` (full module suite) | BUILD SUCCESSFUL (no regression in any Spring-boot integration test that DI-wires BaselineContextProvider) |
| `./gradlew :ai-agent:ai-agent-starter:test` (autoconfig boot smoke) | BUILD SUCCESSFUL |
| `grep -F 'agent.entities' BaselineContextProvider.java` | 5 matches (≥1 required) |
| `grep -F 'agent.permissions' BaselineContextProvider.java` | 6 matches (≥1 required) |
| `grep -F 'currentUserSchemaAccess.getReadableSchema()' BaselineContextProvider.java` | 2 matches (≥1 required) — single Phase 10 substitution seam |
| `grep -F 'CrudEntityContext' BaselineContextProvider.java` | 2 matches (≥1 required) |
| `grep -E 'Locale' BaselineContextProvider.java \| grep -i 'cache\|hash'` | 0 matches — no Locale referenced in any cache-key construction site |
| `grep -F '... (truncated, call list_entities for full list)' BaselineContextProvider.java` | 1 match — verbatim D-03 hint, single occurrence |
| `grep -F 'isLocaleInvariant' BaselineContextProviderTest.java` | 1 match — locale-invariance contract test present |
| `grep -F 'permissionsDoesNotMentionEntitiesBeyondInventoryLimit' BaselineContextProviderTest.java` | 1 match — review-fix parity-with-entities test present |
| `grep -F '@ConfigurationProperties("jmix.ai-agent.prompt")' AiAgentPromptProperties.java` | 1 match |
| `grep -F 'resolvedEntityInventoryLimit' AiAgentPromptProperties.java` | 2 matches (≥1 required) |
| `grep -F 'jmix.ai-agent.prompt.entity-inventory.limit=100' module.properties` | 1 match |

## Locale-Invariance Confirmation

`agent.permissions` is **locale-invariant by construction**, not by accident. Walking the JSON:

- Outer keys: `mc.getName()` (e.g. `acme_Customer`) — locale-stable Jmix metamodel identifier.
- Inner keys: literal `"r"`, `"u"`, `"c"`, `"d"`, `"modifiable"` — string literals.
- Values: ints (1/0) and a list of attribute-name strings (e.g. `"name"`, `"email"`) — all metamodel identifiers, all locale-stable.

The only `messageTools.getEntityCaption(...)` call in the entire Phase 9 baseline extension is inside `renderEntitiesBlock` — it produces the parenthesized suffix of each `agent.entities` line and never crosses into the permissions JSON. The `compose_permissionsJson_isLocaleInvariant_betweenEnglishAndVietnamese` test is the regression gate that pins this invariant: it builds the permissions JSON twice (English captions vs. `Khách hàng` Vietnamese caption for the same Customer entity) and asserts byte-equality.

## Phase 10 Substitution Seam

`BaselineContextProvider.compose(UUID)` now contains exactly **one** schema-source call site:

```java
Map<MetaClass, Set<String>> readableSchema = currentUserSchemaAccess.getReadableSchema();
```

Phase 10's `LlmExposurePolicy` will substitute the source class with the same return type. The substitution is a single-line change: replace `currentUserSchemaAccess` with `llmExposurePolicy` (matching the `getReadableSchema()` signature). All call-site code, all 4 helper methods, all test fixtures, and the JSON shape contract stay unchanged. Documented verbatim in the class-level Javadoc.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

One discovery, not an issue: the test helper had to be adjusted from the plan's reference shape because real Jmix `EntityAttributeContext` exposes `getPropertyPath()` (returning a `MetaPropertyPath`), not `getEntityClass()` / `getName()`. Resolved by reading `jmix-core-2.8.0-sources.jar` directly and stubbing `MetaClass.getPropertyPath(String)` to return a real `MetaPropertyPath` whose `getMetaClass()` returns the mocked `MetaClass` and whose `toString()` returns the attribute name. No production code change was needed — the production code already constructs `EntityAttributeContext` correctly.

## Threat Surface

No new network endpoints, auth paths, file access patterns, or schema changes introduced. All threats in `<threat_model>` (T-09-09 through T-09-13) are mitigated as planned:

- **T-09-09 (denied entities in agent.entities):** mitigated — single source is `CurrentUserSchemaAccess.getReadableSchema()` which already filters via `AccessManager.canReadEntity`. Phase 10 will substitute `LlmExposurePolicy` at the same site for additional admin denylisting.
- **T-09-10 (CRUD bits exposing denied operations):** mitigated — bits read directly from `CrudEntityContext` after `AccessManager.applyRegisteredConstraints`, same authority the Jmix UI uses. `modifiable[]` gated by `EntityAttributeContext.canModify()`.
- **T-09-11 (locale-label leakage into cache key, P-8):** mitigated — Phase 9 baseline is uncached per-request; `agent.permissions` carries zero labels by construction. `compose_permissionsJson_isLocaleInvariant_betweenEnglishAndVietnamese` is the regression gate.
- **T-09-12 (control-char tampering in MessageTools captions):** accept-with-note — Jmix metamodel discipline disallows newlines in captions; Phase 10's `LlmExposurePolicy` will sanitize at the substitution boundary if needed.
- **T-09-13 (large JSON under thousands of entities):** mitigated — D-03 truncation cap (default 100) bounds both blocks; operator can raise via `jmix.ai-agent.prompt.entity-inventory.limit`. The `compose_permissionsDoesNotMentionEntitiesBeyondInventoryLimit` test asserts entities beyond the cap never reach permissions.

No new threat flags to surface.

## Next Phase Readiness

- Plan 09-04 (next wave-2 plan) can now plan/execute knowing Plan 09-03's prompt keys are stable. `BaselineContextProvider` Javadoc documents both the locale-invariance contract and the Phase 10 substitution seam, so Plan 09-04 (and downstream Phase 10) can build on the established conventions without rediscovering them.
- TEST-08 corpus consumers in subsequent plans should reference `agent.permissions` byte-stability as the canonical example of an LLM-prompt key whose value is permitted to participate in cache keys (because labels are absent).
- Phase 10 substitution: a single grep for `currentUserSchemaAccess.getReadableSchema()` in `BaselineContextProvider.java` returns the only call site Phase 10 needs to swap when wiring `LlmExposurePolicy`.

## Self-Check: PASSED

- `[FOUND]` `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java`
- `[FOUND]` `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiAgentPromptPropertiesTest.java`
- `[FOUND]` Modified `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
- `[FOUND]` Modified `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java`
- `[FOUND]` Modified `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`
- `[FOUND]` Commit `cadc1f4` (Task 3.1 RED)
- `[FOUND]` Commit `06c2543` (Task 3.1 GREEN)
- `[FOUND]` Commit `ba33dd1` (Task 3.2 RED)
- `[FOUND]` Commit `fb631af` (Task 3.2 GREEN)

## TDD Gate Compliance

Both tasks executed with explicit RED → GREEN sequence:
- Task 3.1: `cadc1f4` (`test(09-03): ...`) — `compileTestJava` failed `cannot find symbol AiAgentPromptProperties` → `06c2543` (`feat(09-03): ...`) — class added, all 3 tests passed first run.
- Task 3.2: `ba33dd1` (`test(09-03): ...`) — `compileTestJava` failed `constructor BaselineContextProvider cannot be applied to given types` (1-arg vs 6-arg) → `fb631af` (`feat(09-03): ...`) — production constructor + 4 helpers added; all 12 tests (3 pre-existing + 9 new) passed first run.

No REFACTOR commits needed — implementation matched the plan's reference shape. The single Javadoc tweak (removing a duplicate verbatim D-03 hint string from the helper Javadoc to satisfy the plan's exact `grep -F ... | wc -l == 1` acceptance criterion) was rolled into the GREEN commit before push.

Plan-level type is `execute`, not `tdd`, so the plan-level RED/GREEN gate sequence does not apply — only per-task TDD cycles did.

---

*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Plan: 03*
*Completed: 2026-04-27*
