package com.vn.agent.tools;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.filter.FilterLiteralValueConverter;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared entity-resolution helper consumed by {@link BuiltInDataTools} (READ path)
 * and {@code BuiltInMutationTools} (WRITE path, Wave 5+).
 *
 * <p>Enforces the Phase 10 R4 uniform-opacity contract: unknown entity name,
 * unknown metaClass, and {@link LlmExposurePolicy#canReadEntity} denial all
 * collapse to {@code unknown_entity} (never {@code access_denied}). The LLM cannot
 * distinguish "no such entity" from "entity exists but denied" (whether by Jmix
 * role OR admin denylist) — full opacity per EXP-09 / Phase 3 D-08.
 *
 * <p>The write side has operation-specific gates. {@link #resolveCreatableEntityOrThrow}
 * calls {@link LlmExposurePolicy#canCreate}; {@link #resolveUpdatableEntityOrThrow}
 * calls {@link LlmExposurePolicy#canUpdate}. This avoids blocking create-only
 * users through an update-oriented check (HIGH review feedback addressed in Plan 11-04).
 *
 * <p>MUT-09 mandates a single resolver consumed by both
 * {@code BuiltInDataTools} (READ) and {@code BuiltInMutationTools} (WRITE) so the
 * unknown-entity opacity contract from Phase 10 R4 is enforced uniformly.
 */
@Component
public class ToolEntityResolver {

    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final LlmExposurePolicy llmExposurePolicy;
    private final FilterLiteralValueConverter filterLiteralValueConverter;

    public ToolEntityResolver(Metadata metadata,
                              MetadataTools metadataTools,
                              LlmExposurePolicy llmExposurePolicy,
                              FilterLiteralValueConverter filterLiteralValueConverter) {
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.llmExposurePolicy = llmExposurePolicy;
        this.filterLiteralValueConverter = filterLiteralValueConverter;
    }

    /**
     * Resolves a metaClass by name, enforcing R4 uniform opacity:
     * null/blank entityName, unknown to Metadata, OR
     * {@link LlmExposurePolicy#canReadEntity} == false →
     * {@code ToolUserError("unknown_entity", ..., UnknownEntityHints.AS_LIST)}.
     *
     * <p>UNKNOWN_ENTITY_HINTS strings are byte-for-byte unchanged (em dash U+2014 on
     * hint #3 preserved per Phase 9 D-14 / TEST-08).
     */
    public MetaClass resolveReadableEntityOrThrow(String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw new ToolUserError("unknown_entity",
                    "entity name must not be blank",
                    UnknownEntityHints.AS_LIST);
        }

        MetaClass metaClass;
        try {
            metaClass = metadata.getClass(entityName);
        } catch (RuntimeException runtimeException) {
            throw new ToolUserError("unknown_entity",
                    "no entity named " + entityName,
                    UnknownEntityHints.AS_LIST);
        }

        if (metaClass == null) {
            throw new ToolUserError("unknown_entity",
                    "no entity named " + entityName,
                    UnknownEntityHints.AS_LIST);
        }
        if (!llmExposurePolicy.canReadEntity(metaClass)) {
            // Phase 10 Fix R4 — full uniformity (EXP-09, Phase 3 D-08): denied entities surface
            // identically to non-existent ones.
            throw new ToolUserError("unknown_entity",
                    "no entity named " + entityName,
                    UnknownEntityHints.AS_LIST);
        }
        return metaClass;
    }

    /**
     * Read-resolved entity + create-side gate. Returns {@code access_denied} when
     * the entity is visible but {@link LlmExposurePolicy#canCreate} returns false.
     * Visible-but-denied is intentionally distinguishable from unknown (the LLM
     * already saw this entity in {@code list_entities}).
     */
    public MetaClass resolveCreatableEntityOrThrow(String entityName) {
        MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
        if (!llmExposurePolicy.canCreate(metaClass)) {
            throw new ToolUserError("access_denied",
                    "create not permitted on " + metaClass.getName(),
                    List.of("do not retry; surface to user"));
        }
        return metaClass;
    }

    /**
     * Read-resolved entity + update-side gate. Returns {@code access_denied} when
     * the entity is visible but {@link LlmExposurePolicy#canUpdate} returns false.
     * Visible-but-denied is intentionally distinguishable from unknown (the LLM
     * already saw this entity in {@code list_entities}).
     */
    public MetaClass resolveUpdatableEntityOrThrow(String entityName) {
        MetaClass metaClass = resolveReadableEntityOrThrow(entityName);
        if (!llmExposurePolicy.canUpdate(metaClass)) {
            throw new ToolUserError("access_denied",
                    "update not permitted on " + metaClass.getName(),
                    List.of("do not retry; surface to user"));
        }
        return metaClass;
    }

    /**
     * Convert an LLM-supplied id string to the Java type of the entity's primary-key property.
     * For Jmix apps using UUID ids this parses the string via {@link java.util.UUID#fromString}.
     * Delegates to {@link FilterLiteralValueConverter} so non-UUID ids (long, String) are also
     * handled.
     */
    public Object parseEntityId(String id, MetaClass metaClass) {
        if (id == null || id.isBlank()) {
            throw new ToolUserError("invalid_id", "id must not be blank");
        }
        MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(metaClass);
        if (primaryKeyProperty == null) {
            throw new ToolUserError("invalid_id",
                    "entity " + metaClass.getName() + " has no primary key");
        }
        return filterLiteralValueConverter.convertValue(id, primaryKeyProperty);
    }

    /**
     * Preserves {@link BuiltInDataTools}' existing relationship-target filtering:
     * keep the caller-provided iteration order, keep non-relationship/simple names,
     * and drop only relationship attributes whose target entity is not readable.
     */
    public Set<String> llmReadableAttributes(MetaClass metaClass, Set<String> readableAttributeNames) {
        return readableAttributeNames.stream()
                .filter(attributeName -> {
                    MetaProperty property = metaClass.findProperty(attributeName);
                    return property == null
                            || !property.getRange().isClass()
                            || llmExposurePolicy.canReadEntity(property.getRange().asClass());
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
