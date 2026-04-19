# Phase 3 — Jmix-Reuse Review (v2, from scratch)

Reviewed: 2026-04-19
Fixed: 2026-04-19
Lens: six justified concerns only; everything else is duplication suspect.

## Fix status (2026-04-19)

| # | Severity | Finding | Status | Commit |
|---|---|---|---|---|
| 1 | HIGH | LiteralCoercer → `Datatype.parse` | **FIXED** | `aa912be` |
| 2 | MEDIUM | MetamodelScanner trim (enum via `asEnumeration`, drop `collectValidationConstraints`, `maxLength` from `getColumn`) | **FIXED** (folded into #3) | `7a4a17c` |
| 3 | MEDIUM | Delete `AiSchema`/`AiEntityInfo`/`AiAttributeInfo`; compose describe live | **FIXED** | `7a4a17c` |
| 4 | MEDIUM | `countRecords` JPQL concat | **PARTIAL** — factored behind `buildCountContext` helper (Jmix 2.8 fluent `getCount()` not available) | `1a4ed2e` |
| 5 | LOW | Collection-valued attributes emit `{_collectionSize}` when loaded | **FIXED** | `81274d7` |
| 6 | LOW | Reference serialization via `MetadataTools.getInstanceName` wrapped in `<data>` | **FIXED** | `81274d7` |
| 7 | LOW | `FilterDslMapper` NOT via `LogicalCondition.NOT` | **SKIPPED** — verified Jmix 2.8 `LogicalCondition.Type` only exposes `AND`/`OR`; DeMorgan expansion stays | — |
| 8 | LOW | `SpiDefaultsAutoConfiguration` no-op defaults | **DEFERRED** to Phase 6 (per original review recommendation) | — |

**Verification:** `./gradlew :ai-agent:ai-agent:test` — BUILD SUCCESSFUL after all fixes.

**Net impact of #3/#7:** `EffectiveSchemaComputer` now returns `Map<MetaClass, Set<String>>`; `MetamodelScanner` owns only `UserEditableStringIndex`; `ToolResultFormatter.describe(mc, allowedAttrs)` computes typeLabel / caption / enum values / maxLength live from `MetaClass` + `MessageTools` + `@Column`. Locale-sensitive by construction, never cached.

---

## Original review (preserved below)

## Verdict

Phase 3 honors the "thin adapter over Jmix" principle more than it violates it. Every data-access path goes through `DataManager`/`FetchPlan`, every permission decision routes to `AccessManager` via `CrudEntityContext`/`EntityAttributeContext`, all query predicates are built as `PropertyCondition`/`LogicalCondition` (no hand-rolled JPQL with LLM input), and captions are resolved through `MessageTools`. The custom surface is mostly limited to the six justified adapter concerns.

However, the scanner carries some duplication (re-deriving enum constant names, validation-constraint formatting, nullability) that Jmix already exposes or that belongs downstream, and the AiEntityInfo/AiAttributeInfo record layer is a snapshot step that adds little over caching `MetaClass`/`MetaProperty` references directly — the schema is computed per-request anyway. A couple of small correctness/security items stand out.

Six-concern scorecard:
1. Schema shaping — **WELL-DELEGATED** (uses Metadata + AccessManager + MessageTools). Snapshot records are overkill but not duplication.
2. Literal coercion — **PARTIAL**. Hand-rolls what Jmix `Datatype.parse(String, Locale)` already does for every registered datatype.
3. Path-depth governance — **WELL-DELEGATED** (delegates per-hop access checks to AccessManager via EffectiveSchemaComputer).
4. Hard result limits — **WELL-DELEGATED** (server-side clamp; `+1` truncation probe; values pinned).
5. Injection-safe formatting — **WELL-DELEGATED** (scanner filters via MetadataTools.isJpa/isSystem/isSystemLevel; formatter wraps and escapes).
6. Per-request tool assembly — **WELL-DELEGATED** (fresh array per call; uses ChatClient request-scoped `.tools(...)` contract per Phase 4 plan — verified that no `defaultTools(...)` appears).

## Findings (severity: CRITICAL / HIGH / MEDIUM / LOW)

### [HIGH] Hand-rolled datatype parsing duplicates `Datatype.parse` — `filter/LiteralCoercer.java:163-273`
- **Observation:** `coerceDatatype` implements an if-else ladder for String, UUID, Boolean, Integer, Long, Short, BigDecimal, Double, Float, LocalDate, LocalDateTime, OffsetDateTime, Instant — each with its own `try/parse/throw` block. Every `Datatype<?>` returned by `mp.getRange().asDatatype()` already provides `parse(String)` and `parse(String, Locale)` that Jmix uses for filter UI, REST, and CSV import.
- **Jmix alternative:** `io.jmix.core.metamodel.datatype.Datatype#parse(String)` (or `parse(String, Locale)` when a locale is available from `CurrentAuthentication`). Jmix ships a `DatatypeRegistry` covering every attribute type in the metamodel, including user-defined ones.
- **Why it matters:** ~110 lines of maintenance surface, no support for user-defined datatypes (a host app registering `MoneyDatatype` breaks silently), and subtle semantic drift from Jmix (e.g. the filter UI's date parsing honors the user's locale; this coercer forces ISO-8601 only). Increases the risk that a new JDK date type shipped by Jmix is not recognized.
- **Suggested change:** Replace the entire datatype ladder with `dt.parse(asString)` wrapped in the ToolUserError envelope. Keep the special cases only for the three non-Jmix-parse paths you actually need: UUID (association ids), enum `valueOf`, and `IS_SET` boolean. Net: ~200 lines → ~40 lines.

### [MEDIUM] Scanner re-derives what `MetadataTools` / `MetaProperty` already expose — `metadata/MetamodelScanner.java:98-161`
- **Observation:** `buildAttributeInfo` walks `Range` to compute a typeLabel string, extracts enum constants via reflection on `javaEnum.getEnumConstants()`, and `collectValidationConstraints` hardcodes only three Bean Validation annotations (NotNull, Size, Pattern) while Jmix/Hibernate-Validator expose many more. `nullable = !mp.isMandatory()` plus `collectValidationConstraints` adds a redundant "NotNull" entry (`isMandatory()` is exactly that).
- **Jmix alternative:** `MetaProperty.getRange().asEnumeration().getValues()` returns the enum constants directly (no reflection). `MetadataTools.isRequired(MetaProperty)` — same semantic as `isMandatory`, and aligns with what the views layer shows users. For validation-constraint surfacing, the pragmatic choice is to emit **only** what Jmix's own filter UI emits (`MetaProperty.isMandatory()`, length from `Size`/`@Column(length=…)` via `metadataTools.getColumn(mp)`), not reinvent a mini-registry.
- **Why it matters:** Duplicates enum walking; omits `@DecimalMin`, `@DecimalMax`, `@Min`, `@Max`, `@Email`, `@Past`, `@Future` — all of which a business entity commonly carries. LLM sees an incomplete picture of constraints. Also "NotNull" shows up twice in the `describe_entity` payload (as `nullable=false` AND as a string in `constraints`) — token noise.
- **Suggested change:** Delete `collectValidationConstraints` entirely in v1; emit only `nullable` and, for strings, a `maxLength` from `metadataTools.getColumn(mp)` when present. Replace `javaEnum.getEnumConstants()` loop with `range.asEnumeration().getValues()`.

### [MEDIUM] `AiEntityInfo` / `AiAttributeInfo` snapshot is a parallel shadow of `MetaClass` / `MetaProperty` — `metadata/AiEntityInfo.java`, `metadata/AiAttributeInfo.java`, `metadata/AiSchema.java`
- **Observation:** Three record types plus `withLocalizedLabel`/`withAttributes` builders mirror the fields of `MetaClass`/`MetaProperty` plus one derived string (`typeLabel`) and one translated caption. The scanner builds them once at startup; the `EffectiveSchemaComputer` then rebuilds them per request (copy + attach label + filter). Every field except `typeLabel` is already on `MetaClass`/`MetaProperty`.
- **Jmix alternative:** Hold `MetaClass` references; compute `typeLabel` / caption / enum values inside `ToolResultFormatter.describe(...)` from the `MetaClass` directly, the same way you compute them for `records(...)`. The "effective schema" becomes just `List<MetaClass>` filtered by `AccessManager`.
- **Why it matters:** Maintenance burden (two parallel type systems), confusion about which source of truth wins when they drift, and the `withLocalizedLabel` plumbing exists solely because the scanner can't know the caller locale — fine, but that's an argument against caching the snapshot at all. Per the `feedback_pragmatic_modules` lens: "split only when a concrete consumer justifies it" — the consumer (describe_entity) can work off live `MetaClass`.
- **Suggested change:** Inline. Keep `UserEditableStringIndex` (it's a genuine precomputed artifact from `@SystemLevel` + type checks), drop `AiSchema`/`AiEntityInfo`/`AiAttributeInfo`, and let `EffectiveSchemaComputer` return `List<MetaClass>` plus a `Set<String>` of allowed attribute names per class. Formatter composes the describe payload live. Saves ~80 lines and one eager startup pass.

### [MEDIUM] `countRecords` builds JPQL via string concat for no reason — `tools/BuiltInDataTools.java:168-174`
- **Observation:** `"select e from " + mc.getName() + " e"` then `LoadContext.Query` + `setCondition`. The comment argues it's safe because `mc.getName()` is whitelisted — which is true. But `DataManager.getCount(LoadContext)` accepts an empty-query LoadContext: omit the Query entirely and Jmix counts by entity + condition. Alternatively use the fluent loader: `dataManager.loadValue("...", Long.class)` is not what you want, but `dataManager.getCount(new LoadContext<>(mc).setQuery(new LoadContext.Query("select e from " + mc.getName() + " e").setCondition(cond)))` is more-or-less obligated only if you need a query; there's also `dataManager.load(cls).condition(cond).getCount()` in recent Jmix which uses the fluent loader and requires no JPQL string at all.
- **Jmix alternative:** `dataManager.load(mc.getJavaClass()).condition(cond).getCount()` (Jmix fluent loader). If that API is unavailable in 2.8, use `LoadContext<>(mc)` with `new Query("select e from X e")` — but even so, don't concatenate; `MetaClass.getName()` is the JPQL entity name by contract.
- **Why it matters:** The concatenation pattern, even when safe, makes the `TOOL-08` ASM/bytecode scan harder (the test has to whitelist this one site) and sets a bad precedent for future tool implementers. Small but meaningful posture fix.
- **Suggested change:** Rewrite as `dataManager.load(mc.getJavaClass()).condition(cond).getCount()`. If the fluent `getCount()` doesn't exist in your Jmix version, at least factor the concat behind a helper with a whitelist assertion.

### [LOW] `ToolResultFormatter.buildEntityMap` silently drops collection-valued attributes — `tools/ToolResultFormatter.java:158-162`
- **Observation:** When a property is a `Collection`, the formatter writes `null`, losing the distinction between "not fetched", "fetched and empty", and "fetched with items we're choosing not to serialize". Comment says this is to avoid lazy loading.
- **Jmix alternative:** `EntityStates.isLoaded(entity, propertyName)` (already injected) — if loaded, emit the collection size or `[]` instead of `null`; if unloaded, emit `null` with a note. The `entityStates.isLoaded` call at line 151 already handles unloaded — the collection branch bypasses that information.
- **Why it matters:** LLM sees "null" and may hallucinate "customer has no orders" when the real answer is "orders wasn't fetched; call `get_related_records`". Small UX/correctness issue.
- **Suggested change:** For collections where `entityStates.isLoaded` is true, emit `{"_collectionSize": n}`; otherwise emit `null`. Alternatively emit a short hint string like `"<use get_related_records>"`.

### [LOW] Reference attribute serialization uses `String.valueOf(v)` — `tools/ToolResultFormatter.java:164-167`
- **Observation:** For a loaded reference attribute, the formatter calls `String.valueOf(v)`, which invokes whatever `toString()` the referenced entity happens to implement. Jmix entities default to an instance-name-based representation via `InstanceNameProvider`, but that is not guaranteed for host-app entities (a host app may override `toString()` for logging). More importantly, the resulting string is **not wrapped in `<data>`** — even though it carries user-editable data (the instance-name almost always combines user-edited fields).
- **Jmix alternative:** `MetadataTools.getInstanceName(Object)` for the canonical instance-name string, and then run it through the same `<data>` wrap if ANY of the attributes composing the instance name is user-editable. Cheaper heuristic: always wrap the reference rendering in `<data>` since instance-names are ~always derived from user-editable fields.
- **Why it matters:** TOOL-07 / D-13 prompt-injection defense has a gap: a malicious customer name flows through `Order.customer.toString()` unwrapped. The `<data>` wrap on the user-editable string index only triggers when the attribute itself is a String, missing the case where a user-editable String is transitively exposed via an @InstanceName-derived reference rendering.
- **Suggested change:** Replace `String.valueOf(v)` with `"<data>" + escapeDataDelimiters(metadataTools.getInstanceName(v)) + "</data>"`. Inject `MetadataTools`.

### [LOW] `resolveOperation` reimplements negation rules Jmix already understands — `filter/FilterDslMapper.java:118-164`
- **Observation:** The method hand-rolls DeMorgan leaf-level negation with a 13-way switch mirroring each operator to its complement. It's correct but verbose.
- **Jmix alternative:** Jmix's own `PropertyCondition` does not expose a `negate()` helper, so there isn't a direct replacement API. This is a genuine adapter concern. However, the DSL could treat `NOT` as a `LogicalCondition.not(...)` — Jmix supports it as a `LogicalCondition` op — and pass the unnegated leaves through. That moves the negation into the query engine instead of expanding it at the mapper.
- **Why it matters:** Correctness is currently fine, but `LogicalCondition.not(...)` already exists in Jmix 2.8 (`LogicalCondition.Type.NOT`). Using it would let you delete half of `resolveOperation`. The explicit `STARTS_WITH`/`ENDS_WITH` under NOT rejection would also disappear.
- **Suggested change:** Keep the DSL's user-facing operator mirrors (clearer for the LLM) but in the mapper, implement NOT as `LogicalCondition.type(LogicalCondition.Type.NOT).add(child)`. Delete the negation XOR plumbing and the "not_negatable" error case.

### [LOW] `SpiDefaultsAutoConfiguration` default `ToolGuard` / `AuditListener` duplicate the SPI-baseline-vs-builtin principle — `ai-agent-starter/.../SpiDefaultsAutoConfiguration.java`
- **Observation:** Per the user's `feedback_spi_baseline_builtin` memory, baseline behaviors should be built-in, SPIs should exist for genuinely-custom extensions. A no-op default `ToolGuard` suggests the guard ought to be the veto hook described in Phase 6 — fine — but a no-op `AuditListener` is essentially saying "we have no baseline audit". Phase 3 scope is tool-only, so this is inherited from a sibling phase; still worth flagging.
- **Jmix alternative:** Not a Jmix issue per se; flagged under the user's own architecture lens.
- **Why it matters:** Each no-op default is a maintenance tax and a hint that "default X = nothing" is a code smell.
- **Suggested change:** Out-of-scope for Phase 3 but revisit in Phase 6 when `ToolGuard` gets real logic.

## Per-file verdicts

| File | Role | Verdict | Note |
|---|---|---|---|
| `tools/BuiltInDataTools.java` | Six @Tool methods | KEEP | Thin; fix the `countRecords` JPQL concat (MEDIUM). |
| `tools/AgentToolCallbacks.java` | Per-request tool assembly | KEEP | Clean adapter; correctly avoids `defaultTools`. |
| `tools/ToolResultFormatter.java` | JSON + `<data>` wrap | SIMPLIFY | Extend `<data>` wrap to reference toString() output (LOW); use `MetadataTools.getInstanceName`. |
| `tools/ToolLimits.java` | Limit constants | KEEP | Minimal, pinned by tests. |
| `tools/ToolErrorDto.java` | Error record | KEEP | Thin. |
| `tools/ToolUserError.java` | Unchecked error | KEEP | Thin. |
| `metadata/MetamodelScanner.java` | Startup inventory | SIMPLIFY | Drop `collectValidationConstraints`; use `range.asEnumeration().getValues()`; reconsider snapshot vs. live. |
| `metadata/EffectiveSchemaComputer.java` | Per-request access filter | KEEP | Textbook use of `AccessManager` + contexts + `MessageTools`. |
| `metadata/AiSchema.java` | Schema record | DELETE-INLINE | Live `List<MetaClass>` suffices. |
| `metadata/AiEntityInfo.java` | Entity record | DELETE-INLINE | Shadows `MetaClass`. |
| `metadata/AiAttributeInfo.java` | Attribute record | DELETE-INLINE | Shadows `MetaProperty`. |
| `metadata/UserEditableStringIndex.java` | Attribute-name set | KEEP | Genuine precomputed artifact. |
| `filter/FilterDslMapper.java` | DSL → Condition | SIMPLIFY | Replace negation XOR with `LogicalCondition.NOT`. |
| `filter/LiteralCoercer.java` | String → typed | REPLACE-WITH-JMIX | Delegate to `Datatype.parse`; keep UUID/enum/boolean special cases only. |
| `filter/FilterNode.java` + And/Or/Not/LeafNode | DSL shape | KEEP | Clean sealed hierarchy; required by Jackson. |
| `AIConfiguration.java` | Module wiring | KEEP | Standard Jmix module config. |
| `jmix-app/.../OrderSummaryToolContributor.java` | SPI sample | KEEP | Good integration example; raw-JPQL is justified (trusted host code) and parameterized. |
| `ai-agent-starter/.../AIAutoConfiguration.java` | Starter wiring | KEEP | Standard Spring Boot autoconfig. |
| `ai-agent-starter/.../AiToolsAutoConfiguration.java` | Ordering anchor | KEEP | Pure ordering; no logic. |
| `ai-agent-starter/.../SpiDefaultsAutoConfiguration.java` | No-op SPI defaults | REVISIT | Out-of-scope for Phase 3; see LOW finding. |

## What Phase 3 gets right

- `EffectiveSchemaComputer` is exactly the right shape: no caching, per-request, delegates to `AccessManager` with `CrudEntityContext` + `EntityAttributeContext`, and resolves captions through `MessageTools.getEntityCaption` / `getPropertyCaption`. This is the model the rest of the metadata layer should follow.
- All data access goes through `DataManager` with explicit `FetchPlan.INSTANCE_NAME`, and the `+1` truncation probe is an idiomatic pagination pattern.
- `BuiltInDataTools.resolveOrError` is a clean, fail-closed gate: whitelist → MetaClass → AccessManager check.
- `FilterDslMapper` produces `PropertyCondition`/`LogicalCondition` exclusively — zero JPQL from LLM input. This is the single most important security posture in Phase 3 and it is correctly observed.
- `AgentToolCallbacks.forCurrentUser()` returns a fresh array per call and docs the reason — correctly avoids the `.defaultTools(...)` trap.
- `MetamodelScanner.isUserEditableString` uses `MetadataTools.isSystemLevel`/`isSystem`/`isJpa` rather than rolling its own persistence detection — textbook delegation.

## Recommended next steps

1. **Replace `LiteralCoercer.coerceDatatype` with `Datatype.parse`.** (HIGH) Deletes ~100 lines, gains user-defined datatype support, removes locale drift risk. ~1 hour.
2. **Delete `AiSchema` / `AiEntityInfo` / `AiAttributeInfo`; let `EffectiveSchemaComputer` return `Map<MetaClass, Set<String>>` (allowed attrs per class); move `typeLabel` / enum values / constraint rendering into `ToolResultFormatter.describe`.** (MEDIUM) Collapses the snapshot-vs-live duplication, removes `withLocalizedLabel` plumbing, keeps `UserEditableStringIndex`. ~3 hours.
3. **Fix reference-attribute serialization** (LOW but real security gap): swap `String.valueOf(v)` for `MetadataTools.getInstanceName(v)` wrapped in `<data>` + delimiter escape. Add a test with a hostile customer name flowing through `Order.customer`. ~30 min.
4. **Replace `countRecords` JPQL concat with `dataManager.load(...).condition(cond).getCount()`.** (MEDIUM) Closes the last concat site; simplifies the Phase 4 ASM test. ~20 min.
5. **Trim `collectValidationConstraints` to just `nullable` + optional `maxLength`.** (MEDIUM) Removes double-reporting of NotNull and eliminates the hand-rolled constraint registry. ~30 min.

Stretch (LOW): use `LogicalCondition.NOT` instead of the DeMorgan XOR in `FilterDslMapper`; that deletes `resolveOperation`'s negation arms and the `not_negatable` error path.
