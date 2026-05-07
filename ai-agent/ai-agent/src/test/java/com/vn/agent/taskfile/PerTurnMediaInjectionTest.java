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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 TEST-18 / REQ-2 (RES-01) — pins the per-turn-all multi-turn injection contract.
 *
 * <p>Seeds a single text attachment into a conversation, then calls
 * {@link AiTaskFileMediaResolver#resolveActive(UUID)} 3 times in sequence (mimicking 3
 * sequential chat turns over the same conversation). All 3 invocations MUST return a
 * {@link AiTaskFileMediaResolver.DocumentText} list of size 1 whose text contains the
 * original file contents — proving the resolver re-emits file context on every turn rather
 * than marking the row as "consumed" after the first turn (the Phase 13 single-turn semantics).
 *
 * <p>Also asserts that {@link AiTaskFileRepository} carries NO method named {@code markInjected}
 * or {@code loadPending} (any overload, via {@link Class#getDeclaredMethods()}). The Phase 13
 * pending-state stamping path was deleted by Plan 13.1-02 and the resolver now loads rows
 * directly through {@link io.jmix.core.DataManager}.
 *
 * <p>Pattern source: {@code AiTaskFileMediaResolverIntegrationTest} (closest analog).
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.task-file.ttl-seconds=86400",
                "ai-agent.task-file.per-turn-max-files=10",
                "ai-agent.task-file.per-turn-max-total-bytes=52428800"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class PerTurnMediaInjectionTest {

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
     * The TEST-18 must-have: 3 sequential resolver invocations over the same conversation
     * MUST all see the same {@link org.springframework.ai.content.Media} bytes. Phase 13's
     * single-turn pending-state marker is structurally gone; every turn re-injects.
     */
    @Test
    void threeSequentialTurnsOverSameDocumentAllReceiveDocumentText() {
        String originalText = "phase 13.1 persistent context sample\nrow-count=3\nsummary=ok";
        byte[] originalBytes = originalText.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        UUID conversationId = createConversation("alice");
        FileRef blobRef = saveBlob("sample.txt", originalBytes);
        seedTaskFile(conversationId, "system", "sample.txt",
                "text/plain",
                blobRef, (long) originalBytes.length,
                OffsetDateTime.now().plusHours(24));

        for (int turn = 0; turn < 3; turn++) {
            AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                    resolver.resolveActive(conversationId));

            assertThat(result.media())
                    .as("turn %d: text documents are sent as DocumentText, not Media", turn)
                    .isEmpty();
            assertThat(result.documentTexts())
                    .as("turn %d: resolveActive must return one DocumentText object for the seeded row", turn)
                    .hasSize(1);
            assertThat(result.budgetExceeded())
                    .as("turn %d: single small file must not trip the budget cap (10 files / 50 MB)", turn)
                    .isFalse();
            assertThat(result.documentTexts().getFirst().text())
                    .as("turn %d: DocumentText must contain the original file contents (per-turn-all RES-01)", turn)
                    .contains(originalText);
        }
    }

    /**
     * Reflection guard: no method named {@code markInjected} or {@code loadPending} (any
     * overload) survives on {@link AiTaskFileRepository}. Iteration over
     * {@link Class#getDeclaredMethods()} catches a renamed-overload that a string scan
     * would miss. SPEC REQ-2 / REQ-6 acceptance.
     */
    @Test
    void aiTaskFileRepositoryHasNoMarkInjectedOrLoadPendingMethod() {
        Method[] declared = AiTaskFileRepository.class.getDeclaredMethods();

        assertThat(Arrays.stream(declared).noneMatch(m -> m.getName().equals("markInjected")))
                .as("AiTaskFileRepository must have NO method named markInjected (any overload) — SPEC REQ-2")
                .isTrue();

        assertThat(Arrays.stream(declared).noneMatch(m -> m.getName().equals("loadPending")))
                .as("AiTaskFileRepository must have NO method named loadPending (any overload) — SPEC REQ-2")
                .isTrue();
    }

    // ---------- helpers ----------

    private UUID createConversation(String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("per-turn-injection test conversation");
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
