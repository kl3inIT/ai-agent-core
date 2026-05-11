package com.vn.agent.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-validated proposal for side-effecting chat actions.
 */
public record ActionProposal(String proposalId,
                             String operation,
                             String targetEntityName,
                             String instanceName,
                             Map<String, Object> values,
                             List<String> missingFields,
                             List<String> choices) {

    public ActionProposal {
        proposalId = (proposalId == null || proposalId.isBlank())
                ? UUID.randomUUID().toString()
                : proposalId;
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
