# v1.1 Architecture: Integration with Existing v1.0

## Reference Files Inspected

- `.planning/PROJECT.md`
- `.planning/STATE.md`
- `.planning/seeds/SEED-005-floating-user-chat-launcher-and-admin-chat-surface-toggle.md`
- `.planning/seeds/SEED-007-add-ai-specific-llm-exposure-policy.md`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java`

**Confidence — overall HIGH.** All claims are grounded in the read source. Where the choice has trade-offs and current code does not disambiguate, HIGH is named for the recommendation, MEDIUM for the rejected alternative.

---

## 1. Architectural Snapshot of v1.0 (Load-Bearing Facts)

| Element | File | Property v1.1 must respect |
|---|---|---|
| Per-request orchestration | `DefaultChatServiceImpl.ask` lines 161–301 | Single, large per-turn method. RunContext ThreadLocal, IterationCounter, RateLimitGuard / TokenBudgetGuard wrap the LLM call. Any new feature that gates an LLM turn integrates here, never around it. |
| ChatClient bean | `ChatClientFactory.defaultChatClient` lines 65–92 | Singleton ChatClient with fixed advisor chain at fixed orders (HIGHEST_PRECEDENCE, +200, +250, +300, +400). Adding a new advisor is a registration in this method. |
| Per-request tool surface | `AgentToolCallbacks.forCurrentUser` lines 57–74 | Always returns a fresh `ToolCallback[]` wrapped in `ToolCallbackAuditDecorator`. Tools come from `BuiltInDataTools` + each `ToolContributor.contribute()`. The composition step is the single integration seam for both AI exposure policy and mutation tools. |
| Authoritative read-access view | `CurrentUserSchemaAccess.getReadableSchema` lines 44–56 | Walks `metadata.getSession().getClasses()`, applies Jmix `AccessManager` constraints, never cached. Every "what can the LLM see" question funnels through this method today. |
| Baseline prompt | `BaselineContextProvider.compose` / `renderAsText` | Produces deterministic `agent.*` map + sorted text block. Natural extension point for `agent.entities` / `agent.permissions` injection. |
| Audit writer | `AuditWriter.writeChatStart/Finish/writeToolCall/writeRetrieval` | Hard rules: REQUIRES_NEW, `UnconstrainedDataManager`, no proxy self-invocation, `parentId` is always the rootAuditId on `RunContext`. New audit kinds (mutation, draft-extraction, STT) follow the same shape. |
| Chat UI substrate | `ChatPanelFragment` (`@FragmentDescriptor("chat-panel-fragment.xml")`) extends Jmix `Fragment<VerticalLayout>` | Already an embeddable Fragment, exposes `setConversationId / hasMessages / isStreaming / startNewChat`. Phase 7 D-29 already designed it as the reusable surface — v1.1 reuses, does NOT refactor. |

**Critical implication:** the orchestration layer is monolithic-by-intent. v1.1 must add capabilities by composing inside `ask` and `stream` rather than spawning a parallel pipeline. There is no "second backend" sanctioned by v1.0 design.

---

## 2. Feature-by-Feature Integration

### F-A — AI Exposure Policy (SEED-007 activated)

**Decision (HIGH).** Add a single new boundary class `LlmExposurePolicy` that wraps `CurrentUserSchemaAccess`. Not a separate AccessManager — a narrowing filter that runs after AccessManager has produced the user-readable schema. Every existing call site asking `currentUserSchemaAccess.getReadableSchema()` / `canReadEntity` / `canReadAttribute` becomes a call into the new policy, which delegates to `CurrentUserSchemaAccess` first and then subtracts the LLM denylist. Jmix security stays authoritative; the new layer can only narrow.

**New components.**
- `entity/AiExposureRule.java` — `@JmixEntity` UUID + Version + InstanceName. Fields: `entityName`, `attributePath` (nullable; null = whole entity), `mode` (`DENY` only — per Pitfalls research; ALLOW inverts the layer's purpose), `enabled`, audit fields. Lives in `agentstore`.
- `metadata/LlmExposurePolicy.java` — `@Component`, injects `CurrentUserSchemaAccess` + `LlmExposureRuleRepository`.
- `metadata/LlmExposureRuleRepository.java` — `@Component`, uses `UnconstrainedDataManager` (rules apply globally; user roles must NOT be able to bypass by un-granting read on `AiExposureRule`).
- `view/exposure/AiExposureRuleListView.java` + `AiExposureRuleDetailView.java` (+ XML, `menu.xml`, locale files).
- Liquibase changelog under `agentstore`, included in root `changelog.xml`.

**Modified components.**
- `BuiltInDataTools` — replaces all `currentUserSchemaAccess` calls with `llmExposurePolicy` (mechanical: same method names/return types). File deltas at lines 84, 107, 218, 224, 264–283.
- `BaselineContextProvider` — `agent.entities` / `agent.permissions` source from `LlmExposurePolicy.getReadableSchema()`. The LLM must never see denied entity names.
- `RetrievalFilterBuilder` — RAG filter must additionally exclude documents whose source-entity is denylisted; otherwise denylisting `Customer` does nothing for `Customer` KB docs. **Non-obvious but mandatory.**

**Confidence:** HIGH. Alternative (intercept at `AgentToolCallbacks` composition) rejected: would duplicate denylist logic across baseline + RAG, breaking single source of truth.

---

### F-B — Mutation-Capable Built-In Tools

**Decision (HIGH).** Mutation tools live in a separate `BuiltInMutationTools` `@Component`, not as new methods on `BuiltInDataTools`. Reasons:
1. The current ASM test enforces "no `DataManager.save` in `@Tool`" against the read-only class. Keeping that test green is cheap; per-method allowlists invite drift.
2. Hosts opt-in by a single feature flag — easier to gate at the bean level than per-method.
3. Mutation tools need a `@Transactional` boundary that read tools must not have.

**New components.**
- `tools/BuiltInMutationTools.java` — `@Component`, conditional via `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")`. Tools: `create_record`, `update_record`, `delete_record` (optional), `add_related_record`, `remove_related_record`. Layered gating per call, in order, fail-closed:
  1. `LlmExposurePolicy` (admin can deny mutation even where user can mutate).
  2. Jmix `AccessManager` with `CrudEntityContext.isCreatePermitted/isUpdatePermitted/isDeletePermitted` and per-attribute `EntityAttributeContext.canModify`.
  3. Optional `MutationGuard` SPI (host veto).
  4. `@Transactional` `DataManager.save(...)` (regular `DataManager`, NOT `UnconstrainedDataManager`).
- `spi/MutationGuard.java` — new SPI; default no-op bean. Mirrors `ToolGuard`. Veto throws `ToolVetoedException`.
- `properties/AiAgentMutationProperties.java` — `@ConfigurationProperties("ai-agent.tools.mutation")`. Fields: `enabled` (default false), `allowDelete` (default false), `confirmationRequired` (default true; UX hint).
- New audit shape: keep `kind=TOOL`, distinguish via existing `eventName` (`create_record`, etc.) and `argumentsJson` shape including pre-image diff for updates. Avoid a new `AuditKind`.

**Shared helper extraction.** `resolveReadableEntityOrThrow`, `parseEntityId`, and new `resolveWritableEntityOrThrow` land in `tools/ToolEntityResolver.java` (`@Component`) consumed by both `BuiltInDataTools` and `BuiltInMutationTools`.

**Modified components.**
- `AgentToolCallbacks.forCurrentUser` lines 57–74 — accept `Optional<BuiltInMutationTools>`; add to callback list when present. Audit decorator (line 71) already covers it.
- `BaselineContextProvider` — when injecting `agent.permissions`, include CRUD bits per entity (`read`, `create`, `update`, `delete`) and per-attribute `modify`. Sourced from `LlmExposurePolicy + AccessManager`.
- Locale message files in **all** locales: `tool.create.success`, `tool.create.denied.access`, `tool.create.denied.exposure`, etc.

**Audit story.** Reuse `AuditWriter.writeToolCall`. `argumentsJson` carries LLM JSON; `resultSummary` carries new entity id (create) or compact diff summary (update). `outcome` distinguishes `SUCCESS` / `BLOCKED` / `ERROR`. REQUIRES_NEW guarantees the audit row commits even when the mutation transaction rolls back — already there.

**Why exposure policy must land first.** The admin needs `LlmExposurePolicy` rules to deny *write* per entity/attribute *before* mutation tools are switched on; otherwise opt-in is binary host-wide and admins cannot stage rollout.

**Confidence:** HIGH on layout. MEDIUM on whether `BuiltInMutationTools` is `@Transactional` per method or per call — recommend per-method REQUIRED, propagation default; verify against MEMORY `feedback_jmix_unconstrained_for_system_writes`.

---

### F-C — Configurable Chat Surfaces (SEED-005 activated)

**Decision (HIGH).** Three surfaces, **one** `ChatPanelFragment`, **one** `ChatService`, **one** `AiConversation` row per user-session — exactly per SEED-005.

**New components.**
- `view/chat/SidebarChatComponent.java` — Vaadin component (not a `@Route` view) hosting `ChatPanelFragment` inside a Vaadin `Drawer` / `Aside` slot.
- `view/chat/FloatingChatLauncher.java` — Vaadin `Component` rendering a fixed-position `Button` and a `Dialog` containing `ChatPanelFragment`. Bottom-right; opens on click.
- `view/chat/ChatSurfaceMounter.java` — `@Component` listening to `UIInitEvent` to inject configured surface into the host shell. Reads admin toggle and only mounts what's enabled.
- `entity/AiUiSettings.java` — Jmix entity, single-row by convention (mirroring `AiParameters` activation pattern), in `agentstore`. Fields: `enabledSurfaces` (`FULL_ROUTE`, `SIDEBAR`, `FLOATING`), `defaultSurface`, audit fields. **Recommendation (HIGH):** new entity, NOT new fields on `AiParameters` — chat-behavior vs UI-rollout are orthogonal concerns.
- `view/uisettings/AiUiSettingsView.java` (+ XML, menu, locales) — admin-only.

**Modified components.**
- `view/chat/ChatView.java` — no behavioral change; remains a `@Route` view embedding `ChatPanelFragment`.
- `ChatPanelFragment` — only minor additions if any. MEDIUM confidence: `setCompactMode(boolean)` may be needed for the floating dialog to suppress the conversation list.
- Host shell integration must not require host code edits beyond the starter dependency. The floating launcher mounts via add-on `ApplicationContext`-aware UI listeners (starter registers a `UIInitListener` adding the floating button to `UI.getCurrent()`).

**Conversation continuity across surfaces.** Each surface holds its own `ChatPanelFragment` instance, but conversation state lives entirely in `AiConversation` + `AiMessage` (already does). The "current conversation id" follows the user session, not the surface, via a `@VaadinSessionScope` `AiChatSessionState` bean. When the user toggles surface, the new fragment's `setConversationId(state.getCurrentConversationId())` reattaches.

**Confidence:** HIGH on shape. MEDIUM on the exact hook into MainView — verify Jmix 2.8 specifics during planning.

---

### F-D — Speech-to-Text

**Decision (HIGH).** STT happens in two stages with a clear backend boundary:
- **Browser** captures audio via `MediaRecorder` (no JAR; produces `webm/opus` or `mp4`, both Whisper-compatible). Web Speech API rejected for v1.1: language coverage uneven; non-Chromium browsers expose no consistent dictation API.
- **Backend** transcribes via Spring AI's `OpenAiAudioTranscriptionModel` and injects transcribed text into the chat input field, NOT directly into `ChatService.ask`. The user sees the text, can edit, then submits. Audit-friendly because the eventual `ask` carries the same text the user saw.

**New components.**
- `view/chat/fragment/AudioCaptureComponent.java` — Vaadin component with a "mic" button using `executeJs` to invoke `MediaRecorder` and POST the blob to a server endpoint.
- `transcription/TranscriptionService.java` interface + `transcription/SpringAiTranscriptionService.java` impl — wraps Spring AI's transcription model. Returns transcript text. **No new advisor**, no `pre-chat hook`. Transcription never touches the chat client.
- `properties/AiAgentTranscriptionProperties.java` — `@ConditionalOnProperty(prefix="ai-agent.stt", name="enabled", havingValue="true")`. Fields: `model`, `language`, `maxDurationSeconds`.
- `spi/TranscriptionPostProcessor.java` SPI (optional) — host can rewrite transcripts (PII redaction).

**Modified components.**
- `ChatPanelFragment` — adds microphone component beside `MessageInput` (touches `messageInputSlot`).
- Locale messages in all locales.

**STT key requirement (from STACK research).** OpenRouter does not proxy `/audio/transcriptions`. Hosts wanting STT must set `spring.ai.openai.audio.transcription.api-key` + `base-url` separately from the chat key. Document this prominently in operator docs.

**Confidence:** HIGH on the architectural shape. STT does not call the chat client — keeps it out of the per-turn budget logic and iteration cap.

---

### F-E — Intent-Driven Extraction → Jmix Form Prefill

**Decision (HIGH).** Structured-output flow over `ChatService.askTyped` (already implemented `DefaultChatServiceImpl.askTyped` lines 434–485), NOT a new advisor or parallel pipeline.

**New components.**
- `entity/AiExtractionDraft.java` — Jmix entity in `agentstore`. Fields: `id`, `userUsername`, `targetEntityName`, `payloadJson`, `sourceConversationId`, `createdAt`, `expiresAt`, `confirmed`. Lifecycle: created by extractor, deleted after `confirmed` form submit or after TTL (e.g., 1 hour). **Persisted, not transient cache** — survives navigation; form loads by id from URL parameter.
- `extraction/IntentExtractor.java` interface — SPI. `interface IntentExtractor<T> { Class<T> targetType(); String entityName(); T extract(ExtractionInput input); }`. Hosts implement per-intent extractors. Add-on may ship one generic implementation using `askTyped` against a metadata-derived DTO.
- `extraction/ExtractionService.java` — orchestrates: receives uploaded file or text, identifies intent, dispatches to `IntentExtractor`, persists `AiExtractionDraft`, returns draft id.
- `extraction/ExtractionToolBridge.java` — exposes a single `@Tool prepare_form_draft(entityName, contextText)` the LLM may call. The tool **does not** trigger view navigation. It returns `draftId + instance_name` summary; the chat UI client interprets the response and offers a "Open form to confirm" button.
- `extraction/DraftLoader.java` — thin helper for host detail views.

**Modified components.**
- `view/chat/fragment/ChatPanelFragment` — extends response renderer: when assistant message contains structured `open_form_with_draft` payload, render a button. Click calls `ViewNavigators.detailView(...)` on the Jmix UI side, passing draft id as URL parameter (per MEMORY `feedback_jmix_query_parameters_event` — read via `View.QueryParametersChangeEvent`).
- Each host detail view supporting prefill — opens via `?draftId=...` query param; in `onInit` or `onBeforeShow` loads draft, applies `payloadJson` via `DataContext` (NOT raw `setValue` — preserves Jmix validators per Pitfall 18).

**Critical constraint.** The LLM never gets `ViewNavigators` or any UI-mutation primitive. The chat UI maps `prepare_form_draft` result to a confirmation button via a recognized JSON shape (`{ "action": "open_form_with_draft", "draftId": "...", "entityName": "..." }`).

**Why this shape.** Preserves "AI is just another Jmix client" (MEMORY `feedback_ai_as_jmix_client`). LLM produces structured data; user does navigation; jmix-security-data + AccessManager validate the eventual `DataManager.save` exactly as for any human form submission.

**Confidence:** HIGH on shape and security posture. MEDIUM on whether v1.1 ships per-intent SPI plus a metadata-driven default extractor or just the SPI + reference impl.

---

### F-F — Tool-Layer Refinements

**Modified components, no new ones except SPI:**
- `tools/ToolResultPayloads.java` (new helper) — central place where `describe_entity` JSON shape is built. Currently inline in `ToolResultFormatter.describe`. Extracting lets v1.1 add fields (relationships with cardinality, enum values with locale-resolved labels, attribute-level read/write permission, validation constraints) without growing `ToolResultFormatter`.
- `spi/ToolFetchPlanCustomizer.java` (new SPI) — `interface ToolFetchPlanCustomizer { Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx); }`. Hosts implement to override `FetchPlan.BASE` defaults at `BuiltInDataTools` lines 137, 143, 187, 229. Default impl returns `Optional.empty()` (current behavior).
- `BaselineContextProvider` — adds `agent.entities` (compact list of `name|label`) and `agent.permissions` (compact map keyed by entity, values list `r/c/u/d` plus per-attribute modifiable). Sourced from `LlmExposurePolicy` (post-narrows). MEMORY `feedback_no_abbreviations` applies — keep keys spelled (`agent.permissions.read`, not `agent.perms`).

**Confidence:** HIGH. Local refinements, not architectural shifts.

---

### F-G — Prompt-Contract Hardening

**No new components.** Purely about what `BaselineContextProvider` injects, what `ToolResultFormatter` returns, and which strings get a `msg://` lookup before going to the LLM.

**Key rule.** Internal names hidden from **user-facing chat** (assistant prose), but still present in **tool inputs/outputs** (the LLM's tool-call JSON). Achieved by output-scanning the assistant message for entity-name leakage and a system-prompt directive ("when referring to entities to the user, use their human-readable label from `describe_entity`"). The output scanner advisor already exists (`OutputScannerAdvisor`, registered at order +400). v1.1 adds entity-name patterns to its config.

**`unknown_entity` retry contract.** Add to system prompt: "If a tool returns `unknown_entity`, you must call `list_entities` exactly once to get the authoritative list, then either retry with the corrected name or tell the user no matching entity exists. Do not guess a different entity." Lock with prompt-contract test.

**Confidence:** HIGH.

---

## 3. Cross-Feature Integration Matrix

| New surface / feature | Touches | Risk |
|---|---|---|
| `LlmExposurePolicy` | `BuiltInDataTools` (read), `BuiltInMutationTools` (write), `BaselineContextProvider`, `RetrievalFilterBuilder` (RAG filter must subtract denied source-entities) | If RAG filter not updated, denylist is leaky for KB documents. **Highest cross-feature risk.** |
| Mutation tools | `AgentToolCallbacks` composition; new audit `eventName`s; new locale keys; new `MutationGuard` SPI | Without exposure policy in place, no per-entity write denial below user permission level. |
| Configurable surfaces | `ChatPanelFragment` (minor `setCompactMode`?), MainView integration via add-on hook, `AiUiSettings` entity, `AiChatSessionState` `@VaadinSessionScope` bean | Multiple `ChatPanelFragment` instances must not double-stream or double-audit. `RunContext` ThreadLocal in `DefaultChatServiceImpl` is per-request — verify under `chatStreamingScheduler` thread hand-off. |
| STT | `ChatPanelFragment` `messageInputSlot`; new `TranscriptionService` (no advisor) | Lowest cross-feature coupling. Can ship independently. |
| Intent extraction → form prefill | New `AiExtractionDraft` entity; `prepare_form_draft` tool wired into `AgentToolCallbacks`; `ChatPanelFragment` response renderer for `open_form_with_draft` payload | Must NOT route through mutation tools — extraction is read-only from LLM POV (LLM produces draft; user submits). |
| Tool-layer refinements | `ToolResultFormatter`, `BaselineContextProvider`, `BuiltInDataTools.describeEntity` | Pure additive; touches every tool's JSON shape — keep additive and document for hosts that test against it. |
| Prompt-contract hardening | `BaselineContextProvider`, `OutputScannerAdvisor` config, system-prompt template | Output scanner now has more patterns — verify it does not flag legitimate replies. |

---

## 4. Recommended Build Order

The seed/scope statement lists ordering constraints: **exposure policy → mutation tools**; **ChatPanelFragment refactor → configurable surfaces**; **tool-layer foundations → mutation tools**.

**Phase 9 — Tool-layer foundations & prompt-contract hardening (F-F + F-G).** Pure additions; no new entity, no new SPI registration into `AgentToolCallbacks`. Lays groundwork: `ToolResultPayloads` helper, `ToolFetchPlanCustomizer` SPI, richer `describe_entity`, `agent.entities` / `agent.permissions` injection, `unknown_entity` retry contract, output-scanner pattern additions. Output: more honest baseline prompt and richer tool surface, no behavioral risk.

**Phase 10 — AI exposure policy (F-A).** New entity `AiExposureRule`, `LlmExposurePolicy`, admin views, full migration of `BuiltInDataTools` and `BaselineContextProvider` and `RetrievalFilterBuilder` to consult the policy. Mutation tools not yet shipped — policy ships with read+RAG narrowing only.

**Phase 11 — Mutation tools (F-B).** New `BuiltInMutationTools`, `MutationGuard` SPI, opt-in flag, audit `eventName`s, locale keys. Requires Phase 9 (richer permission inventory in baseline) and Phase 10 (admin must be able to deny mutation per entity below user permission level). Ships with `ai-agent.tools.mutation.enabled=false` default.

**Phase 12 — Configurable chat surfaces (F-C).** `AiUiSettings` entity, `SidebarChatComponent`, `FloatingChatLauncher`, `ChatSurfaceMounter`, `AiChatSessionState`. Independent of exposure policy and mutation tools. Could parallel Phases 10/11 if staffing allows; sequencing after avoids interleaving UI refactor with security-layer concerns.

**Phase 13 — Chat task input (F-D + task-scoped file).** Local to `ChatPanelFragment` and a new `TranscriptionService`. Independent of all other phases.

**Phase 14 — Intent-driven extraction → form prefill (F-E).** New `AiExtractionDraft`, `IntentExtractor` SPI, `ExtractionService`, `prepare_form_draft` tool, `ChatPanelFragment` response-renderer extension. Depends on Phase 9 (`askTyped` via richer prompts) and Phase 10 (exposure policy gates which entities the extractor can target). Cleanest ordering: last.

**Why parallelization is not advised.** Phases 9 → 10 → 11 form a hard chain. Phases 12, 13, 14 are independent of each other but all assume Phase 9's tool/baseline refinements landed. Linear order is safer for a single stream and produces shippable intermediate states.

---

## 5. Files: New vs Modified Summary

**New files (functional):**
- `entity/AiExposureRule.java`, `entity/AiUiSettings.java`, `entity/AiExtractionDraft.java`
- `metadata/LlmExposurePolicy.java`, `metadata/LlmExposureRuleRepository.java`
- `tools/BuiltInMutationTools.java`, `tools/ToolEntityResolver.java`, `tools/ToolResultPayloads.java`
- `extraction/IntentExtractor.java` (SPI), `extraction/ExtractionService.java`, `extraction/ExtractionToolBridge.java`, `extraction/DraftLoader.java`
- `transcription/TranscriptionService.java`, `transcription/SpringAiTranscriptionService.java`
- `spi/MutationGuard.java`, `spi/ToolFetchPlanCustomizer.java`, `spi/TranscriptionPostProcessor.java`
- `view/chat/SidebarChatComponent.java`, `view/chat/FloatingChatLauncher.java`, `view/chat/ChatSurfaceMounter.java`, `view/chat/fragment/AudioCaptureComponent.java`
- `view/exposure/AiExposureRuleListView.java` + `AiExposureRuleDetailView.java` (+ XML)
- `view/uisettings/AiUiSettingsView.java` (+ XML)
- `properties/AiAgentMutationProperties.java`, `properties/AiAgentTranscriptionProperties.java`
- `orchestration/AiChatSessionState.java` (`@VaadinSessionScope`)
- Liquibase changelogs (3 entities)
- Locale message bundles in all existing locales

**Modified files (existing v1.0):**
- `tools/BuiltInDataTools.java` — every `currentUserSchemaAccess.*` becomes `llmExposurePolicy.*`; `describe_entity` calls richer formatter; resolver helpers extracted to `ToolEntityResolver`.
- `tools/AgentToolCallbacks.java` — accept `Optional<BuiltInMutationTools>`; expose `prepare_form_draft` callback when extraction enabled.
- `orchestration/BaselineContextProvider.java` — add `agent.entities`, `agent.permissions` keys; source from `LlmExposurePolicy`.
- `view/chat/fragment/ChatPanelFragment.java` — wire microphone component into `messageInputSlot`; render structured `open_form_with_draft` button; possible `setCompactMode` for floating surface.
- `view/chat/ChatView.java` — minimal; possibly read `AiUiSettings` to guard whether the route is exposed.
- `audit/AuditWriter.java` — no signature change. Mutations and extraction reuse `writeToolCall` with new `eventName` strings.
- `orchestration/ChatClientFactory.java` — no change. Advisor chain stable.
- `DefaultChatServiceImpl.java` — no change for F-A/B/F/G. Possible minor change for F-D/E only.
- `rag/RetrievalFilterBuilder.java` — additive: combine current role-scoped filter with exposure-policy denied-source-entity filter.
- `metadata/CurrentUserSchemaAccess.java` — no change. `LlmExposurePolicy` wraps it.
- `security/AiAgentAdminRole.java` — extend with policies for `AiExposureRule`, `AiUiSettings`, `AiExtractionDraft`.
- `security/AiAgentUserRole.java` — extend with read on `AiExtractionDraft` (own rows only — row-level policy).
- `messages*.properties` (all locales) — many new keys.
- `menu.xml` — new menu entries for exposure rules, UI settings.
- Starter `AiAgentAutoConfiguration` — register conditional beans (mutation tools, transcription).

---

## 6. Quality-Gate Checklist

- ✓ Integration points named with file paths and line numbers.
- ✓ New vs modified explicit per feature; summarized in section 5.
- ✓ Build order considers dependencies (hard chain 9 → 10 → 11; soft 12/13/14).
- ✓ No parallel chat backends — F-C reuses `ChatPanelFragment` + `ChatService` + `AiConversation`; STT does not call the chat client; extraction goes through existing `askTyped`.
- ✓ Admin governance for mutation tools (Phase 10 exposure policy) lands before mutation tools (Phase 11). Mutation tools default off; even when on, exposure policy can deny per entity below user permissions.
