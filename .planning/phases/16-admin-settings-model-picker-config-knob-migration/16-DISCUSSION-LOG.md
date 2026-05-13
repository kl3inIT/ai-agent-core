# Phase 16: Admin Settings — Model Picker & Config-Knob Migration - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-13
**Phase:** 16-admin-settings-model-picker-config-knob-migration
**Areas discussed:** AiUiSettings schema shape, Knob inventory source-of-truth, Resolver layering, Model validation + fallback site
**Mode:** advisor (USER-PROFILE.md present; calibration tier `full_maturity` from vendor_philosophy=`thorough-evaluator`; NON_TECHNICAL_OWNER overridden to false based on observed technical fluency in this session)

---

## AiUiSettings schema shape

| Option | Description | Selected |
|--------|-------------|----------|
| Flat JPA columns (~10 nullable) | Type-safe, native Jakarta bean validation, XML `property=` zero-glue, mirrors existing `enabledSurfaceIds`/`defaultSurface` precedent on the same entity | ✓ |
| JSON/YAML blob (`AiUiSettingsRuntimeBody` record) | Mirrors `AiParameters.bodyYaml`; schema evolution w/o DDL; but `@Transient` getter bridges + EclipseLink-weaver setter-name trap | |
| Hybrid (flat columns + JSON sidecar) | Type-safety + extension seam; doubles diff surface; only if concrete extension consumer named | |
| EAV table | Schema-free; loses type-safety; bypasses every Jmix convention; over-engineering | |
| Split `AiUiSettings` + `AiOperatorSettings` | Conceptual separation; 2 singletons + 2 services + 2 views + 2 publish sites; contradicts Plan 10-06 R2 | |

**User's choice:** Flat JPA columns (~10 nullable)
**Notes:** Confirmed `feedback_jmix_first_ui` directive + the `setEnabledSurfaceSet` comment block already documents the EclipseLink-weaver trap that a blob approach would re-introduce. Plan 10-06 R2 entity-listener diff is dramatically simpler over typed columns.

---

## Knob inventory source-of-truth

| Option | Description | Selected |
|--------|-------------|----------|
| `@KnobMetadata` annotation + scan + Actuator-pattern fallback + secret-pattern mask | 3-layer: annotated knobs explicit; `ConfigurationPropertiesBean.getAll()` for un-annotated host records; secret-pattern mask as safety net | ✓ |
| Hardcoded `KnobRegistry` Java constant | Strongest type-safety; blocks host extension; central edit per knob | |
| `@KnobMetadata` annotation only (no fallback layer) | Single SoT; un-annotated host records invisible in Tier-2 tab | |
| YAML catalog `META-INF` + `KnobCatalogLoader` | Host-extensible via classpath drop; drifts from records silently; reinvents Spring's `spring-configuration-metadata.json` | |

**User's choice:** `@KnobMetadata` annotation + Actuator-pattern fallback + secret-pattern mask
**Notes:** Defense-in-depth design: annotation gives type-safe co-located source-of-truth, Actuator-pattern (`ConfigurationPropertiesBean.getAll()`) discovers un-annotated host records automatically, secret-pattern mask catches drift.

### Follow-up — `@KnobMetadata` annotation rollout scope

| Option | Description | Selected |
|--------|-------------|----------|
| Annotate all existing 7 `@ConfigurationProperties` records in this phase | Tier-2 tab populated at ship; ~25 additive annotation lines; pattern applied everywhere | ✓ |
| Annotate only Tier-1 + Tier-3, leave un-annotated as Tier-2 default | Smaller diff; Actuator fallback surfaces un-annotated automatically | |
| Pattern only (no record annotation in P16); host opts in field-by-field | Smallest diff; Boot Config tab empty at ship — bad first impression | |

**User's choice:** Annotate all 7 existing records this phase
**Notes:** Boot Config tab + Secrets section must be fully populated at ship; not a "framework lands empty" feature.

---

## Resolver layering

| Option | Description | Selected |
|--------|-------------|----------|
| Sibling `AiUiSettingsResolver` | Mirrors `AiParametersResolver` shape; 2 cache surfaces for Phase 18 (one per resolver); preserves "additive only" — every Phase 1-15 caller untouched; 1-2 callers inject both | ✓ |
| Extend `AiParametersResolver` to also load `AiUiSettings` | Single injection point; class name lies; god-resolver risk | |
| Merge into `AiSettingsResolver` (deprecate old) | Future-proof name; violates "additive only" — 15+ files churn; v2.0 breaking change shape | |
| Per-cluster resolvers (5 classes) | Each cluster owns eviction path; 5 classes for 10 knobs = premature decomposition; fails `feedback_pragmatic_modules` + `feedback_spi_baseline_builtin` | |
| Extend `@ConfigurationProperties` records with `merge(AiUiSettings)` | Reads feel local; conflates immutable defaults with runtime resolution; scatters singleton loads; kills Phase 18 cache surface | |

**User's choice:** Sibling `AiUiSettingsResolver`
**Notes:** Each resolver gets its own `@EventListener(AiSettingsChangedEvent)` slot complying with Plan 10-06 R2 single-publish-site. The 1-2 callers needing both resolvers (prompt building reads `systemPrompt` from profile + `entityInventoryLimit` from singleton; possibly upload service) inject both — feature, not bug.

---

## Model validation + fallback site

| Option | Description | Selected |
|--------|-------------|----------|
| Catch + reissue inside `executeBlockingTurn` + new `AuditKind.MODEL_VALIDATION_FAILURE` | Existing Phase 13.1 BLK-01 chokepoint; typed audit kind future-proofs Phase 19 STT; 2 rows (failure + RUN_TURN fallback) | ✓ |
| Catch + reissue + reuse `TOOL_CALL` with `eventName="model_validation_failure"` | No new enum; semantically wrong (not a tool call); weak string typing | |
| `ModelFallbackAdvisor` (Spring AI advisor pattern) | Canonical Spring AI 1.1 pattern; reusable across consumers; streaming advisor error semantics uneven in Spring AI 1.1.x (#2877) | |
| Pre-flight probe memoized per `AiSettingsChangedEvent` | Catches before advisor/RAG cost; adds latency to first turn after every settings edit; probe semantics ≠ real turn | |
| Spring `RetryTemplate` + fallback `ChatClient` bean | Framework-native; `NonTransientAiException` is do-not-retry by design — fighting framework semantics; 2-bean drift | |

**User's choice:** Catch + reissue inside `executeBlockingTurn` + new `AuditKind.MODEL_VALIDATION_FAILURE`
**Notes:** Preserves Phase 13.1 BLK-01 invariant by construction (no separate path that bypasses `executeBlockingTurn`). Defer `ModelFallbackAdvisor` until a 2nd operational-fallback case (quota / content filter / regional failover) lands and pays for the abstraction.

---

## Claude's Discretion

- Exact bean-validation bounds per knob (planner researches reasonable defaults; SPEC.md notes these are planner's call).
- Exact entry list for curated catalog default seed (planner researches current open-weights provider availability at plan time; default-marked stays in sync with `default-params.yaml.model`).
- Exact `@ConfigurationProperties` record inventory (7 vs 8 depending on `AiAgentTaskFileProperties` presence — planner confirms during scout).
- Package locations for new classes (`ChatModelCatalog`, `KnobInventoryScanner`, `AiUiSettingsResolver`, entity listeners, `KnobMetadata` annotation, `AiSettingsChangedEvent`).
- Whether `KnobInventoryScanner` lives on `ai-agent` library or `ai-agent-starter` autoconfig classpath.
- Exact OpenRouter error markers classifying a `NonTransientAiException` / `RestClientResponseException` as "bad model".
- Whether `MODEL_VALIDATION_FAILURE` audit row carries offending model id verbatim or hashed (suggest verbatim per admin-input nature).

---

## Deferred Ideas

- Per-tool description / per-tool `topK` / per-tool `similarityThreshold` map shape (jmix-ai-backend style) — out of scope per SPEC.md.
- `ModelFallbackAdvisor` Spring AI advisor pattern — defer until 2nd operational-fallback case lands.
- Pre-flight model probe (1-token warmup) — defer until cost data justifies.
- `AiOperatorSettings` entity split — defer until cross-entity invariants get awkward.
- JSON sidecar on `AiUiSettings` for host-extension knobs — defer until concrete consumer named.
- `META-INF/spring/ai-agent-knob-catalog.yaml` host extension surface — annotation + Actuator-fallback covers without 2nd drifting SoT.
- `spring.ai.retry.*` / `rag.splitter.*` / `rag.ingest-executor.*` / `conversation-title.executor.*` as Tier-1 — out of scope (hazardous at runtime).
- Per-conversation end-user model switching — out of scope per SPEC.md.
- Custom catalog persistence in DB — out of scope.
- CSS-only "default" marking on ComboBox — rejected in favor of `(default)` suffix (screen-reader friendly).
