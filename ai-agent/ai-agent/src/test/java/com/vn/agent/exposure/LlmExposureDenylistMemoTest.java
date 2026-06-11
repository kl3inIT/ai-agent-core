package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import io.jmix.core.AccessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-02 call-count proxy for the app-wide denylist memo on {@link LlmExposurePolicy}.
 *
 * <p>Pure JUnit 5 + Mockito (no {@code @SpringBootTest}) — sidesteps the pre-existing Phase 11/13
 * ai-agent module Spring-context boot regression and asserts the memoization contract directly:
 * the agentstore denylist SELECT ({@link LlmExposureRuleRepository#findEnabledExcludedEntityNames()})
 * fires exactly ONCE across many gate calls, then exactly ONCE more after an
 * {@link LlmExposureChangedEvent} eviction (admin edit visible next turn, T-18-01).</p>
 *
 * <p>Also asserts the cached set is an immutable view (T-18-04): a mutating call on the returned
 * set throws {@link UnsupportedOperationException} and does not poison or re-fetch the shared
 * cache.</p>
 */
@Tag("unit")
class LlmExposureDenylistMemoTest {

    private CurrentUserSchemaAccess delegate;
    private LlmExposureRuleRepository ruleRepository;
    private AccessManager accessManager;
    private LlmExposurePolicy policy;

    @BeforeEach
    void setUp() {
        delegate = mock(CurrentUserSchemaAccess.class);
        ruleRepository = mock(LlmExposureRuleRepository.class);
        accessManager = mock(AccessManager.class);

        Set<String> excluded = new LinkedHashSet<>();
        excluded.add("sample_Customer");
        excluded.add("sample_Order");
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(excluded);

        policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
    }

    @Test
    void denylistFetchedOnceAndReusedUntilEventThenRefetchedExactlyOnce() {
        // Many calls across distinct external/internal call sites — all served from one fetch.
        Set<String> first = policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();

        assertThat(first)
                .contains("sample_Customer", "sample_Order")
                .contains("ai_AiAuditEvent"); // built-ins folded in
        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();

        // Eviction: an admin denylist edit fires LlmExposureChangedEvent.
        policy.onExposureChanged(new LlmExposureChangedEvent(this));

        // Next call refetches exactly once — total two fetches.
        policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();
        verify(ruleRepository, times(2)).findEnabledExcludedEntityNames();
    }

    @Test
    void cachedDenylistIsImmutableAndCannotBePoisoned() {
        Set<String> returned = policy.getDenylistedEntityNames();
        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();

        // A caller mutating the shared cached set must be rejected (T-18-04).
        assertThatThrownBy(() -> returned.add("Tampered"))
                .isInstanceOf(UnsupportedOperationException.class);

        // The cache is neither poisoned nor re-fetched: same content, still one fetch.
        Set<String> again = policy.getDenylistedEntityNames();
        assertThat(again)
                .doesNotContain("Tampered")
                .isEqualTo(returned);
        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();
    }
}
