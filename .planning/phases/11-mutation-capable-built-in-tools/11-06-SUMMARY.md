---
phase: 11-mutation-capable-built-in-tools
plan: 06
subsystem: tools-mutation
tags: [error-translator, mut-07, mut-11, locale, p-22]

# Dependency graph
requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: ToolUserError + ToolErrorDto envelope (Phase 9 D-14, used by BuiltInDataTools)
  - phase: 11-mutation-capable-built-in-tools
    provides: AiMutationIntent locale block (Plan 11-01) — establishes the property prefix layout this plan extends
provides:
  - "MutationErrorTranslator @Component (com.vn.agent.tools.mutation) — translate(Throwable, String, MetaClass) plus 6 typed factory methods (accessDenied / validationFailed / concurrentModification / idempotencyViolation / parameterConversion / notFound) and the special commitFailed factory"
  - "6 LLM-visible stable error codes: access_denied, validation_failed, idempotency_violation, concurrent_modification, parameter_conversion_error, not_found"
  - "6 locale captions in messages_en.properties + messages_vi.properties under prefix ai-agent.tool.mutation.errorCode.*"
affects: [11-07, 11-07A, 11-07B, 11-07C, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Translator chokepoint pattern: every mutation tool catch-all branch routes through translate(...), so the LLM-visible error string is rebuilt from a stable code + canned safe hint and never carries thrown.getMessage() or LLM-supplied attribute names (P-22 mitigation)"
    - "Pre-typed ToolUserError sanitization: ToolUserError instances bubbling up from FilterLiteralValueConverter / ToolEntityResolver are NOT returned verbatim; their stable code is preserved but the message + hints are rebuilt by sanitizeStableToolUserError(...) so legacy reason strings (which may contain LLM-supplied attribute names) cannot reach the LLM"
    - "Legacy → stable code remap: invalid_literal / unsupported_type / invalid_id from the read-tool path collapse to parameter_conversion_error at the mutation boundary, restoring the 6-code D-04 taxonomy"
    - "Both-flavor optimistic-lock catch: jakarta.persistence.OptimisticLockException AND org.springframework.dao.OptimisticLockingFailureException — Spring's translation layer is not guaranteed to run on every code path that touches Eclipselink directly (RESEARCH Pitfall 5)"
    - "Both-flavor access-denied catch: org.springframework.security.access.AccessDeniedException AND io.jmix.core.security.AccessDeniedException — Jmix's AccessManager raises its own type that does NOT extend Spring's, so a single instanceof check would silently miss policy denials"
    - "commit_unknown special-case: commitFailed(...) returns the concurrent_modification code but the hint pivots to 'do not retry automatically' — the host write may already have committed and a fresh-key retry would duplicate it (D-04 commit-unknown contract)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationErrorTranslator.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "Jmix AccessDeniedException lives at io.jmix.core.security.AccessDeniedException, NOT io.jmix.security.AccessDeniedException — the plan skeleton's import was a typo; verified against the jmix-core 2.8.0 sources jar (io/jmix/core/security/AccessDeniedException.java) before committing"
  - "Pre-typed ToolUserError pass-through is FORBIDDEN: even though the code is already correct, the reason string from FilterLiteralValueConverter contains the LLM-supplied attribute name (e.g. 'value must not be null for foo'); routing every typed ToolUserError through sanitizeStableToolUserError(...) closes the P-22 leak surface uniformly"
  - "commitFailed maps to concurrent_modification stable code (not a new 7th code) because the LLM's recovery action is the same shape as concurrent_modification — verify state via get_record / find_records — but with a stricter no-auto-retry constraint encoded in the hint, not the code"
  - "Default fallback (any unknown Throwable) returns validation_failed, not a synthetic 'internal_error' code — the 6-code D-04 taxonomy is closed; LLM recovery for an unknown failure is the same as a constraint violation (re-inspect attribute types / required fields, then retry with a fresh idempotencyKey)"
  - "Locale captions live ONLY in user-facing UI bundles, not in the LLM-visible reason strings — the LLM contract is English (Pitfall 7); host applications surfacing tool errors to end-users localize via the new ai-agent.tool.mutation.errorCode.* keys"

patterns-established:
  - "MutationErrorTranslator factory methods (accessDenied, validationFailed, concurrentModification, idempotencyViolation, parameterConversion, notFound, commitFailed) are public so Wave 7 BuiltInMutationTools call sites can construct typed errors directly without round-tripping through translate(...) when the failure is already classified (e.g. dedup row says VIOLATION → call idempotencyViolation(metaClass) directly)"
  - "Comment policy: do not write the literal token 'thrown.getMessage()' anywhere in the source file (even in comments) — acceptance criteria use literal grep, and a comment matching the forbidden string would create a false positive on automated scans"

requirements-completed:
  - MUT-07
  - MUT-11

# Metrics
duration: 6min
completed: 2026-04-28
---

# Phase 11 Plan 06: MutationErrorTranslator + 6 Stable Error Codes Summary

**Wave 6 ships the single chokepoint that converts every Java/Spring/Jmix exception thrown by Wave 7's mutation tools into one of the 6 stable D-04 error codes the LLM is contract-bound to recover from. `MutationErrorTranslator` is a `@Component` with one public `translate(Throwable, String, MetaClass)` plus 6 typed factory methods and a `commitFailed(...)` special-case for post-host-save finalization failures. The translator NEVER echoes raw exception text, attribute names, or any LLM-supplied prose into the error string returned to the model — it preserves only the stable code and rebuilds the message/hints from canned safe templates (P-22 mitigation). Pre-typed `ToolUserError` instances bubbling up from `FilterLiteralValueConverter` / `ToolEntityResolver` are sanitized through `sanitizeStableToolUserError(...)` rather than passed through verbatim, and the legacy read-tool codes `invalid_literal` / `unsupported_type` / `invalid_id` are remapped to the Phase 11 stable code `parameter_conversion_error`. Both flavors of `OptimisticLockException` (jakarta + Spring's translated `OptimisticLockingFailureException`) and both flavors of `AccessDeniedException` (Spring + Jmix `io.jmix.core.security`) are caught explicitly per RESEARCH Pitfall 5. The 6 error codes have parallel locale captions under `ai-agent.tool.mutation.errorCode.*` in both `messages_en.properties` and `messages_vi.properties` for host UI surfaces (the LLM contract itself stays English-only per Pitfall 7).**

## Performance

- **Duration:** ~6 min (two-task plan; warm Gradle daemon — both tasks compiled in 1-5s)
- **Started:** 2026-04-28T20:50:00Z
- **Completed:** 2026-04-28T20:56:00Z
- **Tasks:** 2 / 2
- **Files created:** 1 (MutationErrorTranslator.java)
- **Files modified:** 2 (messages_en.properties, messages_vi.properties)
- **Per-task verification:** `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL after Task 1; `./gradlew :ai-agent:processResources` BUILD SUCCESSFUL after Task 2

## Accomplishments

- **`MutationErrorTranslator`** ships as a public `@Component` in `com.vn.agent.tools.mutation`:
  - `translate(Throwable thrown, String toolName, MetaClass metaClass)` — single public entry point used by every mutation tool catch-all branch in Wave 7. Routing order: typed `ToolUserError` instance (sanitized + legacy-code-remapped) → `OptimisticLockingFailureException` / `jakarta.persistence.OptimisticLockException` → Spring `AccessDeniedException` / `io.jmix.core.security.AccessDeniedException` → `jakarta.validation.ConstraintViolationException` / `DataIntegrityViolationException` → default fallback (validation_failed).
  - `accessDenied(MetaClass)` — typed factory for direct call by Wave 7 when `LlmExposurePolicy.canModify` denies or per-attribute `EntityAttributeContext.canModify` denies.
  - `validationFailed(MetaClass)` — typed factory for mandatory-attribute-missing on create / dataContext.validate failures.
  - `concurrentModification(MetaClass)` — typed factory for explicit @Version-conflict paths.
  - `idempotencyViolation(MetaClass)` — typed factory for the `MutationIntentRepository.ReservationState.VIOLATION` branch (different request hash on existing key).
  - `parameterConversion(MetaClass)` — typed factory for type-coercion failures the caller has already classified (e.g. malformed UUID for to-one relationship).
  - `notFound(MetaClass, String id)` — typed factory for `update_record` / `add_related_record` / `remove_related_record` when the supplied id does not resolve. Generic message; raw id never echoed back to LLM.
  - `commitFailed(MetaClass)` — typed factory for the post-host-save finalization-failure path. Returns the stable `concurrent_modification` code but with a hint that explicitly says "do not retry automatically" because the host write may already have committed; fresh-key retry would duplicate it.
  - `sanitizeStableToolUserError(String, MetaClass)` private helper — switch over the 6 stable codes (plus `unknown_entity` carryover from Phase 10 read-tool path) that rebuilds the message + hints from canned safe templates, dropping the original ToolUserError reason string entirely.
- **Locale captions for the 6 error codes** in `messages_en.properties` AND `messages_vi.properties`:
  - `ai-agent.tool.mutation.errorCode.access_denied` — "Operation not permitted" / "Không được phép thực hiện"
  - `ai-agent.tool.mutation.errorCode.validation_failed` — "Value validation failed" / "Giá trị không hợp lệ"
  - `ai-agent.tool.mutation.errorCode.idempotency_violation` — "Idempotency conflict" / "Xung đột idempotency"
  - `ai-agent.tool.mutation.errorCode.concurrent_modification` — "Record was modified concurrently" / "Bản ghi vừa bị thay đổi"
  - `ai-agent.tool.mutation.errorCode.parameter_conversion_error` — "Invalid parameter format" / "Định dạng tham số không hợp lệ"
  - `ai-agent.tool.mutation.errorCode.not_found` — "Record not found" / "Không tìm thấy bản ghi"

## Task Commits

1. **Task 1: MutationErrorTranslator** — `15128f5` (feat)
2. **Task 2: Locale captions for 6 error codes** — `4251eb3` (feat)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationErrorTranslator.java` — 142 lines including Javadoc on the P-22 mitigation contract, the both-flavor catch contract (Pitfall 5), and the legacy-code remap contract.

### Modified
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — appended 6 keys + 1 comment header under the existing `# AiMutationIntent entity (Phase 11)` block.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — appended 6 keys + 1 comment header in parallel.

## Decisions Made

- **Jmix `AccessDeniedException` package was misspelled in the plan skeleton** — the plan said `io.jmix.security.AccessDeniedException`, but the actual class lives at `io.jmix.core.security.AccessDeniedException`. Verified by inspecting `jmix-core-2.8.0-sources.jar` (`io/jmix/core/security/AccessDeniedException.java`) before committing. The committed code uses the correct package; behavior is identical (catch the Jmix-flavor denial) so this counts as a Rule 3 typo fix, not an architectural change.
- **Pre-typed `ToolUserError` instances are sanitized, NOT passed through** — even though their stable code is already correct, their original `reason` string was constructed at deeper call sites (e.g. `FilterLiteralValueConverter`'s "value must not be null for {attribute}") and may carry an LLM-supplied attribute name. Routing every typed `ToolUserError` through `sanitizeStableToolUserError(...)` closes the P-22 leak surface uniformly. The plan's acceptance criterion "File does NOT contain literal `return tue;`" enforces this contract.
- **`commitFailed` returns `concurrent_modification` code, not a synthetic 7th code** — the 6-code D-04 taxonomy is closed; commit-unknown is the same recovery shape as concurrent-modification (verify state via `get_record` / `find_records`) but with a stricter no-auto-retry constraint. Encoding that constraint in the hint string rather than a new code keeps the LLM's contract surface narrow.
- **Default fallback is `validation_failed`, not a synthetic `internal_error` code** — same closed-taxonomy reasoning. Any unknown failure is, from the LLM's perspective, indistinguishable from a constraint violation: re-inspect attribute types / required fields, then retry with a fresh idempotencyKey.
- **Comment that originally referenced the literal token `thrown.getMessage()` was rewritten** — acceptance criterion 12 uses literal grep (`grep -c "thrown.getMessage()"` returns 0), and a Javadoc comment explaining "NEVER echo thrown.getMessage()" would create a false positive. Comment now reads "NEVER echo the raw exception message into the LLM-visible result string". Same intent, no false-positive surface for automated scans.
- **`notFound(MetaClass, String id)` accepts `id` but does NOT echo it** — the parameter is retained for API symmetry with future phases that may want to log it server-side (audit `argumentsJson` already carries the id), but the LLM-visible message is generic ("no record found for the supplied id"). Documented in Javadoc.
- **Locale captions only in the user-UI bundles (`ai-agent.tool.mutation.errorCode.*`), NOT in the LLM-visible reason strings** — Pitfall 7 says LLM-protocol strings stay English (the model's tool-output schema is English). Locale captions are for host applications that want to render structured tool errors in their own UI under the user's locale.

## Deviations from Plan

- **[Rule 3 - Typo fix] `io.jmix.security.AccessDeniedException` → `io.jmix.core.security.AccessDeniedException`**
  - **Found during:** Task 1 (compile-time pre-flight verification)
  - **Issue:** The plan skeleton imported `io.jmix.security.AccessDeniedException`, but that class does not exist in jmix-security 2.7.4. The actual Jmix denial type is `io.jmix.core.security.AccessDeniedException` (verified via `jmix-core-2.8.0-sources.jar`).
  - **Fix:** Used the correct fully-qualified name in the `instanceof` check. Behavior is identical — both Spring and Jmix `AccessDeniedException` flavors are caught exactly as the plan intended.
  - **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationErrorTranslator.java` (line 53)
  - **Commit:** `15128f5`

Otherwise plan executed exactly as written. Both tasks compiled on first attempt after the typo fix.

## Issues Encountered

None. Both tasks compiled / processResources cleanly on first attempt after the package-name typo fix. JetBrains MCP `get_file_problems` is not available in this execution environment (per the execution_context note), so static-analysis triage is deferred to the next IntelliJ session.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** when next available, run on `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationErrorTranslator.java`. Gradle `:ai-agent:compileJava` exits 0 with zero ERROR-level Javac diagnostics, so this is a precautionary review only.
- **Locale parity audit:** confirm both bundles still parse cleanly after the next bundle reload; both files retained their existing trailing-newline convention and the new keys nest cleanly under the existing `# AiMutationIntent entity (Phase 11)` block.

## User Setup Required

None — `MutationErrorTranslator` ships as a `@Component` and auto-discovers via `@ComponentScan`. The factory methods are public, so Wave 7 `BuiltInMutationTools` can autowire the component and call either `translate(...)` (catch-all) or the typed factory methods (already-classified failure paths) directly.

## Next Phase Readiness

- **Wave 7 (Plan 11-07*: BuiltInMutationTools)** can now autowire `MutationErrorTranslator` and use:
  - `translate(thrown, toolName, metaClass)` in the catch-all branch around `DataManager.save` and orchestrator finalization.
  - `translator.accessDenied(metaClass)` after `LlmExposurePolicy.canModify` denial or per-attribute `EntityAttributeContext.canModify` denial.
  - `translator.idempotencyViolation(metaClass)` for the `MutationIntentRepository.ReservationState.VIOLATION` branch.
  - `translator.parameterConversion(metaClass)` after explicit type-coercion failures (e.g. malformed UUID) the tool has already classified.
  - `translator.notFound(metaClass, id)` for `update_record` / `add_related_record` / `remove_related_record` when the supplied id does not resolve.
  - `translator.commitFailed(metaClass)` in the post-host-save finalization-failure path (orchestrator audit write fails after `DataManager.save` returned).
- **Host UI surfaces** consuming the new locale captions can resolve them via `Messages.getMessage("ai-agent.tool.mutation.errorCode." + dto.error())`.
- No blockers for Plan 11-07A.

## TDD Gate Compliance

This plan is `type: execute` (not `type: tdd`) and ships infrastructure code only. Wave 7 plans add the integration tests that exercise this translator end-to-end (TEST-10 access_denied, TEST-11 idempotency replay, TEST-12 commit-unknown). No RED/GREEN/REFACTOR gate applies at this layer.

## Self-Check: PASSED

All claimed files exist on disk; both task commits exist in `git log`.

- 1 / 1 created file verified: `MutationErrorTranslator.java`
- 2 / 2 modified files verified: `messages_en.properties`, `messages_vi.properties`
- 2 / 2 commit hashes verified: `15128f5`, `4251eb3`
- `./gradlew :ai-agent:compileJava` exits 0
- `./gradlew :ai-agent:processResources` exits 0
- Acceptance-criteria literals: 6 stable codes ≥1 each, 3 legacy codes ≥1 each, both `OptimisticLocking*Exception` types matched, `if (thrown instanceof ToolUserError tue)` present, `return tue;` absent (0 occurrences), `thrown.getMessage()` absent (0 occurrences), 6 EN locale keys, 6 VI locale keys

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-28*
