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

    @Test
    void chatDialogViewIsUnchangedAndSidebarHostMirrorsIt() throws Exception {
        // Phase 15 Plan 03 — ChatDialogView (HEADER_BUTTON host) keeps its shape; the new
        // SIDEBAR host AiAgentSidebarView is a sibling lean StandardView composing the SAME
        // ChatPanelFragment class via XML (no new fragment subclass, no custom chrome).
        ViewController dialogController = ChatDialogView.class.getAnnotation(ViewController.class);
        assertThat(dialogController.value()).isEqualTo("AiAgent_ChatDialog");

        ViewController sidebarController = AiAgentSidebarView.class.getAnnotation(ViewController.class);
        ViewDescriptor sidebarDescriptor = AiAgentSidebarView.class.getAnnotation(ViewDescriptor.class);
        assertThat(StandardView.class).isAssignableFrom(AiAgentSidebarView.class);
        assertThat(sidebarController).isNotNull();
        assertThat(sidebarController.value()).isEqualTo("AiAgent_Sidebar");
        assertThat(sidebarDescriptor).isNotNull();
        assertThat(sidebarDescriptor.value()).isEqualTo("ai-agent-sidebar-view.xml");
        assertThat(AiAgentSidebarView.class.getAnnotation(com.vaadin.flow.router.Route.class)).isNull();

        Document sidebarDoc = readDescriptor("/com/vn/agent/view/chat/ai-agent-sidebar-view.xml");
        Element fragment = elementById(sidebarDoc, "chatPanelFragment");
        assertThat(fragment.getTagName()).isEqualTo("fragment");
        assertThat(fragment.getAttribute("class"))
                .isEqualTo("com.vn.agent.view.chat.fragment.ChatPanelFragment");
        // No custom chrome — the in-panel closer lives in ChatSurfaceMounter's panel Div.
        assertThat(elementByIdOrNull(sidebarDoc, "closeButton")).isNull();

        // Same ChatPanelFragment class on both hosts — no duplicate fragment implementation.
        assertThat(AiAgentSidebarView.class.getDeclaredField("chatPanelFragment").getType())
                .isEqualTo(ChatDialogView.class.getDeclaredField("chatPanelFragment").getType());
    }

    private static Document readDescriptor() throws Exception {
        return readDescriptor("/com/vn/agent/view/chat/chat-dialog-view.xml");
    }

    private static Document readDescriptor(String resourcePath) throws Exception {
        try (InputStream stream = ChatDialogViewTest.class.getResourceAsStream(resourcePath)) {
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
