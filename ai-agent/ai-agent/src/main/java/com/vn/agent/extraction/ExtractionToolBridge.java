package com.vn.agent.extraction;

import com.vn.agent.orchestration.RunContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM-facing extraction tool. Returns a payload for the chat renderer; UI navigation is owned
 * by later controller-side code.
 */
@Component
public class ExtractionToolBridge {

    private final ExtractionService extractionService;

    public ExtractionToolBridge(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @Tool(name = "prepare_form_draft", description = """
            MANDATORY WORKFLOW:
            1. Use this tool only when the current chat turn selected a named extraction intent.
            2. Pass the exact selected intentId supplied by the system prompt or UI context.
            3. Use contextRefs only for identifiers and short references needed to bind the current turn.
            4. After this tool returns action=open_form_with_draft, tell the user to open and review the prepared form before saving.
            5. Never claim the target record was saved. This tool creates a draft only.

            INPUT CONTRACT:
            - intentId: exact named extraction intent id. It is not a display label.
            - contextRefs: optional JSON object with safe references such as conversationId, taskFileId,
              taskFileIds, or userMessage. Omit it when no references are available.
            - Do not include raw file contents, extracted field values, pasted document bodies, or PII in contextRefs.

            PARAMETER FORMATS:
            - UUID values: hyphenated UUID strings.
            - taskFileIds: array of UUID strings when more than one active file is relevant.
            - userMessage: short user-authored instruction text only, not extracted document contents.
            - Unknown or unavailable references should be omitted instead of guessed.

            ERROR HANDLING:
            - unknown_intent: the intent is not registered or not available. Do not retry with a guessed id.
            - exposure_denied: the target entity is not available to this user. Surface a permission-safe message.
            - schema_parse_failure: extraction output could not be parsed. Ask the user to try again or fill manually.
            - validation_failed: extraction output failed validation. Ask for clearer source information before retrying.
            - payload_serialization_failed: draft payload could not be serialized. Surface a generic failure.

            STRICTNESS + EXAMPLES:
            CORRECT SHAPE: prepare_form_draft("<selected-intent-id>", {"conversationId":"<uuid>"})
            CORRECT SHAPE: prepare_form_draft("<selected-intent-id>", {})
            INCORRECT: prepare_form_draft("<display-label>", ...)  (label, not intent id)
            INCORRECT: prepare_form_draft("<selected-intent-id>", {"email":"<extracted-value>"})  (raw values do not belong in contextRefs)
            INCORRECT: say the record was created or updated after this tool returns  (only a draft exists)
            """)
    public Map<String, Object> prepareFormDraft(
            @ToolParam(description = "Exact selected extraction intent id. Never use a display label.")
            String intentId,
            @ToolParam(description = "Optional safe reference object: conversationId, taskFileId/taskFileIds, or short userMessage. Never raw extracted values or file contents.",
                    required = false)
            Map<String, Object> contextRefs) {

        String effectiveIntentId = RunContext.getIntentId() == null ? intentId : RunContext.getIntentId();
        ExtractionResult result = runScopedInputAvailable()
                ? extractionService.prepare(effectiveIntentId, runScopedInput(effectiveIntentId))
                : extractionService.prepare(effectiveIntentId, copyContextRefs(contextRefs));
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "open_form_with_draft");
        payload.put("draftId", result.draftId());
        payload.put("entityName", result.entityName());
        payload.put("instanceName", result.instanceName());
        return payload;
    }

    private static boolean runScopedInputAvailable() {
        return RunContext.getUserMessage() != null
                || RunContext.getIntentId() != null
                || !RunContext.getTaskFileIds().isEmpty()
                || !RunContext.getTaskFileMedia().isEmpty();
    }

    private static ExtractionInput runScopedInput(String intentId) {
        if (RunContext.getIntentId() != null && !RunContext.markPrepareFormDraftInvoked()) {
            throw ExtractionSchemaException.validationFailure(intentId, null, 0);
        }
        return new ExtractionInput(intentId, RunContext.getConversationId(), RunContext.getUserMessage(),
                RunContext.getTaskFileIds(), RunContext.getTaskFileMedia());
    }

    private static Map<String, Object> copyContextRefs(Map<String, Object> contextRefs) {
        if (contextRefs == null || contextRefs.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(contextRefs);
    }
}
