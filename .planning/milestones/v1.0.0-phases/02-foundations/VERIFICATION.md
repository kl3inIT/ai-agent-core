---
phase: 02-foundations
verified: 2026-04-19T00:00:00Z
status: passed
score: 14/14 must-haves verified (automated)
overrides_applied: 0
human_verification: []
closure_reason: "old dynamic verification debt closed obsolete on 2026-04-26 so Phase 8 can be scoped fresh from the current codebase"
---

# Phase 2: Foundations Verification Report

**Phase Goal:** Land all persistent entities, Liquibase changelogs, security roles, and SPI interface contracts that downstream phases depend on.
**Verified:** 2026-04-19
**Status:** passed (old dynamic verification debt closed obsolete on 2026-04-26; Phase 8 will define fresh release checks from the current codebase)
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (roadmap Success Criteria + plan must-haves)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | 5 JPA entities exist with UUID + `@Version` + `@InstanceName`, no Lombok | PASS | `entity/AiConversation.java`, `AiMessage.java`, `AiToolCallAudit.java`, `AiParameters.java`, `AiKnowledgeDocument.java` — all use `@JmixEntity`, `@JmixGeneratedValue` on UUID id, `@Version`, `@InstanceName` (field or method), no Lombok imports |
| 2 | 3 enums (scope: 3 per ENT-01) declared + i18n | PASS | `AiMessageRole`, `AiToolCallOutcome`, `AiKnowledgeDocumentStatus` all implement `EnumClass<String>`; keys for all values in `messages.properties` + `messages_vi.properties` |
| 3 | `AiMessage @Composition` → `AiConversation` with `@OnDelete(CASCADE)` | PASS | `AiConversation.java:49-52`, `AiMessage.java:32-35` |
| 4 | Liquibase step-numbered changelogs 010..070 exist with `AI_AGENT_*` prefix | PASS | `liquibase/changelog/010-ai-conversation.xml` ... `070-ai-kb-vector-store.xml` present; DDL uses `AI_AGENT_*` prefix verified on 010 |
| 5 | Master `changelog.xml` includes all step files | PASS | `liquibase/changelog.xml` uses `<includeAll path="/com/vn/agent/liquibase/changelog"/>` |
| 6 | Host `jmix-app` changelog `<include>`s add-on master (D-02) | PASS | `jmix-app/.../liquibase/changelog.xml:15` explicitly includes `/com/vn/agent/liquibase/changelog.xml` |
| 7 | Spring AI chat-memory JDBC DDL ported (ENT-03) | PASS | `060-ai-chat-memory.xml` creates `SPRING_AI_CHAT_MEMORY` with postgres + hsqldb variants (verbatim port of Spring AI 1.1.4 schema) |
| 8 | pgvector extension + `AI_AGENT_KB_VECTOR_STORE` DDL gated by `dbms="postgresql"` (ENT-04) | PASS | `070-ai-kb-vector-store.xml` uses `dbms="postgresql"` attr + `<preConditions onFail="MARK_RAN" dbms="postgresql">` belt-and-suspenders; CREATE EXTENSION vector + HNSW index present |
| 9 | 6 SPI interfaces in `com.vn.agent.spi` with substantive Javadoc | PASS | `ToolContributor`, `ContextContributor`, `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester` all present (plus supporting `ToolVetoedException`); each has `@Javadoc` with example usage |
| 10 | `SpiDefaultsAutoConfiguration` declares 6 `@ConditionalOnMissingBean` no-op beans | PASS | `SpiDefaultsAutoConfiguration.java` has exactly 6 `@Bean @ConditionalOnMissingBean` methods — one per SPI; `@AutoConfigureAfter(AIAutoConfiguration.class)` |
| 11 | Auto-config registered in `AutoConfiguration.imports` | PASS | File lists both `AIAutoConfiguration` and `SpiDefaultsAutoConfiguration` |
| 12 | 3 security roles: `AiAgentUserRole`, `AiAgentAdminRole`, `AiAgentUserRowLevelRole` (row-level via `:current_user_username`) | PASS | `AiAgentUserRole` (resource), `AiAgentAdminRole` (resource, ALL actions on 5 entities), `AiAgentUserRowLevelRole` (@JpqlRowLevelPolicy using `:current_user_username` on both `AiConversation` and `AiMessage.conversation.createdBy`) |
| 13 | `@JmixModule` on `AIConfiguration` widened to include `DataConfiguration` + `SecurityConfiguration` | PASS | `AIConfiguration.java:23-28` — `dependsOn = { DataConfiguration, SecurityConfiguration, EclipselinkConfiguration, FlowuiConfiguration }` |
| 14 | Boot smoke test `FoundationsBootSmokeTest` covers all 5 assertions | PASS | `FoundationsBootSmokeTest.java` — exactly 5 `@Test` methods: `liquibase_applies_on_hsqldb`, `all_five_entities_round_trip`, `row_level_policy_restricts_conversation_visibility`, `all_six_spi_defaults_autowire`, `role_catalog_has_all_three_roles`; asserts pgvector table ABSENT on HSQLDB |
| 15 | Doc sync (REQUIREMENTS/ROADMAP/PROJECT) reflects D-10 scope reductions | PASS | `REQUIREMENTS.md` updated: ENT-01 = "Five entities", SPI-04/SPI-08 dropped, UI-07 removed, TEST-06 converted, D-10 trace row present |
| 16 | i18n EN + VI for every entity, enum, role | PASS | `messages.properties` (73 lines) + `messages_vi.properties` (73 lines); all 3 enums, 5 entities, 3 roles, all fields have keys in both files |

**Score:** 16/16 automated truths verified. No roadmap SC or plan must-have has a static-artifact gap.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/*.java` | 5 entities + 3 enums | PASS | All 8 files present |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/*.java` | 6 SPI interfaces | PASS | 6 interfaces + `ToolVetoedException` |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/security/*.java` | 3 roles | PASS | User / Admin / UserRowLevel |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/*.xml` | 7 step files | PASS | 010..070 |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml` | master include | PASS | `includeAll` |
| `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` | include add-on master | PASS | line 15 |
| `ai-agent-starter/.../SpiDefaultsAutoConfiguration.java` | 6 default beans | PASS | 6 `@Bean @ConditionalOnMissingBean` |
| `ai-agent-starter/.../AutoConfiguration.imports` | list 2 configs | PASS | Both listed |
| `ai-agent/ai-agent/src/test/.../FoundationsBootSmokeTest.java` | 5 assertions | PASS | 5 `@Test` methods |
| `messages.properties` + `messages_vi.properties` | EN + VI | PASS | Both present, parity in line count |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `jmix-app/changelog.xml` | `com/vn/agent/liquibase/changelog.xml` | `<include file=...>` | WIRED | Explicit include present |
| `SpiDefaultsAutoConfiguration` | `spring.factories imports` | `AutoConfiguration.imports` | WIRED | Line 2 of imports file |
| `AIConfiguration` | Jmix security+data | `@JmixModule(dependsOn=...)` | WIRED | Both classes present in deps array |
| `AiAgentUserRowLevelRole` | Jmix session predicate | `:current_user_username` | WIRED | JPQL uses canonical Jmix session param key |
| `AiAgentUserRowLevelRole` | `AiMessage` | `{E}.conversation.createdBy` | WIRED | Traverses FK to conversation.createdBy |
| `FoundationsBootSmokeTest` | Starter auto-configs | `@ImportAutoConfiguration` | WIRED | Loads both AIAutoConfiguration + SpiDefaultsAutoConfiguration directly |

### Requirements Coverage

| Req | Description | Status | Evidence |
|-----|-------------|--------|----------|
| ENT-01 | 5 JPA entities | SATISFIED | 5 entity classes + smoke test round-trip |
| ENT-02 | `AI_AGENT_*` prefix | SATISFIED | All `@Table` names + Liquibase DDL use prefix; SPRING_AI_CHAT_MEMORY documented as exception |
| ENT-03 | Chat-memory DDL ported | SATISFIED | `060-ai-chat-memory.xml` postgres + hsqldb |
| ENT-04 | pgvector + KB vector store | SATISFIED | `070-ai-kb-vector-store.xml` gated by dbms=postgresql |
| SPI-01 | ToolContributor interface | SATISFIED | `spi/ToolContributor.java` + no-op default |
| SPI-02 | ContextContributor | SATISFIED | `spi/ContextContributor.java` + no-op default |
| SPI-03 | PromptContextContributor | SATISFIED | `spi/PromptContextContributor.java` + no-op default |
| SPI-05 | ToolGuard | SATISFIED | `spi/ToolGuard.java` + allow-all default |
| SPI-06 | AuditListener | SATISFIED | `spi/AuditListener.java` + no-op default |
| SPI-07 | CustomIngester | SATISFIED | `spi/CustomIngester.java` + noop default ingester |
| SEC-01 | AiAgentUserRole CRUD policies | SATISFIED | `AiAgentUserRole.java` READ/CREATE/UPDATE on Conversation + READ/CREATE on Message |
| SEC-02 | AiAgentAdminRole full CRUD | SATISFIED | `AiAgentAdminRole.java` EntityPolicyAction.ALL on 5 entities |
| SEC-03 | Entity-level policies only (no attribute) | SATISFIED | No `@EntityAttributePolicy` used — matches D-07 |
| SEC-04 | Row-level own-conversation predicate | SATISFIED | `AiAgentUserRowLevelRole` @JpqlRowLevelPolicy + smoke test asserts isolation |

All 14 requirements mapped to Phase 2 are SATISFIED at the static level.

### Anti-Patterns Found

None of significance. Entity classes use plain accessors (no Lombok); no TODO/FIXME markers in Phase 2 artifacts; no empty bodies where behavior is expected. Smoke test properly uses `systemAuthenticator.runWithSystem` / `withUser` for identity-scoped assertions rather than hand-rolled auth plumbing.

### Human Verification Required

None active. The previously listed Phase 2 runtime checks were closed as obsolete on
2026-04-26 by user direction because Phase 8 will be scoped from the current codebase and
fresh release-readiness criteria.

### Gaps Summary

**No active gaps.** Every must-have surfaced from PLAN frontmatter, ROADMAP Success Criteria, and CONTEXT decisions has been verified against concrete artifacts in the codebase. The old dynamic checks were closed as obsolete on 2026-04-26 and are not carried into Phase 8.

## Overall Verdict: **PASS**

The static / goal-backward verification confirms Phase 2 achieved its goal: all entities, DDL, security roles, SPI contracts, auto-config wiring, boot smoke test, and doc sync are in place and correctly wired. Scope reductions (D-05, D-09, D-10) are faithfully reflected in REQUIREMENTS.md.

---

_Verified: 2026-04-19_
_Verifier: Claude (gsd-verifier, opus-4.7)_
