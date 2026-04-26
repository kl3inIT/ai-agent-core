---
phase: 07-flow-ui
plan: 07a
subsystem: testing
tags: [junit5, springboottest, red-skeletons, nyquist, wave-0]

requires:
  - phase: 06-parameters-structured-output-guardrails
    provides: ChatResponseDto and guard exception hierarchy referenced in later Phase 7 test bodies
provides:
  - 11 compiling JUnit 5 RED test skeletons covering every Phase 7 `<verify><automated>` target
  - Stable package layout for Phase 7 test sources (i18n, view.chat, view.parameters, view.audit, view.conversation, view.knowledge, push, security)
  - Discovery-visible @Test methods (28 total) that execute as FAILED or SKIPPED, giving downstream plans a real resolvable test pattern
affects: [07-01, 07-02, 07-03, 07-04, 07-05, 07-06, 07-07b]

tech-stack:
  added: []
  patterns:
    - "Nyquist Wave 0: every later plan's automated verify target exists as a failing test BEFORE implementation"
    - "Class-level @Disabled('07-07b enables once NN-XX lands') for Spring-context skeletons whose target beans don't exist yet — 07-07b Task 0 strips these annotations"

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java
  modified: []

key-decisions:
  - "Applied plan's Rule 1 tolerance to all four Task-2 Spring skeletons + all five Task-3 view skeletons: class-level @Disabled('07-07b …') preserves the @SpringBootTest annotation (so wiring is exercised once beans land) but prevents context-load failures today. Only the two pure-unit skeletons (LocaleParityTest, MarkdownRendererXssTest) remain un-disabled and run as FAILED."
  - "AdminViewAccessTest disabled preemptively: although Phase 2 role interfaces exist, the target views (ParametersListView / KnowledgeBaseListView / ToolCallAuditListView) don't — asserting denials against non-existent routes would throw bean-not-found. Re-enabled in 07-07b alongside 07-05."

patterns-established:
  - "RED-first Nyquist compliance for an entire phase via a single Wave-0 scaffolding plan"
  - "Uniform 'not yet implemented — filled in by 07-07b' message string grep-able for audit"
  - "Uniform '@Disabled(\"07-07b …\")' marker grep-able by 07-07b Task 0 to strip before filling bodies (T-07-22 mitigation)"

requirements-completed: []

duration: 2m 23s
completed: 2026-04-21
---

# Phase 07 Plan 07a: Phase 7 RED Test Skeletons Summary

**11 compiling JUnit 5 RED skeletons (28 @Test stubs) across 8 packages — every Phase 7 automated verify target now resolves, 9 of 11 class-level @Disabled pending downstream beans.**

## Performance

- **Duration:** 2m 23s
- **Started:** 2026-04-21T08:05:13Z
- **Completed:** 2026-04-21T08:07:36Z
- **Tasks:** 3 (all `type="auto"`)
- **Files created:** 11

## Accomplishments

- All 11 Phase 7 skeleton test files exist, compile, and are test-runner-discoverable.
- `./gradlew :ai-agent:ai-agent:compileTestJava` green after each task.
- Total 28 `fail("not yet implemented — filled in by 07-07b")` stubs (≥ 20 required by plan verification).
- 9 of 11 files carry `@Disabled("07-07b …")` class-level markers (5–11 required by plan verification).
- Pure-unit skeletons (LocaleParityTest, MarkdownRendererXssTest) run as FAILED — no Spring context needed.
- Spring-context skeletons (all 9 others) remain SKIPPED until 07-07b removes the @Disabled tag.

## Task Commits

Each task was committed atomically:

1. **Task 1: Pure-unit skeletons (LocaleParity, MarkdownRendererXss)** — `4d32b86` (test)
2. **Task 2: Spring-context skeletons (AdminViewAccess, PushAutoConfig, DocumentStatusPush, KnowledgeBaseUpload)** — `6ecd342` (test)
3. **Task 3: View-level skeletons (ChatViewStream, ChatViewStop, ParametersDetailYamlPreview, ToolCallAuditListView, ConversationListRoleFilter)** — `87309bf` (test)

**Plan metadata:** pending (final docs commit below)

## Files Created

| File | Tests | Spring? | Disabled? |
|------|-------|---------|-----------|
| `i18n/LocaleParityTest.java` | 2 | no | no |
| `view/chat/MarkdownRendererXssTest.java` | 7 | no | no |
| `security/AdminViewAccessTest.java` | 5 | yes | yes |
| `push/PushAutoConfigTest.java` | 2 (across 2 @Nested classes) | yes (on each nested) | yes (on each nested) |
| `push/DocumentStatusPushTest.java` | 2 | yes | yes |
| `view/knowledge/KnowledgeBaseUploadTest.java` | 2 | yes | yes |
| `view/chat/ChatViewStreamTest.java` | 1 | yes | yes |
| `view/chat/ChatViewStopTest.java` | 1 | yes | yes |
| `view/parameters/ParametersDetailYamlPreviewTest.java` | 2 | yes | yes |
| `view/audit/ToolCallAuditListViewTest.java` | 2 | yes | yes |
| `view/conversation/ConversationListRoleFilterTest.java` | 2 | yes | yes |

**Totals:** 11 files, 28 `fail(...)` stubs, 9 files (and both `@Nested` classes inside `PushAutoConfigTest`) carrying `@Disabled("07-07b …")`.

## @Disabled Files (awaiting Phase 7 beans)

To be re-enabled by 07-07b Task 0 (per threat mitigation T-07-22):

- `AdminViewAccessTest` (pending 07-05 admin views)
- `PushAutoConfigTest` — both `@Nested DefaultEnabled` and `@Nested DisabledByProperty` (pending 07-02 push autoconfig)
- `DocumentStatusPushTest` (pending 07-02 push event bus)
- `KnowledgeBaseUploadTest` (pending 07-06 knowledge upload view)
- `ChatViewStreamTest` (pending 07-03 chat stream)
- `ChatViewStopTest` (pending 07-03 chat stop)
- `ParametersDetailYamlPreviewTest` (pending 07-04 parameters detail)
- `ToolCallAuditListViewTest` (pending 07-05 audit list)
- `ConversationListRoleFilterTest` (pending 07-05 conversation list)

A `grep -r "07-07b" ai-agent/ai-agent/src/test` after 07-07b Task 0 MUST return 0 matches (T-07-22).

## Decisions Made

- Followed plan's Rule 1 tolerance guidance verbatim — applied class-level `@Disabled("07-07b …")` to every `@SpringBootTest` skeleton to avoid context-load failures against beans that land later in the wave sequence. This matches the plan intent ("the file still compiles, and plans 07-06 + 07-07b flip the switch") while extending the same pattern to Task 3 view skeletons for consistency.
- Kept all uniform wording "not yet implemented — filled in by 07-07b" so a single grep in 07-07b can locate every body to rewrite.

## Deviations from Plan

None — plan executed exactly as written. Rule 1 `@Disabled` tolerance is explicit plan guidance, not a deviation.

## Issues Encountered

None. `compileTestJava` green on every task's verification run.

## User Setup Required

None.

## Next Phase Readiness

- Plans 07-01, 07-02, 07-03, 07-04, 07-05, 07-06 can now call their `<verify><automated>` gradle test patterns against real, discoverable files.
- Pure-unit skeletons will report FAILED on invocation (Nyquist-correct RED state).
- Spring-context skeletons will report SKIPPED (by design — disabled markers).
- 07-07b (Wave 5) will: (a) `grep -r "@Disabled(\"07-07b" ai-agent/ai-agent/src/test` and strip every match; (b) replace every `fail("not yet implemented …")` with real assertions.

## Self-Check: PASSED

Artifacts verified (`[ -f ... ]` + `git log`):

- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java
- FOUND commit: 4d32b86 (Task 1)
- FOUND commit: 6ecd342 (Task 2)
- FOUND commit: 87309bf (Task 3)
- Plan verification greps all satisfied:
  - `./gradlew :ai-agent:ai-agent:compileTestJava` → BUILD SUCCESSFUL
  - 11 new test files exist
  - `fail("not yet implemented` total count = 28 (≥ 20 required)
  - 9 files carry `@Disabled("07-07b` (5–11 required)

---
*Phase: 07-flow-ui*
*Completed: 2026-04-21*
