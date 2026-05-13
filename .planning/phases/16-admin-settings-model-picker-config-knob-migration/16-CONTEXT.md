# Phase 16: Admin Settings — Model Picker & Config-Knob Migration - Context

**Gathered:** 2026-05-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Admin UI changes that (a) replace the free-text `modelField` in `parameters-detail-view.xml` with a curated open-weights `ComboBox` + custom-entry escape hatch + at-first-use validation with one-shot fallback to `default-params.yaml` model; (b) expand the `AiUiSettings` singleton with ~10 flat nullable columns persisting Tier-1 operator-runtime knobs across 4 clusters (task-file, mutation runtime, prompt/tools shaping, title+upload) read fresh per turn via a new sibling `AiUiSettingsResolver`; (c) add a "Boot Config (read-only)" tab in `AiUiSettingsDetailView` listing every Tier-2 knob (`@ConditionalOnProperty` toggles + executor sizing + retry knobs + audit/output-scanner knobs) with `requires restart` badges and a "Secrets" section showing `configured: yes/no` for Tier-3 pattern-matched keys; (d) publish exactly one `AiSettingsChangedEvent` per save from one entity-listener per entity (`AiParametersEntityListener`, `AiUiSettingsEntityListener`) so Phase 18 caches can evict. **Strictly no per-tool description/knob override surface.** `enabledTools` allowlist stays as the only per-tool admin lever.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**5 requirements are locked.** See `16-SPEC.md` for full requirements, boundaries, and acceptance criteria — Model picker (curated `ComboBox` + custom-entry + at-first-use validation), Curated catalog + TEST-20 allowlist, Tier-1 knob migration (4 clusters), Tier-2 read-only + Tier-3 secret indicator + SEC-08, Unified `AiSettingsChangedEvent` + Liquibase + locale parity.

Downstream agents MUST read `16-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- Curated `ComboBox` + custom-entry escape hatch for `modelField` in `parameters-detail-view.xml`.
- `ChatModelCatalog` `@Component` + `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` constant + TEST-20.
- At-first-use model validation: fail turn + localised notification + one-shot fallback to `default-params.yaml` model.
- Migration of Tier-1 knobs across 4 clusters: RAG per-profile in `AiParameters.bodyYaml`; task-file/mutation runtime/prompt+tools shaping/title/upload in expanded `AiUiSettings`.
- `AiParametersResolver`-style read-through: DB → `module.properties` → strict `default-params.yaml` seed.
- New `AiUiSettings` "Boot Config (read-only)" tab + "Secrets" indicator section.
- SEC-08 (`SecretRedactionInvariantsTest`) two-legged source-scan test.
- `AiSettingsChangedEvent` + 2 entity listeners (single publisher per entity).
- Liquibase changelog adding new `AiUiSettings` columns (separate file, included in `agentstore-changelog.xml`).
- Locale parity in `messages_en.properties` + `messages_vi.properties`.

**Out of scope (from SPEC.md):**
- Per-tool description/knob overrides à la jmix-ai-backend.
- STT knobs (`ai-agent.stt.*`) — owned by Phase 19.
- Per-conversation end-user model switching.
- Performance memoization of settings reads — Phase 18 owns.
- Editing/displaying `spring.ai.retry.*` / `rag.splitter.*` / `rag.ingest-executor.*` / `conversation-title.executor.*` as Tier-1 (stay Tier-2 read-only).
- Custom catalog persistence in the database.
- Mutating prior Liquibase changelogs (`080-ai-ui-settings.xml` frozen).

</spec_lock>

<decisions>
## Implementation Decisions

### AiUiSettings schema shape
- **D-01: Flat JPA columns** — ~10 new nullable columns on the `AiUiSettings` singleton, one per Tier-1 knob, each carrying Jakarta bean-validation annotations directly. Mirrors the existing `enabledSurfaceIds` / `defaultSurface` precedent on the same entity.
  - **Column naming:** `TASK_FILE_TTL_SECONDS` (Long, nullable, sentinel `-1` preserved per Phase 13.1), `TASK_FILE_PER_TURN_MAX_FILES` (Integer, nullable, sentinel `-1`), `TASK_FILE_PER_TURN_MAX_TOTAL_BYTES` (Long, nullable, sentinel `-1`), `MUTATION_CONFIRMATION_REQUIRED` (Boolean, nullable), `MUTATION_IDEMPOTENCY_TTL_SECONDS` (Long, nullable — stored as seconds to avoid JPA `Duration` mapping fuss), `MUTATION_BULK_MAX_ROWS` (Integer, nullable), `PROMPT_ENTITY_INVENTORY_LIMIT` (Integer, nullable), `TOOLS_MAX_FILTER_DEPTH` (Integer, nullable), `TITLE_MAX_CONTEXT_MESSAGES` (Integer, nullable), `TITLE_MIN_ASSISTANT_MESSAGES_TRIGGER` (Integer, nullable), `UPLOAD_MAX_FILE_SIZE_BYTES` (Long, nullable). Final list is the planner's call but every column must be NULLABLE (null = fall through to `module.properties` default).
  - **Why flat over blob:** XML descriptor `<integerField property="...">` binding in `AiUiSettingsDetailView` (already `EditedEntityContainer`-based) works zero-glue on real JPA columns; a blob would force `@Transient` getter bridges + the EclipseLink-weaver setter-name trap that this very entity already documents in the `setEnabledSurfaceSet` comment block; Plan 10-06 R2's entity-listener diff for `AiSettingsChangedEvent` is dramatically simpler over typed columns than over a parsed YAML blob; the `AiParameters.bodyYaml` precedent exists for a different shape (versioned externally-authored profile body with non-form read path) — `AiUiSettings` has the opposite shape (operator-edited form fields read fresh per turn).
  - **Why not JSON sidecar / entity split:** No concrete host-extension consumer is named yet (`feedback_pragmatic_modules`); the entity split would force two singletons + two services + two views + two publish sites (contradicting Plan 10-06 R2). Defer both until a real consumer justifies them.

### Knob inventory source-of-truth (Tier-2 + Tier-3 surfaces)
- **D-02: Three-layer mechanism** — `@KnobMetadata` annotation as primary source-of-truth + Actuator `ConfigurationPropertiesBean.getAll(applicationContext)` as fallback discovery + Tier-3 secret-pattern mask as defense-in-depth safety net.
  - **`@KnobMetadata` (primary):** new annotation in `com.vn.agent.admin.config` (planner's package decision) carrying `tier = TIER_1 | TIER_2 | TIER_3`, `requiresRestart` (only meaningful when `TIER_2`), `displayMessageKey` (root message-bundle key resolved by `io.jmix.core.Messages`). Annotate every component of every existing `@ConfigurationProperties` record in this phase (decided in discussion follow-up — "annotate all existing 7 records"): `AiAgentRagProperties`, `AiAgentMutationProperties`, `AiAgentAuditProperties`, `AiAgentGuardProperties`, `AiAgentPromptProperties`, `AiAgentTitleProperties`, `AiAgentDefaultsProperties` — additive only (annotation lines, no record shape changes). Plus `AiAgentTaskFileProperties` if present; planner to scout.
  - **Boot-time scanner (`KnobInventoryScanner` `@Component`):** at `ApplicationReadyEvent`, walk `ConfigurationPropertiesBean.getAll(applicationContext)`, reflect each record's components for `@KnobMetadata`. Output: an immutable `KnobInventory` `@Component` exposing `tier2()` (annotated + un-annotated-bean-discovered records) and `tier3()` (pattern-matched final-projection). Records with no `@KnobMetadata` are still surfaced as Tier-2 read-only (default classification) so host apps adding their own `@ConfigurationProperties` records get free Tier-2 display.
  - **Tier-3 secret pattern (defense-in-depth):** configurable `ai-agent.admin.secret-property-patterns` (`@ConfigurationProperties`, default `["*.api-key","*.password","*.secret","*.token"]`). Matched at render time across ALL keys (annotated and un-annotated) — a host forgetting to mark a new `*.api-key` field as `TIER_3` still gets masked.
  - **SEC-08 test:** reflective gate that asserts every `@KnobMetadata(tier=TIER_3)` field is masked in render AND every Tier-3 pattern match is masked AND no `@ConditionalOnProperty` toggle key (cross-scanned via classpath annotation grep on consumer beans) is bound to a Vaadin editable component (HasValue subclasses excluding read-only Span / `setReadOnly(true)` containers).
  - **Why this 3-layer:** annotation gives type-safe co-located SoT for own records; Actuator-pattern fallback (`ConfigurationPropertiesBean.getAll`) gives host-extensibility w/o coordination; secret-pattern mask is the defense-in-depth that catches drift. Plan-time scout will confirm Spring Boot version is high enough to expose `ConfigurationPropertiesBean.getAll` cleanly without pulling full Actuator dep.

### Resolver layering
- **D-03: Sibling `AiUiSettingsResolver` `@Component`** — mirrors `AiParametersResolver` shape; loads `AiUiSettings.SINGLETON_ID` via `UnconstrainedDataManager` (Phase 12 D-15 pattern); exposes typed `resolveTaskFileTtl()`, `resolveTaskFilePerTurnMaxFiles()`, `resolveMutationConfirmationRequired()`, `resolveMutationIdempotencyTtl()`, `resolveMutationBulkMaxRows()`, `resolvePromptEntityInventoryLimit()`, `resolveToolsMaxFilterDepth()`, `resolveTitleMaxContextMessages()`, `resolveTitleMinAssistantMessagesTrigger()`, `resolveUploadMaxFileSizeBytes()`. Each method returns `DB value (if non-null) → @ConfigurationProperties resolved value → constant default`.
  - **NOT extending `AiParametersResolver`:** preserves "additive only" (every Phase 1-15 caller of `AiParametersResolver` stays untouched), keeps class names honest (profile-body vs operator-singleton), and gives Phase 18 exactly two memoization surfaces — one per resolver — each with its own `@EventListener(AiSettingsChangedEvent)` slot. The 1-2 callers that need both (prompt building reads `systemPrompt` from profile + `entityInventoryLimit` from singleton; possibly upload service) inject both — feature, not bug, keeps ownership legible at call site.
  - **Caller injection plan (planner detail):** `AiTaskFileCleanupJob`, `AiTaskFileMediaResolver`, `ChatPanelFragment` (upload limits) inject `AiUiSettingsResolver`. `BuiltInMutationTools`, `MutationIntentRepository`, `MutationSaveExecutor` (bulk-max-rows) inject `AiUiSettingsResolver`. `BaselineContextProvider` inject `AiUiSettingsResolver` (entity-inventory.limit). `ToolEntityResolver` / `BuiltInDataTools` (max-filter-depth) inject `AiUiSettingsResolver`. `AiConversationTitleService` inject `AiUiSettingsResolver`. `KnowledgeDocumentUploadService` (rag.upload.max-file-size-bytes) inject `AiUiSettingsResolver`.
  - **NOT merge / per-cluster / record-merge:** merge into `AiSettingsResolver` violates additive-only (15+ files churn, deprecation shim); per-cluster (5 resolvers) fails `feedback_pragmatic_modules` + `feedback_spi_baseline_builtin` (no concrete consumer); `merge()` on `@ConfigurationProperties` records conflates immutable defaults with runtime resolution and kills the Phase 18 cache-eviction surface invariant.

### `AiSettingsChangedEvent` publish path
- **D-04: One typed event, two entity listeners.** `AiSettingsChangedEvent extends ApplicationEvent` carries `kind ∈ {PARAMETERS, UI_SETTINGS}`. `AiParametersEntityListener` (`@EntityChangedEvent`-style or `@OnSave`/`@OnPersist` JPA listener — Jmix preferred pattern) publishes `AiSettingsChangedEvent(kind=PARAMETERS)` IFF the saved row has `active=true`; inactive-profile saves publish ZERO events. `AiUiSettingsEntityListener` publishes `AiSettingsChangedEvent(kind=UI_SETTINGS)` on every save of the singleton (id check guards against unexpected non-singleton rows).
  - **Why this shape:** mirrors `AiExposureRuleEntityListener` precedent for `LlmExposureChangedEvent` (Plan 10-06 R2 invariant) — one publish site per entity, NO `ApplicationEventPublisher` injected in views or services. A source-scan test asserts this.
  - **Why not one listener / one event-source-discrimination-via-payload:** entity listener already has the entity instance — the event listener side gets a typed `kind` to scope eviction; payload-on-event keeps Phase 18 cache evict logic clean (cache for settings A vs settings B can subscribe selectively).

### Model picker validation + fallback
- **D-05: Catch + reissue inside `executeBlockingTurn(...)` with typed audit kind.** Wrap the `ChatClient.call(...)` / streaming-fallback invocation in `DefaultChatServiceImpl.executeBlockingTurn`; on classified bad-model exception (`RestClientResponseException` 4xx with model-not-found / invalid-model body markers + `org.springframework.ai.retry.NonTransientAiException` with model markers), do exactly one reissue against the resolved `default-params.yaml` model (planner reads the seed via `AiParametersResolver` default-fallback path, NOT a hardcoded constant — the seed evolves over phases). The saved `AiParameters.model` value is NEVER mutated by the reissue (the swap is local to the reissue's `ChatOptions`).
  - **Audit:** new `AuditKind.MODEL_VALIDATION_FAILURE` enum value (one new constant — does not break existing audit consumers per Phase 11 D-04 closed-taxonomy invariant; that invariant is about the 6-code error-translator taxonomy, not the broader audit-kind set). Emit two rows per failed-then-recovered turn: (1) `MODEL_VALIDATION_FAILURE` with offending model id + provider error fingerprint (status code, sanitised error class name — NOT raw body to honor `feedback_rich_tool_descriptions` style + `MutationErrorTranslator` P-22 sanitisation precedent); (2) the successful fallback turn under the existing `RUN_TURN` kind. Both rows share the same `runId` correlation key so the UI can surface "fell back to default model" inline.
  - **Why this over advisor pattern:** `executeBlockingTurn` is the existing Phase 13.1 BLK-01 chokepoint — wrapping the call site there preserves BLK-01 by construction (no separate path through advisor that could bypass `executeBlockingTurn`). Spring AI 1.1.x advisor error semantics on streaming are still uneven (issue #2877). Defer to a `ModelFallbackAdvisor` when a second operational-fallback case (quota, content filter, regional failover) lands.
  - **Streaming path:** the `BLK-01` invariant says streaming-fallback double-write was eliminated by sending streaming-failure paths through `executeBlockingTurn` — model-validation failure on the streaming path falls into the same chokepoint, so the catch is at the same site. Planner verifies: if the streaming transport throws synchronously at `ChatClient.stream(...)` connection time (provider rejects model BEFORE the stream starts), the existing `catch(UnsupportedOperationException)` graceful-fallback to blocking path triggers; the blocking `executeBlockingTurn` re-runs there, picks up the same bad model, and the catch fires. Confirm during planning.

### Curated model catalog binding
- **D-06: `@ConfigurationProperties` record `ChatModelCatalogProperties` bound to `jmix.ai-agent.models.catalog[*]`** with components `(id, labelMessageKey, isDefault)`. `ChatModelCatalog` `@Component` validates at boot: exactly one entry has `isDefault=true` (`@PostConstruct` assertion); throws on misconfiguration so a misseed never reaches the UI. `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` is a `Set<String>` Java constant in `ChatModelCatalog`. TEST-20 (`ChatModelCatalogAllowlistTest`) asserts catalog ⊆ allowlist + exactly-one-default. A second drift test asserts the catalog's default entry equals `default-params.yaml.model` (read via `AiParametersResolver.defaultBody().model()`).
  - **Default catalog seed (planner refines):** at minimum `qwen/qwen3.6-35b-a3b` (current default, marked) + a 2-3 entry curated set the planner researches from current open-weights provider availability at plan time (memory `project_self_hostable_models_only`). Exact entry list is the planner's call but the marked-default MUST equal `default-params.yaml.model` at ship time.
  - **ComboBox rendering:** XML `<comboBox id="modelField" allowCustomValue="true">` with controller-side `setItems(catalog.entries())` + `setItemLabelGenerator(...)` resolving `labelMessageKey` via `io.jmix.core.Messages` (root bundle keys per `feedback_jmix_messages_over_spring`). Default-marked entry rendered with a suffix `(default)` from a locale key — keep CSS-only marking out of scope (a screen reader announcing "default" matters more than a colour). Custom-entry accepted via `allowCustomValue` — controller's `addValueChangeListener` writes the raw string back to `AiParameters.bodyYaml.model`.

### Tier-2 / Tier-3 view rendering
- **D-07: New `<tab>` inside `AiUiSettingsDetailView`'s existing tabSheet** — `<tab id="bootConfigTab" label="msg:///aiUiSettings.tab.bootConfig">`. Inside: a `<dataGrid>` over an in-memory `Container<KnobView>` populated at view-init from `KnobInventory.tier2()`. Columns: `displayKey | resolvedValue | badge`. Badge rendered via `@Supply(to="grid.bootConfigBadge", subject="renderer")` per `feedback_jmix_datagrid_renderer` (NEVER programmatic `getColumnByKey().setRenderer()`). All values rendered into Vaadin `Span` (not editable HasValue) so SEC-08's "editable" check structurally cannot fire on these rows.
  - **Tier-3 "Secrets" section:** a sibling `<vbox>` under the boot config tab (or its own tab — planner's call) containing a Jmix `<dataGrid>` of `(secretKey, configuredYesNo)` rows. The "configured yes" computation: `Environment.getProperty(key)` non-blank AND non-empty-after-strip; never call `.toString()` on the actual value anywhere in the controller code path (a source-scan test asserts this).
  - **Admin role gating:** `AiAgentAdminRole` gets `@EntityAttributePolicy` MODIFY=DENY on every new Tier-1 field for non-admins (so the form auto-disables); the bootConfig + secrets tab is `<tab>` with admin-role-only menu policy enforcement at the view level.

### Liquibase
- **D-08: New changelog file `120-ai-ui-settings-tier1-knobs.xml`** under `src/main/resources/com/vn/agent/liquibase/agentstore-changelog/`. Adds `addColumn` operations for the ~10 new columns (defaults `null`). Include the file via the existing `agentstore-changelog.xml`'s `includeAll` discovery (Phase 12 D-15 — no edit to the parent file). `080-ai-ui-settings.xml` is byte-identical post-phase (Liquibase change-identity preservation invariant).

### Locale parity
- **D-09: All new msg keys go in BOTH `messages_en.properties` AND `messages_vi.properties` root bundle** per `feedback_jmix_messages_over_spring` (per-view bundles trip IntelliJ stale-index). New key groups:
  - `aiUiSettings.section.*` (form section labels for the new Tier-1 form fields)
  - `aiUiSettings.field.*` (one per new Tier-1 knob)
  - `aiUiSettings.validation.*` (bean-validation message bundles)
  - `aiUiSettings.tab.bootConfig` + `aiUiSettings.tab.secrets`
  - `aiUiSettings.bootConfig.column.*` (key, value, badge)
  - `aiUiSettings.bootConfig.badge.requiresRestart`
  - `aiUiSettings.secrets.column.*`
  - `aiUiSettings.secrets.indicator.{yes,no}`
  - `parametersDetail.modelField.customValueHint`
  - `parametersDetail.modelField.defaultSuffix` (the "(default)" suffix)
  - `chat.error.modelValidationFailure` (operator-visible notification when fallback fires)
  - `chat.notice.modelFallbackApplied` (chat-row notice when reissue succeeds)

### Claude's Discretion
- Exact bean-validation bounds per knob (e.g. `topK ∈ [1, 50]`, `bulkMaxRows ∈ [1, 500]`, `ttl-seconds ∈ {-1} ∪ [60, 604_800]`); SPEC.md notes these are planner's call. Confirm sentinel `-1` semantics for task-file knobs match Phase 13.1 `TtlConfigSentinelSkipsCleanupTest` expectations.
- Exact entry list for the curated model catalog default seed — `qwen/qwen3.6-35b-a3b` marked default; planner researches 2-3 additional open-weights options at plan time.
- Exact set of existing `@ConfigurationProperties` records that get `@KnobMetadata` annotations in this phase — the user agreed to "all existing 7"; planner confirms the record inventory during scout (may be 7 or 8 depending on whether `AiAgentTaskFileProperties` exists separately).
- Package locations for new classes (`ChatModelCatalog`, `ChatModelCatalogProperties`, `KnobInventoryScanner`, `KnobInventory`, `AiUiSettingsResolver`, `AiParametersEntityListener`, `AiUiSettingsEntityListener`, `KnobMetadata` annotation, `AiSettingsChangedEvent`, `AuditKind.MODEL_VALIDATION_FAILURE` addition).
- Whether `KnobInventoryScanner` lives on the `ai-agent` library classpath or the `ai-agent-starter` autoconfig classpath (host apps may want to opt-out of admin UI but keep runtime knobs — planner decides based on starter vs library coupling).
- Exact provider error markers that classify a `NonTransientAiException` / `RestClientResponseException` as "bad model" (provider-specific; OpenRouter today, future providers may differ). Planner researches current OpenRouter error response shapes for `model_not_found` / `invalid_model` markers.
- Whether `MODEL_VALIDATION_FAILURE` audit row carries the offending model id verbatim or hashed (project memory `feedback_rich_tool_descriptions` style + `MutationErrorTranslator` P-22 sanitisation precedent suggest verbatim for admin-debugging surface, since the model id is admin-input, not user input — confirm during planning).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope + requirements
- `.planning/phases/16-admin-settings-model-picker-config-knob-migration/16-SPEC.md` — Locked requirements (5), boundaries, acceptance criteria. MUST read before planning.
- `.planning/ROADMAP.md` (Phase 16 section) — merged-phase narrative + cross-phase ordering (Phase 16 → Phase 18 cache surface).
- `.planning/REQUIREMENTS.md` (Phase 16 section) — MODEL-01..03, CFG-01..03, SEC-08, TEST-20 contract text.
- `.planning/STATE.md` (Milestone v1.2 Scope + Decisions sections) — cross-phase invariants Phase 16 must preserve.

### Pattern precedents (Phase carry-forward)
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/15-CONTEXT.md` — D-01 to D-09; the in-fragment status-row pattern + bounded-state-in-AiChatSessionState invariants.
- `.planning/milestones/v1.1.0-phases/10-ai-specific-llm-exposure-policy/10-CONTEXT.md` — Plan 10-06 R2 single-publish-site pattern (`AiExposureRuleEntityListener` is the precedent for `AiParametersEntityListener` / `AiUiSettingsEntityListener`).
- `.planning/milestones/v1.1.0-phases/11-mutation-capable-built-in-tools/11-CONTEXT.md` — `MutationErrorTranslator` P-22 sanitisation precedent for the `MODEL_VALIDATION_FAILURE` audit shape (no raw exception text, no LLM-supplied attribute names).
- `.planning/milestones/v1.1.0-phases/12-configurable-chat-surfaces/12-CONTEXT.md` — Singleton-entity load via `UnconstrainedDataManager`, `AiUiSettingsService.loadCurrent()` (D-15), `agentstore-changelog.xml` `includeAll` pattern.
- `.planning/milestones/v1.1.0-phases/13.1-chat-attachments-right-pane/13.1-CONTEXT.md` — Phase 13.1 BLK-01 invariant for `executeBlockingTurn` chokepoint (the catch+reissue site in D-05).

### Code reference points
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java` — singleton entity to expand; SINGLETON_ID `00000000-0000-0000-0000-000000120001`; existing `enabledSurfaceIds` / `defaultSurface` flat-column precedent; the `setEnabledSurfaceSet` comment block documents the EclipseLink-weaver setter-name trap that motivates D-01.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java` — per-profile entity carrying `bodyYaml` blob.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBody.java` — DTO record; `enabledTools` allowlist sentinel + RAG knob nullability.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBodyYamlMapper.java` — strict-on-write YAML mapper (PARAM-02 / D-05 precedent).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java` — DB → `module.properties` → `default-params.yaml` read-through; `AiUiSettingsResolver` mirrors this shape.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java` + `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/` (XML) — singleton detail view to extend with new fields + Boot Config tab + Secrets section.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml` — `modelField` currently `<textField id="modelField" required>` → becomes `<comboBox allowCustomValue="true">`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — `executeBlockingTurn` chokepoint (Phase 13.1 BLK-01) for the catch+reissue in D-05.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java` — `loadCurrent()` via `UnconstrainedDataManager` pattern; reuse for resolver singleton load.
- All `@ConfigurationProperties` records under `ai-agent/ai-agent/src/main/java/com/vn/agent/**/`: `AiAgentRagProperties`, `AiAgentMutationProperties`, `AiAgentAuditProperties`, `AiAgentGuardProperties`, `AiAgentPromptProperties`, `AiAgentTitleProperties`, `AiAgentDefaultsProperties` (+ `AiAgentTaskFileProperties` if present) — all get `@KnobMetadata` annotation pass in this phase.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — Tier-1 knob defaults that the read-through falls back to.
- `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml` — strict seed (no new keys added in this phase).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml` — `includeAll` parent; new `120-ai-ui-settings-tier1-knobs.xml` lands as a sibling.

### Project memory (load-bearing)
- `project_self_hostable_models_only.md` — gates the curated catalog content.
- `feedback_jmix_first_ui.md` — defaults form binding to XML descriptor + `property=`.
- `feedback_jmix_view_listeners.md` — `@Subscribe` / `@Install` event wiring.
- `feedback_jmix_loadvalue_store.md` — raw-JPQL `loadValue` / `loadValues` needs explicit `.store("agentstore")`.
- `feedback_jmix_messages_over_spring.md` — inject `io.jmix.core.Messages`, root bundle keys.
- `feedback_jmix_datagrid_renderer.md` — `@Supply(to="grid.col", subject="renderer")` for the Boot Config + Secrets column rendering.
- `feedback_pragmatic_modules.md` + `feedback_spi_baseline_builtin.md` — gates against per-cluster resolvers / SPI explosion.
- `feedback_reuse_jmix_builtins.md` — gates against EAV / hand-rolled stores.
- `feedback_unconstrained_for_system_writes.md` — confirms `UnconstrainedDataManager` for the singleton write path.
- `feedback_rich_tool_descriptions.md` — gates against per-tool description override exposure.

### Spring AI / framework references
- Spring AI 1.1.4 ChatClient API docs (Context7 `/spring-projects/spring-ai` at plan time) — `ChatClient.mutate()`, `ChatOptions.builder()`, `NonTransientAiException` semantics (for D-05 classification).
- Spring Boot `ConfigurationPropertiesBean.getAll(applicationContext)` API (Context7 `/spring-projects/spring-boot` at plan time) — for the Actuator-fallback discovery in D-02.
- Jmix 2.8 entity-listener docs (Context7 `/jmix-framework/jmix-context7` at plan time) — for `AiParametersEntityListener` / `AiUiSettingsEntityListener` shape.
- Jmix Flow UI `<comboBox allowCustomValue>` + `setItemLabelGenerator` docs (Context7 at plan time) — for D-06 ComboBox binding.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `AiUiSettingsService.loadCurrent()` — singleton load pattern via `UnconstrainedDataManager`; reuse verbatim from `AiUiSettingsResolver`.
- `AiParametersResolver` — DB → `module.properties` → `default-params.yaml` fallback shape; `AiUiSettingsResolver` mirrors this exactly (same structure, different entity).
- `AiParametersBodyYamlMapper` strict-on-write precedent — not directly reused (flat columns instead) but the strict-validation philosophy informs bean-validation choice.
- `AiExposureRuleEntityListener` (Plan 10-06 R2) — direct precedent for `AiParametersEntityListener` + `AiUiSettingsEntityListener` (single publish site per entity).
- `executeBlockingTurn(...)` private helper in `DefaultChatServiceImpl` (Phase 13.1 BLK-01) — direct chokepoint for the catch+reissue in D-05.
- Phase 15 `15-04-SUMMARY.md` in-fragment status-row pattern — reusable for the operator-visible "fell back to default model" notification (light reuse, not heavy).
- Existing `AuditKind` enum + `AuditWriter.writeToolCall(...)` API — extend with `MODEL_VALIDATION_FAILURE` and write 2 rows on fallback path.
- `parameters-detail-view.xml` tabSheet structure (formTab + yamlTab) — extend with no new tabs; replace `modelField` element in-place.
- `AiUiSettingsDetailView` existing form binding via `EditedEntityContainer("uiSettingsDc")` — extend with new `<integerField property=...>` / `<bigDecimalField property=...>` / `<checkbox property=...>` lines for each Tier-1 column.

### Established Patterns
- **Singleton write via `UnconstrainedDataManager`** (Phase 12 D-15) — `AiUiSettings.SINGLETON_ID` is sealed; no admin can create alternate settings rows.
- **`@ResourceRole` + `@EntityPolicy` + `@EntityAttributePolicy`** pattern — `AiAgentAdminRole` gets the new policies for Tier-1 attribute MODIFY + new menu/view IDs.
- **`agentstore-changelog.xml` `includeAll`** strategy (Phase 12 D-15) — new changelog file lands as sibling, parent file untouched.
- **All msg keys in root bundle** (`feedback_jmix_messages_over_spring`) — `messages_en.properties` + `messages_vi.properties`, no per-view bundles.
- **`@Supply(to="grid.col", subject="renderer")` + `UiComponents.create`** (`feedback_jmix_datagrid_renderer`) — for the Boot Config badge + Secrets indicator columns.
- **`AiParametersResolver`** read-through: DB → `module.properties` → `default-params.yaml` — mirror exactly in `AiUiSettingsResolver`.
- **Plan 10-06 R2 invariant**: views/services NEVER inject `ApplicationEventPublisher` for the settings-change event; only entity listeners publish.

### Integration Points
- `AiUiSettings` entity → new `AiUiSettingsEntityListener` → publishes `AiSettingsChangedEvent(kind=UI_SETTINGS)` → Phase 18 cache eviction.
- `AiParameters` entity → new `AiParametersEntityListener` (guard `active=true`) → publishes `AiSettingsChangedEvent(kind=PARAMETERS)` → Phase 18 cache eviction.
- `parameters-detail-view.xml` `modelField` → `ChatModelCatalog.entries()` + `setItemLabelGenerator` + value-change listener writes to `AiParametersBody.model`.
- `AiUiSettingsDetailView` form tab → new `<integerField property=...>` etc. for Tier-1; new `<tab id="bootConfigTab">` for Tier-2 read-only + Secrets section.
- `DefaultChatServiceImpl.executeBlockingTurn(...)` → wrap `ChatClient.call(...)` with classified catch → reissue via resolver default-model branch → emit 2 audit rows.
- All Tier-1 caller sites (per D-03 list) → swap from `props.resolvedFoo()` to `aiUiSettingsResolver.resolveFoo()` — additive (callers gain a new injection alongside existing `@ConfigurationProperties` injection; existing reads stay for the fall-through path).

</code_context>

<specifics>
## Specific Ideas

- **Default-marked entry in ComboBox MUST equal `default-params.yaml.model` at ship time** — a drift test asserts this so a default-params seed bump doesn't silently de-sync the marked catalog entry.
- **Sentinel `-1` for task-file knobs MUST continue to disable cleanup** (Phase 13.1 `TtlConfigSentinelSkipsCleanupTest` invariant) — test re-runs unchanged with the field source swapped from `module.properties` to `AiUiSettings`.
- **Secret pattern matching at view render**: scan ALL `Environment` keys (not just annotated records) against `*.api-key | *.password | *.secret | *.token` — defense-in-depth against a host forgetting `@KnobMetadata(tier=TIER_3)`.
- **`MODEL_VALIDATION_FAILURE` audit row carries provider error fingerprint** (HTTP status + sanitised error class name) but NOT the raw response body — honors `MutationErrorTranslator` P-22 sanitisation precedent. Offending model id is admin-input (not user-input) so verbatim is acceptable.

</specifics>

<deferred>
## Deferred Ideas

- **Per-tool description / per-tool `topK` / per-tool `similarityThreshold` map shape** (jmix-ai-backend style) — out of scope per SPEC.md. Promote to Backlog when a second retriever lands. The `enabledTools` allowlist stays as the only per-tool admin lever.
- **`ModelFallbackAdvisor` (Spring AI advisor pattern) for operational fallbacks** — defer until a second operational-fallback case (quota exceeded, content filter, regional failover) lands and pays for the abstraction. Phase 16 establishes the catch+reissue precedent at `executeBlockingTurn`; the advisor can absorb the precedent later.
- **Pre-flight probe (1-token warmup against the configured model)** — defer until first-turn-failed-call cost (full retrieval + advisor chain wasted) is shown to dominate the budget. Adds latency to first turn after every settings change; not worth it absent data.
- **`AiOperatorSettings` entity split** (UI-shaped vs operator-runtime) — defer until cross-entity invariants get awkward; `feedback_pragmatic_modules` gates against premature split.
- **JSON sidecar field on `AiUiSettings` for host-extension knobs** — defer until a concrete host extension consumer is named; `feedback_pragmatic_modules` gates against speculative extension surface.
- **YAML/properties metadata file `META-INF/spring/ai-agent-knob-catalog.yaml`** — defer; annotation + Actuator-pattern fallback covers the inventory need without a second drifting source-of-truth.
- **`spring.ai.retry.*` / `rag.splitter.*` / `rag.ingest-executor.*` / `conversation-title.executor.*` as Tier-1 runtime-editable** — out of scope per SPEC.md. Splitter / executor knobs are Tier-2 by hazard (chunk-size at runtime creates inconsistent index semantics; thread-pool resize at runtime is hazardous for in-flight tasks); retry knobs are Tier-2 by boot-time advisor wiring.
- **Per-conversation end-user model switching** — out of scope per SPEC.md (admin-only model selection is a SEC-by-contract constraint).
- **Custom catalog persistence in the database (`AiModelCatalog` entity + CRUD view)** — out of scope per SPEC.md; promote to a future phase if hosts need per-deployment overrides without property rebuilds.
- **CSS-only "default" marking on ComboBox catalog entries** — rejected; screen-reader announcing "default" via `(default)` suffix matters more than a colour cue.

</deferred>

---

*Phase: 16-admin-settings-model-picker-config-knob-migration*
*Context gathered: 2026-05-13*
