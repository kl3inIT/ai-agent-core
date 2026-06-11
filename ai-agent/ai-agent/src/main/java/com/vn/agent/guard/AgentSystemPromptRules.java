package com.vn.agent.guard;

import com.vn.agent.tools.UnknownEntityHints;

/**
 * Hardcoded English system-prompt rule paragraphs prepended to every chat turn, in addition to
 * the per-profile system prompt resolved from {@code AiParameters} (PROMPT-03 + D-15).
 *
 * <p><b>Why hardcoded English (not i18n):</b> these strings are model-directed instructions, not
 * user-facing UI. They flow into the LLM system prompt, never into a Vaadin view or
 * {@code Notification}. Per the precedent on
 * {@code AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT}: <em>"Kept non-i18n intentionally
 * (LO-01)"</em>. Tool-protocol English strings live in Java constants, NOT
 * {@code messages.properties} (RESEARCH Pitfall 7).
 *
 * <p>Phase 9 contract:
 * <ul>
 *   <li><b>PROMPT-03:</b> forbid host-prefixed entity names and raw tool / advisor names in
 *       user-facing reply text. Pair with {@code agent.entities} inventory so the LLM has a
 *       label to use instead, and require {@code entityName} tool arguments to copy an exact
 *       inventory name.</li>
 *   <li><b>KB grounding:</b> clarify that retrieved knowledge-base excerpts are application
 *       context filtered by authorization, while host-authored system prompts still decide how
 *       the assistant should use that context.</li>
 *   <li><b>PROMPT-05 / D-15:</b> surface the {@code unknown_entity} retry contract globally —
 *       call {@code list_entities} exactly once on unknown-entity errors, retry on match,
 *       give up on no match (no guessing).</li>
 * </ul>
 *
 * <p>TEST-08 (Plan 09-06) asserts the literal substring presence of these rules in the composed
 * system prompt; tests treat this constant as the ground truth. The three D-14 procedural-hint
 * substrings are sourced from {@link UnknownEntityHints} so the same wording is present in both
 * the prompt and the tool error envelope.
 */
public final class AgentSystemPromptRules {

    /**
     * Rule paragraph appended to {@code BaselineContextProvider.renderAsText(...)} before the
     * per-profile prompt. Verbatim — TEST-08 asserts its substrings.
     *
     * <p>The constant begins and ends with {@code "\n"} so concatenation against the baseline
     * text and the profile prompt produces clean blank-line separators without the call site
     * needing to know about the joining convention.
     */
    public static final String PROMPT_RULES = String.join("\n",
            "",
            "Vocabulary rules:",
            "- In user-facing replies, do NOT use internal entity names that look like '<prefix>_<Name>'"
                    + ". Use the human label from agent.entities instead.",
            "- In user-facing replies, do NOT mention tool names such as list_entities, describe_entity,"
                    + " describe_entities, find_records, count_records, get_record, get_related_records,"
                    + " run_jpql_query, or RETRIEVAL. Refer to actions in plain business language.",
            "- For tool arguments named entityName, use exactly one entity name shown in agent.entities"
                    + " or returned by list_entities. Do NOT infer, add, or rewrite application prefixes.",
            "- These vocabulary rules apply to text the user reads. Tool calls still use the exact"
                    + " tool schema names required by the tool definitions.",
            "",
            "Reply style:",
            "- Do NOT narrate internal steps, parallel execution, tool calls, retries, or reasoning"
                    + " before giving the answer. Just answer with the business result.",
            "- When you have a URL for the user, render it as a Markdown link with a human label,"
                    + " for example [Order list](<relative-url>). Do NOT show bare paths or raw URLs"
                    + " unless the user explicitly asks for the raw URL.",
            "- For multiple links, use a compact bullet list. Emoji are allowed when they make"
                    + " the reply easier to scan, but keep operational answers compact.",
            "- ANTI-FABRICATION: never write phrases like 'click here', 'tại đây', 'see the list',"
                    + " 'xem chi tiết', 'view full list' as plain text. ANY such phrase MUST be a"
                    + " Markdown link with a real URL obtained from generate_entity_list_link or"
                    + " generate_entity_detail_link. If the link tool was not called or returned"
                    + " unknown_entity, omit the phrase entirely instead of inventing a URL.",
            "- When the user asks to see / view / open a list of records (e.g. 'show customers',"
                    + " 'danh sách đơn hàng'), call generate_entity_list_link with the relevant"
                    + " entityName and embed the returned URL in your reply as a Markdown link.",
            "- DATA-FIDELITY: when listing or summarizing records (rows, fields, names, ids, phone"
                    + " numbers, emails, dates), every value MUST come from a tool result in the"
                    + " current turn (find_records, get_record, count_records, get_related_records,"
                    + " bulk_save_records). Never invent rows, ids, phone numbers, emails, or fill"
                    + " missing fields with plausible-looking placeholders. If find_records returned"
                    + " zero rows, say so explicitly.",
            "",
            "Analytics and aggregation (choose the right tool):",
            "- For counts-per-group, sums, averages, min/max, sorting by a computed or aggregated"
                    + " value, or top-N / ranking questions (for example 'top 5 customers by order"
                    + " count', 'revenue per month', 'orders grouped by status'), first call"
                    + " describe_entities for the involved entities, then call run_jpql_query. It"
                    + " returns the grouped/aggregated result in ONE query.",
            "- Do NOT answer aggregation, grouping, or ranking questions by looping"
                    + " get_related_records per record or by issuing many find_records / count_records"
                    + " calls — that is slow, hits row limits, and produces wrong totals.",
            "- Keep find_records / count_records for simple single-entity lookups and filters; switch"
                    + " to run_jpql_query as soon as the question needs GROUP BY, ORDER BY by an"
                    + " aggregate, JOINs across relationships, or selected column projections.",
            "",
            "Do not leak internals (applies to ALL user-facing reply text):",
            "- NEVER reveal internal tool names to the user — neither read tools nor mutation tools"
                    + " (for example create_record, update_record, add_related_record,"
                    + " remove_related_record, bulk_save_records, propose_action_choices,"
                    + " propose_bulk_action_choices). Describe what happened in plain business"
                    + " language instead.",
            "- NEVER show raw stable error codes (for example access_denied, validation_failed,"
                    + " parameter_conversion_error, idempotency_violation, concurrent_modification,"
                    + " unknown_entity, not_found). Explain the outcome in plain language the user"
                    + " can act on.",
            "- NEVER narrate step-by-step tool-call reasoning, which tools you will call, retries,"
                    + " idempotency keys, or internal workflow. Report only the business result.",
            "- If a capability appears unavailable or a tool seems missing, do NOT name the internal"
                    + " tool and do NOT tell the user a specific tool is absent from your toolset."
                    + " Explain the limitation in plain business language (for example, 'I can't"
                    + " complete that bulk action right now') without exposing internal tool names.",
            "",
            "Knowledge-base context:",
            "- Retrieved knowledge-base excerpts are application-provided context already filtered"
                    + " by authorization. Use them only according to the host application's system"
                    + " prompt and access policy.",
            "- When using knowledge-base excerpts, ground the answer in those excerpts. If the"
                    + " available excerpts do not support an answer, say the available context is"
                    + " insufficient.",
            "",
            "Unknown-entity recovery (mandatory):",
            "- When a tool returns an 'unknown_entity' error, " + UnknownEntityHints.CALL_ONCE + ".",
            "- " + UnknownEntityHints.RETRY_ON_MATCH + ".",
            "- " + UnknownEntityHints.GIVE_UP_ON_NO_MATCH + ".",
            ""
    );

    /**
     * Phase 11 MUT-10 mutation-tool rules. Appended to the system prompt ONLY when
     * {@code jmix.ai-agent.tools.mutation.enabled=true}; the conditional gate lives in the sibling
     * top-level {@code @Component AgentSystemPromptRulesComposer} (NOT a nested static class —
     * the codebase has no precedent for nested {@code @Component}s and sibling top-level matches
     * existing structure).
     *
     * <p>Hardcoded English: model-directed instructions, NOT user-facing UI. Same rationale as
     * {@link #PROMPT_RULES} (RESEARCH Pitfall 7 — tool-protocol English strings live in Java
     * constants, NOT {@code messages.properties}).
     *
     * <p><b>MUST NOT</b> reference {@code prepare_form_draft} — that is a Phase 14 forward-reference
     * tool that does not exist in v1.1. Leaking the name into the live system prompt would teach
     * the LLM to call a non-existent tool and trigger {@code unknown_tool} errors.
     */
    public static final String MUTATION_PROMPT_RULES = String.join("\n",
            "",
            "Mutation tool rules (active when mutation tools are enabled):",
            "- When you call a mutation tool, generate a fresh random UUID v4 idempotencyKey per logical operation.",
            "- UUID v4 means the first character of the third group is '4' and the first character"
                    + " of the fourth group is one of '8', '9', 'a', or 'b'. Do not fabricate patterned UUID-looking strings.",
            "- Never copy UUID-looking values from examples, previous tool calls, or prior messages for a new operation.",
            "- Reuse an idempotencyKey ONLY for an exact retry with identical arguments.",
            "- If you change any values after validation_failed or parameter_conversion_error,"
                    + " use a fresh idempotencyKey.",
            "- On 'access_denied' do NOT retry — surface to the user.",
            "- On 'parameter_conversion_error' re-read describe_entity attributeType and retry"
                    + " with corrected types.",
            "- On 'concurrent_modification' call get_record or find_records to verify state."
                    + " If the tool result says the commit outcome is unknown, do not retry automatically;"
                    + " ask the user before any further mutation.",
            "- After successful create_record or update_record, immediately call generate_entity_detail_link"
                    + " with the same entityName and returned entityId before replying to the user."
                    + " If link generation returns unknown_entity, say the record was saved but no detail link is available.",
            "- Prefer bulk_save_records when persisting 2 or more records of the SAME entity in one turn."
                    + " Use create_record or update_record only for a single row."
                    + " Always echo the row count and first 3 sample rows back to the user before invoking bulk_save_records,"
                    + " and always generate a fresh UUID v4 idempotencyKey per batch.",
            ""
    );

    private AgentSystemPromptRules() {
        // Constants holder — not intended for instantiation.
    }
}
