package com.vn.agent.view.chat;

import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.UUID;

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
    void descriptorComposesChatPanelFragmentAndUsesMessages() throws Exception {
        Document document = readDescriptor();

        Element fragment = elementById(document, "chatPanelFragment");
        Element closeButton = elementById(document, "closeButton");
        Element newChatButton = elementById(document, "newChatButton");

        assertThat(fragment.getTagName()).isEqualTo("fragment");
        assertThat(fragment.getAttribute("class"))
                .isEqualTo("com.vn.agent.view.chat.fragment.ChatPanelFragment");
        assertThat(closeButton.getAttribute("text")).startsWith("msg://");
        assertThat(newChatButton.getAttribute("text")).startsWith("msg://");
    }

    @Test
    void newChatClearsSessionStateThroughFragment() throws Exception {
        ChatDialogView view = new ChatDialogView();
        ChatPanelFragment fragment = new ChatPanelFragment();
        AiChatSessionState sessionState = new AiChatSessionState();
        UUID conversationId = UUID.randomUUID();
        sessionState.setCurrentConversationId(conversationId);
        inject(fragment, ChatPanelFragment.class, "chatSessionState", sessionState);
        inject(fragment, ChatPanelFragment.class, "conversationId", conversationId);
        inject(view, ChatDialogView.class, "chatPanelFragment", fragment);

        view.onNewChatButtonClick(null);

        assertThat(fragment.getConversationId()).isNull();
        assertThat(sessionState.getCurrentConversationId()).isNull();
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
        for (int i = 0; i < document.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("*").item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("Element not found: " + id);
    }

    private static void inject(Object target, Class<?> targetClass, String fieldName, Object value) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
