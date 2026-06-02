package com.vn.agent.action;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Array-tolerant carrier for the {@code values} argument of {@code propose_action_choices}.
 *
 * <p><b>Why this exists (iteration 2 of {@code bulk-save-tool-not-exposed}).</b> The single-record
 * proposal tool declares {@code values} as a JSON object (attribute-name → value). When the model
 * is asked to create ≥2 records of the same entity it sometimes reaches for the familiar
 * single-record tool and passes an ARRAY of row objects instead of calling
 * {@code propose_bulk_action_choices}. With a plain {@code Map<String,Object>} parameter, Spring
 * AI's {@code MethodToolCallback.buildTypedArgument} fails to deserialize the array into a Map and
 * throws {@code ToolExecutionException} BEFORE the tool method body runs. In the streaming path
 * that error escapes the {@code ToolExecutionExceptionProcessor} (Spring AI 1.1.x; see GitHub
 * spring-ai #3924 / #4987) and surfaces to the user as a generic chat error — nothing is created.</p>
 *
 * <p>This wrapper carries a custom {@link Deserializer} that accepts BOTH shapes without throwing:
 * a JSON object becomes a single-record {@link #map()}; a JSON array sets {@link #multiRecord()}
 * (and captures the rows in {@link #rows()}). The tool method can then detect the multi-record slip
 * and return a STRUCTURED corrective {@code ActionProposalResult} telling the model to use
 * {@code propose_bulk_action_choices} — instead of a raw, unhelpful Jackson error.</p>
 *
 * <p><b>Schema note.</b> {@code @JsonDeserialize} does not change the JSON Schema Spring AI
 * generates for the parameter; the parameter continues to advertise as an object (the schema
 * generator inspects the declared type's bean shape, and this wrapper's only logical content is a
 * map). The {@code @ToolParam} description still instructs the model to send a single object.</p>
 */
@JsonDeserialize(using = SingleRecordValues.Deserializer.class)
public record SingleRecordValues(Map<String, Object> map,
                                 List<Map<String, Object>> rows,
                                 boolean multiRecord) {

    public SingleRecordValues {
        map = map == null ? Map.of() : new LinkedHashMap<>(map);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static SingleRecordValues ofSingle(Map<String, Object> map) {
        return new SingleRecordValues(map, List.of(), false);
    }

    public static SingleRecordValues empty() {
        return new SingleRecordValues(Map.of(), List.of(), false);
    }

    /**
     * Custom deserializer that tolerates the model passing an array where a single object is
     * expected, so the tool method runs and can return a structured corrective result rather than
     * letting Jackson throw {@code MismatchedInputException} during argument binding.
     */
    static final class Deserializer extends JsonDeserializer<SingleRecordValues> {

        @Override
        public SingleRecordValues deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.readValueAsTree();
            if (node == null || node.isNull() || node.isMissingNode()) {
                return SingleRecordValues.empty();
            }
            if (node.isArray()) {
                List<Map<String, Object>> rows = new java.util.ArrayList<>(node.size());
                for (JsonNode element : node) {
                    if (element != null && element.isObject()) {
                        rows.add(readObject(parser, element));
                    } else {
                        // Non-object array element — still flag multiRecord so the method returns
                        // the corrective path; keep an empty row placeholder so the count is honest.
                        rows.add(Map.of());
                    }
                }
                return new SingleRecordValues(Map.of(), List.copyOf(rows), true);
            }
            if (node.isObject()) {
                return SingleRecordValues.ofSingle(readObject(parser, node));
            }
            // Scalar/other — not a valid single-record object; treat as empty so missing-field
            // validation surfaces a clean prompt rather than a deserialization crash.
            return SingleRecordValues.empty();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> readObject(JsonParser parser, JsonNode objectNode)
                throws IOException {
            Map<String, Object> map = parser.getCodec().treeToValue(objectNode, LinkedHashMap.class);
            return map == null ? Map.of() : map;
        }

        @Override
        public SingleRecordValues getNullValue(DeserializationContext context) {
            return SingleRecordValues.empty();
        }
    }
}
