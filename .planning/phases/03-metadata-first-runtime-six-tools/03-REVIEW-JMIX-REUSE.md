---
phase: 03-metadata-first-runtime-six-tools
reviewed: 2026-04-19T00:00:00Z
depth: deep
review_lens: jmix-reuse-audit
files_reviewed: 21
files_reviewed_list:
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/AndNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterDslMapper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LeafNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/NotNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/OrNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiSchema.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/UserEditableStringIndex.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolLimits.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiToolsAutoConfiguration.java
  - jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java
findings:
  critical: 0
  warning: 4
  info: 6
  total: 10
status: issues_found
---

# Phase 3: Code Review Report — Jmix-Reuse Audit

**Reviewed:** 2026-04-19
**Depth:** deep
**Lens:** Jmix-reuse audit (per user directive — thin adapter only)
**Files Reviewed:** 21
**Status:** issues_found (warnings + info only; no critical bugs or security holes)

## Summary

Phase 3 ships the thin LLM-facing adapter layer more or less as the CONTEXT/RESEARCH prescribe. The implementation is disciplined about routing through Jmix: `DataManager` for all reads, `AccessManager` + `CrudEntityContext`/`EntityAttributeContext` for visibility, `PropertyCondition`/`LogicalCondition` for the filter DSL, `FetchPlan.INSTANCE_NAME` for projection, and `MetadataTools.isJpa/isSystem/isSystemLevel` for the user-editable-string probe. There is no parallel ACL, no JPQL string-builder from LLM input, no cached effective schema, and no AI-specific metadata DTO wandering beyond what the LLM JSON actually needs.

Against the Jmix-reuse lens, the surface the add-on legitimately owns is narrow and exactly matches the charter: schema shape for tools (AiEntityInfo/AiAttributeInfo/AiSchema), strict literal coercion (LiteralCoercer), path-depth governance + DSL→Condition mapping (FilterDslMapper), result-size limits (ToolLimits), prompt-injection-safe formatting (ToolResultFormatter, UserEditableStringIndex). Three small duplications are visible and one label resolution is mis-wired (MessageTools path). Everything else is KEEP.

Bugs found are minor: one API misuse (`MessageTools.getEntityCaption`/`getPropertyCaption` do not exist on `MessageTools` — they live on `MetadataTools`), one locale-sensitive `toLowerCase()`, and one over-eager null-out in the formatter. The read-only, fail-closed contract is intact.

---

## Warnings

### WR-01: `MessageTools.getEntityCaption` / `getPropertyCaption` do not exist — wrong API

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java:67,72`
**Issue:** The code calls `messageTools.getPropertyCaption(mp)` and `messageTools.getEntityCaption(mc)`. In Jmix 2.x these caption helpers live on `io.jmix.core.MetadataTools` (`getEntityCaption(MetaClass)`, `getPropertyCaption(MetaProperty)`), not on `io.jmix.core.MessageTools`. `MessageTools` exposes message-key resolution (`getMessage(...)`, `loadString(...)`, `getDefaultLocale()`, `getMessageRef(...)`) but not the Jmix-specific caption resolvers. Either the class will fail to compile against stock Jmix 2.8, or the project has a local extension that mimics those names — if the latter, that itself is a parallel layer to delete.
**Fix:**
```java
// EffectiveSchemaComputer: inject MetadataTools (already available elsewhere) and use it
private final MetadataTools metadataTools;

// attribute label
attrLabel = metadataTools.getPropertyCaption(mp);

// entity label
String entityLabel = metadataTools.getEntityCaption(mc);
```
Drop `MessageTools` from this class unless it is actually used to resolve a specific message key. This is the exact Jmix API the audit asks us to reuse — MetadataTools already does i18n caption resolution via the current user's locale.

### WR-02: Locale-sensitive `toLowerCase()` in boolean coercer can misbehave in Turkish locale

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java:106`
**Issue:** `s.toLowerCase()` with no `Locale` argument uses the default locale. In Turkish locale `"TRUE".toLowerCase()` → `"t\u0131ue"` (dotless i), so `"true"/"false"` matching can fail for a legitimately-admin-entered Turkish server. The rest of the class correctly uses locale-independent parsing; only the Boolean path slipped. `FilterDslMapper.resolveOperation` at line 119 gets this right (`Locale.ROOT`).
**Fix:**
```java
String lower = s.toLowerCase(java.util.Locale.ROOT);
```

### WR-03: `ToolResultFormatter` nulls ALL collection-valued attributes, including non-association lists

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java:158-162`
**Issue:** The `instanceof Collection<?>` branch fires for every collection, not just to-many associations. Jmix entities routinely hold non-association collections exposed via `@JmixProperty` (e.g. a `List<String>` tag list). The comment says "Collections of related entities" but the guard doesn't check `mp.getRange().isClass()`. Result: legitimate scalar-collection attributes are silently elided from LLM output even though they were fetched and safe to serialize.
**Fix:**
```java
} else if (v instanceof Collection<?> && mp.getRange().isClass()) {
    // to-many association — defer to get_related_records (D-12)
    row.put(mp.getName(), null);
}
```
Non-association collections should fall through to the default branch. If we want to keep the conservative default, at least narrow the guard to `mp.getRange().isClass()`.

### WR-04: `MetamodelScanner` filters only `@SystemLevel` — `MetadataTools.isJpaEntity` / `isJpaEmbeddable` would be cleaner and `MetadataTools.isSystem(MetaClass)` would catch Jmix-internal classes

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java:58-61`
**Issue:** The scan loop iterates every `MetaClass` in the session and excludes only `@SystemLevel`-annotated classes. That still enrolls non-persistent DTO meta-classes, Jmix-internal entities that happen to lack `@SystemLevel` but are not user-facing, and embedded types. Downstream, `list_entities` will happily emit these to the LLM (assuming they're readable). `MetadataTools.isJpaEntity(MetaClass)` / `MetadataTools.isJpaEmbeddable(MetaClass)` give the "is this a real persistent entity" probe Jmix already owns; reusing it both tightens scope and matches the audit charter ("reuse MetadataTools").
**Fix:**
```java
for (MetaClass mc : metadata.getSession().getClasses()) {
    if (!metadataTools.isJpaEntity(mc)) continue;           // skip DTO meta, embeddables, etc.
    if (mc.getJavaClass().isAnnotationPresent(SystemLevel.class)) continue;
    // ...
}
```
This also eliminates the need for a separate "is this entity user-facing" heuristic later.

---

## Info

### IN-01: `AgentToolCallbacks` builds its `ToolCallback[]` array but does not filter per-user

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java:40-53`
**Issue:** Class doc says "effective schema the built-in tools see is user-specific" but the method returns the full six-tool array unconditionally. Filtering is deferred to tool-body time (each `@Tool` method calls `schemaComputer.forCurrentUser()` / `canReadEntity`). That's fine — and arguably better than hiding tools — but the class-level contract wording promises "filtered to what the caller can see" (D-10 wording echoed in javadoc) which the code does not deliver. Either update the doc to say "filtering is applied inside tool bodies, not via callback pruning" or actually prune `list_entities`/`describe_entity` if the caller has zero readable entities. Low priority; current behavior is safe.
**Fix:** Clarify javadoc to: "per-request assembly; built-ins always present, each @Tool body applies AccessManager at call time."

### IN-02: `BuiltInDataTools.countRecords` hand-builds JPQL `"select e from " + mc.getName() + " e"` — `DataManager.getCount(LoadContext)` accepts a null query

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java:168-173`
**Issue:** The JPQL template is safe (entity name is whitelisted by `Metadata.getClass`), but Jmix `LoadContext` supports a condition-only path without any JPQL at all. Cleaner and removes the one string-concat that the D-16 ASM test has to special-case.
**Fix:**
```java
LoadContext<?> ctx = new LoadContext<>(mc);
if (cond != null) {
    LoadContext.Query q = new LoadContext.Query(null); // or verify Jmix 2.8 signature
    q.setCondition(cond);
    ctx.setQuery(q);
}
long n = dataManager.getCount(ctx);
```
Verify the Jmix 2.8 `DataManager.getCount` overload accepts no-query contexts; if it requires a query, keep the current code but add a comment explaining the D-16 allow-list.

### IN-03: `AiEntityInfo` carries the live `MetaClass` reference — transitive leak into JSON serialization surface

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java:12`
**Issue:** Record holds `MetaClass` so downstream code (e.g. `EffectiveSchemaComputer`) can use it for access checks. That's fine internally, but because the record is public and has a canonical constructor that exposes `metaClass()`, any accidental `objectMapper.writeValueAsString(entityInfo)` would try to serialize the MetaClass graph. Today `ToolResultFormatter.describe` hand-builds a map and sidesteps this, so not a live bug. Consider either marking the field `@JsonIgnore` or splitting "AiEntityInfo (internal)" from "AiEntityDto (LLM-facing)" — the latter is what the audit would prefer: the LLM-facing shape is already the map in `describe(...)`.

### IN-04: Validation-constraint probe is minimal (NotNull, Size, Pattern only)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java:142-161`
**Issue:** D-02 calls out `@NotNull`/`@Size`/`@Pattern` as the representative set, which matches. Jmix itself exposes validation constraints via `MetadataTools`/Bean Validation facades; if a future need arises, prefer asking Jmix for the constraint metadata rather than re-reading annotations. Not a change request — just a marker that if the list grows (Min/Max/Email/Digits), route through Jmix's own constraint extractor to avoid drift.

### IN-05: `LiteralCoercer.coerceDatatype` bypasses `Datatype.parse(String, Locale)`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java:163-273`
**Issue:** Every `Datatype` in Jmix implements `parse(String)` / `parse(String, Locale)`. The coercer re-implements per-type parsing (Integer/Long/BigDecimal/LocalDate/...) instead of delegating to `dt.parse(asString)`. The current hand-rolled path is actually the correct call here — we want a **strict, locale-independent, machine-readable** parse for LLM input, NOT the locale-formatted parse `Datatype.parse` does. So this is KEEP with a comment: "we intentionally do not use Datatype.parse because it is locale-aware and format-specific; LLM input is ISO-8601 / strict numeric strings." Add that comment to prevent a future contributor from 'simplifying' to `dt.parse(asString)` and breaking round-trips.
**Fix:** Add a class-level javadoc paragraph: "We do NOT delegate to `Datatype.parse(String)` because Jmix datatypes parse in the user's current locale (e.g. decimal comma); LLM input is always ISO/strict and must be parsed deterministically."

### IN-06: ASM 9.9 dependency for test-only bytecode scan — track Jmix 2.9 upgrade

**File:** `ai-agent/ai-agent/ai-agent.gradle:31-37`
**Issue:** Comment explains 9.9 is required for JDK 25 class files. Fine today. Worth a follow-up TODO that says "drop ASM when D-16 moves to a source-level rule or when Jmix itself ships an `@AiReadOnly` convention." The dependency is test-only, so risk is bounded.

---

## Jmix-Reuse Audit

### Per-Class Table

| Class | Jmix-provided? | Owned surface (legitimate) | Duplication / concern | Recommendation |
|---|---|---|---|---|
| `AndNode`, `OrNode`, `NotNode`, `LeafNode`, `FilterNode` | `Condition` / `LogicalCondition` / `PropertyCondition` are Jmix's native tree; but Jmix has no **JSON-friendly polymorphic DSL** — these sealed records bind Jackson → Condition | Wire-shape for LLM (JSON tag deduction) + exhaustive pattern match for mapper | None. Thin translation layer Jmix does not provide | **KEEP** |
| `FilterDslMapper` | Jmix provides the operators (`PropertyCondition.Operation.*`), `LogicalCondition.and/or`, and `AccessManager` attribute checks | Depth-cap governance, per-hop attribute ACL walk, DeMorgan NOT expansion (no `.invert()` in Jmix 2.8), strict op allow-list | None substantial. The DeMorgan expansion is a v1 decision; Jmix does not own this | **KEEP** |
| `LiteralCoercer` | `Datatype.parse(String)` exists | Strict ISO/locale-free coercion for LLM input; enum-name and UUID coercion; `IN_LIST` element-wise coerce | Very slight overlap with `Datatype.parse`, but intentional (locale-free). Add clarifying javadoc (IN-05) | **KEEP** with javadoc note |
| `AiAttributeInfo` / `AiEntityInfo` / `AiSchema` | `MetaClass`/`MetaProperty` already describe the model | LLM-facing DTO shape (type labels like `"ref:jmixapp_Customer"`, enum-name list, localized caption) | Tiny: `AiEntityInfo.metaClass` field hands the live Jmix object through; mostly harmless (IN-03) | **KEEP** (optionally `@JsonIgnore` metaClass) |
| `UserEditableStringIndex` | `MetadataTools.isJpa(mp)` / `isSystemLevel(mp)` / `isSystem(mp)` already provided | Per-entity set of string attrs to wrap in `<data>…</data>` — cached at scan time | None. Pure index over Jmix MetadataTools output | **KEEP** |
| `MetamodelScanner` | `Metadata.getSession().getClasses()`, `MetadataTools.isJpa*`, `@SystemLevel` | One-shot raw inventory on `ApplicationReadyEvent` + compute user-editable-string index | Could use `MetadataTools.isJpaEntity(MetaClass)` at the top level to drop non-persistent meta-classes (WR-04). Otherwise thin | **KEEP** — SIMPLIFY per WR-04 |
| `EffectiveSchemaComputer` | `AccessManager.applyRegisteredConstraints` + `CrudEntityContext` + `EntityAttributeContext` are exactly the right API; `MetadataTools.getEntityCaption(MetaClass)` / `getPropertyCaption(MetaProperty)` resolve localized captions | Per-request ACL filter + localized caption resolution (not cached, per T-03-01) | **Wrong API**: uses `MessageTools.getEntityCaption/getPropertyCaption` which don't exist on MessageTools; must switch to `MetadataTools` (WR-01) | **KEEP** — FIX per WR-01 |
| `BuiltInDataTools` | `DataManager.load(...).condition/query/id/fetchPlan/maxResults/list/optional`, `DataManager.getCount(LoadContext)`, `FetchPlans.builder`, `FetchPlan.INSTANCE_NAME`, `MetadataTools.getPrimaryKeyProperty`, `EntityValues.getValue`, `Metadata.getClass(String)` | Six `@Tool` methods (thin composition), +1 truncation detection via `maxResults(limit+1)`, per-call ACL double-check on relationships | `countRecords` still builds a JPQL string (IN-02) where LoadContext+Condition alone would do. No other duplication: every load path is a direct `DataManager.load(...)` fluent chain | **KEEP** — SIMPLIFY countRecords per IN-02 |
| `ToolResultFormatter` | `EntityStates.isLoaded`, `EntityValues.getValue`, `MetaClass.getProperties`, Jmix `@InstanceName` (via `toString()`) | JSON serialization + `<data>` wrapping + delimiter escaping + truncation hint (D-14) | Collection-handling is too broad (WR-03); conflates to-many associations with scalar collections | **KEEP** — FIX per WR-03 |
| `ToolUserError` / `ToolErrorDto` | Jmix does not own an LLM-error schema | LLM-facing `{error, reason, expected}` DTO + unchecked throw-at-boundary pattern | None | **KEEP** |
| `ToolLimits` | No Jmix equivalent | Per-TOOL-06 constants (20/100/3) + `clampLimit` | None | **KEEP** |
| `AgentToolCallbacks` | Spring AI `MethodToolCallbackProvider.builder().toolObjects(...).getToolCallbacks()` | Per-request concatenation of built-ins + ToolContributor output | None; but class javadoc promises per-user filtering that isn't actually implemented at this layer (IN-01) — filtering is pushed into tool bodies via `EffectiveSchemaComputer`, which is fine, just under-documented | **KEEP** — clarify doc per IN-01 |
| `AiToolsAutoConfiguration` | Spring Boot `@AutoConfiguration` + `@AutoConfigureAfter` | Ordering anchor only | None | **KEEP** |
| `OrderSummaryToolContributor` (jmix-app) | `DataManager.load(...).query(...).parameter(...).fetchPlan(...).list()`, `FetchPlan.INSTANCE_NAME`, `FetchPlans.builder` | Host-side sample `@Tool` that joins Order+Customer — exercises the SPI | None; note the class-doc correctly calls out that host tools are trusted code (not scanned by D-16) | **KEEP** |

### Summary: KEEP / SIMPLIFY / DEFER / DELETE

**KEEP (no change needed):**
- Filter DSL records (`AndNode`/`OrNode`/`NotNode`/`LeafNode`/`FilterNode`) — Jmix owns `Condition`, not a JSON wire DSL.
- `FilterDslMapper` — depth cap, DeMorgan expansion, per-hop ACL are genuinely owned.
- `LiteralCoercer` — deliberately locale-free; not replaceable by `Datatype.parse`.
- `AiSchema`, `UserEditableStringIndex` — thin DTO / index over Jmix MetadataTools.
- `ToolLimits`, `ToolUserError`, `ToolErrorDto` — no Jmix counterpart.
- `AiToolsAutoConfiguration`, `AgentToolCallbacks`, `OrderSummaryToolContributor` — Spring AI glue + SPI sample.

**SIMPLIFY:**
- `EffectiveSchemaComputer` — replace `MessageTools.getEntityCaption/getPropertyCaption` with `MetadataTools.getEntityCaption/getPropertyCaption` (WR-01). Caption resolution is exactly what Jmix MetadataTools provides.
- `MetamodelScanner.scan()` — gate the outer loop on `metadataTools.isJpaEntity(mc)` to skip DTO meta-classes and embeddables (WR-04). Pure reuse of a Jmix-owned probe.
- `BuiltInDataTools.countRecords` — drop the hand-built `"select e from X e"` JPQL in favor of `LoadContext` + `setCondition(...)` alone if Jmix 2.8's `DataManager.getCount(LoadContext)` accepts no-query (IN-02). Verify before changing.
- `ToolResultFormatter.buildEntityMap` — narrow the `Collection` branch to `mp.getRange().isClass()` (WR-03) so scalar collections aren't silently dropped.
- `LiteralCoercer` — add a class-level javadoc paragraph explaining the intentional non-use of `Datatype.parse` (IN-05).
- `AgentToolCallbacks` — tighten javadoc so it matches the implementation (IN-01).

**DEFER:**
- `AiEntityInfo.metaClass` `@JsonIgnore` / DTO split (IN-03): no live bug; revisit if we ever serialize `AiEntityInfo` directly.
- Broader validation-constraint extraction (IN-04): D-02 set is sufficient for v1; revisit in v2 or when host asks.
- ASM 9.9 pin (IN-06): test-only, revisit on Jmix 2.9 / JDK baseline move.

**DELETE:**
- None. No parallel metadata layer, no ACL re-implementation, no query wrapper duplicating `DataManager`. The adapter is correctly scoped.

### Open Question

There is a potential compilation risk in `EffectiveSchemaComputer` (WR-01). If the module currently builds and passes tests, it implies either:
(a) a local extension of `MessageTools` adds those methods (in which case that extension is itself a candidate for DELETE — use MetadataTools), or
(b) the file does not yet compile in CI with stock Jmix 2.8.
Confirm which during the fix for WR-01.

---

_Reviewed: 2026-04-19_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_
_Lens: Jmix-reuse audit_
