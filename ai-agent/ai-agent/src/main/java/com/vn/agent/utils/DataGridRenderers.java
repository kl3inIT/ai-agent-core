package com.vn.agent.utils;

import com.vaadin.flow.component.html.Span;
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
import java.util.function.Function;

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

    /**
     * Builds a Vaadin Badge column. {@code themeFn} returns a space-separated list of Lumo
     * badge variants (e.g. {@code "success"}, {@code "error"}, {@code "contrast tertiary"}).
     * {@code decorate} lets callers set extra per-row attributes (tooltip, etc.) or reset
     * them when the row state no longer requires them — it is invoked on every render pass,
     * so both branches of a conditional must be handled.
     */
    public static <T> Renderer<T> buildBadgeColumn(UiComponents uiComponents,
                                                   Function<T, String> textFn,
                                                   Function<T, String> themeFn,
                                                   BiConsumer<Span, T> decorate) {
        return new ComponentRenderer<>(
                () -> {
                    Span badge = uiComponents.create(Span.class);
                    badge.getElement().getThemeList().add("badge");
                    return badge;
                },
                (badge, row) -> {
                    badge.setText(textFn.apply(row));
                    badge.getElement().getThemeList().clear();
                    badge.getElement().getThemeList().add("badge");
                    String variants = themeFn.apply(row);
                    if (variants != null) {
                        for (String variant : variants.split(" ")) {
                            if (!variant.isBlank()) {
                                badge.getElement().getThemeList().add(variant);
                            }
                        }
                    }
                    if (decorate != null) {
                        decorate.accept(badge, row);
                    }
                });
    }

    public static <T> Renderer<T> buildBadgeColumn(UiComponents uiComponents,
                                                   Function<T, String> textFn,
                                                   Function<T, String> themeFn) {
        return buildBadgeColumn(uiComponents, textFn, themeFn, null);
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
