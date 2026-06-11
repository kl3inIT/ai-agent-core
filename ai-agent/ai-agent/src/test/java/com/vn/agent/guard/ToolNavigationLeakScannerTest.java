package com.vn.agent.guard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-15 scanner: LLM-facing tool surfaces must never own Jmix UI navigation.
 * The only Phase 14 navigation owner is OpenFormWithDraftHandler, which is not
 * a tool-bearing class and is intentionally outside this scan.
 */
class ToolNavigationLeakScannerTest {

    private static final Path REPOSITORY_ROOT = resolveRepositoryRoot();
    private static final Path AGENT_MAIN_SOURCE = REPOSITORY_ROOT.resolve(
            "ai-agent/ai-agent/src/main/java");
    private static final Path HOST_MAIN_SOURCE = REPOSITORY_ROOT.resolve("dth-crm/src/main/java");
    private static final Path EXTRACTION_TOOL_BRIDGE = AGENT_MAIN_SOURCE.resolve(
            "com/vn/agent/extraction/ExtractionToolBridge.java");

    private static final Pattern TOOL_ANNOTATION = Pattern.compile("@Tool\\s*\\(");
    private static final Pattern TOOL_CONTRIBUTOR_IMPLEMENTATION = Pattern.compile(
            "\\bimplements\\s+[^{};]*\\bToolContributor\\b");
    private static final Pattern TOOL_CALLBACK_PROVIDER_SURFACE = Pattern.compile(
            "\\b(MethodToolCallbackProvider|ToolCallbackProvider)\\b");
    private static final List<String> FORBIDDEN_NAVIGATION_TOKENS = List.of(
            "io.jmix.flowui.ViewNavigators",
            "ViewNavigators",
            ".navigate("
    );

    @Test
    void toolBearingClassesDoNotReferenceViewNavigation() throws IOException {
        List<Path> scannedFiles = discoverToolSurfaceFiles();

        assertThat(scannedFiles)
                .as("ExtractionToolBridge must be part of TEST-15's explicit scan target")
                .contains(EXTRACTION_TOOL_BRIDGE);

        Map<Path, List<String>> violations = new LinkedHashMap<>();
        for (Path sourceFile : scannedFiles) {
            String uncommented = stripJavaComments(read(sourceFile));
            List<String> hits = forbiddenHits(uncommented);
            if (!hits.isEmpty()) {
                violations.put(REPOSITORY_ROOT.relativize(sourceFile), hits);
            }
        }

        assertThat(violations)
                .as("LLM-facing tool surfaces must not import ViewNavigators, reference "
                        + "ViewNavigators, or call .navigate(). Offending files/tokens: %s",
                        violations)
                .isEmpty();
    }

    @Test
    void openFormWithDraftHandlerIsNotAccidentallyScannedAsToolSurface() throws IOException {
        List<Path> scannedFiles = discoverToolSurfaceFiles();

        assertThat(scannedFiles)
                .as("OpenFormWithDraftHandler is the allowed controller-side navigation owner, "
                        + "not a Spring AI tool surface")
                .noneMatch(path -> path.endsWith(
                        Path.of("com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java")));
    }

    private static List<Path> discoverToolSurfaceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        collectMatchingSources(AGENT_MAIN_SOURCE, files);
        if (Files.exists(HOST_MAIN_SOURCE)) {
            collectMatchingSources(HOST_MAIN_SOURCE, files);
        }
        if (!files.contains(EXTRACTION_TOOL_BRIDGE)) {
            files.add(EXTRACTION_TOOL_BRIDGE);
        }
        return files.stream()
                .distinct()
                .sorted()
                .toList();
    }

    private static void collectMatchingSources(Path root, List<Path> files) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(ToolNavigationLeakScannerTest::isToolSurface)
                    .forEach(files::add);
        }
    }

    private static boolean isToolSurface(Path sourceFile) {
        String source = stripJavaComments(read(sourceFile));
        return TOOL_ANNOTATION.matcher(source).find()
                || TOOL_CONTRIBUTOR_IMPLEMENTATION.matcher(source).find()
                || TOOL_CALLBACK_PROVIDER_SURFACE.matcher(source).find();
    }

    private static List<String> forbiddenHits(String uncommentedSource) {
        return FORBIDDEN_NAVIGATION_TOKENS.stream()
                .filter(uncommentedSource::contains)
                .toList();
    }

    private static String read(Path sourceFile) {
        try {
            return Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file: " + sourceFile, e);
        }
    }

    private static String stripJavaComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    out.append(current);
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                } else if (current == '\n' || current == '\r') {
                    out.append(current);
                }
                continue;
            }
            if (!inString && !inChar && current == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && !inChar && current == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            out.append(current);

            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && current == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && current == '"') {
                inString = !inString;
            } else if (!inString && current == '\'') {
                inChar = !inChar;
            }
        }
        return out.toString();
    }

    private static Path resolveRepositoryRoot() {
        Path currentDirectory = Path.of("").toAbsolutePath();
        Path cursor = currentDirectory;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("ai-agent/ai-agent/src/main/java"))
                    && Files.exists(cursor.resolve("dth-crm/src/main/java"))) {
                return cursor;
            }
            if (Files.exists(cursor.resolve("src/main/java/com/vn/agent"))
                    && cursor.getParent() != null
                    && cursor.getParent().getParent() != null
                    && Files.exists(cursor.getParent().getParent().resolve("dth-crm/src/main/java"))) {
                return cursor.getParent().getParent();
            }
            cursor = cursor.getParent();
        }
        return currentDirectory;
    }
}
