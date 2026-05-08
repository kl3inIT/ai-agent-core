package com.vn.agent.orchestration;

import org.springframework.ai.content.Media;

import java.util.List;
import java.util.UUID;

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
 * ALL four ThreadLocals to defend against leakage across pooled Vaadin request threads
 * (threat T-07.2-05).</p>
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
    private static final ThreadLocal<Boolean> PREPARE_FORM_DRAFT_INVOKED = new ThreadLocal<>();

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
        INTENT_ID.set(intentId);
        if (conversationId != null) {
            setConversationId(conversationId);
        }
        USER_MESSAGE.set(userMessage);
        TASK_FILE_IDS.set(taskFileIds == null ? List.of() : List.copyOf(taskFileIds));
        TASK_FILE_MEDIA.set(taskFileMedia == null ? List.of() : List.copyOf(taskFileMedia));
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

    public static boolean markPrepareFormDraftInvoked() {
        if (Boolean.TRUE.equals(PREPARE_FORM_DRAFT_INVOKED.get())) {
            return false;
        }
        PREPARE_FORM_DRAFT_INVOKED.set(true);
        return true;
    }

    /**
     * Remove ALL four thread-local entries — MUST be called in a {@code finally} block.
     *
     * <p>Leak guard: Vaadin UI threads are pooled across requests; a stale {@link #ROOT_AUDIT_ID}
     * could orphan tool/retrieval rows under a different user's turn (T-07.2-05).</p>
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
        PREPARE_FORM_DRAFT_INVOKED.remove();
    }
}
