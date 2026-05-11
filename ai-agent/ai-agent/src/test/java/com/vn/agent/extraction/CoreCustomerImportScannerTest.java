package com.vn.agent.extraction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the add-on core entity-generic. The bundled Customer extractor belongs
 * to the host jmix-app module, not ai-agent core.
 */
class CoreCustomerImportScannerTest {

    private static final Path MODULE_ROOT = resolveModuleRoot();
    private static final Path AGENT_MAIN_SOURCE = MODULE_ROOT.resolve("src/main/java");
    private static final Path STREAM_EVENT_RENDERER = AGENT_MAIN_SOURCE.resolve(
            "com/vn/agent/view/chat/fragment/StreamEventRenderer.java");
    private static final String HOST_CUSTOMER_REFERENCE = "com.vn.jmixapp.entity.Customer";
    private static final List<String> FORBIDDEN_RENDERER_NAVIGATION_TOKENS = List.of(
            "io.jmix.flowui.ViewNavigators",
            "ViewNavigators",
            ".navigate("
    );

    @Test
    void aiAgentCoreDoesNotReferenceHostCustomerEntity() throws IOException {
        Map<Path, String> violations = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(AGENT_MAIN_SOURCE)) {
            for (Path sourceFile : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                String source = stripJavaComments(read(sourceFile));
                if (source.contains(HOST_CUSTOMER_REFERENCE)) {
                    violations.put(MODULE_ROOT.relativize(sourceFile), HOST_CUSTOMER_REFERENCE);
                }
            }
        }

        assertThat(violations)
                .as("ai-agent core must stay host-entity generic. Customer-specific code belongs "
                        + "in jmix-app. Offending files: %s", violations)
                .isEmpty();
    }

    @Test
    void streamEventRendererDoesNotNavigate() {
        String source = stripJavaComments(read(STREAM_EVENT_RENDERER));

        Map<String, Boolean> hits = new LinkedHashMap<>();
        for (String token : FORBIDDEN_RENDERER_NAVIGATION_TOKENS) {
            if (source.contains(token)) {
                hits.put(token, true);
            }
        }

        assertThat(hits)
                .as("StreamEventRenderer must parse payload markers only; OpenFormWithDraftHandler "
                        + "owns ViewNavigators and navigate(). Offending tokens: %s", hits)
                .isEmpty();
    }

    private static String read(Path sourceFile) {
        try {
            return Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file: " + sourceFile, e);
        }
    }

    private static String stripJavaComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static Path resolveModuleRoot() {
        Path currentDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                currentDirectory,
                currentDirectory.resolve("ai-agent/ai-agent"),
                currentDirectory.resolve("ai-agent"))) {
            if (Files.exists(candidate.resolve("src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java"))) {
                return candidate;
            }
        }
        Path cursor = currentDirectory;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return currentDirectory;
    }
}
