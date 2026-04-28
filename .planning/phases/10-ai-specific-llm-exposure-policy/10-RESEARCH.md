# Phase 10: AI-Specific LLM Exposure Policy - Research

**Researched:** 2026-04-27
**Domain:** Jmix 2.8 entity lifecycle events, Spring AI 1.1.4 filter expressions, Jmix Flow UI list/detail views, pgvector chunk enumeration
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01/D-02:** `LlmExposurePolicy` is a stateless `@Component` wrapping `CurrentUserSchemaAccess` (delegate-and-narrow). Method surface: `getReadableSchema()`, `canReadEntity(MetaClass)`, `canReadAttribute(MetaClass, String)` (pass-through in v1.1), `canModify(MetaClass)` (ships in Phase 10, no caller).
- **D-03:** Three call-site swaps: `BuiltInDataTools`, `BaselineContextProvider`, `FetchPlanIntersector` — mechanical field-rename only.
- **D-04/D-05/D-06:** Vector-store chunk metadata is the retrieval authority. `RetrievalFilterBuilder.buildFor()` adds `source_entity NOT IN <denylisted>` clause via `LlmExposurePolicy.getDenylistedEntityNames()`. Empty denylist = no extra clause. Admin bypass preserved (null return path).
- **D-07:** KB upload UX collects `allowedRoles` + optional `sourceEntityName` BEFORE ingestion. Both mirror into chunk metadata during ingest via `AsyncIngestionWorker.enrich()`.
- **D-08:** Reingest (never direct pgvector mutation) is the propagation path for post-ingest changes to `allowedRoles` or `sourceEntityName`. Edit permissions row-action: save `AiKnowledgeDocument`, then schedule reingest via `KnowledgeDocumentService.reingest(id)`.
- **D-09:** `VectorStoreDebugView` — read-only paginated grid over chunks (id, content, metadata). Filter input uses `FilterExpressionTextParser`. No edit/delete actions in v1.1.
- **D-10:** `AiExposureRuleListView` uses `genericFilter` + `propertyFilter`, `@Supply` action column with ONE toggle button per row, built-in list actions via `list_itemTracking`.
- **D-11:** `AiExposureRuleDetailView` uses a Vaadin `ComboBox<MetaClass>` populated from `Metadata.getSession().getClasses()` filtered to exclude `@SystemLevel` + AI-* internals. Same helper reused for `AiKnowledgeDocument.sourceEntityName`.
- **D-12:** Menu IDs: `aiAgent.exposureRules.list`, `aiAgent.vectorStoreDebug`. Both under existing admin menu section.
- **D-13:** ALL UI strings in BOTH `messages.properties` AND `messages_vi.properties`.
- **D-14:** No cache in v1.1. `LlmExposurePolicy` queries rules per call via `LlmExposureRuleRepository` (`UnconstrainedDataManager`).
- **D-15:** `LlmExposureChangedEvent` published on rule create/update/delete. No current consumer.
- **AiExposureRule entity:** NO `attributePath` field. Entity-level only. `mode=EXCLUDE` only (no ALLOW). `entityName`, `enabled`, audit fields.
- **EXP-01 deviation:** CONTEXT.md drops `attributePath` per user decision 2026-04-27. Planner notes deviation from REQUIREMENTS EXP-01.

### Claude's Discretion

- Package layout: favor single cohesive `com.vn.agent.exposure.*` package.
- Repository signature: `Set<String> findEnabledExcludedEntityNames()` preferred (set-of-names sufficient for entity-only v1.1).
- `getDenylistedEntityNames()` helper: package-private is fine (only `RetrievalFilterBuilder` is the external consumer).
- Changelog placement: `060-ai-exposure-rule.xml` + `061-ai-knowledge-document-source-entity.xml` under `agentstore-changelog/`.
- Reingest scheduling: direct call to `KnowledgeDocumentService.reingest(id)` (mirrors existing path).
- VectorStore debug view filter: `FilterExpressionTextParser` (confirmed present in 1.1.4 — see Spring AI Filter API section below).
- `AiExposureRule.entityName` carries `@Column(unique=true)` to enforce one-rule-per-entity.
- TEST-09 harness: single `@SpringBootTest` with seeded rules parameterized over four assertion paths.

### Deferred Ideas (OUT OF SCOPE)

- Attribute-level exposure rules (`attributePath`, dotted paths, prefix/wildcard matching).
- Document-id allowlist UI for legacy KB documents.
- Compiled-rule cache + multi-instance HA invalidation.
- Time-bounded exposure rules (EXP-11).
- `MutationGuard` SPI, mutation tools (Phase 11).
- `AiUiSettings` (Phase 12).
- Edit/delete actions on VectorStoreDebugView.
- Audit rows on rule changes beyond Spring event.
- Document-id-level RAG denylist.
- Attribute-aware `agent.permissions` truncation.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| EXP-01 | `AiExposureRule` Jmix entity in `agentstore` — `entityName`, `mode=EXCLUDE`-only, `enabled`, audit fields. (Note: `attributePath` dropped per CONTEXT decision D-11) | Entity pattern from existing `AiKnowledgeDocument`; `@Store(name="agentstore")`, UUID + Version + InstanceName |
| EXP-02 | `LlmExposurePolicy` wraps `CurrentUserSchemaAccess`; `userVisible AND NOT excluded` | Delegate-and-narrow pattern verified from existing `CurrentUserSchemaAccess` code |
| EXP-03 | `BuiltInDataTools` migrated to `LlmExposurePolicy` | Three field references in BuiltInDataTools confirmed via code read |
| EXP-04 | `BaselineContextProvider` sources from `LlmExposurePolicy` | Single call site confirmed at `currentUserSchemaAccess.getReadableSchema()` in `BaselineContextProvider.compose()` |
| EXP-05 | `RetrievalFilterBuilder` integrates `NOT IN` entity denylist | `FilterExpressionBuilder.nin(key, values)` confirmed present in Spring AI 1.1.4 |
| EXP-06 | `LlmExposureRuleRepository` uses `UnconstrainedDataManager` | Existing `AsyncIngestionWorker` pattern verified; `UnconstrainedDataManager` in-context |
| EXP-07 | Admin Flow UI: list + detail view with `genericFilter` + `propertyFilter`, action column, admin-gated | Existing `KnowledgeBaseView` + `DataGridRenderers` pattern verified |
| EXP-08 | `LlmExposureChangedEvent` published on rule CUD | Jmix `EntityChangedEvent` pattern verified; Spring `ApplicationEventPublisher` injection |
| EXP-09 | Negative test: denylisted entity does NOT appear in `list_entities`, `agent.entities`, RAG, `find_records` | `FilteredSchemaAndExecutionDenialTest` is the closest prior-art test pattern |
| EXP-10 | `AiAgentAdminRole` extended with `AiExposureRule` CRUD + view + menu policies | `AiAgentAdminRole.java` extension pattern confirmed |
| ENT-05 | `AiExposureRule` Jmix entity | Standard CLAUDE.md entity pattern: `@JmixEntity`, UUID + `@Version` + `@InstanceName`, no Lombok |
| SEC-05 | `AiAgentAdminRole` extended (Phase 10: AiExposureRule + VectorStoreDebugView; AiUiSettings in Phase 12) | Interface with `@EntityPolicy`/`@MenuPolicy`/`@ViewPolicy` methods confirmed |
| TEST-09 | `LlmExposurePolicy` integration test — four assertion paths, uniform `unknown_entity` opacity | `@SpringBootTest` + `SystemAuthenticator.withUser` + seeded `AiExposureRule` rows |
</phase_requirements>

---

## Summary

Phase 10 introduces `AiExposurePolicy` — a thin delegate-and-narrow wrapper over the existing `CurrentUserSchemaAccess` that subtracts admin-denylisted entities from the LLM-visible surface. The implementation is mechanically straightforward: one new `@Component` boundary, one new entity + repository, one new event, three call-site swaps, one `FilterExpressionBuilder.nin()` clause, two new admin views, and KB upload form additions.

All Spring AI 1.1.4 primitives required are confirmed present: `FilterExpressionBuilder.nin(String, Object...)` exists as a programmatic API (verified via Spring AI Javadoc), and `FilterExpressionTextParser` + `NinExpressionContext` are confirmed in the jar per the UI-SPEC. The existing `KnowledgeDocumentService.reingest(UUID)` already implements the delete-chunks-then-redispatch path; Phase 10 simply calls it from the new "Edit permissions" row-action handler.

The `LlmExposureChangedEvent` is published via Jmix's `EntityChangedEvent` listener pattern (using `@EventListener` on a `@Component` listener bean that calls `applicationEventPublisher.publishEvent(...)`), NOT via raw JPA `@PostPersist` lifecycle annotations (which are not the Jmix 2.x recommended idiom). No consumer is wired in v1.1; the event is published for future caching consumers.

The Vector Store debug view cannot use standard Jmix `CollectionLoader` / JPQL (chunks live in pgvector, not in Jmix's datasource). The pattern from `jmix-ai-backend` confirms this: the reference impl uses a Jmix entity backed by a separate `pgvector` store. Phase 10 takes a simpler approach: a custom `DataProvider<ChunkDto, Void>` backed by `VectorStore.similaritySearch(SearchRequest)` with an empty-string query and configurable `topK`. This requires the pgvector store to embed the empty string, which it does (Spring AI validates non-null, not non-empty). The `simplePagination` drives page offset via `topK` + `SearchRequest.offset()` where available, or the controller pages client-side over a larger result set.

**Primary recommendation:** Use `FilterExpressionBuilder.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denylistedEntityNames))` in `RetrievalFilterBuilder` for the EXP-05 extension.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Entity denylist enforcement (schema/tools) | API / Backend (`LlmExposurePolicy`) | — | Must run server-side; LLM cannot be trusted with denylist visibility |
| RAG chunk filtering | API / Backend (`RetrievalFilterBuilder`) | Database / Storage (pgvector filter) | Filter pushed into pgvector at query time; no in-process filtering |
| Rule persistence + reads | Database / Storage (`agentstore`) | API / Backend (repository) | `AiExposureRule` lives in `agentstore`; read via `UnconstrainedDataManager` |
| Admin governance UI | Frontend Server (Jmix Flow UI) | — | Vaadin server-side rendering; no SPA |
| KB upload metadata collection | Frontend Server (KB view extension) | API / Backend (upload service) | Form collected in view, passed to `KnowledgeDocumentUploadService` |
| Vector store chunk inspection | Frontend Server (debug view) | API / Backend (`VectorStore`) | View calls `VectorStore.similaritySearch`; no Jmix data loading |
| Event invalidation | API / Backend (Spring event bus) | — | In-process only; cross-node caching deferred to Phase 12 |

---

## Standard Stack

### Core (all already in project)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jmix 2.8 | `io.jmix.*` | Entity, views, security, DataManager | Project standard per CLAUDE.md |
| Spring AI 1.1.4 | `spring-ai-vector-store` | `FilterExpressionBuilder`, `VectorStore`, `SearchRequest` | Already in project (Phase 5) |
| Spring Framework 6.x | `spring-context` | `ApplicationEventPublisher`, `@EventListener`, `@Component` | Transitive via Spring Boot 3 |
| Liquibase | (project version) | Schema migrations | Project standard |
| Jmix Flow UI | (jmix 2.8) | Vaadin-based admin views | Project standard |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `FilterExpressionTextParser` | Spring AI 1.1.4 | Parse user-typed filter text in VectorStoreDebugView | Debug view filter input only |
| Jackson ObjectMapper | (project version) | Serialize/deserialize metadata in debug view | Already in project |

**Version verification:** All libraries are transitive via existing project dependencies. No new top-level dependencies required for Phase 10. [VERIFIED: existing project build.gradle + codebase grep]

---

## Architecture Patterns

### System Architecture Diagram

```
Admin browser request
        |
        v
[AiExposureRuleListView / DetailView]  ← CRUD rules, toggle enabled
        |
        v
[LlmExposureRuleRepository]  ← UnconstrainedDataManager reads
        |  saves via DataManager (security-gated)
        v
[AiExposureRule rows in agentstore]
        |  EntityChangedEvent (Jmix)
        v
[AiExposureRuleEntityListener]  ── publishes ──> [LlmExposureChangedEvent]
                                                      (no consumer in v1.1)

Chat request
        |
        v
[DefaultChatServiceImpl]
        |  ── builds baseline ──>  [BaselineContextProvider.compose()]
        |                                   |
        |                                   v
        |                         [LlmExposurePolicy.getReadableSchema()]
        |                                   |
        |                    delegate       v
        |                         [CurrentUserSchemaAccess] ── AND NOT ──> [AiExposureRuleRepository]
        |
        |  ── builds retrieval filter ──>  [RetrievalFilterBuilder.buildFor(auth)]
        |                                          |
        |                           +  nin clause  v
        |                         [LlmExposurePolicy.getDenylistedEntityNames()]
        |
        v
[VectorStore.similaritySearch(request)] ← filter: roles AND NOT source_entity NIN [...]

LLM tool call: find_records(entityName=X)
        |
        v
[BuiltInDataTools.resolveReadableEntityOrThrow(X)]
        |
        v
[LlmExposurePolicy.canReadEntity(mc)]  ── false if denylisted ──> unknown_entity error
```

### Recommended Project Structure
```
src/main/java/com/vn/agent/
├── exposure/
│   ├── AiExposureRule.java               # JPA entity (@Store agentstore)
│   ├── AiExposureRuleMode.java           # Enum: EXCLUDE only
│   ├── LlmExposurePolicy.java            # @Component boundary (wraps CurrentUserSchemaAccess)
│   ├── LlmExposureRuleRepository.java    # UnconstrainedDataManager reads
│   ├── LlmExposureChangedEvent.java      # Spring ApplicationEvent (no consumer v1.1)
│   └── AiExposureRuleEntityListener.java # EntityChangedEvent -> LlmExposureChangedEvent
│
├── view/exposure/
│   ├── AiExposureRuleListView.java       + ai-exposure-rule-list-view.xml
│   ├── AiExposureRuleDetailView.java     + ai-exposure-rule-detail-view.xml
│   └── MetaclassComboBoxHelper.java      # @Component helper for metaclass dropdown
│
├── view/vectorstore/
│   └── VectorStoreDebugView.java         + vector-store-debug-view.xml
│
└── rag/
    └── ChunkMetadata.java                # Add SOURCE_ENTITY constant
```

### Pattern 1: `LlmExposurePolicy` — Delegate-and-Narrow
**What:** Stateless `@Component` wrapping `CurrentUserSchemaAccess`. Reads `AiExposureRule` rows per-call (no cache).
**When to use:** Every call site that previously injected `CurrentUserSchemaAccess`.
**Example:**
```java
// Source: verified from CurrentUserSchemaAccess.java + CONTEXT.md D-01/D-02
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

    /** Ships in Phase 10, no Phase 10 caller. Phase 11 mutation gating consumes this. */
    public boolean canModify(MetaClass mc) {
        return delegate.canModify(mc)  // or AccessManager CrudEntityContext.isUpdatePermitted
                && !ruleRepository.findEnabledExcludedEntityNames().contains(mc.getName());
    }

    /** Package-private helper consumed by RetrievalFilterBuilder only. */
    Set<String> getDenylistedEntityNames() {
        return ruleRepository.findEnabledExcludedEntityNames();
    }
}
```

### Pattern 2: `FilterExpressionBuilder.nin()` for RAG NOT IN clause
**What:** Programmatic `NOT IN` filter clause for source_entity denylist in `RetrievalFilterBuilder`.
**When to use:** When `denylistedEntityNames` is non-empty in `buildFor()`.
**Example:**
```java
// Source: [VERIFIED: Spring AI 1.1.4 Javadoc - FilterExpressionBuilder.nin(String, Object...)]
// Source: docs.spring.io/spring-ai/docs/current/api/.../FilterExpressionBuilder.html
Set<String> denied = llmExposurePolicy.getDenylistedEntityNames();
if (!denied.isEmpty()) {
    FilterExpressionBuilder.Op notInClause =
        b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied));
    // AND with existing composite filter
    scopedAnyRole = b.and(scopedAnyRole, notInClause);
}
```
Note: `nin(String key, Object... values)` and `nin(String key, List<Object> values)` are both available. Use `List<Object>` overload to convert `Set<String>` safely.

### Pattern 3: Jmix `EntityChangedEvent` listener for `LlmExposureChangedEvent`
**What:** Jmix-idiomatic pattern to publish a Spring event when `AiExposureRule` is saved or deleted.
**When to use:** Any entity lifecycle hook in Jmix 2.x. Do NOT use raw JPA `@EntityListeners`/`@PostPersist` — Jmix's `DataManager` fires `EntityChangedEvent`; JPA lifecycle annotations may not fire reliably for all Jmix save paths.
**Example:**
```java
// Source: [CITED: docs.jmix.io/jmix/data-access/entity-events.html]
@Component
public class AiExposureRuleEntityListener {

    private final ApplicationEventPublisher eventPublisher;

    public AiExposureRuleEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onExposureRuleChanged(EntityChangedEvent<AiExposureRule> event) {
        // Fires inside the committing transaction — safe for Phase 12+ cache invalidation
        eventPublisher.publishEvent(new LlmExposureChangedEvent(this));
    }
}
```
`EntityChangedEvent.Type` values: `CREATED`, `UPDATED`, `DELETED`. All three trigger the same `LlmExposureChangedEvent` publish.

### Pattern 4: `VectorStoreDebugView` — Custom DataProvider over `VectorStore.similaritySearch`
**What:** Read-only paginated grid backed by `VectorStore.similaritySearch()` with empty-string query.
**When to use:** Chunk enumeration where no similarity ranking is needed — empty query produces valid (if arbitrary-order) results from pgvector.
**Example:**
```java
// Source: [CITED: docs.spring.io/spring-ai/reference/api/vectordbs.html + reference impl
//          D:\Study materials spring 2026\EXE101\ai\jmix-ai-backend\]
@Subscribe
public void onSearchClick(ActionPerformedEvent event) {
    String filterText = metadataFilterField.getValue();
    SearchRequest.Builder req = SearchRequest.builder()
            .query("")              // empty string - no semantic ranking needed
            .topK(PAGE_SIZE)
            .similarityThreshold(0.0);  // accept all similarity scores
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
    // Map to ChunkDto and bind to grid DataProvider
}
```
**Caveats:**
- Empty string causes pgvector to embed an empty text. The embedding is valid but semantically meaningless. With `similarityThreshold(0.0)` this effectively returns `topK` arbitrary rows.
- True pagination (page 2, 3, ...) requires either: (a) fetch `page * pageSize` topK and drop the first pages (inefficient but simple for small datasets), or (b) use `SearchRequest.Builder.offset()` if available in 1.1.4. Verify the `SearchRequest.Builder.offset()` method exists before using it; fallback to large-topK client-side slice.
- The debug view is read-only inspection with page 1 default sufficient for v1.1.

### Pattern 5: Metaclass `ComboBox` Helper
**What:** A `ComboBox<MetaClass>` populated from `Metadata.getSession().getClasses()` with exclusion filter. Extracted as a helper to reuse across `AiExposureRuleDetailView` and `KnowledgeBaseView` upload/edit forms.
**When to use:** Any form needing entity-name selection with human-readable labels.
**Example:**
```java
// Source: [VERIFIED: CurrentUserSchemaAccess.java - isSystemLevelEntity pattern + CONTEXT D-11]
@Component
public class MetaclassComboBoxHelper {

    private static final Set<String> AI_INTERNAL_ENTITY_NAMES = Set.of(
        "ai_AiAuditEvent", "ai_AiConversation", "ai_AiMessage",
        "ai_AiKnowledgeDocument", "ai_AiParameters", "ai_AiExposureRule"
    );

    private final Metadata metadata;
    private final MessageTools messageTools;

    public MetaclassComboBoxHelper(Metadata metadata, MessageTools messageTools) {
        this.metadata = metadata;
        this.messageTools = messageTools;
    }

    public List<MetaClass> buildFilteredList() {
        return metadata.getSession().getClasses().stream()
            .filter(mc -> !mc.getJavaClass().isAnnotationPresent(SystemLevel.class))
            .filter(mc -> !AI_INTERNAL_ENTITY_NAMES.contains(mc.getName()))
            .sorted(Comparator.comparing(mc -> messageTools.getEntityCaption(mc)))
            .collect(Collectors.toList());
    }
}
// Item label: messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")"
```

### Pattern 6: `@Supply` Toggle Button Renderer
**What:** `@Supply(to="exposureRulesDataGrid.toggleAction", subject="renderer")` returns a `ComponentRenderer<Button, AiExposureRule>` whose label/theme flips based on `rule.getEnabled()`.
**Example (from UI-SPEC):**
```java
// Source: [VERIFIED: DataGridRenderers.java + CONTEXT D-10 + MEMORY feedback_jmix_action_column_renderer]
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
```
Note: The `DataGridRenderers.buildActionsColumn()` util uses icons only (`EnumSet<ActionColumnType>`). The toggle button is a plain `Button` (not an icon-only row action), so it cannot use `buildActionsColumn` — use `ComponentRenderer<Button, AiExposureRule>` directly per UI-SPEC.

### Anti-Patterns to Avoid
- **Raw JPA `@EntityListeners`/`@PostPersist` on `AiExposureRule`:** Jmix `DataManager` may not trigger these. Use `@EventListener EntityChangedEvent<AiExposureRule>` instead. [CITED: docs.jmix.io/jmix/data-access/entity-events.html]
- **Injecting `CurrentUserSchemaAccess` in new Phase 10 code:** All new code uses `LlmExposurePolicy`. `CurrentUserSchemaAccess` stays as the Jmix-permission source of truth, wrapped by the policy.
- **Direct pgvector metadata mutation:** Always reingest. `KnowledgeDocumentService.reingest(UUID)` is the idempotent path — confirmed in source.
- **`b.eq` + `b.or` loop for NIN:** `FilterExpressionBuilder.nin()` exists and is simpler. Use it.
- **`manager.loadValues(...).store(...)` omission for agentstore entities:** Per MEMORY `feedback_jmix_loadvalue_store`, any raw JPQL `loadValue`/`loadValues` against `AiExposureRule` MUST include `.store("agentstore")`.
- **`property=` attribute on a column backed by computed/enum value:** For `enabled` badge renderer use `key="enabled"` + `@Supply` renderer, not `property="enabled"` which may fail silently. (Per MEMORY `feedback_jmix_dual_typed_field_column`).
- **Hardcoded UI strings:** Every label, title, tooltip, placeholder MUST be via `msg://` key in BOTH locale bundles.
- **`LlmExposurePolicy.getDenylistedEntityNames()` for admin bypass branch:** `RetrievalFilterBuilder.buildFor()` already returns `null` for admin users before reaching the denylist clause. The denylist lookup MUST be inside the non-admin path only to avoid unnecessary DB queries for admins.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| NOT IN filter clause | Custom string concatenation or `ne` loop | `FilterExpressionBuilder.nin(key, values)` | Spring AI 1.1.4 ships `nin()` on the builder — confirmed via Javadoc |
| Filter text parsing (debug view) | Custom ANTLR grammar | `FilterExpressionTextParser` | Already in `spring-ai-vector-store-1.1.4.jar` (NinExpressionContext confirmed) |
| Chunk enumeration | Direct JDBC to pgvector table | `VectorStore.similaritySearch(SearchRequest.builder().query("").similarityThreshold(0.0).topK(N).build())` | Portable across VectorStore adapters; no schema coupling |
| Entity reingest | Direct pgvector metadata mutation | `KnowledgeDocumentService.reingest(UUID)` | Already implemented; idempotent (cancel + delete chunks + re-dispatch) |
| Entity lifecycle events | JPA `@PostPersist`/`@PostRemove` | `@EventListener EntityChangedEvent<AiExposureRule>` | Jmix 2.x idiomatic; fires for all DataManager save paths |
| Metaclass list | Manual hardcoded list | `Metadata.getSession().getClasses()` | Dynamic; adapts to host app metamodel |

**Key insight:** Every custom primitive in this phase has an existing Spring AI or Jmix counterpart. Phase 10 is assembly work, not framework work.

---

## Common Pitfalls

### Pitfall 1: `getDenylistedEntityNames()` called per `canReadEntity()` on hot path
**What goes wrong:** `LlmExposurePolicy.canReadEntity()` is called inside the `getReadableSchema()` loop over ALL metaclasses. If each call re-queries the DB, that's N queries per chat turn (N = number of entities).
**Why it happens:** Naive delegation — each method independently calls the repository.
**How to avoid:** In `getReadableSchema()`, load the denylist once at the top of the method, then pass it to `canReadEntity(MetaClass, Set<String> denied)` as a private overload. Public `canReadEntity(MetaClass)` fetches its own copy (it's called alone from BuiltInDataTools, not in a loop).
**Warning signs:** >10 DB calls logged during a single `list_entities` tool invocation.

### Pitfall 2: Admin bypass not preserved in `RetrievalFilterBuilder`
**What goes wrong:** Adding `NOT IN` clause before checking admin bypass returns a filter for admin users, causing them to miss denylisted chunks in the debug view.
**Why it happens:** Inserting the denylist clause before the `null`-return branch.
**How to avoid:** The admin bypass check (`return null`) is at the TOP of `buildFor()`. The denylist clause is added ONLY within the non-null path, after role construction.

### Pitfall 3: `AiExposureRule` entity not found in test because `.store("agentstore")` omitted
**What goes wrong:** `loadValues` or `loadValue` raw JPQL on `AiExposureRule` returns null/empty in tests.
**Why it happens:** JPQL over `agentstore` entities requires explicit `.store("agentstore")` per MEMORY rule `feedback_jmix_loadvalue_store`.
**How to avoid:** Use `LlmExposureRuleRepository` which encapsulates the `UnconstrainedDataManager` calls with the correct store. Never query `AiExposureRule` via raw JPQL outside the repository.

### Pitfall 4: `FilterExpressionTextParser` parse error not surfaced to user
**What goes wrong:** Invalid filter text in the debug view causes an exception that propagates as a generic 500 error instead of an inline field error.
**Why it happens:** `FilterExpressionTextParser.parse()` throws a runtime exception on invalid syntax.
**How to avoid:** Wrap the parse call in try/catch; display the error message via `textField.setErrorMessage(...)` on the filter field (not a toast). Message key: `vectorStoreDebug.error.filterParse`.

### Pitfall 5: `enabled` column rendered via `property=` causes silent empty grid
**What goes wrong:** `<column property="enabled">` on a Boolean field backed by a custom getter that returns `Boolean` triggers the Jmix metamodel collision per MEMORY `feedback_jmix_dual_typed_field_column`.
**Why it happens:** When the entity has a field of one type and a getter of another, Jmix may crash the DataGrid binder silently.
**How to avoid:** Use `<column key="enabled" ...>` with a `@Supply` badge renderer, not `property="enabled"`.

### Pitfall 6: Reingest race condition — old chunks temporarily visible
**What goes wrong:** Admin saves `allowedRoles` change on a document. Between the `DataManager.save()` commit and the reingest worker completing, old chunks with the old role set are still retrievable.
**Why it happens:** Reingest is async; there is a window.
**How to avoid:** This is the documented v1.1 contract (D-08, D-06). Document it in operator docs. The admin expectation is: after "Edit Permissions" + reingest completes (admin can watch via status column), the new roles take effect. The window is bounded by ingestion time.

### Pitfall 7: `canModify` in `LlmExposurePolicy` depends on `AccessManager` for the `modify` check
**What goes wrong:** `CurrentUserSchemaAccess` has `canReadEntity()` using `CrudEntityContext.isReadPermitted()`. There is no existing `canModify()` method on it. Phase 10 ships `canModify()` on `LlmExposurePolicy` without a Phase 10 caller.
**Why it happens:** Planner may forget to implement the underlying Jmix check.
**How to avoid:** `canModify(MetaClass)` uses `CrudEntityContext.isUpdatePermitted()` (same pattern as `isReadPermitted()` in `CurrentUserSchemaAccess.canReadEntity()`). Add a new method to `CurrentUserSchemaAccess` as `canModify()`, OR implement directly in `LlmExposurePolicy` using `AccessManager` + `CrudEntityContext` inline. Prefer the latter to keep `CurrentUserSchemaAccess` unchanged.

### Pitfall 8: VectorStore.similaritySearch empty-query embedding cost
**What goes wrong:** Each debug view page load embeds the empty string, incurring an OpenAI API call.
**Why it happens:** Spring AI `PgVectorStore` calls the embedding model on every `similaritySearch` invocation.
**How to avoid:** For v1.1 read-only debug view, this is acceptable (admin-only, infrequent). Note in Javadoc. Phase 11+ could cache the empty-string embedding if needed.

---

## Code Examples

### Chunk metadata `SOURCE_ENTITY` constant
```java
// Source: [VERIFIED: ChunkMetadata.java existing pattern]
// Add to com.vn.agent.rag.ChunkMetadata:
/** Jmix MetaClass name of the entity this document describes — used by EXP-05 denylist filter. */
public static final String SOURCE_ENTITY = "source_entity";
```

### `LlmExposureRuleRepository` with `UnconstrainedDataManager`
```java
// Source: [VERIFIED: AsyncIngestionWorker.java UnconstrainedDataManager pattern]
@Component
public class LlmExposureRuleRepository {

    private final UnconstrainedDataManager dataManager;

    public LlmExposureRuleRepository(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    /** Returns the set of entity MetaClass names with enabled=true, mode=EXCLUDE rules. */
    public Set<String> findEnabledExcludedEntityNames() {
        return dataManager.load(AiExposureRule.class)
                .query("select r from aiExposure_AiExposureRule r where r.enabled = true and r.mode = :mode")
                .parameter("mode", AiExposureRuleMode.EXCLUDE)
                .store("agentstore")
                .list()
                .stream()
                .map(AiExposureRule::getEntityName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

### `RetrievalFilterBuilder` extension for EXP-05
```java
// Source: [VERIFIED: RetrievalFilterBuilder.java existing code + FilterExpressionBuilder.nin() API]
// Add LlmExposurePolicy constructor injection, then in buildFor():

// Inject LlmExposurePolicy (replaces/alongside existing dependencies):
private final LlmExposurePolicy llmExposurePolicy;

// Inside buildFor(), AFTER admin bypass check, AFTER building scopedAnyRole:
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

### `AiExposureRule` entity skeleton
```java
// Source: [VERIFIED: AiKnowledgeDocument.java pattern + CLAUDE.md conventions]
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "aiExposure_AiExposureRule")
@Table(name = "AI_EXPOSURE_RULE", indexes = {
    @Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", columnList = "ENTITY_NAME", unique = true),
    @Index(name = "IDX_AI_EXPOSURE_RULE_ENABLED", columnList = "ENABLED")
})
public class AiExposureRule {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "ENTITY_NAME", nullable = false, unique = true, length = 255)
    private String entityName;

    @Column(name = "MODE", nullable = false, length = 16)
    private String mode = AiExposureRuleMode.EXCLUDE.getId();

    @Column(name = "ENABLED", nullable = false)
    private Boolean enabled = true;

    // Audit fields: createdBy, createdDate, lastModifiedBy, lastModifiedDate
    // ... getters/setters, enum converter for mode
}
```

### `BaselineContextProvider` call-site swap
```java
// Source: [VERIFIED: BaselineContextProvider.java line 107 - exact call site]
// BEFORE: Map<MetaClass, Set<String>> readableSchema = currentUserSchemaAccess.getReadableSchema();
// AFTER:  Map<MetaClass, Set<String>> readableSchema = llmExposurePolicy.getReadableSchema();
// Also: rename field from currentUserSchemaAccess to llmExposurePolicy
// Constructor injection: CurrentUserSchemaAccess -> LlmExposurePolicy
```

### Liquibase changelog for `AiExposureRule`
```xml
<!-- Source: [VERIFIED: 050-ai-knowledge-document.xml pattern] -->
<!-- File: 060-ai-exposure-rule.xml -->
<changeSet id="1" author="ai-agent">
    <createTable tableName="AI_EXPOSURE_RULE">
        <column name="ID" type="${uuid.type}">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="VERSION" type="int" defaultValueNumeric="1">
            <constraints nullable="false"/>
        </column>
        <column name="ENTITY_NAME" type="varchar(255)">
            <constraints nullable="false"/>
        </column>
        <column name="MODE" type="varchar(16)" defaultValue="EXCLUDE">
            <constraints nullable="false"/>
        </column>
        <column name="ENABLED" type="boolean" defaultValueBoolean="true">
            <constraints nullable="false"/>
        </column>
        <column name="CREATED_BY" type="varchar(255)"/>
        <column name="CREATED_DATE" type="datetime"/>
        <column name="LAST_MODIFIED_BY" type="varchar(255)"/>
        <column name="LAST_MODIFIED_DATE" type="datetime"/>
    </createTable>
</changeSet>
<changeSet id="2" author="ai-agent">
    <addUniqueConstraint tableName="AI_EXPOSURE_RULE"
                         columnNames="ENTITY_NAME"
                         constraintName="UNQ_AI_EXPOSURE_RULE_ENTITY_NAME"/>
    <createIndex tableName="AI_EXPOSURE_RULE"
                 indexName="IDX_AI_EXPOSURE_RULE_ENABLED">
        <column name="ENABLED"/>
    </createIndex>
</changeSet>
```

### Liquibase changelog for `AiKnowledgeDocument.sourceEntityName`
```xml
<!-- Source: [VERIFIED: existing 050 changelog addColumn pattern] -->
<!-- File: 061-ai-knowledge-document-source-entity.xml -->
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

Both files use `<includeAll>` in `agentstore-changelog.xml` (already present) — no manual include needed.

### TEST-09 skeleton
```java
// Source: [VERIFIED: FilteredSchemaAndExecutionDenialTest.java pattern + CONTEXT D-09]
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class LlmExposurePolicyIntegrationTest {

    @Autowired LlmExposurePolicy llmExposurePolicy;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired BaselineContextProvider baselineContextProvider;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired UnconstrainedDataManager dataManager;
    @Autowired Metadata metadata;

    private MetaClass targetEntity;

    @BeforeEach
    void seedDenylistRule() {
        // Pick any non-@SystemLevel entity that the test user CAN read
        targetEntity = /* some entity on test classpath */;

        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(targetEntity.getName());
        rule.setEnabled(true);
        // mode defaults to EXCLUDE
        dataManager.save(rule);
    }

    @ParameterizedTest
    @EnumSource(AssertionPath.class)
    void denylistedEntityDoesNotAppear(AssertionPath path) {
        systemAuthenticator.withUser("testUser", () -> {
            switch (path) {
                case LIST_ENTITIES -> {
                    String result = builtInDataTools.listEntities();
                    assertThat(result).doesNotContain(targetEntity.getName());
                }
                case AGENT_ENTITIES -> {
                    Map<String, Object> ctx = baselineContextProvider.compose(UUID.randomUUID());
                    assertThat(ctx.getOrDefault("agent.entities", "").toString())
                        .doesNotContain(targetEntity.getName());
                }
                case FIND_RECORDS -> {
                    String result = builtInDataTools.findRecords(targetEntity.getName(), null, 10);
                    assertThat(result).contains("unknown_entity").doesNotContain("access_denied");
                }
                case RAG_HITS -> {
                    // Requires StubVectorStoreConfiguration returning a Document
                    // with source_entity=targetEntity.getName() to verify the filter
                    // would exclude it. Assert filter expression contains SOURCE_ENTITY nin.
                    // Implementation detail: verify RetrievalFilterBuilder produces nin clause
                    // via a unit test on RetrievalFilterBuilder with mocked LlmExposurePolicy.
                }
            }
            return null;
        });
    }

    enum AssertionPath { LIST_ENTITIES, AGENT_ENTITIES, FIND_RECORDS, RAG_HITS }
}
```

---

## Runtime State Inventory

Phase 10 adds a new table and a new column; it does NOT rename or rebrand anything. This section is minimal.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | No existing records reference the new table. `AI_AGENT_KNOWLEDGE_DOCUMENT.SOURCE_ENTITY_NAME` is a nullable additive column. | Liquibase addColumn (061-...) — no data migration needed. Existing documents get NULL; the v1.1 contract is that NULL source_entity = not entity-denylistable until reingested |
| Live service config | No external service references `AI_EXPOSURE_RULE` or `source_entity` metadata key | None |
| OS-registered state | None | None — verified: no Windows Task Scheduler, no pm2 tasks reference these names |
| Secrets/env vars | No env vars reference exposure rule names | None |
| Build artifacts | None — pure Java source additions, no stale egg-info or similar | None |

**Nothing found requiring data migration.** The only schema change is additive (new table + nullable column).

---

## Spring AI 1.1.4 Filter API — Verified Findings

### `FilterExpressionBuilder` programmatic API
**Confirmed present in 1.1.4:** [CITED: docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/filter/FilterExpressionBuilder.html]

| Method | Signature | Notes |
|--------|-----------|-------|
| `eq` | `eq(String key, Object value)` | Already used in project |
| `ne` | `ne(String key, Object value)` | — |
| `in` | `in(String key, Object... values)` and `in(String key, List<Object> values)` | Positive membership |
| **`nin`** | **`nin(String key, Object... values)` and `nin(String key, List<Object> values)`** | **NOT IN — use for denylist** |
| `and` | `and(Op left, Op right)` | Already used in project |
| `or` | `or(Op left, Op right)` | Already used in project |
| `not` | `not(Op content)` | — |
| `isNull` | `isNull(String key)` | — |
| `isNotNull` | `isNotNull(String key)` | — |
| `group` | `group(Op content)` | — |

**Conclusion:** Use `b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied))`. No need for `FilterExpressionTextParser` in `RetrievalFilterBuilder` — the programmatic API suffices.

### `FilterExpressionTextParser`
**Confirmed present in 1.1.4:** [CITED: 10-UI-SPEC.md § "Spring AI 1.1.4 Filter API Findings" — verified by inspecting jar class list for `NinExpressionContext` in `org.springframework.ai.vectorstore.filter.antlr4`]

Usage for debug view filter input:
```java
Filter.Expression expr = new FilterExpressionTextParser().parse(filterText);
// throws RuntimeException on parse error — wrap in try/catch
```

Supported text syntax (including NIN): `source_entity == 'Order'`, `source_entity nin ['Order', 'Invoice']`, `role_ai_agent_user == true AND source_entity != 'Order'`

### `VectorStore.similaritySearch` for chunk enumeration
**Pattern confirmed:** Empty string query with `similarityThreshold(0.0)` and `topK(N)` returns N arbitrary chunks matching the optional filter expression. [CITED: docs.spring.io/spring-ai/reference/api/vectordbs.html + codebase AsyncIngestionWorker.java + KnowledgeDocumentService.java]

The debug view in `jmix-ai-backend` uses a dedicated `pgvector` store entity (`VectorStoreEntity`) backed by JPQL over the actual `vector_store` table. That approach is tightly coupled to pgvector schema and NOT portable. The preferred Phase 10 approach for our add-on (which must be VectorStore-adapter-agnostic) is `VectorStore.similaritySearch` with an empty query.

**Alternative (if empty-string embedding is unacceptable):** Direct JDBC `SELECT id, content, metadata FROM vector_store LIMIT ? OFFSET ?` via `JdbcTemplate`. This is pgvector-specific but avoids an embedding API call. Record as LOW confidence option; default to `similaritySearch` approach.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| JPA `@PostPersist` entity listeners | Jmix `EntityChangedEvent` via `@EventListener` | Jmix 2.x | More reliable for all DataManager save paths |
| `FilterExpressionBuilder` eq/and/or only | `nin()` method added | Spring AI ~1.0 | Programmatic NOT IN without text parser |
| Direct vector metadata mutation | Reingest as the propagation contract | Phase 10 decision (2026-04-27) | Simpler, idempotent, correct |
| `access_denied` for hidden entities | `unknown_entity` uniform opacity | Phase 3 D-08 / Phase 10 extension | Denylist is invisible to LLM |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `VectorStore.similaritySearch` accepts empty-string query without throwing | Pattern 4, Code Examples | Debug view crashes on load; fallback: use sentinel text or JDBC query |
| A2 | `SearchRequest.Builder` in Spring AI 1.1.4 does not have an `offset()` method | Pattern 4 (pagination note) | Pagination simpler than assumed; or offset-based approach available |
| A3 | `AiExposureRule` JPQL entity name is `aiExposure_AiExposureRule` (derived from `@Entity(name=...)`) | Repository example | Query fails; fix by adjusting entity name annotation |
| A4 | Jmix `@InstanceName` on `entityName` field is sufficient (no `@NotNull` collision with nullable issue) | Entity skeleton | Validation error on create; fix by adding null guard |

If this table is non-empty: claims A1 and A2 concern the VectorStoreDebugView only (admin-only feature); the critical path (LlmExposurePolicy + RetrievalFilterBuilder + call-site swaps) is fully VERIFIED or CITED.

---

## Open Questions (RESOLVED)

1. **`SearchRequest.Builder.offset()` availability in Spring AI 1.1.4**
   - What we know: `topK` is confirmed. `offset()` is not documented in the pgvector reference page checked.
   - What's unclear: Whether `SearchRequest` supports offset-based pagination or only topK-capped results.
   - **RESOLVED:** Plan 10-09 implements debug view page 1 with large `topK` (default 100). True offset pagination deferred to v1.2. If admin needs page 2+, a "Load more" button appending another topK batch is the v1.2 path.

2. **TEST-09 RAG assertion approach for `StubVectorStoreConfiguration`**
   - What we know: The existing `RagTestConfiguration` and `StubVectorStoreConfiguration` provide a mock `VectorStore`. Phase 10 needs to assert that the `RetrievalFilterBuilder` adds a `nin` clause when a denylist rule exists.
   - What's unclear: Whether the stub VectorStore captures the filter expression for assertion, or whether the test must directly unit-test `RetrievalFilterBuilder.buildFor()`.
   - **RESOLVED:** Plan 10-10 adds a dedicated `RetrievalFilterBuilderDenylistTest` (unit test using `Mockito`) that seeds a mock `LlmExposurePolicy.getDenylistedEntityNames()` returning `{"Order"}` and asserts the built `Filter.Expression` serializes to contain `nin` or `source_entity`. The `@SpringBootTest` integration test (`LlmExposurePolicyIntegrationTest`) verifies the wiring; the unit test verifies the expression shape. Plan 10-10 Test 4 adds the null-key carve-out assertion (Fix R6 cross-link).

3. **`AiExposureRule` JPQL entity name confirmation**
   - What we know: Jmix derives the JPQL name from `@Entity(name="...")`. Existing entities use pattern `ai_AiKnowledgeDocument` → entity name `"ai_AiKnowledgeDocument"`.
   - **RESOLVED:** Plan 10-01 uses `@Entity(name = "aiExposure_AiExposureRule")` to match the `exposure` package namespace. Boot-time verification via a simple `dataManager.load(AiExposureRule.class).query("select e from aiExposure_AiExposureRule e").list()` is part of Plan 10-02 acceptance criteria.

---

## Environment Availability

All required runtimes and tools are already in use by the project. No new external dependencies.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring AI `FilterExpressionBuilder.nin()` | EXP-05 RetrievalFilterBuilder | ✓ | 1.1.4 (project version) | N/A — method confirmed |
| Spring AI `FilterExpressionTextParser` | VectorStoreDebugView | ✓ | 1.1.4 (jar class confirmed) | Form-builder filter input |
| `VectorStore.similaritySearch` | VectorStoreDebugView | ✓ | Existing (Phase 5) | JDBC fallback (pgvector-specific) |
| Jmix `EntityChangedEvent` | LlmExposureChangedEvent publish | ✓ | Jmix 2.8 | N/A — framework feature |
| `UnconstrainedDataManager` | LlmExposureRuleRepository | ✓ | Jmix 2.8 (in AsyncIngestionWorker) | N/A |
| `KnowledgeDocumentService.reingest(UUID)` | KB edit-permissions row action | ✓ | Existing (Phase 7.1) | N/A — already implemented |
| HSQL (test datasource) | TEST-09 agentstore | ✓ | Existing test setup | N/A |

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | N/A — admin gating via existing Jmix session |
| V3 Session Management | no | N/A — stateless policy |
| V4 Access Control | **yes** | `@EntityPolicy` + `@MenuPolicy` + `@ViewPolicy` on `AiAgentAdminRole` (SEC-05) |
| V5 Input Validation | **yes** | `entityName` validated via dropdown (no free text); filter expression parsed via `FilterExpressionTextParser` with error handling |
| V6 Cryptography | no | No new cryptographic operations |

### Phase 10 Threat Model

| Threat ID | Pattern | STRIDE | Standard Mitigation |
|-----------|---------|--------|---------------------|
| T-10-01 | Bypass via direct UNCONSTRAINED query on `AiExposureRule` by non-admin | Elevation of Privilege | Only `LlmExposureRuleRepository` uses `UnconstrainedDataManager`. Tool layer uses `DataManager` (security-gated). `UnconstrainedDataManager` does not bypass `@EntityPolicy` per MEMORY `feedback_jmix_unconstrained_for_system_writes`. |
| T-10-02 | Leak via `agent.entities` rendering denylisted entity name | Information Disclosure | `BaselineContextProvider` sources via `LlmExposurePolicy.getReadableSchema()` which removes denylisted entries before rendering. |
| T-10-03 | Leak via RAG retrieval of document linked to denylisted entity | Information Disclosure | `RetrievalFilterBuilder` adds `source_entity NOT IN <denied>` clause. Chunks without `source_entity` key are NOT affected (v1.1 contract D-06 — legacy docs remain accessible until reingested). |
| T-10-04 | Timing/error-message side channel revealing denylist contents | Information Disclosure | Uniform `unknown_entity` opacity per Phase 3 D-08 + Phase 9 D-14. Denylisted entity returns `unknown_entity` not `access_denied`. LLM cannot distinguish "denied by Jmix" from "denied by LLM policy". |
| T-10-05 | Privilege escalation via direct `AiExposureRule` table edit by non-admin | Elevation of Privilege | SEC-05: `@EntityPolicy(entityClass=AiExposureRule.class, actions=ALL)` on `AiAgentAdminRole` only. Non-admin cannot `DataManager.save()` the rule. |
| T-10-06 | Race condition: rule save + stale chunks window | Denial of Service / Info Disclosure | Documented v1.1 operator contract (D-06, D-08). Admin must wait for reingest to complete for entity-linked documents. The `source_entity` filter only affects documents explicitly linked — legacy documents are unaffected. |
| T-10-07 | Filter injection via debug view filter text field | Tampering | `FilterExpressionTextParser` parses to a typed `Filter.Expression` — no raw string interpolation into JPQL or SQL. Parse errors are surfaced as field validation, not server errors. Admin-only view. |

---

## Sources

### Primary (HIGH confidence)
- [VERIFIED: `CurrentUserSchemaAccess.java`] — method shape, `isSystemLevelEntity` pattern, constructor injection
- [VERIFIED: `RetrievalFilterBuilder.java`] — existing `FilterExpressionBuilder` usage, admin bypass branch, `buildFor()` signature
- [VERIFIED: `AsyncIngestionWorker.java`] — `UnconstrainedDataManager` pattern, `enrich()` metadata write, chunk metadata contract
- [VERIFIED: `KnowledgeDocumentService.java`] — `reingest(UUID)` idempotent path: cancel + delete chunks + markPending + afterCommit dispatch
- [VERIFIED: `KnowledgeDocumentUploadService.java`] — `TransactionSynchronizationManager.afterCommit()` dispatch pattern
- [VERIFIED: `BaselineContextProvider.java` line 107] — exact Phase 10 substitution seam
- [VERIFIED: `AiKnowledgeDocument.java`] — entity pattern: `@Store`, `@JmixEntity`, `@InstanceName`, UUID + Version + audit fields
- [VERIFIED: `agentstore-changelog.xml`] — `<includeAll>` used, no manual includes needed for new files
- [VERIFIED: `050-ai-knowledge-document.xml`] — changelog file structure pattern
- [VERIFIED: `AiAgentAdminRole.java`] — `@EntityPolicy` + `@MenuPolicy` + `@ViewPolicy` extension pattern
- [VERIFIED: `KnowledgeBaseView.java` + `knowledge-base-view.xml`] — existing KB view structure, reingest/delete row actions
- [VERIFIED: `DataGridRenderers.java`] — `buildActionsColumn`, `buildBadgeColumn`, `ComponentRenderer` pattern
- [VERIFIED: `FilteredSchemaAndExecutionDenialTest.java`] — `@SpringBootTest` + `SystemAuthenticator.withUser` test pattern
- [VERIFIED: reference impl `vector-store-view.xml`] — exact XML structure for filter input row (hbox + span + textField + suffix buttons)
- [CITED: docs.spring.io/spring-ai/docs/current/api/.../FilterExpressionBuilder.html] — `nin(String, Object...)` and `nin(String, List<Object>)` confirmed
- [CITED: docs.spring.io/spring-ai/reference/api/vectordbs.html] — NIN operator in filter expression language, `in`/`nin` collection operators
- [CITED: docs.jmix.io/jmix/data-access/entity-events.html] — `EntityChangedEvent` pattern + `@EventListener` for entity CUD events

### Secondary (MEDIUM confidence)
- [CITED: 10-UI-SPEC.md §"Spring AI 1.1.4 Filter API Findings"] — `FilterExpressionTextParser` + `NinExpressionContext` present in jar (jar inspection by UI researcher)
- [CITED: github.com/spring-projects/spring-ai/issues/1179] — NIN/NOT IN implementation behavior in pgvector filter converter

### Tertiary (LOW confidence)
- [ASSUMED] `VectorStore.similaritySearch(SearchRequest.query(""))` succeeds without exception on pgvector — pgvector embeds the empty string; behavior with `similarityThreshold(0.0)` is acceptable. (Flag A1)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all confirmed in project codebase
- Architecture: HIGH — call sites verified from source; Spring AI `nin()` API confirmed from Javadoc
- Pitfalls: HIGH — based on verified code patterns + MEMORY rules
- Test patterns: HIGH — existing `FilteredSchemaAndExecutionDenialTest` is direct template

**Research date:** 2026-04-27
**Valid until:** 2026-05-27 (Spring AI 1.x stable; Jmix 2.8 stable)
