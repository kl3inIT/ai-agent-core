package com.vn.agent.view.uisettings;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.component.checkboxgroup.JmixCheckboxGroup;
import io.jmix.flowui.component.radiobuttongroup.JmixRadioButtonGroup;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
class AiUiSettingsDetailViewTest {

    @Autowired
    DialogWindows dialogWindows;
    @Autowired
    UnconstrainedDataManager unconstrainedDataManager;
    @Autowired
    SystemAuthenticator systemAuthenticator;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        unconstrainedDataManager.load(AiUiSettings.class)
                .all()
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void controllerUsesSingletonDetailViewIdentity() {
        ViewController viewController = AiUiSettingsDetailView.class.getAnnotation(ViewController.class);
        ViewDescriptor viewDescriptor = AiUiSettingsDetailView.class.getAnnotation(ViewDescriptor.class);

        assertThat(StandardDetailView.class).isAssignableFrom(AiUiSettingsDetailView.class);
        assertThat(viewController).isNotNull();
        assertThat(viewController.value()).isEqualTo("AiAgent_AiUiSettings.detail");
        assertThat(viewDescriptor).isNotNull();
        assertThat(viewDescriptor.value()).isEqualTo("ai-ui-settings-detail-view.xml");
    }

    @Test
    void descriptorUsesControllerManagedSurfaceFields() throws Exception {
        Document document = readDescriptor();

        Element enabledSurfacesField = elementById(document, "enabledSurfacesField");
        Element defaultSurfaceField = elementById(document, "defaultSurfaceField");

        assertThat(enabledSurfacesField.getTagName()).isEqualTo("checkboxGroup");
        assertThat(enabledSurfacesField.getAttribute("property")).isEmpty();
        assertThat(defaultSurfaceField.getTagName()).isEqualTo("radioButtonGroup");
        assertThat(allActionIds(document))
                .doesNotContain("createAction", "removeAction", "deleteAction",
                        "list_create", "list_remove");
    }

    @Test
    void adminCanEditSaveAndReloadSingleton() {
        systemAuthenticator.runWithUser("admin", () -> {
            AiUiSettingsDetailView view = openDialog();

            JmixCheckboxGroup<AiChatSurface> enabledSurfacesField =
                    UiTestUtils.getComponent(view, "enabledSurfacesField");
            JmixRadioButtonGroup<AiChatSurface> defaultSurfaceField =
                    UiTestUtils.getComponent(view, "defaultSurfaceField");
            JmixButton saveAndCloseButton = UiTestUtils.getComponent(view, "saveAndCloseButton");

            enabledSurfacesField.setValue(EnumSet.of(AiChatSurface.HEADER_BUTTON));
            defaultSurfaceField.setValue(AiChatSurface.HEADER_BUTTON);
            saveAndCloseButton.click();
        });

        AiUiSettings reloaded = unconstrainedDataManager.load(AiUiSettings.class)
                .id(AiUiSettings.SINGLETON_ID)
                .one();

        assertThat(reloaded.getEnabledSurfaceSet()).containsExactly(AiChatSurface.HEADER_BUTTON);
        assertThat(reloaded.getDefaultSurface()).isEqualTo(AiChatSurface.HEADER_BUTTON);
    }

    @Test
    void validationRejectsEmptyEnabledSurfacesAndMissingDefaultSurface() {
        systemAuthenticator.runWithUser("admin", () -> {
            AiUiSettingsDetailView view = openDialog();

            JmixCheckboxGroup<AiChatSurface> enabledSurfacesField =
                    UiTestUtils.getComponent(view, "enabledSurfacesField");
            JmixRadioButtonGroup<AiChatSurface> defaultSurfaceField =
                    UiTestUtils.getComponent(view, "defaultSurfaceField");
            JmixButton saveAndCloseButton = UiTestUtils.getComponent(view, "saveAndCloseButton");

            enabledSurfacesField.setValue(Set.of());
            defaultSurfaceField.setValue(AiChatSurface.FULL_ROUTE);
            saveAndCloseButton.click();
        });

        AiUiSettings reloaded = unconstrainedDataManager.load(AiUiSettings.class)
                .id(AiUiSettings.SINGLETON_ID)
                .one();

        assertThat(reloaded.getEnabledSurfaceSet())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON);
        assertThat(reloaded.getDefaultSurface()).isEqualTo(AiChatSurface.FULL_ROUTE);

        systemAuthenticator.runWithUser("admin", () -> {
            AiUiSettingsDetailView view = openDialog();

            JmixCheckboxGroup<AiChatSurface> enabledSurfacesField =
                    UiTestUtils.getComponent(view, "enabledSurfacesField");
            JmixRadioButtonGroup<AiChatSurface> defaultSurfaceField =
                    UiTestUtils.getComponent(view, "defaultSurfaceField");
            JmixButton saveAndCloseButton = UiTestUtils.getComponent(view, "saveAndCloseButton");

            enabledSurfacesField.setValue(EnumSet.of(AiChatSurface.HEADER_BUTTON));
            defaultSurfaceField.setValue(AiChatSurface.FULL_ROUTE);
            saveAndCloseButton.click();
        });

        reloaded = unconstrainedDataManager.load(AiUiSettings.class)
                .id(AiUiSettings.SINGLETON_ID)
                .one();

        assertThat(reloaded.getEnabledSurfaceSet())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON);
        assertThat(reloaded.getDefaultSurface()).isEqualTo(AiChatSurface.FULL_ROUTE);
    }

    private AiUiSettingsDetailView openDialog() {
        DialogWindow<AiUiSettingsDetailView> dialogWindow = dialogWindows
                .view(UiTestUtils.getCurrentView(), AiUiSettingsDetailView.class)
                .build();
        dialogWindow.open();
        return dialogWindow.getView();
    }

    private static Document readDescriptor() throws Exception {
        try (InputStream stream = AiUiSettingsDetailViewTest.class.getResourceAsStream(
                "/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml")) {
            assertThat(stream).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementById(Document document, String id) {
        for (int i = 0; i < document.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("*").item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("Element not found: " + id);
    }

    private static Set<String> allActionIds(Document document) {
        Set<String> actionIds = new java.util.LinkedHashSet<>();
        for (int i = 0; i < document.getElementsByTagName("action").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("action").item(i);
            actionIds.add(element.getAttribute("id"));
            actionIds.add(element.getAttribute("type"));
        }
        return actionIds;
    }
}
