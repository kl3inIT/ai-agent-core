package com.vn.agent.tools.jpql;

import io.jmix.core.EntitySerialization;
import io.jmix.core.entity.KeyValueEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link KeyValueEntity} value-query rows into JSON-serializable maps for the LLM.
 *
 * <p>Ported from the jmix-crm reference assistant. Temporal types are normalized to ISO strings;
 * Jmix entities (rare in projections) fall back to {@link EntitySerialization}.
 */
@Component
public class JpqlResultConverter {

    private final EntitySerialization entitySerialization;

    public JpqlResultConverter(EntitySerialization entitySerialization) {
        this.entitySerialization = entitySerialization;
    }

    public List<Map<String, Object>> convertToMapList(List<KeyValueEntity> results, String[] propertyNames) {
        if (CollectionUtils.isEmpty(results)) {
            return new ArrayList<>();
        }
        return results.stream()
                .map(keyValueEntity -> convertRow(keyValueEntity, propertyNames))
                .toList();
    }

    private Map<String, Object> convertRow(KeyValueEntity keyValueEntity, String[] propertyNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String propertyName : propertyNames) {
            Object value = keyValueEntity.getValue(propertyName);
            row.put(propertyName, convertToSerializableValue(value));
        }
        return row;
    }

    public Object convertToSerializableValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        switch (value) {
            case LocalDateTime localDateTime -> {
                return localDateTime.atOffset(ZoneOffset.UTC).toString();
            }
            case OffsetDateTime offsetDateTime -> {
                return offsetDateTime.toString();
            }
            case Collection<?> collection -> {
                return collection.stream().map(this::convertToSerializableValue).toList();
            }
            default -> {
            }
        }
        try {
            return entitySerialization.objectToJson(value);
        } catch (Exception e) {
            return value;
        }
    }
}
