package com.vn.agent.tools.jpql;

import java.util.List;
import java.util.Map;

/**
 * Wrapper record for the named JPQL parameters of a single {@code run_jpql_query} call.
 *
 * <p>Declared as a concrete record (not a bare {@code Map}/{@code List}) because Spring AI 1.x
 * mis-binds {@code Object}-typed tool parameters; a record gives the tool-schema generator a
 * stable shape to project (see addon memory: "No Object-typed @ToolParam").
 */
public record JpqlParameters(
        List<JpqlParameter> parameters
) {

    public static JpqlParameters empty() {
        return new JpqlParameters(List.of());
    }

    public static JpqlParameters fromMap(Map<String, Object> parameterMap) {
        if (parameterMap == null || parameterMap.isEmpty()) {
            return empty();
        }
        List<JpqlParameter> params = parameterMap.entrySet().stream()
                .map(entry -> new JpqlParameter(entry.getKey(), entry.getValue()))
                .toList();
        return new JpqlParameters(params);
    }

    /** Null-safe view of the parameter list. */
    public List<JpqlParameter> safeParameters() {
        return parameters == null ? List.of() : parameters;
    }
}
