package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.progressbar.ProgressBar;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelFragmentLoadingIndicatorTest {

    @Test
    void streamingUiState_showsProgressBarAndDisablesInput() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        MessageInput messageInput = new MessageInput();
        ProgressBar progressBar = new ProgressBar();
        inject(fragment, "messageInput", messageInput);
        inject(fragment, "streamProgressBar", progressBar);

        fragment.setStreamingUiState(true);

        assertThat(progressBar.isVisible()).isTrue();
        assertThat(messageInput.isEnabled()).isFalse();

        fragment.setStreamingUiState(false);

        assertThat(progressBar.isVisible()).isFalse();
        assertThat(messageInput.isEnabled()).isTrue();
    }

    private static void inject(ChatPanelFragment fragment, String fieldName, Object value) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(fragment, value);
    }
}
