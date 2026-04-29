package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * Test-only parent host entity exercising the <b>supported v1.1 related-write shape</b>:
 * non-composition {@code @OneToMany(mappedBy=...)} parent collection where the child-side
 * inverse is single-valued {@code @ManyToOne}. {@code add_related_record} /
 * {@code remove_related_record} MUST accept this shape (Plan 11-07B must-haves contract).
 *
 * <p>Shape (locked):
 * <ul>
 *     <li>{@code linkedChildren} — non-{@code @Composition} {@code @OneToMany(mappedBy="linkedParent")}.
 *         No {@code orphanRemoval}; no {@code cascade=ALL/REMOVE/DELETE}.</li>
 *     <li>Inverse property: {@link MutationLinkedChildFixture#getLinkedParent}
 *         ({@code @ManyToOne}, optional, no {@code @JoinColumn(nullable=false)}) — so the
 *         resolver's "required-inverse clearing" branch does NOT trigger for remove.</li>
 * </ul>
 */
@JmixEntity
@Entity(name = "mutationTest_MutationLinkedParentFixture")
@Table(name = "MUTATION_LINKED_PARENT_FIXTURE")
public class MutationLinkedParentFixture {

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

    @OneToMany(mappedBy = "linkedParent")
    private List<MutationLinkedChildFixture> linkedChildren;

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

    public List<MutationLinkedChildFixture> getLinkedChildren() {
        return linkedChildren;
    }

    public void setLinkedChildren(List<MutationLinkedChildFixture> linkedChildren) {
        this.linkedChildren = linkedChildren;
    }
}
