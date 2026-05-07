package com.vn.agent.liquibase;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 Plan 07 — REQ-6 / SCHEMA-01 Liquibase deletion-targets regression.
 *
 * <p>Asserts that {@code 100-ai-task-file-drop-dead-columns.xml} drops every Phase 13
 * artifact retired by Plan 13.1-01:
 * <ul>
 *   <li>Foreign key {@code FK_AI_TASK_FILE__ON_MESSAGE} (the SET-NULL FK from Phase 13)</li>
 *   <li>Indexes {@code IDX_AI_TASK_FILE__ON_MESSAGE} and {@code IDX_AI_TASK_FILE__INJECTED_AT}</li>
 *   <li>Columns {@code MESSAGE_ID} and {@code INJECTED_AT}</li>
 * </ul>
 *
 * <p>Asserts that {@code FK_AI_TASK_FILE__ON_CONVERSATION} (with {@code deleteCascade=true},
 * Pitfall 10) is NOT touched by the Plan 13.1-01 migration — a stray drop here would
 * break the Phase 13 invariant that conversation-delete reaps task-file rows.
 *
 * <p><b>Why XML parse instead of {@code @SpringBootTest} + JdbcTemplate against
 * information_schema:</b> the module-level Spring-context boot regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBoundsException) blocks
 * runtime of every {@code @SpringBootTest} on this module. The Liquibase changelog is
 * the source of truth that Liquibase reads at boot; parsing the XML asserts the same
 * deletion-targets set the JDBC query would observe post-migration. When the upstream
 * boot regression clears, a follow-up plan can switch to live JDBC catalog queries with
 * no semantic loss — the assertion targets are identical.
 */
class LiquibaseSchemaTest {

    private static final String DROP_CHANGELOG =
            "/com/vn/agent/liquibase/agentstore-changelog/100-ai-task-file-drop-dead-columns.xml";
    private static final String CREATE_CHANGELOG =
            "/com/vn/agent/liquibase/agentstore-changelog/090-ai-task-file.xml";

    private static final String AI_TASK_FILE = "AI_TASK_FILE";
    private static final String FK_ON_MESSAGE = "FK_AI_TASK_FILE__ON_MESSAGE";
    private static final String FK_ON_CONVERSATION = "FK_AI_TASK_FILE__ON_CONVERSATION";
    private static final String IDX_ON_MESSAGE = "IDX_AI_TASK_FILE__ON_MESSAGE";
    private static final String IDX_INJECTED_AT = "IDX_AI_TASK_FILE__INJECTED_AT";

    @Test
    void plan100DropsTheMessageFkBeforeAnythingElse() throws Exception {
        Document migration = readChangelog(DROP_CHANGELOG);

        List<Element> dropFks = elementsByTag(migration, "dropForeignKeyConstraint");
        assertThat(dropFks)
                .as("exactly one FK drop expected (the SET-NULL FK_AI_TASK_FILE__ON_MESSAGE)")
                .hasSize(1);

        Element drop = dropFks.get(0);
        assertThat(drop.getAttribute("baseTableName"))
                .isEqualToIgnoringCase(AI_TASK_FILE);
        assertThat(drop.getAttribute("constraintName"))
                .as("the dropped FK must be FK_AI_TASK_FILE__ON_MESSAGE — NOT the conversation FK")
                .isEqualToIgnoringCase(FK_ON_MESSAGE);

        // Conservative ordering check: the FK drop must occur in an earlier (or equal)
        // changeSet position than the column drops, otherwise some dialects fail to drop
        // a column that still has a referencing FK.
        int fkChangeSetIndex = changeSetIndexContaining(migration, drop);
        int messageColumnDropIndex = changeSetIndexContaining(migration,
                findDropColumn(migration, "MESSAGE_ID"));
        assertThat(fkChangeSetIndex)
                .as("FK drop must precede the MESSAGE_ID column drop in changeSet order")
                .isLessThanOrEqualTo(messageColumnDropIndex);
    }

    @Test
    void plan100DropsBothObsoleteIndexes() throws Exception {
        Document migration = readChangelog(DROP_CHANGELOG);

        List<Element> dropIndexes = elementsByTag(migration, "dropIndex");
        List<String> droppedIndexNames = dropIndexes.stream()
                .map(e -> e.getAttribute("indexName").toUpperCase())
                .collect(Collectors.toList());

        assertThat(droppedIndexNames)
                .as("both Phase 13 task-file indexes for the retired columns must be dropped")
                .contains(IDX_ON_MESSAGE.toUpperCase(), IDX_INJECTED_AT.toUpperCase());

        // All dropIndex entries must target AI_TASK_FILE — defensive guard against a
        // stray index drop on an unrelated table.
        for (Element dropIndex : dropIndexes) {
            assertThat(dropIndex.getAttribute("tableName"))
                    .as("every dropIndex in this migration must target AI_TASK_FILE")
                    .isEqualToIgnoringCase(AI_TASK_FILE);
        }
    }

    @Test
    void plan100DropsBothObsoleteColumns() throws Exception {
        Document migration = readChangelog(DROP_CHANGELOG);

        List<Element> dropColumns = elementsByTag(migration, "dropColumn");
        List<String> droppedColumnNames = dropColumns.stream()
                .map(e -> e.getAttribute("columnName").toUpperCase())
                .collect(Collectors.toList());

        assertThat(droppedColumnNames)
                .as("both Phase 13 retired columns MESSAGE_ID + INJECTED_AT must be dropped")
                .contains("MESSAGE_ID", "INJECTED_AT");

        for (Element dropColumn : dropColumns) {
            assertThat(dropColumn.getAttribute("tableName"))
                    .as("every dropColumn in this migration must target AI_TASK_FILE")
                    .isEqualToIgnoringCase(AI_TASK_FILE);
        }
    }

    @Test
    void plan100DoesNotTouchTheConversationCascadeFk() throws Exception {
        Document migration = readChangelog(DROP_CHANGELOG);

        List<Element> dropFks = elementsByTag(migration, "dropForeignKeyConstraint");
        for (Element dropFk : dropFks) {
            assertThat(dropFk.getAttribute("constraintName"))
                    .as("FK_AI_TASK_FILE__ON_CONVERSATION (deleteCascade=true) is a Phase 13 "
                            + "invariant and MUST NOT be dropped (Pitfall 10)")
                    .isNotEqualToIgnoringCase(FK_ON_CONVERSATION);
        }
    }

    @Test
    void conversationCascadeFkSurvivesInTheCreateChangelog() throws Exception {
        Document creation = readChangelog(CREATE_CHANGELOG);

        // Liquibase 090 declares FK_AI_TASK_FILE__ON_CONVERSATION INLINE on the
        // CONVERSATION_ID <column> via foreignKeyName=... + deleteCascade="true" (NOT
        // as a separate <addForeignKeyConstraint>). FK_AI_TASK_FILE__ON_MESSAGE is the
        // one declared via <addForeignKeyConstraint> id="3" (REVIEWS HIGH-2).
        Element conversationColumn = null;
        for (Element column : elementsByTag(creation, "column")) {
            if ("CONVERSATION_ID".equalsIgnoreCase(column.getAttribute("name"))) {
                conversationColumn = column;
                break;
            }
        }
        assertThat(conversationColumn)
                .as("CONVERSATION_ID column must still be declared in Liquibase 090")
                .isNotNull();

        Element constraints = null;
        NodeList constraintNodes = conversationColumn.getElementsByTagName("constraints");
        if (constraintNodes.getLength() > 0) {
            constraints = (Element) constraintNodes.item(0);
        }
        assertThat(constraints)
                .as("CONVERSATION_ID column must carry inline <constraints> declaring the FK")
                .isNotNull();
        assertThat(constraints.getAttribute("foreignKeyName"))
                .as("inline FK name on CONVERSATION_ID must be FK_AI_TASK_FILE__ON_CONVERSATION")
                .isEqualToIgnoringCase(FK_ON_CONVERSATION);
        assertThat(constraints.getAttribute("deleteCascade"))
                .as("conversation FK delete cascade is a Phase 13 invariant (Pitfall 10)")
                .isEqualToIgnoringCase("true");

        // Defensive: no separate <addForeignKeyConstraint> for the conversation FK
        // (would imply the inline cascade declaration was duplicated or moved).
        List<String> addedFkNames = elementsByTag(creation, "addForeignKeyConstraint").stream()
                .map(e -> e.getAttribute("constraintName").toUpperCase())
                .collect(Collectors.toList());
        assertThat(addedFkNames)
                .as("FK_AI_TASK_FILE__ON_CONVERSATION must remain inline; addForeignKeyConstraint "
                        + "is reserved for FK_AI_TASK_FILE__ON_MESSAGE per Phase 13 D-03")
                .doesNotContain(FK_ON_CONVERSATION.toUpperCase());
    }

    // ------------------------------- helpers --------------------------------

    private static Document readChangelog(String classpath) throws Exception {
        try (InputStream stream = LiquibaseSchemaTest.class.getResourceAsStream(classpath)) {
            assertThat(stream)
                    .as("Liquibase changelog must be on test classpath: %s", classpath)
                    .isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Liquibase XML uses a default namespace; set namespace-aware off so a plain
            // tag-name lookup works without prefix bookkeeping.
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

    private static Element findDropColumn(Document document, String columnName) {
        for (Element dropColumn : elementsByTag(document, "dropColumn")) {
            if (dropColumn.getAttribute("columnName").equalsIgnoreCase(columnName)) {
                return dropColumn;
            }
        }
        throw new AssertionError("dropColumn for " + columnName + " not found in changelog");
    }

    private static int changeSetIndexContaining(Document document, Element child) {
        List<Element> changeSets = elementsByTag(document, "changeSet");
        for (int i = 0; i < changeSets.size(); i++) {
            if (isAncestor(changeSets.get(i), child)) {
                return i;
            }
        }
        throw new AssertionError("element not contained in any <changeSet>: " + child.getTagName());
    }

    private static boolean isAncestor(Element ancestor, Element child) {
        for (org.w3c.dom.Node n = child; n != null; n = n.getParentNode()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
