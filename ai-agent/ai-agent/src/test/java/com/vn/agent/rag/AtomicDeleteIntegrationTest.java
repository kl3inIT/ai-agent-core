package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.security.AiAgentUserRole;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * ROADMAP success criterion #4: atomic delete. Delete removes entity + every vector for that
 * documentId; a {@link VectorStore#delete} failure leaves the entity intact so the operator
 * can retry. Orphan vectors visible to retrieval are structurally impossible.
 */
class AtomicDeleteIntegrationTest extends AbstractRagIntegrationTest {

    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired CancellationRegistry cancellationRegistry;

    // @SpyBean wraps the real PgVectorStore so we can intercept .delete(Filter) calls for the
    // rollback test. Mockito resets to real behaviour between tests via the explicit reset()
    // + doCallRealMethod() in @AfterEach.
    @SpyBean VectorStore vectorStoreSpy;

    @AfterEach
    void cleanVectors() {
        systemAuthenticator.runWithSystem(() -> {
            reset(vectorStoreSpy);
            try {
                deleteAllVectors();
            } catch (Exception ignore) {
                // If a previous test left the spy in a delete-throws state, tolerate here.
            }
        });
    }

    @Test
    void test_delete_removes_entity_and_vectors() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                    List.of(AiAgentUserRole.CODE));

            // Baseline: chunks exist for this documentId.
            List<Document> before = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(10)
                    .filterExpression(idFilter(id))
                    .build());
            assertThat(before).isNotEmpty();

            documentService.delete(id);

            // Entity is gone.
            Optional<AiKnowledgeDocument> reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(id).optional();
            assertThat(reloaded).as("entity row MUST be removed").isEmpty();

            // No chunk remains for this documentId.
            List<Document> after = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(10)
                    .filterExpression(idFilter(id))
                    .build());
            assertThat(after).as("no vector MUST survive delete").isEmpty();
        });
    }

    @Test
    void test_delete_missing_document_throws_not_found() {
        systemAuthenticator.runWithSystem(() -> {
            UUID random = UUID.randomUUID();
            assertThatThrownBy(() -> documentService.delete(random))
                    .isInstanceOf(DocumentNotFoundException.class);
        });
    }

    @Test
    void test_delete_with_vectorstore_failure_rolls_back_entity_removal() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                    List.of(AiAgentUserRole.CODE));

            // Arm the spy: next call to delete(Filter.Expression) throws.
            doThrow(new RuntimeException("induced vectorStore.delete failure"))
                    .when(vectorStoreSpy).delete(any(Filter.Expression.class));

            assertThatThrownBy(() -> documentService.delete(id))
                    .hasMessageContaining("induced vectorStore.delete failure");

            // Entity MUST still be present — the @Transactional rollback kept it.
            Optional<AiKnowledgeDocument> reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(id).optional();
            assertThat(reloaded)
                    .as("failed vectorStore.delete MUST roll back entity removal (RAG-07 atomicity)")
                    .isPresent();
            assertThat(reloaded.get().getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);

            // Restore real behaviour for cleanup.
            doCallRealMethod().when(vectorStoreSpy).delete(any(Filter.Expression.class));
        });
    }

    @Test
    void test_reingest_cancels_and_reschedules() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = uploadAndAwaitReady("classpath:ai-kb/fixture-alpha.md",
                    List.of(AiAgentUserRole.CODE));

            documentService.reingest(id);

            // With SyncTaskExecutor the re-dispatched worker ran inline and status is READY again.
            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);
            assertThat(reloaded.getIngestedAt()).isNotNull();

            // Flag cleared — no leak across generations.
            assertThat(cancellationRegistry.isCancelled(id))
                    .as("cancellation flag MUST be cleared after reingest terminal state")
                    .isFalse();

            // Chunks exist and are fresh (new ones; the delete-then-reindex contract).
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(10)
                    .filterExpression(idFilter(id))
                    .build());
            assertThat(hits).isNotEmpty();
        });
    }

    private static Filter.Expression idFilter(UUID id) {
        return new FilterExpressionBuilder()
                .eq(ChunkMetadata.DOCUMENT_ID, id.toString())
                .build();
    }
}
