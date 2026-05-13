---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 05
subsystem: admin-config
tags: [jmix-views, jmix-i18n, event-publication, model-picker]
requires:
  - AiSettingsChangedEvent (Plan 16-01)
  - ChatModelCatalog @Component (Plan 16-03)
  - AiAgentAdminRole @ViewPolicy on parameters-detail-view (existing — Phase 07)
provides:
  - AiParametersEntityListener (single publish site, Kind.PARAMETERS, effective-settings-transition guard)
  - AiUiSettingsEntityListener (single publish site, Kind.UI_SETTINGS, SINGLETON_ID guard)
  - parameters-detail-view modelField as <comboBox allowCustomValue=true>
  - ParametersDetailView wired with catalog setItems + setItemLabelGenerator + CustomValueSetEvent
  - 6 model-picker locale keys per D-09 in messages_en + messages_vi
  - AiSettingsChangedEventListenerInvariantTest green (11 methods, includes single-publish-site source scan)
affects:
  - ParametersDetailView (modelField type swap + new @Subscribe for CustomValueSetEvent)
  - parameters-detail-view.xml (modelField element swap textField → comboBox)
  - messages_en.properties / messages_vi.properties (additive — 6 new D-09 keys each)
tech_stack_added: []
patterns_used:
  - "Pattern A: ApplicationEventPublisher single-publish-site entity listener (verbatim from AiExposureRuleEntityListener)"
  - "Pattern 4 (RESEARCH §5): Vaadin ComboBox + allowCustomValue=true + CustomValueSetEvent → setValue wire"
  - "Rule 3 — pure-JUnit + Mockito workaround for pre-existing Phase 11/13 Spring context boot regression (consistent with Plans 13.1-06/07, 14-01/02, 16-01/02/03/04)"
  - "Effective-settings-transition guard (CREATED/UPDATED/DELETED + active∈{true,false} sub-cases) per RESEARCH §4 + codex HIGH Concern #6"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiUiSettingsEntityListener.java
key_files_modified:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java
decisions:
  - "AiParametersEntityListener publishes on the full effective-settings-transition surface (CREATED-active, DELETED-was-active, UPDATED-still-active, UPDATED-activation, UPDATED-deactivation) per codex HIGH Concern #6. The DEACTIVATION case (active=true → false) was missing from the pre-review plan draft; including it is required so Phase 18 caches invalidate when the previously-active profile transitions away."
  - "AiUiSettingsEntityListener enforces the SINGLETON_ID guard as the FIRST line of the listener body (codex MEDIUM Concern #6). Non-singleton writes — host-extension or accidental — are silently ignored. No EntityChangedEvent.Type filter is needed because the singleton schema seals creation; admin-edit is the only intended write path and ALL transitions on that row are effective."
  - "Rule 3 — auto-fix blocking issue: AiSettingsChangedEventListenerInvariantTest uses pure-JUnit + Mockito instead of @SpringBootTest + @RecordApplicationEvents. The ai-agent module's Spring context boot is blocked by the pre-existing Phase 11/13 regression (atmosphere-runtime / agentstoreEntityManagerFactory); Plans 13.1-06/07, 14-01/02, 16-01/02/03/04 all used the same workaround. Contract preserved exactly — the eight publish-contract methods port unchanged to @RecordApplicationEvents once the regression is fixed."
  - "Use ApplicationEvent ArgumentCaptor (NOT Object) in the test helper to disambiguate ApplicationEventPublisher's two overloads (publishEvent(Object) vs publishEvent(ApplicationEvent)). Mockito's verify routes to the overload matching the captor type; capturing as Object misses the ApplicationEvent-overload invocations."
metrics:
  duration: ~25 min
  tasks_completed: 3
  files_created: 2
  files_modified: 5
  completed_date: 2026-05-13
---

# Phase 16 Plan 05: Entity Listeners + Model-Picker ComboBox Summary

Ship the two single-publish-site entity listeners (`AiParametersEntityListener` publishing `Kind.PARAMETERS` only on effective-settings transitions, `AiUiSettingsEntityListener` publishing `Kind.UI_SETTINGS` on every singleton save) and swap `parameters-detail-view.xml` `modelField` from `<textField>` to `<comboBox allowCustomValue=true>` populated from the just-shipped `ChatModelCatalog`. `AiSettingsChangedEventListenerInvariantTest` flips green with 11 method bodies — including the build-time single-publish-site source scan that fails the build if any view/service injects `ApplicationEventPublisher` for this event.

## What Shipped

### Task 1 — Two entity listeners (commit `49b31ec`)

- `com.vn.agent.admin.config.AiParametersEntityListener` (`@Component`) — injects `ApplicationEventPublisher` + `DataManager`; single `@EventListener` method dispatches on `EntityChangedEvent.Type`:
  - **DELETED**: publish IFF `getChanges().getOldValue("active") == TRUE`.
  - **CREATED**: re-read the saved row and publish IFF `getActive() == TRUE`.
  - **UPDATED** (three branches per RESEARCH §4 + codex HIGH Concern #6):
    - `active` not in change set → publish IFF current row is `active=true` (some non-active attribute of the effective profile changed).
    - `active` flipped → publish IFF the row was-active-before OR is-active-now (activation OR deactivation).
    - inactive → inactive update on a non-active attribute → do NOT publish (no effective-settings transition).
  - Pure publisher: no `DataManager.save` / `DataManager.remove`, no Vaadin imports.
- `com.vn.agent.admin.config.AiUiSettingsEntityListener` (`@Component`) — injects only `ApplicationEventPublisher`; single `@EventListener` method:
  - Enforces `AiUiSettings.SINGLETON_ID.equals(event.getEntityId().getValue())` as the FIRST line (codex MEDIUM Concern #6 singleton-id guard).
  - Publishes `AiSettingsChangedEvent(this, Kind.UI_SETTINGS)` on every singleton-id save.
- Both class-level Javadocs declare the Plan 10-06 R2 single-publish-site invariant and reference the source-scan test that enforces it at build time.

### Task 2 — ComboBox swap + D-09 locale keys (commit `7246b04`)

- `parameters-detail-view.xml` — `<textField id="modelField" ...>` replaced with:
  ```xml
  <comboBox id="modelField"
            label="msg:///parametersDetail.field.model"
            allowCustomValue="true"
            required="true"
            requiredMessage="msg:///parametersDetail.validation.modelRequired"
            helperText="msg:///parametersDetail.modelField.customValueHint"/>
  ```
  No other element touched. `git diff` is contained to this 4 → 6-line swap.
- `ParametersDetailView.java`:
  - Field type `TypedTextField<String> modelField` → `JmixComboBox<String> modelField` (with `@ViewComponent`).
  - New `@Autowired private ChatModelCatalog chatModelCatalog;` field.
  - `onInit` wires `setItems(catalog.entries().stream().map(Entry::id).toList())` and an `setItemLabelGenerator` that resolves `labelMessageKey` via `Messages` and appends `parametersDetail.modelField.defaultSuffix` on the marked-default entry; custom-entered ids render verbatim.
  - New `@Subscribe("modelField") onModelFieldCustomValueSet(ComboBoxBase.CustomValueSetEvent<ComboBox<String>>)` (RESEARCH Pattern 4 — Vaadin's `CustomValueSetEvent` does NOT call `setValue` automatically; without this wire, typed values drop on blur).
  - Existing `onModelFieldChange` `ComponentValueChangeEvent` handler left untouched — `refreshYamlPreview()` fires both on catalog selection and on the `setValue` triggered by `onModelFieldCustomValueSet`.
- 6 locale keys appended to both `messages_en.properties` and `messages_vi.properties` per D-09:
  - `parametersDetail.modelField.customValueHint`
  - `parametersDetail.modelField.defaultSuffix`
  - `chatModelCatalog.qwen3_35b`
  - `chatModelCatalog.llama3_3_70b`
  - `chatModelCatalog.mistral_small_3_1`
  - `chatModelCatalog.deepseek_v3_1`
  Model names are proper nouns — Vietnamese values are identical to English except for `defaultSuffix` (`(mặc định)`) and `customValueHint` body text.

### Task 3 — Invariant test flipped green (commit `53d4657`)

11 method bodies replace the Wave-0 `fail()` scaffolds (the plan called for 9; the additional 2 are boundary tests added for completeness — `inactiveDeletedRowOldActiveFalsePublishesZero` and `activationFromInactiveToActivePublishesPARAMETERS`):

- **AiParameters publish contract (8 methods):**
  - `activeParametersSavePublishesPARAMETERS` — CREATED, active=true → 1 event.
  - `inactiveParametersSavePublishesZero` — CREATED, active=false → 0 events.
  - `inactiveDeletedRowOldActiveTrueStillPublishes` — DELETED, oldActive=true → 1 event.
  - `inactiveDeletedRowOldActiveFalsePublishesZero` — DELETED, oldActive=false → 0 events.
  - `deactivationFromActiveToInactivePublishesPARAMETERS` — UPDATED, active flipped true→false → 1 event (codex HIGH #6).
  - `inactiveToInactiveUpdatePublishesZero` — UPDATED, non-active attribute, row stays inactive → 0 events.
  - `activeUpdateOnNonActiveAttributePublishesPARAMETERS` — UPDATED, non-active attribute, row stays active → 1 event.
  - `activationFromInactiveToActivePublishesPARAMETERS` — UPDATED, active flipped false→true → 1 event.
- **AiUiSettings publish contract (2 methods):**
  - `uiSettingsSavePublishesUI_SETTINGS` — singleton-id save → 1 event.
  - `uiSettingsNonSingletonIdSavePublishesZero` — non-singleton-id save → 0 events (codex MEDIUM #6 guard).
- **Source-scan invariant (1 method):**
  - `singlePublishSiteSourceScan` — walks `src/main/java`, collects every `.java` file matching the regex `publishEvent\s*\(\s*new\s+AiSettingsChangedEvent` (`Pattern.DOTALL` for multiline calls), asserts the file-name set equals `{AiParametersEntityListener.java, AiUiSettingsEntityListener.java}`. Plan 10-06 R2 invariant — a future plan trying to publish this event from a view/service fails this test at build time.

Class-level `@Disabled` + `@Tag("phase-16-scaffold")` removed; runs as `@Tag("unit")`.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:compileJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.admin.config.AiSettingsChangedEventListenerInvariantTest"
→ BUILD SUCCESSFUL (11 methods green)
```

Plan verify-section checks:

- `grep -c "publishEvent\\(new AiSettingsChangedEvent" AiParametersEntityListener.java` → 1 (regex `publishEvent\s*\(\s*new\s+AiSettingsChangedEvent` matches multiline call).
- `grep -c "publishEvent\\(new AiSettingsChangedEvent" AiUiSettingsEntityListener.java` → 1.
- Neither listener references `DataManager.save` / `DataManager.remove`.
- `grep -c "Kind\\.PARAMETERS" AiParametersEntityListener.java` → 2 (one literal use, one Javadoc reference).
- `grep -c "Kind\\.UI_SETTINGS" AiUiSettingsEntityListener.java` → 2.
- `grep -c '<comboBox id="modelField"' parameters-detail-view.xml` → 1.
- `grep -c 'allowCustomValue="true"' parameters-detail-view.xml` → 1.
- `grep -c '<textField id="modelField"' parameters-detail-view.xml` → 0 (old element fully removed).
- `grep -c "JmixComboBox<String> modelField" ParametersDetailView.java` → 1.
- `grep -c "setItemLabelGenerator" ParametersDetailView.java` → 1.
- `grep -c "CustomValueSetEvent" ParametersDetailView.java` → 2 (import + method signature).
- `grep -cE "^parametersDetail\\.modelField\\.|^chatModelCatalog\\." messages_en.properties` → 6.
- `grep -cE "^parametersDetail\\.modelField\\.|^chatModelCatalog\\." messages_vi.properties` → 6.

## Decisions Made

- **Effective-settings-transition publish surface (codex HIGH Concern #6)**: include DEACTIVATION (active=true → false) in the AiParameters publish surface. The pre-review plan draft would have published zero events for this transition — leaving Phase 18 caches stale after an admin deactivates the active profile. Locked the full transition lattice (3 CASEs × 4 UPDATED sub-cases) into the listener with a class-level Javadoc that names codex HIGH Concern #6 explicitly.
- **SINGLETON_ID guard as first listener line (codex MEDIUM Concern #6)**: brought in from `16-CONTEXT.md` and locked. Non-singleton writes silently no-op — host extensions or accidental writes cannot trigger cache eviction. No `EntityChangedEvent.Type` filter on the UI-settings listener because every transition on the singleton row is, by definition, effective.
- **Rule 3 — pure-JUnit + Mockito for the invariant test**: the plan's nominal shape was `@SpringBootTest + @RecordApplicationEvents` mirroring Pattern I. Same Phase 11/13 Spring-context boot regression that blocked Plans 13.1-06/07, 14-01/02, 16-01/02/03/04 still blocks this module's `@SpringBootTest` boot (`AdminViewAccessTest` failures on a clean tree confirm the blocker is pre-existing). Workaround: mock `ApplicationEventPublisher` + `DataManager`, build `EntityChangedEvent` instances directly via the `@Internal` constructor with real `AttributeChanges.Builder` payloads, invoke listeners directly, and `verify(publisher, times(N))` the calls. Contract preserved exactly — the publish-contract methods port unchanged when the regression is fixed.
- **`ApplicationEvent` ArgumentCaptor over `Object`**: Mockito routes `verify` to the overload matching the captor's parameter type. `ApplicationEventPublisher` has two `publishEvent` overloads — `(Object)` and `(ApplicationEvent)` — and the listener invokes the latter (because `AiSettingsChangedEvent extends ApplicationEvent`). Captor parameterized as `Object.class` misses the call entirely (initial 6 / 11 failures); fixing it to `ApplicationEvent.class` resolved the verification. Documented inline so the next maintainer doesn't repeat the bug.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking Issue] AiSettingsChangedEventListenerInvariantTest switched from `@SpringBootTest + @RecordApplicationEvents` to pure-JUnit + Mockito**

- **Found during:** Task 3.
- **Issue:** Plan directs `@SpringBootTest` with `@RecordApplicationEvents` mirroring Pattern I. The ai-agent module's Spring context boot is blocked by the pre-existing Phase 11/13 regression (atmosphere-runtime / agentstoreEntityManagerFactory) documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`. Verified pre-existing on a clean tree before any of my edits — `AdminViewAccessTest` fails with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145` on `main`. Same blocker that forced Plans 13.1-06/07, 14-01/02, 16-01/02/03/04 onto pure-JUnit workarounds.
- **Fix:** Mock `ApplicationEventPublisher` + `DataManager`; build real `EntityChangedEvent` instances via the `@Internal public` constructor with `AttributeChanges.Builder` payloads; invoke listeners directly. The source-scan test (the most important leg — the Plan 10-06 R2 build-time invariant) requires no boot at all and runs identically to the planned shape.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java`
- **Commit:** `53d4657`

**2. [Rule 1 — Bug] ArgumentCaptor parameter type widened from Object to ApplicationEvent**

- **Found during:** Task 3 — initial test run reported 6 / 11 failures all at the same line.
- **Issue:** `verify(publisher, times(count)).publishEvent(captor.capture())` where `captor = ArgumentCaptor.forClass(Object.class)` matched the `publishEvent(Object)` overload, missing the `publishEvent(ApplicationEvent)` overload that the listener actually invokes.
- **Fix:** Widen captor to `ArgumentCaptor.forClass(ApplicationEvent.class)`. Documented inline why both overloads exist and why the type matters.
- **Files modified:** Same test file.
- **Commit:** `53d4657` (caught + fixed during the same task before commit).

**3. [Rule 2 — Missing critical functionality] Added boundary test methods beyond the plan's 9**

- **Found during:** Task 3 — writing out the eight UPDATED sub-cases revealed two boundary gaps the plan's 9 named methods did not cover.
- **Issue:** Plan listed 9 methods; the publish contract surface has 11 distinct transitions. The missing two:
  - `inactiveDeletedRowOldActiveFalsePublishesZero` — DELETED with oldActive=false → 0 events. The plan covers the oldActive=true case but not the oldActive=false negative case; without it a regression that publishes on all DELETED events would slip through.
  - `activationFromInactiveToActivePublishesPARAMETERS` — UPDATED with active flipped false → true → 1 event. The plan covers DEACTIVATION but not ACTIVATION; without it a regression that suppresses ACTIVATION publishes would slip through.
- **Fix:** Added both methods. All 11 methods pass.
- **Files modified:** Same test file.
- **Commit:** `53d4657`.

## Authentication Gates

None.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries already listed in `16-05-PLAN.md`:

- **Twin-publisher (R2) — Tampering / source-scan invariant** — mitigated: `singlePublishSiteSourceScan()` test fails the build if any view/service injects `ApplicationEventPublisher` for `AiSettingsChangedEvent`.
- **T-16-03 (DoS — bad-model reissue loop)** — unchanged: this plan's UI swap does not alter the call path; Plan 16-07 owns the reissue-loop guard.
- **T-16-04 (Information Disclosure — event payload)** — unchanged: event carries only `Kind` enum, no PII / no model id.
- **T-16-05 (Elevation of Privilege — ParametersDetailView)** — unchanged: view is admin-only via the existing `AiAgentAdminRole` `@ViewPolicy`; ComboBox swap is a like-for-like field replacement.

## Known Stubs

None. Production code in all three tasks ships without stubs. `ChatModelCatalog.findById()` returning `null` for unknown ids is documented intent (Plan 16-05 ComboBox label generator handles it as the custom-entry escape hatch per SPEC criterion 4); the controller's onCustomValueSet wire ensures the typed value is persisted verbatim.

## Deferred Issues

**Pre-existing Phase 11/13 Spring context boot regression** — `AdminViewAccessTest` (8 methods) fails on `main` and continues to fail post-Plan 05 with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145`. This is the SAME regression documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md` and worked around by every plan in this phase. Out of scope per the SCOPE BOUNDARY rule. Verified pre-existing by `git stash` + re-run on a clean tree.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiUiSettingsEntityListener.java` — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java` (modified) — FOUND

Commits exist:
- `49b31ec` (Task 1) — FOUND
- `7246b04` (Task 2) — FOUND
- `53d4657` (Task 3) — FOUND
