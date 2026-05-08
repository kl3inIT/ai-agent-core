---
phase: 14-intent-driven-extraction-form-prefill
reviewed: 2026-05-08T08:15:43Z
depth: standard
files_reviewed: 65
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiExtractionDraft.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/AiExtractionDraftCleanupJob.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/AiExtractionProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftApplyResult.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftNotFoundException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionInput.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionResult.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentOption.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentRegistry.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/MetaClassDtoSynthesizer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/UnknownIntentException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/IntentExtractor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java
  - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/110-ai-extraction-draft.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceIntentRoutingTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/AiExtractionDraftCleanupJobTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/AiExtractionDraftModelTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/CoreCustomerImportScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/DraftLoaderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/DraftSetValueBypassScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionAuditTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionEvaluationContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionToolBridgeTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/IntentRegistryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/MetaClassDtoSynthesizerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerIntentTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNavigationLeakScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/LocaleParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/AiExtractionDraftSecurityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/IntentCardRowTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/OpenFormWithDraftRenderingTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventIntentPayloadTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandlerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/SaveDeletesDraftTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/TestMutationDetailView.java
  - ai-agent/ai-agent/src/test/resources/eval/extraction-fixtures.yaml
  - jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java
  - jmix-app/src/main/resources/application.properties
  - jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties
  - jmix-app/src/main/resources/com/vn/jmixapp/messages_vi.properties
  - jmix-app/src/test/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractorTest.java
  - jmix-app/src/test/java/com/vn/jmixapp/ai/CustomerDraftWorkflowTest.java
findings:
  critical: 5
  warning: 3
  info: 0
  total: 8
status: issues_found
---

# Phase 14: Code Review Report

**Reviewed:** 2026-05-08T08:15:43Z  
**Depth:** standard  
**Files Reviewed:** 65  
**Status:** issues_found

## Summary

Standard review found five blockers and three warnings. The main risks are committed credentials, stale extraction drafts remaining usable after expiry, UI code persisting denied first-turn conversations before guards run, schema-excluded entity fields leaking back into draft payloads, and source-faithfulness being represented only as a fixture instead of enforced.

## Blocker Issues

### BL-01: Hardcoded Credentials Are Committed

**Severity:** BLOCKER  
**File:** `jmix-app/src/main/resources/application.properties:3`  
**Issue:** `main.datasource.password=admin123`, `agentstore.datasource.password=admin123` at line 73, and `ui.login.defaultPassword=admin` at line 19 are committed credentials. These are secrets/default secrets in a deployable Spring Boot config, so any copied environment starts with known database and admin credentials.

**Fix:**
```properties
main.datasource.url=${MAIN_DATASOURCE_URL}
main.datasource.username=${MAIN_DATASOURCE_USERNAME}
main.datasource.password=${MAIN_DATASOURCE_PASSWORD}

agentstore.datasource.url=${AGENTSTORE_DATASOURCE_URL}
agentstore.datasource.username=${AGENTSTORE_DATASOURCE_USERNAME}
agentstore.datasource.password=${AGENTSTORE_DATASOURCE_PASSWORD}

ui.login.defaultUsername=${DEV_LOGIN_USERNAME:}
ui.login.defaultPassword=${DEV_LOGIN_PASSWORD:}
```
Move local values to `.env` or a non-committed profile file and fail closed for non-dev profiles when required values are absent.

### BL-02: Expired Drafts Remain Usable Until Cleanup Runs

**Severity:** BLOCKER  
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:117`  
**Issue:** Drafts have an `expiresAt` field (`AiExtractionDraft.java:79`) and cleanup only runs hourly (`AiExtractionDraftCleanupJob.java:32-39`), but both UI open and draft apply load by id only (`OpenFormWithDraftHandler.java:117-119`, `DraftLoader.java:114-118`). An expired draft row that has not yet been reaped can still open a form and prefill sensitive extracted values.

**Fix:**
```java
private Optional<AiExtractionDraft> loadDraft(UUID draftId) {
    if (draftId == null) {
        return Optional.empty();
    }
    return dataManager.load(AiExtractionDraft.class)
            .query("""
                    select e from ai_AiExtractionDraft e
                    where e.id = :draftId
                      and e.expiresAt > :now
                      and e.confirmed = false
                    """)
            .parameter("draftId", draftId)
            .parameter("now", OffsetDateTime.now())
            .optional();
}
```
Apply the same expiry/confirmed check inside `DraftLoader.loadDraft()` so navigation races cannot bypass it, and add tests with an expired row that still exists.

### BL-03: Streaming UI Persists First-Turn Conversations Before Guards Run

**Severity:** BLOCKER  
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:689`  
**Issue:** `onSubmit()` calls `ensureConversationIdForSubmit()` before `chatService.stream(...)` (`ChatPanelFragment.java:688-693`). That helper creates the conversation with the raw first message as title (`ChatPanelFragment.java:829-846`). This bypasses the service invariant that rate-limit checks happen before `conversationGateway.loadOrCreate()` (`DefaultChatServiceImpl.java:461-473`), so a rate-limited first turn still leaves a conversation/title row behind.

**Fix:**
```java
UUID targetConversationId = conversationId;
Flux<StreamingEvent> source = chatService.stream(userId, targetConversationId, text, null, selectedIntentId);
```
Let `DefaultChatServiceImpl.stream()` create the conversation after guard checks and update `conversationId` from the terminal `StreamingEvent.Final`. If an upload pre-created a conversation, reuse that id; otherwise do not reserve a new one in the UI before guard evaluation.

### BL-04: Customer Draft Payload Re-Serializes the Full Jmix Entity

**Severity:** BLOCKER  
**File:** `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java:96`  
**Issue:** The extractor validates a narrowed map but then converts it to a `Customer` entity (`CustomerDraftIntentExtractor.java:95-98`, `156-160`). `ExtractionService` serializes that entity back into the persisted draft payload (`ExtractionService.java:106-110`). Jackson will use the entity getters, reintroducing schema-excluded fields such as `id`, `version`, and relationships as null/internal payload entries. This breaks the schema contract and can cause draft apply/audit counts to include fields the LLM was never allowed to produce.

**Fix:**
```java
public Map<String, Object> extract(ExtractionInput input) {
    MetaClassDtoSynthesizer.SynthesizedSchema schema = schemaSynthesizer.buildSchema(Customer.class);
    Map<String, Object> rawPayload = callModel(input, schema);
    LinkedHashMap<String, Object> narrowedPayload = validatePayload(rawPayload, schema);

    Customer customer = metadata.create(Customer.class);
    customer.setName((String) narrowedPayload.get("name"));
    customer.setEmail((String) narrowedPayload.get("email"));
    customer.setPhone((String) narrowedPayload.get("phone"));
    validateCustomer(customer, narrowedPayload.size());

    return narrowedPayload;
}
```
Either return a DTO/map that contains only synthesized-schema attributes, or have `ExtractionService` filter serialized payloads against the schema and omit null/system/collection fields. Do not use `ObjectMapper.convertValue(..., Customer.class)` as the entity creation path.

### BL-05: Source-Faithfulness Is a Fixture, Not an Enforcement Path

**Severity:** BLOCKER  
**File:** `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java:121`  
**Issue:** The fixture marks fabricated values as a critical failure with no draft (`extraction-fixtures.yaml:55-67`), but production only checks allowed keys, value types, email bean validation, and phone format (`CustomerDraftIntentExtractor.java:121-149`, `164-168`). A model response that fabricates a syntactically valid email not present in the source will pass and create a draft.

**Fix:**
Require provenance for each extracted field and reject unsupported values before returning a payload. For example, request `{value, evidence}` per field, verify each evidence span exists in the user message or extracted document text where possible, and fail with `ExtractionSchemaException.validationFailure(...)` when evidence is missing. Add a unit/integration test that stubs a fabricated-but-valid email and asserts no draft is created.

## Warnings

### WR-01: Stop Cannot Cancel Through the Registry During an Active Stream

**Severity:** WARNING  
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:591`  
**Issue:** `stopActiveStream()` only calls `cancellationRegistry.cancel(activeRunId)` when `activeRunId` is non-null (`ChatPanelFragment.java:591-602`), but `activeRunId` is assigned only when a terminal `StreamingEvent.Final` arrives (`ChatPanelFragment.java:702-705`). During the actual streaming window, Stop falls back to raw `dispose()`, bypassing the registry path promised by the `ChatService` cancellation contract (`ChatService.java:96-99`).

**Fix:** Emit the run id before content starts, for example with a `StreamingEvent.Started(runId, conversationId)` event, set `activeRunId` from it, and register/cancel exclusively through `CancellationRegistry`. Add a UI/unit test that clicks Stop before `Final` and verifies `cancellationRegistry.cancel(runId)`.

### WR-02: Failed Extraction Tool Results Show a Misleading Invalid-Payload Toast

**Severity:** WARNING  
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java:136`  
**Issue:** The renderer treats every `prepare_form_draft` `ToolResult` without a valid structured payload as `draftPayloadInvalid()` (`StreamEventRenderer.java:136-143`). The audit decorator emits `ToolResult` for both success and thrown/error paths (`ToolCallbackAuditDecorator.java:137-161`), so denied or failed extraction attempts surface as "draft payload could not be rendered" instead of staying silent or showing the real failure.

**Fix:**
```java
private static RenderedStreamEvent renderToolResult(StreamingEvent.ToolResult toolResult) {
    if (!PREPARE_FORM_DRAFT_TOOL.equals(toolResult.toolName())) {
        return RenderedStreamEvent.markdown("");
    }
    if (toolResult.outcome() != AiToolCallOutcome.SUCCESS) {
        return RenderedStreamEvent.markdown("");
    }
    DraftPayload draftPayload = parseOpenFormWithDraftPayload(toolResult.payloadJson());
    return draftPayload == null
            ? RenderedStreamEvent.invalidDraftPayload()
            : RenderedStreamEvent.draftPayload(draftPayload);
}
```
Cover `ERROR` and `DENIED` prepare-form results in `RenderStreamEventIntentPayloadTest`.

### WR-03: Critical Eval/Scanner Tests Do Not Execute the Critical Paths

**Severity:** WARNING  
**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionEvaluationContractTest.java:14`  
**Issue:** The eval contract explicitly uses deterministic fixtures rather than a live model or Jmix runtime (`ExtractionEvaluationContractTest.java:14-17`) and then asserts the fixture contents (`ExtractionEvaluationContractTest.java:76-88`, `109-125`). That lets source-faithfulness and expired-draft requirements appear covered while the implementation paths in BL-02 and BL-05 remain untested.

**Fix:** Keep the fixture corpus, but add executable tests that drive the real implementation boundaries: `CustomerDraftIntentExtractor` with a fabricated-but-valid field, `ExtractionService` serialization of an entity return value, `OpenFormWithDraftHandler`/`DraftLoader` with an expired row still present, and a prepare-form `ToolResult` with `ERROR` outcome.

---

_Reviewed: 2026-05-08T08:15:43Z_  
_Reviewer: the agent (gsd-code-reviewer)_  
_Depth: standard_
