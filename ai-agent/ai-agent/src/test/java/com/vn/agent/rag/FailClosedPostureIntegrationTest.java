package com.vn.agent.rag;

import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentUserRole;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP fail-closed posture (RAG-04, RAG-05): an empty-roles document must be invisible to any
 * non-admin caller, and a user with no matching role must see zero hits for a role-gated document.
 *
 * <p>Admin-bypass toggle tests live in the {@link AdminBypassOff} nested class because they need a
 * different property context ({@code admin-bypass=false}).</p>
 */
class FailClosedPostureIntegrationTest extends AbstractRagIntegrationTest {

    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired RetrievalFilterBuilder retrievalFilterBuilder;

    @AfterEach
    void cleanVectors() {
        systemAuthenticator.runWithSystem(this::deleteAllVectors);
    }

    @Test
    void test_document_with_empty_allowed_roles_invisible_to_non_admin() {
        runAsAdmin(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md", List.of());

            Authentication user = authWith(AiAgentUserRole.CODE);
            Filter.Expression filter = retrievalFilterBuilder.buildFor(user);
            assertThat(filter).as("non-admin MUST receive a concrete filter, never null").isNotNull();

            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(50)
                    .filterExpression(filter)
                    .build());
            Set<String> docIds = hits.stream()
                    .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                    .collect(Collectors.toSet());
            assertThat(docIds)
                    .as("empty-allowedRoles document MUST be invisible to non-admin (fail-closed)")
                    .doesNotContain(id.toString());
        });
    }

    @Test
    void test_non_admin_with_no_matching_role_sees_nothing() {
        runAsAdmin(() -> {
            // Document gated on the row-level role; user holds the plain user role only.
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-beta.md",
                    List.of(AiAgentTestBetaRole.CODE));

            Authentication user = authWith(AiAgentUserRole.CODE);
            Filter.Expression filter = retrievalFilterBuilder.buildFor(user);
            assertThat(filter).isNotNull();

            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("pricing")
                    .topK(50)
                    .filterExpression(filter)
                    .build());
            Set<String> docIds = hits.stream()
                    .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                    .collect(Collectors.toSet());
            assertThat(docIds)
                    .as("user without matching role MUST see zero hits for that documentId")
                    .doesNotContain(id.toString());
        });
    }

    @Test
    void test_null_authentication_sees_nothing() {
        runAsAdmin(() -> {
            uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md", List.of(AiAgentUserRole.CODE));

            // D-05 fail-closed: null auth collapses to the empty-roles sentinel branch, not null.
            Filter.Expression filter = retrievalFilterBuilder.buildFor(null);
            assertThat(filter).as("null authentication MUST NOT return null (fail-open)").isNotNull();

            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(50)
                    .filterExpression(filter)
                    .build());
            assertThat(hits)
                    .as("null authentication MUST return zero retrieval hits (fail-closed)")
                    .isEmpty();
        });
    }

    // ---------------------------------------------------------------------
    // Admin bypass OFF — admin is treated like any other role.
    // ---------------------------------------------------------------------
    @TestPropertySource(properties = "jmix.ai-agent.rag.admin-bypass=false")
    static class AdminBypassOff extends AbstractRagIntegrationTest {

        @Autowired SystemAuthenticator systemAuthenticator;
        @Autowired RetrievalFilterBuilder retrievalFilterBuilder;

        @AfterEach
        void cleanVectors() {
            systemAuthenticator.runWithSystem(this::deleteAllVectors);
        }

        @Test
        void test_admin_bypass_off_still_sees_same_docs_when_role_matches() {
            runAsAdmin(() -> {
                UUID alphaId = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                        List.of(AiAgentUserRole.CODE));
                UUID gammaId = uploadAndAwaitReady("classpath:ai-kb/fixture-gamma.md",
                        List.of(AiAgentAdminRole.CODE));

                Authentication admin = authWith(AiAgentAdminRole.CODE);
                Filter.Expression filter = retrievalFilterBuilder.buildFor(admin);
                assertThat(filter)
                        .as("with admin-bypass=false, admin MUST receive a concrete role filter")
                        .isNotNull();

                List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                        .query("onboarding compliance")
                        .topK(50)
                        .filterExpression(filter)
                        .build());
                Set<String> docIds = hits.stream()
                        .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                        .collect(Collectors.toSet());
                assertThat(docIds)
                        .as("admin with admin-bypass=false sees only docs whose allowedRoles include admin")
                        .contains(gammaId.toString())
                        .doesNotContain(alphaId.toString());
            });
        }
    }

    private static Authentication authWith(String... roles) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        return new UsernamePasswordAuthenticationToken("user", "n/a", auths);
    }
}
