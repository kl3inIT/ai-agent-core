---
id: SEED-003
status: dormant
planted: 2026-04-21
planted_during: v1.0 / Phase 6 guardrails design review
trigger_when: the config-driven regex output scanner proves insufficient in production, or multiple host apps need non-regex custom output-scanning logic
scope: Medium
---

# SEED-003: Open an OutputScanner SPI only if the config-driven regex scanner proves insufficient

## Why This Matters

Phase 6 already has a strong v1 posture for output scanning: ship a small bundled regex list,
make it configurable through `@ConfigurationProperties`, flag suspicious responses, and audit by
stable pattern key. That keeps the add-on simple, predictable, and aligned with the product's
"plug-and-play first" posture.

What should not happen is opening a new SPI surface too early just because it sounds extensible.
An `OutputScanner` SPI would be justified only when the current model is demonstrably not enough,
for example:

- repeated false negatives in production that regex cannot reasonably cover
- repeated false positives that require domain-aware logic, not just pattern tuning
- two or more host apps independently needing custom non-regex checks such as semantic matching,
  classifier-based review, or policy logic tied to their own environment

This seed preserves that decision boundary. The goal is to avoid premature extensibility in v1,
while keeping a clear path to a reviewed SPI if real usage proves the config-driven scanner is too
weak.

## When to Surface

**Trigger:** the config-driven regex output scanner proves insufficient in production, or multiple host apps need non-regex custom output-scanning logic

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- production or dogfooding shows repeated output-injection cases that escape the bundled/configured
  regex patterns
- hosts start asking for custom output-scanning behavior that cannot be expressed as pattern-list
  configuration
- a milestone forms around guardrail hardening, enterprise extensibility, or host-specific policy
  integration
- Phase 6 lands and early adoption shows that scanner tuning is becoming code work outside the
  add-on instead of remaining a config concern

## Scope Estimate

**Medium** — likely one focused phase, because opening this SPI cleanly is more than adding one
interface:

- define the `OutputScanner` contract and invocation model
- decide composition order between bundled regex scanning and custom beans
- preserve stable audit semantics even when scanner logic becomes host-defined
- document failure isolation, bean ordering, and interaction with the advisor chain
- add tests proving default behavior stays config-driven when no SPI beans are present

This should remain a narrow extension seam. The first cut should not become a generic moderation or
policy engine.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/phases/06-parameters-structured-output-guardrails/06-CONTEXT.md](D:/DTH/ai-agent-core/.planning/phases/06-parameters-structured-output-guardrails/06-CONTEXT.md)
  Already captures the current v1 decision: bundled regex defaults, `@ConfigurationProperties`
  override, flag-and-pass-through behavior, and explicit deferral of an `OutputScanner` SPI.
- [.planning/REQUIREMENTS.md](D:/DTH/ai-agent-core/.planning/REQUIREMENTS.md)
  `GUARD-05` defines the output-side scan requirement. Any future SPI must stay subordinate to that
  requirement rather than expanding the feature into a separate subsystem.
- [.planning/ROADMAP.md](D:/DTH/ai-agent-core/.planning/ROADMAP.md)
  Phase 6 is where the scanner lands. This seed is a follow-up to that phase, not a reason to add
  more surface before the first implementation exists.
- [ChatClientFactory.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java)
  Central advisor assembly point where the Phase 6 output-scanner advisor will be inserted. A
  future SPI would likely plug into this path and therefore needs careful ordering semantics.
- [AuditAdvisor.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java)
  Output-scanner flags are intended to land in the same audited request lifecycle. A host-defined
  SPI must not weaken audit durability or stable pattern-key reporting.
- [.planning/seeds/SEED-002-pre-deploy-answer-quality-regression-gate.md](D:/DTH/ai-agent-core/.planning/seeds/SEED-002-pre-deploy-answer-quality-regression-gate.md)
  Adjacent future work. If scanner behavior starts affecting rollout safety or quality gates, the
  two efforts may need to be planned together.

## Notes

- Default recommendation remains: ship Phase 6 with bundled regex defaults plus config override,
  and no SPI.
- If this seed activates, prefer a very small contract:
  - input: assistant text + stable request context
  - output: `CLEAR` / `FLAGGED` plus stable key/reason metadata
  - no direct mutation of audit rows or chat response DTOs from host code
- Preserve the current soft posture: flag-and-audit first. Do not let a future SPI quietly turn
  this into arbitrary hard-block behavior without an explicit product decision.
