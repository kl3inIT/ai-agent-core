package com.vn.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigurationContractTest {

    // Demo host application.properties. jmix-app was retired in favour of dth-crm as the
    // sole demo host; this contract now tracks the current host's provider wiring.
    private static final String HOST_APPLICATION_PROPERTIES =
            "dth-crm/src/main/resources/application.properties";

    @Test
    void applicationUsesOpenRouterBaseUrlAndSeparateChatEmbeddingModels() throws Exception {
        Properties properties = loadProperties(HOST_APPLICATION_PROPERTIES);

        assertThat(properties.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://openrouter.ai/api");
        // The API key must be environment-injected (no literal default secret), regardless of
        // the placeholder default the host chooses.
        assertThat(properties.getProperty("spring.ai.openai.api-key"))
                .startsWith("${OPENROUTER_API_KEY:");
        assertThat(properties.getProperty("spring.ai.openai.chat.options.model"))
                .isNotBlank();
        assertThat(properties.getProperty("spring.ai.openai.embedding.options.model"))
                .isNotBlank()
                .isNotEqualTo(properties.getProperty("spring.ai.openai.chat.options.model"));
        assertThat(properties.getProperty("jmix.ai-agent.embedding.model"))
                .isEqualTo(properties.getProperty("spring.ai.openai.embedding.options.model"));
    }

    @Test
    void applicationDoesNotHardcodeProviderSecret() throws Exception {
        String source = read(HOST_APPLICATION_PROPERTIES);

        assertThat(source)
                .contains("spring.ai.openai.api-key=${OPENROUTER_API_KEY:")
                .doesNotContain("spring.ai.openai.api-key=sk-");
    }

    private static Properties loadProperties(String repositoryPath) throws Exception {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(resolve(repositoryPath), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String read(String repositoryPath) throws Exception {
        return Files.readString(resolve(repositoryPath), StandardCharsets.UTF_8);
    }

    private static Path resolve(String repositoryPath) throws Exception {
        for (Path candidate : new Path[]{
                Path.of(repositoryPath),
                Path.of(System.getProperty("user.dir")).resolve(repositoryPath).normalize(),
                Path.of(System.getProperty("user.dir")).resolve("..").resolve("..").resolve(repositoryPath).normalize()
        }) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new java.nio.file.NoSuchFileException(repositoryPath);
    }
}
