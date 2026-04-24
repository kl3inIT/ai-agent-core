# Technology Stack

**Analysis Date:** 2026-04-24

## Languages

**Primary:**
- Java 21 - All production and test code in `ai-agent/ai-agent/src/main/java`, `ai-agent/ai-agent-starter/src/main/java`, and `jmix-app/src/main/java`; Gradle toolchains in `ai-agent/build.gradle` and `jmix-app/build.gradle` require `JavaLanguageVersion.of(21)` with `options.release = 21` for add-on modules.

**Secondary:**
- Groovy Gradle DSL - Build configuration in `build.gradle`, `settings.gradle`, `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`, and `jmix-app/build.gradle`.
- XML - Jmix Flow UI descriptors and Liquibase changelogs in `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/**`, `jmix-app/src/main/resources/com/vn/jmixapp/view/**`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/**`, and `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/**`.
- Properties/YAML - Application configuration in `jmix-app/src/main/resources/application.properties`, `jmix-app/src/main/resources/application-hsqldb.properties`, test configuration in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`, and default AI parameters in `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml`.

## Runtime

**Environment:**
- JVM 21 - Required by Gradle toolchain settings in `ai-agent/build.gradle` and `jmix-app/build.gradle`; use Java 21 locally even if older project notes mention Java 17.
- Spring Boot 3.x via Jmix 2.8.1 - Bootstraps `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java` and starter auto-configurations in `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent`.

**Package Manager:**
- Gradle Wrapper 8.14.4 - Wrapper properties in `gradle/wrapper/gradle-wrapper.properties`, `ai-agent/gradle/wrapper/gradle-wrapper.properties`, and `jmix-app/gradle/wrapper/gradle-wrapper.properties`.
- Lockfile: missing - No `gradle.lockfile` or dependency-locking files detected.
- Composite build: root `settings.gradle` includes `jmix-app` and `ai-agent`; `jmix-app/settings.gradle` can include `../ai-agent` when opened standalone.

## Frameworks

**Core:**
- Jmix 2.8.1 - Main application framework and BOM configured in `ai-agent/build.gradle` and `jmix-app/build.gradle`; used for entities, DataManager access, security roles, Flow UI, Liquibase integration, and add-on packaging.
- Spring Boot - Application runtime, auto-configuration, cache abstraction, testing, and web stack through Jmix and dependencies in `jmix-app/build.gradle` and `ai-agent/ai-agent-starter/ai-agent-starter.gradle`.
- Spring AI 1.1.4 - Chat client, OpenAI/OpenRouter model starter, JDBC chat memory repository, pgvector vector store, Tika document ingestion, RAG, and tool annotations configured in `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`, and `jmix-app/build.gradle`.
- Vaadin Flow via Jmix Flow UI - UI views and components in `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/**`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/**`, `jmix-app/src/main/resources/com/vn/jmixapp/view/**`, and `jmix-app/src/main/java/com/vn/jmixapp/view/**`.

**Testing:**
- JUnit 5 / Spring Boot Test - Test runner configured in `ai-agent/ai-agent/ai-agent.gradle` and `jmix-app/build.gradle`; default add-on tests exclude `live`, `rag-it`, and `eval` tags.
- Jmix Flow UI Test Assist - UI testing support declared in `jmix-app/build.gradle` and used by host app UI tests under `jmix-app/src/test/java`.
- Testcontainers PostgreSQL 1.19.8 - RAG/pgvector integration test support declared in `ai-agent/ai-agent/ai-agent.gradle`; `integrationTest` runs `@Tag("rag-it")` tests and requires Docker.
- Spring AI Test 1.1.4 - AI test helpers declared in `ai-agent/ai-agent/ai-agent.gradle` and `ai-agent/ai-agent-starter/ai-agent-starter.gradle`.
- ASM 9.9 - Bytecode-level read-only tool enforcement tests declared in `ai-agent/ai-agent/ai-agent.gradle`.

**Build/Dev:**
- Jmix Gradle Plugin 2.8.1 - Configures add-on modules via `ai-agent/build.gradle` and host app via `jmix-app/build.gradle`.
- Spring Dependency Management Plugin 1.1.6 - Imports Spring AI BOM in `ai-agent/build.gradle`.
- Spring Boot Gradle Plugin - Applied by Jmix/Spring plugins in `jmix-app/build.gradle` for `bootRun` and executable app tasks.
- Vaadin Gradle Plugin - Applied in `jmix-app/build.gradle` with `com.vaadin` for Flow UI frontend build support.
- Maven Publish - Add-on modules publish `com.vn:ai-agent` and `com.vn:ai-agent-starter` from `ai-agent/build.gradle`.

## Key Dependencies

**Critical:**
- `io.jmix.core:jmix-core-starter` / `io.jmix.data:jmix-eclipselink-starter` / `io.jmix.security:jmix-security-starter` - Core Jmix runtime, persistence, and security in `ai-agent/ai-agent/ai-agent.gradle` and `jmix-app/build.gradle`.
- `io.jmix.flowui:jmix-flowui-starter` and `io.jmix.security:jmix-security-flowui-starter` - Add-on and host Flow UI screens plus role annotations in `ai-agent/ai-agent/ai-agent.gradle` and `jmix-app/build.gradle`.
- `org.springframework.ai:spring-ai-starter-model-openai:1.1.4` - OpenAI-compatible model client used by the starter in `ai-agent/ai-agent-starter/ai-agent-starter.gradle`; configured for OpenRouter in `jmix-app/src/main/resources/application.properties` and `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- `org.springframework.ai:spring-ai-client-chat:1.1.4` - ChatClient and `@Tool` annotations used by add-on code and host tool contributors; declared in `ai-agent/ai-agent/ai-agent.gradle` and explicitly in `jmix-app/build.gradle`.
- `org.springframework.ai:spring-ai-model-chat-memory-repository-jdbc:1.1.4` and `org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc:1.1.4` - JDBC chat memory projection layer and starter integration in `ai-agent/ai-agent/ai-agent.gradle` and `ai-agent/ai-agent-starter/ai-agent-starter.gradle`.
- `org.springframework.ai:spring-ai-starter-vector-store-pgvector:1.1.4` - pgvector-backed vector store public API for RAG in `ai-agent/ai-agent/ai-agent.gradle`.
- `org.springframework.ai:spring-ai-rag:1.1.4` - Retrieval augmentation advisor and document retriever public API in `ai-agent/ai-agent/ai-agent.gradle`.

**Infrastructure:**
- `org.postgresql:postgresql` - PostgreSQL JDBC runtime in `ai-agent/ai-agent/ai-agent.gradle` and `jmix-app/build.gradle`.
- `org.hsqldb:hsqldb` - Local fallback/test database in `jmix-app/build.gradle`, `jmix-app/src/main/resources/application-hsqldb.properties`, and `ai-agent/ai-agent/ai-agent.gradle` tests.
- `org.springframework.boot:spring-boot-starter-cache` - Cache abstraction backing rate limit and token budget guards in `ai-agent/ai-agent/ai-agent.gradle` and `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/**`.
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` - Strict YAML serialization/deserialization for AI parameters in `ai-agent/ai-agent/ai-agent.gradle` and parameter code under `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/**`.
- `jakarta.validation:jakarta.validation-api` - Validation annotations for parameter records in `ai-agent/ai-agent/ai-agent.gradle`.
- `org.springframework.ai:spring-ai-tika-document-reader:1.1.4` - Knowledge document extraction in `ai-agent/ai-agent/ai-agent.gradle` and RAG ingestion code under `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/**`.
- `com.vladsch.flexmark:flexmark`, `flexmark-ext-tables`, `flexmark-ext-autolink` - Markdown rendering for assistant output in `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java`.
- `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` - Sanitizes rendered assistant HTML in `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java`.
- `io.jmix.gridexport:jmix-gridexport-flowui-starter` - Export actions for AI audit screens in `ai-agent/ai-agent/ai-agent.gradle` and views under `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/**`.

## Configuration

**Environment:**
- Host app imports developer environment files with `spring.config.import=optional:file:.env[.properties],optional:file:../.env[.properties]` in `jmix-app/src/main/resources/application.properties`; `.env` exists at repo root and must not be read or committed with secrets.
- OpenRouter/OpenAI-compatible API key is configured with `OPENROUTER_API_KEY` through Spring property placeholders in `jmix-app/src/main/resources/application.properties` and `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- OpenRouter model defaults are set through `jmix.ai-agent.defaults.*` and `spring.ai.openai.chat.options.model` in `jmix-app/src/main/resources/application.properties`; add-on tests provide stub defaults in `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.
- PostgreSQL datasource settings for main and agentstore stores live in `jmix-app/src/main/resources/application.properties`; do not copy credential values from this file into docs or code.
- HSQLDB fallback profile lives in `jmix-app/src/main/resources/application-hsqldb.properties` for local boot with `--spring.profiles.active=hsqldb`.

**Build:**
- Root composite build files: `settings.gradle`, `build.gradle`, and `gradle.properties`.
- Add-on build files: `ai-agent/settings.gradle`, `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, and `ai-agent/ai-agent-starter/ai-agent-starter.gradle`.
- Host app build files: `jmix-app/settings.gradle`, `jmix-app/build.gradle`, and `jmix-app/gradle.properties`.
- Spring Boot auto-configuration imports are declared in `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Jmix module metadata lives in `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`.
- Liquibase master changelogs live in `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml`, `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml`, and `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/agentstore-changelog.xml`.

## Platform Requirements

**Development:**
- Use Java 21 and Gradle Wrapper 8.14.4 from `gradlew` / `gradlew.bat`.
- Use `./gradlew bootRun` from the root composite or `jmix-app` build to start the host app; `jmix-app/build.gradle` sets `com.vn.jmixapp.JmixAppApplication` as the main class.
- Use PostgreSQL with pgvector for full RAG behavior because `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-kb-vector-store.xml` manages vector-store schema and `spring-ai-starter-vector-store-pgvector` expects pgvector support.
- Use Docker for `./gradlew :ai-agent:ai-agent:integrationTest` because Testcontainers PostgreSQL is required for `@Tag("rag-it")` tests.
- Set `OPENROUTER_API_KEY` only for live tests or real chat calls; `./gradlew :ai-agent:ai-agent:test` excludes `live` tests by default.

**Production:**
- Deployment target is a Jmix/Spring Boot application consuming `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` as shown in `jmix-app/build.gradle`.
- Runtime database target is PostgreSQL for the host `main` store and `agentstore` additional store configured in `jmix-app/src/main/resources/application.properties`.
- AI model access is OpenAI-compatible HTTP through Spring AI’s OpenAI starter, configured to OpenRouter through `spring.ai.openai.base-url` and `OPENROUTER_API_KEY`.
- Add-on artifacts publish to the custom Nexus repository configured in `ai-agent/build.gradle`; local composite builds avoid requiring published snapshots during development.

---

*Stack analysis: 2026-04-24*
