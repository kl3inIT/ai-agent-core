# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-27
**Phase:** 09-tool-layer-foundations-prompt-contract-hardening
**Areas discussed:** Prompt rendering & schema shape, OutputScanner pattern derivation,
ToolFetchPlanCustomizer SPI surface, TEST-08 harness + AUD-07 plumbing scope, follow-up on
ToolErrorDto.expected payload shape

---

## Area selection (initial)

| Option | Description | Selected |
|--------|-------------|----------|
| Prompt rendering & schema shape | `agent.entities` / `agent.permissions` format, truncation threshold, `describe_entity` field rendering, exclusions | ✓ |
| OutputScanner pattern derivation | Dynamic vs static host-prefix patterns, tool-name list source, default scanner pack | ✓ |
| ToolFetchPlanCustomizer SPI surface | `FetchPlanContext` payload, intersection mechanism, denied-attr failure mode, `_instance_name` scope | ✓ |
| TEST-08 harness + AUD-07 plumbing scope | Mock vs live ChatModel, locale parameterization, AUD-07 P9 vs P11 split | ✓ |

**User's choice:** All four areas selected for discussion.

---

## Prompt rendering & schema shape

### Q1: How should `agent.entities` render in the system prompt?

| Option | Description | Selected |
|--------|-------------|----------|
| `name (label)` per line | Alpha-sorted single multi-line value under one key; lean tokens, predictable hash | ✓ |
| JSON array of `{name, label}` | Structured but heavier; risks LLM treating it as tool input | |
| Localized label first, name in parens | `Khách hàng (jmixapp_Customer)` — emphasizes label; raises label-as-tool-arg risk | |

**User's choice:** `name (label)` per line.

### Q2: Where should `agent.permissions` live in the prompt and how compact?

| Option | Description | Selected |
|--------|-------------|----------|
| JSON object, single key | `{"<entity>":{"r":1,"u":1,"c":0,"d":0,"modifiable":[...]}}`; deterministic ordering, omits all-zero entries | ✓ |
| Flat `key=value` lines | Readable but blows up linearly with entity count | |
| Compact single-line per entity | Middle ground; less parseable than JSON, more compact than flat keys | |

**User's choice:** JSON object, single key.

### Q3: Default truncation threshold for `agent.entities`?

| Option | Description | Selected |
|--------|-------------|----------|
| 50 entities, configurable | Matches PROMPT-01's stated default | |
| 100 entities (more headroom) | Symmetry with `TOOL-06` find_records max=100 | ✓ |
| Token-budget-aware truncation | More precise but introduces token-estimator dependency | |

**User's choice:** 100 entities (configurable).

### Q4: How should richer `describe_entity` render relationship cardinality and enum values (TOOL-09)?

| Option | Description | Selected |
|--------|-------------|----------|
| Jmix enum names, locale-resolved labels for enumValues | Raw Jmix enum strings + name+label enums + name+label relationship targets + raw `@Comment` | ✓ |
| Human-rendered cardinality, enum constants only | Saves tokens, loses information for user-facing translation | |
| Full Jmix shape, raw types, no localization | Simplest impl but breaks PROMPT-03 for enum-typed fields | |

**User's choice:** Jmix enum names + locale-resolved enumValues + name+label relationshipTarget + raw `@Comment`.

### Q5: How to surface the `describe_entity` exclusions list?

| Option | Description | Selected |
|--------|-------------|----------|
| Javadoc on `BuiltInDataTools.describeEntity` only | Reviewers see it; LLM doesn't pay tokens | ✓ |
| Top-level `_excluded` field in JSON payload | Visible to LLM; risks LLM guessing values for excluded fields | |
| Both: Javadoc + tool description note | Tightest signal but two parallel sources to keep in sync | |

**User's choice:** Javadoc only.

---

## OutputScanner pattern derivation

### Q1: How should the host-prefix (internal entity-name) leakage pattern be sourced?

| Option | Description | Selected |
|--------|-------------|----------|
| Dynamic — derived at startup from MetaClass scan | Single regex over distinct prefix tokens; adapts per host; aligns with Phase 3 D-11 | ✓ |
| Static configurable list | Predictable, host-controlled; creates drift risk | |
| Both — dynamic seed + config override | Most flexible; slight implementation cost | |

**User's choice:** Dynamic from MetaClass scan.

### Q2: Which tool names go on the leakage list?

| Option | Description | Selected |
|--------|-------------|----------|
| Built-ins + RETRIEVAL + dynamically-discovered ToolContributor names | Closes leakage loop end-to-end including host tools | ✓ |
| Built-ins only | Simpler; hosts add their own via config | |
| Built-ins + generic regex | Catches naming convention but risks over-flagging | |

**User's choice:** Built-ins + RETRIEVAL + ToolContributor names.

### Q3: Default scanner-pack delivery?

| Option | Description | Selected |
|--------|-------------|----------|
| Ship enabled-by-default in starter | Matches v1.0 default-on safety posture; flag-and-audit-only | ✓ |
| Ship disabled, opt-in per-pattern | Safer first ship but two consumer steps for protection | |
| Ship disabled, flip default-on in v1.2 | Lowest blast radius but weakens TEST-08 default CI | |

**User's choice:** Default-on, opt-out via property.

---

## ToolFetchPlanCustomizer SPI surface

### Q1: What goes in `FetchPlanContext`?

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal: RunContext + current user | `record FetchPlanContext(RunContext, UserDetails)`; matches MEMORY narrow-SPI rule | ✓ |
| Plus original requested attributes / filter | Richer but multiplies churn surface across P10/P11 | |
| Plus a `purpose` enum | Less coupling to tool-name strings but premature abstraction | |

**User's choice:** Minimal RunContext + UserDetails.

### Q2: How is the per-attribute intersection enforced?

| Option | Description | Selected |
|--------|-------------|----------|
| Build-time prune | Walk host plan recursively, drop denied attrs before DataManager load | ✓ |
| Post-load filter | Loads denied columns into JVM; risks leakage via JPA listeners | |
| Reject the call | Strictest but destabilizes LLM flow across users | |

**User's choice:** Build-time prune.

### Q3: Failure mode when host plan references denied attributes?

| Option | Description | Selected |
|--------|-------------|----------|
| Silently drop + audit-log | `outcome=PLAN_NARROWED`; LLM never sees error; matches v1 opacity (P-13) | ✓ |
| Silent drop, no audit | Cheapest; operators have no signal of drift | |
| Drop + warn-log to slf4j only | Visible in app logs but not queryable in audit tree | |

**User's choice:** Silent drop + audit-log.

### Q4: Does the SPI override `_instance_name`?

| Option | Description | Selected |
|--------|-------------|----------|
| Data plan only (recommended) | `_instance_name` stays add-on-default; hosts use Jmix `@InstanceName` for label changes | ✓ |
| Both via two methods | Maximum flexibility; richer SPI surface | |
| Both via single method with `purpose` discriminator | Lighter than two methods but couples concerns | |

**User's choice:** Data plan only.
**Notes:** User wording clarification: the add-on default DATA fetch plan is `_base` (not `_instance_name` — `_instance_name` is purely the label projection). `ToolFetchPlanCustomizer` overrides the data fetch plan ONLY. Hosts needing different labels model that via Jmix `@InstanceName` / instance-name configuration, not through this SPI.

---

## TEST-08 harness + AUD-07 plumbing scope

### Q1: How should the TEST-08 prompt-contract test execute?

| Option | Description | Selected |
|--------|-------------|----------|
| Mock-scripted in default CI + opt-in `@Tag("live")` | Default CI deterministic + free; live test is the regression bar | ✓ |
| Mock-only | Cheap maintenance; doesn't prove prompt rule changes model behavior | |
| Live-only | Highest signal but flaky and CI-default has zero coverage | |

**User's choice:** Mock + `@Tag("live")` opt-in.

### Q2: Locale parameterization for TEST-08?

| Option | Description | Selected |
|--------|-------------|----------|
| JUnit5 `@ParameterizedTest` over VI + EN | Single test method, two assertion runs; matches Jmix patterns | ✓ |
| Two test classes, one per locale | Self-contained but verbose | |
| Single locale (English) only | Cuts CI default time; risks locale-sensitive PROMPT-04 regressions | |

**User's choice:** JUnit5 `@ParameterizedTest` over VI + EN.

### Q3: What AUD-07 plumbing actually ships in Phase 9?

| Option | Description | Selected |
|--------|-------------|----------|
| Hashing utility + property registered, no caller wired | Phase 11 wires call sites; hosts see property surface but no behavior change until P11 | ✓ |
| Hashing utility only (no property) | Defer property to P11; smaller P9 PR | |
| Defer all of AUD-07 to Phase 11 | Removes AUD-07 line from Phase 9 entirely; contradicts ROADMAP "partial — plumbing prepared" | |

**User's choice:** Utility + property registered, no caller wired.

### Q4: Hash function: where does it live?

| Option | Description | Selected |
|--------|-------------|----------|
| Static utility in `com.vn.agent.audit.AuditFieldHasher` | Stateless; SPI later if a concrete host case appears | ✓ |
| SPI from day one | Open to extension immediately; violates MEMORY narrow-SPI rule | |
| Method on existing `AuditWriter` bean | One fewer class but couples concerns | |

**User's choice:** Static utility (`AuditFieldHasher`).

---

## Follow-up — ToolErrorDto.expected payload shape

### Q1: How should `ToolErrorDto.expected` carry the `unknown_entity` retry hint?

| Option | Description | Selected |
|--------|-------------|----------|
| Keep `List<String>`, encode as procedural strings | No DTO churn; existing call sites unaffected; LLM reads natural-language steps | ✓ |
| Add structured `expectedAction` sibling field | Richer but two parallel sources of truth | |
| Replace `expected` with `expectedActions: List<ActionHint>` | Highest signal but breaks every existing call site (Phase 3 D-07 reshape) | |

**User's choice:** Keep `List<String>`, procedural strings.

### Q2: What goes into the `expected` array for `unknown_entity` specifically?

| Option | Description | Selected |
|--------|-------------|----------|
| Three explicit hints in this exact order | "call list_entities exactly once" / "if match, retry with that exact name" / "if no match, tell user no such entity exists — do not guess" | ✓ |
| One single hint string | Minimal tokens; compresses negative path | |
| Two hints — happy path + fallback | Middle ground; loses "exactly once" / "do not guess" wording | |

**User's choice:** Three explicit hints in fixed order.

### Q3: Where does the `unknown_entity` retry rule also live?

| Option | Description | Selected |
|--------|-------------|----------|
| Both: `expected` array + system prompt rule in `DefaultChatServiceImpl` | Belt-and-suspenders; covers very-first-turn case before any tool fires | ✓ |
| System prompt rule only | Single source; risks LLM forgetting after long tool chains | |
| `expected` payload only | Saves tokens but unspecified BEFORE first tool call — defeats prevention goal | |

**User's choice:** Both — `expected` array + system prompt rule.

---

## Claude's Discretion

Captured under `<decisions>` § "Claude's Discretion" in `09-CONTEXT.md`. Highlights:
property keys, internal record/DTO names, helper-class placement for the fetch-plan
intersector, bean discovery model for `ToolFetchPlanCustomizer` (single vs ordered list),
test class organization for TEST-08.

## Deferred Ideas

Captured under `<deferred>` in `09-CONTEXT.md`. Highlights: `LlmExposurePolicy`
substitution (P10), mutation surface (P11), `AuditFieldHasher` SPI extraction (no concrete
case yet), STT/extraction/surfaces (P12-P14), v1.2 collapsible tool-detail panel,
`MetadataChangedEvent` regex refresh, token-budget-aware truncation, structured
`expectedAction` DTO field.
