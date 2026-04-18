# Architecture

**Analysis Date:** 2026-04-18

## Pattern Overview

**Overall:** Jmix 2.8 modular layered architecture (Spring Boot 3 + Vaadin Flow) split into a host application and a reusable add-on, wired together through a Gradle composite build.

**Key Characteristics:**
- Multi-project composite Gradle build: root `ai-agent-core` aggregates two included builds — `jmix-app` (runnable application) and `ai-agent` (Jmix add-on with starter module).
- Jmix module pattern: each logical unit is a Spring `@Configuration` annotated with `@JmixModule(dependsOn = ...)` that contributes view controllers, actions, messages, and menu entries.
- Classic Jmix layering inside the host app: `entity` (JPA domain), `security` (role interfaces + Spring Security config + user repository), `view` (Vaadin Flow UI with XML descriptors + Java controllers), application bootstrap class at package root.
- Spring Boot auto-configuration glue: the add-on exposes `AIAutoConfiguration` via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` so depending on `ai-agent-starter` automatically imports `AIConfiguration`.
- Declarative UI: Vaadin views are defined as XML descriptors loaded by Jmix FlowUI controllers; data binding and actions are wired via XML (`<data>`, `<collection>`, `<loader>`, `<action>`).
- Schema-as-code: Liquibase changelogs under `src/main/resources/com/vn/jmixapp/liquibase/` are executed automatically on startup via the `main.liquibase.change-log` property.

## Layers

**Bootstrap / Application Layer:**
- Purpose: Boot the Spring context, configure the Vaadin shell (theme, PWA, push), expose the primary `DataSource` bean.
- Location: `jmix-app/src/main/java/com/vn/jmixapp/`
- Contains: `JmixAppApplication` (the `@SpringBootApplication` entry point implementing `AppShellConfigurator`).
- Depends on: `ai-agent-starter` (add-on), Jmix core/data/security/flowui starters, HSQLDB at runtime.
- Used by: Spring Boot runtime (`./gradlew bootRun`).

**Entity Layer (Domain Model):**
- Purpose: JPA/Jmix persistent entities.
- Location: `jmix-app/src/main/java/com/vn/jmixapp/entity/`
- Contains: `User.java` — `@JmixEntity` + `@Entity` + `@Table(name = "USER_")`, UUID id with `@JmixGeneratedValue`, `@Version`, `@InstanceName` computed display name, implements `JmixUserDetails` + `HasTimeZone`.
- Depends on: `io.jmix.core`, `io.jmix.security.authentication`, Jakarta Persistence, Jakarta Validation.
- Used by: Views (`UserListView`, `UserDetailView`), security (`DatabaseUserRepository`), Liquibase schema (`USER_` table).

**Security Layer:**
- Purpose: Role definitions, user repository integration, custom Spring Security filter chains.
- Location: `jmix-app/src/main/java/com/vn/jmixapp/security/`
- Contains:
  - `FullAccessRole.java` — `@ResourceRole` interface granting `EntityPolicy`, `EntityAttributePolicy`, `ViewPolicy`, `MenuPolicy`, `SpecificPolicy` all-access.
  - `UiMinimalRole.java` — `@ResourceRole(scope = SecurityScope.UI)` extending `UiMinimalPolicies`, granting access to `MainView` and `LoginView`.
  - `DatabaseUserRepository.java` — `@Primary @Component("UserRepository")` extending `AbstractDatabaseUserRepository<User>`; initializes system user with `FullAccessRole`.
  - `JmixAppSecurityConfiguration.java` — adds a custom `SecurityFilterChain` for `/public/**` at `JmixSecurityFilterChainOrder.CUSTOM`.
- Depends on: `io.jmix.security`, `io.jmix.securitydata`, `io.jmix.securityflowui`, Spring Security.
- Used by: Jmix security starters at runtime; Liquibase seed (admin + role assignment in `010-init-user.xml`).

**View / UI Layer:**
- Purpose: Vaadin Flow UI screens orchestrated by Jmix FlowUI.
- Location: `jmix-app/src/main/java/com/vn/jmixapp/view/` (controllers) and `jmix-app/src/main/resources/com/vn/jmixapp/view/` (XML descriptors, one subdirectory per view family).
- Contains:
  - `view/main/MainView.java` + `main-view.xml` — root layout (`@Route("")`) extending `StandardMainView`; customizes user-menu rendering via `@Install(to = "userMenu", subject = "...")`.
  - `view/login/LoginView.java` + `login-view.xml` — `@Route("login")` extending `StandardView`, wraps `JmixLoginForm`, uses `LoginViewSupport.authenticate(AuthDetails.of(...))` on `LoginEvent`.
  - `view/user/UserListView.java` + `user-list-view.xml` — `@Route(value = "users", layout = MainView.class)` extending `StandardListView<User>`; XML defines `<collection>` + `<loader>` with JPQL, `genericFilter`, `dataGrid`, and Jmix security actions (`sec_showRoleAssignments`, `sec_changePassword`, etc.).
  - `view/user/UserDetailView.java` + `user-detail-view.xml` — `@Route(value = "users/:id", layout = MainView.class)` extending `StandardDetailView<User>`; lifecycle hooks `@Subscribe` on `InitEvent`, `InitEntityEvent`, `ReadyEvent`, `ValidationEvent`, `BeforeSaveEvent`, `AfterSaveEvent`; uses `PasswordEncoder` to hash new-user passwords.
- Depends on: Entity layer, Jmix FlowUI, Vaadin Flow components.
- Used by: Menu (`menu.xml`) and Vaadin router.

**Add-on Layer (`ai-agent`):**
- Purpose: Reusable Jmix module meant to be consumed by other Jmix applications as a starter.
- Location: `ai-agent/ai-agent/` (core module) and `ai-agent/ai-agent-starter/` (auto-configuration module).
- Contains:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — `@Configuration @ComponentScan @ConfigurationPropertiesScan @JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})`; exposes `AI_AIViewControllers` and `AI_AIActions` beans with base package `com.vn.agent`.
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — sets `jmix.ui.menu-config=com/vn/agent/menu.xml`, `jmix.core.available-locales=en`.
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` — registers the `AI` top-level menu.
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` — add-on i18n strings.
  - `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — `@AutoConfiguration @Import(AIConfiguration.class)`.
  - `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — registers `com.vn.autoconfigure.agent.AIAutoConfiguration`.
- Depends on: `jmix-core`, `jmix-data` (core module also pulls `jmix-eclipselink`, `jmix-flowui`, `jmix-flowui-themes`); starter has `api project(':ai-agent')` + `spring-boot-autoconfigure`.
- Used by: `jmix-app/build.gradle` via `implementation 'com.vn:ai-agent-starter:0.0.1-SNAPSHOT'`, which causes Spring Boot to auto-import `AIConfiguration`, register its view controllers/actions, and compose the `AI` menu into the running application.

**Persistence Layer:**
- Purpose: Database schema management and ORM.
- Location: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/`
- Contains: `changelog.xml` master file that `<include>`s Jmix-provided changelogs (`/io/jmix/data/liquibase/changelog.xml`, `/io/jmix/flowuidata/liquibase/changelog.xml`, `/io/jmix/securitydata/liquibase/changelog.xml`) and `<includeAll path="/com/vn/jmixapp/liquibase/changelog"/>`, plus `changelog/010-init-user.xml` (creates `USER_` table, unique username index, seeds admin user + `SEC_ROLE_ASSIGNMENT` row).
- Depends on: EclipseLink ORM via `io.jmix.data:jmix-eclipselink-starter`.
- Used by: Spring Boot startup (Liquibase auto-runs), DataManager-based queries.

## Data Flow

**Typical list-then-edit UI request flow:**

1. Browser hits `/users` -> Vaadin router resolves `UserListView` (`@Route("users", layout = MainView.class)`).
2. `StandardListView<User>` loads `user-list-view.xml`; the `<dataLoadCoordinator auto="true"/>` facet triggers `usersDl` loader.
3. Loader executes JPQL `select e from User e order by e.username` against `DataManager` (wired by Jmix using the `@Primary` `DataSource` bean in `JmixAppApplication`).
4. EclipseLink returns `User` entities populated according to the `_base` fetch plan; results bind to `usersDataGrid`.
5. User clicks `createAction` / `editAction` -> Jmix navigates to `UserDetailView` via `@Route("users/:id")`.
6. `UserDetailView` lifecycle hooks run (`InitEvent`, `InitEntityEvent`, `ReadyEvent`); on save, `@Subscribe BeforeSaveEvent` hashes the password with the injected `PasswordEncoder`, then Jmix persists via `DataManager`.

**Authentication flow:**

1. Anonymous request hits `/login` -> `LoginView` renders `JmixLoginForm`.
2. Form submit fires `LoginEvent`; `LoginView.onLogin` calls `loginViewSupport.authenticate(AuthDetails.of(username, password).withLocale(...).withRememberMe(...))`.
3. Spring Security uses `DatabaseUserRepository` (`@Primary` bean named `UserRepository`) to load the `User` entity.
4. Authorities are resolved via role assignments stored in `SEC_ROLE_ASSIGNMENT` (seeded by `010-init-user.xml` with `system-full-access`).
5. On failure (`BadCredentialsException`, `DisabledException`, `LockedException`, `AccessDeniedException`), the form error flag is set.

**Add-on contribution flow:**

1. `jmix-app` declares `implementation 'com.vn:ai-agent-starter:0.0.1-SNAPSHOT'`.
2. Spring Boot reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` from the starter and registers `AIAutoConfiguration`.
3. `AIAutoConfiguration` `@Import`s `AIConfiguration`, which activates `@ComponentScan` on `com.vn.agent`, registers view-controller and action scanners, and loads `module.properties`.
4. Jmix merges the add-on menu (`com/vn/agent/menu.xml`) with the host menu (`com/vn/jmixapp/menu.xml`) into the composite menu (`jmix.ui.composite-menu=true`).

**State Management:**
- UI state is kept in Jmix view containers (`<collection>`, `<loader>`) defined in XML and wired into controllers via `@ViewComponent`.
- Authentication/session state is handled by Vaadin `VaadinSession` and Spring Security; current user exposed via `CurrentUserSubstitution` (used in `MainView`).
- Persistent state lives in the relational DB via EclipseLink; default runtime store is file-based HSQLDB (`.jmix/hsqldb/jmixapp`).

## Key Abstractions

**Jmix Module:**
- Purpose: A self-contained Spring configuration declaring its Jmix dependencies, scanned packages for views/actions, and property sources.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`.
- Pattern: `@Configuration @ComponentScan @ConfigurationPropertiesScan @JmixModule(dependsOn = {...}) @PropertySource(...)` + `ViewControllersConfiguration` and `ActionsConfiguration` beans keyed by module-prefixed names.

**Jmix Entity:**
- Purpose: Domain class that participates in Jmix metadata (instance names, lifecycle, security metadata).
- Examples: `jmix-app/src/main/java/com/vn/jmixapp/entity/User.java`.
- Pattern: `@JmixEntity` + JPA `@Entity`/`@Table`, UUID `@Id` with `@JmixGeneratedValue`, `@Version`, `@InstanceName` + `@DependsOnProperties`, `@Secret`/`@SystemLevel` for sensitive columns.

**View Controller (Jmix FlowUI):**
- Purpose: Pair a Java class with an XML layout descriptor to build a Vaadin screen.
- Examples: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java` + `user-detail-view.xml`.
- Pattern: `@Route(...)` + `@ViewController(id = "...")` + `@ViewDescriptor(path = "...-view.xml")` extending `StandardListView`/`StandardDetailView`/`StandardView`/`StandardMainView`. Components from XML are injected with `@ViewComponent`; Spring beans with `@Autowired`; lifecycle handled via `@Subscribe` methods.

**Resource Role:**
- Purpose: Declarative RBAC — permissions expressed as annotations on interface methods.
- Examples: `FullAccessRole.java`, `UiMinimalRole.java`.
- Pattern: `@ResourceRole(name, code, scope?)` on an interface, with `@EntityPolicy`, `@EntityAttributePolicy`, `@ViewPolicy`, `@MenuPolicy`, `@SpecificPolicy` on no-arg methods.

**Database User Repository:**
- Purpose: Bridge between Jmix security and the custom `User` entity.
- Example: `jmix-app/src/main/java/com/vn/jmixapp/security/DatabaseUserRepository.java`.
- Pattern: Extend `AbstractDatabaseUserRepository<T extends JmixUserDetails>`; mark `@Primary @Component("UserRepository")`; override `getUserClass`, `initSystemUser`, `initAnonymousUser`.

**Auto-Configured Starter:**
- Purpose: Zero-config inclusion of the add-on.
- Example: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Pattern: `@AutoConfiguration @Import(AIConfiguration.class)` class registered through the Spring Boot 3 `AutoConfiguration.imports` mechanism; starter `ai-agent-starter.gradle` re-exports the core module with `api project(':ai-agent')` and sets `jmix { entitiesEnhancing { enabled = false } }`.

## Entry Points

**HTTP / UI entry point:**
- Location: `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`
- Triggers: `./gradlew bootRun` (Spring Boot launches on http://localhost:8080).
- Responsibilities: `@SpringBootApplication` main, Vaadin `@Push` / `@Theme("jmix-app")` / `@PWA`, defines `@Primary` `DataSource` bound to `main.datasource.*`, logs startup URL in `ApplicationStartedEvent`.

**Default route (`/`):**
- Location: `jmix-app/src/main/java/com/vn/jmixapp/view/main/MainView.java`
- Triggers: Vaadin router when authenticated.
- Responsibilities: Render application shell, customize user menu via `@Install` methods.

**Login route (`/login`):**
- Location: `jmix-app/src/main/java/com/vn/jmixapp/view/login/LoginView.java`
- Triggers: Any unauthenticated navigation (default per `jmix.ui.login-view-id=LoginView`).
- Responsibilities: Present `JmixLoginForm`, call `LoginViewSupport.authenticate(...)`, manage locale selection, inject default credentials (`ui.login.defaultUsername/defaultPassword`).

**Add-on auto-configuration entry point:**
- Location: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`
- Triggers: Spring Boot auto-configuration scan when `ai-agent-starter` is on the classpath.
- Responsibilities: Pull in `AIConfiguration` and register it with the host Spring context.

**Test entry point (add-on):**
- Location: `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java`
- Triggers: `@SpringBootTest` tests such as `AITest.contextLoads`.
- Responsibilities: `@SpringBootConfiguration @EnableAutoConfiguration @Import(AIConfiguration.class)`, provides `@Primary` embedded HSQL `DataSource`.

## Error Handling

**Strategy:** Rely on Jmix/Vaadin/Spring defaults; explicit handling is limited to authentication errors and view validation.

**Patterns:**
- Authentication exceptions caught in `LoginView.onLogin` (`BadCredentialsException`, `DisabledException`, `LockedException`, `AccessDeniedException`) — logged via SLF4J and surfaced as a login-form error flag.
- Form validation handled declaratively in XML (`jakarta.validation` constraints on entity fields — e.g., `@Email` on `User.email`) and imperatively in `UserDetailView.onValidation` (password confirmation check pushes into `event.getErrors()`).
- User notifications shown via Jmix `Notifications` API (`UserDetailView.onAfterSave` emits a warning about unassigned roles with `NotificationVariant.LUMO_WARNING`).

## Cross-Cutting Concerns

**Logging:**
- SLF4J everywhere (`LoggerFactory.getLogger(...)`).
- Levels configured in `jmix-app/src/main/resources/application.properties` (`logging.level.eclipselink.logging.sql`, `logging.level.io.jmix.*`, `logging.level.org.springframework.security`, etc.).
- ANSI colors enabled (`spring.output.ansi.enabled=always`).

**Validation:**
- Jakarta Bean Validation annotations on entity fields (`@Email`).
- Imperative validation in view `@Subscribe`d `ValidationEvent` handlers with i18n messages through `MessageBundle`.

**Authentication:**
- Jmix security starters + custom `JmixAppSecurityConfiguration` for `/public/**` passthrough at `JmixSecurityFilterChainOrder.CUSTOM` order.
- `DatabaseUserRepository` bridges the custom `User` entity to Spring Security via `AbstractDatabaseUserRepository`.
- Passwords hashed at save time in `UserDetailView.onBeforeSave` via injected `PasswordEncoder`; seed admin uses `{noop}admin` placeholder.

**Internationalization:**
- Available locales configured via `jmix.core.available-locales=vi,en` (host) and `=en` (add-on).
- Messages in `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties` and `messages_vi.properties` (37 lines each); add-on messages in `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`.
- All UI text referenced through `msg://` keys in view XML and `MessageBundle`/`Messages` in controllers.

**Configuration:**
- Host app: `jmix-app/src/main/resources/application.properties` (datasource, Liquibase, Jmix UI, default credentials, logging).
- Add-on: `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` loaded via `@PropertySource` in `AIConfiguration`.
- Tests: `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties`.

---

*Architecture analysis: 2026-04-18*
