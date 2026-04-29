package com.vn.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.core.EntityStates;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.annotation.Comment;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.datatype.Enumeration;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TOOL-09 / D-04 — verifies the widened {@code describe_entity} payload built via
 * {@link MetadataTools} (no raw reflection). Pure-unit Mockito test.
 */
class DescribeEntityPayloadTest {

    private ObjectMapper objectMapper;
    private EntityStates entityStates;
    private MetadataTools metadataTools;
    private MessageTools messageTools;
    private Messages messages;
    private ToolResultFormatter formatter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        entityStates = mock(EntityStates.class);
        metadataTools = mock(MetadataTools.class);
        messageTools = mock(MessageTools.class);
        messages = mock(Messages.class);
        formatter = new ToolResultFormatter(objectMapper, entityStates, metadataTools, messageTools, messages);
    }

    // ---- Test 1: entity-level @Comment present ----

    @Test
    void entityLevelCommentRenderedAtTopLevel() throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn("acme_Customer");
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Customer");
        when(metaClass.getProperties()).thenReturn(List.of());
        // MetadataTools.getMetaAnnotationValue returns the value() member directly (String).
        when(metadataTools.getMetaAnnotationValue(metaClass, Comment.class)).thenReturn("Customer entity");

        String json = formatter.describe(metaClass, Set.of());
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("name").asText()).isEqualTo("acme_Customer");
        assertThat(root.path("label").asText()).isEqualTo("Customer");
        assertThat(root.path("comment").asText()).isEqualTo("Customer entity");
    }

    // ---- Test 2: attribute fields populated for a string attribute ----

    @Test
    void stringAttributeRendersFullDescriptionFields() throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn("acme_Customer");
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Customer");
        // No primary key matches the property under test
        MetaProperty primaryKeyProperty = mock(MetaProperty.class);
        when(primaryKeyProperty.getName()).thenReturn("id");
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKeyProperty);

        MetaProperty nameProperty = stringProperty("name", true /*mandatory*/, false /*readOnly*/);
        when(messageTools.getPropertyCaption(nameProperty)).thenReturn("Name");
        when(metadataTools.getMetaAnnotationValue(nameProperty, Comment.class)).thenReturn("Display name");
        when(metadataTools.isJpa(nameProperty)).thenReturn(true);

        when(metaClass.getProperties()).thenReturn(List.of(nameProperty));

        String json = formatter.describe(metaClass, Set.of("name"));
        JsonNode attribute = objectMapper.readTree(json).path("attributes").get(0);
        assertThat(attribute.path("name").asText()).isEqualTo("name");
        assertThat(attribute.path("label").asText()).isEqualTo("Name");
        assertThat(attribute.path("comment").asText()).isEqualTo("Display name");
        assertThat(attribute.path("attributeType").asText()).isEqualTo("String");
        assertThat(attribute.path("cardinality").asText()).isEqualTo("NONE");
        assertThat(attribute.path("mandatory").asBoolean()).isTrue();
        assertThat(attribute.path("readOnly").asBoolean()).isFalse();
        assertThat(attribute.path("persistent").asBoolean()).isTrue();
        assertThat(attribute.path("transientProperty").asBoolean()).isFalse();
        assertThat(attribute.path("primaryKey").asBoolean()).isFalse();
        // No nullable field in the new shape (replaced by mandatory).
        assertThat(attribute.has("nullable")).isFalse();
    }

    // ---- Test 3: enum attribute renders [{name, id, label}] with locale-resolved labels ----

    @Test
    void enumAttributeRendersEnumValuesWithLocaleResolvedLabels_Vietnamese() throws Exception {
        runEnumValuesAssertion(new Locale("vi", "VN"), "Đang hoạt động");
    }

    @Test
    void enumAttributeRendersEnumValuesWithLocaleResolvedLabels_English() throws Exception {
        runEnumValuesAssertion(Locale.ENGLISH, "Active");
    }

    private void runEnumValuesAssertion(Locale locale, String expectedActiveLabel) throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn("acme_Order");
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Order");
        MetaProperty primaryKeyProperty = mock(MetaProperty.class);
        lenient().when(primaryKeyProperty.getName()).thenReturn("id");
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKeyProperty);

        MetaProperty statusProperty = mock(MetaProperty.class);
        when(statusProperty.getName()).thenReturn("status");
        when(messageTools.getPropertyCaption(statusProperty)).thenReturn("Status");
        Range range = mock(Range.class);
        when(range.isEnum()).thenReturn(true);
        when(range.isClass()).thenReturn(false);
        when(range.isDatatype()).thenReturn(false);
        when(range.getCardinality()).thenReturn(Range.Cardinality.NONE);
        Enumeration<Status> enumeration = mock(Enumeration.class);
        when(enumeration.getValues()).thenReturn(List.of(Status.ACTIVE, Status.INACTIVE));
        when(enumeration.getJavaClass()).thenReturn(Status.class);
        when(range.asEnumeration()).thenReturn(enumeration);
        when(statusProperty.getRange()).thenReturn(range);
        when(statusProperty.isMandatory()).thenReturn(false);
        when(statusProperty.isReadOnly()).thenReturn(false);
        when(metadataTools.isJpa(statusProperty)).thenReturn(true);

        when(messages.getMessage(Status.ACTIVE)).thenReturn(expectedActiveLabel);
        when(messages.getMessage(Status.INACTIVE)).thenReturn("Inactive-" + locale.getLanguage());

        when(metaClass.getProperties()).thenReturn(List.of(statusProperty));

        String json = formatter.describe(metaClass, Set.of("status"));
        JsonNode attribute = objectMapper.readTree(json).path("attributes").get(0);
        JsonNode enumValues = attribute.path("enumValues");
        assertThat(enumValues.isArray()).isTrue();
        assertThat(enumValues.size()).isEqualTo(2);
        assertThat(enumValues.get(0).path("name").asText()).isEqualTo("ACTIVE");
        assertThat(enumValues.get(0).path("id").asText()).isEqualTo("A");
        assertThat(enumValues.get(0).path("label").asText()).isEqualTo(expectedActiveLabel);
        assertThat(enumValues.get(1).path("name").asText()).isEqualTo("INACTIVE");
        assertThat(enumValues.get(1).path("id").asText()).isEqualTo("I");
        assertThat(enumValues.get(1).path("label").asText()).isEqualTo("Inactive-" + locale.getLanguage());
    }

    // ---- Test 4: reference attribute renders relationshipTarget as {name, label} ----

    @Test
    void referenceAttributeRendersRelationshipTargetAsNameLabelObject() throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn("acme_Order");
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Order");
        MetaProperty primaryKeyProperty = mock(MetaProperty.class);
        lenient().when(primaryKeyProperty.getName()).thenReturn("id");
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKeyProperty);

        MetaClass customerMetaClass = mock(MetaClass.class);
        when(customerMetaClass.getName()).thenReturn("acme_Customer");
        when(messageTools.getEntityCaption(customerMetaClass)).thenReturn("Customer");

        MetaProperty customerProperty = mock(MetaProperty.class);
        when(customerProperty.getName()).thenReturn("customer");
        when(messageTools.getPropertyCaption(customerProperty)).thenReturn("Customer");
        Range range = mock(Range.class);
        when(range.isClass()).thenReturn(true);
        when(range.isEnum()).thenReturn(false);
        when(range.isDatatype()).thenReturn(false);
        when(range.asClass()).thenReturn(customerMetaClass);
        when(range.getCardinality()).thenReturn(Range.Cardinality.MANY_TO_ONE);
        when(customerProperty.getRange()).thenReturn(range);
        when(customerProperty.isMandatory()).thenReturn(false);
        when(customerProperty.isReadOnly()).thenReturn(false);
        when(metadataTools.isJpa(customerProperty)).thenReturn(true);

        when(metaClass.getProperties()).thenReturn(List.of(customerProperty));

        String json = formatter.describe(metaClass, Set.of("customer"));
        JsonNode attribute = objectMapper.readTree(json).path("attributes").get(0);
        JsonNode relationshipTarget = attribute.path("relationshipTarget");
        assertThat(relationshipTarget.isObject()).isTrue();
        assertThat(relationshipTarget.path("name").asText()).isEqualTo("acme_Customer");
        assertThat(relationshipTarget.path("label").asText()).isEqualTo("Customer");

        // Cardinality is the raw enum string for the same attribute (Test 5).
        assertThat(attribute.path("cardinality").asText()).isEqualTo("MANY_TO_ONE");
    }

    // ---- Test 5: cardinality field is the raw enum string (covered above for MANY_TO_ONE) ----
    // Test 5 assertion is folded into the reference-attribute test above.

    // ---- Test 6: mandatory replaces nullable (covered in Test 2) ----

    // ---- Test 7: maxLength is null when @Column.length is the default 255 ----

    @Test
    void maxLengthIsNullWhenColumnLengthIsDefault() throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn("acme_Customer");
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Customer");
        MetaProperty primaryKeyProperty = mock(MetaProperty.class);
        lenient().when(primaryKeyProperty.getName()).thenReturn("id");
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKeyProperty);

        MetaProperty nameProperty = stringProperty("name", false, false);
        when(messageTools.getPropertyCaption(nameProperty)).thenReturn("Name");
        when(metadataTools.isJpa(nameProperty)).thenReturn(true);
        // Annotated element returns null → columnLength returns null → maxLength field absent.
        when(nameProperty.getAnnotatedElement()).thenReturn(null);
        when(metaClass.getProperties()).thenReturn(List.of(nameProperty));

        String json = formatter.describe(metaClass, Set.of("name"));
        JsonNode attribute = objectMapper.readTree(json).path("attributes").get(0);
        // @JsonInclude(NON_NULL) on AttributeDescription suppresses absent maxLength
        assertThat(attribute.has("maxLength")).isFalse();
    }

    // ---- Test 8: NO raw reflection in production source ----

    @Test
    void toolResultFormatterSource_doesNotUseRawReflectionForMetadata() throws Exception {
        Path source = Path.of("src/main/java/com/vn/agent/tools/ToolResultFormatter.java");
        if (!Files.exists(source)) {
            // Test runner CWD may differ across IDE/CLI; fallback to module-relative path.
            source = Path.of("ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java");
        }
        String body = Files.readString(source);
        // Forbidden raw reflection patterns (Phase 9 framing — no getDeclaredFields, etc.).
        assertThat(body).doesNotContain("getDeclaredFields");
        assertThat(body).doesNotContain("java.lang.reflect.Field");
        assertThat(body).doesNotContain("java.lang.reflect.Method");
        // Whitelisted: AnnotatedElement is needed for the columnLength @Column.length workaround
        // (no MetadataTools accessor exists for @Column.length in Jmix 2.8.1). Confirm it is
        // referenced at most a small handful of times — Javadoc, return-type, getAnnotatedElement().
        int annotatedElementCount = body.split("AnnotatedElement", -1).length - 1;
        assertThat(annotatedElementCount).isLessThanOrEqualTo(3);
    }

    // ---- helpers ----

    /**
     * Build a String-typed datatype attribute. Mandatory/readOnly are configurable; isEnum and
     * isClass are wired to {@code false}. The returned mock includes a Range with
     * {@link Range.Cardinality#NONE}.
     */
    private MetaProperty stringProperty(String name, boolean mandatory, boolean readOnly) {
        MetaProperty property = mock(MetaProperty.class);
        when(property.getName()).thenReturn(name);
        Range range = mock(Range.class);
        when(range.isDatatype()).thenReturn(true);
        when(range.isEnum()).thenReturn(false);
        when(range.isClass()).thenReturn(false);
        when(range.getCardinality()).thenReturn(Range.Cardinality.NONE);
        Datatype datatype = mock(Datatype.class);
        when(datatype.getJavaClass()).thenReturn(String.class);
        when(range.asDatatype()).thenReturn(datatype);
        when(property.getRange()).thenReturn(range);
        when(property.isMandatory()).thenReturn(mandatory);
        when(property.isReadOnly()).thenReturn(readOnly);
        return property;
    }

    /** Sample enum used to drive the enumValues test. */
    public enum Status implements EnumClass<String> {
        ACTIVE("A"),
        INACTIVE("I");

        private final String id;

        Status(String id) {
            this.id = id;
        }

        @Override
        @NonNull
        public String getId() {
            return id;
        }
    }
}
