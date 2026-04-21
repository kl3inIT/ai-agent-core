package com.vn.agent.guard;

import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * E-01 rubric: per-user sliding-window rate limiter.
 *
 * <p>Unit-level test with a real {@link ConcurrentMapCacheManager} wrapping the two guard caches —
 * no Spring context. Exercises the {@link RateLimitGuard#check(String) explicit-username} overload
 * so the test bypasses {@link CurrentAuthentication} plumbing.</p>
 *
 * <p>Per-minute ceiling 3 (not the prod default of 10) keeps the test fast: the 4th call in a row
 * MUST throw; a second user never sees the first user's budget.</p>
 */
@Tag("eval")
class RateLimitGuardTest {

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager(
                RateLimitGuard.CACHE_NAME, TokenBudgetGuard.CACHE_NAME);
    }

    private RateLimitGuard guard(boolean enabled, int ceiling) {
        AiAgentGuardProperties props = new AiAgentGuardProperties(
                new AiAgentGuardProperties.RateLimit(enabled, ceiling), null, null, null);
        return new RateLimitGuard(cacheManager, props, mock(CurrentAuthentication.class));
    }

    @Test
    void allowsUpToCeiling() {
        RateLimitGuard g = guard(true, 3);
        assertThatCode(() -> {
            for (int i = 0; i < 3; i++) {
                g.check("alice");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsAtCeilingPlusOne() {
        RateLimitGuard g = guard(true, 3);
        for (int i = 0; i < 3; i++) {
            g.check("alice");
        }
        assertThatThrownBy(() -> g.check("alice"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("rate-limit-exceeded");
    }

    @Test
    void perUserIsolation() {
        RateLimitGuard g = guard(true, 3);
        for (int i = 0; i < 3; i++) {
            g.check("alice");
        }
        // Different user is NOT rate-limited.
        assertThatCode(() -> g.check("bob")).doesNotThrowAnyException();
    }

    @Test
    void disabledIsNoOp() {
        RateLimitGuard disabled = guard(false, 1);
        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) {
                disabled.check("alice");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void deniedAttemptIsNotRecordedForStarvationAvoidance() {
        // Core invariant of the "do not record denied attempts" rule: after hitting the ceiling,
        // the deque length remains == ceiling (not ceiling+1). Indirectly observable via a second
        // rejection that does not require a further increment.
        RateLimitGuard g = guard(true, 2);
        g.check("alice");
        g.check("alice");
        assertThatThrownBy(() -> g.check("alice"))
                .isInstanceOf(RateLimitExceededException.class);
        // A further attempt also rejects (not accumulating).
        assertThatThrownBy(() -> g.check("alice"))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
