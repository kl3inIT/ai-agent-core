package com.vn.agent.extraction;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 Plan 01 — structural schema/model assertions for AiExtractionDraft.
 *
 * <p>Uses source/XML parsing instead of {@code @SpringBootTest}: the module has a
 * documented pre-existing Spring-context boot regression in STATE.md that fails
 * before test bodies run. These assertions pin the same entity and Liquibase
 * contract until the shared runtime test context is repaired.
 */
class AiExtractionDraftModelTest {

    private static final Path ENTITY_SOURCE = Path.of(
            "src/main/java/com/vn/agent/entity/AiExtractionDraft.java");
    private static final String CHANGELOG =
            "/com/vn/agent/liquibase/agentstore-changelog/110-ai-extraction-draft.xml";

    @Test
    void entityDeclaresAgentstoreJmixMetadataAndRequiredFields() throws Exception {
        String source = Files.readString(ENTITY_SOURCE);

        assertThat(source).contains(
                "@Store(name = \"agentstore\")",
                "@JmixEntity",
                "@Entity(name = \"ai_AiExtractionDraft\")",
                "@Table(name = \"AI_EXTRACTION_DRAFT\"",
                "@JmixGeneratedValue",
                "@Version",
                "@InstanceName");
        assertThat(source).contains(
                "private UUID id;",
                "private Integer version;",
                "private String instanceName;",
                "private String userUsername;",
                "private String targetEntityName;",
                "private String intentId;",
                "private String payloadJson;",
                "private UUID sourceConversationId;",
                "private UUID sourceTaskFileId;",
                "private OffsetDateTime createdAt;",
                "private OffsetDateTime expiresAt;",
                "private Boolean confirmed = false;");
    }

    @Test
    void liquibaseCreatesDraftTableColumnsAndIndexes() throws Exception {
        Document changelog = readChangelog();
        Element createTable = elementsByTag(changelog, "createTable").get(0);
        assertThat(createTable.getAttribute("tableName")).isEqualTo("AI_EXTRACTION_DRAFT");

        Set<String> columns = elementsByTag(changelog, "column").stream()
                .map(element -> element.getAttribute("name"))
                .collect(Collectors.toSet());
        assertThat(columns).contains(
                "ID",
                "VERSION",
                "USER_USERNAME",
                "TARGET_ENTITY_NAME",
                "INTENT_ID",
                "PAYLOAD_JSON",
                "SOURCE_CONVERSATION_ID",
                "SOURCE_TASK_FILE_ID",
                "INSTANCE_NAME",
                "CREATED_AT",
                "EXPIRES_AT",
                "CONFIRMED");

        assertThat(columnType(changelog, "ID")).isEqualTo("${uuid.type}");
        assertThat(columnType(changelog, "SOURCE_CONVERSATION_ID")).isEqualTo("${uuid.type}");
        assertThat(columnType(changelog, "SOURCE_TASK_FILE_ID")).isEqualTo("${uuid.type}");
        assertThat(columnType(changelog, "CREATED_AT")).isEqualTo("${offsetDateTime.type}");
        assertThat(columnType(changelog, "EXPIRES_AT")).isEqualTo("${offsetDateTime.type}");
        assertThat(columnType(changelog, "PAYLOAD_JSON")).isEqualTo("text");

        Set<String> indexes = elementsByTag(changelog, "createIndex").stream()
                .map(element -> element.getAttribute("indexName"))
                .collect(Collectors.toSet());
        assertThat(indexes).contains(
                "IDX_AI_EXTRACTION_DRAFT__USER_USERNAME",
                "IDX_AI_EXTRACTION_DRAFT__EXPIRES_AT",
                "IDX_AI_EXTRACTION_DRAFT__SOURCE_CONVERSATION");
    }

    @Test
    void bilingualMessageBundlesContainEntityAndAttributeCaptions() throws Exception {
        String english = Files.readString(Path.of("src/main/resources/com/vn/agent/messages_en.properties"));
        String vietnamese = Files.readString(Path.of("src/main/resources/com/vn/agent/messages_vi.properties"));
        List<String> keys = List.of(
                "com.vn.agent.entity/AiExtractionDraft=",
                "com.vn.agent.entity/AiExtractionDraft.id=",
                "com.vn.agent.entity/AiExtractionDraft.version=",
                "com.vn.agent.entity/AiExtractionDraft.instanceName=",
                "com.vn.agent.entity/AiExtractionDraft.userUsername=",
                "com.vn.agent.entity/AiExtractionDraft.targetEntityName=",
                "com.vn.agent.entity/AiExtractionDraft.intentId=",
                "com.vn.agent.entity/AiExtractionDraft.payloadJson=",
                "com.vn.agent.entity/AiExtractionDraft.sourceConversationId=",
                "com.vn.agent.entity/AiExtractionDraft.sourceTaskFileId=",
                "com.vn.agent.entity/AiExtractionDraft.createdAt=",
                "com.vn.agent.entity/AiExtractionDraft.expiresAt=",
                "com.vn.agent.entity/AiExtractionDraft.confirmed=");
        assertThat(english).contains(keys.toArray(String[]::new));
        assertThat(vietnamese).contains(keys.toArray(String[]::new));
    }

    private static Document readChangelog() throws Exception {
        try (InputStream stream = AiExtractionDraftModelTest.class.getResourceAsStream(CHANGELOG)) {
            assertThat(stream).as("changelog must be on the test classpath").isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static List<Element> elementsByTag(Document document, String tagName) {
        NodeList all = document.getElementsByTagName(tagName);
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            elements.add((Element) all.item(i));
        }
        return elements;
    }

    private static String columnType(Document document, String columnName) {
        for (Element column : elementsByTag(document, "column")) {
            if (columnName.equals(column.getAttribute("name"))) {
                return column.getAttribute("type");
            }
        }
        throw new AssertionError("Column not found: " + columnName);
    }
}
