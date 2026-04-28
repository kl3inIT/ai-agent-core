package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 10 LLM visibility boundary. Wraps {@link CurrentUserSchemaAccess} (delegate-and-narrow):
 * composition is {@code userVisible AND NOT excluded}. The built-in AI internal entities are
 * always excluded; admin rules can only narrow visibility further, never widen it.
 *
 * <p>Stateless {@link Component} — reads rules per-call via {@link LlmExposureRuleRepository}
 * (no cache, per D-14). Expected rule count &lt;50; per-call latency is negligible compared to
 * the LLM round-trip.</p>
 *
 * <p>Call sites that previously injected {@link CurrentUserSchemaAccess} switch to this class
 * mechanically (D-03): {@code BuiltInDataTools}, {@code BaselineContextProvider},
 * {@code FetchPlanIntersector}.</p>
 */
@Component
public class LlmExposurePolicy {

    private final CurrentUserSchemaAccess delegate;
    private final LlmExposureRuleRepository ruleRepository;
    private final AccessManager accessManager;

    public LlmExposurePolicy(CurrentUserSchemaAccess delegate,
                             LlmExposureRuleRepository ruleRepository,
                             AccessManager accessManager) {
        this.delegate = delegate;
        this.ruleRepository = ruleRepository;
        this.accessManager = accessManager;
    }

    /**
     * Returns the Jmix-readable schema with denylisted entities removed entirely (EXP-02).
     * Loads the denylist ONCE at the top of this method — never calls per-entity DB queries
     * in the loop (avoids N-query hot-path per Pitfall #1 in RESEARCH.md).
     */
    public Map<MetaClass, Set<String>> getReadableSchema() {
        Set<String> denied = hiddenEntityNames();
        Map<MetaClass, Set<String>> base = delegate.getReadableSchema();
        if (denied.isEmpty()) {
            return base;
        }
        Map<MetaClass, Set<String>> result = new LinkedHashMap<>(base);
        result.keySet().removeIf(mc -> denied.contains(mc.getName()));
        return result;
    }

    /**
     * Per-request entity read-access check. Fetches its own denylist copy (this method is
     * called alone from BuiltInDataTools, not inside a loop over all entities).
     */
    public boolean canReadEntity(MetaClass mc) {
        return delegate.canReadEntity(mc)
                && !hiddenEntityNames().contains(mc.getName());
    }

    /**
     * Pass-through in v1.1 — attribute-level rules are deferred to a later milestone.
     * The indirection is in place for the future milestone where AiExposureRule gains
     * an attributePath field.
     */
    public boolean canReadAttribute(MetaClass mc, String attrPath) {
        return delegate.canReadAttribute(mc, attrPath);
    }

    /**
     * Phase 11 MUT-09: create-side entity gate. Returns {@code true} only when the current
     * user has CRUD-create permission AND the entity is not denylisted from the LLM surface.
     * Consumed by {@code ToolEntityResolver.resolveCreatableEntityOrThrow} (mutation tools
     * Wave 4+).
     */
    public boolean canCreate(MetaClass mc) {
        CrudEntityContext ctx = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(ctx);
        return ctx.isCreatePermitted()
                && !hiddenEntityNames().contains(mc.getName());
    }

    /**
     * Phase 11 MUT-09: update-side entity gate. Returns {@code true} only when the current
     * user has CRUD-update permission AND the entity is not denylisted from the LLM surface.
     * Consumed by {@code ToolEntityResolver.resolveUpdatableEntityOrThrow} (mutation tools
     * Wave 4+).
     */
    public boolean canUpdate(MetaClass mc) {
        CrudEntityContext ctx = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(ctx);
        return ctx.isUpdatePermitted()
                && !hiddenEntityNames().contains(mc.getName());
    }

    /**
     * Compatibility alias for existing Phase 10 call sites; update semantics only.
     * Phase 11 mutation tools should call {@link #canCreate} / {@link #canUpdate} directly
     * for operation-specific gating (HIGH review feedback addressed in Plan 11-04).
     */
    public boolean canModify(MetaClass mc) {
        return canUpdate(mc);
    }

    /**
     * Returns the set of {@link MetaClass#getName()} strings hidden from the LLM surface:
     * built-in AI internal entities plus enabled admin denylist rules. Never null.
     * Public because {@code RetrievalFilterBuilder} (cross-package, rag package) consumes it
     * for EXP-05 source_entity NOT IN filter. Do NOT cache — see D-14.
     */
    public Set<String> getDenylistedEntityNames() {
        return hiddenEntityNames();
    }

    private Set<String> hiddenEntityNames() {
        Set<String> hidden = new LinkedHashSet<>(AiInternalEntityNames.all());
        hidden.addAll(ruleRepository.findEnabledExcludedEntityNames());
        return hidden;
    }
}
