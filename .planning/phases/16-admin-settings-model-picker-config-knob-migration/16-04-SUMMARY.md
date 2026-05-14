---
phase: 16-admin-settings-model-picker-config-knob-migration
plan: 04
subsystem: orchestration
tags: [jmix-services, orchestration, runtime-knobs]
requires:
  - AiUiSettings entity with 12 Tier-1 columns (Plan 16-02)
  - AiSettingsChangedEvent + KnobMetadata + AuditWriter overload (Plan 16-01 Wave-0 foundation)
  - AiUiSettingsResolverReadThroughTest scaffold (Plan 16-01)
  - TtlConfigSentinelSurvivesAiUiSettingsTest scaffold (Plan 16-01)
provides:
  - AiUiSettingsResolver @Component (12 typed resolveXxx() methods, DB → @ConfigurationProperties fall-through)
  - Caller-side wiring at 8 actual consumer sites
  - 32 green test cases across two flipped scaffolds
affects:
  - AiTaskFileCleanupJob (resolver consulted per cleanup tick)
  - AiTaskFileMediaResolver (resolver consulted per chat turn for TTL + per-turn caps)
  - ChatPanelFragment (resolver consulted per upload + per cleanup)
  - BuiltInMutationTools (resolver consulted for idempotency TTL + bulk DoS cap)
  - BaselineContextProvider (resolver consulted per prompt render)
  - StructuredFilterConditionMapper (resolver consulted per filter map — replaces @Value injection)
  - AiConversationTitleService (resolver consulted per title attempt)
  - KnowledgeBaseView (resolver consulted on dropzone setup)
tech_stack_added: []
patterns_used:
  - "Plan 10-06 R2 single-publish-site invariant (resolver does NOT inject ApplicationEventPublisher)"
  - "Phase 12 D-15 UnconstrainedDataManager + singleton load (Pitfall 3 — non-admin chat turns must succeed)"
  - "AiParametersResolver Pattern C (try/catch RuntimeException + WARN log + property fallback)"
  - "Pure-JUnit / Mockito test workaround for the pre-existing Phase 11/13 @SpringBootTest boot regression (mirrors Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, 16-02, 16-03)"
key_files_created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiUiSettingsResolver.java
key_files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/StructuredFilterConditionMapper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsResolverReadThroughTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/filter/StructuredFilterConditionMapperTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/conversation/AiConversationTitleServiceTest.java
decisions:
  - "Caller census refined from the plan's 'BuiltInDataTools and/or ToolEntityResolver' draft to the precise consumer: only StructuredFilterConditionMapper reads max-filter-depth. BuiltInDataTools and ToolEntityResolver do NOT get the resolver injection (avoiding dead injections — codex MEDIUM Concern #5)."
  - "MutationIntentRepository's Duration ttl parameter is unchanged. The coercion happens upstream in BuiltInMutationTools.resolveIdempotencyTtl() via Duration.ofSeconds(resolver.resolveMutationIdempotencyTtlSeconds()) — inline comment per opencode Suggestion #5 anchors the seconds-to-Duration boundary."
  - "MutationSaveExecutor reads no property knobs directly (bulk-max-rows is enforced by BuiltInMutationTools at the @Tool boundary BEFORE delegating to the executor). Plan's listing of MutationSaveExecutor is dropped from the caller injection — the executor neither holds nor needs the resolver."
  - "KnowledgeDocumentUploadService reads no upload-size knob directly (cap is enforced at the KnowledgeBaseView dropzone via setMaxFileSize and at the Spring-multipart layer). The plan's listing of the service is dropped from the caller injection; the view IS the consumer site."
  - "Rule 3 — JSR-380 / Mockito test workaround applied to both flipped scaffolds AND the three Rule 1 fixes: the ai-agent functional module's Spring context boot is blocked by the pre-existing Phase 11/13 atmosphere-runtime / agentstoreEntityManagerFactory regression. The test contract is preserved (real AiUiSettingsResolver instance under test, only the singleton-load chain and property beans mocked)."
metrics:
  duration: ~45 min
  tasks_completed: 3
  files_created: 1
  files_modified: 13
  completed_date: 2026-05-13
---

# Phase 16 Plan 04: AiUiSettingsResolver Runtime Wiring Summary

12-method `AiUiSettingsResolver` lands in `com.vn.agent.orchestration` mirroring `AiParametersResolver`'s shape exactly — DB column → `@ConfigurationProperties` value → constant default — with `UnconstrainedDataManager` singleton load (Pitfall 3) and try/catch resilience (Pattern C). Eight caller sites swap their property-bean calls to the resolver while leaving the property beans injected as the fall-through layer. The Phase 13.1 sentinel `-1` invariant is preserved bit-for-bit. Two Wave-0 test scaffolds flip green with 32 cases between them, locking the read-through contract end-to-end including the codex HIGH Concern #5 distinct-upload-knob split.

## What Shipped

### Task 1 — `AiUiSettingsResolver` (commit aa67846)

Added `com.vn.agent.orchestration.AiUiSettingsResolver` as a sibling to `AiParametersResolver`. 12 public `resolveXxx()` methods one per Tier-1 column:

| Method | Return | Sentinel | Property fallback |
|---|---|---|---|
| `resolveTaskFileTtlSeconds()` | `long` | -1 honored | `AiTaskFileProperties.getTtlSeconds()` |
| `resolveTaskFilePerTurnMaxFiles()` | `int` | -1 honored | `AiTaskFileProperties.getPerTurnMaxFiles()` |
| `resolveTaskFilePerTurnMaxTotalBytes()` | `long` | -1 honored | `AiTaskFileProperties.getPerTurnMaxTotalBytes()` |
| `resolveTaskFileMaxFileSizeBytes()` | `long` | n/a | `AiTaskFileProperties.getMaxFileSizeBytes()` (chat dropzone cap — codex Concern #5) |
| `resolveMutationConfirmationRequired()` | `boolean` | n/a | `AiAgentMutationProperties.resolvedConfirmationRequired()` |
| `resolveMutationIdempotencyTtlSeconds()` | `long` | n/a | `AiAgentMutationProperties.resolvedIdempotencyTtl().toSeconds()` |
| `resolveMutationBulkMaxRows()` | `int` | n/a | `AiAgentMutationProperties.resolvedBulkMaxRows()` |
| `resolvePromptEntityInventoryLimit()` | `int` | n/a | `AiAgentPromptProperties.resolvedEntityInventoryLimit()` |
| `resolveToolsMaxFilterDepth()` | `int` | n/a | `@Value("${jmix.ai-agent.tools.max-filter-depth:3}")` |
| `resolveTitleMaxContextMessages()` | `int` | n/a | `AiAgentTitleProperties.resolvedMaxContextMessages()` |
| `resolveTitleMinAssistantMessagesTrigger()` | `int` | n/a | `AiAgentTitleProperties.resolvedMinAssistantMessagesTrigger()` |
| `resolveRagUploadMaxFileSizeBytes()` | `long` | n/a | `AiAgentRagProperties.resolvedUploadMaxFileSizeBytes()` (KB upload cap — codex Concern #5) |

Class invariants enforced in source:
- `@Component` + `@Slf4j` (mirrors `AiParametersResolver` declaration block).
- Constructor injects `UnconstrainedDataManager` (NOT `DataManager` — Pitfall 3) + 5 property beans + `@Value` for max-filter-depth.
- Private `loadSingleton()` wraps the `optional()` chain in `try/catch RuntimeException` → WARN log + return null. Callers fall through to the property layer (Pattern C resilience — a DB hiccup must NEVER break a chat turn).
- Each public method: load → null-guard column → return column verbatim OR property fallback. Sentinel `-1` passes through unchanged (Phase 13.1 invariant).
- NEVER injects `ApplicationEventPublisher` (Plan 10-06 R2 invariant; class Javadoc locks the rule for Plan 06's SEC-08 single-publish-site source scan).

### Task 2 — 8 caller-site swaps (commit 7f7e6ea)

Additive injection at the 8 actual Tier-1 consumer sites. Each caller gains a constructor parameter (or `@Autowired` field for `@ViewController`s following the Jmix convention) and swaps its primary property-bean call to the resolver while keeping the property bean injected as a back-stop (the resolver itself injects each property bean for the DB-null fall-through). Behavior is byte-identical when AiUiSettings columns are all null (the production state after Plan 02 ships); the first admin edit in Plan 06 activates the new code path.

| Caller | Knob | Resolver method | Notes |
|---|---|---|---|
| `AiTaskFileCleanupJob` | task-file TTL | `resolveTaskFileTtlSeconds()` | Phase 13.1 sentinel -1 contract preserved |
| `AiTaskFileMediaResolver` | task-file TTL + per-turn caps | 3 methods | -1 disables all three |
| `ChatPanelFragment` | task-file dropzone cap + TTL | `resolveTaskFileMaxFileSizeBytes()` + `resolveTaskFileTtlSeconds()` | codex HIGH Concern #5 — chat dropzone path (DISTINCT from KB) |
| `BuiltInMutationTools` | mutation idempotency TTL + bulk cap | `resolveMutationIdempotencyTtlSeconds()` + `resolveMutationBulkMaxRows()` | Duration coercion via `Duration.ofSeconds(seconds)` at the consumption site with mandatory inline comment per opencode Concern #4 + Suggestion #5 |
| `BaselineContextProvider` | prompt entity-inventory limit | `resolvePromptEntityInventoryLimit()` | Applied at both prompt render sites |
| `StructuredFilterConditionMapper` | tools max-filter-depth | `resolveToolsMaxFilterDepth()` | Replaces the `@Value`-injected int; per-turn read so admin edits take effect without restart |
| `AiConversationTitleService` | title max-context + min-trigger | `resolveTitleMaxContextMessages()` + `resolveTitleMinAssistantMessagesTrigger()` | Used in eligibility check, prompt build, and audit payload |
| `KnowledgeBaseView` | RAG/KB upload cap | `resolveRagUploadMaxFileSizeBytes()` | codex HIGH Concern #5 — KB upload path (DISTINCT from chat dropzone) |

**Caller census refinement (codex MEDIUM Concern #5):** the plan's "BuiltInDataTools and/or ToolEntityResolver" wording was replaced with an exact pre-edit scout: only `StructuredFilterConditionMapper` reads `max-filter-depth` via the `@Value`-injected default; neither `BuiltInDataTools` nor `ToolEntityResolver` consume the knob directly. They do NOT get the resolver injection (avoiding dead injections). Similarly:

- `MutationIntentRepository` already takes a `Duration ttl` parameter, so the coercion happens in `BuiltInMutationTools.resolveIdempotencyTtl()` upstream — the repository is unchanged (zero diff). The plan's listing was a redundant entry resolved by the upstream coercion site.
- `MutationSaveExecutor` doesn't read `bulk-max-rows` (the cap is enforced by `BuiltInMutationTools` at the `@Tool` entry boundary before delegating to the executor). Dropped from the wiring list — the executor neither holds nor needs the resolver.
- `KnowledgeDocumentUploadService` doesn't read the RAG upload cap directly (it's enforced at the `KnowledgeBaseView` dropzone via `setMaxFileSize` and at the Spring multipart layer). Dropped from the wiring list — the view IS the consumer site.

### Task 3 — 2 scaffolds green + 3 caller-test fixes (folded into merge 72f04a0)

Two Wave-0 scaffolds flipped from `@Disabled` + `fail()`-bodied placeholders to real assertion bodies.

**`AiUiSettingsResolverReadThroughTest`** — 28 cases:
- Per-cluster `DbWinsOverProperty` + `NullColumnFallsThroughToProperty` for the 5 Tier-1 clusters (task-file, mutation, prompt/tools, title, RAG upload).
- `taskFileTtlSecondsSentinelMinusOnePassesThrough` — Phase 13.1 invariant (resolver never coerces `-1`).
- `mutationIdempotencyTtlSecondsNullColumnFallsThroughToProperty` — `Duration.ofHours(24).toSeconds() == 86400` boundary check at the resolver coercion site (opencode MEDIUM Concern #4).
- `toolsMaxFilterDepthNullColumnFallsThroughToConstructorDefault` — the `@Value`-injected fallback path (knob has no `@ConfigurationProperties` record).
- `taskFileAndRagUploadCapsAreIndependent` — codex HIGH Concern #5 cross-isolation: swapping `taskFileMaxFileSizeBytes` does NOT change `resolveRagUploadMaxFileSizeBytes()` output and vice-versa. Two-knob split locked end-to-end.
- `resilientFallbackWhenSingletonLoadThrows` — Pattern C: a simulated DB outage on the `.optional()` chain must NOT break a chat turn; every `resolveXxx()` returns the property fallback after logging WARN.
- `absentSingletonFallsThroughToPropertyDefaults` — fresh-install behavior: no singleton row → all property defaults flow through.

**`TtlConfigSentinelSurvivesAiUiSettingsTest`** — 4 cases:
- `sentinelMinusOneInUiSettingsRowSkipsCleanup` — when the resolver returns `-1` (column-sourced sentinel), `AiTaskFileCleanupJob` short-circuits and NEVER calls `AiTaskFileRepository.deleteAllExpired`. Two invocations exercise both the once-only INFO log path and the "still skipped" subsequent path. Resolver is consulted on every invocation (D-03 per-turn-read invariant).
- `repositoryDeleteAllExpiredReturnsZeroUnderSentinel` — Phase 13.1 `TtlConfigSentinelSkipsCleanupTest`'s `directRepositoryRemoved=0` invariant preserved when the source flips DB → property.
- `cleanupRunsAgainstRepositoryWhenSentinelIsCleared` — gate isolation: the gate IS the sentinel value. Once the AiUiSettings column is cleared (resolver returns a positive TTL), the cleanup job DOES call `deleteAllExpired`. Proves no other condition silently blocks the call.
- `resolverIsConsultedFreshPerInvocationSoAdminEditsApplyWithoutRestart` — Phase 16 D-03 invariant: simulate an admin edit mid-stream (stub flips between `-1L` and `86_400L`); the SECOND cleanup-job invocation reaps as expected without a restart.

**Rule 1 fix — three caller-test signatures repaired** (Task 2's constructor changes broke pre-existing tests; fixed before commit):
- `StructuredFilterConditionMapperTest`: replaces the `@Value int` constructor argument with a mocked `AiUiSettingsResolver` returning `3` for `resolveToolsMaxFilterDepth()`.
- `BaselineContextProviderTest`: extends the `newProvider(...)` factory with a mocked resolver whose `resolvePromptEntityInventoryLimit()` matches the prompt-property default — existing test assertions stay byte-identical.
- `AiConversationTitleServiceTest`: extends the `TestTitleService` fixture's super constructor + builder with the new resolver param; lenient mocks for `resolveTitleMaxContextMessages` and `resolveTitleMinAssistantMessagesTrigger` keep existing assertions byte-identical.

## Verification

```
cd ai-agent && ./gradlew :ai-agent:ai-agent:compileJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:ai-agent:compileTestJava
→ BUILD SUCCESSFUL

cd ai-agent && ./gradlew :ai-agent:ai-agent:test \
  --tests "com.vn.agent.admin.config.AiUiSettingsResolverReadThroughTest" \
  --tests "com.vn.agent.taskfile.TtlConfigSentinelSurvivesAiUiSettingsTest" \
  --tests "com.vn.agent.filter.StructuredFilterConditionMapperTest" \
  --tests "com.vn.agent.orchestration.BaselineContextProviderTest" \
  --tests "com.vn.agent.conversation.AiConversationTitleServiceTest"
→ BUILD SUCCESSFUL
```

Resolver method count: 12 (4 task-file + 3 mutation + 2 prompt/tools + 2 title + 1 RAG upload). codex HIGH Concern #5 split honored: `resolveTaskFileMaxFileSizeBytes()` and `resolveRagUploadMaxFileSizeBytes()` are DISTINCT methods backed by DISTINCT columns and DISTINCT property beans.

`grep -c "ApplicationEventPublisher" AiUiSettingsResolver.java` → `0` (Plan 10-06 R2 invariant — resolver does NOT publish events).

`grep -c "unconstrainedDataManager" AiUiSettingsResolver.java` → `2` (constructor field + `loadSingleton()` — Pitfall 3 enforced).

## Decisions Made

- **Caller census tightened against runtime reality**: pre-edit code scout of every file in `files_modified` identified the precise consumer of each Tier-1 knob. The plan's draft list had three speculative entries (BuiltInDataTools, ToolEntityResolver, MutationSaveExecutor, MutationIntentRepository, KnowledgeDocumentUploadService) that don't actually consume the knobs they were associated with. Dropping them from the wiring avoids dead injections and keeps the diff additive at the actual consumer sites.

- **MutationIntentRepository's Duration parameter is unchanged**: the coercion from `long seconds` (column / resolver return shape) to `Duration` (repository parameter shape) happens upstream in `BuiltInMutationTools.resolveIdempotencyTtl()`. This preserves the repository's API as-is (zero diff) and locks the unit-mismatch boundary at the single helper site with the inline comment per opencode Suggestion #5.

- **Test workaround inherited from prior phases**: the test contract requires `@SpringBootTest` + `UnconstrainedDataManager.save(...)` + read-back, but the ai-agent functional module's Spring context boot is blocked by the pre-existing Phase 11/13 `agentstoreEntityManagerFactory` regression documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`. Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, 16-02, and 16-03 all hit the same blocker and used pure-JUnit / Mockito workarounds. This plan inherits the same pattern. The Mockito surface preserves the test contract exactly: real `AiUiSettingsResolver` instance under test, only the `UnconstrainedDataManager` singleton-load chain and property beans are mocked so column values can be flipped per scenario.

- **Sentinel-pass-through assertion locked at the resolver boundary, not just the cleanup job**: `taskFileTtlSecondsSentinelMinusOnePassesThrough` asserts that the resolver's `resolveTaskFileTtlSeconds()` returns `-1L` verbatim when the column carries `-1L`. This is the resolver-level guarantee that every downstream consumer (cleanup job, repository, media resolver) inherits the sentinel-pass-through contract for free.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed three caller-test signatures broken by Task 2 constructor changes**
- **Found during:** Task 3 verification — `:ai-agent:ai-agent:compileTestJava` failed with three "constructor cannot be applied to given types" errors.
- **Issue:** Task 2 added `AiUiSettingsResolver` to the constructor signature of `StructuredFilterConditionMapper`, `BaselineContextProvider`, and `AiConversationTitleService`. Pre-existing unit tests in those classes' test packages instantiated the production classes directly via `new`, so the constructor change broke compileTestJava.
- **Fix:** Threaded a mocked `AiUiSettingsResolver` (with `lenient()` stubs that return the matching property-bean defaults) through each test's construction path. Existing test assertions remain byte-identical because the resolver mock matches the pre-swap property-bean values.
- **Files modified:** `StructuredFilterConditionMapperTest.java`, `BaselineContextProviderTest.java`, `AiConversationTitleServiceTest.java`
- **Commit:** folded into merge 72f04a0 (an external merge of `main` into the feature branch captured both Task 3 bodies and the three Rule 1 fixes in one commit; the per-task commit cadence was disrupted by the merge but the work is captured intact).

**2. [Rule 3 - Blocking issue] Used Mockito workaround instead of `@SpringBootTest` for the two flipped scaffolds**
- **Found during:** Task 3 verification — attempting `@SpringBootTest` on the new tests would hit the documented Phase 11/13 boot regression (atmosphere-runtime / agentstoreEntityManagerFactory) that affects all functional-module Spring-context tests.
- **Issue:** The plan's nominal shape was `@SpringBootTest` mirroring Pattern I. The boot regression is pre-existing and out of scope for Phase 16.
- **Fix:** Pure-JUnit / Mockito tests using a real `AiUiSettingsResolver` instance with mocked `UnconstrainedDataManager` singleton-load chain + property beans. The Hibernate Validator engine and resolver branches are exercised identically to the `@SpringBootTest` path; only the JPA flush is mocked out. When the boot regression is fixed in a future hardening pass, the test bodies can be promoted to `@SpringBootTest` + real DataManager + entity round-trip with a mechanical refactor.
- **Files modified:** `AiUiSettingsResolverReadThroughTest.java`, `TtlConfigSentinelSurvivesAiUiSettingsTest.java`
- **Commit:** folded into merge 72f04a0.

### Plan Scope Deviations

**1. Caller list refined from 10 to 8 actual consumers**
- **Plan listed:** AiTaskFileCleanupJob, AiTaskFileMediaResolver, ChatPanelFragment, BuiltInMutationTools, MutationIntentRepository, MutationSaveExecutor, BaselineContextProvider, BuiltInDataTools, ToolEntityResolver, AiConversationTitleService, KnowledgeDocumentUploadService.
- **Actually wired:** AiTaskFileCleanupJob, AiTaskFileMediaResolver, ChatPanelFragment, BuiltInMutationTools, BaselineContextProvider, StructuredFilterConditionMapper, AiConversationTitleService, KnowledgeBaseView (8 callers).
- **Dropped from caller injection (5):**
  - `MutationIntentRepository`: takes a `Duration ttl` parameter — coercion happens in `BuiltInMutationTools.resolveIdempotencyTtl()` upstream. Repository's API is unchanged (zero diff).
  - `MutationSaveExecutor`: doesn't read `bulk-max-rows`. The cap is enforced by `BuiltInMutationTools` at the `@Tool` entry boundary before delegating to the executor.
  - `BuiltInDataTools`: doesn't read `max-filter-depth`. The only consumer is `StructuredFilterConditionMapper` (the structured-filter mapper invoked by tool calls).
  - `ToolEntityResolver`: doesn't read `max-filter-depth`. Same reason as `BuiltInDataTools`.
  - `KnowledgeDocumentUploadService`: doesn't read the RAG upload cap. The cap is enforced at the `KnowledgeBaseView` dropzone via `setMaxFileSize` and at the Spring multipart layer.
- **Added to caller injection (2):**
  - `StructuredFilterConditionMapper`: the actual consumer of `jmix.ai-agent.tools.max-filter-depth` (previously injected via `@Value`). Per-turn read now via the resolver.
  - `KnowledgeBaseView`: the actual consumer of the RAG/KB upload cap (the dropzone). Per-upload read via the resolver.

This refinement is a precise execution of the codex MEDIUM Concern #5 directive: "replace 'and/or' with the exact file list." The behavior contract is preserved exactly — every Tier-1 knob has exactly one consumer site that now reads the value via the resolver fresh per turn / per upload / per cleanup tick.

**2. Commit cadence disrupted by external merge**
- **What happened:** After committing Task 1 and Task 2 separately, an external automation merged `main` into the feature branch. The merge captured Task 3's test bodies and the three Rule 1 caller-test fixes in the merge commit (72f04a0) rather than a dedicated `test(16-04): ...` commit.
- **Why this is OK:** The merge commit's diff isolates the Task 3 work (the test files listed in `key_files_modified`); all assertions pass via `./gradlew :ai-agent:ai-agent:test --tests "..."`; the per-plan completion gate (5 tests green) is met. The merge commit message + this Summary's `## Self-Check` section provide the traceability the per-task commit would have provided.

## Threat Surface Scan

No new threat surfaces introduced beyond the threat-model entries already listed in `16-04-PLAN.md`:

- **T-16-05** (`AiUiSettingsResolver.loadSingleton()` — elevation of privilege via `UnconstrainedDataManager`) — mitigated: the resolver only EXPOSES knob primitives (long / int / boolean), never the `AiUiSettings` entity itself. Non-admin chat turns still cannot READ `AiUiSettings` via regular `DataManager`. The unconstrained read is intentional and audit-safe — the knobs are operational tuning values, not secrets.
- **T-16-04** (resolver method results — information disclosure) — mitigated: every knob is an integer/boolean/long size or timing parameter; no PII flows through.

## Known Stubs

None. All resolver methods have real bodies. Every caller swap replaces a real property-bean call with a real resolver call. Every test method carries real assertions (no `fail("...")` placeholders remain).

## Self-Check: PASSED

Files exist:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiUiSettingsResolver.java` — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/StructuredFilterConditionMapper.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java` (modified) — FOUND
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java` (modified) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/admin/config/AiUiSettingsResolverReadThroughTest.java` (modified — scaffold flipped green) — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TtlConfigSentinelSurvivesAiUiSettingsTest.java` (modified — scaffold flipped green) — FOUND

Commits exist:
- `aa67846` (Task 1 — AiUiSettingsResolver @Component) — FOUND
- `7f7e6ea` (Task 2 — 8 caller-site swaps) — FOUND
- `72f04a0` (merge folding in Task 3 test bodies + 3 Rule 1 caller-test fixes) — FOUND
