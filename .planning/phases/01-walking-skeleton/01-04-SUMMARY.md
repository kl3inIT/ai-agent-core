---
phase: 01-walking-skeleton
plan: 04
subsystem: infra
tags: [docs, consumer-smoke, injection-proof, maven-local, d-01]

requires:
  - phase: 01-walking-skeleton
    provides: ChatService SPI + DefaultChatServiceImpl + AIAutoConfiguration + BOM pin (plans 01-01, 01-02); smoke tests (plan 01-03)
provides:
  - docs/versions.md — version matrix for the supported Jmix/Spring Boot/Spring AI/Java stack
  - docs/consumer-smoke.md — reproducible composite-build ↔ Maven-Local toggle procedure (D-02)
  - ChatServiceSmokeRunner — CommandLineRunner in jmix-app that @Autowireds ChatService and logs the resolved class on startup (injection proof)
  - ROADMAP.md + PROJECT.md updates — D-01 (2-module shape kept, flowui split deferred) reflected
  - .env removed from version control + added to .gitignore (Rule 2 auto-fix during execution)
affects: [02-foundations, 03-metadata-runtime, all downstream plans — they rely on a bootable jmix-app consuming the starter from Maven Local]

tech-stack:
  added: []
  patterns:
    - "Injection smoke via CommandLineRunner: log the resolved bean class on startup without calling it — proves wiring without LLM cost"
    - "Consumer-smoke toggle: settings.gradle commenting out `includeBuild 'ai-agent'` forces Maven Local resolution of the starter"

key-files:
  created:
    - docs/versions.md
    - docs/consumer-smoke.md
    - jmix-app/src/main/java/com/vn/jmixapp/ai/ChatServiceSmokeRunner.java
  modified:
    - .planning/ROADMAP.md
    - .planning/PROJECT.md
    - .gitignore

key-decisions:
  - "D-01 ratified: ai-agent + ai-agent-starter two-module shape is the Phase 1 shipping shape; flowui split deferred to a later phase when a concrete UI consumer justifies it."
  - "ChatServiceSmokeRunner logs the bean class but does NOT invoke chat — keeps default boot cost-free and OPENROUTER_API_KEY-independent."
  - "Human-verify checkpoint executed against Maven-Local-resolved starter (settings.gradle toggled OFF), not the composite build. This is the release-readiness posture consumers will see."

patterns-established:
  - "Startup injection proof: @Component CommandLineRunner that logs `{BeanName}: {SPI interface} bean present: class={impl}` — machine-readable confirmation line for CI / human verify"
  - "Pre-boot hygiene on Windows: kill lingering java.exe + remove .jmix/hsqldb/*.lck before bootRun when a prior run crashed mid-startup"

requirements-completed:
  - PKG-04
  - DOC-01
  - DOC-02

duration: ~60min
completed: 2026-04-18
---

# Phase 01 / Plan 04: Phase Closure — Summary

**`jmix-app` boots with `ChatService` resolved from the Maven-Local `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` artifact — the full end-to-end consumer story for Phase 1 is proven.**

## Performance

- **Started:** 2026-04-18 ~21:25 ICT
- **Completed:** 2026-04-18 ~21:55 ICT (including human-verify)
- **Duration:** ~30min execution + ~30min human-verify (incl. HSQLDB lock diagnosis)
- **Tasks:** 4/4 (3 autonomous + 1 human-verify)
- **Commits:** 4 (docs, runner, ROADMAP/PROJECT, + 1 .env hygiene fix)

## Accomplishments

1. **`docs/versions.md`** — version matrix documenting Jmix 2.8.0 (Spring Boot 3.4 / Spring 6.2), Java 17, Gradle 8.x, Spring AI 1.0.2, Vaadin Flow. Single reference for the phase-1 supported stack.
2. **`docs/consumer-smoke.md`** — consumer-smoke procedure per D-02: `publishToMavenLocal` → toggle `settings.gradle` (`// includeBuild 'ai-agent'`) → `bootRun` → restore. This is the release-readiness check downstream phases will re-run.
3. **`ChatServiceSmokeRunner`** (`jmix-app/src/main/java/com/vn/jmixapp/ai/`) — `@Component CommandLineRunner` that `@Autowireds ChatService` and logs the resolved implementation class on startup. Does NOT call the bean — boot is LLM-cost-free even with `OPENROUTER_API_KEY=none`.
4. **ROADMAP + PROJECT updates** — D-01 (2-module shape kept; flowui split deferred) formally reflected. Downstream phase planning will read the corrected shape.
5. **Rule 2 .env hygiene** (auto-fix deviation) — during Task 1 staging, an empty `.env` was inadvertently tracked. Removed from index + added to `.gitignore` in commit `48dfa63` before the local `.env` (which contains a real `OPENROUTER_API_KEY`) could leak on a subsequent `git add`.

## Human-verify Result

**Status:** ✅ APPROVED

**Confirmation line observed** (logger `com.vn.jmixapp.ai.ChatServiceSmokeRunner`, INFO, startup 2026-04-18 21:55:19 ICT):
```
ChatServiceSmokeRunner: ChatService bean present: class=com.vn.agent.DefaultChatServiceImpl
```

**Boot summary:**
- `./gradlew :ai-agent:publishToMavenLocal :ai-agent-starter:publishToMavenLocal` → `BUILD SUCCESSFUL in 10s`; both artifacts (jar + pom + sources) landed in `~/.m2/repository/com/vn/`
- `settings.gradle` toggled to `// includeBuild 'ai-agent'` → Gradle resolved `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` from Maven Local
- `cd jmix-app && ./gradlew bootRun` → `Tomcat started on port 8080` at 21:55:19, `Started JmixAppApplication in 18.906 seconds`, `Application started at http://localhost:8080`, confirmation line emitted exactly once
- Post-verify: `settings.gradle` restored to include both composite builds (dev posture)

**Incidents during verification (documented for future verify runs):**
- First boot attempt failed with `MetaClass not found for class com.vn.jmixapp.entity.User`, which turned out to be a **cascading symptom** of a stale HSQLDB lock from an earlier aborted run (`.jmix/hsqldb/jmixapp.lck` held by a lingering `java.exe`). Liquibase couldn't initialize the schema → Jmix metadata never registered User → login-view init failed.
- Recovery: `taskkill /F /IM java.exe` + `rm -f jmix-app/.jmix/hsqldb/jmixapp.lck` → clean boot on next attempt. Captured as a documented pre-boot hygiene pattern; consider opening a follow-up plan to migrate `jmix-app` to Postgres if HSQLDB proves chronically flaky on Windows.

## Commits

| Commit | Description |
|--------|-------------|
| 7758aa1 | docs(01-04): version matrix + consumer-smoke procedure |
| 48dfa63 | fix(01-04): untrack .env, add to .gitignore (Rule 2 hygiene) |
| 91ca634 | feat(01-04): add ChatServiceSmokeRunner CommandLineRunner in jmix-app |
| 42783e1 | docs(01-04): reflect D-01 2-module decision in ROADMAP and PROJECT |
| 6d076ea | merge(phase-01): walking skeleton into master |

## Downstream Impact

- Phase 2 can now rely on a **proven injection path**: `jmix-app` can resolve Jmix + Spring AI beans via the Maven-Local starter without the composite build.
- Downstream plans that require `jmix-app` to boot for integration tests should document the HSQLDB pre-boot hygiene (kill stale `java.exe`, delete `*.lck`) or migrate to Postgres.
- The `ChatServiceSmokeRunner` stays in `jmix-app` as a permanent startup health check — if a future plan breaks `ChatService` injection, boot will fail with `NoSuchBeanDefinitionException` before reaching the login view.
