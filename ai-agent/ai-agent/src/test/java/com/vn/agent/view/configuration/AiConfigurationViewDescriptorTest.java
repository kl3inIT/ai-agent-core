package com.vn.agent.view.configuration;

import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationViewDescriptorTest {

    @Test
    void controllerUsesExpectedViewIdentity() {
        com.vaadin.flow.router.Route route = AiConfigurationView.class.getAnnotation(
                com.vaadin.flow.router.Route.class);
        ViewController viewController = AiConfigurationView.class.getAnnotation(ViewController.class);
        ViewDescriptor viewDescriptor = AiConfigurationView.class.getAnnotation(ViewDescriptor.class);

        assertThat(route).isNotNull();
        assertThat(route.value()).isEqualTo("ai-agent/configuration");
        assertThat(viewController).isNotNull();
        assertThat(viewController.value()).isEqualTo("AiAgent_Configuration");
        assertThat(viewDescriptor).isNotNull();
        assertThat(viewDescriptor.value()).isEqualTo("ai-configuration-view.xml");
    }

    @Test
    void descriptorGroupsConfigurationIntoThreeTabs() throws Exception {
        Document document = readDescriptor();
        Element tabSheet = elementById(document, "configurationTabs");

        assertThat(tabSheet.getTagName()).isEqualTo("tabSheet");
        assertThat(tabLabels(tabSheet))
                .containsExactly(
                        "msg:///aiConfiguration.tab.parameters",
                        "msg:///aiConfiguration.tab.exposureRules",
                        "msg:///aiConfiguration.tab.promptContext");
    }

    @Test
    void exposureRulesTabUsesMultiSelectComboBox() throws Exception {
        Document document = readDescriptor();
        Element hiddenEntitiesField = elementById(document, "hiddenEntitiesField");

        assertThat(hiddenEntitiesField.getTagName()).isEqualTo("multiSelectComboBox");
        assertThat(hiddenEntitiesField.getAttribute("label"))
                .isEqualTo("msg:///aiConfiguration.exposure.hiddenEntities");
        assertThat(hiddenEntitiesField.getAttribute("helperText"))
                .isEqualTo("msg:///aiConfiguration.exposure.hiddenEntities.helper");
        assertThat(hiddenEntitiesField.getAttribute("placeholder"))
                .isEqualTo("msg:///aiConfiguration.exposure.hiddenEntities.placeholder");
    }

    @Test
    void promptPreviewTabShowsComposedPromptAsReadOnly() throws Exception {
        Document document = readDescriptor();
        Element composedPromptPreview = elementById(document, "composedPromptPreviewField");

        assertThat(composedPromptPreview.getTagName()).isEqualTo("codeEditor");
        assertThat(composedPromptPreview.getAttribute("readOnly")).isEqualTo("true");
        assertThat(composedPromptPreview.getAttribute("label"))
                .isEqualTo("msg:///baselineContext.composedPrompt");
        assertThat(composedPromptPreview.getAttribute("width")).isEqualTo("100%");
    }

    @Test
    void parametersTabKeepsRowActionsInActionColumn() throws Exception {
        Document document = readDescriptor();
        Element actionsColumn = actionsColumn(document);

        assertThat(actionsColumn.getAttribute("header")).isEqualTo("msg:///common.column.actions");
    }

    private static Document readDescriptor() throws Exception {
        try (InputStream stream = AiConfigurationViewDescriptorTest.class.getResourceAsStream(
                "/com/vn/agent/view/configuration/ai-configuration-view.xml")) {
            assertThat(stream).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementById(Document document, String id) {
        for (int i = 0; i < document.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("*").item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("Element not found: " + id);
    }

    private static Element actionsColumn(Document document) {
        for (int i = 0; i < document.getElementsByTagName("column").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("column").item(i);
            if ("actions".equals(element.getAttribute("key"))) {
                return element;
            }
        }
        throw new AssertionError("Column not found: actions");
    }

    private static List<String> tabLabels(Element tabSheet) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < tabSheet.getElementsByTagName("tab").getLength(); i++) {
            Element tab = (Element) tabSheet.getElementsByTagName("tab").item(i);
            labels.add(tab.getAttribute("label"));
        }
        return labels;
    }
}
