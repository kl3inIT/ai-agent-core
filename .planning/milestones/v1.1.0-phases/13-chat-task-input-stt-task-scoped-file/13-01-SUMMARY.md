---
phase: 13-chat-task-input-stt-task-scoped-file
plan: 01
subsystem: taskfile
tags:
  - jmix-entity
  - liquibase
  - agentstore
  - security-role
  - i18n
  - configuration
requires:
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-CONTEXT.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-SPEC.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-PATTERNS.md
provides:
  - "AiTaskFile entity (@Store agentstore) — D-03 schema lock for Wave 2 resolver/repository/cleanup"
  - "AiTaskFileProperties (ai-agent.task-file.*) — TTL + size cap binding"
  - "Security roles (user READ+CREATE+DELETE, row-level, admin ALL) — SEC-06 partial"
  - "Default chat model swap to qwen/qwen3.6-35b-a3b (REQ-1)"
affects:
  - ai-agent/ai-agent
  - ai-agent/ai-agent-starter
  - jmix-app
tech-stack:
  added: []
  patterns:
    - "Phase 11 audit-column convention (createdDate / lastModifiedDate)"
    - "Phase 11 @ConfigurationProperties shape (mirrors AiAgentMutationProperties)"
    - "Phase 2 @JpqlRowLevelPolicy with :current_user_username session parameter"
    - "Phase 5 @PropertyDatatype(\"fileRef\") for FileRef storage column"
    - "Liquibase agentstore-changelog NNN-name.xml ordering with <includeAll>"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiTaskFile.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/090-ai-task-file.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileProperties.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - jmix-app/src/main/resources/application.properties
    - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
decisions:
  - "AIConfiguration uses both @ConfigurationPropertiesScan AND explicit @EnableConfigurationProperties(AiTaskFileProperties.class) — the explicit registration satisfies the plan's verify gate and makes Phase 13 wiring discoverable; the scan was already present and remains."
  - "MESSAGE_ID FK created in a separate addForeignKeyConstraint changeSet (id=3) with onDelete=\"SET NULL\" — inline references=... on a column does not portably support onDelete; this aligns the DB schema with JPA @OnDelete(DeletePolicy.UNLINK) per REVIEWS HIGH-2."
  - "injectedAt OffsetDateTime is the AUTHORITATIVE pending-state marker; messageId is a separate optional UI-display link (REVIEWS HIGH-1). Resolver predicate (Plan 02) is injectedAt IS NULL AND expiresAt > :now — never messageId IS NULL."
  - "User role grants DELETE on AiTaskFile so chip removal in Plan 04 calls dataManager.remove(row) through the secured DataManager (REVIEWS HIGH-3); row-level role narrows DELETE scope to userUsername = :current_user_username, so users only delete their own rows."
  - "default-params.yaml model line swapped alongside application.properties because the starter seeds the AiParameters row at clean boot and that row overrides application.properties at runtime (REVIEWS HIGH-9)."
metrics:
  duration: "~25 minutes"
  completed: "2026-05-06"
  tasks: 2
  files: 11
---

# Phase 13 Plan 01: AiTaskFile Foundation + Default Model Swap Summary

JPA entity + Liquibase 090 + @ConfigurationProperties + bilingual messages + 3 role files + qwen/qwen3.6-35b-a3b model swap (application.properties + starter default-params.yaml) — D-03 schema and REQ-1 model swap landed; downstream Wave 2/3/4 plans depend on this foundation.

## Tasks Executed

### Task 1 — AiTaskFile entity, Liquibase 090, AiTaskFileProperties, AIConfiguration

**Commit:** `0d0032a`

- Created `entity/AiTaskFile.java`: `@Store(name = "agentstore")` + `@Entity(name = "ai_AiTaskFile")` + `@Table(name = "AI_TASK_FILE", indexes = {...})` with four indexes (CONVERSATION_ID, MESSAGE_ID, EXPIRES_AT, INJECTED_AT). Fields per D-03:
  - UUID id, Integer version
  - `@NotNull @ManyToOne @OnDelete(CASCADE) conversation` (FK to AI_AGENT_CONVERSATION)
  - `@ManyToOne @OnDelete(UNLINK) message` (nullable FK to AI_AGENT_MESSAGE)
  - `OffsetDateTime injectedAt` (REVIEWS HIGH-1 — authoritative pending-state marker)
  - userUsername, `@InstanceName filename`, contentType, sizeBytes
  - `@PropertyDatatype("fileRef") FileRef storageRef`
  - audit columns (createdDate / lastModifiedDate convention matching AiMessage / AiMutationIntent / AiUiSettings)
  - `@NotNull OffsetDateTime expiresAt`
  - All getters/setters spelled out (no Lombok per CLAUDE.md).
- Created `liquibase/agentstore-changelog/090-ai-task-file.xml`:
  - changeSet id="1": createTable AI_TASK_FILE with CONVERSATION_ID FK referencing `AI_AGENT_CONVERSATION(ID)` deleteCascade=true; MESSAGE_ID column declared without inline FK so the SET NULL constraint can be added separately.
  - changeSet id="2": four createIndex elements matching the entity `@Index` names, including IDX_AI_TASK_FILE__INJECTED_AT.
  - changeSet id="3": addForeignKeyConstraint MESSAGE_ID → AI_AGENT_MESSAGE(ID) onDelete="SET NULL" (REVIEWS HIGH-2). DB and JPA @OnDelete(UNLINK) now agree.
  - Auto-included by `<includeAll>` in agentstore-changelog.xml — no manual `<include>` line.
- Created `taskfile/AiTaskFileProperties.java`: `@ConfigurationProperties("ai-agent.task-file")` with `Duration ttl = PT1H` and `long maxFileSizeBytes = 104_857_600` (mirrors RAG cap per CONTEXT D-04 Claude's Discretion).
- Modified `AIConfiguration.java`: added explicit `@EnableConfigurationProperties({AiTaskFileProperties.class})` next to the existing `@ConfigurationPropertiesScan`. Both annotations coexist; the explicit registration satisfies the plan's verify gate and makes the Phase 13 binding visible without relying solely on the scan.

Verify: `./gradlew :ai-agent:ai-agent:compileJava` BUILD SUCCESSFUL. All grep gates pass: `@Store(name = "agentstore")` present, `createdAt` count = 0 on entity, `AI_CONVERSATION(ID)` count = 0 on changelog, `CREATED_AT` count = 0 on changelog, `INJECTED_AT` declared (column + index), `onDelete="SET NULL"` present, `AiTaskFileProperties.class` referenced in AIConfiguration.

### Task 2 — Role extensions + bilingual messages + default-model swap

**Commit:** `cc63c51`

- `AiAgentUserRole.userAccess()`: appended `@EntityPolicy(entityClass = AiTaskFile.class, actions = {READ, CREATE, DELETE})` (REVIEWS HIGH-3 — DELETE granted so Plan 04 chip removal works through the secured DataManager). Inline comment documents the row-level scoping invariant.
- `AiAgentUserRowLevelRole`: appended `taskFile()` `@JpqlRowLevelPolicy` with `where = "{E}.userUsername = :current_user_username"` (D-03 — AiTaskFile carries its own userUsername column; not chained via conversation.createdBy).
- `AiAgentAdminRole.adminAccess()`: appended `@EntityPolicy(entityClass = AiTaskFile.class, actions = ALL)`.
- `messages_en.properties` + `messages_vi.properties`: appended 16 `com.vn.agent.entity/AiTaskFile*` keys (entity caption + 15 attribute captions including `injectedAt`) + 5 `chatView.attachments.*` UI keys. Locale parity verified: 16 entity-keys in each bundle.
- `application.properties`:
  - `jmix.ai-agent.defaults.model` → `qwen/qwen3.6-35b-a3b`
  - `spring.ai.openai.chat.options.model` → `qwen/qwen3.6-35b-a3b`
  - `spring.ai.openai.embedding.options.model=qwen/qwen3-embedding-4b` UNCHANGED
  - Added `ai-agent.task-file.ttl=PT1H` and `ai-agent.task-file.max-file-size-bytes=104857600`.
- `ai-agent-starter/src/main/resources/default-params.yaml`: `model:` line swapped to `qwen/qwen3.6-35b-a3b` (REVIEWS HIGH-9 — seeds AiParameters at clean boot; overrides application.properties at runtime).

Verify: `./gradlew :ai-agent:ai-agent:compileJava` BUILD SUCCESSFUL. All grep gates pass: en/vi locale-parity counts identical (16/16), exactly 2 `qwen/qwen3.6-35b-a3b` lines in application.properties, 0 `openai/gpt-4o-mini` references in both application.properties AND default-params.yaml, `:current_user_username` substring present, `AiTaskFile.class` referenced with READ/CREATE/DELETE in AiAgentUserRole and ALL in AiAgentAdminRole.

## Deviations from Plan

**None — plan executed exactly as written.**

The plan-instructed `@EnableConfigurationProperties({...})` "append to existing" landed as a fresh annotation because `AIConfiguration` had no prior `@EnableConfigurationProperties` (it relied on `@ConfigurationPropertiesScan`). The verify gate `grep AiTaskFileProperties.class AIConfiguration.java` is satisfied; both annotations coexist. This was anticipated by the plan's verify text ("Add `AiTaskFileProperties.class` to the existing `@EnableConfigurationProperties({...})` annotation") and is documented above as a decision rather than a deviation.

## Authentication Gates

None.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` — BUILD SUCCESSFUL after Task 1 and again after Task 2.
- All plan-specified grep gates passed (Task 1 + Task 2).
- Locale-parity invariant: 16 `com.vn.agent.entity/AiTaskFile` keys in each of `messages_en.properties` and `messages_vi.properties`.
- Model-swap invariant: exactly 2 `qwen/qwen3.6-35b-a3b` occurrences in `application.properties`; 0 `openai/gpt-4o-mini` in both `application.properties` and `default-params.yaml`; embedding model `qwen/qwen3-embedding-4b` unchanged.
- Schema-target invariant: 0 `AI_CONVERSATION(ID)` in `090-ai-task-file.xml`; FK target is `AI_AGENT_CONVERSATION(ID)` (Blocker 3 fix).
- Audit-column convention: 0 `createdAt` in entity; 0 `CREATED_AT` in changelog. Convention is `createdDate` / `CREATED_DATE` (Warning 5 fix).
- REVIEWS HIGH-1: `injectedAt` field present in entity; `INJECTED_AT` column and index present in changelog.
- REVIEWS HIGH-2: separate `<addForeignKeyConstraint onDelete="SET NULL">` changeSet for MESSAGE_ID.
- REVIEWS HIGH-3: `EntityPolicyAction.DELETE` present for AiTaskFile in AiAgentUserRole.
- REVIEWS HIGH-9: `default-params.yaml` model swapped alongside application.properties.

## Self-Check

**1. Created files exist:**
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiTaskFile.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/090-ai-task-file.xml
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileProperties.java

**2. Commits exist:**
- FOUND: 0d0032a (Task 1 — entity + changelog + properties + AIConfiguration)
- FOUND: cc63c51 (Task 2 — roles + messages + model swap)

## Self-Check: PASSED
