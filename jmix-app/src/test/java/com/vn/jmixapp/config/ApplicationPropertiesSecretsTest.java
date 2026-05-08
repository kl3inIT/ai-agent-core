package com.vn.jmixapp.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesSecretsTest {

    private static final List<String> APPLICATION_PLACEHOLDER_KEYS = List.of(
            "MAIN_DATASOURCE_URL",
            "MAIN_DATASOURCE_USERNAME",
            "MAIN_DATASOURCE_PASSWORD",
            "AGENTSTORE_DATASOURCE_URL",
            "AGENTSTORE_DATASOURCE_USERNAME",
            "AGENTSTORE_DATASOURCE_PASSWORD",
            "JMIX_UI_LOGIN_DEFAULT_USERNAME",
            "JMIX_UI_LOGIN_DEFAULT_PASSWORD"
    );

    private static final List<String> ENV_EXAMPLE_KEYS = List.of(
            "MAIN_DATASOURCE_URL",
            "MAIN_DATASOURCE_USERNAME",
            "MAIN_DATASOURCE_PASSWORD",
            "AGENTSTORE_DATASOURCE_URL",
            "AGENTSTORE_DATASOURCE_USERNAME",
            "AGENTSTORE_DATASOURCE_PASSWORD",
            "JMIX_UI_LOGIN_DEFAULT_USERNAME",
            "JMIX_UI_LOGIN_DEFAULT_PASSWORD",
            "OPENROUTER_API_KEY"
    );

    @Test
    void applicationPropertiesDoNotCommitKnownDevelopmentSecrets() throws IOException {
        String properties = Files.readString(resolve("jmix-app/src/main/resources/application.properties",
                "src/main/resources/application.properties"));

        assertThat(properties)
                .doesNotContain("10.123.123.174")
                .doesNotContain("admin123")
                .doesNotContain("ui.login.defaultPassword=admin");
        for (String placeholderKey : APPLICATION_PLACEHOLDER_KEYS) {
            assertThat(properties).contains(placeholderKey);
        }
    }

    @Test
    void envExampleContainsPlaceholderKeysOnly() throws IOException {
        Path envExample = resolve("jmix-app/.env.example", ".env.example");
        List<String> lines = Files.readAllLines(envExample).stream()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(lines)
                .containsExactlyElementsOf(ENV_EXAMPLE_KEYS.stream()
                        .map(key -> key + "=")
                        .toList());
    }

    private static Path resolve(String repositoryRelativePath, String moduleRelativePath) {
        List<Path> candidates = List.of(
                Path.of(repositoryRelativePath),
                Path.of(moduleRelativePath),
                Path.of(System.getProperty("user.dir")).resolve(repositoryRelativePath).normalize(),
                Path.of(System.getProperty("user.dir")).resolve(moduleRelativePath).normalize()
        );
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing file " + repositoryRelativePath));
    }
}
