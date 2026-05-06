package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiTaskFile;
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
 * returns ALL non-expired AiTaskFile rows for the conversation as Spring AI {@link Media} objects,
 * subject to the per-turn budget caps in {@link AiTaskFileProperties}.
 *
 * <p>Sibling regressions live in {@code PerTurnMediaInjectionTest} (TEST-18 multi-turn) and
 * {@code BudgetCapTest} (cap + audit). This class covers the basic single-row, single-turn path.
 *
 * <p>TTL configured to 1 hour (3600s) so the seeded row is well within its window.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.task-file.ttl-seconds=3600"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
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
     * resolveActive returns a Media list whose bytes match the original file contents.
     * Validates the resolver's blob-read + Media-build path end-to-end.
     */
    @Test
    void resolveActiveReturnsMediaWithOriginalBytes() {
        byte[] originalBytes = "hello task-file world".getBytes(StandardCharsets.UTF_8);
        UUID conversationId = createConversation("resolver-happy-user");
        FileRef blobRef = saveBlob("resolver-happy.txt", originalBytes);
        seedTaskFile(conversationId, "resolver-happy-user",
                "resolver-happy.txt", "text/plain", blobRef,
                (long) originalBytes.length,
                OffsetDateTime.now().plusHours(1));

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(conversationId));

        assertThat(result).isNotNull();
        assertThat(result.media())
                .as("resolveActive must return one Media object for the single seeded row")
                .hasSize(1);
        assertThat(result.budgetExceeded())
                .as("single small file must not trip the budget cap")
                .isFalse();
        assertThat(result.media().get(0).getDataAsByteArray())
                .as("Media bytes must equal the original file contents")
                .isEqualTo(originalBytes);
    }

    /**
     * resolveActive on a conversation with NO non-expired rows returns Resolved.empty().
     */
    @Test
    void resolveActiveReturnsEmptyWhenNoRows() {
        UUID conversationId = createConversation("resolver-empty-user");

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
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
