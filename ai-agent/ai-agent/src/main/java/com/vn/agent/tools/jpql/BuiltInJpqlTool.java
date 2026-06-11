package com.vn.agent.tools.jpql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Built-in read-only JPQL analytics tool — the addon's escape hatch for queries the structured
 * {@code find_records}/{@code count_records} filter cannot express (aggregation, GROUP BY,
 * ORDER BY, projections, JOINs, date arithmetic).
 *
 * <p>Wired as a first-class built-in alongside {@code BuiltInDataTools}/{@code BuiltInLinkTools}
 * (NOT via the host {@code ToolContributor} SPI), so it is audited automatically by
 * {@code ToolCallbackAuditDecorator} and can be removed per-profile via the {@code enabledTools}
 * allowlist. Security and execution live in {@link AiJpqlQueryService}; this class is the thin
 * {@code @Tool} surface.
 */
@Component
public class BuiltInJpqlTool {

    private static final Logger log = LoggerFactory.getLogger(BuiltInJpqlTool.class);

    private final AiJpqlQueryService aiJpqlQueryService;

    public BuiltInJpqlTool(AiJpqlQueryService aiJpqlQueryService) {
        this.aiJpqlQueryService = aiJpqlQueryService;
    }

    @Tool(name = "run_jpql_query", description = """
            Execute a READ-ONLY JPQL query for analytics the structured find_records/count_records
            tools cannot express: aggregation (COUNT/SUM/AVG/MIN/MAX), GROUP BY, ORDER BY,
            projections, JOINs across relationships, and date arithmetic.

            CRITICAL REQUIREMENTS:
            1. Call describe_entities FIRST to get exact entity names, attribute names, relationships
               and enum id mappings. Without it, queries fail on wrong names.
            2. Use AS aliases for ALL SELECT fields, and pass them in selectAliases in the same order.
            3. Use entity names and attribute names (NOT table/column names) and relationship paths
               (NOT foreign keys).
            4. SELECT specific attributes/expressions — no SELECT *. Use COUNT(entity), not COUNT(*).

            ALIAS REQUIREMENT:
            CORRECT:   SELECT c.name AS clientName, COUNT(o) AS orderCount
                       FROM Client c LEFT JOIN c.orders o GROUP BY c
                       -> selectAliases = ["clientName", "orderCount"]
            INCORRECT: SELECT c.name, COUNT(o) FROM ...   (missing AS + selectAliases)

            Never use JPQL reserved words as aliases (position, user, order, group, date, count,
            sum, avg, max, min, etc.). Use SELECT co.position AS jobPosition, not AS position.

            PAGINATION:
            - Default 50 rows, max 200. If the result has hasMore=true, more rows exist; call again
              with an increased offset. Prefer server-side aggregation over fetching all rows.

            DATES (Jmix macros, recommended over CURRENT_DATE arithmetic):
            - @between(field, start, end, unit)  e.g. last 30 days: @between(o.date, now-30, now, day)
            - @today(field), @dateEquals(field, val), @dateBefore(field, val), @dateAfter(field, val)
            - Special values: now, now-N. Literal ISO dates also work: o.date >= '2024-01-01'.

            ENUM PARAMETERS (CRITICAL):
            - Use the enum id from describe_entities (enumValues[].id), NOT the constant name.
              e.g. status PAID has id 40:  WHERE i.status = :status  with parameters {"status": 40}
            - For IN filters pass the mapped id list, e.g. {"statuses": [20, 30]}.

            PARAMETERS:
            - Provide named parameters for each :placeholder. Values may be plain strings; they are
              coerced to dates/numbers/UUIDs/booleans automatically. Leave empty if none.

            SUPPORTED: aggregates (COUNT/SUM/AVG/MIN/MAX, DISTINCT), GROUP BY entity (not entity.id),
            arithmetic (+ - * /), EXTRACT(YEAR/MONTH/DAY FROM date), CONCAT/SUBSTRING/LENGTH/UPPER/
            LOWER/TRIM, LIKE with % wildcards, CASE WHEN ... THEN ... ELSE ... END, COALESCE,
            SIZE(collection), IS EMPTY / IS NOT EMPTY.

            AVOID: COUNT(CASE WHEN ...) and SUM(CASE WHEN ...) — use separate simple queries instead.

            This tool is READ-ONLY: it cannot insert, update or delete. On error it returns the raw
            query error so you can fix the query and retry.
            """)
    public JpqlQueryResult runJpqlQuery(
            @ToolParam(description = "JPQL query with AS aliases for all SELECT fields") String jpqlQuery,
            @ToolParam(required = false, description = "Named parameters for :placeholders. Leave empty if none.")
            JpqlParameters parameters,
            @ToolParam(description = "Aliases used in the SELECT clause, in order, e.g. [\"clientName\",\"orderCount\"]")
            List<String> selectAliases,
            @ToolParam(required = false, description = "Starting row index (default 0)") Integer offset,
            @ToolParam(required = false, description = "Max rows to return (default 50, max 200)") Integer limit) {
        try {
            JpqlParameters effectiveParameters = parameters != null ? parameters : JpqlParameters.empty();
            log.info("LLM Tool Call: run_jpql_query(offset={}, limit={})", offset, limit);
            return aiJpqlQueryService.executeJpqlQuery(jpqlQuery, effectiveParameters, selectAliases, offset, limit);
        } catch (Exception e) {
            // Raw passthrough so the LLM can self-correct (deliberate posture for this power-tool).
            log.warn("run_jpql_query failed: {} - {}", jpqlQuery, e.getMessage());
            return JpqlQueryResult.failed("Error executing query: " + e.getMessage());
        }
    }
}
