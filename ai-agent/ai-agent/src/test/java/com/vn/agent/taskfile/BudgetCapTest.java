package com.vn.agent.taskfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 REQ-3 (AUDIT-01) — pins the per-turn budget cap + audit-row contract under
 * the default caps (10 files / 50 MB).
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>11 × 1 KB rows → resolver keeps the 10 most-recent + emits one
 *       {@code task_file_budget_exceeded} audit row whose argumentsJson parses to the
 *       locked 9-key shape (writer + reader both reference {@link BudgetExceededAuditKeys}).</li>
 *   <li>6 × 10 MB rows → byte cap drops the oldest; {@code keptBytes == 52428800}.</li>
 * </ol>
 *
 * <p>The sentinel branch (caps = -1) lives in the sibling {@link BudgetCapSentinelTest}
 * because Spring Boot test contexts are cached on configuration; mixing a sentinel
 * {@code @TestPropertySource} into a nested class would force context reload and risk leaking
 * the override into surrounding tests.
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
class BudgetCapTest {

    @Autowired private AiTaskFileMediaResolver resolver;
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Metadata metadata;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<UUID> seededAuditIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        BudgetCapTestSupport.cleanupAll(systemAuthenticator, unconstrainedDataManager,
                fileStorageLocator, seededTaskFileIds, seededConversationIds,
                seededAuditIds, seededBlobs);
    }

    @Test
    void eleven1KbFilesDropsOldestEmitsOneAuditRow() throws Exception {
        UUID conversationId = BudgetCapTestSupport.createConversation(systemAuthenticator,
                unconstrainedDataManager, metadata, seededConversationIds, "budget-default-user");

        // 11 × 1 KB. Sleep between saves so JPA-managed createdDate is strictly monotonic
        // — the resolver iterates DESC and drops the OLDEST first (LRU).
        byte[] payload = new byte[1024];
        for (int i = 0; i < 11; i++) {
            FileRef ref = BudgetCapTestSupport.saveBlob(systemAuthenticator, fileStorageLocator,
                    seededBlobs, "budget-default-" + i + ".png", payload);
            BudgetCapTestSupport.seedTaskFile(systemAuthenticator, unconstrainedDataManager,
                    metadata, seededTaskFileIds, conversationId, "system",
                    "budget-default-" + i + ".png", "image/png", ref,
                    (long) payload.length, OffsetDateTime.now().plusHours(24));
            Thread.sleep(2);
        }

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(conversationId));

        assertThat(result.media())
                .as("default cap (10 files) must keep the 10 most-recent of 11 rows")
                .hasSize(10);
        assertThat(result.budgetExceeded())
                .as("11 files exceeds the 10-file cap")
                .isTrue();

        AiAuditEvent auditRow = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select e from ai_AiAuditEvent e where e.eventName = :name " +
                                "and e.conversation.id = :cid order by e.startedAt desc")
                        .parameter("name", "task_file_budget_exceeded")
                        .parameter("cid", conversationId)
                        .list()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "no task_file_budget_exceeded audit row found for conv=" + conversationId)));
        seededAuditIds.add(auditRow.getId());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode args = mapper.readTree(auditRow.getArgumentsJson());
        assertThat(args.path(BudgetExceededAuditKeys.CONVERSATION_ID).asText())
                .isEqualTo(conversationId.toString());
        assertThat(args.path(BudgetExceededAuditKeys.TOTAL_ROWS).asInt()).isEqualTo(11);
        assertThat(args.path(BudgetExceededAuditKeys.KEPT_ROWS).asInt()).isEqualTo(10);
        assertThat(args.path(BudgetExceededAuditKeys.DROPPED_ROWS).asInt()).isEqualTo(1);
        assertThat(args.path(BudgetExceededAuditKeys.KEPT_BYTES).asLong()).isEqualTo(10240L);
        assertThat(args.path(BudgetExceededAuditKeys.DROPPED_BYTES).asLong()).isEqualTo(1024L);
        assertThat(args.path(BudgetExceededAuditKeys.TOTAL_BYTES).asLong()).isEqualTo(11264L);
        assertThat(args.path(BudgetExceededAuditKeys.PER_TURN_MAX_FILES).asInt()).isEqualTo(10);
        assertThat(args.path(BudgetExceededAuditKeys.PER_TURN_MAX_TOTAL_BYTES).asLong())
                .isEqualTo(52428800L);
    }

    @Test
    void sixTenMbFilesByteCapEnforcement() throws Exception {
        UUID conversationId = BudgetCapTestSupport.createConversation(systemAuthenticator,
                unconstrainedDataManager, metadata, seededConversationIds, "budget-byte-user");

        // 6 × 10 MB rows = 60 MB. With a 50 MB byte cap, the 5 newest rows fit (50 MB exactly);
        // row 0 (oldest) is dropped.
        byte[] payload = new byte[10 * 1024 * 1024];
        for (int i = 0; i < 6; i++) {
            FileRef ref = BudgetCapTestSupport.saveBlob(systemAuthenticator, fileStorageLocator,
                    seededBlobs, "budget-byte-" + i + ".png", payload);
            BudgetCapTestSupport.seedTaskFile(systemAuthenticator, unconstrainedDataManager,
                    metadata, seededTaskFileIds, conversationId, "system",
                    "budget-byte-" + i + ".png", "image/png", ref,
                    (long) payload.length, OffsetDateTime.now().plusHours(24));
            Thread.sleep(2);
        }

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(conversationId));

        assertThat(result.media())
                .as("byte cap (50 MB) must keep 5 of 6 × 10 MB rows")
                .hasSize(5);
        assertThat(result.budgetExceeded()).isTrue();

        AiAuditEvent auditRow = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select e from ai_AiAuditEvent e where e.eventName = :name " +
                                "and e.conversation.id = :cid order by e.startedAt desc")
                        .parameter("name", "task_file_budget_exceeded")
                        .parameter("cid", conversationId)
                        .list()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "no task_file_budget_exceeded audit row found for conv=" + conversationId)));
        seededAuditIds.add(auditRow.getId());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode args = mapper.readTree(auditRow.getArgumentsJson());
        assertThat(args.path(BudgetExceededAuditKeys.KEPT_ROWS).asInt()).isEqualTo(5);
        assertThat(args.path(BudgetExceededAuditKeys.DROPPED_ROWS).asInt()).isEqualTo(1);
        assertThat(args.path(BudgetExceededAuditKeys.KEPT_BYTES).asLong()).isEqualTo(52428800L);
    }
}

/**
 * Phase 13.1 REQ-3 sentinel branch: both per-turn caps set to {@code -1} disables every cap;
 * resolver returns every row, no audit row is written.
 *
 * <p>Sibling top-level class (not @Nested) so its sentinel @SpringBootTest properties do
 * not bleed into {@link BudgetCapTest}'s default-caps context. Spring Boot caches contexts
 * by configuration shape; two top-level classes get two independent contexts.
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.task-file.ttl-seconds=86400",
                "ai-agent.task-file.per-turn-max-files=-1",
                "ai-agent.task-file.per-turn-max-total-bytes=-1"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class BudgetCapSentinelTest {

    @Autowired private AiTaskFileMediaResolver resolver;
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Metadata metadata;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();
    private final List<UUID> seededAuditIds = new ArrayList<>();
    private final List<FileRef> seededBlobs = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        BudgetCapTestSupport.cleanupAll(systemAuthenticator, unconstrainedDataManager,
                fileStorageLocator, seededTaskFileIds, seededConversationIds,
                seededAuditIds, seededBlobs);
    }

    @Test
    void sentinelDisablesCapsReturnsAllRowsZeroAuditRows() throws Exception {
        UUID conversationId = BudgetCapTestSupport.createConversation(systemAuthenticator,
                unconstrainedDataManager, metadata, seededConversationIds, "budget-sentinel-user");

        byte[] payload = new byte[1024];
        for (int i = 0; i < 11; i++) {
            FileRef ref = BudgetCapTestSupport.saveBlob(systemAuthenticator, fileStorageLocator,
                    seededBlobs, "budget-sentinel-" + i + ".png", payload);
            BudgetCapTestSupport.seedTaskFile(systemAuthenticator, unconstrainedDataManager,
                    metadata, seededTaskFileIds, conversationId, "system",
                    "budget-sentinel-" + i + ".png", "image/png", ref,
                    (long) payload.length, OffsetDateTime.now().plusHours(24));
            Thread.sleep(2);
        }

        AiTaskFileMediaResolver.Resolved result = systemAuthenticator.withSystem(() ->
                resolver.resolveActive(conversationId));

        assertThat(result.media())
                .as("sentinel -1 on both caps must return every row")
                .hasSize(11);
        assertThat(result.budgetExceeded())
                .as("sentinel mode never sets budgetExceeded")
                .isFalse();

        long auditCount = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select e from ai_AiAuditEvent e where e.eventName = :name " +
                                "and e.conversation.id = :cid")
                        .parameter("name", "task_file_budget_exceeded")
                        .parameter("cid", conversationId)
                        .list()
                        .size());
        assertThat(auditCount)
                .as("sentinel mode must emit zero task_file_budget_exceeded audit rows")
                .isEqualTo(0L);
    }
}

/**
 * Shared seeding + cleanup helpers. Package-private so both {@link BudgetCapTest} and
 * {@link BudgetCapSentinelTest} can reuse them without a Spring bean dependency.
 */
final class BudgetCapTestSupport {

    private BudgetCapTestSupport() { /* no instances */ }

    static UUID createConversation(SystemAuthenticator systemAuthenticator,
                                   UnconstrainedDataManager unconstrainedDataManager,
                                   Metadata metadata,
                                   List<UUID> tracker,
                                   String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("budget-cap test conversation");
            return unconstrainedDataManager.save(conversation).getId();
        });
        tracker.add(conversationId);
        return conversationId;
    }

    static void seedTaskFile(SystemAuthenticator systemAuthenticator,
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
                           List<UUID> seededAuditIds,
                           List<FileRef> seededBlobs) {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededTaskFileIds) {
                unconstrainedDataManager.load(AiTaskFile.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededAuditIds) {
                unconstrainedDataManager.load(AiAuditEvent.class)
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
        seededAuditIds.clear();
        seededBlobs.clear();
    }
}
