package com.vn.agent.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.extraction.introspection.PropertyIntrospector;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds prompt-side JSON schema text for entity-generic extraction.
 *
 * <p>No runtime DTO class is created. The extractor calls Spring AI structured output against
 * {@code Map<String, Object>} and uses this schema text as the prompt contract.</p>
 *
 * <p>Coordinator over a list of {@link PropertyIntrospector} strategy beans (mirrors jmix-crm's
 * {@code JpaDomainModelIntrospector}): the entity-level walk — system/PK/collection exclusion,
 * required flags, ordering, schema envelope — lives here; each property's schema fragment is
 * produced by the first introspector whose {@link PropertyIntrospector#supports} matches. Hosts add
 * a property type by contributing a {@code @Component PropertyIntrospector}; the coordinator never
 * changes. Attribute-level security is enforced authoritatively by Jmix at the data/commit layer
 * (like jmix-crm's read-only introspector relying on the constrained {@code DataManager}); the
 * synthesizer only shapes the prompt schema.</p>
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
    private final ObjectMapper objectMapper;
    private final List<PropertyIntrospector> introspectors;

    public MetaClassDtoSynthesizer(Metadata metadata,
                                   MetadataTools metadataTools,
                                   ObjectMapper objectMapper,
                                   List<PropertyIntrospector> introspectors) {
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.objectMapper = objectMapper;
        this.introspectors = introspectors;
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
        // Attribute-level read/write security is enforced authoritatively by Jmix AccessManager at the
        // data/commit layer (like jmix-crm's read-only introspector relying on the constrained
        // DataManager). The synthesizer only shapes the prompt schema and does not re-derive access.
        return !metaProperty.isReadOnly() && !isCollectionAttribute(metaProperty);
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

    /**
     * Delegates to the first {@link PropertyIntrospector} whose {@link PropertyIntrospector#supports}
     * matches (jmix-crm {@code findIntrospector} first-match-wins). Returns an empty map when no
     * introspector handles the property, in which case the coordinator skips the attribute.
     */
    private Map<String, Object> buildAttributeSchema(MetaProperty metaProperty) {
        return introspectors.stream()
                .filter(introspector -> introspector.supports(metaProperty))
                .findFirst()
                .map(introspector -> introspector.buildSchema(metaProperty))
                .orElseGet(Map::of);
    }

    private String writeSchemaJson(Map<String, Object> schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize extraction schema", e);
        }
    }

    public record SynthesizedSchema(String schemaJson,
                                    List<String> attributeNames,
                                    List<String> requiredAttributeNames) {
    }
}
