---
slug: streaming-auth-not-set
status: resolved
trigger: |
  <!-- DATA_START -->
  Streaming chat request fails with `java.lang.IllegalStateException: Authentication is not set.
  Use SystemAuthenticator in non-user requests like schedulers or asynchronous calls.`
  Stack: CurrentAuthenticationImpl.getAuthentication -> AuthenticationPolicyStore ->
  SecureOperationsImpl.isEntityCreatePermitted -> (entity create inside Spring AI advisor chain
  during streaming). Prior chat_memory table issue already resolved.
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T00:00:00Z
---

# Debug Session: streaming-auth-not-set

## Symptoms

- **Expected:** Sending a prompt via `/ai-agent/chat` streams assistant content end-to-end.
- **Actual:** Stream fails mid-pipeline with `IllegalStateException: Authentication is not set`.
- **Error:** `CurrentAuthenticationImpl.getAuthentication()` throws because the reactor thread
  running the Spring AI advisor chain has no Jmix `CurrentAuthentication` (thread-local).
- **Timeline:** Surfaced after fixing `spring_ai_chat_memory` table (prior session `chat-memory-table-missing`).
- **Reproduction:** Log in at `http://localhost:8080/ai-agent/chat` as admin/admin, send any prompt.

## Current Focus

hypothesis: confirmed.
test: confirmed via source inspection of Jmix + Spring Security jars.
expecting: streaming chat completes after enabling Reactor automatic context propagation.
next_action: rebuild `:ai-agent:publishToMavenLocal`, restart `:jmix-app:bootRun`, verify via Playwright.

## Evidence

- timestamp: 2026-04-22 — `DefaultChatServiceImpl.java:295` calls `systemAuthenticator.begin(userId)` inside `Flux.using` resource factory; `.subscribeOn(chatStreamingScheduler)` only covers the OUTER subscription thread.
- timestamp: 2026-04-22 — Stack trace shows `MessageChatMemoryAdvisor.before` running via `FluxSubscribeOnValue$ScheduledScalar.run` — a DIFFERENT thread than the one that ran `begin()`.
- timestamp: 2026-04-22 — No `Hooks.enableAutomaticContextPropagation()` or `ContextRegistry` usage anywhere in the codebase (grep empty).
- timestamp: 2026-04-22 — No direct `context-propagation` / `micrometer-context` dependency declared in gradle files.
- timestamp: 2026-04-22 — Reference project `jmix-ai-backend` uses `.call()` (blocking), never crosses reactor threads.
- timestamp: 2026-04-22 — Jmix 2.8 source: `SystemAuthenticatorImpl.begin()` writes to `SecurityContextHolder` via `SecurityContextHelper.setAuthentication()`. `CurrentAuthenticationImpl.getAuthentication()` reads from `SecurityContextHolder.getContext().getAuthentication()`. Confirms Jmix uses Spring Security's standard thread-local — no Jmix-private thread-local is involved.
- timestamp: 2026-04-22 — `io.micrometer:context-propagation-1.2.1.jar` is present in the Gradle cache (transitive via Reactor).
- timestamp: 2026-04-22 — `spring-security-core-6.5.8.jar` ships `META-INF/services/io.micrometer.context.ThreadLocalAccessor` registering `org.springframework.security.core.context.SecurityContextHolderThreadLocalAccessor`. `ContextRegistry.getInstance()` auto-discovers it.

## Eliminated

- Hand-rolled scheduler decorator wrapping each task in `systemAuthenticator.runWithUser` — not needed; Reactor's built-in context propagation handles all scheduler hops uniformly including Spring AI's internal HTTP client thread pool.
- `.contextCapture()` per call site — broader Reactor Hook at startup is a single-line change and covers every future streaming pipeline, not just `DefaultChatServiceImpl.stream()`.
- Jmix-private thread-local theory — source inspection confirmed Jmix reads/writes `SecurityContextHolder`.

## Resolution

**Root cause:** Jmix `CurrentAuthenticationImpl.getAuthentication()` reads `SecurityContextHolder.getContext().getAuthentication()`. `DefaultChatServiceImpl.stream()` establishes identity via `systemAuthenticator.begin(userId)` on the outer `chatStreamingScheduler` worker, but Spring AI's `ChatClient.stream()` pipeline executes `MessageChatMemoryAdvisor.before()` (which persists via Jmix `DataManager`) on internal Reactor scheduler threads. Without Reactor automatic context propagation, those inner threads have an empty `SecurityContext`, so the advisor's entity-create permission check throws.

**Fix:** Enabled Reactor automatic context propagation once at startup in `ChatStreamingSchedulerConfig#enableReactorContextPropagation()` (`@PostConstruct` → `Hooks.enableAutomaticContextPropagation()`). Classpath already provides both requirements:
- `io.micrometer:context-propagation` (transitive via Reactor 3.5+).
- Spring Security's `SecurityContextHolderThreadLocalAccessor`, auto-registered via `META-INF/services/io.micrometer.context.ThreadLocalAccessor` in `spring-security-core` 6.5.x.

Reactor now captures the `SecurityContext` on subscription (outer scheduler thread, where `begin(userId)` ran) and restores it on every downstream operator thread, including Spring AI's internal streaming threads. No change to the `stream(...)` pipeline shape was required.

**File touched:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java`.

**Verification plan:**
1. `./gradlew :ai-agent:ai-agent:publishToMavenLocal`
2. Restart `./gradlew :jmix-app:bootRun`
3. Log in at `http://localhost:8080/ai-agent/chat` as admin/admin, send a prompt, confirm stream completes and assistant content renders.
4. `./gradlew :ai-agent:ai-agent:test` — confirm no regressions.
