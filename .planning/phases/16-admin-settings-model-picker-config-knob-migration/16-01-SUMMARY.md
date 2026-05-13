---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 01
subsystem: admin-config
tags: [jmix, spring-boot, scaffolding, audit]
requires: []
provides:
  - AiSettingsChangedEvent (typed ApplicationEvent + Kind enum)
  - KnobMetadata (runtime annotation + Tier enum)
  - AuditKind.MODEL_VALIDATION_FAILURE constant
  - AuditWriter.writeAuditEvent(String kind, ...) additive overload
  - 8 @Disabled Wave-0 test scaffolds (branch stays green between plans)
affects:
  - AuditWriter (additive refactor — writeToolCall body extracted to private helper; existing callers unchanged)
  - AuditKind (additive constant + docstring note for Plan 02 column widening)
tech_stack_added: []
patterns_used:
  - "Plan 10-06 R2 single-publish-site precedent (LlmExposureChangedEvent → AiSettingsChangedEvent)"
  - "Additive overload + shared private helper (writeToolCall + writeAuditEvent both → writeAuditRow)"
  - "@Disabled + @Tag class-level scaffold-green-branch pattern (codex HIGH Concern #3)"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiSettingsChangedEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobMetadata.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/SecretRedactionInvariantsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsResolverReadThroughTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsBeanValidationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/KnobInventoryClassificationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplModelValidationFallbackTest.java
key_files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditKind.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
decisions:
  - "Inherit D-04 ordering verbatim: AiSettingsChangedEvent.Kind enum declares PARAMETERS first, UI_SETTINGS second."
  - "@KnobMetadata targets RECORD_COMPONENT + FIELD + METHOD so both record-style and setter-style @ConfigurationProperties carriers can be tagged (RESEARCH §10)."
  - "AuditWriter.writeAuditEvent reuses a shared private writeAuditRow helper extracted from writeToolCall; existing writeToolCall callers see zero behavior change."
  - "All 8 Wave-0 scaffolds carry BOTH class-level @Disabled AND @Tag(\"phase-16-scaffold\") + fail()-bodied method defense-in-depth, so :ai-agent:test stays GREEN between plans (codex HIGH Concern #3)."
  - "AuditKind.MODEL_VALIDATION_FAILURE = \"MODEL_VALIDATION_FAILURE\" (24-char literal verbatim per D-05); Plan 02 widens KIND column varchar(16) → varchar(32)."
metrics:
  duration: ~12 min
  tasks_completed: 3
  files_created: 10
  files_modified: 2
  completed_date: 2026-05-13
---

# Phase 16 Plan 01: Wave-0 Foundation Scaffolding Summary

Lay down the cross-wave foundation symbols (`AiSettingsChangedEvent` typed event, `KnobMetadata` annotation, `AuditKind.MODEL_VALIDATION_FAILURE` constant, additive `AuditWriter.writeAuditEvent(String kind, ...)` overload) and eight class-level-`@Disabled` test scaffolds tagged `phase-16-scaffold` so the branch stays green between plans.

## What Shipped

### Task 1 — Three production foundation symbols (commit 15150c5)

- `com.vn.agent.admin.config.AiSettingsChangedEvent` extends `ApplicationEvent`, nested `Kind { PARAMETERS, UI_SETTINGS }`, locked single-publish-site invariant in class Javadoc.
- `com.vn.agent.admin.config.KnobMetadata` annotation, `@Retention(RUNTIME)`, `@Target({RECORD_COMPONENT, FIELD, METHOD})`, nested `Tier { TIER_1, TIER_2, TIER_3 }`, `requiresRestart()` + `displayMessageKey()` members.
- `com.vn.agent.spi.AuditKind.MODEL_VALIDATION_FAILURE` sibling constant + inline docstring note that Plan 02 widens `KIND` to `varchar(32)`.

### Task 2 — Eight class-level-`@Disabled` test scaffolds (commit 20fcb15)

Each scaffold carries `@Disabled("Phase 16 Wave 0 scaffold — Plan <NN> ...")` + `@Tag("phase-16-scaffold")` and `fail()`-bodied methods so the branch stays green AND downstream plans can't accidentally silent-pass by removing `@Disabled` without filling in the body.

| Test class | Plan owner | Methods | Subject |
|---|---|---|---|
| `ChatModelCatalogAllowlistTest` | Plan 02 | 3 | TEST-20 catalog subset / exactly-one-default / default drift gate |
| `SecretRedactionInvariantsTest` | Plan 06 | 3 | SEC-08 three legs (XML secret bindings, @ConditionalOnProperty bindings, single-publish-site) |
| `AiSettingsChangedEventListenerInvariantTest` | Plan 04 | 4 | D-04 publish contract for active/inactive parameters + ui-settings + source scan |
| `AiUiSettingsResolverReadThroughTest` | Plan 03 | 8 | D-03 read-through across 5 Tier-1 clusters |
| `AiUiSettingsBeanValidationTest` | Plan 02 | 8 | Bean-validation bounds incl. sentinel `-1` + new `taskFileMaxFileSizeBytes` |
| `KnobInventoryClassificationTest` | Plan 06 | 3 | D-02 three-layer (annotation + scanner + secret-pattern mask) |
| `TtlConfigSentinelSurvivesAiUiSettingsTest` | Plan 03 | 3 | Phase 13.1 sentinel invariant preserved when source flips DB → property |
| `DefaultChatServiceImplModelValidationFallbackTest` | Plan 07 | 7 | D-05 catch+reissue incl. codex HIGH Concerns #8 (direct `RestClientResponseException`) + #9 (user-visible fallback notification) |

### Task 3 — `AuditWriter.writeAuditEvent(String kind, ...)` overload (commit 8a8c3ad)

- New `@Transactional(REQUIRES_NEW)` public method `writeAuditEvent(String kind, UUID parentId, …)` with parameter signature mirroring `writeToolCall` exactly.
- Row-build body extracted from `writeToolCall` into a new `private UUID writeAuditRow(String kind, …)` helper.
- `writeToolCall` now delegates to `writeAuditRow(AuditKind.TOOL, …)`; `writeAuditEvent` delegates to `writeAuditRow(kind, …)`.
- Closes consensus HIGH Concern #1 from `16-REVIEWS.md` (opencode + codex): Plan 07 can call `auditWriter.writeAuditEvent(AuditKind.MODEL_VALIDATION_FAILURE, …)` without further `AuditWriter` edits.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:compileJava :ai-agent:compileTestJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.admin.config.ChatModelCatalogAllowlistTest"
→ BUILD SUCCESSFUL (scaffold SKIPPED via class-level @Disabled — branch stays green)
```

- `AiSettingsChangedEvent.Kind.PARAMETERS` and `AiSettingsChangedEvent.Kind.UI_SETTINGS` accessible as nested enums.
- `KnobMetadata.Tier.TIER_1 / TIER_2 / TIER_3` accessible as nested enums.
- `AuditKind.MODEL_VALIDATION_FAILURE.equals("MODEL_VALIDATION_FAILURE") == true`.
- `AuditWriter` exposes both `writeToolCall(...)` (unchanged signature; delegates to helper) and `writeAuditEvent(String kind, ...)` (new, additive).
- Eight Wave-0 test scaffolds compile under `:ai-agent:compileTestJava` and are SKIPPED (not failing) when run because every class carries class-level `@Disabled`.

## Decisions Made

- **Inherit D-04 ordering verbatim**: `AiSettingsChangedEvent.Kind` enum declares `PARAMETERS` first, `UI_SETTINGS` second. Locked in source order so Plan 04 entity-listener fan-out and Phase 18 cache eviction can switch on the ordinal cleanly if needed.
- **Annotation targets**: `@KnobMetadata` targets `RECORD_COMPONENT + FIELD + METHOD` (three targets) — covers both Java-record-style `@ConfigurationProperties` carriers (the common modern shape in this codebase) and classic setter-style classes plus record accessor methods (RESEARCH §10).
- **Shared `writeAuditRow` helper** over duplicated bodies: prevents drift between `writeToolCall` and `writeAuditEvent`. Both public entry points keep their `@Transactional(REQUIRES_NEW)` annotation so the audit pipeline transactional invariant (D-11) is preserved at the public boundary.
- **Scaffold-green strategy**: every scaffold carries BOTH class-level `@Disabled` (JUnit always honors regardless of tag config) AND `@Tag("phase-16-scaffold")` (CI can also filter by tag). `fail()`-bodied methods stay present as defense-in-depth so a future plan that removes `@Disabled` without filling in the body FAILS rather than silently passing.

## Deviations from Plan

**None — plan executed exactly as written.**

The scaffold class for `DefaultChatServiceImplModelValidationFallbackTest` lives in package `com.vn.agent` to mirror the existing `DefaultChatServiceImplStreamFallbackTest` per the plan's `read_first` instruction; the other scaffolds live under `com.vn.agent.admin.config` (a new package) and `com.vn.agent.taskfile` (existing). The new package directory was created implicitly by the Write tool — no separate action required.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries already listed in `16-01-PLAN.md`:

- T-16-02 (`KnobMetadata` annotation tampering) — mitigated: Tier enum is the type-safe SoT, SEC-08 cross-checks live in `SecretRedactionInvariantsTest` + `KnobInventoryClassificationTest` (both scaffolded here, filled in Plan 06).
- T-16-04 (`AuditKind.MODEL_VALIDATION_FAILURE` information disclosure) — mitigated: this plan only locks the kind string; row sanitisation happens in Plan 07's writer.

## Known Stubs

The eight `@Disabled` test scaffolds are intentional stubs by plan design — each downstream plan (02/03/04/06/07) removes the `@Disabled` + `@Tag` and fills in the body. The plan owner is recorded both in the class-level `@Disabled` message and in this summary's task-2 table. Production code in Tasks 1 + 3 ships with no stubs — every method has a real body and the new symbols are reachable but intentionally unused until Plans 04/06/07 wire them.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiSettingsChangedEvent.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobMetadata.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditKind.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` (modified) — FOUND
- 8 scaffold test files under `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/` + `…/taskfile/` + `…/` — all FOUND

Commits exist:
- `15150c5` (Task 1) — FOUND
- `20fcb15` (Task 2) — FOUND
- `8a8c3ad` (Task 3) — FOUND
