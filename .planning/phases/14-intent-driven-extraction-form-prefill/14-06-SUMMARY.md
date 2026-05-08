---
phase: 14-intent-driven-extraction-form-prefill
plan: 06
subsystem: ui
tags: [jmix-views, chat-ui, intent-extraction, i18n, css, tests]

requires:
  - phase: 14-04
    provides: intent-aware chat service overloads and named-intent tool gating
  - phase: 14-05
    provides: OpenFormWithDraftHandler and draft lifecycle navigation owner
provides:
  - Jmix radioButtonGroup intent card row above the chat input
  - Structured prepare_form_draft payload rendering into inline confirm rows
  - Scoped Phase 14 chat CSS and bilingual intent copy
  - Renderer/source regression coverage for intent row and draft confirm contracts
affects: [phase-14, chat-panel-fragment, stream-event-renderer, locale-parity]

tech-stack:
  added: []
  patterns:
    - Jmix XML radioButtonGroup with @Supply ComponentRenderer card options
    - Structured ToolResult payload side-channel from pure renderer to chat fragment
    - Source/XML regression tests for Jmix UI contracts under known Spring boot blocker

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/IntentCardRowTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/OpenFormWithDraftRenderingTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventIntentPayloadTest.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
    - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java

key-decisions:
  - "Intent row uses Jmix radioButtonGroup plus @Supply ComponentRenderer, with Auto as the first/default option and hidden row when no named intents are eligible."
  - "StreamEventRenderer parses only prepare_form_draft ToolResult.payloadJson and returns a structured DraftPayload marker; human-readable summaries are never parsed for extraction UI."
  - "Confirm rows are appended by ChatPanelFragment and delegate clicks to OpenFormWithDraftHandler; StreamEventRenderer remains navigation-free."
  - "UI tests use source/XML contract checks for this plan because full Jmix UI boot remains affected by the pre-existing agentstore Spring context blocker documented in prior phase summaries."

patterns-established:
  - "Renderer side-channel: keep pure rendering classes UI-free by returning structured markers that the fragment consumes."
  - "Dynamic chat rows: append server-side Div siblings under messageListSlot for non-markdown controls, then let clearMessageList wipe both components and raw elements."

requirements-completed:
  - EXTRACT-01
  - EXTRACT-07

duration: 17min
completed: 2026-05-08
---

# Phase 14 Plan 06: Intent Chat UI Summary

**Jmix intent card-row picker and structured draft-confirm rendering for chat-based extraction**

## Performance

- **Duration:** 17 min
- **Started:** 2026-05-08T04:20:57Z
- **Completed:** 2026-05-08T04:38:17Z
- **Tasks:** 6/6
- **Files modified:** 10

## Accomplishments

- Added `<radioButtonGroup id="intentCardRow">` between the message list and input without changing the Phase 13.1 split layout or attachment pane.
- Populated Auto plus eligible named intents through `IntentRegistry`, rendered as Jmix cards via `@Supply`, and reset named sends back to Auto.
- Extended `StreamEventRenderer` to parse only structured `ToolResult.payloadJson` for `prepare_form_draft` and produce a `DraftPayload` marker.
- Added `ChatPanelFragment.appendIntentConfirmRow(...)` with localized copy, expired-draft disabling, and controller-side delegation to `OpenFormWithDraftHandler`.
- Appended scoped Phase 14 CSS and added bilingual `chatView.intent.*` keys.
- Added regression tests for card-row contracts, structured renderer payloads, confirm-row source contracts, and locale parity.

## Task Commits

1. **Task 1: Add intentCardRow XML and locale keys** - `d999f91` (feat)
2. **Task 2: Populate and render intent cards in ChatPanelFragment** - `fac4af1` (feat)
3. **Task 3: Send selected intent and reset named turns to Auto** - `94c783a` (feat)
4. **Task 4: Detect open_form_with_draft tool result and append confirm row** - `e0c64c0` (feat)
5. **Task 5: Append Phase 14 CSS only** - `28607b6` (feat)
6. **Task 6: Add UI tests and renderer tests** - `6684bf7` (test)

## Files Created/Modified

- `chat-panel-fragment.xml` - Adds the hidden horizontal Jmix intent radio group between messages and input.
- `ChatPanelFragment.java` - Populates/render intent cards, sends selected intent ids, appends confirm rows, and handles invalid draft payload notifications.
- `StreamEventRenderer.java` - Adds `RenderedStreamEvent` and `DraftPayload` parsing for structured extraction tool results.
- `ai-agent-chat.css` - Appends scoped intent row/card/confirm selectors only.
- `messages_en.properties`, `messages_vi.properties` - Adds Phase 14 intent UI copy in both supported locales.
- `IntentCardRowTest.java` - Source/XML contract tests for intent row placement, rendering, visibility, and reset behavior.
- `OpenFormWithDraftRenderingTest.java` - Source contract tests for structured marker rendering and controller-owned confirm rows.
- `RenderStreamEventIntentPayloadTest.java` - Pure renderer tests for valid, invalid, malformed, and summary-only extraction payloads.
- `LocaleParityTest.java` - Adds Phase 14 intent key coverage.

## Decisions Made

- Followed CONTEXT D-07/D-08 by using `radioButtonGroup`, not hand-built toggles, with Auto first/default and named options hidden when none are eligible.
- Kept `StreamEventRenderer` free of Vaadin navigation and Spring dependencies; the renderer only emits a structured marker.
- Used source/XML tests rather than `@UiTest` to avoid the known pre-existing Spring context boot blocker while still pinning the UI contract.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Caught checked Jackson parse failure in renderer**
- **Found during:** Task 4
- **Issue:** `ObjectMapper.readTree(String)` throws checked `JsonProcessingException`, so `compileJava` failed.
- **Fix:** Broadened the parser catch to handle checked parse exceptions and return the localized invalid-payload marker path.
- **Files modified:** `StreamEventRenderer.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:compileJava` passed.
- **Committed in:** `e0c64c0`

**2. [Rule 1 - Test Bug] Fixed source-scan test path resolution**
- **Found during:** Task 6
- **Issue:** New source-scan tests assumed Gradle `user.dir` was the repository root; module-scoped Gradle tests run from `ai-agent/ai-agent`.
- **Fix:** Test helpers now resolve both repository-relative and module-relative paths.
- **Files modified:** `IntentCardRowTest.java`, `OpenFormWithDraftRenderingTest.java`
- **Verification:** Task 6 test command passed.
- **Committed in:** `6684bf7`

**Total deviations:** 2 auto-fixed (1 blocking, 1 test bug)
**Impact on plan:** Both fixes were local to the planned implementation and test harness. No API or architecture change.

## Issues Encountered

- JetBrains reported existing annotation/static-analysis warnings in `ChatPanelFragment.java` (`@ViewComponent` fields seen as unused, raw `GridLayout`, test-visible `onBlockingResponse` unused). These pre-date this plan or are intentional Jmix/test-seam patterns and were not changed.
- JetBrains reported broad existing "Unused property" warnings in the message bundles. The new Phase 14 keys are covered by `LocaleParityTest`; unused-key warnings are expected until runtime UI references are indexed.

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileJava` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*IntentCardRowTest" --tests "*OpenFormWithDraftRenderingTest" --tests "*RenderStreamEventIntentPayloadTest" --tests "*LocaleParityTest"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*Intent*" --tests "*RenderStreamEvent*"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*LocaleParityTest"` - PASS
- JetBrains file-problem checks - PASS for XML, `StreamEventRenderer`, and all new/modified tests; only pre-existing/intentional warnings remained in `ChatPanelFragment` and message bundles.

## Known Stubs

None. Stub scan found only ordinary null checks and pre-existing message placeholder keys.

## Threat Flags

None. The plan-introduced UI and payload parsing surfaces match the plan threat model; no new network endpoint, auth path, file access pattern, or schema boundary was introduced.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 14-07. The chat surface now exposes intent selection and can render `open_form_with_draft` payloads into controller-owned confirm actions.

## Self-Check: PASSED

- Summary file exists.
- Created test files exist.
- All six task commits are present in git history.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
