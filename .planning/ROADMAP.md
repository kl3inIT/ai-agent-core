# Roadmap — v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces

**Milestone:** v1.1.0
**Granularity:** coarse
**Total phases:** 6 (Phase 9 → Phase 14)
**Phase numbering:** continues from v1.0.0 (which ended at Phase 8 plus inserted 7.1, 7.2). v1.1 starts at Phase 9.
**Coverage:** 100% of v1.1 active REQ-IDs mapped (Future Requirements and Out of Scope intentionally NOT mapped).

**Previous milestone:** v1.0.0 MVP shipped 2026-04-26
**Archive:** [v1.0.0-ROADMAP.md](milestones/v1.0.0-ROADMAP.md)
**Phase history:** [v1.0.0-phases/](milestones/v1.0.0-phases/)

## Hard Build-Order Constraints

- Phase 9 → must precede everything (lays `agent.entities` / `agent.permissions` baseline + richer tool DTOs).
- Phase 10 → depends on Phase 9; must precede Phase 11 (admin must be able to deny mutation per entity below user permission level before opt-in).
- Phase 11 → hard-depends on Phase 9 + Phase 10.
- Phase 12 → independent of 10/11 but depends on Phase 9; sequenced after to avoid interleaving UI refactor with security work.
- Phase 13 → depends on Phase 9; independent of 10/11/12.
- Phase 14 → depends on Phase 9 and Phase 10; sequenced last (highest novelty).

## Phases

- [x] **Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening** — Richer `describe_entity`, fetch-plan SPI, baseline `agent.entities` + `agent.permissions`, `unknown_entity` retry contract, output-scanner pattern additions.
- [x] **Phase 10: AI-Specific LLM Exposure Policy** — `AiExposureRule` (`EXCLUDE`-only) + `LlmExposurePolicy` boundary; admin Flow UI; RAG cross-cut. (completed 2026-04-28)
- [ ] **Phase 11: Mutation-Capable Built-In Tools** — `BuiltInMutationTools` (default OFF), `MutationGuard` SPI, `AiMutationIntent` idempotency, layered fail-closed gating, audit reuse via `writeToolCall`.
- [ ] **Phase 12: Configurable Chat Surfaces** — Full / sidebar / floating surfaces over one `ChatPanelFragment`; `AiUiSettings` admin toggle; `AiChatSessionState` continuity.
- [ ] **Phase 13: Chat Task Input — STT + Task-Scoped File** — Browser-recorded STT via Spring AI `OpenAiAudioTranscriptionModel`; transient `AiTaskFile` separate from KB ingestion.
- [ ] **Phase 14: Intent-Driven Extraction → Form Prefill** — Persisted `AiExtractionDraft`; `IntentExtractor<T>` SPI; `prepare_form_draft` tool returning structured payload; controller-side navigation only.

## Phase Details

### Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening
**Goal**: The LLM sees a richer, deterministic, leakage-resistant view of the host schema and tool surface — without any new entity, SPI registration into mutation chain, or behavioral change to data-access policy.
**Depends on**: Nothing (first phase of v1.1; builds purely additively on shipped v1.0)
**Requirements**: PROMPT-01, PROMPT-02, PROMPT-03, PROMPT-04, PROMPT-05, PROMPT-06, TOOL-09, TOOL-10, TOOL-11, TOOL-12, SPI-09, TEST-08, AUD-07 (partial — hash-sensitive-fields plumbing prepared; mutation-specific use lands in Phase 11)
**Success Criteria** (what must be TRUE):
  1. The system prompt that ships to the LLM contains an alphabetically sorted `agent.entities` block (`name (label)` lines, sourced from the post-narrowed schema) and a compact `agent.permissions` map keyed by entity with CRUD bits and per-attribute `modifiable` set; locale-sensitive labels are not part of the cache key.
  2. `describe_entity` returns Jmix-metadata-derived attribute fields (comment, attributeType, cardinality, mandatory, readOnly, persistent, transient, isPrimaryKey, enumValues, relationshipTarget, maxLength) and an entity-level comment, all via `MetadataTools` (no raw reflection); excluded fields are documented inline.
  3. A user-facing chat reply to a question about host data does not contain internal entity-name prefixes (e.g. `<host_prefix>_<name>`) or raw built-in tool names; `OutputScannerAdvisor` flags such leaks (audit, no hard block) and the prompt-contract test (TEST-08) asserts the absence in Vietnamese and English locales.
  4. When the LLM calls a tool with an unknown entity name, the tool returns a structured `unknown_entity` error carrying an `expected` action hint; the LLM is instructed (and observably) calls `list_entities` exactly once and either retries with the corrected name or tells the user no such entity exists.
  5. Hosts can register a `ToolFetchPlanCustomizer` SPI bean that overrides `_base` / `_instance_name` per `(toolName, MetaClass)` at runtime; the resolved plan is intersected with `AccessManager`-allowed attributes (host plan cannot widen the projection beyond user attribute permissions); the comment in code states "fetch plan is projection, not security."
**Plans:** 6 plans
- [x] 09-01-PLAN.md — AUD-07 plumbing: AuditFieldHasher static utility + AiAgentAuditProperties (no callers wired in Phase 9)
- [x] 09-02-PLAN.md — ToolFetchPlanCustomizer SPI + FetchPlanContext record + no-op default in SpiDefaultsAutoConfiguration
- [x] 09-03-PLAN.md — BaselineContextProvider extension: agent.entities + agent.permissions (locale-free cache key) + AiAgentPromptProperties
- [x] 09-04-PLAN.md — Tool-layer changes: FetchPlanIntersector + FetchPlanResolver wiring, describe_entity TOOL-09 widening, PROMPT-04 records wrapper, unknown_entity D-14 hints
- [x] 09-05-PLAN.md — Output scanner pattern packs (HostPrefixPatternProvider + ToolNamePatternProvider) + AgentSystemPromptRules + DefaultChatServiceImpl rule wiring
- [x] 09-06-PLAN.md — TEST-08 prompt-contract regression: PromptContractMockTest (EN+VI parameterized) + PromptContractLiveTest (@Tag("live"))
- [x] 09-07-PLAN.md — Gap closure: exact entityName tool-call guidance from agent.entities/list_entities

### Phase 10: AI-Specific LLM Exposure Policy
**Goal**: Admin can narrow the LLM-visible surface (entities and attributes) below the user's Jmix permissions through a single denylist-only governance layer; the policy is uniformly enforced across schema discovery, tool calls, baseline prompt, and RAG.
**Depends on**: Phase 9 (`agent.entities` / `agent.permissions` baseline keys must already source through a single boundary so the new policy can substitute the source without churning call sites)
**Requirements**: EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06, EXP-07, EXP-08, EXP-09, EXP-10, ENT-05, SEC-05 (extension covering AiExposureRule; AiUiSettings admin policies finish in Phase 12), TEST-09
**Success Criteria** (what must be TRUE):
  1. Admin creates an `AiExposureRule` (entity-level, `mode=EXCLUDE` only — there is no `ALLOW` enum value) via `AiExposureRuleListView` / `AiExposureRuleDetailView` (gated to `AiAgentAdminRole`, with `genericFilter` + `propertyFilter`, action-column for enable/disable, and labels reading "Hide from AI" / "Visible to AI" — never "Allow"); the rule takes effect on the next chat turn. Note: `attributePath` field omitted per user decision 2026-04-27 (entity-level denylist only in v1.1).
  2. An entity that the current user can read in Jmix but is denylisted for the LLM does not appear in `list_entities`, `agent.entities`, RAG hits, or `find_records` responses; attempting to address it surfaces uniformly as `unknown_entity` (never `access_denied`), preserving denylist opacity.
  3. `BuiltInDataTools`, `BaselineContextProvider`, and `RetrievalFilterBuilder` all consult `LlmExposurePolicy` (composed as `userVisible AND NOT excluded`); `LlmExposureRuleRepository` uses `UnconstrainedDataManager` so user-role tweaks cannot bypass admin governance.
  4. Rule create/update/delete publishes `LlmExposureChangedEvent`; `AiAgentAdminRole` carries CRUD + view + menu policies for `AiExposureRule`.
**Plans:** 10/10 plans complete
- [x] 10-01-PLAN.md — Entity foundations: AiExposureRuleMode enum + AiExposureRule entity + ChunkMetadata.SOURCE_ENTITY + AiKnowledgeDocument.sourceEntityName + Liquibase changelogs 060/061
- [x] 10-02-PLAN.md — Service layer: LlmExposureChangedEvent + LlmExposureRuleRepository + AiExposureRuleEntityListener + LlmExposurePolicy
- [x] 10-03-PLAN.md — Security role extension: AiAgentAdminRole CRUD + view + menu policies for AiExposureRule + VectorStoreDebugView
- [x] 10-04-PLAN.md — Call-site swap: CurrentUserSchemaAccess → LlmExposurePolicy in BaselineContextProvider + FetchPlanIntersector + BuiltInDataTools
- [x] 10-05-PLAN.md — RAG integration: RetrievalFilterBuilder nin clause + AsyncIngestionWorker SOURCE_ENTITY metadata enrichment
- [x] 10-06-PLAN.md — AiExposureRuleListView: MetaclassComboBoxHelper + list view XML + Java with @Supply toggle renderer
- [x] 10-07-PLAN.md — AiExposureRuleDetailView: comboBox + checkbox + Java wiring + all ~53 message keys in both locales + menu.xml entries
- [x] 10-08-PLAN.md — KnowledgeBaseView extensions: sourceEntityName comboBox in upload form + editPermissions action + reingest action + sourceEntity column renderer
- [x] 10-09-PLAN.md — VectorStoreDebugView: filter input row + VectorStore.similaritySearch + FilterExpressionTextParser + expand dialog
- [x] 10-10-PLAN.md — Tests: RetrievalFilterBuilderDenylistTest (unit) + LlmExposurePolicyIntegrationTest (@SpringBootTest)
**UI hint**: yes

### Phase 11: Mutation-Capable Built-In Tools
**Goal**: Hosts can opt in to LLM-driven create / update / related-write operations that go through Jmix `DataManager`, are gated fail-closed by exposure policy + `AccessManager` (entity + per-attribute) + optional `MutationGuard` SPI, are idempotent, are audited end-to-end (including rollback), and never leak user-supplied PII through error strings.
**Depends on**: Phase 9 (richer permission inventory in baseline so the LLM knows what it can write); Phase 10 (admin must be able to deny mutation per entity below user permission level — opt-in is otherwise binary host-wide)
**Requirements**: MUT-01, MUT-02, MUT-03, MUT-04, MUT-05, MUT-06, MUT-07, MUT-08, MUT-09, MUT-10, MUT-11, MUT-12, ENT-09, AUD-06, AUD-07 (mutation-specific application: pre/post-image diff with optional PII hashing), SEC-07, SPI-10, TEST-10, TEST-11, TEST-12, TEST-13
**Success Criteria** (what must be TRUE):
  1. With default configuration, the boot test (TEST-13) asserts zero mutation tool callbacks present in `AgentToolCallbacks.forCurrentUser`; setting `ai-agent.tools.mutation.enabled=true` (a `@ConditionalOnProperty` flag) makes `create_record`, `update_record`, `add_related_record`, `remove_related_record` available — `delete_record` is reserved for v1.2 and remains absent even when mutations are enabled.
  2. A user with READ but not MODIFY on attribute `X` triggering `update_record(attribute=X)` is blocked at the per-attribute `EntityAttributeContext.canModify` step (TEST-10); the tool returns a stable structured error code (`access_denied`); `DataManager.save` is never called; the LLM never sees raw constraint or JPA exception text or user-supplied PII.
  3. Calling a mutation tool twice with the same `idempotencyKey` (mandatory `@ToolParam`) returns the original result with `outcome=IDEMPOTENT_REPLAY` (TEST-11); only one row is created/updated in the database; both calls are audited via `AuditWriter.writeToolCall` (no new `AuditKind`); known `DataManager.save` rollback failures still write a durable `ERROR` audit row, while post-host-save idempotency finalization failures write `outcome=COMMIT_FAILED` and leave the intent non-reclaimable (TEST-12).
  4. The layered fail-closed gating chain runs in order on every mutation call: `AiAgentMutationRole` marker → `LlmExposurePolicy.canModify` → `AccessManager` `CrudEntityContext` + per-attribute `EntityAttributeContext.canModify` → pre-host-save idempotency reservation → type coercion + writable-property validation → optional `MutationGuard` SPI → `@Transactional` `DataManager.save` (regular `DataManager`, never `UnconstrainedDataManager`); a host `MutationGuard` veto raises `ToolVetoedException` and aborts before save.
  5. Locale message keys for every denial / success / idempotency / error path are present in all locale bundles; new audit `eventName` strings (`create_record`, `update_record`, `add_related_record`, `remove_related_record`) and new `outcome` values (`IDEMPOTENT_REPLAY`, `COMMIT_FAILED`) are observable on `AiAuditEvent` rows; `AiMutationIntent` dedup table honors a 24h TTL by default.
**Plans:** 14 plan artifacts (11 executable waves plus 11-07 reference split into 11-07A/B/C)

**Wave 1**
- [ ] 11-01-PLAN.md — Foundation: AiMutationIntent entity/status + Liquibase 070 + AiInternalEntityNames + AiAgentAdminRole + AiAgentMutationRole + locale captions
- [ ] 11-02-PLAN.md — AiAgentMutationProperties @ConfigurationProperties + AiToolCallOutcome enum extension + @EnableScheduling
- [ ] 11-03-PLAN.md — MutationGuard SPI + MutationIntent record + default no-op bean

**Wave 2 (blocked on Wave 1 completion)**
- [ ] 11-04-PLAN.md — ToolEntityResolver shared @Component + operation-specific LlmExposurePolicy canCreate/canUpdate gates + BuiltInDataTools delegation
- [ ] 11-05-PLAN.md — MutationIntentRepository reservation/replay with requestHash/status + MutationIntentCleanupJob @Scheduled hourly
- [ ] 11-06-PLAN.md — MutationErrorTranslator (6 stable error codes, converter-code remapping) + locale captions

**Wave 3 (blocked on Wave 2 completion)**
- [ ] 11-07-PLAN.md — Reference contract for split BuiltInMutationTools implementation (do not execute as a monolith)
- [ ] 11-07A-PLAN.md — BuiltInMutationTools create/update core + DiffSerializer + MutationRequestHasher + MutationSaveExecutor
- [ ] 11-07B-PLAN.md — Related-write metadata helpers + add_related_record/remove_related_record
- [ ] 11-07C-PLAN.md — Commit-state, replay, non-throwing audit, and locale hardening
- [ ] 11-08-PLAN.md — BuiltInLinkTools always-on: 2 @Tool methods over ViewRegistry + ServerProperties

**Wave 4 (blocked on Wave 3 completion)**
- [ ] 11-09-PLAN.md — AgentToolCallbacks wiring without duplicate mutation audit + conditional AgentSystemPromptRulesComposer + ToolNamePatternProvider built-in scanner coverage

**Wave 5 (blocked on Wave 4 completion)**
- [ ] 11-10-PLAN.md — Core tests: fixture Liquibase, TEST-10 access gating, TEST-11 idempotency replay/violation/reservation, TEST-13 callback shape, mutation audit ownership

**Wave 6 (blocked on Wave 5 completion)**
- [ ] 11-11-PLAN.md — Supporting tests: TEST-12 commit-failed audit, related-write security, link opacity, translator coverage, prompt rules, tool-name scanner coverage

**Cross-cutting constraints:**
- Mutation tools remain default-off and `delete_record` remains absent under every property combination.
- Host mutations use regular `DataManager` through `MutationSaveExecutor`; system-internal idempotency rows use `UnconstrainedDataManager`.
- Idempotency uses pre-save reservation with `REQUEST_HASH`/`STATUS_`; `AiMutationIntent` does not store full result JSON.
- Mutation callbacks are self-audited exactly once and are not wrapped by `ToolCallbackAuditDecorator`.

### Phase 12: Configurable Chat Surfaces
**Goal**: One `ChatPanelFragment`, one `ChatService`, one `AiConversation` per user-session, surfaced through three admin-toggleable presentations (full route, right-sidebar, floating launcher) with continuous conversation state across surface switches.
**Depends on**: Phase 9 (baseline + tool changes already shipped so UI work does not interleave with prompt-contract churn)
**Requirements**: SURF-01, SURF-02, SURF-03, SURF-04, SURF-05, SURF-06, SURF-07, SURF-08, SURF-09, SURF-10, ENT-06, TEST-14
**Success Criteria** (what must be TRUE):
  1. An admin opens `AiUiSettingsView` and toggles which surfaces are enabled (`FULL_ROUTE`, `SIDEBAR`, `FLOATING`) plus the default surface; on next UI init the host shell renders only the enabled surfaces with no host-side code edits beyond the starter dependency.
  2. A user starts a conversation in the floating launcher, switches mid-session to the sidebar, then to the full `ChatView`; the same `conversationId` follows them, the same JDBC memory rows back each turn, and the cross-surface continuity test (TEST-14) passes.
  3. The floating launcher uses the Vaadin `Dialog.setModality(MODELESS).setDraggable(true)` overlay primitive, anchored bottom-right; it auto-hides while a Jmix admin dialog is open and reappears on close (z-index conflict mitigation).
  4. There is exactly one `AiConversation` row per active user-session conversation regardless of surface — `AiChatSessionState` (`@VaadinSessionScope`) carries the active id and reattaches to whichever fragment is mounted.
**Plans**: TBD
**UI hint**: yes

### Phase 13: Chat Task Input — STT + Task-Scoped File
**Goal**: Users can dictate chat input via browser-recorded audio transcribed server-side, and attach task-scoped files to a turn — both pathways disjoint from the chat client and from KB ingestion, audit-privacy-safe by default.
**Depends on**: Phase 9 (no exposure-policy or mutation surface dependency; `ChatPanelFragment`'s `messageInputSlot` is the integration point and stable from v1.0)
**Requirements**: STT-01, STT-02, STT-03, STT-04, STT-05, STT-06, TASK-01, TASK-02, TASK-03, TASK-04, TASK-05, ENT-07, SPI-11, TEST-16, TEST-17, SEC-06 (partial — read+create on own `AiTaskFile` rows; row-level draft policy completes in Phase 14)
**Success Criteria** (what must be TRUE):
  1. With `ai-agent.stt.enabled=true` and an OpenAI key (operator docs note OpenRouter does not proxy `/audio/transcriptions`), the user clicks the mic button, records up to 60s via browser `MediaRecorder` (no transcoding), and the transcribed text appears in the `MessageInput` for review/edit before send — `TranscriptionService` does not call `ChatService.ask` directly.
  2. A user attaches a task-scoped file to a turn via a UI affordance distinct from `MessageInput` and from `KnowledgeBaseView`; an `AiTaskFile` row is persisted to `agentstore` with conversation id, filename, content type, size, storage ref, TTL (default 1h); the file is stored via Jmix `FileStorage` (default `local`) and `IngesterManager` is never invoked (TEST-16 asserts unchanged `VectorStore` count after attach).
  3. By default the `STT_TRANSCRIPTION` audit row records duration, language, model, outcome, and SHA-256 transcript hash (NOT raw text); flipping `ai-agent.stt.audit.storeTranscript=true` switches it (TEST-17 covers both modes).
  4. STT failures (e.g. provider 4xx, recording too long) surface a non-blocking error message + retry button in the input area; the chat flow itself remains usable; an optional `TranscriptionPostProcessor` SPI bean rewrites transcripts (PII redaction / vocabulary normalization) before they reach the input field.
**Plans**: TBD
**UI hint**: yes

### Phase 14: Intent-Driven Extraction → Form Prefill
**Goal**: The LLM produces a structured draft for a host entity (e.g. PDF → customer-draft), the user confirms via a chat-rendered button, and a Jmix detail view opens prefilled — all without giving the LLM any UI-mutation primitive and with `AccessManager` validating the eventual save exactly as for any human form submission.
**Depends on**: Phase 9 (richer baseline / structured tool outputs); Phase 10 (exposure policy gates which entities the extractor may target)
**Requirements**: EXTRACT-01, EXTRACT-02, EXTRACT-03, EXTRACT-04, EXTRACT-05, EXTRACT-06, EXTRACT-07, EXTRACT-08, EXTRACT-09, EXTRACT-10, ENT-08, SPI-12, TEST-15, SEC-06 (row-level read on own `AiExtractionDraft` rows completes here)
**Success Criteria** (what must be TRUE):
  1. With at least one named intent registered (the add-on ships one reference end-to-end intent plus the SPI for hosts), the user picks an intent, optionally attaches an `AiTaskFile`, and the LLM calls `prepare_form_draft(intentId, contextRefs)` exactly once — the tool returns a structured payload `{ "action": "open_form_with_draft", "draftId": "...", "entityName": "...", "instanceName": "..." }`; the LLM never receives a `ViewNavigators` or any UI-mutation primitive (TEST-15 grep / source-scanner gate).
  2. `ChatPanelFragment` recognizes the `open_form_with_draft` shape and renders an "Open form to confirm" button; clicking it (controller side) invokes `ViewNavigators.detailView(...).newEntity().withInitializer(...)` after `AccessManager.isPermitted(ViewContext)` passes; the Jmix detail view opens prefilled from `AiExtractionDraft.payloadJson`.
  3. The prefill applies via `DataContext.create(...)` and per-attribute `EntityAttributeContext.canModify`-gated `setValueIfPermitted` (never raw `setValue`); `dataContext.validate()` runs before Save; on Save the draft is deleted and a normal Jmix-secured save executes.
  4. `AiExtractionDraft` rows expire after TTL (default 1h, hourly cleanup job); each row is row-level-scoped to its owner `userUsername` (`AiAgentUserRole` row policy), persisted (not `VaadinSession`-cached), and survives navigation; `prepare_form_draft` invocations are audited via `AuditWriter.writeToolCall` with `eventName=prepare_form_draft`.
**Plans**: TBD
**UI hint**: yes

## Phase Dependency Graph

```
Phase 9 (foundations)
  ├── Phase 10 (exposure policy)
  │     ├── Phase 11 (mutation tools)
  │     └── Phase 14 (intent extraction)
  ├── Phase 12 (chat surfaces)
  ├── Phase 13 (chat task input)
  └── (Phase 14 also uses Phase 9)
```

Hard chain: 9 → 10 → 11. Soft sequence: 12 → 13 → 14 (each independent of each other, all assume Phase 9; 14 also assumes 10).

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | 7/7 | Complete | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | 10/10 | Complete   | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | 0/10 | Not started | - |
| 12. Configurable Chat Surfaces | 0/0 | Not started | - |
| 13. Chat Task Input — STT + Task-Scoped File | 0/0 | Not started | - |
| 14. Intent-Driven Extraction → Form Prefill | 0/0 | Not started | - |

## Coverage Validation

All v1.1 active REQ-IDs in REQUIREMENTS.md are mapped to exactly one phase. Future Requirements (MUT-13, SURF-11, EXTRACT-11, EXP-11, dormant SEEDs) and Out of Scope items (PKG-05/TEST-07 carryover, collapsible tool-detail, autonomous agents, DELETE tool, etc.) are intentionally NOT mapped.

| Category | Count | Phase(s) |
|----------|-------|----------|
| PROMPT-01..06 | 6 | Phase 9 |
| TOOL-09..12 | 4 | Phase 9 |
| EXP-01..10 | 10 | Phase 10 |
| MUT-01..12 | 12 | Phase 11 |
| SURF-01..10 | 10 | Phase 12 |
| STT-01..06 | 6 | Phase 13 |
| TASK-01..05 | 5 | Phase 13 |
| EXTRACT-01..10 | 10 | Phase 14 |
| ENT-05 | 1 | Phase 10 |
| ENT-06 | 1 | Phase 12 |
| ENT-07 | 1 | Phase 13 |
| ENT-08 | 1 | Phase 14 |
| ENT-09 | 1 | Phase 11 |
| SPI-09 | 1 | Phase 9 |
| SPI-10 | 1 | Phase 11 |
| SPI-11 | 1 | Phase 13 |
| SPI-12 | 1 | Phase 14 |
| AUD-06 | 1 | Phase 11 |
| AUD-07 | 1 | Phase 11 (plumbing prepared in Phase 9) |
| SEC-05 | 1 | Phase 10 (extends in Phase 12 for AiUiSettings) |
| SEC-06 | 1 | Phase 13 + Phase 14 (split: AiTaskFile in 13, AiExtractionDraft in 14) |
| SEC-07 | 1 | Phase 11 |
| TEST-08 | 1 | Phase 9 |
| TEST-09 | 1 | Phase 10 |
| TEST-10..13 | 4 | Phase 11 |
| TEST-14 | 1 | Phase 12 |
| TEST-15 | 1 | Phase 14 |
| TEST-16, TEST-17 | 2 | Phase 13 |

**No orphans. No duplicates.**

## Notes

- Phase numbering continues from v1.0.0 (which ended at Phase 8 plus inserted 7.1, 7.2). v1.1 starts at Phase 9 — no renumbering.
- Every phase that introduces a new entity (Phase 10: `AiExposureRule`; Phase 11: `AiMutationIntent`; Phase 12: `AiUiSettings`; Phase 13: `AiTaskFile`; Phase 14: `AiExtractionDraft`) bundles its Liquibase changelog (included in root `changelog.xml`), all-locale message bundle entries, and role-policy updates as part of the same phase, per CLAUDE.md.
- Mutation tools (Phase 11) ship default OFF via `@ConditionalOnProperty`; the boot test asserts zero mutation callbacks under default config.
- Exposure policy (Phase 10) is `EXCLUDE`-only at the rule-shape level; UI labels read "Hide from AI" / "Visible to AI"; composition is `userVisible AND NOT excluded`. `attributePath` field omitted in v1.1 per user decision 2026-04-27 (entity-level denylist only).
- LLM never receives `ViewNavigators` or any UI-mutation primitive (Phase 14): controller renders the confirm card; controller navigates after `AccessManager.isPermitted(ViewContext)`.
- Audit reuses `AuditWriter.writeToolCall` end-to-end; no new `AuditKind`. New `eventName` strings and two new `outcome` values are the only audit surface changes.
