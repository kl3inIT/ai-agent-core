# Roadmap: Jmix AI Agent (ai-agent-core)

## Milestones

- ✅ **v1.0.0 MVP** — Phases 1–8 (+ inserted 7.1, 7.2) — shipped 2026-04-26 — [archive](milestones/v1.0.0-ROADMAP.md)
- ✅ **v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces** — Phases 9–14 (+ follow-up 13.1) — shipped 2026-05-11 — [archive](milestones/v1.1.0-ROADMAP.md) · [requirements](milestones/v1.1.0-REQUIREMENTS.md) · [milestone audit](milestones/v1.1.0-MILESTONE-AUDIT.md)
- ✅ **v1.2.0 Operator Experience & Runtime Performance** — Phases 15–18 — shipped 2026-06-12 — [archive](milestones/v1.2.0-ROADMAP.md) · [requirements](milestones/v1.2.0-REQUIREMENTS.md)

## Phases

<details>
<summary>✅ v1.0.0 MVP (Phases 1–8 + 7.1, 7.2) — SHIPPED 2026-04-26</summary>

Full detail: [milestones/v1.0.0-ROADMAP.md](milestones/v1.0.0-ROADMAP.md) · phase history: [milestones/v1.0.0-phases/](milestones/v1.0.0-phases/)

A reusable Jmix AI agent add-on with secure metadata-first read-only tools (via `AccessManager`/`DataManager`), Spring AI ChatClient orchestration with JDBC chat memory + conversation projection + durable audit, pgvector RAG ingestion/retrieval with role-scoped filters, prompt-injection-safe result formatting, Flow UI (chat, conversations, parameters, knowledge base, tree-lite audit), SPI extension points, packaged as `ai-agent` + `ai-agent-starter` with Spring Boot auto-config + CI + operator docs.

</details>

<details>
<summary>✅ v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces (Phases 9–14 + 13.1) — SHIPPED 2026-05-11</summary>

Full detail: [milestones/v1.1.0-ROADMAP.md](milestones/v1.1.0-ROADMAP.md) · requirements: [milestones/v1.1.0-REQUIREMENTS.md](milestones/v1.1.0-REQUIREMENTS.md) · milestone audit: [milestones/v1.1.0-MILESTONE-AUDIT.md](milestones/v1.1.0-MILESTONE-AUDIT.md)

- [x] **Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening** (7/7 plans) — completed 2026-04-27
- [x] **Phase 10: AI-Specific LLM Exposure Policy** (10/10 plans) — completed 2026-04-28
- [x] **Phase 11: Mutation-Capable Built-In Tools** (16/16 plans) — completed 2026-04-29
- [x] **Phase 12: Configurable Chat Surfaces** (6/6 plans) — completed 2026-05-05
- [x] **Phase 13: Chat Task File — Attach + LLM Read + Bulk Save** (6/6 plans) — completed 2026-05-06
- [x] **Phase 13.1: Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context** (7/7 plans) — completed 2026-05-07
- [x] **Phase 14: Intent-Driven Extraction → Form Prefill** (10/10 plans) — completed 2026-05-09, PR #28, UAT 2026-05-11

</details>

<details>
<summary>✅ v1.2.0 Operator Experience & Runtime Performance (Phases 15–18) — SHIPPED 2026-06-12</summary>

Full detail: [milestones/v1.2.0-ROADMAP.md](milestones/v1.2.0-ROADMAP.md) · requirements: [milestones/v1.2.0-REQUIREMENTS.md](milestones/v1.2.0-REQUIREMENTS.md)

Four near-independent feature areas on the v1.1 harness with effectively zero new runtime dependencies; hard ordering Phase 17 → Phase 18 honored. **Voice Input (Phase 19) was descoped → Backlog 999.2; the milestone was renamed to drop "Voice Input".**

- [x] **Phase 15: Right-Sidebar Chat Surface & Observability UX** (6/6 plans) — completed 2026-05-12 — `SIDEBAR` surface over the shared `ChatPanelFragment` + ephemeral KIND-keyed streaming-status line + collapsed per-turn tool-detail disclosure, no new persisted state; TEST-19 leak gate. (SURF-11, OBS-01/02/04, TEST-19)
- [x] **Phase 16: Admin Settings — Model Picker & Config-Knob Migration** *(merged 2026-05-13 from old 16+17)* (9/9 plans) — completed 2026-05-13 — curated open-weights `ComboBox` + custom entry; three-tier knob taxonomy (Tier-1 editable / Tier-2 read-only / Tier-3 indicator-only); `AiSettingsChangedEvent` cache eviction; SEC-08. (MODEL-01..03, CFG-01..03, SEC-08, TEST-20)
- [x] **Phase 17: Mutation-Internals Hardening (Phase 11 follow-up)** *(was 18)* (5/5 plans) — completed 2026-05-31 — canonical `MutationGateChain`, constrained batch FK loads, memoized related-write metadata; MUT-18 byte-for-byte parity HOLDS. Promoted Backlog 999.1. (MUT-15..18)
- [x] **Phase 18: AI-Runtime Performance Pass (targeted)** *(was 19)* (5/5 plans) — completed 2026-06-09 — per-turn `RunContext` memoization, app-wide denylist cache evicted on `LlmExposureChangedEvent`, RAG `Filter.Expression` once per retrieval, task-file `Media` regression-locked; checkable proxies; 865 tests green. (PERF-01..05)

</details>

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1–8 (+ 7.1, 7.2) | v1.0.0 | — | Shipped | 2026-04-26 |
| 9–14 (+ 13.1) | v1.1.0 | 62/62 | Shipped | 2026-05-11 |
| 15. Right-Sidebar Chat Surface & Observability UX | v1.2.0 | 6/6 | Shipped | 2026-05-12 |
| 16. Admin Settings — Model Picker & Config-Knob Migration | v1.2.0 | 9/9 | Shipped | 2026-05-13 |
| 17. Mutation-Internals Hardening (Phase 11 follow-up) | v1.2.0 | 5/5 | Shipped | 2026-05-31 |
| 18. AI-Runtime Performance Pass (targeted) | v1.2.0 | 5/5 | Shipped | 2026-06-09 |

## Notes

- Phase numbering is monotonic across milestones: v1.0.0 = Phases 1–8 (+ 7.1, 7.2); v1.1.0 = Phases 9–14 (+ 13.1); v1.2.0 = Phases 15–18 (former Phases 16 "Admin Model Management" + 17 "Admin Config-Knob Migration" merged into the new Phase 16 on 2026-05-13; former Phases 18/19/20 renumbered to 17/18/19; **Phase 19 (Voice Input) descoped at v1.2.0 close → Backlog 999.2**).
- v1.2.0 shipped 22/29 defined requirements; the 7 STT requirements (STT-01..06, TEST-18) were deferred with Phase 19 → Backlog 999.2.
- Carried debt (NOT in any shipped phase): Phase 10 re-verification + Nyquist `*-VALIDATION.md` backfill (phases 9/10/11/12/13/13.1); PKG-05/TEST-07 clean-consumer smoke (v1.0.0 Plan 08-05 carryover); `TranscriptionPostProcessor` SPI + custom STT-provider SPI; per-conversation end-user model switching; admin-screen performance work; attribute-path-level exposure rules; dormant seeds SEED-001/002/003/004/006/008/009; per-tool description/knob overrides à la jmix-ai-backend.
- Open at v1.2.0 close (deferred, see STATE.md § Deferred Items): 2 debug sessions (`bulk-create-allowlist-collision`, `bulk-create-confirm-throws`); Phase 17 UAT 4 pending scenarios (non-blocking — MUT-18 parity HOLDS via the automated suite).

## Backlog

_(Phase 999.1 (mutation-internals hardening) was promoted into v1.2.0 as Phase 17 and shipped 2026-05-31. Phase 999.2 (Chat Voice Input — Soniox STT) was promoted into v1.2.0 as Phase 19, then descoped at the v1.2.0 close on 2026-06-12 and returned here for a future milestone.)_

### Phase 999.2: Chat Voice Input — Soniox STT (+ OpenAI fallback) (BACKLOG)

**Goal:** With STT enabled and a provider key configured, an operator can dictate chat input — browser-recorded audio transcribed server-side, landing in `MessageInput` for review/edit before sending — through a pathway structurally disjoint from `ChatService`/`ChatClient` and privacy-safe in audit by default.

**Requirements:** STT-01, STT-02, STT-03, STT-04, STT-05, STT-06, TEST-18 (see [milestones/v1.2.0-REQUIREMENTS.md](milestones/v1.2.0-REQUIREMENTS.md) for full text).

**Carried scope (from the descoped Phase 19):**

- `com.vn.agent.stt.*` package — `TranscriptionService` + `SonioxTranscriptionService` (`RestClient`) + `SpringAiTranscriptionService` (fresh `OpenAiAudioApi` against `https://api.openai.com/v1`, independent key) + `AiAgentSttProperties` + selector `@Bean`.
- `@JsModule` `MediaRecorder` mic recorder + `UploadHandler` audio receiver mounted in `ChatPanelFragment.messageInputSlot`; ~60s cap, countdown, auto-stop; `webm/opus` + `mp4` Safari fallback, no transcoding.
- `STT_TRANSCRIPTION` audit via `AuditWriter.writeToolCall(eventName="stt_transcription")` — no new `AuditKind`; SHA-256 hash by default (`AuditFieldHasher`), raw transcript opt-in via `ai-agent.stt.audit.store-transcript=true`; HTTP clients never log response bodies.
- Bounded `aiAgentSttExecutor`; result pushed via `ui.access(...)`, dropped silently (after Soniox `DELETE`s) if detached; non-blocking inline error + retry reusing Phase 15's in-fragment status-row pattern.
- Soniox key / OpenAI-STT key / OpenRouter chat key are three independent properties; `ai-agent.stt.*` knobs are mostly Tier-2 boot toggles / Tier-3 secrets — this phase owns adding its own `store-transcript` toggle (or leaving it a property per CFG-02).
- `ai-agent.stt.enabled=false` (default) → no `TranscriptionService` bean, no mic button (boot test asserts zero STT beans).

**Out of scope (carried):** auto-send without review; client-side transcoding; live/interim transcript; "meeting mode"; routing transcription through OpenRouter; a Soniox Java SDK; `TranscriptionPostProcessor` SPI + custom STT-provider SPI (→ SPI-11 / STT-FUT-01).

**Estimated scope:** ~6 plan phase. Promote with `/gsd-review-backlog` (or include in `/gsd-new-milestone`) when ready.

### Phase 999.1: Admin-rotated provider credentials (API key + base URL) (BACKLOG)

**Goal:** Give admins an in-app UI to rotate AI provider credentials (API key + base URL) without redeploying. Currently keys/URLs live in `application.yml` + env vars only.

**Requirements:** TBD

**Context (captured 2026-05-14 from Phase 16 UAT conversation):**

- Pain: provider key/url rotation requires redeploy.
- Multi-provider: OpenRouter Claude needs key + url; Qwen self-hosted needs url only — UI must handle both shapes.
- Hard constraint: must NOT violate Phase 16 SEC-08 invariants. `SecretRedactionInvariantsTest.noSecretBoundEditable` / `noConditionalOnPropertyToggleBoundEditable` source-scans WILL fail the build if an editable `property=` binding to `*.api-key`/`*.password`/`*.secret`/`*.token` is added. Discuss-phase MUST resolve how a new credentials view side-steps these scans without weakening them.
- Open design questions: new `AiProviderCredential` entity vs extend `AiUiSettings`; encryption-at-rest (Jmix `EncryptedFieldType` / Jasypt / pgcrypto); write-only "Replace key" UX with masked `••••last4`; new audit kind `PROVIDER_CREDENTIAL_ROTATED`; runtime `ChatClient` rebuild via a new `AiSettingsChangedEvent.Kind.PROVIDER_CREDENTIAL` (single-publish-site invariant still applies); multi-provider UI shape; attribute-level policy hiding `apiKey` readback even from admin.
- Estimated scope: 4–6 plan phase.

**Plans:** TBD (promote with `/gsd-review-backlog` when ready)
