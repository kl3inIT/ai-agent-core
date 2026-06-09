package com.vn.agent.taskfile;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.orchestration.AiUiSettingsResolver;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.FluentLoader;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 18 Plan 04 (PERF-04) — the proxy-first call-count test for the task-file encode path.
 *
 * <p><b>Pure JUnit 5 + Mockito; NO {@code @SpringBootTest}.</b> The ai-agent module Spring
 * context boot is blocked by the pre-existing Phase 11/13 {@code agentstoreEntityManagerFactory}
 * regression (documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md} and
 * reproduced today by the sibling {@code @SpringBootTest PerTurnMediaInjectionTest}). This proxy
 * therefore counts at the INJECTABLE constructor seams of a real
 * {@link AiTaskFileMediaResolver} instance, exactly as the plan's HIGH #5 review correction
 * mandates (the private {@code readFileBytes()} / {@code extractDocumentText()} / inline
 * {@code new TikaDocumentReader(...)} symbols have no Mockito seam and are NOT counted directly).
 *
 * <h2>Test seam (review HIGH #5)</h2>
 * <ul>
 *   <li><b>Encode proxy:</b> a hand-written counting {@link FileStorage} (returned by a mocked
 *       {@link FileStorageLocator#getByName(String)} — the actual accessor at
 *       {@code AiTaskFileMediaResolver:358}) whose {@link FileStorage#openStream(FileRef)} bumps an
 *       {@link AtomicInteger} over real small test bytes. {@code FileStorage.openStream} gates
 *       {@code readFileBytes} (:357-359), which gates BOTH the image-bytes {@code .data(...)} (:305)
 *       and the Tika {@code extractDocumentText} (:328) paths, so the {@code openStream} count is
 *       the legitimate per-file encode proxy — the inline {@code TikaDocumentReader} runs once per
 *       {@code FileStorage} read and need NOT be counted separately.</li>
 *   <li><b>Settings-read proxy:</b> a Mockito-mock {@link AiUiSettingsResolver} so the
 *       {@code resolveTaskFile*} accessor call counts within one turn are {@code verify}-able.</li>
 *   <li>A constrained {@link DataManager} mock returning a fixed set of {@link AiTaskFile} rows for
 *       one {@code conversationId} (the resolver loads through {@code DataManager.load(...).query(...)},
 *       never {@code UnconstrainedDataManager} — that constrained-load invariant is asserted below).</li>
 * </ul>
 *
 * <h2>Branch taken: REGRESSION-LOCK (no new cache)</h2>
 * A single {@link AiTaskFileMediaResolver#resolveActive(UUID)} call is the REAL production call
 * shape — one turn = one {@code resolveActive} (Phase 13.1 Plan 03; {@code DefaultChatServiceImpl:350,617}).
 * Within that one turn the proxy observes {@code FileStorage.openStream} fire EXACTLY ONCE per kept
 * {@code (conversationId, taskFileId)} — there is no in-turn re-encode of the same file. Per the
 * locked D-10 scope discipline (RESEARCH Open Q2 / Pitfall 5), this test is therefore the
 * REGRESSION LOCK on the already-once-per-turn behavior and adds NO {@code Media} cache (a cache
 * whose proxy never serves a second in-turn hit would be redundant). No production change is made to
 * {@code AiTaskFileMediaResolver} / {@code AiUiSettingsResolver} beyond a Javadoc {@code [NOTE]}
 * recording the regression lock.
 *
 * <p><b>Settings-singleton finding (recorded for SUMMARY, NOT a cache trigger):</b> the proxy also
 * shows {@code resolveActive} reads the {@code AiUiSettings} singleton THREE times per turn
 * (one each via {@code resolveTaskFileTtlSeconds} / {@code resolveTaskFilePerTurnMaxFiles} /
 * {@code resolveTaskFilePerTurnMaxTotalBytes}). That is a per-turn-redundant SETTINGS read, not a
 * redundant Media ENCODE; the locked PERF-04 scope discipline only authorizes a cache for a proven
 * second in-turn ENCODE, so this test records the count honestly without adding a settings memo.
 */
@Tag("unit")
class TaskFileMediaEncodeOncePerTurnTest {

    private DataManager dataManager;
    private FileStorageLocator fileStorageLocator;
    private AiTaskFileProperties taskFileProperties;
    private AuditWriter auditWriter;
    private CurrentAuthentication currentAuthentication;
    private AiUiSettingsResolver aiUiSettingsResolver;

    private CountingFileStorage fileStorage;
    private AiTaskFileMediaResolver resolver;

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class);
        fileStorageLocator = mock(FileStorageLocator.class);
        taskFileProperties = mock(AiTaskFileProperties.class);
        auditWriter = mock(AuditWriter.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        aiUiSettingsResolver = mock(AiUiSettingsResolver.class);

        // Counting FileStorage is returned for ANY storage name — the resolver looks it up via
        // FileStorageLocator.getByName(fileRef.getStorageName()) inside readFileBytes (:358).
        fileStorage = new CountingFileStorage();
        when(fileStorageLocator.getByName(anyString())).thenReturn(fileStorage);

        // currentAuthentication is only touched on the budget-exceeded audit path, which the
        // generous caps below never trigger — so no getUser() stub is needed.

        // Generous caps so a small set of small files never trips the budget eviction path.
        when(aiUiSettingsResolver.resolveTaskFileTtlSeconds()).thenReturn(-1L); // disable TTL → no :now param
        when(aiUiSettingsResolver.resolveTaskFilePerTurnMaxFiles()).thenReturn(10);
        when(aiUiSettingsResolver.resolveTaskFilePerTurnMaxTotalBytes()).thenReturn(52_428_800L);

        resolver = new AiTaskFileMediaResolver(
                dataManager,
                fileStorageLocator,
                taskFileProperties,
                auditWriter,
                currentAuthentication,
                aiUiSettingsResolver);
    }

    /**
     * REGRESSION LOCK — one turn (= one {@code resolveActive}) over a single document task file
     * reads the blob through {@code FileStorage.openStream} EXACTLY ONCE. There is no in-turn
     * re-encode of the same {@code (conversationId, taskFileId)}.
     */
    @Test
    void singleDocumentEncodesExactlyOncePerTurn() {
        UUID conversationId = UUID.randomUUID();
        UUID taskFileId = UUID.randomUUID();
        byte[] bytes = "phase 18 perf-04 regression lock sample".getBytes(StandardCharsets.UTF_8);

        AiTaskFile row = docRow(taskFileId, "sample.txt", "text/plain", bytes);
        stubLoad(conversationId, List.of(row));

        AiTaskFileMediaResolver.Resolved resolved = resolver.resolveActive(conversationId);

        assertThat(resolved.documentTexts())
                .as("the single text task file is Tika-extracted to one DocumentText")
                .hasSize(1);
        assertThat(fileStorage.openStreamCount())
                .as("PERF-04: FileStorage.openStream (the per-file encode proxy) must fire EXACTLY "
                        + "ONCE per (conversationId, taskFileId) per turn — already once-per-turn, "
                        + "regression-locked (no cache added)")
                .isEqualTo(1);
        assertThat(fileStorage.distinctRefsRead())
                .as("the one read targets exactly the one seeded task-file blob")
                .isEqualTo(1);
    }

    /**
     * REGRESSION LOCK — image rows go through {@code buildMedia} and document rows through
     * {@code extractDocumentText}; both funnel through {@code readFileBytes → FileStorage.openStream}
     * exactly once each. Three distinct files in one turn = three reads, ONE per file, never two for
     * the same file.
     */
    @Test
    void mixedImageAndDocumentRowsEachEncodeExactlyOncePerTurn() {
        UUID conversationId = UUID.randomUUID();
        AiTaskFile doc = docRow(UUID.randomUUID(), "notes.txt", "text/plain",
                "doc bytes".getBytes(StandardCharsets.UTF_8));
        AiTaskFile png = imageRow(UUID.randomUUID(), "shot.png", "image/png",
                pngBytes());
        AiTaskFile csv = docRow(UUID.randomUUID(), "data.csv", "text/csv",
                "a,b\n1,2".getBytes(StandardCharsets.UTF_8));
        stubLoad(conversationId, List.of(doc, png, csv));

        AiTaskFileMediaResolver.Resolved resolved = resolver.resolveActive(conversationId);

        assertThat(resolved.media())
                .as("the PNG row is the only Media (image_url) entry")
                .hasSize(1);
        assertThat(resolved.documentTexts())
                .as("the txt + csv rows are Tika-extracted DocumentText entries")
                .hasSize(2);
        assertThat(fileStorage.openStreamCount())
                .as("PERF-04: three distinct files → exactly three encode reads, ONE per file")
                .isEqualTo(3);
        assertThat(fileStorage.maxReadsForAnySingleRef())
                .as("PERF-04: NO single (conversationId, taskFileId) blob is read more than once "
                        + "within a turn — the in-turn re-encode a memo would dedupe never happens")
                .isEqualTo(1);
    }

    /**
     * Settings-singleton call-count proxy. The plan (review suggestion) notes that counting
     * {@code resolveTaskFile*} accessor calls is the in-scope settings proxy. This turn reads the
     * {@code AiUiSettings} singleton THREE times (TTL + max-files + max-total-bytes), each its own
     * {@code UnconstrainedDataManager.load(AiUiSettings.class)} behind the resolver. The assertion
     * documents that exact shape: it is the per-turn settings-read count, recorded honestly in the
     * SUMMARY. It is a redundant SETTINGS read, not a redundant Media ENCODE, so per the locked
     * PERF-04 scope discipline it does NOT trigger a cache in this plan.
     */
    @Test
    void settingsSingletonAccessorsCalledExactlyOncePerKnobPerTurn() {
        UUID conversationId = UUID.randomUUID();
        AiTaskFile row = docRow(UUID.randomUUID(), "sample.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8));
        stubLoad(conversationId, List.of(row));

        resolver.resolveActive(conversationId);

        // Each of the three task-file knob accessors is invoked exactly once per turn. Because each
        // accessor performs its OWN loadSingleton() in AiUiSettingsResolver, the AiUiSettings
        // singleton is read 3x per turn — recorded for the SUMMARY (settings read, not encode).
        verify(aiUiSettingsResolver, times(1)).resolveTaskFileTtlSeconds();
        verify(aiUiSettingsResolver, times(1)).resolveTaskFilePerTurnMaxFiles();
        verify(aiUiSettingsResolver, times(1)).resolveTaskFilePerTurnMaxTotalBytes();
    }

    /**
     * Constrained-load invariant (T-18-12). The {@code AiTaskFile} row load MUST go through the
     * injected constrained {@link DataManager}; {@code UnconstrainedDataManager} is never used on
     * this LLM-steerable row-load path. Verifying the {@code DataManager.load(AiTaskFile.class)}
     * interaction pins that the row-level user policy stays in force.
     */
    @Test
    void rowLoadGoesThroughConstrainedDataManager() {
        UUID conversationId = UUID.randomUUID();
        AiTaskFile row = docRow(UUID.randomUUID(), "sample.txt", "text/plain",
                "y".getBytes(StandardCharsets.UTF_8));
        stubLoad(conversationId, List.of(row));

        resolver.resolveActive(conversationId);

        verify(dataManager, times(1)).load(AiTaskFile.class);
    }

    // ---------------------------------------------------------------------
    // Fixtures / helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubLoad(UUID conversationId, List<AiTaskFile> rows) {
        FluentLoader<AiTaskFile> loader = mock(FluentLoader.class);
        FluentLoader.ByQuery<AiTaskFile> byQuery =
                mock(FluentLoader.ByQuery.class, Mockito.RETURNS_DEEP_STUBS);
        when(dataManager.load(AiTaskFile.class)).thenReturn(loader);
        when(loader.query(anyString())).thenReturn(byQuery);
        // .parameter(...).parameter(...) returns deep-stub self; .list() returns the seeded rows.
        when(byQuery.parameter(anyString(), any())).thenReturn(byQuery);
        when(byQuery.list()).thenReturn(rows);
    }

    private AiTaskFile docRow(UUID id, String filename, String contentType, byte[] bytes) {
        return taskFileRow(id, filename, contentType, bytes);
    }

    private AiTaskFile imageRow(UUID id, String filename, String contentType, byte[] bytes) {
        return taskFileRow(id, filename, contentType, bytes);
    }

    private AiTaskFile taskFileRow(UUID id, String filename, String contentType, byte[] bytes) {
        AiTaskFile row = mock(AiTaskFile.class);
        FileRef ref = new FileRef("fs", id.toString() + "/" + filename, filename);
        // Register the bytes against this exact FileRef so the counting storage serves real content.
        fileStorage.register(ref, bytes);
        when(row.getId()).thenReturn(id);
        when(row.getFilename()).thenReturn(filename);
        when(row.getContentType()).thenReturn(contentType);
        when(row.getSizeBytes()).thenReturn((long) bytes.length);
        when(row.getStorageRef()).thenReturn(ref);
        return row;
    }

    /** Minimal valid 1x1 PNG so Spring AI's image MimeType branch (buildMedia) is exercised. */
    private static byte[] pngBytes() {
        // 1x1 transparent PNG.
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    }

    /**
     * Hand-written counting {@link FileStorage}. Only {@link #openStream(FileRef)} is exercised by
     * the resolver's encode path; every other method throws {@link UnsupportedOperationException}
     * so any unexpected interaction surfaces loudly. {@link #openStream(FileRef)} bumps a global
     * counter and a per-{@link FileRef} counter so the test can assert BOTH the total encode count
     * and that no single blob is read twice within a turn.
     */
    private static final class CountingFileStorage implements FileStorage {

        private final AtomicInteger openStreamCount = new AtomicInteger();
        private final ConcurrentHashMap<FileRef, byte[]> contentByRef = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<FileRef, AtomicInteger> readsByRef = new ConcurrentHashMap<>();

        void register(FileRef ref, byte[] bytes) {
            contentByRef.put(ref, bytes);
        }

        int openStreamCount() {
            return openStreamCount.get();
        }

        int distinctRefsRead() {
            return readsByRef.size();
        }

        int maxReadsForAnySingleRef() {
            return readsByRef.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
        }

        @Override
        public String getStorageName() {
            return "fs";
        }

        @Override
        public InputStream openStream(FileRef reference) {
            openStreamCount.incrementAndGet();
            readsByRef.computeIfAbsent(reference, k -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = contentByRef.get(reference);
            if (bytes == null) {
                throw new IllegalStateException("no test content registered for ref " + reference);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public FileRef saveStream(String fileName, InputStream inputStream,
                                  java.util.Map<String, Object> parameters) {
            throw new UnsupportedOperationException("saveStream not used by the encode path");
        }

        @Override
        public void removeFile(FileRef reference) {
            throw new UnsupportedOperationException("removeFile not used by the encode path");
        }

        @Override
        public boolean fileExists(FileRef reference) {
            throw new UnsupportedOperationException("fileExists not used by the encode path");
        }
    }
}
