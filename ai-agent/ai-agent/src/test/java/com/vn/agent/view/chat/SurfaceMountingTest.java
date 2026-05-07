package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 Plan 07 — REQ-7 / CONTRACT-01 surface-mounting + contract-zero-diff regression.
 *
 * <p>Two contracts under test:
 * <ol>
 *   <li><b>Both surface descriptors mount the same fragment</b> — {@code chat-view.xml}
 *   (FULL_ROUTE) and {@code chat-dialog-view.xml} (HEADER_BUTTON) MUST both compose
 *   {@code ChatPanelFragment} with NO surface-specific overrides on the slot ids the
 *   reshaped fragment exposes. Asserts that the right-pane Attachments panel shows up
 *   identically on both surfaces because they delegate to the same fragment.</li>
 *   <li><b>Phase 12 surface contract stays intact</b> — the 5 files frozen by the
 *   Phase 12 plan ({@code ChatSurfaceMounter.java}, {@code AiUiSettingsService.java},
 *   {@code AiUiSettings.java}, {@code ChatView.java}, {@code ChatDialogView.java}) keep
 *   the required mount/menu/dialog/settings markers. Later UAT fixes are allowed to
 *   polish the dialog implementation, but not to remove the Phase 12 surface contract.</li>
 * </ol>
 *
 * <p><b>Why no {@code @UiTest}:</b> the module-level {@code @SpringBootTest} boot
 * regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBoundsException) blocks
 * runtime of every {@code @UiTest} that boots an agentstore Spring context. The shape
 * we guard here is fully expressed in source — no live UI render is required.
 */
class SurfaceMountingTest {

    private static final String CHAT_PANEL_FRAGMENT_FQN =
            "com.vn.agent.view.chat.fragment.ChatPanelFragment";

    private static final List<String> PHASE_12_CONTRACT_FILES = List.of(
            "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java",
            "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/AiUiSettingsService.java",
            "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java",
            "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java",
            "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java");

    @Test
    void bothSurfacesMountTheReshapedChatPanelFragment() throws Exception {
        // FULL_ROUTE surface — chat-view.xml.
        Document fullRoute = readDescriptor("/com/vn/agent/view/chat/chat-view.xml");
        Element fullRouteFragment = elementById(fullRoute, "chatPanelFragment");
        assertThat(fullRouteFragment.getTagName()).isEqualTo("fragment");
        assertThat(fullRouteFragment.getAttribute("class"))
                .as("FULL_ROUTE surface must mount ChatPanelFragment with no override")
                .isEqualTo(CHAT_PANEL_FRAGMENT_FQN);

        // HEADER_BUTTON surface — chat-dialog-view.xml.
        Document headerButton = readDescriptor("/com/vn/agent/view/chat/chat-dialog-view.xml");
        Element headerButtonFragment = elementById(headerButton, "chatPanelFragment");
        assertThat(headerButtonFragment.getTagName()).isEqualTo("fragment");
        assertThat(headerButtonFragment.getAttribute("class"))
                .as("HEADER_BUTTON surface must mount ChatPanelFragment with no override")
                .isEqualTo(CHAT_PANEL_FRAGMENT_FQN);

        // Neither surface declares its own attachmentsPanel / messageListSlot / messageInputSlot
        // override — both delegate to the same fragment, so the reshaped layout is identical.
        for (String slotId : List.of(
                "attachmentsPanel",
                "messageListSlot",
                "messageInputSlot",
                "attachmentsGridLayout",
                "taskFileUpload")) {
            assertThat(elementByIdOrNull(fullRoute, slotId))
                    .as("FULL_ROUTE surface must not redeclare slot id %s", slotId)
                    .isNull();
            assertThat(elementByIdOrNull(headerButton, slotId))
                    .as("HEADER_BUTTON surface must not redeclare slot id %s", slotId)
                    .isNull();
        }
    }

    @Test
    void phase12ContractFilesPreserveSurfaceContractMarkers() throws Exception {
        Path repoRoot = locateRepoRoot();

        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(0)),
                "class ChatSurfaceMounter",
                "implements VaadinServiceInitListener",
                "AiAgent_ChatDialog",
                "addUIInitListener",
                "mountHeaderButton",
                "openDialog",
                "shouldShowHeaderButton",
                "shouldShowFullRouteMenu");
        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(1)),
                "class AiUiSettingsService",
                "loadCurrent");
        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(2)),
                "class AiUiSettings",
                "enabledSurfaceIds",
                "defaultSurface");
        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(3)),
                "class ChatView",
                "@ViewController",
                "AiAgent_Chat",
                "fullRouteDisabled");
        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(4)),
                "class ChatDialogView",
                "@ViewController",
                "AiAgent_ChatDialog");
    }

    // ------------------------------- helpers --------------------------------

    private static Document readDescriptor(String classpath) throws Exception {
        try (InputStream stream = SurfaceMountingTest.class.getResourceAsStream(classpath)) {
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

    /**
     * Walk up from the test's working directory until we find a {@code .git} entry — that
     * is the repo root that holds the 5 contract files at the documented paths. Falls back
     * to {@code System.getProperty("user.dir")} if no {@code .git} is found within 6 levels.
     */
    private static Path locateRepoRoot() {
        Path cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            Path parent = cursor.getParent();
            if (parent == null) {
                break;
            }
            cursor = parent;
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static void assertContractMarkers(Path file, String... requiredSubstrings) throws IOException {
        assertThat(Files.exists(file))
                .as("Phase 12 contract file must still exist: %s", file)
                .isTrue();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String marker : requiredSubstrings) {
            assertThat(source)
                    .as("Phase 12 contract marker %s missing from %s — file may have been "
                            + "destructively edited by Phase 13.1", marker, file)
                    .contains(marker);
        }
    }
}
