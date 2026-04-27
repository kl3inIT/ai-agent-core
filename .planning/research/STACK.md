# Stack Research — v1.1.0 Additions

**Domain:** Jmix AI Copilot add-on (`ai-agent-core`) — incremental v1.1 stack delta
**Researched:** 2026-04-26
**Confidence:** HIGH for Spring AI 1.1.4 audio + tool-error contracts (Context7 verified); HIGH for Vaadin Flow 24 dialog/drawer/JS-bridge patterns (Context7 verified); HIGH for Jmix `ViewNavigators.detailView(...).withInitializer(...)` form-prefill (Context7 verified); MEDIUM for browser-side audio capture choice (`MediaRecorder` is the standard; there is no Vaadin first-party microphone component, so client-side JS is required).

## Scope of This Document

v1.0 stack is pinned and validated (Java 21, Jmix 2.8.1, Spring Boot 3, Vaadin Flow 24, Spring AI 1.1.4 BOM, pgvector, OpenRouter via `spring-ai-starter-model-openai`, Liquibase, Tika, `jmix-security-data`, JDBC chat memory). **No version bumps to v1.0 dependencies are required for v1.1**, and none are recommended — the BOM is a milestone release whose churn would force re-validation across the entire chat/RAG/guard/audit chain. v1.1 work fits inside the existing BOM.

This file enumerates ONLY the new additions or activations needed for the five v1.1 features:

1. Mutation-capable built-in tools
2. AI-specific LLM exposure policy (admin-governed denylist/allowlist)
3. Speech-to-text input in chat
4. Intent-driven extraction → prefilled Jmix forms
5. Configurable chat surfaces (full / right-sidebar / floating launcher)

## Recommended Additions

### Core Technologies (no new core; activations only)

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `org.springframework.ai:spring-ai-openai` (audio transcription API surface) | 1.1.4 (already on classpath via `spring-ai-starter-model-openai`) | Speech-to-text via `OpenAiAudioTranscriptionModel` | Already auto-configured by the starter. Activating it requires NO new dependency — only `spring.ai.openai.audio.transcription.*` properties. Ships first-class `AudioTranscriptionPrompt` / `AudioTranscriptionResponse` types and a `language` option (covers vi/en). |
| `org.springframework.ai:spring-ai-client-chat` (`@Tool` + `ToolExecutionException` + `ToolExecutionExceptionProcessor`) | 1.1.4 (already on classpath) | Mutation tools + structured-error contract on policy denial | The 1.1 contract for tool failures is `ToolExecutionException` thrown from the `@Tool` method body; `DefaultToolExecutionExceptionProcessor` (auto-bean) converts to a model-readable string. We layer `AccessManager.CrudEntityContext` / attribute-policy denials onto this same exception so the LLM observes a single structured failure shape. |
| `org.springframework.ai:spring-ai-client-chat` (`BeanOutputConverter` + `ChatClient.prompt().call().entity(Class)`) | 1.1.4 (already on classpath) | Intent-driven extraction → typed Java record → form prefill | First-class structured-output binding in 1.1: `chatClient.prompt().user(...).call().entity(InvoiceDraft.class)`. JSON Schema is generated from the record (use `@JsonProperty(required = true, ...)` to mark required fields). Pairs cleanly with Jmix `ViewNavigators.detailView(...).withInitializer(e -> {...})` for prefill. |
| `io.jmix.flowui` `ViewNavigators` / `DialogWindows` (`.detailView(...).newEntity().withInitializer(...).navigate()`) | 2.8.1 (already on classpath) | Open the prefilled host detail view from chat | Verified pattern in Jmix 2.8 docs (Context7 `/jmix-framework/jmix-context7`). `withInitializer` runs server-side after `Metadata.create(...)` and accepts arbitrary lambdas — the natural seam for an extracted DTO → entity field-set step. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Browser `MediaRecorder` API (no JAR — client-side) | Browser-native (HTML5) | Capture mic audio in the browser, encode to `audio/webm; codecs=opus` (Chrome/Edge/Firefox) or `audio/mp4` (Safari) | Wire via `Element.executeJs(...)` from a Jmix Flow chat-input button. Stream the resulting `Blob` to the server with `fetch()` against a `UploadHandler.inMemory(...)`-registered endpoint (or a small `@RestController` under `/ai-agent/api/stt`). The `webm/opus` and `mp4` containers are both accepted by OpenAI Whisper transcriptions — no transcoding needed. |
| `com.vaadin.flow.component.upload.Upload` (`UploadHandler.inMemory` / `UploadHandler.toFile`) | bundled with `jmix-flowui` 2.8.1 | Server-side receiver for the audio blob and for the v1.1 task-scoped file attachment | Already used elsewhere in the add-on. Per memory `feedback_jmix_upload_receiverType_deprecated`, keep `UploadHandler.toFile` (NOT `setReceiver`). For STT: in-memory handler is preferred — clip is short-lived and we hand the bytes directly to `AudioTranscriptionPrompt`. |
| `com.vaadin.flow.component.dialog.Dialog` with `setModality(MODELESS)` + `setDraggable(true)` | bundled with `jmix-flowui` 2.8.1 | Floating-launcher chat surface (#3 of three configurable surfaces) | Verified in Vaadin docs. Modeless+draggable is the documented pattern; pair with a fixed-position `Button` in the host's main `AppLayout` to act as the launcher. **Do not** introduce a custom web component or Hilla overlay — the `hilla` / `copilot` modules are already excluded in `ai-agent.gradle:121-124` and that exclusion must hold. |
| `com.vaadin.flow.component.applayout.AppLayout` `slot="drawer"` / `slot="drawer-end"` (the right-side drawer slot) | bundled with `jmix-flowui` 2.8.1 | Right-sidebar chat surface (#2 of three configurable surfaces) | Verified Vaadin pattern: `element.setAttribute("slot", "drawer")` for left, `slot="drawer-end"` for right (Vaadin 24.4+). Hosts mount the same `ChatPanelFragment` into this slot via a Jmix layout-extension SPI. **Reuse** of `ChatPanelFragment` is non-negotiable — do NOT fork the chat panel for sidebar mode. |
| `com.vaadin.flow.component.notification.Notification` / `com.vaadin.flow.component.html.Anchor` | bundled with `jmix-flowui` 2.8.1 | Confirm-and-navigate UX from chat to prefilled form | Already in use; no addition. The flow is: chat receives extracted JSON, renders a confirm card, on confirm calls `viewNavigators.detailView(...).newEntity().withInitializer(...).navigate()` from the active Vaadin `UI`. |
| Jakarta Validation (`jakarta.validation-api` + Hibernate Validator) | already on classpath via Spring Boot BOM | Validate extracted DTOs **before** prefilling the form | Already used for `AiParametersBody`. v1.1 extraction DTOs (`InvoiceDraft`, etc.) get `@NotBlank` / `@DecimalMin` / `@Pattern` annotations so we can reject obviously-broken extractions in the chat UI rather than carry a malformed initializer into the host's detail view. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Existing `@Tag("live")` excluded test tier | Hosts the new STT live test | Add a `@Tag("live")` test that hits Whisper with a 2-second sample WAV — same opt-in posture as existing live LLM tests. No CI cost change. |
| Existing `@Tag("rag-it")` Testcontainers Postgres tier | Hosts the new exposure-policy DB integration test (`AiExposureRule` table, query-time filter assertions) | Already present; the `agentstore` schema picks up the new Liquibase changelog automatically. |
| Existing `@SpringBootTest` + `@UiTest` tiers | Host: mutation-tool happy-path + policy-denial tests; intent-extraction structured-output tests; surface-toggle UI tests | No new harness needed. The existing `BuiltInDataToolsReadOnlyTest` class becomes the template for `BuiltInMutationToolsTest`. |

## Activation Recipe (no new dependency lines)

For STT, the v1.0 starter coordinate is sufficient. Add only configuration properties to the host (`jmix-app/src/main/resources/application.properties`) and an opt-in flag:

```properties
# v1.1 — STT activation (host-supplied OpenAI/Whisper key; OpenRouter does NOT proxy /audio/transcriptions)
spring.ai.openai.audio.transcription.api-key=${OPENAI_STT_API_KEY:}
spring.ai.openai.audio.transcription.base-url=https://api.openai.com
spring.ai.openai.audio.transcription.options.model=whisper-1
spring.ai.openai.audio.transcription.options.response-format=text

# Add-on opt-in flags (defined under jmix.ai-agent.* — implementation detail of v1.1):
jmix.ai-agent.stt.enabled=true
jmix.ai-agent.tools.mutations.enabled=false   # off by default; PROJECT.md mandates opt-in
jmix.ai-agent.surfaces.full=true
jmix.ai-agent.surfaces.sidebar=false
jmix.ai-agent.surfaces.launcher=false
```

For mutation tools, exposure policy, intent extraction, and the chat surfaces — **zero new dependencies**. Everything builds on the v1.0 dependency set.

## Integration Points to Existing v1.0 Components

This is the load-bearing section for the roadmapper.

| v1.0 Component | v1.1 Integration | Notes |
|---|---|---|
| `ChatClientFactory` | UNCHANGED. The same single cached `ChatClient` bean serves: (a) regular chat, (b) intent-extraction calls (`chatClient.prompt().call().entity(...)`), (c) the post-STT chat turn that consumes the transcribed text. | Per `ChatClientFactory` Javadoc, configuration is per-request via `chatClient.prompt().system().user().options()`; intent-extraction simply omits tools/RAG/memory advisors via `.advisors(advisors -> advisors.param(...))`. **Do not** instantiate a parallel `ChatClient` for extraction. |
| `BuiltInDataTools` | EXTENDED (or paralleled by a sibling `BuiltInMutationTools` class in the same package, behind `jmix.ai-agent.tools.mutations.enabled`). Read-only ASM enforcement test (Plan 04) gates the existing class — keep it intact and put mutations in a new `@Component` so the bytecode rule does not weaken. | Constructor-injects the same `DataManager` + `Metadata` + `CurrentUserSchemaAccess`. New tools throw `ToolExecutionException` on `AccessManager` denial; default `DefaultToolExecutionExceptionProcessor` returns the message to the model. |
| `CurrentUserSchemaAccess` | EXTENDED. Adds an `AiExposureRulePolicy` collaborator (new bean) that runs AFTER the existing user-permission filter. The result is `effective_visible = user_visible ∩ exposure_allowed`. | This preserves the v1.0 invariant that the LLM never sees more than the user can see. Exposure rules can only NARROW, never widen. New JPA entity `AiExposureRule` lives in `agentstore` schema (Liquibase changelog under `ai-agent/.../liquibase/agentstore-changelog/`). |
| `AuditWriter` | EXTENDED. Mutation tools record `BEFORE` snapshot + `AFTER` snapshot + `ENTITY_OP` (CREATE/UPDATE/DELETE) on the existing `AiAuditEvent` parent/child tree. Intent-extraction records the extracted DTO and the user's confirm/cancel decision. STT records the transcript (NOT raw audio bytes — privacy). | Reuse existing `UnconstrainedDataManager` write path (memory `feedback_jmix_unconstrained_for_system_writes`). No new audit entity; new `event_type` enum values only. |
| `ChatPanelFragment` | EXTENDED, NOT CLONED. Adds: (a) mic-record button (calls `Element.executeJs` → `MediaRecorder` → `fetch` to STT endpoint → server pushes transcribed text into `MessageInput`), (b) intent-confirm card rendering hook, (c) optional "open form" button bound to a `Runnable` set by the host. The same fragment is used in all three surfaces. | The fragment's existing public API (`setConversationId` / `hasMessages` / `isStreaming` / `startNewChat`) is the carrier of conversation-continuity guarantees across surface switches: same conversation id ⇒ JDBC chat memory + RAG retrieval are unchanged. |
| `ConversationGateway` / `ChatService` | UNCHANGED. The streaming `Flux<StreamingEvent>` path is the same regardless of surface. | Surface choice is purely a UI concern. |
| `ai-agent-starter` `AIAutoConfiguration` | EXTENDED. Adds conditional beans for: STT model wrapper, exposure-rule policy, mutation-tool component, three surface beans. Each gated by its own `@ConditionalOnProperty`. | Keep the existing `@AutoConfiguration` ordering — STT depends on the OpenAI starter being already configured. |

## What NOT to Add (Anti-List)

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| **A second `ChatClient` for the floating launcher / sidebar** | Would split conversation memory and tool registration, defeats "same backend across surfaces" and makes audit forensics non-comparable. The v1.0 pattern is "one cached `ChatClient` bean; configure per request." | Use the existing `ChatClientFactory`-produced singleton. Each surface instantiates a fresh `ChatPanelFragment` but resolves the same `ChatService` / `ConversationGateway` beans. |
| **A separate JDBC chat-memory store for the launcher** | Same reason. Conversation continuity across surface changes requires one `ChatMemory` keyed by conversation id. | Reuse the `ProjectingChatMemoryRepository` decorator + `JdbcChatMemoryRepository` already wired in v1.0. |
| **Browser `webkitSpeechRecognition` / Web Speech API client-side STT** | Chrome-only, not available on enterprise-locked Edge/Safari, and the transcript would bypass the server — defeating audit and Vietnamese-language quality control. | Server-side `OpenAiAudioTranscriptionModel` (Whisper). Auditable, deterministic, supports `language=vi`/`en`. |
| **A new vector-store collection for STT or extraction** | STT output is a short-lived chat turn; extraction output is a structured DTO. Neither needs embeddings. | Persist transcripts on `AiMessage` (existing entity) and DTOs as JSON on the new draft handoff record (or just hand straight to `withInitializer` and forget). |
| **A new "draft" entity if the form opens immediately** | Round-tripping the draft through DB on every confirm is overkill when the user is already in the same Vaadin session. | Pass the typed DTO in-memory through a `VaadinSession` attribute or a server-side `Map<UUID, Draft>` keyed by the run id; clean up on form-detach. **Only** introduce a `AiExtractionDraft` entity if PROJECT.md adds a deferred-prefill (multi-session / mobile handoff) requirement, which it does not currently. |
| **A new surface-portal component built on Hilla / web components** | `hilla`, `hilla-dev`, and `copilot` are explicitly excluded in `ai-agent.gradle:121-124`. Reintroducing them re-opens the bundle-size and frontend-build problems v1.0 solved. | Keep all three surfaces in pure Flow Java: `ChatView` (full route), `Drawer` slot in `AppLayout` (sidebar), `Dialog.setModality(MODELESS).setDraggable(true)` (launcher). |
| **A new "mutation tool" abstraction layer / SPI** | Per MEMORY `feedback_reuse_jmix_builtins` and `feedback_spi_baseline_builtin`: SPIs are for genuinely host-specific extensions, not for built-ins shipped by the add-on itself. | Mutation tools are concrete `@Tool`-annotated methods on a new `@Component`. They use the EXISTING tool-discovery pipeline (`MethodToolCallbackProvider.builder().toolObjects(...)`). |
| **An ArchUnit rule for "no `DataManager.save` outside the new mutation-tools class"** | Per MEMORY `feedback_no_archunit`, ArchUnit is deferred until rule drift is observed. The existing read-only ASM bytecode test already demonstrates the discipline; mutation tools are the explicit, narrow exception. | Code review + a positive unit test that exercises the policy-denial path. |
| **Spring Retry / Resilience4j on mutation tools** | Mutations are not idempotent by construction; auto-retry would silently duplicate writes. | Single attempt. Failures surface as `ToolExecutionException` to the model, which can ask the user to retry explicitly. |
| **A dedicated transcoder (FFmpeg / JCodec)** | OpenAI Whisper accepts `webm/opus` and `mp4` directly; both are produced natively by `MediaRecorder` in evergreen browsers. Adding FFmpeg would balloon the artifact and inflict a native-binary dependency on every host. | Send the browser-produced blob as-is. Document the supported MIME types and reject anything else server-side. |
| **A new `RestController` family under `/api/ai-agent/...`** | The MVP avoided new HTTP surface; same logic still applies. STT receiver and any draft endpoints can be modeled as `UploadHandler` registrations or Vaadin `StreamReceiver`s within the Flow session. | Server-side endpoints registered via `Element.setAttribute("endpoint", uploadHandler)` (Vaadin pattern documented in Context7), keeping Vaadin's CSRF/session model intact. |

## Stack Patterns by Variant

**If host is OpenRouter-only (i.e., they have no direct OpenAI key) and the operator wants STT:**
- Hosts MUST set a separate `spring.ai.openai.audio.transcription.api-key` and `base-url` pointing at OpenAI proper (or another Whisper-compatible provider). OpenRouter as of 2026-04 does not proxy `/audio/transcriptions`.
- The add-on documents this in the operator README under a new "Speech-to-text setup" section.
- Recommended fallback for hosts without any STT provider: surface the mic button only when `jmix.ai-agent.stt.enabled=true`. Default is `false`.

**If host has a strict "no LLM mutations ever" policy:**
- Leave `jmix.ai-agent.tools.mutations.enabled=false` (the default). The mutation `@Component` is `@ConditionalOnProperty`-gated and never loads.
- Read-only v1.0 behavior is preserved bit-for-bit.

**If host wants only one chat surface:**
- Set the other two surface flags to `false`. The dormant surfaces register no routes / no drawer slot / no launcher button.
- Chat memory continuity is still preserved if surfaces are toggled at runtime: same conversation id → same JDBC memory rows → same RAG retrieval scope.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| `spring-ai-starter-model-openai:1.1.4` | `spring-ai-client-chat:1.1.4`, `spring-ai-rag:1.1.4`, `spring-ai-tika-document-reader:1.1.4`, `spring-ai-model-chat-memory-repository-jdbc:1.1.4` | All pinned via Spring AI BOM imported in `ai-agent/build.gradle`. **Do not** mix BOM versions. |
| `spring-ai-starter-model-openai:1.1.4` (audio transcription side) | OpenAI `/audio/transcriptions` (`whisper-1`) | Confirmed via Context7. Browser-produced `audio/webm; codecs=opus` and `audio/mp4` both accepted. Vietnamese supported via `language=vi`. |
| Vaadin Flow 24 (via Jmix 2.8.1) | `Dialog.setModality(MODELESS)` + `setDraggable(true)`; `AppLayout` `slot="drawer-end"`; `Element.executeJs(...)` + `Element.setAttribute("endpoint", uploadHandler)` | All confirmed in Vaadin 24 docs (Context7 `/vaadin/docs`). |
| Jmix 2.8.1 `ViewNavigators` | `.detailView(...).newEntity().withInitializer(initializer).navigate()` | Confirmed in Jmix 2.8 docs (Context7 `/jmix-framework/jmix-context7`). Pairs naturally with Spring AI `BeanOutputConverter`-produced records. |

## Sources

- Context7 `/spring-projects/spring-ai` (v1.1.x) — verified: `OpenAiAudioTranscriptionModel`, `AudioTranscriptionPrompt`, `OpenAiAudioTranscriptionOptions.builder().language(...)`, `spring.ai.openai.audio.transcription.*` config namespace, single-starter coverage; `BeanOutputConverter` + `chatClient.prompt().call().entity(Class)`; `ToolExecutionException` + `ToolExecutionExceptionProcessor` + `DefaultToolExecutionExceptionProcessor`; `ToolCallAdvisor` semantics for advisor-controlled tool execution. Confidence: HIGH.
- Context7 `/vaadin/docs` (24.x) — verified: `Dialog.setModality(MODELESS)`, `setDraggable(true)`, `setResizable(true)`; `AppLayout` drawer slots and `slot="drawer"` / `slot="drawer-end"` element-attribute pattern; `Element.executeJs(...)` placeholder substitution; `Element.setAttribute("endpoint", uploadHandler)` Fetch-API pattern for client-side blob upload to a Vaadin `UploadHandler.inMemory(...)`. Confidence: HIGH.
- Context7 `/jmix-framework/jmix-context7` (Jmix 2.8 docs) — verified: `ViewNavigators.detailView(this, X.class).newEntity().withInitializer(e -> {...}).navigate()`; `dialogWindows.detail(...)` variant; `@EntityPolicy(EntityPolicyAction.{CREATE,UPDATE,DELETE,READ})`, `@EntityAttributePolicy(action = MODIFY)`. Confidence: HIGH.
- Project memory feedback notes — applied: `feedback_jmix_upload_receiver_deprecated` (keep `UploadHandler.toFile/inMemory`, NOT `setReceiver`); `feedback_jmix_unconstrained_for_system_writes` (use `UnconstrainedDataManager` for audit writes); `feedback_no_archunit` (no new ArchUnit rules); `feedback_spi_baseline_builtin` and `feedback_reuse_jmix_builtins` (no new SPI for mutation tools — use Spring AI's existing tool pipeline); `feedback_jmix_first_ui` (Flow XML/components, not raw Vaadin). Confidence: HIGH (load-bearing project conventions).
- v1.0 codebase audit — verified: `ai-agent/ai-agent/ai-agent.gradle` already declares `spring-ai-starter-vector-store-pgvector:1.1.4` (api), `spring-ai-rag:1.1.4` (api), `spring-ai-tika-document-reader:1.1.4`, `spring-ai-client-chat:1.1.4`, `spring-ai-model-chat-memory-repository-jdbc:1.1.4`. `ai-agent/ai-agent-starter/ai-agent-starter.gradle` already declares `spring-ai-starter-model-openai:1.1.4` and `spring-ai-starter-model-chat-memory-repository-jdbc:1.1.4`. Vaadin `hilla` / `hilla-dev` / `copilot` are excluded at the configurations level (`ai-agent.gradle:121-124`). Confidence: HIGH.

## Open Questions for Roadmapper / Requirements Step

These do not block stack selection but should be resolved before phase planning:

1. **Mutation tool depth** — does v1.1 ship `create_record` + `update_record` only, or also `delete_record` and `add_to_collection`? PROJECT.md says "create / update / related-write / delete?" with the question mark. Roadmap should pick the exact set (recommendation: ship CREATE and UPDATE; defer DELETE to v1.2 unless host demand surfaces). No stack delta either way.
2. **Intent-extraction provider routing** — should structured-output extraction default to the same per-request `ChatOptions` model as chat, or pin to a known-strong-at-JSON model (e.g., `gpt-4o-mini` over OpenRouter) regardless of the user's chat-model parameter? Recommendation: default to the chat parameter, allow override via `jmix.ai-agent.intent.model`. No stack delta.
3. **STT recording UX** — push-to-talk (mousedown/mouseup) or click-to-toggle (start/stop)? Push-to-talk is simpler, click-to-toggle handles long dictation better. Recommendation: click-to-toggle with a 60-second hard cap. No stack delta — both wire through `MediaRecorder.start()` / `stop()`.
4. **Floating launcher placement** — can the host position it, or is it always bottom-right? Recommendation: configurable corner, default bottom-right. No stack delta.

---

*Stack research for: v1.1.0 of ai-agent-core (Jmix AI Copilot)*
*Researched: 2026-04-26*
*Confidence: HIGH (Context7-verified for all four library surfaces; HIGH for project-memory-derived anti-list)*
