package com.vn.agent.extraction;

/**
 * UI-facing option for intent selection.
 */
public record IntentOption(String intentId,
                           String label,
                           String description,
                           String entityName,
                           boolean auto) {
}
