package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.security.AiAgentUserRole;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP success criterion #1: upload → READY happy path end-to-end against Testcontainers
 * pgvector. Also covers RAG-01 format matrix (MD / PDF / TXT / HTML via Tika).
 */
class UploadToReadyIntegrationTest extends AbstractRagIntegrationTest {

    @Autowired SystemAuthenticator systemAuthenticator;

    @AfterEach
    void cleanVectors() {
        systemAuthenticator.runWithSystem(this::deleteAllVectors);
    }

    @Test
    void test_upload_transitions_pending_to_ready_with_chunks() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                    List.of(AiAgentUserRole.CODE));

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);
            assertThat(reloaded.getErrorMessage()).isNull();
            assertThat(reloaded.getIngestedAt()).isNotNull();

            // Vector count check via filter-scoped similaritySearch.
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("Alpha Corp onboarding")
                    .topK(10)
                    .filterExpression(new FilterExpressionBuilder()
                            .eq(ChunkMetadata.DOCUMENT_ID, id.toString()).build())
                    .build());
            assertThat(hits)
                    .as("upload must produce at least one retrievable chunk for %s", id)
                    .isNotEmpty();
            assertThat(hits).allSatisfy(doc ->
                    assertThat(doc.getMetadata()).containsEntry(ChunkMetadata.DOCUMENT_ID, id.toString()));
        });
    }

    @Test
    void test_upload_with_unknown_role_rejects_and_persists_nothing() {
        systemAuthenticator.runWithSystem(() -> {
            assertThatThrownBy(() ->
                    uploadService.upload("classpath:ai-kb/fixture-alpha.md", "text/markdown",
                            List.of("phantom-role-does-not-exist")))
                    .isInstanceOf(UnknownRoleCodeException.class);

            // No entity persisted (JPA transaction rolled back on the throw).
            List<AiKnowledgeDocument> all = dataManager.load(AiKnowledgeDocument.class)
                    .query("select d from ai_AiKnowledgeDocument d")
                    .list();
            assertThat(all).as("unknown role code must roll back the upload transaction").isEmpty();
        });
    }

    @Test
    void test_upload_with_empty_roles_still_reaches_ready() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md", List.of());
            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);
            // No role-flag metadata should exist on produced chunks — visibility is fail-closed
            // for non-admin (asserted in FailClosedPostureIntegrationTest).
        });
    }

    /**
     * RAG-01 format matrix — every Tika-dispatched format must drive status to READY and
     * produce retrievable chunks. The TikaDocumentReader contract is proven via real content,
     * not a synthetic stub.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "classpath:ai-kb/fixture-alpha.md",
            "classpath:ai-kb/fixture-delta.pdf",
            "classpath:ai-kb/fixture-epsilon.txt",
            "classpath:ai-kb/fixture-zeta.html"
    })
    void test_format_matrix_all_rag01_formats_reach_ready(String sourceUri) {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady(sourceUri, List.of(AiAgentUserRole.CODE));

            Optional<AiKnowledgeDocument> reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(id).optional();
            assertThat(reloaded).isPresent();
            assertThat(reloaded.get().getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);

            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("overview")
                    .topK(5)
                    .filterExpression(new FilterExpressionBuilder()
                            .eq(ChunkMetadata.DOCUMENT_ID, id.toString()).build())
                    .build());
            assertThat(hits)
                    .as("TikaDocumentReader must produce retrievable chunks for %s", sourceUri)
                    .isNotEmpty();
        });
    }
}
