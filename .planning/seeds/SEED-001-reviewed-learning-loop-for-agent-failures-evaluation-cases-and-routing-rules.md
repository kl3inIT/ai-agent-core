---
id: SEED-001
status: dormant
planted: 2026-04-20
planted_during: v1.0 / Phase 5 RAG Layer
trigger_when: orchestration, RAG, and guardrails are stable, or repeated routing/retrieval mistakes appear in production
scope: Medium
---

# SEED-001: Reviewed learning loop for agent failures, evaluation cases, and routing rules

## Why This Matters

The add-on is accumulating the right primitives for a controlled learning loop: chat/tool audit,
role-scoped retrieval, deterministic advisor ordering, and an expanding integration-test harness.
What it does not yet have is a reviewed path from production mistakes to durable system
improvement.

This seed captures a future subsystem that turns real incidents into governed artifacts instead of
unstructured tribal memory. The core idea is:

- record concrete failures (`AiKnownFailure`)
- promote important failures into regression fixtures (`AiEvaluationCase`)
- optionally promote repeated routing mistakes into narrowly-scoped runtime behavior overrides
  (`AiRoutingRule`)
- keep descriptive lessons (`AiLesson`) reviewed and secondary, rather than letting the model
  "self-learn" from raw logs

That approach fits the enterprise posture of this project: the system should improve from
experience, but only through reviewable artifacts with clear scope, auditability, and rollback.

## When to Surface

**Trigger:** orchestration, RAG, and guardrails are stable, or repeated routing/retrieval mistakes appear in production

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- Phase 5 RAG and Phase 6 guardrails are complete enough that routing and retrieval behavior is
  stable, making regressions worth formalizing
- production or dogfooding starts showing repeated wrong-route, wrong-tool, wrong-retrieval, or
  policy-violation incidents that cannot be managed ad hoc
- a milestone is created around ops, evaluation, incident reduction, or answer-quality hardening

## Scope Estimate

**Medium** — likely one focused phase, or two smaller phases if split into:

- an artifact layer (`AiKnownFailure`, `AiEvaluationCase`, optional `AiLesson`)
- a runtime layer (`AiRoutingRule`) with a deliberately small action set such as `FORCE_TOOL`,
  `DISABLE_RAG`, or `FORCE_HYBRID`

This should not start as an unconstrained "self-learning" feature. The first cut should be a
reviewed feedback loop that improves reliability and protects against regressions.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/ROADMAP.md](D:/DTH/ai-agent-core/.planning/ROADMAP.md)
  Phase 4 established the orchestration/audit substrate; Phase 5 is adding role-scoped RAG;
  Phase 6 is reserved for guardrails. This seed depends on those layers being in place first.
- [.planning/REQUIREMENTS.md](D:/DTH/ai-agent-core/.planning/REQUIREMENTS.md)
  Existing requirements already distinguish orchestration, RAG, audit, and security-negative
  tests. A reviewed learning loop would extend that verification posture rather than replace it.
- [.planning/research/FEATURES.md](D:/DTH/ai-agent-core/.planning/research/FEATURES.md)
  Contains adjacent ideas such as thumbs up/down feedback, answer-quality checks, and structured
  audit visibility that can feed this seed.
- [AuditWriter.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java)
  Durable audit rows are the natural source for incident capture and later promotion into
  structured failure artifacts.
- [ChatClientFactory.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java)
  Central advisor assembly point where a future reviewed routing layer would likely integrate.
- [DefaultChatServiceImpl.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java)
  Current per-request orchestration path already threads tools, options, baseline context, audit
  run ids, and RAG filter params; future routing rules would need to stay small and explicit here.
- [KnowledgeDocumentService.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java)
  Part of the RAG substrate whose incidents may later surface as `AiKnownFailure` or
  `AiEvaluationCase` entries.
- [AdvisorOrderStructuralTest.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java)
  Example of the kind of regression harness this seed should produce more of: codified behavior,
  not just documentation.

## Notes

- Recommended first cut: implement only `AiKnownFailure` + `AiEvaluationCase`, and defer
  `AiLesson`/`AiRoutingRule` until there is a real incident corpus.
- Keep runtime behavior changes typed and reviewable. Do not let the model mutate its own routing
  policy from raw feedback.
- Avoid storing this subsystem as "just more vector memory". Failure artifacts and routing rules
  should be structured data first; selective retrieval can come later if needed.
