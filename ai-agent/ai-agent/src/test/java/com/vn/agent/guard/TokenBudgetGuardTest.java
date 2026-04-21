package com.vn.agent.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E-02 rubric: per-conversation rolling token circuit breaker.
 *
 * <p>Correctness — not timing — is what this test asserts. A 10-thread latched burst
 * accumulates a deterministic total into the same conversation id; the post-join state
 * must equal the sum. The guard's {@code synchronized} blocks plus the atomic
 * read-modify-write wrapper around the cache ensure no updates are lost.</p>
 */
@Tag("eval")
class TokenBudgetGuardTest {

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager(
                RateLimitGuard.CACHE_NAME, TokenBudgetGuard.CACHE_NAME);
    }

    private TokenBudgetGuard guard(boolean enabled, long ceiling) {
        AiAgentGuardProperties props = new AiAgentGuardProperties(
                null, new AiAgentGuardProperties.TokenBreaker(enabled, ceiling), null, null);
        return new TokenBudgetGuard(cacheManager, props);
    }

    @Test
    void checkPassesWhenBelowCeiling() {
        TokenBudgetGuard g = guard(true, 1000L);
        UUID convId = UUID.randomUUID();
        g.accumulate(convId, 500L);
        assertThatCode(() -> g.check(convId)).doesNotThrowAnyException();
    }

    @Test
    void checkThrowsWhenAtOrAboveCeiling() {
        TokenBudgetGuard g = guard(true, 1000L);
        UUID convId = UUID.randomUUID();
        g.accumulate(convId, 1000L);
        assertThatThrownBy(() -> g.check(convId))
                .isInstanceOf(TokenBudgetExhaustedException.class)
                .hasMessage("token-budget-exhausted");
    }

    @Test
    void disabledIsNoOp() {
        TokenBudgetGuard g = guard(false, 1L);
        UUID convId = UUID.randomUUID();
        // Accumulate far above a disabled ceiling; check() must still pass.
        g.accumulate(convId, 10_000L);
        assertThatCode(() -> g.check(convId)).doesNotThrowAnyException();
    }

    @Test
    void nullConversationIsNoOp() {
        TokenBudgetGuard g = guard(true, 1L);
        g.accumulate(null, 100L);
        assertThatCode(() -> g.check(null)).doesNotThrowAnyException();
    }

    @Test
    void resetDropsAccumulatedTotal() {
        TokenBudgetGuard g = guard(true, 1000L);
        UUID convId = UUID.randomUUID();
        g.accumulate(convId, 1000L);
        assertThatThrownBy(() -> g.check(convId))
                .isInstanceOf(TokenBudgetExhaustedException.class);
        g.reset(convId);
        assertThatCode(() -> g.check(convId)).doesNotThrowAnyException();
    }

    /**
     * Concurrency invariant: 10 threads × 100 accumulations of 1 token each ⇒ total = 1000,
     * which is exactly the ceiling so {@code check} must throw. This fires after
     * {@code CountDownLatch} synchronised start and a bounded {@code done.await} — the assertion
     * is position-independent (correctness, not timing) per T-06-30.
     */
    @Test
    void concurrentAccumulateIsConsistent() throws Exception {
        TokenBudgetGuard g = guard(true, 1000L);
        UUID convId = UUID.randomUUID();
        int threads = 10;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            g.accumulate(convId, 1L);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS))
                    .as("All 10 accumulator threads should finish within 10s").isTrue();
        } finally {
            pool.shutdownNow();
        }
        // After join: running total should be exactly threads*perThread = 1000 which equals the
        // ceiling, so check() must throw. If updates were lost the total would be < 1000 and
        // check() would erroneously pass.
        assertThatThrownBy(() -> g.check(convId))
                .isInstanceOf(TokenBudgetExhaustedException.class);
    }
}
