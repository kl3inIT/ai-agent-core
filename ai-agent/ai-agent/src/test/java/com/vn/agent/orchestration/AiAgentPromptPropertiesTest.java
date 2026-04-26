package com.vn.agent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiAgentPromptProperties} D-03 default-resolution semantics.
 *
 * <p>Mirrors the {@code AiAgentGuardProperties} default-resolution test pattern: build the record
 * directly (no Spring boot) and assert each {@code resolved*} accessor returns the documented
 * default when the underlying field is absent.</p>
 */
class AiAgentPromptPropertiesTest {

    @Test
    void absentNestedBlock_defaultsTo100() {
        assertThat(new AiAgentPromptProperties(null).resolvedEntityInventoryLimit()).isEqualTo(100);
    }

    @Test
    void absentLimitField_defaultsTo100() {
        assertThat(new AiAgentPromptProperties(new AiAgentPromptProperties.EntityInventory(null))
                .resolvedEntityInventoryLimit()).isEqualTo(100);
    }

    @Test
    void operatorOverride_isHonoured() {
        assertThat(new AiAgentPromptProperties(new AiAgentPromptProperties.EntityInventory(42))
                .resolvedEntityInventoryLimit()).isEqualTo(42);
    }
}
