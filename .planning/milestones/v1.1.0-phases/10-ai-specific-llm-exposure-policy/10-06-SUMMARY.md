---
phase: 10
plan: 06
subsystem: ai-exposure-ui
tags: [admin-ui, jmix-flowui, exposure-policy, list-view, toggle-action]
requires:
  - 10-03 (AiAgentAdminRole @ViewPolicy AiAgent_AiExposureRule.list)
  - 10-04 (LlmExposurePolicy injected at all schema-discovery sites)
provides:
  - MetaclassComboBoxHelper (shared @Component for entity-name ComboBox)
  - AiAgent_AiExposureRule.list view (admin list surface)
  - exposureRulesList.* message keys in EN + VI bundles
affects:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties (21 new keys)
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties (21 new keys)
tech-stack:
  added: []
  patterns:
    - "Jmix StandardListView with genericFilter + propertyFilter (configurations/configuration default)"
    - "@Supply(to='dataGrid.column', subject='renderer') for badge + action-column toggle button"
    - "list_itemTracking action bound from <dataGrid><actions> alongside list_create/edit/remove"
    - "key='enabled' instead of property='enabled' to avoid Jmix dual-typed-field metamodel collision"
    - "io.jmix.core.Messages (NOT Spring MessageSource) for locale-aware view text"
    - "UnconstrainedDataManager.save for admin governance writes; EntityChangedEvent → entity listener is the single LlmExposureChangedEvent publish site (Fix R2)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/MetaclassComboBoxHelper.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "Toggle save MUST NOT publish LlmExposureChangedEvent manually — UnconstrainedDataManager.save fires EntityChangedEvent which AiExposureRuleEntityListener republishes (Fix R2 single-publish-site enforced)"
  - "ApplicationEventPublisher intentionally NOT injected into AiExposureRuleListView to make double-fire impossible by construction"
  - "Toggle save uses UnconstrainedDataManager (not DataManager) so admin governance cannot be locked out by user-role tweaks (D-10 / EXP-06)"
  - "MetaclassComboBoxHelper extracted as shared @Component (not a private static method) so Plans 10-07 (detail view) and 10-08 (KB upload form) reuse the same filter rules"
  - "List view ships only the message keys it uses (21 keys) — detail-view keys (exposureRulesDetail.*) deferred to Plan 10-07 to keep the bundle delta scoped to plan boundaries"
  - "genericFilter declared with <configurations><configuration default='true'> wrapping the propertyFilter children — matches Jmix Flow UI 2.x runtime contract for default conditions"
  - "enabled column uses key='enabled' + @Supply badge renderer; never property='enabled' (MEMORY feedback_jmix_dual_typed_field_column)"
metrics:
  duration_min: 12
  tasks_completed: 2
  files_changed: 5
  completed_date: "2026-04-27"
---

# Phase 10 Plan 06: Exposure Rule List View Summary

EXP-07 admin list surface for `AiExposureRule`: shared metaclass-dropdown helper, Jmix `StandardListView` with `genericFilter` + `@Supply` toggle action button (saves via `UnconstrainedDataManager`), and full EN/VI message-key inventory for the list view. Fix R2 enforced — view never publishes `LlmExposureChangedEvent` directly; the entity listener remains the single publish site.

## Objective Recap

Stand up the admin list view that lets `AiAgentAdminRole` users see all exposure rules, run create/edit/remove, and flip the enabled bit per row. The toggle button must commit through `UnconstrainedDataManager` (admin governance survives user-role tweaks) and must NOT publish `LlmExposureChangedEvent` manually — the entity listener already republishes Jmix's `EntityChangedEvent`, so a manual publish would double-fire (Fix R2).

The metaclass-dropdown helper is extracted as a shared `@Component` because Plans 10-07 (detail view) and 10-08 (KB upload form) consume the same filtered list (same `@SystemLevel` + AI-* internals exclusion rules per CONTEXT D-11).

## What Was Built

### Task 1 — `MetaclassComboBoxHelper` (`com.vn.agent.view.exposure`)

`@Component` with one method `buildFilteredList()`:
- Iterates `metadata.getSession().getClasses()`.
- Excludes `@SystemLevel`-annotated entities (matches `CurrentUserSchemaAccess.isSystemLevelEntity`).
- Excludes the six AI-* internal entity names (`ai_AiAuditEvent`, `ai_AiConversation`, `ai_AiMessage`, `ai_AiKnowledgeDocument`, `ai_AiParameters`, `aiExposure_AiExposureRule`).
- Sorts alphabetically by `MessageTools.getEntityCaption(mc)` so labels read naturally in EN and VI.

Constructor-injected (no field injection) per CLAUDE.md. Caller is responsible for the item-label generator that appends the metaclass name in parentheses (UI-SPEC contract `"{caption} ({metaClassName})"`).

**Commit:** `b719225` — `feat(10-06): add MetaclassComboBoxHelper for entity-name ComboBox`

### Task 2 — `AiExposureRuleListView` XML + Java

**XML descriptor** (`ai-exposure-rule-list-view.xml`):
- `<view title="msg:///exposureRulesList.title" focusComponent="exposureRulesDataGrid">`.
- `<collection id="exposureRulesDc" class="com.vn.agent.exposure.AiExposureRule">` with read-only loader and JPQL `select e from aiExposure_AiExposureRule e order by e.entityName asc`.
- `<dataLoadCoordinator auto="true"/>` facet.
- `<genericFilter>` with `<configurations><configuration default="true"><propertyFilter property="entityName" operation="CONTAINS"/><propertyFilter property="enabled" operation="EQUAL"/></configuration></configurations>` — propertyFilter children must live inside a configuration block in Jmix Flow UI 2.x to be picked up as defaults.
- Buttons panel hbox with three Jmix-built-in actions: `createBtn` / `editBtn` / `removeBtn`.
- `<dataGrid id="exposureRulesDataGrid" themeNames="compact" width="100%" minHeight="20em">` with four `<actions>`: `list_create`, `list_edit`, `list_remove`, `list_itemTracking` (`toggleAction`).
- Four columns: `property="entityName"`, `key="enabled"`, `property="createdDate"`, `key="toggleAction"` — `key=` for `enabled` (NOT `property=`) per MEMORY `feedback_jmix_dual_typed_field_column`.

**Java controller** (`AiExposureRuleListView.java`):
- `@Route(value = "ai-agent/exposure-rules", layout = DefaultMainViewParent.class)`.
- `@ViewController(id = "AiAgent_AiExposureRule.list")` (matches `AiAgentAdminRole` viewIds entry from Plan 10-03).
- `@ViewDescriptor(path = "ai-exposure-rule-list-view.xml")`.
- Extends `StandardListView<AiExposureRule>`.
- `@Autowired` injects: `UnconstrainedDataManager`, `Notifications`, `io.jmix.core.Messages`, `UiComponents`. **NOT** `ApplicationEventPublisher`.
- `@ViewComponent` injects `DataGrid<AiExposureRule>` and `CollectionLoader<AiExposureRule>`.
- `@Supply(to = "exposureRulesDataGrid.enabled", subject = "renderer")` returns `DataGridRenderers.buildBadgeColumn(...)` — "Active" (success theme) when enabled, "Inactive" (contrast theme) otherwise.
- `@Supply(to = "exposureRulesDataGrid.toggleAction", subject = "renderer")` returns `ComponentRenderer<Button, AiExposureRule>` — when `enabled=true` button shows `EYE_SLASH` icon + "Hide from AI" label + `LUMO_PRIMARY` theme; when `enabled=false` shows `EYE` icon + "Visible to AI" label + `LUMO_SUCCESS` theme. Tooltip set via `title` attribute on the underlying element.
- `private void toggleEnabled(AiExposureRule rule)`:
  1. Flips `rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()))`.
  2. Calls `unconstrainedDataManager.save(rule)`. **Does NOT call `applicationEventPublisher.publishEvent(...)`** — `UnconstrainedDataManager.save` fires Jmix `EntityChangedEvent` which `AiExposureRuleEntityListener` (Plan 10-02) republishes as `LlmExposureChangedEvent`. Manual publish would cause double-fire per Fix R2.
  3. `exposureRulesDl.load()` to refresh the row.
  4. Success notification using the post-toggle action label key.
  5. On exception: `NotificationUtils.errorWithDetail(...)` with `exposureRulesList.error.toggle`.

**Messages** — 21 new keys appended to BOTH `messages_en.properties` and `messages_vi.properties`:
- Entity caption + attributes (9 keys): `com.vn.agent.exposure/AiExposureRule[.attribute]`, `AiExposureRuleMode.EXCLUDE`.
- List view (12 keys): title, four column headers, two action labels (each with tooltip variant), two badge labels, two empty-state strings, one error string.

**Commit:** `53a207d` — `feat(10-06): add AiExposureRuleListView (XML + Java) with toggle action`

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 1 | BUILD SUCCESSFUL (14s) |
| `./gradlew :ai-agent:ai-agent:compileJava` after Task 2 | BUILD SUCCESSFUL (9s) |
| `./gradlew :ai-agent:ai-agent:compileTestJava` after Task 2 | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:test` (full suite) | BUILD SUCCESSFUL (2m 32s) |
| `grep -c AI_INTERNAL_ENTITY_NAMES MetaclassComboBoxHelper.java` | 2 (≥1 required) |
| `grep -c SystemLevel MetaclassComboBoxHelper.java` | 4 (≥1 required) |
| `grep -c buildFilteredList MetaclassComboBoxHelper.java` | 2 (≥1 — declaration + javadoc) |
| `grep -c '@Supply' AiExposureRuleListView.java` | 3 (toggleRenderer + enabled badge + 1 doc) |
| `grep -c 'unconstrainedDataManager\.save' AiExposureRuleListView.java` | 1 (exact requirement) |
| `grep -c 'column.*property="enabled"' ai-exposure-rule-list-view.xml` | 0 (no `property="enabled"` on a column — `key="enabled"` used) |
| `grep -c genericFilter ai-exposure-rule-list-view.xml` | 1 |
| `grep -c io\\.jmix\\.core\\.Messages AiExposureRuleListView.java` | 1 |
| `grep -c 'hideFromAi\|visibleToAi' AiExposureRuleListView.java` | 6 (4 message-key references + 2 javadoc) |
| Real-code check for `ApplicationEventPublisher` field / `.publishEvent(` call in view | 0 — only Javadoc Fix-R2 explanations match the broad regex |

The plan's broad regex `LlmExposureChangedEvent\|publishEvent\|ApplicationEventPublisher` returns 5 matches, all in Javadoc that explains why the view does NOT publish (Fix R2 documentation). No actual import, field, or call exists; the spirit of the acceptance criterion (no manual publish) is satisfied. The targeted regex `^import.*ApplicationEventPublisher|@Autowired[\s\S]*?ApplicationEventPublisher|\.publishEvent\(` returns zero matches in code, confirming Fix R2 compliance.

The plan's broad regex `'property="enabled"'` matches the `<propertyFilter property="enabled">` element inside `<genericFilter>` (a different element than `<column>`). The acceptance criterion's intent is "no `column property="enabled"`", which is verified zero via the targeted `'column.*property="enabled"'` regex. The propertyFilter binding is correct and required for the genericFilter to expose an `enabled` filter chip.

## Decisions Made

- **Single publish site enforced by construction (Fix R2).** `ApplicationEventPublisher` is not injected and `LlmExposureChangedEvent` is not constructed in the view. The only publish path is `UnconstrainedDataManager.save` → Jmix `EntityChangedEvent` → `AiExposureRuleEntityListener` → `LlmExposureChangedEvent`. Adding a manual publish would require both injecting the publisher AND constructing the event — a deliberate friction that prevents accidental reintroduction.
- **`UnconstrainedDataManager` over `DataManager` for the toggle.** The list-view toggle is admin governance; user-role policies must not be able to lock the admin out of flipping the enabled bit. Matches D-10 / EXP-06 and the pattern from `LlmExposureRuleRepository` (Plan 10-02).
- **`MetaclassComboBoxHelper` as a shared `@Component`.** Two more consumers in this phase (10-07 detail view, 10-08 KB upload form). Centralising the filter rules (same six AI-* internals + `@SystemLevel` exclusion + alphabetic sort by caption) prevents drift between the three call sites.
- **Per-plan message-key scope.** Only the 21 keys the list view uses landed in this commit. Detail-view keys (`exposureRulesDetail.*`) and KB-extension keys ship in their respective plans. This keeps each commit's bundle delta scoped to the plan that created it and matches the `parametersList` / `parametersDetail` precedent in the same bundles.
- **`<configuration default="true">` wrapping the propertyFilter children.** Jmix Flow UI 2.x routes default `genericFilter` conditions through a configuration block; loose `<propertyFilter>` siblings of `<properties>` are not picked up at runtime. Verified via existing patterns in the project's other views.
- **`key="enabled"` + `@Supply` badge renderer for the enabled column.** MEMORY `feedback_jmix_dual_typed_field_column` records that `property="enabled"` on a Boolean field can silently empty the grid body via a metamodel collision. The badge renderer also gives us locale-resolved "Active" / "Inactive" text and the success/contrast theme variants without coupling to the entity getter.

## Deviations from Plan

None — plan executed as written. The plan's broad acceptance grep for `ApplicationEventPublisher|publishEvent|LlmExposureChangedEvent` does match Javadoc (Fix R2 explanations), but the spirit of the criterion — no actual code-level publish — is satisfied and the documentation actively prevents future regressions. The plan's broad `property="enabled"` grep matches the `propertyFilter` element inside `genericFilter`, which is the required binding for filter UI; the criterion's intent (`column property="enabled"`) is verified zero via a targeted regex.

## Threat Model Compliance

T-10-05 (Elevation of Privilege at the toggle button): Mitigation in Plan 10-03 (view-id `AiAgent_AiExposureRule.list` is gated to `AiAgentAdminRole` only). This plan preserves the contract: the view does not provide any non-admin path to write `AiExposureRule`, and `UnconstrainedDataManager` is reached only from inside an authenticated admin session that already passed the `@ViewPolicy` gate. No new threat surface introduced.

## Open Items / Follow-ups

- Plan 10-07 will add `AiExposureRuleDetailView` (XML + Java) and consume `MetaclassComboBoxHelper` to populate the entity-name ComboBox. The `exposureRulesDetail.*` message keys ship with that plan.
- Plan 10-08 will extend `KnowledgeBaseView` upload form / row actions with `sourceEntityName`, also consuming `MetaclassComboBoxHelper`.
- Plan 10-09 (`menu.xml` registration) will add `<item id="aiAgent.exposureRules.list" view="AiAgent_AiExposureRule.list" title="msg://com.vn.agent/menu.exposureRules"/>` plus the menu-title message key in both bundles.

## Self-Check: PASSED

- Files exist:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/MetaclassComboBoxHelper.java` — FOUND
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java` — FOUND
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml` — FOUND
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — modified (exposureRulesList.* + entity caption keys present)
  - `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — modified (exposureRulesList.* + entity caption keys present)
- Commits exist:
  - `b719225` (Task 1) — verified via `git log`
  - `53a207d` (Task 2) — verified via `git log`
- Compile + full test suite green.
