package com.vn.agent.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.extraction.introspection.DatatypePropertyIntrospector;
import com.vn.agent.extraction.introspection.EnumPropertyIntrospector;
import com.vn.agent.extraction.introspection.PropertyIntrospector;
import com.vn.agent.extraction.introspection.RelationPropertyIntrospector;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import jakarta.persistence.OneToMany;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.AnnotatedElement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaClassDtoSynthesizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaJsonParsesSystemAndCollectionFieldsExcludedToOneBecomesUuid() throws Exception {
        MetaProperty id = datatypeProperty("id", java.util.UUID.class, false);
        MetaProperty version = datatypeProperty("version", Integer.class, false);
        MetaProperty name = datatypeProperty("name", String.class, true);
        MetaProperty email = datatypeProperty("email", String.class, false);
        MetaProperty phone = datatypeProperty("phone", String.class, false);
        MetaProperty createdBy = datatypeProperty("createdBy", String.class, false);
        MetaProperty recommendedProducts = collectionProperty("recommendedProducts");
        MetaClass customer = metaClass("jmixapp_Customer",
                id, version, name, email, phone, createdBy, recommendedProducts);
        MetaClass salesOwner = metaClass("jmixapp_SalesOwner");
        MetaProperty salesOwnerProperty = toOneProperty("salesOwner", salesOwner);
        when(customer.getProperties()).thenReturn(List.of(
                id, version, name, email, phone, createdBy, recommendedProducts, salesOwnerProperty));

        MetaClassDtoSynthesizer synthesizer = synthesizer(customer, id);

        MetaClassDtoSynthesizer.SynthesizedSchema schema = synthesizer.buildSchema(customer);
        JsonNode root = objectMapper.readTree(schema.schemaJson());
        JsonNode properties = root.get("properties");

        assertThat(root.get("type").asText()).isEqualTo("object");
        assertThat(schema.attributeNames()).containsExactly("email", "name", "phone", "salesOwner");
        assertThat(schema.requiredAttributeNames()).containsExactly("name");
        assertThat(properties.has("name")).isTrue();
        assertThat(properties.has("email")).isTrue();
        assertThat(properties.has("phone")).isTrue();
        assertThat(properties.get("salesOwner").get("format").asText()).isEqualTo("uuid");
        assertThat(properties.has("recommendedProducts")).isFalse();
        assertThat(properties.has("id")).isFalse();
        assertThat(properties.has("version")).isFalse();
        assertThat(properties.has("createdBy")).isFalse();
    }

    private MetaClassDtoSynthesizer synthesizer(MetaClass metaClass,
                                               MetaProperty primaryKeyProperty) {
        Metadata metadata = mock(Metadata.class);
        MetadataTools metadataTools = mock(MetadataTools.class);
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKeyProperty);
        MessageTools messageTools = mock(MessageTools.class);
        lenient().when(messageTools.getPropertyCaption(org.mockito.ArgumentMatchers.any(MetaProperty.class)))
                .thenAnswer(invocation -> ((MetaProperty) invocation.getArgument(0)).getName());
        List<PropertyIntrospector> introspectors = List.of(
                new RelationPropertyIntrospector(messageTools),
                new EnumPropertyIntrospector(messageTools),
                new DatatypePropertyIntrospector(messageTools));
        return new MetaClassDtoSynthesizer(metadata, metadataTools, objectMapper, introspectors);
    }

    private static MetaClass metaClass(String name, MetaProperty... properties) {
        MetaClass metaClass = mock(MetaClass.class);
        lenient().when(metaClass.getName()).thenReturn(name);
        lenient().when(metaClass.getProperties()).thenReturn(List.of(properties));
        lenient().when(metaClass.getJavaClass()).thenReturn(Object.class);
        return metaClass;
    }

    @SuppressWarnings("unchecked")
    private static MetaProperty datatypeProperty(String name, Class<?> javaClass, boolean mandatory) {
        MetaProperty property = baseProperty(name, javaClass, mandatory);
        Range range = mock(Range.class);
        Datatype<Object> datatype = mock(Datatype.class);
        lenient().when(datatype.getJavaClass()).thenReturn(javaClass);
        lenient().when(range.isDatatype()).thenReturn(true);
        lenient().when(range.asDatatype()).thenReturn(datatype);
        lenient().when(range.getCardinality()).thenReturn(Range.Cardinality.NONE);
        lenient().when(property.getRange()).thenReturn(range);
        return property;
    }

    private static MetaProperty toOneProperty(String name, MetaClass targetMetaClass) {
        MetaProperty property = baseProperty(name, targetMetaClass.getJavaClass(), false);
        Range range = mock(Range.class);
        lenient().when(range.isClass()).thenReturn(true);
        lenient().when(range.asClass()).thenReturn(targetMetaClass);
        lenient().when(range.getCardinality()).thenReturn(Range.Cardinality.MANY_TO_ONE);
        lenient().when(property.getRange()).thenReturn(range);
        return property;
    }

    private static MetaProperty collectionProperty(String name) {
        MetaProperty property = baseProperty(name, List.class, false);
        Range range = mock(Range.class);
        lenient().when(range.getCardinality()).thenReturn(Range.Cardinality.ONE_TO_MANY);
        lenient().when(property.getRange()).thenReturn(range);
        AnnotatedElement annotatedElement = mock(AnnotatedElement.class);
        lenient().when(annotatedElement.isAnnotationPresent(OneToMany.class)).thenReturn(true);
        lenient().when(property.getAnnotatedElement()).thenReturn(annotatedElement);
        return property;
    }

    private static MetaProperty baseProperty(String name, Class<?> javaClass, boolean mandatory) {
        MetaProperty property = mock(MetaProperty.class);
        lenient().when(property.getName()).thenReturn(name);
        Mockito.doReturn(javaClass).when(property).getJavaType();
        lenient().when(property.isMandatory()).thenReturn(mandatory);
        lenient().when(property.isReadOnly()).thenReturn(false);
        AnnotatedElement annotatedElement = mock(AnnotatedElement.class);
        lenient().when(property.getAnnotatedElement()).thenReturn(annotatedElement);
        return property;
    }
}
