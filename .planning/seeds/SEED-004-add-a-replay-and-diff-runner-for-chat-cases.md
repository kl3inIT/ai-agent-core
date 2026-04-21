---
id: SEED-004
status: dormant
planted: 2026-04-21
planted_during: v1.0 / harness-engineering exploration
trigger_when: we need to compare model, prompt, or profile changes before rollout, or regression debugging requires rerunning the same chat case with controlled inputs
scope: Medium
---

# SEED-004: Add a replay and diff runner for chat cases

## Why This Matters

The add-on already has the right raw ingredients for a strong harness posture: conversations are
persisted, tool calls are audited, retrieval is role-scoped, parameter profiles are becoming
admin-editable, and Phase 6 is defining evaluation corpora and rollout-facing guardrails.

What is still missing is a practical runner that can take one known chat case and answer:

- what changed when we swapped the model, system prompt, or active profile?
- did the tool path change?
- did retrieval context change?
- did a guard start firing or stop firing?
- did the final answer improve, regress, or simply drift?

Without that runner, debugging regressions stays manual and pre-rollout comparison remains
high-friction. Teams end up eyeballing chat logs instead of getting a structured before/after
comparison they can reason about.

This seed captures a future harness component that replays the same chat case under controlled
conditions and emits a structured diff rather than just another transcript.

## When to Surface

**Trigger:** we need to compare model, prompt, or profile changes before rollout, or regression debugging requires rerunning the same chat case with controlled inputs

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- admins or operators want to compare two profiles, prompts, or models before activating one
- production or dogfooding surfaces a suspicious conversation and the team needs to replay it with
  a fixed role set, context, and profile to isolate the regression
- the pre-deploy answer-quality gate from `SEED-002` becomes active and needs a reusable runner
  instead of ad hoc test code
- a milestone forms around evaluation, regression debugging, rollout safety, or harness
  engineering

## Scope Estimate

**Medium** — likely one focused phase if kept narrow:

- define a canonical "chat case" input shape: question, conversation context, role set, active
  profile, optional retrieval fixtures
- run the same case against two configurations ("baseline" vs "candidate")
- capture and diff:
  - tool calls
  - retrieval context / document ids
  - guard outcomes
  - final answer text or structured-output result
- produce a report that is useful both for local debugging and for pre-rollout review

The first cut should not try to replay arbitrary production conversations byte-for-byte. Start
with curated replayable cases and deterministic harness inputs.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/seeds/SEED-002-pre-deploy-answer-quality-regression-gate.md](D:/DTH/ai-agent-core/.planning/seeds/SEED-002-pre-deploy-answer-quality-regression-gate.md)
  Natural adjacent seed. That gate needs a concrete execution mechanism; this replay/diff runner is
  a likely implementation substrate.
- [.planning/seeds/SEED-001-reviewed-learning-loop-for-agent-failures-evaluation-cases-and-routing-rules.md](D:/DTH/ai-agent-core/.planning/seeds/SEED-001-reviewed-learning-loop-for-agent-failures-evaluation-cases-and-routing-rules.md)
  A future known-failure / evaluation-case store becomes much more useful when cases can actually
  be replayed and compared.
- [.planning/ROADMAP.md](D:/DTH/ai-agent-core/.planning/ROADMAP.md)
  Conversation replay, parameter profiles, guardrails, and integration hardening already exist as
  separate roadmap concerns. This seed connects them into a harness workflow rather than a set of
  isolated features.
- [.planning/REQUIREMENTS.md](D:/DTH/ai-agent-core/.planning/REQUIREMENTS.md)
  `ORCH-04`, `ORCH-05`, `UI-03`, `TEST-04`, and the Phase 6 guardrails all create data and
  invariants that a replay runner can reuse.
- [DefaultChatServiceImpl.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java)
  Main per-request orchestration path. Any replay runner should drive the same path instead of
  inventing a parallel execution stack.
- [ChatClientFactory.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java)
  Central place where advisor composition is fixed. Differences in tool calls, retrieval, or
  guard outcomes ultimately flow through this wiring.
- [AuditWriter.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java)
  Existing audit rows provide the natural trace surface for a diff runner report.
- [.planning/phases/06-parameters-structured-output-guardrails/06-AI-SPEC.md](D:/DTH/ai-agent-core/.planning/phases/06-parameters-structured-output-guardrails/06-AI-SPEC.md)
  Already defines eval corpora, judge usage boundaries, and rollout-safety thinking. This seed is
  the operational runner that those evals may eventually sit on top of.

## Notes

- Recommended first cut:
  1. curated replay cases only
  2. baseline-vs-candidate comparison
  3. structured diff report
  4. no UI requirement at first — CLI/test harness is enough
- Keep "replay" honest: the goal is not exact provider determinism. The goal is to hold inputs and
  wiring steady enough to detect meaningful differences in behavior.
- Prefer deterministic assertions first:
  - tool path changed / unchanged
  - retrieved docs changed / unchanged
  - guard fired / did not fire
  - structured output parsed / failed
  Final-answer quality scoring can remain a later layer on top.
