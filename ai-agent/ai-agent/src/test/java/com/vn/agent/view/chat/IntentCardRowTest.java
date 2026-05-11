package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 intent-row contract after the gap-closure UX shift.
 *
 * <p>Uses XML/source checks instead of {@code @UiTest}: the shared module still has
 * the pre-existing agentstore Spring context boot blocker documented by prior phase
 * summaries, while the intent row behavior is expressed directly in descriptor and
 * controller contracts.</p>
 */
class IntentCardRowTest {

    @Test
    void descriptorPlacesIntentCardRowBetweenMessagesAndInput() throws Exception {
        String xml = read("ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml");

        assertThat(xml)
                .contains("<radioButtonGroup id=\"intentCardRow\"")
                .contains("classNames=\"ai-agent-intent-card-row\"")
                .contains("themeNames=\"horizontal\"")
                .contains("visible=\"false\"")
                .contains("ariaLabel=\"msg:///chatView.intent.cardRow.ariaLabel\"");

        assertThat(xml.indexOf("id=\"messageListSlot\""))
                .isLessThan(xml.indexOf("id=\"intentCardRow\""));
        assertThat(xml.indexOf("id=\"intentCardRow\""))
                .isLessThan(xml.indexOf("id=\"messageInputSlot\""));
    }

    @Test
    void controllerStillKnowsNamedIntentsButReadyStateKeepsInitialRowHidden() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("intentRegistry.eligibleForCurrentUser()")
                .contains("options.add(autoOption)")
                .contains("options.addAll(namedIntents)")
                .contains("hideInitialIntentCardRow();")
                .contains("intentCardRow.setVisible(false)")
                .contains("intentCardRow.setValue(buildAutoIntentOption())");
    }

    @Test
    void controllerUsesJmixSupplyRendererAndSelectedCardClass() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("@Supply(to = \"intentCardRow\", subject = \"renderer\")")
                .contains("new ComponentRenderer<>(this::createIntentCard)")
                .contains("uiComponents.create(JmixCard.class)")
                .contains("CardVariant.LUMO_OUTLINED")
                .contains("ai-agent-intent-card--selected")
                .contains("@Subscribe(\"intentCardRow\")");
    }

    @Test
    void firstScreenNoLongerRefreshesStaticIntentChoices() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        String onReadyBody = source.substring(source.indexOf("public void onReady("),
                source.indexOf("@Supply(to = \"intentCardRow\", subject = \"renderer\")"));

        assertThat(onReadyBody)
                .contains("hideInitialIntentCardRow();")
                .doesNotContain("refreshIntentCardRow();");

        assertThat(source)
                .contains("submitChatTurn(text, text, selectedIntentIdForSubmit())")
                .contains("final UUID targetConversationId = conversationId")
                .contains("chatService.stream(userId, targetConversationId, modelText,")
                .contains("null, toolSurfaceIntentId, privateSystemAppendix)")
                .contains("resetIntentCardRowToAutoIfNamed(toolSurfaceIntentId)")
                .contains("intentCardRow.setValue(buildAutoIntentOption())")
                .doesNotContain("ensureConversationIdForSubmit(userId, text)");
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
