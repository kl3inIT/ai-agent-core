package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * Test-only parent host entity exercising the <b>composition</b> + <b>orphanRemoval</b>
 * relationship shape that {@code add_related_record} / {@code remove_related_record} MUST
 * reject as {@code validation_failed} (Plan 11-07B must-haves contract).
 *
 * <p>Shape (locked):
 * <ul>
 *     <li>{@code children} is {@code @Composition} + {@code @OneToMany(mappedBy="parent",
 *         orphanRemoval=true)} — both rejection signals (Type.COMPOSITION via @Composition
 *         and orphanRemoval=true) trigger the resolver's fail-closed branch.</li>
 *     <li>Inverse property is {@link MutationChildFixture#getParent} (single-valued
 *         {@code @ManyToOne}); the resolver verifies inverse single-valued shape is correct
 *         then rejects on the composition/orphanRemoval flags before any save.</li>
 * </ul>
 */
@JmixEntity
@Entity(name = "mutationTest_MutationParentFixture")
@Table(name = "MUTATION_PARENT_FIXTURE")
public class MutationParentFixture {

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

    @Composition
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MutationChildFixture> children;

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

    public List<MutationChildFixture> getChildren() {
        return children;
    }

    public void setChildren(List<MutationChildFixture> children) {
        this.children = children;
    }
}
