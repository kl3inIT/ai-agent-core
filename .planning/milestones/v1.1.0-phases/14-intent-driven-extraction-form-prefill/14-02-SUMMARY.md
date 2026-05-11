---
phase: 14-intent-driven-extraction-form-prefill
plan: 02
subsystem: ai-extraction
tags: [spi, schema, spring-ai, access-control, jmix]

requires:
  - phase: 14-intent-driven-extraction-form-prefill/14-01
    provides: AiExtractionDraft persistence, ownership security, TTL cleanup, and config foundation
  - phase: 10-ai-specific-llm-exposure-policy
    provides: LlmExposurePolicy entity and attribute governance boundary
provides:
  - IntentExtractor<T> host SPI for named extraction intents
  - ExtractionInput and ExtractionResult DTO records for later tool/service plans
  - IntentRegistry with per-request exposure and create/read eligibility filtering
  - MetaClassDtoSynthesizer for prompt JSON schema text from Jmix metadata
affects: [phase-14, extraction-service, chat-intent-ui, prepare-form-draft]

tech-stack:
  added: []
  patterns:
    - Spring bean registry indexed by intentId with deterministic locale-label ordering
    - Jmix Metadata plus AccessManager plus LlmExposurePolicy filtering before LLM schema exposure
    - Prompt schema text instead of runtime DTO bytecode

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/IntentExtractor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionInput.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionResult.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentOption.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentRegistry.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/UnknownIntentException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/MetaClassDtoSynthesizer.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/IntentRegistryTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/MetaClassDtoSynthesizerTest.java
  modified: []

key-decisions:
  - "Structured-output target remains Map/prompt JSON schema: MetaClassDtoSynthesizer emits schema text only, not runtime DTO bytecode."
  - "IntentRegistry recalculates eligibility per request and filters named intents through LlmExposurePolicy plus Jmix create/read permission; Auto remains a UI option, not an extractor."
  - "Registry and schema contracts are covered with Mockito/Jackson unit tests instead of Spring Boot tests, avoiding the known shared Jmix boot-context blocker while still testing the planned behavior."

patterns-established:
  - "Intent labels resolve from chatView.intent.{intentId}.label with extractor label fallback, sorted by localized label then intentId."
  - "Extraction schemas exclude system/audit, primary-key, read-only, collection, relationship-many, computed-no-setter, unreadable, and non-modifiable attributes."
  - "To-one references are represented as JSON schema strings with format=uuid."

requirements-completed: [SPI-12, EXTRACT-02, EXTRACT-03, EXTRACT-05]

duration: ~15 min
completed: 2026-05-07
---

# Phase 14 Plan 02: SPI and Schema Synthesis Summary

**Intent extraction SPI, deterministic intent eligibility, and Jmix-metadata JSON schema synthesis for secure form-prefill extraction.**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-05-07T16:55:17Z
- **Completed:** 2026-05-07T17:10:36Z
- **Tasks:** 4
- **Files modified:** 9

## Accomplishments

- Added the `IntentExtractor<T>` SPI and extraction DTO records used by later extraction service and UI plans.
- Added `IntentRegistry` to index extractor beans by `intentId`, throw stable unknown-intent errors, and return only per-user eligible named intents.
- Added `MetaClassDtoSynthesizer` to emit parseable JSON schema text filtered by Phase 10 exposure and Jmix attribute write permission.
- Added focused unit coverage for registry sorting/filtering and schema inclusion/exclusion behavior.

## Task Commits

Each task was committed atomically:

1. **Task 1: Define IntentExtractor SPI and extraction DTO records** - `8781b8a` (feat)
2. **Task 2: Implement IntentRegistry with deterministic eligibility** - `88a57f6` (feat)
3. **Task 3: Implement MetaClassDtoSynthesizer** - `ecd5797` (feat)
4. **Task 4: Add SPI and schema unit tests** - `b182d61` (test)

**Plan metadata:** committed separately after state and roadmap updates.

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/IntentExtractor.java` - Host SPI for named extraction intent implementations.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionInput.java` - Intent invocation input with conversation, user message, task file ids, and Spring AI media.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionResult.java` - Draft result summary returned after extraction creates a draft.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentOption.java` - UI-facing intent option record, including the future Auto option shape.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentRegistry.java` - Per-request eligible intent lookup and deterministic label ordering.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/UnknownIntentException.java` - Stable missing-intent exception.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/MetaClassDtoSynthesizer.java` - Jmix metamodel to JSON schema prompt builder.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/IntentRegistryTest.java` - Registry sorting, unknown-intent, and exposure-denial tests.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/MetaClassDtoSynthesizerTest.java` - Schema parse and attribute filtering tests.

## Decisions Made

- Kept the structured-output target as prompt JSON schema text and `Map<String,Object>` for later plans; no runtime DTO bytecode is generated.
- Kept intent eligibility uncached globally because Jmix permissions, current locale, and LLM exposure can vary by request.
- Used isolated Mockito/Jackson unit tests for this plan's SPI/schema contracts. This avoids the known shared module Spring Boot context blocker while still proving the registry and schema behavior independently of LLM calls.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Mockito generic stubbing needed minor type-alignment fixes while building the tests; this was resolved before the Task 4 commit.
- JetBrains inspections reported expected "unused" warnings for newly introduced SPI methods and later-plan DTOs, plus defensive null guards. No actionable errors remained.

## User Setup Required

None - no external service configuration required.

## Verification

- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/spi/IntentExtractor.java' -Pattern 'interface IntentExtractor','intentId','targetType','extract').Count -ge 4"` - passed.
- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/IntentRegistry.java' -Pattern 'List<IntentExtractor','eligible','LlmExposurePolicy','Comparator','CurrentAuthentication|Locale').Count -ge 5"` - passed.
- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/MetaClassDtoSynthesizer.java' -Pattern 'EntityAttributeContext','canModify','format','uuid','payload|schema').Count -ge 5"` - passed.
- `./gradlew :ai-agent:ai-agent:compileJava` - passed.
- `./gradlew :ai-agent:ai-agent:test --tests "*IntentRegistryTest" --tests "*MetaClassDtoSynthesizerTest"` - passed.
- JetBrains file-problem checks on touched Java/test files - completed; remaining findings were expected unused-SPI/test-message-key/defensive-null warnings.

## Known Stubs

None.

## Threat Flags

None - the new SPI, registry, and schema surface match the plan threat model and add no endpoints, persistence, or auth paths.

## Next Phase Readiness

Plan 14-03 can build the extraction service and `prepare_form_draft` tool on top of `IntentRegistry`, `ExtractionInput`, and `MetaClassDtoSynthesizer`.

## Self-Check: PASSED

- Confirmed all 9 key files exist.
- Confirmed task commits `8781b8a`, `88a57f6`, `ecd5797`, and `b182d61` exist.
- Stub scan found only defensive null/list normalization checks, not UI-rendered placeholders or unwired mock data.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-07*
