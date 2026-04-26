package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for Phase 5 RAG integration tests (ROADMAP success criteria #1, #3, #4 + G-1 guard).
 *
 * <p><b>Container:</b> spins up a {@code pgvector/pgvector:pg16} PostgreSQL container per test
 * class. The image is pinned — RESEARCH Pitfall #5 forbids the generic {@code postgres} image
 * because it lacks the pgvector extension and causes {@link VectorStore#similaritySearch} to
 * fail with {@code extension "vector" is not installed} at runtime.</p>
 *
 * <p><b>Tag:</b> {@code @Tag("rag-it")} so Gradle's default {@code test} task can exclude these
 * tests. They still run under the {@code integrationTest} task. Tests are additionally gated
 * by {@link #isDockerAvailable()} — environments without Docker (e.g., sandboxed CI, forensic
 * analysis runs) skip the entire class with a clear message rather than stack-traces from
 * container startup.</p>
 *
 * <p><b>Sync execution:</b> {@link RagTestConfiguration} replaces the async
 * {@code aiAgentIngestExecutor} with a {@link org.springframework.core.task.SyncTaskExecutor} so
 * ingestion completes before assertions — no {@link Thread#sleep}, no polling loops.</p>
 *
 * <p><b>Clean-between-tests helper:</b> {@link #deleteAllVectors()} wipes pgvector state between
 * tests so role-scoping and role-matrix tests start from a known-empty store. Called from each
 * concrete test class's {@code @AfterEach}.</p>
 */
@Tag("rag-it")
@SpringBootTest(
        classes = RagItTestApp.class,
        properties = "spring.main.allow-bean-definition-overriding=true")
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(RagTestConfiguration.class)
@ActiveProfiles("rag-it")
@EnabledIf("com.vn.agent.rag.AbstractRagIntegrationTest#isDockerAvailable")
public abstract class AbstractRagIntegrationTest {

    // RESEARCH Pitfall #5: pgvector-bundled image is MANDATORY. Do not swap for plain postgres.
    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres");

    private static final boolean DOCKER_AVAILABLE = detectDockerAvailable();

    protected static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("ai_agent_test")
            .withUsername("test")
            .withPassword("test");

    static {
        if (DOCKER_AVAILABLE) {
            PG.start();
        }
    }

    /**
     * Condition for {@link EnabledIf}: only run this test class when a Docker daemon is reachable.
     * Called by JUnit reflectively — MUST be public static with no args and boolean return.
     */
    public static boolean isDockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    private static boolean detectDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("main.datasource.url", PG::getJdbcUrl);
        registry.add("main.datasource.username", PG::getUsername);
        registry.add("main.datasource.password", PG::getPassword);
        registry.add("main.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("agentstore.datasource.url", PG::getJdbcUrl);
        registry.add("agentstore.datasource.username", PG::getUsername);
        registry.add("agentstore.datasource.password", PG::getPassword);
        registry.add("agentstore.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Liquibase runs entity-owned add-on changelogs; PgVectorStore creates its own table + HNSW index.
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> "true");
    }

    @Autowired protected KnowledgeDocumentUploadService uploadService;
    @Autowired protected KnowledgeDocumentService documentService;
    @Autowired protected DataManager dataManager;
    @Autowired protected VectorStore vectorStore;
    @Autowired protected Metadata metadata;
    @Autowired protected SystemAuthenticator systemAuthenticator;

    protected void runAsAdmin(Runnable action) {
        systemAuthenticator.runWithUser("admin", action);
    }

    protected <T> T withAdmin(Supplier<T> action) {
        return systemAuthenticator.withUser("admin", action::get);
    }

    /**
     * WR-04: centralised hermetic cleanup. Every test class that extends this base runs
     * {@link #purgeAllVectorsAndDocuments()} in {@code @BeforeEach} AND {@code @AfterEach}
     * so the next test starts from a provably empty pgvector store AND empty
     * {@link AiKnowledgeDocument} table. Also fails fast if either store is non-empty at
     * the start of a test — catching leaks from a crashed prior run.
     */
    @BeforeEach
    void purgeBeforeTest() {
        runAsAdmin(() -> {
            purgeAllVectorsAndDocuments();
            assertEmptyStateOrFail();
        });
    }

    @AfterEach
    void purgeAfterTest() {
        runAsAdmin(this::purgeAllVectorsAndDocuments);
    }

    /**
     * Delete every chunk regardless of embedding model (WR-04 fix: the previous helper
     * filtered by the current model and left "other-model-not-current" synthetic chunks
     * behind) AND every {@link AiKnowledgeDocument} row.
     */
    protected void purgeAllVectorsAndDocuments() {
        // Delete every vector: the filter matches any document_id that is "not empty",
        // i.e. everything. There is no truncate() on VectorStore, so this is the
        // coarsest available delete that still goes through Spring AI's abstraction.
        Filter.Expression everything = new FilterExpressionBuilder()
                .ne(ChunkMetadata.DOCUMENT_ID, "__nonexistent_sentinel__").build();
        vectorStore.delete(everything);

        dataManager.load(AiKnowledgeDocument.class)
                .query("select d from ai_AiKnowledgeDocument d").list()
                .forEach(dataManager::remove);
    }

    private void assertEmptyStateOrFail() {
        // Any residue here means the previous test (possibly in a different class)
        // did not clean up — integration assertions that depend on counts would be
        // order-dependent without this guard.
        long docs = dataManager.load(AiKnowledgeDocument.class)
                .query("select d from ai_AiKnowledgeDocument d").list().size();
        assertThat(docs).as("AiKnowledgeDocument table must be empty at test start").isEqualTo(0);

        List<org.springframework.ai.document.Document> leakProbe = vectorStore.similaritySearch(
                SearchRequest.builder().query("leak-probe").topK(1).build());
        assertThat(leakProbe)
                .as("pgvector store must be empty at test start (found %s leaked chunks)", leakProbe.size())
                .isEmpty();
    }

    /**
     * Upload a document and assert the sync-executor path drove it to READY. Returns the id so
     * tests can chain role-scoped retrieval / delete assertions.
     */
    protected UUID uploadAndAwaitReady(String sourceUri, Collection<String> allowedRoles) {
        AiKnowledgeDocument saved = withAdmin(() ->
                uploadService.upload(sourceUri, sourceKindFor(sourceUri), allowedRoles));
        // With SyncTaskExecutor, ingestion has already completed by the time this returns.
        AiKnowledgeDocument reloaded = withAdmin(() -> dataManager.load(AiKnowledgeDocument.class)
                .id(saved.getId()).one());
        assertThat(reloaded.getStatus())
                .as("Upload of %s should drive document to READY under SyncTaskExecutor. errorMessage=%s",
                        sourceUri, reloaded.getErrorMessage())
                .isEqualTo(AiKnowledgeDocumentStatus.READY);
        return saved.getId();
    }

    private static String sourceKindFor(String sourceUri) {
        String lower = sourceUri == null ? "" : sourceUri.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return null;
    }

    /**
     * Wipe every chunk from the underlying pgvector store so the next test starts from an empty
     * index. pgvector-level truncation is NOT part of the JPA transaction and must be done
     * explicitly between tests.
     *
     * <p>WR-04: delegates to {@link #purgeAllVectorsAndDocuments()} so synthetic chunks with
     * a non-default {@code embeddingModel} (e.g. the "other-model-not-current" drift fixture
     * in {@code RoleScopedRetrievalIntegrationTest}) are removed too.</p>
     */
    protected void deleteAllVectors() {
        purgeAllVectorsAndDocuments();
    }

    /** Convenience: similarity-search with no filter and return the raw document list. */
    protected List<org.springframework.ai.document.Document> searchAll(String query, int topK) {
        return vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build());
    }
}
