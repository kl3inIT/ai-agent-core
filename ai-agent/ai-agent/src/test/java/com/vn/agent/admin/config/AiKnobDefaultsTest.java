package com.vn.agent.admin.config;

import com.vn.agent.conversation.AiAgentTitleProperties;
import com.vn.agent.orchestration.AiAgentPromptProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AiKnobDefaults} exposes the same property-fallback values that
 * {@link com.vn.agent.orchestration.AiUiSettingsResolver} returns when its corresponding
 * {@code AiUiSettings} column is {@code null}. Admin UI binds these as placeholder +
 * helperText, so any drift here would show admins a different default from what actually
 * applies at runtime.
 */
@Tag("unit")
class AiKnobDefaultsTest {

    private AiTaskFileProperties taskFileProperties;
    private AiAgentMutationProperties mutationProperties;
    private AiAgentPromptProperties promptProperties;
    private AiAgentTitleProperties titleProperties;
    private AiAgentRagProperties ragProperties;
    private AiKnobDefaults defaults;

    @BeforeEach
    void setUp() {
        taskFileProperties = mock(AiTaskFileProperties.class);
        mutationProperties = mock(AiAgentMutationProperties.class);
        promptProperties = mock(AiAgentPromptProperties.class);
        titleProperties = mock(AiAgentTitleProperties.class);
        ragProperties = mock(AiAgentRagProperties.class);

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

        defaults = new AiKnobDefaults(
                taskFileProperties,
                mutationProperties,
                promptProperties,
                titleProperties,
                ragProperties,
                /* defaultMaxFilterDepth */ 3);
    }

    @Test
    void taskFileGettersDelegateToProperties() {
        assertThat(defaults.taskFileTtlSeconds()).isEqualTo(86_400L);
        assertThat(defaults.taskFilePerTurnMaxFiles()).isEqualTo(10);
        assertThat(defaults.taskFilePerTurnMaxTotalBytes()).isEqualTo(52_428_800L);
        assertThat(defaults.taskFileMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }

    @Test
    void mutationGettersDelegateToProperties() {
        assertThat(defaults.mutationConfirmationRequired()).isTrue();
        assertThat(defaults.mutationIdempotencyTtlSeconds())
                .as("Duration must be coerced to whole seconds at the boundary")
                .isEqualTo(86_400L);
        assertThat(defaults.mutationBulkMaxRows()).isEqualTo(100);
    }

    @Test
    void promptToolsGettersDelegateToPropertiesOrInjectedValue() {
        assertThat(defaults.promptEntityInventoryLimit()).isEqualTo(100);
        assertThat(defaults.toolsMaxFilterDepth())
                .as("free @Value property; not bound to a @ConfigurationProperties bean")
                .isEqualTo(3);
    }

    @Test
    void titleGettersDelegateToProperties() {
        assertThat(defaults.titleMaxContextMessages()).isEqualTo(6);
        assertThat(defaults.titleMinAssistantMessagesTrigger()).isEqualTo(1);
    }

    @Test
    void uploadGetterDelegatesToRagProperties() {
        assertThat(defaults.uploadMaxFileSizeBytes()).isEqualTo(104_857_600L);
    }
}
