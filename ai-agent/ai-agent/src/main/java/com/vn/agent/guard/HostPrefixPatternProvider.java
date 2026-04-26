package com.vn.agent.guard;

import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Phase 9 PROMPT-06 / D-06: derives a regex matching host-prefixed entity names dynamically at
 * startup from {@link Metadata#getSession()}. Extracts distinct prefix tokens before the first
 * {@code _} (e.g. {@code jmixapp_Customer} -&gt; prefix {@code jmixapp}) and compiles a single
 * regex over the union — adapts to each host's namespace with zero config.
 *
 * <p><b>Refresh:</b> requires app restart. {@code MetaClass} sets do not change at runtime in
 * practice; if Jmix later emits a metadata-changed signal a refresh handler is acceptable
 * (CONTEXT D-06 deferred ideas).
 *
 * <p><b>ReDoS safety:</b> each token is wrapped in {@link Pattern#quote(String)} before joining
 * so a host metaclass name with regex meta-chars (theoretical; Jmix names are identifier-shaped)
 * cannot produce a pathological regex. The compiled pattern is bounded by the host's own prefix
 * count (typically one or two).
 *
 * <p><b>Lazy-build fallback:</b> {@link #buildPattern()} runs at {@link ApplicationReadyEvent}
 * to pre-warm the regex, but {@link #asPattern()} also builds on first call if the event has
 * not yet fired (e.g. eager singleton construction during context refresh, before
 * {@code ApplicationReadyEvent} is emitted). Both paths converge on the same compiled value via
 * a {@code volatile} cache + double-checked locking.
 *
 * <p><b>Stable audit key:</b> {@link #PATTERN_KEY}. The {@link OutputScannerAdvisor} writes this
 * key into {@code ChatClientResponse.context()} on a hit; the matched substring is NEVER
 * persisted (T-09-20 mitigation).
 */
@Component
public class HostPrefixPatternProvider {

    /** Stable scanner audit key — written into context on hit; never the matched text. */
    public static final String PATTERN_KEY = "HOST_PREFIX_LEAK";

    private static final Logger log = LoggerFactory.getLogger(HostPrefixPatternProvider.class);

    private final Metadata metadata;
    private final AiAgentGuardProperties props;

    private final Object buildLock = new Object();
    private volatile boolean built;
    private volatile String compiledRegex;

    public HostPrefixPatternProvider(Metadata metadata, AiAgentGuardProperties props) {
        this.metadata = metadata;
        this.props = props;
    }

    /**
     * Pre-warm the compiled regex at {@link ApplicationReadyEvent}. Idempotent — re-runs simply
     * re-snapshot the metamodel (no-op if metaclass set is stable, which it is in practice).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void buildPattern() {
        synchronized (buildLock) {
            if (!props.hostPrefixLeakEnabled()) {
                this.compiledRegex = null;
                this.built = true;
                log.info("HostPrefixPatternProvider: disabled by configuration "
                        + "(host-prefix-leak.enabled=false).");
                return;
            }
            TreeSet<String> prefixes = new TreeSet<>();
            for (MetaClass mc : metadata.getSession().getClasses()) {
                String name = mc.getName();
                int underscore = name == null ? -1 : name.indexOf('_');
                if (underscore > 0) {
                    prefixes.add(Pattern.quote(name.substring(0, underscore)));
                }
            }
            if (prefixes.isEmpty()) {
                this.compiledRegex = null;
                this.built = true;
                log.info("HostPrefixPatternProvider: no underscore-bearing entities found; "
                        + "no pattern compiled.");
                return;
            }
            this.compiledRegex = "\\b(" + String.join("|", prefixes) + ")_\\w+\\b";
            this.built = true;
            log.info("HostPrefixPatternProvider: compiled regex over {} prefix tokens.",
                    prefixes.size());
        }
    }

    /**
     * @return {@link Optional#of} a {@link AiAgentGuardProperties.OutputScanner.Pattern} when the
     *         provider is enabled and at least one underscore-bearing metaclass exists;
     *         {@link Optional#empty()} otherwise (the {@link OutputScannerAdvisor} skips it).
     *         Lazily triggers {@link #buildPattern()} the first time it is called before
     *         {@code ApplicationReadyEvent} fires (defensive against eager-singleton ordering).
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
