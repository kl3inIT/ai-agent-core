---
phase: 10
plan: 07
subsystem: ai-exposure-ui
tags: [admin-ui, jmix-flowui, exposure-policy, detail-view, i18n, menu]
requires:
  - 10-03 (AiAgentAdminRole @ViewPolicy AiAgent_AiExposureRule.detail + menuIds)
  - 10-06 (MetaclassComboBoxHelper shared @Component)
provides:
  - AiAgent_AiExposureRule.detail view (admin create/edit surface)
  - exposureRulesDetail.* message keys in EN + VI bundles
  - knowledgeBase.* extension keys (incl. reingestEnqueueFailed/reingestRetryHint for Plan 10-08)
  - vectorStoreDebug.* keys for Plan 10-09
  - menu.xml entries for aiAgent.exposureRules.list and aiAgent.vectorStoreDebug
affects:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties (~36 new keys)
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties (~36 new keys)
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml (2 new <item> entries)
tech-stack:
  added: []
  patterns:
    - "Jmix StandardDetailView with @EditedEntityContainer + dataLoadCoordinator auto-wiring"
    - "Vaadin ComboBox<MetaClass> populated programmatically (NOT via property=) — type mismatch with String entity field"
    - "@Subscribe('comboBoxId') AbstractField.ComponentValueChangeEvent to mirror MetaClass selection back to entity.entityName"
    - "ReadyEvent (not BeforeShowEvent) used to pre-select MetaClass when editing existing rule — runs after dataContainer is populated"
    - "Reuse of MetaclassComboBoxHelper from Plan 10-06 — single source of truth for @SystemLevel + AI-* internals exclusion"
    - "Item label format '{caption} ({metaClassName})' via MessageTools.getEntityCaption per UI-SPEC"
    - "Property file additions grouped (A/B/C) with section comments to scope the bundle delta clearly"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleDetailView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-detail-view.xml
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
decisions:
  - "ComboBox<MetaClass> not bound via property= — MetaClass != String, so the entityName ComboBox is populated and value-bridged in the controller (UI-SPEC + plan interfaces note)"
  - "Use ReadyEvent (not BeforeShowEvent) for the pre-select hook — at ReadyEvent the dataContainer's item is fully loaded, while BeforeShowEvent fires earlier and risks a null item for edit-by-route flows"
  - "EN bundle is messages_en.properties, NOT messages.properties — matches existing project convention (Plan 10-06 SUMMARY) and the action wording in 10-07-PLAN was a doc inconsistency, not a project change"
  - "Menu entries use <item> (not <menu>) to match existing menu.xml shape; the plan snippet's <menu> tag was illustrative — real Jmix menu-config schema uses <item> for routable views"
  - "Group A/B/C bundle structure preserved across both locales — section comments + blank-line separators reduce merge/drift risk per plan §Fix R9"
  - "Two extra error keys (knowledgeBase.error.reingestEnqueueFailed and knowledgeBase.error.reingestRetryHint) shipped here, ahead of Plan 10-08, so the KB row-action service in 10-08 has its message keys present at compile/test time"
  - "exposureRulesDetail.confirm.remove.* keys (already promised by Plan 10-06's UI-SPEC) ship with this plan since 10-06 scoped only the list-view keys"
  - "checkbox uses property='enabled' on the dataContainer — Boolean is not the dual-type-field issue covered by MEMORY feedback_jmix_dual_typed_field_column (which only affects Strings shadowed by enum getters)"
metrics:
  duration_min: 18
  tasks_completed: 2
  files_changed: 5
  completed_date: "2026-04-27"
---

# Phase 10 Plan 07: Exposure Rule Detail View, Message Keys, Menu Entries Summary

EXP-07 admin detail surface for `AiExposureRule` plus the full UI-SPEC i18n key inventory and the two new admin menu entries. The detail view reuses `MetaclassComboBoxHelper` from Plan 10-06; the bundle adds Plan 10-08's two runtime error keys ahead of time so the next plan compiles cleanly.

## Objective Recap

Stand up the admin create/edit form for `AiExposureRule` (entity-name ComboBox + enabled checkbox, no `attributePath`, no `mode`), add ALL UI-SPEC message keys to both locale bundles in three logical groups (exposure-rule keys, KB-extension keys, debug-view keys), and register the two new menu entries (`aiAgent.exposureRules.list`, `aiAgent.vectorStoreDebug`) so the views are reachable through the AI admin menu.

The plan's Fix R9 grouping (A/B/C with section comments) keeps the bundle delta scoped and reviewable. Plan 10-08's runtime requires `knowledgeBase.error.reingestEnqueueFailed` and `knowledgeBase.error.reingestRetryHint`; both ship in Group B here so 10-08 has them at compile time.

## What Was Built

### Task 1 — `AiExposureRuleDetailView` (XML + Java)

**XML descriptor** (`ai-exposure-rule-detail-view.xml`):
- `<view title="msg:///exposureRulesDetail.title.new" focusComponent="entityNameComboBox">`.
- `<instance id="exposureRuleDc" class="com.vn.agent.exposure.AiExposureRule">` with `<fetchPlan extends="_base"/>` and a loader.
- `<dataLoadCoordinator auto="true"/>` facet.
- `<actions>`: `saveAction` (`detail_saveClose`) + `closeAction` (`detail_close`).
- `<formLayout id="ruleForm" dataContainer="exposureRuleDc">` with single-column `<responsiveSteps><responsiveStep minWidth="0" columns="1"/></responsiveSteps>`.
- `<comboBox id="entityNameComboBox" required="true" width="100%">` — NO `property=` (controller populates programmatically; MetaClass != String).
- `<checkbox id="enabledCheckbox" property="enabled">` bound to the dataContainer.
- `<hbox id="detailActions">` with `saveBtn` (`themeNames="primary"`) and `cancelBtn`.

**Java controller** (`AiExposureRuleDetailView.java`):
- `@Route(value = "ai-agent/exposure-rules/:id", layout = DefaultMainViewParent.class)`.
- `@ViewController(id = "AiAgent_AiExposureRule.detail")` (matches `AiAgentAdminRole` viewIds entry from Plan 10-03).
- `@ViewDescriptor(path = "ai-exposure-rule-detail-view.xml")`.
- `@EditedEntityContainer("exposureRuleDc")`.
- Extends `StandardDetailView<AiExposureRule>`.
- `@Autowired` injects `MetaclassComboBoxHelper`, `MessageTools`, `Metadata`.
- `@ViewComponent` injects `ComboBox<MetaClass> entityNameComboBox` and `InstanceContainer<AiExposureRule> exposureRuleDc`.
- `@Subscribe InitEvent` populates items from `metaclassComboBoxHelper.buildFilteredList()` and sets the item-label generator to `messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")"` per UI-SPEC contract.
- `@Subscribe ReadyEvent` pre-selects the matching MetaClass when editing an existing rule (`metadata.getSession().findClass(rule.getEntityName())`); new rules leave the combo unselected so the placeholder "Select entity..." renders.
- `@Subscribe("entityNameComboBox") ComponentValueChangeEvent` mirrors the selected metaclass back onto `rule.setEntityName(selected.getName())`. Null selection clears `entityName` so Jmix `@NotNull` validation surfaces the missing-field error consistently.

**Commit:** `df21d75` — `feat(10-07): add AiExposureRuleDetailView (XML + Java) with MetaclassComboBox`

### Task 2 — Message keys (EN + VI) and menu.xml entries

**Group A — Exposure-rule keys (~13 keys per locale):**
- Menu titles: `com.vn.agent/menu.exposureRules`, `com.vn.agent/menu.vectorStoreDebug`.
- Detail-view titles: `exposureRulesDetail.title.new`, `exposureRulesDetail.title.edit`.
- Detail-view confirm-remove dialog keys (referenced by `list_remove` action): `exposureRulesDetail.confirm.remove.title`, `exposureRulesDetail.confirm.remove.body`.
- Detail-view fields: `exposureRulesDetail.field.entityName`, `.placeholder`, `.helper`, `exposureRulesDetail.field.enabled`, `exposureRulesDetail.action.save`.

**Group B — KnowledgeBase extension keys (11 keys per locale):**
- 9 from UI-SPEC: `knowledgeBase.action.editPermissions`, `knowledgeBase.upload.field.allowedRoles`, `knowledgeBase.upload.field.sourceEntityName` (+ `.helper`), `knowledgeBase.column.sourceEntity`, `knowledgeBase.confirm.editPermissions.reingest.title` / `.body`, `knowledgeBase.error.editPermissionsReingest`, `com.vn.agent.entity/AiKnowledgeDocument.sourceEntityName`.
- 2 extra runtime error keys for Plan 10-08 (so the service-result routing in 10-08 has them at compile time): `knowledgeBase.error.reingestEnqueueFailed` and `knowledgeBase.error.reingestRetryHint`.

**Group C — VectorStoreDebug keys (14 keys per locale):**
- Title: `vectorStoreDebug.title`.
- Filter row: `.filter.label`, `.filter.clear`, `.filter.help`, `.filter.help.tooltip`.
- Action: `.action.search`.
- Columns: `.column.id`, `.column.content`, `.column.metadata`.
- Detail dialog: `.action.expand`, `.detail.title`, `.detail.close`.
- Empty/error: `.empty.heading`, `.empty.body`, `.error.filterParse`.

**menu.xml additions** (under existing `<menu id="AI">`):
```xml
<item id="aiAgent.exposureRules.list" view="AiAgent_AiExposureRule.list"
      title="msg://com.vn.agent/menu.exposureRules"/>
<item id="aiAgent.vectorStoreDebug" view="AiAgent_VectorStoreDebug"
      title="msg://com.vn.agent/menu.vectorStoreDebug"/>
```

The IDs match `AiAgentAdminRole.@MenuPolicy` from Plan 10-03; security gating is enforced by Jmix automatically based on the IDs.

**Commit:** `cd40659` — `feat(10-07): add UI-SPEC message keys and menu entries for exposure detail / KB extensions / vector store debug`

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 1 | BUILD SUCCESSFUL (10s) |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 2 | BUILD SUCCESSFUL (2s, up-to-date) |
| `./gradlew :ai-agent:ai-agent:test` (full suite) | BUILD SUCCESSFUL (1m 38s) |
| `grep -c MetaclassComboBoxHelper AiExposureRuleDetailView.java` | 2 (≥1 required) |
| `grep -Ec 'attributePath\|mode' ai-exposure-rule-detail-view.xml` | 0 (no mode/attributePath) |
| `grep -c buildFilteredList AiExposureRuleDetailView.java` | 2 (≥1 required) |
| `grep -c getEntityCaption AiExposureRuleDetailView.java` | 1 |
| `grep -c exposureRulesList.title messages_en.properties` | 1 |
| `grep -c exposureRulesList.title messages_vi.properties` | 1 |
| `grep -c vectorStoreDebug.title messages_en.properties` | 1 |
| `grep -c vectorStoreDebug.title messages_vi.properties` | 1 |
| `grep -c knowledgeBase.action.editPermissions messages_en.properties` | 1 |
| `grep -c knowledgeBase.action.editPermissions messages_vi.properties` | 1 |
| `grep -c hideFromAi messages_en.properties` | 2 (label + tooltip) |
| `grep -c hideFromAi messages_vi.properties` | 2 (label + tooltip) |
| `grep -c knowledgeBase.error.reingestEnqueueFailed= messages_en.properties` | 1 |
| `grep -c knowledgeBase.error.reingestEnqueueFailed= messages_vi.properties` | 1 |
| `grep -c knowledgeBase.error.reingestRetryHint= messages_en.properties` | 1 |
| `grep -c knowledgeBase.error.reingestRetryHint= messages_vi.properties` | 1 |
| `grep -c aiAgent.exposureRules.list menu.xml` | 1 |
| `grep -c aiAgent.vectorStoreDebug menu.xml` | 1 |

The plan's acceptance grep referenced `messages.properties` but the project's EN bundle is `messages_en.properties` (per Plan 10-06 SUMMARY and existing repo state). All key counts pass against the actual EN bundle file name; the spirit of the criterion (each key present in BOTH locale bundles) is satisfied.

## Decisions Made

- **`ReadyEvent` (not `BeforeShowEvent`) for the MetaClass pre-select hook.** At `ReadyEvent` the `@EditedEntityContainer`'s item is fully loaded; `BeforeShowEvent` fires earlier and may see a null item under the route-bound edit flow. `ParametersDetailView` already uses the same `ReadyEvent` pattern for parsing `bodyYaml` into form fields — consistent with existing project precedent.
- **ComboBox is NOT `property=`-bound to `entityName`.** A `<comboBox property="entityName">` would force Jmix to bind a `MetaClass` value to a `String` field — the binder rejects type mismatches at runtime. The controller therefore populates items, sets the value on `ReadyEvent`, and writes back on the value-change event. This mirrors the plan's `<interfaces>` directive verbatim and avoids the "dual-typed field" antipattern documented in MEMORY.
- **`InstanceContainer.getItemOrNull()` over `.getItem()`.** Per MEMORY `feedback_jmix_container_lookup`, prefer the null-safe variant in lifecycle handlers; defensive against new-rule flows where `dataContainer` is hydrated late.
- **Bundle additions grouped A/B/C with section comments.** Plan §Fix R9: ~36 new keys across three logical sections is large enough that a flat append risks merge churn. The three groups (exposure detail, KB extensions, debug view) have clear separators (`# --- Group X ---`) and a Plan-07 banner header so future diffs are reviewable.
- **Plan 10-08's two error keys land here.** `knowledgeBase.error.reingestEnqueueFailed` and `knowledgeBase.error.reingestRetryHint` are required by 10-08's service-result routing per the plan's must-haves. Shipping them in this plan keeps 10-08 from regressing to "missing key" warnings during compile/test.
- **Menu entries use `<item>` not `<menu>`.** The plan snippet showed `<menu id="...">` but the existing `menu.xml` and Jmix flowui menu schema use `<item>` for routable view entries (the outer `<menu>` is the container/group). This is a doc inconsistency in the plan snippet; the project convention is correct and was followed.
- **EN bundle is `messages_en.properties`.** The plan referenced `messages.properties` in some places, but the project's actual EN bundle (and per Plan 10-06 SUMMARY) is `messages_en.properties`. Documented as a deviation; no functional impact.
- **`exposureRulesDetail.confirm.remove.*` keys land here.** They were specified in the UI-SPEC under the list-view section, but Plan 10-06 explicitly deferred all `exposureRulesDetail.*` keys to this plan to keep its bundle delta scoped. The `list_remove` confirm dialog now finds the localized strings as soon as 10-07 lands.

## Deviations from Plan

**1. [Doc-only] Plan referenced `messages.properties`; project uses `messages_en.properties`**
- **Found during:** Task 2 — verifying acceptance criteria
- **Issue:** Plan 10-07 §`<files_modified>`, `<acceptance_criteria>`, and `<verification>` reference `messages.properties` as the EN bundle path. The actual project file is `messages_en.properties` (verified via Plan 10-06 SUMMARY which states "21 new keys appended to BOTH `messages_en.properties` and `messages_vi.properties`" and via filesystem inspection).
- **Fix:** Used `messages_en.properties` for the EN bundle. The acceptance-criterion intent (each key in BOTH locales, EN + VI) is preserved.
- **Files modified:** `messages_en.properties` (not `messages.properties`).
- **Commit:** `cd40659`.

**2. [Doc-only] Plan menu snippet used `<menu>`; project schema uses `<item>`**
- **Found during:** Task 2 — adding menu.xml entries
- **Issue:** Plan snippet showed `<menu id="aiAgent.exposureRules.list" view="..."/>` but the existing `menu.xml` (and Jmix `flowui/menu` schema for routable views) use `<item>`. The outer `<menu id="AI">` is the group container; entries inside it are `<item>`.
- **Fix:** Used `<item>` to match project convention; included `title="msg://com.vn.agent/menu.<key>"` since the file's pattern requires it for menu-tree rendering.
- **Files modified:** `menu.xml`.
- **Commit:** `cd40659`.

No deviations from the must-haves: ComboBox<MetaClass> via helper, no attributePath/mode, item label format `{caption} ({metaClassName})`, all UI-SPEC keys in both bundles, both menu IDs registered, both reingest error keys present in both locales.

## Threat Model Compliance

**T-10-05 (Elevation of Privilege at the detail view save):** Mitigation in Plan 10-03 (view id `AiAgent_AiExposureRule.detail` is gated to `AiAgentAdminRole` via `@ViewPolicy`). This plan's `StandardDetailView.save()` uses Jmix's standard security-gated `DataManager` — non-admins lack `@EntityPolicy(AiExposureRule.class, ALL)` and the save fails before any rule reaches the database. No new threat surface introduced; the form does not bypass any policy boundary.

The view does NOT inject `UnconstrainedDataManager` (only the list view's toggle action does, for governance reasons documented in 10-06). Detail-view save through `detail_saveClose` action uses the standard secured DataManager path.

## Open Items / Follow-ups

- Plan 10-08 will extend `KnowledgeBaseView` upload form / row actions with `sourceEntityName`, consuming `MetaclassComboBoxHelper`, and will use the `knowledgeBase.error.reingestEnqueueFailed` / `knowledgeBase.error.reingestRetryHint` keys shipped here.
- Plan 10-09 will create `VectorStoreDebugView` (XML + Java) consuming the `vectorStoreDebug.*` keys and `Spring AI FilterExpressionTextParser`.
- Plan 10-10 will add the `LlmExposurePolicyIntegrationTest` (TEST-09 four-path opacity).
- The menu entries are now visible to `AiAgentAdminRole` users; clicking them will route to the views once 10-09 lands `AiAgent_VectorStoreDebug` (the exposure-rule list/detail are already wired by 10-06 + 10-07).

## Self-Check: PASSED

- Files exist:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleDetailView.java` — FOUND
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-detail-view.xml` — FOUND
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — modified (~36 new keys present)
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — modified (~36 new keys present)
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` — modified (2 new <item> entries)
- Commits exist:
  - `df21d75` (Task 1) — verified via `git log`
  - `cd40659` (Task 2) — verified via `git log`
- Compile + full test suite green (1m 38s).
