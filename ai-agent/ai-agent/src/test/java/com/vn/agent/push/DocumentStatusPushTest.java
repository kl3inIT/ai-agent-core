package com.vn.agent.push;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Skeleton — Phase 07 plan 07-07a (Wave 0 Nyquist contract).
 * Disabled until Phase 7 push event publisher lands; bodies filled in by 07-07b (Wave 5).
 */
@SpringBootTest
@Disabled("07-07b will enable once 07-02 push event bus lands")
class DocumentStatusPushTest {

    @Test
    void publishesOnCommit() {
        fail("not yet implemented — filled in by 07-07b");
    }

    @Test
    void suppressedOnRollback() {
        fail("not yet implemented — filled in by 07-07b");
    }
}
