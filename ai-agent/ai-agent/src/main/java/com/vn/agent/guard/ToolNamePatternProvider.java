package com.vn.agent.guard;

import com.vn.agent.spi.ToolContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Phase 9 PROMPT-06 / D-07: snapshots the union of (a) built-in tool names, (b) the
 * {@code RETRIEVAL} advisor name, and (c) every {@code @Tool} method name reachable through
 * registered {@link ToolContributor} beans. Compiles a single regex matching whole-word
 * occurrences of those names in user-facing assistant text.
 *
 * <p><b>Literal-underscore form only:</b> the regex matches the literal name (e.g.
 * {@code find_records}), NOT the natural-language phrase ({@code find records}). The whole-word
 * boundary {@code \b} prevents accidental matches inside larger tokens (RESEARCH Pitfall 8).
 *
 * <p><b>Lazy-build fallback:</b> {@link #buildPattern()} runs at {@link ApplicationReadyEvent}
 * to pre-warm the regex, but {@link #asPattern()} also builds on first call if the event has
 * not yet fired (defensive against eager-singleton wiring during context refresh).
 *
 * <p><b>ReDoS safety:</b> each token is wrapped in {@link Pattern#quote(String)} before joining
 * so a host-contributed {@code @Tool(name=".*")} bean cannot produce a pathological regex
 * (T-09-22 mitigation).
 *
 * <p><b>Stable audit key:</b> {@link #PATTERN_KEY}; matched substring NEVER persisted.
 */
@Component
public class ToolNamePatternProvider {

    /** Stable scanner audit key — written into context on hit; never the matched text. */
    public static final String PATTERN_KEY = "TOOL_NAME_LEAK";

    /** Six built-in tool names (D-07). Hard-coded because they are part of the v1.0 contract. */
    static final List<String> BUILT_IN_TOOL_NAMES = List.of(
            "list_entities", "describe_entity", "find_records",
            "count_records", "get_record", "get_related_records");

    /** D-07: Spring AI RAG advisor name. */
    static final String RETRIEVAL_ADVISOR = "RETRIEVAL";

    private static final Logger log = LoggerFactory.getLogger(ToolNamePatternProvider.class);

    private final List<ToolContributor> contributors;
    private final AiAgentGuardProperties props;

    private final Object buildLock = new Object();
    private volatile boolean built;
    private volatile String compiledRegex;

    public ToolNamePatternProvider(List<ToolContributor> contributors, AiAgentGuardProperties props) {
        this.contributors = contributors;
        this.props = props;
    }

    /**
     * Pre-warm the compiled regex at {@link ApplicationReadyEvent}. Idempotent — re-runs simply
     * re-snapshot the {@link ToolContributor} chain (T-09-24 documents that runtime contributor
     * additions after this snapshot are out of scope for v1.1).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void buildPattern() {
        synchronized (buildLock) {
            if (!props.toolNameLeakEnabled()) {
                this.compiledRegex = null;
                this.built = true;
                log.info("ToolNamePatternProvider: disabled by configuration "
                        + "(tool-name-leak.enabled=false).");
                return;
            }
            TreeSet<String> tokens = new TreeSet<>();
            tokens.add(Pattern.quote(RETRIEVAL_ADVISOR));
            for (String n : BUILT_IN_TOOL_NAMES) {
                tokens.add(Pattern.quote(n));
            }
            if (contributors != null) {
                for (ToolContributor contributor : contributors) {
                    List<Object> beans;
                    try {
                        beans = contributor.contribute();
                    } catch (RuntimeException contributorFailure) {
                        log.warn("ToolNamePatternProvider: skipping contributor {}: {}",
                                contributor.getClass().getName(), contributorFailure.getMessage());
                        continue;
                    }
                    if (beans == null) {
                        continue;
                    }
                    for (Object bean : beans) {
                        try {
                            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                                    .toolObjects(bean)
                                    .build()
                                    .getToolCallbacks();
                            for (ToolCallback cb : callbacks) {
                                String n = cb.getToolDefinition().name();
                                if (n != null && !n.isBlank()) {
                                    tokens.add(Pattern.quote(n));
                                }
                            }
                        } catch (RuntimeException reflectionFailure) {
                            log.warn("ToolNamePatternProvider: skipping contributor bean {}: {}",
                                    bean.getClass().getName(), reflectionFailure.getMessage());
                        }
                    }
                }
            }
            this.compiledRegex = "\\b(" + String.join("|", tokens) + ")\\b";
            this.built = true;
            log.info("ToolNamePatternProvider: compiled regex over {} tool-name tokens.",
                    tokens.size());
        }
    }

    /**
     * @return {@link Optional#of} a {@link AiAgentGuardProperties.OutputScanner.Pattern} when
     *         the provider is enabled (always non-empty in that case — at minimum it carries the
     *         seven D-07 baseline names); {@link Optional#empty()} when disabled.
     */
    public Optional<AiAgentGuardProperties.OutputScanner.Pattern> asPattern() {
        if (!built) {
            buildPattern();
        }
        String regex = this.compiledRegex;
        return regex == null
                ? Optional.empty()
                : Optional.of(new AiAgentGuardProperties.OutputScanner.Pattern(PATTERN_KEY, regex));
    }
}
