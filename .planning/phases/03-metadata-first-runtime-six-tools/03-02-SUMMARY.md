---
phase: 03-metadata-first-runtime-six-tools
plan: 02
subsystem: filter-dsl
tags: [filter, dsl, jmix-condition, limits, tool-05, tool-06]
requirements: [TOOL-05, TOOL-06]
dependency_graph:
  requires:
    - io.jmix.core.querycondition.PropertyCondition
    - io.jmix.core.querycondition.LogicalCondition
    - io.jmix.core.metamodel.model.MetaClass
    - io.jmix.core.metamodel.model.MetaProperty
    - io.jmix.core.metamodel.datatype.Datatype
    - com.fasterxml.jackson.annotation.JsonTypeInfo
    - com.vn.agent.metadata.EffectiveSchemaComputer
  provides:
    - com.vn.agent.filter.FilterNode (sealed)
    - com.vn.agent.filter.AndNode
    - com.vn.agent.filter.OrNode
    - com.vn.agent.filter.NotNode
    - com.vn.agent.filter.LeafNode
    - com.vn.agent.filter.LiteralCoercer
    - com.vn.agent.filter.FilterDslMapper
    - com.vn.agent.tools.ToolLimits
    - com.vn.agent.tools.ToolErrorDto
    - com.vn.agent.tools.ToolUserError
  affects:
    - Plan 03-03 BuiltInDataTools (consumes FilterDslMapper.map, ToolLimits.clampLimit, ToolUserError)
    - Plan 03-04 ToolResultFormatter (serializes ToolErrorDto)
    - Plan 03-05 BuiltInDataToolsTest (pins ToolLimits constants; exercises every operator branch)
tech_stack:
  added: []
  patterns:
    - "Sealed interface + exhaustive switch — compiler-enforced completeness (Java 17)"
    - "Jackson polymorphic DEDUCTION deserialization for DSL JSON shape (D-06)"
    - "DeMorgan NOT expansion via negation flag (Pitfall 6) — no raw-JPQL escape hatch"
    - "Strict fail-closed literal coercion (D-07) — structured errors never leak stack traces"
    - "@Component + constructor injection only (CLAUDE.md)"
key_files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterNode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/AndNode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/OrNode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/NotNode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LeafNode.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterDslMapper.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolLimits.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
decisions:
  - "Preserved DSL operator token DOES_NOT_CONTAIN on the LLM-facing surface even though Jmix 2.8 renamed the constant to NOT_CONTAINS — the mapper accepts both DSL spellings and always emits the real Jmix constant."
  - "NOT over STARTS_WITH/ENDS_WITH fails with `not_negatable` (T-03-10 accept). DeMorgan cannot express prefix/suffix negation without regex; LLM can rewrite or use DOES_NOT_CONTAIN where semantically equivalent."
  - "NotNode does not eagerly flip at parse time; instead the mapper carries a boolean `negated` flag through recursive descent and XORs it with AND/OR (DeMorgan) + flips leaf operators. Cleaner than generating intermediate NotNode chains."
metrics:
  duration: "~35 minutes"
  completed_date: "2026-04-19"
  tasks_completed: 3
  files_changed: 11
---

# Phase 3 Plan 02: Structured Filter DSL + Tool Limits Summary

**One-liner:** Sealed-hierarchy filter DSL (AND/OR/NOT/Leaf) mapped to Jmix `PropertyCondition` + `LogicalCondition` via DeMorgan-expanded NOT and strict fail-closed literal coercion, plus `ToolLimits` constants (`DEFAULT_LIMIT=20`, `MAX_LIMIT=100`) — makes JPQL/SQL injection from the LLM structurally impossible.

## What Shipped

**Filter DSL core** (`com.vn.agent.filter`, 7 files):
- `FilterNode` sealed interface permitting exactly `AndNode`, `OrNode`, `NotNode`, `LeafNode`; Jackson `JsonTypeInfo.Id.DEDUCTION` polymorphism selects subtype by distinguishing property (`and`/`or`/`not`/`property`).
- `AndNode` / `OrNode` / `NotNode` / `LeafNode` records — immutable, defensive `List.copyOf`.
- `LiteralCoercer` @Component — per-`MetaProperty` strict coercion: `UUID.fromString`, `Enum.valueOf`, `BigDecimal`, `Integer`/`Long`/`Short`/`Double`/`Float`, `LocalDate.parse`/`LocalDateTime.parse`/`OffsetDateTime.parse`/`Instant.parse` (ISO-8601). Every failure → `ToolUserError` with structured `{error, reason, expected}`; no stack traces leak.
- `FilterDslMapper` @Component — exhaustive switch over sealed hierarchy; DeMorgan NOT; 13-operator mapping; `validatePath` with depth cap + per-hop `EffectiveSchemaComputer.canReadAttribute`/`canReadEntity` (D-08 fail-closed).

**Tool primitives** (`com.vn.agent.tools`, 3 files):
- `ToolLimits` — `DEFAULT_LIMIT=20`, `MAX_LIMIT=100`, `DEFAULT_MAX_FILTER_DEPTH=3`; `clampLimit` helper.
- `ToolErrorDto` — `{error, reason, expected[]}` LLM-facing shape.
- `ToolUserError` — unchecked throwable carrying a `ToolErrorDto`.

**Config**: new `jmix.ai-agent.tools.max-filter-depth = 3` in `module.properties`.

## Entry Points for Plan 03

- `FilterDslMapper.map(FilterNode root, MetaClass mc) → Condition`
- `ToolLimits.clampLimit(Integer requested) → int`
- `ToolLimits.DEFAULT_LIMIT`, `ToolLimits.MAX_LIMIT`, `ToolLimits.DEFAULT_MAX_FILTER_DEPTH`
- `ToolErrorDto`, `ToolUserError` (+ `toDto()`)
- `LiteralCoercer.coerce / coerceList / coerceBoolean`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Jmix 2.8 op constant is `NOT_CONTAINS`, not `DOES_NOT_CONTAIN`**
- **Found during:** Task 3 (first `./gradlew compileJava` attempt failed with `cannot find symbol DOES_NOT_CONTAIN`).
- **Root cause:** The plan's `<interfaces>` block listed the constant as `DOES_NOT_CONTAIN` (likely based on older Jmix docs). `javap` against `jmix-core-2.8.0.jar` shows the actual constants: `CONTAINS`, `NOT_CONTAINS` (plus `IN_INTERVAL`, `DATE_EQUALS`, `IS_COLLECTION_EMPTY`, `MEMBER_OF_COLLECTION`, `NOT_MEMBER_OF_COLLECTION` — not in D-05's 13 but present in the enum).
- **Fix:** `FilterDslMapper.resolveOperation` accepts both DSL spellings (`"DOES_NOT_CONTAIN"` and `"NOT_CONTAINS"`) for LLM convenience and maps both to `PropertyCondition.Operation.NOT_CONTAINS`. LLM-facing surface preserved; emitted Jmix op is correct for 2.8.
- **Files modified:** `FilterDslMapper.java`
- **Commit:** `542891b`

**2. [Rule 3 - Blocking] Removed `JpqlCondition` token from Javadoc to satisfy acceptance grep**
- **Issue:** Acceptance criterion required `grep -rn "JpqlCondition"` to return zero matches across the `filter/` tree (structural proof there is no JPQL in source). Initial Javadoc text said `"no JpqlCondition escape hatch"` — tripped the grep.
- **Fix:** Renamed the Javadoc mention to `"raw-JPQL escape hatch"` in both `FilterDslMapper` class javadoc and `NotNode` javadoc. Intent unchanged.
- **Files modified:** `FilterDslMapper.java`, `NotNode.java`
- **Commit:** `542891b`

### Authentication Gates

None — no external-service interaction.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL.
- All 13 D-05 operators referenced in `FilterDslMapper.java` (grep count 26 — each op referenced both positive and negated branches).
- `grep -rn "JpqlCondition|createQuery|createNativeQuery" ai-agent/ai-agent/src/main/java/com/vn/agent/filter/` → 0 matches.
- `grep -rn "@Autowired|@Inject" ai-agent/ai-agent/src/main/java/com/vn/agent/filter/ ai-agent/ai-agent/src/main/java/com/vn/agent/tools/` → 0 matches (constructor injection only).
- `ToolLimits.DEFAULT_LIMIT = 20`, `ToolLimits.MAX_LIMIT = 100`, `ToolLimits.DEFAULT_MAX_FILTER_DEPTH = 3` — all literal matches.
- `jmix.ai-agent.tools.max-filter-depth = 3` present in `module.properties`.

## Threat Flags

None — all surface in this plan is internal mapping logic; no new network endpoints, auth paths, file access, or schema changes. Every threat from the plan's register (T-03-05 through T-03-10) is mitigated by construction and acceptance-gated by the checks above.

## Commits

| Task | Commit    | Summary                                                          |
|------|-----------|------------------------------------------------------------------|
| 1    | `998c500` | Sealed FilterNode hierarchy + ToolLimits + ToolErrorDto + ToolUserError |
| 2    | `c08d8d3` | LiteralCoercer strict fail-closed coercion                       |
| 3    | `542891b` | FilterDslMapper DeMorgan NOT + D-08 path validation + module.properties |

## Self-Check: PASSED

- [x] All 11 files exist at declared paths.
- [x] All 3 commits present in `git log`.
- [x] `./gradlew :ai-agent:ai-agent:compileJava` exits 0.
- [x] All acceptance-criteria greps satisfied (13 ops, 0 JPQL tokens, 0 field-injection, depth-prop in module.properties).
