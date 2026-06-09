package com.vn.agent.tools.mutation;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.MutationToolCallbackBoundaryDecorator;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 1 — single-audit-owner regression gate (HIGH review feedback for Plan 11-09).
 *
 * <p>With {@code jmix.ai-agent.tools.mutation.enabled=true}, {@link AgentToolCallbacks#forCurrentUser()}
 * must wrap mutation callbacks (create/update/add_related/remove_related) in
 * {@link MutationToolCallbackBoundaryDecorator} and NOT in
 * {@link ToolCallbackAuditDecorator}. This preserves the Plan 11-07C invariant:
 * {@code BuiltInMutationTools} self-audits exactly once via
 * {@code MutationCommitCoordinator.safeWriteAudit}; wrapping it in the generic decorator would
 * write a SECOND row per call.
 *
 * <p>The non-mutation surface (read + link) is wrapped by
 * {@link ToolCallbackAuditDecorator} so the test can detect wrap-detection works (positive
 * sanity check).
 *
 * <p>Boundary-decorator pre-method audit assertion: invoking a mutation callback's
 * {@link ToolCallback#call(String)} with malformed JSON forces Spring AI's argument binder to
 * throw before the @Tool method body runs. The boundary decorator catches that throw and writes
 * exactly one audit row with outcome {@link AiToolCallOutcome#ERROR} (Plan 11-09 D-09
 * "last-resort pre-method audit"). On the same path, {@code BuiltInMutationTools} never enters
 * its own try/catch, so {@code safeWriteAudit} is not called — exactly one row, not two.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"jmix.ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AgentToolCallbacksMutationAuditOwnershipTest {

    @Autowired
    private AgentToolCallbacks agentToolCallbacks;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private UUID isolatedRunId;

    @BeforeEach
    void seedRunContext() {
        // A fresh runId for each test so audit-row counting is scoped to this method.
        isolatedRunId = UUID.randomUUID();
        RunContext.set(isolatedRunId);
        RunContext.setConversationId(UUID.randomUUID());
        RunContext.setRootAuditId(UUID.randomUUID());
    }

    @AfterEach
    void clearRunContextAndAuditRows() {
        RunContext.clear();
        if (isolatedRunId != null) {
            // Clean up TOOL audit rows written under the isolated runId so concurrent test runs
            // do not collide on the agentstore.
            systemAuthenticator.runWithSystem(() -> {
                List<AiAuditEvent> rows = unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid")
                        .parameter("rid", isolatedRunId)
                        .list();
                for (AiAuditEvent row : rows) {
                    unconstrainedDataManager.remove(row);
                }
            });
        }
    }

    @Test
    void mutationCallbacks_areWrappedByBoundaryDecorator_notGenericAuditDecorator() {
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        Map<String, ToolCallback> byName = byName(callbacks);

        for (String mutationToolName : List.of(
                "create_record", "update_record", "add_related_record", "remove_related_record")) {
            ToolCallback cb = byName.get(mutationToolName);
            assertThat(cb)
                    .as("mutation callback %s must be present when enabled=true", mutationToolName)
                    .isNotNull();
            assertThat(cb)
                    .as("mutation callback %s must be wrapped by MutationToolCallbackBoundaryDecorator "
                            + "(single-audit-owner invariant from Plan 11-07C)", mutationToolName)
                    .isInstanceOf(MutationToolCallbackBoundaryDecorator.class);
            assertThat(cb)
                    .as("mutation callback %s must NOT be wrapped by ToolCallbackAuditDecorator "
                            + "(would write a duplicate audit row)", mutationToolName)
                    .isNotInstanceOf(ToolCallbackAuditDecorator.class);
        }
    }

    @Test
    void nonMutationCallbacks_areWrappedByGenericAuditDecorator() {
        // Positive control: read tools must be wrapped by ToolCallbackAuditDecorator. If this
        // assertion fails, the wrap-detection mechanism itself is broken and the negative
        // mutation assertion above is meaningless.
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        Map<String, ToolCallback> byName = byName(callbacks);

        ToolCallback listEntities = byName.get("list_entities");
        assertThat(listEntities)
                .as("list_entities must be wrapped by ToolCallbackAuditDecorator (sanity check)")
                .isInstanceOf(ToolCallbackAuditDecorator.class);
        assertThat(listEntities)
                .as("list_entities must NOT be wrapped by MutationToolCallbackBoundaryDecorator")
                .isNotInstanceOf(MutationToolCallbackBoundaryDecorator.class);
    }

    @Test
    void boundaryDecorator_writesExactlyOneErrorRow_whenDelegateBindingFails() {
        ToolCallback createRecord = byName(agentToolCallbacks.forCurrentUser()).get("create_record");
        assertThat(createRecord).isNotNull();

        // Snapshot existing TOOL rows under our isolated runId BEFORE the call.
        long before = countToolAuditRowsForCurrentRun("create_record");
        assertThat(before).isZero();

        // Malformed input: not a JSON object at all. Spring AI's argument binder cannot
        // map this to (String entityName, Map attributes, String idempotencyKey), so the
        // delegate throws before the @Tool method body runs. The boundary decorator's
        // catch path writes exactly one ERROR audit row, then rethrows.
        Throwable thrown = invokeAndCatch(createRecord, "this-is-not-json-at-all");

        // The decorator rethrows — the binding failure must surface, NOT be swallowed.
        assertThat(thrown).isNotNull();

        long after = countToolAuditRowsForCurrentRun("create_record");
        assertThat(after - before)
                .as("Boundary decorator must write exactly ONE audit row on delegate-thrown exception "
                        + "(no duplicate from BuiltInMutationTools because the tool method body never ran)")
                .isEqualTo(1L);

        AiAuditEvent row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.eventName = :n")
                        .parameter("rid", isolatedRunId)
                        .parameter("n", "create_record")
                        .one());

        assertThat(row.getKind()).isEqualTo(AuditKind.TOOL);
        assertThat(row.getOutcome())
                .as("Pre-method binding failure must record outcome=ERROR (Plan 11-09 D-09)")
                .isEqualTo(AiToolCallOutcome.ERROR);
        assertThat(row.getEventName()).isEqualTo("create_record");
        assertThat(row.getErrorClass())
                .as("ERROR row must capture the exception class FQN of the binding failure")
                .isNotBlank();
    }

    // -------- helpers --------

    private Map<String, ToolCallback> byName(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .collect(java.util.stream.Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb));
    }

    private long countToolAuditRowsForCurrentRun(String toolName) {
        return systemAuthenticator.withSystem(() ->
                (long) unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.eventName = :n")
                        .parameter("rid", isolatedRunId)
                        .parameter("n", toolName)
                        .list()
                        .size());
    }

    private static Throwable invokeAndCatch(ToolCallback cb, String input) {
        try {
            cb.call(input);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
