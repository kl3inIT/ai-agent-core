---
slug: nosuchviewexception-view-ai-ai
status: resolved
trigger: |
  <!-- DATA_START -->
  NoSuchViewException: View 'ai_AiConversation.detail' is not defined fix this
  <!-- DATA_END -->
created: 2026-04-22T14:37:28Z
updated: 2026-04-22T16:05:00Z
---

# Debug Session: nosuchviewexception-view-ai-ai

## Symptoms

- **Expected:** Double-clicking a row in `AiAgent_Conversation.list` opens the conversation detail view.
- **Actual:** Jmix throws `NoSuchViewException: View 'ai_AiConversation.detail' is not defined`.
- **Error type:** NoSuchViewException.
- **Timeline:** Reported 2026-04-22.
- **Reproduction:** Navigate to conversations list, double-click a row.

## Current Focus

reasoning_checkpoint:
  hypothesis: "ViewNavigators.detailView(this, AiConversation.class) asks Jmix to auto-resolve the primary detail view for the entity by metaClass+suffix → 'ai_AiConversation.detail'. The actual @ViewController id is 'AiAgent_Conversation.detail'. No match → NoSuchViewException."
  confirming_evidence:
    - "ConversationListView.java:96 calls viewNavigators.detailView(this, AiConversation.class).editEntity(row).navigate() — no explicit withViewClass()."
    - "ConversationDetailView.java:47 registers @ViewController(\"AiAgent_Conversation.detail\")."
    - "AiConversation entity name is 'ai_AiConversation' (entity/AiConversation.java:17), matching the error's expected view id prefix."
    - "menu.xml, AiAgentUserRole, AiAgentAdminRole, AdminViewAccessTest all reference 'AiAgent_Conversation.*' — view id is correct by project convention; only the navigation call is missing the explicit class."
  falsification_test: "Add .withViewClass(ConversationDetailView.class) to the navigator call; double-click a row; if NoSuchViewException disappears and detail opens, hypothesis confirmed."
  fix_rationale: "Explicit view class skips metaClass-based auto-resolution. Safer than renaming the view id because menus, roles, and security tests all reference 'AiAgent_Conversation.detail'."
  blind_spots: "Runtime UAT still required (cannot verify browser navigation in this session)."

## Evidence

- 2026-04-22 — Session created from user-reported exception.
- 2026-04-22 — ConversationListView.java:93-100 uses `viewNavigators.detailView(this, AiConversation.class).editEntity(row).navigate()` without explicit view class → triggers entity-name-based auto-resolution.
- 2026-04-22 — ConversationDetailView registered as `AiAgent_Conversation.detail` (line 47). Entity @Entity name is `ai_AiConversation` (entity/AiConversation.java:17). Auto-resolution looks for `{entityName}.detail` = `ai_AiConversation.detail` which does not exist → NoSuchViewException.
- 2026-04-22 — `AiAgent_Conversation.detail` id is referenced in menu.xml, AiAgentUserRole, AiAgentAdminRole, AdminViewAccessTest. Renaming the id would cascade. Explicit `withViewClass` is the minimal, safe fix.
- 2026-04-22 — After adding `withViewClass`, runtime raised `IllegalStateException: class com.vn.agent.view.conversation.ConversationDetailView does not declare @EditedEntityContainer`.
- 2026-04-22 — Context7 Jmix docs confirm `StandardDetailView` must declare `@EditedEntityContainer("<instanceDcId>")`.
- 2026-04-22 — Added `@EditedEntityContainer("conversationDc")` to `ConversationDetailView`; module compile passes (`:ai-agent:ai-agent:compileJava`).

## Eliminated

- (none)

## Resolution

root_cause: |
  Two issues combined:
  1) ConversationListView navigated via entity-based convention without explicit view class, so Jmix tried `ai_AiConversation.detail` while the registered view id is `AiAgent_Conversation.detail`.
  2) After switching to explicit class navigation, ConversationDetailView lacked `@EditedEntityContainer("conversationDc")`, which `StandardDetailView` requires for edit-entity navigation.
fix: |
  1) Add `.withViewClass(ConversationDetailView.class)` in ConversationListView double-click navigation.
  2) Add `@EditedEntityContainer("conversationDc")` to ConversationDetailView class.
verification: Confirmed fixed by user on 2026-04-22 after UAT.
files_changed:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java
