package com.vn.agent.performance;

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
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-05 build-dependency invariant + per-optimization proxy-existence guard.
 *
 * <p>Pure JUnit 5 ({@code @Tag("unit")}, NO {@code @SpringBootTest}) — boots no Spring context, so
 * it sidesteps the pre-existing Phase 11/13 ai-agent module boot regression
 * (atmosphere-runtime / {@code agentstoreEntityManagerFactory}) documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}.</p>
 *
 * <p>Two locked guarantees of the Phase 18 perf pass made machine-checkable (T-18-15, T-18-SC):</p>
 * <ol>
 *   <li><b>No out-of-scope perf/cache dependency.</b> {@link #buildScriptsDeclareNoBenchmarkOrCacheDependency()}
 *       reads the module build script ({@code ai-agent/ai-agent/ai-agent.gradle}) and the root add-on
 *       build script ({@code ai-agent/build.gradle}) and asserts (case-insensitive) that NEITHER
 *       declares a {@code caffeine}, {@code jmh}, or {@code gatling} token. The only sanctioned cache
 *       primitives in this phase are {@code ConcurrentHashMap} / {@code ThreadLocal} /
 *       {@code ConcurrentMapCacheManager}; the only sanctioned SELECT-count proxy harness is
 *       {@code net.ttddyy:datasource-proxy} (which contains none of the forbidden tokens).</li>
 *   <li><b>Every optimization ships at least one checkable proxy.</b>
 *       {@link #everyPerfOptimizationShipsAtLeastOneProxyTest()} walks the test source root and
 *       asserts each PERF-01..04 proxy test class FILE is present on disk. Their structural presence
 *       enforces the "each change ships a checkable proxy" requirement at the build level.</li>
 * </ol>
 *
 * <p>All filesystem navigation uses {@link java.nio.file.Path} APIs (no hardcoded {@code '/'} or
 * {@code '\\'} string concatenation) so the test is portable across Windows and POSIX runners.</p>
 */
@Tag("unit")
class NoNewPerfDependencyInvariantTest {

    /**
     * Forbidden dependency tokens — adding a benchmark harness (JMH/Gatling) or a third-party cache
     * (Caffeine) would change the runtime/security posture and break the locked Phase 18 boundary.
     */
    private static final List<String> FORBIDDEN_DEPENDENCY_TOKENS = List.of("caffeine", "jmh", "gatling");

    /**
     * The PERF-01..04 proxy test class simple names. Their presence on disk structurally enforces
     * "each optimization ships >=1 checkable proxy". Package-agnostic (matched by file name) so a
     * future package move does not silently defeat the guard.
     *
     * <ul>
     *   <li>PERF-02 (Plan 01, app-wide denylist memo): {@code LlmExposureDenylistMemoTest},
     *       {@code ExposureCacheEventSubscriptionInvariantTest}</li>
     *   <li>PERF-01 (Plan 02, per-turn RunContext memo): {@code LlmExposurePerTurnMemoTest},
     *       {@code PerTurnCacheBoundaryInvariantTest}</li>
     *   <li>PERF-03 (Plan 03, RAG filter built once per retrieval):
     *       {@code RetrievalFilterBuilderBuildOncePerRetrievalTest}</li>
     *   <li>PERF-04 (Plan 04, task-file media encode once per turn):
     *       {@code TaskFileMediaEncodeOncePerTurnTest}</li>
     * </ul>
     */
    private static final List<String> REQUIRED_PROXY_TEST_FILES = List.of(
            "LlmExposureDenylistMemoTest.java",
            "ExposureCacheEventSubscriptionInvariantTest.java",
            "LlmExposurePerTurnMemoTest.java",
            "PerTurnCacheBoundaryInvariantTest.java",
            "RetrievalFilterBuilderBuildOncePerRetrievalTest.java",
            "TaskFileMediaEncodeOncePerTurnTest.java");

    @Test
    void buildScriptsDeclareNoBenchmarkOrCacheDependency() throws IOException {
        List<Path> buildScripts = locateBuildScripts();
        assertThat(buildScripts)
                .as("Expected to locate the module build script (ai-agent.gradle) and the root "
                        + "add-on build script (build.gradle); working dir=%s",
                        Paths.get("").toAbsolutePath())
                .hasSizeGreaterThanOrEqualTo(2);

        for (Path script : buildScripts) {
            String content = Files.readString(script).toLowerCase(Locale.ROOT);
            for (String token : FORBIDDEN_DEPENDENCY_TOKENS) {
                assertThat(content)
                        .as("Phase 18 forbids the '%s' dependency (no benchmark harness, no "
                                + "third-party cache — ConcurrentHashMap/ThreadLocal/"
                                + "ConcurrentMapCacheManager only). Found it in build script: %s",
                                token, script.toAbsolutePath())
                        .doesNotContain(token);
            }
        }
    }

    @Test
    void everyPerfOptimizationShipsAtLeastOneProxyTest() throws IOException {
        Path testJavaRoot = findTestJavaRoot();

        TreeSet<String> presentFiles = new TreeSet<>();
        Files.walkFileTree(testJavaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java")) {
                    presentFiles.add(name);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        TreeSet<String> missing = new TreeSet<>();
        for (String required : REQUIRED_PROXY_TEST_FILES) {
            if (!presentFiles.contains(required)) {
                missing.add(required);
            }
        }

        assertThat(missing)
                .as("Every PERF-01..04 optimization must ship at least one checkable proxy test "
                        + "class. Missing proxy file(s) under %s: %s",
                        testJavaRoot.toAbsolutePath(), missing)
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Path resolution helpers (Path APIs only — portable across Windows/POSIX)
    // -----------------------------------------------------------------------

    /**
     * Resolves the module and root Gradle build scripts relative to the test working directory.
     * Tests run with the working dir set to the {@code ai-agent} subproject (so the module script
     * is {@code ai-agent.gradle} and the root script is {@code ../build.gradle}); a walk-up fallback
     * covers a run launched from the repository root or the composite-build root.
     */
    private List<Path> locateBuildScripts() {
        Path moduleRoot = findModuleRoot();
        List<Path> scripts = new ArrayList<>();

        // Module build script lives at <moduleRoot>/ai-agent.gradle.
        Path moduleScript = moduleRoot.resolve("ai-agent.gradle");
        if (Files.isRegularFile(moduleScript)) {
            scripts.add(moduleScript);
        }

        // Root add-on build script lives one level up at <moduleRoot>/../build.gradle.
        Path rootScript = moduleRoot.resolve("..").resolve("build.gradle").normalize();
        if (Files.isRegularFile(rootScript)) {
            scripts.add(rootScript);
        }

        return scripts;
    }

    /**
     * Locate the {@code ai-agent} module root (the directory that contains {@code ai-agent.gradle}
     * and {@code src/main/java}). Probes the common working-dir candidates first, then walks up from
     * the current working directory looking for the module marker.
     */
    private Path findModuleRoot() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(""));                          // wd == module root (typical)
        candidates.add(Paths.get("ai-agent"));                  // wd == composite root
        candidates.add(Paths.get("ai-agent", "ai-agent"));      // wd == repo root
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute.resolve("ai-agent.gradle"))) {
                return absolute;
            }
        }

        // Walk-up fallback: from the working dir, climb until a directory holds ai-agent.gradle.
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("ai-agent.gradle"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }

        throw new IllegalStateException(
                "Could not locate the ai-agent module root (dir containing ai-agent.gradle) from "
                        + "working dir " + Paths.get("").toAbsolutePath());
    }

    /** Locate {@code <moduleRoot>/src/test/java}. */
    private Path findTestJavaRoot() {
        Path candidate = findModuleRoot().resolve(Paths.get("src", "test", "java"));
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Defensive fallback for unusual working dirs.
        List<Path> fallbacks = List.of(
                Paths.get("src", "test", "java"),
                Paths.get("ai-agent", "src", "test", "java"),
                Paths.get("ai-agent", "ai-agent", "src", "test", "java"));
        for (Path fallback : fallbacks) {
            if (Files.isDirectory(fallback)) {
                return fallback.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
                "Could not locate src/test/java from module root; tried "
                        + fallbacks.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
}
