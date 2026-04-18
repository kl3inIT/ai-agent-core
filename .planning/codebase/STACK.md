# Technology Stack

**Analysis Date:** 2026-04-18

## Languages

**Primary:**
- Java 17 - All application code, Jmix entities, services, view controllers, and security configuration across `jmix-app/src/main/java/` and `ai-agent/ai-agent/src/main/java/`

**Secondary:**
- XML - View descriptors (`jmix-app/src/main/resources/com/vn/jmixapp/view/**/*.xml`), Liquibase changelogs (`jmix-app/src/main/resources/com/vn/jmixapp/liquibase/**`), menu config (`jmix-app/src/main/resources/com/vn/jmixapp/menu.xml`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`)
- Properties - Message bundles and application config (`jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties`, `messages_vi.properties`, `application.properties`)
- Groovy - Gradle build scripts (`build.gradle`, `settings.gradle`, `ai-agent.gradle`, `ai-agent-starter.gradle`, `jmix-app/build.gradle`)
- CSS - Vaadin theme styling (`jmix-app/src/main/frontend/themes/jmix-app/*.css`)
- HTML - Frontend shell (`jmix-app/src/main/frontend/index.html`)

## Runtime

**Environment:**
- Java 17 (JVM) — required by Jmix 2.8 / Spring Boot 3
- Vaadin Flow server-side UI runtime embedded in the Spring Boot application

**Package Manager:**
- Gradle 8.14.4 (via wrapper in `gradle/wrapper/gradle-wrapper.properties`)
- Composite build: root `settings.gradle` uses `includeBuild 'jmix-app'` and `includeBuild 'ai-agent'`
- Lockfile: not present (no `gradle.lockfile`)

## Frameworks

**Core:**
- Jmix 2.8.0 - Low-code business application framework (BOM `io.jmix:jmix-bom:2.8.0`, Gradle plugin `io.jmix:2.8.0` declared in `jmix-app/build.gradle` and `ai-agent/build.gradle`)
- Spring Boot 3 - Application container (pulled transitively via Jmix BOM; `spring-boot-starter-web` declared in `jmix-app/build.gradle`)
- Vaadin Flow - Server-driven UI framework; `com.vaadin` Gradle plugin applied in `jmix-app/build.gradle`; `@Theme`, `@Push`, `@PWA` used in `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`
- EclipseLink - JPA provider via `io.jmix.data:jmix-eclipselink-starter`
- Spring Security - Pulled through `io.jmix.security:jmix-security-starter` and configured in `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`
- Liquibase - Database migrations; master changelog at `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml`

**Testing:**
- JUnit 5 (JUnit Platform) - `useJUnitPlatform()` in `jmix-app/build.gradle` and `ai-agent/ai-agent/ai-agent.gradle`; `junit-vintage-engine` explicitly excluded
- Spring Boot Test - `org.springframework.boot:spring-boot-starter-test`
- Jmix FlowUI Test Assist - `io.jmix.flowui:jmix-flowui-test-assist` for `@UiTest` (see `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java`)

**Build/Dev:**
- Gradle 8.14.4 wrapper
- `io.jmix` Gradle plugin 2.8.0 - Jmix project setup and entity enhancement
- `com.vaadin` plugin - Vaadin bundle/frontend build (`optimizeBundle = false`)
- `org.springframework.boot` plugin - Boot packaging / `bootRun`
- `org.jetbrains.gradle.plugin.idea-ext` 1.1.9 - IntelliJ IDEA project metadata

## Key Dependencies

**Critical (Jmix starters declared in `jmix-app/build.gradle`):**
- `io.jmix.core:jmix-core-starter` - Jmix core runtime
- `io.jmix.data:jmix-eclipselink-starter` - JPA/EclipseLink ORM integration
- `io.jmix.security:jmix-security-starter` - Security core
- `io.jmix.security:jmix-security-flowui-starter` - UI security integration
- `io.jmix.security:jmix-security-data-starter` - Data-level security (row/attribute policies)
- `io.jmix.localfs:jmix-localfs-starter` - Local filesystem file storage
- `io.jmix.flowui:jmix-flowui-starter` - Jmix Vaadin Flow UI
- `io.jmix.flowui:jmix-flowui-data-starter` - UI data binding
- `io.jmix.flowui:jmix-flowui-themes` - Jmix themes
- `io.jmix.datatools:jmix-datatools-starter` + `jmix-datatools-flowui-starter` - Admin/data tooling
- `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` - Local AI agent add-on (composite-built from `ai-agent/`)

**Add-on (`ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`):**
- `io.jmix.core:jmix-core`, `io.jmix.data:jmix-data`, `io.jmix.flowui:jmix-flowui-starter`, `io.jmix.flowui:jmix-flowui-themes`
- `org.springframework.boot:spring-boot-autoconfigure` — registers `com.vn.autoconfigure.agent.AIAutoConfiguration` via `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Infrastructure:**
- `org.hsqldb:hsqldb` - Embedded HSQLDB database (runtimeOnly in main; testRuntimeOnly in add-on)
- `com.google.common.base.Strings` (Guava) - Used in `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java` (provided transitively)

**Excluded (explicit in both `jmix-app/build.gradle` and `ai-agent/ai-agent/ai-agent.gradle`):**
- `com.vaadin:hilla`, `com.vaadin:hilla-dev`, `com.vaadin:copilot` - Hilla/copilot features disabled (`hilla.active=false` in `jmix-app/gradle.properties`)

## Configuration

**Environment:**
- Application properties: `jmix-app/src/main/resources/application.properties`
  - Datasource: `main.datasource.url=jdbc:hsqldb:file:.jmix/hsqldb/jmixapp`, user `sa`, empty password
  - Liquibase: `main.liquibase.change-log=com/vn/jmixapp/liquibase/changelog.xml`
  - UI: `jmix.ui.login-view-id=LoginView`, `jmix.ui.main-view-id=MainView`, `jmix.ui.menu-config=com/vn/jmixapp/menu.xml`, `jmix.ui.composite-menu=true`
  - Locales: `jmix.core.available-locales=vi,en`
  - Default credentials: `ui.login.defaultUsername=admin`, `ui.login.defaultPassword=admin`
  - Vaadin: `vaadin.launch-browser=false`
  - Logging levels for `eclipselink.logging.sql`, `io.jmix.core.datastore`, `io.jmix.core.AccessLogger`, `io.jmix`, `org.springframework.security`, `org.atmosphere`
- Test properties: `jmix-app/src/test/resources/application-test.properties` — isolated HSQLDB file `jdbc:hsqldb:file:.jmix/hsqldb/jmixapp_test`
- Add-on module properties: `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — `jmix.ui.menu-config=com/vn/agent/menu.xml`, `jmix.core.available-locales=en`
- No `.env` files present in repository

**Build:**
- `build.gradle` (root) - defines `group = 'com.vn'`, `version = '0.0.1-SNAPSHOT'`; no subproject config (composite build)
- `jmix-app/build.gradle` - Jmix application module
- `ai-agent/build.gradle` - applies `java-library`, `maven-publish`, `io.jmix` to subprojects; publishes to `https://myrepo/releases/`
- `gradle.properties` (both) - `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`; `jmix-app/gradle.properties` adds `hilla.active=false`
- `settings.gradle` (root composite) - `rootProject.name = 'ai-agent-core'`, includes `jmix-app` and `ai-agent` builds
- `ai-agent/settings.gradle` - `rootProject.name = 'ai-agent-addon'`, includes `ai-agent` and `ai-agent-starter`

**Repositories:**
- `mavenCentral()`
- `https://global.repo.jmix.io/repository/public` (Jmix artifacts)

## Platform Requirements

**Development:**
- Java 17 JDK
- Gradle 8.14.4 (provided via wrapper)
- IntelliJ IDEA recommended (Jmix Studio project file `jmix-studio.xml` present)
- Node.js toolchain provided by Vaadin plugin for frontend bundling

**Production:**
- Spring Boot executable JAR (built via `bootJar`)
- Default HTTP port 8080
- Requires writable working directory for HSQLDB file storage (`.jmix/hsqldb/`) and local filesystem storage (Jmix LocalFS)
- Publishing target configured as Maven repository at `https://myrepo/releases/` (placeholder) in `ai-agent/build.gradle`

---

*Stack analysis: 2026-04-18*
