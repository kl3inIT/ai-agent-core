---
phase: 01-walking-skeleton
plan: 01
subsystem: infra
tags: [gradle, spring-ai, bom, packaging, openrouter]

requires: []
provides:
  - Spring AI BOM pinned at 1.0.2 via dependencyManagement in the add-on subprojects block (Jmix-2.8 / Spring 6.2 compatible)
  - Spring milestone + snapshot repos wired for future forward-compat
  - spring-ai-client-chat on the functional module's classpath (ChatClient API available to DefaultChatServiceImpl in plan 01-02)
  - spring-ai-starter-model-openai + spring-ai-test on the starter module's classpath
  - test task excludes @Tag("live"); dedicated liveTest task runs them on demand
  - OpenRouter wired into jmix-app/application.yaml via spring.ai.openai.* with env-var-backed defaults
affects: [01-02, 01-03, 01-04, all downstream Spring AI consumers]

tech-stack:
  added:
    - org.springframework.ai:spring-ai-bom:1.0.2
    - org.springframework.ai:spring-ai-client-chat
    - org.springframework.ai:spring-ai-starter-model-openai
    - org.springframework.ai:spring-ai-test
  patterns:
    - BOM + ext springAiVersion in subprojects block — consumers inherit consistent Spring AI versions without redeclaring
    - test/liveTest task split via JUnit5 Tag filtering — CI default excludes live LLM calls
    - OpenRouter via standard spring.ai.openai.* keys (base-url override) — no custom starter needed

key-files:
  created:
    - jmix-app/src/main/resources/application.yaml
  modified:
    - ai-agent/build.gradle
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent-starter/ai-agent-starter.gradle

key-decisions:
  - "Spring AI BOM pinned at 1.0.2 (not 2.0.0-M4): 2.0.x targets Spring Boot 4 / Spring Framework 7 and breaks Jmix 2.8's Spring 6.2 baseline (NoClassDefFoundError: ThemeSource during WebApplicationContext bootstrap). 1.0.2 is the latest GA on the Spring Boot 3.4 line."
  - "spring-ai-client-chat lives in the functional module, spring-ai-starter-model-openai in the starter module: keeps the OpenAI/OpenRouter starter out of consumers that only want the SPI."
  - "test task uses excludeTags 'live'; liveTest task uses includeTags 'live' and is opt-in — prevents accidental LLM costs in CI."

patterns-established:
  - "Versioned BOM import: subprojects { ext { springAiVersion }; dependencyManagement { imports { mavenBom ...${springAiVersion} } } }"
  - "Tag-split test tasks: test { useJUnitPlatform { excludeTags 'live' } } + liveTest registered via tasks.register"
  - "OpenRouter config via spring.ai.openai.base-url override — reuses the OpenAI starter for any OpenAI-compatible endpoint"

requirements-completed:
  - PKG-01
  - PKG-02
  - PKG-03
  - TEST-01

duration: ~90min
completed: 2026-04-18
---

# Phase 01 / Plan 01: Gradle BOM + Spring AI Wiring — Summary

**Spring AI classpath is live on a Jmix-2.8-compatible Spring 6.2 stack, with OpenRouter wired and live tests firewalled behind an opt-in task.**

## Performance

- **Started:** 2026-04-18 ~20:50 ICT
- **Completed:** 2026-04-18 ~21:25 ICT
- **Duration:** ~90 min (including a mid-execution architectural checkpoint)
- **Tasks:** 3 original + 1 architectural fix = 4 commits
- **Files modified:** 4 code + 14 planning docs (version pin sweep)

## Accomplishments

1. **BOM + repos** — `ai-agent/build.gradle` imports `spring-ai-bom:${springAiVersion}` inside `subprojects { dependencyManagement { imports { ... } } }`, with `springAiVersion = "1.0.2"` as a single source of truth. Spring milestone + snapshot repos added for forward-compat with future upgrades.
2. **Functional module deps** — `ai-agent/ai-agent/ai-agent.gradle` declares `spring-ai-client-chat` so `ChatClient` / `ChatClient.Builder` are available to `DefaultChatServiceImpl` (plan 01-02). Test task filters out `@Tag("live")`; new `liveTest` task runs only the live tag.
3. **Starter deps** — `ai-agent-starter.gradle` adds `spring-ai-starter-model-openai` (auto-config + `ChatModel` bean) and `spring-ai-test`. Host apps get a fully wired OpenAI/OpenRouter stack by depending on the starter.
4. **OpenRouter wiring** — `jmix-app/application.yaml` sets `spring.ai.openai.base-url=https://openrouter.ai/api/v1`, `api-key=${OPENROUTER_API_KEY:}`, `chat.options.model=${OPENROUTER_MODEL:openai/gpt-4o-mini}`. App boots safely when `OPENROUTER_API_KEY` is unset.

## Deviations

**Architectural checkpoint (Rule 4) — Spring AI version downgrade.**

Original plan pinned `spring-ai-bom:2.0.0-M4`. Verification revealed 2.0.0-M4 transitively forces `spring-context:7.0.5` (Spring Framework 7 / Spring Boot 4), which is incompatible with Jmix 2.8 (Spring 6.2). `:ai-agent:test` regressed with `NoClassDefFoundError: org/springframework/ui/context/ThemeSource` — a class removed in Spring 7.

After user approval, downgraded to `spring-ai-bom:1.0.2`:
- Latest GA on the Spring Boot 3.4 / Spring Framework 6.x line
- `ChatClient` API present and stable (matches plan 01-02 assumptions)
- `:ai-agent:dependencies` confirms `spring-context` resolves to `6.2.16` (Jmix baseline)
- `:ai-agent:test` passes without regression

Version pin swept through all planning artifacts (RESEARCH, PATTERNS, CONTEXT, 4 plans, research/*, ROADMAP, PROJECT) in commit `1146536` so downstream plans read the corrected target.

## Verification

- `./gradlew :ai-agent:compileJava :ai-agent-starter:compileJava` — **succeeds**
- `./gradlew :ai-agent:test` — **BUILD SUCCESSFUL in 43s** (no regression)
- `./gradlew :ai-agent:dependencies --configuration compileClasspath | grep spring-context` — resolves to `6.2.16`

## Commits

| Commit | Description |
|--------|-------------|
| a960779 | feat(01-01): add Spring AI BOM + milestone repos to subprojects |
| c3f8c77 | feat(01-01): wire Spring AI deps + test tag exclusion across ai-agent modules |
| 2239d73 | feat(01-01): wire OpenRouter spring.ai.openai.* config into jmix-app |
| 1146536 | fix(01-01): downgrade Spring AI BOM 2.0.0-M4 → 1.0.2 for Jmix 2.8 compat |

## Downstream Impact

- Plan 01-02 can now compile `DefaultChatServiceImpl` against `ChatClient` / `ChatClient.Builder` from `spring-ai-client-chat` 1.0.2.
- Plan 01-03 live test will run against OpenRouter via the starter's auto-configured `ChatModel`.
- Plan 01-04 consumer-smoke procedure references the 1.0.2 BOM pin; docs sweep already applied.
