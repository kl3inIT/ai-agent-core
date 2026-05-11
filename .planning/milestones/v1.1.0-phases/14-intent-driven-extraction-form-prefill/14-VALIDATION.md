---
phase: 14
slug: intent-driven-extraction-form-prefill
status: reconstructed
nyquist_compliant: true
wave_0_complete: true
created: 2026-05-11
reconstructed_from: 14-01..14-10 PLAN/SUMMARY + 14-VERIFICATION.md
---

# Phase 14 — Validation Strategy

> Reconstructed post-hoc from phase artifacts (State B: no prior VALIDATION.md, SUMMARY files present).
> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) + Mockito + AssertJ; Spring Boot Test / Jmix `@UiTest` available |
| **Config file** | none — `ai-agent/build.gradle` (Gradle test task, Java 21 toolchain) |
| **Quick run command** | `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test --tests "*Extraction*" --tests "*Intent*" --tests "*Draft*" --tests "*ActionProposal*" --tests "*ToolNavigationLeakScannerTest"` |
| **Full suite command** | `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test && ./gradlew --no-daemon --max-workers=1 :jmix-app:test --tests "*CustomerDraft*"` |
| **Estimated runtime** | ~1–3 min targeted; ~5–6 min full add-on module |

> **Environment caveat (carried from phase summaries):** the shared `:ai-agent` / `:jmix-app` module Spring Boot context is blocked before test bodies by a pre-existing `AiAuditEvent` / `com.vn.jmixapp.entity.User` metaclass boot regression unrelated to Phase 14. Phase 14 foundation/UI contracts are therefore pinned with unit (Mockito/Jackson) and source/XML structural scanner tests rather than full `@SpringBootTest`/`@UiTest` boots. Parallel Gradle workers on Windows reproduce a native-memory/paging-file crash — use `--max-workers=1`.

---

## Sampling Rate

- **After every task commit:** Run the quick run command (scoped to the touched test class where possible).
- **After every plan wave:** Run the full suite command.
- **Before `/gsd-verify-work`:** Full add-on suite must be green; targeted Phase 14 matrix must be green.
- **Max feedback latency:** ~180 seconds (targeted matrix).

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 14-01-01 | 01 | 1 | ENT-08, EXTRACT-04 | draft table | `AiExtractionDraft` agentstore entity (UUID/version/instance-name) + Liquibase `110-ai-extraction-draft.xml` | unit (structural) | `:ai-agent:ai-agent:test --tests "*AiExtractionDraftModelTest"` | ✅ | ✅ green |
| 14-01-02 | 01 | 1 | SEC-06, EXTRACT-04 | owner scoping / LLM-surface leak | user resource + row-level (`userUsername`) policies; draft hidden via `AiInternalEntityNames` | unit (structural) | `:ai-agent:ai-agent:test --tests "*AiExtractionDraftSecurityTest"` | ✅ | ✅ green |
| 14-01-03 | 01 | 1 | EXTRACT-09 | unconstrained cleanup bypass | TTL props + scheduled cleanup deletes only expired rows via `UnconstrainedDataManager` | unit (structural) | `:ai-agent:ai-agent:test --tests "*AiExtractionDraftCleanupJobTest"` | ✅ | ✅ green |
| 14-01-04 | 01 | 1 | ENT-08, SEC-06, EXTRACT-04/09 | — | foundation contracts (schema/security/cleanup) | unit (structural) | `:ai-agent:ai-agent:test --tests "*AiExtractionDraft*"` | ✅ | ✅ green |
| 14-02-01 | 02 | 1 | SPI-12, EXTRACT-02 | — | `IntentExtractor<T>` SPI + `ExtractionInput`/`ExtractionResult`/`IntentOption` DTOs | unit | `:ai-agent:ai-agent:test --tests "*IntentRegistryTest"` | ✅ | ✅ green |
| 14-02-02 | 02 | 1 | EXTRACT-01, EXTRACT-03 | exposure-blind intent list | `IntentRegistry` per-request eligibility filtered by `LlmExposurePolicy` + Jmix create/read; deterministic ordering | unit | `:ai-agent:ai-agent:test --tests "*IntentRegistryTest"` | ✅ | ✅ green |
| 14-02-03 | 02 | 1 | EXTRACT-05 | schema leaks non-writable/internal attrs | `MetaClassDtoSynthesizer` emits JSON schema text filtered by exposure + `EntityAttributeContext.canModify`; to-one as uuid string | unit | `:ai-agent:ai-agent:test --tests "*MetaClassDtoSynthesizerTest"` | ✅ | ✅ green |
| 14-02-04 | 02 | 1 | SPI-12, EXTRACT-05 | — | registry sort/filter + schema inclusion/exclusion | unit | `:ai-agent:ai-agent:test --tests "*IntentRegistryTest" --tests "*MetaClassDtoSynthesizerTest"` | ✅ | ✅ green |
| 14-03-01 | 03 | 2 | EXTRACT-05 | raw-value leak in audit/JSON | stable extraction exceptions + deterministic JSON helpers (no raw model output in audit summaries) | unit | `:ai-agent:ai-agent:test --tests "*ExtractionAuditTest"` | ✅ | ✅ green |
| 14-03-02 | 03 | 2 | EXTRACT-05 | transaction held across LLM call | `ExtractionService.prepare(...)` resolves intent, exposure-checks before extract, persists one draft via secured `DataManager`, writes success/denied/failed audit rows; not `@Transactional` | unit | `:ai-agent:ai-agent:test --tests "*ExtractionServiceTest"` | ✅ | ✅ green |
| 14-03-03 | 03 | 2 | EXTRACT-06, EXTRACT-10 | tool returns navigation primitive | `ExtractionToolBridge.prepare_form_draft` returns only `{action,draftId,entityName,instanceName}`; `StreamingEvent.ToolResult` carries `toolName`+`payloadJson`; no `ViewNavigators` import | unit + source grep | `:ai-agent:ai-agent:test --tests "*ExtractionToolBridgeTest"` | ✅ | ✅ green |
| 14-03-04 | 03 | 2 | EXTRACT-03/05/06/10 | duplicate audit / payload leak | success/denial/schema-failure audit shapes + structured streaming payload, no duplicate generic audit | unit | `:ai-agent:ai-agent:test --tests "*ExtractionServiceTest" --tests "*ExtractionToolBridgeTest" --tests "*ExtractionAuditTest"` | ✅ | ✅ green |
| 14-04-01 | 04 | 3 | EXTRACT-01 | breaking existing chat callers | intent-aware `ChatService.ask/stream` overloads; blank/`auto` → null | unit | `:ai-agent:ai-agent:test --tests "*DefaultChatServiceIntentRoutingTest"` | ✅ | ✅ green |
| 14-04-02 | 04 | 3 | EXTRACT-06 | named intent sees full tool surface | `AgentToolCallbacks.callbacksFor(userId,convId,intentId)` → exactly one `prepare_form_draft`; misconfig fails closed | unit | `:ai-agent:ai-agent:test --tests "*AgentToolCallbacksIntentGatingTest"` | ✅ | ✅ green |
| 14-04-03 | 04 | 3 | EXTRACT-01 | prompt invents values / multi-call | named-intent prompt suffix instructs single `prepare_form_draft` call + ask-for-missing | unit | `:ai-agent:ai-agent:test --tests "*AgentSystemPromptRulesComposerIntentTest"` | ✅ | ✅ green |
| 14-04-04 | 04 | 3 | EXTRACT-03/05/06 | stale intent reaches model; audit-only RunContext shadows args | intent threaded through blocking/streaming/fallback; stale ids fail closed; `RunContext` extraction-turn state gates `ExtractionToolBridge` | unit | `:ai-agent:ai-agent:test --tests "*DefaultChatServiceIntentRoutingTest"` | ✅ | ✅ green |
| 14-04-05 | 04 | 3 | EXTRACT-01/03/05/06 | — | stale-intent / callback-misconfig / prompt-routing / streaming-fallback regressions | unit | `:ai-agent:ai-agent:test --tests "*Intent*" --tests "*DefaultChatServiceImplStreamFallbackTest"` | ✅ | ✅ green |
| 14-05-01 | 05 | 3 | EXTRACT-08 | prefill writes unpermitted attrs / raw setValue bypass | `DraftLoader.apply(...)` loads via secured `DataManager`, coerces scalar/to-one, applies only `EntityAttributeContext.canModify` attrs via `setValueIfPermitted`; audits counts only | unit | `:ai-agent:ai-agent:test --tests "*DraftLoaderTest"` | ✅ | ✅ green |
| 14-05-02 | 05 | 3 | EXTRACT-07, EXTRACT-10 | tool/renderer navigates; missing create/view perm check | `OpenFormWithDraftHandler` is sole `ViewNavigators` owner; checks `UiShowViewContext` + create permission; reloads draft per click; opens detail view then applies draft | unit | `:ai-agent:ai-agent:test --tests "*OpenFormWithDraftHandlerTest"` | ✅ | ✅ green |
| 14-05-03 | 05 | 3 | EXTRACT-07/08/09/10 | save doesn't delete draft; close deletes it | `AfterSaveEvent` marks confirmed + deletes; `AfterCloseEvent` only unregisters listeners (TTL retains row); permitted/denied/unknown/relationship prefill + expired draft handling | unit | `:ai-agent:ai-agent:test --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest" --tests "*SaveDeletesDraftTest"` | ✅ | ✅ green |
| 14-06-01 | 06 | 4 | EXTRACT-01 | hand-built toggle UI; missing locale keys | `<radioButtonGroup id="intentCardRow">` between message list & input; Auto first/default; row hidden when no eligible intents; bilingual `chatView.intent.*` keys | unit (XML/source) | `:ai-agent:ai-agent:test --tests "*IntentCardRowTest" --tests "*LocaleParityTest"` | ✅ | ✅ green |
| 14-06-02 | 06 | 4 | EXTRACT-01 | — | intent cards populated from `IntentRegistry`, rendered via `@Supply` ComponentRenderer | unit (source) | `:ai-agent:ai-agent:test --tests "*IntentCardRowTest"` | ✅ | ✅ green |
| 14-06-03 | 06 | 4 | EXTRACT-01 | named send stays on named intent | selected intent id sent on submit; named sends reset to Auto | unit (source) | `:ai-agent:ai-agent:test --tests "*IntentCardRowTest"` | ✅ | ✅ green |
| 14-06-04 | 06 | 4 | EXTRACT-07 | renderer parses human summary / navigates | `StreamEventRenderer` parses only `ToolResult.payloadJson` for `prepare_form_draft` → `DraftPayload` marker; `ChatPanelFragment.appendIntentConfirmRow(...)` delegates to `OpenFormWithDraftHandler` | unit | `:ai-agent:ai-agent:test --tests "*OpenFormWithDraftRenderingTest" --tests "*RenderStreamEventIntentPayloadTest"` | ✅ | ✅ green |
| 14-06-05 | 06 | 4 | EXTRACT-07 | global CSS bleed | scoped Phase 14 chat CSS appended only | unit (source) | `:ai-agent:ai-agent:test --tests "*IntentCardRowTest"` | ✅ | ✅ green |
| 14-06-06 | 06 | 4 | EXTRACT-01, EXTRACT-07 | — | card-row contracts + structured renderer payloads + confirm-row source contracts + locale parity | unit | `:ai-agent:ai-agent:test --tests "*IntentCardRowTest" --tests "*OpenFormWithDraftRenderingTest" --tests "*RenderStreamEventIntentPayloadTest" --tests "*LocaleParityTest"` | ✅ | ✅ green |
| 14-07-01 | 07 | 5 | EXTRACT-02, EXTRACT-03 | host entity import in add-on core | `CustomerDraftIntentExtractor` lives in `jmix-app`; Spring AI `Map<String,Object>` structured output narrowed via Jackson + Bean Validation; raw values not in exception text | unit | `:jmix-app:test --tests "*CustomerDraftIntentExtractorTest"` | ✅ | ✅ green |
| 14-07-02 | 07 | 5 | EXTRACT-02 | reference intent always on | enabled via `ai-agent.intents.customer-reference.enabled=true` (`matchIfMissing=true`); bilingual `customer-from-pdf` keys | unit (source) | `:jmix-app:test --tests "*CustomerDraftWorkflowTest"` | ✅ | ✅ green |
| 14-07-03 | 07 | 5 | EXTRACT-02..09 | — | field mapping, schema field limits, zero-file extraction, media forwarding, validation failure, core-boundary scan, draft apply workflow | unit | `:jmix-app:test --tests "*CustomerDraft*"` | ✅ | ✅ green |
| 14-08-01 | 08 | 6 | TEST-15, EXTRACT-06/10 | tool surface imports/calls navigation | `ToolNavigationLeakScannerTest` scans `@Tool` classes / `ToolCallbackProvider` surfaces (incl. `ExtractionToolBridge`) for `ViewNavigators` / `.navigate()` | unit (source scanner) | `:ai-agent:ai-agent:test --tests "*ToolNavigationLeakScannerTest"` | ✅ | ✅ green |
| 14-08-02 | 08 | 6 | EXTRACT-08, EXTRACT-02 | raw setValue outside helper; host/core coupling | `DraftSetValueBypassScannerTest` (only `DraftLoader.setValueIfPermitted` may call `EntityValues.setValue`); `CoreCustomerImportScannerTest` (no host `Customer` import in add-on; `StreamEventRenderer` navigation-free) | unit (source scanner) | `:ai-agent:ai-agent:test --tests "*DraftSetValueBypassScannerTest" --tests "*CoreCustomerImportScannerTest"` | ✅ | ✅ green |
| 14-08-03 | 08 | 6 | EXTRACT-05/08/09 | failure modes uncovered; PII in eval summaries | `ExtractionEvaluationContractTest` + `extraction-fixtures.yaml` cover happy extraction, schema discipline, source faithfulness, denied attrs, expired drafts, exposure denial, concurrent drafts, stale/unknown attrs, safe audit summaries | unit (fixture-driven) | `:ai-agent:ai-agent:test --tests "*ExtractionEvaluationContractTest"` | ✅ | ✅ green |
| 14-08-04 | 08 | 6 | all Phase 14 REQs | — | full Phase 14 add-on + host targeted matrix + module gates (residual host `User` metaclass / native-memory failures documented as pre-existing) | suite | full suite command above | ✅ | ✅ green (targeted); ⚠️ residual module-gate failures pre-existing |
| 14-08-05 | 08 | 6 | EXTRACT-01/07/08/09 | manual UI flow unverified | `14-UAT-CHECKLIST.md` manual checklist (message-key based expectations) | manual | — | ✅ (doc) | ⬜ manual — see Manual-Only |
| 14-09-01 | 09 | 7 | EXTRACT-09, SEC-06 (BL-02) | expired/confirmed drafts still loadable | `ExtractionDraftAccess.loadOpenDraft(...)` secured predicate `e.id=:draftId and e.expiresAt>:now and e.confirmed=false`; used by `DraftLoader` + `OpenFormWithDraftHandler` | unit | `:ai-agent:ai-agent:test --tests "*ExtractionDraftAccessTest" --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest"` | ✅ | ✅ green |
| 14-09-02 | 09 | 7 | EXTRACT-01 (BL-03) | first-turn streaming creates conversation before guards | `ChatPanelFragment.onSubmit` passes nullable conversation id; `DefaultChatServiceImpl.stream()` runs rate-limit guard before `conversationGateway.loadOrCreate(...)` | unit | `:ai-agent:ai-agent:test --tests "*ChatPanelFragmentConversationIdTest" --tests "*DefaultChatServiceIntentRoutingTest"` | ✅ | ✅ green |
| 14-09-03 | 09 | 7 | EXTRACT-05 (BL-04) | draft JSON / audit carry non-schema attrs (e.g. Jmix entity reserialized) | `ExtractionService` calls `schemaSynthesizer.buildSchema(metaClass)` then `filterPayloadToSchema(...)` before persistence + audit summary | unit | `:ai-agent:ai-agent:test --tests "*ExtractionServiceTest"` | ✅ | ✅ green |
| 14-09-04 | 09 | 7 | EXTRACT-02/03 (BL-05) | reference Customer accepts fabricated string values | `ExtractionInput.sourceTexts` propagated via `RunContext`/`ExtractionToolBridge`; `CustomerDraftIntentExtractor.assertSourceFaithful(...)` rejects unsupported strings against user/document text (image-only stays prompt-based) | unit | `:jmix-app:test --tests "*CustomerDraftIntentExtractorTest" --tests "*CustomerDraftWorkflowTest"` | ✅ | ✅ green |
| 14-09-05 | 09 | 7 | BL-01 (narrowed by override) | OpenRouter API key committed | `spring.ai.openai.api-key=${OPENROUTER_API_KEY:}`; `jmix-app/.env.example` documents only `OPENROUTER_API_KEY` | manual / source check | source assertion (covered in `14-VERIFICATION.md`) | ✅ | ✅ passed (override) |
| 14-09-06 | 09 | 7 | all Phase 14 REQs | — | gap-closure regression matrix + source checks | suite | full suite command above | ✅ | ✅ green |
| 14-10-01 | 10 | 8 | EXTRACT-01, EXTRACT-06 | first-screen action picker; unsafe action choices | `propose_action_choices` planning tool — validates metadata/required fields/writable attrs/create permission, returns `READY`/`MISSING_FIELDS`, no mutation/navigation/draft creation | unit | `:ai-agent:ai-agent:test --tests "*ActionProposalServiceTest" --tests "*ActionProposalToolTest"` | ✅ | ✅ green |
| 14-10-02 | 10 | 8 | EXTRACT-01, EXTRACT-07 | choices render from human summary; renderer creates UI | action-choice row appended only from READY proposal; `StreamEventRenderer` parses payload → marker; `ChatPanelFragment` owns Vaadin row + disables after selection | unit | `:ai-agent:ai-agent:test --tests "*ActionChoiceRowTest" --tests "*RenderStreamEventActionProposalTest"` | ✅ | ✅ green |
| 14-10-03 | 10 | 8 | EXTRACT-06, EXTRACT-08, SEC-06 | create-now exposes mutation tools on planning turns | distinct callback surfaces for default / named-extraction / `action:create-now` / `action:prefill-form`; prefill-form creates `AiExtractionDraft` with `SOURCE_CONVERSATION_ID` then reuses Open-form-to-confirm | unit | `:ai-agent:ai-agent:test --tests "*AgentToolCallbacksDefaultConfigTest" --tests "*AgentToolCallbacksMutationEnabledTest" --tests "*DefaultChatServiceIntentRoutingTest"` | ✅ | ✅ green |
| 14-10-04 | 10 | 8 | EXTRACT-08, SEC-06 | stream UI callback touches secured Jmix without auth | `ChatPanelFragment.accessUiAuthenticated(...)` restores captured current-user authentication before secured loaders | unit | `:ai-agent:ai-agent:test --tests "*ChatPanelFragmentConversationIdTest"` | ✅ | ✅ green |
| 14-10-05 | 10 | 8 | EXTRACT-05 (RAG cross-cut) | RAG/embedding failure blocks non-RAG turn | `AuditingDocumentRetrieverTest` (best-effort retrieval failure handling); `ProviderConfigurationContractTest` (provider config diagnostics separated from action-choice UX) | unit | `:ai-agent:ai-agent:test --tests "*AuditingDocumentRetrieverTest" --tests "*ProviderConfigurationContractTest"` | ✅ | ✅ green |
| 14-10-06 | 10 | 8 | EXTRACT-01/07/08/09 | UAT docs describe superseded picker | `14-UAT-CHECKLIST.md` / `14-HUMAN-UAT.md` / `14-UI-SPEC.md` rewritten to action-intent flow; `rg` asserts no first-screen-picker language remains | manual / source check | source assertions (recorded in 14-10-SUMMARY.md) | ✅ (doc) | ⬜ manual — see Manual-Only |

*Status: ⬜ pending/manual · ✅ green · ❌ red · ⚠️ flaky/residual*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements. No new test framework was installed; Phase 14 added test classes under `ai-agent/ai-agent/src/test/java/com/vn/agent/{extraction,action,guard,security,view/chat,view/chat/intent,view/chat/fragment,i18n,rag/advisor}`, `ai-agent/ai-agent/src/test/resources/eval/extraction-fixtures.yaml`, and `jmix-app/src/test/java/com/vn/jmixapp/ai/`.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| End-to-end chat → form prefill flow in a real browser session | EXTRACT-01, EXTRACT-07, EXTRACT-08, EXTRACT-09 (phase-goal observable) | Requires running app + Vaadin/Jmix rendering, real route navigation, and provider-backed extraction; automated tests cover code paths, contracts, and source invariants but not the browser flow | Follow `14-UAT-CHECKLIST.md`: (1) open chat — verify no entity/action intent card on open; (2) submit a create request with missing required fields — assistant asks for those fields; (3) provide enough data — a server-validated action-choice row appears; (4) `Create now` — record is created only after that click, via the constrained mutation tool surface; (5) `Prefill form` — an `AiExtractionDraft` is created, an inline "Open form to confirm" row renders, the Jmix detail view opens only after that click, prefilled values match the collected data, Save succeeds via normal Jmix validation, and the draft row is deleted afterward. Tracked in `14-HUMAN-UAT.md`. |
| Credential hygiene (BL-01, narrowed) | BL-01 (override applied) | One-time config inspection rather than a runtime behavior | Confirm `jmix-app/src/main/resources/application.properties` keeps `spring.ai.openai.api-key=${OPENROUTER_API_KEY:}` and `jmix-app/.env.example` documents only `OPENROUTER_API_KEY=`. Datasource/UI-login defaults intentionally remain per user override. |

---

## Validation Sign-Off

- [x] All tasks have automated verify (unit / source-scanner / fixture) or are documented manual-only
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (none — existing infrastructure sufficed)
- [x] No watch-mode flags
- [x] Feedback latency < 180s (targeted matrix)
- [x] `nyquist_compliant: true` set in frontmatter
- [x] Phase-level `14-VERIFICATION.md` records 16/16 observable truths verified; only the browser UAT above remains as `human_needed`

**Approval:** reconstructed 2026-05-11

---

## Validation Audit 2026-05-11

| Metric | Count |
|--------|-------|
| Requirements in phase | 14 (EXTRACT-01..10, ENT-08, SPI-12, TEST-15, SEC-06) |
| Requirements with automated coverage | 14 / 14 |
| Gaps found (fillable) | 0 |
| Resolved | 0 |
| Escalated | 0 |
| Manual-only items | 2 (browser chat→form UAT; BL-01 config inspection) |

No fillable Nyquist gaps. Every requirement maps to at least one green automated test (unit, source-scanner, or fixture-driven). The single substantive outstanding verification is the manual browser UAT, which is manual-only by nature (Vaadin/Jmix rendering + provider-backed extraction) and is already tracked in `14-HUMAN-UAT.md` / `14-UAT-CHECKLIST.md`.
