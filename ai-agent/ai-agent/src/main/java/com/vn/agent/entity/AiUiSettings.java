package com.vn.agent.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiUiSettings")
@Table(name = "AI_UI_SETTINGS")
public class AiUiSettings {

    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000120001");

    private static final String SURFACE_ID_SEPARATOR = ",";

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotNull
    @Column(name = "ENABLED_SURFACE_IDS", nullable = false)
    private String enabledSurfaceIds = toEnabledSurfaceIds(EnumSet.allOf(AiChatSurface.class));

    @NotNull
    @Column(name = "DEFAULT_SURFACE", nullable = false, length = 64)
    private String defaultSurface = AiChatSurface.FULL_ROUTE.getId();

    // Phase 16 D-01 Tier-1 runtime knobs — null = fall through to module.properties default (resolved by AiUiSettingsResolver). Setter names MUST match field names (EclipseLink weaver — see setDefaultSurface docstring lines 102-108).
    @Min(-1L)
    @Max(604_800L)
    @Column(name = "TASK_FILE_TTL_SECONDS")
    private Long taskFileTtlSeconds;

    @Min(-1)
    @Max(100)
    @Column(name = "TASK_FILE_PER_TURN_MAX_FILES")
    private Integer taskFilePerTurnMaxFiles;

    @Min(-1L)
    @Max(524_288_000L)
    @Column(name = "TASK_FILE_PER_TURN_MAX_TOTAL_BYTES")
    private Long taskFilePerTurnMaxTotalBytes;

    @Min(1024L)
    @Max(524_288_000L)
    @Column(name = "TASK_FILE_MAX_FILE_SIZE_BYTES")
    private Long taskFileMaxFileSizeBytes;

    @Column(name = "MUTATION_CONFIRMATION_REQUIRED")
    private Boolean mutationConfirmationRequired;

    @Min(60L)
    @Max(604_800L)
    @Column(name = "MUTATION_IDEMPOTENCY_TTL_SECONDS")
    private Long mutationIdempotencyTtlSeconds;

    @Min(1)
    @Max(500)
    @Column(name = "MUTATION_BULK_MAX_ROWS")
    private Integer mutationBulkMaxRows;

    @Min(1)
    @Max(500)
    @Column(name = "PROMPT_ENTITY_INVENTORY_LIMIT")
    private Integer promptEntityInventoryLimit;

    @Min(1)
    @Max(10)
    @Column(name = "TOOLS_MAX_FILTER_DEPTH")
    private Integer toolsMaxFilterDepth;

    @Min(1)
    @Max(50)
    @Column(name = "TITLE_MAX_CONTEXT_MESSAGES")
    private Integer titleMaxContextMessages;

    @Min(1)
    @Max(10)
    @Column(name = "TITLE_MIN_ASSISTANT_MESSAGES_TRIGGER")
    private Integer titleMinAssistantMessagesTrigger;

    @Min(1024L)
    @Max(524_288_000L)
    @Column(name = "UPLOAD_MAX_FILE_SIZE_BYTES")
    private Long uploadMaxFileSizeBytes;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @InstanceName
    public String getInstanceName() {
        return "AiUiSettings";
    }

    @Transient
    public Set<AiChatSurface> getEnabledSurfaceSet() {
        if (enabledSurfaceIds == null || enabledSurfaceIds.isBlank()) {
            return Collections.emptySet();
        }

        EnumSet<AiChatSurface> surfaces = Arrays.stream(enabledSurfaceIds.split(SURFACE_ID_SEPARATOR))
                .map(String::trim)
                .map(AiChatSurface::fromId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AiChatSurface.class)));
        return Collections.unmodifiableSet(surfaces);
    }

    public void setEnabledSurfaceSet(Set<AiChatSurface> enabledSurfaceSet) {
        // Route through the public setter so EclipseLink's setter weaving fires the
        // property-change event and the Jmix DataContext flags the entity dirty.
        // Direct field assignment (this.enabledSurfaceIds = ...) bypasses weaving and
        // produces a silent no-op save (version unchanged, value reverts on revisit).
        setEnabledSurfaceIds(toEnabledSurfaceIds(enabledSurfaceSet));
    }

    public AiChatSurface getDefaultSurface() {
        return defaultSurface == null ? null : AiChatSurface.fromId(defaultSurface);
    }

    public void setDefaultSurface(AiChatSurface defaultSurface) {
        // The JPA field is named `defaultSurface` (String) — EclipseLink's weaver treats
        // this very method (which has the matching name) as the property setter, so
        // direct field assignment here IS tracked. (Contrast with setEnabledSurfaceSet,
        // which has a non-matching name and must route via setEnabledSurfaceIds.)
        this.defaultSurface = defaultSurface == null ? null : defaultSurface.getId();
    }

    private static String toEnabledSurfaceIds(Set<AiChatSurface> enabledSurfaceSet) {
        if (enabledSurfaceSet == null || enabledSurfaceSet.isEmpty()) {
            return "";
        }
        return Arrays.stream(AiChatSurface.values())
                .filter(enabledSurfaceSet::contains)
                .map(AiChatSurface::getId)
                .collect(Collectors.joining(SURFACE_ID_SEPARATOR));
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getEnabledSurfaceIds() {
        return enabledSurfaceIds;
    }

    public void setEnabledSurfaceIds(String enabledSurfaceIds) {
        this.enabledSurfaceIds = enabledSurfaceIds;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public OffsetDateTime getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    // --- Phase 16 D-01 Tier-1 runtime knob accessors ---
    // Setter names MUST match field names exactly so EclipseLink's setter-weaver fires
    // the property-change event and Jmix's DataContext flags the entity dirty.
    // See setDefaultSurface docstring above for the underlying weaver invariant.

    public Long getTaskFileTtlSeconds() {
        return taskFileTtlSeconds;
    }

    public void setTaskFileTtlSeconds(Long taskFileTtlSeconds) {
        this.taskFileTtlSeconds = taskFileTtlSeconds;
    }

    public Integer getTaskFilePerTurnMaxFiles() {
        return taskFilePerTurnMaxFiles;
    }

    public void setTaskFilePerTurnMaxFiles(Integer taskFilePerTurnMaxFiles) {
        this.taskFilePerTurnMaxFiles = taskFilePerTurnMaxFiles;
    }

    public Long getTaskFilePerTurnMaxTotalBytes() {
        return taskFilePerTurnMaxTotalBytes;
    }

    public void setTaskFilePerTurnMaxTotalBytes(Long taskFilePerTurnMaxTotalBytes) {
        this.taskFilePerTurnMaxTotalBytes = taskFilePerTurnMaxTotalBytes;
    }

    public Long getTaskFileMaxFileSizeBytes() {
        return taskFileMaxFileSizeBytes;
    }

    public void setTaskFileMaxFileSizeBytes(Long taskFileMaxFileSizeBytes) {
        this.taskFileMaxFileSizeBytes = taskFileMaxFileSizeBytes;
    }

    public Boolean getMutationConfirmationRequired() {
        return mutationConfirmationRequired;
    }

    public void setMutationConfirmationRequired(Boolean mutationConfirmationRequired) {
        this.mutationConfirmationRequired = mutationConfirmationRequired;
    }

    public Long getMutationIdempotencyTtlSeconds() {
        return mutationIdempotencyTtlSeconds;
    }

    public void setMutationIdempotencyTtlSeconds(Long mutationIdempotencyTtlSeconds) {
        this.mutationIdempotencyTtlSeconds = mutationIdempotencyTtlSeconds;
    }

    public Integer getMutationBulkMaxRows() {
        return mutationBulkMaxRows;
    }

    public void setMutationBulkMaxRows(Integer mutationBulkMaxRows) {
        this.mutationBulkMaxRows = mutationBulkMaxRows;
    }

    public Integer getPromptEntityInventoryLimit() {
        return promptEntityInventoryLimit;
    }

    public void setPromptEntityInventoryLimit(Integer promptEntityInventoryLimit) {
        this.promptEntityInventoryLimit = promptEntityInventoryLimit;
    }

    public Integer getToolsMaxFilterDepth() {
        return toolsMaxFilterDepth;
    }

    public void setToolsMaxFilterDepth(Integer toolsMaxFilterDepth) {
        this.toolsMaxFilterDepth = toolsMaxFilterDepth;
    }

    public Integer getTitleMaxContextMessages() {
        return titleMaxContextMessages;
    }

    public void setTitleMaxContextMessages(Integer titleMaxContextMessages) {
        this.titleMaxContextMessages = titleMaxContextMessages;
    }

    public Integer getTitleMinAssistantMessagesTrigger() {
        return titleMinAssistantMessagesTrigger;
    }

    public void setTitleMinAssistantMessagesTrigger(Integer titleMinAssistantMessagesTrigger) {
        this.titleMinAssistantMessagesTrigger = titleMinAssistantMessagesTrigger;
    }

    public Long getUploadMaxFileSizeBytes() {
        return uploadMaxFileSizeBytes;
    }

    public void setUploadMaxFileSizeBytes(Long uploadMaxFileSizeBytes) {
        this.uploadMaxFileSizeBytes = uploadMaxFileSizeBytes;
    }

    // ---- Phase 16 WR-01 cross-field validators for discontinuous sentinel ranges ----
    // The plain @Min(-1)/@Max(...) annotations on the sentinel-bearing fields accept every
    // value in the closed interval — including the "invalid gap" between -1 and the lower
    // bound documented in SPEC criterion 3. These @AssertTrue methods enforce the actual
    // discontinuous range {-1} ∪ [lower, upper], so a saved value like ttlSeconds=5
    // (5-second TTL — files expire immediately on upload) is rejected at validate time.

    /**
     * SPEC criterion 3: {@code taskFileTtlSeconds ∈ {-1} ∪ [60, 604_800]}.
     * Value -1 disables cleanup (Phase 13.1 sentinel); otherwise the TTL must be at least
     * one minute to be useful. Null falls through to module.properties default.
     */
    @AssertTrue(message = "{aiUiSettings.validation.taskFileTtl.range}")
    @Transient
    public boolean isTaskFileTtlSecondsRangeValid() {
        if (taskFileTtlSeconds == null) {
            return true;
        }
        long v = taskFileTtlSeconds;
        return v == -1L || (v >= 60L && v <= 604_800L);
    }

    /**
     * SPEC criterion 3: {@code taskFilePerTurnMaxFiles ∈ {-1} ∪ [1, 100]}.
     * Value -1 disables per-turn limit (sentinel); 0 would silently break uploads.
     */
    @AssertTrue(message = "{aiUiSettings.validation.taskFilePerTurnMaxFiles.range}")
    @Transient
    public boolean isTaskFilePerTurnMaxFilesRangeValid() {
        if (taskFilePerTurnMaxFiles == null) {
            return true;
        }
        int v = taskFilePerTurnMaxFiles;
        return v == -1 || (v >= 1 && v <= 100);
    }

    /**
     * SPEC criterion 3: {@code taskFilePerTurnMaxTotalBytes ∈ {-1} ∪ [1, 524_288_000]}.
     * Value -1 disables per-turn total-bytes limit (sentinel); 0 would silently disable uploads.
     */
    @AssertTrue(message = "{aiUiSettings.validation.taskFilePerTurnMaxTotalBytes.range}")
    @Transient
    public boolean isTaskFilePerTurnMaxTotalBytesRangeValid() {
        if (taskFilePerTurnMaxTotalBytes == null) {
            return true;
        }
        long v = taskFilePerTurnMaxTotalBytes;
        return v == -1L || (v >= 1L && v <= 524_288_000L);
    }
}
