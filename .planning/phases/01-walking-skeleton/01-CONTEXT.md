# Phase 1: Walking Skeleton & Packaging De-risk - Context

**Gathered:** 2026-04-18
**Status:** Ready for planning

<domain>
## Phase Boundary

Prove the Jmix add-on skeleton works end-to-end with Spring AI 2.0.0-M4 pinned via BOM before any feature code lands. Specifically:

- Keep the existing 2-module shape (`ai-agent` functional + `ai-agent-starter`) — no new modules in Phase 1.
- Pin `spring-ai-bom:2.0.0-M4`, add `https://repo.spring.io/milestone` repo, surface OpenAI-compatible starter.
- A stub `ChatService` bean is injectable in `jmix-app` and reaches `ChatClient.prompt().call().content()` end-to-end through OpenRouter (under `@Tag("live")`) and through a mocked `ChatModel` in CI.
- `publishToMavenLocal` produces artifacts `jmix-app` can consume (verified by toggling `jmix-app` from `includeBuild` to a Maven-Local dependency).
- Three-tier JUnit layout scaffolded (`src/test` default + `@Tag("live")` excluded from CI). `integrationTest` source set is Claude's Discretion — only add it if research shows a concrete need beyond plain `src/test`.
- Version matrix documented: Jmix 2.8 → Spring Boot baseline ≥ 3.4 verified.

**In scope:** BOM wiring, milestone repo, `AutoConfiguration.imports` touch-up for `ai-agent-starter` only, `@JmixModule(dependsOn = …)` on config classes, stub `ChatService` (interface + minimal impl), mock + live smoke tests, Maven-Local consumer smoke, version matrix.

**Out of scope (explicit — belong to other phases or deferred):**
- Adding `ai-agent-flowui` / `ai-agent-flowui-starter` modules — deferred, see D-01.
- Entities, Liquibase migrations, security roles — Phase 2.
- SPI interface definitions — Phase 2.
- Advisor chain, ChatMemory, RAG, tools — Phases 3–6.
- UI (Chat view, admin views) — Phase 7.
- Stripping Vaadin deps from the functional module for headless consumption — deferred until a REST-only consumer use case justifies it.

</domain>

<decisions>
## Implementation Decisions

### Module Structure
- **D-01: Keep the existing 2-module shape (`ai-agent` functional + `ai-agent-starter`) for Phase 1.** The add-on is expected to ship with UI; Jmix add-on conventions already allow UI inside the starter. Do NOT add `ai-agent-flowui` / `ai-agent-flowui-starter` in this phase. Do NOT strip `jmix-flowui-starter` / `jmix-flowui-themes` from `ai-agent/ai-agent.gradle`. A 4-module split is reconsidered only when a concrete REST-only consumer use case surfaces.
  - **Consequences for the planner:** ROADMAP Phase 1 deliverable "ai-agent-flowui + ai-agent-flowui-starter modules added" is NOT executed this phase. Success criteria 1 ("four add-on modules loaded") and 3 ("ai-agent-flowui-starter on the classpath") are revised to the 2-module equivalent. PROJECT.md Key Decision #2 (add flowui modules) moves to Deferred until a later phase owns it. Update ROADMAP.md + PROJECT.md to reflect the 2-module decision as part of Phase 1 work.

### Consumer Smoke
- **D-02: `jmix-app` doubles as the `publishToMavenLocal` consumer smoke.** After `:ai-agent-starter:publishToMavenLocal`, switch `jmix-app/settings.gradle` (or `jmix-app/build.gradle` dep declaration) from the composite `includeBuild` reference to a standard Maven dependency on `com.vn:ai-agent-starter:0.0.1-SNAPSHOT`. Boot the app, verify the stub `ChatService` bean is present. Capture the toggle as a documented Gradle task or README script so it's repeatable. No separate `consumer-smoke/` sub-project, no scripted ephemeral project, no throwaway-only manual checklist.

### ChatService Contract
- **D-03: `ChatService` stub uses the future-shaped signature from day one.**
  - Interface `ChatService` exposed from `ai-agent` functional module (not starter), published in a stable package so `jmix-app` depends on the interface only.
  - Minimal signature (exact Java names TBD by planner):
    - `ChatResponse ask(String message, UUID conversationId, String userKey)` — synchronous, returns a small `ChatResponse` DTO (at minimum: `String content`, optional metadata map).
    - `userKey` accepts a null/anonymous placeholder in Phase 1; wiring to Jmix `CurrentAuthentication` / `UserRepository` is Phase 2+ work.
    - `conversationId` is recorded but not persisted yet (memory/persistence arrives in Phase 3/4).
  - Impl in Phase 1: single `DefaultChatServiceImpl` that calls `ChatClient.prompt().user(message).call().content()` and wraps the string into the DTO. No advisor chain, no memory, no tools, no RAG.
  - This avoids signature churn in Phases 3/4 when memory and user scoping land.

### Test Strategy
- **D-04: Mock-first, spring-ai-test spike, live smoke separated by `@Tag("live")`.**
  - **Primary unit pattern:** `Mockito.mock(ChatModel.class)` (matches `jmix-ai-backend` reference exactly — see `RerankerTest.java`). No new dependency required for this path.
  - **spring-ai-test spike (research task):** Determine whether `org.springframework.ai:spring-ai-test` (version resolved via `spring-ai-bom`, not pinned — see `traffic-law-chatbot/build.gradle`) exposes a usable `MockChatModel` / evaluator surface in M4. If yes, add as `testImplementation` and use it for integration tests where appropriate. If the surface is absent or unstable, stop at the primary Mockito pattern and note the gap in research notes.
  - **Live tier:** Mirror `traffic-law-chatbot`: configure Gradle `test` task with `useJUnitPlatform { excludeTags 'live' }`. `@Tag("live")` smoke test calls OpenRouter with `spring-ai-starter-model-openai` + `base-url` override. Skip cleanly when `OPENROUTER_API_KEY` is absent (follow the `${OPENROUTER_API_KEY:none}` pattern — planner picks the exact skip mechanism).
  - **Reusable `FakeChatModel`:** Not mandatory in Phase 1. Only introduce if a specific integration test needs deterministic canned responses beyond what `mock(ChatModel.class)` provides easily.
  - **`integrationTest` source set:** Defer. Use plain `src/test` + `@Tag("live")` until a second source set earns its keep (matches how `jmix-ai-backend` and `traffic-law-chatbot` both operate today).

### Claude's Discretion
- `ChatResponse` DTO shape (fields, whether it's a record, package location) — planner picks.
- Exact Gradle wiring for the BOM + milestone repo inside the existing `ai-agent/build.gradle` `subprojects { }` block vs. per-module — planner picks.
- Version matrix doc format and location (`docs/versions.md`, new ADR, README section) — planner picks, but it MUST be committed and findable.
- Exact `AutoConfiguration.imports` adjustment and `@JmixModule(dependsOn = …)` dependency list — planner reads existing `AIAutoConfiguration.java` and fills in.
- Naming of the Maven-Local consumer toggle Gradle task (e.g., `verifyMavenLocalConsumer`) — planner picks.
- Skip-if-missing mechanism for live tests (env-var `assumeTrue`, `@EnabledIfEnvironmentVariable`, or properties-file gate) — planner picks the idiomatic JUnit 5 approach.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/ROADMAP.md` §"Phase 1 — Walking Skeleton & Packaging De-risk" — phase goal + original deliverables (note: this CONTEXT.md revises module-split deliverables per D-01).
- `.planning/REQUIREMENTS.md` — PKG-01..05, TEST-01 scaffold.
- `.planning/PROJECT.md` — product context, Key Decisions (entries #1 and #2 re module structure must be updated to reflect D-01).
- `.planning/research/SUMMARY.md` — cross-cutting research summary.
- `.planning/research/STACK.md` — Spring AI 2.x starter naming, BOM pin, repo URLs.
- `.planning/research/ARCHITECTURE.md` — target advisor chain and module responsibility split (informational for Phase 1; implementation is later).
- `.planning/research/PITFALLS.md` — pitfall #5 (packaging), #12 (collisions), #14 (live-LLM-in-CI) directly addressed here.

### Project conventions
- `CLAUDE.md` — Jmix conventions (DataManager only, no Lombok on entities, `msg://` keys in all locales, `get_file_problems` via JetBrains MCP).
- `ai-agent/build.gradle` — current root add-on build (subprojects block is where BOM + milestone repo additions live).
- `ai-agent/ai-agent/ai-agent.gradle` — current functional module deps (NOT to be stripped of Vaadin per D-01).
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — existing imports file to extend.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — existing `@Configuration` to receive `@JmixModule(dependsOn = …)`.

### External reference implementations (pattern source, NOT a dependency)
- `D:/ai/traffic-law-chatbot/build.gradle` — **authoritative pattern for Spring AI 2.0.0-M4 wiring**: `ext { set('springAiVersion', "2.0.0-M4") }`, `dependencyManagement.imports.mavenBom("org.springframework.ai:spring-ai-bom:${springAiVersion}")`, `maven { url = 'https://repo.spring.io/milestone' }`, `maven { url = 'https://repo.spring.io/snapshot' }`, `testImplementation 'org.springframework.ai:spring-ai-test'` (version via BOM), `test { useJUnitPlatform { excludeTags 'live' } }`. Follow this layout where it makes sense in `ai-agent/build.gradle`.
- `D:/ai/traffic-law-chatbot/src/main/resources/application.yaml` — OpenRouter wiring template: `spring.ai.openai.api-key: ${OPENROUTER_API_KEY:none}` + `base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api}` + `options.model` override. Phase 1 lifts only enough for the live smoke test; full profile wiring is Phase 3.
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/src/test/java/io/jmix/ai/backend/retrieval/RerankerTest.java` — canonical `Mockito.mock(ChatModel.class)` pattern for unit tests.
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/build.gradle` — uses `jmix-flowui-test-assist` as the Jmix-side test harness; adopt if view-level tests land (not required in Phase 1).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` — existing `@Configuration` class; extend with `@JmixModule(dependsOn = {...})` rather than creating a new config.
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — existing auto-config, already registered in `AutoConfiguration.imports`; add Spring-AI bean exposure (ChatClient builder, ChatService) here.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`, `messages.properties`, `module.properties` — Jmix add-on scaffolding already in place.
- `jmix-app/` — demo host already depends on `com.vn:ai-agent-starter` via composite build. Doubles as consumer smoke (D-02) and as an injection target for the stub `ChatService`.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/AITest.java` + `AITestConfiguration.java` — existing `@SpringBootTest` scaffolding pattern; extend for the mock-ChatModel smoke.

### Established Patterns
- **Group/namespace:** `com.vn.*` + `group = 'com.vn'` (root build.gradle). Preserve for Phase 1 — renamespacing is out of scope.
- **Jmix projectId:** `'AI'` (root build `jmix { projectId = 'AI' }`). Keep.
- **Gradle structure:** composite build with two `includeBuild`s (`ai-agent/`, `jmix-app/`) at repo root. `ai-agent/` is its own multi-project build (`settings.gradle` includes `ai-agent` + `ai-agent-starter`). Any BOM/repo additions go in `ai-agent/build.gradle` `subprojects { }` or inside each `*.gradle` file; not at the repo root.
- **Test toolchain:** JUnit 5 `useJUnitPlatform`; `spring-boot-starter-test` with `junit-vintage-engine` excluded; HSQLDB for test runtime. Matches jmix-ai-backend reference.

### Integration Points
- `jmix-app/src/main/java/.../*` — where `@Autowired ChatService` gets injected to prove the 4th success criterion (stub injectable end-to-end). Planner picks a minimal Vaadin view or a `CommandLineRunner` — whichever exercises the bean with the least UI noise.
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — entry point for auto-config registration.
- OpenRouter: via `spring-ai-starter-model-openai` with `base-url` override — reuse exactly the `traffic-law-chatbot/application.yaml` pattern (see canonical refs).

</code_context>

<specifics>
## Specific Ideas

- **Align with `traffic-law-chatbot` Gradle pattern explicitly.** The BOM+milestone+spring-ai-test+excludeTags layout there is proven and should be ported almost verbatim into `ai-agent/build.gradle`.
- **Align with `jmix-ai-backend` test pattern.** Plain `@SpringBootTest` + `Mockito.mock(ChatModel.class)` — no evaluation harness, no custom fake-chat-model class unless needed.
- **Do NOT introduce `integrationTest` source set speculatively.** `src/test` + `@Tag("live")` covers Phase 1 needs.
- **Do NOT rename the `com.vn` namespace or `projectId = 'AI'` in this phase.** Any rebrand is a separate decision.

</specifics>

<deferred>
## Deferred Ideas

- **4-module split (`ai-agent-flowui` + `ai-agent-flowui-starter`).** Revisit only when a named REST-only consumer use case justifies it. Until then, UI lives in the starter per D-01.
- **Stripping Vaadin deps (`jmix-flowui-starter`, `jmix-flowui-themes`) from `ai-agent` functional module.** Coupled to the 4-module split; same trigger.
- **Full OpenRouter + profile wiring** (base-url, embedding model, multi-profile `ChatOptions`, per-request model switching). Phase 1 lifts only the minimum for the live smoke. Profile + parameters service is Phase 6.
- **`spring-ai-test` evaluation harness usage** (semantic-similarity, `RelevancyEvaluator`, etc.). Phase 1 only determines presence/shape of the library; application of evaluators is Phase 7+ work.
- **`jmix-flowui-test-assist` adoption** for view-level tests — no views in Phase 1, defer.
- **Rename `com.vn` / `projectId = 'AI'`** to a public-add-on-friendly namespace before first external release.

</deferred>

---

*Phase: 01-walking-skeleton*
*Context gathered: 2026-04-18*
