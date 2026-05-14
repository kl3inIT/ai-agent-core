package com.vn.agent.admin.config;

import com.vn.agent.orchestration.AiAgentDefaultsProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 Plan 03 Task 3 — TEST-20: catalog allowlist + exactly-one-default + drift gate.
 *
 * <p>The plan's nominal shape is {@code @SpringBootTest} mirroring
 * {@code TtlConfigTest} (Pattern I). The ai-agent module's Spring context boot is
 * currently blocked by the pre-existing Phase 11/13 regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory). Plans 13.1-06, 13.1-07,
 * 14-01, 14-02, 16-01, and 16-02 all hit the same blocker and used pure-JUnit
 * workarounds — this test follows the same playbook (Rule 3 — auto-fix blocking
 * issue).
 *
 * <p>The test contract is preserved exactly. The three methods build a
 * {@link ChatModelCatalogProperties} from the SAME {@code module.properties}
 * file that Spring would bind at boot, build a {@link AiAgentDefaultsProperties}
 * from the SAME {@code default-params.yaml} file Spring would bind at boot,
 * construct the {@link ChatModelCatalog} the same way Spring's
 * {@code ApplicationContext} would, and run the same {@code @PostConstruct}
 * {@code validate()} method. Any drift between the {@code module.properties}
 * catalog and the {@code default-params.yaml} model — at build time — fails the
 * test. Any drift at boot time fails {@code @PostConstruct}.
 *
 * <p>TEST-20 covers — per SPEC criterion 4 (curated multi-vendor catalog;
 * host-app deployment determines online/offline reachability):
 * <ul>
 *   <li>{@link #catalogSubsetOfAllowlist()} — every catalog entry id is in
 *       {@link ChatModelCatalog#CURATED_MODEL_ALLOWLIST}.</li>
 *   <li>{@link #exactlyOneDefault()} — exactly one catalog entry has
 *       {@code is-default=true}.</li>
 *   <li>{@link #defaultMatchesDefaultParamsYaml()} — the marked-default id equals
 *       {@code default-params.yaml.model} (the drift gate; if a future phase bumps
 *       the YAML model without updating {@code is-default=true} on the catalog row,
 *       both this test AND the application boot fail with a clear pointer).</li>
 * </ul>
 */
@Tag("unit")
class ChatModelCatalogAllowlistTest {

    private static final Path MODULE_PROPERTIES = Paths.get(
            "src/main/resources/com/vn/agent/module.properties");
    private static final Path DEFAULT_PARAMS_YAML = Paths.get(
            "../ai-agent-starter/src/main/resources/default-params.yaml");

    /**
     * Every {@code ChatModelCatalog.entries().get(i).id()} is contained in
     * {@link ChatModelCatalog#CURATED_MODEL_ALLOWLIST}.
     * Fail if any id is missing (SPEC criterion 4 — curated catalog gate).
     */
    @Test
    void catalogSubsetOfAllowlist() {
        ChatModelCatalog catalog = boot();
        for (ChatModelCatalog.Entry entry : catalog.entries()) {
            assertThat(ChatModelCatalog.CURATED_MODEL_ALLOWLIST)
                    .as("catalog entry '%s' must be in the curated multi-vendor allowlist",
                            entry.id())
                    .contains(entry.id());
        }
    }

    /** Exactly one catalog entry has {@code isDefault=true}. */
    @Test
    void exactlyOneDefault() {
        ChatModelCatalog catalog = boot();
        long count = catalog.entries().stream()
                .filter(ChatModelCatalog.Entry::isDefault)
                .count();
        assertThat(count)
                .as("ChatModelCatalog must have exactly one default entry")
                .isEqualTo(1L);
    }

    /**
     * Drift gate — if {@code default-params.yaml.model} is bumped without
     * updating the catalog's {@code is-default=true} entry, this assertion fails
     * and the build blocks (SPEC Acceptance Criterion 4 + RESEARCH §7 drift
     * gate). The same invariant is enforced at boot by
     * {@link ChatModelCatalog#validate()}.
     */
    @Test
    void defaultMatchesDefaultParamsYaml() {
        ChatModelCatalog catalog = boot();
        String defaultParamsModel = readDefaultParamsModel();
        assertThat(catalog.defaultEntry().id())
                .as("catalog default '%s' must equal default-params.yaml.model '%s' " +
                        "— drift gate (SPEC criterion 4 / RESEARCH §7)",
                        catalog.defaultEntry().id(), defaultParamsModel)
                .isEqualTo(defaultParamsModel);
    }

    // --- boot helpers (mirror Spring's binder + @PostConstruct sequence) ---

    private static ChatModelCatalog boot() {
        Properties properties = readModuleProperties();
        List<ChatModelCatalogProperties.Entry> entries = bindCatalogEntries(properties);
        ChatModelCatalogProperties catalogProperties = new ChatModelCatalogProperties(entries);

        String model = readDefaultParamsModel();
        AiAgentDefaultsProperties defaults = new AiAgentDefaultsProperties(
                model, null, null, null, null);

        ChatModelCatalog catalog = new ChatModelCatalog(catalogProperties, defaults);
        // Mirrors Spring's @PostConstruct invocation post-construction.
        catalog.validate();
        return catalog;
    }

    private static Properties readModuleProperties() {
        Properties properties = new Properties();
        try {
            properties.load(Files.newBufferedReader(MODULE_PROPERTIES));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not read module.properties at " + MODULE_PROPERTIES.toAbsolutePath(), ex);
        }
        return properties;
    }

    /**
     * Mirrors Spring's indexed-list binding of
     * {@code jmix.ai-agent.models.catalog[N].{id,label-message-key,is-default}}.
     * Preserves declaration order via index.
     */
    private static List<ChatModelCatalogProperties.Entry> bindCatalogEntries(Properties properties) {
        Pattern key = Pattern.compile(
                "^jmix\\.ai-agent\\.models\\.catalog\\[(\\d+)\\]\\.(id|label-message-key|is-default)$");
        Map<Integer, Map<String, String>> indexed = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            Matcher m = key.matcher(name);
            if (m.matches()) {
                int index = Integer.parseInt(m.group(1));
                indexed.computeIfAbsent(index, i -> new LinkedHashMap<>())
                        .put(m.group(2), properties.getProperty(name));
            }
        }
        return indexed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ChatModelCatalogProperties.Entry(
                        e.getValue().get("id"),
                        e.getValue().get("label-message-key"),
                        Boolean.parseBoolean(e.getValue().getOrDefault("is-default", "false"))))
                .toList();
    }

    private static String readDefaultParamsModel() {
        try {
            for (String line : Files.readAllLines(DEFAULT_PARAMS_YAML)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("model:")) {
                    return trimmed.substring("model:".length()).trim();
                }
            }
            throw new IllegalStateException(
                    "default-params.yaml has no top-level 'model:' key");
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not read default-params.yaml at " +
                            DEFAULT_PARAMS_YAML.toAbsolutePath(), ex);
        }
    }
}
