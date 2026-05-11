---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 03
subsystem: ui
tags: [jmix, vaadin-flow, chat-surface, fragment-hosting, css, i18n, security-roles]

# Dependency graph
requires:
  - phase: 15-right-sidebar-chat-surface-observability-ux
    provides: "AiChatSurface.SIDEBAR enum constant + en/vi labels + admin-selectable surface (Plan 01)"
  - phase: 12-configurable-chat-surfaces
    provides: "ChatSurfaceMounter, ChatPanelFragment, ChatDialogView, AiUiSettings, AiChatSessionState, AiChatUIState"
provides:
  - "AiAgentSidebarView (StandardView, @ViewController AiAgent_Sidebar) — lean Jmix host that owns the existing ChatPanelFragment for the SIDEBAR surface"
  - "ChatSurfaceMounter SIDEBAR machinery: createSidebarToggleButton / mountSidebarToggle / mountSidebarPanel / createSidebarPanel / toggleSidebar / shouldShowSidebar / isSidebarViewPermitted; SIDEBAR refresh on AfterNavigationEvent"
  - "Far-right navbar toggle (aiAgentSidebarToggleButton, VaadinIcon.PANEL, LUMO_TERTIARY+LUMO_ICON, aria-pressed + ai-agent-sidebar-toggle--active) + an in-panel closer, both via one toggleSidebar() method"
  - "@CssImport(./styles/ai-agent-chat.css) on ChatPanelFragment so .ai-agent-sidebar* (and the Plan-04 observability rules) load on every chat/sidebar render"
  - ".ai-agent-sidebar / .ai-agent-sidebar--open / .ai-agent-sidebar__header / .ai-agent-content--pushed / .ai-agent-sidebar-toggle--active CSS + small-device (≤768px) overlay media query, width clamp(640px, 32vw, 760px)"
  - "AiAgent_Sidebar view id added to AiAgentUserRole.userViews() + AiAgentAdminRole.adminViews()"
  - "msg:// keys (en + vi): chatSurfaceMounter.sidebarToggle.ariaLabel.open / .closed, chatSurfaceMounter.sidebarCloser.ariaLabel"
affects: [15-04, ChatSurfaceMounter, ChatPanelFragment, ai-agent-chat.css, observability-status-row]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Hosting a Jmix view in a non-routed, non-dialog context: views.create(ViewClass.class) + panelDiv.getElement().appendChild(view.getElement()) + ViewControllerUtils.fireEvent(new View.BeforeShowEvent(view)) then ViewControllerUtils.fireEvent(new View.ReadyEvent(view)) — the ReadyEvent propagates to nested fragments via Fragment.onHostReadyInternal, so the fragment's @Subscribe onReady runs; no DialogWindow overlay machinery (no modality curtain)."
    - "Surviving-navigation server-side affordance: attach to UI.getCurrent().getElement() (NOT the AppLayout content slot, which setContent()-replaces on every navigation) and re-assert idempotently on AfterNavigationEvent — same trick as attachDialogWindowToUi for the modeless HEADER_BUTTON dialog."
    - "Adding a third chat surface = a lean host StandardView reusing the existing ChatPanelFragment via XML (no new fragment subclass) + a navbar toggle + a fixed-position Div on the UI element; gated by AiUiSettings.getEnabledSurfaceSet() AND a UiShowViewContext permission check; the new view id added to both AI Agent roles."

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiAgentSidebarView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/ai-agent-sidebar-view.xml
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatPanelFragmentSurfaceSwitchTest.java
    - .gitignore

key-decisions:
  - "Host-view show mechanism: views.create(AiAgentSidebarView.class) + appendChild into the panel Div + ViewControllerUtils.fireEvent(BeforeShowEvent, then ReadyEvent) — chosen over wrapping in a DialogWindow because the DialogWindow renders a <vaadin-dialog> overlay (its element does not visibly live inside an arbitrary Div) and would bring modality/overlay machinery; the views.create + fireEvent path is the Jmix 2.8 way to drive a non-routed view's lifecycle, ReadyEvent propagates to the fragment's @Subscribe onReady, and there is no modal curtain so the main view stays interactive."
  - "Sidebar state (panelDiv / sidebarHostView / sidebarToggleButton / sidebarOpen) lives on the per-UI MountedChatSurfaceState (ComponentUtil.getData(ui, ...)) — NOT AiChatUIState (which keeps only the modeless DialogWindow) and NOT AiChatSessionState (which stays at currentConversationId + listeners). AiChatUIState.java is intentionally left untouched (no empty diff)."
  - "Disabled/not-permitted case: mount-always-then-setVisible(false), mirroring the existing magic header button (RESEARCH Open Q3). Vaadin setVisible(false) removes the element from the rendered DOM, so 'absent (not greyed)' holds; when the surface is disabled while open, the panel is collapsed and the push class dropped (mirrors syncDialogAvailability for the dialog)."
  - "Panel width clamp(640px, 32vw, 760px) and the matching .ai-agent-content--pushed padding-right; top var(--lumo-size-xl, 3.5rem); small-device breakpoint 768px (100vw overlay, push dropped) — per 15-REVIEWS point #4 (640px min ⇒ the fragment's 32%-width attachments pane gets ≈205px, matching the project's ~200px attachment-card note)."
  - "@CssImport(./styles/ai-agent-chat.css) added to ChatPanelFragment (REVIEWS point #5) — it previously sat only on the message-bubble component (used solely by ConversationDetailView), which the MessageList-based live chat/sidebar path never instantiates; the existing import on the bubble component stays (duplicate of the same path is harmless)."
  - "SURF-11 'same implementation + same ChatService / chat memory + AiChatSessionState continuity' framing: the sidebar reuses the existing ChatPanelFragment CLASS via XML (no new fragment subclass, no second fragment-class XML reference), backed by the singleton ChatService bean (and the chat memory ChatService owns). It does NOT require the same physical ChatPanelFragment object — the sidebar's fragment is a distinct instance hosted by AiAgentSidebarView, exactly like ChatDialogView already owns a distinct instance. Cross-surface continuity is via AiChatSessionState.currentConversationId only. Proven by ChatSurfaceMounterTest.sidebarUsesTheSameSingletonChatServiceAndExistingFragmentImplementation (exactly one ChatService bean; same fragment field type on AiAgentSidebarView and ChatDialogView; views.create(AiAgentSidebarView.class) in the mounter source) and ChatPanelFragmentSurfaceSwitchTest.sidebarSurfaceReusesSessionConversationIdForCrossSurfaceContinuity (a conversation set on AiChatSessionState is reflected after opening the sidebar view, and vice versa)."

patterns-established:
  - "view/chat: a lean per-surface host StandardView whose XML is just a single <fragment id=chatPanelFragment class=...ChatPanelFragment/> + a setConversationId-from-AiChatSessionState sync on show (BeforeShow + Ready) — mirrored across ChatDialogView and now AiAgentSidebarView."
  - "ChatSurfaceMounter: every navbar affordance is created via uiComponents.create(JmixButton.class) → setId → addClassName → setIcon(VaadinIcon.X.create()) → addThemeVariants(LUMO_TERTIARY, LUMO_ICON) → aria-* attributes → addClickListener; mounting is self-healing (re-find by id if the parent is gone, else appLayout.addToNavbar); a single toggle method owns the open/closed state mutation (push class + active class + aria-pressed + aria-label together)."

requirements-completed: [SURF-11]

# Metrics
duration: 95min
completed: 2026-05-11
---

# Phase 15 Plan 03: SIDEBAR Chat Surface Mount Summary

**The third chat surface — a non-modal, push-mode right `SIDEBAR` panel — mounted by `ChatSurfaceMounter` through a lean Jmix-owned `AiAgentSidebarView` that reuses the existing `ChatPanelFragment` (same singleton `ChatService`, same chat memory), with a distinct far-right navbar toggle + in-panel closer routed through one `toggleSidebar()` method, surviving route navigation on the UI element, absent (not greyed) when disabled/not-permitted; plus the `.ai-agent-sidebar*` CSS with a real `clamp(640px, 32vw, 760px)` width and the `@CssImport` moved onto `ChatPanelFragment`.**

## Performance

- **Duration:** ~95 min
- **Started:** 2026-05-11T22:35:00Z (approx)
- **Completed:** 2026-05-11T23:55:00Z (approx)
- **Tasks:** 2
- **Files modified:** 12 (2 created, 10 modified)

## Accomplishments
- `AiAgentSidebarView` + `ai-agent-sidebar-view.xml` — a `StandardView` (`@ViewController("AiAgent_Sidebar")`) whose only content is the existing `ChatPanelFragment` via XML; syncs `setConversationId` from `AiChatSessionState` on `BeforeShow`/`Ready` (mirrors `ChatDialogView`). No new fragment subclass.
- `ChatSurfaceMounter` extended: `SIDEBAR_*` constants; `createSidebarToggleButton(ui)` (mirrors `createChatButton()` with `VaadinIcon.PANEL`, `aiAgentSidebarToggleButton`, `aria-pressed`); `createSidebarPanel(ui, state)` (a `Div.ai-agent-sidebar` with a `Div.ai-agent-sidebar__header` close button + the `AiAgentSidebarView` element appended, lifecycle driven via `ViewControllerUtils.fireEvent(BeforeShowEvent → ReadyEvent)`); `mountSidebarToggle` / `mountSidebarPanel` (self-healing; the panel attaches to `UI.getCurrent().getElement()`); `toggleSidebar(ui)` (one path for the navbar toggle AND the in-panel closer — flips `--open` class, `ai-agent-content--pushed` on the AppLayout, `--active` on the toggle, `aria-pressed`, and the toggle's `aria-label`); `shouldShowSidebar(settings, sidebarViewPermitted)` = `getEnabledSurfaceSet().contains(SIDEBAR) && permitted`; `isSidebarViewPermitted()` = `UiShowViewContext("AiAgent_Sidebar")` + `accessManager.applyRegisteredConstraints`; `afterNavigation`/`refreshMountedSurfaces` re-assert toggle + panel attachment + push class + visibility every `AfterNavigationEvent`.
- `MountedChatSurfaceState` gained `sidebarToggleButton` / `sidebarPanelDiv` / `sidebarHostView` / `sidebarOpen`. `AiChatUIState.java` deliberately unchanged (no empty diff).
- `@CssImport("./styles/ai-agent-chat.css")` added to `ChatPanelFragment` (REVIEWS #5) — the only change to that file in this plan.
- `AiAgent_Sidebar` view id added to `AiAgentUserRole.userViews()` and `AiAgentAdminRole.adminViews()` (parity with `AiAgent_ChatDialog`).
- `ai-agent-chat.css`: `.ai-agent-sidebar` (position:fixed, right docked, `clamp(640px, 32vw, 760px)`, `display:none` by default, `top: var(--lumo-size-xl, 3.5rem)`), `.ai-agent-sidebar--open` (`display:flex` column), `.ai-agent-sidebar__header` (close-button row), the host-view fill rule, `.ai-agent-content--pushed` (`padding-right` matching the clamp), `.ai-agent-sidebar-toggle--active` (Lumo primary-10pct "pressed" state), and a `@media (max-width: 768px)` block collapsing the panel to a 100vw overlay and dropping the push. `--lumo-*` tokens throughout; no new `theme.json` / `frontend/themes/`. No `.ai-agent-status` rule (deferred to Plan 04).
- New aria-label `msg://` keys in `messages_en.properties` + `messages_vi.properties`: `chatSurfaceMounter.sidebarToggle.ariaLabel.open` / `.closed`, `chatSurfaceMounter.sidebarCloser.ariaLabel`.
- Tests: `ChatSurfaceMounterTest` +6 cases (closed-panel mount + toggle distinct from the magic button; toggle flips classes/aria + in-panel closer also closes; two distinct independent buttons; disabled ⇒ neither in rendered DOM; survives navigation between two routes keeping push + open state, no Dialog overlay added; same singleton `ChatService` + same fragment class — structural/source assertions). `ChatDialogViewTest` +1 case (sidebar host mirrors the dialog host; same `ChatPanelFragment` field type; `ChatDialogView` unchanged). `ChatPanelFragmentSurfaceSwitchTest` +1 case (SIDEBAR cross-surface `AiChatSessionState.currentConversationId` continuity, both directions).
- `git diff --stat` confirms no change to `chat-panel-fragment.xml`, no new `liquibase/**`, no `theme.json` / `frontend/themes/`; the only edit to `ChatPanelFragment.java` is the `@CssImport` line; `AiChatUIState.java` is unchanged; FULL_ROUTE menu logic + HEADER_BUTTON dialog logic untouched.

## Task Commits

1. **Task 1: Mount the SIDEBAR chat surface (host view + ChatSurfaceMounter + one toggle)** — `516d421` (feat) — combined RED/GREEN: the new tests reference `AiAgentSidebarView` / new `ChatSurfaceMounter` constants, so a standalone RED commit would not compile (precedent: 15-01 Task 1). Includes the new view, the mounter extension, the `@CssImport` move, the role updates, the locale keys, the extended tests, and the `.gitignore` entry for `hs_err_pid*`/`replay_pid*`.
2. **Task 2: Add the SIDEBAR panel + push-class + toggle-active CSS (real clamp width)** — `eccb0b8` (feat) — appended `.ai-agent-sidebar*` / `.ai-agent-content--pushed` / `.ai-agent-sidebar-toggle--active` + the `@media (max-width:768px)` overlay block to `ai-agent-chat.css`.

**Plan metadata:** final docs commit — this SUMMARY + STATE.md + ROADMAP.md + REQUIREMENTS.md.

## Files Created/Modified
- `view/chat/AiAgentSidebarView.java` (new) — lean `StandardView`, `@ViewController("AiAgent_Sidebar")`, composes `ChatPanelFragment`, syncs `setConversationId` from `AiChatSessionState` on show.
- `view/chat/ai-agent-sidebar-view.xml` (new) — `<view><layout width="100%" height="100%" padding="false" spacing="false"><fragment id="chatPanelFragment" class="com.vn.agent.view.chat.fragment.ChatPanelFragment"/></layout></view>`.
- `view/chat/ChatSurfaceMounter.java` — SIDEBAR mount/toggle/permission machinery; host view created via `views.create(AiAgentSidebarView.class)`; panel Div appended to `UI.getCurrent().getElement()`.
- `view/chat/fragment/ChatPanelFragment.java` — `@CssImport("./styles/ai-agent-chat.css")` added (only change).
- `security/AiAgentUserRole.java`, `security/AiAgentAdminRole.java` — `"AiAgent_Sidebar"` added to the `@ViewPolicy(viewIds=...)` lists.
- `META-INF/resources/frontend/styles/ai-agent-chat.css` — `.ai-agent-sidebar*` / `.ai-agent-content--pushed` / `.ai-agent-sidebar-toggle--active` rules + `@media (max-width:768px)` block.
- `messages_en.properties`, `messages_vi.properties` — `chatSurfaceMounter.sidebarToggle.ariaLabel.open` / `.closed`, `chatSurfaceMounter.sidebarCloser.ariaLabel`.
- `test/.../ChatSurfaceMounterTest.java`, `test/.../ChatDialogViewTest.java`, `test/.../ChatPanelFragmentSurfaceSwitchTest.java` — new SIDEBAR cases.
- `.gitignore` — `hs_err_pid*.log` / `replay_pid*.log`.

**New `msg://` keys:** `chatSurfaceMounter.sidebarToggle.ariaLabel.closed` (en: `Open AI chat sidebar`, vi: `Mở thanh bên trò chuyện AI`), `chatSurfaceMounter.sidebarToggle.ariaLabel.open` (en: `Close AI chat sidebar`, vi: `Đóng thanh bên trò chuyện AI`), `chatSurfaceMounter.sidebarCloser.ariaLabel` (en: `Close AI chat sidebar`, vi: `Đóng thanh bên trò chuyện AI`).

## Decisions Made
See the `key-decisions` frontmatter — host-view show mechanism (`views.create` + `appendChild` + `ViewControllerUtils.fireEvent(BeforeShow → Ready)`), sidebar state on the per-UI `MountedChatSurfaceState`, mount-always-then-`setVisible(false)` for the disabled case, the `clamp(640px, 32vw, 760px)` width / `768px` breakpoint / `var(--lumo-size-xl, 3.5rem)` top, the `@CssImport` placement on `ChatPanelFragment`, and the SURF-11 "same implementation + same `ChatService`/chat memory + `AiChatSessionState` continuity" framing with the two tests that prove it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Reworded the `@CssImport` block comment on `ChatPanelFragment` so it no longer contains the literal token `MessageBubbleComponent`**
- **Found during:** Task 1 (running `com.vn.agent.view.chat.*`).
- **Issue:** `NoticeRenderTest.chatPanelFragmentRendersNoticeAsPlainDivWithAttachmentNoticeClass` does a literal `doesNotContain("MessageBubbleComponent")` scan of `ChatPanelFragment.java` source (it scans comments too). My explanatory comment naming the old `@CssImport` host (`MessageBubbleComponent`) tripped it.
- **Fix:** Reworded the comment to "the message-bubble component (used solely by ConversationDetailView's read-only transcript replay)" — same meaning, no banned token. No behavior change.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*"` green.
- **Committed in:** `516d421` (Task 1 commit).

**2. [Rule 3 - Blocking] `.gitignore` entry for `hs_err_pid*.log` / `replay_pid*.log`**
- **Found during:** Task 1 (after running the chat test packages).
- **Issue:** The Gradle test worker occasionally exits with a non-zero code on shutdown and drops `hs_err_pid*.log` / `replay_pid*.log` JVM-crash dumps into `ai-agent/ai-agent/`. These are generated artifacts and must not be committed.
- **Fix:** Added the two patterns to `.gitignore` and deleted the dropped files. Re-running the affected test classes individually (`--rerun`) passes cleanly — the exit-code blip is a worker-shutdown flake, not a real test failure.
- **Files modified:** `.gitignore`
- **Committed in:** `516d421` (Task 1 commit).

---

**Total deviations:** 2 auto-fixed (1 in-scope test bug from a literal source-scan assertion, 1 housekeeping `.gitignore`). No scope creep; no architectural change; no `Rule 4` checkpoint.
**Impact on plan:** None on the plan's deliverables; both keep the suite green / the repo clean.

## Issues Encountered
- The Gradle `:ai-agent:ai-agent:test` worker intermittently exits with code 1 on shutdown (JVM `hs_err`/`replay` dumps appear), even when all tests pass. Re-running the affected classes with `--rerun` is green. Not caused by this plan's changes; noted for future runs.

## Verification Performed
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest" --tests "com.vn.agent.view.chat.ChatDialogViewTest" --tests "com.vn.agent.view.chat.ChatPanelFragmentSurfaceSwitchTest"` — green (the three plan-listed test classes, 25 tests).
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.view.uisettings.*" --tests "com.vn.agent.security.*" --tests "com.vn.agent.entity.AiUiSettings*"` — green (128 tests; chat-surface + ui-settings + security + ui-settings-model packages).
- `./gradlew :ai-agent:ai-agent:test` — green (full add-on module; no regression in the existing FULL_ROUTE / HEADER_BUTTON flows or any view-policy/role test).
- `git diff --stat HEAD~2 HEAD -- "*chat-panel-fragment.xml" "*liquibase*" "*theme.json" "*frontend/themes/*"` — empty (no change to the fragment XML, no DDL, no new theme machinery).
- `git diff HEAD~2 HEAD -- "*/AiChatUIState.java"` — empty (sidebar state lives on `MountedChatSurfaceState`).
- `messages_en.properties` and `messages_vi.properties` both contain the three new `chatSurfaceMounter.sidebar*` keys.

## User Setup Required
None — no external service configuration; no new dependency.

## Next Phase Readiness
- The `SIDEBAR` surface is mounted and the `@CssImport` is on `ChatPanelFragment` — Plan 04 (observability inside the fragment) can land its `.ai-agent-status` rules + `@keyframes` into the same `ai-agent-chat.css` (now loaded on every chat/sidebar render) and its status-row markup inside `ChatPanelFragment`.
- No blockers.

## Self-Check: PASSED

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiAgentSidebarView.java` — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/ai-agent-sidebar-view.xml` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java` contains `AiChatSurface.SIDEBAR` + `views.create(AiAgentSidebarView.class)` — FOUND
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/15-03-SUMMARY.md` — FOUND
- Commit `516d421` (Task 1) — FOUND
- Commit `eccb0b8` (Task 2) — FOUND

---
*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Completed: 2026-05-11*
