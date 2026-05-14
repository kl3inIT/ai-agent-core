---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 02
subsystem: admin-config
tags: [jmix-entities, jmix-liquibase, jmix-i18n, bean-validation]
requires:
  - AiUiSettings (Phase 12 singleton entity + SINGLETON_ID lock)
  - AiUiSettingsBeanValidationTest scaffold (Plan 16-01)
  - AI_AGENT_AUDIT_EVENT.KIND column (Phase 7.2)
provides:
  - 12 nullable Tier-1 columns on AiUiSettings (D-01 + codex HIGH Concern #4)
  - Liquibase changelog 120-ai-ui-settings-tier1-knobs.xml (+ AI_AGENT_AUDIT_EVENT.KIND widening)
  - 28 D-09 locale keys × 2 bundles (5 section + 12 field + 11 validation)
  - AiUiSettingsBeanValidationTest flipped green (8 methods)
affects:
  - AiUiSettings.java (additive — 12 fields + 12 getter/setter pairs)
  - AI_UI_SETTINGS schema (additive — 12 nullable columns)
  - AI_AGENT_AUDIT_EVENT.KIND varchar(16) → varchar(32)
tech_stack_added: []
patterns_used:
  - "Phase 12 D-15 flat-column precedent (defaultSurface) for nullable Tier-1 columns"
  - "071-widen-ai-audit-outcome.xml modifyDataType precedent for KIND widening"
  - "feedback_jmix_messages_over_spring (root bundle, key parity across locales)"
  - "JSR-380 Validator-driven unit tests (Plans 13.1-06 / 13.1-07 / 14-01 / 14-02 / 16-01 precedent)"
key_files_created:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/120-ai-ui-settings-tier1-knobs.xml
key_files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsBeanValidationTest.java
decisions:
  - "Rule 1 auto-fix: Liquibase modifyDataType targets actual table name AI_AGENT_AUDIT_EVENT (plan said AI_AUDIT_EVENT); aligned with 030-ai-audit-event.xml + 071-widen-ai-audit-outcome.xml precedents"
  - "Rule 3 auto-fix: AiUiSettingsBeanValidationTest implemented as pure JSR-380 Validator unit test instead of @SpringBootTest+UnconstrainedDataManager.save — the ai-agent module Spring context boot is blocked by the documented Phase 11/13 AnnotatedResourceRoleProvider regression; same JSR-380 engine runs at JPA flush so contract is preserved"
  - "Bean-validation bounds applied verbatim from RESEARCH §8 table including codex HIGH Concern #4 extension (TASK_FILE_MAX_FILE_SIZE_BYTES with @Min(1024L) @Max(524_288_000L), distinct from UPLOAD_MAX_FILE_SIZE_BYTES)"
  - "TASK_FILE_MAX_FILE_SIZE_BYTES carries NO sentinel (always positive); the three task-file knobs preserving Phase 13.1 sentinel -1 are TTL_SECONDS, PER_TURN_MAX_FILES, PER_TURN_MAX_TOTAL_BYTES"
  - "Setter names match field names verbatim — Pitfall 1 / EclipseLink-weaver invariant preserved across all 12 new setters (no @Transient bridges)"
metrics:
  duration: ~20 min
  tasks_completed: 3
  files_created: 1
  files_modified: 4
  completed_date: 2026-05-13
---

# Phase 16 Plan 02: AiUiSettings Tier-1 Schema Lock Summary

12 nullable Tier-1 runtime-knob columns added to the AiUiSettings singleton with Jakarta `@Min`/`@Max` bean-validation bounds; additive Liquibase changelog `120-ai-ui-settings-tier1-knobs.xml` ships the column adds plus the `AI_AGENT_AUDIT_EVENT.KIND` widening to `varchar(32)` (Pitfall 5 — for Plan 01's 24-character `MODEL_VALIDATION_FAILURE` constant); 28 new D-09 locale keys land in both EN and VI bundles with verified locale parity; `AiUiSettingsBeanValidationTest` flips from `@Disabled` scaffold to 8 green test methods covering every bound + the Phase 13.1 sentinel `-1` invariant + the codex HIGH Concern #4 distinction between task-file and KB/RAG upload caps.

## What Shipped

### Task 1 — 12 Tier-1 columns on AiUiSettings (commit 542944d)

Added the following nullable boxed-typed fields to `AiUiSettings.java` in order, between `defaultSurface` and the `@CreatedBy` block, each with `@Column(name=...)` only (no `nullable=false`) and bean-validation bounds matching RESEARCH §8:

| Column | Field | Type | Annotation | Sentinel |
|---|---|---|---|---|
| `TASK_FILE_TTL_SECONDS` | `taskFileTtlSeconds` | `Long` | `@Min(-1L) @Max(604_800L)` | `-1` disables (Phase 13.1) |
| `TASK_FILE_PER_TURN_MAX_FILES` | `taskFilePerTurnMaxFiles` | `Integer` | `@Min(-1) @Max(100)` | `-1` disables |
| `TASK_FILE_PER_TURN_MAX_TOTAL_BYTES` | `taskFilePerTurnMaxTotalBytes` | `Long` | `@Min(-1L) @Max(524_288_000L)` | `-1` disables (500 MB cap) |
| `TASK_FILE_MAX_FILE_SIZE_BYTES` | `taskFileMaxFileSizeBytes` | `Long` | `@Min(1024L) @Max(524_288_000L)` | none (codex HIGH Concern #4 — chat upload cap, distinct from RAG cap) |
| `MUTATION_CONFIRMATION_REQUIRED` | `mutationConfirmationRequired` | `Boolean` | no bounds | — |
| `MUTATION_IDEMPOTENCY_TTL_SECONDS` | `mutationIdempotencyTtlSeconds` | `Long` | `@Min(60L) @Max(604_800L)` | none (1 min – 7 days) |
| `MUTATION_BULK_MAX_ROWS` | `mutationBulkMaxRows` | `Integer` | `@Min(1) @Max(500)` | none (Phase 13 D-02 DoS guard) |
| `PROMPT_ENTITY_INVENTORY_LIMIT` | `promptEntityInventoryLimit` | `Integer` | `@Min(1) @Max(500)` | none |
| `TOOLS_MAX_FILTER_DEPTH` | `toolsMaxFilterDepth` | `Integer` | `@Min(1) @Max(10)` | none |
| `TITLE_MAX_CONTEXT_MESSAGES` | `titleMaxContextMessages` | `Integer` | `@Min(1) @Max(50)` | none |
| `TITLE_MIN_ASSISTANT_MESSAGES_TRIGGER` | `titleMinAssistantMessagesTrigger` | `Integer` | `@Min(1) @Max(10)` | none |
| `UPLOAD_MAX_FILE_SIZE_BYTES` | `uploadMaxFileSizeBytes` | `Long` | `@Min(1024L) @Max(524_288_000L)` | none (KB/RAG cap) |

All 12 setters use direct field assignment with names matching field names verbatim — the EclipseLink weaver writes the setter intercepts at byte-code time (see existing `setDefaultSurface` docstring lines 102-108). No `@Transient` bridges; no composition or coercion inside the entity. Imports for `jakarta.validation.constraints.Min` and `Max` added.

### Task 2 — Liquibase changelog 120-ai-ui-settings-tier1-knobs.xml (commit fcd8d91)

New file under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/` (sibling of `080-ai-ui-settings.xml` and `110-ai-extraction-draft.xml`):

- **`<changeSet id="1" author="ai-agent">`** — single `<addColumn tableName="AI_UI_SETTINGS">` block adding 12 nullable columns (`bigint` for Long, `int` for Integer, `boolean` for Boolean) with NO `defaultValue` and NO `<constraints nullable="false"/>` — null = fall-through to `module.properties` (Plan 03 resolver responsibility).
- **`<changeSet id="2" author="ai-agent">`** — `<modifyDataType tableName="AI_AGENT_AUDIT_EVENT" columnName="KIND" newDataType="varchar(32)"/>` per RESEARCH Pitfall 5. Precedent: `071-widen-ai-audit-outcome.xml` does the same for the OUTCOME column. Plan 01's `AuditKind.MODEL_VALIDATION_FAILURE = "MODEL_VALIDATION_FAILURE"` (24 chars) requires the wider column.

Sibling `080-ai-ui-settings.xml` is byte-identical (Phase 12 D-15 invariant preserved). Parent `agentstore-changelog.xml` is byte-identical — the new file is discovered by the existing `<includeAll>`.

### Task 3 — Locale keys + AiUiSettingsBeanValidationTest green (commit 6252643)

**Locale keys (28 per bundle, key parity = 100%):**
- 5 `aiUiSettings.section.*` (taskFile / mutation / promptTools / title / upload)
- 12 `aiUiSettings.field.*` (one per Tier-1 column including the new `taskFileMaxFileSizeBytes`)
- 11 `aiUiSettings.validation.*.range` (no validation key for `mutationConfirmationRequired` — no bounds)

Inserted in both `messages_en.properties` and `messages_vi.properties` between the existing `aiUiSettingsDetail.*` block and the `ConversationListView` section. Sentinel-bearing English keys mention `-1` so admins know the disable affordance; Vietnamese counterparts include the same `-1` cue. All keys live in the root bundle (`feedback_jmix_messages_over_spring`).

**`AiUiSettingsBeanValidationTest` — 8 green methods:**
1. `taskFileTtlBelowMinusOneRejected()` — `-2L` violates `@Min(-1)`.
2. `taskFileTtlMinusOneSentinelAccepted()` — `-1L` sentinel passes; round-trip preserves the value.
3. `taskFileTtlAboveMaxRejected()` — `604_801L` violates `@Max(604_800)`.
4. `mutationBulkMaxRowsBelowMinRejected()` — `0` violates `@Min(1)`.
5. `mutationBulkMaxRowsAboveMaxRejected()` — `501` violates `@Max(500)`.
6. `uploadMaxFileSizeBytesBelowFloorRejected()` — `1023L` violates `@Min(1024)`.
7. `taskFileMaxFileSizeBytesBelowFloorRejected()` — `1023L` violates `@Min(1024)` on the NEW distinct column (codex HIGH Concern #4).
8. `mutationConfirmationRequiredAcceptsAnyBooleanIncludingNull()` — `null`/`false`/`true` all validate clean, getter round-trip preserves each.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:compileJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.admin.config.AiUiSettingsBeanValidationTest"
→ BUILD SUCCESSFUL (8 tests, 0 failures)

(Select-String -Path messages_en.properties -Pattern "^aiUiSettings\.field\.").Count  → 12
(Select-String -Path messages_vi.properties -Pattern "^aiUiSettings\.field\.").Count  → 12
(Select-String -Path messages_en.properties -Pattern "^aiUiSettings\.(field|section|validation)\.").Count  → 28
(Select-String -Path messages_vi.properties -Pattern "^aiUiSettings\.(field|section|validation)\.").Count  → 28

git diff --stat HEAD~3 -- ai-agent/.../agentstore-changelog/080-ai-ui-settings.xml
→ (no changes — Phase 12 D-15 invariant)

git diff --stat HEAD~3 -- ai-agent/.../liquibase/agentstore-changelog.xml
→ (no changes — D-08 invariant)
```

Grep on `<column name=` inside `120-ai-ui-settings-tier1-knobs.xml` returns 12 matches — matches the inventory exactly. Grep on `modifyDataType.*AI_AGENT_AUDIT_EVENT.*KIND.*varchar(32)` returns 1 match.

## Decisions Made

- **Rule 1 auto-fix — corrected the audit-event table name**: the plan's `<action>` text (Task 2) referred to `AI_AUDIT_EVENT` but the actual table name is `AI_AGENT_AUDIT_EVENT` per `030-ai-audit-event.xml` (the `createTable` declaration) and the `071-widen-ai-audit-outcome.xml` precedent. The changelog uses the correct schema name so Liquibase will apply cleanly; documented in the commit message for traceability.
- **Rule 3 auto-fix — JSR-380 Validator-driven test instead of @SpringBootTest+save**: the plan's original `<action>` required `unconstrainedDataManager.save(settings)` and asserting `ConstraintViolationException` at JPA flush. The ai-agent functional module's `@SpringBootTest` boot is blocked by the documented Phase 11/13 `AnnotatedResourceRoleProvider` / `agentstoreEntityManagerFactory` regression (see `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`). Plans 13.1-06, 13.1-07, 14-01, 14-02, and 16-01 all hit the same blocker and switched to pure-Validator unit tests. The Hibernate Validator engine reads the same `@Min`/`@Max` annotations at JPA flush, so the test contract (out-of-range → `ConstraintViolation`) is preserved. When the boot regression is fixed in a future hardening pass, the test bodies can be promoted to `@SpringBootTest` with a one-line change to the test infrastructure call.
- **Bean-validation bounds applied verbatim from RESEARCH §8 + codex HIGH Concern #4 extension**: every column gets the exact `@Min`/`@Max` pair from the table; `TASK_FILE_MAX_FILE_SIZE_BYTES` lands as a separate column from `UPLOAD_MAX_FILE_SIZE_BYTES` per codex Concern #4 (chat task-file upload cap is a distinct knob from RAG/KB upload cap) — both share `@Min(1024L) @Max(524_288_000L)` but are read by different consumers downstream.
- **Setter-name-matches-field-name invariant preserved across all 12 setters**: each setter is the trivial `this.field = value` form per Pattern G / Pitfall 1, with the method name EXACTLY matching the field name. No `@Transient` bridges introduced. The class still compiles against all existing consumers (`AiUiSettingsService`, `AiUiSettingsDetailView`) because the additions are purely additive.
- **Inactive-row sentinel scoped per RESEARCH**: only `TTL_SECONDS`, `PER_TURN_MAX_FILES`, `PER_TURN_MAX_TOTAL_BYTES` carry the Phase 13.1 `-1` sentinel; `TASK_FILE_MAX_FILE_SIZE_BYTES` and `UPLOAD_MAX_FILE_SIZE_BYTES` use a strict `@Min(1024L)` floor (1 KiB) because "disabling per-file size" is not a meaningful semantic for either upload path.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected audit-event table name**
- **Found during:** Task 2 — reading `030-ai-audit-event.xml` to confirm KIND column width.
- **Issue:** The plan's Task 2 `<action>` text references `AI_AUDIT_EVENT` in the `<modifyDataType>` step, but the actual table name (created by `030-ai-audit-event.xml`, widened by `071-widen-ai-audit-outcome.xml`) is `AI_AGENT_AUDIT_EVENT`. Using the plan's literal name would cause Liquibase to fail at apply time with "table not found".
- **Fix:** Used `tableName="AI_AGENT_AUDIT_EVENT"` in the `<modifyDataType>` element.
- **Files modified:** `120-ai-ui-settings-tier1-knobs.xml`
- **Commit:** fcd8d91

**2. [Rule 3 - Blocking issue] Switched test infrastructure from @SpringBootTest to pure JSR-380 Validator**
- **Found during:** Task 3 — attempted `./gradlew :ai-agent:test --tests "...TtlConfigTest.defaultTtlSecondsIs86400"` as the plan's boot-clean proxy verification.
- **Issue:** Spring context boot fails with `BeanCreationException: sec_AnnotatedResourceRoleProvider` → `IllegalArgumentException at SessionImpl.java:63`. This is the documented pre-existing Phase 11/13 `agentstoreEntityManagerFactory` regression (see STATE.md "Blockers" and the Plan 16-01 Summary "Decisions Made → scaffold-green strategy"). All @SpringBootTest classes in the ai-agent functional module are affected; Plans 13.1-06, 13.1-07, 14-01, 14-02, and 16-01 itself document the same blocker and chose Validator-only tests as the workaround.
- **Fix:** `AiUiSettingsBeanValidationTest` instantiates a `Validation.buildDefaultValidatorFactory().getValidator()` once in `@BeforeAll`, then for each scenario builds an `AiUiSettings` via `new AiUiSettings()` (legacy NotNull columns default to non-null in field initialisers), sets the Tier-1 field under test, and asserts that `validator.validate(settings)` either contains or does not contain a violation on the expected `propertyPath`. The same Hibernate Validator that runs at JPA flush time runs here, so the bean-validation contract is identical — only the persistence flush is mocked out.
- **Files modified:** `AiUiSettingsBeanValidationTest.java`
- **Commit:** 6252643
- **Future cleanup:** When the boot regression is fixed in a future hardening pass, this test can be promoted to `@SpringBootTest` + `unconstrainedDataManager.save(...)` + `assertThrows(ConstraintViolationException.class, ...)` in a single mechanical refactor without changing the bound expectations.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries already listed in `16-02-PLAN.md`:

- **T-16-02** (boot-toggle becomes runtime-editable) — mitigated: this plan adds ONLY the 12 Tier-1 columns listed in D-01 + codex HIGH Concern #4; no `@ConditionalOnProperty` toggle gets a JPA column. SEC-08 cross-check lands in Plan 06.
- **T-16-04** (`AI_AGENT_AUDIT_EVENT.KIND` widening) — accepted: widening varchar(16) → varchar(32) preserves existing rows unchanged; mirrors `071-widen-ai-audit-outcome.xml` semantics for the OUTCOME column.
- **T-16-05** (Tier-1 columns) — accepted: the columns themselves carry no end-user-facing secret; admin-only access stays gated by existing `AiAgentAdminRole` `@EntityPolicy` on `AiUiSettings` + singleton-entity `ViewPolicy` (verified in Plan 06).

## Known Stubs

None. All 12 new columns are real `@Column` fields with real getters/setters and real bean-validation bounds; the Liquibase changelog ships real DDL; the locale keys carry real English and Vietnamese values; the test class carries 8 real-assertion methods (no `fail("...")` placeholders remain). The columns ARE intentionally unread by application code at this point — Plan 03 wires them into `AiUiSettingsResolver` and Plan 06 wires them into `AiUiSettingsDetailView`. This is plan-by-design and not a stub.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/120-ai-ui-settings-tier1-knobs.xml` (created) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsBeanValidationTest.java` (modified) — FOUND

Commits exist:
- `542944d` (Task 1) — FOUND
- `fcd8d91` (Task 2) — FOUND
- `6252643` (Task 3) — FOUND
