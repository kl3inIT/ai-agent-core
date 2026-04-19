package com.vn.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>All entity-row serialization goes through this class. Every loaded String value sourced
 * from entity data is wrapped in {@code <data>...</data>} by default — there is no separate
 * classification layer deciding which attributes are "user-editable" or "untrusted". The rule
 * is simple and audit-friendly: strings that came from the domain are untrusted text. Literal
 * {@code <data>} / {@code </data>} substrings inside values are escaped (Pitfall 4 — plan
 * 03-RESEARCH.md) to prevent delimiter-bypass.
 *
 * <p>Plan 04's prompt-injection harness verifies both the wrap AND the escape.
 */
@Component
public class ToolResultFormatter {

    private final ObjectMapper objectMapper;
    private final EntityStates entityStates;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;

    public ToolResultFormatter(ObjectMapper objectMapper,
                               EntityStates entityStates,
                               MetadataTools metadataTools,
                               MessageTools messageTools) {
        this.objectMapper = objectMapper;
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
     * the metamodel is already authoritative. {@code readableAttributeNames} is the access-filtered
     * subset from {@code EffectiveSchemaComputer.forCurrentUser()}.
     */
    public String describe(MetaClass metaClass, Set<String> readableAttributeNames) {
        Map<String, Object> entityDescription = new LinkedHashMap<>();
        entityDescription.put("entityName", metaClass.getName());
        entityDescription.put("label", messageTools.getEntityCaption(metaClass));

        List<Map<String, Object>> attributeDescriptions = new ArrayList<>();
        for (MetaProperty metaProperty : metaClass.getProperties()) {
            if (!readableAttributeNames.contains(metaProperty.getName())) {
                continue;
            }
            Map<String, Object> attributeDescription = new LinkedHashMap<>();
            attributeDescription.put("name", metaProperty.getName());
            attributeDescription.put("type", typeLabel(metaProperty));
            attributeDescription.put("nullable", !metaProperty.isMandatory());
            attributeDescription.put("label", messageTools.getPropertyCaption(metaProperty));

            Range range = metaProperty.getRange();
            if (range.isEnum()) {
                List<String> enumValueNames = new ArrayList<>();
                for (Object enumValue : range.asEnumeration().getValues()) {
                    enumValueNames.add(((Enum<?>) enumValue).name());
                }
                attributeDescription.put("enumValues", enumValueNames);
            }
            if (range.isClass()) {
                attributeDescription.put("relationshipTarget", range.asClass().getName());
            }
            if (range.isDatatype() && range.asDatatype().getJavaClass() == String.class) {
                Integer maxLength = columnLength(metaProperty);
                if (maxLength != null) {
                    attributeDescription.put("maxLength", maxLength);
                }
            }
            attributeDescriptions.add(attributeDescription);
        }
        entityDescription.put("attributes", attributeDescriptions);
        return writeJson(entityDescription);
    }

    private String typeLabel(MetaProperty metaProperty) {
        Range range = metaProperty.getRange();
        if (range.isEnum()) return "enum:" + range.asEnumeration().getJavaClass().getSimpleName();
        if (range.isClass()) return "ref:" + range.asClass().getName();
        if (range.isDatatype()) return range.asDatatype().getJavaClass().getSimpleName();
        return "unknown";
    }

    private Integer columnLength(MetaProperty metaProperty) {
        java.lang.reflect.AnnotatedElement annotatedElement = metaProperty.getAnnotatedElement();
        if (annotatedElement == null) return null;
        jakarta.persistence.Column column = annotatedElement.getAnnotation(jakarta.persistence.Column.class);
        if (column == null) return null;
        int length = column.length();
        // JPA default is 255 — emit only when explicitly set (non-default). Most app entities
        // set an explicit @Column(length=…) when they care; returning 255 for every String
        // attribute would add noise.
        return length == 255 ? null : length;
    }

    /** get_record response — one entity row. */
    public String record(Object entity, MetaClass metaClass) {
        return writeJson(buildFormattedEntityRow(entity, metaClass));
    }

    /** find_records response — rows + limit + truncated flag + optional hint (D-14). */
    public String records(List<?> rows, MetaClass metaClass, int limit, boolean truncated) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entityName", metaClass.getName());
        List<Map<String, Object>> serializedRows = new ArrayList<>(rows.size());
        for (Object row : rows) {
            serializedRows.add(buildFormattedEntityRow(row, metaClass));
        }
        response.put("rows", serializedRows);
        response.put("limit", limit);
        response.put("truncated", truncated);
        if (truncated) {
            response.put("hint",
                    "result was truncated to the limit; call count_records for the exact total or narrow the filter");
        }
        return writeJson(response);
    }

    /** get_related_records response. */
    public String related(Object root, MetaProperty relationProperty, List<?> relatedRows) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entityName", relationProperty.getDomain().getName());
        response.put("relationship", relationProperty.getName());
        MetaClass targetMetaClass = relationProperty.getRange().asClass();
        response.put("targetEntity", targetMetaClass.getName());
        List<Map<String, Object>> serializedRows = new ArrayList<>(relatedRows.size());
        for (Object relatedRow : relatedRows) {
            serializedRows.add(buildFormattedEntityRow(relatedRow, targetMetaClass));
        }
        response.put("rows", serializedRows);
        return writeJson(response);
    }

    /** count_records response. */
    public String count(MetaClass metaClass, long count) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entityName", metaClass.getName());
        response.put("count", count);
        return writeJson(response);
    }

    // ---- internals ----

    /**
     * Build a JSON-ready Map of the entity's attributes. The D-13 rule is:
     * every loaded String value — whether a direct attribute or the
     * {@code MetadataTools.getInstanceName(...)} rendering of a reference — is wrapped in
     * {@code <data>...</data>}. No allowlist, no per-attribute classification: the fact that
     * the value came from entity data is the criterion.
     */
    private Map<String, Object> buildFormattedEntityRow(Object entity, MetaClass metaClass) {
        Map<String, Object> formattedEntityRow = new LinkedHashMap<>();
        for (MetaProperty metaProperty : metaClass.getProperties()) {
            // Skip attributes not present in the entity's fetch plan — calling
            // EntityValues.getValue on an unfetched attribute of a detached entity throws
            // "Cannot get unfetched attribute". FetchPlan.INSTANCE_NAME only loads the
            // properties listed in @InstanceName / @DependsOnProperties, so most other
            // attributes are unfetched. Rendering them as null is the correct read-only
            // behavior; callers drill further via get_record / get_related_records (D-12).
            if (!entityStates.isLoaded(entity, metaProperty.getName())) {
                formattedEntityRow.put(metaProperty.getName(), null);
                continue;
            }
            Object attributeValue = EntityValues.getValue(entity, metaProperty.getName());
            if (attributeValue instanceof String stringValue) {
                formattedEntityRow.put(metaProperty.getName(), wrapUntrustedText(stringValue));
            } else if (attributeValue instanceof Collection<?> loadedCollection) {
                // Collection-valued attribute. When loaded, emit {_collectionSize: n} so the LLM
                // can distinguish "empty" from "not fetched" (null). get_related_records is the
                // supported path to drill into the actual rows (D-12).
                Map<String, Object> collectionSummary = new LinkedHashMap<>();
                collectionSummary.put("_collectionSize", loadedCollection.size());
                formattedEntityRow.put(metaProperty.getName(), collectionSummary);
            } else if (attributeValue != null && metaProperty.getRange().isClass()) {
                // Reference attribute: render via MetadataTools.getInstanceName (canonical Jmix
                // instance-name). Instance names almost always derive from user-authored text
                // fields, so they go through the same wrap as direct String attributes.
                String instanceName = metadataTools.getInstanceName(attributeValue);
                formattedEntityRow.put(metaProperty.getName(), wrapUntrustedText(instanceName));
            } else {
                formattedEntityRow.put(metaProperty.getName(), attributeValue);
            }
        }
        return formattedEntityRow;
    }

    /**
     * Centralizes the prompt-boundary marker used throughout Phase 3. Any text value that can
     * originate from host application data is wrapped before it is shown to the model so the
     * system prompt can treat it as untrusted content rather than as instructions.
     */
    private String wrapUntrustedText(String value) {
        return "<data>" + escapeDataDelimiters(value) + "</data>";
    }

    /**
     * Escape the literal delimiter substrings {@code <data>} and {@code </data>} inside a
     * text value so an attacker-supplied value cannot terminate the wrapper and smuggle
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
