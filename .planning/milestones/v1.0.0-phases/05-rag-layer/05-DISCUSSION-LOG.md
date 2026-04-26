# Phase 5: RAG Layer - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-20
**Phase:** 05-rag-layer
**Areas discussed:** Embedding model & wiring, Allowed-roles tagging UX, Retrieval advisor & filter semantics, Ingestion pipeline shape, CustomIngester sample & trigger, Delete semantics

---

## Embedding model & wiring

### Q1 — Default embedding model

| Option | Description | Selected |
|--------|-------------|----------|
| OpenAI text-embedding-3-small (1536) via OpenRouter | 1536 matches DDL; reuses OpenRouter key; OpenAI starter on classpath | ✓ |
| OpenAI text-embedding-3-small via direct OpenAI endpoint | Separate base-url; still 1536 | |
| Configurable, fail-fast if unset | No default; safer but breaks plug-and-play | |

**User's choice:** OpenAI text-embedding-3-small via OpenRouter (Recommended).

### Q2 — EmbeddingModel bean construction

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse spring-ai-starter-model-openai auto-config | @ConditionalOnMissingBean; bean-collision test | ✓ |
| Explicit @Bean constructing OpenAiEmbeddingModel | Full control, decoupled from starter | |
| Require host to declare | Breaks plug-and-play | |

**User's choice:** Reuse starter auto-config (Recommended).

### Q3 — Model drift handling

| Option | Description | Selected |
|--------|-------------|----------|
| Filter out stale chunks silently | Phase 7 banner; zero boot risk | ✓ |
| Fail-fast at startup | Blocks boot on model change | |
| No enforcement | Garbage retrieval risk | |

**User's choice:** Filter out silently (Recommended).

---

## Allowed-roles tagging UX

### Q1 — Upload-time role selection (clarified by user)

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit multi-select, required | Forces role selection every upload | |
| Default to uploader's own roles | Pre-fill, editable | |
| Optional — untagged = admin-only | Minimal UI friction, fail-closed | |
| **User clarification** — default to broad shared role (AiAgentUserRole); expose role selection only for restricted docs | Reframes posture as "shared by default" | ✓ |

**User's choice:** Shared-by-default UX posture; `allowedRoles = [AiAgentUserRole]` pre-filled, "Advanced / restrict access" reveals the picker.
**Notes:** Product decision — vector store's primary role is shared system-wide documentation, not per-team restricted docs. Fail-closed contract remains independent of this UX default.

### Q2 — Empty/missing allowedRoles meaning

| Option | Description | Selected |
|--------|-------------|----------|
| Admin-only (fail-closed) | Matches ROADMAP #3; untagged = most restrictive | ✓ |
| Public within tenant | Matches "shared" intent but breaks fail-closed contract | |

**User's choice:** Admin-only (Recommended).

### Q3 — Admin bypass

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — admin sees everything | Filter returns null for admin | ✓ |
| Configurable via property (default true) | Exposes knob for stricter hosts | |
| No — admin must also be in allowedRoles | Uniform but forces admin self-tagging | |

**User's choice:** Yes, admin bypass (Recommended). Configurable property is additionally implied in CONTEXT.md as a deployed knob.

### Q4 — Role picker source

| Option | Description | Selected |
|--------|-------------|----------|
| Jmix RoleRepository; service re-validates | Host-defined roles appear; defence in depth | ✓ |
| Static list of add-on's two roles only | Simpler, postpones host-defined case | |

**User's choice:** RoleRepository with service re-validation (Recommended).

### Q5 — Default layer

| Option | Description | Selected |
|--------|-------------|----------|
| UI only; service never synthesises | Keeps fail-closed crisp | ✓ |
| Service synthesises on empty | Blurs fail-closed semantics | |

**User's choice:** UI only (Recommended).

---

## Retrieval advisor & filter semantics

### Q1 — Advisor choice

| Option | Description | Selected |
|--------|-------------|----------|
| Defer to researcher (QA advisor fallback to RAG advisor) | Evidence-based | |
| Commit to QuestionAnswerAdvisor | Simpler, older shape | |
| Commit to RetrievalAugmentationAdvisor | Newer composable shape | ✓ |

**User's choice:** Commit to RetrievalAugmentationAdvisor now. Researcher still verifies 1.1.4 API shape but the architectural commitment is locked.

### Q2 — Filter boolean semantics

| Option | Description | Selected |
|--------|-------------|----------|
| ANY (intersection non-empty) | "Shared with roles X, Y" | ✓ |
| ALL (user ⊇ allowedRoles) | Clearance semantics | |
| Configurable per document | Flexibility, bigger surface | |

**User's choice:** ANY (Recommended).

### Q3 — Filter construction

| Option | Description | Selected |
|--------|-------------|----------|
| Spring AI Filter.Expression DSL | Portable across VectorStore impls | ✓ |
| Raw SQL via FilterExpressionConverter override | Locks to pgvector | |

**User's choice:** Spring AI DSL (Recommended).

### Q4 — Builder location

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated RetrievalFilterBuilder bean | Pure function, unit-testable | ✓ |
| Inline in advisor config | Less indirection, harder to test | |

**User's choice:** Dedicated bean (Recommended).

---

## Ingestion pipeline shape

### Q1 — Async mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Spring @Async with named TaskExecutor | Spring-native, test-swappable | ✓ |
| Jmix BackgroundTaskManager | Couples to Vaadin UI | |
| Inline on upload thread | Blocks response | |

**User's choice:** Spring @Async (Recommended).

### Q2 — Chunk size/overlap

| Option | Description | Selected |
|--------|-------------|----------|
| Spring AI defaults (800/350) via @ConfigurationProperties | Tunable, safe defaults | ✓ |
| Smaller (512/128) | Tighter precision | |
| Hard-coded | No tuning | |

**User's choice:** Spring AI defaults via properties (Recommended).

### Q3 — Embed failure behaviour

| Option | Description | Selected |
|--------|-------------|----------|
| Fail whole doc, no partial persistence | Atomic, clean admin state | ✓ |
| Persist succeeded chunks, PARTIAL state | Less wasted work, retrieval complexity | |
| Spring Retry on each embed, fail after N | Transient-error handling | |

**User's choice:** Fail whole doc (Recommended). Spring Retry is combined orthogonally per CONTEXT.md D-16.

### Q4 — Reingest affordance

| Option | Description | Selected |
|--------|-------------|----------|
| Reingest service method (delete chunks + reset status) | Admin-button wireable | ✓ |
| No reingest; delete + re-upload | Admin friction | |

**User's choice:** Reingest service method (Recommended).

---

## CustomIngester sample & trigger

### Q1 — Sample impl

| Option | Description | Selected |
|--------|-------------|----------|
| Classpath markdown folder (default classpath:/ai-kb/**/*.md) | Reference impl + test fixture | ✓ |
| Filesystem directory | Closer to real deploy, harder to test | |
| In-memory String-list (tests only) | Smallest, no real value | |

**User's choice:** Classpath markdown folder (Recommended).

### Q2 — Invocation trigger

| Option | Description | Selected |
|--------|-------------|----------|
| Admin-triggered service method + property gate | No startup surprises | ✓ |
| ApplicationReadyEvent auto-run | Risks long boot | |
| Scheduled | No current requirement | |

**User's choice:** Admin-triggered (Recommended).

---

## Delete semantics

### Q1 — PROCESSING state behaviour

| Option | Description | Selected |
|--------|-------------|----------|
| Allow with cancellation handshake | Never block admin action | ✓ |
| Block with UI error | Simpler but "stuck docs" footgun | |
| Allow, let worker finish, noop-on-missing | Wastes embed quota | |

**User's choice:** Cancellation handshake (Recommended).

### Q2 — Chunk removal mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| VectorStore.delete(FilterExpression) + DataManager.remove in one @Transactional | Portable, one rollback boundary | ✓ |
| Raw JDBC DELETE | Locks to pgvector | |

**User's choice:** VectorStore.delete(FilterExpression) (Recommended).

---

## Claude's Discretion

Captured in CONTEXT.md §Claude's Discretion. Summary: bean/package names, `RetrievalAugmentationAdvisor` construction style, cancellation-marker storage (in-memory vs status poll), UUIDv5 namespace choice, Spring Retry style, `IngestionStatusWriter` internal shape, admin `null`-vs-`ALL_PASS` return from `RetrievalFilterBuilder`.

## Deferred Ideas

Captured in CONTEXT.md §Deferred Ideas. Summary: per-doc ANY/ALL strategy, per-ingester splitter config, scheduled ingester runs, URL crawling, entity auto-ingest, PII redaction, partial resume, stale-chunk admin banner UI, dimension migration, structured-output × retrieval interaction, tenant-level KB partitioning.
