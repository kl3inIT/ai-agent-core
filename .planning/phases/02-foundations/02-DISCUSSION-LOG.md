# Phase 2: Foundations - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in 02-CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-18
**Phase:** 02-foundations
**Areas discussed:** Liquibase layout, SPI defaults + layout, Exposure + role depth, ArchUnit organization

---

## Liquibase layout

### Q: How should the add-on's Liquibase changelogs be organized?

| Option | Description | Selected |
|--------|-------------|----------|
| Step-numbered, one file per entity | 010-ai-conversation.xml, 020-ai-message.xml, ... matching jmix-app | ✓ |
| Step-numbered, grouped by concern | 010-entities.xml, 020-chat-memory.xml, 030-pgvector.xml | |
| Hierarchical time-based | 2026/04/18-entities.xml | |

**User's choice:** Step-numbered, one file per entity (Recommended).

### Q: Where should the add-on's Liquibase master changelog live?

| Option | Description | Selected |
|--------|-------------|----------|
| In ai-agent functional module + auto-include | Auto-discovered via module.properties | ✓ |
| In ai-agent + host must manually include | Host edits its own changelog.xml | |

**User's choice:** In ai-agent functional module + auto-include (Recommended).

### Q: How should pgvector extension + vector store table be created?

| Option | Description | Selected |
|--------|-------------|----------|
| Liquibase <sql> + dbms="postgresql" preCondition | HSQLDB skips cleanly; PG-only DDL | ✓ |
| Skip pgvector in Phase 2 | Defer to Phase 5 (RAG Layer) | |

**User's choice:** Liquibase sql with preCondition on pg (Recommended). Satisfies ENT-04 while keeping HSQLDB tests green.

---

## SPI defaults + layout

### Q: Package layout for the 7 SPI interfaces in the functional module?

| Option | Description | Selected |
|--------|-------------|----------|
| Flat com.vn.agent.spi | All interfaces in one package | ✓ |
| Grouped by concern (spi.tool.*, spi.prompt.*, ...) | More structure | |

**User's choice:** Flat com.vn.agent.spi (Recommended).
**Notes:** Scope later reduced to 6 SPIs (D-05 drops EntityExposurePolicy).

### Q: How to register the "one no-op default bean per SPI"?

| Option | Description | Selected |
|--------|-------------|----------|
| Single @Configuration with @ConditionalOnMissingBean | One auto-config file in starter | ✓ |
| Per-SPI default class + @Component | 6 small classes, component-scanned | |
| Interface default methods only (no bean) | Cleaner code but fails success criterion #4 | |

**User's choice:** One @Configuration in starter with @ConditionalOnMissingBean (Recommended).

### Q: Which module(s) should expose the SPI interfaces?

| Option | Description | Selected |
|--------|-------------|----------|
| Functional module only | All interfaces in ai-agent; starter holds defaults | ✓ |
| Split by consumer | UI-adjacent SPIs later; all in functional now | |

**User's choice:** Functional module only (Recommended).

---

## Exposure + role depth

### User clarification (drove reformulation)

> "If the tool always executes through Jmix DataManager under the current user's security context, and we serialize results with Jmix security-aware serialization, then Jmix already enforces entity-, attribute-, and row-level access, so I don't think we should model attribute-level AI exposure on day 1 unless we explicitly want AI-specific rules that differ from normal user permissions."

> "My preference is to keep the model simple: the AI should be treated as just another client acting under the current user's Jmix security context. [...] not introduce a separate AI-specific exposure layer unless a real use case appears later. If that changes the current plan, please update the docs and requirements accordingly."

### Q: How far should we go in removing the AI-specific exposure layer?

| Option | Description | Selected |
|--------|-------------|----------|
| Full removal from v1 | Drop AiExposureRule, SPI-04, UI-07 entirely | ✓ |
| Keep SPI-04 as extension hook only | Interface ships, no entity/UI | |
| Keep everything, defer rule-backed impl | Entity + SPI + UI with no enforcement | |

**User's choice:** Full removal from v1 (Recommended). Drives D-05, D-10.

### Q: AiAgentUserRole + AiAgentAdminRole depth in Phase 2?

| Option | Description | Selected |
|--------|-------------|----------|
| Entity CRUD only | No attribute policies; @ViewPolicy deferred to Phase 7 | ✓ |
| Entity CRUD + @ViewPolicy stubs now | Pre-declare future view IDs | |

**User's choice:** Entity CRUD only (Recommended). Drives D-07.

### Q: SEC-04 conversation ownership scoping?

| Option | Description | Selected |
|--------|-------------|----------|
| Declarative row-level policy in AiAgentUserRole | DataManager enforces automatically | ✓ |
| Defer to Phase 4 service-layer filtering | ChatService manually filters | |

**User's choice:** Declarative row-level policy in AiAgentUserRole (Recommended). Drives D-08.

---

## ArchUnit organization

### User decision (drove scope drop)

> "Let's drop ArchUnit for now. It adds unnecessary scope and complexity to this milestone. We can enforce these constraints through code review and regular tests for now, and only bring in ArchUnit later if the rule set grows or we see the architecture starting to drift."

No options presented — user made a decisive scope reduction. Drives D-09, D-10 (TEST-06 dropped; TOOL-08 reframed for Phase 3).

---

## Claude's Discretion

- Exact Jmix row-level-policy API selection for D-08.
- Exact mechanism for auto-registering the add-on's master Liquibase changelog with the host.
- `AI_AGENT_*` column conventions (snake_case, audit fields).
- `@Composition` / `@OnDelete` relationships.
- Enum definitions (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome).
- Shape of SPI Javadoc.
- Bootstrap admin role seeding (likely deferred to jmix-app).

## Deferred Ideas

- AI-specific exposure layer (AiExposureRule / SPI-04 / UI-07) — reinstate if AI must see LESS than the user's Jmix permissions.
- ArchUnit rules — revisit when rule set grows or drift appears.
- `@ViewPolicy` — Phase 7 when views exist.
- `@EntityAttributePolicy` — per-entity when a sensitivity case lands.
- ENT-03 schema-version tracking for Spring AI chat-memory upgrades.
- SPI implementations beyond no-op — Phase 3–6.
