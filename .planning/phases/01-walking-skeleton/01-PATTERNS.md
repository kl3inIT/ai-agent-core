# Phase 1: Walking Skeleton & Packaging De-risk — Pattern Map

**Mapped:** 2026-04-18
**Files analyzed:** 11 (7 new + 4 modified)
**Analogs found:** 11 / 11

## File Classification

| New/Modified File | New? | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|------|-----------|----------------|---------------|
| `ai-agent/build.gradle` (modify) | modify | build-config (root subprojects) | build-time dependency resolution | `D:/ai/traffic-law-chatbot/build.gradle` | exact (BOM + milestone pattern) |
| `ai-agent/ai-agent/ai-agent.gradle` (modify) | modify | build-config (functional module) | build-time + test-task | `D:/ai/traffic-law-chatbot/build.gradle` (test task block) | role-match (test tagging) |
| `ai-agent/ai-agent-starter/ai-agent-starter.gradle` (modify) | modify | build-config (starter module) | build-time dependency | Existing self + `traffic-law-chatbot/build.gradle` deps block | role-match |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` | NEW | SPI interface | request-response | None (greenfield; shape from RESEARCH §Code Examples + D-03) | no analog |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java` | NEW | DTO record | request-response payload | None (greenfield record) | no analog |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (or in starter) | NEW | service impl | request-response → Spring AI `ChatClient` | `traffic-law-chatbot` ChatClient-using service pattern (documented in RESEARCH) | role-match |
| `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` (modify) | modify | auto-config | bean registration | Existing self (AIAutoConfiguration.java) | exact (extend in place) |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` | NEW | unit test | mock-driven | `jmix-ai-backend/RerankerTest.java` (per CONTEXT); `AITest.java` (local context-loads scaffold) | role-match |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java` | NEW | live smoke test | `@Tag("live")` + HTTPS → OpenRouter | `traffic-law-chatbot` test pattern + `AITest.java` scaffolding | role-match |
| `jmix-app/src/main/resources/application.yaml` (create or modify) | modify | host app config | boot-time property binding | `traffic-law-chatbot/src/main/resources/application.yaml` | exact (lifted subset) |
| `docs/versions.md` (or README section) | NEW | documentation | reference | None — greenfield doc | no analog |

---

## Pattern Assignments

### `ai-agent/build.gradle` (modify — subprojects block)

**Analog A (authoritative for BOM wiring):** `D:/ai/traffic-law-chatbot/build.gradle`
**Analog B (existing structure to preserve):** `D:/DTH/ai-agent-core/ai-agent/build.gradle`

**Current existing `subprojects` block** (lines 13-23) — preserve and extend:
```gradle
subprojects {
    apply plugin: 'java-library'
    apply plugin: 'maven-publish'
    apply plugin: 'io.jmix'

    repositories {
        mavenCentral()
        maven {
            url = 'https://global.repo.jmix.io/repository/public'
        }
    }

    jmix {
        bomVersion = '2.8.0'
        projectId = 'AI'
    }
```

**Additions to copy from `traffic-law-chatbot/build.gradle` lines 17-31** (adapted into subprojects scope):
```gradle
    // Additive: two new repos + BOM import
    repositories {
        // mavenCentral() + global.repo.jmix.io already present — keep
        maven { url = 'https://repo.spring.io/milestone' }
        maven { url = 'https://repo.spring.io/snapshot' }  // optional forward-compat
    }

    ext {
        set('springAiVersion', "2.0.0-M4")
    }

    dependencyManagement {
        imports {
            mavenBom "org.springframework.ai:spring-ai-bom:${springAiVersion}"
        }
    }
```

**Note:** The Jmix `io.jmix` plugin already pulls `io.spring.dependency-management` transitively (verified by existing `jmix { bomVersion = '2.8.0' }` working). Planner should confirm; if not, `apply plugin: 'io.spring.dependency-management'` must be added.

**Do NOT touch** lines 46-63 (publishing block) — `publishToMavenLocal` already works because `javaMaven(MavenPublication) { from components.java }` is present for every subproject.

---

### `ai-agent/ai-agent/ai-agent.gradle` (modify — test task)

**Analog:** `D:/ai/traffic-law-chatbot/build.gradle` lines 76-94

**Current** (lines 24-26):
```gradle
test {
    useJUnitPlatform()
}
```

**Replace with** (port from traffic-law-chatbot):
```gradle
tasks.named('test') {
    useJUnitPlatform {
        excludeTags 'live'
    }
}

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

**Keep:** the existing `implementation 'io.jmix.flowui:jmix-flowui-starter'` / `jmix-flowui-themes` (D-01 keeps them — do NOT strip).

---

### `ai-agent/ai-agent-starter/ai-agent-starter.gradle` (modify — add Spring AI starter)

**Analog:** existing file (preserve `api project(':ai-agent')`) + `traffic-law-chatbot/build.gradle` deps lines 49, 55.

**Current** (lines 9-16):
```gradle
dependencies {
    api project(':ai-agent')

    implementation 'io.jmix.core:jmix-core'
    implementation 'io.jmix.data:jmix-data'

    implementation 'org.springframework.boot:spring-boot-autoconfigure'
}
```

**Extend with (versions come from BOM — do NOT hardcode):**
```gradle
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'

    testImplementation 'org.springframework.ai:spring-ai-test'  // spike per D-04
    testImplementation('org.springframework.boot:spring-boot-starter-test') {
        exclude group: 'org.junit.vintage', module: 'junit-vintage-engine'
    }
```

---

### `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` (NEW — SPI interface)

**Analog:** None in-codebase. Shape dictated by D-03 + RESEARCH §Code Examples.

**Pattern to copy** (RESEARCH lines 431-436):
```java
package com.vn.agent;

import java.util.Map;
import java.util.UUID;

public interface ChatService {
    ChatResponse ask(String message, UUID conversationId, String userKey);
}
```

**Package convention:** `com.vn.agent` — matches existing `AIConfiguration.java` package exactly (see existing `AIConfiguration.java` line 1).

---

### `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java` (NEW — DTO record)

**Analog:** None — planner's discretion per D-03/Claude's-Discretion.

**Shape (from RESEARCH line 434):**
```java
package com.vn.agent;

import java.util.Map;

public record ChatResponse(String content, Map<String, Object> metadata) {}
```

Could also be nested inside `ChatService` as `record ChatResponse(...) {}` — planner picks. CLAUDE.md forbids Lombok; record form is idiomatic.

---

### `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (NEW — impl)

**Analog:** RESEARCH §Code Examples (lines 441-465). Location recommendation: functional module (RESEARCH Open Q #3 recommends `@Service` in `com.vn.agent`, same package as interface).

**Pattern:**
```java
package com.vn.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class DefaultChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    // CLAUDE.md rule: constructor injection only for services
    public DefaultChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponse ask(String message, UUID conversationId, String userKey) {
        String content = chatClient.prompt().user(message).call().content();
        return new ChatResponse(content, Map.of("conversationId", String.valueOf(conversationId)));
    }
}
```

**Rationale for `@Service` vs `@Bean` in auto-config:** Component-scanned automatically by `AIConfiguration` (`@ComponentScan` at line 19 of `AIConfiguration.java`). RESEARCH recommends escalating to auto-config `@Bean` + `@ConditionalOnMissingBean` only when a second impl appears (Phase 3+).

---

### `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` (modify)

**Analog:** Existing file itself (preserve `@AutoConfiguration` + `@Import` structure); extend with `@Bean` for `ChatClient` per RESEARCH lines 328-346.

**Current (10 lines, entire file):**
```java
package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {
}
```

**Extend (D-03 — expose `ChatClient` default; do NOT register `ChatService` here if impl is `@Service` in functional module):**
```java
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

**Decision point (planner's):** If `DefaultChatServiceImpl` lives in functional module as `@Service`, this `@Bean ChatClient` is the only addition. If impl is moved here (starter), add `@Bean @ConditionalOnMissingBean ChatService` too — see RESEARCH lines 340-346.

**Leave `AutoConfiguration.imports` untouched** — already registers `AIAutoConfiguration` (verified single-line content).

---

### `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java` (NEW)

**Analog A (local scaffold):** `AITest.java` — keep `@SpringBootTest` / JUnit 5 imports.
**Analog B (mock pattern — per D-04):** `jmix-ai-backend/src/test/java/io/jmix/ai/backend/retrieval/RerankerTest.java` (not read here; planner should open before implementing).

**Local scaffold excerpt** (`AITest.java` lines 1-14):
```java
package com.vn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AITest {
    @Test
    void contextLoads() {}
}
```

**Extended pattern (from RESEARCH lines 470-497):**
```java
package com.vn.agent;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceMockTest {

    @Test
    void askReturnsMockContent() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);
        // Stub mockModel.call(Prompt) → ChatResponse("hello from mock")
        ChatClient.Builder builder = ChatClient.builder(mockModel);
        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(builder);

        ChatResponse r = svc.ask("hi", java.util.UUID.randomUUID(), null);

        assertThat(r.content()).isEqualTo("hello from mock");
    }
}
```

**Note:** This is a plain unit test (no `@SpringBootTest`) — faster, matches `RerankerTest.java` per D-04. A separate `@SpringBootTest` context-loads variant can extend the existing `AITestConfiguration.java`.

---

### `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java` (NEW)

**Analog:** `traffic-law-chatbot` live test pattern + `AITestConfiguration.java` (for Spring context).

**Pattern (RESEARCH lines 502-527):**
```java
package com.vn.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveTest {

    @Autowired ChatService chatService;

    @Test
    void openRouterSmoke() {
        ChatResponse r = chatService.ask(
                "Reply with exactly the word OK.",
                java.util.UUID.randomUUID(),
                null);
        assertThat(r.content()).isNotBlank();
    }
}
```

**Belt-and-suspenders (RESEARCH Pitfall 3):**
1. `@Tag("live")` + Gradle `excludeTags 'live'` → CI never runs.
2. `@EnabledIfEnvironmentVariable` → local runs without key skip cleanly.

---

### `jmix-app/src/main/resources/application.yaml` (create or modify)

**Analog:** `D:/ai/traffic-law-chatbot/src/main/resources/application.yaml` lines 37-43.

**Minimum lift (Phase 1 only):**
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENROUTER_API_KEY:none}
      base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}
      chat:
        options:
          model: ${OPENROUTER_MODEL:openai/gpt-4o-mini}
```

**Note on `base-url` path (RESEARCH Assumption A2):** `traffic-law-chatbot` uses `https://openrouter.ai/api` (no `/v1`); OpenRouter docs show `/api/v1`. Planner MUST verify via live smoke which combo works with Spring AI 2.0.0-M4 `completions-path` default, then pin the correct one in the version matrix doc.

**Do NOT lift** the full `app.ai.models[]` catalog, vector-store config, chat-memory config, embedding config — those are Phases 4/5/6.

---

### `docs/versions.md` (NEW — version matrix)

**No codebase analog.** Planner's choice: `docs/versions.md`, ADR file, or README section (Claude's Discretion per CONTEXT).

**Minimum content (derived from RESEARCH §Standard Stack):**

| Component | Version | Source |
|-----------|---------|--------|
| Java toolchain | 17 | repo root `build.gradle` |
| Jmix | 2.8.0 | `ai-agent/build.gradle` `bomVersion` |
| Spring Boot | 3.5.11 | transitive via `jmix-bom:2.8.0` |
| Spring AI BOM | 2.0.0-M4 | `ai-agent/build.gradle` `springAiVersion` |
| `spring-ai-starter-model-openai` | via BOM (2.0.0-M4) | Maven Central |
| OpenRouter base-url | `https://openrouter.ai/api/v1` (TBD after smoke) | verified at smoke time |
| Gradle wrapper | 8.14.4 | `gradle-wrapper.properties` |

**Re-verification commands** (RESEARCH lines 173-180) — embed in doc:
```bash
curl -s https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml | grep -E '<(latest|release)>'
curl -s https://global.repo.jmix.io/repository/public/io/jmix/bom/jmix-bom/2.8.0/jmix-bom-2.8.0.pom | grep -B1 -A1 spring-boot-dependencies
```

---

### Consumer smoke toggle (documented Gradle task or README script — D-02)

**No code analog; planner defines.** Two expected artifacts:

1. A documented sequence in README (or new `docs/consumer-smoke.md`):
   - `./gradlew publishToMavenLocal` (root — publishes both `ai-agent` and `ai-agent-starter`; RESEARCH Open Q #4 recommends root over subproject task).
   - Comment out `includeBuild 'ai-agent'` in root `settings.gradle` (line 4, currently: `includeBuild 'ai-agent'`).
   - `cd jmix-app && ./gradlew bootRun` — verify `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` resolves from `~/.m2/repository` (already in `jmix-app/build.gradle` line 18 `mavenLocal()` + line 26 `implementation 'com.vn:ai-agent-starter:0.0.1-SNAPSHOT'`).
   - Restore `includeBuild 'ai-agent'` after verification.

2. Optionally a Gradle task (name planner's choice per D-02, e.g., `verifyMavenLocalConsumer`) — wraps the steps.

**Verification injection target:** `jmix-app/src/main/java/.../*` — add a `@Component` implementing `CommandLineRunner` that `@Autowired`s `ChatService` and logs bean presence (RESEARCH line 114 — "minimal Vaadin view or `CommandLineRunner` — whichever exercises the bean with the least UI noise"). Recommend `CommandLineRunner` for lowest UI coupling.

---

## Shared Patterns

### Pattern S1: Constructor Injection Only (CLAUDE.md)
**Apply to:** `DefaultChatServiceImpl.java` and any other service added in Phase 1.
**Example:** see `DefaultChatServiceImpl.java` section above — single `ChatClient.Builder` constructor parameter. No `@Autowired` fields, no setter injection.

### Pattern S2: Jmix Module Declaration (already in place)
**Source:** `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` lines 18-22
```java
@Configuration
@ComponentScan
@ConfigurationPropertiesScan
@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})
@PropertySource(name = "com.vn.agent", value = "classpath:/com/vn/agent/module.properties")
public class AIConfiguration {
```
**Apply to:** no new `@Configuration` class is needed in Phase 1 (AIAutoConfiguration extends the existing one via `@Import`). Leave this declaration unchanged — RESEARCH confirms existing `dependsOn` list is sufficient (Assumption A4).

### Pattern S3: Auto-config Registration (already in place)
**Source:** `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
Content (entire file, one line):
```
com.vn.autoconfigure.agent.AIAutoConfiguration
```
**Apply to:** no change in Phase 1. If a new `@AutoConfiguration` is ever added (not this phase), append one FQCN per line, UTF-8, no BOM (RESEARCH Pitfall 5).

### Pattern S4: Test Scaffolding Base
**Source:** `ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java` (all 30 lines)
```java
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(AIConfiguration.class)
@PropertySource("classpath:/com/vn/agent/test-app.properties")
@JmixModule(id = "com.vn.agent.test", dependsOn = AIConfiguration.class)
public class AITestConfiguration {
    @Bean @Primary
    DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.HSQL)
                .build();
    }
}
```
**Apply to:** `ChatServiceLiveTest` (via implicit `@SpringBootTest` lookup of nearest `@SpringBootConfiguration`). `ChatServiceMockTest` does NOT need this — pure unit test.

### Pattern S5: BOM Repositories Ordering
**Source:** `ai-agent/build.gradle` lines 18-23 (existing subprojects repos).
**Rule (RESEARCH Pitfall 1):** `mavenCentral()` MUST precede `maven { url = '…repo.spring.io/milestone' }`. Gradle resolves first-hit; `spring-ai-bom:2.0.0-M4` lives on Central, not on the milestone repo.

### Pattern S6: Env-var Skip for Live Tests (RESEARCH Pitfall 3)
**Belt + suspenders:**
- Gradle: `useJUnitPlatform { excludeTags 'live' }` — default suite never runs.
- Annotation: `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")` — manual `./gradlew liveTest` skips cleanly when key absent.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `ChatService.java` | SPI interface | request-response | First Spring-AI-facing interface in repo — shape from D-03/RESEARCH Code Examples. |
| `ChatResponse.java` | DTO record | payload | First AI response DTO — shape from D-03. |
| `docs/versions.md` | documentation | reference | No prior version matrix doc — greenfield content. |

---

## Key Code Locations (absolute paths for planner)

- `D:\DTH\ai-agent-core\ai-agent\build.gradle`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\ai-agent.gradle`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent-starter\ai-agent-starter.gradle`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\AIConfiguration.java`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent-starter\src\main\java\com\vn\autoconfigure\agent\AIAutoConfiguration.java`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\test\java\com\vn\agent\AITest.java`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\test\java\com\vn\agent\AITestConfiguration.java`
- `D:\DTH\ai-agent-core\jmix-app\build.gradle`
- `D:\DTH\ai-agent-core\settings.gradle`
- External reference: `D:\ai\traffic-law-chatbot\build.gradle`
- External reference: `D:\ai\traffic-law-chatbot\src\main\resources\application.yaml`
- External reference (not opened; for planner): `D:\Study materials spring 2026\EXE101\ai\jmix-ai-backend\src\test\java\io\jmix\ai\backend\retrieval\RerankerTest.java`

---

## Metadata

**Analog search scope:** `ai-agent/**`, `jmix-app/**`, plus two external canonical references cited in CONTEXT.md (`traffic-law-chatbot`, `jmix-ai-backend`).
**Files scanned (opened):** 10 in-repo + 2 external reference files.
**Pattern extraction date:** 2026-04-18
