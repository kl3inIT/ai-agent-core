package com.vn.agent.extraction;

/**
 * Stable extraction failure for missing intents, schema parsing, and validation failures.
 *
 * <p>Messages are intentionally generic. Raw model output, extracted values, and file content
 * must not be embedded in exception messages or audit summaries.</p>
 */
public class ExtractionSchemaException extends RuntimeException {

    public static final String CODE_UNKNOWN_INTENT = "unknown_intent";
    public static final String CODE_SCHEMA_PARSE_FAILURE = "schema_parse_failure";
    public static final String CODE_VALIDATION_FAILURE = "validation_failed";
    public static final String CODE_PAYLOAD_SERIALIZATION_FAILURE = "payload_serialization_failed";

    private final String code;
    private final String intentId;
    private final String entityName;
    private final Integer extractedFieldCount;

    private ExtractionSchemaException(String code,
                                      String message,
                                      String intentId,
                                      String entityName,
                                      Integer extractedFieldCount,
                                      Throwable cause) {
        super(message, cause);
        this.code = code;
        this.intentId = intentId;
        this.entityName = entityName;
        this.extractedFieldCount = extractedFieldCount;
    }

    public static ExtractionSchemaException unknownIntent(String intentId) {
        return new ExtractionSchemaException(CODE_UNKNOWN_INTENT,
                "Extraction intent is not registered",
                intentId,
                null,
                null,
                null);
    }

    public static ExtractionSchemaException schemaParseFailure(String intentId,
                                                               String entityName,
                                                               Throwable cause) {
        return new ExtractionSchemaException(CODE_SCHEMA_PARSE_FAILURE,
                "Extraction payload could not be parsed",
                intentId,
                entityName,
                null,
                cause);
    }

    public static ExtractionSchemaException validationFailure(String intentId,
                                                             String entityName,
                                                             int extractedFieldCount) {
        return new ExtractionSchemaException(CODE_VALIDATION_FAILURE,
                "Extraction payload failed validation",
                intentId,
                entityName,
                extractedFieldCount,
                null);
    }

    public static ExtractionSchemaException payloadSerializationFailure(String intentId,
                                                                        String entityName,
                                                                        int extractedFieldCount,
                                                                        Throwable cause) {
        return new ExtractionSchemaException(CODE_PAYLOAD_SERIALIZATION_FAILURE,
                "Extraction payload could not be serialized",
                intentId,
                entityName,
                extractedFieldCount,
                cause);
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

    public Integer getExtractedFieldCount() {
        return extractedFieldCount;
    }
}
