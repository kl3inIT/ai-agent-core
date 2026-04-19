package com.vn.agent.metadata;

import io.jmix.core.metamodel.model.MetaClass;

import java.util.List;

/**
 * Per-entity metadata for the LLM.
 * <p>{@code localizedLabel} is resolved per-request by {@link EffectiveSchemaComputer} via
 * {@code MessageTools} (D-04); {@link MetamodelScanner} leaves it {@code null}.</p>
 */
public record AiEntityInfo(MetaClass metaClass,
                           String entityName,
                           String localizedLabel,
                           List<AiAttributeInfo> attributes) {
    public AiEntityInfo {
        attributes = List.copyOf(attributes);
    }

    public AiEntityInfo withLocalizedLabel(String label) {
        return new AiEntityInfo(metaClass, entityName, label, attributes);
    }

    public AiEntityInfo withAttributes(List<AiAttributeInfo> attrs) {
        return new AiEntityInfo(metaClass, entityName, localizedLabel, attrs);
    }
}
