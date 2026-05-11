package com.vn.agent.rag.advisor;

import com.vn.agent.audit.AuditWriter;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditingDocumentRetrieverTest {

    @Test
    void retrievalFailureReturnsEmptyDocumentsAndAuditsError() {
        DocumentRetriever delegate = mock(DocumentRetriever.class);
        when(delegate.retrieve(any(Query.class))).thenThrow(new IllegalStateException("provider down"));
        AuditWriter auditWriter = mock(AuditWriter.class);
        AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                delegate, auditWriter, mock(CurrentAuthentication.class));
        Query query = Query.builder()
                .text("Create a product")
                .context(Map.of(AuditingDocumentRetriever.TOP_K_CONTEXT_KEY, 7))
                .build();

        List<org.springframework.ai.document.Document> documents = retriever.retrieve(query);

        assertThat(documents).isEmpty();
        verify(auditWriter).writeRetrieval(
                any(), any(), any(), any(),
                eq("Create a product"), eq(7), eq(0), isNull(), isNull(),
                eq("{\"hits\":[],\"truncated\":false}"),
                anyLong(), eq("ERROR"), eq("IllegalStateException"));
    }
}
