# Phase 14: Intent-Driven Extraction -> Form Prefill - Research

**Created:** 2026-05-07
**Mode:** forced fresh research (`--research`)
**Status:** Ready for planning

## Inputs Read

- `.planning/phases/14-intent-driven-extraction-form-prefill/14-SPEC.md`
- `.planning/phases/14-intent-driven-extraction-form-prefill/14-CONTEXT.md`
- `.planning/phases/14-intent-driven-extraction-form-prefill/14-UI-SPEC.md`
- `.planning/phases/14-intent-driven-extraction-form-prefill/14-AI-SPEC.md`
- `.planning/ROADMAP.md` Phase 14 section via `gsd-sdk query roadmap.get-phase 14`
- `gsd-sdk query init.plan-phase 14`
- Existing Phase 13.1 plans for local plan format and wave style
- Jmix skills: entities, views, security roles, services, Liquibase, testing, i18n, fetch plans
- Context7 docs:
  - `/jmix-framework/jmix-context7`
  - `/websites/spring_io_spring-ai_reference`

## Current Documentation Findings

### Jmix 2.8 / Flow UI

- `ViewNavigators.detailView(this, EntityClass.class).newEntity().navigate()` is the documented Flow UI path for opening the primary detail view for a new entity. The implementation plan should add the draft prefill initializer at this navigation boundary.
- Jmix view permission checks are applied through `AccessManager` contexts. The concrete Flow UI class documented by Jmix is `UiShowViewContext`; this is the implementation equivalent of the Phase 14 shorthand "ViewContext" in upstream artifacts.
- `DataContext.create(...)` is the documented way to create new view-tracked entities in detail views. For this phase, the navigator-created entity must remain under the detail view's standard `DataContext` and save lifecycle.
- `EntityAttributeContext` is the Jmix access context for per-attribute checks; existing code already uses `new EntityAttributeContext(metaClass, attributePath)` followed by `accessManager.applyRegisteredConstraints(...)` and `canModify()`.
- Resource roles and row-level roles are standard Jmix annotations: `@ResourceRole` with `@EntityPolicy`, and `@RowLevelRole` with `@JpqlRowLevelPolicy(where = "{E}.userUsername = :current_user_username")`.
- Jmix XML + annotated controller wiring stays the project default. For Phase 14 this means a descriptor-level `<radioButtonGroup id="intentCardRow">`, `@ViewComponent`, `@Subscribe`, and `@Supply(to = "intentCardRow", subject = "renderer")`.

### Spring AI

- Spring AI structured output to `Map<String, Object>` is supported by `chatClient.prompt().user(...).call().entity(new ParameterizedTypeReference<Map<String, Object>>() {})`.
- `MapOutputConverter` / `entity(new ParameterizedTypeReference<...>() {})` requires the model response to be parseable as the expected map. Phase 14's prompt-only strict JSON contract is therefore a real failure boundary, not just style.
- Declarative tools use `@Tool` on methods and `@ToolParam` on parameters. Existing code already discovers tool-bearing beans with `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()`.
- Spring AI media input is already wired in this repo through `chatClient.prompt().user(u -> { u.text(message); u.media(...); })`. Phase 14 extraction should reuse that lambda form inside the extractor rather than building a separate chat surface.

## Codebase Findings

### Persistence and Security

- `AiTaskFile` is the exact entity pattern for `AiExtractionDraft`: `@Store(name = "agentstore")`, `@JmixEntity`, UUID id with `@JmixGeneratedValue`, `@Version`, `@InstanceName`, `@Table(indexes = ...)`, no Lombok.
- The agentstore root changelog is `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml` and already uses `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog">`. New changelogs are auto-discovered.
- `100-ai-task-file-drop-dead-columns.xml` already exists. Although SPEC says `100-ai-extraction-draft.xml`, the new draft changelog should use `110-ai-extraction-draft.xml` to avoid a duplicate sequence number and preserve lexical order under `includeAll`.
- `AiAgentUserRole` currently grants CRUD surface for conversations, messages, and task files. It must add `AiExtractionDraft` entity policy.
- `AiAgentUserRowLevelRole` currently has ownership policies for `AiConversation`, `AiMessage`, and `AiTaskFile`; the draft policy should mirror task files and filter directly on `userUsername`.
- `AiInternalEntityNames` currently excludes audit, conversations, messages, knowledge documents, parameters, UI settings, exposure rules, and mutation intents. Add `ai_AiExtractionDraft` so drafts never appear in the LLM-visible schema.

### Existing Tool and Chat Pipeline

- `AgentToolCallbacks.forCurrentUser()` aggregates:
  - built-in read tools
  - link tools
  - host `ToolContributor` beans
  - conditional mutation tools
- `AgentToolCallbacks.callbacksFor(String userId, UUID conversationId)` currently delegates to `forCurrentUser()`. Phase 14 needs an intent-aware overload or request context so named-intent turns return only `prepare_form_draft`.
- `DefaultChatServiceImpl.ask(...)` and `stream(...)` currently accept no intent parameter and call `toolCallbacks.callbacksFor(userId, convId)`. The send contract must be extended in both blocking and streaming paths.
- `ChatPanelFragment.onSubmit(...)` currently calls `chatService.stream(userId, targetConversationId, text, null)` and uses a programmatically-created Vaadin `MessageInput`. Phase 14 must keep that substrate and add only the intent id argument.
- `AgentSystemPromptRulesComposer.effectiveRules()` currently appends mutation rules when mutation tools are enabled. Named-intent rules should be added here with a new overload taking the selected intent label/id, rather than hardcoding prompt text in the view.
- `AiTaskFileMediaResolver.resolveActive(convId)` returns all active per-turn media and document text. The extractor service should reuse this model for `ExtractionInput.taskFileMedia` and `taskFileIds`.
- `StreamingEvent.ToolResult` currently carries `(toolCallId, summary, outcome)`. `StreamEventRenderer` ignores it. The plan must either extend the event to carry structured payload JSON or parse `summary` if it is the only available value, then render the confirm row through `ChatPanelFragment`.

### UI Substrate

- `ChatPanelFragment` uses Vaadin `MessageList` plus raw sibling notice rows appended to `messageListSlot`. `appendNoticeRow(String)` is the correct local precedent for `appendIntentConfirmRow(...)`.
- `clearMessageList()` removes all child elements and recreates `MessageList`; Phase 14 must ensure confirm rows are also removed by this path.
- UI-SPEC locks the intent picker to `<radioButtonGroup id="intentCardRow">` with class `ai-agent-intent-card-row`, not a hand-built flex toggle.
- UI-SPEC locks the confirm row as a raw sibling div with class `ai-agent-intent-confirm`; the button itself must be a server-side Vaadin/Jmix button because click handling touches `AccessManager`, `ViewNavigators`, and `DraftLoader`.
- All new UI text belongs in both `messages_en.properties` and `messages_vi.properties`; existing `LocaleParityTest` enforces this.

### Host Reference Intent

- `Customer` lives in `jmix-app/src/main/java/com/vn/jmixapp/entity/Customer.java`.
- The real modifiable fields are `name`, `email`, and `phone`; `recommendedProducts` is `@OneToMany` and must be excluded from the schema.
- The primary detail view is `CustomerDetailView` with `@PrimaryDetailView(Customer.class)`, so `ViewNavigators.detailView(host, Customer.class).newEntity()` should resolve it by convention.
- Host-side AI extensions already live in `jmix-app/src/main/java/com/vn/jmixapp/ai/`, e.g. `OrderSummaryToolContributor`.
- The reference `CustomerDraftIntentExtractor` belongs in `jmix-app`, not in `ai-agent` core. The add-on core must not import `com.vn.jmixapp.entity.Customer`.

## Architecture Recommendation

### Core Pipeline

1. `ChatPanelFragment` resolves `[Auto] + eligible IntentOption` rows and sends either `intentId = null` or a named `intentId`.
2. `ChatService.stream(..., intentId)` stores intent metadata in the turn context, composes named-intent prompt rules, resolves active task-file media, and asks `AgentToolCallbacks` for callbacks.
3. `AgentToolCallbacks.callbacksFor(..., intentId)` returns full callbacks for Auto and exactly one audited `prepare_form_draft` callback for named intent.
4. The LLM calls `prepare_form_draft(intentId, contextRefs)`.
5. `ExtractionToolBridge` delegates to `ExtractionService.prepare(...)`.
6. `ExtractionService` resolves the intent, checks LLM exposure against the target entity, invokes the host extractor, persists `AiExtractionDraft`, and writes the first append-only audit row.
7. The tool returns `{action:"open_form_with_draft", draftId, entityName, instanceName}`.
8. Chat rendering detects the payload and appends an inline confirm row.
9. `OpenFormWithDraftHandler` reloads the draft on every click, checks `UiShowViewContext`, navigates to the primary detail view, applies `DraftLoader`, and registers the save-time draft deletion.
10. `DraftLoader` applies only modifiable attributes and writes the second append-only audit row `extraction.draft_applied`.

### Implementation Notes

- Use Java 17-compatible APIs even though one UI spec note mentions Java 21; this repository's project contract says Java 17.
- Prefer `LinkedHashMap` for tool output and audit summaries so tests can assert deterministic key order.
- Do not log raw extracted field values, raw model response text, or file content. Audit rows should contain ids, counts, property paths, failure codes, and bounded denied-attribute names.
- User-facing draft persistence and loading use secured `DataManager`; cleanup jobs and audit writes use `UnconstrainedDataManager` only for trusted system-internal paths.
- Raw JPQL `loadValue` / `loadValues` touching agentstore entities must call `.store("agentstore")`.
- The extraction entity name in the reference Customer tests is `jmixapp_Customer`, not `sample_Customer`.

## Pitfalls to Avoid

1. **Duplicate Liquibase sequence.** Do not create another `100-*` file. Use `110-ai-extraction-draft.xml`.
2. **Tool-side navigation leak.** No `@Tool`-bearing class or `ToolContributor` may import `ViewNavigators` or call `.navigate()`. `OpenFormWithDraftHandler` is the only navigation owner.
3. **UI permission API mismatch.** Implement the view check with Jmix Flow UI `UiShowViewContext`, even where upstream shorthand says `ViewContext`.
4. **Schema overreach.** `MetaClassDtoSynthesizer` must exclude collections, system/audit fields, read-only fields, and non-modifiable attributes. Do not include `recommendedProducts`.
5. **Cross-user draft access.** Every user-facing load/save/delete of `AiExtractionDraft` must go through secured `DataManager`.
6. **Silent audit PII leak.** Do not put extracted emails, phone numbers, or raw payloads in `resultSummary`.
7. **Renderer coupling.** `StreamEventRenderer` must remain free of `ViewNavigators`; structured payload detection can return a marker/result object that the fragment consumes.
8. **Prompt placement.** Named-intent rules belong in the system prompt. The per-entity schema instruction belongs in the extractor's user prompt.
9. **One-intent-per-turn.** Named intent turns may call `prepare_form_draft` at most once. Tool gating makes this structural; tests should assert callback count and names.
10. **No raw setter bypass.** `DraftLoader` must centralize attribute application through `setValueIfPermitted`; source scans should reject direct `EntityValues.setValue(...)` outside that helper in the draft package.

## Proposed Plan Waves

| Wave | Plans | Purpose |
|------|-------|---------|
| 1 | 14-01, 14-02 | Foundation: draft entity/security/config and SPI/schema synthesis |
| 2 | 14-03, 14-04 | Extraction tool bridge and chat/prompt/tool-gating plumbing |
| 3 | 14-05, 14-06 | Draft apply/navigation lifecycle and Jmix chat UI |
| 4 | 14-07, 14-08 | Host Customer reference intent, end-to-end tests, scanners/eval gates |

## Verification Strategy

- Unit tests for schema synthesis, strict output parsing, payload coercion, tool callback filtering, and renderer parsing.
- Spring Boot integration tests for draft persistence, row-level isolation, cleanup, audit rows, exposure denial, and extraction service.
- UI tests for the intent card row, confirm row rendering, permission denial, expired draft state, and save-time draft deletion.
- Source scanners for TEST-15 (`ViewNavigators`/`.navigate()` in tool-bearing classes), raw draft `setValue` bypass, and core-to-host `Customer` import leakage.
- Module-scoped build/test gates first (`:ai-agent:ai-agent:test`), then host module tests that include the reference Customer extractor.

