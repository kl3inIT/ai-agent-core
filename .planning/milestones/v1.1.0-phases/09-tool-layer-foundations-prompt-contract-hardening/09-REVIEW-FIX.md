---
phase: 09-tool-layer-foundations-prompt-contract-hardening
fixed_at: 2026-04-26T23:24:51Z
review_path: .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-REVIEW.md
iteration: 1
findings_in_scope: 9
fixed: 9
skipped: 0
status: all_fixed
---

# Phase 9: Code Review Fix Report

**Fixed at:** 2026-04-26T23:24:51Z
**Source review:** .planning/phases/09-tool-layer-foundations-prompt-contract-hardening/09-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope (blocker + warning): 9
- Fixed: 9
- Skipped: 0
- Out of scope (info, not attempted): 4 (IN-01 through IN-04)

All blocker and warning findings were fixed. Several are security/behavioral contract changes
and are marked below as "fixed: requires human verification" so they can be reviewed under a
real Jmix security configuration before phase verification is closed.

## Fixed Issues

### BL-01: `agent.roles` rendering order is not deterministic

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java`

**Commit:** c6b89d6
**Applied fix:** Changed role collection from insertion-order `LinkedHashSet` to a sorted
`TreeSet`, and added a regression asserting identical `renderAsText(...)` output for users
with the same roles returned in opposite orders.

### BL-02: `getRelatedRecords` bypasses `FetchPlanIntersector` for relationship `_instance_name`

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java`

**Commit:** 4688d3d
**Status:** fixed: requires human verification
**Applied fix:** Injected `FetchPlanIntersector` into `BuiltInDataTools`, composed the
data-plan plus relationship `_instance_name` projection, then intersected that composed plan
before passing it to `DataManager.load(...)`. The test now verifies `get_related_records`
loads with the intersected composed plan.

### WR-01: `OutputScannerAdvisor.adviseCall` returns null while declaring `@NonNull`

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java`

**Commit:** ae49528
**Applied fix:** Treats a null `CallAdvisorChain` response as an upstream contract violation
and throws `IllegalStateException` instead of returning null from a non-null override. Added
coverage for the null-chain response path.

### WR-02: `FetchPlanIntersector.walk` does not check nested target entity read access

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java`

**Commit:** ddd1ef2
**Status:** fixed: requires human verification
**Applied fix:** Added a `canReadEntity(...)` check before recursing into nested class ranges.
Denied targets now drop the relationship path and emit the existing `PLAN_NARROWED` audit row.

### WR-03: `PLAN_NARROWED` audit row embeds unbounded host-supplied attribute names

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java`

**Commit:** e6f7715
**Applied fix:** Capped the dropped-path list to 20 entries, trimmed each audit path to 64
characters, and added a suffix count for omitted entries. Added regression coverage for list
and path-length bounding.

### WR-04: `extractUserKey` swallows broad reflective exceptions silently

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java`

**Commit:** 6470bf9
**Applied fix:** Narrowed exception handling around reflective `getKey()`, logged non-standard
return types and invocation failures at debug, and added a regression that falls back to
username when reflective key extraction fails.

### WR-05: Default-on Boolean logic is harder to read than needed

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AiAgentAuditPropertiesTest.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AiAgentGuardPropertiesTest.java`

**Commit:** e526180
**Applied fix:** Replaced redundant null-or-double-negation expressions with the canonical
`!Boolean.FALSE.equals(...)` default-on form, centralized the guard property helper, and added
unit coverage for omitted and explicitly disabled settings.

### WR-06: Unknown-entity hint strings have no single source of truth

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/UnknownEntityHints.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java`

**Commit:** c85b852
**Applied fix:** Added `UnknownEntityHints` as the shared D-14 constants holder and rewired both
tool errors and system-prompt rules to use the same constants.

### WR-07: `FetchPlanContext` exposes raw `UserDetails` to host SPI

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/spi/FetchPlanContextTest.java`

**Commit:** d2d31f1
**Status:** fixed: requires human verification
**Applied fix:** Replaced raw `UserDetails` exposure with `FetchPlanContext.UserSnapshot`
containing only `username` and a sorted, unmodifiable role set. `FetchPlanResolver` now
projects the current user at the tool boundary.

## Skipped Issues

None.

## Out of Scope

Info-severity findings were not attempted because this run used the default
`critical_warning` fix scope:

- IN-01: `RecordsPayload.hint` hardcoded English string.
- IN-02: `AiAgentGuardProperties` Javadoc could mention Phase 9 dynamic pattern packs.
- IN-03: `AiAgentPromptProperties.resolvedEntityInventoryLimit()` non-positive validation.
- IN-04: `module.properties` comment for intentionally empty sensitive-fields default.

## Verification

- JetBrains `get_file_problems(..., errorsOnly=false)` run on all touched Java files: no errors.
  Remaining warnings were triaged as intentional defensive guards, reflection/test helper usage,
  `@Tool` reflection entry points, or pre-existing style warnings.
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.orchestration.BaselineContextProviderTest`
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.tools.UnknownEntityRetryHintTest`
- `./gradlew.bat :ai-agent:evalTest --tests com.vn.agent.guard.OutputScannerAdvisorTest`
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.tools.FetchPlanIntersectorTest`
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.audit.AiAgentAuditPropertiesTest --tests com.vn.agent.guard.AiAgentGuardPropertiesTest`
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.guard.AgentSystemPromptRulesTest --tests com.vn.agent.tools.UnknownEntityRetryHintTest`
- `./gradlew.bat :ai-agent:test --tests com.vn.agent.spi.FetchPlanContextTest --tests com.vn.agent.tools.FetchPlanIntersectorTest`
- `./gradlew.bat :ai-agent:test :ai-agent-starter:test :ai-agent:evalTest`

---

_Fixed: 2026-04-26T23:24:51Z_
_Fixer: Codex (inline fallback after gsd-code-fixer worktree setup failed)_
_Iteration: 1_
