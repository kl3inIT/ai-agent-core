# Phase 15 — Deferred / Out-of-Scope Items

## Pre-existing, environment-only: `:jmix-app:test` requires a running PostgreSQL

`./gradlew :jmix-app:test` fails in this environment with:

```
liquibase.exception.DatabaseException: org.postgresql.util.PSQLException: The connection attempt failed.
  Error creating bean 'agentstoreLiquibase' / 'agentstoreEntityManagerFactory'
  (com/vn/agent/AgentstoreStoreConfiguration.class)
```

…and the dependent `@SpringBootTest`s then trip the
`ApplicationContext failure threshold (1) exceeded` short-circuit (24 tests, 13 failed — all the
same root cause).

- **Not caused by Phase 15.** Phase 15's changes are test-only and live entirely in the
  `:ai-agent:ai-agent` module (which runs on HSQLDB / no DB) — `./gradlew :ai-agent:ai-agent:test`
  is fully green, including the new `ObservabilityLeakTest`, `ObservabilityMessagesCompletenessTest`,
  `NoNewPersistedStateTest`, and the extended `AiChatSessionStateTest`.
- **Root cause:** `jmix-app`'s integration tests need a live PostgreSQL `agentstore` datasource;
  one is not provisioned in this CI/dev environment. This is the same DB-dependent-integration-test
  situation noted in earlier phases.
- **Action:** Run `:jmix-app:test` against a real PostgreSQL (the project's documented dev DB) to
  confirm green. No code change required from Phase 15.
