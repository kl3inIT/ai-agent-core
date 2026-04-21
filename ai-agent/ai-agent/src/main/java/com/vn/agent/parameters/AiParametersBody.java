package com.vn.agent.parameters;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Jackson/YAML DTO for the {@code AiParameters.bodyYaml} write path (PARAM-02, D-05 strict-on-write).
 *
 * <p>Validated via Jakarta Bean Validation by {@code ParametersService.save} before persistence:
 * unknown top-level keys trigger {@link ParametersValidationException} and value-range violations
 * trigger a {@code ConstraintViolationException} wrapped into {@link ParametersValidationException}.
 * On read, the persisted text flows through the existing snakeyaml path in
 * {@code AiParametersResolver} — tolerant read, strict write.</p>
 *
 * <p>Nullable-field semantics:
 * <ul>
 *   <li>{@code enabledTools} — {@code null} means "all tools allowed" (Phase 2's full
 *       {@code BuiltInDataTools} + host-contributed tools); non-null restricts.</li>
 *   <li>{@code ragTopK} / {@code ragSimilarityThreshold} — {@code null} means "use add-on defaults
 *       from {@code AiAgentRagProperties}" (AI-SPEC §4 — {@code topK=5}, similarity={@code 0.50}).</li>
 * </ul>
 * Required fields ({@code model}, {@code temperature}, {@code maxTokens}, {@code systemPrompt})
 * fail validation when absent.</p>
 */
@JsonPropertyOrder({"model", "temperature", "topP", "maxTokens", "systemPrompt", "enabledTools", "ragTopK", "ragSimilarityThreshold"})
public record AiParametersBody(
        @NotBlank(message = "{ai-agent.parameters.model.required}") String model,
        @NotNull @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal topP,
        @NotNull
        @DecimalMin(value = "1", message = "{ai-agent.parameters.maxTokens.min}")
        @DecimalMax(value = "16000", message = "{ai-agent.parameters.maxTokens.max}")
        Integer maxTokens,
        @NotBlank String systemPrompt,
        List<String> enabledTools,
        Integer ragTopK,
        BigDecimal ragSimilarityThreshold) {
}
