package com.vn.agent.rag.advisor;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditingDocumentRetrieverSearchRequestTest {

    @AfterEach
    void clearRunContext() {
        RunContext.clear();
    }

    @Test
    void contextParametersOverrideThreadLocalDefaultsForSearchRequest() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                vectorStore, 5, 0.5, mock(AuditWriter.class), mock(CurrentAuthentication.class));

        Query query = Query.builder()
                .text("Role nào được phép cập nhật trạng thái đơn hàng sang SHIPPED?")
                .context(Map.of(
                        AuditingDocumentRetriever.TOP_K_CONTEXT_KEY, 10,
                        AuditingDocumentRetriever.SIMILARITY_THRESHOLD_CONTEXT_KEY, 0.1))
                .build();

        retriever.retrieve(query);

        org.mockito.ArgumentCaptor<SearchRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo(query.text());
        assertThat(request.getTopK()).isEqualTo(10);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.1);
    }
}
