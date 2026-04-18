# External Integrations

**Analysis Date:** 2026-04-18

## APIs & External Services

**Third-party APIs:** Not detected. No external HTTP API clients, SDKs, or outbound service integrations are present in the current source tree. The `ai-agent` add-on module (`ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`) is currently a scaffolding Jmix module that registers view controllers and actions; no LLM/AI provider SDKs (OpenAI, Anthropic, Azure AI, etc.) are declared in `ai-agent/ai-agent/ai-agent.gradle` or `jmix-app/build.gradle` at this time.

**Package Repositories:**
- Maven Central (`mavenCentral()`) - Public Java dependencies
- Jmix Public Repository (`https://global.repo.jmix.io/repository/public`) - Jmix framework artifacts; declared in `jmix-app/build.gradle` and `ai-agent/build.gradle`
- Gradle Plugin Portal (`gradlePluginPortal()`) - Plugin resolution, declared in `ai-agent/build.gradle` buildscript block

## Data Storage

**Databases:**
- HSQLDB (embedded, file-based)
  - Driver artifact: `org.hsqldb:hsqldb` (runtimeOnly)
  - Main connection: `jdbc:hsqldb:file:.jmix/hsqldb/jmixapp` (from `jmix-app/src/main/resources/application.properties`, property `main.datasource.url`)
  - Test connection: `jdbc:hsqldb:file:.jmix/hsqldb/jmixapp_test` (from `jmix-app/src/test/resources/application-test.properties`)
  - Credentials: username `sa`, empty password (development defaults)
  - ORM/Client: EclipseLink JPA via `io.jmix.data:jmix-eclipselink-starter`; access through Jmix `DataManager`
  - Connection pool: HikariCP (Spring Boot default; configured via `main.datasource.hikari` prefix in `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`)
  - Schema management: Liquibase; master changelog `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` includes Jmix core/flowui-data/security-data changelogs plus local `com/vn/jmixapp/liquibase/changelog/010-init-user.xml`

**File Storage:**
- Jmix LocalFS (local filesystem) - `io.jmix.localfs:jmix-localfs-starter` declared in `jmix-app/build.gradle`
- Default storage directory controlled by Jmix LocalFS (under working directory); no cloud object storage configured

**Caching:**
- Not explicitly configured. Jmix core provides internal caches; no external cache (Redis, Memcached, Hazelcast) dependency declared.

## Authentication & Identity

**Auth Provider:**
- Jmix built-in security (in-database users)
  - Core: `io.jmix.security:jmix-security-starter`
  - UI integration: `io.jmix.security:jmix-security-flowui-starter`
  - Data-level security: `io.jmix.security:jmix-security-data-starter`
  - User repository: `jmix-app/src/main/java/com/vn/jmixapp/security/DatabaseUserRepository.java`
  - User entity: `jmix-app/src/main/java/com/vn/jmixapp/entity/User.java`
  - Roles: `jmix-app/src/main/java/com/vn/jmixapp/security/FullAccessRole.java`, `UiMinimalRole.java`
  - Custom filter chain: `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java` — exposes `/public/**` with `permitAll()` via a `SecurityFilterChain` ordered at `JmixSecurityFilterChainOrder.CUSTOM`
  - Login view: `jmix-app/src/main/java/com/vn/jmixapp/view/login/LoginView.java` + `login-view.xml`
  - Default bootstrap credentials: `admin` / `admin` (`ui.login.defaultUsername`, `ui.login.defaultPassword`)

**External identity providers (OIDC / SAML / OAuth2):** Not configured. No `jmix-oidc` or `jmix-authserver` starters are present.

## Monitoring & Observability

**Error Tracking:**
- Not configured. No Sentry, Rollbar, or similar SDK dependency.

**Logs:**
- SLF4J via Spring Boot logging (Logback default)
- Log level configuration in `jmix-app/src/main/resources/application.properties`:
  - `logging.level.eclipselink.logging.sql=info`
  - `logging.level.io.jmix.core.datastore=info`
  - `logging.level.io.jmix.core.AccessLogger=debug`
  - `logging.level.io.jmix=info`
  - `logging.level.org.springframework.security=info`
  - `logging.level.org.atmosphere=warn`
- ANSI colors enabled: `spring.output.ansi.enabled=always`

**Metrics / Tracing:** Not configured. No Micrometer exporters, OpenTelemetry, Prometheus, or APM integrations in dependencies.

## CI/CD & Deployment

**Hosting:** Not specified. No Dockerfile, Kubernetes manifests, or cloud platform config files (Heroku, AWS, GCP, Azure) detected in the repository.

**CI Pipeline:** Not detected. No `.github/workflows/`, `.gitlab-ci.yml`, `Jenkinsfile`, `azure-pipelines.yml`, or similar CI configuration present.

**Artifact publishing:**
- Maven publishing configured in `ai-agent/build.gradle` — target repository `https://myrepo/releases/` (placeholder URL) with `uploadUser` / `uploadPassword` Gradle properties, `allowInsecureProtocol = true`
- Default credentials fall back to `admin` / `admin` if properties unset

## Environment Configuration

**Required runtime properties (defined in `jmix-app/src/main/resources/application.properties`):**
- `main.datasource.url`, `main.datasource.username`, `main.datasource.password` - Primary datasource
- `main.liquibase.change-log` - Liquibase master changelog path
- `jmix.ui.login-view-id`, `jmix.ui.main-view-id`, `jmix.ui.menu-config`, `jmix.ui.composite-menu`
- `jmix.core.available-locales` - `vi,en`
- `ui.login.defaultUsername`, `ui.login.defaultPassword`
- `vaadin.launch-browser`

**Gradle build properties (optional, `ai-agent/build.gradle` publish block):**
- `uploadUser`, `uploadPassword` - Maven publish credentials

**Secrets location:**
- No secret management integration detected. No `.env` files in the repository. Database password is empty in properties files (development-only HSQLDB).

## Webhooks & Callbacks

**Incoming:**
- `/public/**` - Public (unauthenticated) URL path opened in `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java` as a template for custom endpoints. No concrete controllers mapped under it currently.
- Vaadin/Atmosphere server push endpoint (enabled via `@Push` in `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`)
- Standard Jmix Flow UI HTTP endpoints provided by `jmix-flowui-starter`

**Outgoing:** Not detected. No webhook senders, message queue producers, or scheduled HTTP callbacks in the current code.

---

*Integration audit: 2026-04-18*
