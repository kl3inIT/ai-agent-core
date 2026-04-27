# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening — Context

**Gathered:** 2026-04-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Make the system prompt the LLM sees richer, deterministic, and leakage-resistant — without
adding entities, mutation chain SPIs, or any behavioral change to data-access policy. Pure
prompt-contract + tool-surface refinement, plus one new SPI (`ToolFetchPlanCustomizer`) and
plumbing-only AUD-07 utilities for Phase 11 to consume.

**In scope:**
- Inject `agent.entities` + `agent.permissions` blocks into `BaselineContextProvider.compose(...)`.
- Expand `describe_entity` payload with `MetadataTools`-derived fields (`@Comment`,
  `attributeType`, `cardinality`, `mandatory`, `readOnly`, `persistent`, `transient`,
  `isPrimaryKey`, `enumValues`, `relationshipTarget`, `maxLength`).
- `OutputScannerAdvisor` pattern additions for host-prefix and tool-name leakage
  (flag-and-audit, no hard block).
- `unknown_entity` retry contract enforced via `ToolErrorDto.expected` + system prompt rule.
- `ToolFetchPlanCustomizer` SPI (SPI-09) with per-attribute permission intersection.
- `ToolResultFormatter` `<data entity="<label>" type="<internalName>">` shape (label first;
  PROMPT-04).
- TEST-08 prompt-contract regression suite (mock + opt-in live).
- AUD-07 plumbing prepared: `AuditFieldHasher` utility + properties registered (no caller
  wired yet — Phase 11 wires the call sites).

**Out of scope (explicit):**
- Any new Jmix entity (`AiExposureRule`, `AiUiSettings`, `AiTaskFile`, `AiExtractionDraft`,
  `AiMutationIntent` — all later phases).
- `LlmExposurePolicy` substitution layer — Phase 10.
- `BuiltInMutationTools` and any mutation chain SPI registration — Phase 11.
- Behavioral change to existing `CurrentUserSchemaAccess` / `AccessManager` resolution —
  Phase 9 only adds new readers on top.
- ChatPanelFragment surfaces / floating launcher — Phase 12.
- STT, task-scoped files — Phase 13.
- Intent-driven extraction — Phase 14.

</domain>

<decisions>
## Implementation Decisions

### Prompt rendering & schema shape (PROMPT-01, PROMPT-02, TOOL-09, TOOL-12)

- **D-01:** `agent.entities` renders as `name (label)` per line, alphabetical by `name`,
  deterministic, single multi-line value under one prompt key. Empty schema (anonymous user /
  no readable entities) → key omitted entirely.

- **D-02:** `agent.permissions` renders as a compact JSON object under a single prompt key:
  `{"<entity>":{"r":1,"u":1,"c":0,"d":0,"modifiable":["attr1","attr2"]}, ...}`. Deterministic
  ordering (alpha by entity, alpha by attribute inside `modifiable`). Entries where ALL CRUD
  bits are 0 are omitted. Per Jmix Phase 9 v1.1 baseline `read=1` is implied for any entity
  appearing in `agent.entities`, so the `r` bit will be 1 for every emitted entry; including
  it explicitly keeps the shape stable when Phase 10 introduces denylisting that decouples
  read from inclusion. Locale-sensitive labels are NOT in any cache key (P-8 mitigation per
  PROMPT-02 explicit wording).

- **D-03:** `agent.entities` truncation default is **100** entities (symmetry with
  `TOOL-06` find_records `max=100` cap), configurable via property (planner picks key under
  `ai-agent.prompt.entity-inventory.*` namespace). Past threshold, render top-100 alpha +
  trailing line `... (truncated, call list_entities for full list)`.

- **D-04:** Richer `describe_entity` payload renders:
  - `cardinality` as **raw Jmix enum string** (`ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_ONE`,
    `MANY_TO_MANY`, `NONE`). Stable, deterministic, easy for the LLM to map.
  - `enumValues` as `[{name, label}, ...]` with **locale-resolved labels** via
    `MessageTools` so the LLM can translate enum constants into user-facing replies.
  - `relationshipTarget` as `{name, label}` mirroring the `agent.entities` shape.
  - `@Comment` rendered raw (NO `msg://` resolution — Jmix convention is plain text per
    `feedback_jmix_messages_over_spring`).
  - All fields read via `MetadataTools.getMetaAnnotationValue(..., Comment.class)` and
    `MetadataTools` accessors — no raw reflection (Phase 3 framing).

- **D-05:** `describe_entity` excluded fields are documented in **Javadoc on
  `BuiltInDataTools.describeEntity` only** — NOT echoed into the LLM-facing payload. Excluded
  set includes (planner enumerates the final list during research): DDL column names, JPA
  fetch type, cascade rules, raw annotations, internal store name, framework-managed
  audit columns. Reviewers see the rationale; LLM doesn't pay tokens.

### OutputScanner pattern derivation (PROMPT-06)

- **D-06:** Host-prefix leakage pattern is **derived dynamically at startup** from
  `metadata.getSession().getClasses()`. Extract distinct prefix tokens before the first `_`
  (e.g. `jmixapp_Customer` → prefix `jmixapp`); compile a single regex over the union (e.g.
  `\b(jmixapp|otherprefix)_\w+\b`). Adapts per host with zero config; aligns with Phase 3
  D-11 entity-resolution discipline. Refresh on `MetadataChangedEvent` if Jmix emits one;
  otherwise refresh requires app restart (acceptable — metaclasses do not change at runtime
  in practice).

- **D-07:** Tool-name leakage list is the **union of**:
  - `BuiltInDataTools` six tool names: `list_entities`, `describe_entity`, `find_records`,
    `get_record`, `count_records`, `get_related_records`.
  - `RETRIEVAL` advisor name (Spring AI's RAG advisor).
  - Every `ToolCallback.getName()` returned by registered `ToolContributor` beans at
    startup (collected from `AgentToolCallbacks` so host-contributed tools are also
    leakage-shielded).

- **D-08:** New scanner patterns ship **enabled-by-default** in the starter
  auto-config. Operator opts OUT via `ai-agent.guard.scanner.host-prefix-leak.enabled=false`
  / `ai-agent.guard.scanner.tool-name-leak.enabled=false`. Posture remains flag-and-audit
  (no hard block) per PROMPT-06 explicit wording.

### `ToolFetchPlanCustomizer` SPI surface (TOOL-10, TOOL-11, SPI-09)

- **D-09:** SPI signature locked per REQ:
  `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx)`.
  Default no-op bean returns `Optional.empty()` — current `_base` data plan and
  `_instance_name` label projection behavior preserved when no host override.

- **D-10:** `FetchPlanContext` payload is **minimal**:
  `record FetchPlanContext(RunContext run, UserDetails user)`. `RunContext` already carries
  conversationId / profile / locale (Phase 4 contract). Hosts vary plans by
  conversation/profile without dragging unrelated state. Matches MEMORY rule "SPIs only for
  app-specific behavior — keep the surface narrow."

- **D-11:** Per-attribute intersection is **build-time prune**: after the host returns a
  `FetchPlan`, walk its properties recursively against
  `CurrentUserSchemaAccess.getReadableSchema()` for the current user; drop any property
  (and dependent sub-plans) that the user cannot read. The `DataManager` load only ever
  sees the permission-narrowed plan. Code comment **must state**: "fetch plan is
  projection, not security" (TOOL-11 explicit wording).

- **D-12:** Failure mode when the host plan references denied attributes is **silent drop +
  audit-log**. Pruner emits an audit row via the existing `AuditWriter`
  (`outcome=PLAN_NARROWED`, `details={tool, entity, droppedAttrs:[...]}`). LLM never sees
  an error; matches v1 "access denied = entity does not exist" opacity (Phase 3 D-08, P-13
  consistency). No new `AuditKind` per ROADMAP commitment.

- **D-13:** SPI overrides the **data fetch plan only**. The add-on default data plan stays
  `_base`. `_instance_name` is a separate label-projection concern and is **NOT** exposed
  through this SPI in Phase 9. Hosts needing different labels model that via Jmix
  `@InstanceName` / instance-name configuration, not through any AI-agent SPI. (User
  wording clarification 2026-04-27: data fetch plan default is `_base`, not
  `_instance_name`.)

### `unknown_entity` retry contract (PROMPT-05)

- **D-14:** `ToolErrorDto.expected` shape stays `List<String>` (no DTO churn — Phase 3
  D-07 contract preserved). For `unknown_entity` specifically, populate exactly three
  procedural hints in this order:
  1. `"call list_entities exactly once"`
  2. `"if a name in list_entities matches your intent, retry the original tool with that exact name"`
  3. `"if no entity in list_entities matches, tell the user no such entity exists — do not guess"`

  The exact-once + no-guess wording is PROMPT-05's whole point and survives translation into
  the procedural shape. Locks the TEST-08 regression bar — the hint strings are part of the
  deterministic tool output, asserted directly.

- **D-15:** Retry rule lives in **both** places:
  - `ToolErrorDto.expected` (per-tool-call reminder).
  - System prompt rule in `DefaultChatServiceImpl` (global behavior contract that applies
    even before any tool fires — covers the very first turn).
  - Test asserts both: rule string present in composed system prompt AND `expected` payload
    appears in structured tool output for the `unknown_entity` case.

### TEST-08 harness + AUD-07 plumbing scope

- **D-16:** TEST-08 prompt-contract test is split:
  - **Default CI:** `@SpringBootTest` injects a mock `ChatModel` returning two scripted
    leaky replies (one with internal entity name like `jmixapp_Customer`, one with literal
    tool name `find_records`). Asserts `OutputScannerAdvisor` flags + audits both. Asserts
    final user-facing reply (via `ChatService`) does NOT contain the patterns when the
    advisor is configured per default. Deterministic, free, runs in default `./gradlew test`.
  - **Opt-in live:** `@Tag("live")` separate test runs the same chat turn against the
    configured real model; asserts the actual reply doesn't contain the patterns. Excluded
    from default CI per existing v1.0 convention.

- **D-17:** Locale parameterization uses JUnit5 `@ParameterizedTest` over
  `Locale.of("vi","VN")` and `Locale.ENGLISH` — single test method, two assertion runs.
  Test sets `CurrentAuthentication.locale` via test harness before each iteration.

- **D-18:** AUD-07 Phase 9 plumbing scope:
  - Ship: `com.vn.agent.audit.AuditFieldHasher` static utility (SHA-256 over UTF-8
    bytes, hex-string output). Stateless, no SPI yet.
  - Ship: `ai-agent.audit.hashSensitiveFields` `@ConfigurationProperty` registered with
    default `true`.
  - Ship: `ai-agent.audit.sensitive-fields` field-set property with empty default.
  - **Do NOT** wire any caller in Phase 9 — there is no mutation surface to consume the
    hasher yet. Phase 11 (`MutationErrorTranslator` / pre-post-image diff) wires the call
    site. Phase 9 unit-tests the hasher in isolation.
  - SPI extraction is deferred until P11+ produces a concrete host use case for non-SHA-256
    hashing (MEMORY rule "SPIs only for app-specific behavior").

### Claude's Discretion

- Exact configuration property keys (within the namespaces above) — planner picks consistent
  names under `ai-agent.prompt.*`, `ai-agent.guard.scanner.*`, `ai-agent.audit.*`.
- Whether the dynamic host-prefix scan caches the compiled regex on a `@Component` field at
  `ApplicationReadyEvent` time vs lazily on first scan call — planner picks (both satisfy
  D-06).
- The `@Component` vs `@Configuration`-bean wiring of `BaselineContextProvider` extension
  for `agent.entities` / `agent.permissions` (extend existing `BaselineContextProvider`
  vs. new `SchemaInventoryContributor` consumed by it) — planner picks; existing
  `BaselineContextProvider` is the natural home given its `compose(...)` shape.
- Internal record / DTO shape inside the prompt-rendering layer (e.g.
  `EntityInventoryEntry`, `EntityPermissionEntry`) — planner picks.
- Whether the `_base` → permission-narrowed plan transformation is a public method on
  `ToolFetchPlanCustomizer` chain output or hidden inside an internal helper (e.g.
  `FetchPlanIntersector`) — planner picks; the public SPI method signature stays
  `Optional<FetchPlan> overrideFor(...)`.
- Bean discovery model for `ToolFetchPlanCustomizer` — single bean (highlander) vs. ordered
  list with first-non-empty-wins resolution. Planner picks; ordered list mirrors
  `ToolContributor` and tends to age better.
- Exact field-set default for `ai-agent.audit.sensitive-fields` (empty for v1.1, populated
  in v1.2 once Phase 11 telemetry shows what gets logged) — planner picks.
- Test class organization for TEST-08 — single `PromptContractTest` parameterized by locale
  vs. split `PromptContractMockTest` + `PromptContractLiveTest` (`@Tag("live")`) — planner
  picks per existing test-suite conventions.

### Folded Todos

All six v1.1-mapped pending todos under `.planning/todos/pending/` are folded into Phase 9
scope (consistent with STATE.md Pending Todos table):

- `2026-04-26-inject-readable-entity-inventory-into-baseline-context.md` — PROMPT-01,
  shaped by D-01, D-03.
- `2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md` — PROMPT-03,
  PROMPT-04, PROMPT-06, shaped by D-06–D-08, D-16.
- `2026-04-24-enforce-unknown-entity-retry-contract.md` — PROMPT-05, shaped by D-14, D-15.
- `2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md` — TOOL-09,
  shaped by D-04, D-05.
- `2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md` — TOOL-10, TOOL-11,
  SPI-09, shaped by D-09–D-13.
- `2026-04-24-add-llm-permission-inventory.md` — TOOL-12, PROMPT-02, shaped by D-02.

The v1.2-deferred todo (`add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md`)
stays out per ROADMAP / PROJECT.md.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening" —
  goal, success criteria #1..#5, requirements list.
- `.planning/REQUIREMENTS.md` — `PROMPT-01..06`, `TOOL-09..12`, `SPI-09`, `TEST-08`,
  `AUD-07` (partial). Authoritative for scope.
- `.planning/PROJECT.md` §"Current Milestone v1.1.0" — value prop and explicit
  in/out-of-scope statements for v1.1.
- `.planning/STATE.md` — Pending Todos disposition table; six folded into Phase 9.

### Prior phase context (load before planning)
- `.planning/milestones/v1.0.0-phases/02-foundations/02-CONTEXT.md` — D-09 (no ArchUnit),
  D-10 (no AI-specific exposure layer in v1; defer until concrete consumer use case).
- `.planning/milestones/v1.0.0-phases/03-metadata-first-runtime-six-tools/03-CONTEXT.md` —
  D-01..D-16 are the authoritative tool-surface contract. Phase 9 EXTENDS, does not
  refactor: D-07 (`ToolErrorDto`), D-08 (filter depth + denied-attr fail-closed), D-12
  (`_instance_name` label projection convention), D-13 (`<data>` wrapping), D-14
  (truncation UX + `ToolLimits`).
- `.planning/milestones/v1.0.0-phases/01-walking-skeleton/01-CONTEXT.md` §D-03 —
  `ChatService` signature stable; Phase 9 does NOT modify the public interface.

### Pending todos folded into Phase 9 (read before planning)
- `.planning/todos/pending/2026-04-26-inject-readable-entity-inventory-into-baseline-context.md`
- `.planning/todos/pending/2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md`
- `.planning/todos/pending/2026-04-24-enforce-unknown-entity-retry-contract.md`
- `.planning/todos/pending/2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md`
- `.planning/todos/pending/2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md`
- `.planning/todos/pending/2026-04-24-add-llm-permission-inventory.md`

### Project conventions
- `CLAUDE.md` — Jmix conventions, especially: `DataManager` only, no `EntityManager`,
  `Metadata.create()` / `DataManager.create()`, `msg://` keys for any user-facing text,
  JetBrains MCP `get_file_problems` workflow.
- `.planning/research/STACK.md` (or v1.0.0 archive) — Spring AI 1.1.4 pin, Jmix 2.8.

### Add-on source touch points
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` —
  PROMPT-01 + PROMPT-02 extension site (`compose(...)` map and `renderAsText(...)` text).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` —
  source of truth for readable schema; D-11 prune walks it.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` —
  `describe_entity` payload extension (TOOL-09, D-04, D-05).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java` —
  `expected` field populated per D-14.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java` —
  PROMPT-04 `<data entity="<label>" type="<internalName>">` shape; row-level
  `_instance_name` placement.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` — source
  of `ToolContributor.getName()` set for D-07 scanner list.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java` +
  `CompiledOutputScannerPattern.java` + `AiAgentGuardProperties.java` — PROMPT-06
  pattern additions; D-06–D-08.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — system
  prompt rule wiring for PROMPT-03, PROMPT-05 (D-15).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/` — new `ToolFetchPlanCustomizer.java`
  interface + `FetchPlanContext.java` record (SPI-09, D-09–D-13).
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` —
  register any new auto-config (no-op `ToolFetchPlanCustomizer` default, scanner pattern
  pack default-on).
- `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml` — new property
  defaults (entity inventory threshold, scanner toggles, audit hashing).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` +
  `messages_vi.properties` — any new user-facing strings (per CLAUDE.md ALL locales rule).

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — `MetaClass` / `MetaProperty` / `MetadataTools` API for D-04, D-06.
- `jmix-services` — `DataManager` fluent API for fetch-plan-narrowed loads (D-11).
- `jmix-fetch-plans` — `FetchPlan` builders, `_base` / `_instance_name` semantics for
  D-09–D-13.
- `jmix-security-roles` — `AccessManager`, `EntityAttributeContext`, `CrudEntityContext`
  for the per-attribute intersection in D-11 and `agent.permissions` in D-02.
- `jmix-i18n` — `MessageTools.getEntityCaption`, locale-aware label resolution for
  `agent.entities` (D-01) and `enumValues` labels (D-04).
- `jmix-testing` — `@SpringBootTest` + `@Tag("live")` opt-out wiring for TEST-08.

### Spring AI primitives to verify in research
- `OutputScannerAdvisor` extension — how Spring AI advisor chain consumes additional
  patterns (existing P6 work + new D-06–D-08 patterns must layer cleanly).
- Context7 `/spring-ai/spring-ai` — confirm 1.1.4 advisor / `ChatClient` extension
  surface before planner finalizes wiring.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BaselineContextProvider.compose(UUID conversationId)` (line 46) — extension site for
  PROMPT-01/PROMPT-02. Already injects `agent.userId` / `username` / `roles` / `locale` /
  `conversationId`; D-01/D-02 add two more keys under the same map. Extends
  `renderAsText(...)` (line 64) for prompt composition. Stateless `@Component` per
  D-15 of Phase 3 — no caching; rebuilt per request.
- `CurrentUserSchemaAccess.getReadableSchema()` (line 44) — single source of truth for
  D-01, D-02, D-11. Returns `Map<MetaClass, Set<String>>`. Same `AccessManager`-filtered
  view used by `BuiltInDataTools.listEntities()` — guarantees baseline ↔ tool surface
  parity.
- `CurrentUserSchemaAccess.canReadAttribute(MetaClass, attrPath)` (line 64) — used by
  filter depth-cap walk; D-11 prune walks the same path-permission contract.
- `CurrentUserSchemaAccess.canReadEntity(MetaClass)` (line 75) — gating for
  `agent.entities` set membership.
- `BuiltInDataTools.describeEntity` (existing) — extension site for TOOL-09 / D-04. Field
  set today is the Phase 3 D-02 minimum; D-04 widens it via `MetadataTools`.
- `ToolErrorDto` (record, 3 fields) — `expected: List<String>` already in shape; D-14
  reuses without DTO churn.
- `ToolResultFormatter` — PROMPT-04 `<data entity="<label>" type="<internalName>">` shape
  change site.
- `OutputScannerAdvisor` + `CompiledOutputScannerPattern` + `AiAgentGuardProperties` —
  existing scanner advisor; D-06–D-08 add patterns and starter wiring without disturbing
  the existing `OutputScannerAdvisorTest` corpus.
- `AgentToolCallbacks` (per-request tool-list assembly) — source of `ToolContributor`
  bean names for D-07.
- `RunContext` (Phase 4) — already carries conversationId / profile / locale; consumed
  by `FetchPlanContext` per D-10.
- `AuditWriter` (Phase 7.2 tree-lite) — D-12 emits `outcome=PLAN_NARROWED` audit rows
  through it. AUD-07 Phase 11 wiring will reuse the same writer for mutation diffs (per
  ROADMAP commitment "no new AuditKind").

### Established Patterns
- **Namespace:** `com.vn.agent.*`. Phase 9 may add: `com.vn.agent.audit.AuditFieldHasher`
  (utility), `com.vn.agent.spi.ToolFetchPlanCustomizer` + `com.vn.agent.spi.FetchPlanContext`
  (SPI), an internal helper for fetch-plan intersection (planner picks namespace).
- **Configuration:** `@ConfigurationProperties` records under `ai-agent.*` namespace
  (existing pattern from `AiAgentGuardProperties`, `AiAgentDefaultsProperties`,
  `AiAgentChatProperties`).
- **SPI defaults:** No-op default beans live in `SpiDefaultsAutoConfiguration` (Phase 2);
  `ToolFetchPlanCustomizer` default no-op bean registered there.
- **Locale:** `MessageTools` is locale-aware via `CurrentAuthentication` (Phase 3 D-04);
  `agent.entities` labels and `describe_entity.enumValues[].label` use it. Locale-sensitive
  values are NEVER part of any cache key (P-8 mitigation explicit in PROMPT-02).
- **Audit:** `AuditWriter.writeToolCall` (existing) is the only audit emission API; D-12
  reuses with new `outcome=PLAN_NARROWED` enum value (planner verifies enum is open vs.
  needs widening).
- **Test stack:** JUnit 5 + `@SpringBootTest` + Mockito; `@Tag("live")` excluded from
  default `./gradlew test` (existing v1.0 convention).

### Integration Points
- `BaselineContextProvider` is on the hot path for every chat turn — D-01/D-02 cost is
  one extra `getReadableSchema()` call per request, marginal compared to the LLM round
  trip. Same call the `list_entities` tool would make on the happy path the inventory
  prevents.
- `OutputScannerAdvisor` runs in the advisor chain on the response side; D-06–D-08
  pattern additions layer onto the existing `CompiledOutputScannerPattern` mechanism
  with no chain reorder.
- `ToolFetchPlanCustomizer` resolution happens inside `BuiltInDataTools` data-load paths
  (`find_records`, `get_record`, `get_related_records`) BEFORE `DataManager.load(...)`
  fires; D-11 intersection prune is the last transform before the load. Per-request,
  not cached across requests (matches `CurrentUserSchemaAccess` no-cache rule).
- `AgentToolCallbacks` startup snapshot — D-07 reads it once at `ApplicationReadyEvent`
  to seed the tool-name leakage list. Host modules added at runtime (rare in Jmix) are
  out of scope.
- `agentstore` separate datasource (Phase 7.2) — Phase 9 reads NO entities and writes
  NO new tables; AUD-07 Phase 9 plumbing is utility-only. Phase 11 will extend the
  audit table for new outcomes if needed.

</code_context>

<specifics>
## Specific Ideas

- **Reuse Jmix primitives, do not parallel-build.** Every new field in `describe_entity`
  must come from `MetadataTools` (`@Comment`, `attributeType`, `cardinality`, etc.) — never
  raw reflection (Phase 3 framing, MEMORY `feedback_reuse_jmix_builtins`).
- **Determinism is the contract.** `agent.entities` and `agent.permissions` shapes are
  asserted byte-for-byte in tests; alpha ordering, fixed JSON key order, fixed bit names
  (`r`, `u`, `c`, `d`) — locks the prompt-hash for cache stability and audit
  reproducibility.
- **The `unknown_entity` retry hint is procedural English** in `expected[]`, not a
  structured action object — avoids LLM fixating on one shape and dropping the negative
  path. Three explicit hints in fixed order: call once, retry on match, give up on no
  match (no guessing).
- **`fetch plan is projection, not security`** — code comment is mandatory in the
  intersection-prune helper (TOOL-11 explicit wording).
- **AUD-07 Phase 9 is plumbing-only.** No mutation surface to call the hasher; ship the
  utility + properties so Phase 11 wiring is a one-liner. SPI extraction deferred to
  whenever a concrete host case for non-SHA-256 hashing surfaces (MEMORY rule).
- **Default-on safety patterns, opt-out via property** (D-08) — matches v1.0
  `OutputScannerAdvisor` posture; flag-and-audit means the worst case for a host with
  unusual naming is audit noise, not blocked answers.
- **Six folded todos = the bulk of Phase 9 scope.** Planner reads each one before
  decomposing into plans; the todos contain concrete code references (file paths, line
  numbers) that anchor the implementation.

</specifics>

<deferred>
## Deferred Ideas

- **`LlmExposurePolicy` substitution layer** — Phase 10. Phase 9's `agent.entities` /
  `agent.permissions` source `CurrentUserSchemaAccess` directly so Phase 10 can substitute
  the source class without churning call sites (per ROADMAP Phase 10 Depends-on note).
- **Mutation-tool surface (`BuiltInMutationTools`, `MutationGuard` SPI,
  `AiMutationIntent`)** — Phase 11. AUD-07 Phase 9 plumbing is the only Phase 9 nod to
  Phase 11.
- **`AuditFieldHasher` SPI extension** — deferred until a host requests non-SHA-256
  hashing. MEMORY rule.
- **`TranscriptionPostProcessor` and STT plumbing** — Phase 13.
- **`IntentExtractor<T>` and `prepare_form_draft` tool** — Phase 14.
- **Configurable chat surfaces (`ChatPanelFragment` modes, floating launcher)** — Phase 12.
- **Collapsible per-turn tool-detail panel + ephemeral streaming-status indicator** —
  v1.2 (per PROJECT.md, ROADMAP, STATE.md).
- **`MetadataChangedEvent` regex refresh handler for D-06** — only wire if Jmix emits
  such an event in 2.8; otherwise restart-only refresh is acceptable (metaclasses do not
  change at runtime in practice).
- **Token-budget-aware truncation for `agent.entities`** (alternative to D-03's entity-count
  threshold) — defer; revisit if hosts in the wild hit the 100-entity cap and report the
  cutoff is wrong on their entity-name length distribution.
- **Structured `expectedAction` field on `ToolErrorDto`** — defer (D-14 alternative);
  revisit only if observability shows the procedural string hints are dropped by smaller
  LLMs.

</deferred>

---

*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Context gathered: 2026-04-27*
