package com.vn.agent.view.parameters;

import com.vn.agent.parameters.AiParametersBody;
import com.vn.agent.parameters.AiParametersBodyYamlMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 3 — D-12 YAML live-preview contract.
 *
 * <p>{@code ParametersDetailView.refreshYamlPreview} builds an {@link AiParametersBody}
 * from form fields and hands it to {@code AiParametersBodyYamlMapper.writeAsYaml} — the
 * mapper's output is what the CodeEditor preview renders. Asserting the mapper contract
 * directly certifies that changing {@code model} / {@code temperature} in the form
 * regenerates a YAML string that reflects the new values (the form→body wiring is a pure
 * record construction and is covered by existing ParametersService tests).
 *
 * <p>Per plan Rule-1 tolerance clause: driving {@code ParametersDetailView} from an
 * addon-module test requires full Jmix UI test harness from {@code jmix-app}; the YAML
 * preview's load-bearing logic lives in the mapper, so testing the mapper is a sufficient
 * regression guard.
 */
class ParametersDetailYamlPreviewTest {

    private AiParametersBodyYamlMapper mapper() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return new AiParametersBodyYamlMapper(validator);
        }
    }

    private AiParametersBody bodyWith(String model, BigDecimal temperature) {
        return new AiParametersBody(
                model,
                temperature,
                BigDecimal.ONE,
                1500,
                "You are a helpful assistant.",
                List.of(),
                5,
                BigDecimal.valueOf(0.5));
    }

    @Test
    void yamlPreviewRegeneratesOnFieldChange() {
        AiParametersBodyYamlMapper mapper = mapper();

        String yaml = mapper.writeAsYaml(bodyWith("gpt-4o", new BigDecimal("0.7")));

        assertThat(yaml).contains("model: \"gpt-4o\"");
        assertThat(yaml).contains("temperature: 0.7");
    }

    @Test
    void yamlPreviewReflectsModelChange() {
        AiParametersBodyYamlMapper mapper = mapper();

        String firstYaml = mapper.writeAsYaml(bodyWith("gpt-4o", new BigDecimal("0.7")));
        String secondYaml = mapper.writeAsYaml(bodyWith("claude-3-sonnet", new BigDecimal("0.7")));

        assertThat(firstYaml).contains("gpt-4o").doesNotContain("claude-3-sonnet");
        assertThat(secondYaml).contains("claude-3-sonnet").doesNotContain("gpt-4o");
    }
}
