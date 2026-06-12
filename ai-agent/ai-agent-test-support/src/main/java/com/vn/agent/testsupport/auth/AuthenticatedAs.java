package com.vn.agent.testsupport.auth;

import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

/**
 * Parameterised authentication extension. Wraps every {@code @Test} method in
 * {@link SystemAuthenticator#begin(String)} for the chosen username (default {@code "admin"}).
 *
 * <p>Two activation styles:
 * <ol>
 *   <li>{@code @ExtendWith(AuthenticatedAs.Extension.class)} — runs as the default user
 *       {@code admin}.</li>
 *   <li>{@code @AuthenticatedAs("alice")} on the class — meta-annotation that already pulls
 *       in {@code @ExtendWith}, so no extra annotation is needed.</li>
 * </ol>
 *
 * <p>Mirrors {@code com.insurance.common.test_support.AuthenticatedAsAdmin} from the
 * jmix-insurance reference but parameterised by username so the same extension serves admin,
 * alice, bob, and the Phase-11 mutation-* personas.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(AuthenticatedAs.Extension.class)
public @interface AuthenticatedAs {

    /** Default username when the annotation is used without a value. */
    String DEFAULT_USERNAME = "admin";

    String value() default DEFAULT_USERNAME;

    /**
     * JUnit5 callback that performs the begin/end pair. Public so tests may use
     * {@code @ExtendWith(AuthenticatedAs.Extension.class)} directly without the meta-annotation.
     */
    class Extension implements BeforeEachCallback, AfterEachCallback {

        @Override
        public void beforeEach(ExtensionContext context) {
            getSystemAuthenticator(context).begin(resolveUsername(context));
        }

        @Override
        public void afterEach(ExtensionContext context) {
            getSystemAuthenticator(context).end();
        }

        private String resolveUsername(ExtensionContext context) {
            // Walk class hierarchy + enclosing classes for an @AuthenticatedAs(...) value.
            Optional<Class<?>> testClass = context.getTestClass();
            while (testClass.isPresent()) {
                AuthenticatedAs annotation = testClass.get().getAnnotation(AuthenticatedAs.class);
                if (annotation != null) {
                    return annotation.value();
                }
                testClass = Optional.ofNullable(testClass.get().getEnclosingClass());
            }
            return DEFAULT_USERNAME;
        }

        private SystemAuthenticator getSystemAuthenticator(ExtensionContext context) {
            ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
            return applicationContext.getBean(SystemAuthenticator.class);
        }
    }
}
