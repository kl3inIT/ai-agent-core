# Coding Conventions

**Analysis Date:** 2026-04-24

## Naming Patterns

**Files:**
- Use Java `PascalCase.java` for classes and records under `ai-agent/ai-agent/src/main/java/com/vn/agent/**` and `jmix-app/src/main/java/com/vn/jmixapp/**`.
- Use Jmix XML descriptor names in kebab-case matching the view controller, such as `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml` and `jmix-app/src/main/resources/com/vn/jmixapp/view/order/order-detail-view.xml`.
- Use numbered Liquibase changelogs under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/**` and `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/**`.
- Use `*Test.java` for all JUnit tests under `ai-agent/ai-agent/src/test/java/**` and `jmix-app/src/test/java/**`.

**Functions:**
- Use lower camelCase method names with explicit intent, for example `forCurrentUser()` in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` and `currentUserIsAdmin()` in `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java`.
- Test methods use behavior names without underscores, such as `perRequestAssemblyIncludesBuiltInsAndHostContributor()` in `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java`.
- Spring `@Bean` methods describe the bean contract, as in auto-configuration classes under `ai-agent/ai-agent-starter/src/main/java/com/vn/agent/autoconfigure/**`.

**Variables:**
- Use full, non-abbreviated identifiers: `metaClass`, `metaProperty`, `datatype`, `currentAuthentication`, `conversationGateway`, and `userEditableIndex` are preferred over short forms.
- Short loop variables such as `i` and exception variables such as `e` are acceptable only in narrow local scopes.
- Test setup variables should reveal domain intent, for example `orderNumber`, `loadedUser`, and `systemAuthenticator` in `jmix-app/src/test/java/**`.

**Types:**
- Jmix entities use `Ai*` names in the add-on (`AiConversation`, `AiMessage`, `AiKnowledgeDocument`) under `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/**`.
- DTOs and records use explicit suffixes like `Dto`, `Body`, `Request`, `Response`, or `Event`, for example `ChatResponseDto.java`, `AiParametersBody.java`, and `StreamingEvent.java`.
- SPI interfaces live under `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/**` and describe extension points such as tool contributors, audit listeners, and prompt contributors.
- Security roles use role interface names ending in `Role`, such as `AiAgentUserRole.java`, `AiAgentAdminRole.java`, and `SampleDataRole.java`.

## Code Style

**Formatting:**
- Java source uses 4-space indentation and standard Java brace style throughout `ai-agent/ai-agent/src/main/java/**` and `jmix-app/src/main/java/**`.
- Imports are explicit for application/Jmix/Spring classes; wildcard imports appear in some entity files for `jakarta.persistence.*`, so keep new imports explicit unless the surrounding file already uses a wildcard group.
- No repository-level `.editorconfig`, Checkstyle, PMD, Spotless, or formatter config is detected; match surrounding file formatting instead of introducing a new formatter.
- Gradle files use Groovy DSL and single-quoted dependency coordinates in `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, and `jmix-app/build.gradle`.

**Linting:**
- No dedicated lint task or static analysis plugin is detected in `build.gradle`, `ai-agent/build.gradle`, or `jmix-app/build.gradle`.
- Use JetBrains inspections as the primary local lint signal after Java changes, especially `mcp__jetbrains__get_file_problems` on touched Java/XML files.
- Treat inspection fixes as required for real bugs, missing nullability on overrides in `@NonNullApi` packages, diamond operators, broken Javadoc links, and modern collection idioms already accepted by the project.

## Import Organization

**Order:**
1. Static imports first in tests, for example AssertJ imports in `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java`.
2. Project imports such as `com.vn.agent.*` or `com.vn.jmixapp.*`.
3. Jmix, Vaadin, Spring, Spring AI, Jackson, and Jakarta imports.
4. Java standard library imports.

**Path Aliases:**
- Java package roots are `com.vn.agent` for the add-on module and `com.vn.jmixapp` for the sample host application.
- Resource packages mirror Java packages: `ai-agent/ai-agent/src/main/resources/com/vn/agent/**` and `jmix-app/src/main/resources/com/vn/jmixapp/**`.
- No TypeScript-style aliases are detected; do not assume frontend aliasing outside generated Vaadin files.

## Error Handling

**Patterns:**
- User-facing tool failures are represented with explicit exceptions and safe JSON results, especially under `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/**` and `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/**`.
- Guardrail failures use domain exceptions such as `IterationCapExceededException`, `RateLimitExceededException`, `TokenBudgetExhaustedException`, and `StructuredOutputException` in `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/**`.
- Conversation lookup failures use explicit exceptions such as `ConversationNotFoundException.java` in `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationNotFoundException.java`.
- Jmix data access should go through `DataManager` and the current security context; do not introduce `EntityManager` for application logic.

## Logging

**Framework:** SLF4J

**Patterns:**
- Use `LoggerFactory.getLogger(CurrentClass.class)` for production logging, as seen in `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java`.
- Log lifecycle, audit, streaming, and guardrail events where the message supports debugging without leaking prompt contents or secrets.
- Avoid `System.out` in production code; Gradle lifecycle logging is confined to build scripts such as `ai-agent/ai-agent/ai-agent.gradle`.

## Comments

**When to Comment:**
- Add comments for non-obvious dependency scope, verification gates, and intentional deviations, as seen in `ai-agent/ai-agent/ai-agent.gradle` around Spring AI, RAG, eval, and integration test tasks.
- Keep business logic comments concise and focused on invariants, security boundaries, or compatibility constraints.
- Do not add comments that restate obvious Java control flow.

**JSDoc/TSDoc:**
- Use JavaDoc on public SPI contracts and tests that encode phase decisions, for example `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java`.
- JavaDoc can reference project decisions or phase contracts when tests are executable documentation.
- No TSDoc convention is applicable; frontend code is primarily Vaadin/Jmix-generated resources.

## Function Design

**Size:** Keep functions focused on one orchestration or domain operation; move reusable business logic into services under `ai-agent/ai-agent/src/main/java/com/vn/agent/**` or `jmix-app/src/main/java/com/vn/jmixapp/service/**` instead of view controllers.

**Parameters:** Prefer strongly typed parameters and records for boundary models; use strict literal coercion at LLM tool boundaries under `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/**` and `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/**`.

**Return Values:** Return domain DTOs/records or safe serialized tool results; avoid returning mutable internal state from services and tool-surface classes.

## Module Design

**Exports:**
- Add-on runtime code lives in `ai-agent/ai-agent/src/main/java/com/vn/agent/**`; auto-configuration lives in `ai-agent/ai-agent-starter/src/main/java/com/vn/agent/autoconfigure/**`.
- Public SPIs live in `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/**`; only add SPI when host-specific behavior is required.
- Host sample code lives in `jmix-app/src/main/java/com/vn/jmixapp/**` and should remain sample/consumer-facing.

**Barrel Files:**
- Java has no barrel-file pattern; package-level organization is the primary navigation mechanism.
- Spring auto-configuration registration uses `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` where applicable in the starter module.

## Jmix Practices

**Entities:**
- Use `@JmixEntity`, UUID id with `@JmixGeneratedValue`, `@Version`, and `@InstanceName` in entities such as `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java`.
- Use `@Composition` for parent-child aggregates, for example conversation/message relationships in `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java` and `AiMessage.java`.
- Instantiate entities with `Metadata.create()` or `DataManager.create()` in tests and services; do not call entity constructors directly.
- Do not use Lombok on Jmix entities.

**Views:**
- Use XML descriptors for layouts, data containers, actions, filters, and bindings under `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/**` and `jmix-app/src/main/resources/com/vn/jmixapp/view/**`.
- Java view controllers should orchestrate lifecycle, events, navigation, validation, and glue only; keep business logic out of `*View.java`.
- Wire events with official Jmix annotations such as `@Subscribe`, `@Install`, and `@Supply`; avoid raw Vaadin listener registration when Jmix event wiring can express the same behavior.
- Use `msg://` keys for labels, titles, placeholders, and button text; add keys to all locale bundles such as `messages_en.properties` and `messages_vi.properties`.

**Security:**
- Define resource roles as interfaces under `ai-agent/ai-agent/src/main/java/com/vn/agent/security/**` or `jmix-app/src/main/java/com/vn/jmixapp/security/**`.
- Use Jmix role annotations such as `@ResourceRole`, `@EntityPolicy`, `@EntityAttributePolicy`, `@ViewPolicy`, and `@MenuPolicy`.
- Treat AI as another Jmix client: rely on `AccessManager`, `DataManager`, row-level roles, and the current user's security context.

## Validation Workflow

**Required Checks:**
- After meaningful Java/XML changes, run JetBrains file inspections for touched files with `mcp__jetbrains__get_file_problems(filePath, onlyErrors=false)`.
- Run targeted Gradle tests first, then broader module tests when behavior changes.
- Use `./gradlew :ai-agent:ai-agent:test` for add-on unit/integration-smoke coverage and `./gradlew :jmix-app:test` for host app coverage.
- Use `./gradlew :ai-agent:ai-agent:integrationTest` only when Docker/Testcontainers are available.
- Use `./gradlew :ai-agent:ai-agent:evalTest` for evaluation rubrics and `./gradlew :ai-agent:ai-agent:liveTest` only when `OPENROUTER_API_KEY` is available.

---

*Convention analysis: 2026-04-24*
