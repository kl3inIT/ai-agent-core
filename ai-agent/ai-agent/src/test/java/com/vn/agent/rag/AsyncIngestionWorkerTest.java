package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Synchronous-style unit test — invokes {@link AsyncIngestionWorker#ingest(UUID)} directly on the
 * test thread (no {@code @EnableAsync}, no Spring context) so assertions run against a fully
 * completed ingest. Plan 05-05 integration tests cover the real async path via
 * {@code SyncTaskExecutor}.
 */
class AsyncIngestionWorkerTest {

    private IngestionStatusWriter statusWriter;
    private CancellationRegistry cancellationRegistry;
    private DataManager dataManager;
    private VectorStore vectorStore;
    private AiAgentRagProperties ragProps;
    private AiAgentEmbeddingProperties embeddingProps;
    private DefaultResourceLoader resourceLoader;
    private SystemAuthenticator systemAuthenticator;

    private AsyncIngestionWorker worker;

    @BeforeEach
    void setUp() {
        statusWriter = mock(IngestionStatusWriter.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        dataManager = mock(DataManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        vectorStore = mock(VectorStore.class);
        ragProps = new AiAgentRagProperties(
                null, null, null,
                new AiAgentRagProperties.Splitter(200, null, 10),
                null, null, null, null, null);
        embeddingProps = new AiAgentEmbeddingProperties(
                "qwen/qwen3-embedding-4b", 2000, null);
        resourceLoader = new DefaultResourceLoader();
        systemAuthenticator = mock(SystemAuthenticator.class);
        // Stub runWithSystem(Runnable) to invoke the action inline on the test thread.
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(systemAuthenticator).runWithSystem(any(Runnable.class));

        worker = new AsyncIngestionWorker(statusWriter, cancellationRegistry, dataManager,
                vectorStore, ragProps, embeddingProps, resourceLoader, systemAuthenticator);
    }

    // --- Helpers -------------------------------------------------------------

    private static AiKnowledgeDocument doc(UUID id, String fileName, String allowedRolesJson) {
        AiKnowledgeDocument d = new AiKnowledgeDocument();
        // Bypass @JmixGeneratedValue — we want a predictable id in the test without a JPA context.
        setField(d, "id", id);
        d.setFileName(fileName);
        d.setAllowedRolesJson(allowedRolesJson);
        return d;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubLoad(UUID id, AiKnowledgeDocument value) {
        FluentLoader<AiKnowledgeDocument> loader = mock(FluentLoader.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(dataManager.load(AiKnowledgeDocument.class)).thenReturn(loader);
        when(loader.id(id).optional()).thenReturn(Optional.ofNullable(value));
    }

    @SuppressWarnings("unchecked")
    private List<Document> captureAddedChunks() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).add(captor.capture());
        List<Document> all = new ArrayList<>();
        for (List<Document> batch : captor.getAllValues()) {
            all.addAll(batch);
        }
        return all;
    }

    // --- Tests ---------------------------------------------------------------

    @Test
    void happy_path_enriches_every_chunk_with_all_five_metadata_keys_and_marks_ready() {
        UUID id = UUID.randomUUID();
        AiKnowledgeDocument d = doc(id, "classpath:ai-kb/fixture-alpha.md",
                "[\"ai-agent-user\"]");
        stubLoad(id, d);

        worker.ingest(id);

        List<Document> chunks = captureAddedChunks();
        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            Map<String, Object> md = chunk.getMetadata();
            assertThat(md).containsEntry(ChunkMetadata.SOURCE, "classpath:ai-kb/fixture-alpha.md");
            assertThat(md).containsEntry(ChunkMetadata.DOCUMENT_ID, id.toString());
            assertThat(md).containsEntry(ChunkMetadata.EMBEDDING_MODEL,
                    "qwen/qwen3-embedding-4b");
            assertThat(md).containsKey(ChunkMetadata.ALLOWED_ROLES);
            assertThat(md).containsEntry(ChunkMetadata.roleFlagKey("ai-agent-user"), true);
        }
        verify(statusWriter).markReady(eq(id), anyInt());
        verify(statusWriter, never()).markFailed(any(), any());
        verify(cancellationRegistry).clear(id);
    }

    @Test
    void role_flags_lowercase_via_Locale_ROOT() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "classpath:ai-kb/fixture-alpha.md",
                "[\"ai-agent-user\",\"Editor\"]"));

        worker.ingest(id);

        Document anyChunk = captureAddedChunks().get(0);
        Map<String, Object> md = anyChunk.getMetadata();
        assertThat(md).containsEntry(ChunkMetadata.roleFlagKey("ai-agent-user"), true);
        assertThat(md).containsEntry(ChunkMetadata.roleFlagKey("Editor"), true);
        // Original-case key MUST NOT appear — filter lookup side is always lowercase.
        assertThat(md).doesNotContainKey(ChunkMetadata.ROLE_FLAG_PREFIX + "Editor");
        // Hyphenated role codes must be normalized for JSONPath-safe filter keys.
        assertThat(md).doesNotContainKey(ChunkMetadata.ROLE_FLAG_PREFIX + "ai-agent-user");
    }

    @Test
    void empty_allowedRoles_produces_zero_role_flags_but_keeps_ALLOWED_ROLES_empty_list() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "classpath:ai-kb/fixture-alpha.md", null));

        worker.ingest(id);

        Document anyChunk = captureAddedChunks().get(0);
        Map<String, Object> md = anyChunk.getMetadata();
        assertThat(md).containsEntry(ChunkMetadata.ALLOWED_ROLES, List.of());
        long roleFlagCount = md.keySet().stream()
                .filter(k -> k.startsWith(ChunkMetadata.ROLE_FLAG_PREFIX)).count();
        assertThat(roleFlagCount).isZero();
    }

    @Test
    void vectorStore_add_failure_triggers_cleanup_delete_and_markFailed() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "classpath:ai-kb/fixture-alpha.md", "[\"ai-agent-user\"]"));
        doThrow(new RuntimeException("embed blew up"))
                .when(vectorStore).add(any());

        worker.ingest(id);

        verify(vectorStore).delete(any(Filter.Expression.class));
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(statusWriter).markFailed(eq(id), msg.capture());
        assertThat(msg.getValue()).startsWith("RuntimeException: ");
        assertThat(msg.getValue()).contains("embed blew up");
        verify(statusWriter, never()).markReady(any(), anyInt());
        verify(cancellationRegistry).clear(id);
    }

    @Test
    void cancellation_between_batches_marks_cancelled_and_skips_further_add() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "classpath:ai-kb/fixture-alpha.md", "[\"ai-agent-user\"]"));
        // First cancellation check (entry-guard) returns false; subsequent per-batch
        // checks return true so we cancel before any batch is actually written.
        // WR-01: worker now polls isCancelled(id, generation); stub the generation-
        // aware overload used by production code.
        when(cancellationRegistry.isCancelled(org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(false)  // entry guard
                .thenReturn(true);  // first batch guard — cancel immediately

        worker.ingest(id);

        verify(vectorStore, never()).add(any());
        verify(statusWriter).markCancelled(id);
        verify(statusWriter, never()).markFailed(any(), any());
        verify(statusWriter, never()).markReady(any(), anyInt());
    }

    @Test
    void missing_document_logs_and_returns_without_status_transitions() {
        UUID id = UUID.randomUUID();
        stubLoad(id, null);

        worker.ingest(id);

        // markProcessing is always called first (it commits the "we started" signal even if the
        // doc was deleted mid-flight), but after that the worker returns silently.
        verify(statusWriter, times(1)).markProcessing(id);
        verify(statusWriter, never()).markReady(any(), anyInt());
        verify(statusWriter, never()).markFailed(any(), any());
        verify(statusWriter, never()).markCancelled(any());
        verify(vectorStore, never()).add(any());
    }

    @Test
    void document_exceeding_max_chars_fails_before_vector_add() {
        // WR-02: configure a 1-char cap — any real fixture text exceeds it. The worker must
        // NEVER call vectorStore.add and must flip status to FAILED with a clear message.
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "classpath:ai-kb/fixture-alpha.md", "[\"ai-agent-user\"]"));
        AiAgentRagProperties capped = new AiAgentRagProperties(
                null, null, null,
                new AiAgentRagProperties.Splitter(200, null, 10),
                null, null, null,
                new AiAgentRagProperties.Ingest(1),
                null);
        AsyncIngestionWorker cappedWorker = new AsyncIngestionWorker(statusWriter, cancellationRegistry,
                dataManager, vectorStore, capped, embeddingProps, resourceLoader, systemAuthenticator);

        cappedWorker.ingest(id);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(statusWriter).markFailed(eq(id), msg.capture());
        assertThat(msg.getValue()).contains("max-document-chars");
        verify(vectorStore, never()).add(any());
    }

    @Test
    void unknown_uri_scheme_is_rejected_and_becomes_FAILED() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id, "ftp://somewhere/file.md", "[\"ai-agent-user\"]"));

        worker.ingest(id);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(statusWriter).markFailed(eq(id), msg.capture());
        assertThat(msg.getValue()).startsWith("IllegalArgumentException: ");
        verify(vectorStore, never()).add(any());
    }
}
