package com.vn.agent.entity;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.exposure.AiInternalEntityNames;
import io.jmix.core.Metadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.util.Arrays;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AITestConfiguration.class)
class AiUiSettingsModelTest {

    @Autowired
    Metadata metadata;

    @Test
    void chatSurfaceEnumContainsTheThreeSupportedSurfaceIds() {
        assertThat(AiChatSurface.values()).hasSize(3);
        assertThat(AiChatSurface.values())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON, AiChatSurface.SIDEBAR);
        assertThat(AiChatSurface.FULL_ROUTE.getId()).isEqualTo("FULL_ROUTE");
        assertThat(AiChatSurface.HEADER_BUTTON.getId()).isEqualTo("HEADER_BUTTON");
        assertThat(AiChatSurface.SIDEBAR.getId()).isEqualTo("SIDEBAR");
        assertThat(AiChatSurface.fromId("FULL_ROUTE")).isEqualTo(AiChatSurface.FULL_ROUTE);
        assertThat(AiChatSurface.fromId("HEADER_BUTTON")).isEqualTo(AiChatSurface.HEADER_BUTTON);
        assertThat(AiChatSurface.fromId("SIDEBAR")).isEqualTo(AiChatSurface.SIDEBAR);
        assertThat(AiChatSurface.fromId("UNKNOWN_SURFACE")).isNull();
    }

    @Test
    void uiSettingsDefaultsAndTypedSurfaceHelpersUseDeterministicTextIds() {
        AiUiSettings settings = metadata.create(AiUiSettings.class);

        assertThat(settings.getDefaultSurface()).isEqualTo(AiChatSurface.FULL_ROUTE);
        assertThat(settings.getEnabledSurfaceSet())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON, AiChatSurface.SIDEBAR);
        assertThat(settings.getEnabledSurfaceIds()).isEqualTo("FULL_ROUTE,HEADER_BUTTON,SIDEBAR");

        settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.HEADER_BUTTON));

        assertThat(settings.getEnabledSurfaceIds()).isEqualTo("HEADER_BUTTON");
        assertThat(settings.getEnabledSurfaceSet()).containsExactly(AiChatSurface.HEADER_BUTTON);
    }

    @Test
    void enabledSurfaceIdsContainingSidebarParsesBackToSidebar() {
        AiUiSettings settings = metadata.create(AiUiSettings.class);

        settings.setEnabledSurfaceIds("FULL_ROUTE,SIDEBAR");

        assertThat(settings.getEnabledSurfaceSet())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.SIDEBAR);

        settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.SIDEBAR));

        assertThat(settings.getEnabledSurfaceIds()).isEqualTo("SIDEBAR");
        assertThat(settings.getEnabledSurfaceSet()).containsExactly(AiChatSurface.SIDEBAR);
    }

    @Test
    void uiSettingsDoesNotExposeEnabledSurfacesBeanProperty() throws IntrospectionException {
        assertThat(Arrays.stream(Introspector.getBeanInfo(AiUiSettings.class).getPropertyDescriptors())
                .map(propertyDescriptor -> propertyDescriptor.getName()))
                .doesNotContain("enabledSurfaces")
                .contains("enabledSurfaceIds", "defaultSurface", "enabledSurfaceSet");
    }

    @Test
    void uiSettingsEntityIsAlwaysHiddenFromLlmMetadata() {
        assertThat(AiInternalEntityNames.contains("ai_AiUiSettings")).isTrue();
    }
}
