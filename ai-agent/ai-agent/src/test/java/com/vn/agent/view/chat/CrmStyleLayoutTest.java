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
 * Phase 13.1 Plan 07 — REQ-1 / UI-01 CRM-style layout DOM regression.
 *
 * <p>Asserts that the reshaped {@code chat-panel-fragment.xml} renders the documented
 * CRM split (left chat / right Attachments panel) with the correct slot ids, AND that
 * BOTH chat surface descriptors ({@code chat-view.xml} for FULL_ROUTE, {@code
 * chat-dialog-view.xml} for HEADER_BUTTON) compose the same {@link
 * com.vn.agent.view.chat.fragment.ChatPanelFragment} class so the layout is identical
 * regardless of which surface mounted it.
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
    void chatPanelFragmentRendersCrmStyleSplitWithRightPane() throws Exception {
        Document document = readDescriptor(FRAGMENT_DESCRIPTOR);

        // 1. Horizontal split with splitterPosition=68 (CRM verbatim port).
        Element split = elementById(document, "conversationSplit");
        assertThat(split.getTagName()).isEqualTo("split");
        assertThat(split.getAttribute("orientation")).isEqualTo("HORIZONTAL");
        assertThat(split.getAttribute("splitterPosition")).isEqualTo("68");

        // 2. Left pane contains the chat column with messageListSlot + messageInputSlot.
        Element chatPanel = elementById(document, "chatPanel");
        assertThat(chatPanel.getTagName()).isEqualTo("vbox");
        assertThat(elementByIdOrNull(document, "messageListSlot"))
                .as("messageListSlot is the substrate for mixed bubbles + NOTICE rows")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "messageInputSlot"))
                .as("messageInputSlot remains the Phase 12 STT integration site")
                .isNotNull();

        // 3. Right pane is the Attachments vbox — REQ-7 slot id contract.
        Element attachmentsPanel = elementById(document, "attachmentsPanel");
        assertThat(attachmentsPanel.getTagName())
                .as("Phase 12 ChatSurfaceMounter binds attachmentsPanel as a vbox slot")
                .isEqualTo("vbox");

        // 4. Right-pane child ids documented by SPEC §3.
        assertThat(elementByIdOrNull(document, "attachmentsTitle"))
                .as("right-pane title h3 (CRM verbatim slot id)")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "attachmentsEmptyState"))
                .as("empty-state vbox toggled on by refreshTaskFiles()")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "attachmentsGridLayout"))
                .as("right-pane gridLayout itemsContainer=taskFilesDc")
                .isNotNull();
        assertThat(elementByIdOrNull(document, "taskFileUpload"))
                .as("XML id rename from Phase 13 'upload' to taskFileUpload")
                .isNotNull();

        // 5. NO chip-strip residue (attachRow / attachButton) anywhere in the document.
        assertThat(elementByIdOrNull(document, "attachRow"))
                .as("Phase 13 inline-upload row must be retired (D-29)")
                .isNull();
        assertThat(elementByIdOrNull(document, "attachButton"))
                .as("Phase 13 paperclip button must be retired (D-29)")
                .isNull();

        // 6. Right-pane vbox must be a direct child of the split (CRM-verbatim shape).
        List<Element> splitChildren = directElementChildren(split);
        assertThat(splitChildren)
                .as("split must contain exactly chatPanel (left) and attachmentsPanel (right)")
                .extracting(e -> e.getAttribute("id"))
                .containsExactly("chatPanel", "attachmentsPanel");
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
