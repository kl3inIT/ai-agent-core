package com.vn.agent.tools.mutation;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shared boot recipe for mutation-tool integration tests.
 *
 * <p>Consolidates the boilerplate that was copy-pasted across the mutation test suite
 * ({@code @SpringBootTest(classes=…) + @ImportAutoConfiguration(…) + @Import(…)} with
 * {@code ai-agent.tools.mutation.enabled=true}). Because every test that uses THIS annotation
 * declares an identical {@code MergedContextConfiguration}, Spring's test context cache reuses a
 * SINGLE application context across all of them instead of rebooting Jmix + the agentstore
 * datastore once per class — the dominant cost in the suite's wall-clock.
 *
 * <p><b>When NOT to use this:</b> tests that alter the context (e.g. {@code @MockitoBean} bean
 * overrides, fault-injection {@code @Import}s, or extra {@code properties}) intentionally need
 * their OWN context and must keep their explicit {@code @SpringBootTest} declaration — composing
 * them here would dilute the real-bean coverage the cache is meant to share. A test that needs one
 * extra property can still use this annotation and add {@code @TestPropertySource} on top.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
public @interface MutationIntegrationTest {
}
