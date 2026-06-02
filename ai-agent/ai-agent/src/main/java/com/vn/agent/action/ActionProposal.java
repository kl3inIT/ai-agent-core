package com.vn.agent.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-validated proposal for side-effecting chat actions.
 *
 * <p>Two shapes (Workstream A):
 * <ul>
 *   <li><b>Single-record</b> — {@code values} carries one row's attribute map; {@code valuesList}
 *       is empty. Drives the {@code create-now} / {@code prefill-form} choices.</li>
 *   <li><b>Bulk</b> — {@code valuesList} carries ≥2 rows of the same entity; {@code values} is
 *       empty. Drives the {@code bulk-create-now} choice (a single confirmation gate for N rows).</li>
 * </ul>
 * The two are mutually exclusive: {@link #isBulk()} is true exactly when {@code valuesList} is
 * non-empty.</p>
 */
public record ActionProposal(String proposalId,
                             String operation,
                             String targetEntityName,
                             String instanceName,
                             Map<String, Object> values,
                             List<Map<String, Object>> valuesList,
                             List<String> missingFields,
                             List<String> choices) {

    public ActionProposal {
        proposalId = (proposalId == null || proposalId.isBlank())
                ? UUID.randomUUID().toString()
                : proposalId;
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
        valuesList = valuesList == null
                ? List.of()
                : valuesList.stream()
                        .map(row -> row == null
                                ? Map.<String, Object>of()
                                : Map.copyOf(new LinkedHashMap<>(row)))
                        .toList();
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /**
     * Backward-compatible single-record constructor (callers that predate Workstream A). Produces
     * a proposal with an empty {@code valuesList}.
     */
    public ActionProposal(String proposalId,
                          String operation,
                          String targetEntityName,
                          String instanceName,
                          Map<String, Object> values,
                          List<String> missingFields,
                          List<String> choices) {
        this(proposalId, operation, targetEntityName, instanceName, values, List.of(),
                missingFields, choices);
    }

    public boolean isBulk() {
        return !valuesList.isEmpty();
    }
}
