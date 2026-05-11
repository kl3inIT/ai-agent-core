# Stack Research — v1.2 Additions

**Domain:** Jmix 2.8 + Spring Boot 3 + Spring AI 1.1.x AI agent add-on (`ai-agent-core`) — incremental v1.2 stack delta (Operator Experience, Voice Input & Runtime Performance)
**Researched:** 2026-05-11
**Confidence:** HIGH — Spring AI 1.1.x transcription API + config keys verified against current spring.io/spring-ai/reference; Soniox HTTP contract verified against current soniox.com/docs; build coords read from the repo's own gradle files. MEDIUM only on the browser-mic component choice (no Jmix/Vaadin first-party microphone primitive — a small first-party `@JsModule` wrapping `MediaRecorder` is the standard option).

> Supersedes the v1.1.0 delta that previously lived in this file (archived context: STT was scoped but not built in v1.1).

## TL;DR — Net new runtime dependency for v1.2: effectively ZERO

- **OpenAI transcription fallback** — `OpenAiAudioApi` / `OpenAiAudioTranscriptionModel` / `OpenAiAudioTranscriptionOptions` / `AudioTranscriptionPrompt` / `AudioTranscriptionResponse` / the `AudioTranscriptionModel` interface all ship inside `org.springframework.ai:spring-ai-openai:1.1.4`, which `ai-agent` already declares (`ai-agent.gradle:29`). Wire it manually (mirroring the existing manual OpenRouter `ChatModel` wiring). Do **not** add `spring-ai-starter-model-openai` just for property auto-config.
- **Soniox STT (default path)** — NO Soniox Java SDK exists (Python/Node/Web/React/React Native only). Use Spring's built-in `RestClient` (already on classpath via Spring Web / `spring-boot-starter-web`). No new dep.
- **Browser audio capture** — a ~50-line first-party `@JsModule` JS file wrapping `MediaRecorder` + `getUserMedia`, posting the blob to a Spring `@RestController` upload endpoint. No add-on, no npm dep.
- **Model-name dropdown + config-knob migration** — pure Jmix entity/view work + a Vaadin `ComboBox` with `setAllowCustomValue(true)` (or `ComboBox` + `TypedTextField` pair). Nothing new.
- **Chat UX panels (state panel / tool-detail panel / streaming status)** — existing Jmix Flow UI components inside `ChatPanelFragment`. Nothing new.
- **Perf pass** — Spring Cache abstraction (`spring-boot-starter-cache`) is already a dependency (`ai-agent.gradle:70`); `ConcurrentMapCacheManager` is already auto-registered (`AiAgentGuardAutoConfiguration`). Do **not** add Caffeine. No benchmark harness.

## Recommended Stack

### Core Technologies (all already present — confirm pinning, add nothing)

| Technology | Version | Purpose | Why / Status |
|------------|---------|---------|--------------|
| `org.springframework.ai:spring-ai-openai` | 1.1.4 (BOM-pinned via `spring-ai-bom`) | `OpenAiAudioApi`, `OpenAiAudioTranscriptionModel`, `OpenAiAudioTranscriptionOptions`, `AudioTranscriptionPrompt`, `AudioTranscriptionResponse`, `AudioTranscriptionModel`/`TranscriptionModel` interface, `OpenAiAudioApi.TranscriptResponseFormat` | **Already declared** at `ai-agent/ai-agent/ai-agent.gradle:29`. OpenAI audio-transcription classes live in this module (no separate audio artifact). OpenAI fallback builds with **zero new deps** via `new OpenAiAudioApi(...)` + `new OpenAiAudioTranscriptionModel(...)`. |
| Spring `RestClient` (`org.springframework.web.client.RestClient`) | Spring Framework 6.1+ (Boot 3.x, BOM-pinned) | HTTP client for the Soniox async API (`POST /v1/files`, `POST /v1/transcriptions`, `GET .../transcript`, `DELETE`) | **Already on classpath** transitively (Spring Web). `RestClient` is the modern, fluent, synchronous client — exactly right for the short sequential Soniox call chain. **No `WebClient`/reactor needed** — STT is request/response, not streaming. |
| `org.springframework.boot:spring-boot-starter-cache` + `ConcurrentMapCacheManager` | Boot 3.x (BOM-pinned) | Per-resolution caches for the perf pass (metadata/security/exposure-policy resolution, related-write metadata, `MetaClass` lookups) | **Already declared** (`ai-agent.gradle:70` — powers `RateLimitGuard`/`TokenBudgetGuard`); `AiAgentGuardAutoConfiguration` already registers a default `ConcurrentMapCacheManager` when the host hasn't supplied one. Reuse it. |
| Jmix Flow UI (`io.jmix.flowui:*`), Vaadin Flow `ComboBox`/`Select`, `@JsModule` | Jmix 2.8.1 BOM | Model-name dropdown, chat-state side panel, collapsible tool-detail panel, ephemeral streaming-status indicator, mic-button component | **Already present.** `ComboBox.setAllowCustomValue(true)` gives "curated list + free entry"; `@JsModule` + a tiny first-party JS file is the standard Flow pattern for browser-API access (here `MediaRecorder`). Per project memory `feedback_jmix_first_ui`: Jmix XML descriptors + Jmix components by default. |
| `java.security.MessageDigest` (`SHA-256`) + `java.util.HexFormat` | JDK 21 built-in | Privacy-safe `STT_TRANSCRIPTION` audit: SHA-256 transcript hash by default; raw transcript only when `ai-agent.stt.audit.storeTranscript=true` | No dependency. |

### Supporting Libraries (all already transitive — no declarations needed)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jackson (`jackson-databind`) | Boot-BOM-pinned | (De)serialize Soniox JSON: request `{file_id, model, language_hints, ...}`, response `{id, status, error_type, error_message, ...}`, transcript `{tokens:[{text, speaker, language, ...}]}` | **Already transitive** (Spring Boot; `ai-agent.gradle:62` already adds `jackson-dataformat-yaml`). Define small Java records for the Soniox payloads. |
| `jakarta.validation:jakarta.validation-api` + Hibernate Validator | Boot-BOM-pinned | Bean Validation on new `AiParameters` fields migrated in (top-k range, similarity threshold 0..1, task-file token budget > 0, STT enum) | **Already declared** (`ai-agent.gradle:63`); reuse the `@DecimalMin`/`@NotBlank` pattern from `AiParametersBody`. |
| `net.ttddyy:datasource-proxy` (test scope) | 1.11.0 | Count JDBC SELECTs in tests to assert N+1 → 1 after the mutation-binding / RAG-filter / metadata-resolution refactors | **Already declared** test-scoped (`ai-agent.gradle:111`). No benchmark harness needed beyond this. |
| `org.springframework:spring-test` (`MockRestServiceServer`) | Boot-BOM-pinned | Unit-test the `SonioxTranscriptionService` HTTP call chain without hitting Soniox | **Already present** via `spring-boot-starter-test`; `MockRestServiceServer` binds to `RestClient.Builder` (Spring Framework 6.1+). |
| `org.springframework.ai:spring-ai-test` | 1.1.4 | Optional live STT smoke helpers | **Already on `ai-agent` test classpath** (`ai-agent.gradle:118`). Keep any Soniox/OpenAI live STT test behind a `@Tag("live")`-style opt-in tag (matches the existing live-LLM test policy — see project constraint). |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Browser dev | Manual mic-capture smoke | `MediaRecorder`/`getUserMedia` need a secure context (https or `localhost`); local dev is `http://localhost:8088` (per project memory `project_local_dev_port`) — qualifies. |
| `MediaRecorder.isTypeSupported` | Feature-detect codec | Prefer `audio/webm;codecs=opus` (Chrome/Edge/Firefox); fall back to `audio/mp4` (Safari). No transcoding either way. |

## (a) Spring AI 1.1.x audio transcription — first-class abstraction? YES.

Verified against `https://docs.spring.io/spring-ai/reference/api/audio/transcriptions/openai-transcriptions.html` and Context7 `/websites/spring_io_spring-ai_reference`:

- **Interface:** `org.springframework.ai.audio.transcription.AudioTranscriptionModel` — the generic `TranscriptionModel`-style abstraction (`Model<AudioTranscriptionPrompt, AudioTranscriptionResponse>`).
- **Prompt / response:** `AudioTranscriptionPrompt(Resource audio, AudioTranscriptionOptions options)` → `AudioTranscriptionResponse` (`response.getResult().getOutput()` is the transcript `String`).
- **OpenAI impl:** `OpenAiAudioTranscriptionModel`, built from `OpenAiAudioApi` (`new OpenAiAudioApi(apiKey)` or the builder with a custom `base-url`), configured via `OpenAiAudioTranscriptionOptions.builder().model(...).language(...).prompt(...).responseFormat(TranscriptResponseFormat.TEXT).temperature(0f).build()`.
- **Models supported:** `whisper-1` (default), `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`.
- **Coordinates:** classes ship in `org.springframework.ai:spring-ai-openai` — **already a dependency** of `ai-agent` at `1.1.4`. The auto-config starter `org.springframework.ai:spring-ai-starter-model-openai` exists and binds `spring.ai.openai.audio.transcription.*` + auto-registers an `OpenAiAudioTranscriptionModel` bean — but this project does **not** use Spring AI's chat auto-config either (it wires `ChatModel` manually for the OpenRouter `base-url` pattern), so the OpenAI fallback should likewise be wired **manually** inside the add-on's auto-configuration.

**Config keys (for reference / documenting host overrides — only relevant if the starter route is ever chosen):**

| Property | Meaning | Default |
|----------|---------|---------|
| `spring.ai.model.audio.transcription` | `openai` to enable, `none` to disable | `openai` |
| `spring.ai.openai.audio.transcription.api-key` | OpenAI key (independent of the chat key — OpenRouter does **not** proxy `/v1/audio/transcriptions`) | — |
| `spring.ai.openai.audio.transcription.base-url` | endpoint host | `api.openai.com` |
| `spring.ai.openai.audio.transcription.options.model` | `whisper-1` / `gpt-4o-transcribe` / `gpt-4o-mini-transcribe` | `whisper-1` |
| `spring.ai.openai.audio.transcription.options.language` | ISO-639-1 hint | — |
| `spring.ai.openai.audio.transcription.options.prompt` | style/context hint | — |
| `spring.ai.openai.audio.transcription.options.response-format` | `json` / `text` / `srt` / `verbose_json` / `vtt` | `json` |
| `spring.ai.openai.audio.transcription.options.temperature` | 0..1 | `0` |

**Recommendation for v1.2:** expose the add-on's own `ai-agent.stt.openai.api-key` (+ optional `ai-agent.stt.openai.model`, default `gpt-4o-mini-transcribe` — cheap/fast — or `whisper-1`) and construct `OpenAiAudioTranscriptionModel` manually in `SpringAiTranscriptionService`. Keep this OpenAI key strictly separate from the chat-provider key. `AudioTranscriptionPrompt` accepts a Spring `Resource` — the uploaded blob can be wrapped in a `ByteArrayResource` (no temp file required, but a `FileSystemResource` is fine too).

## (b) Soniox async transcription HTTP contract — confirmed against current docs

Verified 2026-05 against `https://soniox.com/docs/stt/async/async-transcription`, `https://soniox.com/docs/api-reference`, `https://soniox.com/docs/api-reference/stt/transcriptions/create_transcription`, `https://soniox.com/docs/stt/models`.

- **Base URL:** `https://api.soniox.com/v1`
- **Auth:** `Authorization: Bearer <SONIOX_API_KEY>` — an **independent** key from the chat/OpenRouter key. Surface as `ai-agent.stt.soniox.api-key`.
- **Step 1 — upload file:** `POST /v1/files`, `Content-Type: multipart/form-data`, single part **field name `file`** carrying the raw `webm/opus` or `mp4` bytes (Soniox auto-detects format — no transcoding). Response: `{ "id": "<uuid>" }`. Supported input formats explicitly include `webm`, `mp4`, `m4a`, `mp3`, `ogg`, `wav`, `flac`, `aac`, `aiff`, `amr`, `asf`. Max audio length: **5 hours** per request (far above the ~60s chat-dictation cap).
- **Step 2 — create transcription:** `POST /v1/transcriptions`, JSON body. Fields:
  - `model` — **required**, max 32 chars. **Use `"stt-async-v4"`** (current generation, released 2026-01-29). The alias `"stt-async-v3"` also points to v4; the literal `stt-async-v3` model was removed 2026-02-28 (auto-routes to v4). Make it a knob (`ai-agent.stt.soniox.model`, default `stt-async-v4`).
  - `file_id` — the id from Step 1 (mutually exclusive with `audio_url`; use `file_id`).
  - `language_hints` — array of language codes, e.g. `["vi","en"]` (auto-detected if omitted). Optional `language_hints_strict` (boolean) to lean harder on the hints.
  - `client_reference_id` — optional tracking id (≤256 chars) — handy to correlate with the `AiAuditEvent` row.
  - `webhook_url` (HTTPS-only, ≤256 chars) + `webhook_auth_header_name` / `webhook_auth_header_value` — **optional**. For server-side Jmix chat, **short polling** is simpler (no public callback endpoint needed). Use webhooks only if a host explicitly wants push.
  - Response (201): `{ id, status, created_at, model, filename, audio_duration_ms, error_type, error_message, webhook_status_code, ... }`. `status` ∈ `"queued" | "processing" | "completed" | "error"`.
- **Step 3 — poll:** `GET /v1/transcriptions/{id}` until `status == "completed"` (or `"error"` → read `error_type` / `error_message`). Bound the loop with a fixed short interval + hard timeout aligned to the recording cap; on timeout surface a non-blocking retry.
- **Step 4 — fetch transcript:** `GET /v1/transcriptions/{id}/transcript` → `{ "tokens": [ { "text", "speaker", "language", "translation_status", ... }, ... ] }`. Concatenate `tokens[].text` (the `text` values already carry the needed leading spaces) to get the plain transcript that lands in `MessageInput`.
- **Step 5 — cleanup (always, including on error):** `DELETE /v1/files/{id}` **and** `DELETE /v1/transcriptions/{id}`, in a `finally`-style block, so transient/PII audio + transcript don't linger on Soniox.
- **Errors:** `400` (validation_errors), `401` (auth), `402` (balance/quota), `429` (rate/capacity), `500`. Map all to the non-blocking "transcription failed — retry" UI; never bubble raw provider text into the chat.

> **Correction to the ROADMAP Backlog notes:** they were right on shape (`POST /v1/files` → `POST /v1/transcriptions` with `model=stt-async-v4` + `language_hints`, then `DELETE` cleanup), but: (1) there is **no status field on the `/transcript` resource** — status lives on `GET /v1/transcriptions/{id}`; (2) the transcript text comes from a **separate** `GET /v1/transcriptions/{id}/transcript` call returning a `tokens` array (not a flat `text` string). Plan the client around both.

## (c) Anything beyond `RestClient` for the Soniox client? NO.

- **No Soniox Java SDK exists.** Soniox publishes Python, Node, Web, React, and React Native SDKs only (verified — `soniox.com/docs/sdk/*`, `github.com/soniox`, `soniox.com/blog/new-soniox-sdks`). Confirm "do not add a Soniox SDK dependency" downstream.
- **`RestClient` is sufficient and preferred:** multipart upload (`MultipartBodyBuilder` → `RestClient.post().contentType(MULTIPART_FORM_DATA).body(...)`), JSON post/get with Jackson, and `DELETE` are all first-class. Build one `RestClient` from a configured `RestClient.Builder` (base-url `https://api.soniox.com/v1`, default `Authorization` header from `ai-agent.stt.soniox.api-key`, sane connect/read timeouts).
- **Do not** pull in OkHttp / Apache HttpClient / Retrofit / Feign — `RestClient` already wraps the JDK `HttpClient`.
- **Do not** add reactor/`WebClient` — the flow is strictly sequential request/response; a blocking `RestClient` keeps it simple and avoids dragging reactive types into the add-on.

## (d) Browser-side `MediaRecorder` capture → server — minimal setup, what to reuse

**Decision: a small first-party `@JsModule` JS file + a Spring `@RestController` upload endpoint.** Rationale:

- Jmix/Vaadin's built-in `Upload` component handles **file-picker** uploads, not microphone capture — `MediaRecorder` + `getUserMedia` are browser APIs with no Java/Flow wrapper in the Jmix or core Vaadin component set. There is a community "Audio Recorder for Vaadin" add-on in the Vaadin Directory, but adopting a third-party add-on conflicts with the no-new-deps / Jmix-first posture and pins us to its maintenance — **skip it**; the JS wrapper is ~50 lines.
- Minimal client module (e.g. `src/main/resources/META-INF/frontend/ai-mic-recorder.js`, referenced via `@JsModule` on a tiny Flow `Component` mounted in `ChatPanelFragment`):
  1. `await navigator.mediaDevices.getUserMedia({ audio: true })` (secure context — https or `localhost`; `localhost:8088` in dev qualifies).
  2. `new MediaRecorder(stream, { mimeType: MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/mp4' })` — **no transcoding**; send whatever the browser produces; Soniox/OpenAI auto-detect.
  3. Collect `dataavailable` chunks → `Blob`; on `stop`, `POST` via `fetch` + `FormData` to a Jmix REST endpoint, including the active conversation id; same-origin so it rides the existing Vaadin session cookie. Protect the endpoint with the normal Jmix security filter chain.
  4. Enforce the ~60s cap client-side (`setTimeout` → `rec.stop()`); also re-check duration server-side from `audio_duration_ms` (Soniox) or before the OpenAI call.
  5. On the response (`{ transcript }` or `{ error }`), return into the Flow component (e.g. `getElement().executeJs(...)` return-promise or a `@ClientCallable`) so the controller drops the text into `ChatPanelFragment.messageInputSlot`'s `MessageInput` for review before send. `TranscriptionService` does **not** call `ChatService.ask` directly.
- **Server endpoint:** a thin `@RestController` (e.g. `POST /ai-agent/stt`) accepting `multipart/form-data`, behind Jmix security, delegating to `TranscriptionService` and writing the `STT_TRANSCRIPTION` audit. Keeping it a REST endpoint (rather than streaming the blob through a Vaadin `Upload` receiver) avoids the deprecated `Upload.getReceiver`/`setReceiver` path (project memory `feedback_jmix_upload_receiver_deprecated` — Vaadin 24.8 marks it forRemoval) and keeps the audio bytes out of the Vaadin server-push channel. `spring-boot-starter-web` is already on the host classpath (`jmix-app/build.gradle:60`).
- Integration point: keep the mic UI inside `ChatPanelFragment` per the existing surface contract; `ChatPanelFragment.messageInputSlot` is stable from v1.0 + Phase 12 (the STT phase depends only on that).

## (e) Perf pass — any new dep? NO. (No Caffeine.)

- **Caching:** `spring-boot-starter-cache` is already declared (`ai-agent.gradle:70`) and a `ConcurrentMapCacheManager` is already auto-registered. Add new named caches (`@Cacheable("ai-agent.metadata")`, `"ai-agent.exposurePolicy"`, `"ai-agent.relatedWriteMeta"`, etc.) or hand-rolled `ConcurrentHashMap` memoization where `@Cacheable` proxying is awkward (e.g. inside a `@Tool` bean). **Do not add `com.github.ben-manes.caffeine:caffeine`** — the v1.2 hotspots (metamodel/security/exposure-policy resolution, related-write metadata, prompt scaffolding) are bounded, low-churn, and JVM-lifetime-stable; `ConcurrentMapCacheManager` (no eviction) is the right fit. Invalidate the exposure cache on `LlmExposureChangedEvent`; the rest are effectively immutable for the JVM lifetime. Caffeine would only matter if a cache needed TTL/size eviction — none of these do.
- **Batching to-one FK loads during mutation binding:** pure `DataManager` fluent-API work (collect distinct FK ids per `MetaClass`, one `.load(...).ids(...)` round-trip, build a lookup map). No dep. (See project memory `feedback_jmix_unconstrained_for_system_writes` — system-internal pre-resolution may use `UnconstrainedDataManager`, but user-attributable mutation binding stays on the regular `DataManager`.)
- **Hotspot identification:** the existing test-scoped `net.ttddyy:datasource-proxy` (`ai-agent.gradle:111`) counts JDBC SELECTs in tests to confirm N+1 → 1. **No benchmark harness** (JMH/gatling/custom rig) — explicitly out of scope.

## (f) Model dropdown + config-knob migration — does the project already pin everything? YES.

- The chat **model code** is currently a free-typed `AiParameters` field. The v1.2 admin "curated dropdown + free entry" is implemented purely with existing Jmix/Vaadin pieces: a `ComboBox<String>` with `setItems(curatedModelIds)` + `setAllowCustomValue(true)` (or a `ComboBox` for curated + a `TypedTextField` for custom) bound to the same `AiParameters` attribute via a data container. Curated list = self-hostable open-weights (project memory `project_self_hostable_models_only`); free entry = anything the host routes to. **No dep, no Spring AI change** — model selection is already per-request via `ChatOptions` in the existing OpenRouter wiring.
- **Config-knob migration** (RAG top-k / similarity threshold, mutation toggle, task-file token budget, chat surface mode, STT enable/provider): add columns to the `AiParameters` entity (already UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`), a Liquibase changelog included in the main `changelog.xml`, messages in **all** locale bundles, and fields on the Parameters/Settings Jmix view. **Note:** these AI entities live in the `agentstore` data store (project memory `feedback_jmix_loadvalue_store`) — the changelog and any `loadValue`/`loadValues` must target `agentstore`. Keep `application.properties` as the bootstrap/override fallback (read-at-startup) and make the entity values the runtime source of truth where they currently aren't. **No new dependency.**
- **BOM / starter coords are all already pinned:** Jmix BOM `2.8.1`, `spring-ai-bom:1.1.4` imported via `io.spring.dependency-management` (root `build.gradle:37-44`), and `ai-agent` already declares `spring-ai-client-chat`, `spring-ai-openai`, `spring-ai-model-chat-memory-repository-jdbc`, `spring-ai-starter-vector-store-pgvector`, `spring-ai-tika-document-reader`, `spring-ai-rag` (all `1.1.4`). Nothing new is required for v1.2 on the Spring AI side.

## Installation

**Recommended approach adds 0 lines to any `*.gradle` file.** New code only:
- `TranscriptionService` strategy interface + `SonioxTranscriptionService` (RestClient) + `SpringAiTranscriptionService` (manual `OpenAiAudioTranscriptionModel`); provider selected via `ai-agent.stt.provider=soniox|openai|<custom-bean-name>` (default `soniox`).
- A Spring `@RestController` STT upload endpoint (behind Jmix security) + the `STT_TRANSCRIPTION` audit write (`AuditWriter.writeToolCall(eventName="stt_transcription", ...)` — no new `AuditKind`; `AUD-06` already reserved the string).
- A `@JsModule` mic-recorder JS file + a small Flow `Component` mounted in `ChatPanelFragment`.
- `AiParameters` entity fields + a Liquibase changelog (`agentstore`) + Jmix view fields + locale messages (all bundles).
- New named Spring caches on the existing `ConcurrentMapCacheManager`; batched FK loads via `DataManager`.

```gradle
// OPTIONAL — ONLY if the team ever decides to use Spring Boot auto-config for the
// OpenAI transcription fallback instead of manually instantiating
// OpenAiAudioTranscriptionModel. NOT recommended — the project wires ChatModel
// manually for OpenRouter; stay consistent and wire transcription manually too.
// implementation 'org.springframework.ai:spring-ai-starter-model-openai:1.1.4'
```

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Manual `OpenAiAudioTranscriptionModel` wiring | `spring-ai-starter-model-openai` auto-config | Only if the project later switches to Spring AI auto-config across the board; today it wires `ChatModel` manually for OpenRouter — stay consistent. |
| Spring `RestClient` for Soniox | `WebClient` (reactive) | Only if the chat goes reactive end-to-end (it isn't). Sequential STT calls don't benefit. |
| Short polling `GET /v1/transcriptions/{id}` | Soniox `webhook_url` push | Only if a host already exposes a public HTTPS callback endpoint and wants push; otherwise polling needs no inbound route. |
| First-party `@JsModule` mic recorder | "Audio Recorder for Vaadin" community add-on | Never for this add-on — third-party add-on dep + maintenance risk; the JS wrapper is ~50 lines. |
| `ConcurrentMapCacheManager` (existing) | Caffeine | Only if a hotspot cache needs size/TTL eviction — none of the v1.2 targets do. |
| `ComboBox` + `allowCustomValue` for model picker | Two separate fields (curated `ComboBox` + free `TextField`) | Either works; pick whichever reads cleaner in the Jmix view — both zero-dep. |
| `stt-async-v4` literal model | `stt-async-v3` alias (points to v4) | Use the alias only if the host wants Soniox to manage version transitions transparently; default to the explicit `stt-async-v4` to pin behavior. |
| `gpt-4o-mini-transcribe` (OpenAI fallback default) | `whisper-1` | `whisper-1` if the host prefers the older general-purpose model or has a budget/contract reason; `gpt-4o-mini-transcribe` is cheaper/faster for short dictation. |

## What NOT to Use / NOT to Add

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| A "Soniox Java SDK" dependency | **None exists** — Soniox ships Python/Node/Web/React SDKs only | Spring `RestClient` against `https://api.soniox.com/v1` |
| `spring-ai-starter-model-openai` (just for STT) | Project wires `ChatModel` manually for OpenRouter; auto-config inconsistency + pulls more than needed | Manual `new OpenAiAudioApi(...)` + `new OpenAiAudioTranscriptionModel(...)` in the add-on auto-config |
| OkHttp / Apache HttpClient / Retrofit / Feign / `WebClient` for Soniox | `RestClient` already covers multipart + JSON + DELETE; STT is non-reactive request/response | `RestClient` (wraps JDK `HttpClient`) |
| Caffeine (`com.github.ben-manes.caffeine:caffeine`) | v1.2 perf-pass caches are bounded & lifetime-stable; no eviction policy needed | Existing `spring-boot-starter-cache` + `ConcurrentMapCacheManager`, or plain `ConcurrentHashMap` memoization |
| Any benchmark harness (JMH, gatling, custom perf rig, Micrometer-perf wiring) | Explicitly **out of scope** for the v1.2 perf pass | Targeted refactors; reuse test-scoped `datasource-proxy` to assert N+1 → 1 in tests |
| Routing Soniox/OpenAI transcription through OpenRouter | OpenRouter does **not** proxy `/v1/audio/transcriptions`; Soniox is its own API | Direct calls with **independent** keys (`ai-agent.stt.soniox.api-key`, `ai-agent.stt.openai.api-key`) |
| Client-side audio transcoding (ffmpeg.wasm, etc.) | Soniox + OpenAI both accept `webm/opus` and `mp4` directly | Send `MediaRecorder` output bytes as-is |
| "Audio Recorder for Vaadin" / `Vcamera` directory add-ons | Third-party add-on dependency + maintenance burden; conflicts with Jmix-first / no-new-deps posture | First-party `@JsModule` wrapping `MediaRecorder` + a Spring `@RestController` |
| Vaadin `Upload` receiver streaming for the audio blob | `Upload.getReceiver/setReceiver` is deprecated-for-removal (Vaadin 24.8 — project memory `feedback_jmix_upload_receiver_deprecated`); also routes bytes through the push channel | A dedicated `@RestController` `multipart/form-data` endpoint behind Jmix security |
| A new `AuditKind` for STT | `AUD-06` (v1.1) already reserved `STT_TRANSCRIPTION` as an `eventName` string | `AuditWriter.writeToolCall(eventName="stt_transcription", ...)` with SHA-256 transcript hash by default |
| A new vector-store abstraction / changes to RAG `VectorStore` wiring | RAG-retrieval perf comes from caching filter/metadata construction, not from swapping the store; `TEST-16` (KB untouched) must stay green | Optimize `RetrievalAugmentationAdvisor` filter building in place; leave `PgVectorStore` wiring alone |
| `TranscriptionPostProcessor` / custom-STT-provider SPI scaffolding | Trimmed out of v1.2 scope (PROJECT.md "Deferred"); only `provider` bean-name selection ships | Plain `ai-agent.stt.provider=<bean-name>` resolution; defer the SPI until a real host need appears |
| Raw Vaadin / programmatic Java UI for the new chat panels | Project memory `feedback_jmix_first_ui` — Jmix XML descriptors + Jmix components by default | Jmix view descriptors + Jmix Flow components inside `ChatPanelFragment` |

## Stack Patterns by Variant

**If the host wants Soniox push delivery instead of polling:**
- Add a small inbound HTTPS `@RestController` webhook + set `webhook_url` / `webhook_auth_header_*` on `POST /v1/transcriptions`.
- Because: avoids the polling loop; only viable if the host's deployment is internet-reachable.

**If the host only has an OpenAI key (no Soniox):**
- Set `ai-agent.stt.provider=openai`; `SpringAiTranscriptionService` uses `OpenAiAudioTranscriptionModel`.
- Because: OpenRouter can't proxy transcription — needs the OpenAI key directly. Default `model` `gpt-4o-mini-transcribe`.

**If the host registers a custom STT bean:**
- `ai-agent.stt.provider=<bean-name>`; the add-on resolves a host `TranscriptionService` bean by name.
- Because: keeps the SPI surface minimal (the full `TranscriptionPostProcessor`/custom-provider *SPI* is deferred, but bean-name selection costs nothing).

**If a perf-pass cache turns out to need eviction (unlikely):**
- Revisit Caffeine then — not now.
- Because: `ConcurrentMapCacheManager` has no eviction; only adopt Caffeine when a measured need (size/TTL) exists.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| `spring-ai-openai:1.1.4` | Spring Boot 3.x / Spring Framework 6.x (Jmix 2.8.1 BOM, Java 21) | Already proven on this classpath; audio-transcription classes included. |
| Spring `RestClient` | Spring Framework 6.1+ | Present via Boot 3.x; `MockRestServiceServer` supports `RestClient.Builder` for tests. |
| `MediaRecorder` `audio/webm;codecs=opus` | Chrome/Edge/Firefox | Safari needs `audio/mp4` fallback — feature-detect with `MediaRecorder.isTypeSupported`. Secure context required (`localhost` or https). |
| Soniox `stt-async-v4` | Soniox API v1 (current, 2026-05) | `stt-async-v3` alias points to v4; literal `stt-async-v3` removed 2026-02-28. Max audio 5h/request. |
| OpenAI transcription `base-url` override | `spring.ai.openai.audio.transcription.base-url` | Works, but irrelevant here — call OpenAI directly, not via OpenRouter. |
| `spring-ai-bom:1.1.4` | All `spring-ai-*` modules used | Imported via `io.spring.dependency-management` at root `build.gradle:37-44`; downstream consumers get explicit versions on each `ai-agent` dependency line. |

## Sources

- `/websites/spring_io_spring-ai_reference` (Context7) — OpenAI + Azure OpenAI transcription API, config properties, `OpenAiAudioTranscriptionModel` / `AudioTranscriptionPrompt` / `AudioTranscriptionOptions` / `AudioTranscriptionModel` usage — HIGH
- https://docs.spring.io/spring-ai/reference/api/audio/transcriptions/openai-transcriptions.html — full `spring.ai.openai.audio.transcription.*` property set, supported models (`whisper-1` / `gpt-4o-transcribe` / `gpt-4o-mini-transcribe`), `AudioTranscriptionModel` interface, custom `base-url` — HIGH
- https://soniox.com/docs/stt/async/async-transcription — async flow: `POST /v1/files` (field `file`), `POST /v1/transcriptions` (`file_id`, `model`, `language_hints`, webhooks), `GET /v1/transcriptions/{id}` status, `GET /v1/transcriptions/{id}/transcript` (tokens), `DELETE` cleanup, supported formats (webm/mp4), 5h max — HIGH
- https://soniox.com/docs/api-reference and https://soniox.com/docs/api-reference/stt/transcriptions/create_transcription — base URL `https://api.soniox.com/v1`, full create-transcription request/response spec, error codes, no Java SDK — HIGH
- https://soniox.com/docs/stt/models — `stt-async-v4` is the current async model (2026-01-29); `stt-async-v3` alias → v4; v3 literal removed 2026-02-28; 5h max — HIGH
- https://github.com/soniox + https://soniox.com/docs/sdk/* + https://soniox.com/blog/new-soniox-sdks — official SDKs: Python, Node, Web, React, React Native (no Java) — HIGH
- Vaadin Directory ("Audio Recorder for Vaadin", "Vcamera"), MDN `MediaRecorder` / `getUserMedia` — context for the JsModule decision (community add-ons evaluated and rejected) — MEDIUM
- Repo build files: root `build.gradle` (`springAiVersion=1.1.4`, `spring-ai-bom`, Jmix BOM `2.8.1`, Java 21 toolchain), `ai-agent/ai-agent/ai-agent.gradle` (`spring-ai-openai:1.1.4` @ line 29, `spring-ai-client-chat:1.1.4`, `spring-ai-starter-vector-store-pgvector:1.1.4`, `spring-ai-rag:1.1.4`, `spring-boot-starter-cache` @ line 70, `jackson-dataformat-yaml` @ line 62, `jakarta.validation-api` @ line 63, `datasource-proxy:1.11.0` test scope @ line 111, `spring-ai-test:1.1.4` @ line 118), `jmix-app/build.gradle` (`spring-boot-starter-web` @ line 60) — HIGH
- Project memory: `feedback_jmix_upload_receiver_deprecated` (Vaadin `Upload.getReceiver/setReceiver` forRemoval), `project_self_hostable_models_only`, `feedback_jmix_loadvalue_store` (`agentstore`), `feedback_jmix_first_ui`, `feedback_jmix_unconstrained_for_system_writes`, `project_local_dev_port` (8088) — HIGH
- `.planning/PROJECT.md`, `.planning/ROADMAP.md` (Backlog → Phase 999.2 / 999.1), `.planning/MILESTONES.md` — milestone scope, existing capabilities, cross-cutting STT constraints — HIGH

---
*Stack research for: Jmix AI agent add-on — v1.2 (Operator Experience, Voice Input & Runtime Performance)*
*Researched: 2026-05-11*
