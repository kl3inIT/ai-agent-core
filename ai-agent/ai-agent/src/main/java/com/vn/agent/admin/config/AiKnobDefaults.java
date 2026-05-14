package com.vn.agent.admin.config;

import com.vn.agent.conversation.AiAgentTitleProperties;
import com.vn.agent.orchestration.AiAgentPromptProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.tools.ToolLimits;
import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Exposes the property-side default for each Tier-1 runtime knob so admin UI can render
 * placeholder + helperText next to an empty input. Mirrors the property-fallback branch of
 * {@link com.vn.agent.orchestration.AiUiSettingsResolver} — every getter here returns the
 * exact value the resolver would return when the corresponding {@code AiUiSettings} column
 * is {@code null}.
 *
 * <p>Keeping defaults DB-null is load-bearing: the resolver fallback chain (DB column →
 * {@code @ConfigurationProperties} → documented constant) lets host apps tune defaults
 * via {@code application.yml} without DB writes, and lets the addon evolve defaults in a
 * future release without dragging admins who never explicitly set a value.
 */
@Component
public class AiKnobDefaults {

    private final AiTaskFileProperties taskFileProperties;
    private final AiAgentMutationProperties mutationProperties;
    private final AiAgentPromptProperties promptProperties;
    private final AiAgentTitleProperties titleProperties;
    private final AiAgentRagProperties ragProperties;
    private final int defaultMaxFilterDepth;

    public AiKnobDefaults(AiTaskFileProperties taskFileProperties,
                          AiAgentMutationProperties mutationProperties,
                          AiAgentPromptProperties promptProperties,
                          AiAgentTitleProperties titleProperties,
                          AiAgentRagProperties ragProperties,
                          @Value("${jmix.ai-agent.tools.max-filter-depth:" + ToolLimits.DEFAULT_MAX_FILTER_DEPTH + "}")
                          int defaultMaxFilterDepth) {
        this.taskFileProperties = taskFileProperties;
        this.mutationProperties = mutationProperties;
        this.promptProperties = promptProperties;
        this.titleProperties = titleProperties;
        this.ragProperties = ragProperties;
        this.defaultMaxFilterDepth = defaultMaxFilterDepth;
    }

    public long taskFileTtlSeconds() {
        return taskFileProperties.getTtlSeconds();
    }

    public int taskFilePerTurnMaxFiles() {
        return taskFileProperties.getPerTurnMaxFiles();
    }

    public long taskFilePerTurnMaxTotalBytes() {
        return taskFileProperties.getPerTurnMaxTotalBytes();
    }

    public long taskFileMaxFileSizeBytes() {
        return taskFileProperties.getMaxFileSizeBytes();
    }

    public boolean mutationConfirmationRequired() {
        return mutationProperties.resolvedConfirmationRequired();
    }

    public long mutationIdempotencyTtlSeconds() {
        return mutationProperties.resolvedIdempotencyTtl().toSeconds();
    }

    public int mutationBulkMaxRows() {
        return mutationProperties.resolvedBulkMaxRows();
    }

    public int promptEntityInventoryLimit() {
        return promptProperties.resolvedEntityInventoryLimit();
    }

    public int toolsMaxFilterDepth() {
        return defaultMaxFilterDepth;
    }

    public int titleMaxContextMessages() {
        return titleProperties.resolvedMaxContextMessages();
    }

    public int titleMinAssistantMessagesTrigger() {
        return titleProperties.resolvedMinAssistantMessagesTrigger();
    }

    public long uploadMaxFileSizeBytes() {
        return ragProperties.resolvedUploadMaxFileSizeBytes();
    }
}
