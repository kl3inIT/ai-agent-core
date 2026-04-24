---
id: SEED-005
status: dormant
planted: 2026-04-21
planted_during: v1.0 / Phase 7 Flow UI planning
trigger_when: after the standard ChatView ships and teams want lower-friction chat access from any screen, or operators need to control whether end users see a full route or a lightweight launcher
scope: Medium
---

# SEED-005: Add a floating user chat launcher and admin chat-surface toggle

## Why This Matters

Phase 7 is correctly keeping v1 conservative: one shared chat backend, one reusable chat UI
core, and a normal full-page `ChatView` as the first shipped end-user surface. That keeps the
memory model, routing contract, and security posture simple while the Flow UI layer lands.

What this defers is a real product opportunity:

- end users often want to ask a quick question while staying on the record or list they are
  already viewing
- a bottom-right launcher is more discoverable and lower-friction than navigating to a dedicated
  route
- host teams may want a staged rollout where the AI surface is visible to some users or exposed in
  one mode but not another

If we do this later without preserving the decision context, there is a high chance the launcher
becomes an ad hoc second chat UI with duplicated state handling, duplicated shell wiring, and a
different conversation contract from the full `ChatView`.

This seed preserves the intended direction: the launcher should be a second presentation surface
over the same backend and the same reusable chat panel, not a second chat system.

## When to Surface

**Trigger:** after the standard ChatView ships and teams want lower-friction chat access from any
screen, or operators need to control whether end users see a full route or a lightweight launcher

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- the v1 full-page `ChatView` is stable and there is demand for faster access from anywhere in the
  app shell
- product feedback says users do not discover or return to the dedicated chat route often enough
- a host app wants AI access while users remain on business screens such as lists or detail views
- admins need a governed switch for which chat surface is exposed to end users: disabled,
  full-page route only, or floating launcher
- the reusable `ChatPanelFragment` from Phase 7 is ready and shell-level integration becomes the
  next leverage point

## Scope Estimate

**Medium** — likely one focused phase, or two small linked phases, if kept narrow:

- mount a launcher into the host shell or `MainView` without creating a second chat backend
- reuse the shared `ChatPanelFragment` rather than cloning transcript/input logic
- define the surface-selection rule clearly: full-route only vs. floating launcher vs. disabled
- add admin-controlled configuration for that exposure decision
- verify responsive behavior, route changes, and conversation continuity when the panel opens from
  arbitrary screens

Keep the first cut intentionally narrow. Do not mix this with new mutation tools, cross-view
context injection, or a brand-new widget framework.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/phases/07-flow-ui/07-CONTEXT.md](D:/DTH/ai-agent-core/.planning/phases/07-flow-ui/07-CONTEXT.md)
  D-29 already records the exact architectural intent: one shared chat backend, one reusable chat
  panel component, v1 full `ChatView` only, floating launcher and admin-configurable toggle
  deferred to v2.
- [.planning/phases/07-flow-ui/07-UI-SPEC.md](D:/DTH/ai-agent-core/.planning/phases/07-flow-ui/07-UI-SPEC.md)
  The UI contract already plans `ChatPanelFragment` as the reusable boundary and states that v2
  floating or embedded surfaces should compose the same fragment without refactoring.
- [.planning/phases/07-flow-ui/07-DISCUSSION-LOG.md](D:/DTH/ai-agent-core/.planning/phases/07-flow-ui/07-DISCUSSION-LOG.md)
  Captures the user decision that admin views stay separate, v1 ships a normal `ChatView`, and
  the floating launcher plus admin toggle move to v2.
- [MainView.java](D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/view/main/MainView.java)
  The current host shell entry point. A future launcher likely mounts here or in an equivalent
  add-on-owned shell integration point.
- [AiAgentAdminRole.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java)
  Admin role already exists at the entity-policy layer. A future chat-surface toggle should align
  with this governed admin/operator boundary rather than becoming an unmanaged per-view hack.
- [AiAgentUserRole.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java)
  End-user role is intentionally narrow. The launcher should remain a user-facing surface over the
  same conversation/memory model rather than bypassing the existing role split.
- [.planning/REQUIREMENTS.md](D:/DTH/ai-agent-core/.planning/REQUIREMENTS.md)
  UI-01..UI-03 define the v1 chat surfaces that must ship first; this seed belongs after those are
  proven, not before.

## Notes

- Current session decision: keep admin surfaces as full views for configuration/audit, and keep
  the user chat surface simple in v1.
- Future work should not introduce a second `ChatService`, second conversation store, or separate
  memory semantics for the launcher.
- If the toggle grows beyond a simple enum or boolean, consider whether it belongs in
  `AiParameters`, a new UI settings entity, or host-owned configuration before implementation
  starts.
