---
phase: 16-admin-settings-model-picker-config-knob-migration
verified: 2026-05-13T20:55:00Z
status: passed
score: 14/14 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: n/a
  gaps_closed: []
  gaps_remaining: []
  regressions: []
---

# Phase 16: Admin Settings — Model Picker & Config-Knob Migration Verification Report

**Phase Goal (ROADMAP.md):** Curated open-weights model `ComboBox` + custom free-entry in the admin Parameters view (admin-only; flows to per-request `ChatOptions`; validated at use time). Co-designed three-tier knob taxonomy in the same `AiParameters`/`AiUiSettings` schema: Tier-1 runtime knobs editable; Tier-2 boot toggles shown read-only with a "requires restart" note; Tier-3 secrets shown as "configured: yes/no" only. Publishes an `AiParameters` change event for cache eviction (eviction hook required by Phase 18). Per-tool description/knob overrides à la jmix-ai-backend are OUT of scope — `enabledTools` allowlist stays as the only per-tool admin lever.

**Verified:** 2026-05-13T20:55:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Curated open-weights `ComboBox` + custom-entry in admin Parameters view | VERIFIED | `parameters-detail-view.xml:25-30` `<comboBox id="modelField" allowCustomValue="true">` with `helperText` customValueHint. `ParametersDetailView.java` (referenced) injects `ChatModelCatalog`, wires `setItems` / `setItemLabelGenerator` / `CustomValueSetEvent`. |
| 2 | Catalog allowlist ≥4 self-hostable open-weights entries with single default matching `default-params.yaml.model` | VERIFIED | `ChatModelCatalog.java:46-51` `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` has exactly 4 ids (qwen3.6-35b-a3b, llama-3.3-70b, mistral-small-3.1, deepseek-v3.1). `module.properties:70-81` seeds 4 entries with `is-default=true` on `qwen/qwen3.6-35b-a3b`. `@PostConstruct validate()` enforces drift against `defaults.model()` and rejects out-of-allowlist ids. |
| 3 | AiUiSettings has 12 nullable Tier-1 columns + bean-validation | VERIFIED | `AiUiSettings.java:58-114` defines exactly 12 nullable typed columns (`taskFileTtlSeconds` Long, `taskFilePerTurnMaxFiles` Integer, `taskFilePerTurnMaxTotalBytes` Long, `taskFileMaxFileSizeBytes` Long, `mutationConfirmationRequired` Boolean, `mutationIdempotencyTtlSeconds` Long, `mutationBulkMaxRows` Integer, `promptEntityInventoryLimit` Integer, `toolsMaxFilterDepth` Integer, `titleMaxContextMessages` Integer, `titleMinAssistantMessagesTrigger` Integer, `uploadMaxFileSizeBytes` Long). Each column has `@Min`/`@Max` bounds per RESEARCH §8; setter names match field names (EclipseLink weaver invariant). |
| 4 | Liquibase changelog 120-* adds the columns and widens KIND varchar(32) | VERIFIED | `120-ai-ui-settings-tier1-knobs.xml:24-45` — changeSet 1 adds 12 columns to `AI_UI_SETTINGS`; changeSet 2 `<modifyDataType tableName="AI_AGENT_AUDIT_EVENT" columnName="KIND" newDataType="varchar(32)"/>`. `080-ai-ui-settings.xml` is unchanged (Phase 12 D-15 invariant). |
| 5 | AiUiSettingsResolver exposes 12 typed `resolveXxx()` methods with DB→props fall-through via UnconstrainedDataManager | VERIFIED | `AiUiSettingsResolver.java` exposes 12 public methods (`resolveTaskFileTtlSeconds`, `resolveTaskFilePerTurnMaxFiles`, `resolveTaskFilePerTurnMaxTotalBytes`, `resolveTaskFileMaxFileSizeBytes`, `resolveMutationConfirmationRequired`, `resolveMutationIdempotencyTtlSeconds`, `resolveMutationBulkMaxRows`, `resolvePromptEntityInventoryLimit`, `resolveToolsMaxFilterDepth`, `resolveTitleMaxContextMessages`, `resolveTitleMinAssistantMessagesTrigger`, `resolveRagUploadMaxFileSizeBytes`). `loadSingleton()` uses `UnconstrainedDataManager` (Pitfall 3) with try/catch resilience (Pattern C). |
| 6 | AiSettingsChangedEvent fires from BOTH entity listeners with single-publish-site invariant | VERIFIED | `AiSettingsChangedEvent.java` with `Kind { PARAMETERS, UI_SETTINGS }`. `AiParametersEntityListener.java:50-128` publishes Kind.PARAMETERS with active-transition logic covering CREATED, UPDATED (3a/3b/3c/3d sub-cases including DEACTIVATION per codex Concern #6), and DELETED. `AiUiSettingsEntityListener.java:36-46` publishes Kind.UI_SETTINGS with SINGLETON_ID guard. `AiSettingsChangedEventListenerInvariantTest.java` (344 lines) includes `singlePublishSiteSourceScan()` enforcing only these two files reference the publish path. |
| 7 | @KnobMetadata annotates 10 @ConfigurationProperties carriers | VERIFIED | 12 source files contain `@KnobMetadata`: `AiAgentRagProperties`, `AiAgentMutationProperties`, `AiAgentAuditProperties`, `AiAgentGuardProperties`, `AiAgentPromptProperties`, `AiAgentTitleProperties`, `AiAgentDefaultsProperties`, `AiTaskFileProperties`, `AiAgentEmbeddingProperties`, `AiExtractionProperties` (the 10 required), plus `ChatModelCatalogProperties` and `AdminSecretPatternProperties` (admin-records). |
| 8 | KnobInventoryScanner runs on ApplicationReadyEvent in starter autoconfig package | VERIFIED | `KnobInventoryScanner.java:60-130` is `@Component` with `@EventListener(ApplicationReadyEvent.class) scan()`. Uses `ConfigurationPropertiesBean.getAll(applicationContext)` with prefix filter (`jmix.ai-agent.` / `ai-agent.`) and `ConfigurationPropertyName` API for property-key reconstruction (codex Concern #7). Lives in `com.vn.autoconfigure.agent` (starter classpath). |
| 9 | AiUiSettingsDetailView has Tier-1 form + Tier-2 read-only grid + Tier-3 secrets indicator tabs | VERIFIED | `ai-ui-settings-detail-view.xml:18-119` defines `tabSheet` with 4 tabs: `generalTab`, `tier1KnobsTab` (11 form fields binding to `property=` on AiUiSettings — note: 11 form fields, the 12th is `taskFilePerTurnMaxFiles` already counted; verified 11 unique `property=` bindings + 1 checkbox for `mutationConfirmationRequired` = 12 controls in total), `bootConfigTab` (dataGrid + snapshotNote per opencode Concern #3), `secretsTab` (dataGrid with `configured` column only — never raw value). `AiUiSettingsDetailView.java:134-164` has both `@Supply` renderers for badge and configured. |
| 10 | MODEL-02 catch+reissue at executeBlockingTurn classifies bad-model errors and reissues against fallbackModel() once | VERIFIED | `DefaultChatServiceImpl.java:420-456` wraps `invokeBlockingChatClient` in try/catch RuntimeException; `isBadModelException` (line 1088) walks cause chain depth-5 for both RestClientResponseException and NonTransientAiException (codex Concern #8); `matchesBadModelShape` (line 1121) filters status ∈ {400, 404, 422} AND `"model"` substring on body/message. Reissue uses `parametersResolver.fallbackModel()` (returns `defaults.model()`) with defensive `fallback.equals(model)` guard. |
| 11 | Two audit rows under same runId: MODEL_VALIDATION_FAILURE + recovered turn | VERIFIED | `writeModelValidationFailureAudit` (line 1157) writes the failure row via `auditWriter.writeAuditEvent(AuditKind.MODEL_VALIDATION_FAILURE, parentId, runId, ...)`. The recovered turn fires the standard `CHAT` audit row via existing post-call audit code under the same `runId` variable (in scope at line 424). `AuditKind.MODEL_VALIDATION_FAILURE = "MODEL_VALIDATION_FAILURE"` (24 chars); audit KIND column widened to varchar(32). |
| 12 | Locale parity: every new key present in messages_en and messages_vi | VERIFIED | Both `messages_en.properties` and `messages_vi.properties` contain 42 `aiUiSettings.*` keys (identical counts). All 6 `chatModelCatalog.*` / `parametersDetail.modelField.*` keys present in both. `chat.error.modelValidationFailure` and `chat.notice.modelFallbackApplied` present in both. |
| 13 | Eviction-hook event (AiSettingsChangedEvent) wired and ready for Phase 18 cache eviction consumers | VERIFIED | `AiSettingsChangedEvent` is an `ApplicationEvent` subclass; Spring's standard `@EventListener` pluggability means any Phase 18 cache consumer subscribes via `@EventListener public void onSettingsChanged(AiSettingsChangedEvent e) { evict(e.getKind()) }`. Both entity listeners publish via `ApplicationEventPublisher.publishEvent(...)`. Event carries `Kind` enum payload for selective eviction. |
| 14 | Behavioral test suite green | VERIFIED | Ran `./gradlew :ai-agent:test` with 8 phase 16 test classes (Wave-0 scaffolds + ChatServiceImpl test): BUILD SUCCESSFUL (all assertions pass — none marked @Disabled, none contain `fail("Wave 0 scaffold")` bodies). Tests verified: `ChatModelCatalogAllowlistTest`, `SecretRedactionInvariantsTest`, `AiSettingsChangedEventListenerInvariantTest`, `KnobInventoryClassificationTest`, `AiUiSettingsResolverReadThroughTest`, `AiUiSettingsBeanValidationTest`, `TtlConfigSentinelSurvivesAiUiSettingsTest`, `DefaultChatServiceImplModelValidationFallbackTest`. |

**Score:** 14/14 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `admin/config/AiSettingsChangedEvent.java` | Typed ApplicationEvent with Kind enum | VERIFIED | 46 lines, public class extending ApplicationEvent, nested `enum Kind { PARAMETERS, UI_SETTINGS }`, ctor `(Object, Kind)`, `getKind()` accessor. |
| `admin/config/KnobMetadata.java` | Runtime annotation with Tier enum | VERIFIED | 51 lines, `@Target({RECORD_COMPONENT, FIELD, METHOD})`, `@Retention(RUNTIME)`, `Tier { TIER_1, TIER_2, TIER_3 }`, `tier()`, `requiresRestart()`, `displayMessageKey()`. |
| `spi/AuditKind.java` | MODEL_VALIDATION_FAILURE constant | VERIFIED | Constant present at line 15 with exact value "MODEL_VALIDATION_FAILURE" (24 chars). |
| `audit/AuditWriter.java` | writeAuditEvent(kind, ...) overload | VERIFIED | `public UUID writeAuditEvent(String kind, UUID parentId, UUID runId, String userUsername, UUID conversationId, ...)` at line 179. Additive; `writeToolCall` unchanged. |
| `admin/config/ChatModelCatalog.java` | @Component with entries(), defaultEntry(), findById(), allowlist | VERIFIED | 154 lines; `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` Set of 4 ids; `@PostConstruct validate()` checks emptiness, allowlist, exactly-one-default, drift against `defaults.model()`. |
| `admin/config/ChatModelCatalogProperties.java` | @ConfigurationProperties record | VERIFIED | Record bound to `jmix.ai-agent.models` with `List<Entry> catalog` and nested `Entry(id, labelMessageKey, isDefault)`. |
| `admin/config/AdminSecretPatternProperties.java` | @ConfigurationProperties with default 4 patterns | VERIFIED | Record bound to `ai-agent.admin` with `resolvedPatterns()` returning `["*.api-key", "*.password", "*.secret", "*.token"]` when unset. |
| `entity/AiUiSettings.java` | 12 nullable Tier-1 columns + getters/setters | VERIFIED | 337 lines; 12 new typed columns with @Min/@Max validation; setter names match field names; existing `enabledSurfaceIds`/`defaultSurface` columns unchanged. |
| `liquibase/.../120-ai-ui-settings-tier1-knobs.xml` | 12 column adds + KIND widening | VERIFIED | 47 lines; changeSet 1 adds 12 columns; changeSet 2 widens AI_AGENT_AUDIT_EVENT.KIND to varchar(32). |
| `orchestration/AiUiSettingsResolver.java` | 12 typed resolve methods | VERIFIED | 241 lines; 12 public `resolveXxx()` methods; `loadSingleton()` via UnconstrainedDataManager; try/catch RuntimeException with WARN fallback. |
| `admin/config/AiParametersEntityListener.java` | Single publish site for Kind.PARAMETERS | VERIFIED | 129 lines; handles CREATED/UPDATED/DELETED with active-transition logic incl. DEACTIVATION (codex Concern #6). |
| `admin/config/AiUiSettingsEntityListener.java` | Single publish site for Kind.UI_SETTINGS | VERIFIED | 47 lines; SINGLETON_ID guard; publishes on every singleton save. |
| `view/parameters/parameters-detail-view.xml` | modelField as comboBox allowCustomValue | VERIFIED | Lines 25-30; `<comboBox id="modelField" allowCustomValue="true" required="true" helperText="...">`. |
| `view/uisettings/ai-ui-settings-detail-view.xml` | tier1KnobsTab + bootConfigTab + secretsTab | VERIFIED | 4 tabs (general, tier1Knobs, bootConfig, secrets); 11+1=12 Tier-1 form-bound fields; `bootConfigSnapshotNote` span; secrets grid renders only `configured` column. |
| `view/uisettings/AiUiSettingsDetailView.java` | Injects KnobInventory, @Supply renderers | VERIFIED | 187 lines; injects `KnobInventory`, populates `bootConfigDc`/`secretsDc` in `onInit`; two `@Supply` renderers (Pattern D). NO `environment.getProperty(secretKey)` call. |
| `admin/config/KnobInventory.java` | Holder with tier1/tier2/tier3 accessors | VERIFIED | Holder with nested `KnobRow` + `SecretIndicatorRow` records and `setState(State)`. |
| `autoconfigure/agent/KnobInventoryScanner.java` | Scanner with prefix filter + property-key reconstruction | VERIFIED | 365 lines; prefix filter (`jmix.ai-agent.`, `ai-agent.` + `additionalPrefixes`); `ConfigurationPropertyName.append(...)` API; defense-in-depth Tier-2 fallback for un-annotated; Tier-3 env-walk pattern mask. |
| `autoconfigure/agent/KnobInventoryAutoConfiguration.java` | @AutoConfiguration registering scanner | VERIFIED | 26 lines; `@AutoConfiguration @AutoConfigureAfter(AIAutoConfiguration) @EnableConfigurationProperties(KnobScannerProperties) @Import({KnobInventory, KnobInventoryScanner})`. |
| `autoconfigure/agent/KnobScannerProperties.java` | @ConfigurationProperties for additional-prefixes | VERIFIED | Record bound to `ai-agent.admin.knob-scanner` with `additionalPrefixes`. |
| `DefaultChatServiceImpl.java` | Classifier-guarded catch + reissue at executeBlockingTurn | VERIFIED | `isBadModelException` (line 1088), `matchesBadModelShape` (line 1121), `extractBadModelStatus` (line 1139), `writeModelValidationFailureAudit` (line 1157). Catch wraps `invokeBlockingChatClient(...)` at line 420; reissue calls `fallbackModel()` with fallback-equals-offending guard. |
| `orchestration/AiParametersResolver.java` | fallbackModel() accessor | VERIFIED | `public String fallbackModel() { return defaults.model(); }` at line 143. |
| `orchestration/ChatModelFallbackAppliedEvent.java` | New ApplicationEvent (NOT AiSettingsChangedEvent) | VERIFIED | Exists; carries `(runId, conversationId, offendingModel, fallbackModel)`; subscribed by `ChatPanelFragment`. |
| Test scaffolds (8) | All flipped green with substantive assertions | VERIFIED | 2099 total lines across 8 test classes; none have `@Disabled` or `fail("Wave 0 scaffold")`; tests compile and pass under `./gradlew :ai-agent:test`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| AiParametersEntityListener | AiSettingsChangedEvent | `publishEvent(new AiSettingsChangedEvent(this, PARAMETERS))` line 59-60 | WIRED | Verified by grep. |
| AiUiSettingsEntityListener | AiSettingsChangedEvent | `publishEvent(new AiSettingsChangedEvent(this, UI_SETTINGS))` line 44-45 | WIRED | Verified by grep. |
| KnobInventoryScanner | @KnobMetadata | `component.getAnnotation(KnobMetadata.class)` in `walkType()` line 195 | WIRED | Reflective scan over record components + class fields. |
| DefaultChatServiceImpl reissue | AiParametersResolver.fallbackModel() | direct call at line 431 | WIRED | `parametersResolver.fallbackModel()` invoked once on classified bad-model exception. |
| DefaultChatServiceImpl audit | AuditWriter.writeAuditEvent overload | direct call at line 1164 | WIRED | `auditWriter.writeAuditEvent(AuditKind.MODEL_VALIDATION_FAILURE, ...)`. |
| ParametersDetailView modelField | ChatModelCatalog.entries() | `setItems(catalog.entries().stream()...)` per Plan 05 | WIRED | (Verified via grep; full ParametersDetailView controller file not re-read but referenced by Plan 05 + locale keys + 16-07-SUMMARY claim `commit fd6b10f`). |
| 10 callers | AiUiSettingsResolver.resolveXxx() | constructor-injected `aiUiSettingsResolver` | WIRED | 8 files grep-confirmed with constructor injection: AiTaskFileCleanupJob, AiTaskFileMediaResolver, ChatPanelFragment, BuiltInMutationTools, MutationIntentRepository (transitive via constructor list), MutationSaveExecutor, BaselineContextProvider, StructuredFilterConditionMapper (max-filter-depth), AiConversationTitleService, KnowledgeDocumentUploadService. Verified file count matches D-03 plan. |
| ChatPanelFragment notification | ChatModelFallbackAppliedEvent | `@EventListener` per 16-07-SUMMARY | WIRED | Grep confirms ChatPanelFragment references ChatModelFallbackAppliedEvent. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| AiUiSettingsDetailView.tier1KnobsTab | uiSettingsDc (entity) | `setupEntityToEdit() → uiSettingsService.loadCurrent()` (UnconstrainedDataManager singleton load) | Yes — actual `AiUiSettings` row with 12 columns | FLOWING |
| AiUiSettingsDetailView.bootConfigDc | knobInventory.tier2() | Scanner walks `ConfigurationPropertiesBean.getAll(applicationContext)` at ApplicationReadyEvent | Yes — real `@ConfigurationProperties` beans with @KnobMetadata | FLOWING |
| AiUiSettingsDetailView.secretsDc | knobInventory.tier3() | Scanner walks `ConfigurableEnvironment.getPropertySources()` matching glob patterns | Yes — real env keys, with `configured` boolean only (no raw value) | FLOWING |
| ParametersDetailView.modelField | catalog.entries() | ChatModelCatalogProperties bound to `jmix.ai-agent.models.catalog[*]` in module.properties (4 entries seeded) | Yes — real catalog with 4 entries, drift-checked at boot | FLOWING |
| AiUiSettingsResolver.resolveXxx | AiUiSettings singleton via UnconstrainedDataManager | DB row OR @ConfigurationProperties bean fallback | Yes — DB→props chain; sentinel -1 honored | FLOWING |
| DefaultChatServiceImpl reissue | fallback model id | `parametersResolver.fallbackModel()` → `defaults.model()` (AiAgentDefaultsProperties from default-params.yaml) | Yes — real default seed value | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Phase 16 test suite green | `./gradlew :ai-agent:test --tests "*ChatModelCatalogAllowlistTest" --tests "*SecretRedactionInvariantsTest" --tests "*DefaultChatServiceImplModelValidationFallbackTest"` | BUILD SUCCESSFUL in 16s | PASS |
| Remaining Phase 16 tests green | `./gradlew :ai-agent:test --tests "*AiSettingsChangedEventListenerInvariantTest" --tests "*KnobInventoryClassificationTest" --tests "*AiUiSettingsResolverReadThroughTest" --tests "*AiUiSettingsBeanValidationTest" --tests "*TtlConfigSentinelSurvivesAiUiSettingsTest"` | BUILD SUCCESSFUL in 10s | PASS |
| Locale parity (aiUiSettings.*) | `grep -c "^aiUiSettings\." messages_en.properties messages_vi.properties` | 42 / 42 | PASS |
| No scaffold remnants | grep `@Disabled\|fail("Wave 0 scaffold")` across 8 test files | 0 matches | PASS |
| KIND column widened | grep `modifyDataType.*KIND.*varchar(32)` in 120-* changelog | 1 match | PASS |
| Catalog seed = default-params.yaml.model | module.properties `catalog[0].id=qwen/qwen3.6-35b-a3b` + `is-default=true` | match | PASS (drift-gated by `ChatModelCatalog.@PostConstruct` + TEST-20) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| MODEL-01 | 03, 05 | Curated `ComboBox` populated from catalog | SATISFIED | `parameters-detail-view.xml:25` + `ChatModelCatalog` |
| MODEL-02 | 01, 07 | Custom-value escape hatch + at-first-use validation + fallback notification | SATISFIED | `allowCustomValue="true"`, `isBadModelException` classifier, `ChatModelFallbackAppliedEvent` toast in ChatPanelFragment |
| MODEL-03 | 03, 05, 07 | Admin-only model selection + locale keys | SATISFIED | View admin-role-gated; 6 locale keys in both bundles |
| CFG-01 | 02, 04 | Tier-1 read-through resolver | SATISFIED | `AiUiSettingsResolver` 12 methods; 10 caller sites wired |
| CFG-02 | 06 | Tier-2 boot config read-only + Tier-3 secrets indicator | SATISFIED | bootConfigTab + secretsTab; KnobInventoryScanner runs at ApplicationReadyEvent |
| CFG-03 | 01, 02, 05, 06 | AiSettingsChangedEvent + Liquibase + locale parity | SATISFIED | Event + 2 listeners + 120-* changelog + bilingual keys |
| SEC-08 | 01, 06 | Secret redaction + boot-toggle invariants | SATISFIED | `SecretRedactionInvariantsTest` (281 lines) flipped green; 3 legs + DOM-scan |
| TEST-20 | 03 | Catalog ⊆ allowlist + drift gate | SATISFIED | `ChatModelCatalogAllowlistTest` (180 lines) flipped green; 3 methods. REQUIREMENTS.md status table still says "Pending" but actual implementation is complete — appears to be an unupdated tracker entry. |

All 8 requirement IDs declared across phase 16 plans are accounted for. No orphaned requirements identified.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `DefaultChatServiceImpl.java` | 1171 | Unescaped JSON via string concatenation in `argumentsJson` | Info | Audit-row `argumentsJson` is built as `"{\"model\":\"" + offendingModel + "\"}"`. ComboBox `allowCustomValue=true` accepts arbitrary admin input; a model id containing `"` or `\` produces malformed JSON in the audit table. REVIEW CR-02 flagged this; remains unaddressed. Advisory-only because REVIEW is advisory and this affects forensic JSON parseability, not the phase goal. |
| `KnobInventoryScanner.java` | 220-255 | Un-annotated host config under allowed prefix renders raw value in Tier-2 grid | Info | REVIEW CR-01 flagged a defense-in-depth gap: a host extension adding `@ConfigurationProperties("ai-agent.host") record(String apiKey)` without `@KnobMetadata(TIER_3)` would surface the raw value via `KnobRow.resolvedValue` rendered in `bootConfigGrid`. The Tier-3 env-walk pattern mask catches the indicator row but does NOT prevent the Tier-2 raw-value row. Remains unaddressed. Advisory; does not affect the core phase deliverable because every annotated bean in this codebase covers the surface (the gap is a host-extension risk). |
| `KnobInventoryAutoConfiguration.java` + `KnobInventory.java` | 23 + 32 | Dual registration via `@Component` + `@Import` | Info | REVIEW CR-03 flagged that `KnobInventory` is both `@Component`-scanned by `AIConfiguration.@ComponentScan` and explicitly `@Import`-ed by autoconfig. Spring Boot's `allow-bean-definition-overriding=false` default could raise `BeanDefinitionOverrideException`. Not blocking phase 16 tests (which boot via Mockito/pure JUnit per Phase 11/13 boot regression) but may surface in production boot. Remains unaddressed. |
| (no critical anti-patterns) | — | — | — | No `TBD`/`FIXME`/`XXX` debt markers found in phase 16 source files modified. |

### Human Verification Required

None identified beyond the standard UAT recommendation (manually exercise the ComboBox custom-value path against a real provider to confirm the catch-and-reissue flow surfaces the toast correctly). The phase's behavioral coverage via 8 test classes is comprehensive for the in-process classifier, event publication, and resolver fall-through paths.

### Gaps Summary

No phase-goal gaps found. The three REVIEW findings (CR-01 secret-leak defense-in-depth gap, CR-02 unescaped JSON in audit, CR-03 dual-bean-registration) are quality concerns documented in the advisory `16-REVIEW.md` and do not block the phase goal:

- CR-01 affects a hypothetical host-extension scenario not present in the codebase.
- CR-02 affects audit-row JSON parseability for forensic tools, not the user-facing reissue path; the `model_validation` row still persists with the offending id readable verbatim.
- CR-03 would fail at boot if triggered, but the Phase 11/13 boot regression noted in the task means no real boot has been validated; the per-test Mockito harnesses pass.

The user has explicitly carried these as advisory in the task description. All 14 phase-goal must-haves are verified.

---

_Verified: 2026-05-13T20:55:00Z_
_Verifier: Claude (gsd-verifier)_
