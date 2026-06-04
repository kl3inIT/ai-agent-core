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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * Validate every attribute name and coerce every value for a SINGLE row (create/update).
     * Delegates through the two-pass batch path with a one-element row list so create/update
     * get the same single-call to-one FK dedup as {@code bulk_save_records} — one constrained
     * {@code .ids(...)} load per distinct target class, not one load per to-one attribute
     * (D-07). The returned map preserves caller iteration order, allows null values (so guards
     * can see optional-field clears), and contains POST-coercion typed values: loaded entity
     * instances for to-ones, typed Integer/UUID/Enum/etc. for scalars.
     */
    public Map<String, Object> coerceAttributes(MetaClass metaClass, Map<String, Object> attributes) {
        Map<MetaClass, Map<UUID, Object>> prefetched =
                prefetchReferences(metaClass, List.of(attributes));
        return coerceAttributes(metaClass, attributes, prefetched);
    }

    /**
     * Pass 1 (cross-row to-one FK prefetch). Iterate ALL {@code rows}; for each writable to-one
     * relationship attribute collect the parsed UUID id per target {@link MetaClass} into a
     * per-class id set (deduped, submission-order via {@link LinkedHashSet}; null FK values =
     * clear and are skipped). Then for EACH distinct target class EXACTLY ONCE — same order/timing
     * as the per-reference baseline — enforce
     * {@link MutationAuthorizationService#enforceLlmRelationshipTargetExposure(MetaClass, boolean)}
     * then {@link MutationAuthorizationService#enforceReadPermission(MetaClass)} (the ONLY
     * {@code access_denied} source), then issue ONE constrained
     * {@code dataManager.load(targetClass).ids(idCollection).list()} (the
     * {@code FluentLoader.ids(java.util.Collection<?>)} overload, jmix-core 2.8.1) so a K-row
     * batch referencing one parent costs ONE FK SELECT regardless of K (MUT-16; slope ~0).
     *
     * <p><b>Security (D-09, T-17-02):</b> uses the CONSTRAINED {@link DataManager} only — never the
     * unconstrained variant, never raw JPQL — so row-level security still filters the result. A
     * row-level-filtered id is simply absent from the returned map and the bind pass collapses it
     * to {@code not_found} (never {@code access_denied}), preserving the opacity contract.
     *
     * <p><b>Prefetch-phase failures are NOT attributed to a specific row (WR-04).</b> This pass
     * runs in gate 5 (coerce), BEFORE the per-row save loop. Per-row attribute-name validation
     * ({@link #validateWritableProperty} — unknown / read-only / PK attribute) and FK-UUID parsing
     * ({@code requireUuidId(parseEntityId(...))} — malformed FK id) throw here, where the failing
     * row ordinal is not tracked. Because the batch is all-or-nothing (the whole submission rolls
     * back on any failure), the contract does NOT expose a per-row index in the error result; the
     * LLM resubmits ALL corrected rows with a fresh idempotency key. This is intentional — see the
     * {@code bulk_save_records} tool description's ERROR HANDLING section.
     *
     * @param ownerMetaClass the entity class every {@code row} belongs to
     * @param rows the LLM-supplied attribute maps (one per record in the batch; single-element
     *             for create/update)
     * @return per-target-class map of {@code id -> loaded entity instance} for every distinct
     *         readable to-one FK id referenced across {@code rows}
     */
    public Map<MetaClass, Map<UUID, Object>> prefetchReferences(MetaClass ownerMetaClass,
                                                                List<Map<String, Object>> rows) {
        // Collect distinct to-one FK ids per target class across ALL rows (submission order).
        Map<MetaClass, LinkedHashSet<UUID>> idsByTarget = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                MetaProperty property = validateWritableProperty(ownerMetaClass, entry.getKey());
                if (!isToOneRelationship(property)) {
                    continue;
                }
                Object rawValue = entry.getValue();
                if (rawValue == null) {
                    // null clears the reference — no FK to load.
                    continue;
                }
                MetaClass targetMetaClass = property.getRange().asClass();
                UUID targetId = requireUuidId(
                        toolEntityResolver.parseEntityId(rawValue.toString(), targetMetaClass));
                idsByTarget.computeIfAbsent(targetMetaClass, k -> new LinkedHashSet<>())
                        .add(targetId);
            }
        }

        // One constrained .ids() load per distinct target class (after the per-class gate pair).
        Map<MetaClass, Map<UUID, Object>> prefetched = new LinkedHashMap<>();
        for (Map.Entry<MetaClass, LinkedHashSet<UUID>> entry : idsByTarget.entrySet()) {
            MetaClass targetMetaClass = entry.getKey();
            Collection<UUID> idSet = entry.getValue();

            mutationAuthorizationService.enforceLlmRelationshipTargetExposure(targetMetaClass, false);
            mutationAuthorizationService.enforceReadPermission(targetMetaClass);

            List<?> loaded = dataManager.load(targetMetaClass.getJavaClass())
                    .ids(idSet)
                    .list();

            Map<UUID, Object> byId = new LinkedHashMap<>();
            for (Object instance : loaded) {
                Object id = EntityValues.getId(instance);
                if (id instanceof UUID uuid) {
                    byId.put(uuid, instance);
                }
            }
            prefetched.put(targetMetaClass, byId);
        }
        return prefetched;
    }

    /**
     * Pass 2 (prefetched bind). Identical to {@link #coerceAttributes(MetaClass, Map)} except the
     * to-one branch binds from {@code prefetched} (built by {@link #prefetchReferences}) instead of
     * issuing its own per-reference load — so no FK SELECT is issued here. For a to-one attribute:
     * re-parse the id, look it up in {@code prefetched.get(targetMetaClass)}; if ABSENT (genuinely
     * missing OR row-level-security-filtered) throw
     * {@link MutationErrorTranslator#notFound(MetaClass, String)} (the whole all-or-nothing batch
     * then rolls back — no per-row index is reported, see {@link #prefetchReferences}); if PRESENT
     * bind the loaded entity INSTANCE, not the id (D-09, Pitfall 6). Read exposure / read permission
     * are NOT
     * re-checked here — they ran once per target class in the prefetch pass.
     */
    public Map<String, Object> coerceAttributes(MetaClass metaClass,
                                               Map<String, Object> attributes,
                                               Map<MetaClass, Map<UUID, Object>> prefetched) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String attributeName = entry.getKey();
            MetaProperty property = validateWritableProperty(metaClass, attributeName);
            Object rawValue = entry.getValue();
            Object coercedValue;
            if (isToOneRelationship(property)) {
                coercedValue = bindPrefetchedReference(property, rawValue, prefetched);
            } else {
                coercedValue = coerceScalarValue(property, rawValue);
            }
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
            // Single-attribute to-one coercion: route through the same two-pass batch path with
            // a one-element row so behavior (gate order, error parity, bound-instance) is
            // identical to the bulk path. No separate per-reference load survives here.
            MetaClass targetMetaClass = property.getRange().asClass();
            Map<MetaClass, Map<UUID, Object>> prefetched =
                    prefetchReferences(ownerMetaClass, List.of(Map.of(property.getName(), rawValue)));
            return bindPrefetchedReference(property, rawValue, prefetched);
        }
        return coerceScalarValue(property, rawValue);
    }

    /**
     * True for a to-one (non-collection) relationship attribute. Mirrors the to-one detection
     * in {@code coerceAttributeValue}: a class-range property whose cardinality is not "many".
     */
    private boolean isToOneRelationship(MetaProperty property) {
        if (!property.getRange().isClass()) {
            return false;
        }
        return property.getRange().getCardinality() == null
                || !property.getRange().getCardinality().isMany();
    }

    /**
     * Bind a single to-one FK attribute from the prefetched per-class map. null clears the
     * reference; a present id binds the LOADED ENTITY INSTANCE (D-09, Pitfall 6); an absent id
     * (genuinely missing OR row-level-security-filtered) throws
     * {@link MutationErrorTranslator#notFound(MetaClass, String)}, which rolls back the whole
     * all-or-nothing batch (no per-row index is reported, see {@link #prefetchReferences}).
     */
    private Object bindPrefetchedReference(MetaProperty property,
                                           Object rawValue,
                                           Map<MetaClass, Map<UUID, Object>> prefetched) {
        if (rawValue == null) {
            return null;
        }
        MetaClass targetMetaClass = property.getRange().asClass();
        UUID targetId = requireUuidId(
                toolEntityResolver.parseEntityId(rawValue.toString(), targetMetaClass));
        Map<UUID, Object> byId = prefetched.get(targetMetaClass);
        Object instance = byId == null ? null : byId.get(targetId);
        if (instance == null) {
            throw mutationErrorTranslator.notFound(targetMetaClass, rawValue.toString());
        }
        return instance;
    }

    /**
     * Coerce a scalar/datatype/enum value via {@link FilterLiteralValueConverter}.
     *
     * <p>Rule 1 fix (Plan 11-10 Task 3): scalar null is a legitimate optional-field clear in
     * mutation context (MUT-03 / Plan 11-03 contract). {@code FilterLiteralValueConverter} is a
     * structured-filter converter and rejects null with "value must not be null"; that semantic
     * does not apply to attribute writes where null = clear-to-null.
     */
    private Object coerceScalarValue(MetaProperty property, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
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
