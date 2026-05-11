# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Pattern Map

**Mapped:** 2026-05-05
**Files analyzed:** 22 (15 new + 7 modified)
**Analogs found:** 21 / 22 (1 partial — multi-file Jmix `<upload>` API surface needs Context7 verification)

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `entity/AiTaskFile.java` (NEW) | entity | persistence | `tools/mutation/AiMutationIntent.java` (audit-fields shape) + `D:/DTH/jmix-crm/.../AiConversationAttachment.java` (FK + `@PropertyDatatype("fileRef")`) | exact (split) |
| `liquibase/agentstore-changelog/090-ai-task-file.xml` (NEW) | liquibase | schema-migration | `070-ai-mutation-intent.xml` (createTable + indexes) + `020-ai-message.xml` (FK with `foreignKeyName`/`references`) | exact (split) |
| `taskfile/AiTaskFileMediaResolver.java` (NEW) | resolver | request-response (read) | `D:/DTH/jmix-crm/.../AiAttachmentMediaResolver.java` | exact (verbatim port) |
| `taskfile/AiTaskFileRepository.java` (NEW) | repository | CRUD | `tools/mutation/MutationIntentRepository.java` (agentstore JdbcTemplate + `REQUIRES_NEW` on agentstoreTransactionManager) | role-match |
| `taskfile/AiTaskFileCleanupJob.java` (NEW) | scheduled-job | batch | `tools/mutation/MutationIntentCleanupJob.java` | exact |
| `taskfile/AiTaskFileProperties.java` (NEW) | config | binding | `tools/mutation/AiAgentMutationProperties.java` (`@ConfigurationProperties` w/ TTL) | exact |
| `tools/mutation/MutationSaveExecutor.java` (MODIFY: add `bulkSave`) | service | request-response | existing `save(Object)` / `saveAll(Object...)` in same file | exact (extension) |
| `tools/mutation/BuiltInMutationTools.java` (MODIFY: add `bulkSaveRecords`) | tool | request-response | existing `createRecord` + `updateRecord` `@Tool` methods in same file | exact (extension) |
| `DefaultChatServiceImpl.java` (MODIFY: inject Media + markSent) | service | streaming/request-response | `D:/DTH/jmix-crm/.../CrmAnalyticsService.java` lines 117–131 | exact |
| `view/chat/fragment/ChatPanelFragment.java` (MODIFY: chip strip + upload) | view-controller | event-driven | existing `ChatPanelFragment` (Phase 12 wiring of `MessageInput`/`ProgressBar`/`messageInputSlot`) | exact (extension) |
| `view/chat/fragment/chat-panel-fragment.xml` (MODIFY: turn `attachmentsPanel` into chip vbox; add upload row above MessageInput) | view-xml | UI-layout | existing `chat-panel-fragment.xml` `messageInputSlot` slot | exact (extension) |
| `security/AiAgentUserRole.java` (MODIFY: `@EntityPolicy(AiTaskFile, READ+CREATE)`) | role | RBAC | existing `userAccess()` w/ `@EntityPolicy(AiConversation,…)` `@EntityPolicy(AiMessage,…)` | exact (extension) |
| `security/AiAgentUserRowLevelRole.java` (MODIFY: row-level on `userUsername`) | role | RBAC | existing `conversation()` `@JpqlRowLevelPolicy` | exact (extension) |
| `security/AiAgentAdminRole.java` (MODIFY: `@EntityPolicy(AiTaskFile, ALL)`) | role | RBAC | existing `adminAccess()` | exact (extension) |
| `messages_en.properties` (MODIFY: entity + chip + error keys) | message-bundle | i18n | existing entity-caption blocks | exact (extension) |
| `messages_vi.properties` (MODIFY: vi parity) | message-bundle | i18n | existing entity-caption blocks | exact (extension) |
| `jmix-app/src/main/resources/application.properties` (MODIFY: model swap + ttl + max-size) | properties | config | existing line 54/63 model + 81 RAG max-file-size keys | exact (extension) |
| `test/.../AiTaskFileMediaResolverIntegrationTest.java` (NEW) | test | integration | `BuiltInMutationToolsAuditArgumentsTest.java` `@SpringBootTest` shape | role-match |
| `test/.../BuiltInMutationToolsBulkSaveTest.java` (NEW) | test | integration | `BuiltInMutationToolsAuditArgumentsTest.java` + `BuiltInMutationToolsIdempotencyReplayTest.java` | exact |
| `test/.../BuiltInMutationToolsBulkSavePartialFailureTest.java` (NEW) | test | integration | `BuiltInMutationToolsKnownRollbackTest.java` | role-match |
| `test/.../TaskFileNoVectorStoreSourceScannerTest.java` (NEW for TEST-16) | test | static-source-scan | (no analog — net-new pattern; planner builds reflective grep over `com.vn.agent.taskfile.**` source files) | partial |
| `test/.../AiTaskFileCleanupJobTest.java` (NEW) | test | integration | (no direct analog — closest is `MutationIntentCleanupJobTest` if it exists; otherwise mirror `MutationIntentRepositoryStateTransitionTest` pattern) | role-match |

---

## Pattern Assignments

### `entity/AiTaskFile.java` (entity, persistence)

**Primary analog:** `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\tools\mutation\AiMutationIntent.java`
**Secondary analog (FileRef + FK only):** `D:\DTH\jmix-crm\src\main\java\com\company\crm\ai\model\AiConversationAttachment.java`

**Annotations + table pattern** (AiMutationIntent.java lines 44–53):
```java
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiMutationIntent")    // change to "ai_AiTaskFile"
@Table(name = "AI_MUTATION_INTENT", indexes = {  // change to "AI_TASK_FILE"
        @Index(name = "IDX_AI_TASK_FILE_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_TASK_FILE_MESSAGE",      columnList = "MESSAGE_ID"),
        @Index(name = "IDX_AI_TASK_FILE_EXPIRES_AT",   columnList = "EXPIRES_AT")
})
```

**Identity + version + audit pattern** (AiMutationIntent.java lines 56–63 + AiUiSettings.java lines 55–69):
```java
@Id @Column(name = "ID", nullable = false) @JmixGeneratedValue private UUID id;
@Version @Column(name = "VERSION", nullable = false) private Integer version;
@CreatedBy   @Column(name = "CREATED_BY")        private String createdBy;
@CreatedDate @Column(name = "CREATED_DATE")      private OffsetDateTime createdDate;
@LastModifiedBy   @Column(name = "LAST_MODIFIED_BY")   private String lastModifiedBy;
@LastModifiedDate @Column(name = "LAST_MODIFIED_DATE") private OffsetDateTime lastModifiedDate;
```

**FK pattern (NOT NULL conversation FK; nullable message FK)** — adapted from `AiMessage.java` lines 32–36:
```java
@NotNull
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@OnDelete(DeletePolicy.CASCADE)
@JoinColumn(name = "CONVERSATION_ID", nullable = false)
private AiConversation conversation;

// Nullable per D-03 — set in two-phase write after AiMessage row is persisted.
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "MESSAGE_ID")
private AiMessage message;
```

**FileRef storage column** (AiConversationAttachment.java lines 32–35) — `@PropertyDatatype("fileRef")` IS the contract:
```java
@NotNull
@Column(name = "STORAGE_REF", nullable = false, length = 1024)
@PropertyDatatype("fileRef")
private FileRef storageRef;
```

**TTL + InstanceName** (mirrors AiMutationIntent.java lines 69–72, 105–111):
```java
@InstanceName
@NotNull @Column(name = "FILENAME", nullable = false, length = 255) private String filename;
@Column(name = "CONTENT_TYPE", length = 128) private String contentType;
@Column(name = "SIZE_BYTES") private Long sizeBytes;
@NotNull @Column(name = "USER_USERNAME", nullable = false, length = 255) private String userUsername;
@NotNull @Column(name = "EXPIRES_AT", nullable = false) private OffsetDateTime expiresAt;
```

**Pitfalls:**
- Do NOT add `@Lob` on `storageRef` — `@PropertyDatatype("fileRef")` controls serialization, the column stays `varchar(1024)`.
- Do NOT use Lombok (CLAUDE.md forbidden).
- Do NOT instantiate via `new AiTaskFile()` — use `Metadata.create(AiTaskFile.class)` (CLAUDE.md memory `feedback_jmix_unconstrained_for_system_writes`).

---

### `liquibase/agentstore-changelog/090-ai-task-file.xml` (liquibase, schema-migration)

**Primary analog:** `070-ai-mutation-intent.xml` (createTable + non-FK indexes shape)
**Secondary analog (FK syntax):** `020-ai-message.xml` lines 16–19 (foreignKeyName + references)

**createTable pattern** — copy header from 070, body adapted:
```xml
<changeSet id="1" author="ai-agent">
    <createTable tableName="AI_TASK_FILE">
        <column name="ID" type="${uuid.type}">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="VERSION" type="int" defaultValueNumeric="1">
            <constraints nullable="false"/>
        </column>
        <column name="CONVERSATION_ID" type="${uuid.type}">
            <constraints nullable="false"
                         foreignKeyName="FK_AI_TASK_FILE__ON_CONVERSATION"
                         references="AI_AGENT_CONVERSATION(ID)"/>
        </column>
        <column name="MESSAGE_ID" type="${uuid.type}">
            <!-- Nullable per D-03 (two-phase write); ON DELETE SET NULL via Liquibase
                 onDelete attribute on the FK. -->
            <constraints foreignKeyName="FK_AI_TASK_FILE__ON_MESSAGE"
                         references="AI_AGENT_MESSAGE(ID)"
                         deleteCascade="false"/>
        </column>
        <column name="USER_USERNAME" type="varchar(255)"><constraints nullable="false"/></column>
        <column name="FILENAME" type="varchar(255)"><constraints nullable="false"/></column>
        <column name="CONTENT_TYPE" type="varchar(128)"/>
        <column name="SIZE_BYTES" type="bigint"/>
        <column name="STORAGE_REF" type="varchar(1024)"><constraints nullable="false"/></column>
        <column name="CREATED_BY"  type="varchar(255)"/>
        <column name="CREATED_DATE" type="datetime"/>
        <column name="LAST_MODIFIED_BY" type="varchar(255)"/>
        <column name="LAST_MODIFIED_DATE" type="datetime"/>
        <column name="EXPIRES_AT" type="datetime"><constraints nullable="false"/></column>
    </createTable>
</changeSet>
```

**Indexes** — copy 070-ai-mutation-intent.xml lines 53–67 shape:
```xml
<changeSet id="2" author="ai-agent">
    <createIndex indexName="IDX_AI_TASK_FILE_CONVERSATION" tableName="AI_TASK_FILE">
        <column name="CONVERSATION_ID"/>
    </createIndex>
    <createIndex indexName="IDX_AI_TASK_FILE_MESSAGE" tableName="AI_TASK_FILE">
        <column name="MESSAGE_ID"/>
    </createIndex>
    <createIndex indexName="IDX_AI_TASK_FILE_EXPIRES_AT" tableName="AI_TASK_FILE">
        <column name="EXPIRES_AT"/>
    </createIndex>
</changeSet>
```

**Pitfalls:**
- `${uuid.type}` token MUST be used (postgres `uuid`, hsqldb `varchar(36)`, mssql `uniqueidentifier`) — defined in `agentstore-changelog.xml` lines 9–11.
- File auto-included by `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog">` (line 15 of `agentstore-changelog.xml`) — do NOT manually add an `<include>` line.
- Reference Phase 1/2 table names: conversation = `AI_AGENT_CONVERSATION`, message = `AI_AGENT_MESSAGE` (NOT `AI_CONVERSATION` / `AI_MESSAGE`).
- Index name max length on Postgres is 63 chars — keep names short.

---

### `taskfile/AiTaskFileMediaResolver.java` (resolver, request-response)

**Analog (verbatim port):** `D:\DTH\jmix-crm\src\main\java\com\company\crm\ai\service\AiAttachmentMediaResolver.java`

**Imports + class shape** (AiAttachmentMediaResolver lines 1–25) — copy verbatim except for `@Service` → `@Component`, package `com.vn.agent.taskfile`, and adapt to `AiTaskFile`:
```java
package com.vn.agent.taskfile;

import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.UnconstrainedDataManager;          // changed from DataManager — see pitfall #2
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
```

**MIME allowlist** (AiAttachmentMediaResolver lines 29–61) — port verbatim (13 entries `Media.Format.DOC_*` + `IMAGE_*`); do NOT modify.

**Resolve method — adapt query for `messageId IS NULL` per D-01** (AiAttachmentMediaResolver lines 87–106 → adapted):
```java
public Resolved resolvePending(UUID conversationId) {
    if (conversationId == null) return Resolved.empty();
    List<AiTaskFile> pending = unconstrainedDataManager.load(AiTaskFile.class)
            .query("select e from ai_AiTaskFile e " +
                   "where e.conversation.id = :cid and e.message is null " +
                   "  and e.expiresAt > :now order by e.createdDate asc")
            .parameter("cid", conversationId)
            .parameter("now", OffsetDateTime.now())
            .list();
    List<Media> media = pending.stream().map(this::buildMedia).toList();
    List<UUID> ids   = pending.stream().map(AiTaskFile::getId).toList();
    return new Resolved(media, ids);
}
```

**Media builder** (AiAttachmentMediaResolver lines 101–105) — verbatim:
```java
return Media.builder()
        .mimeType(resolveSupportedMimeType(row.getContentType(), row.getFilename()))
        .data(readFileBytes(row.getStorageRef()))
        .name(sanitizeMediaName(row.getFilename()))
        .build();
```

**Helpers** — port verbatim (lines 108–164): `readFileBytes`, `resolveSupportedMimeType`, `tryParseMimeType`, `mimeTypeFromExtension`, `sanitizeMediaName` (regex `[^A-Za-z0-9\\s\\-()\\[\\]]`, `MAX_MEDIA_NAME_LENGTH = 96`).

**Pitfalls:**
1. **Do NOT include `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, or `RAG` references** — TEST-16 source scanner will fail. The `taskfile/` package MUST be structurally disjoint from `rag/`.
2. **Use `UnconstrainedDataManager`**, NOT `DataManager` — system-internal load on behalf of chat path; aligns with memory `feedback_jmix_unconstrained_for_system_writes`. (Resolver runs in user request thread but reads agentstore rows owned by the user; using `UnconstrainedDataManager` here is consistent with how Phase 11 finalize/audit reads work.)
3. **JPQL entity name is `ai_AiTaskFile`** (matches `@Entity(name = "ai_AiTaskFile")`) — do NOT use `AiTaskFile` raw class name in JPQL.
4. **Do NOT use `loadValues(...)` raw JPQL** here — Jmix `dataManager.load(Class).query()` infers store from `@Store` annotation; only `loadValue/loadValues` need explicit `.store("agentstore")` (memory `feedback_jmix_loadvalue_store`).
5. **`Media.Format` lives in `org.springframework.ai.content.Media`** (NOT `org.springframework.ai.model.Media` — moved at Spring AI 1.0 GA per AI-SPEC §3 pitfall 1).

---

### `taskfile/AiTaskFileRepository.java` (repository, CRUD)

**Analog:** `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\tools\mutation\MutationIntentRepository.java` lines 60–93

**Bean wiring** (constructor injection + agentstore JdbcTemplate qualifier — MutationIntentRepository.java lines 60–71):
```java
@Component
public class AiTaskFileRepository {

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FileStorageLocator fileStorageLocator;
    private final TransactionTemplate agentstoreRequiresNew;

    public AiTaskFileRepository(UnconstrainedDataManager unconstrainedDataManager,
                                FileStorageLocator fileStorageLocator,
                                @Qualifier("agentstoreTransactionManager")
                                PlatformTransactionManager agentstoreTxManager) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.fileStorageLocator = fileStorageLocator;
        this.agentstoreRequiresNew = new TransactionTemplate(agentstoreTxManager);
        this.agentstoreRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
```

**Methods to expose:**
- `loadPending(UUID conversationId)` → see resolver (delegate or duplicate)
- `markSent(List<UUID> ids, UUID messageId)` — UPDATE `messageId` for the specified ids; runs in `agentstoreRequiresNew` so failure here does NOT roll back the chat-memory persist
- `loadExpired(OffsetDateTime now)` — `expiresAt < :now`
- `deleteRow(UUID id)` — `unconstrainedDataManager.remove(...)` plus `fileStorage.removeFile(fileRef)` BEFORE row delete (cleanup-job orchestration)
- `deleteAllExpired(OffsetDateTime now)` — bulk wrapper used by `AiTaskFileCleanupJob`

**Pitfalls:**
- `markSent` MUST run in `REQUIRES_NEW` (or via agentstore TransactionTemplate) so it commits even if the surrounding chat-memory advisor's transaction rolls back. Mirrors `AuditWriter.writeToolCall` REQUIRES_NEW invariant from Phase 11.
- Use `UnconstrainedDataManager` for all writes here — system-internal stamping, audit-writer pattern (memory `feedback_jmix_unconstrained_for_system_writes`).

---

### `taskfile/AiTaskFileCleanupJob.java` (scheduled-job, batch)

**Analog (exact pattern):** `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\tools\mutation\MutationIntentCleanupJob.java`

**Full skeleton** (copy lines 28–53 verbatim, swap collaborator class):
```java
@Component
public class AiTaskFileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileCleanupJob.class);
    private final AiTaskFileRepository repository;

    public AiTaskFileCleanupJob(AiTaskFileRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly at minute 0 — same as MutationIntentCleanupJob
    @Transactional("agentstoreTransactionManager")
    public void deleteExpiredTaskFiles() {
        OffsetDateTime now = OffsetDateTime.now();
        int removed = repository.deleteAllExpired(now);
        if (removed > 0) {
            log.debug("Removed {} expired AiTaskFile rows", removed);
        }
    }
}
```

**Pitfalls:**
- `@EnableScheduling` is already activated on `AIConfiguration` (Phase 11 plumbing) — verify before assuming it's on.
- Job MUST first `FileStorage.removeFile(fileRef)` before deleting row (handled inside repo method `deleteRow`); otherwise blob storage accumulates orphans.
- Use `@Transactional("agentstoreTransactionManager")` — NOT `@Transactional` (would target host primary datasource).

---

### `taskfile/AiTaskFileProperties.java` (config, binding)

**Analog:** `tools/mutation/AiAgentMutationProperties.java` (`@ConfigurationProperties` for `idempotencyTtl`)

**Shape:**
```java
@ConfigurationProperties(prefix = "ai-agent.task-file")
public class AiTaskFileProperties {
    private Duration ttl = Duration.ofHours(1);
    private long maxFileSizeBytes = 104_857_600L;   // mirrors RAG cap
    // standard getters/setters
}
```

**Registration:** add `@EnableConfigurationProperties(AiTaskFileProperties.class)` to `AIConfiguration` next to the existing `AiAgentMutationProperties` registration.

---

### `tools/mutation/MutationSaveExecutor.java` (MODIFY: add `bulkSave`)

**Analog (existing methods in same file):** `MutationSaveExecutor.java` lines 33–47

**New method to add (mirror `save` and `saveAll` shape):**
```java
/**
 * Bulk save for {@code bulk_save_records}. Single transactional span — any
 * RuntimeException from {@code DataManager.save(SaveContext)} rolls back ALL rows.
 * Uses regular {@link DataManager} (NOT {@code UnconstrainedDataManager}) so user
 * row policies, lifecycle events, and listeners run on every per-row save (MUT-03 invariant).
 */
@Transactional
public EntitySet bulkSave(SaveContext saveContext) {
    return dataManager.save(saveContext);
}
```

**Pitfalls:**
- Do NOT wrap a Java `for (entity : list) dataManager.save(entity)` loop — that creates N transactions and N round-trips. Use `SaveContext.saving(entity)` for all entities, then ONE `dataManager.save(saveContext)` call.
- Do NOT add `@Transactional` to the calling tool method (`bulkSaveRecords`) — Spring proxy self-invocation pitfall (this whole class exists FOR that reason — see file JavaDoc lines 8–24).
- Do NOT switch to `UnconstrainedDataManager` — it would bypass user row policies and break the Phase 11 chain.

---

### `tools/mutation/BuiltInMutationTools.java` (MODIFY: add `bulkSaveRecords`)

**Analog (extension target — same file):** existing `createRecord` at lines 174–275 + `updateRecord` at lines 312–432.

**Constructor + dependencies** — already has every collaborator needed (lines 94–126); no new field required for the bulk path. Just add new `@Tool` method beside `createRecord`.

**`@Tool` description** — follow rich-description template per memory `feedback_rich_tool_descriptions` and AI-SPEC §4 (5 sections, ~120 lines, two examples for create-only batch + mixed PDF-update batch). Skeleton begins at AI-SPEC line 386 (`@Tool(name = "bulk_save_records", description = """ MANDATORY WORKFLOW: ...`).

**Method skeleton — mirror `createRecord` (lines 174–275) try/catch/finally shape:**
```java
@Tool(name = "bulk_save_records", description = """ ... """)
public String bulkSaveRecords(
        @ToolParam(...) String entityName,
        @ToolParam(...) List<Map<String, Object>> records,
        @ToolParam(...) String idempotencyKey) {

    long startedAt = System.currentTimeMillis();
    String userUsername = currentAuthentication.getUser().getUsername();
    MetaClass metaClass = null;
    MutationIntentRepository.ReservationResult reservation = null;
    MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;
    Integer failedRowIndex = null;

    try {
        // Step 1 — marker-role gate (createRecord line 187).
        mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

        // Step 2 — entity resolution. Both creatable AND updatable if any row has id;
        // creatable-only if all rows omit id; updatable-only if all rows include id.
        boolean anyCreate = records.stream().anyMatch(r -> r.get("id") == null);
        boolean anyUpdate = records.stream().anyMatch(r -> r.get("id") != null);
        if (anyCreate) metaClass = toolEntityResolver.resolveCreatableEntityOrThrow(entityName);
        if (anyUpdate) metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);

        // Step 3 — entity-level CRUD gate.
        if (anyCreate) mutationAuthorizationService.enforceCreatePermission(metaClass);
        if (anyUpdate) mutationAuthorizationService.enforceUpdatePermission(metaClass);

        // Step 4 — per-attribute write access (UNION of all keys across rows).
        Set<String> writtenKeys = records.stream()
                .flatMap(r -> r.keySet().stream())
                .filter(k -> !"id".equals(k))
                .collect(Collectors.toSet());
        mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, writtenKeys);

        // Step 5 — request hash over batch envelope (records in submission order).
        Map<String, Object> batchEnvelope = Map.of("records", records);
        String requestHash = mutationRequestHasher.hash(
                "bulk_save_records", entityName, null, null, null, batchEnvelope);

        // Step 6 — single reservation row covering whole batch.
        reservation = mutationIntentRepository.reserveOrReplay(
                "bulk_save_records", idempotencyKey, userUsername, RunContext.getConversationId(),
                requestHash, mutationProperties.resolvedIdempotencyTtl());
        if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
            return mutationCommitCoordinator.handleReservationResult(
                    reservation, "bulk_save_records", startedAt, userUsername,
                    diffSerializer.serializeBulkArgumentsJson(entityName, records, idempotencyKey));
        }

        // Step 7 — per-row coerce + guard veto, build SaveContext.
        SaveContext saveContext = new SaveContext();
        List<UUID> savedIds = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            failedRowIndex = i;   // captured into audit on rollback
            Map<String, Object> row = records.get(i);
            Object rowId = row.get("id");
            Map<String, Object> rowAttrs = row.entrySet().stream()
                    .filter(e -> !"id".equals(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Map<String, Object> coerced = mutationAttributeBinder.coerceAttributes(metaClass, rowAttrs);
            Object entity;
            if (rowId == null) {
                entity = dataManager.create(metaClass.getJavaClass());
            } else {
                UUID parsed = mutationAttributeBinder.requireUuidId(toolEntityResolver.parseEntityId(rowId.toString(), metaClass));
                entity = dataManager.load(metaClass.getJavaClass()).id(parsed).optional()
                        .orElseThrow(() -> mutationErrorTranslator.notFound(metaClass, rowId.toString()));
            }
            mutationGuard.check(new MutationIntent("bulk_save_records", metaClass,
                    rowId == null ? null : (UUID) EntityValues.getId(entity), coerced));
            mutationAttributeBinder.applyAttributes(metaClass, entity, coerced);
            saveContext.saving(entity);
        }
        failedRowIndex = null;

        // Step 8 — single proxy-crossed @Transactional save.
        EntitySet saved = mutationSaveExecutor.bulkSave(saveContext);
        commitState = MutationCommitState.HOST_SAVE_RETURNED;
        saved.forEach(e -> savedIds.add((UUID) EntityValues.getId(e)));

        // Step 9 — finalize idempotency BEFORE audit (Phase 11 invariant).
        UUID firstId = savedIds.isEmpty() ? null : savedIds.get(0);
        mutationIntentRepository.markCommitted(reservation.intent(), firstId, metaClass.getName());
        commitState = MutationCommitState.INTENT_COMMITTED;

        // Step 10 — audit: argumentsJson = {count, entityName, sampleHashes, idempotencyKey}.
        mutationCommitCoordinator.safeWriteAudit("bulk_save_records",
                diffSerializer.serializeBulkArgumentsJson(entityName, records, idempotencyKey),
                diffSerializer.serializeBulkResultSummary(savedIds),
                System.currentTimeMillis() - startedAt,
                AiToolCallOutcome.SUCCESS, null, null, userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("count", savedIds.size());
        result.put("savedIds", savedIds);
        return toolResultFormatter.toJson(result);

    } catch (ToolVetoedException ve) { /* same shape as createRecord lines 241–252 */ }
      catch (ToolUserError tue)      { /* same shape lines 253–261, augmented w/ failedRowIndex */ }
      catch (Throwable t)            { /* same shape lines 262–273 */ }
}
```

**`DiffSerializer` extensions needed (Phase 11 collaborator):**
- `serializeBulkArgumentsJson(entityName, records, idempotencyKey)` → `{count, entityName, sampleHashes:[sha256(row[0..min(2,n)])], idempotencyKey}` — NEVER raw row values (P-22 / AUD-07).
- `serializeBulkResultSummary(savedIds)` → `{savedIds: [...], count: N}`.

**Critical pattern excerpts (createRecord lines 196–229):**
```java
// Canonical raw-call-shape request hash + pre-save reservation/replay.
String requestHash = mutationRequestHasher.hash(...);
reservation = mutationIntentRepository.reserveOrReplay(...);
if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
    return mutationCommitCoordinator.handleReservationResult(...);
}
// Coerce + validate BEFORE the guard so guards see typed values.
Map<String, Object> coercedAttributes = mutationAttributeBinder.coerceAttributes(metaClass, safeAttributes);
mutationGuard.check(new MutationIntent("create_record", metaClass, null, coercedAttributes));
// Build entity in-memory; SAVE via separate @Component (proxy crossed -> real @Transactional).
Object entity = dataManager.create(metaClass.getJavaClass());
Map<String, Object> postImage = mutationAttributeBinder.applyAttributes(metaClass, entity, coercedAttributes);
Object saved = mutationSaveExecutor.save(entity);
commitState = MutationCommitState.HOST_SAVE_RETURNED;
// ...
mutationIntentRepository.markCommitted(reservation.intent(), savedId, metaClass.getName());
commitState = MutationCommitState.INTENT_COMMITTED;
mutationCommitCoordinator.safeWriteAudit("create_record", argumentsJson, diffJson, ...);
```

**Pitfalls (CRITICAL):**
1. **NO `@Transactional` on `bulkSaveRecords`** — must cross the proxy via `mutationSaveExecutor.bulkSave(...)` (BuiltInMutationTools class JavaDoc lines 56–60).
2. **One reservation, one audit, one intent row per batch** — never per-row reserve/audit (D-02 invariant).
3. **`requestHash` MUST be over records in submission order** (`LinkedHashMap` / `List`, NOT a sorted set) — Stripe-style byte-identical retry.
4. **PII safety: never echo raw values** — `failedRowIndex` is the only per-row breadcrumb in error/audit; `failedAttribute` may be added when `MutationErrorTranslator` exposes it but never the value (AI-SPEC §4 + Phase 11 P-22).
5. **`@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")`** is already on the class (line 73–74) — adding the method automatically inherits the gate; no extra wiring.
6. **DO NOT split into `bulk_create_records` + `bulk_update_records`** — D-02 locked id-presence dispatch single-tool.

---

### `DefaultChatServiceImpl.java` (MODIFY: inject Media + markSent)

**Analog:** `D:\DTH\jmix-crm\src\main\java\com\company\crm\ai\service\CrmAnalyticsService.java` lines 117–131 (verify by reading that file before planning).

**Insertion point (`ask` method):** before the existing `chatClient.prompt()` builder. Hoist resolver result into a final local so the lambda can capture it.

**Pattern excerpt (target shape — adapted from CRM):**
```java
AiTaskFileMediaResolver.Resolved resolved = taskFileMediaResolver.resolvePending(convId);

ChatClientResponse clientResp = chatClient.prompt()
        .system(composedSystemPrompt)
        .user(u -> {
            u.text(message);
            if (!resolved.isEmpty()) {
                u.media(resolved.media().toArray(new Media[0]));   // single-turn-only (D-01)
            }
        })
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
        .toolContext(...)
        .advisors(...)
        .options(...)
        .call()
        .chatClientResponse();

// AFTER assistant message persisted (chat memory advisor fires inside .call()):
if (!resolved.isEmpty()) {
    UUID userMessageId = ...;  // resolve from chat-memory store; planner picks the API
    taskFileRepository.markSent(resolved.taskFileIds(), userMessageId);
}
```

**Insertion point (`stream` method):** identical, but hoist `resolvePending(convId)` INSIDE the `Flux.defer(() -> { ... })` body (otherwise it runs eagerly before subscription; AI-SPEC anti-pattern). Run `markSent` inside `doOnComplete(...)` so a cancelled stream does NOT mark files as sent.

**Pitfalls:**
1. **Hoist resolver INSIDE `Flux.defer(...)`** for the streaming path — calling `Media.builder()`+`FileStorage.openStream` on the calling thread before `.subscribe()` blocks the reactive pipeline.
2. **Lambda form `.user(u -> ...)` is required** for `.media(...)` — the convenience overload `.user(String)` silently drops media (AI-SPEC §3 pitfall 6).
3. **`markSent` runs in `doOnComplete`, NOT `doOnSubscribe`** — D-03 two-phase write semantics: cancelled / errored streams must NOT stamp the file as sent (re-attach on retry).
4. **`Media.Format` from `org.springframework.ai.content.Media`** — confirm import.

---

### `view/chat/fragment/ChatPanelFragment.java` + `chat-panel-fragment.xml` (MODIFY)

**Analog:** existing `ChatPanelFragment.java` `onReady` (lines 98–120) + `messageInputSlot` slot in `chat-panel-fragment.xml` line 37.

**XML modification (`attachmentsPanel` becomes chip-strip + add upload row to `messageInputSlot`):**
```xml
<!-- Phase 13: turn attachmentsPanel into a chip strip ABOVE the chat surface (or replace
     the split's right pane). Keep messageInputSlot as the host for the attach button row. -->
<vbox id="attachmentsPanel"
      width="100%"
      padding="false"
      spacing="false"
      classNames="ai-agent-chat-panel__attachments"
      visible="false"/>

<!-- messageInputSlot now hosts (programmatically in onReady, mirroring the existing
     streamProgressBar + MessageInput pattern at lines 110–115):
     1. attach-button row (HBox containing JmixButton 'Attach' + hidden Upload component)
     2. streamProgressBar (existing)
     3. MessageInput (existing) -->
```

**Java additions to `ChatPanelFragment.java` `onReady` — mirror existing slot wiring at lines 100–120:**
```java
// New: chip strip lives in attachmentsPanel slot (formerly hidden vbox).
chipStrip = new HorizontalLayout();
chipStrip.addClassName("ai-agent-chat-panel__chips");
chipStrip.setWidthFull();
attachmentsPanel.add(chipStrip);
attachmentsPanel.setVisible(true);

// New: hidden Upload + visible attach button (D-04).
upload = new com.vaadin.flow.component.upload.Upload();
upload.setVisible(false);
upload.setMaxFiles(10);
upload.setMaxFileSize(104_857_600);
upload.setAcceptedFileTypes(/* MIME allowlist mirror */);
// Vaadin 24.8: prefer UploadHandler.toFile per memory feedback_jmix_upload_receiver_deprecated.
// PLANNER MUST verify multi-file UploadHandler.toFile API surface in jmix-flowui 2.8 via Context7.

attachButton = new JmixButton(VaadinIcon.PAPERCLIP.create());
attachButton.addClickListener(e -> upload.getElement().callJsFunction("uploadFiles"));

HorizontalLayout attachRow = new HorizontalLayout(attachButton, upload);
messageInputSlot.add(attachRow);
// Existing pattern preserved:
messageInputSlot.add(streamProgressBar, messageInput);
```

**Event wiring** — use `@Subscribe` per memory `feedback_jmix_view_listeners`. Vaadin events: `SucceededEvent`, `FailedEvent`, `FileRejectedEvent`. Per memory `feedback_jmix_upload_receiver_deprecated`, `FileRejectedEvent` is the only one safe via `@Subscribe`; the rest may need `addSucceededListener` until jmix-flowui 2.8 ships fluent equivalents (verify via Context7 → jmix-framework/jmix-context7 "upload component flow events").

**On `SucceededEvent`** (mirror jmix-crm `AiConversationDetailView` lines 157–217):
```java
// 1. Read bytes from UploadHandler temp file
// 2. fileStorage.saveStream(filename, in) -> FileRef
// 3. AiTaskFile row = metadata.create(AiTaskFile.class); row.setConversation(...); row.setStorageRef(ref);
//    row.setExpiresAt(now + ttl); row.setUserUsername(currentAuthentication.getUser().getUsername()); ...
// 4. dataManager.save(row)  (USER row policy applies - CREATE)
// 5. Render chip in chipStrip; attach remove-icon click handler that calls dataManager.remove(row)
//    AND fileStorage.removeFile(fileRef)
```

**Pitfalls:**
1. **Do NOT call `setReceiver(MultiFileTemporaryStorageBuffer)`** — `Upload.getReceiver/setReceiver` is `forRemoval` in Vaadin 24.8 (memory `feedback_jmix_upload_receiver_deprecated`). Use `UploadHandler.toFile`.
2. **Vaadin `MessageInput` exposes no prefix/suffix slot** — attach button MUST live in a sibling row in `messageInputSlot`, NOT inside `MessageInput` (CONTEXT D-04).
3. **MIME allowlist + size cap MUST be enforced server-side** in the upload handler (defence-in-depth) — client-side `acceptedFileTypes` is bypassable.
4. **Do NOT instantiate `AiTaskFile` via `new`** — use `Metadata.create(AiTaskFile.class)` per CLAUDE.md.
5. **Use `Messages` (Jmix), NOT Spring `MessageSource`** for chip labels / errors (memory `feedback_jmix_messages_over_spring`) — already injected as `messages` field at line 79.
6. **Both `ChatView` and `ChatDialogView` share this fragment via `ChatSurfaceMounter`** (Phase 12) — single change covers both surfaces; do NOT add surface-specific code.
7. **Programmatic chip rendering** — there is no Jmix chip primitive; use `Span` per chip with CSS class `ai-agent-chat-panel__chip` (jmix-crm `AiAttachmentCardFragmentRenderer` is a heavier card pattern; Phase 13 ships only the chip strip).

---

### Security role files (MODIFY)

**Analogs (extension targets — same files):**
- `AiAgentUserRole.java` lines 26–29 (`@EntityPolicy` on `AiConversation` + `AiMessage`)
- `AiAgentUserRowLevelRole.java` lines 23–31 (`@JpqlRowLevelPolicy`)
- `AiAgentAdminRole.java` lines 28–37 (`@EntityPolicy(... ALL)` block)

**`AiAgentUserRole.userAccess()`** — append:
```java
@EntityPolicy(entityClass = AiTaskFile.class,
        actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
```

**`AiAgentUserRowLevelRole`** — append a new method:
```java
@JpqlRowLevelPolicy(
        entityClass = AiTaskFile.class,
        where = "{E}.userUsername = :current_user_username")
void taskFile();
```

**`AiAgentAdminRole.adminAccess()`** — append:
```java
@EntityPolicy(entityClass = AiTaskFile.class, actions = EntityPolicyAction.ALL)
```

**Pitfalls:**
- `:current_user_username` is a Jmix-bound session parameter (no manual lookup needed) — see existing `conversation()` policy at AiAgentUserRowLevelRole line 24.
- Do NOT filter by `{E}.conversation.createdBy` here — that's the AiMessage pattern. AiTaskFile carries its OWN `userUsername` column per D-03 schema.
- No ViewPolicy/MenuPolicy needed in v1.1 — admin list view is deferred (CONTEXT deferred ideas).

---

### Message bundle additions (`messages_en.properties` + `messages_vi.properties`)

**Analog (existing entity caption blocks):** `messages_en.properties` lines 28–37 (AiConversation block).

**Keys to add (BOTH files; per-locale strings differ):**
```properties
# Entity captions
com.vn.agent.entity/AiTaskFile=Task File
com.vn.agent.entity/AiTaskFile.id=ID
com.vn.agent.entity/AiTaskFile.version=Version
com.vn.agent.entity/AiTaskFile.conversation=Conversation
com.vn.agent.entity/AiTaskFile.message=Message
com.vn.agent.entity/AiTaskFile.userUsername=User
com.vn.agent.entity/AiTaskFile.filename=Filename
com.vn.agent.entity/AiTaskFile.contentType=Content type
com.vn.agent.entity/AiTaskFile.sizeBytes=Size (bytes)
com.vn.agent.entity/AiTaskFile.storageRef=Storage reference
com.vn.agent.entity/AiTaskFile.createdBy=Created by
com.vn.agent.entity/AiTaskFile.createdDate=Created
com.vn.agent.entity/AiTaskFile.expiresAt=Expires

# UI strings (chip strip + upload errors)
chatView.attachments.button=Attach
chatView.attachments.upload.tooLarge=File exceeds the 100 MB limit.
chatView.attachments.upload.unsupportedType=Unsupported file type.
chatView.attachments.chip.removeAria=Remove attachment

# Tool call outcome (already exists for create/update; add bulk variant if planner picks new code)
# com.vn.agent.entity/AiToolCallOutcome.SUCCESS already covers; add bulk-specific labels only if needed.
```

**Pitfalls:**
- **Locale parity is mandatory** (CLAUDE.md "Forbidden — Single-locale messages"). Every key in `_en` MUST also land in `_vi` with translated text.
- The English bundle file is named `messages_en.properties` (not `messages.properties`) per existing repo convention — verify before adding.

---

### `application.properties` (MODIFY)

**Analog (existing keys):** lines 53–84.

**Modifications:**
```properties
# Default model swap (REQ-1) — both keys MUST flip together.
jmix.ai-agent.defaults.model=qwen/qwen3.6-35b-a3b           # was: openai/gpt-4o-mini (line 54)
spring.ai.openai.chat.options.model=qwen/qwen3.6-35b-a3b    # was: openai/gpt-4o-mini (line 63)

# Phase 13 net-new
ai-agent.task-file.ttl=PT1H                                  # ISO-8601 Duration; default 1 hour
ai-agent.task-file.max-file-size-bytes=104857600             # 100 MB; deliberately equal to RAG cap (line 81)
```

**Pitfalls:**
- Embedding model line 64 (`spring.ai.openai.embedding.options.model=qwen/qwen3-embedding-4b`) MUST stay unchanged — Phase 13 swaps chat model only.
- `spring.servlet.multipart.max-file-size=100MB` (line 79) already accommodates the cap — no servlet-multipart change needed.
- Use `Duration` (`PT1H`) for ttl — Spring binder converts; matches `AiAgentMutationProperties.idempotencyTtl` style.

---

### Test patterns

**Analog (existing test infrastructure):** `BuiltInMutationToolsAuditArgumentsTest.java` lines 49–80.

**`@SpringBootTest` shape pattern:**
```java
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsBulkSaveTest {

    @Autowired private BuiltInMutationTools builtInMutationTools;
    @Autowired private MutationToolTestContext mutationToolTestContext;
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededFixtureIds = new ArrayList<>();
    private final List<UUID> seededIntentIds  = new ArrayList<>();
    // ... @AfterEach cleanup as in audit test lines 78–80
}
```

**Test fixtures to reuse:** `MutationTestFixture` (`com.vn.agent.tools.mutation.fixture.MutationTestFixture`) — already used by every `BuiltInMutationToolsXxxTest.java` for create/update.

**TEST-16 source-scanner test (NEW pattern — no analog):**
```java
// Reflective grep over compiled-source / source-folder roots:
// Resource[] taskfileSources = resolver.getResources("classpath:com/vn/agent/taskfile/**/*.java");
// (or scan src/main/java/com/vn/agent/taskfile/**/*.java directly with Files.walk)
// For each source, read content; assert it contains NONE of:
//   "IngesterManager", "VectorStore", "RetrievalAugmentationAdvisor",
//   "TokenTextSplitter", "DocumentReader" (Tika reader)
```

**Pitfalls for tests:**
- Tests writing audit/intent rows must use `UnconstrainedDataManager.save(...)` (memory `feedback_jmix_unconstrained_for_system_writes`).
- `loadValue/loadValues` on agentstore needs `.store("agentstore")` (memory `feedback_jmix_loadvalue_store`).
- Mark tests with `@AfterEach` cleanup of seeded rows to allow concurrent test runs.
- Use the existing `StubChatModelConfiguration` — do NOT call live LLM endpoints.

---

## Shared Patterns

### Authentication / Authorization (apply to all controllers + tools)
**Source:** `MutationAuthorizationService` (already injected into `BuiltInMutationTools`)
**Apply to:** the new `bulkSaveRecords` `@Tool` method; chain order is the same as `createRecord` lines 187–195 — marker-role gate FIRST, then resolve, then per-CRUD, then per-attribute (UNION across rows for bulk).

### TEST-16 No-VectorStore Constraint (apply to ALL `taskfile/**` files)
**Source:** Phase 13 SPEC §8 + AI-SPEC §1 failure mode 5
**Apply to:** every file under `com.vn.agent.taskfile.**` AND `BuiltInMutationTools.bulkSaveRecords` body.
**Forbidden imports:** `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, `TokenTextSplitter`, `DocumentReader`, anything from `com.vn.agent.rag.**`.

### Error Handling (apply to all mutation tool methods)
**Source:** `BuiltInMutationTools.createRecord` lines 241–273 (try/catch/finally with three branches: `ToolVetoedException`, `ToolUserError`, `Throwable`).
**Apply to:** `bulkSaveRecords`. Augment with `failedRowIndex` capture inside the per-row loop. Final `argumentsJson` continues to flow through `DiffSerializer` (PII-safe sample-hash only).

### `@Transactional` Boundary Placement
**Source:** `MutationSaveExecutor.java` JavaDoc lines 8–24
**Apply to:** new `bulkSave(SaveContext)` method on `MutationSaveExecutor` (NOT on `BuiltInMutationTools.bulkSaveRecords`). The proxy crossing is what makes `@Transactional` real.

### System-Internal Writes (apply to repository, cleanup-job, markSent)
**Source:** memory `feedback_jmix_unconstrained_for_system_writes`
**Apply to:** `AiTaskFileRepository.markSent`, `AiTaskFileCleanupJob.deleteExpiredTaskFiles`, `AiTaskFileMediaResolver.resolvePending` — use `UnconstrainedDataManager`. NOT for user upload-event row insert (that runs under user policy with regular `DataManager`).

### Locale Parity (apply to all message-bundle edits)
**Source:** memory `feedback_jmix_messages_over_spring` + CLAUDE.md
**Apply to:** every key added to `messages_en.properties` MUST also land in `messages_vi.properties`. Inject `io.jmix.core.Messages` (NOT Spring `MessageSource`) into views and services for runtime lookup.

### Audit Owner-Single Invariant
**Source:** Phase 11 `MutationToolCallbackBoundaryDecorator` invariant
**Apply to:** `bulk_save_records` reuses `mutationCommitCoordinator.safeWriteAudit(...)` — exactly ONE audit row per batch (not per row). Audit `argumentsJson` carries `{count, entityName, sampleHashes, idempotencyKey}` only — NEVER raw row values (P-22).

---

## No Analog Found / Partial Analogs

| File | Role | Reason |
|------|------|--------|
| `TaskFileNoVectorStoreSourceScannerTest.java` | static-source-scan test | No prior source-scanner test in the repo. Closest precedent is the prompt-contract regression idea from Phase 9 TEST-08, but that test inspects runtime prompts, not Java source files. Planner builds reflective `Files.walk(Path.of("src/main/java/com/vn/agent/taskfile"))` + `String.contains` over `IngesterManager` / `VectorStore` / `RetrievalAugmentationAdvisor` / RAG-splitter class names. |
| Multi-file Jmix `<upload>` + `UploadHandler.toFile` | view-component | Memory `feedback_jmix_upload_receiver_deprecated` covers SINGLE-file `UploadHandler.toFile`. Multi-file API surface in jmix-flowui 2.8 is unverified. Planner MUST use Context7 (`jmix-framework/jmix-context7`) lookup "upload component multi-file UploadHandler" before implementation. Fallback: per-file uploads in a loop, or accept a controlled deprecation warning on `setReceiver(MultiFileTemporaryStorageBuffer)` if the new API is not yet exposed. |

---

## Metadata

**Analog search scope:**
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\java\com\vn\agent\**\*.java`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\resources\com\vn\agent\liquibase\agentstore-changelog\*.xml`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\main\resources\com\vn\agent\view\chat\fragment\*.xml`
- `D:\DTH\ai-agent-core\jmix-app\src\main\resources\application.properties`
- `D:\DTH\ai-agent-core\ai-agent\ai-agent\src\test\java\com\vn\agent\tools\mutation\*.java`
- `D:\DTH\jmix-crm\src\main\java\com\company\crm\ai\service\AiAttachmentMediaResolver.java`
- `D:\DTH\jmix-crm\src\main\java\com\company\crm\ai\model\AiConversationAttachment.java`

**Files scanned:** ~36 in-repo Java/XML/properties files + 2 jmix-crm reference files.

**Pattern extraction date:** 2026-05-05

## PATTERN MAPPING COMPLETE
