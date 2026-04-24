package com.vn.agent.rag;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.test_support.StubChatModelConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that every {@link IngestionStatusWriter} method commits its status transition
 * in a REQUIRES_NEW boundary, handles missing documents gracefully, and truncates oversized
 * error messages to the entity column width.
 *
 * <p>Full Spring context is required — {@code @Transactional(REQUIRES_NEW)} needs the real
 * {@link org.springframework.transaction.PlatformTransactionManager} to be honoured (plain
 * Mockito on {@code DataManager} would bypass the proxy entirely).</p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(StubChatModelConfiguration.class)
class IngestionStatusWriterTest {

    @Autowired IngestionStatusWriter writer;
    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void markPending_resets_errorMessage_and_ingestedAt() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.FAILED, "prior failure", OffsetDateTime.now());

            writer.markPending(id);

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.PENDING);
            assertThat(reloaded.getErrorMessage()).isNull();
            assertThat(reloaded.getIngestedAt()).isNull();
        });
    }

    @Test
    void markProcessing_flips_to_PROCESSING_and_clears_errorMessage() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.PENDING, "stale error", null);

            writer.markProcessing(id);

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.PROCESSING);
            assertThat(reloaded.getErrorMessage()).isNull();
        });
    }

    @Test
    void markReady_flips_to_READY_sets_ingestedAt_clears_errorMessage() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.PROCESSING, "anything", null);

            writer.markReady(id, 42);

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);
            assertThat(reloaded.getErrorMessage()).isNull();
            assertThat(reloaded.getIngestedAt()).isNotNull();
        });
    }

    @Test
    void markFailed_truncates_oversized_messages_to_column_width() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.PROCESSING, null, null);
            String huge = "x".repeat(5000);

            writer.markFailed(id, huge);

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.FAILED);
            // Column is VARCHAR(1024); writer truncates to that limit.
            assertThat(reloaded.getErrorMessage()).hasSize(1024);
        });
    }

    @Test
    void markFailed_with_short_message_stores_unchanged() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.PROCESSING, null, null);

            writer.markFailed(id, "boom");

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getErrorMessage()).isEqualTo("boom");
        });
    }

    @Test
    void markCancelled_flips_to_CANCELLED_and_clears_errorMessage() {
        systemAuthenticator.runWithSystem(() -> {
            UUID id = createDoc(AiKnowledgeDocumentStatus.PROCESSING, "was failing", null);

            writer.markCancelled(id);

            AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class).id(id).one();
            assertThat(reloaded.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.CANCELLED);
            assertThat(reloaded.getErrorMessage()).isNull();
        });
    }

    @Test
    void all_mark_methods_tolerate_missing_document() {
        systemAuthenticator.runWithSystem(() -> {
            UUID missing = UUID.randomUUID();
            // None of these should throw — the document was deleted out from under us.
            assertThatCode(() -> writer.markPending(missing)).doesNotThrowAnyException();
            assertThatCode(() -> writer.markProcessing(missing)).doesNotThrowAnyException();
            assertThatCode(() -> writer.markReady(missing, 1)).doesNotThrowAnyException();
            assertThatCode(() -> writer.markFailed(missing, "x")).doesNotThrowAnyException();
            assertThatCode(() -> writer.markCancelled(missing)).doesNotThrowAnyException();
        });
    }

    private UUID createDoc(AiKnowledgeDocumentStatus status, String errorMessage, OffsetDateTime ingestedAt) {
        AiKnowledgeDocument doc = metadata.create(AiKnowledgeDocument.class);
        doc.setFileName("fixture-" + UUID.randomUUID() + ".md");
        doc.setStatus(status);
        doc.setErrorMessage(errorMessage);
        doc.setIngestedAt(ingestedAt);
        return dataManager.save(doc).getId();
    }
}
