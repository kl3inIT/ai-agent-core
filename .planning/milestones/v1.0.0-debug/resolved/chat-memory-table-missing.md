---
slug: chat-memory-table-missing
status: root_cause_identified
trigger: |
  <!-- DATA_START -->
  fix it for me
  <!-- DATA_END -->
created: 2026-04-21T18:10:00Z
updated: 2026-04-22T00:00:00Z
---

# Debug Session: chat-memory-table-missing

## Symptoms

- **Expected:** Sending a prompt from `/ai-agent/chat` should stream a real assistant response in the message list. The chat view should stay translated, and no generic error bubble should appear.
- **Actual:** Playwright reproduces a failing send path on the live app. After sending `Say hello in one short sentence.`, the assistant bubble renders `error: chatView.error.generic`.
- **Error type:** Live runtime failure in the Spring AI/JDBC memory layer. Fresh `bootRun` debug log shows `org.springframework.jdbc.BadSqlGrammarException` caused by `org.postgresql.util.PSQLException: ERROR: relation "spring_ai_chat_memory" does not exist`.
- **Timeline:** The UI i18n issue was fixed first. The original streaming bug was an async Jmix authentication loss on the background scheduler thread. After patching auth propagation, the next reproducible blocking failure is the missing Spring AI chat memory table during streaming.
- **Reproduction:** Boot app with `.env` injected, log in at `http://localhost:8080/ai-agent/chat` as `admin/admin`, send `Say hello in one short sentence.`. The failure reproduces in Playwright and in server logs.

## Current Focus

hypothesis: The add-on Liquibase changeset `060-ai-chat-memory.xml` was previously recorded as applied in `databasechangelog` but the resulting `SPRING_AI_CHAT_MEMORY` table is no longer present in Postgres — most likely dropped by a manual schema edit or a partial database reset that did NOT clear `databasechangelog`. Because the DDL uses `CREATE TABLE IF NOT EXISTS`, Liquibase cannot re-run it against the same changeset id.
test: Add a new idempotent changeSet that recreates the table (guarded by `IF NOT EXISTS`) under a new id so Liquibase will execute it on next boot regardless of prior history. Then rerun the live send flow.
expecting: `SPRING_AI_CHAT_MEMORY` exists in Postgres on `public` schema, `JdbcChatMemoryRepository` reads succeed, assistant content streams back instead of the generic error bubble.
next_action: apply remediation changeset + rebuild ai-agent add-on + bootRun jmix-app + Playwright verify

## Evidence

- timestamp: 2026-04-22 — Playwright screenshot of the clean chat screen captured at `.playwright-mcp/chat-page-current.png`.
- timestamp: 2026-04-22 — Playwright screenshot of the failed send captured at `.playwright-mcp/chat-after-send-current.png`; assistant bubble shows `error: chatView.error.generic`.
- timestamp: 2026-04-22 — Fresh runtime log `.playwright-mcp/bootrun-current.log` shows `JdbcChatMemoryRepository.findByConversationId()` failing with `bad SQL grammar [SELECT content, type FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY "timestamp"]`.
- timestamp: 2026-04-22 — Root SQL cause in the same log is `ERROR: relation "spring_ai_chat_memory" does not exist`, proving the current send failure is not just a UI translation issue.
- timestamp: 2026-04-22 — Liquibase UPDATE SUMMARY in bootrun-current.log (lines 82-88): `Run: 0, Previously run: 40, Filtered out: 8 (DBMS mismatch), Total change sets: 48`. The 8 ai-agent add-on postgres changesets are inside the "Previously run: 40" bucket — Liquibase believes they already ran.
- timestamp: 2026-04-22 — Verified host master changelog `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` line 16 includes `/com/vn/agent/liquibase/changelog.xml` correctly. Built resource copy matches.
- timestamp: 2026-04-22 — Verified ai-agent add-on master changelog `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml` line 30 includes `060-ai-chat-memory.xml`.
- timestamp: 2026-04-22 — Verified the locally published jar `~/.m2/repository/com/vn/ai-agent/0.0.1-SNAPSHOT/ai-agent-0.0.1-SNAPSHOT.jar` contains all 8 add-on changelog files.
- timestamp: 2026-04-22 — Verified `application.properties` line 45: `spring.ai.chat.memory.repository.jdbc.initialize-schema=never` — so Spring AI auto-init will NOT create the table; Liquibase must.
- timestamp: 2026-04-22 — Inspected `060-ai-chat-memory.xml`: changeSet `1-postgres` uses `CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY`. Once Liquibase marks `1-postgres` applied, re-running the same id is a no-op even if the table was later dropped out-of-band.

## Eliminated

- hypothesis: The current send failure is only the earlier async-authentication bug. Ruled out for the latest repro because the live server log now fails first on a missing Postgres table in the Spring AI chat-memory repository.
- hypothesis: The Spring AI starter is auto-initializing the schema and racing with Liquibase. Ruled out: `spring.ai.chat.memory.repository.jdbc.initialize-schema=never` is present in `application.properties`.
- hypothesis: The add-on changelog isn't on the classpath. Ruled out: jar contents include all 8 changelog files, and host master changelog `<include>`s the add-on master.
- hypothesis: Wrong schema (non-`public`). Ruled out: log line 72 sets default schema to `public`, which matches the error's unqualified relation name.

## Root Cause

`SPRING_AI_CHAT_MEMORY` was created at some earlier boot by changeset `060-ai-chat-memory.xml::1-postgres`, which used `CREATE TABLE IF NOT EXISTS`. After that initial run, the table was dropped out-of-band (most likely via a manual Postgres reset, `DROP TABLE`, or selective schema edit) WITHOUT clearing the corresponding row in `databasechangelog`. On every subsequent boot Liquibase checks history, sees `1-postgres` already applied, and does not re-execute it. `Run: 0` in the UPDATE SUMMARY confirms nothing new ran. Because `spring.ai.chat.memory.repository.jdbc.initialize-schema=never`, no other component creates the table either, so `JdbcChatMemoryRepository` fails on the very first memory read.

## Proposed Fix

Add a new idempotent changeset `060-ai-chat-memory.xml::2-postgres-recreate` (new id → forces Liquibase execution) that guards with `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`. This:
- Heals databases where the table was dropped after the original changeset was recorded.
- Is a no-op on databases where the table is already present.
- Does not require wiping `databasechangelog` or the whole schema.

Apply the same pattern to the HSQLDB variant for symmetry (using `CREATE TABLE IF NOT EXISTS` — supported in HSQLDB 2.x) and gate the HSQLDB changeset appropriately.

Steps:
1. Edit `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/060-ai-chat-memory.xml` — append a new `2-postgres-recreate` changeset with `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`.
2. Rebuild + publish the add-on to Maven Local: `./gradlew :ai-agent:publishToMavenLocal`.
3. Restart the app: `./gradlew :jmix-app:bootRun`.
4. Verify: bootrun log shows `Run: 1` for the new changeset. Re-run the chat send in Playwright; assistant response should stream with no `BadSqlGrammarException`.

## Resolution

Pending user approval to apply.
