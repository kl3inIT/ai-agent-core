package com.vn.agent.rag;

import com.vn.agent.rag.config.AiAgentRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-14 / SPI-07 posture: the {@link ClasspathMarkdownIngester} is gated on
 * {@code jmix.ai-agent.rag.sample-ingester.enabled=true} and MUST NOT register otherwise.
 *
 * <p>Uses {@link ApplicationContextRunner} so the assertion is scoped to the exact
 * {@code @ConditionalOnProperty} evaluation — no Docker, no full Jmix context, no
 * Liquibase. That keeps the contract visible at the place it's owned: the bean's own
 * conditional annotation.</p>
 *
 * <p>The "enabled + actually runs uploads" side of the contract needs a real pgvector
 * and is covered under {@code @Tag("rag-it")} in the other Phase-5 integration tests
 * (upload pipeline → READY → similaritySearch assertions).</p>
 */
class SampleIngesterDisabledByDefaultTest {

    /**
     * Runner supplies only what the ingester constructor needs plus property binding for
     * {@link AiAgentRagProperties}. Intentionally NOT importing the full Jmix add-on —
     * conditional-on-property is about the bean's own gate, not the surrounding context.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class)
            .withConfiguration(UserConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withPropertyValues(
                    "jmix.ai-agent.rag.splitter.chunk-size=800",
                    "jmix.ai-agent.rag.splitter.chunk-overlap=120",
                    "jmix.ai-agent.rag.splitter.min-chunk-size-chars=5");

    @Test
    void test_sample_ingester_not_registered_by_default() {
        runner.run(ctx -> {
            assertThat(ctx)
                    .as("without the enabled flag, ClasspathMarkdownIngester MUST NOT be registered")
                    .doesNotHaveBean(ClasspathMarkdownIngester.class);
        });
    }

    @Test
    void test_sample_ingester_registered_when_enabled_property_is_true() {
        runner.withPropertyValues("jmix.ai-agent.rag.sample-ingester.enabled=true")
                .run(ctx -> {
                    assertThat(ctx)
                            .as("enabled=true MUST activate the ClasspathMarkdownIngester bean")
                            .hasSingleBean(ClasspathMarkdownIngester.class);
                });
    }

    @Test
    void test_sample_ingester_not_registered_when_enabled_property_is_false() {
        runner.withPropertyValues("jmix.ai-agent.rag.sample-ingester.enabled=false")
                .run(ctx -> {
                    assertThat(ctx)
                            .as("explicit enabled=false MUST keep the bean absent")
                            .doesNotHaveBean(ClasspathMarkdownIngester.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AiAgentRagProperties.class)
    @org.springframework.context.annotation.Import(ClasspathMarkdownIngester.class)
    static class TestBeans {

        @Bean
        ResourcePatternResolver resourcePatternResolver() {
            return new PathMatchingResourcePatternResolver();
        }
    }
}
