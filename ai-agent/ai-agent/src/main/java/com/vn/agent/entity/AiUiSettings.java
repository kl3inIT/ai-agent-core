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
}
