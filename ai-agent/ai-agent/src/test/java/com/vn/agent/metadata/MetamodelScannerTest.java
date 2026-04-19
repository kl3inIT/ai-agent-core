package com.vn.agent.metadata;

import com.vn.agent.AITestConfiguration;
import io.jmix.core.entity.annotation.SystemLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link MetamodelScanner} behavior after {@code ApplicationReadyEvent}:
 * <ul>
 *   <li>Raw schema exposes Phase 2 add-on entities (AiConversation, AiMessage, …).</li>
 *   <li>{@code @SystemLevel}-annotated classes are absent.</li>
 *   <li>{@code UserEditableStringIndex} excludes framework-managed fields (createdBy,
 *       createdDate, id, version, …) for every entity (D-13).</li>
 * </ul>
 * Boots against {@link AITestConfiguration} with the same auto-config chain as
 * {@code FoundationsBootSmokeTest}, plus {@code AiToolsAutoConfiguration} which orders
 * the Plan 03 beans after the core + SPI defaults.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class,
        com.vn.autoconfigure.agent.AiToolsAutoConfiguration.class
})
class MetamodelScannerTest {

    @Autowired
    MetamodelScanner scanner;

    @Test
    void rawSchemaContainsPhase2AiEntities() {
        AiSchema raw = scanner.getRawSchema();
        assertThat(raw.entities().keySet())
                .anyMatch(n -> n.endsWith("AiConversation"))
                .anyMatch(n -> n.endsWith("AiMessage"))
                .anyMatch(n -> n.endsWith("AiToolCallAudit"))
                .anyMatch(n -> n.endsWith("AiParameters"))
                .anyMatch(n -> n.endsWith("AiKnowledgeDocument"));
    }

    @Test
    void systemLevelClassesExcluded() {
        AiSchema raw = scanner.getRawSchema();
        for (AiEntityInfo e : raw.entities().values()) {
            assertThat(e.metaClass().getJavaClass().isAnnotationPresent(SystemLevel.class))
                    .as("entity %s must not be @SystemLevel", e.entityName())
                    .isFalse();
        }
    }

    @Test
    void userEditableIndexExcludesFrameworkManagedFields() {
        UserEditableStringIndex uei = scanner.getUserEditableStringIndex();
        Set<String> excluded = Set.of(
                "id", "version",
                "createdBy", "createdDate",
                "lastModifiedBy", "lastModifiedDate",
                "deletedBy", "deletedDate");
        for (Map.Entry<String, Set<String>> e : uei.byEntityName().entrySet()) {
            assertThat(e.getValue())
                    .as("entity %s user-editable set must not contain framework-managed names", e.getKey())
                    .doesNotContainAnyElementsOf(excluded);
        }
    }

    @Test
    void userEditableIndexCapturesSomeStringAttrAcrossPhase2Entities() {
        UserEditableStringIndex uei = scanner.getUserEditableStringIndex();
        // At least one Phase 2 entity must have at least one user-editable String
        // (otherwise the index is vacuously correct — we want positive evidence
        // that the scanner found our String-typed columns like AiMessage.content,
        // AiKnowledgeDocument.fileName, AiParameters.bodyYaml, etc.).
        boolean anyNonEmpty = uei.byEntityName().values().stream().anyMatch(s -> !s.isEmpty());
        assertThat(anyNonEmpty)
                .as("UserEditableStringIndex captured at least one String attribute somewhere")
                .isTrue();
    }
}
