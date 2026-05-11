---
phase: 12-configurable-chat-surfaces
plan: 05
subsystem: ui
tags: [jmix, flow-ui, spring-ai, async, conversation-title]

requires:
  - phase: 12-configurable-chat-surfaces
    provides: Chat panel fragment, chat surfaces, and conversation runtime from Plans 12-01 through 12-04
provides:
  - Fail-silent async conversation title generation after the first assistant reply
  - Manual pencil-edit title override in ChatPanelFragment
  - Hidden attachmentsPanel layout slot reserved for Phase 13
affects: [phase-12, phase-13, chat-ui, chat-runtime, audit]

tech-stack:
  added: [spring-ai-openai]
  patterns:
    - Named bounded ThreadPoolTaskExecutor for optional async model side jobs
    - Post-response title eligibility publisher with AFTER_COMMIT-capable listener
    - Jmix Dialogs input flow for manual chat title editing

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiAgentTitleProperties.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/ConversationTitleEligibilityPublisher.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/ConversationTitleEligibleEvent.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
    - ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentConversationIdTest.java

key-decisions:
  - "Use a narrow ConversationTitleEligibilityPublisher so DefaultChatServiceImpl remains responsible only for invoking title eligibility after assistant response handling returns."
  - "Keep title generation fail-silent and optional: bounded aiAgentTitleExecutor, conditional property, audit SUCCESS/ERROR, and no title failure rethrow into chat."
  - "Use ConversationGateway for manual title ownership checks, then secured DataManager.save for the user edit."

patterns-established:
  - "Async title jobs use @Async(\"aiAgentTitleExecutor\") plus @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true) to support current non-transactional publication and future transactional publishers."
  - "Auto-title clobber protection reloads AiConversation before save and skips if the title no longer equals the first-user-message default."
  - "ChatPanelFragment reserves Phase 13 attachmentsPanel as hidden layout only; upload/file behavior remains out of scope."

requirements-completed: [SURF-05]

duration: 43 min
completed: 2026-05-02
---

# Phase 12 Plan 05: Conversation Titles Summary

**Fail-silent Spring AI conversation titles with manual Jmix pencil-edit override and a hidden Phase 13 attachment slot**

## Performance

- **Duration:** 43 min
- **Started:** 2026-05-02T07:38:06Z
- **Completed:** 2026-05-02T08:20:51Z
- **Tasks:** 3
- **Files modified:** 17

## Accomplishments

- Added configurable, localized async conversation title generation through the existing Spring AI provider path with no tools/advisors and maxTokens=32.
- Published title eligibility after assistant response handling through a narrow collaborator that only emits on the first assistant reply.
- Added a localized icon-only pencil edit action that validates non-blank titles, checks conversation ownership, saves through secured DataManager, and updates the visible title.
- Added `attachmentsPanel visible="false"` to the chat fragment with no upload/file behavior.

## Task Commits

1. **Task 1 RED: Add title service tests** - `8782f35` (test)
2. **Task 1 GREEN: Add async conversation title service** - `ab075ad` (feat)
3. **Task 2 RED: Add title eligibility publication tests** - `13ed050` (test)
4. **Task 2 GREEN: Publish title eligibility after replies** - `a3e19cb` (feat)
5. **Task 3 RED: Add title edit fragment tests** - `05cb7dc` (test)
6. **Task 3 GREEN: Add editable chat titles and attachment slot** - `6db834e` (feat)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java` - async title generation, sanitization, clobber guard, and audit.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/ConversationTitleEligibilityPublisher.java` - first-assistant-reply eligibility gate.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` - post-response title eligibility invocation.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` - manual title edit dialog and secured save flow.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml` - title row and hidden attachmentsPanel.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` and `messages_vi.properties` - localized title edit keys.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java` and `ChatPanelFragmentConversationIdTest.java` - regression coverage.

## Decisions Made

- Used a dedicated publisher collaborator instead of injecting `ApplicationEventPublisher` directly into `DefaultChatServiceImpl`; this keeps event eligibility query logic isolated and testable.
- Kept current non-transactional publication compatible with a transactional future by using `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`.
- Kept the Phase 13 attachment area as a hidden XML slot only; no upload or AiTaskFile behavior was introduced.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added Spring AI OpenAI module dependency**
- **Found during:** Task 1 (Add title properties, event, prompt, and async title service)
- **Issue:** `OpenAiChatOptions` was required for per-request model/temperature/maxTokens override but was not on the add-on module compile classpath.
- **Fix:** Added `org.springframework.ai:spring-ai-openai:1.1.4` to `ai-agent/ai-agent/ai-agent.gradle`.
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
- **Verification:** `./gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.conversation.AiConversationTitleServiceTest"` passed.
- **Committed in:** `ab075ad`

---

**Total deviations:** 1 auto-fixed (Rule 3 blocking).
**Impact on plan:** Required to compile the planned Spring AI options override. No architecture or runtime contract expansion.

## Issues Encountered

- Task 3 GREEN initially missed an `AiConversation` import; the focused fragment test caught the compile error and the import was added before commit.
- JetBrains inspections left intentional warnings only: Java 17-compatible `remove(0)`, defensive null checks in existing chat code, test-only direct `JmixButton` construction, and pre-existing inverted-use warning for `hasMessages()`.

## Verification

- `./gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.conversation.AiConversationTitleServiceTest"` - passed.
- `./gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentConversationIdTest"` - passed.
- `./gradlew.bat :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatViewStreamTest"` - passed.
- `./gradlew.bat :ai-agent:ai-agent:compileJava` - passed.
- JetBrains `get_file_problems` run on touched Java/XML files; no errors, only triaged warnings listed above.

## Known Stubs

None. Stub scan found only existing localized placeholder message keys, not unwired UI/data stubs.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 12-06 can build on a chat fragment that now has stable title UI and a hidden `attachmentsPanel` slot for Phase 13 file/task input work. Title generation remains fail-silent and does not change the chat runtime contract.

## Self-Check: PASSED

- Summary file exists.
- Key created files exist: `AiConversationTitleService.java`, `ConversationTitleEligibilityPublisher.java`, and `chat-panel-fragment.xml`.
- Task commits found: `8782f35`, `ab075ad`, `13ed050`, `a3e19cb`, `05cb7dc`, `6db834e`.
- Working tree contains only the pre-existing unrelated `REVIEW.md` deletion plus the new SUMMARY at self-check time.

---
*Phase: 12-configurable-chat-surfaces*
*Completed: 2026-05-02*
