# Phase 16: Admin Settings — Model Picker & Config-Knob Migration — Specification

**Created:** 2026-05-13
**Ambiguity score:** 0.10 (gate: ≤ 0.20)
**Requirements:** 5 locked
**Merged from:** former Phase 16 "Admin Model Management" + former Phase 17 "Admin Config-Knob Migration" (merged 2026-05-13)

## Goal

An admin can (a) pick the chat model from a curated open-weights `ComboBox` (or type a custom name) in the Parameters detail view, and (b) edit a defined set of Tier-1 runtime knobs in the singleton `AiUiSettings` view — with boot-time `@ConditionalOnProperty` toggles shown read-only and secrets shown only as "configured: yes/no" — through a unified `AiSettingsChangedEvent` so caches downstream (Phase 18) evict and admin edits take effect within one chat turn.

## Background

**Today (post-Phase 15):**

- `AiParameters` (per-profile, `agentstore`) carries a `bodyYaml` blob deserialised into `AiParametersBody` = `{model, temperature, topP, maxTokens, systemPrompt, enabledTools, ragTopK, ragSimilarityThreshold}`. Only `ragTopK`/`ragSimilarityThreshold` already override the `module.properties` defaults via the `AiParametersResolver` read-through.
- `parameters-detail-view.xml` shows `<textField id="modelField" required>` — free-text, no curation.
- `AiUiSettings` (singleton id `...120001`, `agentstore`) carries only `enabledSurfaceIds` + `defaultSurface` (Phase 12). `AiUiSettingsDetailView` mounts the singleton via `AiUiSettingsService.loadCurrent()` through `UnconstrainedDataManager` (Plan 10-06 R2 pattern).
- Knob inventory scout: ~25 `ai-agent.*` / `jmix.ai-agent.*` keys live in `module.properties` only — currently un-tunable at runtime. Includes mutation runtime knobs (`confirmation-required`, `idempotency-ttl`, `bulk-max-rows`), task-file knobs (`ttl-seconds`, `per-turn-max-files`, `per-turn-max-total-bytes`), prompt/tools shaping (`prompt.entity-inventory.limit`, `tools.max-filter-depth`), title knobs (`conversation-title.max-context-messages`, `min-assistant-messages-trigger`), and upload cap (`rag.upload.max-file-size-bytes`).
- Boot-time `@ConditionalOnProperty` toggles exist for `ai-agent.tools.mutation.enabled`, `ai-agent.conversation-title.enabled`, `jmix.ai-agent.rag.sample-ingester.enabled`, and the output-scanner pack toggles.
- Tier-3 secrets live as Spring properties: `spring.ai.openai.api-key`, `spring.ai.openai.base-url` (future STT keys will follow `*.api-key` shape).
- No `AiSettingsChangedEvent` exists. No model catalog exists. No secret-redaction guard exists at the admin UI layer.

**Why now:** Phase 18 (perf pass, was Phase 19) hard-depends on a settings-change event as its cache eviction hook. Operators today must edit `module.properties` + restart to retune cost/latency knobs that have no business being boot-time.

**Primary deliverable:** A reworked admin Parameters view (model `ComboBox`) + an extended `AiUiSettings` singleton view (Tier-1 form tab + Tier-2 read-only tab + Tier-3 secrets indicator section) + an `AiSettingsChangedEvent` publish path + an `agentstore` Liquibase changelog adding the new singleton fields. **Strictly zero per-tool description/knob override surface** — `enabledTools` allowlist stays as the only per-tool admin lever.

## Requirements

1. **Model picker — curated open-weights `ComboBox` + custom-entry escape hatch + at-first-use validation**:
   - Current: `modelField` is a free-text `<textField required>`; no curation, no allowlist test, no validation beyond "non-blank".
   - Target: `modelField` becomes a `<comboBox allowCustomValue="true">` populated from a curated catalog of open-weights models (default marked with a visual cue). Selecting an item writes the existing free-text `model` value in `AiParametersBody`. Typing a value not in the catalog is accepted (escape hatch). Validity is checked at **first use** (the first turn after save) — on `ChatClient` rejection the turn fails with a localised error notification AND falls back to the model in `default-params.yaml` for that turn only; the saved profile is NOT mutated. End users (non-admin roles) cannot switch model per conversation.
   - Acceptance:
     - `ComboBox` populated from a configurable catalog source (option chosen: `module.properties` keyed list, e.g. `jmix.ai-agent.models.catalog[0].id=qwen/qwen3.6-35b-a3b` + `.label=...` + `.default=true`); changing the property changes the dropdown without code change.
     - Selecting any catalog entry persists its `id` into `AiParameters.bodyYaml.model`.
     - Entering a custom value not in the catalog persists exactly as typed and is sent to per-request `ChatOptions` on the next turn.
     - An invalid custom model causes the next turn to fail with a localised toast / notification + a single retry against the default-params.yaml model is performed for that turn; both events emit audit rows.
     - `AiAgentMenuRole` (or stricter) is required to access the Parameters detail view; non-admin chat users have no surface to override the model per conversation.
     - All new labels are present in `messages_en.properties` AND `messages_vi.properties`.

2. **Curated open-weights catalog + TEST-20 allowlist gate**:
   - Current: no model catalog exists; no allowlist test exists; the `project_self_hostable_models_only.md` memory is non-enforced.
   - Target: a `ChatModelCatalog` `@Component` exposes an immutable, ordered list of `(id, label, default)` records sourced from `jmix.ai-agent.models.catalog[...]` properties with a documented default seed. A separate Java constant `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` lists every model id approved as Apache-2.0+ / open-weights. TEST-20 asserts catalog ⊆ allowlist.
   - Acceptance:
     - `ChatModelCatalog.entries()` returns ≥ 1 entry under default config; exactly one entry has `default=true`.
     - `TEST-20` (`ChatModelCatalogAllowlistTest`) fails the build if any catalog entry is missing from the allowlist constant; comment references `project_self_hostable_models_only.md`.
     - The default-marked entry equals the current `default-params.yaml` `model` value (`qwen/qwen3.6-35b-a3b` at spec time) — the seed and the marked default stay in sync, asserted by a second test.
     - Proprietary / hosted-only models (Claude, GPT-4o, Gemini Pro, etc.) are reachable ONLY via the custom-entry escape hatch — they MUST NOT appear in the catalog.

3. **Tier-1 knob migration — RAG + task-file + mutation runtime + prompt/tools shaping + title + upload knobs become editable via `AiParametersResolver`-style read-through**:
   - Current: only `ragTopK` and `ragSimilarityThreshold` override `module.properties`. All other Tier-1 candidates (`ai-agent.task-file.{ttl-seconds, per-turn-max-files, per-turn-max-total-bytes}`, `ai-agent.tools.mutation.{confirmation-required, idempotency-ttl, bulk-max-rows}`, `jmix.ai-agent.prompt.entity-inventory.limit`, `jmix.ai-agent.tools.max-filter-depth`, `ai-agent.conversation-title.{max-context-messages, min-assistant-messages-trigger}`, `jmix.ai-agent.rag.upload.max-file-size-bytes`) are read-only at runtime.
   - Target: each Tier-1 knob is persisted as a nullable field on the entity that owns it semantically — RAG `top-k`/`similarity-threshold` stay in `AiParameters.bodyYaml` (per-profile); all other Tier-1 knobs move to **expanded `AiUiSettings` singleton fields** (operator-runtime, not per-profile). Resolution order on each consult: DB value (if non-null) → `module.properties` default → strict `default-params.yaml` seed. The strict `default-params.yaml` seed stays strict — new keys must not be added there. Each knob has bean-validation bounds (e.g. `topK` ∈ [1, 50]; `ttl-seconds` ∈ {-1} ∪ [60, 86_400 × 7]; `bulkMaxRows` ∈ [1, 500]). The `-1` sentinel for `task-file.ttl-seconds` and `per-turn-max-files` / `per-turn-max-total-bytes` is preserved (Phase 13.1 contract).
   - Acceptance:
     - Editing any Tier-1 knob in the admin UI and saving causes the next chat turn / retrieval / mutation to consult the new value WITHOUT a restart; covered by an integration test per cluster (RAG read-through test, task-file budget read-through test, mutation knob read-through test, prompt/tools read-through test, title read-through test).
     - Setting a Tier-1 knob back to null (unset) restores the `module.properties` default; an integration test asserts this fall-through.
     - Bean-validation bounds are enforced at save time with a localised error; out-of-range save attempts are rejected.
     - The strict `default-params.yaml` seed remains the 7-key body shape it has today (`model`, `temperature`, `topP`, `maxTokens`, `systemPrompt`, `enabledTools`, `ragTopK`, `ragSimilarityThreshold`) — a structural test asserts the seed parses under `AiParametersBody` with no unknown keys.
     - The `-1` sentinel for task-file knobs continues to disable cleanup (Phase 13.1 TtlConfigSentinelSkipsCleanupTest must pass unchanged with the field sourced from `AiUiSettings` instead of `module.properties`).

4. **Tier-2 read-only display + Tier-3 secret indicator + SEC-08 redaction gate**:
   - Current: `AiUiSettingsDetailView` shows only chat-surface controls. Boot-time `@ConditionalOnProperty` toggles and secrets are invisible to admins; there is no surface that distinguishes Tier-1 from Tier-2/Tier-3.
   - Target: `AiUiSettingsDetailView` gets a new tab "Boot Config (read-only)" listing every Tier-2 knob (the `@ConditionalOnProperty` toggles + executor sizing + Spring AI retry knobs + audit hash toggles + output-scanner pack toggles) with key + current value + a badge "property only — requires restart". A new section "Secrets" lists Tier-3 properties matched by the pattern `*.api-key | *.password | *.secret | *.token` showing only "configured: yes / no" — never the value. SEC-08 test enforces no `*.api-key`/etc. is rendered as editable or value-displayed; `@ConditionalOnProperty` toggles are never rendered as `<checkbox editable>` / `<comboBox>`.
   - Acceptance:
     - New tab "Boot Config (read-only)" renders in `AiUiSettingsDetailView`; all components in this tab are non-editable (Vaadin `readOnly` or `<span>`).
     - The Tier-2 audit table is documented in `16-CONTEXT.md` as a source-of-truth list; a unit test loads the table and asserts the rendered tab covers every entry.
     - Tier-3 indicators show `configured: yes` ONLY when the resolved property is non-blank; otherwise `configured: no`. No raw key value is ever rendered to the DOM (assert via Vaadin `Span.getText()` content scan in test).
     - `SEC-08` (`SecretRedactionInvariantsTest`) fails the build if any `*.api-key` / `*.password` / `*.secret` / `*.token` property is bound to an editable form component anywhere in the codebase.
     - `SEC-08` second leg fails the build if any `@ConditionalOnProperty` toggle key is bound to an editable form component (boot toggles are read-only by contract).
     - Tier-3 pattern is configurable via a `@ConfigurationProperties` `ai-agent.admin.secret-property-patterns` (default = `["*.api-key","*.password","*.secret","*.token"]`) so future secrets are caught without code change.

5. **Unified `AiSettingsChangedEvent` + single-publisher entity listeners + Liquibase + locale parity**:
   - Current: no settings change event exists. `LlmExposureChangedEvent` (Phase 10 R2) is the only precedent: a single entity listener (`AiExposureRuleEntityListener`) is the sole publish site.
   - Target: a new `AiSettingsChangedEvent` (`ApplicationEvent` subclass) is published when EITHER `AiParameters.active=true` row or the `AiUiSettings.SINGLETON_ID` row is saved. Publish sites: exactly one entity listener per entity (`AiParametersEntityListener`, `AiUiSettingsEntityListener`) — views and services do NOT publish directly (Plan 10-06 R2 invariant). The event carries no payload beyond an enum `kind ∈ {PARAMETERS, UI_SETTINGS}` so subscribers can scope eviction if needed. An `agentstore` Liquibase changelog adds the new `AiUiSettings` columns and any new `AiParameters` body validation columns; included in `agentstore-changelog.xml` (NOT mutating prior changelogs — Phase 12 D-15 invariant). All new labels are present in `messages_en.properties` AND `messages_vi.properties`.
   - Acceptance:
     - Saving an active `AiParameters` row publishes exactly one `AiSettingsChangedEvent(kind=PARAMETERS)` (verified via `@MockBean ApplicationEventPublisher` capture + count = 1).
     - Saving `AiUiSettings.SINGLETON_ID` publishes exactly one `AiSettingsChangedEvent(kind=UI_SETTINGS)`.
     - Saving an INACTIVE `AiParameters` row publishes ZERO events (inactive profile edits do not invalidate caches).
     - Views and services have zero direct `ApplicationEventPublisher` injection for this event; a source-scan test asserts only the two entity listeners reference `AiSettingsChangedEvent` on the publish path.
     - A new Liquibase changelog file (e.g. `110-ai-ui-settings-tier1-fields.xml`) is referenced from `agentstore-changelog.xml`; the existing `080-ai-ui-settings.xml` is NOT modified.
     - Both locale bundles contain every new key (`LocaleParityTest` extension passes).

## Boundaries

**In scope:**

- Curated `ComboBox` + custom-entry escape hatch for `modelField` in `parameters-detail-view.xml`.
- `ChatModelCatalog` `@Component` + `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` constant + TEST-20.
- At-first-use model validation: fail turn with localised notification + one-shot fallback to `default-params.yaml` model; audit rows for both events.
- Migration of Tier-1 knobs across 4 clusters: RAG (top-k, similarity) — per-profile in `AiParameters.bodyYaml`; task-file (ttl-seconds, per-turn-max-files, per-turn-max-total-bytes), mutation runtime (confirmation-required, idempotency-ttl, bulk-max-rows), prompt+tools shaping (entity-inventory.limit, max-filter-depth), title (max-context-messages, min-assistant-messages-trigger), upload (max-file-size-bytes) — singleton in expanded `AiUiSettings`.
- `AiParametersResolver`-style read-through: DB → `module.properties` → strict `default-params.yaml` seed.
- New `AiUiSettings` "Boot Config (read-only)" tab listing Tier-2 toggles + executor sizing + retry knobs + audit/output-scanner knobs with "requires restart" badges.
- New `AiUiSettings` "Secrets" indicator section: pattern-matched `*.api-key | *.password | *.secret | *.token` keys shown as `configured: yes/no` only.
- SEC-08 (`SecretRedactionInvariantsTest`) — two-legged source-scan test.
- `AiSettingsChangedEvent` + 2 entity listeners (single publisher per entity).
- Liquibase changelog adding new `AiUiSettings` columns (separate file, included in `agentstore-changelog.xml`).
- Locale parity in `messages_en.properties` + `messages_vi.properties`.

**Out of scope:**

- **Per-tool description / per-tool `topK` / per-tool `similarityThreshold` map shape** (jmix-ai-backend style) — only one retriever exists today and rich `@Tool` descriptions are designed in-source (`feedback_rich_tool_descriptions.md`); promote to Backlog when a second retriever lands.
- **STT knobs** (`ai-agent.stt.*`) — STT does not exist yet (Phase 19); its boot toggles + `store-transcript` knob are owned by that phase under the same Tier-2 read-only / Tier-3 secret rules established here.
- **Per-conversation end-user model switching** — admin-only model selection is a SEC-by-contract constraint; explicitly carried out of v1.2.
- **Performance memoization of settings reads** — Phase 18 owns the cache layer that consumes the change-event; this phase only publishes the event, does not memoize.
- **Editing/displaying `spring.ai.retry.*` as Tier-1** — Spring AI retry advisor is wired at boot via `spring-ai-autoconfigure-retry`; reconfiguring at runtime would require advisor re-creation. Stays Tier-2 read-only.
- **Editing/displaying `jmix.ai-agent.rag.splitter.*` as Tier-1** — chunk sizing only affects newly-ingested documents; changing it at runtime creates inconsistent chunk semantics across the index. Stays Tier-2 read-only with a note.
- **Editing `jmix.ai-agent.rag.ingest-executor.*` / `ai-agent.conversation-title.executor.*` as Tier-1** — thread pool resize at runtime is hazardous (in-flight tasks); stays Tier-2 read-only.
- **Custom catalog persistence in the database** — catalog is property-driven, not entity-CRUD'd. A future phase can promote to a `AiModelCatalog` entity if hosts need per-deployment overrides without property rebuilds.
- **Mutating prior Liquibase changelogs** (Phase 12 D-15 invariant): `080-ai-ui-settings.xml` is frozen; new fields land in a new file.

## Constraints

- **Schema design once**: `AiParameters` body and `AiUiSettings` columns are co-designed in this phase — Phase 18 hard-depends on the change event shape, so it must be locked here.
- **Open-weights only in the catalog**: enforced by TEST-20 and `project_self_hostable_models_only.md`. Proprietary models reachable only via custom-entry escape hatch.
- **No `*.api-key` / `*.password` / `*.secret` / `*.token` may be rendered as editable or value-displayed** — SEC-08 source-scan gate.
- **No `@ConditionalOnProperty` toggle may be rendered as runtime-editable** — boot toggles stay read-only with a "requires restart" badge.
- **Single publisher per entity for `AiSettingsChangedEvent`** — views/services NEVER inject `ApplicationEventPublisher` for this event (Plan 10-06 R2 pattern).
- **Strict `default-params.yaml` seed stays strict** — no new keys added to the seed; the new fields live in `AiUiSettings` or are nullable `AiParametersBody` fields with code-level defaults.
- **Liquibase additivity**: prior changelogs (notably `080-ai-ui-settings.xml`) are NOT modified; new fields land in a new changelog file included via the existing `includeAll` strategy in `agentstore-changelog.xml`.
- **Locale parity**: every new label / message in `messages_en.properties` MUST also be present in `messages_vi.properties` (LocaleParityTest extension).
- **No DDL on `agentstore` outside this phase** — changes confined to a single new changelog file.
- **Admin-role gate**: `AiAgentAdminRole` (or stricter) is required to access the new Boot Config tab and the secrets indicator section; non-admin roles see neither.
- **Bean-validation bounds** must be sensible defaults documented in `16-CONTEXT.md` (e.g. `topK ∈ [1, 50]`, `bulkMaxRows ∈ [1, 500]`, `ttlSeconds ∈ {-1} ∪ [60, 604_800]`); per-knob bounds are the planner's call but must not allow `0` for non-sentinel fields.

## Acceptance Criteria

- [ ] `modelField` in `parameters-detail-view.xml` is a `<comboBox allowCustomValue="true">` populated from `jmix.ai-agent.models.catalog[...]`.
- [ ] `ChatModelCatalog.entries()` returns ≥ 1 entry with exactly one default-marked under default config.
- [ ] Catalog ⊆ `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` (TEST-20 `ChatModelCatalogAllowlistTest`).
- [ ] Default-marked catalog entry == `default-params.yaml` `model` field (drift gate test).
- [ ] Invalid custom model causes the next turn to fail with a localised notification + fall back to `default-params.yaml` model for that turn only; audit rows emitted for both the failure and the fallback.
- [ ] The saved `AiParameters.model` value is NOT mutated by the validation failure (test loads the row after a failed turn — value unchanged).
- [ ] Tier-1 RAG knobs (`ragTopK`, `ragSimilarityThreshold`) survive on `AiParameters.bodyYaml` (no behavior change vs Phase 14).
- [ ] Tier-1 non-RAG knobs (task-file, mutation runtime, prompt/tools, title, upload) are new fields on `AiUiSettings` and read fresh per turn via the read-through resolver.
- [ ] Setting any Tier-1 knob to null restores the `module.properties` default — integration test per cluster.
- [ ] Bean-validation bounds reject out-of-range saves with a localised message.
- [ ] `-1` sentinel for task-file knobs continues to disable cleanup (Phase 13.1 `TtlConfigSentinelSkipsCleanupTest` passes with the new source).
- [ ] `AiUiSettingsDetailView` has a new "Boot Config (read-only)" tab listing every Tier-2 knob with key + value + "requires restart" badge.
- [ ] `AiUiSettingsDetailView` has a "Secrets" section showing `configured: yes/no` indicators only; no raw secret value rendered to the DOM (asserted by Span.getText() content scan).
- [ ] `SEC-08` `SecretRedactionInvariantsTest` (both legs) passes: no secret bound to editable; no `@ConditionalOnProperty` toggle bound to editable.
- [ ] `ai-agent.admin.secret-property-patterns` defaults to `["*.api-key","*.password","*.secret","*.token"]` and is overridable via host property.
- [ ] `AiSettingsChangedEvent(kind=PARAMETERS)` fires exactly once on active `AiParameters` save.
- [ ] `AiSettingsChangedEvent(kind=UI_SETTINGS)` fires exactly once on `AiUiSettings.SINGLETON_ID` save.
- [ ] Inactive `AiParameters` saves publish ZERO events.
- [ ] Source-scan test asserts only the two entity listeners reference `AiSettingsChangedEvent` on the publish path.
- [ ] New Liquibase changelog file is referenced from `agentstore-changelog.xml`; `080-ai-ui-settings.xml` is byte-identical to its pre-phase content.
- [ ] All new labels present in BOTH `messages_en.properties` AND `messages_vi.properties` (LocaleParityTest extension).
- [ ] `AiAgentAdminRole` policy covers the new Boot Config tab + Secrets section; a non-admin login cannot see either.
- [ ] Existing Phase 9 / 10 / 11 / 12 / 13 / 13.1 / 14 test suites pass UNCHANGED — this phase is additive to schema and to UI only.
- [ ] Strict `default-params.yaml` seed parses under `AiParametersBody` with no unknown keys (structural test).

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                 |
|--------------------|-------|------|--------|-----------------------------------------------------------------------|
| Goal Clarity       | 0.92  | 0.75 | ✓      | Two clear deliverables — model picker + Tier-1/2/3 knob migration     |
| Boundary Clarity   | 0.90  | 0.70 | ✓      | Tier-1 list locked across 4 clusters; explicit out-of-scope w/ reasons|
| Constraint Clarity | 0.90  | 0.65 | ✓      | Catalog source, storage placement, event shape, secret pattern all set|
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 25 pass/fail criteria; bounds set as planner-call, not silent         |
| **Ambiguity**      | 0.10  | ≤0.20| ✓      | Strong pass — planner has minimal guessing surface                    |

## Interview Log

| Round | Perspective                  | Question summary                                            | Decision locked                                                                          |
|-------|------------------------------|------------------------------------------------------------|------------------------------------------------------------------------------------------|
| 1     | Researcher                   | Which knob clusters are Tier-1?                            | All 4 clusters: RAG+task-file, mutation runtime, prompt+tools shaping, title+upload      |
| 1     | Researcher                   | Curated catalog source-of-truth shape?                     | `module.properties` keyed list + Java `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST` constant    |
| 1     | Researcher                   | Where do non-profile Tier-1 knobs persist?                 | Expand `AiUiSettings` singleton (one entity, one changelog)                              |
| 2     | Boundary Keeper / Failure    | Bad-model validation behavior?                             | Fail turn + localised notification + one-shot fallback to `default-params.yaml` model    |
| 2     | Boundary Keeper              | Tier-2 read-only display location?                         | New "Boot Config (read-only)" tab in `AiUiSettingsDetailView`                            |
| 2     | Boundary Keeper / Failure    | Change-event shape — one or many?                          | Single `AiSettingsChangedEvent` with `kind ∈ {PARAMETERS, UI_SETTINGS}`                  |
| 2     | Failure Analyst              | Tier-3 secret detection rule?                              | Property pattern `*.api-key | *.password | *.secret | *.token` (configurable property)   |

---

*Phase: 16-admin-settings-model-picker-config-knob-migration*
*Spec created: 2026-05-13*
*Next step: /gsd-discuss-phase 16 — implementation decisions (entity listener wiring, ComboBox renderer for default-marked, Liquibase column shapes, etc.)*
