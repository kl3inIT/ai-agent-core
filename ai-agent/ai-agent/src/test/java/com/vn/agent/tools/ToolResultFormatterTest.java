package com.vn.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.metadata.MetamodelScanner;
import io.jmix.core.EntityStates;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link ToolResultFormatter} — focuses on the static
 * {@code escapeDataDelimiters} helper (Pitfall 4) and the JSON shape of the
 * error + count payloads. Prompt-injection behavior is pinned by
 * {@link PromptInjectionHarnessTest} end-to-end.
 */
class ToolResultFormatterTest {

    @Test
    void escapeDataDelimitersLeavesBenignStringsAlone() {
        assertThat(ToolResultFormatter.escapeDataDelimiters("foo bar"))
                .isEqualTo("foo bar");
    }

    @Test
    void escapeDataDelimitersReplacesBothForms() {
        assertThat(ToolResultFormatter.escapeDataDelimiters("<data>x</data>"))
                .isEqualTo("&lt;data&gt;x&lt;/data&gt;");
    }

    @Test
    void escapeDataDelimitersHandlesRepeatedAndAdjacent() {
        assertThat(ToolResultFormatter.escapeDataDelimiters("<data><data></data>"))
                .isEqualTo("&lt;data&gt;&lt;data&gt;&lt;/data&gt;");
    }

    @Test
    void escapeDataDelimitersHandlesNull() {
        assertThat(ToolResultFormatter.escapeDataDelimiters(null)).isNull();
    }

    @Test
    void errorDtoSerializesErrorAndReason() {
        ToolResultFormatter f = new ToolResultFormatter(new ObjectMapper(), mock(MetamodelScanner.class), mock(EntityStates.class));
        String json = f.error("bad_filter", "depth exceeded");
        assertThat(json)
                .contains("\"error\":\"bad_filter\"")
                .contains("\"reason\":\"depth exceeded\"");
    }

    @Test
    void errorFromToolUserErrorSerializesExpectedList() {
        ToolResultFormatter f = new ToolResultFormatter(new ObjectMapper(), mock(MetamodelScanner.class), mock(EntityStates.class));
        ToolUserError e = new ToolUserError("unknown_operation", "bad",
                java.util.List.of("EQUAL", "NOT_EQUAL"));
        String json = f.error(e);
        assertThat(json)
                .contains("\"error\":\"unknown_operation\"")
                .contains("\"reason\":\"bad\"")
                .contains("\"expected\":[\"EQUAL\",\"NOT_EQUAL\"]");
    }

    @Test
    void countSerializesEntityNameAndCount() {
        ToolResultFormatter f = new ToolResultFormatter(new ObjectMapper(), mock(MetamodelScanner.class), mock(EntityStates.class));
        MetaClass mc = mock(MetaClass.class);
        when(mc.getName()).thenReturn("jmixapp_Order");

        String json = f.count(mc, 42L);
        assertThat(json)
                .contains("\"entityName\":\"jmixapp_Order\"")
                .contains("\"count\":42");
    }

    @Test
    void toJsonWrapsArbitraryValue() {
        ToolResultFormatter f = new ToolResultFormatter(new ObjectMapper(), mock(MetamodelScanner.class), mock(EntityStates.class));
        String json = f.toJson(java.util.List.of(java.util.Map.of("name", "foo")));
        assertThat(json).isEqualTo("[{\"name\":\"foo\"}]");
    }
}
