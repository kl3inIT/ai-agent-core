package com.vn.agent.extraction;

/**
 * Stable failure for requests targeting an intent id not registered by the host.
 */
public class UnknownIntentException extends RuntimeException {

    private final String intentId;

    public UnknownIntentException(String intentId) {
        super("Unknown extraction intent: " + intentId);
        this.intentId = intentId;
    }

    public String getIntentId() {
        return intentId;
    }
}
