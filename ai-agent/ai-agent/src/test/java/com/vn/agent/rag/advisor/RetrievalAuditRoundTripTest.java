package com.vn.agent.rag.advisor;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7.2 success criterion 3 (D-09 round trip) â€” the AuditingDocumentRetriever decorator must
 * write exactly one RETRIEVAL child {@link AiAuditEvent} under the current chat turn's root,
 * populated with queryText / topK / hitCount / topScore / filtersJson from the delegate's
 * document list and from {@link RunContext} carriers.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class RetrievalAuditRoundTripTest {

    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired CurrentAuthentication currentAuthentication;
    @Autowired SystemAuthenticator systemAuthenticator;

    @AfterEach
    void clearRunContext() {
        // T-07.2-15 defence: pooled JUnit threads would otherwise carry stale RunContext across tests.
        RunContext.clear();
    }

    @Test
    void retrievalWritesChildRowUnderChatRoot() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            RunContext.set(runId);
            UUID conversationId = createConversation();
            RunContext.setConversationId(conversationId);

            UUID rootId = auditWriter.writeChatStart(runId, "user-A", conversationId, "hash");
            RunContext.setRootAuditId(rootId);
            RunContext.setRetrievalTopK(5);
            RunContext.setRetrievalFiltersJson("docType == 'faq'");

            DocumentRetriever stubDelegate = q -> List.of(
                    buildDoc("doc-1", 0.91),
                    buildDoc("doc-2", 0.85),
                    buildDoc("doc-3", 0.80));
            AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                    stubDelegate, auditWriter, currentAuthentication);

            Query query = new Query("what is foo?");
            List<Document> docs = retriever.retrieve(query);

            assertThat(docs).hasSize(3);

            AiAuditEvent root = dataManager.load(AiAuditEvent.class)
                    .id(rootId)
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("children", FetchPlan.BASE))
                    .one();
            List<AiAuditEvent> children = root.getChildren();
            assertThat(children).hasSize(1);

            AiAuditEvent retrievalRow = children.getFirst();
            assertThat(retrievalRow.getKind()).isEqualTo(AuditKind.RETRIEVAL);
            assertThat(retrievalRow.getQueryText()).isEqualTo("what is foo?");
            assertThat(retrievalRow.getTopK()).isEqualTo(5);
            assertThat(retrievalRow.getHitCount()).isEqualTo(3);
            assertThat(retrievalRow.getTopScore()).isEqualTo(0.91);
            assertThat(retrievalRow.getFiltersJson()).isEqualTo("docType == 'faq'");
            assertThat(retrievalRow.getOutcomeRaw()).isEqualTo("SUCCESS");
            assertThat(retrievalRow.getErrorClass()).isNull();
            assertThat(retrievalRow.getRunId()).isEqualTo(runId);
            assertThat(retrievalRow.getConversation()).isNotNull();
            assertThat(retrievalRow.getConversation().getId()).isEqualTo(conversationId);
            assertThat(retrievalRow.getParent()).isNotNull();
            assertThat(retrievalRow.getParent().getId()).isEqualTo(rootId);
        });
    }

    private UUID createConversation() {
        AiConversation conversation = dataManager.create(AiConversation.class);
        conversation.setTitle("conv-retrieval-fk");
        conversation.setCreatedBy("user-A");
        conversation.setCreatedDate(OffsetDateTime.now());
        return dataManager.save(conversation).getId();
    }

    private static Document buildDoc(String id, double score) {
        return Document.builder()
                .id(id)
                .text("content-" + id)
                .metadata(Map.of())
                .score(score)
                .build();
    }
}
