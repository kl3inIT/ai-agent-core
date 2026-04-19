# Jmix AI Copilot (ai-agent-core)

## What This Is

A reusable, enterprise-grade AI Copilot add-on for Jmix applications. Plug it into any Jmix 2.8+ app and it immediately understands the host's data model (via Jmix metamodel), answers questions through chat with tool calls over `DataManager`, grounds responses in uploaded business documents via RAG, and ships a built-in Flow UI (chat, conversations, parameters, knowledge base, audit). Hosts extend it through SPIs — custom tools, prompts, context providers, guards, custom ingesters, and audit listeners — without forking. (AI-specific exposure-policy SPI dropped per D-10; Jmix `AccessManager` is authoritative.)

## Core Value

**Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.**

If everything else fails, the MVP's read-only Q&A-over-host-entities-plus-documents flow must work with Jmix security enforced end-to-end.

## Requirements

### Validated

(None yet — shipping MVP will validate)

### Active

<!-- All Active requirements are hypotheses until shipped and validated. -->

**Add-on packaging & integration**
- [ ] Ship as standard Jmix add-on with modules: `ai-agent` (functional) + `ai-agent-starter` today; `ai-agent-flowui` + `ai-agent-flowui-starter` split **deferred** per [D-01](phases/01-walking-skeleton/01-CONTEXT.md) until a named REST-only consumer use case surfaces
- [ ] Works plug-and-play: host adds starter dep → chat view, KB admin, audit log appear in menu with sensible defaults
- [ ] Spring Boot auto-configuration wires Spring AI 1.1.4 primitives (ChatClient, advisors, VectorStore, ChatMemory) — version pinned per D-10 (Phase 02)
- [ ] Demo host app (`jmix-app/` with Customer/Order) doubles as integration-test harness

**Metadata-first runtime**
- [ ] Scan Jmix `Metadata`/`MetaClass`/`MetaProperty` on startup → build internal agent schema
- [ ] Auto-generate 6 generic read-only tools: `list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`
- [ ] All structured-data access flows through `DataManager` (inherits Jmix entity, attribute, row-level security)
- [ ] Authorization flows exclusively through Jmix `AccessManager` + `DataManager` — no parallel AI-only exposure layer shipped in v1 (per D-10; revisit if a concrete consumer need surfaces)

**Spring AI 1.1.4 integration**
- [ ] Use official primitives only: `ChatClient.builder(...).defaultAdvisors(...)`, `MessageChatMemoryAdvisor`, RAG advisors, `ToolCallAdvisor`, `.entity(...)` structured output, `StructuredOutputValidationAdvisor` where applicable (existence in 1.1.4 to be verified in Phase 6 per research flag)
- [ ] Default provider: OpenAI-compatible via OpenRouter (reuse pattern from `D:/ai/traffic-law-chatbot`); provider layer extensible for other vendors
- [ ] Do not assume every model supports native structured output — degrade gracefully

**Hybrid orchestration**
- [ ] Conversation state via chat memory (JDBC-backed so host can inspect/retain)
- [ ] Unstructured knowledge via pgvector RAG layer (admin uploads PDF/MD/TXT/HTML)
- [ ] Agent plans actions via tool calling; final answers grounded in both structured tool results and retrieved documents
- [ ] Vector DB never replaces `DataManager` as transactional truth

**Built-in Flow UI (v1 scope)**
- [ ] Chat view (end-user conversational UI; shows tool calls transparently; streams when supported)
- [ ] Conversations list + replay
- [ ] Agent parameters admin (model, temperature, system prompt, enabled tools; multiple profiles with one active)
- [ ] Knowledge base admin (upload/list/delete documents, trigger reingest)
- [ ] Tool-call audit log (searchable, per-user, inputs/outputs)

**Access & security**
- [ ] Any authenticated Jmix user can use Chat view by default (host can tighten via role assignment)
- [ ] `AiAgentAdminRole` gates Parameters / KB / Audit views (Exposure view dropped per D-10)
- [ ] All tool calls audited to host DB as Jmix JPA entity (`AiToolCallAudit`) via DataManager — queryable, exportable, secured

**SPI extension points**
- [ ] Tool contributors — hosts add domain-specific `@Tool`-annotated beans
- [ ] Context contributors — inject extra context (user profile, tenant, env) into prompts
- [ ] Prompt/instruction contributors — augment system prompt per-deployment
- [ ] Guard/policy hooks — veto tool calls before execution (rate limits, domain rules)
- [ ] Audit/run listeners — side-channel observability (Slack, SIEM, metrics)

**Safety posture (v1)**
- [ ] Read-only by default — no create/update/delete tools shipped enabled
- [ ] Mutation-tool framework scaffolded but disabled; opt-in in later phase with dry-run + confirmation
- [ ] Every tool invocation auditable; cannot be silently disabled

**Testing**
- [ ] Unit tests for metamodel scanner, schema generator, tool generator, audit persistence (exposure-policy tests dropped per D-10)
- [ ] Spring integration tests (`@SpringBootTest`) verifying advisor wiring, retrieval context, DataManager-backed security behavior against the demo host app
- [ ] Follow official Spring AI test patterns (avoid brittle exact-text assertions on LLM output; use semantic similarity or structured-output checks)
- [ ] Live-model tests opt-in (`@Tag("live")`) and excluded from default CI

### Out of Scope

- **Autonomous multi-step agents (v1)** — keep orchestration to single-turn tool calling + RAG; autonomous loops deferred to avoid over-engineering and safety complexity
- **Mutation tools enabled by default** — write access is dangerous and host-specific; ship scaffolded SPI, enable in a later phase
- **Auto-ingesting host entity records into the vector store** — freshness/auth complexity outweighs MVP value; `DataManager` is the source of truth for structured data
- **URL/web crawling ingestion (v1)** — defer to post-MVP; file upload covers enterprise document flows
- **Non-OpenAI-compatible provider SDKs in v1** — Anthropic/Gemini/Ollama accessible via OpenRouter or swappable `ChatModel` bean, but no native starters shipped in v1
- **Jmix internal-API dependencies** — public APIs only, even when internals would be more convenient
- **Universal-agent positioning / generic LLM framework** — this is specifically a Jmix copilot add-on, not a standalone agent platform
- **Custom vector-store abstractions** — use Spring AI `VectorStore` directly; don't wrap

## Context

**Existing repo structure (brownfield):** Composite Gradle build with two includeBuilds —
- `ai-agent/` — the add-on source (modules: `ai-agent` functional, `ai-agent-starter` auto-config). Flow UI modules not yet added.
- `jmix-app/` — demo host (Customer + Order sample), HSQLDB, Vaadin Flow UI, currently consumes the add-on via `com.vn:ai-agent-starter`.

**Reference implementations (for pattern-learning only, not dependencies):**
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI 1.1.x + pgvector + Flow UI admin views (Chat / Parameters / VectorStore / Answer checks). Same shape we want for admin UI, but domain-specific (Jmix docs Q&A). We generalize.
- `D:/ai/traffic-law-chatbot` — Spring AI + OpenRouter wiring pattern (OpenAI starter with custom `base-url`, per-request model selection via `ChatOptions`).

**Ecosystem realities:**
- Spring AI 1.1.4 is a milestone release — APIs and starter coordinates are still shifting; research must verify current syntax via Context7/official docs, not training data.
- Jmix 2.8 uses Spring Boot 3, Java 17, Vaadin Flow — matches Spring AI's Boot 3 requirement.
- `DataManager` fluent API is the only supported entry point for secured data access; `EntityManager` bypasses Jmix security and is explicitly forbidden in this codebase (see `CLAUDE.md`).

**Why now:** Jmix lacks a first-party AI copilot. Customer enterprise Jmix apps increasingly want "ask your data" UX, but rolling it safely is expensive. A reusable metadata-first add-on lets every Jmix app get a governed copilot with minimal custom code.

## Constraints

- **Tech stack**: Jmix 2.8 + Spring Boot 3 + Vaadin Flow + Java 17 — fixed by host ecosystem
- **Spring AI version**: 1.1.4 — pinned via BOM (upgraded between Phase 1 wave start and Phase 2 start per D-10; STACK.md updated accordingly)
- **Vector store default**: pgvector — reuses Postgres infra familiar to Jmix enterprise deployments
- **Data access**: `DataManager` only — `EntityManager` forbidden by project conventions and breaks Jmix security model
- **Entities**: No Lombok on entities; UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`; instantiate via `Metadata.create()` / `DataManager.create()`
- **Security**: Jmix roles + data security is the single enforcement layer (per D-10 / MEMORY "AI is just another Jmix client"). No AI-specific exposure layer in v1; revisit only if a concrete "AI must see less than user" use case surfaces.
- **Packaging**: Must be distributable as Maven artifacts; no internal Jmix APIs; starter auto-configuration conventions
- **Safety**: Read-only default; all tool calls auditable; mutations require explicit host opt-in
- **Testing**: Live LLM tests must be opt-in and excluded from default CI (cost + flakiness)
- **UI**: Vaadin Flow server-side; all labels via `msg://` keys in `messages*.properties` (per `CLAUDE.md`)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Keep existing two-sided repo: `ai-agent/` add-on + `jmix-app/` host | Repo already formalizes the add-on / consumer split; demo host doubles as integration-test harness | — Pending |
| Spring AI 1.1.4 with official primitives only (upgraded per D-10) | Product spec mandate; avoid custom abstractions so Spring AI upgrades stay cheap | — Pending |
| OpenAI-compatible provider (via OpenRouter) as MVP default | Matches reference projects; one starter covers many models; per-request model switching via `ChatOptions` | — Pending |
| pgvector as default vector store | Reuses Postgres familiar to Jmix enterprise; Spring AI first-class support; matches reference | — Pending |
| Audit records as Jmix JPA entity in host DB | Queryable via DataManager, visible in Jmix admin UI, inherits Jmix security; exposable via SPI listener for side-channels | — Pending |
| Read-only MVP (6 generic tools, no mutations) | Safety + scope control; mutation SPI scaffolded for later opt-in | — Pending |
| MVP UI: Chat + Conversations + Parameters + KB + Audit | Full admin suite modeled on `jmix-ai-backend` reference; "plug and play" requires no external tools | — Pending |
| Any authenticated user gets Chat; admin role gates settings | Low friction for end-users; safe defaults for governance | — Pending |
| File upload only for KB ingestion in v1 | Covers enterprise doc flows; URL crawling/entity auto-ingest deferred | — Pending |

### Deferred Decisions

- **Split add-on into 4 modules** (`ai-agent-flowui` + `ai-agent-flowui-starter` alongside existing `ai-agent` + `ai-agent-starter`) — was Key Decision #2. **Deferred** per [D-01 in Phase 1 CONTEXT](phases/01-walking-skeleton/01-CONTEXT.md). Trigger: a named REST-only consumer use case that cannot accept Vaadin deps. Until then, `ai-agent-starter` ships with UI and PKG-04 (zero-Vaadin functional module posture) remains open.

- **Ship `AiExposureRule` entity + `EntityExposurePolicy` SPI + `ExposureRuleListView`** — was part of v1 scope. **Deferred** per [D-10 in Phase 2 CONTEXT](phases/02-foundations/02-CONTEXT.md). Trigger: a concrete consumer case where Jmix `AccessManager` + `DataManager` row-/attribute-level policies are insufficient to constrain what the agent sees. Until then, authorization is the host's existing Jmix security stack (per MEMORY note "AI is just another Jmix client").

- **ArchUnit enforcement of layering / `.impl.` imports / `no DataManager.save in @Tool`** (TEST-06, parts of TOOL-08) — was part of v1 scope. **Deferred** per [D-10 in Phase 2 CONTEXT](phases/02-foundations/02-CONTEXT.md) per MEMORY note "Avoid ArchUnit until drift". Code review + targeted unit-test conventions remain authoritative until rule drift justifies ArchUnit.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-18 after Phase 02 planning — D-10 applied*
