---
phase: 02-foundations
plan: 01
subsystem: entity/enums+i18n
tags: [enum, i18n, foundations, EnumClass]
requirements: [ENT-01]
dependency-graph:
  requires: []
  provides:
    - "com.vn.agent.entity.AiMessageRole"
    - "com.vn.agent.entity.AiKnowledgeDocumentStatus"
    - "com.vn.agent.entity.AiToolCallOutcome"
    - "i18n keys for all three enums (EN + VI)"
  affects:
    - "02-03 (entities reference these enums as string-mapped columns)"
    - "02-05 (chat-memory CHECK constraint alignment with AiMessageRole)"
tech-stack:
  added: []
  patterns:
    - "Jmix EnumClass<String> with stable string ids"
    - "@Nullable static fromId(String) lookup pattern"
    - "i18n key format: com.vn.agent.entity/<Enum>.<VALUE>"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessageRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
decisions:
  - "AiMessageRole ids frozen to USER/ASSISTANT/SYSTEM/TOOL to match Spring AI 1.1.4 chat-memory CHECK constraint"
  - "EnumClass<String> chosen (vs <Integer>) for human-readable DB values and CHECK-constraint interoperability"
metrics:
  duration: "~1m"
  tasks: 2
  files_changed: 5
  completed: 2026-04-19
---

# Phase 2 Plan 01: Enums + i18n Summary

Three `EnumClass<String>` enums (`AiMessageRole`, `AiKnowledgeDocumentStatus`, `AiToolCallOutcome`) plus matching EN/VI i18n keys, unblocking entity creation in plan 02-03 and aligning with Spring AI 1.1.4 chat-memory schema.

## What Was Built

### Task 1: Three enum classes under `com.vn.agent.entity`

Each enum mirrors the `jmix-app` `OrderStatus` analog verbatim: `implements EnumClass<String>`, `private final String id`, single-arg constructor, `@Override getId()`, `@Nullable static fromId(String id)` lookup loop. No Lombok, no additional methods.

- `AiMessageRole` — `USER`, `ASSISTANT`, `SYSTEM`, `TOOL`. String ids deliberately match the Spring AI 1.1.4 `SPRING_AI_CHAT_MEMORY.type` CHECK constraint so the chat-memory changelog in plan 05 can round-trip our enum through the JDBC store without translation.
- `AiKnowledgeDocumentStatus` — `PENDING`, `PROCESSING`, `READY`, `FAILED` for RAG-03 document lifecycle.
- `AiToolCallOutcome` — `SUCCESS`, `BLOCKED`, `ERROR` for AUD-03 audit records.

`./gradlew :ai-agent:ai-agent:compileJava` exits 0.

### Task 2: EN + VI i18n keys

- Appended 11 enum keys to existing `messages.properties` (preserving the original `localeDisplayName.en` and `com.vn.agent/menu.addon` lines).
- Created `messages_vi.properties` with `localeDisplayName.vi=Tiếng Việt`, the same menu key, and the identical 11-key enum set translated to Vietnamese (UTF-8).

Both files parse as valid Java `Properties`; key sets are identical across locales, satisfying the CLAUDE.md "single-locale messages forbidden" rule.

## Key Decisions

- **Id strings frozen to upper-case.** `AiMessageRole` ids must match Spring AI chat-memory CHECK constraint literally; mixing case would require an application-side translator.
- **`EnumClass<String>`, not `<Integer>`.** Human-readable DB values, easier JSON audit logs, and direct CHECK-constraint interoperability outweigh the 3-byte-per-row storage delta.
- **i18n namespace `com.vn.agent.entity/<Enum>.<VALUE>`.** Uses the full package path matching the Jmix Studio convention and the `jmix-app` precedent.

## Deviations from Plan

None — plan executed exactly as written. Every acceptance criterion in the plan matched on the first verification pass; no Rule 1–4 deviations triggered.

## Threat Model Compliance

- **T-02-01 (Tampering — enum ids drift from Spring AI CHECK constraint):** mitigated. Ids hard-coded and grep-verified (`USER("USER")`, etc.); plan 02-05 will port the chat-memory DDL with identical string literals.
- **T-02-02 (VI translation gaps):** mitigated beyond plan disposition — all 11 keys present in both locales.

## Commits

| Task | Description | Hash |
|------|-------------|------|
| 1 | Three EnumClass<String> enums | `9020d78` |
| 2 | EN+VI i18n keys for all three enums | `b4f62c2` |

## Validation

- `grep` verification of enum structure, value ids, and i18n keys: PASSED.
- `./gradlew :ai-agent:ai-agent:compileJava`: BUILD SUCCESSFUL.
- No Lombok anywhere in `com.vn.agent.entity`.
- No pre-existing keys removed from `messages.properties`.

## Follow-ups Unlocked

- Plan 02-03 can now reference these enums from the `AiMessage.role`, `AiKnowledgeDocument.status`, and `AiToolCallAudit.outcome` columns.
- Plan 02-05 (chat-memory Liquibase changeset) can assert the CHECK constraint `type IN ('USER','ASSISTANT','SYSTEM','TOOL')` with confidence that the Java enum matches.

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessageRole.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
- FOUND commit: 9020d78
- FOUND commit: b4f62c2
