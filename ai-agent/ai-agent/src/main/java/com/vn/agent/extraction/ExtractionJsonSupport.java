package com.vn.agent.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExtractionJsonSupport {

    private static final TypeReference<LinkedHashMap<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() {
            };

    private ExtractionJsonSupport() {
    }

    static LinkedHashMap<String, Object> toPayloadMap(ObjectMapper objectMapper,
                                                      Object extractedPayload,
                                                      String intentId,
                                                      String entityName) {
        if (extractedPayload == null) {
            throw ExtractionSchemaException.validationFailure(intentId, entityName, 0);
        }
        try {
            LinkedHashMap<String, Object> converted = objectMapper.convertValue(extractedPayload, STRING_OBJECT_MAP);
            return copyMap(converted, intentId, entityName);
        } catch (IllegalArgumentException e) {
            throw ExtractionSchemaException.schemaParseFailure(intentId, entityName, e);
        }
    }

    static String writeJson(ObjectMapper objectMapper,
                            Object value,
                            String intentId,
                            String entityName,
                            int extractedFieldCount) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ExtractionSchemaException.payloadSerializationFailure(
                    intentId, entityName, extractedFieldCount, e);
        }
    }

    static List<String> boundedAttributeNames(Map<String, Object> payload, int limit) {
        if (payload == null || payload.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>(Math.min(payload.size(), limit));
        for (String name : payload.keySet()) {
            if (names.size() == limit) {
                break;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static Object copyJsonValue(Object value, String intentId, String entityName) {
        if (value instanceof Map<?, ?> mapValue) {
            return copyMap(mapValue, intentId, entityName);
        }
        if (value instanceof List<?> listValue) {
            List<Object> copy = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                copy.add(copyJsonValue(item, intentId, entityName));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private static LinkedHashMap<String, Object> copyMap(Map<?, ?> source,
                                                         String intentId,
                                                         String entityName) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object rawKey = entry.getKey();
            if (!(rawKey instanceof String key) || key.isBlank()) {
                throw ExtractionSchemaException.validationFailure(intentId, entityName, source.size());
            }
            copy.put(key, copyJsonValue(entry.getValue(), intentId, entityName));
        }
        return copy;
    }
}
