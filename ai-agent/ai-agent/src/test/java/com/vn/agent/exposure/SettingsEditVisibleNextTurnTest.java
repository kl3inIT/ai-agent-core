package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import io.jmix.core.AccessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-05 admin-edit-visibility gate (T-18-14): an admin denylist change published via
 * {@link LlmExposureChangedEvent} is reflected on the NEXT turn — the app-wide denylist memo on
 * {@link LlmExposurePolicy} evicts within one turn, closing the stale-permission window.
 *
 * <p>Where {@code LlmExposureDenylistMemoTest} (Plan 01) asserts the SELECT call-count (fetched
 * once, refetched once after eviction), this test asserts the OBSERVABLE VALUE: before eviction the
 * policy still returns the OLD denylist (the memo is in effect across turns); after
 * {@code onExposureChanged(...)} it returns the NEW denylist. A non-evicting cache would keep a
 * newly-denied entity visible to the LLM across turns — exactly the tamper risk this gate rules out.</p>
 *
 * <p>Pure JUnit 5 + Mockito, NO {@code @SpringBootTest} — builds a real {@link LlmExposurePolicy}
 * over a Mockito {@code LlmExposureRuleRepository}, so it boots no Spring context and sidesteps the
 * pre-existing Phase 11/13 ai-agent module boot regression (atmosphere-runtime /
 * {@code agentstoreEntityManagerFactory}, see
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}). The app-wide
 * denylist memo lives in {@code denylistCache} and is exercised through the public
 * {@link LlmExposurePolicy#getDenylistedEntityNames()} accessor, which does not touch
 * {@code RunContext} — so no per-turn {@code RunContext} fixture is needed here.</p>
 *
 * <p><b>AiSettingsChangedEvent leg intentionally absent.</b> The plan permits a parallel
 * settings-memo visibility leg ONLY IF Plan 18-04 added an {@code @EventListener(AiSettingsChangedEvent)}
 * settings memo. Per {@code 18-04-SUMMARY.md} Plan 04 took the <b>REGRESSION-LOCK</b> branch and added
 * NO settings cache: the task-file settings ({@code resolveTaskFile*}) are read fresh from the
 * {@code AiUiSettings} singleton on every turn, so an admin settings edit is visible on the next turn
 * <i>by construction</i> (there is no memo that could go stale, hence nothing to evict). The only
 * across-turn cache in the Phase 18 surface that requires explicit eviction is this denylist memo,
 * which is what this test exercises end-to-end against the Plan 01 {@code @EventListener} wiring.</p>
 */
@Tag("unit")
class SettingsEditVisibleNextTurnTest {

    private CurrentUserSchemaAccess delegate;
    private LlmExposureRuleRepository ruleRepository;
    private AccessManager accessManager;
    private LlmExposurePolicy policy;

    @BeforeEach
    void setUp() {
        delegate = mock(CurrentUserSchemaAccess.class);
        ruleRepository = mock(LlmExposureRuleRepository.class);
        accessManager = mock(AccessManager.class);
        policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
    }

    @Test
    void adminDenylistEditIsVisibleOnTheNextTurnAfterEvictionEvent() {
        // Turn 1: admin has denied {sample_Customer}. The first resolve memoizes set A.
        Set<String> setA = new LinkedHashSet<>(Set.of("sample_Customer"));
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(setA);

        Set<String> turn1 = policy.getDenylistedEntityNames();
        assertThat(turn1)
                .as("Turn 1 denylist must contain the admin-denied entity")
                .contains("sample_Customer")
                .doesNotContain("sample_Order");

        // Admin edits the denylist: now {sample_Customer, sample_Order}. The repository would return
        // set B on the next fetch — but WITHOUT eviction the memo still serves set A (the cache is
        // in effect across turns; this is the very window the eviction must close).
        Set<String> setB = new LinkedHashSet<>(Set.of("sample_Customer", "sample_Order"));
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(setB);

        Set<String> stillCached = policy.getDenylistedEntityNames();
        assertThat(stillCached)
                .as("Before the eviction event the memo MUST still serve the OLD denylist "
                        + "(set A) — proving there is a real cache to evict")
                .contains("sample_Customer")
                .doesNotContain("sample_Order");
        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();

        // The admin edit publishes LlmExposureChangedEvent → @EventListener clears the memo.
        policy.onExposureChanged(new LlmExposureChangedEvent(this));

        // Next turn: the resolve now reflects set B — the admin edit is visible (T-18-14).
        Set<String> nextTurn = policy.getDenylistedEntityNames();
        assertThat(nextTurn)
                .as("After the eviction event the denylist MUST reflect the admin edit (set B) — "
                        + "the newly-denied entity is now hidden from the LLM surface on the next turn")
                .contains("sample_Customer", "sample_Order");
        // Exactly one refetch happened as a result of the eviction (no extra fetches).
        verify(ruleRepository, times(2)).findEnabledExcludedEntityNames();
    }

    @Test
    void evictionRefetchesExactlyOncePerEventNotPerCall() {
        // Guards the "exactly one refetch after the event" half of the contract: after a single
        // eviction, many subsequent resolves are served from the one refetch — the memo re-arms.
        when(ruleRepository.findEnabledExcludedEntityNames())
                .thenReturn(new LinkedHashSet<>(Set.of("sample_Customer")));
        policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();
        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();

        policy.onExposureChanged(new LlmExposureChangedEvent(this));

        policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();
        policy.getDenylistedEntityNames();
        // One pre-event fetch + exactly one post-event refetch — not one per resolve.
        verify(ruleRepository, times(2)).findEnabledExcludedEntityNames();
    }
}
