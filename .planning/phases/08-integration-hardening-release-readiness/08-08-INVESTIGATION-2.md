---
plan: 08-08
type: investigation
created: 2026-04-26
status: blocking-replan
supersedes_scope: 08-08-PLAN.md@22ffd05 (the "expanded" plan after Investigation #1)
trigger: gsd-executor reverted Plan 08-08 attempt #2 (gradle dep + AuditWriter unconstrained + FoundationsBootSmokeTest line 165) before commit; new failure modes surfaced beyond what Investigation #1 anticipated
predecessor: 08-08-INVESTIGATION.md
---

# Investigation Findings #2: Plan 08-08 Scope Still Too Narrow

## What was attempted (attempt #2)

After Investigation #1, Plan 08-08 was rewritten with a 3-edit scope: (a) add `jmix-security-data-starter` to gradle, (b) switch `AuditWriter` from `DataManager` → `UnconstrainedDataManager`, (c) change `FoundationsBootSmokeTest:165` from `dataManager.save(audit)` → `dataManager.unconstrained().save(audit)`. The executor applied all three, ran the targeted regression suite, hit the failure modes below, and reverted ALL three files cleanly per the plan's own halt-before-commit guidance. **No commits were made.** Working tree clean of plan changes; HEAD remains at `22ffd05`.

## Test evidence (after attempt #2's 3 edits, on top of `clean` build)

| Suite / Class | Tests | Pass | Fail | Note |
|---|---:|---:|---:|---|
| `com.vn.agent.security.AdminViewAccessTest` | 5 | 5 | 0 | preserved |
| `com.vn.agent.security.RagRoleFilterNegativeTest` | 2 | 2 | 0 | preserved |
| `com.vn.agent.security.FilteredSchemaAndExecutionDenialTest` | 3 | **3** | 0 | **R-XP-2-FIX-1 + FIX-2 flipped GREEN** ✓ |
| `com.vn.agent.security.CrossUserConversationAccessTest` | 2 | 0 | **2** | RED-3 still RED (new failure mode); previously-GREEN replay test regressed |
| **`com.vn.agent.security.*` total** | **12** | **10** | **2** | closure-target gate (12/12) NOT met |
| `com.vn.agent.audit.AuditDurabilityTest` | 3 | **0** | **3** | regression bar BROKEN — verification reads under `runWithSystem` return 0 rows |
| `com.vn.agent.FoundationsBootSmokeTest` | 5 | 4 | 1 | `all_five_entities_round_trip` fails at line 146 (AiConversation save), not line 165 |

Net: 2 RED→GREEN (RED-1 + RED-2 closed), 1 RED still RED (cause shifted again), 6 previously-GREEN tests now FAIL. The "expanded" plan widened the GREEN set vs the bare dep but did not close the regression gap.

## Root cause — the plan's premise was correct, the scope was insufficient

### What the executor returned (and why one part is misread)

The executor framed the failure as: *"`UnconstrainedDataManager` does NOT bypass `DataStoreCrudListener` — listener fires regardless of which DataManager flavor invoked the save."* It cited this stack:

```
io.jmix.core.security.AccessDeniedException: resource: ai_AiMessage, type: entity, action: update
  at DataStoreCrudListener.beforeEntitySave(DataStoreCrudListener.java:118)
  at AbstractDataStore.save(AbstractDataStore.java:218)
  at JpaDataStore.save(JpaDataStore.java:244)
  at UnconstrainedDataManagerImpl.lambda$saveContextToStore$7(UnconstrainedDataManagerImpl.java:280)
  at UnconstrainedDataManagerImpl.saveContextToStore(...:280)
  at UnconstrainedDataManagerImpl.save(...:237)
  at UnconstrainedDataManagerImpl.remove(...:163)
  at ProjectingChatMemoryRepository.lambda$saveAll$0(ProjectingChatMemoryRepository.java:70)
```

**This stack does NOT prove the executor's claim.** `ProjectingChatMemoryRepository:37` declares `private final DataManager dataManager;` — it uses the **constrained** DataManager (the plan never modified this file). In Jmix 2.8, `DataManagerImpl` (constrained) delegates to `UnconstrainedDataManagerImpl` for the actual datastore I/O after applying its constraint chain — but it sets `SaveContext.authorizationRequired=true` first. `DataStoreCrudListener.beforeEntitySave` checks that flag and only enforces when `true`. So the stack passing through `UnconstrainedDataManagerImpl` reflects internal class delegation, **not** an `UnconstrainedDataManager.save()` call from user code. `UnconstrainedDataManager` does still bypass the listener when called directly (it sets `authorizationRequired=false`).

The Jmix Context7 doc (`/jmix-framework/jmix-context7` data-manager.html) and project memory `feedback_jmix_unconstrained_for_system_writes` are **both correct** and consistent with this evidence. The plan's `AuditWriter` switch is the right pattern. It just wasn't applied broadly enough.

### What actually went wrong (each failing test, mapped to root cause)

#### 1. `CrossUserConversationAccessTest.userB_listingConversations_doesNotIncludeUserA_agentStoreScoped` (RED-3) — still RED

Stack: alice's setup `chatService.ask(...)` throws `AccessDeniedException: ai_AiMessage, action: update` at `ProjectingChatMemoryRepository.java:70`. The test never reaches its assertion.

`ProjectingChatMemoryRepository` is a `@Primary` `ChatMemoryRepository` decorator that mirrors Spring AI chat memory into the Jmix `ai_AiMessage` table. Its `saveAll` deletes existing rows then re-inserts (mirroring `JdbcChatMemoryRepository.saveAll` semantics — line 63-70 comment). The delete uses constrained `dataManager.remove(AiMessage)`. Pre-08-08 (no `jmix-security-data` on classpath), `CrudEntityConstraint` and `DataStoreCrudListener` were both no-ops for this entity-CRUD check, so any authenticated user could remove `ai_AiMessage`. With `jmix-security-data-starter` registered, both layers fire and end-user `AiAgentUserRole` lacks UPDATE/DELETE on `ai_AiMessage` → save aborted.

`ProjectingChatMemoryRepository` is **system-internal infrastructure** (chat-memory pruning is invisible bookkeeping; users never observe it directly). It matches the same architectural category as `AuditWriter`.

#### 2. `CrossUserConversationAccessTest.userB_replayingUserAConversation_throwsSameTypeAndCodeAsRandomUuid` — regression

Same root cause as #1: alice's setup `chatService.ask` blows up before the test reaches its assertion. Both tests in this class share alice's setup path; whatever fixes #1 fixes this.

#### 3. `AuditDurabilityTest.toolAuditRowSurvivesOuterRollback` (and 2 other tests in the same class) — regression

Stack: assertion `Expected size: 1 but was: 0` at the verification read step. The test pattern is "do operation under user auth → assert audit row was written → also assert it survives outer rollback". The verification read uses constrained `dataManager.load(AiAuditEvent...)` under `runWithSystem`. Under `jmix-security-data`, `runWithSystem` does NOT auto-grant the system user CRUD on `ai_AiAuditEvent` — its access is policy-gated. `ReadEntityQueryConstraint` filters the query to 0 rows (system user has no ALLOW row-level policy on the audit entity) → assertion fails.

This means the AuditWriter unconstrained switch is working correctly (the row IS written — the audit-write path uses unconstrained DM and bypasses both layers). The tests' VERIFICATION READS are what break, because those still use constrained DM.

The fix is the symmetric one to the AuditWriter pattern: verification reads inside `runWithSystem` for system-internal entities should use `dataManager.unconstrained().load(...).list()`.

#### 4. `FoundationsBootSmokeTest.all_five_entities_round_trip` — regression

Stack: `AccessDenied: ai_AiConversation, action: create` at line **146** (NOT line 165 — the line the plan unlocked). The test does:
- Line 146: `dataManager.save(conv, msg)` → AiConversation + AiMessage
- Line 165: `dataManager.save(audit)` → AiAuditEvent  *(plan unlocked this only)*
- Line 176: `dataManager.save(params)` → AiParameters
- Line 188: `dataManager.save(doc)` → AiKnowledgeDocument

All four sites are inside `runWithSystem` and all use **constrained** `dataManager`. Pre-08-08, all worked because no constraint was registered. Post-08-08, line 146 fails first. The plan's "only line 165 needs unlocking because the other 4 entities have role grants" assumption is FALSE under jmix-security-data: the system user is policy-gated, and there is no system role granting CRUD on `ai_AiConversation`/`ai_AiMessage`/`ai_AiParameters`/`ai_AiKnowledgeDocument`. All four save calls need the same `.unconstrained()` treatment.

## Architectural decision matrix

The widened scope falls into two coherent options. Pick one (or hybrid) for the replan.

### Option A — Widen `UnconstrainedDataManager` adoption to all system-internal sites

| Component | Change | Why |
|---|---|---|
| `AuditWriter` | `DataManager` → `UnconstrainedDataManager` (already in attempt #2) | Audit log = system infrastructure |
| `ProjectingChatMemoryRepository` | `DataManager` → `UnconstrainedDataManager` (line 37 field + line 41 constructor param) | Chat-memory pruning = invisible system bookkeeping |
| `AuditDurabilityTest` | Replace `dataManager.load(...)` verification reads with `dataManager.unconstrained().load(...)` (~6 sites: lines 73, 80, 117, 219, etc. — exact line numbers TBD by replanner reading the file) | Test verifies system-internal write happened; reads system-internal entity |
| `FoundationsBootSmokeTest` | Replace `dataManager.save(...)` with `dataManager.unconstrained().save(...)` at lines 146, 165 (already), 176, 188 | All 4 entities are system-internal in this fixture; runWithSystem alone is not enough under jmix-security-data |

Pros:
- Aligns with existing memory `feedback_jmix_unconstrained_for_system_writes` (already the project standard)
- Each call site declares system-scope intent at the call/type level
- Symmetric pattern (write AND read paths both use unconstrained when the data is system-internal)
- Minimal coupling to role configuration

Cons:
- Touches `ProjectingChatMemoryRepository` (production source — not previously in plan scope) and `AuditDurabilityTest` (test file)
- Spreads `.unconstrained()` calls across multiple files; the replanner must explicitly catalogue each one

### Option B — Grant system-user role CRUD on AI entities

Configure a system-internal `ResourceRole` granting CREATE/READ/UPDATE/DELETE on all `ai_*` tables, and bind it to the `SystemAuthenticator.runWithSystem` user. Keep all `dataManager.save/load/remove(...)` calls constrained.

Pros:
- Cleanest "respect-the-framework" path — the system user gets explicit grants instead of bypassing checks
- Single point of configuration (one role, one binding) instead of scattered `.unconstrained()` calls
- Reads and writes both work without code edits beyond AuditWriter (which has the most demanding "any auth, even non-system" contract)

Cons:
- Requires touching the role configuration surface (`TestUsersConfiguration` or a new `SystemInternalAccessRole`) — currently pinned in original plan constraints
- Doesn't help `ProjectingChatMemoryRepository` (which runs under user auth, not system auth) — would still need `.unconstrained()` OR end-user role CRUD grants on `ai_AiMessage`
- `AuditWriter` still must use `UnconstrainedDataManager` because it runs under arbitrary user auth (not just runWithSystem)

### Option C — Hybrid (recommended)

- `AuditWriter` and `ProjectingChatMemoryRepository` switch to `UnconstrainedDataManager` (both run under arbitrary user auth, both write system-internal entities)
- `AuditDurabilityTest` and `FoundationsBootSmokeTest` use `dataManager.unconstrained()` for verification reads/sanity-saves under `runWithSystem`
- Add `jmix-security-data-starter` to gradle (Investigation #1 / attempt #2's classpath fix is unchanged)

This is Option A applied surgically. It keeps role configuration untouched and isolates the unconstrained-DM contract to genuinely system-internal sites. Aligns with `feedback_jmix_unconstrained_for_system_writes` and the canonical Jmix pattern documented in `/jmix-framework/jmix-context7` `data-access/data-manager.html`.

## Recommended replan scope

**Files to modify (5 source + 0 test fixture roles):**

1. `ai-agent/ai-agent/ai-agent.gradle` — add `implementation 'io.jmix.security:jmix-security-data-starter'`
2. `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` — `DataManager` → `UnconstrainedDataManager` (field + constructor + import) [unchanged from attempt #2]
3. `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java` — `DataManager` → `UnconstrainedDataManager` (field at line 37 + constructor param at line 41 + import). Same pattern as AuditWriter; no other code edits (the fluent API is identical).
4. `ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java` — replace `dataManager.save(...)` with `dataManager.unconstrained().save(...)` at lines 146, 165, 176, 188 (4 saves total). Replanner should reread the file to capture exact lines.
5. `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java` — replace verification-read `dataManager.load(...)` calls inside the assertion sections with `dataManager.unconstrained().load(...)`. Replanner must read the file and enumerate exact lines (~6 sites by inspection of stack traces; precise count is the replanner's responsibility).

**Files NOT to modify (still pinned per user constraint):**
- `FilteredSchemaAndExecutionDenialTest.java`, `CrossUserConversationAccessTest.java`, `RagRoleFilterNegativeTest.java` — RED-test contracts
- `NoCustomerReadRoleConfiguration.java`, `TestUsersConfiguration.java` — user role baseline
- `AiAgentUserRowLevelRole.java`, `AiAgentUserRole.java` — end-user role definitions
- `CurrentUserSchemaAccess.java`, `BuiltInDataTools.java` — SUTs

**Test-file unlocks for the replan:**
- `FoundationsBootSmokeTest.java` — 4 lines (146/165/176/188 — replanner verifies)
- `AuditDurabilityTest.java` — ~6 lines (replanner enumerates by reading the file)

**Closure target unchanged:** VERIFICATION.md Gap 1, R-XP-2-FIX-1/2/3.

**Updated regression bar:** 12/12 in `com.vn.agent.security.*` ; AuditDurabilityTest 3/3; FoundationsBootSmokeTest 5/5; PromptInjectionHarnessTest 5/5; ToolQueryCountBaselineTest 7/7; FindRecordsLimitCapTest 2/2. Total **29/29** in named regression classes; broad broom `:ai-agent:ai-agent:test` BUILD SUCCESSFUL.

## Threat model deltas (from attempt #2's plan)

- **T-08-08-03** (regression on AuditWriter under user auth) — unchanged: `mitigated and tested under realistic user authentication` once `AuditWriter` switches to `UnconstrainedDataManager`.
- **T-08-08-06** (audit-write path bypassed by adversarial code path) — unchanged: accepted; UnconstrainedDataManager scope kept narrow.
- **T-08-08-07** (NEW) — Tampering: `ProjectingChatMemoryRepository` chat-memory pruning bypasses `CrudEntityConstraint` after the switch. Disposition: `accept`. Mitigation: pruning operates on the calling user's own conversation row set (filtered by `conversationId` UUID lookup), not arbitrary rows; the unconstrained scope does not enable cross-user row access because the JPQL query already scopes to the conversation passed in by Spring AI's `MessageWindowChatMemory`. The replanner should explicitly verify this scoping in the SUMMARY.
- **T-08-08-08** (NEW) — Information Disclosure: `AuditDurabilityTest` verification reads using `.unconstrained()` could mask a real production-runtime read-policy bug for `ai_AiAuditEvent` (test passes even if production read paths are broken). Disposition: `accept`. Mitigation: `AuditDurabilityTest` is a write-durability test (REQUIRES_NEW propagation contract), not a read-policy test. Read-policy for `ai_AiAuditEvent` is covered separately by `FilteredSchemaAndExecutionDenialTest.carol_*` (which uses constrained DM and stays RED→GREEN per RED-1 + RED-2). The two tests verify orthogonal contracts; using `.unconstrained()` for the durability test's verification reads does not erode read-policy coverage.

## Key references for the replanner

- Jmix Context7 docs: `/jmix-framework/jmix-context7` → `data-access/data-manager.html` (canonical UnconstrainedDataManager pattern)
- Project memory: `feedback_jmix_unconstrained_for_system_writes` (project standard)
- Project memory: `feedback_jmix_loadvalue_store` (note: agentstore + UnconstrainedDataManager interaction is not exercised by this plan — reads here are on `main` store entities)
- Predecessor: `08-08-INVESTIGATION.md` (attempt #1 evidence — bare gradle dep change)
- Verified at `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\orchestration\ProjectingChatMemoryRepository.java:37` (constrained `DataManager` field — confirms it's the regression source)
- Verified at `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java:60` (the original constrained `DataManager` field, switched in attempt #2)

## Working-tree state at handoff

- `ai-agent.gradle`, `AuditWriter.java`, `FoundationsBootSmokeTest.java` — all reverted by executor; no commits
- HEAD: `22ffd05` (the attempt-#2 plan replan commit)
- `08-08-PLAN.md`: still on disk, scope superseded by THIS investigation
- `.planning/STATE.md` and `.planning/config.json` — orchestrator-owned timestamp drift (not from executor)

## Next command

```
/clear
/gsd-plan-phase 8 --gaps
```

The replanner should read **both** `08-08-INVESTIGATION.md` (attempt #1) and **this** `08-08-INVESTIGATION-2.md` (attempt #2) plus `08-VERIFICATION.md` Gap 1 to produce the revised plan. The new plan must explicitly catalogue every `.unconstrained()` call site (5 files, exact line numbers verified by the replanner reading each file).
