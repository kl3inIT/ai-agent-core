package com.vn.agent.utils;

import com.vaadin.flow.component.notification.NotificationVariant;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;

public final class NotificationUtils {

    private NotificationUtils() {
    }

    public static void errorWithDetail(Notifications notifications,
                                       Messages messages,
                                       String messageKey,
                                       String detail) {
        String message = messages.getMessage(messageKey);
        if (detail != null && !detail.isBlank()) {
            message = message + " " + detail;
        }
        notifications.create(message)
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .show();
    }

    public static void errorWithDetail(Notifications notifications,
                                       Messages messages,
                                       String messageKey,
                                       Exception ex) {
        errorWithDetail(notifications, messages, messageKey, ex == null ? null : ex.getMessage());
    }
}
