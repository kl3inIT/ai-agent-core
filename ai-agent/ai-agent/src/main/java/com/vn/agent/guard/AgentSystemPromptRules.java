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
                    + " find_records, count_records, get_record, get_related_records, or RETRIEVAL."
                    + " Refer to actions in plain business language.",
            "- For tool arguments named entityName, use exactly one entity name shown in agent.entities"
                    + " or returned by list_entities. Do NOT infer, add, or rewrite application prefixes.",
            "- These vocabulary rules apply to text the user reads. Tool calls still use the exact"
                    + " tool schema names required by the tool definitions.",
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
     * {@code ai-agent.tools.mutation.enabled=true}; the conditional gate lives in the sibling
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
            "- When you call a mutation tool, generate a fresh UUID idempotencyKey per logical operation.",
            "- Reuse an idempotencyKey ONLY for an exact retry with identical arguments.",
            "- If you change any values after validation_failed or parameter_conversion_error,"
                    + " use a fresh idempotencyKey.",
            "- On 'access_denied' do NOT retry — surface to the user.",
            "- On 'parameter_conversion_error' re-read describe_entity attributeType and retry"
                    + " with corrected types.",
            "- On 'concurrent_modification' call get_record or find_records to verify state."
                    + " If the tool result says the commit outcome is unknown, do not retry automatically;"
                    + " ask the user before any further mutation.",
            "- On success, you may call generate_entity_detail_link to render a verify-link.",
            ""
    );

    private AgentSystemPromptRules() {
        // Constants holder — not intended for instantiation.
    }
}
