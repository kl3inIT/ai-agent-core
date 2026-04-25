package com.vn.agent.push;

import com.vn.agent.AITestConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 1 — D-02 push autoconfig conditional contract.
 *
 * <p>The {@link AiAgentAppShell} bean registers {@code @Push(WEBSOCKET_XHR)} for every
 * Phase 7 Vaadin UI. Host apps that already ship their own {@link com.vaadin.flow.component.page.AppShellConfigurator}
 * MUST be able to opt out via {@code jmix.ai-agent.flowui.push-autoconfigure=false}.
 *
 * <p>If Spring's {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * does NOT gate bean registration as expected (RESEARCH A2 open question), this test surfaces
 * the truth — pivot to doc-only distribution per 07-SUMMARY.
 */
class PushAutoConfigTest {

    @Nested
    @SpringBootTest(classes = AITestConfiguration.class)
    @ImportAutoConfiguration({
            com.vn.autoconfigure.agent.AIAutoConfiguration.class,
            com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
    })
    class DefaultEnabled {

        @Autowired ApplicationContext ctx;

        @Test
        void appShellPresentByDefault() {
            assertThat(ctx.getBeansOfType(AiAgentAppShell.class))
                    .as("AiAgentAppShell must be registered by default (matchIfMissing=true)")
                    .isNotEmpty();
        }
    }

    @Nested
    @SpringBootTest(
            classes = AITestConfiguration.class,
            properties = "jmix.ai-agent.flowui.push-autoconfigure=false")
    @ImportAutoConfiguration({
            com.vn.autoconfigure.agent.AIAutoConfiguration.class,
            com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
    })
    class DisabledByProperty {

        @Autowired ApplicationContext ctx;

        @Test
        void appShellAbsent() {
            assertThat(ctx.getBeansOfType(AiAgentAppShell.class))
                    .as("Setting jmix.ai-agent.flowui.push-autoconfigure=false must suppress the bean")
                    .isEmpty();
        }
    }
}
