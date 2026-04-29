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
 * Child of {@link MutationLinkedParentFixture}. Single-valued {@code @ManyToOne} inverse,
 * intentionally OPTIONAL (no {@code @JoinColumn(nullable=false)}, no {@code optional=false}),
 * so {@code remove_related_record} can clear the inverse without triggering the
 * required-inverse rejection branch in {@link com.vn.agent.tools.mutation.RelatedWriteMetadataResolver}.
 */
@JmixEntity
@Entity(name = "mutationTest_MutationLinkedChildFixture")
@Table(name = "MUTATION_LINKED_CHILD_FIXTURE")
public class MutationLinkedChildFixture {

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
    @JoinColumn(name = "LINKED_PARENT_ID")
    private MutationLinkedParentFixture linkedParent;

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

    public MutationLinkedParentFixture getLinkedParent() {
        return linkedParent;
    }

    public void setLinkedParent(MutationLinkedParentFixture linkedParent) {
        this.linkedParent = linkedParent;
    }
}
