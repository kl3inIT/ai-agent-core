# Feature Landscape — Jmix AI Copilot v1.1.0

**Domain:** Enterprise AI copilot add-on (Jmix / Spring AI / Vaadin Flow)
**Researched:** 2026-04-26
**Scope:** v1.1.0 target features only — prompt-contract hardening, tool-layer refinements, mutation-capable tools, AI-specific exposure policy, chat task input (speech + transient files), intent-driven extraction, configurable chat surfaces.
**Overall confidence:** MEDIUM-HIGH (Spring AI / Vaadin specifics verified via official docs; product UX patterns drawn from Microsoft Copilot Studio / CopilotKit / Dynamics 365 prior art)

---

## Executive Read

The v1.1 scope is internally consistent with where 2026 enterprise copilot products are converging:

1. **Mutation tools + governed exposure are *paired*** in every prior-art product (Copilot Studio, Dynamics 365, CopilotKit). Shipping write tools without an admin-governed denylist is a documented anti-pattern — it forces the host to lean entirely on user-level RBAC, which is rarely fine-grained enough at the AI surface.
2. **Three chat surfaces (full / sidebar / floating) over one fragment** is the table-stakes pattern (CopilotKit `CopilotChat` / `CopilotSidebar` / `CopilotPopup`; Microsoft Copilot side-by-side + inline + floating). v1.1 is not innovating here — it is catching up to the documented standard.
3. **Speech-to-text via Web Speech API** is universally LOW-effort for a turn-based STT-only flow but has known platform constraints (Chrome/Chromium + Safari only; Chrome routes audio to Google servers). For enterprise, this is acceptable as a v1.1 baseline only if the constraint is documented.
4. **Intent-driven extraction → prefilled form** (PDF → structured draft → Jmix form) is the highest-novelty, highest-complexity feature of v1.1 — it touches structured-output schemas, file lifecycle, view navigation, and domain-specific intents. Treat as a single phase, not bundled with chat-input work.
5. **Prompt-contract hardening** is the lowest-risk, highest-leverage item. It should ship first because it is also the foundation for the LLM permission inventory which the exposure policy reuses.

---

## Table Stakes

Features users expect of an enterprise AI copilot in 2026. Missing these → product feels incomplete relative to Microsoft Copilot Studio / Dynamics 365 Copilot / CopilotKit.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Readable entity inventory in baseline context (`agent.entities = name (label)` lines) | LLMs hallucinate entity names without an explicit inventory; every Copilot Studio / Dataverse pattern injects a schema/context block. Confirmed by Spring AI tool-calling docs: tool descriptions are part of the prompt budget, so inventories must be compact. | **Low** | Reuses existing `CurrentUserSchemaAccess`. Pure prompt-template change + 1 baseline context method. |
| Hide internal tool/entity names from user-facing chat (`ToolResultFormatter` promotes label) | All polished copilots present user-facing labels (Microsoft Copilot Studio's "agent UI guidelines" make this a hard rule). Internal names (`agent_Customer`) leaking into chat is a UX and governance smell. | **Low-Medium** | Renderer change; no LLM behavior shift. Risk is incomplete coverage (some tools may bypass the formatter — needs a test matrix). |
| Deterministic `unknown_entity` retry contract (one `list_entities` recheck, no guessing) | Without an explicit retry contract the LLM either hallucinates or loops. Spring AI's `ToolApprovalStrategy` discussion (#4878) and CopilotKit HITL docs both document this as an explicit-state-machine pattern. | **Low** | Prompt-rule + structured tool-error response. Verifiable via integration test. |
| Richer `describe_entity` (comment, attributeType, mandatory, cardinality, persistent/transient/readOnly, isPrimaryKey, enumValues, relationshipTarget) | Returning raw Jmix `MetaClass` is too verbose and leaks internals. Selected fields is the documented Spring AI pattern: tools should return concise structured objects. | **Low-Medium** | Pure DTO mapping over `MetaClass`. Risk: scope creep — must define fields explicitly and stop. |
| LLM permission inventory at entity + attribute level (per request) | Both Copilot Studio (sensitivity labels) and Microsoft 365 Copilot (Purview integration) inject the user's effective view of the data into context. This is the prerequisite for the exposure policy. | **Medium** | New per-request context provider that calls Jmix `AccessManager` for each entity/attribute. Performance risk: must cache per-request, not per-tool-call. |
| Mutation tools (create/update/related-write) gated by Jmix `AccessManager` (CRUD + EntityAttributeOp) | Every 2026 enterprise copilot ships create/update tools. Read-only-only is now considered MVP-only. Spring AI 1.1.x explicitly supports this via `ToolCallback` + `internalToolExecutionEnabled=false` for HITL gating. | **High** | See [Risk Flag MUT-1] below. |
| Audit of mutation calls end-to-end with structured-error contract on policy denial | EU AI Act (August 2026 effective date for high-risk systems), LiteLLM proxy audit-log pattern, Microsoft Purview audit pattern — all mandate immutable per-call records. Already partially in place via `AiAuditEvent` tree. | **Low-Medium** | Extension of existing audit tree; new `event_type` values and a structured-error envelope. |
| Opt-in per host config (mutation tools default OFF) | Preserves v1.0 read-only stance. Documented practice in Copilot Studio (mutation topics are explicitly authored, not auto-enabled). | **Low** | Config flag in `AiParameters` or `application.properties`. |
| AI-specific entity + attribute denylist (admin-governed, narrows below user's Jmix permissions) | Microsoft Purview sensitivity labels, Copilot CLI MCP allowlists, Dataverse column-level security — all converge on this pattern. Distinct from RBAC because user retains app access; LLM does not. | **Medium-High** | New entity (`AiExposureRule`), new SPI hook in `CurrentUserSchemaAccess` + tool callback assembly. See [Risk Flag EXP-1]. |
| Admin Flow UI for managing exposure rules | Copilot Studio Admin Center, Microsoft Purview AI hub — admin-governed visibility is a governance feature, not a config-file feature. | **Medium** | Standard Jmix list/detail views over `AiExposureRule`. |
| Speech-to-text task input (browser mic) | Universal copilot affordance (Microsoft Copilot voice, ChatGPT voice, every consumer chat product). Web Speech API is the standard browser implementation. | **Low** | Vaadin already has voice-recognition add-ons. See [Risk Flag STT-1]. |
| Task-scoped file attachment (transient, separate lifecycle from KB) | Distinction is documented: ChatGPT, Claude, Copilot all separate "attach to this turn" from "add to knowledge base". Conflating the two is a documented anti-pattern. | **Medium** | Needs explicit lifecycle: temp storage, eviction policy, no embedding/indexing, scoped to conversation/turn. |
| Clear UI distinction between "send", "attach for current task", "upload to KB" | UX best practice — Microsoft 365 Copilot UI guidelines explicitly call out trust signals for data flows. | **Low** | Iconography + tooltip work. |
| Right-sidebar embedded chat | CopilotKit `CopilotSidebar`, Microsoft Copilot "side-by-side mode" — every modern enterprise copilot ships this. | **Medium** | Same backend, same `ChatPanelFragment`; new shell mounting point and routing rules. |
| Floating chat launcher (bottom-right) | CopilotKit `CopilotPopup`, Windows 11 Copilot taskbar share, Intercom-style bubble — universal pattern. | **Medium** | Mount at host shell. Risk: conversation continuity across surface switches. |
| Admin toggle for which surfaces are enabled | Copilot Control System (Microsoft) staged rollout, Copilot Studio environment-scoped controls — staged rollout is enterprise table stakes. | **Low-Medium** | Enum/bitfield on `AiParameters`; honored at shell-mount time. |

---

## Differentiators

Features that go beyond the standard playbook. Not strictly expected, but raise the product ceiling.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Host-override SPI for tool fetch plans (per `toolName` + `MetaClass`) | Most copilots hard-code their tool data shape. Letting the host tune fetch plans means a heavy host entity doesn't blow the prompt budget. Differentiator versus generic copilot kits. | **Medium** | New SPI; default keeps `_base/_instance_name`. Risk: SPI surface drift; freeze the contract early. |
| Intent-driven extraction → prefilled Jmix forms (PDF → customer-draft → form prefill) | Microsoft Levelpath / Stack AI / Instafill all sell this as standalone product. Embedding it inside a Jmix copilot means a host gets it for free — strong sales differentiator. | **High** | See [Risk Flag INT-1]. The hardest feature in v1.1 by a wide margin. |
| Structured draft renders read-only in chat *before* navigation | Trust signal: user reviews the proposed prefill IN-CONVERSATION before the form opens. Matches the "human remains the decision-maker" Copilot UX principle. | **Medium** | Generative-UI in chat (Vaadin server-rendered card). Reuses existing structured-output infra. |
| User confirms → `ViewNavigators` opens prefilled Jmix form | Hand-off from chat to standard Jmix CRUD flow keeps mutations on the well-trodden Jmix UI path. Avoids re-implementing form validation in chat. | **Medium** | Form-prefill via query parameters or a fluent builder; Jmix `ViewNavigators` already supports this pattern. |
| Per-turn audit visibility of which tools the LLM was *allowed* to see (resolved permission inventory) | "Why did the LLM not answer X?" debugging — already a documented pain in Microsoft Purview AI hub. Showing the resolved view in audit makes governance investigations tractable. | **Low** | Reuses existing audit tree; one new event type. |
| Structured-error envelope for tool failures (denial, validation, unknown-entity) | Ad-hoc error strings cause LLM hallucination. Spring AI tool-callback docs recommend structured errors. | **Low** | Codifies what already exists informally. |

---

## Anti-Features

Features to explicitly NOT build in v1.1.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Auto-approving mutation tools without HITL on first use of a write surface | Universal anti-pattern in 2026 copilots; CopilotKit's HITL docs and Microsoft "trust is foundational" guideline both forbid silent writes on sensitive surfaces. | Default mutation tools to **off**; when on, require explicit confirmation via either chat-side preview (intent-driven flow) OR navigate-to-form-then-save (delegate confirmation to Jmix UI). Treat the LLM's tool call as a *proposal*, not a commit. |
| Building a parallel ACL on top of Jmix `AccessManager` for normal user authorization | Violates "AI is just another Jmix client" memory note. Two enforcement layers means two places to misconfigure. | Exposure policy *only narrows* below user's Jmix permissions — it never grants more. Jmix `AccessManager` remains authoritative for the user. |
| Real-time streaming speech-to-speech (LLM voice) | Out of scope ladder: STT → LLM → TTS pipeline; v1.1 is text-output only with mic input. Streaming voice has very different latency, infra, and cost profiles. | Browser STT → text into existing chat input. Defer TTS / S2S to v2+. |
| KB ingestion of task-scoped attachments | Conflates transient (one-turn) lifecycle with persistent vector store. Bug magnet: user uploads sensitive PDF expecting it to vanish, ends up indexed forever. | Separate code paths, separate storage, explicit eviction. Different UI affordance with explicit copy ("attach to this task" vs "add to knowledge base"). |
| Multi-step autonomous mutation chains (LLM does N writes without intermediate user approval) | Outside v1 scope per `PROJECT.md` ("autonomous multi-step agents remain out of v1 scope"). Spring AI 1.1.x supports this but the safety/cost story is unbuilt here. | One write = one confirmation. Chain via user's next prompt, not LLM agency. |
| Building our own STT model | Re-invents the wheel. Microsoft Copilot, ChatGPT, etc. all use Web Speech API + provider STT. | Use Web Speech API for browser-native; document the Chrome-only constraint; allow swap-in via SPI for cloud STT later. |
| Generic "ask a question and prefill any form" intent extraction | Untyped intent = unsolvable without huge LLM prompt engineering. v1 documented examples (PDF → customer draft) are file-bound and intent-bound. | v1.1 ships **enumerated intents** (host registers them; e.g., "Create customer from PDF"). Free-form intent inference is v2+. |
| Floating launcher with its own conversation memory model | SEED-005 explicitly warns against this: "future work should not introduce a second `ChatService`, second conversation store, or separate memory semantics for the launcher". | Same backend, same `ChatPanelFragment`, same `AiConversation`. Surface is presentation-only. |
| Per-screen contextual injection (launcher reads "current entity from current view") | Tempting, but explicitly out of SEED-005 scope ("Do not mix this with new mutation tools, cross-view context injection"). Also creates a per-view security review burden. | Defer to v2. Launcher in v1.1 is just a UI surface, not a context-aware copilot. |

---

## Feature Dependencies

```
PROMPT-HARDENING (entity inventory, hidden internal names, unknown_entity retry)
        |
        +--> RICHER describe_entity ---+
        |                              |
        |                              v
        +--> LLM PERMISSION INVENTORY -+--> EXPOSURE POLICY (denylist/allowlist + admin UI)
        |                                                |
        |                                                v
        |                                          MUTATION TOOLS (create/update/related-write)
        |                                                |
        |                                                +--> Audit extension on mutation events
        |
        +--> HOST-OVERRIDE SPI for fetch plans (independent — pairs with describe_entity refinement)

CHAT TASK INPUT (STT + transient file attach)         [largely independent]
        |
        +--> Reused by INTENT-DRIVEN EXTRACTION (file path is the same upload surface, different lifecycle hook)
                                |
                                +--> STRUCTURED DRAFT IN CHAT --> ViewNavigators prefill

CONFIGURABLE CHAT SURFACES (full / sidebar / floating + admin toggle)
        |
        +--> ChatPanelFragment must remain stable (existing work — soft dependency)
        +--> No dependency on mutation tools or exposure policy (presentation only)
```

### Critical Ordering Constraint

**Exposure policy MUST land before mutation tools** — or at minimum in the same phase with exposure shipping first.

Reasoning: a mutation tool that sees an entity the admin wanted hidden from the LLM is a governance incident. Read-only tools at most leak data; mutation tools mutate it. The narrower-than-Jmix surface that the exposure policy provides is the safety boundary that makes mutation tools palatable to enterprise security review.

Corollary: if exposure policy slips, mutation tools must slip with it — they cannot ship under user-RBAC alone in this milestone.

### Soft Dependencies

- **LLM permission inventory** is logically a child of prompt-hardening (it's a context-block addition) but is reused by exposure policy (the resolved view is what the policy filters). Build once, consumed twice.
- **Richer `describe_entity`** consumes the entity inventory metadata; build it after the inventory shape is settled to avoid two-pass refactor.
- **Intent-driven extraction** depends on task-scoped file attachment shipping first (same upload mechanism, different lifecycle). Order them in the same phase, file-attach first.

### Independent Tracks (can parallelize phases)

- Configurable chat surfaces — pure UI, no backend touch beyond admin toggle on `AiParameters`. Can run in parallel with the prompt/tool/exposure track.
- Speech-to-text — pure UI, no backend touch. Can ship in any phase that touches `ChatView`.

---

## MVP-of-Milestone Recommendation

If v1.1 has to ship narrower than scoped, prioritize in this order:

1. **Prompt-contract hardening** (entity inventory + internal-name hiding + `unknown_entity` retry) — lowest risk, highest leverage, foundation for everything downstream.
2. **Richer `describe_entity` + LLM permission inventory** — small, high-value, prepares the ground for exposure policy.
3. **AI-specific exposure policy + admin Flow UI** — must precede mutation tools.
4. **Mutation tools (create / update / related-write) + audit extension + opt-in config** — gated by #3.
5. **Configurable chat surfaces (full / sidebar / floating + admin toggle)** — independent track; can land any time after `ChatPanelFragment` is stable.
6. **Chat task input (STT + transient file attach)** — independent; pairs with #7.
7. **Intent-driven extraction → prefilled form** — highest novelty, defer if pressed; can ship as a v1.1.1 patch milestone if v1.1 needs to close.

**Hard defer candidates** if scope must shrink:
- Intent-driven extraction (defer to v1.2; it's a self-contained product surface).
- Floating launcher (keep full + sidebar; defer floating to v1.2).
- Host-override SPI for fetch plans (ship default behavior; SPI in v1.2).

**Cannot defer** without breaking the milestone thesis:
- Prompt-contract hardening (foundation).
- Exposure policy + mutation tools as a *pair* (mutation alone is unsafe; exposure alone is unmotivated).

---

## Risk Flags (Complexity Notes)

### [Risk Flag MUT-1] Mutation Tool Confirmation Strategy

Spring AI 1.1.x has **no built-in tool-approval API** ([Spring AI tool-calling docs](https://docs.spring.io/spring-ai/reference/api/tools.html), discussion [#4878](https://github.com/spring-projects/spring-ai/discussions/4878)). Confirmation must be implemented via either:
- `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)` — pause the loop, inspect proposed tool calls, render confirmation UI, resume.
- Custom `ToolCallback` wrapper that intercepts the call.
- `returnDirect = true` with the tool returning a "preview" payload that the user must confirm in a follow-up turn.

**Recommendation for v1.1:** delegate confirmation to the Jmix UI flow whenever possible:
- Intent-driven extraction → form prefill → user clicks Save in the standard Jmix detail view = confirmation.
- Mutation tools (when invoked from chat) → return-direct preview card → user clicks "Apply" button → second tool call commits.

This avoids inventing a new HITL framework while staying within Spring AI 1.1.x primitives. Verify the `internalToolExecutionEnabled=false` pattern via Context7 / Spring AI samples before committing to it.

### [Risk Flag EXP-1] Exposure Policy Performance

Per-request `AccessManager` checks for every entity + every attribute can be expensive (Jmix metamodel has hundreds of attributes in real apps). Recommendations:
- Resolve the LLM-visible surface **once per request**, cache on the request-scoped baseline context.
- Push the cache as a single immutable `AiVisibleSchema` snapshot consumed by tool callbacks AND prompt builder AND audit logger.
- Do not call `AccessManager` from inside `@Tool` method bodies — call once at request entry.

This mirrors the way Microsoft 365 Copilot computes user data scope at conversation entry, not per turn.

### [Risk Flag STT-1] Web Speech API Constraints

- Chrome and Chromium browsers send captured audio to **Google servers** by default. Edge enterprise privacy review may block this.
- Safari supports Web Speech API but with different language defaults.
- Firefox does not support `SpeechRecognition` (only `SpeechSynthesis`).
- On-device speech recognition flag exists but is browser-and-OS-dependent.

**Recommendation:** ship Web Speech API as the v1.1 default (low effort, broadly enough supported); document the Chrome→Google audio path explicitly in the operator README; expose an SPI seam (`SpeechToTextProvider`) so hosts with stricter requirements can swap in an on-prem STT in v1.2 without forking.

### [Risk Flag INT-1] Intent-Driven Extraction Scope

This feature alone is the size of a small product. Avoid scope explosion by:
- **Enumerated intents only.** Host registers `Intent` beans (e.g., `CreateCustomerFromPdfIntent`). No free-form intent inference in v1.1.
- **File-only input.** No "extract from chat history" or "extract from URL" in v1.1.
- **Single output entity per intent.** No "extract a customer + 3 orders + 5 line-items" in v1.1.
- **Read-only draft in chat first.** User MUST confirm before navigation.
- **Structured output via Spring AI's `BeanOutputConverter`** (already in v1.0.0 stack); do not invent a new schema layer.

If any of these constraints needs to bend, push the bend to v1.2 — the whole feature is the differentiator and is worth getting right rather than shipping wide.

### [Risk Flag SURF-1] Surface Toggle vs Conversation Continuity

If a user opens the floating launcher, asks a question, then navigates to the full `ChatView`, they expect the conversation to continue. SEED-005 explicitly warns against parallel state.

**Recommendation:** model the active conversation as a **per-user singleton** (active `AiConversation` reference) at the session level; all three surfaces resolve to the same record. Verify by integration test: open in launcher → switch to full view → assert same `AiConversation.id`.

### [Risk Flag ATT-1] Transient Attachment Lifecycle

Risks: (a) user expects file to vanish but it persists; (b) eviction races with in-flight LLM calls.

**Recommendation:** store under `temp/conversations/{conversationId}/{turnId}/` with a TTL daemon; pin during active turn; evict on next turn (or 24h, whichever is shorter). Add an explicit purge action on conversation close. Audit each attachment as a child event under the user-message audit node.

---

## Summary Table — Complexity per Item

| Feature Item | Complexity | Risk Flag |
|--------------|------------|-----------|
| Entity inventory in baseline context | Low | — |
| Hide internal entity/tool names | Low-Medium | Coverage test matrix |
| `unknown_entity` retry contract | Low | — |
| Richer `describe_entity` wrapper | Low-Medium | DTO scope discipline |
| Host-override SPI for fetch plans | Medium | SPI contract freeze |
| LLM permission inventory | Medium | EXP-1 (perf) |
| Mutation tools (create/update/related-write) | High | MUT-1 |
| Mutation audit + structured-error contract | Low-Medium | — |
| Mutation opt-in host config | Low | — |
| AI-specific exposure policy entity + SPI | Medium-High | EXP-1 |
| Admin Flow UI for exposure rules | Medium | — |
| Speech-to-text input | Low | STT-1 |
| Task-scoped file attachment | Medium | ATT-1 |
| UI distinction (send/attach/KB-upload) | Low | — |
| Intent-driven extraction → form prefill | High | INT-1 |
| Right-sidebar chat | Medium | SURF-1 |
| Floating launcher | Medium | SURF-1 |
| Admin surface toggle | Low-Medium | — |

---

## Sources

- [Spring AI Tool Calling reference (1.1.x)](https://docs.spring.io/spring-ai/reference/api/tools.html) — confirms no built-in approval API; documents `internalToolExecutionEnabled`, `returnDirect`, custom `ToolCallback` patterns. **HIGH confidence.**
- [Spring AI Tool Approval Strategy discussion #4878](https://github.com/spring-projects/spring-ai/discussions/4878) — community confirmation of the approval-via-callback-wrapper pattern. **MEDIUM confidence.**
- [Microsoft 365 Copilot data protection architecture](https://learn.microsoft.com/en-us/copilot/microsoft-365/microsoft-365-copilot-architecture-data-protection-auditing) — table-stakes governance pattern (sensitivity labels, audit). **HIGH confidence.**
- [Microsoft Copilot Studio sensitivity labels](https://learn.microsoft.com/en-us/microsoft-copilot-studio/sensitivity-label-copilot-studio) — admin-governed exposure pattern. **HIGH confidence.**
- [Microsoft Copilot Control System Security and Governance](https://learn.microsoft.com/en-us/copilot/microsoft-365/copilot-control-system/security-governance) — Entra-rooted identity, conditional access, DLP inheritance. **HIGH confidence.**
- [Copilot Studio governance reference (PowerTricks, 2026)](https://powertricks.io/copilot-studio-governance/) — denylist/allowlist patterns at entity + attribute level. **MEDIUM confidence.**
- [Dynamics 365 Copilot 2026 changes](https://www.appverticals.com/blog/dynamics-365-copilot/) — mutation-capable copilots in production enterprise apps. **MEDIUM confidence.**
- [GitHub Copilot enterprise AI controls + agent control plane (Feb 2026)](https://github.blog/changelog/2026-02-26-enterprise-ai-controls-agent-control-plane-now-generally-available/) — admin governance precedent. **MEDIUM confidence.**
- [CopilotKit Human-in-the-Loop docs](https://docs.copilotkit.ai/human-in-the-loop) — interrupt + confirmation pattern. **HIGH confidence.**
- [CopilotKit `CopilotSidebar`](https://docs.copilotkit.ai/reference/components/chat/CopilotSidebar), [`CopilotChat`](https://docs.copilotkit.ai/reference/components/chat/CopilotChat), [`CopilotPopup`](https://docs.copilotkit.ai/reference/components/chat/CopilotPopup) — three-surface UX precedent. **HIGH confidence.**
- [Microsoft Copilot UI guidelines for declarative agents](https://learn.microsoft.com/en-us/microsoft-365-copilot/extensibility/declarative-agent-ui-widgets-guidelines) — inline + side-by-side mode pattern. **HIGH confidence.**
- [MDN Web Speech API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API), [Using the Web Speech API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API/Using_the_Web_Speech_API) — browser STT primitives. **HIGH confidence.**
- [AssemblyAI: Speech recognition in browsers using Web Speech API](https://www.assemblyai.com/blog/speech-recognition-javascript-web-speech-api) — Chrome-only constraint and audio-to-Google note. **MEDIUM confidence.**
- [Vaadin Voice Recognition add-on](https://vaadin.com/directory/component/voice-recognition), [Vaadin Voice Engine add-on](https://vaadin.com/directory/component/voice-engine-add-on-for-vaadin), [Adding speech recognition to Vaadin apps](https://vaadin.com/blog/adding-speech-recognition-to-vaadin-apps) — Flow-native STT integration. **HIGH confidence.**
- [Levelpath AI intake and prefill](https://www.levelpath.com/glossary/ai-intake-and-prefill), [Stack AI PDF form-filling agents](https://www.stackai.com/blog/pdf-form-filling-ai-agents-how-enterprises-are-automating-document-heavy-operational-workflows), [Azure architecture: automate PDF forms processing](https://learn.microsoft.com/en-us/azure/architecture/ai-ml/architecture/automate-pdf-forms-processing) — intent-driven extraction prior art. **MEDIUM-HIGH confidence.**
- [LLM Audit Logs and Compliance Architecture](https://medium.com/@vasanthancomrads/ai-audit-logs-and-compliance-architecture-b0e1b62772d7), [LiteLLM audit logs](https://docs.litellm.ai/docs/proxy/multiple_admins) — audit-trail pattern for tool calls. **MEDIUM confidence.**
- [Enterprise LLM Governance (CXToday)](https://www.cxtoday.com/security-privacy-compliance/enterprise-llm-governance/) — EU AI Act August 2026 compliance bar. **MEDIUM confidence.**
- Internal: `.planning/PROJECT.md`, `.planning/STATE.md`, `.planning/seeds/SEED-005`, `.planning/seeds/SEED-007` — milestone scope and seed activation context. **HIGH confidence (internal authoritative).**
