package com.vn.agent.exposure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 18-02 Task 2 (D-09 / T-18-06) — source-scan boundary invariant for the per-turn cache.
 *
 * <p>The per-turn memoization symbol ({@code PER_TURN_CACHE} slot, {@code perTurnCache}/
 * {@code perTurnMemoize}/{@code perTurnCacheSnapshotForTest} accessors) memoizes ONLY the LLM-facing
 * schema/verdict surface. It MUST be confined to {@code RunContext.java} (the slot owner) and
 * {@code LlmExposurePolicy.java} (the read-through consumer), and MUST NEVER appear in the
 * constrained-{@code DataManager} row-data path ({@code BuiltInDataTools.java}) — {@code AccessManager}
 * / constrained {@code DataManager} stay authoritative for actual row-level access (D-09). A leak of
 * the cache symbol into the row-data path would risk the memo becoming the access decision for real
 * row reads (T-18-06).</p>
 *
 * <p>Pure JUnit, no Spring — reuses the {@code findMainJavaRoot()} + {@code Files.walkFileTree}
 * source-scan idiom from {@code AiSettingsChangedEventListenerInvariantTest}.</p>
 */
@Tag("unit")
class PerTurnCacheBoundaryInvariantTest {

    /** The per-turn cache identifiers that may live ONLY in RunContext + LlmExposurePolicy. */
    private static final Pattern CACHE_SYMBOL = Pattern.compile(
            "PER_TURN_CACHE|perTurnCache|perTurnMemoize|perTurnCacheSnapshotForTest");

    private static final Set<String> ALLOWED = Set.of(
            "RunContext.java",
            "LlmExposurePolicy.java");

    @Test
    void perTurnCacheSymbolConfinedToRunContextAndPolicy() throws IOException {
        Path mainJavaRoot = findMainJavaRoot();

        Set<String> offenders = new TreeSet<>();
        Files.walkFileTree(mainJavaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String content = Files.readString(file);
                if (CACHE_SYMBOL.matcher(content).find() && !ALLOWED.contains(fileName)) {
                    offenders.add(fileName);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(offenders)
                .as("D-09 boundary (T-18-06): the per-turn cache symbol may appear ONLY in "
                        + "RunContext.java + LlmExposurePolicy.java — never in the constrained-"
                        + "DataManager row-data path (BuiltInDataTools). Offenders: %s", offenders)
                .isEmpty();
    }

    @Test
    void builtInDataToolsHasNoPerTurnCacheSymbol() throws IOException {
        Path builtInDataTools = findMainJavaRoot()
                .resolve(Paths.get("com", "vn", "agent", "tools", "BuiltInDataTools.java"));
        assertThat(Files.isRegularFile(builtInDataTools))
                .as("BuiltInDataTools.java must exist at the expected path: %s", builtInDataTools)
                .isTrue();

        String content = Files.readString(builtInDataTools);
        assertThat(CACHE_SYMBOL.matcher(content).find())
                .as("D-09: BuiltInDataTools (constrained-DataManager row path) must NOT reference the "
                        + "per-turn cache symbol — AccessManager/DataManager stay authoritative for "
                        + "row access (T-18-06)")
                .isFalse();
    }

    /**
     * Locate {@code src/main/java} from the test working directory (mirrors the proven idiom in
     * {@code AiSettingsChangedEventListenerInvariantTest}). Tests run with the working dir set to
     * the {@code ai-agent} subproject, so {@code src/main/java} resolves directly; fallbacks cover
     * a root-dir launch.
     */
    private Path findMainJavaRoot() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get("src", "main", "java"));
        candidates.add(Paths.get("ai-agent", "src", "main", "java"));
        candidates.add(Paths.get("..", "src", "main", "java"));
        candidates.add(Paths.get("ai-agent", "ai-agent", "src", "main", "java"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        throw new IllegalStateException(
                "Could not locate ai-agent/src/main/java from working dir "
                        + Paths.get("").toAbsolutePath()
                        + "; tried: "
                        + candidates.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
}
