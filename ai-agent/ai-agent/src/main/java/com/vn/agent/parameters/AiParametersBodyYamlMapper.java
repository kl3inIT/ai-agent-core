package com.vn.agent.parameters;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strict-on-write, tolerant-on-read mapper for {@link AiParametersBody} (Phase 6 D-05).
 *
 * <p>FAIL_ON_UNKNOWN_PROPERTIES is enabled on the deserialisation path used by the write side so
 * operator YAML containing typos or deprecated keys is rejected loudly with a stable i18n message
 * key ({@code ai-agent.parameters.yaml.unknown-key}). Read-side consumers that only need
 * individual fields use {@code AiParametersResolver}'s pre-existing SnakeYAML path — this mapper
 * is the single source of truth for <b>mutation</b> traffic.</p>
 *
 * <p>Jakarta Bean Validation runs on every parsed body and on every {@link #writeAsYaml}
 * round-trip so the constraints declared on {@link AiParametersBody} are enforced uniformly on
 * both the operator-authored YAML path and the bundled seeder path.</p>
 */
@Component
public class AiParametersBodyYamlMapper {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Validator validator;

    public AiParametersBodyYamlMapper(Validator validator) {
        this.validator = validator;
    }

    /** Parse + validate. Throws {@link ParametersValidationException} on unknown keys or bean-validation violations. */
    public AiParametersBody readValue(InputStream in) {
        AiParametersBody body;
        try {
            body = YAML.readValue(in, AiParametersBody.class);
        } catch (UnrecognizedPropertyException e) {
            throw new ParametersValidationException(
                    "ai-agent.parameters.yaml.unknown-key: " + e.getPropertyName(), e);
        } catch (IOException e) {
            throw new ParametersValidationException(
                    "ai-agent.parameters.yaml.invalid: " + e.getMessage(), e);
        }
        validate(body);
        return body;
    }

    /** Parse + validate from a YAML string (used by {@code ParametersService.create}/{@code update}). */
    public AiParametersBody readValue(String yaml) {
        AiParametersBody body;
        try {
            body = YAML.readValue(yaml, AiParametersBody.class);
        } catch (UnrecognizedPropertyException e) {
            throw new ParametersValidationException(
                    "ai-agent.parameters.yaml.unknown-key: " + e.getPropertyName(), e);
        } catch (IOException e) {
            throw new ParametersValidationException(
                    "ai-agent.parameters.yaml.invalid: " + e.getMessage(), e);
        }
        validate(body);
        return body;
    }

    /** Serialise a validated {@link AiParametersBody} back to canonical YAML (used by the seeder and CRUD write paths). */
    public String writeAsYaml(AiParametersBody body) {
        validate(body);
        try {
            return YAML.writeValueAsString(body);
        } catch (IOException e) {
            throw new ParametersValidationException("ai-agent.parameters.yaml.invalid: " + e.getMessage(), e);
        }
    }

    private void validate(AiParametersBody body) {
        Set<ConstraintViolation<AiParametersBody>> violations = validator.validate(body);
        if (!violations.isEmpty()) {
            String joined = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new ParametersValidationException("ai-agent.parameters.yaml.invalid: " + joined);
        }
    }
}
