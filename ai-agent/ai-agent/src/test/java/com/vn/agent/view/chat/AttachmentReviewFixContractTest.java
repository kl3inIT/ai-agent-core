package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level regression checks for the Phase 13.1 code-review fixes that are
 * awkward to exercise without a full Flow UI boot.
 */
class AttachmentReviewFixContractTest {

    private static final Path CHAT_PANEL_FRAGMENT = Path.of(
            "src", "main", "java", "com", "vn", "agent", "view", "chat", "fragment",
            "ChatPanelFragment.java");

    @Test
    void uploadValidationUsesStagedFileSizeForCapAndPersistedRow() throws IOException {
        String source = Files.readString(CHAT_PANEL_FRAGMENT);

        assertThat(source)
                .as("server-side upload cap must measure the actual staged file")
                .contains("Files.size(tempFile)");
        assertThat(source)
                .as("persisted AiTaskFile.sizeBytes must use the measured file size")
                .contains("row.setSizeBytes(actualSizeBytes)");
    }

    @Test
    void cardDeleteIsBlobFirstAndRefreshesOwningLoader() throws IOException {
        // Inline-attachments: the card renderer was retired; delete now lives in the fragment
        // (AiTaskFileInlineCard is a pure renderer that calls back into ChatPanelFragment).
        String chatPanel = Files.readString(CHAT_PANEL_FRAGMENT);

        assertThat(chatPanel.indexOf("storage.removeFile(ref)"))
                .as("blob delete must happen before DB row delete")
                .isLessThan(chatPanel.indexOf("dataManager.remove(file)"));
        assertThat(chatPanel)
                .as("successful delete must notify other open UIs")
                .contains("UiEventPublisher")
                .contains("AiTaskFileDeletedUiEvent");

        assertThat(chatPanel)
                .as("chat panel must listen for card delete events and reload the collection loader")
                .contains("@EventListener")
                .contains("onTaskFileDeleted")
                .contains("taskFilesDl.load()");
    }
}
