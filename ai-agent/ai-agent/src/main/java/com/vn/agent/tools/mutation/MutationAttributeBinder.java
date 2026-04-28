package com.vn.agent.tools.mutation;

import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.DataManager;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict mass-assignment validator + scalar/to-one coercion + setter helper for
 * {@code BuiltInMutationTools} (Plan 11-07A).
 *
 * <p>Pipeline (called once per attribute the LLM writes):
 * <ol>
 *     <li>{@link #validateWritableProperty} — rejects unknown / primary key / version /
 *         audit-system / read-only / non-JPA-transient-calculated / collection-relationship
 *         properties BEFORE any {@link io.jmix.core.accesscontext.EntityAttributeContext}
 *         is constructed. Unknown attribute is caught by
 *         {@link MetaClass#findProperty(String)} returning null.</li>
 *     <li>{@link #coerceAttributeValue} — for scalar/datatype/enum delegates to
 *         {@link FilterLiteralValueConverter#convertValue(Object, MetaProperty)}; for
 *         to-one relationship attributes loads the target entity via regular
 *         {@link DataManager} (so user-level row policies apply), then enforces target
 *         LLM read exposure + Jmix read permission via
 *         {@link MutationAuthorizationService}.</li>
 * </ol>
 *
 * <p>The returned map from {@link #coerceAttributes} is what feeds
 * {@link com.vn.agent.spi.MutationGuard#check(com.vn.agent.spi.MutationIntent)} — guards
 * receive POST-coercion typed values (loaded entity instances for to-ones, typed
 * Integer/UUID/etc. for scalars), never raw LLM strings.
 *
 * <p>{@link #applyAttributes} walks the coerced map and calls
 * {@link EntityValues#setValue(Object, String, Object)} on the target entity, returning
 * the post-image map for diff serialization (AUD-07).
 *
 * <p><b>Audit/system field denylist</b> mirrors Jmix's standard {@code @CreatedBy},
 * {@code @CreatedDate}, {@code @LastModifiedBy}, {@code @LastModifiedDate}, soft-delete
 * audit fields, and {@code version} on top of the metamodel checks. The list is intentional
 * and conservative; hosts that want to expose an audit field as writable must rename it.
 */
@Component
public class MutationAttributeBinder {

    /**
     * Standard Jmix audit / system field names that must never be writable through a
     * mutation tool. Mirrors the {@code @CreatedBy}/{@code @CreatedDate}/etc. trait
     * conventions plus soft-delete columns. {@code version} is tracked separately by the
     * primary {@code @Version} JPA annotation but the field name is also denylisted as a
     * defense in depth in case a host names a non-version field {@code "version"}.
     */
    private static final Set<String> AUDIT_SYSTEM_FIELD_NAMES = Set.of(
            "createdBy", "createdDate",
            "lastModifiedBy", "lastModifiedDate",
            "deletedBy", "deletedDate",
            "version"
    );

    private final MetadataTools metadataTools;
    private final DataManager dataManager;
    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final ToolEntityResolver toolEntityResolver;
    private final MutationAuthorizationService mutationAuthorizationService;
    private final MutationErrorTranslator mutationErrorTranslator;

    public MutationAttributeBinder(MetadataTools metadataTools,
                                   DataManager dataManager,
                                   FilterLiteralValueConverter filterLiteralValueConverter,
                                   ToolEntityResolver toolEntityResolver,
                                   MutationAuthorizationService mutationAuthorizationService,
                                   MutationErrorTranslator mutationErrorTranslator) {
        this.metadataTools = metadataTools;
        this.dataManager = dataManager;
        this.filterLiteralValueConverter = filterLiteralValueConverter;
        this.toolEntityResolver = toolEntityResolver;
        this.mutationAuthorizationService = mutationAuthorizationService;
        this.mutationErrorTranslator = mutationErrorTranslator;
    }

    /**
     * Validate every attribute name and coerce every value. The returned map preserves
     * caller iteration order, allows null values (so guards can see optional-field clears),
     * and contains POST-coercion typed values: loaded entity instances for to-ones, typed
     * Integer/UUID/Enum/etc. for scalars.
     */
    public Map<String, Object> coerceAttributes(MetaClass metaClass, Map<String, Object> attributes) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String attributeName = entry.getKey();
            MetaProperty property = validateWritableProperty(metaClass, attributeName);
            Object coercedValue = coerceAttributeValue(metaClass, property, entry.getValue());
            coerced.put(attributeName, coercedValue);
        }
        return coerced;
    }

    /**
     * Apply the coerced attributes to {@code entity} via
     * {@link EntityValues#setValue(Object, String, Object)} and return a defensive copy of
     * the post-image (attribute → coerced value) for diff serialization.
     */
    public Map<String, Object> applyAttributes(MetaClass metaClass,
                                               Object entity,
                                               Map<String, Object> coercedAttributes) {
        Map<String, Object> postImage = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : coercedAttributes.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue();
            EntityValues.setValue(entity, attribute, value);
            postImage.put(attribute, value);
        }
        return postImage;
    }

    /**
     * Capture pre-image values for the attributes being changed. Used by
     * {@code update_record} to produce the diff JSON. Reads via {@link EntityValues#getValue}
     * — the existing entity must already be loaded with these attributes available
     * (Jmix DataManager id-load loads the {@code _base} graph).
     */
    public Map<String, Object> capturePreImage(Object entity, Set<String> attributeNames) {
        Map<String, Object> preImage = new LinkedHashMap<>();
        for (String attribute : attributeNames) {
            preImage.put(attribute, EntityValues.getValue(entity, attribute));
        }
        return preImage;
    }

    /**
     * Mass-assignment validator. Order is intentional:
     * <ol>
     *     <li>Unknown attribute ({@code metaClass.findProperty(...) == null}) — rejected
     *         BEFORE any {@link io.jmix.core.accesscontext.EntityAttributeContext} is built
     *         for that attribute (must-haves contract).</li>
     *     <li>Primary key (via {@link MetadataTools#getPrimaryKeyProperty}).</li>
     *     <li>Audit/system field name from {@link #AUDIT_SYSTEM_FIELD_NAMES}.</li>
     *     <li>{@link MetaProperty#isReadOnly()}.</li>
     *     <li>Non-JPA / transient / calculated property
     *         ({@link MetadataTools#isJpa(MetaProperty)} returns false).</li>
     *     <li>Collection-valued relationship
     *         ({@code property.getRange().getCardinality().isMany()}).</li>
     * </ol>
     * Each rejection throws {@link ToolUserError} with stable code {@code validation_failed}
     * and a hint pointing the LLM to {@code describe_entity}.
     */
    public MetaProperty validateWritableProperty(MetaClass metaClass, String attributeName) {
        MetaProperty property = metaClass.findProperty(attributeName);
        if (property == null) {
            throw notWritable();
        }
        MetaProperty primaryKey = metadataTools.getPrimaryKeyProperty(metaClass);
        if (primaryKey != null && primaryKey.getName().equals(attributeName)) {
            throw notWritable();
        }
        if (AUDIT_SYSTEM_FIELD_NAMES.contains(attributeName)) {
            throw notWritable();
        }
        if (property.isReadOnly()) {
            throw notWritable();
        }
        if (!metadataTools.isJpa(property)) {
            throw notWritable();
        }
        if (property.getRange().getCardinality() != null
                && property.getRange().getCardinality().isMany()) {
            // Collection-valued relationships are NOT settable through create/update_record.
            // Caller must use add_related_record / remove_related_record.
            throw new ToolUserError("validation_failed",
                    "collection relationship cannot be assigned through attributes",
                    List.of("use add_related_record or remove_related_record for collection relationships"));
        }
        return property;
    }

    /**
     * Coerce a raw JSON-deserialized LLM value into the Java type the property expects.
     * Two branches:
     * <ul>
     *     <li><b>To-one relationship</b> ({@code property.getRange().isClass()} and not
     *         many): null clears the reference; non-null is parsed as a UUID, validated
     *         for LLM read exposure on the target, then loaded via regular
     *         {@link DataManager} so user-level row policies apply. Missing target row
     *         throws stable {@code not_found}.</li>
     *     <li><b>Scalar/datatype/enum:</b> delegates to
     *         {@link FilterLiteralValueConverter#convertValue(Object, MetaProperty)} which
     *         throws structured {@link ToolUserError} on type mismatch (legacy codes
     *         {@code invalid_literal}/{@code unsupported_type} are remapped at the
     *         mutation boundary by {@link MutationErrorTranslator} to
     *         {@code parameter_conversion_error}).</li>
     * </ul>
     *
     * @param ownerMetaClass the owner of {@code property}; used only for context in errors
     * @param property the validated writable property
     * @param rawValue the LLM-supplied value
     * @return the coerced typed value
     */
    public Object coerceAttributeValue(MetaClass ownerMetaClass,
                                       MetaProperty property,
                                       Object rawValue) {
        if (property.getRange().isClass()) {
            // collection-valued was already rejected by validateWritableProperty; defensive
            // re-check keeps this method safe to call standalone.
            if (property.getRange().getCardinality() != null
                    && property.getRange().getCardinality().isMany()) {
                throw new ToolUserError("validation_failed",
                        "collection relationship cannot be assigned through attributes",
                        List.of("use add_related_record or remove_related_record for collection relationships"));
            }
            if (rawValue == null) {
                // null clears the reference; preserved by coerceAttributes via LinkedHashMap.
                return null;
            }
            MetaClass targetMetaClass = property.getRange().asClass();
            mutationAuthorizationService.enforceLlmRelationshipTargetExposure(targetMetaClass, false);
            UUID targetId = requireUuidId(
                    toolEntityResolver.parseEntityId(rawValue.toString(), targetMetaClass));
            mutationAuthorizationService.enforceReadPermission(targetMetaClass);
            return dataManager.load(targetMetaClass.getJavaClass())
                    .id(targetId)
                    .optional()
                    .orElseThrow(() -> mutationErrorTranslator.notFound(targetMetaClass, rawValue.toString()));
        }
        // scalar/datatype/enum — delegate to existing structured filter converter.
        return filterLiteralValueConverter.convertValue(rawValue, property);
    }

    /**
     * Coerce a parsed entity id to UUID; reject non-UUID host id types because v1.1 mutation
     * tools are UUID-id-only (D-01 — every mutation id must be UUID-keyed).
     */
    public UUID requireUuidId(Object parsedId) {
        if (parsedId instanceof UUID uuid) {
            return uuid;
        }
        throw new ToolUserError("parameter_conversion_error",
                "id must be a UUID",
                List.of("use the UUID id returned by find_records/get_record/create_record"));
    }

    private static ToolUserError notWritable() {
        return new ToolUserError("validation_failed",
                "attribute is not writable",
                List.of("call describe_entity to inspect writable attributes; if you change values, retry with a fresh idempotencyKey"));
    }
}
