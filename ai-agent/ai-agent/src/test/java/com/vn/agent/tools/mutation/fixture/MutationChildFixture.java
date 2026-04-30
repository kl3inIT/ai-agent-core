package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Child of {@link MutationParentFixture} for the composition/orphanRemoval rejection branch.
 * Inverse {@code parent} is single-valued {@code @ManyToOne} so the inverse-shape gate
 * passes; the resolver still rejects because the parent collection side is {@code @Composition}.
 */
@JmixEntity
@Entity(name = "mutationTest_MutationChildFixture")
@Table(name = "MUTATION_CHILD_FIXTURE")
public class MutationChildFixture {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "LABEL_", length = 255)
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PARENT_ID")
    private MutationParentFixture parent;

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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public MutationParentFixture getParent() {
        return parent;
    }

    public void setParent(MutationParentFixture parent) {
        this.parent = parent;
    }
}
