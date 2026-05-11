# Phase 14: Intent-Driven Extraction → Form Prefill - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in `14-CONTEXT.md` — this log preserves the alternatives considered.

**Date:** 2026-05-07
**Phase:** 14-intent-driven-extraction-form-prefill
**Areas discussed:** Schema synthesis + LLM context routing, Card-row component + click-handler wiring, Audit metadata for denied-attribute count, Draft lifecycle on close-without-save + multi-file extractor input

---

## Schema synthesis + LLM context routing

### Q1 — FK shape in the synthesizer

| Option | Description | Selected |
|--------|-------------|----------|
| UUID string only | FK appears as `{"type":"string","format":"uuid"}`; `DraftLoader` resolves at prefill | ✓ |
| Nested instance-name lookup hint | `{type:object, properties:{id, hint}}` with engine fuzzy resolution | |
| Skip FK attrs entirely in v1.1 | Synthesizer omits to-one FKs | |

**User's choice:** UUID string only — same shape `create_record` already accepts.

### Q2 — Synthesizer exclusions (multi-select)

| Option | Description | Selected |
|--------|-------------|----------|
| System audit attrs | id, version, createdBy/createdDate, lastModifiedBy/lastModifiedDate, deletedBy/deletedDate | ✓ |
| Non-`canModify` attrs for current user | Per-attribute `EntityAttributeContext.canModify` filter | ✓ |
| `@OneToMany` / collection attrs | Single-record extraction only in v1.1; EXTRACT-11 deferred | ✓ |
| Computed `@JmixProperty` attrs | Read-only / no setter — prefill cannot apply them | ✓ |

**User's choice:** ALL excluded.

### Q3 — Tool gating on named-intent turns

| Option | Description | Selected |
|--------|-------------|----------|
| Hard — only `prepare_form_draft` available | Single-callback list returned by `AgentToolCallbacks` | (effective via free-text) |
| Soft — system-prompt nudge only | Full tool surface stays available | |
| Hybrid — hard for mutation, soft for read | Strip mutation tools, keep read tools | |

**User's choice (free-text):** Phase 14 draft workflow does NOT replace existing mutation tools. Auto chat keeps the normal tool surface — explicit simple create/update requests may still use `create_record`/`update_record`, and explicit batch-save requests may use `bulk_save_records`. Named-intent turns are draft-first and expose ONLY `prepare_form_draft`. When user input is incomplete or ambiguous, the assistant asks for missing information instead of creating. Draft promotion to create/update/bulk-save happens only after explicit user action and reuses the existing Phase 11/13 mutation chain.

**Notes:** This combines hard-gate on named-intent turns with an explicit ask-clarifying-questions rule in the system prompt — see D-05 + D-06.

### Q4 — Structured-output target type

| Option | Description | Selected |
|--------|-------------|----------|
| `Map<String, Object>` + manual JSON-schema instruction | Spring AI `MapOutputConverter`; entity-generic, zero classloading | ✓ |
| Runtime-generated DTO via Byte Buddy / Javassist | Bytecode synthesis from MetaClass | |
| Use the Jmix entity class directly | `.entity(Customer.class)`; couples extraction to entity shape | |

**User's choice:** Map-based — keeps the engine entity-generic.

### Q5 — Strict-mode JSON schema

| Option | Description | Selected |
|--------|-------------|----------|
| Strict via prompt instruction only | Portable across Qwen3.6, future open-weights models | ✓ |
| Provider-native `responseFormat = json_schema, strict = true` | Stronger guarantee where supported; per-provider gating | |
| Hybrid — instruction + best-effort responseFormat | Always instruction; ALSO `responseFormat = json_object` when supported | |

**User's choice:** Instruction-only — provider-portable.

---

## Card-row component + click-handler wiring

### Q1 — Card-row component pattern

| Option | Description | Selected |
|--------|-------------|----------|
| Native `RadioButtonGroup` with custom item renderer | Vaadin RBG + `setRenderer(ComponentRenderer<>(...))` | (effective via free-text) |
| Hand-built `<flexLayout>` of toggleable `<button>` elements | Manual selection state + click handlers | |
| Native `Tabs` component | Vaadin Tabs (misleading 'switch view' semantics) | |

**User's choice (free-text):** Use Jmix Flow UI `radioButtonGroup` as the card-row component, with a custom `ComponentRenderer` supplied via `@Supply`. The component is declared in `chat-panel-fragment.xml`, populated from `IntentRegistry` in the controller, and styled as cards through CSS. Keeps single-select, keyboard nav, focus, ARIA from the native component while staying in the Jmix XML/controller pattern. Do NOT hand-build toggle buttons.

**Notes:** Direction tightens "native RadioButtonGroup" to specifically the Jmix Flow UI variant + `@Supply`-injected renderer (matches Phase 13.1 DataGrid-renderer convention from memory).

### Q2 — Click-handler wiring

| Option | Description | Selected |
|--------|-------------|----------|
| New view-scoped `OpenFormWithDraftHandler` bean | `@Component @VaadinSessionScope`; injected into `ChatPanelFragment`; renderer passes a `Consumer<DraftPayload>` | ✓ |
| `StreamEventRenderer` autowires `ViewNavigators` directly | Renderer becomes navigation-aware | |
| Inline lambda inside `ChatPanelFragment` | Method on the fragment invoked by renderer | |

**User's choice:** View-scoped `OpenFormWithDraftHandler` bean.

### Q3 — Confirm-button rendering location

| Option | Description | Selected |
|--------|-------------|----------|
| Inline inside the assistant message bubble | Append button + 1-line summary inside the bubble | ✓ |
| Separate confirm-card component below the bubble | Distinct vbox with stronger emphasis | |
| Right-pane attachments-style card | Inject into Phase 13.1 attachments right-pane | |

**User's choice:** Inline in bubble.

---

## Audit metadata for denied-attribute count

### Q1 — Audit shape (append-only constraint)

| Option | Description | Selected |
|--------|-------------|----------|
| Second TOOL-shaped row at prefill time | Two rows linked by `runId`: `prepare_form_draft` + `extraction.draft_applied` | ✓ |
| Update the tool row in place at prefill | Patch existing row's `resultSummary` | |
| Logs-only, no audit | slf4j WARN; loses grep | |
| Single tool row, optimistic count | Pre-compute denials at tool-call time | |

**User's choice:** Two TOOL rows linked by `runId` — preserves Phase 9 D-01 append-only.

### Q2 — Detail truncation

| Option | Description | Selected |
|--------|-------------|----------|
| Counts only — no attr names | Smallest payload; no forensic value | |
| Counts + bounded attr-name list (cap 16) | First 16 names + `truncated: true` flag | ✓ |
| Full uncapped lists | Risk of oversized rows on hostile output | |

**User's choice:** Bounded list (16).

### Q3 — Phase 10 exposure denial outcome

| Option | Description | Selected |
|--------|-------------|----------|
| `outcome=DENIED, denialReason="exposure_rule:{ruleId}"` | Reuses Phase 10/11 mutation idiom | ✓ |
| `outcome=ERROR` with `errorClass` | Generic error path | |

**User's choice:** DENIED + ruleId — reuses existing idiom.

---

## Draft lifecycle on close-without-save + multi-file extractor input

### Q1 — Close-without-save behavior

| Option | Description | Selected |
|--------|-------------|----------|
| TTL reaper only — row stays until 1h expiry | Tab-close is a non-event; mistake-friendly | ✓ |
| Delete on `BeforeCloseEvent` (no save) | Rapid cleanup; bad retry UX | |
| Explicit Cancel button + TTL fallback | Adds UI complexity | |

**User's choice:** TTL reaper only.

### Q2 — Confirm button re-clickability

| Option | Description | Selected |
|--------|-------------|----------|
| Re-clickable while draft exists | Idempotent reopen; disabled state when gone | ✓ |
| One-shot — disable on first click | Simpler logic, worse retry | |

**User's choice:** Re-clickable.

### Q3 — Multi-file extractor input

| Option | Description | Selected |
|--------|-------------|----------|
| All non-expired files — SPI takes `List<UUID>` + `List<MediaContent>` | Aligns with Phase 13.1 per-turn-all model | ✓ |
| Newest only — SPI keeps `Optional<UUID>` (singular) | Loses multi-page extraction | |
| LLM-picked via `contextRefs.taskFileIds` array | Chicken-and-egg without seeing Media | |

**User's choice:** List-based SPI.

### Q4 — Zero-file named-intent turn

| Option | Description | Selected |
|--------|-------------|----------|
| Allow — extractor uses `userMessage` text only | Keeps engine flexible; supports voice/text-only intents | ✓ |
| Block — named intent requires at least one attachment | Stricter; blocks valid text-only extraction | |
| Per-intent decision via `requiresTaskFile()` SPI flag | Adds boilerplate; defer | |

**User's choice:** Allow.

---

## Claude's Discretion

- CSS class names for card-row styling (`intent-card`, `intent-card--selected`, `intent-card-row`) — pick names consistent with Phase 13.1 conventions.
- Audit `resultSummary` JSON key ordering — pick deterministic JSON for grep + tests.
- `ToolNavigationLeakScannerTest` discovery mechanism — reflection vs grep; either works.
- `IntentRegistry` ordering for the card row — alphabetical / `@Order` / registration order; pick deterministic.
- Message-bundle key naming — follow `chatView.intent.*` namespace.

## Deferred Ideas

- Searchable-dropdown picker fallback for >6 intents (user: "deferred until a real app has enough intents to require it")
- `IntentExtractor.requiresTaskFile()` SPI flag
- Multi-intent parallel dispatch (EXTRACT-11)
- Host-supplied custom detail-view IDs
- Re-extraction / refresh button in chat
- Editing draft `payloadJson` outside the Jmix detail view
- Inline draft preview / diff card before form open
- Per-conversation TTL override
- Explicit Cancel button on the detail view
- Provider-native `responseFormat = json_schema, strict = true` (revisit if extraction-failure rate justifies)
- Batch-extraction tool (`prepare_bulk_form_drafts`)
