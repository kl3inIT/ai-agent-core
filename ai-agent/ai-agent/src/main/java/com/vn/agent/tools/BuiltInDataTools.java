package com.vn.agent.tools;

import com.vn.agent.filter.FilterNode;
import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.filter.StructuredFilterConditionMapper;
import com.vn.agent.metadata.CurrentUserSchemaAccess;
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
 * {@link DataManager}; {@link CurrentUserSchemaAccess} gates entity/attribute visibility on top.
 * Fail-closed errors leave this class as {@link ToolUserError} →
 * {@code ToolResultFormatter.error}.
 */
@Component
public class BuiltInDataTools {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final FetchPlans fetchPlans;
    private final CurrentUserSchemaAccess currentUserSchemaAccess;
    private final StructuredFilterConditionMapper structuredFilterConditionMapper;
    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final ToolResultFormatter toolResultFormatter;

    public BuiltInDataTools(DataManager dataManager,
                            Metadata metadata,
                            MetadataTools metadataTools,
                            MessageTools messageTools,
                            FetchPlans fetchPlans,
                            CurrentUserSchemaAccess currentUserSchemaAccess,
                            StructuredFilterConditionMapper structuredFilterConditionMapper,
                            FilterLiteralValueConverter filterLiteralValueConverter,
                            ToolResultFormatter toolResultFormatter) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
        this.fetchPlans = fetchPlans;
        this.currentUserSchemaAccess = currentUserSchemaAccess;
        this.structuredFilterConditionMapper = structuredFilterConditionMapper;
        this.filterLiteralValueConverter = filterLiteralValueConverter;
        this.toolResultFormatter = toolResultFormatter;
    }

    // -------- Tool 1: list_entities (D-01) --------

    @Tool(name = "list_entities",
            description = "List entities the current user can read. Returns a JSON array of {name, label}.")
    public String listEntities() {
        try {
            Map<MetaClass, Set<String>> readableSchemaByEntity = currentUserSchemaAccess.getReadableSchema();
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

    // -------- Tool 2: describe_entity (D-02) --------

    @Tool(name = "describe_entity",
            description = "Describe an entity's attributes, types, constraints, relationships, and enum values.")
    public String describeEntity(
            @ToolParam(description = "Jmix entity name from list_entities, e.g. 'jmixapp_Order'")
            String entityName) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            Set<String> readableAttributeNames = currentUserSchemaAccess.getReadableSchema().get(metaClass);
            if (readableAttributeNames == null) {
                throw new ToolUserError("access_denied", "no read access to " + entityName);
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
            @ToolParam(description = "Jmix entity name from list_entities") String entityName,
            @ToolParam(required = false,
                    description = "Structured filter: {and:[...]} | {or:[...]} | {not:{...}} | {property,operation,value}")
            FilterNode filter,
            @ToolParam(required = false, description = "Max rows (1..100, default 20)") Integer limit) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            int clampedLimit = ToolLimits.clampLimit(limit);
            Condition condition = filter == null ? null : structuredFilterConditionMapper.map(filter, metaClass);

            List<?> rows;
            if (condition == null) {
                rows = dataManager.load(metaClass.getJavaClass())
                        .all()
                        .fetchPlan(FetchPlan.BASE)
                        .maxResults(clampedLimit + 1)
                        .list();
            } else {
                rows = dataManager.load(metaClass.getJavaClass())
                        .condition(condition)
                        .fetchPlan(FetchPlan.BASE)
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
            @ToolParam(description = "Jmix entity name") String entityName,
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
            @ToolParam(description = "Jmix entity name") String entityName,
            @ToolParam(description = "Record id (UUID string for entities using UUID ids)") String id) {
        try {
            MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
            Object parsedId = parseEntityId(id, metaClass);
            Object entity = dataManager.load(metaClass.getJavaClass())
                    .id(parsedId)
                    .fetchPlan(FetchPlan.BASE)
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
            @ToolParam(description = "Jmix entity name") String entityName,
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
            if (!currentUserSchemaAccess.canReadAttribute(rootMetaClass, relationship)) {
                throw new ToolUserError("access_denied",
                        "cannot read " + rootMetaClass.getName() + "." + relationship);
            }

            MetaClass targetMetaClass = relationshipProperty.getRange().asClass();
            if (!currentUserSchemaAccess.canReadEntity(targetMetaClass)) {
                throw new ToolUserError("access_denied",
                        "cannot read target entity " + targetMetaClass.getName());
            }

            FetchPlan fetchPlan = fetchPlans.builder(rootMetaClass.getJavaClass())
                    .addFetchPlan(FetchPlan.BASE)
                    .add(relationship, fetchPlanBuilder -> fetchPlanBuilder.addFetchPlan(FetchPlan.INSTANCE_NAME))
                    .build();
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
            throw new ToolUserError("unknown_entity", "entity name must not be blank");
        }

        MetaClass metaClass;
        try {
            metaClass = metadata.getClass(entityName);
        } catch (RuntimeException runtimeException) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName);
        }

        if (metaClass == null) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName);
        }
        if (!currentUserSchemaAccess.canReadEntity(metaClass)) {
            throw new ToolUserError("access_denied", "no read access to " + entityName);
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
