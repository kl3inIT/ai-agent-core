---
created: 2026-04-26T02:22:23+07:00
title: Inject readable entity inventory into baseline context
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
  - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
---

## Problem

Audit logs (2026-04-26) show a recurring wasteful pattern: model calls `find_records` first, gets
`unknown_entity` or empty result because of host-prefix mismatch (e.g. guesses `Customer` while the
host actually exposes `jmixapp_Customer`), THEN falls back to `list_entities`, THEN re-issues
`find_records`. Two concrete chat turns from the screenshot:

- Turn 1: `find_records` (37ms, miss) → `list_entities` (3ms) → `find_records` (0ms) → `RETRIEVAL` (980ms)
- Turn 2: `count_records` (38ms) → `list_entities` (5ms) → `find_records` (0ms) → `RETRIEVAL` (984ms)

Root cause: the LLM has no entity inventory at planning time. `BaselineContextProvider.compose()`
currently injects only `agent.userId`, `agent.username`, `agent.roles`, `agent.locale`,
`agent.conversationId` (see `BaselineContextProvider.java:46`). The model has to guess naming
convention (with/without host prefix, snake/camel) until it sees a tool error.

Consequences:

- +1 unnecessary tool round-trip on the first turn of nearly every conversation
- Audit tree-lite (todo redesign-audit-schema) gets noisy: every chat root has a redundant child
- Latency budget burned on schema discovery instead of retrieval/answer
- Force-rule "always call `list_entities` first" was considered but rejected — it taxes follow-up
  turns where the entity is already known and bloats context if the host has many entities

## Solution

Extend baseline context with the readable-entity inventory, computed per request from the same
`AccessManager`-filtered source the `list_entities` tool already uses.

### Scope

1. **`BaselineContextProvider.compose(...)` — add `agent.entities`**
   - Inject `CurrentUserSchemaAccess.getReadableSchema().keySet()` rendered as
     `name (label)` lines, sorted alphabetically by name (deterministic prompt-hash).
   - Reuse the same source of truth as `BuiltInDataTools.listEntities()` (line 82–96) — no
     parallel filter logic, no risk of drift between baseline and tool surface.
   - Skip if the schema is empty (anonymous user / no readable entities).
   - Render as a single block under one key, NOT one key per entity (keeps `compose()` map small;
     `renderAsText()` produces a multi-line value).

2. **Token-budget guard**
   - If readable entity count exceeds a threshold (default 50, configurable via
     `default-params.yaml`), truncate to top-N by alphabetical order and append a hint:
     `… (truncated, call list_entities for full list)`. This is the only fallback path that keeps
     the `list_entities` tool useful; without truncation a host with 200 entities would inflate
     every system prompt by ~2k tokens.
   - Threshold lives in a typed properties record on the AI side, not hardcoded.

3. **System-prompt wording update (`DefaultChatServiceImpl`)**
   - State explicitly: "Use entity names from `agent.entities` verbatim. Only call `list_entities`
     if the inventory is truncated or empty."
   - Pair with todo `enforce-unknown-entity-retry-contract.md` — that todo handles the recovery
     path; this todo handles the prevention path.

4. **Tests**
   - Extend `BaselineContextProviderTest`: assert `agent.entities` contains expected entities for
     a user with role X, excludes entities the user cannot read (security parity with
     `list_entities`).
   - New integration test: a chat turn that previously triggered `find_records → list_entities →
     find_records` now produces a single `find_records` call with the correct entity name on the
     first try (mock `ChatModel`, instrument tool call count).
   - Truncation test: schema with > threshold entities renders the truncation hint.

5. **Audit metadata**
   - `agent.entities` flows into `compose()` and therefore into the prompt-hash. Expected and
     desirable: a host that newly exposes an entity should produce a different prompt hash so
     cached responses don't go stale.

### Decisions to make during planning

- **Field shape**: `name (label)` per line vs JSON array vs CSV. Lean toward `name (label)\n…`
  for prompt readability; JSON inside a system prompt tends to confuse smaller models.
- **Include attribute names?** NO for v1 — that doubles token cost and overlaps with
  `describe_entity`. Revisit if `describe_entity` round-trips become the next observed waste.
- **Threshold default**: 50 entities is a guess; tune after measuring the host app's actual count.
- **Interaction with todo `add-llm-permission-inventory`**: this todo intentionally stops at entity
  level. Attribute-level / denied-entity inventory belongs in that larger follow-up. Keep this one
  small and shippable in M1 P8.

### Why this beats "force list_entities first"

- Zero extra tool call on happy path → lower latency, cleaner audit tree.
- Inventory is computed once per request inside `BaselineContextProvider` (already on the hot path
  for every chat turn) — marginal CPU cost is the same `getReadableSchema()` call the tool would
  have made anyway.
- Follow-up turns in the same conversation reuse the inventory automatically (system prompt is
  rebuilt per request, but the user pays nothing extra for it).
- Falls back gracefully via truncation + `list_entities` for hosts with very large schemas.
