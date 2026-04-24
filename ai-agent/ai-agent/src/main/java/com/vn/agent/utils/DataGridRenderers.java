package com.vn.agent.utils;

import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.pagination.SimplePagination;
import io.jmix.flowui.data.grid.ContainerDataGridItems;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public final class DataGridRenderers {

    private DataGridRenderers() {
    }

    public enum ActionColumnType {
        VIEW(VaadinIcon.EYE),
        EDIT(VaadinIcon.EDIT),
        SETTING(VaadinIcon.COG),
        DELETE(VaadinIcon.TRASH),
        RELOAD(VaadinIcon.REFRESH),
        COPY(VaadinIcon.COPY_O);

        private final VaadinIcon icon;

        ActionColumnType(VaadinIcon icon) {
            this.icon = icon;
        }

        public VaadinIcon icon() {
            return icon;
        }
    }

    public static <T> Renderer<T> buildActionsColumn(UiComponents uiComponents,
                                                     Set<ActionColumnType> actionColumnTypes,
                                                     BiConsumer<T, ActionColumnType> onAction) {
        return new ComponentRenderer<>(item -> {
            HorizontalLayout layout = uiComponents.create(HorizontalLayout.class);
            layout.addClassNames("row-actions", "my-grid-row-action");
            layout.setWidthFull();

            for (ActionColumnType actionType : actionColumnTypes) {
                Icon icon = actionType.icon().create();
                icon.addClassName("my-grid-row-button");
                icon.addClickListener(event -> onAction.accept(item, actionType));
                layout.add(icon);
            }
            return layout;
        });
    }

    public static <T> Renderer<T> buildIndexColumn(DataGrid<T> dataGrid, SimplePagination pagination) {
        return new TextRenderer<>(item -> {
            if (!(dataGrid.getItems() instanceof ContainerDataGridItems<T> containerItems)) {
                return "";
            }
            List<T> items = containerItems.getContainer().getItems();
            int rowIndex = items.indexOf(item);
            if (rowIndex < 0) {
                return "";
            }
            int firstResult = pagination == null ? 0
                    : pagination.getPaginationLoader().getFirstResult();
            return String.valueOf(firstResult + rowIndex + 1);
        });
    }
}
