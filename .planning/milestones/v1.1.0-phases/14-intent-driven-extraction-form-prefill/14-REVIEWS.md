---
phase: 14
reviewers: [claude, opencode]
reviewed_at: 2026-05-07T16:10:51Z
plans_reviewed: ["14-01-PLAN.md", "14-02-PLAN.md", "14-03-PLAN.md", "14-04-PLAN.md", "14-05-PLAN.md", "14-06-PLAN.md", "14-07-PLAN.md", "14-08-PLAN.md"]
---

# Cross-AI Plan Review — Phase 14

## Claude Review

# Phase 14 Plan Review — Intent-Driven Extraction → Form Prefill

## Summary

The 8-plan set is well-structured, dependency-ordered, and faithful to the locked SPEC/CONTEXT/UI-SPEC/RESEARCH artifacts. Wave decomposition is sensible (foundation → service+chat → loader+UI → host reference + verification), and each plan ships paired tests. The primary risks are (1) a Java version mismatch between project contract and UI-SPEC, (2) `withInitializer` semantics colliding with `DataContext.create`, (3) a `@Component` that takes a `Component` argument runtime ownership question for `OpenFormWithDraftHandler`, (4) thin coverage of streaming-event payload propagation (the renderer parses `summary` but `StreamingEvent.ToolResult` may not carry the JSON payload at all today), and (5) several scanner globs that won't work as written under PowerShell `Select-String`.

## Strengths

- **Decisions traceability**: Every plan's `must_haves.truths` cite specific D-IDs from CONTEXT.md; the D-15 supersession of SPEC REQ-10 is explicitly flagged in 14-03 (good — preserves audit append-only invariant).
- **Hard isolation of navigation owner**: Plan 03+05+06+08 collectively enforce "only `OpenFormWithDraftHandler` touches `ViewNavigators`" via TEST-15 grep + scanner on `StreamEventRenderer` + scanner on `ExtractionToolBridge`. Defense in depth.
- **Append-only audit two-row pattern (D-12)** is correctly distributed: row 1 in `ExtractionService` (Plan 03), row 2 in `DraftLoader` (Plan 05). No in-place updates.
- **Liquibase number bump to 110** correctly identified by RESEARCH and propagated to Plan 01 (avoids 100-* duplicate that SPEC missed).
- **Entity-generic engine discipline**: `MetaClassDtoSynthesizer` + `Map<String,Object>` target type + Customer in `jmix-app` only + Plan 08 source scanner. Engine stays clean.
- **Default-OFF reference extractor** via `@ConditionalOnProperty matchIfMissing=true` matches the Phase 11 mutation-tools precedent.
- **TTL reaper as the only lifecycle path for unsaved drafts** (D-16/D-17) correctly avoids `BeforeCloseEvent` complexity.
- **Bounded denied-attribute audit list (D-13, cap 16, truncation flag)** prevents oversized audit rows on hostile LLM output.

## Concerns

### HIGH

- **Java 17 vs Java 21 mismatch**: `CLAUDE.md` says Java 21; `14-RESEARCH.md` says "Use Java 17-compatible APIs even though one UI spec note mentions Java 21; this repository's project contract says Java 17." Plans 01, 02 both say "Java 17-compatible." This contradicts the project's CLAUDE.md. **Verify before execution** — if the project is actually on Java 21, missing `List.getFirst()`, sealed types, or pattern matching could be over-restrictive; if on 17, `record` patterns must be avoided. The Phase 11 memory entry "Keep Java 17-compatible List.get(0) assertions" suggests Java 17 is the truth, but this should be reconciled.

- **`StreamingEvent.ToolResult` payload shape is unverified**: Plan 06 Task 4 says "Parse `StreamingEvent.ToolResult.summary` as JSON … if Plan 03 extended the event with raw payload, use that instead." This is conditional — but Plan 03 does NOT actually extend `StreamingEvent.ToolResult`. RESEARCH notes the event "currently carries `(toolCallId, summary, outcome)`" and `StreamEventRenderer` ignores it. If `summary` is a human-readable string (e.g. truncated/serialized via existing `ToolResultFormatter`), parsing it as JSON will fail silently for the LLM payload. **Plan 03 should explicitly add a `payloadJson` or `structuredResult` field to `StreamingEvent.ToolResult`, or Plan 06 should read from a different surface (e.g. `RunContext.lastToolResult`).**

- **`OpenFormWithDraftHandler @Component @VaadinSessionScope` taking `Component host` argument**: Plan 05 Task 2 step 3 says `open(Component host, UUID draftId, …)`. But the handler is session-scoped, not view-scoped, and `ViewNavigators.detailView(host, …)` requires the navigation source. If the handler is session-scoped, it must receive the host on every call — fine — but the `AfterSaveEvent` listener registration (step 9) needs care: registering on the opened detail view is correct, but `view.addAfterSaveListener` returns a `Registration` that should be removed if the view closes without save (otherwise listener leaks across re-opens). **Specify the `Registration` cleanup strategy.**

- **`withInitializer` + `DataContext.create` interaction not nailed down**: Plan 05 Task 2 step 8 says "If the exact initializer method differs in Jmix 2.8, use the documented equivalent." This is hand-wave. The `withInitializer(Consumer<E> entity)` lambda runs BEFORE the detail view's `DataContext` registers the entity for tracking. SPEC REQ-10 says "The prefill applies via `DataContext.create(...)`" — but if `DraftLoader.apply` mutates the entity inside `withInitializer`, the entity passed in is the navigator's freshly created instance, not a `DataContext.create`-tracked one. **Either the loader must call `DataContext.merge` after init, or prefill should happen in an `InitEntityEvent` listener.** RESEARCH notes "the navigator-created entity must remain under the detail view's standard `DataContext` and save lifecycle" but the plan doesn't enforce it.

- **PowerShell verify regex globs are broken**: Multiple plans use patterns that won't work:
  - 14-07 Task 1: `Select-String -Path 'ai-agent/ai-agent/src/main/java/**/*.java'` — PowerShell `Select-String -Path` does NOT recurse `**`; this needs `Get-ChildItem -Recurse | Select-String`. Will return 0 hits regardless of actual leakage, falsely passing the scan.
  - 14-05 Task 1: `Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/*.java'` — single-level glob, OK, but won't catch sub-packages if added.
  - 14-06 Task 4: comma-separated patterns in `Select-String -Pattern 'A','B','C'` — `Select-String` supports this, but the count assertion `.Count -ge N` includes ALL matches across patterns, not "all N patterns matched at least once." A file with 3 matches of `open_form_with_draft` and 0 of the others would pass.

### MEDIUM

- **Audit row count for streaming-fallback path**: Plan 04 Task 4 says "Preserve streaming fallback path: pass the same `intentId`." But if `executeBlockingTurn` is invoked from BOTH the blocking path AND the streaming-fallback `catch`, and the chat-memory advisor persists user message once, then `prepare_form_draft` could fire twice if not guarded — the Phase 13 BLK-01 single-write fallback fix (decisions log) should be cited. This plan doesn't reference it.

- **`IntentRegistry` ordering — alphabetical by `label()` resolved in user locale**: UI-SPEC §Card-row picker locks alphabetical-by-label; but `IntentRegistry` (Plan 02) is in `com.vn.agent.extraction`, not the view layer. If the registry sorts by label using `IntentExtractor.label()` (the SPI fallback), it's locale-free; but if it sorts by `Messages.getMessage("chatView.intent.{intentId}.label")`, it depends on `LocaleResolver.getLocale()`. The plan doesn't specify which. **Lock label-resolution source in Plan 02 to match UI-SPEC.**

- **Renderer's "parse `summary` as JSON, silent on parse failure"** (Plan 06 Task 4 step 3): Silent on parse failure is fine for non-extraction tool results, but means an extraction tool result whose JSON shape is malformed (e.g. service threw and audit wrote FAILED but `ExtractionToolBridge` somehow returned a non-payload string) leaves the user with NO confirm button and no error indication. **Add an audit-side check OR a chat-side toast for malformed extraction results.**

- **Plan 03 Task 3 register-with-AgentToolCallbacks step is vague**: "Register the bridge with `AgentToolCallbacks` by adding it to the standard callback assembly." But `AgentToolCallbacks.forCurrentUser()` already aggregates all `@Tool`-bearing beans automatically (per RESEARCH). What's the actual code change? If discovery is automatic, this step is no-op; if not, the plan should specify the registration site.

- **Plan 04 fail-closed on missing `prepare_form_draft` callback**: Step 5 says "Fail closed if zero or more than one matching callback is found." Good, but doesn't specify the user-visible error path. If the Customer reference extractor is disabled and no other intents exist, the registry returns empty and the picker hides (D-08) — but if a user somehow submits a stale `intentId`, this fail-closed path needs a localized error toast, not a stack trace. **Specify error path.**

- **`@AfterSaveEvent` vs `EntitySavedEvent`** in Jmix 2.8: Plan 05 says "Register an `AfterSaveEvent` listener on the opened `StandardDetailView`." Jmix 2.8 detail views fire `BeforeSaveEvent` and `AfterSaveEvent`; the latter is a view event, not a data event. The plan is correct but should reference Jmix doc IDs, not just memory.

- **`ExtractionService` transactionality**: Plan 03 Task 2 doesn't specify `@Transactional`. The service does (a) audit denial write, OR (b) extractor invocation (LLM call — long, MUST NOT be in a transaction), then (c) draft persist + audit success. If wrapped in a single `@Transactional`, the LLM call holds a DB connection for seconds. **Explicitly NOT-transactional at service level; persist-and-audit can use programmatic short transactions.**

- **`MetaClassDtoSynthesizer` not exposed via cache**: Plan 02 does no caching. Schema for the same `(MetaClass, user)` is recomputed on every named-intent turn. For an `EntityAttributeContext.canModify` loop over ~20 attributes plus exposure check, this is OK in v1.1 but flag as monitoring item.

### LOW

- **Plan 01 `confirmed` field**: Marked as set to `true` "immediately before delete" (CONTEXT D-16 implication via Plan 05). This means the row is `confirmed=true` for ~1 ms. Effectively dead state. Consider dropping the field unless an audit query needs it.

- **Plan 02 `IntentOption(... boolean auto)` field**: SPEC keeps Auto out of the SPI but Plan 02 puts an `auto` boolean on the DTO. Cleaner: a separate `IntentOption.AUTO` constant or an `Optional<IntentExtractor<?>>` companion. Minor smell.

- **Plan 06 CSS task 5 omits `min-height: 4rem` on `.ai-agent-intent-confirm`**: UI-SPEC mandates it; the plan's verify regex doesn't check.

- **Plan 08 Task 4 runs `./gradlew build` after `./gradlew :ai-agent:ai-agent:test` and `./gradlew :jmix-app:test`**: `build` re-runs all tests, so the prior runs are wasted compute. Use `:ai-agent:ai-agent:check` + `:jmix-app:check` and skip `build` or use `assemble`.

- **Plan 08 manual UAT checklist** (Task 5) is outside the autonomous gate. Fine for a UI phase, but mark it explicitly as "post-merge UAT" so the autonomous executor doesn't block on it.

- **Plan 03 `MetadataTools.getInstanceName(...)` fallback**: "if null/blank, fall back to the entity caption plus draft id suffix." Good — but the fallback should be reproducible (test asserts exact format).

- **No mention of `Customer.email` / `Customer.phone` validation**: Plan 07 Task 1 says "validate Bean Validation annotations." If the host `Customer` entity has `@Email`, malformed extraction will throw — but the failure path (audit FAILED, no draft, user sees what?) isn't traced through the UI. The user gets a streaming-error toast presumably, but worth verifying.

## Suggestions

1. **Promote Plan 03 to extend `StreamingEvent.ToolResult` with `String payloadJson` (nullable)**, populated by the audit decorator when the tool returns a `LinkedHashMap`. Renderer reads `payloadJson` first, falls back to `summary`. This kills the "parse summary" ambiguity in Plan 06.

2. **Reconcile Java version**: Read the root `build.gradle(.kts)` and update either CLAUDE.md or RESEARCH.md before execution. Don't ship plans with contradictory version claims.

3. **Fix PowerShell `-Recurse` globs in Plans 07 and 08**:
   ```powershell
   (Get-ChildItem -Path 'ai-agent/ai-agent/src/main/java' -Recurse -Filter '*.java' |
     Select-String -Pattern 'com\.vn\.jmixapp\.entity\.Customer').Count -eq 0
   ```

4. **Specify prefill mechanism inside `withInitializer`**: Either (a) load the entity into the detail view's `DataContext` and apply via `dataContext.merge()`, or (b) move prefill to `@Subscribe InitEntityEvent` after `withInitializer` lands. Document the choice in Plan 05 Task 2.

5. **Add `Registration` cleanup for `AfterSaveEvent` listener** in `OpenFormWithDraftHandler` to avoid listener leaks on repeat opens.

6. **Plan 04: cite Phase 13 BLK-01 single-write streaming-fallback fix** when threading `intentId` through `executeBlockingTurn`. Tests must assert no double `prepare_form_draft` invocation on streaming fallback.

7. **Plan 02: lock `IntentRegistry` label-resolution source** to either SPI `label()` (locale-free, deterministic) or `Messages` (locale-aware, needs locale-key for sort stability). UI-SPEC implies locale-aware; if so, capture the user's locale in `eligibleForCurrentUser()`.

8. **Plan 06 Task 4: add explicit error-toast path** when JSON parse fails on a tool result whose `eventName` was `prepare_form_draft` (audit row says SUCCESS but renderer can't extract payload — that's a contract bug worth surfacing).

9. **Plan 08 Task 1: include `ToolContributor` discovery**. The scanner should reflect over `ToolCallbackProvider`-registered beans, not just classes in `com.vn.agent.tools`. Otherwise host-side `OrderSummaryToolContributor`-style classes can leak `ViewNavigators` undetected.

10. **Plan 05: `verify` regex `'AfterSave'`** matches both `AfterSaveEvent` and any other `AfterSave*` token. Tighten to `AfterSaveEvent` to avoid false positives.

## Risk Assessment

**MEDIUM**.

Justification:
- The architectural contract is sound and well-decomposed; no scope creep.
- Security posture is strong: TEST-15 + multi-layer scanners + `EntityAttributeContext.canModify` gate + row-level role + exposure denial.
- The HIGH-severity items are tractable: Java version is a quick reconcile; `StreamingEvent.ToolResult` extension is a 1-line schema change in Plan 03; `withInitializer`/`DataContext` semantics is a Jmix doc lookup; PowerShell glob bugs are find-and-replace.
- The streaming-event payload uncertainty is the riskiest single item — if not resolved before Plan 06 runs, the confirm button silently never renders, and only the eval contract test (Plan 08 Task 3) would catch it (deterministic) — but UI tests in Plan 06 Task 6 might also pass if they bypass the real renderer pipeline.
- No threats to data integrity, security model, or audit invariants. Phase is ready to execute after the HIGH items are addressed in a quick replan pass.


---

## OpenCode Review

# Cross-AI Plan Review: Phase 14 - Intent-Driven Extraction → Form Prefill

## Plan Quality Assessment

**Strengths:**
- Clear, measurable success criteria with specific technical requirements
- Well-structured wave-based implementation plan with logical dependencies
- Thorough mapping to requirements (EXTRACT-01 through EXTRACT-10, ENT-08, SPI-12, TEST-15, SEC-06)
- Strong emphasis on security boundaries (LLM never receives UI-mutation primitives)
- Consistent with established project patterns (audit reuse, permission checking, entity conventions)
- Detailed implementation decisions provide valuable context for execution

**Adequate:**
- Dependency tracking between waves is clear but could benefit from more explicit risk mitigation strategies
- Testing considerations are present but could be expanded to cover edge cases

## Completeness Assessment

**Covered Elements:**
- Entity specifications (AiExtractionDraft with proper Jmix conventions)
- SPI definitions (IntentExtractor<T>, IntentRegistry)
- Schema synthesis (MetaClassDtoSynthesizer with Map<String,Object> target)
- Service layer (ExtractionService, ExtractionToolBridge)
- Controller/view logic (ChatPanelFragment integration, OpenFormWithDraftHandler)
- Draft lifecycle management (TTL cleanup, confirmation flow, deletion on save)
- Security implementation (row-level policies, permission checks at multiple layers)
- Audit integration (reusing AuditWriter.writeToolCall)
- UI components (card-row picker, confirmation button, CSS/i18n)
- Host reference implementation (CustomerDraftIntentExtractor in jmix-app)
- Verification gates (TEST-15 navigation scanner)

**Minor Gaps:**
- Limited detail on error handling for expired drafts during user confirmation
- Insufficient specification for concurrent draft creation scenarios
- PayloadJson format details could be more explicit for implementation guidance
- Edge case handling for schema changes between draft creation and confirmation

## Risk Assessment

**High-Risk Areas:**
1. **Foundation Dependencies**: Heavy reliance on Phase 9/10 implementations being correct (LlmExposurePolicy, AccessManager integration)
2. **Permission Complexity**: Triple-layer permission checking (LlmExposurePolicy → AccessManager → EntityAttributeContext) increases failure surface
3. **Draft Lifecycle Management**: Complex interactions between TTL cleanup, user confirmation, and save operations
4. **Integration Points**: Multiple component interactions (ExtractionService ↔ IntentExtractor SPI ↔ DraftLoader ↔ ViewNavigators) increase integration risk

**Mitigation Strategies Present:**
- Wave-based blocking ensures foundational work completes first
- Explicit TEST-15 scanner prevents UI-mutation primitive leaks
- Permission-gated prefill (setValueIfPermitted) follows security best practices
- Hourly cleanup job with TTL provides safety net for abandoned drafts
- Host reference implementation kept separate from core (jmix-app module)

**Recommended Additional Mitigations:**
- Add explicit handling for draft expiration during user confirmation phase
- Implement conflict detection for concurrent draft creation on same entity
- Consider schema versioning in payloadJson to handle entity changes
- Add more comprehensive error states for failed extractions

## Overall Assessment

The Phase 14 plan is **high quality and comprehensive**, addressing all specified requirements with appropriate technical depth. The wave-based implementation approach is sensible for managing complexity, and the plan correctly emphasizes critical security boundaries (LLM isolation from UI primitives). 

The primary risks are technical dependencies and integration complexity, which are appropriately acknowledged through the blocking wave structure and verification gates. With careful attention to the minor gaps identified (error handling, concurrency, payload format), this plan has a strong likelihood of successful execution.

**Recommendation**: Proceed with planning as structured, with additional focus on the recommended risk mitigations during implementation.


---

## Consensus Summary

Both reviewers judged the Phase 14 plan set as generally strong, well sequenced, and aligned to the intent-driven extraction goal. They agreed that the major design strengths are the wave ordering, requirement traceability, security boundary that keeps UI navigation outside LLM tools, and the use of normal Jmix permission/data-context flows for the final save path.

### Agreed Strengths

- The eight plans decompose the phase coherently from persistence/SPI foundations through service wiring, UI confirmation, host reference implementation, and verification gates.
- The LLM-to-UI boundary is intentionally constrained: prepare_form_draft returns a structured payload, while controller-side code owns navigation and form initialization.
- Security checks are layered through exposure gating, Jmix AccessManager, row-level draft ownership, and attribute-level prefill guards.
- Audit and lifecycle concerns are planned explicitly through AuditWriter.writeToolCall, draft TTL cleanup, and save-time draft deletion.

### Agreed Concerns

- The draft confirmation path has several integration-sensitive points that need tighter specification before execution: structured tool-result propagation, draft expiration behavior, view navigation ownership, and save/listener cleanup.
- Permission and lifecycle complexity is high enough that tests must exercise the real path end to end, not just isolated service behavior.
- The verification plans need hardening so scanner commands cannot falsely pass, especially under PowerShell/glob behavior.

### Divergent Views

- Claude raised five concrete HIGH-severity plan defects that should be resolved in a quick replan pass before Phase 14 execution.
- OpenCode did not raise separate numbered HIGH defects, but framed the main risk as integration complexity around Phase 9/10 dependencies, draft lifecycle, permissions, and component interactions.

### Current Unresolved HIGH Concerns

- Java version contract mismatch: plans/research say Java 17-compatible while the review found contradictory project guidance mentioning Java 21.
- StreamingEvent.ToolResult structured payload propagation is not specified; Plan 06 may parse a human-readable summary that does not carry the raw tool payload.
- OpenFormWithDraftHandler listener lifecycle is underspecified; AfterSaveEvent registration needs cleanup when the detail view closes without saving.
- withInitializer and DataContext.create/tracking semantics are not nailed down for Jmix detail navigation prefill.
- PowerShell verification scanner globs/counts can falsely pass, especially recursive ** usage and aggregate match-count assertions.
