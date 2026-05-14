package com.vn.agent.orchestration;

import com.vn.agent.conversation.AiAgentTitleProperties;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.tools.ToolLimits;
import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import io.jmix.core.UnconstrainedDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sibling resolver to {@link AiParametersResolver} for operator-runtime knobs persisted on
 * {@link AiUiSettings} (Phase 16 D-03). Read fresh per turn — Phase 18 memoization slot lives in
 * its own future class subscribing to {@code AiSettingsChangedEvent(kind=UI_SETTINGS)}.
 *
 * <p>NEVER inject {@code ApplicationEventPublisher} here — only {@code AiUiSettingsEntityListener}
 * publishes (Plan 10-06 R2 invariant inherited by Phase 16).
 *
 * <p>Read-through contract for each {@code resolveXxx()} method:
 * <ol>
 *   <li>Load the {@link AiUiSettings#SINGLETON_ID} row via {@link UnconstrainedDataManager}
 *       (Pitfall 3 — non-admin chat turns must succeed even though row-level security gates
 *       {@code AiUiSettings} reads through regular {@code DataManager}).</li>
 *   <li>If the singleton column is non-null, return it verbatim (sentinel {@code -1} passes
 *       through unchanged — Phase 13.1 invariant).</li>
 *   <li>Otherwise, return the {@code @ConfigurationProperties} bean's {@code resolvedXxx()}
 *       value (which itself may fall through to a documented constant default).</li>
 * </ol>
 *
 * <p>The singleton load is wrapped in a try/catch over {@link RuntimeException}: a transient
 * DB hiccup logs WARN and falls through to the property defaults so a chat turn is never
 * broken by an outage on the settings table.
 *
 * <p>codex HIGH Concern #5 split: chat task-file per-file upload cap
 * ({@link #resolveTaskFileMaxFileSizeBytes()}) and KB/RAG upload cap
 * ({@link #resolveRagUploadMaxFileSizeBytes()}) are DISTINCT methods backed by DISTINCT
 * columns and DISTINCT property beans.
 */
@Component
public class AiUiSettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(AiUiSettingsResolver.class);

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final AiTaskFileProperties taskFileProperties;
    private final AiAgentMutationProperties mutationProperties;
    private final AiAgentPromptProperties promptProperties;
    private final AiAgentTitleProperties titleProperties;
    private final AiAgentRagProperties ragProperties;
    private final int defaultMaxFilterDepth;

    public AiUiSettingsResolver(UnconstrainedDataManager unconstrainedDataManager,
                                AiTaskFileProperties taskFileProperties,
                                AiAgentMutationProperties mutationProperties,
                                AiAgentPromptProperties promptProperties,
                                AiAgentTitleProperties titleProperties,
                                AiAgentRagProperties ragProperties,
                                @Value("${jmix.ai-agent.tools.max-filter-depth:" + ToolLimits.DEFAULT_MAX_FILTER_DEPTH + "}")
                                int defaultMaxFilterDepth) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.taskFileProperties = taskFileProperties;
        this.mutationProperties = mutationProperties;
        this.promptProperties = promptProperties;
        this.titleProperties = titleProperties;
        this.ragProperties = ragProperties;
        this.defaultMaxFilterDepth = defaultMaxFilterDepth;
    }

    /**
     * Loads {@link AiUiSettings#SINGLETON_ID} via {@link UnconstrainedDataManager} (Pitfall 3 —
     * non-admin chat turns must succeed). Returns {@code null} when the row is absent OR when
     * the load throws (Pattern C resilience: a transient DB hiccup must not break a chat turn).
     */
    private AiUiSettings loadSingleton() {
        try {
            return unconstrainedDataManager.load(AiUiSettings.class)
                    .id(AiUiSettings.SINGLETON_ID)
                    .optional()
                    .orElse(null);
        } catch (RuntimeException persistenceFailure) {
            log.warn("Unable to load AiUiSettings singleton; falling back to property defaults: {}",
                    persistenceFailure.getMessage());
            log.debug("AiUiSettings loadSingleton persistence failure details", persistenceFailure);
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // Task-file cluster (4 methods — TTL, per-turn caps, per-file upload cap)
    // ----------------------------------------------------------------------

    /** TTL in seconds (sentinel {@code -1} disables cleanup — Phase 13.1 invariant). */
    public long resolveTaskFileTtlSeconds() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTaskFileTtlSeconds() != null) {
            return settings.getTaskFileTtlSeconds();
        }
        return taskFileProperties.getTtlSeconds();
    }

    /** Per-turn file count cap (sentinel {@code -1} disables — Phase 13.1 invariant). */
    public int resolveTaskFilePerTurnMaxFiles() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTaskFilePerTurnMaxFiles() != null) {
            return settings.getTaskFilePerTurnMaxFiles();
        }
        return taskFileProperties.getPerTurnMaxFiles();
    }

    /** Per-turn total Media bytes cap (sentinel {@code -1} disables — Phase 13.1 invariant). */
    public long resolveTaskFilePerTurnMaxTotalBytes() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTaskFilePerTurnMaxTotalBytes() != null) {
            return settings.getTaskFilePerTurnMaxTotalBytes();
        }
        return taskFileProperties.getPerTurnMaxTotalBytes();
    }

    /**
     * Chat task-file per-file upload size cap (codex HIGH Concern #5 — DISTINCT from
     * {@link #resolveRagUploadMaxFileSizeBytes()}; consumed by the chat task-file dropzone in
     * {@code ChatPanelFragment}).
     */
    public long resolveTaskFileMaxFileSizeBytes() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTaskFileMaxFileSizeBytes() != null) {
            return settings.getTaskFileMaxFileSizeBytes();
        }
        return taskFileProperties.getMaxFileSizeBytes();
    }

    // ----------------------------------------------------------------------
    // Mutation cluster (3 methods — confirmation, idempotency TTL, bulk cap)
    // ----------------------------------------------------------------------

    /** UX-hint metadata; mutation tools consume via the per-tool gate chain (Phase 11). */
    public boolean resolveMutationConfirmationRequired() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getMutationConfirmationRequired() != null) {
            return settings.getMutationConfirmationRequired();
        }
        return mutationProperties.resolvedConfirmationRequired();
    }

    /**
     * Mutation-intent idempotency TTL in SECONDS. Phase 16 D-01: stored as seconds (Long
     * column) on {@link AiUiSettings} to avoid JPA {@code Duration} mapping. Property fallback
     * coerces the bean's {@code Duration resolvedIdempotencyTtl()} to seconds at the resolver
     * boundary so every caller receives a uniform long-seconds return type. Consumers that
     * need {@link java.time.Duration} call {@code Duration.ofSeconds(seconds)} on the
     * consumption side (opencode MEDIUM Concern #4).
     */
    public long resolveMutationIdempotencyTtlSeconds() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getMutationIdempotencyTtlSeconds() != null) {
            return settings.getMutationIdempotencyTtlSeconds();
        }
        return mutationProperties.resolvedIdempotencyTtl().toSeconds();
    }

    /** Phase 13 D-02 DoS guard — max rows per {@code bulk_save_records} call. */
    public int resolveMutationBulkMaxRows() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getMutationBulkMaxRows() != null) {
            return settings.getMutationBulkMaxRows();
        }
        return mutationProperties.resolvedBulkMaxRows();
    }

    // ----------------------------------------------------------------------
    // Prompt / tools cluster (2 methods)
    // ----------------------------------------------------------------------

    /** {@code agent.entities} block truncation cap (Phase 9 D-03). */
    public int resolvePromptEntityInventoryLimit() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getPromptEntityInventoryLimit() != null) {
            return settings.getPromptEntityInventoryLimit();
        }
        return promptProperties.resolvedEntityInventoryLimit();
    }

    /**
     * Structured-filter attribute-path depth cap (Phase 9 D-08). Property fallback reads
     * {@code jmix.ai-agent.tools.max-filter-depth} via {@link Value @Value} because the
     * underlying knob is not bound to a {@code @ConfigurationProperties} record at the time
     * of this phase (it lives as a free property consumed by
     * {@code StructuredFilterConditionMapper}'s constructor-side {@code @Value} injection).
     */
    public int resolveToolsMaxFilterDepth() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getToolsMaxFilterDepth() != null) {
            return settings.getToolsMaxFilterDepth();
        }
        return defaultMaxFilterDepth;
    }

    // ----------------------------------------------------------------------
    // Title cluster (2 methods)
    // ----------------------------------------------------------------------

    /** Max history messages forwarded to the title model. */
    public int resolveTitleMaxContextMessages() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTitleMaxContextMessages() != null) {
            return settings.getTitleMaxContextMessages();
        }
        return titleProperties.resolvedMaxContextMessages();
    }

    /** Assistant-message-count threshold that triggers an auto-title attempt. */
    public int resolveTitleMinAssistantMessagesTrigger() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getTitleMinAssistantMessagesTrigger() != null) {
            return settings.getTitleMinAssistantMessagesTrigger();
        }
        return titleProperties.resolvedMinAssistantMessagesTrigger();
    }

    // ----------------------------------------------------------------------
    // RAG/KB upload cap (codex HIGH Concern #5 — DISTINCT from task-file cap)
    // ----------------------------------------------------------------------

    /**
     * KB / RAG document upload size cap (codex HIGH Concern #5 — DISTINCT from
     * {@link #resolveTaskFileMaxFileSizeBytes()}; consumed by the knowledge base upload UI).
     * Backed by {@code AiUiSettings.uploadMaxFileSizeBytes} and
     * {@code AiAgentRagProperties.upload.maxFileSizeBytes}.
     */
    public long resolveRagUploadMaxFileSizeBytes() {
        AiUiSettings settings = loadSingleton();
        if (settings != null && settings.getUploadMaxFileSizeBytes() != null) {
            return settings.getUploadMaxFileSizeBytes();
        }
        return ragProperties.resolvedUploadMaxFileSizeBytes();
    }
}
