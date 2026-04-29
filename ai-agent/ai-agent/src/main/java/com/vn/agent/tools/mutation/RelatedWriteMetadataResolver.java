package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import jakarta.persistence.CascadeType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Objects;

/**
 * Verified relationship-metadata authority for {@code add_related_record} and
 * {@code remove_related_record} (Plan 11-07B). {@link BuiltInMutationTools} delegates
 * EVERY relationship interpretation to this resolver — no annotation/metamodel traversal
 * happens inline in the tool methods.
 *
 * <h2>Verified Jmix 2.8 metadata APIs (Plan 11-07B Task 0)</h2>
 * <ul>
 *     <li>{@link MetaClass#findProperty(String)} — null when the relationship name is unknown
 *         (verified Jmix 2.8.0 source).</li>
 *     <li>{@link MetaProperty#getRange()} → {@link Range#isClass()} +
 *         {@link Range#getCardinality()} — distinguishes scalar / to-one / to-many. The
 *         resolver requires {@code isClass() == true} on the parent side and
 *         {@code Cardinality.isMany() == true}.</li>
 *     <li>{@link MetaProperty#getType()} returns {@link MetaProperty.Type#COMPOSITION}
 *         when the field carries Jmix's {@code @Composition} annotation
 *         (MetaModelLoader.assignPropertyType, Jmix 2.8.0 line 958). Composition is rejected
 *         outright for related writes — composition implies cascading lifecycle which v1.1
 *         intentionally does NOT expose to LLM.</li>
 *     <li>{@link MetaProperty#getInverse()} — returns the inverse MetaProperty resolved by
 *         Jmix from JPA {@code mappedBy} on the owning side (verified Jmix 2.8.0
 *         {@code MetaProperty.getInverse} javadoc + MetaModelLoader resolves
 *         {@code @OneToMany.mappedBy()} / {@code @OneToOne.mappedBy()} into the inverse pointer
 *         at line 711+). Returns null when ambiguous or unresolvable; resolver fails closed.</li>
 *     <li>{@link MetaProperty#getAnnotatedElement()} +
 *         {@link AnnotatedElement#getAnnotation(Class)} — used as cross-check for
 *         {@code @OneToMany.orphanRemoval()}, {@code @OneToMany.cascade()} delete-capable,
 *         and {@code @ManyToOne.optional()} / {@code @JoinColumn.nullable()} (required-inverse
 *         detection). The resolver inspects the parent-side property AND the child-side
 *         inverse property's annotated elements separately.</li>
 *     <li>{@link MetaProperty#isMandatory()} — Jmix-level required check on the inverse
 *         ({@code @NotNull} / {@code optional=false}); used as a third corroboration alongside
 *         the JPA annotations above.</li>
 *     <li>{@link EntityValues#setValue(Object, String, Object)} +
 *         {@link EntityValues#getValue(Object, String)} — ONLY mutate the child-side inverse;
 *         the resolver NEVER calls {@code setValue} on the parent collection attribute and
 *         NEVER calls {@code remove}/{@code delete}.</li>
 * </ul>
 *
 * <h2>Resolver decision matrix</h2>
 * Inputs: {@code parentMetaClass}, {@code relationshipName}.
 * Outputs: a {@link SupportedRelatedRelationship} for the supported branch, or
 * {@link ToolUserError} ({@code validation_failed}) for any unsupported metadata. v1.1 supports
 * exactly ONE shape:
 * <ol>
 *     <li>Parent property exists and {@link MetaClass#findProperty(String)} is non-null.</li>
 *     <li>Parent property is a relationship ({@code property.getRange().isClass() == true}).</li>
 *     <li>Parent property is collection-valued
 *         ({@code property.getRange().getCardinality().isMany() == true}).</li>
 *     <li>Parent property is NOT {@link MetaProperty.Type#COMPOSITION}.</li>
 *     <li>Parent property's annotated element carries {@link OneToMany} with non-blank
 *         {@code mappedBy} (mapped-by ownership), and NOT {@code orphanRemoval=true}, NOT
 *         {@code cascade} containing {@link CascadeType#REMOVE} or {@link CascadeType#ALL}.</li>
 *     <li>Inverse property is non-null
 *         ({@link MetaProperty#getInverse()} returns the child-side property), is a class
 *         relationship, is single-valued (NOT {@code Cardinality.isMany()}), and is annotated
 *         {@link ManyToOne} or {@link OneToOne}.</li>
 *     <li>Many-to-many ({@code @ManyToMany}), unidirectional collections (mappedBy blank
 *         OR inverse null), parent-side to-one, and ambiguous metadata are rejected.</li>
 * </ol>
 *
 * <p><b>remove-time required-inverse check.</b> {@link #ensureInverseClearable} verifies the
 * child-side inverse is nullable: {@code @ManyToOne(optional=true)} (default) AND not
 * {@code @JoinColumn(nullable=false)} AND not Jmix-mandatory. Required inverses cannot be
 * cleared without violating not-null constraints, so {@code remove_related_record} rejects.
 *
 * <p><b>P-22:</b> No method here concatenates LLM-supplied attribute values into error/result
 * prose. Every error message is a metamodel-only canned string.
 */
@Component
public class RelatedWriteMetadataResolver {

    /**
     * Verified support-matrix result.
     *
     * @param parentProperty the parent collection MetaProperty (mappedBy owning side)
     * @param childMetaClass the inverse-side MetaClass (the child entity type)
     * @param childInverseProperty the single-valued child-side inverse MetaProperty
     */
    public record SupportedRelatedRelationship(
            MetaProperty parentProperty,
            MetaClass childMetaClass,
            MetaProperty childInverseProperty) { }

    /**
     * Single entry point for {@code add_related_record} / {@code remove_related_record}.
     * Returns a {@link SupportedRelatedRelationship} when ALL gates pass; throws
     * {@link ToolUserError}({@code validation_failed}) otherwise. No host save can ever
     * occur on a {@code ToolUserError} because the tool methods call this method BEFORE
     * idempotency reservation.
     */
    public SupportedRelatedRelationship resolveSupportedRelatedWriteRelationship(
            MetaClass parentMetaClass, String relationshipName) {

        Objects.requireNonNull(parentMetaClass, "parentMetaClass");
        if (relationshipName == null || relationshipName.isBlank()) {
            throw unsupportedRelationship();
        }

        MetaProperty parentProperty = parentMetaClass.findProperty(relationshipName);
        if (parentProperty == null) {
            throw unsupportedRelationship();
        }
        if (!parentProperty.getRange().isClass()) {
            throw unsupportedRelationship();
        }
        Range.Cardinality cardinality = parentProperty.getRange().getCardinality();
        if (cardinality == null || !cardinality.isMany()) {
            // parent-side to-one is not a related-write target; v1.1 does not support
            // mutating to-one references via add/remove_related_record.
            throw unsupportedRelationship();
        }
        if (parentProperty.getType() == MetaProperty.Type.COMPOSITION) {
            throw unsupportedRelationship();
        }
        if (isCompositionOrDeleteCapable(parentProperty)) {
            throw unsupportedRelationship();
        }
        // Mapped-by ownership: parent side must declare @OneToMany(mappedBy=...) — the
        // collection is the inverse side of the foreign-key. @ManyToMany and unidirectional
        // collections (no mappedBy) are rejected.
        AnnotatedElement parentElement = parentProperty.getAnnotatedElement();
        OneToMany oneToMany = parentElement == null ? null : parentElement.getAnnotation(OneToMany.class);
        if (oneToMany == null) {
            throw unsupportedRelationship();
        }
        if (oneToMany.mappedBy() == null || oneToMany.mappedBy().isBlank()) {
            throw unsupportedRelationship();
        }

        MetaProperty inverse = parentProperty.getInverse();
        if (inverse == null) {
            // Jmix could not resolve the inverse pointer (ambiguous metadata) — fail closed.
            throw unsupportedRelationship();
        }
        if (!inverse.getRange().isClass()) {
            throw unsupportedRelationship();
        }
        Range.Cardinality inverseCardinality = inverse.getRange().getCardinality();
        if (inverseCardinality != null && inverseCardinality.isMany()) {
            // The inverse-side must be single-valued (the child holds the FK); a collection
            // inverse is the @ManyToMany shape and is rejected.
            throw unsupportedRelationship();
        }
        AnnotatedElement inverseElement = inverse.getAnnotatedElement();
        boolean inverseIsManyToOne = inverseElement != null && inverseElement.isAnnotationPresent(ManyToOne.class);
        boolean inverseIsOneToOne = inverseElement != null && inverseElement.isAnnotationPresent(OneToOne.class);
        if (!inverseIsManyToOne && !inverseIsOneToOne) {
            throw unsupportedRelationship();
        }

        MetaClass childMetaClass = parentProperty.getRange().asClass();
        return new SupportedRelatedRelationship(parentProperty, childMetaClass, inverse);
    }

    /**
     * Cross-check on the parent collection property: rejects when the relationship is
     * delete-capable in any way the LLM should not be allowed to trigger via add/remove.
     * Triggers on {@code orphanRemoval=true}, on {@code @Composition} (defense in depth —
     * the caller should already have rejected COMPOSITION via {@link MetaProperty#getType()}),
     * and on cascade containing {@link CascadeType#REMOVE} or {@link CascadeType#ALL}.
     *
     * <p><b>Why orphanRemoval rejects ADD and not just REMOVE:</b> mutating the inverse on a
     * child whose parent collection has {@code orphanRemoval=true} can move the child between
     * parents and accidentally delete the original orphan. v1.1 does not ship those semantics.
     */
    public boolean isCompositionOrDeleteCapable(MetaProperty parentProperty) {
        if (parentProperty.getType() == MetaProperty.Type.COMPOSITION) {
            return true;
        }
        AnnotatedElement element = parentProperty.getAnnotatedElement();
        if (element == null) {
            return false;
        }
        OneToMany oneToMany = element.getAnnotation(OneToMany.class);
        if (oneToMany != null) {
            if (oneToMany.orphanRemoval()) {
                return true;
            }
            for (CascadeType cascade : oneToMany.cascade()) {
                if (cascade == CascadeType.ALL || cascade == CascadeType.REMOVE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Set the child-side inverse on {@code child} to point to {@code parent}. Caller already
     * verified the relationship via {@link #resolveSupportedRelatedWriteRelationship}; this
     * helper does NOT re-validate. Never modifies {@code parent}'s collection attribute.
     */
    public void wireInverseReference(SupportedRelatedRelationship relationship,
                                     Object parent,
                                     Object child) {
        EntityValues.setValue(child, relationship.childInverseProperty().getName(), parent);
    }

    /**
     * Clear the child-side inverse on {@code child}. Caller MUST have first invoked
     * {@link #ensureInverseClearable} so a not-null violation cannot reach the host save
     * boundary; this helper is mechanical only.
     */
    public void clearInverseReference(SupportedRelatedRelationship relationship,
                                      Object child) {
        EntityValues.setValue(child, relationship.childInverseProperty().getName(), null);
    }

    /**
     * Reject a {@code remove_related_record} when clearing the child-side inverse would
     * violate a not-null constraint. The child still belongs to its parent, but v1.1 cannot
     * unlink without delete-on-orphan — and delete-on-orphan was already rejected upstream.
     *
     * <p>Triggers when ANY of:
     * <ul>
     *     <li>{@link MetaProperty#isMandatory()} on the inverse property.</li>
     *     <li>{@link ManyToOne#optional()} == false on the inverse annotated element.</li>
     *     <li>{@link OneToOne#optional()} == false on the inverse annotated element.</li>
     *     <li>{@link JoinColumn#nullable()} == false on any {@link JoinColumn} on the
     *         inverse annotated element.</li>
     * </ul>
     */
    public void ensureInverseClearable(SupportedRelatedRelationship relationship) {
        MetaProperty inverse = relationship.childInverseProperty();
        if (inverse.isMandatory()) {
            throw unsupportedRelationship();
        }
        AnnotatedElement element = inverse.getAnnotatedElement();
        if (element == null) {
            return;
        }
        ManyToOne manyToOne = element.getAnnotation(ManyToOne.class);
        if (manyToOne != null && !manyToOne.optional()) {
            throw unsupportedRelationship();
        }
        OneToOne oneToOne = element.getAnnotation(OneToOne.class);
        if (oneToOne != null && !oneToOne.optional()) {
            throw unsupportedRelationship();
        }
        JoinColumn joinColumn = element.getAnnotation(JoinColumn.class);
        if (joinColumn != null && !joinColumn.nullable()) {
            throw unsupportedRelationship();
        }
    }

    /**
     * Read the child's current inverse FK and return whether it points to {@code parent}.
     * Used by {@code remove_related_record} so a non-member child surfaces as
     * {@code not_found} (the structured error to call get_record/find_records again) instead
     * of a silent no-op or constraint violation. Never throws on a missing/null inverse — a
     * null inverse simply means "child is orphaned" and is reported as not-a-member.
     */
    public boolean childBelongsToParent(SupportedRelatedRelationship relationship,
                                        Object parent,
                                        Object child) {
        Object current = EntityValues.getValue(child, relationship.childInverseProperty().getName());
        if (current == null) {
            return false;
        }
        Object currentId = EntityValues.getId(current);
        Object parentId = EntityValues.getId(parent);
        return currentId != null && currentId.equals(parentId);
    }

    /**
     * Fail-closed canned error. P-22-safe: contains no LLM-supplied attribute values, no
     * raw exception text, and no entity name leak (the entity name has already been
     * acknowledged as visible by the caller, so omitting it here keeps the message stable).
     */
    private static ToolUserError unsupportedRelationship() {
        return new ToolUserError("validation_failed",
                "relationship is not supported by add/remove_related_record",
                List.of(
                        "use describe_entity to inspect the entity's relationships",
                        "v1.1 supports only non-composition parent @OneToMany(mappedBy) "
                                + "with a child-side @ManyToOne or @OneToOne inverse"));
    }
}
