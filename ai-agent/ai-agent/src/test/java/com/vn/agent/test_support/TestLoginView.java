package com.vn.agent.test_support;

import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.ViewController;

/**
 * Test-only stub that satisfies the transitive {@code @Route("login")} requirement
 * introduced when plan 07-01 pulled {@code jmix-securityflowui} into the filter chain.
 *
 * <p>{@code FlowuiVaadinWebSecurity.getLoginPath()} defaults to {@code "login"}; at context
 * bootstrap time Jmix's {@code ViewRegistry} resolves that path and throws
 * {@link io.jmix.flowui.exception.NoSuchViewException} if no view is registered for it.
 * The host application ({@code jmix-app}) ships {@code LoginView}; the add-on test context
 * does not. Without this stub <em>every</em> {@code @SpringBootTest} loading
 * {@link com.vn.agent.AITestConfiguration} fails bootstrap.
 *
 * <p>Lives in the {@code com.vn.agent.test_support} package so AIConfiguration's
 * component scan sees it during the add-on's own test run — and nowhere else. Zero
 * production impact. Minimum viable shape: a {@link StandardView} with {@code @Route("login")}
 * and a Jmix {@code @ViewController} id. No real authentication logic — tests that exercise
 * auth paths use {@code SystemAuthenticator} directly.
 *
 * <p>Added by plan 07-07b Task 1 as a Rule-1 deviation: 07-01 introduced a transitive
 * test-classpath requirement the plan did not spec. 07-07b remediates test-side rather
 * than blocking the phase.
 */
// ViewController id MUST be "login" — {@code UiProperties.loginViewId} defaults to "login"
// and {@code FlowuiVaadinWebSecurity.getLoginPath()} resolves that id via
// {@code ViewRegistry.getViewInfo(...)}. A non-matching id still throws NoSuchViewException
// even if @Route("login") is correct.
@Route(value = "login")
@ViewController(id = "login")
public class TestLoginView extends StandardView {
}
