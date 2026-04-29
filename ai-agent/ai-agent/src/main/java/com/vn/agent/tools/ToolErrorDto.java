package com.vn.agent.tools;

import java.util.List;

/**
 * LLM-facing structured error (D-07 fail-closed). Emitted as JSON by {@code ToolResultFormatter}
 * (Plan 03). {@code expected} carries hints the LLM can use to retry — e.g. a list of valid
 * operator names, or the expected literal shape ({@code "hyphenated UUID string"}).
 *
 * <p>Never exposes stack traces or Java-internal detail. The {@code error} code is a short
 * machine-readable token (e.g. {@code "unknown_attribute"}, {@code "invalid_literal"}).</p>
 */
public record ToolErrorDto(String error, String reason, List<String> expected) {

    public ToolErrorDto(String error, String reason) {
        this(error, reason, List.of());
    }

    public ToolErrorDto {
        expected = expected == null ? List.of() : List.copyOf(expected);
    }
}
