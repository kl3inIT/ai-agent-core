package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolErrorDto;
import com.vn.agent.tools.ToolUserError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the Phase 11 six-code mutation error taxonomy and legacy converter-code remaps.
 */
class MutationErrorTranslatorTest {

    private final MutationErrorTranslator translator = new MutationErrorTranslator();

    @Test
    void preservesSixStableCodesUsingSafeTemplates() {
        for (String stableCode : List.of(
                "access_denied",
                "not_found",
                "parameter_conversion_error",
                "validation_failed",
                "concurrent_modification",
                "idempotency_violation")) {

            ToolUserError translated = translator.translate(
                    new ToolUserError(stableCode,
                            "raw user supplied reason with customer-secret-123",
                            List.of("raw expected customer-secret-123")),
                    "create_record",
                    null);

            ToolErrorDto dto = translated.toDto();
            assertThat(dto.error()).isEqualTo(stableCode);
            assertThat(dto.reason()).doesNotContain("customer-secret-123");
            assertThat(dto.expected()).allSatisfy(expected ->
                    assertThat(expected).doesNotContain("customer-secret-123"));
        }
    }

    @Test
    void remapsLegacyConverterCodesToParameterConversionError() {
        for (String legacyCode : List.of("invalid_literal", "unsupported_type", "invalid_id")) {
            ToolUserError translated = translator.translate(
                    new ToolUserError(legacyCode, "raw malformed value with pii-456"),
                    "update_record",
                    null);

            ToolErrorDto dto = translated.toDto();
            assertThat(dto.error()).isEqualTo("parameter_conversion_error");
            assertThat(dto.reason()).doesNotContain("pii-456");
            assertThat(dto.expected()).allSatisfy(expected ->
                    assertThat(expected).doesNotContain("pii-456"));
        }
    }

    @Test
    void unknownExceptionFallbackIsValidationFailedAndDoesNotEchoRawExceptionTextOrPii() {
        ToolUserError translated = translator.translate(
                new IllegalStateException("database blew up for ssn-111-22-3333"),
                "create_record",
                null);

        ToolErrorDto dto = translated.toDto();
        assertThat(dto.error()).isEqualTo("validation_failed");
        assertThat(dto.reason())
                .doesNotContain("database blew up")
                .doesNotContain("ssn-111-22-3333");
        assertThat(dto.expected()).allSatisfy(expected ->
                assertThat(expected)
                        .doesNotContain("database blew up")
                        .doesNotContain("ssn-111-22-3333"));
    }
}
