package com.vn.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.exposure.LlmExposurePolicy;
import io.jmix.core.AccessManager;
import io.jmix.core.MessageTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-request producer of the baseline {@code agent.*} context map (D-15). NOT a
 * {@link com.vn.agent.spi.ContextContributor} SPI impl — that SPI's Javadoc forbids implementations
 * from writing the {@code agent.*} namespace; baseline is the producer of those keys.
 *
 * <p>Stateless {@code @Component} — every dependency is resolved per call via injected Jmix beans.
 * SPI {@code ContextContributor} default no-op stays in {@code SpiDefaultsAutoConfiguration}; Phase 6
 * will wire the contributor chain on top of this baseline.</p>
 *
 * <p><b>Two consumption modes:</b>
 * <ul>
 *   <li>{@link #compose(UUID)} — returns {@code Map<String, Object>} for SPI-uniform programmatic
 *       inspection (used by Phase 6 contributor chain and audit metadata).</li>
 *   <li>{@link #renderAsText(UUID)} — returns a deterministic {@code String} (keys sorted
 *       alphabetically, {@code key=value} lines joined with {@code \n}) for prompt composition.
 *       Plan 04-04 {@code DefaultChatServiceImpl} prepends this text to the per-request system
 *       prompt.</li>
 * </ul>
 * Deterministic key order is required so identical requests produce byte-identical baseline blocks
 * (cache &amp; audit prompt-hash stability).</p>
 *
 * <p><b>Phase 9 — D-01 / D-02 / D-03 (PROMPT-01, PROMPT-02, TOOL-12):</b> {@code compose} also emits
 * {@code agent.entities} (alpha-sorted {@code "name (label)"} lines, truncated at the configured
 * limit) and {@code agent.permissions} (compact deterministic JSON of CRUD + modifiable attributes
 * per readable entity). Both keys are OMITTED entirely when the schema is empty (D-01 explicit).
 *
 * <p><b>Cache-key invariant (P-8 / PROMPT-02 explicit wording):</b> the {@code agent.permissions}
 * value contains zero locale-resolved labels — only metaclass names and attribute names — so it is
 * byte-stable across locales and safe to participate in any future cache key. The
 * {@code agent.entities} value carries locale-resolved labels in the parenthesized suffix only;
 * if Phase 10+ introduces caching the cache key MUST be {@code (userId, roleSet,
 * metaclass-name-set)}, never the rendered text.</p>
 *
 * <p><b>Phase 10 substitution seam (complete):</b> the schema-source call site
 * {@code llmExposurePolicy.getReadableSchema()} now sources through the exposure policy
 * boundary (Plan 10-04). The policy delegates to the Jmix-permission source of truth and
 * additionally narrows the result by removing admin-denylisted entities. Call shape and
 * render shape are unchanged from Phase 9.</p>
 */
@Component
public class BaselineContextProvider {

    private static final Logger log = LoggerFactory.getLogger(BaselineContextProvider.class);

    private final CurrentAuthentication currentAuthentication;
    private final LlmExposurePolicy llmExposurePolicy;
    private final AccessManager accessManager;
    private final MessageTools messageTools;
    private final ObjectMapper objectMapper;
    private final AiAgentPromptProperties promptProperties;

    public BaselineContextProvider(CurrentAuthentication currentAuthentication,
                                   LlmExposurePolicy llmExposurePolicy,
                                   AccessManager accessManager,
                                   MessageTools messageTools,
                                   ObjectMapper objectMapper,
                                   AiAgentPromptProperties promptProperties) {
        this.currentAuthentication = currentAuthentication;
        this.llmExposurePolicy = llmExposurePolicy;
        this.accessManager = accessManager;
        this.messageTools = messageTools;
        this.objectMapper = objectMapper;
        this.promptProperties = promptProperties;
    }

    public Map<String, Object> compose(UUID conversationId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        UserDetails user = safeGetUser();
        ctx.put("agent.userId", user != null ? extractUserKey(user) : null);
        ctx.put("agent.username", user != null ? user.getUsername() : "");
        ctx.put("agent.roles", user != null ? rolesOf(user) : Set.of());
        Locale locale = safeGetLocale();
        ctx.put("agent.locale", locale != null ? locale.toString() : Locale.ROOT.toString());
        ctx.put("agent.conversationId", conversationId != null ? conversationId.toString() : null);

        // Phase 9 D-01 / D-02 / D-03: agent.entities + agent.permissions.
        // Source: LlmExposurePolicy (Plan 10-04 substituted the source — denylisted entities are
        // removed before this call returns). NEVER cached — labels are locale-sensitive and
        // resolved per render (P-8: locale labels are NOT in any cache key).
        Map<MetaClass, Set<String>> readableSchema = llmExposurePolicy.getReadableSchema();
        List<MetaClass> visibleEntities = visibleEntities(readableSchema);
        if (!visibleEntities.isEmpty()) {
            ctx.put("agent.entities", renderEntitiesBlock(visibleEntities, readableSchema.size()));
            String permissionsJson = renderPermissionsJson(visibleEntities, readableSchema);
            if (permissionsJson != null) {
                ctx.put("agent.permissions", permissionsJson);
            }
        }
        return ctx;
    }

    /**
     * Render the baseline context as deterministic newline-joined {@code key=value} lines, sorted
     * alphabetically by key. Consumed by {@code DefaultChatServiceImpl} (Plan 04-04) as the first
     * segment of the per-request system prompt. Null values render as the literal string
     * {@code "null"}; collection/Locale values use their {@code toString()}.
     */
    public String renderAsText(UUID conversationId) {
        Map<String, Object> sorted = new TreeMap<>(compose(conversationId));
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private UserDetails safeGetUser() {
        try {
            return currentAuthentication.getUser();
        } catch (RuntimeException anonymous) {
            return null;
        }
    }

    private Locale safeGetLocale() {
        try {
            return currentAuthentication.getLocale();
        } catch (RuntimeException anonymous) {
            return null;
        }
    }

    private static Set<String> rolesOf(UserDetails user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Best-effort extraction of a stable user key. Jmix's {@code User} entity exposes a UUID via
     * a {@code getKey()} accessor on its base class; we reflectively attempt it to avoid a hard
     * compile dep on {@code jmix-security}'s user package shape.
     *
     * <p>LO-04: only accept {@link UUID} or {@link String} return values. If a host swaps
     * {@link UserDetails} for a class whose {@code getKey()} returns something else (e.g. an AES
     * key, an internal handle), we must NOT publish it into the {@code agent.userId} slot — that
     * value flows into the LLM-facing system prompt.
     */
    private static Object extractUserKey(UserDetails user) {
        try {
            var method = user.getClass().getMethod("getKey");
            Object result = method.invoke(user);
            if (result instanceof UUID || result instanceof String) {
                return result;
            }
            log.debug("getKey() returned non-UUID/non-String type {} on user class {}; falling back to username",
                    result == null ? "null" : result.getClass().getName(),
                    user.getClass().getName());
        } catch (NoSuchMethodException noKeyMethod) {
            // Expected for non-Jmix UserDetails implementations.
        } catch (ReflectiveOperationException | RuntimeException keyFailure) {
            log.debug("getKey() invocation failed on {}; falling back to username",
                    user.getClass().getName(), keyFailure);
        }
        return user.getUsername();
    }

    // --- Phase 9 D-01 / D-02 / D-03 helpers --- //

    /**
     * D-01 / D-03: alpha-sorted (by {@link MetaClass#getName()}) entity list, truncated at the
     * configured inventory limit. Both {@code agent.entities} and {@code agent.permissions} are
     * built from the SAME capped list so a denied/hidden tail entity never leaks via permissions.
     */
    private List<MetaClass> visibleEntities(Map<MetaClass, Set<String>> readableSchema) {
        int limit = promptProperties.resolvedEntityInventoryLimit();
        List<MetaClass> sorted = new ArrayList<>(readableSchema.keySet());
        sorted.sort(Comparator.comparing(MetaClass::getName));
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /**
     * D-01 / D-03: alphabetical-by-name list of {@code "name (label)"} lines, joined with
     * {@code \n}. When the readable schema is larger than the inventory limit, append the
     * verbatim D-03 truncation hint line as the final line of the rendered block.
     */
    private String renderEntitiesBlock(List<MetaClass> visibleEntities, int totalReadableEntityCount) {
        int limit = promptProperties.resolvedEntityInventoryLimit();
        boolean truncated = totalReadableEntityCount > limit;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (MetaClass mc : visibleEntities) {
            if (!first) sb.append('\n');
            sb.append(mc.getName()).append(" (").append(messageTools.getEntityCaption(mc)).append(')');
            first = false;
        }
        if (truncated) {
            sb.append('\n').append("... (truncated, call list_entities for full list)");
        }
        return sb.toString();
    }

    /**
     * D-02: compact JSON keyed by entity name, alpha-sorted (via {@link TreeMap}). Each value is
     * a {@link LinkedHashMap} with key order {@code r, u, c, d, modifiable} so Jackson emits keys
     * in that order. CRUD bits are ints (1/0). Entries where ALL CRUD bits are 0 are omitted
     * (D-02). {@code modifiable[]} lists per-attribute names that pass
     * {@link EntityAttributeContext#canModify()}, alpha-sorted.
     *
     * <p><b>Locale-free:</b> this string contains zero locale-resolved labels — only entity
     * metaclass names and attribute names. Cache-key safe (P-8).
     *
     * @return JSON string, or {@code null} if no entity has any CRUD bit set (degenerate edge case)
     */
    private String renderPermissionsJson(List<MetaClass> visibleEntities,
                                         Map<MetaClass, Set<String>> readableSchema) {
        Map<String, Object> permissions = new TreeMap<>();
        for (MetaClass mc : visibleEntities) {
            CrudEntityContext crud = new CrudEntityContext(mc);
            accessManager.applyRegisteredConstraints(crud);
            int r = crud.isReadPermitted() ? 1 : 0;
            int u = crud.isUpdatePermitted() ? 1 : 0;
            int c = crud.isCreatePermitted() ? 1 : 0;
            int d = crud.isDeletePermitted() ? 1 : 0;
            if (r == 0 && u == 0 && c == 0 && d == 0) {
                continue; // D-02: skip entries with no CRUD bits set
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("r", r);
            entry.put("u", u);
            entry.put("c", c);
            entry.put("d", d);
            entry.put("modifiable", modifiableAttributesOf(mc, readableSchema.get(mc)));
            permissions.put(mc.getName(), entry);
        }
        if (permissions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(permissions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize agent.permissions", e);
        }
    }

    /**
     * D-02: per-attribute {@code modifiable} list — names where
     * {@link EntityAttributeContext#canModify()} returns {@code true}, alpha-sorted. Bounded to
     * the {@code readableAttributeNames} set (the user has READ on these; we test MODIFY on the
     * same subset).
     */
    private List<String> modifiableAttributesOf(MetaClass metaClass, Set<String> readableAttributeNames) {
        if (readableAttributeNames == null || readableAttributeNames.isEmpty()) {
            return List.of();
        }
        List<String> modifiable = new ArrayList<>();
        for (String attributeName : new TreeSet<>(readableAttributeNames)) {
            EntityAttributeContext attr = new EntityAttributeContext(metaClass, attributeName);
            accessManager.applyRegisteredConstraints(attr);
            if (attr.canModify()) {
                modifiable.add(attributeName);
            }
        }
        return modifiable; // already alpha-sorted via TreeSet iteration
    }
}
