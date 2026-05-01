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
