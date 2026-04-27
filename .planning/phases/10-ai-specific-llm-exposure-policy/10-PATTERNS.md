# Phase 10: AI-Specific LLM Exposure Policy - Pattern Map

**Mapped:** 2026-04-27
**Files analyzed:** 18 (5 new, 10 modified, 3 supporting)
**Analogs found:** 18 / 18

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `com.vn.agent.exposure.AiExposureRule` | entity | CRUD | `AiParameters.java` | exact |
| `com.vn.agent.exposure.LlmExposurePolicy` | service | request-response | `CurrentUserSchemaAccess.java` | exact |
| `com.vn.agent.exposure.LlmExposureRuleRepository` | service | CRUD | `AsyncIngestionWorker.java` (UnconstrainedDataManager block) | role-match |
| `AiExposureRuleListView` (XML + Java) | view/controller | request-response | `parameters-list-view.xml` + `ParametersListView.java` | exact |
| `AiExposureRuleDetailView` (XML + Java) | view/controller | request-response | `parameters-detail-view.xml` + `ParametersDetailView.java` | exact |
| `VectorStoreDebugView` (XML + Java) | view/controller | request-response | `ai-audit-event-list-view.xml` | role-match |
| `BaselineContextProvider.java` | service | request-response | self (field swap) | self |
| `BuiltInDataTools.java` | service | request-response | self (field swap, 6 lines) | self |
| `FetchPlanIntersector.java` | service | request-response | self (field swap) | self |
| `RetrievalFilterBuilder.java` | service | request-response | self (add nin clause after line 93) | self |
| `ChunkMetadata.java` | utility | — | self (add constant) | self |
| `AiKnowledgeDocument.java` | entity | CRUD | self (add nullable field) | self |
| `knowledge-base-view.xml` + controller | view/controller | file-I/O | self (add upload form field + row actions) | self |
| `AsyncIngestionWorker.java` / `IngesterManager` | service | batch | self (mirror sourceEntityName to metadata) | self |
| `AiAgentAdminRole.java` | security | — | self (add policies) | self |
| `messages_en.properties` + `messages_vi.properties` | config | — | self (add keys) | self |
| Liquibase `060-` + `061-` + `agentstore-changelog.xml` | migration | — | `050-ai-knowledge-document.xml` | exact |
| TEST-09 `LlmExposurePolicyIntegrationTest` | test | request-response | `CurrentUserSchemaAccessTest.java` | role-match |

---

## Pattern Assignments

### 1. `com.vn.agent.exposure.AiExposureRule` (entity, CRUD)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java`

**Role:** Jmix entity in `agentstore`. Mirror `AiParameters` exactly — same `@Store`, `@JmixEntity`, `@Entity(name=...)`, UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, audit fields, enum-backed String field, no Lombok.

**Entity pattern** (lines 12-51):
```java
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "aiExposure_AiExposureRule")
@Table(name = "AI_EXPOSURE_RULE", indexes = {
        @Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", columnList = "ENTITY_NAME", unique = true),
        @Index(name = "IDX_AI_EXPOSURE_RULE_ENABLED", columnList = "ENABLED")
})
public class AiExposureRule {
    @Id @Column(name = "ID", nullable = false) @JmixGeneratedValue private UUID id;
    @Version @Column(name = "VERSION", nullable = false) private Integer version;
    @InstanceName @NotNull @Column(name = "ENTITY_NAME", nullable = false, unique = true, length = 255) private String entityName;
    @Column(name = "MODE", nullable = false, length = 16) private String mode = AiExposureRuleMode.EXCLUDE.getId();
    @Column(name = "ENABLED", nullable = false) private Boolean enabled = true;
    // audit: createdBy, createdDate, lastModifiedBy, lastModifiedDate (OffsetDateTime)
}
```

**Notes:** No `attributePath` field. `mode` enum-backed the same way `AiKnowledgeDocumentStatus` is backed in `AiKnowledgeDocument` (String field + typed getter/setter with `fromId`).

---

### 2. `com.vn.agent.exposure.LlmExposurePolicy` (component, request-response)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`

**Role:** Stateless `@Component`, constructor-injected, wraps `CurrentUserSchemaAccess` delegate-and-narrow. Mirror the class's constructor, method signatures, and import block exactly.

**Constructor + method skeleton** (analog lines 29-93):
```java
@Component
public class LlmExposurePolicy {
    private final CurrentUserSchemaAccess delegate;
    private final LlmExposureRuleRepository ruleRepository;

    public LlmExposurePolicy(CurrentUserSchemaAccess delegate,
                             LlmExposureRuleRepository ruleRepository) {
        this.delegate = delegate;
        this.ruleRepository = ruleRepository;
    }

    public Map<MetaClass, Set<String>> getReadableSchema() {
        Set<String> denied = ruleRepository.findEnabledExcludedEntityNames();
        Map<MetaClass, Set<String>> base = delegate.getReadableSchema();
        if (denied.isEmpty()) return base;
        Map<MetaClass, Set<String>> result = new LinkedHashMap<>(base);
        result.keySet().removeIf(mc -> denied.contains(mc.getName()));
        return result;
    }

    public boolean canReadEntity(MetaClass mc) {
        return delegate.canReadEntity(mc)
                && !ruleRepository.findEnabledExcludedEntityNames().contains(mc.getName());
    }

    /** Pass-through in v1.1 — attribute-level rules deferred. */
    public boolean canReadAttribute(MetaClass mc, String attrPath) {
        return delegate.canReadAttribute(mc, attrPath);
    }

    /** Ships Phase 10, no Phase 10 caller. Phase 11 mutation gating consumes this. */
    public boolean canModify(MetaClass mc) {
        CrudEntityContext ctx = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(ctx);  // inline — keep CurrentUserSchemaAccess unchanged
        return ctx.isUpdatePermitted()
                && !ruleRepository.findEnabledExcludedEntityNames().contains(mc.getName());
    }

    /** Package-private — only RetrievalFilterBuilder consumes this. */
    Set<String> getDenylistedEntityNames() {
        return ruleRepository.findEnabledExcludedEntityNames();
    }
}
```

**Pitfall:** `getReadableSchema()` must load denylist ONCE at the top, not per-entity-loop. `canReadEntity(MetaClass)` fetches its own copy (called alone from BuiltInDataTools, not in a loop).

---

### 3. `com.vn.agent.exposure.LlmExposureRuleRepository` (component, CRUD)

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java` (lines 1-10 — `UnconstrainedDataManager` import + field pattern)

**Imports/constructor pattern** (analog lines 8-21):
```java
import io.jmix.core.UnconstrainedDataManager;
// ...
@Component
public class LlmExposureRuleRepository {
    private final UnconstrainedDataManager dataManager;

    public LlmExposureRuleRepository(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Set<String> findEnabledExcludedEntityNames() {
        return dataManager.load(AiExposureRule.class)
                .query("select r from aiExposure_AiExposureRule r where r.enabled = true and r.mode = :mode")
                .parameter("mode", AiExposureRuleMode.EXCLUDE.getId())
                .store("agentstore")
                .list()
                .stream()
                .map(AiExposureRule::getEntityName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

**Notes:** `.store("agentstore")` is MANDATORY per MEMORY `feedback_jmix_loadvalue_store`. Use `UnconstrainedDataManager` (not `DataManager`) per MEMORY `feedback_jmix_unconstrained_for_system_writes`.

---

### 4. `AiExposureRuleListView` — XML

**Analog:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-list-view.xml`

**XML pattern** (analog lines 1-48):
```xml
<view xmlns="http://jmix.io/schema/flowui/view"
      title="msg:///exposureRulesList.title"
      focusComponent="exposureRulesDataGrid">
    <data>
        <collection id="exposureRulesDc" class="com.vn.agent.exposure.AiExposureRule">
            <fetchPlan extends="_base"/>
            <loader id="exposureRulesDl" readOnly="true">
                <query><![CDATA[select e from aiExposure_AiExposureRule e order by e.entityName asc]]></query>
            </loader>
        </collection>
    </data>
    <facets><dataLoadCoordinator auto="true"/></facets>
    <layout padding="true" spacing="true">
        <genericFilter id="exposureRulesFilter" dataLoader="exposureRulesDl">
            <propertyFilter property="entityName"/>
            <propertyFilter property="enabled"/>
        </genericFilter>
        <hbox id="buttonsPanel" classNames="buttons-panel">
            <button id="createBtn" action="exposureRulesDataGrid.createAction"/>
            <button id="editBtn"   action="exposureRulesDataGrid.editAction"/>
            <button id="removeBtn" action="exposureRulesDataGrid.removeAction"/>
        </hbox>
        <dataGrid id="exposureRulesDataGrid" width="100%" minHeight="20em"
                  dataContainer="exposureRulesDc" themeNames="compact">
            <actions>
                <action id="createAction" type="list_create"/>
                <action id="editAction"   type="list_edit"/>
                <action id="removeAction" type="list_remove"/>
                <action id="toggleAction" type="list_itemTracking"
                        text="msg:///exposureRulesList.column.actions"/>
            </actions>
            <columns resizable="true">
                <column property="entityName" header="msg:///exposureRulesList.column.entityName"/>
                <column key="enabled"         header="msg:///exposureRulesList.column.enabled"/>
                <column property="createdDate"/>
                <column key="toggleAction"    header="msg:///common.column.actions"/>
            </columns>
        </dataGrid>
    </layout>
</view>
```

**Notes:** `genericFilter` + `propertyFilter` per MEMORY `feedback_jmix_generic_filter`. Action column `key="enabled"` NOT `property="enabled"` per MEMORY `feedback_jmix_dual_typed_field_column`.

---

### 5. `AiExposureRuleListView` — Java Controller

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java`

**Class header + @Supply toggle renderer** (analog lines 51-54, 101-135):
```java
@Route(value = "ai-agent/exposure-rules", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiExposureRule.list")
@ViewDescriptor(path = "ai-exposure-rule-list-view.xml")
public class AiExposureRuleListView extends StandardListView<AiExposureRule> {

    @Autowired private Messages messages;
    @Autowired private UiComponents uiComponents;

    @Supply(to = "exposureRulesDataGrid.toggleAction", subject = "renderer")
    private ComponentRenderer<Button, AiExposureRule> toggleRenderer() {
        return new ComponentRenderer<>(rule -> {
            Button btn = new Button();
            if (Boolean.TRUE.equals(rule.getEnabled())) {
                btn.setText(messages.getMessage(getClass(), "exposureRulesList.action.hideFromAi"));
                btn.setIcon(VaadinIcon.EYE_SLASH.create());
                btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            } else {
                btn.setText(messages.getMessage(getClass(), "exposureRulesList.action.visibleToAi"));
                btn.setIcon(VaadinIcon.EYE.create());
                btn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
            }
            btn.addClickListener(e -> toggleEnabled(rule));
            return btn;
        });
    }
    // toggleEnabled: unconstrainedDataManager.save(rule) + exposureRulesDl.load() + publishEvent
}
```

**Notes:** Toggle uses plain `ComponentRenderer<Button, AiExposureRule>`, NOT `DataGridRenderers.buildActionsColumn` (that util is for icon-only row actions, not label-flipping toggle buttons — per RESEARCH Pattern 6 note). Badge renderer for `enabled` column: `DataGridRenderers.buildBadgeColumn(...)`.

---

### 6. `AiExposureRuleDetailView` — XML

**Analog:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml`

**XML pattern** (analog lines 1-69):
```xml
<view xmlns="http://jmix.io/schema/flowui/view"
      title="msg:///exposureRulesDetail.title.edit">
    <data>
        <instance id="exposureRuleDc" class="com.vn.agent.exposure.AiExposureRule">
            <fetchPlan extends="_base"/>
            <loader id="exposureRuleDl"/>
        </instance>
    </data>
    <facets><dataLoadCoordinator auto="true"/></facets>
    <actions>
        <action id="saveAction"  type="detail_saveClose"/>
        <action id="closeAction" type="detail_close"/>
    </actions>
    <layout>
        <formLayout id="ruleForm" dataContainer="exposureRuleDc">
            <!-- entityName populated programmatically via ComboBox, not <comboBox property="entityName"> -->
            <!-- enabled rendered as checkbox -->
        </formLayout>
        <hbox id="actionBar" spacing="true">
            <button id="saveBtn"   action="saveAction"  themeNames="primary"/>
            <button id="cancelBtn" action="closeAction"/>
        </hbox>
    </layout>
</view>
```

---

### 7. `AiExposureRuleDetailView` — Java Controller

**Analog:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java`

**Class header + init pattern** (analog lines 65-133):
```java
@Route(value = "ai-agent/exposure-rules/:id", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiExposureRule.detail")
@ViewDescriptor(path = "ai-exposure-rule-detail-view.xml")
@EditedEntityContainer("exposureRuleDc")
public class AiExposureRuleDetailView extends StandardDetailView<AiExposureRule> {

    @Autowired private Metadata metadata;
    @Autowired private MessageTools messageTools;
    @Autowired private Messages messages;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Populate Vaadin ComboBox<MetaClass> from metadata.getSession().getClasses()
        // filtered to exclude @SystemLevel + AI-* internal entity names
        List<MetaClass> items = metadata.getSession().getClasses().stream()
                .filter(mc -> !mc.getJavaClass().isAnnotationPresent(SystemLevel.class))
                .filter(mc -> !AI_INTERNAL_ENTITY_NAMES.contains(mc.getName()))
                .sorted(Comparator.comparing(mc -> messageTools.getEntityCaption(mc)))
                .collect(Collectors.toList());
        entityNameComboBox.setItems(items);
        entityNameComboBox.setItemLabelGenerator(mc ->
                messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")");
    }
}
```

---

### 8. `VectorStoreDebugView` (XML + Java)

**Analog:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/ai-audit-event-list-view.xml`

**XML layout shell** (analog lines 1-54):
```xml
<view xmlns="http://jmix.io/schema/flowui/view"
      title="msg:///vectorStoreDebug.title">
    <!-- NO <data> block — grid backed by custom DataProvider, not Jmix CollectionLoader -->
    <layout padding="true" spacing="true">
        <hbox alignItems="CENTER" width="100%">
            <span text="msg:///vectorStoreDebug.filter.label" minWidth="8em"/>
            <textField id="metadataFilterField" width="100%"/>
        </hbox>
        <hbox id="buttonsPanel" classNames="buttons-panel">
            <button id="searchBtn" text="msg:///vectorStoreDebug.action.search" themeNames="primary"/>
        </hbox>
        <dataGrid id="chunksDataGrid" width="100%" minHeight="20em" themeNames="compact">
            <!-- NO dataContainer — DataProvider set programmatically in controller -->
            <columns resizable="true">
                <column key="id"       header="msg:///vectorStoreDebug.column.id"       width="260px"/>
                <column key="content"  header="msg:///vectorStoreDebug.column.content"/>
                <column key="metadata" header="msg:///vectorStoreDebug.column.metadata"/>
            </columns>
        </dataGrid>
    </layout>
</view>
```

**Java controller pattern:**
```java
@Route(value = "ai-agent/vector-store-debug", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_VectorStoreDebug")
@ViewDescriptor(path = "vector-store-debug-view.xml")
public class VectorStoreDebugView extends StandardView {

    @Autowired private VectorStore vectorStore;
    @Autowired private Messages messages;

    @Subscribe("searchBtn")
    public void onSearchClick(final ActionPerformedEvent event) {
        String filterText = metadataFilterField.getValue();
        SearchRequest.Builder req = SearchRequest.builder()
                .query("").topK(100).similarityThreshold(0.0);
        if (filterText != null && !filterText.isBlank()) {
            try {
                Filter.Expression expr = new FilterExpressionTextParser().parse(filterText);
                req.filterExpression(expr);
            } catch (Exception e) {
                metadataFilterField.setErrorMessage(
                    messages.getMessage(getClass(), "vectorStoreDebug.error.filterParse"));
                return;
            }
        }
        List<Document> docs = vectorStore.similaritySearch(req.build());
        // map to ChunkDto, bind to grid via ListDataProvider
    }
}
```

---

### 9. `BaselineContextProvider.java` — field swap (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`

**Substitution seam** (lines 73-86, 107):
```java
// BEFORE (lines 73, 80, 86, 107):
private final CurrentUserSchemaAccess currentUserSchemaAccess;
// constructor param: CurrentUserSchemaAccess currentUserSchemaAccess
this.currentUserSchemaAccess = currentUserSchemaAccess;
// line 107: Map<MetaClass, Set<String>> readableSchema = currentUserSchemaAccess.getReadableSchema();

// AFTER (mechanical rename only):
private final LlmExposurePolicy llmExposurePolicy;
// constructor param: LlmExposurePolicy llmExposurePolicy
this.llmExposurePolicy = llmExposurePolicy;
// line 107: Map<MetaClass, Set<String>> readableSchema = llmExposurePolicy.getReadableSchema();
```

---

### 10. `BuiltInDataTools.java` — field swap (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`

**All 6 reference lines** (lines 6, 43, 55, 67, 78, then call-site lines 92, 151, 268, 274, 336):
```java
// import: CurrentUserSchemaAccess → LlmExposurePolicy
// field:  private final CurrentUserSchemaAccess currentUserSchemaAccess;
//          → private final LlmExposurePolicy llmExposurePolicy;
// constructor param rename + assignment
// All 5 call-site references (lines 92, 151, 268, 274, 336) change prefix only:
//   currentUserSchemaAccess.getReadableSchema()    → llmExposurePolicy.getReadableSchema()
//   currentUserSchemaAccess.canReadAttribute(...)  → llmExposurePolicy.canReadAttribute(...)
//   currentUserSchemaAccess.canReadEntity(...)     → llmExposurePolicy.canReadEntity(...)
// unknown_entity error strings preserved byte-for-byte (Phase 9 D-14)
```

---

### 11. `FetchPlanIntersector.java` — field swap (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java`

**Pattern:** Same mechanical swap as BuiltInDataTools — `CurrentUserSchemaAccess` import + field + constructor param renamed to `LlmExposurePolicy`. The `canReadAttribute` call-through is a semantic no-op in v1.1.

---

### 12. `RetrievalFilterBuilder.java` — add nin clause (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`

**Placement** — insert AFTER line 93 (`return scopedAnyRole.build();`), replacing that line:
```java
// Add LlmExposurePolicy constructor injection (alongside existing ragProps, embeddingProps):
private final LlmExposurePolicy llmExposurePolicy;

// Inside buildFor(), AFTER the scopedAnyRole loop (after line 93), BEFORE build():
Set<String> denied = llmExposurePolicy.getDenylistedEntityNames();
if (!denied.isEmpty()) {
    FilterExpressionBuilder.Op notInClause =
        b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied));
    scopedAnyRole = (scopedAnyRole == null)
        ? notInClause
        : b.and(scopedAnyRole, notInClause);
}
return scopedAnyRole.build();
```

**Critical:** Admin bypass check at lines 67-69 (`return null`) runs BEFORE this block — the `getDenylistedEntityNames()` call is inside the non-admin path only.

---

### 13. `ChunkMetadata.java` — add constant (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java`

**Add after line 28** (`ALLOWED_ROLES` constant):
```java
/**
 * Jmix MetaClass name of the entity this document describes.
 * Used by EXP-05 NOT IN denylist filter in RetrievalFilterBuilder.
 * Ingestion writer MUST mirror this key at write time (D-07).
 */
public static final String SOURCE_ENTITY = "source_entity";
```

---

### 14. `AiKnowledgeDocument.java` — add field (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java`

**Add after line 51** (`allowedRolesJson` field), same pattern as existing nullable fields:
```java
@Column(name = "SOURCE_ENTITY_NAME", length = 255)
private String sourceEntityName;
// getter + setter
```

---

### 15. `knowledge-base-view.xml` + controller (modified)

**Source:** existing KB view in `com/vn/agent/view/knowledge/`

**Additions to XML:**
- New `<formLayout id="uploadMetaForm">` with a `<comboBox>` for `sourceEntityName` (same metaclass helper as detail view), before the `<upload>` or beside existing roles field.
- Inside `<dataGrid id="documentsDataGrid">` `<actions>` block: `<action id="editPermissions" type="list_itemTracking" text="msg:///knowledgeBase.action.editPermissions"/>` and `<action id="reingest" type="list_itemTracking" text="msg:///knowledgeBase.action.reingest"/>`.
- New column `<column key="sourceEntity" header="msg:///knowledgeBase.column.sourceEntity"/>` after roles column, with `@Supply` renderer.

**Controller additions:** `@Subscribe("documentsDataGrid.editPermissions")` handler saves doc + calls `knowledgeDocumentService.reingest(id)`. `@Subscribe("documentsDataGrid.reingest")` handler calls reingest directly.

---

### 16. `AsyncIngestionWorker.java` — mirror sourceEntityName (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`

**Context (lines 41-49):** The existing metadata block already writes `ChunkMetadata.ALLOWED_ROLES`, `ChunkMetadata.DOCUMENT_ID`, `ChunkMetadata.EMBEDDING_MODEL`, and `role_<code>` flags. Add `SOURCE_ENTITY` at the same write point:
```java
// In the metadata enrichment block, alongside existing ALLOWED_ROLES mirroring:
if (document.getSourceEntityName() != null) {
    metadata.put(ChunkMetadata.SOURCE_ENTITY, document.getSourceEntityName());
}
```

---

### 17. `AiAgentAdminRole.java` — add policies (modified)

**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`

**Extension pattern** (analog lines 25-46):
```java
// Add to adminAccess() method:
@EntityPolicy(entityClass = AiExposureRule.class, actions = EntityPolicyAction.ALL)

// Extend adminViews() method — add to menuIds array:
"aiAgent.exposureRules.list", "aiAgent.vectorStoreDebug"

// Extend adminViews() method — add to viewIds array:
"AiAgent_AiExposureRule.list", "AiAgent_AiExposureRule.detail", "AiAgent_VectorStoreDebug"
```

---

### 18. `messages_en.properties` + `messages_vi.properties` — add keys (modified)

**Source:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`

**Naming conventions** (analog lines 1-25):
- Menu keys: `com.vn.agent/menu.exposureRules` (matches `com.vn.agent/menu.addon` pattern)
- Entity keys: `com.vn.agent.exposure/AiExposureRule` and attribute keys `com.vn.agent.exposure/AiExposureRule.entityName`
- View keys: `exposureRulesList.title`, `exposureRulesDetail.title.new`, `vectorStoreDebug.title`
- Action keys: `exposureRulesList.action.hideFromAi`, `exposureRulesList.action.visibleToAi`
- Badge keys: `exposureRulesList.badge.active`, `exposureRulesList.badge.inactive`
- Error keys: `exposureRulesList.error.toggle`, `vectorStoreDebug.error.filterParse`
- Full inventory in `10-UI-SPEC.md` §"Full Message Key Inventory" — add ALL to BOTH bundles.

---

### 19. Liquibase `060-ai-exposure-rule.xml` + `061-ai-knowledge-document-source-entity.xml` (new)

**Analog:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/050-ai-knowledge-document.xml`

**File header + changeSet pattern** (analog lines 1-40):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" ...>
    <changeSet id="1" author="ai-agent">
        <createTable tableName="AI_EXPOSURE_RULE">
            <column name="ID" type="${uuid.type}"><constraints primaryKey="true" nullable="false"/></column>
            <column name="VERSION" type="int" defaultValueNumeric="1"><constraints nullable="false"/></column>
            <column name="ENTITY_NAME" type="varchar(255)"><constraints nullable="false"/></column>
            <column name="MODE" type="varchar(16)" defaultValue="EXCLUDE"><constraints nullable="false"/></column>
            <column name="ENABLED" type="boolean" defaultValueBoolean="true"><constraints nullable="false"/></column>
            <column name="CREATED_BY" type="varchar(255)"/>
            <column name="CREATED_DATE" type="datetime"/>
            <column name="LAST_MODIFIED_BY" type="varchar(255)"/>
            <column name="LAST_MODIFIED_DATE" type="datetime"/>
        </createTable>
    </changeSet>
    <changeSet id="2" author="ai-agent">
        <addUniqueConstraint tableName="AI_EXPOSURE_RULE" columnNames="ENTITY_NAME"
                             constraintName="UNQ_AI_EXPOSURE_RULE_ENTITY_NAME"/>
        <createIndex tableName="AI_EXPOSURE_RULE" indexName="IDX_AI_EXPOSURE_RULE_ENABLED">
            <column name="ENABLED"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

**`agentstore-changelog.xml` include strategy:** Uses `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog" errorIfMissingOrEmpty="false"/>` — no manual include needed; place files in the directory and they auto-load alphabetically.

**061 file pattern:**
```xml
<changeSet id="1" author="ai-agent">
    <addColumn tableName="AI_AGENT_KNOWLEDGE_DOCUMENT">
        <column name="SOURCE_ENTITY_NAME" type="varchar(255)"/>
    </addColumn>
</changeSet>
<changeSet id="2" author="ai-agent">
    <createIndex tableName="AI_AGENT_KNOWLEDGE_DOCUMENT"
                 indexName="IDX_AI_AGENT_KNOWLEDGE_DOC_SOURCE_ENTITY">
        <column name="SOURCE_ENTITY_NAME"/>
    </createIndex>
</changeSet>
```

---

### 20. TEST-09 `LlmExposurePolicyIntegrationTest` (new)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/CurrentUserSchemaAccessTest.java`

**Test structure** (analog lines 1-50):
```java
// Unit test style (analog): Mockito mocks for AccessManager, Metadata, Session
// Phase 10 integration test: @SpringBootTest + SystemAuthenticator.withUser + seeded AiExposureRule

class LlmExposurePolicyIntegrationTest {
    @Autowired LlmExposurePolicy llmExposurePolicy;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired BaselineContextProvider baselineContextProvider;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired UnconstrainedDataManager dataManager;
    @Autowired Metadata metadata;

    @BeforeEach
    void seedRule() {
        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(targetEntity.getName());
        rule.setEnabled(true);
        dataManager.save(rule);
    }

    @ParameterizedTest
    @EnumSource(AssertionPath.class)
    void denylistedEntityDoesNotAppear(AssertionPath path) {
        systemAuthenticator.withUser("testUser", () -> {
            // assert: LIST_ENTITIES, AGENT_ENTITIES, FIND_RECORDS → no targetEntity.getName()
            // assert: FIND_RECORDS returns "unknown_entity" not "access_denied"
            return null;
        });
    }
    enum AssertionPath { LIST_ENTITIES, AGENT_ENTITIES, FIND_RECORDS, RAG_HITS }
}
```

**Notes:** RAG_HITS path is best as a dedicated unit test on `RetrievalFilterBuilder` with mocked `LlmExposurePolicy.getDenylistedEntityNames()` returning `{"Order"}` asserting `nin` in the built expression.

---

## Shared Patterns

### Constructor Injection (all services)
**Source:** `CurrentUserSchemaAccess.java` lines 34-37
All new `@Component` beans use constructor injection only — no `@Autowired` field injection. Spring beans injected via `@Autowired` in views only.

### `io.jmix.core.Messages` in views
**Source:** `ParametersListView.java` line 14, 69
```java
@Autowired
private Messages messages;
// usage: messages.getMessage(getClass(), "keyWithoutBundle")
```
Never `MessageSource`. Per MEMORY `feedback_jmix_messages_over_spring`.

### `@Subscribe` / `@Install` wiring
**Source:** `ParametersListView.java` lines 84-96, `ParametersDetailView.java` lines 111-133
All view event handlers use `@Subscribe` with explicit `id` and `target` where needed. Never `addListener(...)` in `onInit`.

### Error notification pattern
**Source:** `ParametersListView.java` lines 144-148
```java
NotificationUtils.errorWithDetail(notifications, messages, "key.error", ex);
```

---

## No Analog Found

All 18 files have analogs. No entries here.

---

## Metadata

**Analog search scope:** `ai-agent/ai-agent/src/main/java/com/vn/agent/` and `src/main/resources/com/vn/agent/`
**Files scanned:** 18 analog reads
**Pattern extraction date:** 2026-04-27
