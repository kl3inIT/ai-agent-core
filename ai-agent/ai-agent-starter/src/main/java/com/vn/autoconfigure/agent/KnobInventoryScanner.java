package com.vn.autoconfigure.agent;

import com.vn.agent.admin.config.AdminSecretPatternProperties;
import com.vn.agent.admin.config.KnobInventory;
import com.vn.agent.admin.config.KnobMetadata;
import com.vn.agent.admin.config.dto.AiKnobRow;
import com.vn.agent.admin.config.dto.AiSecretIndicatorRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 16 D-02 — walks {@code @ConfigurationProperties} beans at
 * {@code ApplicationReadyEvent} (Pitfall 4) and populates {@link KnobInventory}
 * with the three-tier snapshot consumed by {@code AiUiSettingsDetailView}.
 *
 * <p>Two opencode/codex concerns are mechanically encoded:</p>
 * <ul>
 *   <li><b>opencode HIGH Concern #2 — prefix filter</b>: only beans whose
 *       {@code @ConfigurationProperties.prefix} starts with one of the built-in
 *       roots ({@code jmix.ai-agent.}, {@code ai-agent.}) or the host-extension
 *       additions from {@link KnobScannerProperties#additionalPrefixes()} are
 *       walked. This bounds the reflective scan to ~10 records on a typical
 *       Jmix app (vs. 60-80 if every Spring/third-party bean were visited).</li>
 *   <li><b>codex HIGH Concern #7 — property-key reconstruction</b>: property
 *       keys are built via {@link ConfigurationPropertyName#append(String)},
 *       which honours Spring's relaxed-binding rules (camelCase ↔ kebab-case)
 *       and works recursively for nested records. No manual string concat.</li>
 * </ul>
 *
 * <p><b>Defense-in-depth</b>:</p>
 * <ul>
 *   <li>A host {@code @ConfigurationProperties} without {@code @KnobMetadata}
 *       still appears in Tier-2 with {@code requiresRestart=true} IF its prefix
 *       matches the allowed list (extend via
 *       {@code ai-agent.admin.knob-scanner.additional-prefixes}).</li>
 *   <li>A property name matching the Tier-3 pattern from
 *       {@link AdminSecretPatternProperties#resolvedPatterns()} is masked as a
 *       Tier-3 secret indicator even if its declaring record forgot the
 *       {@code @KnobMetadata(tier=TIER_3)} annotation.</li>
 * </ul>
 */
@Component
public class KnobInventoryScanner {

    private static final Logger log = LoggerFactory.getLogger(KnobInventoryScanner.class);

    private static final Set<String> BUILTIN_PREFIXES = Set.of(
            "jmix.ai-agent.",
            "ai-agent.");

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final KnobInventory knobInventory;
    private final AdminSecretPatternProperties secretPatterns;
    private final KnobScannerProperties scannerProperties;

    public KnobInventoryScanner(ApplicationContext applicationContext,
                                Environment environment,
                                KnobInventory knobInventory,
                                AdminSecretPatternProperties secretPatterns,
                                KnobScannerProperties scannerProperties) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.knobInventory = knobInventory;
        this.secretPatterns = secretPatterns;
        this.scannerProperties = scannerProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        Set<String> allowedPrefixes = mergedAllowedPrefixes();
        Map<String, ConfigurationPropertiesBean> allBeans =
                ConfigurationPropertiesBean.getAll(applicationContext);
        Map<String, ConfigurationPropertiesBean> filtered = filterByPrefix(allBeans, allowedPrefixes);
        if (log.isDebugEnabled()) {
            log.debug("Knob scanner: filtered {}/{} @ConfigurationProperties beans into scan set.",
                    filtered.size(), allBeans.size());
        }

        List<AiKnobRow> tier1 = new ArrayList<>();
        List<AiKnobRow> tier2 = new ArrayList<>();
        List<AiSecretIndicatorRow> tier3 = new ArrayList<>();

        for (ConfigurationPropertiesBean bean : filtered.values()) {
            String rawPrefix = bean.getAnnotation() == null ? "" : bean.getAnnotation().prefix();
            if (rawPrefix == null || rawPrefix.isBlank()) {
                continue;
            }
            ConfigurationPropertyName root;
            try {
                root = ConfigurationPropertyName.of(rawPrefix);
            } catch (RuntimeException malformed) {
                log.warn("Knob scanner: skipping bean '{}' with malformed prefix '{}': {}",
                        bean.getName(), rawPrefix, malformed.getMessage());
                continue;
            }
            Class<?> beanType = resolveBeanType(bean);
            if (beanType == null) {
                continue;
            }
            walkType(beanType, root, tier1, tier2);
        }

        tier3.addAll(scanEnvironmentForSecrets());

        knobInventory.setState(new KnobInventory.State(tier1, tier2, tier3));

        if (log.isInfoEnabled()) {
            log.info("Knob scanner: tier1={} tier2={} tier3={} records.",
                    tier1.size(), tier2.size(), tier3.size());
        }
    }

    /**
     * Resolves the carrier type for a {@link ConfigurationPropertiesBean}. The
     * package-private {@code ConfigurationPropertiesBean#getType()} is not
     * accessible from this module, so we use the public {@code asBindTarget()}
     * → {@code Bindable#getType()} chain (constructor-bound records) and fall
     * back to {@code getInstance().getClass()} for setter-style beans.
     */
    private static Class<?> resolveBeanType(ConfigurationPropertiesBean bean) {
        try {
            org.springframework.core.ResolvableType resolvable = bean.asBindTarget().getType();
            Class<?> resolved = resolvable.resolve();
            if (resolved != null) {
                return resolved;
            }
        } catch (RuntimeException ignored) {
            // fall through to instance class
        }
        Object instance = bean.getInstance();
        return instance == null ? null : instance.getClass();
    }

    private Set<String> mergedAllowedPrefixes() {
        Set<String> merged = new LinkedHashSet<>(BUILTIN_PREFIXES);
        merged.addAll(scannerProperties.resolvedAdditionalPrefixes());
        return merged;
    }

    private static Map<String, ConfigurationPropertiesBean> filterByPrefix(
            Map<String, ConfigurationPropertiesBean> allBeans, Set<String> allowedPrefixes) {
        java.util.LinkedHashMap<String, ConfigurationPropertiesBean> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ConfigurationPropertiesBean> entry : allBeans.entrySet()) {
            ConfigurationPropertiesBean bean = entry.getValue();
            if (bean.getAnnotation() == null) {
                continue;
            }
            String prefix = bean.getAnnotation().prefix();
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            String normalised = prefix.endsWith(".") ? prefix : prefix + ".";
            for (String allowed : allowedPrefixes) {
                if (normalised.startsWith(allowed)) {
                    out.put(entry.getKey(), bean);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Walks the component graph of a {@code @ConfigurationProperties} carrier
     * type, classifying each component by {@code @KnobMetadata#tier()}.
     */
    private void walkType(Class<?> type, ConfigurationPropertyName base,
                          List<AiKnobRow> tier1,
                          List<AiKnobRow> tier2) {
        if (type == null) {
            return;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                ConfigurationPropertyName childName = appendName(base, component.getName());
                KnobMetadata metadata = component.getAnnotation(KnobMetadata.class);
                addComponent(childName, component.getType(), metadata, tier1, tier2);
                if (component.getType().isRecord()) {
                    walkType(component.getType(), childName, tier1, tier2);
                }
            }
        } else {
            // classic setter-style class — walk declared fields with @KnobMetadata
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                ConfigurationPropertyName childName = appendName(base, field.getName());
                KnobMetadata metadata = AnnotationUtils.findAnnotation(field, KnobMetadata.class);
                addComponent(childName, field.getType(), metadata, tier1, tier2);
            }
        }
    }

    private static ConfigurationPropertyName appendName(ConfigurationPropertyName base, String componentName) {
        // ConfigurationPropertyName.append uses Spring's relaxed-binding normalisation —
        // camelCase → kebab-case happens here, never via manual string concat (codex HIGH #7).
        return base.append(ConfigurationPropertyName.adapt(componentName, '.').toString());
    }

    private void addComponent(ConfigurationPropertyName name, Class<?> componentType,
                              KnobMetadata metadata,
                              List<AiKnobRow> tier1,
                              List<AiKnobRow> tier2) {
        String key = name.toString();
        // CR-01 defense-in-depth (SPEC criterion 4): if the property key matches a
        // Tier-3 secret glob, suppress the Tier-2 row entirely — regardless of
        // whether the declaring record carried @KnobMetadata(tier=TIER_3). The
        // env-walk pass in scanEnvironmentForSecrets emits a value-less
        // SecretIndicatorRow for the same key, so the secret is still surfaced
        // (configured: yes/no) but the raw value never reaches the Tier-2 grid.
        // This catches a host that adds `@ConfigurationProperties("ai-agent.host")`
        // with an `apiKey` field but forgets `@KnobMetadata(TIER_3)`.
        if (matchesAny(key, compilePatterns(secretPatterns.resolvedPatterns()))) {
            return;
        }
        String resolvedValue = safeReadValue(key);
        KnobMetadata.Tier tier;
        boolean requiresRestart;
        String displayMessageKey;
        if (metadata == null) {
            // Defense-in-depth — un-annotated property under an allowed prefix
            // defaults to Tier-2 with requiresRestart=true (RESEARCH §1.6).
            tier = KnobMetadata.Tier.TIER_2;
            requiresRestart = true;
            displayMessageKey = "bootConfig.knob.unclassified." + key;
        } else {
            tier = metadata.tier();
            requiresRestart = metadata.requiresRestart();
            displayMessageKey = metadata.displayMessageKey() == null || metadata.displayMessageKey().isBlank()
                    ? "bootConfig.knob.unclassified." + key
                    : metadata.displayMessageKey();
        }

        AiKnobRow row = new AiKnobRow(
                key, resolvedValue, displayMessageKey, requiresRestart);
        switch (tier) {
            case TIER_1 -> tier1.add(row);
            case TIER_2 -> tier2.add(row);
            case TIER_3 -> {
                // Tier-3 is surfaced through the Environment-walk pass; record
                // the annotation site as a no-op here. The Environment pass
                // catches the actual property key regardless of declaring
                // record (defense-in-depth — SEC-08).
            }
        }
    }

    private String safeReadValue(String key) {
        try {
            String value = environment.getProperty(key);
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * Walks every {@link EnumerablePropertySource} in the environment, applies
     * the Tier-3 pattern from {@link AdminSecretPatternProperties#resolvedPatterns()},
     * and emits one {@link KnobInventory.SecretIndicatorRow} per match. The
     * raw value is NEVER read into the indicator — only the {@code configured}
     * boolean (non-null + non-blank) is computed (SEC-08).
     */
    private List<AiSecretIndicatorRow> scanEnvironmentForSecrets() {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        List<Pattern> patterns = compilePatterns(secretPatterns.resolvedPatterns());
        if (patterns.isEmpty()) {
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<AiSecretIndicatorRow> rows = new ArrayList<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name == null || !seen.add(name)) {
                    continue;
                }
                if (matchesAny(name, patterns)) {
                    boolean configured = isConfigured(name);
                    String sanitised = name.replaceAll("[^a-zA-Z0-9]", "_");
                    rows.add(new AiSecretIndicatorRow(
                            name,
                            "bootConfig.knob.secret." + sanitised,
                            configured));
                }
            }
        }
        rows.sort(java.util.Comparator.comparing(AiSecretIndicatorRow::getKey));
        return rows;
    }

    private boolean isConfigured(String key) {
        try {
            String value = environment.getProperty(key);
            return value != null && !value.isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matchesAny(String key, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles glob-style patterns ({@code *.api-key}, {@code *.password}, etc.)
     * into anchored case-insensitive regexes. Malformed entries are logged and
     * skipped — a bad operator pattern must not block the scan.
     */
    private static List<Pattern> compilePatterns(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        List<Pattern> out = new ArrayList<>(globs.size());
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            String regex = globToRegex(glob);
            try {
                out.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (RuntimeException malformed) {
                log.warn("Knob scanner: skipping malformed secret pattern '{}': {}",
                        glob, malformed.getMessage());
            }
        }
        return out;
    }

    private static String globToRegex(String glob) {
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
        return sb.toString();
    }

}
