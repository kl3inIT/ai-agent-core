package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.spi.MutationGuard;
import com.vn.agent.spi.MutationIntent;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Plan 11-10 Task 3 — TEST-10 guard sees POST-coercion typed values, not raw LLM strings.
 *
 * <p>Two scenarios are pinned:
 * <ol>
 *   <li><b>Scalar coercion</b> — LLM supplies {@code priority: "7"} (JSON string). The
 *       {@link MutationAttributeBinder} delegates to
 *       {@link com.vn.agent.filter.FilterLiteralValueConverter#convertValue} to coerce
 *       String -> Integer. The {@link MutationGuard} sees {@code Integer 7}, not the raw
 *       String {@code "7"}.</li>
 *   <li><b>Null clear</b> — LLM supplies {@code name: null} via a mutable map (Plan 11-03
 *       preserves null clears for optional-field updates). The guard sees an
 *       {@link MutationIntent#attributes()} containing key {@code name} with null value.</li>
 * </ol>
 *
 * <p>The guard is captured via {@code @MockitoBean MutationGuard} that records the
 * {@link MutationIntent}, then vetoes via {@link ToolVetoedException}. Vetoing means we do
 * NOT need to seed real fixture rows or run host save — the gate stops AFTER the guard runs,
 * BEFORE {@link MutationSaveExecutor#save}.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsGuardReceivesCoercedAttributesTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @MockitoBean
    private MutationGuard mutationGuard;

    private final AtomicReference<MutationIntent> capturedIntent = new AtomicReference<>();

    @BeforeEach
    void setUpGuardCapture() throws ToolVetoedException {
        capturedIntent.set(null);
        doAnswer(invocation -> {
            capturedIntent.set(invocation.getArgument(0, MutationIntent.class));
            throw new ToolVetoedException("captured-and-vetoed");
        }).when(mutationGuard).check(any(MutationIntent.class));
    }

    @Test
    void scalarPriority_isCoercedToInteger_beforeGuardSeesIt() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("priority", "7");   // raw LLM JSON string

        String json = mutationToolTestContext.withMutationRun("mutation-user", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        // Guard vetoed -> response is access_denied; this confirms the guard ran before save.
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(json).path("error").asText())
                .as("guard veto must surface as access_denied; raw=%s", json)
                .isEqualTo("access_denied");

        MutationIntent intent = capturedIntent.get();
        assertThat(intent)
                .as("MutationGuard.check must have been invoked exactly once; raw=%s", json)
                .isNotNull();
        Object priorityValue = intent.attributes().get("priority");
        assertThat(priorityValue)
                .as("guard sees POST-coercion typed value, not the raw LLM String")
                .isInstanceOf(Integer.class);
        assertThat(priorityValue).isEqualTo(7);
    }

    @Test
    void nullClear_isPreserved_whenLlmSendsNullForOptionalField() throws Exception {
        // Mutable map (LinkedHashMap) — Map.of forbids null values.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", null);

        String json = mutationToolTestContext.withMutationRun("mutation-user", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(json).path("error").asText()).isEqualTo("access_denied");

        MutationIntent intent = capturedIntent.get();
        assertThat(intent).isNotNull();
        assertThat(intent.attributes())
                .as("Plan 11-03 preserves null clears: the attributes map must contain "
                        + "the key with null value, not omit it")
                .containsKey("name");
        assertThat(intent.attributes().get("name")).isNull();
    }

}
