package com.vn.agent.tools;

/**
 * Hard limits on tool output size (TOOL-06, D-14).
 *
 * <ul>
 *   <li>{@link #DEFAULT_LIMIT} = {@value #DEFAULT_LIMIT} — used when the LLM does not supply a limit argument.</li>
 *   <li>{@link #MAX_LIMIT}     = {@value #MAX_LIMIT} — absolute ceiling; LLM-supplied limits are clamped to {@code [1, MAX_LIMIT]}.</li>
 *   <li>{@link #DEFAULT_MAX_FILTER_DEPTH} = {@value #DEFAULT_MAX_FILTER_DEPTH} — default attribute-path depth cap (D-08).</li>
 * </ul>
 *
 * <p>The LLM CANNOT override these constants. Plan 04 unit tests pin every value.</p>
 */
public final class ToolLimits {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_MAX_FILTER_DEPTH = 3;

    private ToolLimits() {
    }

    /**
     * Clamp an LLM-supplied limit to {@code [1, MAX_LIMIT]}. {@code null} → {@link #DEFAULT_LIMIT}.
     */
    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
