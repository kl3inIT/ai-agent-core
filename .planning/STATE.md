---
gsd_state_version: 1.0
milestone: v1.1.0
milestone_name: milestone
status: Ready to execute
stopped_at: Phase 14 planned
last_updated: "2026-05-07T16:26:40.928Z"
progress:
  total_phases: 9
  completed_phases: 6
  total_plans: 60
  completed_plans: 53
  percent: 88
---

# Project State

**Last updated:** 2026-05-07

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-26 — v1.1.0 milestone started)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 14 — Intent-Driven Extraction → Form Prefill

## Current Position

Phase: 14 (Intent-Driven Extraction → Form Prefill) — PLANNED
Plan: 0 of 8 complete (ready to execute)
| Field | Value |
|-------|-------|
| Phase | Phase 14 |
| Plan | 0 of 8 |
| Status | Phase 14 planned; research plus 8 executable plans replanned across 6 waves with cross-AI review feedback incorporated |
| Last activity | 2026-05-07 — Replanned Phase 14 with 14-REVIEWS feedback; structured ToolResult payload, Jmix detail-navigation lifecycle, and scanner hardening incorporated |

## Phase Status

| Phase | Status | Plans Complete | Started | Completed |
|-------|--------|----------------|---------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | Complete | 7/7 | 2026-04-27 | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | Shipped | 10/10 | 2026-04-27 | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | Shipped | 16/16 | 2026-04-28 | 2026-04-29 |
| 12. Configurable Chat Surfaces | Shipped | 6/6 | 2026-05-02 | 2026-05-05 |
| 13. Chat Task File — Attach + LLM Read + Bulk Save | Complete | 5/5 | 2026-05-05 | 2026-05-06 |
| 13.1. Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context | Shipped | 7/7 | 2026-05-07 | 2026-05-07 |
| 14. Intent-Driven Extraction → Form Prefill | Ready to execute | 0/8 | 2026-05-07 | - |
| 15. Chat Voice Input — Soniox STT | Not started | 0/0 | - | - |

## Hard Build-Order

- Hard chain: Phase 9 → Phase 10 → Phase 11.
- Soft chain: Phase 12 → Phase 13 → Phase 14 (each independent of each other; 12/13 require Phase 9; 14 requires Phase 9 AND Phase 10).
- Mutation tools default OFF on ship (Phase 11 `@ConditionalOnProperty`).
- Exposure policy is `EXCLUDE`-only at the rule shape (Phase 10).
- LLM never receives `ViewNavigators` (Phase 14).
- Audit reuses `AuditWriter.writeToolCall`; no new `AuditKind`.

## Archived Milestone

**MVP v1.0.0** — shipped 2026-04-26. Archive: `.planning/milestones/v1.0.0-ROADMAP.md`; requirements archive: `.planning/milestones/v1.0.0-REQUIREMENTS.md`; phase artifacts: `.planning/milestones/v1.0.0-phases/`.

See `.planning/MILESTONES.md` for the v1.0.0 close summary.

## Milestone v1.1.0 Scope

Detailed REQ-IDs in `.planning/REQUIREMENTS.md`. Roadmap in `.planning/ROADMAP.md`.

- Prompt-contract hardening — Phase 9.
- Tool-layer refinements — Phase 9.
- AI-specific LLM exposure policy (SEED-007 activated) — Phase 10.
- Mutation-capable built-in tools — Phase 11.
- Configurable chat surfaces (SEED-005 activated, refined) — Phase 12.
- Chat task input (STT + task-scoped file) — Phase 13.
- Intent-driven extraction → prefilled Jmix forms — Phase 14.

**Out of scope for v1.1:** collapsible tool-detail panel + ephemeral streaming-status indicator (deferred); clean-consumer smoke / PKG-05 / TEST-07 (Plan 08-05 carryover, deferred).

## Accumulated Context

### Pending Todos (9 — disposition)

| Todo | Disposition in v1.1 |
|------|---------------------|
| `2026-04-26-inject-readable-entity-inventory-into-baseline-context.md` | Phase 9 (PROMPT-01) |
| `2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md` | Phase 9 (PROMPT-03, PROMPT-04, PROMPT-06) |
| `2026-04-24-enforce-unknown-entity-retry-contract.md` | Phase 9 (PROMPT-05) |
| `2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md` | Phase 9 (TOOL-09) |
| `2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md` | Phase 9 (TOOL-10, TOOL-11) |
| `2026-04-24-add-llm-permission-inventory.md` | Phase 9 (TOOL-12, PROMPT-02) |
| `2026-04-24-add-dedicated-chat-speech-and-file-task-input.md` | Phase 13 |
| `2026-04-24-add-intent-driven-extraction-to-prefilled-jmix-forms.md` | Phase 14 |
| `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` | DEFERRED (out of v1.1 scope) |

### Seeds Reviewed at v1.1 Start

| Seed | Disposition |
|------|-------------|
| SEED-001 — Reviewed learning loop | Dormant — no production-incident trigger yet |
| SEED-002 — Pre-deploy answer-quality regression gate | Dormant — defer until prompt rules from v1.1 produce signal |
| SEED-003 — OutputScanner SPI | Dormant — config-driven scanner sufficient |
| SEED-004 — Replay/diff runner | Dormant — pairs with SEED-002 |
| SEED-005 — Configurable chat surfaces | **ACTIVATED** — Phase 12 |
| SEED-006 — Strict file-backed knowledge path | Dormant — no retrieval-drift trigger |
| SEED-007 — AI-specific LLM exposure policy | **ACTIVATED** — Phase 10 (paired with Phase 11 mutation tools) |

### Roadmap Evolution Notes (carried)

- Phase 7.2 was inserted after Phase 7.1 in v1.0.0: Redesign audit schema as tree-lite (PARENT_ID).
- 2026-04-26 (v1.1 scope decision): prompt-contract hardening bundle promoted into v1.1 first phase. Activates SEED-005 (refined to three configurable chat surfaces) and SEED-007 (AI exposure policy). Adds new mutation-tools scope on top of pending todos.
- 2026-04-26 (v1.1 roadmap): six phases (9-14) defined; numbering continues from v1.0.0 close (Phase 8 + 7.1 + 7.2). All active REQ-IDs mapped; no orphans.

### Decisions

- 2026-04-27 (Plan 09-01): AUD-07 plumbing (`AuditFieldHasher` + `AiAgentAuditProperties`) shipped with intentional zero callers per CONTEXT D-18. Phase 11 `MutationErrorTranslator` is the planned consumer. SHA-256 over UTF-8 byte encoding (locale-independent), lowercase 64-char hex via `java.util.HexFormat`. No SPI extraction — deferred until a host requests non-SHA-256 hashing.
- 2026-04-27 (Plan 09-01): Spring config defaults landed in `module.properties`, NOT in `default-params.yaml` (which is strict `AiParameters` seed YAML). Planner-review carve-out honored.
- [Phase ?]: Plan 09-02: Locked the Phase-9 SPI contract surface — ToolFetchPlanCustomizer (D-09 signature) + FetchPlanContext concrete request snapshot + SpiDefaultsAutoConfiguration no-op default. FetchPlanContext does NOT carry RunContext (per D-10 review correction: RunContext is final + private constructor + static accessors). Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the SPI seam; Plan 09-04 will repeat the phrase at the FetchPlanIntersector consumer seam.
- [Phase ?]: 2026-04-27 (Plan 09-03): BaselineContextProvider emits agent.entities + agent.permissions per chat turn from CurrentUserSchemaAccess + AccessManager + MessageTools. agent.permissions is locale-invariant by construction (P-8): TreeMap entity-keys + LinkedHashMap r,u,c,d,modifiable order + TreeSet attribute iteration; only agent.entities carries locale-resolved labels (parenthesized suffix). Same sorted/capped entity list drives both blocks. Phase 10 LlmExposurePolicy substitution is a single-line swap of the getReadableSchema() call site.
- [Phase ?]: 2026-04-27 (Plan 09-04): FetchPlanResolver + FetchPlanIntersector landed in com.vn.agent.tools.fetchplan; verbatim TOOL-11 phrase exposed as public constant FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT and referenced from class Javadoc via {@value}. PLAN_NARROWED: greppable audit-prefix with AiToolCallOutcome.FLAGGED — no new outcome enum value. describe_entity widened via MetadataTools (no raw reflection); ToolResultFormatter.records emits literal PROMPT-04 envelope <data entity><label></data> with label first via MessageTools.getEntityCaption. UNKNOWN_ENTITY_HINTS verbatim D-14 with em dash preserved on hint #3. Phase 3 D-08 access_denied opacity preserved (Phase 10 will unify, not Phase 9).
- [Phase ?]: 2026-04-27 (Plan 09-05): Output-scanner Phase 9 pattern packs (HOST_PREFIX_LEAK / TOOL_NAME_LEAK) shipped as @Component providers with startup snapshot at ApplicationReadyEvent + lazy-fallback build in asPattern() (eager-singleton ordering safety). OutputScannerAdvisor widened to implement CallAdvisor + StreamAdvisor; streaming uses ChatClientMessageAggregator. Pattern.quote per token (T-09-22 ReDoS). Default-on toggles in module.properties.
- [Phase ?]: 2026-04-27 (Plan 09-05): AgentSystemPromptRules.PROMPT_RULES carries verbatim PROMPT-03 vocabulary rules + D-15 retry contract whose three hint substrings match BuiltInDataTools.UNKNOWN_ENTITY_HINTS BYTE-FOR-BYTE (em dash U+2014 preserved). Lowercase 'if' bullets sacrificed sentence-case to keep the cross-assertion green for TEST-08 in Plan 09-06. Constant lives in com.vn.agent.guard alongside OutputScannerAdvisor (both leak-prevention). DefaultChatServiceImpl wires PROMPT_RULES at BOTH composition sites (blocking ask + streaming stream) so rules apply on every turn regardless of transport mode and even when profile prompt is blank. Hardcoded English (no i18n) per RESEARCH Pitfall 7 — model-directed instructions, not user-facing UI.
- [Phase ?]: Plan 09-06 (TEST-08): cross-locale prompt-contract regression suite landed; Phase 9 feature-complete
- [Phase ?]: Plan 10-01: AiExposureRule (entity-level only, no attributePath) + AiExposureRuleMode (EXCLUDE only) in com.vn.agent.exposure. Liquibase 060+061 auto-loaded. ChunkMetadata.SOURCE_ENTITY=source_entity constant for EXP-05 NOT IN denylist (Plan 10-05 consumer).
- [Phase ?]: Plan 10-02: dataManager.load(EntityClass).query() auto-resolves store from @Store annotation; .store() chain method only applies to raw-JPQL loadValue paths
- [Phase ?]: Plan 10-02: LlmExposurePolicy.canModify ships unused in Phase 10; Phase 11 mutation gating wires it before DataManager.save
- [Phase 10]: Plan 10-03: AiAgentAdminRole extended with @EntityPolicy AiExposureRule + menu/view IDs for AiExposureRule list/detail and VectorStoreDebug; pure additive (zero existing policies removed); SEC-05 partially complete (AiUiSettings policies will fully close in Phase 12)
- [Phase ?]: Plan 10-04: Mechanical call-site swap complete — BaselineContextProvider, BuiltInDataTools, FetchPlanIntersector inject LlmExposurePolicy instead of CurrentUserSchemaAccess. Fix R4 unification: ALL canReadEntity()==false branches in BuiltInDataTools throw unknown_entity (not access_denied) — full opacity per EXP-09 + Phase 3 D-08. Fix R5: FetchPlanIntersector routes both canReadAttribute AND canReadEntity through the policy. UNKNOWN_ENTITY_HINTS byte-for-byte preserved (em dash U+2014). ToolQueryCountBaselineTest recalibrated for D-14 no-cache: list_entities/describe_entity ceiling raised from 0 to 5 SELECTs to absorb the per-call agentstore policy lookup.
- [Phase ?]: Plan 10-05: RetrievalFilterBuilder applies defensive (source_entity IS NULL) OR (NOT IN <denied>) for non-empty denylist (Fix R6); AsyncIngestionWorker.enrich mirrors sourceEntityName to ChunkMetadata.SOURCE_ENTITY when non-null. Legacy chunks unaffected until reingested (D-06).
- [Phase ?]: Plan 10-06: Toggle save uses UnconstrainedDataManager and Fix R2 enforced — view does NOT inject ApplicationEventPublisher; AiExposureRuleEntityListener remains the single LlmExposureChangedEvent publish site
- [Phase ?]: Plan 10-06: MetaclassComboBoxHelper extracted as shared @Component for reuse across exposure detail view (10-07) and KB upload form (10-08); single source of truth for @SystemLevel + AI-* internals exclusion
- [Phase ?]: Plan 10-07: detail view reuses MetaclassComboBoxHelper; ComboBox<MetaClass> value-bridged in controller; ReadyEvent for pre-select; 10-08 reingest error keys shipped ahead in Group B; menu uses <item>; EN bundle is messages_en.properties
- [Phase ?]: Plan 10-09: VectorStoreDebugView shipped — plain Vaadin Grid<Document> (Fix R7) over VectorStore.similaritySearch (empty query, topK=100, threshold=0.0); FilterExpressionTextParser with inline setErrorMessage on parse error; metadataFilterField is TypedTextField<String> (Fix W2); 3 programmatic addColumn calls; expand uses standard Vaadin Dialog (Document is Spring AI POJO, no Jmix metaclass); read-only — no edit/delete per CONTEXT D-09
- [Phase ?]: Plan 10-08: KB upload sourceEntityName persisted BEFORE dataManager.save (D-07 invariant); KnowledgeDocumentService.updatePermissionsAndReingest returns UpdatePermissionsResult enum so view has zero business logic (CLAUDE.md compliant); 3-arg upload overload preserved for backward compatibility with IngesterManager + tests
- [Phase ?]: Plan 10-10: TEST-09 four-path uniform-opacity gate landed (RetrievalFilterBuilderDenylistTest unit + LlmExposurePolicyIntegrationTest integration). Two-tier RAG filter coverage. Phase 10 complete (10/10 plans).
- [Phase ?]: Plan 11-01: AiMutationIntent agentstore entity ships with composite unique index on (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME) + REQUEST_HASH + STATUS_ enum (PENDING/COMMITTED/FAILED/COMMIT_UNKNOWN). PENDING reserved before host save so DB unique index serializes duplicates. COMMIT_UNKNOWN parks post-save finalization failures. AiAgentMutationRole is empty marker (no AiMutationIntent READ to avoid leaking idempotency keys); AiAgentAdminRole gains @EntityPolicy(AiMutationIntent, ALL); both locale bundles updated. IDX_AI_MUT_INTENT_STATUS added beyond plan baseline for cleanup-job diagnostics.
- [Phase ?]: Plan 11-02: AiAgentMutationProperties (@ConfigurationProperties ai-agent.tools.mutation) record + AiToolCallOutcome enum extension (IDEMPOTENT_REPLAY/COMMIT_FAILED via EnumClass<String>, no schema migration) + @EnableScheduling on AIConfiguration. Rule 3 auto-fix: AiAuditEventDetailDialog.outcomeTheme switch extended with the 2 new cases. Rule 2 auto-fix: bilingual auditList.outcome.* lowercase keys added alongside metaclass-format keys (AiAuditEventListView+DetailDialog use lowercase convention).
- [Phase ?]: MutationIntent attributes use Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) NOT Map.copyOf — null attribute values represent optional-field clears
- [Phase ?]: MutationGuard default no-op bean lives directly in AIConfiguration via @ConditionalOnMissingBean — no separate SpiDefaultsAutoConfiguration class (mirrors aiAgentIngestExecutor precedent)
- [Phase ?]: ToolVetoedException reused verbatim for MutationGuard veto path — no new exception type
- [Phase ?]: Plan 11-04: ToolEntityResolver shared @Component centralizes Phase 10 R4 unknown_entity opacity for both READ and WRITE tool paths
- [Phase ?]: Plan 11-04: LlmExposurePolicy split into operation-specific canCreate/canUpdate; canModify retained as backward-compatible alias delegating to canUpdate
- [Phase ?]: Repository reservation uses TransactionTemplate (REQUIRES_NEW) so commit-time DataIntegrityViolationException is caught around execute(...) and re-classified
- [Phase ?]: MutationIntentFailureProbe is a package-public ObjectProvider test seam for TEST-12 COMMIT_UNKNOWN coverage
- [Phase ?]: Cleanup job logs but never deletes PENDING/COMMIT_UNKNOWN; auto-deletion would allow duplicate host writes after a finalization failure
- [Phase ?]: MutationErrorTranslator NEVER echoes raw exception text or LLM-supplied attribute names; pre-typed ToolUserError instances are sanitized via canned safe templates per code (P-22 mitigation)
- [Phase ?]: commitFailed maps to concurrent_modification stable code with 'do not retry automatically' hint; the 6-code D-04 taxonomy is closed (no synthetic 7th code for commit-unknown)
- [Phase ?]: Both OptimisticLockException flavors (jakarta + Spring's translated) AND both AccessDeniedException flavors (Spring + io.jmix.core.security) are caught explicitly per RESEARCH Pitfall 5
- [Phase ?]: Plan 11-07B: related-write tools narrowly support non-composition parent OneToMany(mappedBy) + child to-one inverse only
- [Phase ?]: Plan 11-07C locked mutation-tool invariants via JavaDoc + MutationToolInvariantsTest source-level enforcement
- [Phase ?]: 11-09: ObjectProvider.getIfAvailable for BuiltInMutationTools per RESEARCH Q5
- [Phase ?]: 11-09: Mutation callbacks ride MutationToolCallbackBoundaryDecorator (NOT ToolCallbackAuditDecorator) — single audit owner from Plan 11-07C preserved
- [Phase ?]: 11-09: Sibling top-level @Component AgentSystemPromptRulesComposer (no nested @Component precedent)
- [Phase 11]: Use a test-only Jmix module to make mutation fixture persistence win the reverse persistence.xml scan. — Keeps Plan 11-07B fixture ownership intact while making Plan 11-10 integration tests executable.
- [Phase 11]: Use @MockitoBean for mutation guard capture tests instead of nested @TestConfiguration. — Prevents guard test doubles from leaking into unrelated Spring test contexts.
- [Phase 11]: Preserve scalar null mutation attributes before structured-filter literal conversion. — Null values represent optional-field clears at the mutation prompt boundary.
- [Phase 11]: Use @MockitoBean for the commit-unknown failure probe so the failure does not leak into unrelated Spring test contexts.
- [Phase 11]: Keep Java 17-compatible List.get(0) assertions even when JetBrains suggests List.getFirst().
- [Phase 11]: Align the existing tool-name scanner baseline test with Phase 11's read, link, and mutation built-in names.
- [Phase 12]: Persist enabled chat surfaces as deterministic enabledSurfaceIds text with typed helper methods, not as an enum collection property. — Jmix enum collection persistence is not used here, and the Phase 12 contract forbids an enabledSurfaces JavaBean collection on AiUiSettings.
- [Phase 12]: Keep the existing agentstore includeAll changelog strategy and document that it picks up 080-ai-ui-settings.xml. — Changing old changelog include paths could alter Liquibase change identity for deployed databases; includeAll already loads the new file.
- [Phase 12]: Use the included-build Gradle path :ai-agent:ai-agent:* for Phase 12 add-on verification. — The root checkout has no :ai-agent:test task; Gradle exposes the functional module through the nested included-build path.
- [Phase 12]: Use a singleton StandardDetailView for AiUiSettings that loads through AiUiSettingsService.loadCurrent(), so admins edit only AiUiSettings.SINGLETON_ID and cannot create arbitrary settings rows. — Matches the singleton settings model and avoids arbitrary configuration rows.
- [Phase 12]: Keep settings surface controls controller-managed and persist through getEnabledSurfaceSet/setEnabledSurfaceSet, not an enabledSurfaces Jmix entity property. — Avoids unsupported Jmix enum collection binding and preserves the Phase 12 entity contract.
- [Phase 12]: AiAgentAdminRole grants AiUiSettings READ/UPDATE plus view/menu policies only; CREATE/DELETE stay service-internal for the singleton row. — Admins can edit settings while singleton creation remains trusted service code.
- [Phase 12]: Use DialogWindow<?> in AiChatUIState until ChatDialogView is introduced by Plan 12-04. — Keeps Plan 12-03 compiling while preserving the per-UI dialog handle contract.
- [Phase 12]: Keep active stream/run authority in ChatPanelFragment plus CancellationRegistry; AiChatSessionState stores only currentConversationId and listeners. — Preserves D-10 and avoids turning session state into a cancellation authority.
- [Phase 12]: Use the repository composite Gradle path :ai-agent:ai-agent:* for add-on verification. — The root checkout exposes the functional module through an included build, matching prior Phase 12 verification.
- [Phase 12]: Plan 12-05: Use ConversationTitleEligibilityPublisher to isolate title eligibility from DefaultChatServiceImpl. — Keeps chat runtime changes narrow while proving publication happens only after assistant response handling returns.
- [Phase 12]: Plan 12-05: Manual title edits check ownership through ConversationGateway and save through secured DataManager. — Preserves the AI-as-Jmix-client security model while allowing user-visible title overrides to block future auto-title clobbering.
- [Phase ?]: Phase 13 Plan 13-01: AiTaskFile entity foundation + qwen/qwen3.6-35b-a3b model swap shipped; D-03 schema lock (separate addForeignKeyConstraint with onDelete=SET NULL for MESSAGE_ID, REVIEWS HIGH-2); injectedAt is authoritative pending marker (REVIEWS HIGH-1); user role gains DELETE for Plan 04 chip removal (REVIEWS HIGH-3); default-params.yaml model swapped (REVIEWS HIGH-9).
- [Phase ?]: Phase 13 Plan 13-02: AiTaskFileRepository + AiTaskFileMediaResolver + AiTaskFileCleanupJob + package-info shipped. Resolver predicate is injectedAt IS NULL per REVIEWS HIGH-1. markInjected runs in REQUIRES_NEW agentstore tx; deleteRow uses blob-first ordering with retry-on-failure semantics. TEST-16 forbidden-token grep gates at zero across all .java sources except the allowlisted package-info.java JavaDoc (REVIEWS HIGH-8).
- [Phase ?]: 13-03: bulk_save_records as single @Tool with id-presence dispatch (D-02 mixed-batch); ONE @Transactional boundary on MutationSaveExecutor.bulkSave
- [Phase ?]: 13-03: AiMutationIntent.RESULT_SUMMARY column persists bulk savedIds for IDEMPOTENT_REPLAY (REVIEWS HIGH-11)
- [Phase ?]: 13-03: Per-row CrudEntityContext after entity load/create satisfies MUT-14 row-state-dependent constraint enforcement (REVIEWS HIGH-13)
- [Phase 13]: Plan 13-06 gap closure: extracted `DefaultChatServiceImpl.executeBlockingTurn(...)` private helper to eliminate the BLK-01 streaming-fallback double-write — both `ask(...)` and the streaming `catch(UnsupportedOperationException)` now delegate to the same helper passing already-resolved Media + already-persisted user-message id, so `userMessagePersister.persistUserMessage` and `taskFileMediaResolver.resolvePending` each fire EXACTLY ONCE per turn on the D-04 graceful-fallback path. D-03 streaming-success doOnComplete invariant left untouched. BLK-04 (prompt-build orphan) closed implicitly. Regression locked by `DefaultChatServiceImplStreamFallbackTest` (5 Mockito tests, pure JUnit — sidesteps deferred AiAuditEvent boot regression).
- [Phase ?]: Phase 13.1 Plan 01: Wave-0 schema lock — dropped AI_TASK_FILE.MESSAGE_ID/INJECTED_AT (Liquibase 100), removed AiTaskFile.message/injectedAt fields, flipped AiTaskFileProperties to long ttlSeconds + perTurnMaxFiles + perTurnMaxTotalBytes with -1 sentinel, added AiMessageRole.NOTICE + bilingual captions. Compile passes via Rule 3 stubs in AiTaskFileRepository.markInjected, ConversationDetailView switch, and ChatPanelFragment.setExpiresAt; full rewrite in Plans 13.1-02/04. Three Phase 13 tests pinned to ai-agent.task-file.ttl=PT1H deferred to Plan 13.1-06.
- [Phase 13.1]: Plan 02: AiTaskFileMediaResolver.resolveActive(UUID) per-turn-all + LRU budget cap + task_file_budget_exceeded audit (REQUIRES_NEW; failure swallowed to log.warn). Resolved record now (media, budgetExceeded); taskFileIds dropped. AiTaskFileRepository slimmed to {loadExpired, deleteRow, deleteAllExpired} — markInjected and loadPending deleted with their AiMessage/Optional imports. New BudgetExceededAuditKeys constants are the single source of truth shared with the upcoming BudgetCapTest (Plan 13.1-06) — 9-key argumentsJson order locked via LinkedHashMap; Jackson JsonProcessingException falls back to a String.format literal that ALSO references the constants. Rule-3 stub: DefaultChatServiceImpl resolvePending/markInjected/taskFileIds call sites swapped for resolveActive + structural no-ops; Plan 13.1-03 deletes the dead UserMessagePersister wiring + executeBlockingTurn parameter end-to-end.
- [Phase 13.1]: Plan 03: ProjectingChatMemoryRepository.saveAll JPQL excludes role=NOTICE so notice rows survive the delete-recreate projection wipe each turn (D-A1). DefaultChatServiceImpl now calls AiTaskFileMediaResolver.resolveActive(convId) once per turn on both the blocking ask() and the streaming stream() transports; UserMessagePersister field/parameter and the markInjected stamping path are removed; executeBlockingTurn signature drops userMessageIdAlreadyPersisted while preserving the BLK-01 single-write streaming-fallback invariant. ChatResponseDto + StreamingEvent.Final extended with a budgetExceeded boolean propagated from Resolved.budgetExceeded() on both transports (D-D1). UserMessagePersister.java deleted; chat-memory advisor's own AiMessage projection is the sole user-message persistence path. DefaultChatServiceImplStreamFallbackTest rewritten with 4 cases (A/B/C/D) covering resolveActive-once-per-turn on both transports + budgetExceeded propagation on both — all green on pure JUnit 5 + Mockito. Rule-3 stubs on the 3 deferred Plan-13.1-01 test files (AiTaskFileCleanupJobTest, AiTaskFileMediaResolverIntegrationTest, AiTaskFileNoVectorStoreInvocationTest) were the minimum-diff required to unblock :compileTestJava; Plan 13.1-06 owns the proper rewrite.
- [Phase 13.1]: Plan 04: chat-panel-fragment.xml reshaped into a horizontal `<split splitterPosition="68">` with chatPanel left and attachmentsPanel right; right-pane vbox keeps id="attachmentsPanel" so the Phase 12 ChatSurfaceMounter slot contract is preserved (REQ-7 zero-diff). New ai-task-file-card-fragment.xml + AiTaskFileCardFragmentRenderer.java (extends FragmentRenderer<JmixCard, AiTaskFile> + @RendererItemContainer("taskFileDc")) ship the per-row card with DOWNLOAD action via Jmix Downloader and TRASH action via Dialogs option-confirm; performDelete removes the JPA row first and best-effort removes the storage blob (log-and-continue on blob failure — TTL cleanup sweeps orphans, D-B1). resolveIcon adds an image-extension arm (png/jpg/jpeg/gif/webp → FILE_PICTURE) on top of the CRM csv/xlsx → TABLE and pdf/html/md/txt → FILE_TEXT_O switch. ai-agent-chat.css gets a verbatim CRM appendix (14 new top-level rules) with the single mechanical rename .ai-conversation-message-list → .ai-agent-chat-panel__messages. 13 new chatView.attachments.* keys land in both messages_en.properties and messages_vi.properties. Java fragment controller wiring (taskFilesDl loader binding, empty-state toggle, NOTICE rendering, budgetExceeded toast) is owned by Plan 13.1-05 — this plan delivers the stable XML/CSS/i18n surface against which Plan 05 builds. One Rule-1 deviation: the documentation comment in chat-panel-fragment.xml was rephrased so it doesn't name the deleted `attachRow`/`chip-strip` ids (the verify regex scans comments alongside elements).
- [Phase 13.1]: Plan 05: ChatPanelFragment.java rewired in a single-file rewrite — chip-strip + MessageList substrate retired; right-pane data loader (taskFilesDl) bound programmatically with :conversationId in onReady, setConversationIdInternal, ensureConversationIdForSubmit, ensureConversationIdForUpload; empty-state toggle wired via @Subscribe(id="taskFilesDl", target=Target.DATA_LOADER) onTaskFilesPostLoad over CollectionLoader.PostLoadEvent. messageListSlot migrated from MessageList to a VerticalLayout of MessageBubbleComponent Composites for USER/ASSISTANT plus raw <vaadin-message class="attachment-event"> sibling Elements for NOTICE (Pitfall 7 Option A). handleUploadedFile persists an AiMessage(role=NOTICE) via metadataApi.create with seq computed via DataManager.loadValue(...).store("agentstore") (memory feedback_jmix_loadvalue_store), log-and-continue on failure. Budget-exceeded toast invoked via single showBudgetExceededToast() helper from BOTH paths: streaming consumer reads StreamingEvent.Final.budgetExceeded() inside doOnNext; package-private onBlockingResponse(ChatResponseDto) reads ChatResponseDto.budgetExceeded() (covers blocking-path consumers and tests per CONTEXT D-D1). attachmentsPanel field type stays VerticalLayout (REQ-7 / Pitfall 6); Phase 12 contract files (ChatSurfaceMounter, AiUiSettingsService, AiUiSettings, ChatView, ChatDialogView) have ZERO diff. Two Rule-3 auto-fixes: (a) bundle-key resolution form switched from class-scoped Messages.getMessage(class, key) to bare Messages.getMessage(key) + explicit-group Messages.formatMessage("com.vn.agent", key, params) per memory feedback_jmix_messages_over_spring; (b) field-block comment rephrased to remove a literal "MessageListItem" token that tripped the verify regex.
- [Phase 13.1]: Plan 06: 4 new @SpringBootTest regressions land — PerTurnMediaInjectionTest (TEST-18 — 3 sequential resolveActive calls return the same Media bytes; reflection guard via Class#getDeclaredMethods asserts no markInjected/loadPending overload survives on AiTaskFileRepository), BudgetCapTest + BudgetCapSentinelTest (REQ-3 default-caps drop-oldest + LRU + 9-key argumentsJson via BudgetExceededAuditKeys constants; sentinel caps=-1 returns all rows + zero audit rows), TtlConfigTest + TtlConfigSentinelSkipsCleanupTest + TtlConfigFkCascadeUnderSentinelTest (REQ-4 default ttlSeconds=86400; sentinel ttl-seconds=-1 skip on cleanup-job; FK cascade still reaps under sentinel), NoticeFilterTest (REQ-5 D-A1 NOTICE survives ProjectingChatMemoryRepository.saveAll wipe + D-A2 Spring AI store never carries NOTICE). Sentinel-context fixtures ship as TOP-LEVEL sibling classes (NOT @Nested) so Spring Boot context cache stays clean. The 3 existing Phase 13 tests pinned to ai-agent.task-file.ttl=PT1H now use ttl-seconds=3600; AiTaskFileMediaResolverIntegrationTest's @Disabled placeholder replaced with 3 happy-path cases against resolveActive. TaskFileNoVectorStoreSourceScannerTest scope widened to AiTaskFileCardFragmentRenderer.java + chat-panel-fragment.xml — both pass with 0 forbidden-token references. compileTestJava + scanner test BUILD SUCCESSFUL. Test runtime for the 4 new @SpringBootTest classes inherits the pre-existing Phase 11/13 atmosphere-runtime / agentstoreEntityManagerFactory boot regression documented in .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md (verified by reproducing on the pre-Plan-06 AiTaskFileMediaResolverIntegrationTest @Disabled placeholder); not introduced by this plan, surface stays compileTestJava-green and source-correct.
- [Phase 13.1]: Plan 07: 5 plan-required tests land green TODAY (sidesteps the deferred Spring-context boot regression). CrmStyleLayoutTest (3 cases) parses chat-panel-fragment.xml + chat-view.xml + chat-dialog-view.xml directly to assert split splitterPosition=68 + the documented right-pane slot ids (attachmentsPanel/Title/EmptyState/GridLayout/taskFileUpload) + zero attachRow/attachButton residue + both surfaces mount the same ChatPanelFragment. NoticeRenderTest (3 cases) source-scans ChatPanelFragment.java for the appendNoticeRow helper + raw <vaadin-message class="attachment-event"> Element substrate + setProperty("text",...) T-13.1-17 escape mitigation + clearMessageList wipes both Component and raw Element children + zero MessageListItem residue + AiMessageRole.NOTICE enum guard. SurfaceMountingTest (2 cases) asserts both surface descriptors mount the fragment with no slot-id overrides + git diff against origin/main on the 5 Phase 12 contract files (ChatSurfaceMounter, AiUiSettingsService, AiUiSettings, ChatView, ChatDialogView) returns empty; falls back to assertContractMarkers structural sanity check when git is unavailable in CI. LiquibaseSchemaTest (5 cases) parses 100-ai-task-file-drop-dead-columns.xml + 090-ai-task-file.xml directly: exactly one <dropForeignKeyConstraint> for FK_AI_TASK_FILE__ON_MESSAGE, two <dropIndex> for IDX_..._ON_MESSAGE + IDX_..._INJECTED_AT, two <dropColumn> for MESSAGE_ID + INJECTED_AT, FK-drop changeSet precedes column-drop changeSet, FK_AI_TASK_FILE__ON_CONVERSATION declared INLINE on CONVERSATION_ID column with deleteCascade=true (Pitfall 10 invariant), no <addForeignKeyConstraint> ever names the conversation FK. LocaleParityTest extension (+2 tests) asserts all 14 chatView.attachments.* + AiMessageRole.NOTICE keys present + non-blank in BOTH bundles + chatView.attachments.* namespace symmetric difference is empty. One Rule-3 auto-fix: conversationCascadeFkSurvivesInTheCreateChangelog initially asserted via <addForeignKeyConstraint> but the conversation FK is INLINE on the CONVERSATION_ID <column> per Phase 13 D-03 — fixed before commit. UI/schema substrate is XML/source-scan rather than @UiTest/@SpringBootTest per the plan's project_context preamble explicitly authorizing the deferred-items.md mirror. All 16 testcases green.

### Performance Metrics

| Phase-Plan | Duration | Tasks | Files | Date |
|------------|----------|-------|-------|------|
| 09-01 | ~6 min | 2 | 4 | 2026-04-27 |
| Phase 09 P02 | 6min | 3 tasks | 3 files |
| Phase 09 P03 | 25min | 2 tasks | 5 files |
| Phase 09 P04 | 28min | 3 tasks | 10 files |
| Phase 09 P05 | 22min | 2 tasks | 14 files |
| Phase 09 P06 | 30min | 2 tasks | 3 files |
| Phase 09 P07 | 12min | 4 tasks | 6 files |
| Phase 10 P01 | 15 | 2 tasks | 6 files |
| Phase 10 P10-02 | 9 | 2 tasks | 5 files |
| Phase 10 P03 | 1 | 1 tasks | 1 files |
| Phase 10 P10-04 | 18 | 2 tasks | 9 files |
| Phase 10 P10-05 | 12 | 2 tasks | 3 files |
| Phase 10 P10-06 | 12 | 2 tasks | 5 files |
| Phase 10 P10-07 | 18 | 2 tasks | 5 files |
| Phase Phase 10 PP10-09 | 8 | 2 tasks | 2 files |
| Phase Phase 10 PP10-08 | 25 | 2 tasks | 7 files |
| Phase Phase 10 PP10-10 | 5 | 2 tasks | 2 files |
| Phase Phase 11 PP11-01 | 2min | 2 tasks | 8 files |
| Phase Phase 11 PP11-02 | 3min | 2 tasks | 6 files |
| Phase 11-mutation-capable-built-in-tools P03 | 2min | 1 tasks | 3 files |
| Phase 11 P04 | 11min | 2 tasks | 4 files |
| Phase 11 P05 | 9min | 2 tasks | 2 files |
| Phase 11-mutation-capable-built-in-tools P06 | 6min | 2 tasks | 3 files |
| Phase 11 P07B | 1h | 4 tasks | 13 files |
| Phase 11-mutation-capable-built-in-tools P07C | 12m | 2 tasks | 2 files |
| Phase 11 P08 | 25min | 1 tasks | 3 files |
| Phase 11 P09 | 35min | 2 tasks | 9 files |
| Phase 11 P10 | 25min | 4 tasks | 19 files |
| Phase 11-mutation-capable-built-in-tools P11 | 20min | 2 tasks | 10 files |
| Phase 11 P12 | 8min | 2 tasks | 3 files |
| Phase 11 P13 | 12min | 2 tasks | 4 files |
| Phase 12 P01 | 16 min | 3 tasks | 12 files |
| Phase 12 P02 | 23 min | 2 tasks | 9 files |
| Phase 12 P03 | 10 min | 2 tasks | 5 files |
| Phase 12 P04 | 38 min | 3 tasks | 9 files |
| Phase 12 P05 | 43 min | 3 tasks | 17 files |
| Phase 12 P06 | 29 min | 3 tasks | 5 files |
| Phase 13 P13-01 | 25min | 2 tasks | 11 files |
| Phase 13 P13-02 | 25min | 2 tasks | 4 files |
| Phase 13 P03 | 9m | 2 tasks | 11 files |
| Phase 13 P13-04 | ~10m | 2 tasks | 4 files |
| Phase 13 P13-06 (gap) | ~22m | 2 tasks | 2 files |
| Phase 13.1 P01 | 25min | 2 tasks | 10 files |
| Phase 13.1 P02 | ~20min | 2 tasks | 4 files |
| Phase 13.1 P03 | ~25min | 2 tasks | 10 files |
| Phase 13.1 P04 | ~15min | 2 tasks | 6 files |
| Phase 13.1 P05 | ~30min | 3 tasks | 1 file |
| Phase 13.1 P06 | ~50min | 2 tasks | 8 files |
| Phase 13.1 P07 | ~25min | 2 tasks | 5 files |

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260427-9ci | Add a read-only Jmix admin UI view to inspect the current AI baseline context generated by BaselineContextProvider.renderAsText, including agent.entities and agent.permissions, with refresh only and no editing | 2026-04-26 | 6a39184 | [260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in](./quick/260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in/) |

## Session Continuity

**Last session:** 2026-05-07T23:23:02.526+07:00
**Stopped at:** Phase 14 planned
**Resume file:** .planning/phases/14-intent-driven-extraction-form-prefill/14-01-PLAN.md
**Blockers:** Pre-existing Phase 11/13 Spring-context boot regression (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBoundsException) still blocks runtime of all module-level @SpringBootTest classes including Plan 13.1-06's 4 new ones; not introduced by 13.1; documented in .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md. Plan 13.1-07 sidesteps via XML/source-scan tests per the plan's project_context preamble.
**Next action:** Execute Phase 14 — Intent-Driven Extraction → Form Prefill.
