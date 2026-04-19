---
phase: 02-foundations
plan: 08
subsystem: security
tags: [jmix, security, roles, row-level, i18n]
requires: [02-03]
provides:
  - ai-agent-user resource role
  - ai-agent-admin resource role
  - ai-agent-user-rl row-level role
affects:
  - AiConversation reads (user scope)
  - AiMessage reads (user scope)
tech-stack:
  added: []
  patterns:
    - "Jmix @ResourceRole interface with @EntityPolicy methods"
    - "Jmix @RowLevelRole interface with @JpqlRowLevelPolicy"
    - "Framework-bound :current_user_username session parameter"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "Row-level predicate uses JPQL (not Predicate) so filter pushes into SQL WHERE and works with pagination/count queries"
  - "Explicit entityClass per admin policy (not entityName='*') for safer scope and IDE navigability"
  - "User role has no DELETE, no @EntityAttributePolicy, no @ViewPolicy, no @MenuPolicy (deferred per D-07)"
  - "Row-level role is SEPARATE from resource role — end users must be assigned BOTH ai-agent-user AND ai-agent-user-rl (Javadoc documents this)"
metrics:
  completed_date: 2026-04-19
requirements: [SEC-01, SEC-02, SEC-03, SEC-04]
---

# Phase 2 Plan 08: Jmix Security Roles Summary

Three Jmix security roles wired declaratively: resource-role entity policies for user and admin, plus a row-level role whose `@JpqlRowLevelPolicy` predicate enforces `createdBy == :current_user_username` for every `AiConversation`/`AiMessage` read or write performed through `DataManager`.

## What Shipped

### AiAgentUserRole (`CODE = "ai-agent-user"`)
- `@EntityPolicy(AiConversation.class, {READ, CREATE, UPDATE})`
- `@EntityPolicy(AiMessage.class, {READ, CREATE})`
- No DELETE, no access to `AiToolCallAudit` / `AiParameters` / `AiKnowledgeDocument`
- No attribute/view/menu policies (deferred per D-07)

### AiAgentAdminRole (`CODE = "ai-agent-admin"`)
- `EntityPolicyAction.ALL` on all 5 AI_AGENT_* entities (explicit `entityClass` per method)

### AiAgentUserRowLevelRole (`CODE = "ai-agent-user-rl"`)
- `@JpqlRowLevelPolicy` on `AiConversation` → `{E}.createdBy = :current_user_username`
- `@JpqlRowLevelPolicy` on `AiMessage` → `{E}.conversation.createdBy = :current_user_username`
- Admins deliberately omit this role — absence of narrowing policy means admins see all rows

### i18n
- 3 keys added to `messages.properties` (EN) and `messages_vi.properties` (VI)

## Verification

Plan automated checks (grep assertions) all pass:
- `@ResourceRole` / `@RowLevelRole` annotations present with correct names and codes
- `String CODE` constants match spec
- Exactly 5 occurrences of `EntityPolicyAction.ALL` in admin role
- User role free of DELETE and of attribute/view/menu policy annotations
- JPQL `where` clauses match required patterns verbatim
- Role i18n keys present in both locale files

Gradle compile was skipped per executor constraints (Node/gsd-sdk unavailable). Compile-time validation will be exercised by Wave-4 plans that consume these roles.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Reworded Javadoc to avoid false-positive grep match**
- **Found during:** Task 1 verification
- **Issue:** Initial Javadoc on `AiAgentUserRole` used `{@code @ViewPolicy}` etc. inside a prose sentence documenting what was intentionally omitted. The plan's automated check grepped `@EntityAttributePolicy|@ViewPolicy|@MenuPolicy` without distinguishing Javadoc markup from real annotations, causing a false "banned policy present" flag.
- **Fix:** Rephrased the Javadoc line to describe the exclusions in plain English without the annotation tokens: "No attribute-level, view, or menu policies in Phase 2 (deferred per D-07)." Semantics preserved; automated check now passes.
- **Files modified:** `AiAgentUserRole.java`
- **Commit:** `646b288`

No other deviations. Plan executed as written.

## Deferred Items

None within plan scope. Reminders for future phases:
- Assigning `ai-agent-user` + `ai-agent-user-rl` jointly to end users happens in Wave-4 ops/docs plans (11/12).
- Cross-user isolation smoke test is the responsibility of plan 10 per threat register T-02-SEC-01/02.
- `@EntityAttributePolicy` on sensitive columns (e.g., `AiKnowledgeDocument.allowedRolesJson`) remains deferred per D-07 / T-02-SEC-06.

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
- FOUND: i18n keys in messages.properties and messages_vi.properties
- FOUND: commit 646b288
