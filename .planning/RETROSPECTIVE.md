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

## Milestone: v1.2.0 — Operator Experience & Runtime Performance

**Shipped:** 2026-06-12
**Phases:** 4 (15, 16, 17, 18) | **Plans:** 25

### What Was Built
- Right-sidebar chat surface & observability UX: `SIDEBAR` third surface over the same `ChatPanelFragment`, ephemeral KIND-keyed streaming-status line + collapsed per-turn tool-detail disclosure, driven by the existing `StreamingEvent` flux + `AiAuditEvent` tree (no new persisted "turn" entity), UI-layer leak test.
- Admin model picker + three-tier config-knob migration: curated open-weights `ComboBox` + custom escape hatch (validity checked at first use → per-request `ChatOptions`), Tier-1 runtime-editable / Tier-2 boot read-only / Tier-3 secret indicator-only taxonomy, `AiSettingsChangedEvent` eviction hook, SEC-08 secret-redaction invariants.
- Mutation-internals hardening: canonical `MutationGateChain` (five tools as thin adapters, `@Transactional` only on the save executor), constrained batch FK loads, memoized related-write metadata — byte-for-byte v1.1 parity (MUT-18 HOLDS).
- AI-runtime performance pass: per-turn `RunContext` memoization, app-wide denylist cache evicted on `LlmExposureChangedEvent`, RAG `Filter.Expression` once per retrieval, task-file `Media` regression-locked — each with a checkable proxy; 865 tests green.

### What Worked
- The hard ordering constraint (Phase 17 hardening → Phase 18 perf) paid off exactly as designed: extracting the canonical `MutationGateChain` first meant the perf pass memoized one chain + one batch-FK load, not a duplicated sequence.
- "Checkable proxy per optimization" (SELECT-count via `datasource-proxy`, "1 query not N", call-count) turned an invisible perf pass into green/red gates — no benchmark harness needed, and the existing security/exposure/audit/RAG suites stayed the authority on correctness.
- Phase 16's `AiSettingsChangedEvent` single-publish-site eviction hook was designed in Phase 16 specifically as Phase 18's settings-cache invalidation contract — cross-phase dependency planned up front, wired cleanly.
- MUT-18 parity was enforced mechanically: the full Phase 9/10/11 mutation suite passing with a git-diff audit proving zero test-body edits is a stronger guarantee than any manual "behaves the same" review.
- Source-level invariants again carried their weight (gate-order test, secret-redaction scan, no-`@Transactional`-on-chain, build-dependency invariant forbidding jmh/gatling/caffeine).

### What Was Inefficient
- Working-doc checkbox drift recurred for the third milestone running — TEST-19 / TEST-20 / SEC-08 shipped in code (15-05 / 16-03 / 16-06) but stayed unchecked in `REQUIREMENTS.md`; the Phase 16 plan checkboxes (16-08/16-09) lagged the on-disk summaries. Normalized at close, again.
- Phase 16 swelled mid-milestone: the 16+17 merge, then a UAT that closed after 3/16 tests with the UI Settings consolidation shipped as unnumbered in-place refactor commits. Hard to reconstruct "what is Phase 16" from the ROADMAP alone.
- Two bulk-create defects (`bulk-create-allowlist-collision`, `bulk-create-confirm-throws`) were opened as debug sessions and carried open across the whole milestone instead of being closed or explicitly scheduled — same "UI/agent follow-ups linger as debug sessions" pattern flagged in v1.0.
- Phase 17 UAT (4 scenarios) never ran — parity was proven by the automated suite, but the milestone closed with the manual UAT still `testing`.
- Voice Input (Phase 19) sat in the milestone name and scope from 2026-05-11 but was never started; carrying an unstarted headline feature for a month inflated the milestone's apparent scope until it was descoped at close.

### Patterns Established
- Invisible passes (perf, hardening) must ship a checkable proxy per change and lean on the existing suites for correctness — refactor-with-parity is a test-diff guarantee, not a review opinion.
- Design the cross-phase invalidation/eviction contract in the producing phase (`AiSettingsChangedEvent` in Phase 16 for Phase 18) rather than retrofitting it in the consumer.
- A third UI surface should reuse the one fragment + one backend + one session-state, never fork them — `SIDEBAR` over `ChatPanelFragment` kept continuity for free.
- Don't let a headline feature ride in a milestone's name/scope while unstarted — either start it or descope it early; renaming the milestone at close to match what shipped is the honest record but a late correction.

### Key Lessons
1. Ordering hardening-before-perf is the right shape for "make it invisible" milestones — consolidate the duplication first, then optimize the single path; the reverse would have memoized a sequence about to be deleted.
2. Parity refactors are cheap to trust when "zero test-body edits + full suite green" is the gate; spend the effort on the test-tree git-diff audit, not on re-reviewing behavior by eye.
3. Milestone-close planning-doc normalization is now a standing tax across v1.0/v1.1/v1.2 — worth a lightweight checkbox-sync step at each phase ship, not a big reconciliation at close.
4. Open debug sessions are scope: close them, schedule them, or descope them before milestone end — don't carry `fixing`/`awaiting_human_verify` sessions silently into the next milestone.

### Cost Observations
- Model mix: not measured in this repo.
- Sessions: multiple GSD phase sessions across 2026-05-11 → 2026-06-12 (Phase 15 mid-May; 17/18 late-May to early-June), plus a close session on 2026-06-12.
- Notable: the perf pass (Phase 18) shipped five optimizations behind proxies with the full suite (865 tests) green and zero new dependencies — the "no benchmark harness" bet held.

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0.0 | multiple | 10 dirs / 63 plans | Established full GSD phase lifecycle from skeleton through release readiness |
| v1.1.0 | multiple + ship/audit/close | 7 phases / 62 plans / 138 tasks | Build-order discipline (foundations→policy→mutations→features); mid-milestone phase rewrites (13 STT-split, 13.1 follow-up); gap-closure waves; multi-agent ship+review+fix+CI in one pass; source-scanner architectural invariants |
| v1.2.0 | multiple + close | 4 phases / 25 plans | "Make it invisible" milestone (observability + admin tuning + hardening + perf); hard ordering hardening→perf; checkable-proxy-per-optimization; cross-phase eviction contracts designed in the producing phase; descoped an unstarted headline feature (Voice Input → Backlog 999.2) and renamed the milestone to match what shipped |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0.0 | 236 unit/integration tests in broad Phase 8 broom; CI green on PR #3 | Not measured | Not measured |
| v1.1.0 | ~700 tests (full `:ai-agent:ai-agent:test` green); cross-phase integration audit PASS (8/8 wiring, 5/5 E2E); CI green on PR #28 | Not measured | Zero new core deps (Spring AI / Jmix / pgvector only) |
| v1.2.0 | 865 tests green (full `:ai-agent:ai-agent:test`); MUT-18 parity via Phase 9/10/11 suites unchanged (zero test-body edits); perf proxies (SELECT-count / call-count) per optimization | Not measured | Zero new core deps; build-dependency invariant forbids jmh/gatling/caffeine |

### Top Lessons (Verified Across Milestones)

1. Prefer Jmix-native security and data-access primitives — but a narrowing-only AI-specific exposure layer (`EXCLUDE`-only, composes as AND) is acceptable once a concrete "AI must see less than the user" need surfaces (v1.1 revised the v1.0 "no parallel layer" stance for this bounded case). `AccessManager` stays authoritative for actual access.
2. Use empirical broad-broom test evidence to widen scope when an architectural family of failures appears.
3. Build-order discipline pays compound interest: a single-boundary refactor early makes every downstream phase a near-mechanical extension.
4. Encode architectural invariants as source-scanner tests, and re-run a phase's verification after any "fixed in code, decision deferred" REVIEW item — stale status docs cost more later.
5. Planning artifacts need milestone-close normalization (checkbox state, scattered cross-refs, deferred items) — v1.0, v1.1, AND v1.2 all hit this; the recurring tax argues for a per-phase-ship checkbox-sync step rather than a big close-time reconciliation.
6. For "invisible" passes (perf, hardening), ship a checkable proxy per change and let the existing suites own correctness — order hardening before perf so you optimize a consolidated path, not a duplicated one; and don't carry an unstarted headline feature in a milestone's name — start it or descope it early (v1.2's Voice Input rode the scope a month before descoping).
