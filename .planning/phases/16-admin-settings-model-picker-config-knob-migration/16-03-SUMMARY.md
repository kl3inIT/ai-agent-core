---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 03
subsystem: admin-config
tags: [spring-boot, configuration-properties, catalog, validation]
requires:
  - AiAgentDefaultsProperties (jmix.ai-agent.defaults binding — Phase 6)
  - KnobMetadata annotation (Plan 16-01)
provides:
  - ChatModelCatalogProperties (jmix.ai-agent.models.catalog[*] binding)
  - AdminSecretPatternProperties (ai-agent.admin.secret-property-patterns binding + locked default)
  - ChatModelCatalog @Component (entries / defaultEntry / findById + @PostConstruct boot-fast validator)
  - SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST constant (4-id Set<String>)
  - module.properties catalog seed (4 entries; qwen3.6-35b-a3b marked default)
  - ChatModelCatalogAllowlistTest (TEST-20 — 3 green methods)
affects:
  - module.properties (additive only — 12 new keys appended; existing keys unchanged)
tech_stack_added: []
patterns_used:
  - "Pattern F: record + nested record + resolved*() defaulting (mirrors AiAgentRagProperties)"
  - "Pattern C: @Component with @PostConstruct boot-fast validation (mirrors AiParametersResolver shape)"
  - "Catalog allowlist constant + drift gate (boot @PostConstruct + build-time TEST-20)"
  - "Rule 3 — Spring context boot regression workaround via direct property/YAML parsing (mirrors Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, 16-02)"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalogProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AdminSecretPatternProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java
key_files_modified:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java
decisions:
  - "ChatModelCatalogProperties.Entry uses Boolean isDefault (not primitive) so the boot-time validator can distinguish absent (null) from explicit false. Internal ChatModelCatalog.Entry uses primitive boolean because validation has already rejected null."
  - "ChatModelCatalog.@PostConstruct rejects any entry id outside SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST unconditionally (no jmix.ai-agent.models.allow-out-of-allowlist escape property — proprietary models reach the UI only via the parameters-detail-view custom-entry escape hatch per SPEC criterion 4, never via the seeded catalog)."
  - "AdminSecretPatternProperties.LOCKED_DEFAULT_PATTERNS exposed as public static constant so tests and resolvedPatterns() share a single source-of-truth; resolvedPatterns() never returns null."
  - "Rule 3 auto-fix: TEST-20 implemented as pure-JUnit (parse module.properties + default-params.yaml in-test, construct properties records, invoke @PostConstruct directly) instead of @SpringBootTest. Reason: pre-existing Phase 11/13 Spring-context boot regression (atmosphere-runtime / agentstoreEntityManagerFactory) still blocks @SpringBootTest in this module; documented in .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md and worked around the same way by Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, 16-02. Contract preserved exactly — when the boot regression is fixed in a future phase, the test bodies port unchanged to @SpringBootTest by replacing the boot() helper with @Autowired."
metrics:
  duration: ~12 min
  tasks_completed: 3
  files_created: 3
  files_modified: 2
  completed_date: 2026-05-13
---

# Phase 16 Plan 03: Curated Model Catalog + Secret Pattern Records + TEST-20 Summary

Stand up the open-weights chat-model catalog (`ChatModelCatalogProperties` + `ChatModelCatalog` + `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST`) with a boot-fast `@PostConstruct` drift gate against `default-params.yaml.model`, the secret-pattern configuration record (`AdminSecretPatternProperties`) with the locked 4-element default, the `module.properties` seed (4 entries — `qwen/qwen3.6-35b-a3b` marked default), and flip `ChatModelCatalogAllowlistTest` (TEST-20) green with 3 real assertion bodies. Plan 16-05's ComboBox and Plan 16-06's secrets-mask now have their config-binding seam locked.

## What Shipped

### Task 1 — `ChatModelCatalogProperties` + `AdminSecretPatternProperties` records (commit `1edccda`)

- `ChatModelCatalogProperties` (`@ConfigurationProperties("jmix.ai-agent.models")`) Java record with nested `Entry(String id, String labelMessageKey, Boolean isDefault)`. `Boolean` is intentional — null means absent, distinct from explicit false, so the boot validator can give better diagnostics.
- `AdminSecretPatternProperties` (`@ConfigurationProperties("ai-agent.admin")`) Java record with `List<String> secretPropertyPatterns` plus a `resolvedPatterns()` accessor that returns `LOCKED_DEFAULT_PATTERNS = List.of("*.api-key", "*.password", "*.secret", "*.token")` when the configured value is null or empty. `LOCKED_DEFAULT_PATTERNS` is exposed as a public static constant so the resolver fallback and SEC-08 tests share one source of truth.
- Both records carry `@KnobMetadata(tier = TIER_2, requiresRestart = true, displayMessageKey = "...")` on their list component so Plan 06's annotation-pass stays purely additive.
- Both are picked up automatically by the `@ConfigurationPropertiesScan` on `AIConfiguration` — no explicit `@EnableConfigurationProperties` edit needed.

### Task 2 — `ChatModelCatalog` `@Component` + `module.properties` seed (commit `95c48fd`)

- `ChatModelCatalog` `@Component` in `com.vn.agent.admin.config`. Constructor injects `ChatModelCatalogProperties` + `AiAgentDefaultsProperties`.
- `public static final Set<String> SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST = Set.of(...)` with exactly the 4 ids from RESEARCH §7:
  - `qwen/qwen3.6-35b-a3b` (Apache 2.0)
  - `meta-llama/llama-3.3-70b-instruct` (Llama 3.x Community License)
  - `mistralai/mistral-small-3.1-24b-instruct` (Apache 2.0)
  - `deepseek/deepseek-v3.1` (MIT)
- Block comment above the constant references project memory `project_self_hostable_models_only`.
- `@PostConstruct void validate()` fail-fast assertions:
  - Empty/null catalog → `IllegalStateException`.
  - Any entry id outside the allowlist → `IllegalStateException` (no out-of-allowlist escape hatch in the seeded catalog; proprietary models reach the UI only via the `parameters-detail-view` custom-entry path per SPEC criterion 4).
  - Default-count `!= 1` → `IllegalStateException`.
  - Marked-default id `!=` `defaults.model()` → `IllegalStateException` (drift gate against `default-params.yaml.model`).
- Public accessors: `List<Entry> entries()`, `Entry defaultEntry()`, `Entry findById(String id)` (returns `null` for unknown ids — Plan 16-05's ComboBox renders custom-entered values verbatim).
- Public nested record `Entry(String id, String labelMessageKey, boolean isDefault)` (primitive `boolean` — validation has already rejected null).
- `module.properties` appended: 12 new keys (4 entries × 3 components). Marked-default `qwen/qwen3.6-35b-a3b` matches `default-params.yaml.model` byte-for-byte at ship time. Existing keys byte-identical.

### Task 3 — `ChatModelCatalogAllowlistTest` (TEST-20) flipped green (commit `46c9f70`)

Three real assertion bodies replace the Wave-0 `fail()` scaffolds:

- `catalogSubsetOfAllowlist()` — iterates `catalog.entries()`; asserts each `entry.id()` is in `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` (Javadoc references `project_self_hostable_models_only.md` per SPEC criterion 4).
- `exactlyOneDefault()` — asserts `catalog.entries().stream().filter(Entry::isDefault).count() == 1L`.
- `defaultMatchesDefaultParamsYaml()` — asserts `catalog.defaultEntry().id().equals(<parsed default-params.yaml.model>)`. Comment locks the drift-gate semantics.

Class-level `@Disabled` + `@Tag("phase-16-scaffold")` removed. Zero `fail()` calls remain. The test now runs as `@Tag("unit")`.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:compileJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:test --tests "com.vn.agent.admin.config.ChatModelCatalogAllowlistTest"
→ BUILD SUCCESSFUL (3 methods green)
```

- `ChatModelCatalogProperties` exposes `List<Entry> catalog()` with nested `Entry(id, labelMessageKey, isDefault)`.
- `AdminSecretPatternProperties.resolvedPatterns()` returns `["*.api-key", "*.password", "*.secret", "*.token"]` when the property is unset.
- `ChatModelCatalog.entries().size() == 4`, `defaultEntry().id() == "qwen/qwen3.6-35b-a3b"`.
- `module.properties` contains 12 `jmix.ai-agent.models.catalog[N].*` keys.
- `git diff` on `module.properties` shows only additions; no existing key modified or removed.

## Decisions Made

- **`Boolean` vs `boolean` on `Entry.isDefault`**: the bound record uses `Boolean` (nullable) so the validator can distinguish absent from explicit-false; the resolved internal record (`ChatModelCatalog.Entry`) uses primitive `boolean` because validation has already rejected null. Two-tier model keeps the bound contract honest while giving downstream consumers (Plan 16-05 ComboBox) a non-null primitive.
- **No out-of-allowlist escape property in the seeded catalog**: an earlier plan draft considered `jmix.ai-agent.models.allow-out-of-allowlist=true` as a host-extension hook. Rejected — `feedback_pragmatic_modules` gates against speculative extension; per SPEC criterion 4 proprietary models reach the UI only via the `parameters-detail-view` custom-entry path. Hosts wanting to extend the allowlist subclass and replace the bean.
- **`LOCKED_DEFAULT_PATTERNS` public constant**: rather than inlining `List.of("*.api-key", ...)` in `resolvedPatterns()`, exposed it as `public static final List<String>` so SEC-08 tests, the resolver fallback, and any host-side debugging share one source of truth.
- **Rule 3 — TEST-20 implemented as pure-JUnit (not `@SpringBootTest`)**: the ai-agent module's Spring context boot is blocked by the pre-existing Phase 11/13 regression (atmosphere-runtime / agentstoreEntityManagerFactory) documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`. Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, and 16-02 all hit the same blocker and worked around it the same way. The test parses `module.properties` and `default-params.yaml` directly (the SAME files Spring would bind at boot), constructs `ChatModelCatalogProperties` and `AiAgentDefaultsProperties` manually, and invokes `ChatModelCatalog.validate()` (the same `@PostConstruct` method Spring would invoke). Contract preserved exactly. When the boot regression is fixed, the test ports unchanged by replacing the `boot()` helper with `@Autowired`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking Issue] TEST-20 switched from `@SpringBootTest` to pure-JUnit**

- **Found during:** Task 3.
- **Issue:** Plan directs `@SpringBootTest` mirroring `TtlConfigTest` (Pattern I). The ai-agent module's Spring context boot is blocked by the pre-existing Phase 11/13 regression (atmosphere-runtime / agentstoreEntityManagerFactory). Same blocker that forced Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, and 16-02 onto pure-JUnit workarounds.
- **Fix:** Test parses `module.properties` + `default-params.yaml` directly, constructs `ChatModelCatalogProperties` + `AiAgentDefaultsProperties` manually, invokes `ChatModelCatalog.validate()` exactly as `@PostConstruct` would. All three method bodies preserve the plan's contract verbatim.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java`
- **Commit:** `46c9f70`

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries in `16-03-PLAN.md`:

- **T-16-06 (Tampering — catalog seed in `module.properties`)** — mitigated as planned: `ChatModelCatalog.@PostConstruct` fails boot on drift; `ChatModelCatalogAllowlistTest.defaultMatchesDefaultParamsYaml()` fails build on drift.
- **T-16-04 (Information Disclosure — `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST`)** — accepted as planned: allowlist is a public taxonomy tag, not a secret.

No additional surface added — `AdminSecretPatternProperties` carries pattern strings, not matched values; `toString` safe to log.

## Known Stubs

None. Production code in all three tasks ships with no stubs — every method has a real body and every constant is reachable. `ChatModelCatalog.findById()` returns `null` for unknown ids by design (Plan 16-05 contract for custom-entered values), which is documented intent, not a stub.

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalogProperties.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AdminSecretPatternProperties.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java` — FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java` (modified) — FOUND

Commits exist:
- `1edccda` (Task 1) — FOUND
- `95c48fd` (Task 2) — FOUND
- `46c9f70` (Task 3) — FOUND
