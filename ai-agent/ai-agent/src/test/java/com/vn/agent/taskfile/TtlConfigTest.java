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
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 REQ-4 (LIFE-01) — pins the default TTL value of 86400 (24h) seconds against the
 * baseline {@link AiTaskFileProperties} bean. Sibling classes
 * {@link TtlConfigSentinelSkipsCleanupTest} and {@link TtlConfigFkCascadeUnderSentinelTest}
 * cover the {@code -1} sentinel branch and the FK-cascade-still-works invariant.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class TtlConfigTest {

    @Autowired
    private AiTaskFileProperties taskFileProperties;

    @Test
    void defaultTtlSecondsIs86400() {
        assertThat(taskFileProperties.getTtlSeconds())
                .as("default ai-agent.task-file.ttl-seconds must be 86400 (24h) — REQ-4 LIFE-01")
                .isEqualTo(86_400L);
    }
}

/**
 * Phase 13.1 REQ-4 sentinel branch: {@code ai-agent.task-file.ttl-seconds=-1} causes the
 * cleanup job to log once at INFO and short-circuit; expired rows survive across multiple
 * cleanup invocations.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.task-file.ttl-seconds=-1"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class TtlConfigSentinelSkipsCleanupTest {

    @Autowired private AiTaskFileProperties taskFileProperties;
    @Autowired private AiTaskFileCleanupJob cleanupJob;
    @Autowired private AiTaskFileRepository repository;
    @Autowired private AiTaskFileMediaResolver resolver;
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Metadata metadata;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        TtlConfigTestSupport.cleanupAll(systemAuthenticator, unconstrainedDataManager,
                fileStorageLocator, seededTaskFileIds, seededConversationIds, seededBlobs);
    }

    @Test
    void sentinelMinusOneSkipsCleanup() {
        // Confirm the property bean reflects the sentinel.
        assertThat(taskFileProperties.getTtlSeconds()).isEqualTo(-1L);

        UUID conversationId = TtlConfigTestSupport.createConversation(systemAuthenticator,
                unconstrainedDataManager, metadata, seededConversationIds, "ttl-sentinel-user");
        FileRef blobRef = TtlConfigTestSupport.saveBlob(systemAuthenticator, fileStorageLocator,
                seededBlobs, "ttl-sentinel.png", "sentinel".getBytes(StandardCharsets.UTF_8));
        UUID taskFileId = TtlConfigTestSupport.seedTaskFile(systemAuthenticator,
                unconstrainedDataManager, metadata, seededTaskFileIds,
                conversationId, "system", "ttl-sentinel.png", "image/png", blobRef,
                /* sizeBytes */ 8L,
                /* expiresAt — already past the wall-clock so a non-sentinel cleanup would reap it */
                OffsetDateTime.now().minusHours(1));

        // Two cleanup invocations exercise the once-only INFO log path AND the "still skipped" path.
        cleanupJob.deleteExpiredTaskFiles();
        cleanupJob.deleteExpiredTaskFiles();

        boolean rowStillExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class)
                        .id(taskFileId).optional().isPresent());
        assertThat(rowStillExists)
                .as("sentinel TTL must skip the cleanup-job purge even on already-expired rows " +
                        "(FK cascade is the only growth bound)")
                .isTrue();

        int directRepositoryRemoved = systemAuthenticator.withSystem(() ->
                repository.deleteAllExpired(OffsetDateTime.now()));
        assertThat(directRepositoryRemoved)
                .as("opportunistic chat cleanup calls the repository directly, so the repository " +
                        "must also honor ttl-seconds=-1")
                .isZero();

        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(conversationId));
        assertThat(resolved.media())
                .as("sentinel TTL disables active-row expiry filtering; old rows remain model context")
                .hasSize(1);
    }
}

/**
 * Phase 13.1 REQ-4 FK-cascade invariant: even with {@code ttl-seconds=-1}, deleting the
 * parent {@link AiConversation} removes its {@link AiTaskFile} children via the FK
 * {@code deleteCascade=true} clause from Liquibase 090. Sentinel mode does NOT break that.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.task-file.ttl-seconds=-1"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class TtlConfigFkCascadeUnderSentinelTest {

    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Metadata metadata;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        TtlConfigTestSupport.cleanupAll(systemAuthenticator, unconstrainedDataManager,
                fileStorageLocator, seededTaskFileIds, seededConversationIds, seededBlobs);
    }

    @Test
    void fkCascadeStillWorksUnderSentinel() {
        UUID conversationId = TtlConfigTestSupport.createConversation(systemAuthenticator,
                unconstrainedDataManager, metadata, seededConversationIds, "ttl-cascade-user");
        FileRef blobRef = TtlConfigTestSupport.saveBlob(systemAuthenticator, fileStorageLocator,
                seededBlobs, "ttl-cascade.txt", "cascade".getBytes(StandardCharsets.UTF_8));
        UUID taskFileId = TtlConfigTestSupport.seedTaskFile(systemAuthenticator,
                unconstrainedDataManager, metadata, seededTaskFileIds,
                conversationId, "ttl-cascade-user", "ttl-cascade.txt", "text/plain", blobRef,
                7L, OffsetDateTime.now().plusHours(24));

        // Delete the parent conversation; FK cascade must reap the child task-file row.
        systemAuthenticator.runWithSystem(() -> {
            AiConversation conversation = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            unconstrainedDataManager.remove(conversation);
        });
        // Conversation gone — drop from the cleanup tracker so @AfterEach skips a no-op load.
        seededConversationIds.remove(conversationId);

        boolean taskFileStillExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class)
                        .id(taskFileId).optional().isPresent());
        assertThat(taskFileStillExists)
                .as("FK_AI_TASK_FILE__ON_CONVERSATION cascade must reap the child row even under " +
                        "ttl-seconds=-1 (REQ-4 / Phase 13 invariant preserved)")
                .isFalse();
        // AiTaskFile already gone — drop from the tracker.
        seededTaskFileIds.remove(taskFileId);
    }
}

/**
 * Shared seeding + cleanup helpers for the three TTL test fixtures.
 */
final class TtlConfigTestSupport {

    private TtlConfigTestSupport() { /* no instances */ }

    static UUID createConversation(SystemAuthenticator systemAuthenticator,
                                   UnconstrainedDataManager unconstrainedDataManager,
                                   Metadata metadata,
                                   List<UUID> tracker,
                                   String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("ttl-config test conversation");
            return unconstrainedDataManager.save(conversation).getId();
        });
        tracker.add(conversationId);
        return conversationId;
    }

    static UUID seedTaskFile(SystemAuthenticator systemAuthenticator,
                             UnconstrainedDataManager unconstrainedDataManager,
                             Metadata metadata,
                             List<UUID> tracker,
                             UUID conversationId, String username,
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
        tracker.add(taskFileId);
        return taskFileId;
    }

    static FileRef saveBlob(SystemAuthenticator systemAuthenticator,
                            FileStorageLocator fileStorageLocator,
                            List<FileRef> tracker,
                            String filename, byte[] content) {
        FileRef ref = systemAuthenticator.withSystem(() -> {
            FileStorage fileStorage = fileStorageLocator.getDefault();
            return fileStorage.saveStream(filename, new ByteArrayInputStream(content));
        });
        tracker.add(ref);
        return ref;
    }

    static void cleanupAll(SystemAuthenticator systemAuthenticator,
                           UnconstrainedDataManager unconstrainedDataManager,
                           FileStorageLocator fileStorageLocator,
                           List<UUID> seededTaskFileIds,
                           List<UUID> seededConversationIds,
                           List<FileRef> seededBlobs) {
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
                    // Best-effort.
                }
            }
        });
        seededTaskFileIds.clear();
        seededConversationIds.clear();
        seededBlobs.clear();
    }
}
