package com.vn.agent.rag.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentRagPropertiesTest {

    @Test
    void resolvedUploadMaxFileSizeBytesDefaultsToOneHundredMib() {
        AiAgentRagProperties properties = properties(null);

        assertThat(properties.resolvedUploadMaxFileSizeBytes()).isEqualTo(104_857_600);
    }

    @Test
    void resolvedUploadMaxFileSizeBytesUsesConfiguredValue() {
        AiAgentRagProperties.Upload upload =
                new AiAgentRagProperties.Upload(null, null, 209_715_200);

        assertThat(properties(upload).resolvedUploadMaxFileSizeBytes()).isEqualTo(209_715_200);
    }

    @Test
    void resolvedUploadMaxFileSizeBytesFallsBackForNonPositiveValue() {
        AiAgentRagProperties.Upload upload = new AiAgentRagProperties.Upload(null, null, 0);

        assertThat(properties(upload).resolvedUploadMaxFileSizeBytes()).isEqualTo(104_857_600);
    }

    private static AiAgentRagProperties properties(AiAgentRagProperties.Upload upload) {
        return new AiAgentRagProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                upload);
    }
}
