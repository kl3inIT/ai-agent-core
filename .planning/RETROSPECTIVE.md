# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0.0 — MVP

**Shipped:** 2026-04-26
**Phases:** 10 phase directories | **Plans:** 63 | **Sessions:** multiple GSD phase sessions

### What Was Built
- Reusable Jmix add-on/starter with release metadata and operator documentation.
- Secure metadata-first read tools over host Jmix entities.
- Spring AI orchestration with chat memory, RAG, guardrails, structured output, and audit.
- Built-in Flow UI for chat, conversations, parameters, knowledge base, and audit.
- CI workflows and broad regression bars for the release branch.

### What Worked
- Phase summaries and verification docs made late Phase 8 gap closure tractable.
- RED-to-GREEN security tests in Phase 8 exposed a real missing jmix-security-data dependency before release.
- The project memory about UnconstrainedDataManager for system-internal writes was accurate and prevented scattershot role grants.

### What Was Inefficient
- Several planning docs lagged implementation state, especially REQUIREMENTS.md checkbox status.
- Clean-consumer smoke was planned as a light in-memory boot but empirically required a heavier Postgres/pgvector shape.
- Some UI follow-ups stayed as debug sessions/todos instead of being closed or explicitly promoted before milestone end.

### Patterns Established
- Treat AI as a normal Jmix client; rely on native security instead of a parallel exposure layer.
- Use UnconstrainedDataManager deliberately for infrastructure persistence that already has an upstream authorization boundary.
- Keep live/semantic tests opt-in and CI focused on deterministic non-live gates.

### Key Lessons
1. Registering the right Jmix security module matters as much as writing negative tests; missing jmix-security-data made AccessManager CRUD checks silently permissive.
2. A consumer smoke test must mirror the actual infrastructure contract. HSQLDB-only smoke is misleading for a pgvector-backed starter.
3. Planning artifacts need milestone-close normalization; otherwise stale unchecked requirements obscure the real shipped state.

### Cost Observations
- Model mix: not measured in this repo.
- Sessions: multiple phase execution sessions across 2026-04-18 to 2026-04-26.
- Notable: late broad-broom testing paid for itself by catching system-auth policy regressions before merge.

---

## Milestone: v1.1.0 — Prompt Hardening, Mutation Tools & Configurable Chat Surfaces

**Shipped:** 2026-05-11 (via PR #28)
**Phases:** 7 (9, 10, 11, 12, 13, follow-up 13.1, 14) | **Plans:** 62 | **Tasks:** 138

### What Was Built
- Prompt/tool-contract hardening: `agent.entities`/`agent.permissions` baseline, `describe_entity` via `MetadataTools`, `ToolFetchPlanCustomizer` SPI + projection-only `FetchPlanIntersector`, `unknown_entity` retry contract, output-scanner leak guards.
- Admin LLM-exposure denylist: `AiExposureRule` (entity-level `EXCLUDE` only) + `LlmExposurePolicy` (`userVisible AND NOT excluded`), enforced across schema discovery / tool calls / baseline prompt / RAG; uniform `unknown_entity` opacity; admin Flow UI + `VectorStoreDebugView`.
- Opt-in built-in mutation tools: `create/update/add_related/remove_related_record`, layered fail-closed gating, `AiMutationIntent` idempotency, `MutationGuard` SPI, PII-safe `MutationErrorTranslator`, end-to-end audit incl. rollback; always-on `BuiltInLinkTools`.
- Configurable chat surfaces: `FULL_ROUTE` + `HEADER_BUTTON` over one `ChatPanelFragment`, `AiUiSettings` toggle, `AiChatSessionState` continuity, async auto-title.
- Chat task files (13 + 13.1): `AiTaskFile` transient entity, attach UI, multimodal `Media` read, `bulk_save_records`, default model swap to Apache-2.0 `qwen/qwen3.6-35b-a3b`, CRM-style right-pane with per-turn-all injection + LRU budget cap.
- Intent-driven extraction → prefilled Jmix forms: `IntentExtractor<T>` SPI + prompt-only schema synthesis, `AiExtractionDraft`, `prepare_form_draft` / `propose_action_choices`, controller-side-only navigation, permission-gated prefill, host `CustomerDraftIntentExtractor`; LLM never gets `ViewNavigators`.

### What Worked
- The "foundations first" build-order paid off — Phase 9's single-boundary baseline (`getReadableSchema`) made Phase 10's exposure-policy substitution a near-mechanical call-site swap, and Phase 11's mutation chain dropped cleanly into Phase 13's `bulk_save_records`.
- Per-turn execute-phase verification (`VERIFICATION.md`) + gap-closure waves (11-12/11-13, 14-09/14-10) caught real defects before they compounded.
- Cross-AI peer review on the Phase 14 plan, then a pre-merge code review (6 WARNINGs, all fixed) + CI catch (test stub gap) — defense in depth on the riskiest phase.
- Source-scanner invariant tests (`ToolNavigationLeakScannerTest`, `DraftSetValueBypassScannerTest`, `TaskFileNoVectorStoreSourceScannerTest`, `CoreCustomerImportScannerTest`) turned architectural rules into green/red gates instead of review-only conventions.
- Project memory (`UnconstrainedDataManager` for system-internal writes; Jmix-first UI patterns; rich tool descriptions) was accurate and steered consistent choices.

### What Was Inefficient
- Phase 10's `VERIFICATION.md` was left at `human_needed` and never re-run after the REVIEW blockers were fixed in code — the milestone audit had to re-derive that the items were addressed. A `/gsd-verify-work` pass should follow any "fix in code, decision deferred" REVIEW item.
- `*-VALIDATION.md` (Nyquist) was only produced for Phase 14; the other six phases shipped without one.
- Two phase rewrites mid-milestone (Phase 13 STT-split on 2026-05-05; Phase 13.1 follow-up) — necessary, but the ROADMAP/REQUIREMENTS bookkeeping lagged each time (e.g. "6/7 plans executed" vs 7 checked; STT references scattered across docs).
- The local `origin` remote points at a GitLab mirror while PRs live on GitHub `kl3inIT/ai-agent-core` — every ship needed a manual `git remote add github` + dual push. Worth reconciling the remotes.
- Planning-doc churn from a markdown linter reformatting whole files made surgical edits flaky (had to re-read repeatedly).

### Patterns Established
- An AI-specific exposure layer is fine **when a concrete need exists** — but keep it `EXCLUDE`-only at the rule shape so it can only narrow, never widen, beneath Jmix permissions; compose as boolean AND; uniform `unknown_entity` opacity (never `access_denied`). (This consciously revises v1.0's "no parallel exposure layer" lesson.)
- Mutations: default OFF behind `@ConditionalOnProperty` + a bare marker role; layered fail-closed gating with the order written down; pre-save idempotency reservation; PII-safe canned error templates; self-audit exactly once (not double-wrapped).
- Keep the LLM out of UI control: tools return structured payloads, the controller navigates after an `AccessManager` view check, prefill goes through `setValueIfPermitted`; enforce with a navigation-leak source scanner.
- Strict-mode structured extraction is prompt-only (schema text), not runtime DTO bytecode.
- New entity = bundle its Liquibase changelog (in root `changelog.xml`) + all-locale messages + role policies in the same phase.

### Key Lessons
1. "Fix in code, defer the decision" REVIEW items still need a re-verification pass before milestone close — otherwise the phase's status doc lies and the audit has to do archaeology.
2. Build-order discipline (foundations → policy → mutations → features) makes downstream phases cheap; the single-boundary refactor in Phase 9 was the highest-leverage decision of the milestone.
3. Turn architectural invariants into source-scanner tests early — "the LLM must never import `ViewNavigators`" is a one-line test that's worth more than any amount of review vigilance.
4. Reconcile your git remotes before you need them; a mirror-vs-PR-host mismatch taxes every ship.

### Cost Observations
- Model mix: not measured in this repo.
- Sessions: multiple GSD phase sessions across 2026-04-27 → 2026-05-11, plus a ship/audit/close session on 2026-05-11.
- Notable: the Phase 14 ship session bundled ship + code-review + auto-fix + CI-fix in one pass — the multi-agent code-reviewer/code-fixer chain found and fixed 6 robustness defects and a CI-breaking test stub without a separate review cycle.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0.0 | multiple | 10 dirs / 63 plans | Established full GSD phase lifecycle from skeleton through release readiness |
| v1.1.0 | multiple + ship/audit/close | 7 phases / 62 plans / 138 tasks | Build-order discipline (foundations→policy→mutations→features); mid-milestone phase rewrites (13 STT-split, 13.1 follow-up); gap-closure waves; multi-agent ship+review+fix+CI in one pass; source-scanner architectural invariants |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0.0 | 236 unit/integration tests in broad Phase 8 broom; CI green on PR #3 | Not measured | Not measured |
| v1.1.0 | ~700 tests (full `:ai-agent:ai-agent:test` green); cross-phase integration audit PASS (8/8 wiring, 5/5 E2E); CI green on PR #28 | Not measured | Zero new core deps (Spring AI / Jmix / pgvector only) |

### Top Lessons (Verified Across Milestones)

1. Prefer Jmix-native security and data-access primitives — but a narrowing-only AI-specific exposure layer (`EXCLUDE`-only, composes as AND) is acceptable once a concrete "AI must see less than the user" need surfaces (v1.1 revised the v1.0 "no parallel layer" stance for this bounded case). `AccessManager` stays authoritative for actual access.
2. Use empirical broad-broom test evidence to widen scope when an architectural family of failures appears.
3. Build-order discipline pays compound interest: a single-boundary refactor early makes every downstream phase a near-mechanical extension.
4. Encode architectural invariants as source-scanner tests, and re-run a phase's verification after any "fixed in code, decision deferred" REVIEW item — stale status docs cost more later.
5. Planning artifacts need milestone-close normalization (checkbox state, scattered cross-refs, deferred items) — both v1.0 and v1.1 hit this.
