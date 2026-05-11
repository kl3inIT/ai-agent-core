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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Phase 14 prefill boundary. Raw EntityValues.setValue is allowed
 * only inside DraftLoader.setValueIfPermitted after attribute permission checks.
 */
class DraftSetValueBypassScannerTest {

    private static final Path MODULE_ROOT = resolveModuleRoot();
    private static final Path EXTRACTION_SOURCE_ROOT = MODULE_ROOT.resolve(
            "src/main/java/com/vn/agent/extraction");
    private static final Path DRAFT_LOADER_SOURCE = EXTRACTION_SOURCE_ROOT.resolve("DraftLoader.java");
    private static final String RAW_SET_VALUE_TOKEN = "EntityValues.setValue";
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:private|protected|public)\\s+[^\\n;=]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*\\{");

    @Test
    void entityValuesSetValueAppearsOnlyInDraftLoaderPermissionHelper() throws IOException {
        Map<Path, List<String>> violations = new LinkedHashMap<>();
        int allowedCallCount = 0;

        try (Stream<Path> paths = Files.walk(EXTRACTION_SOURCE_ROOT)) {
            for (Path sourceFile : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                String source = stripJavaComments(read(sourceFile));
                int index = source.indexOf(RAW_SET_VALUE_TOKEN);
                while (index >= 0) {
                    String methodName = enclosingMethodName(source, index);
                    if (sourceFile.normalize().equals(DRAFT_LOADER_SOURCE.normalize())
                            && "setValueIfPermitted".equals(methodName)) {
                        allowedCallCount++;
                    } else {
                        violations.put(MODULE_ROOT.relativize(sourceFile),
                                List.of("method=" + methodName, "token=" + RAW_SET_VALUE_TOKEN));
                    }
                    index = source.indexOf(RAW_SET_VALUE_TOKEN, index + RAW_SET_VALUE_TOKEN.length());
                }
            }
        }

        assertThat(violations)
                .as("Raw draft prefill writes must stay inside DraftLoader.setValueIfPermitted. "
                        + "Offending files/methods: %s", violations)
                .isEmpty();
        assertThat(allowedCallCount)
                .as("DraftLoader.setValueIfPermitted should contain the single guarded setValue call")
                .isEqualTo(1);
    }

    private static String enclosingMethodName(String source, int tokenIndex) {
        Matcher matcher = METHOD_DECLARATION.matcher(source);
        String methodName = "<unknown>";
        while (matcher.find()) {
            int methodStart = matcher.start();
            int bodyStart = source.indexOf('{', matcher.end() - 1);
            if (methodStart > tokenIndex || bodyStart < 0) {
                break;
            }
            int bodyEnd = matchingBraceIndex(source, bodyStart);
            if (bodyEnd >= tokenIndex) {
                methodName = matcher.group(1);
            }
        }
        return methodName;
    }

    private static int matchingBraceIndex(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return source.length();
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
            if (Files.exists(candidate.resolve("src/main/java/com/vn/agent/extraction/DraftLoader.java"))) {
                return candidate;
            }
        }
        Path cursor = currentDirectory;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("src/main/java/com/vn/agent/extraction/DraftLoader.java"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return currentDirectory;
    }
}
