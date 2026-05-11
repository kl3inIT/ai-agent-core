# Phase 14: Intent-Driven Extraction → Form Prefill — Context

**Gathered:** 2026-05-07
**Status:** Ready for planning

<domain>
## Phase Boundary

Adds an intent-driven extraction surface to the chat: a card-row picker above `messageInputSlot` lets the user choose an `IntentExtractor<T>` (or "Auto" for default chat). On a named-intent turn, the LLM tool surface is hard-gated to the single `prepare_form_draft` tool; the engine calls `chatClient.prompt().user(...).media(...).call().entity(Map.class)` against a `MetaClass`-derived JSON-schema instruction, persists an `AiExtractionDraft` row in `agentstore`, and returns a structured payload `{action:"open_form_with_draft", draftId, entityName, instanceName}`. Chat renders an inline "Open form to confirm" button on that payload; click runs `AccessManager.isPermitted(ViewContext)` then `ViewNavigators.detailView(host, EntityClass.class).newEntity().withInitializer(draftLoader).navigate()`. Prefill applies via `DataContext.create` + per-attribute `EntityAttributeContext.canModify`-gated set; raw `setValue` is forbidden. On Save, an `AfterSaveEvent` listener deletes the draft. Engine is entity-generic — the only Customer-specific code lives in the host demo module (`jmix-app`, NOT `ai-agent` core).

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**12 requirements are locked.** See `14-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `14-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- `AiExtractionDraft` Jmix entity in `agentstore` + Liquibase 100 + bilingual messages
- `AiAgentUserRowLevelRole` row-level policy on `AiExtractionDraft.userUsername`; `AiAgentUserRole` CRUD policy
- `IntentExtractor<T>` SPI in `com.vn.agent.spi`; `IntentRegistry` Spring bean
- `MetaClassDtoSynthesizer` (entity-generic, zero host-specific DTOs)
- `ExtractionService` orchestration + `ExtractionToolBridge` exposing `@Tool prepare_form_draft`
- `DraftLoader` per-attribute prefill via `setValueIfPermitted`
- `AiExtractionDraftCleanupJob` (hourly, TTL 1h default)
- Card-row picker + `StreamEventRenderer` extension + controller-side click handler
- ONE bundled reference intent: `CustomerDraftIntentExtractor` in `jmix-app` host module
- Audit via `AuditWriter.writeToolCall` with `eventName=prepare_form_draft` + `extraction.draft_applied` (no new AuditKind)
- Negative test: `ToolNavigationLeakScannerTest` (TEST-15)

**Out of scope (from SPEC.md):**
- LLM-side `ViewNavigators` / UI-mutation primitive
- New `AuditKind` enum value or new audit table
- Server-side auto-Save; `VaadinSession`-cached drafts
- Multi-intent parallel dispatch (EXTRACT-11 stays in backlog)
- Searchable-dropdown picker fallback for >6 intents
- Host-supplied custom detail-view IDs (engine targets primary detail view only)
- Customer-specific code in `ai-agent` core; re-extraction flow; editing `payloadJson` outside Jmix detail view

</spec_lock>

<decisions>
## Implementation Decisions

### Schema synthesis (`MetaClassDtoSynthesizer`)

- **D-01:** Structured-output target type is `Map<String, Object>` via Spring AI's `MapOutputConverter`. The synthesizer builds a JSON-schema string and injects it into the user prompt as "Respond with ONLY a single JSON object that strictly conforms to this schema. No prose, no markdown fences, no extra keys." Spring AI call: `chatClient.prompt().user(promptText).media(media...).call().entity(new ParameterizedTypeReference<Map<String, Object>>(){})`. Engine stays entity-generic; zero runtime classloading or bytecode synthesis.
- **D-02:** Foreign-key (to-one) attributes appear in the schema as `{"type": "string", "format": "uuid"}` — same shape `create_record` already accepts. `DraftLoader` resolves UUID → entity reference at prefill time via `dataManager.load(targetClass).id(uuid).optional()`.
- **D-03:** Excluded from schema by default: (a) system audit attrs (`id`, `version`, `createdBy`, `createdDate`, `lastModifiedBy`, `lastModifiedDate`, `deletedBy`, `deletedDate`); (b) attrs where `EntityAttributeContext.canModify` returns false for the calling user; (c) `@OneToMany` / collection attrs (single-record extraction only in v1.1; EXTRACT-11 deferred); (d) computed `@JmixProperty` attrs without setters.
- **D-04:** Strict-mode is enforced via prompt instruction only — no provider-native `responseFormat = json_schema, strict = true`. Keeps the synthesizer portable across Qwen3.6-A3B, future open-weights models, and any OpenAI-compatible endpoint without per-provider gating.

### LLM context routing

- **D-05:** **Hard tool gating on named-intent turns.** When the picker selection ≠ Auto, `AgentToolCallbacks` returns a single-callback list `[prepare_form_draft]` for that turn. `list_entities`, `find_records`, mutation tools (`create_record` / `update_record` / `bulk_save_records` / `add_related_record` / `remove_related_record`) are stripped. Auto turns retain the full tool surface — explicit simple create/update requests may still use `create_record`/`update_record`, and explicit batch-save requests may use `bulk_save_records`. Phase 14 is draft-first, NOT a replacement for the existing mutation chain.
- **D-06:** **System-prompt rule for named-intent turns.** `AgentSystemPromptRulesComposer` adds a named-intent suffix: when the user has selected intent `{label}`, the assistant MUST call `prepare_form_draft("{intentId}", contextRefs)` to fulfill the request, AND when extracted/generated values are incomplete or ambiguous, the assistant MUST ask the user for missing information instead of proceeding with partial values. Draft promotion to actual create/update only happens through user click in the Jmix detail view (which reuses Phase 11/13 mutation chain via `StandardDetailView` Save).

### Card-row picker UI

- **D-07:** Card row uses **Jmix Flow UI `radioButtonGroup`** declared in `chat-panel-fragment.xml`, populated from `IntentRegistry` in the controller, with a custom `ComponentRenderer` supplied via `@Supply(to="intentCardRow", subject="renderer")`. Cards styled as cards via CSS class `intent-card`. Single-select, keyboard nav, focus, and ARIA semantics inherited from the native component. NO hand-built `<flexLayout>` toggle buttons.
- **D-08:** First option is always "Auto" (default-selected); subsequent options are one card per `IntentExtractor` bean eligible for the current user (filtered by Phase 10 `AiExposureRule` against the extractor's `entityName()`). When zero named intents are eligible, the entire `intentCardRow` is `visible=false`. After a NAMED-intent turn is submitted, picker selection auto-resets to "Auto" immediately (before the response arrives). Auto turns do not reset.

### Click-handler wiring + button placement

- **D-09:** New view-scoped `OpenFormWithDraftHandler` Spring bean (`@Component @VaadinSessionScope`) owns the `ViewNavigators` + `AccessManager` + `DraftLoader` calls. `ChatPanelFragment` autowires it; `StreamEventRenderer` builds the inline confirm button passing a `Consumer<DraftPayload>` that delegates to `OpenFormWithDraftHandler.open(draftId, entityName, instanceName)`. The renderer NEVER imports `ViewNavigators` directly (would fail TEST-15 grep gate even though renderer is not `@Tool`-bearing — keeps separation tight).
- **D-10:** Confirm button rendered **inline inside the assistant message bubble** that carries the `open_form_with_draft` tool result. `StreamEventRenderer` parses tool-result JSON; on `action=="open_form_with_draft"`, appends a 1-line summary "Draft prepared: {instanceName}" + button "Open form to confirm" in the same bubble. No separate confirm-card surface.
- **D-11:** Confirm button is **idempotent / re-clickable** while the draft row exists. Click handler loads draft by `draftId`; if found, reopens the detail view prefilled from the same `payloadJson`. If draft is gone (saved, TTL-reaped, or user cancelled), button transitions to disabled state with msg `chatView.intent.draftExpired` ("Draft expired — ask again").

### Audit shape (denied-attributes + exposure denial)

- **D-12:** **Two TOOL-shaped audit rows linked by `runId`** (NOT a single row patched in place — preserves Phase 9 D-01 append-only invariant):
  1. At LLM tool-call time: `AuditWriter.writeToolCall(eventName="prepare_form_draft", argumentsJson={intentId, contextRefs}, resultSummary={draftId, entityName, instanceName, extractedFieldCount})`.
  2. At prefill time (when user clicks confirm and `DraftLoader` runs): `AuditWriter.writeToolCall(eventName="extraction.draft_applied", argumentsJson={draftId}, resultSummary={appliedFieldCount, deniedAttributeCount, deniedAttributes:[...]})`.
- **D-13:** `resultSummary` JSON includes counts + bounded attribute-name lists capped at 16 entries. If a payload has more than 16 denied attrs, list truncates to first 16 with `truncated: true`. Bounds protect the audit table from oversized rows on hostile/buggy LLM output.
- **D-14:** When a `prepare_form_draft` call targets an entity denied by Phase 10 exposure rules: row written with `outcome=DENIED` + `denialReason="exposure_rule:{ruleId}"` (matching Phase 10/11 mutation-tool idiom). NO draft persisted, NO second `extraction.draft_applied` row. Single audit trail per denied call.
- **D-15:** **SPEC bug to flag in PLAN.md:** SPEC REQ-10 wording "audited as part of the `prepare_form_draft` row metadata" is superseded by D-12's two-row pattern. SPEC.md does NOT need to be edited (it locks contract intent, not the exact audit row shape); planner should reference D-12 for the audit acceptance check.

### Draft lifecycle

- **D-16:** **Close-without-save: TTL reaper only.** No `BeforeCloseEvent` deletion. Any unsaved draft sits in `agentstore` until the hourly cleanup job reaps it (default 1h TTL, configurable via `ai-agent.extraction.ttl-seconds`). Composes with D-11 re-clickability: user who closes the tab by mistake can re-click the same chat-message confirm button to reopen the same draft (no re-extraction round-trip, no token cost).
- **D-17:** **No explicit Cancel button** in the detail view. Save-button + close-X are sufficient affordances; explicit cancel adds UI complexity, extra message-bundle keys, and an extra path that must be tested. TTL reaper is the safety net.

### Multi-file extraction input

- **D-18:** **`ExtractionInput` SPI shape:**
  ```java
  record ExtractionInput(
      String intentId,
      UUID conversationId,
      String userMessage,                   // nullable
      List<UUID> taskFileIds,               // empty list ok
      List<MediaContent> taskFileMedia      // empty list ok
  ) {}
  ```
  Aligns with Phase 13.1 per-turn-all resolver. Reference Customer extractor passes ALL non-expired Media in a single Spring AI call: `chatClient.prompt().user(promptText).media(media1, media2, ...).call().entity(...)`. LLM merges multi-page PDFs / image+text combos into one Customer draft.
- **D-19:** **Zero-file named-intent turn is allowed.** When `taskFileMedia` is empty, the extractor falls back to `userMessage` text (e.g. user types "Customer name: Acme, email: foo@bar.com" or pastes structured data into chat). Reference Customer extractor handles both paths in the same `chatClient.prompt().user(...)` call. SPI does NOT expose a `requiresTaskFile()` flag in v1.1 — defer to backlog if real intents need a stricter gate.

### Reference Customer extractor location & shape

- **D-20:** Reference extractor lives at `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java` (NOT in the engine `ai-agent` core, NOT in a separate "sample" module — host demo module is named `jmix-app`). SPEC.md said "sample module" generically; concrete path is `jmix-app`. The `ai-agent` core has zero `Customer` imports.
- **D-21:** Customer entity (`com.vn.jmixapp.entity.Customer`) has 3 user-modifiable fields: `name`, `email`, `phone`. `MetaClassDtoSynthesizer` will produce a 3-field schema for the reference extractor; `recommendedProducts` (`@OneToMany`) and audit fields excluded per D-03.
- **D-22:** Default config flag `ai-agent.intents.customer-reference.enabled=true` controls whether the reference extractor `@Component` is registered (via `@ConditionalOnProperty`). Hosts that don't want it disable the flag; their own intents still register normally.

### Claude's Discretion

- CSS class names for card-row styling (`intent-card`, `intent-card--selected`, `intent-card-row`) — pick names consistent with Phase 13.1's `attachments-dropzone` / `attachment-card-meta` convention.
- Audit `resultSummary` JSON exact key ordering / Jackson serialization config — pick the lowest-friction option that produces deterministic JSON for grep + test assertion.
- `ToolNavigationLeakScannerTest` discovery mechanism — reflection over Spring's `ToolCallback` registrations vs grep across `ai-agent/src/main/java/**/*.java`. Either works; reflection is more robust to file-layout changes, grep is faster and zero-classpath.
- `IntentRegistry` ordering for the card row (alphabetical by `label()` vs Spring `@Order` vs registration order) — pick deterministic ordering; flag in PLAN.md.
- Message-bundle key naming for card labels — follow existing `chatView.*` namespace from Phase 13.1; specifically `chatView.intent.auto.label`, `chatView.intent.{intentId}.label`, `chatView.intent.draftExpired`.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase 14 contract
- `.planning/phases/14-intent-driven-extraction-form-prefill/14-SPEC.md` — Locked requirements (12) — MUST read before planning. This CONTEXT.md captures HOW; SPEC.md captures WHAT and WHY.

### Project + roadmap context
- `.planning/REQUIREMENTS.md` §EXTRACT-01..10, §ENT-08, §SPI-12, §AUD-06, §SEC-06, §TEST-15 — REQ-IDs mapped to Phase 14
- `.planning/ROADMAP.md` §"Phase 14: Intent-Driven Extraction → Form Prefill" — phase goal + success criteria
- `.planning/PROJECT.md` — project framing; "AI is just another Jmix client" memory rule applies (rely on AccessManager / DataManager for security, no AI-specific exposure layer)

### Prior phases this builds on
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-SPEC.md` — `ToolContributor` SPI shape, `ChatClientFactory.prompt()` pattern, append-only audit (D-01)
- `.planning/phases/10-ai-specific-llm-exposure-policy/10-SPEC.md` — `AiExposureRule` denylist semantics; the eligibility gate D-08 references
- `.planning/phases/11-mutation-capable-built-in-tools/11-SPEC.md` — `MutationSaveExecutor`, `MutationAuthorizationService`, audit-row idioms (`outcome=DENIED`, `denialReason`)
- `.planning/phases/13-chat-task-input-stt-task-scoped-file/13-SPEC.md` + `13-CONTEXT.md` — `AiTaskFile` entity + per-conversation row-level policy
- `.planning/phases/13.1-chat-attachments-rightpane-and-persistent-context/13.1-SPEC.md` + `13.1-CONTEXT.md` — Per-turn-all `Media` resolver (`AiTaskFileMediaResolver.resolveActive`); `ChatPanelFragment` `<split>` layout; verbatim-port preference

### Codebase landmarks
- `ai-agent/src/main/java/com/vn/agent/spi/` — destination for `IntentExtractor<T>` SPI (peer to `ToolContributor`, `ContextContributor`, `ToolGuard`)
- `ai-agent/src/main/java/com/vn/agent/entity/AiTaskFile.java` — pattern reference for new `AiExtractionDraft` entity (UUID + Version + InstanceName + per-user policy + Liquibase + bilingual messages)
- `ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java` — extension point for the new draft row-level predicate
- `ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` §`writeToolCall` (lines 148-189) — already accepts arbitrary `eventName` strings; AUD-06 (already shipped) extended `outcome` enum
- `ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` — pattern reference for `@Tool` + `@ToolParam` + audit + denial outcome wiring; rich-tool-description convention
- `ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` — extension point for D-05 hard tool gating (per-turn callback list filter)
- `ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java` — extension point for D-06 named-intent system-prompt rule
- `ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` — host for new `<radioButtonGroup id="intentCardRow">` + `OpenFormWithDraftHandler` autowire
- `ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java` — currently `ignoredToolResult`; extension point for D-09 inline confirm button
- `ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleAdminService.java` — Phase 10 exposure-rule lookup for the entity-eligibility gate
- `ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java` — pattern reference for `AiExtractionDraftCleanupJob` (`@Scheduled` + `UnconstrainedDataManager` for system-internal writes)

### Host demo module (reference extractor)
- `jmix-app/src/main/java/com/vn/jmixapp/entity/Customer.java` — target entity for the bundled reference intent (3 modifiable fields: `name`, `email`, `phone`)
- `jmix-app/src/main/java/com/vn/jmixapp/view/customer/CustomerDetailView.java` — primary detail view that `ViewNavigators.detailView(host, Customer.class)` resolves to
- `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java` — pattern reference for placement of `CustomerDraftIntentExtractor` in `jmix-app/ai/`

### Memory hooks (apply during planning + execution)
- `feedback_jmix_messages_over_spring.md` — inject `io.jmix.core.Messages` (NOT Spring `MessageSource`) in views
- `feedback_jmix_views.md` / `feedback_jmix_view_listeners.md` — wire fragment buttons via `@Subscribe`/`@Install`
- `feedback_jmix_unconstrained_for_system_writes.md` — `UnconstrainedDataManager` for cleanup-job + system-internal writes; `DataManager` for user-facing paths
- `feedback_rich_tool_descriptions.md` — `prepare_form_draft` `@Tool` description follows MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES sections (~50–150 lines)
- `feedback_no_abbreviations.md` — full identifier names; avoid `mp`, `mc`, `dt`, etc.
- `project_self_hostable_models_only.md` — extraction model = active `AiParameters` profile (default Qwen3.6-35B-A3B from Phase 13)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- `AuditWriter.writeToolCall(...)` — already accepts arbitrary `eventName`; both `prepare_form_draft` (D-12 row 1) and `extraction.draft_applied` (D-12 row 2) reuse it. AUD-06 (already shipped) extended `outcome` enum.
- `AiToolCallOutcome.DENIED` + `denialReason` field — already used by Phase 10/11 mutation tools; D-14 reuses the idiom for exposure-rule denial.
- `AiTaskFileMediaResolver.resolveActive(UUID conversationId)` — Phase 13.1 per-turn-all resolver. `ExtractionService` calls it (or accepts the result via injection from `DefaultChatServiceImpl`) to populate `ExtractionInput.taskFileMedia`.
- `AiTaskFileCleanupJob` — direct pattern for `AiExtractionDraftCleanupJob` (same `@Scheduled` shape + `UnconstrainedDataManager` for system writes).
- `AiAgentUserRowLevelRole` — add a fourth `@JpqlRowLevelPolicy` for `AiExtractionDraft.userUsername`.
- `AgentToolCallbacks` — currently aggregates all `@Tool`-bearing beans + `ToolContributor` extensions. Adds per-turn filter point for D-05 hard gating.
- `MetadataTools.getInstanceName(...)` — Jmix built-in for computing the `instanceName` field on the draft (avoids a parallel impl).
- `EntityAttributeContext.canModify(...)` — Jmix built-in used by D-03 (schema attr filter) AND D-12/D-13 (`DraftLoader` per-attr gate). Same primitive for both phases of the pipeline.
- `Jmix Flow UI radioButtonGroup` + `@Supply(to=..., subject="renderer")` — D-07 pattern (parallels Phase 13.1's DataGrid renderer in `feedback_jmix_datagrid_renderer.md` memory).

### Established Patterns

- **Append-only audit (Phase 9 D-01):** rows carry both `startedAt` and `finishedAt` at insert; no in-place updates of result fields. D-12's two-row design respects this.
- **Default-OFF mutation tools (Phase 11):** mutation `@Tool` beans are gated by `@ConditionalOnProperty`. D-22 follows the pattern for `CustomerDraftIntentExtractor`.
- **Verbatim port from CRM / jmix-app reference (Phase 13.1):** when a UI pattern exists in the reference apps, port it before inventing.
- **`UnconstrainedDataManager` for system-internal writes under jmix-security-data (memory):** `AiExtractionDraftCleanupJob` uses it; user-facing draft reads/writes use `DataManager` (row-level policy enforced).
- **Messages from `io.jmix.core.Messages`:** ALL UI text uses `msg://` keys; messages added to ALL locale files (`messages.properties`, `messages_vi.properties`) per CLAUDE.md.

### Integration Points

- **`DefaultChatServiceImpl`** — needs an `intentId` field on the chat-send request shape so the picker selection reaches the chat service. When non-null, `AgentToolCallbacks` returns the single-callback list (D-05) and `AgentSystemPromptRulesComposer` adds the named-intent suffix (D-06).
- **`ChatPanelFragment` send action** — current `messageInputSlot` send wires only the user text + media. Phase 14 adds the picker selection to the send payload.
- **`StreamEventRenderer`** — currently the `ToolResult` branch is `ignoredToolResult`. Phase 14 extends it to detect `action=="open_form_with_draft"` and render the inline confirm button + summary line.
- **`ToolContributor` SPI** — `ExtractionToolBridge` implements it OR is registered as a plain `@Tool`-bearing `@Component`; pick the simpler path (likely the latter, matching `BuiltInMutationTools`).
- **Liquibase root `changelog.xml`** — new `100-ai-extraction-draft.xml` included alongside Phase 13.1's `090-*` and `100-drop-ai-task-file-message-and-injected-at.xml`.
- **`AiInternalEntityNames`** — add `ai_AiExtractionDraft` so it's hidden from the LLM-visible entity list (mirrors `AiTaskFile`).

</code_context>

<specifics>
## Specific Ideas

- **Engine entity-generic.** User's mandate from spec round 1: "Customer intent is only the bundled reference proving the generic engine against one real host detail view. The add-on core must work for any eligible Jmix entity/detail view without Customer-specific code." Drives D-01 (Map target type), D-03 (canModify-filtered schema), D-20 (host-module placement), D-22 (config-flag toggle).
- **Card-row picker — verbatim Jmix Flow UI primitive.** User explicitly directed: "Use Jmix Flow UI `radioButtonGroup` as the card-row component, with a custom `ComponentRenderer` supplied via `@Supply` … Do not hand-build toggle buttons." → D-07.
- **Draft-first, NOT mutation-replacement.** User's framing for tool-gating: "Phase 14 draft workflow does not replace existing mutation tools. Auto chat keeps the normal tool surface … Named intent turns are draft-first and expose only `prepare_form_draft`. Draft promotion to create/update/bulk save happens only after explicit user action and reuses the existing Phase 11/13 mutation chain." → D-05 + D-06.
- **Searchable-dropdown picker fallback deferred.** User: "Searchable dropdown support is deferred until a real app has enough intents to require it." → SPEC.md out-of-scope locked; still relevant for planning to NOT speculatively wire it.
- **"Single intent per turn"** (spec round 2 confirmation) — overrides any multi-intent ambiguity. Card row is single-select; `prepare_form_draft` may be called at most once per turn. EXTRACT-11 stays in backlog.
- **Append-only audit constraint** flagged D-15: SPEC REQ-10 wording is superseded by D-12 two-row design. Planner uses D-12 for audit acceptance assertion.

</specifics>

<deferred>
## Deferred Ideas

- **Searchable-dropdown picker fallback for >6 eligible intents** — defer until a real app has enough intents to require it. SPEC out of scope.
- **`IntentExtractor.requiresTaskFile()` flag** — not exposed on the SPI in v1.1; add when a real intent needs the gate.
- **Multi-intent parallel dispatch (EXTRACT-11)** — backlog. Card picker stays single-select.
- **Host-supplied custom detail-view IDs** — engine targets the entity's primary detail view only. Future SPI extension if a host needs a non-primary view.
- **Re-extraction / refresh button** in chat — not in v1.1. Re-clicking the same confirm button reopens the SAME draft; running extraction again requires a fresh chat turn.
- **Editing the draft `payloadJson` outside the Jmix detail view** — drafts are read-only after creation; edits happen in the form.
- **Inline draft preview / diff card before opening the form** — SPEC declined; bubble shows just `instanceName` + button.
- **Per-conversation TTL override for drafts** — config is global today (`ai-agent.extraction.ttl-seconds`). Per-conversation override could land later (mirrors Phase 13.1 deferred idea for task-file TTL).
- **Explicit Cancel button on the detail view** → D-17 declined. TTL reaper is the safety net.
- **Provider-native `responseFormat = json_schema, strict = true`** → D-04 declined for portability; revisit if extraction-failure rate justifies per-provider handling.
- **Batch-extraction tool** (`prepare_bulk_form_drafts`) — not in scope; would be a Phase 11/13-style mutation tool.

</deferred>

---

*Phase: 14-intent-driven-extraction-form-prefill*
*Context gathered: 2026-05-07*
*Next step: `/gsd-plan-phase 14` — plan from 12 SPEC requirements + 22 implementation decisions above*
