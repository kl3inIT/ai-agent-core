# Roadmap: Jmix AI Agent (ai-agent-core)

## Milestones

- ✅ **v1.0.0 MVP** — Phases 1–8 (+ inserted 7.1, 7.2) — shipped 2026-04-26 — [archive](milestones/v1.0.0-ROADMAP.md)
- ✅ **v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces** — Phases 9–14 (+ follow-up 13.1) — shipped 2026-05-11 — [archive](milestones/v1.1.0-ROADMAP.md) · [requirements](milestones/v1.1.0-REQUIREMENTS.md) · [milestone audit](milestones/v1.1.0-MILESTONE-AUDIT.md)
- 📋 **v1.2 (next)** — not yet started; run `/gsd-new-milestone`. Likely scope: Phase 999.2 (Chat Voice Input — Soniox STT, deferred from v1.1), Phase 10 re-verification + Nyquist backfill, Phase 999.1 (Phase 11 mutation-internals hardening), PKG-05/TEST-07 clean-consumer smoke.

## Phases

<details>
<summary>✅ v1.0.0 MVP (Phases 1–8 + 7.1, 7.2) — SHIPPED 2026-04-26</summary>

Full detail: [milestones/v1.0.0-ROADMAP.md](milestones/v1.0.0-ROADMAP.md) · phase history: [milestones/v1.0.0-phases/](milestones/v1.0.0-phases/)

A reusable Jmix AI agent add-on with secure metadata-first read-only tools (via `AccessManager`/`DataManager`), Spring AI ChatClient orchestration with JDBC chat memory + conversation projection + durable audit, pgvector RAG ingestion/retrieval with role-scoped filters, prompt-injection-safe result formatting, Flow UI (chat, conversations, parameters, knowledge base, tree-lite audit), SPI extension points, packaged as `ai-agent` + `ai-agent-starter` with Spring Boot auto-config + CI + operator docs.

</details>

<details>
<summary>✅ v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces (Phases 9–14 + 13.1) — SHIPPED 2026-05-11</summary>

Full detail: [milestones/v1.1.0-ROADMAP.md](milestones/v1.1.0-ROADMAP.md) · requirements: [milestones/v1.1.0-REQUIREMENTS.md](milestones/v1.1.0-REQUIREMENTS.md) · milestone audit: [milestones/v1.1.0-MILESTONE-AUDIT.md](milestones/v1.1.0-MILESTONE-AUDIT.md)

- [x] **Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening** (7/7 plans) — completed 2026-04-27 — `agent.entities`/`agent.permissions` baseline, `describe_entity` widening, `ToolFetchPlanCustomizer` SPI + `FetchPlanIntersector`, `unknown_entity` retry contract, output-scanner leak guards.
- [x] **Phase 10: AI-Specific LLM Exposure Policy** (10/10 plans) — completed 2026-04-28 — `AiExposureRule` (`EXCLUDE`-only) + `LlmExposurePolicy` boundary, admin Flow UI, uniform `unknown_entity` opacity, RAG cross-cut. (Verification status `human_needed` — see milestone audit; substantive items fixed in code.)
- [x] **Phase 11: Mutation-Capable Built-In Tools** (16/16 plans) — completed 2026-04-29 — `BuiltInMutationTools` (default OFF), layered fail-closed gating chain, `MutationGuard` SPI, `AiMutationIntent` idempotency, PII-safe error translation, end-to-end audit incl. rollback.
- [x] **Phase 12: Configurable Chat Surfaces** (6/6 plans) — completed 2026-05-05 — `FULL_ROUTE` + `HEADER_BUTTON` surfaces over one `ChatPanelFragment`, `AiUiSettings` admin toggle, `AiChatSessionState` continuity, async auto-title.
- [x] **Phase 13: Chat Task File — Attach + LLM Read + Bulk Save** (6/6 plans) — completed 2026-05-06 — `AiTaskFile` transient entity, attach UI, Spring AI `Media` injection, `bulk_save_records` tool, default chat model swap to multimodal `qwen/qwen3.6-35b-a3b` (Apache-2.0).
- [x] **Phase 13.1: Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context** (7/7 plans) — completed 2026-05-07 — jmix-crm right-pane port, per-turn-all `Media` with LRU budget cap, conversation-scoped 24h TTL, inline attachment notice rows.
- [x] **Phase 14: Intent-Driven Extraction → Form Prefill** (10/10 plans) — completed 2026-05-09, shipped via PR #28, manual UAT accepted 2026-05-11 — `IntentExtractor<T>` SPI, persisted `AiExtractionDraft` (hidden from LLM), `prepare_form_draft` + `propose_action_choices` tools, chat-rendered confirm/action rows, controller-side-only navigation, permission-gated prefill; the LLM never receives `ViewNavigators`.

</details>

### 📋 v1.2 (Planned)

No phases defined yet. Start with `/gsd-new-milestone`, then `/gsd-review-backlog` to promote the Backlog items below.

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1–8 (+ 7.1, 7.2) | v1.0.0 | — | Shipped | 2026-04-26 |
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | v1.1.0 | 7/7 | Shipped | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | v1.1.0 | 10/10 | Shipped (`10-VERIFICATION.md` still `human_needed` — see audit) | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | v1.1.0 | 16/16 | Shipped | 2026-04-29 |
| 12. Configurable Chat Surfaces | v1.1.0 | 6/6 | Shipped | 2026-05-02 |
| 13. Chat Task File — Attach + LLM Read + Bulk Save | v1.1.0 | 6/6 | Shipped | 2026-05-06 |
| 13.1. Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context | v1.1.0 | 7/7 | Shipped | 2026-05-07 |
| 14. Intent-Driven Extraction → Form Prefill | v1.1.0 | 10/10 | Shipped (PR #28; UAT passed 2026-05-11) | 2026-05-11 |

## Notes

- Phase numbering is monotonic across milestones: v1.0.0 = Phases 1–8 (+ inserted 7.1, 7.2); v1.1.0 = Phases 9–14 (+ follow-up 13.1). v1.2 continues from Phase 15 (Chat Voice Input — Soniox STT, sitting in the Backlog as Phase 999.2 until promoted).
- v1.1.0 milestone audit: PASS on integration (8/8 cross-phase wiring) + E2E (5/5 flows); status `tech_debt` for bookkeeping (Phase 10 verification doc stale at `human_needed`; phases 9/10/11/12/13/13.1 lack `*-VALIDATION.md`). See [milestones/v1.1.0-MILESTONE-AUDIT.md](milestones/v1.1.0-MILESTONE-AUDIT.md).
- Out of scope (carried, not in any milestone yet): collapsible per-turn tool-detail panel + ephemeral streaming-status indicator (UX polish); PKG-05/TEST-07 clean-consumer smoke (v1.0.0 Plan 08-05 carryover — needs Testcontainers pgvector OR a stub `VectorStore` boot mode).

## Backlog

### Phase 999.1: Phase 11 Mutation Hardening Follow-ups (BACKLOG)

**Goal:** Capture post-ship hardening for the mutation tool internals: refactor duplicated mutation gate sequencing, batch-load to-one FK references during mutation binding, and cache related-write metadata resolution where safe.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)

### Phase 999.2: Chat Voice Input — Soniox STT (BACKLOG → promote to v1.2)

> Originally Phase 15 of v1.1; sequenced last ("nice to have") and **deferred to v1.2 on 2026-05-11** at v1.1.0 close. Promote into v1.2 scope with `/gsd-review-backlog`. Requirement detail also mirrored in `milestones/v1.1.0-REQUIREMENTS.md` "Deferred to v1.2".

**Goal:** Users can dictate chat input via browser-recorded audio transcribed server-side through Soniox STT — text appears in `MessageInput` for review/edit before send; pathway is disjoint from the chat client and audit-privacy-safe by default.
**Depends on:** Phase 12 (`ChatPanelFragment.messageInputSlot` is the integration point and is stable from v1.0 + the Phase 12 surface contract); independent of Phase 10/11/13/14.
**Requirements:** STT-01, STT-02, STT-03, STT-04, STT-05, STT-06, SPI-11, TEST-17
**Success Criteria** (what must be TRUE):
  1. With `ai-agent.stt.enabled=true` and a Soniox API key (`ai-agent.stt.soniox.api-key`), the user clicks the mic button, records up to 60s via browser `MediaRecorder` (no transcoding — webm/opus or mp4 directly to `Authorization: Bearer` `POST /v1/files` then `POST /v1/transcriptions` with `model=stt-async-v4` + `language_hints: ["vi","en"]`), and the transcribed text appears in `MessageInput` for review/edit before send — `TranscriptionService` does NOT call `ChatService.ask` directly.
  2. `TranscriptionService` is a strategy interface with a default `SonioxTranscriptionService` impl (custom Spring `RestClient`-based — Soniox has no Java SDK) and an optional `SpringAiTranscriptionService` OpenAI-direct impl; selected via `ai-agent.stt.provider=soniox|openai|<custom-bean-name>` (default `soniox`). Hosts can register their own `TranscriptionService` bean and select it by bean name.
  3. By default the `STT_TRANSCRIPTION` audit row (via `AuditWriter.writeToolCall` `eventName=stt_transcription`, no new `AuditKind` — the string is already reserved) records duration, language, model, outcome, and SHA-256 transcript hash (NOT raw text); flipping `ai-agent.stt.audit.storeTranscript=true` stores the raw transcript instead (TEST-17 covers both modes).
  4. STT failures (provider 4xx, recording too long, network) surface a non-blocking error message + retry button in the input area; the chat flow itself remains usable. An optional `TranscriptionPostProcessor` SPI bean (= SPI-11) rewrites transcripts (PII redaction / vocabulary normalization) before they reach the input field.
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `/gsd-review-backlog` when v1.2 planning starts)

**Cross-cutting constraints:**
- Soniox uses an INDEPENDENT API key from the OpenAI/OpenRouter chat key. OpenRouter does NOT proxy `/audio/transcriptions`; if `provider=openai` is selected, the OpenAI key is required directly.
- Soniox file/transcription resources are cleaned up via `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` after retrieval.
- `AUD-06` (v1.1) already reserved `STT_TRANSCRIPTION` as an `eventName` string — no new `AuditKind` needed when this lands.
