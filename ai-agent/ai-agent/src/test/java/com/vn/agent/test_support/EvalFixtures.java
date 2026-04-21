package com.vn.agent.test_support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Tiny loader for Plan 06-05 eval YAML corpora under {@code src/test/resources/eval/}.
 *
 * <p>Each fixture file is a top-level map {@code {cases: [...]}} where each case is a
 * string-keyed map. Returning {@code List<Map<String,Object>>} lets parameterised tests pull
 * typed fields by name without a per-fixture DTO class.</p>
 */
public final class EvalFixtures {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private EvalFixtures() {
    }

    /** Load {@code /eval/<fileName>} and return the {@code cases:} list from the root map. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadCases(String fileName) {
        try (InputStream in = EvalFixtures.class.getResourceAsStream("/eval/" + fileName)) {
            if (in == null) {
                throw new IllegalStateException("Eval fixture /eval/" + fileName + " not found on classpath");
            }
            Map<String, Object> root = YAML.readValue(in, Map.class);
            Object cases = root.get("cases");
            if (!(cases instanceof List<?>)) {
                throw new IllegalStateException("Eval fixture /eval/" + fileName + " has no 'cases' list");
            }
            return (List<Map<String, Object>>) cases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval fixture /eval/" + fileName, e);
        }
    }
}
