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
    private static final Path CARD_RENDERER = Path.of(
            "src", "main", "java", "com", "vn", "agent", "view", "chat", "fragment",
            "AiTaskFileCardFragmentRenderer.java");

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
        String renderer = Files.readString(CARD_RENDERER);
        String chatPanel = Files.readString(CHAT_PANEL_FRAGMENT);

        assertThat(renderer)
                .as("renderer must use root-bundle Messages directly rather than fragment MessageBundle "
                        + "or a stale message group constant")
                .contains("private String message(String key)")
                .contains("return messages.getMessage(key);")
                .doesNotContain("MessageBundle")
                .doesNotContain("MESSAGE_GROUP");
        assertThat(renderer.indexOf("storage.removeFile(ref)"))
                .as("blob delete must happen before DB row delete")
                .isLessThan(renderer.indexOf("dataManager.remove(row)"));
        assertThat(renderer)
                .as("successful delete must notify the owning UI")
                .contains("UiEventPublisher")
                .contains("AiTaskFileDeletedUiEvent");

        assertThat(chatPanel)
                .as("chat panel must listen for card delete events and reload the collection loader")
                .contains("@EventListener")
                .contains("onTaskFileDeleted")
                .contains("taskFilesDl.load()");
    }
}
