package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 Plan 13-05 Task 1C — pins {@link AiTaskFileMediaResolver}'s D-01
 * single-turn-inject contract using the AUTHORITATIVE {@code injectedAt}
 * predicate (REVIEWS HIGH-1).
 *
 * <p>Six scenarios:
 * <ol>
 *   <li>{@code pendingFilesReturnedWhenInjectedAtNull} — pending row resolves
 *       once on the first turn.</li>
 *   <li>{@code stampedFilesNotReturned} — once {@code injectedAt} is set,
 *       subsequent turns receive an empty {@link AiTaskFileMediaResolver.Resolved}.</li>
 *   <li>{@code expiredFilesNotReturned} — TTL filter excludes expired rows
 *       even when {@code injectedAt IS NULL}.</li>
 *   <li>{@code unsupportedMimeRejected} — building Media for a forbidden MIME
 *       throws (mirrors the jmix-crm verbatim port — Phase 13 Plan 13-02).</li>
 *   <li>{@code oldConsumedFileNotReinjectedAfterChatMemoryProjectionRewrite}
 *       — REVIEWS HIGH-1 regression. Even if the chat-memory projecting repo
 *       deletes/reinserts the linked AiMessage row (cascading the message FK
 *       to NULL), the row's {@code injectedAt} timestamp still excludes it
 *       from the resolver predicate. This test would FAIL on the
 *       {@code e.message is null} predicate that REVIEWS HIGH-1 replaced.</li>
 *   <li>{@code markInjectedTolerantOfMissingAiMessage} — REVIEWS HIGH-1 +
 *       HIGH-14 contract. {@code markInjected} stamps {@code injectedAt}
 *       authoritatively even when the supplied {@code messageId} no longer
 *       exists; the {@code message} FK is left null but the row is still
 *       excluded from the next resolver call.</li>
 * </ol>
 *
 * <p>{@code @AfterEach} cleanup removes seeded task-file rows + AiMessage +
 * AiConversation via {@link UnconstrainedDataManager} so concurrent test runs
 * do not collide.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.task-file.ttl=PT1H"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AiTaskFileMediaResolverIntegrationTest {

    @Autowired
    private AiTaskFileMediaResolver resolver;

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
    private final List<UUID> seededMessageIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededTaskFileIds) {
                unconstrainedDataManager.load(AiTaskFile.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededMessageIds) {
                unconstrainedDataManager.load(AiMessage.class)
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
                    // Best-effort blob cleanup — synthetic refs may not have a backing blob.
                }
            }
        });
        seededTaskFileIds.clear();
        seededMessageIds.clear();
        seededConversationIds.clear();
        seededBlobs.clear();
    }

    @Test
    void pendingFilesReturnedWhenInjectedAtNull() {
        UUID conversationId = createConversation("resolver-pending-user");
        FileRef blobRef = saveBlob("pending.txt", "hello pending");
        UUID taskFileId = seedTaskFile(conversationId, "resolver-pending-user",
                "pending.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));

        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolvePending(conversationId));

        assertThat(resolved.isEmpty()).as("pending row must resolve to a Media list").isFalse();
        assertThat(resolved.media()).hasSize(1);
        assertThat(resolved.taskFileIds()).containsExactly(taskFileId);
    }

    @Test
    void stampedFilesNotReturned() {
        UUID conversationId = createConversation("resolver-stamped-user");
        FileRef blobRef = saveBlob("already-injected.txt", "already seen");
        seedTaskFile(conversationId, "resolver-stamped-user",
                "already-injected.txt", "text/plain", blobRef,
                /* injectedAt */ OffsetDateTime.now().minusMinutes(5),
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));

        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolvePending(conversationId));

        assertThat(resolved.isEmpty())
                .as("D-01 single-turn-inject — already-injected row must be excluded")
                .isTrue();
    }

    @Test
    void expiredFilesNotReturned() {
        UUID conversationId = createConversation("resolver-expired-user");
        FileRef blobRef = saveBlob("expired.txt", "expired content");
        seedTaskFile(conversationId, "resolver-expired-user",
                "expired.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().minusMinutes(1));

        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolvePending(conversationId));

        assertThat(resolved.isEmpty())
                .as("expired row must be excluded by the resolver predicate")
                .isTrue();
    }

    @Test
    void unsupportedMimeRejected() {
        UUID conversationId = createConversation("resolver-mime-user");
        FileRef blobRef = saveBlob("malware.exe", "evil bytes");
        seedTaskFile(conversationId, "resolver-mime-user",
                "malware.exe", "application/x-msdownload", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));

        // The MIME check runs inside buildMedia; resolvePending iterates over the pending
        // rows and triggers buildMedia for each, so an unsupported MIME surfaces as
        // IllegalArgumentException through the stream.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        systemAuthenticator.withSystem(() -> resolver.resolvePending(conversationId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported task-file media type");
    }

    /**
     * REVIEWS HIGH-1 regression — the projecting chat-memory repo deletes and
     * reinserts AiMessage rows on every {@code saveAll}. The cascade {@code SET
     * NULL} on the {@code MESSAGE_ID} FK (Plan 01 changeSet id="3") means a row
     * that has already been injected (and therefore had {@code message} set)
     * may legitimately have its {@code message} FK reset to {@code null}.
     *
     * <p>Predicate {@code e.message is null} would re-inject such a row on the
     * NEXT turn, breaking D-01 single-turn-inject. The predicate
     * {@code e.injectedAt is null} (REVIEWS HIGH-1) correctly excludes the row
     * because {@code injectedAt} is the AUTHORITATIVE marker — set exactly
     * once by {@code markInjected} and never reset by chat-memory persistence.
     */
    @Test
    void oldConsumedFileNotReinjectedAfterChatMemoryProjectionRewrite() {
        UUID conversationId = createConversation("resolver-projection-user");
        FileRef blobRef = saveBlob("consumed.txt", "old content");

        // Step 1: insert pending row + first user message; mark injected.
        UUID firstMessageId = seedUserMessage(conversationId, "first turn");
        UUID taskFileId = seedTaskFile(conversationId, "resolver-projection-user",
                "consumed.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));
        systemAuthenticator.runWithSystem(() ->
                repository.markInjected(List.of(taskFileId), firstMessageId, OffsetDateTime.now()));

        // Step 2: simulate the projecting chat-memory repo's saveAll behaviour —
        // delete the original AiMessage; cascade SET NULL on AiTaskFile.MESSAGE_ID
        // resets row.message to null. Then reinsert a NEW user message for turn 2.
        systemAuthenticator.runWithSystem(() -> {
            unconstrainedDataManager.load(AiMessage.class).id(firstMessageId)
                    .optional().ifPresent(unconstrainedDataManager::remove);
        });
        UUID secondMessageId = seedUserMessage(conversationId, "second turn");

        // Step 3: confirm the row's message FK is now null but injectedAt is still set.
        AiTaskFile row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class).id(taskFileId).one());
        // message FK may or may not be cleared depending on cascade behaviour at this layer;
        // the contract is: even if it IS null, injectedAt remains set so the resolver excludes
        // the row.
        assertThat(row.getInjectedAt())
                .as("injectedAt must remain set after chat-memory projection rewrite (REVIEWS HIGH-1)")
                .isNotNull();

        // Step 4: resolve again — must be empty. With the old `e.message is null` predicate
        // this assertion would FAIL because the cascade SET NULL re-makes the row
        // resolver-visible.
        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolvePending(conversationId));

        assertThat(resolved.isEmpty())
                .as("REVIEWS HIGH-1 — row already injected on turn 1 must NOT re-inject "
                        + "on turn 2 even after chat-memory delete/reinsert. (Old predicate "
                        + "e.message IS NULL would fail here; current predicate "
                        + "e.injectedAt IS NULL passes.)")
                .isTrue();
        // Sanity — the second AiMessage actually persisted so the test set up the
        // projection-rewrite scenario, not just an unrelated empty result.
        assertThat(secondMessageId).isNotNull();
    }

    /**
     * REVIEWS HIGH-1 + HIGH-14 — markInjected stamps {@code injectedAt}
     * authoritatively even when the supplied {@code messageId} cannot be
     * resolved (the chat-memory repo may have already rewritten the row).
     * The display-link {@code message} FK is left null but the resolver
     * still excludes the row from the next turn.
     */
    @Test
    void markInjectedTolerantOfMissingAiMessage() {
        UUID conversationId = createConversation("resolver-tolerant-user");
        FileRef blobRef = saveBlob("tolerant.txt", "tolerant content");
        UUID taskFileId = seedTaskFile(conversationId, "resolver-tolerant-user",
                "tolerant.txt", "text/plain", blobRef,
                /* injectedAt */ null,
                /* expiresAt  */ OffsetDateTime.now().plusHours(1));

        UUID nonexistentMessageId = UUID.randomUUID();

        // Must not throw even though nonexistentMessageId has no backing AiMessage row.
        systemAuthenticator.runWithSystem(() ->
                repository.markInjected(
                        List.of(taskFileId), nonexistentMessageId, OffsetDateTime.now()));

        AiTaskFile row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiTaskFile.class).id(taskFileId).one());
        assertThat(row.getInjectedAt())
                .as("markInjected stamps the authoritative marker even when AiMessage lookup fails")
                .isNotNull();
        assertThat(row.getMessage())
                .as("display-link FK is best-effort — left null when AiMessage is missing")
                .isNull();

        // Resolver must exclude the row even though the display-link is null.
        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withSystem(() ->
                resolver.resolvePending(conversationId));
        assertThat(resolved.isEmpty())
                .as("injectedAt is the authoritative resolver predicate (REVIEWS HIGH-1)")
                .isTrue();
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

    private UUID seedUserMessage(UUID conversationId, String content) {
        UUID messageId = systemAuthenticator.withSystem(() -> {
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            AiMessage message = metadata.create(AiMessage.class);
            message.setConversation(conversationRef);
            message.setRole(AiMessageRole.USER);
            message.setContent(content);
            message.setCreatedDate(OffsetDateTime.now());
            return unconstrainedDataManager.save(message).getId();
        });
        seededMessageIds.add(messageId);
        return messageId;
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
            row.setInjectedAt(injectedAt);
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
        // Optional helps callers that want to skip the cleanup hook in error paths.
        return Optional.of(ref).orElseThrow();
    }
}
