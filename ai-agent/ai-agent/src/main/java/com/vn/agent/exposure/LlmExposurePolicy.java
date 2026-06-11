package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 10 LLM visibility boundary. Wraps {@link CurrentUserSchemaAccess} (delegate-and-narrow):
 * composition is {@code userVisible AND NOT excluded}. The built-in AI internal entities are
 * always excluded; admin rules can only narrow visibility further, never widen it.
 *
 * <p>{@link Component} with an app-wide denylist memo (PERF-02). The exposure denylist (built-in
 * AI internal entities plus enabled admin {@code EXCLUDE} rules) is computed ONCE and reused
 * across every internal call site and every external {@link #getDenylistedEntityNames()} caller
 * until an {@link LlmExposureChangedEvent} fires; {@link #onExposureChanged} clears the memo so an
 * admin denylist edit is visible on the next turn. The denylist is user-INDEPENDENT
 * (exposure-derived, not role/user-specific) so app-wide reuse is correct by design (D-08); the
 * {@link AccessManager} / constrained {@code DataManager} remain authoritative for row-level
 * access. The cached value is an IMMUTABLE view (D-05/T-18-04) so no caller can mutate or poison
 * the shared cache.</p>
 *
 * <p>The memo lives INSIDE the bean (no Spring proxy boundary) so every self-invocation and every
 * external caller transparently hits it. {@code @Cacheable}/{@code @CacheEvict} are FORBIDDEN here
 * (D-07): {@link #hiddenEntityNames()} is a private self-invoked method, so a Spring caching proxy
 * would intercept nothing on the hot internal paths. This class is a CONSUMER of
 * {@link LlmExposureChangedEvent} only — {@code AiExposureRuleEntityListener} stays the sole
 * publisher.</p>
 *
 * <p>Call sites that previously injected {@link CurrentUserSchemaAccess} switch to this class
 * mechanically (D-03): {@code BuiltInDataTools}, {@code BaselineContextProvider},
 * {@code FetchPlanIntersector}.</p>
 *
 * <p>Phase 18 (PERF-01 / D-08) adds a per-turn read-through on top of the app-wide denylist memo:
 * the {@code canReadEntity} read verdict, the {@code canCreate}/{@code canUpdate} CRUD verdicts, and
 * {@code getReadableSchema()} are resolved ONCE per turn via
 * {@link RunContext#perTurnMemoize(Object, java.util.function.Supplier)} and shared across every
 * tool call in that turn. Verdict keys are locale-INVARIANT (D-04): permissions/verdicts do not
 * depend on locale, only the entity/schema labels rendered in {@code BaselineContextProvider} do.
 * Within a turn the authenticated user, role set, and registered constraints are immutable, so a
 * cached verdict can never go over-permissive mid-turn (D-08, threat T-18-07 accepted); the per-turn
 * map is wiped each turn by {@link RunContext#clear()} and the only verdict-changing event is the
 * across-turn {@link LlmExposureChangedEvent}. The per-turn cache symbol appears ONLY here and in
 * {@link RunContext} — never in the constrained-{@code DataManager} row-data path
 * ({@code BuiltInDataTools}), which stays authoritative for row access (D-09, T-18-06).</p>
 */
@Component
public class LlmExposurePolicy {

    /**
     * Single sentinel key for the app-wide denylist memo. The denylist is user-independent so one
     * shared entry serves every caller; eviction is all-or-nothing via {@link #onExposureChanged}.
     */
    private static final String DENYLIST_KEY = "denylist";

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
    private final LlmExposureRuleRepository ruleRepository;
    private final AccessManager accessManager;

    /**
     * App-wide denylist memo (PERF-02). Single-sentinel-key map mirroring
     * {@code RelatedWriteMetadataResolver}'s {@code computeIfAbsent} idiom. The cached value is an
     * IMMUTABLE view (T-18-04); evicted on {@link LlmExposureChangedEvent} via
     * {@link #onExposureChanged}.
     */
    private final Map<String, Set<String>> denylistCache = new ConcurrentHashMap<>();

    public LlmExposurePolicy(CurrentUserSchemaAccess delegate,
                             LlmExposureRuleRepository ruleRepository,
                             AccessManager accessManager) {
        this.delegate = delegate;
        this.ruleRepository = ruleRepository;
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
     * Phase 11 MUT-09: update-side entity gate. Returns {@code true} only when the current
     * user has CRUD-update permission AND the entity is not denylisted from the LLM surface.
     * Consumed by {@code ToolEntityResolver.resolveUpdatableEntityOrThrow} (mutation tools
     * Wave 4+).
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
     * for EXP-05 source_entity NOT IN filter.
     *
     * <p>The returned set is the shared, app-wide memoized value (PERF-02) and is an IMMUTABLE
     * view — callers must NOT mutate it (doing so would throw {@link UnsupportedOperationException}
     * and, if it succeeded, would poison the cache for every other caller, T-18-04).</p>
     */
    public Set<String> getDenylistedEntityNames() {
        return hiddenEntityNames();
    }

    /**
     * Returns the memoized denylist (built-in AI internal entities plus enabled admin
     * {@code EXCLUDE} rules) as an IMMUTABLE view. Computed ONCE app-wide via
     * {@code computeIfAbsent} on the single {@link #DENYLIST_KEY}, reused across every internal
     * call site and external caller until {@link #onExposureChanged} clears the memo. Never null.
     *
     * <p>The cached value MUST be unmodifiable (T-18-04): the same instance is handed to ALL
     * callers, so an immutable view prevents any caller from over-/under-exposing entities by
     * mutating the shared denylist. Iteration order (built-ins first, then admin rules) is
     * preserved via {@link LinkedHashSet} wrapped in {@link Collections#unmodifiableSet}.</p>
     */
    private Set<String> hiddenEntityNames() {
        return denylistCache.computeIfAbsent(DENYLIST_KEY, key -> {
            Set<String> hidden = new LinkedHashSet<>(AiInternalEntityNames.all());
            hidden.addAll(ruleRepository.findEnabledExcludedEntityNames());
            return Collections.unmodifiableSet(hidden);
        });
    }

    /**
     * Evicts the app-wide denylist memo when an {@link LlmExposureChangedEvent} fires (PERF-02 /
     * D-05). Mirrors the {@code AiExposureRuleEntityListener} / {@code RelatedWriteMetadataResolver}
     * shape: this class is a CONSUMER only and never publishes the event. After eviction the next
     * {@link #hiddenEntityNames()} call refetches exactly once, so an admin denylist edit is
     * visible on the next turn (T-18-01).
     */
    @EventListener(LlmExposureChangedEvent.class)
    void onExposureChanged(LlmExposureChangedEvent event) {
        denylistCache.clear();
    }
}
