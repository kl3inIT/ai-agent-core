package com.vn.agent.utils;

import com.vaadin.flow.component.notification.NotificationVariant;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;

public final class NotificationUtils {

    private NotificationUtils() {
    }

    public static void showWithDetail(Notifications notifications,
                                      Messages messages,
                                      NotificationVariant variant,
                                      String messageKey,
                                      String detail) {
        String message = messages.getMessage(messageKey);
        if (detail != null && !detail.isBlank()) {
            message = message + " " + detail;
        }
        notifications.create(message).withThemeVariant(variant).show();
    }

    public static void errorWithDetail(Notifications notifications,
                                       Messages messages,
                                       String messageKey,
                                       String detail) {
        showWithDetail(notifications, messages, NotificationVariant.LUMO_ERROR, messageKey, detail);
    }

    public static void errorWithDetail(Notifications notifications,
                                       Messages messages,
                                       String messageKey,
                                       Exception ex) {
        errorWithDetail(notifications, messages, messageKey, ex == null ? null : ex.getMessage());
    }

    public static void warnWithDetail(Notifications notifications,
                                      Messages messages,
                                      String messageKey,
                                      Exception ex) {
        showWithDetail(notifications, messages, NotificationVariant.LUMO_WARNING,
                messageKey, ex == null ? null : ex.getMessage());
    }
}
