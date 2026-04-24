---
id: SEED-002
status: dormant
planted: 2026-04-20
planted_during: v1.0 / Phase 5 RAG Layer
trigger_when: admins need to change parameters safely, or rollout decisions start depending on eval and golden-set results
scope: Medium
---

# SEED-002: Pre-deploy answer-quality regression gate

## Why This Matters

The add-on is moving toward the point where small configuration changes can materially change
runtime behavior: model swaps, system-prompt edits, parameter-profile changes, routing tweaks,
retrieval-threshold changes, and future guardrail rules. Once those knobs are exposed to admins,
"it still boots" is no longer a sufficient release bar.

This seed captures a future pre-deploy gate that runs a reviewed regression suite before a
parameter or routing change is promoted broadly. The gate is intended to answer:

- did tool-vs-RAG routing regress?
- did role-scoped retrieval regress?
- did answer quality regress on golden cases?
- did a prompt/profile/model change improve one dimension while quietly breaking another?

The goal is not to turn the add-on into an LLM-evals platform. The goal is to protect rollout
decisions with a lightweight, repeatable quality gate grounded in this product's actual risk
surface.

## When to Surface

**Trigger:** admins need to change parameters safely, or rollout decisions start depending on eval and golden-set results

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- Phase 6 parameters/guardrails work reaches the point where admins can change behavior without a
  code deploy
- the team wants to compare profiles, prompts, models, or routing changes before rollout
- dogfooding or production incidents show that "looks good in a manual chat" is not enough to
  catch regressions
- a milestone forms around answer quality, evaluation, or safer parameter/profile operations

## Scope Estimate

**Medium** — likely one focused phase if kept narrow:

- a curated golden set of representative questions
- deterministic assertions for routing/retrieval/security behavior
- a small answer-quality rubric for user-visible output
- a before/after report that can block or warn on regressions

This should start with deterministic checks and fixture-based comparisons. LLM-as-judge is a
possible later extension, not a v1 prerequisite.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/research/FEATURES.md](D:/DTH/ai-agent-core/.planning/research/FEATURES.md)
  Already identifies "Answer-quality checks (reference-answer regression suite)" as a v1.x idea
  and explicitly ties it to the trigger "admins want to change Parameters safely".
- [.planning/ROADMAP.md](D:/DTH/ai-agent-core/.planning/ROADMAP.md)
  Phase 6 introduces parameters and guardrails; later rollout safety should build on that rather
  than relying on manual spot checks.
- [.planning/phases/04-orchestration-core/04-AI-SPEC.md](D:/DTH/ai-agent-core/.planning/phases/04-orchestration-core/04-AI-SPEC.md)
  Establishes the principle that evals should match the risk surface: deterministic assertions for
  deterministic invariants, not LLM-judge by default.
- [.planning/phases/05-rag-layer/05-AI-SPEC.md](D:/DTH/ai-agent-core/.planning/phases/05-rag-layer/05-AI-SPEC.md)
  Already defines retrieval-quality baseline ideas, golden fixtures, and soft metrics such as
  precision@k / recall@k / MRR for the RAG path.
- [AdvisorOrderStructuralTest.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java)
  Example of a deterministic structural invariant that belongs in any future gate.
- [RoleScopedRetrievalIntegrationTest.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java)
  Shows the shape of retrieval-side regression cases that should remain stable across prompt/model
  changes.
- [RetrievalFilterBuilderTest.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java)
  Example of pure-function eval fixtures that can feed a broader regression suite.

## Notes

- Recommended first cut:
  1. deterministic routing/retrieval/security assertions
  2. a small golden set for user-visible answers
  3. before/after comparison report for profile/model/prompt changes
- Do not block deployments on subjective scoring too early. Start with a warning/report mode, then
  tighten to a gate when the corpus is trustworthy.
- Keep this separate from the "reviewed learning loop" seed:
  - `SEED-001` is about learning from incidents over time
  - this seed is about protecting planned changes before rollout
