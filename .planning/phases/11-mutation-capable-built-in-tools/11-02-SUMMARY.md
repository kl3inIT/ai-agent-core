---
phase: 11-mutation-capable-built-in-tools
plan: 02
subsystem: tools-mutation
tags: [config-properties, enum-extension, scheduling, i18n]

# Dependency graph
requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: AiMutationIntent agentstore entity (Plan 11-01 — composite-unique idempotency table downstream consumers reference)
provides:
  - AiAgentMutationProperties @ConfigurationProperties record (prefix ai-agent.tools.mutation)
  - 4 resolved* accessors with conservative defaults (enabled=false, allowDelete=false, confirmationRequired=true, idempotencyTtl=24h)
  - AiToolCallOutcome enum extension — IDEMPOTENT_REPLAY + COMMIT_FAILED values (no schema migration)
  - @EnableScheduling on AIConfiguration — Spring @Scheduled bean activation (host-wide side effect)
  - Bilingual captions for the 2 new enum values in both metaclass and view-bundle key conventions
affects: [11-03, 11-04, 11-05, 11-06, 11-07A, 11-07B, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@ConfigurationProperties record + resolved* null-tolerant accessors (mirrors AiAgentAuditProperties Phase 9 D-18 shape)"
    - "Host-wide @EnableScheduling activation alongside @EnableAsync — single annotation enables ALL host-app @Scheduled beans"
    - "Enum extension via EnumClass<String> with no schema migration — column type already varchar"
    - "Switch statement enum-extension caught at compile time — switch (outcome) without default forces explicit handling of new cases (Rule 3 blocking-issue auto-fix on AiAuditEventDetailDialog)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventDetailDialog.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "AiAgentMutationProperties prefix is ai-agent.tools.mutation (NOT jmix.ai-agent.tools.mutation) per PATTERNS.md verbatim and CONTEXT.md @ConditionalOnProperty(prefix='ai-agent.tools.mutation') — matches the @ConditionalOnProperty contract that the conditional BuiltInMutationTools bean will use in Plan 11-03"
  - "@EnableScheduling placed directly below @EnableAsync on AIConfiguration class header — minimal-diff intent; both annotations are intentional host-wide Spring side effects of installing the add-on"
  - "AiAuditEventDetailDialog.outcomeTheme mapping (Rule 3 auto-fix): IDEMPOTENT_REPLAY → 'success' theme (replay returns the same successful result); COMMIT_FAILED → 'error' theme (host save status unknown is a tangible operational error to the audit-list operator)"
  - "auditList.outcome.* keys added in BOTH locale bundles to match existing AiAuditEventListView lookup convention (row.getOutcome().name().toLowerCase()); without these keys the new outcomes would render as raw bundle-key strings in the audit list UI (Rule 2 missing critical i18n)"

patterns-established:
  - "Mutation-tools configuration follows AiAgentAuditProperties shape: @ConfigurationProperties record + resolved*() null-tolerant accessors with conservative defaults"
  - "Enum extensions on EnumClass<String> are zero-migration — only add values + bundle captions + downstream switch cases"
  - "When extending an enum that already has a String→Theme switch consumer, also extend the switch in the same commit; Java will refuse to compile non-default switches that miss cases (compile-time safety net)"
  - "@EnableScheduling activation is documented as a host-wide Spring side effect of installing the add-on; cleanup-job classes can now use @Scheduled without further wiring"

requirements-completed:
  - MUT-06
  - AUD-06
  - MUT-11

# Metrics
duration: 3min
completed: 2026-04-28
---

# Phase 11 Plan 02: Configuration Foundation Summary

**Mutation-tools configuration foundation — `AiAgentMutationProperties` record + `AiToolCallOutcome` enum extension (`IDEMPOTENT_REPLAY` / `COMMIT_FAILED`) + `@EnableScheduling` on `AIConfiguration`, plus the bilingual outcome captions every downstream Phase 11 plan consumes.**

## Performance

- **Duration:** ~3 min (per-task gradle compile already warm)
- **Started:** 2026-04-28T20:10:53Z
- **Completed:** 2026-04-28T20:14Z (final commit baaaf78)
- **Tasks:** 2 / 2
- **Files modified:** 6 (1 created + 5 modified)

## Accomplishments

- `AiAgentMutationProperties` ships as a `@ConfigurationProperties("ai-agent.tools.mutation")` record with the four fields downstream Phase 11 code reads — `enabled`, `allowDelete`, `confirmationRequired`, `idempotencyTtl` — plus four null-tolerant `resolved*()` accessors. Auto-discovered by the existing `@ConfigurationPropertiesScan` on `AIConfiguration` (no extra wiring).
- `AiToolCallOutcome` gains the two outcome values the mutation tool layer needs without a schema migration. Column type stays `varchar` via `EnumClass<String>`; only new ids and message-bundle captions land.
- `@EnableScheduling` is now active on `AIConfiguration` (placed directly below the existing `@EnableAsync`). All host-app `@Scheduled` beans are activated by installing the add-on — operator-visible side effect documented here and in the locale captions for the two new outcomes.
- Both locale bundles carry the new enum captions in BOTH key conventions: `com.vn.agent.entity/AiToolCallOutcome.IDEMPOTENT_REPLAY|COMMIT_FAILED` (metaclass-format keys, used wherever Jmix metaclass captions are looked up) and `auditList.outcome.idempotent_replay|commit_failed` (view-bundle keys used by `AiAuditEventListView` + `AiAuditEventDetailDialog`).
- `AiAuditEventDetailDialog.outcomeTheme(...)` switch extended with the two new enum values (Rule 3 auto-fix): `IDEMPOTENT_REPLAY → "success"` (replay surfaces the same successful host result), `COMMIT_FAILED → "error"` (host-save status unknown is a real operational error in the audit list).

## Task Commits

Each task was committed atomically:

1. **Task 1: AiAgentMutationProperties record** — `0a82963` (feat)
2. **Task 2: AiToolCallOutcome extension + @EnableScheduling + locale captions + switch fix** — `baaaf78` (feat)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java` — `@ConfigurationProperties("ai-agent.tools.mutation")` record. 4 fields (`enabled`, `allowDelete`, `confirmationRequired`, `idempotencyTtl`); 4 `resolved*()` accessors. Defaults: `false / false / true / Duration.ofHours(24)`.

### Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java` — added `IDEMPOTENT_REPLAY("IDEMPOTENT_REPLAY")` and `COMMIT_FAILED("COMMIT_FAILED")` after the existing `FLAGGED` value. Existing `getId`/`fromId` work unchanged.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — added `import org.springframework.scheduling.annotation.EnableScheduling;` and the class-level `@EnableScheduling` annotation directly below `@EnableAsync`. No bean changes.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventDetailDialog.java` — extended the `outcomeTheme(AiToolCallOutcome)` switch with cases for `IDEMPOTENT_REPLAY` (theme `"success"`) and `COMMIT_FAILED` (theme `"error"`). Required because the existing switch had no `default` branch — Java refused to compile after enum extension (Rule 3 blocking issue).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — appended 4 new keys: 2 metaclass-format (`com.vn.agent.entity/AiToolCallOutcome.{IDEMPOTENT_REPLAY,COMMIT_FAILED}`) and 2 view-bundle-format (`auditList.outcome.{idempotent_replay,commit_failed}`).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — matching 4 Vietnamese translations.

## Decisions Made

- **Property prefix `ai-agent.tools.mutation`** (NOT `jmix.ai-agent.tools.mutation`) — matches CONTEXT.md `@ConditionalOnProperty(prefix="ai-agent.tools.mutation")` contract that Plan 11-03 will use to gate the `BuiltInMutationTools` bean. Verbatim from PATTERNS.md AiAgentMutationProperties section. (Note: this differs from the `jmix.ai-agent.audit` prefix used by `AiAgentAuditProperties` — intentional; the prefix is namespaced under the tools subtree.)
- **`@EnableScheduling` on `AIConfiguration` class header** (not on a separate `SchedulingConfiguration` bean) — minimal diff, mirrors the existing `@EnableAsync` precedent. Documented in plan must-haves and operator-visible accomplishment list as a host-wide Spring side effect.
- **`outcomeTheme` mapping for new enum values:** `IDEMPOTENT_REPLAY → "success"` because the replay returns the same successful host result; `COMMIT_FAILED → "error"` because the operator needs to investigate stuck `COMMIT_UNKNOWN` rows. No new theme strings added.
- **Both metaclass-format AND view-bundle-format keys added** in each locale bundle — `AiAuditEventListView.auditsDataGridOutcomeRenderer` uses the lowercase view-bundle convention and `AiAuditEventDetailDialog.applyOutcomeBadge` uses the same lowercase convention; without `auditList.outcome.*` keys the new outcomes would render as raw bundle keys in production (Rule 2 missing critical i18n).
- **No `MutationGuard` SPI bean here** — that lands in Plan 11-04 alongside the SPI interface; placing it now would risk a compile cycle since the SPI interface has not been authored yet.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking issue] `AiAuditEventDetailDialog.outcomeTheme` switch did not cover the new enum values**

- **Found during:** Task 2 verification (`./gradlew :ai-agent:compileJava` failed)
- **Issue:** `outcomeTheme(...)` uses a switch expression with no `default` branch. Adding `IDEMPOTENT_REPLAY` and `COMMIT_FAILED` to the enum made Java's exhaustiveness check fail with `error: the switch expression does not cover all possible input values`.
- **Fix:** Added two `case` arms inside the same task commit — `IDEMPOTENT_REPLAY → "success"`, `COMMIT_FAILED → "error"`. Compile then passed.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventDetailDialog.java`
- **Commit:** `baaaf78` (folded into the Task 2 commit because the fix is mechanically required by the enum extension itself)

**2. [Rule 2 — Missing critical functionality] `auditList.outcome.idempotent_replay` / `auditList.outcome.commit_failed` view-bundle keys were not in the plan's literal acceptance list**

- **Found during:** Task 2 — when reading `AiAuditEventDetailDialog.applyOutcomeBadge` and `AiAuditEventListView.auditsDataGridOutcomeRenderer`, I noticed both consumers look up `auditList.outcome.<lowercase-name>`, NOT the metaclass-format `com.vn.agent.entity/AiToolCallOutcome.<NAME>` key the plan listed.
- **Issue:** Without the `auditList.outcome.*` keys, the new outcomes would render as `auditList.outcome.idempotent_replay` (raw bundle key) on the operator audit list / detail dialog — a visible i18n bug in production.
- **Fix:** Added 2 new lowercase keys in BOTH locale bundles alongside the metaclass-format keys the plan asked for (so 4 keys per bundle total — 2 conventions × 2 enum values).
- **Files modified:** `messages_en.properties`, `messages_vi.properties`
- **Commit:** `baaaf78`

## Issues Encountered

- **Compile failure on first verification:** `./gradlew :ai-agent:compileJava` failed with exhaustiveness error in `AiAuditEventDetailDialog.outcomeTheme`. Diagnosed as Rule 3 blocking issue (mechanically caused by the enum extension itself); fixed inline. Recompile passed cleanly. No re-attempt needed beyond the single fix.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** the JetBrains MCP server is not registered in this execution environment. Per CLAUDE.md workflow guidance, the following Java files should be opened in IntelliJ for `get_file_problems("path", onlyErrors=false)` triage during the next session that has the MCP available:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventDetailDialog.java`

  The Gradle compile passed with zero ERROR-level Javac diagnostics, so no functional issue is expected; this is a precautionary review only.

## User Setup Required

None — no external service configuration required. Hosts opt in to mutation tools by setting `ai-agent.tools.mutation.enabled=true` in their application config in a later session; default-OFF posture is preserved by this plan.

## Next Phase Readiness

- Plan 11-03 (or whichever plan introduces `BuiltInMutationTools` and the rest of the mutation gating chain) can now read `AiAgentMutationProperties.resolvedEnabled()` to gate its `@ConditionalOnProperty` annotation, reference `AiToolCallOutcome.IDEMPOTENT_REPLAY` / `COMMIT_FAILED` directly, and rely on `@EnableScheduling` being active when registering `MutationIntentCleanupJob`.
- No blockers.

## Self-Check: PASSED

All claimed files exist on disk and both task commit hashes are present in `git log`.

- 6 / 6 files verified (1 created, 5 modified, plus this SUMMARY.md)
- 2 / 2 commit hashes verified (`0a82963`, `baaaf78`)

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-28*
