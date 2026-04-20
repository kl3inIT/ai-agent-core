package com.vn.agent.orchestration;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request read of the active {@link AiParameters} profile (D-03/D-04). No caching — Phase 6's
 * profile-swap requirement depends on each request seeing the latest active row. Falls back to the
 * {@link AiAgentDefaultsProperties} record (bound from {@code jmix.ai-agent.defaults.*}) when no
 * active row exists.
 *
 * <p>Per D-04 the fallback path returns a synthetic {@code AiParameters} created via
 * {@link Metadata#create(Class)} (CLAUDE.md: never instantiate entities by constructor) populated
 * with the defaults' values and {@code profileName="__defaults__"}, {@code active=true}. The synthetic
 * row is never persisted.</p>
 *
 * <p>YAML parsing uses snakeyaml (transitively on classpath via Spring Boot starter). Keys consumed
 * from {@code bodyYaml}: {@code model}, {@code temperature}, {@code topP}, {@code systemPrompt}.
 * Missing keys fall through to defaults (per-field).</p>
 */
@Component
public class AiParametersResolver {

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AiAgentDefaultsProperties defaults;

    public AiParametersResolver(DataManager dataManager,
                                Metadata metadata,
                                AiAgentDefaultsProperties defaults) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.defaults = defaults;
    }

    public AiParameters resolveActive() {
        return dataManager.load(AiParameters.class)
                .query("select e from ai_AiParameters e where e.active = true")
                .optional()
                .orElseGet(this::buildFallback);
    }

    private AiParameters buildFallback() {
        AiParameters synthetic = metadata.create(AiParameters.class);
        synthetic.setProfileName("__defaults__");
        synthetic.setActive(Boolean.TRUE);
        // Encode defaults as YAML so consumers' single accessor path (effectiveXxx) works uniformly.
        StringBuilder yaml = new StringBuilder();
        if (defaults.model() != null) yaml.append("model: ").append(defaults.model()).append('\n');
        if (defaults.temperature() != null) yaml.append("temperature: ").append(defaults.temperature()).append('\n');
        if (defaults.topP() != null) yaml.append("topP: ").append(defaults.topP()).append('\n');
        if (defaults.maxTokens() != null) yaml.append("maxTokens: ").append(defaults.maxTokens()).append('\n');
        if (defaults.systemPrompt() != null) yaml.append("systemPrompt: ").append(defaults.systemPrompt()).append('\n');
        synthetic.setBodyYaml(yaml.toString());
        return synthetic;
    }

    /** Parsed YAML body keyed map — empty if {@code bodyYaml} is null/blank. */
    public Map<String, Object> parseBody(AiParameters params) {
        String body = params.getBodyYaml();
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        Object parsed = new Yaml().load(body);
        if (parsed instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    public String effectiveModel(AiParameters params) {
        Object v = parseBody(params).get("model");
        String model = v != null ? String.valueOf(v) : defaults.model();
        if (model == null || !model.contains("/")) {
            throw new IllegalStateException("Model id must follow OpenRouter slug format provider/model: " + model);
        }
        return model;
    }

    public Double effectiveTemperature(AiParameters params) {
        Object v = parseBody(params).get("temperature");
        return v instanceof Number n ? n.doubleValue() : defaults.temperature();
    }

    public Double effectiveTopP(AiParameters params) {
        Object v = parseBody(params).get("topP");
        return v instanceof Number n ? n.doubleValue() : defaults.topP();
    }

    public Integer effectiveMaxTokens(AiParameters params) {
        Object v = parseBody(params).get("maxTokens");
        return v instanceof Number n ? n.intValue() : defaults.maxTokens();
    }

    public String effectiveSystemPrompt(AiParameters params) {
        Object v = parseBody(params).get("systemPrompt");
        return v != null ? String.valueOf(v) : defaults.systemPrompt();
    }
}
