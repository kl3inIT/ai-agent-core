package com.vn.agent.metadata;

import io.jmix.core.AccessManager;
import io.jmix.core.MessageTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TOOL-02 per-request effective-schema filter. Stateless {@link Component} — {@code AccessManager}
 * resolves the caller's authentication per call, so there is no class-level cache (per threat
 * model T-03-01 + Open-Question #6 recommendation).
 * <p>All LLM-facing paths in Plan 03 go through this class; the raw {@link MetamodelScanner}
 * output is never surfaced to tools.</p>
 */
@Component
public class EffectiveSchemaComputer {

    private final AccessManager accessManager;
    private final MetamodelScanner scanner;
    private final MessageTools messageTools;

    public EffectiveSchemaComputer(AccessManager accessManager,
                                   MetamodelScanner scanner,
                                   MessageTools messageTools) {
        this.accessManager = accessManager;
        this.scanner = scanner;
        this.messageTools = messageTools;
    }

    /**
     * Build a fresh {@link AiSchema} filtered by the current user's {@code AccessManager} view.
     * Per-request locale labels are resolved via {@link MessageTools} (D-04). Never cached
     * (TOOL-02).
     */
    public AiSchema forCurrentUser() {
        AiSchema raw = scanner.getRawSchema();
        Map<String, AiEntityInfo> out = new LinkedHashMap<>();

        for (Map.Entry<String, AiEntityInfo> e : raw.entities().entrySet()) {
            AiEntityInfo entity = e.getValue();
            MetaClass mc = entity.metaClass();

            CrudEntityContext ec = new CrudEntityContext(mc);
            accessManager.applyRegisteredConstraints(ec);
            if (!ec.isReadPermitted()) {
                continue;
            }

            List<AiAttributeInfo> visibleAttrs = new ArrayList<>(entity.attributes().size());
            for (AiAttributeInfo attr : entity.attributes()) {
                EntityAttributeContext ac = new EntityAttributeContext(mc, attr.name());
                accessManager.applyRegisteredConstraints(ac);
                if (!ac.canView()) {
                    continue;
                }
                String attrLabel = null;
                MetaProperty mp = mc.findProperty(attr.name());
                if (mp != null) {
                    attrLabel = messageTools.getPropertyCaption(mp);
                }
                visibleAttrs.add(attr.withLocalizedLabel(attrLabel));
            }

            String entityLabel = messageTools.getEntityCaption(mc);
            out.put(e.getKey(),
                    entity.withAttributes(visibleAttrs).withLocalizedLabel(entityLabel));
        }

        return new AiSchema(out);
    }

    /**
     * Per-request attribute-path access check. Consumed by Plan 02's {@code FilterDslMapper}
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
     * hops and by {@code BuiltInDataTools.resolveOrError(...)} in Plan 03.
     */
    public boolean canReadEntity(MetaClass mc) {
        CrudEntityContext ec = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(ec);
        return ec.isReadPermitted();
    }
}
