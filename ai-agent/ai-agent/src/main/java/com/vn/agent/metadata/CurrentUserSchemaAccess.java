package com.vn.agent.metadata;

import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.entity.annotation.SystemLevel;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * TOOL-02 per-request read-access view of the Jmix metamodel for the current user.
 * Stateless {@link Component} — {@code AccessManager} resolves the caller's authentication per
 * call, so there is no class-level cache (per threat model T-03-01 + Open-Question #6
 * recommendation).
 *
 * <p>Returns a {@code Map<MetaClass, Set<String>>} whose keys are entities visible to the
 * current user and whose values are the subset of attribute names the user can read. Tools
 * and the formatter compose display labels / type strings live off {@link MetaClass} via
 * {@code MessageTools} at call time (locale-sensitive).</p>
 */
@Component
public class CurrentUserSchemaAccess {

    private final AccessManager accessManager;
    private final Metadata metadata;

    public CurrentUserSchemaAccess(AccessManager accessManager, Metadata metadata) {
        this.accessManager = accessManager;
        this.metadata = metadata;
    }

    /**
     * Build a fresh map of {@link MetaClass} → readable attribute names, filtered by the
     * current user's {@link AccessManager} view. Never cached (TOOL-02). Excludes
     * {@code @SystemLevel} entities.
     */
    public Map<MetaClass, Set<String>> getReadableSchema() {
        Map<MetaClass, Set<String>> readableAttributeNamesByEntity = new LinkedHashMap<>();
        for (MetaClass metaClass : metadata.getSession().getClasses()) {
            if (isSystemLevelEntity(metaClass)) {
                continue;
            }
            if (!canReadEntity(metaClass)) {
                continue;
            }
            readableAttributeNamesByEntity.put(metaClass, collectReadableAttributeNames(metaClass));
        }
        return readableAttributeNamesByEntity;
    }

    /**
     * Per-request attribute-path access check. Consumed by
     * {@code StructuredFilterConditionMapper}
     * during depth-cap path validation (D-08). {@code attrPath} may be a dotted path (e.g.
     * {@code "customer.region.code"}) — {@code EntityAttributeContext} accepts property paths.
     */
    public boolean canReadAttribute(MetaClass metaClass, String attributePath) {
        EntityAttributeContext attributeAccessContext =
                new EntityAttributeContext(metaClass, attributePath);
        accessManager.applyRegisteredConstraints(attributeAccessContext);
        return attributeAccessContext.canView();
    }

    /**
     * Per-request entity read-access check. Used by the filter mapper when walking relationship
     * hops and by {@code BuiltInDataTools.resolveReadableEntityOrThrow(...)}.
     */
    public boolean canReadEntity(MetaClass metaClass) {
        CrudEntityContext entityAccessContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(entityAccessContext);
        return entityAccessContext.isReadPermitted();
    }

    private boolean isSystemLevelEntity(MetaClass metaClass) {
        return metaClass.getJavaClass().isAnnotationPresent(SystemLevel.class);
    }

    private Set<String> collectReadableAttributeNames(MetaClass metaClass) {
        Set<String> readableAttributeNames = new LinkedHashSet<>();
        for (MetaProperty metaProperty : metaClass.getProperties()) {
            if (canReadAttribute(metaClass, metaProperty.getName())) {
                readableAttributeNames.add(metaProperty.getName());
            }
        }
        return readableAttributeNames;
    }
}
