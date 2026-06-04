package com.vn.agent.action;

import java.util.List;

/**
 * JSON result emitted by {@code propose_action_choices}.
 */
public record ActionProposalResult(String action,
                                   String status,
                                   ActionProposal proposal,
                                   List<String> choices,
                                   List<String> missingFields,
                                   String messageKey) {

    public static final String ACTION_SHOW_CHOICES = "show_action_choices";
    /**
     * Corrective action emitted when the single-record tool received multiple records
     * (iteration 2 of {@code bulk-save-tool-not-exposed}). The UI renders no confirmation button;
     * the model reads this result and is expected to re-issue the request via
     * {@code propose_bulk_action_choices}.
     */
    public static final String ACTION_USE_BULK_TOOL = "use_bulk_action_choices";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_MISSING_FIELDS = "MISSING_FIELDS";
    public static final String STATUS_INVALID = "INVALID";
    public static final String STATUS_ACCESS_DENIED = "ACCESS_DENIED";
    /**
     * Status returned when {@code propose_action_choices} was called with multiple rows. Distinct
     * from {@link #STATUS_INVALID} so the renderer never treats it as a (single-record) proposal and
     * the model gets an unambiguous "switch to the bulk tool" signal.
     */
    public static final String STATUS_WRONG_TOOL_FOR_BULK = "WRONG_TOOL_FOR_BULK";

    static ActionProposalResult ready(ActionProposal proposal, List<String> choices) {
        return new ActionProposalResult(ACTION_SHOW_CHOICES, STATUS_READY, proposal,
                List.copyOf(choices), List.of(), null);
    }

    static ActionProposalResult missingFields(ActionProposal proposal, List<String> missingFields) {
        return new ActionProposalResult(ACTION_SHOW_CHOICES, STATUS_MISSING_FIELDS, proposal,
                List.of(), List.copyOf(missingFields), "chatView.actionChoice.missingFields");
    }

    static ActionProposalResult invalid(ActionProposal proposal, String messageKey) {
        return new ActionProposalResult(ACTION_SHOW_CHOICES, STATUS_INVALID, proposal,
                List.of(), List.of(), messageKey);
    }

    static ActionProposalResult accessDenied(ActionProposal proposal) {
        return new ActionProposalResult(ACTION_SHOW_CHOICES, STATUS_ACCESS_DENIED, proposal,
                List.of(), List.of(), "chatView.intent.permissionDenied");
    }

    /**
     * Corrective result for the single-record tool receiving multiple rows. The {@code messageKey}
     * is a model-directed instruction string (NOT a UI i18n key) so the tool result the model reads
     * names the correct path explicitly. The UI ignores this status (no confirmation button).
     */
    static ActionProposalResult useBulkTool(ActionProposal proposal) {
        return new ActionProposalResult(ACTION_USE_BULK_TOOL, STATUS_WRONG_TOOL_FOR_BULK, proposal,
                List.of(), List.of(),
                "This request contains multiple records. propose_action_choices handles ONLY one "
                        + "record. Call propose_bulk_action_choices once with the full array of rows "
                        + "in valuesList for the same target entity.");
    }
}
