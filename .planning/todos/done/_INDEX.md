# Completed Todos

Idea-capture notes whose work shipped. Kept for provenance; the authoritative record is the phase `PLAN.md`/`SUMMARY.md`/`VERIFICATION.md` and `REQUIREMENTS.md`.

| Todo | Shipped in | Notes |
|------|------------|-------|
| `2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md` | v1.1.0 — Phase 9 | `ToolFetchPlanCustomizer` SPI + `FetchPlanIntersector` (TOOL-10, TOOL-11, SPI-09) |
| `2026-04-24-add-intent-driven-extraction-to-prefilled-jmix-forms.md` | v1.1.0 — Phase 14 | `IntentExtractor<T>` SPI, `prepare_form_draft`, `AiExtractionDraft`, `OpenFormWithDraftHandler` (EXTRACT-01..10); shipped via PR #28 |
| `2026-04-24-add-llm-permission-inventory.md` | v1.1.0 — Phase 9 | `agent.permissions` in baseline + `describe_entity` per-attribute readability (TOOL-12, PROMPT-02) |
| `2026-04-24-enforce-unknown-entity-retry-contract.md` | v1.1.0 — Phase 9 | `unknown_entity` structured error + `list_entities`-once retry hint (PROMPT-05) |
| `2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md` | v1.1.0 — Phase 9 | `describe_entity` widened via `MetadataTools` (TOOL-09) |
| `2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md` | v1.1.0 — Phase 9 | `OutputScannerAdvisor` host-prefix/tool-name pattern packs + prompt vocabulary rules (PROMPT-03, PROMPT-04, PROMPT-06) |
| `2026-04-26-inject-readable-entity-inventory-into-baseline-context.md` | v1.1.0 — Phase 9 | `agent.entities` block in baseline context (PROMPT-01) |
| `2026-04-28-add-deep-link-generator-tool.md` | v1.1.0 — Phase 11 | `BuiltInLinkTools` — always-on `@Tool` methods over `ViewRegistry`/`ServerProperties` (Plan 11-08) |
| `2026-04-28-add-llm-auto-generated-conversation-titles.md` | v1.1.0 — Phase 12 | Async conversation auto-title + pencil-edit override (Plan 12-05) |
| `2026-04-24-add-dedicated-chat-speech-and-file-task-input.md` | v1.1.0 (partial) — Phase 13 / 13.1 | **File task input** shipped: `AiTaskFile` + attach UI + Spring AI `Media` injection + `bulk_save_records` (TASK-01..06, ENT-07, MUT-14, TEST-16) and the 13.1 CRM right-pane reshape. **Chat speech (STT)** was split into Phase 15 and **deferred to v1.2** — see `ROADMAP.md` Backlog → Phase 999.2 and `REQUIREMENTS.md` "Deferred to v1.2". |
| `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` | Phase 15 — Right-Sidebar Chat Surface & Observability UX | Ephemeral KIND-keyed streaming-status `<span class="ai-agent-status">` (OBS-01) + collapsed-by-default per-turn tool-detail `Details` in one `.ai-agent-turn-activity` block (OBS-02), in the shared `ChatPanelFragment`; backed by `StreamingEvent.Activity(ActivityKind)` (15-02) + a name-column-free `AiAuditEvent` audit read. Decisions D-05..D-08; leak-gated by `ObservabilityLeakTest` (TEST-19) reusing the Phase 9 `TOOL_NAME_LEAK` / `HOST_PREFIX_LEAK` pattern packs. No new persistence (OBS-04). See `15-01-SUMMARY.md` … `15-05-SUMMARY.md`. |

Still pending (not done): none — the pending queue is empty.
