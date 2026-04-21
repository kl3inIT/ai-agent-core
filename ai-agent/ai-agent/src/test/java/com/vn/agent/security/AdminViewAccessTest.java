package com.vn.agent.security;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Skeleton — Phase 07 plan 07-07a (Wave 0 Nyquist contract).
 * Disabled until Phase 7 admin views land; bodies filled in by 07-07b (Wave 5).
 */
@SpringBootTest
@Disabled("07-07b will enable once 07-05 admin views land")
class AdminViewAccessTest {

    @Test
    void deniesParametersListForNonAdmin() {
        fail("not yet implemented — filled in by 07-07b");
    }

    @Test
    void deniesKnowledgeBaseListForNonAdmin() {
        fail("not yet implemented — filled in by 07-07b");
    }

    @Test
    void deniesAuditListForNonAdmin() {
        fail("not yet implemented — filled in by 07-07b");
    }

    @Test
    void allowsChatForUser() {
        fail("not yet implemented — filled in by 07-07b");
    }

    @Test
    void allowsConversationListForUser() {
        fail("not yet implemented — filled in by 07-07b");
    }
}
