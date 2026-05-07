package com.vn.agent.taskfile;

/**
 * Argument-JSON keys for the {@code task_file_budget_exceeded} audit row written by
 * {@link AiTaskFileMediaResolver}. Shared with {@code BudgetCapTest} so writer
 * and reader cannot drift. SPEC REQ-3 / §3 Acceptance.
 */
public final class BudgetExceededAuditKeys {
    public static final String CONVERSATION_ID         = "conversationId";
    public static final String TOTAL_ROWS              = "totalRows";
    public static final String TOTAL_BYTES             = "totalBytes";
    public static final String KEPT_ROWS               = "keptRows";
    public static final String KEPT_BYTES              = "keptBytes";
    public static final String DROPPED_ROWS            = "droppedRows";
    public static final String DROPPED_BYTES           = "droppedBytes";
    public static final String PER_TURN_MAX_FILES      = "perTurnMaxFiles";
    public static final String PER_TURN_MAX_TOTAL_BYTES = "perTurnMaxTotalBytes";

    private BudgetExceededAuditKeys() { /* no instances */ }
}
