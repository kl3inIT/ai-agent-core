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
 * TOOL-02 per-request effective-schema filter. Stateless {@link Component} — {@code AccessManager}
 * resolves the caller's authentication per call, so there is no class-level cache (per threat
 * model T-03-01 + Open-Question #6 recommendation).
 *
 * <p>Returns a {@code Map<MetaClass, Set<String>>} whose keys are entities visible to the
 * current user and whose values are the subset of attribute names the user can read. Tools
 * and the formatter compose display labels / type strings live off {@link MetaClass} via
 * {@code MessageTools} at call time (locale-sensitive).</p>
 */
@Component
public class EffectiveSchemaComputer {

    private final AccessManager accessManager;
    private final Metadata metadata;

    public EffectiveSchemaComputer(AccessManager accessManager, Metadata metadata) {
        this.accessManager = accessManager;
        this.metadata = metadata;
    }

    /**
     * Build a fresh map of {@link MetaClass} → readable attribute names, filtered by the
     * current user's {@link AccessManager} view. Never cached (TOOL-02). Excludes
     * {@code @SystemLevel} entities (same policy as the scanner).
     */
    public Map<MetaClass, Set<String>> forCurrentUser() {
        Map<MetaClass, Set<String>> out = new LinkedHashMap<>();
        for (MetaClass mc : metadata.getSession().getClasses()) {
            if (mc.getJavaClass().isAnnotationPresent(SystemLevel.class)) {
                continue;
            }
            CrudEntityContext ec = new CrudEntityContext(mc);
            accessManager.applyRegisteredConstraints(ec);
            if (!ec.isReadPermitted()) {
                continue;
            }
            Set<String> visible = new LinkedHashSet<>();
            for (MetaProperty mp : mc.getProperties()) {
                EntityAttributeContext ac = new EntityAttributeContext(mc, mp.getName());
                accessManager.applyRegisteredConstraints(ac);
                if (ac.canView()) {
                    visible.add(mp.getName());
                }
            }
            out.put(mc, visible);
        }
        return out;
    }

    /**
     * Per-request attribute-path access check. Consumed by {@code FilterDslMapper}
     * during depth-cap path validation (D-08). {@code attrPath} may be a dotted path (e.g.
     * {@code "customer.region.code"}) — {@code EntityAttributeContext} accepts property paths.
     */
    public boolean canReadAttribute(MetaClass mc, String attrPath) {
        EntityAttributeContext ac = new EntityAttributeContext(mc, attrPath);
        accessManager.applyRegisteredConstraints(ac);
        return ac.canView();
    }

    /**
     * Per-request entity read-access check. Used by the filter mapper when walking relationship
     * hops and by {@code BuiltInDataTools.resolveOrError(...)}.
     */
    public boolean canReadEntity(MetaClass mc) {
        CrudEntityContext ec = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(ec);
        return ec.isReadPermitted();
    }
}
