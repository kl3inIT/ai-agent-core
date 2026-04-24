# Testing Patterns

**Analysis Date:** 2026-04-24

## Test Framework

**Runner:**
- JUnit Jupiter via Gradle `Test` tasks.
- Config: `ai-agent/ai-agent/ai-agent.gradle`, `jmix-app/build.gradle`.
- Default add-on test task excludes `@Tag("live")`, `@Tag("rag-it")`, and `@Tag("eval")` in `ai-agent/ai-agent/ai-agent.gradle`.

**Assertion Library:**
- AssertJ from `spring-boot-starter-test`, used with `assertThat(...)` in tests such as `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java`.
- JUnit assertions are used where appropriate, but AssertJ is the dominant readability pattern.

**Run Commands:**
```bash
./gradlew test                                      # Run all default tests across composite builds
./gradlew :ai-agent:ai-agent:test                  # Run add-on default tests
./gradlew :jmix-app:test                           # Run host sample app tests
./gradlew :ai-agent:ai-agent:evalTest              # Run @Tag("eval") rubric tests
./gradlew :ai-agent:ai-agent:integrationTest       # Run @Tag("rag-it") tests; requires Docker/Testcontainers
./gradlew :ai-agent:ai-agent:liveTest              # Run @Tag("live") tests; requires OPENROUTER_API_KEY
./gradlew :ai-agent:ai-agent:check                 # Run default checks and Docker-gated integrationTest when Docker is available
```

## Test File Organization

**Location:**
- Add-on tests are under `ai-agent/ai-agent/src/test/java/com/vn/agent/**` and reuse `src/test/resources/**` fixtures.
- Host sample tests are under `jmix-app/src/test/java/com/vn/jmixapp/**` with test profile configuration in `jmix-app/src/test/resources/application-test.properties`.
- Evaluation fixtures live under `ai-agent/ai-agent/src/test/resources/eval/**` and are executed by tests tagged `@Tag("eval")`.

**Naming:**
- Test classes end with `Test`, for example `FoundationsBootSmokeTest.java`, `ConversationGatewayTest.java`, and `UserUiTest.java`.
- Test methods use behavior-oriented names such as `findRecordsOrderRoundTrip()` and `describeEntityAdminPathSurfacesStructuredJson()`.
- Tagged suites remain in the normal `src/test` source set; Gradle task selection is driven by JUnit tags rather than separate source sets.

**Structure:**
```text
ai-agent/ai-agent/src/test/java/com/vn/agent/<feature>/*Test.java
ai-agent/ai-agent/src/test/resources/eval/*.yaml
jmix-app/src/test/java/com/vn/jmixapp/<feature>/*Test.java
jmix-app/src/test/resources/application-test.properties
```

## Test Structure

**Suite Organization:**
```java
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class ChatServiceToolIntegrationTest {

    @Autowired AgentToolCallbacks agentToolCallbacks;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void perRequestAssemblyIncludesBuiltInsAndHostContributor() {
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        assertThat(callbacks.length).isGreaterThanOrEqualTo(7);
    }
}
```

**Patterns:**
- Use `@SpringBootTest` for business logic, persistence, auto-configuration, role, and tool wiring tests.
- Use Jmix test support extensions such as `AuthenticatedAsAdmin` under `jmix-app/src/test/java/com/vn/jmixapp/test_support/AuthenticatedAsAdmin.java`.
- Use `@ActiveProfiles("test")` for Jmix/Spring Boot tests that need test database and profile-specific properties.
- Use `SystemAuthenticator` to seed data or run setup that should bypass row-level policies, then assert through secured `DataManager` paths.
- Keep integration smoke tests focused on end-to-end contracts rather than exhaustive UI rendering.

## Mocking

**Framework:** Mockito via `spring-boot-starter-test`; Spring test support is available for application-context tests.

**Patterns:**
```java
@Test
void serviceHandlesCollaboratorResult() {
    Collaborator collaborator = mock(Collaborator.class);
    when(collaborator.call()).thenReturn("ok");

    Service service = new Service(collaborator);

    assertThat(service.run()).isEqualTo("ok");
}
```

**What to Mock:**
- Mock LLM providers, clock-like collaborators, prompt-boundary collaborators, and small service dependencies when testing deterministic logic under `ai-agent/ai-agent/src/main/java/com/vn/agent/**`.
- Use Spring AI test support for chat-model behavior where possible; keep live model calls in `@Tag("live")` tests.
- Mock or fake SPI contributors when testing aggregation behavior, such as tool contributor assembly in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/**`.

**What NOT to Mock:**
- Do not mock Jmix `DataManager`, `Metadata`, `AccessManager`, or row-level security when the test is asserting Jmix integration semantics.
- Do not replace `ApplicationEventPublisher` with `@SpyBean`; use real Spring events and event capture patterns.
- Do not mock entity construction; create entities with `Metadata.create()` or `DataManager.create()`.

## Fixtures and Factories

**Test Data:**
```java
systemAuthenticator.runWithSystem(() -> {
    Customer customer = metadata.create(Customer.class);
    customer.setName("Test Buyer " + System.currentTimeMillis());
    customer.setEmail("test.buyer@example.com");
    dataManager.save(customer);

    Order order = metadata.create(Order.class);
    order.setNumber("ORD-" + System.currentTimeMillis());
    order.setCustomer(customer);
    dataManager.save(order);
});
```

**Location:**
- Host authentication helpers live in `jmix-app/src/test/java/com/vn/jmixapp/test_support/**`.
- Test data for evaluation rubrics lives in `ai-agent/ai-agent/src/test/resources/eval/**`.
- Spring/Jmix profile properties live in `jmix-app/src/test/resources/application-test.properties` and add-on test resources under `ai-agent/ai-agent/src/test/resources/**`.

## Coverage

**Requirements:** None enforced by a coverage plugin in `build.gradle`, `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, or `jmix-app/build.gradle`.

**View Coverage:**
```bash
./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.ConversationGatewayTest"
./gradlew :jmix-app:test --tests "com.vn.jmixapp.ai.ChatServiceToolIntegrationTest"
```

## Test Types

**Unit Tests:**
- Scope deterministic service, guard, filter, DTO, prompt-boundary, and utility behavior under `ai-agent/ai-agent/src/main/java/com/vn/agent/**`.
- Prefer constructor-created subjects and mocked/fake collaborators for pure logic.
- Keep assertions precise with AssertJ and avoid broad string-only checks unless testing serialized tool output.

**Integration Tests:**
- Use `@SpringBootTest` to validate Jmix entity mappings, Liquibase, DataManager access, auto-configuration, roles, Spring AI tool callbacks, and host add-on wiring.
- Tag Docker/Testcontainers-backed pgvector/RAG tests with `@Tag("rag-it")`; run them with `./gradlew :ai-agent:ai-agent:integrationTest`.
- Keep `./gradlew test` green without Docker by relying on tag exclusion and Docker availability gates in `ai-agent/ai-agent/ai-agent.gradle`.

**E2E Tests:**
- Jmix UI tests use `@UiTest` with `FlowuiTestAssistConfiguration`, as in `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java`.
- UI tests are present but selective; most add-on UI contracts are verified through view/controller support tests and service-level tests.
- Browser/manual verification is still expected for complex Flow UI behavior when the app is running at `http://localhost:8080`.

## Common Patterns

**Async Testing:**
```java
@Test
void publishesStreamingEvent() {
    conversationGateway.sendMessage(conversationId, "hello");

    assertThat(eventCapture.events())
            .anySatisfy(event -> assertThat(event.conversationId()).isEqualTo(conversationId));
}
```

**Error Testing:**
```java
@Test
void rejectsInvalidToolInput() {
    assertThatThrownBy(() -> converter.convert(invalidLiteral))
            .isInstanceOf(ToolUserError.class)
            .hasMessageContaining("invalid");
}
```

## Validation Workflow

**Local Sequence:**
- Run JetBrains inspections on touched Java/XML files with `mcp__jetbrains__get_file_problems(filePath, onlyErrors=false)` after meaningful code changes.
- Run the narrowest affected test class first with `./gradlew :module:test --tests "fully.qualified.TestClass"`.
- Run the containing module test task after targeted tests pass.
- Run `./gradlew :ai-agent:ai-agent:check` on machines with Docker so `integrationTest` participates in the verification gate.
- Run `./gradlew :ai-agent:ai-agent:evalTest` when changing parameters, structured output, prompt formatting, guardrails, or eval fixtures.

**Environment Notes:**
- `liveTest` requires `OPENROUTER_API_KEY`; read it from `.env` when needed and never hardcode or document secret values.
- `integrationTest` requires Docker for Testcontainers pgvector.
- HSQLDB file-lock flakiness is documented in `.planning/STATE.md`; stop lingering app/test JVMs before re-running database-heavy tests on Windows.

---

*Testing analysis: 2026-04-24*
