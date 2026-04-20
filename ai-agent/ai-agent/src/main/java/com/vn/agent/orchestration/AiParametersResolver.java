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
        // Do NOT encode defaults as a synthetic YAML blob — string concatenation cannot safely
        // quote arbitrary operator-supplied values (MD-02). The effective* accessors fall through
        // to {@link AiAgentDefaultsProperties} directly when bodyYaml is null/blank.
        synthetic.setBodyYaml(null);
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
        String body = params.getBodyYaml();
        String model;
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("model");
            model = v != null ? String.valueOf(v) : defaults.model();
        } else {
            model = defaults.model();
        }
        if (model == null || !model.contains("/")) {
            throw new IllegalStateException("Model id must follow OpenRouter slug format provider/model: " + model);
        }
        return model;
    }

    public Double effectiveTemperature(AiParameters params) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("temperature");
            if (v instanceof Number n) return n.doubleValue();
        }
        return defaults.temperature();
    }

    public Double effectiveTopP(AiParameters params) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("topP");
            if (v instanceof Number n) return n.doubleValue();
        }
        return defaults.topP();
    }

    public Integer effectiveMaxTokens(AiParameters params) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("maxTokens");
            if (v instanceof Number n) return n.intValue();
        }
        return defaults.maxTokens();
    }

    public String effectiveSystemPrompt(AiParameters params) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("systemPrompt");
            if (v != null) return String.valueOf(v);
        }
        return defaults.systemPrompt();
    }
}
