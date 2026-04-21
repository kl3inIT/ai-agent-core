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
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
 *       whatever default Spring AI registered. The delegate lookup happens lazily via the
 *       {@link BeanFactory} so we can resolve the non-self bean by name and avoid the
 *       {@code @Primary}-on-self injection cycle.</li>
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
     * {@code ChatClientFactory}) receive THIS manager. The delegate is resolved from the
     * {@link BeanFactory} at bean-creation time via
     * {@link BeanFactory#getBean(String, Class)} looking for the well-known Spring AI bean name
     * {@code "toolCallingManager"} — this avoids the
     * {@code @Primary}-on-self injection cycle that a direct {@code ToolCallingManager delegate}
     * parameter would cause (we would become the primary and get asked to inject ourselves).
     *
     * <p>Hosts that truly want the raw manager can declare a bean named
     * {@value #GUARDED_TOOL_CALLING_MANAGER_BEAN} themselves — this conditional-on-missing-bean
     * stanza then skips.</p>
     */
    @Bean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
    @Primary
    @ConditionalOnMissingBean(name = GUARDED_TOOL_CALLING_MANAGER_BEAN)
    public ToolCallingManager guardedToolCallingManager(
            BeanFactory beanFactory,
            AiAgentGuardProperties props,
            ToolGuard toolGuard,
            AuditWriter auditWriter,
            CurrentAuthentication currentAuthentication) {
        ToolCallingManager delegate = resolveDelegate(beanFactory);
        return new GuardedToolCallingManager(delegate, props, toolGuard, auditWriter, currentAuthentication);
    }

    /**
     * Locates the upstream Spring AI default {@link ToolCallingManager}. Spring AI's own
     * autoconfig registers the bean under the canonical method name {@code toolCallingManager};
     * any host that replaced it with a non-primary named bean can register its own
     * {@value #GUARDED_TOOL_CALLING_MANAGER_BEAN} to bypass this wiring entirely.
     */
    private static ToolCallingManager resolveDelegate(BeanFactory beanFactory) {
        try {
            return beanFactory.getBean("toolCallingManager", ToolCallingManager.class);
        } catch (RuntimeException e) {
            // Defensive fallback — if the canonical name is not in use for any reason, Spring
            // will have failed earlier anyway (ChatClientFactory requires a ToolCallingManager).
            // Surface the failure at autoconfig construction so it is diagnosable.
            throw new IllegalStateException(
                    "GuardedToolCallingManager could not locate the default Spring AI 'toolCallingManager' "
                            + "bean to decorate. Register a bean named '"
                            + GUARDED_TOOL_CALLING_MANAGER_BEAN
                            + "' to opt out of this autoconfig, or ensure Spring AI's default "
                            + "ToolCallingManager autoconfig is active.", e);
        }
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
