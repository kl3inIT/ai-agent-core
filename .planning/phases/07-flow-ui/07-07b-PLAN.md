---
phase: 07-flow-ui
plan: 07b
type: execute
wave: 5
depends_on: [07-07a, 07-01, 07-02, 07-03, 07-04, 07-05, 07-06]
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
tags: [test, i18n, security, push, streaming, xss]
must_haves:
  truths:
    - "LocaleParityTest asserts messages.properties and messages_vi.properties have identical key sets"
    - "AdminViewAccessTest asserts non-admin users get access denial when navigating to Parameters/KnowledgeBase/ToolCallAudit routes"
    - "MarkdownRendererXssTest asserts <script>, javascript: URIs, on* handlers, and data: URIs are stripped"
    - "ChatViewStreamTest asserts ChatPanelFragment subscribes to ChatService.stream and appends Content events to the assistant bubble"
    - "ChatViewStopTest asserts clicking Stop disposes the active Flux and marks the bubble as stopped"
    - "KnowledgeBaseUploadTest asserts Upload triggers KnowledgeDocumentUploadService.upload and a new PENDING row is shown"
    - "DocumentStatusPushTest asserts DocumentStatusChangedEvent fired after commit refreshes the KB grid row"
    - "ParametersDetailYamlPreviewTest asserts YAML preview regenerates on form field valueChange"
    - "ToolCallAuditListViewTest asserts grid has grdexp_excelExport and grdexp_jsonExport actions"
    - "ConversationListRoleFilterTest asserts non-admin sees only own rows, admin sees all rows"
    - "PushAutoConfigTest asserts AiAgentAppShell is registered by default and gated by jmix.ai-agent.flowui.push-autoconfigure=false"
  artifacts:
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java"
      provides: "Key-parity test between en and vi bundles"
      contains: "Properties"
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java"
      provides: "XSS defense test with 5+ malicious inputs"
      contains: "<script>"
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java"
      provides: "afterCommit event emission integration test"
      contains: "ApplicationEventPublisher"
  key_links:
    - from: "LocaleParityTest"
      to: "messages.properties + messages_vi.properties"
      via: "Properties.load() + Set.symmetricDifference on keySet()"
      pattern: "messages_vi"
    - from: "MarkdownRendererXssTest"
      to: "MarkdownRenderer.toSafeHtml"
      via: "direct unit test, no Spring context"
      pattern: "toSafeHtml"
---

<objective>
Wave 5 plan. GREEN-fills the Phase 7 test suite scaffolded by 07-07a (Wave 0). Removes every @Disabled("07-07b…") annotation, deletes every fail("not yet implemented — 07-07b") stub, and replaces each @Test body with the real assertion logic. Together with 07-07a this suite — 11 tests that together certify UI-01..10 success criteria: locale parity (UI-08/09), admin gating (UI-10), XSS defense (D-07), streaming (UI-01), Stop (UI-02/D-03), upload (UI-05), push refresh (D-16), YAML preview live regen (D-12), gridexport actions (D-20), role-aware filter (D-24), and AppShell conditional (D-02). Depends on all prior Phase 7 plans. All tests are @SpringBootTest or @UiTest where views are involved; pure unit tests where classes are standalone.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/07-flow-ui/07-CONTEXT.md
@.planning/phases/07-flow-ui/07-RESEARCH.md
@.planning/phases/07-flow-ui/07-UI-SPEC.md
@.planning/phases/07-flow-ui/07-01-PLAN.md
@.planning/phases/07-flow-ui/07-02-PLAN.md
@.planning/phases/07-flow-ui/07-03-PLAN.md
@.planning/phases/07-flow-ui/07-04-PLAN.md
@.planning/phases/07-flow-ui/07-05-PLAN.md
@.planning/phases/07-flow-ui/07-06-PLAN.md
@ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java
@ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java
@ai-agent/ai-agent/src/main/java/com/vn/agent/push/DocumentStatusChangedEvent.java
@ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java

<interfaces>
Jmix test base classes (from existing test patterns in this repo):
- `@UiTest` annotation for view-level tests with a Vaadin UI context — locate existing examples via `grep -r "@UiTest" ai-agent/` to find base configuration.
- `@SpringBootTest` for wiring tests with a real Spring context + TestContainers Postgres (existing Phase 5/6 tests).

Reactor test utility: `reactor.test.StepVerifier` for Flux-based tests.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 0: Un-disable skeletons — strip @Disabled + fail() placeholders from 07-07a</name>
  <read_first>
    - .planning/phases/07-flow-ui/07-07a-PLAN.md
    - All 11 test files listed in files_modified
  </read_first>
  <files>
    (same 11 test files as files_modified)
  </files>
  <action>
    Before writing real assertions, the executor MUST:
    1. Delete every class-level  annotation added by 07-07a. Grep target: .
    2. Remove every  line. Each will be replaced by the real assertion body in Tasks 1–3 below.
    3. Remove the  line from any class that no longer calls .

    Verification after Task 0 (before Task 1 writes real code):
    -  prints NOTHING.
    - > Task :ai-agent:ai-agent:compileJava
> Task :ai-agent:ai-agent:processResources UP-TO-DATE

> Task :ai-agent:ai-agent:enhanceJmixMain
Enhancing entities in project ':ai-agent:ai-agent' for source set 'main'
Project entities:
    JPA: [com.vn.agent.entity.AiParameters, com.vn.agent.entity.AiMessage, com.vn.agent.entity.AiToolCallAudit, com.vn.agent.entity.AiKnowledgeDocument, com.vn.agent.entity.AiConversation];
    DTO: [];
Project converters: [].
Running EclipseLink enhancer in project ':ai-agent:ai-agent' for source set 'main'
Running Jmix enhancer in project ':ai-agent:ai-agent' for source set 'main'

> Task :ai-agent:ai-agent:copyJmixEnhancingResourcesMain
> Task :ai-agent:ai-agent:classes
> Task :ai-agent:ai-agent-starter:compileJava UP-TO-DATE
> Task :ai-agent:ai-agent:compileTestJava UP-TO-DATE

[Incubating] Problems report is available at: file:///D:/DTH/ai-agent-core/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.4/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 22s
6 actionable tasks: 3 executed, 3 up-to-date still succeeds (files temporarily have empty @Test bodies — tasks 1–3 fill them).
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:compileTestJava</automated>
  </verify>
  <done>
    - zero remaining  markers
    - zero remaining  markers
    - compileTestJava green
  </done>
</task>

<task type="auto">
  <name>Task 1: Infrastructure tests (LocaleParity, AdminViewAccess, MarkdownRendererXss, PushAutoConfig)</name>
  <read_first>
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties (full en bundle)
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties (full vi bundle)
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - Any existing Phase 6 I18nParityTest under ai-agent/ai-agent/src/test/ for base pattern (grep for "I18nParityTest" to find).
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/push/PushAutoConfigTest.java
  </files>
  <action>
    1. `LocaleParityTest.java` — plain JUnit 5 unit test (no Spring):
       ```java
       @Test
       void bundlesHaveIdenticalKeySets() throws IOException {
           Properties en = new Properties();
           en.load(getClass().getResourceAsStream("/com/vn/agent/messages.properties"));
           Properties vi = new Properties();
           vi.load(new InputStreamReader(getClass().getResourceAsStream("/com/vn/agent/messages_vi.properties"), StandardCharsets.UTF_8));
           Set<Object> enOnly = new HashSet<>(en.keySet()); enOnly.removeAll(vi.keySet());
           Set<Object> viOnly = new HashSet<>(vi.keySet()); viOnly.removeAll(en.keySet());
           assertThat(enOnly).as("Keys in en but missing in vi").isEmpty();
           assertThat(viOnly).as("Keys in vi but missing in en").isEmpty();
       }

       @Test
       void allPhase7KeysPresent() throws IOException {
           Properties en = new Properties();
           en.load(getClass().getResourceAsStream("/com/vn/agent/messages.properties"));
           List<String> requiredPrefixes = List.of("chatView.", "conversationList.", "conversationDetail.", "parametersList.", "parametersDetail.", "knowledgeBase.", "auditList.");
           for (String prefix : requiredPrefixes) {
               assertThat(en.keySet().stream().anyMatch(k -> ((String) k).startsWith(prefix)))
                       .as("At least one key starting with %s must exist", prefix).isTrue();
           }
       }
       ```

    2. `AdminViewAccessTest.java` — `@SpringBootTest` with a non-admin authenticated user:
       - Start the `jmix-app` context (or a test slice that includes ai-agent security + flow UI auto-config).
       - Programmatically simulate a user with ONLY `AiAgentUserRole` (not admin).
       - Navigate via Jmix `ViewNavigators` or reflection to `AiAgent_Parameters.list`, `AiAgent_KnowledgeBase.list`, `AiAgent_ToolCallAudit.list`.
       - Assert `AccessDeniedException` or Jmix's view access denial mechanism kicks in for each admin view.
       - For `AiAgent_Chat` + `AiAgent_Conversation.list`: assert access granted.
       - Rule 1 tolerance: Jmix 2.8 view-access-denial assertion API — consult skill `jmix-views` or grep existing repo for view-access test shape; adapt. If the Jmix API only throws on actual navigation and not on policy lookup, use an integration Vaadin test with `UIUnit4Test` if available; else fall back to asserting via AccessManager directly: `assertThat(accessManager.applyRegisteredConstraints(ViewAccessContext.with("AiAgent_Parameters.list")).isPermitted()).isFalse();`.

    3. `MarkdownRendererXssTest.java` — plain JUnit 5:
       ```java
       @Test void stripsScriptTags() {
           MarkdownRenderer r = new MarkdownRenderer();
           assertThat(r.toSafeHtml("<script>alert(1)</script>hi")).doesNotContain("<script>");
       }
       @Test void stripsJavascriptUri() {
           MarkdownRenderer r = new MarkdownRenderer();
           String out = r.toSafeHtml("[evil](javascript:alert(1))");
           assertThat(out).doesNotContain("javascript:");
       }
       @Test void stripsDataUri() {
           MarkdownRenderer r = new MarkdownRenderer();
           String out = r.toSafeHtml("[evil](data:text/html;base64,PHNjcmlwdD4=)");
           assertThat(out).doesNotContain("data:");
       }
       @Test void stripsOnErrorHandler() {
           MarkdownRenderer r = new MarkdownRenderer();
           String out = r.toSafeHtml("<img src=x onerror=alert(1)>");
           assertThat(out).doesNotContain("onerror");
       }
       @Test void preservesSafeFormatting() {
           MarkdownRenderer r = new MarkdownRenderer();
           String out = r.toSafeHtml("**bold** and _italic_ and `code`");
           assertThat(out).contains("<strong>").contains("<em>").contains("<code>");
       }
       @Test void preservesSafeLinks() {
           MarkdownRenderer r = new MarkdownRenderer();
           String out = r.toSafeHtml("[docs](https://jmix.io)");
           assertThat(out).contains("https://jmix.io");
       }
       @Test void nullOrEmptyReturnsEmpty() {
           MarkdownRenderer r = new MarkdownRenderer();
           assertThat(r.toSafeHtml(null)).isEmpty();
           assertThat(r.toSafeHtml("")).isEmpty();
       }
       ```

    4. `PushAutoConfigTest.java` — `@SpringBootTest`:
       ```java
       @Nested @SpringBootTest
       class DefaultEnabled {
           @Autowired ApplicationContext ctx;
           @Test void appShellPresentByDefault() {
               assertThat(ctx.getBeansOfType(AiAgentAppShell.class)).isNotEmpty();
           }
       }

       @Nested @SpringBootTest(properties = "jmix.ai-agent.flowui.push-autoconfigure=false")
       class DisabledByProperty {
           @Autowired ApplicationContext ctx;
           @Test void appShellAbsent() {
               assertThat(ctx.getBeansOfType(AiAgentAppShell.class)).isEmpty();
           }
       }
       ```
       Rule 1 caveat: If Vaadin classpath scanning ignores Spring conditionality (RESEARCH Open Q#3), this test will surface the truth — expected failure mode becomes the SUMMARY evidence to switch to doc-only distribution.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:test --tests "*LocaleParityTest" --tests "*MarkdownRendererXssTest" --tests "*AdminViewAccessTest" --tests "*PushAutoConfigTest"</automated>
  </verify>
  <done>
    - All 4 test classes compile + run.
    - LocaleParityTest passes (07-01 bundles seeded with parity).
    - MarkdownRendererXssTest 7 assertions all pass.
    - AdminViewAccessTest asserts denial for 3 admin views, access for 2 user views.
    - PushAutoConfigTest passes OR fails with evidence to update D-02 (either outcome is acceptable; document in SUMMARY).
  </done>
</task>

<task type="auto">
  <name>Task 2: View-level tests (ChatViewStream, ChatViewStop, KnowledgeBaseUpload, DocumentStatusPush)</name>
  <read_first>
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java (just created)
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
    - Any existing @UiTest example in the repo (grep "@UiTest" recursively). If none exists, fall back to @SpringBootTest + stubbed UI via `new UI()` + `UI.setCurrent(ui)`.
    - reactor.test.StepVerifier javadoc.
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/push/DocumentStatusPushTest.java
  </files>
  <action>
    1. `ChatViewStreamTest.java`:
       - Prefer `@UiTest` if the annotation exists in this codebase; fall back to `@SpringBootTest` with manual UI setup.
       - Mock `ChatService` to return a `Flux.just(new StreamingEvent.Content("hello "), new StreamingEvent.Content("world"), new StreamingEvent.Final(UUID.randomUUID(), 100L, 0, 0))`.
       - Instantiate `ChatPanelFragment`, trigger send with a test message, wait for completion (use a `CountDownLatch` inside a doOnComplete hook or `StepVerifier` with the mocked service).
       - Assert the resulting `activeAssistantBubble` HTML contains "hello world" after markdown render.
       - Rule 1 tolerance: Fragment direct instantiation in tests without a Vaadin session may fail — if so, use a headless test harness from Jmix (skill jmix-views documents this) or refactor to test the streaming-handler helper method directly (split `handleEvent` into a pure function + a UI-access wrapper for testability).

    2. `ChatViewStopTest.java`:
       - Mock `ChatService.stream(...)` to return an infinite `Flux.interval(Duration.ofMillis(10)).map(i -> new StreamingEvent.Content("tick "))`.
       - Simulate send → 100 ms wait → click Stop.
       - Assert: `activeStream.isDisposed()` is true, bubble contains "— stopped" suffix, sendButton visible + stopButton hidden.

    3. `KnowledgeBaseUploadTest.java` — `@SpringBootTest`:
       - Mock `KnowledgeDocumentUploadService.upload(...)` to return a new document id.
       - Load `KnowledgeBaseView`, trigger upload via the upload listener (simulate SucceededEvent).
       - Assert `KnowledgeDocumentUploadService.upload` was called with the test InputStream + filename.
       - Assert a PENDING row appears in `documentsDc.getItems()` (after `documentsDl.load()`).

    4. `DocumentStatusPushTest.java` — `@SpringBootTest` + TestContainers Postgres (if Phase 5 test infra used TC; else H2):
       - Spy on `ApplicationEventPublisher`.
       - Call `IngestionStatusWriter.markReady(documentId, 5)` inside `@Transactional`.
       - Assert: the transaction commits, then `publisher.publishEvent(any(DocumentStatusChangedEvent.class))` was invoked EXACTLY ONCE with status=READY and matching documentId.
       - Also assert: if the transaction ROLLS BACK (simulated via `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`), the event is NOT published.

       Rule 1: if Phase 5 IngestionStatusWriter test already exists under a similar name, this test ADDS to it rather than duplicating — verify via `find ai-agent -name "IngestionStatusWriterTest*"` and either extend the existing file or create a separate `DocumentStatusPushTest` that focuses only on the afterCommit event semantics added in 07-02.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:test --tests "*ChatViewStreamTest" --tests "*ChatViewStopTest" --tests "*KnowledgeBaseUploadTest" --tests "*DocumentStatusPushTest"</automated>
  </verify>
  <done>
    - All four tests compile and run.
    - ChatViewStreamTest asserts content appears in assistant bubble after stream completes.
    - ChatViewStopTest asserts Flux disposed + bubble marked stopped.
    - KnowledgeBaseUploadTest asserts upload service called + row displayed.
    - DocumentStatusPushTest asserts afterCommit event fires on commit but not on rollback.
  </done>
</task>

<task type="auto">
  <name>Task 3: Remaining view tests (ParametersDetailYamlPreview, ToolCallAuditListView, ConversationListRoleFilter)</name>
  <read_first>
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBodyYamlMapper.java
  </read_first>
  <files>
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java,
    ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java
  </files>
  <action>
    1. `ParametersDetailYamlPreviewTest.java`:
       - Instantiate `ParametersDetailView` (via Jmix test harness / @UiTest / manual Spring wiring).
       - Load it with an empty AiParameters entity.
       - Set `modelField.setValue("gpt-4o")` and `temperatureField.setValue(0.7)`.
       - Assert `yamlPreview.getValue()` contains `model: gpt-4o` AND `temperature: 0.7`.
       - Change model to "claude-3-sonnet"; assert preview updates.
       - If the YAML serializer produces different key-casing (snake_case vs camelCase), adapt assertion to match the real mapper output.

    2. `ToolCallAuditListViewTest.java`:
       - Load the view under @UiTest / @SpringBootTest.
       - Assert grid has two actions with ids `excelExport` and `jsonExport` (type `grdexp_excelExport` + `grdexp_jsonExport`):
         ```java
         assertThat(auditsDataGrid.getActions().stream().map(Action::getId)).contains("excelExport", "jsonExport");
         ```
         Rule 1: adapt to actual Jmix DataGrid action API.
       - Assert all 6 columns present: createdDate, userId, toolName, phase, outcome, latencyMs.

    3. `ConversationListRoleFilterTest.java` — `@SpringBootTest` with transactional seed:
       - Seed 2 AiConversation rows: one `createdBy="alice"`, one `createdBy="bob"`.
       - Authenticate as `alice` with only AiAgentUserRole (non-admin).
       - Load `ConversationListView`; assert `conversationsDc.getItems()` size == 1 and createdBy == "alice".
       - Authenticate as `admin-user` with `AiAgentAdminRole`.
       - Reload view; assert `conversationsDc.getItems()` size == 2 AND the `createdBy` column is now visible.

       Rule 1: in-test authentication simulation — Jmix exposes `SystemAuthenticator.runWithUser(String, Runnable)` or similar; grep existing Phase 4 ownership-opacity tests for the pattern.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:ai-agent:test --tests "*ParametersDetailYamlPreviewTest" --tests "*ToolCallAuditListViewTest" --tests "*ConversationListRoleFilterTest"</automated>
  </verify>
  <done>
    - All three tests compile and run.
    - Full phase test suite green: `./gradlew :ai-agent:ai-agent:test` at the end of this task prints BUILD SUCCESSFUL.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Test harness simulating roles | Must use real Jmix security plumbing — never bypass AccessManager |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-07-21 | Elevation | Test role simulation leaks to production | accept | Tests in src/test are isolated by Gradle SourceSet; the auth-simulation utility is scoped to test classpath only. |
</threat_model>

<verification>
- `./gradlew :ai-agent:ai-agent:test` green at end of task 3
- 11 new test classes exist and their asserts run
- Phase 7 success criteria 1-5 covered by tests (#1 admin gating, #2 streaming+Stop, #3 upload+status push, #4 gridexport, #5 bilingual parity)
</verification>

<success_criteria>
Phase 7 ships with a regression-tested UI layer. Every D-0x/D-1x/D-2x decision that has observable behavior has a test that would fail if the behavior regressed.
</success_criteria>

<output>
After completion, create `.planning/phases/07-flow-ui/07-07-SUMMARY.md` capturing:
- Which test harness was used for view-level tests (@UiTest vs manual @SpringBootTest + UI stub)
- Authentication simulation API actually used (SystemAuthenticator vs a custom helper)
- Any tests that were reduced in scope because underlying API was missing, with a follow-up task
- PushAutoConfigTest outcome (did conditional work, or do we pivot to doc-only distribution for D-02?)
</output>
