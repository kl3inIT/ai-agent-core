package com.vn.agent.taskfile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-16 STATIC enforcement (Phase 13 Plan 13-05 Task 1A): asserts that no source
 * file under {@code src/main/java/com/vn/agent/taskfile/**} references any RAG /
 * VectorStore / Ingester / DocumentReader API. Phase 13's task-file pathway is
 * structurally disjoint from KB ingestion (see {@code package-info.java}).
 *
 * <p>The static scan is the first half of TEST-16; the runtime invocation half
 * lives in {@code AiTaskFileNoVectorStoreInvocationTest}. Together they enforce
 * <b>both</b> source-level disjointness (this test) <b>and</b> runtime
 * data-flow disjointness (the runtime test) — a contributor introducing
 * {@code import org.springframework.ai.vectorstore.VectorStore;} into any
 * {@code taskfile/} source file fails this CI gate.
 *
 * <p><b>REVIEWS HIGH-8 allowlist:</b> {@code package-info.java} intentionally
 * names every forbidden token inside its JavaDoc {@code DO NOT REFERENCE}
 * block (so the package's structural invariant is documented at the source
 * itself). This scanner strips that JavaDoc range before checking — the
 * allowlist is therefore narrow: it begins at the first occurrence of
 * {@code DO NOT REFERENCE} and ends at the next {@code *&#47;}, so anything
 * outside that JavaDoc block is still scanned for forbidden tokens.
 */
class TaskFileNoVectorStoreSourceScannerTest {

    /**
     * Tokens that Phase 13's task-file package MUST NOT reference (TEST-16).
     * Each entry corresponds to a key API in the RAG / KB-ingestion code path.
     */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "IngesterManager",
            "VectorStore",
            "RetrievalAugmentationAdvisor",
            "TokenTextSplitter",
            "DocumentReader",
            "com.vn.agent.rag."
    );

    /**
     * Filesystem-relative root the scanner walks. Tests for the {@code ai-agent}
     * Gradle subproject run from {@code ai-agent/ai-agent}, so this relative
     * path resolves correctly. Computed dynamically so a project rename does
     * not silently break the test.
     */
    private static final Path TASKFILE_SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "vn", "agent", "taskfile");

    @Test
    void noForbiddenTokenReferencedFromTaskfilePackage() throws IOException {
        assertThat(Files.exists(TASKFILE_SOURCE_ROOT))
                .as("taskfile source package must exist at %s (TEST-16 invariant target)",
                        TASKFILE_SOURCE_ROOT)
                .isTrue();

        try (Stream<Path> paths = Files.walk(TASKFILE_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(this::assertFileFreeOfForbiddenTokens);
        }
    }

    private void assertFileFreeOfForbiddenTokens(Path javaFile) {
        String body;
        try {
            body = Files.readString(javaFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file: " + javaFile, e);
        }

        String scanned = stripDoNotReferenceJavadoc(body);
        for (String token : FORBIDDEN_TOKENS) {
            assertThat(scanned)
                    .as("File %s must not reference forbidden token %s (TEST-16 invariant; "
                            + "task-file pathway is structurally disjoint from KB ingestion). "
                            + "If the reference is documentation, place it inside the "
                            + "package-info.java DO-NOT-REFERENCE JavaDoc block (REVIEWS HIGH-8).",
                            javaFile, token)
                    .doesNotContain(token);
        }
    }

    /**
     * Strips the {@code DO NOT REFERENCE} JavaDoc block from {@code body} so
     * the scanner does not flag {@code package-info.java}'s own structural
     * documentation. Allowlist range: from the first occurrence of
     * {@code DO NOT REFERENCE} up to (and including) the matching closing
     * JavaDoc {@code *&#47;}. If no {@code DO NOT REFERENCE} marker exists the
     * body is returned unchanged.
     */
    private static String stripDoNotReferenceJavadoc(String body) {
        int startIdx = body.indexOf("DO NOT REFERENCE");
        if (startIdx < 0) {
            return body;
        }
        int closeIdx = body.indexOf("*/", startIdx);
        if (closeIdx < 0) {
            return body;
        }
        // Drop everything from "DO NOT REFERENCE" through the closing "*/" (inclusive).
        return body.substring(0, startIdx) + body.substring(closeIdx + 2);
    }
}
