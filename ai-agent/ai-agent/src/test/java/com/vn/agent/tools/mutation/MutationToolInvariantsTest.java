package com.vn.agent.tools.mutation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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

    /**
     * Plan 17-01 (MUT-15): the canonical fail-closed gate sequence is extracted into
     * {@code MutationGateChain} by Plan 04. This file does not yet exist — the gate-order /
     * save-after-all-gates / no-{@code @Transactional} assertions below are intentionally RED
     * until Plan 04 commits it.
     */
    private static final Path MUTATION_GATE_CHAIN_FILE = MUTATION_PACKAGE
            .resolve("MutationGateChain.java");

    /**
     * Plan 17-01 (MUT-16): the to-one FK batch-load path lives in
     * {@code MutationAttributeBinder}. The forbidden-token scan below proves the FK path uses
     * only the constrained {@code dataManager.load(class).ids(...)} / {@code .id(...)} API —
     * never {@code UnconstrainedDataManager} and never raw JPQL — so row-level security is
     * preserved (D-09). This assertion is GREEN today and must STAY green after Plan 03.
     */
    private static final Path MUTATION_ATTRIBUTE_BINDER_FILE = MUTATION_PACKAGE
            .resolve("MutationAttributeBinder.java");

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

    // ------------------------------------------------------------------------
    // Plan 17-01 (MUT-15) — MutationGateChain gate-order + save-after-all-gates +
    // no-@Transactional reflection invariants. Intentionally RED until Plan 04.
    // ------------------------------------------------------------------------

    /**
     * The eight distinct gate-method invocation tokens in canonical fail-closed order, as the
     * {@code MutationGateChain.execute(...)} dispatch spine must invoke them (Plan 04). The
     * order encodes the security contract: role marker → exposure/entity resolution →
     * AccessManager entity+attribute authorization → idempotency reservation → literal
     * coercion → MutationGuard SPI → host save → finalize. A reordering that lets a save occur
     * before any authorization gate is a fail-closed regression (T-17-01).
     *
     * <p>Tokens are matched as the leading substring of each private-method invocation (the
     * trailing {@code (} anchors them to a call site rather than a comment/Javadoc mention).
     */
    private static final List<String> GATE_TOKENS_IN_CANONICAL_ORDER = List.of(
            "enforceRole(",
            "resolve(",
            "authorize(",
            "reserve(",
            "coerce(",
            "guard(",
            "save(",
            "finalize(");

    /** The transactional save delegate the chain crosses AFTER every gate (fail-closed). */
    private static final String SAVE_DELEGATE_TOKEN = "mutationSaveExecutor.";

    private static final String MUTATION_GATE_CHAIN_CLASS = "com.vn.agent.tools.mutation.MutationGateChain";

    /**
     * Plan 17-01 (MUT-15): the gate tokens must appear in strictly-increasing source order in
     * {@code MutationGateChain.execute}. Strictly-increasing {@code indexOf} is the structural
     * proxy for "the canonical fail-closed sequence is preserved" (D-14). RED until Plan 04
     * commits {@code MutationGateChain.java}.
     */
    @Test
    void mutationGateChain_gatesAppearInCanonicalOrder() throws IOException {
        assertThat(Files.exists(MUTATION_GATE_CHAIN_FILE))
                .as("MUT-15: MutationGateChain.java must exist — produced by Plan 04 "
                        + "(intentional RED until then). Expected at %s",
                        MODULE_ROOT.relativize(MUTATION_GATE_CHAIN_FILE))
                .isTrue();

        String src = Files.readString(MUTATION_GATE_CHAIN_FILE, StandardCharsets.UTF_8);

        int previousIndex = -1;
        String previousToken = "<start>";
        for (String token : GATE_TOKENS_IN_CANONICAL_ORDER) {
            int index = src.indexOf(token);
            assertThat(index)
                    .as("MUT-15: gate token '%s' must appear in MutationGateChain (Plan 04)", token)
                    .isGreaterThanOrEqualTo(0);
            assertThat(index)
                    .as("MUT-15: gate token '%s' (index %d) must appear AFTER '%s' (index %d) — "
                            + "the canonical fail-closed gate order is structurally enforced (D-14)",
                            token, index, previousToken, previousIndex)
                    .isGreaterThan(previousIndex);
            previousIndex = index;
            previousToken = token;
        }
    }

    /**
     * Plan 17-01 (MUT-15): the transactional save delegate ({@code mutationSaveExecutor.}) must
     * appear AFTER every gate token — proving "every gate throws before the transactional save
     * crosses the boundary" (fail-closed; T-17-01). RED until Plan 04.
     */
    @Test
    void mutationGateChain_saveTokenAppearsAfterAllGateTokens() throws IOException {
        assertThat(Files.exists(MUTATION_GATE_CHAIN_FILE))
                .as("MUT-15: MutationGateChain.java must exist — produced by Plan 04 "
                        + "(intentional RED until then). Expected at %s",
                        MODULE_ROOT.relativize(MUTATION_GATE_CHAIN_FILE))
                .isTrue();

        String src = Files.readString(MUTATION_GATE_CHAIN_FILE, StandardCharsets.UTF_8);

        int saveDelegateIndex = src.indexOf(SAVE_DELEGATE_TOKEN);
        assertThat(saveDelegateIndex)
                .as("MUT-15: the transactional save delegate '%s' must appear in MutationGateChain "
                        + "(Plan 04)", SAVE_DELEGATE_TOKEN)
                .isGreaterThanOrEqualTo(0);

        // Every gate token EXCEPT the save/finalize spine tokens must precede the delegate
        // crossing. The gate tokens up to (and including) "guard(" are the authorization gates;
        // "save(" / "finalize(" are the spine itself. We assert the delegate sits after the last
        // authorization gate, i.e. after "guard(".
        int lastGateIndex = src.indexOf("guard(");
        assertThat(lastGateIndex)
                .as("MUT-15: final authorization gate 'guard(' must exist in MutationGateChain (Plan 04)")
                .isGreaterThanOrEqualTo(0);
        assertThat(saveDelegateIndex)
                .as("MUT-15: the transactional save delegate '%s' (index %d) must appear AFTER the "
                        + "last authorization gate 'guard(' (index %d) — fail-closed: every gate "
                        + "throws before the save crosses the @Transactional boundary",
                        SAVE_DELEGATE_TOKEN, saveDelegateIndex, lastGateIndex)
                .isGreaterThan(lastGateIndex);
    }

    /**
     * Plan 17-01 (MUT-15): {@code MutationGateChain} must carry NO {@code @Transactional} — not
     * on the class and not on any declared method (D-14: by REFLECTION, never a regex of the
     * source). The single transactional boundary is {@code MutationSaveExecutor.save}; the chain
     * is a non-transactional orchestrator so every gate throws OUTSIDE the transaction. RED until
     * Plan 04 (class does not exist yet → {@link ClassNotFoundException}).
     */
    @Test
    void mutationGateChain_carriesNoTransactionalAnnotation() {
        Class<?> chainClass;
        try {
            chainClass = Class.forName(MUTATION_GATE_CHAIN_CLASS);
        } catch (ClassNotFoundException e) {
            fail("MUT-15: %s must exist — produced by Plan 04 (intentional RED until then). "
                    + "Reflection-based @Transactional-absence check cannot run until the class "
                    + "is on the classpath.", MUTATION_GATE_CHAIN_CLASS);
            return; // unreachable — fail(...) throws
        }

        Class<? extends java.lang.annotation.Annotation> transactional =
                org.springframework.transaction.annotation.Transactional.class;

        assertThat(chainClass.isAnnotationPresent(transactional))
                .as("MUT-15: MutationGateChain must NOT be @Transactional at the class level — "
                        + "the only transactional boundary is MutationSaveExecutor.save (D-14)")
                .isFalse();

        for (Method method : chainClass.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(transactional))
                    .as("MUT-15: MutationGateChain method '%s' must NOT be @Transactional — gates "
                            + "must throw outside the transaction (D-14)", method.getName())
                    .isFalse();
        }
    }

    /**
     * Plan 17-01 (MUT-16): the to-one FK batch-load path in {@code MutationAttributeBinder} must
     * use ONLY the constrained {@code dataManager.load(class).ids(...)} / {@code .id(...)} API.
     * It must NOT switch to {@code UnconstrainedDataManager} or raw JPQL
     * ({@code loadValue}/{@code loadValues}/{@code .query(}) — either would bypass row-level
     * security (D-09, T-17-02). GREEN today; must STAY green after Plan 03's batch-load refactor.
     */
    @Test
    void mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql() throws IOException {
        assertThat(MUTATION_ATTRIBUTE_BINDER_FILE)
                .as("MUT-16: MutationAttributeBinder.java must exist on the FK-binding path")
                .exists();

        String src = Files.readString(MUTATION_ATTRIBUTE_BINDER_FILE, StandardCharsets.UTF_8);

        assertThat(src)
                .as("MUT-16: MutationAttributeBinder FK path must NOT use UnconstrainedDataManager "
                        + "(row-level security must still apply — D-09)")
                .doesNotContain("UnconstrainedDataManager");
        assertThat(src)
                .as("MUT-16: MutationAttributeBinder FK path must NOT use raw JPQL loadValues(...)")
                .doesNotContain("loadValues(");
        assertThat(src)
                .as("MUT-16: MutationAttributeBinder FK path must NOT use raw JPQL loadValue(...)")
                .doesNotContain("loadValue(");
        assertThat(src)
                .as("MUT-16: MutationAttributeBinder FK path must NOT use raw JPQL .query(...)")
                .doesNotContain(".query(");
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
