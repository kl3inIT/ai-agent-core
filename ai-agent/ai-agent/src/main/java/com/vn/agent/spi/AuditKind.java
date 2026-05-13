package com.vn.agent.spi;

/**
 * SPI-06: kind tag carried by every AiAuditEvent row and passed to
 * {@link AuditListener#onEventAudited(java.util.UUID, String)}. Open-ended
 * String values — hosts may introduce additional kinds (e.g. "GUARDRAIL") after v1
 * without schema change. The underlying KIND column is varchar(16) to keep
 * the option open. Plan 16-02 widens KIND to varchar(32) to fit
 * MODEL_VALIDATION_FAILURE (24 chars).
 */
public final class AuditKind {
    public static final String CHAT = "CHAT";
    public static final String TOOL = "TOOL";
    public static final String RETRIEVAL = "RETRIEVAL";
    public static final String MODEL_VALIDATION_FAILURE = "MODEL_VALIDATION_FAILURE";

    private AuditKind() { }
}
