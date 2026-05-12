# Project Milestones: Jmix AI Agent (ai-agent-core)

## v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces (Shipped: 2026-05-11)

**Delivered:** The v1.0 read-only Jmix AI Agent grew into a mutation-capable, governance-aware, multi-surface chat add-on — without new core dependencies. Hard build-order honored: tool/prompt foundations → exposure policy → mutation tools → configurable surfaces / chat task file / intent extraction.

**Phases completed:** 7 — Phase 9, 10, 11, 12, 13, follow-up 13.1, 14 (62 plans, 138 tasks). Phase 15 (Chat Voice Input — Soniox STT) was sequenced last in v1.1, then deferred to v1.2.

**Key accomplishments:**

- **Phase 9 — Tool-Layer Foundations & Prompt-Contract Hardening:** `agent.entities` / `agent.permissions` baseline blocks, `describe_entity` widened via `MetadataTools`, `ToolFetchPlanCustomizer` SPI + projection-only `FetchPlanIntersector`, `unknown_entity` retry contract, `OutputScannerAdvisor` host-prefix/tool-name leak guards.
- **Phase 10 — AI-Specific LLM Exposure Policy (SEED-007 activated):** `AiExposureRule` (entity-level, `EXCLUDE`-only) + `LlmExposurePolicy` boundary (`userVisible AND NOT excluded`), uniformly enforced across schema discovery, tool calls, baseline prompt, and RAG; admin Flow UI; uniform `unknown_entity` opacity (never `access_denied`).
- **Phase 11 — Mutation-Capable Built-In Tools:** `BuiltInMutationTools` (default OFF), `create_record`/`update_record`/`add_related_record`/`remove_related_record`, layered fail-closed gating chain (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save), PII-safe `MutationErrorTranslator`, end-to-end audit incl. rollback.
- **Phase 12 — Configurable Chat Surfaces (SEED-005 activated):** `FULL_ROUTE` `ChatView` + `HEADER_BUTTON` `ChatDialogView` over one `ChatPanelFragment`, `AiUiSettings` admin toggle, `AiChatSessionState` cross-surface conversation continuity, async auto-titled conversations.
- **Phase 13 — Chat Task File — Attach + LLM Read + Bulk Save:** `AiTaskFile` transient entity (structurally disjoint from KB ingestion), attach UI, Spring AI `Media` injection, `bulk_save_records` tool (one transaction, batch idempotency) extending the Phase 11 chain, default chat model swap to multimodal `qwen/qwen3.6-35b-a3b` (Apache-2.0, self-hostable).
- **Phase 13.1 — Chat Attachments CRM-Style Right-Pane + Persistent Multi-Turn Context:** verbatim jmix-crm right-pane port (card grid + drop-zone + empty state), per-turn-all `Media` injection with LRU token-budget cap + `task_file_budget_exceeded` audit, conversation-scoped 24h TTL, inline `[user] added attachment` notice rows.
- **Phase 14 — Intent-Driven Extraction → Form Prefill:** `IntentExtractor<T>` SPI + `IntentRegistry` + prompt-only `MetaClassDtoSynthesizer`, persisted `AiExtractionDraft` (owner-scoped, TTL, hidden from the LLM), `prepare_form_draft` + server-validated `propose_action_choices` tools, chat-rendered confirm / action-choice rows, controller-side-only navigation (`OpenFormWithDraftHandler`), permission-gated `setValueIfPermitted` prefill, host `CustomerDraftIntentExtractor` reference; **the LLM never receives `ViewNavigators` or any UI-mutation primitive**. Shipped via PR #28; manual chat-to-form UAT accepted.

**Quality gates:** Cross-phase integration audit PASS (8/8 wiring points wired, 5/5 E2E flows pass) — see `milestones/v1.1.0-MILESTONE-AUDIT.md`. Phase 14 code review: 0 blocker / 0 critical / 6 warning (all 6 fixed pre-merge) / 5 info; CI green after fixing a test stub gap.

**Milestone debt carried forward (not blockers):**

- Phase 10 `10-VERIFICATION.md` status remains `human_needed` — phase goal achieved (4/4 ROADMAP criteria, 12/12 REQ IDs); the substantive REVIEW items (BLOCKER-01/02, WARNING-08) are fixed in code; the visual-UI checks + WARNING-01 (RAG partial-failure window) were never formally recorded. Optional `/gsd-verify-work 10` in v1.2.
- Nyquist `*-VALIDATION.md` exists only for Phase 14; backfill phases 9/10/11/12/13/13.1 with `/gsd-validate-phase` as a v1.2 hardening pass.
- Phase 14 code-review INFO items IN-01..IN-05 (cosmetic) remain open.

**Deferred to v1.2:** Phase 15 — Chat Voice Input · Soniox STT (`STT-01..06`, `SPI-11`, `TEST-17`) — see `ROADMAP.md` Backlog → Phase 999.2. Plus Backlog Phase 999.1 (Phase 11 mutation-internals hardening). The collapsible per-turn tool-detail panel + ephemeral streaming-status indicator remain out of scope. PKG-05 / TEST-07 clean-consumer smoke still carried from v1.0.0 Plan 08-05.

**Known deferred planning artifacts at close:** 14 (1 quick-task — actually complete, scanner mis-flag; 1 deferred todo; 6 dormant seeds; 5 already-resolved UAT artifacts; 1 Phase 10 verification gap) — see `.planning/STATE.md` § Deferred Items. At close, 9 capture-note todos were moved to `todos/done/`; SEED-005 & SEED-007 flipped to `implemented`.

**Archived artifact sets:**

- `.planning/milestones/v1.1.0-ROADMAP.md`
- `.planning/milestones/v1.1.0-REQUIREMENTS.md`
- `.planning/milestones/v1.1.0-MILESTONE-AUDIT.md`
- Phase artifacts: kept in `.planning/phases/` (run `/gsd-cleanup` later to archive retroactively).

**What's next:** `/gsd-new-milestone` for v1.2 — likely scope: Phase 15 (Chat Voice Input — Soniox STT) promoted from Backlog 999.2, Phase 10 re-verification + Nyquist backfill, Phase 11 mutation-internals hardening (Backlog 999.1).

---

## v1.0.0 MVP (Shipped: 2026-04-26)

**Delivered:** A reusable Jmix AI agent add-on with secure metadata tools, Spring AI orchestration, RAG, guardrails, Flow UI, audit tree, release docs, and CI.

**Phases completed:** 1-8 plus inserted 7.1 and 7.2 (63 plans total)

**Key accomplishments:**

- Packaged the add-on as `ai-agent` + `ai-agent-starter` with Spring Boot auto-configuration and Maven publishing metadata.
- Implemented metadata-first, read-only tool access through Jmix AccessManager and DataManager with prompt-injection-safe result formatting.
- Built ChatClient orchestration with JDBC chat memory, conversation projection, durable audit events, and SPI extension points.
- Added pgvector RAG ingestion/retrieval with role-scoped filters, async processing, delete/reingest flows, and status tracking.
- Delivered Flow UI for chat, conversations, parameters, knowledge base, and tree-lite audit inspection.
- Closed Phase 8 security/release gaps, including jmix-security-data enforcement, GitHub Actions CI, operator README, CHANGELOG, and broad regression green.

**Stats:**

- 10 phase directories, 63 plans, 63 summaries
- 529 files changed from initial commit to release HEAD
- 105255 insertions, 1060 deletions over the milestone range
- Repository text/code corpus at close: ~149096 lines across Java/XML/properties/Gradle/YAML/Markdown
- Timeline: 2026-04-18 → 2026-04-26

**Git range:** 566ccfb → dd0d13e before milestone archive commits

**Known deferred items at close:** 20 open planning artifacts acknowledged as deferred; see .planning/STATE.md Deferred Items.

**Known milestone debt:** PKG-05/TEST-07 clean-consumer smoke remains deferred from Plan 08-05; follow-up should choose either a stub VectorStore boot mode or a Testcontainers-backed consumer smoke.

**Archived artifact sets:**

- `.planning/milestones/v1.0.0-ROADMAP.md`
- `.planning/milestones/v1.0.0-REQUIREMENTS.md`
- `.planning/milestones/v1.0.0-phases/`
- `.planning/milestones/v1.0.0-adr/`
- `.planning/milestones/v1.0.0-codebase/`
- `.planning/milestones/v1.0.0-debug/`
- `.planning/milestones/v1.0.0-forensics/`
- `.planning/milestones/v1.0.0-quick/`
- `.planning/milestones/v1.0.0-research/`
- `.planning/milestones/v1.0.0-test-fixtures/`
- `.planning/milestones/v1.0.0-todos-completed/`

**What's next:** Define the next milestone with `$gsd-new-milestone`; likely candidates are consumer-smoke hardening and prompt-contract/UI clarity work.

---
