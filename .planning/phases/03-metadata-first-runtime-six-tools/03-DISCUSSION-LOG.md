# Phase 3: Metadata-First Runtime & Six Tools - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-19
**Phase:** 03-metadata-first-runtime-six-tools
**Areas discussed:** Schema shape, Filter DSL, Tool registration & executor, Formatter / limits / TOOL-08

**User framing (locked before discussion):** "Before building custom infrastructure for Phase 3, first verify whether Jmix already provides the capability and reuse it wherever possible. Prefer Jmix Metadata for schema introspection, AccessManager for per-user permission checks, DataManager for secure data access, and Condition/generic filter APIs for structured querying. The goal of Phase 3 should not be to recreate Jmix's metadata, security, or query engine, but to add only the thin LLM-facing adapter layer that Jmix does not already provide."

---

## Schema shape for LLM

### Q: What should `list_entities` return per entity?

| Option | Description | Selected |
|--------|-------------|----------|
| Name + display label only | Minimal tokens; LLM calls `describe_entity` for detail. | ✓ |
| Name + label + field count | Adds size hint. | |
| Full summary (name, label, all attr names) | One-shot listing; higher token cost. | |

**User's choice:** Name + display label only (Recommended).

### Q: What should `describe_entity` expose per attribute?

| Option | Description | Selected |
|--------|-------------|----------|
| Name, type, nullable, enum values, relationship target | Minimum needed to build valid filters. | |
| Above + display label + validation constraints | Adds i18n labels + @NotNull/@Size/@Pattern. | ✓ |
| Expose MetaProperty as-is | Thin pass-through; exposes framework internals. | |

**User's choice:** Above + display label + validation constraints.

### Q: How should schema output be serialized to the LLM?

| Option | Description | Selected |
|--------|-------------|----------|
| Structured JSON string | Reliable parsing + `<data>` wrapping integrates cleanly. | ✓ |
| Markdown tables | Human-readable; token-heavier. | |
| Claude's discretion | Planner picks after prototyping. | |

**User's choice:** Structured JSON string (Recommended).

### Q: Which locale for display labels in schema output?

| Option | Description | Selected |
|--------|-------------|----------|
| Current user's locale | Consistent with Jmix UI, matches chat locale. | ✓ |
| English only | Simpler; loses locale signal. | |
| Both (key + localized) | Extra tokens. | |

**User's choice:** Current user's locale (Recommended).

---

## Filter DSL → Condition

### Q: Which comparison operators should the filter DSL support in v1?

| Option | Description | Selected |
|--------|-------------|----------|
| Jmix `Op` enum 1:1 | Reuses Jmix engine verbatim. | ✓ |
| Curated subset | Small LLM friendly; needs mapping table. | |
| Claude's discretion | Planner picks. | |

**User's choice:** Jmix Op enum 1:1 (Recommended).

### Q: How should AND/OR/NOT nesting work in the DSL?

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit LogicalCondition nodes | `{and}`, `{or}`, `{not}` nested leaves. | ✓ |
| Flat AND-only list | Simpler v1. | |
| AND-only + LLM chains multiple calls | Minimal DSL; token cost. | |

**User's choice:** Explicit LogicalCondition nodes (Recommended).

### Q: How should the mapper handle literal type coercion?

| Option | Description | Selected |
|--------|-------------|----------|
| Coerce strictly, reject on mismatch | Fail-closed with descriptive error. | ✓ |
| Best-effort coerce, swallow errors | Risky. | |
| Require LLM to pre-format literals | Same as strict coerce without explanatory errors. | |

**User's choice:** Coerce strictly, reject on mismatch (Recommended).

### Q: How deep may filter attribute paths traverse?

| Option | Description | Selected |
|--------|-------------|----------|
| Follow Jmix metadata, cap at configurable depth (default 3) | Per-hop AccessManager check. | ✓ |
| One hop only | Safest; limits useful queries. | |
| No limit — DataManager enforces security anyway | Simpler; risks pathological joins. | |

**User's choice:** Follow Jmix metadata, cap at configurable depth (default 3) (Recommended).

---

## Tool registration & executor

### Q: How are the six built-in tools structured as Spring beans?

| Option | Description | Selected |
|--------|-------------|----------|
| One `@Component` with six `@Tool` methods | Shared injection; simple TOOL-08 test. | ✓ |
| Six separate `@Component` classes | Cleaner SRP; more boilerplate. | |
| Claude's discretion | Planner picks. | |

**User's choice:** One @Component with six @Tool methods (Recommended).

### Q: How are tools attached to the ChatClient per request?

| Option | Description | Selected |
|--------|-------------|----------|
| `ChatClient.Builder.tools(MethodToolCallback[])` per request | Matches roadmap deliverable. | ✓ |
| `ToolCallbackResolver` + bean names | Less request-level control. | |
| Claude's discretion | Planner verifies M4 signature. | |

**User's choice:** ChatClient.Builder.tools(...) per request (Recommended).

### Q: How do tools accept the 'entity name' argument from the LLM?

| Option | Description | Selected |
|--------|-------------|----------|
| Jmix entity name string, resolved via `Metadata.getClass(name)` | Stable across renames. | ✓ |
| Fully qualified Java class name | Exposes package layout. | |
| Caller chooses (name or FQCN) | Two code paths. | |

**User's choice:** Jmix entity name string (Recommended).

### Q: Default fetch plan for get_record and get_related_records?

| Option | Description | Selected |
|--------|-------------|----------|
| `_instance_name` base + requested relationships | Cheap, no N+1 surprises. | ✓ |
| `_local` fetch plan | More complete; higher tokens. | |
| LLM-supplied attribute projection list | Most flexible; more DSL surface. | |

**User's choice:** _instance_name base + requested relationships (Recommended).

---

## Formatter, limits, TOOL-08

### Q: Which string fields get `<data>…</data>` wrapping in tool results?

| Option | Description | Selected |
|--------|-------------|----------|
| Every user-editable string attribute | Matches TOOL-07; minimal noise. | ✓ |
| Every string field including system/framework | Defensive; more tokens. | |
| Claude's discretion | Planner picks exact detection rule. | |

**User's choice:** Every user-editable string attribute (Recommended).

### Q: What does find_records return when rows > cap?

| Option | Description | Selected |
|--------|-------------|----------|
| Truncated list + `truncated:true` + hint to use count_records | No extra DB call by default. | ✓ |
| Truncated list + pre-computed totalCount | Extra getCount() per call. | |
| Fail the call when results exceed cap | Harsh; loop risk. | |

**User's choice:** Truncated list + truncated:true + hint to count_records (Recommended).

### Q: What does the ToolContributor sample impl do?

| Option | Description | Selected |
|--------|-------------|----------|
| jmix-app domain tool (host-side) | Real extension pattern; exercises mixed tool set. | ✓ |
| Toy no-op sample in ai-agent test resources | Smaller blast radius; less convincing. | |
| Both — toy in docs + real in jmix-app | More files; clearer onboarding. | |

**User's choice:** jmix-app domain tool (Recommended).

### Q: Where/how does the read-only enforcement test live (no ArchUnit)?

| Option | Description | Selected |
|--------|-------------|----------|
| Reflection unit test on `BuiltInDataTools` | Localized to one class; simple. | ✓ |
| Integration test via Jmix persistence listener | Runtime signal; can't be gamed. | |
| Both — reflection + runtime listener | Belt-and-braces. | |

**User's choice:** Reflection unit test on BuiltInDataTools (Recommended).

---

## Claude's Discretion

- Exact package layout, bean names, configuration keys, ASM vs. method-descriptor reflection, and the specific `jmix-app` tool chosen as the `ToolContributor` sample — all delegated to the planner per D-decisions in CONTEXT.md.

## Deferred Ideas

- LLM-supplied attribute projection list for `get_record`.
- Pre-computed `totalCount` in `find_records` response.
- OR-unions via multiple `find_records` calls (alternative to nested DSL).
- `ToolGuard` wiring — Phase 6.
- Audit DTO emission from tool bodies — Phase 4.
- Mutation tools — v2.
- ArchUnit rules — dropped per D-09.

---

## 2026-04-20 — Post-execute refactor doc resync

**Trigger:** User feedback absorbed during Phase 3 execution — "Reuse Jmix built-ins over parallel layers" (see `memory/feedback_reuse_jmix_builtins.md`).

**Code changes applied post-execute (commits already on branch, tests green):**
- Metadata layer collapsed: 6 files (`AiSchema`, `AiEntityInfo`, `AiAttributeInfo`, `UserEditableStringIndex`, `MetamodelScanner`, `EffectiveSchemaComputer`) -> 1 adapter (`CurrentUserSchemaAccess`). TOOL-01 / TOOL-02 surface identical; only the shape changed.
- Filter DSL renames: `LiteralCoercer` -> `FilterLiteralValueConverter`; `FilterDslMapper` -> `StructuredFilterConditionMapper`. Tests renamed to match.
- New files surfaced in code: `com.vn.agent.tools.ToolResultPayloads` (add-on), `com.vn.jmixapp.ai.ChatServiceSmokeRunner` (host — note: this may predate Phase 3; documented here only if Phase 3 referenced it).

**Docs resynced (this entry):**
- 03-01-SUMMARY, 03-02-SUMMARY, 03-03-SUMMARY, 03-04-SUMMARY, 03-05-SUMMARY: factual references (class names, file paths, test names, "What Was Built" prose) updated to match current code. Decisions, rationale, commit hashes, completed_dates preserved.
- 03-AI-SPEC, 03-PATTERNS: class-name references updated; design intent preserved.

**Verification:** `./gradlew :ai-agent:ai-agent:test` and `./gradlew :jmix-app:test` BUILD SUCCESSFUL at time of resync. Consistency grep for old class names across `.planning/phases/03-*/` returns zero hits outside intentional historical callouts.
