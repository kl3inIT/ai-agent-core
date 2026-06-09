package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 18-02 Task 2 (PERF-01) — pure-JUnit + Mockito call-count proxy for the per-turn
 * memoization on {@link LlmExposurePolicy}. <b>No {@code @SpringBootTest}</b> (isolates from the
 * pre-existing Phase 11/13 full-suite boot regression for the {@code AiAuditEvent} MetaClass).
 *
 * <p>Proves the PERF-01 / D-08 contract:</p>
 * <ul>
 *     <li>Within one active turn, {@code canReadEntity}/{@code canCreate}/{@code canUpdate}/
 *         {@code getReadableSchema} resolve EXACTLY ONCE across N tool calls — the underlying
 *         delegate / {@code AccessManager.applyRegisteredConstraints} fires once per gate
 *         (review HIGH #4 includes {@code canReadEntity}; review MEDIUM requires per-operation
 *         counts, so create+update is {@code times(2)} total, not {@code times(1)}).</li>
 *     <li>After {@link RunContext#clear()} the RAW per-turn slot is {@code null} via the
 *         non-initializing {@link RunContext#perTurnCacheSnapshotForTest()} seam — a MEANINGFUL
 *         empty-after-clear proof, not a freshly lazy-allocated empty map masking the wipe
 *         (review HIGH #3).</li>
 *     <li>A SECOND turn re-resolves (recompute, never stale cross-turn reuse).</li>
 *     <li>The no-active-turn safe miss: with no active turn the verdict recomputes WITHOUT storing
 *         — the raw slot stays {@code null} (D-02).</li>
 * </ul>
 */
@Tag("unit")
class LlmExposurePerTurnMemoTest {

    private CurrentUserSchemaAccess delegate;
    private LlmExposureRuleRepository ruleRepository;
    private AccessManager accessManager;
    private LlmExposurePolicy policy;
    private MetaClass mc;

    @BeforeEach
    void setUp() {
        delegate = mock(CurrentUserSchemaAccess.class);
        ruleRepository = mock(LlmExposureRuleRepository.class);
        accessManager = mock(AccessManager.class);
        policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);

        mc = mock(MetaClass.class);
        lenient().when(mc.getName()).thenReturn("sample_TestEntity");
        // Empty denylist so verdicts hinge on the delegate / CrudEntityContext only.
        lenient().when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of());
        lenient().when(delegate.canReadEntity(mc)).thenReturn(true);

        Map<MetaClass, Set<String>> base = new LinkedHashMap<>();
        base.put(mc, Set.of("name", "code"));
        lenient().when(delegate.getReadableSchema()).thenReturn(base);
        // Ensure a clean slate even if a prior test on this pooled thread leaked.
        RunContext.clear();
    }

    @AfterEach
    void tearDown() {
        // Keep the pooled-thread ThreadLocal clean for the next test.
        RunContext.clear();
    }

    @Test
    void verdictsResolveOncePerTurnAndSlotIsWipedAfterClear() {
        // CrudEntityContext is constructed inside canCreate/canUpdate; stub the verdict to permitted.
        try (MockedConstruction<CrudEntityContext> ignored = mockConstruction(
                CrudEntityContext.class,
                (m, ctx) -> {
                    when(m.isCreatePermitted()).thenReturn(true);
                    when(m.isUpdatePermitted()).thenReturn(true);
                })) {

            // --- Turn 1: mark an active turn so the accessor actually stores. ---
            RunContext.set(UUID.randomUUID());

            for (int i = 0; i < 3; i++) {
                assertThat(policy.canReadEntity(mc)).isTrue();
                assertThat(policy.canCreate(mc)).isTrue();
                assertThat(policy.canUpdate(mc)).isTrue();
                assertThat(policy.getReadableSchema()).containsKey(mc);
            }

            // read verdict resolved once across N calls (review HIGH #4).
            verify(delegate, times(1)).canReadEntity(mc);
            // readable schema resolved once across N calls.
            verify(delegate, times(1)).getReadableSchema();
            // applyRegisteredConstraints fires once PER OPERATION (create + update) → 2 total,
            // NOT 1 (review MEDIUM on per-operation count).
            verify(accessManager, times(2)).applyRegisteredConstraints(any(CrudEntityContext.class));

            // A live entry exists during the active turn.
            assertThat(RunContext.perTurnCacheSnapshotForTest())
                    .as("per-turn slot must hold the memoized verdicts during an active turn")
                    .isNotNull()
                    .isNotEmpty();

            // --- clear() wipes the RAW slot (non-initializing seam — review HIGH #3). ---
            RunContext.clear();
            assertThat(RunContext.perTurnCacheSnapshotForTest())
                    .as("RunContext.clear() must remove the per-turn slot entirely — no cross-turn bleed")
                    .isNull();

            // --- Turn 2: re-resolution proves recompute, not stale cross-turn reuse. ---
            RunContext.set(UUID.randomUUID());
            assertThat(policy.canReadEntity(mc)).isTrue();
            verify(delegate, times(2)).canReadEntity(mc);
        }
    }

    @Test
    void noActiveTurnRecomputesWithoutStoring() {
        // No RunContext.set(...) → no active turn. The verdict must recompute WITHOUT storing,
        // so the raw slot stays null (D-02 safe miss; nothing left on a pooled worker thread).
        assertThat(RunContext.get()).as("precondition: no active turn").isNull();

        assertThat(policy.canReadEntity(mc)).isTrue();
        assertThat(policy.canReadEntity(mc)).isTrue();

        // Recompute every call off-turn (no memoization) → delegate hit twice.
        verify(delegate, times(2)).canReadEntity(mc);
        // Nothing stored on the pooled thread.
        assertThat(RunContext.perTurnCacheSnapshotForTest())
                .as("off-turn calls must NOT allocate or store a per-turn map (D-02 safe miss)")
                .isNull();
    }
}
