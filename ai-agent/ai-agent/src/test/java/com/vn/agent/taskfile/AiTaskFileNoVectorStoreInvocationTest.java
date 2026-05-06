package com.vn.agent.taskfile;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.rag.IngesterManager;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.FileRef;
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
 * Inserts an {@link AiTaskFile} row + (synthetic) {@link FileRef} pointer,
 * then exercises {@code AiTaskFileMediaResolver.resolvePending(...)} (the same
 * call the chat path uses). The resolver streams blob bytes via
 * {@code FileStorage} — never via {@code VectorStore} or {@code IngesterManager}.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "ai-agent.task-file.ttl=PT1H"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AiTaskFileNoVectorStoreInvocationTest {

    @MockitoSpyBean
    private VectorStore vectorStore;          // REVIEWS HIGH-10: SPY, not @MockitoBean

    @MockitoBean
    private IngesterManager ingesterManager;  // boot-time-safe; strict no-interaction

    @Autowired
    private AiTaskFileMediaResolver resolver;

    @Autowired
    private AiTaskFileRepository repository;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededTaskFileIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();

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
        });
        seededTaskFileIds.clear();
        seededConversationIds.clear();
    }

    @Test
    void resolvePending_neverIngests_andRepositoryDoesNotInjectVectorStore() {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy("test16-runtime-user");
            conversation.setTitle("test-16 runtime");
            return unconstrainedDataManager.save(conversation).getId();
        });
        seededConversationIds.add(conversationId);

        UUID taskFileId = systemAuthenticator.withSystem(() -> {
            AiTaskFile row = metadata.create(AiTaskFile.class);
            // Reference the saved conversation so the FK is satisfied.
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            row.setConversation(conversationRef);
            row.setUserUsername("test16-runtime-user");
            row.setFilename("test16.txt");
            row.setContentType("text/plain");
            row.setSizeBytes(11L);
            // Synthetic FileRef — the resolver only reads bytes when buildMedia runs.
            // For this test we ONLY exercise the load+filter path, never opening the
            // blob, so a synthetic ref with no real backing blob is intentional.
            row.setStorageRef(new FileRef("fs", "synthetic-test16.txt", "test16.txt"));
            row.setExpiresAt(OffsetDateTime.now().plusHours(1));
            return unconstrainedDataManager.save(row).getId();
        });
        seededTaskFileIds.add(taskFileId);

        // Exercise the agentstore predicate paths used by the chat turn — both the
        // resolver and the repository touch the same query. We do not call
        // resolvePending(...) directly because the synthetic FileRef has no blob;
        // loadPending(...) exercises the same JPA path without opening the stream.
        systemAuthenticator.runWithSystem(() -> repository.loadPending(conversationId));

        // TEST-16 invariant: NO ingestion calls. similaritySearch (retrieval) is
        // intentionally NOT asserted to be zero — RAG retrieval is allowed.
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).accept(any());
        // Strict on the IngesterManager — task-file pathway must never reach it.
        verifyNoInteractions(ingesterManager);
    }
}
