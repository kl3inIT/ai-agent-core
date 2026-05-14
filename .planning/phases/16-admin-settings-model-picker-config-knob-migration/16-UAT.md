---
status: testing
phase: 16-admin-settings-model-picker-config-knob-migration
source:
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-01-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-02-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-03-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-04-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-05-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-06-SUMMARY.md
  - .planning/phases/16-admin-settings-model-picker-config-knob-migration/16-07-SUMMARY.md
started: 2026-05-14T09:24:26+07:00
updated: 2026-05-14T09:37:00+07:00
---

## Current Test

[testing complete — diagnosis required for blockers in tests 4 + 12]

## Tests

### 1. Cold Start Smoke Test
expected: Stop any running app, run `./gradlew bootRun` clean. Boot succeeds, Liquibase 120-* applies (12 new AI_UI_SETTINGS columns + AI_AGENT_AUDIT_EVENT.KIND widened to varchar(32)), ChatModelCatalog @PostConstruct passes, KnobInventoryScanner runs at ApplicationReadyEvent, login at http://localhost:8088 as admin/admin succeeds.
result: pass
evidence: |
  - `Started JmixAppApplication in 42.686 seconds`
  - `liquibase.command: Command execution complete` (no migration errors)
  - `c.v.a.agent.KnobInventoryScanner: Knob scanner: tier1=13 tier2=62 tier3=3 records.`
  - `Tomcat started on port 8088 (http)`
  - Login as admin/admin succeeded; menu rendered.

### 2. Model Picker ComboBox in Parameters Detail
expected: Navigate to AI Agent → Parameters → open/create a profile. The `model` field is now a ComboBox (not a text field) populated with 4 entries: `qwen/qwen3.6-35b-a3b` (marked default), `meta-llama/llama-3.3-70b-instruct`, `mistralai/mistral-small-3.1-24b-instruct`, `deepseek/deepseek-v3.1`. Helper text mentions custom values are allowed.
result: pass
note: |
  ComboBox renders correctly with 4 entries and the "(mặc định)" default suffix in VI. Helper text appears:
  "Chọn một mô hình từ danh mục được tuyển chọn, hoặc nhập id mô hình tùy ý."
  
  Catalog content has **drifted from the SUMMARY** — current entries are:
    - Qwen3.6 35B A3B (đa phương thức, offline) — default
    - Qwen3 32B (offline, lập luận)
    - Claude Sonnet 4.6 (online, đa năng)
    - Claude Opus 4.7 (online, lập luận)
  
  This aligns with the updated project memory `project_self_hostable_models_only.md` ("addon mixes Qwen (offline) + Claude (online via OpenRouter)") — likely the result of a follow-on edit after Plan 16-03 shipped. The deliverable (curated catalog + ComboBox) is fully present; the specific ids in the SUMMARY's expectation list are stale.

### 3. Custom Model Value Persists
expected: In the Parameters model ComboBox, type a custom id (e.g. `openai/gpt-4o`), press Enter or tab away. The typed value stays in the field (does NOT drop on blur) and the YAML preview reflects it. Save the profile and reopen — the custom value persists.
result: pass
evidence: |
  Typed `openai/gpt-4o-uat-custom`, pressed Enter, clicked Save. Returned to list view; grid shows `default → openai/gpt-4o-uat-custom`. Custom value persisted through round trip. (After UAT, reverted the active profile back to `anthropic/claude-sonnet-4-6` from the catalog so the system is back in a working state.)

### 4. AiUiSettings — Four Tabs Visible
expected: Navigate to AI Agent → AI UI Settings. Four tabs visible: General, Tier-1 Knobs, Boot Config, Secrets. The General tab shows existing chat-surface controls byte-identical to before phase 16.
result: issue
reported: |
  Navigating to /ai-agent/ui-settings throws `java.lang.IllegalArgumentException: MetaClass not found for class com.vn.agent.admin.config.KnobInventory$KnobRow` at io.jmix.core.metamodel.model.impl.SessionImpl.getClass(SessionImpl.java:63), invoked from DataComponentsLoaderSupport.loadCollectionContainer. The "Unexpected error" dialog blocks the view. AI UI Settings is unreachable.
severity: blocker
root_cause: |
  The Plan 16-06 descriptor at ai-ui-settings-detail-view.xml lines 10-11 declares:
    <collection id="bootConfigDc" class="com.vn.agent.admin.config.KnobInventory$KnobRow"/>
    <collection id="secretsDc"     class="com.vn.agent.admin.config.KnobInventory$SecretIndicatorRow"/>
  
  Both target classes are nested plain Java records inside KnobInventory.java (lines 41, 49). They are NOT annotated `@JmixEntity`, so the Jmix metamodel (Session.getClass) cannot resolve them at view-init time and throws IllegalArgumentException, breaking the whole view. The Plan 06 scaffold tests were pure-JUnit reflective walks that never booted the view, so this never surfaced in test.
fix_options: |
  Pick one (in order of least surprise):
    a) Annotate both nested records with `@JmixEntity` + add `@JmixId` UUID accessors (turns them into DTO entities the metamodel can resolve). This is the smallest diff and matches how other read-only DTO grids in the codebase are wired.
    b) Replace the `<collection>` containers with programmatic DataGrid binding in the controller (`bootConfigGrid.setItems(knobInventory.tier2())`). The descriptor would drop the data containers entirely and the grid would still render but without Jmix data-loader infrastructure.
    c) Move the records to a JmixEntity DTO class under `com.vn.agent.admin.config.dto.*` and reference the DTO from the descriptor.

### 5. Tier-1 Knobs Tab — 5 Sections × 12 Fields
expected: Tier-1 Knobs tab with 5 sections (taskFile, mutation, promptTools, title, upload) and 12 typed fields.
result: blocked
blocked_by: test-4
reason: AI UI Settings view crashes on open — Tier-1 Knobs tab is unreachable.

### 6. Tier-1 Edit — Bulk Max Rows Range Validation
expected: 0 and 501 rejected with validation error; 100 saves cleanly.
result: blocked
blocked_by: test-4
reason: Cannot reach the form. Hibernate Validator bounds are unit-tested green (AiUiSettingsBeanValidationTest); the runtime UI path is blocked.

### 7. Tier-1 Edit — TTL Sentinel -1 Accepted
expected: -1 accepted (sentinel), -2 rejected.
result: blocked
blocked_by: test-4
reason: Cannot reach the form.

### 8. Tier-1 Edit Takes Effect Without Restart
expected: bulkMaxRows edit applies on next mutation call without restart.
result: blocked
blocked_by: test-4
reason: Cannot reach the form to perform the edit. AiUiSettingsResolverReadThroughTest covers the read-through contract at unit level.

### 9. Boot Config Tab — Read-Only Grid
expected: Tier-2 entries listed with badge column; no editable inputs.
result: blocked
blocked_by: test-4
reason: View crashes on open. KnobInventoryScanner emitted `tier2=62 records` at boot so the data is populated server-side; only the rendering pipeline is broken.

### 10. Secrets Tab — Indicator Only, No Raw Values
expected: yes/no indicator only, no raw secret values in DOM.
result: blocked
blocked_by: test-4
reason: View crashes on open. Scanner emitted `tier3=3 records`; data exists server-side.

### 11. Locale Parity — EN/VI Toggle
expected: All four tabs / fields / badges / indicators display in VI.
result: blocked
blocked_by: test-4
reason: VI is the active locale and Parameters / Audit / Configuration views all render localized strings cleanly (no raw `msg://` keys observed). Locale parity inside the AI UI Settings view itself could not be verified because the view crashes.

### 12. Bad-Model Auto-Fallback — Toast + Audit
expected: Setting an obviously-bad model id triggers one-shot fallback, yellow Notification "Used default model for this turn." appears, MODEL_VALIDATION_FAILURE audit row written.
result: issue
reported: |
  Set the active profile's model to `openai/gpt-4o-uat-custom` (a fake id). Sent a chat message. Result: generic "lỗi: Đã xảy ra lỗi. Vui lòng thử lại." surfaced. No yellow fallback toast appeared. No MODEL_VALIDATION_FAILURE row appeared in the Audit Events view — only a plain CHAT row with result "Lỗi".
severity: major
root_cause: |
  Two compounding gaps:
  
  1. **Streaming path is intentionally NOT wrapped** — Plan 16-07 SUMMARY's "Deferred Issues" section documents that `DefaultChatServiceImpl.stream(...)` does not invoke the bad-model classifier and is scoped only to the BLK-01 chokepoint (`executeBlockingTurn`). The user-facing chat surface uses the streaming path, so the toast can never fire from a chat message.
  
  2. **Classifier matches the wrong exception type** — the boot log shows the actual exception thrown by Spring AI for a 400 Bad Request on this stack is `org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest`. Plan 16-07's classifier `isBadModelException` walks the cause chain looking for `RestClientResponseException` or `NonTransientAiException` wrapping one. `WebClientResponseException` is a sibling class in a different package — not a subclass of `RestClientResponseException`. So even on the blocking path the classifier would not match, and the catch+reissue would never fire on this WebClient-backed provider (OpenRouter via Spring AI's reactive client).
  
  The Plan 16-07 unit tests fed `RestClientResponseException` directly, so they pass; production with WebClient sees a different exception and slips past.
fix_options: |
  a) Extend `isBadModelException` to ALSO recognize `org.springframework.web.reactive.function.client.WebClientResponseException` (status method is `getStatusCode().value()` — same shape).
  b) Decide whether to wrap the streaming path with the same classifier (`Flux.onErrorResume` at the BLK-01 sibling site) so the toast can fire from the user's chat surface — currently it can only fire for non-streaming entry points the user doesn't see directly.
  c) Until (a) ships, the entire MODEL-02 catch+reissue feature is effectively dead code on this stack — worth flagging in the SUMMARY's Deferred Issues.

### 13. Saved Profile Not Mutated by Fallback
expected: Bad-model profile field is unchanged after the chat error.
result: pass
note: |
  After the bad-model chat error in test 12, reopened Parameters → profile's `model` field still showed `openai/gpt-4o-uat-custom` verbatim. Saved YAML was not mutated. This holds even though the fallback never fired (test 12) — the streaming error path also does not touch the saved AiParameters row.

### 14. Non-Bad-Model Errors Still Propagate
expected: Non-model errors flow through the generic chat error path without spurious retry.
result: skipped
reason: |
  Cannot easily inject a 5xx without changing infrastructure. The 400 response from test 12 already proves no spurious retry happened (generic error surfaced once, no second call observed in logs). The classifier's `Set.of(400, 404, 422)` plus 5xx-pass-through is covered by the existing `_5xxResponseDoesNotTriggerReissue` unit test in DefaultChatServiceImplModelValidationFallbackTest. Defer to that unit coverage.

### 15. Phase 13.1 Sentinel — Task File Cleanup Skips Under -1
expected: Setting taskFileTtlSeconds=-1 disables cleanup; clearing it resumes.
result: blocked
blocked_by: test-4
reason: Cannot reach the Tier-1 Knobs form to perform the edit. The resolver-level sentinel pass-through is covered by AiUiSettingsResolverReadThroughTest + TtlConfigSentinelSurvivesAiUiSettingsTest unit tests (Plan 04).

### 16. Admin-Only Access
expected: Non-admin user cannot reach AI UI Settings / Parameters.
result: skipped
reason: |
  Not exercised in this UAT pass — would require provisioning a non-admin user and re-login. The view-policy plumbing on `AiAgentAdminRole` is unchanged from prior phases and the new view ID (`AiAgent_AiUiSettings.detail`) inherits the existing `@ViewPolicy` gate per the SUMMARY's Threat Surface Scan.

## Summary

total: 16
passed: 4
issues: 2
pending: 0
skipped: 2
blocked: 8

## Gaps

- truth: "AI UI Settings view renders four tabs (General, Tier-1 Knobs, Boot Config, Secrets) and lets admins inspect runtime knobs / boot config / secret indicators."
  status: failed
  reason: "View crashes on open: IllegalArgumentException: MetaClass not found for class com.vn.agent.admin.config.KnobInventory$KnobRow. The descriptor binds <collection> data containers to nested plain Java records that are not registered Jmix entities."
  severity: blocker
  test: 4
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java:41
    - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java:49
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml:10
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml:11
  missing:
    - "@JmixEntity (or equivalent DTO registration) on KnobInventory$KnobRow + SecretIndicatorRow, OR programmatic DataGrid.setItems(...) in onInit instead of <collection> containers"

- truth: "Bad-model errors trigger a one-shot reissue against defaults.model() and surface a user-visible fallback Notification + MODEL_VALIDATION_FAILURE audit row."
  status: failed
  reason: "User typed a fake model id and sent a chat message. Got generic 'lỗi' error. No fallback toast. No MODEL_VALIDATION_FAILURE audit row. Two root causes: (1) streaming chat path is not wrapped by the classifier (acknowledged-deferred in the SUMMARY); (2) actual exception is WebClientResponseException — Plan 16-07's classifier only recognizes RestClientResponseException / NonTransientAiException, so even the blocking-path catch+reissue would not fire on this WebClient-backed stack."
  severity: major
  test: 12
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplModelValidationFallbackTest.java
  missing:
    - "WebClientResponseException recognition in isBadModelException cause-chain walker"
    - "Decision on whether to also wrap the streaming Flux with the classifier so the toast can fire from the user's chat surface (today it can't)"
