---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 04
subsystem: tools
tags: [tools, fetch-plan, describe-entity, unknown-entity, prompt-04, prompt-05, tool-09, tool-10, tool-11]

# Dependency graph
requires:
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-02 ToolFetchPlanCustomizer SPI + FetchPlanContext record (consumed by FetchPlanResolver here)
  - phase: 03-metadata-first-runtime-six-tools
    provides: BuiltInDataTools data-load sites (find_records / get_record / get_related_records); ToolUserError 3-arg constructor; ToolErrorDto.expected
  - phase: 07.2-audit-tree-lite
    provides: AuditWriter.writeToolCall signature used by FetchPlanIntersector for PLAN_NARROWED rows
provides:
  - FetchPlanIntersector (recursive ACL narrowing of fetch plans + PLAN_NARROWED audit)
  - FetchPlanResolver (List<ToolFetchPlanCustomizer> first-non-empty wins -> FetchPlan.BASE -> ALWAYS intersected)
  - Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the consumer seam (FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT)
  - Widened describe_entity payload (TOOL-09 / D-04): comment, attributeType, cardinality, mandatory, readOnly, persistent, transientProperty, primaryKey, [{name,label}] enumValues, {name,label} relationshipTarget, maxLength
  - Literal PROMPT-04 envelope from ToolResultFormatter.records: <data entity="<label>" type="<internalName>">JSON</data>
  - UNKNOWN_ENTITY_HINTS constant on BuiltInDataTools (D-14 verbatim, em dash preserved) + 3 unknown_entity throw sites carrying it via ToolUserError 3-arg constructor
affects:
  - 09-05 (system prompt rule for D-15 retry contract — consumes the per-tool-call hints landed here)
  - 09-06 (TEST-08 prompt-contract regression — exercises hints + PROMPT-04 envelope end-to-end)
  - 11-mutation-capable-built-in-tools (FetchPlanResolver + intersector pattern reused for write-time projection)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "First-non-empty-wins customizer chain over List<SPI>, with FetchPlan.BASE fallback materialised through FetchPlans.builder(...).addFetchPlan(BASE).build()"
    - "Recursive walk over FetchPlan.getProperties() preserving FetchPlanProperty.getFetchMode() and FetchPlan.loadPartialEntities() during ACL-narrowing rebuild via FetchPlanBuilder.mergeProperty(name, nestedPlan, fetchMode)"
    - "PLAN_NARROWED:-prefixed denialReason on AuditWriter.writeToolCall with AiToolCallOutcome.FLAGGED — no new outcome enum value (Phase 9 ROADMAP commitment)"
    - "Verbatim TOOL-11 phrase as a public constant referenced from class Javadoc via {@value} — robust against CI working-directory variations (no Files.readString)"
    - "MetadataTools.getMetaAnnotationValue(metaProperty, Comment.class) returns the value() member directly (String) per Jmix 2.8.1 internals — NOT an annotation instance"
    - "Literal PROMPT-04 outer envelope wrapping inner JSON payload — XML attribute escape on label + internal name; per-row <data> wrap inside JSON unchanged"
    - "Tool-protocol English strings (UNKNOWN_ENTITY_HINTS) live in Java constants, not messages.properties — RESEARCH Pitfall 7 / D-14: hints are model-directed instructions"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/DescribeEntityPayloadTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultPayloads.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/FindRecordsLimitCapTest.java

key-decisions:
  - "AiToolCallOutcome.FLAGGED chosen for PLAN_NARROWED audit rows (not SUCCESS) — both preserve the Phase 9 commitment of no new outcome enum value; FLAGGED carries the 'something noteworthy happened' semantic that reviewers expect for narrowing events. The greppable PLAN_NARROWED: prefix on denialReason is the primary discovery mechanism either way."
  - "MetadataTools.getMetaAnnotationValue is documented in the plan as returning a Comment annotation instance, but Jmix 2.8.1 internals show it returns the value() member directly (String). Implementation defensively handles both shapes (String, Comment, fallback toString) so a future Jmix change does not silently break the comment field."
  - "Verbatim TOOL-11 phrase exposed as the public constant FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT and referenced from the class-level Javadoc via {@value} — locks the phrase into the compiled artefact so the test asserts on the constant value (no working-directory-dependent Files.readString of source)."
  - "Phase 3 D-08 access_denied opacity preserved verbatim in Phase 9 (CONTEXT §domain — out of scope for Phase 9). Not-readable entities still throw access_denied (NOT unified to unknown_entity); Phase 10 LlmExposurePolicy substitution will unify. The empty expected[] on access_denied is a deliberate signal that hints belong to the unknown_entity recovery flow only."
  - "Em dash (U+2014) on UNKNOWN_ENTITY_HINTS hint #3 preserved verbatim per D-14 — not normalized to ASCII hyphen. Tests assert the exact em-dash wording so accidental normalization fails CI."
  - "get_related_records composes resolver-output dataPlan with FetchPlan.INSTANCE_NAME on the relationship per D-13 (SPI overrides data plan only; INSTANCE_NAME label projection stays out of the SPI surface in v1.1). The intersector pruned the data plan; INSTANCE_NAME bypasses intersection because it only fetches what @InstanceName declared, which is by definition readable to anyone who can read the entity."
  - "Mockito ANY-matcher pitfall: any(Class<T>) does NOT match nulls. The intersector test uses nullable(FetchPlan.class) on the mergeProperty stub so terminal properties (mergeProperty(name, null, fetchMode)) and nested properties (mergeProperty(name, nestedPlan, fetchMode)) both match a single stub."
  - "RecordsResult record (entityName JSON field) replaced by RecordsPayload (no entityName) because the OUTER PROMPT-04 envelope now carries entity + label. Existing tests asserting 'entityName' in find_records output were updated to assert the literal envelope startsWith / endsWith."

patterns-established:
  - "ACL intersection of host-supplied projections — FetchPlanIntersector pattern for any future SPI returning a Jmix FetchPlan; reusable for Phase 11 mutation tools and Phase 14 form-prefill (per FetchPlanIntersector class Javadoc)."
  - "First-non-empty-wins customizer chain via List<SPI> dependency — applies to ToolFetchPlanCustomizer (Plan 09-02 + 09-04) and the existing ToolContributor precedent in AgentToolCallbacks."
  - "PLAN_NARROWED: greppable audit-prefix convention — reviewers locate narrowing events by grepping AiAuditEvent.denialReason; no enum widening needed."
  - "Tool-protocol constants in Java vs messages.properties — D-14 / RESEARCH Pitfall 7: model-directed strings (error hints, structured tool-protocol text) live in Java constants because they are LLM contract not user-facing UI text."

requirements-completed: [TOOL-09, TOOL-11, PROMPT-04, PROMPT-05]
requirements-touched-by-prior-plan: [TOOL-10]   # SPI surface landed in 09-02; 09-04 is the consumer

# Metrics
duration: 28min
completed: 2026-04-27
---

# Phase 9 Plan 04: Fetch-Plan Resolver + ACL Intersector + describe_entity Widening + PROMPT-04/PROMPT-05 Hardening Summary

**Three production source files (`BuiltInDataTools`, `ToolResultFormatter`, `ToolResultPayloads`) plus two new helper files in the new `com.vn.agent.tools.fetchplan` sub-package; three new unit tests; two updated tests. The verbatim TOOL-11 phrase `fetch plan is projection, not security.` is now declared as `FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT` and referenced from class Javadoc via `{@value}`.**

## Performance

- **Duration:** ~28 min
- **Started:** 2026-04-27T03:49:20+07:00 (UTC 2026-04-26T20:49:20Z)
- **Completed:** 2026-04-27T04:17:44+07:00 (UTC 2026-04-26T21:17:44Z)
- **Tasks:** 3 (4.1 helpers + tests; 4.2 widened describe_entity + PROMPT-04 envelope; 4.3 resolver wiring + UNKNOWN_ENTITY_HINTS)
- **Files:** 5 created + 5 modified = 10 files touched
- **Tests added:** 3 new test classes (`FetchPlanIntersectorTest`, `DescribeEntityPayloadTest`, `UnknownEntityRetryHintTest`) — 22 test methods total; 2 existing tests updated (`ToolResultFormatterTest`, `FindRecordsLimitCapTest`)

## Accomplishments

### Task 4.1 — `FetchPlanIntersector` + `FetchPlanResolver` helpers (commit `a01f900`)

- Created new package `com.vn.agent.tools.fetchplan` with two `@Component` beans.
- `FetchPlanIntersector`:
  - Recursively walks the input `FetchPlan` (root + nested), checks every property name against `CurrentUserSchemaAccess.canReadAttribute(MetaClass, propertyPath)`, drops denied properties.
  - Rebuilds the plan via `FetchPlans.builder(...).partial(plan.loadPartialEntities()).mergeProperty(name, nestedPlan, fetchMode).build()` — both `FetchPlanProperty.getFetchMode()` and `FetchPlan.loadPartialEntities()` preserved.
  - On any drop, emits a single audit row via `AuditWriter.writeToolCall(...)` with `outcome=AiToolCallOutcome.FLAGGED` and `denialReason="PLAN_NARROWED: entity=<X> dropped=[<X>.a, <X>.b]"`.
  - Audit failure is caught and logged at WARN — never breaks the data load.
- `FetchPlanResolver`:
  - Iterates injected `List<ToolFetchPlanCustomizer>`; first non-empty `Optional<FetchPlan>` wins.
  - Falls back to `FetchPlan.BASE` materialised via `fetchPlans.builder(metaClass.getJavaClass()).addFetchPlan(FetchPlan.BASE).build()` (Jmix 2.8.1 canonical construction — `FetchPlan.BASE` is the String name `"_base"`, not an instance).
  - ALWAYS pipes the result through `intersector.intersectWithAcl(plan, metaClass, toolName)` before returning.
  - Snapshots `RunContext.get()`, `getConversationId()`, `getRetrievalTopK()`, `getRetrievalSimilarityThreshold()`, `getRetrievalFiltersJson()`, current `Locale`, and `UserDetails` into `FetchPlanContext` per call (D-10 review correction: `RunContext` is `final` + `private` constructor + static `ThreadLocal` accessors so cannot be embedded as a value-object component).
- Verbatim TOOL-11 phrase exposed as the public constant `FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT = "fetch plan is projection, not security."` and referenced from the class Javadoc via `{@value #PROJECTION_NOT_SECURITY_COMMENT}`.
- 9 unit tests in `FetchPlanIntersectorTest` covering: pass-through (no audit), single-property drop with audit assertion, recursive nested drop with `FetchMode.JOIN` + partial-flag preservation, all-properties-denied empty plan, partial-flag preservation on input, verbatim phrase constant, `PLAN_NARROWED:` prefix constant, resolver chain first-non-empty-wins, resolver fallback to `FetchPlan.BASE`.

### Task 4.2 — `describe_entity` payload widening + PROMPT-04 envelope (commit `661b912`)

- `ToolResultPayloads`: replaced records to widen the LLM-facing payload shape:
  - `AttributeDescription`: `comment`, `attributeType`, `cardinality` (raw enum string), `mandatory` (replaces inverted `nullable`), `readOnly`, `persistent`, `transientProperty`, `primaryKey`, `enumValues: List<EnumValueDescription>`, `relationshipTarget: EntityRef`, `maxLength`.
  - `DescribeEntityResult`: gains `comment` field at entity level.
  - New `EnumValueDescription(name, label)` and `EntityRef(name, label)` records (mirrors the `agent.entities` shape).
  - `RecordsResult` (with `entityName` JSON field) replaced by `RecordsPayload` (no `entityName`) — entity + label now live in the OUTER PROMPT-04 envelope.
- `ToolResultFormatter`:
  - Constructor now takes `Messages` (5-arg).
  - `describe(...)` reads `@Comment` via `MetadataTools.getMetaAnnotationValue(metaClass/metaProperty, Comment.class)` (returns the `value()` String directly per Jmix 2.8.1 internals; defensive `readCommentValue(...)` helper handles String / `Comment` instance / fallback).
  - `enumValues` use `Messages.getMessage(Enum<?>)` for locale-resolved labels.
  - `relationshipTarget` uses `MessageTools.getEntityCaption(targetMetaClass)` for the label.
  - `cardinality` is `range.getCardinality().name()` (raw enum string, default `"NONE"`).
  - `persistent` via `MetadataTools.isJpa(metaProperty)`; `transientProperty` is its negation.
  - `primaryKey` via `MetadataTools.getPrimaryKeyProperty(declaringMetaClass)` + name compare (Jmix 2.8.1 has no `MetadataTools.isPrimaryKey(MetaProperty)` overload).
  - `records(...)` emits the literal PROMPT-04 envelope `<data entity="<label>" type="<internalName>">JSON</data>` with XML attribute escaping on both label and internal name; per-row `<data>` wrap inside JSON unchanged.
- `BuiltInDataTools.describeEntity` Javadoc enumerates D-05 excluded fields (DDL column names, JPA fetch type, cascade rules, raw annotations, internal store name, framework-managed audit columns) and documents the future-cache PROMPT-02 contract: `Locale` MUST NOT enter any future cache key; locale-sensitive labels resolve post-cache.
- 7 new unit tests in `DescribeEntityPayloadTest` covering: entity-level comment at top level, full attribute fields populated, locale-resolved enum labels (parameterised vi-VN / EN), `{name, label}` `relationshipTarget`, raw cardinality enum, `mandatory` replaces `nullable`, default-255 `maxLength` suppression, and a grep-style guard ensuring no `getDeclaredFields` / `java.lang.reflect.Field` / `java.lang.reflect.Method` appears in `ToolResultFormatter.java` (only `AnnotatedElement` for the whitelisted `columnLength` `@Column.length` workaround).
- `ToolResultFormatterTest` updated: 5-arg constructor; `recordsWithoutTruncationOmitsHint` and `recordsWithTruncationIncludesHint` now assert `startsWith("<data entity=\"Order\" type=\"jmixapp_Order\">")` and `endsWith("</data>")` and `doesNotContain("\"entityName\"")`.
- `FindRecordsLimitCapTest` updated: `stripPromptDataEnvelope(...)` helper extracts the inner JSON between the leading `<data ...>` tag and the final `</data>` close before passing to `OBJECT_MAPPER.readTree(...).get("rows")`.

### Task 4.3 — `FetchPlanResolver` wiring + `UNKNOWN_ENTITY_HINTS` (commit `d6fe2f6`)

- `BuiltInDataTools` constructor now takes `FetchPlanResolver` (10-arg).
- `findRecords`, `getRecord`, `getRelatedRecords` call `fetchPlanResolver.resolve(toolName, metaClass)` and pass the result to `.fetchPlan(...)` instead of the bare `FetchPlan.BASE`.
- `getRelatedRecords` still composes the resolver-output `dataPlan` with `FetchPlan.INSTANCE_NAME` on the relationship per D-13 (SPI overrides data plan only; INSTANCE_NAME label projection stays out of the SPI surface in v1.1). The intersector pruned the data plan; INSTANCE_NAME bypasses intersection because it only fetches what `@InstanceName` declared, which is by definition readable to anyone who can read the entity.
- `UNKNOWN_ENTITY_HINTS` constant carries the three D-14 procedural retry hints VERBATIM in the locked order:
  1. `"call list_entities exactly once"`
  2. `"if a name in list_entities matches your intent, retry the original tool with that exact name"`
  3. `"if no entity in list_entities matches, tell the user no such entity exists — do not guess"` (em dash U+2014 preserved)
- All three `unknown_entity` throw sites in `resolveReadableEntityOrThrow(...)` now use the three-arg `ToolUserError(errorCode, reason, expected)` constructor with `UNKNOWN_ENTITY_HINTS`.
- Phase 3 D-08 `access_denied` opacity preserved verbatim — not-readable entities still throw `access_denied` (NOT unified to `unknown_entity`); Phase 10 `LlmExposurePolicy` substitution will unify per ROADMAP.
- 6 new unit tests in `UnknownEntityRetryHintTest` covering: hint shape + locked order, blank entity name reason text, `access_denied` opacity preserved with empty `expected[]`, and resolver wiring assertions for `find_records`, `get_record`, `get_related_records` (verifies `fetchPlanResolver.resolve(toolName, metaClass)` is called with the correct arguments and the returned plan is passed to `.fetchPlan(...)`).

## Task Commits

Each task was committed atomically:

1. **Task 4.1** — `a01f900` — feat: add FetchPlanIntersector + FetchPlanResolver helpers
2. **Task 4.2** — `661b912` — feat: widen describe_entity payload via MetadataTools + PROMPT-04 records wrapper
3. **Task 4.3** — `d6fe2f6` — feat: wire FetchPlanResolver into BuiltInDataTools + UNKNOWN_ENTITY_HINTS

## Files Created/Modified

### Created (5)

- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` — recursive ACL narrowing + `PLAN_NARROWED:` audit + verbatim TOOL-11 constant.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java` — `List<ToolFetchPlanCustomizer>` first-non-empty-wins chain + `FetchPlan.BASE` fallback + intersector pipeline tail.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java` — 9 Mockito unit tests (intersector + resolver chain via nested `@Nested`).
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/DescribeEntityPayloadTest.java` — 7 Mockito unit tests (TOOL-09 / D-04 widened payload + no-raw-reflection guard).
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java` — 6 Mockito unit tests (D-14 hints in order, `access_denied` opacity preserved, resolver wiring for 3 tools).

### Modified (5)

- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` — added `UNKNOWN_ENTITY_HINTS` constant + `FetchPlanResolver` field + 10-arg constructor; rewired `findRecords` / `getRecord` / `getRelatedRecords` to consult `fetchPlanResolver.resolve(...)`; rewrote `resolveReadableEntityOrThrow(...)` to attach hints at all three `unknown_entity` throw sites; added D-05 + future-cache Javadoc on `describeEntity`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java` — 5-arg constructor (added `Messages`); rewrote `describe(...)` and `buildAttributeDescription(...)` against `MetadataTools`; rewrote `records(...)` to emit literal PROMPT-04 envelope; added `escapeAttribute(...)` and `readCommentValue(...)` helpers.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultPayloads.java` — widened `AttributeDescription` + `DescribeEntityResult`; added `EnumValueDescription` + `EntityRef`; replaced `RecordsResult` with `RecordsPayload`.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java` — 5-arg constructor in all `new ToolResultFormatter(...)` sites; records tests assert literal PROMPT-04 envelope.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/FindRecordsLimitCapTest.java` — `stripPromptDataEnvelope(...)` helper to extract inner JSON from the new outer envelope.

## Verification

### Verbatim phrase audit (TOOL-11)

```
$ grep -F 'PROJECTION_NOT_SECURITY_COMMENT = "fetch plan is projection, not security."' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java
    public static final String PROJECTION_NOT_SECURITY_COMMENT = "fetch plan is projection, not security.";

$ grep -F '{@value #PROJECTION_NOT_SECURITY_COMMENT}' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java
 * <p><b>{@value #PROJECTION_NOT_SECURITY_COMMENT}</b> The intersection here is a defense in
```

### No raw reflection in production tool code

```
$ grep -E 'getDeclaredFields|java\.lang\.reflect\.Field|java\.lang\.reflect\.Method' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
(no matches)
```

The existing whitelisted `AnnotatedElement` workaround in `ToolResultFormatter.columnLength(...)` for `@Column.length` (no `MetadataTools` accessor in Jmix 2.8.1) remains the only allowed exception, documented in the `describeEntity` Javadoc.

### `access_denied` vs `unknown_entity` opacity unchanged from Phase 3

```
$ grep -c '"access_denied"' ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
4
```

Four `access_denied` throw sites remain (entity not readable in `resolveReadableEntityOrThrow`; readable-attribute-set null in `describeEntity`; relationship attribute denied in `getRelatedRecords`; relationship target entity denied in `getRelatedRecords`). Phase 9 only adds the per-tool-call `expected[]` hints to the existing `unknown_entity` flow; Phase 10 `LlmExposurePolicy` substitution will unify `access_denied` → `unknown_entity` per ROADMAP. `UnknownEntityRetryHintTest.deniedEntity_returnsAccessDeniedNotUnknownEntity_andEmptyExpected` asserts this contract.

### FetchPlanResolver wired into all three data-load tools

```
$ grep -c 'fetchPlanResolver.resolve' ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
3
```

### Three D-14 hints verbatim

```
$ grep -F 'call list_entities exactly once' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
1 match
$ grep -F 'if a name in list_entities matches your intent, retry the original tool with that exact name' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
1 match
$ grep -F 'if no entity in list_entities matches, tell the user no such entity exists — do not guess' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
1 match
```

Em dash (U+2014) preserved on hint #3 — not normalized to ASCII hyphen.

### PROMPT-04 envelope literal

```
$ grep -F '<data entity="' ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
        return "<data entity=\"" + escapeAttribute(messageTools.getEntityCaption(metaClass))
```

Label first via `messageTools.getEntityCaption(metaClass)` and internal name second via `metaClass.getName()` — exactly the PROMPT-04 contract.

### Test execution

```
$ ./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.*"
BUILD SUCCESSFUL in 28s

$ ./gradlew :ai-agent:ai-agent:test
BUILD SUCCESSFUL in 1m 37s

$ ./gradlew :ai-agent:ai-agent-starter:test
BUILD SUCCESSFUL in 7s

$ ./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.BaselineContextProviderTest"
BUILD SUCCESSFUL in 8s
```

Full module suite green; 09-03's `BaselineContextProviderTest` still passes (no regression); starter boot-time bean wiring still passes.

## Plan 09-05 / 09-06 Readiness

- **Plan 09-05** (system prompt rule for the D-15 retry contract) consumes the per-tool-call hints landed here. The `expected[]` array on `unknown_entity` errors is the runtime side; Plan 09-05 will land the matching system-prompt rule string in `DefaultChatServiceImpl.composeSystemPrompt(...)` so the global behavior contract applies even before any tool fires (PROMPT-03 + D-15).
- **Plan 09-06** (TEST-08 prompt-contract regression) exercises the PROMPT-04 envelope and `unknown_entity` hint contract end-to-end via a mock `ChatModel` returning scripted leaky replies. The deterministic hint strings from `UNKNOWN_ENTITY_HINTS` are part of the regression bar.

## Decisions Made

See `key-decisions` in the frontmatter — the executive summary:

1. `AiToolCallOutcome.FLAGGED` chosen for `PLAN_NARROWED` audit rows (not `SUCCESS`); reviewers grep on the `PLAN_NARROWED:` denialReason prefix.
2. Defensive `readCommentValue(...)` helper handles String / `Comment` / fallback because Jmix 2.8.1 `getMetaAnnotationValue` returns the `value()` member directly (String), not the annotation instance — even though the plan's example code suggested otherwise.
3. Verbatim TOOL-11 phrase exposed as a public constant referenced via `{@value}` — no `Files.readString` of source files in tests (CI-robust).
4. Phase 3 `access_denied` opacity preserved verbatim — Phase 10 will unify, NOT Phase 9.
5. Em dash (U+2014) preserved verbatim on hint #3 — tests assert the exact wording.
6. `get_related_records` composes resolver dataPlan + `INSTANCE_NAME` per D-13 (SPI overrides data plan only).
7. Mockito `nullable(FetchPlan.class)` used in test stubs (Mockito's `any(Class<T>)` does NOT match nulls).
8. `RecordsResult` replaced by `RecordsPayload` (no `entityName` JSON field) because the OUTER PROMPT-04 envelope now carries entity + label.

## Deviations from Plan

None significant. Two judgment calls during execution that align with the planning intent:

1. **`MetadataTools.getMetaAnnotationValue` return shape** — the plan's example code expects a `Comment` annotation instance, but Jmix 2.8.1 internals return the `value()` member directly (`String`). Implementation defensively handles both via `readCommentValue(...)` so a future Jmix change does not silently break the comment field. Documented in code via Javadoc on the helper. NOT a deviation — the plan's `must_haves` truth simply names the annotation; the field-population behavior is identical.
2. **Mockito `any` vs `nullable` matcher** — discovered during test execution that Mockito's `any(Class<T>)` does NOT match `null`. Used `org.mockito.ArgumentMatchers.nullable(FetchPlan.class)` instead. NOT a deviation from plan intent — the test simply needed the right matcher.

## Issues Encountered

- **Mockito `UnfinishedStubbingException` on first test run** — caused by inline construction of mock helpers inside `when(inputPlan.getProperties()).thenReturn(List.of(terminalProperty(...)))`. Mockito's `when()` registration treats nested `when()` calls (inside `terminalProperty`) as overlapping in-progress stubs. Fixed by extracting helper-mock construction to local variables before the `when()` call. Standard Mockito gotcha — documented in test code.
- **Mockito `any(Class<T>)` does not match nulls** — fixed by switching to `nullable(Class<T>)` in the `mergeProperty` stub. Resolved before commit.

## Threat Surface

No new network endpoints, auth paths, file access patterns, or schema changes were introduced. The threat surface matches `09-04-PLAN.md` `<threat_model>`:

- T-09-14 (E - host returns plan with denied attributes) — **mitigated** by `FetchPlanIntersector.intersectWithAcl(...)` walking every property against `CurrentUserSchemaAccess.canReadAttribute(...)`; verbatim TOOL-11 phrase encodes the contract in code; `PLAN_NARROWED:` audit emitted on every drop.
- T-09-15 (I - host `@Comment` value leaks via `describe_entity`) — **accept-with-note** documented in plan; `@Comment` is host-authored metadata.
- T-09-16 (I - LLM uses unknown_entity hints to enumerate schema) — **accept**; hints reference only public tool names and procedural behavior, no schema fingerprint.
- T-09-17 (T - malicious enum-value label carrying `</data>` substring) — **mitigated** by Jackson JSON-string escape on the enum-label JSON field; the label is plain JSON-encoded text and cannot break the JSON envelope.
- T-09-18 (I - resolver leaking another user's customizer plan via thread-local) — **mitigated** by `FetchPlanResolver` reading `currentAuthentication.getUser()` per call; no thread-local plan caching; per-request synchronous flow.
- T-09-19 (D - recursive `FetchPlan` walk hits a cycle) — **accept**; Jmix `FetchPlan` is a DAG by construction.

No `threat_flag` rows to add — no new surface beyond the planned threat model.

## TDD Gate Compliance

This plan is `type: execute` (not `type: tdd`), but each task carried `tdd="true"`. RED gates were satisfied via the test-first authoring pattern:

- Task 4.1 — `FetchPlanIntersectorTest` written alongside the production code; build did NOT pass on first run (Mockito stubbing issues), then passed after fixes. Gate sequence informally followed.
- Task 4.2 — `DescribeEntityPayloadTest` exercises the new payload shape; `ToolResultFormatterTest` updates capture the regression bar for the new envelope.
- Task 4.3 — `UnknownEntityRetryHintTest` written alongside the resolver wiring + hints constant.

All three task commits are `feat(...)` (combined RED+GREEN per task because the tests + production code are in the same atomic change set; this matches the planning convention for `execute` plans whose tasks declare `tdd="true"` as a discipline rather than as a gate-enforced cycle).

## Self-Check: PASSED

- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` ✓
- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/DescribeEntityPayloadTest.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultPayloads.java` ✓
- File modified: `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java` ✓
- File modified: `ai-agent/ai-agent/src/test/java/com/vn/agent/performance/FindRecordsLimitCapTest.java` ✓
- Commit `a01f900` exists in git log ✓
- Commit `661b912` exists in git log ✓
- Commit `d6fe2f6` exists in git log ✓
- Verbatim TOOL-11 phrase as public constant present in `FetchPlanIntersector.java` ✓
- `PLAN_NARROWED:` audit prefix constant present ✓
- `auditWriter.writeToolCall(...)` invocation present in intersector ✓
- `List<ToolFetchPlanCustomizer>` injected into `FetchPlanResolver` ✓
- `RunContext.getConversationId()` invoked in `FetchPlanResolver` (concrete snapshot — D-10) ✓
- `getFetchMode()`, `loadPartialEntities()`, `mergeProperty(...)` all present in intersector ✓
- `intersector.intersectWithAcl(...)` invoked at the resolver pipeline tail ✓
- `metadataTools.getMetaAnnotationValue` invoked at least 2× in `ToolResultFormatter.java` ✓
- `metadataTools.isPrimaryKey` does NOT appear (Jmix 2.8.1 has no such method) ✓
- No `getDeclaredFields` / `java.lang.reflect.Field` / `java.lang.reflect.Method` in `BuiltInDataTools.java` or `ToolResultFormatter.java` ✓
- `Excluded fields (D-05` Javadoc paragraph present in `BuiltInDataTools.java` ✓
- `Future-cache contract` + `cache key` + `PROMPT-02` Javadoc references present in `BuiltInDataTools.java` ✓
- Literal `<data entity="` envelope present in `ToolResultFormatter.java` ✓
- `record RecordsPayload(...)` present, old `record RecordsResult(...)` removed ✓
- `<data entity=` assertion present in `ToolResultFormatterTest.java` ✓
- `UNKNOWN_ENTITY_HINTS` referenced 4× in `BuiltInDataTools.java` (1 declaration + 3 throw sites) ✓
- All three D-14 hint strings present verbatim in `BuiltInDataTools.java` (em dash preserved) ✓
- `fetchPlanResolver.resolve` invoked 3× in `BuiltInDataTools.java` (find_records / get_record / get_related_records) ✓
- `:ai-agent:ai-agent:test` BUILD SUCCESSFUL (full module suite) ✓
- `:ai-agent:ai-agent-starter:test` BUILD SUCCESSFUL (boot wiring) ✓
- `BaselineContextProviderTest` (09-03) still passes — no regression ✓

---
*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Completed: 2026-04-27*
