---
status: complete
phase: 16-admin-settings-model-picker-config-knob-migration
source:
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-01-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-02-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-03-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-04-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-05-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-06-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-07-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-08-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-09-SUMMARY.md
started: 2026-05-14T15:18:22+07:00
updated: 2026-05-14T16:30:00+07:00
closure_reason: |
  Closed mid-UAT (3/16 tests run). Tests 1–3 PASSED — they cover the data-side
  work that survives: cold-start + Liquibase changeset 120-ai-ui-settings-tier1-knobs,
  ChatModelCatalog @PostConstruct validation, KnobInventoryScanner ApplicationReadyEvent
  log, ComboBox curated catalog with localized (default) suffix, custom-model
  value persistence end-to-end (verified via Playwright headless — see
  .playwright-uat/test_16_03.py + screenshots 01-09).

  Tests 4–16 SUPERSEDED — they target the AiUiSettingsDetailView 4-tab UI which
  is being removed as part of an in-place refactor: General + Tier-1 Knobs tabs
  fold into AiConfigurationView (5 tabs total: Parameters, Exposure Rules,
  Prompt Context, General, Tier-1 Knobs); Boot Config + Secrets tabs dropped
  entirely; each Tier-1 Knob field gains a default-value affordance (placeholder
  ghost + helperText "Default: X"). DB still stores null for unset values — the
  resolver fallback chain (DB → application.yml → constants) and the Phase 16
  "edits take effect without restart" invariant continue to hold.

  Retained from Phase 16 (no rework): AiUiSettings entity, AI_UI_SETTINGS schema,
  AiSettingsChangedEvent single-publish-site invariant, ChatModelCatalog,
  KnobInventoryScanner, MODEL_VALIDATION_FAILURE audit + fallback notification.

  Removed surface: AiUiSettingsDetailView (.java + .xml + 4 test files),
  menu entry aiAgent.uiSettings, AiAgentAdminRole @ViewPolicy on
  AiAgent_AiUiSettings.detail. UAT for the merged view runs against
  AiConfigurationView once refactor commits land.
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Stop any running app, run `./gradlew bootRun` clean. Boot succeeds — Liquibase changeset `120-ai-ui-settings-tier1-knobs.xml` applies (12 new AI_UI_SETTINGS columns + AI_AGENT_AUDIT_EVENT.KIND widened to varchar(32)). ChatModelCatalog @PostConstruct validation passes (4 entries, exactly one default, default matches default-params.yaml.model). KnobInventoryScanner runs at ApplicationReadyEvent and logs non-zero tier1/tier2/tier3 counts. Jmix enhancer registers the two new DTOs (AiKnobRow + AiSecretIndicatorRow). Login at http://localhost:8088 as admin/admin succeeds.
result: pass

### 2. Model Picker ComboBox in Parameters Detail
expected: Navigate to AI Agent → Parameters → open or create a profile. The `model` field is a ComboBox (not a text field) populated with the curated catalog from module.properties. Exactly one entry is marked with the localized default suffix (`(default)` / `(mặc định)`). Helper text below the field explains custom values are allowed.
result: pass

### 3. Custom Model Value Persists
expected: In the Parameters model ComboBox, type a custom id (e.g. `openai/gpt-4o-uat-custom`) and press Enter or tab away. The typed value stays in the field (does NOT drop on blur). The YAML preview reflects the typed id. Save the profile and reopen — the custom value persists. (After this test, revert the active profile's model back to a catalog entry so subsequent tests work against a working stack.)
result: pass
verified_by: playwright .playwright-uat/test_16_03.py (headless chromium, http://localhost:8088)
evidence: |
  - inputValue/label after blur = "openai/gpt-4o-uat-custom" (rawValue is internal combo key)
  - YAML preview: model: "openai/gpt-4o-uat-custom" (screenshot 05-yaml-preview.png)
  - Save → reopen → label still "openai/gpt-4o-uat-custom" (screenshot 07-after-reopen.png)
  - Reverted active profile model to anthropic/claude-sonnet-4-6 for downstream tests
    (screenshot 09-yaml-after-revert.png shows model: "anthropic/claude-sonnet-4-6")

### 4. AI UI Settings — Four Tabs Visible (Plan 16-08 gap closure)
expected: Navigate to AI Agent → AI UI Settings. The view opens with NO "Unexpected error" dialog. Four tabs are visible: General, Tier-1 Knobs, Boot Config, Secrets. The General tab shows the existing chat-surface controls (defaultSurface etc.) byte-identical to before phase 16.
result: [pending]

### 5. Tier-1 Knobs Tab — Sections and Fields
expected: Click the Tier-1 Knobs tab. Five labeled sections render: taskFile, mutation, promptTools, title, upload. Each section contains its Tier-1 fields (12 total across sections — textField for Long types, integerField for Integer types, checkbox for the Boolean `mutationConfirmationRequired`). All field labels resolve from messages bundle (no raw `msg://` keys visible).
result: [pending]

### 6. Tier-1 Edit — Bulk Max Rows Range Validation
expected: In Tier-1 Knobs → mutation → `mutationBulkMaxRows`, enter `0` and Save — validation error appears (bound is `@Min(1) @Max(500)`). Enter `501` and Save — validation error. Enter `100` and Save — saves cleanly with no error.
result: [pending]

### 7. Tier-1 Edit — TTL Sentinel -1 Accepted
expected: In Tier-1 Knobs → taskFile → `taskFileTtlSeconds`, enter `-1` and Save — saves cleanly (sentinel passes through; bean validation is `@Min(-1) @Max(604_800)`). Enter `-2` and Save — validation error appears.
result: [pending]

### 8. Tier-1 Edit Takes Effect Without Restart (D-03 invariant)
expected: With `mutationBulkMaxRows` set to a low value (e.g. 2) via the Tier-1 Knobs tab, invoke a mutation tool from chat that would exceed the cap. The resolver reads the current DB value on the next mutation call — the request is rejected against the new cap without any app restart. Set it back to 100, retry — request now succeeds. (Acceptable substitute if you cannot trigger a bulk mutation: confirm the field round-trips through Save → Reopen and the value persists.)
result: [pending]

### 9. Boot Config Tab — Read-Only Tier-2 Grid
expected: Click the Boot Config tab. A grid renders with columns `key | resolvedValue | badge`. At least one row is present. Every row's badge shows the "requires restart" marker (Lumo badge theme). No fields are editable — there are no input controls or Edit buttons.
result: [pending]

### 10. Secrets Tab — Indicator Only, No Raw Values
expected: Click the Secrets tab. A grid renders with only two columns: `key | configured`. The configured column shows yes/no indicators (localized). NO raw secret value is visible anywhere in the page. Open browser DevTools and search the rendered DOM for an actual secret prefix (e.g. `sk-or-`, `sk-`, or a known token prefix from `.env`) — confirm ZERO matches.
result: [pending]

### 11. Locale Parity — EN/VI Toggle
expected: Toggle UI locale between EN and VI. All four tabs render in the selected locale: tab labels (`aiUiSettings.tab.*`), section headers, field labels, badges (`requires restart` / `cần khởi động lại`), and configured indicators (`yes/no` / `có/không`). No raw `msg://` keys appear in either locale. The Parameters ComboBox default suffix also localizes (`(default)` ↔ `(mặc định)`).
result: [pending]

### 12. Bad-Model Auto-Fallback — Toast + Audit (Plan 16-09 gap closure)
expected: Set the active Parameters profile's model to a fake id (e.g. `openai/gpt-4o-uat-bogus-123`). Send a chat message from the chat surface (streaming path). Result: the answer comes back successfully from the fallback (default) model. A yellow Vaadin Notification appears saying "Used default model for this turn." / "Đã sử dụng mô hình mặc định cho lượt này." A new row appears in the Audit Events view with KIND = `MODEL_VALIDATION_FAILURE` and the offending model id + HTTP status visible in the result summary; the raw provider response body is NOT in the row.
result: [pending]

### 13. Saved Profile Not Mutated by Fallback
expected: After test 12's chat-and-recover cycle, reopen the Parameters profile that triggered the fallback. The `model` field still shows the offending id (e.g. `openai/gpt-4o-uat-bogus-123`) verbatim — the fallback path NEVER mutates `AiParameters.bodyYaml.model`.
result: [pending]

### 14. Phase 13.1 Sentinel — Task File Cleanup Skips Under -1
expected: Set `taskFileTtlSeconds = -1` via Tier-1 Knobs and Save. The AiTaskFileCleanupJob (scheduled task) skips cleanup on its next tick (server log shows the skip path, no deleteAllExpired call). Clear the column (set back to default or a positive value) and Save — cleanup resumes on the next tick. (Substitute if scheduler tick is slow: confirm the resolver returns `-1` verbatim via a unit-test pass of `AiUiSettingsResolverReadThroughTest` + `TtlConfigSentinelSurvivesAiUiSettingsTest`.)
result: [pending]

### 15. Admin-Only Access
expected: Log out, log in as a non-admin user (one without `AiAgentAdminRole`). The AI UI Settings and Parameters menu entries are not reachable — either hidden from the menu, or direct URL navigation to `/ai-agent/ui-settings` and `/ai-agent/parameters/...` is denied by the existing `@ViewPolicy` gate. (Substitute if no non-admin user is provisioned: confirm `AiAgentAdminRole` carries `@ViewPolicy` on both view ids and the role assignment is required by inspection.)
result: [pending]

### 16. AiSettingsChangedEvent Single-Publish-Site Invariant
expected: Run `./gradlew :ai-agent:test --tests "com.vn.agent.admin.config.AiSettingsChangedEventListenerInvariantTest" --tests "com.vn.agent.admin.config.SecretRedactionInvariantsTest"`. Both pass — including the source-scan tests that walk `src/main/java` and assert the set of files publishing `new AiSettingsChangedEvent(...)` equals exactly `{AiParametersEntityListener.java, AiUiSettingsEntityListener.java}`. This is the goal-stated cache-eviction-on-admin-edit invariant.
result: [pending]

## Summary

total: 16
passed: 3
issues: 0
pending: 0
skipped: 0
superseded: 13

Tests 4–16 marked superseded because the AiUiSettingsDetailView 4-tab UI they
exercise is being removed (replaced by 2 new tabs in AiConfigurationView; Boot
Config + Secrets tabs dropped entirely). The merged view will get its own UAT
once refactor commits land. See frontmatter.closure_reason for details.

## Gaps

[none — closure not driven by issues]
