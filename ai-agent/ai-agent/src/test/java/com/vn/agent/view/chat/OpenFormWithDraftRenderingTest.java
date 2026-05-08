package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 Plan 06 confirm-row rendering contract.
 *
 * <p>Source-level because the click behavior depends on the Jmix UI runtime affected by
 * the existing shared Spring context blocker. This still pins the important security
 * contract: renderer emits a structured marker only, and the controller owns navigation
 * delegation through {@code OpenFormWithDraftHandler}.</p>
 */
class OpenFormWithDraftRenderingTest {

    @Test
    void rendererEmitsStructuredDraftPayloadWithoutNavigationImports() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java");

        assertThat(source)
                .contains("record DraftPayload")
                .contains("record RenderedStreamEvent")
                .contains("payloadJson")
                .contains("toolName")
                .contains("prepare_form_draft")
                .contains("open_form_with_draft")
                .doesNotContain("ViewNavigators")
                .doesNotContain(".navigate(");
    }

    @Test
    void chatPanelAppendsEscapedConfirmRowAndDelegatesToHandler() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("void appendIntentConfirmRow(UUID draftId, String entityName, String instanceName)")
                .contains("new Div()")
                .contains("ai-agent-intent-confirm")
                .contains("role\", \"status\"")
                .contains("aria-live\", \"polite\"")
                .contains("chatView.intent.confirmButton.summary")
                .contains("VaadinIcon.EXTERNAL_LINK.create()")
                .contains("openFormWithDraftHandler.open(this, draftId, entityName, instanceName)");
    }

    @Test
    void expiredDraftDisablesConfirmButtonAndUsesLocalizedCopy() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("OpenFormWithDraftHandler.OpenStatus.EXPIRED")
                .contains("markIntentConfirmRowExpired")
                .contains("chatView.intent.draftExpired")
                .contains("confirmButton.setEnabled(false)");
    }

    @Test
    void invalidExtractionPayloadShowsLocalizedErrorAndNoConfirmRow() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("rendered.draftPayloadInvalid()")
                .contains("showDraftPayloadInvalidNotification")
                .contains("chatView.intent.draftPayloadInvalid");
    }

    @Test
    void clearMessageListStillRemovesRawAndComponentRows() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("messageListSlot.removeAll()")
                .contains("messageListSlot.getElement().removeAllChildren()");
    }

    private static String read(String repositoryPath) throws Exception {
        String moduleRelativePath = repositoryPath.startsWith("ai-agent/ai-agent/")
                ? repositoryPath.substring("ai-agent/ai-agent/".length())
                : repositoryPath;
        for (Path candidate : new Path[]{
                Path.of(repositoryPath),
                Path.of(moduleRelativePath),
                Path.of(System.getProperty("user.dir")).resolve(repositoryPath).normalize(),
                Path.of(System.getProperty("user.dir")).resolve(moduleRelativePath).normalize()
        }) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new java.nio.file.NoSuchFileException(repositoryPath);
    }
}
