package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import com.vn.agent.AgentstoreStoreConfiguration;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.jdbc.init.PlatformPlaceholderDatabaseDriverResolver;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for the AI Agent add-on (Phase 4).
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Imports {@link AIConfiguration} (functional module's Jmix-aware @Configuration).</p>
 *
 * <p>Phase 4 ownership changes:</p>
 * <ul>
 *   <li>The default {@code ChatClient} @Bean is now provided by {@code ChatClientFactory} inside
 *       the ai-agent module (D-01) — this autoconfig no longer owns it.</li>
 *   <li>This autoconfig supplies {@link ChatMemory} (a {@link MessageWindowChatMemory} of size 20)
 *       and a raw {@link JdbcChatMemoryRepository}, both {@code @ConditionalOnMissingBean}. The
 *       {@code @Primary ProjectingChatMemoryRepository} in the ai-agent module decorates the raw
 *       JDBC repository so the {@code ChatMemory} builder sees the dual-layer decorator (D-08).</li>
 * </ul>
 *
 * <p><b>Ordering notes (bean-collision fixes):</b></p>
 * <ul>
 *   <li>{@code after = OpenAiEmbeddingAutoConfiguration.class} — OpenAI's
 *       {@code openAiEmbeddingModel} bean must be registered before this autoconfig's
 *       {@code @ConditionalOnMissingBean} passthrough is evaluated. Without the ordering
 *       directive both beans can end up in the context and pgvector's
 *       {@code PgVectorStoreAutoConfiguration.vectorStore(EmbeddingModel)} fails with
 *       {@code NoUniqueBeanDefinitionException}.</li>
 *   <li>{@code before = PgVectorStoreAutoConfiguration.class} — our
 *       {@link #aiAgentVectorStore aiAgentVectorStore} bean MUST register first so Spring
 *       AI's auto-configured {@code PgVectorStore} (which carries {@code @ConditionalOnMissingBean}
 *       and defaults to table {@code public.vector_store}) is skipped. Without this directive
 *       Spring AI's bean wins, targets a non-existent table, and every VectorStore call fails
 *       with {@code relation "public.vector_store" does not exist}.</li>
 * </ul>
 * <p>The {@code spring-ai-starter-model-openai} and {@code spring-ai-starter-vector-store-pgvector}
 * dependencies in {@code ai-agent.gradle} (api-scope) guarantee the referenced autoconfig classes
 * are on the classpath.</p>
 */
@AutoConfiguration(
    after = OpenAiEmbeddingAutoConfiguration.class,
    before = PgVectorStoreAutoConfiguration.class)
@Import({AIConfiguration.class, AgentstoreStoreConfiguration.class})
@EnableConfigurationProperties(PgVectorStoreProperties.class)
public class AIAutoConfiguration {

  private static final String SPRING_AI_CHAT_MEMORY_SCHEMA =
      "classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-@@platform@@.sql";

  private static final String DEFAULT_VECTOR_TABLE_NAME = "AI_AGENT_KB_VECTOR_STORE";

  @Bean
  @ConditionalOnMissingBean
  public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(20)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(JdbcChatMemoryRepository.class)
  public JdbcChatMemoryRepository jdbcChatMemoryRepository(@Qualifier("pgvectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
    return JdbcChatMemoryRepository.builder().jdbcTemplate(jdbcTemplate).build();
  }

  /**
   * Initializes Spring AI's {@code SPRING_AI_CHAT_MEMORY} table on the add-on's
   * {@code agentstore} datasource. Spring AI's own JDBC chat-memory initializer
   * is bound to the primary datasource, while this add-on deliberately stores
   * its AI runtime tables in the Jmix {@code agentstore} datastore.
   */
  @Bean("aiAgentChatMemorySchemaInitializer")
  @ConditionalOnMissingBean(name = "aiAgentChatMemorySchemaInitializer")
  public DataSourceScriptDatabaseInitializer aiAgentChatMemorySchemaInitializer(
      @Qualifier("agentstoreDataSource") DataSource dataSource) {
    DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
    settings.setSchemaLocations(chatMemorySchemaLocations(dataSource));
    settings.setMode(DatabaseInitializationMode.ALWAYS);
    settings.setContinueOnError(true);
    return new DataSourceScriptDatabaseInitializer(dataSource, settings);
  }

  /**
   * Passthrough bean (D-02). The OpenAI starter auto-configures {@link EmbeddingModel}
   * out of the box; this {@code @ConditionalOnMissingBean} declaration is a no-op by
   * default but gives hosts a documented override seam — declare a host
   * {@code @Bean EmbeddingModel} and this method is skipped. Bean-collision across
   * starters is caught by {@code EmbeddingModelBeanCollisionTest} (RAG-02).
   *
   * <p>Intentionally accepts {@link EmbeddingModel} by parameter (not produced
   * fresh) — this is a passthrough, not a construction site. A host swapping the
   * provider replaces the upstream bean directly via a {@code @Primary @Bean}.</p>
   */
  @Bean
  @ConditionalOnMissingBean
  public EmbeddingModel aiAgentEmbeddingModel(EmbeddingModel autoConfiguredEmbeddingModel) {
    return autoConfiguredEmbeddingModel;
  }

  /**
   * PgVectorStore bound to the add-on's knowledge-base table. Spring AI owns the
   * pgvector table, extensions, and ANN index creation, matching the upstream
   * jmix-ai-backend pattern and avoiding duplicated Spring AI DDL in Liquibase.
   * Dimension is pinned from {@link AiAgentEmbeddingProperties#resolvedDimensions()} —
   * default 2000 to stay within pgvector's HNSW/IVFFLAT limit while using Qwen3
   * Embedding 4B.
   */
  @Bean
  @ConditionalOnMissingBean(VectorStore.class)
  public PgVectorStore aiAgentVectorStore(
      @Qualifier("pgvectorJdbcTemplate") JdbcTemplate jdbcTemplate,
      EmbeddingModel embeddingModel,
      AiAgentEmbeddingProperties embeddingProps,
      PgVectorStoreProperties pgVectorStoreProperties) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .schemaName(pgVectorStoreProperties.getSchemaName())
        .idType(pgVectorStoreProperties.getIdType())
        .vectorTableName(vectorTableName(pgVectorStoreProperties))
        .vectorTableValidationsEnabled(pgVectorStoreProperties.isSchemaValidation())
        .distanceType(pgVectorStoreProperties.getDistanceType())
        .removeExistingVectorStoreTable(pgVectorStoreProperties.isRemoveExistingVectorStoreTable())
        .indexType(pgVectorStoreProperties.getIndexType())
        .initializeSchema(true)
        .dimensions(embeddingProps.resolvedDimensions())
        .maxDocumentBatchSize(pgVectorStoreProperties.getMaxDocumentBatchSize())
        .build();
  }

  private static List<String> chatMemorySchemaLocations(DataSource dataSource) {
    return new PlatformPlaceholderDatabaseDriverResolver()
        .resolveAll(dataSource, SPRING_AI_CHAT_MEMORY_SCHEMA);
  }

  private static String vectorTableName(PgVectorStoreProperties properties) {
    String configuredTableName = properties.getTableName();
    if (configuredTableName == null
        || configuredTableName.isBlank()
        || PgVectorStore.DEFAULT_TABLE_NAME.equals(configuredTableName)) {
      return DEFAULT_VECTOR_TABLE_NAME;
    }
    return configuredTableName;
  }
}
