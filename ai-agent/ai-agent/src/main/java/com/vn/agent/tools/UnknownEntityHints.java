package com.vn.agent.tools;

import java.util.List;

/**
 * D-14 procedural retry hints for {@code unknown_entity} tool errors. NOT translated; these
 * are LLM-protocol English strings, not user-facing UI text.
 */
public final class UnknownEntityHints {

    public static final String CALL_ONCE = "call list_entities exactly once";

    public static final String RETRY_ON_MATCH =
            "if a name in list_entities matches your intent, retry the original tool with that exact name";

    public static final String GIVE_UP_ON_NO_MATCH =
            "if no entity in list_entities matches, tell the user no such entity exists — do not guess";

    public static final List<String> AS_LIST = List.of(CALL_ONCE, RETRY_ON_MATCH, GIVE_UP_ON_NO_MATCH);

    private UnknownEntityHints() {
        // Constants holder.
    }
}
