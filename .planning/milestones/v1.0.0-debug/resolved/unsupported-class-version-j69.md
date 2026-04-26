---
slug: unsupported-class-version-j69
status: resolved
trigger: |
  <!-- DATA_START -->
  UnsupportedClassVersionError: com/vn/agent/push/DocumentStatusChangedEvent has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version of the Java Runtime only recognize
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T15:15:00Z
---

# Debug Session: unsupported-class-version-j69

## Symptoms

- **Expected:** Application and dependent modules should run on the configured project Java runtime without bytecode version errors.
- **Actual:** Startup/runtime fails with `UnsupportedClassVersionError` for `com.vn.agent.push.DocumentStatusChangedEvent`.
- **Error:** `...compiled by a more recent version of the Java Runtime (class file version 69.0)...`.
- **Timeline:** Reported on 2026-04-22 (exact first occurrence unknown).
- **Reproduction:** Run application/test path on a JVM older than the bytecode target produced for `ai-agent` classes.

## Current Focus

hypothesis: confirmed.
test: verify effective bytecode major version after build config change.
expecting: classes compile to Java 21 bytecode (major 65), not Java 25 bytecode (major 69).
next_action: closed.

## Evidence

- timestamp: 2026-04-22 — session initialized from `$gsd-debug` report containing class file version 69.0 mismatch on `DocumentStatusChangedEvent`.
- timestamp: 2026-04-22 — `./gradlew -version` showed Gradle launcher/daemon using JDK 25 (`temurin-25.0.2`), so without explicit `--release` classes were emitted as major 69.
- timestamp: 2026-04-22 — before fix, `:ai-agent:ai-agent:compileJava` used JDK 25 and produced bytecode incompatible with lower runtimes.
- timestamp: 2026-04-22 — after fix, `:ai-agent:ai-agent:compileJava` and `:jmix-app:compileJava` succeeded.
- timestamp: 2026-04-22 — `javap -verbose .../DocumentStatusChangedEvent.class` reports `major version: 65` (Java 21).

## Eliminated

- hypothesis: none yet.

## Resolution

**Root cause:** build configuration did not pin Java release/toolchain, so modules were compiled by the machine's active JDK 25, producing class file version 69. Runtime environments on Java 21 or lower then failed with `UnsupportedClassVersionError`.

**Fix:** pinned compile target to Java 21 in both build entry points:
- `ai-agent/build.gradle`: `java.toolchain` set to 21 and `JavaCompile.options.release = 21`.
- `jmix-app/build.gradle`: `java.toolchain` set to 21 and `JavaCompile.options.release = 21`.

**Verification:**
1. `./gradlew :ai-agent:ai-agent:clean :ai-agent:ai-agent:compileJava` passed.
2. `./gradlew :jmix-app:compileJava` passed.
3. `javap -verbose ai-agent/ai-agent/build/classes/java/main/com/vn/agent/push/DocumentStatusChangedEvent.class` shows `major version: 65`.
