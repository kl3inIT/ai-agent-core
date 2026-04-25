package com.vn.jmixapp.view.util;

import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vn.jmixapp.entity.User;
import com.vn.jmixapp.enums.ActionColumnType;
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

    public static <T> Renderer<T> buildActionsColumn(UiComponents uiComponents,
                                                     Set<ActionColumnType> actionColumnTypes,
                                                     BiConsumer<T, ActionColumnType> onAction) {
        return new ComponentRenderer<>(item -> {
            HorizontalLayout actionLayout = uiComponents.create(HorizontalLayout.class);
            actionLayout.addClassNames("row-actions", "my-grid-row-action");
            actionLayout.setWidthFull();

            for (ActionColumnType actionType : actionColumnTypes) {
                Icon icon = resolveIcon(item, actionType).create();
                icon.addClassName("my-grid-row-button");
                icon.addClickListener(event -> onAction.accept(item, actionType));
                actionLayout.add(icon);
            }

            return actionLayout;
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

            int firstResult = 0;
            if (pagination != null) {
                firstResult = pagination.getPaginationLoader().getFirstResult();
            }

            return String.valueOf(firstResult + rowIndex + 1);
        });
    }

    private static <T> VaadinIcon resolveIcon(T item, ActionColumnType actionType) {
        return switch (actionType) {
            case VIEW -> VaadinIcon.EYE;
            case EDIT -> VaadinIcon.EDIT;
            case SETTING -> VaadinIcon.COG;
            case DELETE -> VaadinIcon.TRASH;
            case RELOAD -> VaadinIcon.REFRESH;
            case COPY -> VaadinIcon.COPY_O;
            case LOCK -> resolveLockIcon(item);
        };
    }

    private static <T> VaadinIcon resolveLockIcon(T item) {
        if (item instanceof User user) {
            return Boolean.TRUE.equals(user.getActive()) ? VaadinIcon.LOCK : VaadinIcon.UNLOCK;
        }
        return VaadinIcon.LOCK;
    }
}
