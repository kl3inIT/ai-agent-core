# Testing Patterns

**Analysis Date:** 2026-04-18

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) via `useJUnitPlatform()` declared in every Gradle module (`jmix-app/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`).
- `spring-boot-starter-test` with `org.junit.vintage:junit-vintage-engine` explicitly excluded — Jupiter only.
- UI tests add `io.jmix.flowui:jmix-flowui-test-assist` (`jmix-app/build.gradle`).
- `junit-platform-launcher` is added as `testRuntimeOnly` in `ai-agent/ai-agent/ai-agent.gradle`.

**Assertion Libraries:**
- AssertJ (`org.assertj.core.api.Assertions.assertThat`) — primary for integration tests (`UserTest.java`).
- JUnit Jupiter `Assertions` — used in UI tests for null/not-null checks (`UserUiTest.java`).

**Run Commands (from `CLAUDE.md`):**
```bash
./gradlew test                                                              # Run all tests
./gradlew test --tests "com.company.sample.order.OrderServiceTest"          # Run single class
./gradlew test --tests "com.company.sample.order.OrderServiceTest.testOrderCalculations"  # Single method
```
Gradle has no default `--watch` task; no coverage plugin (JaCoCo) is configured.

## Test File Organization

**Location:**
- Separate test source tree under `src/test/java`, mirroring production package structure.
- Test utilities live in a `test_support` sub-package (e.g., `jmix-app/src/test/java/com/vn/jmixapp/test_support/`).

**Naming:**
- `<Entity>Test` for integration/data tests (`UserTest`, `AITest`).
- `<Entity>UiTest` for Vaadin Flow UI tests (`UserUiTest`).
- Test method names use snake_case prefixed with `test_`: `test_saveAndLoad`, `test_createUser`. `AITest.contextLoads` is the smoke-test exception.

**Structure:**
```
jmix-app/src/test/
├── java/com/vn/jmixapp/
│   ├── user/
│   │   ├── UserTest.java          # Data/integration test
│   │   └── UserUiTest.java        # UI test
│   └── test_support/
│       └── AuthenticatedAsAdmin.java
└── resources/
    └── application-test.properties

ai-agent/ai-agent/src/test/
├── java/com/vn/agent/
│   ├── AITest.java                # Context-loads smoke test
│   └── AITestConfiguration.java   # Test @SpringBootConfiguration
└── resources/com/vn/agent/
    ├── liquibase/changelog.xml
    └── test-app.properties
```

## Test Structure

**Suite Organization (from `jmix-app/src/test/java/com/vn/jmixapp/user/UserTest.java`):**
```java
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
public class UserTest {

    @Autowired DataManager dataManager;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;

    User savedUser;

    @Test
    void test_saveAndLoad() {
        User user = dataManager.create(User.class);
        user.setUsername("test-user-" + System.currentTimeMillis());
        user.setPassword(passwordEncoder.encode("test-passwd"));
        savedUser = dataManager.save(user);

        User loadedUser = dataManager.load(User.class).id(user.getId()).one();
        assertThat(loadedUser).isEqualTo(user);

        UserDetails userDetails = userRepository.loadUserByUsername(user.getUsername());
        assertThat(userDetails).isEqualTo(user);
    }

    @AfterEach
    void tearDown() {
        if (savedUser != null) dataManager.remove(savedUser);
    }
}
```

**Patterns:**
- **Full Spring context.** `@SpringBootTest` is mandatory; no Mockito-based unit tests exist in the repository.
- **Active profile:** `@ActiveProfiles("test")` to load `application-test.properties`.
- **Authentication:** Inject `AuthenticatedAsAdmin` via `@ExtendWith` to wrap each test in `SystemAuthenticator.begin("admin") / end()` (see `test_support/AuthenticatedAsAdmin.java`).
- **Unique test data:** Use `System.currentTimeMillis()` to suffix usernames and avoid collisions across runs.
- **Narrative comments** separate Arrange/Act/Assert phases inside each test.

**Setup/Teardown:**
- No `@BeforeEach` in observed tests; authentication is handled by the extension.
- `@AfterEach tearDown()` deletes created entities. UI test uses a broad cleanup query: `dataManager.load(User.class).query("e.username like ?1", "test-user-%").list().forEach(dataManager::remove);` — runs unconditionally.

## Mocking

**Framework:**
- Mockito is available transitively via `spring-boot-starter-test`, but **no mocks are used** in the observed tests. The repository favours full `@SpringBootTest` integration tests over unit tests with mocks.

**What to Mock:** Not applicable in the current test suite.

**What NOT to Mock:**
- `DataManager`, `UserRepository`, `PasswordEncoder`, `ViewNavigators` — always injected real beans.
- Database layer — replaced with an embedded HSQL DB (see Fixtures section), not mocked.

## Fixtures and Factories

**Test Data:**
- Created ad-hoc inside each test via `DataManager.create(User.class)` followed by setters. No shared factory/builder classes.
- No `@Sql`, `@DataSet`, or fixture files; Liquibase runs at startup to build schema.

**Location:**
- None. Test data is inline in the test method; cleanup is done in `@AfterEach`.

## Test Database

**`jmix-app` (integration + UI):**
- HSQL file-based DB. `jmix-app/src/test/resources/application-test.properties`:
  ```properties
  main.datasource.url=jdbc:hsqldb:file:.jmix/hsqldb/jmixapp_test
  main.datasource.username=sa
  main.datasource.password=
  ```
- Schema is built by Liquibase at startup from `src/main/resources/com/vn/jmixapp/liquibase/changelog.xml`.

**`ai-agent` module:**
- Uses an embedded in-memory HSQL DB with a unique name per test context in `AITestConfiguration.java`:
  ```java
  @Bean @Primary
  DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
              .generateUniqueName(true)
              .setType(EmbeddedDatabaseType.HSQL)
              .build();
  }
  ```
- Annotated with `@SpringBootConfiguration`, `@EnableAutoConfiguration`, `@Import(AIConfiguration.class)`, `@JmixModule`, `@PropertySource("classpath:/com/vn/agent/test-app.properties")`.
- Liquibase changelog for tests: `ai-agent/ai-agent/src/test/resources/com/vn/agent/liquibase/changelog.xml`.

## Coverage

**Requirements:** None enforced. No JaCoCo, Kover, or coverage plugin detected.

**View Coverage:** Not configured.

## Test Types

**Smoke / Context-Loads:**
- `ai-agent/ai-agent/src/test/java/com/vn/agent/AITest.java` — `@SpringBootTest` with an empty `contextLoads()` method verifying the Jmix module starts.

**Integration Tests:**
- `jmix-app/src/test/java/com/vn/jmixapp/user/UserTest.java` — exercises `DataManager`, entity persistence, and `UserRepository` against the real Spring context and HSQL DB.

**UI Tests:**
- `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java` — Jmix Flow UI integration test using `@UiTest` + `FlowuiTestAssistConfiguration`.
  ```java
  @UiTest
  @SpringBootTest(classes = {JmixAppApplication.class, FlowuiTestAssistConfiguration.class})
  @ActiveProfiles("test")
  public class UserUiTest { ... }
  ```
- Uses `ViewNavigators` to navigate, `UiTestUtils.getCurrentView()` to grab the active view, and `UiTestUtils.getComponent(view, "id")` to retrieve Vaadin components by id.
- Interacts through real component API (`createBtn.click()`, `usernameField.setValue(...)`), asserts by inspecting `DataGrid<User>.getItems()`.

**Unit Tests / E2E:**
- No pure unit tests (Mockito-only) detected.
- No E2E framework (Playwright, Selenium) integrated in the repo. `CLAUDE.md` mentions Playwright MCP only as an ad-hoc UI verification aid when the app is running manually.

## Common Patterns

**JUnit Extensions:**
- Custom extension `AuthenticatedAsAdmin` implements `BeforeEachCallback` + `AfterEachCallback`; pulls `SystemAuthenticator` from the Spring `ApplicationContext` via `SpringExtension.getApplicationContext(context)` and wraps each test in `begin("admin") / end()`.

**Navigation in UI Tests:**
```java
viewNavigators.view(UiTestUtils.getCurrentView(), UserListView.class).navigate();
UserListView userListView = UiTestUtils.getCurrentView();
JmixButton createBtn = UiTestUtils.getComponent(userListView, "createButton");
createBtn.click();
```

**Async Testing:** No async/reactive tests present. Test logic is strictly synchronous (Vaadin Flow server-side model).

**Error Testing:** No explicit `assertThrows` patterns observed; error paths are covered implicitly via UI validation events.

**Cleanup Strategy:** Broad `WHERE e.username LIKE 'test-user-%'` sweeps in `@AfterEach` for UI tests to handle mid-test failures that leave persisted entities behind.

---

*Testing analysis: 2026-04-18*
