package com.vn.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.metadata.AiAttributeInfo;
import com.vn.agent.metadata.AiEntityInfo;
import com.vn.agent.metadata.MetamodelScanner;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
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

    public ToolResultFormatter(ObjectMapper objectMapper, MetamodelScanner scanner) {
        this.objectMapper = objectMapper;
        this.scanner = scanner;
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

    /** describe_entity response (D-02). */
    public String describe(AiEntityInfo info) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityName", info.entityName());
        out.put("label", info.localizedLabel());
        List<Map<String, Object>> attrs = new ArrayList<>(info.attributes().size());
        for (AiAttributeInfo a : info.attributes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.name());
            m.put("type", a.typeLabel());
            m.put("nullable", a.nullable());
            m.put("label", a.localizedLabel());
            if (a.enumValues() != null) m.put("enumValues", a.enumValues());
            if (a.relationshipTarget() != null) m.put("relationshipTarget", a.relationshipTarget());
            if (a.validationConstraints() != null && !a.validationConstraints().isEmpty()) {
                m.put("constraints", a.validationConstraints());
            }
            attrs.add(m);
        }
        out.put("attributes", attrs);
        return writeJson(out);
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
            Object v = EntityValues.getValue(entity, mp.getName());
            if (v instanceof String s && userEditable.contains(mp.getName())) {
                row.put(mp.getName(), "<data>" + escapeDataDelimiters(s) + "</data>");
            } else if (v instanceof Collection<?>) {
                // Collections of related entities: leave to downstream tools; render as null
                // to avoid accidentally serializing a lazy-loaded graph. get_related_records
                // is the supported path.
                row.put(mp.getName(), null);
            } else if (v != null && mp.getRange().isClass()) {
                // Reference attribute: serialize the target's instance-name fragment by name only,
                // to avoid recursing and to avoid double-wrapping. Callers use get_record /
                // get_related_records to drill further (D-12).
                row.put(mp.getName(), String.valueOf(v));
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
