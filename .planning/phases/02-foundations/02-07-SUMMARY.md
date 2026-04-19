---
phase: 02-foundations
plan: 07
subsystem: starter/auto-configuration
tags: [spring-boot, auto-config, spi, defaults]
requirements: [SPI-01, SPI-02, SPI-03, SPI-05, SPI-06, SPI-07]
dependency_graph:
  requires:
    - "02-02 SPI interfaces (com.vn.agent.spi.*)"
    - "AIAutoConfiguration (sibling, existing)"
  provides:
    - "No-op defaults for all 6 SPIs so hosts boot without boilerplate"
    - "Override point: host-declared bean of same type wins via @ConditionalOnMissingBean"
  affects:
    - "Phase 3-6 consumers that inject any SPI bean"
tech_stack:
  added: []
  patterns:
    - "Spring Boot @AutoConfiguration + AutoConfiguration.imports"
    - "@ConditionalOnMissingBean override pattern"
    - "@AutoConfigureAfter to enforce ordering with sibling auto-config"
key_files:
  created:
    - "ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java"
  modified:
    - "ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
decisions:
  - "Separate auto-config class (not inside AIAutoConfiguration) — hosts can exclude just the defaults via @EnableAutoConfiguration(exclude=...)"
  - "No @Import(AIConfiguration.class) here — AIAutoConfiguration already imports it; re-import would double-wire"
  - "Default ToolGuard is permissive (allow-all); documented as intentional per threat T-02-SPI-DEF-01 — hosts opt in to guarding"
metrics:
  duration: "~5 min"
  completed: "2026-04-19"
  tasks_completed: 2
  files_touched: 2
---

# Phase 02 Plan 07: SPI Defaults Auto-Configuration Summary

Ship `SpiDefaultsAutoConfiguration`: a single Spring Boot `@AutoConfiguration` exposing 6 `@ConditionalOnMissingBean` no-op defaults (ToolContributor, ContextContributor, PromptContextContributor, ToolGuard, AuditListener, CustomIngester) so host apps can inject every SPI without declaring a bean, while `@ConditionalOnMissingBean` lets them override any one individually.

## What Was Built

1. **`SpiDefaultsAutoConfiguration.java`** — `@AutoConfiguration` annotated, `@AutoConfigureAfter(AIAutoConfiguration.class)` to preserve ordering. Six `@Bean @ConditionalOnMissingBean` methods, each returning a minimal no-op implementation:
   - `defaultToolContributor()` → `Collections::emptyList`
   - `defaultContextContributor()` → lambda that does nothing with the bag
   - `defaultPromptContextContributor()` → returns `""`
   - `defaultToolGuard()` → permissive check (never throws)
   - `defaultAuditListener()` → swallows the audit id
   - `defaultCustomIngester()` → `id="noop"`, empty `read()` list
2. **`AutoConfiguration.imports`** — appended `com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration` after the existing `AIAutoConfiguration` entry. Both classes now discoverable by Spring Boot 3 auto-configuration scan.

## Deviations from Plan

None — plan executed exactly as written. Verbatim body from `02-RESEARCH.md` applied.

## Commits

- `dc690ac` — feat(02-07): add SpiDefaultsAutoConfiguration with 6 no-op defaults

## Verification

- File structure: class sits next to sibling `AIAutoConfiguration` in `com.vn.autoconfigure.agent`.
- `@ConditionalOnMissingBean` count: 6 — one per SPI.
- No `@Import` present (intentional; sibling handles `AIConfiguration` import).
- Imports file ordering: `AIAutoConfiguration` first, `SpiDefaultsAutoConfiguration` second, trailing newline present.
- Gradle compile skipped per user instruction (node not on PATH); Plan 10 smoke test will auto-wire all 6 beans and assert non-null.

## Threat Flags

None new. Defaults align with threat register entries T-02-SPI-DEF-01..04 already documented in the plan.

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java
- FOUND: ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (updated)
- FOUND commit: dc690ac
