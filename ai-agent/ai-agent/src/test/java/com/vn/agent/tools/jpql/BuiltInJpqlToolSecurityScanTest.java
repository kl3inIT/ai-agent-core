package com.vn.agent.tools.jpql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-posture regression for the read-only JPQL analytics tool.
 *
 * <p><b>Why source-scan instead of {@code @SpringBootTest}:</b> the module-level boot regression
 * documented elsewhere (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBounds)
 * blocks every test that boots an agentstore Spring context, and the security wiring this guards is
 * fully expressed in {@link AiJpqlQueryService}'s source — deterministic Java call sites. This
 * mirrors {@code NoticeRenderTest} / {@code BuiltInDataToolsReadOnlyTest} (the latter is a bytecode
 * scan of the same spirit).
 *
 * <p>The three guarantees asserted:
 * <ol>
 *   <li><b>Framework entity-read gate</b> — the query is pre-flighted through
 *       {@code LoadValuesAccessContext} + {@code accessManager.applyRegisteredConstraints} so a user
 *       without READ on a referenced entity is rejected.</li>
 *   <li><b>Addon LLM-exposure overlay</b> — every referenced entity is additionally checked against
 *       {@code llmExposurePolicy.canReadEntity}, so admin-denylisted entities cannot be reached via
 *       raw JPQL even when Jmix permissions would allow the user.</li>
 *   <li><b>Constrained data path</b> — execution uses the secured {@code dataManager.loadValues}
 *       (row-level security auto-applied); it must NOT use {@code UnconstrainedDataManager}.</li>
 * </ol>
 */
class BuiltInJpqlToolSecurityScanTest {

    @Test
    void queryServiceEnforcesFrameworkGateExposureOverlayAndConstrainedPath() throws Exception {
        String source = readSource(
                "src/main/java/com/vn/agent/tools/jpql/AiJpqlQueryService.java");

        assertThat(source)
                .as("must pre-flight the query through the Jmix entity-read access gate")
                .contains("LoadValuesAccessContext")
                .contains("applyRegisteredConstraints")
                .contains("isPermitted()");

        assertThat(source)
                .as("must overlay the addon LLM-exposure denylist on every referenced entity")
                .contains("llmExposurePolicy.canReadEntity");

        assertThat(source)
                .as("must execute through the secured (constrained) DataManager, never unconstrained")
                .contains("dataManager.loadValues")
                .doesNotContain("UnconstrainedDataManager")
                .doesNotContain(".unconstrained()");
    }

    @Test
    void toolIsReadOnly_noMutationOrPersistenceContextCalls() throws Exception {
        String serviceSource = readSource(
                "src/main/java/com/vn/agent/tools/jpql/AiJpqlQueryService.java");
        String toolSource = readSource(
                "src/main/java/com/vn/agent/tools/jpql/BuiltInJpqlTool.java");

        for (String source : new String[]{serviceSource, toolSource}) {
            assertThat(source)
                    .as("JPQL analytics tool must be strictly read-only")
                    .doesNotContain("dataManager.save")
                    .doesNotContain("dataManager.remove")
                    .doesNotContain("saveContext")
                    .doesNotContain("EntityManager");
        }
    }

    private static String readSource(String relativePath) throws Exception {
        Path primary = Paths.get(relativePath);
        if (Files.exists(primary)) {
            return Files.readString(primary, StandardCharsets.UTF_8);
        }
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/").resolve(relativePath);
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }
}
