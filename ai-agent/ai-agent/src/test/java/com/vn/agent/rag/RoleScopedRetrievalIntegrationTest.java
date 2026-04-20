package com.vn.agent.rag;

import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentUserRole;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP success criterion #3: role-scoped retrieval. A non-admin caller MUST NOT retrieve
 * chunks whose {@code allowedRoles} do not include their granted role.
 *
 * <p>Uses {@link RetrievalFilterBuilder}-produced {@link Filter.Expression} directly against
 * the real {@link org.springframework.ai.vectorstore.VectorStore}, mirroring the production
 * wiring inside {@code DefaultChatServiceImpl} (which threads the filter through
 * {@code VectorStoreDocumentRetriever.FILTER_EXPRESSION}).</p>
 */
class RoleScopedRetrievalIntegrationTest extends AbstractRagIntegrationTest {

    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired RetrievalFilterBuilder retrievalFilterBuilder;

    private UUID alphaId;
    private UUID betaId;
    private UUID gammaId;

    @BeforeEach
    void seed() {
        systemAuthenticator.runWithSystem(() -> {
            // Beta uses AiAgentTestBetaRole, a test-scoped @ResourceRole registered under the
            // test classpath — a real resolvable role code that AiAgentUserRole holders do NOT
            // hold, so user→beta visibility must fail-closed.
            alphaId = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                    List.of(AiAgentUserRole.CODE));
            betaId = uploadAndAwaitReady("classpath:ai-kb/fixture-beta.md",
                    List.of(AiAgentTestBetaRole.CODE));
            gammaId = uploadAndAwaitReady("classpath:ai-kb/fixture-gamma.md",
                    List.of(AiAgentAdminRole.CODE));
        });
    }

    @AfterEach
    void cleanVectors() {
        systemAuthenticator.runWithSystem(this::deleteAllVectors);
    }

    @Test
    void test_admin_bypass_sees_all_three() {
        Authentication admin = authWith(AiAgentAdminRole.CODE);
        Filter.Expression filter = retrievalFilterBuilder.buildFor(admin);

        // D-06: admin bypass returns null — retrieve with no filter.
        assertThat(filter).as("admin with admin-bypass=true MUST produce null filter").isNull();

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("onboarding pricing compliance")
                .topK(50)
                .build());

        Set<String> docIds = hits.stream()
                .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                .collect(Collectors.toSet());
        assertThat(docIds)
                .as("admin bypass must see every seeded document")
                .contains(alphaId.toString(), betaId.toString(), gammaId.toString());
    }

    @Test
    void test_user_sees_only_alpha() {
        Authentication user = authWith(AiAgentUserRole.CODE);
        Filter.Expression filter = retrievalFilterBuilder.buildFor(user);
        assertThat(filter).isNotNull();

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("onboarding pricing compliance")
                .topK(50)
                .filterExpression(filter)
                .build());

        Set<String> docIds = hits.stream()
                .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                .collect(Collectors.toSet());
        assertThat(docIds).as("user must see alpha").contains(alphaId.toString());
        assertThat(docIds).as("user MUST NOT see beta (different role)").doesNotContain(betaId.toString());
        assertThat(docIds).as("user MUST NOT see gamma (admin-only)").doesNotContain(gammaId.toString());
    }

    @Test
    void test_unauthenticated_sees_nothing() {
        // null Authentication collapses to the empty-roles fail-closed branch.
        Filter.Expression filter = retrievalFilterBuilder.buildFor(null);
        assertThat(filter).as("null authentication MUST return a fail-closed filter, not null").isNotNull();

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("anything")
                .topK(50)
                .filterExpression(filter)
                .build());
        assertThat(hits).as("empty roles fail-closed MUST match zero chunks").isEmpty();
    }

    @Test
    void test_embedding_model_mismatch_filters_out() {
        // Add a synthetic document with a DIFFERENT embeddingModel slug directly to the
        // vector store. It must be invisible to any retrieval that pins the current model.
        systemAuthenticator.runWithSystem(() -> {
            UUID otherId = UUID.randomUUID();
            Document outOfModel = Document.builder()
                    .text("Out-of-model drift chunk content for mismatch test.")
                    .metadata(java.util.Map.of(
                            ChunkMetadata.SOURCE, "synthetic://drift",
                            ChunkMetadata.DOCUMENT_ID, otherId.toString(),
                            ChunkMetadata.EMBEDDING_MODEL, "other-model-not-current",
                            ChunkMetadata.ALLOWED_ROLES, List.of(AiAgentUserRole.CODE),
                            ChunkMetadata.ROLE_FLAG_PREFIX + AiAgentUserRole.CODE, true))
                    .build();
            vectorStore.add(List.of(outOfModel));

            Authentication user = authWith(AiAgentUserRole.CODE);
            Filter.Expression filter = retrievalFilterBuilder.buildFor(user);
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("drift")
                    .topK(10)
                    .filterExpression(filter)
                    .build());

            Set<String> docIds = hits.stream()
                    .map(d -> (String) d.getMetadata().get(ChunkMetadata.DOCUMENT_ID))
                    .collect(Collectors.toSet());
            assertThat(docIds)
                    .as("D-03 drift filter MUST suppress chunks with non-current embeddingModel")
                    .doesNotContain(otherId.toString());
        });
    }

    private static Authentication authWith(String... roles) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        return new UsernamePasswordAuthenticationToken("user", "n/a", auths);
    }
}
