package com.vn.agent.parameters;

/**
 * Sparse per-conversation parameter overrides (D-01: {@code model} only in v1).
 *
 * <p>Null-merge semantics (D-02): a non-null field replaces the active profile's value;
 * {@code null} means inherit from the active {@code AiParameters} profile. Expansion is a
 * non-breaking record-field addition when a host surfaces a concrete use case.</p>
 *
 * <p>{@link #NONE} is the canonical "no override" sentinel — prefer it over {@code null}
 * arguments so call sites remain null-safe.</p>
 */
public record Overrides(String model) {

    /** Canonical no-op overrides — inherit every field from the active profile. */
    public static final Overrides NONE = new Overrides(null);
}
