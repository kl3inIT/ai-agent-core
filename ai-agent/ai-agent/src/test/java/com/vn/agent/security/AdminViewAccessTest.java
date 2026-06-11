package com.vn.agent.security;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.AccessManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 1 — D-10 + UI-10 admin-view gating contract.
 *
 * <p>Three admin-only views (Parameters / KnowledgeBase / ToolCallAudit) must be denied to
 * any user holding only {@link AiAgentUserRole}; conversely the two user-facing views
 * (Chat / Conversation list) must be permitted. Exercises Jmix 2.8's real view-policy
 * plumbing via {@link AccessManager} and {@link UiShowViewContext} — this is the same
 * path Vaadin navigation walks at runtime (no reflection, no route-specific fakes).
 *
 * <p>User store comes from the ambient {@code com.vn.agent.test_support.TestUsersConfiguration}
 * (inside the {@code com.vn.agent} scan root; loaded by AIConfiguration's component scan
 * during every add-on test).
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
// Import the same stub ChatModel/VectorStore beans the shared add-on integration context
// already uses. This test does not exercise either, but matching the @Import set lets it
// reuse the cached Spring context instead of booting a near-duplicate one (the stubs are
// inert here).
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AdminViewAccessTest {

    @Autowired AccessManager accessManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    private boolean permittedFor(String username, String viewId) {
        return systemAuthenticator.withUser(username, () -> {
            UiShowViewContext accessContext = new UiShowViewContext(viewId);
            accessManager.applyRegisteredConstraints(accessContext);
            return accessContext.isPermitted();
        });
    }

    @Test
    void deniesParametersDetailForNonAdmin() {
        assertThat(permittedFor("alice", "AiAgent_Parameters.detail"))
                .as("Non-admin must NOT see Parameters detail").isFalse();
    }

    @Test
    void deniesKnowledgeBaseListForNonAdmin() {
        assertThat(permittedFor("alice", "AiAgent_KnowledgeBase.list"))
                .as("Non-admin must NOT see KnowledgeBase list").isFalse();
    }

    @Test
    void deniesAuditListForNonAdmin() {
        assertThat(permittedFor("alice", "AiAgent_AiAuditEvent.list"))
                .as("Non-admin must NOT see ToolCallAudit list").isFalse();
    }

    @Test
    void deniesAiConfigurationForNonAdmin() {
        assertThat(permittedFor("alice", "AiAgent_Configuration"))
                .as("Non-admin must NOT see AI configuration").isFalse();
    }

    @Test
    void allowsAdminViewsForAdmin() {
        assertThat(permittedFor("admin", "AiAgent_ChatDialog")).isTrue();
        assertThat(permittedFor("admin", "AiAgent_Configuration")).isTrue();
        assertThat(permittedFor("admin", "AiAgent_Parameters.detail")).isTrue();
        assertThat(permittedFor("admin", "AiAgent_KnowledgeBase.list")).isTrue();
        assertThat(permittedFor("admin", "AiAgent_AiAuditEvent.list")).isTrue();
        assertThat(permittedFor("admin", "AiAgent_AiAuditEvent.detailDialog")).isTrue();
    }

    @Test
    void allowsChatForUser() {
        // ChatView/ConversationList carry no @ViewPolicy, so they must remain permitted
        // for any authenticated user (non-admin included).
        assertThat(permittedFor("alice", "AiAgent_Chat"))
                .as("Chat view is not admin-gated; user must have access").isTrue();
        assertThat(permittedFor("alice", "AiAgent_Conversation.list"))
                .as("Conversation list is not admin-gated; user must have access").isTrue();
    }
}
