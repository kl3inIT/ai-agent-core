package com.vn.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.metadata.MetamodelScanner;
import io.jmix.core.EntityStates;
import io.jmix.core.MessageTools;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON serialization + prompt-injection defense for tool results (D-03, D-13, TOOL-07).
 *
 * <p>All entity-row serialization goes through this class. User-editable string attributes
 * (identified by {@link MetamodelScanner#getUserEditableStringIndex()}) are wrapped in
 * {@code <data>...</data>} sentinels. Literal {@code <data>} / {@code </data>} substrings
 * inside values are HTML-escaped to prevent delimiter-bypass (Pitfall 4 — plan 03-RESEARCH.md).
 *
 * <p>Plan 04's prompt-injection harness will verify both the wrap AND the escape.
 */
@Component
public class ToolResultFormatter {

    private final ObjectMapper objectMapper;
    private final MetamodelScanner scanner;
    private final EntityStates entityStates;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;

    public ToolResultFormatter(ObjectMapper objectMapper,
                               MetamodelScanner scanner,
                               EntityStates entityStates,
                               MetadataTools metadataTools,
                               MessageTools messageTools) {
        this.objectMapper = objectMapper;
        this.scanner = scanner;
        this.entityStates = entityStates;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
    }

    // ---- public API ----

    /** Serialize an arbitrary value as JSON (used for list_entities / count_records / etc.). */
    public String toJson(Object value) {
        return writeJson(value);
    }

    public String error(String errorCode, String reason) {
        return writeJson(new ToolErrorDto(errorCode, reason));
    }

    public String error(ToolErrorDto dto) {
        return writeJson(dto);
    }

    public String error(ToolUserError e) {
        return writeJson(e.toDto());
    }

    /**
     * describe_entity response (D-02). Computed live off {@link MetaClass} +
     * {@link MessageTools} rather than a cached snapshot — captions are locale-sensitive and
     * the metamodel is already authoritative. {@code allowedAttrs} is the access-filtered
     * subset from {@code EffectiveSchemaComputer.forCurrentUser()}.
     */
    public String describe(MetaClass mc, Set<String> allowedAttrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityName", mc.getName());
        out.put("label", messageTools.getEntityCaption(mc));
        List<Map<String, Object>> attrs = new ArrayList<>();
        for (MetaProperty mp : mc.getProperties()) {
            if (!allowedAttrs.contains(mp.getName())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", mp.getName());
            m.put("type", typeLabel(mp));
            m.put("nullable", !mp.isMandatory());
            m.put("label", messageTools.getPropertyCaption(mp));
            Range range = mp.getRange();
            if (range.isEnum()) {
                List<String> names = new ArrayList<>();
                for (Object c : range.asEnumeration().getValues()) {
                    names.add(((Enum<?>) c).name());
                }
                m.put("enumValues", names);
            }
            if (range.isClass()) {
                m.put("relationshipTarget", range.asClass().getName());
            }
            if (range.isDatatype() && range.asDatatype().getJavaClass() == String.class) {
                Integer maxLength = columnLength(mp);
                if (maxLength != null) {
                    m.put("maxLength", maxLength);
                }
            }
            attrs.add(m);
        }
        out.put("attributes", attrs);
        return writeJson(out);
    }

    private String typeLabel(MetaProperty mp) {
        Range range = mp.getRange();
        if (range.isEnum()) return "enum:" + range.asEnumeration().getJavaClass().getSimpleName();
        if (range.isClass()) return "ref:" + range.asClass().getName();
        if (range.isDatatype()) return range.asDatatype().getJavaClass().getSimpleName();
        return "unknown";
    }

    private Integer columnLength(MetaProperty mp) {
        java.lang.reflect.AnnotatedElement el = mp.getAnnotatedElement();
        if (el == null) return null;
        jakarta.persistence.Column col = el.getAnnotation(jakarta.persistence.Column.class);
        if (col == null) return null;
        int len = col.length();
        // JPA default is 255 — emit only when explicitly set (non-default). Most app entities
        // set an explicit @Column(length=…) when they care; returning 255 for every String
        // attribute would add noise.
        return len == 255 ? null : len;
    }

    /** get_record response — one entity row. */
    public String record(Object entity, MetaClass mc) {
        return writeJson(buildEntityMap(entity, mc));
    }

    /** find_records response — rows + limit + truncated flag + optional hint (D-14). */
    public String records(List<?> rows, MetaClass mc, int limit, boolean truncated) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityName", mc.getName());
        List<Map<String, Object>> serialized = new ArrayList<>(rows.size());
        for (Object r : rows) {
            serialized.add(buildEntityMap(r, mc));
        }
        out.put("rows", serialized);
        out.put("limit", limit);
        out.put("truncated", truncated);
        if (truncated) {
            out.put("hint",
                    "result was truncated to the limit; call count_records for the exact total or narrow the filter");
        }
        return writeJson(out);
    }

    /** get_related_records response. */
    public String related(Object root, MetaProperty relationProp, List<?> relatedRows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityName", relationProp.getDomain().getName());
        out.put("relationship", relationProp.getName());
        MetaClass targetMc = relationProp.getRange().asClass();
        out.put("targetEntity", targetMc.getName());
        List<Map<String, Object>> serialized = new ArrayList<>(relatedRows.size());
        for (Object r : relatedRows) {
            serialized.add(buildEntityMap(r, targetMc));
        }
        out.put("rows", serialized);
        return writeJson(out);
    }

    /** count_records response. */
    public String count(MetaClass mc, long count) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityName", mc.getName());
        out.put("count", count);
        return writeJson(out);
    }

    // ---- internals ----

    /**
     * Build a JSON-ready Map of the entity's attributes, applying the D-13 {@code <data>} wrap
     * to every user-editable String attribute. Non-user-editable strings serialize as plain
     * JSON strings; non-String values pass through as-is.
     */
    private Map<String, Object> buildEntityMap(Object entity, MetaClass mc) {
        Set<String> userEditable = scanner.getUserEditableStringIndex().forEntity(mc);
        Map<String, Object> row = new LinkedHashMap<>();
        for (MetaProperty mp : mc.getProperties()) {
            // Skip attributes not present in the entity's fetch plan — calling
            // EntityValues.getValue on an unfetched attribute of a detached entity throws
            // "Cannot get unfetched attribute". FetchPlan.INSTANCE_NAME only loads the
            // properties listed in @InstanceName / @DependsOnProperties, so most other
            // attributes are unfetched. Rendering them as null is the correct read-only
            // behavior; callers drill further via get_record / get_related_records (D-12).
            if (!entityStates.isLoaded(entity, mp.getName())) {
                row.put(mp.getName(), null);
                continue;
            }
            Object v = EntityValues.getValue(entity, mp.getName());
            if (v instanceof String s && userEditable.contains(mp.getName())) {
                row.put(mp.getName(), "<data>" + escapeDataDelimiters(s) + "</data>");
            } else if (v instanceof Collection<?> col) {
                // Collection-valued attribute. When loaded, emit {_collectionSize: n} so the LLM
                // can distinguish "empty" from "not fetched" (null). get_related_records is the
                // supported path to drill into the actual rows (D-12).
                Map<String, Object> sizeOnly = new LinkedHashMap<>();
                sizeOnly.put("_collectionSize", col.size());
                row.put(mp.getName(), sizeOnly);
            } else if (v != null && mp.getRange().isClass()) {
                // Reference attribute: render via MetadataTools.getInstanceName (canonical Jmix
                // instance-name) wrapped in <data>...</data>. Instance names are derived from
                // user-editable fields, so the <data> wrap + delimiter escape closes the gap
                // where a malicious @InstanceName field would otherwise bypass TOOL-07/D-13.
                String instanceName = metadataTools.getInstanceName(v);
                row.put(mp.getName(),
                        "<data>" + escapeDataDelimiters(instanceName) + "</data>");
            } else {
                row.put(mp.getName(), v);
            }
        }
        return row;
    }

    /**
     * Escape the literal delimiter substrings {@code <data>} and {@code </data>} inside a
     * user-editable value so an attacker-supplied value cannot terminate the wrapper and smuggle
     * instructions (Pitfall 4 — delimiter escape-sequence bypass).
     */
    static String escapeDataDelimiters(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("<data>", "&lt;data&gt;")
                .replace("</data>", "&lt;/data&gt;");
    }

    private String writeJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
