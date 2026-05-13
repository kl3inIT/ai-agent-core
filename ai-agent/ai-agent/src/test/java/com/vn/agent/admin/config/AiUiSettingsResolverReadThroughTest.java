package com.vn.agent.admin.config;

import com.vn.agent.conversation.AiAgentTitleProperties;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.orchestration.AiAgentPromptProperties;
import com.vn.agent.orchestration.AiUiSettingsResolver;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import io.jmix.core.FluentLoader;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 16 Plan 04 Task 3 — D-03 read-through invariant for
 * {@link AiUiSettingsResolver}: DB column non-null wins; null column falls
 * through to the {@code @ConfigurationProperties} value.
 *
 * <p>The plan's nominal shape is {@code @SpringBootTest} mirroring Pattern I.
 * The ai-agent module's Spring context boot is currently blocked by the
 * pre-existing Phase 11/13 regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory). Plans 13.1-06, 13.1-07,
 * 14-01, 14-02, 16-01, 16-02, and 16-03 all hit the same blocker and used
 * pure-JUnit / Mockito workarounds — this test follows the same playbook
 * (Rule 3 — auto-fix blocking issue).
 *
 * <p>The test contract is preserved exactly: each cluster gets the documented
 * {@code DbWinsOverProperty} + {@code NullColumnFallsThroughToProperty}
 * assertion shape. The resolver under test is a real
 * {@link AiUiSettingsResolver} instance; only the
 * {@link UnconstrainedDataManager} singleton-load chain and the property beans
 * are mocked so column values can be flipped per test.
 *
 * <p>codex HIGH Concern #5 — task-file per-file upload cap
 * ({@link AiUiSettingsResolver#resolveTaskFileMaxFileSizeBytes}) and KB/RAG
 * upload cap ({@link AiUiSettingsResolver#resolveRagUploadMaxFileSizeBytes})
 * are asserted as DISTINCT methods backed by DISTINCT columns and DISTINCT
 * property beans via two separate cluster pairs PLUS a cross-isolation
 * assertion that swapping one column does NOT change the other resolver's
 * output.
 */
@Tag("unit")
class AiUiSettingsResolverReadThroughTest {

    private UnconstrainedDataManager unconstrainedDataManager;
    private AiTaskFileProperties taskFileProperties;
    private AiAgentMutationProperties mutationProperties;
    private AiAgentPromptProperties promptProperties;
    private AiAgentTitleProperties titleProperties;
    private AiAgentRagProperties ragProperties;

    private AiUiSettings columnsBackingRow;
    private AiUiSettingsResolver resolver;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        unconstrainedDataManager = mock(UnconstrainedDataManager.class);
        taskFileProperties = mock(AiTaskFileProperties.class);
        mutationProperties = mock(AiAgentMutationProperties.class);
        promptProperties = mock(AiAgentPromptProperties.class);
        titleProperties = mock(AiAgentTitleProperties.class);
        ragProperties = mock(AiAgentRagProperties.class);

        // Wire the singleton-load chain so every resolveXxx() call returns
        // columnsBackingRow (mutated in-place per test).
        columnsBackingRow = new AiUiSettings();
        FluentLoader<AiUiSettings> loader = mock(FluentLoader.class);
        FluentLoader.ById<AiUiSettings> byId = mock(FluentLoader.ById.class);
        when(unconstrainedDataManager.load(AiUiSettings.class)).thenReturn(loader);
        when(loader.id(any())).thenReturn(byId);
        when(byId.optional()).thenAnswer(invocation -> Optional.of(columnsBackingRow));

        // Property-fallback baselines (DB-null → these values flow through).
        when(taskFileProperties.getTtlSeconds()).thenReturn(86_400L);
        when(taskFileProperties.getPerTurnMaxFiles()).thenReturn(10);
        when(taskFileProperties.getPerTurnMaxTotalBytes()).thenReturn(52_428_800L);
        when(taskFileProperties.getMaxFileSizeBytes()).thenReturn(104_857_600L);
        when(mutationProperties.resolvedConfirmationRequired()).thenReturn(true);
        when(mutationProperties.resolvedIdempotencyTtl()).thenReturn(Duration.ofHours(24));
        when(mutationProperties.resolvedBulkMaxRows()).thenReturn(100);
        when(promptProperties.resolvedEntityInventoryLimit()).thenReturn(100);
        when(titleProperties.resolvedMaxContextMessages()).thenReturn(6);
        when(titleProperties.resolvedMinAssistantMessagesTrigger()).thenReturn(1);
        when(ragProperties.resolvedUploadMaxFileSizeBytes()).thenReturn(104_857_600);

        resolver = new AiUiSettingsResolver(
                unconstrainedDataManager,
                taskFileProperties,
                mutationProperties,
                promptProperties,
                titleProperties,
                ragProperties,
                /* defaultMaxFilterDepth */ 3);
    }

    // ----------------------------------------------------------------------
    // Task-file cluster — TTL, per-turn caps, per-file upload cap (4 knobs)
    // ----------------------------------------------------------------------

    @Test
    void taskFileTtlSecondsDbWinsOverProperty() {
        columnsBackingRow.setTaskFileTtlSeconds(3_600L);
        assertThat(resolver.resolveTaskFileTtlSeconds())
                .as("DB column non-null must win over property fallback")
                .isEqualTo(3_600L);
    }

    @Test
    void taskFileTtlSecondsNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTaskFileTtlSeconds(null);
        assertThat(resolver.resolveTaskFileTtlSeconds())
                .as("null DB column must fall through to the AiTaskFileProperties default")
                .isEqualTo(86_400L);
    }

    @Test
    void taskFileTtlSecondsSentinelMinusOnePassesThrough() {
        // Phase 13.1 invariant — -1 disables cleanup; resolver must never coerce it.
        columnsBackingRow.setTaskFileTtlSeconds(-1L);
        assertThat(resolver.resolveTaskFileTtlSeconds())
                .as("sentinel -1 must pass through verbatim — Phase 13.1 contract")
                .isEqualTo(-1L);
    }

    @Test
    void taskFilePerTurnMaxFilesDbWinsOverProperty() {
        columnsBackingRow.setTaskFilePerTurnMaxFiles(5);
        assertThat(resolver.resolveTaskFilePerTurnMaxFiles()).isEqualTo(5);
    }

    @Test
    void taskFilePerTurnMaxFilesNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTaskFilePerTurnMaxFiles(null);
        assertThat(resolver.resolveTaskFilePerTurnMaxFiles()).isEqualTo(10);
    }

    @Test
    void taskFilePerTurnMaxTotalBytesDbWinsOverProperty() {
        columnsBackingRow.setTaskFilePerTurnMaxTotalBytes(1_000L);
        assertThat(resolver.resolveTaskFilePerTurnMaxTotalBytes()).isEqualTo(1_000L);
    }

    @Test
    void taskFilePerTurnMaxTotalBytesNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTaskFilePerTurnMaxTotalBytes(null);
        assertThat(resolver.resolveTaskFilePerTurnMaxTotalBytes()).isEqualTo(52_428_800L);
    }

    @Test
    void taskFileMaxFileSizeBytesDbWinsOverProperty() {
        // codex HIGH Concern #5 — chat task-file dropzone cap (DISTINCT knob).
        columnsBackingRow.setTaskFileMaxFileSizeBytes(2_048L);
        assertThat(resolver.resolveTaskFileMaxFileSizeBytes()).isEqualTo(2_048L);
    }

    @Test
    void taskFileMaxFileSizeBytesNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTaskFileMaxFileSizeBytes(null);
        assertThat(resolver.resolveTaskFileMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }

    // ----------------------------------------------------------------------
    // Mutation cluster — confirmation, idempotency TTL, bulk cap (3 knobs)
    // ----------------------------------------------------------------------

    @Test
    void mutationConfirmationRequiredDbWinsOverProperty() {
        columnsBackingRow.setMutationConfirmationRequired(false);
        assertThat(resolver.resolveMutationConfirmationRequired()).isFalse();
    }

    @Test
    void mutationConfirmationRequiredNullColumnFallsThroughToProperty() {
        columnsBackingRow.setMutationConfirmationRequired(null);
        assertThat(resolver.resolveMutationConfirmationRequired()).isTrue();
    }

    @Test
    void mutationIdempotencyTtlSecondsDbWinsOverProperty() {
        columnsBackingRow.setMutationIdempotencyTtlSeconds(120L);
        assertThat(resolver.resolveMutationIdempotencyTtlSeconds())
                .as("Phase 16 D-01 — resolver returns seconds (Long); consumers coerce to Duration")
                .isEqualTo(120L);
    }

    @Test
    void mutationIdempotencyTtlSecondsNullColumnFallsThroughToProperty() {
        // opencode MEDIUM Concern #4 — property bean exposes Duration, resolver
        // coerces to long seconds at the resolver boundary so every caller gets
        // a uniform long-seconds return.
        columnsBackingRow.setMutationIdempotencyTtlSeconds(null);
        assertThat(resolver.resolveMutationIdempotencyTtlSeconds())
                .as("Duration.ofHours(24).toSeconds() == 86400")
                .isEqualTo(86_400L);
    }

    @Test
    void mutationBulkMaxRowsDbWinsOverProperty() {
        columnsBackingRow.setMutationBulkMaxRows(50);
        assertThat(resolver.resolveMutationBulkMaxRows()).isEqualTo(50);
    }

    @Test
    void mutationBulkMaxRowsNullColumnFallsThroughToProperty() {
        columnsBackingRow.setMutationBulkMaxRows(null);
        assertThat(resolver.resolveMutationBulkMaxRows()).isEqualTo(100);
    }

    // ----------------------------------------------------------------------
    // Prompt / tools cluster (2 knobs)
    // ----------------------------------------------------------------------

    @Test
    void promptEntityInventoryLimitDbWinsOverProperty() {
        columnsBackingRow.setPromptEntityInventoryLimit(25);
        assertThat(resolver.resolvePromptEntityInventoryLimit()).isEqualTo(25);
    }

    @Test
    void promptEntityInventoryLimitNullColumnFallsThroughToProperty() {
        columnsBackingRow.setPromptEntityInventoryLimit(null);
        assertThat(resolver.resolvePromptEntityInventoryLimit()).isEqualTo(100);
    }

    @Test
    void toolsMaxFilterDepthDbWinsOverProperty() {
        columnsBackingRow.setToolsMaxFilterDepth(7);
        assertThat(resolver.resolveToolsMaxFilterDepth()).isEqualTo(7);
    }

    @Test
    void toolsMaxFilterDepthNullColumnFallsThroughToConstructorDefault() {
        // No @ConfigurationProperties record owns this knob; the resolver's
        // constructor takes the property default via @Value (3 here).
        columnsBackingRow.setToolsMaxFilterDepth(null);
        assertThat(resolver.resolveToolsMaxFilterDepth()).isEqualTo(3);
    }

    // ----------------------------------------------------------------------
    // Title cluster (2 knobs)
    // ----------------------------------------------------------------------

    @Test
    void titleMaxContextMessagesDbWinsOverProperty() {
        columnsBackingRow.setTitleMaxContextMessages(20);
        assertThat(resolver.resolveTitleMaxContextMessages()).isEqualTo(20);
    }

    @Test
    void titleMaxContextMessagesNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTitleMaxContextMessages(null);
        assertThat(resolver.resolveTitleMaxContextMessages()).isEqualTo(6);
    }

    @Test
    void titleMinAssistantMessagesTriggerDbWinsOverProperty() {
        columnsBackingRow.setTitleMinAssistantMessagesTrigger(3);
        assertThat(resolver.resolveTitleMinAssistantMessagesTrigger()).isEqualTo(3);
    }

    @Test
    void titleMinAssistantMessagesTriggerNullColumnFallsThroughToProperty() {
        columnsBackingRow.setTitleMinAssistantMessagesTrigger(null);
        assertThat(resolver.resolveTitleMinAssistantMessagesTrigger()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // RAG/KB upload cluster (1 knob — DISTINCT from task-file upload)
    // ----------------------------------------------------------------------

    @Test
    void ragUploadMaxFileSizeBytesDbWinsOverProperty() {
        columnsBackingRow.setUploadMaxFileSizeBytes(8_192L);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes()).isEqualTo(8_192L);
    }

    @Test
    void ragUploadMaxFileSizeBytesNullColumnFallsThroughToProperty() {
        columnsBackingRow.setUploadMaxFileSizeBytes(null);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }

    // ----------------------------------------------------------------------
    // codex HIGH Concern #5 — upload-knob distinction cross-isolation
    // ----------------------------------------------------------------------

    @Test
    void taskFileAndRagUploadCapsAreIndependent() {
        // Swap ONLY the chat task-file column; RAG upload resolver must NOT see it.
        columnsBackingRow.setTaskFileMaxFileSizeBytes(2_048L);
        columnsBackingRow.setUploadMaxFileSizeBytes(null);
        assertThat(resolver.resolveTaskFileMaxFileSizeBytes()).isEqualTo(2_048L);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes())
                .as("swapping taskFileMaxFileSizeBytes must NOT change the RAG upload resolver — " +
                        "the two are DISTINCT methods (codex HIGH Concern #5)")
                .isEqualTo(104_857_600L);

        // And the inverse: swapping RAG upload column does NOT change task-file resolver.
        columnsBackingRow.setTaskFileMaxFileSizeBytes(null);
        columnsBackingRow.setUploadMaxFileSizeBytes(16_384L);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes()).isEqualTo(16_384L);
        assertThat(resolver.resolveTaskFileMaxFileSizeBytes())
                .as("swapping uploadMaxFileSizeBytes must NOT change the task-file resolver")
                .isEqualTo(104_857_600L);
    }

    // ----------------------------------------------------------------------
    // Pattern C resilience — DB load throws, resolver returns property fallback
    // ----------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resilientFallbackWhenSingletonLoadThrows() {
        FluentLoader<AiUiSettings> loader = mock(FluentLoader.class);
        FluentLoader.ById<AiUiSettings> byId = mock(FluentLoader.ById.class);
        when(unconstrainedDataManager.load(AiUiSettings.class)).thenReturn(loader);
        when(loader.id(any())).thenReturn(byId);
        when(byId.optional()).thenThrow(new RuntimeException("simulated DB outage"));

        // A transient DB hiccup must NOT break a chat turn — every resolveXxx() must
        // log WARN and return the property-bean fallback.
        assertThat(resolver.resolveTaskFileTtlSeconds()).isEqualTo(86_400L);
        assertThat(resolver.resolveMutationBulkMaxRows()).isEqualTo(100);
        assertThat(resolver.resolvePromptEntityInventoryLimit()).isEqualTo(100);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }

    // ----------------------------------------------------------------------
    // Singleton-row-absent — resolver returns property fallback
    // ----------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void absentSingletonFallsThroughToPropertyDefaults() {
        FluentLoader<AiUiSettings> loader = mock(FluentLoader.class);
        FluentLoader.ById<AiUiSettings> byId = mock(FluentLoader.ById.class);
        when(unconstrainedDataManager.load(AiUiSettings.class)).thenReturn(loader);
        when(loader.id(any())).thenReturn(byId);
        when(byId.optional()).thenReturn(Optional.empty());

        // No singleton row = all property defaults (fresh-install behavior).
        assertThat(resolver.resolveTaskFileTtlSeconds()).isEqualTo(86_400L);
        assertThat(resolver.resolveTaskFileMaxFileSizeBytes()).isEqualTo(104_857_600L);
        assertThat(resolver.resolveMutationConfirmationRequired()).isTrue();
        assertThat(resolver.resolveMutationIdempotencyTtlSeconds()).isEqualTo(86_400L);
        assertThat(resolver.resolveToolsMaxFilterDepth()).isEqualTo(3);
        assertThat(resolver.resolveRagUploadMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }
}
