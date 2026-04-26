---
plan: 08-08
type: investigation
created: 2026-04-26
status: blocking-escalation
supersedes_scope: 08-08-PLAN.md
trigger: gsd-executor returned architectural-escalation deviation after attempting Plan 08-08 Task 1
---

# Investigation Findings: Plan 08-08 Architectural Escalation

## What was attempted

Per Plan 08-08 Task 1, the gsd-executor added `implementation 'io.jmix.security:jmix-security-data-starter'` to `ai-agent/ai-agent/ai-agent.gradle`, ran the 3 RED tests in isolation, and observed:

| Test | Pre-08-08 | Post-dep-change |
|---|---|---|
| `FilteredSchemaAndExecutionDenialTest.carol_filteredSchema_excludesDenied_andDeniedAttributes` | RED | **GREEN** ✓ |
| `FilteredSchemaAndExecutionDenialTest.carol_findRecordsDeniedEntity_returnsAccessDeniedJson` | RED | **GREEN** ✓ |
| `CrossUserConversationAccessTest.userB_listingConversations_doesNotIncludeUserA_agentStoreScoped` | RED | **NEW FAIL** ✗ — alice setup `chatService.ask` throws AccessDenied at `AuditWriter.writeChatStart:95` |
| `CrossUserConversationAccessTest.userB_replayingUserAConversation_throwsSameTypeAndCodeAsRandomUuid` | GREEN | **REGRESSION** ✗ |
| `AuditDurabilityTest.toolAuditRowSurvivesOuterRollback` | GREEN | **REGRESSION** ✗ |
| `AuditDurabilityTest.writeChatFinish_doesNotOrphanChildren_writtenInSeparateRequiresNew` | GREEN | **REGRESSION** ✗ |
| `AuditDurabilityTest.errorPathToolAuditAlsoSurvivesOuterRollback_viaDecorator` | GREEN | **REGRESSION** ✗ |
| `FoundationsBootSmokeTest.all_five_entities_round_trip` | GREEN | **REGRESSION** ✗ — `AccessDenied: ai_AiConversation, action: create` under `runWithSystem` |

Net of bare dep change: 2 RED→GREEN, 1 RED stays RED (cause shifted), **5 previously-GREEN tests regress**.

The executor reverted the gradle change cleanly (working tree restored to pre-edit state, no commits made) and surfaced this as an architectural escalation per Plan 08-08 `<notes>` "Out-of-band escalation paths" guidance.

## Root cause

`AuditWriter` writes `ai_AiAuditEvent` rows via the **constrained** `DataManager` under the calling user's authentication. Pre-fix, no `CrudEntityConstraint` was registered (because `jmix-security-data` was not on the classpath), so the entity-CRUD policy check was a silent no-op — any authenticated user could write audit rows. Post-fix, `CrudEntityConstraint` enforces `secureOperations.isEntityReadPermitted(metaClass, policyStore)` and end-user roles (`AiAgentUserRole`) intentionally do not grant CREATE on `ai_AiAuditEvent` (audit log is tamper-evident system infrastructure).

This is the exact concern documented at `FoundationsBootSmokeTest.java:198-209` (Phase 2 forensics) and listed in Plan 08-08 threat model entry T-08-08-03.

`runWithSystem` is **not** a substitute fix here. Per `FoundationsBootSmokeTest.java:205-208`: "AccessDenied under runWithSystem" — under jmix-security-data, the system user is not implicitly fully-privileged; its access is still policy-gated by whatever roles are configured for it.

## Architectural fix (validated against Jmix 2.8 docs)

### Production code

**Switch `AuditWriter.java` from `DataManager` to `UnconstrainedDataManager`.**

Per Jmix Context7 docs (`/jmix-framework/jmix-context7` → `data-access/data-manager.html`):

> The `UnconstrainedDataManager` interface provides the same methods as `DataManager` but bypasses all security policy checks. It can be used to override security constraints when necessary in your application code.

Use case match: audit log writes are system-internal persistence that must succeed regardless of caller authentication. `UnconstrainedDataManager` declares this intent at the type level, is the canonical Jmix pattern (also recommended for Quartz jobs and unauthenticated contexts per the same doc), and avoids the role-grant brittleness of `runWithSystem`.

Surface area: ~4 lines in `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`:
- Field: `private final DataManager dataManager;` → `private final UnconstrainedDataManager dataManager;`
- Constructor: parameter type `DataManager` → `UnconstrainedDataManager`
- Import: add `io.jmix.core.UnconstrainedDataManager`; keep `DataManager` import only if still used (it is not — all usages are via the field)

All call sites inside `AuditWriter` (`save`, `load`, `loadValue`) work unchanged — `UnconstrainedDataManager` has the same fluent API.

### Test code

`FoundationsBootSmokeTest.all_five_entities_round_trip` (line 128–195) does a direct `dataManager.save(audit)` (line 165) **inside** `runWithSystem`, not via `AuditWriter`. Two options:

- **Recommended:** Change line 165 from `dataManager.save(audit)` to `dataManager.unconstrained().save(audit)` (one line). Same pattern, same intent ("this is a persistence sanity check, not a policy-enforcement test"). Co-locates the unconstrained-write idiom near the runWithSystem boundary.
- **Defer:** Document `all_five_entities_round_trip` as a known test-fixture limitation under `jmix-security-data` enforcement; mark `@Disabled` with a TODO. Trade-off: lose the persistence smoke for `AiAuditEvent`.

The original Plan 08-08 critical_constraints pinned all test files. The replanned 08-08 must explicitly unlock `FoundationsBootSmokeTest.java` for this single-line edit (or accept the deferral).

## Replan scope (recommended)

A revised Plan 08-08 should include **all three** changes in a single atomic gap closure:

1. `ai-agent/ai-agent/ai-agent.gradle` — add `implementation 'io.jmix.security:jmix-security-data-starter'`
2. `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` — `DataManager` → `UnconstrainedDataManager` (constructor + field + import)
3. `ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` line 165 — `dataManager.save(audit)` → `dataManager.unconstrained().save(audit)`

Closure target unchanged: VERIFICATION.md Gap 1, R-XP-2-FIX-1/2/3.
Regression bar updated: 12/12 in `com.vn.agent.security.*`, 17/17 in 08-02+08-03 cross-cutting suites, 5/5 in `FoundationsBootSmokeTest`. Total 29/29.

Threat model T-08-08-03 transitions from `mitigate` (planned) to **`mitigated and tested under realistic user authentication`** — strictly stronger.

## Files NOT to modify in the replan

Per the original Plan 08-08 user constraints (still valid):
- The 3 RED test files (`FilteredSchemaAndExecutionDenialTest.java`, `CrossUserConversationAccessTest.java`) — pinned contracts
- `NoCustomerReadRoleConfiguration.java` — carol's role pinned
- `TestUsersConfiguration.java` — alice/bob baseline pinned
- `AiAgentUserRowLevelRole.java`, `CurrentUserSchemaAccess.java`, `BuiltInDataTools.java` — SUTs pinned (the dep + AuditWriter changes are the only fix)

Test-file unlock for the replan is **only** for `FoundationsBootSmokeTest.java` line 165 (one-line `.unconstrained()` insertion).

## Working-tree state at handoff

- `ai-agent/ai-agent/ai-agent.gradle`: clean (executor reverted)
- No commits added by the failed 08-08 attempt
- `08-08-PLAN.md`: still on disk, scope superseded by this investigation
- `.planning/STATE.md`: shows "Executing Phase 08" (orchestrator-owned)

## Next command

```
/clear
/gsd-plan-phase 8 --gaps
```

The replanner should read this `08-08-INVESTIGATION.md` and `08-VERIFICATION.md` Gap 1 together to produce the revised plan.
