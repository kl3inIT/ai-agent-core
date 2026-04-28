package com.vn.agent.view.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.rag.ChunkMetadata;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Full-page read-only detail view for a knowledge document.
 *
 * <p>The list view keeps only row-level actions; this screen owns the heavier detail surface:
 * persisted document metadata, access policy, source entity, and vector chunks linked by
 * {@link ChunkMetadata#DOCUMENT_ID}. Chunks are Spring AI {@link Document} POJOs, so the chunk grid
 * is a plain Vaadin {@link Grid} hosted inside the XML placeholder rather than a Jmix dataGrid.
 */
@Route(value = "ai-agent/knowledge-detail", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_KnowledgeDocument.detail")
@ViewDescriptor(path = "knowledge-document-detail-view.xml")
public class KnowledgeDocumentDetailView extends StandardView {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentDetailView.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final int CHUNK_LIMIT = 200;
  private static final int CONTENT_PREVIEW_LIMIT = 160;
  private static final int METADATA_PREVIEW_LIMIT = 120;
  private static final String EMPTY_VALUE = "-";

  @ViewComponent private Span documentTitle;
  @ViewComponent private Span statusBadge;
  @ViewComponent private TypedTextField<String> documentIdField;
  @ViewComponent private TypedTextField<String> fileNameField;
  @ViewComponent private TypedTextField<String> mimeTypeField;
  @ViewComponent private TypedTextField<String> sizeBytesField;
  @ViewComponent private TypedTextField<String> uploadedAtField;
  @ViewComponent private TypedTextField<String> ingestedAtField;
  @ViewComponent private TypedTextField<String> createdByField;
  @ViewComponent private JmixTextArea errorMessageField;
  @ViewComponent private TypedTextField<String> sourceEntityField;
  @ViewComponent private JmixTextArea allowedRolesField;
  @ViewComponent private Span chunksHeading;
  @ViewComponent private VerticalLayout chunksContainer;
  @ViewComponent private JmixTextArea chunkContentField;
  @ViewComponent private JmixTextArea chunkMetadataField;

  @Autowired private DataManager dataManager;
  @Autowired private Metadata metadata;
  @Autowired private MessageTools messageTools;
  @Autowired private Messages messages;
  @Autowired private VectorStore vectorStore;
  @Autowired private ViewNavigators viewNavigators;

  private Grid<Document> chunksGrid;
  private UUID currentDocumentId;

  @Subscribe
  public void onInit(final InitEvent initEvent) {
    chunksGrid = new Grid<>(Document.class, false);
    chunksGrid.setWidthFull();
    chunksGrid.setMinHeight("18em");
    chunksGrid.addThemeNames("compact");
    chunksGrid
        .addColumn(Document::getId)
        .setHeader(messages.getMessage("knowledgeBase.detail.chunk.id"))
        .setWidth("260px")
        .setFlexGrow(0)
        .setResizable(true);
    chunksGrid
        .addColumn(
            new TextRenderer<>(
                doc -> preview(Objects.toString(doc.getText(), ""), CONTENT_PREVIEW_LIMIT)))
        .setHeader(messages.getMessage("knowledgeBase.detail.chunk.content"))
        .setResizable(true);
    chunksGrid
        .addColumn(new TextRenderer<>(doc -> preview(formatMetadata(doc), METADATA_PREVIEW_LIMIT)))
        .setHeader(messages.getMessage("knowledgeBase.detail.chunk.metadata"))
        .setResizable(true);
    chunksGrid
        .asSingleSelect()
        .addValueChangeListener(valueChangeEvent -> renderSelectedChunk(valueChangeEvent.getValue()));
    chunksContainer.add(chunksGrid);

    renderSelectedChunk(null);
  }

  @Subscribe
  public void onQueryParametersChange(final View.QueryParametersChangeEvent event) {
    event
        .getQueryParameters()
        .getSingleParameter("documentId")
        .ifPresentOrElse(this::loadFromQueryParameter, this::renderMissingDocumentId);
  }

  @Subscribe("backButton")
  public void onBackButtonClick(final ClickEvent<Button> event) {
    if (currentDocumentId == null) {
      viewNavigators.view(this, KnowledgeBaseView.class).navigate();
      return;
    }
    viewNavigators
        .view(this, KnowledgeBaseView.class)
        .withQueryParameters(
            QueryParameters.simple(Map.of("documentId", currentDocumentId.toString())))
        .navigate();
  }

  private void loadFromQueryParameter(String rawDocumentId) {
    try {
      UUID documentId = UUID.fromString(rawDocumentId);
      loadDocument(documentId);
    } catch (IllegalArgumentException ex) {
      log.debug("Ignoring malformed knowledge document id query parameter: {}", rawDocumentId);
      renderUnavailable(messages.getMessage("knowledgeBase.detail.invalidDocumentId"));
    }
  }

  private void loadDocument(UUID documentId) {
    dataManager
        .load(AiKnowledgeDocument.class)
        .id(documentId)
        .optional()
        .ifPresentOrElse(
            this::renderDocument,
            () -> renderUnavailable(messages.getMessage("knowledgeBase.detail.documentNotFound")));
  }

  private void renderDocument(AiKnowledgeDocument document) {
    currentDocumentId = document.getId();
    documentTitle.setText(Objects.toString(document.getFileName(), EMPTY_VALUE));
    applyStatusBadge(document.getStatus());

    documentIdField.setValue(stringValue(document.getId()));
    fileNameField.setValue(Objects.toString(document.getFileName(), ""));
    mimeTypeField.setValue(Objects.toString(document.getMimeType(), ""));
    sizeBytesField.setValue(formatSize(document.getSizeBytes()));
    uploadedAtField.setValue(formatDateTime(document.getCreatedDate()));
    ingestedAtField.setValue(formatDateTime(document.getIngestedAt()));
    createdByField.setValue(blankToPlaceholder(document.getCreatedBy()));
    errorMessageField.setValue(Objects.toString(document.getErrorMessage(), ""));
    errorMessageField.setVisible(document.getErrorMessage() != null && !document.getErrorMessage().isBlank());
    sourceEntityField.setValue(formatSourceEntity(document.getSourceEntityName()));
    allowedRolesField.setValue(formatRoles(document.getAllowedRolesJson()));

    renderChunks(document.getId());
  }

  private void renderChunks(UUID documentId) {
    List<Document> chunks;
    try {
      chunks = loadVectorChunks(documentId);
      chunksHeading.setText(
          messages.getMessage("knowledgeBase.detail.vectorChunks").formatted(chunks.size()));
    } catch (Exception ex) {
      log.warn("Could not load vector chunks for knowledge document {}", documentId, ex);
      chunks = List.of();
      chunksHeading.setText(messages.getMessage("knowledgeBase.detail.chunksLoadFailed"));
    }

    chunksGrid.setItems(chunks);
    chunksGrid.asSingleSelect().clear();
    if (chunks.isEmpty()) {
      renderSelectedChunk(null);
    } else {
      chunksGrid.select(chunks.getFirst());
    }
  }

  private List<Document> loadVectorChunks(UUID documentId) {
    if (documentId == null) {
      return List.of();
    }
    List<Document> documents =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("")
                .topK(CHUNK_LIMIT)
                .similarityThreshold(0.0)
                .filterExpression(
                    new FilterExpressionBuilder()
                        .eq(ChunkMetadata.DOCUMENT_ID, documentId.toString())
                        .build())
                .build());
    return documents == null ? List.of() : documents;
  }

  private void renderSelectedChunk(Document document) {
    boolean hasChunk = document != null;
    chunkContentField.setVisible(hasChunk);
    chunkMetadataField.setVisible(hasChunk);
    if (!hasChunk) {
      chunkContentField.setValue("");
      chunkMetadataField.setValue("");
      return;
    }
    chunkContentField.setValue(Objects.toString(document.getText(), ""));
    chunkMetadataField.setValue(formatMetadata(document));
  }

  private void renderMissingDocumentId() {
    renderUnavailable(messages.getMessage("knowledgeBase.detail.invalidDocumentId"));
  }

  private void renderUnavailable(String title) {
    currentDocumentId = null;
    documentTitle.setText(title);
    clearStatusBadge();
    documentIdField.setValue("");
    fileNameField.setValue("");
    mimeTypeField.setValue("");
    sizeBytesField.setValue(EMPTY_VALUE);
    uploadedAtField.setValue("");
    ingestedAtField.setValue("");
    createdByField.setValue(EMPTY_VALUE);
    errorMessageField.setValue("");
    errorMessageField.setVisible(false);
    sourceEntityField.setValue("");
    allowedRolesField.setValue("");
    chunksHeading.setText(messages.getMessage("knowledgeBase.detail.noChunks"));
    chunksGrid.setItems(List.of());
    renderSelectedChunk(null);
  }

  private void applyStatusBadge(AiKnowledgeDocumentStatus status) {
    statusBadge.setText(formatStatus(status));
    statusBadge.getElement().getThemeList().clear();
    statusBadge.getElement().getThemeList().add("badge");
    String theme = statusTheme(status);
    for (String token : theme.split(" ")) {
      if (!token.isBlank()) {
        statusBadge.getElement().getThemeList().add(token);
      }
    }
  }

  private void clearStatusBadge() {
    statusBadge.setText("");
    statusBadge.getElement().getThemeList().clear();
  }

  private String formatStatus(AiKnowledgeDocumentStatus status) {
    return messages.getMessage(
        "knowledgeBase.status."
            + (status == null ? "pending" : status.name().toLowerCase(Locale.ROOT)));
  }

  private static String statusTheme(AiKnowledgeDocumentStatus status) {
    if (status == null) {
      return "contrast";
    }
    return switch (status) {
      case READY -> "success";
      case FAILED -> "error";
      case PROCESSING, PENDING -> "contrast";
      case CANCELLED -> "contrast tertiary";
    };
  }

  private String formatSourceEntity(String sourceEntityName) {
    if (sourceEntityName == null || sourceEntityName.isBlank()) {
      return messages.getMessage("knowledgeBase.detail.noSourceEntity");
    }
    MetaClass metaClass = metadata.getSession().findClass(sourceEntityName);
    return metaClass == null
        ? sourceEntityName
        : messageTools.getEntityCaption(metaClass) + " (" + sourceEntityName + ")";
  }

  private String formatRoles(String rolesJson) {
    Set<String> roles = parseAllowedRoles(rolesJson);
    if (roles.isEmpty()) {
      return messages.getMessage("knowledgeBase.detail.noRoles");
    }
    return String.join(System.lineSeparator(), roles);
  }

  private String formatMetadata(Document document) {
    if (document == null || document.getMetadata() == null || document.getMetadata().isEmpty()) {
      return "{}";
    }
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document.getMetadata());
    } catch (Exception ex) {
      return document.getMetadata().toString();
    }
  }

  private static String preview(String value, int limit) {
    String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return compact.length() > limit ? compact.substring(0, limit) + "..." : compact;
  }

  private static String formatDateTime(OffsetDateTime value) {
    return value == null ? EMPTY_VALUE : DATE_TIME_FORMATTER.format(value);
  }

  private static String stringValue(Object value) {
    return value == null ? EMPTY_VALUE : value.toString();
  }

  private static String blankToPlaceholder(String value) {
    return value == null || value.isBlank() ? EMPTY_VALUE : value;
  }

  private static String formatSize(Long sizeBytes) {
    if (sizeBytes == null) {
      return EMPTY_VALUE;
    }
    if (sizeBytes < 1024) {
      return sizeBytes + " B";
    }
    double value = sizeBytes;
    String[] units = {"B", "KB", "MB", "GB"};
    int unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex++;
    }
    return new java.text.DecimalFormat("#,##0.#").format(value)
        + " "
        + units[unitIndex]
        + " ("
        + sizeBytes
        + " B)";
  }

  private static Set<String> parseAllowedRoles(String json) {
    if (json == null || json.isBlank()) {
      return Collections.emptySet();
    }
    try {
      List<String> roles = JSON.readValue(json, new TypeReference<>() {});
      return roles == null ? Collections.emptySet() : new LinkedHashSet<>(roles);
    } catch (Exception e) {
      log.warn("Failed to parse allowedRolesJson; treating as empty: {}", json, e);
      return Collections.emptySet();
    }
  }
}
