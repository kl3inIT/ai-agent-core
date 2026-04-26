---
phase: 08
plan: 05
status: deferred
subsystem: build/smoke
tags: [test-only, consumer-smoke, deferred, TEST-07, R-05a, R-05b, R-05c, R-05d, R-05e]
requires:
  - com.vn:ai-agent-starter (published to mavenLocal)
  - com.vn.agent.AgentstoreStoreConfiguration
  - com.vn.autoconfigure.agent.AIAutoConfiguration
provides: []
affects:
  - none (no committed code — directory + settings.gradle reverted)
key-files:
  created: []
  modified: []
key-decisions:
  - "Plan 08-05 deferred after end-to-end pipeline failed at multiple layers — see Findings below. The starter as currently published cannot be consumed by a minimal HSQLDB-only host; a real consumer needs PostgreSQL + pgvector at minimum."
requirements-completed: []
duration: ~45min (investigation only — no code shipped)
completed: 2026-04-26
---

# Phase 8 Plan 05: Consumer-Smoke Subproject — DEFERRED

Attempted the full consumer-smoke pipeline per plan; surfaced a chain of starter-consumability gaps that cannot be papered over inside this plan's scope. Reverted all changes (deleted `consumer-smoke/` directory, reverted `settings.gradle`); no commits left on the branch.

## Findings (in failure order)

Each fix unblocked the next problem; the chain is what matters, not any single failure.

| # | Failure | Cause | Fix attempted | Result |
|---|---------|-------|--------------|--------|
| 1 | `agentstoreLiquibase`: `db/changelog/db.changelog-master.yaml does not exist` | Default Spring Boot Liquibase path; starter actually ships at `com/vn/agent/liquibase/agentstore-changelog.xml` | Added `agentstore.liquibase.change-log=...` | Unblocked |
| 2 | `entityManagerFactory`: No bean named `dataSource` | Jmix's `EclipselinkAutoConfiguration` expects a bean literally named `dataSource`; Spring Boot doesn't auto-create one from `main.datasource.*` alone | Added `@Bean @Primary DataSource dataSource()` mirroring `JmixAppApplication` | Unblocked |
| 3 | `core_SystemAuthenticator`: No `UserRepository` bean | Starter brings Jmix security but no user repository (jmix-app uses a custom `DatabaseUserRepository`); `TestUsersConfiguration` lives only in ai-agent's TEST classpath | Added `@Bean InMemoryUserRepository core_UserRepository()` | Unblocked |
| 4 | `VaadinSecurityFilterChainBean`: No `WebApplicationContext` | `WebEnvironment.NONE` doesn't supply one; Vaadin SpringBootAutoConfiguration needs it | Switched to `WebEnvironment.MOCK` | Unblocked |
| 5 | `VaadinSecurityFilterChainBean`: `View 'login' is not defined` | `DefaultFlowuiVaadinWebSecurity` defaults to `setLoginView("login")`; starter ships no login view (host concern by Jmix convention) | Excluded `SecurityFlowuiAutoConfiguration` via `@SpringBootApplication(exclude=...)` | Unblocked |
| 6 | `aiAgentVectorStore`: bad SQL grammar `CREATE EXTENSION IF NOT EXISTS vector` | `AIAutoConfiguration` constructs a pgvector `VectorStore` bean unconditionally on bean wiring; HSQLDB obviously cannot `CREATE EXTENSION vector`. The starter is hard-coded to PostgreSQL+pgvector for any non-stub vector store | **Deferred** — would require either Testcontainers Postgres+pgvector (heavy for a "smoke" test, defeats the in-memory premise) OR introducing a stub VectorStore bean exposed by the starter (production code change, out of scope) |

## Why deferred (not just RED)

The plan's premise — "verify a clean Jmix consumer can add `implementation 'com.vn:ai-agent-starter:1.0.0'` and boot" — is not currently achievable with the published starter. Each unblock above represents an undocumented prerequisite that the starter forces on consumers. Cumulatively, a real consumer must:

1. Pin `agentstore.liquibase.change-log` to the add-on's internal classpath path
2. Provide their own primary `DataSource` bean (no auto-config from `main.datasource.*`)
3. Provide a `UserRepository` bean
4. Provide a `LoginView` (or accept the security filter chain failing on missing view)
5. Have PostgreSQL with the pgvector extension installed (no in-memory option)

Any of #1–#4 are reasonable host responsibilities, but they are NOT documented in the operator README (Plan 08-06 will fix that). #5 is a hard runtime infrastructure dependency that effectively scopes consumer-smoke from "minimal in-memory boot" to "Testcontainers-backed integration test" — a categorically different test type.

## Recommendation

Two follow-up paths, recorded for 8.x or 9 phase planning:

1. **Quick fix:** add a stub VectorStore bean autoconfiguration to the starter (gated by `jmix.ai-agent.vector-store.enabled=false`) so HSQLDB-only hosts can boot. Then this plan's smoke test becomes feasible.
2. **Honest fix:** reframe the smoke as a Testcontainers integration test (pgvector + HSQLDB), accept the heavier runtime, and document that the add-on requires PostgreSQL+pgvector in production. This is closer to the actual deployment shape.

## What WAS produced (then reverted)

- `consumer-smoke/settings.gradle`, `consumer-smoke/consumer-smoke.gradle`
- `consumer-smoke/src/main/java/com/vn/consumersmoke/ConsumerSmokeApplication.java`
- `consumer-smoke/src/main/resources/application.properties`
- `consumer-smoke/src/main/resources/com/vn/consumersmoke/liquibase/changelog.xml`
- `consumer-smoke/src/test/java/com/vn/consumersmoke/BootSmokeTest.java`
- `settings.gradle` edit adding `includeBuild 'consumer-smoke'`

All deleted/reverted before this SUMMARY was written. The investigation work (which prerequisites the starter implicitly imposes on consumers) is captured in the Findings table above so the follow-up phase doesn't have to re-discover them.

## Self-Check: DEFERRED

The plan's success criteria cannot be met without either a stub VectorStore in the starter or a Testcontainers-backed test infrastructure. Both are out of scope for an integration-hardening phase that should not change production code or test infrastructure shape. Recommended for replan.
