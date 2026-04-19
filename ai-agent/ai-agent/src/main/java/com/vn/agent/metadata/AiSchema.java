package com.vn.agent.metadata;

import java.util.Map;

/**
 * Immutable snapshot of entities visible to a caller. Keyed by Jmix entity name (D-11),
 * e.g. {@code "jmixapp_Order"}.
 */
public record AiSchema(Map<String, AiEntityInfo> entities) {
    public AiSchema {
        entities = Map.copyOf(entities);
    }
}
