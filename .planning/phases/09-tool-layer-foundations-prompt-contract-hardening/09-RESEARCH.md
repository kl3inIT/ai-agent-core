# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening — Research

**Researched:** 2026-04-27
**Domain:** Jmix 2.8.1 metamodel + Spring AI 1.1.4 advisor / tool-callback layer
**Confidence:** HIGH (most claims verified via Context7 and direct read of v1.0 source)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Prompt rendering & schema shape (PROMPT-01, PROMPT-02, TOOL-09, TOOL-12)

- **D-01:** `agent.entities` renders as `name (label)` per line, alphabetical by `name`,
  deterministic, single multi-line value under one prompt key. Empty schema (anonymous user /
  no readable entities) → key omitted entirely.

- **D-02:** `agent.permissions` renders as a compact JSON object under a single prompt key:
  `{"<entity>":{"r":1,"u":1,"c":0,"d":0,"modifiable":["attr1","attr2"]}, ...}`. Deterministic
  ordering (alpha by entity, alpha by attribute inside `modifiable`). Entries where ALL CRUD
  bits are 0 are omitted. Per Jmix Phase 9 v1.1 baseline `read=1` is implied for any entity
  appearing in `agent.entities`, so the `r` bit will be 1 for every emitted entry. Locale-
  sensitive labels are NOT in any cache key (P-8 mitigation per PROMPT-02 explicit wording).

- **D-03:** `agent.entities` truncation default is **100** entities, configurable via
  property under `ai-agent.prompt.entity-inventory.*` namespace. Past threshold, render top-100
  alpha + trailing line `... (truncated, call list_entities for full list)`.

- **D-04:** Richer `describe_entity` payload renders:
  - `cardinality` as **raw Jmix enum string** (`ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_ONE`,
    `MANY_TO_MANY`, `NONE`).
  - `enumValues` as `[{name, label}, ...]` with **locale-resolved labels** via
    `Messages.getMessage(EnumValue)`.
  - `relationshipTarget` as `{name, label}` mirroring the `agent.entities` shape.
  - `@Comment` rendered raw (NO `msg://` resolution — Jmix convention is plain text).
  - All fields read via `MetadataTools.getMetaAnnotationValue(..., Comment.class)` and
    `MetadataTools` accessors — no raw reflection.

- **D-05:** `describe_entity` excluded fields are documented in **Javadoc on
  `BuiltInDataTools.describeEntity` only** — NOT echoed into the LLM-facing payload.

#### OutputScanner pattern derivation (PROMPT-06)

- **D-06:** Host-prefix leakage pattern is **derived dynamically at startup** from
  `metadata.getSession().getClasses()`. Extract distinct prefix tokens before the first `_`;
  compile a single regex over the union (e.g. `\b(jmixapp|otherprefix)_\w+\b`).

- **D-07:** Tool-name leakage list is the **union of**: built-ins six tool names,
  `RETRIEVAL` advisor name, every `ToolCallback.getName()` returned by registered
  `ToolContributor` beans at startup.

- **D-08:** New scanner patterns ship **enabled-by-default** in the starter auto-config.
  Operator opts OUT via `ai-agent.guard.scanner.host-prefix-leak.enabled=false` /
  `ai-agent.guard.scanner.tool-name-leak.enabled=false`. Posture remains flag-and-audit.

#### `ToolFetchPlanCustomizer` SPI surface (TOOL-10, TOOL-11, SPI-09)

- **D-09:** SPI signature locked:
  `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx)`.
  Default no-op bean returns `Optional.empty()`.

- **D-10:** `FetchPlanContext` payload is minimal:
  `record FetchPlanContext(RunContext run, UserDetails user)`.

- **D-11:** Per-attribute intersection is **build-time prune**: walk host plan recursively
  against `CurrentUserSchemaAccess.getReadableSchema()`; drop any property the user cannot
  read. Code comment must state: "fetch plan is projection, not security."

- **D-12:** Failure mode when host plan references denied attributes is **silent drop +
  audit-log** (`outcome=PLAN_NARROWED`, details={tool, entity, droppedAttrs:[...]}).

- **D-13:** SPI overrides the **data fetch plan only**. The add-on default data plan stays
  `_base`. `_instance_name` is NOT exposed through this SPI in Phase 9.

#### `unknown_entity` retry contract (PROMPT-05)

- **D-14:** `ToolErrorDto.expected` shape stays `List<String>`. For `unknown_entity`,
  populate exactly three procedural hints in this order:
  1. `"call list_entities exactly once"`
  2. `"if a name in list_entities matches your intent, retry the original tool with that exact name"`
  3. `"if no entity in list_entities matches, tell the user no such entity exists — do not guess"`

- **D-15:** Retry rule lives in **both** `ToolErrorDto.expected` AND a system prompt rule
  in `DefaultChatServiceImpl`. Test asserts both.

#### TEST-08 harness + AUD-07 plumbing scope

- **D-16:** TEST-08 prompt-contract test is split:
  - **Default CI:** `@SpringBootTest` injects a mock `ChatModel` returning two scripted
    leaky replies; asserts `OutputScannerAdvisor` flags + audits both. Final user-facing
    reply does NOT contain the patterns.
  - **Opt-in live:** `@Tag("live")` separate test runs the same chat turn against the
    configured real model.

- **D-17:** Locale parameterization uses JUnit5 `@ParameterizedTest` over
  `Locale.of("vi","VN")` and `Locale.ENGLISH`.

- **D-18:** AUD-07 Phase 9 plumbing:
  - Ship: `com.vn.agent.audit.AuditFieldHasher` static utility (SHA-256 over UTF-8 bytes,
    hex-string output). Stateless, no SPI yet.
  - Ship: `ai-agent.audit.hashSensitiveFields` `@ConfigurationProperty` with default `true`.
  - Ship: `ai-agent.audit.sensitive-fields` field-set property with empty default.
  - **Do NOT** wire any caller in Phase 9. Phase 11 wires the call site.
  - SPI extraction is deferred until Phase 11+.

### Claude's Discretion

- Exact configuration property keys (within the namespaces above) — planner picks consistent
  names under `ai-agent.prompt.*`, `ai-agent.guard.scanner.*`, `ai-agent.audit.*`.
- Whether the dynamic host-prefix scan caches the compiled regex on a `@Component` field at
  `ApplicationReadyEvent` time vs lazily on first scan call — planner picks.
- Whether the `_base` → permission-narrowed plan transformation is a public method on
  `ToolFetchPlanCustomizer` chain output or hidden inside an internal helper (e.g.
  `FetchPlanIntersector`) — planner picks; the public SPI method signature stays
  `Optional<FetchPlan> overrideFor(...)`.
- Bean discovery model for `ToolFetchPlanCustomizer` — single bean (highlander) vs. ordered
  list with first-non-empty-wins resolution.
- Internal record / DTO shape inside the prompt-rendering layer (e.g. `EntityInventoryEntry`,
  `EntityPermissionEntry`).
- Test class organization for TEST-08 — single `PromptContractTest` parameterized by locale
  vs. split `PromptContractMockTest` + `PromptContractLiveTest`.

### Deferred Ideas (OUT OF SCOPE)

- `LlmExposurePolicy` substitution layer — Phase 10.
- Mutation-tool surface (`BuiltInMutationTools`, `MutationGuard` SPI, `AiMutationIntent`) — Phase 11.
- `AuditFieldHasher` SPI extension — until a host requests non-SHA-256 hashing.
- `TranscriptionPostProcessor`, STT plumbing — Phase 13.
- `IntentExtractor<T>` and `prepare_form_draft` tool — Phase 14.
- Configurable chat surfaces — Phase 12.
- Collapsible per-turn tool-detail panel — v1.2.
- `MetadataChangedEvent` regex refresh handler for D-06 — only wire if Jmix 2.8 emits one.
- Token-budget-aware truncation for `agent.entities`.
- Structured `expectedAction` field on `ToolErrorDto`.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PROMPT-01 | `agent.entities` injected into baseline (sorted, `name (label)`, threshold default 50) | Existing `BaselineContextProvider.compose(...)` (line 46) is the extension site; `CurrentUserSchemaAccess.getReadableSchema()` already returns the user-narrowed `Map<MetaClass, Set<String>>`. Threshold raised to 100 per CONTEXT D-03. |
| PROMPT-02 | `agent.permissions` injected (compact map, CRUD bits + `modifiable` set, locale-free key) | `AccessManager.applyRegisteredConstraints(CrudEntityContext)` returns `isReadPermitted/isUpdatePermitted/isCreatePermitted/isDeletePermitted`; `EntityAttributeContext.canModify()` returns the `modifiable` set per attribute. Verified live against `CurrentUserSchemaAccess.canReadAttribute(...)` pattern at line 64. |
| PROMPT-03 | System prompt forbids host-prefix entity names + tool names in user-facing replies | `DefaultChatServiceImpl` line 199-207 composes `composedSystemPrompt = baselineText + profileSystemPrompt`. New rule string is appended at this site (or via the existing `effectiveSystemPrompt(...)` resolver). |
| PROMPT-04 | `ToolResultFormatter.records(...)` wraps as `<data entity="<label>" type="<internalName>">` | `ToolResultFormatter.records(...)` at line 117 currently builds `RecordsResult(metaClass.getName(), ...)`. PROMPT-04 reshapes the wrapper element only — payload internals unchanged. `_instance_name` row-level placement uses existing `MetadataTools.getInstanceName(...)` (already used at line 217). |
| PROMPT-05 | `unknown_entity` retry contract via `ToolErrorDto.expected` + system prompt rule | `ToolUserError` at line 264 of `BuiltInDataTools` already throws `unknown_entity`; `ToolErrorDto.expected: List<String>` already shaped (D-07 of Phase 3). Phase 9 populates the three procedural hints from D-14. |
| PROMPT-06 | `OutputScannerAdvisor` patterns for host-prefix + tool-name leakage | Existing `OutputScannerAdvisor` + `CompiledOutputScannerPattern` + `AiAgentGuardProperties.OutputScanner.Pattern(key,regex)` shape extend cleanly. New patterns are appended to `resolvedPatterns()` default set; default-on. |
| TOOL-09 | `describe_entity` payload extension via `MetadataTools` | All required APIs verified via Context7: `MetadataTools.getMetaAnnotationValue(metaProperty, Comment.class)`, `MetaProperty.getRange().getCardinality()`, `MetaProperty.isMandatory()`, `MetaProperty.isReadOnly()`, `MetadataTools.isPrimaryKey(metaProperty)`, `MetadataTools.isJpa(metaProperty)`, `Range.asEnumeration().getValues()`, `Range.asClass().getName()`, `@Column.length` via `MetaProperty.getAnnotatedElement()`. The `columnLength(...)` helper is already implemented at `ToolResultFormatter` line 99. |
| TOOL-10 | `ToolFetchPlanCustomizer` SPI defined | New SPI under `com.vn.agent.spi/`. Default no-op bean in `SpiDefaultsAutoConfiguration`. |
| TOOL-11 | Override fetch plans intersected with attribute policy | Hand-walk `FetchPlan.getProperties()` against `currentUserSchemaAccess.canReadAttribute(metaClass, propertyName)`. Jmix has no built-in `FetchPlan`-vs-ACL intersector — see Don't Hand-Roll for what we DO use vs. what we own. |
| TOOL-12 | LLM permission inventory at entity granularity in baseline + `describe_entity` per-attribute readability | Combined with PROMPT-02 by D-02; per-attribute layer is the existing `readableAttributeNames` set already returned by `CurrentUserSchemaAccess.getReadableSchema()`. |
| SPI-09 | `ToolFetchPlanCustomizer` SPI | See TOOL-10. Mirrors existing `ToolContributor`/`ToolGuard` shape. |
| TEST-08 | Prompt-contract regression suite | `StubChatModelConfiguration` test_support already in place; `@Tag("live")` excluded by default per `ai-agent.gradle`; JUnit5 `@ParameterizedTest` over `Locale.of("vi","VN")` + `Locale.ENGLISH` per D-17. |
| AUD-07 (partial) | Hash-sensitive-fields plumbing | `AuditFieldHasher` static utility (SHA-256 hex). Two `@ConfigurationProperty` keys registered. No caller wired in Phase 9. |
</phase_requirements>

## Summary

Phase 9 is the foundation phase of v1.1. It is **purely additive on top of shipped v1.0** — no new entities, no new mutation chain SPIs, no behavioral change to data-access policy. Every Jmix and Spring AI primitive needed for the work is already present in the v1.0 codebase or is a documented public API. The CONTEXT.md decisions are exhaustive: most architectural calls are locked. This research's job is to **verify the API surface those decisions assume** and **flag the few real risks** (locale in cache key, fetch-plan widening, raw reflection in describe_entity, output-scanner false positives).

**Primary recommendation:** Extend `BaselineContextProvider`, `BuiltInDataTools.describeEntity`, `ToolResultFormatter`, `AiAgentGuardProperties`, and `OutputScannerAdvisor` in place. Add three new files: `ToolFetchPlanCustomizer` SPI + `FetchPlanContext` record + `AuditFieldHasher` static utility. Add one new internal helper class for fetch-plan-vs-ACL intersection. Total new public types: 3. Total touched files: ~10. No build.gradle change.

Confidence is HIGH because (a) Spring AI 1.1.4 + Jmix 2.8.1 versions are pinned and verified in `ai-agent/build.gradle`, (b) Context7 confirmed every Jmix metadata API the plan needs, (c) the v1.0 codebase already exercises 90% of the surface — the work is shape-extension, not greenfield.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Baseline-prompt assembly (`agent.entities`, `agent.permissions`) | Backend (Spring `@Component`) | — | Per-request, runs in `DefaultChatServiceImpl.ask` before LLM call. Stateless, no UI. |
| `describe_entity` payload composition | Backend tool layer (`@Tool` method on `BuiltInDataTools`) | — | LLM-facing serialization; runs inside Spring AI's tool-call invocation cycle. |
| Output-scanner pattern matching | Backend advisor chain (`CallAdvisor` impl) | — | Runs after model response, before user-facing serialization. Already wired at `HIGHEST_PRECEDENCE + 400`. |
| Fetch-plan customization SPI resolution + ACL intersection | Backend tool layer (per-request, before `DataManager.load`) | Backend security | Customizer is host-defined; ACL intersection consults Jmix `AccessManager`. |
| `unknown_entity` retry rule | Backend prompt assembly + tool error DTO | — | Both touch the same Spring AI prompt+tool boundary. |
| TEST-08 prompt-contract test | Backend test (`@SpringBootTest`) | — | Uses existing `StubChatModelConfiguration`; opt-in live via `@Tag("live")`. |
| Sensitive-field hashing utility | Backend audit layer (Phase 11 wires the caller) | — | Pure stateless utility; no per-request lifecycle. |

All work lands in `com.vn.agent.*` backend packages. Zero UI tier work in Phase 9 (Jmix Flow UI views land in Phases 10/12).

## Standard Stack

### Core (already on classpath — verified at `ai-agent/build.gradle:32-44`)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Jmix BOM | 2.8.1 | Metamodel, security, data access, Flow UI | Project baseline; non-negotiable. |
| Spring AI BOM | 1.1.4 | `ChatClient`, advisors, `@Tool`, `MethodToolCallback` | Pinned in shared root build.gradle. |
| Spring Boot | 3.x (transitive via Jmix BOM) | DI, `@ConfigurationProperties` | Project baseline. |
| Java | 21 | Language toolchain | `JavaLanguageVersion.of(21)` set in subprojects block. |

### Supporting (already on classpath)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jackson `ObjectMapper` | Spring Boot transitive | JSON serialization for `agent.permissions` map + tool result DTOs | Already injected into `ToolResultFormatter`. |
| JUnit 5 + Mockito + AssertJ | Spring Boot transitive | Test framework | Existing convention; `@Tag("live")` excluded via `ai-agent.gradle`. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| In-method per-attribute walk for `agent.permissions` | `@Cacheable` over a Spring `Cache` bean | Cache key would need to include user roles + locale-free metaclass identity. CONTEXT D-02 requires locale-sensitive labels NOT in cache key. v1.0 deliberately runs `CurrentUserSchemaAccess.getReadableSchema()` per request with no cache (per `metadata/CurrentUserSchemaAccess.java` Javadoc line 17: "no class-level cache (per threat model T-03-01)"). **Use the same no-cache posture in Phase 9.** Phase 10 introduces `LlmExposureChangedEvent` for future cache invalidation; we publish that event from Phase 10, not consume it from Phase 9. |
| Hand-built regex over emitted text for host-prefix scan | Tokenization + set lookup | Regex is what `OutputScannerAdvisor` already uses; consistency wins. Performance: 8 KiB hard-cap (already in advisor at line 49) eliminates ReDoS risk. |
| `String.format` for `<data entity=...>` element | Jackson custom serializer / XML library | The current code already produces this shape with `String` concat; `ToolResultFormatter` is JSON-internal but emits an XML-shaped wrapper element by string for prompt-injection isolation (Phase 3 D-13). Adding a real XML library would break the existing `<data>` escape-on-write pattern. **Stay with String concat; reuse `escapeDataDelimiters` already at line 239.** |

**Installation:** None. Phase 9 adds zero dependencies.

**Version verification (current April 2026):**

```
verified at ai-agent/build.gradle line 32: bomVersion = '2.8.1'
verified at ai-agent/build.gradle line 37: springAiVersion = '1.1.4'
verified at jmix-app/build.gradle line 2: id 'io.jmix' version '2.8.1'
```

No drift from training data; both are current as of the project's pin date.

## Architecture Patterns

### System Architecture Diagram

```
   USER chat turn
        │
        ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │  DefaultChatServiceImpl.ask(...)                                  │
   │   ├── (existing) RateLimitGuard / TokenBudgetGuard preamble       │
   │   ├── ConversationGateway.loadOrCreate                            │
   │   ├── BaselineContextProvider.renderAsText(convId)  ◄─── PHASE 9  │
   │   │     └── compose(...) — adds two new keys:                     │
   │   │         • agent.entities      (D-01)                          │
   │   │         • agent.permissions   (D-02)                          │
   │   │     SOURCE: CurrentUserSchemaAccess.getReadableSchema()       │
   │   │             AccessManager + CrudEntityContext +               │
   │   │             EntityAttributeContext (incl. canModify())        │
   │   │                                                               │
   │   ├── effectiveSystemPrompt(...) → composedSystemPrompt           │
   │   │     └── baselineText + profileSystemPrompt + PHASE 9 rules:   │
   │   │         • forbid host-prefix names in user replies (PROMPT-03)│
   │   │         • unknown_entity retry-once contract (PROMPT-05/D-15) │
   │   │                                                               │
   │   └── chatClient.prompt()                                         │
   │         .system(composedSystemPrompt)                             │
   │         .toolCallbacks(toolCallbacks.callbacksFor(...))           │
   │         .call().chatClientResponse();                             │
   └───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │  Spring AI advisor chain (existing order, unchanged)              │
   │   AuditAdvisor → MessageChatMemoryAdvisor → RetrievalAugmentation │
   │   → ToolCallAdvisor → OutputScannerAdvisor                        │
   │                                ▲                                  │
   │                                │                                  │
   │                       PHASE 9 adds two patterns:                  │
   │                        • host-prefix-leak (D-06)                  │
   │                        • tool-name-leak    (D-07)                 │
   │                       to AiAgentGuardProperties.resolvedPatterns()│
   └───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │  Tool invocation (LLM-driven, recursive via ToolCallAdvisor)      │
   │   ToolCallback (decorated by ToolCallbackAuditDecorator)          │
   │     └── BuiltInDataTools.@Tool methods                            │
   │                                                                   │
   │   describe_entity (TOOL-09 / D-04, PHASE 9 expands payload):      │
   │     ToolResultFormatter.describe(metaClass, readableAttrs)        │
   │       └── per MetaProperty:                                       │
   │             MetadataTools.getMetaAnnotationValue(p, Comment.class)│
   │             MetaProperty.getRange().getCardinality()              │
   │             MetadataTools.isPrimaryKey(p)                         │
   │             MetadataTools.isJpa(p)        (persistent vs transient)
   │             p.isMandatory() / p.isReadOnly()                      │
   │             p.getRange().asEnumeration().getValues() + Messages   │
   │             p.getRange().asClass().getName() + caption            │
   │             @Column(length=...) via p.getAnnotatedElement()       │
   │                                                                   │
   │   find_records / get_record / get_related_records (TOOL-10/11):   │
   │     fetchPlanResolver.resolve(toolName, metaClass, ctx)  ◄─NEW    │
   │       1. host: ToolFetchPlanCustomizer.overrideFor(...) optional  │
   │       2. fallback: FetchPlan.BASE                                 │
   │       3. ALWAYS: FetchPlanIntersector.intersectWithAcl(plan, mc)  │
   │             walks plan.getProperties() recursively;               │
   │             drops those for which                                 │
   │             currentUserSchemaAccess.canReadAttribute() == false;  │
   │             audits PLAN_NARROWED via AuditWriter (D-12).          │
   │     // CRITICAL CODE COMMENT: "fetch plan is projection, not security."
   │     dataManager.load(...).fetchPlan(narrowedPlan).list();         │
   │                                                                   │
   │   On unknown_entity error (PHASE 9 / D-14):                       │
   │     throw new ToolUserError("unknown_entity", reason,             │
   │                             List.of(<3 procedural hints>));      │
   │     → ToolResultFormatter.error(...) → JSON ToolErrorDto          │
   │     → returned to LLM as tool-result string                       │
   └───────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

```
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── orchestration/
│   ├── BaselineContextProvider.java          # EXTEND — add entities/permissions keys (D-01, D-02)
│   └── (existing files unchanged)
├── tools/
│   ├── BuiltInDataTools.java                 # EXTEND — describe_entity (D-04), unknown_entity hints (D-14)
│   ├── ToolResultFormatter.java              # EXTEND — describe payload widening, <data entity= shape (PROMPT-04)
│   ├── ToolErrorDto.java                     # UNCHANGED (List<String> expected already shaped)
│   ├── AgentToolCallbacks.java               # UNCHANGED (D-07 source of tool name set is read-only access)
│   └── fetchplan/                            # NEW package
│       └── FetchPlanIntersector.java         # NEW — internal helper, walks FetchPlan vs canReadAttribute
├── spi/
│   ├── ToolFetchPlanCustomizer.java          # NEW SPI (SPI-09 / TOOL-10)
│   ├── FetchPlanContext.java                 # NEW record (D-10)
│   └── (existing SPIs unchanged)
├── guard/
│   ├── AiAgentGuardProperties.java           # EXTEND — host-prefix + tool-name leak pattern blocks (D-08)
│   ├── OutputScannerAdvisor.java             # UNCHANGED — pattern list lives in properties
│   └── HostPrefixPatternProvider.java        # NEW — derives pattern from MetaClass scan at startup (D-06)
├── audit/
│   ├── AuditWriter.java                      # UNCHANGED in Phase 9 (Phase 11 extends)
│   └── AuditFieldHasher.java                 # NEW — static SHA-256 hex utility (D-18)
├── DefaultChatServiceImpl.java               # EXTEND — append PROMPT-03 + PROMPT-05/D-15 system prompt rules
└── (existing files unchanged)

ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/
├── SpiDefaultsAutoConfiguration.java         # EXTEND — add no-op ToolFetchPlanCustomizer bean
├── AiAgentGuardAutoConfiguration.java        # EXTEND — register HostPrefixPatternProvider bean
└── (other autoconfigs unchanged)

ai-agent/ai-agent-starter/src/main/resources/
└── default-params.yaml                       # UNCHANGED (no AiParameters change in Phase 9)

ai-agent/ai-agent/src/main/resources/com/vn/agent/
├── messages.properties                       # EXTEND — new keys for any user-facing label
└── messages_vi.properties                    # EXTEND — VI parity (CLAUDE.md mandate)

ai-agent/ai-agent/src/test/java/com/vn/agent/
├── orchestration/BaselineContextProviderTest.java  # EXTEND — agent.entities + agent.permissions assertions
├── tools/DescribeEntityPayloadTest.java            # NEW — TOOL-09 widened-payload unit test
├── tools/FetchPlanIntersectorTest.java             # NEW — TOOL-11 prune walk + audit emission
├── tools/UnknownEntityRetryHintTest.java           # NEW — D-14 expected[] string assertions (deterministic)
├── guard/HostPrefixLeakScannerTest.java            # NEW — corpus-driven, parallels OutputScannerAdvisorTest
├── audit/AuditFieldHasherTest.java                 # NEW — SHA-256 hex output, UTF-8 byte handling
└── PromptContractMockTest.java                     # NEW — TEST-08 default-CI mock variant (parameterized VI/EN)

ai-agent/ai-agent/src/test/java/com/vn/agent/live/
└── PromptContractLiveTest.java                     # NEW — TEST-08 @Tag("live") opt-in
```

### Pattern 1: Locale-Free Permission Map Construction

**What:** Build `agent.permissions` map keyed by entity-name (locale-stable), with CRUD bits and `modifiable` set computed live per request from `AccessManager`.

**When to use:** Every chat turn. Reuses the same `CurrentUserSchemaAccess` boundary the v1.0 tools already use (parity guarantee).

**Example:**
```java
// Source: pattern verified in v1.0 source — CurrentUserSchemaAccess.java line 64-69
//         Context7 — /jmix-framework/jmix-context7 "AccessManager applyConstraints"
public Map<String, Map<String, Object>> buildPermissionMap(
        Map<MetaClass, Set<String>> readableSchema) {
    Map<String, Map<String, Object>> out = new TreeMap<>();   // alpha entity order
    for (Map.Entry<MetaClass, Set<String>> e : readableSchema.entrySet()) {
        MetaClass mc = e.getKey();
        CrudEntityContext crud = new CrudEntityContext(mc);
        accessManager.applyRegisteredConstraints(crud);

        int r = crud.isReadPermitted()   ? 1 : 0;
        int u = crud.isUpdatePermitted() ? 1 : 0;
        int c = crud.isCreatePermitted() ? 1 : 0;
        int d = crud.isDeletePermitted() ? 1 : 0;
        if (r + u + c + d == 0) continue;   // D-02: skip all-zero entries

        // alpha-sorted modifiable attribute set
        Set<String> modifiable = new TreeSet<>();
        for (String attrName : e.getValue()) {
            EntityAttributeContext ac = new EntityAttributeContext(mc, attrName);
            accessManager.applyRegisteredConstraints(ac);
            if (ac.canModify()) {
                modifiable.add(attrName);
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("r", r);
        entry.put("u", u);
        entry.put("c", c);
        entry.put("d", d);
        entry.put("modifiable", modifiable);
        out.put(mc.getName(), entry);   // metaclass name is locale-free; safe in cache key
    }
    return out;
}
```

### Pattern 2: Output-Scanner Pattern Append (PROMPT-06)

**What:** Two new patterns are appended to the existing `OutputScannerAdvisor` corpus via `AiAgentGuardProperties.resolvedPatterns()`. Pattern *source* differs (host-prefix is dynamic from metaclasses; tool-name is dynamic from `AgentToolCallbacks`), but the *consumption* layer is unchanged.

**Example:**
```java
// Source: existing AiAgentGuardProperties.java line 94-103 — extension point.
// New beans (HostPrefixPatternProvider, ToolNamePatternProvider) compute patterns at
// ApplicationReadyEvent and inject them into the guard properties via a small helper.
@Component
public class HostPrefixPatternProvider {

    private final Metadata metadata;
    private volatile String compiledRegex;

    @EventListener(ApplicationReadyEvent.class)
    public void buildPattern() {
        Set<String> prefixes = new TreeSet<>();
        for (MetaClass mc : metadata.getSession().getClasses()) {
            String name = mc.getName();
            int underscore = name.indexOf('_');
            if (underscore > 0) {
                prefixes.add(Pattern.quote(name.substring(0, underscore)));
            }
        }
        if (prefixes.isEmpty()) {
            this.compiledRegex = null;   // no host_prefix entities — emit no pattern
            return;
        }
        // Source: D-06 — single regex over union, bounded \w+ stops at ReDoS-safe end.
        this.compiledRegex = "\\b(" + String.join("|", prefixes) + ")_\\w+\\b";
    }

    public Optional<AiAgentGuardProperties.OutputScanner.Pattern> asPattern() {
        return compiledRegex == null
                ? Optional.empty()
                : Optional.of(new AiAgentGuardProperties.OutputScanner.Pattern(
                        "HOST_PREFIX_LEAK", compiledRegex));
    }
}
```

### Pattern 3: Fetch-Plan-vs-ACL Intersection (TOOL-11, D-11)

**What:** After `ToolFetchPlanCustomizer.overrideFor(...)` returns a `FetchPlan` (or the default `_base` is used), recursively walk its properties and drop any whose path is denied by `currentUserSchemaAccess.canReadAttribute(metaClass, propertyPath)`. Emits a `PLAN_NARROWED` audit row when any property is dropped.

**Example:**
```java
// Source: pattern derived from existing CurrentUserSchemaAccess.canReadAttribute
//         (line 64) + FetchPlans.builder() flow (Context7 jmix data-access/fetching.html).
@Component
public class FetchPlanIntersector {

    private final FetchPlans fetchPlans;
    private final CurrentUserSchemaAccess schemaAccess;
    private final AuditWriter auditWriter;

    /**
     * fetch plan is projection, not security.   // ◄── REQUIRED CODE COMMENT (TOOL-11 verbatim)
     *
     * Walks the host-supplied (or default) FetchPlan and removes every property for which
     * the current user lacks read access on the corresponding MetaClass attribute path.
     * Returns a freshly-built FetchPlan; never mutates the input.
     */
    public FetchPlan intersectWithAcl(FetchPlan original, MetaClass rootMc, String toolName) {
        List<String> dropped = new ArrayList<>();
        FetchPlanBuilder builder = fetchPlans.builder(rootMc.getJavaClass());

        for (FetchPlanProperty prop : original.getProperties()) {
            String name = prop.getName();
            if (!schemaAccess.canReadAttribute(rootMc, name)) {
                dropped.add(name);
                continue;
            }
            FetchPlan nested = prop.getFetchPlan();
            if (nested != null) {
                MetaClass nestedMc = rootMc.getProperty(name).getRange().asClass();
                FetchPlan narrowedNested = intersectWithAcl(nested, nestedMc, toolName);
                builder.add(name, fpb -> fpb.addFetchPlan(narrowedNested));
            } else {
                builder.add(name);
            }
        }

        if (!dropped.isEmpty()) {
            auditPlanNarrowed(toolName, rootMc.getName(), dropped);
        }
        return builder.build();
    }
}
```

### Anti-Patterns to Avoid

- **Caching `agent.permissions` by `(userId, conversationId)` key:** v1.0 deliberately runs no cache for `getReadableSchema()` per `metadata/CurrentUserSchemaAccess.java` line 17 ("no class-level cache (per threat model T-03-01 + Open-Question #6 recommendation)"). Phase 10 will introduce `LlmExposureChangedEvent` for cache invalidation, but Phase 9 must NOT add a cache. Per-request cost is one metaclass-iteration loop — same scale as the existing happy path.
- **Including locale or label text in any cache key:** PROMPT-02 explicit wording. If we ever add a cache, the key must be `(userId, roleSet, metaclass-name-set)` — never `MessageTools.getEntityCaption(...)` output.
- **Hard-blocking on output-scanner flag:** PROMPT-06 explicit wording: flag-and-audit only. Existing advisor is correctly flag-only at line 109-120; new patterns must inherit this posture.
- **Echoing the matched leak text into the audit row:** D-18 of Phase 6 plus existing advisor at line 109 — only the pattern KEY is recorded. Phase 9 patterns inherit.
- **Reflectively reading `@Comment` annotation directly:** Per CLAUDE.md and feedback memory `feedback_reuse_jmix_builtins`. Use `MetadataTools.getMetaAnnotationValue(metaProperty, Comment.class)` — this is the documented Jmix path per Context7 `/jmix-framework/jmix-context7 entities.html#@Comment > Usage and Tools`.
- **Using `@Comment` annotation values as `msg://` keys:** Per feedback memory `feedback_jmix_messages_over_spring`, Jmix convention is plain text comments, not message-bundle keys. Render raw.
- **Adding `_excluded` field to the `describe_entity` JSON response:** D-05 explicit — Javadoc only. Adding it would let the LLM "guess" values for excluded fields, defeating the exclusion.
- **Calling host `ToolFetchPlanCustomizer` before checking exposure policy (Phase 10 forward-compat):** Phase 9 has no exposure policy yet, but the resolution order must be: (1) host customizer → (2) ACL intersection → (3) pass to `DataManager.load`. Phase 10 inserts exposure-policy narrow BEFORE step 1; Phase 9 leaves the seam open.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Per-attribute access check | Custom `if (role.contains("ROLE_X"))` walk | `accessManager.applyRegisteredConstraints(new EntityAttributeContext(mc, path))` then `canView() / canModify()` | Jmix encapsulates roles, row-level rules, system roles, and policy composition. Bypassing leaks through every constraint type the host configures. Pattern at `CurrentUserSchemaAccess.java:64`. |
| Per-entity CRUD check | Manual `Authentication.authorities` parse | `accessManager.applyRegisteredConstraints(new CrudEntityContext(mc))` then `isReadPermitted/isUpdatePermitted/isCreatePermitted/isDeletePermitted` | Same reason. Documented in Context7 `/jmix-framework/jmix-context7 authorization.html#Authorization Architecture > Entity Loading Process`. |
| Reading `@Comment` via reflection | `metaProperty.getJavaType().getDeclaredField(name).getAnnotation(Comment.class)` | `metadataTools.getMetaAnnotationValue(metaProperty, Comment.class)` | CLAUDE.md mandate; Context7 `/jmix-framework/jmix-context7 entities.html#@Comment > Usage and Tools` confirms `MetadataTools.getMetaAnnotationValue()` is the canonical accessor. Reflection bypasses Jmix's metadata-post-processor pipeline (e.g. multi-store `MetadataPostProcessor` rewrites). |
| Building entity-caption / enum-value labels | Custom message-bundle reads | `messageTools.getEntityCaption(metaClass)` and `messages.getMessage(EnumValueInstance)` | Locale-aware via `CurrentAuthentication`; already wired (`ToolResultFormatter` line 89). Context7 `/jmix-framework/jmix-context7 message-bundles.html#Localize Enumeration Names and Values` confirms `messages.getMessage(Enum)` overload. |
| Detecting JPA persistent vs. transient | `metaProperty.getJavaType().isAnnotationPresent(Transient.class)` | `metadataTools.isJpa(metaProperty)` (true = persistent JPA-mapped) and the inverse for "transient" (`!isJpa(...)`). For embedded primary keys: `metadataTools.isPrimaryKey(metaProperty)` | The Jmix metamodel correctly classifies `@Transient`, `@JmixProperty`, embedded id/property, computed `@JmixProperty(...)`, and inheritance. Manual annotation reads miss several. |
| Detecting primary key | Walk `@Id` / `@EmbeddedId` annotations | `metadataTools.getPrimaryKeyProperty(metaClass)` (returns the property) and `metadataTools.isPrimaryKey(metaProperty)` (returns boolean) | Already used at `BuiltInDataTools.java:312`. Handles UUID + `@JmixGeneratedValue` and embedded PKs uniformly. |
| Caching tool-result schema | Custom `ConcurrentHashMap` with role-keyed eviction | **Don't cache. Run live per request** | v1.0 explicit decision (`CurrentUserSchemaAccess` Javadoc line 17). Per-request cost is acceptable; cache invalidation on role/metadata change is harder than the build cost. Phase 10's `LlmExposureChangedEvent` is a forward-compat hook, not a Phase 9 consumer. |
| Tool-error envelope to send back to LLM | Throw `RuntimeException` with `getMessage()` baked in | Return `JSON.stringify(ToolErrorDto)` from the `@Tool` method (existing pattern) | Spring AI 1.1.4: `DefaultToolExecutionExceptionProcessor` sends `RuntimeException.getMessage()` to the model, but our v1.0 `BuiltInDataTools` already does it more cleanly — catch `ToolUserError` at the `@Tool` boundary and return a JSON-encoded `ToolErrorDto` (see `BuiltInDataTools.java:113-115`). Spring AI delivers that string to the LLM as a normal `tool` role message. **Keep the existing pattern.** |
| Stream output scanner | Re-implement scanner for streaming chunks | `ChatClientMessageAggregator().aggregateChatClientResponse(flux, this::scan)` | Spring AI 1.1 ships `ChatClientMessageAggregator` (Context7 `/spring-projects/spring-ai/v1.1.2 advisors.adoc`). Phase 9 stays on call-only `OutputScannerAdvisor` posture (existing) — streaming output scan is a v1.2 concern; do not add now. |
| Sensitive-field hashing | `MessageDigest` getInstance + custom hex encoder per call site | `AuditFieldHasher.hash(String)` static utility (Phase 9 ships it stateless; Phase 11 wires callers) | One-class scope, pure function, no SPI yet (MEMORY rule "SPIs only for app-specific behavior"). |
| Iterating `FetchPlan` properties | Cast to internal type | `fetchPlan.getProperties()` returning `Collection<FetchPlanProperty>`; for nested plans, `prop.getFetchPlan()` returns the nested `FetchPlan` or null | Public Jmix API; safe across 2.x. |

**Key insight:** Every Jmix metadata-derived field needed by `describe_entity` already has a documented `MetadataTools` accessor or `MetaProperty` getter. The temptation to "just use reflection" is exactly what `feedback_reuse_jmix_builtins` warns against — Jmix metadata may have been post-processed (multi-store, custom datatypes, computed properties) and reflection sees a stale picture. Always go through `MetadataTools` / `MetaProperty`.

## Common Pitfalls

### Pitfall 1: Locale-Sensitive Labels Leaking into Cache Keys

**What goes wrong:** Naïve cache like `agent.permissions.cache.put(userId + ":" + locale, map)` makes cache size grow with locale count and forces re-population for users who switch language. Worse, if labels (Vietnamese vs English) end up in the cached value but the key omits locale, the cache will silently serve stale-locale labels.

**Why it happens:** `MessageTools.getEntityCaption(metaClass)` returns locale-resolved text. Easy to pull it into the same map you later cache.

**How to avoid:** Phase 9 explicitly does NOT add a cache. If a cache is ever added (Phase 10+), key on `(userId, roleSet)` only, store the locale-free skeleton (`Map<String, Set<String>>` of metaclass-name → modifiable-attr-name), and resolve labels per render via `MessageTools` / `Messages.getMessage(...)`. PROMPT-02 explicit wording bans labels in cache keys.

**Warning signs:** Any new cache annotation (`@Cacheable`, `Cache.put(...)`) introduced in Phase 9 — fail review.

### Pitfall 2: Fetch Plan Widens Beyond AccessManager (Defeats TOOL-11)

**What goes wrong:** Host-supplied `ToolFetchPlanCustomizer` returns a `FetchPlan` containing attributes the user cannot read. If we hand the plan straight to `DataManager.load(...).fetchPlan(plan)`, Jmix `DataManager` *does* enforce attribute-level read permissions and will null those fields — but the column is still loaded into the JVM heap, traversed by JPA listeners, and may surface via `EntityValues.getValue(...)` paths in `ToolResultFormatter` before being stripped.

**Why it happens:** Hosts assume "fetch plan = just a projection optimization, security takes care of itself." This is half-true: Jmix's runtime DOES filter, but the boundary is much later than ours.

**How to avoid:** Apply `FetchPlanIntersector.intersectWithAcl(plan, mc, toolName)` BEFORE `DataManager.load(...)`. Drop denied attributes at plan-build time, audit `PLAN_NARROWED`, then load. CONTEXT D-11/D-12 verbatim. **Code comment must read "fetch plan is projection, not security." (TOOL-11 verbatim).**

**Warning signs:** Any code path where `customizer.overrideFor(...)` result is passed unmodified to `DataManager.load(...)`. Grep test enforces.

### Pitfall 3: Raw Reflection in `describe_entity`

**What goes wrong:** Reading `@Comment` via `metaProperty.getJavaClass().getDeclaredField(...).getAnnotation(Comment.class)` works for vanilla JPA entities but breaks on (a) computed `@JmixProperty` getters, (b) embedded primary keys, (c) entities with `MetadataPostProcessor` rewrites (multi-store), and (d) DTO entities.

**Why it happens:** It's the obvious shortcut and Jmix's `MetadataTools.getMetaAnnotationValue(...)` is less well-known.

**How to avoid:** Use `metadataTools.getMetaAnnotationValue(metaProperty, Comment.class)` — verified via Context7 docs. For entity-level comment, `metadataTools.getMetaAnnotationValue(metaClass, Comment.class)` (same accessor, different first arg).

**Warning signs:** Any `getDeclaredField`, `getAnnotation`, `getDeclaredAnnotations` call in code under `tools/` or `orchestration/`. Reject in PR review. Existing `ToolResultFormatter.columnLength(...)` at line 99 is the *only* allowed reflection in the tool layer (it reads `@Column.length` which has no `MetadataTools` accessor — verified).

### Pitfall 4: `ToolFetchPlanCustomizer.overrideFor(...)` Throwing Disrupts Tool Call

**What goes wrong:** Host's customizer throws an unchecked exception. `BuiltInDataTools.findRecords` lets it bubble, the tool method returns nothing, and Spring AI's `DefaultToolExecutionExceptionProcessor` sends the raw `RuntimeException.getMessage()` back to the LLM — possibly leaking host stack-trace strings.

**Why it happens:** Host code under stress: NPE in customizer, mistyped `MetaClass.getProperty(...)`, etc.

**How to avoid:** Wrap customizer invocation in `try { ... } catch (RuntimeException ex) { log.warn(...); return Optional.empty(); }` inside the resolver. Default `_base` then applies. No new audit kind needed (matches D-12 silent-drop posture).

**Warning signs:** Direct `customizer.overrideFor(...).orElse(default)` chain without a try-catch.

### Pitfall 5: Output-Scanner Pattern Catastrophic Backtracking

**What goes wrong:** Dynamically-built host-prefix regex `\b(p1|p2|p3)_\w+\b` LOOKS bounded, but if the host has 500 prefix tokens (multi-tenant Jmix monolith) the alternation explodes; on certain inputs `Pattern.matcher().find()` becomes O(2^n).

**Why it happens:** D-06 says "single regex over distinct prefix tokens" without bounding the count.

**How to avoid:** (a) Existing 8 KiB input cap at `OutputScannerAdvisor.MAX_SCAN_CHARS` (line 49) is the last-line ReDoS defence. (b) `Pattern.quote(prefix)` on each token (already in Pattern 2 example) prevents pathological characters in the prefix from amplifying. (c) `\w+` is greedy but bounded by the input cap. (d) Sanity check at startup: if `prefixes.size() > 200`, log a warning and fall back to a per-prefix iteration (loop over patterns, first hit wins) instead of one giant alternation. Decision: add the 200-prefix sanity check.

**Warning signs:** Host-prefix regex source longer than ~4 KiB — log warning and consider per-prefix iteration.

### Pitfall 6: `agent.permissions` JSON Key Order Drift

**What goes wrong:** Map iteration order on a fresh `HashMap` is non-deterministic; serialized JSON differs byte-for-byte across runs; prompt-hash for audit varies; cache (when added in Phase 10) misses constantly.

**Why it happens:** `LinkedHashMap` insertion order is fine if entities and attributes are inserted in alpha order, but `HashMap`/random-iteration breaks it.

**How to avoid:** Use `TreeMap<String, ...>` for the outer entity map and `TreeSet<String>` for `modifiable`. Existing convention in `BaselineContextProvider.renderAsText(...)` line 65 (`TreeMap<>`) gives precedent. Test `byte_for_byte_serialization_is_stable_across_runs` asserts.

**Warning signs:** `HashMap` / `HashSet` in Phase 9 prompt-rendering code. Use `TreeMap` / `TreeSet` exclusively.

### Pitfall 7: `unknown_entity` Procedural Hints Drift Across Translations

**What goes wrong:** Future contributor "improves" wording on the three `expected[]` strings; TEST-08 asserts on substring; passes for VI but fails for EN (or vice versa) because the test uses one locale's literal string.

**Why it happens:** D-14 requires three EXACT procedural hints in EXACT order — they are part of the deterministic tool output, not user-facing text. They are NOT translated.

**How to avoid:** **The three procedural hints in `ToolErrorDto.expected` are NOT in `messages.properties`.** They are hardcoded English strings inside `BuiltInDataTools.resolveReadableEntityOrThrow(...)` (or wherever `unknown_entity` is constructed). User-facing messaging is generated by the LLM from those English procedural hints + the LLM's own translation step. Test asserts the literal English strings on the tool output JSON.

**Warning signs:** Any `messages.getMessage("ai-agent.tool.unknown-entity.hint.X")` call. Reject — these strings are tool-protocol, not UI.

### Pitfall 8: Tool-Name Leak Pattern Matches Legitimate Output

**What goes wrong:** Tool-name leak pattern lists `find_records`, `count_records`, etc. The LLM, when explaining an error, might legitimately write "I tried to find records but..." — the substring `find records` is unrelated to the literal tool-name `find_records`. False positive flags an innocent reply.

**Why it happens:** Word-boundary regex `\bfind_records\b` matches the literal underscore form only — but if the LLM writes "find_records" (with underscore) in user-facing reply, it IS the leak we want to catch. This is correct behavior. But naïve pattern `find\s*records` would over-match.

**How to avoid:** Patterns must require the underscore (or other non-natural-language separator) verbatim. Use `\bfind_records\b`, `\bdescribe_entity\b`, etc. — never `find\s+records`. Test corpus must include both leaky form (`find_records`) and innocent form ("I will look for records") to verify discrimination.

**Warning signs:** Any pattern using `\s` or `[^a-z]` as separator. Underscore literal only.

### Pitfall 9: `ToolFetchPlanCustomizer` Bean Discovery Ambiguity

**What goes wrong:** Two host modules each declare a `ToolFetchPlanCustomizer` bean. Single-bean injection model fails with `NoUniqueBeanDefinitionException`. List-injection works but resolution order undefined.

**Why it happens:** `ToolContributor` (existing parallel SPI) uses `List<ToolContributor>` injection with default-no-op fallback in `SpiDefaultsAutoConfiguration`. Phase 9 should mirror that.

**How to avoid:** Inject `List<ToolFetchPlanCustomizer>` (Spring AI's `ToolContributor` precedent at `AgentToolCallbacks.java:33`). Resolver iterates in `@Order` order; first non-empty `Optional<FetchPlan>` wins. Default no-op bean returns `Optional.empty()` always (registered in `SpiDefaultsAutoConfiguration`). Mirrors `feedback_spi_baseline_builtin` — narrow surface, clear conflict resolution.

**Warning signs:** Single-bean `@Autowired ToolFetchPlanCustomizer customizer` injection. Use `List<>`.

### Pitfall 10: `enumValues[].label` Resolution Bypasses Locale

**What goes wrong:** `((Enum<?>)v).name()` gives the constant identifier (e.g. `ACTIVE`). Per D-04, we want `[{name:"ACTIVE", label:"Hoạt động"}]` for VI locale. Calling `value.name()` only gets the name; calling `messages.getMessage(value)` requires `value` to be the actual enum *constant instance*, not its `name()` string.

**Why it happens:** `Range.asEnumeration().getValues()` returns `List<?>` of enum-class instances. The Jmix `EnumClass` interface (also implemented by entity enums) provides `getId()` for the database value but NOT a localized label — that comes from `Messages`.

**How to avoid:** For each `Object v` returned by `range.asEnumeration().getValues()`:
- `name = ((Enum<?>) v).name()` for the Java identifier
- `id = v instanceof EnumClass<?> ec ? ec.getId().toString() : name` for the persisted DB token (Jmix `AiToolCallOutcome` example)
- `label = messages.getMessage((Enum<?>) v)` — the canonical locale-aware accessor per Context7 `/jmix-framework/jmix-context7 message-bundles.html#Retrieve Localized Enum Value using Messages Interface`. Verified.

**Warning signs:** Custom enum-label lookup like `messageBundle.getMessage(enum.getClass().getName() + "." + enum.name())`. Use `messages.getMessage(enumValue)` overload.

## Code Examples

### `agent.entities` Block Render (D-01)

```java
// Source: extension of BaselineContextProvider.compose(...) at line 46.
// CONTEXT D-01 + D-03.
@Component
public class BaselineContextProvider {
    // existing fields...
    private final CurrentUserSchemaAccess schemaAccess;
    private final MessageTools messageTools;
    private final AiAgentPromptProperties promptProps;   // NEW

    public Map<String, Object> compose(UUID conversationId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        // ... existing agent.userId / username / roles / locale / conversationId ...

        Map<MetaClass, Set<String>> readable = schemaAccess.getReadableSchema();
        if (!readable.isEmpty()) {
            ctx.put("agent.entities", renderEntityInventory(readable));
            ctx.put("agent.permissions", renderPermissionMap(readable));   // see Pattern 1
        }
        return ctx;
    }

    private String renderEntityInventory(Map<MetaClass, Set<String>> readable) {
        List<MetaClass> sorted = readable.keySet().stream()
                .sorted(Comparator.comparing(MetaClass::getName))
                .toList();
        int threshold = promptProps.entityInventoryThreshold();   // default 100, D-03
        boolean truncated = sorted.size() > threshold;
        if (truncated) sorted = sorted.subList(0, threshold);

        StringBuilder sb = new StringBuilder();
        for (MetaClass mc : sorted) {
            sb.append(mc.getName())
              .append(" (").append(messageTools.getEntityCaption(mc)).append(")")
              .append('\n');
        }
        if (truncated) {
            sb.append("... (truncated, call list_entities for full list)");
        } else if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);   // strip trailing \n
        }
        return sb.toString();
    }
}
```

### `unknown_entity` Retry Hint Population (D-14)

```java
// Source: extension of BuiltInDataTools.resolveReadableEntityOrThrow(...) line 264.
// CONTEXT D-14 — three procedural hints in fixed order.
private static final List<String> UNKNOWN_ENTITY_HINTS = List.of(
        "call list_entities exactly once",
        "if a name in list_entities matches your intent, retry the original tool with that exact name",
        "if no entity in list_entities matches, tell the user no such entity exists — do not guess"
);

private MetaClass resolveReadableEntityOrThrow(String entityName) {
    if (entityName == null || entityName.isBlank()) {
        throw new ToolUserError("unknown_entity",
                "entity name must not be blank", UNKNOWN_ENTITY_HINTS);
    }
    MetaClass metaClass;
    try {
        metaClass = metadata.getClass(entityName);
    } catch (RuntimeException unknownName) {
        throw new ToolUserError("unknown_entity",
                "no entity named " + entityName, UNKNOWN_ENTITY_HINTS);
    }
    if (metaClass == null) {
        throw new ToolUserError("unknown_entity",
                "no entity named " + entityName, UNKNOWN_ENTITY_HINTS);
    }
    if (!currentUserSchemaAccess.canReadEntity(metaClass)) {
        // P-13 opacity: denied entity surfaces as unknown_entity with same hints.
        throw new ToolUserError("unknown_entity",
                "no entity named " + entityName, UNKNOWN_ENTITY_HINTS);
    }
    return metaClass;
}
```

### `describe_entity` Widened Payload Snippet (D-04)

```java
// Source: extension of ToolResultFormatter.buildAttributeDescription(...) line 148.
// CONTEXT D-04 — fields verified via Context7 jmix-context7 metadata.html
//                + entities.html#@Comment > Usage and Tools.
private AttributeDescription buildAttributeDescription(MetaProperty mp) {
    Range range = mp.getRange();

    String comment = metadataTools.getMetaAnnotationValue(mp,
            io.jmix.core.metamodel.annotation.Comment.class);
    String attributeType = typeLabel(mp);                           // existing helper line 91
    String cardinality = range.isClass()
            ? range.getCardinality().name()                         // raw enum string per D-04
            : "NONE";
    boolean mandatory     = mp.isMandatory();
    boolean readOnly      = mp.isReadOnly();
    boolean isPersistent  = metadataTools.isJpa(mp);                // Jmix-canonical
    boolean isTransient   = !isPersistent;
    boolean isPrimaryKey  = metadataTools.isPrimaryKey(mp);

    List<EnumValueEntry> enumValues = null;
    if (range.isEnum()) {
        enumValues = new ArrayList<>();
        for (Object v : range.asEnumeration().getValues()) {
            String name = ((Enum<?>) v).name();
            String label = messages.getMessage((Enum<?>) v);        // locale-aware (D-04)
            enumValues.add(new EnumValueEntry(name, label));
        }
    }

    RelationshipTargetEntry relationshipTarget = null;
    if (range.isClass()) {
        MetaClass target = range.asClass();
        relationshipTarget = new RelationshipTargetEntry(
                target.getName(),
                messageTools.getEntityCaption(target));
    }

    Integer maxLength = (range.isDatatype() && range.asDatatype().getJavaClass() == String.class)
            ? columnLength(mp)                                       // existing line 99
            : null;

    return new AttributeDescription(
            mp.getName(),
            attributeType,
            comment,
            cardinality,
            mandatory,
            readOnly,
            isPersistent,
            isTransient,
            isPrimaryKey,
            enumValues,
            relationshipTarget,
            maxLength,
            messageTools.getPropertyCaption(mp));
}
```

### `ToolFetchPlanCustomizer` SPI + Resolver (TOOL-10/11, D-09–D-13)

```java
// Source: new com.vn.agent.spi.ToolFetchPlanCustomizer (D-09)
package com.vn.agent.spi;

import io.jmix.core.FetchPlan;
import io.jmix.core.metamodel.model.MetaClass;
import java.util.Optional;

/**
 * Host hook to override the data fetch plan used by built-in read tools (find_records,
 * get_record, get_related_records). The returned plan is intersected with the current
 * user's attribute-level read permissions before any DataManager load fires; host
 * customisation is projection-only and cannot widen the result beyond what AccessManager
 * already permits.
 *
 * <p>Default no-op bean in SpiDefaultsAutoConfiguration returns Optional.empty().
 *
 * <p>fetch plan is projection, not security.
 */
public interface ToolFetchPlanCustomizer {
    Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx);
}

// Source: new com.vn.agent.spi.FetchPlanContext (D-10)
package com.vn.agent.spi;

import com.vn.agent.orchestration.RunContext;
import org.springframework.security.core.userdetails.UserDetails;

/** Minimal context: RunContext (conversation id, run id, profile) + current user. */
public record FetchPlanContext(RunContext run, UserDetails user) {}

// Source: new resolver inside tools/fetchplan/. Phase 9 D-09 + D-11 + D-12.
@Component
public class ToolFetchPlanResolver {

    private final List<ToolFetchPlanCustomizer> customizers;
    private final FetchPlanIntersector intersector;
    private final FetchPlans fetchPlans;
    private final CurrentAuthentication currentAuthentication;

    public FetchPlan resolve(String toolName, MetaClass mc) {
        FetchPlan candidate = null;
        FetchPlanContext ctx = new FetchPlanContext(
                /* RunContext snapshot */ RunContext.snapshot(),
                currentAuthentication.getUser());
        for (ToolFetchPlanCustomizer c : customizers) {
            try {
                Optional<FetchPlan> override = c.overrideFor(toolName, mc, ctx);
                if (override.isPresent()) {
                    candidate = override.get();
                    break;   // first non-empty wins (D-09 defaults preserved)
                }
            } catch (RuntimeException ex) {
                log.warn("ToolFetchPlanCustomizer {} threw — falling back to default plan", c, ex);
                // Pitfall #4: customizer throw doesn't kill the tool call.
            }
        }
        if (candidate == null) {
            // D-13: default DATA fetch plan is _base (not _instance_name).
            candidate = fetchPlans.builder(mc.getJavaClass())
                    .addFetchPlan(FetchPlan.BASE)
                    .build();
        }
        return intersector.intersectWithAcl(candidate, mc, toolName);
    }
}
```

### TEST-08 Mock Variant (D-16, D-17)

```java
// Source: new PromptContractMockTest. Pattern derived from existing
// OrchestrationIntegrationTest.java + StubChatModelConfiguration + OutputScannerAdvisorTest.
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class PromptContractMockTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired CurrentAuthentication currentAuthentication;

    static Stream<Locale> locales() {
        return Stream.of(Locale.of("vi", "VN"), Locale.ENGLISH);
    }

    @ParameterizedTest @MethodSource("locales")
    void replyDoesNotLeakHostPrefixOrToolName(Locale locale) {
        systemAuthenticator.runWithSystem(() -> {
            // Force the locale via the test harness — see TestUsersConfiguration patterns.
            withLocale(locale, () -> {
                // The stub chat model is reconfigured (per-test override @Primary) to return
                // a leaky reply containing both jmixapp_Customer and find_records literal.
                ChatResponseDto resp = chatService.ask("test-user", null, "có bao nhiêu khách hàng?");
                // PROMPT-06 flag-and-audit posture: scanner promotes flag, content unchanged.
                assertThat(resp.flagged()).isTrue();
                assertThat(resp.flaggedPatternKey()).isIn("HOST_PREFIX_LEAK", "TOOL_NAME_LEAK");
                // Audit row exists with key only, never matched text.
                List<AiAuditEvent> flagged = dataManager.unconstrained()
                        .load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.outcome = :flag")
                        .parameter("rid", resp.runId())
                        .parameter("flag", AiToolCallOutcome.FLAGGED)
                        .list();
                assertThat(flagged).hasSize(1);
                assertThat(flagged.get(0).getDenialReason()).startsWith("flagged:");
            });
        });
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Throw `RuntimeException` from `@Tool` method to signal error to LLM | Return `JSON.stringify(ToolErrorDto)` from `@Tool` method (existing v1.0 pattern) | Spring AI 1.0+ | Cleaner — `DefaultToolExecutionExceptionProcessor` is for ungraceful failures only; structured business-logic errors should be modeled as tool result strings. v1.0 already does this; Phase 9 widens `expected[]` payload but doesn't change shape. |
| Reflective `@Comment` reads | `MetadataTools.getMetaAnnotationValue(mp, Comment.class)` | Jmix 2.0+ | Idiomatic, post-processor-aware, safe for multi-store. |
| `MessageAggregator.aggregateChatClientResponse(...)` for stream advisor aggregation | `ChatClientMessageAggregator().aggregateChatClientResponse(...)` | Spring AI 1.1 (verified Context7 upgrade-notes.adoc) | Phase 9 stays on call-only `OutputScannerAdvisor` — no streaming advisor change needed. Listed for forward-compat (Phase 12+ may revisit). |
| `MessageChatMemoryAdvisor.builder(chatMemory).order(int)` | Same | Stable in Spring AI 1.1.4 | Existing `ChatClientFactory` line 73 already uses this. |
| `ToolCallAdvisor.builder().advisorOrder(int)` | Same | Stable in Spring AI 1.1.4 | Note: setter named `.advisorOrder(int)`, NOT `.order(int)` — see existing `ChatClientFactory` line 79 + `ToolCallAdvisorBuilderConstants` Javadoc cross-ref. |

**Deprecated/outdated:**
- `BeanOutputParseException` — never existed in Spring AI 1.1.4 per `DefaultChatServiceImpl.java:101` Javadoc. Don't try to import.
- `ToolCallbacks.from(bean)` static helper — referenced in some old plans; replaced by `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` in 1.1.4 (verified at `AgentToolCallbacks.java:25-27`).
- Streaming `OutputScannerAdvisor` via `MessageAggregator` — superseded by `ChatClientMessageAggregator`; Phase 9 doesn't need this.

## Project Constraints (from CLAUDE.md)

| Directive | Phase 9 Compliance |
|-----------|---------------------|
| Java 21, Jmix 2.8, Spring Boot 3, Vaadin Flow, Gradle | Verified via build.gradle. No version drift. |
| `DataManager` only, no `EntityManager` | Phase 9 reads metadata only via `Metadata`/`MetaClass`/`MetadataTools` — no entity loads. SPI consumers eventually use `DataManager` (existing pattern in `BuiltInDataTools`). |
| `Metadata.create()` / `DataManager.create()`, no entity constructors | Phase 9 creates no entities. AUD-07 plumbing creates no rows. |
| Lombok forbidden on entities | Phase 9 adds zero entities. |
| Constructor injection for services | All new `@Component` classes use constructor injection — matches existing `BaselineContextProvider`/`BuiltInDataTools` patterns. |
| `@ConfigurationProperties` records under `ai-agent.*` | New properties under `ai-agent.prompt.*`, `ai-agent.guard.scanner.*`, `ai-agent.audit.*` per CONTEXT discretion. |
| Liquibase changelogs in root `changelog.xml` | Phase 9 adds zero changelogs (no entity, no DDL). |
| Messages in ALL locale files | New user-facing strings (if any beyond the procedural-hint English literals — see Pitfall 7) ship in `messages.properties` AND `messages_vi.properties`. The three `unknown_entity` procedural hints are tool-protocol English, NOT translated. |
| `UnconstrainedDataManager` for system-internal writes under jmix-security-data | AUD-07 hashing utility writes nothing in Phase 9. Phase 11 wires the caller (existing `AuditWriter` already uses `UnconstrainedDataManager` correctly per line 69-77). |
| `loadValue/loadValues` requires `.store("agentstore")` for AI entities | Phase 9 does no `loadValue` calls. Listed for completeness; existing `AuditWriter.findChatRootId` line 244-257 demonstrates pattern. |
| JetBrains MCP `get_file_problems` after Java work | Plans must include this as a verification step per phase. |
| Reuse Jmix built-ins over parallel layers | Don't Hand-Roll table enforces. |
| SPIs only for genuinely custom extensions | One new SPI in Phase 9 (`ToolFetchPlanCustomizer`) — has concrete consumer use case (host overrides `_base` per `(toolName, MetaClass)`). `AuditFieldHasher` deliberately NOT an SPI per MEMORY rule + CONTEXT D-18. |
| No abbreviated identifiers | `ToolFetchPlanCustomizer`, `FetchPlanIntersector`, `HostPrefixPatternProvider`, `AuditFieldHasher`, `EntityInventoryEntry` — all spell-checked. |
| Jmix-first UI over raw Vaadin | Phase 9 adds zero UI. |

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ + `@SpringBootTest` |
| Config file | None new — existing `AITestConfiguration` + `StubChatModelConfiguration` |
| Quick run command | `./gradlew :ai-agent:ai-agent:test --tests "*Phase9*"` (planner picks the naming pattern) |
| Full suite command | `./gradlew :ai-agent:ai-agent:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PROMPT-01 | `agent.entities` deterministic alpha render | unit | `./gradlew test --tests "*BaselineContextProviderTest*entityInventory*"` | ❌ Wave 0 (extend existing) |
| PROMPT-02 | `agent.permissions` JSON, locale-free, alpha-stable | unit | `./gradlew test --tests "*BaselineContextProviderTest*permissions*"` | ❌ Wave 0 |
| PROMPT-03 | System prompt rule string present | unit | `./gradlew test --tests "*DefaultChatServiceImpl*systemPrompt*"` | ❌ Wave 0 |
| PROMPT-04 | `<data entity="<label>" type="<internalName>">` shape | unit | `./gradlew test --tests "*ToolResultFormatterTest*records*"` | ✅ extend existing |
| PROMPT-05 | `unknown_entity` `expected[]` carries 3 hints in fixed order | unit | `./gradlew test --tests "*UnknownEntityRetryHintTest*"` | ❌ Wave 0 |
| PROMPT-06 | Host-prefix + tool-name patterns flag without blocking | unit corpus | `./gradlew test --tests "*HostPrefixLeakScannerTest*"` | ❌ Wave 0 |
| TOOL-09 | `describe_entity` returns all required fields via `MetadataTools` | unit | `./gradlew test --tests "*DescribeEntityPayloadTest*"` | ❌ Wave 0 |
| TOOL-10 | `ToolFetchPlanCustomizer` host bean is consulted; default empty preserved | integration | `./gradlew test --tests "*ToolFetchPlanResolverTest*"` | ❌ Wave 0 |
| TOOL-11 | Host plan referencing denied attr is pruned + audited PLAN_NARROWED | integration | `./gradlew test --tests "*FetchPlanIntersectorTest*"` | ❌ Wave 0 |
| TOOL-12 | Permission inventory at entity granularity reflects current user roles | integration | `./gradlew test --tests "*BaselineContextProviderTest*permissions*"` | ❌ Wave 0 (covers PROMPT-02) |
| TEST-08 | Prompt-contract regression VI + EN, mock-default + live-opt-in | integration + live | `./gradlew test --tests "*PromptContractMockTest*"` + `./gradlew :ai-agent:ai-agent:liveTest --tests "*PromptContractLiveTest*"` | ❌ Wave 0 |
| AUD-07 (partial) | `AuditFieldHasher` SHA-256 hex deterministic, UTF-8 bytes | unit | `./gradlew test --tests "*AuditFieldHasherTest*"` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew :ai-agent:ai-agent:test --tests "*<TouchedClass>*"` (~30s typical)
- **Per wave merge:** `./gradlew :ai-agent:ai-agent:test` (full suite, ~5–8min on local; CI ~10min)
- **Phase gate:** Full suite green + `./gradlew :ai-agent:ai-agent:liveTest --tests "*PromptContractLiveTest*"` green (manual, requires `OPENROUTER_API_KEY`).

### Wave 0 Gaps

- [ ] `tests/PromptContractMockTest.java` — covers TEST-08 default CI variant.
- [ ] `tests/live/PromptContractLiveTest.java` — covers TEST-08 `@Tag("live")` variant.
- [ ] `tests/tools/DescribeEntityPayloadTest.java` — covers TOOL-09.
- [ ] `tests/tools/UnknownEntityRetryHintTest.java` — covers PROMPT-05.
- [ ] `tests/tools/FetchPlanIntersectorTest.java` — covers TOOL-11.
- [ ] `tests/tools/ToolFetchPlanResolverTest.java` — covers TOOL-10.
- [ ] `tests/guard/HostPrefixLeakScannerTest.java` — covers PROMPT-06 host-prefix branch (corpus-driven).
- [ ] `tests/guard/ToolNameLeakScannerTest.java` — covers PROMPT-06 tool-name branch.
- [ ] `tests/audit/AuditFieldHasherTest.java` — covers AUD-07 partial.
- [ ] Extend `tests/orchestration/BaselineContextProviderTest.java` — adds PROMPT-01 + PROMPT-02 assertions.
- [ ] Extend `tests/tools/ToolResultFormatterTest.java` — adds PROMPT-04 `<data entity=>` shape assertion.
- [ ] Extend `tests/test_support/StubChatModelConfiguration.java` — add a builder/factory for scripted leaky replies (TEST-08 needs deterministic content).
- [ ] No framework install needed — JUnit 5 / `@SpringBootTest` / Mockito already on classpath.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes (transitive) | `CurrentAuthentication` for user identity (no Phase 9 change) |
| V3 Session Management | no | Phase 9 adds no session state |
| V4 Access Control | yes | `AccessManager.applyRegisteredConstraints` for entity + attribute checks (existing `CurrentUserSchemaAccess` pattern). Fetch-plan intersection reuses same checks. |
| V5 Input Validation | yes | LLM-supplied entity name → `Metadata.getClass(...)` whitelist (existing). LLM-supplied tool args → existing `FilterLiteralValueConverter`. Phase 9 adds no new LLM input surface beyond `unknown_entity` retry which is `expected[]` output, not input. |
| V6 Cryptography | yes (Phase 9 plumbing only) | `AuditFieldHasher` uses `MessageDigest.getInstance("SHA-256")` — JDK-stock, no third-party crypto. |
| V7 Error Handling and Logging | yes | `OutputScannerAdvisor` records pattern KEY only, never matched text (existing v1.0 D-18). New patterns inherit. `ToolUserError` → `ToolErrorDto` redacts stack traces (existing). |
| V8 Data Protection | yes | `agent.permissions` is an LLM-facing structured payload — labels are locale-resolved per request, structure (entity names) is not user-controlled. No PII in baseline. |
| V12 API and Web Service | yes (transitive) | `@Tool` boundary returns JSON only; Spring AI's tool-call ABI is the protocol. No raw HTTP exposure. |
| V14 Configuration | yes | New properties default to safe values: scanner patterns DEFAULT-ON (D-08); `ai-agent.audit.hashSensitiveFields=true` default (D-18); `sensitive-fields` empty default (operator opt-in via list). |

### Known Threat Patterns for Spring AI / Jmix

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| LLM-driven JPQL injection via entity-name parameter | Tampering | `Metadata.getClass(name)` whitelist (existing `BuiltInDataTools.java:271` — Phase 9 unchanged). |
| Output scanner ReDoS via host-supplied pattern | DoS | 8 KiB input cap (`OutputScannerAdvisor.MAX_SCAN_CHARS`) + bounded `\w+` quantifier + 200-prefix sanity check (Pitfall #5). |
| Fetch-plan widening past attribute permission | EoP | `FetchPlanIntersector` build-time prune + `PLAN_NARROWED` audit (TOOL-11/D-11/D-12). |
| Sensitive field leakage via prompt cache | Info disclosure | No cache in Phase 9 (deliberate). Phase 10's exposure-policy event will gate any future cache. |
| Stack-trace leakage via tool error | Info disclosure | `ToolErrorDto` shape never includes stack traces (existing `ToolUserError.toDto()` line 26). |
| Internal entity name / table prefix leakage to user | Info disclosure | `OutputScannerAdvisor` host-prefix pattern (PROMPT-06 / D-06) — flag and audit; system-prompt rule (PROMPT-03) instructs LLM not to emit. |
| Host customizer throwing leaks stack to LLM | Info disclosure | Try-catch in `ToolFetchPlanResolver.resolve(...)` returns to default; logs internally only (Pitfall #4). |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `MetadataTools.getMetaAnnotationValue(MetaProperty, Class<? extends Annotation>)` is the documented Jmix API for reading `@Comment` (and similar) on properties | Pattern 2 / TOOL-09 | Low — Context7 explicitly documents this method at `entities.html#@Comment > Usage and Tools`. If the exact signature is `getMetaAnnotationValue(MetadataObject, Class<? extends Annotation>)` (taking `MetaClass` OR `MetaProperty`), planner verifies via JetBrains MCP `get_file_problems` on first compile. |
| A2 | `messages.getMessage(EnumValueInstance)` overload is on the canonical `io.jmix.core.Messages` interface | Pattern 1 / D-04 | Low — Context7 `/jmix-framework/jmix-context7 message-bundles.html#Retrieve Localized Enum Value using Messages Interface` documents the overload. Verified. |
| A3 | `FetchPlan.getProperties()` returns an iterable of `FetchPlanProperty` objects each exposing `getName()` and `getFetchPlan()` for nested plans | Pattern 3 | Low — Public Jmix API used internally by every Jmix data store. If the exact iterator method is `getOwnProperties()` vs `getProperties()`, planner adjusts at compile time. Either way, the iteration logic in `FetchPlanIntersector` is unchanged. |
| A4 | `EntityAttributeContext.canModify()` is the boolean accessor returning whether the current user has UPDATE permission on the attribute | Pattern 1 / D-02 modifiable | Low — verified via Context7 + project memory `feedback_jmix_unconstrained_for_system_writes` mentions the same API surface. |
| A5 | Jmix 2.8 emits no public `MetadataChangedEvent` on metaclass mutation | D-06 host-prefix refresh | Low — even if it did, fallback is restart-only refresh which is acceptable per CONTEXT (metaclasses don't mutate at runtime in normal Jmix apps). |
| A6 | The 200-prefix sanity check threshold for the host-prefix regex is a reasonable upper bound | Pitfall #5 | Low — purely defensive. Largest known Jmix monoliths have <100 distinct table prefixes; 200 is a conservative ReDoS guard. |
| A7 | The three procedural `unknown_entity` hint strings are tool-protocol English, NOT user-facing UI text, and therefore do NOT live in `messages.properties` | D-14 / Pitfall #7 | Low — they are part of the tool-protocol JSON output the LLM consumes; the LLM itself produces the user-facing translation. Confirmed by D-14's "exact wording" framing. |
| A8 | Spring AI 1.1.4 delivers the JSON-string return value of a `@Tool` method to the model as a `tool` role message verbatim | Don't Hand-Roll table / Pattern: tool error contract | Low — verified via Context7 `/spring-projects/spring-ai/v1.1.2 tools.adoc` — `DefaultToolExecutionExceptionProcessor` handles only `ToolExecutionException` thrown out; normal `String` returns are passed through. v1.0 has been doing this for months. |

**If A1–A8 are wrong, the failure mode is a compile error or test failure on first run, NOT a silent security or correctness regression.** All assumptions are verifiable inside the IDE / first test run; none are deployed-in-production-and-broken risks.

## Open Questions Deferred to Plan

1. **Property key naming.** Planner picks under `ai-agent.prompt.*`, `ai-agent.guard.scanner.*`, `ai-agent.audit.*`. Suggested:
   - `ai-agent.prompt.entity-inventory.threshold` (default 100)
   - `ai-agent.guard.scanner.host-prefix-leak.enabled` (default true)
   - `ai-agent.guard.scanner.tool-name-leak.enabled` (default true)
   - `ai-agent.audit.hash-sensitive-fields` (default true)
   - `ai-agent.audit.sensitive-fields` (default empty list)

2. **Bean discovery model for `ToolFetchPlanCustomizer`.** Pitfall #9 recommends ordered `List<>` injection (mirrors `ToolContributor`). Planner finalizes.

3. **Test class organization for TEST-08.** Two classes (`PromptContractMockTest` + `PromptContractLiveTest`) is recommended over single parameterized class because `@Tag("live")` granularity is class-level in `ai-agent.gradle`.

4. **Caching the compiled host-prefix regex.** Eager (at `ApplicationReadyEvent`) recommended — startup cost is single metaclass scan; lazy adds first-request latency to the chat hot path.

5. **`describe_entity` excluded fields list.** Javadoc on `BuiltInDataTools.describeEntity` should enumerate at minimum: DDL column names, JPA fetch types, cascade rules, `@JmixGeneratedValue` strategy, store name, framework-managed audit columns (`createdBy`, `createdDate`, `lastModifiedBy`, `lastModifiedDate`, `version`, `deletedBy`, `deletedDate`). Planner finalizes the list during planning by sweeping the AI-agent's own `agentstore` entities for `@SystemLevel` patterns.

6. **`HostPrefixPatternProvider` placement.** Either in `guard/` (alongside `OutputScannerAdvisor`) or in a new `metadata/` cousin. Recommendation: `guard/` because it's a guard-rail concern; metadata-driven derivation doesn't make it a metadata package's responsibility.

7. **Whether `agent.entities` truncation note text becomes a new message key.** Recommendation: NO — it's tool-protocol-adjacent text consumed by the LLM, not user-facing UI. Hardcode the English. Mirrors A7 reasoning.

## Environment Availability

Phase 9 has no external environment dependencies beyond the existing v1.0 stack. No new `@ConditionalOnClass`, no new database, no new vendor dependencies.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 | Compilation | Verified (build.gradle line 56) | 21 | — |
| Jmix 2.8.1 | All work | Verified (build.gradle line 32) | 2.8.1 | — |
| Spring AI 1.1.4 BOM | Advisor + tool surface | Verified (build.gradle line 37) | 1.1.4 | — |
| Spring Boot 3.x | Autoconfig + properties | Transitive via Jmix 2.8 BOM | 3.x | — |
| `OPENROUTER_API_KEY` env var | TEST-08 `@Tag("live")` only | Operator-supplied | — | Skip live test (default CI excludes via `@Tag("live")` filter); mock variant always runs |
| pgvector / Postgres | Existing RAG (unchanged in Phase 9) | Existing | — | N/A — Phase 9 adds no RAG calls |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** Live test API key (handled by `@Tag("live")` exclusion).

## Sources

### Primary (HIGH confidence)
- `/jmix-framework/jmix-context7` — `data-model/metadata.html`, `data-model/entities.html` (`@Comment > Usage and Tools`), `localization/message-bundles.html` (Enum value localization), `data-access/fetching.html` (FetchPlanBuilder), `security/authorization.html` (AccessManager flow), `data-access/data-manager.html` (UnconstrainedDataManager).
- `/spring-projects/spring-ai/v1.1.2` — `api/advisors.adoc` (`CallAdvisor` + `StreamAdvisor` interfaces, `ChatClientMessageAggregator`), `api/tools.adoc` (`ToolExecutionExceptionProcessor`, `MethodToolCallback`, `@Tool returnDirect`), `api/advisors-recursive.adoc` (`ToolCallAdvisor.builder().advisorOrder(int)`), `upgrade-notes.adoc`.
- Project source — verified in this research session:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/CompiledOutputScannerPattern.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java`
  - `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java`
  - `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java`
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubChatModelConfiguration.java`
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java`
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java`
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java`
  - `ai-agent/build.gradle` (Spring AI / Jmix version pins)

### Secondary (MEDIUM confidence)
- Project memory rules — `feedback_reuse_jmix_builtins.md`, `feedback_jmix_unconstrained_for_system_writes.md`, `feedback_jmix_messages_over_spring.md`, `feedback_spi_baseline_builtin.md`, `feedback_no_abbreviations.md`. (Author authority HIGH; not Context7-verified per claim, but consistent with codebase.)

### Tertiary (LOW confidence)
- None — all critical claims have at least one Context7 source.

## Confidence Levels

| Area | Level | Reason |
|------|-------|--------|
| Standard Stack | HIGH | Versions verified directly in build.gradle. |
| Architecture (existing code shape) | HIGH | Read every relevant v1.0 source file. |
| `MetadataTools` API surface | HIGH | Context7 `/jmix-framework/jmix-context7` confirmed every accessor used in the plan. |
| Spring AI Advisor / tool error contract | HIGH | Context7 `/spring-projects/spring-ai/v1.1.2` confirmed `CallAdvisor`, `StreamAdvisor`, `ToolExecutionExceptionProcessor`, `MethodToolCallback`, `ChatClientMessageAggregator`. |
| `FetchPlan` introspection (`getProperties`, nested-plan walk) | MEDIUM | Verified that `FetchPlans.builder()` and `FetchPlan.BASE` exist; the exact name of the property iteration accessor (`getProperties()` vs `getOwnProperties()`) is best confirmed against the JmixCore JAR at first compile. Algorithm shape is unaffected. |
| Cache key strategy (locale-free) | HIGH | CONTEXT D-02 explicit; v1.0 `CurrentUserSchemaAccess` already encodes the no-cache decision. |
| Output-scanner pattern derivation | HIGH | Existing `OutputScannerAdvisor` + `AiAgentGuardProperties` shape supports trivial extension; corpus-driven test pattern already exists. |
| `ToolFetchPlanCustomizer` SPI design | HIGH | Mirrors `ToolContributor` precedent; CONTEXT D-09–D-13 fully locked. |
| `unknown_entity` retry contract observability | MEDIUM | The TEST-08 mock variant asserts the protocol; live behavior depends on the chosen LLM following procedural hints. CONTEXT D-16 splits exactly this risk: mock = deterministic; live = signal. |
| AUD-07 plumbing scope | HIGH | Pure utility + properties; no behavioral surface. CONTEXT D-18 fully locked. |
| Common pitfalls catalogue | HIGH | Each pitfall is rooted in either (a) Context7-documented behavior or (b) v1.0 source file inspection. Pitfall #5 (ReDoS at high prefix count) is the only one with a numerical guess (200 threshold) — flagged in Assumptions Log A6 as low-risk defensive. |

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — versions verified at `ai-agent/build.gradle:32-44`.
- Architecture: HIGH — every extension point verified by direct source read; existing v1.0 patterns (`BaselineContextProvider`, `CurrentUserSchemaAccess`, `OutputScannerAdvisor`, `BuiltInDataTools`, `ToolErrorDto`) cover ~90% of the surface.
- API surface: HIGH — Context7 confirmed all critical Jmix `MetadataTools` and Spring AI advisor / tool-error APIs.
- Pitfalls: HIGH — every pitfall traces to a CONTEXT decision or a verified API behavior.

**Research date:** 2026-04-27
**Valid until:** 2026-05-27 (Spring AI / Jmix versions are pinned and slow-moving; no major release predicted in 30 days. Re-verify if Spring AI 1.2 ships or Jmix 2.9 is announced.)
