package com.vn.agent.view.exposure;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.exposure.AiExposureRule;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.NotificationUtils;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * EXP-07 admin list view for {@link AiExposureRule}. Gated to {@code AiAgentAdminRole}
 * via {@code @ViewPolicy("AiAgent_AiExposureRule.list")} (Plan 10-03).
 *
 * <p>Toggle behaviour (CONTEXT.md D-10): the action-column button label/theme flips
 * between "Hide from AI" (enabled rule, primary theme) and "Visible to AI"
 * (disabled rule, success theme) based on the row's {@code enabled} flag. Single
 * click commits via {@link UnconstrainedDataManager} and reloads the grid.</p>
 *
 * <p><b>Fix R2 — single publish site:</b> this view does NOT publish
 * {@code LlmExposureChangedEvent}. {@code UnconstrainedDataManager.save(rule)}
 * fires Jmix {@code EntityChangedEvent}, which {@code AiExposureRuleEntityListener}
 * republishes as {@code LlmExposureChangedEvent}. A manual publish here would
 * cause the event to fire twice per toggle action — {@code ApplicationEventPublisher}
 * is intentionally NOT injected.</p>
 *
 * <p>{@link UnconstrainedDataManager} (not {@link io.jmix.core.DataManager}) is used
 * for the toggle save so that user-role tweaks cannot lock admins out of the
 * governance surface (D-10 / EXP-06, MEMORY {@code feedback_jmix_unconstrained_for_system_writes}).</p>
 */
@Route(value = "ai-agent/exposure-rules", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiExposureRule.list")
@ViewDescriptor(path = "ai-exposure-rule-list-view.xml")
public class AiExposureRuleListView extends StandardListView<AiExposureRule> {

    private static final Logger log = LoggerFactory.getLogger(AiExposureRuleListView.class);

    @ViewComponent
    private DataGrid<AiExposureRule> exposureRulesDataGrid;
    @ViewComponent
    private CollectionLoader<AiExposureRule> exposureRulesDl;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;
    @Autowired
    private UiComponents uiComponents;

    /**
     * Status badge renderer for the {@code enabled} column. Uses {@code key="enabled"}
     * (NOT {@code property="enabled"}) per MEMORY {@code feedback_jmix_dual_typed_field_column}
     * to avoid the Jmix metamodel collision on Boolean fields.
     */
    @Supply(to = "exposureRulesDataGrid.enabled", subject = "renderer")
    private Renderer<AiExposureRule> exposureRulesDataGridEnabledRenderer() {
        return DataGridRenderers.buildBadgeColumn(
                uiComponents,
                row -> messages.getMessage(Boolean.TRUE.equals(row.getEnabled())
                        ? "exposureRulesList.badge.active"
                        : "exposureRulesList.badge.inactive"),
                row -> Boolean.TRUE.equals(row.getEnabled()) ? "success" : "contrast");
    }

    /**
     * Action-column toggle button. Label/icon/theme flip based on row's
     * {@code enabled} flag. {@code @Supply} renderer per MEMORY
     * {@code feedback_jmix_action_column_renderer}.
     */
    @Supply(to = "exposureRulesDataGrid.toggleAction", subject = "renderer")
    private ComponentRenderer<Button, AiExposureRule> toggleRenderer() {
        return new ComponentRenderer<>(rule -> {
            Button btn = new Button();
            if (Boolean.TRUE.equals(rule.getEnabled())) {
                btn.setText(messages.getMessage(getClass(), "exposureRulesList.action.hideFromAi"));
                btn.setIcon(VaadinIcon.EYE_SLASH.create());
                btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                btn.getElement().setAttribute("title",
                        messages.getMessage(getClass(), "exposureRulesList.action.hideFromAi.tooltip"));
            } else {
                btn.setText(messages.getMessage(getClass(), "exposureRulesList.action.visibleToAi"));
                btn.setIcon(VaadinIcon.EYE.create());
                btn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
                btn.getElement().setAttribute("title",
                        messages.getMessage(getClass(), "exposureRulesList.action.visibleToAi.tooltip"));
            }
            btn.addClickListener(e -> toggleEnabled(rule));
            return btn;
        });
    }

    /**
     * Flip the enabled bit and persist via {@link UnconstrainedDataManager}.
     *
     * <p>Does NOT publish {@code LlmExposureChangedEvent} — see Fix R2 in class
     * Javadoc. {@code UnconstrainedDataManager.save(rule)} fires
     * {@code EntityChangedEvent} which {@code AiExposureRuleEntityListener}
     * republishes; double-publish would result if this method called
     * {@code applicationEventPublisher.publishEvent(...)}.</p>
     */
    private void toggleEnabled(AiExposureRule rule) {
        try {
            rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
            unconstrainedDataManager.save(rule);
            exposureRulesDl.load();
            notifications.create(messages.getMessage(getClass(),
                            Boolean.TRUE.equals(rule.getEnabled())
                                    ? "exposureRulesList.action.hideFromAi"
                                    : "exposureRulesList.action.visibleToAi"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("Toggle exposure rule failed for entity {}", rule.getEntityName(), ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "exposureRulesList.error.toggle", ex);
        }
    }
}
