---
phase: 16-admin-settings-model-picker-config-knob-migration
fixed_at: 2026-05-13T16:30:00Z
review_path: .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-REVIEW.md
iteration: 1
findings_in_scope: 10
fixed: 8
skipped: 2
status: partial
---

# Phase 16: Code Review Fix Report

**Fixed at:** 2026-05-13T16:30:00Z
**Source review:** `.planning/phases/16-admin-settings-model-picker-config-knob-migration/16-REVIEW.md`
**Iteration:** 1

**Summary:**
- Findings in scope (Critical + Warning): 10 (3 BLOCKER + 7 WARN)
- Fixed: 8 (3 BLOCKER + 5 WARN)
- Skipped: 2 (WR-02 verification-only; WR-07 cross-phase architectural)

## Fixed Issues

### CR-01: Un-annotated host config under allowed prefix leaks raw value to Tier-2 Boot Config grid

**Files modified:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java`
**Commit:** `fdf12bb`
**Applied fix:** Added a defense-in-depth secret-pattern check at the top of `addComponent(...)`. When the property key matches any pattern from `AdminSecretPatternProperties.resolvedPatterns()`, the method now returns early — suppressing the Tier-2 row entirely. The env-walk pass in `scanEnvironmentForSecrets` still emits the value-less `SecretIndicatorRow`, so the secret remains surfaced as "configured: yes/no" but the raw value never reaches the Boot Config grid. Closes the SPEC criterion 4 gap for the "host forgets `@KnobMetadata(TIER_3)`" scenario.

### CR-02: `writeModelValidationFailureAudit` builds JSON via unescaped string concatenation

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
**Commit:** `1ee5531`
**Applied fix:** Added a `private static final ObjectMapper AUDIT_ARGUMENTS_OBJECT_MAPPER` constant (mirroring the `STRUCTURED_PAYLOAD_OBJECT_MAPPER` pattern in `ToolCallbackAuditDecorator`). `writeModelValidationFailureAudit` now calls `AUDIT_ARGUMENTS_OBJECT_MAPPER.writeValueAsString(Map.of("model", offendingModel))` instead of raw string concatenation, with a `JsonProcessingException` fallback to `"{}"` to preserve the "audit-row failure must NOT break the reissue path" contract. Model ids containing `"`, `\`, or newline now produce valid JSON. Imports updated (`ObjectMapper`). Existing test (`DefaultChatServiceImplModelValidationFallbackTest`) uses `anyString()` for the argumentsJson capture, so contract is preserved.

### CR-03: `KnobInventory` dual-registered via `@Component` + autoconfig `@Import`

**Files modified:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryAutoConfiguration.java`
**Commit:** `6d40f33`
**Applied fix:** Removed `KnobInventory.class` from the autoconfig's `@Import` set, leaving only `KnobInventoryScanner.class`. Verified: `KnobInventory` lives at `com.vn.agent.admin.config` (inside `AIConfiguration.@ComponentScan`'s root `com.vn.agent`) and is `@Component`-annotated, so the component scan registers it. `KnobInventoryScanner` lives at `com.vn.autoconfigure.agent` (outside the scan root), so the autoconfig `@Import` remains the sole registration path for it. Spring Boot 3.x `BeanDefinitionOverrideException` at boot is closed. Javadoc on the autoconfig updated to document why `KnobInventory` is not imported here.

### WR-01: Bean-validation bounds allow values in the documented "invalid gap" range for sentinel knobs

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`
**Commit:** `2175fba`
**Applied fix:** Added three `@Transient @AssertTrue` cross-field range validators on `AiUiSettings`:
- `isTaskFileTtlSecondsRangeValid()` — `{-1} ∪ [60, 604_800]`
- `isTaskFilePerTurnMaxFilesRangeValid()` — `{-1} ∪ [1, 100]`
- `isTaskFilePerTurnMaxTotalBytesRangeValid()` — `{-1} ∪ [1, 524_288_000]`

The plain `@Min`/`@Max` annotations stay as DB-layer bounds; the `@AssertTrue` layer closes the discontinuous-range gap. Tightened the existing en + vi message strings (`taskFileTtl.range`, `taskFilePerTurnMaxFiles.range`, `taskFilePerTurnMaxTotalBytes.range`) so the validation error message reflects the real lower bounds (60/1/1 rather than the previous "0 and …"). Locale parity preserved.

**Note:** This is a behavioral / semantic change to validation. Requires human verification that the lower bounds I picked (60/1/1) match the operator's intent.

### WR-03: `AiParametersEntityListener` re-reads via constrained `DataManager`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java`
**Commit:** `c7d1f86`
**Applied fix:** Swapped `DataManager` for `UnconstrainedDataManager` in the listener's constructor + field (rename, no behavior change to the load fluent chain — `UnconstrainedDataManager.load(Class)` is the supertype API). System-internal saves (Liquibase / system seeder) with no `SecurityContext` no longer silently suppress the cache-eviction event. Mirrors the `AiExposureRuleEntityListener` precedent (Plan 10-06 R2). Test (`AiSettingsChangedEventListenerInvariantTest`) updated to mock the new dependency type — the test mocks the fluent chain (`.load(...).id(...).optional()`) which is present on both interfaces, so all eight existing publish-contract assertions stay green.

### WR-04: `extractBadModelStatus` ignores deeper RCRE inside `NonTransientAiException.getCause()`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
**Commit:** `c0a3322`
**Applied fix:** Introduced a shared `findCausalRcre(Throwable)` helper that performs the same outer-walk + `NonTransientAiException` inner-descent traversal as `isBadModelException`. Refactored `extractBadModelStatus` to delegate to it, fixing the chain shape `RuntimeException → NonTransientAiException → RestClientResponseException` where the linear walker previously returned `-1`. `isBadModelException` itself is unchanged (it additionally filters by `matchesBadModelShape`, which the audit helper doesn't need).

### WR-05: `ChatModelCatalog` non-final mutable state initialized post-construction

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java`
**Commit:** `cda0507`
**Applied fix:** Marked `entries` and `defaultEntry` `volatile`. Wrapped the stream result in `List.copyOf(...)` at the assignment point for explicit immutability. Reordered the assignments so `defaultEntry` is published before `entries` — a reader that observes the new entries list also observes a matching defaultEntry, never a stale one pointing into the previous list. Safe under any future reload hook.

### WR-06: `ParametersDetailView.populateFormFromBody` writes empty string to ComboBox

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java`
**Commit:** `535a557`
**Applied fix:** Removed `nullToBlank(...)` wrapper around `body.model()` on the `modelField.setValue(...)` call. ComboBox handles null cleanly as "no selection"; passing `null` (rather than `""`) lets `required=true` validation prompt the operator to pick one instead of failing on an empty custom value (the previous empty-string was treated as a custom value via `allowCustomValue=true`). `nullToBlank` stays in use for the text fields (`systemPromptField`) where empty string is the correct rendering.

## Skipped Issues

### WR-02: `AiParametersEntityListener` may not publish on DELETED when Jmix doesn't carry attribute values for delete events

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java:70-71`
**Reason:** Skipped — the reviewer's own fix description ("Verify in an integration test (post-boot-regression) that `AttributeChanges.getOldValue` returns the pre-delete `active` value") is verification work, not a concrete source change. The fallback options (`event.getDeletedEntityValue()`) would need API-existence confirmation against the Jmix 2.8 `EntityChangedEvent` shape, which requires `@SpringBootTest` to verify — and the documented Phase 11/13 boot regression blocks that. Re-evaluate when the regression is resolved (per the same prompt context: "Do not attempt to fix that regression as part of this run.").
**Original issue:** `AttributeChanges.getOldValue("active")` may return `null` on DELETED events depending on Jmix listener phase, silently breaking the "DELETED row whose previous active=true" publish invariant.

### WR-07: ChatPanelFragment `onChatModelFallbackApplied` is `@EventListener` on a Vaadin prototype-scoped fragment

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:417-429`
**Reason:** Skipped per the reviewer's own guidance ("The Phase 15 sidebar chat surface uses a similar pattern (`onAiTaskFileDeleted`) — verify the same pattern is applied consistently. If it isn't, this is a pre-existing condition; document and defer."). The fix options ((a) singleton bridge bean or (b) manual register/unregister via fragment `onAttach`/`onDetach`) are architectural changes spanning Phase 15's `onAiTaskFileDeleted` AND Phase 16's `onChatModelFallbackApplied` — they belong in a dedicated fragment-lifecycle phase, not as a Phase 16 review-fix. Documented for cross-phase backlog.
**Original issue:** Spring registers each fragment instance's `@EventListener` with the application event multicaster; Vaadin's `Fragment` lifecycle does not auto-remove the registration on detach, accumulating listener references across navigations.

---

_Fixed: 2026-05-13T16:30:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
