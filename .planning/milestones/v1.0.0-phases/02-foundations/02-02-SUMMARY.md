---
phase: 02-foundations
plan: 02
subsystem: api
tags: [spi, spring-ai, jmix, extension-points, javadoc]

requires:
  - phase: 01-bootstrap
    provides: [ai-agent add-on module skeleton, Spring AI 1.1.4 BOM on compileClasspath]
provides:
  - 6 SPI interfaces in com.vn.agent.spi locking the host extension surface for Phase 3+
  - ToolVetoedException (unchecked) supporting ToolGuard veto semantics
  - Verbatim-from-RESEARCH signatures + Javadoc contracts for every SPI
affects: [03-tools, 04-chat-service, 05-knowledge-base, 06-admin-ui]

tech-stack:
  added: []
  patterns:
    - "Flat SPI package (com.vn.agent.spi) — interfaces + Javadoc only, no impl"
    - "Reserved agent.* namespace in ToolContext bag; host contributors use their own namespace"
    - "Unchecked veto exception pattern — ToolVetoedException extends RuntimeException"
    - "FQCN in signatures (java.util.*, org.springframework.ai.document.Document) — avoids import churn in SPI file"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ContextContributor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/PromptContextContributor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolVetoedException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditListener.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java
  modified: []

key-decisions:
  - "SPIs ship signatures + Javadoc only in Phase 2; no-op defaults arrive in plan 07; wiring in Phase 4+"
  - "ToolVetoedException is unchecked so @Tool method bodies don't need throws declarations"
  - "ToolContext baseline keys (agent.userId, agent.username, agent.roles, agent.locale, agent.conversationId) reserved — contributors must namespace their own keys"

patterns-established:
  - "SPI layout: flat package com.vn.agent.spi; fully-qualified types in signatures; contract clauses in Javadoc"
  - "Javadoc-as-contract: every SPI documents baseline the add-on supplies, and what the host must/must-not do"

requirements-completed: [SPI-01, SPI-02, SPI-03, SPI-05, SPI-06, SPI-07]

duration: 5min
completed: 2026-04-19
---

# Phase 02 Plan 02: SPI Interfaces Summary

**Six host extension SPIs (ToolContributor, ContextContributor, PromptContextContributor, ToolGuard, AuditListener, CustomIngester) plus unchecked ToolVetoedException, locking the add-on's host-facing contract before Phase 3 implementation begins.**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-04-19
- **Completed:** 2026-04-19
- **Tasks:** 2 (merged into a single atomic commit — no cross-task dependencies, build verification skipped per instruction)
- **Files modified:** 7 created

## Accomplishments
- All 6 SPI interfaces created in `com.vn.agent.spi` with signatures verbatim from 02-RESEARCH.md
- `ToolVetoedException` provided as unchecked RuntimeException with two constructors
- Every interface carries Javadoc with at least one integration example or explicit contract clause
- Reserved `agent.*` baseline keys documented on `ContextContributor`; namespace rules captured on `PromptContextContributor` too

## Task Commits

1. **Task 1 + Task 2 merged: create 7 SPI files** — `e3e1554` (feat)

(Tasks 1 and 2 were combined into one atomic commit because Task 2's build-verification step was explicitly skipped per orchestrator instructions — `node` and therefore `gsd-sdk`/Gradle verification were unavailable on the host.)

**Plan metadata:** pending after this SUMMARY commit.

## Files Created/Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java` — SPI-01; host supplies additional `@Tool`-annotated beans
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ContextContributor.java` — SPI-02; host injects app-specific entries into ToolContext bag (baseline `agent.*` reserved)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/PromptContextContributor.java` — SPI-03; host appends domain-specific prompt fragments, ordered via `getOrder()`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` — SPI-05; host vetoes tool calls via `ToolVetoedException`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolVetoedException.java` — unchecked veto signal; message surfaces as `AiToolCallAudit.denialReason`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditListener.java` — SPI-06; fire-and-forget side-channel observers for audit writes
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java` — SPI-07; pluggable knowledge-base sources returning `org.springframework.ai.document.Document`

## Decisions Made
- Merged Task 1 and Task 2 into a single commit because the Gradle compile verification step in Task 2 was intentionally skipped (node/gradle verification prohibited by orchestrator context on Windows host) — no compile-check gate to separate the commits.
- Added extra Javadoc contract paragraph on `AuditListener`, `ToolVetoedException`, `ToolGuard`, and `CustomIngester` (beyond the minimum shown in the plan snippets) to satisfy must-have "integration example OR contract clause" for every interface.

## Deviations from Plan

### Auto-fixed / Scope Adjustments

**1. [Rule 3 - Blocking] Skipped `./gradlew :ai-agent:ai-agent:compileJava` verification**
- **Found during:** Task 2
- **Issue:** Orchestrator instructions prohibited running Gradle (slow on Windows, no node/gsd-sdk). Task 2's automated verify step depended on it.
- **Fix:** Omitted the compile check; relied on grep-based signature verification which all passed. Spring AI `Document` import is FQCN in the source and matches the BOM resolved by Phase 1.
- **Files modified:** none (process deviation)
- **Verification:** All `grep -q` checks from Task 1 and Task 2 `<verify>` blocks pass; `ToolGuard` references `ToolVetoedException` in the same package (no import needed).
- **Committed in:** `e3e1554`

**2. [Rule 2 - Missing Critical Contract Clause] Added extra Javadoc on `ToolVetoedException`, `AuditListener`, `ToolGuard`, `CustomIngester`**
- **Found during:** Task 1 + Task 2
- **Issue:** Plan snippets for `AuditListener` and `ToolVetoedException` had only a brief description; must-have #4 demands each interface "carries Javadoc including at least one integration example or contract clause". The bare descriptions arguably satisfied this, but were weak.
- **Fix:** Added explicit contract paragraphs (denialReason persistence rule, listener error-swallowing rule, stable-id rule for `CustomIngester`) and an `@Example` block on `ToolGuard`.
- **Files modified:** ToolGuard.java, ToolVetoedException.java, AuditListener.java, CustomIngester.java
- **Committed in:** `e3e1554`

---

**Total deviations:** 2 (1 blocking-skip process deviation, 1 Javadoc enhancement)
**Impact on plan:** No scope creep. Compile verification is deferred to Phase 02 plan 07 (no-op defaults) or the next gradle-capable environment. All grep-verifiable acceptance criteria satisfied.

## Issues Encountered
- Git LF/CRLF warnings on write — cosmetic, autocrlf behaviour; no action needed.

## User Setup Required
None.

## Next Phase Readiness
- Host extension surface locked; Phase 3 (tools) and Phase 4 (chat wiring) can reference these types immediately.
- Plan 02-07 (no-op defaults) will add `@ConditionalOnMissingBean` defaults.
- Recommend running `./gradlew :ai-agent:ai-agent:compileJava` in the next gradle-capable step as a belt-and-braces check before Phase 3.

## Self-Check: PASSED
- All 7 files confirmed present on disk.
- Commit `e3e1554` present in `git log`.
- All `grep -q` acceptance checks from plan `<verify>` blocks pass.

---
*Phase: 02-foundations*
*Completed: 2026-04-19*
