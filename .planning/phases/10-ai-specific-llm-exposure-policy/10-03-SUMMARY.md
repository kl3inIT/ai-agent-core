---
phase: 10
plan: 03
subsystem: ai-exposure
tags: [security, role, policy, admin]
requires:
  - phase-10-01 (AiExposureRule entity exists in com.vn.agent.exposure)
provides:
  - "AiAgentAdminRole @EntityPolicy(entityClass=AiExposureRule.class, actions=ALL)"
  - "AiAgentAdminRole @MenuPolicy: aiAgent.exposureRules.list, aiAgent.vectorStoreDebug"
  - "AiAgentAdminRole @ViewPolicy: AiAgent_AiExposureRule.list, AiAgent_AiExposureRule.detail, AiAgent_VectorStoreDebug"
affects:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
tech-stack:
  added: []
  patterns:
    - "Jmix @ResourceRole with explicit entityClass per @EntityPolicy (project convention)"
    - "Co-located @MenuPolicy + @ViewPolicy on adminViews() method, gating menu and route in one place"
key-files:
  created: []
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
decisions:
  - "Pure additive extension — no existing policy reordered or removed (zero-regression contract)"
  - "AiExposureRule policy granted ALL actions (CRUD) since admin is the only role permitted to manage rules per threat T-10-05"
  - "Menu IDs and view IDs added inline as string literals matching the established pattern; no constants extracted (existing IDs are also literals)"
metrics:
  duration_min: 1
  tasks_completed: 1
  files_changed: 1
  completed_date: "2026-04-27"
---

# Phase 10 Plan 03: AiAgentAdminRole Extension for AiExposureRule + New Views Summary

Wave 2 prerequisite that lets Wave 3 UI plans (10-06 list/detail, 10-09 vector-store-debug) assume their menu, route, and entity CRUD are gated to `AiAgentAdminRole`. One file touched, one task, one commit.

## Objective Recap

Extend `AiAgentAdminRole` with the security policies required by SEC-05 / EXP-10 for the new entity and views introduced in this phase:

- `@EntityPolicy(entityClass = AiExposureRule.class, actions = EntityPolicyAction.ALL)` — admin-only CRUD on the rule table.
- `@MenuPolicy` adds `aiAgent.exposureRules.list` and `aiAgent.vectorStoreDebug`.
- `@ViewPolicy` adds `AiAgent_AiExposureRule.list`, `AiAgent_AiExposureRule.detail`, `AiAgent_VectorStoreDebug`.

Pure additive change. All previously existing policy entries preserved verbatim.

## What Was Built

### Task 1 — Extend AiAgentAdminRole

Single Java file edit at `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`:

1. Added import `com.vn.agent.exposure.AiExposureRule`.
2. Added one more `@EntityPolicy` line on `adminAccess()` (now 6 entity policies total: AiConversation, AiMessage, AiAuditEvent, AiParameters, AiKnowledgeDocument, AiExposureRule).
3. Extended `@MenuPolicy.menuIds` from 6 to 8 IDs (added `aiAgent.exposureRules.list`, `aiAgent.vectorStoreDebug`).
4. Extended `@ViewPolicy.viewIds` from 9 to 12 IDs (added `AiAgent_AiExposureRule.list`, `AiAgent_AiExposureRule.detail`, `AiAgent_VectorStoreDebug`).

**Commit:** `3740483` — `feat(10-03): extend AiAgentAdminRole with AiExposureRule and new view policies`

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:compileJava` | BUILD SUCCESSFUL |
| `grep -c "AiExposureRule" AiAgentAdminRole.java` | 3 (import + class ref + @EntityPolicy) — at least 2 required |
| `grep "aiAgent.exposureRules.list"` | 1 occurrence at line 41 |
| `grep "aiAgent.vectorStoreDebug"` | 1 occurrence at line 42 |
| `grep "AiAgent_AiExposureRule.list"` | 1 occurrence at line 50 |
| `grep "AiAgent_AiExposureRule.detail"` | 1 occurrence at line 50 |
| `grep "AiAgent_VectorStoreDebug"` | 1 occurrence at line 51 |
| `grep "AiAgent_KnowledgeBase.list"` (regression spot-check) | 1 occurrence at line 48 (preserved) |
| Pre-existing menu IDs (chat, baselineContext, conversations, parameters.list, knowledge.list, audit.list) | All present (lines 35-40) |
| Pre-existing view IDs (Chat, BaselineContext, Conversation.list/detail, Parameters.list/detail, AiAuditEvent.list/detailDialog) | All present (lines 44-49) |

Resulting structure: `adminAccess()` carries 6 `@EntityPolicy` annotations; `adminViews()` carries 8-element `@MenuPolicy.menuIds` and 12-element `@ViewPolicy.viewIds`.

## Decisions Made

- **Pure additive change.** Did not reorder existing entries; appended new IDs at the end of each array. Keeps blame/diff clean and avoids accidental regression.
- **Granted `EntityPolicyAction.ALL`** on `AiExposureRule` (matches every other entity policy on this role). Threat T-10-05 explicitly mitigates by restricting CRUD to admin only — full-CRUD on the role is correct since no narrower role grants any access.
- **String-literal IDs.** The existing convention on this role uses literal strings for menu and view IDs; introduced no constants. Future refactor to per-view ID constants would touch all entries uniformly and is out of scope here.

## Deviations from Plan

None — plan executed exactly as written.

## Threat Model Compliance

Threat register from PLAN.md:

- **T-10-05 (Elevation of Privilege, mitigate, AiExposureRule entity):** `@EntityPolicy(entityClass = AiExposureRule.class, actions = EntityPolicyAction.ALL)` is wired on `AiAgentAdminRole.adminAccess()`. No other role in the codebase grants any policy on `AiExposureRule`, so non-admin users cannot CRUD rules through Jmix's standard `AccessManager` enforcement.
- **T-10-05 (Elevation of Privilege, mitigate, VectorStoreDebugView):** `@ViewPolicy("AiAgent_VectorStoreDebug")` and `@MenuPolicy("aiAgent.vectorStoreDebug")` are gated to `AiAgentAdminRole`. Non-admin attempts to navigate to the debug view will be rejected by Jmix view-access enforcement at route resolution time.

No new threat surface introduced beyond what the threat model already accepted. The view/menu IDs do not yet have implementations (Wave 3 plans 10-06 and 10-09); the policies simply prevent any non-admin from reaching them once they ship.

## Open Items / Follow-ups

- Plan 10-06 will create `AiExposureRuleListView` (`AiAgent_AiExposureRule.list`) and `AiExposureRuleDetailView` (`AiAgent_AiExposureRule.detail`) plus the `aiAgent.exposureRules.list` menu entry. The policies wired here will gate them.
- Plan 10-09 (or Wave 3 vector-store-debug plan) will create `VectorStoreDebugView` (`AiAgent_VectorStoreDebug`) plus `aiAgent.vectorStoreDebug` menu entry. Same gating applies.
- SEC-05 partially completes here. Phase 12 will add `AiUiSettings` policies to fully close SEC-05.
- No `AiAgentUserRowLevelRole` or other narrower role needs an update — `AiExposureRule` is admin-only.

## Self-Check: PASSED

Files modified:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` — FOUND, contains all 6 expected new strings, all pre-existing policies preserved.

Commit exists (verified via `git rev-parse --short HEAD`):
- `3740483` — Task 1 (AiAgentAdminRole extension)

`./gradlew :ai-agent:ai-agent:compileJava` — BUILD SUCCESSFUL.
