# Phase 16: Admin Settings — Model Picker & Config-Knob Migration — Research

**Researched:** 2026-05-13
**Domain:** Jmix Flow UI admin views + Spring AI per-request `ChatOptions` + Spring Boot `@ConfigurationProperties` inventory + Liquibase additive DDL + audit/event publication
**Confidence:** HIGH (all D-01..D-09 implementations have precedent files in the codebase; all API surfaces confirmed via Context7 + existing in-tree usage)

## Summary

Phase 16 is **additive and pattern-rich**. Every D-01..D-09 decision has a working precedent already in `ai-agent/ai-agent/src/main`:
- D-01 flat-column entity expansion mirrors the existing `enabledSurfaceIds` / `defaultSurface` columns on `AiUiSettings`.
- D-02 `ConfigurationPropertiesBean.getAll(ApplicationContext)` is a public Spring Boot 2.2+ API (we run Boot 3 via Jmix 2.8.1) that does NOT require Actuator on the classpath; defense-in-depth secret pattern matching uses `Environment.getProperty`.
- D-03 `AiUiSettingsResolver` mirrors `AiParametersResolver` exactly (same DB → properties → constant fallback chain).
- D-04 entity listeners mirror `AiExposureRuleEntityListener` verbatim — a Jmix `EntityChangedEvent` handler annotated with `@EventListener` is the locked precedent (Plan 10-06 R2). Confirmed via Context7 `/jmix-framework/jmix-context7`.
- D-05 catch+reissue lands at the exact `ChatClient.prompt()...call().chatClientResponse()` site in `DefaultChatServiceImpl.executeBlockingTurn(...)` (lines 383-412); the existing `try { stream... } catch (UnsupportedOperationException) { executeBlockingTurn(...) }` graceful-fallback chains the streaming path through the same chokepoint so D-05's catch fires once per turn regardless of transport.
- D-06 catalog is a thin `@ConfigurationProperties` record + `@Component` validator + `Set<String>` allowlist constant; `<comboBox allowCustomValue="true">` + `CustomValueSetEvent` is the documented Jmix Flow UI pattern (Context7).
- D-07 boot-config tab uses `<dataGrid>` over in-memory rows + `@Supply(to="...col", subject="renderer")` per project memory `feedback_jmix_datagrid_renderer`.
- D-08 lands one new `120-ai-ui-settings-tier1-knobs.xml` sibling under the `includeAll` parent `agentstore-changelog.xml` (next free slot after `110-ai-extraction-draft.xml`).
- D-09 root-bundle keys live in `messages_en.properties` + `messages_vi.properties` per `feedback_jmix_messages_over_spring`.

**Primary recommendation:** Treat this phase as **6 vertically-sliced waves** (catalog + model picker, AiUiSettings schema + Liquibase, Resolver + caller wiring, Entity listeners + event, Boot-config tab + Secrets section, Catch+reissue + audit kind). Each wave is independently mergeable behind feature switches and has its own integration test class. The riskiest piece is **D-05's classification of provider error responses** — OpenRouter today returns `{"error":{"message":"Model X is not available","code":404}}` for unknown models, but the planner must defensively check status code (400/404) + error-class fingerprint, NOT regex-match the message text.

## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01: Flat JPA columns** — ~10 new nullable columns on the `AiUiSettings` singleton, one per Tier-1 knob, each carrying Jakarta bean-validation annotations directly. Column naming: `TASK_FILE_TTL_SECONDS` (Long, nullable, sentinel `-1` preserved per Phase 13.1), `TASK_FILE_PER_TURN_MAX_FILES` (Integer, nullable, sentinel `-1`), `TASK_FILE_PER_TURN_MAX_TOTAL_BYTES` (Long, nullable, sentinel `-1`), `MUTATION_CONFIRMATION_REQUIRED` (Boolean, nullable), `MUTATION_IDEMPOTENCY_TTL_SECONDS` (Long, nullable — stored as seconds to avoid JPA Duration mapping fuss), `MUTATION_BULK_MAX_ROWS` (Integer, nullable), `PROMPT_ENTITY_INVENTORY_LIMIT` (Integer, nullable), `TOOLS_MAX_FILTER_DEPTH` (Integer, nullable), `TITLE_MAX_CONTEXT_MESSAGES` (Integer, nullable), `TITLE_MIN_ASSISTANT_MESSAGES_TRIGGER` (Integer, nullable), `UPLOAD_MAX_FILE_SIZE_BYTES` (Long, nullable). All columns NULLABLE (null = fall through to `@ConfigurationProperties` default).

**D-02: Three-layer knob inventory mechanism** — `@KnobMetadata` annotation primary + `ConfigurationPropertiesBean.getAll(applicationContext)` fallback discovery + Tier-3 secret-pattern mask as defense-in-depth.

**D-03: Sibling `AiUiSettingsResolver` `@Component`** — mirrors `AiParametersResolver` shape; loads `AiUiSettings.SINGLETON_ID` via `UnconstrainedDataManager`; NOT extending `AiParametersResolver`. Caller injection: `AiTaskFileCleanupJob`, `AiTaskFileMediaResolver`, `ChatPanelFragment` (upload), `BuiltInMutationTools`, `MutationIntentRepository`, `MutationSaveExecutor`, `BaselineContextProvider`, `ToolEntityResolver` / `BuiltInDataTools`, `AiConversationTitleService`, `KnowledgeDocumentUploadService`.

**D-04: One typed event, two entity listeners** — `AiSettingsChangedEvent extends ApplicationEvent` with `kind ∈ {PARAMETERS, UI_SETTINGS}`. `AiParametersEntityListener` guards `active=true`. `AiUiSettingsEntityListener` fires on every singleton save. Views/services NEVER inject `ApplicationEventPublisher` for this event.

**D-05: Catch + reissue inside `executeBlockingTurn(...)`** — wrap the `ChatClient.call(...)` chokepoint (Phase 13.1 BLK-01); on classified bad-model exception (`RestClientResponseException` 4xx with model-not-found markers + `org.springframework.ai.retry.NonTransientAiException` with model markers), do exactly one reissue against the resolved `default-params.yaml` model. New `AuditKind.MODEL_VALIDATION_FAILURE`. Two audit rows per failed-then-recovered turn, sharing the same `runId`.

**D-06: `ChatModelCatalogProperties` `@ConfigurationProperties` record** bound to `jmix.ai-agent.models.catalog[*]` with components `(id, labelMessageKey, isDefault)`. `ChatModelCatalog` `@Component` validates "exactly one default" at `@PostConstruct`. `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` is a `Set<String>` constant. TEST-20 asserts catalog ⊆ allowlist + exactly-one-default + drift gate (default == `default-params.yaml.model`).

**D-07: New `<tab id="bootConfigTab">`** inside `AiUiSettingsDetailView`'s tabSheet with `<dataGrid>` + `@Supply(to="grid.col", subject="renderer")` for badge. Tier-3 Secrets section as sibling. Admin-role gating via `@EntityAttributePolicy` MODIFY=DENY for non-admins.

**D-08: New changelog `120-ai-ui-settings-tier1-knobs.xml`** under `src/main/resources/com/vn/agent/liquibase/agentstore-changelog/`. Parent `agentstore-changelog.xml` uses `includeAll` (no edit). `080-ai-ui-settings.xml` is byte-identical post-phase.

**D-09: All msg keys** in BOTH `messages_en.properties` AND `messages_vi.properties` root bundle. New key groups: `aiUiSettings.section.*`, `aiUiSettings.field.*`, `aiUiSettings.validation.*`, `aiUiSettings.tab.bootConfig`, `aiUiSettings.tab.secrets`, `aiUiSettings.bootConfig.column.*`, `aiUiSettings.bootConfig.badge.requiresRestart`, `aiUiSettings.secrets.column.*`, `aiUiSettings.secrets.indicator.{yes,no}`, `parametersDetail.modelField.customValueHint`, `parametersDetail.modelField.defaultSuffix`, `chat.error.modelValidationFailure`, `chat.notice.modelFallbackApplied`.

### Claude's Discretion

1. Exact bean-validation bounds per Tier-1 knob (see Recommendations below)
2. Exact entry list for curated catalog default seed beyond `qwen/qwen3.6-35b-a3b`
3. Exact set of `@ConfigurationProperties` records getting `@KnobMetadata` annotations (confirmed inventory below — **8 records**, not 7)
4. Package locations for new classes (see Recommendations)
5. `KnobInventoryScanner` classpath placement (`ai-agent-starter` autoconfig vs `ai-agent` library) — see Recommendations
6. Exact provider error markers for bad-model classification (see API Surface Confirmations)
7. Whether `MODEL_VALIDATION_FAILURE` audit row carries the offending model id verbatim or hashed (recommendation: **verbatim**, per `MutationErrorTranslator` P-22 precedent + admin-input not user-input)

### Deferred Ideas (OUT OF SCOPE)

- Per-tool description / per-tool `topK` / per-tool `similarityThreshold` map shape (jmix-ai-backend style).
- `ModelFallbackAdvisor` Spring AI advisor pattern (defer until second operational-fallback case lands).
- Pre-flight probe / warmup turn against configured model.
- `AiOperatorSettings` entity split (UI-shaped vs operator-runtime).
- JSON sidecar field on `AiUiSettings` for host-extension knobs.
- YAML/properties metadata file `META-INF/spring/ai-agent-knob-catalog.yaml`.
- `spring.ai.retry.*` / `rag.splitter.*` / `rag.ingest-executor.*` / `conversation-title.executor.*` as Tier-1.
- Per-conversation end-user model switching.
- Custom catalog persistence in the database (`AiModelCatalog` entity + CRUD view).
- CSS-only "default" marking on ComboBox catalog entries.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| MODEL-01 | Admin curated ComboBox catalog of open-weights models, default-marked | D-06 catalog binding + `<comboBox allowCustomValue="true">` confirmed via Context7 Jmix |
| MODEL-02 | Custom-entry escape hatch + at-first-use validation | D-05 catch+reissue at `executeBlockingTurn` chokepoint (line 383-412) |
| MODEL-03 | Admin-only model selection flows to per-request `ChatOptions` | Existing line 405-410 in `DefaultChatServiceImpl` already uses `ChatOptions.builder().model(model)` per-request |
| CFG-01 | Tier-1 operator runtime knobs editable, read fresh per turn via `AiParametersResolver`-style read-through | D-03 sibling `AiUiSettingsResolver` mirroring `AiParametersResolver.effectiveModel/Temperature/etc.` (lines 109-185) |
| CFG-02 | Tier-2 boot-time `@ConditionalOnProperty` read-only with badge; Tier-3 secrets indicator-only | D-02 three-layer inventory + D-07 dataGrid render |
| CFG-03 | Persist as fields on `AiParameters`/`AiUiSettings` + Liquibase + bean-validation + change event for cache eviction | D-01 flat columns + D-04 `AiSettingsChangedEvent` + D-08 changelog |
| SEC-08 | Source-scan test asserts no `*.api-key`/etc. surfaced editable + no `@ConditionalOnProperty` toggle bound editable | D-02 reflective gate + Tier-3 pattern mask defense-in-depth |
| TEST-20 | Catalog ⊆ `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` + exactly-one-default + drift gate vs `default-params.yaml.model` | D-06 `ChatModelCatalogAllowlistTest` |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Model picker UI binding | Frontend Server (Vaadin Flow controller) | — | `parameters-detail-view.xml` + `ParametersDetailView` controller; values write through `EditedEntityContainer` to JPA |
| Curated catalog source-of-truth | API / Backend (`@ConfigurationProperties`) | — | Property-driven `jmix.ai-agent.models.catalog[*]`; not entity-CRUDed |
| Per-request `ChatOptions.model` override | API / Backend (`DefaultChatServiceImpl.executeBlockingTurn`) | — | Already wired via `parametersResolver.effectiveModel(active, overrides)` at line 250 + `ChatOptions.builder().model(model)` at 405-410 |
| Tier-1 knob persistence | Database / Storage (agentstore JPA) | — | Singleton row in `AI_UI_SETTINGS` table |
| Tier-1 knob read path | API / Backend (resolver) | — | `AiUiSettingsResolver` consults `UnconstrainedDataManager` per-turn |
| Tier-1 admin form | Frontend Server (Jmix `<formLayout>`) | — | `EditedEntityContainer("uiSettingsDc")` form bindings |
| Tier-2 read-only dataGrid | Frontend Server | — | `<dataGrid>` populated from `KnobInventory.tier2()` at view init |
| Tier-3 secrets indicator | Frontend Server | Environment / Spring property source | `Environment.getProperty(key)` non-blank check only; no value rendered |
| `AiSettingsChangedEvent` publication | API / Backend (entity listeners) | — | Single publish site per entity (Jmix `EntityChangedEvent` → Spring `ApplicationEvent`) |
| Settings cache eviction | API / Backend (Phase 18) | — | Out of scope this phase; this phase only PUBLISHES, Phase 18 SUBSCRIBES |
| Liquibase additive changelog | Database / Storage | — | `120-ai-ui-settings-tier1-knobs.xml` sibling under `includeAll` parent |
| Audit row write | Database / Storage (`agentstore`) | — | Reuse existing `AuditWriter.writeToolCall(...)` with `AuditKind.MODEL_VALIDATION_FAILURE` |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jmix BOM | 2.8.1 | Entities, views, security, DataManager, UnconstrainedDataManager | [VERIFIED: D:/DTH/ai-agent-core/ai-agent/build.gradle line 42 + gradle.properties] |
| Spring AI BOM | 1.1.4 | `ChatClient`, `ChatOptions`, `NonTransientAiException` | [VERIFIED: build.gradle line 47 + existing imports in `DefaultChatServiceImpl`] |
| Spring Boot | 3.x (transitive via Jmix 2.8.1) | `@ConfigurationProperties`, `ConfigurationPropertiesBean.getAll`, `RestClientResponseException` | [VERIFIED: existing `@ConfigurationProperties` records throughout codebase] |
| Liquibase | included with Jmix 2.8.1 | DDL changelog `120-ai-ui-settings-tier1-knobs.xml` | [VERIFIED: existing `agentstore-changelog/*.xml` files] |
| Jakarta Bean Validation | 3.x (transitive) | `@NotNull`, `@Min`, `@Max`, `@Pattern` annotations on new entity columns | [VERIFIED: existing `@NotNull` on `AiUiSettings.enabledSurfaceIds` line 47] |
| snakeyaml | transitive | Already used by `AiParametersResolver` to parse `bodyYaml`; NOT needed for D-01 flat columns | [VERIFIED: import line 11 in `AiParametersResolver`] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Spring `ApplicationEventPublisher` | Spring Framework 6 | Entity listeners → `AiSettingsChangedEvent` publication | Inside `AiParametersEntityListener` / `AiUiSettingsEntityListener` only |
| Jmix `Messages` | 2.8 | Locale-aware label resolution for ComboBox `setItemLabelGenerator` + Tier-1 form messages | Per project memory `feedback_jmix_messages_over_spring` — inject `io.jmix.core.Messages`, not Spring `MessageSource` |
| Jmix `UiComponents` | 2.8 | `@Supply` renderer construction for badge + secret-indicator columns | Per project memory `feedback_jmix_datagrid_renderer` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Flat JPA columns on `AiUiSettings` (D-01) | JSON sidecar field or `AiOperatorSettings` second entity | `feedback_pragmatic_modules` gates against speculative entity-split until concrete consumer named; flat columns let `<integerField property="...">` bind zero-glue |
| Catch+reissue at `executeBlockingTurn` (D-05) | Spring AI `Advisor` / `ModelFallbackAdvisor` | Spring AI 1.1.4 advisor streaming-error semantics are uneven (issue #2877); the catch site stays at the BLK-01 chokepoint until a second fallback case justifies the abstraction |
| `ConfigurationPropertiesBean.getAll` discovery (D-02) | Static knob registry YAML in `META-INF/` | Drift-prone; the `@KnobMetadata` annotation is the SoT and `getAll` is the fallback for host-extension records — single SoT philosophy |
| Sibling `AiUiSettingsResolver` (D-03) | Merge into `AiParametersResolver` | Merging forces 15+ caller file churn; sibling preserves additive-only Plan 10-06 R2 invariant |

**Installation:** No new dependencies needed. All Phase 16 work is additive to the existing Jmix 2.8.1 + Spring AI 1.1.4 + Spring Boot 3 stack.

**Version verification:**
- Jmix 2.8.1 — confirmed via `build.gradle` line 42 (`bomVersion = '2.8.1'`)
- Spring AI 1.1.4 — confirmed via `build.gradle` line 47 (`set('springAiVersion', "1.1.4")`)
- Both are project-pinned; no version drift between research and plan time.

## API Surface Confirmations

### 1. Spring AI `ChatClient` + `ChatOptions.builder().model(...)` per-request override

**Confirmed via Context7 `/spring-projects/spring-ai`** — `ChatOptions.builder().model("provider/slug").temperature(...).topP(...).maxTokens(...).build()` is the documented per-request override pattern across all providers (OpenAI, Mistral, MiniMax, DeepSeek, Ollama).

**In-tree usage confirms:** `DefaultChatServiceImpl.executeBlockingTurn` lines 405-410:
```java
.options(ChatOptions.builder()
        .model(model)
        .temperature(parametersResolver.effectiveTemperature(active))
        .topP(parametersResolver.effectiveTopP(active))
        .maxTokens(parametersResolver.effectiveMaxTokens(active))
        .build())
```
The `model` parameter is already threaded per-turn via `parametersResolver.effectiveModel(active, effectiveOverrides)`. **No mutation required for MODEL-03** — the per-request override mechanism is already in place. The phase only needs to (a) feed a curated value into `AiParameters.bodyYaml.model` and (b) add the catch+reissue at line 412 (`.call().chatClientResponse()` site).

`[VERIFIED: Context7 /spring-projects/spring-ai + D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java lines 405-412]`

**`ChatClient.mutate()` note:** The existing code reuses the singleton `chatClient` and overrides per-request via `.prompt()...options(...)`. `ChatClient.mutate()` is NOT needed for D-05's per-turn reissue — just rebuild the prompt with a different `ChatOptions.builder().model(fallbackModel).build()`.

### 2. Spring Boot `ConfigurationPropertiesBean.getAll(ApplicationContext)`

`[VERIFIED: https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/context/properties/ConfigurationPropertiesBean.html — public API since Spring Boot 2.2.0; class is final, method is static, returns Map<String, ConfigurationPropertiesBean>]`

```java
public static Map<String, ConfigurationPropertiesBean> getAll(ApplicationContext applicationContext)
```

**Key facts for D-02:**
- The class lives in `org.springframework.boot.context.properties` — part of `spring-boot` core, **NOT** `spring-boot-actuator`. **No new dependency needed**.
- Returns ALL `@ConfigurationProperties`-annotated beans in the context, keyed by bean name. Value carries `getName()`, `getInstance()`, `getType()`, `getAnnotation()`, `getBindMethod()`.
- Useful API surface on each `ConfigurationPropertiesBean`:
  - `getInstance()` → the bound bean object; reflect for `@KnobMetadata` on record components / setter methods
  - `getAnnotation()` → the `@ConfigurationProperties` annotation (carries the `prefix`)
  - `getType()` → the bean class for further reflection
- Spring Boot Actuator's `/configprops` endpoint internally uses this API; we're calling it directly so we don't pull Actuator.

**Scanner runs at `ApplicationReadyEvent`** to ensure the full property source is bound. Cache the inventory as an immutable `@Component` `KnobInventory` for view-init reads.

### 3. Spring AI error classification — bad-model markers

**OpenRouter error shape** (current provider used at host deployments):
```json
{
  "error": {
    "message": "Model openai/foo-not-real is not available",
    "code": 404
  },
  "user_id": "..."
}
```
`[VERIFIED: https://openrouter.ai/docs/api/reference/errors-and-debugging + verified via web search on production behavior 2026-05]`

**Status codes for "bad model":**
- `404 Not Found` — most common for unknown model slugs
- `400 Bad Request` — provider rejects slug format or unsupported feature on that model (e.g., `tool_choice` value)
- `422 Unprocessable Entity` — sometimes used when slug parses but provider routing fails

**Spring AI exception classification:**
- `org.springframework.ai.retry.NonTransientAiException` — the parent class Spring AI uses to mark errors that should NOT trigger retry advisor. Bad-model errors are non-transient.
- `org.springframework.web.client.RestClientResponseException` — the raw HTTP response carrier; subclasses are `HttpClientErrorException` (4xx) and `HttpServerErrorException` (5xx).
- `HttpClientErrorException.NotFound` for 404, `HttpClientErrorException.BadRequest` for 400.

**Classification rule for D-05 (recommended):**
```
Bad-model exception IFF:
  e instanceof NonTransientAiException
  AND (
    cause chain contains RestClientResponseException with rawStatusCode ∈ {400, 404, 422}
    AND responseBody contains "model" (case-insensitive substring on the JSON error.message)
  )
```
The substring check on `"model"` is intentionally loose — provider messages vary (`"Model X is not available"`, `"invalid model"`, `"no endpoints found that support the provided model"`). Combine with status code to keep false positives down. Do NOT regex-match exact message text — it drifts across provider versions.

`[VERIFIED: web search on OpenRouter error patterns 2026-05 + Spring AI 1.1.4 exception hierarchy via Context7]`

**Defensive note:** The reissue MUST classify on cause chain (not just top-level exception) because Spring AI wraps provider errors via the retry advisor. Use `Throwable.getCause()` walk capped at depth 5.

`[ASSUMED]` — exact substring patterns may shift; verify against a recorded OpenRouter 404 response during plan execution (Plan can include a small integration test stubbing the provider response).

### 4. Jmix `EntityChangedEvent` entity listener pattern (D-04)

**Confirmed via Context7 `/jmix-framework/jmix-context7`** — the canonical Jmix 2.x pattern is:

```java
@Component
public class XyzEntityListener {
    @Autowired private ApplicationEventPublisher publisher;

    @EventListener
    void onChangedBeforeCommit(EntityChangedEvent<Xyz> event) {
        // event.getType() ∈ {CREATED, UPDATED, DELETED}
        // event.getEntityId() / event.getChanges()
        publisher.publishEvent(new XyzChangedEvent(this));
    }
}
```

The handler fires AFTER save BEFORE commit — exceptions trigger rollback. This is the exact shape used by `AiExposureRuleEntityListener` (Plan 10-06 R2).

`[VERIFIED: Context7 /jmix-framework/jmix-context7 + D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java]`

**Active guard pattern for `AiParameters` listener (D-04):**
```java
@EventListener
void onParametersChangedBeforeCommit(EntityChangedEvent<AiParameters> event) {
    if (event.getType() == EntityChangedEvent.Type.DELETED) {
        // Deleted row's active flag is in the old reference; check old value
        Boolean oldActive = event.getChanges().getOldValue("active");
        if (Boolean.TRUE.equals(oldActive)) {
            publisher.publishEvent(new AiSettingsChangedEvent(this, Kind.PARAMETERS));
        }
        return;
    }
    // For CREATED/UPDATED — load current state via DataManager to check active flag
    AiParameters params = dataManager.load(event.getEntityId()).one();
    if (Boolean.TRUE.equals(params.getActive())) {
        publisher.publishEvent(new AiSettingsChangedEvent(this, Kind.PARAMETERS));
    }
}
```
Watch for the `active` field change specifically — `event.getChanges().isChanged("active")` lets the listener detect "row just became active OR just became inactive while being saved" cases. The semantically correct rule per CONTEXT.md D-04 is **publish IFF the saved row's `active` is `true` AT SAVE TIME**.

### 5. Jmix Flow UI `<comboBox allowCustomValue="true">` + `setItemLabelGenerator` (D-06)

**Confirmed via Context7 `/jmix-framework/jmix-context7`:**

XML:
```xml
<comboBox id="modelField"
          property="model"
          label="msg:///parametersDetail.field.model"
          allowCustomValue="true"
          required="true"
          requiredMessage="msg:///parametersDetail.validation.modelRequired"/>
```

Java controller:
```java
@ViewComponent
private JmixComboBox<String> modelField;

@Subscribe
public void onInit(InitEvent event) {
    modelField.setItems(catalog.entries().stream().map(CatalogEntry::id).toList());
    modelField.setItemLabelGenerator(id -> {
        CatalogEntry entry = catalog.findById(id);
        if (entry == null) return id; // custom-entered value
        String label = messages.getMessage(entry.labelMessageKey());
        return entry.isDefault()
                ? label + " " + messages.getMessage("parametersDetail.modelField.defaultSuffix")
                : label;
    });
}

@Subscribe("modelField")
public void onModelFieldCustomValueSet(ComboBoxBase.CustomValueSetEvent<ComboBox<String>> event) {
    modelField.setValue(event.getDetail());  // accept verbatim
}
```

**Critical:** `property="model"` on the `<comboBox>` doesn't work directly because `model` is inside `bodyYaml` (a String blob), not a flat column. Use the existing PARAM detail-view's pattern of binding to a transient field or letting the existing yaml-write path serialize via `AiParametersBodyYamlMapper`. **Scout the existing `ParametersDetailView` controller** to see how `temperatureField`, `topPField` etc. write back to `bodyYaml` — they bind to in-memory fields and the controller composes the YAML on save.

`[VERIFIED: Context7 /jmix-framework/jmix-context7 — comboBox.html + ui-samples/combobox-user-input.md]`

### 6. Jmix `<dataGrid>` + `@Supply(to="grid.col", subject="renderer")` (D-07)

Per project memory `feedback_jmix_datagrid_renderer`: **never** use programmatic `getColumnByKey().setRenderer()` in `onInit`. The correct pattern:

```xml
<dataGrid id="bootConfigGrid" dataContainer="bootConfigDc" width="100%">
    <columns>
        <column key="key" header="msg:///aiUiSettings.bootConfig.column.key"/>
        <column key="value" header="msg:///aiUiSettings.bootConfig.column.value"/>
        <column key="badge" header="msg:///aiUiSettings.bootConfig.column.badge"/>
    </columns>
</dataGrid>
```

```java
@Supply(to = "bootConfigGrid.badge", subject = "renderer")
private Renderer<KnobRow> bootConfigBadgeRenderer() {
    return new ComponentRenderer<>(row -> {
        Span badge = uiComponents.create(Span.class);
        badge.setText(messages.getMessage("aiUiSettings.bootConfig.badge.requiresRestart"));
        badge.getElement().getThemeList().add("badge");
        return badge;
    });
}
```

`[VERIFIED: project memory feedback_jmix_datagrid_renderer + existing usages in audit-event-list-view + chat-panel-fragment]`

## Recommendations for Claude's Discretion Items

### 7. Default open-weights catalog seed (3-4 entries)

Per project memory `project_self_hostable_models_only` — Apache 2.0+ open-weights only; exclude Qwen3.6 Plus/Flash, GPT-4o, Claude, Gemini Pro.

**Recommended default seed** (planner to verify availability via OpenRouter at plan time):

| Slug | Label key | Default? | License | Self-hostable |
|------|-----------|----------|---------|---------------|
| `qwen/qwen3.6-35b-a3b` | `chatModelCatalog.qwen3_35b` | ✓ | Apache 2.0 | Yes |
| `meta-llama/llama-3.3-70b-instruct` | `chatModelCatalog.llama3_3_70b` | — | Llama 3.x Community License | Yes |
| `mistralai/mistral-small-3.1-24b-instruct` | `chatModelCatalog.mistral_small_3_1` | — | Apache 2.0 | Yes |
| `deepseek/deepseek-v3.1` | `chatModelCatalog.deepseek_v3_1` | — | MIT | Yes |

`[ASSUMED]` — exact slug availability on OpenRouter changes weekly. Planner should curl `https://openrouter.ai/api/v1/models` at plan time and pick the current preferred 3 alongside `qwen/qwen3.6-35b-a3b`.

**Note on Llama license:** Llama 3.x is "open-weights" with the Llama Community License — most operator surveys treat this as open-weights-acceptable; if the project memory's "Apache 2.0+" wording is strict, drop Llama and add a third Mistral/Qwen variant.

### 8. Bean-validation bounds per Tier-1 knob

| Column | Type | Validation | Sentinel | Rationale |
|--------|------|------------|----------|-----------|
| `TASK_FILE_TTL_SECONDS` | Long | `@Min(-1)` (sentinel) `@Max(7 * 86_400)` (7 days) | `-1` disables | Phase 13.1 contract; 7-day cap prevents accidental forever |
| `TASK_FILE_PER_TURN_MAX_FILES` | Integer | `@Min(-1)` `@Max(100)` | `-1` disables | Existing default 10; cap aligns with `TOOL-06 find_records max=100` |
| `TASK_FILE_PER_TURN_MAX_TOTAL_BYTES` | Long | `@Min(-1)` `@Max(500L * 1024 * 1024)` (500 MB) | `-1` disables | Default 50 MB; hard cap defends against accidental DoS |
| `MUTATION_CONFIRMATION_REQUIRED` | Boolean | (no bounds) | — | UX hint only |
| `MUTATION_IDEMPOTENCY_TTL_SECONDS` | Long | `@Min(60)` `@Max(7 * 86_400)` | — | 1 min ≤ TTL ≤ 7 days; idempotency must be meaningful, no sentinel |
| `MUTATION_BULK_MAX_ROWS` | Integer | `@Min(1)` `@Max(500)` | — | Existing default 100; floor 1; ceiling 500 (Phase 13 D-02 DoS guard) |
| `PROMPT_ENTITY_INVENTORY_LIMIT` | Integer | `@Min(1)` `@Max(500)` | — | Existing default 100; aligns with `TOOL-06 find_records max=100` |
| `TOOLS_MAX_FILTER_DEPTH` | Integer | `@Min(1)` `@Max(10)` | — | Default 3; deeper filters risk SQL plan explosion |
| `TITLE_MAX_CONTEXT_MESSAGES` | Integer | `@Min(1)` `@Max(50)` | — | Default 6; cap prevents memory blowout on long convs |
| `TITLE_MIN_ASSISTANT_MESSAGES_TRIGGER` | Integer | `@Min(1)` `@Max(10)` | — | Default 1 |
| `UPLOAD_MAX_FILE_SIZE_BYTES` | Long | `@Min(1024)` `@Max(500L * 1024 * 1024)` | — | Default 100 MB; floor 1 KiB; ceiling 500 MB |

Validation messages keyed via `messages.properties`: `aiUiSettings.validation.taskFileTtl.range`, etc.

### 9. Sentinel `-1` invariant — `TtlConfigSentinelSkipsCleanupTest`

**File:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigTest.java` (contains three sibling test classes: `TtlConfigTest`, `TtlConfigSentinelSkipsCleanupTest`, `TtlConfigFkCascadeUnderSentinelTest`).

**Current assertions in `TtlConfigSentinelSkipsCleanupTest.sentinelMinusOneSkipsCleanup()`:**
1. `taskFileProperties.getTtlSeconds() == -1L` — bean reflects sentinel from `@SpringBootTest(properties = "ai-agent.task-file.ttl-seconds=-1")`.
2. After two `cleanupJob.deleteExpiredTaskFiles()` invocations, row whose `expiresAt` is `OffsetDateTime.now().minusHours(1)` (already past) MUST still exist.
3. `repository.deleteAllExpired(OffsetDateTime.now())` (the opportunistic chat-cleanup path) MUST return 0 deletions.
4. `resolver.resolveActive(conversationId)` MUST return non-empty media (sentinel disables active-row expiry filtering).

**Phase 16 impact:** The sentinel value source-of-truth shifts from `module.properties` (read directly off `taskFileProperties.getTtlSeconds()`) to `AiUiSettingsResolver.resolveTaskFileTtl()`. **The test MUST continue to pass unchanged**. The resolver must return `-1` when:
- AiUiSettings row has `TASK_FILE_TTL_SECONDS = -1` (DB-set sentinel), OR
- AiUiSettings row has `TASK_FILE_TTL_SECONDS = null` AND `taskFileProperties.getTtlSeconds() == -1` (property-set sentinel falls through).

The cleanup-job + repository + resolver consumers must all call `aiUiSettingsResolver.resolveTaskFileTtl()` instead of `taskFileProperties.getTtlSeconds()` directly — the resolver enforces the DB→property→constant chain. Phase 16's test plan should add a NEW test class `TtlConfigSentinelSurvivesAiUiSettingsTest` that sets the sentinel via DB (not properties) and asserts the same three invariants. The existing `TtlConfigSentinelSkipsCleanupTest` continues to set the sentinel via properties — both must pass.

**Same invariant applies to:** `TASK_FILE_PER_TURN_MAX_FILES` and `TASK_FILE_PER_TURN_MAX_TOTAL_BYTES`. Their consumer is `AiTaskFileMediaResolver.resolveActive(...)`; the existing `BudgetCapSentinelTest` covers them and must pass unchanged when consumers swap to `aiUiSettingsResolver.resolveTaskFilePerTurnMaxFiles()` / `resolveTaskFilePerTurnMaxTotalBytes()`.

`[VERIFIED: D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigTest.java + compiled siblings BudgetCapSentinelTest.class + TtlConfigFkCascadeUnderSentinelTest.class]`

### 10. `KnobInventoryScanner` classpath placement

**Recommendation: `ai-agent-starter` autoconfig classpath.**

Rationale:
- `KnobInventoryScanner` only serves the admin UI. A host app that wants the AI runtime without the admin UI surface (rare but possible — e.g., a headless integration) shouldn't pull boot-time reflection over every `@ConfigurationProperties` bean.
- `ai-agent-starter` already owns `AIAutoConfiguration` + `SpiDefaultsAutoConfiguration` (per `TtlConfigTest` `@ImportAutoConfiguration` block — lines 41-44 of the test). Adding a `KnobInventoryAutoConfiguration` there fits the precedent.
- The `@KnobMetadata` annotation itself stays in `ai-agent` (library classpath) so records can annotate their components.

If a future host wants admin UI WITHOUT the autoconfig scanner (custom inventory source), they can `@ConditionalOnMissingBean(KnobInventory.class)` exclude it. Default behavior: starter wires it up.

## File-by-File Scout

### Files to MODIFY (existing)

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java`
**Current shape:** 175 lines, JPA entity with `enabledSurfaceIds` (String) + `defaultSurface` (String enum) + audit fields. Singleton ID `00000000-0000-0000-0000-000000120001`.
**Minimal-diff change:** Add ~10 nullable JPA columns (D-01) each as `@Column(name="...", nullable=true)` Long/Integer/Boolean fields with Jakarta `@Min`/`@Max` annotations. Add getter/setter pairs. NO `@Transient` bridges needed (flat columns bind directly to `<integerField property="...">`). Watch for the EclipseLink-weaver trap documented in `setEnabledSurfaceSet` (lines 90-96): direct field assignment is fine when the setter name MATCHES the field name (the `setDefaultSurface` pattern at line 102-108 documents this).

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java`
**Current shape:** 244 lines, `@Component`, exposes `resolveActive()`, `effectiveModel(...)`, `effectiveTemperature`, `effectiveTopP`, `effectiveMaxTokens`, `effectiveRagTopK`, `effectiveRagSimilarityThreshold`, `effectiveSystemPrompt`. DB → bodyYaml → `AiAgentDefaultsProperties` fallback chain via `parseBody` (snakeyaml).
**Minimal-diff change:** NONE. Phase 16 keeps `ragTopK` + `ragSimilarityThreshold` per-profile so this resolver is untouched. The new `AiUiSettingsResolver` is a sibling, NOT an edit to this file.

#### `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml`
**Current shape:** 69 lines, tabSheet with `formTab` + `yamlTab`. `modelField` is `<textField id="modelField" required="true">` at lines 25-28.
**Minimal-diff change:** Replace lines 25-28 with `<comboBox id="modelField" allowCustomValue="true" label="..." required="true" requiredMessage="..."/>`. The controller's existing onInit + value-write logic (need to scout the Java side) handles the model write to `bodyYaml.model`.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java`
**Current shape:** 117 lines, `@ViewController("AiAgent_AiUiSettings.detail")`, `@EditedEntityContainer("uiSettingsDc")`. `onInit` populates `enabledSurfacesField` + `defaultSurfaceField`. `onReady` syncs field changes to entity. `onBeforeSave` validates enabled/default consistency.
**Minimal-diff change:** Add field declarations for each new Tier-1 form field (`@ViewComponent private JmixIntegerField taskFileTtlField;` etc.). Add `@Autowired KnobInventory knobInventory` + bind boot-config grid + secrets grid in `onInit`. Add `bootConfigBadgeRenderer()` `@Supply` method. Extend `onBeforeSave` with bean-validation error mapping for new fields (Jakarta `@Min`/`@Max` triggers a single localised toast per violated field).

#### `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml`
**Current shape:** 42 lines, flat `<formLayout>` with `enabledSurfacesField` (checkboxGroup) + `defaultSurfaceField` (radioButtonGroup). No tabSheet.
**Minimal-diff change:** Wrap existing form in a new `<tabSheet>` with `<tab id="generalTab">` containing the current form (keep existing controls untouched). Add `<tab id="tier1KnobsTab">` with sectioned `<formLayout>` for each Tier-1 cluster (task-file / mutation / prompt+tools / title / upload). Add `<tab id="bootConfigTab">` with `<dataGrid id="bootConfigGrid">` + `<column key="key|value|badge">`. Add `<tab id="secretsTab">` (or sibling section under bootConfigTab — planner's call) with `<dataGrid id="secretsGrid">` + `<column key="key|configured">`. Add the in-memory `<collection>` data containers for `KnobRow` rows backing the boot-config + secrets grids.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
**Current shape:** 964 lines. `executeBlockingTurn(...)` at lines 358-449. The `chatClient.prompt()...call().chatClientResponse()` chain at lines 383-412.
**Minimal-diff change:** Wrap lines 383-412 in a try/catch:
```java
ChatClientResponse clientResp;
try {
    clientResp = buildAndCall(model, ...);
} catch (NonTransientAiException badModel) {
    if (!isBadModelException(badModel)) throw badModel;
    auditModelValidationFailure(runId, userId, convId, model, badModel);
    String fallbackModel = parametersResolver.effectiveModel(parametersResolver.resolveActive());
    // Use FALLBACK profile model (not the offending one); the saved profile is NOT mutated.
    clientResp = buildAndCall(fallbackModel, ...);
    auditModelFallbackApplied(runId, userId, convId, model, fallbackModel);
    // Reissue's response continues normal flow below.
}
```
Extract a `private ChatClientResponse buildAndCall(String activeModel, ...)` helper to avoid duplicating the 30-line builder. Add `private boolean isBadModelException(Throwable t)` walker. Add `private void auditModelValidationFailure(...)` + `private void auditModelFallbackApplied(...)` helpers wrapping existing `auditWriter.writeToolCall(...)`.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditKind.java`
**Current shape:** 16 lines, constants `CHAT`, `TOOL`, `RETRIEVAL`. Comment says "open-ended String values — hosts may introduce additional kinds without schema change".
**Minimal-diff change:** Add one constant: `public static final String MODEL_VALIDATION_FAILURE = "MODEL_VALIDATION_FAILURE";`. Comment note: 16-char varchar KIND column (per the docstring); confirm "MODEL_VALIDATION_FAILURE" fits — it's 24 characters, so this exceeds the column width. **Open question:** widen the KIND column in changelog 120 OR use a shorter tag like `MODEL_FAIL` (10 chars). Planner decides.

#### `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` + `messages_vi.properties`
**Current shape:** Existing `parametersDetail.*` and `aiUiSettings.*` keys.
**Minimal-diff change:** Add all D-09 keys (see CONTEXT.md for the full list). Locale parity test must pass (`LocaleParityTest` extension).

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`
**Current shape:** 63 lines. Has `@EntityPolicy(entityClass = AiUiSettings.class, actions = {READ, UPDATE})`.
**Minimal-diff change:** Possibly add `@EntityAttributePolicy` MODIFY=DENY for `AiAgentUserRole` (existing non-admin role) on every new Tier-1 attribute — but Tier-1 attrs only render in the AiUiSettings detail view which is already admin-only. Safer: leave the admin role unchanged; non-admins can't reach `AiAgent_AiUiSettings.detail` view (no MenuPolicy / ViewPolicy for non-admins). **Planner verifies** by checking that `AiAgentUserRole` does NOT include `AiAgent_AiUiSettings.detail` in its `@ViewPolicy`.

### Files to CREATE (new)

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiUiSettingsResolver.java`
Sibling to `AiParametersResolver`. Methods: `resolveTaskFileTtl()`, `resolveTaskFilePerTurnMaxFiles()`, `resolveTaskFilePerTurnMaxTotalBytes()`, `resolveMutationConfirmationRequired()`, `resolveMutationIdempotencyTtlSeconds()`, `resolveMutationBulkMaxRows()`, `resolvePromptEntityInventoryLimit()`, `resolveToolsMaxFilterDepth()`, `resolveTitleMaxContextMessages()`, `resolveTitleMinAssistantMessagesTrigger()`, `resolveUploadMaxFileSizeBytes()`. Constructor injects `UnconstrainedDataManager` + the 8 `@ConfigurationProperties` beans needed for fallback. Each method: load `AiUiSettings.SINGLETON_ID` → return column-value (non-null) ELSE properties-value → ELSE constant default.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiSettingsChangedEvent.java`
```java
public class AiSettingsChangedEvent extends ApplicationEvent {
    public enum Kind { PARAMETERS, UI_SETTINGS }
    private final Kind kind;
    public AiSettingsChangedEvent(Object source, Kind kind) { super(source); this.kind = kind; }
    public Kind getKind() { return kind; }
}
```

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java`
Mirror `AiExposureRuleEntityListener` shape; guard `active=true`.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiUiSettingsEntityListener.java`
Mirror `AiExposureRuleEntityListener` shape; fires on every singleton save.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobMetadata.java`
```java
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface KnobMetadata {
    Tier tier();
    boolean requiresRestart() default false;
    String displayMessageKey() default "";
    enum Tier { TIER_1, TIER_2, TIER_3 }
}
```

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventoryScanner.java`
`@Component` listening for `ApplicationReadyEvent`. Walks `ConfigurationPropertiesBean.getAll(applicationContext)`. Reflects each bean for `@KnobMetadata`. Builds an immutable `KnobInventory` and publishes it via a setter on the existing `KnobInventory` `@Component`.

**Placement:** `ai-agent-starter` autoconfig classpath per Recommendation #10. The scanner needs `ApplicationContext` injection and runs once at boot.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java`
`@Component` exposing `tier2()` (List<KnobRow>) + `tier3()` (List<SecretIndicatorRow>). Backed by an `AtomicReference<State>` written by `KnobInventoryScanner` at `ApplicationReadyEvent`.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalogProperties.java`
```java
@ConfigurationProperties("jmix.ai-agent.models")
public record ChatModelCatalogProperties(List<Entry> catalog) {
    public record Entry(String id, String labelMessageKey, Boolean isDefault) {}
}
```

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java`
`@Component` validating "exactly one default" at `@PostConstruct`. Exposes `entries()`, `defaultEntry()`, `findById(String)`. Holds the `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` `Set<String>` constant.

#### `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AdminSecretPatternProperties.java`
```java
@ConfigurationProperties("ai-agent.admin")
public record AdminSecretPatternProperties(List<String> secretPropertyPatterns) {
    public List<String> resolvedPatterns() {
        return secretPropertyPatterns == null || secretPropertyPatterns.isEmpty()
                ? List.of("*.api-key", "*.password", "*.secret", "*.token")
                : secretPropertyPatterns;
    }
}
```

#### `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/120-ai-ui-settings-tier1-knobs.xml`
Single `<changeSet>` with `<addColumn tableName="AI_UI_SETTINGS">` adding ~10 nullable columns. Mirrors the type set used by Phase 12 `080-ai-ui-settings.xml`.

### Test files to CREATE

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java` (TEST-20)
Three test methods:
1. `catalogSubsetOfAllowlist()` — every `ChatModelCatalog.entries().id()` is in `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST`.
2. `exactlyOneDefault()` — count of `isDefault()=true` entries == 1.
3. `defaultMatchesDefaultParamsYaml()` — `ChatModelCatalog.defaultEntry().id()` == `AiAgentDefaultsProperties.model()` (read via Spring context).

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/SecretRedactionInvariantsTest.java` (SEC-08)
Two test legs:
1. `noSecretBoundEditable()` — reflective scan of all `*-detail-view.xml` and other view descriptors: parse XML, walk every `<*Field/textField/comboBox/checkbox/checkboxGroup/radioButtonGroup/etc.>` element with `property="..."`, cross-reference property name against secret patterns (`*.api-key`, `*.password`, `*.secret`, `*.token`). Fail build if any match.
2. `noConditionalOnPropertyToggleBoundEditable()` — reflective scan: walk classpath for `@ConditionalOnProperty` annotations, collect their key names, cross-reference against XML view descriptors' `property="..."` bindings. Fail build if any match.

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventPublicationTest.java`
Four `@SpringBootTest` test methods:
1. `activeParametersSavePublishesPARAMETERS()` — save active `AiParameters` row; capture events via `@MockBean ApplicationEventPublisher` OR `@RecordApplicationEvents`; assert exactly one `AiSettingsChangedEvent(kind=PARAMETERS)`.
2. `inactiveParametersSavePublishesZero()` — save inactive `AiParameters` row; assert zero events.
3. `uiSettingsSavePublishesUI_SETTINGS()` — save `AiUiSettings.SINGLETON_ID`; assert exactly one event.
4. `singlePublishSiteSourceScan()` — reflective scan asserts only `AiParametersEntityListener` + `AiUiSettingsEntityListener` reference `AiSettingsChangedEvent` on the publish path; no views / services do.

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsResolverFallthroughTest.java`
Per-cluster integration test (RAG, task-file, mutation, prompt/tools, title, upload): set DB column to a specific value, assert resolver returns that value; null the column, assert resolver returns `module.properties` value; remove property, assert resolver returns constant default.

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsBeanValidationTest.java`
Per-knob out-of-range save attempt → `ConstraintViolationException`.

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java`
New test class mirroring `TtlConfigSentinelSkipsCleanupTest` but setting sentinel via `AiUiSettings.taskFileTtlSeconds=-1` instead of `module.properties`. All three invariants must hold.

#### `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/chat/ModelValidationFailureFallbackTest.java`
Stub the chat client with a `NonTransientAiException` wrapping a 404 response; assert:
1. First call raises the exception.
2. `executeBlockingTurn` catches it.
3. Second `ChatClient` call uses the `default-params.yaml` model.
4. Two audit rows are written (MODEL_VALIDATION_FAILURE + RUN_TURN/CHAT) sharing the same `runId`.
5. The saved `AiParameters.bodyYaml.model` is unchanged after the failure.

## `@ConfigurationProperties` Records Inventory (Confirmed)

**Total: 10 records** in the `ai-agent` library (more than CONTEXT.md's "7"). Disposition per `@KnobMetadata` annotation pass:

| # | Class | Prefix | Tier | Notes |
|---|-------|--------|------|-------|
| 1 | `AiAgentRagProperties` | `jmix.ai-agent.rag` | Mixed | `topK` + `similarityThreshold` already Tier-1 (in `AiParameters.bodyYaml`); `splitter.*` + `ingestExecutor.*` Tier-2; `upload.maxFileSizeBytes` Tier-1 → migrate to `AiUiSettings.UPLOAD_MAX_FILE_SIZE_BYTES`; `sampleIngester.enabled` Tier-2 boot toggle |
| 2 | `AiAgentMutationProperties` | `ai-agent.tools.mutation` | Mixed | `enabled` Tier-2 boot toggle (`@ConditionalOnProperty` consumer); `allowDelete` Tier-2; `confirmationRequired` Tier-1 → migrate; `idempotencyTtl` Tier-1 → migrate; `bulkMaxRows` Tier-1 → migrate |
| 3 | `AiAgentAuditProperties` | `jmix.ai-agent.audit` | Tier-2 | `hashSensitiveFields` boot-time hash decision; `sensitiveFields` set is read at audit-row write — could be Tier-1 but no operator demand. Keep Tier-2 |
| 4 | `AiAgentGuardProperties` | `jmix.ai-agent.guard` | Tier-2 | `rateLimit.*`, `tokenBreaker.*`, `iterationCap.*` already exposed via `AiParameters` chat behavior; `outputScanner.patterns` Tier-2 (boot regex compile); host-prefix/tool-name-leak pack toggles Tier-2 |
| 5 | `AiAgentPromptProperties` | `jmix.ai-agent.prompt` | Tier-1 | `entityInventory.limit` Tier-1 → migrate to `AiUiSettings.PROMPT_ENTITY_INVENTORY_LIMIT` |
| 6 | `AiAgentTitleProperties` | `ai-agent.conversation-title` | Mixed | `enabled` Tier-2 (`@ConditionalOnProperty`); `modelId` Tier-2 (advisor wiring); `maxContextMessages` Tier-1 → migrate; `minAssistantMessagesTrigger` Tier-1 → migrate; `executor.*` Tier-2 (thread-pool hazard) |
| 7 | `AiAgentDefaultsProperties` | `jmix.ai-agent.defaults` | Tier-2 | Used by `AiParametersResolver` fallback when no active row exists; not directly admin-editable since `AiParameters` itself is the editable layer |
| 8 | `AiTaskFileProperties` | `ai-agent.task-file` | Tier-1 | All 4 fields Tier-1 → migrate (`ttlSeconds`, `maxFileSizeBytes`, `perTurnMaxFiles`, `perTurnMaxTotalBytes`). Sentinel `-1` semantics preserved per Phase 13.1 contract |
| 9 | `AiAgentEmbeddingProperties` | `jmix.ai-agent.embedding` | Tier-2 | `model` + `dimensions` pinned to PgVectorStore schema — DIMENSION CHANGE REQUIRES FULL REINGEST. Stays Tier-2 |
| 10 | `AiExtractionProperties` | `ai-agent.extraction` | Tier-2 | `ttlSeconds` + `cleanupIntervalMs` for extraction draft cleanup; not user-facing — keep Tier-2 |

**`@KnobMetadata` annotation pass scope:** Annotate all 10 records' components. Tier-1 components carry `tier=TIER_1` + `displayMessageKey`. Tier-2 components carry `tier=TIER_2 requiresRestart=true` + `displayMessageKey`. Tier-3 components don't exist in any of these records (no secrets stored) — they'll be picked up by the secret-pattern mask via `Environment.getProperty`.

**Important:** Migration to `AiUiSettings` does NOT delete the `module.properties` defaults. The properties remain as the **fallback layer** when the singleton column is null. The defaults file (`module.properties`) is byte-identical post-phase.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Configuration bean introspection | Custom classpath scanner | `ConfigurationPropertiesBean.getAll(ApplicationContext)` | Built-in since Spring Boot 2.2; handles all binding shapes (direct annotation, `@Bean` factory, constructor binding); no Actuator dependency |
| Entity-change event publication | Spring `@EventListener` on every save site, or JPA `@PostPersist` | Jmix `EntityChangedEvent` + entity listener `@Component` | Jmix DataManager fires `EntityChangedEvent` reliably across Jmix save paths; JPA lifecycle annotations are unreliable for Jmix per `AiExposureRuleEntityListener` docstring |
| Per-request model override | `ChatClient.mutate()` rebuild | `ChatOptions.builder().model(slug).build()` on the existing prompt | Already in place at `DefaultChatServiceImpl` line 405-410; per-request `.options(...)` is the documented Spring AI pattern |
| Singleton-entity load | Try/catch on `DataManager.load(...).id(SINGLETON_ID).one()` | `unconstrainedDataManager.load(...).id(...).optional().orElseGet(create)` | `AiUiSettingsService.loadCurrent()` documents this pattern; reuse verbatim |
| Provider error classification | String-match the exception message | Status code + cause walk + class-name check | Provider messages drift across versions; status code is structural |
| Curated catalog persistence | New JPA entity + CRUD view | `@ConfigurationProperties` record + Java `Set<String>` allowlist | Catalog drift is rare; properties-driven catalog avoids `feedback_pragmatic_modules` violation |
| ComboBox custom-value plumbing | Manual `Component.addValueChangeListener` | `<comboBox allowCustomValue="true">` + `@Subscribe("modelField") onCustomValueSet` | Documented Jmix pattern (Context7); handles dropdown vs free-text uniformly |
| `<dataGrid>` cell renderers | Programmatic `getColumnByKey().setRenderer()` | `@Supply(to="grid.col", subject="renderer")` | Project memory `feedback_jmix_datagrid_renderer` |
| Secret value redaction | Hash the secret | `Environment.getProperty(key)` non-blank check only; render "configured: yes/no" | Hashing a secret in the UI is itself a leak (timing oracle); pure presence-check is safe |

**Key insight:** This phase is **plumbing, not novel construction**. Every piece has a precedent in the codebase that the planner can extract a code excerpt from. The planner's job is to wire the precedents into 5-6 vertically-sliced waves, not to design new abstractions.

## Common Pitfalls

### Pitfall 1: EclipseLink-weaver setter-name trap on new `AiUiSettings` columns
**What goes wrong:** A new Tier-1 column on `AiUiSettings` with a setter whose name doesn't match the field name (e.g., a `@Transient` getter bridge over a private String holding a serialized value) silently bypasses EclipseLink's weaving — the DataContext snapshot diff doesn't detect the change, and the UPDATE never fires. The user clicks Save, the form clears, but the value reverts on revisit.
**Why it happens:** EclipseLink weaves the setter that matches the JPA field name. The `setEnabledSurfaceSet` method on `AiUiSettings` (lines 90-96) documents this — it routes through `setEnabledSurfaceIds` (the real setter) because the property accessor name doesn't match.
**How to avoid:** D-01 says "flat columns", which by definition gives every Tier-1 knob a field-name-matching setter. NO `@Transient` bridges, NO computed getters. If a knob needs type coercion (e.g., Duration ↔ seconds), do the coercion in the RESOLVER, not in the entity.
**Warning signs:** Field saves don't persist; integration test asserts `aiUiSettings.getTaskFileTtl() == 42` but the row in DB still shows `null` after `dataManager.save(settings)`.

### Pitfall 2: `AiSettingsChangedEvent` fires twice from view + listener
**What goes wrong:** A view controller injects `ApplicationEventPublisher` and calls `publishEvent(new AiSettingsChangedEvent(...))` on save, AND the entity listener also publishes — the event fires twice per save. Phase 18 cache evicts twice per edit; possibly fine, but the SEC-08 source-scan test will catch this and fail the build.
**Why it happens:** Plan 10-06 R2 documented this exact pitfall for `AiExposureRuleEntityListener` (line 18-19 docstring: "view controllers must NOT call applicationEventPublisher.publishEvent ... directly, or the event fires twice per save").
**How to avoid:** SEC-08-adjacent source-scan test: reflective check that ONLY `AiParametersEntityListener` and `AiUiSettingsEntityListener` reference `AiSettingsChangedEvent` on the publish path. Reject any view / service injection.
**Warning signs:** Phase 18 cache eviction count > 1 per save; test using `@RecordApplicationEvents` shows 2 events per save.

### Pitfall 3: Resolver loads singleton via constrained `DataManager` — admin-bypass row-level security trips
**What goes wrong:** A non-admin user makes a chat turn → resolver calls `dataManager.load(AiUiSettings.class).id(SINGLETON_ID).one()` → `DataManager` checks `AiAgentUserRole` → user has no READ on `AiUiSettings` → resolver throws → chat turn fails.
**Why it happens:** `DataManager` enforces row-level security; the AI runtime's resolver needs to consult settings regardless of caller role.
**How to avoid:** Always inject `UnconstrainedDataManager` for the singleton load in `AiUiSettingsResolver`. Same pattern as `AiUiSettingsService.loadCurrent()` (lines 23-28).
**Warning signs:** Non-admin chat turn raises `AccessDeniedException` mentioning `AiUiSettings`; admin-only turns work fine.

### Pitfall 4: ConfigurationPropertiesBean.getAll returns beans NOT yet bound at `@PostConstruct`
**What goes wrong:** `KnobInventoryScanner` reflects records at `@PostConstruct` time; some records aren't fully bound yet (Spring boot binding happens in a specific order); the scanner sees `null` getters for valid configured properties.
**Why it happens:** `@PostConstruct` ordering for cross-bean dependencies is fragile.
**How to avoid:** Listen for `ApplicationReadyEvent` (fires AFTER all binding completes). The scanner stores its result in a singleton `KnobInventory` `@Component` that the view reads via `@Autowired`.
**Warning signs:** Boot config tab shows blank values for some keys despite `module.properties` having them set.

### Pitfall 5: `AuditKind.MODEL_VALIDATION_FAILURE` value exceeds the 16-char VARCHAR
**What goes wrong:** `AuditKind.java` docstring says "the underlying KIND column is varchar(16)". `"MODEL_VALIDATION_FAILURE"` is 24 chars. Save throws on column-width violation.
**Why it happens:** Spec/CONTEXT.md chose the long name without checking column width.
**How to avoid:** EITHER (a) the new changelog 120 widens `AI_AUDIT_EVENT.KIND` to varchar(32) — DDL-safe additive change — OR (b) use a shorter constant like `AuditKind.MODEL_FAIL`. Recommendation: **widen the column to varchar(32)** so future audit kinds aren't constrained. Add to changelog 120 alongside the AiUiSettings columns.
**Warning signs:** Integration test for D-05 fallback raises a JPA persistence exception on save.

### Pitfall 6: Bad-model classification false-positive on transient network errors
**What goes wrong:** A 502 / 504 from the provider gateway returns `RestClientResponseException` with body containing the word "model"; D-05 catch fires, falls back to `default-params.yaml.model`; user's actual model selection was fine but the network blip got mistranslated into a fallback.
**Why it happens:** Generic exception-message matching catches too much.
**How to avoid:** Strict classification: status code ∈ {400, 404, 422} AND (the substring `"model"` appears in the body OR the message). 5xx responses are NOT model-validation failures — let them propagate to `NonTransientAiException`'s normal retry path.
**Warning signs:** Audit rows show MODEL_VALIDATION_FAILURE for the same active model intermittently (every 10th turn or so under flaky network).

### Pitfall 7: Locale parity test breaks on Vietnamese-specific punctuation
**What goes wrong:** `messages_vi.properties` uses a different ellipsis character (`…` vs `...`) and the locale-parity test does naive `messages_en.keySet().equals(messages_vi.keySet())` — passes — but a downstream view test asserting exact message text fails for `vi` locale.
**Why it happens:** `LocaleParityTest` only checks key parity, not value consistency.
**How to avoid:** Test parity = key parity (NOT value parity). Value parity is impossible (it's a translation). Make sure both files have the SAME KEY SET — add every new D-09 key to BOTH files at the same time.
**Warning signs:** `LocaleParityTest` fails reporting "key `aiUiSettings.tab.bootConfig` missing from messages_vi.properties".

## Code Examples

### Pattern 1: Entity listener publishing `AiSettingsChangedEvent`

Source: precedent from `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java`

```java
@Component
public class AiUiSettingsEntityListener {

    private final ApplicationEventPublisher eventPublisher;

    public AiUiSettingsEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onAiUiSettingsChanged(EntityChangedEvent<AiUiSettings> event) {
        // Singleton entity — every save fires the event.
        eventPublisher.publishEvent(new AiSettingsChangedEvent(this, Kind.UI_SETTINGS));
    }
}
```

```java
@Component
public class AiParametersEntityListener {

    private final ApplicationEventPublisher eventPublisher;
    private final DataManager dataManager;

    public AiParametersEntityListener(ApplicationEventPublisher eventPublisher, DataManager dataManager) {
        this.eventPublisher = eventPublisher;
        this.dataManager = dataManager;
    }

    @EventListener
    public void onAiParametersChanged(EntityChangedEvent<AiParameters> event) {
        boolean wasActiveBeforeOrAfter;
        if (event.getType() == EntityChangedEvent.Type.DELETED) {
            Boolean oldActive = event.getChanges().getOldValue("active");
            wasActiveBeforeOrAfter = Boolean.TRUE.equals(oldActive);
        } else {
            AiParameters current = dataManager.load(event.getEntityId()).one();
            wasActiveBeforeOrAfter = Boolean.TRUE.equals(current.getActive());
        }
        if (wasActiveBeforeOrAfter) {
            eventPublisher.publishEvent(new AiSettingsChangedEvent(this, Kind.PARAMETERS));
        }
    }
}
```

### Pattern 2: ChatModelCatalog with allowlist constant

```java
@Component
public class ChatModelCatalog {

    public static final Set<String> SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST = Set.of(
            // See project memory project_self_hostable_models_only — Apache 2.0+ open-weights only.
            "qwen/qwen3.6-35b-a3b",
            "meta-llama/llama-3.3-70b-instruct",
            "mistralai/mistral-small-3.1-24b-instruct",
            "deepseek/deepseek-v3.1"
    );

    private final ChatModelCatalogProperties properties;
    private final AiAgentDefaultsProperties defaults;
    private List<Entry> entries;
    private Entry defaultEntry;

    public ChatModelCatalog(ChatModelCatalogProperties properties, AiAgentDefaultsProperties defaults) {
        this.properties = properties;
        this.defaults = defaults;
    }

    @PostConstruct
    void validate() {
        List<ChatModelCatalogProperties.Entry> raw = properties.catalog() == null
                ? List.of() : properties.catalog();
        if (raw.isEmpty()) {
            // Seed default catalog when host hasn't configured one.
            raw = defaultSeed();
        }
        long defaultCount = raw.stream().filter(e -> Boolean.TRUE.equals(e.isDefault())).count();
        if (defaultCount != 1) {
            throw new IllegalStateException(
                    "ChatModelCatalog must have exactly one default; found " + defaultCount);
        }
        // Drift gate: the marked default MUST equal default-params.yaml.model.
        // (TEST-20 enforces this independently; @PostConstruct fail-fast for boot safety.)
        this.entries = raw.stream().map(e -> new Entry(e.id(), e.labelMessageKey(), Boolean.TRUE.equals(e.isDefault()))).toList();
        this.defaultEntry = entries.stream().filter(Entry::isDefault).findFirst().orElseThrow();
    }

    public List<Entry> entries() { return entries; }
    public Entry defaultEntry() { return defaultEntry; }
    public Entry findById(String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    public record Entry(String id, String labelMessageKey, boolean isDefault) {}
}
```

### Pattern 3: Catch + reissue inside `executeBlockingTurn`

```java
private ChatClientResponse buildAndCall(String chosenModel, ...) {
    return chatClient.prompt()
            .system(systemPromptWithDocs)
            .user(u -> { u.text(message); if (!resolvedMedia.media().isEmpty()) u.media(...); })
            .toolCallbacks(toolCallbacks.callbacksFor(userId, convId, toolSurfaceIntentId))
            .toolContext(auditToolContext(runId, convId))
            .advisors(advisorSpec -> { ... })
            .options(ChatOptions.builder()
                    .model(chosenModel)
                    .temperature(...)
                    .topP(...)
                    .maxTokens(...)
                    .build())
            .call()
            .chatClientResponse();
}

// Inside executeBlockingTurn:
ChatClientResponse clientResp;
try {
    clientResp = buildAndCall(model, ...);
} catch (NonTransientAiException badModel) {
    if (!isBadModelException(badModel)) throw badModel;
    auditModelValidationFailure(runId, userId, convId, model, badModel);
    // Read the seed default ONLY (not the active profile's model — the active profile's model
    // is what just failed). The seed comes from default-params.yaml via AiAgentDefaultsProperties.
    String fallbackModel = parametersResolver.effectiveModel(parametersResolver.buildSyntheticDefaults());
    clientResp = buildAndCall(fallbackModel, ...);
}
```

**Note:** the resolver may need a new method `buildSyntheticDefaults()` exposing the fallback-only path; alternatively, inject `AiAgentDefaultsProperties` directly into `DefaultChatServiceImpl` and read `defaults.model()`. Planner decides — cleanest is to add `parametersResolver.fallbackModel()` returning `defaults.model()`.

### Pattern 4: ComboBox with custom-value support

Source: confirmed via Context7 `/jmix-framework/jmix-context7` — `combobox-user-input.md`

```xml
<comboBox id="modelField"
          label="msg:///parametersDetail.field.model"
          allowCustomValue="true"
          required="true"
          requiredMessage="msg:///parametersDetail.validation.modelRequired"/>
```

```java
@ViewComponent private JmixComboBox<String> modelField;
@Autowired private ChatModelCatalog catalog;
@Autowired private Messages messages;

@Subscribe
public void onInit(InitEvent event) {
    modelField.setItems(catalog.entries().stream().map(ChatModelCatalog.Entry::id).toList());
    modelField.setItemLabelGenerator(id -> {
        ChatModelCatalog.Entry entry = catalog.findById(id);
        if (entry == null) return id;
        String label = messages.getMessage(entry.labelMessageKey());
        return entry.isDefault() ? label + " " + messages.getMessage("parametersDetail.modelField.defaultSuffix") : label;
    });
}

@Subscribe("modelField")
public void onModelFieldCustomValueSet(ComboBoxBase.CustomValueSetEvent<ComboBox<String>> event) {
    modelField.setValue(event.getDetail());  // store the custom string verbatim
}
```

## Runtime State Inventory

Phase 16 is a **schema-additive + new-feature** phase, not a rename/refactor. The Runtime State Inventory matrix is mostly empty by design:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | New singleton row in `AI_UI_SETTINGS` table; existing row's columns NULL until admin edit | New changelog `120-*.xml` adds nullable columns; null = use property fallback. No data migration of existing rows needed |
| Live service config | None — Phase 16 doesn't touch n8n / Datadog / external services | None |
| OS-registered state | None — no Task Scheduler / pm2 / systemd registrations | None |
| Secrets/env vars | None added by this phase; existing `spring.ai.openai.api-key` etc. are READ for indicator-only display | Verify no `*.api-key` / `*.password` / `*.secret` / `*.token` ENV name introduced; SEC-08 enforces |
| Build artifacts | None — no package rename, no `pyproject.toml` change | None |

**Nothing found in 4 of 5 categories — verified explicitly.**

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `module.properties`-only operator tuning | DB-backed `AiUiSettings` columns with read-through | This phase | Operator can retune without restart; Phase 18 caches with event-based invalidation |
| Free-text `modelField` `<textField>` | Curated `<comboBox allowCustomValue>` | This phase | Reduces typo-driven model errors; preserves custom-entry for hosts using non-curated providers |
| Free-text model validity unchecked until first call | Catch-and-reissue at first use with fallback | This phase | Single failed turn instead of broken profile; saved profile unchanged |
| No `AiSettingsChangedEvent` | Single typed event from 2 entity listeners | This phase | Phase 18 cache eviction surface ready |
| Tier-3 secrets indistinguishable from Tier-2 toggles | Pattern-matched mask + read-only indicator | This phase | SEC-08 enforces source-scan invariant |

**Deprecated/outdated:**
- The yaml-blob `bodyYaml` shape for `AiParameters` (carries `ragTopK` + `ragSimilarityThreshold`) — STAYS in Phase 16 per CONTEXT.md (per-profile RAG tuning is the right shape). Out-of-scope: blob-to-columns migration for `AiParameters`.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | OpenRouter returns 404 with `error.message` substring "model" for unknown slugs in 2026 | API Surface §3 | D-05 classification rule may miss provider-specific phrasings; mitigation: stub-based integration test + status-code primary filter |
| A2 | Llama 3.3 70B is acceptable under "Apache 2.0+ open-weights" rule | Recommendations §7 | If memory `project_self_hostable_models_only` is strictly Apache 2.0+, drop Llama; planner verifies with user during plan stage |
| A3 | `MODEL_VALIDATION_FAILURE` audit row carries verbatim model id (not hashed) | Pitfall 5 + Recommendations | If wrong, switch to `AuditFieldHasher.hash(modelId)` — same precedent as Phase 9 D-18 hash flow |
| A4 | `AuditKind.KIND` column is varchar(16) and needs widening to fit `MODEL_VALIDATION_FAILURE` | Pitfall 5 | Verify by reading `030-ai-audit-event.xml`; if already varchar(32+), no widening needed |
| A5 | The 3-4 catalog seed entries are currently available on OpenRouter | Recommendations §7 | Planner re-verifies at plan time via `curl https://openrouter.ai/api/v1/models` |
| A6 | Vietnamese locale parity test exists as `LocaleParityTest` | Pitfall 7 | If not yet, Phase 16 adds it; per spec.md Acceptance Criteria, the parity assertion is a phase deliverable |
| A7 | `KnobInventoryScanner` belongs in `ai-agent-starter` autoconfig | Recommendations §10 | If host wants admin UI without scanner, `@ConditionalOnMissingBean` escape hatch is trivial to add later |

## Open Questions

1. **Should the catch+reissue's reissue use the active profile's model resolved fresh, or strictly the seed default?**
   - What we know: CONTEXT.md D-05 says "reissue against the resolved `default-params.yaml` model". The seed default is read via `AiParametersResolver`'s synthetic-defaults path.
   - What's unclear: If the admin has just saved a bad model, the active profile still carries the bad model. Re-resolving via `parametersResolver.effectiveModel(parametersResolver.resolveActive())` would just return the same bad model and loop.
   - Recommendation: Add a `fallbackModel()` method on the resolver that returns `defaults.model()` directly, bypassing the profile body. Use that for reissue.

2. **Is the new `MODEL_VALIDATION_FAILURE` audit row a `AuditKind.TOOL`-like row, or a `AuditKind.CHAT`-like row?**
   - What we know: It's a chat-turn failure, not a tool failure. The closest semantic is `CHAT` with outcome `FAILED`.
   - What's unclear: Whether to reuse `AuditWriter.writeChatFinish(...)` with a different `outcome` string, or use `writeToolCall(...)` with a synthetic tool name like `GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME`.
   - Recommendation: Reuse `writeToolCall` with `CHAT_SENTINEL_TOOL_NAME` (precedent from `auditDenial` / `auditFlagged` at lines 840-866 of `DefaultChatServiceImpl`); set `kind` to the new `MODEL_VALIDATION_FAILURE` constant by adding a `kind` parameter overload OR by adding a new `writeChatGuardEvent(...)` method.

3. **Does `parameters-detail-view.xml`'s `modelField` get bound via `property="model"` (which doesn't exist on `AiParameters` — `model` lives in `bodyYaml`), or via a transient controller-managed field?**
   - What we know: Current XML has `<textField id="modelField" required="true">` with NO `property` attribute (lines 25-28); the existing controller must read/write via a custom getter or onReady hook.
   - What's unclear: Without scouting the controller (`ParametersDetailView.java` — file not read in this research pass), we don't know if there's a transient field, a `@Subscribe` bind, or a YAML-roundtrip on save.
   - Recommendation: Planner reads `ParametersDetailView.java` first; the existing pattern for `temperatureField`, `topPField` etc. tells the planner exactly where to write the model value (likely a `bodyYaml` re-serialize on `BeforeSaveEvent`).

4. **Does `ChatModelCatalog` need an empty-catalog defaulting strategy?**
   - What we know: If `jmix.ai-agent.models.catalog[*]` properties are unset, `properties.catalog()` returns null/empty.
   - What's unclear: Whether to fail boot (host MUST configure a catalog) or seed a built-in catalog (host can override).
   - Recommendation: Seed a built-in catalog of 3-4 entries; allow `jmix.ai-agent.models.catalog.override=true` to replace (planner's call).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Jmix BOM | All Phase 16 work | ✓ | 2.8.1 | — |
| Spring AI BOM | D-05 classification, ChatOptions | ✓ | 1.1.4 | — |
| Spring Boot 3 | ConfigurationPropertiesBean.getAll | ✓ (transitive via Jmix 2.8.1) | — | — |
| Liquibase | New changelog 120 | ✓ | included with Jmix | — |
| PostgreSQL / agentstore | DB-backed singleton row | ✓ (existing project config) | — | HSQLDB for tests via existing test harness |
| OpenRouter API | Validating curated catalog entries at plan time | ✓ (admin can curl) | live | Plan-time check only; runtime catalog is property-driven |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test (Jmix 2.8.1 standard) |
| Config file | `D:/DTH/ai-agent-core/ai-agent/ai-agent/build.gradle` (gradle test task) |
| Quick run command | `./gradlew test --tests "com.vn.agent.admin.config.*Test"` |
| Full suite command | `./gradlew :ai-agent:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| MODEL-01 | Curated ComboBox catalog populates from properties + default-marked entry | unit | `gradlew test --tests "ChatModelCatalogTest"` | ❌ Wave 0 |
| MODEL-02 | Custom-entry escape hatch + at-first-use validation with fallback | integration | `gradlew test --tests "ModelValidationFailureFallbackTest"` | ❌ Wave 0 |
| MODEL-03 | Admin-only model selection flows to per-request ChatOptions | integration | `gradlew test --tests "ModelOverridePerRequestTest"` | ❌ Wave 0 (or covered by existing chat tests) |
| CFG-01 | Tier-1 knobs read fresh per turn via resolver | integration | `gradlew test --tests "AiUiSettingsResolverFallthroughTest"` | ❌ Wave 0 |
| CFG-02 | Tier-2 read-only display + Tier-3 secrets indicator | UI test | `gradlew test --tests "AiUiSettingsBootConfigTabTest"` | ❌ Wave 0 |
| CFG-03 | Persisted in `AiUiSettings` + Liquibase + change event fires | integration | `gradlew test --tests "AiSettingsChangedEventPublicationTest"` | ❌ Wave 0 |
| SEC-08 | No secret bound editable + no `@ConditionalOnProperty` editable | source-scan | `gradlew test --tests "SecretRedactionInvariantsTest"` | ❌ Wave 0 |
| TEST-20 | Catalog ⊆ allowlist + exactly-one-default + drift gate | unit | `gradlew test --tests "ChatModelCatalogAllowlistTest"` | ❌ Wave 0 |
| Phase 13.1 invariant | Sentinel `-1` task-file knobs disable cleanup when source is `AiUiSettings` | integration | `gradlew test --tests "TtlConfigSentinelSurvivesAiUiSettingsTest"` | ❌ Wave 0 (new test class; existing `TtlConfigSentinelSkipsCleanupTest` keeps property-source path) |
| Strict default-params seed | YAML parses under `AiParametersBody` with no unknown keys | structural | `gradlew test --tests "DefaultParamsSeedStructuralTest"` | ❌ Wave 0 |
| Bean-validation | Out-of-range Tier-1 save rejected | integration | `gradlew test --tests "AiUiSettingsBeanValidationTest"` | ❌ Wave 0 |
| Locale parity | All new keys present in EN + VI | source-scan | `gradlew test --tests "LocaleParityTest"` | partial — extend if exists |
| Single-publish-site | Only the 2 entity listeners reference event publication | source-scan | inside `AiSettingsChangedEventPublicationTest` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "com.vn.agent.admin.config.*Test"` (~30s if scoped to phase-16 packages)
- **Per wave merge:** `./gradlew :ai-agent:test` (full suite ~5-10 min)
- **Phase gate:** Full suite green + manual UI smoke at http://localhost:8088

### Wave 0 Gaps

- [ ] `tests/admin/config/ChatModelCatalogAllowlistTest.java` — covers TEST-20
- [ ] `tests/admin/config/SecretRedactionInvariantsTest.java` — covers SEC-08 (two legs)
- [ ] `tests/admin/config/AiSettingsChangedEventPublicationTest.java` — covers CFG-03 + Plan 10-06 R2 invariant
- [ ] `tests/admin/config/AiUiSettingsResolverFallthroughTest.java` — covers CFG-01 per-cluster
- [ ] `tests/admin/config/AiUiSettingsBeanValidationTest.java` — covers Tier-1 bounds rejection
- [ ] `tests/admin/config/AiUiSettingsBootConfigTabTest.java` — covers CFG-02 read-only render + Span content scan for secrets
- [ ] `tests/admin/chat/ModelValidationFailureFallbackTest.java` — covers MODEL-02 reissue path + audit rows
- [ ] `tests/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java` — covers Phase 13.1 invariant survival
- [ ] `tests/parameters/DefaultParamsSeedStructuralTest.java` — covers strict seed (no new keys)
- [ ] Extend `LocaleParityTest` if it exists; else add as new (extension only counts new D-09 keys)
- [ ] No framework install needed — JUnit 5 + Spring Boot Test already on classpath

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | partial | Jmix `CurrentAuthentication` for admin-role check on edit access |
| V3 Session Management | no | (Jmix Flow handles via Vaadin session) |
| V4 Access Control | yes | `@ResourceRole` + `@EntityPolicy` (READ/UPDATE on `AiUiSettings` admin-only); `@ViewPolicy` on `AiAgent_AiUiSettings.detail`; `@EntityAttributePolicy` MODIFY=DENY for non-admin if needed |
| V5 Input Validation | yes | Jakarta Bean Validation (`@Min`, `@Max`, `@NotNull`, `@Pattern`) on Tier-1 columns; sentinel `-1` honored per Phase 13.1 |
| V6 Cryptography | no | No cryptographic ops in Phase 16; secrets are read-only indicator |
| V7 Error Handling & Logging | yes | `MODEL_VALIDATION_FAILURE` audit row carries sanitised error fingerprint (status + class name), NOT raw provider body |
| V8 Data Protection | yes | Tier-3 secrets pattern-mask defense-in-depth; `Environment.getProperty(key)` non-blank check ONLY — no value rendering |
| V13 API & Web Service | no | Internal admin view; no external API |

### Known Threat Patterns for Jmix Flow + Spring AI

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Operator stores secret in Tier-1 form field | I (Info Disclosure) | SEC-08 source-scan blocks at build time |
| Boot toggle bound to editable form | T (Tampering) | SEC-08 second leg blocks via `@ConditionalOnProperty` cross-scan |
| Bad-model exception leaks raw provider URL/body into UI/audit | I | `MutationErrorTranslator` P-22 sanitisation precedent — audit carries class name + status only |
| Settings-change event fires twice → cache evicts twice → log noise | D (DoS — minor) | Single-publish-site invariant + source-scan test |
| Non-admin user reaches AiUiSettings detail view → reads Tier-2/Tier-3 keys | I | `@ViewPolicy` `AiAgent_AiUiSettings.detail` admin-only |
| Custom-entry model accepts arbitrary string → SSRF via crafted slug | T/I | OpenRouter slug format validated by existing `effectiveModel` check (must contain `/`); per-request error catch handles bad-slug failure |
| Admin enters extremely long custom model string → DB column overflow | D | Existing `bodyYaml` storage uses TEXT-like column; no length-cap risk |

## Project Constraints (from CLAUDE.md)

- Java 21, Jmix 2.8 (Spring Boot 3, Vaadin Flow UI), Gradle, relational database.
- Entities: `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`. NO Lombok on entities. Instantiate via `Metadata.create()` or `DataManager.create()`, never constructor.
- Constructor injection for services.
- DataManager (not EntityManager) for data access; fluent loader; `@Transactional` where needed.
- Views: XML descriptor + Java controller pair; `@ViewController`, `@ViewDescriptor`, extend `StandardListView` / `StandardDetailView`. `@ViewComponent` for XML-defined components, `@Autowired` for Spring beans.
- Liquibase changelogs in `src/main/resources/**/liquibase/changelog/**.xml`, included in `changelog.xml` (here: `agentstore-changelog.xml` via `includeAll`).
- ALL labels via `msg://` keys in ALL locale files.
- Tests: `@SpringBootTest` integration tests for business logic; `@UiTest` for UI.

**Forbidden:** Lombok on entities, constructor entity instantiation, EntityManager, business logic in views, hardcoded UI text, single-locale messages, edits in `frontend/generated/`.

## Sources

### Primary (HIGH confidence)

- Context7 `/jmix-framework/jmix-context7` — EntityChangedEvent listener pattern + comboBox allowCustomValue + dataGrid renderer pattern
- Context7 `/spring-projects/spring-ai` — ChatOptions.builder().model() per-request override (confirmed across OpenAI / Mistral / DeepSeek / Ollama / MiniMax providers)
- Spring Boot API docs — `ConfigurationPropertiesBean.getAll(ApplicationContext)` — [Spring Boot API ConfigurationPropertiesBean](https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/context/properties/ConfigurationPropertiesBean.html)
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java` — direct precedent for D-04
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java` — direct precedent for D-03
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` lines 358-449 — `executeBlockingTurn` chokepoint for D-05
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java` — `UnconstrainedDataManager` singleton-load pattern
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigTest.java` — Phase 13.1 sentinel invariant test source
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml` — `includeAll` pattern

### Secondary (MEDIUM confidence)

- [OpenRouter API Errors and Debugging](https://openrouter.ai/docs/api/reference/errors-and-debugging) — error response shape; status code + error.code semantics
- [OpenRouter Error Guide - Janitor AI](https://help.janitorai.com/en/article/openrouter-error-guide-10ear52/) — production error patterns including 404 model-not-found phrasing
- Project memory `feedback_jmix_datagrid_renderer`, `feedback_jmix_messages_over_spring`, `feedback_jmix_view_listeners`, `feedback_unconstrained_for_system_writes`, `feedback_pragmatic_modules`, `project_self_hostable_models_only`

### Tertiary (LOW confidence)

- Exact OpenRouter response substring patterns for "model not found" in 2026-05 — provider phrasing drifts; integration test should stub a fixed response

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries pinned in `build.gradle`; no version drift
- Architecture: HIGH — every D-01..D-09 has a direct in-tree precedent
- Pitfalls: HIGH — Pitfalls 1-7 are derived from documented in-source comments or prior phase contracts
- API surfaces: HIGH for ChatOptions / ConfigurationPropertiesBean / EntityChangedEvent / ComboBox; MEDIUM for OpenRouter error markers

**Research date:** 2026-05-13
**Valid until:** 2026-06-13 (30 days — stable Jmix 2.8.1 + Spring AI 1.1.4 + Spring Boot 3 baseline; OpenRouter error patterns may shift — recommend a stubbed-response integration test rather than provider-string-coupled production code)

---

*Phase 16 research complete. Planner can now create Plan files for the 6 vertically-sliced waves: (1) Catalog + Model Picker, (2) AiUiSettings schema + Liquibase, (3) Resolver + Caller wiring, (4) Entity Listeners + AiSettingsChangedEvent, (5) Boot-Config Tab + Secrets Section + KnobInventory, (6) Catch+Reissue + MODEL_VALIDATION_FAILURE.*
