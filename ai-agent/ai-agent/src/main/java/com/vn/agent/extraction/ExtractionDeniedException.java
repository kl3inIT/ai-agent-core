package com.vn.agent.extraction;

/**
 * Stable extraction denial used when an intent target is unavailable to the current user.
 */
public class ExtractionDeniedException extends RuntimeException {

    public static final String CODE_EXPOSURE_DENIED = "exposure_denied";

    private final String code;
    private final String intentId;
    private final String entityName;
    private final String denialReason;

    public ExtractionDeniedException(String intentId, String entityName, String denialReason) {
        super("Extraction target is not available");
        this.code = CODE_EXPOSURE_DENIED;
        this.intentId = intentId;
        this.entityName = entityName;
        this.denialReason = safeDenialReason(denialReason);
    }

    public static ExtractionDeniedException exposureDenied(String intentId,
                                                           String entityName,
                                                           String denialReason) {
        return new ExtractionDeniedException(intentId, entityName, denialReason);
    }

    public String getCode() {
        return code;
    }

    public String getIntentId() {
        return intentId;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getDenialReason() {
        return denialReason;
    }

    private static String safeDenialReason(String denialReason) {
        if (denialReason == null || denialReason.isBlank()) {
            return "exposure_rule";
        }
        return denialReason.replaceAll("[^A-Za-z0-9:_.-]", "_");
    }
}
