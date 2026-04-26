package com.vn.agent.audit;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentAuditPropertiesTest {

    @Test
    void hashSensitiveFieldsDefaultsOn() {
        assertThat(new AiAgentAuditProperties(null, null).resolvedHashSensitiveFields()).isTrue();
        assertThat(new AiAgentAuditProperties(Boolean.TRUE, null).resolvedHashSensitiveFields()).isTrue();
    }

    @Test
    void hashSensitiveFieldsCanBeDisabled() {
        assertThat(new AiAgentAuditProperties(Boolean.FALSE, null).resolvedHashSensitiveFields()).isFalse();
    }

    @Test
    void sensitiveFieldsDefaultsToEmptySet() {
        assertThat(new AiAgentAuditProperties(null, null).resolvedSensitiveFields()).isEqualTo(Set.of());
    }
}
