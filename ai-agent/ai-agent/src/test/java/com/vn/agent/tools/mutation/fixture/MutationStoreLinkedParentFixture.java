package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Plan 17-01 Task 3 (MUT-16, Open Question 1 option a) — agentstore-backed parent target for
 * the batch FK-load SELECT-count proxy ({@link com.vn.agent.performance.MutationFkBatchLoadQueryCountTest}).
 *
 * <p>Mirrors {@link MutationLinkedParentFixture} (UUID {@code @JmixGeneratedValue} id,
 * {@code @Version}, {@code @InstanceName} name) but carries {@code @Store("agentstore")} and
 * distinct entity/table names. The {@code @OneToMany} collection is intentionally OMITTED — the
 * FK fan-out the SELECT-count test exercises lives on the CHILD side
 * ({@link MutationStoreLinkedChildFixture#getStoreLinkedParent}).
 *
 * <p><b>Why agentstore.</b> The existing {@code QueryCountingDataSourceConfiguration} wraps ONLY
 * the {@code agentstoreDataSource} bean. Placing these FK fixtures in the agentstore lets the
 * shared counting harness observe the batch FK {@code .ids(...)} SELECT without widening the
 * counting config (lower divergence per RESEARCH Open Question 1).
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "mutationTest_MutationStoreLinkedParentFixture")
@Table(name = "MUTATION_STORE_LINKED_PARENT_FIXTURE")
public class MutationStoreLinkedParentFixture {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "NAME_", length = 255)
    private String name;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
