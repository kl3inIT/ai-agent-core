package com.vn.agent.action;

import java.util.Set;

/**
 * Stable ids for post-clarification action choices rendered in chat.
 */
public final class ActionIntentId {

    public static final String CREATE_NOW = "create-now";
    public static final String PREFILL_FORM = "prefill-form";
    /**
     * Workstream A — batch sibling of {@link #CREATE_NOW}. Selected after a bulk action proposal
     * (≥2 rows of the same entity). The action turn it triggers attaches the mutation tool surface
     * and the {@code AgentSystemPromptRulesComposer} instructs the model to call
     * {@code bulk_save_records} exactly once with the full row set, preserving the single
     * human-confirmation-gate model (one gate for N rows).
     */
    public static final String BULK_CREATE_NOW = "bulk-create-now";

    private static final String SELECTION_PREFIX = "action:";
    private static final Set<String> KNOWN = Set.of(CREATE_NOW, PREFILL_FORM, BULK_CREATE_NOW);

    private ActionIntentId() {
    }

    public static boolean isKnown(String actionIntentId) {
        return actionIntentId != null && KNOWN.contains(actionIntentId);
    }

    public static String selectionParameter(String actionIntentId) {
        if (!isKnown(actionIntentId)) {
            throw new IllegalArgumentException("Unknown action intent id");
        }
        return SELECTION_PREFIX + actionIntentId;
    }

    public static boolean isSelectionParameter(String intentId) {
        return intentId != null && intentId.startsWith(SELECTION_PREFIX)
                && isKnown(intentId.substring(SELECTION_PREFIX.length()));
    }

    public static String fromSelectionParameter(String intentId) {
        if (!isSelectionParameter(intentId)) {
            return null;
        }
        return intentId.substring(SELECTION_PREFIX.length());
    }
}
