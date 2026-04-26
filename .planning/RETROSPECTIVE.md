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

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0.0 | multiple | 10 dirs / 63 plans | Established full GSD phase lifecycle from skeleton through release readiness |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0.0 | 236 unit/integration tests in broad Phase 8 broom; CI green on PR #3 | Not measured | Not measured |

### Top Lessons (Verified Across Milestones)

1. Prefer Jmix-native security and data access primitives; avoid parallel authorization layers until a concrete consumer need exists.
2. Use empirical broad-broom test evidence to widen scope when an architectural family of failures appears.
