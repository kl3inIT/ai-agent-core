package com.vn.agent.tools;

import com.vn.agent.filter.FilterDslMapper;
import com.vn.agent.filter.FilterNode;
import com.vn.agent.filter.LiteralCoercer;
import com.vn.agent.metadata.AiEntityInfo;
import com.vn.agent.metadata.AiSchema;
import com.vn.agent.metadata.EffectiveSchemaComputer;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.LoadContext;
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

/**
 * The six read-only @Tool methods the LLM can call (TOOL-03). A single {@link Component} so
 * {@code MethodToolCallbackProvider.builder().toolObjects(builtIns).build()} (Plan 03 task 3)
 * discovers all six via one reflection pass (D-09).
 *
 * <p><b>Read-only contract</b> (TOOL-04): every method body delegates to
 * {@link DataManager#load} / {@link DataManager#getCount} only. No {@code save},
 * {@code saveContext}, {@code remove}, no direct JPA persistence-context access, no JPQL
 * strings built from LLM input. The JPQL template in {@link #countRecords} is parameterized over {@code mc.getName()}
 * which is itself whitelisted via {@link Metadata#getClass(Object)}. Plan 04's ASM test enforces
 * this at build time.
 *
 * <p><b>Security</b>: Jmix entity-, attribute-, and row-level security apply automatically via
 * {@link DataManager}; {@link EffectiveSchemaComputer} gates entity/attribute visibility on top.
 * Fail-closed errors leave this class as {@link ToolUserError} → {@code ToolResultFormatter.error}.
 */
@Component
public class BuiltInDataTools {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final FetchPlans fetchPlans;
    private final EffectiveSchemaComputer schemaComputer;
    private final FilterDslMapper filterMapper;
    private final LiteralCoercer literalCoercer;
    private final ToolResultFormatter formatter;

    public BuiltInDataTools(DataManager dataManager,
                            Metadata metadata,
                            MetadataTools metadataTools,
                            FetchPlans fetchPlans,
                            EffectiveSchemaComputer schemaComputer,
                            FilterDslMapper filterMapper,
                            LiteralCoercer literalCoercer,
                            ToolResultFormatter formatter) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.fetchPlans = fetchPlans;
        this.schemaComputer = schemaComputer;
        this.filterMapper = filterMapper;
        this.literalCoercer = literalCoercer;
        this.formatter = formatter;
    }

    // -------- Tool 1: list_entities (D-01) --------

    @Tool(name = "list_entities",
            description = "List entities the current user can read. Returns a JSON array of {name, label}.")
    public String listEntities() {
        try {
            AiSchema eff = schemaComputer.forCurrentUser();
            List<Map<String, Object>> out = new ArrayList<>(eff.entities().size());
            for (AiEntityInfo e : eff.entities().values()) {
                out.add(Map.of(
                        "name", e.entityName(),
                        "label", e.localizedLabel() == null ? e.entityName() : e.localizedLabel()
                ));
            }
            return formatter.toJson(out);
        } catch (ToolUserError e) {
            return formatter.error(e);
        }
    }

    // -------- Tool 2: describe_entity (D-02) --------

    @Tool(name = "describe_entity",
            description = "Describe an entity's attributes, types, constraints, relationships, and enum values.")
    public String describeEntity(
            @ToolParam(description = "Jmix entity name from list_entities, e.g. 'jmixapp_Order'")
            String entityName) {
        try {
            MetaClass mc = resolveOrError(entityName);
            AiEntityInfo info = schemaComputer.forCurrentUser().entities().get(mc.getName());
            if (info == null) {
                throw new ToolUserError("access_denied", "no read access to " + entityName);
            }
            return formatter.describe(info);
        } catch (ToolUserError e) {
            return formatter.error(e);
        }
    }

    // -------- Tool 3: find_records (TOOL-05, TOOL-06, D-12, D-14) --------

    @Tool(name = "find_records",
            description = "Find records matching a structured filter DSL. Default limit 20, max 100. "
                    + "When results exceed the limit, response includes truncated=true and a hint to use count_records.")
    public String findRecords(
            @ToolParam(description = "Jmix entity name from list_entities") String entityName,
            @ToolParam(required = false,
                    description = "Filter DSL: {and:[...]} | {or:[...]} | {not:{...}} | {property,operation,value}")
            FilterNode filter,
            @ToolParam(required = false, description = "Max rows (1..100, default 20)") Integer limit) {
        try {
            MetaClass mc = resolveOrError(entityName);
            int clampedLimit = ToolLimits.clampLimit(limit);
            Condition cond = filter == null ? null : filterMapper.map(filter, mc);

            List<?> rows;
            if (cond == null) {
                rows = dataManager.load(mc.getJavaClass())
                        .all()
                        .fetchPlan(FetchPlan.INSTANCE_NAME)
                        .maxResults(clampedLimit + 1) // +1 to detect truncation
                        .list();
            } else {
                rows = dataManager.load(mc.getJavaClass())
                        .condition(cond)
                        .fetchPlan(FetchPlan.INSTANCE_NAME)
                        .maxResults(clampedLimit + 1)
                        .list();
            }

            boolean truncated = rows.size() > clampedLimit;
            if (truncated) {
                rows = rows.subList(0, clampedLimit);
            }
            return formatter.records(rows, mc, clampedLimit, truncated);
        } catch (ToolUserError e) {
            return formatter.error(e);
        }
    }

    // -------- Tool 4: count_records (D-14 hint target) --------

    @Tool(name = "count_records",
            description = "Count records matching a filter. Use when find_records returned truncated=true.")
    public String countRecords(
            @ToolParam(description = "Jmix entity name") String entityName,
            @ToolParam(required = false, description = "Same filter DSL as find_records") FilterNode filter) {
        try {
            MetaClass mc = resolveOrError(entityName);
            Condition cond = filter == null ? null : filterMapper.map(filter, mc);
            long n = dataManager.getCount(buildCountContext(mc, cond));
            return formatter.count(mc, n);
        } catch (ToolUserError e) {
            return formatter.error(e);
        }
    }

    // -------- Tool 5: get_record (D-12) --------

    @Tool(name = "get_record",
            description = "Load a single record by id. Returns the entity's _instance_name attributes.")
    public String getRecord(
            @ToolParam(description = "Jmix entity name") String entityName,
            @ToolParam(description = "Record id (UUID string for entities using UUID ids)") String id) {
        try {
            MetaClass mc = resolveOrError(entityName);
            Object parsedId = parseId(id, mc);
            Object entity = dataManager.load(mc.getJavaClass())
                    .id(parsedId)
                    .fetchPlan(FetchPlan.INSTANCE_NAME)
                    .optional()
                    .orElse(null);
            if (entity == null) {
                return formatter.error("not_found", "no record with id " + id);
            }
            return formatter.record(entity, mc);
        } catch (ToolUserError e) {
            return formatter.error(e);
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
            MetaClass mc = resolveOrError(entityName);
            MetaProperty mp = mc.findProperty(relationship);
            if (mp == null) {
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + relationship + " on " + mc.getName());
            }
            if (!mp.getRange().isClass()) {
                throw new ToolUserError("not_a_relationship",
                        relationship + " is not an association");
            }
            if (!schemaComputer.canReadAttribute(mc, relationship)) {
                throw new ToolUserError("access_denied",
                        "cannot read " + mc.getName() + "." + relationship);
            }
            MetaClass targetMc = mp.getRange().asClass();
            if (!schemaComputer.canReadEntity(targetMc)) {
                throw new ToolUserError("access_denied",
                        "cannot read target entity " + targetMc.getName());
            }

            FetchPlan fp = fetchPlans.builder(mc.getJavaClass())
                    .addFetchPlan(FetchPlan.INSTANCE_NAME)
                    .add(relationship, fpb -> fpb.addFetchPlan(FetchPlan.INSTANCE_NAME))
                    .build();
            Object root = dataManager.load(mc.getJavaClass())
                    .id(parseId(id, mc))
                    .fetchPlan(fp)
                    .optional()
                    .orElse(null);
            if (root == null) {
                return formatter.error("not_found", "no record with id " + id);
            }

            Object relatedValue = EntityValues.getValue(root, relationship);
            List<?> relatedRows;
            if (relatedValue instanceof Collection<?> col) {
                relatedRows = new ArrayList<>(col);
            } else if (relatedValue == null) {
                relatedRows = List.of();
            } else {
                relatedRows = List.of(relatedValue);
            }
            return formatter.related(root, mp, relatedRows);
        } catch (ToolUserError e) {
            return formatter.error(e);
        }
    }

    // -------- helpers --------

    /**
     * Resolve an LLM-supplied entity name to a {@link MetaClass}, verifying read access against
     * the current user's Jmix security (D-11). Fail-closed: unknown names and denied entities
     * both produce {@link ToolUserError}.
     */
    private MetaClass resolveOrError(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw new ToolUserError("unknown_entity", "entity name must not be blank");
        }
        MetaClass mc;
        try {
            mc = metadata.getClass(entityName);
        } catch (RuntimeException e) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName);
        }
        if (mc == null) {
            throw new ToolUserError("unknown_entity", "no entity named " + entityName);
        }
        if (!schemaComputer.canReadEntity(mc)) {
            throw new ToolUserError("access_denied", "no read access to " + entityName);
        }
        return mc;
    }

    /**
     * Build a {@link LoadContext} suitable for {@link DataManager#getCount(LoadContext)}. Jmix
     * 2.8 requires a JPQL query on the LoadContext — the fluent loader has no {@code getCount}
     * — so we build a minimal template whose only interpolated token is
     * {@link MetaClass#getName()}, which came from {@link Metadata#getClass(String)} via
     * {@link #resolveOrError} and therefore is a Jmix-whitelisted entity name by construction.
     * Zero LLM input flows into the JPQL string; Plan 04's ASM test only needs to whitelist
     * this one call site.
     */
    private LoadContext<?> buildCountContext(MetaClass mc, Condition cond) {
        LoadContext.Query q = new LoadContext.Query("select e from " + mc.getName() + " e");
        if (cond != null) {
            q.setCondition(cond);
        }
        return new LoadContext<>(mc).setQuery(q);
    }

    /**
     * Coerce an LLM-supplied id string to the Java type of the entity's primary-key property.
     * For Jmix apps using UUID ids this parses the string via {@link java.util.UUID#fromString}.
     * Delegates to {@link LiteralCoercer} so non-UUID ids (long, String) are also handled.
     */
    private Object parseId(String id, MetaClass mc) {
        if (id == null || id.isBlank()) {
            throw new ToolUserError("invalid_id", "id must not be blank");
        }
        MetaProperty pk = metadataTools.getPrimaryKeyProperty(mc);
        if (pk == null) {
            throw new ToolUserError("invalid_id", "entity " + mc.getName() + " has no primary key");
        }
        return literalCoercer.coerce(id, pk);
    }
}
