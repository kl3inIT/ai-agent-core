# Version Matrix

**Last verified:** 2026-04-18
**Valid until:** 2026-05-18 — re-verify Spring AI BOM monthly while Spring AI 1.x is still actively releasing minor versions on the Boot 3.x line.

| Component | Version | Source / Pin | Verification |
|-----------|---------|--------------|--------------|
| Java toolchain | 17 | Repo root `build.gradle` / `CLAUDE.md` | `java -version` |
| Gradle wrapper | 8.x (project default) | `gradle/wrapper/gradle-wrapper.properties` | `./gradlew -v` |
| Jmix | 2.8.0 | `ai-agent/build.gradle` `jmix { bomVersion = '2.8.0' }` + `jmix-app/build.gradle` same | — |
| Spring Boot | 3.4.x (transitive) | Transitive via `io.jmix.bom:jmix-bom:2.8.0` | see curl below |
| Spring Framework | 6.2.16 | Transitive via Jmix BOM + Spring Boot | `./gradlew :ai-agent:dependencies --configuration compileClasspath \| grep spring-context` |
| Spring AI BOM | 1.0.2 | `ai-agent/build.gradle` subprojects `ext.springAiVersion` | see curl below |
| `spring-ai-client-chat` | 1.0.2 (via BOM) | `ai-agent/ai-agent/ai-agent.gradle` | — |
| `spring-ai-starter-model-openai` | 1.0.2 (via BOM) | `ai-agent/ai-agent-starter/ai-agent-starter.gradle` | — |
| `spring-ai-test` | 1.0.2 (via BOM) | `ai-agent-starter.gradle` (testImpl) | — |
| Vaadin Flow | Per Jmix 2.8 BOM | Transitive via `io.jmix.flowui:jmix-flowui-starter` | — |
| OpenRouter base-url | `https://openrouter.ai/api/v1` (default; re-validate on first live run) | `jmix-app/src/main/resources/application.yaml` + `ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties` | `./gradlew :ai-agent:liveTest` with `OPENROUTER_API_KEY` set |
| Default OpenRouter model | `openai/gpt-4o-mini` | `application.yaml` env-var default (`OPENROUTER_MODEL`) | — |

## Re-verification Commands

Run these before bumping Spring AI or Jmix; update this file if any version shifts.

```bash
# Spring AI BOM latest / release on Maven Central
curl -s https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml \
  | grep -E '<(latest|release)>'

# Jmix BOM -> Spring Boot pin
curl -s https://global.repo.jmix.io/repository/public/io/jmix/bom/jmix-bom/2.8.0/jmix-bom-2.8.0.pom \
  | grep -B1 -A1 spring-boot-dependencies
```

## Known Assumptions

| ID | Claim | Validated | Notes |
|----|-------|-----------|-------|
| A1 | `spring-ai-test:1.0.2` surface (`MockChatModel` etc.) | Not exercised — not needed | Plan 03 used primary `Mockito.mock(ChatModel.class)` path which compiled + passed first run. Fallback to `spring-ai-test` types never triggered. |
| A2 | OpenRouter `base-url` + `completions-path` combo | NOT empirically validated | No `OPENROUTER_API_KEY` available during Plan 03 executor run. Default `https://openrouter.ai/api/v1` stands. First manual `./gradlew :ai-agent:liveTest` with a real key validates; if 404, flip to `/api` and update this row. |

## Upgrade Checklist

- [ ] Re-run re-verification commands above; update versions in table
- [ ] Bump `springAiVersion` in `ai-agent/build.gradle` (single source of truth)
- [ ] Run `./gradlew :ai-agent:test` — must be green
- [ ] Run `./gradlew :ai-agent:liveTest` with `OPENROUTER_API_KEY` set — must be green
- [ ] Run consumer-smoke per [`docs/consumer-smoke.md`](consumer-smoke.md) — `jmix-app` must boot with `ChatService` bean present
- [ ] Confirm `spring-context` still resolves to the Jmix-BOM-baseline version (currently 6.2.x) — a jump to 7.x indicates an incompatible Spring AI major (see Plan 01-01 deviation)
