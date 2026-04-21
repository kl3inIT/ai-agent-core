package com.vn.agent.guard;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Per-conversation rolling-token circuit breaker (GUARD-02, D-14).
 *
 * <p>Stores a {@code Long} running total per conversation id inside the Spring cache named
 * {@code "ai-agent.tokenBreaker"} (D-12). {@link #check(UUID)} throws
 * {@link TokenBudgetExhaustedException} when the accumulated total meets or exceeds the configured
 * ceiling; {@link #accumulate(UUID, long)} is called post-response by the orchestration layer to
 * add the usage the LLM reported for the just-completed turn.</p>
 *
 * <p>When {@code jmix.ai-agent.guard.token-breaker.enabled=false} all three methods are no-ops.
 * {@link #reset(UUID)} is exposed for explicit conversation-budget resets (operator tooling,
 * tests) — it is NOT invoked automatically.</p>
 *
 * <p>All public methods are {@code synchronized} on the guard instance to keep the
 * read-modify-write pair atomic against the default {@link org.springframework.cache.concurrent.ConcurrentMapCacheManager}
 * (whose {@code put} is thread-safe but a get-compute-put cycle is not).</p>
 */
@Component
public class TokenBudgetGuard {

    /** Cache name mandated by D-12 — created by the autoconfig when no host CacheManager is present. */
    public static final String CACHE_NAME = "ai-agent.tokenBreaker";

    private final CacheManager cacheManager;
    private final AiAgentGuardProperties props;

    public TokenBudgetGuard(CacheManager cacheManager, AiAgentGuardProperties props) {
        this.cacheManager = cacheManager;
        this.props = props;
    }

    /**
     * Pre-turn gate: if the running total for {@code conversationId} has reached the configured
     * ceiling, raise. Null conversation ids (first turn before the gateway has issued one) are
     * skipped because the breaker is strictly a per-conversation concept.
     */
    public synchronized void check(UUID conversationId) {
        if (!props.tokenBreakerEnabled() || conversationId == null) {
            return;
        }
        long total = currentTotal(conversationId);
        long ceiling = props.resolvedTokenCeiling();
        if (total >= ceiling) {
            throw new TokenBudgetExhaustedException(ceiling);
        }
    }

    /**
     * Post-turn accumulator: adds {@code tokensUsed} (prompt + completion from the LLM usage block)
     * onto the conversation's running total. No-op when the breaker is disabled, the conversation
     * id is null, or the tokensUsed value is non-positive.
     */
    public synchronized void accumulate(UUID conversationId, long tokensUsed) {
        if (!props.tokenBreakerEnabled() || conversationId == null || tokensUsed <= 0L) {
            return;
        }
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return;
        }
        long newTotal = currentTotal(conversationId) + tokensUsed;
        cache.put(conversationId, newTotal);
    }

    /**
     * Drops the cached total for {@code conversationId}. Test hook + operator-tooling override;
     * not invoked automatically by Plan 04.
     */
    public synchronized void reset(UUID conversationId) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null && conversationId != null) {
            cache.evict(conversationId);
        }
    }

    private long currentTotal(UUID conversationId) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return 0L;
        }
        Long stored = cache.get(conversationId, Long.class);
        return stored == null ? 0L : stored;
    }
}
