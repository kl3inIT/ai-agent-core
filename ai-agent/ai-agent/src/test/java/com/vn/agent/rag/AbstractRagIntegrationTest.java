package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.vectorstore.VectorStore;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
@Testcontainers
@EnabledIf("com.vn.agent.rag.AbstractRagIntegrationTest#isDockerAvailable")
public abstract class AbstractRagIntegrationTest {

    // RESEARCH Pitfall #5: pgvector-bundled image is MANDATORY. Do not swap for plain postgres.
    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    protected static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("ai_agent_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Condition for {@link EnabledIf}: only run this test class when a Docker daemon is reachable.
     * Called by JUnit reflectively — MUST be public static with no args and boolean return.
     */
    public static boolean isDockerAvailable() {
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
        // Liquibase on the real pgvector container will run the add-on changelogs including the
        // 070 vector store DDL that creates the AI_AGENT_KB_VECTOR_STORE table + HNSW index.
        registry.add("spring.liquibase.enabled", () -> "true");
        // PgVectorStore.initializeSchema(false) honoured; the vector(1536) + HNSW creation is
        // owned by Liquibase (RESEARCH Pitfall #2).
    }

    @Autowired protected KnowledgeDocumentUploadService uploadService;
    @Autowired protected KnowledgeDocumentService documentService;
    @Autowired protected DataManager dataManager;
    @Autowired protected VectorStore vectorStore;
    @Autowired protected Metadata metadata;

    /**
     * Upload a document and assert the sync-executor path drove it to READY. Returns the id so
     * tests can chain role-scoped retrieval / delete assertions.
     */
    protected UUID uploadAndAwaitReady(String sourceUri, Collection<String> allowedRoles) {
        AiKnowledgeDocument saved = uploadService.upload(sourceUri, "text/markdown", allowedRoles);
        // With SyncTaskExecutor, ingestion has already completed by the time this returns.
        AiKnowledgeDocument reloaded = dataManager.load(AiKnowledgeDocument.class)
                .id(saved.getId()).one();
        assertThat(reloaded.getStatus())
                .as("Upload of %s should drive document to READY under SyncTaskExecutor", sourceUri)
                .isEqualTo(AiKnowledgeDocumentStatus.READY);
        return saved.getId();
    }

    /**
     * Wipe every chunk from the underlying pgvector store so the next test starts from an empty
     * index. pgvector-level truncation is NOT part of the JPA transaction and must be done
     * explicitly between tests.
     */
    protected void deleteAllVectors() {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq(ChunkMetadata.EMBEDDING_MODEL, "openai/text-embedding-3-small").build());
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
