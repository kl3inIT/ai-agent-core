package com.vn.agent.orchestration;

import com.vn.agent.extraction.ExtractionSourceText;
import org.springframework.ai.content.Media;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-thread run-scoped carrier (D-12). Set by {@code AuditAdvisor} at the start of each chat
 * call, read by {@code ToolCallbackAuditDecorator} and by retrieval hooks so downstream audit
 * rows can be correlated with the chat-level root row. Always cleared in a {@code finally} by
 * {@code AuditAdvisor}.
 *
 * <p>Not a Spring bean — a tiny utility holder. Avoids passing parameters through every advisor
 * context map and avoids the noise of an {@code InheritableThreadLocal} (Phase 4 is sync-only
 * per D-16).</p>
 *
 * <p>Phase 07.2 (Plan 02, D-10) extends the Phase-4 shape with three new ThreadLocals — the root
 * {@link com.vn.agent.entity.AiAuditEvent} audit id (advertised by the advisor to tool/retrieval
 * hooks), and the per-run retrieval {@code topK} + {@code filtersJson} carriers used by the RAG
 * advisor to populate retrieval rows without widening the public APIs. {@link #clear()} removes
 * EVERY ThreadLocal entry to defend against leakage across pooled Vaadin request threads
 * (threat T-07.2-05).</p>
 *
 * <p>Phase 18 (Plan 02, PERF-01/D-01/D-02) adds ONE per-turn memoization slot
 * ({@link #PER_TURN_CACHE}). It holds user/role/exposure-sensitive verdicts and the readable
 * schema computed once per turn (by {@code LlmExposurePolicy}), so N tool calls in one turn resolve
 * them once. Because the value is sensitive, the slot MUST be wiped in the turn-end {@code finally}
 * (the last line of {@link #clear()}) — a verdict that outlived the turn would be reused under a
 * different user on a pooled streaming/Vaadin thread (authorization bypass, T-18-04).</p>
 *
 * <p>The accessor is ACTIVE-TURN-GATED (D-02 safe miss, review HIGH #3): it allocates and stores a
 * backing map ONLY within an active turn ({@link #get()} {@code != null}). With no active turn — a
 * foreign streaming worker thread, or after {@link #clear()} — {@link #perTurnCache()} returns a
 * shared read-only empty view (no allocation, no store) and {@link #perTurnMemoize} recomputes
 * WITHOUT storing. This guarantees nothing is left on a pooled worker thread outside a turn and
 * makes the safe miss a recompute, never a stale reuse. The cache is deliberately NOT a process-wide
 * {@code ConcurrentHashMap<runId,…>} and is NEVER propagated via reactor {@code Context} /
 * Micrometer — both would risk stale cross-user reuse (D-02; correctness &gt; hit-rate).</p>
 */
public final class RunContext {

    public static final String TOOL_CONTEXT_RUN_ID_KEY = "ai-agent.audit.run-id";
    public static final String TOOL_CONTEXT_CONVERSATION_ID_KEY = "ai-agent.audit.conversation-id";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> ROOT_AUDIT_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> RETRIEVAL_TOPK = new ThreadLocal<>();
    private static final ThreadLocal<Double> RETRIEVAL_SIMILARITY_THRESHOLD = new ThreadLocal<>();
    private static final ThreadLocal<String> RETRIEVAL_FILTERS_JSON = new ThreadLocal<>();
    private static final ThreadLocal<String> INTENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_MESSAGE = new ThreadLocal<>();
    private static final ThreadLocal<List<UUID>> TASK_FILE_IDS = new ThreadLocal<>();
    private static final ThreadLocal<List<Media>> TASK_FILE_MEDIA = new ThreadLocal<>();
    private static final ThreadLocal<List<ExtractionSourceText>> SOURCE_TEXTS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PREPARE_FORM_DRAFT_INVOKED = new ThreadLocal<>();

    /**
     * Per-turn memoization slot (Phase 18, PERF-01 / D-01 / D-02). Holds user/role/exposure-sensitive
     * verdicts + readable schema computed once per turn by {@code LlmExposurePolicy}. Allocated lazily
     * ONLY within an active turn and wiped by {@link #clear()}; never a process-wide map, never
     * reactor-Context propagated. See the class Javadoc for the active-turn-gated safe-miss contract.
     */
    private static final ThreadLocal<Map<Object, Object>> PER_TURN_CACHE = new ThreadLocal<>();

    private RunContext() { }

    /** Set the runId for the current thread. Replaces any existing value. */
    public static void set(UUID runId) { CURRENT.set(runId); }

    /** Returns the runId or {@code null} if no chat call is in progress on this thread. */
    public static UUID get() { return CURRENT.get(); }

    /** Advertise the root {@link com.vn.agent.entity.AiAuditEvent#getId()} for downstream hooks (D-10). */
    public static void setRootAuditId(UUID id) { ROOT_AUDIT_ID.set(id); }

    /** Root audit row id for the in-progress chat turn, or {@code null} outside a run. */
    public static UUID getRootAuditId() { return ROOT_AUDIT_ID.get(); }

    /** Advertise the conversation id for downstream child audit rows. */
    public static void setConversationId(UUID id) { CONVERSATION_ID.set(id); }

    /** Conversation id for the in-progress chat turn, or {@code null} outside a run. */
    public static UUID getConversationId() { return CONVERSATION_ID.get(); }

    /** Per-run retrieval {@code topK} carrier (populated by RAG advisor for retrieval audit row). */
    public static void setRetrievalTopK(Integer topK) { RETRIEVAL_TOPK.set(topK); }

    /** Current retrieval {@code topK} or {@code null} when no retrieval is in flight. */
    public static Integer getRetrievalTopK() { return RETRIEVAL_TOPK.get(); }

    /** Per-run retrieval similarity threshold carrier. */
    public static void setRetrievalSimilarityThreshold(Double threshold) {
        RETRIEVAL_SIMILARITY_THRESHOLD.set(threshold);
    }

    /** Current retrieval similarity threshold or {@code null} when no retrieval is in flight. */
    public static Double getRetrievalSimilarityThreshold() { return RETRIEVAL_SIMILARITY_THRESHOLD.get(); }

    /** Per-run retrieval filters-JSON carrier (Filter.Expression#toString only — T-07.2-03). */
    public static void setRetrievalFiltersJson(String s) { RETRIEVAL_FILTERS_JSON.set(s); }

    /** Current retrieval filters-JSON or {@code null} when no retrieval is in flight. */
    public static String getRetrievalFiltersJson() { return RETRIEVAL_FILTERS_JSON.get(); }

    public static void setExtractionTurn(String intentId,
                                         UUID conversationId,
                                         String userMessage,
                                         List<UUID> taskFileIds,
                                         List<Media> taskFileMedia) {
        setExtractionTurn(intentId, conversationId, userMessage, taskFileIds, taskFileMedia, List.of());
    }

    public static void setExtractionTurn(String intentId,
                                         UUID conversationId,
                                         String userMessage,
                                         List<UUID> taskFileIds,
                                         List<Media> taskFileMedia,
                                         List<ExtractionSourceText> sourceTexts) {
        INTENT_ID.set(intentId);
        if (conversationId != null) {
            setConversationId(conversationId);
        }
        USER_MESSAGE.set(userMessage);
        TASK_FILE_IDS.set(taskFileIds == null ? List.of() : List.copyOf(taskFileIds));
        TASK_FILE_MEDIA.set(taskFileMedia == null ? List.of() : List.copyOf(taskFileMedia));
        SOURCE_TEXTS.set(sourceTexts == null ? List.of() : List.copyOf(sourceTexts));
        PREPARE_FORM_DRAFT_INVOKED.set(false);
    }

    public static String getIntentId() { return INTENT_ID.get(); }

    public static String getUserMessage() { return USER_MESSAGE.get(); }

    public static List<UUID> getTaskFileIds() {
        List<UUID> taskFileIds = TASK_FILE_IDS.get();
        return taskFileIds == null ? List.of() : taskFileIds;
    }

    public static List<Media> getTaskFileMedia() {
        List<Media> taskFileMedia = TASK_FILE_MEDIA.get();
        return taskFileMedia == null ? List.of() : taskFileMedia;
    }

    public static List<ExtractionSourceText> getSourceTexts() {
        List<ExtractionSourceText> sourceTexts = SOURCE_TEXTS.get();
        return sourceTexts == null ? List.of() : sourceTexts;
    }

    public static boolean markPrepareFormDraftInvoked() {
        if (Boolean.TRUE.equals(PREPARE_FORM_DRAFT_INVOKED.get())) {
            return false;
        }
        PREPARE_FORM_DRAFT_INVOKED.set(true);
        return true;
    }

    /**
     * Active-turn-gated per-turn cache accessor (PERF-01 / D-01 / D-02, review HIGH #3).
     *
     * <p>Within an active turn ({@link #get()} {@code != null}) it lazily creates and stores a
     * {@link HashMap} for this thread and returns it. With NO active turn (foreign streaming worker
     * thread, or after {@link #clear()}) it returns a shared read-only empty view
     * ({@link Collections#emptyMap()}) WITHOUT allocating or storing — nothing is left on a pooled
     * worker thread outside a turn, and the post-{@code clear()} slot stays observably null.</p>
     *
     * <p>Callers should prefer {@link #perTurnMemoize(Object, Supplier)} so the no-active-turn path
     * transparently recomputes-without-storing instead of silently treating the shared empty map as
     * a cache.</p>
     */
    public static Map<Object, Object> perTurnCache() {
        if (CURRENT.get() == null) {
            // No active turn / post-clear: safe miss. Do NOT allocate or store on a pooled thread.
            return Collections.emptyMap();
        }
        Map<Object, Object> cache = PER_TURN_CACHE.get();
        if (cache == null) {
            cache = new HashMap<>();
            PER_TURN_CACHE.set(cache);
        }
        return cache;
    }

    /**
     * Active-turn-gated read-through memoization (PERF-01 / D-02 safe miss).
     *
     * <p>Within an active turn, returns the cached value for {@code key}, computing and storing it
     * via {@code compute} exactly once. With NO active turn it returns {@code compute.get()}
     * directly — recompute, never stored (the D-02 safe miss: never stale reuse, never a map left
     * behind on a pooled worker thread). The per-turn map holds user/role/exposure-sensitive
     * verdicts, so it is wiped each turn by {@link #clear()} (no cross-turn / cross-user bleed).</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> T perTurnMemoize(Object key, Supplier<T> compute) {
        if (CURRENT.get() == null) {
            // No active turn: recompute WITHOUT storing (safe miss).
            return compute.get();
        }
        return (T) perTurnCache().computeIfAbsent(key, k -> compute.get());
    }

    /**
     * Non-initializing inspection accessor for tests (review HIGH #3). Returns the RAW
     * {@link #PER_TURN_CACHE} slot — which may be {@code null} — WITHOUT any lazy-init, so an
     * "empty after {@code clear()}" assertion is MEANINGFUL: it inspects the actual stored slot
     * rather than a freshly minted empty map masking the wipe. Not for production use.
     */
    public static Map<Object, Object> perTurnCacheSnapshotForTest() {
        return PER_TURN_CACHE.get();
    }

    /**
     * Remove EVERY thread-local entry — MUST be called in a {@code finally} block.
     *
     * <p>Leak guard: Vaadin UI threads are pooled across requests; a stale {@link #ROOT_AUDIT_ID}
     * could orphan tool/retrieval rows under a different user's turn (T-07.2-05). The per-turn cache
     * ({@link #PER_TURN_CACHE}) holds user/role/exposure-sensitive verdicts, so its removal here is
     * the non-negotiable defense against cross-turn / cross-user reuse (T-18-04 / D-01).</p>
     */
    public static void clear() {
        CURRENT.remove();
        ROOT_AUDIT_ID.remove();
        CONVERSATION_ID.remove();
        RETRIEVAL_TOPK.remove();
        RETRIEVAL_SIMILARITY_THRESHOLD.remove();
        RETRIEVAL_FILTERS_JSON.remove();
        INTENT_ID.remove();
        USER_MESSAGE.remove();
        TASK_FILE_IDS.remove();
        TASK_FILE_MEDIA.remove();
        SOURCE_TEXTS.remove();
        PREPARE_FORM_DRAFT_INVOKED.remove();
        PER_TURN_CACHE.remove();
    }
}
