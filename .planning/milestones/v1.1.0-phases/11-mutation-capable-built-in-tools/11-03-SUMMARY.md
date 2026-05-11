---
phase: 11-mutation-capable-built-in-tools
plan: 03
subsystem: tools-mutation
tags: [spi, mutation-guard, default-bean, conditional-on-missing-bean]

# Dependency graph
requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: AiAgentMutationProperties + AiToolCallOutcome.IDEMPOTENT_REPLAY/COMMIT_FAILED + @EnableScheduling (Plan 11-02)
provides:
  - MutationGuard SPI interface (com.vn.agent.spi.MutationGuard)
  - MutationIntent record carrier (com.vn.agent.spi.MutationIntent — 4-field minimal shape per D-03)
  - Default no-op MutationGuard bean registered via @ConditionalOnMissingBean(MutationGuard.class) in AIConfiguration
  - SPI-10 / MUT-05 contract surface for Wave 3+ consumers
affects: [11-04, 11-05, 11-06, 11-07A, 11-07B, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SPI mirror of ToolGuard: single-method interface + reused ToolVetoedException + default no-op bean via @ConditionalOnMissingBean"
    - "Typed record carrier (MutationIntent) over Map<String,Object> for forward-compatibility (extra fields go behind default methods later)"
    - "Null-preserving immutable map: Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) — Map.copyOf would throw on null values; null attribute values represent optional-field clears"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationGuard.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationIntent.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java

key-decisions:
  - "MutationIntent attributes use Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) NOT Map.copyOf — Map.copyOf rejects null values, but null attribute values are the wire representation of optional-field clears (per plan must-haves truth)"
  - "Default no-op bean registered directly in AIConfiguration via @ConditionalOnMissingBean(MutationGuard.class) — no separate SpiDefaultsAutoConfiguration class introduced (PATTERNS.md confirms it does not currently exist; existing project precedent is the aiAgentIngestExecutor @ConditionalOnMissingBean at AIConfiguration line 81)"
  - "ToolVetoedException reused verbatim — no new exception type added (CONTEXT.md D-03)"

patterns-established:
  - "Mutation-side SPIs follow ToolGuard shape verbatim with the single difference of a typed record argument: void check(MutationIntent intent) throws ToolVetoedException"
  - "Default no-op SPI bean lives directly in AIConfiguration (next to aiAgentIngestExecutor) until a SpiDefaultsAutoConfiguration class is genuinely warranted by SPI count"

requirements-completed:
  - SPI-10
  - MUT-05

# Metrics
duration: 2min
completed: 2026-04-28
---

# Phase 11 Plan 03: MutationGuard SPI + MutationIntent Record + Default No-op Bean

**Wave 3's `BuiltInMutationTools` SPI surface — `MutationGuard` interface mirrors `ToolGuard`, `MutationIntent` record carries the 4-field minimal D-03 shape, and the no-op default bean ships via `@ConditionalOnMissingBean` in `AIConfiguration` so hosts can opt in to mutation policy enforcement by declaring their own `@Component MutationGuard`.**

## Performance

- **Duration:** ~2 min (single-task plan; warm Gradle daemon)
- **Started:** 2026-04-28T20:19:43Z
- **Completed:** 2026-04-28T20:21Z (Task 1 commit `964a6a9`)
- **Tasks:** 1 / 1
- **Files modified:** 3 (2 created + 1 modified)

## Accomplishments

- `MutationGuard` SPI ships at `com.vn.agent.spi.MutationGuard` with the single `void check(MutationIntent intent) throws ToolVetoedException` method. Mirror of `ToolGuard` shape (Phase 9 SPI-09); reuses `ToolVetoedException` verbatim — no new exception class.
- `MutationIntent` record ships at `com.vn.agent.spi.MutationIntent` with the D-03 minimal 4-field shape: `String toolName`, `MetaClass metaClass`, `@Nullable UUID entityId`, `Map<String, Object> attributes`. The compact constructor null-checks `toolName`/`metaClass`, treats a null map as `Map.of()`, and otherwise defensively copies via `Collections.unmodifiableMap(new LinkedHashMap<>(attributes))` to preserve null attribute values (the wire representation of optional-field clears).
- Default no-op `MutationGuard` bean registered in `AIConfiguration` via `@Bean @ConditionalOnMissingBean(MutationGuard.class)` (placed directly after the existing `aiAgentIngestExecutor` `@ConditionalOnMissingBean` precedent). Lambda `intent -> { /* no-op */ }`. Hosts override by declaring their own `@Component MutationGuard`.
- `MutationGuard` import added to `AIConfiguration` next to the `MdcPropagatingTaskDecorator` / `AiAgentRagProperties` block.
- `./gradlew :ai-agent:compileJava` exits 0; no warnings introduced beyond the existing Gradle 8.14.4 deprecation notices.

## Task Commits

1. **Task 1: Create MutationGuard SPI + MutationIntent record + default no-op bean** — `964a6a9` (feat)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationGuard.java` — SPI interface; single `check(MutationIntent)` method; mirrors `ToolGuard`; reuses `ToolVetoedException`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationIntent.java` — record with the D-03 4-field shape; compact constructor enforces null-checks on `toolName`/`metaClass` and the null-preserving immutable copy on `attributes`.

### Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — added `import com.vn.agent.spi.MutationGuard;` plus the `noopMutationGuard()` `@Bean @ConditionalOnMissingBean(MutationGuard.class)` method directly above the existing `resolveInt(...)` private helper. No other changes.

## Decisions Made

- **`Collections.unmodifiableMap(new LinkedHashMap<>(attributes))`** (NOT `Map.copyOf(attributes)`) — null attribute values are the wire representation of optional-field clears (e.g. `update_record` setting `description=null` to clear an optional field). `Map.copyOf` throws `NullPointerException` on null values; `LinkedHashMap` accepts them. Plan must-haves truth verbatim.
- **No-op bean placed directly in `AIConfiguration`** (not in a new `SpiDefaultsAutoConfiguration` class) — `PATTERNS.md` and `RESEARCH.md` confirm `SpiDefaultsAutoConfiguration` does not currently exist (referenced only in `ToolFetchPlanCustomizer` Javadoc). Existing project precedent is the `aiAgentIngestExecutor` `@ConditionalOnMissingBean` at `AIConfiguration` line 81. Following that precedent keeps the diff minimal and avoids introducing a new auto-configuration class for a single bean.
- **Reuse `ToolVetoedException` verbatim** (CONTEXT.md D-03) — no new exception type. Audit row records `outcome=BLOCKED` with `denialReason=exception.getMessage()`; LLM-facing error code is `access_denied` with the `expected` hint "do not retry; surface to user" (Wave 3 wires this).
- **`MutationIntent` record vs. interface with default methods** — record per D-03. Forward-compatibility note in Javadoc: "extra fields can be added behind default methods later" — when an additional field is needed (e.g. `Supplier<Object> preImage`), the record can grow a static factory + add an interface above the record without breaking existing callers, but v1.1 ships the minimal 4-field shape.
- **`@Nullable UUID entityId`** — Spring's `org.springframework.lang.Nullable` (matches the project's existing usage in other SPI/utility classes). `entityId` is null on `create_record`; populated on `update_record` / `add_related_record` / `remove_related_record`.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None. Single-task compile passed on first attempt.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** the JetBrains MCP server is not registered in this execution environment (per Plan 11-02 SUMMARY environment note). The following Java files should be opened in IntelliJ for `get_file_problems("path", onlyErrors=false)` triage during the next session that has the MCP available:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationGuard.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationIntent.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`

  Gradle compile (`:ai-agent:compileJava`) passed with zero ERROR-level Javac diagnostics, so no functional issue is expected; this is a precautionary review only.

## User Setup Required

None — SPI-only plan. Hosts opt in to mutation policy enforcement in a later session by declaring their own `@Component MutationGuard` bean; the no-op default preserves the v1.1 default-OFF posture.

## Next Phase Readiness

- Wave 3 (`BuiltInMutationTools` + supporting tools-mutation classes) can now inject `MutationGuard` via constructor and call `mutationGuard.check(new MutationIntent(toolName, metaClass, entityId, attributes))` between gating step 2 (per-attribute `EntityAttributeContext.canModify`) and step 4 (`@Transactional DataManager.save`).
- Hosts that need mutation policy enforcement can declare their own `@Component MutationGuard` bean — `@ConditionalOnMissingBean(MutationGuard.class)` ensures the no-op default yields automatically.
- No blockers.

## Self-Check: PASSED

All claimed files exist on disk and the task commit hash is present in `git log`.

- 3 / 3 files verified (2 created, 1 modified)
- 1 / 1 commit hash verified (`964a6a9`)

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-28*
