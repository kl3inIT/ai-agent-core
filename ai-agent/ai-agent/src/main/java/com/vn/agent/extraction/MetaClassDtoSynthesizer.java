package com.vn.agent.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.exposure.LlmExposurePolicy;
import io.jmix.core.AccessManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedElement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds prompt-side JSON schema text for entity-generic extraction.
 *
 * <p>No runtime DTO class is created. The extractor calls Spring AI structured output against
 * {@code Map<String, Object>} and uses this schema text as the prompt contract.</p>
 */
@Component
public class MetaClassDtoSynthesizer {

    private static final Set<String> SYSTEM_ATTRIBUTE_NAMES = Set.of(
            "id",
            "version",
            "createdBy",
            "createdDate",
            "lastModifiedBy",
            "lastModifiedDate",
            "deletedBy",
            "deletedDate"
    );

    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final AccessManager accessManager;
    private final LlmExposurePolicy llmExposurePolicy;
    private final MessageTools messageTools;
    private final ObjectMapper objectMapper;

    public MetaClassDtoSynthesizer(Metadata metadata,
                                   MetadataTools metadataTools,
                                   AccessManager accessManager,
                                   LlmExposurePolicy llmExposurePolicy,
                                   MessageTools messageTools,
                                   ObjectMapper objectMapper) {
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.accessManager = accessManager;
        this.llmExposurePolicy = llmExposurePolicy;
        this.messageTools = messageTools;
        this.objectMapper = objectMapper;
    }

    public SynthesizedSchema buildSchema(Class<?> entityClass) {
        return buildSchema(metadata.getClass(entityClass));
    }

    public SynthesizedSchema buildSchema(MetaClass metaClass) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> attributeNames = new ArrayList<>();
        List<String> requiredAttributeNames = new ArrayList<>();

        if (llmExposurePolicy.canReadEntity(metaClass)) {
            List<MetaProperty> sortedProperties = new ArrayList<>(metaClass.getProperties());
            sortedProperties.sort(Comparator.comparing(MetaProperty::getName));
            for (MetaProperty metaProperty : sortedProperties) {
                if (!isSchemaAttribute(metaClass, metaProperty)) {
                    continue;
                }
                Map<String, Object> attributeSchema = buildAttributeSchema(metaProperty);
                if (attributeSchema.isEmpty()) {
                    continue;
                }
                properties.put(metaProperty.getName(), attributeSchema);
                attributeNames.add(metaProperty.getName());
                if (metaProperty.isMandatory()) {
                    requiredAttributeNames.add(metaProperty.getName());
                }
            }
        }

        schema.put("properties", properties);
        schema.put("required", requiredAttributeNames);
        return new SynthesizedSchema(writeSchemaJson(schema), List.copyOf(attributeNames),
                List.copyOf(requiredAttributeNames));
    }

    private boolean isSchemaAttribute(MetaClass metaClass, MetaProperty metaProperty) {
        String attributeName = metaProperty.getName();
        if (SYSTEM_ATTRIBUTE_NAMES.contains(attributeName)) {
            return false;
        }
        MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(metaClass);
        if (primaryKeyProperty != null && primaryKeyProperty.getName().equals(attributeName)) {
            return false;
        }
        if (metaProperty.isReadOnly() || isCollectionAttribute(metaProperty)) {
            return false;
        }
        if (!llmExposurePolicy.canReadAttribute(metaClass, attributeName)) {
            return false;
        }
        EntityAttributeContext attributeAccessContext = new EntityAttributeContext(metaClass, attributeName);
        accessManager.applyRegisteredConstraints(attributeAccessContext);
        return attributeAccessContext.canModify();
    }

    private boolean isCollectionAttribute(MetaProperty metaProperty) {
        Range range = metaProperty.getRange();
        if (range.getCardinality() != null && range.getCardinality().isMany()) {
            return true;
        }
        Class<?> javaType = metaProperty.getJavaType();
        if (javaType != null && Collection.class.isAssignableFrom(javaType)) {
            return true;
        }
        AnnotatedElement annotatedElement = metaProperty.getAnnotatedElement();
        return annotatedElement != null
                && (annotatedElement.isAnnotationPresent(OneToMany.class)
                || annotatedElement.isAnnotationPresent(ManyToMany.class));
    }

    private Map<String, Object> buildAttributeSchema(MetaProperty metaProperty) {
        Range range = metaProperty.getRange();
        if (range.isClass()) {
            MetaClass targetMetaClass = range.asClass();
            if (!llmExposurePolicy.canReadEntity(targetMetaClass)) {
                return Map.of();
            }
            return attributeSchema("string", "uuid", description(metaProperty));
        }
        if (range.isEnum()) {
            Map<String, Object> schema = attributeSchema("string", null, description(metaProperty));
            schema.put("enum", enumValues(metaProperty));
            return schema;
        }
        if (range.isDatatype()) {
            return datatypeSchema(metaProperty, range.asDatatype().getJavaClass());
        }
        return Map.of();
    }

    private Map<String, Object> datatypeSchema(MetaProperty metaProperty, Class<?> javaClass) {
        Class<?> boxedClass = box(javaClass);
        if (boxedClass == String.class || boxedClass == Character.class) {
            return attributeSchema("string", null, description(metaProperty));
        }
        if (boxedClass == UUID.class) {
            return attributeSchema("string", "uuid", description(metaProperty));
        }
        if (boxedClass == Boolean.class) {
            return attributeSchema("boolean", null, description(metaProperty));
        }
        if (boxedClass == Byte.class || boxedClass == Short.class
                || boxedClass == Integer.class || boxedClass == Long.class
                || boxedClass == BigInteger.class) {
            return attributeSchema("integer", null, description(metaProperty));
        }
        if (boxedClass == Float.class || boxedClass == Double.class
                || boxedClass == BigDecimal.class || Number.class.isAssignableFrom(boxedClass)) {
            return attributeSchema("number", null, description(metaProperty));
        }
        if (boxedClass == LocalDate.class || boxedClass == java.sql.Date.class) {
            return attributeSchema("string", "date", description(metaProperty));
        }
        if (boxedClass == LocalTime.class || boxedClass == OffsetTime.class) {
            return attributeSchema("string", "time", description(metaProperty));
        }
        if (boxedClass == LocalDateTime.class || boxedClass == OffsetDateTime.class
                || boxedClass == ZonedDateTime.class || boxedClass == Instant.class
                || boxedClass == java.util.Date.class) {
            return attributeSchema("string", "date-time", description(metaProperty));
        }
        return Map.of();
    }

    private Map<String, Object> attributeSchema(String type, String format, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (format != null) {
            schema.put("format", format);
        }
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        return schema;
    }

    private String description(MetaProperty metaProperty) {
        try {
            return messageTools.getPropertyCaption(metaProperty);
        } catch (RuntimeException ignored) {
            return metaProperty.getName();
        }
    }

    private List<String> enumValues(MetaProperty metaProperty) {
        List<String> values = new ArrayList<>();
        for (Object enumValue : metaProperty.getRange().asEnumeration().getValues()) {
            Enum<?> typedEnum = (Enum<?>) enumValue;
            Object id = typedEnum instanceof EnumClass<?> enumClassValue
                    ? enumClassValue.getId()
                    : typedEnum.name();
            values.add(id == null ? typedEnum.name() : id.toString());
        }
        return values;
    }

    private String writeSchemaJson(Map<String, Object> schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize extraction schema", e);
        }
    }

    private static Class<?> box(Class<?> javaClass) {
        if (javaClass == null || !javaClass.isPrimitive()) {
            return javaClass;
        }
        if (javaClass == boolean.class) return Boolean.class;
        if (javaClass == byte.class) return Byte.class;
        if (javaClass == short.class) return Short.class;
        if (javaClass == int.class) return Integer.class;
        if (javaClass == long.class) return Long.class;
        if (javaClass == float.class) return Float.class;
        if (javaClass == double.class) return Double.class;
        if (javaClass == char.class) return Character.class;
        return javaClass;
    }

    public record SynthesizedSchema(String schemaJson,
                                    List<String> attributeNames,
                                    List<String> requiredAttributeNames) {
    }
}
