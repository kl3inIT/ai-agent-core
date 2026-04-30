package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps thrown exceptions / Jmix denial paths into the 6 stable error codes
 * specified in CONTEXT D-04. NEVER echoes user-supplied PII or raw exception
 * message text into the LLM-visible result string (P-22 mitigation).
 *
 * <p>Pre-typed {@link ToolUserError} instances from resolvers/converters are
 * NOT passed through verbatim. The translator preserves only their stable code
 * and safe expected hints, then rebuilds the message from code + entity name.
 * This prevents invalid attribute names or other LLM-supplied text from
 * becoming LLM-visible prose. {@code FilterLiteralValueConverter} currently
 * emits {@code invalid_literal}/{@code unsupported_type}/{@code invalid_id};
 * mutation tools remap those to the Phase 11 stable code
 * {@code parameter_conversion_error}.
 *
 * <p>Catches BOTH {@code jakarta.persistence.OptimisticLockException} (raw
 * Eclipselink) AND {@code org.springframework.dao.OptimisticLockingFailureException}
 * (Spring-translated) per RESEARCH Pitfall 5.
 */
@Component
public class MutationErrorTranslator {

    public ToolUserError translate(Throwable thrown, String toolName, MetaClass metaClass) {
        if (thrown instanceof ToolUserError tue) {
            String code = tue.toDto().error();
            if ("parameter_conversion_error".equals(code)
                    && "idempotencyKey must be a UUID v4".equals(tue.toDto().reason())) {
                return invalidIdempotencyKey();
            }
            if ("invalid_literal".equals(code)
                    || "unsupported_type".equals(code)
                    || "invalid_id".equals(code)) {
                return parameterConversion(metaClass);
            }
            return sanitizeStableToolUserError(code, metaClass);
        }

        // concurrent_modification — both flavors per Pitfall 5
        if (thrown instanceof OptimisticLockingFailureException
                || thrown instanceof jakarta.persistence.OptimisticLockException) {
            return new ToolUserError("concurrent_modification",
                    "record was modified concurrently",
                    List.of("call get_record to fetch current state, then retry with a fresh idempotencyKey"));
        }

        // access_denied — both Spring and Jmix flavors
        if (thrown instanceof org.springframework.security.access.AccessDeniedException
                || thrown instanceof io.jmix.core.security.AccessDeniedException) {
            return new ToolUserError("access_denied",
                    "operation not permitted",
                    List.of("do not retry; surface to user"));
        }

        // validation_failed — JPA constraint + Spring data integrity
        if (thrown instanceof jakarta.validation.ConstraintViolationException
                || thrown instanceof DataIntegrityViolationException) {
            return new ToolUserError("validation_failed",
                    "value validation failed",
                    List.of("call describe_entity to inspect mandatory and constraint fields; if you change any values, retry with a fresh idempotencyKey"));
        }

        // Default fallback — NEVER echo the raw exception message into the
        // LLM-visible result string. Generic guidance only (P-22).
        return new ToolUserError("validation_failed",
                "operation failed",
                List.of("call describe_entity to inspect required fields; if you change any values, retry with a fresh idempotencyKey"));
    }

    private ToolUserError sanitizeStableToolUserError(String code, MetaClass metaClass) {
        return switch (code) {
            case "access_denied" -> accessDenied(metaClass);
            case "not_found" -> notFound(metaClass, null);
            case "parameter_conversion_error" -> parameterConversion(metaClass);
            case "validation_failed" -> validationFailed(metaClass);
            case "concurrent_modification" -> concurrentModification(metaClass);
            case "idempotency_violation" -> idempotencyViolation(metaClass);
            case "unknown_entity" -> new ToolUserError("unknown_entity",
                    "entity is not available",
                    List.of("call list_entities and retry with a visible entity name"));
            default -> validationFailed(metaClass);
        };
    }

    public ToolUserError accessDenied(MetaClass metaClass) {
        return new ToolUserError("access_denied",
                "operation not permitted",
                List.of("do not retry; surface to user"));
    }

    public ToolUserError validationFailed(MetaClass metaClass) {
        return new ToolUserError("validation_failed",
                "value validation failed",
                List.of("call describe_entity to inspect mandatory and constraint fields; if you change any values, retry with a fresh idempotencyKey"));
    }

    public ToolUserError concurrentModification(MetaClass metaClass) {
        return new ToolUserError("concurrent_modification",
                "record was modified concurrently",
                List.of("call get_record to fetch current state, then retry with a fresh idempotencyKey"));
    }

    /**
     * Builds the {@code idempotency_violation} error. Used when a dedup row
     * exists for the (toolName, idempotencyKey, userUsername) tuple but the
     * call shape conflicts (different attributes Map for same key).
     */
    public ToolUserError idempotencyViolation(MetaClass metaClass) {
        return new ToolUserError("idempotency_violation",
                "idempotencyKey already used for a different operation",
                List.of("use a fresh idempotencyKey"));
    }

    public ToolUserError parameterConversion(MetaClass metaClass) {
        return new ToolUserError("parameter_conversion_error",
                "parameter value could not be converted",
                List.of("call describe_entity to inspect attribute types, then retry with corrected values"));
    }

    public ToolUserError invalidIdempotencyKey() {
        return new ToolUserError("parameter_conversion_error",
                "idempotencyKey must be a UUID v4",
                List.of("generate a fresh random UUID v4 idempotencyKey: third group starts with 4, fourth group starts with 8, 9, a, or b"));
    }

    public ToolUserError commitFailed(MetaClass metaClass) {
        return new ToolUserError("concurrent_modification",
                "commit outcome is unknown",
                List.of("do not retry automatically; call get_record or find_records to verify state and ask the user before any further mutation"));
    }

    /**
     * Builds the {@code not_found} error. Used by update_record / add_related_record /
     * remove_related_record when the supplied entity id does not resolve.
     */
    public ToolUserError notFound(MetaClass metaClass, String id) {
        // Do NOT echo raw `id` if it could carry attacker-controlled content
        // through to a downstream rendering layer; generic wording plus the
        // structured hint is sufficient for the LLM to call get_record (P-22).
        return new ToolUserError("not_found",
                "no record found for the supplied id",
                List.of("call get_record to verify the id exists before retrying"));
    }
}
