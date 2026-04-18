# Phase 1: Walking Skeleton & Packaging De-risk - Research

**Researched:** 2026-04-18
**Domain:** Jmix 2.8 add-on packaging + Spring AI 2.0.0-M4 BOM pin + OpenRouter smoke
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01 — Module structure (2-module, NOT 4-module):** Keep the existing 2-module shape (`ai-agent` functional + `ai-agent-starter`) for Phase 1. The add-on is expected to ship with UI; Jmix add-on conventions already allow UI inside the starter. Do NOT add `ai-agent-flowui` / `ai-agent-flowui-starter` in this phase. Do NOT strip `jmix-flowui-starter` / `jmix-flowui-themes` from `ai-agent/ai-agent.gradle`. A 4-module split is reconsidered only when a concrete REST-only consumer use case surfaces.
- **Consequences:** ROADMAP Phase 1 deliverable "ai-agent-flowui + ai-agent-flowui-starter modules added" is NOT executed this phase. Success criteria 1 and 3 are revised to the 2-module equivalent. PROJECT.md Key Decision #2 moves to Deferred. Update ROADMAP.md + PROJECT.md to reflect the 2-module decision as part of Phase 1 work.

**D-02 — Consumer smoke via jmix-app:** `jmix-app` doubles as the `publishToMavenLocal` consumer smoke. After `:ai-agent-starter:publishToMavenLocal`, switch `jmix-app` dep declaration from the composite `includeBuild` reference to a standard Maven dependency on `com.vn:ai-agent-starter:0.0.1-SNAPSHOT`. Boot the app, verify stub `ChatService` bean is present. Capture the toggle as a documented Gradle task or README script so it's repeatable. No separate `consumer-smoke/` sub-project, no scripted ephemeral project, no throwaway-only manual checklist.

**D-03 — ChatService future-shaped signature:**
- Interface `ChatService` exposed from `ai-agent` functional module (not starter), in a stable package; `jmix-app` depends on the interface only.
- Minimal signature (exact names planner's choice): `ChatResponse ask(String message, UUID conversationId, String userKey)` — synchronous, returns small `ChatResponse` DTO (`String content` + optional metadata map).
- `userKey` accepts null/anonymous in Phase 1; wiring to `CurrentAuthentication` is Phase 2+.
- `conversationId` recorded but not persisted yet.
- Phase 1 impl: single `DefaultChatServiceImpl` calls `ChatClient.prompt().user(message).call().content()` and wraps string into DTO. No advisors, no memory, no tools, no RAG.

**D-04 — Test strategy:**
- **Primary unit pattern:** `Mockito.mock(ChatModel.class)` — matches `jmix-ai-backend/RerankerTest.java`. No new dependency required.
- **spring-ai-test spike (research task):** Determine whether `org.springframework.ai:spring-ai-test` (version via BOM) exposes usable `MockChatModel` / evaluator surface in M4. If yes, add as `testImplementation`. If absent/unstable, stop at Mockito and note gap.
- **Live tier:** `useJUnitPlatform { excludeTags 'live' }`. `@Tag("live")` smoke test hits OpenRouter with `spring-ai-starter-model-openai` + `base-url` override. Skip cleanly when `OPENROUTER_API_KEY` absent (follow `${OPENROUTER_API_KEY:none}` pattern).
- **Reusable `FakeChatModel`:** NOT mandatory in Phase 1. Only introduce if a test needs deterministic canned responses.
- **`integrationTest` source set:** DEFER. Use plain `src/test` + `@Tag("live")`.

### Claude's Discretion
- `ChatResponse` DTO shape (fields, record-or-class, package location).
- Exact Gradle wiring: BOM + milestone repo inside the existing `ai-agent/build.gradle` `subprojects { }` block vs. per-module.
- Version matrix doc format and location (`docs/versions.md` / ADR / README section) — MUST be committed and findable.
- Exact `AutoConfiguration.imports` adjustment and `@JmixModule(dependsOn = …)` dependency list — planner reads existing `AIAutoConfiguration.java` and fills in.
- Naming of the Maven-Local consumer toggle Gradle task (e.g. `verifyMavenLocalConsumer`).
- Skip-if-missing mechanism for live tests (env-var `assumeTrue`, `@EnabledIfEnvironmentVariable`, properties-file gate) — planner picks the idiomatic JUnit 5 approach.

### Deferred Ideas (OUT OF SCOPE)
- 4-module split (`ai-agent-flowui` + `ai-agent-flowui-starter`) — revisit only when a named REST-only consumer use case justifies it.
- Stripping Vaadin deps from `ai-agent` functional module — coupled to the 4-module split.
- Full OpenRouter + profile wiring (embedding model, multi-profile `ChatOptions`, per-request model switching) — Phase 6.
- `spring-ai-test` evaluation harness usage (semantic-similarity, `RelevancyEvaluator`) — Phase 7+.
- `jmix-flowui-test-assist` adoption — no views in Phase 1.
- Rename `com.vn` / `projectId = 'AI'` — separate decision.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **PKG-01** (revised) | Add-on ships as Gradle modules under `ai-agent/` | Per D-01, Phase 1 keeps 2 modules (`ai-agent` + `ai-agent-starter`). Verified both exist and build. |
| **PKG-02** | Each starter registers auto-configuration via `META-INF/spring/.../AutoConfiguration.imports` | Existing file at `ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists `com.vn.autoconfigure.agent.AIAutoConfiguration`. Format verified; no changes needed beyond confirming it loads Spring-AI ChatClient bean graph. |
| **PKG-03** | `@JmixModule(dependsOn = {…})` on configuration classes | Existing `AIConfiguration.java` already declares `@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})`. Matches Jmix 2.8 canonical pattern verbatim. |
| **PKG-04** | Functional module contains zero Vaadin/Flow UI deps | **NOT executed in Phase 1 per D-01.** `ai-agent.gradle` keeps `jmix-flowui-starter` + `jmix-flowui-themes`. Deferred. |
| **PKG-05** | Clean-consumer smoke: `publishToMavenLocal` + fresh Jmix consumer boots with default config | Executed via `jmix-app` dep-swap (D-02). Verified that `jmix-app/build.gradle` already declares `mavenLocal()` repo and consumes `com.vn:ai-agent-starter:0.0.1-SNAPSHOT`. Toggle = remove `includeBuild 'ai-agent'` from root `settings.gradle` after publishing. |
| **TEST-01** (scaffold) | Three-tier structure: `src/test` (unit), `src/integrationTest` (SpringBoot), `@Tag("live")` excluded | Per D-04, Phase 1 scaffolds only `src/test` + `@Tag("live")`. No `integrationTest` source set this phase. |
</phase_requirements>

## Summary

Spring AI 2.0.0-M4 is available on **Maven Central** (`org.springframework.ai:spring-ai-bom:2.0.0-M4`, released 2026-03-26) — NOT on `repo.spring.io/milestone`, whose `spring-ai-bom` metadata stops at `1.1.0-M1-PLATFORM-2`. This inverts a common assumption in the project's prior research. The milestone repo is still worth adding for forward compatibility / other Spring artifacts, but Maven Central alone satisfies the 2.0.0-M4 pin. All six 2.x starter artifacts referenced by the project research (`spring-ai-starter-model-openai`, `spring-ai-starter-model-chat-memory-repository-jdbc`, `spring-ai-test`, `spring-ai-advisors-vector-store`, `spring-ai-rag`, `spring-ai-starter-vector-store-pgvector`) were confirmed present at 2.0.0-M4 via direct HTTP HEAD to Maven Central.

Jmix 2.8.0 pins `spring-boot-dependencies:3.5.11` (verified by inspecting `io/jmix/bom/jmix-bom/2.8.0/jmix-bom-2.8.0.pom`). This comfortably satisfies the Spring AI 2.0.x ≥ 3.4 Boot baseline. No Boot-version conflict resolution is required.

The existing codebase is already structurally correct for Phase 1: the 2-module shape is in place, `@JmixModule(dependsOn = …)` is already declared on `AIConfiguration`, `AutoConfiguration.imports` already registers `AIAutoConfiguration`, and `jmix-app/build.gradle` already has `mavenLocal()` + a Maven-coordinate dependency on `com.vn:ai-agent-starter`. Phase 1 work is therefore additive, not reconstructive: pin the BOM, add the milestone repo, add the OpenAI starter + `spring-ai-test`, publish the `ChatService` interface + stub impl, expose a `ChatClient.Builder` → `ChatClient` bean, wire a mock-ChatModel unit test + a `@Tag("live")` OpenRouter smoke, document the version matrix, and execute the Maven-Local toggle as a one-shot smoke.

**Primary recommendation:** Port the `traffic-law-chatbot/build.gradle` BOM pattern verbatim into `ai-agent/build.gradle` `subprojects { }` block. Add the Spring-AI starter + `spring-ai-test` in `ai-agent-starter.gradle`. Expose `ChatService` interface from `ai-agent` and `ChatService` bean + `ChatClient` bean from `AIAutoConfiguration` (starter). Use `application.yaml` in `jmix-app` for OpenRouter `base-url` override. Run `./gradlew :ai-agent-starter:publishToMavenLocal` + remove `includeBuild 'ai-agent'` from the root `settings.gradle` to execute the consumer smoke.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Spring AI BOM pin + milestone repo | Add-on root build (`ai-agent/build.gradle` `subprojects`) | — | Every module in the add-on needs the BOM-managed versions; applying at `subprojects` avoids duplication. |
| `spring-ai-starter-model-openai` classpath dep | Add-on starter (`ai-agent-starter.gradle`) | — | Starter is the plug-and-play artifact consumers import; functional module must remain LLM-provider-agnostic. |
| `ChatService` interface (SPI-shaped) | Add-on functional (`ai-agent`) | — | Consumers (including `jmix-app`) inject the interface; impl should be swappable. Per D-03. |
| `ChatService` impl (`DefaultChatServiceImpl`) | Add-on starter auto-config (`AIAutoConfiguration`) | — | Auto-config is Spring's canonical place for `@ConditionalOnMissingBean` default impls. |
| `ChatClient` bean | Add-on starter auto-config | — | Built from `ChatClient.Builder` (auto-configured by `spring-ai-starter-model-openai`). |
| OpenRouter `base-url` + API key config | Host app (`jmix-app/application.yaml`) | — | API keys and provider endpoint are host deployment concerns; add-on ships only sensible defaults / property stubs. |
| Live smoke test (`@Tag("live")`) | Add-on functional `src/test` | — | Keeps the test close to the code under test (`ChatService`). |
| Mock-ChatModel unit test | Add-on functional `src/test` | — | Same rationale; uses existing `AITestConfiguration` scaffolding. |
| `publishToMavenLocal` + consumer smoke | Add-on build + root composite `settings.gradle` | — | Composite `includeBuild` toggle + `mavenLocal()` consumption lives at project root. |
| Version matrix documentation | Add-on docs | — | Planner's choice of location (README / ADR / `docs/versions.md`); committed artifact. |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.ai:spring-ai-bom` | `2.0.0-M4` | Pin all Spring AI artifact versions transitively | [VERIFIED: Maven Central `maven-metadata.xml` — latest release is 2.0.0-M4, lastUpdated 2026-03-26] Used verbatim by `traffic-law-chatbot` reference build. |
| `org.springframework.ai:spring-ai-starter-model-openai` | via BOM (`2.0.0-M4`) | OpenAI-compatible chat starter (auto-configures `ChatModel` + `ChatClient.Builder`) | [VERIFIED: HTTP 200 on `https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-model-openai/2.0.0-M4/spring-ai-starter-model-openai-2.0.0-M4.pom`] [CITED: Context7 `/spring-projects/spring-ai` — "Add Spring AI OpenAI Starter Dependency"] Current 2.x starter ID; the old `spring-ai-openai-spring-boot-starter` is superseded. OpenRouter speaks OpenAI protocol — reuses the same starter via `base-url` override. |
| `org.springframework.ai:spring-ai-test` | via BOM | Test utilities (evaluator harness, possibly mock `ChatModel`) — presence/shape to be spiked in Phase 1 | [VERIFIED: HTTP 200 on `.../spring-ai-test/2.0.0-M4/…pom`] [ASSUMED] The specific surface (`MockChatModel`, `BasicEvaluator`, `RelevancyEvaluator`) is NOT verified in this research pass; Phase 1 D-04 explicitly marks this as a spike. If surface is absent/unstable, stop at Mockito. |
| `io.jmix.bom:jmix-bom` | `2.8.0` | Jmix platform BOM (already in place) | [VERIFIED: inspected `jmix-bom-2.8.0.pom` at `global.repo.jmix.io`] Pins `spring-boot-dependencies:3.5.11`. |
| Spring Boot (via Jmix BOM) | `3.5.11` | Runtime framework | [VERIFIED: grep of `jmix-bom-2.8.0.pom` shows `<artifactId>spring-boot-dependencies</artifactId><version>3.5.11</version>`] Satisfies Spring AI 2.0.x ≥ 3.4 baseline. |
| Java toolchain | `17` | LTS baseline Jmix + Spring Boot 3.x require | [VERIFIED: repo root `build.gradle` + CLAUDE.md] |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.mockito:mockito-core` (transitive via `spring-boot-starter-test`) | Boot-managed | Mock `ChatModel` for unit tests | Default test pattern per D-04; matches `jmix-ai-backend/RerankerTest.java`. |
| `org.hsqldb:hsqldb` | Boot-managed | `testRuntimeOnly` — in-memory DB for `@SpringBootTest` context loads | Already in place; keep. |
| `org.springframework.boot:spring-boot-starter-web` | Boot-managed | `testImplementation` in functional module | Already in place; needed by Jmix-UI stack during `@SpringBootTest`. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `spring-ai-starter-model-openai` + OpenRouter `base-url` override | Direct OpenAI (`api.openai.com`) | Direct OpenAI pins a single provider; OpenRouter provides model portability without changing starter — strictly better for Phase 1 de-risk. |
| BOM pin in root `ai-agent/build.gradle` `subprojects { }` | Per-module BOM pin in each `*.gradle` | Subprojects block is DRY and matches the existing `jmix { bomVersion = '2.8.0' }` pattern. Per-module is only justified if modules diverge on Spring AI version — no use case. |
| `repo.spring.io/milestone` + Maven Central | Only Maven Central | 2.0.0-M4 exists on Maven Central, so milestone repo is strictly optional for this pin. However, project research recommends including it for forward compatibility (next milestone could appear on repo.spring.io first). Add both; cost is one extra URL. |
| `@Tag("live")` + `excludeTags` in Gradle `test` task | Separate `integrationTest` source set | Per D-04: defer source-set split. One-tier + tag exclusion mirrors the two reference projects (`jmix-ai-backend`, `traffic-law-chatbot`). |

**Installation (template for planner):**

```gradle
// ai-agent/build.gradle  (inside subprojects { } block)

plugins {
    id 'io.spring.dependency-management' version '1.1.7' apply false  // (or existing Jmix-plugin-transitive — verify)
}

subprojects {
    apply plugin: 'io.spring.dependency-management'

    repositories {
        mavenCentral()
        maven { url = 'https://global.repo.jmix.io/repository/public' }
        maven { url = 'https://repo.spring.io/milestone' }
        maven { url = 'https://repo.spring.io/snapshot' }  // optional forward-compat
    }

    ext {
        set('springAiVersion', "2.0.0-M4")
    }

    dependencyManagement {
        imports {
            mavenBom "org.springframework.ai:spring-ai-bom:${springAiVersion}"
        }
    }
}
```

```gradle
// ai-agent/ai-agent-starter/ai-agent-starter.gradle

dependencies {
    api project(':ai-agent')

    implementation 'io.jmix.core:jmix-core'
    implementation 'io.jmix.data:jmix-data'

    implementation 'org.springframework.boot:spring-boot-autoconfigure'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'  // NEW

    testImplementation 'org.springframework.ai:spring-ai-test'              // NEW (spike)
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

```gradle
// ai-agent/ai-agent/ai-agent.gradle  (test task — add excludeTags)

test {
    useJUnitPlatform {
        excludeTags 'live'
    }
}
```

**Version verification (commands for planner to re-run before committing):**

```bash
# Confirm 2.0.0-M4 is still latest on Maven Central (re-run before commit)
curl -s https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml | grep -E '<(latest|release)>'

# Confirm Jmix 2.8.0 → Spring Boot pin (verify in version matrix doc)
curl -s https://global.repo.jmix.io/repository/public/io/jmix/bom/jmix-bom/2.8.0/jmix-bom-2.8.0.pom \
  | grep -B1 -A1 spring-boot-dependencies
```

Verified values at 2026-04-18:
- `spring-ai-bom` latest = `2.0.0-M4` (lastUpdated `20260326144636`)
- `jmix-bom:2.8.0` pins `spring-boot-dependencies:3.5.11`

## Architecture Patterns

### System Architecture Diagram

```
[ jmix-app (host) ]
        |
        | Gradle composite includeBuild (dev)
        | OR Maven coord 'com.vn:ai-agent-starter:0.0.1-SNAPSHOT' (consumer smoke)
        v
[ ai-agent-starter ]  ---(api project)---> [ ai-agent (functional) ]
        |                                          |
        | META-INF/spring/…/AutoConfiguration.imports
        |    -> com.vn.autoconfigure.agent.AIAutoConfiguration
        |                                          |
        |  @AutoConfiguration @Import(AIConfiguration.class)
        |                                          |
        |  Beans registered:                       |   Jmix @JmixModule(dependsOn = {
        |   - ChatService (interface ->            |       EclipselinkConfiguration,
        |       DefaultChatServiceImpl)            |       FlowuiConfiguration
        |   - ChatClient (built from               |   })
        |       Spring-AI-auto-configured          |
        |       ChatClient.Builder)                |   Exports:
        |                                          |   - interface com.vn.agent.ChatService
        v                                          |   - record com.vn.agent.ChatResponse
[ spring-ai-starter-model-openai                   |
    auto-config ]                                  |
        |                                          |
        | Reads spring.ai.openai.* props           |
        | (api-key, base-url, options.model)       |
        |                                          |
        v                                          v
[ OpenAI protocol client ]  ---HTTPS--->  [ OpenRouter  /  mock ChatModel (test) ]
   base-url override: https://openrouter.ai/api/v1
```

**Primary data flow (end-to-end Phase 1 ask):**

1. `jmix-app` boots -> Spring Boot discovers `AIAutoConfiguration` via `AutoConfiguration.imports` in `ai-agent-starter` jar.
2. `AIAutoConfiguration` imports `AIConfiguration` -> Jmix registers the module (via `@JmixModule`) -> component scan finds `DefaultChatServiceImpl`.
3. `spring-ai-starter-model-openai` auto-config reads `spring.ai.openai.*` from `jmix-app/application.yaml` -> builds `OpenAiChatModel` + `ChatClient.Builder` beans.
4. `DefaultChatServiceImpl` constructor-injects `ChatClient.Builder` -> builds a `ChatClient`.
5. Test / Vaadin view calls `chatService.ask("hello", convId, null)` -> `ChatClient.prompt().user(msg).call().content()` -> HTTPS call to OpenRouter (live tier) OR mocked response (unit).
6. Result wrapped in `ChatResponse` DTO, returned to caller.

**Consumer-smoke flow (D-02):**

1. `./gradlew :ai-agent-starter:publishToMavenLocal` (also publishes `:ai-agent`).
2. Edit root `settings.gradle`: comment out `includeBuild 'ai-agent'`.
3. `cd jmix-app && ./gradlew bootRun` -> Gradle resolves `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` from `mavenLocal()`.
4. App boots; `ChatService` bean injectable; smoke passes.
5. Restore `includeBuild` after verification.

### Recommended Project Structure (Phase 1 delta — ADDITIONS only)

```
ai-agent/
├── build.gradle                                       # ADD: BOM, milestone repo, dependencyManagement
├── ai-agent/                                          # (functional)
│   ├── ai-agent.gradle                                # (test { excludeTags 'live' })
│   └── src/
│       ├── main/java/com/vn/agent/
│       │   ├── AIConfiguration.java                   # (existing — already has @JmixModule; verify dependsOn list)
│       │   ├── ChatService.java                       # NEW — interface (SPI-shaped)
│       │   └── ChatResponse.java                      # NEW — record DTO
│       └── test/java/com/vn/agent/
│           ├── AITest.java                            # (existing context-loads test)
│           ├── ChatServiceMockTest.java               # NEW — Mockito.mock(ChatModel.class)
│           └── ChatServiceLiveTest.java               # NEW — @Tag("live") OpenRouter smoke
└── ai-agent-starter/
    ├── ai-agent-starter.gradle                        # ADD: spring-ai-starter-model-openai, spring-ai-test
    └── src/main/
        ├── java/com/vn/autoconfigure/agent/
        │   ├── AIAutoConfiguration.java               # ADD: @Bean ChatClient, @Bean ChatService
        │   └── DefaultChatServiceImpl.java            # NEW — impl (or keep in functional module — planner picks)
        └── resources/
            ├── META-INF/spring/…/AutoConfiguration.imports   # (existing — no change)
            └── application-ai-agent-defaults.yaml     # OPTIONAL — sensible defaults for base-url

jmix-app/
├── build.gradle                                       # (unchanged — already consumes com.vn:ai-agent-starter)
└── src/main/resources/
    └── application.yaml / application.properties     # ADD: spring.ai.openai.* OpenRouter wiring

docs/ (or README section / ADR)
└── versions.md                                        # NEW — version matrix table
```

### Pattern 1: Spring AI OpenAI starter + OpenRouter base-url override
**What:** Override `spring.ai.openai.base-url` to point the OpenAI-compatible client at OpenRouter; use the same `spring-ai-starter-model-openai` auto-config without any custom `ChatModel` bean.
**When to use:** Whenever the deployment targets OpenRouter (v1) or any OpenAI-protocol compatible provider.
**Example:**

```yaml
# jmix-app/src/main/resources/application.yaml  [CITED: traffic-law-chatbot/src/main/resources/application.yaml]
spring:
  ai:
    openai:
      api-key: ${OPENROUTER_API_KEY:none}
      base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api}
      chat:
        options:
          model: ${OPENROUTER_MODEL:openai/gpt-4o-mini}
```

Note: `traffic-law-chatbot` uses base path `https://openrouter.ai/api` (without `/v1`); Spring AI OpenAI starter appends `/v1/chat/completions` by default. [CITED: Context7 `/spring-projects/spring-ai` — perplexity-chat.adoc shows `spring.ai.openai.chat.completions-path` defaults to `/chat/completions`]. OpenRouter's documented OpenAI-compatible base is `https://openrouter.ai/api/v1`; both forms work depending on how `completions-path` is set. [ASSUMED] Planner should pick one and verify with a live smoke.

### Pattern 2: `@JmixModule(dependsOn = …)` on add-on config class
**What:** Declare Jmix subsystem dependencies on the add-on's main `@Configuration` class so Jmix orders module initialization correctly.
**When to use:** Every functional-module `@Configuration` in a Jmix add-on.
**Example:**

```java
// [CITED: Context7 /jmix-framework/jmix-context7 — creating-add-ons.html]
// [VERIFIED: existing ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java matches this pattern verbatim]
@Configuration
@ComponentScan
@ConfigurationPropertiesScan
@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})
@PropertySource(name = "com.vn.agent", value = "classpath:/com/vn/agent/module.properties")
public class AIConfiguration {
    // @Bean definitions …
}
```

**Phase 1 action:** Verify existing declaration is sufficient. `EclipselinkConfiguration` + `FlowuiConfiguration` cover data + UI; Core is transitive (per Context7 "dependency on Core subsystem is added transitively through other subsystem dependencies"). No change needed unless Phase 1 smoke reveals a missing subsystem.

### Pattern 3: Auto-configuration registration
**What:** Register the starter's auto-configuration class in Spring Boot 2.7+ `AutoConfiguration.imports` file (superseded `META-INF/spring.factories`).
**Example:**

```
# [VERIFIED: existing ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports]
com.vn.autoconfigure.agent.AIAutoConfiguration
```

One FQCN per line. Existing file is correct; no change required.

### Pattern 4: `@AutoConfiguration` @Import functional config
**What:** Starter's auto-config class imports the functional module's `@Configuration`, so consumers get all functional beans plus any starter-level defaults.

```java
// [VERIFIED: existing ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java]
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    // Phase 1: expose a default ChatService impl if none supplied by host.
    @Bean
    @ConditionalOnMissingBean
    public ChatService chatService(ChatClient chatClient) {
        return new DefaultChatServiceImpl(chatClient);
    }
}
```

Planner picks whether to define `DefaultChatServiceImpl` as a `@Component` in the functional module (component-scanned by `AIConfiguration`) or instantiate manually in auto-config via `@Bean`. Either is idiomatic; `@ConditionalOnMissingBean` is the key for swappability.

### Anti-Patterns to Avoid
- **Hardcoded Spring AI version per artifact** — always via BOM. `jmix-ai-backend/build.gradle` (reference) does it per-artifact but that's the OLD 1.1.2 world; new 2.x convention via BOM is mandatory.
- **Per-module Maven repository declarations** — consolidate in root `subprojects` to avoid drift.
- **Live-LLM calls in default test run** — always tag `@Tag("live")` + `excludeTags 'live'` in Gradle `test` task.
- **Defining a custom `ChatModel` bean in Phase 1** — let `spring-ai-starter-model-openai` auto-configure it; only override the `base-url` via properties.
- **Wiring `ChatService` to `CurrentAuthentication` / persistence** — explicitly deferred per D-03; Phase 1 stub takes `userKey` nullable and does nothing with it.
- **Adding `integrationTest` source set speculatively** — D-04 forbids.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Pinning Spring AI artifact versions | Per-artifact hardcoded versions in each `*.gradle` | `spring-ai-bom` + `dependencyManagement` import | BOM guarantees cross-artifact version compatibility; upgrading is a one-line change. |
| OpenAI HTTP client | Custom `RestTemplate` / `WebClient` wiring to OpenRouter | `spring-ai-starter-model-openai` + `base-url` override | Starter handles auth header injection, retry, error parsing, streaming, tool-call framing. |
| `ChatClient` construction | `new OpenAiChatModel(...)` in a `@Bean` method | Inject auto-configured `ChatClient.Builder` | Builder already carries default options, advisor support, retry template. |
| "Mock LLM" fake | Custom `FakeChatModel` class | `Mockito.mock(ChatModel.class)` (D-04 primary) OR `spring-ai-test` mocks if spike succeeds | Per D-04 + `jmix-ai-backend/RerankerTest.java` pattern; FakeChatModel only if test needs deterministic canned responses. |
| Consumer smoke harness | Separate `consumer-smoke/` sub-project or scripted ephemeral Jmix project | Toggle `jmix-app` between `includeBuild` and Maven coord (D-02) | `jmix-app` already depends on the Maven coord; no duplicate project to maintain. |
| Jmix module dependency declaration | Hand-rolled bean ordering with `@DependsOn` | `@JmixModule(dependsOn = {…Configuration.class})` | Jmix framework hook; participates in Jmix's module lifecycle (add-ons appearing in Studio, menu merging, etc.). |
| Starter auto-discovery | Writing `spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `spring.factories` was deprecated in Boot 2.7 and removed for auto-config registration in 3.0. |

**Key insight:** The Phase 1 add-on should contain essentially NO custom integration code — it's a BOM pin, a property override, a starter artifact, and a 20-line `@Bean` definition. Every deviation from this signals a de-risk issue worth investigating before adding architecture.

## Runtime State Inventory

_Not applicable — Phase 1 is a greenfield additive phase (no rename, refactor, migration)._

## Common Pitfalls

### Pitfall 1: Assuming spring-ai-bom:2.0.0-M4 lives on repo.spring.io/milestone
**What goes wrong:** Build fails with `Could not find spring-ai-bom-2.0.0-M4.pom` despite adding `maven { url = 'https://repo.spring.io/milestone' }`.
**Why it happens:** 2.0.0-M4 was published to **Maven Central** (released 2026-03-26), not to the milestone repo. Milestone repo's `spring-ai-bom` metadata stops at `1.1.0-M1-PLATFORM-2`.
**How to avoid:** Ensure `mavenCentral()` precedes milestone in the repo list (it does, by convention). Milestone repo remains useful for *other* Spring artifacts and for forward-compat (future milestones may land there first), but Maven Central is the authoritative source for this BOM version.
**Warning signs:** `Could not find org.springframework.ai:spring-ai-bom:2.0.0-M4` with only the milestone repo configured.

### Pitfall 2: `includeBuild` masks consumer-smoke failure
**What goes wrong:** `./gradlew bootRun` in `jmix-app` succeeds because `includeBuild 'ai-agent'` short-circuits the Maven resolve; `publishToMavenLocal` artifact might be missing metadata, wrong artifact ID, or have a bad publication config — but the developer never notices.
**Why it happens:** Gradle composite builds substitute `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` with the in-tree project silently.
**How to avoid:** D-02 toggle — explicitly remove `includeBuild 'ai-agent'` from root `settings.gradle` before the final consumer smoke. Capture as a Gradle task or README step. Re-add after verification.
**Warning signs:** `publishToMavenLocal` succeeds but never produces `ai-agent-starter-0.0.1-SNAPSHOT.jar` in `~/.m2/repository/com/vn/ai-agent-starter/0.0.1-SNAPSHOT/`; composite-build consumer never exercises that path.

### Pitfall 3: `@Tag("live")` tests leak into CI
**What goes wrong:** Default `./gradlew test` hits OpenRouter, burning API credits or failing offline.
**Why it happens:** Forgot `excludeTags 'live'` in Gradle `test` task OR used `@EnabledIfEnvironmentVariable` only (test is "enabled" but then fails because key is absent) OR test is not tagged.
**How to avoid:** Both belt and suspenders — `useJUnitPlatform { excludeTags 'live' }` in the Gradle `test` task AND `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")` on the test method. Mirrors `traffic-law-chatbot` pattern exactly.
**Warning signs:** CI run for a fresh PR hits a 401 Unauthorized on OpenRouter, or a rate-limit from CI runners.

### Pitfall 4: Spring AI OpenAI auto-config races with Jmix bootstrap
**What goes wrong:** `ChatClient.Builder` bean not available during `@JmixModule` initialization; `NoSuchBeanDefinitionException` at startup.
**Why it happens:** Custom `@Bean` methods that depend on `ChatClient.Builder` run before the Spring AI auto-config; Jmix module order + auto-config order interact.
**How to avoid:** Use constructor injection in impls (`DefaultChatServiceImpl`), not field injection; don't instantiate `ChatClient` in static init or `@PostConstruct` of Jmix-lifecycle beans. Let Spring handle bean resolution order naturally. If needed, declare `@ConditionalOnBean(ChatClient.Builder.class)` on the `ChatService` bean.
**Warning signs:** `Parameter 0 of constructor required a bean of type 'org.springframework.ai.chat.client.ChatClient$Builder' that could not be found.`

### Pitfall 5: `AutoConfiguration.imports` file encoding / newline
**What goes wrong:** Starter loads in dev (composite build) but silently fails in consumer smoke because the file is UTF-16 / has BOM / missing trailing newline.
**Why it happens:** Windows editors introduce BOMs; some tools trim final newline.
**How to avoid:** Ensure the file is UTF-8 without BOM, one FQCN per line, trailing newline. The existing file is correct; verify after any edit.
**Warning signs:** In the consumer-smoke boot, `AIAutoConfiguration` never runs → `ChatService` bean missing → `NoSuchBeanDefinitionException` on host injection.

### Pitfall 6: OpenRouter `base-url` path confusion
**What goes wrong:** 404 or "model not found" from OpenRouter when the `base-url` path mismatches Spring AI's assumed `completions-path`.
**Why it happens:** Spring AI appends `/v1/chat/completions` (or whatever `spring.ai.openai.chat.completions-path` is set to — default `/v1/chat/completions`) to `base-url`. `traffic-law-chatbot` uses `https://openrouter.ai/api` (trailing slash omitted, no `/v1`); OpenRouter's docs show `https://openrouter.ai/api/v1/chat/completions` as the full URL. Both can work depending on whether Spring AI's `completions-path` default is `/v1/chat/completions` or `/chat/completions`. [ASSUMED] Exact default needs verification at smoke time.
**How to avoid:** Planner should verify via a direct `curl` against OpenRouter with the chosen `base-url` + `completions-path` combo, then pick one. Document the chosen combo in the version matrix.
**Warning signs:** HTTP 404 from OpenRouter; Spring AI wraps as `NonTransientAiException`.

### Pitfall 7: Jmix flowui dep missing from functional module breaks test boot
**What goes wrong:** Removing `jmix-flowui-starter` / `jmix-flowui-themes` (speculative PKG-04 work) causes `AIConfiguration` to fail — it references `FlowuiConfiguration.class` in `@JmixModule(dependsOn = …)`.
**Why it happens:** The existing `@JmixModule` declaration compiles against `FlowuiConfiguration`; removing the flowui dep removes the class from the classpath.
**How to avoid:** Per D-01, this is explicitly deferred. Leave flowui deps in place in Phase 1. If a future phase does strip flowui, `@JmixModule(dependsOn = …)` must drop `FlowuiConfiguration` at the same time.
**Warning signs:** Compilation error `cannot find symbol FlowuiConfiguration`; or runtime `ClassNotFoundException` in a fresh consumer build that excluded flowui.

## Code Examples

### ChatService interface (in `ai-agent` functional module)

```java
// Source: D-03 (CONTEXT.md); planner picks exact shape/names/package.
package com.vn.agent;

import java.util.Map;
import java.util.UUID;

public interface ChatService {
    ChatResponse ask(String message, UUID conversationId, String userKey);

    record ChatResponse(String content, Map<String, Object> metadata) {}
}
```

### DefaultChatServiceImpl (skeleton)

```java
// [CITED: Context7 /spring-projects/spring-ai — ChatClient reference]
package com.vn.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class DefaultChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    public DefaultChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponse ask(String message, UUID conversationId, String userKey) {
        String content = chatClient.prompt().user(message).call().content();
        return new ChatResponse(content, Map.of("conversationId", String.valueOf(conversationId)));
    }
}
```

### Mock-ChatModel unit test

```java
// [CITED: jmix-ai-backend/src/test/java/io/jmix/ai/backend/retrieval/RerankerTest.java — pattern]
package com.vn.agent;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse as SpringChatResponse;
// … imports elided
import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceMockTest {

    @Test
    void askReturnsMockContent() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);
        // stub mockModel.call(Prompt) to return a SpringChatResponse with "hello from mock"
        // … (planner fills in exact stubbing — depends on ChatClient.Builder construction path)

        ChatClient.Builder builder = ChatClient.builder(mockModel);
        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(builder);

        ChatService.ChatResponse r = svc.ask("hi", java.util.UUID.randomUUID(), null);

        assertThat(r.content()).isEqualTo("hello from mock");
    }
}
```

### Live smoke test (`@Tag("live")`)

```java
// [CITED: traffic-law-chatbot test pattern + D-04]
package com.vn.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveTest {

    @Autowired ChatService chatService;

    @Test
    void openRouterSmoke() {
        ChatService.ChatResponse r = chatService.ask(
                "Reply with exactly the word OK.",
                java.util.UUID.randomUUID(),
                null);
        assertThat(r.content()).isNotBlank();
    }
}
```

### Gradle test tag exclusion

```gradle
// [CITED: traffic-law-chatbot/build.gradle]
test {
    useJUnitPlatform {
        excludeTags 'live'
    }
}
```

### application.yaml for jmix-app (OpenRouter)

```yaml
# Source: traffic-law-chatbot/src/main/resources/application.yaml (lifted for Phase 1 minimum)
spring:
  ai:
    openai:
      api-key: ${OPENROUTER_API_KEY:none}
      base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}
      chat:
        options:
          model: ${OPENROUTER_MODEL:openai/gpt-4o-mini}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` | Spring AI 2.0.x naming convention | Old artifact coords will not resolve at 2.0.0-M4. Use current ID. |
| Hardcoded Spring AI artifact versions (e.g. `$springAiVersion` per-artifact) | `spring-ai-bom` + `dependencyManagement` imports | Spring AI 1.0.x → 2.0.x | BOM is the mandatory pattern now. `jmix-ai-backend` is stuck on old per-artifact 1.1.2 — not the pattern to follow for Phase 1. |
| `META-INF/spring.factories` for auto-config | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot 2.7 (deprecated) → 3.0 (removed) | Already applied in this codebase. |
| `repo.spring.io/milestone` as authoritative milestone source | Maven Central for Spring AI 2.0.0-M4 | Spring AI 2.0.0-M4 release (2026-03-26) | Expectation flip: include `mavenCentral()` first; milestone repo is forward-compat insurance only. |

**Deprecated/outdated:**
- `@EnableAutoConfiguration` meta-annotation on starter config — replaced by `@AutoConfiguration` (Boot 3.x convention; already applied in `AIAutoConfiguration.java`).
- `ChatClient` construction via `new OpenAiChatClient(...)` — use auto-configured `ChatClient.Builder`.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `spring-ai-test:2.0.0-M4` exposes a stable `MockChatModel` / evaluator surface usable for Phase 1 mock tests | Standard Stack; D-04 | LOW — D-04 already marks this as a spike; fallback is Mockito-only (primary path). |
| A2 | OpenRouter `base-url` = `https://openrouter.ai/api/v1` (with `/v1` segment) is the correct combo with Spring AI's default `spring.ai.openai.chat.completions-path` at 2.0.0-M4 | Pattern 1; Pitfall 6 | MEDIUM — `traffic-law-chatbot` uses `https://openrouter.ai/api` (no `/v1`). Planner MUST verify via a manual `curl` or short smoke before committing the version matrix. |
| A3 | `ChatClient.Builder` bean name and shape at 2.0.0-M4 unchanged from 1.x (`ChatClient.builder(ChatModel)` + auto-configured `ChatClient.Builder` bean from starter) | Code Examples, Architecture | LOW — highly standard API; confirmed by multiple Context7 snippets though none dated to 2.0.0-M4 specifically. Smoke test will catch any drift. |
| A4 | Existing `@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})` declaration is sufficient for Phase 1 — no missing subsystems | Pattern 2 | LOW — matches Context7 canonical example verbatim; Core is transitive. Will be validated by the `@SpringBootTest` context-loads check. |
| A5 | `./gradlew :ai-agent-starter:publishToMavenLocal` produces a POM that correctly pulls `:ai-agent` as transitive dep when consumed as Maven coord | D-02 consumer smoke | MEDIUM — existing `publishing { publications { javaMaven(MavenPublication) { from components.java } } }` in `ai-agent/build.gradle` subprojects block should work, but has never been end-to-end exercised. D-02 flow itself is the validator. |

## Open Questions (RESOLVED)

1. **Does `spring-ai-test:2.0.0-M4` provide a usable mock / evaluator surface?**
   - What we know: artifact exists (HTTP 200 at Maven Central), project research calls it MEDIUM confidence.
   - What's unclear: specific classes (`MockChatModel`? `RelevancyEvaluator`? `BasicEvaluator`?) and whether they're stable at M4.
   - Recommendation: Phase 1 task = open the jar, list `@PublicApi` / non-internal classes, decide in ≤ 30 minutes. If empty, skip and note. Defer any evaluator use to Phase 7 (per Deferred list).
   - **RESOLVED:** Deferred to a time-boxed spike in Plan 03 Task 2. If the spring-ai-test surface is empty or unstable at M4, fall back to the Mockito-only path (D-04 primary). spring-ai-test remains on the test classpath via Plan 01 (no cost), usage only materialises if the spike succeeds. Evaluator usage (`RelevancyEvaluator` etc.) stays deferred to Phase 7 per the Deferred list.

2. **What is the exact OpenRouter-compatible `base-url` + `completions-path` combo at Spring AI 2.0.0-M4?**
   - What we know: `traffic-law-chatbot` uses `https://openrouter.ai/api`; OpenRouter's public docs show `/v1/chat/completions` as the endpoint.
   - What's unclear: whether Spring AI 2.0.0-M4's default `completions-path` is `/v1/chat/completions` or `/chat/completions`.
   - Recommendation: Planner includes a 5-minute manual `curl` smoke step in the live-test task: try both combos with the same API key, pick the one that returns 200.
   - **RESOLVED:** Deferred to Plan 03 Task 2 (`@Tag("live")` smoke). Default committed in Plan 01 Task 3 is `https://openrouter.ai/api/v1` with Spring AI's default `completions-path`. Documented fallback: if the live smoke returns 404, flip the env-var default to `https://openrouter.ai/api` (matching `traffic-law-chatbot`) and re-run. Chosen combo recorded in the version-matrix doc (Plan 04).

3. **Should `DefaultChatServiceImpl` live in `ai-agent` (functional) or `ai-agent-starter` (auto-config)?**
   - What we know: interface MUST be in functional (D-03). Impl location is flexible.
   - Tradeoff: impl-in-functional = component-scanned automatically, simpler; impl-in-starter auto-config = cleaner `@ConditionalOnMissingBean` swap point.
   - Recommendation: Start with impl as `@Service` in functional (com.vn.agent package, same as interface); upgrade to auto-config `@Bean` with `@ConditionalOnMissingBean` only when a second impl is introduced (Phase 3+).
   - **RESOLVED:** `@Service` in the functional module (`com.vn.agent.DefaultChatServiceImpl`), component-scanned by existing `AIConfiguration`'s `@ComponentScan`. `AIAutoConfiguration` only exposes the `ChatClient` `@Bean` (with `@ConditionalOnMissingBean`); it does NOT register a `ChatService` `@Bean` (would collide with the component-scanned `@Service`). Promotion to auto-config `@Bean` + `@ConditionalOnMissingBean` is deferred to Phase 3+ when a second impl arrives. Locked in Plan 02 Task 2 + Task 3.

4. **Does `publishToMavenLocal` publish both `ai-agent` AND `ai-agent-starter`, or only `ai-agent-starter`?**
   - What we know: `subprojects { apply plugin: 'maven-publish' }` applies to both; both should publish.
   - What's unclear: whether `./gradlew :ai-agent-starter:publishToMavenLocal` transitively triggers the `:ai-agent` publication, or whether we need `./gradlew publishToMavenLocal` (root task).
   - Recommendation: Use the root `./gradlew publishToMavenLocal` (publishes all) in the documented task to be safe.
   - **RESOLVED:** Root-level `./gradlew publishToMavenLocal` task per D-02 (publishes ALL subprojects in one invocation). Plan 04 Task 1 uses the root task exclusively; the per-module form `:ai-agent-starter:publishToMavenLocal` is explicitly NOT the documented path. This guarantees both `com.vn:ai-agent:0.0.1-SNAPSHOT` and `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` land in `~/.m2/repository/` before the consumer smoke toggle.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | Jmix 2.8 + Spring Boot 3.5.11 | ✓ (assumed, established stack) | 17+ | — |
| Gradle wrapper | Add-on + jmix-app builds | ✓ | 8.14.4 (per Jmix 2.8 upgrade note [CITED: Context7 jmix-context7 what's-new]) | — |
| Maven Central (network) | BOM + all Spring AI artifacts at 2.0.0-M4 | ✓ (verified via HTTP HEAD 200) | — | — |
| `global.repo.jmix.io` | Jmix BOM + artifacts | ✓ | — | — |
| `repo.spring.io/milestone` | Forward-compat for future Spring AI milestones | ✓ (reachable) | — | Can omit if only sticking at 2.0.0-M4 (Central has it). |
| `~/.m2/repository` write access | `publishToMavenLocal` | ✓ (standard dev env) | — | — |
| `OPENROUTER_API_KEY` env var | `@Tag("live")` smoke test | ✗ (per-developer) | — | Skip test via `@EnabledIfEnvironmentVariable` — mandatory; CI MUST pass without the key. |
| HSQLDB (test runtime) | `@SpringBootTest` in-memory DB | ✓ (already declared) | Boot-managed | — |

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:**
- `OPENROUTER_API_KEY` → skip `@Tag("live")` test cleanly; unit tests carry the wiring proof via mock `ChatModel`.

## Security Domain

> Workflow config (`workflow.security_enforcement`) is not explicitly set in `.planning/config.json`. Treating as enabled. Phase 1 is a skeleton / wiring phase with limited attack surface, but the following applies.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (Phase 1 stub accepts null `userKey`; real auth is Phase 2+) | — |
| V3 Session Management | no (no session state in skeleton) | — |
| V4 Access Control | no (no authorization decisions) | — |
| V5 Input Validation | partial | User `message` string passes through `ChatClient.prompt().user(...)` — Spring AI handles prompt serialization. Phase 1 adds no validation; downstream Phase 3 (tool generator) hardens via `<data>` delimiters. |
| V6 Cryptography | partial | TLS for OpenRouter (HTTPS enforced by URL); API key in env var (`OPENROUTER_API_KEY`) never committed. |
| V7 Error Handling | yes | Spring AI errors wrapped in `NonTransientAiException` / `TransientAiException`; default Spring Boot handling OK. Don't log full prompt/response (PII). |
| V9 Communication | yes | HTTPS-only `base-url`; no plaintext fallback. |
| V14 Configuration | yes | `api-key: ${OPENROUTER_API_KEY:none}` pattern — fallback is literal string "none", which OpenRouter rejects with 401. Safe default. |

### Known Threat Patterns for {Jmix 2.8 + Spring AI 2.0.0-M4 + OpenRouter}

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| API key leaked in logs / stack traces | Information disclosure | Do NOT log `spring.ai.openai.*` properties; rely on Spring Boot's standard sensitive-property masking. |
| API key committed to git | Information disclosure | `${OPENROUTER_API_KEY:none}` env-var indirection; `.gitignore` any `.env*` files. |
| Prompt injection via user message | Tampering | DEFERRED to Phase 3 (tools) and Phase 6 (output-side scanner). Phase 1 smoke sends fixed strings only. |
| Unbounded token consumption | DoS / cost exhaustion | DEFERRED to Phase 6 (GUARD-03 circuit breaker). Phase 1 smoke issues single prompt. |
| Live-LLM tests leaking API credits on every CI run | DoS / cost | `excludeTags 'live'` in default Gradle `test` + `@EnabledIfEnvironmentVariable`. |
| Malicious transitive dep via Spring milestone repo | Supply chain (Tampering) | `spring-ai-bom` pin + `mavenCentral()` FIRST in repo order (Gradle prefers first-found). BOM-managed versions for all Spring AI artifacts. |

## Project Constraints (from CLAUDE.md)

Directives extracted from `./CLAUDE.md` that Phase 1 plans MUST honor:

- **Tech stack:** Java 17, Jmix 2.8 (Spring Boot 3, Vaadin Flow UI), relational DB, Gradle.
- **Module layout:** Standard Gradle. Java in `src/main/java`, resources in `src/main/resources`.
- **Entity rules (NOT exercised in Phase 1 but applied if any entity shows up):** `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`, no Lombok, instantiate via `Metadata.create()` / `DataManager.create()` (not constructor).
- **Service rules:** Constructor injection only.
- **Data access:** `DataManager` only (NOT `EntityManager`).
- **Forbidden:** Lombok on entities, `EntityManager`, business logic in views, hardcoded UI text, single-locale messages, edits in `frontend/generated/`.
- **UI text:** All labels/titles/buttons use `msg://` keys across ALL locale files. Phase 1 does NOT add UI, but any property descriptions / messages added for `ChatService` must comply if surfaced.
- **Workflow:** After writing code, use JetBrains MCP `get_file_problems("path/to/file.ext", onlyErrors=false)` on each modified file when available; then run `./gradlew test`.
- **Use Context7 `jmix-framework/jmix-context7`** for Jmix reference info — already done in this research.
- **Use Context7 `spring-projects/spring-ai`** via user global instructions — partially done; open questions remain (see Open Questions).

## Sources

### Primary (HIGH confidence)
- Context7 `/spring-projects/spring-ai` — OpenAI starter setup, `ChatClient` API (`ChatClient.Builder`, `prompt().user().call().content()`), `application.properties` shape for Perplexity (template for OpenRouter).
- Context7 `/jmix-framework/jmix-context7` — `@JmixModule(dependsOn = {…})` canonical example, add-on module structure, Jmix 2.8 upgrade notes (Gradle 8.14.4, IntelliJ 2025.3).
- Maven Central metadata:
  - `https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml` — confirms `2.0.0-M4` is latest release (lastUpdated `20260326144636`).
  - HTTP HEAD 200 on each starter pom at 2.0.0-M4 (`spring-ai-starter-model-openai`, `spring-ai-starter-model-chat-memory-repository-jdbc`, `spring-ai-test`, `spring-ai-advisors-vector-store`, `spring-ai-rag`, `spring-ai-starter-vector-store-pgvector`).
- Jmix BOM POM: `https://global.repo.jmix.io/repository/public/io/jmix/bom/jmix-bom/2.8.0/jmix-bom-2.8.0.pom` — confirms `spring-boot-dependencies:3.5.11`.
- Reference builds (in-project canonical refs): `D:/ai/traffic-law-chatbot/build.gradle` + `application.yaml`; `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/build.gradle`.
- Existing code (verified in this pass):
  - `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\AIConfiguration.java`
  - `D:\DTH\ai-agent-core\ai-agent\ai-agent-starter\src\main\java\com\vn\autoconfigure\agent\AIAutoConfiguration.java`
  - `D:\DTH\ai-agent-core\ai-agent\ai-agent-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `D:\DTH\ai-agent-core\jmix-app\build.gradle`
  - `D:\DTH\ai-agent-core\ai-agent\build.gradle`

### Secondary (MEDIUM confidence)
- Project research `SUMMARY.md` / `STACK.md` (prior phase 0 research) — high-level stack consensus.
- Spring Boot 3.5.11 as the Boot pin — known to satisfy Spring AI 2.0.x ≥ 3.4 baseline; not independently cross-verified against Spring AI 2.0.0-M4 release notes.

### Tertiary (LOW confidence)
- `spring-ai-test:2.0.0-M4` public surface — artifact exists, internal structure not inspected. Flagged as Assumption A1, marked Phase 1 spike.
- Exact OpenRouter `base-url` path combination at 2.0.0-M4 — flagged as Assumption A2.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every artifact + version verified at Maven Central via HTTP; Jmix Boot pin verified by POM inspection.
- Architecture: HIGH — canonical Jmix add-on pattern matches existing code verbatim; Spring AI auto-config is documented and standard.
- Pitfalls: HIGH — specific to this project (e.g., `includeBuild` masking, milestone-repo-vs-Central expectation flip) discovered during research.
- Assumptions: Two MEDIUM-risk assumptions (A1 spring-ai-test surface, A2 OpenRouter base-url path) — both have fallbacks.

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (30 days — pre-2.x-GA milestone; re-verify BOM version and starter IDs if planning slips beyond 30 days).

---

## RESEARCH COMPLETE

**Phase:** 01 — Walking Skeleton & Packaging De-risk
**Confidence:** HIGH

### Key Findings
- `spring-ai-bom:2.0.0-M4` is available on **Maven Central** (not `repo.spring.io/milestone` — that repo's `spring-ai-bom` metadata stops at `1.1.0-M1-PLATFORM-2`). Include `mavenCentral()` first; add milestone repo for forward-compat only.
- Jmix 2.8.0 pins `spring-boot-dependencies:3.5.11` (verified by POM inspection) — comfortably ≥ 3.4 required by Spring AI 2.0.x. No Boot version conflict.
- All six 2.x starter artifacts (`spring-ai-starter-model-openai`, `-model-chat-memory-repository-jdbc`, `-test`, `-advisors-vector-store`, `-rag`, `-starter-vector-store-pgvector`) confirmed at 2.0.0-M4 on Maven Central via HTTP HEAD 200.
- Existing codebase is structurally ready: 2-module shape in place, `@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})` already declared, `AutoConfiguration.imports` already registers `AIAutoConfiguration`, `jmix-app` already consumes `com.vn:ai-agent-starter` via Maven coord with `mavenLocal()` in its repo list. Phase 1 work is additive.
- Two open assumptions flagged: A1 (spring-ai-test surface — a D-04 spike) and A2 (exact OpenRouter `base-url` path combo — a 5-minute manual curl at smoke time). Both have clean fallbacks.

### File Created
`D:\DTH\ai-agent-core\.planning\phases\01-walking-skeleton\01-RESEARCH.md`

### Confidence Assessment
| Area | Level | Reason |
|------|-------|--------|
| Standard Stack | HIGH | All versions/coords verified via Maven Central HTTP HEAD + BOM metadata. |
| Architecture | HIGH | Canonical Jmix add-on patterns match existing code; Spring AI auto-config is documented. |
| Pitfalls | HIGH | Project-specific pitfalls (`includeBuild` masking D-02 toggle, Maven-Central-vs-milestone expectation flip) concretely documented. |
| Open Assumptions | MEDIUM | Two assumptions (spring-ai-test surface, OpenRouter base-url path). Both have fallbacks; both are in-scope planner tasks. |

### Open Questions (RESOLVED — see "Open Questions (RESOLVED)" section above)
1. `spring-ai-test:2.0.0-M4` public surface — **RESOLVED:** deferred as time-boxed spike in Plan 03 Task 2 with Mockito fallback (D-04 primary).
2. OpenRouter `base-url` + `completions-path` combo — **RESOLVED:** default `https://openrouter.ai/api/v1` in Plan 01 Task 3; live-smoke validation in Plan 03 Task 2 with documented fallback to `https://openrouter.ai/api`.
3. `DefaultChatServiceImpl` placement — **RESOLVED:** `@Service` in functional module, component-scanned by `AIConfiguration`; `AIAutoConfiguration` exposes only the `ChatClient` `@Bean`. Locked in Plan 02.
4. `publishToMavenLocal` scope — **RESOLVED:** root-level `./gradlew publishToMavenLocal` per D-02, used exclusively in Plan 04 Task 1.

### Ready for Planning
Research complete. Planner can now create the Phase 1 PLAN with confidence in BOM coords, Boot baseline, module shape, and known pitfalls.
