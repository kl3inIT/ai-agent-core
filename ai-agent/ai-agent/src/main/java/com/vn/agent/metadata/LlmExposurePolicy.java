package com.vn.agent.metadata;

import com.vn.agent.orchestration.RunContext;
import com.vn.agent.tools.AiAgentToolsProperties;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * LLM visibility boundary. Wraps {@link CurrentUserSchemaAccess} (delegate-and-narrow):
 * composition is {@code userVisible AND NOT hidden}. The {@code hidden} set is a pure
 * schema-visibility denylist sourced from {@link AiAgentToolsProperties} — it never widens
 * access, only removes entities from the LLM schema surface; the {@link AccessManager} /
 * constrained {@code DataManager} remain authoritative for real entity-, attribute-, and
 * row-level access (D-08).
 *
 * <p>The denylist is user-INDEPENDENT (config-derived, not role/user-specific) so it is
 * shared across every call site and every external {@link #getDenylistedEntityNames()}
 * caller.</p>
 *
 * <p>Per-turn read-through (PERF-01 / D-08): the {@code canReadEntity} read verdict, the
 * {@code canCreate}/{@code canUpdate} CRUD verdicts, and {@code getReadableSchema()} are
 * resolved ONCE per turn via
 * {@link RunContext#perTurnMemoize(Object, java.util.function.Supplier)} and shared across
 * every tool call in that turn. Verdict keys are locale-INVARIANT (D-04). Within a turn the
 * authenticated user, role set, and registered constraints are immutable, so a cached verdict
 * can never go over-permissive mid-turn (D-08); the per-turn map is wiped each turn by
 * {@link RunContext#clear()}. The per-turn cache symbol appears ONLY here and in
 * {@link RunContext} — never in the constrained-{@code DataManager} row-data path
 * ({@code BuiltInDataTools}), which stays authoritative for row access (D-09).</p>
 */
@Component
public class LlmExposurePolicy {

    /**
     * Per-turn cache key for a CRUD/read verdict (PERF-01 / D-04). Locale-INVARIANT by
     * construction — only {@code (metaClassName, operation)} identify the verdict; operations are
     * the literals {@code "read"} / {@code "create"} / {@code "update"}. A {@code record} so its
     * {@code equals}/{@code hashCode} make distinct gates distinct keys in the per-turn map.
     */
    record CrudVerdictKey(String metaClassName, String operation) { }

    /**
     * Single sentinel key for the per-turn memoized {@link #getReadableSchema()} value. The verdict
     * portion of the schema is locale-invariant (D-04); the locale-bearing {@code agent.entities}
     * label rendering lives in {@code BaselineContextProvider} and is NOT cached here.
     */
    private static final String READABLE_SCHEMA_KEY = "readableSchema";

    private final CurrentUserSchemaAccess delegate;
    private final AiAgentToolsProperties toolsProperties;
    private final AccessManager accessManager;

    public LlmExposurePolicy(CurrentUserSchemaAccess delegate,
                             AiAgentToolsProperties toolsProperties,
                             AccessManager accessManager) {
        this.delegate = delegate;
        this.toolsProperties = toolsProperties;
        this.accessManager = accessManager;
    }

    /**
     * Returns the Jmix-readable schema with denylisted entities removed entirely (EXP-02).
     * Loads the denylist ONCE at the top — never calls per-entity DB queries in the loop
     * (avoids N-query hot-path per Pitfall #1 in RESEARCH.md).
     *
     * <p>Resolved ONCE per turn via {@link RunContext#perTurnMemoize} (PERF-01 / D-08) and shared
     * across every tool call in the turn; off-turn it recomputes-without-storing (D-02 safe miss).
     * The cached value is a DEEPLY-immutable view — both the outer map and every inner attribute
     * {@link Set} are copied with {@link Map#copyOf}/{@link Set#copyOf} (review MEDIUM) so no caller
     * can mutate the per-turn cached schema or its attribute sets.</p>
     */
    public Map<MetaClass, Set<String>> getReadableSchema() {
        return RunContext.perTurnMemoize(READABLE_SCHEMA_KEY, this::computeReadableSchema);
    }

    private Map<MetaClass, Set<String>> computeReadableSchema() {
        Set<String> denied = hiddenEntityNames();
        Map<MetaClass, Set<String>> base = delegate.getReadableSchema();
        Map<MetaClass, Set<String>> filtered = new LinkedHashMap<>(base);
        if (!denied.isEmpty()) {
            filtered.keySet().removeIf(mc -> denied.contains(mc.getName()));
        }
        // Deep-immutable defensive copy: outer map AND every inner attribute set (review MEDIUM —
        // Map.copyOf alone leaves the inner Sets mutable, letting a caller mutate cached attributes).
        Map<MetaClass, Set<String>> immutable = new LinkedHashMap<>(filtered.size());
        for (Map.Entry<MetaClass, Set<String>> entry : filtered.entrySet()) {
            immutable.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    /**
     * Per-request entity read-access check. Resolved ONCE per turn via
     * {@link RunContext#perTurnMemoize} (PERF-01 / review HIGH #4 — {@code canReadEntity} is the
     * hottest read gate with ~15 external call sites, so D-08 names it explicitly). Off-turn it
     * recomputes-without-storing (D-02 safe miss). Locale-invariant verdict key (D-04).
     */
    public boolean canReadEntity(MetaClass mc) {
        return RunContext.perTurnMemoize(
                new CrudVerdictKey(mc.getName(), "read"),
                () -> delegate.canReadEntity(mc) && !hiddenEntityNames().contains(mc.getName()));
    }

    /**
     * Pass-through in v1.1 — attribute-level rules are deferred to a later milestone.
     */
    public boolean canReadAttribute(MetaClass mc, String attrPath) {
        return delegate.canReadAttribute(mc, attrPath);
    }

    /**
     * Create-side entity gate. Returns {@code true} only when the current user has CRUD-create
     * permission AND the entity is not denylisted from the LLM surface. Consumed by
     * {@code ToolEntityResolver.resolveCreatableEntityOrThrow} (mutation tools Wave 4+).
     */
    public boolean canCreate(MetaClass mc) {
        return RunContext.perTurnMemoize(
                new CrudVerdictKey(mc.getName(), "create"),
                () -> {
                    CrudEntityContext ctx = new CrudEntityContext(mc);
                    accessManager.applyRegisteredConstraints(ctx);
                    return ctx.isCreatePermitted()
                            && !hiddenEntityNames().contains(mc.getName());
                });
    }

    /**
     * Update-side entity gate. Returns {@code true} only when the current user has CRUD-update
     * permission AND the entity is not denylisted from the LLM surface. Consumed by
     * {@code ToolEntityResolver.resolveUpdatableEntityOrThrow} (mutation tools Wave 4+).
     */
    public boolean canUpdate(MetaClass mc) {
        return RunContext.perTurnMemoize(
                new CrudVerdictKey(mc.getName(), "update"),
                () -> {
                    CrudEntityContext ctx = new CrudEntityContext(mc);
                    accessManager.applyRegisteredConstraints(ctx);
                    return ctx.isUpdatePermitted()
                            && !hiddenEntityNames().contains(mc.getName());
                });
    }

    /**
     * Compatibility alias for existing call sites; update semantics only. Mutation tools should
     * call {@link #canCreate} / {@link #canUpdate} directly for operation-specific gating.
     */
    public boolean canModify(MetaClass mc) {
        return canUpdate(mc);
    }

    /**
     * Returns the set of {@link MetaClass#getName()} strings hidden from the LLM surface.
     * Never null. Public because {@code RetrievalFilterBuilder} (cross-package, rag package)
     * consumes it for EXP-05 source_entity NOT IN filter.
     */
    public Set<String> getDenylistedEntityNames() {
        return hiddenEntityNames();
    }

    /**
     * Returns the schema-visibility denylist from {@link AiAgentToolsProperties}. Never null.
     */
    private Set<String> hiddenEntityNames() {
        return toolsProperties.resolvedHiddenEntities();
    }
}
