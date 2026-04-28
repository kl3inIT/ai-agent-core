package com.vn.agent.spi;

import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed descriptor of a mutation tool invocation, passed to {@link MutationGuard#check}.
 *
 * <p>Forward-compatible: extra fields can be added behind default methods later.
 * In v1.1, guards needing user/conversation context fetch from
 * {@code CurrentAuthentication}/{@code RunContext}; guards needing pre-image
 * reload via {@code DataManager} themselves (no pre-image lazy supplier in v1.1).
 *
 * @param toolName  the @Tool name being invoked
 *                  (one of {@code create_record}, {@code update_record},
 *                  {@code add_related_record}, {@code remove_related_record})
 * @param metaClass the resolved Jmix MetaClass for the target entity
 * @param entityId  null on {@code create_record}; populated on update / add_related / remove_related
 * @param attributes the LLM-supplied attributes Map (post type-coercion); never null, may be empty,
 *                   may contain null values when the user is clearing optional attributes
 */
public record MutationIntent(
        String toolName,
        MetaClass metaClass,
        @Nullable UUID entityId,
        Map<String, Object> attributes) {
    public MutationIntent {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(metaClass, "metaClass");
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
