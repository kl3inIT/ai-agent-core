---
phase: 12-configurable-chat-surfaces
plan: 06
subsystem: testing
tags: [jmix, flow-ui, spring-ai, jdbc-memory, uat, i18n]

requires:
  - phase: 12-configurable-chat-surfaces
    provides: Plans 12-01 through 12-05 configurable chat surfaces, session state, dialog surface, and title service
provides:
  - TEST-14 route-to-dialog continuity gate with direct Spring AI JDBC memory assertions
  - Hardened settings, mounter, title, and locale regression tests
  - Phase 12 human UAT checklist
affects: [phase-12, phase-13, chat-surfaces, conversation-title, ui-settings]

tech-stack:
  added: []
  patterns:
    - Jmix @UiTest continuity gates using ObjectProvider for Vaadin scoped beans
    - Direct JdbcChatMemoryRepository assertions paired with AiMessage projection parity
    - Phase-specific i18n key inventory tests

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatPanelFragmentSurfaceSwitchTest.java
    - .planning/phases/12-configurable-chat-surfaces/12-UAT.md
  modified:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java

key-decisions:
  - "Use the included-build Gradle path :ai-agent:ai-agent:test for Plan 12-06 verification; the root checkout has no :ai-agent:test task."
  - "Keep Java 17-compatible List.get(0)/remove(0) test code despite JetBrains getFirst/removeFirst suggestions."

patterns-established:
  - "Cross-surface continuity tests must assert both JdbcChatMemoryRepository rows and AiMessage projection rows for the same conversation id."
  - "Vaadin scoped beans in @UiTest classes should be obtained through ObjectProvider inside the active UI/session scope."

requirements-completed:
  - SURF-09
  - TEST-14
  - SURF-01
  - SURF-03
  - SURF-04
  - SURF-05
  - SURF-08

duration: 29 min
completed: 2026-05-02
---

# Phase 12 Plan 06: Cross-Surface Test and UAT Summary

**Cross-surface chat continuity is now gated by direct JDBC memory assertions, hardened surface/title/i18n tests, and a human UAT checklist.**

## Performance

- **Duration:** 29 min
- **Started:** 2026-05-02T08:36:53Z
- **Completed:** 2026-05-02T09:05:38Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added `ChatPanelFragmentSurfaceSwitchTest` for TEST-14: full route to header dialog, same UUID, one `AiConversation`, four projected `AiMessage` rows, and four direct Spring AI JDBC memory rows.
- Hardened mounter/settings, title, and locale tests for independent surface toggles, dialog policy access, disabled title property gating, and explicit Phase 12 message key parity.
- Created `12-UAT.md` with admin/user/manual title and audit checks mapped back to Phase 12 success criteria.

## Task Commits

1. **Task 1: Add TEST-14 cross-surface continuity test** - `0a90491` (test)
2. **Task 2: Harden settings, mounter, title, and locale tests** - `baf03e0` (test)
3. **Task 3: Write Phase 12 UAT checklist and run module test gate** - `236b75e` (docs)

**Plan metadata:** pending final docs commit

## Files Created/Modified

- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatPanelFragmentSurfaceSwitchTest.java` - TEST-14 continuity gate with route/dialog mount, direct JDBC memory assertions, projection parity, and deterministic cleanup.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java` - Added independent full-route menu hiding coverage while preserving header-button visibility checks.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java` - Added disabled-property bean gate coverage for title generation.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java` - Added explicit Phase 12 key inventory in both root locale bundles.
- `.planning/phases/12-configurable-chat-surfaces/12-UAT.md` - Manual admin/user/title/audit checklist for Phase 12.

## Decisions Made

- Used `:ai-agent:ai-agent:test` for all Gradle verification because the repository exposes the functional module through an included build; `:ai-agent:test` is not a valid root project path here.
- Kept Java 17-compatible `List.get(0)` and `remove(0)` in tests; JetBrains suggested Java 21 convenience methods, but project state already records this compatibility choice.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Resolved stale read-first test support path**
- **Found during:** Task 1
- **Issue:** The plan referenced `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/AuthenticatedAsAdmin.java`, but that file does not exist in the current codebase.
- **Fix:** Used the current `TestUsersConfiguration` plus `SystemAuthenticator` pattern already present in Phase 12 tests.
- **Files modified:** None for this adjustment.
- **Verification:** Existing and new `@UiTest` classes run under `alice`/`admin` test users.
- **Committed in:** `0a90491`

**2. [Rule 1 - Bug] Avoided Vaadin scoped bean injection outside active UI scope**
- **Found during:** Task 1
- **Issue:** The first TEST-14 run failed because `AiChatSessionState` / `AiChatUIState` were autowired directly into the test instance before a Vaadin session was bound.
- **Fix:** Injected `ObjectProvider` and resolved the scoped beans inside the active `@UiTest` execution.
- **Files modified:** `ChatPanelFragmentSurfaceSwitchTest.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatPanelFragmentSurfaceSwitchTest"` passed.
- **Committed in:** `0a90491`

**3. [Rule 1 - Bug] Made TEST-14 cleanup audit-safe**
- **Found during:** Task 1
- **Issue:** Cleanup attempted to remove `AiConversation` rows before chat audit rows, causing an HSQL foreign-key violation.
- **Fix:** Removed root `AiAuditEvent` rows before removing conversations and JDBC memory rows.
- **Files modified:** `ChatPanelFragmentSurfaceSwitchTest.java`
- **Verification:** TEST-14 passed repeatedly after cleanup fix.
- **Committed in:** `0a90491`

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking issue)
**Impact on plan:** All fixes were test-harness corrections required to make the planned gates deterministic. No product behavior was added.

## Issues Encountered

- Task-level TDD red/green could not be demonstrated as a pre-implementation failure because Plans 12-03 and 12-04 were already committed prerequisites. The new gates passed against the existing implementation after test-harness fixes.
- JetBrains reported `getFirst()` / `removeFirst()` suggestions in tests. These were skipped per the recorded Phase 11/12 Java 17 compatibility decision.

## Verification

- PASS: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatPanelFragmentSurfaceSwitchTest"`
- PASS: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest" --tests "com.vn.agent.conversation.AiConversationTitleServiceTest" --tests "com.vn.agent.i18n.LocaleParityTest"`
- PASS: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.conversation.*" --tests "com.vn.agent.security.AdminViewAccessTest" --tests "com.vn.agent.i18n.*"`
- PASS: `./gradlew :ai-agent:ai-agent:test`
- PASS: JetBrains `get_file_problems` on modified Java test files reported no errors; remaining warnings were Java 17 compatibility suggestions only.

## Known Stubs

None. Stub-pattern scan found only intentional Mockito/test-state checks, not UI placeholder data.

## Threat Flags

None. This plan added tests and UAT documentation only; no new runtime endpoint, auth path, file access, or schema trust boundary was introduced.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Phase 12 is complete from the automated gate perspective. Manual browser UAT is documented in `12-UAT.md`; Phase 13 can build on the shared chat fragment and surface continuity guarantees.

## Self-Check: PASSED

- Found summary, UAT checklist, and all created/modified Java test files.
- Found task commits `0a90491`, `baf03e0`, and `236b75e`.
- Confirmed no unexpected tracked deletions were introduced by Plan 12-06 task commits.

---
*Phase: 12-configurable-chat-surfaces*
*Completed: 2026-05-02*
