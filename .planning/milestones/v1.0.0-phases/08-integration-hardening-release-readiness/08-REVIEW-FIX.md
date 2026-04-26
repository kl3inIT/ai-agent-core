---
phase: 08-integration-hardening-release-readiness
fixed_at: 2026-04-26T08:15:00Z
review_path: .planning/phases/08-integration-hardening-release-readiness/08-REVIEW.md
iteration: 1
findings_in_scope: 9
fixed: 9
skipped: 0
status: all_fixed
---

# Phase 8: Code Review Fix Report

**Fixed at:** 2026-04-26T08:15:00Z
**Source review:** .planning/phases/08-integration-hardening-release-readiness/08-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope (critical + warning): 9
- Fixed: 9
- Skipped: 0
- Out of scope (info, not attempted): 1 (IN-001)

All nine warning-severity findings were fixed. Several touch logic paths (WR-001
counter wiring, WR-007 audit-store query, WR-008 schema-filter assertion) — these
are flagged below as "fixed: requires human verification" so a developer can
confirm the new behavior under a real run before the phase advances.

## Fixed Issues

### WR-001: Query-count tests do not reset the counter they install

**Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/QueryCountingDataSourceConfiguration.java`
**Commit:** cff9912
**Status:** fixed: requires human verification (counter-wiring change — needs a real test run to confirm steady-state SELECT counts are now correctly scoped)

Switched the proxy from `.countQuery(new SingleQueryCountHolder())` to the
default `.countQuery()`, which uses the thread-local `QueryCountHolder` that
`QueryCountHolder.clear()` actually resets. Removed the now-unused
`SingleQueryCountHolder` import.

### WR-002: PR CI does not run the integration tests it claims to gate

**Files modified:** `.github/workflows/ai-agent-ci.yml`
**Commit:** f9a41e0
**Applied fix:** Changed the PR gate from `./gradlew :ai-agent:ai-agent:test` to `./gradlew :ai-agent:ai-agent:check`. The `check` task already pulls in `integrationTest` via the `tasks.named('check') { dependsOn integrationTest }` wiring in `ai-agent.gradle` when Docker is available — and GitHub `ubuntu-latest` runners do ship with Docker, so the `@Tag('rag-it')` pgvector contracts now run on every PR. Header comment also updated to reflect the new behavior.

### WR-003: Publish tag trigger uses a regex-shaped glob pattern

**Files modified:** `.github/workflows/ai-agent-publish.yml`
**Commit:** 2a7246d
**Applied fix:** Replaced the regex-shaped tag pattern `v[0-9]+.[0-9]+.[0-9]+*` with a broad GitHub-Actions glob `v*.*.*`. Strict SemVer rejection is performed inside the workflow during the "Resolve effective version" step (see WR-004).

### WR-004: Manual publish version is unvalidated and interpolated into shell commands

**Files modified:** `.github/workflows/ai-agent-publish.yml`
**Commit:** 2a7246d (combined with WR-003)
**Applied fix:** `version_override` is now passed via `env: VERSION_OVERRIDE` rather than inlined into the shell script body. After resolution, the version is matched against `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$` and the workflow fails fast on mismatch BEFORE writing to `$GITHUB_OUTPUT`. The Test and Publish steps now read the validated version from `env: PUBLISH_VERSION` and quote `"-Pversion=${PUBLISH_VERSION}"` in the Gradle invocations.

### WR-005: Nexus publishing explicitly allows insecure HTTP

**Files modified:** `ai-agent/build.gradle`
**Commit:** 2fb9dd7
**Applied fix:** Removed `allowInsecureProtocol = true`. The chosen `targetUrl` is now validated against an `https://` prefix and `throw new GradleException(...)` on mismatch, so any future override of `nexusReleaseUrl` / `nexusSnapshotUrl` to a plain-HTTP endpoint fails loudly rather than silently shipping credentials over the wire.

### WR-006: Live workflow can override optional defaults with empty env vars

**Files modified:** `.github/workflows/ai-agent-live.yml`
**Commit:** 2fe562e
**Applied fix:** Substituted explicit defaults for the optional secrets: `OPENROUTER_BASE_URL: ${{ secrets.OPENROUTER_BASE_URL || 'https://openrouter.ai/api/v1' }}` and `OPENROUTER_MODEL: ${{ secrets.OPENROUTER_MODEL || 'openai/gpt-4o-mini' }}`. An unset secret no longer exports an empty env var that would override the test-app.properties defaults with a blank value.

### WR-007: Live golden suite ignores expected tool calls

**Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java`
**Commit:** 91a72ec
**Status:** fixed: requires human verification (live-tier suite is gated on `OPENROUTER_API_KEY`; needs a manual-dispatch live run to confirm the audit-store assertion works in the real environment)

Added a post-response audit-store check guarded by `question.expectedTools()` non-empty. Wraps `dataManager.load(AiAuditEvent.class).query("... where runId = :rid and kind = :k")` with `AuditKind.TOOL` inside `systemAuthenticator.withSystem(...)` so the audit query is not blocked by the runtime user role, and asserts the captured `eventName` set `containsAll(question.expectedTools())`. Also asserts `response.runId()` is non-null (precondition for the lookup). Imports added: `AiAuditEvent`, `AuditKind`, `DataManager`, `SystemAuthenticator`.

### WR-008: Attribute-denial assertion is currently vacuous

**Files modified:**
- `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java`

**Commit:** f7cbdf3
**Status:** fixed: requires human verification (Jmix attribute-policy precedence + EclipseLink `@Lob` interaction in metamodel iteration is subtle — needs a real test run to confirm dave's readable-attribute set excludes both `userUsername` and `argumentsJson`)

Added a second resource role `AuditReadNoSensitiveAttrsRole` (code `audit-read-no-sensitive-attrs`) that grants `EntityPolicyAction.READ` on `AiAuditEvent` plus `EntityAttributePolicyAction.VIEW` on a curated attribute list that EXPLICITLY EXCLUDES `userUsername` and `argumentsJson`. (Jmix attribute policies are positive-grant only — there is no `DENY` action in `EntityAttributePolicyAction`; absence of VIEW is denial.) Registered new persona `dave` with this role and added `dave_filteredSchema_includesEntity_butExcludesProtectedAttributes` to `FilteredSchemaAndExecutionDenialTest` which asserts the entity IS present in the readable schema AND the protected attributes are absent from its readable-attribute set.

### WR-009: Live test logs API key fragments

**Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java`
**Commit:** 91a72ec (combined with WR-007 — same file)
**Applied fix:** Replaced the prefix/suffix dump (`key.substring(0,4) + "..." + key.substring(key.length()-4)`) in `@BeforeAll announceLiveSuiteState` with `"ENABLED — key present, length=" + key.length()`. Matches the workflow preflight format and removes the partial-secret-fingerprint leakage path.

## Out-of-Scope (Info severity — not fixed in this iteration)

### IN-001: Stale Javadoc link and unused test field

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java:38`
**Reason:** Info-severity finding; current `fix_scope` is `critical_warning`. Recommend folding into a follow-up cleanup pass — the `metadata` field could be removed and the Javadoc link `com.vn.agent.tools.FilterNode` corrected to `com.vn.agent.filter.FilterNode`.

---

_Fixed: 2026-04-26T08:15:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
