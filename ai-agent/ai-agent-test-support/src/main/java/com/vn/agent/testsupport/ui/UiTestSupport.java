package com.vn.agent.testsupport.ui;

import com.vaadin.flow.component.Component;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.select.JmixSelect;
import io.jmix.flowui.component.textfield.JmixIntegerField;
import io.jmix.flowui.component.textfield.JmixPasswordField;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;

import java.util.List;

/**
 * Static lookup helpers for Jmix Flow components in {@code @UiTest}-driven test classes.
 * Mirrors {@code com.insurance.common.test_support_ui.UiTestSupport} from the jmix-insurance
 * reference.
 */
public class UiTestSupport {

    public static JmixButton findButtonByText(Component parent, String text) {
        if (parent instanceof JmixButton button && text.equals(button.getText())) {
            return button;
        }
        for (Component child : parent.getChildren().toList()) {
            JmixButton found = findButtonByText(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> DataGrid<T> getDataGrid(View<?> view, String id) {
        return (DataGrid<T>) UiTestUtils.getComponent(view, id);
    }

    public static <T> List<T> getGridItems(View<?> view, String id) {
        DataGrid<T> grid = getDataGrid(view, id);
        DataGridItems<T> items = grid.getItems();
        if (items == null) {
            return List.of();
        }
        return items.getItems().stream().toList();
    }

    @SuppressWarnings("unchecked")
    public static <T> TypedTextField<T> getTextField(View<?> view, String id) {
        return (TypedTextField<T>) UiTestUtils.getComponent(view, id);
    }

    @SuppressWarnings("unchecked")
    public static <T> EntityComboBox<T> getComboBox(View<?> view, String id) {
        return (EntityComboBox<T>) UiTestUtils.getComponent(view, id);
    }

    @SuppressWarnings("unchecked")
    public static <T> JmixSelect<T> getSelect(View<?> view, String id) {
        return (JmixSelect<T>) UiTestUtils.getComponent(view, id);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Comparable<? super T>> TypedDatePicker<T> getDatePicker(View<?> view, String id) {
        return (TypedDatePicker<T>) UiTestUtils.getComponent(view, id);
    }

    public static JmixIntegerField getIntegerField(View<?> view, String id) {
        return (JmixIntegerField) UiTestUtils.getComponent(view, id);
    }

    public static JmixPasswordField getPasswordField(View<?> view, String id) {
        return (JmixPasswordField) UiTestUtils.getComponent(view, id);
    }
}
