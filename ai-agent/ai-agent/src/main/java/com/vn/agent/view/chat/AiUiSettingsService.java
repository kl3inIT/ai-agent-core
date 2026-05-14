package com.vn.agent.view.chat;

import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.DataManager;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

@Component
public class AiUiSettingsService {

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;

    public AiUiSettingsService(DataManager dataManager,
                               UnconstrainedDataManager unconstrainedDataManager) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
    }

    public AiUiSettings loadCurrent() {
        return unconstrainedDataManager.load(AiUiSettings.class)
                .id(AiUiSettings.SINGLETON_ID)
                .optional()
                .orElseGet(this::createDefaultSettings);
    }

    /**
     * Admin-edit save path — routes through the regular {@link DataManager} so role
     * policies apply. {@link com.vn.agent.admin.config.AiUiSettingsEntityListener}
     * fires {@code AiSettingsChangedEvent(UI_SETTINGS)} from the resulting
     * {@code EntityChangedEvent}; never publish that event directly.
     */
    public AiUiSettings save(AiUiSettings settings) {
        return dataManager.save(settings);
    }

    private AiUiSettings createDefaultSettings() {
        AiUiSettings settings = dataManager.create(AiUiSettings.class);
        settings.setId(AiUiSettings.SINGLETON_ID);
        settings.setEnabledSurfaceSet(EnumSet.allOf(AiChatSurface.class));
        settings.setDefaultSurface(AiChatSurface.FULL_ROUTE);

        try {
            return unconstrainedDataManager.save(settings);
        } catch (RuntimeException exception) {
            return unconstrainedDataManager.load(AiUiSettings.class)
                    .id(AiUiSettings.SINGLETON_ID)
                    .optional()
                    .orElseThrow(() -> exception);
        }
    }
}
