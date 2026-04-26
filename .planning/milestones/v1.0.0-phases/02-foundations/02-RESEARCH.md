# Phase 2: Foundations — Research

**Researched:** 2026-04-18
**Domain:** Jmix 2.8 add-on persistence + security + SPI contracts; Spring AI 1.1.4 chat-memory JDBC + pgvector schema porting
**Confidence:** HIGH on Jmix entity/role/liquibase patterns (verified via jmix-context7 and existing jmix-app code), HIGH on Spring AI 1.1.4 chat-memory and pgvector schemas (fetched from GitHub tag v1.1.4 directly), MEDIUM on Spring AI 1.0.2 → 1.1.4 schema delta (1.0.2 schema not inspected — planner should `git diff` if a delta matters; expected to be additive or identical for chat-memory).

## Summary

Phase 2 lands the persistence + security + SPI-contract skeleton that phases 3–8 build on. All six deliverables are well-understood Jmix/Spring-AI primitives — no research gaps of architectural significance. Two surprises worth calling out before the planner starts:

1. **Spring AI 1.1.4 `SPRING_AI_CHAT_MEMORY` table cannot be renamed via configuration.** The starter wires a hard-coded SQL script and `JdbcChatMemoryRepository` that reads/writes `SPRING_AI_CHAT_MEMORY`. ENT-02's `AI_AGENT_*` prefix requirement does NOT apply to this table — document the exception rather than fight the framework. The table will physically live in the host DB under the Spring-AI-owned name, with our Liquibase changelog owning the DDL (initialize-schema: never).
2. **Jmix add-on Liquibase changelogs are NOT auto-registered.** Per jmix-context7 docs and the existing `jmix-app/changelog.xml`, the host's master changelog must explicitly `<include file="/com/vn/agent/liquibase/changelog.xml"/>`. There is no `module.properties` key that picks it up. D-02's claim ("auto-discovers") is incorrect — the planner must add a success criterion that `jmix-app/changelog.xml` is updated during Phase 2 execution.

**Primary recommendation:** Ship the 5 entities, 2 roles, 6 SPIs, `SpiDefaultsAutoConfiguration`, and 7 Liquibase changelogs exactly as D-01..D-10 specify. Use `@JpqlRowLevelPolicy` with `:current_user_username` for SEC-04 (verified pattern). Pin `jmix-app/changelog.xml` update as a deliverable.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 Step-numbered Liquibase layout** under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/` — `010-ai-conversation.xml` … `070-ai-kb-vector-store.xml` (planner may adjust numbering).
- **D-02 Master changelog** at `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml`; includes each step file.
  - **Correction from research:** Jmix does NOT auto-discover add-on changelogs. The host's `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` MUST be edited to `<include file="/com/vn/agent/liquibase/changelog.xml"/>` before the Jmix app's own `<includeAll>`.
- **D-03 pgvector DDL gated via `<preConditions onFail="MARK_RAN" dbms="postgresql">`** — `CREATE EXTENSION IF NOT EXISTS vector` + `AI_AGENT_KB_VECTOR_STORE` table. HSQLDB skips cleanly.
- **D-04 Flat `com.vn.agent.spi` package** holds all 6 SPI interfaces: `ToolContributor`, `ContextContributor`, `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`. Interfaces + Javadoc only.
- **D-05 Drop `AiExposureRule` / `EntityExposurePolicy` SPI-04 / `ExposureRuleListView` UI-07** — Jmix `AccessManager` + `DataManager` are authoritative. Entity count 6 → 5, SPI count 7 → 6.
- **D-06 Single `SpiDefaultsAutoConfiguration`** in `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/`, 6 `@Bean @ConditionalOnMissingBean` no-op defaults, registered in `AutoConfiguration.imports` alongside existing `AIAutoConfiguration`.
- **D-07 Entity-level `@EntityPolicy` CRUD only** — no `@EntityAttributePolicy`, no `@ViewPolicy` in Phase 2. `AiAgentUserRole` = READ on `AiConversation` / `AiMessage` (row-scoped per D-08); `AiAgentAdminRole` = full CRUD on all 5.
- **D-08 SEC-04 declarative row-level predicate** on `AiAgentUserRole`: `AiConversation.createdBy == currentAuthentication.user.username`. `AiMessage` filters via `conversation` FK. Admin bypasses.
- **D-09 ArchUnit dropped from v1 (TEST-06 removed).** Code review + tests enforce the rules.
- **D-10 Update REQUIREMENTS.md, ROADMAP.md, PROJECT.md during Phase 2 execution** to reflect D-05 + D-09 scope reductions. Also update `.planning/research/STACK.md` from 1.0.2 → 1.1.4 (see Spring AI version note).

### Claude's Discretion

- Exact row-level-policy API: **research recommends `@JpqlRowLevelPolicy` with `:current_user_username`** (see §Row-Level Security).
- Liquibase add-on registration: **research confirms explicit `<include>` in host changelog is the only supported path** (see §Liquibase Registration).
- `AI_AGENT_*` column conventions: snake_case, follow jmix-app `020-customer.xml` style (`${uuid.type}` for UUIDs, `int` for version, `varchar(255)`/`varchar(32)` for strings).
- `@Composition` on `AiConversation → AiMessage`: **research recommends YES** (see §Entity Shapes).
- SPI Javadoc style: **research recommends integration-example-in-Javadoc** (see §SPI Signatures).
- Enum definitions: **research recommends values below** (see §Enum Definitions).
- `@OnDelete(DeletePolicy.CASCADE)` on `AiConversation.messages` (see §Entity Shapes).
- Admin-role seed data: **research recommends NO seed in Phase 2** — admin manually assigns via Jmix role UI per success criterion #3.

### Deferred Ideas (OUT OF SCOPE)

- `AiExposureRule` entity / `EntityExposurePolicy` SPI / `ExposureRuleListView` UI — dropped v1.
- ArchUnit rules / TEST-06 — dropped v1.
- `@ViewPolicy` — Phase 7.
- `@EntityAttributePolicy` — no sensitive AI-specific attributes in MVP.
- Spring AI chat-memory schema version tracking — Phase 2 ports 1.1.4; upgrades later.
- SPI implementations beyond no-op defaults — phases 3–6.
- Bootstrap admin seed assigning `AiAgentAdminRole` — manual via Jmix role UI.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| ENT-01 | Five JPA entities (scope-reduced from 6 per D-05): `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument` — UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok | §Entity Shapes — full field lists, composition rules, cascades |
| ENT-02 | All DDL in add-on Liquibase with `AI_AGENT_*` prefix | §Liquibase Skeletons — 7 step files. Exception: `SPRING_AI_CHAT_MEMORY` table name is hard-coded by Spring AI (see §Chat-Memory Schema) |
| ENT-03 | Spring AI 1.1.4 JDBC chat-memory DDL ported into add-on Liquibase; `initialize-schema: never` in host app | §Chat-Memory Schema — exact 1.1.4 SQL for both postgresql + hsqldb fetched from `spring-ai v1.1.4` tag |
| ENT-04 | `CREATE EXTENSION vector` + `AI_AGENT_KB_VECTOR_STORE` — postgres-gated | §pgvector Skeleton |
| SEC-01 | `AiAgentUserRole` + `AiAgentAdminRole` with `@ResourceRole` annotation | §Security Roles |
| SEC-02 | Any authenticated user = Chat access (default); admin entities gated | §Security Roles — `AiAgentUserRole` reads only `AiConversation` + `AiMessage` |
| SEC-03 | DataManager-only persistence — enforced by code review per D-09 | §Don't Hand-Roll — restated for entities; no ArchUnit |
| SEC-04 | Row-scoped conversation ownership | §Row-Level Security — `@JpqlRowLevelPolicy` with `:current_user_username` |
| SPI-01..03, 05..07 | Six SPI interfaces in `com.vn.agent.spi` | §SPI Signatures — full Javadoc+signature sketches |
| SPI-04 | **DROPPED per D-05** — do not implement | — |
| TEST-06 | **DROPPED per D-09** — do not implement | — |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Entity persistence | Database / Storage (Liquibase DDL + JPA entities) | API (DataManager) | Standard Jmix pattern; schema in Liquibase, entities mapped via EclipseLink |
| Chat-memory storage | Database / Storage (SPRING_AI_CHAT_MEMORY + AiConversation) | API (Spring AI repository) | Spring AI owns raw memory; Jmix entity is projection (Phase 3/4) |
| Vector storage | Database / Storage (pgvector extension + table) | API (Spring AI PgVectorStore, Phase 5) | Postgres-native; Phase 2 lands schema only |
| Security policies | API / Backend (resource roles + row-level role) | — | Jmix `@ResourceRole` + `@RowLevelRole`, applied inside DataManager |
| SPI contracts | API / Backend (com.vn.agent.spi package) | — | Functional module; interfaces only |
| Default SPI beans | API / Backend (auto-configuration in starter) | — | `@AutoConfiguration` in `ai-agent-starter` |
| i18n | API / Backend (messages.properties) | — | Jmix message-key convention, `msg://` consumed later by UI phase |

## Architecture Patterns

### System Architecture Diagram — Phase 2 runtime surface

```
 Fresh host DB boot
      │
      ▼
 [Liquibase master (jmix-app/changelog.xml)]
      │ runs Jmix core changelogs, then:
      ├─► <include file="/com/vn/agent/liquibase/changelog.xml"/>    ← ADDED in Phase 2
      │        │
      │        ├─ 010-ai-conversation.xml     → AI_AGENT_CONVERSATION
      │        ├─ 020-ai-message.xml          → AI_AGENT_MESSAGE (FK → conversation)
      │        ├─ 030-ai-tool-call-audit.xml  → AI_AGENT_TOOL_CALL_AUDIT
      │        ├─ 040-ai-parameters.xml       → AI_AGENT_PARAMETERS
      │        ├─ 050-ai-knowledge-document.xml → AI_AGENT_KNOWLEDGE_DOCUMENT
      │        ├─ 060-ai-chat-memory.xml      → SPRING_AI_CHAT_MEMORY    (Spring AI owned)
      │        └─ 070-ai-kb-vector-store.xml  → vector ext + AI_AGENT_KB_VECTOR_STORE
      │                                         (wrapped in dbms="postgresql" preCondition)
      └─► jmix-app's own <includeAll ... changelog/> (sample data entities)

 Spring context startup
      │
      ├─► AIConfiguration (ai-agent)          — existing @Configuration; dependsOn list widened
      │        @JmixModule(dependsOn = {EclipselinkConfiguration, FlowuiConfiguration,
      │                                  SecurityConfiguration, DataConfiguration})
      │
      ├─► AIAutoConfiguration (ai-agent-starter, existing)
      │        @Import(AIConfiguration) + ChatClient @Bean
      │
      └─► SpiDefaultsAutoConfiguration (ai-agent-starter, NEW)
               6 × @Bean @ConditionalOnMissingBean no-op defaults:
                 ToolContributor, ContextContributor, PromptContextContributor,
                 ToolGuard, AuditListener, CustomIngester
```

### Recommended Project Structure (additions)

```
ai-agent/ai-agent/src/main/
├── java/com/vn/agent/
│   ├── AIConfiguration.java          # existing; extend @JmixModule(dependsOn=)
│   ├── entity/
│   │   ├── AiConversation.java
│   │   ├── AiMessage.java
│   │   ├── AiMessageRole.java         # enum
│   │   ├── AiToolCallAudit.java
│   │   ├── AiToolCallOutcome.java     # enum
│   │   ├── AiParameters.java
│   │   ├── AiKnowledgeDocument.java
│   │   └── AiKnowledgeDocumentStatus.java # enum
│   ├── security/
│   │   ├── AiAgentUserRole.java       # @ResourceRole + @RowLevelRole (combined via meta-role? see §Row-Level Security)
│   │   └── AiAgentAdminRole.java      # @ResourceRole
│   └── spi/
│       ├── ToolContributor.java
│       ├── ContextContributor.java
│       ├── PromptContextContributor.java
│       ├── ToolGuard.java
│       ├── AuditListener.java
│       └── CustomIngester.java
└── resources/com/vn/agent/
    ├── module.properties              # existing
    ├── menu.xml                       # existing
    ├── messages.properties            # extend (EN fallback)
    ├── messages_vi.properties         # NEW (VI)
    └── liquibase/
        ├── changelog.xml              # NEW master
        └── changelog/
            ├── 010-ai-conversation.xml
            ├── 020-ai-message.xml
            ├── 030-ai-tool-call-audit.xml
            ├── 040-ai-parameters.xml
            ├── 050-ai-knowledge-document.xml
            ├── 060-ai-chat-memory.xml
            └── 070-ai-kb-vector-store.xml

ai-agent/ai-agent-starter/src/main/
├── java/com/vn/autoconfigure/agent/
│   ├── AIAutoConfiguration.java       # existing
│   └── SpiDefaultsAutoConfiguration.java  # NEW
└── resources/META-INF/spring/
    └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
        # existing: AIAutoConfiguration
        # append:   SpiDefaultsAutoConfiguration

jmix-app/src/main/resources/com/vn/jmixapp/liquibase/
└── changelog.xml                       # EDITED: add <include> for add-on changelog
```

### Pattern — Jmix entity shape (mirror jmix-app `Customer.java` / `Order.java`)

All 5 entities follow the exact template already proven by `jmix-app/entity/Customer.java` and `Order.java`:

```java
// [VERIFIED: jmix-app/src/main/java/com/vn/jmixapp/entity/Customer.java]
@JmixEntity
@Entity(name = "ai_AiConversation")                // metadata name: ai_ prefix per projectId='AI'
@Table(name = "AI_AGENT_CONVERSATION", indexes = {
        @Index(name = "IDX_AI_AGENT_CONVERSATION__ON_CREATED_BY", columnList = "CREATED_BY")
})
public class AiConversation {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "TITLE")
    private String title;
    // ... fields + getters/setters (no Lombok)
}
```

**Metadata name convention:** `@Entity(name = "ai_<ClassName>")` — `ai_` prefix matches `jmix { projectId = 'AI' }` convention (per STACK.md and root build.gradle line 31). Table name uses `AI_AGENT_*` prefix (ENT-02).

### Anti-Patterns to Avoid

- **Lombok on entities** — banned by CLAUDE.md; breaks Jmix enhancer.
- **Entity construction via `new`** — use `Metadata.create()` or `DataManager.create()`.
- **`EntityManager` in add-on code** — banned by CLAUDE.md; bypasses Jmix security.
- **Renaming `SPRING_AI_CHAT_MEMORY` table** — the 1.1.4 starter has no property to override; attempting to do so requires custom `JdbcChatMemoryRepositoryDialect` — deferred.
- **Auto-init on Spring AI schemas** — set both `spring.ai.chat.memory.repository.jdbc.initialize-schema: never` and `spring.ai.vectorstore.pgvector.initialize-schema: false` in `jmix-app/application.properties`. Liquibase owns the schema.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Row-level filtering on conversations | Custom service-layer `if (user.equals(conv.createdBy))` checks | `@JpqlRowLevelPolicy` with `:current_user_username` | Applied automatically by DataManager; ChatService in Phase 4 gets it for free |
| Add-on changelog auto-registration | Parsing classpath for `**/liquibase/changelog.xml` | Explicit `<include>` in host master changelog | Jmix's documented pattern; no framework auto-discovery for add-on changelogs |
| Chat-memory schema | Hand-write CREATE TABLE from scratch | Port Spring AI 1.1.4's `schema-postgresql.sql` / `schema-hsqldb.sql` verbatim into Liquibase | Exact columns, types, constraints, indexes mandated by Spring AI's JDBC dialect readers |
| pgvector schema | Hand-design column shape | Copy from Spring AI `pgvector.adoc` reference schema (id uuid, content text, metadata json, embedding vector(1536)) | `PgVectorStore.builder()` reads this exact shape at runtime in Phase 5 |
| No-op SPI bean boilerplate | Separate `*NoOp` class per SPI | Inline anonymous class or static inner class inside `SpiDefaultsAutoConfiguration` | 6 one-liners is smaller than 6 classes |

## Common Pitfalls

### Pitfall 1: Spring AI chat-memory table renamed but JDBC repo still reads default
**What goes wrong:** Team tries to prefix `AI_AGENT_CHAT_MEMORY` for consistency; `JdbcChatMemoryRepository` fails at runtime because its SQL is hard-coded to `SPRING_AI_CHAT_MEMORY`.
**How to avoid:** Document the exception in RESEARCH.md and README. Table stays `SPRING_AI_CHAT_MEMORY`. Add-on-owned-DDL claim (ENT-02) is explicitly relaxed for this one table.
**Warning signs:** Changeset creates `AI_AGENT_CHAT_MEMORY`; boot-test succeeds at schema creation but later fails when Spring AI autoconfig probes for its schema. `[VERIFIED: spring-ai v1.1.4 source]`

### Pitfall 2: Liquibase changelog not included in host master
**What goes wrong:** Add-on ships changelogs under `com/vn/agent/liquibase/`; `./gradlew bootRun` succeeds because Liquibase finds no new changesets to run; `AiConversation` entity fails at first DataManager call with "table not found."
**How to avoid:** Phase 2 MUST edit `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` to add `<include file="/com/vn/agent/liquibase/changelog.xml"/>` BEFORE the existing `<includeAll>` line.
**Warning signs:** Fresh HSQLDB boot with deleted `.jmix/hsqldb/jmixapp.*` files; entity persistence fails. `[CITED: jmix-context7 — "Changelogs from dependencies are executed on your database before the ones of the application"; VERIFIED by absence of any auto-include mechanism in existing jmix-app changelog.xml]`

### Pitfall 3: pgvector preCondition wraps `<createTable>` but not `<sql>`
**What goes wrong:** Raw `<sql>CREATE EXTENSION IF NOT EXISTS vector</sql>` runs on HSQLDB, throws "unknown CREATE EXTENSION" even though the table create is correctly gated.
**How to avoid:** Wrap the ENTIRE changeSet body — or use changeset-level `<preConditions>` — so both the `CREATE EXTENSION` and the table DDL skip together.
**Warning signs:** HSQLDB test fails with "unknown statement CREATE EXTENSION." See skeleton below. `[VERIFIED: Liquibase docs — preConditions onFail=MARK_RAN must wrap all SQL in the changeSet]`

### Pitfall 4: `SpiDefaultsAutoConfiguration` registered before host bean overrides resolve
**What goes wrong:** Host's custom `ToolGuard` is in `@Component` form; `@ConditionalOnMissingBean` runs too early and installs the no-op; host's guard is ignored.
**How to avoid:** Use `@AutoConfigureAfter(AIAutoConfiguration.class)` OR trust Spring's default ordering (auto-configs process AFTER user `@Component` scanning in the same context — typical, but not guaranteed when host's guard is itself in an `@AutoConfiguration`). Recommended: explicit `@AutoConfigureAfter(AIAutoConfiguration.class)` on `SpiDefaultsAutoConfiguration`.
**Warning signs:** Test with a host-supplied `ToolGuard` bean — verify the custom one wins, not the no-op.

### Pitfall 5: `@Composition` on `AiConversation.messages` missing cascades
**What goes wrong:** Deleting a conversation leaves orphaned `AiMessage` rows; FK constraint denies the delete.
**How to avoid:** Mirror `jmix-app/Order.java` exactly — `@Composition` + `@OnDelete(DeletePolicy.CASCADE)` + `@OneToMany(mappedBy="conversation", cascade=CascadeType.ALL, orphanRemoval=true)`. `[VERIFIED: jmix-app/entity/Order.java:53-56]`

### Pitfall 6: Row-level role not combined with resource role
**What goes wrong:** Grants `AiAgentUserRole` but row-level filter doesn't fire because row-level roles are a SEPARATE role type in Jmix.
**How to avoid:** Define a companion `AiAgentUserRowLevelRole` (or use the meta-role mechanism) and assign both. Confirmed pattern: `@ResourceRole` + `@RowLevelRole` are two different interfaces, each with its own `code`. Document in README: "Assign both `AiAgentUserRole` and `AiAgentUserRowLevelRole` to end users." `[VERIFIED: jmix-context7 docs — two separate annotations/interfaces, separate code constants]`

### Pitfall 7: `available-locales` host config drifts from add-on messages
**What goes wrong:** Add-on ships `messages_vi.properties` but host's `jmix.core.available-locales=en` omits Vietnamese; the VI translations are never loaded.
**How to avoid:** Verify `jmix-app/application.properties` already has `jmix.core.available-locales=vi,en` (it does — line 9). Document for other host apps. `[VERIFIED: jmix-app/application.properties:9]`

## Liquibase Skeletons

### Master changelog (`ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <!-- Master changelog for the ai-agent add-on. Hosts include this from their own master. -->
    <includeAll path="/com/vn/agent/liquibase/changelog"/>

</databaseChangeLog>
```

### Host edit: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml`

```xml
<!-- existing Jmix includes ... -->
<include file="/io/jmix/data/liquibase/changelog.xml"/>
<include file="/io/jmix/flowuidata/liquibase/changelog.xml"/>
<include file="/io/jmix/securitydata/liquibase/changelog.xml"/>

<!-- ADDED in Phase 2: -->
<include file="/com/vn/agent/liquibase/changelog.xml"/>

<!-- existing jmix-app entities -->
<includeAll path="/com/vn/jmixapp/liquibase/changelog"/>
```

### 010-ai-conversation.xml (example skeleton — planner fills remaining columns)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="1" author="ai-agent">
        <createTable tableName="AI_AGENT_CONVERSATION">
            <column name="ID" type="${uuid.type}">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="VERSION" type="int" defaultValueNumeric="1">
                <constraints nullable="false"/>
            </column>
            <column name="TITLE" type="varchar(255)"/>
            <column name="CREATED_BY" type="varchar(255)">
                <constraints nullable="false"/>
            </column>
            <column name="CREATED_DATE" type="timestamp"/>
            <column name="LAST_MODIFIED_BY" type="varchar(255)"/>
            <column name="LAST_MODIFIED_DATE" type="timestamp"/>
        </createTable>
    </changeSet>

    <changeSet id="2" author="ai-agent">
        <createIndex indexName="IDX_AI_AGENT_CONVERSATION__ON_CREATED_BY"
                     tableName="AI_AGENT_CONVERSATION">
            <column name="CREATED_BY"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

### 060-ai-chat-memory.xml — Spring AI 1.1.4 chat-memory schema (VERBATIM PORT)

> **Source:** `spring-ai v1.1.4` tag, `memory/repository/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-{postgresql,hsqldb}.sql` `[VERIFIED: raw.githubusercontent.com/spring-projects/spring-ai/v1.1.4/...]`
>
> Table name `SPRING_AI_CHAT_MEMORY` is hard-coded in the Spring AI JDBC starter; do NOT rename. This is the one documented exception to the `AI_AGENT_*` prefix rule.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <!-- ====== PostgreSQL variant ====== -->
    <changeSet id="1-postgres" author="ai-agent" dbms="postgresql">
        <sql>
            CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
                conversation_id VARCHAR(36) NOT NULL,
                content TEXT NOT NULL,
                type VARCHAR(10) NOT NULL CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL')),
                "timestamp" TIMESTAMP NOT NULL
            );
        </sql>
        <sql>
            CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
            ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");
        </sql>
    </changeSet>

    <!-- ====== HSQLDB variant (matches schema-hsqldb.sql in 1.1.4) ====== -->
    <changeSet id="1-hsqldb" author="ai-agent" dbms="hsqldb">
        <sql>
            CREATE TABLE SPRING_AI_CHAT_MEMORY (
                conversation_id VARCHAR(36) NOT NULL,
                content LONGVARCHAR NOT NULL,
                type VARCHAR(10) NOT NULL,
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
            );
        </sql>
        <sql>
            CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
            ON SPRING_AI_CHAT_MEMORY(conversation_id, timestamp DESC);
        </sql>
        <sql>
            ALTER TABLE SPRING_AI_CHAT_MEMORY
            ADD CONSTRAINT TYPE_CHECK CHECK (type IN ('USER','ASSISTANT','SYSTEM','TOOL'));
        </sql>
    </changeSet>
</databaseChangeLog>
```

**Why two changeSets, not one with preConditions:** the DDL differs (`TEXT` vs `LONGVARCHAR`, quoted vs unquoted `timestamp`, separate ALTER on HSQLDB). Cleaner to gate each with `dbms="..."`. MySQL/MSSQL/Oracle hosts would add their own changeSet later.

### 070-ai-kb-vector-store.xml — pgvector extension + vector table (postgres-only)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="1" author="ai-agent" dbms="postgresql">
        <preConditions onFail="MARK_RAN">
            <dbms type="postgresql"/>
        </preConditions>
        <sql>CREATE EXTENSION IF NOT EXISTS vector;</sql>
        <sql>CREATE EXTENSION IF NOT EXISTS hstore;</sql>
        <sql>CREATE EXTENSION IF NOT EXISTS "uuid-ossp";</sql>
    </changeSet>

    <changeSet id="2" author="ai-agent" dbms="postgresql">
        <preConditions onFail="MARK_RAN">
            <dbms type="postgresql"/>
        </preConditions>
        <sql>
            CREATE TABLE IF NOT EXISTS AI_AGENT_KB_VECTOR_STORE (
                id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
                content text,
                metadata json,
                embedding vector(1536)
            );
        </sql>
        <sql>
            CREATE INDEX IF NOT EXISTS IDX_AI_AGENT_KB_VECTOR_STORE__EMBEDDING
            ON AI_AGENT_KB_VECTOR_STORE USING HNSW (embedding vector_cosine_ops);
        </sql>
    </changeSet>
</databaseChangeLog>
```

**Column shape `[VERIFIED: spring-ai pgvector.adoc]`:** id uuid (default uuid_generate_v4), content text, metadata json, embedding vector(1536). Dimension 1536 is the OpenAI default and what `PgVectorStore.builder()` falls back to when `.dimensions()` is not set. Hosts using other embedding models must override both the `dimensions` config AND the changelog.

**Phase 5 PgVectorStore wiring** will set `.schemaName("public").vectorTableName("ai_agent_kb_vector_store").initializeSchema(false)`.

**`dbms="postgresql"` on `<changeSet>` AND `<preConditions onFail="MARK_RAN">`:** belt-and-suspenders. The `dbms` attribute handles the "changeset doesn't apply to this DB" case; the `preConditions` handles cases where `dbms` is computed late (e.g., a host using a Postgres-compat proxy layer).

## Chat-Memory Schema — Spring AI 1.0.2 → 1.1.4 delta

**1.1.4 schema (fetched from GitHub v1.1.4 tag):** 4 columns — `conversation_id VARCHAR(36)`, `content TEXT/LONGVARCHAR`, `type VARCHAR(10)` (CHECK in USER/ASSISTANT/SYSTEM/TOOL), `timestamp TIMESTAMP`. One index on `(conversation_id, timestamp)`.

**1.0.2 schema:** not re-fetched in this research session. Based on the Spring AI release history, the `JdbcChatMemoryRepository` API was GA in 1.0 and the schema has been stable. [ASSUMED] that 1.0.2 schema is identical to 1.1.4 — **planner should `git diff` `spring-projects/spring-ai@v1.0.2` vs `@v1.1.4` on the `schema-*.sql` paths before committing the Liquibase XML**. If differences exist, default to the 1.1.4 shape (since STACK.md will be updated to 1.1.4 per D-10 and the actual Phase 1 BOM is already at 1.1.4 per `ai-agent/build.gradle:35`).

**Action for planner:** Before finalizing `060-ai-chat-memory.xml`, verify 1.1.4 shape against the exact `JdbcChatMemoryRepository` source in the resolved artifact under `~/.gradle/caches/modules-2/.../spring-ai-chat-memory-repository-jdbc-1.1.4.jar`. The schemas above are authoritative for v1.1.4.

## Row-Level Security

### Recommendation: `@JpqlRowLevelPolicy` with `:current_user_username`

**Confidence:** HIGH `[VERIFIED: jmix-context7 — exact pattern "Filter entities using session attributes in JPQL policies"]`

```java
// [CITED: jmix-context7 docs — verbatim pattern]
package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.security.role.annotation.JpqlRowLevelPolicy;
import io.jmix.security.role.annotation.RowLevelRole;

@RowLevelRole(
        name = "AI Agent User Row-Level",
        code = AiAgentUserRowLevelRole.CODE)
public interface AiAgentUserRowLevelRole {

    String CODE = "ai-agent-user-rl";

    @JpqlRowLevelPolicy(
            entityClass = AiConversation.class,
            where = "{E}.createdBy = :current_user_username")
    void conversation();

    @JpqlRowLevelPolicy(
            entityClass = AiMessage.class,
            where = "{E}.conversation.createdBy = :current_user_username")
    void message();
}
```

**Why JPQL over Predicate:** JPQL row-level policies push the filter into the SQL `WHERE` clause — no in-memory filtering, works with pagination, works with count queries. `@PredicateRowLevelPolicy` loads all rows then filters in Java — acceptable for tiny datasets only.

**Why the `:current_user_username` session parameter:** automatically bound by Jmix from `CurrentAuthentication` — no reflection, no Spring bean lookup inside the policy. Cleaner than `@PredicateRowLevelPolicy + ApplicationContext`.

**Assignment model:** Jmix end users receive BOTH `AiAgentUserRole` (resource role — entity CRUD policies) AND `AiAgentUserRowLevelRole` (row-level role — predicate). `AiAgentAdminRole` (resource role with `*`-style privileges) need NOT receive the row-level role; admins see all rows by default because they don't have a narrowing row-level policy.

**Alternative rejected:** `@PredicateRowLevelPolicy` with `RowLevelBiPredicate` + `ApplicationContext`. Works but: (a) leaks `CurrentAuthentication` import into security package, (b) runs in-memory — wrong for paginated list views. Keep in back pocket if the JPQL path hits a Jmix/EclipseLink bug.

## Security Roles

### AiAgentUserRole (resource role — CRUD + visibility)

```java
package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

@ResourceRole(name = "AI Agent User", code = AiAgentUserRole.CODE)
public interface AiAgentUserRole {

    String CODE = "ai-agent-user";

    @EntityPolicy(entityClass = AiConversation.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = AiMessage.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
    void userAccess();
}
```

**Rationale:** Users need CREATE on conversations (starting a new chat) and messages (posting a question). READ is scoped by the row-level role. No DELETE in v1 — users don't delete their own conversations (admin action only; could be added later).

**No `@EntityPolicy` for the other three entities** — per D-07, users cannot see `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument` at all.

### AiAgentAdminRole (resource role — full CRUD)

```java
package com.vn.agent.security;

import com.vn.agent.entity.*;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

@ResourceRole(name = "AI Agent Admin", code = AiAgentAdminRole.CODE)
public interface AiAgentAdminRole {

    String CODE = "ai-agent-admin";

    @EntityPolicy(entityClass = AiConversation.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiMessage.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiToolCallAudit.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiParameters.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiKnowledgeDocument.class, actions = EntityPolicyAction.ALL)
    void adminAccess();
}
```

**Deliberately omitted (per D-07):** no `@EntityAttributePolicy`, no `@ViewPolicy`, no `@MenuPolicy`. Phase 7 adds `@ViewPolicy` + `@MenuPolicy` after views exist.

## Entity Shapes

Field proposals — planner may trim/expand. All entities carry `id`/`version`/`@InstanceName`; audit columns (`createdBy`, `createdDate`, `lastModifiedBy`, `lastModifiedDate`) follow Jmix conventions where applicable. Confidence MEDIUM on exact field lists — downstream phase needs drove these choices from REQUIREMENTS.md / CONTEXT.md, but some fields are planner's call.

### AiConversation

| Column | Type | Notes |
|--------|------|-------|
| ID | `${uuid.type}` | PK |
| VERSION | int | `@Version` |
| TITLE | varchar(255) | `@InstanceName` — first user message or admin-edited label |
| CREATED_BY | varchar(255) NOT NULL | Jmix audit — row-level filter key |
| CREATED_DATE | timestamp | Jmix audit |
| LAST_MODIFIED_BY | varchar(255) | |
| LAST_MODIFIED_DATE | timestamp | |

**Composition:** `@OneToMany @Composition @OnDelete(CASCADE)` to `List<AiMessage> messages` — mirrors `Order → OrderLine` (`[VERIFIED: jmix-app/entity/Order.java:53]`).

### AiMessage

| Column | Type | Notes |
|--------|------|-------|
| ID | `${uuid.type}` | PK |
| VERSION | int | `@Version` |
| CONVERSATION_ID | `${uuid.type}` NOT NULL | FK → AI_AGENT_CONVERSATION |
| ROLE_ | varchar(16) | `AiMessageRole` enum (USER/ASSISTANT/SYSTEM/TOOL) — note trailing underscore to avoid SQL keyword collision, same pattern as `NUMBER_` in jmix-app |
| CONTENT | text / LONGVARCHAR | message body; HSQLDB needs LONGVARCHAR for large text |
| SEQ | int | monotonic order within conversation |
| CREATED_DATE | timestamp | |

**@InstanceName:** computed `getDisplayName()` returning `role + " @ " + createdDate` (mirrors `Order.getDisplayName()` pattern).

### AiToolCallAudit

| Column | Type | Notes |
|--------|------|-------|
| ID | `${uuid.type}` | PK |
| VERSION | int | |
| CONVERSATION_ID | `${uuid.type}` | FK → AI_AGENT_CONVERSATION (nullable — some audits are standalone) |
| USER_USERNAME | varchar(255) | not a FK to the host user table — users may be deleted, audit retention is longer |
| TOOL_NAME | varchar(128) NOT NULL | e.g., `find_records` |
| ARGUMENTS_JSON | text / LONGVARCHAR | serialized tool args |
| RESULT_SUMMARY | text / LONGVARCHAR | truncated result representation |
| OUTCOME | varchar(16) NOT NULL | `AiToolCallOutcome` enum (SUCCESS/BLOCKED/ERROR) |
| DENIAL_REASON | varchar(512) | populated when OUTCOME=BLOCKED |
| LATENCY_MS | bigint | |
| STARTED_AT | timestamp NOT NULL | |
| FINISHED_AT | timestamp | null if still running (pre-entry; Phase 4) |

**@InstanceName:** `toolName + " / " + outcome`.

### AiParameters (single active, multi-row for profiles)

Per PARAM-01 ("multiple profiles (YAML blob) with exactly one marked active"):

| Column | Type | Notes |
|--------|------|-------|
| ID | `${uuid.type}` | PK |
| VERSION | int | |
| PROFILE_NAME | varchar(128) NOT NULL | `@InstanceName`; unique index |
| ACTIVE_ | boolean NOT NULL default false | trailing underscore; enforce "exactly one active" via service logic (Phase 6, not Phase 2) |
| BODY_YAML | text / LONGVARCHAR | full profile body — model id, temperature, max tokens, system prompt, enabled tool names, top-k, similarity threshold |
| CREATED_BY / CREATED_DATE / ... | | Jmix audit fields |

**Phase 2 ships the entity + DDL; `ParametersService` + CRUD + active-exclusion are Phase 6.** Don't over-engineer: no individual columns for temperature/maxTokens in Phase 2 — the YAML blob is authoritative.

### AiKnowledgeDocument

| Column | Type | Notes |
|--------|------|-------|
| ID | `${uuid.type}` | PK |
| VERSION | int | |
| FILE_NAME | varchar(255) NOT NULL | `@InstanceName` |
| MIME_TYPE | varchar(128) | |
| SIZE_BYTES | bigint | |
| STATUS | varchar(16) NOT NULL default 'PENDING' | `AiKnowledgeDocumentStatus` enum |
| ERROR_MESSAGE | varchar(1024) | populated when STATUS=FAILED |
| ALLOWED_ROLES_JSON | text | JSON array of role codes (RAG-04); empty = admin-only per RAG-06 fail-closed default |
| CREATED_BY / CREATED_DATE / ... | | Jmix audit |
| INGESTED_AT | timestamp | set when STATUS→READY |

**Phase 2 ships the entity + DDL; Phase 5 wires the ingestion pipeline.**

## Enum Definitions

Planner may adjust — values are cross-checked against downstream phases.

```java
package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

// [DESIGN: values aligned with Spring AI SPRING_AI_CHAT_MEMORY.type CHECK constraint]
public enum AiMessageRole implements EnumClass<String> {
    USER("USER"), ASSISTANT("ASSISTANT"), SYSTEM("SYSTEM"), TOOL("TOOL");
    private final String id;
    AiMessageRole(String id) { this.id = id; }
    @Override public String getId() { return id; }
    @Nullable public static AiMessageRole fromId(String id) {
        for (var v : values()) if (v.id.equals(id)) return v;
        return null;
    }
}

// [DESIGN: aligned with RAG-03 lifecycle]
public enum AiKnowledgeDocumentStatus implements EnumClass<String> {
    PENDING("PENDING"), PROCESSING("PROCESSING"), READY("READY"), FAILED("FAILED");
    // ... same boilerplate
}

// [DESIGN: aligned with AUD-03 outcome field]
public enum AiToolCallOutcome implements EnumClass<String> {
    SUCCESS("SUCCESS"), BLOCKED("BLOCKED"), ERROR("ERROR");
    // ... same boilerplate
}
```

**Alignment notes:**
- `AiMessageRole` values `USER/ASSISTANT/SYSTEM/TOOL` match the `CHECK` constraint in the Spring AI 1.1.4 chat-memory schema exactly `[VERIFIED above]` — avoids type-drift between Spring AI's raw memory table and our projection.
- `AiKnowledgeDocumentStatus` matches RAG-03 verbatim (PENDING/PROCESSING/READY/FAILED).
- `AiToolCallOutcome` three values cover Phase 4 audit needs. Phase 5 may add `DENIED` if that semantic differs from `BLOCKED`; for Phase 2 three values suffice.

Each enum requires i18n keys (see §i18n Keys).

## SPI Signatures

All 6 SPIs live in `com.vn.agent.spi`. Phase 2 ships interfaces + Javadoc. Default no-op beans provided by `SpiDefaultsAutoConfiguration`.

```java
package com.vn.agent.spi;

/**
 * Host extension point for contributing additional @Tool-annotated beans to the agent.
 * <p>Spring AI 2.x tool-callback resolution consumes every ToolContributor bean in the
 * application context; returned beans' @Tool methods are exposed via ToolCallbacks.from(bean).</p>
 * <p><b>Example:</b>
 * <pre>{@code
 * @Component
 * class CrmTools implements ToolContributor {
 *     @Override public List<Object> contribute() { return List.of(this); }
 *     @Tool(description = "Look up a CRM contact by email")
 *     public Contact lookup(@ToolParam String email) { ... }
 * }
 * }</pre>
 */
public interface ToolContributor {
    /** @return beans whose @Tool methods should be exposed; empty list = no contribution. */
    java.util.List<Object> contribute();
}

/**
 * Host extension point for injecting per-request context (user, tenant, env, correlation id)
 * into the tool-execution ToolContext. Fires once per ChatService.ask/stream call.
 */
public interface ContextContributor {
    /**
     * @param bag mutable key→value map attached to ToolContext; keys MUST be namespaced,
     *            e.g. "host.tenantId", never a bare key like "tenant".
     */
    void contribute(java.util.Map<String, Object> bag);
}

/**
 * Host extension point for appending host-specific text into the system prompt at request time.
 * Fragments are concatenated in bean-order-value order after the profile's base system prompt.
 */
public interface PromptContextContributor {
    /** @return text to append; empty string = no contribution. Avoid newlines at ends. */
    String fragment();

    default int getOrder() { return 0; }
}

/**
 * Host extension point that can veto a tool invocation before it runs.
 * Multiple guards compose by short-circuit AND — any guard throwing blocks the call and
 * writes an AiToolCallAudit row with outcome=BLOCKED and the thrown message as denialReason.
 */
public interface ToolGuard {
    /**
     * @param toolName the @Tool name being invoked
     * @param arguments the resolved tool arguments as a map
     * @throws ToolVetoedException when the invocation must be blocked
     */
    void check(String toolName, java.util.Map<String, Object> arguments) throws ToolVetoedException;
}

/**
 * Host extension point fired after every AiToolCallAudit write for side-channel observability
 * (Slack, SIEM, metrics). Listeners run in @Async fire-and-forget mode (Phase 4 wiring);
 * exceptions thrown here MUST NOT fail the primary request.
 */
public interface AuditListener {
    /** @param auditId the just-persisted AiToolCallAudit.id */
    void onToolCallAudited(java.util.UUID auditId);
}

/**
 * Host extension point to plug in custom KB ingestion sources (S3, Confluence, SharePoint).
 * Phase 5 wires IngesterManager; Phase 2 ships the interface only.
 */
public interface CustomIngester {
    /** @return stable identifier, surfaced in admin UI. */
    String getId();

    /** @return human-readable label for admin UI. */
    String getDisplayName();

    /** Pull documents from the source; Phase 5 splits + embeds + writes to vector store. */
    java.util.List<org.springframework.ai.document.Document> read();
}
```

**`ToolVetoedException`** is a simple checked/unchecked exception in the same package. Planner picks checked vs unchecked — recommended **unchecked** (`RuntimeException` subclass) so tool bodies aren't littered with `throws`.

## SpiDefaultsAutoConfiguration

Single class, 6 no-op defaults, all `@ConditionalOnMissingBean`.

```java
package com.vn.autoconfigure.agent;

import com.vn.agent.spi.*;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class SpiDefaultsAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public ToolContributor defaultToolContributor() {
        return Collections::emptyList;
    }

    @Bean @ConditionalOnMissingBean
    public ContextContributor defaultContextContributor() {
        return bag -> { /* no-op */ };
    }

    @Bean @ConditionalOnMissingBean
    public PromptContextContributor defaultPromptContextContributor() {
        return () -> "";
    }

    @Bean @ConditionalOnMissingBean
    public ToolGuard defaultToolGuard() {
        return (toolName, arguments) -> { /* allow all */ };
    }

    @Bean @ConditionalOnMissingBean
    public AuditListener defaultAuditListener() {
        return (UUID auditId) -> { /* no-op */ };
    }

    @Bean @ConditionalOnMissingBean
    public CustomIngester defaultCustomIngester() {
        return new CustomIngester() {
            @Override public String getId() { return "noop"; }
            @Override public String getDisplayName() { return "No-op"; }
            @Override public List<Document> read() { return Collections.emptyList(); }
        };
    }
}
```

**Register in** `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.vn.autoconfigure.agent.AIAutoConfiguration
com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration
```

**Multi-bean caveat:** `@ConditionalOnMissingBean` defaults to "no bean of this exact type." If a host declares MULTIPLE `ToolContributor` beans, the condition would still install the default (because it checks for the exact-type match but then Spring would inject multiple + default). Phase 3+ consumers should inject `List<ToolContributor>` and ignore the no-op entry (identifiable because it returns empty). Document this in the SPI Javadoc.

## AIConfiguration extension (D-02)

The existing `AIConfiguration` must widen its `@JmixModule(dependsOn=)` to pull in the Jmix data + security configurations so the new entities/roles register correctly:

```java
// [EDIT: existing file, new deps]
@JmixModule(dependsOn = {
        io.jmix.eclipselink.EclipselinkConfiguration.class,
        io.jmix.data.DataConfiguration.class,           // ADD — needed for Liquibase auto-discovery of add-on DDL? Verify.
        io.jmix.security.SecurityConfiguration.class,   // ADD — needed so @ResourceRole/@RowLevelRole register via Jmix role catalog
        io.jmix.flowui.FlowuiConfiguration.class
})
```

**Confidence:** MEDIUM on whether `DataConfiguration` is strictly required for Liquibase — Jmix's doc language ("Changelogs from dependencies are executed") suggests the mechanism is build-time classpath scanning, not a `@JmixModule` dep. Planner verifies by booting without the dep first; add only if missing changelogs symptomize.

## i18n Keys

All keys namespaced under the Jmix-convention `<package>/<name>` pattern (`[VERIFIED: jmix-app/messages_en.properties:5-52]`).

### Required keys (EN — `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties`)

```properties
# ===== Entities =====
com.vn.agent.entity/AiConversation=Conversation
com.vn.agent.entity/AiConversation.id=ID
com.vn.agent.entity/AiConversation.version=Version
com.vn.agent.entity/AiConversation.title=Title
com.vn.agent.entity/AiConversation.createdBy=Created by
com.vn.agent.entity/AiConversation.createdDate=Created
com.vn.agent.entity/AiConversation.messages=Messages

com.vn.agent.entity/AiMessage=Message
com.vn.agent.entity/AiMessage.id=ID
com.vn.agent.entity/AiMessage.conversation=Conversation
com.vn.agent.entity/AiMessage.role=Role
com.vn.agent.entity/AiMessage.content=Content
com.vn.agent.entity/AiMessage.seq=Sequence
com.vn.agent.entity/AiMessage.createdDate=Created
com.vn.agent.entity/AiMessage.displayName=Name

com.vn.agent.entity/AiToolCallAudit=Tool call audit
com.vn.agent.entity/AiToolCallAudit.id=ID
com.vn.agent.entity/AiToolCallAudit.conversation=Conversation
com.vn.agent.entity/AiToolCallAudit.userUsername=User
com.vn.agent.entity/AiToolCallAudit.toolName=Tool
com.vn.agent.entity/AiToolCallAudit.argumentsJson=Arguments
com.vn.agent.entity/AiToolCallAudit.resultSummary=Result
com.vn.agent.entity/AiToolCallAudit.outcome=Outcome
com.vn.agent.entity/AiToolCallAudit.denialReason=Denial reason
com.vn.agent.entity/AiToolCallAudit.latencyMs=Latency (ms)
com.vn.agent.entity/AiToolCallAudit.startedAt=Started
com.vn.agent.entity/AiToolCallAudit.finishedAt=Finished

com.vn.agent.entity/AiParameters=Parameters
com.vn.agent.entity/AiParameters.id=ID
com.vn.agent.entity/AiParameters.profileName=Profile
com.vn.agent.entity/AiParameters.active=Active
com.vn.agent.entity/AiParameters.bodyYaml=Body (YAML)

com.vn.agent.entity/AiKnowledgeDocument=Knowledge document
com.vn.agent.entity/AiKnowledgeDocument.id=ID
com.vn.agent.entity/AiKnowledgeDocument.fileName=File name
com.vn.agent.entity/AiKnowledgeDocument.mimeType=MIME type
com.vn.agent.entity/AiKnowledgeDocument.sizeBytes=Size (bytes)
com.vn.agent.entity/AiKnowledgeDocument.status=Status
com.vn.agent.entity/AiKnowledgeDocument.errorMessage=Error
com.vn.agent.entity/AiKnowledgeDocument.allowedRolesJson=Allowed roles
com.vn.agent.entity/AiKnowledgeDocument.ingestedAt=Ingested

# ===== Enums =====
com.vn.agent.entity/AiMessageRole.USER=User
com.vn.agent.entity/AiMessageRole.ASSISTANT=Assistant
com.vn.agent.entity/AiMessageRole.SYSTEM=System
com.vn.agent.entity/AiMessageRole.TOOL=Tool

com.vn.agent.entity/AiKnowledgeDocumentStatus.PENDING=Pending
com.vn.agent.entity/AiKnowledgeDocumentStatus.PROCESSING=Processing
com.vn.agent.entity/AiKnowledgeDocumentStatus.READY=Ready
com.vn.agent.entity/AiKnowledgeDocumentStatus.FAILED=Failed

com.vn.agent.entity/AiToolCallOutcome.SUCCESS=Success
com.vn.agent.entity/AiToolCallOutcome.BLOCKED=Blocked
com.vn.agent.entity/AiToolCallOutcome.ERROR=Error

# ===== Resource roles =====
com.vn.agent.security/AiAgentUserRole=AI Agent User
com.vn.agent.security/AiAgentAdminRole=AI Agent Admin
com.vn.agent.security/AiAgentUserRowLevelRole=AI Agent User (row-level)
```

### VI translations (`messages_vi.properties` — NEW file)

```properties
com.vn.agent.entity/AiConversation=Hội thoại
com.vn.agent.entity/AiConversation.title=Tiêu đề
com.vn.agent.entity/AiConversation.createdBy=Người tạo
# ... (planner fills; key set identical to EN)

com.vn.agent.entity/AiMessageRole.USER=Người dùng
com.vn.agent.entity/AiMessageRole.ASSISTANT=Trợ lý
com.vn.agent.entity/AiMessageRole.SYSTEM=Hệ thống
com.vn.agent.entity/AiMessageRole.TOOL=Công cụ

com.vn.agent.entity/AiKnowledgeDocumentStatus.PENDING=Chờ xử lý
com.vn.agent.entity/AiKnowledgeDocumentStatus.PROCESSING=Đang xử lý
com.vn.agent.entity/AiKnowledgeDocumentStatus.READY=Sẵn sàng
com.vn.agent.entity/AiKnowledgeDocumentStatus.FAILED=Thất bại

com.vn.agent.entity/AiToolCallOutcome.SUCCESS=Thành công
com.vn.agent.entity/AiToolCallOutcome.BLOCKED=Bị chặn
com.vn.agent.entity/AiToolCallOutcome.ERROR=Lỗi

com.vn.agent.security/AiAgentUserRole=Người dùng AI Agent
com.vn.agent.security/AiAgentAdminRole=Quản trị viên AI Agent
com.vn.agent.security/AiAgentUserRowLevelRole=Người dùng AI Agent (lọc hàng)
```

Every EN key MUST have a VI counterpart — CLAUDE.md: "Single-locale messages — ALWAYS add to ALL locale files."

**Host locale config `[VERIFIED: jmix-app/application.properties:9]`** — `jmix.core.available-locales=vi,en` is already set; no host edit needed.

## Spring AI version delta (1.0.2 → 1.1.4)

**Context:** STACK.md + prior phase CONTEXT.md reference 1.0.2. `ai-agent/build.gradle:35` already sets `springAiVersion = "1.1.4"` (Phase 1 BOM actually pinned 1.0.2 per STATE.md line 50 but was bumped during execution — verify in git log). D-10 mandates updating STACK.md to 1.1.4 during Phase 2.

| Surface | 1.0.2 | 1.1.4 | Impact on Phase 2 |
|---------|-------|-------|-------------------|
| `spring-ai-starter-model-chat-memory-repository-jdbc` coordinate | present | present | no change |
| `SPRING_AI_CHAT_MEMORY` table shape | ASSUMED identical | 4 cols (conversation_id, content, type, timestamp) — VERIFIED | **verify before porting** |
| CHECK constraint on `type` | ASSUMED USER/ASSISTANT/SYSTEM/TOOL | confirmed USER/ASSISTANT/SYSTEM/TOOL | none — aligns with our enum |
| `spring-ai-starter-vector-store-pgvector` coordinate | present | present | no change |
| `vector_store` reference table shape | same (id/content/metadata/embedding) | same | none |
| `PgVectorStore.builder()` API | `.vectorTableName()` present | same | none |
| `initialize-schema` property for chat-memory | `embedded/always/never` | same | none |
| `initialize-schema` property for pgvector | boolean | boolean | none |

**No schema deltas detected for the two tables Phase 2 ports. Mark as `[ASSUMED identical 1.0.2 vs 1.1.4]` — planner to spot-check by diffing `schema-postgresql.sql` between tags before committing.**

## Test Strategy

`workflow.nyquist_validation` is **false** per `.planning/config.json`. No VALIDATION.md scoping needed.

Phase 2 verifies through a single boot test in `jmix-app`:

1. **Liquibase boot test** (HSQLDB): delete `.jmix/hsqldb/jmixapp.*`, run `./gradlew :jmix-app:bootRun` (or integration test with `@SpringBootTest`), assert all `AI_AGENT_*` tables present + `SPRING_AI_CHAT_MEMORY` present + `AI_AGENT_KB_VECTOR_STORE` absent (pgvector changeset MARK_RAN on HSQLDB).
2. **Entity persistence test** (`@SpringBootTest`): `dataManager.create(AiConversation.class)` → set fields → `dataManager.save()` → query back. Repeat for all 5 entities.
3. **Row-level role smoke** (`@SpringBootTest` with `@WithUserDetails`): user `alice` creates a conversation, user `bob` queries `AiConversation.list()` — assert zero results. Switch back to `alice`, query — 1 result.
4. **SPI default-bean smoke** (`@SpringBootTest`): auto-wire all 6 SPI beans; assert non-null, assert defaults return empty/no-op values.
5. **Role catalog smoke** (`@SpringBootTest`): `roleRepository.getRoleByCode(AiAgentAdminRole.CODE)` is non-null, contains 5 entity policies. Same for user role + row-level role.

No @Tag("live") tests in Phase 2 — zero LLM calls in scope.

## Code Examples

### Using `Metadata.create()` (per CLAUDE.md)

```java
// [CITED: CLAUDE.md — "Instantiate entities using Metadata.create() ... Don't use entity constructor directly."]
@Autowired private Metadata metadata;
@Autowired private DataManager dataManager;

AiConversation conv = metadata.create(AiConversation.class);
conv.setTitle("Day 1 Q&A");
dataManager.save(conv);
```

### Row-level policy session parameter

```java
// [VERIFIED: jmix-context7 — filter by session attribute]
@JpqlRowLevelPolicy(
    entityClass = AiConversation.class,
    where = "{E}.createdBy = :current_user_username")
void conversation();
```

`{E}` is the entity alias placeholder; `:current_user_username` is a framework-bound parameter (pulled from `CurrentAuthentication.getUser()`).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Spring AI 1.0.2 BOM | 1.1.4 BOM | Between Phase 1 wave start and Phase 2 start | STACK.md needs D-10 update. Chat-memory + pgvector table shapes unchanged for the cases Phase 2 covers |
| `spring-ai-openai-spring-boot-starter` (1.x) | `spring-ai-starter-model-openai` (2.x naming) | Spring AI 2.x rename | already correctly referenced in existing code |
| `QuestionAnswerAdvisor` classical RAG | `RetrievalAugmentationAdvisor` modular RAG | Spring AI 1.0+ | irrelevant to Phase 2 |

**Deprecated / outdated:**
- `StructuredOutputValidationAdvisor` — referenced in PROJECT.md; class name not confirmed in 1.1.4. Phase 6 problem, not Phase 2.
- `FunctionCallback` API — replaced by `@Tool`. Phase 3 problem.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring AI 1.0.2 `schema-postgresql.sql` and `schema-hsqldb.sql` are identical (or compatibly additive) to 1.1.4 | §Chat-Memory Schema | LOW — BOM is already at 1.1.4 per build.gradle; only STACK.md needs the version bump. If 1.0.2 shape differs, Phase 1 chat-memory work isn't done yet so no migration cost |
| A2 | `SpiDefaultsAutoConfiguration` default beans don't conflict when a host ships multiple contributors of the same type | §SpiDefaultsAutoConfiguration | MEDIUM — if Spring resolves "1 bean of type T" ambiguously with >1 host bean, the no-op might persist. Planner tests this in Step 5 (SPI default-bean smoke with a host-supplied `ToolGuard`) |
| A3 | `DataConfiguration` dep in `@JmixModule(dependsOn=)` is required | §AIConfiguration extension | LOW — if missing, Liquibase probably still runs from classpath. Planner verifies empirically by booting without the dep |
| A4 | End users will need BOTH `AiAgentUserRole` (resource) AND `AiAgentUserRowLevelRole` (row-level) assigned, documented in README | §Row-Level Security | LOW — standard Jmix pattern |
| A5 | `available-locales=vi,en` stays correctly configured in jmix-app (verified today), and add-on's VI messages load via classpath scanning without additional config | §i18n Keys | LOW — verified in jmix-app/application.properties |
| A6 | Phase 6's `ParametersService` can enforce "exactly one active profile" via service logic; Phase 2 doesn't need a DB-level unique partial index | §AiParameters | LOW — over-engineering avoided; if two profiles end up active concurrently, it's a bug surfaced in Phase 6 integration tests |

## Open Questions

1. **Should `AiConversation` carry a `CURRENT_PROFILE_ID` FK to `AiParameters` (per-conversation override)?**
   - What we know: PARAM-03 says per-conversation override is a Phase 6 API-level feature.
   - What's unclear: whether the override is stored on the conversation or passed per-request only.
   - Recommendation: OMIT in Phase 2. Phase 6 adds the column via additional changeset if needed. Defers a coupling decision.

2. **Does `AiMessage.content` need to be encrypted at rest?**
   - What we know: no Phase 2 encryption requirement in REQUIREMENTS.md or CONTEXT.md.
   - What's unclear: compliance posture for Vietnamese data-localization rules.
   - Recommendation: NO encryption in Phase 2 (would require `@JmixEntityAttributePolicy` which D-07 defers). Revisit when a concrete compliance trigger lands.

3. **Where does `ChatServiceSmokeRunner` (Phase 1) land after row-level roles appear?**
   - What we know: runner logs `ChatService bean present` at boot with anonymous context.
   - What's unclear: will row-level policies block its CommandLineRunner use because `CurrentAuthentication` is absent?
   - Recommendation: runner is READ-ONLY and doesn't touch `AiConversation`/`AiMessage` in Phase 2 scope; no change needed. Phase 4 may retire it.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| HSQLDB | Test runtime (boot smoke) | ✓ | transitive via jmix-data | — |
| PostgreSQL | Postgres-gated changelogs (070, 060 postgres changeSet) | ✗ in dev | — | Changesets skip via `dbms="postgresql"` preCondition; pgvector DDL verified only in CI/prod |
| Jmix Studio | Entity scaffolding convenience | optional | — | Hand-code entities following Customer.java template |
| JetBrains MCP | `get_file_problems` per CLAUDE.md workflow | optional | — | `./gradlew build` catches compilation errors |

**Missing dependencies with fallback:**
- PostgreSQL not required for Phase 2 acceptance — pgvector changeset is gated and HSQLDB boot succeeds with the table absent. Real validation of `070-ai-kb-vector-store.xml` deferred to the Phase 5 environment where Postgres is required anyway.

**Missing dependencies with no fallback:** none.

## Project Constraints (from CLAUDE.md)

| Directive | Phase 2 Application |
|-----------|---------------------|
| JPA entities: `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName` | All 5 entities MUST follow this exact template |
| No Lombok on entities | Hand-written getters/setters only |
| `@Composition` for parent-child aggregates | `AiConversation → List<AiMessage>` |
| Instantiate via `Metadata.create()` / `DataManager.create()` | Tests + future services; never `new AiConversation()` |
| Resource roles as interfaces in `security/` with `@ResourceRole` | `AiAgentUserRole` + `AiAgentAdminRole` in `com.vn.agent.security` |
| Liquibase changelogs in `**/liquibase/changelog/**.xml`, step-numbered | `010-…` through `070-…` under `com/vn/agent/liquibase/changelog/` |
| Include new changelogs in the main `changelog.xml` | Add-on master at `com/vn/agent/liquibase/changelog.xml` + host edit to include it |
| Hardcoded UI text FORBIDDEN — all via `msg://` keys | Every entity/enum/role label has key in `messages.properties` + `messages_vi.properties` |
| Single-locale messages FORBIDDEN — add to ALL locale files | Every EN key has VI counterpart |
| `EntityManager` FORBIDDEN | DataManager-only for all persistence (smoke tests + service code) |
| Business logic in views FORBIDDEN | No views in Phase 2; constraint trivially satisfied |
| `get_file_problems` via JetBrains MCP after each edit | Planner includes this in task workflow |
| `./gradlew test` to verify nothing is broken | Success criterion #1 |

## Sources

### Primary (HIGH confidence)
- `/jmix-framework/jmix-context7` (Context7 — High reputation) — row-level policies (`@JpqlRowLevelPolicy` + `:current_user_username`), add-on changelog hierarchy ("Changelogs from dependencies are executed on your database before the ones of the application"), `@Composition` + `@OnDelete` patterns.
- `raw.githubusercontent.com/spring-projects/spring-ai/v1.1.4/memory/repository/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql` — exact 1.1.4 chat-memory DDL for Postgres.
- `raw.githubusercontent.com/spring-projects/spring-ai/v1.1.4/memory/repository/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-hsqldb.sql` — exact 1.1.4 chat-memory DDL for HSQLDB.
- `/spring-projects/spring-ai` via Context7 — pgvector reference schema, `PgVectorStore.builder()` API, `initialize-schema` property semantics.
- `jmix-app/src/main/java/com/vn/jmixapp/entity/{Customer,Order,OrderStatus}.java` — verified entity shape, @Composition pattern.
- `jmix-app/src/main/java/com/vn/jmixapp/security/{FullAccessRole,SampleDataRole}.java` — verified role annotation patterns.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/{changelog.xml,changelog/*.xml}` — verified step-numbered Liquibase layout + host master include pattern.
- `jmix-app/src/main/resources/application.properties` — verified `jmix.core.available-locales=vi,en` already set; HSQLDB datasource config.
- `ai-agent/build.gradle:35` — Spring AI 1.1.4 BOM already wired.
- `CLAUDE.md` — Jmix conventions authoritative for coding patterns.

### Secondary (MEDIUM confidence)
- `.planning/research/STACK.md` — Spring AI artifact list + Gradle skeletons (note: references 1.0.2, supersede with 1.1.4 per D-10).
- `.planning/research/ARCHITECTURE.md` — component responsibility map (Phase 2 components C1 + persistence layer partially land).
- `.planning/research/PITFALLS.md` — pitfall #11 (JDBC memory / Liquibase collision) and #12 (add-on packaging) directly inform the research findings above.

### Tertiary (LOW confidence)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — referenced in CONTEXT.md canonical-refs but not re-inspected this session for pgvector DDL. If planner needs a second reference, worth a spot-check.
- Spring AI 1.0.2 exact schema deltas vs 1.1.4 — not inspected; assumed-identical flagged in Assumptions Log (A1).

## Metadata

**Confidence breakdown:**
- Standard stack (existing): HIGH — inherited from Phase 1.
- Entity shapes: MEDIUM — exact field lists are planner's call; jmix-app patterns authoritative for conventions.
- Liquibase skeletons: HIGH for master + 060 + 070; MEDIUM for step files 010-050 (entity-specific columns are reasonable defaults).
- Row-level security: HIGH — exact pattern verified in jmix-context7.
- Chat-memory schema porting: HIGH — SQL fetched verbatim from v1.1.4 tag.
- SPI signatures: HIGH on surface; MEDIUM on exact method names (planner's call if "contribute" vs "tools" etc. feel wrong).
- SpiDefaultsAutoConfiguration: HIGH — `@ConditionalOnMissingBean` is a 10-year-old Spring Boot idiom.
- i18n keys: HIGH — pattern verbatim from jmix-app/messages_en.properties.

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (30 days) — Jmix 2.8 is stable; Spring AI 1.1.x schema unlikely to shift within a minor bump.
