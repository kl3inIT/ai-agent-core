# Pitfalls Research

**Domain:** Operator-experience / voice-input / runtime-performance pass (v1.2) on an existing, shipped Jmix 2.8 + Spring Boot 3 + Spring AI 1.1.x + Vaadin Flow + pgvector AI agent add-on
**Researched:** 2026-05-11
**Confidence:** HIGH for the integration-with-existing-contracts pitfalls (grounded in this repo's PROJECT.md / ROADMAP.md / STATE.md decisions and the project memory notes); MEDIUM for the Soniox/OpenAI STT-API specifics (verify `/v1/files` + `/v1/transcriptions` request/response shapes against current Soniox docs at Phase 15 plan time — not independently re-fetched here).

This file is scoped to **adding these specific v1.2 features to THIS system** — not generic STT/perf advice. Every pitfall references an existing v1.1 contract: the leak guards (`OutputScannerAdvisor` HOST_PREFIX_LEAK / TOOL_NAME_LEAK + PROMPT-03/04/06 vocabulary rules), the fail-closed mutation gating chain (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save), `LlmExposurePolicy` (`userVisible AND NOT excluded`), the strict `default-params.yaml` seed vs `module.properties` defaults split, "the LLM never receives `ViewNavigators`", audit-via-`AuditWriter.writeToolCall` (no new `AuditKind`; `STT_TRANSCRIPTION`/`stt_transcription` already reserved per AUD-06), `UnconstrainedDataManager` for system-internal writes only, and "self-hostable models only" (open-weights, Apache-2.0+).

## Phase naming used below

Phase numbering is monotonic from Phase 15 (v1.1 ended at Phase 14 + follow-up 13.1). The roadmapper assigns final numbers; these are placeholders the pitfalls map to:

- **Phase 15 — Chat Voice Input (Soniox STT)** — promoted from ROADMAP Backlog 999.2
- **Phase 16 — Chat UX & Observability** — right-sidebar chat-state panel, collapsible per-turn tool-detail panel, ephemeral streaming-status indicator
- **Phase 17 — Admin Model Management** — curated self-hostable dropdown + custom free-entry
- **Phase 18 — Admin Config-Knob Migration** — operator-relevant `module.properties` knobs → editable `AiParameters` / admin UI
- **Phase 19 — Phase 11 Mutation-Internals Hardening** — promoted from ROADMAP Backlog 999.1 (dedup gate sequencing, batch FK loads, cache related-write metadata)
- **Phase 20 — AI-Runtime Performance Pass** — targeted hotspots only, no benchmark harness, no admin-screen perf

## Critical Pitfalls

### Pitfall 1: Raw transcripts leak into audit rows / application logs by default

**What goes wrong:**
The STT path writes the user's spoken words verbatim into the `STT_TRANSCRIPTION` audit row's `argumentsJson` (or into `log.info`/`log.debug` while debugging the Soniox round-trip), so dictated PII lands in a queryable Jmix admin table and in log files even when `ai-agent.stt.audit.storeTranscript=false`. The hash-by-default privacy contract is defeated.

**Why it happens:**
`AuditWriter.writeToolCall` already exists and is trivial to call with the transcript string in the args map; the privacy switch is one more thing to remember and is easy to wire backwards (defaulting to "store raw" because that's what you logged during development). Logging the Soniox response body for debugging is the natural first move and the transcript is right there in it.

**How to avoid:**
- Default `ai-agent.stt.audit.storeTranscript=false`. The default audit row records duration, language hint(s), model, outcome, and `sha256(transcript bytes)` ONLY — never the text. Mirror the existing `AuditFieldHasher` convention from Plan 09-01 (SHA-256 over UTF-8 bytes, lowercase 64-char hex via `java.util.HexFormat`).
- Use `eventName=stt_transcription` (the string is already reserved per AUD-06) — do NOT add a new `AuditKind`.
- Audit the Soniox HTTP client at DEBUG only for status/headers/resource-ids — never the response body. A `TranscriptLeakScannerTest` (source-grep, mirroring the existing TEST-16 forbidden-token gate) should fail if any `.java` outside an allowlist references the transcript variable in a `log.` or audit-args context without the hash wrapper.
- TEST-17 must cover BOTH modes: hash-default row AND `storeTranscript=true` raw row.

**Warning signs:**
A grep for the transcript-holding variable hits a `log.` call; an `STT_TRANSCRIPTION` audit row in the demo app shows readable text under default config; the privacy property has no test asserting the negative case.

**Phase to address:** Phase 15

---

### Pitfall 2: The transcription path calls `ChatService.ask` instead of just filling `MessageInput`

**What goes wrong:**
After transcription returns, the code "helpfully" submits the turn — the user never reviews/edits the text, a wrong transcription becomes a wrong chat turn (and a wrong tool call / mutation), and the STT pathway is no longer "disjoint from the chat client" as the contract requires. It also bypasses the review a user would have done on a `prepare_form_draft` confirmation.

**Why it happens:**
"Voice → answer" feels like the obvious UX; the `ChatService` / `ChatPanelFragment` submit path is right there at the `messageInputSlot` integration point, so it's one line away.

**How to avoid:**
- `TranscriptionService` (and the mic component that calls it) has NO reference to `ChatService` / `DefaultChatServiceImpl` / the submit handler. It only sets the value on the `MessageInput` (`messageInput.setValue(transcript)`) and returns focus. A structural source-scan test (like the Phase 14 navigation-free `StreamEventRenderer` tests) asserts the STT package does not import the chat-service type.
- The mic button lives in `ChatPanelFragment.messageInputSlot` (the stable Phase 12 extension point) — it adds a sibling control; it does not wrap or replace the input's submit listener.
- JavaDoc the invariant verbatim ("transcript lands in `MessageInput` for review before send; `TranscriptionService` does NOT call `ChatService.ask`"), mirroring the Phase 11/14 invariant-comment pattern.

**Warning signs:**
The STT class imports `ChatService`/`DefaultChatServiceImpl`; clicking mic + speaking produces an assistant reply with no user click; the UAT script has no "edit the transcript before sending" step.

**Phase to address:** Phase 15

---

### Pitfall 3: Reusing the OpenRouter base-url (or the chat API key) for `/audio/transcriptions`

**What goes wrong:**
The OpenAI-direct fallback is wired to the same `base-url` (`https://openrouter.ai/api/v1`) and the same key the chat client uses. OpenRouter does NOT proxy `/audio/transcriptions`, so the call 404s/415s — and only at runtime, since nothing at boot validates it. Symmetrically: the Soniox path is handed the chat key instead of `ai-agent.stt.soniox.api-key` and every transcription returns 401.

**Why it happens:**
The whole chat stack is "OpenAI-compatible via OpenRouter, one starter, per-request model switch" (Key Decision, mirrors `traffic-law-chatbot`). It's natural to assume the audio endpoint rides the same client. And "API key" feels singular when there are actually three independent keys (chat/OpenRouter, OpenAI-direct, Soniox).

**How to avoid:**
- Three distinct properties, documented as independent: `ai-agent.openrouter...` (chat), `ai-agent.stt.openai.api-key` (OpenAI-direct transcription — must point at `https://api.openai.com/v1`, NOT OpenRouter), `ai-agent.stt.soniox.api-key` (independent of both).
- The Soniox client is a hand-rolled `RestClient` (Soniox has no Java SDK): `POST /v1/files` → `POST /v1/transcriptions` (`model=stt-async-v4`, `language_hints: ["vi","en"]`) → poll → fetch transcript → `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}`.
- The OpenAI-direct fallback uses Spring AI's transcription model bean configured with `base-url=https://api.openai.com/v1` explicitly — never inherit the chat `base-url`.
- README + the operator config doc: a table showing "this key → this provider → this endpoint" so an operator can't cross-wire them. A startup INFO line states which STT provider is active and which endpoint it will hit (no secrets).

**Warning signs:**
Only one API-key property for both chat and STT; the OpenAI fallback bean has no explicit `base-url`; the first transcription attempt 404s in the demo app.

**Phase to address:** Phase 15

---

### Pitfall 4: Soniox files/transcriptions are not deleted after retrieval

**What goes wrong:**
Each dictation uploads a file and creates a transcription on Soniox's side; if `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` are skipped (or skipped on the error path), audio and transcripts accumulate on a third-party server indefinitely — a privacy and cost liability the host operator never agreed to.

**Why it happens:**
The happy path returns the transcript and the dev moves on; cleanup is "later"; when transcription fails partway, the early `return`/`throw` skips cleanup that was tacked on at the end of the method.

**How to avoid:**
- Wrap upload→transcribe→fetch so cleanup runs in a `finally` (or try-with-resources style), covering BOTH success and every failure branch — including "recording too long → 4xx" and network timeouts. Best-effort delete: log-and-continue on delete failure (mirrors the existing `AiTaskFileRepository.deleteRow` blob-first / log-on-failure pattern from Phase 13).
- Never block the user-facing flow on the deletes — fire them after the transcript is returned, but DO fire them.
- TEST-17 (or a sibling) asserts the Soniox client issues the two `DELETE`s on the success path; a separate case asserts a 4xx during transcription still triggers the file `DELETE`.

**Warning signs:**
The Soniox client has `POST`s but no `DELETE`s; cleanup is the last statement before `return` rather than in `finally`; no test for the delete calls.

**Phase to address:** Phase 15

---

### Pitfall 5: Blocking the Vaadin UI thread on a slow transcription / wrong `UI.access` discipline pushing the transcript back

**What goes wrong:**
The mic-button click handler synchronously calls `TranscriptionService.transcribe(...)` (multiple HTTP round-trips to Soniox plus polling) on the Vaadin UI thread → the whole session freezes for seconds. Or: transcription is moved to a background thread but the result is pushed onto `MessageInput` without `ui.access(...)` (or `@Push` not enabled, or `ui.getUI()` already detached because the user navigated away or the `HEADER_BUTTON` dialog closed) → silent no-op, `IllegalStateException`, or cross-thread Vaadin state corruption.

**Why it happens:**
Synchronous-in-the-listener is the default Vaadin shape; async-with-`UI.access` is fiddly; the existing chat streaming already solved this once (`@Push`, `CancellationRegistry`), so it's tempting to assume STT "just works" the same way without re-checking detach handling. The fragment can be mounted inside the `HEADER_BUTTON` `DialogWindow` surface, whose UI can close mid-transcription.

**How to avoid:**
- Run the Soniox round-trip on a bounded executor (mirror the `aiAgentIngestExecutor` precedent — a dedicated `aiAgentSttExecutor` bean). The UI-thread handler only kicks off the task and shows the ephemeral "transcribing…" indicator.
- Push the result with `ui.access(...)`; guard for `ui.isClosing()` / detached UI / a stale conversation id (the dialog surface may have closed). On detach, drop the result silently (and still run the Soniox `DELETE`s).
- Confirm `@Push` is enabled for the chat surfaces (it already is for streaming) and the STT result path uses the same push transport.
- Hard client-side cap on recording length (≤60 s per the success criterion) so the upload is bounded; surface "recording too long" as a non-blocking message + retry, never a thrown exception that kills the panel.

**Warning signs:**
The mic listener calls the transcription service directly with no executor; the result is set on `MessageInput` outside `ui.access`; the panel throws when you navigate away mid-transcription; no recording-length cap.

**Phase to address:** Phase 15

---

### Pitfall 6: `@ConditionalOnProperty` mis-wiring — the mic button shows when STT is disabled (or hides when enabled)

**What goes wrong:**
The mic button is rendered unconditionally in `ChatPanelFragment` while only the `TranscriptionService` bean is `@ConditionalOnProperty("ai-agent.stt.enabled")`. With STT off, the user sees a mic button that throws "no such bean" on click (or NPEs). Or the property name in the `@ConditionalOnProperty` annotation doesn't match the one in `module.properties` (typo / `stt` vs `speech`), so the feature looks dead even when "enabled".

**Why it happens:**
The Phase 11 precedent (`BuiltInMutationTools` default-OFF behind `@ConditionalOnProperty` + a boot test asserting zero mutation callbacks under default config) covers the *bean*, but a Vaadin UI control is built imperatively in fragment code, not as a Spring bean — so the conditional gating must be re-implemented at the UI layer, and that's easy to forget.

**How to avoid:**
- Gate the mic-button creation in `ChatPanelFragment` on the same resolved property the bean uses — inject `ObjectProvider<TranscriptionService>` and only add the button when `getIfAvailable() != null` (mirrors Plan 11-09's `ObjectProvider.getIfAvailable for BuiltInMutationTools` decision). One source of truth.
- A boot/integration test: with default config (`ai-agent.stt.enabled` unset), the chat fragment renders NO mic control AND no `TranscriptionService` bean exists — exactly the shape of the Phase 11 "zero mutation callbacks under default config" test.
- Centralize the property name as a constant referenced by both the `@ConditionalOnProperty` and the fragment check (no string duplication).

**Warning signs:**
The mic button appears in the demo app without setting any STT property; `@ConditionalOnProperty` value is a string literal duplicated elsewhere; no "default config = no mic" test.

**Phase to address:** Phase 15

---

### Pitfall 7: Browser codec / mic-permission / recording-length edge cases handled as exceptions instead of friendly retry UI

**What goes wrong:**
`MediaRecorder` produces `audio/webm;codecs=opus` in Chrome/Firefox but `audio/mp4` in Safari; the server-side Soniox client assumes one container/MIME and rejects the other → cryptic 415. Or the user denies mic permission and the JS throws → a blank panel or an ugly stack trace bubble. Or the user records for 5 minutes → a huge upload that times out. None of these are "the chat is broken", but uncaught they look like it.

**Why it happens:**
Browser audio capture is genuinely fragmented; the "happy path in my browser" works, so the others surface in production. The success criterion explicitly says "no transcoding — webm/opus or mp4 directly", which means the *server* must accept whatever the browser hands it.

**How to avoid:**
- The client-side capture component sends the actual `MediaRecorder` MIME type alongside the bytes; the Soniox `POST /v1/files` call forwards it (Soniox accepts both). Do not hardcode a single content type server-side.
- Mic-permission denial: catch it client-side, surface "microphone access is required for voice input" as a non-blocking message + retry button — never a Vaadin/JS error.
- Hard cap recording duration client-side (≤60 s) with a visible countdown; auto-stop at the cap; if exceeded, the server returns "recording too long" → friendly message + retry (success criterion 4 explicitly lists "recording too long" as a non-blocking error case).
- TEST-17 / sibling: cases for "provider 4xx", "recording too long", "network failure" — all assert the chat flow stays usable and a retry affordance appears.

**Warning signs:**
The server-side client sets a fixed `Content-Type`; Safari users report "voice doesn't work"; denying mic permission shows a stack trace; no countdown / auto-stop on the recorder.

**Phase to address:** Phase 15

---

### Pitfall 8: The observability panel rebuilds a parallel turn model when the audit tree already has it — or leaks internal tool/entity names into a user-facing panel (v1.1 leak-guard violation)

**What goes wrong:**
Two failure modes, opposite directions:
1. **Duplication:** the per-turn tool-detail panel and the right-sidebar chat-state panel each maintain their own ad-hoc "what happened this turn" structure, drifting from the durable audit tree (`AiAuditEvent` PARENT_ID tree-lite + the audit-listener SPI) that already records every tool call, outcome, and rollback. Two sources of truth that disagree.
2. **Leak:** the panel (or the ephemeral streaming-status indicator) surfaces internal tool names (`describe_entity`, `bulk_save_records`, `prepare_form_draft`) and host-prefixed entity names directly to end users — exactly what Phase 9's `OutputScannerAdvisor` HOST_PREFIX_LEAK / TOOL_NAME_LEAK guards and the PROMPT-03/04/06 vocabulary rules exist to prevent. The leak guard scans the *LLM output*; a UI panel rendering raw internal names bypasses that contract entirely.

**Why it happens:**
The audit tree is "backend bookkeeping" in the dev's mind, not "the data model for the observability UI", so a fresh model feels cleaner. And the tool names are right there in the streaming events / tool callbacks, so echoing them into a status line is the path of least resistance — the dev forgets the v1.1 leak-guard contract because the guard lives in the advisor layer, not the UI layer.

**How to avoid:**
- The collapsible per-turn tool-detail panel reads from the existing audit tree (`AiAuditEvent` rows for that conversation/turn). The right-sidebar chat-state panel reads `AiChatSessionState` + the current conversation projection. NO new "turn model" entity, NO parallel in-memory turn structure. If the audit tree lacks a field the panel needs, add it to the audit row (one source of truth), don't fork.
- **Audience gating + name sanitization:** end-user-visible observability surfaces show *humanized* descriptions ("looked up customer records", "checked your permissions"), never the raw `@Tool` method name or host-prefixed entity name. Reuse the same humanization the chat already does (`MessageTools.getEntityCaption`, the PROMPT-04 `<data entity><label>` label-first convention). The full raw tool args/outcome stay in the admin-only `AiAuditEventListView` / detail dialog — that's where internal names are allowed.
- Extend the existing leak-guard test family: a `ChatObservabilityLeakTest` asserting the user-facing panel renderer never emits a string matching the HOST_PREFIX_LEAK / TOOL_NAME_LEAK patterns. Reuse the Phase 9 pattern packs.
- The ephemeral streaming-status indicator shows generic phases ("thinking…", "looking things up…", "writing the answer…"), not "calling `query_records`".

**Warning signs:**
A new `@JmixEntity` named like `AiTurnDetail`; the panel renderer concatenates a `ToolDefinition.name()`; the streaming status text contains an underscore_case token; the leak-guard test suite has no UI-panel case.

**Phase to address:** Phase 16

---

### Pitfall 9: Per-turn detail held in `AiChatSessionState` grows unbounded → memory leak

**What goes wrong:**
To power the collapsible tool-detail panel, the dev stashes the full tool-call list (args, results, payloads, media references) for every turn into `AiChatSessionState` — a session-scoped object. A long conversation, or a user who keeps the tab open all day, accumulates megabytes of turn detail per session × N sessions → heap pressure, eventual OOM on a busy host.

**Why it happens:**
`AiChatSessionState` already exists as the cross-surface continuity holder (Phase 12: it stores only `currentConversationId` + listeners — deliberately thin per the D-10 decision); it's the obvious place to hang "current turn UI state". The Phase 12 thin-state decision gets forgotten.

**How to avoid:**
- Keep `AiChatSessionState` thin (Phase 12 invariant: `currentConversationId` + listeners, not a cancellation authority, not a turn cache). The tool-detail panel queries the durable audit tree on demand (lazy-load on expand) rather than holding it in session memory.
- If a small per-turn cache is genuinely needed for the *current* turn's streaming status, bound it to the current turn only and clear it when the next turn starts (mirror the `CancellationRegistry` lifecycle).
- A code-review gate: `AiChatSessionState`'s field count and types reviewed against the Phase 12 contract; ideally a heap/soak check.

**Warning signs:**
`AiChatSessionState` gains a `List<TurnDetail>` / `Map<turnId, ...>` field; heap dumps in a soak test show `AiChatSessionState` retaining tool payloads; the Phase 12 "AiChatSessionState stores only currentConversationId and listeners" decision is contradicted by the diff.

**Phase to address:** Phase 16

---

### Pitfall 10: Non-self-hostable models in the curated default dropdown (violates project policy + memory note)

**What goes wrong:**
The curated common-model dropdown ships with `gpt-4o`, `claude-3.5-sonnet`, `gemini-1.5-pro`, `qwen3.6-plus`, etc. — exactly the proprietary/hosted-only models the project's "self-hostable models only" policy (open-weights, Apache-2.0+) excludes from defaults. An operator picks one in good faith and the add-on now ships a default that violates the product's stated constraint.

**Why it happens:**
"Curated common models" reads as "the models everyone uses" — and the most-used models are the proprietary ones. The self-hostable constraint is a project-memory note (`project_self_hostable_models_only.md`), not something visible at the dropdown-design moment.

**How to avoid:**
- The curated default list contains ONLY open-weights / self-hostable models (e.g. the current default `qwen/qwen3.6-35b-a3b` (Apache-2.0) and siblings; whatever the current self-hostable SOTA is — verify at plan time, the list will be stale by ship). The custom free-entry field is the escape hatch for anything the host routes to (OpenRouter, a hosted endpoint) — that's where proprietary models belong, entered explicitly by the admin who owns that decision.
- A test asserting every entry in the curated list is on an allowlist of known open-weights model ids (with a comment pointing at `project_self_hostable_models_only.md`).
- The dropdown UI labels the curated section "Self-hostable (recommended)" and the free-entry "Custom model name" so the distinction is visible to the admin. The operator README notes custom-entry models are the operator's responsibility re: licensing/hosting.

**Warning signs:**
The curated list contains a model whose weights aren't published; no allowlist test; the dropdown doesn't distinguish curated vs custom; reviewers didn't cross-check the self-hostable memory note.

**Phase to address:** Phase 17

---

### Pitfall 11: The custom model-name free-entry silently accepts an invalid model that only fails at the first chat turn

**What goes wrong:**
An admin typos the custom model name (`qwen/qwen3.6-35b-a3` instead of `...a3b`), the Parameters view saves it happily, and the first end-user chat turn afterward blows up with a provider 404 — far from the admin who made the change, presented to the end user as "the AI is broken".

**Why it happens:**
There's no cheap way to validate an arbitrary routed model name at save time without making a call; "save whatever they type" is the easy implementation; and the failure surfaces in a totally different place (end-user chat) from where it was caused (admin settings).

**How to avoid:**
- On save of a custom model name, do a lightweight validation ping (a tiny `chat` call with `max_tokens=1`, or the provider's models-list endpoint if available) and show the admin a clear "couldn't reach model `X` — saved anyway / fix it" outcome. Don't *block* the save (the host may be pre-configuring for a model not yet provisioned), but make the failure loud at the point of change.
- The chat turn's model-resolution path must fail gracefully if the configured model 404s: a clear admin-facing error in the chat ("the configured model is unavailable — check AI settings"), logged, audited — not a raw stack trace to the end user.
- Persist the model choice in `AiParameters` (the existing admin-edited settings entity), NOT a side store — see Pitfall 12.

**Warning signs:**
Saving a garbage model name in the demo app succeeds with no feedback; the first chat turn afterward shows a 404 to the user; the chat model-resolution path has no graceful-fallback branch.

**Phase to address:** Phase 17

---

### Pitfall 12: Model choice / migrated knobs persisted somewhere that overrides the strict seed YAML incorrectly (precedence bug)

**What goes wrong:**
Two related precedence bugs:
1. **Seed YAML clobber:** the new model-picker (or a migrated knob) writes into the strict `default-params.yaml` seed mechanism, or into `AiParameters` in a way the strict seed re-applies on next boot and overwrites the admin's choice — admin sets model `X`, app restarts, model snaps back to the seed default.
2. **Wrong layering:** an operator-relevant knob is migrated to `AiParameters` but the `module.properties` Spring default still wins (or vice versa), so changing the admin UI value has no effect — or changing `module.properties` no longer has an effect when an operator expected it to.

**Why it happens:**
There are three config layers — Spring/library defaults in `module.properties`, the strict `AiParameters` seed in `default-params.yaml` (Plan 09-01 decision: Spring config defaults live in `module.properties`, NOT `default-params.yaml`), and admin-edited `AiParameters` rows. The precedence among them is a convention, not enforced, and the seed YAML is strict (re-applies). Easy to put a new knob in the wrong layer.

**How to avoid:**
- Honor the existing layering convention: Spring/library defaults → `module.properties` (Plan 09-01 carve-out). Operator-tunable runtime values → `AiParameters` rows, editable in the admin UI, with the *fallback* being the `module.properties` value when no `AiParameters` row exists. The strict `default-params.yaml` seed is for genuinely-seed data, not for things the admin changes at runtime — don't put the model picker or migrated runtime knobs there, or document precisely how "admin override survives reseed".
- A precedence test: set a `module.properties` default, set a different `AiParameters` value, assert the `AiParameters` value wins at runtime; assert that with no `AiParameters` row the `module.properties` default applies; restart-simulate and assert the admin's `AiParameters` value is NOT clobbered by the seed.
- Document the layering in the operator README and in JavaDoc on the settings-resolution component (one place that resolves "effective value of knob X").

**Warning signs:**
Admin changes a setting, restarts, it reverts; changing an admin-UI setting has no runtime effect; the model picker writes to `default-params.yaml`; no precedence test.

**Phase to address:** Phase 17 (model choice) + Phase 18 (migrated knobs)

---

### Pitfall 13: Secrets / API keys exposed in the admin UI during the config-knob migration

**What goes wrong:**
The "migrate operator-relevant `module.properties` knobs into editable `AiParameters`/admin UI" sweep is too aggressive and drags `ai-agent.openrouter.api-key` / `ai-agent.stt.soniox.api-key` / `ai-agent.stt.openai.api-key` into the admin Parameters view — now API keys are stored in a DB table (`AiParameters`), visible in plaintext to anyone with the admin role, possibly shown in `AiAuditEvent` change history, and no longer managed as deployment secrets.

**Why it happens:**
"Operator-relevant knob" is a fuzzy boundary and API keys *are* operator-relevant in a loose sense; the migration is mechanical ("move all the `ai-agent.*` properties"); the existing Plan 14-09 decision already established "only the OpenRouter API key remains env-backed" — that line must be held, not eroded.

**How to avoid:**
- Explicit denylist for the migration: secrets/API keys (`*.api-key`, any credential) NEVER become `AiParameters` / admin-UI settings — they stay env/`application.properties`-backed deployment secrets (consistent with Plan 14-09's BL-01 narrowing: "only OpenRouter API key remains env-backed"; the STT keys join that list).
- Also denylist boot-time-only toggles (`@ConditionalOnProperty` switches: `ai-agent.tools.mutation.enabled`, `ai-agent.stt.enabled`, `ai-agent.stt.provider` if it selects a bean at boot, chat-surface enablement wired at startup) — making them *look* runtime-editable in the admin UI when changing them does nothing until restart is worse UX than leaving them in properties. Either leave them in properties OR make the UI clearly state "requires restart" — don't pretend.
- The migration produces an explicit, reviewed list: "knob → stays in properties (reason: secret / boot-time)" vs "knob → migrated to `AiParameters` (reason: runtime-tunable)". RAG top-k, similarity threshold, task-file token budget — runtime-tunable, migrate. Mutation enabled, STT enabled, provider selection — boot-time, don't migrate (or restart-flag).
- A test asserting no `*.api-key` / credential property has a corresponding `AiParameters` key.

**Warning signs:**
An API key appears in the Parameters view or in an `AiParameters` row; a `@ConditionalOnProperty` toggle is editable in the admin UI with no "requires restart" note; the migration list isn't reviewed against a secret/boot-time denylist.

**Phase to address:** Phase 18

---

### Pitfall 14: Cache staleness when an admin edits a knob mid-session (and the perf pass makes this worse)

**What goes wrong:**
An admin changes RAG top-k or the model name in the Parameters view; existing chat sessions keep using the old value because the setting was read once at session/bean init and cached, or because the Phase 20 perf pass added a static/longer-lived cache around settings resolution. The change "doesn't take effect" until restart — the opposite of what migrating it to an admin UI promised.

**Why it happens:**
Reading `AiParameters` on every chat turn feels wasteful (a DB hit), so the natural perf instinct is to cache it; but invalidation on admin edit is the part that gets skipped. The Phase 20 perf pass actively *encourages* caching here, raising the risk.

**How to avoid:**
- Settings resolution caches with an explicit invalidation hook on `AiParameters` save — reuse the existing event pattern (`AiExposureRuleEntityListener` publishes `LlmExposureChangedEvent` on rule change → consumers refresh; do the same for `AiParameters`: an entity-listener publishes a change event, the settings cache evicts). One publish site (the entity listener), like the Phase 10 R2 invariant ("the view does NOT inject `ApplicationEventPublisher`; the entity listener is the single publish site").
- If a cache is added in Phase 20, it MUST wire to that invalidation event — a perf optimization without invalidation is a correctness regression. Phase 20's success criterion for any settings cache: "admin edit visible to in-flight sessions within one turn".
- Test: edit an `AiParameters` value, assert the next chat turn uses the new value without a restart.

**Warning signs:**
An admin edit requires a restart to take effect; a `@Cacheable`/static map around settings with no `@CacheEvict`/listener; the perf-pass diff adds a cache near settings resolution with no invalidation.

**Phase to address:** Phase 18 (invalidation hook) — and a hard constraint on Phase 20

---

### Pitfall 15: The mutation-internals "hardening" refactor is actually a behavior change (gating order / audit rows / error outputs / SPI contract)

**What goes wrong:**
Phase 19 is supposed to be byte-for-byte behavior-identical: dedup the gate sequencing into a shared component, batch-load to-one FK refs during binding, cache related-write metadata. But the refactor:
- **reorders the gating chain** — the fail-closed order is `AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save. If "dedup" moves the `MutationGuard` SPI call before the `AccessManager` check, a host guard now sees mutations the user isn't even allowed to attempt → security regression.
- **changes audit rows** — different `argumentsJson` shape, different outcome enum value, a missing rollback row → breaks `AiAuditEventListView` consumers and the audit-listener SPI contract.
- **changes `MutationErrorTranslator` outputs** — the 6-code D-04 taxonomy is closed (`concurrent_modification` etc.); a "cleaner" error path emits a 7th code or leaks raw exception text / LLM-supplied attribute names → P-22 regression.
- **breaks the `MutationGuard` SPI contract** — different `ToolVetoedException` reuse, different timing of the guard call, a changed method signature → host guards stop working.

**Why it happens:**
"Refactor" gives false confidence; the gate chain's order is load-bearing for fail-closed semantics but reads like an arbitrary sequence; the audit-row shape and error-code taxonomy are conventions enforced only by tests, not types; and "batch-load FK refs" / "cache metadata" feel orthogonal to gating but touch the same binding code path.

**How to avoid:**
- Phase 19 success criterion is explicitly "behavior byte-for-byte identical" — codify it: the existing Phase 11 test suite (TEST-10..13, `MutationToolInvariantsTest`, the gating-chain order test, the audit-row assertions, the `MutationErrorTranslator` canned-template tests, the `MutationGuard` veto test) must pass UNCHANGED. If a test needs editing, that's a red flag — stop and reconsider.
- Lock the gating ORDER in the new shared component with a source-level invariant test (mirror Plan 11-07C's `MutationToolInvariantsTest` JavaDoc + source-enforcement pattern) asserting the call sequence: role → exposure → AccessManager → idempotency → guard → save. The fail-closed property: every gate that throws must throw BEFORE the `@Transactional` save.
- The 6-code D-04 taxonomy stays closed — a test asserts `MutationErrorTranslator` only ever emits one of the 6 codes, never raw exception text, never an LLM-supplied attribute name.
- `MutationGuard` SPI: no signature change, `ToolVetoedException` reused verbatim, guard call stays at its current position; the existing host-guard integration test passes unchanged.
- Audit: same `eventName`s, same outcome enum values, same rollback-row behavior; the `AiAuditEvent` assertions from Phase 11 pass unchanged. No new `AuditKind`.

**Warning signs:**
A Phase 11 test had to be modified to make Phase 19 pass; the gating-chain order test is gone or weakened; `MutationErrorTranslator` gained a new code; the `MutationGuard` interface changed; an `AiAuditEvent` row shape differs in a diff.

**Phase to address:** Phase 19

---

### Pitfall 16: Batch FK loading during mutation binding bypasses `AccessManager` row-level checks

**What goes wrong:**
Phase 19 "batch-load to-one FK references during mutation binding" replaces N per-reference `DataManager.load(...)` calls (each going through Jmix security — row-level constraints, attribute policies) with one bulk fetch — and the bulk fetch uses `UnconstrainedDataManager`, or a raw JPQL `IN (...)`, or a fetch that skips the row-level constraint the per-reference loads honored. Now the LLM can bind a mutation to a parent/related record the user can't actually see — a row-level-security bypass through the back door.

**Why it happens:**
"Batch the loads" is a classic N+1 fix and the obvious way to batch is `IN (...)` — but the per-reference loads went through the *constrained* `DataManager` for a reason (the row-level policy applies per row). `UnconstrainedDataManager` is right there (used for audit/idempotency/cleanup system writes) and "it's just loading FKs" feels like system plumbing. The "AI is just another Jmix client" principle gets quietly violated.

**How to avoid:**
- The batch FK load goes through the **constrained `DataManager`** (the same one the per-reference loads used) with an `IN (...)` query — Jmix applies row-level constraints to `IN` queries too. If a referenced id isn't returned by the constrained query, it's not visible to the user → the mutation binding for that reference fails closed exactly as the per-reference path would have. Never `UnconstrainedDataManager` for FK refs the user is binding to via the LLM.
- `UnconstrainedDataManager` stays scoped to its existing system-internal uses (audit, idempotency reservation, cleanup, exposure-rule reads, task-file resolution) — per the memory note `feedback_jmix_unconstrained_for_system_writes`. FK-ref loading on a user-attributable mutation is NOT system-internal.
- Test: a user without row-level access to a parent record cannot `add_related_record` to it — the existing Phase 11 row-level-security test passes unchanged after the batch-load refactor.

**Warning signs:**
The new batch FK loader injects `UnconstrainedDataManager`; the batch query is raw JPQL outside `DataManager`; the Phase 11 row-level mutation-security test needed changes.

**Phase to address:** Phase 19

---

### Pitfall 17: Caching/memoizing across users, across requests, or across `LlmExposureChangedEvent` → a user sees another user's schema or stale exposure rules

**What goes wrong:**
The perf pass memoizes "repeated metadata / security / exposure-policy resolution" — and the cache key is too coarse: keyed by entity name only (not by user), or static (not per-request), or never invalidated on `LlmExposureChangedEvent`. Result: user A's readable-schema list (which depends on A's Jmix roles AND the exposure denylist) gets served to user B; or an admin adds an `EXCLUDE` rule and in-flight users keep seeing the now-excluded entity; or a host metamodel change leaves a stale `MetaClass` cache.

**Why it happens:**
`BaselineContextProvider` computes `agent.entities` + `agent.permissions` *per turn* from `CurrentUserSchemaAccess` + `AccessManager` + `LlmExposurePolicy` (Plan 09-03/10-04) — a lot of work per turn that screams "cache it". But the result is a function of (current user's roles, exposure rules, locale, host metamodel) — all four are cache-key dimensions, and exposure rules + metamodel change at runtime. The Plan 10-04 note ("ToolQueryCountBaselineTest recalibrated for D-14 no-cache: ceiling raised from 0 to 5 SELECTs to absorb the per-call agentstore policy lookup") is a direct signal the original design *chose* not to cache the policy lookup.

**How to avoid:**
- Any cache around schema/permission/exposure resolution is keyed by **(user principal/roles, locale, exposure-rules-version)** at minimum — never just entity name, never static. Prefer request-scoped (compute once per turn, reuse within the turn) over session/application-scoped.
- Wire eviction to `LlmExposureChangedEvent` (the existing event published by `AiExposureRuleEntityListener` — single publish site, Plan 10-06) AND to host metamodel changes if any cache holds `MetaClass`-derived data. A cache without these eviction hooks is a correctness regression masquerading as perf.
- The exposure policy stays `userVisible AND NOT excluded` and `AccessManager` stays authoritative — a cache must not become a *second* source of truth for access decisions. If in doubt, cache the expensive *inputs* (metamodel descriptions that don't depend on the user) and recompute the *user-specific composition* per turn.
- Test: with a cache in place, two users with different roles get different `agent.entities`; adding an `EXCLUDE` rule mid-session changes the next turn's schema for an active user; the Phase 10 four-path uniform-opacity test (TEST-09) passes unchanged.

**Warning signs:**
A `@Cacheable` / static map keyed by entity name around schema/permission resolution; no `LlmExposureChangedEvent` listener evicting it; the Plan 10-04 "no-cache" baseline test is gone; two users see the same schema regardless of roles in a test.

**Phase to address:** Phase 20

---

### Pitfall 18: Memoizing something per-locale (or NOT per-locale when it should be)

**What goes wrong:**
The perf pass caches `agent.entities` (which carries locale-resolved labels — the parenthesized caption suffix, per Plan 09-03) without locale in the key → a `vi` user sees `en` entity captions. Or, symmetrically, it caches `agent.permissions` *with* a locale key even though that block is locale-invariant by construction (TreeMap keys, fixed `r,u,c,d,modifiable` order, TreeSet attributes — Plan 09-03 P-8) → wasted cache entries and a false impression that the block is locale-sensitive.

**Why it happens:**
The two baseline blocks (`agent.permissions` locale-invariant; `agent.entities` locale-resolved) come from the *same* sorted/capped entity list but have different locale sensitivity — Plan 09-03 went out of its way to make `agent.permissions` locale-invariant precisely so it *could* be cached safely. A blanket "cache the baseline" misses the distinction.

**How to avoid:**
- Honor the Plan 09-03 split: `agent.permissions` is locale-invariant → cacheable without locale (key on user/roles/exposure-version only). `agent.entities` is locale-resolved → if cached, locale MUST be in the key. Don't cache them as one blob.
- More generally: any cache of user-facing text (entity captions, `MessageTools.getEntityCaption` results) is locale-keyed. Any cache of structural data (metamodel shape, permission booleans) is not.
- Test: a `vi` user and an `en` user get correctly-localized `agent.entities` with caching on; `agent.permissions` bytes are identical across locales for the same user (the existing cross-locale prompt-contract test, TEST-08, passes unchanged).

**Warning signs:**
`agent.entities` cache key has no locale; `agent.permissions` cache key has a locale; the TEST-08 cross-locale assertion needed changes; a `vi` user sees `en` captions in chat.

**Phase to address:** Phase 20

---

### Pitfall 19: "Optimizing" RAG filter building in a way that drops role/exposure scoping

**What goes wrong:**
The perf pass touches `RetrievalFilterBuilder` (and `AsyncIngestionWorker.enrich`) — the bit that, per Plan 10-05, applies a defensive `(source_entity IS NULL) OR (NOT IN <denied>)` filter for the exposure denylist AND the per-role retrieval scoping. A "cleaner"/faster filter construction accidentally drops the denylist clause, or the role-scope clause, or both → the LLM retrieves RAG chunks for entities it's supposed to be blind to, or chunks scoped to other roles. The Phase 10 cross-cut (uniform exposure enforcement across schema/tools/baseline/RAG) is broken on the RAG leg.

**Why it happens:**
Filter-expression construction is verbose and looks like a refactor target; the denylist clause is "defensive" (it overlaps with what the role scope already does in most cases) so it looks redundant and removable; the perf-pass dev may not know the Plan 10-05 / EXP-05 history.

**How to avoid:**
- The RAG filter MUST keep both the role-scope clause and the exposure denylist clause (`(source_entity IS NULL) OR (NOT IN <denied>)` for a non-empty denylist — Plan 10-05 Fix R6). Treat the denylist clause as load-bearing, not redundant — it's there for the case where a chunk's `source_entity` was set by ingestion and the entity later got excluded.
- The Phase 10 TEST-09 four-path uniform-opacity gate (which includes the RAG leg — `RetrievalFilterBuilderDenylistTest` + `LlmExposurePolicyIntegrationTest`) passes UNCHANGED after the perf pass. If it needs editing, stop.
- If you must refactor the filter builder, refactor the *construction mechanics*, not the *clauses*.

**Warning signs:**
The RAG filter no longer mentions `source_entity` or `NOT IN`; the role-scope predicate disappeared; `RetrievalFilterBuilderDenylistTest` needed changes; an excluded entity's chunks show up in retrieval in a test.

**Phase to address:** Phase 20

---

### Pitfall 20: Data races introduced into Vaadin's single-threaded-per-session model by the perf pass

**What goes wrong:**
The perf pass "parallelizes" something in the chat turn (e.g. firing tool calls concurrently, prefetching media on a background thread) and mutates Vaadin component state (the message list, the status indicator, `AiChatSessionState` listeners) from those threads without `UI.access(...)` — Vaadin's per-session single-threaded contract is violated → intermittent UI corruption, lost updates, `IllegalStateException` under load. Or shared mutable caches added for perf are accessed concurrently without synchronization.

**Why it happens:**
"Make it faster" → "do it in parallel" is reflexive; Vaadin's threading model is easy to forget when focused on the AI-runtime layer; the existing streaming code already does `UI.access` correctly so it's assumed the rest does too.

**How to avoid:**
- Any background work in the chat turn pushes UI updates exclusively through `ui.access(...)` (mirror the existing streaming + the STT pattern from Pitfall 5). The AI-runtime layer can be concurrent; the *UI-touching* code path stays single-threaded-per-session.
- Caches added for perf use thread-safe structures (`ConcurrentHashMap`, etc.) and are reviewed for races — or are request-scoped (no sharing → no race).
- Don't parallelize tool calls unless there's a measured need AND the audit/ordering semantics survive it (the audit tree's PARENT_ID structure assumes a turn's tool calls have a determinable order). The mutation chain in particular must stay sequential (fail-closed gating is inherently sequential).
- Soak/load test the chat under concurrent sessions after the perf pass.

**Warning signs:**
A `new Thread` / `executor.submit` in the chat turn that touches a Vaadin component without `ui.access`; a non-thread-safe `HashMap` cache shared across requests; tool calls dispatched in parallel; intermittent UI glitches under load that weren't there before.

**Phase to address:** Phase 20

---

### Pitfall 21: Declaring the perf pass "done" with no observable proxy that anything actually got faster

**What goes wrong:**
The perf pass ships a pile of "optimization" diffs (caches, batched loads, prefetches) and is marked complete — but there's no benchmark harness (explicitly out of scope), no before/after numbers, no `ToolQueryCountBaseline`-style assertion that the SELECT count actually dropped. Nobody knows if it helped, hurt (added caches with invalidation overhead), or just added risk for nothing — and a future regression that re-introduces the slow path goes undetected.

**Why it happens:**
The milestone explicitly says "no benchmark harness" and "no admin-screen perf" — correct scope discipline — but that gets misread as "no measurement at all". A refactor that *looks* faster feels done.

**How to avoid:**
- "No benchmark harness" ≠ "no observable proxy". Use the existing lightweight proxies: the `ToolQueryCountBaselineTest` family (SELECT-count assertions — Plan 10-04 already uses this; tighten the ceilings where the perf pass eliminates queries, e.g. the per-turn agentstore policy lookup the cache removes), and targeted "N+1 → 1" assertions (the batch FK load: assert the binding path issues 1 query, not N). Each perf change ships with the proxy that proves it.
- Each Phase 20 success criterion is stated as a *checkable* claim: "mutation binding for K to-one refs issues 1 FK query, not K" / "the per-turn schema-policy lookup is not repeated within a turn" — not "it's faster".
- Don't ship a perf change you can't prove changed something. If the only evidence is "it should be faster", it's not in scope yet.

**Warning signs:**
The perf-pass PR has no test changes / no baseline-count changes; success criteria say "improved performance" with no number or count; nobody can answer "what's the before/after".

**Phase to address:** Phase 20

---

### Pitfall 22: Scope creep into the explicitly-out-of-scope benchmark harness / admin-screen perf

**What goes wrong:**
The perf pass grows a JMH module, a load-test rig, a perf-dashboard view, or starts optimizing the `AiAuditEventListView` grid / the Parameters view rendering — all explicitly NOT in v1.2 scope ("no benchmark harness", "admin-screen performance is explicitly NOT in scope" per PROJECT.md). The milestone balloons and the actual targeted hotspots get less attention.

**Why it happens:**
Perf work is a rabbit hole; "while I'm in here" is seductive; the admin screens are the most *visible* slow thing so they attract attention even though they're out of scope.

**How to avoid:**
- Hold the line from PROJECT.md: Phase 20 touches AI-runtime hotspots only — chat turn execution, tool calls, mutation binding/save, media/attachment injection, RAG retrieval/filter building, prompt/context construction, repeated metadata/security/exposure resolution. Not admin UI. Not a benchmark harness (lightweight proxies via existing tests only — Pitfall 21).
- If admin-screen perf is genuinely needed, it's a separate effort (PROJECT.md already says so) — capture it as a backlog/seed item, don't fold it in.
- The Phase 20 plan's "out of scope" section copies these two exclusions verbatim.

**Warning signs:**
A JMH/Gatling dependency appears; a perf-metrics admin view; commits touching `AiAuditEventListView` / `AiParametersDetailView` for speed; the Phase 20 scope drifts past the PROJECT.md hotspot list.

**Phase to address:** Phase 20 (scope guard)

---

### Pitfall 23: Re-carrying the deferred debt (Phase 10 verification, Nyquist backfill, clean-consumer smoke) into v1.2 by accident

**What goes wrong:**
A v1.2 phase plan picks up "while we're here, let's flip Phase 10's `human_needed` status / backfill the missing `*-VALIDATION.md` files / finally do the PKG-05 clean-consumer smoke" — all of which PROJECT.md explicitly **deferred OUT of v1.2** per the 2026-05-11 user decision. v1.2 scope expands, the milestone slips, and the user's explicit "not v1.2" decision is overridden by drift. (Memory note `feedback_fresh_phase_scope`: derive phase scope from ROADMAP + fresh audit; do NOT auto-carry deferred UAT/verification items.)

**Why it happens:**
These items are *right there* in STATE.md's Deferred Items table and feel like low-hanging fruit; the v1.1 milestone audit status was `tech_debt` for these, which itches; "hardening pass" (Phase 19) sounds adjacent to "validation backfill".

**How to avoid:**
- These three are explicitly deferred to v1.3+ / a later hardening pass per PROJECT.md "Deferred (carried to v1.3+)" and the 2026-05-11 user decision: (a) Phase 10 re-verification (`/gsd-verify-work 10`), (b) Nyquist `*-VALIDATION.md` backfill for phases 9/10/11/12/13/13.1, (c) PKG-05/TEST-07 clean-consumer smoke. NO v1.2 phase plan includes them. If a phase plan proposes one, that's scope creep — reject it.
- Phase 19 ("mutation-internals hardening") is *code* hardening (dedup gates, batch FK, cache metadata) — it is NOT the "hardening pass" that owns the Nyquist backfill. Different thing. Don't conflate.
- The roadmapper / discuss-phase steps should explicitly note "these are NOT in v1.2" so no phase silently absorbs them.

**Warning signs:**
A v1.2 phase plan mentions `/gsd-verify-work 10`, `/gsd-validate-phase`, `*-VALIDATION.md`, PKG-05, TEST-07, or "clean-consumer smoke" as in-scope work; the v1.2 phase count grows to absorb the Deferred Items table.

**Phase to address:** Process — roadmapper + `/gsd-discuss-phase` for every v1.2 phase

---

### Pitfall 24: Activating dormant seeds whose triggers aren't met

**What goes wrong:**
A v1.2 phase plan reaches for SEED-001 (learning loop) / SEED-002 (answer-quality regression gate) / SEED-003 (OutputScanner SPI) / SEED-004 (replay/diff runner) / SEED-006 (strict file-backed knowledge path) / SEED-008 (JPQL analytics tool) because it seems thematically adjacent (e.g. the observability phase → "let's also do the replay runner"; the perf phase → "let's add a regression gate") — but all six are dormant for stated reasons (no production-incident corpus, no signal yet, config-driven scanner is sufficient, no retrieval-drift trigger, future tool surface). PROJECT.md explicitly lists "dormant-seed activation (SEED-001/002/003/004/006/008 triggers not met)" as deferred-from-v1.2.

**Why it happens:**
Seeds are aspirational and "thematically adjacent" is a low bar; the observability work especially flirts with SEED-002/004 territory (regression gate / replay runner).

**How to avoid:**
- None of SEED-001/002/003/004/006/008 activate in v1.2 unless its *specific* trigger fires (production-incident corpus exists / v1.1 prompt rules produced signal / config scanner proved insufficient / retrieval drift observed / a named tool-surface need). PROJECT.md says these triggers are NOT met. Check the trigger before touching a seed.
- The Phase 16 observability work is *display* (panels, status indicator) — it is NOT SEED-002 (regression gate) or SEED-004 (replay runner). Keep it display-only.
- If a v1.2 phase plan proposes seed activation, the discuss-phase step must verify the trigger against STATE.md's "Seeds Reviewed" table — and reject if not met.

**Warning signs:**
A v1.2 phase plan references SEED-001/002/003/004/006/008 as in-scope; the observability phase grows a replay/diff runner or an answer-quality gate; "while we're adding observability, let's also…".

**Phase to address:** Process — `/gsd-discuss-phase` for every v1.2 phase (esp. Phase 16, Phase 20)

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Store raw transcripts in audit "for now" to debug STT | Easier to see what the model heard | PII in a queryable admin table + logs; defeats the privacy contract; TEST-17 negative case fails | Never — hash-by-default is the contract; `storeTranscript=true` is a per-host opt-in only |
| Mic button rendered unconditionally, only the bean is `@ConditionalOnProperty` | One less conditional in fragment code | Button throws "no bean" when STT off; mismatched property names go undetected | Never — gate the UI on the same property/`ObjectProvider` as the bean |
| Skip Soniox `DELETE` calls in v1.2, "clean up later" | Ships faster | Audio/transcripts pile up on a third party indefinitely; privacy/cost liability | Never — `finally`-block the deletes from day one |
| Build a fresh `AiTurnDetail` entity/in-memory model for the observability panel | Clean data shape for the UI | Second source of truth diverging from the audit tree; extra schema; the leak guard doesn't cover it | Never — read the existing audit tree; extend it if a field is missing |
| Hold full per-turn tool detail in `AiChatSessionState` | Panel renders instantly without a query | Session memory grows unbounded; contradicts the Phase 12 thin-state decision; OOM risk on a busy host | Only a single-current-turn streaming buffer, cleared on next turn |
| Cache schema/permission/exposure resolution keyed by entity name only | Big per-turn speedup | Cross-user data leak; stale exposure rules after admin edit; stale `MetaClass` on metamodel change | Only with a (user/roles, locale, exposure-version) key + `LlmExposureChangedEvent` eviction |
| Migrate "all `ai-agent.*` properties" to the admin UI mechanically | Uniform settings surface | API keys in a DB table; boot-time toggles that look runtime-editable but aren't | Never — explicit secret/boot-time denylist; restart-flag the rest |
| "Refactor" the mutation gate chain without re-running the Phase 11 test suite unchanged | Looks cleaner, ships the dedup | Reordered fail-closed gating = security regression; changed audit/error shapes break consumers | Never — Phase 11 tests must pass UNCHANGED; if a test needs editing, stop |
| Batch FK refs via `UnconstrainedDataManager` / raw JPQL | One query instead of N | Row-level security bypass — LLM binds mutations to records the user can't see | Never — constrained `DataManager` `IN (...)` only |
| Parallelize the chat turn and update UI from the worker thread | Lower latency | Vaadin per-session threading violated → intermittent UI corruption under load | Only if UI updates go through `ui.access` and audit ordering survives |
| Ship the perf pass with no before/after proxy | Faster to "complete" | Can't prove it helped; regressions undetected; risk added for nothing | Never — use the existing `ToolQueryCountBaseline`-style assertions |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Soniox STT (`/v1/files` + `/v1/transcriptions`) | Reusing the OpenRouter/OpenAI chat key; reusing the chat `base-url`; assuming a single MIME type; skipping the `DELETE`s | Independent `ai-agent.stt.soniox.api-key`; hand-rolled `RestClient` against Soniox's own base-url; forward the browser's actual `MediaRecorder` MIME; `finally`-block `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` |
| OpenAI-direct transcription fallback | Pointing it at `https://openrouter.ai/api/v1` (OpenRouter does NOT proxy `/audio/transcriptions`); reusing the chat key | Explicit `base-url=https://api.openai.com/v1`; separate `ai-agent.stt.openai.api-key`; documented as a distinct key from chat |
| Browser `MediaRecorder` | Assuming webm/opus everywhere; letting permission-denial throw to the UI; unbounded recording length | Send the real MIME; catch permission denial → non-blocking message + retry; ≤60 s cap with visible countdown + auto-stop |
| `AuditWriter.writeToolCall` for STT | Adding a new `AuditKind`; writing the raw transcript by default | `eventName=stt_transcription` (already reserved per AUD-06), no new `AuditKind`; SHA-256 hash by default, raw only on `storeTranscript=true` |
| `AiChatSessionState` (Phase 12) | Hanging per-turn tool detail on it | Keep it thin (`currentConversationId` + listeners); the panel queries the audit tree on demand |
| `AiParameters` settings layer | Writing the model picker / migrated knobs into the strict `default-params.yaml` seed; precedence ambiguity vs `module.properties` | Spring/library defaults → `module.properties`; runtime-tunable → `AiParameters` rows with `module.properties` as fallback; admin overrides must survive reseed; one resolution component, documented precedence |
| `AiParameters` + perf cache | Caching settings with no invalidation | Entity-listener publishes a change event (single publish site, Phase 10 R2 pattern) → settings cache evicts; admin edit visible within one turn |
| `LlmExposurePolicy` + perf cache | Caching the `userVisible AND NOT excluded` composition statically / per-entity | Cache the user-independent inputs; recompute the user-specific composition per turn; evict on `LlmExposureChangedEvent`; `AccessManager` stays authoritative |
| `RetrievalFilterBuilder` (RAG) + perf refactor | Dropping the `(source_entity IS NULL) OR (NOT IN <denied>)` denylist clause or the role-scope clause as "redundant" | Both clauses are load-bearing; refactor construction mechanics, not clauses; TEST-09 RAG leg passes unchanged |
| `MutationGuard` SPI + Phase 19 refactor | Changing the guard call's position in the chain / its signature / the `ToolVetoedException` reuse | Position stays after `AccessManager`+idempotency, before `@Transactional` save; signature unchanged; `ToolVetoedException` reused verbatim; the host-guard integration test passes unchanged |
| `MutationErrorTranslator` + Phase 19 refactor | Emitting a 7th error code; leaking raw exception text / LLM attribute names | The 6-code D-04 taxonomy stays closed; canned safe templates only; the P-22 sanitization tests pass unchanged |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Re-resolving `agent.entities`/`agent.permissions` from scratch per turn (the thing the perf pass targets) | Per-turn agentstore policy lookups; the `ToolQueryCountBaseline` ceiling was raised 0→5 to absorb it (Plan 10-04) | Cache the user-independent metamodel inputs; recompute the user-specific composition once per turn (request-scoped); evict on `LlmExposureChangedEvent` | Always-on; worse with more entities and longer conversations |
| N per-reference `DataManager.load` during mutation binding | One SELECT per to-one FK ref on every `create_record`/`update_record`/`add_related_record` | Batch via constrained `DataManager` `IN (...)`; assert "1 query not N" | Mutations with several FK refs; bulk_save with many rows |
| Re-injecting all task-file media every turn with no fast-path when nothing changed | Re-reading + re-encoding blobs each turn even when the conversation's attachments are unchanged | Cache the resolved `Media` list per conversation, invalidate on attach/delete/TTL-expiry; the LRU budget cap stays | Long multi-turn conversations with attachments |
| A perf cache with no invalidation hook | "It's fast now but admin edits / exposure changes / metamodel changes don't take effect until restart" | Every perf cache wires to its invalidation event (`LlmExposureChangedEvent`, `AiParameters` change event, metamodel change) — no exceptions | The moment an admin changes anything mid-session |
| Parallelizing tool calls / chat-turn work without `ui.access` | Intermittent UI corruption / lost updates / `IllegalStateException` under concurrent sessions | Concurrent AI-runtime work is fine; all UI-touching code goes through `ui.access`; the mutation chain stays sequential | Under load with multiple concurrent sessions |
| Holding per-turn detail in session state for the observability panel | Heap dumps show `AiChatSessionState` retaining tool payloads; OOM on a busy host with long-lived tabs | Panel lazy-queries the audit tree on expand; session state stays thin | Many users × long conversations × tabs left open |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Raw transcripts in audit rows / logs by default | Dictated PII in a queryable admin table and log files | SHA-256 hash by default (`AuditFieldHasher` convention); raw only on per-host `storeTranscript=true`; transcript-leak source-scan test |
| Transcription path calls `ChatService.ask` | Unreviewed transcript becomes a chat turn → unreviewed tool call / mutation; STT pathway no longer disjoint from the chat client | STT package has zero reference to `ChatService`; only sets `MessageInput.setValue`; structural test enforces |
| API keys (`*.api-key`) migrated into the admin `AiParameters` UI | Credentials in a DB table, plaintext to any admin, possibly in audit change-history | Explicit secret denylist for the config-knob migration; keys stay env/`application.properties`-backed (Plan 14-09 line held + extended to STT keys) |
| Batch FK loading via `UnconstrainedDataManager` / raw JPQL during mutation binding | Row-level-security bypass — LLM binds mutations to records the user can't see | Constrained `DataManager` `IN (...)` only; `UnconstrainedDataManager` stays scoped to its existing system-internal uses |
| Reordering the fail-closed mutation gate chain during the "hardening" refactor | A `MutationGuard` SPI or `@Transactional` save sees a mutation an earlier gate should have rejected → privilege escalation | Lock the order (role → exposure → AccessManager → idempotency → guard → save) with a source-level invariant test; Phase 11 security tests pass unchanged |
| Schema/permission/exposure cache keyed too coarsely | User A sees user B's readable-schema; stale exposure denylist after an admin `EXCLUDE` | Cache key includes (user/roles, locale, exposure-version); evict on `LlmExposureChangedEvent`; `AccessManager` stays authoritative |
| Dropping the RAG exposure-denylist clause as "redundant" during a perf refactor | LLM retrieves RAG chunks for entities it's supposed to be blind to | Keep both the role-scope and `(source_entity IS NULL) OR (NOT IN <denied>)` clauses; TEST-09 RAG leg passes unchanged |
| Internal tool/entity names rendered in a user-facing observability panel / status indicator | Leaks the internal vocabulary the v1.1 PROMPT-03/04/06 + `OutputScannerAdvisor` guards exist to hide | User-facing surfaces show humanized descriptions only; raw names stay in the admin-only audit view; a leak-guard UI test using the Phase 9 pattern packs |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Transcription auto-submits the turn | User can't fix a misheard word; a wrong transcription becomes a wrong (possibly mutating) action | Transcript lands in `MessageInput` for review/edit; user clicks send |
| Slow transcription freezes the chat panel | Whole session hangs for seconds while Soniox round-trips | Background executor + ephemeral "transcribing…" indicator; `ui.access` to push the result |
| Mic-permission denial / Safari codec / over-long recording surface as errors | Looks like "the AI is broken"; blank panel or stack trace | Non-blocking message + retry button in the input area; ≤60 s cap with countdown + auto-stop; server accepts the browser's actual MIME |
| Mic button visible when STT is disabled | Click → "no bean"/NPE; confusing | Gate the button on the same `@ConditionalOnProperty`/`ObjectProvider` as the service |
| Streaming-status indicator shows "calling `query_records`" | Internal vocabulary leaks to end users (v1.1 contract violation) | Generic phases: "thinking…", "looking things up…", "writing the answer…" |
| Custom model name saved with no validation feedback | Admin's typo surfaces as a 404 to an end user later, far from the cause | Lightweight validation ping on save with a clear "couldn't reach `X` — saved anyway / fix it" outcome; graceful chat-turn fallback if the model 404s |
| Admin edits a knob, nothing changes until restart | "The admin UI doesn't work" | Settings cache evicts on `AiParameters` change event; change visible within one turn |
| Curated model dropdown mixes self-hostable and proprietary with no distinction | Operator picks a proprietary model thinking it's a "recommended default", violating the product constraint | "Self-hostable (recommended)" curated section; "Custom model name" free-entry clearly labeled as the operator's-responsibility escape hatch |

## "Looks Done But Isn't" Checklist

- [ ] **STT audit:** Often missing the *negative* test — verify a default-config `STT_TRANSCRIPTION` row shows the SHA-256 hash and NO raw text, AND `storeTranscript=true` shows the raw text (TEST-17 covers both).
- [ ] **STT disjointness:** Often missing the structural assertion — verify the STT package has zero import of `ChatService`/`DefaultChatServiceImpl` and only calls `MessageInput.setValue`.
- [ ] **Soniox cleanup:** Often missing the error-path `DELETE` — verify a 4xx during transcription still triggers `DELETE /v1/files/{id}`.
- [ ] **STT key wiring:** Often missing the three-key separation — verify Soniox key ≠ OpenAI-direct key ≠ chat key, and the OpenAI fallback's `base-url` is `api.openai.com`, not OpenRouter.
- [ ] **Mic button gating:** Often missing the "default config = no mic, no bean" test — verify it renders nothing under default config.
- [ ] **Observability panel source:** Often missing the "reads the existing audit tree, no new turn model" check — verify no new `AiTurnDetail`-style entity and no per-turn structure on `AiChatSessionState`.
- [ ] **Observability leak guard:** Often missing the UI-panel leak test — verify the user-facing panel/status renderer never emits a HOST_PREFIX_LEAK / TOOL_NAME_LEAK match (reuse Phase 9 pattern packs).
- [ ] **Curated model list:** Often missing the allowlist test — verify every curated entry is a known open-weights model id.
- [ ] **Config-knob migration:** Often missing the secret/boot-time denylist test — verify no `*.api-key` has an `AiParameters` key and boot-time toggles either stay in properties or are restart-flagged in the UI.
- [ ] **Settings precedence:** Often missing the reseed test — verify an admin's `AiParameters` value survives a restart and is NOT clobbered by the strict seed.
- [ ] **Settings cache invalidation:** Often missing — verify an `AiParameters` edit takes effect on the next chat turn without a restart.
- [ ] **Mutation-hardening "no behavior change":** Often missing the "Phase 11 tests pass UNCHANGED" gate — verify TEST-10..13, `MutationToolInvariantsTest`, the gating-order test, the audit-row assertions, the `MutationErrorTranslator` template tests, and the `MutationGuard` veto test all pass with zero edits.
- [ ] **Batch FK load security:** Often missing — verify a user without row-level access to a parent can't `add_related_record` to it (existing Phase 11 test passes unchanged) AND the batch loader uses the constrained `DataManager`.
- [ ] **Perf cache safety:** Often missing the eviction wiring — verify each new cache evicts on `LlmExposureChangedEvent` / `AiParameters` change / metamodel change, and two users with different roles get different `agent.entities`.
- [ ] **Perf observable proxy:** Often missing — verify each perf change ships a `ToolQueryCountBaseline`-style assertion (tightened ceiling / "1 not N") proving the query count actually dropped.
- [ ] **Locale-correctness under caching:** Often missing — verify a `vi` user gets `vi` entity captions with the cache on (`agent.entities` locale-keyed) and `agent.permissions` bytes are locale-identical (TEST-08 passes unchanged).
- [ ] **Deferred debt NOT re-carried:** Often missing the scope check — verify no v1.2 phase plan includes Phase 10 re-verification, Nyquist `*-VALIDATION.md` backfill, or PKG-05/TEST-07 clean-consumer smoke.
- [ ] **No dormant-seed activation:** Often missing the scope check — verify no v1.2 phase plan activates SEED-001/002/003/004/006/008 (triggers not met).

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Raw transcripts already written to audit/logs | MEDIUM | Flip default to hash; purge/rotate affected `AiAuditEvent` rows + log files; add the transcript-leak test; document the incident |
| Transcription path wired to `ChatService.ask` | LOW | Remove the `ChatService` reference; route to `MessageInput.setValue` only; add the structural test |
| OpenAI fallback pointed at OpenRouter / wrong key in use | LOW | Split the properties; set explicit `base-url`; add the startup INFO line + the key-separation doc table |
| Soniox files/transcriptions piling up | LOW–MEDIUM | Add the `finally`-block `DELETE`s; one-time cleanup script over existing Soniox resource ids; add the delete-call tests |
| Mic button shows when STT disabled | LOW | Gate the button on the `ObjectProvider`/property; add the default-config test |
| Observability panel built as a parallel turn model | MEDIUM | Re-point the panel at the audit tree; delete the parallel entity/state; add the missing audit fields if needed; add the leak-guard UI test |
| `AiChatSessionState` bloated with per-turn detail | MEDIUM | Move to lazy audit-tree queries on expand; restore session state to the Phase 12 thin shape; soak-test the heap |
| Proprietary model in the curated list | LOW | Replace with the current self-hostable SOTA; add the allowlist test; label the dropdown sections |
| API key landed in `AiParameters`/admin UI | MEDIUM | Move the key back to env/`application.properties`; remove the `AiParameters` key + the UI field; purge it from audit change-history; add the secret-denylist test |
| Admin edit doesn't take effect without restart | LOW–MEDIUM | Add the `AiParameters` change event + cache eviction (Phase 10 R2 pattern); add the "edit visible next turn" test |
| Mutation-hardening refactor changed behavior | HIGH | Revert to the byte-for-byte-identical state; redo the refactor with the Phase 11 test suite as the unchanging gate; if a test "had to change", that was the bug |
| Batch FK load bypassed row-level security | HIGH | Switch to constrained `DataManager` `IN (...)`; audit for any mutation that bound to an out-of-scope record while the bug was live; add the row-level-access test |
| Perf cache leaks data across users / serves stale exposure | HIGH | Add the (user/roles, locale, exposure-version) key + `LlmExposureChangedEvent` eviction (or revert the cache); audit for cross-user exposure during the window; add the two-users-different-roles test |
| Deferred debt accidentally pulled into v1.2 | LOW | Cut it from the phase plan; re-park it in the Deferred section; note it in the milestone scope |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 1. Raw transcript leak into audit/logs | Phase 15 | TEST-17 both modes + a transcript-leak source-scan test |
| 2. Transcription calls `ChatService.ask` | Phase 15 | Structural test: STT package has zero `ChatService` import; UAT has an "edit transcript before send" step |
| 3. OpenRouter base-url / wrong key reused for transcription | Phase 15 | Three separate key properties; OpenAI fallback `base-url` asserted = `api.openai.com`; startup INFO line; key-table in README |
| 4. Soniox files/transcriptions not cleaned up | Phase 15 | Test asserts `DELETE`s on success path + on a 4xx; cleanup is in `finally` |
| 5. UI thread blocked / bad `UI.access` on transcript push | Phase 15 | Dedicated STT executor bean; `ui.access` + detached-UI guard; navigate-away-mid-transcription test |
| 6. `@ConditionalOnProperty` mic-button mis-wiring | Phase 15 | "Default config = no mic + no bean" boot test (Phase 11 pattern); property name centralized as a constant |
| 7. Browser codec / mic-permission / over-long recording as errors | Phase 15 | Server forwards the real MIME; permission-denial → non-blocking retry; ≤60 s cap test; 4xx/network/too-long all keep chat usable |
| 8. Parallel turn model OR internal-name leak in observability panel | Phase 16 | No new turn entity; panel reads the audit tree; `ChatObservabilityLeakTest` using Phase 9 pattern packs |
| 9. Per-turn detail bloats `AiChatSessionState` | Phase 16 | `AiChatSessionState` field review vs the Phase 12 thin-state contract; lazy audit-tree query on expand; heap soak |
| 10. Non-self-hostable model in the curated list | Phase 17 | Allowlist test of curated entries; dropdown labels curated vs custom; reviewer cross-checks `project_self_hostable_models_only.md` |
| 11. Custom model name silently invalid | Phase 17 | Validation ping on save with a clear outcome; graceful chat-turn fallback on a 404 |
| 12. Model choice / migrated knobs override the strict seed YAML | Phase 17 (model) + Phase 18 (knobs) | Precedence test (AiParameters wins; module.properties fallback; admin value survives reseed); single resolution component |
| 13. Secrets / boot-time toggles exposed in the admin UI | Phase 18 | Secret/boot-time denylist test (no `*.api-key` has an `AiParameters` key; boot-time toggles stay in properties or are restart-flagged); reviewed migration list |
| 14. Cache staleness on mid-session admin edit | Phase 18 (invalidation hook) + Phase 20 (cache discipline) | `AiParameters` change event (single publish site) + cache eviction; "edit visible next turn" test |
| 15. "Hardening" refactor is a behavior change | Phase 19 | Phase 11 test suite passes UNCHANGED; new shared-component gating-order invariant test; D-04 6-code closure test; `MutationGuard` host-guard test unchanged |
| 16. Batch FK load bypasses row-level checks | Phase 19 | Batch loader uses the constrained `DataManager`; Phase 11 row-level-access mutation test passes unchanged |
| 17. Cache leaks data across users / serves stale exposure | Phase 20 | (user/roles, locale, exposure-version) cache key; `LlmExposureChangedEvent` eviction; two-users-different-roles test; TEST-09 unchanged; Plan 10-04 "no-cache" baseline updated deliberately |
| 18. Per-locale memoization mistake | Phase 20 | `agent.entities` locale-keyed; `agent.permissions` not locale-keyed; TEST-08 cross-locale passes unchanged; `vi`-user-gets-`vi`-captions test |
| 19. RAG filter refactor drops role/exposure scoping | Phase 20 | RAG filter keeps both clauses; `RetrievalFilterBuilderDenylistTest` / TEST-09 RAG leg pass unchanged |
| 20. Data races in Vaadin's per-session model | Phase 20 | UI updates via `ui.access` only; thread-safe or request-scoped caches; mutation chain stays sequential; concurrent-session soak test |
| 21. Perf pass "done" with no observable proxy | Phase 20 | Each perf change ships a `ToolQueryCountBaseline`-style assertion; success criteria are checkable counts, not "faster" |
| 22. Scope creep into benchmark harness / admin-screen perf | Phase 20 (scope guard) | Phase 20 plan copies the PROJECT.md exclusions verbatim; no JMH/Gatling dep; no admin-view perf commits |
| 23. Re-carrying deferred debt into v1.2 | Process (roadmapper + every `/gsd-discuss-phase`) | No v1.2 phase plan mentions `/gsd-verify-work 10`, `*-VALIDATION.md` backfill, PKG-05, TEST-07, clean-consumer smoke |
| 24. Activating dormant seeds without triggers | Process (every `/gsd-discuss-phase`, esp. 16 & 20) | No v1.2 phase plan references SEED-001/002/003/004/006/008 as in-scope; triggers checked against STATE.md |

## Sources

- `.planning/PROJECT.md` — v1.2 scope, constraints, key decisions, deferred decisions/items, out-of-scope (HIGH)
- `.planning/ROADMAP.md` — Backlog 999.1 (mutation hardening) + 999.2 (Soniox STT with the "Cross-cutting constraints" list), out-of-scope notes (HIGH)
- `.planning/MILESTONES.md` — v1.1.0 close: milestone debt carried forward, deferred planning artifacts (HIGH)
- `.planning/STATE.md` — Hard Build-Order, Deferred Items table, Accumulated Context → Decisions (Plans 09-01/09-03/09-05, 10-02/10-04/10-05/10-06, 11-01/11-07C/11-09, 12-*, 13-*, 13.1-*, 14-09), Seeds Reviewed (HIGH)
- Project memory: `project_self_hostable_models_only.md`, `feedback_jmix_unconstrained_for_system_writes.md`, `feedback_jmix_loadvalue_store.md`, `feedback_rich_tool_descriptions.md`, `feedback_no_object_toolparam.md`, `feedback_jmix_ui_review_checklist.md`, `feedback_fresh_phase_scope.md`, `feedback_ai_as_jmix_client.md`, `feedback_reuse_jmix_builtins.md`, `feedback_jmix_messages_over_spring.md`, `project_local_dev_port.md` (HIGH)
- Soniox STT API (`POST /v1/files`, `POST /v1/transcriptions` `model=stt-async-v4` + `language_hints`, `DELETE` cleanup) — as described in the milestone context / ROADMAP 999.2 cross-cutting constraints; verify exact request/response shapes against current Soniox docs at Phase 15 plan time (MEDIUM — not independently re-fetched here)
- OpenRouter does not proxy `/audio/transcriptions` — per the milestone context / ROADMAP 999.2 (MEDIUM — consistent with OpenRouter's documented chat-completions-only proxy scope; verify at plan time)
- Vaadin Flow threading model (`UI.access`, `@Push`) — established by the existing v1.0/v1.1 streaming implementation in this repo (HIGH for "it's how this repo already does it")

---
*Pitfalls research for: v1.2 Operator Experience, Voice Input & Runtime Performance — Jmix AI agent add-on*
*Researched: 2026-05-11*
