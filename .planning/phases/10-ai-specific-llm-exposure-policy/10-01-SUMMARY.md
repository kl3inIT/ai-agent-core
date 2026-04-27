---
phase: 10
plan: 01
subsystem: ai-exposure
tags: [entity, liquibase, agentstore, exposure-policy]
requires:
  - phase-09 (Phase 9 prompt-contract baseline; this plan is pure additive on top)
provides:
  - AiExposureRule (JPA entity, agentstore)
  - AiExposureRuleMode (enum: EXCLUDE only)
  - ChunkMetadata.SOURCE_ENTITY ("source_entity")
  - AiKnowledgeDocument.sourceEntityName (nullable varchar(255))
  - AI_EXPOSURE_RULE table (Liquibase 060)
  - AI_AGENT_KNOWLEDGE_DOCUMENT.SOURCE_ENTITY_NAME column (Liquibase 061)
affects:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java (added SOURCE_ENTITY constant)
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java (added sourceEntityName field)
tech-stack:
  added: []
  patterns:
    - "Jmix @JmixEntity + @Store(agentstore) + UUID/@Version/@InstanceName per CLAUDE.md"
    - "EnumClass<String> with getId/fromId for enum-backed String DB column (matches AiKnowledgeDocumentStatus)"
    - "Liquibase changelogs auto-loaded via existing <includeAll> on agentstore-changelog directory"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleMode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/061-ai-knowledge-document-source-entity.xml
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java
decisions:
  - "AiExposureRule entity-level only — no attributePath field per CONTEXT.md decision 2026-04-27 (supersedes REQUIREMENTS EXP-01 wording)"
  - "AiExposureRuleMode is EXCLUDE-only — no ALLOW value; rules can only narrow LLM-visible surface"
  - "JPQL entity name aiExposure_AiExposureRule (matches new com.vn.agent.exposure package namespace)"
  - "Unique constraint UNQ_AI_EXPOSURE_RULE_ENTITY_NAME enforces one-rule-per-entity at the DB layer (RESEARCH discretion choice)"
  - "Liquibase author 'ai-agent' matching 050-ai-knowledge-document.xml convention"
  - "createdDate/lastModifiedDate typed as OffsetDateTime (matching AiKnowledgeDocument pattern), persisted as datetime"
metrics:
  duration_min: 15
  tasks_completed: 2
  files_changed: 6
  completed_date: "2026-04-27"
---

# Phase 10 Plan 01: AI Exposure Policy — Entity Foundations Summary

Data-model foundations for the Phase 10 LLM exposure policy: `AiExposureRule` Jmix entity (entity-level, EXCLUDE-only) in `agentstore`, the `ChunkMetadata.SOURCE_ENTITY` constant that downstream RAG plans consume, and the nullable `AiKnowledgeDocument.sourceEntityName` field with both Liquibase changelogs.

## Objective Recap

Lay down the data model that every other Phase 10 plan depends on: a Jmix entity for admin-governed denylist rules, a single source-of-truth chunk-metadata key, and a per-document `sourceEntityName` field that ingestion (Wave 3) will mirror into chunk metadata. No service layer, no UI — entity, enum, constants, and schema migrations only.

Per CONTEXT.md decision 2026-04-27: entity-level rules only in v1.1; the `attributePath` field that REQUIREMENTS EXP-01 mentions is intentionally absent.

## What Was Built

### Task 1 — `com.vn.agent.exposure` package

Two new files in a new cohesive `exposure` package:

- **`AiExposureRuleMode`** (enum, `EnumClass<String>`):
  - Single value `EXCLUDE` with `getId()` returning `"EXCLUDE"`.
  - `fromId(String)` matches the project's `AiKnowledgeDocumentStatus` pattern.
  - Javadoc explicitly notes there is no ALLOW value and that adding one is a deliberate decision.
- **`AiExposureRule`** (JPA entity):
  - `@Store(name = "agentstore")`, `@JmixEntity`, `@Entity(name = "aiExposure_AiExposureRule")`, `@Table(name = "AI_EXPOSURE_RULE")`.
  - Indexes: `IDX_AI_EXPOSURE_RULE_ENTITY_NAME` (unique on `ENTITY_NAME`) and `IDX_AI_EXPOSURE_RULE_ENABLED`.
  - Fields: `id` (UUID + `@JmixGeneratedValue`), `version` (`@Version`), `entityName` (`@InstanceName`, `@NotNull`, unique, length 255), `mode` (String backed by `AiExposureRuleMode` via `getId()`/`fromId()`, default `EXCLUDE`), `enabled` (Boolean default true), audit fields `createdBy`/`createdDate`/`lastModifiedBy`/`lastModifiedDate` typed `OffsetDateTime`.
  - Typed `getMode()` / `setMode()` convert between `String` column and `AiExposureRuleMode` enum (matches `AiKnowledgeDocument.getStatus()` pattern).
  - No Lombok, no `attributePath`, no `EntityManager`.

**Commit:** `e4fed35` — `feat(10-01): add AiExposureRule entity and AiExposureRuleMode enum`

### Task 2 — Chunk metadata constant + document column + Liquibase

- **`ChunkMetadata.SOURCE_ENTITY = "source_entity"`** added between `ALLOWED_ROLES` and `ROLE_FLAG_PREFIX`. Javadoc records the rename-as-breaking-change contract (full reingest required) and points to the EXP-05 NOT IN filter consumer.
- **`AiKnowledgeDocument.sourceEntityName`** — nullable `@Column(name = "SOURCE_ENTITY_NAME", length = 255)` with standard getter/setter, placed immediately after `allowedRolesJson` to keep the persistence-related fields colocated.
- **Liquibase `060-ai-exposure-rule.xml`** — two changeSets:
  - `1`: `createTable AI_EXPOSURE_RULE` with all columns (`ID ${uuid.type}` PK, `VERSION` int default 1, `ENTITY_NAME` varchar(255) not null, `MODE` varchar(16) default `EXCLUDE` not null, `ENABLED` boolean default true not null, audit columns).
  - `2`: `addUniqueConstraint UNQ_AI_EXPOSURE_RULE_ENTITY_NAME` and `createIndex IDX_AI_EXPOSURE_RULE_ENABLED`.
- **Liquibase `061-ai-knowledge-document-source-entity.xml`** — two changeSets:
  - `1`: `addColumn SOURCE_ENTITY_NAME varchar(255)` to `AI_AGENT_KNOWLEDGE_DOCUMENT`.
  - `2`: `createIndex IDX_AI_AGENT_KNOWLEDGE_DOC_SOURCE_ENTITY` on `SOURCE_ENTITY_NAME`.

Both changelogs are picked up automatically by the existing `<includeAll path=".../agentstore-changelog">` in `agentstore-changelog.xml`; no manual include was required.

**Commit:** `676c576` — `feat(10-01): add SOURCE_ENTITY chunk metadata + sourceEntityName field + Liquibase`

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 1 | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 2 | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:test` (full suite, 328 tests) | BUILD SUCCESSFUL |
| `grep attributePath` on AiExposureRule.java | only in javadoc explaining absence (intentional) |
| `grep "^\s*ALLOW\s*[(,]"` on AiExposureRuleMode.java | 0 matches (no enum constant) |
| `grep "@InstanceName"` / `@Version` on AiExposureRule.java | 1 each (UUID + Version + InstanceName present) |
| `grep SOURCE_ENTITY` on ChunkMetadata.java | constant present with `"source_entity"` |
| `grep sourceEntityName` on AiKnowledgeDocument.java | 3 occurrences (field, getter, setter) |
| 060 changelog | contains `AI_EXPOSURE_RULE` and `UNQ_AI_EXPOSURE_RULE_ENTITY_NAME` |
| 061 changelog | contains `SOURCE_ENTITY_NAME` and `AI_AGENT_KNOWLEDGE_DOCUMENT` |

### First-run flake note

The very first `./gradlew test` after Task 2 reported 76 transient failures with `IllegalStateException` at `DefaultCacheAwareContextLoaderDelegate` and `IllegalArgumentException at SessionImpl.java:63`. A clean rerun completed all 328 tests successfully. Single-test reruns (`com.vn.agent.tools.PromptInjectionHarnessTest`) also passed. The flake appears unrelated to this plan's changes — likely Gradle test parallelism and embedded HSQLDB port/resource contention given that the suite spins up 14+ Spring contexts. Treated as transient; final clean run is authoritative.

## Decisions Made

- **Entity-level only.** `AiExposureRule` has no `attributePath` field. CONTEXT.md decision 2026-04-27 supersedes REQUIREMENTS EXP-01. Documented in plan, in entity Javadoc, and in CONTEXT.md.
- **EXCLUDE-only enum.** `AiExposureRuleMode` ships with one constant and a comment explaining why ALLOW is absent. The `EnumClass<String>` interface lets the enum be persisted as a stable String code.
- **Unique constraint on `ENTITY_NAME`.** Per CONTEXT D-?? planner-discretion choice, uniqueness is enforced at the DB layer (one-rule-per-entity contract). The list-view dropdown will also prevent duplicates at the UI layer in Plan 10-06.
- **JPQL entity name `aiExposure_AiExposureRule`.** Matches the `com.vn.agent.exposure` package; differs from existing `ai_*` prefixed entities (which live in `com.vn.agent.entity`). Boot-time JPQL test deferred to Plan 10-02 (repository).
- **Audit field types `OffsetDateTime`.** Matches `AiKnowledgeDocument` exactly; persisted as Liquibase `datetime` (HSQLDB and PG both accept).
- **`sourceEntityName` nullable.** Legacy KB documents without an entity link must remain ingestable. The v1.1 contract (CONTEXT D-06) is that chunks without a `source_entity` metadata key are not entity-denylistable — accepted, deferred to v1.2 for the document-id allowlist case.

## Deviations from Plan

None — plan executed exactly as written. Plan-driven CLAUDE.md compliance was straightforward (no Lombok, no `EntityManager`, no constructor-instantiation; this plan does not write business logic so most CLAUDE.md rules trivially apply).

## Threat Model Compliance

Threat register from PLAN.md:
- **T-10-01 (mitigate, EoP at entity layer):** No data access introduced in this plan. Repository (Plan 10-02) is the consumer that must use `UnconstrainedDataManager`. Verified that the new entity is in scope only — no DataManager calls.
- **T-10-05 (accept, EoP at table):** Table exists but no `@EntityPolicy` is wired yet (SEC-05 / Plan 10-03). No user-facing surface exists in this plan. Risk window is plan-internal only; no deviation.

No new threat surface introduced beyond what the threat model already accepted.

## Open Items / Follow-ups

- Plan 10-02 will add `LlmExposureRuleRepository` (using `UnconstrainedDataManager` per MEMORY rule) plus boot-time JPQL verification of the `aiExposure_AiExposureRule` entity name.
- Plan 10-03 will extend `AiAgentAdminRole` with `@EntityPolicy(entityClass = AiExposureRule.class, actions = ALL)`. Until then, only `UnconstrainedDataManager` callers can write the table.
- Plan 10-05 is the first consumer of `ChunkMetadata.SOURCE_ENTITY` in `RetrievalFilterBuilder` and `AsyncIngestionWorker`.

## Self-Check: PASSED

- Files exist:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleMode.java` — FOUND
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java` — FOUND
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java` — modified (SOURCE_ENTITY present)
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java` — modified (sourceEntityName present)
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml` — FOUND
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/061-ai-knowledge-document-source-entity.xml` — FOUND
- Commits exist:
  - `e4fed35` (Task 1) — verified via `git log`
  - `676c576` (Task 2) — verified via `git log`
- Compile + test suite green on the final run.
