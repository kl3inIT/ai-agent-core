# Codebase Structure

**Analysis Date:** 2026-04-18

## Directory Layout

```
ai-agent-core/                              # Root Gradle composite build
├── build.gradle                            # Minimal root build (empty stanza)
├── settings.gradle                         # includeBuild 'jmix-app' + includeBuild 'ai-agent'
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/ # Gradle wrapper
├── AGENTS.md                               # Agent instructions (mirror of CLAUDE.md)
├── CLAUDE.md                               # Project-specific AI coding guidelines
├── README.md
├── .gitignore
├── .idea/                                  # IntelliJ metadata
├── .jmix/hsqldb/                           # Local HSQLDB data files (generated)
├── .planning/codebase/                     # GSD codebase-map outputs
│
├── jmix-app/                               # Runnable Jmix/Vaadin application (included build)
│   ├── build.gradle                        # Spring Boot + Vaadin + Jmix starters + HSQLDB runtime
│   ├── settings.gradle                     # rootProject.name = 'jmix-app'
│   ├── gradle.properties
│   ├── gradlew / gradle/wrapper/
│   ├── README.md
│   ├── .gitignore
│   ├── .idea/ / .jmix/hsqldb/
│   └── src/main/
│       ├── java/com/vn/jmixapp/
│       │   ├── JmixAppApplication.java     # @SpringBootApplication entry point
│       │   ├── entity/
│       │   │   └── User.java               # JmixUserDetails entity
│       │   ├── security/
│       │   │   ├── FullAccessRole.java
│       │   │   ├── UiMinimalRole.java
│       │   │   ├── DatabaseUserRepository.java
│       │   │   └── JmixAppSecurityConfiguration.java
│       │   └── view/
│       │       ├── login/LoginView.java
│       │       ├── main/MainView.java
│       │       └── user/
│       │           ├── UserListView.java
│       │           └── UserDetailView.java
│       ├── resources/
│       │   ├── application.properties
│       │   ├── com/vn/jmixapp/
│       │   │   ├── menu.xml
│       │   │   ├── messages_en.properties
│       │   │   ├── messages_vi.properties
│       │   │   ├── liquibase/
│       │   │   │   ├── changelog.xml       # Master changelog
│       │   │   │   └── changelog/
│       │   │   │       └── 010-init-user.xml
│       │   │   └── view/
│       │   │       ├── login/login-view.xml
│       │   │       ├── main/main-view.xml
│       │   │       └── user/
│       │   │           ├── user-list-view.xml
│       │   │           └── user-detail-view.xml
│       │   └── META-INF/resources/
│       │       ├── icons/icon.png
│       │       └── public/images/logo.png
│       ├── bundles/                        # Vaadin dev bundle (generated / committed marker)
│       └── frontend/
│           ├── index.html
│           ├── generated/                  # Vaadin-generated — DO NOT EDIT
│           └── themes/jmix-app/
│               ├── jmix-app.css
│               ├── styles.css
│               ├── theme.json
│               └── view/login-view.css
│
└── ai-agent/                               # Jmix add-on (included build)
    ├── build.gradle                        # Common subproject config (jmix BOM, publishing)
    ├── settings.gradle                     # include 'ai-agent', 'ai-agent-starter'
    ├── gradle.properties
    ├── gradlew / gradle/wrapper/
    ├── .gitignore / .idea/
    ├── ai-agent/                           # Core add-on module
    │   ├── ai-agent.gradle                 # archivesBaseName = 'ai-agent'
    │   └── src/
    │       ├── main/
    │       │   ├── java/com/vn/agent/
    │       │   │   └── AIConfiguration.java
    │       │   └── resources/com/vn/agent/
    │       │       ├── menu.xml
    │       │       ├── messages.properties
    │       │       └── module.properties
    │       └── test/
    │           ├── java/com/vn/agent/
    │           │   ├── AITest.java
    │           │   └── AITestConfiguration.java
    │           └── resources/com/vn/agent/
    │               ├── test-app.properties
    │               └── liquibase/changelog.xml
    └── ai-agent-starter/                   # Spring Boot auto-configuration module
        ├── ai-agent-starter.gradle         # api project(':ai-agent')
        └── src/main/
            ├── java/com/vn/autoconfigure/agent/
            │   └── AIAutoConfiguration.java
            └── resources/META-INF/spring/
                └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## Directory Purposes

**`jmix-app/`** (included Gradle build):
- Purpose: The runnable Spring Boot + Vaadin Flow + Jmix application.
- Contains: Application entry point, entities, views, security config, Liquibase changelogs, Vaadin theme.
- Key files: `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`, `jmix-app/src/main/resources/application.properties`, `jmix-app/build.gradle`.

**`jmix-app/src/main/java/com/vn/jmixapp/entity/`**:
- Purpose: JPA/Jmix domain entities.
- Contains: `User.java` (with `@JmixEntity`, UUID id, `@Version`, `@InstanceName`).
- Rule: No Lombok on entities; instantiate via `Metadata.create()` / `DataManager.create()`, never via constructor.

**`jmix-app/src/main/java/com/vn/jmixapp/security/`**:
- Purpose: Role-based access control and Spring Security customization.
- Contains: `FullAccessRole`, `UiMinimalRole` (both annotated `@ResourceRole` interfaces), `DatabaseUserRepository` (extends `AbstractDatabaseUserRepository<User>`), `JmixAppSecurityConfiguration` (custom filter chain).

**`jmix-app/src/main/java/com/vn/jmixapp/view/<feature>/`**:
- Purpose: Vaadin/Jmix screens grouped by feature (`login`, `main`, `user`).
- Contains: Java controllers annotated with `@ViewController` + `@ViewDescriptor` extending `StandardListView`/`StandardDetailView`/`StandardView`/`StandardMainView`.
- Pair with: Matching XML descriptor in `src/main/resources/com/vn/jmixapp/view/<feature>/`.

**`jmix-app/src/main/resources/com/vn/jmixapp/view/<feature>/`**:
- Purpose: XML layout descriptors paired 1:1 with Java view controllers.
- Naming: kebab-case ending in `-view.xml` (e.g., `user-list-view.xml`).

**`jmix-app/src/main/resources/com/vn/jmixapp/liquibase/`**:
- Purpose: Database schema versioning.
- Contains: Master `changelog.xml` that includes Jmix-provided changelogs and `<includeAll path=".../changelog"/>`, plus the `changelog/` directory with numbered step files (`010-init-user.xml`).
- Rule: New changelogs go in `changelog/` with numeric prefixes (`020-...`, `030-...`) or time-based hierarchical paths; always included through the master `changelog.xml`.

**`jmix-app/src/main/resources/com/vn/jmixapp/`** (root):
- Purpose: Module-wide Jmix resources.
- Contains: `menu.xml` (top-level menu), `messages_en.properties`, `messages_vi.properties` (i18n — must add keys to ALL locales).

**`jmix-app/src/main/frontend/`**:
- Purpose: Vaadin frontend assets.
- Contains: `index.html`, `themes/jmix-app/` (custom CSS + `theme.json`).
- Generated: `frontend/generated/` is produced by Vaadin — DO NOT edit.

**`jmix-app/src/main/bundles/`**:
- Purpose: Vaadin dev bundle artifacts.
- Generated: Yes.

**`ai-agent/`** (included Gradle build):
- Purpose: Jmix add-on published as `com.vn:ai-agent-starter:0.0.1-SNAPSHOT`.
- Contains: Two subprojects — `ai-agent` (module) and `ai-agent-starter` (auto-configuration).
- Key file: `ai-agent/build.gradle` defines shared Jmix BOM (`bomVersion = '2.8.0'`, `projectId = 'AI'`) and publishing.

**`ai-agent/ai-agent/src/main/java/com/vn/agent/`**:
- Purpose: Add-on Jmix module configuration and (future) code.
- Contains: `AIConfiguration.java` — `@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})`; currently only exposes view-controller/action scanners for `com.vn.agent`.

**`ai-agent/ai-agent/src/main/resources/com/vn/agent/`**:
- Purpose: Add-on Jmix resources.
- Contains: `module.properties` (loaded via `@PropertySource`), `menu.xml` (contributes `AI` menu), `messages.properties`.

**`ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/`**:
- Purpose: Spring Boot 3 auto-configuration package.
- Contains: `AIAutoConfiguration.java` (`@AutoConfiguration @Import(AIConfiguration.class)`).

**`ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/`**:
- Purpose: Spring Boot 3 auto-configuration discovery.
- Contains: `org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing `com.vn.autoconfigure.agent.AIAutoConfiguration`.

**`ai-agent/ai-agent/src/test/`**:
- Purpose: Add-on integration tests.
- Contains: `AITest` (`@SpringBootTest contextLoads`), `AITestConfiguration` (embedded HSQL DataSource), `test-app.properties`, empty `liquibase/changelog.xml` for test schema.

**`.planning/codebase/`**:
- Purpose: Output directory for GSD codebase-mapper documents (this folder).
- Generated: Yes, by `/gsd-map-codebase`.

**`.jmix/hsqldb/`**:
- Purpose: Local HSQLDB database files for dev runtime.
- Generated: Yes. Committed: No (in `.gitignore` typically).

## Key File Locations

**Entry Points:**
- `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java` — Spring Boot `main()`, Vaadin shell config, primary `DataSource` bean.
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — add-on auto-configuration.

**Configuration:**
- `jmix-app/src/main/resources/application.properties` — datasource URL, Liquibase changelog path, `jmix.ui.*`, default login credentials, logging levels.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — add-on menu + locales.
- `build.gradle` (root, `jmix-app`, `ai-agent`, `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`) — build config.
- `settings.gradle` (root) — composite `includeBuild` declarations.

**Core Logic:**
- Entities: `jmix-app/src/main/java/com/vn/jmixapp/entity/`
- Views: `jmix-app/src/main/java/com/vn/jmixapp/view/<feature>/` + XML in `jmix-app/src/main/resources/com/vn/jmixapp/view/<feature>/`
- Security: `jmix-app/src/main/java/com/vn/jmixapp/security/`
- Add-on module: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`

**Testing:**
- Add-on tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/`
- Add-on test resources: `ai-agent/ai-agent/src/test/resources/com/vn/agent/`
- Host `jmix-app` currently has no `src/test/` tree.

**Menus & i18n:**
- `jmix-app/src/main/resources/com/vn/jmixapp/menu.xml`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`
- `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties` + `messages_vi.properties`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`

**Database Schema:**
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` (master)
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/010-init-user.xml` (seed)

## Naming Conventions

**Java packages:**
- Host app: `com.vn.jmixapp` root, with `.entity`, `.security`, `.view.<feature>` sub-packages.
- Add-on module: `com.vn.agent`.
- Add-on starter: `com.vn.autoconfigure.agent`.

**Java classes:**
- Entities: singular PascalCase (`User`).
- Views: `<Entity><Purpose>View` (`UserListView`, `UserDetailView`); shell/auth: `MainView`, `LoginView`.
- Roles: `<Descriptor>Role` interface annotated `@ResourceRole` (`FullAccessRole`, `UiMinimalRole`).
- Repositories: `<Descriptor>UserRepository` (`DatabaseUserRepository`).
- Security configs: `<App>SecurityConfiguration` (`JmixAppSecurityConfiguration`).
- Jmix configuration classes: `<Prefix>Configuration` (`AIConfiguration`, `AIAutoConfiguration`).
- Application main: `<App>Application` (`JmixAppApplication`).

**XML descriptor files:** kebab-case with `-view.xml` suffix matched to controller name.
- `UserListView.java` -> `user-list-view.xml`
- `UserDetailView.java` -> `user-detail-view.xml`
- `MainView.java` -> `main-view.xml`
- `LoginView.java` -> `login-view.xml`

**View IDs (`@ViewController(id = "...")`):**
- Entity screens: `Entity.purpose` (`User.list`, `User.detail`).
- Standalone screens: plain PascalCase (`MainView`, `LoginView`).

**Route paths:**
- Entity lists: plural lowercase (`/users`).
- Entity details: plural + id param (`/users/:id`).
- Shell: empty (`""`).
- Login: `login`.

**Menu IDs:** lowercase dot-less identifiers (`application`, `AI`).

**Liquibase changelog files:** numeric prefix + kebab-case description (`010-init-user.xml`); alternative time-based hierarchy documented in `CLAUDE.md` (`YYYY/MM/DD-HHMMSS-entity.xml`).

**Gradle subproject build files:** Named after the project with `.gradle` extension (`ai-agent.gradle`, `ai-agent-starter.gradle`) per the `buildFileName` override in `ai-agent/settings.gradle`.

**Locale messages:** `messages_<locale>.properties` (host: `_en`, `_vi`); add-on uses single `messages.properties` (default locale).

**Resource roles:**
- `@ResourceRole(code = "...")` uses kebab-case codes (`system-full-access`, `ui-minimal`).

## Where to Add New Code

**New JPA Entity:**
- Java class: `jmix-app/src/main/java/com/vn/jmixapp/entity/<EntityName>.java` with `@JmixEntity` + `@Entity` + UUID id + `@JmixGeneratedValue` + `@Version` + `@InstanceName`.
- Liquibase: new changelog in `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/<NNN>-<descr>.xml` and ensure it is picked up by the `<includeAll>` in `changelog.xml`.
- Messages: add entity + attribute captions to `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties` AND `messages_vi.properties`.

**New View (list/detail pair):**
- Controller: `jmix-app/src/main/java/com/vn/jmixapp/view/<entity>/<Entity>ListView.java` and `<Entity>DetailView.java`, extending `StandardListView<T>` / `StandardDetailView<T>`.
- XML descriptor: `jmix-app/src/main/resources/com/vn/jmixapp/view/<entity>/<entity>-list-view.xml` and `<entity>-detail-view.xml`.
- Menu: add `<item view="Entity.list" title="msg://..."/>` to `jmix-app/src/main/resources/com/vn/jmixapp/menu.xml`.
- Messages: titles/labels in both locale files.
- Routes: use `layout = MainView.class` on list/detail routes; pattern `/entities` and `/entities/:id`.

**New Security Role:**
- File: `jmix-app/src/main/java/com/vn/jmixapp/security/<Name>Role.java` as an interface annotated with `@ResourceRole(name, code[, scope])` and policy annotations on no-arg methods.

**New Service / Business Logic:**
- Per `CLAUDE.md`, create a `service/` package under `com.vn.jmixapp` for Spring `@Service` beans using constructor injection and `DataManager` (not `EntityManager`). Business logic must not live in views.

**New Add-on Feature (in `ai-agent`):**
- Java: `ai-agent/ai-agent/src/main/java/com/vn/agent/...` — scanned automatically by `AIConfiguration.@ComponentScan` and the view/action configurations with base package `com.vn.agent`.
- Resources: `ai-agent/ai-agent/src/main/resources/com/vn/agent/...` (menu.xml, messages.properties, XML view descriptors matching the Java package layout).
- If new module dependencies are needed, update `ai-agent/ai-agent/ai-agent.gradle` and the `@JmixModule(dependsOn = ...)` list in `AIConfiguration`.

**New Spring Security Filter Chain:**
- Add a `@Bean @Order(JmixSecurityFilterChainOrder.CUSTOM) SecurityFilterChain` method to `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`.

**Tests:**
- Add-on integration tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/` using `@SpringBootTest` with `AITestConfiguration`.
- Host app tests (not yet present): create `jmix-app/src/test/java/com/vn/jmixapp/...` using `@SpringBootTest` for business logic and `@UiTest` for view tests (per `CLAUDE.md`).

## Special Directories

**`jmix-app/src/main/frontend/generated/`:**
- Purpose: Vaadin-generated TypeScript/JavaScript frontend imports and adapter code.
- Generated: Yes (by Vaadin during build).
- Committed: Contents currently staged in git, but editing is forbidden per `CLAUDE.md`. Also excluded from IntelliJ in `jmix-app/build.gradle` (`excludeDirs`).

**`jmix-app/src/main/bundles/`:**
- Purpose: Vaadin dev bundle artifacts (`dev.bundle`).
- Generated: Yes.
- Excluded from IntelliJ and `src/main/frontend/generated/`.

**`.jmix/hsqldb/`:**
- Purpose: On-disk HSQLDB database for dev runtime (`jdbc:hsqldb:file:.jmix/hsqldb/jmixapp`).
- Generated: Yes.
- Committed: No (local dev only).

**`build/` (root, `jmix-app/build/`, `ai-agent/*/build/`):**
- Purpose: Gradle build output.
- Generated: Yes. Committed: No.

**`.gradle/`:**
- Purpose: Gradle caches.
- Generated: Yes. Committed: No.

**`.idea/`, `.iml` files, `jmix-studio.xml`:**
- Purpose: IntelliJ IDEA and Jmix Studio project metadata.
- Committed: Yes (some files).

**`.planning/`:**
- Purpose: GSD planning artifacts (codebase map, phase plans).
- Generated: Yes, by GSD commands.

---

*Structure analysis: 2026-04-18*
