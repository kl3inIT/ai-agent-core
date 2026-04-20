package com.vn.agent.rag;

import com.vn.agent.security.AiAgentUserRole;
import com.vn.agent.spi.CustomIngester;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mockito-only unit test — no Spring context required. */
class IngesterManagerTest {

    private KnowledgeDocumentUploadService uploadService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        uploadService = mock(KnowledgeDocumentUploadService.class);
    }

    private static CustomIngester ingester(String id, List<Document> docs) {
        CustomIngester i = mock(CustomIngester.class);
        when(i.getId()).thenReturn(id);
        when(i.getDisplayName()).thenReturn("Mock ingester " + id);
        when(i.read()).thenReturn(docs);
        return i;
    }

    private static Document doc(String source) {
        return Document.builder()
                .text("hello " + source)
                .metadata(Map.of(ChunkMetadata.SOURCE, source))
                .build();
    }

    private static Document docNoSource(String id) {
        return Document.builder()
                .id(id)
                .text("no-source " + id)
                .build();
    }

    @Test
    void runAll_with_zero_ingesters_does_nothing() {
        IngesterManager manager = new IngesterManager(List.of(), uploadService);

        manager.runAll();

        verify(uploadService, never()).upload(any(), any(), any());
    }

    @Test
    void runAll_uploads_every_document_from_every_ingester() {
        CustomIngester a = ingester("alpha", List.of(
                doc("classpath:ai-kb/a1.md"),
                doc("classpath:ai-kb/a2.md"),
                doc("classpath:ai-kb/a3.md")));
        CustomIngester b = ingester("beta", List.of(
                doc("classpath:ai-kb/b1.md"),
                doc("classpath:ai-kb/b2.md")));

        IngesterManager manager = new IngesterManager(List.of(a, b), uploadService);
        manager.runAll();

        verify(uploadService, times(5)).upload(anyString(), eq("CUSTOM_INGESTER"),
                eq(List.of(AiAgentUserRole.CODE)));
    }

    @Test
    void first_ingester_read_failure_does_not_stop_second_ingester() {
        CustomIngester broken = mock(CustomIngester.class);
        when(broken.getId()).thenReturn("broken");
        when(broken.read()).thenThrow(new RuntimeException("read boom"));

        CustomIngester healthy = ingester("healthy", List.of(doc("classpath:ai-kb/h.md")));

        IngesterManager manager = new IngesterManager(List.of(broken, healthy), uploadService);
        manager.runAll();

        verify(uploadService, times(1)).upload(eq("classpath:ai-kb/h.md"),
                eq("CUSTOM_INGESTER"), eq(List.of(AiAgentUserRole.CODE)));
    }

    @Test
    void per_document_upload_failure_skips_rest_of_current_ingester_but_continues_with_next() {
        CustomIngester a = ingester("alpha", List.of(
                doc("classpath:ai-kb/a1.md"),
                doc("classpath:ai-kb/a2.md"),
                doc("classpath:ai-kb/a3.md")));
        CustomIngester b = ingester("beta", List.of(doc("classpath:ai-kb/b1.md")));

        // First upload call (a1) throws — remaining docs of alpha skipped; beta still runs.
        when(uploadService.upload(eq("classpath:ai-kb/a1.md"), any(), any()))
                .thenThrow(new RuntimeException("upload blew up"));

        IngesterManager manager = new IngesterManager(List.of(a, b), uploadService);
        manager.runAll();

        verify(uploadService, times(1)).upload(eq("classpath:ai-kb/a1.md"), any(), any());
        verify(uploadService, never()).upload(eq("classpath:ai-kb/a2.md"), any(), any());
        verify(uploadService, never()).upload(eq("classpath:ai-kb/a3.md"), any(), any());
        verify(uploadService, times(1)).upload(eq("classpath:ai-kb/b1.md"), any(), any());
    }

    @Test
    void synthetic_sourceUri_used_when_metadata_source_absent() {
        CustomIngester a = ingester("alpha", List.of(docNoSource("doc-42")));

        IngesterManager manager = new IngesterManager(List.of(a), uploadService);
        manager.runAll();

        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        verify(uploadService).upload(uri.capture(), eq("CUSTOM_INGESTER"),
                eq(List.of(AiAgentUserRole.CODE)));
        assertThat(uri.getValue()).isEqualTo("ingester://alpha/doc-42");
    }

    @Test
    void allowedRoles_is_always_ai_agent_user_code_per_D17() {
        CustomIngester a = ingester("alpha", List.of(
                doc("classpath:ai-kb/a1.md"), doc("classpath:ai-kb/a2.md")));

        IngesterManager manager = new IngesterManager(List.of(a), uploadService);
        manager.runAll();

        ArgumentCaptor<List<String>> roles = ArgumentCaptor.forClass(List.class);
        verify(uploadService, times(2)).upload(anyString(), eq("CUSTOM_INGESTER"), roles.capture());
        for (List<String> r : roles.getAllValues()) {
            assertThat(r).containsExactly(AiAgentUserRole.CODE);
        }
    }

    @Test
    void ingester_returning_empty_list_is_tolerated() {
        CustomIngester a = ingester("empty", new ArrayList<>());
        CustomIngester b = ingester("beta", List.of(doc("classpath:ai-kb/b.md")));

        IngesterManager manager = new IngesterManager(List.of(a, b), uploadService);
        manager.runAll();

        verify(uploadService, times(1)).upload(anyString(), any(), any());
    }
}
