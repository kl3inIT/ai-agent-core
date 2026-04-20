package com.vn.agent.rag;

/**
 * Thrown by {@link KnowledgeDocumentUploadService} when an {@code allowedRoles} code
 * supplied by the caller does not resolve to any {@code io.jmix.security.model.ResourceRole}
 * via the Jmix {@code ResourceRoleRepository} (D-07 fail-closed posture).
 *
 * <p>Carries the offending code as a field so callers (future REST / Flow UI layers)
 * can render localized messages via the {@code com.vn.agent.rag/UnknownRoleCodeException.message}
 * key in {@code messages.properties} / {@code messages_vi.properties}.</p>
 */
public class UnknownRoleCodeException extends RuntimeException {

    private final String code;

    public UnknownRoleCodeException(String code) {
        super("Unknown role code: " + code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
