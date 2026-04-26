---
phase: 04
status: PASS
verified_at: 2026-04-20T00:00:00Z
must_haves_total: 7
must_haves_passed: 7
gaps: 0
---

# Phase 04: Orchestration Core Verification Report

**Phase Goal:** Stitch the Phase 3 tool surface to a real chat client via a cached singleton
`ChatClient` with verified advisor ordering, observable runs, user-scoped ownership opacity,
dual-layer chat memory, deterministic baseline, fault-isolated event fan-out, and a verification
suite that pins every invariant.

**Verified:** 2026-04-20
**Verifier:** Claude (gsd-verifier)
**Method:** Goal-backward — each promised invariant traced to production artifact + pinning test.

---

## Goal Achievement

### Observable Truths (from Phase 4 promised deliverables)

| #   | Truth                                                                                             | Artifact                                                              | Pinning Test                                          | Status |
| --- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | ----------------------------------------------------- | ------ |
| 1   | Cached singleton `ChatClient` with verified advisor order: Audit → Memory(+200) → Tool(+300, disableMemory) | `orchestration/ChatClientFactory.java` (@Configuration, single @Bean) | `AdvisorOrderStructuralTest.verifyAdvisorChainOrder`  | ✓ VERIFIED |
| 2   | Every chat turn and every tool call produces an `AiToolCallAudit` row that survives tool-side rollback (REQUIRES_NEW), tagged with `runId`/`kind`/`phase` | `audit/AuditWriter.java` (sole @Transactional REQUIRES_NEW surface); `audit/AuditAdvisor.java` (chat PRE/POST); `audit/ToolCallbackAuditDecorator.java` (tool PRE/POST); schema 080 adds runId/kind/phase columns | `AuditDurabilityTest.toolAuditRowSurvivesOuterRollback` + `OrchestrationIntegrationTest.askTwiceSameConversationProducesTwoChatAuditPairs` + `AuditWriterFieldMappingTest` | ✓ VERIFIED |
| 3   | D-09 opacity: `ConversationGateway` throws the identical `ConversationNotFoundException` for "doesn't exist" and "not yours" | `orchestration/ConversationGateway.java:78-83` — combined JPQL predicate `c.id = :id AND c.createdBy = :owner`; `ConversationNotFoundException.DEFAULT_MESSAGE` constant | `OwnershipOpacityTest.crossUserProbeReturnsSameExceptionAsMissingId` | ✓ VERIFIED |
| 4   | D-08 dual-layer memory: `ProjectingChatMemoryRepository` writes `AiMessage` rows in lockstep with `JdbcChatMemoryRepository` inside the same REQUIRED transaction | `orchestration/ProjectingChatMemoryRepository.java` @Primary @Component; delete-then-insert mirroring JDBC semantics; every write path @Transactional | `DualLayerParityTest.afterAskBothLayersHaveSameMessageCount` (asserts equal count, role, content, order) | ✓ VERIFIED |
| 5   | Deterministic baseline: system prompt composed via `BaselineContextProvider.renderAsText()` (text mode, B-NEW-1) | `orchestration/BaselineContextProvider.java` (TreeMap sort, `key=value\n`); `DefaultChatServiceImpl.java:75-79` calls `renderAsText(convId)` and prepends to profile prompt | `BaselineContextProviderTest.composeRendersAsTextWithSortedAgentKeys` (alphabetical determinism) | ✓ VERIFIED |
| 6   | Event fan-out: `AuditListener` notifications fire inside `afterCommit`, fault-isolated per listener | `audit/AuditWriter.java:145-151` (`registerSynchronization` + `afterCommit`); `audit/AuditListenerFanOut.java` (per-listener try/catch Throwable) | `AuditListenerFanOutTest.throwingListenerDoesNotBlockOthers` | ✓ VERIFIED |
| 7   | Full verification suite + `@Tag("live")` smoke test against real OpenRouter, excluded from default `test` task | 9 test files in `test/java/com/vn/agent/{orchestration,audit,live}/`; `ai-agent.gradle:50` `excludeTags 'live'`; `ChatServiceLiveSemanticTest` has `@Tag("live")` + `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` | All 9 test files verified present + structurally correct | ✓ VERIFIED |

**Score:** 7/7 truths verified.

---

### Required Artifacts

| Artifact                                                                  | Expected                                                            | Status     |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------- | ---------- |
| `orchestration/ChatClientFactory.java`                                    | @Configuration @Bean producing singleton ChatClient                 | ✓ VERIFIED |
| `orchestration/ConversationGateway.java`                                  | loadOrCreate with D-09 opacity + D-08 title truncation              | ✓ VERIFIED |
| `orchestration/ProjectingChatMemoryRepository.java`                       | @Primary @Component decorator over JdbcChatMemoryRepository         | ✓ VERIFIED |
| `orchestration/BaselineContextProvider.java`                              | renderAsText deterministic                                          | ✓ VERIFIED |
| `orchestration/AiParametersResolver.java`                                 | resolveActive + effective* accessors                                | ✓ VERIFIED |
| `orchestration/ChatResponseDto.java`                                      | 5-component DTO (conversationId, runId, content, model, latencyMs) | ✓ VERIFIED |
| `orchestration/RunContext.java` + `ConversationNotFoundException.java`    | ThreadLocal + single-message exception                              | ✓ VERIFIED |
| `audit/AuditWriter.java`                                                  | 3 @Transactional(REQUIRES_NEW) methods, no self-invocation          | ✓ VERIFIED |
| `audit/AuditAdvisor.java`                                                 | HIGHEST_PRECEDENCE CallAdvisor, injects AuditWriter (no @Transactional) | ✓ VERIFIED |
| `audit/ToolCallbackAuditDecorator.java`                                   | PRE/POST rows via injected AuditWriter; 4096-char resultSummary cap | ✓ VERIFIED |
| `audit/AuditListenerFanOut.java`                                          | try/catch(Throwable) per listener                                   | ✓ VERIFIED |
| `audit/ToolCallAdvisorBuilderProbe.java`                                  | OQ-1 closure constants (disableMemory, advisorOrder, conversationHistoryEnabled) | ✓ VERIFIED |
| `DefaultChatServiceImpl.java`                                             | Full orchestration body; pre-allocated runId; text baseline          | ✓ VERIFIED |
| `liquibase/changelog/080-ai-tool-call-audit-runid.xml`                    | RUN_ID / KIND / PHASE / PROMPT_HASH / ERROR_CLASS + index            | ✓ VERIFIED |
| 9 test files (Advisor, Orchestration, Ownership, DualLayer, Durability, FanOut, FieldMapping, Live, StubChatModel) | All present on disk; structure matches SUMMARY claims                | ✓ VERIFIED |

---

### Key Link Verification (Wiring)

| From                          | To                              | Via                                                                  | Status |
| ----------------------------- | ------------------------------- | -------------------------------------------------------------------- | ------ |
| `DefaultChatServiceImpl.ask`  | `AuditAdvisor` (via ChatClient) | advisor context key `audit.runId` set in `.advisors(spec -> ...)`   | ✓ WIRED |
| `AuditAdvisor`                | `AuditWriter`                   | constructor injection — no self-invocation; calls proxy              | ✓ WIRED |
| `ToolCallbackAuditDecorator`  | `AuditWriter`                   | constructor injection of proxy                                       | ✓ WIRED |
| `AuditWriter.writeToolCall`   | `AuditListenerFanOut`           | `TransactionSynchronizationManager.registerSynchronization` afterCommit | ✓ WIRED |
| `ChatClient`                  | `AuditAdvisor` + memory + tool  | `.defaultAdvisors(auditAdvisor, memoryAdvisor, toolCallAdvisor)`     | ✓ WIRED |
| `MessageChatMemoryAdvisor`    | `ProjectingChatMemoryRepository`| `@Primary` decorator over `JdbcChatMemoryRepository` via `ChatMemory` bean | ✓ WIRED |
| `ConversationGateway`         | `AiConversation.createdBy`      | JPQL `c.id = :id AND c.createdBy = :owner`                           | ✓ WIRED |
| `DefaultChatServiceImpl`      | `BaselineContextProvider`       | `renderAsText(convId)` → string prepend to system prompt             | ✓ WIRED |

---

### Requirements Coverage

| Requirement                        | Source Plan            | Status       | Evidence                                               |
| ---------------------------------- | ---------------------- | ------------ | ------------------------------------------------------ |
| ORCH-01 (ChatClient per request)   | 04-04                  | ✓ SATISFIED  | ChatClientFactory + DefaultChatServiceImpl per-request prompt() |
| ORCH-02 (advisor order)            | 04-04                  | ✓ SATISFIED* | Audit/Memory+200/Tool+300 verified; RAG advisor (+250) scheduled for Phase 5 (deferred — ROADMAP says "ORCH-01..06"). |
| ORCH-03 (dual-layer projection)    | 04-04                  | ✓ SATISFIED  | ProjectingChatMemoryRepository + DualLayerParityTest   |
| ORCH-04 (user-scoped conversation) | 04-04                  | ✓ SATISFIED  | ConversationGateway + OwnershipOpacityTest             |
| ORCH-05 (ChatService public API)   | 04-04                  | ✓ SATISFIED  | ask(userId, conversationId, message) signature; stream deferred to future phase per plan note |
| AUD-02 (REQUIRES_NEW durability)   | 04-03                  | ✓ SATISFIED  | AuditWriter REQUIRES_NEW + AuditDurabilityTest         |
| AUD-04 (AuditListener fan-out)     | 04-03                  | ✓ SATISFIED  | AuditListenerFanOut + afterCommit sync                 |
| AUD-05 (audit not silently disabled) | 04-03                | ✓ SATISFIED  | AuditWriterFieldMappingTest reflective B1-name guard; AdvisorOrderStructuralTest pins AuditAdvisor presence |
| SPI-06 (AuditListener observe)     | 04-03                  | ✓ SATISFIED  | AuditListenerFanOutTest.throwingListenerDoesNotBlockOthers |
| TEST-02 (unit tests)               | 04-02, 04-03, 04-05    | ✓ SATISFIED  | AiParametersResolverTest, BaselineContextProviderTest, AuditWriterFieldMappingTest |
| TEST-03 (integration tests)        | 04-05                  | ✓ SATISFIED  | OrchestrationIntegrationTest + AdvisorOrderStructuralTest + others |
| TEST-05 (live semantic check)      | 04-05                  | ✓ SATISFIED  | ChatServiceLiveSemanticTest with @Tag("live") + @EnabledIfEnvironmentVariable |

*Note on ORCH-02: ROADMAP lists a RAG advisor slot at `+250` between Memory and Tool. Phase 4 built only Audit/Memory/Tool (no RAG advisor) — RAG is explicitly Phase 5 scope. Memory at +200 and Tool at +300 leave an untaken +250 slot that Phase 5's `QuestionAnswerAdvisor` will occupy. This matches the phased roadmap and is not a Phase 4 gap.

---

### Anti-Patterns / Review Findings

Carried from `04-REVIEW.md` — all MEDIUM+LOW items, **zero CRITICAL or HIGH**:

| ID     | File                                  | Severity | Impact on Goal                                                      |
| ------ | ------------------------------------- | -------- | ------------------------------------------------------------------- |
| MD-01  | ConversationGateway                   | Medium   | Manual `setCreatedDate` competes with Jmix @CreatedDate auditing. No behavioral failure.  |
| MD-02  | AiParametersResolver.buildFallback    | Medium   | YAML string-concat unsafe against non-trivial default systemPrompt. Current default safe.  |
| MD-03  | AuditWriter (stale conversationId)    | Medium   | Silent null FK on deleted conversation; no WARN log.                |
| MD-04  | ToolCallbackAuditDecorator            | Medium   | `argumentsJson` uncapped; `resultSummary` capped 4096.              |
| LO-01  | ChatClientFactory                     | Low      | Fallback system prompt literal (not i18n'd) — arguably correct for model text. |
| LO-02  | ProjectingChatMemoryRepository read   | Low      | Missing `readOnly=true` on findByConversationId @Transactional.      |
| LO-03  | AuditAdvisor.hashPrompt fallback      | Low      | toString() fallback not stable across option changes.               |
| LO-04  | BaselineContextProvider.extractUserKey | Low     | Broad reflection on `getKey()` could publish unintended host values. |

None block the phase goal. All are polish/hardening items appropriate to defer or accept.

---

### Behavioral Spot-Checks

Default build behavior claimed in `04-05-SUMMARY`: `./gradlew :ai-agent:ai-agent:test` → 18 suites, 102 tests, 0 failures, 0 errors (verifier did not re-run per instructions). Spot-checks instead:

| Behavior                                                      | Check                                                                       | Result | Status |
| ------------------------------------------------------------- | --------------------------------------------------------------------------- | ------ | ------ |
| Live test excluded from default task                          | `grep "excludeTags 'live'" ai-agent.gradle`                                 | match (line 50) | ✓ PASS |
| AuditAdvisor does not self-invoke AuditWriter                 | No `this.writeChatPre` / `this.writeChatPost` in AuditAdvisor.java          | Confirmed — only `auditWriter.writeXxx(...)` proxy calls | ✓ PASS |
| AuditWriter methods are REQUIRES_NEW                          | grep @Transactional in AuditWriter.java                                     | 3 method annotations with `Propagation.REQUIRES_NEW` | ✓ PASS |
| afterCommit synchronization registered                        | grep `registerSynchronization` + `afterCommit` in AuditWriter               | Present at lines 145-151 | ✓ PASS |
| Advisor order constants match test assertions exactly         | `HIGHEST_PRECEDENCE + 200` / `+ 300` in ChatClientFactory vs test           | Exact match | ✓ PASS |
| `disableMemory()` actually disables ToolCallAdvisor memory    | ChatClientFactory calls `.disableMemory()`; test reflects `conversationHistoryEnabled == FALSE` | Confirmed | ✓ PASS |

---

### Human Verification Required

None. All invariants are structurally verifiable; behavioral assertions are pinned by the 9
test files listed, and the live smoke path is gated behind OPENROUTER_API_KEY + @Tag("live")
for ops-level exercise when credentials available.

---

### Gaps Summary

None. Every promised Phase 4 invariant has a concrete production artifact and a pinning test.
Code review MEDIUM/LOW items are polish for a hardening phase; they do not block the phase goal.

---

_Verified: 2026-04-20_
_Verifier: Claude (gsd-verifier)_
