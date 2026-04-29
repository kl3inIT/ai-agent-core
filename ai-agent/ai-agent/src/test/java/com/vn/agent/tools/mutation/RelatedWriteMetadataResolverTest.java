package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import com.vn.agent.tools.mutation.fixture.MutationChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationLinkedChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture;
import com.vn.agent.tools.mutation.fixture.MutationParentFixture;
import io.jmix.core.Metadata;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan 11-07B Task 2 — focused helper-level tests for
 * {@link RelatedWriteMetadataResolver}. Uses CONCRETE Jmix metadata loaded from the
 * fixture entities (NOT pure mocks): the resolver's authority over the support matrix
 * must be proven against real {@link io.jmix.core.metamodel.model.MetaProperty} +
 * {@link io.jmix.core.metamodel.model.MetaProperty#getInverse} behavior, not a mocked
 * approximation.
 *
 * <p>The fixtures span both branches:
 * <ul>
 *     <li>{@link MutationLinkedParentFixture} / {@link MutationLinkedChildFixture} —
 *         supported v1.1 shape: non-composition {@code @OneToMany(mappedBy="linkedParent")}
 *         with single-valued {@code @ManyToOne} inverse.</li>
 *     <li>{@link MutationParentFixture} / {@link MutationChildFixture} — rejection branch:
 *         {@code @Composition} + {@code orphanRemoval=true}.</li>
 * </ul>
 *
 * <p>Test methods MUST stay concrete: every assertion exercises a real Jmix metaproperty.
 * Plan 11-07B does NOT add the broad add_related_record / remove_related_record tool tests
 * here — Plans 11-10 / 11-11 own those. This file proves the support matrix only.
 */
@SpringBootTest
class RelatedWriteMetadataResolverTest {

    @Autowired
    private Metadata metadata;

    @Autowired
    private RelatedWriteMetadataResolver resolver;

    // ----------------------------------------------------------------------
    // Supported branch — non-composition @OneToMany(mappedBy) + @ManyToOne inverse
    // ----------------------------------------------------------------------

    @Test
    void resolveSupportedRelatedWriteRelationship_acceptsLinkedChildren() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);

        RelatedWriteMetadataResolver.SupportedRelatedRelationship resolved =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        assertThat(resolved).isNotNull();
        assertThat(resolved.parentProperty().getName()).isEqualTo("linkedChildren");
        assertThat(resolved.childMetaClass().getJavaClass()).isEqualTo(MutationLinkedChildFixture.class);
        assertThat(resolved.childInverseProperty().getName()).isEqualTo("linkedParent");
    }

    // ----------------------------------------------------------------------
    // Rejection branches
    // ----------------------------------------------------------------------

    @Test
    void resolveSupportedRelatedWriteRelationship_rejectsCompositionWithOrphanRemoval() {
        MetaClass parentMetaClass = metadata.getClass(MutationParentFixture.class);

        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "children"))
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");
    }

    @Test
    void resolveSupportedRelatedWriteRelationship_rejectsUnknownRelationship() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);

        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "doesNotExist"))
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");
    }

    @Test
    void resolveSupportedRelatedWriteRelationship_rejectsBlankRelationshipName() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);

        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, ""))
                .isInstanceOf(ToolUserError.class);
        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, null))
                .isInstanceOf(ToolUserError.class);
    }

    @Test
    void resolveSupportedRelatedWriteRelationship_rejectsScalarAttribute() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);

        // "name" is a scalar — not a relationship at all; resolver must reject it the same as any
        // other unsupported metadata, NOT silently treat it as a single-valued relationship.
        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "name"))
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");
    }

    @Test
    void resolveSupportedRelatedWriteRelationship_rejectsParentSideToOneAttribute() {
        // The CHILD's "linkedParent" attribute is a single-valued to-one. add/remove_related_record
        // is for collection relationships only — the LLM should be calling update_record to swap
        // a to-one reference, not add/remove_related_record.
        MetaClass childMetaClass = metadata.getClass(MutationLinkedChildFixture.class);

        assertThatThrownBy(() -> resolver.resolveSupportedRelatedWriteRelationship(childMetaClass, "linkedParent"))
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");
    }

    // ----------------------------------------------------------------------
    // isCompositionOrDeleteCapable — direct gate verification
    // ----------------------------------------------------------------------

    @Test
    void isCompositionOrDeleteCapable_trueForCompositionWithOrphanRemoval() {
        MetaClass parentMetaClass = metadata.getClass(MutationParentFixture.class);

        assertThat(resolver.isCompositionOrDeleteCapable(parentMetaClass.getProperty("children")))
                .isTrue();
    }

    @Test
    void isCompositionOrDeleteCapable_falseForLinkedChildren() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);

        assertThat(resolver.isCompositionOrDeleteCapable(parentMetaClass.getProperty("linkedChildren")))
                .isFalse();
    }

    // ----------------------------------------------------------------------
    // wireInverseReference / clearInverseReference / childBelongsToParent — never rewrite parent
    // ----------------------------------------------------------------------

    @Test
    void wireInverseReference_setsChildSideInverseAndDoesNotTouchParentCollection() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);
        RelatedWriteMetadataResolver.SupportedRelatedRelationship rel =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        MutationLinkedParentFixture parent = metadata.create(MutationLinkedParentFixture.class);
        parent.setName("p1");
        MutationLinkedChildFixture child = metadata.create(MutationLinkedChildFixture.class);
        child.setLabel("c1");

        // Pre-condition: parent.linkedChildren is null AND inverse is null — confirms the helper
        // does not accidentally touch the parent collection on either side of the call.
        assertThat(parent.getLinkedChildren()).isNull();
        assertThat(child.getLinkedParent()).isNull();

        resolver.wireInverseReference(rel, parent, child);

        // Post-condition: child.linkedParent points to parent. parent.linkedChildren must remain
        // untouched (still null) — the resolver mutates ONLY the child-side inverse.
        Object inverseValue = EntityValues.getValue(child, "linkedParent");
        assertThat(inverseValue).isSameAs(parent);
        assertThat(parent.getLinkedChildren()).isNull();
    }

    @Test
    void clearInverseReference_clearsOnlyTheChildSideInverse() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);
        RelatedWriteMetadataResolver.SupportedRelatedRelationship rel =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        MutationLinkedParentFixture parent = metadata.create(MutationLinkedParentFixture.class);
        MutationLinkedChildFixture child = metadata.create(MutationLinkedChildFixture.class);
        child.setLinkedParent(parent);
        // parent.linkedChildren is intentionally NOT populated — we are testing that the
        // resolver does not require the parent collection to be in any particular shape.

        resolver.clearInverseReference(rel, child);

        assertThat(child.getLinkedParent()).isNull();
        assertThat(parent.getLinkedChildren()).isNull();
    }

    @Test
    void ensureInverseClearable_acceptsOptionalInverseOnLinkedChild() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);
        RelatedWriteMetadataResolver.SupportedRelatedRelationship rel =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        // Linked-child inverse is optional @ManyToOne with nullable JoinColumn; clearable.
        resolver.ensureInverseClearable(rel);  // does not throw
    }

    @Test
    void childBelongsToParent_returnsTrueWhenInverseMatchesParentId() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);
        RelatedWriteMetadataResolver.SupportedRelatedRelationship rel =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        MutationLinkedParentFixture parent = metadata.create(MutationLinkedParentFixture.class);
        MutationLinkedChildFixture child = metadata.create(MutationLinkedChildFixture.class);
        child.setLinkedParent(parent);

        assertThat(resolver.childBelongsToParent(rel, parent, child)).isTrue();
    }

    @Test
    void childBelongsToParent_returnsFalseWhenInverseIsNullOrMismatched() {
        MetaClass parentMetaClass = metadata.getClass(MutationLinkedParentFixture.class);
        RelatedWriteMetadataResolver.SupportedRelatedRelationship rel =
                resolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, "linkedChildren");

        MutationLinkedParentFixture parent = metadata.create(MutationLinkedParentFixture.class);
        MutationLinkedParentFixture otherParent = metadata.create(MutationLinkedParentFixture.class);
        MutationLinkedChildFixture orphan = metadata.create(MutationLinkedChildFixture.class);
        MutationLinkedChildFixture otherChild = metadata.create(MutationLinkedChildFixture.class);
        otherChild.setLinkedParent(otherParent);

        assertThat(resolver.childBelongsToParent(rel, parent, orphan)).isFalse();
        assertThat(resolver.childBelongsToParent(rel, parent, otherChild)).isFalse();
    }
}
