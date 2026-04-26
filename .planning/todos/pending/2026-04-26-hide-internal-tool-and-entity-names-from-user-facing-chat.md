---
created: 2026-04-26T02:35:00+07:00
title: Hide internal tool and entity names from user-facing chat
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
---

## Problem

Current chat surface leaks the implementation contract between the add-on and the LLM into the
end-user reply text. Two distinct rivers of leakage:

1. **Reply text echoes internal entity names.** The LLM is given raw Jmix entity names
   (`jmixapp_Customer`, `jmixapp_Order`) so it can call tools. With no prompt rule against it, the
   model copies those names verbatim into its reply: *"Tôi đã tìm thấy 5 jmixapp_Customer..."*.
   These names are an internal contract — host prefixes, snake casing, plural conventions — they
   mean nothing to a non-developer end user and look like a bug to a developer.
2. **Reply text narrates tool calls.** The model says *"Tôi sẽ gọi `find_records` để..."* or
   *"Sau khi `RETRIEVAL` xong..."*, exposing the tool layer that should be invisible. End users
   want answers, not a play-by-play of the orchestration.

Separation-of-concerns violation:

- **Audit panel** (`AiToolCallAudit` / `AiAuditEvent` per the tree-lite redesign) IS the technical
  channel — admins / developers go there to see exactly what was called, with what arguments, in
  what order. Raw entity names and tool names belong here. No change needed.
- **Chat panel** is the end-user channel — should speak the host's domain language (entity labels,
  plain verbs), never the add-on's tool surface.

`ToolResultFormatter.records(...)` currently wraps results in `<data>`-delimited blocks keyed by
raw entity name with no label prominence, so the model has no prompt-side incentive to translate.

## Solution

Prompt + formatter rules only. UI-side hiding (collapsible "AI did" panel, ephemeral streaming
status) is split out into a separate M2 todo
`add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` so this todo stays small and fits
inside M1 P8 alongside the other two prompt-bundle todos.

### Scope

1. **System prompt rule** in `DefaultChatServiceImpl`:
   - "Never use internal entity names (e.g. names starting with a host prefix like `jmixapp_`) in
     user-facing replies. Always use the human-readable label provided in `agent.entities` or
     returned by tools."
   - "Do not narrate tool calls. Answer the user's question directly. The orchestration layer is
     not part of the conversation."
   - Pair with todo `inject-readable-entity-inventory-into-baseline-context.md`: that todo
     guarantees the label is available; this todo enforces the model uses it.

2. **`ToolResultFormatter` — promote label, keep name as fallback**:
   - Each tool result currently wraps payload in `<data entity="jmixapp_Customer">…`. Change to
     `<data entity="Khách hàng" type="jmixapp_Customer">…` so the model sees the label first.
   - For row-level rendering, ensure each row's `_instance_name` (the human-friendly identifier
     Jmix already computes) leads the projection. Verify that `find_records` / `get_record`
     output puts label-shaped fields before raw key fields.

3. **Tests**:
   - Prompt-contract test: a chat reply to *"có bao nhiêu khách hàng?"* must NOT contain the
     literal substring matching the internal entity name pattern (e.g. regex `\bjmixapp_\w+\b`).
   - Prompt-contract test: reply must NOT contain literal tool names (`find_records`,
     `count_records`, `list_entities`, `describe_entity`, `get_record`, `get_related_records`,
     `RETRIEVAL`).
   - These belong in the same suite as `enforce-unknown-entity-retry-contract.md` and the
     existing semantic-similarity tests in P8 TEST-05.

### Decisions to make during planning

- Should the regex test for internal name leakage be model-agnostic or model-specific? Smaller
  models (gpt-4o-mini) may slip more often than gpt-4o. Lean toward strict regex on all models; if
  a specific cheap model fails, that's signal we shouldn't run it on the user-facing path.
- **Localization**: `agent.entities` inventory ships labels via `MessageTools.getEntityCaption`,
  which is locale-aware. Verify Vietnamese + English locale produce different label text in the
  prompt, and that the prompt-contract regex test runs in both locales.

## Relationship to existing todos

- `inject-readable-entity-inventory-into-baseline-context.md` (M1 P8): provides the label
  inventory the prompt rule needs. Ship this todo AFTER inventory todo lands, not before.
- `enforce-unknown-entity-retry-contract.md` (M1 P8): orthogonal — that one fixes correctness on
  miss; this one fixes presentation on success. Same prompt file, different rules.
- `add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` (M2): the UI counterpart of
  this todo. Even with prompt-side leakage closed, advanced users still want to see what the AI
  did — that's the secondary surface, separate work.
- `refine-describe-entity-wrapper-around-selected-jmix-metadata.md` (M2-leaning): adds richer
  metadata (label, comment) to `describe_entity`. Complementary; reduces residual cases where the
  model still has to guess a domain term.
