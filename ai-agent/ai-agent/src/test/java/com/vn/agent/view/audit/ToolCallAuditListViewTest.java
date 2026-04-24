package com.vn.agent.view.audit;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 3 — D-20 grid-export actions + grid column contract.
 *
 * <p>Asserts the {@code tool-call-audit-list-view.xml} descriptor declares both
 * {@code grdexp_excelExport} and {@code grdexp_jsonExport} actions on the
 * {@code auditsDataGrid} — these carry the gridexport behavior. Columns must include
 * all six plan-mandated fields (createdDate/startedAt, userUsername, toolName, phase,
 * outcome, latencyMs).
 *
 * <p>Per plan Rule-1 tolerance clause: Jmix DataGrid action discovery at runtime
 * requires a live UiTest session; XML-descriptor inspection certifies the same contract
 * without bootstrapping Vaadin + the jmix-app module, and the descriptor IS the source
 * of truth for action / column declarations.
 */
class ToolCallAuditListViewTest {

    private static final String DESCRIPTOR_PATH =
            "/com/vn/agent/view/audit/tool-call-audit-list-view.xml";

    private Document loadDescriptor() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(DESCRIPTOR_PATH)) {
            assertThat(in).as("descriptor must be on the test classpath").isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(in));
        }
    }

    @Test
    void gridHasExportActions() throws Exception {
        Document doc = loadDescriptor();
        NodeList actions = doc.getElementsByTagName("action");

        List<String> actionTypes = new ArrayList<>();
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            String type = action.getAttribute("type");
            if (type != null && !type.isEmpty()) {
                actionTypes.add(type);
            }
        }

        assertThat(actionTypes)
                .as("grid must declare grdexp_excelExport + grdexp_jsonExport (D-20)")
                .contains("grdexp_excelExport", "grdexp_jsonExport");
    }

    @Test
    void gridHasAllSixColumns() throws Exception {
        Document doc = loadDescriptor();
        NodeList columns = doc.getElementsByTagName("column");

        List<String> columnKeys = new ArrayList<>();
        for (int i = 0; i < columns.getLength(); i++) {
            Element col = (Element) columns.item(i);
            String property = col.getAttribute("property");
            String key = col.getAttribute("key");
            // Prefer property; fall back to key when the column uses a renderer-only definition.
            String identifier = (property != null && !property.isEmpty()) ? property : key;
            if (identifier != null && !identifier.isEmpty()) {
                columnKeys.add(identifier);
            }
        }

        assertThat(columnKeys)
                .as("grid must render all six audit columns")
                .contains("startedAt", "userUsername", "toolName", "phase", "outcome", "latencyMs");
    }
}
