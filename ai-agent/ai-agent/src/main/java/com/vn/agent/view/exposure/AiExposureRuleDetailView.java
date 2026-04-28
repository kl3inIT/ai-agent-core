package com.vn.agent.view.exposure;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.router.Route;
import com.vn.agent.exposure.AiExposureRule;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * EXP-07 admin detail view for {@link AiExposureRule}. Gated to {@code AiAgentAdminRole}
 * via {@code @ViewPolicy("AiAgent_AiExposureRule.detail")} (Plan 10-03).
 *
 * <p>Field shape (CONTEXT.md D-11, UI-SPEC Surface 2):
 * <ul>
 *     <li>{@code entityNameComboBox} — Vaadin {@link ComboBox}{@code <MetaClass>} populated
 *         from {@link MetaclassComboBoxHelper#buildFilteredList()} (excludes
 *         {@code @SystemLevel} and AI-* internals). NOT bound via {@code property=} because
 *         {@link MetaClass} is not a {@link String}; the selected metaclass name is written
 *         back onto {@link AiExposureRule#setEntityName(String)} via the value-change handler.</li>
 *     <li>{@code enabledCheckbox} — bound to {@code enabled} via
 *         {@code property="enabled"} on the checkbox (Boolean is not a dual-type issue).</li>
 *     <li>NO {@code attributePath} field — entity-level only per CONTEXT D-11.</li>
 *     <li>NO {@code mode} field — defaults silently to {@code EXCLUDE} per CONTEXT D-01.</li>
 * </ul>
 *
 * <p>Item label format mirrors the UI-SPEC contract:
 * {@code "{localizedLabel} ({metaClassName})"} — human-readable caption first, internal name
 * in parentheses for disambiguation.</p>
 */
@Route(value = "ai-agent/exposure-rules/:id", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiExposureRule.detail")
@ViewDescriptor(path = "ai-exposure-rule-detail-view.xml")
@EditedEntityContainer("exposureRuleDc")
public class AiExposureRuleDetailView extends StandardDetailView<AiExposureRule> {

    @ViewComponent
    private ComboBox<MetaClass> entityNameComboBox;
    @ViewComponent
    private InstanceContainer<AiExposureRule> exposureRuleDc;

    @Autowired
    private MetaclassComboBoxHelper metaclassComboBoxHelper;
    @Autowired
    private MessageTools messageTools;
    @Autowired
    private Metadata metadata;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Populate the entity-name dropdown with the filtered, sorted metaclass list.
        entityNameComboBox.setItems(metaclassComboBoxHelper.buildFilteredList());
        // Item label: human-readable caption first, then internal metaclass name in parens.
        entityNameComboBox.setItemLabelGenerator(
                mc -> messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")");
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // Pre-select the matching MetaClass when editing an existing rule. New rules have
        // a null entityName so the combo box stays unselected (placeholder visible).
        AiExposureRule rule = exposureRuleDc.getItemOrNull();
        if (rule == null || rule.getEntityName() == null) {
            return;
        }
        MetaClass mc = metadata.getSession().findClass(rule.getEntityName());
        if (mc != null) {
            entityNameComboBox.setValue(mc);
        }
    }

    @Subscribe("entityNameComboBox")
    public void onEntityNameComboBoxValueChange(
            final AbstractField.ComponentValueChangeEvent<ComboBox<MetaClass>, MetaClass> event) {
        // Mirror the selected MetaClass back onto the entity's String field.
        // Null is allowed (Jmix @NotNull validation will block save with a clear message).
        MetaClass selected = event.getValue();
        AiExposureRule rule = exposureRuleDc.getItemOrNull();
        if (rule == null) {
            return;
        }
        rule.setEntityName(selected == null ? null : selected.getName());
    }
}
