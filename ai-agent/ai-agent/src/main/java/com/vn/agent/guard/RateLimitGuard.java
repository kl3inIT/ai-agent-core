package com.vn.agent.guard;

import io.jmix.core.security.CurrentAuthentication;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-minute sliding-window rate limiter keyed by username (GUARD-01, D-13).
 *
 * <p>Stores a per-user {@code Deque<Long>} of epoch-millisecond timestamps inside the Spring cache
 * named {@code "ai-agent.rateLimit"} (D-12). On each {@link #check()} call, entries older than the
 * 60-second window are evicted; when the remaining deque size would meet or exceed the configured
 * requests-per-minute ceiling, a {@link RateLimitExceededException} is raised carrying the ceiling
 * on an audit-only side field (the {@code super()} message remains the stable key
 * {@code "rate-limit-exceeded"} — D-10 opacity).</p>
 *
 * <p>When {@code jmix.ai-agent.guard.rate-limit.enabled=false} both {@code check()} overloads are
 * no-ops. A denied attempt does NOT record itself in the deque — this avoids starvation after the
 * window cools down (a blocked user still gets a fair next-minute budget).</p>
 *
 * <p><b>Concurrency note:</b> method-level {@code synchronized} puts all callers on one instance
 * lock. This is acceptable for the default {@link org.springframework.cache.concurrent.ConcurrentMapCacheManager};
 * hosts swapping to Redis or another shared cache backend should provide a replacement guard that
 * uses a striped lock or atomic cache operations. Recorded as a Plan 06-03 follow-up.</p>
 */
@Component
public class RateLimitGuard {

    /** Cache name mandated by D-12 — created by the autoconfig when no host CacheManager is present. */
    public static final String CACHE_NAME = "ai-agent.rateLimit";

    /** 60-second sliding window. */
    private static final long WINDOW_MS = 60_000L;

    private final CacheManager cacheManager;
    private final AiAgentGuardProperties props;
    private final CurrentAuthentication currentAuthentication;

    public RateLimitGuard(CacheManager cacheManager,
                          AiAgentGuardProperties props,
                          CurrentAuthentication currentAuthentication) {
        this.cacheManager = cacheManager;
        this.props = props;
        this.currentAuthentication = currentAuthentication;
    }

    /** Convenience overload keyed by {@link CurrentAuthentication}'s username. */
    public void check() {
        if (!props.rateLimitEnabled()) {
            return;
        }
        String username;
        try {
            username = currentAuthentication.getUser().getUsername();
        } catch (RuntimeException anonymous) {
            // No authenticated principal — skip rate limiting (matches audit writer's
            // "anonymous" fallback). Production call paths route through AuditAdvisor which
            // always has an authenticated user; test paths without a principal are unblocked.
            return;
        }
        check(username);
    }

    /**
     * Core check — public so tests can feed a stable username without SecurityContext plumbing.
     *
     * @throws RateLimitExceededException when the per-minute ceiling has been reached
     */
    @SuppressWarnings("unchecked")
    public synchronized void check(String username) {
        if (!props.rateLimitEnabled() || username == null) {
            return;
        }
        int ceiling = props.resolvedRequestsPerMinute();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            // Defensive: autoconfig ensures the cache exists; host-supplied CacheManagers that
            // do not pre-register the name will return null. Fail-open rather than crash.
            return;
        }
        Deque<Long> hits = cache.get(username, Deque.class);
        long now = Instant.now().toEpochMilli();
        if (hits == null) {
            hits = new ArrayDeque<>();
        }
        // Evict entries outside the sliding window.
        while (!hits.isEmpty() && now - hits.peekFirst() > WINDOW_MS) {
            hits.pollFirst();
        }
        if (hits.size() >= ceiling) {
            // Ceiling hit — do NOT record this attempt (avoid permanent starvation after cooldown).
            throw new RateLimitExceededException(ceiling);
        }
        hits.offerLast(now);
        cache.put(username, hits);
    }
}
