package com.vn.agent.tools.mutation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-07C Task 2 — source-level grep-style invariants for the mutation tool surface.
 *
 * <p>These tests exist because the must-haves contract for Plan 11-07C is largely a set of
 * grep-level invariants ({@code auditWriter.writeToolCall} isolation, COMMIT_FAILED caption
 * wording) that are easier to enforce on the source files directly than to retro-fit into a
 * full Spring integration test. Pure-unit JUnit; no Spring context, no mocks.
 *
 * <p><b>Invariants under test:</b>
 * <ul>
 *     <li>{@code auditWriter.writeToolCall(} appears in exactly one Java file under
 *         {@code com.vn.agent.tools.mutation}: {@link MutationCommitCoordinator}, and only
 *         inside the {@code safeWriteAudit} method body. Every other mutation-package call
 *         site must go through {@code safeWriteAudit}.</li>
 *     <li>{@code BuiltInMutationTools} contains zero direct {@code auditWriter.} usage so
 *         that Plan 11-07's "BuiltInMutationTools is a thin orchestration bean" contract
 *         cannot regress quietly.</li>
 *     <li>The {@code COMMIT_FAILED} captions in both {@code messages_en.properties} and
 *         {@code messages_vi.properties} carry the "commit outcome unknown" wording
 *         mandated by the must-haves and never claim the database commit failed.</li>
 * </ul>
 *
 * <p>Project root is resolved relative to the JVM working directory so the test runs the
 * same way under {@code ./gradlew :ai-agent:test} (working dir = {@code ai-agent/ai-agent})
 * and an IDE run from the module root.
 */
class MutationToolInvariantsTest {

    private static final Path MODULE_ROOT = resolveModuleRoot();

    private static final Path MUTATION_PACKAGE = MODULE_ROOT
            .resolve("src/main/java/com/vn/agent/tools/mutation");

    private static final Path COMMIT_COORDINATOR_FILE = MUTATION_PACKAGE
            .resolve("MutationCommitCoordinator.java");

    private static final Path BUILT_IN_MUTATION_TOOLS_FILE = MUTATION_PACKAGE
            .resolve("BuiltInMutationTools.java");

    private static final Path MESSAGES_EN = MODULE_ROOT
            .resolve("src/main/resources/com/vn/agent/messages_en.properties");

    private static final Path MESSAGES_VI = MODULE_ROOT
            .resolve("src/main/resources/com/vn/agent/messages_vi.properties");

    /**
     * Plan 11-07C must-have: "{@code auditWriter.writeToolCall} must appear nowhere else in
     * BuiltInMutationTools." This test is the structural enforcement of that rule.
     */
    @Test
    void auditWriter_writeToolCall_appearsOnlyInsideSafeWriteAudit() throws IOException {
        assertThat(MUTATION_PACKAGE).exists();

        try (Stream<Path> files = Files.walk(MUTATION_PACKAGE)) {
            List<Path> javaFiles = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (file.equals(COMMIT_COORDINATOR_FILE)) {
                    // Allowed: exactly the one call inside safeWriteAudit. Verified below.
                    continue;
                }
                assertThat(src)
                        .as("Plan 11-07C invariant: auditWriter.writeToolCall must only "
                                + "be invoked from MutationCommitCoordinator.safeWriteAudit. "
                                + "Found a direct call site in %s", MODULE_ROOT.relativize(file))
                        .doesNotContain("auditWriter.writeToolCall(");
            }
        }
    }

    /**
     * Plan 11-07C must-have: the single allowed {@code auditWriter.writeToolCall} call site
     * lives inside {@link MutationCommitCoordinator#safeWriteAudit}. Anchored on the method
     * signature plus the call so a stray helper added to the same class would still fail.
     */
    @Test
    void commitCoordinator_writeToolCall_isInsideSafeWriteAuditMethod() throws IOException {
        String src = Files.readString(COMMIT_COORDINATOR_FILE, StandardCharsets.UTF_8);

        // Exactly one call site in this file.
        long callSiteCount = countOccurrences(src, "auditWriter.writeToolCall(");
        assertThat(callSiteCount)
                .as("MutationCommitCoordinator must contain exactly one auditWriter.writeToolCall(...) call site")
                .isEqualTo(1L);

        // The call site must sit inside the public safeWriteAudit method body. We anchor on
        // the method signature line and require the call to appear AFTER it.
        int safeWriteAuditIdx = src.indexOf("public void safeWriteAudit(");
        assertThat(safeWriteAuditIdx)
                .as("safeWriteAudit method signature must exist in MutationCommitCoordinator")
                .isGreaterThan(0);

        int writeToolCallIdx = src.indexOf("auditWriter.writeToolCall(");
        assertThat(writeToolCallIdx)
                .as("auditWriter.writeToolCall call site must appear after the safeWriteAudit signature")
                .isGreaterThan(safeWriteAuditIdx);
    }

    /**
     * Plan 11-07C must-have / acceptance criterion: BuiltInMutationTools must remain a thin
     * orchestration bean. No direct {@code auditWriter} field access either — every audit
     * write goes through {@code mutationCommitCoordinator.safeWriteAudit(...)}.
     */
    @Test
    void builtInMutationTools_hasNoDirectAuditWriterUsage() throws IOException {
        String src = Files.readString(BUILT_IN_MUTATION_TOOLS_FILE, StandardCharsets.UTF_8);
        assertThat(src)
                .as("BuiltInMutationTools must not call auditWriter directly; route through safeWriteAudit")
                .doesNotContain("auditWriter.writeToolCall(");
        // Also reject any private auditWriter field; the bean must not even hold a reference.
        assertThat(src)
                .as("BuiltInMutationTools must not hold a direct AuditWriter dependency")
                .doesNotContain(" AuditWriter ");
    }

    /**
     * Plan 11-07C must-have: COMMIT_FAILED captions must not say "database commit failed" in
     * any locale. The host save returned but the dedup-row finalization failed — the
     * database commit outcome is genuinely unknown, not known-failed.
     */
    @Test
    void commitFailedCaptions_doNotClaimDatabaseCommitFailed() throws IOException {
        String enBundle = Files.readString(MESSAGES_EN, StandardCharsets.UTF_8);
        String viBundle = Files.readString(MESSAGES_VI, StandardCharsets.UTF_8);

        Pattern forbidden = Pattern.compile("database\\s+commit\\s+failed",
                Pattern.CASE_INSENSITIVE);
        assertThat(forbidden.matcher(enBundle).find())
                .as("messages_en.properties must not contain 'database commit failed' wording")
                .isFalse();
        assertThat(forbidden.matcher(viBundle).find())
                .as("messages_vi.properties must not contain 'database commit failed' wording")
                .isFalse();

        // Positive assertion: the caption mandated by the must-haves is in place.
        assertThat(enBundle)
                .as("messages_en.properties must keep the 'Commit outcome unknown' caption for COMMIT_FAILED")
                .contains("com.vn.agent.entity/AiToolCallOutcome.COMMIT_FAILED=Commit outcome unknown");
        assertThat(viBundle)
                .as("messages_vi.properties must keep the localized 'Commit outcome unknown' caption for COMMIT_FAILED")
                .contains("com.vn.agent.entity/AiToolCallOutcome.COMMIT_FAILED=Chưa rõ kết quả commit");
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Resolve the {@code ai-agent/ai-agent} Gradle module root regardless of whether the
     * test is launched from the repo root, the gradle root, or the module itself. The
     * Path operations are read-only; no environment mutation.
     */
    private static Path resolveModuleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        // Common cases:
        //   1) cwd already IS the module root (./gradlew :ai-agent:test from ai-agent/)
        //   2) cwd is one level above (run from repo root)
        Path[] candidates = new Path[] {
                cwd,
                cwd.resolve("ai-agent"),
                cwd.resolve("ai-agent").resolve("ai-agent"),
                cwd.getParent() == null ? cwd : cwd.getParent(),
        };
        for (Path candidate : candidates) {
            if (candidate != null
                    && Files.exists(candidate.resolve("src/main/java/com/vn/agent/tools/mutation"))) {
                return candidate;
            }
        }
        // Fall back to cwd; the @Test assertions will surface a clear missing-path failure.
        return cwd;
    }
}
