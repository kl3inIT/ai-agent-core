---
phase: 16
reviewers: [opencode, codex]
reviewed_at: 2026-05-13T08:40:42Z
plans_reviewed: [16-01-PLAN.md, 16-02-PLAN.md, 16-03-PLAN.md, 16-04-PLAN.md, 16-05-PLAN.md, 16-06-PLAN.md, 16-07-PLAN.md]
---

# Cross-AI Plan Review — Phase 16

## OpenCode Review

# Cross-AI Plan Review: Phase 16 — Admin Settings

## Summary

These 7 plans implement the merged Phase 16 (model picker + config-knob migration) with strong architectural consistency, comprehensive test coverage (7 scaffolded test classes → ~40 test methods), and clear dependency ordering. The wave structure (0→1→2→3) is well-grounded — Wave 0 locks type signatures, Wave 1 builds schema + catalog, Wave 2 wires runtime (resolver + events + UI), Wave 3 delivers the admin surface + validation + catch-reissue. Every deliverable maps to an in-tree precedent documented in PATTERNS.md.

---

## Strengths

- **Precedent-driven architecture**: Every D-01..D-09 decision maps to an existing in-tree analog (`AiExposureRuleEntityListener` → entity listeners, `AiParametersResolver` → `AiUiSettingsResolver`, `TtlConfigTest` → test shapes, etc.). This dramatically reduces the "design from scratch" risk.

- **Test-first scaffolding**: 16-01 creates 7 failing test classes before any production code lands. Each downstream plan explicitly enumerates which test it flips green. This is a genuine verification chain — not aspirational.

- **Security invariants enforced at build time**: SEC-08's three-legged source-scan (no secret bound editable, no `@ConditionalOnProperty` bound editable, single publish site) is checked by file-system walking tests, not code review. The "twin publisher" invariant is tested in **two** separate test classes (Plan 05 + Plan 06) for defense-in-depth.

- **Correct Pitfall 3 handling**: `AiUiSettingsResolver` uses `UnconstrainedDataManager` for the singleton load, avoiding the row-level-security trip that would break non-admin chat turns. `AiParametersResolver` (unchanged) uses constrained `DataManager` which is correct since it reads per-profile parameters scoped to the active role.

- **Pitfall 6 false-positive guard**: The bad-model classifier (`isBadModelException`) requires status ∈ {400, 404, 422} — 5xx responses are explicitly excluded. This prevents transient network errors from triggering a one-shot model reissue.

- **Open Question 1 resolved correctly**: `fallbackModel()` returns `defaults.model()` directly, bypassing `resolveActive()`. A naive implementer would have written `effectiveModel(resolveActive())` which loops on bad-model saves.

- **Co-located KIND widening**: The `AI_AUDIT_EVENT.KIND` varchar(16)→varchar(32) migration is correctly placed in the same changelog as the AiUiSettings columns. A separate changelog would be correct but would add unnecessary ordering constraints.

---

## Concerns

### HIGH

1. **`MODEL_VALIDATION_FAILURE` audit kind — kind-parameter overload may not exist on `AuditWriter`**
   - Plan 16-07 acknowledges this as a scout-time risk ("if no overload exists, add one"). If the existing `writeToolCall` stamps `AuditKind.TOOL` unconditionally, adding a generic `writeAuditEvent(String kind, ...)` method is necessary but risks breaking existing callers if not done carefully.
   - **Impact**: Plan 16-07 cannot complete until this is verified during implementation. Since it's Plan 07 (last wave), the delay is manageable — but the plan should harden the fallback: define the overload in Plan 16-01 alongside the `AuditKind` constant extension, or migrate Wave 0 to include the new writer method.

2. **`KnobInventoryScanner` scanning ALL `@ConfigurationProperties` — performance risk at boot**
   - `ConfigurationPropertiesBean.getAll(applicationContext)` reflects every `@ConfigurationProperties` bean in the context, including Spring Boot auto-config beans, embedded-server beans, and third-party library beans. On a typical Jmix 2.8 app with ~50-80 `@ConfigurationProperties` beans, the reflective walk + environment key lookup per component could add 200-500ms to boot time.
   - **Mitigation**: The plan could filter to beans whose prefix starts with `jmix.ai-agent.` or `ai-agent.` (the project's known prefix), with an escape hatch for host extension. This is not currently mentioned. The `ApplicationReadyEvent` timing means this doesn't delay Tomcat startup, but it delays the first admin view paint.

### MEDIUM

3. **Plan 16-06 wave-3 ordering — `AiUiSettingsDetailView` depends on Plan 02 (schema) AND Plan 04 (resolver), but Plan 04 is Wave 2. This is correct in the depends-on chain. However, a subtle issue: the `bootConfigGrid` renders *current property values* from `KnobInventory`, which the scanner resolves at `ApplicationReadyEvent`. The values shown on the Boot Config tab are a boot-time snapshot — they do NOT reflect runtime edits to the Tier-1 DB fields even though the Tier-1 knobs are editable elsewhere on the same form. This is **intentional** per CFG-02 (boot-capture toggle display), but the tab should carry a refresh button or note "values captured at boot" to avoid operator confusion. The plan doesn't mention a refresh affordance.

4. **10 caller-site injections in Plan 16-04 are additive but include `MutationIntentRepository` — mutation-idempotency TTL has a coerce step**
   - Plan says: "swap `mutationProperties.resolvedIdempotencyTtl()` (Duration) → `aiUiSettingsResolver.resolveMutationIdempotencyTtlSeconds()` (long seconds — coerce on consumption: `Duration.ofSeconds(seconds)`)." The coercion site (`MutationIntentRepository`) must be verified during scout — if it calls `.toMillis()` or compares against `System.currentTimeMillis()`, the unit mismatch breaks idempotency logic silently.
   - **Recommendation**: Plan 16-04 should include an explicit cross-reference step: read `MutationIntentRepository`'s existing idempotency comparison logic before changing the return type to `long seconds`.

5. **Catalog drift test defaultMatchesDefaultParamsYaml — what happens when `default-params.yaml` has no `model` field?**
   - `default-params.yaml` currently has a `model` entry, so this is theoretical. But the plan doesn't specify the failure mode if `AiAgentDefaultsProperties.model()` returns null (e.g., a misconfigured seed). The `ChatModelCatalog @PostConstruct` drift check would also blow up. This is acceptable for a dev-facing boot-fast failure but should be documented as a known boot hazard.

### LOW

6. **Plan 16-02 bean-validation `@Min(-1)` on Long fields — boundary semantics**
   - The plan correctly annotates task-file columns with `@Min(-1)` to preserve the sentinel. However, `@Min(-1)` on a Long field in Jakarta Validation rejects any value `<-1`, including -50 and 0. The sentinel is `-1` and the meaningful range is `-1 ∪ [60, 604800]`. But `0` would be rejected even though it's clearly wrong. This is fine — the error message should guide the admin to the correct sentinel value. The locale keys include the validation messages, which should mention the sentinel.

7. **Plan 16-07: Thread safety of `buildAndCallChatClient` helper extraction**
   - The existing code at the BLK-01 chokepoint runs in a single-threaded Vaadin access path. The extracted helper doesn't introduce new thread-safety issues as long as it accesses only local/method-scoped variables. The plan should verify that `toolCallbacks.callbacksFor(...)` and `auditToolContext(...)` don't hold mutable shared state that would confuse a reissue. (This is unlikely since reissue happens in the same thread, not a concurrent retry.)

8. **Plan 16-05: The `CustomValueSetEvent` handler calls `modelField.setValue(event.getDetail())` — but the existing `@Subscribe("modelField") onModelFieldChange` also fires after `setValue`.**
   - This means `onModelFieldChange` fires twice for custom entries (once from `CustomValueSetEvent.setValue`, once from ComboBox's own value change). The existing `refreshYamlPreview` runs twice. This is innocuous (idempotent repaint) but should be documented so nobody stacks non-idempotent logic on `onModelFieldChange`.

---

## Suggestions

1. **Move `AuditWriter` kind-overload into Plan 16-01 Wave 0** — Define the `writeAuditEvent(String kind, ...)` method alongside the `MODEL_VALIDATION_FAILURE` constant. This gives Plan 16-07 a stable API to call, eliminating the scout-time risk. Even better: add a method that accepts `kind` as a parameter and delegates to the private helper that `writeToolCall` uses internally. The overload is additive (existing `writeToolCall` callers are untouched).

2. **Add a filter prefix to `KnobInventoryScanner`** — Scope the `ConfigurationPropertiesBean.getAll` walk to beans whose `@ConfigurationProperties prefix` starts with `jmix.ai-agent.` or `ai-agent.` (or whatever the project's known prefix is). Optionally expose `ai-agent.admin.knob-scanner.additional-prefixes` for host extension. This keeps boot-time reflection bounded to ~10 records instead of ~60-80.

3. **Add a "refresh" button or "boot-time snapshot" note to the Boot Config tab** — Label the tab clearly: "Boot config (read-only — values captured at application startup)". Without this, an operator who edits Tier-1 knobs on the same form might assume the Boot Config tab reflects the new values. A `<simplePagination>`-style reload action could refresh `KnobInventory` from `ConfigurationPropertiesBean.getAll` without a full restart, but out of scope for this phase — just the note is sufficient.

4. **Add a dedicated unit test for `isBadModelException` static method** — Plan 16-07's integration test stubs the `ChatClient`, but the classification function is pure logic (cause-chain walker). A standalone `@ParameterizedTest` with input pairs (exception type, cause chain, status, body) → expected boolean would catch regression faster than an integration test and is simpler to write. Extract the method into a package-private static on `DefaultChatServiceImpl` (or a util class) so it can be unit-tested without booting Spring.

5. **Document the `MutationIntentRepository` coercion change in a source comment** — Where the resolver call replaces `Duration.ofMillis(mutationProperties.resolvedIdempotencyTtl().toMillis())` with `Duration.ofSeconds(aiUiSettingsResolver.resolveMutationIdempotencyTtlSeconds())`, add a comment: "Phase 16 D-01: AiUiSettings stores idempotency TTL as seconds (Long column) to avoid JPA Duration mapping. Resolver coerces to Duration for existing comparison logic."

---

## Risk Assessment

**Overall: MEDIUM**

| Category | Rating | Basis |
|----------|--------|-------|
| Architectural soundness | LOW (good) | Every decision has an in-tree precedent; no new abstractions |
| Dependency correctness | LOW | Wave ordering is correct; Plan 04 correctly serialized after Plan 02; cross-plan symbol references are stable |
| Security | LOW | SEC-08's three-legged source-scan is comprehensive; secrets never reach DOM; `UnconstrainedDataManager` is correctly scoped to resolver |
| Completeness vs. requirements | MEDIUM | All 8 requirements addressed; but Plan 16-04's `MutationIntentRepository` coercion detail is under-specified (relies on scout-time verification) |
| Implementation risk | MEDIUM | Plan 16-06 touches ~20 files (annotation pass on 10 records + 5 starter/library files + view expansion); Plan 16-07's `AuditWriter` kind-overload may need a Plan 01 reshuffle; Plan 16-04's 10 caller modifications are mechanically repetitive but error-prone on the coercion path |

**Key risk**: Plan 16-07's dependency on resolving the `AuditWriter` kind-overload issue at scout time rather than at plan time. If `writeToolCall` stamps `AuditKind.TOOL` in a way that can't be overridden, the two-audit-row-per-failure contract breaks. **Mitigation**: Accept the suggestion to move the overload into Plan 16-01 (Wave 0) where it's scouted once and locked.

**Second-order risk**: The `KnobInventoryScanner` boot-time cost on a production app with 60+ `@ConfigurationProperties` beans. Unlikely to exceed 500ms but could surprise on a constrained deployment. **Mitigation**: Add the prefix filter in Plan 16-06.

---

## Final Verdict

The plans are **ready for execution** after two small adjustments:

1. **Plan 16-01**: Add the `AuditWriter.writeAuditEvent(String kind, ...)` overload alongside `AuditKind.MODEL_VALIDATION_FAILURE` so Plan 16-07 has a stable API.
2. **Plan 16-06**: Scope `KnobInventoryScanner` to beans whose prefix starts with `jmix.ai-agent.` or `ai-agent.` to keep boot-time reflection bounded.

The remaining concerns (UI refresh note, coercion documentation, test-for-classifier) are quality improvements, not blockers.

---

## Codex Review

Review based on the plan text only.

**Key Findings**
- `HIGH`: The Phase 16 plans are directionally strong, but there are three material delivery risks: the upload-knob model is inconsistent across Plans 02/04/06, the settings-change event semantics around active-profile deactivation are underspecified in Plan 05, and Plan 07 does not fully wire the required audit-kind and user-visible fallback notification.
- `MEDIUM`: Verification commands are written as bash pipelines (`grep`, `wc`, process substitution, `test -f`) even though the environment is Windows PowerShell. That makes the automation/verification story brittle.

### `16-01`
**Summary**  
Good foundation plan for locking shared types and test names early, but it intentionally leaves the branch red and has some naming/scaffold drift with later plans.

**Strengths**
- Locks the event, annotation, and audit-kind symbols before downstream waves depend on them.
- Captures the single-publish-site invariant early.
- Keeps production behavior unchanged.

**Concerns**
- `HIGH`: Intentionally failing scaffold tests mean the repo stays red between plans, which is risky for autonomous execution and CI hygiene.
- `MEDIUM`: The scaffold set drifts from later plans; e.g. `AiUiSettingsBeanValidationTest` appears in Plan 02 but is not scaffolded here.
- `MEDIUM`: Verification steps assume a Unix shell, not PowerShell.

**Suggestions**
- Prefer `@Disabled` or a non-default scaffold source set over committed failing tests.
- Align scaffold names/files with later plans now, especially the bean-validation test.
- Normalize verification to Gradle-only or PowerShell-safe commands.

**Risk Assessment**  
`MEDIUM` — solid type-locking, but the “red until later” strategy creates avoidable execution risk.

### `16-02`
**Summary**  
The schema plan is mostly well-structured and additive, but it appears to miss one Tier-1 knob that later plans still treat as migrated.

**Strengths**
- Nullable columns cleanly support DB → property fallback.
- Bean-validation bounds are explicit and tied to documented ranges.
- Liquibase stays additive and preserves the frozen earlier changelog.

**Concerns**
- `HIGH`: The 11-column schema omits `AiTaskFileProperties.maxFileSizeBytes`, while later plans still classify task-file max file size as Tier-1 and route upload limits through the resolver.
- `MEDIUM`: `AI_AUDIT_EVENT.KIND` widening is necessary, but it increases blast radius in a plan otherwise framed as UI-settings schema only.
- `LOW`: Verification references generic tests that may not exist.

**Suggestions**
- Resolve the upload-limit model now: either add a separate task-file max-size column or explicitly declare that only the RAG upload cap is migrated.
- Add one direct boot test that proves the new changelog applies cleanly.
- Keep the plan’s schema inventory exactly synchronized with Plans 04 and 06.

**Risk Assessment**  
`MEDIUM-HIGH` — the schema itself is fine, but the upload-knob mismatch can silently break CFG-01.

### `16-03`
**Summary**  
This is a good catalog plan with a strong drift gate, but it introduces extra policy escape hatches that are not in the spec.

**Strengths**
- The allowlist plus default-model drift test is exactly the right safety rail.
- Property-backed catalog is pragmatic and low-overhead.
- Custom-entry escape hatch preserves operator flexibility without polluting the curated list.

**Concerns**
- `MEDIUM`: The proposed `allow-out-of-allowlist` override is scope creep and weakens the locked “open-weights catalog only” rule.
- `MEDIUM`: Adding `@KnobMetadata` here is premature; these records are not part of the main 10-record inventory.
- `LOW`: The Llama entry depends on a license-policy interpretation that should be explicitly confirmed.

**Suggestions**
- Remove the out-of-allowlist override unless the spec explicitly permits it.
- Keep the curated catalog strict; anything else should go through custom entry only.
- Confirm the project’s exact open-weights licensing bar before finalizing the seed list.

**Risk Assessment**  
`MEDIUM` — strong core design, but small policy-expansion choices could dilute the requirement.

### `16-04`
**Summary**  
The resolver shape is good and the additive caller-swap pattern is sound, but the plan looks under-scouted around actual consumers and likely conflates two different upload limits.

**Strengths**
- Mirrors the existing resolver pattern instead of inventing a new abstraction.
- Preserves null-column behavior as byte-identical fallback to current properties.
- Includes the right sentinel-regression coverage.

**Concerns**
- `HIGH`: `resolveUploadMaxFileSizeBytes()` appears to serve both chat task-file upload and KB/RAG upload, which are not clearly the same setting.
- `MEDIUM`: The caller census is still uncertain (`BuiltInDataTools and/or ToolEntityResolver`), which suggests the code search is incomplete.
- `LOW`: Repeated singleton loads may be noisy until Phase 18 memoization lands.

**Suggestions**
- Split resolver methods if task-file upload max size and RAG upload max size are distinct knobs.
- Finish the caller inventory before execution and make it exact, not “and/or”.
- Add one consumer-level integration test per actual path, not just resolver tests.

**Risk Assessment**  
`HIGH` — wrong knob wiring here would satisfy tests superficially while violating CFG-01 in real UI/runtime paths.

### `16-05`
**Summary**  
The model-picker UI work is well-targeted, but the event-publication semantics have an important edge-case gap and the singleton guard from the context is not carried through.

**Strengths**
- Uses the right Jmix ComboBox/custom-value pattern.
- Single publish-site discipline is strong.
- Source-scan test for event publishers is valuable.

**Concerns**
- `HIGH`: Active-profile deactivation is not covered. If an active `AiParameters` row is changed from `true` to `false`, the current rule may publish zero events even though effective settings changed.
- `MEDIUM`: The plan omits the `AiUiSettings.SINGLETON_ID` guard that the context explicitly called for.
- `MEDIUM`: Reloading `AiParameters` inside `EntityChangedEvent` may be transaction-order sensitive unless tested carefully.

**Suggestions**
- Add a test for `active=true -> false` and decide whether that must invalidate caches.
- Enforce the singleton-id check in `AiUiSettingsEntityListener`.
- Verify Jmix before-commit listener semantics with a focused test instead of assuming the reload sees the saved state.

**Risk Assessment**  
`HIGH` — if event publication is wrong, downstream cache invalidation semantics will be unreliable.

### `16-06`
**Summary**  
This is the most ambitious plan in the phase. It covers the right functional surface, but it also carries the most reflection, binding, and UI-type risk.

**Strengths**
- Strong SEC-08 posture with both source-scan and runtime-shape thinking.
- Clear separation of Tier-1 editable vs Tier-2/Tier-3 display.
- Starter-side scanner placement is reasonable.

**Concerns**
- `HIGH`: The scanner reconstructs property keys manually from prefixes/component names, which is fragile for nested records and relaxed-binding cases.
- `MEDIUM`: The Long-valued UI field types are not fully resolved; the XML still has “verify” placeholders around component choice.
- `MEDIUM`: SEC-08 checks mostly XML `property=` bindings, so programmatic leaks are still possible unless separately tested.

**Suggestions**
- Prefer reading resolved values from bound bean instances plus metadata, not string-built property names.
- Lock the actual Jmix component types before execution; don’t leave that as in-plan uncertainty.
- Add at least one runtime UI/render assertion that no secret value text reaches the DOM.

**Risk Assessment**  
`HIGH` — this is the reflection-heavy/security-heavy plan, so small mistakes can make CFG-02 or SEC-08 only partially true.

### `16-07`
**Summary**  
This plan is aimed at the right chokepoint, but it is not yet complete enough to guarantee MODEL-02 as written.

**Strengths**
- Putting fallback inside `executeBlockingTurn(...)` is correct.
- `fallbackModel()` is the right loop-avoidance design.
- The 5xx false-positive concern is explicitly recognized.

**Concerns**
- `HIGH`: The plan catches only `NonTransientAiException`, but the stated classifier contract also includes direct `RestClientResponseException`.
- `HIGH`: The plan may need `AuditWriter` changes to emit `MODEL_VALIDATION_FAILURE`, but `AuditWriter.java` is not in `files_modified`.
- `HIGH`: User-visible error surfacing is effectively optionalized (“leave keys present for next phase if no wire exists”), which does not meet MODEL-02.
- `MEDIUM`: It assumes the recovered-turn audit row already exists under the same `runId`; that should be verified, not assumed.

**Suggestions**
- Catch a broader runtime exception and classify by cause chain.
- Explicitly add `AuditWriter.java` (or equivalent) to the plan if custom-kind writes are needed.
- Require a concrete notification/status-row implementation in this phase, not a follow-up.
- Add a guard for “fallback model equals offending model” so the behavior is explicit.

**Risk Assessment**  
`HIGH` — as written, this plan can easily ship with partial MODEL-02 compliance.

**Overall Risk Assessment**  
`HIGH` — the phase design is strong, but I would not treat it as execution-ready until the upload-knob model is made consistent, the event invalidation semantics are clarified for active-profile transitions, and Plan 07 is expanded to fully cover audit-kind writing and user-visible fallback/error surfacing.

---

## Consensus Summary

Both reviewers agree the architecture is precedent-grounded and the wave/dependency ordering is correct, but flag the same execution-readiness gap on Plan 16-07's `AuditWriter` integration and identify several distinct delivery risks that should be tightened before execution. Codex assesses overall risk as HIGH; OpenCode as MEDIUM. The merged signal is **HIGH** until the shared `AuditWriter` overload risk and the upload-knob model are resolved.

### Agreed Strengths
- Precedent-driven design — every decision maps to an existing in-tree pattern (resolver, entity listener, audit writer); no new abstractions.
- Test-first scaffolding locks names and contracts in Wave 0; downstream waves flip specific tests green.
- SEC-08 source-scan invariants (no secret bound editable, single publish-site for change event) are enforced at build time, not via review.
- Bad-model classifier explicitly excludes 5xx, avoiding reissue loops on transient failures.
- `fallbackModel()` reads `defaults.model()` directly — correctly avoids the `resolveActive()` self-loop trap.

### Agreed Concerns (HIGH — raised by both reviewers)
- **Plan 16-07 `AuditWriter` integration is under-specified.** OpenCode: the kind-parameter overload may not exist on `AuditWriter` and could force a Plan 01 reshuffle. Codex: `AuditWriter.java` is not in Plan 07's `files_modified`, yet `MODEL_VALIDATION_FAILURE` writes are required. Consensus fix: define the `writeAuditEvent(String kind, ...)` overload in Plan 16-01 (Wave 0) alongside the `AuditKind` constant.

### Reviewer-Specific HIGH Concerns (raised by one reviewer, worth addressing)
- **[opencode] `KnobInventoryScanner` reflects ALL `@ConfigurationProperties` beans** — 200–500ms boot cost on a typical Jmix app; needs a `jmix.ai-agent.` / `ai-agent.` prefix filter.
- **[codex / 16-01] Intentionally failing scaffold tests leave the branch red** between plans — risky for autonomous execution and CI hygiene; prefer `@Disabled` or a non-default scaffold source set.
- **[codex / 16-02] Schema omits `AiTaskFileProperties.maxFileSizeBytes`** while later plans still treat task-file max size as Tier-1 — could silently break CFG-01.
- **[codex / 16-04] `resolveUploadMaxFileSizeBytes()` conflates chat task-file upload and KB/RAG upload limits** — likely two distinct knobs that need separate resolver methods.
- **[codex / 16-05] Active-profile deactivation (`active=true → false`) not covered** — the current entity-listener rule may publish zero events even though effective settings changed; cache invalidation becomes unreliable.
- **[codex / 16-06] `KnobInventoryScanner` reconstructs property keys manually from prefixes/component names** — fragile for nested records and relaxed-binding cases; prefer reading from bound bean instances plus metadata.
- **[codex / 16-07] Classifier catches only `NonTransientAiException`** — the stated contract also includes direct `RestClientResponseException`; broaden the catch and classify by cause chain.
- **[codex / 16-07] User-visible error surfacing is optionalized** ("leave keys present for next phase if no wire exists") — does not meet MODEL-02; needs a concrete notification/status-row implementation in this phase.

### Divergent Views
- **Overall risk rating:** OpenCode → MEDIUM (architecture sound, two adjustments); Codex → HIGH (multiple plans need substantive expansion). The divergence is driven by Codex digging into per-plan delivery completeness while OpenCode focuses on cross-cutting architectural risks.
- **Plan 16-03 catalog `allow-out-of-allowlist` override:** Codex flags as scope creep that weakens the open-weights rule (MEDIUM); OpenCode does not raise it.
- **Plan 16-01 red-branch strategy:** Codex treats committed failing scaffolds as a HIGH process risk; OpenCode treats the test-first chain as a Strength.
- **Verification command portability:** Codex flags bash-only `grep`/`wc`/`test -f` verification commands as brittle on Windows PowerShell (MEDIUM); OpenCode does not mention this.

### Suggested Plan Adjustments Before Execution
1. Move the `AuditWriter.writeAuditEvent(String kind, ...)` overload into Plan 16-01 Wave 0; add `AuditWriter.java` to Plan 16-07's `files_modified`.
2. Resolve the upload-knob model — either add `task_file_max_file_size_bytes` to the Plan 16-02 schema and a dedicated resolver method in Plan 16-04, or explicitly declare in both plans that only the RAG upload cap is migrated.
3. Add an active-profile deactivation test (`active=true → false`) in Plan 16-05 and decide whether that transition must publish `AiSettingsChangedEvent`.
4. Broaden the Plan 16-07 catch to `RuntimeException` (or the documented superclass) and classify via cause-chain walk; require a concrete user-visible fallback notification in this phase.
5. Add a `jmix.ai-agent.` / `ai-agent.` prefix filter to `KnobInventoryScanner` in Plan 16-06; verify property-key reconstruction works for nested records via a runtime assertion.
6. Re-scout Plan 16-04's caller census (replace "and/or" with the exact file list); add the `AiUiSettings.SINGLETON_ID` guard called out in CONTEXT to the Plan 16-05 entity listener.
7. Normalize verification snippets to Gradle-only or PowerShell-safe commands.


