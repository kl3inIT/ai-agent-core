package com.vn.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Sanitizes raw mutation tool arguments before they cross the streaming or fallback-audit
 * boundary. Normal mutation method bodies still receive the original JSON; this class only
 * protects pre-method callback surfaces where {@code DiffSerializer} has not run yet.
 */
@Component
public class MutationArgumentSanitizer {

    private static final Set<String> MUTATION_TOOL_NAMES = Set.of(
            "create_record",
            "update_record",
            "add_related_record",
            "remove_related_record");

    private static final String UNPARSEABLE_ARGUMENTS =
            "{\"sanitized\":true,\"reason\":\"unparseable_mutation_arguments\"}";
    private static final String UNKNOWN_MUTATION_TOOL =
            "{\"sanitized\":true,\"reason\":\"unknown_mutation_tool\"}";

    private final ObjectMapper objectMapper;
    private final AiAgentAuditProperties auditProperties;

    public MutationArgumentSanitizer(ObjectMapper objectMapper,
                                     AiAgentAuditProperties auditProperties) {
        this.objectMapper = objectMapper;
        this.auditProperties = auditProperties;
    }

    public String sanitize(String toolName, String toolInput) {
        if (!MUTATION_TOOL_NAMES.contains(toolName)) {
            return UNKNOWN_MUTATION_TOOL;
        }
        try {
            JsonNode root = objectMapper.readTree(toolInput);
            if (!(root instanceof ObjectNode objectNode)) {
                return UNPARSEABLE_ARGUMENTS;
            }
            JsonNode sanitized = sanitizeObject(objectNode,
                    auditProperties.resolvedSensitiveFields(),
                    auditProperties.resolvedHashSensitiveFields());
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception ignored) {
            return UNPARSEABLE_ARGUMENTS;
        }
    }

    private JsonNode sanitizeNode(JsonNode node, Set<String> sensitiveFields, boolean hashSensitiveFields) {
        if (node instanceof ObjectNode objectNode) {
            return sanitizeObject(objectNode, sensitiveFields, hashSensitiveFields);
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode item : arrayNode) {
                copy.add(sanitizeNode(item, sensitiveFields, hashSensitiveFields));
            }
            return copy;
        }
        return node.deepCopy();
    }

    private ObjectNode sanitizeObject(ObjectNode objectNode, Set<String> sensitiveFields, boolean hashSensitiveFields) {
        ObjectNode copy = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> field : objectNode.properties()) {
            JsonNode value = field.getValue();
            if (hashSensitiveFields && sensitiveFields.contains(field.getKey()) && !value.isNull()) {
                String hashInput = value.isValueNode() ? value.asText() : value.toString();
                copy.set(field.getKey(), TextNode.valueOf(AuditFieldHasher.sha256Hex(hashInput)));
            } else {
                copy.set(field.getKey(), sanitizeNode(value, sensitiveFields, hashSensitiveFields));
            }
        }
        return copy;
    }
}
