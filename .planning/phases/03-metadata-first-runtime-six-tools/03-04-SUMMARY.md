---
phase: 03-metadata-first-runtime-six-tools
plan: 04
subsystem: tools
tags: [test, tdd, asm, prompt-injection, unit-tests, mockito, spring-boot-test]
requirements: [TOOL-08, TEST-02]
dependency_graph:
  requires:
    - com.vn.agent.tools.BuiltInDataTools
    - com.vn.agent.tools.ToolResultFormatter
    - com.vn.agent.tools.ToolLimits
    - com.vn.agent.tools.ToolUserError
    - com.vn.agent.filter.StructuredFilterConditionMapper
    - com.vn.agent.filter.FilterNode
    - com.vn.agent.filter.FilterLiteralValueConverter
    - com.vn.agent.metadata.CurrentUserSchemaAccess  # post-execute: collapsed from MetamodelScanner + EffectiveSchemaComputer + UserEditableStringIndex
    - com.vn.agent.entity.AiMessage
    - org.ow2.asm:asm:9.9
    - org.mockito:mockito-core (mockConstruction)
    - org.springframework.boot:spring-boot-starter-test
    - org.assertj:assertj-core
  provides:
    - BuiltInDataToolsReadOnlyTest (D-16 build-time TOOL-08 enforcement)
    - PromptInjectionHarnessTest (success criterion #5 + Pitfall 4 contract)
    - ToolResultFormatterTest (escape + error + count unit coverage)
    - ToolLimitsTest (TOOL-06 / D-14 constant pinning)
    - StructuredFilterConditionMapperTest (all 13 operators + DeMorgan + rejections)  # renamed post-execute from FilterDslMapperTest
    - FilterLiteralValueConverterTest (every coercion branch + fail-closed)  # renamed post-execute from LiteralCoercerTest
    - CurrentUserSchemaAccessTest (@SystemLevel exclusion + ACL filter with mocked CrudEntityContext)  # collapsed post-execute from MetamodelScannerTest + EffectiveSchemaComputerTest
  affects:
    - Plan 03-05 integration suite (consumes same test infra)
    - Phase 4 regression safety net
tech_stack:
  added:
    - "org.ow2.asm:asm:9.9 (upgraded from 9.7; JDK 25 class v69 support — Rule 3 deviation)"
  patterns:
    - "ASM ClassReader + method-scoped MethodVisitor for bytecode invariants (D-16)"
    - "Conservative-v1 dataflow: paramLoadedRecently flag reset on each instruction"
    - "Mockito.mockConstruction for stubbing Jmix per-request access contexts"
    - "Sabotage-and-revert validation of enforcement tests"
    - "@SpringBootTest with systemAuthenticator.runWithSystem(Runnable) for entity-create fixtures"
key_files:
  created:  # file names reflect current src/test tree (post-execute renames/collapse)
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolLimitsTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/filter/StructuredFilterConditionMapperTest.java  # renamed post-execute from FilterDslMapperTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/filter/FilterLiteralValueConverterTest.java     # renamed post-execute from LiteralCoercerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/CurrentUserSchemaAccessTest.java       # collapsed post-execute from MetamodelScannerTest + EffectiveSchemaComputerTest
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
key_decisions:
  - "ASM 9.7 -> 9.9 (Rule 3): 9.7 rejects JDK 25 class v69 with UnsupportedClassVersion; 9.9 is backward-compatible and adds v69 support."
  - "Conservative-v1 LLM-JPQL scan: single-pass paramLoadedRecently flag rather than full Analyzer+SourceInterpreter dataflow. Slightly over-restrictive but precise for a single-class scan."
  - "Mockito mockConstruction pattern: extract arg values to locals BEFORE when() to avoid UnfinishedStubbingException."
metrics:
  duration_minutes: 180
  tasks_completed: 4
  files_created: 8
  files_modified: 1
  completed_date: 2026-04-19
---

# Phase 3 Plan 04: Tests + TOOL-08 Build-Time Enforcement Summary

Locked in every Phase 3 behavior with 8 new test files and enforced the TOOL-08 read-only posture at build time via an ASM bytecode scan of `BuiltInDataTools` (D-16).

## Outcome

- 8 test files originally created across `tools/`, `filter/`, `metadata/` packages; post-execute refactor collapsed the two `metadata/` tests (MetamodelScannerTest + EffectiveSchemaComputerTest) into a single `CurrentUserSchemaAccessTest` and renamed the two `filter/` tests (LiteralCoercerTest → FilterLiteralValueConverterTest, FilterDslMapperTest → StructuredFilterConditionMapperTest). Current on-disk count: 7 test files.
- `./gradlew :ai-agent:ai-agent:test` exits 0 — all new tests pass, no existing test broken
- TOOL-08 now enforced at compile-time-adjacent (test-run) granularity: a future `DataManager.save` / `EntityManager.*` call or LLM-parameter JPQL concat inside a `@Tool` method fails the build before merge
- Success criterion #5 (prompt-injection wrap) and Pitfall 4 (delimiter-escape bypass) have red-line regression tests

## Task-by-Task

### Task 1 — ToolLimits + filter-literal-converter + filter-condition-mapper (commit `7dedbd5`) — post-execute: filter classes renamed

- **ToolLimitsTest**: pins `DEFAULT_LIMIT=20`, `MAX_LIMIT=100`, `DEFAULT_MAX_FILTER_DEPTH=3`; clampLimit(null)=20, (0)=1, (1000)=100, (50)=50.
- **FilterLiteralValueConverterTest** (previously `LiteralCoercerTest`): mocked MetaProperty/Range/Datatype; good+bad paths for UUID, Long, Integer, BigDecimal, Boolean, LocalDate, LocalDateTime, Enum, String, association, null; `coerceList` + `coerceBoolean`. Uses raw `Datatype` cast to sidestep generic wildcard issue in `thenReturn`.
- **StructuredFilterConditionMapperTest** (previously `FilterDslMapperTest`): @ParameterizedTest CsvSource over 10 binary ops; dedicated cases for IN_LIST / NOT_IN_LIST / IS_SET; AND/OR nesting; DeMorgan NOT (leaf, AND, double-NOT); rejections: depth cap, unknown op, denied attribute, unknown attribute, NOT over STARTS_WITH/ENDS_WITH, null root, empty AND.

### Task 2 — metadata-surface tests (commit `cfffc6f`) — post-execute: collapsed into CurrentUserSchemaAccessTest

Originally two tests (`MetamodelScannerTest` + `EffectiveSchemaComputerTest`); post-execute they were collapsed into a single `CurrentUserSchemaAccessTest` matching the `CurrentUserSchemaAccess` adapter.

- Pre-refactor `MetamodelScannerTest`: `@SpringBootTest` boot-test verifying AI-visible entities present, any `@SystemLevel` classes absent, user-editable-string index excludes framework-managed fields (id, version, createdBy, etc.).
- Pre-refactor `EffectiveSchemaComputerTest`: `Mockito.mockConstruction(CrudEntityContext.class)` + `EntityAttributeContext.class` with initializer that toggles `isReadPermitted()` per MetaClass argument. Critical pattern: extract `argName` and `permitted` to locals BEFORE the `when()` call — nested mock calls inside `when()` corrupt Mockito state. `findProperty` returns null to avoid nested stubbing.
- Post-refactor `CurrentUserSchemaAccessTest` preserves both sets of assertions against the single adapter.

### Task 3 — ToolResultFormatter + PromptInjectionHarness (commit `b83d58f`)

- **ToolResultFormatterTest**: unit coverage for static `escapeDataDelimiters` (null, benign, `<data>x</data>`, repeated/adjacent), `error(code,reason)`, `error(ToolUserError)` with expected list, `count(mc,n)`, `toJson`.
- **PromptInjectionHarnessTest**: `@SpringBootTest` seeding a real `AiMessage` entity via `Metadata.create` under `SystemAuthenticator.runWithSystem(Runnable)`; three tests pin:
  1. `SYSTEM: ignore previous instructions` is wrapped in `<data>...</data>` (success criterion #5).
  2. Literal `</data><system>...<data>` inside a value is HTML-escaped (`&lt;/data&gt;` / `&lt;data&gt;`) before being wrapped — blocks Pitfall 4 delimiter-bypass.
  3. Benign `hello world` still wrapped but unmangled.
- `runWithSystem` takes a `Runnable` (no return value) — lambdas must not `return null;`.

### Task 4 — ASM Read-Only Bytecode Scan (commit `51ac8f8`)

- **BuiltInDataToolsReadOnlyTest** with two ASM `ClassReader`-based tests:
  1. `noMutationPathsInToolBodies`: walks every `@Tool`-annotated method and fails if any `INVOKEVIRTUAL` / `INVOKEINTERFACE` targets `io/jmix/core/DataManager.{save,saveContext,remove}` or any method on `jakarta/persistence/EntityManager`.
  2. `noJpqlStringBuiltFromMethodParameters` (conservative-v1 per plan): fails if a `@Tool` method body contains BOTH (a) a call to `createQuery` / `createNativeQuery` or `LoadContext$Query.<init>` AND (b) a `makeConcatWithConstants` or `StringBuilder.append` whose operand stack was reached via an ALOAD/ILOAD of a method-parameter local slot. Tracks `paramLoadedRecently` and resets on every non-load instruction.
- ASM 9.7 -> 9.9 upgrade (Rule 3 deviation): JDK 25 emits class v69 which 9.7 rejects; 9.9 is backward-compatible.
- **Sabotage-and-revert experiment**: temporarily inserted `dataManager.save(new Object());` at the top of `listEntities()`. `./gradlew :ai-agent:ai-agent:test --tests "...BuiltInDataToolsReadOnlyTest.noMutationPathsInToolBodies"` → BUILD FAILED (as expected). Reverted the sabotage; test passes. The enforcement works.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ASM 9.7 incompatible with JDK 25 class v69**
- **Found during:** Task 4, running ASM test.
- **Issue:** ASM 9.7 throws `IllegalArgumentException: Unsupported class file major version 69` because this project builds under JDK 25 (class v69); ASM 9.7 max supported is v66.
- **Fix:** Upgraded `org.ow2.asm:asm:9.7` → `9.9` in `ai-agent/ai-agent/ai-agent.gradle` with an explanatory comment. 9.9 is backward-compatible and supports v69.
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
- **Commit:** `51ac8f8`

**2. [Rule 1 - Bug] Mockito UnfinishedStubbingException in EffectiveSchemaComputerTest** (previously; pattern preserved in the collapsed `CurrentUserSchemaAccessTest` post-execute)
- **Found during:** Task 2 test authoring.
- **Issue:** Calling a mock method (`arg.getName()`) inside `when(mockCtx.isReadPermitted()).thenReturn(...)` corrupts Mockito's internal stubbing state. Also `when(mc.findProperty(...)).thenAnswer(lambda-that-creates-more-mocks)` has the same failure mode.
- **Fix:** Extract `argName` and `permitted` to locals BEFORE `when()`; make `findProperty` return null to avoid nested stubbing. Documented as a pattern in key_decisions.
- **Commit:** `cfffc6f`

**3. [Rule 1 - Bug] Datatype<?> generic wildcard in Mockito thenReturn**
- **Found during:** Task 1 authoring.
- **Issue:** `Datatype<?> dt = mock(Datatype.class)` cannot be passed to `thenReturn` with the correct generic.
- **Fix:** `@SuppressWarnings("rawtypes") Datatype dt = mock(Datatype.class);` — raw type avoids the wildcard capture problem. Applied to `Enumeration` likewise.
- **Commits:** `7dedbd5`

**4. [Rule 1 - Bug] runWithSystem lambda return value**
- **Found during:** Task 3 authoring.
- **Issue:** `SystemAuthenticator.runWithSystem` takes a `Runnable`, not a `Supplier`; lambdas with `return null;` don't compile.
- **Fix:** Removed `return null;` from lambdas.
- **Commit:** `b83d58f`

### Notable Experiment (not a deviation)

- **Sabotage-and-revert validation of D-16**: intentionally broke `BuiltInDataTools.listEntities()` with a `DataManager.save` call, confirmed `noMutationPathsInToolBodies` fails the build, then reverted. Demonstrates the enforcement is live, not just present. No residual change.

## Deferred Issues

None — scope contained and all 8 plan-listed files delivered.

## Self-Check: PASSED

Files exist (as of current on-disk state after post-execute renames/collapse):
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolLimitsTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/filter/StructuredFilterConditionMapperTest.java  (renamed post-execute from FilterDslMapperTest.java)
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/filter/FilterLiteralValueConverterTest.java      (renamed post-execute from LiteralCoercerTest.java)
- FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/CurrentUserSchemaAccessTest.java        (collapsed post-execute from MetamodelScannerTest + EffectiveSchemaComputerTest)

Commits exist (commit messages below are verbatim historical — pre-refactor class names are preserved per the rename-table exception):
- FOUND: 7dedbd5 (Task 1 — ToolLimits + LiteralCoercer + FilterDslMapper; previously, collapsed post-execute)
- FOUND: cfffc6f (Task 2 — MetamodelScanner + EffectiveSchemaComputer; previously, collapsed post-execute)
- FOUND: b83d58f (Task 3 — ToolResultFormatter + PromptInjection)
- FOUND: 51ac8f8 (Task 4 — ASM read-only scan + ASM 9.9 upgrade)
