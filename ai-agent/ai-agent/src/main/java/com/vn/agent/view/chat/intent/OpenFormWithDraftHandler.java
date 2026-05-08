package com.vn.agent.view.chat.intent;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.DraftApplyResult;
import com.vn.agent.extraction.DraftLoader;
import com.vn.agent.extraction.ExtractionDraftAccess;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.exception.NoSuchViewException;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;

import java.util.Optional;
import java.util.UUID;

/**
 * UI-side owner for opening a Jmix detail view from an extraction draft payload.
 *
 * <p>This class is intentionally the only chat-intent class that knows about
 * {@link ViewNavigators}. LLM tools return payloads only; navigation stays in the current
 * user's Flow UI session and is guarded by Jmix view permissions.</p>
 */
@org.springframework.stereotype.Component
@VaadinSessionScope
public class OpenFormWithDraftHandler {

    private final ViewNavigators viewNavigators;
    private final AccessManager accessManager;
    private final ViewRegistry viewRegistry;
    private final Metadata metadata;
    private final DataManager dataManager;
    private final ExtractionDraftAccess extractionDraftAccess;
    private final DraftLoader draftLoader;
    private final Messages messages;
    private final Notifications notifications;

    public OpenFormWithDraftHandler(ViewNavigators viewNavigators,
                                    AccessManager accessManager,
                                    ViewRegistry viewRegistry,
                                    Metadata metadata,
                                    DataManager dataManager,
                                    ExtractionDraftAccess extractionDraftAccess,
                                    DraftLoader draftLoader,
                                    Messages messages,
                                    Notifications notifications) {
        this.viewNavigators = viewNavigators;
        this.accessManager = accessManager;
        this.viewRegistry = viewRegistry;
        this.metadata = metadata;
        this.dataManager = dataManager;
        this.extractionDraftAccess = extractionDraftAccess;
        this.draftLoader = draftLoader;
        this.messages = messages;
        this.notifications = notifications;
    }

    public OpenResult open(Component hostComponent, UUID draftId, String entityName, String instanceName) {
        return open(UiComponentUtils.getView(hostComponent), draftId, entityName, instanceName);
    }

    public OpenResult open(View<?> originView, UUID draftId, String entityName, String instanceName) {
        Optional<AiExtractionDraft> draftOptional = loadDraft(draftId);
        if (draftOptional.isEmpty()) {
            showWarning("chatView.intent.draftExpired");
            return OpenResult.expired();
        }

        AiExtractionDraft draft = draftOptional.get();
        if (!draft.getTargetEntityName().equals(entityName)) {
            showConfigurationError();
            return OpenResult.configurationError();
        }

        MetaClass metaClass = metadata.getSession().findClass(entityName);
        if (metaClass == null || !draft.getTargetEntityName().equals(metaClass.getName())) {
            showConfigurationError();
            return OpenResult.configurationError();
        }

        ViewInfo detailViewInfo = resolveDetailViewInfo(metaClass);
        if (detailViewInfo == null) {
            showConfigurationError();
            return OpenResult.configurationError();
        }

        if (!isViewPermitted(detailViewInfo.getId()) || !isCreatePermitted(metaClass)) {
            notifications.create(messages.getMessage("chatView.intent.permissionDenied"))
                    .withType(Notifications.Type.ERROR)
                    .show();
            return OpenResult.denied();
        }

        Class<? extends View<?>> detailViewClass = detailViewInfo.getControllerClass();
        if (!StandardDetailView.class.isAssignableFrom(detailViewClass)) {
            showConfigurationError();
            return OpenResult.configurationError();
        }

        navigate(originView, metaClass.getJavaClass(), detailViewClass, draftId);
        return OpenResult.opened(instanceName);
    }

    private Optional<AiExtractionDraft> loadDraft(UUID draftId) {
        return extractionDraftAccess.loadOpenDraft(draftId);
    }

    private ViewInfo resolveDetailViewInfo(MetaClass metaClass) {
        try {
            return viewRegistry.getDetailViewInfo(metaClass);
        } catch (NoSuchViewException ignored) {
            return null;
        }
    }

    private boolean isViewPermitted(String viewId) {
        UiShowViewContext accessContext = new UiShowViewContext(viewId);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isPermitted();
    }

    private boolean isCreatePermitted(MetaClass metaClass) {
        CrudEntityContext accessContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isCreatePermitted();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void navigate(View<?> originView,
                          Class<?> entityClass,
                          Class<? extends View<?>> detailViewClass,
                          UUID draftId) {
        Class<Object> typedEntityClass = (Class) entityClass;
        Class<View<?>> typedDetailViewClass = (Class) detailViewClass;
        io.jmix.flowui.view.navigation.DetailViewClassNavigator<Object, View<?>> navigator =
                viewNavigators.detailView(originView, typedEntityClass)
                .newEntity()
                .withViewClass(typedDetailViewClass);
        navigator.withAfterNavigationHandler(event -> afterNavigation(event.getView(), draftId))
                .navigate();
    }

    private void afterNavigation(View<?> navigatedView, UUID draftId) {
        if (!(navigatedView instanceof StandardDetailView<?> detailView)) {
            showConfigurationError();
            return;
        }

        Object editedEntity = detailView.getEditedEntity();
        DraftApplyResult ignored = draftLoader.apply(draftId, editedEntity);
        DraftLifecycleRegistration lifecycleRegistration = new DraftLifecycleRegistration();
        lifecycleRegistration.afterSaveRegistration = ComponentUtil.addListener(
                detailView,
                StandardDetailView.AfterSaveEvent.class,
                event -> {
                    confirmAndDeleteDraft(draftId);
                    lifecycleRegistration.remove();
                });
        lifecycleRegistration.afterCloseRegistration = ComponentUtil.addListener(
                detailView,
                View.AfterCloseEvent.class,
                event -> lifecycleRegistration.remove());
    }

    private void confirmAndDeleteDraft(UUID draftId) {
        dataManager.load(AiExtractionDraft.class)
                .id(draftId)
                .optional()
                .ifPresent(draft -> {
                    draft.setConfirmed(true);
                    dataManager.save(draft);
                    dataManager.remove(draft);
                });
    }

    private void showConfigurationError() {
        showWarning("chatView.intent.configurationError");
    }

    private void showWarning(String messageKey) {
        notifications.create(messages.getMessage(messageKey))
                .withType(Notifications.Type.WARNING)
                .show();
    }

    public enum OpenStatus {
        OPENED,
        EXPIRED,
        DENIED,
        CONFIGURATION_ERROR
    }

    public record OpenResult(OpenStatus status, String instanceName) {
        static OpenResult opened(String instanceName) {
            return new OpenResult(OpenStatus.OPENED, instanceName);
        }

        static OpenResult expired() {
            return new OpenResult(OpenStatus.EXPIRED, null);
        }

        static OpenResult denied() {
            return new OpenResult(OpenStatus.DENIED, null);
        }

        static OpenResult configurationError() {
            return new OpenResult(OpenStatus.CONFIGURATION_ERROR, null);
        }
    }

    private static final class DraftLifecycleRegistration {
        private Registration afterSaveRegistration;
        private Registration afterCloseRegistration;
        private boolean removed;

        private void remove() {
            if (removed) {
                return;
            }
            removed = true;
            if (afterSaveRegistration != null) {
                afterSaveRegistration.remove();
            }
            if (afterCloseRegistration != null) {
                afterCloseRegistration.remove();
            }
        }
    }
}
