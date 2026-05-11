package com.vn.agent.extraction;

/**
 * Stable failure for requests targeting an intent id not registered by the host.
 */
public class UnknownIntentException extends RuntimeException {

    public static final String CODE = ExtractionSchemaException.CODE_UNKNOWN_INTENT;

    private final String intentId;

    public UnknownIntentException(String intentId) {
        super("Unknown extraction intent: " + intentId);
        this.intentId = intentId;
    }

    public String getCode() {
        return CODE;
    }

    public String getIntentId() {
        return intentId;
    }
}
