package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
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
 * Plan 17-01 Task 3 (MUT-16, Open Question 1 option a) — agentstore-backed child carrying a
 * writable to-one FK to {@link MutationStoreLinkedParentFixture}.
 *
 * <p>Mirrors {@link MutationLinkedChildFixture} (UUID {@code @JmixGeneratedValue} id,
 * {@code @Version}, {@code @InstanceName} label, single-valued {@code @ManyToOne(fetch=LAZY)}
 * inverse) but carries {@code @Store("agentstore")} and distinct entity/table names. The
 * {@code storeLinkedParent} reference is a writable, NON-composition to-one so a
 * {@code bulk_save_records} batch can set it through {@code coerceAttributes} — exactly the
 * many-rows-pointing-at-one-parent shape the SELECT-count proxy needs to prove the FK load is
 * "1 query, not N".
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "mutationTest_MutationStoreLinkedChildFixture")
@Table(name = "MUTATION_STORE_LINKED_CHILD_FIXTURE")
public class MutationStoreLinkedChildFixture {

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
    @JoinColumn(name = "STORE_LINKED_PARENT_ID")
    private MutationStoreLinkedParentFixture storeLinkedParent;

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

    public MutationStoreLinkedParentFixture getStoreLinkedParent() {
        return storeLinkedParent;
    }

    public void setStoreLinkedParent(MutationStoreLinkedParentFixture storeLinkedParent) {
        this.storeLinkedParent = storeLinkedParent;
    }
}
