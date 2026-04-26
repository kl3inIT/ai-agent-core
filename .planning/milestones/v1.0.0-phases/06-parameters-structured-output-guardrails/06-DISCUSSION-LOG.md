# Phase 6: Parameters, Structured Output & Guardrails - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-21
**Phase:** 06-parameters-structured-output-guardrails
**Areas discussed:** Parameters (overrides API + bootstrap/activation); Guardrails (composition + denial UX + audit); Rate limit + token breaker (storage, scope, identity); Output path (injection scanner + structured output API/retry)

---

## Parameters: overrides API + bootstrap/activation

### Override field scope

| Option | Description | Selected |
|---|---|---|
| All profile fields | model, temperature, maxTokens, systemPrompt, enabledTools, ragTopK, ragSimilarityThreshold | |
| Sampling + prompt only | temperature, topP, maxTokens, systemPrompt (no model/tools) | |
| Model + sampling only | model, temperature, topP, maxTokens (no systemPrompt/tools) | |
| **Other (free-text)** | **Model only — per-conversation override exists just for one-off model selection; prompt, tools, and RAG settings stay profile-controlled by admin** | ✓ |

**User's choice:** Model-only Overrides record (freeform).
**Notes:** Narrower than any offered option; admin retains strict control over prompt/tools/RAG surface.

### Merge semantics

| Option | Description | Selected |
|---|---|---|
| Per-field null-merge | Non-null overrides; null inherits | ✓ |
| Replace-all | Whole profile swap | |
| Merge + named-profile reference | Hybrid sparse-or-pointer | |

### default-params.yaml seed trigger

| Option | Description | Selected |
|---|---|---|
| Empty-table only at boot | One-shot on ApplicationReadyEvent if table empty | ✓ |
| Re-seed when no active profile exists | Self-healing | |
| Always ensure 'default' profile exists | Upsert on every boot | |

### Exactly-one-active enforcement

| Option | Description | Selected |
|---|---|---|
| Service-level transactional flip | @Transactional UPDATE false all + UPDATE true target | ✓ |
| Partial unique index on active=true | DB-level (Postgres-only syntax) | |
| Zero-or-one (allow no active) | Fall through to synthetic defaults | |

---

## Guardrails: composition order + denial UX + audit

### Firing order

| Option | Description | Selected |
|---|---|---|
| Rate-limit → token breaker → iteration cap → ToolGuard → output scanner | Cheapest/highest-blast first | ✓ |
| ToolGuard → iteration cap → rate-limit → token breaker → output scanner | Content-first | |
| All pre-LLM guards at one interceptor; content-level inside ChatService loop | Grouped boundary | |

### Plumbing

| Option | Description | Selected |
|---|---|---|
| Pre-LLM guards in ChatService preamble; content guards in advisor/ToolCallingManager | Split by scope | ✓ |
| All guards as Spring AI advisors | Uniform | |
| Servlet filter for rate-limit; rest in ChatService/advisors | Web-layer rate-limit | |

### Denial UX

| Option | Description | Selected |
|---|---|---|
| Typed exception → localised msg:// message in UI | Per-guard exception, i18n key + params | ✓ |
| Generic 'request blocked' | Detail only in audit | |
| Full stack/detail surfaced | Transparent but leaks | |

### Audit strategy

| Option | Description | Selected |
|---|---|---|
| ToolGuard → AiToolCallAudit BLOCKED; pre-LLM → same writer, tool='__chat__' synthetic | Single writer, single table | ✓ |
| Separate AiChatAudit table for request-level | Two tables | |
| ToolGuard only; log rest | Softer durability | |

---

## Rate limit + token breaker: storage, scope, identity

### Counter storage

| Option | Description | Selected |
|---|---|---|
| Bucket4j in-memory directly | No cache abstraction | |
| Pure ConcurrentHashMap + hand-rolled bucket | Smallest surface | |
| **JCache abstraction, local-by-default, swappable to distributed** | **JSR-107 / Spring CacheManager; ConcurrentMapCache default; swap to Hazelcast/Redis JCache via config** | ✓ |

**User's choice:** JCache abstraction (confirmed after user's clarification citing Jmix's `CacheOperations` local-first design).
**Notes:** Aligns add-on with Jmix standalone-first cache posture; cluster support becomes a config swap, not a code change.

### Identity

| Option | Description | Selected |
|---|---|---|
| Jmix user id from CurrentAuthentication | Per-user bucket | ✓ |
| User id + HTTP session fallback | Defensive | |
| Composite user+conversation | Defeats per-user intent | |

### Token-breaker scope

| Option | Description | Selected |
|---|---|---|
| Per-conversation rolling total | Lifetime-of-conversation ceiling | ✓ |
| Per-user rolling window (e.g. 1h) | Time-windowed auto-recover | |
| Per-HTTP-session total | Weak (resets on relog) | |

### Token count source

| Option | Description | Selected |
|---|---|---|
| Provider-reported usage from ChatResponse.metadata | Spring AI Usage; post-response accumulate | ✓ |
| Self-count via jtokkit | Pre-flight hard reject | |
| Hybrid self-count + provider-reported reconcile | Max accuracy/complexity | |

---

## Output path: injection scanner + structured output API/retry

### Scanner action on match

| Option | Description | Selected |
|---|---|---|
| Flag + audit, pass-through content | Banner + flagged DTO, content shown | ✓ |
| Redact matched spans, audit, pass-through | [REDACTED] tokens | |
| Block full response + audit | Strictest posture | |

### Pattern source

| Option | Description | Selected |
|---|---|---|
| Bundled regex defaults + @ConfigurationProperties override | Config-only extensibility | ✓ |
| Pluggable SPI (OutputScanner interface) | Code extensibility | |
| Both: bundled defaults + SPI | Max surface | |

### Structured-output API shape

| Option | Description | Selected |
|---|---|---|
| New method askTyped(userId, convId, question, Class<T>) → T | Dedicated typed method | ✓ |
| Generic ask overload returning wrapper<T> | Unified response shape (breaks Phase 4 callers) | |
| Separate TypedChatService bean | Cleanest separation (two beans) | |

### Retry policy

| Option | Description | Selected |
|---|---|---|
| Retry only on parse failure; max 2 | Re-inject format instructions; other exceptions bubble | ✓ |
| Retry on any exception; max 2 | Broader retry (amplifies rate-limit) | |
| No retry; fail fast | Single attempt | |

---

## Claude's Discretion

- Package layout inside `com.vn.agent.parameters` / `com.vn.agent.guard`
- ToolGuard enforcement seam (delegating ToolCallingManager vs ToolCallback interceptor vs pre-dispatch hook)
- Output scanner advisor position (post-ToolCall final-only vs per-turn wrap)
- Rate-limit bucket implementation (hand-rolled vs Bucket4j-JCache library)
- Jackson-YAML DTO shape for body validation
- `AiToolCallOutcome.FLAGGED` enum addition vs side column for scanner flags
- Retry-attempt-count audit carrier (new column vs metadata JSON vs separate table)
- `jmix.ai-agent.guard.*` property grouping (single record vs per-guard records)

## Deferred Ideas

- Per-conversation overrides beyond model
- Named-profile per-conversation reference
- OutputScanner SPI (regex-only in v1)
- Cluster-aware counter storage shipped in-box
- Self-tokenizer pre-flight counting
- Admin token-breaker reset affordance
- Dynamic pattern reload
- Role-differentiated rate-limit tiers
- Streaming-response output scanner interaction
- Mutation-tool dry-run/confirmation
- Structured-output schema registry
