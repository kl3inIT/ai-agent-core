---
phase: 12-configurable-chat-surfaces
plan: 02
subsystem: ui-configuration
tags: [jmix, flow-ui, security, i18n, chat-surfaces]

requires:
  - phase: 12-configurable-chat-surfaces
    provides: AiUiSettings singleton entity, AiChatSurface enum, and AiUiSettingsService.loadCurrent()
provides:
  - Admin-only AiUiSettings singleton detail view
  - UI settings menu entry and AiAgentAdminRole view/menu/entity policies
  - Focused UI and security access tests for settings persistence and authorization
affects: [phase-12, admin-settings, chat-ui, security]

tech-stack:
  added:
    - io.jmix.flowui:jmix-flowui-test-assist
  patterns:
    - Jmix XML StandardDetailView over a singleton entity loaded through a service
    - Controller-managed enum surface fields mapped to AiUiSettings.enabledSurfaceIds helpers
    - AccessManager checks for both UiShowViewContext and UiMenuContext in security tests

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java

key-decisions:
  - "Use a singleton StandardDetailView that overrides entity lookup/setup and calls AiUiSettingsService.loadCurrent(), preventing arbitrary settings row creation."
  - "Keep enabled-surface UI controls controller-managed and persist only through getEnabledSurfaceSet/setEnabledSurfaceSet, avoiding a Jmix enum collection property."
  - "Grant AiUiSettings READ/UPDATE plus view/menu policies to AiAgentAdminRole, with no CREATE/DELETE policy because singleton creation stays service-internal."

patterns-established:
  - "Settings detail views for singleton add-on configuration should load through a service and expose only save/close actions."
  - "Admin-only Flow UI menu additions should be tested through both UiShowViewContext and UiMenuContext."

requirements-completed: [SURF-08, ENT-06, SEC-05]

duration: 23 min
completed: 2026-05-01
---

# Phase 12 Plan 02: Configurable Chat Surface Admin UI Summary

**Admin-only Jmix settings detail view for runtime chat-surface toggles with bilingual menu wiring and policy tests**

## Performance

- **Duration:** 23 min
- **Started:** 2026-05-01T20:48:44Z
- **Completed:** 2026-05-01T21:11:46Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- Added `AiUiSettingsDetailView` as a Jmix XML-backed `StandardDetailView<AiUiSettings>` that edits only the singleton settings row.
- Wired controller-managed `AiChatSurface` fields to the text-backed `enabledSurfaceIds` model helpers and rejected invalid settings before save.
- Added `aiAgent.uiSettings` menu wiring, bilingual root-bundle labels, and admin-only view/menu/entity policies.
- Extended access tests so admin users can open the settings view/menu and non-admin users cannot.
- Closed the AiUiSettings portion of `SEC-05` without granting create/delete access to the singleton settings entity.

## Task Commits

1. **Task 1 RED: Singleton settings detail view test** - `0c659b0` (`test`)
2. **Task 1 GREEN: Singleton settings detail view** - `4ad06b1` (`feat`)
3. **Task 2 RED: Settings access-policy tests** - `0d98717` (`test`)
4. **Task 2 GREEN: Menu and policy wiring** - `52e02d4` (`feat`)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java` - singleton settings detail controller with service-backed entity setup and save validation.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml` - XML descriptor with controller-managed enabled/default surface fields and save/close actions only.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java` - UI/controller persistence and validation coverage.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` - AiUiSettings READ/UPDATE entity policy, detail view policy, and menu policy.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` - `aiAgent.uiSettings` menu item near AI configuration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` - English view/menu/validation labels.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` - Vietnamese view/menu/validation labels.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java` - view and menu access assertions for admin/non-admin users.
- `ai-agent/ai-agent/ai-agent.gradle` - Jmix Flow UI test assist dependency for add-on UI tests.

## Decisions Made

- The settings view has no route entity id lookup; it always edits `AiUiSettings.SINGLETON_ID` through `AiUiSettingsService.loadCurrent()`.
- The XML field id can be `enabledSurfacesField`, but no Jmix/JPA `enabledSurfaces` property was introduced.
- `AiAgentAdminRole` grants no `CREATE`, `DELETE`, or `ALL` policy for `AiUiSettings`; the singleton row remains service-owned.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added missing Jmix Flow UI test assist dependency**
- **Found during:** Task 1 RED
- **Issue:** The new UI test needed Jmix Flow UI test assist classes that were not on the add-on test classpath.
- **Fix:** Added `io.jmix.flowui:jmix-flowui-test-assist` as a test dependency.
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
- **Verification:** Task 1 RED reached the intended missing-view failure, then Task 1 GREEN passed `AiUiSettingsDetailViewTest`.
- **Committed in:** `0c659b0`

**2. [Rule 2 - Missing Critical] Added minimum AiUiSettings security before menu wiring**
- **Found during:** Task 1 GREEN
- **Issue:** The settings save path is admin-only and required Jmix view/entity policy coverage to be correct and testable as soon as the view existed.
- **Fix:** Added `AiUiSettings` READ/UPDATE entity policy and `AiAgent_AiUiSettings.detail` view policy; Task 2 later added the menu policy and menu item.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`
- **Verification:** `AiUiSettingsDetailViewTest` and `AdminViewAccessTest` passed.
- **Committed in:** `4ad06b1`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 missing critical)
**Impact on plan:** Both were required for executable UI/security coverage. Product scope stayed within the admin settings surface described by the plan.

## Issues Encountered

- The plan's shorthand Gradle task path was executed with the repository's included-build path, `:ai-agent:ai-agent:test`, matching Phase 12 Plan 01's established verification decision.
- Task 2 was split into an explicit RED commit after the first local patch so the plan-level TDD contract remained auditable.

## TDD Gate Compliance

- RED gate present for Task 1: `0c659b0`
- GREEN gate present for Task 1: `4ad06b1`
- RED gate present for Task 2: `0d98717`
- GREEN gate present for Task 2: `52e02d4`

## Known Stubs

None - no placeholder/mock data was introduced. Stub scan only matched existing real `.placeholder` i18n keys and null validation guards.

## Threat Flags

None - the new admin view, menu, and security policies are the threat surfaces already covered in the plan threat model.

## Authentication Gates

None.

## Verification

- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.security.AdminViewAccessTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest" --tests "com.vn.agent.security.AdminViewAccessTest" --tests "com.vn.agent.i18n.*"` - PASS
- JetBrains `get_file_problems` on the controller, XML descriptor, role, and test files - PASS for actionable issues; skipped expected weak field-injection warnings in the Jmix view controller.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 12-03 can build `AiChatSessionState` and `ChatPanelFragment` continuity on top of persisted admin settings and a secured settings UI.

## Self-Check: PASSED

- Verified the summary file and all key created/modified files exist.
- Verified task commits `0c659b0`, `4ad06b1`, `0d98717`, and `52e02d4` exist in git history.

---
*Phase: 12-configurable-chat-surfaces*
*Completed: 2026-05-01*
