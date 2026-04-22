package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.AuditAdvisor;
import com.vn.agent.audit.ToolCallAdvisorBuilderConstants;
import com.vn.agent.guard.OutputScannerAdvisor;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural assertion that the default {@link ChatClient} carries the five advisors in the locked
 * order with the locked order values (Phase 4 baseline + Phase 5 RAG slot at +250 + Phase 6
 * OutputScannerAdvisor at +400):
 *
 * <ol>
 *   <li>{@link AuditAdvisor} at {@link Ordered#HIGHEST_PRECEDENCE}</li>
 *   <li>{@code MessageChatMemoryAdvisor} at {@code HIGHEST_PRECEDENCE + 200}</li>
 *   <li>{@link RetrievalAugmentationAdvisor} at {@code HIGHEST_PRECEDENCE + 250} — Phase 5 RAG-04</li>
 *   <li>{@link ToolCallAdvisor} at {@code HIGHEST_PRECEDENCE + 300}</li>
 *   <li>{@link OutputScannerAdvisor} at {@code HIGHEST_PRECEDENCE + 400} — Phase 6 (Plan 06-04)</li>
 * </ol>
 *
 * <p>Spring AI's {@code ChatClient} interface does not expose the default advisor list, so the
 * test reflects into {@code DefaultChatClient.defaultChatClientRequest.advisors} — the one and
 * only place the list lives in 1.1.4. The field name is verified against the public constructor
 * signature of {@code DefaultChatClient$DefaultChatClientRequestSpec}; any future rename will
 * surface here as a {@link NoSuchFieldException} at test time rather than silent drift.</p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AdvisorOrderStructuralTest {

    @Autowired ChatClient chatClient;

    @Test
    void verifyAdvisorChainOrder() throws Exception {
        List<Advisor> advisors = readDefaultAdvisors(chatClient);

        List<CallAdvisor> callAdvisors = new ArrayList<>();
        for (Advisor a : advisors) {
            if (a instanceof CallAdvisor ca) {
                callAdvisors.add(ca);
            }
        }
        callAdvisors.sort(Comparator.comparingInt(CallAdvisor::getOrder));

        assertThat(callAdvisors)
                .as("Default advisor list must contain the five advisors "
                        + "(Phase 4 audit/memory/tool + Phase 5 rag at +250 + Phase 6 outputScanner at +400)")
                .hasSize(5);

        CallAdvisor audit = callAdvisors.get(0);
        CallAdvisor memory = callAdvisors.get(1);
        CallAdvisor rag = callAdvisors.get(2);
        CallAdvisor tool = callAdvisors.get(3);
        CallAdvisor outputScanner = callAdvisors.get(4);

        assertThat(audit)
                .as("First advisor (HIGHEST_PRECEDENCE) must be AuditAdvisor")
                .isInstanceOf(AuditAdvisor.class);
        assertThat(audit.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);

        assertThat(memory.getClass().getSimpleName()).isEqualTo("MessageChatMemoryAdvisor");
        assertThat(memory.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);

        assertThat(rag)
                .as("Third advisor must be RetrievalAugmentationAdvisor at +250 (Phase 5 RAG-04)")
                .isInstanceOf(RetrievalAugmentationAdvisor.class);
        assertThat(rag.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 250);

        assertThat(tool).isInstanceOf(ToolCallAdvisor.class);
        assertThat(tool.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);

        assertThat(outputScanner)
                .as("Fifth advisor must be OutputScannerAdvisor at +400 (Phase 6 Plan 06-04)")
                .isInstanceOf(OutputScannerAdvisor.class);
        assertThat(outputScanner.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 400);

        // Reflectively assert the internal conversation-history flag on ToolCallAdvisor. Field
        // name is captured in ToolCallAdvisorBuilderConstants (OQ-1 closure constants).
        Field internalFlag = tool.getClass().getDeclaredField(
                ToolCallAdvisorBuilderConstants.INTERNAL_FLAG_FIELD);
        internalFlag.setAccessible(true);
        Object value = internalFlag.get(tool);
        assertThat(value)
                .as("ToolCallAdvisor.%s must be true so tool recursion keeps assistant/tool pairing",
                        ToolCallAdvisorBuilderConstants.INTERNAL_FLAG_FIELD)
                .isEqualTo(Boolean.TRUE);
    }

    /**
     * Reflectively read the default advisor list from a {@code DefaultChatClient}. Spring AI
     * 1.1.4 stores the list on {@code DefaultChatClient$DefaultChatClientRequestSpec.advisors}
     * (field name verified via {@code javap} — see ChatClientFactory javadoc).
     */
    @SuppressWarnings("unchecked")
    private static List<Advisor> readDefaultAdvisors(ChatClient client) throws Exception {
        Field requestField = client.getClass().getDeclaredField("defaultChatClientRequest");
        requestField.setAccessible(true);
        Object requestSpec = requestField.get(client);
        Field advisorsField = requestSpec.getClass().getDeclaredField("advisors");
        advisorsField.setAccessible(true);
        return (List<Advisor>) advisorsField.get(requestSpec);
    }
}
