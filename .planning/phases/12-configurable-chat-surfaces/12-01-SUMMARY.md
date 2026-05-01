---
phase: 12-configurable-chat-surfaces
plan: 01
subsystem: ui-configuration
tags: [jmix, flow-ui, liquibase, agentstore, chat-surfaces]

requires:
  - phase: 9-tool-layer-foundations-prompt-contract-hardening
    provides: Existing chat route, prompt/tool foundation, and LLM-visible metadata boundary
provides:
  - Two-surface Phase 12 roadmap and requirements contract
  - AiChatSurface enum for FULL_ROUTE and HEADER_BUTTON
  - AiUiSettings singleton entity, Liquibase table, and no-cache loader service
  - LLM metadata exclusion for AiUiSettings
affects: [phase-12, chat-ui, admin-settings, ai-metadata]

tech-stack:
  added: []
  patterns:
    - Text-backed Jmix EnumClass set helpers instead of enum element collections
    - Singleton agentstore settings loaded through UnconstrainedDataManager

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/080-ai-ui-settings.xml
    - ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java
  modified:
    - .planning/ROADMAP.md
    - .planning/REQUIREMENTS.md
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "Persist enabled surfaces as deterministic enabledSurfaceIds text, with typed helpers named getEnabledSurfaceSet/setEnabledSurfaceSet, to avoid unsupported Jmix enum collection persistence."
  - "Preserve the existing agentstore includeAll changelog strategy and document that it picks up 080-ai-ui-settings.xml, avoiding a path-identity change for older Liquibase changesets."
  - "Use the included-build Gradle path :ai-agent:ai-agent:* for verification because the plan's :ai-agent:* shorthand is not a task in this checkout."

patterns-established:
  - "AiUiSettingsService.loadCurrent(): no cache, UnconstrainedDataManager load/save, DataManager.create for the entity instance, and reload after duplicate insert races."
  - "AiUiSettings stays hidden from LLM-visible metadata through AiInternalEntityNames."

requirements-completed: [SURF-01, SURF-02, SURF-06, SURF-07, SURF-10, ENT-06]

duration: 16 min
completed: 2026-05-01
---

# Phase 12 Plan 01: Configurable Chat Surface Foundation Summary

**Two-surface chat configuration foundation with AiUiSettings singleton persistence and no-cache Jmix loader service**

## Performance

- **Duration:** 16 min
- **Started:** 2026-05-01T20:21:27Z
- **Completed:** 2026-05-01T20:37:49Z
- **Tasks:** 3
- **Files modified:** 12

## Accomplishments

- Rewrote Phase 12 requirements around `FULL_ROUTE` and `HEADER_BUTTON`, with stale sidebar/floating/P-21/compact-mode work marked deferred or out of scope.
- Added `AiChatSurface` and `AiUiSettings` as Jmix model foundation for runtime chat-surface settings.
- Added `AI_UI_SETTINGS` Liquibase DDL, bilingual captions, and `AiInternalEntityNames` exclusion so settings never appear in LLM-visible metadata.
- Added `AiUiSettingsService.loadCurrent()` with lazy singleton creation, `UnconstrainedDataManager` persistence, and duplicate insert race recovery.

## Task Commits

1. **Task 1: Amend roadmap and requirements** - `ea04f30` (`docs`)
2. **Task 2 RED: Model contract test** - `f16be85` (`test`)
3. **Task 2 GREEN: Enum/entity/Liquibase/i18n model** - `9b9f016` (`feat`)
4. **Task 3 RED: Singleton service test** - `5cea9f7` (`test`)
5. **Task 3 GREEN: Singleton loader service** - `b243cff` (`feat`)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java` - two-value `EnumClass<String>` for `FULL_ROUTE` and `HEADER_BUTTON`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java` - singleton Jmix entity in `agentstore` with text-backed enabled surface ids and default surface.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java` - no-cache singleton loader/creator using `UnconstrainedDataManager`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/080-ai-ui-settings.xml` - creates `AI_UI_SETTINGS`.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java` - model and internal-exclusion regression coverage.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java` - singleton creation, repeated load, and concurrent first-load coverage.
- `.planning/ROADMAP.md` and `.planning/REQUIREMENTS.md` - active Phase 12 scope now matches the two-surface contract.

## Decisions Made

- `enabledSurfaceIds` is the persisted field name; the entity deliberately does not expose `getEnabledSurfaces()` / `setEnabledSurfaces(...)`.
- Parent changelog keeps `includeAll`; changing to explicit includes would risk changing existing changelog identities. The parent now explicitly documents that `080-ai-ui-settings.xml` is picked up.
- Verification uses `.\gradlew.bat :ai-agent:ai-agent:*` because Gradle reports `:ai-agent` as an included build and there is no `:ai-agent:test` task at the root.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Adjusted Gradle verification path for included build**
- **Found during:** Task 2 TDD RED
- **Issue:** The plan's `:ai-agent:test` / `:ai-agent:compileJava` task path is not present in this checkout; Gradle exposes the functional module as `:ai-agent:ai-agent:*` through an included build.
- **Fix:** Verified with `.\gradlew.bat projects` and used `:ai-agent:ai-agent:compileJava` plus focused `:ai-agent:ai-agent:test --tests ...`.
- **Files modified:** None
- **Verification:** `:ai-agent:ai-agent:compileJava` and focused tests passed.
- **Committed in:** N/A - command adjustment only

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Verification stayed equivalent to the intended module-scoped checks; no product scope changed.

## Issues Encountered

- JetBrains flagged expected Jmix accessor/future-use warnings on `AiUiSettings` before Task 3 consumed `SINGLETON_ID`; actionable warnings were fixed.
- Stub scan matched existing message-bundle keys containing `.placeholder`; these are real i18n keys, not stubs or mock data.

## Known Stubs

None - no placeholder/mock data was introduced. Existing `.placeholder` message keys are UI labels.

## Authentication Gates

None.

## Verification

- `.\gradlew.bat :ai-agent:ai-agent:compileJava` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.entity.AiUiSettingsModelTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.AiUiSettingsServiceSingletonTest"` - PASS
- JetBrains `get_file_problems` on new/modified Java and XML files - PASS for actionable issues; only skipped routine unused Jmix accessors and existing Liquibase HTTP schema URL weak warnings.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 12-02 can build the admin settings view and role policies on top of `AiUiSettingsService.loadCurrent()` and the `AiUiSettings` singleton model.

## Self-Check: PASSED

- Created files exist on disk.
- Task commits `ea04f30`, `f16be85`, `9b9f016`, `5cea9f7`, and `b243cff` exist in git history.

---
*Phase: 12-configurable-chat-surfaces*
*Completed: 2026-05-01*
