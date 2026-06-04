# Technology Stack

**Analysis Date:** 2026-06-04

## Languages

**Primary:**
- Java 21 - All addon (`ai-agent/ai-agent/`) and host (`jmix-app/`) source. Compiled with `options.release = 21` and `-parameters` (required by Spring AI `@ToolParam` reflective binding).

**Secondary:**
- XML - Jmix view descriptors (`src/main/resources/**/view/**`), Liquibase changelogs, `menu.xml`, `module.properties`.
- YAML - Parameter bodies / catalog defaults (`AiParametersBody` ser/de, `default-params.yaml`, eval fixtures in `src/test/resources/eval/*.yaml`).

## Runtime

**Environment:**
- JVM 21 (Gradle toolchain `JavaLanguageVersion.of(21)` in `ai-agent/build.gradle` and `jmix-app/build.gradle`).
- Spring Boot 3 (via Jmix 2.8 BOM).
- Note: addon test classpath compiles against JDK 25 in CI (ASM bumped to 9.9 for class-file major 69 bytecode scans — see `ai-agent.gradle:105`).

**Package Manager:**
- Gradle 8.14.4 (wrapper under `gradle/wrapper/`).
- Multi-module addon build: root `ai-agent/settings.gradle` includes `ai-agent` (main module) + `ai-agent-starter` (auto-configuration). Host `jmix-app/` is a separate Gradle build consuming the addon via `mavenLocal()`.
- Lockfile: not used (Gradle dynamic resolution via BOMs).

## Frameworks

**Core:**
- Jmix 2.8.1 - Application platform. `bomVersion = '2.8.1'`, plugin `io.jmix.gradle:jmix-gradle-plugin:2.8.1`. Starters in use: `jmix-core`, `jmix-eclipselink` (EclipseLink ORM), `jmix-security`, `jmix-security-flowui`, `jmix-security-data` (row-level constraints), `jmix-flowui` (Vaadin Flow UI), `jmix-flowui-themes`, `jmix-gridexport-flowui` (Excel/JSON audit export). Host adds `jmix-localfs`, `jmix-datatools`.
- Spring Boot 3 - Pulled via Jmix BOM; `org.springframework.boot:spring-boot-starter-web` (host + addon test), `spring-boot-starter-cache` (rate-limit / token-budget guards).
- Vaadin Flow - UI rendering through Jmix Flow UI. Hilla and Vaadin `copilot` modules explicitly excluded in both builds.

**AI / LLM:**
- Spring AI 1.1.4 - `spring-ai-bom:1.1.4` imported via dependency-management plugin. Modules:
  - `spring-ai-client-chat` - `ChatClient`, `@Tool`/`@ToolParam` callbacks.
  - `spring-ai-openai` - OpenAI-compatible client (points at OpenRouter base URL).
  - `spring-ai-model-chat-memory-repository-jdbc` - JDBC chat memory (decorated by `ProjectingChatMemoryRepository`).
  - `spring-ai-starter-vector-store-pgvector` (`api` scope) - pgvector `VectorStore`.
  - `spring-ai-tika-document-reader` - document parsing for RAG ingestion.
  - `spring-ai-rag` (`api` scope) - `RetrievalAugmentationAdvisor`, `VectorStoreDocumentRetriever`.

**Testing:**
- JUnit 5 (Jupiter) via `spring-boot-starter-test` (vintage engine excluded); `junit-platform-launcher` runtime.
- `jmix-flowui-test-assist` - Jmix UI test harness.
- Testcontainers 1.19.8 (`postgresql`, `junit-jupiter`) - pgvector integration tests (`@Tag("rag-it")`, Docker-gated).
- `spring-ai-test:1.1.4` - AI evaluation tests (`@Tag("eval")`).
- `org.ow2.asm:asm:9.9` - bytecode scan enforcing read-only `BuiltInDataTools`.
- `net.ttddyy:datasource-proxy:1.11.0` - JDBC SELECT counting for N+1 perf smoke tests.
- HSQLDB - in-memory test datastore.

**Build/Dev:**
- `io.spring.dependency-management:1.1.7` - BOM import.
- `org.jetbrains.gradle.plugin.idea-ext:1.4.1` - host IDE config.
- `com.vaadin` Gradle plugin - frontend bundle (host: `vaadin { optimizeBundle = false }`).

## Key Dependencies

**Critical:**
- `org.springframework.ai:spring-ai-*:1.1.4` - Entire LLM/RAG layer.
- `io.jmix.*:*:2.8.1` - Platform, security, persistence, UI.
- `org.postgresql:postgresql` - Primary + agentstore datastore driver (also carries pgvector).

**Infrastructure:**
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` - `AiParametersBody` strict YAML.
- `jakarta.validation:jakarta.validation-api` - Bean Validation on parameter records (Hibernate Validator transitive).
- `com.vladsch.flexmark:flexmark:0.64.8` (+ `flexmark-ext-tables`, `flexmark-ext-autolink`) - Markdown rendering of assistant output.
- `com.googlecode.owasp-java-html-sanitizer:20220608.1` - XSS sanitizer for rendered assistant HTML.

## Configuration

**Environment:**
- Host config: `jmix-app/src/main/resources/application.properties`. Imports developer secrets via `spring.config.import=optional:file:.env[.properties],optional:file:../.env[.properties]`.
- Addon defaults: `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` (RAG splitter sizing, ingest executor pools, model catalog, retry, guards, audit hashing).
- Key runtime knobs: `spring.ai.openai.base-url` (OpenRouter), `spring.ai.openai.api-key=${OPENROUTER_API_KEY:}`, model `qwen/qwen3.6-35b-a3b` (chat) + `qwen/qwen3-embedding-4b` (embedding, 2000 dims), pgvector table `AI_AGENT_KB_VECTOR_STORE` (HNSW), `ai-agent.tools.mutation.enabled`, `jmix.ai-agent.tools.max-filter-depth=3`.
- `.env` files present for secrets — contents not committed.

**Build:**
- `ai-agent/build.gradle` (root subproject config: Java 21, Jmix BOM, Spring AI BOM, Maven publishing to Nexus).
- `ai-agent/ai-agent/ai-agent.gradle` (module deps + custom test tasks `evalTest`/`liveTest`/`integrationTest`).
- `ai-agent/gradle.properties` (`version=1.1.1-SNAPSHOT`; Nexus creds via CI/user-global only).

## Platform Requirements

**Development:**
- JDK 21 (toolchain). PostgreSQL 16 with pgvector for full-feature dev: `docker compose up -d` (`docker-compose.yml` → `pgvector/pgvector:pg16`, host port 5433, databases `ai-agent` + `agentstore`).
- HSQLDB fallback profile (`--spring.profiles.active=hsqldb`) for the host app.
- App runs at `http://localhost:8088` (host `server.port=8088`), login admin/admin.

**Production:**
- Addon published as Maven artifacts (`com.vn:ai-agent`, `com.vn:ai-agent-starter`) to Nexus (`nexus.x2h.com.vn`, snapshot vs release repo chosen by version suffix; HTTPS enforced). Consumed by any Jmix host app.
- Requires PostgreSQL with pgvector for RAG; an OpenAI-compatible LLM endpoint (OpenRouter by default).

---

*Stack analysis: 2026-06-04*
