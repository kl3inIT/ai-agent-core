package com.vn.agent.extraction.introspection;

import io.jmix.core.MessageTools;
import io.jmix.core.metamodel.model.MetaProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared base for {@link PropertyIntrospector} beans: localized caption lookup and the JSON-schema
 * fragment builder. Mirrors the {@code getPropertyCaption} default helper jmix-crm hangs on its
 * introspector interface, kept as an abstract base here so the shared {@link MessageTools}
 * dependency is injected once per bean.
 */
public abstract class AbstractPropertyIntrospector implements PropertyIntrospector {

    protected final MessageTools messageTools;

    protected AbstractPropertyIntrospector(MessageTools messageTools) {
        this.messageTools = messageTools;
    }

    /** Localized attribute caption, falling back to the raw attribute name. */
    protected String description(MetaProperty metaProperty) {
        try {
            return messageTools.getPropertyCaption(metaProperty);
        } catch (RuntimeException ignored) {
            return metaProperty.getName();
        }
    }

    /** Mutable JSON-schema fragment with {@code type}, optional {@code format} and {@code description}. */
    protected Map<String, Object> attributeSchema(String type, String format, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (format != null) {
            schema.put("format", format);
        }
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        return schema;
    }
}
