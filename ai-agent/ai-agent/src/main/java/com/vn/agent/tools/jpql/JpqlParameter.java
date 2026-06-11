package com.vn.agent.tools.jpql;

/**
 * A single named JPQL parameter supplied by the LLM for the {@code run_jpql_query} tool.
 *
 * <p>Ported from the jmix-crm reference assistant. The {@code parameterValue} is intentionally
 * {@link Object}-typed so the LLM can pass raw strings/numbers/booleans; {@link AiJpqlParameterConverter}
 * coerces them to JPA-compatible types at execution time.
 */
public record JpqlParameter(
        String parameterName,
        Object parameterValue
) {
}
