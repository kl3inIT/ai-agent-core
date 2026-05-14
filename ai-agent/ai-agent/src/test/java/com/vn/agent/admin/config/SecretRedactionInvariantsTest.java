package com.vn.agent.admin.config;

import com.vn.agent.admin.config.dto.AiSecretIndicatorRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 16-06 Task 3 — SEC-08 three-legged source-scan invariant.
 *
 * <p>All three legs are pure file-system walks via {@link Files#walk(Path, java.nio.file.FileVisitOption...)};
 * no Spring context required (Rule 3 — consistent with the rest of Phase 16's
 * test workarounds).</p>
 *
 * <p>The legs:</p>
 * <ol>
 *   <li>{@link #noSecretBoundEditable()} — parses every {@code *-view.xml} under
 *       {@code ai-agent/ai-agent/src/main/resources} for any element of a Vaadin
 *       value-bearing component type whose {@code property=} matches the Tier-3
 *       secret-name glob set from {@link AdminSecretPatternProperties#LOCKED_DEFAULT_PATTERNS}.</li>
 *   <li>{@link #noConditionalOnPropertyToggleBoundEditable()} — walks the source
 *       tree for {@code @ConditionalOnProperty(name = "...")} declarations and
 *       asserts no XML {@code property=} binding maps to any of those keys.</li>
 *   <li>{@link #singlePublishSiteForAiSettingsChangedEvent()} — walks every
 *       {@code .java} file under {@code ai-agent/ai-agent/src/main/java} for the
 *       regex {@code publishEvent\s*\(\s*new\s+AiSettingsChangedEvent}; asserts
 *       the matching file set is exactly {@code {AiParametersEntityListener.java,
 *       AiUiSettingsEntityListener.java}}.</li>
 * </ol>
 */
@Tag("unit")
class SecretRedactionInvariantsTest {

    private static Path locate(String... segments) {
        // Probe candidate cwds — test runners may launch from project root or module dir.
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(segments[0], java.util.Arrays.copyOfRange(segments, 1, segments.length)));
        // Inside ai-agent/ai-agent module
        candidates.add(Paths.get("ai-agent").resolve(Paths.get(segments[0],
                java.util.Arrays.copyOfRange(segments, 1, segments.length))));
        // Inside repo root (D:\DTH\ai-agent-core)
        candidates.add(Paths.get("ai-agent", "ai-agent").resolve(Paths.get(segments[0],
                java.util.Arrays.copyOfRange(segments, 1, segments.length))));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c.toAbsolutePath();
            }
        }
        // None present — return the first candidate; downstream caller checks Files.exists.
        return candidates.get(0).toAbsolutePath();
    }

    private static final Path VIEWS_ROOT =
            locate("src", "main", "resources", "com", "vn", "agent", "view");
    private static final Path JAVA_SRC_ROOT =
            locate("src", "main", "java");

    /**
     * Vaadin value-bearing field element names that carry editable {@code property=}
     * bindings in Jmix view descriptors. List sourced from the Jmix 2.8
     * {@code layout.xsd} field-component definitions.
     */
    private static final Set<String> EDITABLE_FIELD_ELEMENTS = Set.of(
            "textField", "textArea", "passwordField", "emailField",
            "comboBox", "multiSelectComboBox", "select", "valuePicker",
            "checkbox", "checkboxGroup", "radioButtonGroup",
            "integerField", "bigDecimalField", "bigIntegerField", "numberField",
            "datePicker", "dateTimePicker", "timePicker"
    );

    @Test
    void noSecretBoundEditable() throws IOException {
        List<Pattern> secretPatterns = compileGlobs(AdminSecretPatternProperties.LOCKED_DEFAULT_PATTERNS);
        // Element-with-property regex — captures element name + property attribute.
        Pattern elementWithProperty = Pattern.compile(
                "<(\\w+)\\b[^>]*\\bproperty\\s*=\\s*\"([^\"]+)\"",
                Pattern.DOTALL);

        List<String> offenders = new ArrayList<>();
        if (Files.exists(VIEWS_ROOT)) {
            try (Stream<Path> walk = Files.walk(VIEWS_ROOT)) {
                walk.filter(p -> p.toString().endsWith(".xml"))
                        .forEach(p -> scanXmlForSecretBinding(p, elementWithProperty, secretPatterns, offenders));
            }
        }
        assertTrue(offenders.isEmpty(),
                "Tier-3 secret-bearing property names must NEVER be bound to editable " +
                        "Vaadin components via property=. Offenders:\n" + String.join("\n", offenders));
    }

    @Test
    void noConditionalOnPropertyToggleBoundEditable() throws IOException {
        // Step 1 — collect every @ConditionalOnProperty FULL key from the source
        // tree. The annotation accepts either name="full.key" or
        // prefix="x.y" + name="leaf"; combine prefix + name into the full key
        // before comparing so bare leaf names like "enabled" (legitimately used
        // on JPA columns like AiExposureRule.enabled) are NOT flagged.
        Pattern annotationBlock = Pattern.compile(
                "@ConditionalOnProperty\\s*\\(([^)]*?)\\)",
                Pattern.DOTALL);
        Pattern prefixAttr = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"");
        Pattern nameAttr = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");
        Set<String> conditionalKeys = new HashSet<>();
        if (Files.exists(JAVA_SRC_ROOT)) {
            try (Stream<Path> walk = Files.walk(JAVA_SRC_ROOT)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            try {
                                String body = Files.readString(p);
                                Matcher block = annotationBlock.matcher(body);
                                while (block.find()) {
                                    String args = block.group(1);
                                    Matcher pm = prefixAttr.matcher(args);
                                    Matcher nm = nameAttr.matcher(args);
                                    String prefix = pm.find() ? pm.group(1) : null;
                                    String name = nm.find() ? nm.group(1) : null;
                                    if (name == null) {
                                        continue;
                                    }
                                    String fullKey = (prefix == null || prefix.isBlank())
                                            ? name
                                            : prefix + "." + name;
                                    conditionalKeys.add(fullKey);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        // Step 2 — assert no XML property= attribute maps to the FULL form of
        // any conditional-on-property key. We compare against the full dotted
        // key + the camelCase form of the full path; bare leaf names like
        // "enabled" are intentionally NOT flagged because JPA columns
        // legitimately use the same leaf name (e.g. AiExposureRule.enabled).
        Set<String> normalisedKeys = new HashSet<>();
        for (String key : conditionalKeys) {
            normalisedKeys.add(key);
            normalisedKeys.add(toCamelCase(key));
        }

        Pattern propertyAttr = Pattern.compile(
                "<(\\w+)\\b[^>]*\\bproperty\\s*=\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
        List<String> offenders = new ArrayList<>();
        if (Files.exists(VIEWS_ROOT)) {
            try (Stream<Path> walk = Files.walk(VIEWS_ROOT)) {
                walk.filter(p -> p.toString().endsWith(".xml"))
                        .forEach(p -> {
                            try {
                                String body = Files.readString(p);
                                Matcher m = propertyAttr.matcher(body);
                                while (m.find()) {
                                    String elementName = m.group(1);
                                    String propertyName = m.group(2);
                                    if (!EDITABLE_FIELD_ELEMENTS.contains(elementName)) {
                                        continue;
                                    }
                                    if (normalisedKeys.contains(propertyName)) {
                                        offenders.add(p.getFileName() + ": <" + elementName +
                                                " property=\"" + propertyName + "\"> (matches a " +
                                                "@ConditionalOnProperty toggle key)");
                                    }
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        assertTrue(offenders.isEmpty(),
                "No editable XML property= binding may match a @ConditionalOnProperty " +
                        "toggle key. Offenders:\n" + String.join("\n", offenders));
    }

    /**
     * Plan 16-08 gap-closure — defense-in-depth invariant for the new
     * {@link AiSecretIndicatorRow} DTO. The previous nested record
     * {@code KnobInventory$SecretIndicatorRow} had this invariant implicitly
     * (records cannot carry hidden fields); migration to a top-level class
     * with explicit declared fields requires an explicit assertion that NO
     * field name suggests a raw-secret payload.
     *
     * <p>The {@code configured} boolean is the indicator itself; field-name
     * regex {@code (?i).*(value|raw|secret).*} catches accidental additions
     * such as {@code rawSecret}, {@code secretValue}, {@code value} that would
     * cross SEC-08's redaction boundary.</p>
     */
    @Test
    void noValueFieldOnSecretIndicatorRow() {
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
                "AiSecretIndicatorRow MUST NOT declare any field whose name " +
                        "matches (?i).*(value|raw|secret).* — SEC-08 invariant. Offenders: " + offenders);
        // Also assert the configured boolean is present (positive invariant —
        // the indicator surface itself must exist).
        boolean hasConfigured = false;
        for (Field f : AiSecretIndicatorRow.class.getDeclaredFields()) {
            if ("configured".equals(f.getName()) && f.getType() == boolean.class) {
                hasConfigured = true;
                break;
            }
        }
        assertFalse(!hasConfigured,
                "AiSecretIndicatorRow MUST declare 'configured' boolean — that is the indicator surface.");
    }

    @Test
    void singlePublishSiteForAiSettingsChangedEvent() throws IOException {
        Pattern publish = Pattern.compile(
                "publishEvent\\s*\\(\\s*new\\s+AiSettingsChangedEvent",
                Pattern.DOTALL);
        Set<String> publishers = new HashSet<>();
        if (Files.exists(JAVA_SRC_ROOT)) {
            try (Stream<Path> walk = Files.walk(JAVA_SRC_ROOT)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            try {
                                String body = Files.readString(p);
                                if (publish.matcher(body).find()) {
                                    publishers.add(p.getFileName().toString());
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        Set<String> expected = Set.of(
                "AiParametersEntityListener.java",
                "AiUiSettingsEntityListener.java");
        assertEquals(expected, publishers,
                "AiSettingsChangedEvent MUST be published only from the two entity " +
                        "listeners (Plan 10-06 R2 single-publish-site invariant).");
    }

    private static void scanXmlForSecretBinding(Path xmlFile, Pattern elementWithProperty,
                                                List<Pattern> secretPatterns, List<String> offenders) {
        try {
            String body = Files.readString(xmlFile);
            Matcher m = elementWithProperty.matcher(body);
            while (m.find()) {
                String elementName = m.group(1);
                String propertyName = m.group(2);
                if (!EDITABLE_FIELD_ELEMENTS.contains(elementName)) {
                    continue;
                }
                for (Pattern secret : secretPatterns) {
                    if (secret.matcher(propertyName).matches()) {
                        offenders.add(xmlFile.getFileName() + ": <" + elementName +
                                " property=\"" + propertyName + "\"> (matches Tier-3 mask)");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Pattern> compileGlobs(List<String> globs) {
        List<Pattern> out = new ArrayList<>();
        for (String glob : globs) {
            StringBuilder sb = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append(".");
                    case '.', '(', ')', '[', ']', '{', '}', '+', '|', '^', '$', '\\' ->
                            sb.append("\\").append(c);
                    default -> sb.append(c);
                }
            }
            sb.append("$");
            out.add(Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    private static String toCamelCase(String dotted) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < dotted.length(); i++) {
            char c = dotted.charAt(i);
            if (c == '.' || c == '-' || c == '_') {
                upperNext = true;
            } else {
                if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
