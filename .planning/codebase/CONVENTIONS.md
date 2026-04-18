# Coding Conventions

**Analysis Date:** 2026-04-18

## Naming Patterns

**Files:**
- Java classes: `PascalCase.java` (e.g., `UserListView.java`, `DatabaseUserRepository.java`, `AIAutoConfiguration.java`)
- View XML descriptors: `kebab-case.xml` matching the view (e.g., `user-list-view.xml`, `user-detail-view.xml`) in `jmix-app/src/main/resources/com/vn/jmixapp/view/**`
- Gradle modules use lowercase hyphenless/hyphenated names: `ai-agent.gradle`, `ai-agent-starter.gradle`
- Resource bundles: `messages.properties`, `messages_en.properties`, `messages_vi.properties` — ALWAYS maintain all locales in parallel

**Classes:**
- Views: suffix with `View` (e.g., `MainView`, `UserListView`, `UserDetailView`, `LoginView`)
- Configurations: suffix with `Configuration` (e.g., `AIConfiguration`, `AIAutoConfiguration`, `AITestConfiguration`, `JmixAppSecurityConfiguration`)
- Repositories: suffix with `Repository` (e.g., `DatabaseUserRepository`)
- Security roles: suffix with `Role`, defined as interfaces (e.g., `FullAccessRole`, `UiMinimalRole`)
- Test classes: suffix with `Test` or `UiTest` (e.g., `UserTest`, `UserUiTest`, `AITest`)

**Functions/Methods:**
- `camelCase`. Standard JavaBean accessors: `getXxx`/`setXxx`/`isXxx`. Examples: `generateUserName`, `initDefaultCredentials`, `createAvatar`.
- Spring Jmix event handlers use `on<EventName>` pattern: `onInit`, `onInitEntity`, `onReady`, `onValidation`, `onBeforeSave`, `onAfterSave`, `onLogin` (see `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java`).

**Variables:**
- `camelCase` fields; `final` parameters are common (e.g., `setId(final UUID id)` in `User.java`).
- Constants: `UPPER_SNAKE_CASE` with `String CODE = "..."` pattern for role codes (e.g., `FullAccessRole.CODE = "system-full-access"`).

**Types / Packages:**
- Base package: `com.vn.*`. Jmix app uses `com.vn.jmixapp`, agent module uses `com.vn.agent`, starter uses `com.vn.autoconfigure.agent`.
- Feature subpackages: `entity/`, `view/<entity>/`, `security/`, `test_support/`.

**Database Columns:**
- `UPPER_SNAKE_CASE` (e.g., `USER_`, `USERNAME`, `FIRST_NAME`, `TIME_ZONE_ID`). Table names end with `_` to avoid SQL keyword clashes (`USER_`). Indexes: `IDX_<TABLE>__ON_<COLUMN>` (e.g., `IDX_USER__ON_USERNAME`).

**Message Keys:**
- Fully-qualified: `<package>/<view-or-entity>.<field>` (e.g., `com.vn.jmixapp.view.user/UserListView.title`, `com.vn.jmixapp.entity/User.username`). See `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties`.

## Code Style

**Formatting:**
- No explicit formatter config (`.editorconfig`, `.prettierrc`, Checkstyle, Spotless) detected. IDE defaults (IntelliJ Jmix Studio) are assumed.
- 4-space indentation; opening braces on the same line (K&R).
- Trailing newline at end of file.

**Linting:**
- No standalone linter (e.g., Checkstyle, PMD, SpotBugs) configured in any `build.gradle`. Quality is enforced via Jmix Studio and JetBrains MCP `get_file_problems` (see `CLAUDE.md`).

**Modifiers:**
- `final` on method parameters is idiomatic (e.g., `public void setUsername(final String username)`, `public void onInit(final InitEvent event)`).
- Fields are `private` with explicit getters/setters. No Lombok is permitted (see `CLAUDE.md` forbidden list).

## Import Organization

**Order (observed in `UserDetailView.java`, `MainView.java`, `LoginView.java`):**
1. Project-local imports (`com.vn.*`)
2. Third-party imports (`com.vaadin.*`, `io.jmix.*`, `org.springframework.*`, `org.slf4j.*`, etc.)
3. `jakarta.*` imports
4. `java.*` / `java.util.*` imports

- No wildcard imports except for related Jmix packages (e.g., `io.jmix.flowui.view.*` in view controllers).
- No `import static` except in tests (`import static org.assertj.core.api.Assertions.assertThat;`).

**Path Aliases:** N/A (Java).

## Error Handling

**Patterns:**
- Catch specific exception types, never generic `Exception`. Example from `LoginView.onLogin`:
  ```java
  } catch (final BadCredentialsException | DisabledException | LockedException | AccessDeniedException e) {
      log.warn("Login failed for user '{}': {}", event.getUsername(), e.toString());
      event.getSource().setError(true);
  }
  ```
- UI validation surfaces errors via Jmix `ValidationEvent.getErrors().add(...)` using a message-bundle key, not hardcoded strings (see `UserDetailView.onValidation`).
- `throws Exception` only where Spring signatures demand it (e.g., `SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception` in `JmixAppSecurityConfiguration.java`).
- Stream terminal operations that must yield a value use `.orElseThrow()` (e.g., `UserUiTest.test_createUser`).

## Logging

**Framework:** SLF4J (`org.slf4j.Logger` / `LoggerFactory`). Never `System.out`.

**Patterns:**
- Per-class logger: `private static final Logger log = LoggerFactory.getLogger(LoginView.class);`
- Parameterised messages with `{}` placeholders: `log.warn("Login failed for user '{}': {}", event.getUsername(), e.toString());`
- `JmixAppApplication` uses ad-hoc `LoggerFactory.getLogger(JmixAppApplication.class)` inside an event listener for startup messages.

## Comments

**When to Comment:**
- Javadoc on classes that provide extension points or non-obvious behaviour (e.g., `JmixAppSecurityConfiguration` documents `SecurityFilterChain` ordering with an embedded `<pre>` example; `AuthenticatedAsAdmin` describes JUnit extension usage).
- Short one-line JavaDoc on test classes stating intent (e.g., `/** Sample integration test for the User entity. */`).
- Inline comments in tests narrate setup/act/assert steps (see `UserUiTest.test_createUser`).

**JavaDoc/TSDoc:** Standard JavaDoc. `{@link ...}` used for cross-referencing Spring/Jmix classes.

## Function Design

**Size:** Methods stay small and focused; view logic is decomposed into private helpers (e.g., `MainView.createAvatar`, `generateUserName`, `isSubstituted`; `LoginView.initLocales`, `initDefaultCredentials`).

**Parameters:** Prefer `final` on parameters. Pass domain objects (`User`, `UserDetails`) rather than primitives.

**Return Values:**
- Null-safe returns for collections: `return authorities != null ? authorities : Collections.emptyList();` (`User.getAuthorities()`).
- Defensive boolean checks: `Boolean.TRUE.equals(active)` instead of unboxing.
- `Strings.nullToEmpty(...)` (Guava) for string concatenation defence (`MainView.generateUserName`).

## Module Design

**Exports:** Plain `public` classes. No barrel files.

**Packaging:**
- Jmix modular layout per `CLAUDE.md`: `entity/`, `service/`, `view/<entity>/`, `security/`, `test_support/`.
- Each view package owns both the Java controller and the `*.xml` descriptor under `src/main/resources/com/vn/jmixapp/view/<entity>/`.
- Multi-project Gradle build: `ai-agent-core` (root), `ai-agent/ai-agent`, `ai-agent/ai-agent-starter`, `jmix-app`. Starters re-export configurations via `@AutoConfiguration` + `@Import` (see `AIAutoConfiguration.java`).

## Dependency Injection

**Views:**
- `@ViewComponent` for XML-declared components (fields `usernameField`, `passwordField`, `messageBundle` in `UserDetailView.java`).
- `@Autowired` for Spring beans (`Notifications`, `EntityStates`, `PasswordEncoder`, `Messages`, `UiComponents`).
- Field injection is accepted in views (framework idiom).

**Services / Repositories:**
- Constructor injection is mandated by `CLAUDE.md`. Field injection is forbidden in services.

## Jmix-Specific Rules

- Entities: `@JmixEntity` + `@Entity` + UUID `@Id` with `@JmixGeneratedValue`, `@Version` field, `@InstanceName` with `@DependsOnProperties` (see `User.java`).
- Never instantiate entities via `new`; use `DataManager.create(...)` / `Metadata.create(...)` (see `UserTest.test_saveAndLoad`).
- Data access uses `DataManager` fluent API, never `EntityManager`.
- Views are annotated with `@Route`, `@ViewController(id = "...")`, `@ViewDescriptor(path = "...")` and extend `StandardListView`/`StandardDetailView`/`StandardView`/`StandardMainView`.
- Security roles are **interfaces** annotated with `@ResourceRole`; policies via `@EntityPolicy`, `@EntityAttributePolicy`, `@ViewPolicy`, `@MenuPolicy`, `@SpecificPolicy` (see `FullAccessRole.java`, `UiMinimalRole.java`).
- All UI text goes through `msg://` keys or `MessageBundle.getMessage(...)`. No hardcoded UI strings.
- Every message must be added to all locale files (`messages_en.properties`, `messages_vi.properties`).
- Password handling: hash with `PasswordEncoder.encode(...)` in `onBeforeSave` before persisting (`UserDetailView.onBeforeSave`).

## Forbidden (from `CLAUDE.md`)

- Lombok on entities.
- Entity instantiation via constructor.
- `EntityManager`.
- Business logic inside view controllers.
- Hardcoded UI text.
- Single-locale message additions.
- Edits in `frontend/generated/`.

---

*Convention analysis: 2026-04-18*
