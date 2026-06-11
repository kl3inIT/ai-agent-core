package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.test_support.InMemoryFileStorageConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 Plan 06 rewrite — pins the {@link AiTaskFileMediaResolver#resolveActive(UUID)}
 * happy-path contract.
 *
 * <p>Replaces the Phase 13 single-turn pending-state integration test (which has been
 * deleted along with the injected-at and message columns and the post-send stamping
 * path). The new contract: every call to {@link AiTaskFileMediaResolver#resolveActive}
 * returns ALL non-expired AiTaskFile rows for the conversation as Spring AI {@link Media} objects
 * for images or bounded document text for non-image documents, subject to the per-turn budget caps
 * in {@link AiTaskFileProperties}.
 *
 * <p>Sibling regressions live in {@code PerTurnMediaInjectionTest} (TEST-18 multi-turn) and
 * {@code BudgetCapTest} (cap + audit). This class covers the basic single-row, single-turn path.
 *
 * <p>TTL configured to 1 hour (3600s) so the seeded row is well within its window.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"jmix.ai-agent.task-file.ttl-seconds=3600"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class AiTaskFileMediaResolverIntegrationTest {

    @Autowired
    private AiTaskFileMediaResolver resolver;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededTaskFileIds) {
                unconstrainedDataManager.load(AiTaskFile.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededConversationIds) {
                unconstrainedDataManager.load(AiConversation.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (FileRef ref : seededBlobs) {
                try {
                    fileStorageLocator.getByName(ref.getStorageName()).removeFile(ref);
                } catch (Exception ignored) {
                    // Best-effort — synthetic refs may not have a backing blob.
                }
            }
        });
        seededTaskFileIds.clear();
        seededConversationIds.clear();
        seededBlobs.clear();
    }

    /**
     * resolveActive returns a DocumentText list for non-image documents. This pins the
     * UAT-fix-02 contract: document files are extracted to text instead of being sent as
     * non-image Media parts.
     */
    @Test
    void resolveActiveReturnsDocumentTextForPlainTextFile() {
        byte[] originalBytes = "hello task-file world".getBytes(StandardCharsets.UTF_8);
        UUID conversationId = createConversation("alice");
        FileRef blobRef = saveBlob("resolver-happy.txt", originalBytes);
        seedTaskFile(conversationId, "alice",
                "resolver-happy.txt", "text/plain", blobRef,
                (long) originalBytes.length,
                OffsetDateTime.now().plusHours(1));

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withUser("alice", () ->
                resolver.resolveActive(conversationId));

        assertThat(result).isNotNull();
        assertThat(result.media())
                .as("plain text files are extracted to DocumentText, not sent as Media")
                .isEmpty();
        assertThat(result.documentTexts())
                .as("resolveActive must return one DocumentText object for the single seeded row")
                .hasSize(1);
        assertThat(result.budgetExceeded())
                .as("single small file must not trip the budget cap")
                .isFalse();
        AiTaskFileMediaResolver.DocumentText documentText = result.documentTexts().getFirst();
        assertThat(documentText.filename()).isEqualTo("resolver-happy.txt");
        assertThat(documentText.text())
                .as("DocumentText must contain the extracted file contents")
                .contains("hello task-file world");
        assertThat(documentText.truncated()).isFalse();
    }

    /**
     * Image files remain Media so vision-capable models receive image_url content parts.
     */
    @Test
    void resolveActiveReturnsMediaForImageFile() {
        byte[] imageBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        UUID conversationId = createConversation("alice");
        FileRef blobRef = saveBlob("resolver-image.png", imageBytes);
        seedTaskFile(conversationId, "alice",
                "resolver-image.png", "image/png", blobRef,
                (long) imageBytes.length,
                OffsetDateTime.now().plusHours(1));

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withUser("alice", () ->
                resolver.resolveActive(conversationId));

        assertThat(result.media())
                .as("image files must still be sent as Media")
                .hasSize(1);
        assertThat(result.documentTexts())
                .as("image files should not produce document text blocks")
                .isEmpty();
        assertThat(result.media().getFirst().getDataAsByteArray())
                .as("Media bytes must equal the original image bytes")
                .isEqualTo(imageBytes);
    }

    /**
     * resolveActive on a conversation with NO non-expired rows returns Resolved.empty().
     */
    @Test
    void resolveActiveReturnsEmptyWhenNoRows() {
        UUID conversationId = createConversation("alice");

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withUser("alice", () ->
                resolver.resolveActive(conversationId));

        assertThat(result.isEmpty())
                .as("resolveActive on an empty conversation must return Resolved.empty()")
                .isTrue();
        assertThat(result.budgetExceeded()).isFalse();
    }

    /**
     * resolveActive with a null conversationId returns Resolved.empty() — defensive guard for
     * test/admin call sites without a chat conversation.
     */
    @Test
    void resolveActiveReturnsEmptyWhenConversationIdNull() {
        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(null));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.budgetExceeded()).isFalse();
    }

    // ---------- helpers ----------

    private UUID createConversation(String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("resolver-test conversation");
            return unconstrainedDataManager.save(conversation).getId();
        });
        seededConversationIds.add(conversationId);
        return conversationId;
    }

    private UUID seedTaskFile(UUID conversationId, String username,
                              String filename, String contentType, FileRef storageRef,
                              Long sizeBytes, OffsetDateTime expiresAt) {
        UUID taskFileId = systemAuthenticator.withSystem(() -> {
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            AiTaskFile row = metadata.create(AiTaskFile.class);
            row.setConversation(conversationRef);
            row.setUserUsername(username);
            row.setFilename(filename);
            row.setContentType(contentType);
            row.setSizeBytes(sizeBytes == null ? 0L : sizeBytes);
            row.setStorageRef(storageRef);
            row.setExpiresAt(expiresAt);
            return unconstrainedDataManager.save(row).getId();
        });
        seededTaskFileIds.add(taskFileId);
        return taskFileId;
    }

    private FileRef saveBlob(String filename, byte[] content) {
        FileRef ref = systemAuthenticator.withSystem(() -> {
            FileStorage fileStorage = fileStorageLocator.getDefault();
            return fileStorage.saveStream(filename, new ByteArrayInputStream(content));
        });
        seededBlobs.add(ref);
        return ref;
    }
}
