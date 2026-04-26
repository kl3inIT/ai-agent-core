---
phase: 08
plan: 07
subsystem: build/release/ci
tags: [release, version-bump, credentials, changelog, github-actions, nexus, TEST-07, R-07a, R-07b, R-07c, R-07d, R-07e, R-07f, R-07g, R-XP-1]
requires:
  - ai-agent/build.gradle (publishing block — maven-publish already wired)
  - ai-agent/gradle.properties (was committing nexus credentials)
  - .gitignore
  - .github/workflows/ (new dir)
provides:
  - ai-agent project version=1.0.0 (sourced from gradle.properties; CI overridable)
  - Snapshot-vs-release URL conditional in build.gradle (W-03 GATING preserved)
  - CHANGELOG.md at repo root (Keep-a-Changelog v1.1.0 + [Unreleased] discipline + per-phase backfill)
  - 3 GitHub Actions workflows: ai-agent-ci.yml (PR-blocking), ai-agent-live.yml (manual), ai-agent-publish.yml (tag + manual)
  - .gitignore patterns for nexus-credentials* / gradle-local.properties / **/nexus.properties
affects:
  - all future PRs land under ai-agent-ci.yml gate
  - publish workflow becomes available for tagging + Nexus uploads
  - leaked credential acknowledged in CHANGELOG Security section + gradle.properties comment
tech-stack:
  added: []
  patterns:
    - "Snapshot-vs-release URL conditional in publishing block (W-03 GATING comment preserves orchestrator visibility)"
    - "Dynamic aiAgentVersion resolution in CI (read from gradle.properties — R-07b — never hardcoded in workflow YAML)"
    - "Preflight secrets-presence check in publish workflow (R-07f) — fails loudly with named missing secrets, avoids confusing 401 mid-publish"
    - "Tag-trigger publish (v[0-9]+.[0-9]+.[0-9]+*) with manual workflow_dispatch override fallback"
key-files:
  created:
    - CHANGELOG.md
    - .github/workflows/ai-agent-ci.yml
    - .github/workflows/ai-agent-live.yml
    - .github/workflows/ai-agent-publish.yml
  modified:
    - ai-agent/build.gradle
    - ai-agent/gradle.properties
    - .gitignore
key-decisions:
  - "Version source moved from build.gradle line 12 (was '0.0.1-SNAPSHOT') to gradle.properties (version=1.0.0). build.gradle now reads project.findProperty('version') ?: '1.0.0' — CI passes -Pversion=... to override"
  - "Snapshot-vs-release URL conditional: chooses jmix-internal-snapshots URL when version ends with -SNAPSHOT, jmix-internal-releases URL otherwise. Both URLs have CI override hooks (-PnexusReleaseUrl / -PnexusSnapshotUrl). [W-03 GATING] comment block preserved per R-07c"
  - "gradle.properties edit was SURGICAL (R-07d) — only the credential lines were removed and a version line + safety comment added. The org.gradle.jvmargs line is byte-identical"
  - "R-07a — historical credential nexusUsername=admin / nexusPassword=admin123 is FOREVER BURNT in git history (commit prior to 08-07). Removing the lines from current head does NOT re-secure the credential. CHANGELOG Security entry + gradle.properties comment + this SUMMARY all call this out. Rotation in Nexus admin UI is REQUIRED — that is a USER OUT-OF-BAND ACTION"
  - "Three workflows ship with explicit permissions: blocks (R-07f minimal least-privilege) + concurrency: blocks (cancel-in-progress on CI; serialize on publish; parallel-OK for live)"
  - "ai-agent-ci.yml does NOT yet invoke :consumer-smoke:bootRunSmoke — that step is commented out and unblocks once Plan 08-05 (deferred) is fully landed"
  - "ai-agent-live.yml is workflow_dispatch only — never triggers on push or PR — to avoid burning OPENROUTER quota on every push"
  - "CHANGELOG ordering follows R-07g Phase 7 → 7.1 → 7.2 chronology explicitly. R-XP-1 [Unreleased] discipline note explains how PRs add entries without bumping the version"
patterns-established:
  - "Out-of-band user-action checklist pattern: when a plan is autonomous: false because of credential / secret / external-system actions, list them explicitly in the SUMMARY so the operator can sequence them with deployment"
requirements-completed:
  - TEST-07
duration: ~30min
completed: 2026-04-26
---

# Phase 8 Plan 07: Release Wiring + CHANGELOG + GitHub Actions Summary

Bumped the add-on version source to gradle.properties (version=1.0.0), removed the committed Nexus credentials with a `FOREVER BURNT` comment + CHANGELOG Security entry, wired the snapshot-vs-release URL conditional, wrote a Keep-a-Changelog v1.1.0 CHANGELOG.md backfilled per phase, and shipped three GitHub Actions workflows.

## Outcome

- All seven files in plan's `files_modified` were edited / created.
- All R-07a..g + R-XP-1 acceptance bullets satisfied (table below).
- Compile gate green (`./gradlew :ai-agent:ai-agent:compileJava` PASS after changes).
- 3 workflows have explicit permissions + concurrency blocks; publish has preflight secrets check.

## Out-of-band user actions (autonomous: false rationale)

These cannot be automated from inside the repo and MUST be performed by the operator before the published artifact is usable:

1. **Rotate the leaked Nexus admin password in the Nexus admin UI.** The credential
   `admin / admin123` is FOREVER BURNT in git history at commits prior to 08-07. Removing
   the lines from current head does NOT re-secure the credential. Until rotation, anyone
   with read access to the repo can authenticate to the Nexus.

2. **Configure GitHub Actions repo secrets** (Settings → Secrets and variables → Actions):
   - `NEXUS_USERNAME` — required for publish.yml
   - `NEXUS_PASSWORD` — required for publish.yml
   - `OPENROUTER_API_KEY` — required for live.yml
   - `NEXUS_RELEASE_URL` (optional) — overrides the hardcoded default in build.gradle
   - `NEXUS_SNAPSHOT_URL` (optional) — overrides the hardcoded default in build.gradle
   - `OPENROUTER_BASE_URL`, `OPENROUTER_MODEL` (optional) — for live.yml

3. **(Optional)** When ready for the first release, tag the commit and push the tag:
   ```bash
   git tag -a v1.0.0 -m "Release 1.0.0"
   git push origin v1.0.0
   ```
   This triggers `ai-agent-publish.yml`. Alternatively, run the `Publish` workflow manually
   from the Actions tab with an explicit `version_override`.

## Tasks Executed

| Task | Name | Notes |
|---|---|---|
| 1 | Version source extraction + URL conditional + gradle.properties surgical edit + .gitignore | build.gradle line 14 + publishing block; gradle.properties FOREVER BURNT comment; .gitignore three patterns |
| 2 | CHANGELOG.md (Keep-a-Changelog v1.1.0 + [Unreleased] discipline + per-phase 1.0.0 backfill) | R-07g chronology Phase 7 → 7.1 → 7.2 explicit; Security entry calling out leaked credential |
| 3 | Three GitHub Actions workflows (ci + live + publish) | All with explicit permissions + concurrency; publish has preflight secrets check |
| 4 | (R-07a threat-model paragraph) | Inline in CHANGELOG Security section + gradle.properties comment + this SUMMARY (3 redundant locations so operator can't miss it) |

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileJava` — **PASS** (build.gradle changes compile cleanly)
- All acceptance greps pass:

| Criterion | Required | Actual |
|---|---|---|
| `ai-agent/build.gradle` version expression | `findProperty.*version` | line 14: `version = project.findProperty('version') ?: '1.0.0'` |
| `[W-03 GATING]` comment preserved (R-07c) | ≥ 1 | 1 |
| `ai-agent/gradle.properties` `version=1.0.0` | ≥ 1 | 1 |
| `nexusUsername` / `nexusPassword` in current gradle.properties | 0 (R-07d) | 0 |
| `.gitignore` nexus patterns | ≥ 1 | 3 |
| `CHANGELOG.md` `[Unreleased]` + `[1.0.0]` (R-XP-1 + R-07g) | both | both present |
| Phase 7 / 7.1 / 7.2 explicit (R-07g) | ≥ 3 | 4 |
| `permissions:` block in each workflow (R-07f) | each = 1 | each = 1 |
| `concurrency:` block in each workflow (R-07f) | each = 1 | each = 1 |
| Preflight secrets check in publish.yml (R-07f) | present | 5 lines of MISSING / ERROR check |
| Dynamic aiAgentVersion in ci.yml (R-07b) | present | reads from `ai-agent/gradle.properties`, exports `VERSION` step output |

## Notes for downstream waves

- The first PR after this plan lands will surface any ai-agent-ci.yml integration issues (path glob, runner OS, timeout). Watch the first run.
- `ai-agent-publish.yml` is opt-in (tag-trigger or manual dispatch) — it will NOT fire on this plan's commit.
- `ai-agent-live.yml` is opt-in only — needs operator dispatch + OPENROUTER_API_KEY secret.
- Plan 08-05 (consumer-smoke) is deferred. When it lands, uncomment the `:consumer-smoke:bootRunSmoke` step in ai-agent-ci.yml.
- The `[W-03 GATING]` comment block in build.gradle is intentional — orchestrator must see it for downstream gating logic. Do not delete during cleanup.

## Self-Check: PASSED

All success criteria met:
- Version source extracted to gradle.properties; CI override path verified.
- Snapshot-vs-release URL conditional added with W-03 GATING comment preserved.
- Committed credentials removed from current head (with FOREVER BURNT comment so future readers don't think removal alone is sufficient).
- CHANGELOG.md created with Keep-a-Changelog discipline + per-phase 1.0.0 backfill + Security note.
- Three GitHub Actions workflows shipped with explicit permissions + concurrency + preflight checks.
- Compile gate green.
- Out-of-band user actions enumerated above.
