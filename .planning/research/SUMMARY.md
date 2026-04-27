# Project Research Summary — v1.1.0

**Project:** Jmix AI Copilot (`ai-agent-core`)
**Domain:** Enterprise AI copilot add-on for Jmix 2.8 / Spring AI 1.1.4 / Vaadin Flow — v1.1.0 milestone
**Researched:** 2026-04-26
**Confidence:** HIGH (incremental research on a shipped v1.0 baseline; every claim grounded in existing source files, MEMORY rules, or Context7-verified library docs)

## Executive Summary

v1.1.0 is a **stack-stable, governance-first** milestone built on top of a working v1.0 read-only Copilot. STACK research confirms **zero new core dependencies**: Spring AI 1.1.4 already on the classpath supplies `OpenAiAudioTranscriptionModel` for STT, the `@Tool` + `ToolExecutionException` contract for mutations, and `BeanOutputConverter` + `chatClient.prompt().call().entity(Class)` for intent extraction. Vaadin Flow 24 (via Jmix 2.8.1) supplies the `Dialog(MODELESS, draggable)` floating launcher, the `slot="drawer-end"` sidebar, the `MediaRecorder`-via-`executeJs` browser audio path, and `ViewNavigators.detailView(...).withInitializer(...)` form prefill. The whole milestone is activations + new beans, not platform churn.

The **load-bearing thesis** of all four research files is the same: the LLM is just another Jmix client, and v1.1's job is to keep that thesis intact while widening the action surface. That requires three paired guarantees that must ship together — (1) an admin-governed `LlmExposurePolicy` that can only narrow below user permissions (never widen), (2) mutation tools that go through policy-checked `DataManager` (NEVER `UnconstrainedDataManager`) and pass per-attribute `EntityAttributeContext.canModify` before any save, and (3) an auditable, idempotent tool-call shape that reuses the existing `AuditWriter` REQUIRES_NEW tree and the `writeToolCall` event rather than inventing a new audit kind. Three configurable chat surfaces (full route / right-sidebar drawer / floating launcher), browser STT, and intent-driven extraction are technically simpler but carry their own subtle invariants — one `ChatPanelFragment`, one `ChatService`, one `AiConversation` per user-session across all surfaces; STT transcripts are PII and must be hash-only in audit by default; intent extraction returns a structured DTO that the chat *controller* (not the LLM) navigates with.

The **dominant risk** is concentrated in two places. First, mutation tools introduce four reinforcing pitfalls every prior-art enterprise copilot has stumbled on: silent default-on auto-config, `@Composition` cascade-deletes from a "merge children" tool param, "tool returned success but transaction rolled back," and JPA constraint exception messages echoing user-supplied PII into the LLM result string. Second, the exposure policy is monotonic-only by design (`EXCLUDE`-only rule type, `userVisible AND NOT excluded` boolean composition); any UI label or rule shape that admits an `ALLOW` form is a baseline-correctness regression. Both risks are mitigated by hard-ordering the build: tool-layer foundations + prompt-contract hardening → exposure policy → mutation tools.

## Key Findings

### Recommended Stack

No new core dependencies. v1.1 fits inside the v1.0 Spring AI 1.1.4 BOM, the Jmix 2.8.1 platform, and Vaadin Flow 24. Activations are config-property-only.

**Core technologies (all present, activated for v1.1):**
- **Spring AI 1.1.4 `OpenAiAudioTranscriptionModel`** — STT for voice input. Caveat: OpenRouter does NOT proxy `/audio/transcriptions`; hosts must point STT at OpenAI directly with a separate key.
- **Spring AI 1.1.4 `@Tool` + `ToolExecutionException` + `DefaultToolExecutionExceptionProcessor`** — mutation tools' structured error contract. `internalToolExecutionEnabled=false` is the documented HITL primitive but v1.1 prefers delegating confirmation to the Jmix UI flow.
- **Spring AI 1.1.4 `BeanOutputConverter` + `chatClient.prompt().call().entity(Class)`** — intent extraction binds JSON Schema-derived structured output to typed Java records.
- **Jmix 2.8.1 `ViewNavigators.detailView(...).newEntity().withInitializer(...).navigate()`** — controller-layer-only prefill seam.
- **Vaadin Flow 24 primitives** — `Dialog.setModality(MODELESS).setDraggable(true)`, `AppLayout slot="drawer-end"`, `Element.executeJs` + `MediaRecorder` + `UploadHandler.inMemory` (no transcoding — Whisper accepts `webm/opus` and `mp4` natively).

**Anti-list:** second `ChatClient`, second `ChatMemory` store, Web Speech API client-side STT, FFmpeg/JCodec, Hilla/Copilot dev modules (already excluded), `webkitSpeechRecognition`, new `RestController` family, ArchUnit rule for "no `DataManager.save` outside mutation tools," Spring Retry / Resilience4j on mutations.

Detailed: `.planning/research/STACK.md`.

### Expected Features

**Must have (table stakes):** entity inventory in baseline; hide internal names; richer `describe_entity`; `unknown_entity` retry contract; LLM permission inventory; mutation tools (create/update/related-write) gated by `AccessManager`, opt-in, audited; AI exposure denylist with admin Flow UI; STT input; task-scoped file attachment (separate from KB); three configurable surfaces over one `ChatPanelFragment`.

**Should have (differentiators):** host-override SPI for fetch plans; intent-driven extraction → prefilled Jmix form (highest novelty); per-turn audit visibility of resolved permission inventory; structured-error envelope.

**Defer (v1.2+ if scope shrinks):** intent-driven extraction (self-contained, can ship as v1.1.1); floating launcher (keep full + sidebar); host-override SPI.

**Hard anti-features:** auto-approving mutations without HITL; parallel ACL on top of `AccessManager`; speech-to-speech; KB ingestion of task-scoped attachments; multi-step autonomous mutation chains; per-screen contextual injection in launcher; free-form intent inference.

Detailed: `.planning/research/FEATURES.md`.

### Architecture Approach

v1.1 composes new beans inside the existing v1.0 orchestration spine — there is **no parallel pipeline**. `DefaultChatServiceImpl.ask` remains the single per-turn integration point; `ChatClientFactory` returns one cached `ChatClient`; `AgentToolCallbacks.forCurrentUser` remains the single tool-composition seam; `BaselineContextProvider.renderAsText` remains byte-deterministic; `AuditWriter` remains REQUIRES_NEW.

**Major components (new + extended):**
1. **`LlmExposurePolicy`** — wraps `CurrentUserSchemaAccess`; consumed by `BuiltInDataTools`, `BuiltInMutationTools`, `BaselineContextProvider`, AND `RetrievalFilterBuilder` (RAG cross-cut is non-obvious but mandatory).
2. **`AiExposureRule`** entity (`agentstore`, `EXCLUDE`-only) + admin Flow views.
3. **`BuiltInMutationTools`** — separate `@Component`, `@ConditionalOnProperty` opt-in, default OFF; layered gating (exposure → `CrudEntityContext` + per-attribute `canModify` → `MutationGuard` SPI → `@Transactional` `DataManager.save`).
4. **`AiUiSettings`** + `ChatSurfaceMounter` + `SidebarChatComponent` + `FloatingChatLauncher` + `AiChatSessionState` (`@VaadinSessionScope`) — three surfaces share one `ChatPanelFragment` and one active conversation tracker.
5. **`TranscriptionService`** + `AudioCaptureComponent` — STT transcribes server-side, injects text into `MessageInput`; never calls `ChatService.ask` directly.
6. **`AiExtractionDraft`** + `IntentExtractor` SPI + `ExtractionService` + `prepare_form_draft` `@Tool` — LLM produces typed DTO; chat UI renders confirm card; controller calls `ViewNavigators` after `accessManager.isPermitted(ViewContext)`.
7. **`ToolFetchPlanCustomizer`** SPI + `ToolResultPayloads` + `ToolEntityResolver` — richer allowlisted-DTO `describe_entity`; host-supplied fetch plans wrapped to enforce attribute-policy intersection.
8. **`BaselineContextProvider`** extended — `agent.entities` + `agent.permissions` keys sourced from `LlmExposurePolicy`, alphabetically ordered.
9. **`AuditWriter`** signature unchanged — mutations, intent extraction, STT all reuse `writeToolCall` with new `eventName` strings + new outcomes (`IDEMPOTENT_REPLAY`, `COMMIT_FAILED`).

Detailed: `.planning/research/ARCHITECTURE.md`.

### Critical Pitfalls

1. **Mutation tools bypass `AccessManager` because `DataManager.save` doesn't enforce attribute policies (P-1)** — symmetric API, asymmetric enforcement. Avoid: mandatory pre-flight `CrudEntityContext` + per-attribute `EntityAttributeContext.canModify`; fail-closed; "READ but not MODIFY → blocked write" integration test.
2. **Mutation auto-config default-on silently flips read-only to write on upgrade (P-2)** — Avoid: `@ConditionalOnProperty` at the bean level + boot-test asserting zero mutation callbacks under default config + CHANGELOG SAFETY section.
3. **Exposure policy widens via `ALLOW` rule shape (P-6)** — Avoid: `EXCLUDE`-only rule type (no `ALLOW` enum value); composition is `userVisible AND NOT excluded`, never OR; UI label "Hide from AI" / "Visible to AI."
4. **Mutation idempotency: LLM retries duplicate writes (P-3)** — Avoid: mandatory `idempotencyKey` (UUID) `@ToolParam` + server-side `AiMutationIntent` lookup + `IDEMPOTENT_REPLAY` audit outcome.
5. **Intent extraction lets LLM call `ViewNavigators` directly (P-17)** — Avoid: LLM gets NO `navigate` tool — only `prepare_form_draft` returning a structured intent DTO; controller renders confirm card; controller calls `ViewNavigators` after `accessManager.isPermitted(ViewContext)`; intent → view-id mapping is server-side allowlist.

Honorable mention: **baseline non-determinism (P-8)** — `Metadata.getSession().getClasses()` HashMap iteration breaks the v1.0 byte-deterministic prompt-cache invariant unless every inventory rendering sorts entities/attributes alphabetically and renders locale-sensitive labels outside the cache key.

Full set of 25 pitfalls in `.planning/research/PITFALLS.md`.

## Implications for Roadmap

All four research files converge on the same build order. ARCHITECTURE explicitly states the chain: tool-layer foundations + prompt-contract hardening → exposure policy → mutation tools. FEATURES affirms "exposure policy MUST land before mutation tools." PITFALLS maps each pitfall to its prevention phase identically.

### Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening

**Rationale:** Lowest risk, highest leverage. Pure additions; no new entity, no behavioral risk. Foundation for everything downstream.
**Delivers:** Richer `describe_entity` DTO; `agent.entities` + `agent.permissions` baseline keys; permission-inventory tool (only entities user can READ); `ToolFetchPlanCustomizer` SPI with security-intersection wrapper; `unknown_entity` retry contract; output-scanner pattern additions.
**Addresses:** Prompt-contract hardening; richer `describe_entity`; LLM permission inventory; host-override SPI.
**Avoids:** P-7, P-8, P-9, P-10, P-11, P-12.

### Phase 10: AI Exposure Policy

**Rationale:** Must precede mutation tools. Read-only path first.
**Delivers:** `AiExposureRule` (`EXCLUDE`-only) + `LlmExposurePolicy` boundary + admin views + `LlmExposureChangedEvent` cache invalidation. **Critical:** migrate `RetrievalFilterBuilder` to consult policy (RAG cross-cut).
**Implements:** ARCH components #1, #2.
**Avoids:** P-6, P-13, P-14.

### Phase 11: Mutation Tools

**Rationale:** Hard-depends on Phases 9 and 10. Default-OFF on ship.
**Delivers:** `BuiltInMutationTools` (`@ConditionalOnProperty` opt-in); `MutationGuard` SPI; mandatory `idempotencyKey` + `AiMutationIntent` dedup table; `AiAgentMutationProperties`; audit `eventName` extensions; `MutationErrorTranslator` for safe error codes (no PII echo); locale messages for denial in all locales.
**Implements:** ARCH component #3.
**Avoids:** P-1, P-2, P-3, P-4, P-5, P-22.

### Phase 12: Configurable Chat Surfaces

**Rationale:** Independent of exposure-policy/mutation chain. Sequential after to avoid interleaving UI refactor with security work.
**Delivers:** `AiUiSettings` (single-row); `SidebarChatComponent` (`AppLayout slot="drawer-end"`); `FloatingChatLauncher` (Vaadin overlay primitive — NOT raw CSS); `ChatSurfaceMounter`; `AiChatSessionState` (`@VaadinSessionScope`); admin runtime-toggle Flow view.
**Implements:** ARCH component #4.
**Avoids:** P-19, P-20, P-21.

### Phase 13: Chat Task Input (STT + Task-Scoped File)

**Rationale:** Independent. Lowest cross-feature coupling. STT does NOT call `ChatService.ask`; task files NEVER touch `VectorStore`.
**Delivers:** `AudioCaptureComponent`; `TranscriptionService` over `OpenAiAudioTranscriptionModel`; `AiAgentTranscriptionProperties` (default OFF); `TranscriptionPostProcessor` SPI; task-scoped file path injecting `Media` only; `ai.agent.audio.audit.storeTranscript=false` default with hash-only audit; `STT_TRANSCRIPTION` audit event.
**Implements:** ARCH component #5.
**Avoids:** P-15, P-16, P-23, P-24.

### Phase 14: Intent-Driven Extraction → Form Prefill

**Rationale:** Highest novelty + complexity. Depends on Phases 9 and 10. Hard-defer candidate if scope shrinks (can ship as v1.1.1).
**Delivers:** `AiExtractionDraft` entity (TTL purge); `IntentExtractor<T>` SPI + reference impl using `chatClient.prompt().call().entity(Class)`; `ExtractionService`; `ExtractionToolBridge` exposing `prepare_form_draft` `@Tool`; chat-UI response renderer for `{ "action": "open_form_with_draft", ... }`; controller-side `ViewNavigators` wired from confirm button after `ViewContext` permission check; intent → view-id allowlist; per-attribute `canModify` filter on prefill; `dataContext.validate()` pre-Save.
**Implements:** ARCH component #6.
**Avoids:** P-17, P-18, P-25.

### Phase Ordering Rationale

- **Hard chain 9 → 10 → 11.** Mutation tools require admin-narrowing layer (otherwise opt-in is binary host-wide) AND permission inventory. All three research files converge on this chain.
- **Soft sequence 12 → 13 → 14.** All independent of each other but all assume Phase 9. Linear order produces shippable intermediate states.
- **Cross-cutting: exposure policy must touch RAG (`RetrievalFilterBuilder`)**, not just baseline + tools — non-obvious cross-feature regression risk.
- **Cross-cutting: audit reuses `writeToolCall`**, not a new audit kind. Mutation, extraction, STT all share `AiAuditEvent` parent/child tree with new `eventName` strings.

### Research Flags

Phases needing deeper research (`/gsd-research-phase`):
- **Phase 11 (Mutation Tools):** Spring AI 1.1.x has no built-in tool-approval API; `internalToolExecutionEnabled=false` semantics + transaction interaction (P-4: success-before-flush) need a focused spike.
- **Phase 14 (Intent-Driven Extraction):** Per-intent SPI + metadata-driven default extractor vs SPI + reference impl only? Prefill via `DataContext.create(Customer.class)` + per-attribute `canModify` interaction needs a Jmix-specific spike.
- **Phase 12 (Configurable Surfaces):** Floating-launcher Vaadin overlay primitive choice + dialog-open listener for hide-on-modal — HIGH on shape, MEDIUM on exact Jmix 2.8.1 MainView hook.

Standard patterns (skip phase research):
- **Phase 9, 10, 13** — Patterns are mechanical; HIGH confidence.

### Convergence and Divergence

**Convergence (HIGH):**
- All four files name **exposure-before-mutation** as the hard order.
- All four reject `UnconstrainedDataManager` for mutation tools (audit/seed only).
- All four require ONE `ChatPanelFragment`, ONE `ChatService`, ONE `AiConversation` per user-session.
- STACK and PITFALLS reject Web Speech API for server-side Whisper.
- ARCHITECTURE and PITFALLS require RAG to consult exposure policy.
- ARCHITECTURE and FEATURES converge: LLM never receives `ViewNavigators`.

**Divergence (open decisions):**
- **`BuiltInMutationTools` separate component vs methods on `BuiltInDataTools`** — ARCHITECTURE says separate (preserves v1.0 ASM read-only test). **Resolution:** separate.
- **HITL pattern** — STACK suggests delegate-to-Jmix-UI; FEATURES leaves `internalToolExecutionEnabled=false` open. **Resolution:** delegate as default; `returnDirect=true` preview as Phase 11 spike.
- **Intent-extraction draft persistence** — STACK leans `VaadinSession`; ARCHITECTURE/PITFALLS specify persisted entity. **Resolution:** persisted `AiExtractionDraft` (survives navigation, has TTL, no cross-user `Map` leak).
- **Mutation tool depth** — open. **Resolution to roadmapper/requirements:** ship CREATE + UPDATE + ADD/REMOVE_RELATED in v1.1 with per-tool `@ConditionalOnProperty`; DELETE off-by-default even when mutations on.

**Open questions deferred to requirements step:**
1. Mutation tool depth (CREATE+UPDATE+RELATED vs include DELETE).
2. Intent-extraction provider routing (follow chat model vs pin to JSON-strong model).
3. STT recording UX (push-to-talk vs click-to-toggle 60s cap).
4. Floating launcher placement (configurable corner vs bottom-right always).
5. v1.1 vs v1.1.1 phasing for intent-driven extraction.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All four library surfaces Context7-verified. Zero new core deps. |
| Features | HIGH (table stakes); MEDIUM-HIGH (intent extraction prior art) | Microsoft Copilot Studio, Dynamics 365, CopilotKit cited as concrete prior art for every "must have." |
| Architecture | HIGH | Every claim grounded in named files + line numbers; integration seams are existing extension points. MEDIUM only on `BuiltInMutationTools` `@Transactional` per-method vs per-call (Phase 11 spike). |
| Pitfalls | HIGH | Each pitfall framed as regression against named v1.0 invariant; mitigations map 1:1 to existing test classes. |

**Overall confidence:** HIGH.

### Gaps to Address

- **Mutation HITL pattern verification** — Phase 11 spike: Context7-verified check of Spring AI 1.1.4 `internalToolExecutionEnabled=false` samples before plan authoring.
- **Surface-portal hook into host MainView** — Phase 12 planning-time Context7 + jmix-context7 lookup.
- **Intent-extraction provider/model choice** — requirements step decides; ship `jmix.ai-agent.intent.model` override property; default = follow chat parameter.
- **Cross-locale baseline-cache key** — verify existing baseline cache-key implementation excludes locale-sensitive labels.
- **v1.0 deferred PKG-05/TEST-07 clean-consumer smoke** — explicitly out of v1.1 scope (PROJECT.md, STATE.md). Do not pull into roadmap.

## Sources

### Primary (HIGH confidence)
- Context7 `/spring-projects/spring-ai` (1.1.x) — audio transcription, structured output, tool calling, advisor primitives.
- Context7 `/vaadin/docs` (24.x) — Dialog modality/draggable, AppLayout drawer slots, Element JS bridge.
- Context7 `/jmix-framework/jmix-context7` (Jmix 2.8) — `ViewNavigators.withInitializer`, `AccessManager` contexts, entity/attribute policy annotations.
- v1.0 shipped code — `BuiltInDataTools`, `BaselineContextProvider`, `ToolCallbackAuditDecorator`, `ChatPanelFragment`, `OwnershipOpacityTest`, `DefaultChatServiceImpl`, `ChatClientFactory`, `AgentToolCallbacks`, `CurrentUserSchemaAccess`, `AuditWriter`.
- `MEMORY.md` — "AI is just another Jmix client"; "UnconstrainedDataManager for system writes" (audit/seed only); "Reuse Jmix built-ins"; "Jmix-first UI"; "Avoid ArchUnit"; "SPIs only for app-specific behavior."
- `.planning/PROJECT.md`, `.planning/STATE.md`, `SEED-005`, `SEED-007`.

### Secondary (MEDIUM confidence)
- Microsoft 365 Copilot data protection architecture; Copilot Studio governance; Copilot UI guidelines.
- CopilotKit HITL docs; `CopilotChat` / `CopilotSidebar` / `CopilotPopup`.
- GitHub Copilot enterprise AI controls; Dynamics 365 Copilot 2026.
- Levelpath / Stack AI / Azure architecture — intent-extraction prior art.
- Spring AI Tool Approval Strategy discussion #4878.
- LiteLLM audit logs; EU AI Act August 2026.

---

### Roadmap Implications

Suggested phases: **6** (Phases 9–14)

1. **Phase 9 — Tool-Layer Foundations & Prompt-Contract Hardening** — pure additive; no behavioral risk; foundation for everything.
2. **Phase 10 — AI Exposure Policy** — must precede mutation tools; ships read-only narrowing first; **must include RAG cross-cut**.
3. **Phase 11 — Mutation Tools** — hard-depends on 9 and 10; default OFF; mandatory idempotency key.
4. **Phase 12 — Configurable Chat Surfaces** — independent; one fragment, one service, one conversation across surfaces.
5. **Phase 13 — Chat Task Input (STT + Task File)** — independent; lowest coupling; STT never calls ChatService directly; files never touch VectorStore.
6. **Phase 14 — Intent-Driven Extraction → Form Prefill** — highest novelty; LLM never navigates; controller does, after permission check.

### Research Flags

Needs research-phase: **Phase 11** (Spring AI HITL pattern spike), **Phase 14** (Jmix prefill + DataContext interaction spike), **Phase 12** (Jmix MainView hook for surface mounter).
Standard patterns: **Phase 9, Phase 10, Phase 13**.

### Confidence

**Overall: HIGH.**
Gaps: Spring AI HITL pattern verification (Phase 11), Jmix MainView mounting hook (Phase 12), intent-extraction provider/model decision (requirements), v1.1 vs v1.1.1 scope decision for intent extraction (requirements).

### Ready for Requirements

Synthesis ready. Proceeding to requirements definition.
