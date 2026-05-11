---
id: SEED-007
status: implemented
planted: 2026-04-24
planted_during: v1.0 / Phase 07.1 awaiting human UAT
implemented_during: v1.1.0 / Phase 10 — AI-Specific LLM Exposure Policy
trigger_when: When a host application needs the AI to see less than the current user's Jmix permissions, or when admin governance over the LLM-visible entity surface becomes planned product scope.
scope: Medium
---

> **Implemented in v1.1.0 — Phase 10 (AI-Specific LLM Exposure Policy).** Shipped `AiExposureRule` (entity-level, `EXCLUDE`-only) + `LlmExposurePolicy` boundary (composition `userVisible AND NOT excluded`), enforced uniformly across schema discovery, tool calls, baseline prompt, and RAG; admin Flow UI (`AiExposureRuleListView`/`DetailView`) gated to `AiAgentAdminRole`; `LlmExposureChangedEvent`. `attributePath`-level rules deferred (entity-level denylist only in v1.1).

# SEED-007: Add AI-specific LLM exposure policy

## Why This Matters

The current add-on intentionally treats the AI as just another Jmix client under the current user's security context. That is the right default for v1 because it keeps `AccessManager` and `DataManager` as the single enforcement layer and avoids building a parallel policy system too early.

However, a real governance need may surface later: a host application may want the user to keep normal Jmix access to some entities or attributes while preventing the LLM from seeing them at all. Typical examples are internal audit tables, sensitive operational codes, or entities that are technically readable in the app but should never be exposed to a language model. When that happens, the project will need a deliberate AI-specific exposure layer rather than stretching Jmix permissions to solve a different problem.

This seed preserves that product and architecture fork so it can resurface at the right time instead of being rediscovered ad hoc.

## When to Surface

**Trigger:** When a host application needs the AI to see less than the current user's Jmix permissions, or when admin governance over the LLM-visible entity surface becomes planned product scope.

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of these conditions:

- a concrete requirement appears for "AI must see less than the user"
- an admin UI or governance feature is planned for controlling entity or attribute visibility to the LLM
- tool-calling needs a host-configurable denylist or allowlist beyond native Jmix security
- enterprise rollout requirements demand explicit review of which entities are public to the LLM

## Scope Estimate

**Medium** — likely one or two phases. This is larger than a quick task because it changes an existing architecture decision, requires a policy model, and would probably touch both runtime filtering and admin/governance UX.

## Breadcrumbs

Related code and decisions found in the current codebase:

- `.planning/phases/02-foundations/02-CONTEXT.md` — D-05 explicitly drops `AiExposureRule`, `EntityExposurePolicy`, and `ExposureRuleListView` from v1, while leaving room to revisit the decision if a concrete "AI must see less than the user" use case appears
- `.planning/PROJECT.md` — project-level security note says Jmix roles and data security are the single enforcement layer and that no AI-specific exposure layer ships in v1
- `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` — current per-request boundary that decides which entities and attributes are visible to the LLM under native Jmix permissions
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` — current tool surface that relies on `CurrentUserSchemaAccess` plus `DataManager` to enforce readable entity and attribute access
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` and `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — current per-request tool callback assembly, which is the natural place where any future LLM-specific visibility policy would need to compose with the existing user-scoped tool surface

## Notes

Captured from a `gsd-explore` conversation about tool-calling and permissions:

- the desired future behavior is that admins may mark some entities or attributes as "not public for the LLM" even when the user still has Jmix access to them
- this is distinct from the separate near-term todo about giving the LLM a clearer view of the current user's permissions
- if this seed is activated later, the design should preserve the principle that Jmix security stays authoritative for normal application access while the new layer only narrows the LLM-visible surface
