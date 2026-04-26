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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.reset;

/**
 * RAG-01 retry + RAG-03 failure atomicity coverage.
 *
 * <p>Retry behaviour (the first test) models transient infrastructure failures inside the
 * {@link VectorStore#add} path. The {@link AsyncIngestionWorker} itself does NOT retry — Spring
 * AI's {@code spring.ai.retry.*} infrastructure wraps the underlying {@code EmbeddingModel} /
 * {@code VectorStore} calls when the real starter is wired. In this test harness we use a
 * {@link SpyBean} to inject a transient failure count of 2, then call-real thereafter; because
 * the worker catches any failure and flips to {@code FAILED} (no built-in retry), this test
 * asserts the atomicity side (no partial vectors) on the transient-then-flake scenario too.
 * Real-retry-succeeds requires the live starter and is covered by live tagged tests.</p>
 *
 * <p>Permanent failure (the second test) asserts the headline RAG-03 contract: status becomes
 * FAILED, errorMessage is populated, and similaritySearch shows zero chunks for the failed
 * documentId (the worker's catch block calls {@code vectorStore.delete(Filter)} to clean up
 * any partial writes before marking failed).</p>
 */
class IngestionRetryAndFailureIntegrationTest extends AbstractRagIntegrationTest {

    @Autowired SystemAuthenticator systemAuthenticator;
    @SpyBean VectorStore vectorStoreSpy;

    @AfterEach
    void resetSpy() {
        systemAuthenticator.runWithSystem(() -> {
            try {
                doCallRealMethod().when(vectorStoreSpy).add(anyList());
            } catch (Throwable ignore) { /* best-effort */ }
            reset(vectorStoreSpy);
            try {
                deleteAllVectors();
            } catch (Exception ignore) { /* tolerate */ }
        });
    }

    @Test
    void test_transient_vectorstore_failure_does_not_leave_partial_vectors() {
        runAsAdmin(() -> {
            AtomicInteger calls = new AtomicInteger(0);

            // First 2 calls throw (transient), subsequent calls delegate to the real bean.
            doAnswer(inv -> {
                if (calls.incrementAndGet() <= 2) {
                    throw new RuntimeException("transient vector store error #" + calls.get());
                }
                return inv.callRealMethod();
            }).when(vectorStoreSpy).add(anyList());

            // Upload path: worker catches the throw, cleans up, marks FAILED. The contract we
            // verify here is atomicity (no partial vectors survive) — NOT retry success, which
            // requires the real Spring AI retry starter around the embedding/vector infra.
            AiKnowledgeDocument saved = uploadService.upload(
                    "classpath:ai-kb/fixture-alpha.md", "text/markdown",
                    List.of(AiAgentUserRole.CODE));

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(saved.getId()).one();
            // Either FAILED (no retry wrapper present) OR READY (retry wrapper recovered) — both
            // are valid under the atomicity contract. Assert NO partial vectors survive.
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(50)
                    .filterExpression(new FilterExpressionBuilder()
                            .eq(ChunkMetadata.DOCUMENT_ID, saved.getId().toString()).build())
                    .build());

            if (reloaded.getStatus() == AiKnowledgeDocumentStatus.FAILED) {
                assertThat(hits)
                        .as("RAG-03 atomicity: failed ingest MUST leave zero chunks for the documentId")
                        .isEmpty();
                assertThat(reloaded.getErrorMessage())
                        .as("FAILED status MUST carry a non-blank error message")
                        .isNotBlank();
            } else {
                // Retry recovered; the chunks are legitimate.
                assertThat(hits).isNotEmpty();
            }
        });
    }

    @Test
    void test_permanent_failure_marks_failed_with_message_and_no_vectors() {
        runAsAdmin(() -> {
            doAnswer(inv -> {
                throw new RuntimeException("induced permanent embed/add failure");
            }).when(vectorStoreSpy).add(anyList());

            AiKnowledgeDocument saved = uploadService.upload(
                    "classpath:ai-kb/fixture-alpha.md", "text/markdown",
                    List.of(AiAgentUserRole.CODE));

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(saved.getId()).one();
            assertThat(reloaded.getStatus())
                    .as("permanent vector-store failure MUST drive status to FAILED")
                    .isEqualTo(AiKnowledgeDocumentStatus.FAILED);
            assertThat(reloaded.getErrorMessage())
                    .as("errorMessage MUST carry a class-prefixed operator-readable summary")
                    .contains("induced permanent embed/add failure");
            // Note: AiKnowledgeDocument has no chunkCount field in this phase — atomicity is
            // asserted directly via similaritySearch returning zero chunks for the documentId.
            Filter.Expression docFilter = new FilterExpressionBuilder()
                    .eq(ChunkMetadata.DOCUMENT_ID, saved.getId().toString()).build();
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("anything")
                    .topK(50)
                    .filterExpression(docFilter)
                    .build());
            assertThat(hits)
                    .as("RAG-03 atomicity: no chunk MUST survive a failed ingest")
                    .isEmpty();
        });
    }

    @Test
    void test_failure_after_partial_add_cleans_up_all_partial_chunks() {
        runAsAdmin(() -> {
            AtomicInteger calls = new AtomicInteger(0);

            // Write the batch for real (partial state), then throw — models a mid-stream
            // failure where chunks are committed before an error surfaces. The worker's
            // catch block MUST delete them via the documentId filter.
            doAnswer(inv -> {
                int n = calls.incrementAndGet();
                inv.callRealMethod();
                throw new RuntimeException("induced mid-stream failure after batch " + n);
            }).when(vectorStoreSpy).add(any(List.class));

            AiKnowledgeDocument saved = uploadService.upload(
                    "classpath:ai-kb/fixture-alpha.md", "text/markdown",
                    List.of(AiAgentUserRole.CODE));

            // Even if only one batch was written, cleanup MUST zero out the documentId's vectors.
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("onboarding")
                    .topK(50)
                    .filterExpression(new FilterExpressionBuilder()
                            .eq(ChunkMetadata.DOCUMENT_ID, saved.getId().toString()).build())
                    .build());
            assertThat(hits)
                    .as("partial-write failure MUST be cleaned up — zero chunks for this documentId")
                    .isEmpty();

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class)
                    .id(saved.getId()).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.FAILED);
        });
    }
}
