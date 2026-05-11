---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 01
subsystem: audit
tags: [audit, hashing, sha256, configuration-properties, aud-07]

# Dependency graph
requires:
  - phase: 07-audit-tree-lite
    provides: AuditWriter + AiAuditEvent infrastructure that Phase 11 will pair with the hasher.
provides:
  - AuditFieldHasher static SHA-256-over-UTF-8 hex utility (com.vn.agent.audit)
  - AiAgentAuditProperties @ConfigurationProperties record (jmix.ai-agent.audit.*)
  - module.properties Spring defaults (hash-sensitive-fields=true, sensitive-fields=)
affects:
  - 11-mutation-capable-built-in-tools
  - MutationErrorTranslator (Phase 11 consumer of AuditFieldHasher)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Static utility precedent: public final class + private constructor + null-safe pure function (mirrors ToolResultFormatter.escapeDataDelimiters)."
    - "@ConfigurationProperties record + resolved* accessors using !Boolean.FALSE.equals default-true idiom (mirrors AiAgentGuardProperties)."
    - "Spring defaults in module.properties; default-params.yaml left untouched (it is strict AiParameters seed YAML)."

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditFieldHasherTest.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties

key-decisions:
  - "AUD-07 hashing utility ships with zero callers in Phase 9 — Phase 11 MutationErrorTranslator wires the consumer (D-18)."
  - "SHA-256 over UTF-8 byte encoding (locale-independent), lowercase 64-char hex via java.util.HexFormat."
  - "No SPI extraction — deferred until a host requests non-SHA-256 hashing (project memory feedback_spi_baseline_builtin)."
  - "Spring config defaults in module.properties (hash-sensitive-fields=true, sensitive-fields=); default-params.yaml is strict AiParameters seed YAML and was NOT touched (planner review incorporation)."

patterns-established:
  - "Pattern 1: Stateless static helper for one-way hashing — no DI, throws IllegalStateException if SHA-256 missing from JVM (defensive but unreachable on any conformant Java SE 8+)."
  - "Pattern 2: @ConfigurationProperties auto-discovery via existing @ConfigurationPropertiesScan on AIConfiguration (no explicit basePackages — covers all of com.vn.agent including new audit subpackage)."

requirements-completed:
  - AUD-07

# Metrics
duration: 6min
completed: 2026-04-26
---

# Phase 9 Plan 1: AUD-07 Plumbing — AuditFieldHasher + AiAgentAuditProperties Summary

**Stateless SHA-256-over-UTF-8 hex utility (`AuditFieldHasher`) and `@ConfigurationProperties` record (`AiAgentAuditProperties`) bound to `jmix.ai-agent.audit.*`, shipped with intentional zero callers in Phase 9 so Phase 11's `MutationErrorTranslator` wiring is a one-liner.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-04-26T20:00:58Z
- **Completed:** 2026-04-26T20:06:37Z
- **Tasks:** 2 (Task 1.1 with TDD: RED + GREEN; Task 1.2)
- **Files created:** 3
- **Files modified:** 1

## Accomplishments

- `AuditFieldHasher` static utility: SHA-256 over UTF-8 → lowercase hex (64 chars), null-safe, deterministic, no Spring DI.
- 7-test unit suite covering null, empty (RFC empty digest), ASCII RFC `"abc"` vector, Vietnamese UTF-8 byte stability (`"Hoạt động"`), 64-char lowercase hex format, determinism, and final-class-no-public-constructor invariants — all PASS.
- `AiAgentAuditProperties` @ConfigurationProperties record bound to `jmix.ai-agent.audit.*` with `resolvedHashSensitiveFields()` defaulting to true and `resolvedSensitiveFields()` defaulting to empty unmodifiable set.
- `module.properties` carries the two Spring configuration defaults; `default-params.yaml` (strict AiParameters seed YAML) left untouched per planner review.
- Boot-context smoke (`FoundationsBootSmokeTest`) passes — confirms `@ConfigurationPropertiesScan` picks up the new record without typo errors.

## Task Commits

Each task was committed atomically (TDD on Task 1.1):

1. **Task 1.1 RED:** `b38a655` — `test(09-01): add failing tests for AuditFieldHasher SHA-256 utility`
2. **Task 1.1 GREEN:** `9cba845` — `feat(09-01): implement AuditFieldHasher SHA-256 over UTF-8 hex utility`
3. **Task 1.2:** `598a56c` — `feat(09-01): add AiAgentAuditProperties record + module.properties defaults`

**Plan metadata commit:** to be created after this SUMMARY.md / STATE.md / ROADMAP.md / REQUIREMENTS.md are written.

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java` — Created. Public final class, private constructor, single static `sha256Hex(String)` method.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java` — Created. Java record bound to `jmix.ai-agent.audit` with two `resolved*` accessors.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditFieldHasherTest.java` — Created. 7 JUnit 5 tests using AssertJ.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — Appended two AUD-07 default keys: `jmix.ai-agent.audit.hash-sensitive-fields=true`, `jmix.ai-agent.audit.sensitive-fields=`.

## Decisions Made

None - followed plan as specified. Plan was already shaped by D-18 from `09-CONTEXT.md` and the planner-review carve-out around `default-params.yaml`; both were honored verbatim.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. RED gate failed compilation as expected (missing class). GREEN gate compiled and all 7 tests passed first run. Boot-context smoke test passed unchanged after properties addition.

## Verification Performed

| Verification | Result |
|---|---|
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.audit.AuditFieldHasherTest"` | PASS (7 tests) |
| `./gradlew :ai-agent:ai-agent:compileJava :ai-agent:ai-agent-starter:processResources` | PASS |
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.FoundationsBootSmokeTest"` | PASS — confirms `@ConfigurationPropertiesScan` discovers `com.vn.agent.audit.AiAgentAuditProperties` and binds `jmix.ai-agent.audit.*` keys without typo errors. |
| `grep -RF 'AuditFieldHasher.sha256Hex' ai-agent/ai-agent/src/main/java` | 0 matches — zero production callers in Phase 9 (intended D-18 posture). |
| `grep 'AuditFieldHasher' ai-agent/ai-agent` | 2 matches — only the class itself + its test. |

## Configuration Property Scan Confirmation

`AIConfiguration` has bare `@ConfigurationPropertiesScan` (no explicit `basePackages`), so by Spring Boot convention it scans the configuration's package — `com.vn.agent` — and all subpackages. The new `com.vn.agent.audit.AiAgentAuditProperties` is therefore auto-discovered without any code change to `AIConfiguration`. The full Spring Boot smoke test (`FoundationsBootSmokeTest`) successfully booted the context with the new record on the classpath, confirming binding works end-to-end.

## Phase 9 Zero-Caller Posture (Intentional)

Per CONTEXT.md D-18 and the plan's stated must-have ("AUD-07 plumbing has zero callers in Phase 9"), neither the hasher nor the properties record is consumed by any production code in this milestone. Phase 11's `MutationErrorTranslator` (mutation pre/post-image diff) is the planned consumer and will wire the call site as a one-liner: `AuditFieldHasher.sha256Hex(value)`. Shipping the plumbing now keeps Phase 11 focused on the mutation surface itself.

## Next Phase Readiness

- AUD-07 hashing contract is locked: SHA-256-over-UTF-8 hex output is deterministic across deployments, locales, and JVMs (mandatory algorithm in every Java SE 8+ runtime). Any change to this output before Phase 11 wiring would require an explicit migration note (per AI-SPEC §6 G-06).
- `jmix.ai-agent.audit.*` namespace established and documented in `module.properties`. Hosts can opt out via `hash-sensitive-fields=false` and populate `sensitive-fields=` with comma-separated attribute paths once Phase 11 publishes the canonical attribute-name list.
- Phase 9 Plan 02 (next) will tackle baseline-context inventory injection (PROMPT-01, PROMPT-02) — independent of this plan.

## Self-Check: PASSED

Verified during execution:
- `[FOUND]` `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java`
- `[FOUND]` `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java`
- `[FOUND]` `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditFieldHasherTest.java`
- `[FOUND]` Modified `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`
- `[FOUND]` Commit `b38a655` (RED gate test)
- `[FOUND]` Commit `9cba845` (GREEN gate implementation)
- `[FOUND]` Commit `598a56c` (Task 1.2 properties + defaults)

## TDD Gate Compliance

Task 1.1 was executed with explicit RED → GREEN sequence:
- RED commit `b38a655` (`test(09-01): ...`) — added the test class while production class did not exist; `compileTestJava` failed with `cannot find symbol` for `AuditFieldHasher`.
- GREEN commit `9cba845` (`feat(09-01): ...`) — added the production class; all 7 tests passed.
- No REFACTOR commit needed — implementation matched the plan's reference shape verbatim and passed all assertions on first run.

Plan-level type is `execute`, not `tdd`, so the plan-level RED/GREEN gate sequence does not apply — only Task 1.1's per-task TDD cycle did.

---

*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Plan: 01*
*Completed: 2026-04-26*
