---
created: 2026-04-26T02:35:00+07:00
title: Hide internal tool and entity names from user-facing chat
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
---

## Problem

Current chat surface leaks the implementation contract between the add-on and the LLM into the
end-user UI. Two distinct rivers of leakage:

1. **Reply text echoes internal entity names.** The LLM is given raw Jmix entity names
   (`jmixapp_Customer`, `jmixapp_Order`) so it can call tools. With no prompt rule against it, the
   model copies those names verbatim into its reply: *"Tôi đã tìm thấy 5 jmixapp_Customer..."*.
   These names are an internal contract — host prefixes, snake casing, plural conventions — they
   mean nothing to a non-developer end user and look like a bug to a developer.
2. **Reply text narrates tool calls.** The model says *"Tôi sẽ gọi `find_records` để..."* or
   *"Sau khi `RETRIEVAL` xong..."*, exposing the tool layer that should be invisible. End users
   want answers, not a play-by-play of the orchestration.

This is a clear separation-of-concerns violation:

- **Audit panel** (`AiToolCallAudit` / `AiAuditEvent` per the tree-lite redesign) IS the technical
  channel — admins / developers go there to see exactly what was called, with what arguments, in
  what order. Raw entity names and tool names belong here. No change needed.
- **Chat panel** is the end-user channel — should speak the host's domain language (entity labels,
  plain verbs), never the add-on's tool surface.

Currently the two are blurred: `ToolResultFormatter.records(...)` wraps results in
`<data>`-delimited blocks keyed by raw entity name, with no label prominence, so the model has no
prompt-side incentive to translate. There is also no UI affordance (collapsible "AI did X" panel,
secondary slot in the message bubble) that lets us route tool detail away from the answer surface
the way `jmix-ai-backend` reference and ChatGPT do.

## Solution

Split into two layers with different cost and different milestone fit.

### Part A — Prompt + formatter rules (M1 candidate, low cost)

Solves ~80% of the leak with no UI work. Three concrete changes:

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

### Part B — UI: collapsible tool-detail surface (M2 candidate, scope-heavy)

Even with Part A locked down, advanced users sometimes want to see what the AI did. Move that
detail into an explicit, secondary surface — never the primary answer:

1. **ChatPanel layout**:
   - Primary: assistant message text only.
   - Secondary: collapsed "AI did" panel per turn — count of tool calls, latency, an "open in
     audit" link that deep-links to the new tree-lite audit row by `runId`.
   - Default collapsed; expand only on user click. Mirrors `jmix-ai-backend` reference + ChatGPT.

2. **Streaming considerations**:
   - During streaming, surface "Đang tìm kiếm..." / "Đang truy xuất tài liệu..." status as
     ephemeral text, NOT as part of the final reply. Status should disappear once the assistant
     message finalizes.
   - Use Vaadin `MessageList` secondary slot or a sibling component — do not concatenate status
     into the message body.

3. **Data source**:
   - Reuse the `AiAuditEvent` rows from todo `redesign-audit-schema-as-tree-lite-...`. Each chat
     turn already maps to one root row + N children; the UI just renders that tree, scoped to the
     current conversation + current user.

### Decisions to make during planning

- **Part A**: should the regex test for internal name leakage be model-agnostic or model-specific?
  Smaller models (gpt-4o-mini) may slip more often than gpt-4o. Lean toward strict regex on all
  models; if a specific cheap model fails, that's signal we shouldn't run it on the user-facing
  path.
- **Part B**: secondary panel as part of the message bubble (per-turn) vs separate side panel
  (per-conversation drill-down). Per-turn matches user intuition better but costs more layout
  work. Defer the call to UI design in M2.
- **Localization**: `agent.entities` inventory ships labels via `MessageTools.getEntityCaption`,
  which is locale-aware. Verify Vietnamese + English locale produce different label text in the
  prompt, and that the prompt-contract regex test runs in both locales.
- **Status text wording (Part B)**: "Đang tìm kiếm dữ liệu...", "Đang tra cứu tài liệu...", etc.
  — must not name tools. Tied to i18n message bundle.

### Why split into two parts

- Part A pays for itself in M1 P8: it lands inside the same prompt + test surface as
  `enforce-unknown-entity-retry-contract.md` and `inject-readable-entity-inventory-into-baseline-context.md`.
  All three plans should probably be one phase plan with a shared test suite.
- Part B is real UI work — touches `ChatView`, `ChatPanelFragment`, possibly a new fragment for
  the audit-detail panel, plus i18n. That's M2 territory and overlaps with todos
  `add-dedicated-chat-speech-and-file-task-input.md` and
  `add-intent-driven-extraction-to-prefilled-jmix-forms.md` — design them together to avoid
  rebuilding the chat layout twice.

## Relationship to existing todos

- `inject-readable-entity-inventory-into-baseline-context.md` (this milestone): provides the
  label inventory the prompt rule needs. Ship Part A AFTER that todo lands, not before.
- `enforce-unknown-entity-retry-contract.md` (this milestone): orthogonal — that one fixes
  correctness on miss; this one fixes presentation on success. Same prompt file, different rules.
- `refine-describe-entity-wrapper-around-selected-jmix-metadata.md` (M2-leaning): adds richer
  metadata (label, comment) to `describe_entity`. Complementary; reduces residual cases where the
  model still has to guess a domain term.
- `redesign-audit-schema-as-tree-lite-...` (in flight on current branch): Part B's UI surface
  reads from the tree-lite event rows. Part B planning depends on this redesign being merged.
- `add-dedicated-chat-speech-and-file-task-input.md` + `add-intent-driven-extraction-to-prefilled-jmix-forms.md`
  (M2): both touch `ChatView` / `ChatPanelFragment`. Coordinate Part B with these to avoid
  layout churn.
