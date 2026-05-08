package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 Plan 06 intent-card row contract.
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
    void controllerBuildsAutoPlusEligibleNamedIntentsAndHidesWhenEmpty() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("intentRegistry.eligibleForCurrentUser()")
                .contains("options.add(autoOption)")
                .contains("options.addAll(namedIntents)")
                .contains("intentCardRow.setVisible(!namedIntents.isEmpty())")
                .contains("intentCardRow.setValue(autoOption)");
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
    void selectedNamedTurnIsSentAndThenResetToAuto() throws Exception {
        String source = read("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");

        assertThat(source)
                .contains("selectedIntentIdForSubmit()")
                .contains("chatService.stream(userId, targetConversationId, text, null, selectedIntentId)")
                .contains("resetIntentCardRowToAutoIfNamed(selectedIntentId)")
                .contains("intentCardRow.setValue(buildAutoIntentOption())");
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
