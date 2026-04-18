# Phase 2: Foundations - Context

**Gathered:** 2026-04-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Land the durable persistence + security + SPI-contract layer that phases 3–8 depend on. Specifically:

- **5 Jmix JPA entities** (scope-reduced from 6 — see D-05): `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument`. Each: UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok, instantiated via `Metadata.create()` / `DataManager.create()`.
- **Liquibase changelogs** with `AI_AGENT_*` table-name prefix, including Spring AI JDBC chat-memory DDL (ENT-03) and pgvector extension + `AI_AGENT_KB_VECTOR_STORE` (ENT-04) — pgvector wrapped in a `dbms="postgresql"` preCondition so HSQLDB test DB still boots.
- **2 resource roles** — `AiAgentUserRole` (chat access + row-scoped reads of own `AiConversation`/`AiMessage`) and `AiAgentAdminRole` (full CRUD on the 5 entities). Entity-level `@EntityPolicy` only; `@ViewPolicy` deferred to Phase 7.
- **6 SPI interfaces** (scope-reduced from 7 — see D-05): `ToolContributor`, `ContextContributor`, `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`. Interfaces only — no implementations. Each has a no-op default bean registered so host apps can inject them without NPE.
- **i18n** — `messages.properties` (EN) + `messages_vi.properties` (VI) for every entity and enum.

**In scope:**
- JPA entities + Liquibase (incl. chat-memory + pgvector DDL via preCondition).
- Resource roles with entity-level policies + row-level predicate for conversation ownership.
- SPI interfaces + no-op default beans via `@ConditionalOnMissingBean`.
- i18n for entities/enums in both locales.
- Propagating the scope reductions (5 entities, 6 SPIs, dropped UI-07, dropped ArchUnit/TEST-06) into `REQUIREMENTS.md`, `ROADMAP.md`, `PROJECT.md` as part of Phase 2 execution.

**Out of scope (explicit):**
- `AiExposureRule` entity / `EntityExposurePolicy` SPI-04 / `ExposureRuleListView` UI-07 — **removed from v1**; Jmix native security is authoritative (D-05).
- ArchUnit rules / TEST-06 — **dropped from v1**; code review + tests enforce the same constraints (D-09).
- SPI implementations beyond no-op defaults — Phase 3+ own the real impls.
- Metamodel scanner, tools, advisor chain, memory wiring, RAG, UI views — later phases.
- Mutation-tool scaffolding — later phase.
- `@EntityAttributePolicy` on any entity — no AI-specific sensitive attributes in MVP (D-07).

</domain>

<decisions>
## Implementation Decisions

### Liquibase Layout

- **D-01: Step-numbered Liquibase changelogs, one file per entity + one per non-entity concern.** Located under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/`. Expected files (planner may adjust numbering):
  - `010-ai-conversation.xml`
  - `020-ai-message.xml`
  - `030-ai-tool-call-audit.xml`
  - `040-ai-parameters.xml`
  - `050-ai-knowledge-document.xml`
  - `060-ai-chat-memory.xml` (Spring AI JDBC chat-memory schema)
  - `070-ai-kb-vector-store.xml` (pgvector extension + vector store table, `dbms="postgresql"` preCondition)

  Matches the existing `jmix-app` pattern (`010-init-user.xml`, `020-customer.xml`, …). Easy to diff, easy to add future migrations.

- **D-02: Master changelog lives in the functional module, auto-registered via Jmix.** `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml` includes each step file. Jmix auto-discovers add-on changelogs through the add-on's module properties — host apps do NOT need to hand-edit their own `changelog.xml`. Planner verifies the registration mechanism (probably via `module.properties` or the add-on's `@JmixModule` metadata) during research.

- **D-03: pgvector DDL ships in Phase 2 via a PostgreSQL-gated preCondition.** `070-ai-kb-vector-store.xml` uses a `<preConditions onFail="MARK_RAN" dbms="postgresql">` wrapper around both `CREATE EXTENSION IF NOT EXISTS vector` (raw `<sql>`) and the `AI_AGENT_KB_VECTOR_STORE` table DDL. HSQLDB test runs skip the changeset cleanly. RAG consumption happens in Phase 5; the table schema still lands here so ENT-04 is satisfied.

### SPI Interfaces + Defaults

- **D-04: Flat `com.vn.agent.spi` package in the functional module.** All 6 SPI interfaces live in `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/`:
  - `ToolContributor`
  - `ContextContributor`
  - `PromptContextContributor`
  - `ToolGuard`
  - `AuditListener`
  - `CustomIngester`

  Phase 2 ships interface signatures + Javadoc only. Method contracts documented but no impl beyond the no-op defaults in D-06.

  **(SPI-04 `EntityExposurePolicy` dropped — see D-05.)**

### Scope Reduction: No AI-Specific Exposure Layer

- **D-05: Drop `AiExposureRule` entity, `EntityExposurePolicy` SPI (SPI-04), and `ExposureRuleListView` (UI-07) from v1.** The AI agent is treated as just another client acting under the current user's Jmix security context. `AccessManager` + `DataManager` natively enforce entity-, attribute-, and row-level access; duplicating that in an AI-specific rule layer adds surface without solving a real problem.
  - **Reinstate trigger:** a concrete use case where a host wants the AI to see LESS than the user's own Jmix permissions (e.g., "user can view the `notes` field in the UI but the AI must not include it in tool output"). Until such a case surfaces, do not ship the layer.
  - **Consequences for the planner:**
    - Entity count: 6 → **5** (drop `AiExposureRule`).
    - SPI count: 7 → **6** (drop `EntityExposurePolicy`).
    - UI scope: drop UI-07 (`ExposureRuleListView`).
    - Phase 3 `TOOL-02` (effective per-user schema) computes directly from `AccessManager` — no `EntityExposurePolicy` chain.
    - `REQUIREMENTS.md`, `ROADMAP.md`, `PROJECT.md` must be updated during Phase 2 execution to reflect this (D-10).

- **D-06: Single `@ConditionalOnMissingBean` auto-config in the starter for SPI defaults.** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` declares 6 `@Bean @ConditionalOnMissingBean` methods, each returning a no-op default (anonymous implementations or tiny package-private static classes). Hosts override by declaring their own bean of the same type. The auto-config is registered in `AutoConfiguration.imports` alongside the existing `AIAutoConfiguration`.

### Security Roles

- **D-07: Entity-level `@EntityPolicy` CRUD only; no `@EntityAttributePolicy` in Phase 2.** No sensitive AI-specific attributes exist in MVP — attribute policies would be ceremony without protection. `AiAgentUserRole`: READ on `AiConversation`, `AiMessage` (scoped by D-08), no access to the other three entities. `AiAgentAdminRole`: full CRUD on all 5 entities.
  - `@ViewPolicy` **deferred to Phase 7** — views don't exist yet and view IDs will churn; pre-declaring stubs is wasted effort.

- **D-08: SEC-04 enforced declaratively via a row-level predicate on `AiAgentUserRole`.** Predicate: `AiConversation.createdBy == currentAuthentication.user.username` (exact Jmix row-level API — `@JpqlRowLevelPolicy` or equivalent — planner picks). `AiMessage` filters through `conversation` FK. `DataManager` applies the predicate automatically; Phase 4 `ChatService` inherits the enforcement without any service-level filtering code. Admin role bypasses (sees all).

### ArchUnit Scope Removal

- **D-09: ArchUnit dropped from v1 (`TEST-06` removed).** The constraints (`no EntityManager`, `no io.jmix..impl/..internal imports`, `no Lombok on entities`, `no save/remove in @Tool bodies`) are enforced by code review + regular unit/integration tests for now. Revisit only if (a) the rule set grows materially, or (b) architecture drift starts to appear.
  - **Consequences for the planner:**
    - `REQUIREMENTS.md` TEST-06 removed.
    - Phase 3 `TOOL-08` (read-only tool bodies) reframed: enforced via test-level assertion on tool-exposure registry, not ArchUnit.
    - No `archunit` dependency added to any module.

### Documentation Updates Required in Phase 2

- **D-10: Update `REQUIREMENTS.md`, `ROADMAP.md`, and `PROJECT.md` as part of Phase 2 execution.** These are planning-authoritative docs; they must reflect the scope reductions locked by D-05 and D-09 before Phase 2 is called complete. Specifically:
  - `REQUIREMENTS.md`: ENT-01 → 5 entities (remove `AiExposureRule`); drop SPI-04; drop UI-07; drop TEST-06; TOOL-08 rewording.
  - `ROADMAP.md`: Phase 2 deliverables list updated; Phase 7 UI list updated; Phase 3 TOOL-08 footnote.
  - `PROJECT.md`: remove "Additional AI exposure layer" bullet; SPI count 7 → 6; deferred-decisions log entry for exposure layer.
  - Commit as part of Phase 2 execution (not a pre-phase chore).

### Claude's Discretion

- Exact Jmix row-level-policy API selection for D-08 (`@JpqlRowLevelPolicy` vs `@PredicateRowLevelPolicy` vs programmatic `RowLevelPolicyContainer`) — planner reads current Jmix docs and picks.
- Exact mechanism for auto-registering the add-on's master Liquibase changelog with the host (D-02) — module property key name, configuration approach — planner verifies via Jmix docs/Context7.
- Naming and location of the `AI_AGENT_*` column conventions (snake_case widths, audit fields) — follow jmix-app conventions where possible.
- Whether `AiMessage` uses `@Composition` to `AiConversation` (likely yes — parent/child aggregate) — planner picks based on Jmix patterns.
- Exact shape of the SPI Javadoc (code examples in Javadoc vs pure API contract) — planner picks; aim for "host developer can integrate without reading source."
- Enum definitions (`AiMessageRole`, `AiKnowledgeDocumentStatus {PENDING, PROCESSING, READY, FAILED}`, `AiToolCallOutcome`) — planner defines based on downstream-phase needs documented in REQUIREMENTS.
- Which `@Composition` / `@OnDelete` / cascading rules apply where — planner picks per Jmix defaults.
- Seed data for `AiAgentAdminRole` assignment to the bootstrap admin (Phase 2 scope? or Phase 8?) — planner decides; default: no seed, admin manually assigns via Jmix role UI (success criterion #3).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 2 — Foundations" — goal + deliverables list (needs update per D-10).
- `.planning/REQUIREMENTS.md` — ENT-01..04, SEC-01..04, SPI-01..07, TEST-06 (see D-05, D-09, D-10 for scope changes).
- `.planning/PROJECT.md` — constraints, product context, Key Decisions (needs update per D-10: drop "Additional AI exposure layer").
- `.planning/STATE.md` — current status.
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` — prior context: 2-module shape, namespace, test strategy.
- `.planning/research/STACK.md` — Spring AI 1.0.2 versions, Jmix 2.8 / Boot baseline.
- `.planning/research/ARCHITECTURE.md` — target advisor chain (informational for Phase 2; no advisors here).
- `.planning/research/PITFALLS.md` — Liquibase collisions (#12), chat-memory schema porting, pgvector init.

### Project conventions
- `CLAUDE.md` — Jmix conventions (DataManager only, no Lombok on entities, `msg://` keys in both locales, `Metadata.create()` for entity instantiation, `get_file_problems` via JetBrains MCP).
- `ai-agent/ai-agent/build.gradle` and `ai-agent/build.gradle` — existing module deps; any new deps for chat-memory or pgvector go here.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — existing `@Configuration`; may need `@JmixModule` dependency extensions for security/data configurations.
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — register new `SpiDefaultsAutoConfiguration` here.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/` — canonical step-numbered Liquibase layout to mirror.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` — master changelog include pattern.

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-entities` — entity annotations, UUID/Version/InstanceName patterns.
- `jmix-liquibase` — changelog structure, ChangeSet conventions, dbms preConditions.
- `jmix-security-roles` — `@ResourceRole`, `@EntityPolicy`, row-level policy options.
- `jmix-enums` — enum patterns + i18n for enum values.
- `jmix-i18n` — messages.properties conventions.
- `jmix-services` — DataManager usage (for testing entity persistence).
- `jmix-testing` — `@SpringBootTest` patterns for entity/role smoke tests.

### External reference implementations (pattern source, NOT a dependency)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI + pgvector; inspect its Liquibase changelogs and vector-store table DDL for pattern reference on ENT-04.
- Spring AI 1.0.2 source: `org/springframework/ai/chat/memory/repository/jdbc/schema-*.sql` — port schema into our Liquibase for ENT-03 (chat-memory JDBC tables).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — existing `@Configuration`; extend `@JmixModule(dependsOn = {...})` to include Jmix `SecurityConfiguration`, `DataConfiguration`.
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — existing starter auto-config; `SpiDefaultsAutoConfiguration` is a sibling (separate class, same package) or the defaults can live inside `AIAutoConfiguration` (planner picks).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`, `messages.properties`, `module.properties` — Jmix add-on scaffolding; add Liquibase changelog pointer to `module.properties`, extend `messages.properties`.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/010-init-user.xml` — reference for Jmix `User`/role table DDL conventions (instance name, version column, etc.).

### Established Patterns
- **Namespace:** `com.vn.*` (kept per Phase 1). New packages: `com.vn.agent.entity`, `com.vn.agent.spi`, `com.vn.agent.security`, `com.vn.agent.enums` (or nested under entity).
- **Jmix projectId:** `'AI'` (root `jmix { projectId = 'AI' }`). Keep — this governs default metadata prefixes.
- **Table prefix:** `AI_AGENT_*` for all add-on tables (ENT-02). Hard requirement to avoid host collisions.
- **Liquibase layout:** step-numbered files in `src/main/resources/com/vn/agent/liquibase/changelog/`; master `changelog.xml` in `com/vn/agent/liquibase/`.
- **Test stack:** JUnit 5 + `@SpringBootTest` + Mockito; HSQLDB at test runtime (Phase 1 baseline). pgvector changelog MUST skip on HSQLDB via `dbms="postgresql"` preCondition.
- **Messages:** `messages.properties` (EN default) + `messages_vi.properties` (VI). Every entity display name, every enum value, every `@InstanceName` references a key.

### Integration Points
- `jmix-app` — boot test: fresh HSQLDB DB → Liquibase creates all 5 entities + chat-memory tables (pgvector skipped cleanly). `AiAgentAdminRole` must be assignable from the Jmix role admin UI (success criterion #3).
- `ai-agent-starter` `AutoConfiguration.imports` — register `SpiDefaultsAutoConfiguration`.
- Jmix add-on Liquibase registration — likely via `module.properties` key (`jmix.liquibase.changelog`); planner confirms the exact key during research.

</code_context>

<specifics>
## Specific Ideas

- **Treat AI as just another Jmix client.** Do not build a parallel AI-specific security/exposure layer. Jmix `AccessManager` + `DataManager` are the single source of truth. (Memory: `feedback_ai_as_jmix_client.md`.)
- **Code review + tests over ArchUnit for MVP.** Don't add static-enforcement tooling until the rule set outgrows review capacity. (Memory: `feedback_no_archunit.md`.)
- **Step-numbered Liquibase, one file per entity.** Mirror jmix-app exactly.
- **Single auto-config for SPI defaults.** One file, 6 `@Bean @ConditionalOnMissingBean` methods.
- **`@EntityPolicy` CRUD only — no attribute or view policies in Phase 2.** Phase 7 adds `@ViewPolicy`; attribute policies wait for a concrete sensitivity need.
- **Row-level predicate (declarative) for SEC-04.** ChatService inherits enforcement for free in Phase 4.
- **pgvector DDL ships in Phase 2** (satisfies ENT-04) but under a `dbms="postgresql"` preCondition so HSQLDB dev/test still boots.
- **Spring AI chat-memory schema ported, not generated.** `spring.ai.chat.memory.repository.jdbc.initialize-schema: never` in `application.yaml` of `jmix-app`.

</specifics>

<deferred>
## Deferred Ideas

- **AI-specific exposure layer** (`AiExposureRule` entity, `EntityExposurePolicy` SPI, `ExposureRuleListView` UI) — dropped from v1 per D-05. Reinstate only when a concrete "AI must see less than the user" use case surfaces.
- **ArchUnit rules** (TEST-06 constraints as static enforcement) — dropped from v1 per D-09. Revisit when rule set grows or drift appears.
- **`@ViewPolicy` declarations** for chat / parameters / KB / audit views — Phase 7 (views don't exist yet).
- **`@EntityAttributePolicy` on any entity** — no sensitive AI-specific attributes in MVP; revisit per-entity if a sensitivity case lands.
- **ENT-03 schema-version tracking** — if Spring AI milestone versions shift the chat-memory schema, we may need a versioning/migration strategy. Phase 2 ports the 1.0.2 schema; upgrades are a later concern.
- **SPI implementations beyond no-op** — tool/audit/guard/ingester impls are Phase 3–6 work.
- **Bootstrap admin seeding** — optional Liquibase seed assigning `AiAgentAdminRole` to the default `admin` user. Not needed for success criteria; can ship later or remain a jmix-app-only seed.

</deferred>

---

*Phase: 02-foundations*
*Context gathered: 2026-04-18*
