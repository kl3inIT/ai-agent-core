package com.vn.agent.tools;

import java.util.List;

/**
 * Unchecked exception thrown from deep inside the tool machinery (e.g. {@code FilterDslMapper},
 * {@code LiteralCoercer}, and in Plan 03 {@code BuiltInDataTools.resolveOrError}). Caught at
 * the {@code @Tool} method boundary where {@code ToolResultFormatter} converts it into a
 * {@link ToolErrorDto} JSON string returned to the LLM.
 *
 * <p>NEVER leaks as a stack trace to the LLM (D-07 fail-closed).</p>
 */
public class ToolUserError extends RuntimeException {

    private final ToolErrorDto dto;

    public ToolUserError(String errorCode, String reason) {
        this(errorCode, reason, List.of());
    }

    public ToolUserError(String errorCode, String reason, List<String> expected) {
        super(errorCode + ": " + reason);
        this.dto = new ToolErrorDto(errorCode, reason, expected);
    }

    public ToolErrorDto toDto() {
        return dto;
    }
}
