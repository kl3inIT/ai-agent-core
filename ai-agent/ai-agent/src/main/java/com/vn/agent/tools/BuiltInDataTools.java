package com.vn.agent.tools;

import com.vn.agent.filter.FilterNode;
import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.filter.StructuredFilterConditionMapper;
import com.vn.agent.exposure.LlmExposurePolicy;
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
 * <p><b>Phase 10 Fix R4 — uniform opacity:</b> {@code resolveReadableEntityOrThrow} returns
 * {@code unknown_entity} for BOTH unknown-name and Jmix-/denylist-denied cases. The LLM cannot
 * distinguish "no such entity" from "entity exists but denied" — full opacity per EXP-09 +
 * Phase 3 D-08.
 */
@Component
public class BuiltInDataTools {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final FetchPlans fetchPlans;
    private final LlmExposurePolicy llmExposurePolicy;
    private final StructuredFilterConditionMapper structuredFilterConditionMapper;
    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final ToolResultFormatter toolResultFormatter;
    private final FetchPlanResolver fetchPlanResolver;
    private final FetchPlanIntersector fetchPlanIntersector;

    public BuiltInDataTools(DataManager dataManager,
                            Metadata metadata,
                            MetadataTools metadataTools,
                            MessageTools messageTools,
                            FetchPlans fetchPlans,
                            LlmExposurePolicy llmExposurePolicy,
                            StructuredFilterConditionMapper structuredFilterConditionMapper,
                            FilterLiteralValueConverter filterLiteralValueConverter,
                            ToolResultFormatter toolResultFormatter,
                            FetchPlanResolver fetchPlanResolver,
                            FetchPlanIntersector fetchPlanIntersector) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
        this.fetchPlans = fetchPlans;
        this.llmExposurePolicy = llmExposurePolicy;
        this.structuredFilterConditionMapper = structuredFilterConditionMapper;
        this.filterLiteralValueConverter = filterLiteralValueConverter;
        this.toolResultFormatter = toolResultFormatter;
        this.fetchPlanResolver = fetchPlanResolver;
        this.fetchPlanIntersector = fetchPlanIntersector;
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
     * (each as {@code [{name, label}]} with locale-resolved labels via {@link io.jmix.core.Messages}),
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
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            Set<String> readableAttributeNames = llmExposurePolicy.getReadableSchema().get(metaClass);
            if (readableAttributeNames == null) {
                // Phase 10 EXP-09 uniform opacity: denial paths surface as unknown_entity (Fix R4).
                // The defensive null guard hits if canReadEntity passed but the schema map omits
                // this key (e.g. denylist races); treat as opaque-not-found.
                throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
            }
            return toolResultFormatter.describe(metaClass, readableAttributeNames);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 3: find_records (TOOL-05, TOOL-06, D-12, D-14) --------

    @Tool(name = "find_records",
            description = "Find records matching a structured filter object. Default limit 20, max 100. "
                    + "When results exceed the limit, response includes truncated=true and a hint to use count_records.")
    public String findRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(required = false,
                    description = "Structured filter: {and:[...]} | {or:[...]} | {not:{...}} | {property,operation,value}")
            FilterNode filter,
            @ToolParam(required = false, description = "Max rows (1..100, default 20)") Integer limit) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
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
            description = "Count records matching a filter. Use when find_records returned truncated=true.")
    public String countRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(required = false, description = "Same structured filter shape as find_records") FilterNode filter) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            Condition condition = filter == null ? null : structuredFilterConditionMapper.map(filter, metaClass);
            long recordCount = dataManager.getCount(buildCountContext(metaClass, condition));
            return toolResultFormatter.count(metaClass, recordCount);
        } catch (ToolUserError toolUserError) {
            return toolResultFormatter.error(toolUserError);
        }
    }

    // -------- Tool 5: get_record (D-12) --------

    @Tool(name = "get_record",
            description = "Load a single record by id. Returns the entity's _instance_name attributes.")
    public String getRecord(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(description = "Record id (UUID string for entities using UUID ids)") String id) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            Object parsedId = parseEntityId(id, metaClass);
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
            description = "Load related records via a relationship attribute. Returns related rows' _instance_name attributes.")
    public String getRelatedRecords(
            @ToolParam(description = "Exact entity name from agent.entities or list_entities; do not infer or add prefixes")
            String entityName,
            @ToolParam(description = "Root entity id") String id,
            @ToolParam(description = "Relationship attribute name (from describe_entity)") String relationship) {
        try {
            MetaClass rootMetaClass = resolveReadableEntityOrThrow(entityName);
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
                // Phase 10 EXP-09 uniform opacity (Fix R4): denied target entity surfaces as
                // unknown_entity for the target name — same opacity as resolveReadableEntityOrThrow.
                throw new ToolUserError("unknown_entity",
                        "no entity named " + targetMetaClass.getName(), UnknownEntityHints.AS_LIST);
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
                    .id(parseEntityId(id, rootMetaClass))
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

    /**
     * Resolve an LLM-supplied entity name to a {@link MetaClass}, verifying read access against
     * the current user's Jmix security (D-11). Fail-closed: unknown names and denied entities
     * both produce {@link ToolUserError}.
     */
    private MetaClass resolveReadableEntityOrThrow(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw new ToolUserError("unknown_entity", "entity name must not be blank", UnknownEntityHints.AS_LIST);
        }

        MetaClass metaClass;
        try {
            metaClass = metadata.getClass(entityName);
        } catch (RuntimeException runtimeException) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
        }

        if (metaClass == null) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
        }
        if (!llmExposurePolicy.canReadEntity(metaClass)) {
            // Phase 10 Fix R4 — full uniformity (EXP-09, Phase 3 D-08): denied entities surface
            // identically to non-existent ones. The LLM cannot distinguish "no such entity"
            // from "entity exists but denied" (whether by Jmix role OR admin denylist).
            // UNKNOWN_ENTITY_HINTS strings are byte-for-byte unchanged (em dash U+2014 on hint #3
            // preserved per Phase 9 D-14 / TEST-08).
            throw new ToolUserError("unknown_entity", "no entity named " + entityName, UnknownEntityHints.AS_LIST);
        }
        return metaClass;
    }

    /**
     * Build a {@link LoadContext} suitable for {@link DataManager#getCount(LoadContext)}. Jmix
     * 2.8 requires a JPQL query on the LoadContext — the fluent loader has no {@code getCount}
     * — so we build a minimal template whose only interpolated token is
     * {@link MetaClass#getName()}, which came from {@link Metadata#getClass(String)} via
     * {@link #resolveReadableEntityOrThrow(String)} and therefore is a Jmix-whitelisted entity
     * name by construction. Zero LLM input flows into the JPQL string; Plan 04's ASM test only
     * needs to whitelist this one call site.
     */
    private LoadContext<?> buildCountContext(MetaClass metaClass, Condition condition) {
        LoadContext.Query query = new LoadContext.Query("select e from " + metaClass.getName() + " e");
        if (condition != null) {
            query.setCondition(condition);
        }
        return new LoadContext<>(metaClass).setQuery(query);
    }

    /**
     * Convert an LLM-supplied id string to the Java type of the entity's primary-key property.
     * For Jmix apps using UUID ids this parses the string via {@link java.util.UUID#fromString}.
     * Delegates to {@link FilterLiteralValueConverter} so non-UUID ids (long, String) are also
     * handled.
     */
    private Object parseEntityId(String id, MetaClass metaClass) {
        if (id == null || id.isBlank()) {
            throw new ToolUserError("invalid_id", "id must not be blank");
        }
        MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(metaClass);
        if (primaryKeyProperty == null) {
            throw new ToolUserError("invalid_id", "entity " + metaClass.getName() + " has no primary key");
        }
        return filterLiteralValueConverter.convertValue(id, primaryKeyProperty);
    }
}
