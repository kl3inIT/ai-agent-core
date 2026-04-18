# Consumer Smoke (PKG-05)

Verifies that `ai-agent-starter` publishes cleanly to Maven Local and that `jmix-app` boots when
consuming it as a standard Maven coordinate rather than a composite-build `includeBuild`. Addresses
the packaging-defect risk that composite builds can mask (see Phase 1 RESEARCH Pitfall 2) and
realizes design decision D-02 (`jmix-app` doubles as the `publishToMavenLocal` consumer smoke).

## Procedure

1. **Publish both add-on modules to Maven Local** (root task publishes all subprojects):
   ```bash
   cd ai-agent
   ./gradlew publishToMavenLocal
   ```
   Verify artifacts appear:
   ```bash
   ls ~/.m2/repository/com/vn/ai-agent/0.0.1-SNAPSHOT/
   ls ~/.m2/repository/com/vn/ai-agent-starter/0.0.1-SNAPSHOT/
   ```
   Each directory should contain a `.jar`, a `.pom`, and a `-sources.jar`.

2. **Toggle composite include off** in the repo-root `settings.gradle`. Comment out the
   `includeBuild 'ai-agent'` line (currently line 4):
   ```groovy
   // includeBuild 'ai-agent'
   ```
   This forces Gradle to resolve `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` from `~/.m2/repository`
   instead of the in-tree project.

3. **Boot the host app:**
   ```bash
   cd jmix-app
   ./gradlew bootRun
   ```
   Watch the startup log for the line emitted by `ChatServiceSmokeRunner` (Plan 01-04 Task 2):
   ```
   ChatServiceSmokeRunner: ChatService bean present: class=com.vn.agent.DefaultChatServiceImpl
   ```
   Log in to <http://localhost:8080> as `admin / admin` to confirm the UI renders. No further
   UI action is required — the injection proof is the log line.

4. **Restore composite build.** Re-enable `includeBuild 'ai-agent'` in `settings.gradle`. The
   composite build is the default dev posture; the Maven-Local consumer smoke is a one-shot
   release-readiness check, not an everyday workflow.

## When to run

- Before tagging any release of `ai-agent-starter`
- After changing `ai-agent/build.gradle` or any sub-project publishing config
- After adding a new sub-module to the add-on (each new module needs its own
  `publishToMavenLocal` output verified)
- When an upgrade-checklist item in [`docs/versions.md`](versions.md) is exercised

## Troubleshooting

- **`~/.m2/repository/com/vn/ai-agent-starter/...` missing the `-sources.jar`** — confirm the
  publishing block in `ai-agent/build.gradle` (or per-module `*.gradle`) includes
  `java { withSourcesJar() }` or the equivalent `maven-publish` component wiring.
- **`NoSuchBeanDefinitionException: ChatService` at boot after toggle-off** — the Maven-Local
  JAR likely predates the current source tree. Re-run `./gradlew publishToMavenLocal` before
  re-booting.
- **App boots but `ChatServiceSmokeRunner` log line missing** — component scan is not picking
  up the runner. Confirm it lives under the `@SpringBootApplication` base package
  (`com.vn.jmixapp.ai`) and carries `@Component`.
- **Gradle keeps resolving the composite build even after commenting `includeBuild`** — stop
  any running Gradle daemons (`./gradlew --stop`) and/or delete `.gradle/` before retrying.
