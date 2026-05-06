package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 *   <li><b>Phase 12 contract files have ZERO diff</b> — the 5 files frozen by the
 *   Phase 12 plan ({@code ChatSurfaceMounter.java}, {@code AiUiSettingsService.java},
 *   {@code AiUiSettings.java}, {@code ChatView.java}, {@code ChatDialogView.java}) MUST
 *   not be modified by Phase 13.1. The primary check uses {@code git diff} against
 *   {@code main}; if git is unavailable in CI (e.g. shallow clone), the test falls
 *   back to a structural sanity check that the documented Phase 12 contract markers
 *   are still present in each file.</li>
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
    void phase12ContractFilesAreUnchangedByPhase13Dot1() throws Exception {
        Path repoRoot = locateRepoRoot();

        // Primary path: git diff against main (and its remotes/origin) — empty output proves
        // Phase 13.1 made zero modifications to the 5 contract files.
        GitDiffOutcome diffOutcome = tryGitDiffAgainstMain(repoRoot);

        if (diffOutcome.executed) {
            assertThat(diffOutcome.changedFiles)
                    .as("Phase 13.1 must NOT modify any of the 5 Phase 12 contract files. "
                            + "Diff against %s reported these changes:%n  %s",
                            diffOutcome.baseRef, String.join("\n  ", diffOutcome.changedFiles))
                    .isEmpty();
            return;
        }

        // Fall-through: git unavailable in this environment (shallow clone, no .git, etc.).
        // Apply the structural sanity check the plan calls for: each of the 5 contract files
        // exists and still contains its documented Phase 12 contract markers, so a destructive
        // edit (file gutted / class renamed) surfaces here even without git.
        assertContractMarkers(repoRoot.resolve(PHASE_12_CONTRACT_FILES.get(0)),
                "class ChatSurfaceMounter",
                "implements VaadinServiceInitListener",
                "AiAgent_ChatDialog",
                "addUIInitListener");
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

    private record GitDiffOutcome(boolean executed, String baseRef, List<String> changedFiles) {}

    /**
     * Run {@code git diff --name-only <base>...HEAD -- <5-files>} for each candidate base ref
     * (origin/main, main). Return the first successful invocation. If neither ref resolves
     * (shallow clone, missing remote), return {@code executed=false} so the test falls through
     * to the structural marker check.
     */
    private static GitDiffOutcome tryGitDiffAgainstMain(Path repoRoot) {
        for (String base : List.of("origin/main", "main")) {
            try {
                List<String> command = new java.util.ArrayList<>(List.of(
                        "git", "diff", "--name-only", base + "...HEAD", "--"));
                command.addAll(PHASE_12_CONTRACT_FILES);
                ProcessBuilder pb = new ProcessBuilder(command)
                        .directory(repoRoot.toFile())
                        .redirectErrorStream(false);
                Process process = pb.start();

                List<String> stdoutLines;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    stdoutLines = reader.lines()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                }
                // Drain stderr so the process can exit even if it printed something.
                try (InputStream err = process.getErrorStream()) {
                    err.readAllBytes();
                }
                boolean finished = process.waitFor(15, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0) {
                    return new GitDiffOutcome(true, base, stdoutLines);
                }
            } catch (IOException | InterruptedException ignored) {
                // try next base ref
            }
        }
        return new GitDiffOutcome(false, null, List.of());
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
