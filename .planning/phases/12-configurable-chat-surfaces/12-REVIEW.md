---
phase: 12-configurable-chat-surfaces
reviewed: 2026-05-02T09:32:31Z
depth: standard
files_reviewed: 43
files_reviewed_list:
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiAgentTitleProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/ConversationTitleEligibilityPublisher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/ConversationTitleEligibleEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiChatSessionState.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiChatUIState.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/080-ai-ui-settings.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-dialog-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiChatSessionStateTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatPanelFragmentSurfaceSwitchTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatSurfaceMounterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentConversationIdTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java
findings:
  critical: 3
  warning: 2
  info: 0
  total: 5
status: issues_found
---

# Phase 12: Code Review Report

**Reviewed:** 2026-05-02T09:32:31Z
**Depth:** standard
**Files Reviewed:** 43
**Status:** issues_found

## Summary

Reviewed the Phase 12 chat-surface implementation, settings entity/view, security policies, title-generation path, resources, and related tests. The main risks are in surface lifecycle/security parity: open header dialogs are not closed when the surface becomes unavailable, and the admin role does not grant the new dialog view. The auto-title sanitizer also misses a locked sentinel rejection case.

## Critical Issues

### CR-01: Open Chat Dialog Survives Disabled Or Conflicting Surface State

**Severity:** BLOCKER
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java:93`
**Issue:** `afterNavigation()` reloads `AiUiSettings` and computes whether the current route is `AiAgent_Chat`, but it only hides the header button and then unconditionally calls `reattachOpenDialogToUi()`. An already-open `HEADER_BUTTON` dialog therefore remains active after an admin disables `HEADER_BUTTON`, after permissions no longer allow `AiAgent_ChatDialog`, or after the user navigates to the full chat route. This violates Phase 12's single-active-surface and disabled-surface contracts and can leave two `ChatPanelFragment` instances mounted in the same UI.
**Fix:**
```java
private void afterNavigation(UI ui, AfterNavigationEvent event) {
    AiUiSettings settings = uiSettingsService.loadCurrent();
    boolean fullChatRoute = isFullChatRoute(event);
    boolean dialogPermitted = isDialogViewPermitted();

    MountedChatSurfaceState mountedState = mountedState(ui);
    mountHeaderButton(ui, mountedState, true);
    refreshMountedSurfaces(ui, settings, fullChatRoute);

    if (shouldShowHeaderButton(settings, dialogPermitted, fullChatRoute)) {
        reattachOpenDialogToUi();
    } else {
        closeAndClearDialogInstance();
    }
}
```
Add coverage for: open dialog -> navigate to `ChatView`; open dialog -> disable `HEADER_BUTTON` -> navigate; open dialog -> permission denied refresh.

### CR-02: Admin Role Cannot Open The Header Chat Dialog

**Severity:** BLOCKER
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java:47`
**Issue:** `ChatSurfaceMounter` gates the header button with `UiShowViewContext("AiAgent_ChatDialog")`, but `AiAgentAdminRole.adminViews()` does not include `AiAgent_ChatDialog`. An account with only the AI admin role can access `AiAgent_Chat` and configure `AiUiSettings`, but the new header dialog surface is denied by the same permission gate that controls button visibility.
**Fix:**
```java
@ViewPolicy(viewIds = {
        "AiAgent_Chat",
        "AiAgent_ChatDialog",
        "AiAgent_Configuration",
        // existing entries...
})
void adminViews();
```
Extend `AdminViewAccessTest.allowsAdminViewsForAdmin()` to assert `permittedFor("admin", "AiAgent_ChatDialog")`.

### CR-03: Auto-Title Sanitizer Accepts The Default Sentinel

**Severity:** BLOCKER
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java:223`
**Issue:** Phase 12 requires generated titles to reject the `NEW_CONVERSATION` sentinel, but `sanitizeTitle()` only rejects blank output, the current first-message default, and a small internal-token list. A model response of `NEW_CONVERSATION` would be persisted and audited as a successful generated title.
**Fix:**
```java
if ("NEW_CONVERSATION".equalsIgnoreCase(title)) {
    throw new IllegalArgumentException("default sentinel");
}
```
Add a unit test beside `sanitizerRejectionDoesNotPersistAndAuditsError()` for `NEW_CONVERSATION`, including quoted/trailing-period variants after normalization.

## Warnings

### WR-01: Dialog Handle Is Not Cleared For Non-Button Close Paths

**Severity:** WARNING
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java:170`
**Issue:** `openDialog()` stores the `DialogWindow` in `AiChatUIState`, but cleanup only happens through the header-button toggle and `ChatDialogView`'s custom `closeButton` handler. If the dialog is closed through any standard dialog close path such as Esc, an overlay close affordance, detach, or server-side close, the UI-scoped handle remains stale. The next header click will close the stale handle and return instead of opening a fresh dialog.
**Fix:** Register a dialog close listener where the handle is stored, and make the close-button handler rely on the same cleanup path.
```java
chatUIState.setDialogInstance(dialogWindow);
dialogWindow.addAfterCloseListener(event -> chatUIState.clearDialogInstance());
```
Add a test that closes the `DialogWindow` directly and then verifies the next header click opens a new dialog on the first click.

### WR-02: AiUiSettings Audit Columns Are Never Populated

**Severity:** WARNING
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java:51`
**Issue:** `080-ai-ui-settings.xml` creates `CREATED_BY`, `CREATED_DATE`, `LAST_MODIFIED_BY`, and `LAST_MODIFIED_DATE`, and the entity exposes matching fields, but the fields lack Jmix audit annotations. As written, singleton creation and admin settings updates will leave those columns null, weakening operational traceability for surface-rollout changes.
**Fix:**
```java
@CreatedBy
@Column(name = "CREATED_BY")
private String createdBy;

@CreatedDate
@Column(name = "CREATED_DATE")
private OffsetDateTime createdDate;

@LastModifiedBy
@Column(name = "LAST_MODIFIED_BY")
private String lastModifiedBy;

@LastModifiedDate
@Column(name = "LAST_MODIFIED_DATE")
private OffsetDateTime lastModifiedDate;
```
Add an integration assertion that `loadCurrent()` creation and an admin update populate the expected audit fields.

---

_Reviewed: 2026-05-02T09:32:31Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
