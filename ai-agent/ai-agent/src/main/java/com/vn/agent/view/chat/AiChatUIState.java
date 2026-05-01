package com.vn.agent.view.chat;

import com.vaadin.flow.spring.annotation.UIScope;
import io.jmix.flowui.view.DialogWindow;
import org.springframework.stereotype.Component;

@Component
@UIScope
public class AiChatUIState {

    private DialogWindow<?> dialogInstance;

    public DialogWindow<?> getDialogInstance() {
        return dialogInstance;
    }

    public void setDialogInstance(DialogWindow<?> dialogInstance) {
        this.dialogInstance = dialogInstance;
    }

    public void clearDialogInstance() {
        this.dialogInstance = null;
    }
}
