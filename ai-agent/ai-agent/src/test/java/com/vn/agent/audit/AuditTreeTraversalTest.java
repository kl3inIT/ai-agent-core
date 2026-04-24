package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7.2 success criterion 2 — one chat turn = one root + N children linked by PARENT_ID.
 * Writes a root CHAT row plus one TOOL child and one RETRIEVAL child, then reloads the root
 * with an explicit children fetch plan and asserts both kinds present and parent pointers
 * resolve back to the root.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AuditTreeTraversalTest {

    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void readsRootWithToolAndRetrievalChildren() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID rootId = auditWriter.writeChatStart(runId, "user-A", null, "hash");

            auditWriter.writeToolCall(rootId, runId, "user-A", null, "echo",
                    "{}", "{}", 4L, AiToolCallOutcome.SUCCESS, null, null);
            auditWriter.writeRetrieval(rootId, runId, "user-A", null,
                    "what is foo?", 5, 3, 0.87, "docType == 'faq'",
                    8L, "SUCCESS", null);
            auditWriter.writeChatFinish(rootId, 20L, "SUCCESS", null);

            AiAuditEvent root = dataManager.load(AiAuditEvent.class)
                    .id(rootId)
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("children", FetchPlan.BASE))
                    .one();

            assertThat(root.getKind()).isEqualTo(AuditKind.CHAT);
            assertThat(root.getParent()).isNull();
            assertThat(root.getFinishedAt()).isNotNull();
            assertThat(root.getOutcomeRaw()).isEqualTo("SUCCESS");

            List<AiAuditEvent> children = root.getChildren();
            assertThat(children).hasSize(2);
            assertThat(children).extracting(AiAuditEvent::getKind)
                    .containsExactlyInAnyOrder(AuditKind.TOOL, AuditKind.RETRIEVAL);
            assertThat(children).allSatisfy(c -> {
                assertThat(c.getParent()).isNotNull();
                assertThat(c.getParent().getId()).isEqualTo(rootId);
                assertThat(c.getRunId()).isEqualTo(runId);
            });
        });
    }
}
