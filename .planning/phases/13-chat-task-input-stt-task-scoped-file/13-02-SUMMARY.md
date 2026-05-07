---
phase: 13-chat-task-input-stt-task-scoped-file
plan: 02
subsystem: taskfile
tags:
  - spring-ai-media
  - resolver
  - scheduled-job
  - agentstore
  - file-storage
requires:
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-CONTEXT.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-PATTERNS.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-RESEARCH.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-REVIEWS.md
  - .planning/phases/13-chat-task-input-stt-task-scoped-file/13-01-SUMMARY.md
provides:
  - "AiTaskFileRepository (loadPending / markInjected / loadExpired / deleteRow / deleteAllExpired) — agentstore CRUD seam consumed by Wave 3 chat injection and the cleanup job"
  - "AiTaskFileMediaResolver.resolvePending(conversationId) returning Resolved(media, taskFileIds) — single-turn Media injection target for Wave 3 DefaultChatServiceImpl"
  - "AiTaskFileCleanupJob hourly @Scheduled — TTL reaper activated immediately on first boot via existing @EnableScheduling on AIConfiguration"
  - "package-info.java declaring the TEST-16 no-RAG structural invariant for the source-scan test in Plan 13-05"
affects:
  - ai-agent/ai-agent
tech-stack:
  added:
    - "Spring AI 1.1.x Media (org.springframework.ai.content.Media) + 13-entry MIME allowlist verbatim ported from jmix-crm AiAttachmentMediaResolver"
  patterns:
    - "Phase 11 REQUIRES_NEW agentstore TransactionTemplate (mirror MutationIntentRepository.agentstoreRequiresNew) for system-internal stamping that survives outer rollbacks"
    - "Blob-first deletion ordering (FileStorage.removeFile before UnconstrainedDataManager.remove) to avoid orphan blobs (PATTERNS Pitfall 3)"
    - "Phase 11 hourly cron @Scheduled('0 0 * * * *') for agentstore TTL reapers (mirror MutationIntentCleanupJob)"
    - "UnconstrainedDataManager for system-internal writes per memory feedback_jmix_unconstrained_for_system_writes (cleanup job runs without a user principal)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileRepository.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/package-info.java
  modified: []
decisions:
  - "Resolver uses the regular DataManager (NOT UnconstrainedDataManager) so the user row-level policy from Plan 13-01 (userUsername = :current_user_username) filters cross-user reads structurally — diverges from PATTERNS.md sample but matches the security model and the plan's <action> spec verbatim. Repository.loadPending follows the same rationale; markInjected/loadExpired/deleteRow use UnconstrainedDataManager because they run without a reliable user principal."
  - "AiTaskFileCleanupJob omits @Transactional at the cron-method level — the repository's deleteAllExpired manages its own agentstoreRequiresNew template so a corrupt row is skipped via deleteRow's false return rather than rolling back the entire batch (PATTERNS Pitfall 3). Diverges from the snippet in PATTERNS.md (which kept @Transactional('agentstoreTransactionManager')) and matches the plan's <action> spec."
  - "deleteRow returns boolean (true = removed, false = blob-delete failed and row preserved for retry) so deleteAllExpired can count successful removals accurately. Plan said 'returns void'; promoted to boolean for the count semantic — strictly additive, no caller change."
  - "JavaDoc DO-NOT-REFERENCE block lives only in package-info.java; the inline reminder removed from AiTaskFileRepository.java JavaDoc to keep the per-plan grep gate at zero hits across all source files except package-info (REVIEWS HIGH-8 allowlist)."
metrics:
  duration: "~25 minutes"
  completed: "2026-05-06"
  tasks: 2
  files: 4
---

# Phase 13 Plan 02: Resolver + Repository + Cleanup Job Summary

Spring AI Media resolver + agentstore CRUD seam + hourly TTL reaper + the TEST-16 package-info invariant — all four files in `com.vn.agent.taskfile`, all compile clean, all forbidden-token grep gates at zero (except the allowlisted JavaDoc in `package-info.java`).

## Tasks Executed

### Task 1 — AiTaskFileRepository (agentstore CRUD seam)

**Commit:** `5837f60`

- Created `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileRepository.java` (`@Component`).
- Constructor mirrors `MutationIntentRepository` — `DataManager`, `UnconstrainedDataManager`, `FileStorageLocator`, plus a `TransactionTemplate` built from `@Qualifier("agentstoreTransactionManager") PlatformTransactionManager` with `PROPAGATION_REQUIRES_NEW`.
- Five public methods:
  - `loadPending(UUID conversationId)` — JPQL `select e from ai_AiTaskFile e where e.conversation.id = :cid and e.injectedAt is null and e.expiresAt > :now order by e.createdDate asc` (REVIEWS HIGH-1 — `injectedAt` is the stable pending marker, not `messageId`). Returns `List.of()` when `conversationId == null`. Uses regular `DataManager` so user row-level policy filters by `userUsername`.
  - `markInjected(List<UUID> ids, UUID userMessageId, OffsetDateTime injectedAt)` — wraps work in `agentstoreRequiresNew.execute(...)`. Loads each row via `unconstrainedDataManager.load(AiTaskFile.class).id(id).optional()`, sets `injectedAt` (authoritative), and best-effort sets the optional `message` FK by loading `AiMessage` with `optional()`. Tolerates the chat-memory repo having already deleted/reinserted the user message — leaves `message` null and relies on `injectedAt`. No-op when ids list is empty/null. Renamed from `markSent` per REVIEWS HIGH-1 + HIGH-14.
  - `loadExpired(OffsetDateTime now)` — `expiresAt < :now` via `UnconstrainedDataManager` (cleanup runs without a user principal).
  - `deleteRow(AiTaskFile row)` — blob-first ordering: `fileStorageLocator.getByName(...).removeFile(ref)` BEFORE `unconstrainedDataManager.remove(row)`; on blob-remove failure logs at WARN and returns `false` (row preserved for next retry, prevents orphan blobs per PATTERNS Pitfall 3). Returns `boolean` so `deleteAllExpired` can count successes — additive promotion from the plan's `void` spec.
  - `deleteAllExpired(OffsetDateTime now)` — wraps the iteration in `agentstoreRequiresNew.execute(...)`, calls `deleteRow` for each expired row, returns the count of successful removals.
- Class-level imports contain none of `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, `TokenTextSplitter`, `DocumentReader`. Logging via `org.slf4j.Logger`/`LoggerFactory`.

**Verify:** `./gradlew :ai-agent:ai-agent:compileJava` BUILD SUCCESSFUL. Greps:
- `@Qualifier("agentstoreTransactionManager")` present (line 66)
- `PROPAGATION_REQUIRES_NEW` present (line 72)
- `removeFile` present (line 179)
- Forbidden tokens count = 0
- `createdAt` count = 0 (convention is `createdDate`)
- `e.createdDate asc` present
- `public void markInjected` present
- `public void markSent` count = 0 (REVIEWS HIGH-1 — old name removed)
- `row.setInjectedAt` present

### Task 2 — AiTaskFileMediaResolver + AiTaskFileCleanupJob + package-info.java

**Commit:** `f11a5ba`

- Created `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java` (`@Component`):
  - Verbatim port of jmix-crm `AiAttachmentMediaResolver` constants (`MAX_MEDIA_NAME_LENGTH = 96`, 13-entry `SUPPORTED_MEDIA_TYPES` `Set<MimeType>` covering `Media.Format.DOC_PDF/CSV/DOC/DOCX/XLS/XLSX/HTML/TXT/MD` plus `IMAGE_PNG/JPEG/GIF/WEBP`, 14-entry `EXTENSION_MIME_TYPES` `Map` with `.htm`/`.html` and `.jpg`/`.jpeg` aliases).
  - Constructor injection of `DataManager` + `FileStorageLocator`. Resolver uses regular `DataManager` (NOT `UnconstrainedDataManager`) so the user row-level policy from Plan 13-01 filters cross-user reads structurally.
  - `public Resolved resolvePending(UUID conversationId)` — JPQL `where e.conversation.id = :cid and e.injectedAt is null and e.expiresAt > :now order by e.createdDate asc`. Returns `Resolved.empty()` for null conversationId or empty pending list. Maps each row to `Media.builder().mimeType(...).data(...).name(sanitizeMediaName(...)).build()`.
  - Private helpers `buildMedia`, `readFileBytes` (opens stream via `FileStorageLocator.getByName(ref.getStorageName()).openStream(ref)` and reads all bytes; wraps `IOException` in `IllegalStateException`), `resolveSupportedMimeType` (try declared MIME, fall back to extension lookup, reject if not in allowlist), `tryParseMimeType` (catches `IllegalArgumentException`), `mimeTypeFromExtension` (lowercase + `endsWith` over the `.ext` keys), `sanitizeMediaName` (regex `[^A-Za-z0-9\\s\\-()\\[\\]]` → `_`, then truncate to `MAX_MEDIA_NAME_LENGTH`).
  - Inner `record Resolved(List<Media> media, List<UUID> taskFileIds)` with `empty()` and `isEmpty()` helpers — pairs Media injection with the id list the caller passes back to `AiTaskFileRepository.markInjected` after the user message persists.
  - Class JavaDoc explicitly notes the D-01 single-turn-inject lock and the REVIEWS HIGH-1 invariant.

- Created `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java` (`@Component`):
  - `@Scheduled(cron = "0 0 * * * *")` on `deleteExpiredTaskFiles()` — same hourly cadence as `MutationIntentCleanupJob`.
  - Delegates to `AiTaskFileRepository.deleteAllExpired(OffsetDateTime.now())`; logs `removed > 0` at DEBUG.
  - **No** outer `@Transactional` — the repository's `deleteAllExpired` already manages its own REQUIRES_NEW agentstore tx, and putting one here would defeat the per-row tolerance pattern (a single corrupt row would roll back the entire batch).
  - `@EnableScheduling` already present on `AIConfiguration` (line 36, from Phase 11) — verified before assuming.

- Created `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/package-info.java`:
  - Declares the **STRUCTURAL INVARIANT (TEST-16)** with the explicit phrase "DO NOT REFERENCE:" followed by the forbidden-token list (`IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, `TokenTextSplitter`, `DocumentReader`, `com.vn.agent.rag.**`).
  - Notes the REVIEWS HIGH-8 allowlist policy: only this file may name the forbidden tokens, and only inside JavaDoc; the Wave 4 source-scan test (Plan 13-05) strips this block via a helper that mirrors the per-plan grep gate exclusion.

**Verify:** `./gradlew :ai-agent:ai-agent:compileJava` BUILD SUCCESSFUL. Greps:
- `import org.springframework.ai.content.Media` present (line 8)
- `e.injectedAt is null` present (line 107) — REVIEWS HIGH-1 fix
- `e.message is null` count = 0 (old predicate removed)
- `e.createdDate asc` present (line 109)
- `createdAt` count = 0 (convention is `createdDate`)
- `MAX_MEDIA_NAME_LENGTH = 96` present (line 44)
- `Media.Format.(DOC_|IMAGE_)` count = 28 (>= 13 — full MIME table including extension map repeats)
- `@Scheduled(cron = "0 0 * * * *")` present in cleanup job (line 39)
- `DO NOT REFERENCE` present in package-info.java (line 7)
- Forbidden-token grep count across `taskfile/` excluding `package-info.java`: zero hits in `AiTaskFileRepository.java`, `AiTaskFileMediaResolver.java`, `AiTaskFileCleanupJob.java`, `AiTaskFileProperties.java` (TEST-16 invariant satisfied).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Removed inline JavaDoc DO-NOT-REFERENCE block from `AiTaskFileRepository.java`**
- **Found during:** Task 1 grep verify
- **Issue:** First draft of `AiTaskFileRepository.java` JavaDoc included the line `<p>DO NOT REFERENCE: {@code IngesterManager}, {@code VectorStore}, {@code RetrievalAugmentationAdvisor}, RAG splitters — task-file pathway is structurally disjoint from KB ingestion (TEST-16; see {@code package-info.java}).` This caused the per-plan forbidden-token grep gate to count 2 hits, violating the plan's `done` criterion "Zero forbidden-token grep matches" for that file (REVIEWS HIGH-8 allowlists JavaDoc only in `package-info.java`, not in other files).
- **Fix:** Replaced with `<p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16; see this package's {@code package-info.java} for the forbidden-token list).` — defers token-naming to the allowlisted file. Forbidden-token count for `AiTaskFileRepository.java` returned to 0.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileRepository.java`
- **Commit:** `5837f60`

### Additive deviations (documented as decisions, not blockers)

- `deleteRow` returns `boolean` instead of `void` so `deleteAllExpired` can count actual successful removals — strictly additive, plan said "returns void" but the count semantic matches the plan's done criterion ("returns the count of rows whose blob+row delete BOTH succeeded").
- Resolver uses `DataManager` (NOT `UnconstrainedDataManager`) — explicit in the plan's `<action>` spec ("Use `DataManager` (NOT `UnconstrainedDataManager`) — runs in user request thread"), supersedes PATTERNS.md sample which used `UnconstrainedDataManager`. The plan rationale: row-level policy from Plan 13-01 already filters by `userUsername`.
- Cleanup job has no outer `@Transactional` — explicit in the plan's `<action>` spec ("DO NOT add `@Transactional` here — `AiTaskFileRepository.deleteAllExpired` already manages its own `agentstoreRequiresNew` transaction template internally"), supersedes PATTERNS.md sample which had `@Transactional("agentstoreTransactionManager")`.

## Authentication Gates

None.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` — BUILD SUCCESSFUL after Task 1 and again after Task 2.
- All plan-specified grep gates passed (Task 1 + Task 2).
- TEST-16 forbidden-token invariant: zero hits in every `.java` source file under `com.vn.agent.taskfile` except `package-info.java` (allowlisted JavaDoc per REVIEWS HIGH-8).
- REVIEWS HIGH-1: resolver and repository predicate is `e.injectedAt is null` (NOT `e.message is null`); repository method named `markInjected` (NOT `markSent`); 0 occurrences of the old strings.
- D-01 single-turn-inject: resolver returns `Resolved.empty()` when no rows have `injectedAt is null`; the caller (Wave 3) is responsible for calling `markInjected(...)` after the user message persists, with `injectedAt = OffsetDateTime.now()`.
- PATTERNS Pitfall 3 (blob-first ordering): `deleteRow` calls `removeFile` before `remove`; failure short-circuits with `return false`.
- Spring AI 1.1.x package: import is `org.springframework.ai.content.Media` (NOT `org.springframework.ai.model`).
- Cron expression `0 0 * * * *` matches `MutationIntentCleanupJob` cadence — hourly at minute 0.

## Self-Check

**1. Created files exist:**
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileRepository.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/package-info.java

**2. Commits exist:**
- FOUND: 5837f60 (Task 1 — AiTaskFileRepository)
- FOUND: f11a5ba (Task 2 — resolver + cleanup job + package-info)

## Self-Check: PASSED
