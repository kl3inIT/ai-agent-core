---
slug: ai-parameters-jpql-unknown
status: resolved
trigger: |
  java.lang.IllegalArgumentException: Problem compiling [select count(e) from ai_AiParameters e]
  [21, 36] The abstract schema type 'ai_AiParameters' is unknown.
created: 2026-04-23T00:00:00Z
updated: 2026-04-23T00:00:00Z
---

# Debug Session: ai-parameters-jpql-unknown

## Symptoms

- App startup could fail when probing/seeding `AiParameters`.
- Resolver/seeder logic assumed the JPQL entity-name query always compiles.

## Root Cause

- `DefaultParamsSeeder` used a string JPQL count query (`select count(e) from ai_AiParameters e`) as an idempotency probe.
- `AiParametersResolver.resolveActive()` and seeding logic failed hard on persistence/query initialization failures instead of failing open to defaults.

## Resolution

1. `AiParametersResolver.resolveActive()` now catches runtime persistence failures and falls back to default in-memory parameters.
2. `DefaultParamsSeeder` now probes via typed fluent loading (`dataManager.load(AiParameters.class).all().maxResults(1)`), and skips seeding on probe/save failures with warnings instead of aborting startup.
3. Added/updated `DefaultParamsSeederTest` coverage for probe-failure skip behavior.

## Files

- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/DefaultParamsSeeder.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/parameters/DefaultParamsSeederTest.java`

