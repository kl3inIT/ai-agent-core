---
phase: 07-flow-ui
plan: 07a
type: execute
wave: 0
depends_on: []
files_modified:
  - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java
autonomous: true
requirements: [UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-08, UI-09, UI-10]
tags: [test-skeletons, nyquist, red-phase]
must_haves:
  truths:
    - "All 11 Phase 7 test files exist as compiling JUnit skeletons BEFORE any implementation plan runs"
    - "Every skeleton contains @Test methods with a single failing assertion (Assertions.fail(\"not yet implemented — 07-07b\"))"
    - "./gradlew :ai-agent:ai-agent:compileTestJava succeeds"
    - "./gradlew :ai-agent:ai-agent:test --tests <any of the 11> reports tests RAN (as failures) — they are discoverable and executable"
    - "Implementation plans 07-01..07-06 can reference these test files in their <automated> verify commands with confidence the file exists"
  artifacts:
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java"
      provides: "Compiling JUnit 5 skeleton with @Test bundlesHaveIdenticalKeySets() + @Test allPhase7KeysPresent()"
      contains: "fail("
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java"
      provides: "Compiling skeleton with 7 @Test stubs (stripsScriptTags, stripsJavascriptUri, stripsDataUri, stripsOnErrorHandler, preservesSafeFormatting, preservesSafeLinks, nullOrEmptyReturnsEmpty)"
      contains: "fail("
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java"
      provides: "Compiling skeleton with @Test publishesOnCommit() + @Test suppressedOnRollback()"
      contains: "fail("
  key_links:
    - from: "07-01/02/03/04/05/06 plans <verify><automated> commands"
      to: "these test files"
      via: "Gradle --tests pattern resolution"
      pattern: "LocaleParityTest|MarkdownRendererXssTest|ChatViewStreamTest|ChatViewStopTest|KnowledgeBaseUploadTest|DocumentStatusPushTest|ParametersDetailYamlPreviewTest|ToolCallAuditListViewTest|ConversationListRoleFilterTest|PushAutoConfigTest|AdminViewAccessTest"
---

<objective>
Wave 0 skeleton plan. Creates the failing-test scaffolding for every Phase 7 automated verify target BEFORE implementation plans 07-01..07-06 run. This satisfies the Nyquist test-first rule: every `<verify><automated>` command in a later plan points to a real, discoverable, currently-red test file. Plan 07-07b (Wave 5) fills the bodies with real assertions once implementations land.

Purpose:
- Unblock Nyquist compliance for plans 07-01..07-06 without rewriting each of their verify commands.
- Establish all test package directories and file names up-front — avoids conflicting mkdir/touch races between Wave 2 parallel plans.
- Force the executor to confront every test contract at the start of the phase, surfacing naming or API mismatches before deep implementation.

Output: 11 JUnit test class files, each compiling with empty-body methods that throw via `org.junit.jupiter.api.Assertions.fail("not yet implemented — filled in by 07-07b")`.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/07-flow-ui/07-07-PLAN.md
@.planning/phases/07-flow-ui/07-UI-SPEC.md

<interfaces>
Every skeleton follows the same shape:

```java
package com.vn.agent.<subpackage>;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

class <ClassName> {

    @Test
    void <methodFromPlan07_07>() {
        fail("not yet implemented — filled in by 07-07b");
    }

    // …one @Test per method listed in 07-07-PLAN.md for this file…
}
```

For `@SpringBootTest` / `@UiTest` skeletons, keep the annotation on the class so Spring wiring is exercised at discovery time:

```java
@SpringBootTest
class DocumentStatusPushTest {
    @Test void publishesOnCommit() { fail("not yet implemented — 07-07b"); }
    @Test void suppressedOnRollback() { fail("not yet implemented — 07-07b"); }
}
```

For PushAutoConfigTest, keep the two `@Nested` inner classes (`DefaultEnabled`, `DisabledByProperty`) as declared in 07-07.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Pure-unit skeletons (LocaleParityTest, MarkdownRendererXssTest)</name>
  <read_first>
    - .planning/phases/07-flow-ui/07-07-PLAN.md (Task 1 — copy the `@Test` method names VERBATIM)
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java
  </files>
  <action>
    Create each file as a plain JUnit 5 class (no Spring) with empty-body @Test methods whose names match 07-07 Task 1 VERBATIM. Each body: `fail("not yet implemented — 07-07b");`.

    LocaleParityTest:
      - @Test void bundlesHaveIdenticalKeySets()
      - @Test void allPhase7KeysPresent()

    MarkdownRendererXssTest:
      - @Test void stripsScriptTags()
      - @Test void stripsJavascriptUri()
      - @Test void stripsDataUri()
      - @Test void stripsOnErrorHandler()
      - @Test void preservesSafeFormatting()
      - @Test void preservesSafeLinks()
      - @Test void nullOrEmptyReturnsEmpty()

    Do NOT add any imports beyond `org.junit.jupiter.api.Test` and `static org.junit.jupiter.api.Assertions.fail`. No MarkdownRenderer instantiation yet (class does not exist until 07-01).
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:compileTestJava</automated>
  </verify>
  <done>
    - compileTestJava succeeds.
    - `grep -c "void bundlesHaveIdenticalKeySets" ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java` prints `1`.
    - `grep -c "void stripsScriptTags\|void stripsJavascriptUri\|void stripsDataUri\|void stripsOnErrorHandler\|void preservesSafeFormatting\|void preservesSafeLinks\|void nullOrEmptyReturnsEmpty" ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java` prints `7`.
    - `./gradlew :ai-agent:ai-agent:test --tests "*MarkdownRendererXssTest.stripsScriptTags"` REPORTS THE TEST RAN (expected outcome: FAILED with "not yet implemented — 07-07b").
  </done>
</task>

<task type="auto">
  <name>Task 2: Spring-context skeletons (AdminViewAccessTest, PushAutoConfigTest, DocumentStatusPushTest, KnowledgeBaseUploadTest)</name>
  <read_first>
    - .planning/phases/07-flow-ui/07-07-PLAN.md (Task 1 + Task 2 — copy @Test names verbatim)
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java
  </files>
  <action>
    Create each as @SpringBootTest with empty-body @Test stubs failing via `fail(...)`. Method names VERBATIM from 07-07-PLAN.md. DO NOT remove the @Nested structure from PushAutoConfigTest — preserve both `DefaultEnabled` and `DisabledByProperty` nested classes, each with a single failing @Test.

    AdminViewAccessTest:
      - @Test void deniesParametersListForNonAdmin()
      - @Test void deniesKnowledgeBaseListForNonAdmin()
      - @Test void deniesAuditListForNonAdmin()
      - @Test void allowsChatForUser()
      - @Test void allowsConversationListForUser()

    PushAutoConfigTest (preserve @Nested shape from 07-07):
      - @Nested @SpringBootTest class DefaultEnabled { @Test void appShellPresentByDefault() { fail(...); } }
      - @Nested @SpringBootTest(properties = "jmix.ai-agent.flowui.push-autoconfigure=false") class DisabledByProperty { @Test void appShellAbsent() { fail(...); } }

    DocumentStatusPushTest:
      - @Test void publishesOnCommit()
      - @Test void suppressedOnRollback()

    KnowledgeBaseUploadTest:
      - @Test void uploadTriggersService()
      - @Test void pendingRowAppearsAfterUpload()

    Rule 1 tolerance: If `@SpringBootTest` wiring fails at skeleton time because Phase-7 beans (KnowledgeDocumentUploadService, etc.) don't yet exist, keep the `@SpringBootTest` annotation but add `@Disabled("07-07b will enable once 07-06 lands")` at the class level — the file still compiles, and plans 07-06 + 07-07b flip the switch.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:compileTestJava</automated>
  </verify>
  <done>
    - compileTestJava succeeds.
    - All 4 files exist under the paths declared.
    - `grep -c "@Nested" ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java` prints `2`.
    - `grep -c "void deniesParametersListForNonAdmin\|void deniesKnowledgeBaseListForNonAdmin\|void deniesAuditListForNonAdmin" ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java` prints `3`.
    - `grep -c "void publishesOnCommit\|void suppressedOnRollback" ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java` prints `2`.
  </done>
</task>

<task type="auto">
  <name>Task 3: View-level skeletons (ChatViewStream, ChatViewStop, ParametersDetailYamlPreview, ToolCallAuditListView, ConversationListRoleFilter)</name>
  <read_first>
    - .planning/phases/07-flow-ui/07-07-PLAN.md (Tasks 2 + 3 — copy @Test names verbatim)
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java
  </files>
  <action>
    Create each as `@SpringBootTest @Disabled("07-07b enables once 07-03/04/05/06 land")` with failing @Test stubs. Method names per 07-07-PLAN.md.

    ChatViewStreamTest:
      - @Test void appendsContentEventsToAssistantBubble()

    ChatViewStopTest:
      - @Test void stopDisposesStreamAndMarksBubble()

    ParametersDetailYamlPreviewTest:
      - @Test void yamlPreviewRegeneratesOnFieldChange()
      - @Test void yamlPreviewReflectsModelChange()

    ToolCallAuditListViewTest:
      - @Test void gridHasExportActions()
      - @Test void gridHasAllSixColumns()

    ConversationListRoleFilterTest:
      - @Test void nonAdminSeesOnlyOwnRows()
      - @Test void adminSeesAllRowsAndCreatedByColumn()

    Use the class-level `@Disabled(...)` so skeletons don't halt downstream plan verify steps. 07-07b removes the @Disabled annotation and fills bodies.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:compileTestJava</automated>
  </verify>
  <done>
    - compileTestJava succeeds.
    - All 5 files exist.
    - `grep -l "@Disabled" ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java` prints 5 paths.
    - `./gradlew :ai-agent:ai-agent:test --tests "*ChatViewStreamTest" --tests "*ChatViewStopTest" --tests "*ParametersDetailYamlPreviewTest" --tests "*ToolCallAuditListViewTest" --tests "*ConversationListRoleFilterTest"` exits 0 (all disabled = SKIPPED, not failed).
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

None — this plan only creates empty test skeletons under src/test. No runtime code paths change.

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-07-22 | Tampering | Skeleton `@Disabled` forgotten in 07-07b | mitigate | 07-07b Task 0 explicitly deletes every `@Disabled("07-07b…")` annotation it added here; `grep -r "07-07b" ai-agent/ai-agent/src/test` must return 0 matches at phase close. |
</threat_model>

<verification>
- `./gradlew :ai-agent:ai-agent:compileTestJava` green
- 11 new test files exist
- `grep -rc "fail(\"not yet implemented" ai-agent/ai-agent/src/test/java/com/vn/agent | awk -F: '{s+=$2} END {print s}'` prints >= 20 (total stub count across files)
- `grep -rc "@Disabled(\"07-07b" ai-agent/ai-agent/src/test/java/com/vn/agent` reports 5–11 files (the Spring/View-level ones)
</verification>

<success_criteria>
Every `<verify><automated>` command in plans 07-01..07-06 now points to an existing test file. Nyquist contract satisfied: RED tests exist before implementation, GREEN fill handled by 07-07b at Wave 5.
</success_criteria>

<output>
After completion, create `.planning/phases/07-flow-ui/07-07a-SUMMARY.md` capturing:
- Confirmation that all 11 skeleton files compile
- Which files had to be @Disabled at the class level (awaiting Phase 7 beans)
- Total failing+disabled test count, to be reconciled by 07-07b
</output>
</objective>
