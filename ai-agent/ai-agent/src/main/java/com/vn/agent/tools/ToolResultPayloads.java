package com.vn.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

record ReadableEntitySummary(String name, String label) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record DescribeEntityResult(String name,
                            String label,
                            String comment,
                            List<AttributeDescription> attributes) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record AttributeDescription(String name,
                            String label,
                            String comment,
                            String attributeType,
                            String cardinality,
                            boolean mandatory,
                            boolean readOnly,
                            boolean persistent,
                            boolean transientProperty,
                            boolean primaryKey,
                            List<EnumValueDescription> enumValues,
                            EntityRef relationshipTarget,
                            Integer maxLength) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record EnumValueDescription(String name, String label) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record EntityRef(String name, String label) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record RecordsPayload(List<Map<String, Object>> rows,
                      int limit,
                      boolean truncated,
                      String hint) {
}

record RelatedRecordsResult(String entityName,
                            String relationship,
                            String targetEntity,
                            List<Map<String, Object>> rows) {
}

record CountResult(String entityName, long count) {
}
