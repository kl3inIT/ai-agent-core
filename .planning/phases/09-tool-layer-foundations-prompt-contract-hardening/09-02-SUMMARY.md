---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 02
subsystem: spi
tags: [spi, fetch-plan, autoconfig, jmix, spring-ai]

# Dependency graph
requires:
  - phase: 03-metadata-first-runtime-six-tools
    provides: BuiltInDataTools data-load sites that Plan 09-04 will rewire to consult ToolFetchPlanCustomizer
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-01 AUD-07 plumbing (separate; Plan 09-02 contracts are independent of audit hashing)
provides:
  - ToolFetchPlanCustomizer SPI interface (D-09 locked signature)
  - FetchPlanContext concrete request snapshot record (D-10 review correction)
  - SpiDefaultsAutoConfiguration no-op default bean (mirrors ToolGuard / ToolContributor precedent)
  - Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the SPI seam
affects:
  - 09-04 (Plan 09-04 BuiltInDataTools + FetchPlanIntersector consumers)
  - 11-mutation-capable-built-in-tools (mutation tools may also consult the SPI for write-time projection)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@ConditionalOnMissingBean no-op SPI default in SpiDefaultsAutoConfiguration"
    - "Concrete immutable request-snapshot record (NOT a static-utility holder) as SPI parameter"
    - "Verbatim contract phrase mirrored at both interface and consumer Javadoc seams"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java
  modified:
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java

key-decisions:
  - "FetchPlanContext is a concrete snapshot record (UUID runId, UUID conversationId, Integer retrievalTopK, Double retrievalSimilarityThreshold, String retrievalFiltersJson, Locale locale, UserDetails user) rather than carrying RunContext as a value-object component (D-10 review correction: RunContext is a final class with private constructor and static ThreadLocal accessors and cannot be embedded in an SPI parameter object)."
  - "SPI signature locked verbatim per D-09: Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context). No deviation from the planning contract."
  - "Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the SPI Javadoc seam in this plan. Plan 09-04 will repeat the same phrase in FetchPlanIntersector so the contract is documented at both seams."
  - "Discovery model is List<ToolFetchPlanCustomizer> first-non-empty-wins (mirrors ToolContributor precedent at AgentToolCallbacks). The default bean returns Optional.empty() so the add-on falls back to FetchPlan.BASE."

patterns-established:
  - "SPI parameter records snapshot static utility values explicitly rather than embedding the utility itself, keeping the SPI surface decoupled from internal carrier classes."
  - "TOOL-11-style invariants are authored at the SPI seam and re-asserted at the consumer seam (Plan 09-04 FetchPlanIntersector)."

requirements-completed: [SPI-09, TOOL-10]

# Metrics
duration: 6min
completed: 2026-04-27
---

# Phase 9 Plan 02: ToolFetchPlanCustomizer SPI Surface Summary

**ToolFetchPlanCustomizer SPI interface plus FetchPlanContext request-snapshot record, with a no-op `@ConditionalOnMissingBean` default registered in `SpiDefaultsAutoConfiguration` mirroring the existing six no-op SPI defaults.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-04-27T03:13:00+07:00 (UTC 2026-04-26T20:13:00Z)
- **Completed:** 2026-04-27T03:19:01+07:00 (UTC 2026-04-26T20:19:01Z)
- **Tasks:** 3
- **Files modified:** 3 (2 created, 1 extended)

## Accomplishments

- Created `ToolFetchPlanCustomizer` SPI interface with the locked D-09 signature `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context)` and the verbatim TOOL-11 phrase `"fetch plan is projection, not security."` in its Javadoc.
- Created `FetchPlanContext` record as a concrete immutable per-request snapshot (D-10 review correction): `record FetchPlanContext(UUID runId, UUID conversationId, Integer retrievalTopK, Double retrievalSimilarityThreshold, String retrievalFiltersJson, Locale locale, UserDetails user)`. Does NOT embed `RunContext`, which is a final static-only utility class.
- Registered `defaultToolFetchPlanCustomizer()` `@Bean` in `SpiDefaultsAutoConfiguration` returning `Optional.empty()`, mirroring the existing `ToolGuard` / `ToolContributor` no-op precedent. `@ConditionalOnMissingBean` preserves host-bean override semantics.
- Verified verbatim TOOL-11 phrase via `grep -F`: `1` match, lowercase, terminating period.
- Confirmed boot smoke: `:ai-agent:ai-agent-starter:test` passes (the new bean coexists with the existing six SPI defaults; `:ai-agent:ai-agent:test` full suite also passes).

## Task Commits

Each task was committed atomically:

1. **Task 2.1: Create FetchPlanContext record** — `8091daa` (feat)
2. **Task 2.2: Create ToolFetchPlanCustomizer SPI interface** — `0fc5f3a` (feat)
3. **Task 2.3: Register no-op default in SpiDefaultsAutoConfiguration** — `777e357` (feat)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` — NEW. Concrete immutable per-request snapshot for the SPI; carries `runId`, `conversationId`, retrieval params, locale, and `UserDetails`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` — NEW. Single-method `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context)` SPI; Javadoc carries the verbatim TOOL-11 invariant, the D-13 scope clarification, and a `@Component`-using example mirroring `ToolGuard`'s shape.
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` — EXTENDED. Added `defaultToolFetchPlanCustomizer()` `@Bean`, plus the corresponding `import com.vn.agent.spi.ToolFetchPlanCustomizer;` and `import java.util.Optional;` (alphabetical position).

## Decisions Made

- Followed the planning contract verbatim. The only judgement call was honoring the D-10 review correction: `FetchPlanContext` carries explicit values rather than a `RunContext` reference, because `RunContext` in this codebase is `final` with a private constructor and static `ThreadLocal` accessors. Documented in the record's Javadoc so future maintainers understand why the field set is the shape it is.

## Verification

- `grep -F 'fetch plan is projection, not security.' ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` → 1 match (lowercase, period at end). Verbatim TOOL-11 contract present.
- `grep -F 'Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context)' ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` → 1 match. Verbatim D-09 signature.
- `grep -F 'public record FetchPlanContext(UUID runId,' ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` → 1 match.
- `grep -F 'com.vn.agent.orchestration.RunContext' ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` → 0 matches (record correctly does NOT carry RunContext).
- `grep -c '@ConditionalOnMissingBean' ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` → 8 (six existing defaults + one new + one in the class Javadoc reference). At least the required ≥7 (one per `@Bean`).
- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL.
- `./gradlew :ai-agent:ai-agent-starter:compileJava :ai-agent:ai-agent-starter:test` → BUILD SUCCESSFUL.
- `./gradlew :ai-agent:ai-agent:test` → BUILD SUCCESSFUL (full module test suite, includes `@SpringBootTest`-style boot tests that load the autoconfigs).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## Threat Surface

No new network endpoints, auth paths, file access patterns, or schema changes were introduced. The SPI surface is host-side Spring DI only and matches the threat dispositions already documented in `09-02-PLAN.md` (`<threat_model>`):

- T-09-05 (E - host returning a wider FetchPlan than user permissions) is *mitigated* in this plan by Javadoc documenting the projection-only contract; the runtime mitigation lands in Plan 09-04 (`FetchPlanIntersector`).
- T-09-07 (I - argument leakage to customizer) is mitigated by `FetchPlanContext` deliberately omitting tool arguments.
- T-09-06 (S - host registers malicious customizer) and T-09-08 (D - slow customizer) remain *accept* per ASVS L1 boundaries — host owns its own ApplicationContext and bean performance.

No `threat_flag` rows to add.

## Next Phase Readiness

- Plan 09-04 can now be planned/executed against a stable interface. Wave-1 contracts (Plan 09-02) and Wave-2 consumers (Plan 09-04) are correctly split per the phase-level wave model.
- The SPI is unused by production code in this commit set. Adding a host bean would compile cleanly today; the no-op default keeps the runtime behavior of the existing six tools (`list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`) unchanged until Plan 09-04 wires the consumer.
- No blockers for Plan 09-03 (the next wave-1 plan) or Plan 09-04 (wave-2 consumer of these contracts).

## Self-Check: PASSED

- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` ✓
- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java` ✓
- File modified: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` ✓
- Commit `8091daa` exists in git log ✓
- Commit `0fc5f3a` exists in git log ✓
- Commit `777e357` exists in git log ✓
- Verbatim TOOL-11 phrase present in `ToolFetchPlanCustomizer.java` ✓
- Verbatim D-09 signature present in `ToolFetchPlanCustomizer.java` ✓
- `FetchPlanContext` does NOT carry `RunContext` as a component ✓
- `SpiDefaultsAutoConfiguration` registers the new `@ConditionalOnMissingBean` default bean ✓
- Boot smoke confirmed via `:ai-agent-starter:test` BUILD SUCCESSFUL ✓

---
*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Completed: 2026-04-27*
