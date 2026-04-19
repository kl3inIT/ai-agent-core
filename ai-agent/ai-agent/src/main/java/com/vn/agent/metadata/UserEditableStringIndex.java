package com.vn.agent.metadata;

import io.jmix.core.metamodel.model.MetaClass;

import java.util.Map;
import java.util.Set;

/**
 * Scanner output: per-entity-name set of attribute names that are user-editable strings and MUST
 * be wrapped in {@code <data>…</data>} by {@code ToolResultFormatter} (D-13).
 * "User-editable" = persistent + String-typed + not annotated {@code @SystemLevel} and not
 * framework-managed (createdBy, createdDate, lastModifiedBy, lastModifiedDate, deletedBy,
 * deletedDate, version, id).
 */
public record UserEditableStringIndex(Map<String, Set<String>> byEntityName) {
    public UserEditableStringIndex {
        byEntityName = Map.copyOf(byEntityName);
    }

    /** Empty set for unknown or non-persistent-entity inputs. */
    public Set<String> forEntity(String entityName) {
        return byEntityName.getOrDefault(entityName, Set.of());
    }

    /** Shortcut. */
    public Set<String> forEntity(MetaClass mc) {
        return forEntity(mc.getName());
    }
}
