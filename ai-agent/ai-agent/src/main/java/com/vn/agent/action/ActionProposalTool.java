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
    public static final String BULK_TOOL_NAME = "propose_bulk_action_choices";

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
            5. STRICTLY ONE RECORD per call. The values argument MUST be a single JSON object, NEVER an array.
               To create TWO OR MORE records of the same entity in one request, call propose_bulk_action_choices instead.

            INPUT CONTRACT:
            - operation: currently "create".
            - targetEntityName: exact internal entity name from list_entities, never a label.
            - values: collected writable attribute-name to value object for ONE record. Do NOT pass a list/array here.
            - missingFields: required fields you know are still missing, or an empty list.
            - choices: optional requested choices. Supported ids are "create-now" and "prefill-form".
            """)
    public ActionProposalResult proposeActionChoices(
            @ToolParam(description = "Operation, currently create") String operation,
            @ToolParam(description = "Exact target entity name from list_entities") String targetEntityName,
            @ToolParam(description = "Short human-readable instance summary", required = false) String instanceName,
            @ToolParam(description = "Writable attribute-name to value object for ONE record. Must be a single object, NEVER an array; for multiple records call propose_bulk_action_choices.", required = false) SingleRecordValues values,
            @ToolParam(description = "Known missing required fields", required = false) List<String> missingFields,
            @ToolParam(description = "Requested choice ids: create-now, prefill-form", required = false) List<String> choices) {

        // Defensive boundary (iteration 2): if the model reached for this single-record tool but
        // supplied multiple rows, the array-tolerant SingleRecordValues deserializer captured them
        // without throwing. Return a structured corrective result that routes the model to the bulk
        // tool, instead of letting Jackson throw a ToolExecutionException during argument binding.
        if (values != null && values.multiRecord()) {
            return ActionProposalResult.useBulkTool(
                    new ActionProposal(null, operation, targetEntityName, instanceName,
                            Map.of(), values.rows(), missingFields, List.of()));
        }

        Map<String, Object> singleRecord = values == null ? Map.of() : values.map();
        return actionProposalService.validate(new ActionProposal(
                null, operation, targetEntityName, instanceName,
                singleRecord,
                missingFields, choices));
    }

    @Tool(name = BULK_TOOL_NAME, description = """
            MANDATORY WORKFLOW (batch create of the SAME entity):
            1. Use this tool ONLY when the user asks to create TWO OR MORE records of the SAME entity in one request (for example 'create 5 orders', 'thao tác hàng loạt').
            2. For a single record, use propose_action_choices instead.
            3. This tool is safe: it does not create records, update records, open views, or create drafts. It validates the batch server-side and renders ONE confirmation choice for all rows.
            4. If status=MISSING_FIELDS, ask the user for those fields across the affected rows. Do not show the bulk choice.
            5. If status=READY, the UI renders a single 'create all' confirmation. Wait for the user to confirm before any side effect.

            INPUT CONTRACT:
            - operation: currently "create".
            - targetEntityName: exact internal entity name from list_entities, never a label. ALL rows are the same entity.
            - rows: array of objects. Each object maps writable attribute names to values for one record. Provide at least 2 rows.
            - missingFields: required fields you know are still missing across the rows, or an empty list.
            """)
    public ActionProposalResult proposeBulkActionChoices(
            @ToolParam(description = "Operation, currently create") String operation,
            @ToolParam(description = "Exact target entity name from list_entities; all rows share this entity") String targetEntityName,
            @ToolParam(description = "Short human-readable batch summary", required = false) String instanceName,
            @ToolParam(description = "Array of row objects. Each object maps writable attribute names to values for one record. At least 2 rows.")
            List<Map<String, Object>> rows,
            @ToolParam(description = "Known missing required fields across the rows", required = false) List<String> missingFields) {

        return actionProposalService.validate(new ActionProposal(
                null, operation, targetEntityName, instanceName,
                Map.of(),
                rows == null ? List.of() : rows,
                missingFields, List.of()));
    }
}
