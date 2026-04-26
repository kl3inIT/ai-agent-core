---
status: issues_found
phase: 08-integration-hardening-release-readiness
phase_name: Integration Hardening & Release Readiness
reviewed: 2026-04-26T07:31:07Z
depth: standard
files_reviewed: 22
files_reviewed_list:
  - .github/workflows/ai-agent-ci.yml
  - .github/workflows/ai-agent-live.yml
  - .github/workflows/ai-agent-publish.yml
  - .gitignore
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/FindRecordsLimitCapTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/QueryCountingDataSourceConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/CrossUserConversationAccessTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/RagRoleFilterNegativeTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties
  - ai-agent/ai-agent/src/test/resources/golden-questions.yaml
  - ai-agent/build.gradle
  - ai-agent/gradle.properties
  - ai-agent/README.md
  - CHANGELOG.md
  - CLAUDE.md
finding_counts:
  critical: 0
  warning: 9
  info: 1
  total: 10
findings:
  critical: 0
  warning: 9
  info: 1
  total: 10
---

# Phase 8: Code Review Report

**Reviewed:** 2026-04-26T07:31:07Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** issues_found

## Summary

Reviewed the explicit Phase 8 file list: GitHub workflows, Gradle release/test wiring, new hardening tests, live golden fixtures, README, changelog, and project guidance. No critical production-code vulnerability was found, but the release gate and several test contracts have meaningful false-green or release-failure risks.

## Warnings

### WR-001: Query-count tests do not reset the counter they install

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/QueryCountingDataSourceConfiguration.java:51`

**Issue:** The proxy is configured with `new SingleQueryCountHolder()`, which accumulates counts in its own holder. `ToolQueryCountBaselineTest` resets only `QueryCountHolder.clear()` before measurement, so the warm-up invocation and prior tests can remain in the measured value. The per-call baselines and N-scaling detector can therefore report false positives or false negatives.

**Fix:** Use datasource-proxy's default thread-local holder, or keep a shared `SingleQueryCountHolder` and clear that exact instance before each measurement.

```java
return ProxyDataSourceBuilder.create(delegate)
        .name(DATASOURCE_NAME)
        .countQuery()
        .build();
```

### WR-002: PR CI does not run the integration tests it claims to gate

**File:** `.github/workflows/ai-agent-ci.yml:55`

**Issue:** CI runs only `:ai-agent:ai-agent:test`, and that task excludes `@Tag("rag-it")` in `ai-agent/ai-agent/ai-agent.gradle:120`. The `integrationTest` task is wired into `check` only when Docker is available, but the workflow never runs `check` or `integrationTest`. This leaves the pgvector/RAG integration contracts out of the PR gate, while `CHANGELOG.md:126` says the PR-blocking workflow runs `test + integrationTest`.

**Fix:** Run `check` on the GitHub runner, or add an explicit integration step.

```yaml
- name: Compile + check (default + Docker-gated integration tier)
  run: ./gradlew :ai-agent:ai-agent:check --no-daemon
```

### WR-003: Publish tag trigger uses a regex-shaped glob pattern

**File:** `.github/workflows/ai-agent-publish.yml:10`

**Issue:** GitHub Actions tag filters are glob-style patterns, not SemVer regexes. The pattern `v[0-9]+.[0-9]+.[0-9]+*` is likely to miss normal tags such as `v1.0.0`, so release tags may not trigger publishing.

**Fix:** Use a broad glob trigger and validate SemVer in the workflow script.

```yaml
on:
  push:
    tags:
      - 'v*.*.*'
```

Then reject malformed versions before publishing.

### WR-004: Manual publish version is unvalidated and interpolated into shell commands

**File:** `.github/workflows/ai-agent-publish.yml:67`

**Issue:** `version_override` is inserted directly into the shell script, written to `$GITHUB_OUTPUT`, and later used unquoted in Gradle command lines at lines 84 and 92. A malformed manual input can corrupt the output file, break the command, or publish an invalid artifact version.

**Fix:** Pass the input via `env`, validate it with a strict version regex, and quote the version in Gradle invocations.

```yaml
env:
  VERSION_OVERRIDE: ${{ inputs.version_override }}
run: |
  OVERRIDE="$VERSION_OVERRIDE"
  if [ -n "$OVERRIDE" ] && [[ ! "$OVERRIDE" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "ERROR: invalid version_override: $OVERRIDE" >&2
    exit 1
  fi
```

### WR-005: Nexus publishing explicitly allows insecure HTTP

**File:** `ai-agent/build.gradle:84`

**Issue:** `allowInsecureProtocol = true` permits an overridden Nexus URL to use plain HTTP while sending `nexusUsername` and `nexusPassword`. The defaults are HTTPS, so this weakens the release path without a current need.

**Fix:** Remove the line and fail fast if an override is not HTTPS.

```groovy
def targetUrl = version.toString().endsWith('-SNAPSHOT') ? nexusSnapshotUrl : nexusReleaseUrl
if (!targetUrl.toString().startsWith('https://')) {
    throw new GradleException("Nexus publish URL must use HTTPS: ${targetUrl}")
}
url = targetUrl
```

### WR-006: Live workflow can override optional defaults with empty env vars

**File:** `.github/workflows/ai-agent-live.yml:52`

**Issue:** `OPENROUTER_BASE_URL` and `OPENROUTER_MODEL` are optional in the test properties, but the workflow always exports them from optional secrets. If a secret is not configured, the environment variable is still present as an empty value, which can override the defaults and make the live suite fail with blank provider settings.

**Fix:** Either omit optional env vars unless configured, or set explicit workflow defaults.

```yaml
OPENROUTER_BASE_URL: ${{ secrets.OPENROUTER_BASE_URL || 'https://openrouter.ai/api/v1' }}
OPENROUTER_MODEL: ${{ secrets.OPENROUTER_MODEL || 'openai/gpt-4o-mini' }}
```

### WR-007: Live golden suite ignores expected tool calls

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java:84`

**Issue:** The fixture declares `expectedTools` for tool-backed prompts, but the test asserts only semantic anchor text. A model can answer with the right words without invoking `list_entities`, `find_records`, or `count_records`, so the capability-coverage suite can go green without exercising the intended production tool path.

**Fix:** Use `response.runId()` to load `AiAuditEvent` rows with `kind = TOOL` and assert their `eventName` values include `question.expectedTools()` when that list is present.

### WR-008: Attribute-denial assertion is currently vacuous

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java:100`

**Issue:** The test first requires `ai_AiAuditEvent` to be absent from the readable schema, then looks for that same entity in the schema with `findFirst()`. On the passing path the optional is always empty, so protected attributes `userUsername` and `argumentsJson` are never actually checked.

**Fix:** Add a separate fixture role that grants entity read while denying selected attributes, then assert the entity is present and the protected attributes are absent from its readable attribute set.

### WR-009: Live test logs API key fragments

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticGoldenSuiteTest.java:60`

**Issue:** The live suite prints the first and last four characters of `OPENROUTER_API_KEY`. GitHub masks full secret values, but partial secret fragments are not reliably masked and can leak stable credential fingerprints into logs.

**Fix:** Print only whether the suite is enabled and the key length, matching the workflow preflight.

```java
String suffix = enabled
        ? "ENABLED - key present, length=" + key.length()
        : "SKIPPED - no OPENROUTER_API_KEY in environment";
```

## Info

### IN-001: Stale Javadoc link and unused test field

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java:38`

**Issue:** IntelliJ reports the Javadoc link to `com.vn.agent.tools.FilterNode` as unresolved; the real type is `com.vn.agent.filter.FilterNode`. The same file also autowires `Metadata metadata` at line 83 but never uses it.

**Fix:** Update the link to the correct package and remove the unused `metadata` field.

---

_Reviewed: 2026-04-26T07:31:07Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
