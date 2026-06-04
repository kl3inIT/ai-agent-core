<!-- refreshed: 2026-06-04 -->
# Architecture

**Analysis Date:** 2026-06-04

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    Vaadin Flow UI (view/)                                 │
│  ChatView / ChatDialogView / AiAgentSidebarView · admin (parameters,      │
│  exposure, knowledge, audit, configuration, diagnostics)                  │
│  `view/chat/ChatView.java`            `view/**`                           │
└───────────────────────────────┬───────────────────────────────────────────┘
                                 │ ask(...) / askTyped(...) → Flux<StreamingEvent>
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│            Orchestration — ChatService turn engine                        │
│  `DefaultChatServiceImpl.java`  `orchestration/RunContext.java`           │
│  ConversationGateway · SystemPromptComposer · ChatClientFactory ·         │
│  AiParametersResolver · AiUiSettingsResolver · StreamingSinkHolder        │
└───────┬───────────────────────┬─────────────────────┬─────────────────────┘
        │ guard preamble        │ tool callbacks       │ RAG advisor
        ▼                       ▼                      ▼
┌──────────────────┐  ┌──────────────────────┐  ┌──────────────────────────┐
│  guard/          │  │  tools/              │  │  rag/                     │
│ RateLimitGuard   │  │ AgentToolCallbacks   │  │ RetrievalAugmentation-    │
│ TokenBudgetGuard │  │ BuiltInDataTools     │  │  AdvisorFactory           │
│ IterationCounter │  │ tools/link, mutation │  │ AuditingDocumentRetriever │
│ OutputScanner-   │  │ ActionProposalTool   │  │ (pgvector via agentstore) │
│  Advisor         │  │ ExtractionToolBridge │  │ ingestion (IngesterMgr)   │
└──────────────────┘  └──────────┬───────────┘  └──────────────────────────┘
                                 │ mutation @Tool calls
                                 ▼
                  ┌──────────────────────────────────────────┐
                  │  tools/mutation — fail-closed gate spine   │
                  │  `MutationGateChain.execute()`             │
                  │ enforceRole→resolve→authorize→reserve→     │
                  │ coerce→guard→save→finalize                 │
                  └──────────────────┬─────────────────────────┘
                                     │ audit (every call)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Persistence — TWO Jmix datastores                                        │
│  main (host app entities)   +   agentstore (`AgentstoreStoreConfiguration`)│
│  agentstore: AiConversation, AiMessage, AiAuditEvent, AiParameters,       │
│  AiKnowledgeDocument, AiExposureRule, AiMutationIntent, AiUiSettings,      │
│  AiTaskFile, AiExtractionDraft + pgvector embeddings                      │
└─────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| ChatService impl | Per-turn orchestration: gateway, guards, RAG, tool assembly, streaming | `DefaultChatServiceImpl.java` |
| RunContext | Per-thread run-scoped carrier (run id, root audit id, conversation id, retrieval params, extraction turn) | `orchestration/RunContext.java` |
| ConversationGateway | Load/create `AiConversation` with ownership-opacity + title rule (UnconstrainedDataManager) | `orchestration/ConversationGateway.java` |
| AgentToolCallbacks | Per-request `ToolCallback[]` assembly + intent routing + admin allowlist intersection | `tools/AgentToolCallbacks.java` |
| BuiltInDataTools | Read-only data tools (list/get/count) | `tools/BuiltInDataTools.java` |
| MutationGateChain | Single ordered fail-closed mutation gate spine for all 5 mutation tools | `tools/mutation/MutationGateChain.java` |
| Guard chain | Rate limit, token budget, iteration cap, output scanner, tool-name gating | `guard/` |
| RAG advisor | pgvector retrieval + retrieval auditing | `rag/advisor/` |
| AuditWriter | REQUIRES_NEW audit row boundary into agentstore | `audit/AuditWriter.java` |
| Extraction | Intent → form-prefill draft (`prepare_form_draft`) | `extraction/` |
| Action proposal | Side-effect-free proposal UX scaffolding tools | `action/` |
| Agentstore config | Second Jmix datastore (datasource, EMF, tx manager, Liquibase, pgvector JdbcTemplate) | `AgentstoreStoreConfiguration.java` |
| Module config | Jmix module, component scan, async/scheduling, SPI defaults | `AIConfiguration.java` |

## Pattern Overview

**Overall:** Layered Jmix/Spring-Boot addon with a Spring AI `ChatClient` core, exposed as a Jmix module (`@JmixModule`) consumed by a host app via an auto-configuration starter (`ai-agent-starter`).

**Key Characteristics:**
- "AI as another Jmix client" — all data access and authorization flow through Jmix `DataManager`/`AccessManager`; no parallel AI security layer.
- Fail-closed mutation gating: every mutation passes one canonical ordered gate spine; save crosses the transactional proxy last.
- Dual datastore: host `main` store + addon-owned `agentstore` (conversations, audit, KB embeddings, idempotency).
- SPI extension points (`spi/`) only for genuinely host-app-specific behavior; baseline context is built-in.
- Per-request, never-cached tool callback arrays — effective tool surface is user/intent/allowlist specific.

## Layers

**View (Vaadin Flow):**
- Purpose: Chat UI + admin views.
- Location: `view/`
- Depends on: orchestration `ChatService`.

**Orchestration:**
- Purpose: Drive each chat turn; assemble prompt, tools, RAG, guards; stream results.
- Location: `orchestration/`, `DefaultChatServiceImpl.java`
- Used by: View layer.

**Tools:**
- Purpose: Expose `@Tool` callbacks (read, link, mutation, extraction, proposal) to the model.
- Location: `tools/`, `action/`, `extraction/`
- Depends on: Jmix metadata/data; SPI contributors.

**Guard:**
- Purpose: Pre/in/post-turn safety (rate limit, token budget, iteration cap, output scanning, tool-name pattern gating).
- Location: `guard/`

**Persistence:**
- Purpose: Two Jmix datastores; entities + Liquibase changelogs.
- Location: `entity/`, `AgentstoreStoreConfiguration.java`, `resources/com/vn/agent/liquibase/`

## Data Flow

### Primary Chat Turn

1. View calls `ChatService.ask(...)` (`DefaultChatServiceImpl.java`)
2. `ConversationGateway.loadOrCreate(...)` enforces ownership opacity + title rule (`orchestration/ConversationGateway.java`)
3. Guard preamble: `RateLimitGuard.check` then `TokenBudgetGuard.check` (`guard/`)
4. `SystemPromptComposer` + guard rules build the system prompt (`orchestration/SystemPromptComposer.java`, `guard/AgentSystemPromptRulesComposer.java`)
5. RAG advisor retrieves context from pgvector (`rag/advisor/RetrievalAugmentationAdvisorFactory.java`)
6. `AgentToolCallbacks.callbacksFor(user, conv, intentId, enabledTools)` assembles the per-turn tool surface (`tools/AgentToolCallbacks.java`)
7. `ChatClient` runs; tool calls dispatch through audit/boundary decorators
8. Streaming tokens emitted via `StreamingSinkHolder` → `Flux<StreamingEvent>`
9. `AuditWriter` writes a root audit row + child rows (REQUIRES_NEW) into agentstore

### Mutation Tool Invocation (fail-closed spine)

1. Model calls a mutation `@Tool` → thin adapter builds a `MutationRequest` and calls `MutationGateChain.execute` (`tools/mutation/MutationGateChain.java`)
2. `enforceRole` (marker role `AiAgentMutationRole`) → `resolve` → `authorize` (Jmix CRUD + attribute + LLM exposure) → `reserve` (idempotency hash/replay) → `coerce` → `guard` (host `MutationGuard` SPI veto) → `save` (only transactional proxy crossing) → `finalize` (mark committed + success audit)
3. Any gate throwing routes to a typed catch arm → translated error + `BLOCKED`/`ERROR` audit; no partial commit.

**State Management:**
- Per-thread state via `RunContext` ThreadLocals (cleared in `finally`).
- Per-execute mutation state in a `MutationGateChain.Context` (never instance fields — singleton concurrency safety).

## Key Abstractions

**ToolCallback assembly:**
- Purpose: User/intent/allowlist-specific tool surface, rebuilt per request.
- Examples: `tools/AgentToolCallbacks.java`
- Pattern: `MethodToolCallbackProvider` reflection; decorator wrapping for audit.

**MutationRequest (sealed):**
- Purpose: Variant carrier for the 5 mutation operations.
- Examples: `tools/mutation/MutationRequest.java` (Create/Update/AddRelated/RemoveRelated/Bulk)
- Pattern: Java sealed type + exhaustive `switch`.

**SPI extension points:**
- Purpose: Host-app customization without widening core APIs.
- Examples: `spi/MutationGuard.java`, `spi/IntentExtractor.java`, `spi/ToolContributor.java`, `spi/ContextContributor.java`

## Entry Points

**Chat UI:**
- Location: `view/chat/ChatView.java`, `ChatDialogView.java`, `AiAgentSidebarView.java`
- Triggers: User chat interaction.

**ChatService:**
- Location: `ChatService.java` / `DefaultChatServiceImpl.java`
- Triggers: View `ask`/`askTyped`.

**Module/auto-config:**
- Location: `AIConfiguration.java` (addon module); `ai-agent-starter/.../AIAutoConfiguration.java` and siblings (host wiring).

## Architectural Constraints

- **Threading:** Sync-only per turn; Vaadin UI threads are pooled, so `RunContext.clear()` MUST run in `finally` to prevent cross-user leakage. Async only for ingestion (`AsyncIngestionWorker`) and scheduled cleanup jobs.
- **Datastores:** Two Jmix stores. Raw-JPQL `loadValue/loadValues` on agentstore entities require explicit `.store("agentstore")`.
- **Mutation transaction boundary:** NO `@Transactional` on `MutationGateChain` (self-invocation pitfall). The sole tx boundary is `MutationSaveExecutor`. A reflection test fails the build if `@Transactional` is added to the chain.
- **System-internal writes:** Use `UnconstrainedDataManager` (audit, conversation gateway, chat memory) — the Jmix system user is still policy-gated.
- **No `delete_record` tool:** never exposed under any property combination.

## Anti-Patterns

### Caching the tool callback array

**What happens:** Reusing a previously built `ToolCallback[]`.
**Why it's wrong:** Effective schema, contributor output, intent routing, and admin allowlist vary per request/turn.
**Do this instead:** Always call `AgentToolCallbacks.forCurrentUser()` / `callbacksFor(...)` fresh (`tools/AgentToolCallbacks.java`).

### Self-invoked `@Transactional` on the gate chain

**What happens:** Annotating a private/self-called method on `MutationGateChain`.
**Why it's wrong:** Spring proxy is bypassed; the annotation is silently ignored, breaking rollback.
**Do this instead:** Keep the tx boundary on `MutationSaveExecutor`; cross the proxy via the save delegate.

### Promoting per-execute mutation state to instance fields

**What happens:** Storing `commitState`/`reservation` on the singleton.
**Why it's wrong:** Concurrent tool calls corrupt each other's commit classification.
**Do this instead:** Use the per-call `MutationGateChain.Context`.

### Mutating pgvector chunk metadata directly

**What happens:** Editing embedding metadata to reflect KB permission/source changes.
**Why it's wrong:** Chunks desync from `AiKnowledgeDocument`.
**Do this instead:** Reingest via the ingestion path (`rag/IngesterManager.java`).

## Error Handling

**Strategy:** Typed user-facing errors (`ToolUserError`) translated per tool/metaclass; guard denials map to stable i18n keys; mutations classify outcomes (`SUCCESS`/`BLOCKED`/`ERROR`).

**Patterns:**
- Ownership opacity: identical exception shape whether a row is missing or owned by another user (`ConversationGateway`).
- Mutation catch arms: `ToolVetoedException` → BLOCKED, `AccessDeniedException` (bulk) → BLOCKED, `ToolUserError` → ERROR, `Throwable` → coordinator-classified.

## Cross-Cutting Concerns

**Logging:** SLF4J; sanitized arguments (`audit/MutationArgumentSanitizer.java`); never echo host veto text (PII).
**Validation:** Jmix Bean Validation + `MutationAttributeBinder` literal coercion before guard.
**Authentication:** Jmix `CurrentAuthentication` / `AccessManager`; marker role `AiAgentMutationRole` gates mutation; LLM exposure rules (`exposure/`) gate which entities the model sees.
**Auditing:** `AuditWriter` (REQUIRES_NEW) + `ToolCallbackAuditDecorator` / `MutationToolCallbackBoundaryDecorator`.

---

*Architecture analysis: 2026-06-04*
