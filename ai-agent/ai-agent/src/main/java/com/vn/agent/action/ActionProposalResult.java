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
    public static final String STATUS_READY = "READY";
    public static final String STATUS_MISSING_FIELDS = "MISSING_FIELDS";
    public static final String STATUS_INVALID = "INVALID";
    public static final String STATUS_ACCESS_DENIED = "ACCESS_DENIED";

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
}
