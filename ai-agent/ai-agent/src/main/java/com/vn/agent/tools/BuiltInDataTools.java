package com.vn.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.filter.FilterNode;
import com.vn.agent.filter.StructuredFilterConditionMapper;
import com.vn.agent.tools.fetchplan.FetchPlanIntersector;
import com.vn.agent.tools.fetchplan.FetchPlanResolver;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.LoadContext;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.querycondition.Condition;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The six read-only @Tool methods the LLM can call (TOOL-03). A single {@link Component} so
 * {@code MethodToolCallbackProvider.builder().toolObjects(builtIns).build()} (Plan 03 task 3)
 * discovers all six via one reflection pass (D-09).
 *
 * <p><b>Read-only contract</b> (TOOL-04): every method body delegates to
 * {@link DataManager#load} / {@link DataManager#getCount} only. No {@code save},
 * {@code saveContext}, {@code remove}, no direct JPA persistence-context access, no JPQL
 * strings built from LLM input. The JPQL template in {@link #countRecords} is parameterized
 * over {@code metaClass.getName()}, which is itself whitelisted via
 * {@link Metadata#getClass(Object)}. Plan 04's ASM test enforces this at build time.
 *
 * <p><b>Security</b>: Jmix entity-, attribute-, and row-level security apply automatically via
 * {@link DataManager}; {@link LlmExposurePolicy} gates entity/attribute visibility on top
 * (Phase 10 — wraps the underlying Jmix-permission source of truth and additionally narrows
 * by the admin denylist). Fail-closed errors leave this class as {@link ToolUserError} →
 * {@code ToolResultFormatter.error}.
 *
 * <p><b>Phase 10 Fix R4 — uniform opacity:</b>
 * {@link ToolEntityResolver#resolveReadableEntityOrThrow} returns {@code unknown_entity} for
 * BOTH unknown-name and Jmix-/denylist-denied cases. The LLM cannot distinguish "no such
 * entity" from "entity exists but denied" — full opacity per EXP-09 + Phase 3 D-08.
 */
@Component
public class BuiltInDataTools {

    private final DataManager dataManager;
    private final MessageTools messageTools;
    private final FetchPlans fetchPlans;
    private final LlmExposurePolicy llmExposurePolicy;
    private final StructuredFilterConditionMapper structuredFilterConditionMapper;
    private final ToolResultFormatter toolResultFormatter;
    private final FetchPlanResolver fetchPlanResolver;
    private final FetchPlanIntersector fetchPlanIntersector;
    private final ToolEntityResolver toolEntityResolver;
    private final ObjectMapper objectMapper;

    public BuiltInDataTools(DataManager dataManager,
                            MessageTools messageTools,
                            FetchPlans fetchPlans,
                            LlmExposurePolicy llmExposurePolicy,
                            StructuredFilterConditionMapper structuredFilterConditionMapper,
                            ToolResultFormatter toolResultFormatter,
                            FetchPlanResolver fetchPlanResolver,
                            FetchPlanIntersector fetchPlanIntersector,
                            ToolEntityResolver toolEntityResolver,
                            ObjectMapper objectMapper) {
        this.dataManager = dataManager;
        this.messageTools = messageTools;
        this.fetchPlans = fetchPlans;
        this.llmExposurePolicy = llmExposurePolicy;
        this.structuredFilterConditionMapper = structuredFilterConditionMapper;
        this.toolResultFormatter = toolResultFormatter;
        this.fetchPlanResolver = fetchPlanResolver;
        this.fetchPlanIntersector = fetchPlanIntersector;
        this.toolEntityResolver = toolEntityResolver;
        this.objectMapper = objectMapper;
    }

    // -------- Tool 1: list_entities (D-01) --------

    @Tool(name = "list_entities",
            description = "List entities the current user can read. Returns a JSON array of {name, label}.")
    public String listEntities() {
        try {
            Map<MetaClass, Set<String>> readableSchemaByEntity = llmExposurePolicy.getReadableSchema();
            List<ReadableEntitySummary> entities = new ArrayList<>(readableSchemaByEntity.size());
            for (MetaClass metaClass : readableSchemaByEntity.keySet()) {
                entities.add(new ReadableEntitySummary(
                        metaClass.getName(),
                        messageTools.getEntityCaption(metaClass)
                ));
            }
            return toolResultFormatter.toJson(entities);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 2: describe_entity (D-02; widened in Phase 9 TOOL-09 / D-04) --------

    /**
     * Tool 2: describe_entity (D-02; widened in Phase 9 TOOL-09 / D-04).
     *
     * <p>Returns Jmix-{@link MetadataTools}-derived attribute fields: {@code comment},
     * {@code attributeType}, {@code cardinality}, {@code mandatory}, {@code readOnly},
     * {@code persistent}, {@code transientProperty}, {@code primaryKey}, {@code enumValues}
     * (each as {@code [{name, id, label}]} with locale-resolved labels via {@link io.jmix.core.Messages}),
     * {@code relationshipTarget} ({@code {name, label}} mirroring the {@code agent.entities}
     * shape), and {@code maxLength}. Top-level {@code comment} carries any entity-level
     * {@link io.jmix.core.metamodel.annotation.Comment}.
     *
     * <p><b>Excluded fields (D-05 — documented in Javadoc only, NOT echoed into LLM-facing
     * payload):</b> DDL column names, JPA fetch type, cascade rules, raw annotations, internal
     * store name, framework-managed audit columns. The exclusion keeps the prompt token-cost
     * bounded and avoids leaking host-storage details that the LLM cannot act on.
     *
     * <p>Implementation rule: every metadata read goes through {@link MetadataTools} (e.g.
     * {@code getMetaAnnotationValue}, {@code isJpa}, {@code getPrimaryKeyProperty}). Raw reflection
     * is forbidden in this code path; the only allowed reflection in {@code tools/} is
     * {@code ToolResultFormatter.columnLength} for {@code @Column.length}, which has no
     * {@code MetadataTools} accessor.
     *
     * <p><b>Future-cache contract (mirrors AiAgentPromptProperties PROMPT-02):</b>
     * the {@code DescribeEntityResult} payload carries locale-sensitive labels (entity caption,
     * attribute captions, enum captions) resolved through {@link MessageTools} /
     * {@link io.jmix.core.Messages}. If a future phase (10/11+) introduces a cache layer in
     * front of {@code describe_entity}, that cache MUST NOT include {@link java.util.Locale}
     * in the cache key. Locale-sensitive labels must be resolved AFTER the cache lookup
     * (post-cache label rendering against the current
     * {@code CurrentAuthentication.getLocale()}), exactly as
     * {@code AiAgentPromptProperties.effectiveSystemPrompt(...)} resolves prompt text post-cache.
     * Including locale in the cache key would create the PROMPT-02 forbidden coupling and force
     * per-locale cache duplication; it is a known anti-pattern. The structural metadata
     * (attribute names, types, cardinality) is locale-invariant and is the legitimate cache
     * target.
     */
    @Tool(name = "describe_entity",
            description = "Describe an entity's attributes, types, constraints, relationships, and enum values.")
    public String describeEntity(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            Set<String> schemaAttributeNames = llmExposurePolicy.getReadableSchema().get(metaClass);
            if (schemaAttributeNames == null) {
                // Phase 10 EXP-09 uniform opacity: denial paths surface as unknown_entity (Fix R4).
                // The defensive null guard hits if canReadEntity passed but the schema map omits
                // this key (e.g. denylist races); treat as opaque-not-found.
                throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
            }
            Set<String> readableAttributeNames = toolEntityResolver.llmReadableAttributes(metaClass, schemaAttributeNames);
            return toolResultFormatter.describe(metaClass, readableAttributeNames);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 3: find_records (TOOL-05, TOOL-06, D-12, D-14) --------

    @Tool(name = "find_records",
            description = """
                    Find records matching a structured filter object. Default limit 20, max 100.
                    Filter shapes: {"and":[...]}, {"or":[...]}, {"not":{...}}, or
                    {"property":"attributeOr.path","operation":"EQUAL","value":...}.
                    Supported operations: EQUAL, NOT_EQUAL, GREATER, GREATER_OR_EQUAL, LESS,
                    LESS_OR_EQUAL, CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH,
                    IN_LIST, NOT_IN_LIST, IS_SET. IN_LIST/NOT_IN_LIST require a non-empty JSON array.
                    IS_SET requires a boolean value: true means the attribute is not null, false means null.
                    NOT over STARTS_WITH or ENDS_WITH is not supported; use a positive filter instead.
                    Use exact attribute names from describe_entity; dotted paths are allowed only through
                    readable relationships and are depth-limited. Enum values should use enumValues[].id
                    from describe_entity; enumValues[].name is also accepted. Never use localized labels as values.
                    When results exceed the limit, response includes truncated=true and a hint to use count_records.
                    """)
    public String findRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(required = false,
                    description = "Structured filter object. A JSON string containing the same object is also accepted.")
            Object filter,
            @ToolParam(required = false, description = "Max rows (1..100, default 20)") Integer limit) {
        try {
            return findRecordsInternal(entityName, normalizeFilter(filter), limit);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    public String findRecords(String entityName, FilterNode filter, Integer limit) {
        return findRecordsInternal(entityName, filter, limit);
    }

    private String findRecordsInternal(String entityName, FilterNode filter, Integer limit) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            int clampedLimit = ToolLimits.clampLimit(limit);
            Condition condition = filter == null ? null : structuredFilterConditionMapper.map(filter, metaClass);

            FetchPlan plan = fetchPlanResolver.resolve("find_records", metaClass);
            List<?> rows;
            if (condition == null) {
                rows = dataManager.load(metaClass.getJavaClass())
                        .all()
                        .fetchPlan(plan)
                        .maxResults(clampedLimit + 1)
                        .list();
            } else {
                rows = dataManager.load(metaClass.getJavaClass())
                        .condition(condition)
                        .fetchPlan(plan)
                        .maxResults(clampedLimit + 1)
                        .list();
            }

            boolean truncated = rows.size() > clampedLimit;
            if (truncated) {
                rows = rows.subList(0, clampedLimit);
            }
            return toolResultFormatter.records(rows, metaClass, clampedLimit, truncated);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 4: count_records (D-14 hint target) --------

    @Tool(name = "count_records",
            description = """
                    Count records matching a structured filter. Use the same filter shape and operation
                    names as find_records. Use when find_records returned truncated=true or when the
                    user asks for a total count instead of row details.
                    """)
    public String countRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(required = false,
                    description = "Same structured filter shape as find_records. JSON string form is also accepted.")
            Object filter) {
        try {
            return countRecordsInternal(entityName, normalizeFilter(filter));
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    public String countRecords(String entityName, FilterNode filter) {
        return countRecordsInternal(entityName, filter);
    }

    private String countRecordsInternal(String entityName, FilterNode filter) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            Condition condition = filter == null ? null : structuredFilterConditionMapper.map(filter, metaClass);
            long recordCount = dataManager.getCount(buildCountContext(metaClass, condition));
            return toolResultFormatter.count(metaClass, recordCount);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 5: get_record (D-12) --------

    @Tool(name = "get_record",
            description = """
                    Load a single record by id. Use an id returned by find_records, create_record, or
                    update_record. Returns authorized fields according to the configured fetch plan;
                    unfetched fields are null. Does not load collection contents; use get_related_records
                    for relationship drill-down.
                    """)
    public String getRecord(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(description = "Record id (UUID string for entities using UUID ids)") String id) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            Object parsedId = toolEntityResolver.parseEntityId(id, metaClass);
            FetchPlan plan = fetchPlanResolver.resolve("get_record", metaClass);
            Object entity = dataManager.load(metaClass.getJavaClass())
                    .id(parsedId)
                    .fetchPlan(plan)
                    .optional()
                    .orElse(null);
            if (entity == null) {
                return toolResultFormatter.error("not_found", "no record with id " + id);
            }
            return toolResultFormatter.record(entity, metaClass);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 6: get_related_records (D-12) --------

    @Tool(name = "get_related_records",
            description = """
                    Load related records via a relationship attribute from describe_entity. Use only when
                    the parent row id is known and the relationship attribute is readable. This is a read-only
                    drill-down; it never creates, links, unlinks, or deletes records.
                    """)
    public String getRelatedRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(description = "Root entity id") String id,
            @ToolParam(description = "Relationship attribute name (from describe_entity)") String relationship) {
        try {
            MetaClass rootMetaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            MetaProperty relationshipProperty = rootMetaClass.findProperty(relationship);
            if (relationshipProperty == null) {
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + relationship + " on " + rootMetaClass.getName());
            }
            if (!relationshipProperty.getRange().isClass()) {
                throw new ToolUserError("not_a_relationship",
                        relationship + " is not an association");
            }
            if (!llmExposurePolicy.canReadAttribute(rootMetaClass, relationship)) {
                // Phase 10 EXP-09 uniform opacity (Fix R4): a denied relationship attribute
                // surfaces identically to a non-existent attribute. The LLM cannot distinguish
                // "exists but you can't read it" from "no such attribute".
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + relationship + " on " + rootMetaClass.getName());
            }

            MetaClass targetMetaClass = relationshipProperty.getRange().asClass();
            if (!llmExposurePolicy.canReadEntity(targetMetaClass)) {
                // Phase 10 EXP-09 uniform opacity: if the relationship target entity is hidden,
                // the relationship itself is hidden. Do not disclose the target entity name.
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + relationship + " on " + rootMetaClass.getName());
            }

            // D-13: SPI overrides the data fetch plan only; INSTANCE_NAME on the relationship
            // is a separate label-projection concern outside the SPI surface in v1.1. The
            // composed plan is intersected again because Jmix instance-name attributes are
            // normal attributes and can still be denied by the current user's policies.
            FetchPlan dataPlan = fetchPlanResolver.resolve("get_related_records", rootMetaClass);
            FetchPlan composedPlan = fetchPlans.builder(rootMetaClass.getJavaClass())
                    .addFetchPlan(dataPlan)
                    .add(relationship, fetchPlanBuilder -> fetchPlanBuilder.addFetchPlan(FetchPlan.INSTANCE_NAME))
                    .build();
            FetchPlan fetchPlan = fetchPlanIntersector.intersectWithAcl(
                    composedPlan, rootMetaClass, "get_related_records");
            Object rootEntity = dataManager.load(rootMetaClass.getJavaClass())
                    .id(toolEntityResolver.parseEntityId(id, rootMetaClass))
                    .fetchPlan(fetchPlan)
                    .optional()
                    .orElse(null);
            if (rootEntity == null) {
                return toolResultFormatter.error("not_found", "no record with id " + id);
            }

            Object relatedValue = EntityValues.getValue(rootEntity, relationship);
            List<?> relatedRows;
            if (relatedValue instanceof Collection<?> relatedCollection) {
                relatedRows = new ArrayList<>(relatedCollection);
            } else if (relatedValue == null) {
                relatedRows = List.of();
            } else {
                relatedRows = List.of(relatedValue);
            }
            return toolResultFormatter.related(relationshipProperty, relatedRows);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- helpers --------

    private FilterNode normalizeFilter(Object rawFilter) {
        if (rawFilter == null) {
            return null;
        }
        if (rawFilter instanceof FilterNode filterNode) {
            return filterNode;
        }
        if (rawFilter instanceof JsonNode jsonNode) {
            return normalizeJsonFilter(jsonNode);
        }
        if (rawFilter instanceof String text) {
            return normalizeStringFilter(text);
        }
        try {
            return objectMapper.convertValue(rawFilter, FilterNode.class);
        } catch (IllegalArgumentException e) {
            throw invalidFilter();
        }
    }

    private FilterNode normalizeStringFilter(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return normalizeJsonFilter(objectMapper.readTree(text));
        } catch (JsonProcessingException e) {
            throw invalidFilter();
        }
    }

    private FilterNode normalizeJsonFilter(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        if (jsonNode.isTextual()) {
            return normalizeStringFilter(jsonNode.asText());
        }
        try {
            return objectMapper.treeToValue(jsonNode, FilterNode.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidFilter();
        }
    }

    private ToolUserError invalidFilter() {
        return new ToolUserError("invalid_filter",
                "filter must be a structured filter object or a JSON string containing that object",
                List.of(
                        "use one of these root shapes: {property,operation,value}, {and:[...]}, {or:[...]}, {not:{...}}",
                        "call describe_entity for exact attribute names and enum ids",
                        "do not treat an invalid_filter response as no matching records"
                ));
    }

    /**
     * Build a {@link LoadContext} suitable for {@link DataManager#getCount(LoadContext)}. Jmix
     * 2.8 requires a JPQL query on the LoadContext — the fluent loader has no {@code getCount}
     * — so we build a minimal template whose only interpolated token is
     * {@link MetaClass#getName()}, which came from {@link Metadata#getClass(String)} via
     * {@link ToolEntityResolver#resolveReadableEntityOrThrow(String)} and therefore is a
     * Jmix-whitelisted entity name by construction. Zero LLM input flows into the JPQL string;
     * Plan 04's ASM test only needs to whitelist this one call site.
     */
    private LoadContext<?> buildCountContext(MetaClass metaClass, Condition condition) {
        LoadContext.Query query = new LoadContext.Query("select e from " + metaClass.getName() + " e");
        if (condition != null) {
            query.setCondition(condition);
        }
        return new LoadContext<>(metaClass).setQuery(query);
    }
}
