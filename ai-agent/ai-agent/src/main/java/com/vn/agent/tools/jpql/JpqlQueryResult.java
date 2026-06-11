package com.vn.agent.tools.jpql;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@code run_jpql_query} execution, serialized back to the LLM as JSON.
 *
 * <p>On failure {@code errorMessage} carries the (mostly raw) JPQL/EclipseLink error so the model
 * can self-correct its query — a deliberate posture decision for this read-only analytics
 * power-tool (unlike the structured tools, which return sanitized stable error codes).
 */
public record JpqlQueryResult(
        boolean success,
        List<Map<String, Object>> data,
        int rowCount,
        boolean hasMore,
        int offset,
        int limit,
        String errorMessage
) {

    public static JpqlQueryResult success(List<Map<String, Object>> data, boolean hasMore, int offset, int limit) {
        return new JpqlQueryResult(true, data, data.size(), hasMore, offset, limit, null);
    }

    public static JpqlQueryResult failed(String errorMessage) {
        return new JpqlQueryResult(false, List.of(), 0, false, 0, 0, errorMessage);
    }
}
