---
phase: 12
reviewers: [codex]
reviewed_at: 2026-05-02T03:15:33.5277007+07:00
plans_reviewed:
  - .planning/phases/12-configurable-chat-surfaces/12-01-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-02-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-03-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-04-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-05-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-06-PLAN.md
---

# Cross-AI Plan Review - Phase 12

## Codex Review

### Summary

The dac3d59 replan closes the three previous HIGH concerns with concrete artifacts and acceptance gates. The plans now avoid the unsupported enum-collection trap, add a named bounded title executor, and make TEST-14 require direct Spring AI JDBC memory verification instead of a proxy. I do not see any unresolved current HIGH concerns. Remaining risks are implementation-level, mostly around Jmix/Vaadin lifecycle details and test brittleness.

### Strengths

- `enabledSurfaceIds` is now a text-backed entity field, with `getEnabledSurfaceSet()` helpers and explicit bans on `getEnabledSurfaces()` / direct XML binding.
- Auto-title async execution now has a concrete `AIConfiguration` artifact, named `aiAgentTitleExecutor`, bounded queue/pool settings, and `@Async("aiAgentTitleExecutor")`.
- TEST-14 now requires direct `JdbcChatMemoryRepository.findByConversationId(...)` assertions, with `AiMessage` parity only as supplemental coverage.
- The two-surface scope remains clean: `FULL_ROUTE` + `HEADER_BUTTON`, no sidebar, raw floating launcher, P-21 mitigation, or compact-mode creep.
- Security checks are planned at the right layers: admin settings policies, chat-dialog view policy for normal users, `UiShowViewContext`, route gate, and menu/button visibility.
- Dialog survival across navigation is not assumed; `12-04` and `12-06` require executable proof.

### Concerns

- **MEDIUM - `12-05`: title-client isolation is specified but not strongly acceptance-tested.** The plan says no tools/advisors, but acceptance does not require a test proving title generation cannot inherit chat advisors/tool callbacks/memory. This is worth adding because title calls run on user/assistant content.
- **MEDIUM - `12-04`: UI-level dialog attachment remains somewhat implementation-dependent.** The plan correctly requires a route-navigation survival test, but the implementation instruction still says `DialogWindows.view(currentViewOrUiContext, ...)`. If parent-bound dialogs fail the test, execution must stop and adjust the mounting strategy, not paper over it.
- **MEDIUM - `12-05`: manual title-edit authorization remains under-tested.** The plan says save through secured `DataManager` only if the user owns/can access the conversation, but should add an explicit non-admin own-conversation and other-user-conversation test.
- **LOW - `12-03`: listener cleanup could use one more stale-UI assertion.** The plan covers unregister-on-detach, but a test proving detached UI callbacks are ignored would harden the cross-tab listener path.
- **LOW - `12-01` / `12-02`: malformed persisted `enabledSurfaceIds` handling is not explicit.** Admin UI validation covers normal flow, but service/mounter behavior for corrupted DB values should fail to safe defaults or reject clearly.

### Suggestions

- Add a title-service test that verifies no tool callbacks, advisors, RAG, or chat memory are invoked during title generation.
- In `12-04`, make the dialog survival test a hard gate before accepting the mounter implementation.
- Add non-admin title-edit authorization tests: own conversation succeeds, another user's conversation is denied/opaque.
- Add a small `AiUiSettings` parsing test for unknown/blank surface ids.
- Add a detached-listener test for `AiChatSessionState` or the fragment integration test.

### Risk Assessment

**Overall risk: MEDIUM.** The plan architecture is sound and the prior HIGHs are resolved. Remaining risk comes from Vaadin/Jmix lifecycle behavior, async event timing, and UI test reliability rather than missing phase-level design.

### Current HIGH Count

**0**

---

## Consensus Summary

Only the Codex reviewer was invoked for this cycle because the requested workflow flags were `--phase 12 --codex`.

### Agreed Strengths

- The dac3d59 replan resolves all three previous HIGH concerns with explicit plan artifacts and verification gates.
- The implementation scope remains aligned to the locked two-surface Phase 12 target: `FULL_ROUTE` and `HEADER_BUTTON`.
- The remaining issues are implementation hardening items rather than unresolved phase-blocking design gaps.

### Agreed Concerns

- Title generation should be tested to ensure it does not inherit chat tools, advisors, RAG, or memory.
- Dialog attachment and route-navigation survival must remain a hard executable gate during Plan 12-04.
- Manual title-edit authorization should be covered with non-admin own-conversation and other-user denial tests.

### Divergent Views

- None. This cycle used one requested reviewer.
