# Feature Research — Jmix AI Agent v1.2

**Domain:** Enterprise embedded AI agent add-on — v1.2 increment (operator experience, voice input, runtime perf) for a shipped Jmix 2.8 + Spring AI 1.1.x + Vaadin Flow + pgvector agent
**Researched:** 2026-05-11
**Confidence:** MEDIUM-HIGH — UX conventions for voice input / tool-call transparency are well-established (HIGH); config-knob taxonomy and "done" criteria for invisible phases are project-specific judgment grounded in the existing codebase (MEDIUM)

> Supersedes the v1.1.0 feature landscape (which covered prompt-hardening, mutation tools, exposure policy, task files, intent extraction, chat surfaces — all now shipped). The v1.1.0 version is recoverable from git history if needed.

---

## Scope note

This is a **subsequent milestone**. The chat surfaces (`FULL_ROUTE` `ChatView` + `HEADER_BUTTON` `ChatDialogView` over one `ChatPanelFragment`), conversations list with async auto-titling, admin Parameters view (chat-model code currently a free-text param), KB ingestion/retrieval, exposure-rule admin UI, vector-store debug view, audit tree, opt-in mutation tools, chat task files (CRM-style right-pane + per-turn-all `Media` injection + LRU token-budget cap), intent-driven extraction → prefilled Jmix forms, and the default model (`qwen/qwen3.6-35b-a3b`) all already exist — they are NOT re-researched here. Each feature below is scoped only to the *new behaviour* and explicitly names the existing add-on component it plugs into.

---

## Category 1 — Chat Voice Input (Soniox STT + OpenAI fallback)

**Plugs into:** `ChatPanelFragment.messageInputSlot` (stable since v1.0, contract frozen by Phase 12); `MessageInput`/`MessageList` Vaadin components; `AuditWriter.writeToolCall` with the already-reserved `STT_TRANSCRIPTION` / `stt_transcription` event string (no new `AuditKind`); the `AiParameters` admin store for the enable/provider/key/audit-mode knobs (overlaps Category 4). New `TranscriptionService` strategy interface + `SonioxTranscriptionService` (`RestClient`, no Java SDK) + optional `SpringAiTranscriptionService` (OpenAI-direct). No coupling to RAG, mutations, exposure policy, or intent extraction.

### Table Stakes (must-have for the feature to be worth shipping)

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| **Mic button in the chat input area, off by default at the add-on level** | Voice is universally a discoverable icon next to send; gated behind `ai-agent.stt.enabled=true` because most hosts won't configure a Soniox key | LOW | Button renders only when STT enabled AND a provider key configured; otherwise hidden, not greyed |
| **Tap-to-start / tap-to-stop recording (toggle), with a hard ~60s cap** | This is dictation, not walkie-talkie — "speak a sentence into a text box" is click-to-start, click-to-stop (ChatGPT, Intercom, Google Chat voice messages). Press-and-hold is for real-time voice channels (Discord/TeamSpeak), not transcription-to-textbox. Max-duration cap prevents runaway uploads and Soniox cost | LOW-MED | Browser `MediaRecorder` (webm/opus or mp4 — no transcoding); visible countdown + auto-stop near 60s; user may stop early |
| **Visible "recording" state then "transcribing…" state** | User must know the mic is hot (privacy) and that work is happening after stop. Two distinct visual states: live recording (timer/pulse) → spinner/"transcribing…" during the server round-trip | LOW | Can reuse the Category 2c ephemeral-status slot if convenient |
| **Transcript lands in `MessageInput` for review/edit — NEVER auto-sent** | The single most-cited usability regression in voice-dictation products is "it sent before I could fix the transcription" (OpenAI community thread). Enterprise users dictating against business data MUST get a review beat. `TranscriptionService` does not call `ChatService.ask` | LOW | Drop text into the input box, cursor at end; user edits then presses send normally. Hard architectural rule |
| **Non-blocking error + retry on transcription failure** | Provider 4xx, network drop, recording-too-long, empty audio — none should break the chat. Inline dismissible error + a "retry" affordance in the input area; chat stays usable | LOW-MED | Distinguish "no speech detected" (friendly) / "service error" (retry) / "recording too long" (try shorter) |
| **Privacy-safe audit by default (hash, not raw text)** | Dictated content is often more sensitive than typed content. Default `STT_TRANSCRIPTION` row stores SHA-256 of the transcript + duration + language + model + outcome — NOT the words. `ai-agent.stt.audit.storeTranscript=true` opt-in stores raw text | LOW | Same `AuditWriter.writeToolCall` path as every other audited action; the choice is what's in the payload field. (Per MEMORY: audit entities under agentstore — mind `.store("agentstore")` for raw JPQL.) |
| **Soniox default path + OpenAI-direct fallback, provider-selectable** | `ai-agent.stt.provider=soniox\|openai\|<custom-bean>`, default `soniox`. Soniox = `POST /v1/files` → `POST /v1/transcriptions` (`model=stt-async-v4`, `language_hints:["vi","en"]`) → poll → `DELETE` both. OpenAI path uses Spring AI's transcription primitive against the OpenAI key **directly** (OpenRouter does not proxy `/audio/transcriptions`) | MED | Soniox needs file-upload→async-job→poll→cleanup; OpenAI is a single multipart call. Both behind the strategy interface |
| **Language hints for VI + EN (or host-configurable)** | The agent is bilingual VI/EN (locale bundles, prompt-contract tests) — STT must not be English-only | LOW | `language_hints` for Soniox; `language` param for OpenAI |

### Differentiators (set this apart, not required)

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| **Resource cleanup on Soniox after retrieval** | `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` so the host's Soniox account doesn't accumulate dictation artifacts | LOW | Best-effort, non-fatal on failure |
| **Keyboard shortcut to start/stop dictation** | Power-user nicety; matches the LibreChat feature request | LOW | Optional; only if it fits the Vaadin input layout cleanly |
| **`partial` / live-interim transcript display** | Some STT APIs stream interim words; feels responsive | HIGH | Anti-feature risk — Soniox async-v4 is batch, not streaming; don't build a streaming path for a non-streaming API |

### Anti-Features (commonly requested, problematic here)

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| **Auto-send the transcript** | "Faster, hands-free" | Transcription errors against business data → wrong query / wrong mutation intent; the #1 reported regression in this space | Always review-before-send; non-negotiable |
| **`TranscriptionPostProcessor` SPI now** | "Hosts will want to rewrite transcripts" | No host has asked; speculative SPI surface. Already explicitly deferred in PROJECT.md | Ship the disjoint pipeline; add the SPI when a real host need appears |
| **Custom-STT-provider SPI beyond bean-name selection now** | "Generality" | `ai-agent.stt.provider=<bean>` already covers "register your own `TranscriptionService`"; a richer SPI is over-engineering | Strategy interface + bean-name selection is enough |
| **Audio transcoding (browser or server)** | "Normalize to wav/mp3" | Both Soniox and OpenAI accept webm/opus and mp4 directly; transcoding adds an ffmpeg-class dependency for zero benefit | Send the `MediaRecorder` blob as-is |
| **Voice *output* (TTS — read the answer aloud)** | "Full voice loop" | Not in the brief; large new surface (audio player, barge-in, locale voices); no demand signal | Out of scope; revisit only if asked |
| **Transcribe whole conversations / meeting-mode** | "It's an STT system, why not" | This is dictation into a textbox, not a transcription product; 60s cap is the deliberate boundary | Keep the 60s single-utterance scope |

---

## Category 2 — Chat UX & Observability (chat-state side panel · per-turn tool-detail · ephemeral streaming status)

**Plugs into:** `ChatPanelFragment` (must work in both the full-route and the header-button dialog surfaces, or be the same fragment slot); the existing tree-lite `AiAuditEvent` rows (one root `KIND=CHAT` per turn + N children), read scoped to current conversation + current user via `DataManager` (display projection, not new persistence); `AiChatSessionState` for cross-surface continuity; the existing audit list view (`AiAuditEventListView?runId=...` deep-link target) reached via Jmix `ViewNavigators` + `View.QueryParametersChangeEvent` (per MEMORY); the active model code (Category 3 output) + exposure-policy active flag (Phase 10) + task-file count + LRU token-budget bookkeeping (Phase 13.1) + mutation-enabled flag (Phase 11) + conversation id/title (Phase 12). Resolves the pending `add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo.

### 2a. Operator-facing chat-state side panel — what it should contain

An operator-facing "what is this chat session currently doing/configured as" panel surfaces **stable session facts** an operator needs to reason about a turn — not a metrics dashboard. Useful set, mapped to what this add-on already tracks:

| Panel item | Include? | Source (already exists) | Rationale |
|---|---|---|---|
| **Active chat model code** | YES | `AiParameters` chat-model param | The most-asked "which model answered me"; pairs with Category 3's picker |
| **Conversation id + title** | YES | `AiConversation` (async auto-titled) | Correlate with the conversations list and the audit tree |
| **Exposure-policy active flag** | YES | `LlmExposurePolicy` — is any `AiExposureRule EXCLUDE` in effect | Governance transparency — "is the AI seeing a narrowed surface right now"; boolean, not the rule list |
| **Attached-file count (+ which)** | YES | Phase 13.1 right-pane state | The CRM-style pane already exists; the chat-state panel needs the count + maybe names; ties to per-turn-all injection |
| **Token-budget usage (attachments)** | YES (compact) | Phase 13.1 LRU cap + `task_file_budget_exceeded` audit | "X of Y token budget used, Z files dropped this turn" — explains why an older file stopped being seen |
| **Mutation-tools enabled flag** | YES | `ai-agent.tools.mutation.enabled` + `AiAgentMutationRole` on the current user | Operator wants to know if the AI *can* write right now; boolean |
| **Recent tool-call summary (last turn: N tools, total ms)** | YES (header line only) | tree-lite `AiAuditEvent` root for the last `runId` | The bridge into 2b; one-line "last turn: 3 steps, 1.2s"; detail lives in 2b |
| Live token/latency charts, cost meters, request/response dumps | NO | — | Overkill — that's an observability product; the audit tree already holds the raw detail |
| Raw system prompt / full context window | NO | — | Leaks internals; the Phase 9 prompt-hardening deliberately keeps internals out of user-facing surfaces |
| Per-user persisted panel preferences | NO | — | Session-scoped expand/collapse only; per-user prefs are over-engineering |

### 2b. Collapsible per-turn tool-detail panel — useful vs noisy

| Detail level | Include? | Rationale |
|---|---|---|
| Collapsed-by-default header per assistant turn: "▶ what the AI did (N steps, total ms)" | YES | Drill-down on demand, zero noise when collapsed; **hide the header entirely if the turn had zero tool calls** (don't show "0 steps") |
| Expanded: ordered list of steps with **human-readable labels** + per-step ms | YES | Label-only — "looked up entity list (3 ms)", "searched customers in Hanoi (37 ms)", "retrieved related documents (980 ms)"; **never** print `list_entities` / internal entity names (consistent with the Phase 9 leak-guard contract — prompt side already suppresses leaks; this is the UI side) |
| Tool **arguments** in the expanded view | OPTIONAL / behind a second expand | Useful for "why did it query that"; risky for noise + leaking entity/attribute names. If shown: redacted/summarized, second-level expand, never for mutation args containing PII (reuse `MutationErrorTranslator`-style scrubbing) |
| Tool **results** shown inline | NO (link out instead) | Results can be large and contain business data already in the bubble; show a "view in audit log →" deep-link instead of duplicating |
| Error/rollback indication on a failed step | YES | Operators need to see a turn was partial / a mutation rolled back |
| Per-turn vs single conversation-wide audit panel | **Per-turn** | Matches user intuition ("what did *this answer* do"); the conversation-wide view already exists as the audit list view — link to it, don't rebuild it |

### 2c. Ephemeral streaming-status indicator — useful vs noisy

| Behaviour | Include? | Rationale |
|---|---|---|
| Status line in a **sibling slot** (not inside the message bubble) while the turn is in flight: "thinking…", "searching data…", "retrieving documents…" | YES | Fixes the real bug from the todo: today intermediate model text like "Để tôi tìm kiếm…" gets concatenated into the final bubble and stays there forever. Status must live *outside* the bubble |
| Status text keyed by the currently-executing audit `KIND` (CHAT/TOOL/RETRIEVAL/MUTATION), i18n'd in all locale bundles, **never naming the tool** | YES | Same leak-guard discipline; "calling describe_entity…" → "looking up details…" |
| Status component **clears completely** when the turn finalizes — final bubble = reply text only | YES | The core contract; no status residue |
| Fallback generic typing indicator (dots) when no `KIND` is identifiable yet | YES | Covers the pre-first-tool window and pure-LLM turns |
| Verbose step-by-step running log in the status slot | NO | That's what 2b is for, after the fact; the live indicator is one ephemeral line |
| Token-by-token streaming text rendering changes | OUT OF SCOPE | The brief is about *status*, not re-architecting token streaming |

### Table Stakes (Category 2 as a whole)

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Ephemeral status in a sibling slot that clears on completion | Production AI agents all separate "working…" from the answer; the current concatenation behaviour is a visible defect | MED | Controller must know which `KIND` is executing — via `AuditAdvisor` event publish or a session-scoped `Phase` holder; works for both chat surfaces |
| Collapsed-by-default per-turn "what the AI did" with label-only steps + timing | Power users currently leave chat, open the audit list, find the `runId` manually — high friction; table stakes for a agent that *has* an audit tree | MED | Reads existing tree-lite rows; hide when empty; i18n per `KIND` |
| Chat-state side panel with the model / conversation / exposure / mutation / attachment-budget facts | Operators embedding this in an enterprise app expect to see "what is this session configured as" without spelunking | MED | All data already exists; layout + binding in `ChatPanelFragment`; must render in full-route and dialog surfaces |
| All new labels via `msg://` in ALL locale bundles | Project rule (CLAUDE.md) | LOW | VI + EN copy for every step-kind label, status string, panel field |

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Deep-link from a turn's tool-detail into the filtered audit list (`?runId=`) | One click from "this answer did 3 things" → the full forensic tree | LOW | `ViewNavigators` + query param; the audit list view + `QueryParametersChangeEvent` reading already exist |
| Second-level expand for (scrubbed) tool arguments | Real debugging power for "why did it pick that filter" | MED | Only worth it if scrubbing is solid; otherwise skip |
| Panel collapse/expand state remembered per session | Small ergonomic win | LOW | Session-scoped only, no per-user persistence |

### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| Showing tool/entity/attribute internal names in the UI | "Be transparent" | Contradicts the Phase 9 leak-guard contract; confuses end-users; leaks schema | Human-readable labels keyed by `KIND` |
| Inline tool *results* in the turn panel | "See everything in one place" | Duplicates data already in the bubble; can be huge; can re-expose narrowed data | Link to the audit tree |
| A metrics/cost dashboard inside the chat panel | "Observability!" | Scope explosion; that's a separate admin concern; the audit tree owns metrics | Keep the chat-state panel to session *facts*, not charts |
| Persisting per-user UI preferences for the panels | "Respect my layout" | Over-engineering; new persistence + settings UI | Session-scoped state |
| Surfacing the raw system prompt / context window | "Full transparency" | Leaks internals the whole prompt-hardening hides | Don't |

---

## Category 3 — Admin Model Management (curated dropdown + "custom…" free-entry, admin-only)

**Plugs into:** the existing admin **Parameters** view, where the chat-model code is *today a free-text parameter*. This feature replaces that free-text field with a combo control. Reads/writes `AiParameters` (agentstore-backed — note `.store("agentstore")` for raw JPQL per MEMORY). No per-conversation end-user model switching (explicitly admin-only; per-conversation switching is deferred). Interacts with the Category 2a panel (which displays the chosen model) and the OpenAI-compatible provider wiring (per-request model via `ChatOptions` — established pattern from the reference projects).

### Conventional pattern: "pick a known model OR type your own"

The standard control is a **combobox with a free-entry escape hatch** — either (a) an editable / `allowCustomValue` combobox where the dropdown lists curated slugs and the user can also type an arbitrary string, or (b) a select with a sentinel "Custom…" option that reveals a text field. In Vaadin/Jmix, pattern (a) is cleaner (Jmix `ComboBox` supports custom value entry; or `ComboBox` + a conditionally-visible `TextField`). Either way:

- The **curated list is a convenience, not a constraint** — anything the host routes to (OpenRouter slugs, a local vLLM model name, an internal alias) must remain enterable.
- Curated entries carry a short human label, not just the raw slug, so the dropdown is readable (e.g. "Qwen3.6 35B (A3B, multimodal, Apache-2.0) — default").
- Whatever is chosen is validated only at *use* time (the provider call), not at *save* time — don't block saving an unknown slug; surface the error when a turn fails.

### Sane default curated list under an open-weights-first policy

Per MEMORY (`project_self_hostable_models_only`): default recommendations are **open-weights / Apache-2.0-or-similar only**; Qwen3.6 Plus/Flash, GPT-4o, Claude, Gemini Pro are explicitly **excluded from the curated list** (reachable only via the custom-entry field). A sane curated set (exact slugs to confirm against current provider availability at planning time — the *policy* is the load-bearing part):

| Curated entry | Why it's in the default list |
|---|---|
| `qwen/qwen3.6-35b-a3b` (current default, multimodal, Apache-2.0) | The shipped default — first/marked-default; multimodal is required for the task-file read feature |
| A smaller Qwen open-weights variant (~7–8B class, Apache-2.0) | "Cheaper/faster, text-only" option for hosts that don't need multimodal |
| A Llama-family open-weights instruct model (as a *reference* slug, not a dep) | Common alternative many hosts already self-host |
| A Mistral/Mixtral open-weights instruct model (Apache-2.0) | Another widely self-hosted family |
| A DeepSeek open-weights instruct model | Strong open-weights option many enterprises run locally |
| **"Custom…"** sentinel / free-entry | The escape hatch — anything else, including proprietary models, OpenRouter slugs, local aliases |

### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| Combobox of curated open-weights model slugs with readable labels | Replacing a raw free-text field with a guided picker is the whole feature; an admin shouldn't have to remember exact slugs | LOW | Jmix `ComboBox` in the Parameters view; labels via `msg://` |
| "Custom…" free-entry escape hatch accepting any string | Hosts route to all kinds of models/aliases; the picker must never be a hard constraint | LOW | `allowCustomValue` combobox, or sentinel option + conditional `TextField` |
| Admin-only — no per-conversation end-user model switching | Governance + scope; per-conversation switching is deferred | LOW | Control lives in the admin Parameters view, gated by the existing admin role; nothing new in the chat surface |
| The chosen model is what the chat-state panel shows and what `ChatOptions` uses per request | Consistency between "what admin set" and "what's running" | LOW | Wire the param through to the existing per-request model selection |
| Curated list honours the open-weights-first policy; proprietary models only via custom entry | Project policy (MEMORY) | LOW | Don't ship Claude/GPT-4o/Gemini Pro in the dropdown |
| All labels via `msg://` in ALL locale bundles | Project rule | LOW | |

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| Inline note per curated entry ("multimodal", "Apache-2.0", "text-only", "default") | Helps an admin pick the right one — only multimodal models support the task-file read feature | LOW | Static metadata next to each slug |
| A "test this model" button in the Parameters view (fires a trivial turn, reports ok/fail) | Closes the loop on "is the slug + key valid before users hit it" | MED | Reuses the chat client with a throwaway prompt; nice-to-have |
| Warn (don't block) on saving a model the add-on can't confirm is multimodal when task-files are enabled | Prevents a confusing "files aren't being read" support ticket | MED | Heuristic only; advisory |

### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| Per-conversation / per-user model picker in the chat UI | "Let users choose" | Explicitly deferred; governance concern; complicates audit/cost attribution | Admin-only for now |
| Hard-validating the model slug at save time against a remote model list | "Catch typos early" | Couples the admin screen to a live provider call; breaks if the provider is down; brittle as model catalogs churn | Validate at use time; surface turn errors clearly |
| Shipping proprietary frontier models in the curated default list | "They're the best models" | Violates the self-hostable-open-weights policy; the add-on's default posture must be self-hostable | Open-weights curated; proprietary via custom entry only |
| Auto-fetching the provider's full model catalog into the dropdown | "Always up to date" | Provider-specific API, auth, rate limits, churn; over-engineering | Small hand-curated list + custom entry |
| Per-model parameter sets (temperature, max-tokens) tied to the picker | "Power tuning" | Scope creep into Category 4 territory; mixes concerns | Keep model selection and tuning knobs separate |

---

## Category 4 — Admin Config-Knob Migration (properties → editable admin UI)

**Plugs into:** the `AiParameters` agentstore entity + the admin Parameters/Settings view; the Spring `@ConfigurationProperties` / `application.properties` knobs introduced across Phases 9–14; the RAG advisor (top-k, similarity threshold), the Phase 13.1 task-file token budget / TTL, the Phase 12 chat surface mode (`AiUiSettings`), the Category 1 STT enable/provider/audit-mode knobs, the mutation-enabled `@ConditionalOnProperty` toggle. The migration must respect the existing pattern where some beans are conditionally created at boot (`@ConditionalOnProperty`) and therefore cannot be flipped at runtime.

### The right taxonomy

| Tier | Definition | Examples in this add-on | UI treatment |
|---|---|---|---|
| **Tier 1 — Runtime-editable → move to admin UI** | Read on each request / cheaply re-readable; changing it takes effect on the next turn with no restart; not security-load-bearing in a way that needs a deploy gate | RAG top-k; RAG similarity threshold; task-file token budget (the LRU cap); task-file TTL hours; conversation auto-title on/off; STT max recording seconds; STT language hints; STT audit mode (hash vs raw transcript — only affects future rows, fine to flip at runtime); chat surface mode (`FULL_ROUTE` vs `HEADER_BUTTON`) is already an `AiUiSettings` UI toggle so it's "UI-editable" already; data-driven output-scanner sensitivity if any | Editable field in the Parameters view, validated, takes effect next turn |
| **Tier 2 — Boot-time-only → stays a property, shown read-only / informational in the UI** | Gates whether a bean even exists (`@ConditionalOnProperty`), or wiring resolved once at startup; flipping at runtime is meaningless or unsafe | `ai-agent.tools.mutation.enabled` (it's `@ConditionalOnProperty` — the mutation tool beans aren't registered when false; the boot test asserts zero mutation callbacks under default config); `ai-agent.stt.enabled` *if* the `TranscriptionService` bean is `@ConditionalOnProperty` (likely yes — keep boot-time, show read-only); `ai-agent.stt.provider` if provider selection picks a bean at startup; vector-store/datasource wiring; whether the chat surface is mounted at all | Shown in the admin UI as a **read-only "current configuration" row with a "set in application.properties" note** — operators can *see* it, can't *change* it there; a flag/icon makes "read-only at runtime" explicit |
| **Tier 3 — Never UI-exposed** | Secrets and connection material | OpenAI/OpenRouter API key; Soniox API key; DB credentials; any bearer token | Not in the admin UI at all — not even read-only. At most a boolean "configured / not configured" indicator. Secrets stay in `application.properties` / env / a secrets manager |

**Categorization rule the roadmapper can apply:** *If a value is read fresh on each chat turn or each RAG call and changing it only affects future requests → Tier 1, move it. If it controls bean registration (`@ConditionalOnProperty`) or one-time wiring → Tier 2, show read-only with a "property only" note. If it's a credential → Tier 3, keep it out of the UI entirely, indicator-only.*

### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---|---|---|---|
| RAG top-k + similarity threshold editable in the admin UI | The most-tuned operator knobs in any RAG agent; editing `application.properties` + restart to tune retrieval is a real pain point | LOW-MED | Read from `AiParameters` on each retrieval; sensible bounds + validation |
| Task-file token budget + TTL editable in the admin UI | The LRU cap directly controls "why did the AI stop seeing my older attachment"; operators need to tune it without a restart | LOW | Already conversation-scoped bookkeeping; source the cap from `AiParameters` |
| Boot-time-only knobs (mutation toggle, STT enable if conditional) shown **read-only with a clear "property only / read-only at runtime" marker** | If an operator sees a settings screen, they expect to change things; the ones they *can't* must be visibly flagged, not silently absent or silently inert | LOW-MED | A distinct visual treatment (disabled field + helper text + icon); the safe answer for `@ConditionalOnProperty` beans |
| Secrets (API keys) never editable in the UI; at most a "configured: yes/no" indicator | API keys in a DB-backed settings entity is a security regression; secrets belong in properties/env | LOW | Indicator-only; never echo the key |
| A clear categorization decision so the roadmapper knows which knob goes where | The whole feature is "audit the knobs and place them correctly" | — | This taxonomy section is that input |
| All labels via `msg://` in ALL locale bundles | Project rule | LOW | |

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---|---|---|---|
| "Effective value" display when an editable knob also has a property default | Operators see "UI override: 8; property default: 5" — no confusion about precedence | MED | Requires a property-vs-UI precedence rule (UI wins when set) |
| Validation with sensible bounds + inline help per knob | Stops "top-k = 9999" foot-guns | LOW | Per-field min/max + helper text |
| Grouping the Parameters view into "Retrieval", "Chat", "Attachments", "Voice", "Governance (read-only)" sections | A flat list of 15 knobs is hostile; sections make the migration legible | LOW | Pure layout |

### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| Making `@ConditionalOnProperty` toggles (mutation-enabled, STT-enabled) live-editable | "Why can't I just flip it in the UI" | The beans don't exist when the property is false; a live toggle would lie or require dynamic context refresh — fragile and security-sensitive | Keep them properties; show read-only with a note |
| Moving API keys into `AiParameters` | "One place for all config" | Secrets in a DB-backed, DataManager-readable entity is an exfiltration surface; violates least-exposure | Properties/env/secrets-manager; indicator-only in the UI |
| A "reload config" / "refresh context" button that re-wires beans | "Apply boot-time changes without restart" | Spring context refresh in a running enterprise app is risky and out of scope | Restart for boot-time knobs; expected for `@ConditionalOnProperty` |
| Migrating *every* property indiscriminately | "Be thorough" | Boot/wiring/secret knobs don't belong in a runtime UI; indiscriminate migration creates footguns and security holes | Apply the three-tier taxonomy; migrate only Tier 1 |
| Per-environment / per-tenant config overrides in the UI | "Multi-tenant flexibility" | Big new concept (scoping, inheritance); not in the brief | Single set of admin params for the app instance |

---

## Category 5 — Phase 11 Mutation-Internals Hardening (internal; no visible behaviour change)

**Plugs into:** the Phase 11 mutation-tool internals only — the layered fail-closed gating chain (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save), the mutation-binding code that resolves to-one FK references, the related-write metadata resolution, the `bulk_save_records` extension of that chain (Phase 13). Touches no UI, no audit *content*, no error *content*, no security *outcomes* — those must be byte-for-byte identical. (Promotes ROADMAP Backlog Phase 999.1.)

### Table Stakes — "done" definition for an invisible refactor

| Property | Why it's the bar | Observable proxy that confirms it |
|---|---|---|
| **Byte-for-byte identical gating outcomes** | The whole value of the fail-closed chain is nothing slips through; a refactor must not move a single decision boundary | The full Phase 11 test suite (`TEST-10..13`) + the boot test asserting zero mutation callbacks under default config still pass, **unchanged**; add characterization tests around the de-duplicated gate sequencing if coverage is thin |
| **Identical audit rows** | Audit completeness is a contract; same `AuditKind`/`eventName`, same payload shape, same rollback rows | Audit-assertion tests pass unchanged; a before/after diff of audit rows for a fixed scenario is empty |
| **Identical error messages** | `MutationErrorTranslator` output is PII-safe and user-facing; wording/structure must not drift | Error-translation tests pass unchanged; before/after string diff empty |
| **Same idempotency behaviour** | `AiMutationIntent` dedup must still dedup the same way | Idempotency/replay tests pass unchanged |
| **De-duplicated gate sequencing has one canonical implementation** | The stated goal — remove the duplicated sequencing across `create`/`update`/`add_related`/`remove_related`/`bulk_save` | Code review confirms one shared sequencing path; line count down; cyclomatic complexity down |
| **Batch-loaded to-one FK references during binding** | Stated goal — kill the N+1 when binding multiple FK fields | A query-count assertion in a multi-FK mutation test shows fewer round-trips than before |
| **Cached related-write metadata resolution where safe** | Stated goal — don't re-resolve the same metadata per call | A metadata-resolution call-count assertion shows caching is in effect; cache invalidation tested if the metadata can change |

### Differentiators

| Feature | Value | Complexity | Notes |
|---|---|---|---|
| New characterization tests pinning the gating decision matrix | Future refactors stay safe | MED | Worth adding if the current suite tests behaviour but not the decision boundaries explicitly |
| A `*-VALIDATION.md` for Phase 11 as part of the Nyquist backfill | Closes a known doc gap while the code is touched | LOW | The milestone already flagged the 9/10/11/12/13/13.1 validation backfill |

### Anti-Features

| Feature | Why tempting | Why problematic | Alternative |
|---|---|---|---|
| "While we're in here" behaviour changes (new tools, new error wording, looser gating) | "Improve it" | Breaks the byte-for-byte contract; turns an invisible refactor into a risky feature change | Strict no-behaviour-change; file any improvement idea as a separate todo |
| Adding `delete_record` | "We have mutation infra now" | `delete_record` is deliberately reserved; not this phase | Stays reserved |
| Speculative caching of things that *can* change (exposure rules, ACLs) | "More caching = faster" | Stale security/exposure decisions = a real vulnerability | Cache only provably-stable metadata; if in doubt, don't |

---

## Category 6 — AI-Runtime Performance Pass (targeted hotspots; no visible behaviour change)

**Plugs into (the named hotspot surfaces):** chat turn execution (the ChatClient call path / advisor chain); tool-call dispatch; mutation binding/save flow (overlaps Category 5); media/attachment injection (the Phase 13.1 per-turn-all `Media` + LRU budget path); RAG retrieval / filter building (role-scoped filter construction, pgvector query); prompt/context construction (the `agent.entities`/`agent.permissions` baseline blocks, `describe_entity` text, record envelope formatting); repeated metadata / security / exposure-policy resolution (Jmix `Metadata`/`MetadataTools` lookups, `AccessManager` checks, `LlmExposurePolicy` evaluation). **Out of scope (anti-features, stated):** a benchmark harness; admin-screen performance.

### Table Stakes — "done" definition for an invisible perf pass

| Property | Why it's the bar | Observable proxy that confirms it |
|---|---|---|
| **No regression in security/exposure enforcement** | Performance must never cost a narrowed surface widening or an ACL check being skipped | The Phase 9/10/11 security + exposure test suites (`TEST-08/09/10..13`, `unknown_entity` opacity tests, RAG role-scoped filter tests) pass **unchanged** |
| **No regression in audit completeness** | Same rows, same kinds, same parent/child tree shape | Audit-assertion tests pass unchanged; before/after audit-row diff for fixed scenarios is empty |
| **No regression in tool / RAG correctness** | Same tool outputs, same retrieval results for the same inputs | Tool-contract + RAG retrieval tests pass unchanged |
| **Measurable reduction in the targeted hotspot's work** | "Targeted" means each change is justified by a specific hotspot | Per-hotspot micro-measurements / call-count or query-count assertions: "metadata lookups per turn N→M", "RAG filter built once per turn not per-doc", "media re-encoded once not per-injection", "exposure policy evaluated once per turn not per-tool-call". A lightweight repeatable timing in a test (NOT a full harness) showing turn latency down for a representative scenario is acceptable; a benchmark *product* is not |
| **No new flakiness** | Caching/memoization can introduce ordering/staleness bugs | Full suite green across repeated runs; any new cache has an invalidation test |
| **No new dependencies, no API/contract changes** | It's an internal pass | Dependency diff empty; public/SPI surface unchanged |

### Suggested concrete proxies the roadmapper can turn into criteria

- "Metadata/security/exposure resolution is memoized per turn, not per tool-call" — assert call counts in a multi-tool-call turn test.
- "RAG filter is built once per retrieval, not per candidate document" — assert in a retrieval test.
- "Attachment `Media` is encoded once per turn and reused across the per-turn-all injection, not re-read per message" — assert read/encode count in a multi-attachment, multi-turn test.
- "Prompt/context construction does not re-serialize the entity inventory / `describe_entity` text it already built this turn" — assert build count.
- "Mutation binding batch-loads to-one FK references" — query-count assertion (shared with Category 5).
- "A single representative end-to-end scenario shows reduced wall-clock turn time before vs after" — recorded once, not maintained as a harness.

### Differentiators

| Feature | Value | Complexity | Notes |
|---|---|---|---|
| Per-turn timing breakdown emitted at DEBUG (advisor-level) | Lets operators/devs see where a slow turn went without a profiler | LOW-MED | Log-only, off by default; not the chat-state panel |
| A short "perf notes" doc recording what was optimized and the before/after proxy numbers | Institutional memory; future passes don't re-litigate | LOW | One markdown file, not a dashboard |

### Anti-Features

| Feature | Why tempting | Why problematic | Alternative |
|---|---|---|---|
| Building a benchmark harness | "Measure properly" | Explicitly out of scope; large effort; maintenance burden | Lightweight one-off measurements + call/query-count test assertions |
| Optimizing admin-screen performance | "It's all UI perf" | Explicitly out of scope (the pass is AI-runtime only) | Leave admin screens alone |
| Caching security/exposure decisions across turns | "Biggest win" | Stale ACL/exposure = vulnerability; the safe scope is *within* a turn | Per-turn memoization only |
| Trading correctness for speed (looser fetch plans, skipping checks, approximate retrieval) | "Faster" | Violates the no-regression-in-correctness/security/audit bar | Optimize the *how*, never the *what* |
| Adding a caching/perf dependency (Caffeine etc.) | "Standard tool" | "No new dependencies" is a project posture; Spring/JDK primitives suffice for per-turn memoization | Plain maps / Spring's existing cache abstraction if already on the classpath |

---

## Feature Dependencies

```
Category 1 (Voice input)
    └──integrates-into──> ChatPanelFragment.messageInputSlot   [exists, frozen v1.0/Phase 12]
    └──audits-via──────> AuditWriter.writeToolCall / STT_TRANSCRIPTION  [string reserved AUD-06 v1.1]
    └──config-knobs──────> AiParameters  ── shared with ──> Category 4
    └──may-reuse────────> Category 2c ephemeral-status slot (for "transcribing…")

Category 2 (UX & observability)
    ├── 2a chat-state panel ──reads──> active model (Category 3 output), exposure flag (Phase 10),
    │                                  task-file count + token budget (Phase 13.1), mutation flag (Phase 11),
    │                                  conversation id/title (Phase 12), last-turn audit summary (audit tree)
    ├── 2b per-turn tool-detail ──reads──> tree-lite AiAuditEvent rows  [exists]
    │                            ──deep-links-to──> AiAuditEventListView?runId=  [exists; QueryParametersChangeEvent]
    │                            ──MUST-honour──> Phase 9 leak-guard contract (label-only, no tool/entity names)
    └── 2c ephemeral status ──needs──> controller knows current audit KIND (AuditAdvisor event publish OR session Phase holder)
    All three ──render-in──> both ChatView (FULL_ROUTE) and ChatDialogView (HEADER_BUTTON)

Category 3 (Admin model picker)
    └──replaces──> the current free-text chat-model parameter in the Parameters view
    └──feeds──> Category 2a (panel shows the chosen model) and per-request ChatOptions  [pattern exists]
    └──constrained-by──> open-weights-first policy (MEMORY)

Category 4 (Config-knob migration)
    └──audits-knobs-from──> Phases 9–14 + Category 1 (STT knobs) + Phase 13.1 (token budget) + Phase 12 (surface mode)
    └──must-respect──> @ConditionalOnProperty pattern (mutation toggle, possibly STT enable) → boot-time tier
    └──excludes──> all secrets (API keys) → never-UI tier

Category 5 (Mutation-internals hardening)
    └──refactors──> Phase 11 gating chain + binding + bulk_save (Phase 13)
    └──shares──> the batch-load-FK proxy with Category 6
    └──pinned-by──> TEST-10..13 + the default-config zero-callback boot test (must stay green, unchanged)

Category 6 (Runtime perf pass)
    └──touches──> chat turn path, tool dispatch, mutation binding (overlap Cat 5), media injection (Phase 13.1),
    │             RAG retrieval/filter build, prompt/context build, metadata/security/exposure resolution
    └──pinned-by──> Phase 8/9/10/11 regression + security + audit suites (must stay green, unchanged)
    └──excludes──> benchmark harness, admin-screen perf

Conflicts / sequencing notes:
- Category 5 and Category 6 overlap on the mutation binding/save path → do Category 5's refactor first,
  then Category 6's perf measurement on the cleaned-up code (don't optimize duplicated code you're about to delete).
- Categories 1, 2, 3 all touch ChatPanelFragment / ChatView / the Parameters view → the original todo's
  advice stands: treat the chat-UI changes (1 + 2) as one coordinated UI phase rather than three layout rebuilds;
  Category 3 is a smaller, separable Parameters-view change.
- Category 4 depends on Categories 1 + 3 being scoped (it migrates their knobs) → sequence Category 4 after,
  or at least co-design the AiParameters schema once.
```

### Dependency Notes

- **Category 1 → ChatPanelFragment.messageInputSlot:** the slot is the deliberate integration point and has been stable since v1.0; voice input adds a mic affordance there and nothing else in the chat layout needs to move.
- **Category 1 ↔ Category 4 (AiParameters):** STT's enable/provider/audit-mode/max-seconds knobs are config knobs — design them into the `AiParameters` schema once, with Category 4, not twice.
- **Category 2a ← Categories 3, 10, 11, 12, 13.1:** the chat-state panel is almost entirely a *read* of state other components already own; the work is layout + binding, not new state.
- **Category 2b ← Phase 9 leak-guard contract:** the per-turn tool-detail UI MUST use human-readable, `KIND`-keyed labels — it is the UI-side counterpart of the prompt-side leak fix, and showing raw tool/entity names would re-open the leak the project closed.
- **Category 2c ← AuditAdvisor / session Phase holder:** the ephemeral status needs a signal for "which `KIND` is executing right now"; the cleanest source is publishing audit events as they happen or a session-scoped phase enum the controller updates.
- **Category 3 → Category 2a + ChatOptions:** the picker's output is what the panel displays and what the per-request model selection uses — one value, three readers.
- **Category 4 must respect `@ConditionalOnProperty`:** the mutation-enabled toggle (and likely the STT-enabled toggle) gate bean *registration*; they cannot become live UI toggles — they get a read-only "property only" treatment.
- **Category 5 before Category 6 on the mutation path:** refactor the duplicated gate sequencing and FK binding first, then measure perf on the result — otherwise the perf pass optimizes code that's about to be deleted.

---

## MVP Definition (for this v1.2 increment — "ship with" / "fast-follow" / "later")

### Ship with v1.2 (core of the increment)

- [ ] **Voice input — table-stakes set** (mic button gated by `stt.enabled` + key; toggle record w/ 60s cap; recording + transcribing states; transcript→`MessageInput`, never auto-send; non-blocking error+retry; hash-by-default audit / raw-transcript opt-in; Soniox default + OpenAI fallback selectable; VI/EN hints; Soniox resource cleanup) — the no-auto-send rule and the privacy-safe audit are non-negotiable.
- [ ] **UX & observability — table-stakes set** (ephemeral status in a sibling slot that clears on completion, `KIND`-keyed, no tool names; collapsed-by-default per-turn "what the AI did" with label-only steps + timing, hidden when empty; chat-state side panel with model / conversation id+title / exposure flag / mutation flag / attached-file count / token-budget usage / last-turn summary; deep-link from a turn to the filtered audit list) — resolves the long-pending todo; the ephemeral-status fix is the resolution of a visible defect.
- [ ] **Admin model picker** (curated open-weights combobox w/ readable labels + "custom…" free-entry; admin-only; chosen model flows to the chat-state panel + `ChatOptions`; validate at use-time not save-time) — small, high-leverage, replaces a raw free-text field.
- [ ] **Config-knob migration — table-stakes tier** (RAG top-k + similarity threshold + task-file token budget + TTL editable; boot-time `@ConditionalOnProperty` knobs shown read-only with a clear "property only" marker; secrets never in the UI, indicator-only; the three-tier taxonomy applied).
- [ ] **Phase 11 mutation-internals hardening** (de-dup gate sequencing → one canonical path; batch-load to-one FK refs; cache safe related-write metadata) with the byte-for-byte-identical bar enforced by the unchanged Phase 11 test suite + the default-config zero-callback boot test.
- [ ] **AI-runtime perf pass — targeted** (per-turn memoization of metadata/security/exposure resolution; RAG filter built once per retrieval; `Media` encoded once per turn; prompt/context not re-serialized within a turn; FK batch-load shared with the hardening phase) with the no-regression-in-security/audit/correctness bar enforced by the unchanged regression suites; **no benchmark harness**, **no admin-screen perf**.

### Fast-follow (v1.2.x or early v1.3 — add when triggered)

- [ ] Voice: keyboard shortcut to start/stop dictation — trigger: a power-user request.
- [ ] UX: second-level expand for scrubbed tool arguments — trigger: solid scrubbing exists and devs ask for it.
- [ ] Model picker: "test this model" button + "may not be multimodal" advisory warning — trigger: support tickets about invalid slugs / silently-not-read files.
- [ ] Config: "effective value" (UI override vs property default) display + grouped Parameters sections — trigger: the migrated knob count crosses ~8.
- [ ] Perf: DEBUG-level per-turn timing breakdown — trigger: a slow-turn investigation.

### Later / out of this increment (deferred, with the trigger)

- [ ] `TranscriptionPostProcessor` SPI + custom STT-provider SPI beyond bean-name selection — trigger: a real host need (already deferred in PROJECT.md).
- [ ] Per-conversation / per-user end-user model switching — trigger: a concrete demand + an audit/cost-attribution design.
- [ ] Voice output (TTS) — trigger: explicit request; large new surface.
- [ ] Admin-screen performance work — trigger: a measured admin-UI complaint; separate effort.
- [ ] Phase 10 re-verification + Nyquist `*-VALIDATION.md` backfill (phases 9/10/11/12/13/13.1) — deferred from v1.2 per the 2026-05-11 decision.
- [ ] PKG-05 / TEST-07 clean-consumer smoke — deferred; needs Testcontainers pgvector or a stub `VectorStore` boot mode.

---

## Feature Prioritization Matrix

| Feature | Operator/User Value | Implementation Cost | Priority |
|---|---|---|---|
| Voice: transcript→input, never auto-send + privacy-safe audit | HIGH | LOW-MED | P1 |
| Voice: Soniox + OpenAI-fallback selectable provider | MEDIUM | MED | P1 |
| UX: ephemeral status in a sibling slot, clears on completion (fixes the concatenation defect) | HIGH | MED | P1 |
| UX: collapsed per-turn "what the AI did", label-only, hidden when empty | HIGH | MED | P1 |
| UX: chat-state side panel (model / convo / exposure / mutation / attachments / budget / last-turn) | MEDIUM-HIGH | MED | P1 |
| Admin: curated open-weights model combobox + "custom…" entry | MEDIUM-HIGH | LOW | P1 |
| Config: RAG top-k + similarity threshold + task-file budget/TTL editable | HIGH | LOW-MED | P1 |
| Config: boot-time knobs shown read-only w/ "property only" marker; secrets indicator-only | HIGH (correctness/safety) | LOW-MED | P1 |
| Phase 11 mutation-internals hardening (byte-for-byte identical) | MEDIUM (debt) | MED | P1 |
| AI-runtime perf pass (targeted, no harness) | MEDIUM | MED-HIGH | P1 |
| UX: deep-link from a turn into the filtered audit list | MEDIUM | LOW | P2 |
| Voice: keyboard shortcut; Soniox resource cleanup | LOW-MED | LOW | P2 |
| Model picker: "test this model" button; multimodal advisory | LOW-MED | MED | P2 |
| Config: "effective value" display + grouped sections | MEDIUM | MED | P2 |
| Perf: DEBUG per-turn timing breakdown | LOW-MED | LOW-MED | P2 |
| UX: scrubbed tool-arguments second-level expand | LOW-MED | MED | P3 |
| TranscriptionPostProcessor / custom-provider SPIs; TTS; per-conversation model switch | LOW (no demand) | HIGH | P3 |

**Priority key:** P1 = ship with v1.2 · P2 = should-have, add when capacity allows · P3 = nice-to-have / explicitly deferred

---

## Competitor / convention reference (how comparable products do these)

| Feature | How comparable products do it | Our approach |
|---|---|---|
| Voice-to-textbox | ChatGPT (historically), Intercom Messenger voice transcription, Google Chat voice messages: tap-to-record, server transcribe, **show text for review before send** (ChatGPT's removal of the review step is widely cited as a usability regression — a lesson, not a model) | Tap-to-toggle, 60s cap, transcript→`MessageInput`, never auto-send — hard rule |
| Push-to-talk vs toggle | Press-and-hold (Discord/TeamSpeak) is for live voice *channels*; dictation-into-textbox products use **toggle** (click start / click stop) | Toggle |
| Tool-call transparency | Microsoft Copilot Studio / Business Central "glass-box" agent observability, CopilotKit AG-UI "render tool calls as they stream", GitHub Copilot function-calling UI: expose tools called + args + timing, on demand, expandable; production surfaces keep it **collapsed by default** | Collapsed-by-default per-turn panel, label-only steps + timing, deep-link to the full audit tree; args behind an optional second expand |
| Streaming status | CopilotKit "agent streams are tricky" — they separate transient status events from final content because mixing them leaves residue in the message; Production AI agents show an ephemeral "thinking… / calling X… / retrieving…" line that clears | Ephemeral status in a *sibling* slot, `KIND`-keyed, no tool names, clears on completion — exactly the fix the todo asks for |
| "Known model OR custom" picker | Standard: editable combobox / `allowCustomValue` with curated entries + free text, or a select with a "Custom…" sentinel revealing a text field; curated list is a convenience, not a constraint; validation at use-time | Jmix `ComboBox` with curated open-weights slugs (readable labels, default marked) + "custom…" entry; validate when a turn fails, not on save |
| Config: which knobs go in the UI | Mature admin tools split runtime-tunable settings (in the UI) from boot/wiring settings (properties, shown read-only if at all) and never put secrets in the settings store | Three-tier taxonomy: runtime-editable → UI; `@ConditionalOnProperty`/wiring → read-only "property only"; secrets → never (indicator-only) |
| "Done" for invisible refactors/perf | Characterization tests + before/after diffs + call/query-count assertions + a one-off representative timing — not a benchmark product | Exactly that; existing test suites stay green and unchanged; new pinning tests where coverage is thin |

---

## Sources

- [Discord — Voice Input Modes 101 (Push-to-Talk vs Voice Activated)](https://support.discord.com/hc/en-us/articles/211376518-Voice-Input-Modes-101-Push-to-Talk-Voice-Activated)
- [TeamSpeak — Push-To-Talk vs Voice Activity Detection](https://support.teamspeak.com/hc/en-us/articles/360002745898-What-is-the-difference-between-Push-To-Talk-and-Voice-Activity-Detection)
- [LibreChat — push-to-talk / keyboard shortcuts for voice prompting (issue #4807)](https://github.com/danny-avila/LibreChat/issues/4807)
- [OpenAI Developer Community — voice dictation no longer shows transcribed text before sending (usability regression)](https://community.openai.com/t/voice-dictation-no-longer-shows-transcribed-text-before-sending-major-usability-regression/1177339)
- [Intercom — using voice transcription in the Messenger (record, review, send)](https://www.intercom.com/help/en/articles/12098515-using-voice-transcription-in-the-messenger)
- [Google Workspace Updates — voice message transcriptions in Google Chat](https://workspaceupdates.googleblog.com/2024/09/voice-message-transcriptions-google-chat.html)
- [Microsoft — Agentic Tooling: making agent performance transparent and measurable (glass-box tool-call observability)](https://microsoft.github.io/mcscatblog/posts/response-analysis-copilot-tool/)
- [Microsoft Learn — Transparency Note: Developer Tools for Copilot in Business Central](https://learn.microsoft.com/en-us/dynamics365/business-central/dev-itpro/ai/transparency-note-dev-tools-for-copilot)
- [CopilotKit — Agent streams are tricky, here's how we got ours to make sense (separating status events from final content)](https://www.copilotkit.ai/blog/agent-streams-are-tricky-heres-how-we-got-ours-to-make-sense)
- [AG-UI — Tools concept (rendering tool calls / args / status in the UI)](https://docs.ag-ui.com/concepts/tools)
- [Visual Studio Blog — function calling enabled in GitHub Copilot (tool-call UI)](https://devblogs.microsoft.com/visualstudio/function-calling-is-now-enabled-in-github-copilot/)
- Project context (HIGH confidence — primary source for what already exists and what's in scope): `.planning/PROJECT.md`, `.planning/ROADMAP.md` (Backlog → Phase 999.1 / 999.2), `.planning/MILESTONES.md`, `.planning/todos/pending/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md`, `C:\Users\admin\.claude\projects\D--DTH-ai-agent-core\memory\MEMORY.md` (self-hostable-models-only, AI-as-Jmix-client, Jmix UI conventions)

---
*Feature research for: v1.2 increment of the Jmix AI agent add-on (operator experience · voice input · runtime performance)*
*Researched: 2026-05-11*
