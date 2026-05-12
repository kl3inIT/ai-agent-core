package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 OBS-04 structural gate (T-15-E2) — Phase 15 adds NO persisted state.
 *
 * <p><strong>15-REVIEWS point #10</strong> — this asserts the negative WITHOUT pinning a brittle
 * global {@code @Entity} count (an adjacent phase could add an entity concurrently). Instead:
 * <ol>
 *   <li>no Liquibase changelog file under {@code **&#47;liquibase/**} has a Phase-15-related name
 *       ({@code 15-} / {@code phase-15} / {@code sidebar} / {@code observability}); and</li>
 *   <li>neither the root {@code agentstore-changelog.xml} nor {@code changelog.xml} contains an
 *       {@code <include file=...>} referencing a Phase-15 changelog.</li>
 * </ol>
 * Belt-and-braces: no entity source file under {@code com/vn/agent/entity} declares a
 * {@code @Table(name=...)} containing {@code TURN} / {@code ACTIVITY} / {@code DISCLOSURE} — but
 * (1)+(2) are the load-bearing assertions.
 *
 * <p>Plain JUnit — operates on the source tree (with a build-output fallback). No Spring context.
 */
class NoNewPersistedStateTest {

    /** A Phase-15-related changelog filename token. Case-insensitive. */
    private static final Pattern PHASE_15_CHANGELOG_NAME = Pattern.compile(
            "(^|[^0-9])15-|phase-?15|sidebar|observability", Pattern.CASE_INSENSITIVE);

    /** A {@code @Table(name="...")} value containing a per-turn-state token. Case-insensitive. */
    private static final Pattern FORBIDDEN_TABLE_NAME = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final List<String> FORBIDDEN_TABLE_TOKENS = List.of("TURN", "ACTIVITY", "DISCLOSURE");

    private static final Pattern INCLUDE_FILE = Pattern.compile(
            "<include\\s+file\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    @Test
    void noPhase15NamedLiquibaseChangelogFileExists() throws IOException {
        Path liquibaseRoot = firstExistingDir(
                "ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase",
                "src/main/resources/com/vn/agent/liquibase");
        assertThat(liquibaseRoot)
                .as("the agentstore liquibase directory must exist")
                .isNotNull();

        try (Stream<Path> walk = Files.walk(liquibaseRoot)) {
            List<Path> phase15Named = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .filter(p -> PHASE_15_CHANGELOG_NAME.matcher(p.getFileName().toString()).find())
                    .toList();
            assertThat(phase15Named)
                    .as("OBS-04: Phase 15 must not add a Phase-15-named Liquibase changelog file "
                            + "(no entity/table/DDL); found: %s", phase15Named)
                    .isEmpty();
        }
    }

    @Test
    void rootChangelogsHaveNoPhase15Include() throws IOException {
        // The agentstore changelog uses <includeAll path=...> (a new .xml in the directory would be
        // picked up — covered by noPhase15NamedLiquibaseChangelogFileExists). It also <include file=>'s
        // upstream changelogs; assert none of those <include file=> entries names a Phase-15 changelog.
        for (String rel : new String[]{
                "ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml",
                "src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml",
                // the jmix-app master changelogs (the host wiring) — assert no Phase-15 include there either
                "jmix-app/src/main/resources/com/vn/jmixapp/liquibase/agentstore-changelog.xml",
                "jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml"}) {
            Path p = firstExisting(rel);
            if (p == null) {
                continue; // jmix-app paths only exist when run from the repo root
            }
            String xml = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = INCLUDE_FILE.matcher(xml);
            while (m.find()) {
                String included = m.group(1);
                assertThat(PHASE_15_CHANGELOG_NAME.matcher(included).find())
                        .as("OBS-04: %s must not <include file=> a Phase-15 changelog; found include=<%s>",
                                p, included)
                        .isFalse();
            }
        }
    }

    @Test
    void noEntityClassDeclaresAPerTurnTableName() throws IOException {
        Path entityDir = firstExistingDir(
                "ai-agent/ai-agent/src/main/java/com/vn/agent/entity",
                "src/main/java/com/vn/agent/entity");
        assertThat(entityDir)
                .as("the entity package directory must exist")
                .isNotNull();

        try (Stream<Path> walk = Files.walk(entityDir)) {
            List<Path> javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String src = Files.readString(javaFile, StandardCharsets.UTF_8);
                Matcher m = FORBIDDEN_TABLE_NAME.matcher(src);
                while (m.find()) {
                    String tableName = m.group(1).toUpperCase(Locale.ROOT);
                    for (String token : FORBIDDEN_TABLE_TOKENS) {
                        assertThat(tableName.contains(token))
                                .as("OBS-04: %s declares @Table(name=\"%s\") containing forbidden "
                                        + "per-turn-state token '%s' — Phase 15 adds no entity/table",
                                        javaFile, m.group(1), token)
                                .isFalse();
                    }
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static Path firstExisting(String... relativePaths) {
        for (String rel : relativePaths) {
            for (Path candidate : new Path[]{
                    Path.of(rel),
                    Path.of(System.getProperty("user.dir")).resolve(rel).normalize()
            }) {
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Path firstExistingDir(String... relativePaths) {
        Path p = firstExisting(relativePaths);
        return (p != null && Files.isDirectory(p)) ? p : null;
    }
}
