---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 07
subsystem: chat-service
tags: [spring-ai, audit, chat-service, application-event, classifier]
requires:
  - AuditWriter.writeAuditEvent(String kind, ...) overload (Plan 16-01)
  - AuditKind.MODEL_VALIDATION_FAILURE constant (Plan 16-01)
  - AI_AGENT_AUDIT_EVENT.KIND widened to varchar(32) (Plan 16-02)
  - ChatModelCatalog @PostConstruct drift gate against defaults.model() (Plan 16-03)
  - ParametersDetailView ComboBox with allowCustomValue (Plan 16-05)
  - KnobInventory @KnobMetadata coverage (Plan 16-06)
provides:
  - AiParametersResolver.fallbackModel() accessor (D-05 reissue source)
  - DefaultChatServiceImpl bad-model classifier (isBadModelException + matchesBadModelShape)
  - DefaultChatServiceImpl one-shot reissue at BLK-01 chokepoint (executeBlockingTurn)
  - MODEL_VALIDATION_FAILURE audit row (P-22 sanitised body — HTTP status + class name only)
  - ChatModelFallbackAppliedEvent (new ApplicationEvent type — NOT AiSettingsChangedEvent)
  - ChatPanelFragment @EventListener for the fallback Notification surface (MODEL-02)
  - 2 D-09 locale keys × 2 bundles (chat.error.modelValidationFailure + chat.notice.modelFallbackApplied)
  - DefaultChatServiceImplModelValidationFallbackTest flipped green (11 methods — 7 spec + 4 classifier statics)
affects:
  - DefaultChatServiceImpl (one new constructor parameter — ApplicationEventPublisher)
  - DefaultChatServiceImplStreamFallbackTest, DefaultChatServiceIntentRoutingTest,
    AskTypedRetryTest, ChatServiceFilterParamContractTest (constructor arg additions)
  - ChatPanelFragment (one additive @EventListener — Plan 12 fragment shape preserved)
tech_stack_added: []
patterns_used:
  - "Pattern K: AuditWriter.writeAuditEvent(kind, ...) overload call site (Plan 16-01 surface)"
  - "@EventListener fragment fan-out (mirrors AiTaskFileDeletedUiEvent → ChatPanelFragment)"
  - "Cause-chain walker (depth-bounded at 5) for both RestClientResponseException + NonTransientAiException wrappers (codex HIGH Concern #8)"
  - "Defensive fallback==offendingModel guard (codex MEDIUM — loop hazard)"
  - "Rule 3 workaround — pure JUnit + Mockito for the test (Spring context boot blocked by pre-existing Phase 11/13 regression; consistent with every prior Plan in this phase)"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatModelFallbackAppliedEvent.java
key_files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplModelValidationFallbackTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceIntentRoutingTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
decisions:
  - "fallbackModel() is a separate AiParametersResolver accessor returning defaults.model() directly — does NOT route through effectiveModel(resolveActive()) (RESEARCH Open Question 1 — locked decision)"
  - "Defensive `fallback != null && !fallback.equals(model)` guard at the call site lets the caller propagate the original exception when the configured fallback is itself the bad model (no silent self-loop on misconfigured defaults.model)"
  - "ApplicationEventPublisher injected into DefaultChatServiceImpl for the NEW ChatModelFallbackAppliedEvent (NOT AiSettingsChangedEvent). The SecretRedactionInvariantsTest single-publish-site source-scan keys off the literal `new AiSettingsChangedEvent` regex, so adding a new event type is invariant-clean"
  - "ChatPanelFragment notification surface uses Notifications + chat.notice.modelFallbackApplied (LUMO_WARNING, 8s duration). The longer chat.error.modelValidationFailure key lands in both bundles for the audit row's operator-visible context but is NOT rendered as a separate persistent banner (no existing status-row analogue in the fragment for the MODEL-02 surface; the audit row + the toast suffice — codex HIGH Concern #9 is satisfied by the toast firing)"
  - "Test infrastructure remains pure JUnit + Mockito (consistent with the documented Phase 11/13 Spring-context boot regression that blocks @SpringBootTest in this module — same workaround Plans 13.1-06/07, 14-01/02, 16-01/02/03/04/05/06 used)"
  - "ChatModelFallbackAppliedEvent publication failure is caught + logged but does NOT break the successfully-recovered turn (operator just loses the toast; the audit row is the durable record)"
  - "MODEL_VALIDATION_FAILURE audit-row failure is also caught + logged — the reissue turn still ships even if audit write fails (the user gets their answer; the operator just loses the correlated MODEL_VALIDATION_FAILURE row)"
  - "DTO model field on the recovered turn reports the FALLBACK model id (the one the successful response came from), not the original offending model"
  - "The streaming-path .stream() chain is intentionally NOT wrapped with the bad-model classifier — Plan 16-07 owns only the BLK-01 chokepoint per RESEARCH §3 + the depends_on graph; streaming bad-model errors flow through the existing mapToStreamingError → chatView.error.generic surface. A future plan can extend the classifier to the streaming Flux if telemetry shows the same failure mode hits the streaming path"
metrics:
  duration: ~50 min
  tasks_completed: 2
  files_created: 1
  files_modified: 10
  completed_date: 2026-05-13
---

# Phase 16 Plan 07: MODEL-02 Bad-Model Catch + One-Shot Reissue Summary

Land the MODEL-02 catch + one-shot reissue inside `DefaultChatServiceImpl.executeBlockingTurn(...)` (the Phase 13.1 BLK-01 chokepoint). Provider HTTP failures with status ∈ {400, 404, 422} AND case-insensitive `model` substring on body/message trigger exactly one reissue against `AiParametersResolver.fallbackModel()` (which returns `defaults.model()` directly — never re-resolves through the active profile, RESEARCH Open Question 1 loop hazard avoided). The MODEL-02 user-visible notification is wired in this phase (NOT deferred) via a fresh `ChatModelFallbackAppliedEvent` published after every successful reissue; `ChatPanelFragment` listens, filters by conversation, and surfaces the `chat.notice.modelFallbackApplied` Vaadin toast. The `MODEL_VALIDATION_FAILURE` audit row uses the Plan 16-01 kind-parameter overload, carries P-22-sanitised body (HTTP status + sanitised exception class name only — raw response body NEVER persisted), and correlates with the recovered CHAT row via the same `runId`. Saved `AiParameters.bodyYaml.model` is NEVER mutated.

## What Shipped

### Task 1 — `AiParametersResolver.fallbackModel()` accessor (commit `a981600`)

Added a public method to `AiParametersResolver`:

```java
public String fallbackModel() {
    return defaults.model();
}
```

Javadoc anchors the rationale: bypasses the active-profile read-through chain so the bad-model reissue never loops back to the same bad model id. The defensive guard `fallback.equals(offendingModel)` lives at the caller (so the caller can choose to surface the original exception when defaults.model is also the offending id), keeping this accessor intentionally inert apart from the `defaults.model()` delegation.

### Task 2 — Classifier-guarded catch + one-shot reissue + audit + locale + notification + test (commit `fd6b10f`)

**Step A — `executeBlockingTurn` refactor at lines 358-449:** extracted the `chatClient.prompt()...call().chatClientResponse()` chain into `invokeBlockingChatClient(...)` so the bad-model reissue can hit the same builder with a different model id without duplicating the 30-line prompt-shape declaration. Wrapped the original call site in a `try { ... } catch (RuntimeException providerEx) { ... }`:

- Classifier (`isBadModelException`) — `static`/package-private (opencode Suggestion #4 — unit-testable in isolation). Walks the cause chain depth-bounded at 5 for **either** a direct `RestClientResponseException` **or** a `NonTransientAiException` wrapping one. Returns true IFF status ∈ `{400, 404, 422}` (the `BAD_MODEL_STATUS_CODES` static set) AND body or message contains `"model"` case-insensitive substring. 5xx + 429 + non-classified `RuntimeException`s are rethrown unchanged (Pitfall 6 false-positive guard).
- Reissue uses `parametersResolver.fallbackModel()`. Defensive guard rethrows the original exception when `fallback == null || fallback.equals(model)`.
- `writeModelValidationFailureAudit(runId, userUsername, convId, model, providerEx)` writes the audit row via `auditWriter.writeAuditEvent(AuditKind.MODEL_VALIDATION_FAILURE, ...)` (the kind-parameter overload added by Plan 16-01). The body carries `model=<offending-id> status=<status> error=<simpleName>` — never the raw response body (P-22 / T-16-04 mitigation).
- `eventPublisher.publishEvent(new ChatModelFallbackAppliedEvent(this, runId, convId, model, fallback))` fires AFTER the reissue succeeds — wraps the publish in try/catch so a misbehaving listener cannot break the successfully-recovered turn.
- DTO returned by the method reports the `effectiveModel` (fallback after recovery, original on the happy path).

**Step B — `ChatModelFallbackAppliedEvent` (new file):** a fresh `ApplicationEvent` subclass in `com.vn.agent.orchestration` carrying `(runId, conversationId, offendingModel, fallbackModel)`. Class-level Javadoc anchors why this is a separate type from `AiSettingsChangedEvent` (the Plan 16-06 `SecretRedactionInvariantsTest.singlePublishSiteForAiSettingsChangedEvent()` source-scan keys off the literal `new AiSettingsChangedEvent` regex, so adding a new event type is invariant-clean).

**Step C — `ChatPanelFragment.onChatModelFallbackApplied(...)`:** one new `@EventListener` method added next to the existing `onTaskFileDeleted(...)` handler. Mirrors the same conversation-id filter pattern. Renders `notifications.create(messages.getMessage("chat.notice.modelFallbackApplied"))` with `LUMO_WARNING` variant + 8-second duration via `accessUi(...)` (the existing UI-thread bridge). Class-less `Messages.getMessage(key)` form per project memory `feedback_jmix_messages_over_spring` so the lookup hits the root `com.vn.agent` bundle.

**Step D — Locale keys (D-09):** appended to both `messages_en.properties` and `messages_vi.properties`:
- `chat.error.modelValidationFailure` — operator-visible long-form text (referenced by the audit row resultSummary). EN: *"The configured chat model is not available; this turn was retried with the default model. The Parameters profile was not changed."* VI: *"Mô hình chat đã cấu hình không khả dụng; lượt này được thử lại với mô hình mặc định. Hồ sơ Parameters không bị thay đổi."*
- `chat.notice.modelFallbackApplied` — short user-facing Notification text. EN: *"Used default model for this turn."* VI: *"Đã sử dụng mô hình mặc định cho lượt này."*

**Step E — `DefaultChatServiceImplModelValidationFallbackTest` flipped green:** 11 methods replace the 7 `fail()` scaffolds, organised in two groups:
- **Classifier static unit tests (4 methods)** — exercise `DefaultChatServiceImpl.isBadModelException(Throwable)` directly without booting the chat pipeline: direct RCRE with bad-model shape, NonTransientAiException wrapping RCRE, 5xx-with-"model"-substring rejected, 4xx-without-"model"-substring rejected.
- **End-to-end contract tests (7 methods — every method named in the spec)**: `badModelExceptionTriggersOneShotReissue`, `bothAuditRowsShareRunId`, `savedAiParametersBodyYamlModelIsNotMutated`, `nonBadModelExceptionPropagates`, `_5xxResponseDoesNotTriggerReissue`, `directRestClientResponseExceptionTriggersReissue` (codex HIGH Concern #8), `userVisibleFallbackNotificationFires` (codex HIGH Concern #9 — verifies the `ChatModelFallbackAppliedEvent` is published via the injected `ApplicationEventPublisher` with the correct payload).

**Step F — Constructor parameter migration:** `DefaultChatServiceImpl` gains one new constructor parameter `ApplicationEventPublisher eventPublisher`. The 4 existing constructor call sites in tests are updated to inject `mock(ApplicationEventPublisher.class)`.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:compileJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.DefaultChatServiceImplModelValidationFallbackTest"
→ BUILD SUCCESSFUL (11 methods green)

cd ai-agent && ./gradlew :ai-agent:test \
    --tests "com.vn.agent.DefaultChatServiceImplStreamFallbackTest" \
    --tests "com.vn.agent.DefaultChatServiceIntentRoutingTest" \
    --tests "com.vn.agent.guard.AskTypedRetryTest" \
    --tests "com.vn.agent.rag.ChatServiceFilterParamContractTest"
→ BUILD SUCCESSFUL (BLK-01 stream-fallback + intent-routing + askTyped retry + RAG filter-param contracts all preserved)

cd ai-agent && ./gradlew :ai-agent:test \
    --tests "com.vn.agent.admin.config.SecretRedactionInvariantsTest" \
    --tests "com.vn.agent.admin.config.AiSettingsChangedEventListenerInvariantTest"
→ BUILD SUCCESSFUL (Plan 16-05/16-06 single-publish-site + secret-redaction invariants preserved — confirms the new ChatModelFallbackAppliedEvent type does NOT trigger the AiSettingsChangedEvent source-scan)
```

Plan verify-section grep checks:

- `grep -cE "isBadModelException|writeModelValidationFailureAudit" DefaultChatServiceImpl.java` → **5** (≥ 2 ✓).
- `grep -c "parametersResolver.fallbackModel()" DefaultChatServiceImpl.java` → **1** (≥ 1 ✓).
- `grep -c "Set.of(400, 404, 422)" DefaultChatServiceImpl.java` → **1** (≥ 1 ✓ — status filter rejects 5xx per Pitfall 6).
- `grep -cE "^chat\.(error\.modelValidationFailure|notice\.modelFallbackApplied)" messages_en.properties` → **2** ✓.
- `grep -cE "^chat\.(error\.modelValidationFailure|notice\.modelFallbackApplied)" messages_vi.properties` → **2** ✓.
- `grep -v '^#' DefaultChatServiceImplModelValidationFallbackTest.java | grep -cE "fail\("` → **0** ✓.
- `grep -v '^#' DefaultChatServiceImpl.java | grep -c "getResponseBodyAsString"` → **1** (used ONLY inside `matchesBadModelShape` classifier — body NEVER touches the audit-row body, T-16-04 mitigation preserved).

## Decisions Made

- **`fallbackModel()` as a separate accessor** (RESEARCH Open Question 1 — locked): the resolver exposes `fallbackModel()` returning `defaults.model()` directly, NOT a parameterised version of `effectiveModel(...)`. This makes the loop-avoidance contract explicit and audit-able by name; a future grep over `parametersResolver\.fallbackModel\(\)` finds every site that consumes the bad-model recovery path.
- **Defensive `fallback.equals(model)` guard at the call site, NOT the accessor** (codex MEDIUM Concern): when an admin sets `default-params.yaml.model` to the same id that's currently producing the bad-model error, the guard rethrows the original exception unchanged. The caller chose to surface the failure to the user via the existing `mapToStreamingError` / `ChatResponseDto` paths rather than silently emit a misleading "fallback applied" notification.
- **`ApplicationEventPublisher` injected into `DefaultChatServiceImpl`** (codex HIGH Concern #9): the threat-model entry "Twin-publisher (R2)" notes that this plan does NOT inject the publisher for `AiSettingsChangedEvent`. That invariant is preserved — the publisher is injected for the NEW `ChatModelFallbackAppliedEvent` type only, and the `SecretRedactionInvariantsTest.singlePublishSiteForAiSettingsChangedEvent()` source-scan keys off the literal `new AiSettingsChangedEvent` regex, so the additional `publishEvent(new ChatModelFallbackAppliedEvent(...))` site is structurally invisible to that test (verified by re-running the invariant test post-Plan-07 — BUILD SUCCESSFUL).
- **Notification + audit row, NOT a persistent status-row banner**: the plan listed an optional persistent `chat.error.modelValidationFailure` banner contingent on an existing status-row analogue in the fragment. The fragment has `showStatus(...)` for the streaming-status `<span>` but that surface is owned by the per-turn observability path (cleared on every turn-complete) and not a good fit for a transient bad-model event. The Vaadin Notification + the durable audit row together satisfy codex HIGH Concern #9 (operator + user both see the recovery happened); the longer `chat.error.modelValidationFailure` key still ships in both bundles so the audit row's operator-visible context has a localized label.
- **`Set.of(400, 404, 422)` as a `private static final` class-level constant** (P-22 / Pitfall 6 lock): the structural status-code filter is the load-bearing classifier leg. Pulling it to a named constant makes the rejection set audit-able by future maintainers without having to read the classifier body — and a future grep over `Set.of(400, 404, 422)` finds every place a similar pattern might emerge.
- **Audit-write try/catch wraps `writeModelValidationFailureAudit`** (defensive — D-11 transactional invariant): the existing audit pipeline is `@Transactional(REQUIRES_NEW)` so a failure to commit the MODEL_VALIDATION_FAILURE row would propagate as a `RuntimeException` back into `executeBlockingTurn` and cancel the successfully-recovered turn. The wrap ensures the user-facing turn ships even if the audit row fails to persist; the operator just loses the correlated row (audited via the warn log line).
- **DTO model field reports the EFFECTIVE model (fallback after recovery)**: the `ChatResponseDto.model()` value on a recovered turn is the fallback id, not the offending id. Rationale: callers (chat-history persistence, observability) want to know which model actually produced the answer. The offending id is still visible in the `MODEL_VALIDATION_FAILURE` audit row's resultSummary for forensics.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking issue] Test infrastructure remains pure JUnit + Mockito (consistent with prior Plans 13.1-06/07, 14-01/02, 16-01..06)**

- **Found during:** Task 2 RED — the plan's `<behavior>` block hints `@MockBean` / Pattern I boot-stack, but the Wave-0 scaffold class-level `@Disabled` comment notes "Boot stack mirrors `DefaultChatServiceImplStreamFallbackTest`" which is already pure JUnit + Mockito.
- **Issue:** The ai-agent module's Spring context boot is blocked by the pre-existing Phase 11/13 atmosphere-runtime / agentstoreEntityManagerFactory regression. Verified pre-existing on a clean tree by `git stash` + re-running `AgentToolCallbacksDefaultConfigTest` — still fails with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145`, same blocker that forced every prior Phase 16 plan onto pure-JUnit workarounds.
- **Fix:** The new test mirrors `DefaultChatServiceImplStreamFallbackTest` exactly — mocks every collaborator, constructs `DefaultChatServiceImpl` directly, exercises the classifier statically AND the end-to-end ask path. Contract preserved 1:1 with the plan's spec; when the regression is fixed, the test infrastructure can be promoted to `@SpringBootTest + @MockBean` with a mechanical refactor.
- **Files modified:** `DefaultChatServiceImplModelValidationFallbackTest.java`
- **Commit:** `fd6b10f`

### Plan Scope Additions

**1. [Rule 2 — Missing critical functionality] Added 4 classifier static unit tests beyond the plan's 7-method green set**

- **Found during:** Task 2 TDD-RED — building out the 7 end-to-end methods. The classifier `isBadModelException` is the load-bearing security/correctness gate; without dedicated unit-test coverage at the static level, a future refactor that moves the classifier's status-code/substring logic into a regression could slip past the end-to-end tests (which use `NonTransientAiException` wrapping by default).
- **Issue:** The plan's spec calls for `isBadModelException` as package-private static "for unit-test isolation per opencode Suggestion #4" but does not list the static unit tests in the green set.
- **Fix:** Added 4 classifier static unit tests: direct-RCRE-accepts, NonTransientAi-wrapping-accepts, 5xx-with-"model"-rejected, 4xx-without-"model"-rejected. Total method count is now 11 (4 classifier + 7 end-to-end) — every method from the plan's spec is present and green.
- **Files modified:** `DefaultChatServiceImplModelValidationFallbackTest.java`
- **Commit:** `fd6b10f`

**2. [Rule 1 — Bug-as-API-correction] Updated `getRawStatusCode()` to `getStatusCode().value()`**

- **Found during:** Task 2 — first compileJava after writing the classifier.
- **Issue:** The plan's draft code used `rcre.getRawStatusCode()` but that method is `@Deprecated(for removal in 7.0)` on Spring Web 6.2.17. Using it would compile but trigger a deprecation warning on every build and break on the Spring Web 7.0 upgrade path.
- **Fix:** Use `rcre.getStatusCode().value()` (the non-deprecated API returning the int status). Both produce the same value at runtime.
- **Files modified:** `DefaultChatServiceImpl.java`
- **Commit:** `fd6b10f`

## Authentication Gates

None.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries already listed in `16-07-PLAN.md`:

- **T-16-03 (DoS — Reissue path)** — mitigated as planned: `fallbackModel()` returns `defaults.model()` directly (different from the failing model by construction in the happy admin-misconfig case); classifier filters status codes to `{400, 404, 422}` so 5xx loops don't fire; `_5xxResponseDoesNotTriggerReissue` test enforces. Additional defense: `fallback.equals(model)` defensive guard at the call site rethrows the original on misconfigured defaults.
- **T-16-04 (Information Disclosure — MODEL_VALIDATION_FAILURE audit row)** — mitigated as planned: `writeModelValidationFailureAudit(...)` builds the result summary from HTTP status + sanitised class name only. `grep -c "getResponseBodyAsString" DefaultChatServiceImpl.java` returns **1** — the single use is inside `matchesBadModelShape` (classifier-only read, never written to an audit row). No raw response body persisted.
- **T-16-05 (Elevation of Privilege — per-request model override)** — accepted: the offending model comes from `AiParameters.bodyYaml.model` set by the admin-only Plan 16-05 view; end users still cannot inject a model via chat input.
- **Twin-publisher (R2) — Tampering / single-publish-site invariant** — preserved: the SecretRedactionInvariantsTest single-publish-site source-scan keys off the literal `publishEvent\s*\(\s*new\s+AiSettingsChangedEvent` regex. The new `publishEvent(new ChatModelFallbackAppliedEvent(...))` site uses a different event type and is structurally invisible to the scan — verified by re-running the test post-Plan-07.

No additional surfaces added — `ChatModelFallbackAppliedEvent` carries `(runId, conversationId, offendingModel, fallbackModel)`, all of which are either internally-allocated UUIDs or admin-input model ids (already public taxonomy). No PII, no secrets.

## Known Stubs

None. Production code in all two tasks ships with no stubs — every method has a real body, the classifier matches real `RestClientResponseException` shapes, the audit row uses the real Plan 16-01 overload, the event is published with real arguments, the fragment listener renders a real Vaadin Notification. The `chat.error.modelValidationFailure` locale key is documented as the audit-row operator-visible label rather than a separate rendered banner (decision documented above) — a future plan that adds a persistent status-row analogue to the fragment could trivially wire it to this same key.

## Deferred Issues

- **Pre-existing Phase 11/13 Spring context boot regression** — `AgentToolCallbacksDefaultConfigTest` + sibling `com.vn.agent.tools.mutation.*` tests fail on a clean tree with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145`. Verified pre-existing by `git stash` + re-run on the prior commit. Same regression documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md` and worked around by every prior plan in Phase 16. Out of scope per the SCOPE BOUNDARY rule.
- **Streaming-path bad-model wrapping** — `DefaultChatServiceImpl.stream(...)` runs `chatClient.prompt()...stream().chatResponse()` and is NOT wrapped by the new classifier. RESEARCH §3 + the depends_on graph scope Plan 16-07 to the BLK-01 chokepoint only. Streaming bad-model failures flow through the existing `mapToStreamingError` → `chatView.error.generic` surface. A future plan can extend the classifier to the streaming Flux via `Flux.onErrorResume` if telemetry shows the same failure mode hits the streaming path. Documented as a known follow-up; not blocking.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatModelFallbackAppliedEvent.java` (created) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplModelValidationFallbackTest.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplStreamFallbackTest.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceIntentRoutingTest.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java` (modified) — FOUND

Commits exist:
- `a981600` (Task 1 — fallbackModel accessor) — FOUND
- `fd6b10f` (Task 2 — catch + reissue + audit + notification + locale + test) — FOUND
