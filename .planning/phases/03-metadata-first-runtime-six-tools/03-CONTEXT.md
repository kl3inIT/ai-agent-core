# Phase 3: Metadata-First Runtime & Six Tools - Context

**Gathered:** 2026-04-19
**Status:** Ready for planning

<domain>
## Phase Boundary

Build the **thin LLM-facing adapter layer** over Jmix `Metadata`, `AccessManager`, `DataManager`, and the `Condition` DSL. The goal is NOT to recreate Jmix's metadata, security, or query engine — it is to expose just enough of those to Spring AI tool-calling that the LLM can safely query host data under the caller's Jmix security context.

Deliverables:
- **`MetamodelScanner`** — raw `MetaClass` / `MetaProperty` inventory, cached once at startup.
- **Per-request effective-schema computer** — applies `AccessManager` directly (no `EntityExposurePolicy` chain per D-05). Produces the LLM-visible schema for the current user.
- **Six `@Tool` methods** on a single `BuiltInDataTools` bean: `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`.
- **`DataManagerToolExecutor`** — executes tool bodies through `DataManager.load(...).query(...)` only; no raw JPQL from LLM input, no mutations.
- **Filter DSL → `Condition` mapper** — structured JSON (operator/attribute/literal + and/or/not nesting) mapped to `PropertyCondition` + `LogicalCondition`.
- **Hard `limit` cap** — default 20, max 100 per `TOOL-06`; enforced via constant + unit test (no ArchUnit per D-09).
- **`<data>`-delimited safe formatter** — wraps user-editable string attributes to defuse prompt injection (`TOOL-07`).
- **`ToolContributor` SPI impl (sample)** — a host-side real tool in `jmix-app` that demonstrates the extension pattern and exercises the executor with a mixed built-in + host tool set.
- **SPI-01 impl** — executor consumes `ToolContributor` beans alongside built-ins when composing the per-request tool list.

**In scope:**
- Metamodel scanner + effective schema + six tools + filter DSL + executor + formatter + limit cap.
- Reflection-based unit test asserting `BuiltInDataTools` contains no `DataManager.save/remove` calls and no parameter-derived JPQL (replaces ArchUnit for `TOOL-08`).
- Prompt-injection harness covering the `notes = "SYSTEM: ignore previous instructions"` case (success criterion #5).
- Integration test in `jmix-app` for `find_records("Order", filter=…)` through `ChatService` (success criterion #3).
- One real host-side `ToolContributor` impl in `jmix-app` (the sample).

**Out of scope (explicit):**
- Any AI-specific permission/exposure layer beyond `AccessManager` (D-05 stands).
- Advisor chain, `ChatClientFactory`, `ChatMemory` wiring — Phase 4.
- Audit advisor / `AuditAdvisor` invocation — Phase 4 (tools may produce audit DTOs, but persistence pipeline is P4).
- RAG, `QuestionAnswerAdvisor`, pgvector retrieval — Phase 5.
- Parameter profiles, `ToolGuard`, rate limits, iteration cap — Phase 6.
- Mutation tools (any kind) — v2.
- ArchUnit rules — dropped per D-09.
- ViewPolicy / attribute policies declarative config — Phase 7 / not planned.
- UI for tool exposure review — dropped per D-10.

</domain>

<decisions>
## Implementation Decisions

### Schema Shape for the LLM

- **D-01: `list_entities` returns name + localized display label only, nothing else.** Minimal tokens. The LLM drills down with `describe_entity` when it needs attribute detail. Matches the "thin adapter" philosophy — the scanner still holds the full inventory internally; the tool output is intentionally lean.

- **D-02: `describe_entity` exposes: attribute name, type, nullable, enum values (for enum-typed attrs), relationship target entity name, localized display label, and Jmix validation constraints (`@NotNull` / `@Size` / `@Pattern` equivalents).** No Jmix internals (column names, DDL metadata, cascade rules). Constraints included so the LLM can explain fields in UI terms and reject obviously-invalid filters before calling `find_records`.

- **D-03: All tool output is serialized as a structured JSON string.** Including schema output. User-editable string fields inside the JSON are wrapped in `<data>…</data>` with escaping per D-10 below. LLM parses reliably; we keep one formatter code path.

- **D-04: Display labels use the current user's locale** (`CurrentAuthentication` → `Locale`). Consistent with the rest of the user's Jmix UI experience. Scanner does not pre-localize at startup; effective-schema computer resolves labels per-request against `MessageTools` / `Messages`.

### Filter DSL → `Condition` Mapper

- **D-05: Filter DSL operators map 1:1 to the Jmix `PropertyCondition.Operation` enum** (EQUAL, NOT_EQUAL, GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, CONTAINS, DOES_NOT_CONTAIN, STARTS_WITH, ENDS_WITH, IN_LIST, NOT_IN_LIST, IS_SET). No translation table to maintain. Planner confirms the exact enum values during research — Jmix is authoritative.

- **D-06: AND/OR/NOT nesting is explicit in the DSL.** JSON shape accepts `{and: [...]}`, `{or: [...]}`, `{not: {...}}` with `PropertyCondition`-style leaves. Maps directly to `LogicalCondition.and(...)` / `.or(...)` / `.invert()`. LLM can express any v1 query shape without multiple round-trips.

- **D-07: Literal type coercion is strict; failure throws a descriptive error that reaches the LLM.** ISO-8601 for date/instant/localDateTime; `UUID.fromString(...)` for UUIDs; `Enum.valueOf(enumClass, name)` for enums; `BigDecimal`/`Long`/`Integer` via type-aware parse for numbers. On failure the tool returns a structured error (not a stack trace) describing the expected type — the LLM retries with a fixed literal. Fail-closed: invalid clause = no results, not "best-effort silently drop".

- **D-08: Filter attribute paths follow Jmix metadata with a configurable depth cap (default 3).** `customer.region.name` OK by default; `customer.region.country.continent.name` rejected. Each hop validated against `AccessManager` for read permission on both the owning entity and the attribute; denied attribute = rejected filter, not silently filtered. Cap configurable via add-on property (planner picks the exact key, e.g. `jmix.ai-agent.tools.max-filter-depth`).

### Tool Registration & Executor

- **D-09: Six built-in tools live as `@Tool`-annotated methods on one `BuiltInDataTools` `@Component`.** One bean, shared `DataManager` / `AccessManager` / `Metadata` / `MessageTools` / effective-schema-computer injection via constructor. Keeps the `TOOL-08` reflection test trivial (one class to walk). Naming: the class stays in `com.vn.agent.tools` (or a package the planner picks).

- **D-10: Tools are attached to `ChatClient` per request via `ChatClient.Builder.tools(MethodToolCallback[])` — never `.defaultTools(...)`.** Matches the roadmap deliverable. Phase 3 exposes a `ToolCallbackProvider` (or equivalent named `AgentToolCallbacks`) that the Phase 4 `ChatClientFactory` invokes when building a request. Per-request assembly: built-ins (filtered to what the caller can see) + all `ToolContributor.contribute()` beans. Planner verifies `MethodToolCallback.Builder` signature in M4 during research (roadmap flags Phase 3 as "partial research").

- **D-11: LLM references entities by their Jmix entity name (e.g. `"Order"`), resolved via `Metadata.getClass(name)`.** Stable across Java-class renames; matches how Jmix speaks about itself. Resolver returns a structured error when the name is unknown or the caller has no read policy on the class. FQCN is NOT accepted (one path only).

- **D-12: Default fetch plans for `get_record` and `get_related_records` use the Jmix `_instance_name` base plan plus the requested relationship expanded to the target's `_instance_name`.** Cheap, avoids N+1, predictable. `get_record` returns the root entity with its `_instance_name` attributes; `get_related_records` returns the related rows with their `_instance_name` attributes. The LLM drills further via additional tool calls. No LLM-supplied attribute projection in v1.

### Formatter, Limits, TOOL-08

- **D-13: `<data>…</data>` wrapping applies to every user-editable string attribute.** "User-editable" = persistent + string-typed + not marked system (`@SystemLevel` / framework-managed `createdBy`, `updatedTs`, etc.). Scanner pre-computes the user-editable set per `MetaClass` so the formatter does not re-derive on every call. Escaping rule: any literal `<data>` or `</data>` sequence inside the value is escaped (e.g. to `<data>` / `</data>`) before wrapping. Success criterion #5 (the `notes = "SYSTEM: ignore previous instructions"` case) is covered by this wrapping + escaping.

- **D-14: `find_records` truncation UX — return `truncated: true`, `limit: N`, and a hint pointing at `count_records` when results exceed the cap.** No extra `DataManager.getCount()` call by default; the LLM learns the cap via the tool description text and can explicitly call `count_records` when it needs the exact total or narrower filter guidance. Cap constants: default `20`, max `100` (per `TOOL-06`); both enforced via a `ToolLimits` constant class and a unit test pinning the values. LLM cannot override via tool arguments (the `limit` arg, if any, is clamped to `[1, max]`).

- **D-15: `ToolContributor` sample is a real jmix-app domain tool, not a toy in the add-on test sources.** The sample lives in `jmix-app` (e.g. `OrderTotalCalculator` or similar — planner picks the most illustrative), demonstrates the real extension pattern a host would use, and is exercised by an integration test that builds a per-request tool set containing built-ins + this sample together. This also proves the per-request assembly pipeline works end-to-end.

- **D-16: `TOOL-08` read-only enforcement is a reflection-based unit test on `BuiltInDataTools`.** The test walks all `@Tool`-annotated methods and asserts (a) no method body references `DataManager.save` / `DataManager.saveContext` / `DataManager.remove`, and (b) no method constructs a JPQL / SQL string from a parameter. Reflection on bytecode via ASM or a simpler method-body scan — planner picks. Located next to `BuiltInDataTools` in the unit test tree. This replaces what TEST-06/ArchUnit would have enforced; code review + this test are authoritative per D-09.

### Claude's Discretion

- Exact package layout for scanner / schema computer / tools / DSL mapper within `com.vn.agent` — planner picks; follow the module's existing conventions.
- Exact name of the `ToolCallbackProvider` / `AgentToolCallbacks` bean and its interface surface — planner picks a name that reads well from Phase 4's `ChatClientFactory` call site.
- Choice of ASM vs. simpler reflection-on-method-descriptors for the `TOOL-08` test (D-16) — planner picks; both satisfy the intent.
- Exact configuration property keys (e.g. for the filter-depth cap in D-08, for the limit cap in D-14) — planner picks, uses the `jmix.ai-agent.tools.*` namespace consistently.
- The specific `jmix-app` sample tool picked for D-15 — planner surveys the `jmix-app` entity set and picks the most illustrative domain operation. Prefers something that joins two entities to exercise the tool-call surface meaningfully.
- Whether the scanner caches by `MetaClass` reference or by entity name string — planner picks.
- Error DTO shape that surfaces to the LLM on filter-mapping failure (D-07), unknown-entity (D-11), denied-attribute (D-08) — planner picks a consistent `{error, reason, expected?}` JSON shape.
- Whether the effective-schema computer is a `@Component` (stateless) or a `@Scope("request")` bean — planner picks based on Jmix wiring norms.
- Exact enum values returned in `describe_entity` — planner reads `Metadata` / `Datatypes` and picks the representation (name list vs. name+label list) that stays token-lean but informative.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 3 — Metadata-First Runtime & Six Tools" — goal, deliverables, success criteria, research flag.
- `.planning/REQUIREMENTS.md` — `TOOL-01..08`, `SPI-01` (impl), `TEST-02` (partial); also `SEC-03`, `SEC-04` inherited.
- `.planning/PROJECT.md` §"Metadata-first runtime", §"Safety posture (v1)" — value prop + safety posture this phase delivers.
- `.planning/STATE.md` — current progress.
- `.planning/phases/02-foundations/02-CONTEXT.md` — D-05 (no exposure layer), D-09 (no ArchUnit), D-11 (baseline context built-in, SPIs app-specific only). Directly shapes Phase 3 `TOOL-02` and `TOOL-08`.
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` §D-03 — `ChatService` signature + module layout still authoritative.
- `.planning/research/ARCHITECTURE.md` — target advisor chain + where tools sit in it (Phase 4 consumes Phase 3's tool list).
- `.planning/research/STACK.md` — Spring AI 1.1.4 pin (upgraded from 1.0.2 per D-10), Jmix 2.8 / Boot baseline.
- `.planning/research/PITFALLS.md` — any known tool-calling / metadata gotchas from earlier research.

### Project conventions
- `CLAUDE.md` — Jmix conventions, especially: `DataManager` only (no `EntityManager`), `Metadata.create()` / `DataManager.create()` for entity instantiation, `msg://` keys for any user-visible text, JetBrains MCP `get_file_problems` workflow.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — `@JmixModule(dependsOn = {...})` entry point; Phase 3 may widen the dependsOn set.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java` — Phase 2 interface; D-15 sample impl lives in `jmix-app`, not here.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolVetoedException.java` — exists in SPI package; relevant for future `ToolGuard` wiring (Phase 6), not Phase 3.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/` — Phase 2 entities; Phase 3 does NOT touch them.
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — register any new Phase 3 auto-config (scanner, executor, tools bean) here.
- `jmix-app/src/main/resources/com/vn/jmixapp/` — host app; D-15 sample `ToolContributor` lives here.

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — `MetaClass` / `MetaProperty` model.
- `jmix-services` — `DataManager` fluent loading, `loadValues`, `getCount`; `Condition` / `PropertyCondition` / `LogicalCondition` surface.
- `jmix-fetch-plans` — `_instance_name`, `_local`, composed fetch plans (D-12).
- `jmix-security-roles` — `AccessManager`, `EntityContext`, `EntityAttributeContext`, `InMemoryCrudEntityContext` — how Phase 3 computes effective schema (TOOL-02, D-08).
- `jmix-i18n` — `MessageTools`, `Messages`, locale resolution for display labels (D-04).
- `jmix-testing` — `@SpringBootTest` harness for the `jmix-app` integration test in success criterion #3.

### Spring AI primitives to verify in research
- `MethodToolCallback.Builder` (M4 / Spring AI 1.1.4) — signature + how Spring AI resolves `@Tool` on a bean instance. Confirm before D-10 wiring.
- `ChatClient.Builder.tools(...)` vs `.defaultTools(...)` — the difference enforced by D-10.
- Context7 `/spring-ai/spring-ai` (or best-match ID via `mcp__context7__resolve-library-id`) — fetch current docs for tool-callback APIs in 1.1.4.

### User-referenced guidance (this discussion)
- User's Phase 3 framing (2026-04-19): "Before building custom infrastructure … first verify whether Jmix already provides the capability and reuse it … The goal of Phase 3 should not be to recreate Jmix's metadata, security, or query engine, but to add only the thin LLM-facing adapter layer that Jmix does not already provide." — This is the overriding design principle for every Phase 3 decision.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java` — Phase 2 interface; Phase 3 executor composes these with built-ins per request (D-10).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` + `DefaultChatServiceImpl.java` — Phase 1 stub; Phase 3 does NOT modify the interface, but the Phase 4 `ChatClientFactory` (future) will call into Phase 3's `ToolCallbackProvider`. Phase 3 tools must be invokable from a future Phase 4 code path without API churn.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — `@JmixModule` config entry point; Phase 3 adds scanner/executor/tools beans here (or in a new `@Configuration` included by this one).
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — starter auto-config; scanner + tools bean auto-registered alongside SPI defaults from Phase 2's `SpiDefaultsAutoConfiguration`.

### Established Patterns
- **Namespace:** `com.vn.agent.*`. New Phase 3 packages (planner picks): `com.vn.agent.tools`, `com.vn.agent.metadata`, `com.vn.agent.filter`.
- **Jmix projectId:** `'AI'`. Unchanged.
- **Test stack:** JUnit 5 + `@SpringBootTest` + Mockito + HSQLDB. `@Tag("live")` excluded from default `./gradlew test`.
- **Messages:** `messages.properties` (EN) + `messages_vi.properties` (VI). Phase 3 may add tool-description message keys if the descriptions need localization (planner decides; Spring AI `@Tool` description is typically English-only — likely not localized).
- **No-op SPI defaults** from Phase 2 (`SpiDefaultsAutoConfiguration`) — `ToolContributor` default returns empty list. Phase 3's executor must treat "empty list" as valid (use only built-ins in that case).

### Integration Points
- `jmix-app` — success criterion #3: integration test calling `ChatService.ask(...)` with a mock `ChatModel` returning a `find_records` tool-call for `Order`, asserting the real `DataManager` answer flows back correctly AND denied attributes are absent for a restricted user.
- `jmix-app` — D-15 sample `ToolContributor` lives in the host, not the add-on. Requires an `@Component` in `jmix-app` and a companion integration test proving it composes with built-ins in the per-request tool set.
- `AccessManager` — per-request effective schema computer (TOOL-02) is the single point where Jmix security is read into the LLM-visible surface. One computation per request, NOT cached across requests (per `TOOL-02` wording: "never cached per-app").
- `Metadata` — scanner's only input at startup. Cache = `Map<String, MetaClass>` keyed by entity name (or FQCN; planner's discretion per D-11).

</code_context>

<specifics>
## Specific Ideas

- **Overriding principle for this phase:** reuse Jmix primitives. Before adding any custom class, verify Jmix (`Metadata`, `AccessManager`, `DataManager`, `Condition`) does not already provide the capability. The acceptable delta is strictly the Spring AI tool-calling adapter surface — nothing else. (Memory: `feedback_ai_as_jmix_client.md`, `feedback_spi_baseline_builtin.md`, `feedback_pragmatic_modules.md`.)
- **`<data>` wrapping scope = user-editable strings only** (not every string). Scanner computes the set once at startup; formatter reads it cheap. Covers the injection-harness success criterion #5 without wrapping every `id` / `version` / framework-managed column.
- **Filter DSL = Jmix `Op` enum 1:1.** Do not invent an abstraction over Jmix's operator set; the LLM-facing shape just wraps the enum name.
- **Truncation hint points to `count_records`.** No implicit extra `getCount` call — the LLM opts in for the exact total when it actually needs one.
- **`ToolContributor` sample is a real host tool in `jmix-app`.** Demonstrates the actual extension pattern; avoids the "toy-only" smell and exercises the per-request tool-list assembly.
- **`TOOL-08` enforced by reflection unit test on one class.** Reflects D-09 (no ArchUnit) and the "AI is just another Jmix client" framing — read-only posture is a property of the built-in tools class, not a whole-module architectural rule.

</specifics>

<deferred>
## Deferred Ideas

- **LLM-supplied attribute projection list** for `get_record` (D-12 alternative) — adds a flexible `attributes` tool param. Revisit in a later phase if token costs on entities with wide `_local` plans become a problem.
- **Pre-computed `totalCount` in `find_records` response** (D-14 alternative) — trades one extra `getCount()` per call for a more informative truncation signal. Revisit if the LLM often guesses wrong filter widths and burns tokens on re-tries.
- **OR-unions across multiple `find_records` calls** as an alternative to nested DSL (D-06 alternative) — stay with explicit nesting; revisit only if small LLMs misuse `or` nodes.
- **`ToolGuard` wiring before each tool execution** — Phase 6 (`GUARD-01`). Phase 3 does not call `ToolGuard` even though the SPI interface exists.
- **Audit DTO emission from tool bodies** — Phase 4 owns the audit pipeline (`AuditAdvisor`). Phase 3 tools return clean results; audit instrumentation is injected in P4.
- **Mutation tools (create/update/delete)** — v2. `MutationTool` SPI scaffolding deferred until after v1 ships.
- **Auto-ingesting host entity records into the vector store** — out of scope per PROJECT.md; `DataManager` is the source of truth for structured data.
- **ArchUnit rules** — dropped per D-09; revisit only if the rule set grows or drift appears.

</deferred>

---

*Phase: 03-metadata-first-runtime-six-tools*
*Context gathered: 2026-04-19*
