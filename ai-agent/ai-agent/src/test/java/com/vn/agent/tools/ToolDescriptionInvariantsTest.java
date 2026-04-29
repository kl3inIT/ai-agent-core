package com.vn.agent.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level guard for LLM-facing tool descriptions. Descriptions must teach shapes and
 * workflows without giving the model copyable fake entity names, order numbers, dates, or UUIDs.
 */
class ToolDescriptionInvariantsTest {

    private static final Path MODULE_ROOT = resolveModuleRoot();
    private static final Pattern CONCRETE_UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    @Test
    void builtInToolDescriptionsAvoidCopyableSampleValues() throws IOException {
        for (Path sourceFile : List.of(
                MODULE_ROOT.resolve("src/main/java/com/vn/agent/tools/BuiltInDataTools.java"),
                MODULE_ROOT.resolve("src/main/java/com/vn/agent/tools/link/BuiltInLinkTools.java"),
                MODULE_ROOT.resolve("src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java"))) {

            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s must not contain copyable sample metaclass names", MODULE_ROOT.relativize(sourceFile))
                    .doesNotContain("sample_")
                    .doesNotContain("jmixapp_");
            assertThat(CONCRETE_UUID.matcher(source).find())
                    .as("%s must not contain concrete UUID examples", MODULE_ROOT.relativize(sourceFile))
                    .isFalse();
            assertThat(source)
                    .as("%s must not contain copyable business sample literals", MODULE_ROOT.relativize(sourceFile))
                    .doesNotContain("ORD-")
                    .doesNotContain("2026-04-29");
        }
    }

    @Test
    void mutationDescriptionsTeachUuidV4ShapeAndPostSaveVerifyLink() throws IOException {
        String mutationTools = Files.readString(
                MODULE_ROOT.resolve("src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java"),
                StandardCharsets.UTF_8);
        String promptRules = Files.readString(
                MODULE_ROOT.resolve("src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java"),
                StandardCharsets.UTF_8);

        assertThat(mutationTools)
                .contains("first character of the third group is '4'")
                .contains("first character of the fourth group is one of '8', '9', 'a', or 'b'")
                .contains("immediately call generate_entity_detail_link with this same entityName and the returned entityId");
        assertThat(promptRules)
                .contains("immediately call generate_entity_detail_link")
                .contains("same entityName and returned entityId");
    }

    private static Path resolveModuleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
                cwd,
                cwd.resolve("ai-agent"),
                cwd.resolve("ai-agent").resolve("ai-agent"),
                cwd.getParent() == null ? cwd : cwd.getParent(),
        };
        for (Path candidate : candidates) {
            if (candidate != null
                    && Files.exists(candidate.resolve("src/main/java/com/vn/agent/tools/BuiltInDataTools.java"))) {
                return candidate;
            }
        }
        return cwd;
    }
}
