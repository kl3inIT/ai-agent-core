---
phase: 05-rag-layer
plan: 04
subsystem: rag-document-service-spi
tags: [rag, role-validation, atomic-delete, reingest, spi, custom-ingester, i18n]
requires:
  - Plan 05-01 (AiAgentEmbeddingProperties, AiAgentRagProperties.SampleIngester)
  - Plan 05-02 (ChunkMetadata constants, RetrievalFilterBuilder)
  - Plan 05-03 (AsyncIngestionWorker, IngestionStatusWriter, CancellationRegistry)
  - Phase 2 (AiKnowledgeDocument entity, AiAgentUserRole/AdminRole, CustomIngester SPI)
provides:
  - KnowledgeDocumentUploadService (@Service @Transactional) — upload(sourceUri, sourceKind, allowedRoles) with RoleRepository validation + afterCommit async dispatch
  - KnowledgeDocumentService (@Service @Transactional) — delete(UUID) atomic + reingest(UUID) with REQUIRES_NEW markPending
  - UnknownRoleCodeException + DocumentNotFoundException typed exceptions (EN + VI i18n)
  - IngesterManager (@Component) — List<CustomIngester> fan-out with per-ingester and per-document isolation
  - ClasspathMarkdownIngester (@Component @ConditionalOnProperty) — opt-in reference ingester (disabled by default)
  - 6 new RAG i18n keys in EN + VI bundles
affects:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
patterns:
  - "Service-layer role validation via ResourceRoleRepository.findRoleByCode — fail-closed, unknown codes abort save"
  - "afterCommit async dispatch via TransactionSynchronizationManager (Pitfall #7 — worker never sees half-committed row)"
  - "Metadata.create() entity instantiation (PATTERNS rule — no `new AiKnowledgeDocument()`)"
  - "Atomic delete order: cancel → vectorStore.delete → dataManager.remove inside @Transactional(REQUIRED)"
  - "Per-ingester + per-document try/catch isolation (one broken ingester does not stop the batch)"
  - "@ConditionalOnProperty opt-in for sample / reference components (D-14)"
key-files-created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UnknownRoleCodeException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/DocumentNotFoundException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngesterManager.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ClasspathMarkdownIngester.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngesterManagerTest.java
key-files-modified:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "sourceUri persists into the Phase 2 entity's fileName column (no sourceUri column exists). sourceKind persists into mimeType. This honours the AsyncIngestionWorker source-resolution contract from Plan 05-03 without Liquibase churn."
  - "Role validation uses ResourceRoleRepository.findRoleByCode (Jmix 2.8 API). A null return from the repository is the unknown-code signal; no row-level repository touched for this check."
  - "allowedRoles null is treated as empty List (defensive); empty roles is NOT an upload-time error — downstream RetrievalFilterBuilder fail-closes on empty."
  - "reingest does NOT re-validate roles — the document's stored allowedRolesJson has already been validated at upload time. Re-validating would reject a doc whose role codes were deleted after initial upload, which is admin-UI territory not service territory."
  - "IngesterManager per-document failure BREAKS out of the current ingester (bounding blast radius) rather than continuing with the next document. Tested explicitly in `per_document_upload_failure_skips_rest_of_current_ingester_but_continues_with_next`."
  - "AIAutoConfiguration unchanged — AIConfiguration.@ComponentScan (default package = com.vn.agent) already covers com.vn.agent.rag, so ClasspathMarkdownIngester's @ConditionalOnProperty works without explicit @Bean registration."
  - "DEFERRED D-19 UUIDv5 synthetic ids: re-running a CustomIngester creates duplicate AiKnowledgeDocument rows. Documented in Javadoc and CONTEXT §Deferred; operator workaround is delete-then-reingest."
  - "IngesterManager forwards classpath: / file: URIs from Document.metadata.source verbatim when present; only bare / otherwise-unknown identifiers get the synthetic `ingester://<id>/<source>` shape. This lets the ClasspathMarkdownIngester's classpath URIs flow through to AsyncIngestionWorker resolution without needing to strip the ingester:// prefix."
metrics:
  duration_minutes: 8
  completed_date: 2026-04-20
  tasks_completed: 3
  files_created: 9
  files_modified: 2
  commits: 4
requirements-completed: [RAG-06, RAG-07, RAG-08, SPI-07]
---

# Phase 5 Plan 04: Upload service, atomic delete, CustomIngester SPI manager

Delivered the user-facing service layer for knowledge documents (RAG-06 role validation, RAG-07 atomic delete, RAG-08 reingest) and the CustomIngester SPI fan-out (SPI-07). Role codes are validated against Jmix's `ResourceRoleRepository` before persist; delete is atomic across pgvector and JPA with a cancellation handshake for in-flight workers; reingest purges old chunks, flips status via the REQUIRES_NEW writer from Plan 05-03, and reschedules the async worker. A reference `ClasspathMarkdownIngester` ships disabled by default so hosts see a working example without surprise data loads.

## Commits

| Task | Commit    | Description                                                                  |
| ---- | --------- | ---------------------------------------------------------------------------- |
| 1    | `07776bb` | feat(05-04): KnowledgeDocumentUploadService with RoleRepository validation   |
| 1-fix | `5f04405` | style(05-04): tighten comment wording to clear acceptance grep               |
| 2    | `0f491d7` | feat(05-04): KnowledgeDocumentService — atomic delete + reingest             |
| 3    | `ab30cdd` | feat(05-04): IngesterManager SPI fan-out + opt-in ClasspathMarkdownIngester  |

Branch: `gsd/phase-05-rag-layer`.

## Must-Haves

- [x] **KnowledgeDocumentUploadService.upload validates every role code via RoleRepository** — `for (String code : roles) roleRepository.findRoleByCode(code)`; unknown codes throw `UnknownRoleCodeException` before `dataManager.save`. Covered by `unknown_role_code_throws_and_does_not_persist_or_schedule` + `admin_code_is_validated_via_RoleRepository_not_allowlisted`.
- [x] **sourceUri contract is ResourceLoader-resolvable** — persists directly into `fileName`; AsyncIngestionWorker (Plan 05-03) accepts `classpath:` / `file:` prefixes and rejects other schemes with `IllegalArgumentException` → FAILED status.
- [x] **KnowledgeDocumentService.delete atomically removes pgvector chunks AND entity** — `@Transactional(REQUIRED)`; order cancel → vectorStore.delete → dataManager.remove. VectorStore failure propagates (entity kept for retry); test `delete_propagates_vectorStore_failure_and_skips_entity_remove`.
- [x] **delete signals CancellationRegistry BEFORE vector cleanup** — verified by Mockito `InOrder` in `delete_cancels_before_vectorStore_delete_before_entity_remove`.
- [x] **IngesterManager discovers every CustomIngester bean and isolates failures** — constructor `List<CustomIngester>` injection; per-ingester try/catch on `read()`; per-document try/catch on upload with break-to-next-ingester. Tests cover zero ingesters, fan-out, read failure, per-doc failure blast radius.
- [x] **ClasspathMarkdownIngester is opt-in** — `@ConditionalOnProperty(name = "jmix.ai-agent.rag.sample-ingester.enabled", havingValue = "true")`; `module.properties` default is `false`.
- [x] **i18n EN + VI symmetric** — 6 new keys (`UnknownRoleCodeException.message`, `DocumentNotFoundException.message`, `upload.roleValidation.failed`, `delete.inProgress`, `reingest.scheduled`, `ingester.failed`) in both bundles. `grep -c "com.vn.agent.rag/"` = 6 in each file.

## Upload Contract

```java
@Transactional
public AiKnowledgeDocument upload(String sourceUri, String sourceKind, Collection<String> allowedRoles)
    throws UnknownRoleCodeException;
```

| Parameter       | Contract                                                                                                                                                                     |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sourceUri`     | Spring `ResourceLoader`-resolvable URI — `classpath:` or `file:` only; non-null. Persists into `AiKnowledgeDocument.fileName`. Phase 7 Flow UI owns the Vaadin stream → temp file staging. |
| `sourceKind`    | Free-form label (`application/pdf`, `text/markdown`, `CUSTOM_INGESTER`, …); nullable. Persists into `AiKnowledgeDocument.mimeType`. Phase 5 does not parse this field.       |
| `allowedRoles`  | Jmix role codes; nullable (treated as empty list — fail-closed at retrieval per D-05). Each code MUST resolve via `ResourceRoleRepository.findRoleByCode`; unknown codes throw `UnknownRoleCodeException`. |

**Return value:** the persisted `AiKnowledgeDocument` with `id` populated and `status = PENDING`. The async worker is scheduled via `TransactionSynchronizationManager.registerSynchronization(afterCommit(...))` — the worker never observes a half-committed row.

**Future REST / Flow UI consumers** (Phase 7) call this service directly after staging uploaded bytes to a temp file; there is no Vaadin-specific code in this service.

## Atomic Delete Ordering + Known Limitation

```java
@Transactional(REQUIRED)
public void delete(UUID documentId) {
    AiKnowledgeDocument doc = loadOrThrow(documentId);
    cancellationRegistry.cancel(documentId);                  // 1. signal in-flight worker
    vectorStore.delete(eq(DOCUMENT_ID, documentId.toString())); // 2. purge pgvector chunks
    dataManager.remove(doc);                                   // 3. drop entity
}
```

- **Step 2 throws** → `@Transactional` rollback keeps the entity intact; admin sees status unchanged and can retry.
- **Step 3 throws** → JPA rollback CANNOT undo the pgvector delete (non-transactional boundary). The dangerous failure mode — orphan chunks visible to retrieval — is impossible (chunks already gone). A lingering entity row is recoverable: repeat `delete(id)` and it short-circuits via `DocumentNotFoundException` or completes cleanly depending on race timing.

This is the accepted CONTEXT §D-06 contract: orphaned-vectors-visible-to-retrieval cannot happen, and the rare "entity remains after vectors gone" case is self-healing via operator retry. 2PC was explicitly deferred.

## Reingest Semantics vs Upload

| Aspect                  | upload()                                          | reingest()                                                    |
| ----------------------- | ------------------------------------------------- | ------------------------------------------------------------- |
| Role validation         | Yes — via `ResourceRoleRepository.findRoleByCode` | **No** — doc's stored `allowedRolesJson` already validated    |
| Entity creation         | Yes — `Metadata.create` → `DataManager.save`      | No — entity persists; status reset only                       |
| Status after commit     | `PENDING`                                         | `PENDING` (via `IngestionStatusWriter.markPending` REQUIRES_NEW) |
| Vector purge            | N/A (no prior chunks)                             | Yes — `vectorStore.delete(documentId filter)` before status reset |
| Cancellation handshake  | N/A                                               | `cancel(id)` + `clear(id)` so fresh worker is not aborted on entry |
| Async dispatch          | afterCommit → `asyncIngestionWorker.ingest(id)`   | afterCommit → `asyncIngestionWorker.ingest(id)`               |

**Why reingest skips role re-validation:** the stored JSON has already been validated at upload time. If a host operator deletes a role that a document references (edge case), reingest still works — the stale code is harmless in `allowedRoles` metadata; retrieval's `RetrievalFilterBuilder` only matches against the caller's current authorities. Forcing re-validation at reingest time would turn a routine operator action into a cascading failure when roles churn.

## CustomIngester Default `allowedRoles` Policy

Every SPI-ingested document is tagged with `[AiAgentUserRole.CODE]` by `IngesterManager` per D-17 — matches the D-04 shared-default posture of the admin upload UI. Documents needing narrower exposure are retagged after ingest via the admin view (Phase 7). Hosts wanting a different default implement their own `CustomIngester` variant or post-process documents via a dedicated service.

## Sample Ingester Enable Flag + Default Pattern

```properties
# Classpath-markdown reference ingester — default OFF per D-14.
jmix.ai-agent.rag.sample-ingester.enabled=false
# Overridable via AiAgentRagProperties.SampleIngester.pathPattern; default used otherwise.
# Default: classpath*:ai-kb/**/*.md
```

When enabled, `ClasspathMarkdownIngester` scans the classpath pattern via
`ResourcePatternResolver.getResources(pattern)`, runs each matched resource
through `TikaDocumentReader`, and emits Documents whose `metadata.source` is the
resolved classpath URI. `IngesterManager` forwards that URI through
`KnowledgeDocumentUploadService.upload`; `AsyncIngestionWorker` resolves
`classpath:` URIs directly via its `ResourceLoader` without touching the default
`ai-kb/` fallback root.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `dataManager.save(any())` was ambiguous in tests**

- **Found during:** Task 1 test compile. `UnconstrainedDataManager.save(SaveContext)` and `save(Object...)` both match `any()` without a type bound, so Mockito call chain did not compile.
- **Fix:** Disambiguated to `save(any(AiKnowledgeDocument.class))` at both verify sites in `KnowledgeDocumentUploadServiceTest`.
- **Files modified:** test class only.
- **Commit:** Folded into `07776bb`.
- **Root cause:** Jmix 2.8 `DataManager` inherits from `UnconstrainedDataManager` which defines two overloads of `save`; the plan's `<action>` snippet used untyped `any()` matchers.

**2. [Rule 3 - Blocking] Entity shape does not carry `sourceUri` / `sourceKind` columns**

- **Found during:** Task 1 design. The plan's `upload(sourceUri, sourceKind, allowedRoles)` signature referenced fields that do not exist on the Phase 2 `AiKnowledgeDocument` (it has `fileName` and `mimeType` only; no `sourceUri`, no `sourceKind`). Plan 05-03 already accepted this shape drift for the async worker.
- **Fix:** Mapped `sourceUri` → `fileName` (matches AsyncIngestionWorker's source-resolution contract exactly) and `sourceKind` → `mimeType` (semantically compatible — `text/markdown`, `application/pdf`, or synthetic `CUSTOM_INGESTER` all fit). Documented the mapping in the service Javadoc so future REST / Flow UI consumers understand the column mapping without reading the entity.
- **Rationale for not adding columns:** adding `sourceUri`/`sourceKind` columns would require a Liquibase changeset (architectural, Rule 4). Plan 05-03 explicitly deferred this; propagating the deferral through Plan 05-04 keeps scope bounded. A future plan can rename the columns (or add new ones) without changing the service API shape.
- **Files modified:** `KnowledgeDocumentUploadService.java`.
- **Commit:** `07776bb`.

**3. [Rule 2 - Missing functionality] IngesterManager forwards classpath URIs verbatim**

- **Found during:** Task 3 design review. The plan's action snippet unconditionally wrapped every Document source with `"ingester://" + id + "/" + source` — but the `ClasspathMarkdownIngester` deliberately sets `metadata.source` to a resolvable `classpath:` URI precisely so AsyncIngestionWorker can read it. Prefixing with `ingester://` would turn every sample document into an unsupported-scheme FAILED status immediately.
- **Fix:** If `metadata.source` already starts with `classpath:` or `file:`, pass it through verbatim; otherwise apply the synthetic `ingester://...` wrapper. The ingester's intent is preserved; ingesters that want synthetic URIs just omit the `source` key (or put a non-resolvable value there).
- **Files modified:** `IngesterManager.java` + `IngesterManagerTest.java` (tests match the new behaviour).
- **Commit:** `ab30cdd`.
- **Root cause:** plan did not consider the interaction between the sample ingester's `metadata.source = classpathUri` convention and the synthetic URI wrapper.

### Verification Deltas

- **`findRoleByCode` vs `getRoleByCode`**: `ResourceRoleRepository` in Jmix 2.8 exposes both; the existing `FoundationsBootSmokeTest` uses `getRoleByCode`, but `findRoleByCode` returns null on miss instead of throwing — which is the signal we need for fail-closed validation. Both methods exist; we picked the null-returning variant.
- **`Document.builder()` in test fixtures**: Spring AI 1.1.4 exposes `Document.builder().text(...).metadata(Map).build()`. Confirmed via the existing `AsyncIngestionWorker` — same pattern reused.

## Verification Trace

| Acceptance Check                                                                           | Command                                                                                 | Result                     |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------- | -------------------------- |
| Metadata.create used (PATTERNS)                                                            | `grep -c "metadata.create(AiKnowledgeDocument" …/KnowledgeDocumentUploadService.java`   | 1                          |
| No `new AiKnowledgeDocument` in upload service                                             | `grep -c "new AiKnowledgeDocument" …/KnowledgeDocumentUploadService.java`               | 0                          |
| roleRepository used                                                                        | `grep -c "roleRepository" …/KnowledgeDocumentUploadService.java`                        | 4                          |
| registerSynchronization / afterCommit                                                      | `grep -cE "registerSynchronization|afterCommit" …/KnowledgeDocumentUploadService.java`  | 3                          |
| cancellationRegistry.cancel in service (delete+reingest)                                   | `grep -c "cancellationRegistry.cancel" …/KnowledgeDocumentService.java`                 | 2                          |
| vectorStore.delete in service (delete+reingest)                                            | `grep -c "vectorStore.delete" …/KnowledgeDocumentService.java`                          | 2                          |
| dataManager.remove in service (delete only)                                                | `grep -c "dataManager.remove" …/KnowledgeDocumentService.java`                          | 1                          |
| DocumentNotFoundException thrown                                                           | `grep -c "DocumentNotFoundException" …/KnowledgeDocumentService.java`                   | 3                          |
| afterCommit in service (reingest schedules worker)                                         | `grep -c "afterCommit" …/KnowledgeDocumentService.java`                                 | 2                          |
| List<CustomIngester> constructor injection                                                 | `grep -c "List<CustomIngester>" …/IngesterManager.java`                                 | 2                          |
| ConditionalOnProperty on sample ingester                                                   | `grep -c "ConditionalOnProperty" …/ClasspathMarkdownIngester.java`                      | 3                          |
| sample-ingester.enabled literal                                                            | `grep -c "sample-ingester.enabled" …/ClasspathMarkdownIngester.java`                    | 2                          |
| implements CustomIngester                                                                  | `grep -c "implements CustomIngester" …/ClasspathMarkdownIngester.java`                  | 1                          |
| i18n keys EN                                                                               | `grep -cE "UnknownRoleCodeException|DocumentNotFoundException" …/messages.properties`   | 2                          |
| i18n keys VI                                                                               | `grep -cE "UnknownRoleCodeException|DocumentNotFoundException" …/messages_vi.properties` | 2                         |
| Total RAG i18n keys EN                                                                     | `grep -c "com.vn.agent.rag/" …/messages.properties`                                     | 6                          |
| Total RAG i18n keys VI                                                                     | `grep -c "com.vn.agent.rag/" …/messages_vi.properties`                                  | 6                          |
| KnowledgeDocumentUploadServiceTest (6 tests)                                               | `./gradlew :ai-agent:ai-agent:test --tests "*KnowledgeDocumentUploadServiceTest"`       | BUILD SUCCESSFUL           |
| KnowledgeDocumentServiceTest (7 tests)                                                     | `./gradlew :ai-agent:ai-agent:test --tests "*KnowledgeDocumentServiceTest"`             | BUILD SUCCESSFUL           |
| IngesterManagerTest (7 tests)                                                              | `./gradlew :ai-agent:ai-agent:test --tests "*IngesterManagerTest"`                      | BUILD SUCCESSFUL           |
| Full `:ai-agent:ai-agent:test`                                                             | `./gradlew :ai-agent:ai-agent:test`                                                     | BUILD SUCCESSFUL           |

## Test Coverage Inventory

- **KnowledgeDocumentUploadServiceTest** (6 tests, Mockito + `TransactionSynchronizationManager.init`): happy path persists PENDING + schedules afterCommit, unknown role throws and does not persist, empty roles succeeds, null roles defensively treated as empty, worker not invoked before afterCommit, admin code not allowlisted (still validated through repository).
- **KnowledgeDocumentServiceTest** (7 tests, Mockito): delete happy path, delete missing → `DocumentNotFoundException`, delete propagates vectorStore failure + skips remove, delete InOrder (cancel → vector.delete → entity.remove), reingest happy path (cancel + clear + purge + markPending + afterCommit scheduling), reingest missing id, reingest cancels and clears even on vectorStore failure.
- **IngesterManagerTest** (7 tests, Mockito): zero ingesters, full fan-out across 2 ingesters × 5 docs, read-failure isolation, per-document upload failure blast-radius containment, synthetic URI for docs without metadata.source, allowedRoles always `[AiAgentUserRole.CODE]`, empty-list tolerance.

Full `:ai-agent:ai-agent:test` remains green (previous 130 tests + 20 new = 150).

## Deferred Issues

- **D-19 UUIDv5 synthetic ids for SPI-ingested docs** — CONTEXT.md §Deferred explicitly parks this. Re-running `IngesterManager.runAll()` creates duplicate `AiKnowledgeDocument` rows. Operator workaround is delete-then-reingest. Implementing UUIDv5 upsert requires an id-aware overload on `KnowledgeDocumentUploadService` plus a UUIDv5 helper — out of scope for Phase 5 bandwidth; revisit when a host surfaces a concrete rerun use case.
- **sourceUri / sourceKind columns on AiKnowledgeDocument** — Plan 05-03 folded the column mapping into `fileName` / `mimeType`; this plan keeps the same mapping. A future admin-UI plan may add dedicated columns (and a Liquibase changeset) if the display layer needs to distinguish "file name" from "source URI".
- **Admin-triggered `runById(String ingesterId)` on IngesterManager** — CONTEXT §D-18 mentions `runAll()` + `runById()`, but `runById` is unused in Phase 5 (Phase 7 admin UI is deferred). Added only `runAll()` to avoid dead code; plan 07-XX will add `runById` alongside the UI button that calls it.

## Known Stubs

None. Every service method persists real data; tests use Mockito doubles for collaborators but the production wiring hits real beans.

## Threat Flags

No new security surface beyond the plan's `<threat_model>`. Every STRIDE row is mitigated by the implementation:

- **T-05-04-01 (EoP / upload role forge)** — `ResourceRoleRepository.findRoleByCode` validation; `UnknownRoleCodeException` on null.
- **T-05-04-02 (Info disclosure / audit leak)** — `UnknownRoleCodeException` carries only the caller-supplied code; i18n keys avoid log concatenation.
- **T-05-04-03 (Tampering / delete ordering)** — `cancel → vectorStore.delete → dataManager.remove` inside `@Transactional(REQUIRED)`; InOrder-verified.
- **T-05-04-04 (DoS / ingester failure cascade)** — per-ingester + per-document try/catch with bounded break-to-next-ingester on upload failure.
- **T-05-04-05 (DoS / surprise classpath load)** — `@ConditionalOnProperty` default `false`.
- **T-05-04-06 (accept / default role posture)** — `[AiAgentUserRole.CODE]` matches D-04 shared default; documented.
- **T-05-04-07 (accept / non-transactional pgvector boundary)** — worst case logged; RAG-07 success criterion preserved (no orphan visible to retrieval).

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UnknownRoleCodeException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/DocumentNotFoundException.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngesterManager.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ClasspathMarkdownIngester.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngesterManagerTest.java`
- FOUND: commit `07776bb` (Task 1), `5f04405` (Task 1 fix), `0f491d7` (Task 2), `ab30cdd` (Task 3)
- Verified: `./gradlew :ai-agent:ai-agent:test` exits 0 (BUILD SUCCESSFUL).
