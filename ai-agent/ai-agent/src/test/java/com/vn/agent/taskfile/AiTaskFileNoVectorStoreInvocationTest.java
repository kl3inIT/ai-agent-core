package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.rag.IngesterManager;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TEST-16 RUNTIME enforcement (Phase 13 Plan 13-05 Task 1B). Boots a Spring
 * context with the stub chat model and the {@link StubVectorStoreConfiguration}
 * in-memory bean, then verifies that <b>resolving pending task-file rows</b>
 * (the only Phase 13 surface that touches user-uploaded bytes) never invokes
 * the RAG ingestion pathway.
 *
 * <h2>Why a SPY, not a {@code @MockitoBean}, on {@link VectorStore}</h2>
 * Replacing the real {@code VectorStore} with a pure mock breaks the
 * {@code RetrievalAugmentationAdvisor} initialization path that Spring AI runs
 * at advisor-construction time (REVIEWS HIGH-10). The functional bean stays
 * primary via {@link StubVectorStoreConfiguration}; we wrap it with
 * {@link MockitoSpyBean} so we can assert zero ingestion calls without losing
 * the boot-time contract. The advisor MAY legitimately call
 * {@link VectorStore#similaritySearch(org.springframework.ai.vectorstore.SearchRequest)}
 * during a chat turn for retrieval — TEST-16 forbids only INGESTION
 * ({@link VectorStore#add(java.util.List)} / {@code accept(...)}), NOT retrieval.
 *
 * <h2>Why {@code @MockitoBean} is safe for {@link IngesterManager}</h2>
 * {@code IngesterManager} has no boot-time dependency that demands a functional
 * implementation; replacing it with a strict mock catches any accidental
 * invocation from the task-file path. {@code verifyNoInteractions(ingesterManager)}
 * is therefore the strict assertion for the Ingester.
 *
 * <h2>Test surface</h2>
 * Inserts an {@link AiTaskFile} row + real {@link FileRef} blob, then exercises
 * {@link AiTaskFileMediaResolver#resolveActive(UUID)} (the same call the chat
 * path uses). The resolver reads blob bytes via {@link FileStorage} and extracts
 * text locally — never via {@code VectorStore} or {@code IngesterManager}.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "ai-agent.task-file.ttl-seconds=3600"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        InMemoryFileStorageConfiguration.class})
class AiTaskFileNoVectorStoreInvocationTest {

    @MockitoSpyBean
    private VectorStore vectorStore;          // REVIEWS HIGH-10: SPY, not @MockitoBean

    @MockitoBean
    private IngesterManager ingesterManager;  // boot-time-safe; strict no-interaction

    @Autowired
    private AiTaskFileMediaResolver resolver;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;
    @Autowired
    private FileStorageLocator fileStorageLocator;

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
                    // Best-effort cleanup.
                }
            }
        });
        seededTaskFileIds.clear();
        seededConversationIds.clear();
        seededBlobs.clear();
    }

    @Test
    void resolveActive_neverIngests_andRepositoryDoesNotInjectVectorStore() {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy("alice");
            conversation.setTitle("test-16 runtime");
            return unconstrainedDataManager.save(conversation).getId();
        });
        seededConversationIds.add(conversationId);

        byte[] originalBytes = "hello test16".getBytes(StandardCharsets.UTF_8);
        FileRef blobRef = systemAuthenticator.withSystem(() -> {
            FileStorage fileStorage = fileStorageLocator.getDefault();
            return fileStorage.saveStream("test16.txt", new ByteArrayInputStream(originalBytes));
        });
        seededBlobs.add(blobRef);

        UUID taskFileId = systemAuthenticator.withSystem(() -> {
            AiTaskFile row = metadata.create(AiTaskFile.class);
            // Reference the saved conversation so the FK is satisfied.
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            row.setConversation(conversationRef);
            row.setUserUsername("alice");
            row.setFilename("test16.txt");
            row.setContentType("text/plain");
            row.setSizeBytes((long) originalBytes.length);
            row.setStorageRef(blobRef);
            row.setExpiresAt(OffsetDateTime.now().plusHours(1));
            return unconstrainedDataManager.save(row).getId();
        });
        seededTaskFileIds.add(taskFileId);

        AiTaskFileMediaResolver.Resolved resolved = systemAuthenticator.withUser("alice", () ->
                resolver.resolveActive(conversationId));
        org.assertj.core.api.Assertions.assertThat(resolved.media())
                .as("text/plain task files are extracted to DocumentText, not Media")
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(resolved.documentTexts())
                .as("TEST-16 must exercise the runtime resolver path that reads the stored blob")
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(resolved.documentTexts().getFirst().text())
                .contains("hello test16");

        // TEST-16 invariant: NO ingestion calls. similaritySearch (retrieval) is
        // intentionally NOT asserted to be zero — RAG retrieval is allowed.
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).accept(any());
        // Strict on the IngesterManager — task-file pathway must never reach it.
        verifyNoInteractions(ingesterManager);
    }
}
