# Codebase Concerns

**Analysis Date:** 2026-04-18

## Tech Debt

**AI-Agent module is an empty skeleton:**
- Issue: The `ai-agent` add-on, which is the apparent purpose of this repository (name: `ai-agent-core`), contains no business logic, no AI-related classes, no services, no entities. Only boilerplate Jmix module scaffolding (`AIConfiguration`, `AIAutoConfiguration`).
- Files: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`, `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`
- Impact: The project name/intent and the implementation are mismatched. Feature is unimplemented.
- Fix approach: Build out AI integration (LLM client, prompt templates, tools, services) under `com.vn.agent`, register view controllers, actions, and add entities/menu entries.

**Stub menu with no actionable items:**
- Issue: `menu.xml` in the `ai-agent` add-on declares a top-level menu `AI` with no child items or views.
- Files: `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`
- Impact: Menu appears in host apps but leads nowhere.
- Fix approach: Add view entries tied to real agent features, or remove until features exist.

**Duplicate/placeholder publishing repository configuration:**
- Issue: `ai-agent/build.gradle` publishes to a hard-coded placeholder URL `https://myrepo/releases/` with default credentials `admin/admin` and `allowInsecureProtocol = true`.
- Files: `ai-agent/build.gradle` (lines 48-55)
- Impact: Any `publish` task will fail or leak artifacts to a non-existent/insecure endpoint. Defaults represent leakable credentials.
- Fix approach: Replace with the real Maven repo URL; require `uploadUser`/`uploadPassword` Gradle properties (no defaults); remove `allowInsecureProtocol`.

**Nested git projects / composite build coupling:**
- Issue: Root `settings.gradle` uses `includeBuild 'jmix-app'` and `includeBuild 'ai-agent'`, while each subproject also contains its own Gradle wrapper, `.gradle`, and `.idea` directories. Duplicated wrappers and build state.
- Files: `settings.gradle`, `jmix-app/settings.gradle`, `ai-agent/settings.gradle`, `jmix-app/gradlew*`, `ai-agent/gradlew*`
- Impact: Inconsistency risk between wrapper versions; larger repo size; confusion about which `gradlew` to invoke.
- Fix approach: Standardize on the root wrapper; remove duplicated wrappers and `.idea` copies; document the composite build model in README.

**Default test Jmix module id collision risk:**
- Issue: `AITestConfiguration` uses id `com.vn.agent.test` with `dependsOn = AIConfiguration.class`. Fine for now but there is no actual test coverage of agent behavior.
- Files: `ai-agent/ai-agent/src/test/java/com/vn/agent/AITest.java` (only `contextLoads()`), `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java`
- Impact: Test suite signals nothing meaningful about correctness.
- Fix approach: Add real tests once agent features land.

**Missing Vietnamese locale in ai-agent add-on:**
- Issue: Add-on declares only `en` locale (`jmix.core.available-locales=en` in `module.properties`) and ships `messages.properties` only. The host app declares both `vi,en`.
- Files: `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`
- Impact: Mixed UI languages when add-on menu is shown in Vietnamese locale.
- Fix approach: Add `messages_vi.properties` with translations; align `available-locales` with host expectations (or rely on host).

**Hard-coded add-on version pin:**
- Issue: `jmix-app/build.gradle` depends on `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` while both projects carry version `0.0.1-SNAPSHOT` (root `ai-agent/build.gradle`, `build.gradle`).
- Files: `jmix-app/build.gradle` (line 25), `ai-agent/build.gradle` (line 11)
- Impact: No version catalog/BOM; upgrades must be done by string search.
- Fix approach: Extract versions to `gradle.properties` or a Gradle version catalog.

## Known Bugs

**Missing `@EditedEntityContainer` value vs descriptor mismatch risk:**
- Symptoms: `UserDetailView` uses `@EditedEntityContainer("userDc")`; any rename of the data container in `user-detail-view.xml` silently breaks the view at runtime.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java`
- Trigger: Renaming the data container in XML without updating the annotation.
- Workaround: None; keep names in sync.

**`HasTimeZone.isAutoTimeZone()` always returns true:**
- Symptoms: Setting `timeZoneId` on a `User` has no effect because `isAutoTimeZone()` is unconditionally `true`, which typically overrides the explicit zone.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/entity/User.java` (lines 168-171)
- Trigger: Any user persisting a non-null `timeZoneId` and expecting it to be honored.
- Workaround: Fix the method to return a field-backed flag, or remove the `timeZoneId` column from the UI.

## Security Considerations

**Admin seed user with plaintext `{noop}admin` password:**
- Risk: Initial admin account is seeded with `{noop}admin`, meaning no password hashing. On any deployment that doesn't rotate the admin password immediately, credentials are effectively `admin/admin` in the clear.
- Files: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/010-init-user.xml` (lines 41-55)
- Current mitigation: None. Also, `application.properties` auto-fills login form with `admin/admin` in the UI.
- Recommendations: Replace seed with a bcrypt hash, force password change on first login, remove `ui.login.defaultUsername` / `ui.login.defaultPassword` outside dev profile, gate auto-fill behind a `dev` Spring profile.

**Default dev login credentials exposed in production config:**
- Risk: `ui.login.defaultUsername=admin` and `ui.login.defaultPassword=admin` live in `application.properties`, which is loaded in all environments. `LoginView` pre-fills them into the form.
- Files: `jmix-app/src/main/resources/application.properties` (lines 12-13), `jmix-app/src/main/java/com/vn/jmixapp/view/login/LoginView.java` (lines 55-85)
- Current mitigation: None.
- Recommendations: Move defaults to `application-dev.properties`, leave production blank.

**`/public/**` endpoints permit all with no CSRF / rate limit strategy documented:**
- Risk: `JmixAppSecurityConfiguration` opens `/public/**` to `permitAll()`. No handlers currently exist there, but the pattern is permanent.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`
- Current mitigation: No endpoints are mapped under `/public/**` yet.
- Recommendations: Document intended use; require explicit controllers under `/public/**` to enforce their own auth (e.g., signed tokens) and disable CSRF deliberately only for those specific matchers.

**HSQLDB file-based storage with embedded credentials:**
- Risk: Production-profile `main.datasource.url` defaults to a local HSQLDB file (`.jmix/hsqldb/jmixapp`) with username `sa` and empty password. Not suitable for production.
- Files: `jmix-app/src/main/resources/application.properties` (lines 1-3)
- Current mitigation: None.
- Recommendations: Externalize via env vars / `application-prod.properties`; require secure DB in non-dev profiles; add a startup check that refuses to boot with `sa`/empty password outside dev.

**Insecure artifact publishing (HTTP allowed):**
- Risk: `allowInsecureProtocol = true` with default credentials `admin/admin`.
- Files: `ai-agent/build.gradle` (lines 48-55)
- Current mitigation: None.
- Recommendations: Remove `allowInsecureProtocol`, require HTTPS, forbid default credentials.

**`@Secret` on password field but stored unsalted if seeded:**
- Risk: Seeded passwords bypass `PasswordEncoder`. Programmatic creation in `UserDetailView.onBeforeSave` does encode, but any direct insert (scripts/seed) bypasses this.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java` (line 77), `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/010-init-user.xml`
- Recommendations: Provide a utility for generating encoded seeds; add integration test that no `{noop}` prefixes exist in non-test profiles.

## Performance Bottlenecks

**`UserDetailView.onInit` loads all TimeZone IDs synchronously on every view open:**
- Problem: `TimeZone.getAvailableIDs()` returns ~600 entries; the combobox is populated in-memory each init.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java` (lines 47-50)
- Cause: No caching, no lazy loading, no filtering in the combo.
- Improvement path: Cache the list in a static field or service bean, or switch to a lazy-loaded/searchable combo.

**UI test tearDown deletes with `LIKE` query without transaction control:**
- Problem: `UserUiTest.tearDown()` scans `USER_` with `username like 'test-user-%'` after every test. Cheap now but scales linearly with accumulated orphan rows if tests crash mid-run.
- Files: `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java` (lines 85-90)
- Improvement path: Use `@Transactional` rollback test harness, or capture created IDs and remove by ID.

## Fragile Areas

**`ai-agent` add-on configuration is split across two modules with minimal code:**
- Files: `ai-agent/ai-agent/**`, `ai-agent/ai-agent-starter/**`
- Why fragile: Refactoring package `com.vn.agent` requires coordinated changes in `AIConfiguration`, `AIAutoConfiguration`, `module.properties`, `menu.xml`, and the `.imports` file. No tests guard this.
- Safe modification: Add integration tests that verify the starter auto-configures the module before refactoring.
- Test coverage: Only a `contextLoads()` test.

**Seed data embedded in changelog with per-DBMS `dbms=` duplication:**
- Files: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/010-init-user.xml` (lines 41-77)
- Why fragile: Two parallel `<insert>` blocks (UUID with/without dashes) must stay in sync for all seeded rows.
- Safe modification: Use Liquibase preconditions / parameters, or a Java migration, or seed via a `CommandLineRunner` gated by profile.

**`MainView.isSubstituted` dereferences authenticated user without null check:**
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/main/MainView.java` (lines 114-117)
- Why fragile: `currentUserSubstitution.getAuthenticatedUser()` is assumed non-null. If called during a window where auth is not yet set up (edge case rendering), NPE could occur.
- Safe modification: Guard with null check; return `false` when no authenticated user.

**`JmixAppApplication` defines `@Primary` `DataSource` bean manually:**
- Files: `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java` (lines 35-47)
- Why fragile: Overriding Spring Boot's auto-configured DataSource with a manually assembled one tied to `main.datasource.*` properties bypasses standard `spring.datasource.*` conventions. Changes to Spring Boot autoconfig may conflict.
- Safe modification: Prefer `spring.datasource.*` and let Jmix wire via its conventions; if custom prefix needed, add a validation test.

## Scaling Limits

**HSQLDB file-based datasource:**
- Current capacity: Single-process, file-locked HSQLDB (`jdbc:hsqldb:file:.jmix/hsqldb/jmixapp`).
- Limit: No concurrent application instances; limited by HSQLDB memory/disk; not ACID-robust under crashes.
- Scaling path: Switch to PostgreSQL (or another server DB) via profile-specific properties; add Flyway-compatible seed tasks.

**Single HSQLDB file shared dev/prod by default:**
- Current capacity: One dev-style DB.
- Limit: No separation of test/prod data; HSQLDB file path is under `.jmix/` at runtime working directory.
- Scaling path: Profile-based datasource selection; ephemeral DB for CI.

## Dependencies at Risk

**Jmix 2.8.0 and Gradle plugin 2.8.0 hard-pinned in multiple places:**
- Risk: Version appears in `build.gradle` (buildscript classpath), `jmix-app/build.gradle` (plugin and BOM), `ai-agent/build.gradle` (BOM).
- Impact: Upgrade requires touching 3+ files; drift possible.
- Migration plan: Centralize via `gradle.properties` (`jmixVersion=2.8.0`) or Gradle version catalog.

**Vaadin Hilla / copilot forcibly excluded:**
- Files: `jmix-app/build.gradle` (lines 49-53), `ai-agent/ai-agent/ai-agent.gradle` (lines 18-22)
- Impact: Any dependency that transitively requires Hilla will silently lose functionality. If Jmix/Vaadin upgrades make Hilla mandatory, build will break unexpectedly.
- Migration plan: Track Jmix release notes; re-evaluate exclusions per upgrade.

**HSQLDB runtime-only:**
- Risk: HSQLDB is explicitly `runtimeOnly` in `jmix-app/build.gradle` (line 41) and `testRuntimeOnly` in the add-on; acceptable only for dev/test. Production deployment configuration is absent.
- Migration plan: Add PostgreSQL (or similar) as an optional profile-backed dependency.

## Missing Critical Features

**No AI functionality despite repository name:**
- Problem: `com.vn.agent` package contains zero AI/LLM logic — no `ChatClient`, no prompt management, no tool/function calling, no vector store.
- Blocks: The entire value proposition of an "AI agent" addon.

**No production profile / environment separation:**
- Problem: Only `application.properties` and `application-test.properties`. No `application-dev.properties` or `application-prod.properties`; default credentials and file-DB are always on.
- Blocks: Safe deployment outside a local developer workstation.

**No CI configuration:**
- Problem: No `.github/workflows`, `.gitlab-ci.yml`, or similar.
- Blocks: Automated tests, build validation, artifact publication.

**No logging of AI operations:**
- Problem: No structured logging/metrics scaffolding in `ai-agent` module.
- Blocks: Auditability, cost tracking, debugging of future LLM calls.

**No `.gitignore` discipline on generated artifacts in add-on:**
- Problem: The `ai-agent/.gradle/` directory is tracked in git status (see repo root git status showing `ai-agent/.gradle/buildOutputCleanup/cache.properties`).
- Blocks: Repo cleanliness; may leak local build state.

## Test Coverage Gaps

**`ai-agent` add-on has only a context-loads smoke test:**
- What's not tested: Any agent behavior, bean wiring beyond `contextLoads()`, auto-configuration activation from the starter.
- Files: `ai-agent/ai-agent/src/test/java/com/vn/agent/AITest.java`
- Risk: Silent regressions in auto-configuration; no regression net for future AI features.
- Priority: High (once AI features exist).

**`UserListView` has no dedicated controller tests:**
- What's not tested: Data grid population, navigation, filters.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserListView.java`
- Risk: Grid column/action regressions go unnoticed.
- Priority: Medium.

**`UserDetailView` business logic (password confirmation, encoding, new-entity flag) is only covered by a happy-path UI test:**
- What's not tested: `onValidation` mismatch path, `onAfterSave` notification branch when editing existing user, unicode/long passwords.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java`, `jmix-app/src/test/java/com/vn/jmixapp/user/UserUiTest.java`
- Risk: Password confirmation errors may regress silently; no test for editing existing users.
- Priority: High (security-sensitive code path).

**No security role tests:**
- What's not tested: `FullAccessRole`, `UiMinimalRole` policy enforcement.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/security/FullAccessRole.java`, `jmix-app/src/main/java/com/vn/jmixapp/security/UiMinimalRole.java`
- Risk: Accidental policy widening/narrowing not caught.
- Priority: High.

**No tests for `JmixAppSecurityConfiguration` `/public/**` matcher:**
- What's not tested: That `permitAll()` truly applies and no private endpoints leak through.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`
- Risk: Future endpoints placed under `/public/**` could unintentionally skip auth without a guardrail test.
- Priority: Medium.

**`MainView` rendering helpers (`userMenuButtonRenderer`, `userMenuHeaderRenderer`, `isSubstituted`) are untested:**
- What's not tested: Null user handling, substitution display, avatar/name composition.
- Files: `jmix-app/src/main/java/com/vn/jmixapp/view/main/MainView.java`
- Risk: NPEs on edge auth states (see Fragile Areas).
- Priority: Medium.

---

*Concerns audit: 2026-04-18*
