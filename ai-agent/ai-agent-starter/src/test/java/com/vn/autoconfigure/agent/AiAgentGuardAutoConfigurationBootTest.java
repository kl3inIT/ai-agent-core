package com.vn.autoconfigure.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.guard.AiAgentGuardProperties;
import com.vn.agent.guard.GuardedToolCallingManager;
import com.vn.agent.guard.HostPrefixPatternProvider;
import com.vn.agent.guard.OutputScannerAdvisor;
import com.vn.agent.guard.ToolNamePatternProvider;
import com.vn.agent.spi.ToolGuard;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.Session;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * E-10 rubric: {@link AiAgentGuardAutoConfiguration} boots with all conditional beans resolving
 * as expected under a minimal dependency stub set.
 *
 * <p>Uses {@link ApplicationContextRunner} — NO {@code @SpringBootTest}, NO Jmix, NO eclipselink.
 * The only dependencies supplied are those each guard bean declares as constructor parameters:
 * {@link ToolGuard}, {@link AuditWriter}, {@link CurrentAuthentication}, plus a sentinel
 * {@code toolCallingManager} bean so the {@code @ConditionalOnBean} stanza on the guarded
 * manager activates.</p>
 */
@Tag("eval")
class AiAgentGuardAutoConfigurationBootTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(AiAgentGuardAutoConfiguration.class))
            .withUserConfiguration(TestBeans.class);

    @Test
    void autoconfigBootsAndWiresDefaultBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // CacheManager default registered with the two required caches.
            CacheManager cm = ctx.getBean(CacheManager.class);
            assertThat(cm.getCache("ai-agent.rateLimit")).isNotNull();
            assertThat(cm.getCache("ai-agent.tokenBreaker")).isNotNull();
            // OutputScannerAdvisor CallAdvisor bean registered.
            assertThat(ctx.getBeansOfType(CallAdvisor.class).values())
                    .anyMatch(a -> a instanceof OutputScannerAdvisor);
            // GuardedToolCallingManager registered as @Primary (only one ToolCallingManager
            // after decoration; the sentinel delegate lives under its original name).
            ToolCallingManager primary = ctx.getBean(ToolCallingManager.class);
            assertThat(primary).isInstanceOf(GuardedToolCallingManager.class);
            // Guarded manager also obtainable by its documented bean name.
            assertThat(ctx.containsBean(
                    AiAgentGuardAutoConfiguration.GUARDED_TOOL_CALLING_MANAGER_BEAN)).isTrue();
        });
    }

    @Test
    void hostProvidedCacheManagerOverridesDefault() {
        runner.withUserConfiguration(HostCacheManager.class).run(ctx -> {
            CacheManager cm = ctx.getBean(CacheManager.class);
            // Host-provided instance wins — ours is @ConditionalOnMissingBean.
            assertThat(cm).isSameAs(ctx.getBean("hostCacheManager"));
        });
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiAgentGuardProperties.class)
    static class TestBeans {
        @Bean
        ToolGuard toolGuard() { return mock(ToolGuard.class); }

        @Bean
        AuditWriter auditWriter() { return mock(AuditWriter.class); }

        @Bean
        CurrentAuthentication currentAuthentication() { return mock(CurrentAuthentication.class); }

        /** Sentinel delegate bean — the guard decorator looks this up by name. */
        @Bean(name = "toolCallingManager")
        ToolCallingManager toolCallingManager() { return mock(ToolCallingManager.class); }

        // Phase 9 PROMPT-06: HostPrefixPatternProvider depends on Jmix Metadata; supply a tiny
        // stub returning an empty class set so the provider compiles to no host-prefix regex.
        // ToolNamePatternProvider is wired with no ToolContributor beans so it carries only the
        // seven D-07 baseline names (six built-ins + RETRIEVAL).
        @Bean
        Metadata metadata() {
            Metadata m = mock(Metadata.class);
            Session session = mock(Session.class);
            org.mockito.Mockito.when(m.getSession()).thenReturn(session);
            org.mockito.Mockito.when(session.getClasses()).thenReturn(java.util.Set.of());
            return m;
        }

        @Bean
        HostPrefixPatternProvider hostPrefixPatternProvider(Metadata metadata,
                                                             AiAgentGuardProperties props) {
            return new HostPrefixPatternProvider(metadata, props);
        }

        @Bean
        ToolNamePatternProvider toolNamePatternProvider(AiAgentGuardProperties props) {
            return new ToolNamePatternProvider(java.util.List.of(), props);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostCacheManager {
        @Bean
        CacheManager hostCacheManager() {
            return new org.springframework.cache.concurrent.ConcurrentMapCacheManager(
                    "host-cache");
        }
    }
}
