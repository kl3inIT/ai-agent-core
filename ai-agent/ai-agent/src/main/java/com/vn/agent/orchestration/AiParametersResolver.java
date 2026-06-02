package com.vn.agent.orchestration;

import com.vn.agent.entity.AiParameters;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.spi.PromptContextContributor;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *
 * <p>Phase 6 extensions (additive — no existing signature changed):
 * <ul>
 *   <li>{@link #effectiveModel(AiParameters, Overrides)} — sparse per-conversation override of the
 *       profile's model slug (D-01). Null/blank override falls back to the no-override path.</li>
 *   <li>{@link #effectiveSystemPrompt(AiParameters, String, UUID, UUID)} — composes the profile's
 *       system prompt followed by every registered {@link PromptContextContributor} fragment in
 *       {@code @Order} sequence (D-08, SPI-05). A crashing contributor is logged and skipped so a
 *       misbehaving host implementation cannot break the chat turn.</li>
 * </ul>
 * The 3-arg contextual signature for {@code effectiveSystemPrompt} exists for Plan 04 to invoke
 * per-request even though the SPI's {@link PromptContextContributor#fragment()} itself is
 * zero-arg in v1 — the contextual args reserve the plumbing for a future SPI expansion without
 * another resolver signature churn.</p>
 */
@Component
public class AiParametersResolver {

    private static final Logger log = LoggerFactory.getLogger(AiParametersResolver.class);

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AiAgentDefaultsProperties defaults;
    private final List<PromptContextContributor> contributors;

    public AiParametersResolver(DataManager dataManager,
                                Metadata metadata,
                                AiAgentDefaultsProperties defaults,
                                List<PromptContextContributor> contributors) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.defaults = defaults;
        List<PromptContextContributor> sorted = new ArrayList<>(contributors);
        sorted.sort(Comparator.comparingInt(PromptContextContributor::getOrder));
        this.contributors = List.copyOf(sorted);
    }

    public AiParameters resolveActive() {
        try {
            return dataManager.load(AiParameters.class)
                    .query("select e from ai_AiParameters e where e.active = true")
                    .optional()
                    .orElseGet(this::buildFallback);
        } catch (RuntimeException persistenceFailure) {
            log.warn("Unable to resolve active AiParameters from persistence; using defaults fallback: {}",
                    persistenceFailure.getMessage());
            log.debug("AiParameters resolveActive persistence failure details", persistenceFailure);
            return buildFallback();
        }
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

    /**
     * Phase 16 D-05 — returns the seed model from {@code default-params.yaml} (via
     * {@link AiAgentDefaultsProperties#model()}). Used by
     * {@code DefaultChatServiceImpl.executeBlockingTurn(...)} to reissue a turn whose
     * active-profile model was rejected by the provider as invalid (MODEL-02 catch + reissue
     * contract).
     *
     * <p>Bypasses the active-profile read-through chain — calling
     * {@code effectiveModel(resolveActive())} after a bad-model failure would return the same
     * bad model and loop (RESEARCH Open Question 1 — locked-decision rationale). The saved
     * {@link AiParameters#getBodyYaml()} {@code model} field is NEVER mutated by this path;
     * the admin's saved typo stays until the admin corrects it via the
     * {@code ParametersDetailView} ComboBox.
     *
     * <p>The defensive guard {@code fallback.equals(offendingModel)} that prevents a
     * reissue-into-the-same-bad-model loop lives in the caller (so the caller can choose to
     * surface the original exception unchanged); this accessor is intentionally inert apart
     * from the {@code defaults.model()} delegation.
     */
    public String fallbackModel() {
        return defaults.model();
    }

    /**
     * Per-run override of the active profile's model slug (D-01). If {@code overrides} is null,
     * or its {@link Overrides#model()} is null/blank, falls back to
     * {@link #effectiveModel(AiParameters)}. Slug format is validated identically to the
     * no-override path (provider/model shape).
     */
    public String effectiveModel(AiParameters params, Overrides overrides) {
        if (overrides != null && overrides.model() != null && !overrides.model().isBlank()) {
            String slug = overrides.model();
            if (!slug.contains("/")) {
                throw new IllegalStateException(
                        "Model id must follow OpenRouter slug format provider/model: " + slug);
            }
            return slug;
        }
        return effectiveModel(params);
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

    public Integer effectiveRagTopK(AiParameters params, int defaultValue) {
        Number value = numberFromBody(params, "ragTopK");
        if (value == null) {
            return defaultValue;
        }
        int topK = value.intValue();
        return topK > 0 ? topK : defaultValue;
    }

    public Double effectiveRagSimilarityThreshold(AiParameters params, double defaultValue) {
        Number value = numberFromBody(params, "ragSimilarityThreshold");
        if (value == null) {
            return defaultValue;
        }
        double threshold = value.doubleValue();
        return threshold >= 0.0 && threshold <= 1.0 ? threshold : defaultValue;
    }

    /**
     * Workstream B — the active profile's {@code enabledTools} allowlist for the runtime tool
     * surface. Reads the {@code enabledTools} list from the profile body YAML.
     *
     * <p>Semantics (matches {@code AiParametersBody.enabledTools} documentation and the
     * {@code ParametersDetailView} read path):
     * <ul>
     *   <li>{@code null} / absent / empty list → {@code null} is returned, meaning "all tools
     *       allowed" (no runtime restriction; preserves pre-Workstream-B behavior).</li>
     *   <li>non-empty list → the exact list of tool names the admin selected. The tool surface is
     *       intersected with this list (a tool is exposed only if it is both selected by the
     *       per-turn intent routing AND present here).</li>
     * </ul>
     * Blank entries are dropped. The allowlist can only narrow the built-in/contributed tool set;
     * it never widens it.</p>
     */
    public List<String> effectiveEnabledTools(AiParameters params) {
        String body = params.getBodyYaml();
        if (body == null || body.isBlank()) {
            return null;
        }
        Object value = parseBody(params).get("enabledTools");
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<String> tools = new ArrayList<>(rawList.size());
        for (Object element : rawList) {
            if (element == null) {
                continue;
            }
            String name = String.valueOf(element).trim();
            if (!name.isEmpty()) {
                tools.add(name);
            }
        }
        return tools.isEmpty() ? null : List.copyOf(tools);
    }

    public String effectiveSystemPrompt(AiParameters params) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object v = parseBody(params).get("systemPrompt");
            if (v != null) return String.valueOf(v);
        }
        return defaults.systemPrompt();
    }

    /**
     * Compose the effective system prompt: the profile's own systemPrompt followed by each
     * registered {@link PromptContextContributor} fragment in {@code @Order} sequence, joined with
     * {@code "\n\n"} and filtered for null/blank entries (D-08, SPI-05). The baseline profile
     * prompt precedes contributor fragments so operator-authored instructions dominate host-side
     * augmentations. A contributor that throws at runtime is logged and skipped so a misbehaving
     * host implementation cannot break a chat turn.
     *
     * <p>The {@code userId}/{@code conversationId}/{@code runId} arguments reserve the plumbing
     * for a future SPI expansion; the v1 {@link PromptContextContributor#fragment()} signature is
     * zero-arg, so these values are not forwarded to contributors today.</p>
     */
    public String effectiveSystemPrompt(AiParameters params,
                                        String userId,
                                        UUID conversationId,
                                        UUID runId) {
        String base = effectiveSystemPrompt(params);
        if (contributors.isEmpty()) {
            return base;
        }
        StringBuilder out = new StringBuilder(base == null ? "" : base);
        for (PromptContextContributor c : contributors) {
            String fragment;
            try {
                fragment = c.fragment();
            } catch (RuntimeException ex) {
                // One misbehaving contributor must not break the chat turn. Log and skip.
                log.warn("PromptContextContributor {} threw — skipping", c.getClass().getName(), ex);
                continue;
            }
            if (fragment != null && !fragment.isBlank()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(fragment);
            }
        }
        return out.toString();
    }

    private Number numberFromBody(AiParameters params, String key) {
        String body = params.getBodyYaml();
        if (body != null && !body.isBlank()) {
            Object value = parseBody(params).get(key);
            if (value instanceof Number number) {
                return number;
            }
        }
        return null;
    }
}
