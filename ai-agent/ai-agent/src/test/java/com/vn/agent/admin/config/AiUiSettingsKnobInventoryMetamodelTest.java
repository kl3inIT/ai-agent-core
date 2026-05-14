package com.vn.agent.admin.config;

import com.vn.agent.admin.config.dto.AiKnobRow;
import com.vn.agent.admin.config.dto.AiSecretIndicatorRow;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 16-08 Task 2 — UAT Gap 1 regression gate.
 *
 * <p>Plan 16-06's {@code KnobInventoryClassificationTest} reflectively walked the
 * @KnobMetadata annotation on the 10 carrier records but NEVER read the
 * {@code ai-ui-settings-detail-view.xml} descriptor. The bug that broke UAT
 * test 4 was a nested-class binding in the descriptor's {@code <collection
 * class=...>} attribute pointing at a non-{@code @JmixEntity} record — the
 * Jmix metamodel could not resolve it at view-init time and the view crashed
 * with {@code IllegalArgumentException: MetaClass not found ...}.</p>
 *
 * <p>This test fills the explicit gap with three legs:</p>
 * <ol>
 *   <li>{@link #aiKnobRowCarriesJmixEntityAndJmixId()} — asserts the new
 *       top-level DTO carries {@code @JmixEntity} + {@code @JmixId} (so the
 *       Jmix metamodel CAN resolve it) and does NOT carry {@code @Entity} /
 *       {@code @Store} (so it is a non-persistent DTO, not a JPA entity).</li>
 *   <li>{@link #aiSecretIndicatorRowCarriesJmixEntityAndJmixId()} — same +
 *       additional SEC-08 invariant that no field name matches
 *       {@code (?i).*(value|raw|secret).*}.</li>
 *   <li>{@link #viewDescriptorBindsTopLevelJmixEntityClassesOnly()} — walks
 *       {@code ai-ui-settings-detail-view.xml} via {@code Files.walk} (same
 *       discovery pattern as {@code SecretRedactionInvariantsTest}) and
 *       asserts every {@code <collection class="...">} attribute is (a) a
 *       loadable class, (b) carries {@code @JmixEntity}, (c) contains no
 *       inner-class {@code $} separator.</li>
 * </ol>
 *
 * <p><b>Rule 3 workaround</b>: pure-JUnit + reflection + filesystem scan, no
 * Spring context. The ai-agent module's full-context boot is blocked by the
 * Phase 11/13 atmosphere-runtime regression that every prior Phase 16 plan
 * worked around the same way. A future hardening pass that unblocks
 * {@code @SpringBootTest} can port the assertion to read the live
 * {@code io.jmix.core.Metadata#getSession()} metaclass set; the contract is
 * the same.</p>
 */
@Tag("unit")
class AiUiSettingsKnobInventoryMetamodelTest {

    private static Path locate(String... segments) {
        // Probe candidate cwds — test runners may launch from project root or module dir.
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(segments[0], java.util.Arrays.copyOfRange(segments, 1, segments.length)));
        candidates.add(Paths.get("ai-agent").resolve(Paths.get(segments[0],
                java.util.Arrays.copyOfRange(segments, 1, segments.length))));
        candidates.add(Paths.get("ai-agent", "ai-agent").resolve(Paths.get(segments[0],
                java.util.Arrays.copyOfRange(segments, 1, segments.length))));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c.toAbsolutePath();
            }
        }
        return candidates.get(0).toAbsolutePath();
    }

    private static final Path VIEWS_ROOT =
            locate("src", "main", "resources", "com", "vn", "agent", "view");

    @Test
    void aiKnobRowCarriesJmixEntityAndJmixId() {
        assertTrue(AiKnobRow.class.isAnnotationPresent(JmixEntity.class),
                "AiKnobRow MUST carry @JmixEntity so the Jmix metamodel resolves it.");
        assertFalse(AiKnobRow.class.isAnnotationPresent(Entity.class),
                "AiKnobRow is a non-persistent DTO — MUST NOT carry @jakarta.persistence.Entity.");
        assertFalse(AiKnobRow.class.isAnnotationPresent(Store.class),
                "AiKnobRow is a non-persistent DTO — MUST NOT carry @Store.");
        assertTrue(hasIdField(AiKnobRow.class),
                "AiKnobRow MUST declare an id-bearing field (@JmixId OR field named 'id' " +
                        "with @JmixGeneratedValue) so the Jmix metamodel can identify rows.");
    }

    @Test
    void aiSecretIndicatorRowCarriesJmixEntityAndJmixId() {
        assertTrue(AiSecretIndicatorRow.class.isAnnotationPresent(JmixEntity.class),
                "AiSecretIndicatorRow MUST carry @JmixEntity so the Jmix metamodel resolves it.");
        assertFalse(AiSecretIndicatorRow.class.isAnnotationPresent(Entity.class),
                "AiSecretIndicatorRow is a non-persistent DTO — MUST NOT carry @jakarta.persistence.Entity.");
        assertFalse(AiSecretIndicatorRow.class.isAnnotationPresent(Store.class),
                "AiSecretIndicatorRow is a non-persistent DTO — MUST NOT carry @Store.");
        assertTrue(hasIdField(AiSecretIndicatorRow.class),
                "AiSecretIndicatorRow MUST declare an id-bearing field (@JmixId OR field named 'id' " +
                        "with @JmixGeneratedValue) so the Jmix metamodel can identify rows.");

        // SEC-08 — defense-in-depth: no field whose name suggests a raw secret payload.
        Pattern forbidden = Pattern.compile("(?i).*(value|raw|secret).*");
        List<String> offenders = new ArrayList<>();
        for (Field field : AiSecretIndicatorRow.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (forbidden.matcher(field.getName()).matches()) {
                offenders.add(field.getName());
            }
        }
        assertTrue(offenders.isEmpty(),
                "AiSecretIndicatorRow MUST NOT declare any field whose name matches " +
                        "(?i).*(value|raw|secret).* — SEC-08 invariant. Offenders: " + offenders);
    }

    @Test
    void viewDescriptorBindsTopLevelJmixEntityClassesOnly() throws IOException {
        // Use Files.walk for file-discovery consistency with SecretRedactionInvariantsTest
        // (Plan 16-06 sibling — keeps the two tests structurally identical for future
        // maintainer recognition).
        Path target = null;
        if (Files.exists(VIEWS_ROOT)) {
            try (Stream<Path> walk = Files.walk(VIEWS_ROOT)) {
                target = walk.filter(p -> p.getFileName().toString().equals("ai-ui-settings-detail-view.xml"))
                        .findFirst()
                        .orElse(null);
            }
        }
        assertNotNull(target,
                "ai-ui-settings-detail-view.xml MUST be discoverable under " + VIEWS_ROOT);

        String body = Files.readString(target, StandardCharsets.UTF_8);
        Pattern collectionClass = Pattern.compile(
                "<collection\\s+[^>]*class\\s*=\\s*\"([^\"]+)\"");
        Matcher m = collectionClass.matcher(body);

        Set<String> bound = new HashSet<>();
        while (m.find()) {
            bound.add(m.group(1));
        }

        assertFalse(bound.isEmpty(),
                "Expected at least one <collection class=...> binding in " + target);

        for (String fqcn : bound) {
            // (c) — inner-class binding gate: no $ separator.
            // This single check would have caught the original Plan 16-06 bug at
            // the syntactic level — the nested record was bound as
            // "com.vn.agent.admin.config.KnobInventory$KnobRow".
            assertFalse(fqcn.contains("$"),
                    "View descriptor <collection class=\"" + fqcn + "\"> binds to an inner " +
                            "class. Inner classes are not registered Jmix metaclasses and the view " +
                            "will crash at open with IllegalArgumentException: MetaClass not found. " +
                            "Promote to a top-level @JmixEntity DTO.");

            // (a) — class is loadable on the classpath.
            Class<?> resolved;
            try {
                resolved = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(
                        "View descriptor <collection class=\"" + fqcn + "\"> references a class " +
                                "that is not on the classpath.", e);
            }

            // (b) — class carries @JmixEntity.
            assertTrue(resolved.isAnnotationPresent(JmixEntity.class),
                    "View descriptor <collection class=\"" + fqcn + "\"> binds to a class that " +
                            "does NOT carry @JmixEntity. The Jmix metamodel cannot resolve it and " +
                            "the view will crash at open.");
        }

        // For the current descriptor: exactly the two new DTO classes.
        Set<String> expected = Set.of(
                "com.vn.agent.admin.config.dto.AiKnobRow",
                "com.vn.agent.admin.config.dto.AiSecretIndicatorRow");
        assertEquals(expected, bound,
                "AI UI Settings descriptor <collection> bindings must be exactly the two new " +
                        "top-level Jmix DTO classes shipped in Plan 16-08.");
    }

    /**
     * Returns true iff {@code clazz} declares an id-bearing field — either a field
     * annotated {@code @JmixId}, or a field named {@code id} annotated
     * {@code @JmixGeneratedValue} (the Jmix non-persistent DTO id contract).
     */
    private static boolean hasIdField(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(JmixId.class)) {
                return true;
            }
            if ("id".equals(f.getName()) && f.isAnnotationPresent(JmixGeneratedValue.class)) {
                return true;
            }
        }
        return false;
    }
}
