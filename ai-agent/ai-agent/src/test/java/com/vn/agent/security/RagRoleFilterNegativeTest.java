package com.vn.agent.security;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 08-01 Task 3 — TEST-04 RAG role-filter negative case (CONTEXT D-03 bullet 2).
 *
 * <p>Verifies the RAG retrieval filter built for a non-admin caller (alice — only
 * {@link AiAgentUserRole}) excludes admin-tagged chunks (RAG-04/05) and is fail-closed
 * by construction (RAG-06).
 *
 * <p><b>R-01b correction:</b> the actual SUT method is
 * {@link RetrievalFilterBuilder#buildFor(Authentication)}, NOT
 * {@code buildForCurrentUser()}. The filter takes the {@link Authentication} as an
 * explicit parameter; the test extracts it from {@link SecurityContextHolder} inside
 * the {@link SystemAuthenticator#withUser} block.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class RagRoleFilterNegativeTest {

    @Autowired RetrievalFilterBuilder retrievalFilterBuilder;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void aliceUserRoleOnly_buildFor_excludesAdminTaggedChunks() {
        systemAuthenticator.withUser("alice", () -> {
            // R-01b: actual SUT method is buildFor(Authentication), not buildForCurrentUser().
            // Pull the current authentication from SecurityContextHolder, which
            // SystemAuthenticator.withUser populates with alice's authorities.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth)
                    .as("withUser must populate SecurityContextHolder with alice's authentication")
                    .isNotNull();

            Filter.Expression expr = retrievalFilterBuilder.buildFor(auth);
            assertThat(expr)
                    .as("Non-admin caller must receive a non-null filter expression "
                            + "(admin-bypass is admin-only)")
                    .isNotNull();

            String exprText = expr.toString();

            // Positive contract: alice's flattened role flag appears in the filter.
            // RetrievalFilterBuilder uses ChunkMetadata.roleFlagKey(role) which lowercases
            // the role code and replaces non-key characters (e.g. '-') with '_'. So
            // "ai-agent-user" becomes the metadata key "role_ai_agent_user".
            assertThat(exprText)
                    .as("Filter must reference the user's normalized role flag for "
                            + "AiAgentUserRole-only callers")
                    .contains("role_ai_agent_user");

            // Negative contract: admin role flag (normalized form of ai-agent-admin) MUST
            // NOT appear in the serialized filter for non-admin callers (RAG-04/05).
            assertThat(exprText)
                    .as("Filter must NOT include admin-tagged chunks for non-admin caller (RAG-04/05)")
                    .doesNotContain("ai_agent_admin");
            return null;
        });
    }

    @Test
    void anonymousAuthentication_buildFor_failsClosed() {
        // RAG-06 fail-closed: a null/empty-roles Authentication must produce a filter that
        // matches zero chunks. RetrievalFilterBuilder encodes this via the sentinel
        // documentId == "__none__" (D-05 fail-closed) — see RetrievalFilterBuilder.java
        // lines 76-80. A future relaxation (e.g. "OR allowedRoles is null") that lets
        // untagged chunks through would be caught by the absence-of-sentinel assertion
        // below.
        Filter.Expression expr = retrievalFilterBuilder.buildFor(null);
        assertThat(expr)
                .as("Null authentication must still produce a (fail-closed) filter, not null")
                .isNotNull();

        String exprText = expr.toString();
        assertThat(exprText)
                .as("Fail-closed filter must include the unreachable-document sentinel "
                        + "(RAG-06 — RetrievalFilterBuilder.java:78)")
                .contains("__none__");

        // Defense in depth: the admin role flag (normalized) must not leak even on the
        // fail-closed path. ChunkMetadata.roleFlagKey lowercases + replaces '-' with '_',
        // so "ai-agent-admin" → "ai_agent_admin".
        assertThat(exprText)
                .as("Fail-closed filter must not reference admin role flag")
                .doesNotContain("ai_agent_admin");
    }
}
