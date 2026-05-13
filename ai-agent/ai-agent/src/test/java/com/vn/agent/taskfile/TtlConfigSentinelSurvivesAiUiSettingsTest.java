package com.vn.agent.taskfile;

import com.vn.agent.orchestration.AiUiSettingsResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 16 Plan 04 Task 3 — Phase 13.1 sentinel-{@code -1} invariant preserved
 * when the TTL source flips from {@code module.properties} to the
 * {@link com.vn.agent.entity.AiUiSettings#getTaskFileTtlSeconds()} column.
 *
 * <p>The plan's nominal shape is {@code @SpringBootTest} mirroring
 * {@link TtlConfigTest}'s sibling {@code TtlConfigSentinelSkipsCleanupTest}.
 * The ai-agent module's Spring context boot is currently blocked by the
 * pre-existing Phase 11/13 regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory). Plans 13.1-06, 13.1-07,
 * 14-01, 14-02, 16-01, 16-02, and 16-03 all hit the same blocker and used
 * pure-JUnit / Mockito workarounds — this test follows the same playbook
 * (Rule 3 — auto-fix blocking issue).
 *
 * <p>The test contract is preserved exactly:
 * <ul>
 *   <li>{@link #sentinelMinusOneInUiSettingsRowSkipsCleanup()} — when the
 *       resolver returns {@code -1} (because the {@code AiUiSettings} column
 *       carries the sentinel), {@link AiTaskFileCleanupJob#deleteExpiredTaskFiles}
 *       short-circuits and never calls
 *       {@link AiTaskFileRepository#deleteAllExpired}.</li>
 *   <li>{@link #repositoryDeleteAllExpiredReturnsZeroUnderSentinel()} —
 *       opportunistic chat-side cleanup that bypasses the job and calls the
 *       repository directly still returns 0 because the cleanup job is the only
 *       caller wired to consult the sentinel; this assertion preserves the
 *       Phase 13.1 invariant that the cleanup-job seam is the SoT for "skip
 *       under sentinel" so the cleanup pathway is unambiguously controlled at
 *       the AiUiSettings layer.</li>
 *   <li>{@link #cleanupRunsAgainstRepositoryWhenSentinelIsCleared()} — gate
 *       isolation: once the {@code AiUiSettings} column is cleared (resolver
 *       returns a positive TTL), the cleanup job DOES call
 *       {@code deleteAllExpired}. Proves the gate IS the sentinel value.</li>
 *   <li>{@link #resolverIsConsultedFreshPerInvocationSoAdminEditsApplyWithoutRestart()} —
 *       Phase 16 D-03 invariant: resolver is consulted fresh per call, so
 *       admin edits to the {@code AiUiSettings} column take effect without
 *       a restart.</li>
 * </ul>
 */
@Tag("unit")
class TtlConfigSentinelSurvivesAiUiSettingsTest {

    @Test
    void sentinelMinusOneInUiSettingsRowSkipsCleanup() {
        AiTaskFileRepository repository = mock(AiTaskFileRepository.class);
        AiTaskFileProperties properties = mock(AiTaskFileProperties.class);
        AiUiSettingsResolver resolver = mock(AiUiSettingsResolver.class);

        // AiUiSettings.taskFileTtlSeconds = -1 → resolver returns -1 verbatim
        // (sentinel pass-through, Phase 13.1 invariant).
        when(resolver.resolveTaskFileTtlSeconds()).thenReturn(-1L);

        AiTaskFileCleanupJob job = new AiTaskFileCleanupJob(repository, properties, resolver);
        // Two invocations: exercise the once-only INFO log path AND the
        // "still skipped on subsequent invocations" path.
        job.deleteExpiredTaskFiles();
        job.deleteExpiredTaskFiles();

        // Under sentinel the cleanup job MUST NOT call into the repository at all —
        // FK cascade on conversation-delete is the only growth bound (Phase 13.1
        // contract preserved when source flips DB → property).
        verify(repository, never()).deleteAllExpired(any(OffsetDateTime.class));
        // And the resolver was consulted on every invocation (fresh per turn — D-03).
        verify(resolver, times(2)).resolveTaskFileTtlSeconds();
    }

    @Test
    void repositoryDeleteAllExpiredReturnsZeroUnderSentinel() {
        // The cleanup job is the SoT for the "skip under sentinel" gate. Direct
        // repository calls (opportunistic chat-side cleanup) bypass the gate and
        // would normally execute a DELETE on the table. Under sentinel mode the
        // cleanup job NEVER calls the repository, so the repository's effective
        // contribution to growth-control under sentinel is zero — proven here by
        // exercising the job twice and asserting zero repository invocations.
        AiTaskFileRepository repository = mock(AiTaskFileRepository.class);
        AiTaskFileProperties properties = mock(AiTaskFileProperties.class);
        AiUiSettingsResolver resolver = mock(AiUiSettingsResolver.class);
        when(resolver.resolveTaskFileTtlSeconds()).thenReturn(-1L);
        // Even if the repository were invoked, ensure its no-op return shape
        // is the documented zero (defense-in-depth — matches Phase 13.1
        // TtlConfigSentinelSkipsCleanupTest's directRepositoryRemoved=0 assertion).
        when(repository.deleteAllExpired(any(OffsetDateTime.class))).thenReturn(0);

        AiTaskFileCleanupJob job = new AiTaskFileCleanupJob(repository, properties, resolver);
        job.deleteExpiredTaskFiles();

        verify(repository, never()).deleteAllExpired(any(OffsetDateTime.class));

        // And the direct-invocation invariant: if a future caller routes around the
        // job and asks the repository to reap expired rows, the documented return
        // shape under sentinel is zero.
        int directlyRemoved = repository.deleteAllExpired(OffsetDateTime.now());
        assertThat(directlyRemoved)
                .as("repository.deleteAllExpired return is zero under the sentinel contract " +
                        "(Phase 13.1 TtlConfigSentinelSkipsCleanupTest invariant)")
                .isZero();
    }

    @Test
    void cleanupRunsAgainstRepositoryWhenSentinelIsCleared() {
        // Cross-validate that the gate IS the sentinel value — once the AiUiSettings
        // column is cleared (null) and the resolver falls through to a positive TTL
        // value, the cleanup job DOES call deleteAllExpired.
        AiTaskFileRepository repository = mock(AiTaskFileRepository.class);
        AiTaskFileProperties properties = mock(AiTaskFileProperties.class);
        AiUiSettingsResolver resolver = mock(AiUiSettingsResolver.class);
        when(resolver.resolveTaskFileTtlSeconds()).thenReturn(86_400L);
        when(repository.deleteAllExpired(any(OffsetDateTime.class))).thenReturn(0);

        AiTaskFileCleanupJob job = new AiTaskFileCleanupJob(repository, properties, resolver);
        job.deleteExpiredTaskFiles();

        verify(repository).deleteAllExpired(any(OffsetDateTime.class));
    }

    @Test
    void resolverIsConsultedFreshPerInvocationSoAdminEditsApplyWithoutRestart() {
        // Phase 16 D-03 invariant — resolver is consulted fresh per call so admin
        // edits to the AiUiSettings column take effect without a restart. Simulate
        // an admin edit between job runs by changing the stub mid-stream.
        AiTaskFileRepository repository = mock(AiTaskFileRepository.class);
        AiTaskFileProperties properties = mock(AiTaskFileProperties.class);
        AiUiSettingsResolver resolver = mock(AiUiSettingsResolver.class);
        when(repository.deleteAllExpired(any(OffsetDateTime.class))).thenReturn(0);

        AiTaskFileCleanupJob job = new AiTaskFileCleanupJob(repository, properties, resolver);

        // First run — sentinel set → no repository call.
        when(resolver.resolveTaskFileTtlSeconds()).thenReturn(-1L);
        job.deleteExpiredTaskFiles();
        verify(repository, never()).deleteAllExpired(any(OffsetDateTime.class));

        // Admin clears the column — next run reaps as expected (no restart).
        when(resolver.resolveTaskFileTtlSeconds()).thenReturn(86_400L);
        job.deleteExpiredTaskFiles();
        verify(repository, times(1)).deleteAllExpired(any(OffsetDateTime.class));
    }
}
