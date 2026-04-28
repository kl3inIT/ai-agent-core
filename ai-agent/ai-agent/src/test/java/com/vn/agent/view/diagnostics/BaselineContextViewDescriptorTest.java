package com.vn.agent.view.diagnostics;

import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineContextViewDescriptorTest {

    @Test
    void controllerUsesExpectedViewIdentity() {
        com.vaadin.flow.router.Route route = BaselineContextView.class.getAnnotation(
                com.vaadin.flow.router.Route.class);
        ViewController viewController = BaselineContextView.class.getAnnotation(ViewController.class);
        ViewDescriptor viewDescriptor = BaselineContextView.class.getAnnotation(ViewDescriptor.class);

        assertThat(route).isNotNull();
        assertThat(route.value()).isEqualTo("ai-agent/baseline-context");
        assertThat(viewController).isNotNull();
        assertThat(viewController.value()).isEqualTo("AiAgent_BaselineContext");
        assertThat(viewDescriptor).isNotNull();
        assertThat(viewDescriptor.value()).isEqualTo("baseline-context-view.xml");
    }

    @Test
    void descriptorRendersBaselinePreviewAsReadOnly() throws Exception {
        Document document = readDescriptor();
        Element preview = elementById(document, "baselinePreviewField");

        assertThat(preview.getTagName()).isEqualTo("codeEditor");
        assertThat(preview.getAttribute("readOnly")).isEqualTo("true");
        assertThat(preview.getAttribute("label")).startsWith("msg:///");
        assertThat(preview.getAttribute("width")).isEqualTo("100%");
    }

    @Test
    void descriptorRendersComposedPromptPreviewAsReadOnly() throws Exception {
        Document document = readDescriptor();
        Element preview = elementById(document, "composedPromptPreviewField");

        assertThat(preview.getTagName()).isEqualTo("codeEditor");
        assertThat(preview.getAttribute("readOnly")).isEqualTo("true");
        assertThat(preview.getAttribute("label")).isEqualTo("msg:///baselineContext.composedPrompt");
        assertThat(preview.getAttribute("width")).isEqualTo("100%");
    }

    @Test
    void descriptorShowsPromptPreviewMetadata() throws Exception {
        Document document = readDescriptor();
        Element activeProfile = elementById(document, "activeProfileField");
        Element previewConversationId = elementById(document, "previewConversationIdField");

        assertThat(activeProfile.getTagName()).isEqualTo("textField");
        assertThat(activeProfile.getAttribute("readOnly")).isEqualTo("true");
        assertThat(activeProfile.getAttribute("label")).isEqualTo("msg:///baselineContext.activeProfile");
        assertThat(previewConversationId.getTagName()).isEqualTo("textField");
        assertThat(previewConversationId.getAttribute("readOnly")).isEqualTo("true");
        assertThat(previewConversationId.getAttribute("label"))
                .isEqualTo("msg:///baselineContext.previewConversationId");
    }

    @Test
    void descriptorHasRefreshOnlyNoSaveAction() throws Exception {
        Document document = readDescriptor();
        Element refreshButton = elementById(document, "refreshButton");

        assertThat(refreshButton.getTagName()).isEqualTo("button");
        assertThat(refreshButton.getAttribute("text")).startsWith("msg:///");
        assertThat(document.getElementsByTagName("action").getLength()).isZero();
    }

    private static Document readDescriptor() throws Exception {
        try (InputStream stream = BaselineContextViewDescriptorTest.class.getResourceAsStream(
                "/com/vn/agent/view/diagnostics/baseline-context-view.xml")) {
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
}
