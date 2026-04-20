package com.vn.agent.orchestration;

import io.jmix.core.security.CurrentAuthentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 */
@Component
public class BaselineContextProvider {

    private final CurrentAuthentication currentAuthentication;

    public BaselineContextProvider(CurrentAuthentication currentAuthentication) {
        this.currentAuthentication = currentAuthentication;
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
            sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue()));
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
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Best-effort extraction of a stable user key. Jmix's {@code User} entity exposes a UUID via
     * a {@code getKey()} accessor on its base class; we reflectively attempt it to avoid a hard
     * compile dep on {@code jmix-security}'s user package shape.
     */
    private static Object extractUserKey(UserDetails user) {
        try {
            var m = user.getClass().getMethod("getKey");
            return m.invoke(user);
        } catch (Exception ignore) {
            return user.getUsername();
        }
    }
}
