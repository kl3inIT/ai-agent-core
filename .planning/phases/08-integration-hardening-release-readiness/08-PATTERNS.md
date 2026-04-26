# Phase 8: Integration Hardening & Release Readiness — Pattern Map

**Mapped:** 2026-04-26
**Files analyzed:** 17 new + 6 modified
**Analogs found:** 21 / 23 (2 with no analog: `consumer-smoke/`, `.github/workflows/`)

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java` | test (security) | request-response | `ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java` | exact (same package, same `permittedFor` shape) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/security/RagRoleFilterNegativeTest.java` | test (security) | request-response | `ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java` + `RetrievalFilterBuilder.java` (SUT) | role-match (same security package; different SUT) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/security/CrossUserConversationAccessTest.java` | test (security) | request-response | `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java` | exact (same SUT — `ChatService` boundary) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java` | test (perf) | CRUD | `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java` (`@SpringBootTest` shape) | role-match (no perf-test analog yet) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/FindRecordsLimitCapTest.java` | test (perf) | CRUD | `BuiltInDataTools.java` (SUT) + `AuditDurabilityTest.java` (shape) | role-match |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java` | test (live) | request-response | `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java` | exact (same package + tag + gating) |
| `ai-agent/ai-agent/src/test/resources/golden-questions.yaml` | test fixture (yaml) | file-I/O | `ai-agent/ai-agent/src/test/resources/eval/param-profile-fixtures.yaml` | role-match (Jackson YAML loading) |
| `…/tools/PromptInjectionHarnessTest.java` (EXTEND) | test (security/tools) | request-response | self (existing 3 methods) | exact (extending existing file) |
| `…/audit/AuditDurabilityTest.java` (EXTEND) | test (audit) | event-driven | self (existing 2 methods) | exact (extending existing file) |
| `consumer-smoke/consumer-smoke.gradle` (or `build.gradle`) | gradle build | config | `jmix-app/build.gradle` + `ai-agent/ai-agent/ai-agent.gradle` | role-match |
| `consumer-smoke/settings.gradle` | gradle settings | config | `ai-agent/settings.gradle` | exact |
| `consumer-smoke/src/main/java/com/vn/consumersmoke/ConsumerSmokeApplication.java` | host bootstrap | config | `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java` | exact |
| `consumer-smoke/src/main/resources/application.properties` | config | config | `jmix-app/src/main/resources/application.properties` | exact |
| `consumer-smoke/src/test/java/com/vn/consumersmoke/BootSmokeTest.java` | test (smoke) | request-response | `ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java` (`@SpringBootTest` + `applicationContext` access) | role-match |
| `ai-agent/README.md` (or `ai-agent/ai-agent/README.md`) | docs | static | `docs/consumer-smoke.md` (existing operator doc) | role-match |
| `CHANGELOG.md` (repo root) | docs | static | n/a (Keep-a-Changelog v1.1.0 spec is the template) | no analog |
| `ai-agent/gradle.properties` (MODIFY) | config | config | self | exact |
| `ai-agent/build.gradle` (MODIFY — version + verify publish) | gradle build | config | self (existing `subprojects` block lines 14–92) | exact |
| `ai-agent/ai-agent/ai-agent.gradle` (MODIFY — datasource-proxy testImpl) | gradle build | config | self (existing testImpl block lines 64–96) | exact |
| `ai-agent/ai-agent-starter/ai-agent-starter.gradle` (REVIEW) | gradle build | config | self | exact |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java` | test config | config | `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/TestUsersConfiguration.java` | exact |
| `.github/workflows/ai-agent-ci.yml` | CI | event-driven | n/a (no `.github/` directory yet) | no analog |
| `.github/workflows/ai-agent-live.yml` | CI | event-driven | n/a | no analog |
| `.github/workflows/ai-agent-publish.yml` | CI | event-driven | n/a | no analog |

---

## Pattern Assignments

### `…/security/FilteredSchemaAndExecutionDenialTest.java` (test, security)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/security/AdminViewAccessTest.java`

**Boilerplate / annotations** (lines 27–32 of analog):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
class AdminViewAccessTest {

    @Autowired AccessManager accessManager;
    @Autowired SystemAuthenticator systemAuthenticator;
```

**Role-binding helper** (lines 37–43 of analog) — copy pattern, swap to schema-access call:
```java
private boolean permittedFor(String username, String viewId) {
    return systemAuthenticator.withUser(username, () -> {
        UiShowViewContext ctx = new UiShowViewContext(viewId);
        accessManager.applyRegisteredConstraints(ctx);
        return ctx.isPermitted();
    });
}
```

**Adapt for schema assertion** (NOT `EffectiveSchemaComputer.compute(...)` — that does not exist; use `CurrentUserSchemaAccess.getReadableSchema()` from `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java:44`):
```java
@Autowired CurrentUserSchemaAccess currentUserSchemaAccess;
@Autowired BuiltInDataTools builtInDataTools;

@Test
void restrictedUser_filteredSchema_excludesCustomer() {
    systemAuthenticator.withUser("carol", () -> {
        Map<MetaClass, Set<String>> schema = currentUserSchemaAccess.getReadableSchema();
        assertThat(schema.keySet().stream().map(MetaClass::getName))
                .doesNotContain("sample_Customer");
        return null;
    });
}
```

**SUT signature** (`CurrentUserSchemaAccess.java:44`):
```java
public Map<MetaClass, Set<String>> getReadableSchema()
public boolean canReadAttribute(MetaClass metaClass, String attributePath)
public boolean canReadEntity(MetaClass metaClass)
```

---

### `…/security/RagRoleFilterNegativeTest.java` (test, security)

**Analog:** `AdminViewAccessTest.java` (shape) + `RetrievalFilterBuilder.java` (SUT, lines 41–113 per RESEARCH).

Same `@SpringBootTest(classes = AITestConfiguration.class)` + `@ImportAutoConfiguration({AIAutoConfiguration, SpiDefaultsAutoConfiguration})` shell as above. Stub-vector-store import pattern from `AuditDurabilityTest.java:43`:
```java
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
```

Inject `RetrievalFilterBuilder`, run `withUser("alice", ...)` (only `AiAgentUserRole` per `TestUsersConfiguration:78`), assert filter expression excludes admin-tagged chunks.

---

### `…/security/CrossUserConversationAccessTest.java` (test, security)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java`

**Full boilerplate** (lines 26–37 of analog):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class OwnershipOpacityTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;
```

**Cross-user assertion pattern** (lines 38–57 of analog):
```java
@Test
void crossUserProbeReturnsSameExceptionAsMissingId() {
    systemAuthenticator.runWithSystem(() -> {
        var userA = chatService.ask("user-A", null, "hello");
        UUID nonExistent = UUID.randomUUID();

        ConversationNotFoundException eA = catchThrowableOfType(
                () -> chatService.ask("user-B", userA.conversationId(), "probe"),
                ConversationNotFoundException.class);
        ConversationNotFoundException eB = catchThrowableOfType(
                () -> chatService.ask("user-B", nonExistent, "probe"),
                ConversationNotFoundException.class);

        assertThat(eA.getClass()).isEqualTo(eB.getClass());
        assertThat(eA.getMessage()).isEqualTo(eB.getMessage());
    });
}
```

**Reuse:** the new test is essentially additional `@Test` methods on the same shape — read-only access + replay assertions for user A's conversation by user B.

---

### `…/performance/ToolQueryCountBaselineTest.java` & `FindRecordsLimitCapTest.java` (test, perf)

**Analog (shape):** `AuditDurabilityTest.java` `@SpringBootTest` boilerplate + `BuiltInDataTools.java` SUT.

**Boilerplate** (from `AuditDurabilityTest.java:38-49`):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AuditDurabilityTest {
    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;
```

**Limit-cap constant** (from `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolLimits.java:17,33`):
```java
public static final int MAX_LIMIT = 100;
// clamp:  return Math.min(requested, MAX_LIMIT);
```

So `FindRecordsLimitCapTest` invokes `findRecords("…", null, 999_999)` and asserts the response carries at most `ToolLimits.MAX_LIMIT` (100) rows.

**Query-count strategy (RESEARCH OQ-1 deviation):** project uses EclipseLink, NOT Hibernate — D-07 names `SessionFactory.getStatistics()` which does not exist here. Use `net.ttddyy:datasource-proxy:1.10.0` (RESEARCH §Standard Stack — Supporting). Add to `ai-agent/ai-agent/ai-agent.gradle` testImplementation block (alongside line 88 `asm:9.9` precedent).

```groovy
testImplementation 'net.ttddyy:datasource-proxy:1.10.0'
```

---

### `…/live/ChatServiceLiveSemanticGoldenSuiteTest.java` (test, live)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java` (entire file is 53 lines).

**Full boilerplate to copy** (lines 34–43 of analog):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveSemanticTest {

    @Autowired ChatService chatService;
```

**Note:** no `@Import(StubChatModelConfiguration)` — live tests need the real auto-configured OpenRouter `ChatModel`. The starter is on the test classpath via `testImplementation(project(':ai-agent-starter'))` per `ai-agent.gradle:76-78`.

**Existing call shape** (lines 45–52 of analog):
```java
@Test
void livePingProducesNonEmptyResponse() {
    var resp = chatService.ask("test-live-user", null, "Reply with the single word: pong");

    assertThat(resp.content()).isNotBlank();
    assertThat(resp.runId()).isNotNull();
    assertThat(resp.conversationId()).isNotNull();
}
```

**YAML loader pattern (parallel to existing eval fixtures):** `ai-agent/ai-agent/src/test/resources/eval/param-profile-fixtures.yaml` is loaded via `jackson-dataformat-yaml` already on classpath (`ai-agent.gradle:46`). Use `@ParameterizedTest` + `@MethodSource` per RESEARCH Pattern 3.

---

### `…/test/resources/golden-questions.yaml` (test fixture)

**Analog (shape):** `ai-agent/ai-agent/src/test/resources/eval/param-profile-fixtures.yaml` — Jackson YAML drive for parameterized tests.

**Schema** (locked by CONTEXT D-10 + RESEARCH Example 3):
```yaml
- id: schema-introspection
  prompt: "What entities can I query? List a few examples."
  anchors: ["customer", "order", "entity", "list_entities"]
  expectedTools: ["list_entities"]
  notes: "Capability 1 — schema introspection."
```

Six entries covering: schema-introspection, single-entity-find, multi-step-tool-chain, rag-retrieval, multi-turn-memory, refusal-injection (last one uses `notAnchors` for negative assertion). Full draft in 08-RESEARCH.md Example 3 (lines 698–737).

---

### `…/tools/PromptInjectionHarnessTest.java` (EXTEND — TEST-02)

**Analog:** self — file already has 3 `@Test` methods (lines 41–88).

**Extension pattern** (mirror existing test method `untrustedTextValueIsWrappedInDataDelimiters` at lines 40–57):
```java
@Test
void untrustedTextValueIsWrappedInDataDelimiters() {
    systemAuthenticator.runWithSystem(() -> {
        AiMessage entity = metadata.create(AiMessage.class);
        entity.setContent(ATTACK);

        String json = formatter.record(entity, metadata.getClass(AiMessage.class));

        assertThat(json).contains("<data>" + ATTACK + "</data>");
        int idx = json.indexOf(ATTACK);
        assertThat(json.substring(0, idx)).endsWith("<data>");
    });
}
```

New method (per RESEARCH Example 1) — poisoned-tool-result fixture with `Customer.notes`-style payload routed through `ToolResultFormatter.record(entity, metaClass)`. Same `@SpringBootTest`/`@ImportAutoConfiguration` already at lines 23–28 — DO NOT redeclare.

---

### `…/audit/AuditDurabilityTest.java` (EXTEND — TEST-03)

**Analog:** self — file already has 2 `@Test` methods (lines 51–134).

**Extension pattern** (mirror `toolAuditRowSurvivesOuterRollback` at lines 51–79):
```java
@Test
void toolAuditRowSurvivesOuterRollback() {
    UUID runId = UUID.randomUUID();

    systemAuthenticator.runWithSystem(() -> {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            auditWriter.writeToolCall(/*parentId*/ null, runId, "user-A", null,
                    "echo", "{\"in\":1}", "{\"out\":1}", 5L,
                    AiToolCallOutcome.SUCCESS, null, null);
            status.setRollbackOnly();
        });

        List<AiAuditEvent> rows = dataManager.load(AiAuditEvent.class)
                .query("select a from ai_AiAuditEvent a where a.runId = :rid")
                .parameter("rid", runId)
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo(AuditKind.TOOL);
    });
}
```

New method per RESEARCH Example 2 — same fixture, but with the tool-callback-throw path (outcome=ERROR, errorClass="RuntimeException"). All autowires already declared at lines 46–49.

---

### `…/test_support/NoCustomerReadRoleConfiguration.java` (test config)

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/TestUsersConfiguration.java`

**`@Configuration` + `@PostConstruct` initializer skeleton** (lines 42–104 of analog):
```java
@Configuration
public class TestUsersConfiguration {

    @Bean(name = "core_UserRepository")
    @ConditionalOnMissingBean(UserRepository.class)
    public UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    @ConditionalOnMissingBean(TestUserInitializer.class)
    public TestUserInitializer testUserInitializer(UserRepository userRepository,
                                                   RoleGrantedAuthorityUtils authorityUtils) {
        return new TestUserInitializer(userRepository, authorityUtils);
    }

    public static class TestUserInitializer {
        @PostConstruct
        void init() {
            if (!(userRepository instanceof InMemoryUserRepository repo)) return;
            repo.addUser(buildUser("alice", AiAgentUserRole.CODE, AiAgentUserRowLevelRole.CODE));
            // ...
        }

        private UserDetails buildUser(String username, String resourceRoleCode, String rowLevelRoleCode) {
            List<GrantedAuthority> auths = new ArrayList<>();
            auths.add(authorityUtils.createResourceRoleGrantedAuthority(resourceRoleCode));
            auths.add(authorityUtils.createRowLevelRoleGrantedAuthority(rowLevelRoleCode));
            return User.builder()
                    .username(username).password("{noop}password")
                    .authorities(auths).build();
        }
    }
}
```

**`@ResourceRole` interface form** — copy from production role analog `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java:21-31`:
```java
@ResourceRole(name = "AI Agent User", code = AiAgentUserRole.CODE)
public interface AiAgentUserRole {

    String CODE = "ai-agent-user";

    @EntityPolicy(entityClass = AiConversation.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = AiMessage.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
    void userAccess();
}
```

`NoCustomerReadRole` mirrors this shape — interface annotated with `@ResourceRole(name="No Customer Read", code="no-customer-read")`, NO `@EntityPolicy` on `Customer` (Jmix deny-by-default), but READ on `AiConversation`/`AiMessage` so the user can still chat. The role lives in the test-only file as a nested interface (per RESEARCH Pattern 1 sketch) and `init()` adds a `carol` user with this role's CODE.

**OQ-3 placement note:** PLAN.md should pin `com.vn.agent.test_support.NoCustomerReadRoleConfiguration` (matches `TestUsersConfiguration` precedent in the same package).

---

### `consumer-smoke/consumer-smoke.gradle` (gradle build)

**Analog:** `jmix-app/build.gradle` (Jmix host-app shape) + `ai-agent/settings.gradle` (Jmix `${p1.name}.gradle` build-file convention).

**Plugins + java toolchain** (from `jmix-app/build.gradle:1-18`):
```groovy
plugins {
    id 'io.jmix' version '2.8.1'
    id 'java'
    id 'org.jetbrains.gradle.plugin.idea-ext' version '1.1.9'
}

apply plugin: 'org.springframework.boot'
apply plugin: 'com.vaadin'

jmix {
    bomVersion = '2.8.1'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

group = 'com.vn'
version = '0.0.1-SNAPSHOT'

springBoot {
    mainClass = 'com.vn.consumersmoke.ConsumerSmokeApplication'
}
```

**Repositories — `mavenLocal()` MUST come first** (the entire point of the smoke is to consume freshly-published artifacts):
```groovy
repositories {
    mavenLocal()        // FIRST so just-published ai-agent / ai-agent-starter wins
    mavenCentral()
    maven { url = 'https://global.repo.jmix.io/repository/public' }
    maven { url = 'https://repo.spring.io/milestone' }
    maven { url = 'https://repo.spring.io/snapshot' }
}
```

**Dependency on the artifact under test** (parallel to `jmix-app/build.gradle:38`):
```groovy
dependencies {
    implementation 'com.vn:ai-agent-starter:1.0.0'   // from mavenLocal after :ai-agent:publishToMavenLocal

    implementation 'io.jmix.core:jmix-core-starter'
    implementation 'io.jmix.data:jmix-eclipselink-starter'
    implementation 'io.jmix.security:jmix-security-starter'
    implementation 'io.jmix.security:jmix-security-flowui-starter'
    implementation 'io.jmix.flowui:jmix-flowui-starter'
    implementation 'io.jmix.flowui:jmix-flowui-themes'

    runtimeOnly 'org.hsqldb:hsqldb'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

**`bootRunSmoke` task** (per RESEARCH Example 4 lines 793–802 — register a Test task scoped to `BootSmokeTest` class):
```groovy
tasks.register('bootRunSmoke', Test) {
    description = 'Boots the consumer app and asserts ai-agent-starter wires correctly.'
    group = 'verification'
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    filter { includeTestsMatching '*BootSmokeTest' }
}
```

This mirrors the `liveTest`/`integrationTest`/`evalTest` task pattern in `ai-agent/ai-agent/ai-agent.gradle:124-155`.

---

### `consumer-smoke/settings.gradle` (gradle settings)

**Analog:** `ai-agent/settings.gradle`

**Pattern** (entire file of analog is 8 lines):
```groovy
rootProject.name = 'ai-agent-addon'

include 'ai-agent'
include 'ai-agent-starter'

rootProject.children.each { p1 ->
    p1.buildFileName = "${p1.name}.gradle"
}
```

For `consumer-smoke`, single-project build:
```groovy
rootProject.name = 'consumer-smoke'
rootProject.buildFileName = 'consumer-smoke.gradle'   // optional — keep Jmix convention
```

**Wiring into root build** — root `settings.gradle` currently has (entire file):
```groovy
rootProject.name = 'ai-agent-core'

includeBuild 'jmix-app'
includeBuild 'ai-agent'
```

Phase 8 adds line 5 — `includeBuild 'consumer-smoke'` (Pattern A in RESEARCH Pitfall 10; recommended). Invocation becomes `./gradlew :consumer-smoke:bootRunSmoke`.

---

### `consumer-smoke/src/main/java/com/vn/consumersmoke/ConsumerSmokeApplication.java`

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`

**Minimal `@SpringBootApplication` + Vaadin shell** (lines 1–34 of analog):
```java
package com.vn.jmixapp;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.theme.Theme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Push
@Theme(value = "jmix-app")
@PWA(name = "Jmix App", shortName = "Jmix App", offline = false)
@SpringBootApplication
public class JmixAppApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(JmixAppApplication.class, args);
    }
}
```

For `consumer-smoke`, strip the Hikari `@Bean DataSource` machinery (lines 36–48) — single HSQLDB datasource via `application.properties` is enough; no agentstore split. Keep `@Push`/`@Theme`/`@SpringBootApplication`.

---

### `consumer-smoke/src/main/resources/application.properties`

**Analog:** `jmix-app/src/main/resources/application.properties`

**Minimal config** — copy and trim:
```properties
# HSQLDB single-store (no agentstore split for the smoke)
main.datasource.url=jdbc:hsqldb:mem:consumer-smoke
main.datasource.username=sa
main.datasource.password=

main.liquibase.change-log=com/vn/consumersmoke/liquibase/changelog.xml

jmix.ui.login-view-id=login
jmix.ui.main-view-id=main

# OPENROUTER not required — bootSmoke does not exercise live LLM
spring.ai.openai.api-key=${OPENROUTER_API_KEY:none}
```

**Critical:** `consumer-smoke` does NOT need `agentstore` split — its purpose is solely to prove the starter auto-configures the `ChatService` bean and registers `aiAgent.*` menu items. RAG ingest / pgvector are out of smoke scope.

---

### `consumer-smoke/src/test/java/com/vn/consumersmoke/BootSmokeTest.java`

**Analog (shape):** `AdminViewAccessTest.java` (Spring context introspection style).

**Bean-presence assertion (recommended over Vaadin route enumeration — RESEARCH Pattern 4 Note):**
```java
@SpringBootTest(classes = ConsumerSmokeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BootSmokeTest {

    @Autowired(required = false) ChatService chatService;
    @Autowired ApplicationContext applicationContext;

    @Test
    void chatServiceBeanIsWired() {
        assertThat(chatService)
                .as("ChatService must be auto-wired by ai-agent-starter")
                .isNotNull();
    }

    @Test
    void aiAgentMenuConfigRegistersAddonItems() {
        // The add-on registers menu.xml via @JmixModule classpath scanning. Asserting
        // the views are bean-resolvable proves the menu items map to real views.
        assertThat(applicationContext.containsBean("aiAgent.chat") ||
                   applicationContext.getBeanNamesForAnnotation(io.jmix.flowui.view.ViewController.class).length > 0)
                .isTrue();
    }
}
```

**Note:** the menu-presence assertion mechanism is in CONTEXT § Claude's Discretion. Recommended path = bean-class lookup (`getBean(ChatView.class)`) over Vaadin `RouteConfiguration` (per RESEARCH A2 fallback note).

---

### `ai-agent/README.md` (operator docs)

**Analog (shape):** `docs/consumer-smoke.md` (existing operator doc — same audience).

**Mandated sections per CONTEXT D-14:** Quick start, Required env vars, Configuration matrix, Entity/table ownership, Upgrade checklist, Air-gap notes, SPI cookbook (one runnable example per SPI: `ToolContributor`, `ContextContributor`, `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`).

**Skeleton drafted in 08-RESEARCH.md Example 5 (lines 805–953).** Tables for env vars + config matrix + entity ownership are concrete; SPI cookbook examples are minimal `@Component` + interface `@Override` blocks.

**i18n note (CLAUDE.md):** SPI cookbook code in README is English-only per CONTEXT § code_context. UI strings in production code remain bilingual EN+VI.

---

### `CHANGELOG.md` (repo root)

**Analog:** none in repo. Template is [Keep-a-Changelog v1.1.0](https://keepachangelog.com/en/1.1.0/).

**Section structure** (CONTEXT D-17):
```markdown
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-XX-XX

### Added
- ChatService + dual-layer chat memory (Phase 4)
- 6 read-only tool methods (list_entities, find_records, count_records, ...) (Phase 3)
- RAG retrieval with role-flag filter + admin bypass (Phase 5)
- ...

### Security
- Rotated dev Nexus credentials previously committed to gradle.properties (Phase 8)
```

**Backfill** (OQ-5 recommendation in 08-RESEARCH.md): aggregated `[1.0.0]` section with one bullet per ROADMAP success criterion (4–5 per phase), grouped under Added / Changed / Fixed / Security.

---

### `ai-agent/gradle.properties` (MODIFY — version + secrets)

**Current state** (3 lines):
```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8

nexusUsername=admin
nexusPassword=admin123
```

**Required Phase 8 changes (RESEARCH Pitfalls 3 + 4):**
1. Remove `nexusUsername`/`nexusPassword` lines (committed-secret hazard).
2. Add `version=1.0.0` (with the build.gradle redirect change below).
3. Add to `.gitignore` to prevent re-committing.

---

### `ai-agent/build.gradle` (MODIFY — version source + verify publish)

**Existing `subprojects` block** already wires `maven-publish` exactly per the user-supplied snippet (lines 63–81 of analog — D-18 is re-discovery per RESEARCH Pitfall 5):
```groovy
publishing {
    repositories {
        maven {
            name = 'CustomNexus'
            url = 'https://nexus.x2h.com.vn/repository/jmix-internal-snapshots/'
            credentials {
                username = project.findProperty('nexusUsername')
                password = project.findProperty('nexusPassword')
            }
            allowInsecureProtocol = true
        }
    }
    publications {
        javaMaven(MavenPublication) {
            artifactId = archName
            from components.java
        }
    }
}
```

**Hard-coded version line** (analog line 12):
```groovy
group = 'com.vn'
version = '0.0.1-SNAPSHOT'
```

**Phase 8 change** (per RESEARCH Pitfall 4 option A — preferred):
```groovy
group = 'com.vn'
version = project.findProperty('version') ?: '0.0.1-SNAPSHOT'
```

Combined with `version=1.0.0` in `gradle.properties`, this lets CI override the version on tag push (`-Pversion=1.0.0`).

**Pitfall 6 surfaced concern:** snapshot URL (`…/jmix-internal-snapshots/`) may reject `1.0.0` (release) uploads. Planner picks: (a) release URL `…/jmix-internal-releases/` for non-SNAPSHOT versions; (b) `1.0.0-SNAPSHOT` to keep the snapshot URL.

---

### `ai-agent/ai-agent/ai-agent.gradle` (MODIFY — datasource-proxy testImpl)

**Existing testImpl block** (lines 64–96 of analog) already has the precedent for adding test-only deps with explicit version (e.g. `asm:9.9` at line 88).

**Phase 8 addition** (single line in testImpl block, near the asm line):
```groovy
// Plan 08-XX (perf smoke OQ-1): EclipseLink has no Statistics API; use datasource-proxy
// to count JDBC SELECTs at the connection layer for per-tool baseline assertions.
testImplementation 'net.ttddyy:datasource-proxy:1.10.0'
```

**No other changes** to test-task wiring — new perf-smoke tests run in default `test` task (no tag). New live-tier golden suite runs in existing `liveTest` task at lines 135–144 (already includes `@Tag("live")` tests). No new Gradle task required.

---

### `ai-agent/ai-agent-starter/ai-agent-starter.gradle` (REVIEW)

**Existing file** is 45 lines. Inherits `maven-publish` from `subprojects { ... }` block at root `ai-agent/build.gradle:14-92`. The `archName` extraction at line 50 of root build.gradle reads `archivesBaseName = 'ai-agent-starter'` from line 1 of this file.

**Phase 8 verification only** — confirm `./gradlew :ai-agent:ai-agent-starter:publishToMavenLocal` produces `com.vn:ai-agent-starter:1.0.0` after the version bump. No edits expected.

---

### `.github/workflows/ai-agent-ci.yml` + `ai-agent-live.yml` + `ai-agent-publish.yml`

**Analog:** none in repo (RESEARCH §Integration Points). YAML drafted in 08-RESEARCH.md Example 6 (lines 956–1052).

**Key shapes:**
- `ai-agent-ci.yml` — two jobs: `test` (runs `:ai-agent:ai-agent:test :ai-agent:ai-agent:integrationTest` against pgvector service container) + `consumer-smoke` (runs `:ai-agent:ai-agent:publishToMavenLocal` then `:consumer-smoke:bootRunSmoke`). Triggers: `pull_request` + `push` to `main`.
- `ai-agent-live.yml` — single job: `liveTest`, gated on `secrets.OPENROUTER_API_KEY`. Trigger: `workflow_dispatch`.
- `ai-agent-publish.yml` — single job: `publish`, uses `-PnexusUsername=$NEXUS_USERNAME -PnexusPassword=$NEXUS_PASSWORD` from secrets. Trigger: `workflow_dispatch` + tag `v*`.

**Standard actions:** `actions/checkout@v4`, `actions/setup-java@v4` (Java 21 / temurin), `gradle/actions/setup-gradle@v4`.

---

## Shared Patterns

### Pattern A — `@SpringBootTest` boilerplate for ai-agent module tests

**Source:** `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java` (declares `@SpringBootConfiguration` + HSQL `DataSource` + mocked `ChatClient.Builder`) + every `*Test.java` in the module.

**Apply to:** all NEW test files in this phase (`FilteredSchemaAndExecutionDenialTest`, `RagRoleFilterNegativeTest`, `CrossUserConversationAccessTest`, `ToolQueryCountBaselineTest`, `FindRecordsLimitCapTest`, `ChatServiceLiveSemanticGoldenSuiteTest`).

```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})  // omit for live tests
class SomeTest { ... }
```

`AiToolsAutoConfiguration` should also be included for tool-touching tests (per `PromptInjectionHarnessTest:24-28`).

### Pattern B — Role-binding via `SystemAuthenticator`

**Source:** `AdminViewAccessTest.java:37-43` + `OwnershipOpacityTest.java:40` + `AuditDurabilityTest.java:55,95`.

**Apply to:** all new security tests + perf smoke tests + cross-user tests.

```java
systemAuthenticator.withUser("alice", () -> {
    // body returns the value the test asserts on
    return someService.someCall();
});

systemAuthenticator.runWithSystem(() -> {
    // body has no return value
    auditWriter.writeChatStart(...);
});
```

`alice` / `bob` (non-admin) and `admin` users are auto-seeded by `TestUsersConfiguration` (`com.vn.agent.test_support` is on the AIConfiguration scan path). The new `carol` user (NoCustomerReadRole) is added by `NoCustomerReadRoleConfiguration`.

### Pattern C — Gradle `tasks.register('xxxTest', Test)` for new test tiers

**Source:** `ai-agent/ai-agent/ai-agent.gradle:124-155` — three working examples (`liveTest`, `integrationTest`, `evalTest`).

```groovy
tasks.register('liveTest', Test) {
    description = 'Run @Tag("live") integration tests (require OPENROUTER_API_KEY).'
    group = 'verification'
    useJUnitPlatform {
        includeTags 'live'
    }
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    shouldRunAfter tasks.named('test')
}
```

**Apply to:** `consumer-smoke/consumer-smoke.gradle` `bootRunSmoke` task (uses the same `Test` task class, scoped via `filter { includeTestsMatching '*BootSmokeTest' }` rather than tag). No new task needed in `ai-agent.gradle` for Phase 8 — perf tests run in default `test`, golden suite runs in existing `liveTest`.

### Pattern D — `@Tag("live")` + `@EnabledIfEnvironmentVariable` double-gate

**Source:** `ChatServiceLiveSemanticTest.java:39-40`.

**Apply to:** only `ChatServiceLiveSemanticGoldenSuiteTest`. All other Phase 8 tests run untagged in default `test` task.

```java
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
```

Default `./gradlew test` excludes `live` (per `ai-agent.gradle:114`). Existing `liveTest` task includes it. Belt-and-suspenders: `@EnabledIfEnvironmentVariable` skips at JUnit level if the env var is unset even when the task filter is bypassed.

### Pattern E — Stub imports for non-live tests

**Source:** `AuditDurabilityTest.java:43` + `OwnershipOpacityTest.java:31`.

```java
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
```

**Apply to:** ALL Phase 8 tests EXCEPT `ChatServiceLiveSemanticGoldenSuiteTest` (live needs the real OpenRouter ChatModel).

### Pattern F — `archName`-based publication (already in place)

**Source:** `ai-agent/build.gradle:46-50, 75-80`.

```groovy
def props = new Properties()
buildFile.withInputStream { props.load(it) }
def subArchivesBaseName = props.getProperty('archivesBaseName')
def archName = subArchivesBaseName.substring(1, subArchivesBaseName.length() - 1)
// ...
publications {
    javaMaven(MavenPublication) {
        artifactId = archName
        from components.java
    }
}
```

**Apply to:** verify only — both `ai-agent/ai-agent/ai-agent.gradle:1` (`'ai-agent'`) and `ai-agent/ai-agent-starter/ai-agent-starter.gradle:1` (`'ai-agent-starter'`) have `archivesBaseName` declared as a single-quoted string at line 1, so the substring trim works.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `.github/workflows/ai-agent-ci.yml` | CI | event-driven | No `.github/` directory yet; first CI workflow in repo |
| `.github/workflows/ai-agent-live.yml` | CI | event-driven | Same |
| `.github/workflows/ai-agent-publish.yml` | CI | event-driven | Same |
| `consumer-smoke/` (project as a whole) | host bootstrap | config | First "clean consumer" subproject; `jmix-app/` is the adjacent integrated host but not strictly clean |
| `CHANGELOG.md` | docs | static | First CHANGELOG in repo; structure follows Keep-a-Changelog v1.1.0 |

For these, the planner pulls structure from RESEARCH.md examples (Examples 4–6) and Keep-a-Changelog spec rather than from existing in-repo files.

---

## Critical Naming Drift to Surface in PLAN.md

These come from RESEARCH §Pitfalls 1–2 + State of the Art table; the planner MUST flag them as deviations from CONTEXT.md verbatim:

1. **`EffectiveSchemaComputer.compute(...) → AiSchema.AiEntityInfo.AiAttributeInfo`** named in CONTEXT D-05 — DOES NOT EXIST. Use `CurrentUserSchemaAccess.getReadableSchema()` returning `Map<MetaClass, Set<String>>` (see `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java:44-79`).
2. **`SessionFactory.getStatistics().getQueryExecutionCount()`** named in CONTEXT D-07 — Hibernate API, project uses EclipseLink. Use `net.ttddyy:datasource-proxy:1.10.0` for query counting (RESEARCH §Standard Stack — Supporting).
3. **`AiToolCallAudit` entity** — RENAMED to `AiAuditEvent` in Phase 7.2 (see `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java`). RESEARCH State of the Art table.
4. **`maven-publish` "wire it in"** (CONTEXT D-18) — already wired at `ai-agent/build.gradle:63-81`. Phase 8 is verification + version-source bump (Pitfall 4) + secret rotation (Pitfall 3) only. RE-DECLARING the publication block causes `publication 'javaMaven' already declared` errors.

---

## Metadata

**Analog search scope:**
- `ai-agent/ai-agent/src/test/java/com/vn/agent/{security,orchestration,audit,live,tools,test_support}/**`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/{security,metadata,tools}/**`
- `ai-agent/{settings.gradle, build.gradle, gradle.properties, ai-agent/ai-agent.gradle, ai-agent-starter/ai-agent-starter.gradle}`
- `jmix-app/{settings.gradle, build.gradle, src/main/{java,resources}/**}`
- `settings.gradle` (repo root)
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`
- `ai-agent/ai-agent/src/test/resources/{com/vn/agent/test-app.properties, eval/*.yaml}`

**Files scanned:** 23 analog files read; 3 directories globbed.
**Pattern extraction date:** 2026-04-26
