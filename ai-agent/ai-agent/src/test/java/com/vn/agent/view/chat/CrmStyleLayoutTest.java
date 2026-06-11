package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inline-attachments layout DOM regression.
 *
 * <p>Asserts that {@code chat-panel-fragment.xml} renders the full-width chat column with
 * the upload moved into the composer bar (the right-pane Attachments split was retired;
 * files render inline as cards), AND that BOTH chat surface descriptors
 * ({@code chat-view.xml} for FULL_ROUTE, {@code chat-dialog-view.xml} for HEADER_BUTTON)
 * compose the same {@link com.vn.agent.view.chat.fragment.ChatPanelFragment} class so the
 * layout is identical regardless of which surface mounted it.
 *
 * <p><b>Why XML descriptor parsing instead of {@code @UiTest}:</b> the module-level
 * {@code @SpringBootTest} boot regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBoundsException) blocks
 * runtime of every {@code @UiTest} that boots an agentstore Spring context. Pure XML
 * descriptor parsing exercises the SAME contract surface (Vaadin element ids in the
 * fragment that becomes the live DOM tree at runtime) without paying that boot cost,
 * mirroring the {@link ChatDialogViewTest#descriptorComposesChatPanelFragmentWithoutCustomChrome}
 * pattern shipped under Plan 12-04.
 */
class CrmStyleLayoutTest {

    private static final String FRAGMENT_DESCRIPTOR =
            "/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml";
    private static final String CHAT_VIEW_DESCRIPTOR =
            "/com/vn/agent/view/chat/chat-view.xml";
    private static final String CHAT_DIALOG_VIEW_DESCRIPTOR =
            "/com/vn/agent/view/chat/chat-dialog-view.xml";
    private static final String CHAT_PANEL_FRAGMENT_FQN =
            "com.vn.agent.view.chat.fragment.ChatPanelFragment";

    @Test
    void chatPanelFragmentRendersFullWidthChatWithComposerUpload() throws Exception {
        Document document = readDescriptor(FRAGMENT_DESCRIPTOR);

        // 1. The right-pane Attachments split is retired — chat is full-width now.
        assertThat(elementByIdOrNull(document, "conversationSplit"))
                .as("right-pane split must be gone (inline-attachments layout)")
                .isNull();
        assertThat(elementByIdOrNull(document, "attachmentsPanel"))
                .as("permanent right-pane Attachments vbox must be gone")
                .isNull();
        assertThat(elementByIdOrNull(document, "attachmentsGridLayout"))
                .as("right-pane card grid must be gone (files render inline)")
                .isNull();
        assertThat(elementByIdOrNull(document, "attachmentsEmptyState"))
                .as("right-pane empty-state must be gone")
                .isNull();

        // 2. Full-width chat column substrate is preserved.
        assertThat(elementByIdOrNull(document, "rootLayout"))
                .as("rootLayout is the full-width chat column")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "messageListSlot"))
                .as("messageListSlot is the substrate for chat bubbles + inline cards")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "messageInputSlot"))
                .as("messageInputSlot remains the Phase 12 STT integration site")
                .isNotNull();

        // 3. Upload moved into the composer bar next to the input — exactly one <upload>,
        //    no deprecated receiverType, declarative cap preserved.
        Element composerBar = elementById(document, "composerBar");
        assertThat(composerBar.getTagName()).isEqualTo("hbox");
        assertThat(document.getElementsByTagName("upload").getLength())
                .as("exactly one upload control, now in the composer")
                .isEqualTo(1);
        Element taskFileUpload = elementById(document, "taskFileUpload");
        assertThat(taskFileUpload.hasAttribute("receiverType"))
                .as("UploadHandler.toFile path — no declarative receiverType")
                .isFalse();
        assertThat(taskFileUpload.getAttribute("maxFiles")).isEqualTo("10");

        // 4. The composer bar holds the upload AND the message-input slot.
        assertThat(directElementChildren(composerBar))
                .extracting(e -> e.getAttribute("id"))
                .containsExactly("taskFileUpload", "messageInputSlot");
    }

    @Test
    void chatViewMountsTheFragmentForFullRouteSurface() throws Exception {
        Document document = readDescriptor(CHAT_VIEW_DESCRIPTOR);
        Element fragment = elementById(document, "chatPanelFragment");

        assertThat(fragment.getTagName()).isEqualTo("fragment");
        assertThat(fragment.getAttribute("class"))
                .as("FULL_ROUTE surface (ChatView) must mount the same ChatPanelFragment")
                .isEqualTo(CHAT_PANEL_FRAGMENT_FQN);
    }

    @Test
    void chatDialogViewMountsTheFragmentForHeaderButtonSurface() throws Exception {
        Document document = readDescriptor(CHAT_DIALOG_VIEW_DESCRIPTOR);
        Element fragment = elementById(document, "chatPanelFragment");

        assertThat(fragment.getTagName()).isEqualTo("fragment");
        assertThat(fragment.getAttribute("class"))
                .as("HEADER_BUTTON surface (ChatDialogView) must mount the same ChatPanelFragment")
                .isEqualTo(CHAT_PANEL_FRAGMENT_FQN);
    }

    // ------------------------------- helpers --------------------------------

    private static Document readDescriptor(String classpath) throws Exception {
        try (InputStream stream = CrmStyleLayoutTest.class.getResourceAsStream(classpath)) {
            assertThat(stream)
                    .as("XML descriptor must be on test classpath: %s", classpath)
                    .isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementById(Document document, String id) {
        Element element = elementByIdOrNull(document, id);
        if (element == null) {
            throw new AssertionError("Element id not found in descriptor: " + id);
        }
        return element;
    }

    private static Element elementByIdOrNull(Document document, String id) {
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> directElementChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }
}
