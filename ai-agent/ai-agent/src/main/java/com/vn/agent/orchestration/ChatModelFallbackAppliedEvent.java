package com.vn.agent.orchestration;

import org.springframework.context.ApplicationEvent;

import java.io.Serial;
import java.util.UUID;

/**
 * Phase 16 D-05 / MODEL-02 — published by {@code DefaultChatServiceImpl.executeBlockingTurn(...)}
 * after a bad-model turn was caught and successfully reissued against
 * {@code AiParametersResolver.fallbackModel()} (D-05 catch + one-shot reissue contract).
 *
 * <p>The user-facing chat surface ({@code ChatPanelFragment}) listens via {@code @EventListener},
 * filters by conversation id, and surfaces the {@code chat.notice.modelFallbackApplied} locale
 * key as a Vaadin {@link com.vaadin.flow.component.notification.Notification}. The audit row
 * for the failure is written separately by
 * {@code AuditWriter.writeAuditEvent(AuditKind.MODEL_VALIDATION_FAILURE, ...)}.
 *
 * <p>This event is intentionally a separate type from {@code AiSettingsChangedEvent}
 * (Plan 16-05) — the SecretRedactionInvariantsTest's single-publish-site source scan walks
 * for the literal {@code new AiSettingsChangedEvent} regex, so adding a new event type does
 * NOT violate the R2 invariant locked in 16-CONTEXT.
 *
 * @param runId            run id correlating to the failed-then-recovered chat turn (matches
 *                         both audit rows)
 * @param conversationId   conversation id; consumers filter by their currently-attached
 *                         conversation so an unrelated chat panel does not flash the
 *                         notification
 * @param offendingModel   the original model id from the active profile that the provider
 *                         rejected (admin-input, verbatim per P-22 — never the raw provider
 *                         response body)
 * @param fallbackModel    the {@code defaults.model()} value the reissue succeeded against
 */
public class ChatModelFallbackAppliedEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID runId;
    private final UUID conversationId;
    private final String offendingModel;
    private final String fallbackModel;

    public ChatModelFallbackAppliedEvent(Object source,
                                         UUID runId,
                                         UUID conversationId,
                                         String offendingModel,
                                         String fallbackModel) {
        super(source);
        this.runId = runId;
        this.conversationId = conversationId;
        this.offendingModel = offendingModel;
        this.fallbackModel = fallbackModel;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getOffendingModel() {
        return offendingModel;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }
}
