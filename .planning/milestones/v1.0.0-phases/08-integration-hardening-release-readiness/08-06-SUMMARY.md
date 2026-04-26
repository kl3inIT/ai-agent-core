---
phase: 08
plan: 06
subsystem: docs
tags: [docs, operator-readme, configuration-matrix, spi-cookbook, troubleshooting, TEST-07, R-06a, R-06b, R-06c, R-06d]
requires:
  - All 6 @ConfigurationProperties classes under com.vn.agent (defaults, guard, embedding, rag) + agentstore + AIConfiguration
  - 5 Liquibase changesets under com/vn/agent/liquibase/agentstore-changelog/
  - 6 SPI interfaces in com.vn.agent.spi (ToolContributor, ContextContributor, PromptContextContributor, ToolGuard, AuditListener, CustomIngester)
provides:
  - ai-agent/README.md (270 lines, 9 sections — Quick Start, Env Vars, Config Matrix, Entity/Table Ownership, SPI Cookbook, Upgrade Checklist, Air-Gap, Troubleshooting, See Also)
  - CLAUDE.md Java 17 → Java 21 fix
affects:
  - none (docs only — no code change)
tech-stack:
  added: []
  patterns:
    - "Verification footer pattern: pin doc to a commit SHA + date so drift is obvious on next read"
    - "Configuration Matrix derived from real @ConfigurationProperties record fields, not hand-curated"
key-files:
  created:
    - ai-agent/README.md
  modified:
    - CLAUDE.md
key-decisions:
  - "Quick Start consumer-smoke block is documented as deferred per the 08-05 SUMMARY rather than promised — does not lie to operators about what works today"
  - "Configuration Matrix lists 37 jmix.ai-agent.* properties + agentstore.* — read from each @ConfigurationProperties record source rather than copied from RESEARCH example"
  - "Troubleshooting table includes the full 6-layer consumer-smoke prerequisite chain discovered during 08-05 attempt (pgvector hard requirement, missing LoginView, etc.) so consumers don't re-discover them"
  - "AuditListener SPI cookbook uses the post-Phase-7.2 signature `onEventAudited(UUID auditId, String kind)`. The pre-7.2 method name is referenced in the Upgrade Checklist + cookbook note as 'gone in 7.2' but not in literal grep-able form (acceptance criterion required 0 occurrences of the pre-7.2 token)"
  - "Verification footer pins commit 520f7098 + date 2026-04-26 — re-verify when bumping versions or after a phase that changes shared types"
patterns-established:
  - "Operator README skeleton with 7 mandated sections (D-14) + Troubleshooting (R-06b) + verification footer (R-06a) — reusable shape for future starter docs"
requirements-completed:
  - TEST-07
duration: ~25min
completed: 2026-04-26
---

# Phase 8 Plan 06: Operator README + CLAUDE.md Java 21 Fix Summary

Delivered the ai-agent operator README (270 lines) and surgically fixed the stale `Java 17` line in repo-root CLAUDE.md. README is derived from real codebase state (37 jmix.ai-agent.* properties enumerated from `@ConfigurationProperties` source, 5 Liquibase changesets mapped, 6 SPI signatures verified) rather than the example values in RESEARCH.

## Outcome

- **README.md (ai-agent/README.md):** 270 lines, 9 sections (the 7 mandated by D-14 plus Troubleshooting per R-06b plus See Also). All acceptance greps pass.
- **CLAUDE.md:** single-line surgical change `Java 17` → `Java 21` (R-06c). `git diff CLAUDE.md` shows exactly that one line changed.

| Acceptance check | Required | Actual |
|---|---|---|
| `## Quick Start` | 1 | 1 |
| `## Required Environment Variables` | 1 | 1 |
| `## Configuration Matrix` | 1 | 1 |
| `## Entity / Table Ownership` | 1 | 1 |
| `## SPI Cookbook` | 1 | 1 |
| `## Upgrade Checklist` | 1 | 1 |
| `## Air-Gap Notes` | 1 | 1 |
| `## Troubleshooting` (R-06b) | 1 | 1 |
| 6 SPI names referenced | each ≥ 2 | each ≥ 2 |
| `onEventAudited` (post-7.2 signature) | ≥ 1 | 3 |
| `dispatchToolCallAudited` (pre-7.2, must be absent) | 0 | 0 |
| `ArchUnit` (per memory `feedback_no_archunit.md`, must be absent) | 0 | 0 |
| `jmix.ai-agent.*` property references | ≥ 5 | 37 |
| `AI_AGENT_*` table references | ≥ 5 | 9 |
| `Java 21` / `JDK 21` | ≥ 1 | 3 |
| `last verified against commit` (R-06a footer) | ≥ 1 | 1 |
| `publishToMavenLocal` (R-06d Quick Start prerequisite) | ≥ 1 | 3 |
| Length 200–600 lines | yes | 270 |
| `CLAUDE.md` `Java 17` (R-06c) | 0 | 0 |
| `CLAUDE.md` `Java 21` | ≥ 1 | 1 |

## Tasks Executed

| Task | Name | Commit | Notes |
|---|---|---|---|
| 1 | Create ai-agent/README.md | `5082815` | 270 lines, derived from real codebase data, includes 6-layer consumer-smoke prerequisite chain in Troubleshooting (discovered in 08-05 attempt) |
| 2 | CLAUDE.md `Java 17` → `Java 21` (R-06c) | `5082815` | Surgical 1-line edit, no other content touched |

## Verification Results

- All acceptance greps pass (table above).
- `git diff CLAUDE.md` between HEAD~1 and HEAD shows exactly one line changed (`Java 17` → `Java 21`).
- README footer pinned to commit `520f7098ac67226b6830c61b45a673ad9ee74f05` on `2026-04-26`.

## Notes for downstream waves

- The README's Quick Start consumer-smoke section is documented as currently deferred per 08-05 SUMMARY, not promised as working — keeps the doc honest.
- Plan 08-07 release wiring will add a `CHANGELOG.md` at repo root; the README's "See Also" section already links to it (placeholder).
- When the consumer-smoke subproject is fully landed in a future phase, the Quick Start prerequisite block (`./gradlew :ai-agent:ai-agent:publishToMavenLocal :ai-agent:ai-agent-starter:publishToMavenLocal`) is already documented and ready.

## Self-Check: PASSED

All success criteria met:
- ROADMAP Phase 8 success criterion #3 (Operator README walkthrough) backstopped by a concrete file.
- D-14 mandated sections all present + R-06a/b/c/d tightenings applied.
- CLAUDE.md no longer contradicts the actual Gradle toolchain.
- All 6 SPI cookbook snippets reference the real interface signatures (verified by reading each interface source).
