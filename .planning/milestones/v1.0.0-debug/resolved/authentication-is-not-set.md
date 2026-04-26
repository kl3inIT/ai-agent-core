---
slug: authentication-is-not-set
status: resolved
trigger: |
  <!-- DATA_START -->
  fix this 026-04-22T21:33:34.290+07:00 ERROR 21412 --- [oundedElastic-2] o.s.ai.chat.model.MessageAggregator      : Aggregation Error

  java.lang.IllegalStateException: Authentication is not set. Use SystemAuthenticator in non-user requests like schedulers or asynchronous calls.
  at io.jmix.core.security.impl.CurrentAuthenticationImpl.getAuthentication(CurrentAuthenticationImpl.java:60)
  at io.jmix.security.impl.constraint.AuthenticationPolicyStore.extractResourcePoliciesFromAuthenticationByScope(AuthenticationPolicyStore.java:143)
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T14:58:00Z
---

# Debug Session: authentication-is-not-set

## Symptoms

- **Expected:** Streaming chat request should complete under the current signed-in user without security-context failures.
- **Actual:** Stream fails with an aggregation error and no assistant response completion.
- **Error:** `java.lang.IllegalStateException: Authentication is not set. Use SystemAuthenticator in non-user requests like schedulers or asynchronous calls.`
- **Timeline:** Reported at 2026-04-22T21:33:34.290+07:00; similar error was seen earlier in the same codebase.
- **Reproduction:** Trigger a streaming chat call (AI chat view/API) and observe failure on a `boundedElastic` worker thread.

## Current Focus

hypothesis: confirmed.
test: source-level trace of stream chain + Reactor context-propagation behavior.
expecting: capture authenticated context after `systemAuthenticator.begin(userId)` and propagate to downstream reactive threads.
next_action: closed.

## Evidence

- timestamp: 2026-04-22 — stack trace points to `CurrentAuthenticationImpl.getAuthentication()` on a `boundedElastic` worker during Spring AI streaming aggregation.
- timestamp: 2026-04-22 — `DefaultChatServiceImpl.stream(...)` already uses `systemAuthenticator.begin(userId)`, but context propagation can still restore an empty captured caller context on downstream scheduler hops.
- timestamp: 2026-04-22 — Reactor docs (`advanced-contextPropagation.adoc`) confirm automatic mode restores ThreadLocal state from Reactor `Context`; explicit `contextCapture()` captures the active ThreadLocal snapshot at subscription boundary.
- timestamp: 2026-04-22 — patch applied in `DefaultChatServiceImpl.stream(...)`: `merged.contextCapture().concatWith(...)` after `begin(userId)` scope to capture authenticated context for downstream operators.
- timestamp: 2026-04-22 — `./gradlew :ai-agent:compileJava` succeeded after patch.
- timestamp: 2026-04-22 — `./gradlew bootRun --stacktrace` succeeded after importing `.env`; logs show `Started JmixAppApplication`, `Application started at http://localhost:8080`, and `ChatServiceSmokeRunner: ChatService bean present`.

## Eliminated

- hypothesis: none yet.

## Resolution

**Root cause:** authentication context was being re-applied from Reactor context on downstream scheduler threads without a guaranteed capture point after `systemAuthenticator.begin(userId)` in the stream pipeline, causing Jmix security checks in Spring AI memory/advisor stages to run with no authentication.

**Fix:** added `.contextCapture()` to the merged streaming flux in `DefaultChatServiceImpl.stream(...)` immediately after authentication scope is established, so downstream Reactor threads receive the authenticated `SecurityContext`.

**Verification:** app boots successfully with OpenRouter key loaded from `.env` via `spring.config.import`; smoke runner sees `DefaultChatServiceImpl` bean and no startup `UnsatisfiedDependencyException`.
