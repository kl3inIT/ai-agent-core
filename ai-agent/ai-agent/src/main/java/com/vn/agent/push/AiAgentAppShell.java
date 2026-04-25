package com.vn.agent.push;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.shared.ui.Transport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Activates Vaadin {@code @Push} with the {@link Transport#WEBSOCKET_XHR}
 * transport for the ai-agent add-on. Vaadin scans the classpath for
 * {@link AppShellConfigurator} implementations at startup; a single
 * implementation is required, more than one triggers the
 * "More than one class found with the @PWA / AppShellConfigurator"
 * startup failure (RESEARCH Pitfall #9).
 *
 * <p>Host applications that already ship their own {@code AppShellConfigurator}
 * MUST opt out of this class by setting
 * {@code jmix.ai-agent.flowui.push-autoconfigure=false} in their
 * {@code application.properties}. The {@link ConditionalOnProperty}
 * matches-if-missing behaviour keeps Push on by default — the expected
 * posture for Phase 7 chat streaming and knowledge-base status fan-out.
 *
 * <p>Open question (RESEARCH Assumption A2): Vaadin's classpath scan for
 * {@code AppShellConfigurator} is independent of Spring bean registration.
 * If {@link ConditionalOnProperty} does NOT prevent Vaadin from picking up
 * this class (i.e. Vaadin finds it on the classpath regardless of Spring
 * condition evaluation), the two-AppShellConfigurator collision can still
 * surface at {@code bootRun}. In that case the add-on's fallback is to
 * delete this class entirely and document the required
 * {@code @Push(transport = Transport.WEBSOCKET_XHR)} snippet for host apps
 * to place on their own shell. The 07-01 SUMMARY notes this for follow-up
 * human bootRun verification.
 */
@Component
@Push(transport = Transport.WEBSOCKET_XHR)
@ConditionalOnProperty(
        name = "jmix.ai-agent.flowui.push-autoconfigure",
        havingValue = "true",
        matchIfMissing = true)
public class AiAgentAppShell implements AppShellConfigurator {
    // marker — @Push annotation carries the config.
    // @Component makes the @ConditionalOnProperty effective for Spring bean registration
    // (Vaadin separately scans classpath for AppShellConfigurator implementations; the
    // add-on's SUMMARY for 07-01 documents the bootRun-time collision follow-up).
}
