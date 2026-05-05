package com.vn.agent.view.chat;

import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDialogViewTest {

    @Test
    void controllerIsNoEntityStandardView() {
        ViewController viewController = ChatDialogView.class.getAnnotation(ViewController.class);
        ViewDescriptor viewDescriptor = ChatDialogView.class.getAnnotation(ViewDescriptor.class);

        assertThat(StandardView.class).isAssignableFrom(ChatDialogView.class);
        assertThat(viewController).isNotNull();
        assertThat(viewController.value()).isEqualTo("AiAgent_ChatDialog");
        assertThat(viewDescriptor).isNotNull();
        assertThat(viewDescriptor.value()).isEqualTo("chat-dialog-view.xml");
        assertThat(ChatDialogView.class.getAnnotation(com.vaadin.flow.router.Route.class)).isNull();
        assertThat(ChatDialogView.class.getAnnotation(io.jmix.flowui.view.EditedEntityContainer.class)).isNull();
    }

    @Test
    void descriptorComposesChatPanelFragmentWithoutCustomChrome() throws Exception {
        Document document = readDescriptor();

        Element fragment = elementById(document, "chatPanelFragment");

        assertThat(fragment.getTagName()).isEqualTo("fragment");
        assertThat(fragment.getAttribute("class"))
                .isEqualTo("com.vn.agent.view.chat.fragment.ChatPanelFragment");

        // DialogWindow's built-in X handles close; new-chat moved into the fragment titleBar.
        assertThat(elementByIdOrNull(document, "closeButton")).isNull();
        assertThat(elementByIdOrNull(document, "newChatButton")).isNull();
        assertThat(elementByIdOrNull(document, "dialogHeaderBar")).isNull();
    }

    private static Document readDescriptor() throws Exception {
        try (InputStream stream = ChatDialogViewTest.class.getResourceAsStream(
                "/com/vn/agent/view/chat/chat-dialog-view.xml")) {
            assertThat(stream).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementById(Document document, String id) {
        Element element = elementByIdOrNull(document, id);
        if (element == null) {
            throw new AssertionError("Element not found: " + id);
        }
        return element;
    }

    private static Element elementByIdOrNull(Document document, String id) {
        for (int i = 0; i < document.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("*").item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        return null;
    }
}
