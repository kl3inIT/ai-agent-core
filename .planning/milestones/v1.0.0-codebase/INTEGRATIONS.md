# External Integrations

**Analysis Date:** 2026-04-24

## APIs & External Services

**LLM Providers:**
- OpenRouter via Spring AI OpenAI-compatible client - Used for chat completions, tool calling, and live semantic tests.
  - SDK/Client: `org.springframework.ai:spring-ai-starter-model-openai:1.1.4` in `ai-agent/ai-agent-starter/ai-agent-starter.gradle`; low-level chat/tool annotations from `org.springframework.ai:spring-ai-client-chat:1.1.4` in `ai-agent/ai-agent/ai-agent.gradle` and `jmix-app/build.gradle`.
  - Auth: `OPENROUTER_API_KEY` property placeholder in `jmix-app/src/main/resources/application.properties` and `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
  - Base URL: `spring.ai.openai.base-url` in `jmix-app/src/main/resources/application.properties`; test default points to OpenRouter-compatible `/api/v1` in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
  - Model: `spring.ai.openai.chat.options.model` and `jmix.ai-agent.defaults.model` in `jmix-app/src/main/resources/application.properties`; defaults use OpenRouter model names such as `openai/gpt-4o-mini`.

**AI Framework Services:**
- Spring AI ChatClient - Core orchestration path for `ChatService` and default implementation.
  - SDK/Client: `org.springframework.ai:spring-ai-client-chat:1.1.4` and `org.springframework.ai:spring-ai-starter-model-openai:1.1.4`.
  - Implementation: `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`, `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`, and orchestration classes under `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/**`.
- Spring AI tool calling - LLM-facing tools include built-in data tools and host-contributed tools.
  - SDK/Client: `org.springframework.ai:spring-ai-client-chat:1.1.4` for `@Tool` / `@ToolParam` annotations.
  - Built-ins: tool classes under `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/**`.
  - Host extension: `ToolContributor` SPI in `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java`; sample host implementation in `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`.
- Spring AI RAG - Retrieval augmentation for knowledge base queries.
  - SDK/Client: `org.springframework.ai:spring-ai-rag:1.1.4`, `org.springframework.ai:spring-ai-starter-vector-store-pgvector:1.1.4`, and `org.springframework.ai:spring-ai-tika-document-reader:1.1.4` in `ai-agent/ai-agent/ai-agent.gradle`.
  - Implementation: RAG services under `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/**` and knowledge UI under `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/**`.

**Artifact Repository:**
- Custom Nexus snapshot repository - Used for publishing add-on modules.
  - Client: Gradle `maven-publish` in `ai-agent/build.gradle`.
  - Auth: Gradle properties `nexusUsername` and `nexusPassword` in `ai-agent/build.gradle`; keep values outside committed files.
  - Repository: `CustomNexus` configured in `ai-agent/build.gradle`.

**Jmix Repository:**
- Jmix public Maven repository - Resolves Jmix artifacts.
  - Client: Gradle Maven repository `https://global.repo.jmix.io/repository/public` in `ai-agent/build.gradle` and `jmix-app/build.gradle`.
  - Auth: Not detected.

**Spring Milestone/Snapshot Repositories:**
- Spring repository endpoints - Resolve Spring AI 1.1.4 artifacts if not available from Maven Central.
  - Client: Gradle Maven repositories `https://repo.spring.io/milestone` and `https://repo.spring.io/snapshot` in `ai-agent/build.gradle` and `jmix-app/build.gradle`.
  - Auth: Not detected.

## Data Storage

**Databases:**
- PostgreSQL main store - Host application data for users, customers, orders, products, and Jmix security.
  - Connection: `main.datasource.*` properties in `jmix-app/src/main/resources/application.properties`; credential values are present in this file and must not be copied into docs or logs.
  - Client: Jmix `DataManager`, EclipseLink, and `io.jmix.data:jmix-eclipselink-starter` in `jmix-app/build.gradle`.
  - Changelog: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` and child changelogs under `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/**`.
- PostgreSQL `agentstore` additional store - AI conversations, messages, parameters, tool audit, chat memory projection, knowledge documents, and vector store tables.
  - Connection: `agentstore.datasource.*` and `jmix.core.additional-stores=agentstore` in `jmix-app/src/main/resources/application.properties`; credential values are present in this file and must not be copied into docs or logs.
  - Client: Jmix additional data store, Spring AI JDBC chat memory repository, and Spring AI pgvector vector store.
  - Changelog: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/agentstore-changelog.xml` includes add-on store changelog from `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml`.
- HSQLDB fallback/test store - Local fallback profile and add-on tests.
  - Connection: `main.datasource.*` in `jmix-app/src/main/resources/application-hsqldb.properties` and test properties in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
  - Client: Jmix/EclipseLink and HSQLDB runtime dependency in `jmix-app/build.gradle` and `ai-agent/ai-agent/ai-agent.gradle`.
- Testcontainers PostgreSQL - Integration testing database for pgvector/RAG behavior.
  - Connection: Managed by Testcontainers in tests tagged `rag-it`.
  - Client: `org.testcontainers:postgresql:1.19.8` and `org.testcontainers:junit-jupiter:1.19.8` in `ai-agent/ai-agent/ai-agent.gradle`.

**File Storage:**
- Jmix LocalFS - Host app includes `io.jmix.localfs:jmix-localfs-starter` in `jmix-app/build.gradle`.
- RAG upload staging - `jmix.ai-agent.rag.upload.file-staging-root=${jmix.core.temp-dir}` in `jmix-app/src/main/resources/application.properties`; ingestion code under `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/**` uses local staging and Tika document reading.
- Static UI assets - Images in `jmix-app/src/main/resources/META-INF/resources/**` and add-on CSS in `ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css`.

**Caching:**
- Spring Cache abstraction - Guard state uses cache names `ai-agent.rateLimit` and `ai-agent.tokenBreaker`.
  - Client: `org.springframework.boot:spring-boot-starter-cache` in `ai-agent/ai-agent/ai-agent.gradle`.
  - Default provider: starter auto-configuration creates a `ConcurrentMapCacheManager` when the host does not provide one, implemented in `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java`.
  - Guard implementations: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/RateLimitGuard.java` and `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/TokenBudgetGuard.java`.

## Authentication & Identity

**Auth Provider:**
- Jmix/Spring Security - Host app authentication, authorization, and row-level controls.
  - Implementation: `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`, `jmix-app/src/main/java/com/vn/jmixapp/security/DatabaseUserRepository.java`, and role interfaces under `jmix-app/src/main/java/com/vn/jmixapp/security/**`.
  - Add-on roles: `ai-agent/ai-agent/src/main/java/com/vn/agent/security/**` define AI user/admin/resource/row-level access policies.
  - User context: AI tools run through Jmix security-aware `DataManager` and access checks; avoid adding a parallel AI exposure layer.

**Authorization:**
- Jmix ResourceRole/RowLevelRole policies - Entity, attribute, view, and menu policies govern AI screens and data access.
  - Implementation: add-on security package `ai-agent/ai-agent/src/main/java/com/vn/agent/security/**` and host security package `jmix-app/src/main/java/com/vn/jmixapp/security/**`.
  - Data path: built-in tools under `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/**` use Jmix metadata/security rather than direct unaudited SQL.

## Monitoring & Observability

**Error Tracking:**
- None detected - No Sentry, Rollbar, Datadog, New Relic, or OpenTelemetry exporter dependencies detected in Gradle build files.

**Logs:**
- Spring Boot/Jmix logging - Log levels configured in `jmix-app/src/main/resources/application.properties` for EclipseLink SQL, Jmix datastore, access logger, Spring Security, and Atmosphere.
- Application logs - Classes use SLF4J/logging through Spring/Jmix conventions; examples include `jmix-app/src/main/java/com/vn/jmixapp/ai/ChatServiceSmokeRunner.java` and service/orchestration classes under `ai-agent/ai-agent/src/main/java/com/vn/agent/**`.
- Audit persistence - Tool call and chat audit data is stored as Jmix entities and durable rows through code under `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/**` and entity `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java`.
- UI status updates - Document processing events use classes under `ai-agent/ai-agent/src/main/java/com/vn/agent/push/**` and knowledge views under `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/**`.

## CI/CD & Deployment

**Hosting:**
- Not detected - No deployment-specific manifests such as Dockerfile, Kubernetes manifests, Procfile, or cloud platform config detected in the repository root.
- Runtime package: Jmix/Spring Boot host app in `jmix-app` consuming add-on starter `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` from `jmix-app/build.gradle`.

**CI Pipeline:**
- Not detected - No GitHub Actions, GitLab CI, Jenkinsfile, Azure Pipelines, or similar CI config detected in the scanned repository paths.
- Publish pipeline assumptions: `ai-agent/build.gradle` defines Maven publication to `CustomNexus`; CI or release automation is not present in repo.

## Environment Configuration

**Required env vars:**
- `OPENROUTER_API_KEY` - Required for real OpenRouter chat calls and `liveTest`; referenced in `jmix-app/src/main/resources/application.properties` and `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- `OPENROUTER_BASE_URL` - Optional live-test override in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- `OPENROUTER_MODEL` - Optional live-test model override in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- `nexusUsername` / `nexusPassword` - Gradle project properties required only when publishing add-on artifacts to the custom Nexus repository configured in `ai-agent/build.gradle`.

**Application properties:**
- `spring.ai.openai.base-url` - OpenAI-compatible endpoint override in `jmix-app/src/main/resources/application.properties`.
- `spring.ai.openai.api-key` - Reads `OPENROUTER_API_KEY` in `jmix-app/src/main/resources/application.properties`.
- `spring.ai.openai.chat.options.model` - Default chat model in `jmix-app/src/main/resources/application.properties`.
- `jmix.ai-agent.defaults.*` - Default model, temperature, top-p, max tokens, and system prompt in `jmix-app/src/main/resources/application.properties`; test defaults in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- `spring.ai.chat.memory.repository.jdbc.initialize-schema=never` - Liquibase owns chat memory DDL in `jmix-app/src/main/resources/application.properties`.
- `spring.ai.vectorstore.pgvector.initialize-schema=false` - Liquibase owns pgvector DDL in `jmix-app/src/main/resources/application.properties`.
- `jmix.core.additional-stores=agentstore` - Enables the AI additional store in `jmix-app/src/main/resources/application.properties`.
- `agentstore.datasource.studio.liquibase.exclude-prefixes` - Prevents Jmix Studio diffs from dropping Spring AI-managed table prefixes in `jmix-app/src/main/resources/application.properties`.

**Secrets location:**
- `.env` file present at repository root - Used by `spring.config.import` for developer secrets; do not read, quote, or commit its contents.
- `jmix-app/src/main/resources/application.properties` contains datasource credential properties inline - Treat values as sensitive and move to environment-specific overrides when hardening deployment.
- Gradle publishing credentials are expected from Gradle properties `nexusUsername` and `nexusPassword`; no values should be committed.

## Webhooks & Callbacks

**Incoming:**
- None detected - No webhook controller endpoints detected under `ai-agent/ai-agent/src/main/java`, `ai-agent/ai-agent-starter/src/main/java`, or `jmix-app/src/main/java`.
- HTTP application endpoints are Spring Boot/Jmix web and Vaadin routes from `jmix-app/src/main/java/com/vn/jmixapp/view/**` and add-on views under `ai-agent/ai-agent/src/main/java/com/vn/agent/view/**`.

**Outgoing:**
- OpenRouter/OpenAI-compatible chat API calls - Performed through Spring AI OpenAI starter using properties in `jmix-app/src/main/resources/application.properties`.
- Custom Nexus publication - Outgoing artifact publishing configured in `ai-agent/build.gradle`.
- Maven dependency resolution - Outgoing build-time access to Maven Central, Jmix public repository, Spring milestone repository, and Spring snapshot repository configured in `ai-agent/build.gradle` and `jmix-app/build.gradle`.

---

*Integration audit: 2026-04-24*
