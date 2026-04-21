package com.vn.agent.parameters;

/**
 * Thrown by {@code ParametersService} on the write path when the YAML body fails Jakarta
 * Bean Validation or contains unknown top-level keys (D-05 strict-on-write).
 *
 * <p>Unchecked so callers (REST controller, admin view, YAML bootstrap) are not forced to
 * declare {@code throws}. The {@link #getMessage() message} is safe to surface to admin
 * users — it is intentionally specific (invalid key, bad value range) so operators can
 * correct their YAML. Do NOT surface this to chat end users.</p>
 */
public class ParametersValidationException extends RuntimeException {

    public ParametersValidationException(String message) {
        super(message);
    }

    public ParametersValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
