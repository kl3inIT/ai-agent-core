# Phase 14: Intent-Driven Extraction → Form Prefill — Specification

**Created:** 2026-05-07
**Ambiguity score:** 0.092 (gate: ≤ 0.20)
**Requirements:** 12 locked

## Goal

The chat user picks one named intent from a card row above the message input, optionally attaches an `AiTaskFile`, and sends a turn. An entity-generic `IntentExtractor<T>` (one bundled reference impl for Customer, the rest provided by hosts) calls Spring AI structured output against a `MetaClass`-derived schema, persists an `AiExtractionDraft` row in `agentstore`, and the LLM's only available extraction tool — `prepare_form_draft(intentId, contextRefs)` — returns a structured payload `{ "action": "open_form_with_draft", "draftId": "...", "entityName": "...", "instanceName": "..." }`. `ChatPanelFragment` renders an "Open form to confirm" button on that payload; clicking the button (controller-side, after `AccessManager.isPermitted(ViewContext)`) opens the entity's primary Jmix detail view via `ViewNavigators.detailView(host, EntityClass.class).newEntity().withInitializer(draftLoader)`. Prefill applies through `DataContext.create(...)` + `EntityAttributeContext.canModify`-gated `setValueIfPermitted` (never raw `setValue`); `dataContext.validate()` runs before Save; on Save the draft is deleted via an `AfterSaveEvent` listener; the LLM never receives `ViewNavigators` or any UI-mutation primitive.

## Background

No extraction code exists yet. Phase 9 (`ToolContributor`/`ContextContributor` SPI shape, `ChatClientFactory.prompt()`), Phase 10 (`AiExposureRule` admin denylist), Phase 11 (`MutationSaveExecutor` + `MutationAuthorizationService` + `AuditWriter.writeToolCall` chain), Phase 12 (`ChatPanelFragment` mounted in two surfaces — `ChatView` FULL_ROUTE and `ChatDialogView` HEADER_BUTTON), and Phase 13 + 13.1 (`AiTaskFile` persisted in `agentstore` with row-level `userUsername` policy, per-turn-all `Media` resolver) have all shipped. The pieces Phase 14 will plug into:

- `ai-agent/src/main/java/com/vn/agent/spi/` — destination for the new `IntentExtractor<T>` SPI (keeps the existing pattern: `ToolContributor`, `ContextContributor`, `ToolGuard`, etc.)
- `ai-agent/src/main/java/com/vn/agent/entity/` — destination for `AiExtractionDraft` (peer to `AiTaskFile`)
- `ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java` — gains a row-level predicate for `AiExtractionDraft.userUsername`
- `ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` — `writeToolCall` already accepts arbitrary `eventName` strings; `prepare_form_draft` joins `create_record`/`update_record`/`add_related_record`/`remove_related_record`/`bulk_save_records` (AUD-06 already extended)
- `ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` — gains the `intentCardRow` above `messageInputSlot`
- `ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java` — currently `ignoredToolResult` for tool-result events; Phase 14 extends it to detect the `open_form_with_draft` payload shape and render the confirm button
- `ai-agent/src/main/java/com/vn/agent/exposure/` — Phase 10 `AiExposureRule` lookup for the "is this entity eligible as an intent target for this user?" gate
- `ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` — pattern reference for `@Tool` + `@ToolParam` + audit + denial outcome wiring

The phase ships the SPI plus exactly one reference end-to-end intent (Customer draft from an attached PDF/image `AiTaskFile`), proving the engine against a real Jmix detail view without any Customer-specific code in the engine itself. Hosts add intents by registering a Spring `@Component` that implements `IntentExtractor<T>`.

## Requirements

1. **`AiExtractionDraft` entity (ENT-08, EXTRACT-04)**: Persisted draft row in `agentstore`, row-level scoped by `userUsername`.
   - Current: No draft entity exists.
   - Target: New `@JmixEntity @Entity(name="ai_AiExtractionDraft") @Store(name="agentstore")` class with `id` (UUID + `@JmixGeneratedValue`), `version` (`@Version`), `userUsername` (NotNull, indexed), `targetEntityName` (NotNull — the Jmix entity name to instantiate), `intentId` (NotNull), `payloadJson` (NotNull, `text` column), `sourceConversationId` (NotNull, indexed), `sourceTaskFileId` (nullable UUID), `instanceName` (NotNull, used for the chat confirm-card label), `createdAt` (NotNull), `expiresAt` (NotNull, default `now() + 1h`), `confirmed` (boolean, default false). Liquibase changelog `100-ai-extraction-draft.xml` included in root `changelog.xml` adds table `AI_EXTRACTION_DRAFT` with indices on `USER_USERNAME` and `EXPIRES_AT`. Excluded from LLM-visible surface via `AiInternalEntityNames`.
   - Acceptance: `@SpringBootTest` creates an `AiExtractionDraft` via `DataManager`, asserts row appears in `agentstore.AI_EXTRACTION_DRAFT`; query as user `bob` returns rows where `userUsername='bob'` only (row-level policy from REQ-2).

2. **Row-level policy on `AiExtractionDraft` (SEC-06 completion)**: `AiAgentUserRowLevelRole` predicates user-owned drafts only.
   - Current: `AiAgentUserRowLevelRole` covers `AiConversation`, `AiMessage`, `AiTaskFile` only.
   - Target: New `@JpqlRowLevelPolicy(entityClass=AiExtractionDraft.class, where="{E}.userUsername = :current_user_username")` method; `AiAgentUserRole` resource role extended with `@EntityPolicy` + `@EntityAttributePolicy` granting `READ + CREATE + UPDATE + DELETE` on `AiExtractionDraft` for the owner.
   - Acceptance: `@UiTest` test sets up two users `alice` and `bob`; `bob` cannot read/update/delete a draft row created by `alice` (DataManager throws `AccessDeniedException` or returns empty result). `alice` can.

3. **`IntentExtractor<T>` SPI (SPI-12, EXTRACT-02)**: Host-extension point for adding intents.
   - Current: No SPI exists.
   - Target: New `interface IntentExtractor<T> { String intentId(); String label(); String description(); Class<T> targetType(); String entityName(); T extract(ExtractionInput input); }` in `com.vn.agent.spi`. `ExtractionInput` carries `String intentId`, `UUID conversationId`, `String userMessage` (nullable), `Optional<UUID> taskFileId`, `Optional<MediaContent> taskFileMedia`. Add-on registers all `@Component` beans implementing the interface into a Spring-managed `IntentRegistry` bean.
   - Acceptance: Test registers two `@Component` `IntentExtractor` beans in `@SpringBootTest`; `IntentRegistry.eligibleFor(currentUser)` returns both unless one is excluded by `AiExposureRule` for that user (REQ-10).

4. **Reference Customer extractor (EXTRACT-02 reference impl)**: One bundled end-to-end intent proving the engine against a real Jmix detail view.
   - Current: No reference extractor exists.
   - Target: New `@Component class CustomerDraftIntentExtractor implements IntentExtractor<Customer>` in the `sample` module (NOT in `ai-agent` core — keeps the engine entity-generic, no Customer imports in the add-on). Uses `chatClient.prompt().user(...).call().entity(extractionDtoClass)` where `extractionDtoClass` is a `MetaClass`-derived DTO synthesized via the engine's generic `MetaClassDtoSynthesizer` (REQ-5). Engine ships at most a default config flag `ai-agent.intents.customer-reference.enabled=true` so hosts who don't want the reference can disable it.
   - Acceptance: With the sample module on the classpath and a 100KB customer-info PDF attached as an `AiTaskFile`, sending a turn with the Customer card selected results in (a) exactly one `AiExtractionDraft` row persisted with `targetEntityName='sample_Customer'`, (b) `payloadJson` parses as a `Customer` DTO with at least `firstName` + `lastName` populated from the PDF text, (c) `prepare_form_draft` audit row written with `outcome=SUCCESS`.

5. **`MetaClass`-derived extraction DTO (entity-generic engine)**: Engine synthesizes the structured-output target type from `MetaClass` only — no host-specific DTO classes in the engine.
   - Current: No DTO synthesis path exists.
   - Target: New `MetaClassDtoSynthesizer` in `ai-agent` core that, given a `Class<T>` (or `MetaClass`), produces a JSON-schema-strict prompt instruction listing the entity's modifiable string/number/date/enum/FK attributes (filtered by Phase 10 exposure for the calling user) so Spring AI's structured-output binding can populate the target type via `chatClient.prompt().call().entity(targetClass)`.
   - Acceptance: Unit test calls `MetaClassDtoSynthesizer.buildSchema(Customer.class, currentUser)` and asserts the generated schema includes only attributes that pass `EntityAttributeContext.canModify` for the user; excludes any attribute hidden by an `AiExposureRule`; schema is a syntactically valid OpenAI/Spring-AI structured-output schema (parseable JSON).

6. **`ExtractionService` orchestration (EXTRACT-05)**: Single transactional service that ties the pieces together.
   - Current: No `ExtractionService` exists.
   - Target: New `@Service class ExtractionService { ExtractionResult prepare(String intentId, ExtractionInput input); }` returning `ExtractionResult { UUID draftId, String entityName, String instanceName }`. Implementation: (a) resolve `IntentExtractor` from `IntentRegistry`; (b) check Phase 10 exposure for the extractor's `entityName()` against current user → throw `ToolDeniedException` if denied; (c) call `extractor.extract(input)`; (d) serialize result to `payloadJson` via Jackson; (e) compute `instanceName` via Jmix `MetadataTools.getInstanceName(...)`; (f) persist `AiExtractionDraft` via `DataManager.save(...)` with `expiresAt = now + 1h`.
   - Acceptance: `@SpringBootTest` invokes `ExtractionService.prepare("customer-from-pdf", input)`; asserts the returned `draftId` matches a single row in `AI_EXTRACTION_DRAFT`; asserts the row's `userUsername` equals the calling user; asserts a `prepare_form_draft` audit row exists with `outcome=SUCCESS`.

7. **`prepare_form_draft` tool (EXTRACT-06, AUD-06)**: The single tool the LLM may call for extraction; returns a structured payload, not a navigation primitive.
   - Current: No `@Tool prepare_form_draft` exists. Only mutation/data tools exist (`create_record`, `update_record`, `add_related_record`, `remove_related_record`, `bulk_save_records`, plus read tools).
   - Target: New `@Component class ExtractionToolBridge` exposes `@Tool(name="prepare_form_draft", description="...") Map<String, Object> prepareFormDraft(@ToolParam("intent id") String intentId, @ToolParam("optional context refs: AiTaskFile id and/or free-form text") Map<String, Object> contextRefs)`. Tool calls `ExtractionService.prepare(...)` and returns `Map.of("action", "open_form_with_draft", "draftId", id, "entityName", name, "instanceName", inst)`. `ExtractionToolBridge` does NOT import `ViewNavigators`. Tool registered via the existing `AgentToolCallbacks` aggregator. Tool description follows the rich-tool-description convention (MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES sections).
   - Acceptance: Unit test invokes the tool with a known `intentId` + a `taskFileId` contextRef; asserts the returned map keys = `{action, draftId, entityName, instanceName}`; asserts `action` literal is exactly `"open_form_with_draft"`; asserts a single audit row written with `eventName=prepare_form_draft`.

8. **Card-row picker UI (EXTRACT-01)**: User picks exactly one intent for the next submitted turn from a card row above `messageInputSlot`.
   - Current: `ChatPanelFragment` has no intent UI; sends always go through the default chat path.
   - Target: New `<flexLayout id="intentCardRow">` placed above `messageInputSlot` inside `chat-panel-fragment.xml`. Renders an "Auto" card (always first, default-selected, restores normal chat behavior) plus one card per `IntentExtractor` bean eligible for the current user (Phase 10 exposure-rule filtered + bean enabled). Single-select; selected card visually highlighted. Selection drives the chat-send path: when a NAMED intent is selected, the send turn passes `intentId` to the chat service and the LLM system prompt is augmented to bias toward `prepare_form_draft(intentId, ...)`. After the turn is submitted, the picker selection resets to "Auto" immediately. If zero named intents are eligible, the entire `intentCardRow` is `visible=false`. Cards are localized via `messages_*.properties` `chatView.intent.{intentId}.label` keys (host-supplied; engine reads `IntentExtractor.label()` as fallback).
   - Acceptance: `@UiTest IntentCardRowTest` (a) opens `ChatView` with two registered intents and asserts three cards visible (`Auto`, intent-1, intent-2), Auto highlighted; (b) clicks intent-1 card, asserts intent-1 highlighted; (c) sends a turn, asserts picker selection back to Auto; (d) opens `ChatView` with zero named intents registered, asserts `intentCardRow` not visible (or has zero rendered cards).

9. **Chat-side `open_form_with_draft` rendering + controller navigation (EXTRACT-07)**: Chat detects the structured payload, renders a confirm button, and the click navigates server-side.
   - Current: `StreamEventRenderer` matches `StreamingEvent.ToolResult` with `ignoredToolResult` (no UI side effect today).
   - Target: `StreamEventRenderer` (or a new renderer collaborator owned by `ChatPanelFragment`) parses tool-result JSON; when the parsed JSON has `action == "open_form_with_draft"`, renders an "Open form to confirm" button (label includes `instanceName`) inline in the assistant message bubble. Click handler runs in the Jmix view controller (NOT in any `@Tool`-bearing class): looks up `MetaClass` from `entityName`, calls `accessManager.isPermitted(new ViewContext(detailViewId))`, and if permitted invokes `viewNavigators.detailView(this, entityClass).newEntity().withInitializer(e -> draftLoader.apply(draftId, e)).navigate()`. If not permitted, renders a permission-denied notice in chat (no navigation).
   - Acceptance: `@UiTest OpenFormWithDraftRenderingTest` injects a fake assistant message bubble with a `ToolResult` carrying `{"action":"open_form_with_draft","draftId":"<uuid>","entityName":"sample_Customer","instanceName":"Acme Inc."}`; asserts an "Open form to confirm — Acme Inc." button appears; asserts clicking it triggers a `ViewNavigators.detailView(...)` call (verified via mock or by the Jmix detail view appearing in the test harness); asserts the LLM-side class containing the `@Tool` does NOT call `ViewNavigators` (verified via the TEST-15 grep gate in REQ-12).

10. **`DraftLoader` per-attribute prefill (EXTRACT-08)**: Prefill via `DataContext.create` + `setValueIfPermitted`, never raw `setValue`.
    - Current: No `DraftLoader` exists.
    - Target: New `@Component class DraftLoader { void apply(UUID draftId, Object editingEntity); }` in `com.vn.agent.taskfile`-peer package (e.g. `com.vn.agent.extraction`). Implementation: load `AiExtractionDraft` by id (row-level-policy enforced); deserialize `payloadJson` to a `Map<String, Object>` (NOT into `targetType` — engine stays generic); for each map entry resolve the `MetaProperty`; check `EntityAttributeContext.canModify` for the current user; if permitted, set via the Jmix `EntityValues.setValue(entity, prop, coerced)` pattern that the framework's `setValueIfPermitted` helper wraps (NEVER call `entity.setX(...)` reflectively, NEVER call raw `setValue` bypassing the access check); silently skip denied attrs. After all attributes applied, increment a `deniedAttributes` counter audited as part of the `prepare_form_draft` row metadata. `dataContext.validate()` runs before the user can click Save (standard Jmix detail-view lifecycle); when Phase 10 exposure-rule denial bites at runtime, the controller renders a permission-denied notice.
    - Acceptance: `@UiTest DraftLoaderTest` creates an `AiExtractionDraft` with `payloadJson` containing one permitted attribute and one denied attribute; opens the detail view via the confirm button; asserts the permitted attribute is prefilled; asserts the denied attribute is empty; asserts the audit row's metadata records `deniedAttributes >= 1`.

11. **Draft lifecycle: TTL cleanup + delete-on-save (EXTRACT-09)**: Drafts expire after TTL, hourly cleanup; saved drafts deleted via Jmix Save lifecycle.
    - Current: No cleanup job exists; no save-time deletion path.
    - Target: New `@Component class AiExtractionDraftCleanupJob` runs `@Scheduled(fixedDelayString="${ai-agent.extraction.cleanup-interval-ms:3600000}")` and deletes rows where `expiresAt < now()` via `UnconstrainedDataManager` (system-internal, audit-bypass safe; matches the `AiTaskFileCleanupJob` pattern). Default TTL property `ai-agent.extraction.ttl-seconds=3600`. Jmix StandardDetailView Save reuses the standard Save flow — no custom save coordinator. A new `AfterSaveEvent` listener (registered in the controller-side click handler via `view.addAfterSaveListener(e -> draftRepository.deleteById(draftId))`) deletes the draft row on successful Save. Cancel/close-without-save leaves the draft to be reaped by the cleanup job at TTL expiry. `confirmed` flag is set to true at the moment of successful Save (immediately before delete) for audit grep convenience.
    - Acceptance: `@SpringBootTest CleanupJobTest` inserts a draft with `expiresAt = now() - 1m`; runs the job; asserts the row is gone. `@UiTest SaveDeletesDraftTest` opens the detail view via the confirm button, edits one field, clicks Save; asserts the draft row is gone after Save; asserts the standard Jmix `AfterSaveEvent` fired (verified via test listener).

12. **Negative test: no LLM-side navigation primitive (EXTRACT-10, TEST-15)**: Static guard that the LLM cannot bypass the draft → confirm → controller-side navigation flow.
    - Current: No such test exists.
    - Target: New `@Test ToolNavigationLeakScannerTest` walks all `@Tool`-bearing classes (discovered via reflection over Spring's `ToolCallback` registrations OR a grep across `ai-agent/src/main/java/**/*.java` for `@Tool(`) and asserts none of them imports `io.jmix.flowui.ViewNavigators`, references `ViewNavigators` as a field/parameter, or calls `.navigate()`. Test fails fast with a list of offending classes if any are found. Co-locates with the existing `ToolNameLeakScannerTest`.
    - Acceptance: Test passes against the Phase 14 codebase. Test fails (verified by intentionally adding a `ViewNavigators` field to `ExtractionToolBridge` in a throwaway commit, running the test, observing failure, reverting). The `prepare_form_draft` tool result test (REQ-7) further asserts the tool returns a structured payload only — no navigation side effect server-side at the tool layer.

## Boundaries

**In scope:**
- `AiExtractionDraft` Jmix entity in `agentstore` + Liquibase changelog 100 + root `changelog.xml` include + per-locale messages (entity name, attribute names) in ALL locale files
- `AiAgentUserRowLevelRole` extension for `AiExtractionDraft` ownership
- `AiAgentUserRole` resource-role extension (CRUD on `AiExtractionDraft` for the owner)
- `IntentExtractor<T>` SPI in `com.vn.agent.spi`
- `IntentRegistry` Spring bean
- `MetaClassDtoSynthesizer` (entity-generic engine; no host-specific DTOs)
- `ExtractionService` orchestrating SPI dispatch + audit + persistence
- `ExtractionToolBridge` exposing exactly one `@Tool prepare_form_draft`
- `DraftLoader` for `setValueIfPermitted`-gated prefill
- `AiExtractionDraftCleanupJob` (hourly, TTL default 1h, configurable)
- Card-row intent picker above `messageInputSlot` in `chat-panel-fragment.xml` + `ChatPanelFragment.java` wiring
- `StreamEventRenderer` extension to render the "Open form to confirm" button on `open_form_with_draft` payloads
- Controller-side click handler that calls `accessManager.isPermitted(ViewContext)` then `viewNavigators.detailView(...)` then registers an `AfterSaveEvent` listener to delete the draft
- ONE bundled reference intent: `CustomerDraftIntentExtractor` in the `sample` module (entity-generic engine has zero Customer imports)
- Audit: reuse `AuditWriter.writeToolCall` with `eventName="prepare_form_draft"` (AUD-06 already extended)
- Negative test: `ToolNavigationLeakScannerTest` (TEST-15)

**Out of scope:**
- LLM-side `ViewNavigators` or any UI-mutation primitive — hard rule, enforced by REQ-12 grep gate
- New `AuditKind` enum value or new audit table — reuse `writeToolCall` chain end-to-end
- Server-side auto-Save — engine NEVER auto-saves the draft; the user click in the Jmix detail view is the only save path (where standard `AccessManager` entity policies apply)
- `VaadinSession`-cached drafts — drafts MUST persist in `agentstore` so the form-load by id survives navigation, page reload, and multi-tab
- Multi-intent parallel dispatch — single intent per turn for v1.1; EXTRACT-11 stays in backlog
- Searchable-dropdown picker fallback for >6 intents — deferred until a real app has enough intents to require it
- Host-supplied custom detail-view IDs — engine targets the entity's primary detail view via `ViewNavigators.detailView(host, EntityClass.class)`; non-primary view selection deferred to a future SPI extension
- Host-specific DTO classes baked into the engine — `MetaClassDtoSynthesizer` keeps the engine entity-generic
- Customer-specific code in the `ai-agent` core — the reference extractor lives in the `sample` module
- Re-extraction flow — clicking the confirm button re-uses the existing draft; no re-extract / refresh button this phase
- Editing the draft `payloadJson` outside the Jmix detail view — drafts are read-only after creation; users edit in the form

## Constraints

- Default TTL: `ai-agent.extraction.ttl-seconds=3600` (1 hour). Cleanup job runs hourly via `@Scheduled` (`ai-agent.extraction.cleanup-interval-ms=3600000`).
- Drafts persisted in `agentstore` (NOT `VaadinSession`) so navigation/reload survival holds.
- Intent-extraction model follows the active `AiParameters` profile (no separate model pin in v1.1; EXTRACT-03). Operator docs note that weak-JSON-adherence models may produce parse errors.
- Reference intent ships ONLY in the `sample` module — `ai-agent` core has zero Customer-specific imports.
- Card-row picker hides when zero named intents are eligible (registry empty for current user under Phase 10 exposure rules).
- Card-row picker selection auto-resets to Auto immediately after a turn where a named intent was selected (prevents accidental repeated extraction).
- Engine always navigates to the entity's primary detail view via `ViewNavigators.detailView(host, EntityClass.class)` — host viewId override deferred.
- `AiAgentUserRole` row-level policy on `AiExtractionDraft` is enforced via `DataManager` (not `UnconstrainedDataManager`); the cleanup job is the ONLY system-internal write path and uses `UnconstrainedDataManager`.
- Per-attribute prefill ALWAYS uses `setValueIfPermitted`; raw `setValue` is forbidden (P-18 mitigation).
- `prepare_form_draft` tool description follows the project's rich-tool-description convention (~50–150 lines, MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES sections).
- All UI text uses `msg://` keys; messages added to ALL locale files (`messages.properties`, `messages_vi.properties`, etc.) per CLAUDE.md.

## Acceptance Criteria

- [ ] `AiExtractionDraft` entity persisted in `agentstore.AI_EXTRACTION_DRAFT` with all required columns (REQ-1).
- [ ] Liquibase changelog `100-ai-extraction-draft.xml` runs cleanly on a fresh schema and is included in root `changelog.xml`.
- [ ] `AiAgentUserRowLevelRole` row-level predicate scopes draft reads/writes to `userUsername = :current_user_username` (REQ-2).
- [ ] `IntentExtractor<T>` SPI defined in `com.vn.agent.spi` with `intentId / label / description / targetType / entityName / extract` methods (REQ-3).
- [ ] `IntentRegistry` returns only Phase-10-exposure-eligible intents for the current user (REQ-3, REQ-5).
- [ ] `MetaClassDtoSynthesizer.buildSchema(...)` generates a valid JSON-schema-strict instruction for any Jmix entity, filtered by per-attribute exposure + `canModify` (REQ-5).
- [ ] `ExtractionService.prepare(...)` persists exactly one `AiExtractionDraft` row per call and writes one `eventName=prepare_form_draft` audit row (REQ-6, REQ-7).
- [ ] `prepare_form_draft` tool returns the literal map shape `{action: "open_form_with_draft", draftId, entityName, instanceName}` and triggers no server-side navigation (REQ-7).
- [ ] `ExtractionToolBridge` source code does NOT import `io.jmix.flowui.ViewNavigators` (TEST-15 grep gate, REQ-12).
- [ ] No `@Tool`-bearing class in `ai-agent` (or registered via `ToolContributor`) imports `ViewNavigators` (REQ-12).
- [ ] Card-row picker renders Auto + one card per eligible intent above `messageInputSlot` (REQ-8).
- [ ] Card-row picker selection auto-resets to Auto after a named-intent turn is submitted (REQ-8).
- [ ] Card-row picker is `visible=false` when zero named intents are eligible (REQ-8).
- [ ] `StreamEventRenderer` (or its collaborator) detects `action="open_form_with_draft"` payloads and renders an "Open form to confirm — {instanceName}" button (REQ-9).
- [ ] Confirm-button click runs `AccessManager.isPermitted(ViewContext)` before `ViewNavigators.detailView(...)` (REQ-9).
- [ ] `DraftLoader.apply(...)` populates only attributes that pass `EntityAttributeContext.canModify`; denied attributes are silently skipped and counted in the audit row (REQ-10).
- [ ] `dataContext.validate()` runs as part of the standard Jmix StandardDetailView Save lifecycle before persistence (REQ-10).
- [ ] On successful Save, the corresponding `AiExtractionDraft` row is deleted via `AfterSaveEvent` listener (REQ-11).
- [ ] `AiExtractionDraftCleanupJob` deletes rows where `expiresAt < now()` on its scheduled tick (REQ-11).
- [ ] Reference `CustomerDraftIntentExtractor` lives in the `sample` module, not in `ai-agent` core (REQ-4).
- [ ] End-to-end test: select Customer card → attach customer-info PDF → send → confirm-button appears → click → Customer detail view opens prefilled → edit + Save → draft row deleted (REQ-4 + REQ-9 + REQ-11).
- [ ] All UI text added to ALL locale files; no hardcoded labels.
- [ ] `./gradlew test` passes; `./gradlew build` passes.

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                 |
|--------------------|-------|------|--------|-----------------------------------------------------------------------|
| Goal Clarity       | 0.95  | 0.75 | ✓      | Entity-generic engine; reference target Customer; Spring AI structured output |
| Boundary Clarity   | 0.92  | 0.70 | ✓      | Explicit out-of-scope list locked (8 items)                           |
| Constraint Clarity | 0.88  | 0.65 | ✓      | TTL/cleanup/persisted/denial/exposure-gate/single-intent all pinned  |
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 23 pass/fail checkboxes + TEST-15 grep gate                           |
| **Ambiguity**      | 0.092 | ≤0.20| ✓      |                                                                       |

## Interview Log

| Round | Perspective     | Question summary                                                | Decision locked                                                                 |
|-------|-----------------|-----------------------------------------------------------------|---------------------------------------------------------------------------------|
| 1     | Researcher      | Reference intent target?                                        | Customer (sample module only); engine entity-generic, no Customer in core       |
| 1     | Researcher      | Detail-view resolution?                                         | `ViewNavigators.detailView(host, EntityClass.class)` — Jmix primary-view convention |
| 1     | Researcher      | LLM extraction wire?                                            | Spring AI `chatClient.prompt().call().entity(MetaClassDto)`; schema from `MetaClass` |
| 1     | Failure Analyst | Per-attribute denial behavior?                                  | Silent skip via `setValueIfPermitted` + `deniedAttributes` count in audit row   |
| 1     | Boundary Keeper | Eligibility gate for an entity to be an intent target?          | Phase 10 `AiExposureRule` admin denylist composes with the registry            |
| 2     | Boundary Keeper | Multi-intent per turn?                                          | Single intent per turn; EXTRACT-11 stays deferred                               |
| 2     | Boundary Keeper | Save path after confirm-button click?                           | Reuse Jmix StandardDetailView Save + `AfterSaveEvent` delete-draft listener     |
| 2     | Boundary Keeper | Picker UI shape?                                                | Card row above `messageInputSlot`; Auto + named-intent cards; auto-reset to Auto after named-intent turn; hide when empty registry; searchable-dropdown fallback DEFERRED |
| 2     | Boundary Keeper | Out-of-scope list?                                              | LLM-side `ViewNavigators`, new `AuditKind`, server-side auto-Save, `VaadinSession` drafts, searchable-dropdown picker, host viewId override |

---

*Phase: 14-intent-driven-extraction-form-prefill*
*Spec created: 2026-05-07*
*Next step: /gsd-discuss-phase 14 — implementation decisions (DTO synthesizer schema strictness, card-row CSS class, audit metadata key naming, etc.)*
