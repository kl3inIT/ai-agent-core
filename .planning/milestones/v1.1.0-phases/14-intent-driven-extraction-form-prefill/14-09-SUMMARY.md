---
phase: 14-intent-driven-extraction-form-prefill
plan: 09
subsystem: ai-extraction
tags: [gap-closure, extraction-draft, source-faithfulness, chat-guard-ordering, api-key-env, jmix]

requires:
  - phase: 14-intent-driven-extraction-form-prefill
    provides: "Plans 14-01 through 14-08 extraction intent, draft, chat UI, and verification substrate"
provides:
  - "Live extraction draft reads reject missing, expired, and confirmed rows before apply/open"
  - "Streaming first-turn submits leave conversation creation to DefaultChatServiceImpl guard ordering"
  - "Draft payload JSON and success audit summaries are filtered to MetaClassDtoSynthesizer-approved attributes"
  - "Reference Customer extractor validates string values against user/document textual evidence"
  - "OpenRouter API key remains environment-backed; datasource and UI-login defaults stay in application.properties per user override"
affects: [phase-14-verification, extraction-draft, chat-ui, customer-reference-intent, local-configuration]

tech-stack:
  added: []
  patterns:
    - "Secured DataManager open-draft accessor for user-facing draft loads"
    - "RunContext extraction turn carries task-file source text alongside media"
    - "Pure JUnit/source tests for Phase 14 gap-closure regressions"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionDraftAccess.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionSourceText.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionDraftAccessTest.java
    - jmix-app/.env.example
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionInput.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java
    - jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java
    - jmix-app/src/main/resources/application.properties

key-decisions:
  - "Plan 14-09 stayed limited to BL-01 through BL-05 gap closure; no new AI tool, entity table, audit kind, Jmix view/menu, or exposure layer was introduced."
  - "Source-faithfulness is enforced only when textual evidence exists; image-only input preserves the existing prompt path because this plan does not add OCR."
  - "Serial Gradle workers are used for Phase 14 verification on this Windows environment to avoid the known native-memory/paging-file failure."
  - "After user correction, BL-01 was narrowed: do not externalize datasource or UI-login defaults; only OpenRouter API key belongs in env/.env."

patterns-established:
  - "Draft payload persistence filters through synthesized schema order before JSON serialization and audit summary creation."
  - "Task-file DocumentText is converted to ExtractionSourceText at service/RunContext boundaries rather than reparsed from prompts."
  - "Committed local datasource/UI defaults are left as-is by project preference; `.env.example` documents only `OPENROUTER_API_KEY`."

requirements-completed:
  - EXTRACT-01
  - EXTRACT-02
  - EXTRACT-04
  - EXTRACT-05
  - EXTRACT-07
  - EXTRACT-08
  - EXTRACT-09
  - EXTRACT-10
  - ENT-08
  - SPI-12
  - TEST-15
  - SEC-06

duration: ~1h 10m
completed: 2026-05-08
---

# Phase 14 Plan 09: Gap Closure Summary

**Extraction draft gap closure for stale draft rejection, guard-ordered streaming, schema-filtered payloads, source-faithful Customer extraction, and API-key env hygiene.**

## Performance

- **Duration:** ~1h 10m
- **Started:** 2026-05-08T09:09:57Z
- **Completed:** 2026-05-08T10:17:56Z
- **Tasks:** 6/6
- **Files changed:** 25

## Accomplishments

- **BL-02 closed by Task 1:** `DraftLoader` and `OpenFormWithDraftHandler` now use `ExtractionDraftAccess.loadOpenDraft(...)`, whose secured DataManager predicate requires `e.id = :draftId and e.expiresAt > :now and e.confirmed = false`.
- **BL-03 closed by Task 2:** `ChatPanelFragment.onSubmit(...)` passes the nullable current conversation id into `chatService.stream(...)`; service guards now run before first-turn conversation creation.
- **BL-04 closed by Task 3:** `ExtractionService` builds the current schema with `schemaSynthesizer.buildSchema(metaClass)` and writes draft payload JSON/audit summaries only from `filterPayloadToSchema(...)`.
- **BL-05 closed by Task 4:** `ExtractionInput` carries `ExtractionSourceText`, and `CustomerDraftIntentExtractor.assertSourceFaithful(...)` rejects unsupported string values before returning a Customer payload.
- **BL-01 narrowed by user correction after Task 5:** datasource and UI-login defaults remain in `application.properties`; `.env.example` now documents only `OPENROUTER_API_KEY`, and no database/UI-default env migration is retained.

## Task Commits

1. **Task 1: Centralize live-draft loading and reject expired or confirmed rows** - `e9fee23` (fix)
2. **Task 2: Make first-turn streaming conversation creation service-owned** - `4d2bf95` (fix)
3. **Task 3: Persist only schema-approved draft payload attributes** - `53e4657` (fix)
4. **Task 4: Enforce source faithfulness on the reference Customer intent** - `8946976` (fix)
5. **Task 5: API-key env hygiene only** - `6a96571` was superseded by a user-directed correction that restores datasource/UI defaults and keeps only `OPENROUTER_API_KEY` in `.env.example`.
6. **Task 6: Run gap-closure regression matrix and write summary** - summary/state commit follows this file.

## Verification

- PASS: `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ExtractionDraftAccessTest" --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest" --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest"` during Task 1 scope, plus final matrix below.
- PASS: `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraftWorkflowTest"` during Task 1 scope.
- PASS: `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ChatPanelFragmentConversationIdTest" --tests "*IntentCardRowTest" --tests "*DefaultChatServiceIntentRoutingTest"`.
- PASS: `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ExtractionServiceTest"`.
- PASS: `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraftIntentExtractorTest"` after serial rerun.
- PASS: `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ExtractionToolBridgeTest" --tests "*DefaultChatServiceIntentRoutingTest" --tests "*ExtractionEvaluationContractTest"`.
- PASS: final targeted gap matrix:
  `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test --tests "*ExtractionDraftAccessTest" --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest" --tests "*ExtractionServiceTest" --tests "*ExtractionToolBridgeTest" --tests "*ChatPanelFragmentConversationIdTest" --tests "*IntentCardRowTest" --tests "*DefaultChatServiceIntentRoutingTest" --tests "*ExtractionEvaluationContractTest"`.
- PASS: final targeted jmix-app matrix:
  `./gradlew --no-daemon --max-workers=1 :jmix-app:test --tests "*CustomerDraftIntentExtractorTest" --tests "*CustomerDraftWorkflowTest"`.
- PASS: broader ai-agent confidence:
  `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test`.
- PASS: broader CustomerDraft confidence:
  `./gradlew --no-daemon --max-workers=1 :jmix-app:test --tests "*CustomerDraft*"`.
- PASS: source checks for `filterPayloadToSchema`, `schemaSynthesizer.buildSchema(metaClass)`, `assertSourceFaithful`, `getSourceTexts|setExtractionTurn`, draft predicate, no `UnconstrainedDataManager` in `ExtractionDraftAccess`, and `OPENROUTER_API_KEY` env wiring in `application.properties`/`.env.example`.
- PASS: JetBrains file-problem checks on touched Java/properties files. Remaining warnings were pre-existing/style/custom-property metadata warnings: Java 17-compatible `List.get(0)`, fixture field-local suggestions, raw-grid/test-seam warnings, and unresolved custom/Jmix property metadata.

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionDraftAccess.java` - secured open-draft read component.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionSourceText.java` - source-text evidence record.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionInput.java` - carries `sourceTexts` with backward-compatible constructor.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java` - carries and clears source texts for extraction turns.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` - passes document source text into RunContext on blocking/streaming paths.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java` - filters payloads to schema attributes and passes direct document text sources.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java` - builds scoped `ExtractionInput` with source texts.
- `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java` - production source-faithfulness enforcement.
- `jmix-app/src/main/resources/application.properties` and `jmix-app/.env.example` - OpenRouter API-key env documentation only.
- Test files under `ai-agent/ai-agent/src/test/...` and `jmix-app/src/test/...` - regression coverage for all five blocker closures.

## Decisions Made

- BL-02, BL-03, BL-04, and BL-05 are closed; BL-01's datasource/UI-default env migration was removed per user correction, leaving only API-key env handling.
- No new AI tool, entity table, audit kind, Jmix view, menu item, or AI-specific exposure layer was introduced.
- Image-only input remains prompt-based when no textual evidence exists; this plan closes pasted text and Tika-extracted document text, not OCR.
- Verification commands use `--max-workers=1` on this machine because parallel Gradle workers reproduced the known native-memory/paging-file failure.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Preserved source text on null ExtractionInput normalization**
- **Found during:** Task 4
- **Issue:** After adding source texts, `ExtractionService.normalizeInput(..., null)` would still rebuild an input from RunContext without user message, task files, media, or source texts.
- **Fix:** Normalization now pulls user message, task-file ids, media, and source texts from `RunContext`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java`
- **Verification:** `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test --tests "*ExtractionServiceTest" --tests "*ExtractionToolBridgeTest" --tests "*DefaultChatServiceIntentRoutingTest" --tests "*ExtractionEvaluationContractTest"`
- **Committed in:** `8946976`

**2. [Rule 3 - Blocking] Re-ran jmix-app extractor verification serially after native-memory worker failure**
- **Found during:** Task 4 verification
- **Issue:** Running ai-agent and jmix-app Gradle commands in parallel caused a JVM native-memory/paging-file failure before `CustomerDraftIntentExtractorTest` executed.
- **Fix:** Re-ran the jmix-app command alone with `--max-workers=1`; it passed.
- **Files modified:** None.
- **Verification:** `./gradlew --no-daemon --max-workers=1 :jmix-app:test --tests "*CustomerDraftIntentExtractorTest"`
- **Committed in:** N/A

**3. [Rule 3 - Blocking] Cleared stale Gradle daemon after broad ai-agent timeout left test-results locked**
- **Found during:** Task 6 verification
- **Issue:** The first broad `:ai-agent:ai-agent:test` attempt exceeded the tool timeout; the next attempt failed to delete `build/test-results/test/binary/output.bin` because a stale process held it open.
- **Fix:** Ran `./gradlew --stop`, then re-ran the broad ai-agent suite with a longer timeout and `--max-workers=1`; it passed.
- **Files modified:** None.
- **Verification:** `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test`
- **Committed in:** N/A

---

**Total deviations:** 3 auto-fixed (Rule 2: 1, Rule 3: 2)  
**Impact on plan:** No scope expansion; deviations either preserved the new source-text contract or stabilized verification in the known Windows/Jmix memory environment.

## Issues Encountered

- A generated `hs_err_pid10008.log` file was produced by the native-memory Gradle failure and removed as runtime output before commit.
- No residual blocker remains for the plan-required matrix. The known broad-module `User` metaclass/native-memory issues from `14-VERIFICATION.md` were not hidden; the plan-required broader confidence commands both passed after serializing workers.

## Auth Gates

None.

## Known Stubs

None. Stub-pattern scan only matched log-format placeholders (`{}`) and tool-description text, not unimplemented data paths or UI placeholders.

## User Setup Required

- Local developers should put `OPENROUTER_API_KEY` in an ignored `.env` file when using OpenRouter locally.
- Datasource and UI-login defaults intentionally remain in `application.properties`.

## Next Phase Readiness

Phase 14 gap closure is ready for phase-level re-verification and manual UI UAT from `14-UAT-CHECKLIST.md`.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
