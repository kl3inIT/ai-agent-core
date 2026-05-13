---
phase: 16
slug: admin-settings-model-picker-config-knob-migration
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-13
---

# Phase 16 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution. Populated by the planner from the `## Validation Architecture` section of `16-RESEARCH.md`.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Jmix `@JmixTest` / `@UiTest` / `@SpringBootTest` (Gradle) |
| **Config file** | `ai-agent/ai-agent/src/test/resources/test-app.properties` (Jmix test profile) |
| **Quick run command** | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.<package>.<TestClass>"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~3–6 minutes (full); ~10–30 seconds (single test class) |

---

## Sampling Rate

- **After every task commit:** Run the task's quick-run command (the specific `--tests` filter listed in the per-task verification map).
- **After every plan wave:** Run `./gradlew :ai-agent:ai-agent:test` (module-scoped full suite).
- **Before `/gsd-verify-work`:** Full `./gradlew test` must be green.
- **Max feedback latency:** ~30 seconds per task; ~6 minutes per wave.

---

## Per-Task Verification Map

> Populated by the planner. One row per planned task. Each row links a task to a REQUIREMENT id, a threat (if any), and an automated test command. Tasks lacking automated verification must depend on Wave 0 stubs OR be listed under "Manual-Only Verifications" below.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

**Required coverage (from REQUIREMENTS.md + 16-SPEC.md):**

- **MODEL-01** — ComboBox renders curated catalog with the default marked; selection writes to `AiParameters.bodyYaml.model`.
- **MODEL-02** — At-first-use model validation: bad model fails the turn, surfaces localised notification, falls back once to `default-params.yaml.model`; saved `AiParameters.model` is NOT mutated.
- **MODEL-03** — Per-request `ChatOptions.builder().model(...)` carries the resolved model; admin-only — end users cannot switch model per conversation.
- **CFG-01** — Tier-1 knobs (4 clusters: task-file, mutation runtime, prompt/tools shaping, title+upload) editable in admin UI; read fresh per turn via `AiUiSettingsResolver`; take effect on next turn without restart; strict `default-params.yaml` seed unchanged.
- **CFG-02** — Tier-2 boot toggles shown read-only with "property only — requires restart" marker. Documented three-tier taxonomy classifies every audited knob.
- **CFG-03** — New editable settings persisted as fields on `AiParameters`/`AiUiSettings`; `agentstore` Liquibase changelog included in `agentstore-changelog.xml`; bean-validation bounds; locale parity; `AiSettingsChangedEvent` published on save so Phase 18 caches evict — admin edit visible within one turn.
- **SEC-08** — No `*.api-key` / `*.password` / `*.secret` / `*.token` property is surfaced as editable or displayed; `@ConditionalOnProperty` toggle keys are not presented as runtime-editable HasValue components. Source-scan invariant test (`SecretRedactionInvariantsTest`).
- **TEST-20** — Curated-catalog allowlist invariant test (`ChatModelCatalogAllowlistTest`): every catalog id ∈ `SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST`, exactly one entry marked default, catalog default == `default-params.yaml.model` (drift test).

---

## Wave 0 Requirements

> Stub test classes the planner schedules in Wave 0 so subsequent waves have an automated verification target. Populated by the planner.

- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/ChatModelCatalogAllowlistTest.java` — TEST-20 invariant (catalog ⊆ allowlist + exactly-one-default + catalog default == `default-params.yaml.model` drift test).
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/SecretRedactionInvariantsTest.java` — SEC-08 two-legged source-scan invariant.
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java` — sentinel `-1` invariant for `TASK_FILE_TTL_SECONDS` source swap (`module.properties` → `AiUiSettings`).
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiSettingsChangedEventListenerInvariantTest.java` — single-publish-site invariant (Plan 10-06 R2 carry-forward; no `ApplicationEventPublisher` injection outside the two entity listeners).
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsResolverReadThroughTest.java` — DB → `module.properties` → strict seed fallback chain for each Tier-1 knob.
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/KnobInventoryClassificationTest.java` — `@KnobMetadata` annotation coverage + Actuator-fallback discovery + Tier-3 secret-pattern mask classification.
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplModelValidationFallbackTest.java` — D-05 catch+reissue path + `MODEL_VALIDATION_FAILURE` audit row shape + `runId` correlation between the two audit rows.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| ComboBox `(default)` suffix readable by screen reader | MODEL-01 | Visual/AT verification — automated DOM probes do not assert AT-tree announcement | Run `./gradlew bootRun`, navigate to `/parameters`, tab to `modelField`, verify NVDA/JAWS/VoiceOver announces the default entry as "<label> (default)". |
| "fell back to default model" chat-row notice rendered after MODEL-02 failure | MODEL-02 | Requires live OpenRouter call to a non-existent model id; the unit test asserts the audit row + reissue, but the UI inline-notice rendering is observed in the chat surface | Pick a non-existent model in admin Parameters, send a chat turn, verify the chat row shows the locale `chat.notice.modelFallbackApplied` message and the next turn defaults restored. |
| Vietnamese locale labels render correctly in admin UI | CFG-03 (locale parity) | Locale-parity test asserts key set equality; visual confirmation needed for layout/truncation on long Vietnamese labels | Set `Accept-Language: vi` and re-open `/parameters` + `/aiUiSettings`, verify all new labels are present and not visually truncated. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (TEST-20, SEC-08, MODEL-02 fallback, sentinel `-1` survives source swap, AiSettingsChangedEvent single-publish invariant, AiUiSettingsResolver read-through chain, KnobInventory classification)
- [ ] No watch-mode flags (Gradle `test` runs to completion; no continuous mode)
- [ ] Feedback latency < 30s per task (Jmix `@JmixTest` boots are amortised across class loads)
- [ ] `nyquist_compliant: true` set in frontmatter after planner finalises the per-task verification map.

**Approval:** pending
