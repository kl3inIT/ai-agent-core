package com.vn.agent.action;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Safe planning tool that proposes UI action choices without saving data or navigating.
 */
@Component
public class ActionProposalTool {

    public static final String TOOL_NAME = "propose_action_choices";

    private final ActionProposalService actionProposalService;

    public ActionProposalTool(ActionProposalService actionProposalService) {
        this.actionProposalService = actionProposalService;
    }

    @Tool(name = TOOL_NAME, description = """
            MANDATORY WORKFLOW:
            1. Use this tool only after you have gathered enough structured data for a create/update request, or when you need the server to tell you which required fields are still missing.
            2. This tool is safe: it does not create records, update records, open views, or create drafts.
            3. If status=MISSING_FIELDS, ask the user for those fields. Do not show action choices.
            4. If status=READY, the UI will render the returned action choices. Wait for the user to choose one.
            5. This tool validates ONE record per call. Do not pass an array as values.

            INPUT CONTRACT:
            - operation: currently "create".
            - targetEntityName: exact internal entity name from list_entities, never a label.
            - values: collected writable attribute-name to value object.
            - missingFields: required fields you know are still missing, or an empty list.
            - choices: optional requested choices. Supported ids are "create-now" and "prefill-form".
            """)
    public ActionProposalResult proposeActionChoices(
            @ToolParam(description = "Operation, currently create") String operation,
            @ToolParam(description = "Exact target entity name from list_entities") String targetEntityName,
            @ToolParam(description = "Short human-readable instance summary", required = false) String instanceName,
            @ToolParam(description = "Writable attribute-name to value object. Must be an object, not an array.", required = false) Map<String, Object> values,
            @ToolParam(description = "Known missing required fields", required = false) List<String> missingFields,
            @ToolParam(description = "Requested choice ids: create-now, prefill-form", required = false) List<String> choices) {

        return actionProposalService.validate(new ActionProposal(
                null, operation, targetEntityName, instanceName,
                values == null ? Map.of() : values,
                missingFields, choices));
    }
}
