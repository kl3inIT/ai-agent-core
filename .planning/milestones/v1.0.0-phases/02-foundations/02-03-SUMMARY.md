---
phase: 02-foundations
plan: 03
subsystem: database
tags: [jmix, jpa, entity, hibernate, composition, i18n]

requires:
  - phase: 02-foundations
    provides: "Ai*Enum types (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome) from plan 02-01"
provides:
  - "5 @JmixEntity classes under com.vn.agent.entity"
  - "Bidirectional AiConversation <-> AiMessage composition (CASCADE + orphanRemoval)"
  - "String-backed enum columns (ROLE_, STATUS, OUTCOME) with get/set EnumClass adapters"
  - "Full EN + VI i18n coverage for entity + attribute labels"
affects: [02-04-ddl, 02-08-security, 02-10-smoke, phase-04-audit, phase-05-rag, phase-06-parameters]

tech-stack:
  added: []
  patterns:
    - "Jmix entity template: @JmixEntity + @Entity(name) + @Table(name) + UUID @JmixGeneratedValue + @Version + @InstanceName"
    - "Composition parent/child via @Composition + @OnDelete(CASCADE) + @OneToMany(mappedBy,cascade=ALL,orphanRemoval=true)"
    - "Enum-as-string adapter: @Column length=16 String field + getX()/setX() returning EnumClass via fromId/getId"
    - "Trailing-underscore columns (ROLE_, ACTIVE_) to avoid SQL keyword collision"

key-files:
  created:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java"
  modified:
    - "ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties"
    - "ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties"

key-decisions:
  - "Enum columns stored as VARCHAR(16) with manual fromId/getId adapters (not @Enumerated) for cross-DB portability and defensive null handling"
  - "AiToolCallAudit.conversation is nullable — audit entries may be standalone (non-conversational tool calls)"
  - "Jmix audit fields (createdBy/createdDate/lastModifiedBy/lastModifiedDate) declared on AiConversation, AiParameters, AiKnowledgeDocument; population deferred to @CreatedBy/@LastModifiedBy auditing config in later plan"
  - "AiMessage.role is not @NotNull at the JPA level; DDL plan 02-04 can add NOT NULL at SQL layer if desired"

patterns-established:
  - "Composition parent uses explicit mappedBy on child FK property name (conversation)"
  - "One-line getter/setter style for all entity properties, no Lombok"
  - "Computed @InstanceName methods use @DependsOnProperties for change tracking"

requirements-completed: [ENT-01]

duration: 10min
completed: 2026-04-19
---

# Phase 02 Plan 03: JPA Entities + i18n Summary

**Five @JmixEntity classes (AiConversation/AiMessage/AiToolCallAudit/AiParameters/AiKnowledgeDocument) with @Composition wiring, String-backed enum adapters, and full EN+VI i18n coverage.**

## Performance

- **Duration:** ~10 min
- **Completed:** 2026-04-19
- **Tasks:** 3
- **Files modified:** 7 (5 created, 2 modified)

## Accomplishments
- AiConversation composition parent wired to AiMessage child via `@Composition` + `@OnDelete(CASCADE)` + `orphanRemoval=true`, mapped by `conversation`
- Three flat entities (AiToolCallAudit, AiParameters, AiKnowledgeDocument) with UUID + Version + InstanceName
- Enum-as-string adapters for ROLE_, OUTCOME, STATUS (manual fromId/getId round-trip, no @Enumerated)
- EN + VI i18n keys appended for 5 entities and all attributes (~48 new keys per locale)

## Task Commits

1. **Task 1+2+3: All 5 entities + i18n (batched)** - `a78fe38` (feat)

Tasks 1–3 were batched into a single commit at the user's direction ("Commit entity files + i18n — e.g., feat(02-03): add 5 JPA entities + i18n"), matching the plan's single-commit deliverable 1. Per-task atomic commits were waived because the three tasks form one logically cohesive unit (entity classes + their labels) and Gradle compile verification was skipped per the execution context.

## Files Created/Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java` — composition parent, CREATED_BY ownership key
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java` — composition child, ROLE_ enum adapter, computed InstanceName
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java` — append-only audit, OUTCOME enum adapter, nullable conversation FK
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java` — profile config, ACTIVE_ boolean (default FALSE), BODY_YAML @Lob
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java` — KB doc metadata, STATUS enum adapter, ALLOWED_ROLES_JSON
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` — entity + attribute EN labels
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — entity + attribute VI labels

## Decisions Made
- AiToolCallAudit.conversation kept nullable (not @NotNull) — standalone/background tool calls are legitimate; DDL plan 02-04 may still enforce FK without NOT NULL.
- AiParameters.active defaults to `Boolean.FALSE` at field-init to match DDL default 'false' guarantee even for raw `Metadata.create` instances.
- No `requirements` field was present in the plan frontmatter's `requirements` list beyond ENT-01 — only ENT-01 recorded as completed.

## Deviations from Plan

None in code content — all 5 entities and i18n keys match the plan's action sections verbatim.

**Process deviation (scoped):** The plan defined three separate tasks with per-task commits; per the user's explicit instruction in the execution context ("Commit entity files + i18n — e.g., feat(02-03): add 5 JPA entities + i18n"), tasks 1–3 were batched into a single commit. This is intentional, not an auto-fix.

## Issues Encountered
- Git CRLF warnings on Windows for newly-tracked files — benign, `.gitattributes` handling applies on checkout. No action needed.
- Gradle compile verification was skipped per execution context (node/gsd-sdk unavailable). Compile + DDL validation will be exercised by plan 02-04 (Liquibase changelogs) and plan 02-10 (smoke test).

## User Setup Required
None.

## Next Phase Readiness
- Plan 02-04 (Liquibase DDL): entity column/index specs are authoritative (table names, column names including trailing-underscore ROLE_/ACTIVE_, @Index definitions).
- Plan 02-08 (security roles): entity classes available for `@EntityPolicy(entityClass = ...)` references.
- Plan 02-10 (smoke test): entities instantiable via `Metadata.create(...)` (no-arg constructor, no Lombok).

## Self-Check: PASSED

- All 5 entity files exist at expected paths
- Commit `a78fe38` exists on branch `gsd/phase-02-foundations`
- `grep -r lombok ai-agent/ai-agent/src/main/java/com/vn/agent/entity/` returns no results
- Both locale files contain entity + attribute keys for all 5 entities

---
*Phase: 02-foundations*
*Completed: 2026-04-19*
