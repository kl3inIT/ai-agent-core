# Requirements: Jmix AI Agent (ai-agent-core) — v1.2

**Defined:** 2026-05-11
**Milestone:** v1.2 — Operator Experience, Voice Input & Runtime Performance
**Core Value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

## v1.2 Requirements

Six near-independent feature areas layered onto the shipped v1.1 agent harness with effectively zero new runtime dependencies. Each maps to exactly one roadmap phase.

### Voice Input — Soniox STT (+ OpenAI-direct fallback)

- [ ] **STT-01**: With `ai-agent.stt.enabled=true` and a provider API key configured, a mic button appears in the chat input area in both the `FULL_ROUTE` and `HEADER_BUTTON` surfaces; when STT is disabled the button is absent (not greyed) and no `TranscriptionService` bean exists (default-config boot test asserts zero STT beans / no mic button).
- [ ] **STT-02**: User taps the mic to record browser audio (`MediaRecorder`, `audio/webm;codecs=opus` with an `audio/mp4` Safari fallback, no transcoding) with a hard ~60-second cap, a visible countdown, and auto-stop; "recording" and "transcribing…" are distinct visible states.
- [ ] **STT-03**: On stop, the audio is transcribed server-side and the resulting text is placed into `MessageInput` for the user to review/edit before sending — the transcription path never calls `ChatService.ask` and never auto-sends.
- [ ] **STT-04**: The default transcription path is Soniox async STT via a custom Spring `RestClient` client (`POST /v1/files` → `POST /v1/transcriptions` `model=stt-async-v4` `language_hints:["vi","en"]` → poll `GET /v1/transcriptions/{id}` → `GET .../transcript` → `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` in a `finally` on every path including errors); an OpenAI-direct fallback (`OpenAiAudioApi` / `OpenAiAudioTranscriptionModel` against `https://api.openai.com/v1` with an independent key — never the OpenRouter chat base-url) is selectable via `ai-agent.stt.provider=soniox|openai|<host-bean-name>` (default `soniox`). Soniox, OpenAI-STT, and the OpenRouter chat key are three independent properties.
- [ ] **STT-05**: STT failures (provider 4xx, network error, recording too long, no speech detected) surface a non-blocking inline error message + a retry button in the input area (reusing the Phase 15 in-fragment status-row pattern); the chat flow itself stays usable; transcription runs on a bounded executor and the result is pushed back via `ui.access(...)`, dropped silently (after running the Soniox `DELETE`s) if the UI/conversation has detached or closed mid-transcription.
- [ ] **STT-06**: Each transcription writes an `STT_TRANSCRIPTION` audit row via `AuditWriter.writeToolCall(eventName="stt_transcription", ...)` (no new `AuditKind`) recording duration, language, model, provider, and outcome plus a SHA-256 hash of the transcript by default (`AuditFieldHasher`); `ai-agent.stt.audit.store-transcript=true` stores the raw transcript instead. The Soniox/OpenAI HTTP clients never log response bodies (status/headers/ids at DEBUG only).

### Chat Observability & UX

- [x] **SURF-11**: A `SIDEBAR` / right-sidebar chat surface is implemented as a third chat surface beside `FULL_ROUTE` and `HEADER_BUTTON`, mounts the shared `ChatPanelFragment`, participates in the existing `AiUiSettings` enabled-surface controls, and preserves `AiChatSessionState` conversation continuity across surface switches. It must not introduce a separate chat backend, chat memory, or duplicate fragment implementation.
- [ ] **OBS-01**: An ephemeral streaming-status line renders in a sibling slot (not inside the message bubble), keyed by audit `KIND` ("thinking…", "searching data…", "retrieving documents…"), and clears completely when the turn finalizes — the status text is never concatenated into the final answer and never shows internal `@Tool` / entity names.
- [ ] **OBS-02**: Each completed turn shows a collapsed-by-default "what the agent did — N steps, total ms" disclosure listing humanized, label-only steps (KIND-keyed, never internal tool/entity names) with per-step timing and error/rollback indication; the disclosure is hidden entirely for turns with zero tool calls. A turn deep-links to its filtered audit list (`AiAuditEventListView?runId=...`).
- [x] **OBS-04**: The observability panels are driven by the existing `StreamingEvent` flux and `AiAuditEvent` tree — no new persisted "turn" entity, no parallel state store; per-turn detail held in the panels does not accumulate unbounded in `AiChatSessionState`. New labels use `msg://` keys in all locale bundles. (Resolves the pending `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo.)

### Admin Model Management

- [ ] **MODEL-01**: In the admin Parameters/Settings view the chat-model field becomes a `ComboBox` populated from a configurable curated catalog of common self-hostable open-weights model slugs with readable labels (the default marked); selecting an item writes the existing free-text `model` value in the active `AiParameters` profile.
- [ ] **MODEL-02**: The same control lets an admin enter a custom model name (any string) when the desired model is not in the curated list (`ComboBox.allowCustomValue` or a "Custom…" sentinel revealing a text field); the curated list contains only open-weights models per the self-hostable policy and custom entry is the escape hatch. Model validity is checked at first use with a clear error surfaced, not at save time.
- [ ] **MODEL-03**: Model selection is admin-only — end users cannot switch model per conversation; the chosen model flows through to per-request `ChatOptions`. All new labels use `msg://` keys in all locale bundles.

### Admin Config-Knob Migration

- [ ] **CFG-01**: Operator-relevant runtime-tunable prior-phase knobs — RAG `top-k`, RAG similarity threshold, task-file token budget, task-file TTL, and any other Tier-1 knobs identified by the audit — become editable in the admin UI, read fresh on each retrieval/turn, and take effect on the next turn without a restart, via the existing `AiParametersResolver`-style read-through (prefer the `AiParameters` / `AiUiSettings` value, fall back to the `module.properties` default). The strict `default-params.yaml` seed stays strict.
- [ ] **CFG-02**: Boot-time / wiring knobs (`@ConditionalOnProperty` toggles such as `ai-agent.tools.mutation.enabled`; when STT ships in Phase 20, also `ai-agent.stt.enabled` / `ai-agent.stt.provider`) are shown in the admin UI read-only with a clear "property only — requires restart" marker; secrets (`*.api-key`) are never editable or displayed — at most a "configured: yes/no" indicator. A documented three-tier taxonomy (runtime-editable → migrate; boot/wiring → read-only with note; secret → indicator only) classifies every audited knob.
- [ ] **CFG-03**: New editable settings are persisted as fields on `AiParameters` / `AiUiSettings` with an `agentstore` Liquibase changelog (included in `agentstore-changelog.xml`), bean-validation with sensible bounds, and labels in all locale bundles. An `AiParameters` / `AiUiSettings` change event is published so any cache around settings (see PERF) evicts — an admin edit is visible within one turn.

### Mutation Internals Hardening (Phase 11 follow-up)

- [ ] **MUT-15**: The fail-closed mutation gate sequence (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save) is extracted into one canonical `MutationGateChain` component; `create_record` / `update_record` / `add_related_record` / `remove_related_record` and `bulk_save_records` become thin adapters over it. The gate order is locked by a source-level invariant test — every gate throws before the transactional save (the chain itself carries no `@Transactional`; only `MutationSaveExecutor.save` does).
- [ ] **MUT-16**: To-one FK references are batch-loaded during mutation attribute binding — one **constrained** `DataManager.load(...).ids(...)` per target class (never `UnconstrainedDataManager`, never raw JPQL, so row-level security still applies) — replacing per-reference loads.
- [ ] **MUT-17**: Related-write metadata resolution (`(parentMetaClass, relationshipName)` → supported-relationship descriptor) is memoized (immutable Jmix metamodel; no eviction needed).
- [ ] **MUT-18**: Behavior is byte-for-byte identical to v1.1 — same gating outcomes and order, same exception classification and `MutationErrorTranslator` outputs, same audit rows (including rollback rows), same idempotency semantics, same `MutationGuard` SPI contract. The Phase 9/10/11 mutation test suites (`MutationToolInvariantsTest`, gating-order, audit-row, error-translator, host-guard-veto tests) pass unchanged, and the default-config zero-mutation-callback boot test still passes.

### AI-Runtime Performance Pass (targeted)

- [ ] **PERF-01**: Per-turn (single `RunContext`) memoization of `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions / `LlmExposurePolicy` resolution — computed once and shared across all tool calls in the turn; nothing user/role/exposure-sensitive is reused across turns or users.
- [ ] **PERF-02**: Longer-lived memoization of pure-metadata derivations (entity name → `MetaClass`) and the exposure denylist (`getDenylistedEntityNames()`), with the denylist and any other exposure-derived cache evicted on `LlmExposureChangedEvent`; `AccessManager` remains authoritative for actual data access.
- [ ] **PERF-03**: The RAG retrieval `Filter.Expression` (role/exposure scoping) is built once per retrieval rather than rebuilt repeatedly; the `(source_entity IS NULL) OR (NOT IN <denied>)` / role clauses are preserved verbatim (no "redundant clause" removal); the existing `RetrievalFilterBuilder` denylist test passes unchanged.
- [ ] **PERF-04**: Task-file `Media` is encoded/resolved once per `(conversationId, taskFileId)` per turn (cache evicted on attachment add/delete) rather than re-encoded per injection; prompt/context is not re-serialized within a turn; FK batch-loading (shared with MUT-16) is in effect.
- [ ] **PERF-05**: No benchmark harness and no admin-screen performance work are introduced; each optimization ships with a checkable proxy (SELECT-count assertion via the test-scoped `datasource-proxy`, "1 query not N" assertion, or a call-count assertion) and the existing security / exposure / audit / tool / RAG test suites pass unchanged.

### Testing & Safety (cross-cutting)

- [ ] **TEST-18**: STT coverage — the `STT_TRANSCRIPTION` audit row is asserted in both hash-default and `store-transcript=true` modes; a source-scan test asserts the `com.vn.agent.stt` package has zero reference to `ChatService`; a default-config boot test asserts no STT beans and no mic button.
- [ ] **TEST-19**: Chat-observability leak test — the streaming-status line and the per-turn tool-detail disclosure never emit internal `@Tool` method names or raw entity names (reuses the Phase 9 leak-guard pattern packs at the UI layer).
- [ ] **TEST-20**: Curated-model allowlist test — every model id in the curated dropdown catalog is on a self-hostable open-weights allowlist (comment references `project_self_hostable_models_only.md`).
- [ ] **SEC-08**: Config-knob secret denylist — a test asserts no `*.api-key` (or other secret) property is surfaced as an editable or displayed admin setting, and boot-time `@ConditionalOnProperty` toggles are not presented as runtime-editable.

## Future Requirements (deferred — not in the v1.2 roadmap)

### Voice / Transcription

- **SPI-11**: `TranscriptionPostProcessor` SPI — host bean rewrites transcripts (PII redaction / vocabulary normalization) before they reach `MessageInput`. (Trimmed from v1.2; revisit when a host asks.)
- **STT-FUT-01**: Custom STT-provider SPI beyond plain `ai-agent.stt.provider=<bean-name>` selection.
- **STT-FUT-02**: Voice output (TTS) for agent responses.

### UI / Operator

- **MODEL-FUT-01**: Per-conversation / per-user end-user model switching.
- **CFG-FUT-01**: Admin-screen performance work (the v1.2 perf pass is AI-runtime only).
- **OBS-FUT-01**: Chat-state side panel showing model, conversation, governance flags, attached-file count, attachment token-budget usage, and last-turn summary. Deferred from v1.2 per user decision on 2026-05-11; do not implement in Phase 15.

### Carried debt (later hardening pass)

- **DEBT-01**: Phase 10 re-verification — `/gsd-verify-work 10` to flip the stale `human_needed` status.
- **DEBT-02**: Nyquist `*-VALIDATION.md` backfill for phases 9 / 10 / 11 / 12 / 13 / 13.1.
- **DEBT-03**: Clean-consumer smoke (PKG-05 / TEST-07) — Postgres/pgvector Testcontainers smoke OR a starter stub `VectorStore` boot mode (v1.0.0 Plan 08-05 carryover).
- **EXP-FUT-01**: Attribute-path-level exposure rules (`attributePath` on `AiExposureRule`) — deferred per user decision 2026-04-27.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Auto-send the transcribed text without user review | #1 reported regression in voice-to-chat tools; hard architectural rule — transcript only fills `MessageInput` |
| Client-side audio transcoding (FFmpeg / JCodec / WASM) | Soniox and OpenAI auto-detect `webm/opus` and `mp4`; transcoding is needless complexity and bloat |
| Live / interim / partial transcript display | Soniox async is batch, not streaming; building a streaming path is out of scope for v1.2 |
| "Meeting mode" / transcribe whole conversations | Far beyond dictation-into-the-input-box; not in v1.2 |
| Routing Soniox/OpenAI transcription through OpenRouter | OpenRouter does not proxy `/audio/transcriptions`; the OpenAI fallback needs the OpenAI key + endpoint directly |
| A Soniox Java SDK dependency | None exists (Python/Node/Web/React only); use Spring `RestClient` |
| `spring-ai-starter-model-openai` added just for STT | Audio classes already ship in the declared `spring-ai-openai:1.1.4`; wire `OpenAiAudioApi` manually like the existing OpenRouter `ChatModel` |
| Caffeine / a benchmark harness (JMH / Gatling) | The perf hotspots are bounded and JVM-lifetime-stable; the existing `ConcurrentMapCacheManager` + test-scoped `datasource-proxy` SELECT-count assertions suffice |
| A new `AuditKind` for STT | `STT_TRANSCRIPTION` / `stt_transcription` is already a reserved `eventName` string (AUD-06); reuse `AuditWriter.writeToolCall` |
| A second `ChatClient` / second `ChatMemory` store | The STT path is disjoint; it never touches `ChatService` / `ChatClient` |
| Proprietary / hosted-only models in the curated dropdown | Violates the self-hostable-open-weights policy; proprietary models are reachable only via the custom-entry escape hatch |
| Secrets (API keys) in the admin config UI | Plaintext credentials in a DB table; secrets stay env / `application.properties`-backed (indicator-only in the UI) |
| Migrating `ai-agent.stt.*` knobs in the Phase 17 config-knob pass | STT lands later (Phase 20); its knobs are mostly Tier-2 boot toggles / Tier-3 secrets, and the STT phase owns adding its own `store-transcript` toggle (or leaving it a property per CFG-02) |
| Activation of dormant seeds SEED-001 / 002 / 003 / 004 / 006 / 008 | Their documented triggers are not met; do not activate in v1.2 |
| Phase 10 re-verification, Nyquist backfill, PKG-05 / TEST-07 clean-consumer smoke | Explicitly deferred to a later hardening pass per the 2026-05-11 decision; must not be folded into a v1.2 phase |
| `@Transactional` on the `MutationGateChain` itself | Only `MutationSaveExecutor.save` is transactional; the chain must throw before the save crosses the transaction boundary (fail-closed) |

## Traceability

Which phases cover which requirements.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SURF-11 | Phase 15 | Complete |
| OBS-01 | Phase 15 | Pending |
| OBS-02 | Phase 15 | Pending |
| OBS-04 | Phase 15 | Complete |
| TEST-19 | Phase 15 | Pending |
| MODEL-01 | Phase 16 | Pending |
| MODEL-02 | Phase 16 | Pending |
| MODEL-03 | Phase 16 | Pending |
| TEST-20 | Phase 16 | Pending |
| CFG-01 | Phase 17 | Pending |
| CFG-02 | Phase 17 | Pending |
| CFG-03 | Phase 17 | Pending |
| SEC-08 | Phase 17 | Pending |
| MUT-15 | Phase 18 | Pending |
| MUT-16 | Phase 18 | Pending |
| MUT-17 | Phase 18 | Pending |
| MUT-18 | Phase 18 | Pending |
| PERF-01 | Phase 19 | Pending |
| PERF-02 | Phase 19 | Pending |
| PERF-03 | Phase 19 | Pending |
| PERF-04 | Phase 19 | Pending |
| PERF-05 | Phase 19 | Pending |
| STT-01 | Phase 20 | Pending |
| STT-02 | Phase 20 | Pending |
| STT-03 | Phase 20 | Pending |
| STT-04 | Phase 20 | Pending |
| STT-05 | Phase 20 | Pending |
| STT-06 | Phase 20 | Pending |
| TEST-18 | Phase 20 | Pending |

**Coverage:**
- v1.2 requirements: 29 total
- Mapped to phases: 29 ✓ (Phase 15: 5 · Phase 16: 4 · Phase 17: 4 · Phase 18: 4 · Phase 19: 5 · Phase 20: 7)
- Unmapped: 0
- No orphans, no duplicates. (Future Requirements and Out of Scope sections are intentionally unmapped.)

---
*Requirements defined: 2026-05-11*
*Last updated: 2026-05-11 — v1.2 roadmap created (Phases 15–20 mapped); revised same day — Soniox STT moved to Phase 20 (last in milestone); phases 15–20 re-ordered/re-numbered.*
