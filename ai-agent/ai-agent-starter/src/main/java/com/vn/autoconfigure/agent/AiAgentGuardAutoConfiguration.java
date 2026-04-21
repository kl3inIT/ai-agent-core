package com.vn.autoconfigure.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.guard.AiAgentGuardProperties;
import com.vn.agent.guard.GuardedToolCallingManager;
import com.vn.agent.guard.OutputScannerAdvisor;
import com.vn.agent.spi.ToolGuard;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Guard-layer defaults for the AI Agent starter (GUARD-06, D-12, D-21).
 *
 * <p>All beans declared here are {@code @ConditionalOnMissingBean} so a host application can
 * override any individual seam by declaring its own bean of the same type. Runs AFTER
 * {@link AIAutoConfiguration} so that any guard-adjacent beans contributed by the functional
 * {@code AIConfiguration} (Jmix module config) are already discoverable.</p>
 *
 * <p>Registrations:</p>
 * <ul>
 *   <li>{@link CacheManager} — default {@link ConcurrentMapCacheManager} pre-registering the two
 *       caches named in D-12 ({@code "ai-agent.rateLimit"} and {@code "ai-agent.tokenBreaker"}).
 *       Hosts declaring their own {@code CacheManager} (Redis, Caffeine, etc.) override this
 *       entirely.</li>
 *   <li>{@link OutputScannerAdvisor} — exposed as a {@link CallAdvisor} bean so Plan 04's
 *       {@code ChatClientFactory} can pick it up alongside the existing advisor chain.</li>
 *   <li>{@link GuardedToolCallingManager} — {@code @Primary} ToolCallingManager decorating
 *       whatever default Spring AI registered. The delegate lookup happens lazily via an
 *       {@link ObjectProvider} that filters out any {@link GuardedToolCallingManager} instances,
 *       so we can resolve the non-self bean by type and avoid the {@code @Primary}-on-self
 *       injection cycle.</li>
 *   <li>{@code StructuredOutputValidationAdvisor} — optional, reflective, {@code @ConditionalOnClass}.
 *       The class does NOT exist in Spring AI 1.1.4 (RESEARCH D-21); this bean ships for forward
 *       compatibility only. Plan 04's inline retry is the authoritative path today.</li>
 * </ul>
 */
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class AiAgentGuardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAgentGuardAutoConfiguration.class);

    /** Bean name used for the guarded manager — referenced in {@link #guardedToolCallingManager}
     *  delegate-lookup logic so the bean does not accidentally wrap itself. */
    public static final String GUARDED_TOOL_CALLING_MANAGER_BEAN = "guardedToolCallingManager";

    /**
     * Default two-cache CacheManager (D-12). Hosts declaring their own CacheManager
     * (e.g. Redis, Caffeine) override this entirely.
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheManager aiAgentGuardCacheManager() {
        log.info("No CacheManager bean found — registering default ConcurrentMapCacheManager for "
                + "caches ai-agent.rateLimit + ai-agent.tokenBreaker");
        return new ConcurrentMapCacheManager("ai-agent.rateLimit", "ai-agent.tokenBreaker");
    }

    /**
     * OutputScannerAdvisor bean. Declared via {@code @Bean} rather than {@code @Component} so the
     * {@link ConditionalOnMissingBean} by-name check lets hosts register their own scanner.
     * {@link AiAgentGuardProperties} is bound via {@code @ConfigurationPropertiesScan} on
     * {@code AIConfiguration} (covers {@code com.vn.agent.*}); no explicit
     * {@code @EnableConfigurationProperties} is needed.
     */
    @Bean
    @ConditionalOnMissingBean(name = "outputScannerAdvisor")
    public CallAdvisor outputScannerAdvisor(AiAgentGuardProperties props) {
        return new OutputScannerAdvisor(props);
    }

    /**
     * Guarded tool-calling manager — decorates whatever {@link ToolCallingManager} Spring AI
     * registered by default. Uses {@code @Primary} so consumers (notably
     * {@code ChatClientFactory}) receive THIS manager. The delegate is resolved by TYPE via an
     * {@link ObjectProvider} (WR-03): any upstream {@code ToolCallingManager} that is not itself
     * a {@link GuardedToolCallingManager} is picked as the delegate. Type-based resolution lets
     * hosts that replaced Spring AI's default under a different bean name still benefit from
     * iteration-cap and {@code ToolGuard} veto enforcement, which the previous bean-name-based
     * lookup silently skipped.
     *
     * <p>Hosts that truly want the raw manager can declare a bean named
     * {@value #GUARDED_TOOL_CALLING_MANAGER_BEAN} themselves — this conditional-on-missing-bean
     * stanza then skips.</p>
     */
    @Bean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
    @Primary
    @ConditionalOnBean(ToolCallingManager.class)
    @ConditionalOnMissingBean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
    public ToolCallingManager guardedToolCallingManager(
            ObjectProvider<ToolCallingManager> upstreamManagers,
            AiAgentGuardProperties props,
            ToolGuard toolGuard,
            AuditWriter auditWriter,
            CurrentAuthentication currentAuthentication) {
        ToolCallingManager delegate = resolveDelegate(upstreamManagers);
        return new GuardedToolCallingManager(delegate, props, toolGuard, auditWriter, currentAuthentication);
    }

    /**
     * Locates the upstream {@link ToolCallingManager} to decorate (WR-03). Resolving by type via
     * {@link ObjectProvider} instead of the canonical Spring AI bean name {@code toolCallingManager}
     * lets hosts that replaced the default with a differently-named bean still have iteration-cap
     * and {@code ToolGuard} veto enforcement applied. Excludes any {@link GuardedToolCallingManager}
     * instances to avoid the {@code @Primary}-on-self cycle.
     *
     * <p>If zero upstream managers remain, surface the failure here (rather than letting
     * {@code ChatClientFactory} inject a raw manager) with actionable remediation guidance. If
     * more than one candidate is present, require the host to opt out by registering their own
     * {@value #GUARDED_TOOL_CALLING_MANAGER_BEAN} so we never silently pick an arbitrary bean.</p>
     */
    private static ToolCallingManager resolveDelegate(ObjectProvider<ToolCallingManager> upstreamManagers) {
        java.util.List<ToolCallingManager> candidates = upstreamManagers.orderedStream()
                .filter(manager -> !(manager instanceof GuardedToolCallingManager))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "GuardedToolCallingManager could not locate an upstream ToolCallingManager "
                            + "to decorate. Ensure Spring AI's default ToolCallingManager autoconfig "
                            + "is active, or register a bean named '"
                            + GUARDED_TOOL_CALLING_MANAGER_BEAN + "' to opt out of this autoconfig.");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "GuardedToolCallingManager found multiple upstream ToolCallingManager beans "
                            + "(" + candidates.size() + "). Register a bean named '"
                            + GUARDED_TOOL_CALLING_MANAGER_BEAN + "' to opt out of this autoconfig "
                            + "and handle decoration explicitly.");
        }
        return candidates.get(0);
    }

    /**
     * Optional Spring-AI {@code StructuredOutputValidationAdvisor} (D-21). The class does NOT
     * exist in Spring AI 1.1.4 — this block is forward-compat: when a future Spring AI ships the
     * class, {@link ConditionalOnClass} activates and reflective construction binds it. Plan 04's
     * inline {@code askTyped} retry remains the authoritative path today.
     *
     * <p>T-06-20 trade-off: a malicious class with the same FQN on the classpath would be
     * picked up here, but that scenario implies the host is already compromised — the FQN is
     * scoped to Spring AI.</p>
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor")
    @ConditionalOnMissingBean(name = "structuredOutputValidationAdvisor")
    public CallAdvisor structuredOutputValidationAdvisor() {
        try {
            Class<?> cls = Class.forName(
                    "org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor");
            Object instance = cls.getDeclaredConstructor().newInstance();
            log.info("Detected Spring AI StructuredOutputValidationAdvisor — wired as CallAdvisor bean");
            return (CallAdvisor) instance;
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.warn("StructuredOutputValidationAdvisor class present but could not be instantiated "
                    + "reflectively; falling back to inline retry loop", e);
            return null;
        }
    }
}
