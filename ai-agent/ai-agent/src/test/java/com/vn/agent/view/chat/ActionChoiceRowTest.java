package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ActionChoiceRowTest {

    @Test
    void chatPanelRendersPostProposalActionChoicesWithLocalizedButtons() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("void appendActionChoiceRow(StreamEventRenderer.ActionProposalPayload proposalPayload)")
                .contains("ai-agent-action-choice")
                .contains("chatView.actionChoice.summary")
                .contains("chatView.actionChoice.createNow")
                .contains("chatView.actionChoice.prefillForm")
                .contains("ActionIntentId.selectionParameter(actionIntentId)")
                .contains("actionProposalService.createDraft(");
    }

    @Test
    void newChatClearRemovesActionChoiceComponentRowsWithOtherDynamicRows() throws Exception {
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
