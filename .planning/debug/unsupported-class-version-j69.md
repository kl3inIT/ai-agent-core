---
slug: unsupported-class-version-j69
status: awaiting_human_verify
trigger: |
  <!-- DATA_START -->
  UnsupportedClassVersionError: com/vn/agent/push/DocumentStatusChangedEvent has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version of the Java Runtime only recognize
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T00:00:00Z
---

# Debug Session: unsupported-class-version-j69

## Symptoms

- **Expected:** Application and dependent modules should run on the configured project Java runtime without bytecode version errors.
- **Actual:** Startup/runtime fails with `UnsupportedClassVersionError` for `com.vn.agent.push.DocumentStatusChangedEvent`.
- **Error:** `...compiled by a more recent version of the Java Runtime (class file version 69.0)...`.
- **Timeline:** Reported on 2026-04-22.
- **Reproduction:** Run application/test path that resolves `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` from mavenLocal.

## Current Focus

reasoning_checkpoint:
  hypothesis: "Stale jars in mavenLocal (~/.m2/.../ai-agent and ai-agent-starter) contain Java 25 bytecode (class file major version 69, 0x45). jmix-app's `repositories { mavenLocal() ... }` resolves these stale jars instead of the composite-built ones, and the JVM loading them rejects v69."
  confirming_evidence:
    - "mavenLocal ai-agent-0.0.1-SNAPSHOT.jar DocumentStatusChangedEvent.class magic shows 00 00 00 45 (= v69, Java 25)"
    - "mavenLocal ai-agent-starter-0.0.1-SNAPSHOT.jar first class is also 00 00 00 45 (v69)"
    - "Fresh gradle build at ai-agent/ai-agent/build/classes/.../DocumentStatusChangedEvent.class shows 00 00 00 41 (= v65, Java 21) — proving current `options.release = 21` in ai-agent subprojects block works"
    - "JAVA_HOME=C:\\Users\\admin\\.jdks\\temurin-25.0.2 (Java 25) — matches the bytecode version produced when the stale jars were published"
    - "Code comment in ai-agent.gradle explicitly notes `this project builds under JDK 25 (class file major version 69)` — confirms the team had previously built without a --release gate"
  falsification_test: "If we delete the stale mavenLocal jars and re-resolve dependencies, a composite-built replacement should be v65; if v69 still appears anywhere the diagnosis is wrong."
  fix_rationale: "The stale mavenLocal artifacts were published before `options.release = 21` was enforced. Republishing (or purging + composite resolution) produces v65 bytecode the runtime can load. Root cause = outdated published artifact, not missing compile setting."
  blind_spots: "Haven't verified that the runtime JVM actually rejecting these jars is NOT Java 25 — if runtime is also Java 25 then v69 would load fine, so the failing runtime must be ≤ Java 24 (likely Spring Boot / IDE configured JRE ≠ JAVA_HOME)."

## Evidence

- timestamp: 2026-04-22 — session initialized from gsd-debug report.
- timestamp: 2026-04-22 — Root build.gradle is single-line `ext.jmixCompositeProjectRoot = true`. settings.gradle uses `includeBuild 'jmix-app'` + `includeBuild 'ai-agent'` (composite build).
- timestamp: 2026-04-22 — ai-agent/build.gradle subprojects block sets `toolchain { languageVersion = JavaLanguageVersion.of(21) }` AND `options.release = 21` on all JavaCompile tasks. Correct config.
- timestamp: 2026-04-22 — ai-agent/ai-agent/ai-agent.gradle contains comment: "this project builds under JDK 25 (class file major version 69)" — referring to the ASM 9.9 requirement. Confirms JDK 25 was used historically.
- timestamp: 2026-04-22 — Fresh build output ai-agent/ai-agent/build/classes/java/main/com/vn/agent/push/DocumentStatusChangedEvent.class: magic ca fe ba be 00 00 00 41 → major 0x41 = 65 → Java 21 ✓ (release flag works).
- timestamp: 2026-04-22 — MavenLocal ~/.m2/repository/com/vn/ai-agent/0.0.1-SNAPSHOT/ai-agent-0.0.1-SNAPSHOT.jar DocumentStatusChangedEvent.class: magic 00 00 00 45 → major 0x45 = 69 → Java 25 ✗.
- timestamp: 2026-04-22 — MavenLocal ai-agent-starter-0.0.1-SNAPSHOT.jar first .class also 0x45 = v69 ✗. Sibling common-lib and toollib jars are 0x3d = v61 (Java 17) — consistent, not affected.
- timestamp: 2026-04-22 — JAVA_HOME=Java 25 → produced v69 when stale jars were published (before options.release=21 was added or in an external build invocation).

## Eliminated

- hypothesis: Current build configuration produces v69 bytecode.
  evidence: Fresh build/classes shows v65 (Java 21). options.release = 21 is effective.
  timestamp: 2026-04-22

## Resolution

**Root cause:** Stale artifacts `com.vn:ai-agent:0.0.1-SNAPSHOT` and `com.vn:ai-agent-starter:0.0.1-SNAPSHOT` in the user's Maven local repository (`~/.m2/repository/com/vn/`) contain class file version 69.0 (Java 25) bytecode. They were published prior to the `options.release = 21` enforcement being added to the root ai-agent subprojects block, using the Java 25 JDK pointed to by `JAVA_HOME`. Downstream consumers (e.g. `jmix-app` which lists `mavenLocal()` as a repository) resolve these stale jars and the JVM runtime rejects v69 with `UnsupportedClassVersionError`.

**Fix:**
1. Delete the stale v69 artifacts from mavenLocal.
2. Rebuild and republish `ai-agent` + `ai-agent-starter` with the current Gradle config (`options.release = 21`, toolchain 21) so mavenLocal now contains v65 bytecode.

**Verification plan:**
1. Delete stale mavenLocal dirs.
2. Run `./gradlew :ai-agent:publishToMavenLocal :ai-agent-starter:publishToMavenLocal` from the ai-agent composite root.
3. Inspect the republished jars — class file major version must be 0x41 (65, Java 21).
4. Rebuild jmix-app and confirm no `UnsupportedClassVersionError` for `DocumentStatusChangedEvent`.

**Files changed:** none (infrastructure / artifact fix).
