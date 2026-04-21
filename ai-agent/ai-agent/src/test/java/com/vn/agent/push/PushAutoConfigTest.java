package com.vn.agent.push;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Skeleton — Phase 07 plan 07-07a (Wave 0 Nyquist contract).
 * Nested shape preserved from 07-07; bodies filled in by 07-07b (Wave 5).
 */
class PushAutoConfigTest {

    @Nested
    @SpringBootTest
    @Disabled("07-07b will enable once 07-02 push autoconfig lands")
    class DefaultEnabled {

        @Test
        void appShellPresentByDefault() {
            fail("not yet implemented — filled in by 07-07b");
        }
    }

    @Nested
    @SpringBootTest(properties = "jmix.ai-agent.flowui.push-autoconfigure=false")
    @Disabled("07-07b will enable once 07-02 push autoconfig lands")
    class DisabledByProperty {

        @Test
        void appShellAbsent() {
            fail("not yet implemented — filled in by 07-07b");
        }
    }
}
