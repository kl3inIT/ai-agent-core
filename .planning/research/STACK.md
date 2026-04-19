# Stack Research — Jmix 2.8 AI Copilot Add-on

**Domain:** Reusable Jmix 2.8 add-on that embeds Spring AI 1.1.4 (ChatClient + advisors + RAG + tools + chat memory) into any host Jmix application, shipping a Flow UI for chat / KB / params / audit.
**Researched:** 2026-04-18
**Overall confidence:** HIGH for Spring AI 2.x artifact naming, advisor/tool API surface, pgvector + JDBC chat memory starters, Jmix add-on module layout. MEDIUM on 1.1.4-specific items that could shift between milestones (noted inline); LOW on `StructuredOutputValidationAdvisor` (name not found in Spring AI docs — see "What NOT to Use" + "Gaps").

Research procedure: Context7 `/websites/spring_io_spring-ai_reference` (High reputation) + `/spring-projects/spring-ai` + `/jmix-framework/jmix-context7`. Cross-referenced against two working codebases: `D:/ai/traffic-law-chatbot/build.gradle` (Spring AI 1.1.4 + OpenRouter + pgvector + JDBC memory, Boot 4.0.5 — proves coordinates resolve from Spring milestone repo) and `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/build.gradle` (Spring AI 1.1.2 + Jmix 2.7.4 — proves Jmix + Spring AI coexistence pattern).

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|---|---|---|---|
| Java | 17 | Language runtime | Fixed by Jmix 2.8 / Spring Boot 3 baseline. Spring AI 2.x supports 17+. HIGH. |
| Jmix | 2.8.0 | Host framework (entities, DataManager, Flow UI, security, Liquibase) | Fixed by product spec. `io.jmix:jmix-bom:2.8.0` + `io.jmix` Gradle plugin 2.8.0. HIGH. |
| Spring Boot | 3.x (transitive via Jmix BOM) | App container, auto-config | Pulled by Jmix 2.8 BOM. Compatible with Spring AI 1.1.4 (which requires Boot 3.4+). HIGH. |
| Spring AI BOM | `org.springframework.ai:spring-ai-bom:1.1.4` | Version manager for every Spring AI artifact | Mandated by product spec. BOM is the ONLY supported way to pin 2.x coordinates — individual artifacts MUST NOT carry explicit versions. HIGH (coordinate verified against `traffic-law-chatbot` working build). |
| Vaadin Flow | (transitive via Jmix 2.8) | Server-side UI for add-on's Flow UI module | Fixed by Jmix. Used in `ai-agent-flowui` module. HIGH. |
| pgvector (Postgres extension) | Postgres 13+ with `pgvector` extension | Vector store for RAG knowledge base | Spring AI first-class; SQL-familiar ops teams; enterprise Postgres ubiquity; schema managed by Spring AI autoconfig (opt-in) or Liquibase (`CREATE EXTENSION IF NOT EXISTS vector`). HIGH. |
| EclipseLink | (via `jmix-eclipselink-starter`) | JPA provider for host entities + our audit/KB entities | Jmix default. All add-on JPA entities (`AiToolCallAudit`, KB metadata, conversation rows if we back chat memory with JPA) flow through it. HIGH. |
| Liquibase | (Jmix default) | Schema migrations for add-on entities + `CREATE EXTENSION vector` | Jmix convention: changelogs in `src/main/resources/**/liquibase/changelog/*.xml`, include in `changelog.xml`. Initialize pgvector via Liquibase so the extension is guaranteed before Spring AI's vector store bean wires. HIGH. |

### Spring AI 1.1.4 Artifacts

Every coordinate below uses the **new 2.x naming pattern** (`spring-ai-starter-model-*`, `spring-ai-starter-vector-store-*`, `spring-ai-starter-model-chat-memory-repository-*`) — verified against Spring AI upgrade notes (Context7 `/websites/spring_io_spring-ai_reference`). No version on any line; BOM resolves them.

| Artifact | Module placement | Purpose | Confidence |
|---|---|---|---|
| `org.springframework.ai:spring-ai-starter-model-openai` | `ai-agent` (functional) | OpenAI-compatible chat + embedding client; used with OpenRouter via `base-url` override | HIGH |
| `org.springframework.ai:spring-ai-starter-vector-store-pgvector` | `ai-agent` (functional) | pgvector `VectorStore` auto-configuration | HIGH |
| `org.springframework.ai:spring-ai-advisors-vector-store` | `ai-agent` (functional) | `QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor` for RAG | HIGH |
| `org.springframework.ai:spring-ai-rag` | `ai-agent` (functional) | Modular RAG primitives (query transformers, rewriter, compressor) used by `RetrievalAugmentationAdvisor` | HIGH |
| `org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc` | `ai-agent` (functional) | JDBC-backed `ChatMemoryRepository` (conversation persistence) | HIGH |
| `org.springframework.ai:spring-ai-pdf-document-reader` | `ai-agent` (functional) | PDF ingestion for KB uploads | HIGH |
| `org.springframework.ai:spring-ai-jsoup-document-reader` | `ai-agent` (functional) | HTML ingestion for KB uploads | HIGH |
| `org.springframework.ai:spring-ai-test` (testImplementation) | `ai-agent` (functional) test | Spring AI evaluation harness (semantic equality, structured-output checks) — required by PROJECT.md ("avoid brittle exact-text assertions") | MEDIUM (artifact exists in 2.x per `traffic-law-chatbot`; API surface still evolving) |

**Key API surface (verified against 2.x docs / Spring AI repo):**

- `ChatClient.builder(chatModel).defaultAdvisors(...).defaultTools(...).build()` — the fluent entry point. HIGH.
- Advisor classes confirmed present in 2.x:
  - `MessageChatMemoryAdvisor` (from `spring-ai-advisors-vector-store` + memory module) — HIGH
  - `QuestionAnswerAdvisor` (classical single-shot RAG) — HIGH
  - `RetrievalAugmentationAdvisor` (modular RAG pipeline from `spring-ai-rag`) — HIGH
  - `SafeGuardAdvisor` (optional) — MEDIUM
- Tool calling: **`@Tool` method annotation + `@ToolParam`** on Spring beans is the 2.x idiom. Beans are passed via `.tools(new MyToolsBean())` per request, or registered globally via `ChatClient.Builder.defaultTools(...)`. `ToolCallbacks.from(bean)` utility generates `ToolCallback[]` when programmatic control is needed. HIGH.
- Structured output: `.call().entity(MyDto.class)` / `.entity(new ParameterizedTypeReference<List<X>>(){})`. HIGH.
- Breaking change vs. 1.x: starter renames (`spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai`, `spring-ai-pgvector-store` → `spring-ai-starter-vector-store-pgvector`). HIGH — Spring AI upgrade notes are explicit.

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---|---|---|---|
| PostgreSQL JDBC | (Jmix/Boot BOM) | Postgres driver for pgvector + JDBC chat memory in host apps that enable them | `runtimeOnly` in the `ai-agent` functional module (so starter pulls it transitively); host still declares its own main datasource dep. Demo `jmix-app` keeps HSQLDB for non-AI paths and adds Postgres profile only when exercising pgvector. HIGH. |
| Apache Tika (`tika-core`, `tika-parsers-standard-package`) | 3.x | Fallback extraction for formats Spring AI readers don't handle (docx, xlsx) | Pull into `ai-agent` functional module for KB ingestion pipeline. HIGH (used in both reference projects). |
| Caffeine | 3.x | Embedding cache to cut OpenRouter cost for repeat queries (optional) | `implementation` in `ai-agent` functional, gated by property `ai-agent.cache.embeddings.enabled=true`. MEDIUM — deferred until perf phase, but coordinate is stable. |
| Micrometer + Prometheus registry | (Boot BOM) | Observability for tool-call latency, token counters | `runtimeOnly` in functional module; host opts in via `management.endpoints` config. MEDIUM priority. |
| SLF4J / Logback | (Boot BOM) | Logging of tool calls (complements JPA audit) | Transitive; no explicit dep. HIGH. |

### Development Tools

| Tool | Purpose | Notes |
|---|---|---|
| Gradle 8.14.4 (wrapper) | Build | Composite build already in place (`ai-agent/` + `jmix-app/`). Flow UI module pair is added as two new Gradle subprojects under `ai-agent/`. |
| `io.jmix` Gradle plugin 2.8.0 | Jmix project setup, entity enhancement | Already applied in `ai-agent/build.gradle`. |
| `io.spring.dependency-management` plugin 1.1.x | Imports Spring AI BOM into Jmix subprojects | Required because the Jmix plugin's BOM management does not automatically pull in Spring AI. Apply in `ai-agent/ai-agent/ai-agent.gradle`. HIGH. |
| JUnit 5 + Spring Boot Test + `jmix-flowui-test-assist` | Integration tests | Already present; add `spring-ai-test` for semantic assertions. |
| `@Tag("live")` JUnit tag | Gate LLM-calling tests | Already the pattern in `traffic-law-chatbot`; reuse. `test` task excludes `live`; separate `liveTest` task opts in with `OPENROUTER_API_KEY`. HIGH. |

---

## Installation

### Required Maven repositories

Add to both `ai-agent/build.gradle` (subprojects `repositories` block) and `jmix-app/build.gradle`:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://global.repo.jmix.io/repository/public' }
    maven { url = 'https://repo.spring.io/milestone' }   // REQUIRED: 1.1.4 is a milestone, not yet on Central
    // Only add snapshot repo if you intentionally want nightly builds:
    // maven { url = 'https://repo.spring.io/snapshot' }
}
```

**Why milestone repo is mandatory:** Spring AI 1.1.4 is pre-GA. Maven Central hosts only GA releases (`1.0.x`, `1.1.x`). Without `repo.spring.io/milestone`, the BOM coordinate will fail to resolve. HIGH (verified — `traffic-law-chatbot/build.gradle` declares this repo alongside `1.1.4`).

### Gradle — `ai-agent/ai-agent/ai-agent.gradle` (functional module)

```groovy
plugins {
    id 'io.jmix'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:1.1.4"
    }
}

dependencies {
    // --- Jmix (unchanged from current file) ---
    implementation 'io.jmix.core:jmix-core'
    implementation 'io.jmix.data:jmix-data'
    // Flow UI deps live in the ai-agent-flowui module, NOT here.

    // --- Spring AI core (no versions — BOM-managed) ---
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'
    implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector'
    implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
    implementation 'org.springframework.ai:spring-ai-advisors-vector-store'
    implementation 'org.springframework.ai:spring-ai-rag'

    // --- Document ingestion for KB ---
    implementation 'org.springframework.ai:spring-ai-pdf-document-reader'
    implementation 'org.springframework.ai:spring-ai-jsoup-document-reader'
    implementation 'org.apache.tika:tika-core:3.3.0'
    implementation 'org.apache.tika:tika-parsers-standard-package:3.3.0'

    // --- Driver (runtimeOnly — host may swap, but default path is pgvector/Postgres) ---
    runtimeOnly 'org.postgresql:postgresql'

    // --- Tests ---
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.ai:spring-ai-test'
    testRuntimeOnly 'org.hsqldb:hsqldb'   // non-AI paths
}

test {
    useJUnitPlatform { excludeTags 'live' }
}
tasks.register('liveTest', Test) {
    useJUnitPlatform { includeTags 'live' }
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
}
```

### Gradle — `ai-agent/ai-agent-starter/ai-agent-starter.gradle` (auto-config starter)

Starter carries **no Spring AI deps directly** — they are pulled transitively via the functional module. Starter only wires auto-configuration classes (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — already present).

```groovy
dependencies {
    api project(':ai-agent')   // transitive Spring AI + Jmix data
    implementation 'org.springframework.boot:spring-boot-autoconfigure'
}
```

### Gradle — `ai-agent/ai-agent-flowui/ai-agent-flowui.gradle` (NEW module — Flow UI)

```groovy
plugins {
    id 'io.jmix'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencyManagement {
    imports { mavenBom "org.springframework.ai:spring-ai-bom:1.1.4" }
}

dependencies {
    api project(':ai-agent')                               // core functional add-on
    implementation 'io.jmix.flowui:jmix-flowui'
    implementation 'io.jmix.flowui:jmix-flowui-data'
    implementation 'io.jmix.flowui:jmix-flowui-themes'
    implementation 'io.jmix.security:jmix-security-flowui'
    // Optional: grid export for audit log downloads
    implementation 'io.jmix.gridexport:jmix-gridexport-flowui-starter'
}
```

### Gradle — `ai-agent/ai-agent-flowui-starter/ai-agent-flowui-starter.gradle` (NEW module)

```groovy
dependencies {
    api project(':ai-agent-flowui')
    api project(':ai-agent-starter')   // ensures functional starter also active
    implementation 'org.springframework.boot:spring-boot-autoconfigure'
}
```

Views, menu entries, role interfaces and `messages*.properties` for chat/conversations/parameters/KB/audit all live in `ai-agent-flowui`. Host applications that DO NOT want Flow UI (REST-only) pull `ai-agent-starter` alone.

### `settings.gradle` update (add-on composite)

```groovy
rootProject.name = 'ai-agent-addon'
include 'ai-agent'
include 'ai-agent-starter'
include 'ai-agent-flowui'         // NEW
include 'ai-agent-flowui-starter' // NEW
```

### Application configuration template (for demo host `jmix-app`)

```yaml
spring:
  ai:
    openai:
      base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api}
      api-key:  ${OPENROUTER_API_KEY}
      chat.options.model: ${CHAT_MODEL:openai/gpt-4o-mini}
      embedding:
        base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api}
        options.model: ${EMBEDDING_MODEL:openai/text-embedding-3-small}
    vectorstore.pgvector:
      initialize-schema: false          # Liquibase owns the schema; avoid double-init
      schema-name: public
      table-name: ai_agent_vector_store
      dimensions: 1536                  # must match embedding model
      distance-type: COSINE_DISTANCE
      index-type: HNSW
    chat.memory.repository.jdbc:
      initialize-schema: never          # Liquibase owns it
```

HIGH confidence: property keys verified against Spring AI reference docs + `traffic-law-chatbot` working app.

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|---|---|---|
| `spring-ai-starter-model-openai` + OpenRouter `base-url` | `spring-ai-starter-model-anthropic`, `spring-ai-starter-model-google-genai`, `spring-ai-starter-model-ollama` | Host wants a direct, non-OpenAI-compatible provider SDK. PROJECT.md defers this to post-MVP; SPI lets hosts swap `ChatModel` bean. |
| pgvector | `spring-ai-starter-vector-store-chroma`, `-redis`, `-elasticsearch`, `-milvus`, `-weaviate` | Host's ops team already runs one of these; no Postgres. Our add-on should keep pgvector as default but NOT hard-code it — expose the `VectorStore` as a bean the host can override. |
| JDBC chat memory | `-cassandra`, `-neo4j`, or in-memory `InMemoryChatMemoryRepository` | Greenfield POC or host without a relational DB. JDBC is the only option that cleanly reuses the host's existing DataSource and stays Jmix-security-visible. |
| `QuestionAnswerAdvisor` for MVP RAG | `RetrievalAugmentationAdvisor` (modular pipeline from `spring-ai-rag`) | Later phases where we need query rewriting / compression / multi-query. Ship `spring-ai-rag` on the classpath from day one so we can swap without a dep change. |
| Spring AI 1.1.4 | Spring AI 1.1.2 (GA) | Host needs production-grade stability NOW. 1.1.2 ships today, works with Jmix 2.7/2.8 (proven by `jmix-ai-backend`). Product spec mandates 2.x — document this as a known risk. |
| Jmix-managed Liquibase for `CREATE EXTENSION vector` | Spring AI's `initialize-schema: true` | Single-source schema story keeps Jmix Studio diffing and CI reproducibility intact. Only use the Spring AI initializer for throwaway dev profiles. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|---|---|---|
| `spring-ai-openai-spring-boot-starter` (1.x naming) | Renamed in 2.x; the old ID resolves to stale jars or nothing in milestone repo | `spring-ai-starter-model-openai` |
| `spring-ai-pgvector-store` (1.x artifact) | Renamed | `spring-ai-starter-vector-store-pgvector` (transitive `spring-ai-autoconfigure-vector-store-pgvector`) |
| Hard-coding Spring AI versions on each dep | BOM should be the single source of truth; mixed versions across M4 artifacts will compile but crash at runtime with `NoSuchMethodError` | Import `spring-ai-bom:1.1.4` once, leave artifact lines version-less |
| Omitting `repo.spring.io/milestone` | M4 artifacts are not on Maven Central | Add the milestone repo to both `ai-agent/` and `jmix-app/` |
| `ChatClient.builder(...).tools(List.of(new Tool(...)))` (old `tools()` signature) | Silently fails post-M8 per official migration notes | Pass bean instances directly: `.tools(new MyToolsBean())` or `.defaultTools(...)`; register `@Tool` methods on beans |
| `FunctionCallingOptions` / `.functions(...)` / `.functionCallbacks(...)` | Replaced by tool calling API in 2.x | `@Tool` annotation + `ToolCallbacks.from(bean)` |
| Lombok on Jmix entities | Banned by `CLAUDE.md`; breaks Jmix enhancer | Manual getters/setters; instantiate via `Metadata.create()` |
| `EntityManager` anywhere in add-on | Banned by `CLAUDE.md`; bypasses Jmix security | `DataManager` only — including inside tool implementations |
| `MessageAggregator` (deprecated) | Removed/deprecated in 2.x | `ChatClientMessageAggregator` |
| Hard dependency on a specific `ChatModel` concrete class | Breaks SPI swappability requirement | Inject `ChatModel` / `EmbeddingModel` beans; let host override |
| Wrapping `VectorStore` with our own abstraction | Explicitly forbidden by PROJECT.md ("Out of Scope — Custom vector-store abstractions") | Use Spring AI `VectorStore` directly |
| `StructuredOutputValidationAdvisor` as a hard requirement | LOW confidence — could not verify this class name in Spring AI 2.x docs; may be renamed, moved, or removed. Originating mention appears to be speculative in PROJECT.md | Use `.call().entity(Dto.class)` with `BeanOutputConverter` (HIGH-confidence API); catch/re-ask on parse failure via a simple retry advisor. Flag as research gap for the structured-output phase. |

---

## Module Placement Cheat-Sheet

| Dependency | `ai-agent` (functional) | `ai-agent-starter` | `ai-agent-flowui` | `ai-agent-flowui-starter` |
|---|:---:|:---:|:---:|:---:|
| Spring AI BOM import | yes | transitive | yes | transitive |
| `spring-ai-starter-model-openai` | yes | transitive | — | transitive |
| `spring-ai-starter-vector-store-pgvector` | yes | transitive | — | transitive |
| `spring-ai-starter-model-chat-memory-repository-jdbc` | yes | transitive | — | transitive |
| `spring-ai-advisors-vector-store` + `spring-ai-rag` | yes | transitive | — | transitive |
| PDF / JSoup / Tika readers | yes | transitive | — | transitive |
| `jmix-core`, `jmix-data` | yes | transitive | transitive | transitive |
| `jmix-flowui`, `jmix-flowui-data`, `jmix-flowui-themes` | no | no | yes | transitive |
| `jmix-security-flowui` | no | no | yes | transitive |
| `spring-boot-autoconfigure` | — | yes | — | yes |
| `postgresql` JDBC | runtimeOnly | transitive | — | transitive |
| `spring-ai-test` | testImplementation | — | testImplementation | — |

Rationale: keep the functional module headless so pure REST hosts can consume `ai-agent-starter` without dragging Vaadin Flow. All UI code (views, menu, `messages*.properties`, roles for UI access) lives in the Flow UI module pair.

---

## Stack Patterns by Variant

**If host runs Postgres already:** Default path — host enables `ai-agent-flowui-starter`, sets `OPENROUTER_API_KEY`, runs Liquibase (our `CREATE EXTENSION vector` + our tables), done.

**If host runs Oracle / SQL Server:** pgvector unavailable. Guidance: host overrides the `VectorStore` bean (e.g., Chroma in a sidecar container, or in-memory for small KBs). JDBC chat memory still works — Spring AI JDBC repo supports multiple dialects.

**If host does not want Flow UI:** Host pulls only `ai-agent-starter`. They lose chat/KB/audit views but retain the `ChatClient` bean, tool infrastructure, `VectorStore`, and can build their own UI (REST/React/etc.).

**If host wants to swap provider away from OpenRouter:** Two levers — (a) change `spring.ai.openai.base-url` + model IDs to another OpenAI-compatible gateway (Azure OpenAI, Groq, Together, vLLM); (b) for non-OpenAI-compatible SDKs, add a different starter (e.g., `spring-ai-starter-model-anthropic`) and override the `ChatModel` bean. Our ChatClient.builder consumes whatever `ChatModel` is wired.

---

## Version Compatibility

| Package A | Compatible With | Notes |
|---|---|---|
| `spring-ai-bom:1.1.4` | Spring Boot 3.4+ | Boot 3.x transitively from Jmix 2.8 BOM should satisfy. Verify at integration time; if Jmix 2.8 pins Boot < 3.4, raise with Jmix team. MEDIUM. |
| `spring-ai-bom:1.1.4` | Java 17, 21, 25 | Reference `traffic-law-chatbot` runs on Java 25 with `--enable-native-access=ALL-UNNAMED`. Our target is Java 17. HIGH. |
| `jmix-bom:2.8.0` | Spring Boot 3.x | Jmix 2.8 declares its Boot baseline. HIGH. |
| pgvector extension | Postgres 12+ (14+ recommended for HNSW) | `index-type: HNSW` used in config requires pgvector ≥ 0.5.0. MEDIUM. |
| `spring-ai-starter-model-openai` ↔ OpenRouter | Works via `base-url` override | Proven in `traffic-law-chatbot`. Per-request model override via `ChatOptions.builder().model(id).build()`. HIGH. |
| Milestone artifacts ↔ reproducible builds | Milestone repo may garbage-collect old milestones eventually | Plan a pin to GA as soon as Spring AI 2.0 reaches RC1/GA; document an upgrade checklist. HIGH. |

---

## Sources

- `/websites/spring_io_spring-ai_reference` (Context7, **High** reputation) — verified: artifact rename pattern `spring-ai-starter-model-{model}` / `spring-ai-starter-vector-store-{store}` / `spring-ai-starter-mcp-{type}`; pgvector autoconfig dep; chat memory repository rename to `*-chat-memory-repository-*`; `ChatClient`/advisors refactor; `ChatClientMessageAggregator` replacement for `MessageAggregator`; tool-calling API change to `toolSpecifications()` / bean-based `@Tool`; `initialize-schema` default = false.
- `/spring-projects/spring-ai` (Context7) — verified: `@Tool` + `@ToolParam` declarative usage, `ToolCallbacks.from(bean)` utility, `ChatClient.create(model).prompt().tools(new Bean()).call().content()` / `.entity(Dto.class)` fluent API.
- `/jmix-framework/jmix-context7` (Context7, **High** reputation) — verified: add-on template produces functional + starter module pair; add-on dependencies pattern `{subsystem}-starter` + `{subsystem}-flowui-starter` (Quartz, Email, Multitenancy examples confirm the Flow-UI starter split convention).
- `D:/ai/traffic-law-chatbot/build.gradle` + `application.yaml` — working reference proving `spring-ai-bom:1.1.4` resolves via `https://repo.spring.io/milestone` and that all of our chosen artifact IDs coexist; proving OpenRouter wiring through `spring.ai.openai.base-url` + per-request `ChatOptions` model override.
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/build.gradle` — working reference proving Jmix (2.7.4) + Spring AI (1.1.2) coexistence, including `jmix-flowui-*`, `spring-ai-pgvector-store`, and `spring-ai-advisors-vector-store` on the same classpath.
- `.planning/PROJECT.md` + `.planning/codebase/STACK.md` + root `CLAUDE.md` — constraints: 1.1.4 mandate, DataManager-only, no Lombok on entities, Jmix 2.8 + Java 17, `msg://` keys in ALL locales, Liquibase-managed schema.

---

## Research Gaps / Low-Confidence Items to Flag

1. **`StructuredOutputValidationAdvisor`** — named in PROJECT.md but not surfaced in Spring AI 2.x docs via Context7. Plan: during the structured-output phase, (a) search Spring AI GitHub for the class, (b) if absent, fall back to `.entity()` + explicit retry/guard advisor. LOW.
2. **Spring Boot baseline of Jmix 2.8.0 vs. Spring AI 1.1.4 minimum** — Spring AI 2.x requires Boot 3.4+. Verify `jmix-bom:2.8.0` resolves ≥ 3.4 before Phase 1 finalizes; if not, bump via explicit `spring-boot-dependencies` BOM import. MEDIUM.
3. **Exact Spring AI M4 API signature for RAG advisors** — `QuestionAnswerAdvisor` vs `RetrievalAugmentationAdvisor` builder signatures shift between milestones. Pin to a commit and write a smoke test that fails fast when M4 → M5 breaks us. MEDIUM.
4. **JDBC chat memory table DDL** — default schema is shipped by Spring AI; we need to either adopt it verbatim in our Liquibase changelog or set `initialize-schema: embedded` in a dev profile and copy what the initializer generates into a proper changelog. MEDIUM.
5. **Spring AI `spring-ai-test` API** — marked LOW confidence on API; spike it in Phase 0/1 with one semantic-equality assertion before relying on it for CI. MEDIUM.

---

*Stack research for: reusable Jmix 2.8 AI Copilot add-on on Spring AI 1.1.4*
*Researched: 2026-04-18*

> Footnote (D-10): BOM upgraded to 1.1.4 between Phase 1 wave start and Phase 2 start. BOM pinned in `ai-agent/build.gradle`. Table deltas verified in `.planning/phases/02-foundations/02-RESEARCH.md` §"Spring AI version delta". See `.planning/REQUIREMENTS.md` Scope Changes Log (D-10) and `.planning/PROJECT.md` Deferred Decisions.
