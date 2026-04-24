---
phase: 05-rag-layer
plan: 03
subsystem: rag-ingestion-pipeline
tags: [rag, ingestion, async, mdc, executor, status-writer, cancellation, atomicity, threat-mitigation]
requires:
  - Plan 05-01 (EmbeddingModel + VectorStore beans, AiAgentRagProperties, AiAgentEmbeddingProperties)
  - Plan 05-02 (ChunkMetadata constants)
  - Phase 4 AuditWriter pattern (REQUIRES_NEW status-writer shape)
provides:
  - IngestionStatusWriter (@Transactional REQUIRES_NEW) — markPending / markProcessing / markReady / markFailed / markCancelled
  - CancellationRegistry (in-memory, ConcurrentHashMap.newKeySet)
  - AsyncIngestionWorker (@Async aiAgentIngestExecutor) — Tika → TokenTextSplitter → metadata enrichment → VectorStore.add → REQUIRES_NEW status transitions
  - MdcPropagatingTaskDecorator — correlation id survival across request → worker handoff
  - aiAgentIngestExecutor ThreadPoolTaskExecutor (@Bean @ConditionalOnMissingBean(name=…)) in AIConfiguration
  - @EnableAsync activation (class-level on AIConfiguration)
  - RAG ingestion defaults in module.properties (splitter/executor/sample-ingester) + spring.ai.retry.* defaults
  - AiKnowledgeDocumentStatus.CANCELLED (new enum value) with en/vi messages
affects:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
patterns:
  - "REQUIRES_NEW writer bean — mirrors Phase 4 AuditWriter for independent status commits"
  - "@Async on named executor + @Transactional on collaborator bean (never on the same method, Pitfall #7)"
  - "TaskDecorator for MDC propagation across async handoffs"
  - "Cooperative cancellation via polled in-memory flag (D-20)"
  - "RAG-03 atomic failure: VectorStore.delete(FilterExpression) before markFailed"
  - "Option A flattened role flags — lowercased via Locale.ROOT (threat T-05-03-08)"
key-files-created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/MdcPropagatingTaskDecorator.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionStatusWriterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/CancellationRegistryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-alpha.md
key-files-modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "IngestionStatusWriter uses method-level REQUIRES_NEW (five methods), mirroring Phase 4 AuditWriter. All methods accept UUID and reload via DataManager to pick up interleaved changes and avoid stale-entity issues across the REQUIRES_NEW boundary."
  - "markReady accepts chunkCount for Plan 05-04 API compat but does not persist it — the Phase 2 entity has no chunkCount column and adding one would require a Liquibase changeset (out of scope). Value is logged for observability."
  - "errorMessage truncation is 1024 chars (entity VARCHAR(1024)), not the plan's nominal 2000 — column width is the source of truth."
  - "AiKnowledgeDocumentStatus.CANCELLED added to support D-20 delete handshake; fits the existing VARCHAR(16) column and is accompanied by en/vi messages. No Liquibase changeset required — status column is a free-form varchar with defaultValue='PENDING'."
  - "aiAgentIngestExecutor uses java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy for back-pressure (D-03 / T-05-03-03). setTaskDecorator(new MdcPropagatingTaskDecorator()) wires correlation-id survival. waitForTasksToCompleteOnShutdown=true with 30s awaitTermination for clean shutdown."
  - "@EnableAsync declared on AIConfiguration (single source). The starter (AIAutoConfiguration) was checked and does not carry @EnableAsync — no duplicate wiring."
  - "RAG retry is the built-in spring.ai.retry.* surface (D-16 override). No spring-retry dep added, no @Retryable — SpringAiRetryAutoConfiguration wraps provider calls including EmbeddingModel transparently."
  - "AsyncIngestionWorker resolves source via Spring ResourceLoader: fileName with classpath:/file: prefix used as-is, bare names resolved under classpath:ai-kb/, other schemes (http://, ftp://, etc.) rejected with IllegalArgumentException that becomes FAILED (threat T-05-03-05). This is a transitional shape — Plan 05-04 upload service will formalise it."
  - "AsyncIngestionWorkerTest invokes ingest() synchronously on the test thread (no @EnableAsync, no Spring context) so assertions observe a completed pipeline — plan explicitly allows this; Plan 05-05 covers async-execution via SyncTaskExecutor."
  - "spring.ai.retry.backoff.multiplier=2 (integer) — Spring AI 1.1.4 SpringAiRetryProperties.Backoff.multiplier is declared int, so 2.0 fails binding and collapses every @SpringBootTest context."
metrics:
  duration_minutes: 35
  completed_date: 2026-04-20
  tasks_completed: 3
  files_created: 8
  files_modified: 5
  commits: 3
requirements-completed: [RAG-01, RAG-03]
---

# Phase 5 Plan 03: Async ingestion pipeline — status writer, cancellation, worker, executor

Landed the async KB ingestion path wiring RAG-01 (upload → READY pipeline running off the request thread) and RAG-03 (atomic failure — no partial vectors on a failed ingest). A REQUIRES_NEW `IngestionStatusWriter` commits status transitions independently of the worker, `AsyncIngestionWorker` runs on a named `aiAgentIngestExecutor` with MDC propagated, and the `VectorStore` cleanup-on-failure path guarantees RAG-03 atomicity using a portable Spring AI `Filter.Expression`.

## Commits

| Task | Commit    | Description                                                                         |
| ---- | --------- | ----------------------------------------------------------------------------------- |
| 1    | `f316f77` | feat(05-03): add IngestionStatusWriter + CancellationRegistry                       |
| 2    | `b75b443` | feat(05-03): aiAgentIngestExecutor bean + MDC propagation + Spring AI retry         |
| 3    | `97d60f3` | feat(05-03): AsyncIngestionWorker — Tika to splitter to metadata-enriched VectorStore.add |

Branch: `gsd/phase-05-rag-layer`.

## Must-Haves

- [x] **IngestionStatusWriter commits READY/FAILED in REQUIRES_NEW transactions** — every method annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)`; verified by 6 IngestionStatusWriterTest cases (pending reset, processing, ready + ingestedAt, failed + truncation, cancelled, missing-doc tolerance).
- [x] **AsyncIngestionWorker runs off the request thread via aiAgentIngestExecutor with MDC propagated** — `@Async("aiAgentIngestExecutor")` on `ingest(UUID)`; `MdcPropagatingTaskDecorator` wired into the executor in `AIConfiguration.aiAgentIngestExecutor(...)`.
- [x] **Every chunk carries documentId, source, embeddingModel, allowedRoles, and flattened role_<code>=true keys** — `AsyncIngestionWorker.enrich(...)` puts all five key groups on every chunk's metadata; `happy_path_enriches_every_chunk_with_all_five_metadata_keys_and_marks_ready` asserts via `containsEntry` / `containsKey`.
- [x] **CancellationRegistry.cancel flips a flag the worker polls between chunks** — entry-guard polled before any VectorStore.add; per-batch guard polled before every batch; `cancellation_between_batches_marks_cancelled_and_skips_further_add` asserts no `vectorStore.add(…)` occurs after cancellation and status flips to CANCELLED.
- [x] **Embedding retry is driven by spring.ai.retry.* properties — no spring-retry dep, no @Retryable** — grep of `ai-agent/ai-agent/src/main/java/com/vn/agent/rag` for `@Retryable` / `org.springframework.retry` returns zero; `module.properties` sets `spring.ai.retry.max-attempts=5`, backoff, and `on-client-errors=false`.

## Bean Registration

Appended to `AIConfiguration`:

```java
@Bean(name = "aiAgentIngestExecutor")
@ConditionalOnMissingBean(name = "aiAgentIngestExecutor")
public ThreadPoolTaskExecutor aiAgentIngestExecutor(AiAgentRagProperties props) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    var cfg = props.ingestExecutor();
    executor.setCorePoolSize(resolveInt(cfg == null ? null : cfg.corePoolSize(), 2));
    executor.setMaxPoolSize(resolveInt(cfg == null ? null : cfg.maxPoolSize(), 4));
    executor.setQueueCapacity(resolveInt(cfg == null ? null : cfg.queueCapacity(), 64));
    executor.setKeepAliveSeconds(resolveInt(cfg == null ? null : cfg.keepAliveSeconds(), 60));
    executor.setThreadNamePrefix("ai-ingest-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
}
```

`@ConditionalOnMissingBean(name = "aiAgentIngestExecutor")` is the host override seam — supplying a `@Bean("aiAgentIngestExecutor")` of any compatible type suppresses the default.

`@EnableAsync` was added at the class level of `AIConfiguration` after confirming the starter (`AIAutoConfiguration`) does not already declare it — a duplicate would be a no-op but violates the single-source principle.

## Metadata Contract Emitted

For a chunk belonging to document `d` with `allowedRoles = ["ai-agent-user", "Editor"]` and embedding model `openai/text-embedding-3-small`:

```
{
  "source":          "<d.fileName>",                             // ChunkMetadata.SOURCE
  "documentId":      "<d.id>",                                   // ChunkMetadata.DOCUMENT_ID — used by D-21 delete
  "embeddingModel":  "openai/text-embedding-3-small",            // ChunkMetadata.EMBEDDING_MODEL
  "allowedRoles":    ["ai-agent-user", "Editor"],                // ChunkMetadata.ALLOWED_ROLES (audit/debug)
  "role_ai-agent-user": true,                                    // ChunkMetadata.ROLE_FLAG_PREFIX + lowercase(role)
  "role_editor":       true
}
```

Plan 05-04's `CustomIngester` SPI (and the upload service) MUST emit the same key set unchanged — the `AsyncIngestionWorker.enrich(...)` helper is the single producer for uploads; Plan 05-04 will either reuse this worker for SPI-sourced Documents or duplicate the enrichment contract verbatim.

## Cancellation Protocol (for Plan 05-04)

`KnowledgeDocumentService.delete(UUID documentId)` (Plan 05-04) MUST:

1. `cancellationRegistry.cancel(documentId)` — flips the in-memory flag.
2. In the same `@Transactional(REQUIRED)` boundary:
   - `vectorStore.delete(FilterExpressionBuilder.eq(ChunkMetadata.DOCUMENT_ID, documentId.toString()).build())`.
   - `dataManager.remove(doc)`.

The worker polls `isCancelled(documentId)` at entry AND before every batch of up to 32 chunks. If flipped, the worker transitions to CANCELLED via the writer and returns without further `VectorStore.add` calls. Already-written chunks for that id are caught by the service's `vectorStore.delete(...)` above, so atomicity is preserved even if the worker managed to add one batch before seeing the flag.

The worker always calls `cancellationRegistry.clear(documentId)` on any terminal path (READY, FAILED, CANCELLED) to avoid flag leaks across ingestion generations (D-15 reingest reset relies on this).

## Advisor Order (unchanged from Plan 05-02)

This plan adds no advisor. The retrieval advisor order remains:

| # | Advisor                         | Order                       | Source  |
|---|---------------------------------|-----------------------------|---------|
| 1 | `AuditAdvisor`                  | `HIGHEST_PRECEDENCE`        | Phase 4 |
| 2 | `MessageChatMemoryAdvisor`      | `HIGHEST_PRECEDENCE + 200`  | Phase 4 |
| 3 | `RetrievalAugmentationAdvisor`  | `HIGHEST_PRECEDENCE + 250`  | Plan 05-02 |
| 4 | `ToolCallAdvisor`               | `HIGHEST_PRECEDENCE + 300`  | Phase 4 |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] Added `AiKnowledgeDocumentStatus.CANCELLED`**

- **Found during:** Task 1 design — plan behavior demanded a `markCancelled` terminal state but the Phase 2 enum only had PENDING/PROCESSING/READY/FAILED.
- **Issue:** Without a CANCELLED value, the D-20 delete handshake would have to overload either FAILED (losing distinction between user-initiated cancel and real failure) or leave PROCESSING rows dangling. Both break the admin-UX contract from CONTEXT.md §D-20.
- **Fix:** Added `CANCELLED("CANCELLED")` to `AiKnowledgeDocumentStatus` (fits the VARCHAR(16) column with room to spare — the `defaultValue="PENDING"` Liquibase changeset is not affected). Added `en=Cancelled` / `vi=Đã huỷ` message entries in both locale files per CLAUDE.md.
- **Files modified:** `AiKnowledgeDocumentStatus.java`, `messages.properties`, `messages_vi.properties`.
- **Commit:** Folded into `f316f77` (Task 1).
- **Root cause (planner heuristic):** CONTEXT.md §Specifics on the canonical enum noted "Phase 5 may add CANCELLED if D-20 chooses status-polled cancellation — planner picks". The plan picked in-memory-registry cancellation but still needed a CANCELLED terminal status to distinguish the state from READY/FAILED.

**2. [Rule 1 - Bug] `spring.ai.retry.backoff.multiplier` must be integer 2, not 2.0**

- **Found during:** Task 2 verification — full `:ai-agent:ai-agent:test` collapsed with 28 failures after `module.properties` added `spring.ai.retry.backoff.multiplier=2.0`.
- **Issue:** `org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties.Backoff.multiplier` is declared `int` (not `double`) in spring-ai-autoconfigure-retry 1.1.4. The `2.0` value triggered `NumberFormatException` during `@ConfigurationProperties` binding, which cascaded through `SpringAiRetryAutoConfiguration.retryTemplate(...)` and failed context load for every `@SpringBootTest` on the classpath.
- **Fix:** Changed the value to integer `2` with an explanatory comment in `module.properties`. All 123 tests on the `:ai-agent:ai-agent:test` run now pass.
- **Files modified:** `module.properties`.
- **Commit:** Folded into `b75b443` (Task 2) — the fix was applied before that commit landed, so no intermediate broken commit exists in git history.
- **Root cause (planner heuristic):** Plan Task 2 hard-coded `multiplier=2.0` from memory. The actual Spring AI 1.1.4 API treats the multiplier as an int — a planner review against bytecode (or Context7) would catch this. Added to the planner feedback file implicitly via this deviation note.

**3. [Rule 2 - Missing functionality] `application.properties` moved to `module.properties`**

- **Found during:** Task 2 — the plan-specified path `ai-agent/ai-agent-starter/src/main/resources/com/vn/autoconfigure/agent/application.properties` does not exist in the starter module and has no Spring Boot bootstrap surface hooked up (the starter only contains `AutoConfiguration.imports`).
- **Issue:** Writing a new `application.properties` at the plan-specified path would leave it unloaded — Spring Boot does not pick up arbitrary classpath properties files by path alone; a host `application.yml/properties` at the root classpath is the only automatic load site.
- **Fix:** Put the RAG defaults in `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`, the existing add-on properties file that `AIConfiguration` already loads via `@PropertySource(...)`. That's the same surface the pre-existing `jmix.ai-agent.tools.max-filter-depth` default uses — consistent with the add-on's established convention.
- **Files modified:** `module.properties`.
- **Commit:** `b75b443` (Task 2).
- **Impact on callers:** Host `application.properties` (in `jmix-app` or any consumer) still overrides module.properties via Spring's standard property-source ordering — the override semantics expected by the plan are preserved.

**4. [Rule 3 - Blocking] Entity shape drift: `sourceUri`, `chunkCount`, `IngestStatus`, `allowedRoles:Set<String>` all absent on `AiKnowledgeDocument`**

- **Found during:** Task 1 design — the plan's interfaces block assumed fields/types that do not match the Phase 2 entity (`AiKnowledgeDocument` has `fileName` not `sourceUri`, no `chunkCount`, `allowedRolesJson:String` not `allowedRoles:Set<String>`, and the enum is `AiKnowledgeDocumentStatus` not `IngestStatus`).
- **Issue:** Adding fields/columns to the entity is architectural (Rule 4) and requires Liquibase changesets outside this plan's scope. Blocking on the mismatch would stall the entire wave.
- **Fix:** Adapted every code site to the existing entity shape:
  - Imported `AiKnowledgeDocumentStatus` throughout (not `IngestStatus`).
  - `markReady(id, chunkCount)` accepts the param but does not persist it — logs at DEBUG for observability. Plan 05-04 is free to add a `CHUNK_COUNT` column via changelog if the admin UI needs the count.
  - `AsyncIngestionWorker` reads `document.getFileName()` as the source hint (with the resolution rules documented in the class Javadoc: `classpath:` / `file:` / fallback `classpath:ai-kb/` / reject everything else with `IllegalArgumentException`).
  - `AsyncIngestionWorker.parseAllowedRoles(String json)` parses the JSON string via Jackson into `List<String>` before enrichment. Empty/null JSON → empty list → zero `role_*` flags (honours the fail-closed posture upstream in `RetrievalFilterBuilder`).
- **Files modified:** All three source files in this plan.
- **Commit:** Folded into the three per-task commits (`f316f77`, `b75b443`, `97d60f3`). All commits compile and test green against the actual Phase 2 schema.
- **Root cause (planner heuristic):** Plan 05-03 was authored against an entity memory that drifted from the committed Phase 2 DDL. Planner should cross-check `<interfaces>` against the actual entity file (not just CONTEXT.md's recollection) before freezing the plan.

## Context7 / Source Verification Deltas

- **`TokenTextSplitter.Builder`** — verified against `spring-ai-commons-1.1.4.jar` bytecode. Builder has `withChunkSize(int)`, `withMinChunkSizeChars(int)`, `withMinChunkLengthToEmbed(int)`, `withMaxNumChunks(int)`, `withKeepSeparator(boolean)`, `withPunctuationMarks(List<Character>)` — NO `withChunkOverlap(...)`. The `chunk-overlap` property is a semantic alias only (TokenTextSplitter does not produce overlapping windows; RESEARCH Pitfall #5 confirmed). Worker silently ignores the property; the binding stays for future splitter swaps.
- **`TikaDocumentReader(Resource)`** — constructor signature confirmed; returns `List<Document>` via `.get()`.
- **`VectorStore.delete(Filter.Expression)`** — confirmed on the 1.1.4 `VectorStore.class`; RAG-03 atomicity path uses this portable API, not raw JDBC.
- **`Document.mutate().metadata(Map).build()`** — `Document.getMetadata()` returns a `Map<String,Object>` but the splitter-produced documents may hand back immutable views; the worker enriches by copying into a fresh `HashMap` and calling `.mutate().metadata(merged).build()` to guarantee the new chunk carries the merged map.
- **`SpringAiRetryProperties.Backoff.multiplier`** — confirmed `int` (not `double`) via bytecode inspection of `spring-ai-autoconfigure-retry-1.1.4.jar`. See Deviation #2.

## Verification Trace

| Acceptance Check                                                                                              | Command                                                                                                                       | Result                           |
| ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| Writer has REQUIRES_NEW on every transition                                                                   | `grep -c "Propagation.REQUIRES_NEW" .../IngestionStatusWriter.java`                                                           | 5                                |
| No spring-retry / @Retryable in the module                                                                    | `grep -rn "@Retryable\|org.springframework.retry" .../rag/`                                                                   | 0                                |
| CancellationRegistry uses ConcurrentHashMap                                                                   | `grep -n "ConcurrentHashMap" .../CancellationRegistry.java`                                                                   | 1                                |
| IngestionStatusWriterTest passes                                                                              | `./gradlew :ai-agent:ai-agent:test --tests "*IngestionStatusWriterTest"`                                                      | BUILD SUCCESSFUL (6 tests)       |
| CancellationRegistryTest passes                                                                               | `./gradlew :ai-agent:ai-agent:test --tests "*CancellationRegistryTest"`                                                       | BUILD SUCCESSFUL (5 tests)       |
| aiAgentIngestExecutor bean declared                                                                           | `grep -c "aiAgentIngestExecutor" .../AIConfiguration.java`                                                                    | 3                                |
| CallerRunsPolicy wired                                                                                        | `grep -n "CallerRunsPolicy" .../AIConfiguration.java`                                                                         | 1                                |
| setTaskDecorator wired                                                                                        | `grep -n "setTaskDecorator" .../AIConfiguration.java`                                                                         | 1                                |
| @EnableAsync declared exactly once in add-on                                                                  | `grep -rn "@EnableAsync" ai-agent/ai-agent/src/main ai-agent/ai-agent-starter/src/main`                                       | 1 (AIConfiguration)              |
| spring.ai.retry.max-attempts present                                                                          | `grep -n "spring.ai.retry.max-attempts" .../module.properties`                                                                | 1                                |
| jmix.ai-agent.rag.ingest-executor.* keys                                                                      | `grep -n "jmix.ai-agent.rag.ingest-executor" .../module.properties`                                                           | 4                                |
| @Async("aiAgentIngestExecutor") on worker                                                                     | `grep -cn "@Async(\"aiAgentIngestExecutor\")" .../AsyncIngestionWorker.java`                                                  | 1                                |
| @Transactional absent on worker                                                                               | `grep -n "@Transactional" .../AsyncIngestionWorker.java`                                                                      | 0                                |
| ChunkMetadata constants used                                                                                  | `grep -c "ChunkMetadata\." .../AsyncIngestionWorker.java`                                                                     | 6                                |
| Lowercase-role keys                                                                                           | `grep -n "toLowerCase(Locale.ROOT)" .../AsyncIngestionWorker.java`                                                            | 1                                |
| Cleanup delete path                                                                                           | `grep -n "vectorStore.delete" .../AsyncIngestionWorker.java`                                                                  | 1                                |
| All four status transitions used                                                                              | `grep -o "markProcessing\|markReady\|markFailed\|markCancelled" .../AsyncIngestionWorker.java \| sort -u`                     | 4                                |
| AsyncIngestionWorkerTest passes                                                                               | `./gradlew :ai-agent:ai-agent:test --tests "*AsyncIngestionWorkerTest"`                                                       | BUILD SUCCESSFUL (7 tests)       |
| Full `:ai-agent:ai-agent:test`                                                                                | `./gradlew :ai-agent:ai-agent:test`                                                                                           | BUILD SUCCESSFUL (130 tests)     |
| `:ai-agent:ai-agent:compileJava` + `:ai-agent:ai-agent-starter:compileJava`                                   | `./gradlew :ai-agent:ai-agent:compileJava :ai-agent:ai-agent-starter:compileJava`                                             | BUILD SUCCESSFUL                 |

## Test Coverage Inventory

- **IngestionStatusWriterTest** (6 tests, `@SpringBootTest`): markPending reset, markProcessing clears, markReady sets ingestedAt + clears error, markFailed truncates oversize to column width, markFailed with short message unchanged, markCancelled clears error, missing-doc tolerance across all 5 methods.
- **CancellationRegistryTest** (5 tests, plain JUnit 5): idempotent cancel, isCancelled after clear, unknown id returns false, null-id silent-tolerance, 100-way concurrent cancel/isCancelled.
- **AsyncIngestionWorkerTest** (7 tests, Mockito + classpath fixture): happy path with all 5 metadata keys, role-flag lowercasing via Locale.ROOT, empty allowedRoles gives zero role_* flags, VectorStore.add failure triggers cleanup delete + markFailed with class+message, cancellation between batches skips add + marks CANCELLED, missing document logs and returns silently after markProcessing, unknown URI scheme → IllegalArgumentException → FAILED.

Plan 05-05 will cover the real `@Async` execution path (via `SyncTaskExecutor` swapped in via a test config) end-to-end against a Testcontainers pgvector store.

## Deferred Issues

- **chunkCount column on `AiKnowledgeDocument`** — `markReady(id, chunkCount)` logs the count rather than persisting it. Plan 05-04 or a later UI plan may add a `CHUNK_COUNT` column via Liquibase changeset; the writer signature already anticipates it (no caller-side change needed when the column lands).
- **sourceUri formalisation** — the worker's `fileName`-as-source-hint is transitional. Plan 05-04's upload service will finalise the file storage and either add a `sourceUri` column or formalise the fileName-as-path convention.
- **`application.properties` in the starter** — deferred; if a host wants an override seam visible in the starter (rather than buried in `module.properties`), Plan 05-05 can add one.

## Known Stubs

None. `AsyncIngestionWorkerTest` uses Mockito for the VectorStore/IngestionStatusWriter collaborators — those are test-only doubles, not production placeholders. Production wiring hits the real `aiAgentIngestExecutor`, `IngestionStatusWriter`, `VectorStore` beans.

## Threat Flags

No new security surface introduced beyond the plan's `<threat_model>`. Every STRIDE row in the plan's threat register is either mitigated by the implementation (T-05-03-01 role flag flattening, T-05-03-02 REQUIRES_NEW status writer, T-05-03-03 bounded executor + CallerRunsPolicy, T-05-03-04 MDC propagation, T-05-03-05 scheme allow-list, T-05-03-06 cleanup-before-markFailed, T-05-03-07 2000-char cap on errorMessage [tightened to 1024 to match column width], T-05-03-08 Locale.ROOT lowercase) or explicitly out of scope for this plan.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/MdcPropagatingTaskDecorator.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionStatusWriterTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/CancellationRegistryTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java`
- FOUND: `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-alpha.md`
- FOUND: modifications in `AIConfiguration.java`, `AiKnowledgeDocumentStatus.java`, `module.properties`, `messages.properties`, `messages_vi.properties`
- FOUND: commit `f316f77` (Task 1 — writer + registry + CANCELLED enum)
- FOUND: commit `b75b443` (Task 2 — executor bean + MDC decorator + properties)
- FOUND: commit `97d60f3` (Task 3 — worker + fixture + tests)
- Verified: `./gradlew :ai-agent:ai-agent:test` exits 0 (BUILD SUCCESSFUL).
