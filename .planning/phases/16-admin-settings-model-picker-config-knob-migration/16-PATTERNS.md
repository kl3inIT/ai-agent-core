# Phase 16: Admin Settings — Model Picker & Config-Knob Migration — Pattern Map

**Mapped:** 2026-05-13
**Files analyzed:** 35 (10 new components/annotations/events, 1 new changelog, 2 new role/messages, 8 entity/view/XML edits, 7 caller-injection edits, 7 new test classes)
**Analogs found:** 35 / 35 (every D-01..D-09 deliverable has a concrete in-tree precedent)

---

## File Classification

### NEW — components / annotations / events

| New file | Role | Data flow | Closest analog | Match |
|----------|------|-----------|----------------|-------|
| `com.vn.agent.orchestration.AiUiSettingsResolver` | service (resolver) | read-through (DB→props→constant) | `AiParametersResolver.java` | exact |
| `com.vn.agent.admin.config.AiSettingsChangedEvent` | event | pub-sub (`ApplicationEvent`) | `LlmExposureChangedEvent.java` | exact |
| `com.vn.agent.admin.config.AiParametersEntityListener` | entity-listener (publisher) | event-driven | `AiExposureRuleEntityListener.java` | exact |
| `com.vn.agent.admin.config.AiUiSettingsEntityListener` | entity-listener (publisher) | event-driven | `AiExposureRuleEntityListener.java` | exact |
| `com.vn.agent.admin.config.KnobMetadata` | annotation (meta) | reflective metadata | (no annotation-only precedent; partial match: `AuditKind` constants for grouping) | new pattern |
| `com.vn.agent.admin.config.KnobInventoryScanner` | component (boot listener) | event-driven (`ApplicationReadyEvent`) | `DefaultParamsSeeder.java` (same `@EventListener(ApplicationReadyEvent.class)`) | role+flow match |
| `com.vn.agent.admin.config.KnobInventory` | component (immutable holder) | request-response (view reads) | `AiAgentRagProperties` (record-shape immutability) + `AgentToolCallbacks` (lazy-built immutable holder) | partial |
| `com.vn.agent.admin.config.ChatModelCatalog` | component (curated catalog) | read-only lookup | `AiParametersResolver` (singleton @Component) + `AiAgentMutationProperties` (props-as-source-of-truth) | role match |
| `com.vn.agent.admin.config.ChatModelCatalogProperties` | `@ConfigurationProperties` record | binding | `AiAgentRagProperties.java` | exact |
| `com.vn.agent.admin.config.AdminSecretPatternProperties` | `@ConfigurationProperties` record | binding | `AiAgentRagProperties.java` | exact |

### NEW — changelog, role, messages

| New file | Role | Data flow | Closest analog | Match |
|----------|------|-----------|----------------|-------|
| `agentstore-changelog/120-ai-ui-settings-tier1-knobs.xml` | Liquibase changelog | DDL (additive `addColumn`) | `agentstore-changelog/080-ai-ui-settings.xml` + `110-ai-extraction-draft.xml` | exact (same parent `includeAll`) |

### MODIFY — entity, views, XML

| Modified file | Role | Data flow | Closest analog | Match |
|---------------|------|-----------|----------------|-------|
| `entity/AiUiSettings.java` | JPA entity (column add) | CRUD | self (existing `defaultSurface` flat-column at lines 51-53, 102-108) | exact |
| `view/uisettings/AiUiSettingsDetailView.java` | view controller (form + grid) | request-response + bound form | self + `AiAuditEventListView` (`@Supply` renderer) | role match |
| `view/uisettings/ai-ui-settings-detail-view.xml` | view descriptor (tabSheet) | bound form | `parameters-detail-view.xml` (tabSheet structure) | exact |
| `view/parameters/parameters-detail-view.xml` | view descriptor (field swap) | bound form | self (lines 25-28 textField → comboBox) | exact |
| `view/parameters/ParametersDetailView.java` | view controller (comboBox wire) | event-driven (`@Subscribe` value-change) | self (existing `@Subscribe("modelField") onModelFieldChange`) | exact |
| `DefaultChatServiceImpl.executeBlockingTurn(...)` | service chokepoint (catch+reissue) | request-response with retry | self lines 383-412 (`chatClient.prompt()...call()`) | exact (same site) |
| `spi/AuditKind.java` | constant taxonomy add | reflective metadata | self (existing `CHAT`/`TOOL`/`RETRIEVAL` constants) | exact |
| `security/AiAgentAdminRole.java` | role policy | static metadata | self (existing `@ViewPolicy` for `AiAgent_AiUiSettings.detail`) | exact (additive `@EntityAttributePolicy`) |
| `messages_en.properties` + `messages_vi.properties` | i18n | static lookup | self (existing `aiUiSettingsDetail.*` block lines 271-279) | exact |

### MODIFY — callers (additive resolver injection)

| Modified file | Role | Data flow | Closest analog | Match |
|---------------|------|-----------|----------------|-------|
| `taskfile/AiTaskFileCleanupJob` | scheduled job | batch | `AiParametersResolver` injection precedent (constructor injection) | role match |
| `taskfile/AiTaskFileMediaResolver` | service | request-response | same | role match |
| `view/chat/fragment/ChatPanelFragment` | view fragment | request-response | same | role match |
| `tools/mutation/BuiltInMutationTools` | tool service | request-response | same | role match |
| `tools/mutation/MutationIntentRepository` | repository | CRUD | same | role match |
| `tools/mutation/MutationSaveExecutor` | service | CRUD | same | role match |
| `orchestration/BaselineContextProvider` | service | request-response | same | role match |
| `tools/BuiltInDataTools` / `ToolEntityResolver` | tool services | request-response | same | role match |
| `conversation/AiConversationTitleService` | service | request-response | same | role match |
| `rag/KnowledgeDocumentUploadService` | service | request-response | same | role match |
| All 10 `@ConfigurationProperties` records (`AiAgentRagProperties`, `AiAgentMutationProperties`, `AiAgentAuditProperties`, `AiAgentGuardProperties`, `AiAgentPromptProperties`, `AiAgentTitleProperties`, `AiAgentDefaultsProperties`, `AiTaskFileProperties`, `AiAgentEmbeddingProperties`, `AiExtractionProperties`) | annotation pass | static metadata | self records | exact (additive annotation lines) |

### NEW — tests

| Test class | Role | Data flow | Closest analog | Match |
|------------|------|-----------|----------------|-------|
| `ChatModelCatalogAllowlistTest` (TEST-20) | unit/integration | static contract | `TtlConfigTest` (sibling `@SpringBootTest` classes) | role match |
| `SecretRedactionInvariantsTest` (SEC-08) | source-scan test | static analysis | (no exact precedent — closest: `TaskFileNoVectorStoreSourceScannerTest`) | role match |
| `TtlConfigSentinelSurvivesAiUiSettingsTest` | integration | CRUD | `TtlConfigSentinelSkipsCleanupTest` (same file, sibling class) | exact |
| `AiSettingsChangedEventPublicationTest` | integration | event publication | (no existing direct test for `LlmExposureChangedEvent` — pattern follows `@RecordApplicationEvents`) | role match |
| `AiUiSettingsResolverFallthroughTest` | integration | read-through | `TtlConfigTest` shape | role match |
| `KnobInventoryClassificationTest` | integration | reflective inventory | `ProviderConfigurationContractTest` | role match |
| `ModelValidationFailureFallbackTest` | integration | event-driven exception | `DefaultChatServiceImplStreamFallbackTest` | exact (same chokepoint, sibling test) |

---

## Pattern Assignments

### `AiUiSettingsResolver.java` (new component, read-through)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java`

**Constructor + DB-load (lines 49-83):**
```java
@Component
public class AiParametersResolver {
    private final DataManager dataManager;
    private final Metadata metadata;
    private final AiAgentDefaultsProperties defaults;
    // ... constructor injection ...

    public AiParameters resolveActive() {
        try {
            return dataManager.load(AiParameters.class)
                    .query("select e from ai_AiParameters e where e.active = true")
                    .optional()
                    .orElseGet(this::buildFallback);
        } catch (RuntimeException persistenceFailure) {
            log.warn("Unable to resolve active AiParameters from persistence; using defaults fallback: {}",
                    persistenceFailure.getMessage());
            return buildFallback();
        }
    }
}
```

**Effective-value fallback chain (lines 169-185):**
```java
public Integer effectiveRagTopK(AiParameters params, int defaultValue) {
    Number value = numberFromBody(params, "ragTopK");
    if (value == null) {
        return defaultValue;
    }
    int topK = value.intValue();
    return topK > 0 ? topK : defaultValue;
}
```

**Singleton-load idiom (from `AiUiSettingsService.loadCurrent()` lines 23-28):**
```java
public AiUiSettings loadCurrent() {
    return unconstrainedDataManager.load(AiUiSettings.class)
            .id(AiUiSettings.SINGLETON_ID)
            .optional()
            .orElseGet(this::createDefaultSettings);
}
```

**Minimal-diff sketch:**
- Inject `UnconstrainedDataManager` + each `@ConfigurationProperties` bean for fallback (`AiTaskFileProperties`, `AiAgentMutationProperties`, `AiAgentPromptProperties`, `AiAgentRagProperties`, `AiAgentTitleProperties`).
- One `resolveXxx()` method per Tier-1 knob: load singleton via `UnconstrainedDataManager` → if column non-null return it → else return `props.resolvedXxx()`.
- Wrap singleton-load in try/catch with the `AiParametersResolver.resolveActive()` resilience pattern.

---

### `AiSettingsChangedEvent.java` (new event)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureChangedEvent.java`

**Full file (15 lines):**
```java
public class LlmExposureChangedEvent extends ApplicationEvent {
    public LlmExposureChangedEvent(Object source) {
        super(source);
    }
}
```

**Minimal-diff sketch:**
- Same shape; add `enum Kind { PARAMETERS, UI_SETTINGS }` + private final field + `getKind()` accessor.
- Copy the "SINGLE publish site" docstring verbatim (substitute entity-listener names).

---

### `AiParametersEntityListener.java` + `AiUiSettingsEntityListener.java` (new entity listeners)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java`

**Full file (35 lines — exact precedent):**
```java
@Component
public class AiExposureRuleEntityListener {
    private final ApplicationEventPublisher eventPublisher;

    public AiExposureRuleEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onExposureRuleChanged(EntityChangedEvent<AiExposureRule> event) {
        eventPublisher.publishEvent(new LlmExposureChangedEvent(this));
    }
}
```

**Minimal-diff sketch (UI_SETTINGS — every save fires):**
- Copy verbatim; substitute `AiUiSettings` + `AiSettingsChangedEvent(this, Kind.UI_SETTINGS)`.

**Minimal-diff sketch (PARAMETERS — guard `active=true`):**
- Add `DataManager` injection; on `CREATED`/`UPDATED` load by `event.getEntityId()` and check `getActive()`; on `DELETED` read `event.getChanges().getOldValue("active")`. Publish only when `Boolean.TRUE.equals(...)`.

---

### `KnobInventoryScanner.java` (new boot-time listener)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/DefaultParamsSeeder.java`

**`ApplicationReadyEvent` listener idiom (lines 31-63):**
```java
@Component
@ConditionalOnProperty(
        name = "jmix.ai-agent.parameters.seed-default",
        havingValue = "true",
        matchIfMissing = true)
public class DefaultParamsSeeder {

    private final DataManager dataManager;
    // ... constructor injection ...

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        systemAuthenticator.runWithSystem(this::doSeedIfEmpty);
    }
}
```

**Minimal-diff sketch:**
- `@Component` + `@EventListener(ApplicationReadyEvent.class)` method.
- Inject `ApplicationContext` (constructor injection); call `ConfigurationPropertiesBean.getAll(applicationContext)` once; walk each bean's `getType()` reflectively for `@KnobMetadata` on record components / setter methods.
- Build immutable `KnobInventory` state and `set` it on the `KnobInventory` `@Component`.
- Place in `ai-agent-starter` autoconfig module per RESEARCH §10.

---

### `ChatModelCatalog.java` + `ChatModelCatalogProperties.java` (new catalog + props)

**Analog (Properties record):** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/rag/config/AiAgentRagProperties.java`

**Record + sub-record shape (lines 43-96):**
```java
@ConfigurationProperties("jmix.ai-agent.rag")
public record AiAgentRagProperties(
        Boolean adminBypass,
        Integer topK,
        // ...
        Upload upload) {

    public record Upload(
            java.util.List<String> classpathAllowedPrefixes,
            String fileStagingRoot,
            Integer maxFileSizeBytes) {}

    public int resolvedTopK() {
        return topK == null ? 5 : topK;
    }
}
```

**Analog (component + `@PostConstruct` validation):** `AiAgentMutationProperties.java` for `resolved*()` defaulting + `IngesterManager.java` for component-init validation pattern.

**Minimal-diff sketch (`ChatModelCatalogProperties`):**
- `@ConfigurationProperties("jmix.ai-agent.models")` + `record(List<Entry> catalog)` + nested `record Entry(String id, String labelMessageKey, Boolean isDefault)`.

**Minimal-diff sketch (`ChatModelCatalog`):**
- `@Component`; constructor takes `ChatModelCatalogProperties` + `AiAgentDefaultsProperties`; `@PostConstruct` validates exactly-one-default + drift-equals-default-params-yaml; expose `entries()`, `defaultEntry()`, `findById(...)`. Hold `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` as `Set.of(...)` Java constant.

---

### `AuditKind.java` (modify — add `MODEL_VALIDATION_FAILURE`)

**Analog:** self — `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditKind.java`

**Full file (16 lines):**
```java
/**
 * SPI-06: kind tag carried by every AiAuditEvent row...
 * The underlying KIND column is varchar(16) to keep the option open.
 */
public final class AuditKind {
    public static final String CHAT = "CHAT";
    public static final String TOOL = "TOOL";
    public static final String RETRIEVAL = "RETRIEVAL";

    private AuditKind() { }
}
```

**Minimal-diff sketch:**
- Add `public static final String MODEL_VALIDATION_FAILURE = "MODEL_VALIDATION_FAILURE";`.
- Width audit: 24 chars > 16-char column. Pitfall 5 in RESEARCH — widen `AI_AUDIT_EVENT.KIND` to `varchar(32)` inside changelog `120-ai-ui-settings-tier1-knobs.xml` (additive `modifyDataType` is DDL-safe in PG/HSQL/MSSQL).

---

### `AiUiSettings.java` (modify — add ~10 nullable Tier-1 columns)

**Analog:** self — `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java`

**Flat-column precedent (lines 38-53):**
```java
@NotNull
@Column(name = "ENABLED_SURFACE_IDS", nullable = false)
private String enabledSurfaceIds = toEnabledSurfaceIds(EnumSet.allOf(AiChatSurface.class));

@NotNull
@Column(name = "DEFAULT_SURFACE", nullable = false, length = 64)
private String defaultSurface = AiChatSurface.FULL_ROUTE.getId();
```

**EclipseLink-weaver setter warning (lines 102-108):**
```java
public void setDefaultSurface(AiChatSurface defaultSurface) {
    // The JPA field is named `defaultSurface` (String) — EclipseLink's weaver treats
    // this very method (which has the matching name) as the property setter, so
    // direct field assignment here IS tracked. (Contrast with setEnabledSurfaceSet,
    // which has a non-matching name and must route via setEnabledSurfaceIds.)
    this.defaultSurface = defaultSurface == null ? null : defaultSurface.getId();
}
```

**Minimal-diff sketch:**
- Add per D-01 the 10 nullable columns with Jakarta bounds, e.g.:
  ```java
  @Min(-1) @Max(7L * 86_400)
  @Column(name = "TASK_FILE_TTL_SECONDS")
  private Long taskFileTtlSeconds;

  public Long getTaskFileTtlSeconds() { return taskFileTtlSeconds; }
  public void setTaskFileTtlSeconds(Long v) { this.taskFileTtlSeconds = v; }
  ```
- Setter name MUST match field name (RESEARCH Pitfall 1 — no `@Transient` bridges).

---

### `120-ai-ui-settings-tier1-knobs.xml` (new Liquibase changelog)

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/080-ai-ui-settings.xml` (createTable shape) + `110-ai-extraction-draft.xml` (sibling under same parent `includeAll`).

**Parent `includeAll` confirms additive discovery (`agentstore-changelog.xml` lines 14-15):**
```xml
<!-- includeAll below picks up agentstore-changelog/080-ai-ui-settings.xml. -->
<includeAll path="/com/vn/agent/liquibase/agentstore-changelog" errorIfMissingOrEmpty="false"/>
```

**Existing 080-ai-ui-settings.xml column types (reuse):**
```xml
<column name="ENABLED_SURFACE_IDS" type="varchar(255)" defaultValue="FULL_ROUTE,HEADER_BUTTON">
    <constraints nullable="false"/>
</column>
<column name="DEFAULT_SURFACE" type="varchar(64)" defaultValue="FULL_ROUTE">
    <constraints nullable="false"/>
</column>
```

**Minimal-diff sketch:**
- New file `120-ai-ui-settings-tier1-knobs.xml` with one `<changeSet id="1" author="ai-agent">` containing `<addColumn tableName="AI_UI_SETTINGS">` for each of the 10 nullable Long/Integer/Boolean columns. No defaultValue (null fall-through).
- Second `<changeSet id="2">` widens `AI_AUDIT_EVENT.KIND` to `varchar(32)` via `<modifyDataType>` (Pitfall 5).
- Do NOT edit `080-ai-ui-settings.xml` (Phase 12 D-15 invariant).

---

### `parameters-detail-view.xml` (modify — textField → comboBox)

**Analog:** self lines 25-28 (current textField).

**Current shape (lines 25-28):**
```xml
<textField id="modelField"
           label="msg:///parametersDetail.field.model"
           required="true"
           requiredMessage="msg:///parametersDetail.validation.modelRequired"/>
```

**Minimal-diff sketch:**
```xml
<comboBox id="modelField"
          label="msg:///parametersDetail.field.model"
          allowCustomValue="true"
          required="true"
          requiredMessage="msg:///parametersDetail.validation.modelRequired"/>
```
(no `property=` binding — controller writes via `setValue`; matches existing pattern where `modelField` does NOT have `property=` since the value lives inside `bodyYaml`.)

---

### `ParametersDetailView.java` (modify — comboBox setup + custom-value)

**Analog:** self — existing `@Subscribe("modelField")` precedent.

**Existing value-change wire pattern (lines 138-141):**
```java
@Subscribe("modelField")
public void onModelFieldChange(final AbstractField.ComponentValueChangeEvent<?, ?> event) {
    refreshYamlPreview();
}
```

**Existing onInit pattern for `enabledToolsField` (lines 112-123):**
```java
@Subscribe
public void onInit(final InitEvent event) {
    List<String> toolNames = discoverToolNames();
    // ...
    enabledToolsField.setItems(registryToolNames);
}
```

**Minimal-diff sketch:**
- Change field type: `private TypedTextField<String> modelField;` → `private JmixComboBox<String> modelField;` (import swap).
- In `onInit`: `modelField.setItems(catalog.entries().stream().map(Entry::id).toList())` + `modelField.setItemLabelGenerator(id -> { /* resolve via Messages, append `(default)` suffix */ })`.
- Add `@Subscribe("modelField") onModelFieldCustomValueSet(ComboBoxBase.CustomValueSetEvent<ComboBox<String>> event) { modelField.setValue(event.getDetail()); }` per RESEARCH Pattern 4.
- Keep the existing `onModelFieldChange` for YAML preview refresh (compatible — `ComponentValueChangeEvent` fires for both catalog selection and custom-value-set after `setValue`).
- Inject `ChatModelCatalog catalog` + reuse the existing `Messages messages`.

---

### `AiUiSettingsDetailView.java` + XML (modify — Tier-1 form fields + Boot Config tab + Secrets section)

**Analog (controller):** self + `AiAuditEventListView.java` (for `@Supply` renderer).

**Existing onInit field-binding precedent (`AiUiSettingsDetailView.java` lines 59-65):**
```java
@Subscribe
public void onInit(final InitEvent event) {
    enabledSurfacesField.setItems(AiChatSurface.class);
    enabledSurfacesField.setItemLabelGenerator(messages::getMessage);
    defaultSurfaceField.setItems(AiChatSurface.class);
    defaultSurfaceField.setItemLabelGenerator(messages::getMessage);
}
```

**`@Supply` renderer precedent (`AiAuditEventListView.java` lines 51-68):**
```java
@Supply(to = "auditsDataGrid.actions", subject = "renderer")
private Renderer<AiAuditEvent> auditsDataGridActionsRenderer() {
    return DataGridRenderers.buildActionsColumn(
            uiComponents,
            EnumSet.of(ActionColumnType.VIEW),
            (row, type) -> openDetailDialog(row));
}

@Supply(to = "auditsDataGrid.outcome", subject = "renderer")
private Renderer<AiAuditEvent> auditsDataGridOutcomeRenderer() {
    return DataGridRenderers.buildBadgeColumn(
            uiComponents,
            row -> messages.getMessage("auditList.outcome." + ...),
            row -> AiAuditEventDetailDialog.outcomeTheme(row.getOutcome()));
}
```

**Analog (XML — tabSheet wrap):** `parameters-detail-view.xml` lines 22-57.

**Minimal-diff sketch (XML):**
- Wrap existing `<formLayout id="settingsForm">` in `<tabSheet id="editTabs">` → `<tab id="generalTab">` (keep existing content untouched).
- Add `<tab id="tier1KnobsTab">` with sectioned `<formLayout>` binding new fields via `property="taskFileTtlSeconds"` etc. (flat columns make this zero-glue).
- Add `<tab id="bootConfigTab">` with `<dataGrid id="bootConfigGrid" dataContainer="bootConfigDc">` + columns `key|value|badge`.
- Add `<tab id="secretsTab">` with `<dataGrid id="secretsGrid">` + columns `key|configured`.
- Add `<collection id="bootConfigDc">` + `<collection id="secretsDc">` in-memory data containers under `<data>`.

**Minimal-diff sketch (Java controller):**
- Inject `KnobInventory knobInventory` + `UiComponents uiComponents` (already standard imports).
- In `onInit`, populate `bootConfigDc.getMutableItems().addAll(knobInventory.tier2())` + `secretsDc.getMutableItems().addAll(knobInventory.tier3())`.
- Add `@Supply(to = "bootConfigGrid.badge", subject = "renderer")` returning a `ComponentRenderer<KnobRow>` creating a Vaadin `Span` with "requires restart" text — exact shape from `AiAuditEventListView.auditsDataGridOutcomeRenderer`.
- Add Tier-1 field declarations: `@ViewComponent private JmixIntegerField taskFileTtlField;` etc.
- Extend `onBeforeSave` with bean-validation error mapping (Jakarta bounds → localised toast via `rejectSave(...)` helper at line 102).

---

### `DefaultChatServiceImpl.executeBlockingTurn(...)` (modify — catch+reissue chokepoint)

**Analog:** self — `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` lines 358-412.

**Current `ChatClient.prompt()...call()` chokepoint (lines 383-412):**
```java
ChatClientResponse clientResp = chatClient.prompt()
        .system(systemPromptWithDocs)
        .user(u -> { u.text(message); if (!resolvedMedia.media().isEmpty()) u.media(...); })
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId, toolSurfaceIntentId))
        .toolContext(auditToolContext(runId, convId))
        .advisors(advisorSpec -> { /* memory + audit + retrieval params */ })
        .options(ChatOptions.builder()
                .model(model)
                .temperature(parametersResolver.effectiveTemperature(active))
                .topP(parametersResolver.effectiveTopP(active))
                .maxTokens(parametersResolver.effectiveMaxTokens(active))
                .build())
        .call()
        .chatClientResponse();
```

**Audit-write idiom (`AuditWriter.writeToolCall` lines 149-189):**
```java
public UUID writeToolCall(UUID parentId, UUID runId, String userUsername, UUID conversationId,
                          String toolName, String argumentsJson, String resultSummary, long latencyMs,
                          AiToolCallOutcome outcome, String denialReason, String errorClass) {
    AiAuditEvent row = metadata.create(AiAuditEvent.class);
    row.setRunId(runId);
    row.setKind(AuditKind.TOOL);
    // ...
    dataManager.save(row);
    registerAfterCommit(auditId, AuditKind.TOOL);
    return auditId;
}
```

**Minimal-diff sketch:**
- Extract a `private ChatClientResponse buildAndCall(String chosenModel, ...prompt args...)` helper containing lines 383-412 with `chosenModel` parameterised.
- Wrap the call site in a try/catch:
  ```java
  ChatClientResponse clientResp;
  try {
      clientResp = buildAndCall(model, ...);
  } catch (NonTransientAiException badModel) {
      if (!isBadModelException(badModel)) throw badModel;
      writeModelValidationFailureAudit(runId, userId, convId, model, badModel);
      String fallbackModel = aiAgentDefaultsProperties.model(); // or parametersResolver.fallbackModel()
      clientResp = buildAndCall(fallbackModel, ...);
      writeModelFallbackAppliedAudit(runId, userId, convId, model, fallbackModel);
  }
  ```
- Add private classifier walking `Throwable.getCause()` chain for `RestClientResponseException` with status ∈ {400, 404, 422} AND body containing "model" (RESEARCH §3).
- Reuse `AuditWriter.writeToolCall(...)` with `kind = AuditKind.MODEL_VALIDATION_FAILURE` (after extending `AuditKind`).

---

### `AiAgentAdminRole.java` (modify — policy add)

**Analog:** self — `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`.

**Existing policy shape (lines 29-62):**
```java
@EntityPolicy(entityClass = AiUiSettings.class, actions = {
        EntityPolicyAction.READ,
        EntityPolicyAction.UPDATE})
void adminAccess();

@MenuPolicy(menuIds = {... "aiAgent.uiSettings" ...})
@ViewPolicy(viewIds = {... "AiAgent_AiUiSettings.detail"})
void adminViews();
```

**Minimal-diff sketch:**
- Verify `AiAgentUserRole` does NOT include `AiAgent_AiUiSettings.detail` in its `@ViewPolicy` (per RESEARCH §1.13 — likely already true; if so no role edit needed).
- Optionally add `@EntityAttributePolicy(entityClass = AiUiSettings.class, attributes = {"taskFileTtlSeconds", ...}, action = MODIFY)` if a stricter defence-in-depth is wanted, but the view gate is the primary admin gate.

---

### `messages_en.properties` + `messages_vi.properties` (modify — add D-09 keys)

**Analog:** self — `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` lines 271-279.

**Existing aiUiSettings keys (lines 271-279):**
```properties
aiUiSettingsDetail.title=AI UI settings
aiUiSettingsDetail.field.enabledSurfaces=Enabled chat surfaces
aiUiSettingsDetail.field.enabledSurfaces.helper=Choose where users can open chat.
aiUiSettingsDetail.field.defaultSurface=Default chat surface
aiUiSettingsDetail.field.defaultSurface.helper=Used when the application needs one preferred chat entry point.
aiUiSettingsDetail.action.save=Save settings
aiUiSettingsDetail.validation.enabledSurfacesRequired=Enable at least one chat surface.
aiUiSettingsDetail.validation.defaultSurfaceRequired=Select a default chat surface.
aiUiSettingsDetail.validation.defaultSurfaceEnabled=The default chat surface must be enabled.
```

**Existing parametersDetail keys (lines 301-325):** `parametersDetail.title.edit`, `.field.model`, `.field.temperature`, `.validation.modelRequired`, etc.

**Minimal-diff sketch:**
- Append D-09 key groups to BOTH `messages_en.properties` AND `messages_vi.properties` in identical key order:
  - `aiUiSettings.section.taskFile`, `.mutation`, `.promptTools`, `.title`, `.upload`
  - `aiUiSettings.field.taskFileTtlSeconds`, `.taskFilePerTurnMaxFiles`, ... (10 keys)
  - `aiUiSettings.validation.taskFileTtl.range`, ... (per-knob bound message)
  - `aiUiSettings.tab.bootConfig`, `.tab.secrets`
  - `aiUiSettings.bootConfig.column.key`, `.value`, `.badge`
  - `aiUiSettings.bootConfig.badge.requiresRestart`
  - `aiUiSettings.secrets.column.key`, `.configured`
  - `aiUiSettings.secrets.indicator.yes`, `.no`
  - `parametersDetail.modelField.customValueHint`, `.defaultSuffix`
  - `chat.error.modelValidationFailure`, `chat.notice.modelFallbackApplied`
- Add new keys to root bundle only (`feedback_jmix_messages_over_spring`).

---

### Caller-injection callers (additive — 10 files)

**Analog (constructor-injection):** `AiParametersResolver.java` constructor (lines 59-69) — every caller across the codebase injects this resolver via constructor.

**Minimal-diff sketch (per caller):**
- Add `private final AiUiSettingsResolver aiUiSettingsResolver;` field.
- Append constructor parameter; assign in body.
- At each `props.resolvedXxx()` call site, swap to `aiUiSettingsResolver.resolveXxx()` (which itself falls back to `props.resolvedXxx()` if column is null — fully additive, no behavior change when DB column unset).

Files (per CONTEXT D-03 + RESEARCH §1):
- `AiTaskFileCleanupJob` (TTL) + `AiTaskFileMediaResolver` (per-turn caps)
- `view/chat/fragment/ChatPanelFragment.java` (upload cap)
- `BuiltInMutationTools` + `MutationIntentRepository` + `MutationSaveExecutor` (mutation knobs)
- `BaselineContextProvider` (entity-inventory limit)
- `BuiltInDataTools` / `ToolEntityResolver` (max-filter-depth)
- `AiConversationTitleService` (title knobs)
- `KnowledgeDocumentUploadService` (upload size)

---

### `@KnobMetadata` annotation pass on all 10 `@ConfigurationProperties` records (additive)

**Analog (records targeted):**
- `AiAgentRagProperties.java` (record + nested records)
- `AiAgentMutationProperties.java` (record with components)
- `AiTaskFileProperties.java` (class — non-record — same annotation target works on `setXxx` methods)
- `AiAgentAuditProperties`, `AiAgentGuardProperties`, `AiAgentPromptProperties`, `AiAgentTitleProperties`, `AiAgentDefaultsProperties`, `AiAgentEmbeddingProperties`, `AiExtractionProperties`.

**Minimal-diff sketch:**
- Define `@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})` so it sits on record components (`AiAgentRagProperties`), POJO setters (`AiTaskFileProperties`), or methods.
- Annotate every component with `tier`, `requiresRestart`, `displayMessageKey`. Tier-1 = migrated knobs. Tier-2 = everything else.
- Pure additive — no record shape changes, no class signature changes.

---

## Shared Patterns

### Pattern A — `@EventListener(EntityChangedEvent<T>)` single-publish-site

**Source:** `AiExposureRuleEntityListener.java` (entire file)
**Apply to:** `AiParametersEntityListener`, `AiUiSettingsEntityListener`
**Invariant:** NEVER inject `ApplicationEventPublisher` in views/services for `AiSettingsChangedEvent`. SEC-08 source-scan asserts only these 2 classes reference the event on publish path.
```java
@Component
public class AiExposureRuleEntityListener {
    private final ApplicationEventPublisher eventPublisher;

    public AiExposureRuleEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onExposureRuleChanged(EntityChangedEvent<AiExposureRule> event) {
        eventPublisher.publishEvent(new LlmExposureChangedEvent(this));
    }
}
```

### Pattern B — `UnconstrainedDataManager` singleton load with fallback

**Source:** `AiUiSettingsService.loadCurrent()` (lines 23-44)
**Apply to:** `AiUiSettingsResolver` — every `resolveXxx()` consults the singleton row.
```java
return unconstrainedDataManager.load(AiUiSettings.class)
        .id(AiUiSettings.SINGLETON_ID)
        .optional()
        .orElseGet(this::createDefaultSettings);
```
**Why unconstrained:** RESEARCH Pitfall 3 — non-admin chat turn must read settings without tripping row-level security on `AiUiSettings`.

### Pattern C — Read-through with try/catch resilience

**Source:** `AiParametersResolver.resolveActive()` (lines 71-83)
**Apply to:** every `AiUiSettingsResolver.resolveXxx()` method.
```java
try {
    return /* DB load */;
} catch (RuntimeException persistenceFailure) {
    log.warn("Unable to resolve... using defaults fallback: {}", persistenceFailure.getMessage());
    return /* fallback to properties */;
}
```

### Pattern D — `@Supply(to="grid.col", subject="renderer")` for dataGrid cells

**Source:** `AiAuditEventListView.java` lines 51-68 (verbatim renderer pattern)
**Apply to:** `AiUiSettingsDetailView` → `bootConfigGrid.badge` + `secretsGrid.configured` columns.
**Invariant:** project memory `feedback_jmix_datagrid_renderer` — never `getColumnByKey().setRenderer()` in `onInit`.

### Pattern E — `@EventListener(ApplicationReadyEvent.class)` boot scanner

**Source:** `DefaultParamsSeeder.java` lines 60-63
**Apply to:** `KnobInventoryScanner` — runs after full Spring context binding (RESEARCH Pitfall 4).
```java
@EventListener(ApplicationReadyEvent.class)
public void seedIfEmpty() {
    systemAuthenticator.runWithSystem(this::doSeedIfEmpty);
}
```

### Pattern F — `@ConfigurationProperties` record with `resolved*()` accessors

**Source:** `AiAgentMutationProperties.java` (entire file) + `AiAgentRagProperties.java`
**Apply to:** `ChatModelCatalogProperties`, `AdminSecretPatternProperties`.
**Invariant:** nullable boxed fields + `resolvedXxx()` defaulting in the record body; record is the single source of truth for defaults.

### Pattern G — Flat JPA column with field-name-matching setter (EclipseLink weaver)

**Source:** `AiUiSettings.setDefaultSurface(...)` lines 102-108 (docstring documents the trap)
**Apply to:** every new Tier-1 column on `AiUiSettings` (D-01).
**Invariant:** RESEARCH Pitfall 1 — no `@Transient` bridges, setter name = field name.

### Pattern H — Liquibase additive changelog via `includeAll`

**Source:** `agentstore-changelog.xml` lines 14-15 + `110-ai-extraction-draft.xml` (sibling shape)
**Apply to:** new `120-ai-ui-settings-tier1-knobs.xml` lands as sibling under same parent; parent file NOT modified (Phase 12 D-15 invariant).

### Pattern I — `@SpringBootTest` + `@ImportAutoConfiguration` integration test shape

**Source:** `TtlConfigTest.java` lines 38-46 (the `@SpringBootTest` boot stack the new tests inherit)
**Apply to:** `AiUiSettingsResolverFallthroughTest`, `TtlConfigSentinelSurvivesAiUiSettingsTest`, `AiSettingsChangedEventPublicationTest`, `ModelValidationFailureFallbackTest`, `KnobInventoryClassificationTest`.
```java
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class TtlConfigSentinelSkipsCleanupTest {
    // sentinel set via properties = {"ai-agent.task-file.ttl-seconds=-1"}
}
```

### Pattern J — `@ViewPolicy` / `@MenuPolicy` / `@EntityPolicy` role grants

**Source:** `AiAgentAdminRole.java` lines 29-62
**Apply to:** any new view ids / menu ids / entity-attribute policies added for Tier-1 / Boot-Config / Secrets surfaces.

### Pattern K — `AuditWriter.writeToolCall(...)` audit row append

**Source:** `AuditWriter.java` lines 149-189
**Apply to:** the two audit-write helpers introduced in `DefaultChatServiceImpl` for `MODEL_VALIDATION_FAILURE` + the recovered turn — reuse existing public API (`writeToolCall`) with new `AuditKind.MODEL_VALIDATION_FAILURE` constant.

---

## No Analog Found

| File | Role | Reason |
|------|------|--------|
| `KnobMetadata` annotation | meta-annotation | No existing custom runtime annotations in `com.vn.agent.*` (`@JmixEntity`, `@Tool`, etc. are framework annotations the codebase consumes, not defines). Pattern is straightforward: standard `@Target` + `@Retention(RUNTIME)` + enum `Tier`. Planner should write it from scratch — there is no closer in-tree precedent. |
| `KnobInventory` immutable bean | in-memory holder | No existing `AtomicReference<State>`-style holder in `com.vn.agent.*`. Closest shape: `AgentToolCallbacks` (lazy-built list) — not a strong analog. Planner builds from scratch using ordinary `@Component` with `volatile`/`AtomicReference` for thread-safe publish at `ApplicationReadyEvent`. |
| `SecretRedactionInvariantsTest` | source-scan test (XML + classpath annotation grep) | No existing pure-XML-scan test in the codebase. Closest: `TaskFileNoVectorStoreSourceScannerTest` (classpath bytecode scan, different mechanism). Planner builds from scratch using `BufferedReader` over `*-view.xml` files + `Reflections` library for `@ConditionalOnProperty` discovery — but `Reflections` is not yet a dependency, so a hand-rolled classpath walker is the cheapest path. |

---

## Metadata

**Analog search scope:**
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/**` (Java sources)
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/**` (XML descriptors, Liquibase, messages)
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/**` (test class shapes)

**Files scanned:** ~120 (Java + XML)
**Pattern extraction date:** 2026-05-13

---

## Planner consumption notes

1. **Every D-01..D-09 deliverable has a verbatim-copy starting point.** Plan actions should reference these analog files by absolute path + line numbers.
2. **The riskiest extraction is the catch+reissue in `DefaultChatServiceImpl`** — RESEARCH §3 plus Pitfall 5 (KIND column width) plus Pitfall 6 (false-positive classification) all converge there. Plan that wave carefully.
3. **Pattern G (EclipseLink-weaver trap) is the only non-obvious entity-side hazard.** Plan must enforce: every new column on `AiUiSettings` has a setter whose name matches the field name. No `@Transient` bridges.
4. **Pattern A (single publish site) is the only non-obvious cross-cutting invariant.** SEC-08 must be enforced by source-scan, not by code review alone.
5. **All 10 caller-site edits (Pattern C consumers) are mechanically additive** — they each gain a new `AiUiSettingsResolver` injection alongside their existing `@ConfigurationProperties` injection. No deletions. Phase 18 will memoize behind the resolver later; this phase only does the read-through wire.
