package com.vn.agent.metadata;

import com.vn.agent.AITestConfiguration;
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
 *   <li>{@code UserEditableStringIndex} excludes framework-managed fields (createdBy,
 *       createdDate, id, version, …) for every entity (D-13).</li>
 *   <li>At least one Phase 2 entity has a non-empty user-editable String set — positive
 *       evidence that the scanner actually sees the enhanced metamodel.</li>
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
        boolean anyNonEmpty = uei.byEntityName().values().stream().anyMatch(s -> !s.isEmpty());
        assertThat(anyNonEmpty)
                .as("UserEditableStringIndex captured at least one String attribute somewhere")
                .isTrue();
    }
}
