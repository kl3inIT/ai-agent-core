package com.vn.agent.guard;

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
 *       label to use instead.</li>
 *   <li><b>PROMPT-05 / D-15:</b> surface the {@code unknown_entity} retry contract globally —
 *       call {@code list_entities} exactly once on unknown-entity errors, retry on match,
 *       give up on no match (no guessing).</li>
 * </ul>
 *
 * <p>TEST-08 (Plan 09-06) asserts the literal substring presence of these rules in the composed
 * system prompt; tests treat this constant as the ground truth. The three D-14 procedural-hint
 * substrings ({@code "call list_entities exactly once"}, the retry-on-match wording, and
 * {@code "if no entity in list_entities matches, tell the user no such entity exists — do not guess"})
 * match {@code BuiltInDataTools.UNKNOWN_ENTITY_HINTS} byte-for-byte (em dash U+2014 preserved on
 * the give-up clause) so the same wording is present in both the prompt and the tool error
 * envelope.
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
                    + " (for example 'jmixapp_Customer'). Use the human label from agent.entities instead.",
            "- In user-facing replies, do NOT mention tool names such as list_entities, describe_entity,"
                    + " find_records, count_records, get_record, get_related_records, or RETRIEVAL."
                    + " Refer to actions in plain business language.",
            "- These vocabulary rules apply to text the user reads. Tool calls themselves still use the"
                    + " canonical entity and tool names.",
            "",
            "Unknown-entity recovery (mandatory):",
            "- When a tool returns an 'unknown_entity' error, call list_entities exactly once.",
            // The next two bullets MUST start with lowercase 'if' so they match the BuiltInDataTools
            // UNKNOWN_ENTITY_HINTS strings byte-for-byte (D-14 contract; TEST-08 cross-asserts).
            // Sentence-case is sacrificed to keep the constants reconciled.
            "- if a name in list_entities matches your intent, retry the original tool with that exact name.",
            "- if no entity in list_entities matches, tell the user no such entity exists — do not guess.",
            ""
    );

    private AgentSystemPromptRules() {
        // Constants holder — not intended for instantiation.
    }
}
