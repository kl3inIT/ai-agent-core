---
phase: 02-foundations
plan: 11
subsystem: planning-docs
tags: [docs, scope-management, d-10, d-02]
requires: [02-CONTEXT.md D-02, D-10]
provides: [synced-requirements, synced-roadmap, synced-project, synced-stacks]
affects: [downstream-phase-planners]
tech-stack:
  added: []
  patterns: [scope-reduction-log, deferred-decisions-table]
key-files:
  created:
    - .planning/phases/02-foundations/02-11-SUMMARY.md
  modified:
    - .planning/REQUIREMENTS.md
    - .planning/ROADMAP.md
    - .planning/PROJECT.md
    - .planning/codebase/STACK.md
    - .planning/research/STACK.md
decisions:
  - "D-10 scope reductions applied across four authoritative planning docs (5 entities, 6 SPIs, no UI-07, TEST-06 deferred, TOOL-08 de-ArchUnit'd)"
  - "D-02 Liquibase-include correction captured in ROADMAP Phase 2 deliverables; no other docs claimed auto-discovery so no further corrections applied"
  - "Spring AI pinned at 1.1.4 in PROJECT Active/Integration/Constraints and both STACK.md copies"
metrics:
  duration: ~25min
  completed: 2026-04-19
---

# Phase 02 Plan 11: Sync REQUIREMENTS/ROADMAP/PROJECT/STACK with D-02/D-10 Summary

Propagate Phase 2 CONTEXT decisions D-02 (Liquibase explicit `<include>`) and D-10 (scope reductions + Spring AI 1.1.4 pin) across the four authoritative planning docs so downstream phases plan against the correct surface area.

## Diffs Applied

### `.planning/REQUIREMENTS.md`

- **ENT-01** rewritten: "Six Jmix JPA entities ... `AiExposureRule`" → "Five Jmix JPA entities ... (`AiExposureRule` dropped per D-10 ...)".
- **SEC-01** Exposure view reference dropped; D-10 trace added.
- **SEC-03** / **AUD-05** / **TOOL-02** / **TOOL-08** ArchUnit references converted to code-review + unit-test conventions with D-10 traces.
- **SPI block**: SPI-04 (`EntityExposurePolicy`) bullet removed; SPI-08 (per-SPI integration-test obligation) bullet removed; trailing D-10 note added explaining substitution with Phase 02 boot smoke test.
- **UI-07** bullet removed; trailing D-10 note preserving UI-08..UI-10 numbering stability.
- **TEST-06** body replaced with "(removed per D-10 — ArchUnit rules deferred ...)" (checkbox preserved to keep line count stable for cross-doc refs).
- **Out of Scope** line re ArchUnit enforcement reworded.
- **Scope Changes Log** appended summarising D-10 deltas.
- `Last updated:` fields bumped to `2026-04-18 after Phase 02 planning — D-10 applied`.

### `.planning/ROADMAP.md`

- Phase 2 summary row: Requirements reduced to `ENT-01..04, SEC-01..04, SPI-01, SPI-02, SPI-03, SPI-05, SPI-06, SPI-07 (interfaces only)`; Goal reworded to drop ArchUnit.
- Phase 2 detail: Requirements list identical to row; deliverables "Six JPA" → "Five JPA"; Seven SPIs → Six SPIs; ArchUnit deliverable replaced with SpiDefaultsAutoConfiguration; added explicit D-02 host `<include>` deliverable; success criterion 1 reworded to "boot smoke test (5 assertions) green"; criterion 4 "seven" → "six"; Plans list finalised (11 entries 02-01..02-11).
- Phase 3 row/detail: SPI-04 (impl) removed; deliverables de-`EntityExposurePolicy`'d; ArchUnit deliverable/success criterion reworded; TOOL-08 footnote added.
- Phase 7 row/detail: UI-07 dropped; Requirements list enumerated explicitly; `ExposureRuleListView` deliverable removed.
- Phase 8 row: TEST-06 dropped.
- Phase 1 Goal + BOM deliverable bumped from Spring AI 1.0.2 → 1.1.4 with D-10 trace (for cross-doc consistency).
- Traceability table: SPI list narrowed to SPI-01..03, SPI-05..07; UI list narrowed to UI-01..06, UI-08..10; TEST list narrowed to TEST-02..05, TEST-07; D-10 footnote added.
- Total mapped: `73 of 73` → `69 of 69` with delta breakdown.
- `Last updated:` bumped.

### `.planning/PROJECT.md`

- "What This Is" vision sentence de-`exposure policies` with D-10 footnote.
- **Metadata-first runtime**: "Additional AI exposure layer" bullet replaced with "Authorization flows exclusively through Jmix AccessManager + DataManager — no parallel AI-only exposure layer shipped in v1 (per D-10 ...)".
- **Spring AI 1.0.2 integration** heading → **Spring AI 1.1.4 integration**; advisor bullet annotated with D-10 verification flag.
- **Access & security**: `AiAgentAdminRole` gates list de-Exposure'd.
- **SPI extension points**: "Entity exposure policy" bullet removed.
- **Testing**: "exposure policy" test item removed with D-10 trace.
- **Constraints**: Spring AI version pinned at 1.1.4 with D-10 trace; Security constraint reworded (Jmix single enforcement layer, no AI exposure layer).
- Two new **Deferred Decisions** entries: (1) `AiExposureRule`/`EntityExposurePolicy`/`ExposureRuleListView` deferred per D-10; (2) ArchUnit enforcement deferred per D-10.
- Legacy "Spring AI 1.0.2 milestone" Context bullet and "Spring AI 1.0.2 with official primitives only" Key Decisions row rephrased to 1.1.4 so `grep -c 1.0.2` returns 0.
- `Last updated:` bumped.

### `.planning/codebase/STACK.md`

- New "Spring AI (add-on surface)" section added (codebase stack doc had no prior Spring AI surface) pinning 1.1.4 with D-10 trace and milestone-repo note. Added footnote pointing to RESEARCH §"Spring AI version delta" and REQUIREMENTS Scope Changes Log.
- Footer stack-analysis date line annotated with "Spring AI pinned at 1.1.4 per D-10".

### `.planning/research/STACK.md`

- Every `1.0.2` literal replaced with `1.1.4` (Domain header, Overall confidence note, Core Technologies row, Spring AI BOM row, Spring AI artifacts heading, milestone-repo comment, Gradle snippets for functional and flowui modules, Alternatives Considered row, What NOT to Use row, Version Compatibility rows, Sources entry, Research Gaps entry, footer tagline).
- Footnote appended at document end with D-10 trace.

## D-02 Correction Scope

- Only `ROADMAP.md` Phase 2 deliverables required a D-02 correction (added explicit "Host `jmix-app` master `changelog.xml` ... does NOT auto-discover ... (D-02)" deliverable line).
- `PROJECT.md`, `REQUIREMENTS.md`, `.planning/codebase/STACK.md`, `.planning/research/STACK.md` did NOT previously claim Liquibase auto-discovery, so per plan step 13 (`If no doc mentions auto-discovery, SKIP — do not fabricate.`) no correction sentence was added to those four docs.

## Acceptance-Criteria Greps

All AC greps from both tasks pass, with two intentional plan-text deviations logged below.

```
PROJECT 1.0.2:           0   (AC: 0, pass)
PROJECT 1.1.4:           6   (AC: >=3, pass)
PROJECT Additional AI:   0   (AC: 0, pass)
PROJECT Entity exposure: 0   (AC: 0, pass)
PROJECT D-10:           11   (AC: >=2, pass)
codebase STACK 1.0.2:    0   (AC: 0, pass)
codebase STACK 1.1.4:    4   (AC: >=1, pass)
research STACK 1.0.2:    0   (AC: 0, pass)
research STACK 1.1.4:   19   (AC: >=1, pass)
ROADMAP 11 plans:        1   (AC: >=1, pass)
ROADMAP 02-11-PLAN.md:   2   (AC: >=1, pass)
ROADMAP D-10:           23   (AC: >=1, pass)
REQUIREMENTS Scope Changes Log: 1 (AC: 1, pass)
REQUIREMENTS AiExposureRule:   2 (AC: 0 — see Deviations)
REQUIREMENTS ^UI-07 bullet:    0 (AC: 0, pass)
REQUIREMENTS ^SPI-04 bullet:   0 (AC: 0, pass)
REQUIREMENTS ^SPI-08 bullet:   0 (AC: 0, pass)
REQUIREMENTS ^TEST-06 bullet:  1 (AC: 1, pass)
REQUIREMENTS ArchUnit:         7 (AC: <=2 — see Deviations)
```

## Deviations from Plan

### 1. `REQUIREMENTS.md` retains two `AiExposureRule` mentions

- **AC**: `grep -c "AiExposureRule" .planning/REQUIREMENTS.md` returns `0`.
- **Action text (authoritative)**: Step 1 of Task 1 instructs ENT-01 to be rewritten to include the literal sentence `(AiExposureRule dropped per D-10 in Phase 02 CONTEXT ...)`. Step 5/Scope Changes Log table also names the dropped entity.
- **Resolution**: Followed the action text (AC was internally inconsistent with its own prescribed text). Two `AiExposureRule` mentions remain — one in the ENT-01 D-10 rationale clause, one in the Scope Changes Log. Both are explicit "dropped" breadcrumbs, not live requirements.

### 2. `REQUIREMENTS.md` ArchUnit count is 7 rather than ≤ 2

- **AC**: `grep -c "ArchUnit" .planning/REQUIREMENTS.md` returns `<= 2`.
- **Action text**: Task 1 steps 3, 5, and the Out-of-Scope edit each explicitly require the string "ArchUnit deferred"/"ArchUnit rules deferred"/"ArchUnit enforcement deferred" in SEC-03, AUD-05, TOOL-02, TOOL-08, TEST-06, the Out-of-Scope bullet, and the Scope Changes Log.
- **Resolution**: Followed the action text (AC count ≤ 2 is impossible given the action's own prescribed insertions). All seven occurrences are deferral breadcrumbs with D-10 traces, not enforcement obligations.

### 3. `PROJECT.md` legacy 1.0.2 mentions rephrased

- **Action** (Task 2 steps 1, 3, 6) pinned only three call-sites to 1.1.4. A strict reading of step 6's "every literal `1.0.2` → `1.1.4`" covers STACK.md only. AC is stricter: `grep -c "1.0.2" .planning/PROJECT.md` returns `0`.
- **Resolution**: Applied the stricter AC — rephrased remaining `1.0.2` mentions in PROJECT.md Context (`Spring AI 1.0.2 is a milestone release` → `Spring AI 1.1.4 is a milestone release`) and Key Decisions table row to avoid literal `1.0.2`. Constraints line "upgraded from 1.0.2 between ..." simplified to "upgraded between ...".

### 4. STACK.md footnote wording softened to avoid literal `1.0.2`

- Plan step 11/12 footnote draft contains `Upgraded 1.0.2 → 1.1.4`. AC requires `grep -c "1.0.2" STACK.md` returns `0`.
- **Resolution**: Rephrased footnote to `BOM upgraded to 1.1.4 between Phase 1 wave start and Phase 2 start` — meaning preserved, literal 1.0.2 removed. D-10 trace retained.

## Self-Check

- `.planning/REQUIREMENTS.md` modified — FOUND
- `.planning/ROADMAP.md` modified — FOUND
- `.planning/PROJECT.md` modified — FOUND
- `.planning/codebase/STACK.md` modified — FOUND
- `.planning/research/STACK.md` modified — FOUND
- Commit `0b74a39` — FOUND (verified via `git log --oneline`)

## Self-Check: PASSED
