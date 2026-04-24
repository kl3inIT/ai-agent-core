package com.vn.agent.guard;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable {@code (key, compiled Pattern)} tuple used on the {@link OutputScannerAdvisor} hot path
 * (GUARD-05). Patterns are compiled ONCE at advisor construction to avoid per-turn regex compilation
 * cost and so that any {@link PatternSyntaxException} from operator-supplied regexes surfaces at
 * startup (not mid-chat).
 */
public record CompiledOutputScannerPattern(String key, Pattern pattern) {

    /**
     * Compile one raw configuration entry into a reusable tuple. Throws
     * {@link PatternSyntaxException} when {@code raw.regex()} is not a valid Java regex — callers
     * (currently the advisor constructor) can catch and skip individual malformed patterns.
     */
    public static CompiledOutputScannerPattern from(AiAgentGuardProperties.OutputScanner.Pattern raw) {
        return new CompiledOutputScannerPattern(raw.key(), Pattern.compile(raw.regex()));
    }
}
