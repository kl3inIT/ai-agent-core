package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.test_support.InMemoryFileStorageConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageException;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13 Plan 13-05 Task 1D — pins {@link AiTaskFileCleanupJob} +
 * {@link AiTaskFileRepository#deleteAllExpired} contract.
 *
 * <p><b>Critical invariant (PATTERNS Pitfall 3 — blob-first ordering):</b> the
 * cleanup job removes the {@link io.jmix.core.FileRef} blob from
 * {@link FileStorage} BEFORE removing the {@link AiTaskFile} agentstore row.
 * On blob-remove failure the row is preserved so the next hourly retry can
 * re-attempt; this prevents orphaning a blob whose pointer row has already
 * been deleted.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>{@code expiredRowAndBlobDeleted} — happy path: row gone, blob gone.</li>
 *   <li>{@code blobDeleteFailureLeavesRowAlone} — synthetic FileRef pointing
 *       at a non-existent blob; blob delete fails; row STILL EXISTS so the
 *       next hourly tick can retry.</li>
 *   <li>{@code nonExpiredRowsUntouched} — TTL not yet elapsed; cleanup is a
 *       no-op for that row.</li>
 * </ol>
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"jmix.ai-agent.task-file.ttl-seconds=3600"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class AiTaskFileCleanupJobTest {

    @Autowired
    private AiTaskFileCleanupJob cleanupJob;

    @Autowired
    private AiTaskFileRepository repository;

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
     * Happy path: the @Scheduled tick reaps an expired row and its backing blob.
     * Asserts the row is gone from agentstore AND the blob is no longer
     * resolvable through {@code FileStorage.openStream}.
     */
    @Test
    void expiredRowAndBlobDeleted() {
        UUID conversationId = createConversation("cleanup-happy-user");
        FileRef blobRef = saveBlob("cleanup-happy.txt", "happy bytes");
        UUID taskFileId = seedTaskFile(conversationId, "cleanup-happy-user",
                "cleanup-happy.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().minusMinutes(1));

        // Direct invocation of the @Scheduled method (no scheduler involved).
        cleanupJob.deleteExpiredTaskFiles();

        // Row MUST be gone.
        boolean rowExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class).id(taskFileId).optional().isPresent());
        assertThat(rowExists)
                .as("expired row must be reaped from agentstore by deleteExpiredTaskFiles")
                .isFalse();

        // Drop tracking — the row is already gone, so @AfterEach should skip it. We
        // also drop the blob ref from cleanup tracking since the cleanup job already
        // removed it; keeping it would surface a benign FileStorageException in the
        // @AfterEach catch.
        seededTaskFileIds.remove(taskFileId);
        seededBlobs.remove(blobRef);

        // Blob MUST be gone — openStream throws on a removed FileRef.
        FileStorage fileStorage = fileStorageLocator.getByName(blobRef.getStorageName());
        assertThatThrownBy(() -> {
            try (var s = fileStorage.openStream(blobRef)) {
                // Drain a byte to force the storage layer to fault on the missing blob;
                // some FileStorage impls only check existence on first read.
                s.read();
            }
        })
                .as("blob must be removed BEFORE row (blob-first ordering — PATTERNS Pitfall 3)")
                .isInstanceOfAny(FileStorageException.class, java.io.IOException.class,
                        IllegalArgumentException.class, RuntimeException.class);
    }

    /**
     * Pitfall 3 ordering invariant: a synthetic FileRef pointing at a blob that
     * does NOT exist forces {@code FileStorage.removeFile} to fail. The
     * repository's contract is to short-circuit row deletion in that case so the
     * next hourly tick can retry without orphaning the blob (or the row).
     */
    @Test
    void blobDeleteFailureLeavesRowAlone() {
        UUID conversationId = createConversation("cleanup-blob-fail-user");
        // Synthetic FileRef — references a storage path that is not backed by a
        // real blob. FileStorage.removeFile on this ref throws.
        FileRef syntheticRef = new FileRef("fs", "nonexistent-blob-" + UUID.randomUUID() + ".bin",
                "nonexistent.txt");
        UUID taskFileId = seedTaskFile(conversationId, "cleanup-blob-fail-user",
                "nonexistent.txt", "text/plain", syntheticRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().minusMinutes(1));

        // Cleanup attempts blob-delete first; it fails; row stays.
        cleanupJob.deleteExpiredTaskFiles();

        boolean rowStillExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class).id(taskFileId).optional().isPresent());
        assertThat(rowStillExists)
                .as("blob-delete failure must short-circuit row delete (PATTERNS Pitfall 3 — "
                        + "next hourly tick will retry; never orphan a blob whose row was deleted)")
                .isTrue();
    }

    /**
     * Cleanup is bounded by the {@code expiresAt < :now} predicate. A row whose
     * TTL has not elapsed must NOT be reaped on the same tick.
     */
    @Test
    void nonExpiredRowsUntouched() {
        UUID conversationId = createConversation("cleanup-fresh-user");
        FileRef blobRef = saveBlob("fresh.txt", "still fresh");
        UUID taskFileId = seedTaskFile(conversationId, "cleanup-fresh-user",
                "fresh.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));

        cleanupJob.deleteExpiredTaskFiles();

        boolean rowStillExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class).id(taskFileId).optional().isPresent());
        assertThat(rowStillExists)
                .as("non-expired row must remain after cleanup")
                .isTrue();
        // Sanity — the repository's loadExpired predicate also excludes this row.
        List<AiTaskFile> expired = systemAuthenticator.withSystem(() ->
                repository.loadExpired(OffsetDateTime.now()));
        assertThat(expired)
                .as("loadExpired must not include rows with expiresAt > now")
                .extracting(AiTaskFile::getId)
                .doesNotContain(taskFileId);
    }

    // ---------- helpers ----------

    private UUID createConversation(String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("cleanup-test conversation");
            return unconstrainedDataManager.save(conversation).getId();
        });
        seededConversationIds.add(conversationId);
        return conversationId;
    }

    private UUID seedTaskFile(UUID conversationId, String username,
                              String filename, String contentType, FileRef storageRef,
                              OffsetDateTime injectedAt, OffsetDateTime expiresAt) {
        UUID taskFileId = systemAuthenticator.withSystem(() -> {
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            AiTaskFile row = metadata.create(AiTaskFile.class);
            row.setConversation(conversationRef);
            row.setUserUsername(username);
            row.setFilename(filename);
            row.setContentType(contentType);
            row.setSizeBytes(0L);
            row.setStorageRef(storageRef);
            assertThat(injectedAt)
                    .as("AiTaskFile.injectedAt was dropped by the Phase 13.1 schema migration")
                    .isNull();
            row.setExpiresAt(expiresAt);
            return unconstrainedDataManager.save(row).getId();
        });
        seededTaskFileIds.add(taskFileId);
        return taskFileId;
    }

    private FileRef saveBlob(String filename, String content) {
        FileRef ref = systemAuthenticator.withSystem(() -> {
            FileStorage fileStorage = fileStorageLocator.getDefault();
            return fileStorage.saveStream(filename,
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        });
        seededBlobs.add(ref);
        return ref;
    }
}
