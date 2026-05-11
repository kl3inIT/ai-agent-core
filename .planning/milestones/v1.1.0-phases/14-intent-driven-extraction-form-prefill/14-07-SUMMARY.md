---
phase: 14-intent-driven-extraction-form-prefill
plan: 07
subsystem: host-reference
tags: [host-reference, spring-ai, customer, extraction, tests]

requires:
  - phase: 14-02
    provides: IntentExtractor SPI and MetaClass schema synthesis
  - phase: 14-03
    provides: ExtractionService and prepare_form_draft tool contract
  - phase: 14-04
    provides: named-intent chat routing and tool gating
  - phase: 14-05
    provides: draft apply navigation handler
  - phase: 14-06
    provides: intent selector and confirm-row chat UI
provides:
  - Host-side CustomerDraftIntentExtractor for jmixapp_Customer
  - Explicit customer-reference enablement flag and bilingual intent copy
  - Host tests for schema narrowing, zero-file input, media forwarding, validation failure, and draft workflow
  - Source scan proving ai-agent core remains free of Customer imports
affects: [phase-14, jmix-app, customer-reference-intent, extraction-e2e]

tech-stack:
  added: []
  patterns:
    - Host-owned IntentExtractor bean behind @ConditionalOnProperty
    - Spring AI Map structured output narrowed through Jackson and Bean Validation
    - Host workflow tests over generic extraction service plus Jmix detail navigation handler

key-files:
  created:
    - jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java
    - jmix-app/src/test/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractorTest.java
    - jmix-app/src/test/java/com/vn/jmixapp/ai/CustomerDraftWorkflowTest.java
  modified:
    - jmix-app/src/main/resources/application.properties
    - jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties
    - jmix-app/src/main/resources/com/vn/jmixapp/messages_vi.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "CustomerDraftIntentExtractor stays entirely in jmix-app and is enabled through ai-agent.intents.customer-reference.enabled=true."
  - "The reference extractor asks Spring AI for Map<String,Object>, validates only schema-approved keys, then narrows through Jackson to Customer."
  - "Validation and parse failures throw ExtractionSchemaException with stable codes/counts only; raw model values are not included in exception text."
  - "Workflow coverage uses mocked Spring AI and Jmix navigation/service seams so tests do not require a live model."

patterns-established:
  - "Reference intents belong in host apps, not ai-agent core; keep host entity imports out of add-on main sources."
  - "Use repository-root discovery in source-scan tests so they pass under both root and module Gradle working directories."

requirements-completed:
  - EXTRACT-02
  - EXTRACT-03
  - EXTRACT-04
  - EXTRACT-05
  - EXTRACT-06
  - EXTRACT-07
  - EXTRACT-08
  - EXTRACT-09

duration: 46min
completed: 2026-05-08
---

# Phase 14 Plan 07: Customer Reference Intent Summary

**Host-side Customer extraction intent proving the generic draft workflow against jmixapp_Customer**

## Performance

- **Duration:** 46 min
- **Started:** 2026-05-08T04:38:17Z
- **Completed:** 2026-05-08T05:24:38Z
- **Tasks:** 3/3
- **Files modified:** 8

## Accomplishments

- Added `CustomerDraftIntentExtractor` in `jmix-app/src/main/java/com/vn/jmixapp/ai`, not in `ai-agent` core.
- Registered the reference intent behind `ai-agent.intents.customer-reference.enabled=true` with `matchIfMissing=true`.
- Locked intent metadata to `customer-from-pdf`, `jmixapp_Customer`, and the existing `Customer.class` detail-view target.
- Built prompt-only strict JSON extraction using `MetaClassDtoSynthesizer` schema text and Spring AI `ParameterizedTypeReference<Map<String,Object>>`.
- Narrowed model output to allowed schema fields only, then converted to `Customer` and validated through Bean Validation.
- Added bilingual `chatView.intent.customer-from-pdf.*` keys in both add-on and host message bundles.
- Added host tests for field mapping, schema field limits, zero-file extraction, media forwarding, validation failures, core-boundary source scan, and draft apply workflow.

## Task Commits

1. **Task 1: Implement CustomerDraftIntentExtractor in jmix-app** - `cc7563d` (feat)
2. **Task 2: Add reference intent configuration and message keys** - `03f5932` (feat)
3. **Task 3: Add host integration and workflow tests** - `67dacbf` (test)

## Files Created/Modified

- `CustomerDraftIntentExtractor.java` - Host reference `IntentExtractor<Customer>` using Spring AI Map output, prompt schema text, Jackson narrowing, and validation-safe errors.
- `application.properties` - Adds explicit `ai-agent.intents.customer-reference.enabled=true` for operator visibility.
- `messages_en.properties`, `messages_vi.properties` - Adds `customer-from-pdf` label and description in host and add-on bundles.
- `CustomerDraftIntentExtractorTest.java` - Unit tests for structured output, prompt schema, zero-file input, media forwarding, and validation failures.
- `CustomerDraftWorkflowTest.java` - Host workflow/source tests proving core boundary, primary detail view target, prefill, save, and draft deletion behavior.

## Decisions Made

- Kept the reference extractor host-owned per D-20. A source scan verifies no `com.vn.jmixapp.entity.Customer` import exists under `ai-agent/ai-agent/src/main/java`.
- Used Map structured output before narrowing to `Customer`, preserving the Phase 14 prompt-only strict-output decision.
- Treated extra keys as schema validation failure, so `recommendedProducts` and other undeclared fields cannot flow into a draft.
- Used no-daemon Gradle with lower JVM memory for final verification after a Gradle daemon native-memory crash.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Test Bug] Made source-scan paths Gradle-working-directory stable**
- **Found during:** Task 3 verification
- **Issue:** `CustomerDraftWorkflowTest` read repository-relative source paths directly. Module-scoped `:jmix-app:test` executes from a module working directory, so the source scan failed with `NoSuchFileException`.
- **Fix:** Added repository-root discovery that walks upward until both `ai-agent` and `jmix-app` directories are present.
- **Files modified:** `CustomerDraftWorkflowTest.java`
- **Verification:** `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraft*"` passed.
- **Committed in:** `67dacbf`

**2. [Rule 3 - Verification Environment] Re-ran tests with smaller no-daemon Gradle JVM**
- **Found during:** Task 3 verification
- **Issue:** The first focused test run produced `hs_err_pid34732.log` / `replay_pid34732.log` from native memory exhaustion in the Gradle JVM, not from a test assertion.
- **Fix:** Removed generated crash logs and reran focused verification with `GRADLE_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=512m' ./gradlew --no-daemon`.
- **Files modified:** None in product code.
- **Verification:** Focused host tests passed twice after the memory-constrained rerun.
- **Committed in:** N/A

**Total deviations:** 2 auto-fixed (1 test harness, 1 verification environment)
**Impact on plan:** No runtime contract changes. The host reference intent and workflow tests meet the original plan.

## Issues Encountered

- Gradle daemon native-memory exhaustion generated JVM crash logs during one `:jmix-app:test` run. The logs were removed, and the same focused test set passed under a smaller no-daemon JVM.
- JetBrains reported remaining fixture-style warnings that private mock fields in tests could be local variables. These were triaged as non-blocking test-fixture style warnings.

## Verification Results

- `./gradlew --no-daemon :jmix-app:compileJava :jmix-app:test --tests "*CustomerDraft*"` with `GRADLE_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=512m` - PASS
- `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraftIntentExtractorTest" --tests "*CustomerDraftWorkflowTest"` with the same `GRADLE_OPTS` - PASS
- Source scan: no `com.vn.jmixapp.entity.Customer` import under `ai-agent/ai-agent/src/main/java` - PASS
- JetBrains file-problem checks - PASS for `CustomerDraftIntentExtractor.java`; test files have only non-blocking local-variable fixture warnings.

## Known Stubs

None. The reference tests use mocked Spring AI and Jmix seams intentionally so the host workflow is covered without a live model call.

## Threat Flags

None. The host extractor rejects extra keys, avoids raw value leakage in schema failures, and remains disabled by configuration if operators turn off `ai-agent.intents.customer-reference.enabled`.

## User Setup Required

None. The reference intent is enabled by default in the sample app through `application.properties`.

## Next Phase Readiness

Ready for Plan 14-08. The full generic-to-host workflow now has a real Customer reference intent and focused host tests.

## Self-Check: PASSED

- Summary file exists.
- Created test files exist.
- All three task commits are present in git history.
- Focused host tests and core-boundary scan pass.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
