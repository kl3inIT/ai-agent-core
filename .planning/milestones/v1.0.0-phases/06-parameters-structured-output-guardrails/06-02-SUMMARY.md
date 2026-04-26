---
phase: 06-parameters-structured-output-guardrails
plan: 02
subsystem: [parameters, yaml, seeder, prompt-composition]
tags: [yaml-mapper, crud, seeder, overloads, spi-wiring]
requires:
  - com.vn.agent.parameters.AiParametersBody
  - com.vn.agent.parameters.Overrides
  - com.vn.agent.parameters.ParametersValidationException
provides:
  - com.vn.agent.parameters.AiParametersBodyYamlMapper
  - com.vn.agent.parameters.ParametersService
  - com.vn.agent.parameters.DefaultParamsSeeder
  - com.vn.agent.orchestration.AiParametersResolver#effectiveModel(AiParameters, Overrides)
  - com.vn.agent.orchestration.AiParametersResolver#effectiveSystemPrompt(AiParameters, String, UUID, UUID)
  - classpath:default-params.yaml (bundled starter seed)
affects:
  - com.vn.agent.orchestration.AiParametersResolver (constructor signature: +List<PromptContextContributor>)
  - com.vn.agent.orchestration.AiParametersResolverTest (constructor call updated to pass List.of())
tech-stack:
  added:
    - com.fasterxml.jackson.dataformat:jackson-dataformat-yaml (BOM-versioned)
    - jakarta.validation:jakarta.validation-api (BOM-versioned)
  patterns:
    - "Jackson YAML + FAIL_ON_UNKNOWN_PROPERTIES + explicit UnrecognizedPropertyException catch for stable i18n key"
    - "Jakarta Validator injected into mapper; runs on both readValue and writeAsYaml"
    - "SystemAuthenticator.runWithSystem(Runnable) wrapping @EventListener(ApplicationReadyEvent.class) seed path"
    - "Idempotent count probe via dataManager.loadValue('select count(e) ...', Long.class).one()"
    - "Exactly-one-active invariant via single REQUIRED transaction: flip-all-then-set-one (D-06)"
    - "Additive constructor expansion with sorted immutable List.copyOf snapshot for SPI wiring"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBodyYamlMapper.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/DefaultParamsSeeder.java
    - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java
decisions:
  - "SPI's PromptContextContributor.fragment() is zero-arg in Phase 2; per plan guidance 'match existing signature EXACTLY', the resolver's effectiveSystemPrompt(params, userId, conversationId, runId) accepts the contextual args for Plan 04's call site but does NOT forward them to contributors today. No nested RunContext record added to the SPI."
  - "SystemAuthenticator.runWithSystem accepts Runnable (void return); the plan's Supplier-style '() -> { ... return null; }' lambda was incompatible, so the seeder body moved into a private doSeedIfEmpty() method invoked via method reference. Matches the existing codebase idiom in FoundationsBootSmokeTest, AuditListenerDispatcherTest, PromptInjectionHarnessTest."
  - "Idempotency count probe uses dataManager.loadValue('select count(e) from ai_AiParameters e', Long.class).one() — the Jmix scalar-query idiom. Alternative .list().size() > 0 was considered; loadValue is the canonical scalar path and avoids materialising any rows."
  - "jackson-dataformat-yaml + jakarta.validation-api declared in a dedicated Plan 06-02-labeled block at the bottom of the dependencies {} stanza so Plan 06-03 can append guard-specific deps cleanly alongside without edit conflict."
metrics:
  duration: "~20 minutes"
  completed: "2026-04-21"
  tasks: 3
---

# Phase 6 Plan 02: Parameters Layer Summary

Ship the YAML mapper, CRUD + exactly-one-active service, idempotent default-profile seeder, and two AiParametersResolver overloads (Overrides model + PromptContextContributor chain) that Plan 04's orchestration wiring will consume. All behaviour self-contained on top of the Plan 06-01 foundation types.

## What Shipped

### YAML mapper (`com.vn.agent.parameters.AiParametersBodyYamlMapper`)

- `@Component` holding a static final `ObjectMapper(new YAMLFactory())` with `WRITE_DOC_START_MARKER` disabled and `FAIL_ON_UNKNOWN_PROPERTIES` enabled.
- Explicit two-catch form on every read path:
  - `UnrecognizedPropertyException` → `ParametersValidationException("ai-agent.parameters.yaml.unknown-key: " + propertyName)`.
  - `IOException` → `ParametersValidationException("ai-agent.parameters.yaml.invalid: " + message)`.
- Jakarta `Validator` injected via constructor; runs on every `readValue(InputStream)`, `readValue(String)`, and `writeAsYaml(AiParametersBody)`. Violation messages joined into the thrown exception payload.
- `writeAsYaml` re-validates before serialising so the seeder and CRUD-write paths cannot persist a body that fails validation even if the caller synthesised the record.

### ParametersService (`com.vn.agent.parameters.ParametersService`)

- `@Service` with `DataManager` + `Metadata` + `AiParametersBodyYamlMapper` constructor injection.
- `create(profileName, bodyYaml, boolean active)` — strict-on-write validation, canonical YAML round-trip through `writeAsYaml`, `metadata.create(AiParameters.class)`, starts inactive and delegates to `setActive` when `active=true`.
- `update(UUID, newBodyYaml)` — re-validates and stores canonical form.
- `delete(UUID)` — refuses to drop the currently-active profile.
- `setActive(UUID targetId)` — `@Transactional(propagation = Propagation.REQUIRED)`, loads every row with `active=true`, flips them all to `FALSE`, saves via `dataManager.save(array)`, then loads and activates the target. Flip-then-set ordering observable in the bytecode (grep-verified).
- `loadById(UUID)` + `listAll()` — `@Transactional(readOnly = true)` accessors.

### DefaultParamsSeeder (`com.vn.agent.parameters.DefaultParamsSeeder`)

- `@Component` with `@EventListener(ApplicationReadyEvent.class)` on `seedIfEmpty()`.
- Wraps the work in `systemAuthenticator.runWithSystem(this::doSeedIfEmpty)` — matches every existing `runWithSystem` call site in the codebase.
- Idempotency probe: `dataManager.loadValue("select count(e) from ai_AiParameters e", Long.class).one()`; returns if `count > 0`.
- Loads `classpath:default-params.yaml` via `ResourceLoader`; logs a warning and no-ops if the resource is absent (supports host-only classpath configurations).
- Validates + canonicalises through the YAML mapper, `metadata.create(AiParameters.class)`, sets `profileName="default"` + `active=true`, persists via `dataManager.save`.

### AiParametersResolver overloads

- Constructor signature grew from 3 args to 4: `(DataManager, Metadata, AiAgentDefaultsProperties, List<PromptContextContributor>)`. Contributors are sorted once in the constructor via `Comparator.comparingInt(PromptContextContributor::getOrder)` into an immutable `List.copyOf` snapshot (T-06-10 — ordering is observable and testable; Plan 05 E-08 asserts it).
- `effectiveModel(AiParameters params, Overrides overrides)` — non-null non-blank `overrides.model()` replaces the profile's model after the same slug-format validation used in the no-override path. Null / `Overrides.NONE` / blank falls back to `effectiveModel(AiParameters)`.
- `effectiveSystemPrompt(AiParameters params, String userId, UUID conversationId, UUID runId)` — concatenates the profile's system prompt with each contributor's `fragment()` output, joined by `"\n\n"` and filtered for null/blank. Contributor exceptions are caught and logged; a crashing contributor cannot break a chat turn (T-06-09).
- Existing `effectiveModel(AiParameters)`, `effectiveTemperature`, `effectiveTopP`, `effectiveMaxTokens`, `effectiveSystemPrompt(AiParameters)`, `parseBody`, `resolveActive`, and `buildFallback` are **unchanged** — pure additive extension.

### Bundled resource

- `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml` — 5 baseline fields (`model`, `temperature`, `topP`, `maxTokens`, `systemPrompt`) plus 3 explicit-null fields (`enabledTools`, `ragTopK`, `ragSimilarityThreshold`).

### Build

- `ai-agent/ai-agent/ai-agent.gradle` dependencies stanza gained a dedicated "Plan 06-02" labeled block declaring `jackson-dataformat-yaml` and `jakarta.validation-api` (both BOM-versioned). Block is placed at the bottom of the `dependencies {}` list so Plan 06-03 can append guard deps alongside without collision.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` → **BUILD SUCCESSFUL** after each task.
- `./gradlew :ai-agent:ai-agent:compileTestJava` → **BUILD SUCCESSFUL** after test constructor update.
- `./gradlew :ai-agent:ai-agent:test --tests AiParametersResolverTest` → **BUILD SUCCESSFUL** (existing 3 tests green with the new 4-arg constructor).
- Acceptance-criteria greps:
  - `FAIL_ON_UNKNOWN_PROPERTIES` → 1 match in `AiParametersBodyYamlMapper.java`.
  - `UnrecognizedPropertyException` → 3 matches (import + 2 catch blocks).
  - `ai-agent.parameters.yaml.unknown-key` → 2 matches (in both catch blocks).
  - `ai-agent.parameters.yaml.invalid` → 4 matches.
  - `readValue` / `writeAsYaml` → 5 matches total in mapper.
  - `@Transactional(propagation = Propagation.REQUIRED)` → 1 match on `setActive`.
  - `where e.active = true` → 1 match inside `setActive`.
  - `for (AiParameters p : active)` → 1 match BEFORE `target.setActive(Boolean.TRUE)` line.
  - `metadata.create(AiParameters.class)` → 1 match in `ParametersService`, 1 match in `DefaultParamsSeeder`.
  - `@EventListener(ApplicationReadyEvent.class)` → 1 match in seeder.
  - `runWithSystem` → 1 match in seeder (matches the codebase convention).
  - `select count(e) from ai_AiParameters e` → 1 match in seeder.
  - `yamlMapper.readValue` → 2 matches in `ParametersService` (create + update).
  - `public String effectiveModel(AiParameters params, Overrides overrides)` → 1 match.
  - `public String effectiveSystemPrompt(AiParameters params,` → 1 match with `String userId, UUID conversationId, UUID runId`.
  - `Comparator.comparingInt` → 1 match.
  - `PromptContextContributor` → 4 matches (import + field + method body + parameter type ref).
  - `overrides.model()` → 3 matches.
  - `grep -rn "new AiParametersResolver(" ai-agent/` → 1 match, in `AiParametersResolverTest.java`, passing `List.of()` as the contributors argument.

## Deviations from Plan

### Rule 3 Auto-fixes

**1. [Rule 3 — Blocking issue] `runWithSystem` returns void, not a Supplier result**

- **Found during:** Task 2 first compileJava run.
- **Issue:** The plan's `systemAuthenticator.runWithSystem(() -> { ... return null; })` pattern failed compilation: the Jmix `SystemAuthenticator.runWithSystem` API accepts a `Runnable`, not a `Supplier<T>`, so `return null` in the lambda body is an "unexpected return value" error.
- **Fix:** Extracted the seed body into a private `doSeedIfEmpty()` method and invoked it via `this::doSeedIfEmpty` method reference. The guard returns replace the `return null;` statements. Behaviour and semantics unchanged.
- **Files modified:** `DefaultParamsSeeder.java`.
- **Commit:** `f5bac19` (Task 2).

### Plan interpretation decisions

**PromptContextContributor signature — no SPI change made.**

The plan's Step C / "PREREQUISITE CHECK" section allowed for adding a nested `RunContext(String userId, UUID conversationId, UUID runId)` record to the `PromptContextContributor` SPI if absent, OR matching the existing signature. The existing Phase 2 SPI declares `fragment()` as zero-arg with no nested context type, so per the plan's explicit "match existing signature EXACTLY" guidance I did NOT mutate the SPI. The resolver overload still accepts `(String userId, UUID conversationId, UUID runId)` for Plan 04's call site — those args reserve the plumbing for a future SPI expansion but are not forwarded to contributors today. Javadoc on the overload calls this out.

### Direct `new AiParametersResolver(` callers updated

- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java` — updated its single instantiation to pass `List.of()` as the new 4th arg. Added `java.util.List` import. The test suite still runs green.
- No production direct-instantiation call sites found (grep across `ai-agent/` returned only the one test file).

### Build-file delimitation for Plan 06-03

Per the prompt's "share `ai-agent/ai-agent/build.gradle` with plan 06-03" guidance, I placed the two new dependency declarations in a dedicated comment-labeled block ("Plan 06-02 (PARAM-02/D-05): YAML ser/de ...") at the bottom of the existing `dependencies {}` block. Plan 06-03 can append its own labeled block directly after this one with no edit conflict.

## Known Stubs / Deferred Work

- `ParametersService` and `AiParametersBodyYamlMapper` are written but not yet consumed by the chat path — `DefaultChatServiceImpl` continues to read via the existing `AiParametersResolver` SnakeYAML path (tolerant-on-read per D-05). Plan 04 wires the new overloads into the chat call site.
- Admin view / CRUD REST controller for `ParametersService` is Phase 7 scope (UI-04).
- The `effectiveSystemPrompt(params, userId, conversationId, runId)` overload's context args are not yet forwarded to contributors (SPI is zero-arg in v1). If a future host case requires user-scoped fragments, the SPI will gain a `fragment(ctx)` overload and this resolver method will forward.
- The seeder is idempotent on row count — it does not detect an operator-deleted-then-reseeded scenario. Per D-04 this is intentional: admin deletion is honoured, no re-seed.

## Threat Model Adherence

| Threat ID | Mitigation Plan Says | Implemented |
|-----------|----------------------|-------------|
| T-06-06 | FAIL_ON_UNKNOWN_PROPERTIES + Bean Validation on every readValue/writeAsYaml round-trip | Yes — enabled on the static ObjectMapper; validator runs on all three public mapper methods; ParametersService.create/update always `writeAsYaml(readValue(bodyYaml))` so the stored text is canonicalised |
| T-06-07 | setActive single REQUIRED tx: flip-all-then-set-one; @Version makes races observable | Yes — verified by grep that `for (AiParameters p : active) p.setActive(FALSE)` precedes `target.setActive(TRUE)`; @Version is on the Phase 2 entity already |
| T-06-08 | Seeder is @EventListener(ApplicationReadyEvent.class) + runWithSystem + count guard | Yes — verified by grep; idempotency guard short-circuits on any existing row (including a non-default profile) |
| T-06-09 | Contributor fragment() wrapped in try/catch RuntimeException + log-and-skip | Yes — see AiParametersResolver.effectiveSystemPrompt(params,uid,cid,rid) inner for-loop |
| T-06-10 | Constructor-local List.copyOf snapshot + comparingInt(getOrder) freezes composition order | Yes — sorted in constructor, stored as immutable copy |
| T-06-11 | Canonical YAML round-trip via @JsonPropertyOrder on AiParametersBody | Yes — AiParametersBody already has @JsonPropertyOrder (Plan 06-01); ParametersService.create/update call `writeAsYaml` after `readValue` so stored text matches the pinned field order |

No new threat-register surface discovered beyond the types enumerated in the plan's threat register.

## JetBrains File-Problems Check

JetBrains MCP tooling is not available in this executor agent's tool surface (MCP tools are stripped from sub-agents with a `tools:` frontmatter restriction per the upstream bug). The plan's explicit verification gate (`./gradlew :ai-agent:ai-agent:compileJava`) is green on the first run after each task. Operators with IntelliJ open can run `get_file_problems` on the 6 touched files to confirm; the two unchecked-operations / deprecation warnings emitted during `compileTestJava` come from unrelated pre-existing tests (`OwnershipOpacityTest`, `ChatServiceFilterParamContractTest`) and are out of scope per the SCOPE BOUNDARY rule.

## Commits

- `146de14` — **Task 1**: AiParametersBodyYamlMapper + jackson-dataformat-yaml + jakarta.validation-api deps + default-params.yaml.
- `f5bac19` — **Task 2**: ParametersService (CRUD + setActive invariant) + DefaultParamsSeeder (ApplicationReadyEvent + runWithSystem + idempotent count guard).
- `b7e2696` — **Task 3**: AiParametersResolver Overrides + PromptContextContributor-chain overloads; test constructor updated.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/AiParametersBodyYamlMapper.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/DefaultParamsSeeder.java`
- FOUND: `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml`
- FOUND (modified): `ai-agent/ai-agent/ai-agent.gradle` — Plan 06-02 dep block present
- FOUND (modified): `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java` — 4-arg ctor + two new overloads
- FOUND (modified): `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AiParametersResolverTest.java` — `List.of()` passed
- FOUND: commit `146de14` (Task 1)
- FOUND: commit `f5bac19` (Task 2)
- FOUND: commit `b7e2696` (Task 3)
- BUILD SUCCESSFUL: `./gradlew :ai-agent:ai-agent:compileJava`
- BUILD SUCCESSFUL: `./gradlew :ai-agent:ai-agent:compileTestJava`
- TEST PASSED: `AiParametersResolverTest` (3 tests)
