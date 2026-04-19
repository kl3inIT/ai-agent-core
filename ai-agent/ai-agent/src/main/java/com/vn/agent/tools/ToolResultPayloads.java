package com.vn.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

record ReadableEntitySummary(String name, String label) {
}

record DescribeEntityResult(String entityName, String label, List<AttributeDescription> attributes) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record AttributeDescription(String name,
                            String type,
                            boolean nullable,
                            String label,
                            List<String> enumValues,
                            String relationshipTarget,
                            Integer maxLength) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record RecordsResult(String entityName,
                     List<Map<String, Object>> rows,
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
