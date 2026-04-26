# Project Milestones: Jmix AI Copilot (ai-agent-core)

## v1.0.0 MVP (Shipped: 2026-04-26)

**Delivered:** A reusable Jmix AI Copilot add-on with secure metadata tools, Spring AI orchestration, RAG, guardrails, Flow UI, audit tree, release docs, and CI.

**Phases completed:** 1-8 plus inserted 7.1 and 7.2 (63 plans total)

**Key accomplishments:**
- Packaged the add-on as `ai-agent` + `ai-agent-starter` with Spring Boot auto-configuration and Maven publishing metadata.
- Implemented metadata-first, read-only tool access through Jmix AccessManager and DataManager with prompt-injection-safe result formatting.
- Built ChatClient orchestration with JDBC chat memory, conversation projection, durable audit events, and SPI extension points.
- Added pgvector RAG ingestion/retrieval with role-scoped filters, async processing, delete/reingest flows, and status tracking.
- Delivered Flow UI for chat, conversations, parameters, knowledge base, and tree-lite audit inspection.
- Closed Phase 8 security/release gaps, including jmix-security-data enforcement, GitHub Actions CI, operator README, CHANGELOG, and broad regression green.

**Stats:**
- 10 phase directories, 63 plans, 63 summaries
- 529 files changed from initial commit to release HEAD
- 105255 insertions, 1060 deletions over the milestone range
- Repository text/code corpus at close: ~149096 lines across Java/XML/properties/Gradle/YAML/Markdown
- Timeline: 2026-04-18 → 2026-04-26

**Git range:** 566ccfb → dd0d13e before milestone archive commits

**Known deferred items at close:** 20 open planning artifacts acknowledged as deferred; see .planning/STATE.md Deferred Items.

**Known milestone debt:** PKG-05/TEST-07 clean-consumer smoke remains deferred from Plan 08-05; follow-up should choose either a stub VectorStore boot mode or a Testcontainers-backed consumer smoke.

**What's next:** Define the next milestone with `$gsd-new-milestone`; likely candidates are consumer-smoke hardening and prompt-contract/UI clarity work.

---


