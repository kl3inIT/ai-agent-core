package com.vn.agent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptComposerTest {

    @Test
    void composePlacesBaselineRulesAndHostPromptInRuntimeOrder() {
        String composed = SystemPromptComposer.compose("agent.username=admin", "host prompt");

        assertThat(composed)
                .startsWith("agent.username=admin")
                .contains("Vocabulary rules:")
                .contains("Knowledge-base context:")
                .endsWith("host prompt");
    }

    @Test
    void composeOmitsBlankHostPrompt() {
        String composed = SystemPromptComposer.compose("agent.username=admin", " ");

        assertThat(composed)
                .startsWith("agent.username=admin")
                .contains("Unknown-entity recovery (mandatory):")
                .doesNotEndWith("\n\n ");
    }
}
