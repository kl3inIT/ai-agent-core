---
phase: 12-configurable-chat-surfaces
plan: 04
subsystem: ui
tags: [jmix, vaadin, flow-ui, chat, security]

requires:
  - phase: 12-configurable-chat-surfaces
    provides: AiUiSettings, AiChatSessionState, AiChatUIState, shared ChatPanelFragment state
provides:
  - Header-button chat surface mounted into host AppLayout navbar
  - Non-modal Jmix DialogWindow chat shell using ChatPanelFragment
  - FULL_ROUTE disable gate for menu visibility and direct ChatView navigation
affects: [phase-12-chat-surfaces, phase-13-chat-task-input]

tech-stack:
  added: []
  patterns:
    - VaadinServiceInitListener AppLayout mounting with AfterNavigation refresh
    - UI-level Jmix DialogWindow chat shell using Views-created StandardView
    - UiShowViewContext-gated header button visibility

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-dialog-view.xml
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "Use a UI-level Jmix DialogWindow created from Views for ChatDialogView because DialogWindows.view(parentView, ...) was proven parent-navigation-bound in the executable survival test."
  - "Use ObjectProvider<AiChatUIState> from the singleton mounter so UI-scoped dialog state is resolved only inside an active UI scope."
  - "Use Jmix Messages in ChatView while adding the FULL_ROUTE disabled notification, avoiding new Spring MessageSource usage."
  - "Override Jmix's inherited View.beforeEnter() for route forwarding and delegate allowed navigation to super.beforeEnter(event) so QueryParametersChangeEvent and BeforeShowEvent still fire."

patterns-established:
  - "Header surface visibility = HEADER_BUTTON enabled + UiShowViewContext(AiAgent_ChatDialog) permitted + current route not AiAgent_Chat."
  - "Route navigation refresh re-loads AiUiSettings and reattaches any remembered dialog handle to the current UI."

requirements-completed: [SURF-01, SURF-03, SURF-04, SURF-05, SURF-06, SURF-07]

duration: 38 min
completed: 2026-05-02
---

# Phase 12 Plan 04: Header Chat Surface Summary

**Header-button chat surface with a non-modal Jmix DialogWindow, permission-gated AppLayout mounting, and FULL_ROUTE disable enforcement**

## Performance

- **Duration:** 38 min
- **Started:** 2026-05-01T21:30:16Z
- **Completed:** 2026-05-01T22:07:50Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments

- Added `ChatDialogView`, a no-route `StandardView` XML shell around the shared `ChatPanelFragment`.
- Added `ChatSurfaceMounter`, which mounts one header chat button into `AppLayout`, gates it with `AiUiSettings` and `UiShowViewContext`, and opens/closes a non-modal Jmix dialog.
- Added the FULL_ROUTE gate in `ChatView`, including localized notification text and menu/header visibility coverage.

## Task Commits

1. **Task 1 RED:** `878199d` test(12-04): add failing test for chat dialog shell
2. **Task 1 GREEN:** `9f22763` feat(12-04): add chat dialog shell
3. **Task 2 RED:** `6ae354d` test(12-04): add failing test for chat surface mounter
4. **Task 2 GREEN:** `cf3aa5d` feat(12-04): mount header chat dialog surface
5. **Task 3 RED:** `21d8513` test(12-04): add failing test for disabled full chat route
6. **Task 3 GREEN:** `f79d21b` feat(12-04): gate full chat route by UI settings
7. **Lifecycle fix:** `dc07890` fix(12-04): preserve jmix before-enter lifecycle

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java` - Dialog shell around `ChatPanelFragment`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-dialog-view.xml` - Jmix XML descriptor composing the chat fragment and shell buttons.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java` - AppLayout header-button mounter and dialog toggle coordinator.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java` - FULL_ROUTE before-enter gate and Jmix `Messages` notification usage.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java` - User-facing role now grants `AiAgent_ChatDialog`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` - English dialog/button/route-gate messages.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` - Vietnamese dialog/button/route-gate messages.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java` - Dialog shell contract tests.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java` - Mounting, permission, dialog toggle/survival, and route-gate tests.

## Decisions Made

- Use `Views.create(ChatDialogView.class)` plus direct Jmix `DialogWindow` construction for the header dialog. The test proved the normal parent-view builder path is closed during route navigation in this harness; UI-level attachment preserves the dialog handle and fragment conversation state.
- Resolve `AiChatUIState` through `ObjectProvider` inside the singleton mounter, since direct UI-scope injection into a singleton fails outside an active UI scope.
- Preserve Java-17-compatible `list.get(0)` in tests despite JetBrains suggesting `getFirst()`, matching the standing Phase 11 decision.
- Keep route forwarding in `beforeEnter()` because Jmix `BeforeShowEvent` cannot change the Vaadin navigation target, but delegate the enabled path to `super.beforeEnter(event)` to preserve Jmix view lifecycle events.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Removed invalid fragment sizing attributes**
- **Found during:** Task 1 (Add ChatDialogView shell)
- **Issue:** JetBrains reported `width` and `height` were not valid on the `<fragment>` element in `chat-dialog-view.xml`.
- **Fix:** Removed the invalid attributes and left sizing to the dialog window settings as required by the plan.
- **Files modified:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-dialog-view.xml`
- **Verification:** JetBrains XML inspection clean; `ChatDialogViewTest` passed.
- **Committed in:** `9f22763`

**2. [Rule 3 - Blocking] Resolved UI-scoped state injection from singleton mounter**
- **Found during:** Task 2 (Add ChatSurfaceMounter and dialog toggle)
- **Issue:** Spring failed to load the test context because singleton `ChatSurfaceMounter` directly injected `@UIScope` `AiChatUIState`.
- **Fix:** Switched the mounter to `ObjectProvider<AiChatUIState>` and resolved the UI-scoped state only during UI click/navigation handling.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java`
- **Verification:** `ChatSurfaceMounterTest` passed.
- **Committed in:** `cf3aa5d`

**3. [Rule 1 - Bug] Made dialog route-survival executable**
- **Found during:** Task 2 (Add ChatSurfaceMounter and dialog toggle)
- **Issue:** The executable navigation test proved a parent-view dialog builder path was closed during route navigation.
- **Fix:** Created the dialog view through `Views`, wrapped it in a Jmix `DialogWindow`, attached the window element to the UI, and reattached remembered handles after navigation.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java`
- **Verification:** `ChatSurfaceMounterTest.dialogWindowSurvivesRouteNavigationWithConversationState` passed.
- **Committed in:** `cf3aa5d`

**4. [Rule 1 - Bug] Preserved Jmix before-enter lifecycle**
- **Found during:** user review after Task 3
- **Issue:** `ChatView` redundantly implemented Vaadin `BeforeEnterObserver` and the enabled route path did not call `super.beforeEnter(event)`, which would bypass Jmix `QueryParametersChangeEvent` and `BeforeShowEvent`.
- **Fix:** Removed the raw observer declaration, kept the route-blocking short-circuit for disabled FULL_ROUTE, and delegated enabled navigation to `super.beforeEnter(event)`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java`
- **Verification:** `ChatSurfaceMounterTest` and `ChatViewStreamTest` passed.
- **Committed in:** `dc07890`

---

**Total deviations:** 4 auto-fixed (3 bug, 1 blocking)
**Impact on plan:** All fixes were required to satisfy the plan invariants. No extra user-facing scope was added.

## Issues Encountered

- Used the composite Gradle path `:ai-agent:ai-agent:*` for verification, matching prior Phase 12 state. The plan text uses `:ai-agent:*`, but this checkout exposes the functional module through the included-build path.
- JetBrains remaining weak warnings are accepted: existing Jmix view field injection style, duplicated confirmation code between route/dialog shells, stylistic functional rewrite suggestion, and Java-17-compatible `List.get(0)` in tests.

## Known Stubs

None - no placeholder UI/data stubs were introduced.

## Verification

- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatDialogViewTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.security.*AccessTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatViewStreamTest"` - PASS
- `.\gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest" --tests "com.vn.agent.view.chat.ChatViewStreamTest"` - PASS after lifecycle fix
- `.\gradlew.bat :ai-agent:ai-agent:compileJava` - PASS
- JetBrains MCP `get_file_problems` - no errors on touched Java/XML files; accepted weak warnings listed above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 12-05. The two enabled chat surfaces now share session conversation state, and the route/menu/header visibility gates are enforced from `AiUiSettings`.

---
*Phase: 12-configurable-chat-surfaces*
*Completed: 2026-05-02*
