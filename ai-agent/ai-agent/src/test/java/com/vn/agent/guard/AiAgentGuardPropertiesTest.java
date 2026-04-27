package com.vn.agent.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentGuardPropertiesTest {

    @Test
    void defaultOnBooleansResolveToEnabledWhenOmitted() {
        AiAgentGuardProperties props = new AiAgentGuardProperties(null, null, null, null);

        assertThat(props.rateLimitEnabled()).isTrue();
        assertThat(props.tokenBreakerEnabled()).isTrue();
        assertThat(props.outputScannerEnabled()).isTrue();
        assertThat(props.hostPrefixLeakEnabled()).isTrue();
        assertThat(props.toolNameLeakEnabled()).isTrue();
    }

    @Test
    void defaultOnBooleansCanBeDisabledExplicitly() {
        AiAgentGuardProperties props = new AiAgentGuardProperties(
                new AiAgentGuardProperties.RateLimit(false, null),
                new AiAgentGuardProperties.TokenBreaker(false, null),
                null,
                new AiAgentGuardProperties.OutputScanner(false, null,
                        new AiAgentGuardProperties.OutputScanner.HostPrefixLeak(false),
                        new AiAgentGuardProperties.OutputScanner.ToolNameLeak(false)));

        assertThat(props.rateLimitEnabled()).isFalse();
        assertThat(props.tokenBreakerEnabled()).isFalse();
        assertThat(props.outputScannerEnabled()).isFalse();
        assertThat(props.hostPrefixLeakEnabled()).isFalse();
        assertThat(props.toolNameLeakEnabled()).isFalse();
    }
}
