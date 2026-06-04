---
id: SEED-009
status: dormant
planted: 2026-05-30
planted_during: v1.2 in progress (Phases 17–19 pending) — architecture review vs Spring AI 1.1.x modular-RAG best practices + zero-mail reference
trigger_when: multi-turn RAG answers degrade (a follow-up question like "what about its price?" retrieves the wrong context because the standalone query was never reconstructed), OR a retrieval-quality measurement (precision@k / recall@k / MRR, or SEED-002's golden set) shows a regression that better retrieval — not a bigger model — would fix
scope: Small–Medium
---

# SEED-009: Multi-turn RAG query-compression + reranking

## Why This Matters

The v1.0 RAG layer (Phase 5) ships a deliberately simple retrieval path: embed the latest
user message, similarity-search pgvector under a role-scoped `Filter.Expression`, hand the
top-k chunks to the model. That is the right v1 default — it is secure (per-request filter),
auditable (`AuditingDocumentRetriever`), and cheap.

What it does **not** do is the two modular-RAG steps that Spring AI 1.1.x exposes and that
most production RAG quality gains come from:

1. **Query compression for multi-turn.** In a conversation, the user's literal latest turn is
   often not a standalone question ("and the deadline?", "what about its price?"). Embedding
   that raw fragment retrieves poorly. Spring AI's `CompressionQueryTransformer` folds the
   recent conversation history into one self-contained query before retrieval. This pairs
   directly with the chat-memory advisor the add-on already runs.
2. **Reranking / post-retrieval processing.** Vector top-k alone suffers "lost-in-the-middle":
   the most relevant chunk is often not in the top similarity slots. A `DocumentPostProcessor`
   (rerank + dedup) reorders/trims before generation.

This seed preserves the decision to keep RAG naive in v1 while recording the exact, bounded
upgrade path — so it is activated against a measured retrieval-drift signal, not adopted
speculatively (which would violate Pitfall #24's "don't activate without a trigger" rule).

## When to Surface

**Trigger:** multi-turn RAG answers degrade (follow-up questions retrieve wrong context
because the standalone query was never reconstructed), OR a retrieval-quality measurement
shows a regression that better *retrieval* — not a bigger model — would fix.

Present this seed during `$gsd-new-milestone` when the milestone scope matches any of:

- dogfooding/feedback shows the agent answers follow-up questions about KB documents poorly
  while answering the same question asked standalone correctly
- a milestone forms around RAG/answer quality (natural pairing with **SEED-002** pre-deploy
  answer-quality gate and **SEED-006** strict file-backed knowledge path)
- the KB grows large enough that top-k similarity alone returns near-duplicates or misses the
  best chunk (reranking territory)
- a reranker model becomes available in the self-hostable catalog (`project_self_hostable_models_only`)

## Scope Estimate

**Small–Medium** — likely one focused phase, additive to the existing advisor wiring:

- add `CompressionQueryTransformer` (temperature 0.0) to `RetrievalAugmentationAdvisorFactory`'s
  pre-retrieval stage; verify it composes with `MessageChatMemoryAdvisor` ordering
- add a `DocumentPostProcessor` rerank/dedup stage post-retrieval (model-backed or heuristic)
- keep the per-request role-scoped `Filter.Expression` verbatim (security-critical — no change)
- guard against the `ContextualQueryAugmenter` empty-context refusal default (set
  `allowEmptyContext` deliberately)
- a before/after retrieval-quality check (precision@k / recall@k on a small golden set) to
  prove the upgrade actually helped — ties to SEED-002

Keep it narrow: this is a *retrieval-quality* upgrade, not a re-architecture. Do not fold in
embedding-model swaps, chunking-strategy rewrites, or hybrid-search engines unless a separate
measured signal demands them.

## Breadcrumbs

Related code and decisions in the current codebase:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java`
  — the single composition point where pre-retrieval (query transform) and post-retrieval
  (rerank) stages would be added; today builds a retriever + role-scoped filter only
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AuditingDocumentRetriever.java`
  — retrieval audit wrapper; a rerank stage must preserve the RETRIEVAL audit semantics
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`
  — per-request role/exposure `Filter.Expression`; MUST remain unchanged (security boundary)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`
  — advisor-chain ordering; query compression must sit correctly relative to the memory advisor
- `.planning/milestones/v1.0.0-phases/05-rag-layer/05-AI-SPEC.md`
  — already defines precision@k / recall@k / MRR retrieval-quality baseline ideas (the
  measurement substrate this seed's before/after check would reuse)
- **SEED-002** (pre-deploy answer-quality regression gate) and **SEED-006** (strict file-backed
  knowledge path) — natural co-activation partners; this seed is the "better retrieval" lever,
  SEED-002 is the "prove it didn't regress" lever

## Notes

- Source of the recommendation: 2026-05-30 architecture review against Spring AI 1.1.x modular
  RAG (`RetrievalAugmentationAdvisor` + `CompressionQueryTransformer` + `DocumentPostProcessor`)
  and the zero-mail reference project's turn-aware context handling.
- Stay on the Spring AI **1.1.x** line (Boot 3 / Jmix 2.8). Do NOT pull 2.0 RAG examples — they
  carry a Boot 4 + Jackson 3 hard dependency.
- This is intentionally *not* SEED-006: SEED-006 is about an authoritative file-backed knowledge
  source; SEED-009 is about retrieving better from whatever knowledge already exists.
