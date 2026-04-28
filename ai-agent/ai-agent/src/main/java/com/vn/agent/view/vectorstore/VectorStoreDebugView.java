package com.vn.agent.view.vectorstore;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.Messages;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * EXP-07 / D-09 admin-only Vector Store debug view. Read-only paginated grid over
 * vector-store chunks using {@link VectorStore#similaritySearch(SearchRequest)} with
 * an empty-string query, similarity threshold 0.0, and a load-more {@code topK}
 * window. Filter input is parsed via Spring AI's {@link FilterExpressionTextParser};
 * parse errors surface as inline field validation, NOT as a toast or 500.
 *
 * <p>Gated to {@code AiAgentAdminRole} via {@code @ViewPolicy("AiAgent_VectorStoreDebug")}
 * (Plan 10-03). Admin bypass in {@code RetrievalFilterBuilder} returns null for admin
 * users — admins see all chunks here regardless of denylist.</p>
 *
 * <p><b>Fix R7 — grid component choice:</b> uses plain Vaadin {@link Grid}{@code <Document>}
 * rather than the Jmix metaclass-driven grid. {@link Document} is a Spring AI POJO with
 * no Jmix metaclass, so a Jmix metamodel-driven grid bound to a
 * {@code CollectionContainer<Document>} would fail metaclass lookup at compile or
 * render time. Columns are added programmatically only — the XML declares a plain
 * {@code <vbox id="chunksContainer">} placeholder.</p>
 *
 * <p><b>Fix W2 — metadataFilterField injection type:</b> Jmix {@code <textField>} XML
 * elements are backed by {@link TypedTextField}, NOT raw Vaadin {@code TextField}. Using
 * the Vaadin type for the {@code @ViewComponent} declaration causes injection failure at
 * runtime ({@code TypedTextField<String>.getValue()} returns {@link String} so the
 * call-site shape is unchanged).</p>
 *
 * <p>Read-only: NO edit, delete, or re-embed actions in v1.1 (CONTEXT D-09 — debug
 * surface only). The "expand" buttons open a standard Vaadin {@link Dialog} (NOT Jmix
 * {@code DialogWindows} which require a Jmix entity).</p>
 */
@Route(value = "ai-agent/vector-store-debug", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_VectorStoreDebug")
@ViewDescriptor(path = "vector-store-debug-view.xml")
public class VectorStoreDebugView extends StandardView {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreDebugView.class);

    private static final int PAGE_SIZE = 100;
    private static final int CONTENT_PREVIEW_LIMIT = 120;
    private static final int METADATA_PREVIEW_LIMIT = 80;

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private Messages messages;

    // Fix W2: Jmix <textField> XML element maps to TypedTextField<String>, NOT Vaadin TextField.
    @ViewComponent
    private TypedTextField<String> metadataFilterField;
    @ViewComponent
    private Button filterClearBtn;
    @ViewComponent
    private Button filterHelpBtn;
    @ViewComponent
    private Button loadMoreBtn;
    @ViewComponent
    private VerticalLayout chunksContainer;

    // Fix R7: plain Vaadin Grid<Document>, not the Jmix metamodel-driven grid.
    private Grid<Document> chunksGrid;
    private int loadedLimit = PAGE_SIZE;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Accessibility: aria labels for icon-only buttons (UI-SPEC Surface 4 requirement).
        filterClearBtn.setAriaLabel(
                messages.getMessage("vectorStoreDebug.filter.clear"));
        filterHelpBtn.setAriaLabel(
                messages.getMessage("vectorStoreDebug.filter.help"));
        // Help button surfaces the filter syntax hint as a tooltip rather than opening a dialog
        // — keeps the read-only debug surface lightweight.
        filterHelpBtn.getElement().setAttribute("title",
                messages.getMessage("vectorStoreDebug.filter.help.tooltip"));

        // Fix R7: programmatic Grid<Document> — columns added programmatically, no metaclass binding.
        chunksGrid = new Grid<>(Document.class, false);
        chunksGrid.setWidth("100%");
        chunksGrid.setMinHeight("20em");
        chunksGrid.addThemeNames("compact");

        // Column 1: chunk id (value column).
        chunksGrid.addColumn(Document::getId)
                .setKey("id")
                .setHeader(messages.getMessage("vectorStoreDebug.column.id"))
                .setWidth("260px")
                .setFlexGrow(0)
                .setResizable(true);

        // Column 2: content preview + expand button when truncated (component column).
        chunksGrid.addColumn(new ComponentRenderer<>(this::buildContentCell))
                .setKey("content")
                .setHeader(messages.getMessage("vectorStoreDebug.column.content"))
                .setResizable(true);

        // Column 3: metadata preview + expand button when truncated (component column).
        chunksGrid.addColumn(new ComponentRenderer<>(this::buildMetadataCell))
                .setKey("metadata")
                .setHeader(messages.getMessage("vectorStoreDebug.column.metadata"))
                .setResizable(true);

        chunksContainer.add(chunksGrid);
    }

    /**
     * Run a similarity search with the parsed filter expression. Empty filter input
     * returns all chunks (subject to the topK cap). Parse errors surface inline on the
     * filter field — never as a stack trace or 500.
     */
    @Subscribe("searchBtn")
    public void onSearchBtnClick(final ClickEvent<Button> event) {
        loadedLimit = PAGE_SIZE;
        loadChunks();
    }

    @Subscribe("loadMoreBtn")
    public void onLoadMoreBtnClick(final ClickEvent<Button> event) {
        loadedLimit += PAGE_SIZE;
        loadChunks();
    }

    private void loadChunks() {
        metadataFilterField.setErrorMessage(null);
        String filterText = metadataFilterField.getValue();

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query("")
                .topK(loadedLimit)
                .similarityThreshold(0.0);

        if (filterText != null && !filterText.isBlank()) {
            try {
                Filter.Expression expression = new FilterExpressionTextParser().parse(filterText);
                requestBuilder.filterExpression(expression);
            } catch (Exception ex) {
                log.debug("Vector store debug filter parse failed for input '{}'", filterText, ex);
                metadataFilterField.setErrorMessage(
                        messages.getMessage("vectorStoreDebug.error.filterParse"));
                return;
            }
        }

        List<Document> documents = vectorStore.similaritySearch(requestBuilder.build());
        List<Document> safeDocuments = documents != null ? documents : List.of();
        chunksGrid.setItems(safeDocuments);
        loadMoreBtn.setEnabled(safeDocuments.size() >= loadedLimit);
    }

    @Subscribe("filterClearBtn")
    public void onFilterClearBtnClick(final ClickEvent<Button> event) {
        metadataFilterField.clear();
        metadataFilterField.setErrorMessage(null);
    }

    private Component buildContentCell(Document doc) {
        String full = doc.getText() != null ? doc.getText() : "";
        return buildPreviewCell(doc, full, CONTENT_PREVIEW_LIMIT);
    }

    private Component buildMetadataCell(Document doc) {
        String metaStr = doc.getMetadata() != null ? doc.getMetadata().toString() : "{}";
        return buildPreviewCell(doc, metaStr, METADATA_PREVIEW_LIMIT);
    }

    private Component buildPreviewCell(Document doc, String fullText, int limit) {
        String preview = fullText.length() > limit
                ? fullText.substring(0, limit) + "..."
                : fullText;
        Span span = new Span(preview);
        if (fullText.length() <= limit) {
            return span;
        }
        Button expand = new Button(
                messages.getMessage("vectorStoreDebug.action.expand"));
        expand.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        expand.addClickListener(e -> openChunkDetail(doc));
        HorizontalLayout layout = new HorizontalLayout(span, expand);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        layout.setSpacing(true);
        return layout;
    }

    /**
     * Open a standard Vaadin {@link Dialog} showing the chunk's full content and metadata
     * in read-only {@link TextArea} components. NOT Jmix {@code DialogWindows} — that
     * facility expects a Jmix entity, while {@link Document} is a Spring AI POJO.
     */
    private void openChunkDetail(Document doc) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(
                messages.getMessage("vectorStoreDebug.detail.title"));
        dialog.setWidth("640px");

        TextArea contentArea = new TextArea(
                messages.getMessage("vectorStoreDebug.column.content"));
        contentArea.setValue(doc.getText() != null ? doc.getText() : "");
        contentArea.setMinRows(5);
        contentArea.setMaxRows(20);
        contentArea.setReadOnly(true);
        contentArea.setWidthFull();

        TextArea metaArea = new TextArea(
                messages.getMessage("vectorStoreDebug.column.metadata"));
        metaArea.setValue(doc.getMetadata() != null ? doc.getMetadata().toString() : "{}");
        metaArea.setMinRows(5);
        metaArea.setMaxRows(10);
        metaArea.setReadOnly(true);
        metaArea.setWidthFull();

        VerticalLayout content = new VerticalLayout(contentArea, metaArea);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        dialog.add(content);

        Button closeBtn = new Button(
                messages.getMessage("vectorStoreDebug.detail.close"),
                e -> dialog.close());
        dialog.getFooter().add(closeBtn);
        dialog.open();
    }
}
