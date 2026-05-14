---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 08
subsystem: admin-config
tags: [jmix-metamodel, jmix-dto, jmix-views, ui-settings, gap-closure, sec-08]
requires:
  - Plan 16-06 (4-tab AiUiSettingsDetailView descriptor + KnobInventory scaffolding)
provides:
  - com.vn.agent.admin.config.dto.AiKnobRow (top-level @JmixEntity DTO; replaces nested KnobInventory$KnobRow)
  - com.vn.agent.admin.config.dto.AiSecretIndicatorRow (top-level @JmixEntity DTO; replaces nested KnobInventory$SecretIndicatorRow; SEC-08 — no value field)
  - AiUiSettingsKnobInventoryMetamodelTest (build-time regression gate — fails if a future plan reintroduces a nested-class binding under <collection class="X$Y"/> or drops @JmixEntity from either DTO)
  - SecretRedactionInvariantsTest#noValueFieldOnSecretIndicatorRow (4th invariant leg — reflective field-name regex)
affects:
  - KnobInventory (retyped tier1/tier2/tier3 accessors + State record element types; nested records removed)
  - KnobInventoryScanner (retyped scan locals + row constructions; sort uses getKey() JavaBean accessor)
  - ai-ui-settings-detail-view.xml (<collection class=...> bindings retargeted to top-level FQCNs)
  - AiUiSettingsDetailView (CollectionContainer + @Supply Renderer return types retyped; renderer bodies migrated to JavaBeans accessors except retained requiresRestart() bare-name accessor)
tech_stack_added: []
patterns_used:
  - "Pattern A: @JmixEntity non-persistent DTO (no @Entity / @Store / @Table) with @JmixId + @JmixGeneratedValue UUID id field"
  - "Files.walk file-discovery + regex scan of <collection class=...> attributes (mirrors SecretRedactionInvariantsTest convention — Warning #9)"
  - "Rule 3 — pure-JUnit reflection / file-system tests, no @SpringBootTest (Phase 11/13 boot regression workaround consistent with every prior Phase 16 plan)"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/dto/AiKnobRow.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/dto/AiSecretIndicatorRow.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsKnobInventoryMetamodelTest.java
key_files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/SecretRedactionInvariantsTest.java
decisions:
  - "Use plain Java classes (NOT records) for the DTOs because Jmix metamodel's non-persistent DTO registration requires mutable id setters and the @JmixGeneratedValue contract — records cannot expose setters."
  - "Retain a single record-compat bare-name accessor on AiKnobRow.requiresRestart() (boolean predicate idiom) so the existing @Supply renderer call site row.requiresRestart() compiles without churn. All other accessors migrate to JavaBeans form to avoid Jmix metamodel duplicate-property scan warnings."
  - "AiSecretIndicatorRow carries NO record-compat accessors at all — every caller (renderer, classification test) uses isConfigured() / getKey() / getDisplayMessageKey() (JavaBeans form)."
  - "Add a second SEC-08 enforcement layer in the new test class: AiUiSettingsKnobInventoryMetamodelTest#aiSecretIndicatorRowCarriesJmixEntityAndJmixId reasserts the (?i).*(value|raw|secret).* field-name regex, duplicating SecretRedactionInvariantsTest#noValueFieldOnSecretIndicatorRow on purpose — both pass if the invariant holds; either fails on its own if it is broken."
  - "Use Files.walk for file discovery (Warning #9 — locked in plan), mirroring SecretRedactionInvariantsTest exactly. Future maintainers see structurally identical tests."
metrics:
  duration: ~25 min
  tasks_completed: 2
  tasks_pending_manual_verification: 1
  files_created: 3
  files_modified: 5
  completed_date: 2026-05-14
status: AWAITING_MANUAL_VERIFICATION
---

# Phase 16 Plan 08: AI UI Settings KnobInventory DTO Promotion Summary

UAT Gap 1 (test 4, blocker — cascading to tests 5/6/7/8/9/10/11/15) closed at the code level: the AI UI Settings detail view's `<collection class=...>` bindings now target two top-level `@JmixEntity` DTO classes (`com.vn.agent.admin.config.dto.AiKnobRow`, `AiSecretIndicatorRow`) instead of the nested plain Java records that broke Plan 16-06 with `IllegalArgumentException: MetaClass not found ...`. A new build-time regression gate (`AiUiSettingsKnobInventoryMetamodelTest`) asserts the descriptor's `<collection class=...>` attributes (a) are loadable, (b) carry `@JmixEntity`, (c) contain no inner-class `$` separator — would have caught the original bug syntactically before any user could open the view. SEC-08 invariant preserved byte-for-byte and reasserted in two test classes (the original `SecretRedactionInvariantsTest` adds a 4th leg `noValueFieldOnSecretIndicatorRow`; the new test class duplicates the regex on `AiSecretIndicatorRow` for defense-in-depth).

## What Shipped

### Task 1 — Promote KnobRow + SecretIndicatorRow to top-level @JmixEntity DTOs (commit `a962dd8`)

- **Created `AiKnobRow.java`** at `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/dto/AiKnobRow.java`:
  - `@JmixEntity(name = "ai_AiKnobRow")` — NO `@Entity`, NO `@Store`, NO `@Table` (non-persistent DTO).
  - Fields: `@JmixId @JmixGeneratedValue private UUID id;`, plus `key`, `resolvedValue`, `displayMessageKey`, `requiresRestart`.
  - Three constructors: no-args (Jmix metamodel construction path), 4-arg record-shape (scanner call sites), 5-arg with id (tests).
  - JavaBeans getters/setters for every field plus a bare-name predicate `requiresRestart()` retained for the existing `@Supply` renderer call site `row.requiresRestart()`.
- **Created `AiSecretIndicatorRow.java`** with same DTO shape, fields `key` / `displayMessageKey` / `configured` only — **NO** `value` / `raw` / `secret` field (SEC-08). No record-compat accessors; all callers migrate to JavaBeans form (`isConfigured()`).
- **Updated `KnobInventory.java`**: removed the two nested records; retyped `tier1()`/`tier2()` to `List<AiKnobRow>`, `tier3()` to `List<AiSecretIndicatorRow>`; updated `State` record element types; Javadoc anchors the gap-closure context.
- **Updated `KnobInventoryScanner.java`**: retyped local `tier1`/`tier2`/`tier3` lists, the `walkType` / `addComponent` method signatures, the row constructions (`new AiKnobRow(...)` / `new AiSecretIndicatorRow(...)`), and the sort comparator (`AiSecretIndicatorRow::getKey`).
- **Updated `ai-ui-settings-detail-view.xml`**: two `<collection class=...>` attributes retargeted to `com.vn.agent.admin.config.dto.AiKnobRow` / `AiSecretIndicatorRow`. No new editable bindings on Tier-3 keys (SEC-08).
- **Updated `AiUiSettingsDetailView.java`**: retyped `bootConfigDc` / `secretsDc` CollectionContainer types, retyped both `@Supply` renderer return types (`Renderer<AiKnobRow>`, `Renderer<AiSecretIndicatorRow>`). Renderer body migrations: `row.requiresRestart()` retained on the boot-config badge renderer (record-compat accessor on `AiKnobRow`); `row.configured()` → `row.isConfigured()` on the secrets-indicator renderer (JavaBeans form on `AiSecretIndicatorRow`).
- **Updated `SecretRedactionInvariantsTest.java`**: added 4th invariant leg `noValueFieldOnSecretIndicatorRow` — reflective `(?i).*(value|raw|secret).*` field-name regex on `AiSecretIndicatorRow.class.getDeclaredFields()`. Plus positive invariant: `configured` boolean MUST be present.

The Jmix enhancer confirms DTO registration at build time:
```
Project entities:
    JPA: [com.vn.agent.entity.AiParameters, …, com.vn.agent.tools.mutation.AiMutationIntent, …];
    DTO: [com.vn.agent.admin.config.dto.AiKnobRow, com.vn.agent.admin.config.dto.AiSecretIndicatorRow];
```

The classification test (`KnobInventoryClassificationTest`) did NOT need updating — it walks `@KnobMetadata` reflection on the 10 carrier classes; it never referenced the row types.

### Task 2 — Metamodel-resolution boot test (commit `00a8bd9`)

- **Created `AiUiSettingsKnobInventoryMetamodelTest.java`** at `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/`.
- Three test methods, all green:
  1. `aiKnobRowCarriesJmixEntityAndJmixId` — asserts `@JmixEntity` present, `@Entity` + `@Store` absent, id-bearing field present.
  2. `aiSecretIndicatorRowCarriesJmixEntityAndJmixId` — same shape + SEC-08 regex `(?i).*(value|raw|secret).*` on declared field names.
  3. `viewDescriptorBindsTopLevelJmixEntityClassesOnly` — `Files.walk` finds the descriptor; regex `<collection\s+[^>]*class\s*=\s*"([^"]+)"` extracts every binding; asserts each (a) is loadable via `Class.forName`, (b) carries `@JmixEntity`, (c) contains no `$` separator. The set of bindings is asserted equal to `{AiKnobRow, AiSecretIndicatorRow}`.
- Pure-JUnit / reflection / file-system — no `@SpringBootTest` (Phase 11/13 boot regression workaround, same Rule 3 path as Plan 16-06).

### Task 3 — Manual human-verify checkpoint (AWAITING USER)

**Status:** PENDING USER VERIFICATION.

The user must boot the app at `http://localhost:8088` (per `project_local_dev_port` memory — the user keeps the app running there; executor did NOT auto-start `bootRun`) and perform the 5 verification steps from the plan:

1. Visit `http://localhost:8088/ai-agent/ui-settings`.
2. Verify NO "Unexpected error" dialog appears.
3. Verify four tabs visible: General, Tier-1 Knobs, Boot Config, Secrets.
4. Click each tab; verify Boot Config has ≥ 1 row and Secrets shows only `key | configured` columns with NO raw secret value in the DOM (open browser DevTools → search for `sk-or-` or any actual API key prefix → confirm zero matches).
5. Toggle UI locale EN ↔ VI; confirm tab labels, column headers, and indicator text update.

On approval (`"approved"`): plan is COMPLETE.
On failure: report the symptom — the planner will queue a follow-up gap-closure plan.

## Verification

```
cd ai-agent && ./gradlew.bat :ai-agent:compileJava :ai-agent-starter:compileJava
→ BUILD SUCCESSFUL in 41s

cd ai-agent && ./gradlew.bat :ai-agent:test \
    --tests "com.vn.agent.admin.config.KnobInventoryClassificationTest" \
    --tests "com.vn.agent.admin.config.SecretRedactionInvariantsTest" \
    --tests "com.vn.agent.admin.config.AiUiSettingsBeanValidationTest" \
    --tests "com.vn.agent.admin.config.AiUiSettingsKnobInventoryMetamodelTest"
→ BUILD SUCCESSFUL (all tests green; SecretRedactionInvariantsTest now 4 legs including new noValueFieldOnSecretIndicatorRow; AiUiSettingsKnobInventoryMetamodelTest 3 methods green)
```

Plan verify-section grep gates:

- `grep -c "record KnobRow\|record SecretIndicatorRow" KnobInventory.java` → **0** (nested records gone).
- `grep -c "com.vn.agent.admin.config.dto.AiKnobRow" ai-ui-settings-detail-view.xml` → **1**.
- `grep -c "com.vn.agent.admin.config.dto.AiSecretIndicatorRow" ai-ui-settings-detail-view.xml` → **1**.
- `grep -c "@JmixEntity" AiKnobRow.java` → **2** (import + annotation on class) — annotation IS present exactly once on the class.
- `grep -c "@JmixEntity" AiSecretIndicatorRow.java` → **2** (same shape).
- `grep -cE "private\s+\w+\s+(value|rawSecret|secretValue)\s*;" AiSecretIndicatorRow.java` → **0** (SEC-08).
- `grep -c "isAnnotationPresent(JmixEntity.class)" AiUiSettingsKnobInventoryMetamodelTest.java` → **3** (per-DTO assertion + per-loop assertion on descriptor scan).
- `grep -c "contains(\"\$\")" AiUiSettingsKnobInventoryMetamodelTest.java` → **1** (inner-class binding gate).
- `grep -c "Files.walk" AiUiSettingsKnobInventoryMetamodelTest.java` → **3** (one Files.walk on the descriptor scan + locate helper) — file-discovery uses Files.walk as required.

Jmix enhancer output recognized both DTOs:
```
DTO: [com.vn.agent.admin.config.dto.AiKnobRow, com.vn.agent.admin.config.dto.AiSecretIndicatorRow]
```

## Decisions Made

- **Plain Java classes, not records, for the new DTOs.** Jmix non-persistent DTO registration requires settable id (`@JmixGeneratedValue` populates the id field via reflection through a setter or direct field write). Records cannot expose setters; while Jmix can sometimes handle record DTOs, the safest and most-documented path for `@JmixEntity` non-persistent DTOs is the JavaBeans-style class with default ctor + setters. The plan locked this path.
- **Retain ONE record-compat accessor — `AiKnobRow.requiresRestart()`.** The existing `@Supply` renderer in `AiUiSettingsDetailView` reads `row.requiresRestart()` (boolean predicate idiom). Migrating that call site to `row.isRequiresRestart()` would be cosmetic churn with no functional benefit. The bare-name overload is additive to the JavaBean `isRequiresRestart` getter and does not register a second metamodel property (the JavaBean property name `requiresRestart` is computed from the `is*` getter, not from this bare-name overload).
- **NO record-compat accessors on `AiSecretIndicatorRow`.** Avoids any risk of Jmix metamodel duplicate-property scan flagging an overload as a competing property. All callers use `isConfigured()` / `getKey()` / `getDisplayMessageKey()`.
- **SEC-08 double-tested intentionally.** The new test class duplicates the field-name regex assertion. If a future plan accidentally removes `noValueFieldOnSecretIndicatorRow` from `SecretRedactionInvariantsTest` (e.g. by deleting a "redundant" test), the metamodel test still fires the same regex against the same class. Either test passing alone is enough; both failing is the only break path.
- **Files.walk for file discovery (Warning #9 — locked).** Mirrors `SecretRedactionInvariantsTest.noSecretBoundEditable` byte-for-byte so future maintainers see two structurally identical file-system invariant tests in the same package.

## Deviations from Plan

### Auto-fixed Issues

None. The plan was executed exactly as written.

### Scope Deviations

**1. `KnobInventoryClassificationTest` did not require any changes**
- **Plan listed:** the test as part of the Task 1 file set, with `KnobInventory.KnobRow` / `SecretIndicatorRow` references requiring migration.
- **Reality:** the test never referenced the row types — it walks `@KnobMetadata` reflection on the 10 phase-targeted carrier classes only. `grep "KnobRow\|SecretIndicatorRow\|\.key()\|\.resolvedValue()\|\.configured()" KnobInventoryClassificationTest.java` returns zero matches before and after this plan.
- **Resolution:** left the test file untouched. The plan-listed file `KnobInventoryClassificationTest.java` is omitted from Task 1's commit; the green run still passes 4 methods unchanged.
- **Risk:** zero — the test continues to assert the @KnobMetadata coverage contract over the same 10 carriers.

## Authentication Gates

None.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries listed in `16-08-PLAN.md`:

- **T-16-08-01 (Information Disclosure — Tier-3 secrets surface)** — mitigated by `AiSecretIndicatorRow` carrying NO `value` / `raw` / `secret` field; the new `noValueFieldOnSecretIndicatorRow` invariant in `SecretRedactionInvariantsTest` plus the reflective field-name regex in `AiUiSettingsKnobInventoryMetamodelTest#aiSecretIndicatorRowCarriesJmixEntityAndJmixId` both enforce. Plan 16-06's T-16-01 mitigation preserved byte-for-byte (3 original legs of `SecretRedactionInvariantsTest` continue to pass).
- **T-16-08-02 (Tampering — Jmix metamodel scanner)** — mitigated by `viewDescriptorBindsTopLevelJmixEntityClassesOnly` (build-time fail-fast gate). Future plans that bind a nested-class DTO are caught at build time, not at first user navigation.
- **T-16-08-03 (Denial of Service — KnobInventoryScanner)** — accept (unchanged from Plan 16-06's disposition). Scanner runs once at boot; DTO promotion did not change scan complexity or the prefix-filter / EnumerablePropertySource walk.
- **Twin-publisher (R2) — single-publish-site invariant** — `SecretRedactionInvariantsTest.singlePublishSiteForAiSettingsChangedEvent` continues to pass (verified in the Task 1 test run). No new `publishEvent(new AiSettingsChangedEvent(...))` site introduced.

## Known Stubs

None. Production code in both shipped tasks (1 + 2) is complete — no placeholder methods, no `TODO` markers in the new DTO classes or the new test class.

## Deferred Issues

- **Pre-existing Phase 11/13 Spring context boot regression** — continues to block `@SpringBootTest` in the ai-agent module (documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`). The new test uses pure-JUnit + reflection + `Files.walk` as the documented workaround. When the boot regression is fixed in a future hardening pass, the new test's three legs port unchanged to a `@SpringBootTest`-driven equivalent that asserts directly against `io.jmix.core.Metadata#getSession()` metaclass set.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/dto/AiKnobRow.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/dto/AiSecretIndicatorRow.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsKnobInventoryMetamodelTest.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java` (modified) — FOUND
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/SecretRedactionInvariantsTest.java` (modified) — FOUND

Commits exist:
- `a962dd8` (Task 1 — DTO promotion + 8-file mechanical migration) — FOUND
- `00a8bd9` (Task 2 — metamodel-resolution boot test) — FOUND

Task 3 status: AWAITING_MANUAL_VERIFICATION — user must navigate to http://localhost:8088/ai-agent/ui-settings and confirm the 5 plan-listed steps before the plan is marked complete.
